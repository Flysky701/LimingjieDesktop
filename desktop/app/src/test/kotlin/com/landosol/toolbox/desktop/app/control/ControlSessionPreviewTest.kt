package com.landosol.toolbox.desktop.app.control

import com.landosol.toolbox.desktop.shared.CapturePixels
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.graphics.toArgb
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.Base64
import javax.imageio.ImageIO
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ControlSessionPreviewTest {
    @Test fun unchangedPreviewSkipsDecodeButNewPixelsTimestampsAndClearsPassThrough() = runBlocking {
        val port = PreviewPort()
        val results = mutableListOf<CapturePixels?>()
        var decodes = 0
        val collector = launch(Dispatchers.Unconfined) {
            port.previewFrames { image ->
                decodes++
                CapturePixels(1, 1, intArrayOf(image.getValue("png").jsonPrimitive.int),
                    image.getValue("timestampMillis").jsonPrimitive.long)
            }.collect { results += it }
        }
        try {
            port.publish(preview("10", 1))
            val first = results.last()
            repeat(3) { port.publish(preview("10", 1), revision = it + 1) }
            assertEquals(1, decodes)
            assertSame(first, results.last())
            port.publish(preview("10", 2))
            assertEquals(2, decodes)
            assertEquals(2L, results.last()!!.timestampMillis)
            // A timestamp alone is not a safe key: a different payload must still be decoded.
            port.publish(preview("20", 2))
            assertEquals(3, decodes)
            assertEquals(20, results.last()!!.pixels.single())
            port.publish(null)
            assertNull(results.last())
            port.publish(preview("20", 2))
            assertEquals(4, decodes)
            assertEquals(20, results.last()!!.pixels.single())
        } finally { collector.cancelAndJoin() }
    }

    @Test fun clearDisconnectAndShutdownCancelPendingFrames() = runBlocking {
        for (boundary in listOf("clear", "disconnect", "shutdown")) {
            val port = PreviewPort()
            val started = CompletableDeferred<Unit>()
            val cancelled = CompletableDeferred<Unit>()
            val results = mutableListOf<CapturePixels?>()
            val collector = launch(Dispatchers.Unconfined) {
                port.previewFrames<CapturePixels> {
                    started.complete(Unit)
                    try { awaitCancellation() }
                    finally { cancelled.complete(Unit) }
                }.collect { results += it }
            }
            try {
                port.publish(preview("pending", 1))
                withTimeout(5_000) { started.await() }
                when (boundary) {
                    "clear" -> port.publish(null)
                    "disconnect" -> port.connection.value = WorkerConnectionStatus.LOST
                    else -> port.connection.value = WorkerConnectionStatus.CLOSED
                }
                withTimeout(5_000) { cancelled.await() }
                assertTrue(boundary, results.all { it == null })
            } finally { collector.cancelAndJoin() }
        }
    }

    @Test fun aNewFrameReplacesAnUnfinishedDecode() = runBlocking {
        val port = PreviewPort()
        val started = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val results = mutableListOf<CapturePixels?>()
        val collector = launch(Dispatchers.Unconfined) {
            port.previewFrames { image ->
                val timestamp = image.getValue("timestampMillis").jsonPrimitive.long
                if (timestamp == 1L) {
                    started.complete(Unit)
                    try { awaitCancellation() }
                    finally { cancelled.complete(Unit) }
                }
                CapturePixels(1, 1, intArrayOf(20), timestamp)
            }.collect { results += it }
        }
        try {
            port.publish(preview("old", 1))
            withTimeout(5_000) { started.await() }
            port.publish(preview("new", 2))
            withTimeout(5_000) { cancelled.await() }
            assertEquals(listOf(2L), results.filterNotNull().map { it.timestampMillis })
        } finally { collector.cancelAndJoin() }
    }

    @Test fun uiReconnectStartsEmptyAndKeepsEachPortsPixelsSeparate() = runBlocking<Unit> {
        val oldPort = PreviewPort("old-run")
        val newPort = PreviewPort("new-run")
        val oldUi = withContext(Dispatchers.Main) { ControlSessionUi(oldPort) }
        var newUi: ControlSessionUi? = null
        try {
            oldPort.publish(preview(png(0xff112233.toInt()), 1))
            withTimeout(5_000) { oldUi.preview.first { it != null } }
            val replacement = withContext(Dispatchers.Main) { ControlSessionUi(newPort, previous = oldUi) }
            newUi = replacement
            assertNull(replacement.preview.value)
            newPort.publish(preview(png(0xff445566.toInt()), 1))
            val frame = withTimeout(5_000) { replacement.preview.first { it != null } }!!
            assertEquals(0xff445566.toInt(), frame.toPixelMap()[0, 0].toArgb())
            assertEquals(0xff112233.toInt(), oldUi.preview.value!!.toPixelMap()[0, 0].toArgb())
            oldPort.connection.value = WorkerConnectionStatus.CLOSED
            withTimeout(5_000) { oldUi.preview.first { it == null } }
            assertSame(frame, replacement.preview.value)
            newPort.publish(null)
            withTimeout(5_000) { replacement.preview.first { it == null } }
        } finally {
            withContext(Dispatchers.Main) { oldUi.close(); newUi?.close() }
        }
    }

    private fun preview(png: String, timestamp: Long) = buildJsonObject {
        put("png", png); put("timestampMillis", timestamp)
    }

    private fun png(pixel: Int): String {
        val image = BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB)
        image.setRGB(0, 0, pixel)
        return ByteArrayOutputStream().use { output ->
            check(ImageIO.write(image, "png", output))
            Base64.getEncoder().encodeToString(output.toByteArray())
        }
    }

    private class PreviewPort(override val workerRunId: String = "preview-run") : ControlSessionPort {
        override val instanceId = "same-device"
        override val snapshots = MutableStateFlow(JsonObject(emptyMap()))
        override val connection = MutableStateFlow(WorkerConnectionStatus.CONNECTED)
        override val error = MutableStateFlow<String?>(null)
        fun publish(image: JsonObject?, revision: Int = 0) {
            snapshots.value = buildJsonObject {
                put("preview", image ?: JsonNull); put("revision", revision)
            }
        }
        override suspend fun request(op: String, args: JsonObject): JsonObject = error("No device commands in preview tests")
        override fun setSelected(selected: Boolean) = Unit
        override suspend fun shutdown() = Unit
    }
}
