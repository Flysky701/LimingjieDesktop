package com.landosol.toolbox.labyrinth

import com.landosol.toolbox.automation.capture.RefCountedFrameLease
import com.landosol.toolbox.labyrinth.concurrent.LabyrinthFourThreadRuntime
import com.landosol.toolbox.labyrinth.concurrent.WorkHandle
import com.landosol.toolbox.labyrinth.concurrent.WorkKind
import com.landosol.toolbox.labyrinth.vision.LabyrinthEntryFrameResult
import com.landosol.toolbox.labyrinth.vision.LabyrinthFastFrameObservation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** A producer mailbox owns each offered handle even when publication throws. */
internal class LabyrinthBalancedMailbox<T : Any> {
    private val latest = AtomicReference<RefCountedFrameLease<T>?>(null)
    private val closed = AtomicBoolean(false)
    fun offer(handle: RefCountedFrameLease<T>): Boolean {
        if (closed.get()) { handle.close(); return false }
        val replaced = latest.getAndSet(handle)
        replaced?.close()
        if (closed.get()) latest.getAndSet(null)?.close()
        return replaced != null
    }
    fun take(): RefCountedFrameLease<T>? = latest.getAndSet(null)
    val hasPending: Boolean get() = latest.get() != null
    fun close() { closed.set(true); latest.getAndSet(null)?.close() }
}

internal data class LabyrinthBalancedVisionResult(
    val result: LabyrinthEntryFrameResult,
    val visionMillis: Long,
    val workerStartedMillis: Long,
    val queryUsed: Boolean,
)

/**
 * T1 only publishes a latest mailbox. T2 owns all scheduling and serial result transactions.
 * One full processor body and one result transaction maximum; pending input is latest-only.
 * CPU leases survive cancel until root AND inherited children have physically exited.
 */
