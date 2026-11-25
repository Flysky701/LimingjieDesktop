package com.landosol.toolbox.labyrinth.vision

import com.landosol.toolbox.vision.PixelImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LabyrinthOcrInkBoundsTest {
    private val white = 0xFFFFFFFF.toInt()
    private val glyph = 0xFF404040.toInt()
    private val underline = 0xFF80A6E5.toInt()

    /** Geometry measured on the 2026-09-16 16:13 bundle: 美空 in the 500x70 detail name band. */
    private fun misoraNameBand(): PixelImage {
        val width = 500
        val height = 70
        val pixels = IntArray(width * height) { white }
        for (y in 16..44) for (x in 7..69) pixels[y * width + x] = glyph
        for (y in 55..60) for (x in 0 until width) pixels[y * width + x] = underline
        return PixelImage(width, height, pixels)
    }

    @Test
    fun `short name tightens to its ink and ignores the blue rule below it`() {
        val bounds = requireNotNull(labyrinthOcrInkBounds(misoraNameBand()))
        assertEquals(EntryPixelRect(left = 1, top = 10, width = 75, height = 41), bounds)
    }

    @Test
    fun `long name keeps its full width`() {
        val width = 500
        val height = 70
        val pixels = IntArray(width * height) { white }
        for (y in 16..44) for (x in 7..430) pixels[y * width + x] = glyph
        val bounds = requireNotNull(labyrinthOcrInkBounds(PixelImage(width, height, pixels)))
        assertEquals(1, bounds.left)
        assertEquals(437, bounds.left + bounds.width)
    }

    @Test
    fun `panel with only the decorative rule has no ink`() {
        val width = 500
        val height = 70
        val pixels = IntArray(width * height) { white }
        for (y in 55..60) for (x in 0 until width) pixels[y * width + x] = underline
        assertNull(labyrinthOcrInkBounds(PixelImage(width, height, pixels)))
        assertNull(labyrinthOcrInkBounds(PixelImage(width, height, IntArray(width * height) { white })))
    }

    @Test
    fun `isolated specks do not open a box`() {
        val width = 500
        val height = 70
        val pixels = IntArray(width * height) { white }
        pixels[30 * width + 300] = glyph
        pixels[10 * width + 480] = glyph
        assertNull(labyrinthOcrInkBounds(PixelImage(width, height, pixels)))
        val bounds = labyrinthOcrInkBounds(PixelImage(width, height, pixels), minimumInkPixelsPerLine = 1)
        assertTrue(bounds != null)
    }
}
