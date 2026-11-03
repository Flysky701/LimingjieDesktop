package com.landosol.toolbox.labyrinth

import com.landosol.toolbox.labyrinth.vision.EntryPixelRect
import com.landosol.toolbox.labyrinth.vision.LabyrinthEntryPageState

/**
 * Intermediate Boss victories (for example area 3 -> area 4) use a special WIN/result chain
 * that is not the final run-clear flow. On the current client those pages can be classified as
 * UNKNOWN while still exposing the same fixed bottom-right "下一步" button used by the ordinary
 * battle result page.
 *
 * This is deliberately narrower than a generic UNKNOWN fallback: only an active Boss combat
 * context may use it, and only a current-frame button match above the Boss-specific safety line
 * is accepted. Normal/EX battles and unrelated UNKNOWN pages keep their existing behavior.
 */
internal fun labyrinthBossSettlementNextButtonRect(
    pageState: LabyrinthEntryPageState,
    combatContext: LabyrinthCombatContext?,
    nextButtonMatch: com.landosol.toolbox.labyrinth.vision.EntryAnchorMatch?,
    bossSummaryNextButtonMatch: com.landosol.toolbox.labyrinth.vision.EntryAnchorMatch? = null,
    minimumScore: Double = 0.68,
): com.landosol.toolbox.labyrinth.vision.EntryPixelRect? {
    if (pageState != LabyrinthEntryPageState.UNKNOWN) return null
    if (combatContext?.kind != LabyrinthCombatKind.BOSS) return null
    val match = listOfNotNull(nextButtonMatch, bossSummaryNextButtonMatch)
        .maxByOrNull(com.landosol.toolbox.labyrinth.vision.EntryAnchorMatch::score)
        ?: return null
    return match.rect.takeIf { match.score >= minimumScore }
}

/**
 * Pages of the final settlement chain. When the entry planner reports one of them as "complete"
 * but no route executor is configured, the session must wait rather than stop: the game is still
 * inside a run and a stopped session would strand it on the score page.
 */
internal fun labyrinthCompletionWaitsWithoutRoute(state: LabyrinthEntryPageState): Boolean = state in setOf(
    LabyrinthEntryPageState.RUN_CLEAR_RESULT,
    LabyrinthEntryPageState.RUN_CLEAR_CONGRATULATIONS,
    LabyrinthEntryPageState.RUN_CLEAR_CHARACTER_SUMMARY,
    LabyrinthEntryPageState.RUN_CLEAR_REWARD_ANIMATION,
    LabyrinthEntryPageState.RUN_CLEAR_CHEST_ANIMATION,
    LabyrinthEntryPageState.RUN_CLEAR_CHEST_RESULT,
)

internal enum class LabyrinthRunTerminalOutcome { CLEARED, FAILED_MAX_RETRY, SCORE_OPENING_SKIPPED }

internal enum class LabyrinthPostBossStage {
    NONE,
    BEFORE_SCORE,
    SCORE_RESULT,
    CHEST_SEQUENCE,
    FINAL_ITEM_REWARD,
    WAITING_FOR_DAWN_HOME,
}

/**
 * Reconcile settlement progress with the recognized page. A close tap can merely skip the
 * score animation, so a lingering RESULT must remain retryable. Once a chest or final item
 * page is recognized, it also proves that RESULT has closed, even if the last RESULT frame
 * arrived during the tap cooldown. Unknown/map frames are not evidence of that transition.
 */
internal fun labyrinthPostBossStageOnPage(
    current: LabyrinthPostBossStage,
    page: LabyrinthEntryPageState,
): LabyrinthPostBossStage = when (page) {
    LabyrinthEntryPageState.RUN_CLEAR_RESULT -> when (current) {
        LabyrinthPostBossStage.NONE, LabyrinthPostBossStage.BEFORE_SCORE,
        LabyrinthPostBossStage.CHEST_SEQUENCE -> LabyrinthPostBossStage.SCORE_RESULT
        else -> current
    }
    LabyrinthEntryPageState.RUN_CLEAR_CHEST_ANIMATION,
    LabyrinthEntryPageState.RUN_CLEAR_CHEST_RESULT,
    -> if (current == LabyrinthPostBossStage.SCORE_RESULT) LabyrinthPostBossStage.CHEST_SEQUENCE else current
    LabyrinthEntryPageState.ITEM_REWARD -> when (current) {
        LabyrinthPostBossStage.SCORE_RESULT,
        LabyrinthPostBossStage.CHEST_SEQUENCE -> LabyrinthPostBossStage.FINAL_ITEM_REWARD
        else -> current
    }
    else -> current
}

/**
 * The chest-result dialog (宝箱开封结果) is a centred modal with one 确认 button at the bottom
 * centre (1080p ≈ (958,958)), the same shape as the RESULT page's 关闭. The bottom-right point used
 * for the character summary's 下一步 misses it entirely.
 */
internal fun labyrinthChestResultFallbackTap(): LabyrinthFallbackTap = LabyrinthFallbackTap.BOTTOM_CENTER
