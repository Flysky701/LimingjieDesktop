package com.landosol.toolbox.labyrinth

import com.landosol.toolbox.automation.capture.RefCountedFrameLease
import com.landosol.toolbox.labyrinth.concurrent.LabyrinthFourThreadRuntime
import com.landosol.toolbox.labyrinth.concurrent.WorkKind
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class Batch5EvidenceAndMailboxTest {
    private fun snapshot(version: Long = 1) = LabyrinthBalancedVisionContext.RequestSnapshot(
        finalBossOnly = false, skipJoinedCharacters = false, relicEvidenceVersion = version,
        relicVerificationMarks = linkedSetOf("防御"), nodeScanRequested = true,
        nodeScanConsumable = true, nodeSearchHint = null, rosterCharacterIds = linkedSetOf("r2", "r1"),
        allowInitial1088GeometryRefinement = false,
    )

    @Test fun `nested helped vision roots restore snapshot and query including exception`() {
        assertNull(LabyrinthBalancedVisionContext.current())
        LabyrinthBalancedVisionContext.withSnapshot(snapshot()) {
            LabyrinthBalancedVisionContext.markNodeScanQuery()
            assertTrue(LabyrinthBalancedVisionContext.nodeScanQueryUsed())
            try {
                LabyrinthBalancedVisionContext.withSnapshot(snapshot(2)) {
                    assertEquals(2L, LabyrinthBalancedVisionContext.current()!!.relicEvidenceVersion)
                    assertNull(LabyrinthBalancedVisionContext.current()!!.nodeSearchHint)
                    assertFalse(LabyrinthBalancedVisionContext.nodeScanQueryUsed())
                    throw IllegalArgumentException("inner root")
                }
            } catch (_: IllegalArgumentException) { }
            assertEquals(1L, LabyrinthBalancedVisionContext.current()!!.relicEvidenceVersion)
            assertTrue(LabyrinthBalancedVisionContext.nodeScanQueryUsed())
        }
        assertNull(LabyrinthBalancedVisionContext.current())
        assertFalse(LabyrinthBalancedVisionContext.nodeScanQueryUsed())
    }

    @Test fun `another helped root cannot read or mark outer vision context`() = runBlocking {
        val root = LabyrinthFourThreadRuntime.submitWork(kind = WorkKind.DIAGNOSTIC) {
            LabyrinthBalancedVisionContext.withSnapshot(snapshot()) {
                val other = LabyrinthFourThreadRuntime.submitWork(kind = WorkKind.OBSERVATION) {
                    assertNull(LabyrinthBalancedVisionContext.current())
                    LabyrinthBalancedVisionContext.markNodeScanQuery()
                    assertFalse(LabyrinthBalancedVisionContext.nodeScanQueryUsed())
                    true
                }
                assertTrue(other.future.get())
                assertEquals(1L, LabyrinthBalancedVisionContext.current()!!.relicEvidenceVersion)
                assertFalse(LabyrinthBalancedVisionContext.nodeScanQueryUsed())
                true
            }
        }
        assertTrue(root.await())
    }

    @Test fun `latest replacement closes mailbox handle but not active CPU reader`() {
        val disposed = mutableListOf<String>()
        val mailbox = LabyrinthBalancedMailbox<String>()
        val first = RefCountedFrameLease.create("first") { disposed += it }
        val cpu = first.retain()
        assertFalse(mailbox.offer(first))
        assertTrue(mailbox.offer(RefCountedFrameLease.create("latest") { disposed += it }))
        assertTrue(first.isClosed)
        assertEquals("first", cpu.value)
        assertTrue(disposed.isEmpty())
        mailbox.close()
        assertEquals(listOf("latest"), disposed)
        cpu.close()
        assertEquals(listOf("latest", "first"), disposed)
        assertFalse(mailbox.offer(RefCountedFrameLease.create("after-stop") { disposed += it }))
        assertNull(mailbox.take())
        assertEquals(listOf("latest", "first", "after-stop"), disposed)
    }

    @Test fun `fresh evidence rejects stale preplanner wrong epoch or resized frame`() {
        fun fresh(at: Long? = 900, after: Long = 800, epoch: Long = 3, width: Int = 1920) =
            LabyrinthBalancedEvidence.fresh(1000, at, after, epoch, 3, width, 1080, 1920, 1080)
        assertTrue(fresh())
        assertFalse(fresh(null))
        assertFalse(fresh(after = 900))
        assertFalse(fresh(epoch = 2))
        assertFalse(fresh(width = 1280))
        assertFalse(LabyrinthBalancedEvidence.fresh(4000, 900, 800, 3, 3, 1920, 1080, 1920, 1080))
    }

    @Test fun `mailbox backpressure retains only latest of many diagnostic independent handles`() {
        var disposed = 0
        val mailbox = LabyrinthBalancedMailbox<Int>()
        repeat(100) { mailbox.offer(RefCountedFrameLease.create(it) { disposed++ }) }
        assertEquals(99, disposed)
        val latest = requireNotNull(mailbox.take())
        assertEquals(99, latest.value)
        latest.close(); mailbox.close()
        assertEquals(100, disposed)
    }
}
