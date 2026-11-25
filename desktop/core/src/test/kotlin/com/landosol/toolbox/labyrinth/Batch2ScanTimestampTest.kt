package com.landosol.toolbox.labyrinth

import com.landosol.toolbox.labyrinth.vision.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Replay the batch-1 defect: captured before the deadline, handled after it. */
class Batch2ScanTimestampTest {
    private fun awaitingMap(f: Batch2SessionFixture): LabyrinthRelicStackLedger =
        (f.get("relicStackLedger") as LabyrinthRelicStackLedger).also {
            assertTrue(it.prepare("test-relic", LabyrinthRelicMark.DEFENSE, 1, 9))
            assertTrue(it.acquired("test-relic", 100L))
            assertTrue(it.waitingForMap(1_000L))
        }

    private fun mapFrame() = LabyrinthEntryFrameResult(
        observation = LabyrinthEntryPageObservation(LabyrinthEntryPageState.NODE_SELECTION, 1.0,
            emptyMap(), LabyrinthAnchorScores(emptyMap())),
        matchedFeatures = emptyList(), elapsedMillis = 0,
        nodeSearchMode = "deferred", frameWidth = 1920, frameHeight = 1080,
    )

    @Test fun `captured frame before timeout remains held when processing starts after timeout`() = runBlocking {
        val f = Batch2SessionFixture()
        try {
            f.start()
            awaitingMap(f)
            f.clock.set(9_500L)
            f.set("nodeScanFrameTimestampMillis", 8_999L)
            assertFalse(f.session.nodeScanConsumable())
            assertEquals(8_999L, f.get("lastNodeScanQueryTimestampMillis"))
            assertEquals("RELIC_MAP_VERIFICATION", f.get("lastNodeScanHoldReason"))
            f.set("nodeScanFrameTimestampMillis", null)
            f.feed(mapFrame(), 8_999L, processingAt = 9_500L)
            assertEquals("正在复核地图系列层数", f.session.state.value.message)
            assertEquals(0, f.calls.get())
            assertFalse(f.session.nodeScanConsumableAt(8_999L))
        } finally { f.set("nodeScanFrameTimestampMillis", null); f.awaitActions(); f.close() }
    }

    @Test fun `exact original eight second deadline opens both scan and verification gates`() = runBlocking {
        val f = Batch2SessionFixture()
        try {
            f.start()
            val ledger = awaitingMap(f)
            assertFalse(f.session.nodeScanConsumableAt(8_999L))
            assertTrue(ledger.waitingForMap(8_999L))
            assertTrue(f.session.nodeScanConsumableAt(9_000L))
            assertFalse(ledger.waitingForMap(9_000L))
            assertNull(f.get("lastNodeScanHoldReason"))
            assertEquals(9_000L, f.get("lastNodeScanQueryTimestampMillis"))
        } finally { f.awaitActions(); f.close() }
    }

    @Test fun `pre scan query cannot start or extend the reread timeout`() = runBlocking {
        val f = Batch2SessionFixture()
        try {
            f.start()
            val ledger = f.get("relicStackLedger") as LabyrinthRelicStackLedger
            assertTrue(ledger.prepare("test-relic", LabyrinthRelicMark.DEFENSE, 1, 9))
            assertTrue(ledger.acquired("test-relic", 100L))
            assertFalse(f.session.nodeScanConsumableAt(1_000L))
            assertFalse(f.session.nodeScanConsumableAt(100_000L))
            assertTrue(ledger.waitingForMap(200_000L))
            assertFalse(f.session.nodeScanConsumableAt(207_999L))
            assertTrue(f.session.nodeScanConsumableAt(208_000L))
            assertFalse(ledger.waitingForMap(208_000L))
        } finally { f.awaitActions(); f.close() }
    }

    @Test fun `map settling also uses capture time rather than later execution time`() = runBlocking {
        val f = Batch2SessionFixture()
        try {
            f.start()
            f.set("lastPostEntryActionAt", 1_000L)
            f.clock.set(3_500L)
            f.set("nodeScanFrameTimestampMillis", 2_999L)
            assertFalse(f.session.nodeScanConsumable())
            assertEquals("MAP_SETTLE", f.get("lastNodeScanHoldReason"))
            f.set("nodeScanFrameTimestampMillis", 3_000L)
            assertTrue(f.session.nodeScanConsumable())
            assertNull(f.get("lastNodeScanHoldReason"))
        } finally { f.set("nodeScanFrameTimestampMillis", null); f.awaitActions(); f.close() }
    }

    @Test fun `fresh map evidence releases the gate without waiting for the deadline`() = runBlocking {
        val f = Batch2SessionFixture()
        try {
            f.start()
            val ledger = awaitingMap(f)
            ledger.observe(LabyrinthRelicMark.DEFENSE, 10, LabyrinthRelicStackSource.MAP, "map", 1L, 2_000L)
            assertFalse(f.session.nodeScanConsumableAt(2_001L))
            ledger.observe(LabyrinthRelicMark.DEFENSE, 10, LabyrinthRelicStackSource.MAP, "map", 2L, 2_300L)
            assertTrue(f.session.nodeScanConsumableAt(2_301L))
        } finally { f.awaitActions(); f.close() }
    }

    @Test fun `cleared frame context falls back to current clock without changing dry run policy`() = runBlocking {
        val f = Batch2SessionFixture()
        try {
            f.start()
            awaitingMap(f)
            f.clock.set(9_500L)
            assertTrue(f.session.nodeScanConsumable())
            assertEquals(9_500L, f.get("lastNodeScanQueryTimestampMillis"))
            f.state { it.copy(dryRun = true) }
            assertTrue(f.session.nodeScanConsumableAt(2_000L))
            assertNull(f.get("lastNodeScanHoldReason"))
        } finally { f.awaitActions(); f.close() }
    }
}
