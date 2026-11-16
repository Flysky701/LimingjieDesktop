package com.landosol.toolbox.labyrinth

import com.landosol.toolbox.labyrinth.vision.LabyrinthEntryPageState
import com.landosol.toolbox.labyrinth.vision.LabyrinthEventChoiceObservation
import com.landosol.toolbox.labyrinth.vision.LabyrinthEventOcrDiagnostics
import com.landosol.toolbox.labyrinth.vision.LabyrinthEventVisualIdentity
import com.landosol.toolbox.labyrinth.vision.canRetainEventCalculationDuringOcrExpiry
import kotlinx.coroutines.CoroutineScope

/** Current qualification changes recompute the winner; trust/confirmation/geometry are action gates. */
internal fun labyrinthEventPlanningSemanticKey(
    session: Any, generation: Long, sceneEpoch: Long, planner: Any,
    observation: LabyrinthEventChoiceObservation, acquiredIds: List<String>,
    context: LabyrinthRoleDecisionContext, stacks: Map<LabyrinthRelicMark, Int>, rejectedIds: List<String>,
): List<Any?> = listOf(session, generation, sceneEpoch, planner, observation.event,
    observation.choices.map { listOf(it.choice, it.enabledConfidence >= 0.35, it.buttonConfidence >= 0.12) },
    acquiredIds.toList(), context, stacks.toMap(), rejectedIds.toList())

/** Independent event-lane telemetry; timestamps are wall-clock, never action permission. */
data class LabyrinthEventPlanningDiagnostics(
    val eventId: String? = null,
    val phase: String = "IDLE",
    val semanticKeyHash: Int? = null,
    val active: Boolean = false,
    val ready: Boolean = false,
    val requestCount: Long = 0,
    val submittedCount: Long = 0,
    val completedCount: Long = 0,
    val cancelledCount: Long = 0,
    val discardedCount: Long = 0,
    val ocrRefreshRetainedCount: Long = 0,
    val submittedAtMillis: Long? = null,
    val startedAtMillis: Long? = null,
    val endedAtMillis: Long? = null,
    val resultAtMillis: Long? = null,
    val quiescedAtMillis: Long? = null,
    val readyAtMillis: Long? = null,
    val waitingMillis: Long? = null,
    val lastCancellationReason: String? = null,
    val lastFailure: String? = null,
)

/**
 * T2 owns this lane. A confirmed semantic calculation and current action permission are separate.
 * Expiry may retain only the calculation; no old observation/rectangle is returned for clicking.
 * The underlying slot still enforces cancellation + actual quiescence before reuse.
 */
internal class LabyrinthEventPlanningLane(
    scope: CoroutineScope,
    private val now: () -> Long,
    private val changed: () -> Unit,
) {
    private val slot = LabyrinthBalancedPlanningSlot<List<Any?>, LabyrinthEventChoiceDecision>(scope, now, changed)
    private var trustedKey: List<Any?>? = null
    private var trustedVisual: LabyrinthEventVisualIdentity? = null
    var heldObservation: LabyrinthEventChoiceObservation? = null
        private set
    private var phase = "IDLE"
    private var retained = 0L
    private var refreshAwaitingConfirmation = false
    val busy: Boolean get() = slot.busy
    val discarded: Long get() = slot.discarded
    val readyAt: Long get() = slot.readyAt
    val hasReady: Boolean get() = slot.hasReady
    fun matchesConfirmedKey(key: List<Any?>): Boolean = trustedKey == key

    fun invalidate(reason: String = "SCENE_OR_LIFECYCLE_CHANGED") {
        trustedKey = null
        trustedVisual = null
        heldObservation = null
        phase = "INVALIDATED"
        refreshAwaitingConfirmation = false
        slot.invalidate(reason)
    }

    fun holdExpired(
        currentKey: List<Any?>?, currentVisual: LabyrinthEventVisualIdentity?,
        page: LabyrinthEntryPageState, diagnostic: LabyrinthEventOcrDiagnostics?,
    ): Boolean {
        if (trustedKey != null && currentKey == trustedKey &&
            canRetainEventCalculationDuringOcrExpiry(trustedVisual, currentVisual, page, diagnostic)) {
            if (phase != "OCR_REFRESH_WAIT") retained++
            phase = "OCR_REFRESH_WAIT"
            refreshAwaitingConfirmation = true
            changed()
            return true
        }
        invalidate("OCR_EVIDENCE_OR_SEMANTIC_INPUT_CHANGED")
        return false
    }

    fun update(
        key: List<Any?>, observation: LabyrinthEventChoiceObservation,
        visual: LabyrinthEventVisualIdentity?, capturedAt: Long?,
        calculate: () -> LabyrinthEventChoiceDecision,
        revalidate: (LabyrinthEventChoiceDecision) -> LabyrinthEventChoiceDecision,
    ): LabyrinthEventChoiceDecision {
        // A candidate is not a calculation request or a live action. After a genuine soft refresh,
        // a same-key stable-1 frame may keep work; any different evidence hard-invalidates it.
        if (!observation.trusted || observation.stableFrames < 2) {
            if (!refreshAwaitingConfirmation || key != trustedKey || visual == null || visual != trustedVisual) {
                invalidate("EVENT_NOT_CONFIRMED_OR_INPUT_CHANGED")
            }
            phase = "WAITING_CONFIRMATION"
            changed()
            return LabyrinthEventChoiceDecision.Wait("事件文字尚未连续稳定确认，等待确认后再规划")
        }
        refreshAwaitingConfirmation = false
        trustedKey = key
        trustedVisual = visual
        heldObservation = observation
        phase = "PLANNING"
        // Do not turn cancellation/transient failure into a cached successful Wait. Failed work
        // quiesces and the next current confirmed frame can retry the same semantic key.
        val ready = slot.request(key, calculate)
        if (ready == null) {
            phase = if (slot.lastFailureMessage == null) "PLANNING" else "FAILED"
            return LabyrinthEventChoiceDecision.Wait(slot.lastFailureMessage?.let {
                "事件规划失败，等待重新规划：$it"
            } ?: "事件规划进行中，等待规划返回后的新详细帧")
        }
        if (capturedAt == null || capturedAt <= slot.readyAt) {
            phase = "WAITING_FRESH_DETAIL"
            return LabyrinthEventChoiceDecision.Wait("事件规划已返回，等待规划完成后的新详细帧")
        }
        phase = "READY_REVALIDATED"
        return revalidate(ready)
    }

    fun diagnostics(): LabyrinthEventPlanningDiagnostics = LabyrinthEventPlanningDiagnostics(
        eventId = heldObservation?.event?.id,
        phase = phase,
        semanticKeyHash = trustedKey?.hashCode(),
        active = slot.busy,
        ready = slot.hasReady,
        requestCount = slot.requestCount,
        submittedCount = slot.submittedCount,
        completedCount = slot.completedCount,
        cancelledCount = slot.cancelledCount,
        discardedCount = slot.discarded,
        ocrRefreshRetainedCount = retained,
        submittedAtMillis = slot.lastSubmittedAt,
        startedAtMillis = slot.lastStartedAt,
        endedAtMillis = slot.lastEndedAt,
        resultAtMillis = slot.lastResultAt,
        quiescedAtMillis = slot.lastQuiescedAt,
        readyAtMillis = slot.readyAt.takeUnless { it == Long.MIN_VALUE },
        waitingMillis = if (slot.busy) slot.lastSubmittedAt?.let { (now() - it).coerceAtLeast(0L) } else null,
        lastCancellationReason = slot.lastCancellationReason,
        lastFailure = slot.lastFailureMessage,
    )
}
