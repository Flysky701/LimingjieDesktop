package com.landosol.toolbox.labyrinth.execution.runtime

import com.landosol.toolbox.labyrinth.*
import com.landosol.toolbox.automation.AutomationActionResult
import com.landosol.toolbox.automation.AutomationSessionId
import com.landosol.toolbox.automation.SessionBoundActionExecutor
import com.landosol.toolbox.automation.AutomationAction
import com.landosol.toolbox.automation.ScreenPoint
import com.landosol.toolbox.automation.accessibility.GAME_NOT_FOREGROUND_REASON
import com.landosol.toolbox.automation.session.SessionBlockClassifier
import com.landosol.toolbox.automation.session.SessionBlockKind
import com.landosol.toolbox.labyrinth.vision.EntryPixelRect
import com.landosol.toolbox.labyrinth.vision.LabyrinthEntryFrameResult
import com.landosol.toolbox.labyrinth.vision.LabyrinthEntryPageState
import com.landosol.toolbox.labyrinth.node.LabyrinthNodeViewportScanner
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import com.landosol.toolbox.labyrinth.LabyrinthExecutionLimits.CHANNEL_PROMPT_HOLD_MILLIS
import com.landosol.toolbox.labyrinth.LabyrinthExecutionLimits.CHANNEL_PROMPT_POLL_MILLIS
import com.landosol.toolbox.labyrinth.LabyrinthExecutionLimits.MAX_SESSION_RETURN_TITLE_ATTEMPTS
import com.landosol.toolbox.labyrinth.LabyrinthExecutionLimits.SESSION_BLOCK_STABLE_FRAMES
import com.landosol.toolbox.labyrinth.LabyrinthExecutionLimits.SESSION_RETURN_TITLE_ACTION_INTERVAL_MILLIS

internal interface LabyrinthRecoveryPort {
    val _state: MutableStateFlow<LabyrinthEntryRecognitionSessionState>
    val actionExecutor: SessionBoundActionExecutor?
    val actionInFlight: AtomicBoolean
    var actionInFlightSince: Long
    var actionPlanner: LabyrinthEntryActionPlanner?
    val actionPlannerLock: Any
    val actionScope: CoroutineScope
    var activeNodeArea: Int?
    var activeNodeType: Int?
    val activeSessionId: AutomationSessionId?
    val battleTeamRecommendationUnavailableReason: String?
    val battleWait: LabyrinthBattleWaitPolicy
    var channelPromptMessage: String?
    var channelPromptVisibleUntil: Long
    val channelPrompts: com.landosol.toolbox.automation.session.ChannelLoginPromptAssistant?
    val clock: () -> Long
    var combatContext: LabyrinthCombatContext?
    var entryPhaseComplete: Boolean
    var eventActionAttempts: Int
    var existingRunResumeHandoffArmed: Boolean
    var lastBattleTeamRecommendationKey: String?
    var lastBattleTeamSelectionPlanLog: String?
    var lastNodeActionAt: Long
    var lastNodeSwipeAvoidRects: List<EntryPixelRect>
    val lastObservedPageState: LabyrinthEntryPageState?
    var lastPostEntryActionAt: Long
    var lastPostEntryActionLandedAt: Long
    var lastPostEntryState: LabyrinthEntryPageState?
    var lastSessionBlockKind: SessionBlockKind
    var lastSessionReturnTitleActionAt: Long
    var nodeTapAttempts: Int
    val nodeViewportScanner: LabyrinthNodeViewportScanner
    var postEntryAttempts: Int
    var postEntryStableFrames: Int
    var postEntryUnknownSince: Long
    val sessionBlockClassifier: SessionBlockClassifier
    var sessionBlockStableFrames: Int
    var sessionReturnTitleAttempts: Int
    var sessionReturnTitleStableFrames: Int
    fun resetNodeErrorTracking()
    fun resetNodeScrollBudget()
    fun clearNodeScrollSourceTracking()
    fun beginGuardedAction(): Boolean
    fun clearCharacterAcquisitionContext(): Unit
    fun clearRoleRewardBatchTracking(): Unit
    fun createActionPlanner(): LabyrinthEntryActionPlanner
    fun endGuardedAction(): Unit
    fun launchGuardedAction(block: suspend () -> Unit): Unit
    fun publishOverlay(sessionId: AutomationSessionId): Unit
    fun publishWaitingForGameForeground(sessionId: AutomationSessionId): Unit
    fun clearPendingNodeTransition()
    fun resetNodeEntryMismatchTracking()
    fun resetNodeMoveConfirmationTracking(): Unit
    fun resetNodeRecoveryNudgeTracking(): Unit
    fun resetNodeScrollSearchGate(): Unit
    fun resetOrphanMoveConfirmationTracking(): Unit
    fun resetPendingNodeClickStability(): Unit
    fun resetPostEntryStall(): Unit
    fun resetStrongRouteTargetBinding(): Unit
    suspend fun stopFromRunLogic(reason: String): Boolean
    fun traceAction(label: String): Unit
}

