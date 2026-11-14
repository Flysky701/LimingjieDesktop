package com.landosol.toolbox.labyrinth

import org.junit.Assert.*
import org.junit.Test

class LabyrinthRelicStackLedgerTest {
    private val ledger = LabyrinthRelicStackLedger()
    private val mark = LabyrinthRelicMark.DEFENSE
    private fun observe(value: Int?, source: LabyrinthRelicStackSource, id: Long, at: Long = 1_000) =
        ledger.observe(mark, value, source, "slot", id, at)
    private fun confirm(value: Int, source: LabyrinthRelicStackSource, id: Long = 1, at: Long = 1_000) {
        observe(value, source, id, at); observe(value, source, id + 1, at + 1)
    }
    private fun acquire(id: String = "reward", before: Int = 9, bonus: Int = 1, at: Long = 100) {
        assertTrue(ledger.prepare(id, mark, bonus, before))
        assertTrue(ledger.acquired(id, at))
    }

    @Test fun `cached and out of order evidence cannot supply a second vote`() {
        repeat(20) { observe(9, LabyrinthRelicStackSource.CHOICE, 10) }
        observe(9, LabyrinthRelicStackSource.CHOICE, 9)
        assertTrue(ledger.stacks.isEmpty())
        observe(9, LabyrinthRelicStackSource.CHOICE, 11)
        assertEquals(9, ledger.stacks[mark])
    }

    @Test fun `map updates before reward acknowledgement cannot double count or leave a conflict`() {
        for (source in listOf(LabyrinthRelicStackSource.MAP, LabyrinthRelicStackSource.CHOICE)) {
            ledger.clear()
            confirm(9, source)
            ledger.prepare("reward", mark, 1, 9)
            confirm(10, LabyrinthRelicStackSource.MAP, 10)
            assertFalse(ledger.needsAudit)
            assertEquals(9, ledger.stacks[mark])
            assertTrue(ledger.acquired("reward", 2_000))
            assertEquals(10, ledger.stacks[mark])
            confirm(10, LabyrinthRelicStackSource.MAP, 20, 2_001)
            assertFalse(ledger.needsAudit)
            assertFalse(ledger.waitingForMap(2_100))
        }
    }

    @Test fun `a newly populated map cannot become the reward baseline while awaiting ownership`() {
        ledger.prepare("reward", mark, 2, 0)
        confirm(2, LabyrinthRelicStackSource.MAP)
        ledger.acquired("reward", 2_000)
        assertEquals(2, ledger.stacks[mark])
    }

    @Test fun `duplicate ownership does not add layers and a different relic does`() {
        acquire()
        assertFalse(ledger.prepare("reward", mark, 1, 0))
        assertFalse(ledger.acquired("reward", 200))
        assertEquals(10, ledger.stacks[mark])
        acquire("other", bonus = 2, at = 300)
        assertEquals(12, ledger.stacks[mark])
    }

    @Test fun `map disagreement needs three fresh votes and matching evidence clears it`() {
        acquire()
        confirm(1, LabyrinthRelicStackSource.MAP)
        assertFalse(ledger.needsAudit)
        repeat(10) { observe(1, LabyrinthRelicStackSource.MAP, 2) }
        assertFalse(ledger.needsAudit)
        observe(1, LabyrinthRelicStackSource.MAP, 3)
        assertTrue(ledger.needsAudit)
        assertEquals(10, ledger.stacks[mark])
        confirm(10, LabyrinthRelicStackSource.MAP, 4)
        assertFalse(ledger.needsAudit)
        confirm(12, LabyrinthRelicStackSource.MAP, 6)
        observe(12, LabyrinthRelicStackSource.MAP, 8)
        assertTrue(ledger.needsAudit)
    }

    @Test fun `late OCR from before the reward cannot roll back the accepted total`() {
        acquire(at = 2_000)
        confirm(9, LabyrinthRelicStackSource.CHOICE, 100, 1_000)
        assertEquals(10, ledger.stacks[mark])
        confirm(8, LabyrinthRelicStackSource.CHOICE, 102, 2_100)
        assertEquals(8, ledger.stacks[mark])
    }

