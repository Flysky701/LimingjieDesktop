package com.landosol.toolbox.desktop.device

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

fun interface AdbProcessFactory { fun start(arguments: List<String>): Process }

/** No host shell is involved. Every subprocess is owned until completion or close. */
class ProcessAdbExecutor(
    override val executable: String,
    private val processFactory: AdbProcessFactory = AdbProcessFactory { ProcessBuilder(it).start() },
) : AdbExecutor {
    private val ordinary = Semaphore(4)
    private val screenshots = Semaphore(1)
    private val active = ConcurrentHashMap.newKeySet<Process>()
    private val closed = AtomicBoolean()
    private val serverStartAttempted = AtomicBoolean()

    override suspend fun execute(arguments: List<String>, serial: String?, timeoutMillis: Long, screenshot: Boolean): AdbResult {
        require(timeoutMillis > 0)
        val result = (if (screenshot) screenshots else ordinary).withPermit { executeOnce(arguments, serial, timeoutMillis, screenshot) }
        // FB-06 applies to the ADB daemon, not a refused emulator TCP port.
        if (result is AdbResult.Failure && result.kind == AdbFailureKind.ConnectionRefused &&
            Regex("daemon|server.*not running|cannot connect to.*5037", RegexOption.IGNORE_CASE).containsMatchIn(result.detail) &&
            arguments.firstOrNull() != "start-server" && serverStartAttempted.compareAndSet(false, true)) {
            val start = ordinary.withPermit { executeOnce(listOf("start-server"), null, 10_000, false) }
            if (start is AdbResult.Success) return (if (screenshot) screenshots else ordinary).withPermit { executeOnce(arguments, serial, timeoutMillis, screenshot) }
        }
        return result
    }

    override suspend fun shell(serial: String, arguments: List<String>, timeoutMillis: Long): AdbResult =
        execute(listOf("shell", arguments.joinToString(" ", transform = ::shellQuote)), serial, timeoutMillis)

    private suspend fun executeOnce(arguments: List<String>, serial: String?, timeoutMillis: Long, screenshot: Boolean): AdbResult = withContext(Dispatchers.IO) {
        if (closed.get()) return@withContext AdbResult.Failure(AdbFailureKind.LaunchFailed, "ADB 执行器已关闭")
        val process = try { openInteractive(arguments, serial) }
        catch (error: IOException) {
            val missing = !Files.isRegularFile(Path.of(executable)) && (executable.any { it == '/' || it == '\\' } || error.message.orEmpty().contains("error=2") || error.message.orEmpty().contains("No such file"))
            return@withContext AdbResult.Failure(if (missing) AdbFailureKind.AdbMissing else AdbFailureKind.LaunchFailed, "无法启动 adb：${error.message}")
        }
        try {
            withTimeout(timeoutMillis) {
                coroutineScope {
                    val stdout = async(Dispatchers.IO) { process.inputStream.use { it.readBytes() } }
                    val stderr = async(Dispatchers.IO) { process.errorStream.use { it.readBytes().toString(Charsets.UTF_8).replace("\r\n", "\n") } }
                    try {
                        val exited = runInterruptible(Dispatchers.IO) { process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS) }
                        if (!exited) { terminate(process); return@coroutineScope AdbResult.Failure(AdbFailureKind.Timeout, "adb 命令超时（${timeoutMillis}ms）") }
                        val output = stdout.await()
                        val errors = stderr.await()
                        val textOutput = if (!screenshot && arguments.firstOrNull() in setOf("connect", "start-server")) output.toString(Charsets.UTF_8) else ""
                        val classification = classifyAdbFailure(process.exitValue(), errors, textOutput)
                        classification ?: AdbResult.Success(process.exitValue(), output, errors)
                    } finally { if (!isActive) terminate(process) }
                }
            }
        } catch (_: TimeoutCancellationException) {
            terminate(process)
            AdbResult.Failure(AdbFailureKind.Timeout, "adb 命令超时（${timeoutMillis}ms）")
        } catch (cancelled: CancellationException) { terminate(process); throw cancelled }
        catch (error: IOException) { AdbResult.Failure(AdbFailureKind.LaunchFailed, "adb 数据读取失败：${error.message}") }
        finally { active.remove(process); terminate(process) }
    }

    /** For MaaTouch's persistent channel only; caller owns both output readers and must release it. */
    fun openInteractive(arguments: List<String>, serial: String?): Process {
        check(!closed.get()) { "ADB 执行器已关闭" }
        val command = buildList { add(executable); if (serial != null) { add("-s"); add(serial) }; addAll(arguments) }
        return processFactory.start(command).also { process ->
            active.add(process)
            if (closed.get()) { release(process); error("ADB 执行器已关闭") }
        }
    }

    fun release(process: Process) { active.remove(process); terminate(process) }
    override fun close() { if (closed.compareAndSet(false, true)) active.toList().forEach(::release) }

    companion object {
        fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
        internal fun terminate(process: Process) {
            process.descendants().use { children -> children.toList().asReversed().forEach { if (it.isAlive) it.destroyForcibly() } }
            if (process.isAlive) process.destroyForcibly()
            try { process.outputStream.close() } catch (_: IOException) { }
            try { process.inputStream.close() } catch (_: IOException) { }
            try { process.errorStream.close() } catch (_: IOException) { }
        }
    }
}

internal fun classifyAdbFailure(exitCode: Int, stderr: String, stdout: String = ""): AdbResult.Failure? {
    val text = (stderr + "\n" + stdout).trim()
    val lower = text.lowercase()
    val kind = when {
        // ADB can recover from an old server itself. Startup diagnostics alone do not make a successful command fail.
        exitCode != 0 && "server version" in lower && ("doesn't match" in lower || "mismatch" in lower) -> AdbFailureKind.ServerVersionConflict
        "unauthorized" in lower -> AdbFailureKind.Unauthorized
        "device offline" in lower -> AdbFailureKind.DeviceOffline
        "no devices/emulators found" in lower || Regex("device .* not found").containsMatchIn(lower) -> AdbFailureKind.DeviceNotFound
        listOf("cannot connect to", "failed to connect", "connection refused", "error: closed", "protocol fault", "daemon not running").any { it in lower } && exitCode != 0 -> AdbFailureKind.ConnectionRefused
        ("failed to connect" in lower || "cannot connect to" in lower || "connection refused" in lower) -> AdbFailureKind.ConnectionRefused
        exitCode != 0 -> AdbFailureKind.NonZeroExit
        else -> return null
    }
    val detail = when (kind) {
        AdbFailureKind.ServerVersionConflict -> "ADB 客户端与服务端版本不匹配，命令未成功。请在“设备 → ADB 工具”选择模拟器自带的 adb.exe 并保存；若仍失败，请关闭其他使用 ADB 的工具后重试。"
        AdbFailureKind.Unauthorized -> "设备尚未授权，请在模拟器或设备上允许 USB 调试。"
        else -> text.ifBlank { "adb 退出码 $exitCode" }
    }
    return AdbResult.Failure(kind, detail)
}
