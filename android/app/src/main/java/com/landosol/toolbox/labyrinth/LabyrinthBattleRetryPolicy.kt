package com.landosol.toolbox.labyrinth

import com.landosol.toolbox.labyrinth.vision.LabyrinthBattleFailureLayout
import com.landosol.toolbox.labyrinth.vision.LabyrinthBattleFailureObservation

internal fun labyrinthBattleRetryLimit(kind: LabyrinthCombatKind): Int = when (kind) {
    LabyrinthCombatKind.BOSS -> 1
    LabyrinthCombatKind.NORMAL,
    LabyrinthCombatKind.EX,
    -> 2
}

/**
 * Whether the retry-context stop conditions apply to a recognised battle-failure page.
 *
 * The blue "重新挑战" button of the three-button layout consumes the run's retry budget, so the
 * reroll-after-three-failures stop and the retry-limit / batch-limit stops belong to it. The
 * two-button layout's lower-right button reads "下一步": it advances the relic-revive chain, and
 * rerolling or ending the run there would throw away the run that was just revived. See
 * `android/docs/relic-revive-battle-failure-audit.md`, section 「3. 实现级设计」.
 */
internal fun labyrinthBattleFailureUsesRetryBudget(layout: LabyrinthBattleFailureLayout): Boolean =
    layout != LabyrinthBattleFailureLayout.TWO_BUTTON

/**
 * The post-entry tap a recognised battle-failure page offers.
 *
 * Both layouts expose the same lower-right blue button and therefore the same rect; only the kind
 * differs, and with it the accounting in `dispatchPostEntryTap`. Extracted so both layouts stay
 * pinned by tests instead of by reading the plan branch.
 */
internal fun labyrinthBattleFailureTapPlan(
    failure: LabyrinthBattleFailureObservation,
    pendingSingleBossFallbackToMulti: Boolean,
): LabyrinthPostEntryTapPlan = when (failure.layout) {
    LabyrinthBattleFailureLayout.TWO_BUTTON -> LabyrinthPostEntryTapPlan(
        LabyrinthPostEntryActionKind.BATTLE_FAILURE_NEXT,
        "战斗失败页(遗物复活)：下一步",
        failure.retryButtonRect,
    )
    LabyrinthBattleFailureLayout.THREE_BUTTON -> if (pendingSingleBossFallbackToMulti) {
        LabyrinthPostEntryTapPlan(
            LabyrinthPostEntryActionKind.BATTLE_RETRY_SWITCH_MULTI,
            "Boss单队达到设定重试次数：切换多队重新挑战",
            failure.retryButtonRect,
        )
    } else {
        LabyrinthPostEntryTapPlan(
            LabyrinthPostEntryActionKind.BATTLE_RETRY,
            "战斗失败：重新挑战",
            failure.retryButtonRect,
        )
    }
}

/** Number of automatic retry taps allowed before the current failed page becomes terminal. */
internal fun labyrinthEffectiveBattleRetryLimit(
    kind: LabyrinthCombatKind,
    rerollAfterThreeFailures: Boolean,
    singleBossFallbackRetryCount: Int? = null,
): Int = maxOf(
    labyrinthBattleRetryLimit(kind),
    if (rerollAfterThreeFailures) 2 else 0,
    if (kind == LabyrinthCombatKind.BOSS) singleBossFallbackRetryCount ?: 0 else 0,
)

/** battleRetryCount counts successful "重新挑战" taps; retryCount=2 means the third attempt failed. */
internal fun labyrinthShouldSwitchSingleBossToMulti(
    enabled: Boolean,
    kind: LabyrinthCombatKind,
    mode: LabyrinthBossTeamMode,
    battleRetryCount: Int,
    retryCountBeforeMulti: Int = 2,
): Boolean = enabled &&
    kind == LabyrinthCombatKind.BOSS &&
    mode == LabyrinthBossTeamMode.SINGLE_TEAM &&
    battleRetryCount >= retryCountBeforeMulti

internal fun labyrinthShouldRerollAfterBattleFailure(
    rerollAfterThreeFailures: Boolean,
    battleRetryCount: Int,
): Boolean = rerollAfterThreeFailures && battleRetryCount >= 2

/**
 * A batch-owned run that exhausts its retries is a formal FAILED_MAX_RETRY, never a stop on the
 * failure page. 2026-09-17 live (batch 20260917-031313 run001): 「刷开局」 was off in the
 * strategy, so the EX retry limit fell into the interactive "保留在失败页" stop, the batch
 * received FatalUnknown and halted FAILED with the game parked on 战斗失败. The batch owns
 * the reroll and the failure-page invalidation, so the run only has to end with the right
 * outcome; the strategy toggle governs interactive runs alone.
 */
internal fun labyrinthBatchOwnedRunEndsAtRetryLimit(
    batchOwnsRun: Boolean,
    battleRetryCount: Int,
    retryLimit: Int,
): Boolean = batchOwnsRun && battleRetryCount >= retryLimit
