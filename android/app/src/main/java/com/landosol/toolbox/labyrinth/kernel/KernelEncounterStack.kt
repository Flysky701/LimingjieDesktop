package com.landosol.toolbox.labyrinth.kernel

import kotlinx.serialization.Serializable

@Serializable
enum class KernelEncounterKind { ENTRY, MAP, EVENT, BATTLE, SHOP, REWARD_TAIL, SETTLEMENT, RECOVERY }

@Serializable
data class KernelEncounterFrame(
    val id: String,
    val kind: KernelEncounterKind,
    val parentId: String? = null,
    val label: String = "",
    val metadata: Map<String, String> = emptyMap(),
) {
    init { require(id.isNotBlank()) }
}

class KernelEncounterStack(private val runtime: KernelRuntime) {
    private val frames = ArrayDeque<KernelEncounterFrame>()

    fun push(frame: KernelEncounterFrame): KernelEncounterFrame = synchronized(runtime) {
        require(frames.none { it.id == frame.id }) { "Duplicate encounter id: " + frame.id }
        val next = frame.copy(parentId = frame.parentId ?: frames.lastOrNull()?.id, metadata = frame.metadata.toMap())
        frames.addLast(next)
        record(next, "push", "encounter_push")
        next
    }

    fun peek(): KernelEncounterFrame? = synchronized(runtime) { frames.lastOrNull() }
    fun depth(): Int = synchronized(runtime) { frames.size }
    fun snapshot(): List<KernelEncounterFrame> = synchronized(runtime) { frames.toList() }

    fun pop(reason: String = "encounter_pop"): KernelEncounterFrame? = synchronized(runtime) {
        frames.removeLastOrNull()?.also { record(it, "pop", reason) }
    }

    fun popExpected(id: String, reason: String = "encounter_pop_expected"): KernelEncounterFrame? = synchronized(runtime) {
        if (frames.lastOrNull()?.id == id) pop(reason) else null
    }

    fun clear(reason: String = "encounter_clear"): List<KernelEncounterFrame> = synchronized(runtime) {
        buildList { while (frames.isNotEmpty()) add(checkNotNull(pop(reason))) }
    }

    internal fun resetSilent() = synchronized(runtime) { frames.clear() }

    private fun record(frame: KernelEncounterFrame, op: String, reason: String) {
        runtime.emit(KernelDomain.RUNTIME, KernelEventKinds.STATE_REDUCED, StateReducedPayload(
            frame.kind.name, frame.id, frame.parentId, frames.size,
            mapOf("op" to op, "kind" to frame.kind.name, "reason" to reason),
        ), reasonCode = reason)
    }
}
