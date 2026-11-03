package com.landosol.toolbox.labyrinth.execution.reward

import com.landosol.toolbox.labyrinth.*
import com.landosol.toolbox.automation.AutomationSessionId
import com.landosol.toolbox.labyrinth.vision.EntryAnchorId
import com.landosol.toolbox.labyrinth.vision.EntryPixelRect
import com.landosol.toolbox.labyrinth.vision.LabyrinthEntryFrameResult
import com.landosol.toolbox.labyrinth.vision.LabyrinthEntryPageState
import com.landosol.toolbox.labyrinth.batch.LabyrinthRunTerminalEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import com.landosol.toolbox.labyrinth.LabyrinthExecutionLimits.FINAL_HOME_RETURN_TIMEOUT_MILLIS
import com.landosol.toolbox.labyrinth.LabyrinthExecutionLimits.POST_ENTRY_STABLE_FRAMES

internal interface LabyrinthSettlementPort {
    val _state: MutableStateFlow<LabyrinthEntryRecognitionSessionState>
    val clock: () -> Long
    val actionScope: CoroutineScope
    val activeRunAccountId: Long?
    val activeSessionId: AutomationSessionId?
    var combatContext: LabyrinthCombatContext?
    var currentRunId: String?
    var pendingTerminalOutcome: LabyrinthRunTerminalOutcome?
    var postBossStage: LabyrinthPostBossStage
    var postBossStartedAt: Long
    var postBossUnknownAttempts: Int
    val postEntryStableFrames: Int
    val rerollRequester: (suspend (Long) -> Boolean)?
    var routeNodeCount: Int
    val runTerminalListener: (suspend (LabyrinthRunTerminalEvent) -> Unit)?
    fun anchorRect(result: LabyrinthEntryFrameResult, anchorId: String): EntryPixelRect?
    fun beginGuardedAction(): Boolean
    fun finishFromPlanner(sessionId: AutomationSessionId, reason: String): Unit
    fun launchGuardedAction(block: suspend () -> Unit): Unit
    fun nodeLog(message: String, warning: Boolean = false): Unit
    suspend fun stop(
            reason: String = "用户停止入口识别",
            releaseCapture: Boolean = true,
        ): Boolean
    fun traceWorkflow(owner: String, phase: String): Unit
}

/** 推进最终结算并发布本局终止结果。 */
internal class LabyrinthSettlementWorkflow(private val port: LabyrinthSettlementPort) : LabyrinthSettlementPort by port {
    private var runStartedAt = 0L
    private var scoreCollector = LabyrinthRunScoreCollector()

    fun beginRun() {
        runStartedAt = clock()
        scoreCollector = LabyrinthRunScoreCollector()
    }

    fun observeScore(result: LabyrinthEntryFrameResult, timestampMillis: Long) {
        scoreCollector.observe(result.observation.state, result.runResultScore, timestampMillis)
    }

    fun finalSettlementFallbackRect(
        result: LabyrinthEntryFrameResult,
        pageState: LabyrinthEntryPageState,
        frameWidth: Int,
        frameHeight: Int,
    ): EntryPixelRect {
        val center = { LabyrinthFallbackTap.CENTER.rect(frameWidth, frameHeight) }
        val bottomCenter = { LabyrinthFallbackTap.BOTTOM_CENTER.rect(frameWidth, frameHeight) }
        val bottomRight = { LabyrinthFallbackTap.BOTTOM_RIGHT.rect(frameWidth, frameHeight) }
        return when (pageState) {
            LabyrinthEntryPageState.RUN_CLEAR_CHARACTER_SUMMARY ->
                anchorRect(result, EntryAnchorId.RUN_CLEAR_NEXT_BUTTON)
                    ?: bottomRight()

            LabyrinthEntryPageState.RUN_CLEAR_REWARD_ANIMATION -> bottomCenter()
            LabyrinthEntryPageState.RUN_CLEAR_CHEST_RESULT ->
                anchorRect(result, EntryAnchorId.RUN_CLEAR_CHEST_CONFIRM_BUTTON)
                    ?: labyrinthChestResultFallbackTap().rect(frameWidth, frameHeight)
            LabyrinthEntryPageState.RUN_CLEAR_CONGRATULATIONS,
            LabyrinthEntryPageState.RUN_CLEAR_CHEST_ANIMATION,
            -> center()

            LabyrinthEntryPageState.UNKNOWN -> when (postBossStage) {
                LabyrinthPostBossStage.CHEST_SEQUENCE ->
                    if (postBossUnknownAttempts >= 3) bottomRight() else center()
                LabyrinthPostBossStage.BEFORE_SCORE ->
                    when (postBossUnknownAttempts % 4) {
                        1, 3 -> bottomRight()
                        else -> center()
                    }
                else -> center()
            }

            else -> center()
        }
    }

