package com.landosol.toolbox.labyrinth

/** Diagnostic snapshot only; it is not an acquisition receipt. */
data class LabyrinthRoleRoundDiagnostic(
    val generation: Long, val phase: String, val selectedId: String?, val token: Long?,
    val dispatchedAtMillis: Long?, val feedbackAfterMillis: Long?, val newRoundEvidenceFrames: Int,
)

/** One selection round, not the entire multi-role reward batch. No scores or geometry are stored. */
internal class LabyrinthRoleRewardRound {
    private enum class Phase { DISPATCHED, INPUT_FINISHED, RETRY_READY }
    private data class Pending(val token: Long, val selectedId: String,
        val trustedSlots: Map<String, String>, val dispatchedAt: Long,
        var feedbackAfter: Long, var phase: Phase = Phase.DISPATCHED)
    private var generation = 0L
    private var sequence = 0L
    private var pending: Pending? = null
    private var candidateKey: String? = null
    private var candidateFrames = 0
    private var candidateFirstAt = Long.MIN_VALUE
    private var candidateLastAt = Long.MIN_VALUE

    @Synchronized fun epoch(): Long = generation
    @Synchronized fun blocksPlanning(): Boolean = pending != null
    @Synchronized fun blocksDispatch(): Boolean = pending?.let { it.phase != Phase.RETRY_READY } == true
    @Synchronized fun retryReady(): Boolean = pending?.phase == Phase.RETRY_READY
    @Synchronized fun selectedId(): String? = pending?.selectedId
    @Synchronized fun isCurrent(token: Long): Boolean = pending?.token == token
    @Synchronized fun snapshot(): LabyrinthRoleRoundDiagnostic = LabyrinthRoleRoundDiagnostic(
        generation, pending?.phase?.name ?: "OPEN", pending?.selectedId, pending?.token,
        pending?.dispatchedAt, pending?.feedbackAfter, candidateFrames)

    @Synchronized fun reset() {
        generation++; sequence++; pending = null; resetEvidence()
    }
    private fun resetEvidence() {
        candidateKey = null; candidateFrames = 0
        candidateFirstAt = Long.MIN_VALUE; candidateLastAt = Long.MIN_VALUE
    }

    /** A weak sibling becoming known, or a known slot becoming unknown, is not a new round. */
    @Synchronized fun observe(isRewardPage: Boolean, trustedSlots: Map<String, String>, capturedAt: Long): Boolean {
        val p = pending ?: return false
        if (!isRewardPage || p.phase == Phase.DISPATCHED || capturedAt <= p.feedbackAfter) {
            resetEvidence(); return false
        }
        val changedKnownSlot = p.trustedSlots.any { (slot, oldId) ->
            trustedSlots[slot]?.let { it != oldId } == true
        }
        val valid = trustedSlots.size >= 2 && trustedSlots.values.toSet().size == trustedSlots.size &&
            p.selectedId !in trustedSlots.values && changedKnownSlot
        if (!valid) { resetEvidence(); return false }
        val key = trustedSlots.entries.sortedBy { it.key }.joinToString("|") { "${it.key}:${it.value}" }
        if (capturedAt <= candidateLastAt) return false
        if (key != candidateKey) {
            candidateKey = key; candidateFrames = 1; candidateFirstAt = capturedAt
        } else candidateFrames++
        candidateLastAt = capturedAt
        // Fresh frames stabilize the round boundary, not the identity matcher itself.
        if (candidateFrames < 2 || capturedAt - candidateFirstAt < 300L) return false
        pending = null; generation++; resetEvidence()
        return true
    }

    @Synchronized fun submit(selectedId: String, trustedSlots: Map<String, String>, dispatchedAt: Long): Long? {
        if (blocksDispatch() || selectedId !in trustedSlots.values) return null
        if (pending != null && pending?.selectedId != selectedId) return null
        val token = ++sequence
        pending = Pending(token, selectedId, trustedSlots.toMap(), dispatchedAt, dispatchedAt)
        resetEvidence()
        return token
    }
    @Synchronized fun inputFinished(token: Long, at: Long): Boolean {
        val p = pending?.takeIf { it.token == token } ?: return false
        p.phase = Phase.INPUT_FINISHED; p.feedbackAfter = maxOf(p.dispatchedAt, at)
        return true
    }
    @Synchronized fun notExecuted(token: Long, at: Long): Boolean {
        val p = pending?.takeIf { it.token == token } ?: return false
        p.phase = Phase.RETRY_READY; p.feedbackAfter = maxOf(p.dispatchedAt, at)
        generation++; resetEvidence()
        return true
    }
}

internal fun labyrinthRoleDecisionCanPublish(
    sameSession: Boolean, running: Boolean, paused: Boolean,
    requestGeneration: Long, currentGeneration: Long,
    requestRound: Long, currentRound: Long, submitted: Boolean,
): Boolean = sameSession && running && !paused && requestGeneration == currentGeneration &&
    requestRound == currentRound && !submitted
