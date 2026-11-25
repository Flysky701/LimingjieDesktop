package com.landosol.toolbox.automation.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureStateRegistryTest {
    @Test
    fun `registry reports active only while capture is running`() {
        CaptureStateRegistry.update(CaptureState.Running(1080, 1920, 1L, gestureDisplayId = 10))
        assertTrue(CaptureStateRegistry.isActive())
        assertEquals(10, CaptureStateRegistry.gestureDisplayId())

        CaptureStateRegistry.update(CaptureState.Stopped("test"))
        assertFalse(CaptureStateRegistry.isActive())
        assertEquals(0, CaptureStateRegistry.gestureDisplayId())
        assertEquals("test", CaptureStateRegistry.lastStopReason())
        CaptureStateRegistry.clear()
        assertEquals("test", CaptureStateRegistry.lastStopReason())

        CaptureStateRegistry.update(CaptureState.Running(1920, 1080, 2L, gestureDisplayId = 11))
        assertTrue(CaptureStateRegistry.isActive())
        assertEquals(null, CaptureStateRegistry.lastStopReason())
        CaptureStateRegistry.clear()
    }
}
