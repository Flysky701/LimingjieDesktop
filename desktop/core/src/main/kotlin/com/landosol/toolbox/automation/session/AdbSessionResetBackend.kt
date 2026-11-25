package com.landosol.toolbox.automation.session

/**
 * 开发阶段 ADB 实现。
 *
 * 产物：应用通过已授权的 ADB 会话（`adb shell`）请求执行
 * `am force-stop`。本实现依赖 ADB 通道抽象，避免把 `Runtime.exec` 直接
 * 写死在模块里，方便在 JVM 测试中注入假命令执行器。
 */
interface ShellCommandRunner {
    suspend fun run(command: List<String>, timeoutMillis: Long): ShellCommandResult
}

data class ShellCommandResult(
    val exitCode: Int,
    val output: String,
) {
    val succeeded: Boolean get() = exitCode == 0
}

/**
 * 开发用 ADB 强制停止执行器：通过 `adb shell am force-stop` 关闭客户端。
 * 仅用于开发阶段（PC 通过 ADB 连接实机），不允许进入最终发布版本。
 */
class AdbForceStopTerminator(
    private val shell: ShellCommandRunner,
    private val gamePackageName: String,
    private val commandTimeoutMillis: Long = 10_000L,
) : GameClientTerminator {
    override val kind: ClientTerminationKind get() = ClientTerminationKind.FORCE_STOP

    override suspend fun terminate(): ClientTerminationResult {
        val result = shell.run(
            listOf("adb", "shell", "am", "force-stop", gamePackageName),
            commandTimeoutMillis,
        )
        return if (result.succeeded) ClientTerminationResult.TERMINATED
        else ClientTerminationResult.UNSUPPORTED
    }

    override fun isSupported(): Boolean = true
}

/** 通过 `adb shell am start` 重启客户端 */
class AdbRelauncher(
    private val shell: ShellCommandRunner,
    private val launchComponent: String,
    private val commandTimeoutMillis: Long = 10_000L,
) : GameClientRelauncher {
    override suspend fun relaunch(): GameClientRelaunchResult {
        val result = shell.run(
            listOf("adb", "shell", "am", "start", "-n", launchComponent),
            commandTimeoutMillis,
        )
        return if (result.succeeded) GameClientRelaunchResult.LAUNCH_REQUESTED
        else GameClientRelaunchResult.LAUNCH_UNAVAILABLE
    }
}

/** 开发用组合器：force-stop 重启并等待客户端重新回到前台 */
class DevAdbSessionResetBackend(
    private val shell: ShellCommandRunner,
    gamePackageName: String,
    launchComponent: String,
    private val presence: GameClientPresenceObserver,
) : GameSessionResetBackend {
    private val terminator = AdbForceStopTerminator(shell, gamePackageName)
    private val relauncher = AdbRelauncher(shell, launchComponent)

    override val kind: ClientTerminationKind get() = ClientTerminationKind.FORCE_STOP

    override suspend fun terminateClient(): ClientTerminationResult =
        terminator.terminate()

    override suspend fun relaunchClient(): GameClientRelaunchResult =
        relauncher.relaunch()

    override fun isSupported(): Boolean = true

    suspend fun reset(
        presenceTimeoutMillis: Long = 30_000L,
    ): SessionResetBackendResult {
        when (terminator.terminate()) {
            ClientTerminationResult.TERMINATED -> Unit
            else -> return SessionResetBackendResult.Rejected("ADB 强制停止失败")
        }
        if (!waitUntilGone(presenceTimeoutMillis)) {
            return SessionResetBackendResult.Rejected("客户端未能在超时内退出")
        }
        when (relauncher.relaunch()) {
            GameClientRelaunchResult.LAUNCH_REQUESTED -> return SessionResetBackendResult.Completed
            GameClientRelaunchResult.LAUNCH_UNAVAILABLE ->
                return SessionResetBackendResult.Rejected("无法重启客户端")
        }
    }

    private suspend fun waitUntilGone(timeoutMillis: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            if (!presence.isGameForeground()) return true
            kotlinx.coroutines.delay(POLL_INTERVAL_MILLIS)
        }
        return false
    }

    private companion object {
        const val POLL_INTERVAL_MILLIS = 500L
    }
}

sealed interface SessionResetBackendResult {
    data object Completed : SessionResetBackendResult
    data class Rejected(val reason: String) : SessionResetBackendResult
}