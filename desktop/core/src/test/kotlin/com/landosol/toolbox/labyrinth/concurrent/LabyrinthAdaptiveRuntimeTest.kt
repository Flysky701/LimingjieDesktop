package com.landosol.toolbox.labyrinth.concurrent

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

/** Event-driven checks for both production worker budgets; no device, sleeps, or timing benchmark. */
class LabyrinthAdaptiveRuntimeTest {
    private fun CountDownLatch.awaitChecked() = assertTrue(await(10, TimeUnit.SECONDS))
    private fun withPool(count: Int, capacity: Int = 64, body: (LabyrinthHelpingExecutor) -> Unit) {
        val pool = LabyrinthHelpingExecutor(capacity, "adaptive-test-$count", workerCount = count)
        try { body(pool) } finally {
            pool.shutdownNow()
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS))
        }
    }

    @Test fun startupRuleOnlySelectsTwoOrFourAndKeepsTheFixedLanes() {
        for (processors in listOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 12, 64)) {
            val budget = LabyrinthThreadBudget.forProcessors(processors)
            val expected = if (processors >= 6) 4 else 2
            assertEquals(processors, budget.availableProcessors)
            assertEquals(expected, budget.cpuWorkerBudget)
            assertEquals(1, budget.captureThreadBudget)
            assertEquals(1, budget.controlThreadBudget)
            assertEquals(2 + expected, budget.businessThreadBudget)
        }
        assertSame(LabyrinthThreadBudget.process, LabyrinthThreadBudget.process)
        assertEquals(LabyrinthThreadBudget.process.availableProcessors, LabyrinthFourThreadRuntime.availableProcessors)
        assertEquals(LabyrinthThreadBudget.process.cpuWorkerBudget, LabyrinthFourThreadRuntime.cpuWorkerBudget)
        assertEquals(2 + LabyrinthFourThreadRuntime.cpuWorkerBudget, LabyrinthFourThreadRuntime.businessThreadBudget)
    }

    @Test fun legacyConstructorStillOwnsTwoWorkersAndInvalidCountStartsNone() {
        withPool(2) { assertEquals(2, it.workerCount) }
        val legacy = LabyrinthHelpingExecutor(64, "adaptive-legacy")
        try { assertEquals(2, legacy.workerCount) } finally {
            legacy.shutdownNow(); assertTrue(legacy.awaitTermination(10, TimeUnit.SECONDS))
        }
        try { LabyrinthHelpingExecutor(workerCount = 0); fail("invalid worker count") }
        catch (_: IllegalArgumentException) { }
    }

    @Test fun allCpuParentsHelpTheirChildrenWithoutExtraWorkersAndRestoreTheirTokens() {
        for (count in listOf(2, 4)) withPool(count) { pool ->
            val barrier = CyclicBarrier(count)
            val names = ConcurrentHashMap.newKeySet<String>()
            val handles = (0 until count).map { parent ->
                val token = WorkToken()
                pool.submitRoot(WorkKind.PLANNING, token, {}, {}) {
                    names.add(Thread.currentThread().name)
                    barrier.await(10, TimeUnit.SECONDS)
                    val result = pool.invokeAll((0 until 4).map { shard -> Callable {
                        names.add(Thread.currentThread().name)
                        assertSame(token, LabyrinthHelpingExecutor.currentToken())
                        pool.submit(Callable {
                            assertSame(token, LabyrinthHelpingExecutor.currentToken())
                            parent * 100 + shard
                        }).get(5, TimeUnit.SECONDS)
                    } }.toMutableList()).map { it.get() }
                    assertSame(token, LabyrinthHelpingExecutor.currentToken())
                    result
                }
            }
            handles.forEachIndexed { parent, handle ->
                assertEquals((0 until 4).map { parent * 100 + it }, handle.future.get(10, TimeUnit.SECONDS))
            }
            assertEquals(count, names.size)
            assertTrue(names.all { it.startsWith("adaptive-test-$count-") })
            assertNull(LabyrinthHelpingExecutor.currentToken())
        }
    }

    @Test fun shutdownCannotReportDoneAfterOnlyTwoOfFourWorkersExit() {
        for (count in listOf(2, 4)) withPool(count) { pool ->
            val entered = CountDownLatch(count)
            val releases = (0 until count).map { CountDownLatch(1) }
            val bodies = ConcurrentHashMap<Int, Thread>()
            val futures = (0 until count).map { index -> pool.submit(Callable {
                bodies[index] = Thread.currentThread(); entered.countDown()
                releases[index].awaitChecked(); index
            }) }
            try {
                entered.awaitChecked(); pool.shutdown()
                for (index in 0 until count / 2) releases[index].countDown()
                for (index in 0 until count / 2) {
                    assertEquals(index, futures[index].get(10, TimeUnit.SECONDS))
                    val worker = checkNotNull(bodies[index]); worker.join(10_000)
                    assertFalse(worker.isAlive)
                }
                assertFalse(pool.isTerminated)
                assertFalse(pool.awaitTermination(1, TimeUnit.MILLISECONDS))
                releases.forEach { it.countDown() }
                assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS))
                assertTrue(pool.isTerminated)
            } finally { releases.forEach { it.countDown() } }
        }
    }

    @Test fun boundedQueueRejectsWithoutCallerRunsAndQueuedCancelQuiescesExactlyOnce() {
        for (count in listOf(2, 4)) withPool(count, capacity = 1) { pool ->
            val entered = CountDownLatch(count)
            val release = CountDownLatch(1)
            val ran = AtomicInteger()
            val quiet = AtomicInteger()
            // A capacity-one queue requires each submitted body to enter before the next submission.
            try {
                repeat(count) {
                    val oneEntered = CountDownLatch(1)
                    pool.submit(Callable { entered.countDown(); oneEntered.countDown(); release.awaitChecked() })
                    oneEntered.awaitChecked()
                }
                entered.awaitChecked()
                val queued = pool.submitRoot(WorkKind.PLANNING, WorkToken(), {}, { quiet.incrementAndGet() }) { ran.incrementAndGet() }
                assertEquals(1, pool.queuedTaskCount)
                val rejectedQuiet = AtomicInteger()
                try {
                    pool.submitRoot(WorkKind.PLANNING, WorkToken(), {}, { rejectedQuiet.incrementAndGet() }) { ran.incrementAndGet() }
                    fail("full queue must reject")
                } catch (_: RejectedExecutionException) { }
                assertEquals(1, rejectedQuiet.get())
                queued.cancel(false); queued.cancel(false)
                assertEquals(1, quiet.get())
                assertEquals(0, pool.queuedTaskCount)
                assertEquals(0, ran.get())
            } finally { release.countDown() }
        }
    }

    @Test fun cancelledRunningRootWaitsForActualExitBeforeResourceRelease() {
        for (count in listOf(2, 4)) withPool(count) { pool ->
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val quiet = CountDownLatch(1)
            val callbackCount = AtomicInteger()
            val handle = pool.submitRoot(WorkKind.OBSERVATION, WorkToken(), {}, { callbackCount.incrementAndGet(); quiet.countDown() }) {
                entered.countDown(); release.awaitChecked()
                assertFalse(Thread.currentThread().isInterrupted)
                LabyrinthHelpingExecutor.checkpoint()
            }
            try {
                entered.awaitChecked(); handle.cancel(true)
                assertTrue(handle.isDone)
                assertEquals(1L, quiet.count)
            } finally { release.countDown() }
            quiet.awaitChecked(); assertEquals(1, callbackCount.get())
        }
    }

    @Test fun rootResourceLifetimeIncludesCancelledRunningChildInBothBudgets() {
        for (count in listOf(2, 4)) withPool(count) { pool ->
            val childEntered = CountDownLatch(1)
            val childRelease = CountDownLatch(1)
            val quiet = CountDownLatch(1)
            val token = WorkToken()
            val handle = pool.submitRoot(WorkKind.PLANNING, token, {}, { quiet.countDown() }) {
                val child = pool.submit(Callable {
                    assertSame(token, LabyrinthHelpingExecutor.currentToken())
                    childEntered.countDown(); childRelease.awaitChecked()
                    LabyrinthHelpingExecutor.checkpoint(); 7
                })
                childEntered.awaitChecked(); child.cancel(false); 42
            }
            try {
                assertEquals(42, handle.future.get(10, TimeUnit.SECONDS))
                assertEquals(1L, quiet.count)
                handle.cancel(false); assertTrue(token.isCancelled)
            } finally { childRelease.countDown() }
            quiet.awaitChecked()
        }
    }

    @Test fun controlRemainsResponsiveWithEverySelectedWorkerBusy() = runBlocking {
        val count = LabyrinthFourThreadRuntime.cpuWorkerBudget
        val entered = CountDownLatch(count)
        val release = CountDownLatch(1)
        val names = ConcurrentHashMap.newKeySet<String>()
        val jobs = (0 until count).map { LabyrinthFourThreadRuntime.submitWork(WorkKind.PLANNING) {
            names.add(Thread.currentThread().name); entered.countDown(); release.awaitChecked(); 12
        } }
        try {
            entered.awaitChecked()
            val waiting = CompletableDeferred<Unit>()
            val pong = CompletableDeferred<Unit>()
            val waiter = launch(LabyrinthFourThreadRuntime.controlDispatcher) {
                waiting.complete(Unit); assertEquals(12, jobs.first().await())
            }
            withTimeout(10_000) { waiting.await() }
            val ping = launch(LabyrinthFourThreadRuntime.controlDispatcher) {
                assertTrue(LabyrinthFourThreadRuntime.isControlThread())
                try { jobs.first().future.get(); fail("control must not block") }
                catch (_: IllegalStateException) { }
                pong.complete(Unit)
            }
            withTimeout(10_000) { pong.await() }
            assertEquals(count, names.size)
            release.countDown()
            withTimeout(10_000) { waiter.join(); ping.join(); jobs.forEach { assertEquals(12, it.await()) } }
        } finally { release.countDown(); jobs.forEach { it.cancel(false) } }
    }
}
