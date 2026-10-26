package com.landosol.toolbox.labyrinth.execution.entry

import com.landosol.toolbox.labyrinth.*
import com.landosol.toolbox.automation.AutomationActionResult
import com.landosol.toolbox.automation.AutomationSessionId
import com.landosol.toolbox.automation.SessionBoundActionExecutor
import com.landosol.toolbox.automation.accessibility.GAME_NOT_FOREGROUND_REASON
import com.landosol.toolbox.labyrinth.vision.LabyrinthEntryFrameResult
import com.landosol.toolbox.labyrinth.vision.LabyrinthEntryPageState
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow

internal interface LabyrinthEntryPort {
    val _state: MutableStateFlow<LabyrinthEntryRecognitionSessionState>
    val actionExecutor: SessionBoundActionExecutor?
    val actionInFlight: AtomicBoolean
    var actionPlanner: LabyrinthEntryActionPlanner?
    val actionPlannerFactory: () -> LabyrinthEntryActionPlanner
    val actionPlannerLock: Any
    val activeSessionId: AutomationSessionId?
    val clock: () -> Long
    val configuredOpeningRosters: Map<Int, List<List<String>>>
    var entryPhaseComplete: Boolean
    var existingRunResumeHandoffArmed: Boolean
    val roleDisplayNames: Map<String, String>
    val validatedRoute: LabyrinthRouteJson?
    fun beginGuardedAction(): Boolean
    fun finishFromPlanner(sessionId: AutomationSessionId, reason: String): Unit
    fun launchGuardedAction(block: suspend () -> Unit): Unit
    fun nodeExecutionConfigured(): Boolean
    fun publishOverlay(sessionId: AutomationSessionId): Unit
    fun publishWaitingForGameForeground(sessionId: AutomationSessionId): Unit
    suspend fun stopFromRunLogic(reason: String): Boolean
    fun traceAction(label: String): Unit
    fun traceWorkflow(owner: String, phase: String): Unit
}

/** 按入口规划器导航，并将已进入的局交接给路线执行。 */
internal class LabyrinthEntryWorkflow(private val port: LabyrinthEntryPort) : LabyrinthEntryPort by port {
    fun createActionPlanner(): LabyrinthEntryActionPlanner = actionPlannerFactory().also { planner ->
        planner.configureOpeningRoster(
            guildId = validatedRoute?.guildId,
            openingRosters = configuredOpeningRosters,
            displayNameFor = { id -> roleDisplayNames[id] },
        )
        planner.start(clock())
    }

    fun handleActionDecision(
        sessionId: AutomationSessionId,
        result: LabyrinthEntryFrameResult,
        frameWidth: Int,
        frameHeight: Int,
        timestampMillis: Long,
    ) {
        traceWorkflow("entry", "navigate")
        if (actionInFlight.get() || port.activeSessionId != sessionId) return
        val planner = actionPlanner ?: return
        val decision = synchronized(actionPlannerLock) {
            planner.decide(
                state = result.observation.state,
                frameWidth = frameWidth,
                frameHeight = frameHeight,
                nowMillis = timestampMillis,
                anchorScores = result.observation.anchorScores,
                anchorMatches = result.anchorMatches,
                openingCharacterMatches = result.openingCharacterMatches,
                openingCharacterSelection = result.openingCharacterSelection,
            )
        }
        when (decision) {
            is LabyrinthEntryActionDecision.Wait -> {
                if (port.activeSessionId == sessionId && _state.value.message != decision.reason) {
                    _state.value = _state.value.copy(message = decision.reason)
                }
            }
            is LabyrinthEntryActionDecision.Execute -> dispatchAction(sessionId, decision)
            is LabyrinthEntryActionDecision.Complete ->
                if (!nodeExecutionConfigured() && labyrinthCompletionWaitsWithoutRoute(decision.state)) {
                    // The run-clear settlement chain is owned by route execution. Without a
                    // configured route there is nobody to drive it, but stopping on the score
                    // page abandons a finished run mid-settlement. Stay put and say so.
                    _state.value = _state.value.copy(
                        message = "已到达通关结算页 ${decision.state.name}；未配置路线，保持在当前页不自动点击",
                    )
                } else if (nodeExecutionConfigured() && shouldHandOffToRouteExecution(decision.state)) {
                    // A live session can be started on the map or on a route-owned page. Hand it
                    // to the route executor instead of treating the entry planner as finished.
                    entryPhaseComplete = true
                    existingRunResumeHandoffArmed = false
                    _state.value = _state.value.copy(
                        message = if (decision.state == LabyrinthEntryPageState.NODE_SELECTION) {
                            "入口流程完成，开始按已保存路线执行"
                        } else {
                            "检测到中途运行页面 ${decision.state.name}，接管当前路线执行"
                        },
                    )
                } else {
                    finishFromPlanner(sessionId, decision.reason)
                }
            is LabyrinthEntryActionDecision.Stop -> finishFromPlanner(sessionId, decision.reason)
        }
    }

