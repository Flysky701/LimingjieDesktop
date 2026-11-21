package com.landosol.toolbox.labyrinth.kernel

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.concurrent.CancellationException
import java.util.concurrent.CopyOnWriteArraySet

typealias KernelEvent = KernelEventEnvelope<KernelEventPayload>

fun interface KernelEventSink {
    fun append(event: KernelEvent)
}

@Serializable
data class KernelLogSummary(
    val total: Long, val retained: Int, val capacity: Int, val currentTraceId: String?,
    val byDomain: Map<KernelDomain, Int>, val byKind: Map<String, Int>,
)

@Serializable
data class KernelLogSnapshot(val summary: KernelLogSummary, val events: List<KernelEvent>)

class KernelEventLog(val maxCapacity: Int = 2_000) : KernelEventSink {
    init { require(maxCapacity > 0) }
    private val ring = ArrayDeque<KernelEvent>()
    private var total = 0L
    private val sinks = CopyOnWriteArraySet<KernelEventSink>()
    @Volatile var sinkFailureCount = 0L
        private set

    fun addSink(sink: KernelEventSink) { require(sink !== this); sinks.add(sink) }
    @Synchronized fun removeSink(sink: KernelEventSink) { sinks.remove(sink) }

    @Synchronized override fun append(event: KernelEvent) {
        if (ring.size == maxCapacity) ring.removeFirst()
        ring.addLast(event)
        total++
        for (sink in sinks) {
            try {
                sink.append(event)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                sinkFailureCount++
            }
        }
    }

    @Synchronized fun recent(limit: Int = maxCapacity) = ring.takeLast(limit)
    fun byTraceId(traceId: String) = filter(traceId = traceId)

    fun causalChain(eventSeq: Long): List<KernelEvent> {
        val index = recent().associateBy { it.eventSeq }
        val seen = mutableSetOf<Long>()
        val chain = mutableListOf<KernelEvent>()
        var next = index[eventSeq]
        while (next != null && seen.add(next.eventSeq)) {
            chain += next
            next = index[next.causeEventSeq]?.takeIf { it.traceId == chain.last().traceId }
        }
        return chain.asReversed()
    }

    fun descendants(rootEventSeq: Long): List<KernelEvent> {
        val reached = mutableSetOf(rootEventSeq)
        return recent().filter { it.causeEventSeq in reached && reached.add(it.eventSeq) }
    }

    fun filter(domain: KernelDomain? = null, kind: String? = null, traceId: String? = null,
               reasonCode: String? = null, sinceSeq: Long? = null) = recent().filter {
        (domain == null || it.domain == domain) && (kind == null || it.kind == kind) &&
            (traceId == null || it.traceId == traceId) && (reasonCode == null || it.reasonCode == reasonCode) &&
            (sinceSeq == null || it.eventSeq > sinceSeq)
    }

    @Synchronized fun summary() = KernelLogSummary(total, ring.size, maxCapacity, ring.lastOrNull()?.traceId,
        ring.groupingBy { it.domain }.eachCount(), ring.groupingBy { it.kind }.eachCount())

    @Synchronized fun snapshot() = KernelLogSnapshot(summary(), recent())
    fun toNdjson() = recent().joinToString("\n") { Json.encodeToString<KernelEvent>(it) }
}
