package com.landosol.toolbox.labyrinth

/** Business progress captured around one team-selection frame, without display text or logs. */
internal data class LabyrinthBattleTeamProgress(
    val dispatchedActions: Long,
    val bossPreparation: LabyrinthBossEditorPreparationStage,
    val bossTeamIndex: Int?,
    val bossBlocked: Boolean,
    val bossTargetCount: Int?,
    val pendingBossAdvance: Pair<Int, List<String>>?,
    val selectionPlan: LabyrinthBattleTeamPlanProgress?,
    val rosterIds: List<String>,
    val exhaustedFilters: Set<com.landosol.toolbox.labyrinth.vision.LabyrinthBattleElementFilter>,
    val specialEquipment: LabyrinthSpecialEquipmentProgress,
)

/** Only business fields participate; display names and blocked-reason text are excluded. */
internal data class LabyrinthBattleTeamPlanProgress(
    val recommendedIds: List<String>,
    val selectedIds: List<String>,
    val needSelectIds: List<String>,
    val needDeselectIds: List<String>,
    val scrollRequired: Boolean,
    val readyToExecute: Boolean,
    val teamReady: Boolean,
)

internal data class LabyrinthSpecialEquipmentProgress(
    val stage: LabyrinthSpecialEquipmentFlowStage,
    val attempts: Int,
    val pendingEscape: LabyrinthSpecialEquipmentTap?,
    val closeAnimationPending: Boolean,
    val bossTeamIndex: Int?,
)