    /** Only the final item popup being closed and the idle maze home being visible completes a run. */
    fun markRunCleared(sessionId: AutomationSessionId) {
        if (activeSessionId != sessionId) return
        postBossStage = LabyrinthPostBossStage.WAITING_FOR_DAWN_HOME
        val current = _state.value
        val progress = current.routeProgress ?: LabyrinthRouteProgress(
            currentArea = 0,
            visitedCount = 0,
            routeNodeCount = routeNodeCount,
        )
        val summary = LabyrinthRunSummary(runStartedAt, clock(), scoreCollector.score)
        _state.value = current.copy(routeProgress = progress.copy(complete = true), runSummary = summary)
        nodeLog("run-result-complete durationMs=${summary.durationMillis} score=${summary.score ?: "unread"}")
        pendingTerminalOutcome = LabyrinthRunTerminalOutcome.CLEARED
        finishFromPlanner(sessionId, "已完成最终结算并返回黎明界主页 · ${summary.description()}")
    }

    fun handleSettlementProgress(sessionId: AutomationSessionId, pageState: LabyrinthEntryPageState, timestampMillis: Long): Boolean {
        val nextPostBossStage = labyrinthPostBossStageOnPage(postBossStage, pageState)
        if (nextPostBossStage != postBossStage) {
            nodeLog("post-boss-stage ${postBossStage.name} -> ${nextPostBossStage.name} on ${pageState.name}")
            postBossStage = nextPostBossStage
            if (pageState == LabyrinthEntryPageState.RUN_CLEAR_RESULT) {
                postBossStartedAt = timestampMillis
                postBossUnknownAttempts = 0
                // postEntryAttempts is deliberately kept: a CHEST_SEQUENCE -> SCORE_RESULT return
                // means 关闭 was already tapped once and the per-page budget bounds the re-taps.
            }
        }
        if (postBossStage == LabyrinthPostBossStage.WAITING_FOR_DAWN_HOME &&
            pageState == LabyrinthEntryPageState.DAWN_REALM_HOME_IDLE
        ) {
            if (postEntryStableFrames < POST_ENTRY_STABLE_FRAMES) return true.also { traceWorkflow("settlement", "completion") }
            markRunCleared(sessionId)
            return true.also { traceWorkflow("settlement", "completion") }
        }
        if (postBossStage == LabyrinthPostBossStage.WAITING_FOR_DAWN_HOME &&
            postBossStartedAt != Long.MIN_VALUE &&
            timestampMillis - postBossStartedAt > FINAL_HOME_RETURN_TIMEOUT_MILLIS
        ) {
            finishFromPlanner(sessionId, "最终道具已关闭，但未在限定时间内返回黎明界主页")
            return true.also { traceWorkflow("settlement", "completion") }
        }

        return false
    }

