package com.landosol.toolbox.desktop.app.control

import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.*
import kotlinx.serialization.json.*

/** Runs only in the child JVM. Closing the supervisor connection revokes all device actions. */
internal suspend fun runWorkerTransport(
    port: Int,
    token: String,
    profileId: String,
    runId: String,
    domain: WorkerDomain,
    leaseMillis: Long = WorkerProtocol.LEASE_MILLIS,
) = withContext(Dispatchers.IO) {
    val socket = Socket()
    val work = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val finished = CompletableDeferred<Unit>()
    val closing = AtomicBoolean(false)
    val selected = AtomicBoolean(true)
    val lastMessage = AtomicLong(System.nanoTime())
    val sequence = AtomicLong()
    val requests = ConcurrentHashMap<String, CompletableDeferred<JsonObject>>()
    fun stop(reason: String) {
        if (closing.compareAndSet(false, true)) {
            try { domain.revokeActions(reason) }
            finally { runCatching { socket.close() }; finished.complete(Unit) }
        }
    }
    try {
        socket.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), port), 10_000)
        socket.tcpNoDelay = true
        socket.soTimeout = 15_000
        val wire = WorkerWire(socket)
        wire.write(wireMessage("hello", profileId, runId) { put("version", WorkerProtocol.VERSION); put("token", token) })
        val ack = wire.read()
        check(ack.wireString("kind") == "hello.ok" && ack.wireString("profileId") == profileId && ack.wireString("runId") == runId) { "总控连接验证失败" }
        socket.soTimeout = 0
        work.launch {
            try {
                while (isActive && !closing.get()) {
                    val message = wire.read()
                    if (message.wireString("profileId") != profileId || message.wireString("runId") != runId) continue
                    lastMessage.set(System.nanoTime())
                    when (message.wireString("kind")) {
                        "ping" -> wire.write(wireMessage("pong", profileId, runId))
                        "selected" -> selected.set((message["value"] as? JsonPrimitive)?.booleanOrNull == true)
                        "request" -> {
                            val requestId = message.wireString("requestId") ?: continue
                            val op = message.wireString("op") ?: continue
                            val completion = CompletableDeferred<JsonObject>()
                            val existing = requests.putIfAbsent(requestId, completion)
                            if (existing != null) {
                                launch { runCatching { wire.write(existing.await()) } }
                                continue
                            }
                            // Only completed entries are evicted; active operations stay deduplicated.
                            if (requests.size > 256) requests.entries.firstOrNull { it.value.isCompleted }?.let { requests.remove(it.key, it.value) }
                            if (op == "worker.shutdown") {
                                val reply = wireMessage("reply", profileId, runId) { put("requestId", requestId); put("result", buildJsonObject { put("accepted", true) }) }
                                completion.complete(reply)
                                wire.write(reply)
                                stop("控制实例已关闭")
                            } else launch {
                                val reply = try {
                                    check(!closing.get()) { "控制连接已结束" }
                                    val result = domain.execute(op, message.wireObject("args"))
                                    wireMessage("reply", profileId, runId) { put("requestId", requestId); put("result", result) }
                                } catch (cancelled: CancellationException) {
                                    completion.cancel(cancelled)
                                    throw cancelled
                                } catch (failure: Exception) {
                                    wireMessage("reply", profileId, runId) { put("requestId", requestId); put("error", failure.message ?: "操作失败") }
                                }
                                completion.complete(reply)
                                runCatching { wire.write(reply) }.onFailure { stop("总控连接已断开") }
                            }
                        }
                    }
                }
            } catch (_: Exception) { stop("总控连接已断开，任务已停止") }
        }
        work.launch {
            while (isActive && !closing.get()) {
                try {
                    val state = domain.snapshot(selected.get())
                    wire.write(wireMessage("state", profileId, runId) { put("sequence", sequence.incrementAndGet()); put("state", state) })
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { stop("状态连接已失效，任务已停止") }
                delay(500)
            }
        }
        work.launch {
            while (isActive && !closing.get()) {
                delay(minOf(1_000L, leaseMillis))
                if ((System.nanoTime() - lastMessage.get()) / 1_000_000L > leaseMillis) stop("总控失去响应，任务已停止")
            }
        }
        finished.await()
    } finally {
        try { stop("控制实例正在退出") }
        finally {
            work.cancel()
            try { withContext(NonCancellable) { withTimeoutOrNull(8_000) { domain.shutdown() } } }
            finally { runCatching { socket.close() } }
        }
    }
}
