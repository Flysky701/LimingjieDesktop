package com.landosol.toolbox.labyrinth.execution.runtime

import com.landosol.toolbox.labyrinth.*
import com.landosol.toolbox.automation.AutomationActionResult
import com.landosol.toolbox.automation.AutomationSessionId
import com.landosol.toolbox.automation.SessionBoundActionExecutor
import com.landosol.toolbox.automation.AutomationAction
import com.landosol.toolbox.automation.ScreenPoint
import com.landosol.toolbox.automation.accessibility.GAME_NOT_FOREGROUND_REASON
import com.landosol.toolbox.labyrinth.vision.EntryPixelRect
import com.landosol.toolbox.labyrinth.vision.LabyrinthEntryFrameResult
import kotlinx.coroutines.flow.MutableStateFlow
import com.landosol.toolbox.labyrinth.LabyrinthExecutionLimits.POST_ENTRY_ANCHOR_MIN_SCORE

internal interface LabyrinthPageActionPort {
    val _state: MutableStateFlow<LabyrinthEntryRecognitionSessionState>
    val actionExecutor: SessionBoundActionExecutor?
    val activeSessionId: AutomationSessionId?
    val clock: () -> Long
    var lastPostEntryActionAt: Long
    var lastPostEntryActionLandedAt: Long
    var pendingAcquiredCharacterId: String?
    var postEntryAttempts: Int
    fun beginGuardedAction(): Boolean
    fun captureRoleRewardTap(roleRewardSelectedCharacterId: String?): LabyrinthRoleRewardTapContext
    fun clearPlannedShopPurchase(): Unit
    fun launchGuardedAction(block: suspend () -> Unit): Unit
    fun onBattleTapExecuted(kind: LabyrinthPostEntryActionKind): Unit
    fun onEncounterTapExecuted(kind: LabyrinthPostEntryActionKind): Unit
    fun onEncounterTapRejected(kind: LabyrinthPostEntryActionKind): Unit
    fun onEventTapDispatched(kind: LabyrinthPostEntryActionKind): Unit
    fun onEventTapExecuted(kind: LabyrinthPostEntryActionKind, eventFreeRoleCandidateId: String?, eventFreeRoleConfirm: Boolean, eventFreeRoleConfirmIds: List<String>): Unit
    fun onRewardPageClosed(kind: LabyrinthPostEntryActionKind, timestampMillis: Long): Unit
    fun onRewardTapDispatched(kind: LabyrinthPostEntryActionKind, timestampMillis: Long): Unit
    suspend fun onRewardTapExecuted(sessionId: AutomationSessionId, kind: LabyrinthPostEntryActionKind, roleRewardSelectionSignature: String?, roleRewardSelectedCharacterId: String?, context: LabyrinthRoleRewardTapContext, rect: EntryPixelRect): Unit
    fun onRewardTapNotForeground(kind: LabyrinthPostEntryActionKind): Unit
    fun onSettlementTapDispatched(kind: LabyrinthPostEntryActionKind): Unit
    fun onSettlementTapExecuted(kind: LabyrinthPostEntryActionKind, timestampMillis: Long): Unit
    fun onSettlementTapNotForeground(kind: LabyrinthPostEntryActionKind): Unit
    fun onShopImprintTapExecuted(kind: LabyrinthPostEntryActionKind): Unit
    fun onShopTapDispatched(shopPurchaseRelicId: String?, shopPurchaseRoleImprintLabel: String?, timestampMillis: Long): Unit
    fun onShopTapExecuted(kind: LabyrinthPostEntryActionKind): Unit
    fun publishOverlay(sessionId: AutomationSessionId): Unit
    fun publishWaitingForGameForeground(sessionId: AutomationSessionId): Unit
    fun resetPostEntryStall(): Unit
    suspend fun stopFromRunLogic(reason: String): Boolean
    fun traceAction(label: String): Unit
}

