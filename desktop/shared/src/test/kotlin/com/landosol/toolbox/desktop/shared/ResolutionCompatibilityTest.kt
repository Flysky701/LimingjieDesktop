package com.landosol.toolbox.desktop.shared

import com.landosol.toolbox.clanbattle.recognition.PixelImage
import com.landosol.toolbox.labyrinth.LabyrinthFallbackTap
import com.landosol.toolbox.labyrinth.vision.*
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import org.junit.Assert.*
import org.junit.Test

class ResolutionCompatibilityTest {
    @Test fun `existing result button template crop and fallback stay aligned after uniform scaling and centered bars`() {
        // Existing captured frame, resampled locally; this does not simulate a device's DPI or renderer.
        val source = ImageIO.read(File("src/test/resources/labyrinth/run-result-next-button-20260920.jpg"))
        assertEquals(1920, source.width)
        assertEquals(1080, source.height)
        val definition = LabyrinthEntryFrameProcessor.DEFINITIONS_BY_ID
            .getValue(EntryAnchorId.RUN_RESULT_CLOSE_BUTTON).single()
        val template = pixels(ImageIO.read(File(
            "src/main/assets/resource-packs/cn-bilibili/vision/run_result/run_result_close_button.png",
        )))
        val matcher = GradientTemplateMatcher()
        for ((width, height) in listOf(
            1920 to 1080, 1280 to 720, 2560 to 1440,
            2560 to 1080, 1440 to 1080, 1280 to 800,
        )) {
            val canvas = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
            val scale = minOf(width / 1920.0, height / 1080.0)
            val contentWidth = (1920 * scale).toInt()
            val contentHeight = (1080 * scale).toInt()
            val left = (width - contentWidth) / 2
            val top = (height - contentHeight) / 2
            canvas.createGraphics().let { g ->
                try {
                    g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
                    g.drawImage(source, left, top, contentWidth, contentHeight, null)
                } finally { g.dispose() }
            }
            val rect = requireNotNull(ReferenceFitMapper.map(
                width, height, definition.referenceSize, definition.referenceRect,
            ))
            val frame = pixels(canvas)
            val score = matcher.score(frame, rect, template)
            assertTrue("${width}x$height button score=$score", score >= 0.45)
            val tap = LabyrinthFallbackTap.BOTTOM_CENTER.rect(width, height)
            val x = tap.left + tap.width / 2
            val y = tap.top + tap.height / 2
            assertTrue(x in rect.left until rect.left + rect.width)
            assertTrue(y in rect.top until rect.top + rect.height)
            // Also check against the button interior observed in the original screenshot,
            // independently of the template's declared rectangle.
            assertTrue((x - left) / scale in 780.0..1140.0)
            assertTrue((y - top) / scale in 970.0..1020.0)
            val crop = pixels(canvas.getSubimage(rect.left, rect.top, rect.width, rect.height))
            assertEquals(score, matcher.score(crop, EntryPixelRect(0, 0, crop.width, crop.height), template), 0.000001)
            println("result-button ${width}x$height score=$score tap=($x,$y) crop=$rect")
        }
    }

    private fun pixels(image: BufferedImage) = PixelImage(
        image.width, image.height,
        image.getRGB(0, 0, image.width, image.height, null, 0, image.width),
    )
}
