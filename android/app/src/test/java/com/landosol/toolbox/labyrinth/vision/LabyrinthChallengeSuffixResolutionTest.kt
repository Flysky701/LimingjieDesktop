package com.landosol.toolbox.labyrinth.vision

import com.landosol.toolbox.clanbattle.recognition.PixelImage
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LabyrinthChallengeSuffixResolutionTest {
    private val normalId = EntryAnchorId.BATTLE_CHALLENGE_SUFFIX_NORMAL
    private val extremeId = EntryAnchorId.BATTLE_CHALLENGE_SUFFIX_EXTREME
    private val templates = mapOf(normalId to template("normal"), extremeId to template("extreme"))
    private val processor = LabyrinthEntryFrameProcessor(LabyrinthEntryTemplateSet(
        EntryAnchorId.required.associateWith { PixelImage(1, 1, intArrayOf(-1)) } + templates,
    ))

    @Test
    fun `real 720p normal title passes unchanged difficulty gates after resampling`() {
        // Exact 90x40 crop at (203,57) of frame/latest.jpg in the 2026-10-26 00:44:11
        // user bundle. Only the generic difficulty label is retained; JPEG pixels are unchanged.
        val crop = ImageIO.read(javaClass.getResource("/labyrinth/challenge-normal-suffix-720p-20261026.png"))
        val frame = canvas(1280, 720, crop, 203, 57)
        val legacy = GradientTemplateMatcher()
        val rect = EntryPixelRect(203, 57, 90, 40)
        val normal = legacy.score(frame, rect, templates.getValue(normalId))
        val extreme = legacy.score(frame, rect, templates.getValue(extremeId))
        assertTrue("legacy normal=$normal", normal in 0.75..0.78)
        assertEquals(LabyrinthChallengeDifficulty.UNKNOWN, LabyrinthChallengeDifficultyResolver.resolve(normal, extreme))
        assertDifficulty(frame, LabyrinthChallengeDifficulty.NORMAL)
    }

    @Test
    fun `normal and extreme remain distinct at 720p 900p and native 1080p`() {
        for ((id, template) in templates) {
            val reference = BufferedImage(1920, 1080, BufferedImage.TYPE_INT_RGB)
            reference.setRGB(305, 85, template.width, template.height, template.pixels, 0, template.width)
            for (width in listOf(1280, 1600, 1920)) {
                val resized = BufferedImage(width, width * 9 / 16, BufferedImage.TYPE_INT_RGB)
                resized.createGraphics().apply {
                    setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
                    drawImage(reference, 0, 0, resized.width, resized.height, null)
                    dispose()
                }
                assertDifficulty(pixels(resized), if (id == normalId) LabyrinthChallengeDifficulty.NORMAL else LabyrinthChallengeDifficulty.EXTREME)
            }
        }
    }

    @Test
    fun `blank and ambiguous titles remain unresolved`() {
        assertDifficulty(PixelImage(1280, 720, IntArray(1280 * 720) { -1 }), LabyrinthChallengeDifficulty.UNKNOWN)
        val normal = templates.getValue(normalId)
        val extreme = templates.getValue(extremeId)
        val mixed = BufferedImage(normal.width, normal.height, BufferedImage.TYPE_INT_RGB)
        val colors = IntArray(normal.pixels.size) { i ->
            var color = 0xff000000.toInt()
            for (shift in listOf(0, 8, 16)) {
                val value = ((normal.pixels[i] ushr shift and 255) + (extreme.pixels[i] ushr shift and 255)) / 2
                color = color or (value shl shift)
            }
            color
        }
        mixed.setRGB(0, 0, normal.width, normal.height, colors, 0, normal.width)
        val small = BufferedImage(90, 40, BufferedImage.TYPE_INT_RGB)
        small.createGraphics().apply {
            setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            drawImage(mixed, 0, 0, 90, 40, null)
            dispose()
        }
        assertDifficulty(canvas(1280, 720, small, 203, 57), LabyrinthChallengeDifficulty.UNKNOWN)
    }

    private fun assertDifficulty(frame: PixelImage, expected: LabyrinthChallengeDifficulty) {
        val scores = processor.process(frame).observation.anchorScores
        val normal = scores[normalId]
        val extreme = scores[extremeId]
        println("${frame.width}x${frame.height}: expected=$expected normal=$normal extreme=$extreme")
        assertEquals("${frame.width}x${frame.height}: normal=$normal extreme=$extreme", expected,
            LabyrinthChallengeDifficultyResolver.resolve(normal, extreme))
    }

    private fun template(name: String): PixelImage = pixels(ImageIO.read(File(
        "src/main/assets/resource-packs/cn-bilibili/vision/battle_challenge/battle_challenge_${name}_suffix.png",
    )))

    private fun canvas(width: Int, height: Int, crop: BufferedImage, left: Int, top: Int): PixelImage {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        image.createGraphics().apply { drawImage(crop, left, top, null); dispose() }
        return pixels(image)
    }

    private fun pixels(image: BufferedImage): PixelImage = PixelImage(
        image.width, image.height, image.getRGB(0, 0, image.width, image.height, null, 0, image.width),
    )
}
