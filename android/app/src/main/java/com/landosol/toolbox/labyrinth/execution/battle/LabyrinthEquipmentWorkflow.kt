package com.landosol.toolbox.labyrinth.execution.battle

import com.landosol.toolbox.labyrinth.*
import com.landosol.toolbox.automation.AutomationActionResult
import com.landosol.toolbox.automation.AutomationSessionId
import com.landosol.toolbox.automation.SessionBoundActionExecutor
import com.landosol.toolbox.automation.AutomationAction
import com.landosol.toolbox.automation.ScreenPoint
import com.landosol.toolbox.automation.accessibility.GAME_NOT_FOREGROUND_REASON
import com.landosol.toolbox.labyrinth.vision.EntryPixelRect
import com.landosol.toolbox.labyrinth.vision.LabyrinthEntryFrameResult
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow

internal interface LabyrinthEquipmentPort {
    val _state: MutableStateFlow<LabyrinthEntryRecognitionSessionState>
    val actionExecutor: SessionBoundActionExecutor?
    val activeSessionId: AutomationSessionId?
    val battleTeamDispatchSequence: AtomicLong
    val bossTeamMode: LabyrinthBossTeamMode
    val combatContext: LabyrinthCombatContext?
    val lastNormalBattleTeamSignature: String?
    var lastSessionActionAt: Long
    val specialEquipmentFlow: LabyrinthSpecialEquipmentFlow
    val specialEquipmentSettings: LabyrinthStrategySettings?
    fun beginGuardedAction(): Boolean
    fun finishFromPlanner(sessionId: AutomationSessionId, reason: String): Unit
    fun launchGuardedAction(block: suspend () -> Unit): Unit
    fun publishOverlay(sessionId: AutomationSessionId): Unit
    fun publishWaitingForGameForeground(sessionId: AutomationSessionId): Unit
    fun sessionActionIntervalElapsed(timestampMillis: Long): Boolean
    suspend fun stopFromRunLogic(reason: String): Boolean
    fun traceAction(label: String): Unit
    fun traceWorkflow(owner: String, phase: String): Unit
}

/** 连接特别装备状态机与会话动作准入；派发与画面确认仍分开。 */
internal class LabyrinthEquipmentWorkflow(private val port: LabyrinthEquipmentPort) : LabyrinthEquipmentPort by port {
    fun resetSpecialEquipmentRound() = specialEquipmentFlow.reset()

    /** Keep the session gate and action execution here; the flow owns its round state. */
    fun handleSpecialEquipmentFlowFrame(
        sessionId: AutomationSessionId,
        result: LabyrinthEntryFrameResult,
        timestampMillis: Long,
    ): Boolean {
        if (activeSessionId != sessionId) return false
        val kind = combatContext?.kind
        return specialEquipmentFlow.onFrame(
            result = result,
            timestampMillis = timestampMillis,
            context = LabyrinthSpecialEquipmentContext(
                appliesToBattle = kind != null && specialEquipmentSettings?.autoEquipsSpecialEquipment(kind) == true,
                multiTeamBoss = kind == LabyrinthCombatKind.BOSS && bossTeamMode == LabyrinthBossTeamMode.MULTI_TEAM,
                normalBattle = kind == LabyrinthCombatKind.NORMAL,
                teamReady = _state.value.battleTeamSelectionPlan?.teamReady == true,
                previousNormalTeamSignature = lastNormalBattleTeamSignature,
                actionIntervalElapsed = sessionActionIntervalElapsed(timestampMillis),
            ),
            effects = object : LabyrinthSpecialEquipmentEffects {
                override fun tryDispatch(rect: EntryPixelRect, label: String): Boolean =
                    dispatchSpecialEquipmentAction(sessionId, rect, label, timestampMillis)

                override fun publishMessage(message: String) {
                    if (_state.value.message != message) _state.value = _state.value.copy(message = message)
                }

                override fun stop(reason: String) = finishFromPlanner(sessionId, reason)
            },
        ).ownsFrame.also { ownsFrame ->
            if (ownsFrame) traceWorkflow("equipment", "modal")
        }
    }

    /**
     * 派发特别装备流程的一次点击。与 [dispatchBattleTeamAction] 同构（同一把单动作锁、同样的前台与
     * 拒绝处置），但**用自己的时间戳与计数**，不触碰编组执行步的 key/attempts 语义。
     *
     * 动作坐标一律取「决策选中的按钮身份」在本帧观测里的矩形中心（[labyrinthSpecialEquipmentTapRect]），
     * 因此不存在写死坐标，也不存在把「确认」派发到「装备确定」上的路径。
     */
    fun dispatchSpecialEquipmentAction(
        sessionId: AutomationSessionId,
        rect: EntryPixelRect,
        label: String,
        timestampMillis: Long,
    ): Boolean {
        if (!beginGuardedAction()) return false
        battleTeamDispatchSequence.incrementAndGet()
        traceAction(label)
        lastSessionActionAt = timestampMillis
        launchGuardedAction {
            val executor = actionExecutor
            val action = AutomationAction.Tap(
                ScreenPoint(rect.left + rect.width / 2f, rect.top + rect.height / 2f),
            )
            val actionResult = if (executor == null) {
                AutomationActionResult.Rejected(action, "动作执行器不可用")
            } else {
                executor.execute(sessionId, action)
            }
            when (actionResult) {
                is AutomationActionResult.Executed -> if (activeSessionId == sessionId) {
                    val current = _state.value
                    _state.value = current.copy(
                        actionCount = current.actionCount + 1,
                        lastActionLabel = label,
                        message = "已执行：$label；等待画面确认",
                    )
                    publishOverlay(sessionId)
                }
                is AutomationActionResult.Rejected -> {
                    if (actionResult.reason == GAME_NOT_FOREGROUND_REASON) {
                        // 这一下没有落到游戏上，不算一次真实点击：丢掉节流戳，回前台后可以立刻重试
                        // （与编组执行步被拒时的处置一致）。
                        lastSessionActionAt = Long.MIN_VALUE
                        publishWaitingForGameForeground(sessionId)
                    } else {
                        stopFromRunLogic("特别装备动作被拒绝：${actionResult.reason}")
                    }
                }
                AutomationActionResult.StaleSession -> Unit
                AutomationActionResult.Paused -> Unit
                is AutomationActionResult.DryRun -> Unit
            }
        }
        return true
    }
}
