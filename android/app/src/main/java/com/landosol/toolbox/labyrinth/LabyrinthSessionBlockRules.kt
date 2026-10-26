package com.landosol.toolbox.labyrinth

import com.landosol.toolbox.automation.session.SessionBlockClassifier
import com.landosol.toolbox.automation.session.SessionBlockKind
import com.landosol.toolbox.automation.session.toSessionBlockScores
import com.landosol.toolbox.labyrinth.vision.EntryAnchorId
import com.landosol.toolbox.labyrinth.vision.EntryPixelRect
import com.landosol.toolbox.labyrinth.vision.LabyrinthEntryFrameResult
import com.landosol.toolbox.labyrinth.vision.LabyrinthEntryPageState

/** A session popup always takes priority over the normal page classifier and route actions. */
internal data class LabyrinthSessionBlockObservation(
    val kind: SessionBlockKind,
    val returnTitleRect: EntryPixelRect?,
    val actionLabel: String = "返回标题",
) {
    val blocksNormalActions: Boolean get() = kind != SessionBlockKind.NONE
}

internal fun labyrinthSessionBlockObservation(
    result: LabyrinthEntryFrameResult,
    classifier: SessionBlockClassifier = SessionBlockClassifier(),
): LabyrinthSessionBlockObservation {
    // 「特别装备」的两个弹窗是本特性的新 chrome，却与这里「故意做宽」的蓝标题模板同壳：
    // 一旦某帧把它读成会话失效，自动化会去点「返回标题」并把整局丢掉。
    // 窄排除：只在观测确实给出弹窗 stage（≥0.70 的模板证据）时生效；stage 为 null 时——包括
    // 真实的过期/掉线弹窗——本函数逐字不变（反向断言见 LabyrinthSpecialEquipmentSessionFlowTest）。
    if (result.specialEquipment?.stage != null) {
        return LabyrinthSessionBlockObservation(SessionBlockKind.NONE, returnTitleRect = null)
    }
    // The reconnect detector uses a deliberately broad blue-title template. A normal labyrinth
    // movement dialog shares that chrome, so its more specific two-button detector must win.
    if (result.nodeMoveConfirmation != null) {
        return LabyrinthSessionBlockObservation(SessionBlockKind.NONE, returnTitleRect = null)
    }
    // A plain one-button 确认 dialog (无法获得报酬) shares the title chrome too. 2026-09-18 live:
    // it read as a session block and the run waited 255 actions for a 返回标题 that never came.
    if (labyrinthGenericConfirmDialogRect(result) != null) {
        return LabyrinthSessionBlockObservation(SessionBlockKind.NONE, returnTitleRect = null)
    }
    val dateChangeTitleScore =
        result.observation.anchorScores[EntryAnchorId.SESSION_DATE_CHANGE_TITLE]
    if (dateChangeTitleScore >= SESSION_DATE_CHANGE_TITLE_MIN_SCORE) {
        val confirmRect = result.anchorMatches[EntryAnchorId.SESSION_DATE_CHANGE_CONFIRM]
            ?.takeIf { it.score >= SESSION_DATE_CHANGE_CONFIRM_MIN_SCORE }
            ?.rect
        return LabyrinthSessionBlockObservation(
            kind = SessionBlockKind.RELOGIN_REQUIRED,
            returnTitleRect = confirmRect,
            actionLabel = "确认",
        )
    }
    // The shop's own dialogs (purchase confirmation / purchase complete / exit confirmation)
    // reuse the blue title bar the broad reconnect template was cut from, and a transition frame
    // between them classifies UNKNOWN while the shop chrome stays on screen. 2026-09-18 live:
    // every purchase produced "检测到账号会话失效". Without a recognised 返回标题 button there is
    // nothing to act on anyway; a genuine expiry inside the shop still shows that button.
    if (
        labyrinthShopBackgroundVisible(result) &&
        result.observation.anchorScores[EntryAnchorId.SESSION_RETURN_TITLE] < SESSION_RETURN_TITLE_MIN_SCORE
    ) {
        return LabyrinthSessionBlockObservation(SessionBlockKind.NONE, returnTitleRect = null)
    }
    val kind = classifier.classify(
        scores = result.observation.anchorScores.toSessionBlockScores(),
        pageTitleScore = result.observation.stateScores[LabyrinthEntryPageState.TITLE_WAITING_TAP] ?: 0.0,
    )
    val returnTitleRect = if (
        kind == SessionBlockKind.RECONNECT_PROMPTED || kind == SessionBlockKind.RELOGIN_REQUIRED
    ) {
        result.anchorMatches[EntryAnchorId.SESSION_RETURN_TITLE]
            ?.takeIf { it.score >= SESSION_RETURN_TITLE_MIN_SCORE }
            ?.rect
    } else {
        null
    }
    return LabyrinthSessionBlockObservation(kind, returnTitleRect)
}

private const val SESSION_RETURN_TITLE_MIN_SCORE = 0.45

private const val SESSION_DATE_CHANGE_TITLE_MIN_SCORE = 0.72

private const val SESSION_DATE_CHANGE_CONFIRM_MIN_SCORE = 0.55