/** 统一派发页面点击，将执行器回执交回对应业务模块。 */
internal class LabyrinthPageActionWorkflow(private val port: LabyrinthPageActionPort) : LabyrinthPageActionPort by port {
    fun dispatchPostEntryTap(
        sessionId: AutomationSessionId,
        kind: LabyrinthPostEntryActionKind,
        label: String,
        rect: EntryPixelRect,
        timestampMillis: Long,
        roleRewardSelectionSignature: String? = null,
        roleRewardSelectedCharacterId: String? = null,
        shopPurchaseRelicId: String? = null,
        shopPurchaseRoleImprintLabel: String? = null,
        eventFreeRoleCandidateId: String? = null,
        eventFreeRoleConfirm: Boolean = false,
        eventFreeRoleConfirmIds: List<String> = emptyList(),
    ) {
        if (!beginGuardedAction()) return
        val rewardContext = captureRoleRewardTap(roleRewardSelectedCharacterId)
        onShopTapDispatched(shopPurchaseRelicId, shopPurchaseRoleImprintLabel, timestampMillis)
        traceAction(label)
        resetPostEntryStall()
        postEntryAttempts++
        lastPostEntryActionAt = timestampMillis
        onEventTapDispatched(kind)
        onRewardTapDispatched(kind, timestampMillis)
        onSettlementTapDispatched(kind)
        launchGuardedAction {
            val executor = actionExecutor
            val tap = AutomationAction.Tap(
                ScreenPoint(
                    x = rect.left + rect.width / 2f,
                    y = rect.top + rect.height / 2f,
                ),
            )
            val actionResult = if (executor == null) {
                AutomationActionResult.Rejected(tap, "动作执行器不可用")
            } else {
                executor.execute(sessionId, tap)
            }
            when (actionResult) {
                is AutomationActionResult.Executed -> {
                    lastPostEntryActionLandedAt = clock()
                    onEventTapExecuted(kind, eventFreeRoleCandidateId, eventFreeRoleConfirm, eventFreeRoleConfirmIds)
                    onRewardTapExecuted(sessionId, kind, roleRewardSelectionSignature, roleRewardSelectedCharacterId, rewardContext, rect)
                    onShopImprintTapExecuted(kind)
                    onBattleTapExecuted(kind)
                    onShopTapExecuted(kind)
                    onSettlementTapExecuted(kind, timestampMillis)
                    onEncounterTapExecuted(kind)
                    onRewardPageClosed(kind, timestampMillis)
                    if (activeSessionId == sessionId) {
                        val current = _state.value
                        _state.value = current.copy(
                            actionCount = current.actionCount + 1,
                            lastActionLabel = label,
                            pendingAcquiredCharacterId = pendingAcquiredCharacterId,
                            message = "已执行：$label",
                        )
                        publishOverlay(sessionId)
                    }
                }

                is AutomationActionResult.Rejected -> {
                    if (shopPurchaseRelicId != null) clearPlannedShopPurchase()
                    onEncounterTapRejected(kind)
                    if (actionResult.reason == GAME_NOT_FOREGROUND_REASON) {
                        postEntryAttempts = (postEntryAttempts - 1).coerceAtLeast(0)
                        onRewardTapNotForeground(kind)
                        onSettlementTapNotForeground(kind)
                        publishWaitingForGameForeground(sessionId)
                    } else {
                        stopFromRunLogic("点击被拒绝：${actionResult.reason}")
                    }
                }
                AutomationActionResult.StaleSession -> Unit
                AutomationActionResult.Paused -> Unit
                is AutomationActionResult.DryRun -> Unit
            }
        }
    }

    fun anchorRect(result: LabyrinthEntryFrameResult, anchorId: String): EntryPixelRect? =
        result.anchorMatches[anchorId]
            ?.takeIf { it.score >= POST_ENTRY_ANCHOR_MIN_SCORE }
            ?.rect
}
