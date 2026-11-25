package com.landosol.toolbox.labyrinth

import com.landosol.toolbox.labyrinth.vision.LabyrinthEntryPageState
import com.landosol.toolbox.labyrinth.vision.LabyrinthInitial1088RefinementEvidence
import com.landosol.toolbox.labyrinth.vision.LabyrinthInitial1088RefinementOutcome

/** Captured with the vision request, not reconstructed from a later control state. */
data class LabyrinthInitial1088RefinementContext(
    val allowed: Boolean,
    val reason: String,
    val freshStartExecuted: Boolean,
    val retainedHandoffStart: Boolean,
    val entryPhaseComplete: Boolean,
    val initializationState: String,
    val routeArea: Int?,
    val currentNodeId: Long?,
    val visitedCount: Int,
    val persistedNodeId: Long?,
    val activeNodeType: Int?,
    val transitionPending: Boolean,
    val imprintPending: Boolean,
    val capturedAtMillis: Long = 0,
    val frameSequence: Long = 0,
)

/** Retains only an already successful START over a short map-to-opening handoff. */
internal class LabyrinthInitial1088HandoffEvidence(private val maximumMillis: Long = 5_000L) {
    private var recordedAt = Long.MIN_VALUE
    fun record(page: LabyrinthEntryPageState, startExecuted: Boolean, previouslyAllowed: Boolean, now: Long) {
        recordedAt = if (page == LabyrinthEntryPageState.NODE_SELECTION && startExecuted && previouslyAllowed) now
            else Long.MIN_VALUE
    }
    fun retained(now: Long): Boolean = recordedAt != Long.MIN_VALUE && now >= recordedAt && now - recordedAt <= maximumMillis
    fun reset() { recordedAt = Long.MIN_VALUE }
}

data class LabyrinthInitial1088RecoveryDiagnostic(
    val status: String,
    val reason: String,
    val waitedMillis: Long = 0,
    val requiredNewerThanMillis: Long? = null,
) {
    val shouldWait: Boolean get() = status == "WAITING"
}

/** Only the failed preferred-1088 branch consults this budget. Never grants permission. */
internal class LabyrinthInitial1088RecoveryGate(private val maximumMillis: Long = 5_000L) {
    private var startedAt = Long.MIN_VALUE
    private var firstBlockedFrameAt = Long.MIN_VALUE
    fun reset() { startedAt = Long.MIN_VALUE; firstBlockedFrameAt = Long.MIN_VALUE }
    fun evaluate(
        eligible: Boolean,
        frameAt: Long,
        observedContext: LabyrinthInitial1088RefinementContext?,
        currentContext: LabyrinthInitial1088RefinementContext,
        evidence: LabyrinthInitial1088RefinementEvidence?,
    ): LabyrinthInitial1088RecoveryDiagnostic {
        val waited = if (startedAt == Long.MIN_VALUE) 0 else (frameAt - startedAt).coerceAtLeast(0)
        if (!eligible) {
            reset()
            return LabyrinthInitial1088RecoveryDiagnostic("NOT_NEEDED", "NO_PENDING_FAILED_PREFERRED_1088", waited)
        }
        // A default route START is not newly accepted as evidence by this recovery branch.
        if (!currentContext.allowed || currentContext.reason == "ROUTE_START_DEFAULT_NOT_OBSERVED") {
            return LabyrinthInitial1088RecoveryDiagnostic("BLOCKED", currentContext.reason, waited)
        }
        val stalePermission = observedContext?.allowed == false ||
            evidence?.outcome == LabyrinthInitial1088RefinementOutcome.BLOCKED_PERMISSION
        val sameOldFrame = firstBlockedFrameAt != Long.MIN_VALUE && frameAt <= firstBlockedFrameAt
        if (!stalePermission && !sameOldFrame) {
            return if (evidence == null) LabyrinthInitial1088RecoveryDiagnostic("UNOBSERVED", "REFINEMENT_EVIDENCE_NOT_CAPTURED", waited)
                else LabyrinthInitial1088RecoveryDiagnostic("READY", "CURRENT_FRAME_CHECK_COMPLETE", waited)
        }
        if (startedAt == Long.MIN_VALUE) {
            startedAt = frameAt
            firstBlockedFrameAt = frameAt
        }
        val elapsed = (frameAt - startedAt).coerceAtLeast(0)
        return if (elapsed >= maximumMillis) {
            LabyrinthInitial1088RecoveryDiagnostic("TIMED_OUT", "FRESH_REFINEMENT_FRAME_TIMEOUT", elapsed, firstBlockedFrameAt)
        } else {
            LabyrinthInitial1088RecoveryDiagnostic("WAITING", "PERMISSION_RECOVERED_WAIT_FRESH_FRAME", elapsed, firstBlockedFrameAt)
        }
    }
}

internal fun labyrinthInitial1088RefinementLogKey(
    viewportRevision: Long?,
    context: LabyrinthInitial1088RefinementContext?,
    evidence: LabyrinthInitial1088RefinementEvidence,
): String = listOf(viewportRevision, context?.allowed, context?.reason, context?.initializationState,
    evidence.outcome, evidence.cacheHit, evidence.before, evidence.after,
    evidence.originalMatchRoi, evidence.bestCrop, evidence.attempts, evidence.searchTopCandidates).joinToString("|")

/** One event per outcome/cache/permission change; frame numbers do not cause log flooding. */
internal class LabyrinthInitial1088LogDeduplicator {
    private val lastKeys = linkedMapOf<String, String>()
    fun reset() { lastKeys.clear() }
    fun accept(key: String, subject: String = "default"): Boolean {
        if (lastKeys[subject] == key) return false
        if (subject !in lastKeys && lastKeys.size >= 32) lastKeys.remove(lastKeys.keys.first())
        lastKeys[subject] = key
        return true
    }
}
