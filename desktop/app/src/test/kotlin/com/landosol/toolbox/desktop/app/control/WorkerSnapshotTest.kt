package com.landosol.toolbox.desktop.app.control

import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Real loopback transport, no child JVM or device. */
class WorkerSnapshotTest {
    @Test fun idleSendsHeartbeatChangesSendStateAndStalledSnapshotsCannotLookAlive() = runBlocking {
        val initial = buildJsonObject { put("settings", "keep"); put("captcha", JsonNull); put("finished", false) }
        val state = AtomicReference(initial)
        val receiver = WorkerStateReceiver()
        val stall = AtomicBoolean()
        val stalled = CompletableDeferred<Unit>()
        val revoked = AtomicBoolean()
        val domain = object : WorkerDomain {
            override suspend fun snapshot(includePreview: Boolean): JsonObject {
                if (stall.get()) { stalled.complete(Unit); awaitCancellation() }
                return state.get()
            }
            override suspend fun execute(op: String, args: JsonObject) = JsonObject(emptyMap())
            override fun revokeActions(reason: String) { revoked.set(true) }
            override suspend fun shutdown() = Unit
        }
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            val worker = async(Dispatchers.IO) { runWorkerTransport(server.localPort, "test", "profile", "run", domain) }
            withContext(Dispatchers.IO) {
                WorkerWire(server.accept().apply { soTimeout = 3_000 }).use { peer ->
                    assertEquals("hello", peer.read().wireString("kind"))
                    peer.write(wireMessage("hello.ok", "profile", "run"))
                    val first = peer.read()
                    assertEquals("state", first.wireString("kind"))
                    assertEquals(1L, first.getValue("sequence").jsonPrimitive.long)
                    assertEquals(initial, receiver.accept(first, peer.lastReadBytes))
                    // No duplicate state in the next interval, just a lightweight heartbeat.
                    assertEquals("pong", peer.read().wireString("kind"))
                    val changes = buildJsonObject { put("captcha", "new-ticket"); put("finished", true) }
                    val changed = JsonObject(initial + changes)
                    state.set(changed)
                    peer.write(wireMessage("ping", "profile", "run"))
                    val second = peer.read()
                    assertEquals("state", second.wireString("kind"))
                    assertEquals(2L, second.getValue("sequence").jsonPrimitive.long)
                    assertEquals(changes, second["patch"])
                    assertEquals(changed, receiver.accept(second, peer.lastReadBytes))
                    stall.set(true)
                    withTimeout(3_000) { stalled.await() }
                    peer.write(wireMessage("ping", "profile", "run"))
                    assertThrows(SocketTimeoutException::class.java) { peer.read() }
                }
            }
            withTimeout(5_000) { worker.await() }
            assertTrue(revoked.get())
        }
    }

    private fun WorkerStateReceiver.accept(frame: JsonObject): JsonObject? =
        accept(frame, frame.toString().toByteArray(Charsets.UTF_8).size)

    @Test fun mergedPatchesMustFitTheFullFrameLimit() {
        val initial = buildJsonObject { put("catalog", "角".repeat(20)); put("logs", "甲".repeat(20)) }
        val full = checkNotNull(workerStateMessage("p", "r", 1, null, initial))
        val limit = full.toString().toByteArray(Charsets.UTF_8).size
        val receiver = WorkerStateReceiver(limit)
        assertEquals(initial, receiver.accept(full))

        val replaced = JsonObject(initial + ("logs" to JsonPrimitive("乙".repeat(20))))
        val valid = checkNotNull(workerStateMessage("p", "r", 2, initial, replaced))
        assertEquals(replaced, receiver.accept(valid))

        val larger = JsonObject(replaced + ("new" to JsonPrimitive("扩展")))
        val excess = checkNotNull(workerStateMessage("p", "r", 3, replaced, larger))
        assertTrue(excess.toString().toByteArray(Charsets.UTF_8).size < limit)
        assertThrows(IllegalArgumentException::class.java) { receiver.accept(excess) }
        val recovery = checkNotNull(workerStateMessage("p", "r", 3, replaced, initial))
        assertEquals(initial, receiver.accept(recovery))
    }

    @Test fun aPatchCanClearAndAddFieldsButRequiresItsExactBaseline() {
        val receiver = WorkerStateReceiver()
        val initial = buildJsonObject { put("catalog", "unchanged"); put("preview", "image") }
        val clear = buildJsonObject { put("preview", JsonNull); put("captcha", JsonNull) }
        val next = JsonObject(initial + clear)
        val full = checkNotNull(workerStateMessage("p", "r", 1, null, initial))
        val patch = checkNotNull(workerStateMessage("p", "r", 2, initial, next))

        assertEquals(clear, patch["patch"])
        assertNull(workerStateMessage("p", "r", 2, initial, Json.parseToJsonElement(initial.toString()).jsonObject))
        assertThrows(IllegalStateException::class.java) { receiver.accept(patch) }
        assertEquals(initial, receiver.accept(full))
        val gap = checkNotNull(workerStateMessage("p", "r", 3, initial, next))
        assertThrows(IllegalStateException::class.java) { receiver.accept(gap) }
        assertEquals(next, receiver.accept(patch))
        assertNull(receiver.accept(full))

        val removed = JsonObject(next - "catalog")
        val replacement = checkNotNull(workerStateMessage("p", "r", 3, next, removed))
        assertEquals(removed, replacement["state"])
        assertEquals(removed, receiver.accept(replacement))
        assertNull(receiver.accept(patch))
    }
}
