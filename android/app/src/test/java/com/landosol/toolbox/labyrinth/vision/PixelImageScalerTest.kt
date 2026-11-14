package com.landosol.toolbox.labyrinth.vision

import com.landosol.toolbox.clanbattle.recognition.PixelImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test

class PixelImageScalerTest {
    @Test fun `same size returns the source untouched`() {
        val source = PixelImage(4, 3, IntArray(12) { 0xff102030.toInt() + it })
        assertSame(source, PixelImageScaler.scale(source, 4, 3))
    }

    @Test fun `half size averages each two by two block`() {
        val source = PixelImage(
            4, 4,
            intArrayOf(
                0xff000000.toInt(), 0xff000000.toInt(), 0xffffffff.toInt(), 0xffffffff.toInt(),
                0xff000000.toInt(), 0xff000000.toInt(), 0xffffffff.toInt(), 0xffffffff.toInt(),
                0xff404040.toInt(), 0xff404040.toInt(), 0xff808080.toInt(), 0xff808080.toInt(),
                0xff404040.toInt(), 0xff404040.toInt(), 0xff808080.toInt(), 0xff808080.toInt(),
            ),
        )
        val scaled = PixelImageScaler.scale(source, 2, 2)
        assertEquals(2, scaled.width)
        assertEquals(2, scaled.height)
        assertEquals(0xff000000.toInt(), scaled[0, 0])
        assertEquals(0xffffffff.toInt(), scaled[1, 0])
        assertEquals(0xff404040.toInt(), scaled[0, 1])
        assertEquals(0xff808080.toInt(), scaled[1, 1])
    }

    @Test fun `transparent pixels do not darken an opaque edge`() {
        // Left half opaque red, right half fully transparent (the shape of a node-icon mask edge).
        val source = PixelImage(
            2, 1,
            intArrayOf(0xffff0000.toInt(), 0x00000000),
        )
        val scaled = PixelImageScaler.scale(source, 1, 1)
        // Alpha is averaged to 127, but the colour stays pure red rather than becoming dark red.
        val alpha = scaled[0, 0] ushr 24 and 0xff
        val red = scaled[0, 0] ushr 16 and 0xff
        val green = scaled[0, 0] ushr 8 and 0xff
        assertEquals(127, alpha)
        assertEquals(255, red)
        assertEquals(0, green)
    }

    @Test fun `fully transparent input stays transparent`() {
        val source = PixelImage(2, 2, IntArray(4) { 0x00ffffff })
        val scaled = PixelImageScaler.scale(source, 1, 1)
        assertEquals(0, scaled[0, 0] ushr 24 and 0xff)
    }

    @Test fun `non integer ratio keeps every source column represented`() {
        // 3 -> 2: with a sampling kernel the middle column would be skipped entirely.
        val source = PixelImage(
            3, 1,
            intArrayOf(0xff000000.toInt(), 0xffffffff.toInt(), 0xff000000.toInt()),
        )
        val scaled = PixelImageScaler.scale(source, 2, 1)
        val first = scaled[0, 0] and 0xff
        val second = scaled[1, 0] and 0xff
        // Both target columns are grey: neither is a pure black copy of one source column.
        assertEquals(true, first in 1..254)
        assertEquals(true, second in 1..254)
        assertNotSame(source, scaled)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `upscaling is rejected`() {
        val source = PixelImage(2, 2, IntArray(4) { 0xff000000.toInt() })
        PixelImageScaler.scale(source, 3, 3)
    }
}
