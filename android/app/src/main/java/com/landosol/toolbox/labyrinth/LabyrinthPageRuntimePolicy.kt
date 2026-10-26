package com.landosol.toolbox.labyrinth

import com.landosol.toolbox.labyrinth.vision.EntryAnchorId
import com.landosol.toolbox.labyrinth.vision.EntryPixelRect
import com.landosol.toolbox.labyrinth.vision.LabyrinthEntryFrameResult
import com.landosol.toolbox.labyrinth.vision.LabyrinthEntryPageState

/** Exactly one page owns page-local actions, plans and overlay boxes for each recognized frame. */
internal enum class LabyrinthPageUiOwner {
    NONE,
    NODE_SELECTION,
    NODE_MAP_VIEW,
    CHARACTER_SELECTION,
    CHARACTER_JOINED,
    BATTLE_TEAM_SELECTION,
    LINK_CHOICE,
    RELIC_CHOICE,
    EVENT_CHOICE,
    SHOP,
    BATTLE,
    RUN_RESULT,
}

internal fun labyrinthPageUiOwner(page: LabyrinthEntryPageState): LabyrinthPageUiOwner = when (page) {
    LabyrinthEntryPageState.NODE_SELECTION -> LabyrinthPageUiOwner.NODE_SELECTION
    LabyrinthEntryPageState.NODE_MAP_VIEW -> LabyrinthPageUiOwner.NODE_MAP_VIEW
    LabyrinthEntryPageState.INITIAL_CHARACTER_SELECTION -> LabyrinthPageUiOwner.CHARACTER_SELECTION
    LabyrinthEntryPageState.CHARACTER_JOINED -> LabyrinthPageUiOwner.CHARACTER_JOINED
    LabyrinthEntryPageState.BATTLE_TEAM_SELECTION -> LabyrinthPageUiOwner.BATTLE_TEAM_SELECTION
    LabyrinthEntryPageState.LINK_CHOICE -> LabyrinthPageUiOwner.LINK_CHOICE
    LabyrinthEntryPageState.RELIC_CHOICE -> LabyrinthPageUiOwner.RELIC_CHOICE
    LabyrinthEntryPageState.EVENT_CHOICE,
    LabyrinthEntryPageState.EVENT_ANIMATION,
    -> LabyrinthPageUiOwner.EVENT_CHOICE
    LabyrinthEntryPageState.SHOP,
    LabyrinthEntryPageState.SHOP_PURCHASE_CONFIRMATION,
    LabyrinthEntryPageState.SHOP_PURCHASE_COMPLETE,
    LabyrinthEntryPageState.SHOP_EXIT_CONFIRMATION,
    -> LabyrinthPageUiOwner.SHOP
    LabyrinthEntryPageState.BATTLE_CHALLENGE,
    LabyrinthEntryPageState.BATTLE_IN_PROGRESS,
    LabyrinthEntryPageState.BATTLE_FAILED,
    LabyrinthEntryPageState.BATTLE_RESULT,
    -> LabyrinthPageUiOwner.BATTLE
    LabyrinthEntryPageState.RUN_CLEAR_RESULT,
    LabyrinthEntryPageState.RUN_CLEAR_CONGRATULATIONS,
    LabyrinthEntryPageState.RUN_CLEAR_CHARACTER_SUMMARY,
    LabyrinthEntryPageState.RUN_CLEAR_REWARD_ANIMATION,
    LabyrinthEntryPageState.RUN_CLEAR_CHEST_ANIMATION,
    LabyrinthEntryPageState.RUN_CLEAR_CHEST_RESULT,
    -> LabyrinthPageUiOwner.RUN_RESULT
    else -> LabyrinthPageUiOwner.NONE
}

/**
 * A plain one-button 确认 dialog over the map: blue title, white body, one pale 确认 button
 * bottom-centre and nothing else (2026-09-17: 「无法获得报酬」 after an event). It has no page
 * state of its own, so it reads as UNKNOWN and used to stall the run. The 确认 button is the
 * same artwork as the date-change popup's, so its template is reused; every other dialog family
 * that shares the chrome is excluded explicitly: the date-change popup (its title), the session
 * expiry popup (错误提示 title / 返回标题 button), movement and battle-end dialogs (two or three
 * buttons, structurally detected).
 */