    fun onSettlementTapExecuted(kind: LabyrinthPostEntryActionKind, timestampMillis: Long) {
        when (kind) {
                        LabyrinthPostEntryActionKind.CLOSE_FINAL_SCORE -> {
                            postBossStage = LabyrinthPostBossStage.CHEST_SEQUENCE
                            postBossUnknownAttempts = 0
                        }
                        LabyrinthPostEntryActionKind.CLOSE_FINAL_ITEM -> {
                            postBossStage = LabyrinthPostBossStage.WAITING_FOR_DAWN_HOME
                            postBossStartedAt = timestampMillis
                        }
            else -> Unit
        }
    }

    /**
     * Turns the stop into one formal [LabyrinthRunTerminalEvent] for the batch, if this run was
     * batch-owned. CLEARED and FAILED_MAX_RETRY are set explicitly by their producing paths;
     * everything else is either a user stop (capture released, no explicit outcome) or an
     * unexplained stop the batch must not count.
     */
    fun publishRunTerminal(reason: String, userInitiated: Boolean) {
        val runId = currentRunId ?: return
        val outcome = pendingTerminalOutcome
        // Run ownership is session-local state and must be cleared even in builds/tests that do
        // not install a batch listener. Otherwise the next interactive run inherits a stale run
        // id and may retain capture or report a terminal event to the wrong batch.
        currentRunId = null
        pendingTerminalOutcome = null
        val listener = runTerminalListener ?: return
        val event = when {
            outcome == LabyrinthRunTerminalOutcome.CLEARED -> LabyrinthRunTerminalEvent.Cleared(runId, _state.value.runSummary)
            outcome == LabyrinthRunTerminalOutcome.SCORE_OPENING_SKIPPED -> LabyrinthRunTerminalEvent.ScoreOpeningSkipped(runId)
            outcome == LabyrinthRunTerminalOutcome.FAILED_MAX_RETRY -> LabyrinthRunTerminalEvent.FailedMaxRetry(runId)
            userInitiated -> LabyrinthRunTerminalEvent.UserStopped(runId)
            else -> LabyrinthRunTerminalEvent.FatalUnknown(runId, reason)
        }
        actionScope.launch { runCatching { listener(event) } }
    }

    fun finishFromPlannerAndRequestReroll(
        sessionId: AutomationSessionId,
        reason: String,
        scoreEvent: Boolean = false,
    ) {
        if (!beginGuardedAction()) return
        val accountId = activeRunAccountId
        launchGuardedAction {
            if (activeSessionId == sessionId) {
                // The next run reuses this projection; only the run ends here.
                pendingTerminalOutcome = if (scoreEvent) LabyrinthRunTerminalOutcome.SCORE_OPENING_SKIPPED
                    else LabyrinthRunTerminalOutcome.FAILED_MAX_RETRY
                // stop() publishes the terminal event and clears currentRunId. The batch
                // owns its next attempt; do not race its listener with a standalone restart.
                val batchWillRestart = scoreEvent && currentRunId != null && runTerminalListener != null
                val stopped = stop("$reason；正在切换到刷开局", releaseCapture = false)
                if (batchWillRestart) return@launchGuardedAction
                if (stopped && accountId != null) {
                    val started = try {
                        rerollRequester?.invoke(accountId) == true
                    } catch (cancelled: kotlinx.coroutines.CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        false
                    }
                    if (!started) {
                        _state.value = _state.value.copy(
                            message = "$reason；自动重刷启动失败，请手动进入刷开局页面继续",
                        )
                    }
                } else if (stopped) {
                    _state.value = _state.value.copy(
                        message = "$reason；缺少当前账号，未自动启动重刷",
                    )
                }
            }
        }
    }

    fun resetSettlementRuntimeState() {
        postBossStage = LabyrinthPostBossStage.NONE
        postBossUnknownAttempts = 0
        postBossStartedAt = Long.MIN_VALUE
    }