/** 管理会话阻断与渠道登录弹窗；恢复策略和预算保持独立。 */
internal class LabyrinthRecoveryWorkflow(private val port: LabyrinthRecoveryPort) : LabyrinthRecoveryPort by port {
    /**
     * Handles account-session interruption before normal page actions. The popup can retain a
     * recognizable HOME page behind it, so allowing the regular planner to run here would tap
     * through the modal instead of returning to the title screen.
     */
    fun handleSessionBlockFrame(
        sessionId: AutomationSessionId,
        result: LabyrinthEntryFrameResult,
        frameWidth: Int,
        frameHeight: Int,
        timestampMillis: Long,
        dryRun: Boolean,
    ): Boolean {
        if (
            labyrinthShopTransitionSuppressesSessionBlock(
                pageState = result.observation.state,
                previousPageState = lastObservedPageState,
                activeNodeType = activeNodeType,
                lastActionAt = lastPostEntryActionAt,
                now = timestampMillis,
            )
        ) {
            resetSessionRecoveryTracking()
            return false
        }
        val block = labyrinthSessionBlockObservation(result, sessionBlockClassifier)
        if (!block.blocksNormalActions) {
            resetSessionRecoveryTracking()
            return false
        }
        battleWait.reset()
        if (lastSessionBlockKind != block.kind) {
            lastSessionBlockKind = block.kind
            sessionBlockStableFrames = 0
            sessionReturnTitleStableFrames = 0
            sessionReturnTitleAttempts = 0
            lastSessionReturnTitleActionAt = Long.MIN_VALUE
        }
        sessionBlockStableFrames++
        sessionReturnTitleStableFrames = labyrinthSessionReturnTitleStreak(
            previousStreak = sessionReturnTitleStableFrames,
            returnTitleRectPresent = block.returnTitleRect != null,
        )

        val message = when {
            dryRun -> "检测到账号会话失效；只读模式不会点击${block.actionLabel}"
            block.kind == SessionBlockKind.UNKNOWN_PROMPT -> "检测到未知会话弹窗，已暂停普通点击"
            block.returnTitleRect == null -> "检测到账号会话失效，等待识别“${block.actionLabel}”按钮"
            sessionReturnTitleStableFrames < SESSION_BLOCK_STABLE_FRAMES ->
                "检测到账号会话失效，正在确认“${block.actionLabel}”按钮"
            sessionReturnTitleAttempts >= MAX_SESSION_RETURN_TITLE_ATTEMPTS ->
                "${block.actionLabel}多次无响应，请手动点击；自动会话仍保持运行"
            else -> null
        }
        if (message != null) {
            publishSessionRecoveryMessage(sessionId, message)
            return true
        }

        val rect = block.returnTitleRect ?: return true
        if (
            rect.left < 0 || rect.top < 0 || rect.width <= 0 || rect.height <= 0 ||
            rect.left + rect.width > frameWidth || rect.top + rect.height > frameHeight
        ) {
            publishSessionRecoveryMessage(sessionId, "${block.actionLabel}按钮坐标越界，等待重新识别")
            return true
        }
        if (actionInFlight.get()) return true
        if (
            lastSessionReturnTitleActionAt != Long.MIN_VALUE &&
            timestampMillis - lastSessionReturnTitleActionAt < SESSION_RETURN_TITLE_ACTION_INTERVAL_MILLIS
        ) {
            return true
        }
        dispatchSessionReturnTitle(sessionId, rect, block.actionLabel, timestampMillis)
        return true
    }

