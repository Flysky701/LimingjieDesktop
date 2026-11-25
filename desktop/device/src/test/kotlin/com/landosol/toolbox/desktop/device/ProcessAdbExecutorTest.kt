package com.landosol.toolbox.desktop.device

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class ProcessAdbExecutorTest {
    @get:Rule val folder = TemporaryFolder()
    private val adb by lazy { AdbTestProcesses(folder.root.toPath()) }

    @Test fun drains64MbStdoutAnd8MbStderrConcurrentlyWithoutChangingBinary() = runBlocking {
        ProcessAdbExecutor("fake", AdbProcessFactory { adb.start("large") }).use { runner ->
            val result = runner.execute(listOf("exec-out", "screencap"), timeoutMillis = 15_000, screenshot = true) as AdbResult.Success
            assertEquals(64 * 1024 * 1024, result.stdout.size); assertEquals(8 * 1024 * 1024, result.stderr.length)
            assertEquals(42.toByte(), result.stdout.first()); assertEquals(42.toByte(), result.stdout.last())
        }
    }
    @Test fun timeoutKillsParentAndDescendant() = runBlocking {
        var parent: Process? = null
        var descendant: ProcessHandle? = null
        ProcessAdbExecutor("fake", AdbProcessFactory {
            val process = adb.startParent().also { parent = it }
            try {
                // Start the command's timeout only after the real process tree exists.
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                while (descendant == null && process.isAlive && System.nanoTime() < deadline) {
                    descendant = process.children().use { it.findFirst().orElse(null) }
                    if (descendant == null) Thread.sleep(10)
                }
                check(descendant?.isAlive == true) { "fixture did not start its descendant" }
                process
            } catch (failure: Throwable) {
                ProcessAdbExecutor.terminate(process)
                throw failure
            }
        }).use { runner ->
            val result = runner.execute(listOf("wait"), timeoutMillis = 250)
            assertEquals(AdbFailureKind.Timeout, (result as AdbResult.Failure).kind)
            withTimeout(3000) { while (parent!!.isAlive || descendant!!.isAlive) delay(10) }
        }
    }
    @Test fun completedStartupLeavesSharedDaemonAliveAfterExecutorCloses() = runBlocking {
        val pidFile = folder.root.toPath().resolve("daemon.pid")
        try {
            ProcessAdbExecutor("fake", AdbProcessFactory { adb.start("daemon", pidFile.toString()) }).use { runner ->
                assertTrue(runner.execute(listOf("start-server")) is AdbResult.Success)
            }
            val daemon = ProcessHandle.of(Files.readString(pidFile).toLong()).orElse(null)
            assertTrue("Closing a completed adb client must not kill its shared daemon", daemon?.isAlive == true)
        } finally {
            if (Files.exists(pidFile)) ProcessHandle.of(Files.readString(pidFile).toLong()).orElse(null)?.let {
                if (it.isAlive) { it.destroyForcibly(); it.onExit().get(3, TimeUnit.SECONDS) }
            }
        }
    }
    @Test fun argvSupportsChineseSpacesAndShellQuotes() = runBlocking {
        val calls = CopyOnWriteArrayList<List<String>>()
        val path = "C:/中文 模拟器/adb.exe"
        ProcessAdbExecutor(path, AdbProcessFactory { args -> calls += args; adb.start("echo", *args.toTypedArray()) }).use { runner ->
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
            if (args.last() == "start-server") { starts.incrementAndGet(); adb.respond() }
            else adb.respond(stderr = "cannot connect to daemon at tcp:5037: Connection refused", exitCode = 1)
        }).use { runner -> repeat(2) { runner.execute(listOf("devices")) }; assertEquals(1, starts.get()) }
        val conflictCalls = AtomicInteger()
        ProcessAdbExecutor("fake", AdbProcessFactory {
            conflictCalls.incrementAndGet()
            adb.respond(stderr = "adb server version (39) doesn't match this client (41)", exitCode = 1)
        }).use {
            assertEquals(AdbFailureKind.ServerVersionConflict, (it.execute(listOf("devices")) as AdbResult.Failure).kind)
            assertEquals(1, conflictCalls.get())
        }
    }
    @Test fun daemonRestartRecoversAgainAfterMidRunCrash() = runBlocking {
        val starts = AtomicInteger()
        var daemonAlive = false
        ProcessAdbExecutor("fake", AdbProcessFactory { args ->
            if (args.last() == "start-server") {
                starts.incrementAndGet()
                daemonAlive = true
                adb.respond()
            } else if (!daemonAlive) {
                adb.respond(stderr = "cannot connect to adb daemon at 127.0.0.1:5037", exitCode = 1)
            } else {
                adb.respond(stdout = "ok")
            }
        }).use { runner ->
            assertTrue(runner.execute(listOf("devices")) is AdbResult.Success)
            assertEquals(1, starts.get())
            daemonAlive = false
            assertTrue(runner.execute(listOf("devices")) is AdbResult.Success)
            assertEquals(2, starts.get())
        }
    }
    @Test fun repeatedDaemonStartupPreservesSuccessfulCommandAndDiagnostics() = runBlocking {
        val diagnostic = "* daemon not running; starting now at tcp:5037\n* daemon started successfully"
        val output = "List of devices attached\n127.0.0.1:5555\tdevice\n"
        ProcessAdbExecutor("fake", AdbProcessFactory { adb.respond(stdout = output, stderr = diagnostic) }).use {
            repeat(2) { _ ->
                val result = it.execute(listOf("devices")) as AdbResult.Success
                assertEquals(output, result.text())
                assertEquals(diagnostic, result.stderr)
            }
        }
    }
    @Test fun recoveredVersionMismatchPreservesSuccessfulConnection() = runBlocking {
        val diagnostic = "adb server version (39) doesn't match this client (41); killing...\n* daemon started successfully"
        ProcessAdbExecutor("fake", AdbProcessFactory { adb.respond(stdout = "connected to 127.0.0.1:5555", stderr = diagnostic) }).use {
            val result = it.execute(listOf("connect", "127.0.0.1:5555")) as AdbResult.Success
            assertEquals("connected to 127.0.0.1:5555", result.text())
            assertEquals(diagnostic, result.stderr)
        }
    }
    @Test fun repeatedFailedStartupIsConnectionFailureWithoutInventedVersionConflict() = runBlocking {
        val diagnostic = "* daemon not running; starting now at tcp:5037\n* failed to start daemon"
        ProcessAdbExecutor("fake", AdbProcessFactory { adb.respond(stderr = diagnostic, exitCode = 1) }).use {
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
            if (args.last() == "capture") adb.respond(stdout = "frame") else adb.block().also { processes += it }
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

internal class AdbTestProcesses(private val root: Path) {
    private val windows = System.getProperty("os.name").startsWith("Windows")

    fun start(vararg args: String): Process {
        val java = Path.of(System.getProperty("java.home"), "bin", if (windows) "java.exe" else "java")
        val classpath = listOf(ProcessFixture::class.java, Unit::class.java)
            .joinToString(File.pathSeparator) { Path.of(it.protectionDomain.codeSource.location.toURI()).toString() }
        val encoded = args.map { Base64.getEncoder().encodeToString(it.toByteArray(Charsets.UTF_8)) }
        val command = listOf(
            java.toString(), "-XX:TieredStopAtLevel=1", "-XX:+UseSerialGC", "-XX:ActiveProcessorCount=2",
            "-cp", classpath, ProcessFixture::class.java.name,
        )
        return ProcessBuilder(command + encoded).start()
    }

    fun respond(stdout: String = "", stderr: String = "", exitCode: Int = 0): Process {
        if (!windows) return start("error", stderr, exitCode.toString(), stdout)
        if (stdout.isEmpty() && stderr.isEmpty()) {
            return ProcessBuilder("cmd.exe", "/d", "/q", "/c", "exit", "/b", exitCode.toString()).start()
        }
        val directory = Files.createTempDirectory(root, "adb-response-")
        // Keep payload bytes in files so cmd never interprets adb arguments, quotes, or Unicode.
        val script = buildString {
            if (stdout.isNotEmpty()) {
                Files.writeString(directory.resolve("stdout.txt"), stdout)
                append("type stdout.txt\r\n")
            }
            if (stderr.isNotEmpty()) {
                Files.writeString(directory.resolve("stderr.txt"), stderr)
                append("type stderr.txt 1>&2\r\n")
            }
            append("exit /b $exitCode\r\n")
        }
        Files.writeString(directory.resolve("response.cmd"), script)
        return ProcessBuilder("cmd.exe", "/d", "/q", "/c", "response.cmd").directory(directory.toFile()).start()
    }

    fun block(): Process =
        if (windows) ProcessBuilder("cmd.exe", "/d", "/q", "/c", "pause").start() else start("sleep")

    fun startParent(): Process =
        if (windows) ProcessBuilder("cmd.exe", "/d", "/q", "/c", "cmd.exe /d /q /c pause").start()
        else start("parent", root.resolve("child.pid").toString())
}
