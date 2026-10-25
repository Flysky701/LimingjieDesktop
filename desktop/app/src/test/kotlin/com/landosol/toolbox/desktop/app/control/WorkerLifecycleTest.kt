package com.landosol.toolbox.desktop.app.control

import com.landosol.toolbox.desktop.platform.InstanceCatalog
import com.landosol.toolbox.desktop.platform.WorkerProcessContainment
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Only disposable child JVMs and temporary files; no graph or emulator. */
class WorkerLifecycleTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun launcher(scope: CoroutineScope, timeout: Int = 8_000,
                         contain: (Long) -> AutoCloseable = WorkerProcessContainment::own): WorkerLauncher {
        val command = listOf(Path.of(System.getProperty("java.home"), "bin", "java.exe").toString(),
            "-cp", System.getProperty("limingjie.test.classpath"), LifecycleWorkerFixture::class.java.name)
        return WorkerLauncher(scope, contain, command, timeout)
    }
    private suspend fun connectedPid(link: WorkerLink): Long = withTimeout(15_000) {
        link.snapshots.first { it["pid"] != null }["pid"]!!.jsonPrimitive.long
    }
    private suspend fun exited(pid: Long) = withTimeout(15_000) {
        while (ProcessHandle.of(pid).orElse(null)?.isAlive == true) delay(20)
    }

    @Test fun blockedWriterCannotBlockCancelledOrRepeatedShutdown() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val link = launcher(scope).start(UUID.randomUUID().toString(), "silent", temporary.newFolder().toPath())
        try {
            val pid = connectedPid(link)
            val writer = async(Dispatchers.IO) { runCatching { link.request("large", buildJsonObject { put("payload", "x".repeat(8 * 1024 * 1024)) }) } }
            delay(250) // Fixture never reads again; a large frame cannot drain into its receive buffer.
            assertFalse(writer.isCompleted)
            val first = launch(start = CoroutineStart.UNDISPATCHED) { link.shutdown() }
            first.cancel()
            val second = async { link.shutdown() }
            withTimeout(20_000) { first.join(); second.await(); writer.await() }
            assertEquals(WorkerConnectionStatus.CLOSED, link.connection.value)
            exited(pid)
        } finally { link.shutdown(); scope.cancel() }
    }

    @Test fun silentWorkerTimesOutAndIsReapedWithoutExplicitShutdown() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val link = launcher(scope, timeout = 500).start(UUID.randomUUID().toString(), "silent", temporary.newFolder().toPath())
        try {
            val pid = connectedPid(link)
            withTimeout(5_000) { link.connection.first { it == WorkerConnectionStatus.LOST } }
            assertTrue(link.error.value.orEmpty().contains("响应超时"))
            exited(pid)
        } finally { link.shutdown(); scope.cancel() }
    }

    @Test fun cancellationWhileBindingNewChildStillReapsPublishedProcess() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val entered = CompletableDeferred<Long>()
        val release = CompletableDeferred<Unit>()
        val link = launcher(scope, contain = { pid ->
            entered.complete(pid)
            runBlocking { release.await() }
            WorkerProcessContainment.own(pid)
        }).start(UUID.randomUUID().toString(), "hold", temporary.newFolder().toPath())
        try {
            val pid = withTimeout(15_000) { entered.await() }
            val shutdown = launch(start = CoroutineStart.UNDISPATCHED) { link.shutdown() }
            shutdown.cancel()
            release.complete(Unit)
            withTimeout(20_000) { shutdown.join(); link.shutdown() }
            assertEquals(WorkerConnectionStatus.CLOSED, link.connection.value)
            exited(pid)
        } finally { release.complete(Unit); link.shutdown(); scope.cancel() }
    }

    @Test fun failedShellSaveCannotSkipSupervisorCleanupAndConcurrentCallsWait() = runBlocking {
        val root = temporary.newFolder().toPath()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val catalog = InstanceCatalog(root.resolve("catalog"), root.resolve("legacy"))
        catalog.rename(InstanceCatalog.DEFAULT_PROFILE_ID, "silent")
        val model = SupervisorModel(catalog, launcher(scope), root.resolve("shell"), scope)
        try {
            model.open(InstanceCatalog.DEFAULT_PROFILE_ID.toString())
            val link = model.instances.value.single().link
            val pid = connectedPid(link)
            // A nonempty directory at the file destination deterministically rejects atomic replace.
            val destination = root.resolve("shell/shell.json")
            Files.delete(destination)
            Files.createDirectory(destination)
            Files.writeString(destination.resolve("fixture"), "synthetic")
            val first = launch(start = CoroutineStart.UNDISPATCHED) { model.shutdown() }
            first.cancel()
            val second = async { model.shutdown() }
            withTimeout(20_000) { first.join(); second.await() }
            assertTrue(model.error.value.orEmpty().contains("保存标签状态失败"))
            assertTrue(model.instances.value.isEmpty())
            assertEquals(WorkerConnectionStatus.CLOSED, link.connection.value)
            exited(pid)
        } finally { model.shutdown(); scope.cancel() }
    }
}

object LifecycleWorkerFixture {
    @JvmStatic fun main(args: Array<String>) {
        fun argument(key: String) = args[args.indexOf(key) + 1]
        if (argument("--instance-name") == "hold") { Thread.sleep(60_000); return }
        val id = argument("--instance-id")
        val run = argument("--worker-run-id")
        val socket = Socket().apply {
            connect(InetSocketAddress(InetAddress.getLoopbackAddress(), System.getenv(WorkerProtocol.PORT_ENV).toInt()), 5_000)
        }
        WorkerWire(socket).use { wire ->
            wire.write(wireMessage("hello", id, run) {
                put("version", WorkerProtocol.VERSION); put("token", System.getenv(WorkerProtocol.TOKEN_ENV))
            })
            check(wire.read().wireString("kind") == "hello.ok")
            wire.write(wireMessage("state", id, run) {
                put("sequence", 1)
                put("state", buildJsonObject { put("pid", ProcessHandle.current().pid()) })
            })
            // Hold the socket open without reading ping, requests or close notifications.
            Thread.sleep(60_000)
        }
    }
}
