package com.landosol.toolbox.labyrinth.debug

import com.landosol.toolbox.labyrinth.LabyrinthInitial1088RefinementContext
import com.landosol.toolbox.labyrinth.LabyrinthInitial1088RecoveryDiagnostic
import com.landosol.toolbox.labyrinth.vision.EntryPixelRect
import com.landosol.toolbox.labyrinth.vision.LabyrinthInitial1088RefinementEvidence
import com.landosol.toolbox.labyrinth.vision.LabyrinthInitial1088RefinementOutcome
import com.landosol.toolbox.labyrinth.vision.LabyrinthInitial1088RefinementSnapshot

/** Plain data shared by logcat and JSON export; never consulted to authorize an action. */
fun labyrinthInitial1088ContextPayload(context: LabyrinthInitial1088RefinementContext): Map<String, Any?> = linkedMapOf(
    "allowed" to context.allowed, "reason" to context.reason,
    "capturedAtMillis" to context.capturedAtMillis, "frameSequence" to context.frameSequence,
    "freshStartExecuted" to context.freshStartExecuted, "retainedHandoffStart" to context.retainedHandoffStart,
    "entryPhaseComplete" to context.entryPhaseComplete, "initializationState" to context.initializationState,
    "routeArea" to context.routeArea, "currentNodeId" to context.currentNodeId,
    "visitedCount" to context.visitedCount, "persistedNodeId" to context.persistedNodeId,
    "activeNodeType" to context.activeNodeType, "transitionPending" to context.transitionPending,
    "imprintPending" to context.imprintPending,
)

fun labyrinthInitial1088RefinementPayload(
    slotId: String,
    evidence: LabyrinthInitial1088RefinementEvidence,
    context: LabyrinthInitial1088RefinementContext?,
): Map<String, Any?> = linkedMapOf(
    "slot" to slotId, "outcome" to evidence.outcome.name,
    "reason" to when (evidence.outcome) {
        LabyrinthInitial1088RefinementOutcome.SKIPPED_TRUSTED -> "ALREADY_TRUSTED"
        LabyrinthInitial1088RefinementOutcome.BLOCKED_PERMISSION -> if (context?.allowed == true)
            "OPENING_SHELL_NOT_CONFIRMED" else context?.reason ?: evidence.permitReason ?: "PERMISSION_NOT_GRANTED"
        LabyrinthInitial1088RefinementOutcome.BLOCKED_CLIPPED -> "CARD_NOT_FULLY_VISIBLE"
        LabyrinthInitial1088RefinementOutcome.BLOCKED_VIEWPORT -> "RECOVERY_CROP_OUTSIDE_VIEWPORT"
        LabyrinthInitial1088RefinementOutcome.EXECUTED_TRUSTED -> "REFINEMENT_PASSED_SAFE_FLOORS"
        LabyrinthInitial1088RefinementOutcome.EXECUTED_UNTRUSTED -> "REFINEMENT_DID_NOT_PASS_SAFE_FLOORS"
    },
    "sessionAllowed" to context?.allowed,
    "refinementAllowed" to (evidence.permitReason != "PERMISSION_NOT_GRANTED"),
    "before" to initial1088SnapshotPayload(evidence.before),
    "after" to initial1088SnapshotPayload(evidence.after),
    "originalMatchRoi" to initial1088RectPayload(evidence.originalMatchRoi),
    "bestCrop" to evidence.bestCrop?.let(::initial1088RectPayload),
    "attempts" to evidence.attempts, "cacheHit" to evidence.cacheHit,
    "searchTopCandidates" to evidence.searchTopCandidates.map { linkedMapOf("id" to it.characterId, "score" to it.score) },
)

fun labyrinthInitial1088RecoveryPayload(value: LabyrinthInitial1088RecoveryDiagnostic): Map<String, Any?> = linkedMapOf(
    "status" to value.status, "reason" to value.reason, "waitedMillis" to value.waitedMillis,
    "requiredNewerThanMillis" to value.requiredNewerThanMillis,
)

private fun initial1088SnapshotPayload(value: LabyrinthInitial1088RefinementSnapshot): Map<String, Any?> = linkedMapOf(
    "score" to value.score, "margin" to value.margin, "suspectedId" to value.suspectedCharacterId,
    "topCandidates" to value.topCandidates.map { linkedMapOf("id" to it.characterId, "score" to it.score) },
)

private fun initial1088RectPayload(rect: EntryPixelRect): Map<String, Int> = linkedMapOf(
    "left" to rect.left, "top" to rect.top, "width" to rect.width, "height" to rect.height,
)
