package com.landosol.toolbox.automation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CoordinateMapperTest {
    private val mapper = CoordinateMapper()

    @Test
    fun `centers reference canvas inside ultrawide screen`() {
        val mapping = mapper.createMapping(PixelRect(0f, 0f, 2400f, 1080f))

        assertEquals(PixelRect(240f, 0f, 2160f, 1080f), mapping.viewport)
        assertEquals(ScreenPoint(1200f, 540f), mapping.toScreen(ScreenPoint(960f, 540f)))
        assertNull(mapping.toReference(ScreenPoint(100f, 540f)))
    }

    @Test
    fun `letterboxes portrait screen without stretching`() {
        val mapping = mapper.createMapping(PixelRect(0f, 0f, 1080f, 1920f))

        assertEquals(0.5625f, mapping.scale)
        assertEquals(656.25f, mapping.viewport.top)
        assertEquals(ScreenPoint(540f, 960f), mapping.toScreen(ScreenPoint(960f, 540f)))
    }

    @Test
    fun `applies calibration offset and can map back`() {
        val mapping = mapper.createMapping(
            PixelRect(0f, 0f, 2400f, 1080f),
            CoordinateCalibration(offsetX = 4f, offsetY = -2f),
        )

        val screen = mapping.toScreen(ScreenPoint(960f, 540f))

        assertEquals(ScreenPoint(1204f, 538f), screen)
        assertEquals(ScreenPoint(960f, 540f), mapping.toReference(screen!!))
    }
}