    @Test fun `details correct only visible marks and clear their conflicts`() {
        acquire()
        ledger.prepare("critical", LabyrinthRelicMark.CRITICAL, 3, 0)
        ledger.acquired("critical", 100)
        confirm(1, LabyrinthRelicStackSource.MAP)
        observe(1, LabyrinthRelicStackSource.MAP, 3)
        confirm(4, LabyrinthRelicStackSource.DETAIL)
        assertEquals(4, ledger.stacks[mark])
        assertEquals(3, ledger.stacks[LabyrinthRelicMark.CRITICAL])
        assertFalse(ledger.needsAudit)
    }

    @Test fun `uncertain readings break a pending vote without changing totals`() {
        observe(9, LabyrinthRelicStackSource.CHOICE, 1)
        observe(null, LabyrinthRelicStackSource.CHOICE, 2)
        observe(9, LabyrinthRelicStackSource.CHOICE, 3)
        observe(1, LabyrinthRelicStackSource.CHOICE, 4)
        assertTrue(ledger.stacks.isEmpty())
    }

    @Test fun `restore preserves zero and source rather than rebuilding calibrated totals`() {
        val relic = LabyrinthObservedRelic("owned", "owned", "守备", "3", "", 1.0, 0, 0)
        val records = mapOf(mark to LabyrinthRelicStackRecord(9, LabyrinthRelicStackSource.DETAIL),
            LabyrinthRelicMark.CRITICAL to LabyrinthRelicStackRecord(0, LabyrinthRelicStackSource.CHOICE))
        ledger.restore(listOf(relic), records)
        assertEquals(records, ledger.records)
        assertFalse(ledger.prepare("owned", mark, 3, 0))
        assertFalse(ledger.stacks.containsKey(LabyrinthRelicMark.ACCELERATION))
    }

    @Test fun `migrated unique relic history is an estimate that a stable map can correct`() {
        val relic = LabyrinthObservedRelic("owned", "owned", "守备", "3", "", 1.0, 0, 0)
        ledger.restore(listOf(relic, relic), emptyMap())
        assertEquals(3, ledger.stacks[mark])
        confirm(9, LabyrinthRelicStackSource.MAP)
        assertEquals(9, ledger.stacks[mark])
        assertFalse(ledger.needsAudit)
    }

    @Test fun `reread window begins on the map and ends without repeated taps`() {
        acquire(at = 100)
        assertTrue(ledger.waitingForMap(100_000))
        assertTrue(ledger.waitingForMap(107_999))
        assertFalse(ledger.waitingForMap(108_000))
        assertFalse(ledger.waitingForMap(200_000))
        assertEquals(setOf(mark), ledger.unverifiedMarks(200_000))
        assertFalse(ledger.needsAudit)
        confirm(10, LabyrinthRelicStackSource.MAP, 1, 200_001)
        assertTrue(ledger.unverifiedMarks(200_100).isEmpty())
    }

    @Test fun `failed staged commit leaves live ownership and totals untouched`() {
        confirm(9, LabyrinthRelicStackSource.CHOICE)
        ledger.prepare("reward", mark, 1, 0)
        val staged = ledger.fork()
        assertTrue(staged.acquired("reward", 2_000))
        assertEquals(9, ledger.stacks[mark])
        assertEquals(10, staged.stacks[mark])
        assertTrue(ledger.acquired("reward", 2_000))
    }

    @Test fun `unknown pre-acquisition total stays an estimate until a fresh map confirms it`() {
        assertTrue(ledger.prepare("reward", mark, 2, null))
        ledger.acquired("reward", 100)
        assertEquals(LabyrinthRelicStackSource.HISTORY, ledger.records[mark]?.source)
        confirm(12, LabyrinthRelicStackSource.MAP)
        assertEquals(12, ledger.stacks[mark])
        assertFalse(ledger.needsAudit)
    }

    @Test fun `cancelled pending acquisition no longer blocks calibration`() {
        ledger.prepare("reward", mark, 2, 0)
        val staged = ledger.fork()
        staged.cancel("reward")
        staged.observe(mark, 4, LabyrinthRelicStackSource.CHOICE, "slot", 1)
        staged.observe(mark, 4, LabyrinthRelicStackSource.CHOICE, "slot", 2)
        assertEquals(4, staged.stacks[mark])
        assertFalse(staged.acquired("reward", 100))
    }

