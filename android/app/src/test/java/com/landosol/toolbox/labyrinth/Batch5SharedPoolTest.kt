package com.landosol.toolbox.labyrinth

import com.landosol.toolbox.labyrinth.concurrent.LabyrinthFourThreadRuntime
import com.landosol.toolbox.labyrinth.concurrent.LabyrinthHelpingExecutor
import com.landosol.toolbox.labyrinth.concurrent.WorkKind
import com.landosol.toolbox.labyrinth.concurrent.WorkToken
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class Batch5SharedPoolTest {
    private fun CountDownLatch.awaitChecked() = assertTrue(await(10, TimeUnit.SECONDS))

    @Test fun roleAndIconExecutorsAreIdentityAliasesNotIndependentPools() {
        fun executorField(owner: String, field: String): Any {
            val holder = Class.forName(owner)
            return holder.getDeclaredField(field).also { it.isAccessible = true }.get(null)
        }
        val shared = LabyrinthFourThreadRuntime.computeExecutor
        assertSame(shared, executorField("com.landosol.toolbox.labyrinth.LabyrinthRoleDecisionPolicyKt", "ROLE_SEARCH_EXECUTOR"))
        assertSame(shared, executorField("com.landosol.toolbox.labyrinth.vision.LabyrinthCharacterIconMatcherKt", "ICON_SCORING_EXECUTOR"))
    }

    @Test fun boundedQueueRejectsExplicitlyAndQueuedCancellationQuiescesImmediately() {
        val pool = LabyrinthHelpingExecutor(queueCapacity = 2, threadPrefix = "batch5-bounded")
        val entered = CountDownLatch(2)
        val release = CountDownLatch(1)
        try {
            val blockers = (0..1).map { pool.submit(Callable { entered.countDown(); release.awaitChecked() }) }
            entered.awaitChecked()
            val ran = AtomicInteger()
            val quiet = AtomicInteger()
            val queued = pool.submitRoot(WorkKind.PLANNING, WorkToken(), {}, { quiet.incrementAndGet() }) { ran.incrementAndGet() }
            val second = pool.submit(Callable { 2 })
            assertEquals(2, pool.queuedTaskCount)
            val rejectedResult = AtomicInteger()
            val rejectedQuiet = AtomicInteger()
            try {
                pool.submitRoot(WorkKind.DETAILED_VISION, WorkToken(), {
                    assertTrue(it.isFailure); rejectedResult.incrementAndGet()
                }, { rejectedQuiet.incrementAndGet() }) { ran.incrementAndGet() }
                fail("full queue must throw; T2 must not CallerRuns")
            } catch (_: RejectedExecutionException) { }
            assertEquals(1, rejectedResult.get())
            assertEquals(1, rejectedQuiet.get())
            assertEquals(0, ran.get())
            queued.cancel(false)
            assertTrue(queued.future.isCancelled)
            assertEquals(1, quiet.get())
            assertEquals(1, pool.queuedTaskCount)
            release.countDown()
            assertEquals(2, second.get(10, TimeUnit.SECONDS))
            blockers.forEach { it.get(10, TimeUnit.SECONDS) }
            assertEquals(0, ran.get())
        } finally { release.countDown(); pool.shutdownNow(); assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS)) }
    }

    @Test fun priorityIsDetailedObservationThenPlanningThenDiagnosticWithFifoWithinTier() {
        val pool = LabyrinthHelpingExecutor(threadPrefix = "batch5-priority")
        val entered = CountDownLatch(2)
        val releaseFirst = CountDownLatch(1)
        val releaseSecond = CountDownLatch(1)
        val order = java.util.Collections.synchronizedList(ArrayList<WorkKind>())
        try {
            pool.submit(Callable { entered.countDown(); releaseFirst.awaitChecked() })
            pool.submit(Callable { entered.countDown(); releaseSecond.awaitChecked() })
            entered.awaitChecked()
            val kinds = listOf(WorkKind.DIAGNOSTIC, WorkKind.PLANNING, WorkKind.DETAILED_VISION, WorkKind.OBSERVATION)
            val handles = kinds.map { kind -> pool.submitRoot(kind, WorkToken(), {}, {}) { order.add(kind) } }
            releaseFirst.countDown() // Only one worker can drain the queue, making order deterministic.
            handles.forEach { it.future.get(10, TimeUnit.SECONDS) }
            assertEquals(listOf(WorkKind.DETAILED_VISION, WorkKind.OBSERVATION, WorkKind.PLANNING, WorkKind.DIAGNOSTIC), order)
        } finally {
            releaseFirst.countDown(); releaseSecond.countDown(); pool.shutdownNow()
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS))
        }
    }

    @Test fun rejectedInvokeAllCancelsAlreadyQueuedSiblingsRatherThanDroppingRequiredWork() {
        val pool = LabyrinthHelpingExecutor(queueCapacity = 2, threadPrefix = "batch5-reject-children")
        val entered = CountDownLatch(2)
        val release = CountDownLatch(1)
        val ran = AtomicInteger()
        try {
            repeat(2) { pool.submit(Callable { entered.countDown(); release.awaitChecked() }) }
            entered.awaitChecked()
            try {
                pool.invokeAll((0..2).map { Callable { ran.incrementAndGet() } }.toMutableList())
                fail("three tasks cannot fit the bounded queue")
            } catch (_: RejectedExecutionException) { }
            assertEquals(0, pool.queuedTaskCount)
            assertEquals(0, ran.get())
        } finally { release.countDown(); pool.shutdownNow(); assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS)) }
    }

    @Test fun everyPublicRoleSearchMatchesSerialFullOutputsInsideTwoCpuParentsIncludingForcedFourShards() {
        for (tied in listOf(false, true)) {
            val input = batch3SyntheticInput(12, tied = tied)
            val roster = input.acquiredIds.map(input.profiles::getValue)
            val cases = listOf(roster to input.context, roster.reversed() to input.context.copy(targetCount = 3, defenseMarkStacks = 2))
            val serial = Batch4OptimizerPair(searchThreads = 1)
            val expected = cases.map { (roles, context) -> serial.snapshotOptimized(roles, context) }
            val barrier = CyclicBarrier(2)
            val handles = cases.mapIndexed { index, (roles, context) ->
                LabyrinthFourThreadRuntime.submitWork(WorkKind.PLANNING) {
                    barrier.await(10, TimeUnit.SECONDS)
                    Batch4OptimizerPair(searchThreads = if (index == 0) 2 else 4).snapshotOptimized(roles, context)
                }
            }
            try {
                handles.forEachIndexed { index, handle ->
                    batch3AssertExact("batch5-two-parents-tied=$tied-$index", expected[index], handle.future.get(30, TimeUnit.SECONDS))
                }
            } finally { handles.forEach { it.cancel(false) } }
        }
    }
}