internal fun labyrinthGenericConfirmDialogRect(
    result: LabyrinthEntryFrameResult,
    minimumButtonScore: Double = 0.85,
): EntryPixelRect? {
    // 「特别装备」的两个弹窗有自己的处理分支，而且弹窗2 的蓝色「确认」与这里共享的按钮模板互相
    // 干扰：压暗的编组页可能被判成「单按钮确认弹窗」，把点击派发到弹窗2「确认」的位置上，从而
    // 绕过「先弹窗2 再判弹窗1」那条判态顺序。窄排除：只在观测确实给出弹窗 stage 时
    // 生效；stage 为 null（真实的会话失效弹窗、事件后的单按钮确认弹窗）时本函数逐字不变。
    if (result.specialEquipment?.stage != null) return null
    // 「遗物效果结果」 proves its own identity, so it is resolved before any of the guards below
    // and regardless of which page won the classifier.
    //
    // Those guards exist only to separate dialogs whose *sole* evidence is the shared 确认 button
    // artwork. This popup instead scores 0.81 on that button and 0.62 on 错误提示's blue bar, so
    // without an identity of its own it was read as an expired account session and the route
    // stopped (upstream report 2026-09-23, v1.0.9). The same frame put SESSION_RETURN_TITLE's ROI
    // directly over this 关闭 at 0.434 against a 0.45 trigger: one frame of jitter and the run
    // taps it and keeps going as though it were logged out, discarding the whole run.
    val relicEffectResultClose = result.anchorMatches[EntryAnchorId.RELIC_EFFECT_RESULT_CLOSE]
    if (
        result.observation.anchorScores[EntryAnchorId.RELIC_EFFECT_RESULT_TITLE] >= 0.80 &&
        relicEffectResultClose != null &&
        relicEffectResultClose.score >= minimumButtonScore
    ) {
        return relicEffectResultClose.rect
    }
    // The dialog sits over the map; the map behind it can still win the page classifier
    // (2026-09-18 live: NODE_SELECTION with the dialog open). Pages with their own dialogs
    // (shop, battle, roster) are excluded; the map family and UNKNOWN are eligible.
    if (result.observation.state !in GENERIC_CONFIRM_DIALOG_PAGES) return null
    if (result.nodeMoveConfirmation != null || result.battleEndConfirmation != null) return null
    val scores = result.observation.anchorScores
    if (scores[EntryAnchorId.SESSION_DATE_CHANGE_TITLE] >= 0.60) return null
    // The blue title bar is shared with the 错误提示 popup and scores 0.6+ here, so it cannot
    // discriminate. The button does: 返回标题 is blue, this 确认 is pale.
    if (scores[EntryAnchorId.SESSION_RETURN_TITLE] >= 0.45) return null
    val button = result.anchorMatches[EntryAnchorId.SESSION_DATE_CHANGE_CONFIRM] ?: return null
    return button.rect.takeIf { button.score >= minimumButtonScore }
}

/**
 * Consecutive frames that actually resolved a 返回标题 rect.
 *
 * The session used to gate the tap on how long it had been blocked, which counts the stretches where
 * no button is recognised at all and the run just reports 等待识别“返回标题”按钮. That primes the
 * counter past the floor, so the first frame whose score happens to clear the trigger taps at once,
 * unconfirmed. Measured 2026-09-23 on the labyrinth 商店 page: its white panel and bottom-right 关闭
 * made session.return.title spike to 0.646 (and 0.524) on single frames, above the 0.45 trigger,
 * while the page was already classified as an expiry block. A genuine expiry popup renders its
 * button continuously, so demanding consecutive button-bearing frames delays real recovery by nothing.
 */
internal fun labyrinthSessionReturnTitleStreak(
    previousStreak: Int,
    returnTitleRectPresent: Boolean,
): Int = if (returnTitleRectPresent) previousStreak + 1 else 0

private val GENERIC_CONFIRM_DIALOG_PAGES = setOf(
    LabyrinthEntryPageState.UNKNOWN,
    LabyrinthEntryPageState.NODE_SELECTION,
    LabyrinthEntryPageState.NODE_MAP_VIEW,
    LabyrinthEntryPageState.EVENT_CHOICE,
    LabyrinthEntryPageState.EVENT_ANIMATION,
)
