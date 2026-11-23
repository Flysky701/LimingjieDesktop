package com.landosol.toolbox.desktop.device

import com.landosol.toolbox.desktop.platform.ResourceLease
import com.landosol.toolbox.desktop.platform.UserResourceLeases

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.xml.sax.SAXException
import java.io.IOException
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

class AdbDeviceService(
    private val adb: AdbExecutor,
    private val dataDirectory: Path,
    private val gamePackages: Set<String>,
    private val onEvent: (String) -> Unit = {},
    private val onLost: (String) -> Unit = {},
    private val touchFactory: TouchConnectionFactory = MaaTouchConnection,
    private val leases: UserResourceLeases = UserResourceLeases(),
    private val expectedBootId: String? = System.getProperty("limingjie.runtime.boot-id"),
    private val verifiedPackageCache: Path = dataDirectory.resolve("cache/verified-packages"),
) : DeviceService {
    private class Session(val serial: String, val touchMode: TouchMode, val capturer: ScreenCapturer, @Volatile var ready: DeviceReady, val bootId: String, @Volatile var touch: TouchConnection?, val lease: ResourceLease) {
        val captures = Mutex()
        @Volatile var capturePaused = false
        @Volatile var checkingTransport = false
        var failures = 0
        var failureSince = 0L
        @Volatile var lastTransportRecoveredAt = 0L
        var lastCaptureCompleted = 0L
        @Volatile var frameSize = ready.width to ready.height
        var touchRestarted = false
        var touchRestartedAt = 0L
    }
    @Volatile internal var clockNanos: () -> Long = System::nanoTime
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lifecycle = Mutex()
    private val closed = AtomicBoolean()
    private val connectingJobs = ConcurrentHashMap.newKeySet<Job>()
    private class Cleanup(val touch: TouchConnection?, val lease: ResourceLease)
    private val pendingCleanup = ConcurrentHashMap.newKeySet<Cleanup>()
    private val mutableState = MutableStateFlow<DeviceConnectionState>(DeviceConnectionState.Disconnected)
    override val state: StateFlow<DeviceConnectionState> = mutableState.asStateFlow()
    private val identities = DeviceIdentityReader(adb, dataDirectory, gamePackages, ::emit, verifiedPackageCache)
    @Volatile private var session: Session? = null
    @Volatile private var cachedForeground: String? = null
    @Volatile private var foregroundUpdated = 0L
    private var monitor: Job? = null
    private var reconnect: Job? = null
    override val foregroundPackage: String? get() = cachedForeground.takeIf { session != null && clockNanos() - foregroundUpdated <= FOREGROUND_CACHE_TTL_NANOS }

    private suspend fun <T> connectionOperation(block: suspend () -> T): T = coroutineScope {
        val operation = currentCoroutineContext().job
        connectingJobs.add(operation)
        try { block() } finally { connectingJobs.remove(operation) }
    }

    @Volatile override var discoveryNotice: String? = null
        private set

    override suspend fun discover(): List<DeviceDescriptor> = connectionOperation { lifecycle.withLock {
        check(!closed.get()) { "设备服务已关闭" }
        // Discovery is read-only: do not guess ports and register extra transports in the global server.
        discoveryNotice = null
        val transports = DeviceParsers.devices(adb.execute(listOf("devices", "-l")).requireSuccess().text())
        val listed = transports.filter { it.state == "device" }
            .sortedWith(compareBy<DeviceDescriptor> { it.serial != session?.serial }
                .thenBy { ':' !in it.serial }.thenBy { it.serial })
        if (listed.isEmpty() && transports.isNotEmpty()) {
            val states = transports.joinToString("、") { "${it.serial}（${it.state}）" }
            discoveryNotice = buildString {
                append("ADB 已发现设备，尚不可连接：$states。")
                if (transports.any { it.state == "offline" }) append("请等待模拟器完成启动后重新检测。如果一直离线，请重启对应模拟器实例。")
                if (transports.any { it.state == "unauthorized" }) append("请在对应设备上允许 ADB 调试。")
            }.also(::emit)
        }
        val deduplicated = linkedMapOf<String, DeviceDescriptor>()
        for (device in listed) {
            val bootId = shellText(device.serial, "cat", "/proc/sys/kernel/random/boot_id")
                ?.takeIf { Regex("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}").matches(it) }
            // ro.serialno can be empty (MuMu), or cloned. Model/serial alone never prove the same instance.
            val key = bootId?.lowercase()?.let { "boot:$it" } ?: "transport:${device.serial}"
            val previous = deduplicated[key]
            deduplicated[key] = previous?.copy(aliases = previous.aliases + device.serial) ?: device.copy(bootId = bootId?.lowercase())
        }
        deduplicated.values.toList()
    } }

    override suspend fun resolveDevice(serial: String): DeviceDescriptor = connectionOperation { lifecycle.withLock {
        check(!closed.get()) { "设备服务已关闭" }
        require(serial.isNotBlank() && serial.none { it.isWhitespace() || it == '\u0000' }) { "设备地址无效" }
        ensureTcpTransport(serial)
        val boot = readBootId(serial)
        val label = shellText(serial, "getprop", "ro.product.model")?.takeIf { it.isNotBlank() } ?: serial
        DeviceDescriptor(serial, label, "device", setOf(serial), boot)
    } }

    override suspend fun connect(serial: String, touchMode: TouchMode): Unit = connectionOperation { lifecycle.withLock {
        check(!closed.get()) { "设备服务已关闭" }
        require(serial.isNotBlank() && serial.none { it.isWhitespace() || it == '\u0000' }) { "设备地址无效" }
        if (session?.let { it.serial == serial && it.touchMode == touchMode } == true && mutableState.value is DeviceConnectionState.Ready) return@withLock
        reconnect?.cancelAndJoin(); reconnect = null
        stopSession("切换设备，任务已停止")
        mutableState.value = DeviceConnectionState.Connecting(serial)
        try {
            activate(establish(serial, touchMode, true, expectedBootId))
        } catch (cancelled: CancellationException) { mutableState.value = DeviceConnectionState.Disconnected; throw cancelled }
        catch (error: Exception) { mutableState.value = DeviceConnectionState.Failed(error.message ?: "连接模拟器失败") }
    } }

    private class BootChanged : IllegalStateException("模拟器启动身份已变化，请重新打开设备会话并确认账号绑定")

    private fun verifyBoot(actual: String, expected: String?) {
        if (expected != null && actual != expected) throw BootChanged()
    }

    private suspend fun disconnectTcpTransport(serial: String) {
        if (':' !in serial) return
        val result = adb.execute(listOf("disconnect", serial), timeoutMillis = 3000)
        if (result is AdbResult.Failure && result.kind == AdbFailureKind.ServerVersionConflict) {
            throw DeviceOperationException(result.detail, result.kind)
        }
    }

    private suspend fun ensureTcpTransport(serial: String) {
        if (':' !in serial) return
        val state = adb.execute(listOf("get-state"), serial, timeoutMillis = 3000)
        if (state is AdbResult.Failure && state.kind == AdbFailureKind.ServerVersionConflict) {
            throw DeviceOperationException(state.detail, state.kind)
        }
        val stateText = (state as? AdbResult.Success)?.text()?.trim()
        if (stateText == "device") return
        if (stateText == "offline" || (state as? AdbResult.Failure)?.kind == AdbFailureKind.DeviceOffline) {
            disconnectTcpTransport(serial)
        }
        val connected = adb.execute(listOf("connect", serial), timeoutMillis = 3000).requireSuccess()
        if (connected.text().contains("already connected", ignoreCase = true)) {
            val verified = (adb.execute(listOf("get-state"), serial, timeoutMillis = 3000) as? AdbResult.Success)?.text()?.trim()
            if (verified != "device") {
                disconnectTcpTransport(serial)
                adb.execute(listOf("connect", serial), timeoutMillis = 3000).requireSuccess()
            }
        }
    }

    private suspend fun establish(serial: String, touchMode: TouchMode, initial: Boolean, requiredBootId: String?): Session {
        ensureTcpTransport(serial)
        val bootCompleted = withTimeoutOrNull(120_000) {
            if (initial) mutableState.value = DeviceConnectionState.Verifying(serial, "模拟器启动中")
            var transientFailures = 0
            while (true) {
                val ready = try {
                    val done = adb.shell(serial, listOf("getprop", "sys.boot_completed")).requireSuccess().text().trim() == "1"
                    transientFailures = 0
                    done
                } catch (error: DeviceOperationException) {
                    if (!initial || error.adbFailure !in transientTransportFailures || ++transientFailures > INITIAL_BOOT_TRANSIENT_RETRIES) {
                        throw error
                    }
                    if (error.adbFailure == AdbFailureKind.DeviceOffline || error.adbFailure == AdbFailureKind.DeviceNotFound) {
                        try { ensureTcpTransport(serial) }
                        catch (retryError: DeviceOperationException) {
                            if (retryError.adbFailure == AdbFailureKind.ServerVersionConflict) throw retryError
                        }
                    }
                    false
                }
                if (ready) break
                delay(2000)
            }
            true
        } ?: false
        if (!bootCompleted) throw DeviceOperationException("模拟器未完成启动")
        val sdk = adb.shell(serial, listOf("getprop", "ro.build.version.sdk")).requireSuccess().text().trim().toIntOrNull()
            ?: throw DeviceOperationException("无法读取 Android 版本")
        if (sdk < 26) throw DeviceOperationException("不支持 Android 8 以下的模拟器")
        if (initial) mutableState.value = DeviceConnectionState.Verifying(serial, "核验游戏安装信息（首次较慢）")
        val size = DeviceParsers.screenSize(adb.shell(serial, listOf("wm", "size")).requireSuccess().text())
        val bootId = readBootId(serial)
        verifyBoot(bootId, requiredBootId)
        val lease = leases.acquireDevice(bootId)
        var touch: TouchConnection? = null
        try {
            val identityStarted = System.nanoTime()
            val identity = identities.read(serial, size)
            emit("设备信息与游戏签名检查：${(System.nanoTime() - identityStarted) / 1_000_000} ms")
            val awake = DeviceParsers.awake(adb.shell(serial, listOf("dumpsys", "power")).requireSuccess().text())
            val capturer = ScreenCapturer(adb, serial)
            if (initial) mutableState.value = DeviceConnectionState.Verifying(serial, "测试截图速度")
            val (timing, selected) = capturer.benchmark()
            timing.forEach { emit("截图 ${it.mode}：p50 ${it.p50Millis} ms，p95 ${it.p95Millis} ms") }
            emit("已选择截图方式：$selected")
            if (timing.first { it.mode == selected }.p95Millis > ScreenCapturer.SLOW_CAPTURE_MILLIS) emit("截图耗时较长，可尝试调整模拟器渲染模式或关闭高帧率")
            if (maxOf(size.first, size.second) != 1920 || minOf(size.first, size.second) != 1080) emit("上游只在 1920×1080 下验证过")
            if (!awake) emit("模拟器屏幕已休眠，请先在模拟器中唤醒")
            if (initial) mutableState.value = DeviceConnectionState.Verifying(serial, "连接触控服务")
            verifyBoot(readBootId(serial), bootId)
            touch = if (touchMode == TouchMode.MaaTouch) touchFactory.open(adb, serial, dataDirectory) else null
            verifyBoot(readBootId(serial), bootId)
            val ready = DeviceReady(serial, identity.model.ifBlank { serial }, size.first, size.second, timing, selected, true, awake, identity)
            return Session(serial, touchMode, capturer, ready, bootId, touch, lease)
            } catch (failure: Throwable) {
            try { finishCleanup(Cleanup(touch, lease)) } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
            throw failure
        }
    }

    private fun activate(value: Session) {
        session = value
        cachedForeground = null; foregroundUpdated = 0
        mutableState.value = DeviceConnectionState.Ready(value.ready)
        monitor = scope.launch {
            var iteration = 0
            while (isActive && session === value) {
                if (iteration % 2 == 0) queryForeground()
                if (iteration % 4 == 0) {
                    if (!lifecycle.withLock { session === value && verifiedSession(checkState = true) === value }) break
                }
                if (iteration % 8 == 0) {
                    val power = shellText(value.serial, "dumpsys", "power")
                    if (power != null) lifecycle.withLock {
                        if (session !== value) return@withLock
                        val awake = DeviceParsers.awake(power)
                        if (awake != value.ready.awake) {
                            value.ready = value.ready.copy(awake = awake)
                            mutableState.value = DeviceConnectionState.Ready(value.ready)
                            if (!awake) emit("模拟器屏幕已休眠，请先在模拟器中唤醒")
                        }
                    }
                }
                if (value.touchMode == TouchMode.MaaTouch) {
                    if (value.touch?.alive != true) {
                        lifecycle.withLock { recoverTouchLocked(value) }
                    } else if (value.touchRestarted && clockNanos() - value.touchRestartedAt >= TOUCH_STABLE_RESET_NANOS) {
                        lifecycle.withLock {
                            if (session === value && value.touch?.alive == true &&
                                clockNanos() - value.touchRestartedAt >= TOUCH_STABLE_RESET_NANOS) {
                                value.touchRestarted = false
                            }
                        }
                    }
                }
                iteration++
                delay(500)
            }
        }
    }

    private suspend fun recoverTouchLocked(value: Session) {
        if (session !== value || value.touch?.alive == true || verifiedSession() !== value) return
        if (value.touchRestarted) { loseLocked(value, "触控服务已断开，任务已停止"); return }
        value.touchRestarted = true
        value.touchRestartedAt = clockNanos()
        value.touch?.close(); value.touch = null
        var lastError: Exception? = null
        for (attempt in 0..1) {
            try {
                value.touch = touchFactory.open(adb, value.serial, dataDirectory)
                value.touchRestartedAt = clockNanos()
                if (verifiedSession() === value) emit("触控服务已恢复") // A reboot during deployment invalidates the recovered channel too.
                return
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                lastError = error
                value.touch?.close(); value.touch = null
                if (attempt == 0 && (error as? DeviceOperationException)?.adbFailure in transientTransportFailures) {
                    emit("触控服务恢复遇到短暂 ADB 中断，复核连接后重试：${error.message}")
                    if (verifiedSession(checkState = true) !== value) return
                    continue
                }
                break
            }
        }
        lastError?.let { emit("触控服务恢复失败：${it.message ?: it.javaClass.simpleName}") }
        loseLocked(value, "触控服务已断开，任务已停止")
    }

    private suspend fun lose(value: Session, reason: String, retry: Boolean = true) = lifecycle.withLock { loseLocked(value, reason, retry) }

    private fun loseLocked(value: Session, reason: String, retry: Boolean = true) {
        if (session !== value || !mutableState.compareAndSet(DeviceConnectionState.Ready(value.ready), DeviceConnectionState.Lost(reason))) return
        session = null; cachedForeground = null; foregroundUpdated = 0
        // The application owns task cancellation. Notify synchronously before any reconnect work.
        notifyLost(reason)
        monitor?.cancel()
        try { finishCleanup(Cleanup(value.touch, value.lease)) }
        catch (failure: Exception) {
            mutableState.value = DeviceConnectionState.Failed("设备清理未完成，占用锁保留：${failure.message}")
            return
        }
        if (!retry) { mutableState.value = DeviceConnectionState.Failed(reason); return }
        reconnect = scope.launch {
            repeat(12) { index ->
                delay(5000)
                try {
                    lifecycle.withLock {
                        ensureActive()
                        mutableState.value = DeviceConnectionState.Reconnecting(index + 1)
                        val connected = establish(value.serial, value.touchMode, false, value.bootId)
                        activate(connected)
                    }
                    emit("设备已重新连接；任务保持停止，请手动启动")
                    return@launch
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) {
                    if (error is BootChanged || (error as? DeviceOperationException)?.adbFailure == AdbFailureKind.ServerVersionConflict) {
                        mutableState.value = DeviceConnectionState.Failed(error.message ?: "设备重连失败")
                        return@launch
                    }
                    if (index == 11) mutableState.value = DeviceConnectionState.Failed(error.message ?: "设备重连失败")
                }
            }
        }
    }

    override suspend fun disconnect() {
        connectingJobs.toList().forEach { it.cancel() }
        reconnect?.cancelAndJoin()
        monitor?.cancelAndJoin()
        lifecycle.withLock {
            reconnect = null
            stopSession("设备已断开，任务已停止")
            mutableState.value = DeviceConnectionState.Disconnected
        }
    }

    private suspend fun stopSession(reason: String) {
        val previous = session
        session = null; cachedForeground = null; foregroundUpdated = 0
        if (previous != null) notifyLost(reason)
        monitor?.cancel(); monitor = null
        if (previous != null) finishCleanup(Cleanup(previous.touch, previous.lease))
        pendingCleanup.toList().forEach(::finishCleanup)
    }

    private fun finishCleanup(value: Cleanup) {
        pendingCleanup.add(value)
        value.touch?.close()
        value.lease.close()
        pendingCleanup.remove(value)
    }

    override suspend fun capture(): DeviceFrame {
        val value = session ?: throw DeviceOperationException("设备未连接")
        return value.captures.withLock {
            if (value.checkingTransport) lifecycle.withLock { requireCurrent(value) }
            requireCurrent(value)
            val started = clockNanos()
            if (started - value.lastCaptureCompleted > 1_000_000_000L) value.failureSince = 0
            var mode = value.ready.selectedMode
            val frame = try {
                value.capturer.capture(mode)
            } catch (error: IOException) {
                currentCoroutineContext().ensureActive()
                requireCurrent(value)
                value.capturePaused = true
                val failure = captureFailure(mode, started, error)
                emit(failure)
                val kind = (error as? DeviceOperationException)?.adbFailure
                if (kind == AdbFailureKind.ServerVersionConflict) {
                    lose(value, failure, retry = false)
                    throw error
                }
                if (value.failureSince == 0L) value.failureSince = started
                value.failures++
                val backup = value.ready.timing.firstOrNull { it.mode != mode }?.mode
                val (recoveredMode, recoveredFrame) = recoverCapture(value, mode, backup, kind, failure)
                mode = recoveredMode
                recoveredFrame
            } finally {
                value.lastCaptureCompleted = clockNanos()
            }
            requireCurrent(value)
            if (value.capturePaused) acceptRecoveredFrame(value, mode, frame)
            else {
                if (value.checkingTransport) lifecycle.withLock { requireCurrent(value) }
                value.frameSize = frame.width to frame.height
            }
            value.failures = 0
            value.failureSince = 0
            frame
        }
    }

    private fun requireCurrent(value: Session) {
        if (session !== value) throw DeviceOperationException("设备连接已更改")
    }

    /** Called under lifecycle. A reboot during the capture must invalidate the returned frame too. */
    private suspend fun requireVerifiedCaptureSession(value: Session, checkState: Boolean = false): Boolean {
        requireCurrent(value)
        val (verified, recovered) = verifiedSessionDetailed(checkState)
            ?: throw DeviceOperationException("截图恢复无法确认原设备")
        if (verified !== value) throw DeviceOperationException("截图恢复无法确认原设备")
        return recovered
    }

    private fun captureFailure(mode: CaptureMode, started: Long, error: IOException): String {
        val kind = (error as? DeviceOperationException)?.adbFailure?.name ?: "解码失败"
        return "截图 $mode 失败（${(clockNanos() - started) / 1_000_000} ms，$kind）：${error.message.orEmpty().lineSequence().first().take(300)}"
    }

    private suspend fun recoverCapture(
        value: Session,
        primary: CaptureMode,
        backup: CaptureMode?,
        kind: AdbFailureKind?,
        firstFailure: String,
    ): Pair<CaptureMode, DeviceFrame> {
        val transportRecovered = lifecycle.withLock {
            requireVerifiedCaptureSession(value, checkState = true)
        } || (value.lastTransportRecoveredAt != 0L && value.lastTransportRecoveredAt >= value.failureSince)
        val preservePrimary = transportRecovered ||
            kind in setOf(AdbFailureKind.DeviceOffline, AdbFailureKind.DeviceNotFound, AdbFailureKind.ConnectionRefused)
        if (transportRecovered) {
            // Transport recovery can take up to 30s; do not count that outage against the post-recovery window.
            value.failureSince = clockNanos()
        }
        val mode = if (preservePrimary || backup == null) primary else backup
        val usingBackup = mode != primary
        emit("设备在线，启动身份一致。" + if (usingBackup) "尝试备用截图 $mode" else if (backup == null) "无已验证备用方式，重试 $mode" else "重试 $mode")
        val started = clockNanos()
        return try {
            mode to value.capturer.capture(mode)
        } catch (error: IOException) {
            currentCoroutineContext().ensureActive()
            requireCurrent(value)
            var failure = captureFailure(mode, started, error)
            emit("截图恢复失败：$failure")
            var retryKind = (error as? DeviceOperationException)?.adbFailure
            var lastError: IOException = error
            if (retryKind == AdbFailureKind.ServerVersionConflict) {
                lose(value, "无法获取模拟器画面，任务已停止。$firstFailure；$failure", retry = false)
                throw error
            }
            if (preservePrimary && mode == primary && backup != null && retryKind !in transientTransportFailures) {
                val backupStarted = clockNanos()
                emit("原截图 $primary 失败且非传输中断，尝试备用截图 $backup")
                try {
                    return backup to value.capturer.capture(backup)
                } catch (backupError: IOException) {
                    currentCoroutineContext().ensureActive()
                    requireCurrent(value)
                    lastError = backupError
                    val backupFailure = captureFailure(backup, backupStarted, backupError)
                    emit("备用截图恢复失败：$backupFailure")
                    failure = "$failure；$backupFailure"
                    retryKind = (backupError as? DeviceOperationException)?.adbFailure
                    if (retryKind == AdbFailureKind.ServerVersionConflict) {
                        lose(value, "无法获取模拟器画面，任务已停止。$firstFailure；$failure", retry = false)
                        throw backupError
                    }
                }
            }
            val transportRetry = retryKind in setOf(AdbFailureKind.DeviceOffline, AdbFailureKind.DeviceNotFound, AdbFailureKind.ConnectionRefused) ||
                (transportRecovered && retryKind == AdbFailureKind.Timeout)
            val timeoutEscalation = !transportRetry && (kind == AdbFailureKind.Timeout || retryKind == AdbFailureKind.Timeout)
            if (timeoutEscalation || value.failures >= 5 || clockNanos() - value.failureSince >= 10_000_000_000L) {
                lose(value, "无法获取模拟器画面，任务已停止。$firstFailure；$failure", retry = true)
            }
            throw lastError
        }
    }

    private suspend fun acceptRecoveredFrame(value: Session, mode: CaptureMode, frame: DeviceFrame) {
        lifecycle.withLock {
            requireVerifiedCaptureSession(value)
            value.frameSize = frame.width to frame.height
            value.ready = value.ready.copy(selectedMode = mode)
            mutableState.value = DeviceConnectionState.Ready(value.ready)
            value.capturePaused = false
        }
        emit("截图已恢复，当前使用 $mode（${frame.width}×${frame.height}）")
    }

    override suspend fun queryForeground(): String? {
        val value = session ?: return null
        val result = adb.shell(value.serial, listOf("dumpsys", "window"))
        val foreground = (result as? AdbResult.Success)?.text()?.let(DeviceParsers::foreground)
        if (session === value) { cachedForeground = foreground; foregroundUpdated = clockNanos() }
        return foreground.takeIf { session === value }
    }

    private suspend fun action(expectedPackage: String, commands: String, durationMillis: Long, compatibility: List<String>): TouchResult = lifecycle.withLock {
        val permit = currentCoroutineContext()[DeviceDispatchPermit]
        if (permit?.allowed?.invoke() == false) return@withLock TouchResult.Rejected(DEVICE_DISPATCH_SUPPRESSED)
        val value = controlSession() ?: return@withLock TouchResult.Rejected("触控服务未连接")
        if (value.touchMode == TouchMode.MaaTouch && value.touch?.alive != true) recoverTouchLocked(value)
        if (session !== value) return@withLock TouchResult.Rejected("触控服务未连接")
        if (queryForeground() != expectedPackage) return@withLock TouchResult.Rejected("游戏不在前台")
        if (session !== value || value.capturePaused) return@withLock TouchResult.Rejected("设备或画面尚未就绪")
        if (permit?.allowed?.invoke() == false) return@withLock TouchResult.Rejected(DEVICE_DISPATCH_SUPPRESSED)
        try {
            if (value.touchMode == TouchMode.Compatibility) adb.shell(value.serial, listOf("input") + compatibility, durationMillis + 10_000).requireSuccess()
            else {
                val touch = value.touch ?: return@withLock TouchResult.Rejected("触控服务未连接")
                if (!touch.alive) return@withLock TouchResult.Rejected("触控服务已断开")
                if (!MaaTouchProtocol.matchesDimensions(touch.header, value.frameSize.first, value.frameSize.second)) return@withLock TouchResult.Rejected("触控坐标系与截图尺寸不一致")
                if (permit == null) touch.send(commands, durationMillis)
                else if (!touch.sendGuarded(commands, durationMillis, permit.allowed)) return@withLock TouchResult.Rejected(DEVICE_DISPATCH_SUPPRESSED)
                value.touchRestarted = false
            }
            TouchResult.Completed
        } catch (_: IOException) { TouchResult.Rejected("触控服务已断开") }
    }

    override suspend fun tap(x: Float, y: Float, expectedPackage: String): TouchResult {
        if (!validCoordinates(x, y)) return TouchResult.Rejected("动作坐标无效")
        return action(expectedPackage, MaaTouchProtocol.tap(x, y), 60, listOf("tap", x.roundToInt().toString(), y.roundToInt().toString()))
    }

    override suspend fun swipe(fromX: Float, fromY: Float, toX: Float, toY: Float, durationMillis: Long, holdMillis: Long, expectedPackage: String): TouchResult {
        if (!validCoordinates(fromX, fromY, toX, toY)) return TouchResult.Rejected("动作坐标无效")
        if (durationMillis !in 1..5000 || holdMillis.coerceAtLeast(0) > 5000 - durationMillis) return TouchResult.Rejected("滑动时长超出范围")
        if (session?.touchMode == TouchMode.Compatibility && holdMillis > 0) return TouchResult.Rejected("兼容模式不支持按住拖拽，请在设置中切换到 MaaTouch")
        return action(expectedPackage, MaaTouchProtocol.swipe(fromX, fromY, toX, toY, durationMillis, holdMillis), durationMillis + holdMillis.coerceAtLeast(0),
            listOf("swipe", fromX.roundToInt().toString(), fromY.roundToInt().toString(), toX.roundToInt().toString(), toY.roundToInt().toString(), durationMillis.toString()))
    }

    override suspend fun back(expectedPackage: String): TouchResult = action(expectedPackage, MaaTouchProtocol.key(4), 0, listOf("keyevent", "4"))

    override suspend fun home(): Boolean = lifecycle.withLock {
        val value = controlSession() ?: return@withLock false
        try {
            if (value.touchMode == TouchMode.Compatibility) adb.shell(value.serial, listOf("input", "keyevent", "3")).requireSuccess()
            else {
                if (value.touch?.alive != true) recoverTouchLocked(value)
                if (session !== value) return@withLock false
                (value.touch ?: return@withLock false).send(MaaTouchProtocol.key(3), 0)
                value.touchRestarted = false
            }
            true
        } catch (_: IOException) { false }
    }

    override suspend fun launch(packageName: String): Boolean = lifecycle.withLock {
        val serial = controlSession()?.serial ?: return@withLock false
        val component = shellText(serial, "cmd", "package", "resolve-activity", "--brief", "-c", "android.intent.category.LAUNCHER", packageName)?.let(DeviceParsers::launchComponent) ?: return@withLock false
        if (session?.capturePaused != false) return@withLock false
        val output = adb.shell(serial, listOf("am", "start", "-n", component))
        output is AdbResult.Success && DeviceParsers.launchSucceeded(output.text() + "\n" + output.stderr)
    }

    override suspend fun isPackageRunning(packageName: String): Boolean {
        require(packageName in gamePackages) { "不是当前支持的游戏包" }
        val value = session ?: throw DeviceOperationException("设备未连接，无法检查游戏进程")
        // Toybox NAME is argv[0]; -w prevents truncating long package/subprocess names.
        val result = adb.shell(value.serial, listOf("ps", "-A", "-w", "-o", "NAME"), 3000)
        if (session !== value) throw DeviceOperationException("设备连接已更改，无法确认游戏进程")
        return when (result) {
            is AdbResult.Success -> {
                val names = result.text().lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
                check(names.firstOrNull() == "NAME" && names.size > 1 && result.stderr.isBlank()) { "游戏进程检查返回无效结果" }
                names.drop(1).any { it == packageName || it.startsWith("$packageName:") }
            }
            is AdbResult.Failure -> throw DeviceOperationException("无法检查游戏进程：${result.detail}", result.kind)
        }
    }

    override suspend fun forceStop(packageName: String): Boolean = lifecycle.withLock {
        val serial = controlSession()?.serial ?: return@withLock false
        if (adb.shell(serial, listOf("am", "force-stop", packageName)) !is AdbResult.Success) return@withLock false
        val deadline = clockNanos() + 3_000_000_000L
        while (clockNanos() < deadline) {
            val remaining = ((deadline - clockNanos()) / 1_000_000).coerceIn(1, 1000)
            when (val result = adb.shell(serial, listOf("pidof", packageName), remaining)) {
                is AdbResult.Success -> if (result.text().isBlank()) return@withLock true
                is AdbResult.Failure -> return@withLock result.kind == AdbFailureKind.NonZeroExit && result.detail == "adb 退出码 1"
            }
            delay(100)
        }
        emit("游戏进程未在 3 秒内退出")
        false
    }

    override suspend fun uiTree(): List<UiNode> = lifecycle.withLock {
        val serial = controlSession()?.serial ?: return@withLock emptyList()
        repeat(4) { attempt ->
            val dump = adb.shell(serial, listOf("uiautomator", "dump", "/data/local/tmp/ui.xml"))
            val text = when (dump) { is AdbResult.Success -> dump.text() + dump.stderr; is AdbResult.Failure -> dump.detail }
            if (text.contains("could not get idle state", ignoreCase = true) && attempt < 3) { delay(500); return@repeat }
            if (dump is AdbResult.Success && !text.contains("ERROR:", ignoreCase = true)) {
                val xml = shellText(serial, "cat", "/data/local/tmp/ui.xml")
                if (xml != null) {
                    try { return@withLock DeviceParsers.uiTree(xml) }
                    catch (_: IOException) { }
                    catch (_: SAXException) { }
                    catch (_: IllegalArgumentException) { }
                }
            }
            emit("无法读取渠道登录界面")
            return@withLock emptyList()
        }
        emit("无法读取渠道登录界面")
        emptyList()
    }

    private suspend fun readBootId(serial: String, allowBlankAsTransient: Boolean = false): String {
        val raw = adb.shell(serial, listOf("cat", "/proc/sys/kernel/random/boot_id"), 3000).requireSuccess().text().trim()
        if (allowBlankAsTransient && raw.isEmpty()) {
            throw DeviceOperationException("模拟器启动身份返回为空", AdbFailureKind.ConnectionRefused)
        }
        return UserResourceLeases.canonicalBootId(raw)
    }

    /** Under lifecycle: no control command can outlive cleanup and lease release. */
    private suspend fun controlSession(): Session? {
        if (session?.capturePaused != false) return null
        return verifiedSession()?.takeUnless { it.capturePaused }
    }

    private suspend fun verifyTransport(value: Session, checkState: Boolean) {
        if (checkState) {
            val status = adb.execute(listOf("get-state"), value.serial, timeoutMillis = 3000).requireSuccess().text().trim()
            if (status != "device") {
                val kind = when (status) {
                    "offline", "authorizing" -> AdbFailureKind.DeviceOffline
                    "unknown", "" -> AdbFailureKind.DeviceNotFound
                    "unauthorized" -> AdbFailureKind.Unauthorized
                    else -> AdbFailureKind.NonZeroExit
                }
                throw DeviceOperationException("ADB 设备状态：$status", kind)
            }
        }
        verifyBoot(readBootId(value.serial, allowBlankAsTransient = true), value.bootId)
    }

    /** Hold the existing lease and serialize control while the same transport is being verified. */
    private suspend fun verifyWithRetry(value: Session, checkState: Boolean): Boolean {
        var failure = try {
            verifyTransport(value, checkState)
            return false
        } catch (error: DeviceOperationException) {
            if (error.adbFailure !in transientTransportFailures) throw error
            error
        }
        value.checkingTransport = true
        emit("ADB 通信暂时中断，最多等待 ${TRANSPORT_RECOVERY_MILLIS / 1000} 秒复核原设备：${failure.message}")
        var attempt = 0
        var nextConnectAttempt = 1
        var connectedOnce = false
        val recovered = withTimeoutOrNull(TRANSPORT_RECOVERY_MILLIS) {
            while (true) {
                delay(1_000)
                attempt++
                try {
                    if (':' in value.serial &&
                        failure.adbFailure in setOf(AdbFailureKind.DeviceOffline, AdbFailureKind.DeviceNotFound, AdbFailureKind.ConnectionRefused) &&
                        attempt >= nextConnectAttempt) {
                        if (connectedOnce && failure.adbFailure == AdbFailureKind.DeviceOffline) {
                            disconnectTcpTransport(value.serial)
                        }
                        val connectResult = try {
                            adb.execute(listOf("connect", value.serial), timeoutMillis = 3000).requireSuccess()
                        } catch (error: DeviceOperationException) {
                            if (error.adbFailure in transientTransportFailures) {
                                nextConnectAttempt = attempt + TCP_FAILED_CONNECT_INTERVAL_ATTEMPTS
                            }
                            throw error
                        }
                        if (failure.adbFailure == AdbFailureKind.DeviceOffline &&
                            connectResult.text().contains("already connected", ignoreCase = true)) {
                            disconnectTcpTransport(value.serial)
                            try {
                                adb.execute(listOf("connect", value.serial), timeoutMillis = 3000).requireSuccess()
                            } catch (error: DeviceOperationException) {
                                if (error.adbFailure in transientTransportFailures) {
                                    nextConnectAttempt = attempt + TCP_FAILED_CONNECT_INTERVAL_ATTEMPTS
                                }
                                throw error
                            }
                        }
                        connectedOnce = true
                        nextConnectAttempt = attempt + TCP_RECONNECT_INTERVAL_ATTEMPTS
                    }
                    verifyTransport(value, checkState)
                    break
                } catch (error: DeviceOperationException) {
                    if (error.adbFailure !in transientTransportFailures) throw error
                    failure = error
                }
            }
            true
        }
        if (recovered != true) throw failure
        value.lastTransportRecoveredAt = clockNanos()
        emit("ADB 通信已恢复，已确认原模拟器启动身份")
        return true
    }

    private suspend fun verifiedSessionDetailed(checkState: Boolean = false): Pair<Session, Boolean>? {
        val value = session ?: return null
        if (!value.lease.isValid) {
            loseLocked(value, "设备占用锁已失效，任务已停止", retry = false)
            return null
        }
        val recovered = try {
            verifyWithRetry(value, checkState)
        } catch (changed: BootChanged) {
            loseLocked(value, changed.message.orEmpty(), retry = false)
            return null
        } catch (error: DeviceOperationException) {
            currentCoroutineContext().ensureActive()
            loseLocked(value, "无法确认原设备的 ADB 连接与启动身份，任务已停止：${error.message}", retry = error.adbFailure != AdbFailureKind.ServerVersionConflict)
            return null
        } catch (_: IllegalArgumentException) {
            loseLocked(value, "模拟器返回了无效的启动身份，任务已停止")
            return null
        } finally {
            value.checkingTransport = false
        }
        return value to recovered
    }

    private suspend fun verifiedSession(checkState: Boolean = false): Session? = verifiedSessionDetailed(checkState)?.first

    private suspend fun shellText(serial: String, vararg args: String): String? = (adb.shell(serial, args.toList()) as? AdbResult.Success)?.text()?.trim()
    private fun validCoordinates(vararg coordinates: Float): Boolean = coordinates.all { it.isFinite() && it >= 0 }
    private fun emit(message: String) { try { onEvent(message) } catch (_: Exception) { /* Observers cannot break device cleanup. */ } }
    private fun notifyLost(reason: String) { try { onLost(reason) } catch (_: Exception) { emit("任务停止通知失败") } }
    private companion object {
        const val TRANSPORT_RECOVERY_MILLIS = 30_000L
        const val TCP_RECONNECT_INTERVAL_ATTEMPTS = 10
        const val TCP_FAILED_CONNECT_INTERVAL_ATTEMPTS = 4
        const val TOUCH_STABLE_RESET_NANOS = 15_000_000_000L
        const val FOREGROUND_CACHE_TTL_NANOS = 2_500_000_000L
        const val INITIAL_BOOT_TRANSIENT_RETRIES = 3
        val transientTransportFailures = setOf(AdbFailureKind.Timeout, AdbFailureKind.DeviceOffline,
            AdbFailureKind.DeviceNotFound, AdbFailureKind.ConnectionRefused)
    }
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        try { runBlocking { disconnect() } }
        finally {
            scope.cancel()
            adb.close()
            // Failed cleanup keeps its OS lock until cleanup succeeds or this worker exits.
            pendingCleanup.toList().forEach { runCatching { finishCleanup(it) } }
        }
    }
}
