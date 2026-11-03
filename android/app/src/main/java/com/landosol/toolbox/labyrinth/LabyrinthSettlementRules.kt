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
 * Stage to adopt when the final RESULT page is recognized.
 *
 * The final Boss usually completes the route first, which arms BEFORE_SCORE; the RESULT page
 * then arrives on top of that. The old transition only accepted NONE → SCORE_RESULT, so a run
 * that had already armed BEFORE_SCORE never gained a close-button plan for RESULT and spent its
 * whole animation budget tapping blind fallback points instead (2026-09-17 live: 402 taps,
 * "RUN_CLEAR_RESULT：暂不自动处理"). Any pre-score stage may enter SCORE_RESULT; later stages
 * must not regress.
 */
internal fun labyrinthPostBossStageOnFinalResult(current: LabyrinthPostBossStage): LabyrinthPostBossStage =
    when (current) {
        LabyrinthPostBossStage.NONE,
        LabyrinthPostBossStage.BEFORE_SCORE,
        // The first tap on 关闭 only skips the score count-up animation and the RESULT page stays
        // visible (2026-09-17 02:08 device log: one anchor-centre tap, page unchanged, then no
        // plan). A visible RESULT page is the score stage by definition; the chest sequence has
        // not started until RESULT is gone.
        LabyrinthPostBossStage.CHEST_SEQUENCE,
        -> LabyrinthPostBossStage.SCORE_RESULT
        else -> current
    }

/**
 * The chest-result dialog (宝箱开封结果) is a centred modal with one 确认 button at the bottom
 * centre (1080p ≈ (958,958)), the same shape as the RESULT page's 关闭. The bottom-right point used
 * for the character summary's 下一步 misses it entirely.
 */
internal fun labyrinthChestResultFallbackTap(): LabyrinthFallbackTap = LabyrinthFallbackTap.BOTTOM_CENTER
