package com.landosol.toolbox.desktop.shared

import android.graphics.Bitmap
import com.landosol.toolbox.automation.capture.*
import com.landosol.toolbox.labyrinth.debug.LabyrinthDebugDashboardServer
import com.landosol.toolbox.labyrinth.debug.LabyrinthRuntimeDiagnostics
import com.landosol.toolbox.labyrinth.vision.AndroidChineseRoleNameOcr
import com.landosol.toolbox.protocol.bilibili.CaptchaChallenge
import java.net.ServerSocket
import java.net.URI
import java.nio.file.Files
import java.util.zip.ZipInputStream
import javax.swing.SwingUtilities
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class DesktopAdaptersTest {
    @Test fun captureUsesOwnedThreadAndStopsAfterFiveFailures() = runBlocking {
        val received = CompletableDeferred<String>()
        val lost = CompletableDeferred<String>()
        var calls = 0
        var failing = false
        val registered = CaptureFrameBus.register("desktop-fixture") { frame ->
            received.complete(Thread.currentThread().name)
            frame.bitmap.recycle()
        } as CaptureFrameRegistrationResult.Registered
        val service = DesktopCaptureService(source = {
            calls++
            if (failing) throw java.io.IOException("fixture disconnect")
            CapturePixels(16, 9, IntArray(144) { 0xff123456.toInt() }, System.currentTimeMillis())
        }, onLost = { lost.complete(it) })
        try {
            withTimeout(4000) { service.start() }
            assertEquals("desktop-capture", withTimeout(1000) { received.await() }.substringBefore(" @coroutine"))
            assertTrue(CaptureStateRegistry.isActive())
            failing = true
            assertTrue(withTimeout(4000) { lost.await() }.contains("连续 5 次截图失败"))
            assertFalse(CaptureStateRegistry.isActive())
            val stoppedAt = calls
            delay(400)
            assertEquals(stoppedAt, calls)
        } finally { service.stopAndJoin("fixture complete"); service.close(); CaptureFrameBus.unregister(registered.lease) }
    }

    @Test fun ocrBridgeCopiesPixelsAndReturnsOnSwingMain() = runBlocking {
        val submitted = CompletableDeferred<() -> Unit>()
        val result = CompletableDeferred<Pair<String?, Boolean>>()
        val services = DesktopServices(
            OcrPort { width, height, pixels, callback ->
                assertEquals(2, width); assertEquals(1, height)
                submitted.complete { assertEquals(0xff123456.toInt(), pixels[0]); callback(" 名称 ") }
            },
            object : SecretProtector { override fun protect(plaintext: ByteArray, entropy: ByteArray) = error("unused"); override fun unprotect(blob: ByteArray, entropy: ByteArray) = error("unused") },
            object : DeviceIdentityStore { override fun current(): DeviceIdentitySnapshot? = null; override fun save(snapshot: DeviceIdentitySnapshot) = error("unused") },
            object : CaptchaHost {
                override fun open(challenge: CaptchaChallenge, onSolved: (String) -> Unit, onError: (String) -> Unit): CaptchaSession = error("unused")
                override fun browse(url: String) = false
                override fun close() = Unit
            }, { byteArrayOf() },
        )
        DesktopBindings.install(services)
        try {
            val image = Bitmap.createBitmap(intArrayOf(0xff123456.toInt(), 0xffabcdef.toInt()), 2, 1, Bitmap.Config.ARGB_8888)
            AndroidChineseRoleNameOcr.readAsync(image) { result.complete(it to SwingUtilities.isEventDispatchThread()) }
            image.recycle()
            withContext(Dispatchers.Default) { submitted.await().invoke() }
            assertEquals("名称" to true, withTimeout(3000) { result.await() })
        } finally { DesktopBindings.release(services) }
    }

    @Test fun diagnosticsUsesDesktopLogAndReleasesItsSocket() {
        val directory = Files.createTempDirectory("diagnostics 中文")
        DesktopLog.initialize(directory.resolve("logs"))
        DesktopLog.write('I', "Fixture", "password=fixture-password access_key=\"fixture-key\" loginId=fixture-login token=fixture-token")
        val socket = ServerSocket()
        val diagnostics = LabyrinthRuntimeDiagnostics(DesktopContext(directory.toFile()))
        val server = LabyrinthDebugDashboardServer(port = 0, runtimeDiagnostics = diagnostics,
            processLogSource = DesktopLog::snapshot, serverSocketFactory = { socket }, allowAddressReuse = false)
        try {
            server.start()
            val connection = URI("http://127.0.0.1:${socket.localPort}/logs.zip").toURL().openConnection().apply { connectTimeout = 3000; readTimeout = 3000 }
            val entries = mutableMapOf<String, String>()
            ZipInputStream(connection.getInputStream()).use { zip ->
                while (true) { val entry = zip.nextEntry ?: break; entries[entry.name] = zip.readBytes().toString(Charsets.UTF_8) }
            }
            assertEquals(setOf("README.txt", "state/latest.json", "environment.json", "runtime/history.ndjson", "state/history.ndjson", "logs/logcat.txt"), entries.keys)
            val allText = entries.values.joinToString("\n")
            listOf("fixture-password", "fixture-key", "fixture-login", "fixture-token").forEach { assertFalse("Leaked $it", allText.contains(it)) }
            assertTrue(entries.getValue("logs/logcat.txt").contains("<redacted>"))
            val port = socket.localPort
            socket.close()
            ServerSocket().use { next -> next.reuseAddress = false; next.bind(java.net.InetSocketAddress("127.0.0.1", port)) }
        } finally { socket.close(); diagnostics.close() }
    }
}
