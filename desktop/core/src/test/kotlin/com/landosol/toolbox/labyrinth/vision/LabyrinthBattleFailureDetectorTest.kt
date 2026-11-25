package com.landosol.toolbox.labyrinth.vision

import com.landosol.toolbox.vision.PixelImage
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LabyrinthBattleFailureDetectorTest {
    @Test
    fun `current lower damage report layout is recognized`() {
        val pixels = IntArray(1920 * 1080) { rgb(45, 50, 80) }
        fill(pixels, EntryPixelRect(740, 40, 450, 130), rgb(70, 145, 220))
        fill(pixels, EntryPixelRect(1380, 250, 380, 55), rgb(90, 165, 240))
        fill(pixels, EntryPixelRect(945, 935, 430, 110), rgb(245, 245, 245))
        fill(pixels, EntryPixelRect(1405, 935, 430, 110), rgb(65, 150, 240))

        val result = requireNotNull(
            LabyrinthBattleFailureDetector().detect(PixelImage(1920, 1080, pixels)),
        )

        assertTrue(result.confidence >= 0.60)
        assertEquals(EntryPixelRect(1405, 935, 430, 110), result.retryButtonRect)
    }

    @Test
    fun `failure layout exposes retry button but not the end button as the action target`() {
        val pixels = IntArray(1920 * 1080) { rgb(45, 50, 80) }
        fill(pixels, EntryPixelRect(740, 40, 450, 130), rgb(70, 145, 220))
        fill(pixels, EntryPixelRect(1600, 55, 220, 45), rgb(245, 245, 245))
        fill(pixels, EntryPixelRect(945, 935, 430, 110), rgb(245, 245, 245))
        fill(pixels, EntryPixelRect(1405, 935, 430, 110), rgb(65, 150, 240))

        val result = requireNotNull(
            LabyrinthBattleFailureDetector().detect(PixelImage(1920, 1080, pixels)),
        )

        assertTrue(result.confidence >= 0.70)
        assertEquals(EntryPixelRect(945, 935, 430, 110), result.endButtonRect)
        assertEquals(EntryPixelRect(1405, 935, 430, 110), result.retryButtonRect)
    }

    private fun readImage(file: File): PixelImage {
        val imageIo = Class.forName("javax.imageio.ImageIO")
        val image = requireNotNull(imageIo.getMethod("read", File::class.java).invoke(null, file))
        val width = image.javaClass.getMethod("getWidth").invoke(image) as Int
        val height = image.javaClass.getMethod("getHeight").invoke(image) as Int
        val pixels = IntArray(width * height)
        image.javaClass.getMethod(
            "getRGB",
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            IntArray::class.java,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
        ).invoke(image, 0, 0, width, height, pixels, 0, width)
        return PixelImage(width, height, pixels)
    }

    @Test
    fun `generic dark game frame is not a failure page`() {
        val pixels = IntArray(1920 * 1080) { rgb(45, 50, 80) }
        assertNull(LabyrinthBattleFailureDetector().detect(PixelImage(1920, 1080, pixels)))
    }

    @Test
    fun `current client failure fixture is recognized at the real retry button`() {
        val fixture = File("src/test/resources/battle/battle_failure_20260910.jpg")
        val frame = readImage(fixture)

        val result = requireNotNull(LabyrinthBattleFailureDetector().detect(frame))

        assertTrue(result.confidence >= 0.45)
        assertEquals(EntryPixelRect(1405, 935, 430, 110), result.retryButtonRect)
    }

    private fun fill(pixels: IntArray, rect: EntryPixelRect, color: Int) {
        repeat(rect.height) { row ->
            val offset = (rect.top + row) * 1920 + rect.left
            pixels.fill(color, offset, offset + rect.width)
        }
    }

    private companion object {
        fun rgb(red: Int, green: Int, blue: Int): Int =
            (0xff shl 24) or (red shl 16) or (green shl 8) or blue
    }
}
