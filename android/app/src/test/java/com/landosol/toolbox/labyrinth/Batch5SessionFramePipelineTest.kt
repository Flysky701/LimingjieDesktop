package com.landosol.toolbox.labyrinth

import com.landosol.toolbox.automation.capture.RefCountedFrameLease
import com.landosol.toolbox.labyrinth.concurrent.LabyrinthFourThreadRuntime
import com.landosol.toolbox.labyrinth.vision.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

/** Actual production pipeline with one fake frame type; no Bitmap, Android Clock, Unsafe or extra pool. */
class Batch5SessionFramePipelineTest {
    private data class Frame(val id: Int, val timestamp: Long = id * 300L) {
        val disposed = AtomicBoolean()
        fun assertLive() { check(!disposed.get()) { "reading disposed frame $id" } }
    }
    private fun page() = LabyrinthEntryPageObservation(LabyrinthEntryPageState.UNKNOWN, 0.0, emptyMap(), LabyrinthAnchorScores(emptyMap()))
    private fun full() = LabyrinthEntryFrameResult(page(), emptyList(), 0, frameWidth = 1920, frameHeight = 1080)
    private fun snapshot() = LabyrinthBalancedVisionContext.RequestSnapshot(false, false, 0, emptySet(), false, true,
        null, emptySet(), false)
    private val control = LabyrinthFourThreadRuntime.controlDispatcher

    private class Fixture(
        val detail: (Frame) -> LabyrinthEntryFrameResult,
        val makeSnapshot: (Frame) -> LabyrinthBalancedVisionContext.RequestSnapshot,
        val onDeliver: suspend (RefCountedFrameLease<Frame>) -> Unit = {},
        val failFastId: Int? = null,
    ) {
        val scope = CoroutineScope(SupervisorJob() + LabyrinthFourThreadRuntime.controlDispatcher)
        val disposedIds = ConcurrentLinkedQueue<Int>()
        val observedIds = mutableListOf<Int>()
        val detailedIds = ConcurrentLinkedQueue<Int>()
        val deliveredIds = mutableListOf<Int>()
        val observationEvents = ConcurrentHashMap<Int, CompletableDeferred<Unit>>()
        val deliveryEvents = ConcurrentHashMap<Int, CompletableDeferred<Unit>>()
        val failureEvents = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
        val quiesced = CompletableDeferred<Unit>()
        val cpuBodies = AtomicInteger()
        val maxCpuBodies = AtomicInteger()
        var admitted = true
        lateinit var pipeline: LabyrinthBalancedFramePipeline<Frame>
            private set
        init { pipeline = LabyrinthBalancedFramePipeline(
            scope = scope,
            fastProcessor = { frame ->
                assertTrue(LabyrinthFourThreadRuntime.isControlThread()); frame.assertLive()
                if (frame.id == failFastId) error("fast")
                LabyrinthFastFrameObservation(LabyrinthEntryPageObservation(LabyrinthEntryPageState.UNKNOWN, 0.0,
                    emptyMap(), LabyrinthAnchorScores(emptyMap())), frame.id.toLong(), frame.timestamp, 1920, 1080, 1)
            },
            detailProcessor = { frame ->
                assertTrue(LabyrinthFourThreadRuntime.isComputeThread()); frame.assertLive()
                assertNotNull(LabyrinthBalancedVisionContext.current())
                val active = cpuBodies.incrementAndGet()
                maxCpuBodies.updateAndGet { previous -> maxOf(previous, active) }
                detailedIds += frame.id
                try { detail(frame) } finally { frame.assertLive(); cpuBodies.decrementAndGet() }
            },
            admitted = { admitted },
            snapshot = { frame -> assertTrue(LabyrinthFourThreadRuntime.isControlThread()); makeSnapshot(frame) },
            observe = { observed ->
                assertTrue(LabyrinthFourThreadRuntime.isControlThread())
                val id = observed.frameSequence.toInt()
                observedIds += id
                observationEvents.computeIfAbsent(id) { CompletableDeferred() }.complete(Unit)
            },
            deliver = { lease, _, result ->
                assertTrue(LabyrinthFourThreadRuntime.isControlThread()); lease.value.assertLive()
                assertEquals(77L, result.workerStartedMillis)
                val id = lease.value.id
                onDeliver(lease)
                deliveredIds += id
                deliveryEvents.computeIfAbsent(id) { CompletableDeferred() }.complete(Unit)
            },
            failure = { error ->
                assertTrue(LabyrinthFourThreadRuntime.isControlThread())
                failureEvents.computeIfAbsent(requireNotNull(error.message)) { CompletableDeferred() }.complete(Unit)
            },
            changed = { if (!admitted && !pipeline.detailActive && !pipeline.actorBusy) quiesced.complete(Unit) },
            intervalMillis = { 300L }, timestampMillis = { it.timestamp }, workerClock = { 77L },
            recycle = { frame -> assertTrue(frame.disposed.compareAndSet(false, true)); disposedIds += frame.id },
        ) }
        suspend fun offerObserved(id: Int): Frame {
            val signal = observationEvents.computeIfAbsent(id) { CompletableDeferred() }
            val frame = Frame(id)
            pipeline.offer(frame) // Fake T1 is the JUnit caller, not another business executor.
            withTimeout(4000) { signal.await() }
            return frame
        }
        suspend fun delivered(id: Int) = withTimeout(4000) {
            deliveryEvents.computeIfAbsent(id) { CompletableDeferred() }.await()
        }
        suspend fun failed(message: String) = withTimeout(4000) {
            failureEvents.computeIfAbsent(message) { CompletableDeferred() }.await()
        }
        suspend fun close() = withContext(LabyrinthFourThreadRuntime.controlDispatcher) {
            admitted = false; pipeline.close()
        }
    }

