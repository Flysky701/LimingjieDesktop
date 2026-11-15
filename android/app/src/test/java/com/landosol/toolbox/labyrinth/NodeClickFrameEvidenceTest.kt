package com.landosol.toolbox.labyrinth

import com.landosol.toolbox.labyrinth.node.NodeFrameEvidenceSource
import java.lang.reflect.Modifier
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test

/** Tests the production evidence owner/token, not a copy of its freshness algorithm. */
class NodeClickFrameEvidenceTest {
    @Test fun explicitSourcesDoNotImplicitlyTrustMissingEvidence() {
        assertFalse(NodeFrameEvidenceSource.NONE.currentFrameVerified)
        for (source in listOf(NodeFrameEvidenceSource.SEARCH, NodeFrameEvidenceSource.TRACKED,
            NodeFrameEvidenceSource.MEMO_REVALIDATED, NodeFrameEvidenceSource.FINAL_BOSS_PLATFORM)) {
            assertTrue(source.name, source.currentFrameVerified)
        }
    }

    @Test fun absentAndUnverifiedFramesCannotCreateTokens() {
        val evidence = LabyrinthNodeActionEvidence()
        assertFalse(evidence.isCurrentFrame(1_000L))
        assertNull(evidence.capture(1_000L))
        evidence.observe(1_000L, currentFrameVerified = false)
        assertFalse(evidence.isCurrentFrame(1_000L))
        assertNull(evidence.capture(1_000L))
    }

    @Test fun tokenUsesOriginalCaptureAndExactFreshnessBoundary() {
        val evidence = LabyrinthNodeActionEvidence()
        evidence.observe(10_000L, currentFrameVerified = true)
        assertTrue(evidence.isCurrentFrame(10_000L))
        assertNull(evidence.capture(9_999L))
        val token = requireNotNull(evidence.capture(10_000L))
        assertFalse(token.isCurrent(9_999L))
        assertTrue(token.isCurrent(10_000L))
        assertTrue(token.isCurrent(11_200L))
        assertFalse(token.isCurrent(11_201L))
    }

    @Test fun everyAcceptedNewFrameRevokesPreviouslyCapturedCoordinates() {
        val evidence = LabyrinthNodeActionEvidence()
        evidence.observe(1_000L, true)
        val original = requireNotNull(evidence.capture(1_000L))
        evidence.observe(1_001L, true)
        assertFalse(original.isCurrent(1_001L))
        assertTrue(requireNotNull(evidence.capture(1_001L)).isCurrent(1_001L))
        val second = requireNotNull(evidence.capture(1_001L))
        evidence.observe(1_002L, false)
        assertFalse(second.isCurrent(1_002L))
        assertNull(evidence.capture(1_002L))
    }

    @Test fun repeatedCaptureTimestampsCannotManufactureStableFrameProof() {
        val evidence = LabyrinthNodeActionEvidence()
        evidence.observe(1_000L, true)
        val original = requireNotNull(evidence.capture(1_000L))
        repeat(12) {
            evidence.observe(1_000L, true)
            assertFalse(evidence.isCurrentFrame(1_000L))
            assertNull(evidence.capture(1_000L))
            assertFalse(original.isCurrent(1_001L))
        }
        evidence.observe(1_001L, true)
        assertTrue(evidence.isCurrentFrame(1_001L))
    }

    @Test fun outOfOrderAndUnverifiedFramesCannotLowerTheWatermark() {
        val evidence = LabyrinthNodeActionEvidence()
        evidence.observe(2_000L, false)
        evidence.observe(1_999L, true)
        assertFalse(evidence.isCurrentFrame(1_999L))
        assertNull(evidence.capture(1_999L))
        evidence.observe(2_000L, true)
        assertFalse(evidence.isCurrentFrame(2_000L))
        evidence.observe(2_001L, true)
        assertTrue(requireNotNull(evidence.capture(2_001L)).isCurrent(2_001L))
    }

    @Test fun invalidationDoesNotAllowTheSameOldCaptureToBeReauthorized() {
        val evidence = LabyrinthNodeActionEvidence()
        evidence.observe(3_000L, true)
        val old = requireNotNull(evidence.capture(3_000L))
        evidence.invalidate()
        assertFalse(old.isCurrent(3_001L))
        assertNull(evidence.capture(3_000L))
        evidence.observe(3_000L, true)
        assertFalse(evidence.isCurrentFrame(3_000L))
        evidence.observe(3_001L, true)
        assertNotNull(evidence.capture(3_001L))
    }