internal class LabyrinthBalancedFramePipeline<F : Any>(
    private val scope: CoroutineScope,
    private val fastProcessor: (F) -> LabyrinthFastFrameObservation,
    private val detailProcessor: (F) -> LabyrinthEntryFrameResult,
    private val admitted: () -> Boolean,
    private val snapshot: (F) -> LabyrinthBalancedVisionContext.RequestSnapshot,
    private val observe: (LabyrinthFastFrameObservation) -> Unit,
    private val deliver: suspend (RefCountedFrameLease<F>, LabyrinthBalancedVisionContext.RequestSnapshot, LabyrinthBalancedVisionResult) -> Unit,
    private val failure: (Throwable) -> Unit,
    private val changed: () -> Unit,
    private val intervalMillis: () -> Long,
    private val recycle: (F) -> Unit,
    private val timestampMillis: (F) -> Long,
    private val workerClock: () -> Long,
    private val foregroundReady: () -> Boolean = { true },
    private val onNotForeground: () -> Unit = {},
) {
    private val mailbox = LabyrinthBalancedMailbox<F>()
    private val observationScheduled = AtomicBoolean(false)
    private val fastReplacements = AtomicLong()
    val fastReplacementCount get() = fastReplacements.get()
    @Volatile private var closed = false
    private var latestFastFrame: RefCountedFrameLease<F>? = null
    private var pending: RefCountedFrameLease<F>? = null
    private class Flight<F : Any>(val lease: RefCountedFrameLease<F>, val snapshot: LabyrinthBalancedVisionContext.RequestSnapshot) {
        var handle: WorkHandle<LabyrinthBalancedVisionResult>? = null
        var result: Result<LabyrinthBalancedVisionResult>? = null
        var quiesced = false
    }
    private var active: Flight<F>? = null
    private var lastDetailAt = Long.MIN_VALUE
    var actorBusy = false
        private set
    var replacements = 0L
        private set
    val detailActive get() = active != null
    val detailPending get() = pending != null

    fun offer(frame: F) {
        // No exception may escape to CaptureFrameBus's legacy recycle fallback.
        var handle: RefCountedFrameLease<F>? = null
        try {
            handle = RefCountedFrameLease.create(frame, recycle)
            if (closed || !admitted()) { handle.close(); return }
            if (mailbox.offer(handle)) fastReplacements.incrementAndGet()
            handle = null
            scheduleObservation()
        } catch (_: Throwable) { handle?.close() }
    }

    private fun scheduleObservation() {
        if (!observationScheduled.compareAndSet(false, true)) return
        scope.launch {
            try {
                while (true) {
                    val input = mailbox.take() ?: break
                    try {
                        if (!closed && admitted()) {
                            if (!foregroundReady()) {
                                pending?.close()
                                pending = null
                                onNotForeground()
                                changed()
                            } else {
                                val fast = fastProcessor(input.value)
                                observe(fast)
                                latestFastFrame?.close()
                                latestFastFrame = input.retain()
                                if (pending != null) replacements++
                                pending?.close()
                                pending = input.retain()
                                pump()
                                changed()
                            }
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Throwable) {
                        if (!closed && admitted()) failure(error)
                    } finally { input.close() }
                    yield() // Lifecycle/control messages outrank a perpetually nonempty mailbox.
                }
            } finally {
                observationScheduled.set(false)
                // No take/reoffer: it could overwrite a newer producer publication with an old one.
                if (mailbox.hasPending) scheduleObservation()
            }
        }
    }

    private suspend fun complete(flight: Flight<F>) {
        val outcome = flight.result ?: return
        if (active !== flight || !flight.quiesced) return
        active = null
        if (!closed && admitted() && outcome.isSuccess) {
            actorBusy = true
            try { deliver(flight.lease, flight.snapshot, outcome.getOrThrow()) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Throwable) { if (!closed && admitted()) failure(error) }
            finally { actorBusy = false; flight.lease.close() }
        } else {
            flight.lease.close()
            val error = outcome.exceptionOrNull()
            if (!closed && admitted() && error != null && error !is java.util.concurrent.CancellationException) failure(error)
        }
        pump()
        changed()
    }

    private fun pump() {
        if (closed || !admitted() || !foregroundReady() || active != null || actorBusy) return
        val input = pending ?: return
        pending = null
        if (lastDetailAt != Long.MIN_VALUE && timestampMillis(input.value) - lastDetailAt < intervalMillis()) {
            input.close(); return
        }
        lastDetailAt = timestampMillis(input.value)
        val requestSnapshot = try { snapshot(input.value) } catch (error: Throwable) {
            input.close(); failure(error); return
        }
        val flight = Flight(input, requestSnapshot)
        active = flight
        try {
            flight.handle = LabyrinthFourThreadRuntime.submitWork(
                kind = WorkKind.DETAILED_VISION,
                onResult = { outcome -> scope.launch {
                    if (active === flight) { flight.result = outcome; complete(flight) }
                } },
                onQuiesced = { scope.launch {
                    if (active === flight) { flight.quiesced = true; complete(flight) }
                } },
            ) {
                val workerStarted = workerClock()
                LabyrinthBalancedVisionContext.withSnapshot(requestSnapshot) {
                    LabyrinthFourThreadRuntime.checkpoint()
                    val started = System.nanoTime()
                    val result = detailProcessor(flight.lease.value)
                    LabyrinthBalancedVisionResult(result, (System.nanoTime() - started) / 1_000_000,
                        workerStarted, LabyrinthBalancedVisionContext.nodeScanQueryUsed())
                }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            // Rejection callbacks own the lease and the flight slot.
        }
    }

    fun clearPending() {
        mailbox.take()?.close()
        pending?.close(); pending = null
        latestFastFrame?.close(); latestFastFrame = null
        active?.handle?.cancel(false)
        changed()
    }

    /** Pause/stop seals ingress immediately; never awaits a long CPU pass. */
    fun close() {
        closed = true
        mailbox.close()
        pending?.close(); pending = null
        latestFastFrame?.close(); latestFastFrame = null
        active?.handle?.cancel(false)
        changed()
    }
}

/** Shared-pool low-priority diagnostics: immutable T2 closures, active + latest pending only. */
internal class LabyrinthBalancedDiagnosticLane(private val scope: CoroutineScope, private val changed: () -> Unit) {
    private class Item(val close: () -> Unit, val body: () -> Unit) { var handle: WorkHandle<Unit>? = null }
    private var active: Item? = null
    private var pending: Item? = null
    var replacements = 0L
        private set
    val busy get() = active != null
    val hasPending get() = pending != null
    fun offer(close: () -> Unit, body: () -> Unit) {
        val item = Item(close, body)
        if (active == null) start(item) else {
            if (pending != null) replacements++
            pending?.close?.invoke(); pending = item
        }
        changed()
    }
    fun clear() {
        pending?.close?.invoke(); pending = null
        active?.handle?.cancel(false)
        changed()
    }
    private fun start(item: Item) {
        active = item
        try {
            item.handle = LabyrinthFourThreadRuntime.submitWork(
                kind = WorkKind.DIAGNOSTIC,
                onQuiesced = { scope.launch {
                    item.close()
                    if (active === item) {
                        active = null
                        val next = pending; pending = null
                        if (next != null) start(next)
                    }
                    changed()
                } },
            ) { item.body() }
        } catch (_: java.util.concurrent.RejectedExecutionException) { /* callbacks clean up */ }
    }
}