    @Test fun `actual fast observations continue while CPU detail is held and pending remains latest only`() = runBlocking {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val fixture = Fixture(detail = { frame ->
            if (frame.id == 1) { entered.countDown(); check(release.await(4, TimeUnit.SECONDS)) }
            full()
        }, makeSnapshot = { snapshot() })
        try {
            val active = fixture.offerObserved(1)
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            for (id in 2..8) fixture.offerObserved(id)
            withContext(control) {
                assertEquals((1..8).toList(), fixture.observedIds)
                assertTrue(fixture.pipeline.detailActive); assertTrue(fixture.pipeline.detailPending)
                assertEquals(listOf(1), fixture.detailedIds.toList())
                assertFalse(active.disposed.get())
            }
            release.countDown(); fixture.delivered(8)
            withContext(control) { assertEquals(listOf(1, 8), fixture.deliveredIds) }
            assertEquals(listOf(1, 8), fixture.detailedIds.toList())
            assertEquals(1, fixture.maxCpuBodies.get())
        } finally { release.countDown(); fixture.close(); withTimeout(4000) { fixture.quiesced.await() } }
        assertEquals((1..8).toSet(), fixture.disposedIds.toSet())
        assertEquals(8, fixture.disposedIds.size)
    }

    @Test fun `running cancellation never disposes active reader before actual quiescence`() = runBlocking {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val fixture = Fixture(detail = { entered.countDown(); check(release.await(4, TimeUnit.SECONDS)); full() }, makeSnapshot = { snapshot() })
        try {
            val active = fixture.offerObserved(1)
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            fixture.offerObserved(2)
            withContext(control) {
                fixture.admitted = false
                fixture.pipeline.clearPending()
                assertTrue(fixture.pipeline.detailActive)
                assertFalse(active.disposed.get())
            }
            assertFalse(active.disposed.get())
            release.countDown(); withTimeout(4000) { fixture.quiesced.await() }
            assertTrue(active.disposed.get())
            withContext(control) { assertTrue(fixture.deliveredIds.isEmpty()) }
        } finally { release.countDown(); fixture.close(); withTimeout(4000) { fixture.quiesced.await() } }
        assertEquals(setOf(1, 2), fixture.disposedIds.toSet())
        assertEquals(2, fixture.disposedIds.size)
    }

    @Test fun `suspended detail result actor does not block fast observation or reenter transaction`() = runBlocking {
        val actorEntered = CompletableDeferred<Unit>(); val actorRelease = CompletableDeferred<Unit>()
        var actorCount = 0; var maxActors = 0
        val fixture = Fixture(detail = { full() }, makeSnapshot = { snapshot() }, onDeliver = { lease ->
            actorCount++; maxActors = maxOf(maxActors, actorCount)
            try {
                if (lease.value.id == 1) { actorEntered.complete(Unit); actorRelease.await(); lease.value.assertLive() }
            } finally { actorCount-- }
        })
        try {
            val actorFrame = fixture.offerObserved(1)
            withTimeout(4000) { actorEntered.await() }
            for (id in 2..6) fixture.offerObserved(id)
            withContext(control) {
                assertEquals((1..6).toList(), fixture.observedIds)
                assertTrue(fixture.pipeline.actorBusy); assertFalse(fixture.pipeline.detailActive)
                assertTrue(fixture.pipeline.detailPending)
                assertEquals(listOf(1), fixture.detailedIds.toList())
                assertFalse(actorFrame.disposed.get())
            }
            actorRelease.complete(Unit); fixture.delivered(6)
            withContext(control) { assertEquals(listOf(1, 6), fixture.deliveredIds); assertEquals(1, maxActors) }
            assertEquals(listOf(1, 6), fixture.detailedIds.toList())
            assertEquals(1, fixture.maxCpuBodies.get())
        } finally { actorRelease.complete(Unit); fixture.close(); withTimeout(4000) { fixture.quiesced.await() } }
        assertEquals((1..6).toSet(), fixture.disposedIds.toSet())
        assertEquals(6, fixture.disposedIds.size)
    }

    @Test fun `snapshot CPU fast and delivery failures close handles and leave no occupied flight`() = runBlocking {
        val fixture = Fixture(
            detail = { frame -> if (frame.id == 2) error("cpu"); full() },
            makeSnapshot = { frame -> if (frame.id == 1) error("snapshot"); snapshot() },
            onDeliver = { lease -> if (lease.value.id == 4) error("delivery") }, failFastId = 3,
        )
        try {
            fixture.offerObserved(1); fixture.failed("snapshot")
            fixture.offerObserved(2); fixture.failed("cpu")
            fixture.pipeline.offer(Frame(3)); fixture.failed("fast")
            fixture.offerObserved(4); fixture.failed("delivery")
            fixture.offerObserved(5); fixture.delivered(5)
            withContext(control) {
                assertEquals(listOf(5), fixture.deliveredIds)
                assertFalse(fixture.pipeline.detailActive); assertFalse(fixture.pipeline.actorBusy)
            }
        } finally { fixture.close(); withTimeout(4000) { fixture.quiesced.await() } }
        assertEquals((1..5).toSet(), fixture.disposedIds.toSet())
        assertEquals(5, fixture.disposedIds.size)
    }
}
