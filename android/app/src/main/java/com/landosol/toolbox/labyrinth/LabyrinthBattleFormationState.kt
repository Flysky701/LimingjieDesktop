package com.landosol.toolbox.labyrinth

/** Battle-local fallback only. Page churn and retries do not alter the saved user mode. */
internal class LabyrinthBattleFormationState {
    @Volatile
    var stableFallbackReason: LabyrinthBattleFormationFallbackReason? = null
        private set

    fun requestedMode(savedMode: LabyrinthFrontlineSurvivalMode): LabyrinthFrontlineSurvivalMode =
        if (savedMode == LabyrinthFrontlineSurvivalMode.VERY_AGGRESSIVE && stableFallbackReason != null) {
            LabyrinthFrontlineSurvivalMode.STABLE
        } else savedMode

    fun observe(
        savedMode: LabyrinthFrontlineSurvivalMode,
        teamIndex: Int,
        effectiveMode: LabyrinthFrontlineSurvivalMode?,
        fallbackReason: LabyrinthBattleFormationFallbackReason?,
    ) {
        if (savedMode != LabyrinthFrontlineSurvivalMode.VERY_AGGRESSIVE ||
            effectiveMode != LabyrinthFrontlineSurvivalMode.STABLE || fallbackReason == null) return
        // Team 2/3 shortage is local to that remaining pool. A failed aggressive search, or
        // shortage on the lead team, keeps this whole battle stable through subsequent retries.
        if (teamIndex <= 1 || fallbackReason == LabyrinthBattleFormationFallbackReason.NO_UNFAILED_FORMATION) {
            stableFallbackReason = fallbackReason
        }
    }

    fun reset() { stableFallbackReason = null }

    val fallbackNote: String?
        get() = stableFallbackReason?.let { "非常激进：" + it.description + "；本场临时按稳定编队" }
}

internal enum class LabyrinthExhaustedFormationAction { NONE, REROLL, PROMPT }

/** Only a completed aggressive -> stable exhaustion may use the existing reroll permission. */
internal fun labyrinthExhaustedFormationAction(
    savedMode: LabyrinthFrontlineSurvivalMode,
    failure: LabyrinthBattleFormationFailure,
    rerollEnabled: Boolean,
    dryRun: Boolean,
): LabyrinthExhaustedFormationAction {
    if (savedMode != LabyrinthFrontlineSurvivalMode.VERY_AGGRESSIVE || dryRun ||
        failure != LabyrinthBattleFormationFailure.EXHAUSTED_AFTER_STABLE) {
        return LabyrinthExhaustedFormationAction.NONE
    }
    return if (rerollEnabled) LabyrinthExhaustedFormationAction.REROLL else LabyrinthExhaustedFormationAction.PROMPT
}

/** Whole Boss capacity uses all remaining roles; each later team applies its own mode fallback. */
internal fun labyrinthAggressiveBossPlannedRemainingTeams(
    savedMode: LabyrinthFrontlineSurvivalMode,
    multiBoss: Boolean,
    requestedRemaining: Int,
    availableCount: Int,
    plannedCount: Int,
): Int = if (savedMode == LabyrinthFrontlineSurvivalMode.VERY_AGGRESSIVE && multiBoss && availableCount >= 5) {
    minOf(requestedRemaining, availableCount / 5, 3).coerceAtLeast(1)
} else plannedCount

/** Keep confirmed Boss teams; the existing empty-tab/start-button checks still own execution. */
internal fun labyrinthAggressiveBossRemainingTeamTarget(
    savedMode: LabyrinthFrontlineSurvivalMode,
    multiTeam: Boolean,
    teamIndex: Int,
    remainingCount: Int,
    confirmedUsedCount: Int,
): Int? = if (savedMode == LabyrinthFrontlineSurvivalMode.VERY_AGGRESSIVE && multiTeam &&
    teamIndex in 2..3 && remainingCount in 0..4 && confirmedUsedCount >= (teamIndex - 1) * 5) {
    teamIndex - 1
} else null
