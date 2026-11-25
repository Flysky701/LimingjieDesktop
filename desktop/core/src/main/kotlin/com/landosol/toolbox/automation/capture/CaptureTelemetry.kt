package com.landosol.toolbox.automation.capture

import java.util.concurrent.atomic.AtomicLong

/** Diagnostic-only process-lifetime counters; never used for scheduling or decisions. */
data class CaptureCounterSnapshot(val callbacks: Long, val acquiredImages: Long,
    val convertedFrames: Long, val intervalDropped: Long, val orientationDropped: Long,
    val noConsumerDropped: Long, val conversionErrors: Long)

object CaptureTelemetry {
    private val callbacks = AtomicLong()
    private val acquired = AtomicLong()
    private val converted = AtomicLong()
    private val interval = AtomicLong()
    private val orientation = AtomicLong()
    private val noConsumer = AtomicLong()
    private val errors = AtomicLong()
    fun callback() { callbacks.incrementAndGet() }
    fun acquired() { acquired.incrementAndGet() }
    fun converted(): Long = converted.incrementAndGet()
    fun intervalDropped() { interval.incrementAndGet() }
    fun orientationDropped() { orientation.incrementAndGet() }
    fun noConsumerDropped() { noConsumer.incrementAndGet() }
    fun conversionError() { errors.incrementAndGet() }
    fun snapshot() = CaptureCounterSnapshot(callbacks.get(), acquired.get(), converted.get(),
        interval.get(), orientation.get(), noConsumer.get(), errors.get())
}

data class FrameQueueSnapshot(val seen: Long, val accepted: Long, val intervalDropped: Long,
    val invalidDropped: Long, val replaced: Long)

/** Session-scoped counters. Capture callback and frame worker may both read these. */
class FrameQueueMetrics {
    private val seen = AtomicLong()
    private val accepted = AtomicLong()
    private val interval = AtomicLong()
    private val invalid = AtomicLong()
    private val replaced = AtomicLong()
    fun reset() { seen.set(0); accepted.set(0); interval.set(0); invalid.set(0); replaced.set(0) }
    fun seen() { seen.incrementAndGet() }
    fun accepted() { accepted.incrementAndGet() }
    fun intervalDropped() { interval.incrementAndGet() }
    fun invalidDropped() { invalid.incrementAndGet() }
    fun replaced() { replaced.incrementAndGet() }
    fun snapshot() = FrameQueueSnapshot(seen.get(), accepted.get(), interval.get(), invalid.get(), replaced.get())
}
