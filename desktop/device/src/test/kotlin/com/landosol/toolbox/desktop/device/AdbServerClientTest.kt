package com.landosol.toolbox.desktop.device

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicInteger

class AdbServerClientTest {
    private fun client(server: ServerSocket) = AdbServerClient(InetSocketAddress("127.0.0.1", server.localPort))
    private fun Socket.request(): String {
        soTimeout = 3000
        val length = getInputStream().readNBytes(4).toString(Charsets.US_ASCII).toInt(16)
        return getInputStream().readNBytes(length).toString(Charsets.UTF_8)
    }
    private fun Socket.send(value: String) { getOutputStream().write(value.toByteArray()); getOutputStream().flush() }
    private fun Socket.message(value: String) { val data = value.toByteArray(); send("OKAY%04x".format(data.size)); getOutputStream().write(data) }
    private fun Socket.packet(id: Int, value: ByteArray) {
        val data = ByteBuffer.allocate(5 + value.size).order(ByteOrder.LITTLE_ENDIAN).put(id.toByte()).putInt(value.size).put(value).array()
        // Deliberately fragment headers and UTF-8/binary payloads across TCP writes.
        data.forEach { getOutputStream().write(it.toInt()) }; getOutputStream().flush()
    }

    @Test fun shellV2PreservesBinaryStderrExitAndUsesNoChildProcess() = runBlocking {
        ServerSocket(0).use { server ->
            val output = byteArrayOf(0, 13, 10, -1, 42)
            val peer = async(Dispatchers.IO) {
                repeat(2) { attempt -> server.accept().use { socket ->
                    assertEquals("host:transport:fixture", socket.request()); socket.send("OKAY")
                    assertEquals("shell,v2,raw:'echo' '中文 空格' 'a'\\''b'", socket.request()); socket.send("OKAY")
                    assertArrayEquals(byteArrayOf(4, 0, 0, 0, 0), socket.getInputStream().readNBytes(5))
                    socket.packet(1, output)
                    socket.packet(2, "diagnostic\r\n".toByteArray())
                    socket.packet(3, byteArrayOf(if (attempt == 0) 0 else 7))
                } }
            }
            ProcessAdbExecutor("must-not-launch", AdbProcessFactory { error("unexpected process") }, client(server)).use { adb ->
                val success = adb.shell("fixture", listOf("echo", "中文 空格", "a'b")) as AdbResult.Success
                assertArrayEquals(output, success.stdout); assertEquals("diagnostic\n", success.stderr)
                assertEquals(AdbFailureKind.NonZeroExit, (adb.shell("fixture", listOf("echo", "中文 空格", "a'b")) as AdbResult.Failure).kind)
            }
            peer.await()
        }
    }

    @Test fun screenshotRawAndPngRetainAllBytesWithoutProcesses() = runBlocking {
        ServerSocket(0).use { server ->
            val peer = async(Dispatchers.IO) {
                for ((request, bytes) in listOf("shell,v2,raw:'screencap'" to FakeAdb.raw(), "shell,v2,raw:'screencap' '-p'" to FakeAdb.png())) {
                    server.accept().use { socket ->
                        assertEquals("host:transport:fixture", socket.request()); socket.send("OKAY")
                        assertEquals(request, socket.request()); socket.send("OKAY")
                        socket.getInputStream().readNBytes(5)
                        socket.packet(1, bytes); socket.packet(3, byteArrayOf(0))
                    }
                }
                server.accept().use { socket ->
                    socket.request(); socket.send("OKAY"); socket.request(); socket.send("OKAY")
                    socket.getInputStream().readNBytes(5)
                    socket.packet(2, "screencap failed".toByteArray()); socket.packet(3, byteArrayOf(1))
                }
            }
            ProcessAdbExecutor("must-not-launch", AdbProcessFactory { error("unexpected process") }, client(server)).use { adb ->
                val capturer = ScreenCapturer(adb, "fixture")
                assertEquals(2, capturer.capture(CaptureMode.Raw).width)
                assertEquals(2, capturer.capture(CaptureMode.Png).width)
                assertEquals(AdbFailureKind.NonZeroExit, (adb.execute(listOf("exec-out", "screencap"), "fixture", screenshot = true) as AdbResult.Failure).kind)
            }
            peer.await()
        }
    }

