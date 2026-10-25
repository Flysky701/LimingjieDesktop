package com.landosol.toolbox.desktop.device

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.ceil
import kotlin.math.roundToInt

data class MaaTouchHeader(val contacts: Int, val width: Int, val height: Int, val pressure: Int, val pid: Long)

interface TouchConnection : AutoCloseable {
    val header: MaaTouchHeader
    val alive: Boolean
    suspend fun send(commands: String, durationMillis: Long)
}

fun interface TouchConnectionFactory {
    suspend fun open(adb: AdbExecutor, serial: String, dataDirectory: Path): TouchConnection
}

object MaaTouchProtocol {
    const val VERSION = "1.1.0"
    const val SHA256 = "4ea8590cd0349ce900f39ab16ef3751dad2356286b465b4293f80f9858c995d0"
    const val DEVICE_PATH = "/data/local/tmp/maatouch"
    fun parseHeader(lines: List<String>): MaaTouchHeader? {
        val dimensions = lines.firstNotNullOfOrNull { Regex("^\\^\\s+(\\d+)\\s+(\\d+)\\s+(\\d+)\\s+(\\d+)\\s*$").find(it) } ?: return null
        val pid = lines.firstNotNullOfOrNull { Regex("^\\$\\s+(\\d+)\\s*$").find(it)?.groupValues?.get(1)?.toLongOrNull() } ?: return null
        val values = dimensions.groupValues.drop(1).map { it.toIntOrNull() ?: return null }
        if (values.any { it <= 0 } || pid <= 0) return null
        return MaaTouchHeader(values[0], values[1], values[2], values[3], pid)
    }
    fun tap(x: Float, y: Float): String = "d 0 ${x.roundToInt()} ${y.roundToInt()} 1\nc\nw 60\nu 0\nc\n"
    fun swipe(fromX: Float, fromY: Float, toX: Float, toY: Float, durationMillis: Long, holdMillis: Long): String {
        require(durationMillis in 1..5000 && holdMillis.coerceAtLeast(0) <= 5000 - durationMillis)
        return buildString {
            append("d 0 ${fromX.roundToInt()} ${fromY.roundToInt()} 1\nc\n")
            val steps = ceil(durationMillis / 8.0).toInt()
            var elapsed = 0L
            for (step in 1..steps) {
                val targetTime = durationMillis * step / steps
                val fraction = step.toDouble() / steps
                append("w ${targetTime - elapsed}\nm 0 ${(fromX + (toX - fromX) * fraction).roundToInt()} ${(fromY + (toY - fromY) * fraction).roundToInt()} 1\nc\n")
                elapsed = targetTime
            }
            if (holdMillis > 0) append("w $holdMillis\n")
            append("u 0\nc\n")
        }
    }
    fun key(code: Int): String = "k $code o\nc\n"
    fun matchesDimensions(header: MaaTouchHeader, width: Int, height: Int): Boolean =
        (header.width == width && header.height == height) || (header.width == height && header.height == width)
    fun resourceBytes(): ByteArray {
        val bytes = MaaTouchProtocol::class.java.getResourceAsStream("/device/maatouch")?.use { it.readBytes() }
            ?: throw DeviceOperationException("MaaTouch 资源缺失，请重新解压完整发行包")
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        if (hash != SHA256) throw DeviceOperationException("MaaTouch 校验失败，请重新下载发行包")
        return bytes
    }
}

