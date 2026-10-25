package com.landosol.toolbox.desktop.device

import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** ADB smart-socket client for frequent commands. No process per screenshot or status poll.
 * Like MAA's adb-lite, this talks to the existing server; it never kills/replaces that server.
 * Shell v2 keeps stdout, stderr and exit status separate (unlike legacy `shell:`).
 */
class AdbServerClient(private val address: InetSocketAddress = defaultAddress()) : AutoCloseable {
    private val sockets = ConcurrentHashMap.newKeySet<Socket>()
    private val closed = AtomicBoolean()

    /** Null means a command not implemented here (version, push, pull, persistent MaaTouch). */
    suspend fun execute(arguments: List<String>, serial: String?, timeoutMillis: Long): AdbResult? {
        val request = when {
            arguments == listOf("devices", "-l") -> "host:devices-l"
            arguments == listOf("get-state") && serial != null -> "host-serial:$serial:get-state"
            arguments.size == 2 && arguments[0] == "connect" -> "host:connect:${arguments[1]}"
            arguments.size == 2 && arguments[0] == "shell" && serial != null -> "shell,v2,raw:${arguments[1]}"
            // raw shell-v2 has no PTY/newline conversion and, unlike legacy exec:, also reports exit status.
            arguments.firstOrNull() == "exec-out" && serial != null -> "shell,v2,raw:" + arguments.drop(1).joinToString(" ", transform = ProcessAdbExecutor::shellQuote)
            else -> return null
        }
        require(timeoutMillis > 0)
        if (closed.get()) return AdbResult.Failure(AdbFailureKind.LaunchFailed, "ADB 通信已关闭")
        val socket = Socket()
        sockets.add(socket)
        try {
            if (closed.get()) return AdbResult.Failure(AdbFailureKind.LaunchFailed, "ADB 通信已关闭")
            return withTimeout(timeoutMillis) {
                coroutineScope {
                    val work = async(Dispatchers.IO) {
                        val wire = Wire(socket, System.nanoTime() + timeoutMillis * 1_000_000)
                        socket.connect(address, timeoutMillis.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
                        socket.tcpNoDelay = true
                        if (!request.startsWith("host")) wire.request("host:transport:$serial")
                        wire.request(request)
                        when {
                            request.startsWith("shell,") -> wire.shellResult()
                            else -> {
                                val text = wire.message()
                                classifyAdbFailure(0, "", if (arguments[0] == "connect") text else "")
                                    ?: AdbResult.Success(0, text.toByteArray(Charsets.UTF_8), "")
                            }
                        }
                    }
                    // Await cancellation closes the socket before coroutineScope joins its blocked IO child.
                    try { work.await() } finally { socket.close() }
                }
            }
        } catch (_: TimeoutCancellationException) {
            return AdbResult.Failure(AdbFailureKind.Timeout, "ADB 命令超时（${timeoutMillis}ms）")
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: SocketTimeoutException) { return AdbResult.Failure(AdbFailureKind.Timeout, "ADB 命令超时（${timeoutMillis}ms）") }
        catch (_: ConnectException) {
            return AdbResult.Failure(AdbFailureKind.ConnectionRefused, "cannot connect to adb daemon at ${address.hostString}:${address.port}")
        } catch (error: ProtocolFailure) {
            return classifyAdbFailure(1, error.message.orEmpty())
                ?: AdbResult.Failure(AdbFailureKind.NonZeroExit, error.message.orEmpty())
        } catch (error: IOException) {
            return AdbResult.Failure(AdbFailureKind.ConnectionRefused, "ADB 通信中断：${error.message}")
        } finally { sockets.remove(socket); socket.close() }
    }

    override fun close() { closed.set(true); sockets.forEach { runCatching { it.close() } } }

    private class ProtocolFailure(message: String) : IOException(message)
    private class Wire(private val socket: Socket, private val deadline: Long) {
        private val input get() = socket.getInputStream()
        private val output get() = socket.getOutputStream()
        private fun remaining() {
            val millis = (deadline - System.nanoTime()) / 1_000_000
            if (millis <= 0) throw SocketTimeoutException()
            socket.soTimeout = millis.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        }
        private fun bytes(count: Int): ByteArray {
            if (count !in 0..MAX_PACKET) throw IOException("ADB 数据帧长度无效")
            val data = ByteArray(count)
            var position = 0
            while (position < count) {
                remaining()
                val read = input.read(data, position, count - position)
                if (read < 0) throw EOFException("ADB 数据帧不完整")
                position += read
            }
            return data
        }
        fun message(): String {
            val length = bytes(4).toString(Charsets.US_ASCII).toIntOrNull(16) ?: throw IOException("ADB 响应长度无效")
            return bytes(length).toString(Charsets.UTF_8)
        }
        fun request(service: String) {
            val data = service.toByteArray(Charsets.UTF_8)
            if (data.size > 65535) throw IOException("ADB 请求过长")
            output.write("%04x".format(data.size).toByteArray(Charsets.US_ASCII)); output.write(data); output.flush()
            when (bytes(4).toString(Charsets.US_ASCII)) {
                "OKAY" -> Unit
                "FAIL" -> {
                    val detail = message()
                    if (service.startsWith("shell,v2,") && Regex("unknown|not support|unsupported", RegexOption.IGNORE_CASE).containsMatchIn(detail)) {
                        throw ProtocolFailure("ADB shell-v2 通道不可用。请确认模拟器为 Android 8 及以上，并更新模拟器 ADB 后重试（$detail）。")
                    }
                    throw ProtocolFailure(detail)
                }
                else -> throw IOException("ADB 响应状态无效")
            }
        }
        private fun ByteArrayOutputStream.append(data: ByteArray) {
            if (size().toLong() + data.size > MAX_OUTPUT) throw IOException("ADB 输出超过上限")
            write(data)
        }
        fun shellResult(): AdbResult {
            // Our commands never consume stdin. Send shell-v2 CLOSE_STDIN, not a socket half-close.
            output.write(byteArrayOf(4, 0, 0, 0, 0)); output.flush()
            val stdout = ByteArrayOutputStream(); val stderr = ByteArrayOutputStream()
            while (true) {
                val header = bytes(5)
                val length = ByteBuffer.wrap(header, 1, 4).order(ByteOrder.LITTLE_ENDIAN).int
                val payload = bytes(length)
                when (header[0].toInt()) {
                    1 -> stdout.append(payload)
                    2 -> stderr.append(payload)
                    3 -> {
                        if (payload.size != 1) throw IOException("ADB 退出状态无效")
                        val exit = payload[0].toInt() and 255
                        val errors = stderr.toString(Charsets.UTF_8).replace("\r\n", "\n")
                        return classifyAdbFailure(exit, errors) ?: AdbResult.Success(exit, stdout.toByteArray(), errors)
                    }
                    else -> throw IOException("ADB shell 数据帧类型无效")
                }
            }
        }
    }

    companion object {
        private const val MAX_PACKET = 16 * 1024 * 1024
        private const val MAX_OUTPUT = 128 * 1024 * 1024
        private fun defaultAddress(): InetSocketAddress {
            val socket = System.getenv("ADB_SERVER_SOCKET")?.takeIf { it.isNotBlank() }
            if (socket != null) {
                require(socket.startsWith("tcp:")) { "仅支持 TCP ADB 服务地址" }
                val endpoint = socket.removePrefix("tcp:")
                val host = if (':' in endpoint) endpoint.substringBeforeLast(':').removeSurrounding("[", "]") else "127.0.0.1"
                return InetSocketAddress(host, endpoint.substringAfterLast(':').toInt())
            }
            return InetSocketAddress(System.getenv("ANDROID_ADB_SERVER_ADDRESS") ?: "127.0.0.1",
                System.getenv("ANDROID_ADB_SERVER_PORT")?.toIntOrNull() ?: 5037)
        }
    }
}