    fun dispatchAction(
        sessionId: AutomationSessionId,
        decision: LabyrinthEntryActionDecision.Execute,
    ) {
        if (!beginGuardedAction()) return
        traceAction(decision.label)
        launchGuardedAction {
            val executor = actionExecutor
            val result = if (executor == null) {
                AutomationActionResult.Rejected(decision.action, "动作执行器不可用")
            } else {
                executor.execute(sessionId, decision.action)
            }
            when (result) {
                is AutomationActionResult.Executed -> {
                    if (decision.kind == LabyrinthEntryActionKind.RESUME_DAWN_REALM) {
                        // The next page may be the node map, a choice page, or an unfinished
                        // CHARACTER_JOINED reward from before the app/session was restarted.
                        existingRunResumeHandoffArmed = true
                    }
                    if (port.activeSessionId == sessionId) {
                        val current = _state.value
                        _state.value = current.copy(
                            actionCount = current.actionCount + 1,
                            lastActionLabel = decision.label,
                            message = "已执行：${decision.label}",
                        )
                        publishOverlay(sessionId)
                    }
                }
                is AutomationActionResult.Rejected -> {
                    if (decision.kind == LabyrinthEntryActionKind.RESUME_DAWN_REALM) {
                        existingRunResumeHandoffArmed = false
                    }
                    if (result.reason == GAME_NOT_FOREGROUND_REASON) {
                        synchronized(actionPlannerLock) {
                            actionPlanner = createActionPlanner()
                        }
                        publishWaitingForGameForeground(sessionId)
                    } else {
                        stopFromRunLogic("点击被拒绝：${result.reason}")
                    }
                }
                AutomationActionResult.StaleSession -> Unit
                AutomationActionResult.Paused -> Unit
                is AutomationActionResult.DryRun -> Unit
            }
        }
    }

    fun shouldHandOffToRouteExecution(state: LabyrinthEntryPageState): Boolean = state in setOf(
        LabyrinthEntryPageState.NODE_SELECTION,
        LabyrinthEntryPageState.LINK_CHOICE,
        LabyrinthEntryPageState.RELIC_CHOICE,
        LabyrinthEntryPageState.EVENT_CHOICE,
        LabyrinthEntryPageState.EVENT_ANIMATION,
        LabyrinthEntryPageState.ITEM_REWARD,
        LabyrinthEntryPageState.SHOP,
        LabyrinthEntryPageState.SHOP_PURCHASE_CONFIRMATION,
        LabyrinthEntryPageState.SHOP_PURCHASE_COMPLETE,
        LabyrinthEntryPageState.SHOP_EXIT_CONFIRMATION,
        LabyrinthEntryPageState.BATTLE_CHALLENGE,
        LabyrinthEntryPageState.BATTLE_TEAM_SELECTION,
        LabyrinthEntryPageState.BATTLE_IN_PROGRESS,
        LabyrinthEntryPageState.BATTLE_RESULT,
        LabyrinthEntryPageState.RUN_CLEAR_RESULT,
        LabyrinthEntryPageState.RUN_CLEAR_CONGRATULATIONS,
        LabyrinthEntryPageState.RUN_CLEAR_CHARACTER_SUMMARY,
        LabyrinthEntryPageState.RUN_CLEAR_REWARD_ANIMATION,
        LabyrinthEntryPageState.RUN_CLEAR_CHEST_ANIMATION,
        LabyrinthEntryPageState.RUN_CLEAR_CHEST_RESULT,
    )
}