    fun planSettlementPage(result: LabyrinthEntryFrameResult, frameWidth: Int, frameHeight: Int, finalAnimationActive: Boolean, finalAnimationPage: Boolean): LabyrinthPostEntryTapPlan? {
        val pageState = result.observation.state
        return when (pageState) {
            LabyrinthEntryPageState.UNKNOWN -> when {
                labyrinthHasBattleControls(result) -> null
                labyrinthBossSettlementNextButtonRect(
                    pageState = pageState,
                    combatContext = combatContext,
                    nextButtonMatch = result.anchorMatches[EntryAnchorId.BATTLE_RESULT_NEXT_BUTTON],
                    bossSummaryNextButtonMatch = result.anchorMatches[EntryAnchorId.BATTLE_RESULT_BOSS_SUMMARY_NEXT_BUTTON],
                ) != null ->
                    LabyrinthPostEntryTapPlan(
                        LabyrinthPostEntryActionKind.BOSS_SETTLEMENT_NEXT,
                        "Boss结算：下一步",
                        requireNotNull(
                            labyrinthBossSettlementNextButtonRect(
                                pageState = pageState,
                                combatContext = combatContext,
                                nextButtonMatch = result.anchorMatches[EntryAnchorId.BATTLE_RESULT_NEXT_BUTTON],
                                bossSummaryNextButtonMatch = result.anchorMatches[EntryAnchorId.BATTLE_RESULT_BOSS_SUMMARY_NEXT_BUTTON],
                            ),
                        ),
                    )
                finalAnimationActive && finalAnimationPage ->
                    LabyrinthPostEntryTapPlan(
                        LabyrinthPostEntryActionKind.ADVANCE_FINAL_SETTLEMENT,
                        "推进最终结算动画",
                        finalSettlementFallbackRect(
                            result = result,
                            pageState = pageState,
                            frameWidth = frameWidth,
                            frameHeight = frameHeight,
                        ),
                    )
                else -> null
            }

            LabyrinthEntryPageState.RUN_CLEAR_CONGRATULATIONS,
            LabyrinthEntryPageState.RUN_CLEAR_CHARACTER_SUMMARY,
            LabyrinthEntryPageState.RUN_CLEAR_REWARD_ANIMATION,
            LabyrinthEntryPageState.RUN_CLEAR_CHEST_ANIMATION,
            LabyrinthEntryPageState.RUN_CLEAR_CHEST_RESULT,
            -> if (finalAnimationActive && finalAnimationPage) {
                LabyrinthPostEntryTapPlan(
                    LabyrinthPostEntryActionKind.ADVANCE_FINAL_SETTLEMENT,
                    "推进最终结算动画",
                    finalSettlementFallbackRect(
                        result = result,
                        pageState = pageState,
                        frameWidth = frameWidth,
                        frameHeight = frameHeight,
                    ),
                )
            } else {
                null
            }

            LabyrinthEntryPageState.RUN_CLEAR_RESULT ->
                if (postBossStage == LabyrinthPostBossStage.SCORE_RESULT) {
                    LabyrinthPostEntryTapPlan(
                        LabyrinthPostEntryActionKind.CLOSE_FINAL_SCORE,
                        "关闭最终分数结果",
                        anchorRect(result, EntryAnchorId.RUN_RESULT_CLOSE_BUTTON)
                            ?: LabyrinthFallbackTap.BOTTOM_CENTER.rect(frameWidth, frameHeight),
                    )
                } else {
                    null
                }

            else -> null
        }
    }

    fun onSettlementTapDispatched(kind: LabyrinthPostEntryActionKind): Unit {
        if (kind == LabyrinthPostEntryActionKind.ADVANCE_FINAL_SETTLEMENT) postBossUnknownAttempts++
    }

    fun onSettlementTapNotForeground(kind: LabyrinthPostEntryActionKind): Unit {
        if (kind == LabyrinthPostEntryActionKind.ADVANCE_FINAL_SETTLEMENT) {
            postBossUnknownAttempts = (postBossUnknownAttempts - 1).coerceAtLeast(0)
        }
    }
}
