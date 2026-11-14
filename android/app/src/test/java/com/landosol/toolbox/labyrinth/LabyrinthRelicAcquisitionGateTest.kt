package com.landosol.toolbox.labyrinth

import com.landosol.toolbox.labyrinth.vision.LabyrinthEntryPageState
import org.junit.Assert.*
import org.junit.Test

class LabyrinthRelicAcquisitionGateTest {
    private val gate = LabyrinthRelicAcquisitionGate()
    @Test fun `click and unknown loading frames never commit a reward`() {
        repeat(10) { assertFalse(gate.observe(true, LabyrinthEntryPageState.RELIC_CHOICE, it.toLong())) }
        repeat(10) { assertFalse(gate.observe(true, LabyrinthEntryPageState.UNKNOWN, it.toLong())) }
    }
    @Test fun `requires two consecutive fresh map frames after click`() {
        assertFalse(gate.observe(true, LabyrinthEntryPageState.NODE_SELECTION, 1))
        assertFalse(gate.observe(true, LabyrinthEntryPageState.UNKNOWN, 2))
        assertFalse(gate.observe(true, LabyrinthEntryPageState.NODE_SELECTION, 3))
        assertFalse(gate.observe(true, LabyrinthEntryPageState.NODE_SELECTION, 3))
        assertFalse(gate.observe(true, LabyrinthEntryPageState.NODE_SELECTION, 2))
        assertTrue(gate.observe(true, LabyrinthEntryPageState.NODE_SELECTION, 4))
    }
    @Test fun `cleared commitment or new run cannot add reward again`() {
        assertFalse(gate.observe(true, LabyrinthEntryPageState.NODE_SELECTION, 1))
        assertTrue(gate.observe(true, LabyrinthEntryPageState.NODE_SELECTION, 2))
        repeat(10) { assertFalse(gate.observe(false, LabyrinthEntryPageState.NODE_SELECTION, it.toLong())) }
        gate.reset()
        assertFalse(gate.observe(true, LabyrinthEntryPageState.NODE_SELECTION, 20))
    }
}
