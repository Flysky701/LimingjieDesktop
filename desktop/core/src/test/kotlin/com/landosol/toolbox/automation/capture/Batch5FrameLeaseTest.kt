package com.landosol.toolbox.automation.capture

import java.util.concurrent.CountDownLatch
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

/** Pure JVM ownership tests: no Android Bitmap or Future completion assumptions. */
class Batch5FrameLeaseTest {
    @Test fun retainReturnsIndependentHandlesAndLastCloseDisposesOnce() {
        val disposed = AtomicInteger()
        val payload = IntArray(8) { it }
        val owner = RefCountedFrameLease.create(payload) { assertSame(payload, it); disposed.incrementAndGet() }
        val reader = owner.retain()
        assertNotSame(owner, reader)
        owner.close()
        owner.close()
        assertEquals(0, disposed.get())
        assertSame(payload, reader.value)
        assertTrue(owner.isClosed)
        expectClosed { owner.value }
        expectClosed { owner.retain() }
        reader.close()
        reader.close()
        assertEquals(1, disposed.get())
        expectClosed { reader.value }
    }

    @Test fun replacingQueuedHandleKeepsOldRunningReadersAlive() {
        val disposed = ConcurrentLinkedQueue<String>()
        val oldOwner = RefCountedFrameLease.create("old") { disposed.add(it) }
        val running = oldOwner.retain()
        var pending = oldOwner.retain()
        oldOwner.close()
        val newOwner = RefCountedFrameLease.create("new") { disposed.add(it) }
        val replacement = newOwner.retain()
        pending.close()
        pending = replacement
        newOwner.close()
        assertEquals("old", running.value)
        assertEquals("new", pending.value)
        assertTrue(disposed.isEmpty())
        // Cancellation request / Future.done does not close this actually-running reader.
        val cancelledButRunning = running.retain()
        running.close()
        assertEquals("old", cancelledButRunning.value)
        assertTrue(disposed.isEmpty())
        cancelledButRunning.close() // runtime onQuiesced, not cancellation request
        assertEquals(listOf("old"), disposed.toList())
        pending.close()
        assertEquals(listOf("old", "new"), disposed.toList())
    }

    @Test fun exceptionalReaderFinallyAndThrowingDisposerStaySingleRelease() {
        val disposed = AtomicInteger()
        val owner = RefCountedFrameLease.create("value") { disposed.incrementAndGet(); error("dispose failed") }
        val reader = owner.retain()
        owner.close()
        try {
            try {
                assertEquals("value", reader.value)
                error("reader failed")
            } finally {
                reader.close()
            }
            fail("exception expected")
        } catch (_: IllegalStateException) { }
        assertEquals(1, disposed.get())
        reader.close()
        expectClosed { reader.value }
    }

    @Test fun concurrentRetainsAndIdempotentClosesDisposeExactlyOnce() {
        val disposed = AtomicInteger()
        val owner = RefCountedFrameLease.create(IntArray(4) { 7 }) { disposed.incrementAndGet() }
        val gate = CountDownLatch(1)
        val errors = ConcurrentLinkedQueue<Throwable>()
        val workers = List(8) {
            Thread {
                try {
                    gate.await()
                    repeat(200) {
                        val first = owner.retain()
                        val second = first.retain()
                        try { assertEquals(7, second.value[0]) } finally {
                            first.close(); first.close(); second.close(); second.close()
                        }
                    }
                } catch (error: Throwable) { errors.add(error) }
            }.also { it.start() }
        }
        gate.countDown()
        workers.forEach { it.join(10_000); assertFalse("worker failed to quiesce", it.isAlive) }
        assertTrue("errors=$errors", errors.isEmpty())
        assertEquals(0, disposed.get())
        owner.close()
        assertEquals(1, disposed.get())
    }

    @Test fun retainRacingOwnerCloseIsLinearizableAndCannotResurrectReleasedValue() {
        repeat(100) {
            val disposed = AtomicInteger()
            val root = RefCountedFrameLease.create("frame") { disposed.incrementAndGet() }
            val gate = CountDownLatch(1)
            val errors = ConcurrentLinkedQueue<Throwable>()
            val retained = ConcurrentLinkedQueue<RefCountedFrameLease<String>>()
            val retainThread = Thread {
                try {
                    gate.await()
                    try { retained.add(root.retain()) } catch (_: IllegalStateException) { }
                } catch (error: Throwable) { errors.add(error) }
            }
            val closeThread = Thread {
                try { gate.await(); root.close(); root.close() } catch (error: Throwable) { errors.add(error) }
            }
            retainThread.start(); closeThread.start(); gate.countDown()
            retainThread.join(5_000); closeThread.join(5_000)
            assertFalse(retainThread.isAlive); assertFalse(closeThread.isAlive)
            assertTrue("errors=$errors", errors.isEmpty())
            retained.forEach { child -> assertEquals("frame", child.value); child.close() }
            assertEquals(1, disposed.get())
            expectClosed { root.retain() }
        }
    }

    private fun expectClosed(block: () -> Any?) {
        try { block(); fail("closed handle must reject access") } catch (_: IllegalStateException) { }
    }
}
