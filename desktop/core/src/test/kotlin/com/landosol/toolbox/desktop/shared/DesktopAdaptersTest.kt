package com.landosol.toolbox.desktop.shared

import com.landosol.toolbox.core.AppContext
import com.landosol.toolbox.core.logging.AppLogger
import com.landosol.toolbox.vision.NativeBitmap
import com.landosol.toolbox.automation.capture.*
import com.landosol.toolbox.labyrinth.debug.LabyrinthDiagnosticsRecorder
import com.landosol.toolbox.labyrinth.debug.LabyrinthRuntimeDiagnostics
import com.landosol.toolbox.labyrinth.vision.DesktopChineseRoleNameOcr
import com.landosol.toolbox.protocol.bilibili.CaptchaChallenge
import java.nio.file.Files
import java.util.zip.ZipInputStream
import javax.swing.SwingUtilities
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class DesktopAdaptersTest {
    @Test fun hiddenPreviewSkipsItsCopyWhileRecognitionKeepsReceivingFrames() = runBlocking {
        val enabled = java.util.concurrent.atomic.AtomicBoolean(false)
        val frames = java.util.concurrent.atomic.AtomicInteger()
        val previews = java.util.concurrent.atomic.AtomicInteger()
        val modifiedFrames = java.util.concurrent.atomic.AtomicInteger()
        val sourceColor = 0xff123456.toInt()
        val registered = CaptureFrameBus.register("preview-demand-fixture") { frame ->
            if (frame.bitmap.getPixel(0, 0) != sourceColor) modifiedFrames.incrementAndGet()
            frames.incrementAndGet(); frame.bitmap.recycle()
        } as CaptureFrameRegistrationResult.Registered
        val service = DesktopCaptureService(
            source = {
                val sourcePixels = IntArray(16 * 9) { sourceColor }
                CapturePixels(16, 9, sourcePixels, System.currentTimeMillis(), ownsPixels = true)
            },
            preview = {
                it.pixels[0] = 0
                previews.incrementAndGet()
            },
            previewEnabled = enabled::get,
        )
        try {
            service.start()
            withTimeout(3_000) { while (frames.get() < 3) delay(10) }
            assertEquals(0, previews.get())
            enabled.set(true)
            withTimeout(2_000) { while (previews.get() == 0) delay(10) }
            enabled.set(false)
            // Allow a callback already in flight to finish, then observe new recognition frames.
            val before = frames.get()
            withTimeout(2_000) { while (frames.get() <= before) delay(10) }
            val retained = previews.get()
            val next = frames.get() + 3
            withTimeout(2_000) { while (frames.get() < next) delay(10) }
            assertEquals(retained, previews.get())
            assertEquals("Preview writes reached recognition", 0, modifiedFrames.get())
        } finally { service.stopAndJoin("preview test complete"); service.close(); CaptureFrameBus.unregister(registered.lease) }
    }

    @Test fun recognitionRetainsBorrowedPixelsWhenSourceReusesItsBuffer() {
        val source = intArrayOf(0xff123456.toInt())
        val bitmap = CapturePixels(1, 1, source, 0L).recognitionBitmap()
        val owned = CapturePixels(1, 1, intArrayOf(0), 0L, ownsPixels = true)
        val copied = owned.copy(pixels = source).recognitionBitmap()
        source[0] = 0
        assertEquals(0xff123456.toInt(), bitmap.getPixel(0, 0))
        assertEquals(0xff123456.toInt(), copied.getPixel(0, 0))
        bitmap.recycle()
        copied.recycle()
    }

    @Test fun captureUsesOwnedThreadAndStopsAfterFiveFailures() = runBlocking {
        val received = CompletableDeferred<Pair<String, CapturedFrame>>()
        val lost = CompletableDeferred<String>()
        val before = CaptureTelemetry.snapshot()
        var calls = 0
        var failing = false
        val registered = CaptureFrameBus.register("desktop-fixture") { frame ->
            received.complete(Thread.currentThread().name to frame)
            frame.bitmap.recycle()
        } as CaptureFrameRegistrationResult.Registered
        val service = DesktopCaptureService(source = {
            calls++
            if (failing) throw java.io.IOException("fixture disconnect")
            CapturePixels(16, 9, IntArray(144) { 0xff123456.toInt() }, System.currentTimeMillis())
        }, onLost = { lost.complete(it) })
        try {
            withTimeout(4000) { service.start() }
            val (thread, frame) = withTimeout(1000) { received.await() }
            assertEquals("desktop-capture", thread.substringBefore(" @coroutine"))
            assertTrue(frame.frameSequence > 0)
            assertTrue(requireNotNull(frame.captureConvertMillis) >= 0)
            assertTrue(requireNotNull(frame.enqueuedAtElapsedRealtimeMillis) >= requireNotNull(frame.capturedAtElapsedRealtimeMillis))
            val after = CaptureTelemetry.snapshot()
            assertTrue(after.callbacks > before.callbacks)
            assertTrue(after.acquiredImages > before.acquiredImages)
            assertTrue(after.convertedFrames > before.convertedFrames)
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
            val image = NativeBitmap.create(intArrayOf(0xff123456.toInt(), 0xffabcdef.toInt()), 2, 1)
            DesktopChineseRoleNameOcr.readAsync(image) { result.complete(it to SwingUtilities.isEventDispatchThread()) }
            image.recycle()
            withContext(Dispatchers.Default) { submitted.await().invoke() }
            assertEquals("名称" to true, withTimeout(3000) { result.await() })
        } finally { DesktopBindings.release(services) }
    }

    @Test fun diagnosticsExportsUnifiedLogsAndRuntimeDetails() {
        val directory = Files.createTempDirectory("diagnostics 中文")
        val diagnostics = LabyrinthRuntimeDiagnostics(AppContext(directory.toFile()))
        val traceId = "adapters-diagnostics-fixture"
        diagnostics.environmentDetails = { JSONObject().put("message", "password=environment-fixture") }
        val recorder = LabyrinthDiagnosticsRecorder(runtimeDiagnostics = diagnostics,
            userLogSource = { "console token=console-fixture".encodeToByteArray() })
        try {
            AppLogger.bindTrace(traceId)
            AppLogger.i("Fixture", "password=fixture-password access_key=\"fixture-key\" loginId=fixture-login token=fixture-token")
            val entries = mutableMapOf<String, String>()
            ZipInputStream(recorder.exportDiagnosticZip().inputStream()).use { zip ->
                while (true) { val entry = zip.nextEntry ?: break; entries[entry.name] = zip.readBytes().toString(Charsets.UTF_8) }
            }
            assertEquals(setOf(
                "summary.txt", "manifest.json", "latest-snapshot.json", "state-history.jsonl",
                "fast/history.ndjson", "environment.json", "runtime/history.ndjson", "process.log", "user-console.log",
            ), entries.keys)
            val allText = entries.values.joinToString("\n")
            listOf("fixture-password", "fixture-key", "fixture-login", "fixture-token", "console-fixture", "environment-fixture")
                .forEach { assertFalse("Leaked $it", allText.contains(it)) }
            assertEquals("traceId=$traceId\n", entries.getValue("summary.txt"))
            assertEquals(traceId, JSONObject(entries.getValue("manifest.json")).getString("traceId"))
            assertEquals("console token=<redacted>", entries.getValue("user-console.log"))
            val process = entries.getValue("process.log")
            assertTrue(process.contains("[trace=$traceId] Fixture: password=<redacted>"))
            val environment = JSONObject(entries.getValue("environment.json"))
            assertEquals("desktop", environment.getString("platform"))
            assertEquals("password=<redacted>", environment.getJSONObject("desktop").getString("message"))
        } finally {
            AppLogger.clearTrace(traceId)
            recorder.close()
            diagnostics.close()
        }
    }
}
