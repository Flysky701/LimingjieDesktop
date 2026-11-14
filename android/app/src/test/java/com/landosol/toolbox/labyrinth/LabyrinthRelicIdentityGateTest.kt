package com.landosol.toolbox.labyrinth

import com.landosol.toolbox.labyrinth.vision.EntryPixelRect
import com.landosol.toolbox.labyrinth.vision.LabyrinthEntryPageState
import com.landosol.toolbox.labyrinth.vision.LabyrinthRelicMatch
import org.junit.Assert.*
import org.junit.Test

class LabyrinthRelicIdentityGateTest {
    private val gate = LabyrinthRelicIdentityGate()
    private val relic = LabyrinthRelicMatch("left", "a", "a", "守备", "1", "", 0.9,
        EntryPixelRect(0, 0, 10, 10), iconRect = EntryPixelRect(0, 0, 5, 5))
    @Test fun `duplicate capture timestamps cannot confirm identity`() {
        repeat(10) { gate.observe(LabyrinthEntryPageState.RELIC_CHOICE, 100, listOf(relic)) }
        assertFalse(gate.confirmed(relic))
        gate.observe(LabyrinthEntryPageState.RELIC_CHOICE, 200, listOf(relic))
        assertTrue(gate.confirmed(relic))
    }
    @Test fun `different identities and missing slots break agreement`() {
        gate.observe(LabyrinthEntryPageState.RELIC_CHOICE, 1, listOf(relic))
        gate.observe(LabyrinthEntryPageState.RELIC_CHOICE, 2, listOf(relic.copy(relicId = "b")))
        assertFalse(gate.confirmed(relic))
        gate.observe(LabyrinthEntryPageState.RELIC_CHOICE, 3, listOf(relic))
        assertFalse(gate.confirmed(relic))
        gate.observe(LabyrinthEntryPageState.RELIC_CHOICE, 4, emptyList())
        gate.observe(LabyrinthEntryPageState.RELIC_CHOICE, 5, listOf(relic))
        assertFalse(gate.confirmed(relic))
    }
    @Test fun `purchase modal requires its own two frames and clearing retires all votes`() {
        gate.observe(LabyrinthEntryPageState.SHOP, 1, listOf(relic))
        gate.observe(LabyrinthEntryPageState.SHOP, 2, listOf(relic))
        assertTrue(gate.confirmed(relic))
        gate.observe(LabyrinthEntryPageState.SHOP_PURCHASE_CONFIRMATION, 3, listOf(relic))
        assertFalse(gate.confirmed(relic))
        gate.observe(LabyrinthEntryPageState.SHOP_PURCHASE_CONFIRMATION, 4, listOf(relic))
        assertTrue(gate.confirmed(relic))
        gate.clear()
        assertFalse(gate.confirmed(relic))
    }
}
