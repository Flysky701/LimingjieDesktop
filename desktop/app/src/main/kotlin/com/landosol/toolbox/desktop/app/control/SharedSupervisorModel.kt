package com.landosol.toolbox.desktop.app.control

import com.landosol.toolbox.desktop.device.DeviceDescriptor
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

internal data class RememberedRunConfiguration(
    val deviceId: String, val bootId: String, val sessionId: String, val boundAccountId: Long? = null,
)

/** Owns runtime workers only. Shared account data belongs to the host, never to this registry. */
@OptIn(ExperimentalCoroutinesApi::class)
class SharedSupervisorModel(
    override val global: ControlSessionPort,
    endpoint: SharedDataEndpoint,
    launcher: WorkerLauncher,
    private val root: Path,
    private val scope: CoroutineScope,
    private val inventory: RuntimeDeviceInventory = SharedDeviceInventory(global),
    private val workers: RuntimeWorkerFactory = RuntimeWorkerFactory { launcher.startRuntime(it, endpoint) },
) : RunSessionSupervisor {
    private val mutex = Mutex()
    private val discoveryMutex = Mutex()
    private val deviceLocks = ConcurrentHashMap<String, Mutex>()
    private val known = linkedMapOf<String, RuntimeDeviceTarget>()
    private val configurations = mutableMapOf<String, RememberedRunConfiguration>()
    private val watchers = mutableMapOf<String, Job>()
    private val mutableSessions = MutableStateFlow<List<DeviceControlSession>>(emptyList())
    override val sessions = mutableSessions.asStateFlow()
    private val mutableDevices = MutableStateFlow<List<ManagedRuntimeDevice>>(emptyList())
    override val devices = mutableDevices.asStateFlow()
    private val selected = MutableStateFlow<String?>(null)
    override val selectedId = selected.asStateFlow()
    override val selectedSessionId = selectedId
    private val errors = MutableStateFlow<String?>(null)
    override val error = errors.asStateFlow()
    private val dirty = MutableStateFlow<Set<String>>(emptySet())
    override val dirtyInstances = dirty.asStateFlow()
    private var closing = false
    private val shutdownDone = CompletableDeferred<Unit>()
    override val busy: StateFlow<Boolean> = sessions.flatMapLatest { items ->
        if (items.isEmpty()) flowOf(false)
        else combine(items.map { it.link.snapshots }) { states -> states.any { ControlSnapshot(it).busy } }
    }.stateIn(scope, SharingStarted.Eagerly, false)

    /** Restore display metadata only. Opening the application never launches a device worker. */
    override suspend fun restore() = withContext(Dispatchers.IO) {
        mutex.withLock {
            check(!closing) { "总控正在退出" }
            val file = root.resolve("device-sessions.json")
            if (Files.exists(file)) {
                val saved = Json.parseToJsonElement(Files.readString(file)).jsonObject
                val rows = saved["devices"] as? JsonArray ?: JsonArray(emptyList())
                rows.forEach { row ->
                    val value = ControlModelCodec.decode<RuntimeDeviceTarget>(row)
                    // Old boot proofs are for display, not permission to reconnect or reuse an account.
                    known.putIfAbsent(value.deviceId, value.copy(connectionState = "offline"))
                }
                (saved["configurations"] as? JsonArray)?.forEach { row ->
                    val value = ControlModelCodec.decode<RememberedRunConfiguration>(row)
                    require(UUID.fromString(value.sessionId).toString() == value.sessionId) { "保存的运行会话身份无效" }
                    configurations.putIfAbsent(value.deviceId, value)
                }
                publishDevices()
            }
        }
    }

    override suspend fun refreshDevices(force: Boolean): Unit = discoveryMutex.withLock {
        val checked = mutex.withLock { known.keys.toSet() }
        val discovered = inventory.discover(force)
        val missing = mutex.withLock {
            known.filter { (id, previous) -> id in checked && discovered.none { target ->
                target.deviceId == id || (target.aliases + target.serial).any { it in previous.aliases || it == previous.serial }
            } }.keys.toSet()
        }
        retireReplacedSessions(discovered)
        retireMissingSessions(missing)
        mutex.withLock {
            check(!closing) { "总控正在退出" }
            val active = sessions.value.map { it.deviceId }.toSet()
            known.keys.removeAll(missing - active)
            known.replaceAll { _, value -> value.copy(connectionState = "offline") }
            discovered.forEach(::rememberDevice)
            publishDevices()
            saveMetadata()
        }
        Unit
    }

    override suspend fun connectDevice(serial: String): String {
        val target = inventory.resolve(serial.trim())
        require(target.bootId != null && target.connectionState == "device") { "无法确认设备身份，请刷新后重试" }
        retireReplacedSessions(listOf(target))
        return deviceLocks.computeIfAbsent(target.deviceId) { Mutex() }.withLock {
            var created = false
            var restoreBinding: Long? = null
            val item = mutex.withLock {
                check(!closing) { "总控正在退出" }
                rememberDevice(target)
                val existing = sessions.value.firstOrNull { it.deviceId == target.deviceId }
                check(existing == null || existing.link.connection.value !in setOf(WorkerConnectionStatus.LOST, WorkerConnectionStatus.CLOSED)) {
                    "此设备会话已断开，请使用重新连接"
                }
                (existing ?: run {
                    val saved = configurations[target.deviceId]?.takeIf { it.bootId == target.bootId }
                    val id = saved?.sessionId ?: UUID.randomUUID().toString()
                    restoreBinding = saved?.boundAccountId
                    configurations[target.deviceId] = saved ?: RememberedRunConfiguration(target.deviceId, target.bootId, id)
                    val spec = RuntimeWorkerSpec(id, target, root.resolve("sessions").resolve(id))
                    val link = workers.start(spec)
                    DeviceControlSession(id, target.deviceId, target.name, target.serial, null, link).also {
                        mutableSessions.value += it
                        created = true
                        watchers[id] = scope.launch {
                            combine(link.snapshots, link.connection) { _, _ -> Unit }.collect {
                                mutex.withLock { publishDevices() }
                            }
                        }
                    }
                }).also { selectUnlocked(it.id); publishDevices() }
            }
            if (created) {
                try {
                    item.link.request(ControlOps.DEVICE_CONNECT, buildJsonObject { put("serial", target.serial) })
                    restoreBinding?.let { account ->
                        try { bind(item, account) }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (failure: Exception) {
                            // Deleted/unavailable accounts must not trap the user in an unopenable saved tab.
                            bind(item, null)
                            reportError("${item.name} 的原账号绑定未恢复，请重新选择账号：${failure.message}")
                        }
                    }
                    mutex.withLock { saveMetadata(); publishDevices() }
                } catch (failure: Throwable) {
                    // Cancellation or failed connect must not leave an invisible owning worker behind.
                    withContext(NonCancellable) {
                        try { remove(item) } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
                    }
                    throw failure
                }
            }
            item.id
        }
    }

    override suspend fun bindAccount(sessionId: String, accountId: Long?) = withSession(sessionId) { item ->
        bind(item, accountId)
    }

    private suspend fun bind(item: DeviceControlSession, accountId: Long?) {
        require(accountId == null || accountId > 0) { "账号无效" }
        // The worker serializes this with task admission and rejects changes while busy.
        item.link.request(ControlOps.ACCOUNT_SELECT, buildJsonObject { put("id", accountId) })
        mutex.withLock {
            mutableSessions.value = sessions.value.map {
                if (it.link.workerRunId == item.link.workerRunId) it.copy(boundAccountId = accountId) else it
            }
            configurations[item.deviceId]?.let { configurations[item.deviceId] = it.copy(boundAccountId = accountId) }
            publishDevices()
            saveMetadata()
        }
    }

    override suspend fun disconnectDevice(sessionId: String) = withSession(sessionId) { item ->
        item.link.request(ControlOps.DEVICE_DISCONNECT)
        remove(item)
    }

    override suspend fun closeSession(sessionId: String) = withSession(sessionId) { remove(it) }

    override suspend fun reconnectSession(sessionId: String) {
        val previous = mutex.withLock { sessions.value.firstOrNull { it.id == sessionId } } ?: return
        closeSession(sessionId)
        // connectDevice restores only a freshly verified matching boot, including deleted-account fallback.
        connectDevice(previous.serial)
    }

    override suspend fun selectSession(sessionId: String) = mutex.withLock { selectUnlocked(sessionId) }

    private fun selectUnlocked(id: String) {
        if (sessions.value.none { it.id == id }) return
        selected.value = id
        sessions.value.forEach { it.link.setSelected(it.id == id) }
    }

    private suspend fun withSession(id: String, action: suspend (DeviceControlSession) -> Unit) {
        val original = mutex.withLock { sessions.value.firstOrNull { it.id == id } } ?: return
        deviceLocks.computeIfAbsent(original.deviceId) { Mutex() }.withLock {
            val current = mutex.withLock {
                check(!closing) { "总控正在退出" }
                sessions.value.firstOrNull { it.link.workerRunId == original.link.workerRunId }
            } ?: return@withLock
            action(current)
        }
    }

    private suspend fun remove(item: DeviceControlSession) {
        item.link.shutdown()
        mutex.withLock {
            mutableSessions.value = sessions.value.filterNot { it.link.workerRunId == item.link.workerRunId }
            watchers.remove(item.id)?.cancel()
            markDirty(item.id, false)
            if (selected.value == item.id) {
                selected.value = null
                sessions.value.firstOrNull()?.let { selectUnlocked(it.id) }
            }
            publishDevices()
            saveMetadata()
        }
    }

    override suspend fun stopAll(): Unit = supervisorScope {
        sessions.value.map { item -> async {
            if (item.link.connection.value == WorkerConnectionStatus.CONNECTED) {
                try { item.link.request(ControlOps.STOP) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { reportError("${item.name}：${failure.message}") }
            }
        } }.awaitAll()
        Unit
    }

    override suspend fun shutdown(): Unit = withContext(NonCancellable) {
        val copy = mutex.withLock {
            if (closing) null else { closing = true; sessions.value }
        }
        if (copy == null) { shutdownDone.await(); return@withContext }
        try {
            val outcomes = supervisorScope { copy.map { async { runCatching { remove(it) } } }.awaitAll() }
            outcomes.firstNotNullOfOrNull { it.exceptionOrNull() }?.let { throw it }
            shutdownDone.complete(Unit)
        } catch (failure: Throwable) {
            shutdownDone.completeExceptionally(failure)
            throw failure
        }
    }

    override fun markDirty(id: String, dirty: Boolean) { this.dirty.update { if (dirty) it + id else it - id } }
    override fun reportError(message: String) { errors.value = message }
    override fun dismissError() { errors.value = null }

    private suspend fun retireMissingSessions(missing: Set<String>) {
        val candidates = mutex.withLock { sessions.value.filter { it.deviceId in missing } }
        candidates.forEach { item ->
            val deviceLock = deviceLocks.computeIfAbsent(item.deviceId) { Mutex() }
            // Do not wait behind a connection and then discard the session it just established.
            if (!deviceLock.tryLock()) {
                reportError("${item.name} 正在处理设备操作，记录暂时保留，请稍后重新检测。")
                return@forEach
            }
            try {
                val current = mutex.withLock {
                    check(!closing) { "总控正在退出" }
                    sessions.value.firstOrNull { it.link.workerRunId == item.link.workerRunId }
                } ?: return@forEach
                val snapshot = ControlSnapshot(current.link.snapshots.value)
                val phase = (snapshot.section("console")["device"] as? JsonObject)?.wireString("_type")
                if (current.id in dirty.value || snapshot.busy || current.link.connection.value == WorkerConnectionStatus.STARTING ||
                    phase in setOf("Connecting", "Verifying", "Reconnecting")) {
                    reportError("${current.name} 本次未检测到；请先结束运行或连接、保存或取消修改，再重新检测以移除记录。")
                    return@forEach
                }
                // The worker checks task admission again before disconnecting, including a concurrent start.
                if (current.link.connection.value == WorkerConnectionStatus.CONNECTED) current.link.request(ControlOps.DEVICE_DISCONNECT)
                remove(current)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { reportError("${item.name} 的记录暂未移除：${failure.message}") }
            finally { deviceLock.unlock() }
        }
    }

    /** A fresh boot at a worker's own transport invalidates that runtime, not its saved data.
     * Retire it before adding the replacement; never transfer an old boot's account binding.
     */
    private suspend fun retireReplacedSessions(targets: List<RuntimeDeviceTarget>) {
        val replaced = mutex.withLock {
            check(!closing) { "总控正在退出" }
            sessions.value.filter { item ->
                val previous = known[item.deviceId]
                previous?.bootId != null && targets.any { target ->
                    target.connectionState == "device" && target.bootId != null && target.bootId != previous.bootId &&
                        (item.serial == target.serial || item.serial in target.aliases)
                }
            }.map { it.id }
        }
        replaced.forEach { id -> withSession(id) { item ->
            check(id !in dirty.value) { "模拟器已重新启动，请先保存或取消旧运行页的修改，再重新连接。" }
            check(!ControlSnapshot(item.link.snapshots.value).busy) { "模拟器已重新启动，请先停止旧运行页的任务，再重新连接。" }
            remove(item)
        } }
    }

    private fun rememberDevice(target: RuntimeDeviceTarget) {
        val previous = known[target.deviceId]
        // Drop stale offline address rows only when no session still owns their previous boot.
        known.entries.removeAll { (id, value) -> id != target.deviceId &&
            value.aliases.any { it in target.aliases } && sessions.value.none { it.deviceId == id } }
        known[target.deviceId] = target.copy(aliases = target.aliases + previous?.aliases.orEmpty())
    }

    private fun publishDevices() {
        mutableDevices.value = known.values.map { device ->
            val item = sessions.value.firstOrNull { it.deviceId == device.deviceId }
            val snapshot = item?.let { ControlSnapshot(it.link.snapshots.value) }
            val state = snapshot?.section("console")?.get("device") as? JsonObject
            val phase = state?.wireString("_type")
            val ready = phase == "Ready"
            val transport = item?.link?.connection?.value
            val connecting = transport == WorkerConnectionStatus.STARTING ||
                (transport == WorkerConnectionStatus.CONNECTED && phase in setOf("Connecting", "Verifying", "Lost", "Reconnecting"))
            val status = when {
                transport == WorkerConnectionStatus.LOST -> "会话已断开"
                transport == WorkerConnectionStatus.CLOSED -> "会话已关闭"
                transport == WorkerConnectionStatus.STARTING -> "正在打开"
                snapshot?.busy == true -> "运行中"
                ready -> "已连接"
                phase == "Verifying" -> state?.wireString("detail") ?: "正在核验设备"
                phase == "Connecting" -> "正在连接"
                phase in setOf("Lost", "Reconnecting") -> "正在重新连接"
                phase == "Failed" -> "连接失败"
                device.connectionState != "device" -> "离线"
                item != null -> "连接中或已断开"
                else -> "未连接"
            }
            ManagedRuntimeDevice(device.deviceId, item?.serial ?: device.serial, device.name, device.aliases,
                item?.id, item?.boundAccountId, ready && item?.link?.connection?.value == WorkerConnectionStatus.CONNECTED,
                snapshot?.busy == true, status, connecting)
        }
    }

    private suspend fun saveMetadata() = withContext(Dispatchers.IO) {
        Files.createDirectories(root)
        val document = buildJsonObject {
            put("version", 1)
            put("devices", ControlModelCodec.encode(known.values.toList()))
            put("configurations", ControlModelCodec.encode(configurations.values.toList()))
        }
        val temporary = Files.createTempFile(root, "devices-", ".writing")
        try {
            Files.writeString(temporary, document.toString())
            Files.move(temporary, root.resolve("device-sessions.json"), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { Files.deleteIfExists(temporary) }
    }
}

/** The boot UUID proves alias equivalence for this boot only, never permanent device identity. */
internal class SharedDeviceInventory(private val global: ControlSessionPort) : RuntimeDeviceInventory {
    override suspend fun discover(force: Boolean): List<RuntimeDeviceTarget> {
        val result = global.request(ControlOps.DEVICE_DISCOVER, buildJsonObject { put("force", force) })
        val rows = checkNotNull(result["devices"]) { "设备检测未返回完整结果，原有记录已保留" }
        return ControlModelCodec.decode<List<DeviceDescriptor>>(rows).map(::target)
    }

    override suspend fun resolve(serial: String): RuntimeDeviceTarget {
        require(serial.isNotBlank()) { "请先选择设备或填写地址" }
        val result = global.request(SharedSupervisorOps.DEVICE_RESOLVE, buildJsonObject { put("serial", serial) })
        val descriptor = ControlModelCodec.decode<DeviceDescriptor>(result.getValue("device"))
        check(descriptor.bootId != null && descriptor.state == "device") { "无法确认设备启动身份" }
        return target(descriptor)
    }

    private fun target(value: DeviceDescriptor): RuntimeDeviceTarget {
        val boot = value.bootId?.let(com.landosol.toolbox.desktop.platform.UserResourceLeases::canonicalBootId)
        val key = boot?.let { "boot:$it" } ?: "unverified:${value.serial}"
        val id = UUID.nameUUIDFromBytes(key.toByteArray(Charsets.UTF_8)).toString()
        return RuntimeDeviceTarget(id, value.serial, value.label, value.aliases + value.serial, boot, value.state)
    }
}
