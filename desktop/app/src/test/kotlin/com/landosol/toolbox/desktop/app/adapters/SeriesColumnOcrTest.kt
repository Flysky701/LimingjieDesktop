package com.landosol.toolbox.desktop.app.adapters

import android.graphics.Bitmap
import com.landosol.toolbox.clanbattle.recognition.PixelImage
import com.landosol.toolbox.desktop.ocr.OnnxOcrEngine
import com.landosol.toolbox.desktop.shared.*
import com.landosol.toolbox.labyrinth.LabyrinthRelicMark.*
import com.landosol.toolbox.labyrinth.vision.*
import com.landosol.toolbox.protocol.bilibili.CaptchaChallenge
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SeriesColumnOcrTest {
    private val fixture = File("../../android/app/src/test/resources/labyrinth/series-effect-panel-20261012.jpg")

    @Test fun confirmedPanelReadsOnlyCompleteUnambiguousRowsThroughDesktopBindings() = runBlocking {
        val engine = OnnxOcrEngine()
        val services = DesktopServices(
            OcrPort(engine::submit),
            object : SecretProtector {
                override fun protect(plaintext: ByteArray, entropy: ByteArray) = error("unused")
                override fun unprotect(blob: ByteArray, entropy: ByteArray) = error("unused")
            },
            object : DeviceIdentityStore {
                override fun current(): DeviceIdentitySnapshot? = null
                override fun save(snapshot: DeviceIdentitySnapshot) = error("unused")
            },
            object : CaptchaHost {
                override fun open(challenge: CaptchaChallenge, onSolved: (String) -> Unit, onError: (String) -> Unit): CaptchaSession = error("unused")
                override fun browse(url: String) = false
                override fun close() = Unit
            },
            { byteArrayOf() },
        )
        DesktopBindings.install(services)
        try {
            val image = ImageIO.read(fixture)
            assertEquals(mapOf(DEFENSE to 2, DEBUFF to 1, ACCELERATION to 1), read(image).entries)

            // Replace the second heading with the first: two different visible counts now claim the same series.
            val heading = image.getRGB(532, 150, 120, 59, null, 0, 120)
            image.setRGB(532, 351, 120, 59, heading, 0, 120)
            assertEquals(mapOf(ACCELERATION to 1), read(image).entries)

            // Keep the actual header and icon, but erase the last badge. Right-edge description fragments remain.
            val paint = image.createGraphics()
            try {
                paint.color = Color.WHITE
                paint.fillRect(600, 659, 29, 44)
            } finally { paint.dispose() }
            assertEquals(emptyMap<Any, Any>(), read(image).entries)
        } finally {
            DesktopBindings.release(services)
            engine.close()
        }
    }

    @Test fun anUnavailableReaderDropsTheWholeColumnIncludingEarlierRows() = runBlocking {
        val source = ImageIO.read(fixture)
        val width = 240
        val height = 1426
        val pixels = IntArray(width * height) { i -> source.getRGB(532 + (i % width) / 2, 150 + (i / width) / 2) }
        val column = PixelImage(width, height, pixels)
        val firstRowThenRejected = listOf("守备", "2", "2", null).iterator()
        assertNull(readSeriesColumn(column, OcrPort { _, _, _, done -> done(firstRowThenRejected.next()) }))
        assertNull(readSeriesColumn(column, OcrPort { _, _, _, done -> done(null) }))
    }

    private suspend fun read(image: BufferedImage): LabyrinthRelicDetailObservation {
        val bitmap = Bitmap.createBitmap(image.getRGB(0, 0, image.width, image.height, null, 0, image.width),
            image.width, image.height, Bitmap.Config.ARGB_8888)
        val resolver = AndroidLabyrinthRelicDetailResolver()
        val frame = LabyrinthEntryFrameResult(
            LabyrinthEntryPageObservation(LabyrinthEntryPageState.UNKNOWN, 0.0, emptyMap(), LabyrinthAnchorScores(emptyMap())),
            emptyList(), 0,
        )
        try {
            return withTimeout(10_000) {
                var detail: LabyrinthRelicDetailObservation?
                do {
                    detail = resolver.resolve(bitmap, frame).relicDetailObservation
                    if (detail?.evidenceId == null) delay(25)
                } while (detail?.evidenceId == null)
                checkNotNull(detail)
            }
        } finally { bitmap.recycle() }
    }
}