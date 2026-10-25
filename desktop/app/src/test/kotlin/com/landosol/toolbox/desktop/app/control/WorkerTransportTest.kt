package com.landosol.toolbox.desktop.app.control

import com.landosol.toolbox.desktop.platform.WorkerProcessContainment
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WorkerTransportTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `two child JVMs keep commands and previews isolated and close independently`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val java = Path.of(System.getProperty("java.home"), "bin", "java.exe").toString()
        val command = listOf(java, "-cp", System.getProperty("limingjie.test.classpath"), "com.landosol.toolbox.desktop.app.control.WorkerProtocolFixtureKt")
        val launcher = WorkerLauncher(scope, WorkerProcessContainment::own, command)
        val aDirectory = temporary.newFolder("one").toPath()
        val a = launcher.start(UUID.randomUUID().toString(), "one", aDirectory)
        val b = launcher.start(UUID.randomUUID().toString(), "two", temporary.newFolder("two").toPath())
        try {
            withTimeout(30_000) {
                a.connection.first { it != WorkerConnectionStatus.STARTING }
                b.connection.first { it != WorkerConnectionStatus.STARTING }
            }
            assertEquals(a.error.value, WorkerConnectionStatus.CONNECTED, a.connection.value)
            assertEquals(b.error.value, WorkerConnectionStatus.CONNECTED, b.connection.value)
            val first = a.request("add", buildJsonObject { put("add", 3) })
            val second = b.request("add", buildJsonObject { put("add", 7) })
            assertEquals(a.instanceId, first["instance"]!!.jsonPrimitive.content)
            assertEquals(b.instanceId, second["instance"]!!.jsonPrimitive.content)
            assertNotEquals(first["pid"], second["pid"])
            assertEquals(3, first["count"]!!.jsonPrimitive.int)
            assertEquals(7, second["count"]!!.jsonPrimitive.int)
            assertTrue(runCatching { a.request("reject") }.exceptionOrNull()?.message.orEmpty().contains("fixture rejection"))
            a.setSelected(false); b.setSelected(true)
            withTimeout(5_000) { a.snapshots.first { it["preview"]?.jsonPrimitive?.booleanOrNull == false } }
            assertEquals(3, a.request("read")["count"]!!.jsonPrimitive.int)
            a.shutdown()
            assertEquals(WorkerConnectionStatus.CLOSED, a.connection.value)
            assertEquals("revoked=true", Files.readString(aDirectory.resolve("shutdown.marker")))
            assertEquals(8, b.request("add", buildJsonObject { put("add", 1) })["count"]!!.jsonPrimitive.int)
        } finally { a.shutdown(); b.shutdown(); scope.cancel() }
    }

    @Test fun `wrong worker messages are ignored duplicate commands run once and channel loss revokes`() = runBlocking {
        val count = AtomicInteger()
        val revoked = AtomicBoolean()
        val stopped = AtomicBoolean()
        val domain = object : WorkerDomain {
            override suspend fun snapshot(includePreview: Boolean) = buildJsonObject { put("count", count.get()) }
            override suspend fun execute(op: String, args: JsonObject): JsonObject { delay(20); return buildJsonObject { put("count", count.incrementAndGet()) } }
            override fun revokeActions(reason: String) { revoked.set(true) }
            override suspend fun shutdown() { stopped.set(true) }
        }
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            val worker = async(Dispatchers.IO) { runWorkerTransport(server.localPort, "test-token", "profile", "run", domain) }
            val peer = withContext(Dispatchers.IO) { WorkerWire(server.accept().apply { soTimeout = 3_000 }) }
            try {
                withContext(Dispatchers.IO) {
                    assertEquals("hello", peer.read().wireString("kind"))
                    peer.write(wireMessage("hello.ok", "profile", "run"))
                    peer.write(wireMessage("request", "wrong-profile", "run") { put("requestId", "wrong"); put("op", "add") })
                    peer.write(wireMessage("request", "profile", "old-run") { put("requestId", "old"); put("op", "add") })
                    val command = wireMessage("request", "profile", "run") { put("requestId", "one-command"); put("op", "add") }
                    peer.write(command); peer.write(command)
                    var replies = 0
                    while (replies < 2) if (peer.read().wireString("kind") == "reply") replies++
                    assertEquals(1, count.get())
                }
            } finally { peer.close() }
            withTimeout(5_000) { worker.await() }
            assertTrue(revoked.get()); assertTrue(stopped.get())
        }
    }

    @Test fun `unresponsive supervisor revokes worker even while socket remains open`() = runBlocking {
        val revoked = AtomicBoolean()
        val stopped = AtomicBoolean()
        val domain = object : WorkerDomain {
            override suspend fun snapshot(includePreview: Boolean) = JsonObject(emptyMap())
            override suspend fun execute(op: String, args: JsonObject) = JsonObject(emptyMap())
            override fun revokeActions(reason: String) { revoked.set(true) }
            override suspend fun shutdown() { stopped.set(true) }
        }
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            val worker = async(Dispatchers.IO) { runWorkerTransport(server.localPort, "test-token", "profile", "run", domain, leaseMillis = 200) }
            withContext(Dispatchers.IO) { WorkerWire(server.accept()) }.use { peer ->
                withContext(Dispatchers.IO) { peer.read(); peer.write(wireMessage("hello.ok", "profile", "run")) }
                withTimeout(5_000) { worker.await() }
                assertTrue(revoked.get()); assertTrue(stopped.get())
            }
        }
    }

    @Test fun `failed connection still shuts down domain`() = runBlocking {
        val revoked = AtomicBoolean()
        val stopped = AtomicBoolean()
        val port = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }
        val domain = object : WorkerDomain {
            override suspend fun snapshot(includePreview: Boolean) = JsonObject(emptyMap())
            override suspend fun execute(op: String, args: JsonObject) = JsonObject(emptyMap())
            override fun revokeActions(reason: String) { revoked.set(true) }
            override suspend fun shutdown() { stopped.set(true) }
        }
        assertTrue(runCatching { runWorkerTransport(port, "test-token", "profile", "run", domain) }.isFailure)
        assertTrue(revoked.get()); assertTrue(stopped.get())
    }
}