    @Test fun `empty sibling card cannot erase independent confirmations from a readable card`() {
        for (id in 1L..3L) {
            ledger.observe(mark, 9, LabyrinthRelicStackSource.CHOICE, "a", id)
            ledger.observe(mark, null, LabyrinthRelicStackSource.CHOICE, "b", id + 10)
        }
        assertEquals(9, ledger.stacks[mark])
    }

    @Test fun `independent slots each require repeated evidence instead of two simultaneous single reads`() {
        ledger.observe(mark, 9, LabyrinthRelicStackSource.CHOICE, "a", 1)
        ledger.observe(mark, 9, LabyrinthRelicStackSource.CHOICE, "b", 2)
        assertTrue(ledger.stacks.isEmpty())
        ledger.observe(mark, 9, LabyrinthRelicStackSource.CHOICE, "a", 3)
        assertEquals(9, ledger.stacks[mark])
    }

    @Test fun `explicit contradictory cards clear the whole pending series`() {
        ledger.observe(mark, 9, LabyrinthRelicStackSource.CHOICE, "a", 1)
        ledger.clearPending(mark)
        ledger.observe(mark, 9, LabyrinthRelicStackSource.CHOICE, "a", 2)
        assertTrue(ledger.stacks.isEmpty())
    }

    @Test fun `a delayed old map total retains the full bounded reread opportunity`() {
        acquire()
        assertTrue(ledger.waitingForMap(10_000))
        for (id in 1L..3L) observe(9, LabyrinthRelicStackSource.MAP, id, 10_000 + id)
        assertTrue(ledger.needsAudit)
        assertTrue(ledger.waitingForMap(14_000))
        ledger.beginMapReread()
        confirm(10, LabyrinthRelicStackSource.MAP, 4, 15_000)
        assertFalse(ledger.waitingForMap(16_000))
        assertFalse(ledger.needsAudit)
    }

    @Test fun `receipt batch sums different relics with the same mark exactly once`() {
        for (id in listOf("40006", "50006", "40006", "50006")) ledger.received(id, mark, 1, 100, knownTotal = 2)
        assertEquals(2, ledger.stacks[mark])
        assertEquals(LabyrinthRelicStackSource.ACQUISITION, ledger.records[mark]?.source)
    }

    @Test fun `late receipt identity cannot add to a map total that already includes it`() {
        confirm(12, LabyrinthRelicStackSource.MAP)
        ledger.received("receipt", mark, 1, 2_000, knownTotal = null)
        assertEquals(12, ledger.stacks[mark])
        assertTrue(ledger.waitingForMap(2_100))
        confirm(12, LabyrinthRelicStackSource.MAP, 3, 2_200)
        assertFalse(ledger.waitingForMap(2_300))
    }

    @Test fun `special receipt relic has ownership but no series increment`() {
        assertTrue(ledger.received("special", null, 0, 100, knownTotal = null))
        assertFalse(ledger.received("special", null, 0, 200, knownTotal = null))
        assertTrue(ledger.stacks.isEmpty())
    }

    @Test fun `complete history is absolute even if map already contains the direct grant`() {
        confirm(2, LabyrinthRelicStackSource.MAP)
        ledger.received("40006", mark, 1, 2_000, knownTotal = 2)
        ledger.received("50006", mark, 1, 2_000, knownTotal = 2)
        assertEquals(2, ledger.stacks[mark])
    }

    @Test fun `unknown direct grant baseline is not protected against fresh map correction`() {
        acquire()
        ledger.received("unplanned", mark, 1, 2_000, knownTotal = null)
        confirm(11, LabyrinthRelicStackSource.MAP, 4, 2_100)
        assertEquals(11, ledger.stacks[mark])
        assertFalse(ledger.needsAudit)
    }

    @Test fun `clear permits the same relic in a new run`() {
        acquire()
        ledger.clear()
        assertTrue(ledger.stacks.isEmpty())
        assertFalse(ledger.needsAudit)
        acquire(before = 0)
        assertEquals(1, ledger.stacks[mark])
    }
}
