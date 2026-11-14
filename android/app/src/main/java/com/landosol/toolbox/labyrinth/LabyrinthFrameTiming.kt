package com.landosol.toolbox.labyrinth

import com.landosol.toolbox.automation.capture.FrameQueueSnapshot

/** All elapsed fields are diagnostic-only; session handling includes persistence/planning/overlay. */
data class LabyrinthFrameTiming(
    val frameSequence: Long,
    val captureConvertMillis: Long?,
    val captureToWorkerMillis: Long?,
    val queueWaitMillis: Long?,
    val visionMillis: Long,
    val sessionHandlingMillis: Long,
    val responseIntervalMillis: Long,
    val queue: FrameQueueSnapshot,
    val nodeScanHoldReason: String?,
    val nodeScanQueryTimestampMillis: Long? = null,
    val roleRound: LabyrinthRoleRoundDiagnostic? = null,
    val bossTabAction: LabyrinthBossTabDiagnostic? = null,
    val discardedRoleDecisionResults: Long = 0L,
)