    @Test fun hostCommandsAndProtocolFailuresAreNotRetriedThroughProcesses() = runBlocking {
        ServerSocket(0).use { server ->
            val commands = listOf("host:devices-l", "host-serial:fixture:get-state", "host:connect:127.0.0.1:5555", "host:transport:fixture")
            val peer = async(Dispatchers.IO) {
                commands.forEachIndexed { index, request -> server.accept().use { socket ->
                    assertEquals(request, socket.request())
                    when(index) {
                        0 -> socket.message("fixture device model:Fixture\n")
                        1 -> socket.message("device")
                        2 -> socket.message("failed to connect to 127.0.0.1:5555")
                        else -> { socket.send("FAIL000e"); socket.send("device offline") }
                    }
                } }
            }
            ProcessAdbExecutor("must-not-launch", AdbProcessFactory { error("unexpected process") }, client(server)).use { adb ->
                assertEquals(1, DeviceParsers.devices((adb.execute(listOf("devices", "-l")) as AdbResult.Success).text()).size)
                assertEquals("device", (adb.execute(listOf("get-state"), "fixture") as AdbResult.Success).text())
                assertEquals(AdbFailureKind.ConnectionRefused, (adb.execute(listOf("connect", "127.0.0.1:5555")) as AdbResult.Failure).kind)
                assertEquals(AdbFailureKind.DeviceOffline, (adb.shell("fixture", listOf("getprop")) as AdbResult.Failure).kind)
            }
            peer.await()
        }
    }

    @Test fun timeoutCancellationAndCloseReleaseBlockedSockets() = runBlocking {
        for (stop in listOf("timeout", "cancel", "close")) {
            ServerSocket(0).use { server ->
                val entered = CompletableDeferred<Unit>()
                val peer = async(Dispatchers.IO) {
                    server.accept().use { socket ->
                        assertEquals("host:devices-l", socket.request()); entered.complete(Unit)
                        assertEquals(-1, socket.getInputStream().read())
                    }
                }
                val adb = ProcessAdbExecutor("must-not-launch", AdbProcessFactory { error("unexpected process") }, client(server))
                try {
                    val waiting = async { adb.execute(listOf("devices", "-l"), timeoutMillis = if (stop == "timeout") 150 else 10000) }
                    entered.await()
                    when(stop) {
                        "timeout" -> assertEquals(AdbFailureKind.Timeout, (waiting.await() as AdbResult.Failure).kind)
                        "cancel" -> withTimeout(1000) { waiting.cancelAndJoin() }
                        "close" -> { adb.close(); withTimeout(1000) { assertTrue(waiting.await() is AdbResult.Failure) } }
                    }
                    withTimeout(1000) { peer.await() }
                } finally { adb.close() }
            }
        }
    }

    @Test fun truncatedShellPacketDoesNotReportSuccessOrRetry() = runBlocking {
        ServerSocket(0).use { server ->
            val calls = AtomicInteger()
            val peer = async(Dispatchers.IO) {
                server.accept().use { socket ->
                    socket.request(); socket.send("OKAY"); socket.request(); socket.send("OKAY")
                    socket.getInputStream().readNBytes(5)
                    socket.getOutputStream().write(byteArrayOf(1, 5, 0, 0, 0, 42))
                }
            }
            ProcessAdbExecutor("unused", AdbProcessFactory { calls.incrementAndGet(); error("no retry") }, client(server)).use { adb ->
                assertTrue(adb.shell("fixture", listOf("echo")) is AdbResult.Failure)
            }
            assertEquals(0, calls.get()); peer.await()
        }
    }

    @Test fun unsupportedShellV2ReportsActionableFailureWithoutProcessFallback() = runBlocking {
        ServerSocket(0).use { server ->
            val peer = async(Dispatchers.IO) {
                server.accept().use { socket ->
                    socket.request(); socket.send("OKAY"); socket.request()
                    val text = "unknown service"
                    socket.send("FAIL%04x".format(text.length) + text)
                }
            }
            ProcessAdbExecutor("must-not-launch", AdbProcessFactory { error("unexpected fallback") }, client(server)).use { adb ->
                val result = adb.shell("fixture", listOf("getprop")) as AdbResult.Failure
                assertTrue(result.detail.contains("shell-v2")); assertTrue(result.detail.contains("更新模拟器 ADB"))
            }
            peer.await()
        }
    }
}
