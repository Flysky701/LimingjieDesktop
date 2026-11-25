package com.landosol.toolbox.labyrinth.vision

import org.junit.Assert.assertEquals
import org.junit.Test

class ReferenceFitMapperTest {
    @Test
    fun `centers standard reference canvas inside ultrawide frame`() {
        val mapped = ReferenceFitMapper.map(
            frameWidth = 2560,
            frameHeight = 1080,
            referenceSize = EntryReferenceSize(1920, 1080),
            referenceRect = EntryReferenceRect(757, 965, 408, 59),
        )

        assertEquals(1077, mapped?.left)
        assertEquals(965, mapped?.top)
        assertEquals(408, mapped?.width)
        assertEquals(59, mapped?.height)
    }

    @Test
    fun `fits standard reference canvas into taller frame without cropping`() {
        val mapped = ReferenceFitMapper.map(
            frameWidth = 2780,
            frameHeight = 1264,
            referenceSize = EntryReferenceSize(1920, 1080),
            referenceRect = EntryReferenceRect(586, 644, 723, 276),
        )

        assertEquals(952, mapped?.left)
        assertEquals(754, mapped?.top)
        assertEquals(846, mapped?.width)
        assertEquals(323, mapped?.height)
    }
}
