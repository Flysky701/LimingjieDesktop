package com.landosol.toolbox.labyrinth.concurrent

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.Callable
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

/** Java17 JVM tests. Synchronization uses events/barriers, never sleep polling or java.management. */
class Batch5RuntimeTest {
    private fun CountDownLatch.awaitChecked() = assertTrue(await(10, TimeUnit.SECONDS))
    private fun <T> LabyrinthHelpingExecutor.root(
        kind: WorkKind = WorkKind.PLANNING,
        token: WorkToken = WorkToken(),
        quiesced: () -> Unit = {},
        body: () -> T,
    ): WorkHandle<T> = submitRoot(kind, token, {}, quiesced, body)

    private fun withPool(body: (LabyrinthHelpingExecutor) -> Unit) {
        val pool = LabyrinthHelpingExecutor(threadPrefix = "batch5-test-compute")
        try { body(pool) } finally {
            pool.shutdownNow()
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS))
        }
    }

    @Test fun twoSimultaneousCpuParentsInvokeAllAndGetWithoutCoordinator() = withPool { pool ->
        val barrier = CyclicBarrier(2)
        val names = ConcurrentHashMap.newKeySet<String>()
        val parents = (0..1).map { parent -> pool.root {
            names.add(Thread.currentThread().name)
            barrier.await(10, TimeUnit.SECONDS)
            pool.invokeAll((0..3).map { shard -> Callable {
                names.add(Thread.currentThread().name)
                parent * 100 + shard
            } }.toMutableList()).map { it.get() }
        } }
        assertEquals(listOf(0, 1, 2, 3), parents[0].future.get(10, TimeUnit.SECONDS))
        assertEquals(listOf(100, 101, 102, 103), parents[1].future.get(10, TimeUnit.SECONDS))
        assertEquals(2, names.size)
    }

    @Test fun nestedHelpingAndTimedGetInheritRootToken() = withPool { pool ->
        val barrier = CyclicBarrier(2)
        val tokens = (0..1).map { WorkToken() }
        val parents = tokens.map { token -> pool.root(token = token) {
            barrier.await(10, TimeUnit.SECONDS)
            pool.submit(Callable {
                assertSame(token, LabyrinthHelpingExecutor.currentToken())
                pool.submit(Callable {
                    assertSame(token, LabyrinthHelpingExecutor.currentToken())
                    42
                }).get(5, TimeUnit.SECONDS)
            }).get(5, TimeUnit.SECONDS)
        } }
        parents.forEach { assertEquals(42, it.future.get(10, TimeUnit.SECONDS)) }
        assertNull(LabyrinthHelpingExecutor.currentToken())
    }

    @Test fun timedGetAndTimedInvokeAllReturnTimeoutWithoutWaitingOnCpuParent() = withPool { pool ->
        val running = CountDownLatch(1)
        val release = CountDownLatch(1)
        val other = pool.root { running.countDown(); release.awaitChecked(); 1 }
        try {
            running.awaitChecked()
            val timed = pool.root {
                try { other.future.get(1, TimeUnit.MILLISECONDS); fail("expected timeout") }
                catch (_: TimeoutException) { }
                val never = pool.invokeAll(listOf(Callable { fail("zero deadline body must not run"); 0 }).toMutableList(), 0, TimeUnit.NANOSECONDS)
                assertTrue(never.single().isCancelled)
                true
            }
            assertTrue(timed.future.get(10, TimeUnit.SECONDS))
        } finally { release.countDown() }
    }

    @Test fun runningCancellationDonePrecedesQuiescenceAndDoesNotInterruptWorker() = withPool { pool ->
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val exited = CountDownLatch(1)
        val quiet = CountDownLatch(1)
        val root = pool.root(quiesced = { quiet.countDown() }) {
            try {
                entered.countDown()
                release.awaitChecked()
                assertFalse(Thread.currentThread().isInterrupted)
                LabyrinthHelpingExecutor.checkpoint()
                fail("cancelled checkpoint must throw")
            } finally { exited.countDown() }
        }
        try {
            entered.awaitChecked()
            root.cancel(true)
            assertTrue(root.isDone)
            assertEquals(1L, quiet.count)
            assertEquals(1L, exited.count)
        } finally { release.countDown() }
        quiet.awaitChecked()
        assertEquals(0L, exited.count)
        val late = AtomicInteger()
        root.onQuiesced { late.incrementAndGet() }
        assertEquals(1, late.get())
    }

    @Test fun quiescenceIncludesCancelledChildStillUsingRootResources() = withPool { pool ->
        val childEntered = CountDownLatch(1)
        val childRelease = CountDownLatch(1)
        val parentReturned = CountDownLatch(1)
        val quiet = CountDownLatch(1)
        val token = WorkToken()
        val root = pool.root(token = token, quiesced = { quiet.countDown() }) {
            val child = pool.submit(Callable {
                assertSame(token, LabyrinthHelpingExecutor.currentToken())
                childEntered.countDown()
                childRelease.awaitChecked()
                LabyrinthHelpingExecutor.checkpoint()
                5
            })
            childEntered.awaitChecked() // The second worker owns the child body.
            child.cancel(false)
            parentReturned.countDown()
            7
        }
        try {
            parentReturned.awaitChecked()
            assertEquals(7, root.future.get(10, TimeUnit.SECONDS))
            assertEquals(1L, quiet.count)
            root.cancel(false) // Also cancel descendants even though the root result is complete.
            assertTrue(token.isCancelled)
        } finally { childRelease.countDown() }
        quiet.awaitChecked()
    }

    @Test fun helpingAnotherRootRestoresTokenEvenAfterCancellationAndFailure() = withPool { pool ->
        val blockerEntered = CountDownLatch(1)
        val blockerRelease = CountDownLatch(1)
        val parentEntered = CountDownLatch(1)
        val cooperateNow = CountDownLatch(1)
        val parentToken = WorkToken()
        val detailToken = WorkToken()
        val blocker = pool.root { blockerEntered.countDown(); blockerRelease.awaitChecked() }
        val parent = pool.root(WorkKind.DIAGNOSTIC, parentToken) {
            parentEntered.countDown()
            cooperateNow.awaitChecked()
            assertSame(parentToken, LabyrinthHelpingExecutor.currentToken())
            LabyrinthHelpingExecutor.cooperate()
            assertSame(parentToken, LabyrinthHelpingExecutor.currentToken())
            assertFalse(parentToken.isCancelled)
            9
        }
        try {
            blockerEntered.awaitChecked(); parentEntered.awaitChecked()
            val detail = pool.root(WorkKind.DETAILED_VISION, detailToken) {
                assertSame(detailToken, LabyrinthHelpingExecutor.currentToken())
                detailToken.cancel()
                LabyrinthHelpingExecutor.checkpoint()
            }
            cooperateNow.countDown()
            assertEquals(9, parent.future.get(10, TimeUnit.SECONDS))
            assertTrue(detail.future.isCancelled)
            assertFalse(blocker.future.isCancelled)
        } finally { cooperateNow.countDown(); blockerRelease.countDown() }
    }

    @Test fun rootCancellationInvalidatesQueuedChildrenAndFreshRootsHaveFreshThreadLocals() = withPool { pool ->
        val blockerEntered = CountDownLatch(1)
        val blockerRelease = CountDownLatch(1)
        val childrenQueued = CountDownLatch(1)
        val parentRelease = CountDownLatch(1)
        val quiet = CountDownLatch(1)
        val ran = AtomicInteger()
        val token = WorkToken()
        pool.root { blockerEntered.countDown(); blockerRelease.awaitChecked() }
        blockerEntered.awaitChecked()
        val root = pool.root(token = token, quiesced = { quiet.countDown() }) {
            pool.submit(Callable { ran.incrementAndGet(); assertSame(token, LabyrinthHelpingExecutor.currentToken()) })
            pool.submit(Callable { ran.incrementAndGet(); assertSame(token, LabyrinthHelpingExecutor.currentToken()) })
            childrenQueued.countDown()
            parentRelease.awaitChecked()
            LabyrinthHelpingExecutor.checkpoint()
        }
        try {
            childrenQueued.awaitChecked()
            assertEquals(2, pool.queuedTaskCount)
            token.cancel()
            assertTrue(root.isDone)
            assertEquals(0, pool.queuedTaskCount)
            assertEquals(1L, quiet.count)
            parentRelease.countDown()
            quiet.awaitChecked()
            val freshToken = WorkToken()
            val fresh = pool.root(token = freshToken) {
                assertSame(freshToken, LabyrinthHelpingExecutor.currentToken())
                LabyrinthHelpingExecutor.checkpoint()
                1
            }
            assertEquals(1, fresh.future.get(10, TimeUnit.SECONDS))
            assertEquals(0, ran.get())
        } finally { parentRelease.countDown(); blockerRelease.countDown() }
    }

    @Test fun t2RemainsResponsiveWithBothCpuBodiesHeldAndAwaitDoesNotBlockIt() = runBlocking {
        val entered = CountDownLatch(2)
        val release = CountDownLatch(1)
        val names = ConcurrentHashMap.newKeySet<String>()
        val computeThreadIds = ConcurrentHashMap.newKeySet<Long>()
        val jobs = (0..1).map { LabyrinthFourThreadRuntime.submitWork(WorkKind.PLANNING) {
            names.add(Thread.currentThread().name)
            computeThreadIds.add(Thread.currentThread().id)
            entered.countDown()
            release.awaitChecked()
            12
        } }
        try {
            entered.awaitChecked()
            val startedAwait = CompletableDeferred<Long>()
            val response = CompletableDeferred<Pair<String, Long>>()
            val waiter = launch(LabyrinthFourThreadRuntime.controlDispatcher) {
                assertTrue(LabyrinthFourThreadRuntime.isControlThread())
                assertFalse(LabyrinthFourThreadRuntime.isComputeThread())
                startedAwait.complete(Thread.currentThread().id)
                assertEquals(12, jobs.first().await())
            }
            val waiterThreadId = withTimeout(10_000) { startedAwait.await() }
            val ping = launch(LabyrinthFourThreadRuntime.controlDispatcher) {
                assertTrue(LabyrinthFourThreadRuntime.isControlThread())
                assertFalse(LabyrinthFourThreadRuntime.isComputeThread())
                try { jobs.first().future.get(); fail("T2 must reject blocking get") }
                catch (_: IllegalStateException) { }
                response.complete(Thread.currentThread().name to Thread.currentThread().id)
            }
            val (controlName, controlThreadId) = withTimeout(10_000) { response.await() }
            // Coroutine debug mode decorates the same physical thread with a coroutine suffix.
            val controlBaseName = controlName.substringBefore(" @coroutine")
            assertEquals("labyrinth-b5-control", controlBaseName)
            assertEquals(waiterThreadId, controlThreadId)
            assertEquals(2, names.size)
            assertEquals(2, computeThreadIds.size)
            assertFalse(computeThreadIds.contains(controlThreadId))
            assertTrue(names.all { it.substringBefore(" @coroutine") != controlBaseName })
            release.countDown()
            withTimeout(10_000) { waiter.join(); ping.join(); jobs.forEach { assertEquals(12, it.await()) } }
        } finally { release.countDown(); jobs.forEach { it.cancel(false) } }
    }

    @Test fun suspendAwaitCancellationCancelsTokenButWaitsForBodyExitBeforeRelease() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val quiet = CountDownLatch(1)
        val handle = LabyrinthFourThreadRuntime.submitWork(WorkKind.OBSERVATION, onQuiesced = { quiet.countDown() }) {
            entered.countDown(); release.awaitChecked(); LabyrinthFourThreadRuntime.checkpoint(); 4
        }
        try {
            entered.awaitChecked()
            val started = CompletableDeferred<Unit>()
            val waiter = launch(LabyrinthFourThreadRuntime.controlDispatcher) { started.complete(Unit); handle.await() }
            withTimeout(10_000) { started.await() }
            waiter.cancel()
            withTimeout(10_000) { waiter.join() }
            assertTrue(handle.token.isCancelled)
            assertEquals(1L, quiet.count)
        } finally { release.countDown(); handle.cancel(false) }
        quiet.awaitChecked()
    }
}
