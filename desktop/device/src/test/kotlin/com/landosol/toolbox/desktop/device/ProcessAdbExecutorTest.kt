package com.landosol.toolbox.desktop.device

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

class ProcessAdbExecutorTest {
    @get:Rule val folder = TemporaryFolder()
    @Test fun drains64MbStdoutAnd8MbStderrConcurrentlyWithoutChangingBinary() = runBlocking {
        ProcessAdbExecutor("fake", AdbProcessFactory { ProcessFixture.start("large") }).use { runner ->
            val result = runner.execute(listOf("exec-out", "screencap"), timeoutMillis = 15_000, screenshot = true) as AdbResult.Success
            assertEquals(64 * 1024 * 1024, result.stdout.size); assertEquals(8 * 1024 * 1024, result.stderr.length)
            assertEquals(42.toByte(), result.stdout.first()); assertEquals(42.toByte(), result.stdout.last())
        }
    }
    @Test fun timeoutKillsParentAndDescendant() = runBlocking {
        val pidFile = folder.root.toPath().resolve("child.pid")
        var parent: Process? = null
        ProcessAdbExecutor("fake", AdbProcessFactory { ProcessFixture.start("parent", pidFile.toString()).also { parent = it } }).use { runner ->
            val result = runner.execute(listOf("wait"), timeoutMillis = 2500)
            assertEquals(AdbFailureKind.Timeout, (result as AdbResult.Failure).kind)
            assertFalse(parent!!.isAlive)
            val pid = Files.readString(pidFile).toLong()
            withTimeout(3000) { while (ProcessHandle.of(pid).orElse(null)?.isAlive == true) delay(20) }
        }
    }
    @Test fun completedStartupLeavesSharedDaemonAliveAfterExecutorCloses() = runBlocking {
        val pidFile = folder.root.toPath().resolve("daemon.pid")
        try {
            ProcessAdbExecutor("fake", AdbProcessFactory { ProcessFixture.start("daemon", pidFile.toString()) }).use { runner ->
                assertTrue(runner.execute(listOf("start-server")) is AdbResult.Success)
            }
            val daemon = ProcessHandle.of(Files.readString(pidFile).toLong()).orElse(null)
            assertTrue("Closing a completed adb client must not kill its shared daemon", daemon?.isAlive == true)
        } finally {
            if (Files.exists(pidFile)) ProcessHandle.of(Files.readString(pidFile).toLong()).orElse(null)?.let {
                if (it.isAlive) { it.destroyForcibly(); it.onExit().get(3, java.util.concurrent.TimeUnit.SECONDS) }
            }
        }
    }
    @Test fun argvSupportsChineseSpacesAndShellQuotes() = runBlocking {
        val calls = CopyOnWriteArrayList<List<String>>()
        val path = "C:/中文 模拟器/adb.exe"
        ProcessAdbExecutor(path, AdbProcessFactory { args -> calls += args; ProcessFixture.start("echo", *args.toTypedArray()) }).use { runner ->
            val result = runner.shell("127.0.0.1:1234", listOf("echo", "含 空格", "a'b", "\$(never-execute)")) as AdbResult.Success
            assertEquals(listOf(path, "-s", "127.0.0.1:1234", "shell", "'echo' '含 空格' 'a'\\''b' '\$(never-execute)'"), calls.single())
            assertEquals(calls.single().joinToString("|"), result.text())
        }
    }
    @Test fun allKnownFailurePhrasesAreClassified() {
        val fixtures = mapOf(
            "error: device offline" to AdbFailureKind.DeviceOffline,
            "error: device unauthorized" to AdbFailureKind.Unauthorized,
            "error: device 'emulator-5554' not found" to AdbFailureKind.DeviceNotFound,
            "adb server version (39) doesn't match this client (41); killing..." to AdbFailureKind.ServerVersionConflict,
            "cannot connect to 127.0.0.1:5555" to AdbFailureKind.ConnectionRefused,
            "failed to connect to '127.0.0.1:5555': Connection refused" to AdbFailureKind.ConnectionRefused,
            "error: no devices/emulators found" to AdbFailureKind.DeviceNotFound,
            "error: closed" to AdbFailureKind.ConnectionRefused,
            "error: protocol fault (couldn't read status)" to AdbFailureKind.ConnectionRefused,
            "unexpected exit" to AdbFailureKind.NonZeroExit,
        )
        fixtures.forEach { (phrase, kind) -> assertEquals(phrase, kind, classifyAdbFailure(1, phrase)!!.kind) }
        assertEquals(AdbFailureKind.ConnectionRefused, classifyAdbFailure(0, "", "cannot connect to 127.0.0.1:5555")!!.kind)
        assertTrue(classifyAdbFailure(1, "adb server version mismatch")!!.detail.contains("设备 → ADB 工具"))
        assertEquals(AdbFailureKind.ConnectionRefused, classifyAdbFailure(0, "adb server version mismatch", "failed to connect to 127.0.0.1:5555")!!.kind)
    }
    @Test fun missingAndLaunchFailuresAreTyped() = runBlocking {
        ProcessAdbExecutor(folder.root.resolve("missing/adb.exe").path).use { assertEquals(AdbFailureKind.AdbMissing, (it.execute(listOf("version")) as AdbResult.Failure).kind) }
        ProcessAdbExecutor("invalid", AdbProcessFactory { throw java.io.IOException("synthetic launch failed") }).use { assertEquals(AdbFailureKind.LaunchFailed, (it.execute(listOf("version")) as AdbResult.Failure).kind) }
    }
    @Test fun daemonStartsOnlyOnceAndVersionConflictNeverRestarts() = runBlocking {
        val starts = AtomicInteger()
        ProcessAdbExecutor("fake", AdbProcessFactory { args ->
            if (args.last() == "start-server") { starts.incrementAndGet(); ProcessFixture.start("echo") }
            else ProcessFixture.start("error", "cannot connect to daemon at tcp:5037: Connection refused", "1")
        }).use { runner -> repeat(2) { runner.execute(listOf("devices")) }; assertEquals(1, starts.get()) }
        val conflictCalls = AtomicInteger()
        ProcessAdbExecutor("fake", AdbProcessFactory { conflictCalls.incrementAndGet(); ProcessFixture.start("error", "adb server version (39) doesn't match this client (41)", "1") }).use {
            assertEquals(AdbFailureKind.ServerVersionConflict, (it.execute(listOf("devices")) as AdbResult.Failure).kind)
            assertEquals(1, conflictCalls.get())
        }
    }
    @Test fun repeatedDaemonStartupPreservesSuccessfulCommandAndDiagnostics() = runBlocking {
        val diagnostic = "* daemon not running; starting now at tcp:5037\n* daemon started successfully"
        val output = "List of devices attached\n127.0.0.1:5555\tdevice\n"
        ProcessAdbExecutor("fake", AdbProcessFactory { ProcessFixture.start("error", diagnostic, "0", output) }).use {
            repeat(2) { _ ->
                val result = it.execute(listOf("devices")) as AdbResult.Success
                assertEquals(output, result.text())
                assertEquals(diagnostic, result.stderr)
            }
        }
    }
    @Test fun recoveredVersionMismatchPreservesSuccessfulConnection() = runBlocking {
        val diagnostic = "adb server version (39) doesn't match this client (41); killing...\n* daemon started successfully"
        ProcessAdbExecutor("fake", AdbProcessFactory { ProcessFixture.start("error", diagnostic, "0", "connected to 127.0.0.1:5555") }).use {
            val result = it.execute(listOf("connect", "127.0.0.1:5555")) as AdbResult.Success
            assertEquals("connected to 127.0.0.1:5555", result.text())
            assertEquals(diagnostic, result.stderr)
        }
    }
    @Test fun repeatedFailedStartupIsConnectionFailureWithoutInventedVersionConflict() = runBlocking {
        val diagnostic = "* daemon not running; starting now at tcp:5037\n* failed to start daemon"
        ProcessAdbExecutor("fake", AdbProcessFactory { ProcessFixture.start("error", diagnostic, "1") }).use {
            repeat(2) { _ ->
                val result = it.execute(listOf("devices")) as AdbResult.Failure
                assertEquals(AdbFailureKind.ConnectionRefused, result.kind)
                assertEquals(diagnostic, result.detail)
            }
        }
    }
    @Test fun screenshotDoesNotWaitForFourOrdinaryCommandsAndCancellationKillsThem() = runBlocking {
        val processes = CopyOnWriteArrayList<Process>()
        ProcessAdbExecutor("fake", AdbProcessFactory { args ->
            if (args.last() == "capture") ProcessFixture.start("echo", "frame") else ProcessFixture.start("sleep").also { processes += it }
        }).use { runner ->
            val jobs = List(5) { launch { runner.execute(listOf("wait"), timeoutMillis = 20_000) } }
            withTimeout(5000) { while (processes.size < 4) delay(10) }
            assertEquals("frame", (runner.execute(listOf("capture"), timeoutMillis = 2000, screenshot = true) as AdbResult.Success).text())
            assertEquals(4, processes.size)
            jobs.forEach { it.cancel() }; jobs.joinAll()
            withTimeout(3000) { while (processes.any(Process::isAlive)) delay(10) }
        }
    }
}