    fun dispatchSessionReturnTitle(
        sessionId: AutomationSessionId,
        rect: EntryPixelRect,
        actionLabel: String,
        timestampMillis: Long,
    ) {
        if (!beginGuardedAction()) return
        traceAction("会话失效：$actionLabel")
        sessionReturnTitleAttempts++
        lastSessionReturnTitleActionAt = timestampMillis
        launchGuardedAction {
            val tap = AutomationAction.Tap(
                ScreenPoint(
                    x = rect.left + rect.width / 2f,
                    y = rect.top + rect.height / 2f,
                ),
            )
            val executor = actionExecutor
            val result = if (executor == null) {
                AutomationActionResult.Rejected(tap, "动作执行器不可用")
            } else {
                executor.execute(sessionId, tap)
            }
            when (result) {
                is AutomationActionResult.Executed -> {
                    if (activeSessionId == sessionId) {
                        restartEntryNavigationAfterSessionError()
                        val current = _state.value
                        _state.value = current.copy(
                            actionCount = current.actionCount + 1,
                            lastActionLabel = "会话失效：$actionLabel",
                            message = "已点击$actionLabel，等待标题页后重新登录",
                        )
                        publishOverlay(sessionId)
                    }
                }

                is AutomationActionResult.Rejected -> {
                    if (result.reason == GAME_NOT_FOREGROUND_REASON) {
                        sessionReturnTitleAttempts = (sessionReturnTitleAttempts - 1).coerceAtLeast(0)
                        lastSessionReturnTitleActionAt = Long.MIN_VALUE
                        publishWaitingForGameForeground(sessionId)
                    } else {
                        stopFromRunLogic("$actionLabel 点击被拒绝：${result.reason}")
                    }
                }
                AutomationActionResult.StaleSession -> Unit
                AutomationActionResult.Paused -> Unit
                is AutomationActionResult.DryRun -> Unit
            }
        }
    }

    /** Restart login/entry navigation while retaining any already-loaded route progress. */
    fun restartEntryNavigationAfterSessionError() {
        battleWait.reset()
        entryPhaseComplete = false
        resetPendingNodeClickStability()
        resetStrongRouteTargetBinding()
        clearPendingNodeTransition()
        nodeTapAttempts = 0
        resetNodeEntryMismatchTracking()
        resetNodeMoveConfirmationTracking()
        resetOrphanMoveConfirmationTracking()
        resetNodeScrollBudget()
        resetNodeRecoveryNudgeTracking()
        lastNodeSwipeAvoidRects = emptyList()
        resetNodeScrollSearchGate()
        clearNodeScrollSourceTracking()
        nodeViewportScanner.reset()
        lastNodeActionAt = Long.MIN_VALUE
        resetNodeErrorTracking()
        lastPostEntryState = null
        postEntryStableFrames = 0
        postEntryAttempts = 0
        lastPostEntryActionAt = Long.MIN_VALUE
        lastPostEntryActionLandedAt = Long.MIN_VALUE
        postEntryUnknownSince = Long.MIN_VALUE
        actionInFlightSince = Long.MIN_VALUE
        resetPostEntryStall()
        clearCharacterAcquisitionContext()
        clearRoleRewardBatchTracking()
        existingRunResumeHandoffArmed = false
        activeNodeType = null
        activeNodeArea = null
        activeNodeArea = null
        eventActionAttempts = 0
        combatContext = null
        lastBattleTeamRecommendationKey = null
        lastBattleTeamSelectionPlanLog = null
        _state.value = _state.value.copy(
            combatContext = null,
            battleTeamRecommendation = null,
            battleTeamRecommendationUnavailableReason = null,
            battleTeamSelectionPlan = null,
        )
        synchronized(actionPlannerLock) {
            actionPlanner = createActionPlanner()
        }
    }

