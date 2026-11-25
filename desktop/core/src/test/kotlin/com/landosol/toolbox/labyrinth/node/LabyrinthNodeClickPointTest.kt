package com.landosol.toolbox.labyrinth.node

import com.landosol.toolbox.labyrinth.vision.EntryPixelRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LabyrinthNodeClickPointTest {
    private val planner = LabyrinthNodeActionPlanner()

    @Test fun `a rejected tap is never repeated at the same pixel`() {
        // 2026-09-20 bundle 163307: 连结#20602 matched at (848,238,280,350) and was tapped three
        // times at exactly 988,350 — ~7 s apart, each rejected because the game attributed the
        // point to the node above and answered "cannot move there". Only after a map nudge did
        // the same node re-match at top=350, where the tap was accepted. 41 s for one node.
        val rect = EntryPixelRect(848, 238, 280, 350)
        val taps = (0..2).map { planner.getBaseClickPosition(rect, it) }
        assertEquals("first attempt is unchanged", Pair(988, 350), taps[0])
        assertEquals("every attempt keeps the node's centre line", listOf(988, 988, 988), taps.map { it.first })
        assertEquals("no attempt repeats another", taps.size, taps.distinct().size)
    }

    @Test fun `the first retry aims where the node actually was`() {
        // The crop was registered ~112 px high. After the nudge the accepted tap was y=462.
        val rect = EntryPixelRect(848, 238, 280, 350)
        val (_, retryY) = planner.getBaseClickPosition(rect, 1)
        assertTrue("retry $retryY should land near the accepted 462", retryY in 430..490)
    }

    @Test fun `retries stay inside the crop and clear of the bottom HUD band`() {
        // `.bottom` templates include the retreat/return controls below 0.78 of the crop.
        val rect = EntryPixelRect(1304, 670, 280, 350)
        (0..2).forEach { retry ->
            val (_, y) = planner.getBaseClickPosition(rect, retry)
            assertTrue("retry $retry escaped the crop at $y", y >= rect.top && y < rect.top + rect.height)
            assertTrue("retry $retry reached the HUD at $y", y - rect.top < rect.height * 0.78)
            assertTrue("retry $retry reached the screen controls at $y", y < 930)
        }
    }

    @Test fun `an unknown retry index falls back to the calibrated point`() {
        val rect = EntryPixelRect(848, 238, 280, 350)
        assertEquals(planner.getBaseClickPosition(rect, 0), planner.getBaseClickPosition(rect, 99))
        assertNotEquals(planner.getBaseClickPosition(rect, 0), planner.getBaseClickPosition(rect, 1))
    }
}
