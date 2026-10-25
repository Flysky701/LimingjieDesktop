package com.landosol.toolbox.desktop.app.control

import com.landosol.toolbox.desktop.app.DesktopAppGraph
import com.landosol.toolbox.desktop.app.adapters.storage.DatabaseRecovery
import com.landosol.toolbox.desktop.app.adapters.storage.DesktopRunRecord
import com.landosol.toolbox.desktop.platform.InstanceLock
import com.landosol.toolbox.desktop.platform.UserRuntimeDirectory
import com.landosol.toolbox.desktop.shared.DesktopContext
import com.landosol.toolbox.desktop.shared.DesktopLog
import com.landosol.toolbox.desktop.shared.AtomicFiles
import com.landosol.toolbox.labyrinth.AndroidLabyrinthRerollSettingsStore
import com.landosol.toolbox.labyrinth.LabyrinthRerollSettings
import com.landosol.toolbox.protocol.bilibili.GameServer
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.file.Path
import java.nio.file.Files
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import java.util.concurrent.Executors
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

/** The only owner of global credentials, account/configuration SQLite, strategy and shared history. */
class SharedDataHost private constructor(
    private val graph: DesktopAppGraph,
    private val lock: InstanceLock,
    val endpoint: SharedDataEndpoint,
    private val server: HttpServer,
) {
    private val domain = GraphControlService(graph, "shared-data", "全局数据")
    private val writes = Mutex()
    private val shutdownLock = Mutex()
    @Volatile private var closed = false
    private var lastStrategy = graph.labyrinthStrategySettings.state.value
    private var revision = System.currentTimeMillis()
    private val verifiedFile = graph.dataDirectory.resolve("shared/verified-uids.json")
    private val verifiedUids = if (Files.exists(verifiedFile)) runCatching {
        Json.parseToJsonElement(Files.readString(verifiedFile)).jsonObject.toMutableMap()
    }.getOrElse { mutableMapOf() } else mutableMapOf()
    private val executor = Executors.newFixedThreadPool(4) { Thread(it, "shared-data-rpc").apply { isDaemon = true } }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val mutableSnapshots = MutableStateFlow(JsonObject(emptyMap()))
    private val mutableConnection = MutableStateFlow(WorkerConnectionStatus.CONNECTED)
    private val mutableError = MutableStateFlow<String?>(null)
    private val runId = UUID.randomUUID().toString()
    val control: ControlSessionPort = object : ControlSessionPort {
        override val instanceId = "shared-data"
        override val workerRunId = runId
        override val snapshots = mutableSnapshots.asStateFlow()
        override val connection = mutableConnection.asStateFlow()
        override val error = mutableError.asStateFlow()
        override suspend fun request(op: String, args: JsonObject): JsonObject = writes.withLock {
            check(!closed) { "共享数据服务已关闭" }
            val editingId = when (op) {
                ControlOps.ACCOUNT_SAVE -> args["editor"]?.jsonObject?.get("id")?.jsonPrimitive?.longOrNull
                ControlOps.ACCOUNT_DELETE -> args["id"]?.jsonPrimitive?.longOrNull
                else -> null
            }
            val editingLease = editingId?.let { AccountStartLease.acquire(UserRuntimeDirectory.resolve(), endpoint.authorityId, it) }
            val result = try {
                // Persist invalidation before any credential mutation, including a crash mid-save.
                editingId?.let { verifiedUids.remove(it.toString()); saveVerifiedUids() }
                domain.execute(op, args)
            } finally { editingLease?.close() }
            publish()
            result
        }
        override fun setSelected(selected: Boolean) = Unit
        override suspend fun shutdown() = this@SharedDataHost.shutdown()
    }

    init {
        server.executor = executor
        server.createContext("/shared-data") { exchange ->
            exchange.use {
                val supplied = exchange.requestHeaders.getFirst("X-Limingjie-Shared-Token").orEmpty()
                if (exchange.requestMethod != "POST" || !MessageDigest.isEqual(supplied.toByteArray(), endpoint.token.toByteArray())) {
                    exchange.sendResponseHeaders(403, -1); return@createContext
                }
                val body = exchange.requestBody.readNBytes(MAX_REQUEST + 1)
                if (body.size > MAX_REQUEST) { exchange.sendResponseHeaders(413, -1); return@createContext }
                val response = try {
                    val request = Json.parseToJsonElement(body.toString(Charsets.UTF_8)).jsonObject
                    check(request["authorityId"]?.jsonPrimitive?.content == endpoint.authorityId) { "共享数据身份不匹配" }
                    val result = runBlocking { writes.withLock { rpc(request["op"]?.jsonPrimitive?.content.orEmpty(), request["args"]?.jsonObject ?: JsonObject(emptyMap())) } }
                    buildJsonObject { put("result", result) }
                } catch (failure: Exception) {
                    buildJsonObject { put("error", DesktopLog.redact(failure.message ?: "共享数据操作失败")) }
                }.toString().toByteArray(Charsets.UTF_8)
                exchange.responseHeaders.set("Content-Type", "application/json; charset=utf-8")
                exchange.responseHeaders.set("Cache-Control", "no-store")
                exchange.sendResponseHeaders(200, response.size.toLong())
                exchange.responseBody.use { it.write(response) }
            }
        }
        server.start()
        scope.launch {
            while (isActive) {
                try { writes.withLock { publish() } }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { mutableError.value = failure.message ?: "共享数据状态读取失败" }
                delay(500)
            }
        }
    }

    private fun strategyRevision(): Long {
        val current = graph.labyrinthStrategySettings.state.value
        if (current != lastStrategy) { lastStrategy = current; revision = maxOf(revision + 1, System.currentTimeMillis()) }
        return revision
    }

    private fun saveVerifiedUids() = AtomicFiles.write(verifiedFile, JsonObject(verifiedUids).toString().toByteArray())

    private suspend fun publish() {
        mutableSnapshots.value = JsonObject(domain.snapshot(false) + mapOf(
            "sharedData" to JsonPrimitive(true), "strategyRevision" to JsonPrimitive(strategyRevision())))
    }

    private suspend fun rpc(op: String, args: JsonObject): JsonObject {
        check(!closed) { "共享数据服务已关闭" }
        fun id() = args.getValue("accountId").jsonPrimitive.long.also { require(it > 0) }
        when (op) {
            "runtime.taskData" -> {
                val id = id()
                val account = graph.database.accountDao().getById(id) ?: error("共享账号不存在，请重新绑定")
                val material = graph.accountRepository.loadLoginMaterial(id)
                val strategy = graph.labyrinthStrategySettings.state.value
                val value = SharedTaskData(SharedAccountMetadata(id, account.alias, account.serverId, account.gameUid, account.updatedAt),
                    material.loginId, material.password, AndroidLabyrinthRerollSettingsStore(graph.context).load(id),
                    strategy, strategyRevision(), graph.settings.state.value,
                    (verifiedUids[id.toString()] as? JsonObject)?.takeIf { it["serverId"]?.jsonPrimitive?.content == account.serverId }
                        ?.get("uid")?.jsonPrimitive?.longOrNull, graph.ux.preferences.state.value)
                return buildJsonObject { put("value", ControlModelCodec.encode(value)) }
            }
            "runtime.settings" -> return buildJsonObject { put("value", ControlModelCodec.encode(graph.settings.state.value)) }
            "runtime.uid" -> {
                val uid = args.getValue("uid").jsonPrimitive.content
                require(uid.matches(Regex("[1-9][0-9]*"))) { "服务端 UID 无效" }
                val accountId = id()
                graph.accountRepository.updateGameUid(accountId, uid)
                val account = checkNotNull(graph.database.accountDao().getById(accountId))
                verifiedUids[accountId.toString()] = buildJsonObject { put("serverId", account.serverId); put("uid", uid.toLong()) }
                saveVerifiedUids()
            }
            "runtime.reroll" -> {
                val accountId = id()
                check(graph.database.accountDao().getById(accountId) != null) { "共享账号不存在" }
                val value = ControlModelCodec.decode<LabyrinthRerollSettings>(args.getValue("value"))
                value.validationError()?.let { error(it) }
                AndroidLabyrinthRerollSettingsStore(graph.context).save(accountId, value)
                withContext(Dispatchers.Main) {
                    if (graph.labyrinthController.uiState.value.selectedAccount?.id == accountId && graph.labyrinthController.uiState.value.settingsReady)
                        graph.labyrinthController.saveSettings(value)
                }
            }
            "runtime.record" -> {
                val session = args.getValue("sessionId").jsonPrimitive.content
                require(session.isNotBlank() && session.length <= 128) { "运行会话身份无效" }
                val value = ControlModelCodec.decode<DesktopRunRecord>(args.getValue("value"))
                graph.runHistory.put(value.copy(id = "$session:${value.id}", deviceId = session,
                    deviceName = args.getValue("deviceName").jsonPrimitive.content,
                    accountId = args["accountId"]?.jsonPrimitive?.longOrNull ?: value.accountId))
            }
            else -> error("不支持的共享数据操作")
        }
        return buildJsonObject { put("accepted", true) }
    }

    suspend fun shutdown() = shutdownLock.withLock {
        if (closed) return@withLock
        closed = true
        mutableConnection.value = WorkerConnectionStatus.CLOSED
        withContext(NonCancellable) {
            scope.coroutineContext[Job]?.cancelAndJoin()
            server.stop(0); executor.shutdownNow()
            try { domain.shutdown() } finally { lock.close() }
        }
    }

    companion object {
        private const val MAX_REQUEST = 2 * 1024 * 1024
        suspend fun start(directory: Path, startupUpdates: Boolean = true): SharedDataHost {
            val lock = InstanceLock.acquire(directory)
            var graph: DesktopAppGraph? = null
            try {
                val notices = mutableListOf<String>()
                val database = DatabaseRecovery.open(DesktopContext(directory.toFile())) { notices += it }
                graph = DesktopAppGraph(directory, database, startupUpdates = startupUpdates, dataOnly = true)
                notices.forEach(graph::addNotice)
                val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
                val token = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(SecureRandom()::nextBytes))
                val authority = MessageDigest.getInstance("SHA-256").digest(directory.toAbsolutePath().normalize().toString().lowercase().toByteArray())
                    .joinToString("") { "%02x".format(it) }
                return SharedDataHost(graph, lock, SharedDataEndpoint(server.address.port, token, authority), server)
            } catch (failure: Throwable) {
                graph?.let { withContext(NonCancellable) { it.shutdown() } }; lock.close(); throw failure
            }
        }
    }
}