    @Test fun resetAllowsANewStreamButNeverRevivesAnOldToken() {
        val evidence = LabyrinthNodeActionEvidence()
        evidence.observe(8_000L, true)
        val old = requireNotNull(evidence.capture(8_000L))
        evidence.reset()
        evidence.observe(8_000L, true)
        val replacement = requireNotNull(evidence.capture(8_000L))
        assertFalse(old.isCurrent(8_000L))
        assertTrue(replacement.isCurrent(8_000L))
        evidence.reset()
        evidence.observe(1L, true)
        assertTrue(requireNotNull(evidence.capture(1L)).isCurrent(1L))
        assertFalse(replacement.isCurrent(8_001L))
    }

    @Test fun newEvidenceDoesNotExtendTheDeadlineOfAnOldToken() {
        val evidence = LabyrinthNodeActionEvidence()
        evidence.observe(10_000L, true)
        val old = requireNotNull(evidence.capture(10_000L))
        evidence.observe(11_199L, true)
        assertFalse(old.isCurrent(11_199L))
        assertFalse(old.isCurrent(11_201L))
        assertTrue(requireNotNull(evidence.capture(11_199L)).isCurrent(11_201L))
    }

    @Test fun negativeFutureAndLongBoundariesAreSafe() {
        val evidence = LabyrinthNodeActionEvidence()
        evidence.observe(Long.MIN_VALUE, true)
        assertNull(evidence.capture(Long.MIN_VALUE))
        evidence.observe(-1L, true)
        assertNull(evidence.capture(-1L))
        evidence.observe(0L, true)
        val zero = requireNotNull(evidence.capture(0L))
        assertFalse(zero.isCurrent(-1L))
        assertTrue(zero.isCurrent(1_200L))
        assertFalse(zero.isCurrent(Long.MAX_VALUE))
        evidence.observe(Long.MAX_VALUE - 1_200L, true)
        val upper = requireNotNull(evidence.capture(Long.MAX_VALUE - 1_200L))
        assertTrue(upper.isCurrent(Long.MAX_VALUE))
        assertFalse(upper.isCurrent(Long.MIN_VALUE))
    }

    @Test fun signedRevisionWrapDoesNotReviveTheImmediatelyPreviousToken() {
        val evidence = LabyrinthNodeActionEvidence()
        evidence.observe(1_000L, true)
        val field = evidence.javaClass.getDeclaredField("revision").apply { isAccessible = true }
        (field.get(evidence) as AtomicLong).set(Long.MAX_VALUE)
        val old = requireNotNull(evidence.capture(1_000L))
        evidence.observe(1_001L, true)
        assertFalse(old.isCurrent(1_001L))
        assertTrue(requireNotNull(evidence.capture(1_001L)).isCurrent(1_001L))
    }

    @Test(timeout = 15_000L) fun captureRacingWithObserveInvalidateOrResetNeverBorrowsANewRevision() {
        data class Attempt(
            val evidence: LabyrinthNodeActionEvidence,
            val capturedAt: Long,
            val captured: AtomicReference<LabyrinthNodeActionEvidence.Token> = AtomicReference(),
        )
        val rounds = 192
        val start = CountDownLatch(1)
        val ready = CyclicBarrier(2)
        val finished = CyclicBarrier(2)
        val current = AtomicReference<Attempt>()
        val failure = AtomicReference<Throwable>()
        val checked = AtomicInteger()
        fun recordFailure(error: Throwable) {
            failure.compareAndSet(null, error)
            ready.reset()
            finished.reset()
        }
        val observer = Thread({
            try {
                check(start.await(2L, TimeUnit.SECONDS))
                repeat(rounds) { round ->
                    val at = 10_000L + round * 10L
                    val evidence = LabyrinthNodeActionEvidence()
                    evidence.observe(at, true)
                    val attempt = Attempt(evidence, at)
                    current.set(attempt)
                    ready.await(2L, TimeUnit.SECONDS)
                    when (round % 3) {
                        0 -> evidence.observe(at + 1L, true)
                        1 -> evidence.invalidate()
                        else -> evidence.reset()
                    }
                    finished.await(2L, TimeUnit.SECONDS)
                    val token = attempt.captured.get()
                    // Check at age zero: a pass must come from revision/snapshot safety, never TTL.
                    check(token == null || !token.isCurrent(at)) {
                        "old capture borrowed a new revision in transition " + (round % 3) + ", round " + round
                    }
                    checked.incrementAndGet()
                }
            } catch (error: Throwable) { recordFailure(error) }
        }, "node-evidence-observer").apply { isDaemon = true }
        val capturer = Thread({
            try {
                check(start.await(2L, TimeUnit.SECONDS))
                repeat(rounds) {
                    ready.await(2L, TimeUnit.SECONDS)
                    val attempt = requireNotNull(current.get())
                    attempt.captured.set(attempt.evidence.capture(attempt.capturedAt))
                    finished.await(2L, TimeUnit.SECONDS)
                }
            } catch (error: Throwable) { recordFailure(error) }
        }, "node-evidence-capturer").apply { isDaemon = true }
        val workers = listOf(observer, capturer)
        try {
            workers.forEach(Thread::start)
            start.countDown()
            workers.forEach { it.join(5_000L) }
            assertTrue("concurrent evidence workers did not finish", workers.none { it.isAlive })
            failure.get()?.let { throw AssertionError("production evidence concurrency failed", it) }
            assertEquals(rounds, checked.get())
        } finally {
            start.countDown()
            workers.forEach(Thread::interrupt)
            workers.forEach { it.join(1_000L) }
            assertTrue("evidence workers leaked after cleanup", workers.none { it.isAlive })
        }
    }

