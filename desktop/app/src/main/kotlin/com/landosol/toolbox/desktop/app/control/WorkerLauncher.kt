package com.landosol.toolbox.desktop.app.control

import java.net.InetAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*

class WorkerLauncher(
    private val scope: CoroutineScope,
    private val containProcess: (Long) -> AutoCloseable,
    private val command: List<String> = applicationCommand(),
    private val receiveTimeoutMillis: Int = WorkerProtocol.LEASE_MILLIS.toInt(),
) {
    init { require(receiveTimeoutMillis > 0) }
    fun start(instanceId: String, label: String, directory: Path): WorkerLink =
        ProcessWorkerLink(instanceId, label, directory, scope, command, containProcess, receiveTimeoutMillis)

    companion object {
        fun applicationCommand(): List<String> {
            System.getProperty("jpackage.app-path")?.takeIf { Files.isRegularFile(Path.of(it)) }?.let { return listOf(it) }
            val java = Path.of(System.getProperty("java.home"), "bin", if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java")
            return listOf(java.toString(), "-cp", System.getProperty("java.class.path"), "com.landosol.toolbox.desktop.app.MainKt")
        }
    }
}

private class ProcessWorkerLink(
    override val instanceId: String,
    label: String,
    directory: Path,
    parentScope: CoroutineScope,
    command: List<String>,
    private val containProcess: (Long) -> AutoCloseable,
    receiveTimeoutMillis: Int,
) : WorkerLink {
    override val workerRunId: String = UUID.randomUUID().toString()
    private val mutableSnapshots = MutableStateFlow(JsonObject(emptyMap()))
    override val snapshots = mutableSnapshots.asStateFlow()
    private val mutableConnection = MutableStateFlow(WorkerConnectionStatus.STARTING)
    override val connection = mutableConnection.asStateFlow()
    private val mutableError = MutableStateFlow<String?>(null)
    override val error = mutableError.asStateFlow()
    private val work = CoroutineScope(parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]))
    private val ready = CompletableDeferred<Unit>()
    private val intentionalClose = AtomicBoolean(false)
    private val closed = CompletableDeferred<Unit>()
    private val pending = ConcurrentHashMap<String, CompletableDeferred<JsonObject>>()
    private val selected = AtomicBoolean(true)
    @Volatile private var wire: WorkerWire? = null
    @Volatile private var server: ServerSocket? = null
    @Volatile private var process: Process? = null
    @Volatile private var containment: AutoCloseable? = null
    private var lastSequence = 0L

    private val launchJob = work.launch(Dispatchers.IO) {
        try {
            Files.createDirectories(directory.resolve("logs"))
            val token = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(SecureRandom()::nextBytes))
            val listener = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).also { server = it; it.soTimeout = 60_000 }
            val builder = ProcessBuilder(command + listOf("--worker", "--instance-id", instanceId, "--worker-run-id", workerRunId, "--instance-name", label, "--data-dir", directory.toString()))
                .redirectOutput(ProcessBuilder.Redirect.appendTo(directory.resolve("logs/worker-console.log").toFile()))
                .redirectErrorStream(true)
            builder.environment()[WorkerProtocol.TOKEN_ENV] = token
            builder.environment()[WorkerProtocol.PORT_ENV] = listener.localPort.toString()
            ensureActive()
            val child = builder.start().also { process = it }
            // start() is blocking: publish the child before checking cancellation so finally owns it.
            ensureActive()
            containment = containProcess(child.pid())
            child.onExit().thenRun { if (!intentionalClose.get()) lose("实例进程已退出，请重新打开标签") }
            ensureActive()
            val peer = WorkerWire(listener.accept().apply { tcpNoDelay = true; soTimeout = 15_000 })
            wire = peer
            listener.close(); server = null
            ensureActive() // shutdown may have closed the listener before this accepted peer was published.
            val hello = peer.read()
            check(hello.wireString("kind") == "hello" && hello.wireString("profileId") == instanceId &&
                hello.wireString("runId") == workerRunId && hello.wireString("token") == token &&
                (hello["version"] as? JsonPrimitive)?.intOrNull == WorkerProtocol.VERSION) { "实例连接验证失败" }
            peer.write(wireMessage("hello.ok", instanceId, workerRunId))
            peer.socket.soTimeout = receiveTimeoutMillis
            peer.write(wireMessage("selected", instanceId, workerRunId) { put("value", selected.get()) })
            mutableConnection.value = WorkerConnectionStatus.CONNECTED
            ready.complete(Unit)
            work.launch(Dispatchers.IO) {
                while (isActive && !intentionalClose.get() && mutableConnection.value == WorkerConnectionStatus.CONNECTED) {
                    try { peer.write(wireMessage("ping", instanceId, workerRunId)) }
                    catch (_: Exception) { lose("控制连接已中断"); break }
                    delay(WorkerProtocol.HEARTBEAT_MILLIS)
                }
            }
            while (isActive && !intentionalClose.get()) {
                val incoming = peer.read()
                if (incoming.wireString("profileId") != instanceId || incoming.wireString("runId") != workerRunId) continue
                when (incoming.wireString("kind")) {
                    "state" -> {
                        val seq = (incoming["sequence"] as? JsonPrimitive)?.longOrNull ?: continue
                        if (seq > lastSequence) { lastSequence = seq; mutableSnapshots.value = incoming.wireObject("state") }
                    }
                    "reply" -> incoming.wireString("requestId")?.let { id ->
                        pending.remove(id)?.let { reply ->
                            incoming.wireString("error")?.let { reply.completeExceptionally(IllegalStateException(it)) }
                                ?: reply.complete(incoming.wireObject("result"))
                        }
                    }
                }
            }
        } catch (_: CancellationException) { if (!intentionalClose.get()) lose("总控正在退出") }
        catch (_: java.net.SocketTimeoutException) { if (!intentionalClose.get()) lose("实例响应超时，连接已关闭") }
        catch (failure: Exception) { if (!intentionalClose.get()) lose(failure.message ?: "实例启动失败") }
        finally {
            withContext(NonCancellable + Dispatchers.IO) {
                closeTransport()
                failRequests(mutableError.value ?: "实例已关闭")
                // EOF revokes worker actions. Regardless of cancellation or handshake outcome,
                // this job owns every child it published, including a late ProcessBuilder.start().
                try { if (process?.isAlive == true) process?.waitFor(8, TimeUnit.SECONDS) }
                finally { terminateOwnedProcess() }
            }
        }
    }

    private fun lose(reason: String) {
        if (intentionalClose.get()) return
        mutableError.value = reason
        mutableConnection.value = WorkerConnectionStatus.LOST
        failRequests(reason)
        closeTransport()
    }

    override suspend fun request(op: String, args: JsonObject): JsonObject {
        withTimeout(65_000) { ready.await() }
        check(!intentionalClose.get() && mutableConnection.value == WorkerConnectionStatus.CONNECTED) { mutableError.value ?: "实例未连接" }
        val id = UUID.randomUUID().toString()
        val result = CompletableDeferred<JsonObject>()
        pending[id] = result
        try {
            withContext(Dispatchers.IO) {
                checkNotNull(wire).write(wireMessage("request", instanceId, workerRunId) { put("requestId", id); put("op", op); put("args", args) })
            }
            return withTimeout(180_000) { result.await() }
        } finally { pending.remove(id) }
    }

    override fun setSelected(selected: Boolean) {
        this.selected.set(selected)
        if (!intentionalClose.get() && mutableConnection.value == WorkerConnectionStatus.CONNECTED) work.launch(Dispatchers.IO) {
            runCatching { wire?.write(wireMessage("selected", instanceId, workerRunId) { put("value", selected) }) }
                .onFailure { lose("预览连接已中断") }
        }
    }

    override suspend fun shutdown(): Unit = withContext(NonCancellable + Dispatchers.IO) {
        if (!intentionalClose.compareAndSet(false, true)) { closed.await(); return@withContext }
        try {
            // Do not wait for the write lock: closing the socket unblocks any writer and the reader.
            closeTransport()
            failRequests("实例已关闭")
            try { launchJob.cancelAndJoin() }
            finally { terminateOwnedProcess() }
            mutableConnection.value = WorkerConnectionStatus.CLOSED
            closed.complete(Unit)
        } catch (failure: Throwable) {
            mutableError.value = failure.message ?: "实例关闭失败"
            mutableConnection.value = WorkerConnectionStatus.LOST
            closed.completeExceptionally(failure)
            throw failure
        } finally {
            closeTransport()
            failRequests("实例已关闭")
            work.cancel()
        }
    }

    private fun closeTransport() {
        runCatching { server?.close() }
        wire?.close()
    }

    private fun failRequests(reason: String) {
        pending.values.forEach { it.completeExceptionally(IllegalStateException(reason)) }; pending.clear()
        if (!ready.isCompleted) ready.completeExceptionally(IllegalStateException(reason))
    }

    private fun terminateOwnedProcess() {
        // The platform containment owns this worker only; never enumerate or kill unrelated ADB processes.
        runCatching { containment?.close() }; containment = null
        process?.takeIf { it.isAlive }?.let {
            it.destroy()
            if (!it.waitFor(1, TimeUnit.SECONDS)) check(it.destroyForcibly().waitFor(3, TimeUnit.SECONDS)) {
                "实例进程尚未退出，不能释放此标签"
            }
        }
    }
}
