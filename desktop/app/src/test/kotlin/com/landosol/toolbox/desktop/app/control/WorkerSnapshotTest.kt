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
        val state = AtomicReference(buildJsonObject { put("captcha", JsonNull); put("finished", false) })
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
                    // No duplicate state in the next interval, just a lightweight heartbeat.
                    assertEquals("pong", peer.read().wireString("kind"))
                    val changed = buildJsonObject { put("captcha", "new-ticket"); put("finished", true) }
                    state.set(changed)
                    peer.write(wireMessage("ping", "profile", "run"))
                    val second = peer.read()
                    assertEquals("state", second.wireString("kind"))
                    assertEquals(2L, second.getValue("sequence").jsonPrimitive.long)
                    assertEquals(changed, second["state"])
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
}