    @Test(timeout = 15_000L) fun allFiveEvidenceOperationsOwnTheWholeInstanceMonitor() {
        for (name in listOf("observe", "isCurrentFrame", "invalidate", "reset", "capture")) {
            val method = LabyrinthNodeActionEvidence::class.java.declaredMethods.single { it.name == name }
            assertTrue(name + " must synchronize its entire snapshot operation", Modifier.isSynchronized(method.modifiers))
            assertFalse(name + " must lock the instance, not the class", Modifier.isStatic(method.modifiers))
            val evidence = LabyrinthNodeActionEvidence()
            evidence.observe(1_000L, true)
            val entering = CountDownLatch(1)
            val done = CountDownLatch(1)
            val failure = AtomicReference<Throwable>()
            val worker = Thread({
                try {
                    entering.countDown()
                    when (name) {
                        "observe" -> evidence.observe(1_001L, true)
                        "isCurrentFrame" -> evidence.isCurrentFrame(1_000L)
                        "invalidate" -> evidence.invalidate()
                        "reset" -> evidence.reset()
                        "capture" -> evidence.capture(1_000L)
                        else -> error("unknown evidence operation: " + name)
                    }
                } catch (error: Throwable) { failure.set(error) }
                finally { done.countDown() }
            }, "node-evidence-monitor-" + name).apply { isDaemon = true }
            try {
                synchronized(evidence) {
                    worker.start()
                    assertTrue(name + " worker never entered", entering.await(2L, TimeUnit.SECONDS))
                    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2L)
                    while (worker.state != Thread.State.BLOCKED && done.count != 0L && System.nanoTime() < deadline) {
                        Thread.sleep(1L)
                    }
                    assertEquals(name + " must wait for the exact owner monitor", Thread.State.BLOCKED, worker.state)
                    assertEquals(name + " ran while the owner monitor was held", 1L, done.count)
                }
                assertTrue(name + " did not finish after releasing the monitor", done.await(2L, TimeUnit.SECONDS))
                worker.join(2_000L)
                failure.get()?.let { throw AssertionError(name + " production operation failed", it) }
                assertFalse(name + " worker remained alive", worker.isAlive)
            } finally {
                worker.interrupt()
                worker.join(1_000L)
                assertFalse(name + " monitor worker leaked", worker.isAlive)
            }
        }
    }

    @Test(timeout = 5_000L) fun nativeTokenCheckDoesNotWaitForTheEvidenceOwnerMonitor() {
        val evidence = LabyrinthNodeActionEvidence()
        evidence.observe(1_000L, true)
        val token = requireNotNull(evidence.capture(1_000L))
        val method = LabyrinthNodeActionEvidence.Token::class.java.declaredMethods.single { it.name == "isCurrent" }
        assertFalse(Modifier.isSynchronized(method.modifiers))
        val started = CountDownLatch(1)
        val done = CountDownLatch(1)
        val result = AtomicReference<Boolean>()
        val failure = AtomicReference<Throwable>()
        val worker = Thread({
            try {
                started.countDown()
                result.set(token.isCurrent(1_000L))
            } catch (error: Throwable) { failure.set(error) }
            finally { done.countDown() }
        }, "node-native-token-check").apply { isDaemon = true }
        try {
            synchronized(evidence) {
                worker.start()
                assertTrue(started.await(1L, TimeUnit.SECONDS))
                assertTrue("native token waited for T2's evidence lock", done.await(1L, TimeUnit.SECONDS))
                failure.get()?.let { throw AssertionError("native token worker failed", it) }
                assertEquals(true, result.get())
            }
            worker.join(1_000L)
        } finally {
            worker.interrupt()
            worker.join(1_000L)
            assertFalse("native token worker leaked", worker.isAlive)
        }
    }

    @Test fun nativeTokenCarriesOnlyAnAtomicAndImmutableScalars() {
        val fields = LabyrinthNodeActionEvidence.Token::class.java.declaredFields
            .filterNot { Modifier.isStatic(it.modifiers) }
        assertEquals(3, fields.size)
        assertEquals(1, fields.count { it.type == AtomicLong::class.java })
        assertEquals(2, fields.count { it.type == java.lang.Long.TYPE })
        assertTrue(fields.all { Modifier.isFinal(it.modifiers) })
        assertTrue(fields.none { it.isSynthetic })
    }
}
