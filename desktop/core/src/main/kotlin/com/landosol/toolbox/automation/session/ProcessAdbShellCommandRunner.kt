package com.landosol.toolbox.automation.session

import java.io.InputStream
import java.util.concurrent.TimeUnit

/**
 * 开发阶段 ADB 真机执行器：通过 `ProcessBuilder` 调用系统 PATH 中的 adb 二进制。
 *
 * 仅用于开发/回归（PC 通过 USB 连接实机，adb 已加入 PATH）。
 * 发布版本不会装配本类；无 Root 版本使用 [RecentsSwipeTerminator] / 用户协助降级链。
 */
class ProcessAdbShellCommandRunner(
    private val adbBinary: String = "adb",
) : ShellCommandRunner {
    override suspend fun run(command: List<String>, timeoutMillis: Long): ShellCommandResult {
        require(command.isNotEmpty()) { "command 不能为空" }
        return try {
            val process = ProcessBuilder(command).start()
            val finished = process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)
            if (!finished) {
                process.destroyForcibly()
                return ShellCommandResult(exitCode = -1, output = "command timeout")
            }
            val output = readOutput(process.inputStream) + readOutput(process.errorStream)
            ShellCommandResult(exitCode = process.exitValue(), output = output.trim())
        } catch (error: Exception) {
            ShellCommandResult(exitCode = -1, output = "Cannot launch adb: ${error.message}")
        }
    }

    private fun readOutput(input: InputStream): String =
        input.use { stream ->
            val buffer = ByteArray(MAX_BUFFER_BYTES)
            val read = stream.read(buffer)
            if (read > 0) String(buffer, 0, read) else ""
        }

    private companion object {
        const val MAX_BUFFER_BYTES = 8 * 1024
    }
}

/** 便捷构造：开发阶段 adb force-stop + am start 的组合器 */
fun devAdbResetBackend(
    adbBinary: String = "adb",
    gamePackageName: String,
    launchComponent: String,
    presence: GameClientPresenceObserver,
): DevAdbSessionResetBackend {
    val shell = ProcessAdbShellCommandRunner(adbBinary)
    return DevAdbSessionResetBackend(shell, gamePackageName, launchComponent, presence)
}