class MaaTouchConnection private constructor(
    private val owner: ProcessAdbExecutor,
    private val process: Process,
    private val serial: String,
    override val header: MaaTouchHeader,
    private val scope: CoroutineScope,
) : TouchConnection {
    private data class Request(val commands: String, val durationMillis: Long, val completion: CompletableDeferred<Unit>)
    private val queue = Channel<Request>(64)
    private val closed = AtomicBoolean()
    override val alive: Boolean get() = !closed.get() && process.isAlive
    private val actor = scope.launch {
        try {
            for (request in queue) {
                if (!request.completion.isActive) continue
                try {
                    if (!alive) throw DeviceOperationException("触控服务已断开")
                    process.outputStream.write(request.commands.toByteArray(Charsets.UTF_8))
                    process.outputStream.flush()
                    if (request.durationMillis > 0) delay(request.durationMillis + 20)
                    if (!alive) throw DeviceOperationException("触控服务已断开")
                    request.completion.complete(Unit)
                } catch (cancelled: CancellationException) { request.completion.cancel(cancelled); throw cancelled }
                catch (error: IOException) { request.completion.completeExceptionally(error) }
            }
        } finally {
            while (true) {
                val request = queue.tryReceive().getOrNull() ?: break
                request.completion.completeExceptionally(DeviceOperationException("触控服务已断开"))
            }
        }
    }
    override suspend fun send(commands: String, durationMillis: Long) {
        if (!alive) throw DeviceOperationException("触控服务已断开")
        val completion = CompletableDeferred<Unit>(currentCoroutineContext()[Job])
        queue.send(Request(commands, durationMillis, completion))
        completion.await()
    }
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        queue.close()
        actor.cancel()
        runBlocking { owner.shell(serial, listOf("kill", header.pid.toString()), timeoutMillis = 3000) }
        owner.release(process)
        scope.cancel()
    }
    companion object : TouchConnectionFactory {
        override suspend fun open(adb: AdbExecutor, serial: String, dataDirectory: Path): TouchConnection = withContext(Dispatchers.IO) {
            val executor = adb as? ProcessAdbExecutor ?: throw DeviceOperationException("当前 ADB 执行器不支持 MaaTouch 常驻连接")
            val local = dataDirectory.resolve("tools/maatouch")
            Files.createDirectories(local.parent)
            Files.write(local, MaaTouchProtocol.resourceBytes())
            adb.execute(listOf("push", local.toString(), MaaTouchProtocol.DEVICE_PATH), serial, 30_000).requireSuccess()
            val cleanup = adb.shell(serial, listOf("pkill", "-f", "com.shxyke.MaaTouch"))
            if (cleanup is AdbResult.Failure && !(cleanup.kind == AdbFailureKind.NonZeroExit && cleanup.detail == "adb 退出码 1")) throw DeviceOperationException(cleanup.detail, cleanup.kind)
            val process = executor.openInteractive(listOf("shell", "CLASSPATH=${MaaTouchProtocol.DEVICE_PATH} app_process / com.shxyke.MaaTouch.App"), serial)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val handshake = CompletableDeferred<MaaTouchHeader>()
            scope.launch {
                try {
                    val lines = ArrayList<String>()
                    process.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                        while (true) {
                            val line = reader.readLine() ?: break
                            if (!handshake.isCompleted) {
                                if (lines.size < 64) lines.add(line)
                                MaaTouchProtocol.parseHeader(lines)?.let { handshake.complete(it) }
                            }
                        }
                    }
                    if (!handshake.isCompleted) handshake.completeExceptionally(DeviceOperationException("MaaTouch 在返回头部之前退出"))
                } catch (error: IOException) { if (!handshake.isCompleted) handshake.completeExceptionally(error) }
            }
            // Drain stderr continuously without retaining device-specific text or an unbounded buffer.
            scope.launch { try { process.errorStream.use { input -> val buffer = ByteArray(8192); while (input.read(buffer) >= 0) { } } } catch (_: IOException) { } }
            try { MaaTouchConnection(executor, process, serial, withTimeout(5000) { handshake.await() }, scope) }
            catch (_: TimeoutCancellationException) {
                executor.release(process); scope.cancel()
                throw DeviceOperationException("MaaTouch 启动失败：5 秒内未返回头部")
            }
            catch (error: Throwable) { executor.release(process); scope.cancel(); throw error }
        }
    }
}
