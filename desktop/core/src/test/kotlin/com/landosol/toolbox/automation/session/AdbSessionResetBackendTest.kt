package com.landosol.toolbox.automation.session

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AdbSessionResetBackendTest {
    private class FakeShell : ShellCommandRunner {
        val commands = mutableListOf<List<String>>()
        var nextExitCode = 0
        var output = ""

        override suspend fun run(command: List<String>, timeoutMillis: Long): ShellCommandResult {
            commands += command
            return ShellCommandResult(exitCode = nextExitCode, output = output)
        }
    }

    private class MutablePresence(
        private var foreground: Boolean = true,
    ) : GameClientPresenceObserver {
        fun setForeground(value: Boolean) {
            foreground = value
        }

        override suspend fun isGameForeground(): Boolean = foreground
    }

    @Test
    fun `force stop terminator runs adb shell am force-stop`() = runTest {
        val shell = FakeShell()
        val terminator = AdbForceStopTerminator(
            shell = shell,
            gamePackageName = "com.bilibili.priconne",
        )

        val result = terminator.terminate()

        assertEquals(ClientTerminationResult.TERMINATED, result)
        assertEquals(
            listOf("adb", "shell", "am", "force-stop", "com.bilibili.priconne"),
            shell.commands.single(),
        )
    }

    @Test
    fun `force stop failure reports unsupported`() = runTest {
        val shell = FakeShell().apply { nextExitCode = 1 }
        val terminator = AdbForceStopTerminator(shell, "com.bilibili.priconne")

        assertEquals(ClientTerminationResult.UNSUPPORTED, terminator.terminate())
    }

    @Test
    fun `relauncher runs adb shell am start with component`() = runTest {
        val shell = FakeShell()
        val relauncher = AdbRelauncher(shell, "com.bilibili.priconne/.test.MainActivity")

        val result = relauncher.relaunch()

        assertEquals(GameClientRelaunchResult.LAUNCH_REQUESTED, result)
        assertEquals(
            listOf("adb", "shell", "am", "start", "-n", "com.bilibili.priconne/.test.MainActivity"),
            shell.commands.single(),
        )
    }

    @Test
    fun `relaunch failure reports launch unavailable`() = runTest {
        val shell = FakeShell().apply { nextExitCode = 1 }
        val relauncher = AdbRelauncher(shell, "com.bilibili.priconne/.test.MainActivity")

        assertEquals(GameClientRelaunchResult.LAUNCH_UNAVAILABLE, relauncher.relaunch())
    }

    @Test
    fun `reset completes when client leaves foreground and relaunch succeeds`() = runTest {
        val presence = MutablePresence(foreground = true)
        val shell = FakeShell()
        val backend = DevAdbSessionResetBackend(
            shell = shell,
            gamePackageName = "com.bilibili.priconne",
            launchComponent = "com.bilibili.priconne/.test.MainActivity",
            presence = presence,
        )

        presence.setForeground(false)
        val result = backend.reset(presenceTimeoutMillis = 5_000L)

        assertTrue(result is SessionResetBackendResult.Completed)
        assertEquals(2, shell.commands.size)
    }

    @Test
    fun `rejects when client never leaves foreground`() = runTest {
        val shell = FakeShell()
        val backend = DevAdbSessionResetBackend(
            shell = shell,
            gamePackageName = "com.bilibili.priconne",
            launchComponent = "com.bilibili.priconne/.test.MainActivity",
            presence = FakePresence(staysForeground = true),
        )

        val result = backend.reset(presenceTimeoutMillis = 100L)

        assertTrue(result is SessionResetBackendResult.Rejected)
        assertEquals(1, shell.commands.size)
    }

    @Test
    fun `rejects when force stop command fails`() = runTest {
        val shell = FakeShell().apply { nextExitCode = 1 }
        val backend = DevAdbSessionResetBackend(
            shell = shell,
            gamePackageName = "com.bilibili.priconne",
            launchComponent = "com.bilibili.priconne/.test.MainActivity",
            presence = FakePresence(staysForeground = false),
        )

        val result = backend.reset(presenceTimeoutMillis = 5_000L)

        assertTrue(result is SessionResetBackendResult.Rejected)
        assertEquals(1, shell.commands.size)
    }

    private class FakePresence(
        private val staysForeground: Boolean,
    ) : GameClientPresenceObserver {
        override suspend fun isGameForeground(): Boolean = staysForeground
    }
}