    fun publishSessionRecoveryMessage(sessionId: AutomationSessionId, message: String) {
        if (activeSessionId != sessionId) return
        if (_state.value.message != message) {
            _state.value = _state.value.copy(message = message)
            publishOverlay(sessionId)
        }
    }

    fun resetSessionRecoveryTracking() {
        lastSessionBlockKind = SessionBlockKind.NONE
        sessionBlockStableFrames = 0
        sessionReturnTitleStableFrames = 0
        sessionReturnTitleAttempts = 0
        lastSessionReturnTitleActionAt = Long.MIN_VALUE
    }

    /**
     * Polls for channel SDK login dialogs while the entry flow has not yet handed the run to route
     * execution. Independent of frames, so a portrait SDK page that never yields a landscape frame
     * is still seen. The loop ends by itself within one interval once this session is no longer
     * the active one.
     */
    fun startChannelPromptPoll(sessionId: AutomationSessionId) {
        val prompts = channelPrompts ?: return
        actionScope.launch {
            while (activeSessionId == sessionId) {
                if (!entryPhaseComplete && !_state.value.paused) pollChannelPrompt(sessionId, prompts)
                delay(CHANNEL_PROMPT_POLL_MILLIS)
            }
        }
    }

    suspend fun pollChannelPrompt(
        sessionId: AutomationSessionId,
        prompts: com.landosol.toolbox.automation.session.ChannelLoginPromptAssistant,
    ) {
        // Cheap look first, so an empty screen never takes the action latch from frame actions.
        if (prompts.peek() == null) {
            prompts.markAbsent()
            channelPromptVisibleUntil = Long.MIN_VALUE
            channelPromptMessage = null
            return
        }
        if (!beginGuardedAction()) return
        try {
            val now = clock()
            // Node clicks and the gesture fallback both go through this session's executor, so
            // they keep the session and pause checks every other entry action has.
            val actions = com.landosol.toolbox.automation.session.SessionBoundChannelPromptActions(actionExecutor, sessionId)
            when (val step = prompts.step(now, actions)) {
                com.landosol.toolbox.automation.session.ChannelPromptStep.None -> {
                    channelPromptVisibleUntil = Long.MIN_VALUE
                    channelPromptMessage = null
                }
                is com.landosol.toolbox.automation.session.ChannelPromptStep.Seen -> {
                    channelPromptVisibleUntil = now + CHANNEL_PROMPT_HOLD_MILLIS
                    channelPromptMessage = "渠道登录界面：${step.label}，确认中"
                }
                is com.landosol.toolbox.automation.session.ChannelPromptStep.Activated -> {
                    channelPromptVisibleUntil = now + CHANNEL_PROMPT_HOLD_MILLIS
                    channelPromptMessage = "渠道登录界面：${step.label}，已点击"
                    if (activeSessionId == sessionId) {
                        val current = _state.value
                        _state.value = current.copy(
                            actionCount = current.actionCount + 1,
                            lastActionLabel = step.label,
                            message = channelPromptMessage,
                        )
                        publishOverlay(sessionId)
                    }
                }
                is com.landosol.toolbox.automation.session.ChannelPromptStep.Exhausted -> {
                    channelPromptVisibleUntil = Long.MIN_VALUE
                    channelPromptMessage = null
                    if (activeSessionId == sessionId) stopFromRunLogic(step.reason)
                }
            }
        } finally {
            endGuardedAction()
        }
    }
}
