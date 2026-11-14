package com.landosol.toolbox.labyrinth.debug

import com.landosol.toolbox.labyrinth.LabyrinthBalancedDiagnostics
import com.landosol.toolbox.labyrinth.LabyrinthEntryRecognitionSessionState
import com.landosol.toolbox.labyrinth.vision.LabyrinthFastFrameObservation

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

internal fun labyrinthBalancedDiagnosticPayload(d: LabyrinthBalancedDiagnostics): Map<String, Any?> = linkedMapOf(
    "enabled" to d.enabled,
    "businessThreadBudget" to d.businessThreadBudget,
    "cpuWorkerBudget" to d.cpuWorkerBudget,
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
)
