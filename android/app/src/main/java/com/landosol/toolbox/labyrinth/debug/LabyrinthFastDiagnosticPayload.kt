package com.landosol.toolbox.labyrinth.debug

import com.landosol.toolbox.labyrinth.LabyrinthBalancedDiagnostics
import com.landosol.toolbox.labyrinth.LabyrinthEventPlanningDiagnostics
import com.landosol.toolbox.labyrinth.LabyrinthEntryRecognitionSessionState
import com.landosol.toolbox.labyrinth.vision.LabyrinthFastFrameObservation
import com.landosol.toolbox.labyrinth.concurrent.LabyrinthThreadBudget

/** Tiny T2-only payload. It labels its own capture and never relabels detailed boxes as fresh. */
internal fun labyrinthFastDiagnosticPayload(
    observation: LabyrinthFastFrameObservation,
    state: LabyrinthEntryRecognitionSessionState,
    publishedAtMillis: Long,
    appVersion: String,
    appVersionCode: Int,
): Map<String, Any?> = linkedMapOf(
    "schemaVersion" to 1,
    "available" to true,
    "scope" to "fast_observation_only_not_action_ack",
    "evidenceForActions" to false,
    "appVersion" to appVersion,
    "appVersionCode" to appVersionCode,
    "timestamp" to observation.timestampMillis,
    "publishedAtMillis" to publishedAtMillis,
    "frameSequence" to observation.frameSequence,
    "frameWidth" to observation.width,
    "frameHeight" to observation.height,
    "page" to observation.observation.state.name,
    "pageConfidence" to observation.observation.confidence,
    "pageReason" to observation.observation.reason,
    "elapsedMillis" to observation.elapsedMillis,
    "status" to state.status.name,
    "running" to state.running,
    "paused" to state.paused,
    "message" to state.message,
    "detailObservationCount" to state.frameCount,
    "detailFrameSequence" to state.balancedDiagnostics?.detailFrameSequence,
    "detailTimestampMillis" to state.balancedDiagnostics?.detailTimestampMillis,
    "balancedDiagnostics" to state.balancedDiagnostics?.let(::labyrinthBalancedDiagnosticPayload),
)

internal fun labyrinthBalancedDiagnosticPayload(d: LabyrinthBalancedDiagnostics): Map<String, Any?> = linkedMapOf<String, Any?>(
    "enabled" to d.enabled,
    "availableProcessors" to d.availableProcessors,
    "captureThreadBudget" to 1,
    "controlThreadBudget" to 1,
    "businessThreadBudget" to d.businessThreadBudget,
    "cpuWorkerBudget" to d.cpuWorkerBudget,
    "threadBudgetDescription" to labyrinthBusinessThreadBudgetDescription(d),
    "controlThread" to d.controlThread,
    "fastObservationCount" to d.fastObservationCount,
    "fastFrameSequence" to d.fastFrameSequence,
    "fastTimestampMillis" to d.fastTimestampMillis,
    "fastPage" to d.fastPage,
    "fastElapsedMillis" to d.fastElapsedMillis,
    "detailFrameSequence" to d.detailFrameSequence,
    "detailTimestampMillis" to d.detailTimestampMillis,
    "detailActive" to d.detailActive,
    "detailQuiescent" to d.detailQuiescent,
    "detailActorBusy" to d.detailActorBusy,
    "detailPending" to d.detailPending,
    "detailDiscardCount" to d.detailDiscardCount,
    "fastReplacementCount" to d.fastReplacementCount,
    "plannerPendingCount" to d.plannerPendingCount,
    "plannerDiscardCount" to d.plannerDiscardCount,
    "diagnosticActive" to d.diagnosticActive,
    "diagnosticQuiescent" to d.diagnosticQuiescent,
    "diagnosticPending" to d.diagnosticPending,
    "diagnosticReplacementCount" to d.diagnosticReplacementCount,
    "staleActionBlockedCount" to d.staleActionBlockedCount,
    "sceneEpoch" to d.sceneEpoch,
).apply { d.eventPlanning?.let { put("eventPlanning", labyrinthEventPlanningDiagnosticPayload(it)) } }

internal fun labyrinthEventPlanningDiagnosticPayload(d: LabyrinthEventPlanningDiagnostics): Map<String, Any?> = linkedMapOf(
    "scope" to "event_planning_only_not_action_permission",
    "eventId" to d.eventId, "phase" to d.phase, "semanticKeyHash" to d.semanticKeyHash,
    "active" to d.active, "ready" to d.ready,
    "requestCount" to d.requestCount, "submittedCount" to d.submittedCount,
    "completedCount" to d.completedCount, "cancelledCount" to d.cancelledCount,
    "discardedCount" to d.discardedCount, "ocrRefreshRetainedCount" to d.ocrRefreshRetainedCount,
    "submittedAtMillis" to d.submittedAtMillis, "startedAtMillis" to d.startedAtMillis,
    "endedAtMillis" to d.endedAtMillis, "resultAtMillis" to d.resultAtMillis,
    "quiescedAtMillis" to d.quiescedAtMillis, "readyAtMillis" to d.readyAtMillis,
    "waitingMillis" to d.waitingMillis,
    "lastCancellationReason" to d.lastCancellationReason, "lastFailure" to d.lastFailure,
)

/** Read-only display from the same actual runtime fields carried in the balanced payload. */
internal fun labyrinthBusinessThreadBudgetDescription(d: LabyrinthBalancedDiagnostics): String =
    "${d.businessThreadBudget}（采集1＋控制1＋计算${d.cpuWorkerBudget}） · " +
        "启动可见CPU ${d.availableProcessors}（预算，非同时运行数）"

/** Does not access the runtime or create a pool; selection is not a live worker-count observation. */
internal fun labyrinthThreadBudgetSelectionPayload(
    budget: LabyrinthThreadBudget = LabyrinthThreadBudget.process,
): Map<String, Any?> = linkedMapOf(
    "scope" to "startup_selection_not_live_worker_count",
    "availableProcessors" to budget.availableProcessors,
    "captureThreadBudget" to budget.captureThreadBudget,
    "controlThreadBudget" to budget.controlThreadBudget,
    "selectedCpuWorkerBudget" to budget.cpuWorkerBudget,
    "selectedBusinessThreadBudget" to budget.businessThreadBudget,
)
