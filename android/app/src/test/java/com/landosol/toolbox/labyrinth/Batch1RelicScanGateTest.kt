package com.landosol.toolbox.labyrinth

import org.junit.Assert.*
import org.junit.Test

class Batch1RelicScanGateTest {
    private fun acquired(): LabyrinthRelicStackLedger = LabyrinthRelicStackLedger().also {
        assertTrue(it.prepare("r", LabyrinthRelicMark.DEFENSE, 1, 9))
        assertTrue(it.acquired("r", 100L))
    }
    @Test fun `peek does not start or extend map timeout`() {
        val ledger = acquired()
        assertTrue(ledger.peekWaitingForMap(1_000L))
        assertTrue(ledger.peekWaitingForMap(100_000L))
        assertTrue(ledger.waitingForMap(200_000L))
        assertTrue(ledger.peekWaitingForMap(207_999L))
        assertFalse(ledger.peekWaitingForMap(208_000L))
        assertFalse(ledger.waitingForMap(208_000L))
    }
    @Test fun `fresh matching evidence releases scan gate`() {
        val ledger = acquired()
        assertTrue(ledger.peekWaitingForMap(1_000L))
        ledger.observe(LabyrinthRelicMark.DEFENSE, 10, LabyrinthRelicStackSource.MAP, "s", 1L, 1_000L)
        assertTrue(ledger.peekWaitingForMap(1_001L))
        ledger.observe(LabyrinthRelicMark.DEFENSE, 10, LabyrinthRelicStackSource.MAP, "s", 2L, 1_001L)
        assertFalse(ledger.peekWaitingForMap(1_002L))
    }
    @Test fun `cached evidence cannot release scan gate`() {
        val ledger = acquired()
        repeat(5) { ledger.observe(LabyrinthRelicMark.DEFENSE, 10, LabyrinthRelicStackSource.MAP, "s", 1L, 1_000L) }
        assertTrue(ledger.peekWaitingForMap(1_001L))
    }
    @Test fun `acquisition and verification holds defer only node work`() {
        assertTrue(labyrinthNodeScanConsumable(0L, false))
        assertFalse(labyrinthNodeScanConsumable(0L, false, relicAcquisitionHeld = true))
        assertFalse(labyrinthNodeScanConsumable(0L, false, relicVerificationHeld = true))
        assertFalse(labyrinthNodeScanConsumable(1L, false))
        assertFalse(labyrinthNodeScanConsumable(0L, true))
        assertTrue(labyrinthNodeScanConsumable(-1L, false))
    }
    @Test fun `dry run bypasses acquisition verification hold flags`() {
        val dryRun = true
        assertTrue(labyrinthNodeScanConsumable(0L, false,
            relicAcquisitionHeld = !dryRun, relicVerificationHeld = !dryRun))
    }
    @Test fun `empty ledger never holds scans`() {
        assertFalse(LabyrinthRelicStackLedger().peekWaitingForMap(100L))
    }
}
