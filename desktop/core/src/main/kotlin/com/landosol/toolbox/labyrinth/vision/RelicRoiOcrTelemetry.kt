package com.landosol.toolbox.labyrinth.vision

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** Engine completion diagnostics only, never an acquisition or business acknowledgement. */
data class RelicRoiOcrCompletion(val id: Long, val slot: String, val capturedAtMillis: Long,
    val durationMillis: Long, val textLength: Int?)
data class RelicRoiOcrSnapshot(val requests: Long, val completions: Long, val inFlight: Int,
    val last: RelicRoiOcrCompletion?)

object RelicRoiOcrTelemetry {
    private data class Request(val slot: String, val capturedAtMillis: Long, val startedAt: Long)
    private val sequence = AtomicLong()
    private val requests = AtomicLong()
    private val completions = AtomicLong()
    private val pending = ConcurrentHashMap<Long, Request>()
    @Volatile private var last: RelicRoiOcrCompletion? = null
    fun requested(slot: String, capturedAtMillis: Long, startedAtElapsedMillis: Long): Long {
        val id = sequence.incrementAndGet()
        pending[id] = Request(slot, capturedAtMillis, startedAtElapsedMillis)
        requests.incrementAndGet()
        return id
    }
    fun completed(id: Long, completedAtElapsedMillis: Long, textLength: Int?) {
        val request = pending.remove(id) ?: return
        last = RelicRoiOcrCompletion(id, request.slot, request.capturedAtMillis,
            (completedAtElapsedMillis - request.startedAt).coerceAtLeast(0L), textLength)
        completions.incrementAndGet()
    }
    fun snapshot() = RelicRoiOcrSnapshot(requests.get(), completions.get(), pending.size, last)
}
