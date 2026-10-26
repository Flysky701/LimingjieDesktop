package com.landosol.toolbox.labyrinth.execution.battle

import com.landosol.toolbox.labyrinth.*
import com.landosol.toolbox.automation.AutomationActionResult
import com.landosol.toolbox.automation.AutomationSessionId
import com.landosol.toolbox.automation.SessionBoundActionExecutor
import com.landosol.toolbox.automation.accessibility.GAME_NOT_FOREGROUND_REASON
import com.landosol.toolbox.labyrinth.vision.EntryAnchorId
import com.landosol.toolbox.labyrinth.vision.EntryPixelRect
import com.landosol.toolbox.labyrinth.vision.LabyrinthBattleElementFilter
import com.landosol.toolbox.labyrinth.vision.LabyrinthEntryFrameResult
import com.landosol.toolbox.labyrinth.vision.LabyrinthEntryPageState
import com.landosol.toolbox.labyrinth.vision.LabyrinthBattleTeamRecognitionState
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import com.landosol.toolbox.labyrinth.LabyrinthExecutionLimits.BATTLE_TEAM_ACTION_ACK_TIMEOUT_MILLIS
import com.landosol.toolbox.labyrinth.LabyrinthExecutionLimits.BATTLE_TEAM_CHARACTER_FEEDBACK_TIMEOUT_MILLIS
import com.landosol.toolbox.labyrinth.LabyrinthExecutionLimits.MAX_BATTLE_TEAM_ACTION_UNCONFIRMED
import com.landosol.toolbox.labyrinth.LabyrinthExecutionLimits.MAX_BATTLE_TEAM_SCROLL_ACTIONS
import com.landosol.toolbox.labyrinth.LabyrinthExecutionLimits.MAX_BATTLE_TEAM_STEP_ATTEMPTS

internal interface LabyrinthBattleTeamPort {
    val _state: MutableStateFlow<LabyrinthEntryRecognitionSessionState>
    val actionExecutor: SessionBoundActionExecutor?
    val actionInFlight: AtomicBoolean
    val activeSessionId: AutomationSessionId?
    val battleRosterSearch: LabyrinthBattleRosterSearch
    var battleTeamActionStopReason: String?
    var battleTeamActionUnconfirmedAttempts: Int
    val battleTeamDispatchSequence: AtomicLong
    var battleTeamExecutionAttempts: Int
    var battleTeamPageStallSince: Long
    val battleTeamRecommendationUnavailableReason: String?
    var battleTeamScrollActions: Int
    val battleWait: LabyrinthBattleWaitPolicy
    var bossEditorBlocked: Boolean
    var bossEditorPreparationStage: LabyrinthBossEditorPreparationStage
    var bossEditorTeamIndex: Int?
    var bossMultiTeamTargetCount: Int?
    val bossTeamMode: LabyrinthBossTeamMode
    val clock: () -> Long
    var combatContext: LabyrinthCombatContext?
    val committedBattleCharacterIds: MutableSet<String>
    val currentBattleTeamSignatures: MutableSet<String>
    val exhaustedBattleRosterFilters: MutableSet<com.landosol.toolbox.labyrinth.vision.LabyrinthBattleElementFilter>
    var lastBattleTeamActionAt: Long
    var lastBattleTeamExecutionKey: String?
    var lastBattleTeamRecommendationKey: String?
    var lastBattleTeamSelectionPlanLog: String?
    var lastNormalBattleTeamSignature: String?
    var lastSessionActionAt: Long
    var pendingBattleTeamAction: PendingBattleTeamAction?
    var pendingBossTeamAdvance: Pair<Int, List<String>>?
    var preparedBossFirstTeamIds: List<String>
    val specialEquipmentFlow: LabyrinthSpecialEquipmentFlow
    fun anchorRect(result: LabyrinthEntryFrameResult, anchorId: String): EntryPixelRect?
    fun beginGuardedAction(): Boolean
    fun excludeUnfindableBattleCharacters(
            sessionId: AutomationSessionId,
            unfindable: Set<String>,
            missingLabel: String,
            scannedLabel: String,
        ): Unit
    fun finishFromPlanner(sessionId: AutomationSessionId, reason: String): Unit
    fun handleSpecialEquipmentFlowFrame(
            sessionId: AutomationSessionId,
            result: LabyrinthEntryFrameResult,
            timestampMillis: Long,
        ): Boolean
    fun launchGuardedAction(block: suspend () -> Unit): Unit
    fun nodeLog(message: String, warning: Boolean = false): Unit
    fun publishOverlay(sessionId: AutomationSessionId): Unit
    fun publishWaitingForGameForeground(sessionId: AutomationSessionId): Unit
    fun sessionActionIntervalElapsed(timestampMillis: Long): Boolean
    suspend fun stopFromRunLogic(reason: String): Boolean
    fun traceAction(label: String): Unit
    fun traceReject(reason: String): Unit
    fun traceWorkflow(owner: String, phase: String): Unit
}

/** 执行编组计划，核对开战与切队反馈，并跟踪编组停滞。 */
internal class LabyrinthBattleTeamWorkflow(private val port: LabyrinthBattleTeamPort) : LabyrinthBattleTeamPort by port {
    /**
     * 编组页帧的入口：先看一眼特别装备流程是否在等着让帧，再交给编组/开战规划，最后给本页记账。
     *
     * 这个页面在 [handlePostEntryPage] 里直接 return，通用的 120 秒停滞看门狗对它永远不可达
     * （见 [LabyrinthPostEntryTapPlan] 里 BATTLE_TEAM_SELECTION 的显式 null），所以业务状态和动作
     * 都不再推进时必须在这里收尾。诊断文字的刷新不能延长停滞期限。
     */
    fun handleBattleTeamSelectionExecution(
        sessionId: AutomationSessionId,
        result: LabyrinthEntryFrameResult,
        frameWidth: Int,
        frameHeight: Int,
        timestampMillis: Long,
    ) {
        traceWorkflow("battle", "team-selection")
        val progressBefore = battleTeamProgress()
        planBattleTeamSelectionFrame(sessionId, result, frameWidth, frameHeight, timestampMillis)
        val progressed = battleTeamProgress() != progressBefore
        if (!progressed && battleTeamPageStallSince != Long.MIN_VALUE &&
            timestampMillis - battleTeamPageStallSince >= POST_ENTRY_STALL_TIMEOUT_MILLIS
        ) {
            finishFromPlanner(
                sessionId,
                "队伍编组页连续${(timestampMillis - battleTeamPageStallSince) / 1000}秒没有派发任何编组动作：" +
                    "${_state.value.message}；已停止并保留诊断状态",
            )
            return
        }
        if (progressed || battleTeamPageStallSince == Long.MIN_VALUE) {
            battleTeamPageStallSince = timestampMillis
        }
    }

    fun battleTeamProgress(): LabyrinthBattleTeamProgress {
        val current = _state.value
        return LabyrinthBattleTeamProgress(
            dispatchedActions = battleTeamDispatchSequence.get(),
            bossPreparation = bossEditorPreparationStage,
            bossTeamIndex = bossEditorTeamIndex,
            bossBlocked = bossEditorBlocked,
            bossTargetCount = bossMultiTeamTargetCount,
            pendingBossAdvance = pendingBossTeamAdvance,
            selectionPlan = current.battleTeamSelectionPlan?.let { plan ->
                LabyrinthBattleTeamPlanProgress(
                    recommendedIds = plan.recommendedIds.toList(),
                    selectedIds = plan.currentlySelectedIds.toList(),
                    needSelectIds = plan.needSelectIds.toList(),
                    needDeselectIds = plan.needDeselectIds.toList(),
                    scrollRequired = plan.scrollRequired,
                    readyToExecute = plan.readyToExecute,
                    teamReady = plan.teamReady,
                )
            },
            rosterIds = current.joinedCharacters.map { it.characterId },
            exhaustedFilters = exhaustedBattleRosterFilters.toSet(),
            specialEquipment = specialEquipmentFlow.progress(),
        )
    }

    fun planBattleTeamSelectionFrame(
        sessionId: AutomationSessionId,
        result: LabyrinthEntryFrameResult,
        frameWidth: Int,
        frameHeight: Int,
        timestampMillis: Long,
    ) {
        if (actionInFlight.get() || activeSessionId != sessionId) return
        val observation = result.battleTeamSelection ?: return

        var preparationStep: LabyrinthBattleTeamExecutionStep? = null
        if (observation.bossTeamIndex != null && combatContext?.kind == LabyrinthCombatKind.BOSS) {
            if (bossEditorPreparationStage == LabyrinthBossEditorPreparationStage.INACTIVE) {
                // Also recovers a session that entered an already-open Boss editor directly.
                bossEditorPreparationStage = LabyrinthBossEditorPreparationStage.TEAM_1
                bossEditorBlocked = false
                pendingBossTeamAdvance = null
            }
            if (bossEditorPreparationStage != LabyrinthBossEditorPreparationStage.COMPLETE) {
                val preparation = labyrinthBossEditorPreparationDecision(
                    observation = observation,
                    stage = bossEditorPreparationStage,
                )
                bossEditorPreparationStage = preparation.stage
                if (preparation.stage == LabyrinthBossEditorPreparationStage.COMPLETE) {
                    bossEditorTeamIndex = 1
                    bossEditorBlocked = false
                    pendingBossTeamAdvance = null
                    preparedBossFirstTeamIds = emptyList()
                    bossMultiTeamTargetCount = null
                    synchronized(committedBattleCharacterIds) { committedBattleCharacterIds.clear() }
                    synchronized(currentBattleTeamSignatures) { currentBattleTeamSignatures.clear() }
                    combatContext = (combatContext ?: LabyrinthCombatContext(LabyrinthCombatKind.BOSS))
                        .copy(kind = LabyrinthCombatKind.BOSS, teamIndex = 1)
                    lastBattleTeamRecommendationKey = null
                    lastBattleTeamSelectionPlanLog = null
                    resetBattleTeamExecutionTracking()
                    _state.value = _state.value.copy(
                        combatContext = combatContext,
                        battleTeamRecommendation = null,
                        battleTeamSelectionPlan = null,
                        battleTeamRecommendationUnavailableReason = null,
                        message = "Boss编组预处理完成：三队已清空并返回队伍1；开始扫描并生成新阵容",
                    )
                    return
                }
                preparationStep = preparation.step
                if (preparationStep == null) {
                    _state.value = _state.value.copy(message = "Boss编组预处理：等待队伍页与当前成员稳定识别")
                    return
                }
            }
        }

        if (preparationStep == null && observation.bossTeamIndex != null && bossEditorBlocked) {
            // A missed/ambiguous tab transition must never reuse another team's recommendation,
            // but stopping here permanently makes the automation unrecoverable. Re-enter the
            // existing Boss editor preparation flow instead: it clears all three tabs and returns
            // to team 1 before any new recommendation is allowed to execute.
            bossEditorPreparationStage = LabyrinthBossEditorPreparationStage.TEAM_1
            bossEditorTeamIndex = observation.bossTeamIndex
            bossEditorBlocked = false
            pendingBossTeamAdvance = null
            preparedBossFirstTeamIds = emptyList()
            bossMultiTeamTargetCount = null
            synchronized(committedBattleCharacterIds) { committedBattleCharacterIds.clear() }
            synchronized(currentBattleTeamSignatures) { currentBattleTeamSignatures.clear() }
            combatContext = (combatContext ?: LabyrinthCombatContext(LabyrinthCombatKind.BOSS))
                .copy(kind = LabyrinthCombatKind.BOSS, teamIndex = observation.bossTeamIndex)
            lastBattleTeamRecommendationKey = null
            lastBattleTeamSelectionPlanLog = null
            resetBattleTeamExecutionTracking()
            _state.value = _state.value.copy(
                combatContext = combatContext,
                battleTeamRecommendation = null,
                battleTeamSelectionPlan = null,
                battleTeamRecommendationUnavailableReason = null,
                message = "Boss切队记录不完整；已自动进入重同步，将清空三队并返回队伍1重新编组",
            )
            return
        }
        // 「特别装备」闸门：Boss 预处理完成之后、产出任何编组执行步（含三条路径各自的 START_BATTLE）
        // 之前。本轮没结束时这一帧由特别装备流程接管并 return，于是下面三个调用点都无法产出动作；
        // 本轮结束（成功 / 未变更 / 超限放弃）或不适用时返回 false，原有编组/开战路径逐字继续。
        if (handleSpecialEquipmentFlowFrame(sessionId, result, timestampMillis)) return
        if (specialEquipmentFlow.consumeModalCloseSettle()) {
            // 特别装备弹窗刚关闭：这一帧只让给关闭动画，不派发任何编组/开战点击。紧跟在闸门之后、
            // 在产出任何执行步之前消费，且只让一帧。
            return
        }
        val step = (preparationStep ?: if (
            bossTeamMode == LabyrinthBossTeamMode.MULTI_TEAM &&
            bossMultiTeamTargetCount != null &&
            bossMultiTeamTargetCount!! < 3 &&
            (observation.bossTeamIndex ?: 1) > bossMultiTeamTargetCount!!
        ) {
            labyrinthBossPartialMultiTeamExecutionStep(
                observation = observation,
                completedTeamCount = bossMultiTeamTargetCount!!,
                startButtonRect = anchorRect(result, EntryAnchorId.BATTLE_TEAM_START_BUTTON),
            ).also { next ->
                if (next == null) {
                    _state.value = _state.value.copy(message = when {
                        observation.recognitionState != com.landosol.toolbox.labyrinth.vision.LabyrinthBattleTeamRecognitionState.STABLE ->
                            "Boss空队切换后等待画面稳定"
                        observation.selectedCharacters.isNotEmpty() ->
                            "Boss计划仅使用${bossMultiTeamTargetCount}队，但第${observation.bossTeamIndex}队并非空队；停止自动点击"
                        else -> "Boss已完成${bossMultiTeamTargetCount}队安全编组；等待空队页与开始按钮"
                    })
                }
            }
        } else if (
            bossTeamMode == LabyrinthBossTeamMode.SINGLE_TEAM &&
            preparedBossFirstTeamIds.isNotEmpty()
        ) {
            labyrinthBossSingleTeamExecutionStep(
                observation,
                preparedBossFirstTeamIds,
                anchorRect(result, EntryAnchorId.BATTLE_TEAM_START_BUTTON),
            ).also { next ->
                if (next == null) {
                    _state.value = _state.value.copy(message = when {
                        observation.recognitionState != com.landosol.toolbox.labyrinth.vision.LabyrinthBattleTeamRecognitionState.STABLE ->
                            "Boss切队后等待画面稳定"
                        observation.selectedCharacters.isNotEmpty() ->
                            "Boss单队首战阻塞：第${observation.bossTeamIndex}队并非空队，请核对后从队伍1重新启动"
                        else -> "Boss第一队已确认；等待可靠识别当前队伍页及战斗开始按钮"
                    })
                }
            }
        } else {
            val recommendation = _state.value.battleTeamRecommendation ?: return
            val plan = _state.value.battleTeamSelectionPlan ?: return
            if (!plan.isStillValid(sessionId, recommendation, observation)) return
            val search = battleRosterSearch.observe(sessionId, observation, plan.recommendedIds)
            var rosterRecoveryStep: LabyrinthBattleTeamExecutionStep? = null
            if (plan.scrollRequired) {
                when (search) {
                    LabyrinthBattleRosterSearchDecision.WAIT_FOR_SETTLE -> {
                        _state.value = _state.value.copy(message = "角色列表滑动后等待越界回弹并重新稳定")
                        return
                    }
                    LabyrinthBattleRosterSearchDecision.EXHAUSTED -> {
                        val missing = plan.notCurrentlyVisibleIds.joinToString("、") {
                            plan.recommendedNames[it] ?: plan.currentlySelectedNames[it] ?: it
                        }
                        battleRosterSearch.reset()
                        battleTeamScrollActions = 0
                        // Walk the missing roles' attribute tabs first (usually one or two rows,
                        // so no multi-row ambiguity), then 全部 as the last resort. Each tab is
                        // scanned to the end at most once per recommendation; the set is keyed to
                        // the recommendation in refreshBattleTeamSelectionPlan.
                        exhaustedBattleRosterFilters += observation.currentFilter
                        val recoveryStep = labyrinthBattleRosterFilterRecoveryStep(
                            plan = plan,
                            observation = observation,
                            exhaustedFilters = exhaustedBattleRosterFilters,
                        )
                        if (recoveryStep != null) {
                            rosterRecoveryStep = recoveryStep
                        } else {
                            // Every candidate tab, 全部 included, was scanned end to end and the
                            // roles are simply not in this editor. Clearing the exhausted set here
                            // used to restart the identical sweep on the next frame (8 full
                            // 火→光→全部 cycles, 66 swipes in the 2026-09-15 20:58 bundle). Keep
                            // the set, drop the unfindable roles from the run roster so the next
                            // recommendation is built from characters that actually exist, and
                            // let the plan be rebuilt from that.
                            val scanned = exhaustedBattleRosterFilters.joinToString("、") { it.label }
                            val unfindable = plan.notCurrentlyVisibleIds.toSet()
                            resetBattleTeamExecutionTracking()
                            excludeUnfindableBattleCharacters(sessionId, unfindable, missing, scanned)
                            return
                        }
                    }
                    LabyrinthBattleRosterSearchDecision.UNAVAILABLE -> {
                        resetBattleTeamExecutionTracking()
                        _state.value = _state.value.copy(message = "角色列表反馈暂不可确认；保持编组页并重新建立扫描")
                        return
                    }
                    else -> Unit
                }
            }
            rosterRecoveryStep ?: labyrinthBattleTeamExecutionStep(
                plan = plan,
                sessionId = sessionId,
                recommendation = recommendation,
                observation = observation,
                startButtonRect = anchorRect(result, EntryAnchorId.BATTLE_TEAM_START_BUTTON),
                frameWidth = frameWidth,
                frameHeight = frameHeight,
                scrollDirection = if (search == LabyrinthBattleRosterSearchDecision.TO_TOP)
                    LabyrinthBattleRosterScrollDirection.TO_TOP else LabyrinthBattleRosterScrollDirection.NEXT_PAGE,
            )
        }) ?: return

        if (step.key != lastBattleTeamExecutionKey) {
            lastBattleTeamExecutionKey = step.key
            battleTeamExecutionAttempts = 0
        }
        val isCharacterToggle = step.kind == LabyrinthBattleTeamExecutionKind.SELECT_CHARACTER ||
            step.kind == LabyrinthBattleTeamExecutionKind.DESELECT_CHARACTER ||
            step.kind == LabyrinthBattleTeamExecutionKind.RESET_BOSS_DESELECT_CHARACTER
        val maximumAttempts = when {
            isCharacterToggle -> 1
            step.kind == LabyrinthBattleTeamExecutionKind.SCROLL_CHARACTERS -> MAX_BATTLE_TEAM_SCROLL_ACTIONS
            else -> MAX_BATTLE_TEAM_STEP_ATTEMPTS
        }
        if (battleTeamExecutionAttempts >= maximumAttempts) {
            // Never blindly repeat a character toggle. If visual feedback is still absent after
            // the grace period, discard the executable plan and let the next frame rebuild it
            // from the current roster state instead of terminating automation.
            if (
                isCharacterToggle &&
                timestampMillis - lastBattleTeamActionAt < BATTLE_TEAM_CHARACTER_FEEDBACK_TIMEOUT_MILLIS
            ) {
                return
            }
            resetBattleTeamExecutionTracking()
            lastBattleTeamSelectionPlanLog = null
            _state.value = _state.value.copy(
                battleTeamSelectionPlan = null,
                message = "自动编组动作反馈未确认：${step.label}；保持编组页并重新识别后规划",
            )
            return
        }
        if (step.kind == LabyrinthBattleTeamExecutionKind.SCROLL_CHARACTERS &&
            battleTeamScrollActions >= MAX_BATTLE_TEAM_SCROLL_ACTIONS
        ) {
            resetBattleTeamExecutionTracking()
            _state.value = _state.value.copy(message = "自动编组达到单轮滚动上限；保持编组页并重新从顶部扫描")
            return
        }
        if (lastBattleTeamActionAt != Long.MIN_VALUE &&
            timestampMillis - lastBattleTeamActionAt < BATTLE_TEAM_ACTION_INTERVAL_MILLIS
        ) {
            return
        }
        // 会话级节流：本路径自己的时间戳只在编组执行步之间有效，特别装备流程的点击不写它。
        // 两条流程之间必须看 [lastSessionActionAt]，否则「特别装备：取消」之后 0.76s 就会派发开战。
        if (!sessionActionIntervalElapsed(timestampMillis)) {
            traceReject("session-interval:${timestampMillis - lastSessionActionAt}")
            return
        }
        if (
            step.kind == LabyrinthBattleTeamExecutionKind.START_BATTLE ||
            step.kind == LabyrinthBattleTeamExecutionKind.NEXT_BOSS_TEAM
        ) {
            // 这两种动作必须把编组页推走：派发即记账，页面原地不动超过动作确认上限就按「没被收下」
            // 回滚（见 handleBattleWaitFrame）。
            // 先取旧值：下面会把本场编组写进记忆，而「这一下没被收下」时要还回去的是**写入之前**的
            // 上一场编组。顺序反了的话回滚只是把新值写成它自己。
            val previousNormalTeamSignature = lastNormalBattleTeamSignature
            if (step.kind == LabyrinthBattleTeamExecutionKind.START_BATTLE &&
                combatContext?.kind == LabyrinthCombatKind.NORMAL &&
                step.confirmedTeamIds.isNotEmpty()
            ) {
                // 普通关卡在这里记下本场编组：下一场若与它完全相同就跳过特别装备流程。EX / Boss 不写。
                lastNormalBattleTeamSignature = labyrinthBattleTeamSignature(step.confirmedTeamIds)
            }
            pendingBattleTeamAction = PendingBattleTeamAction(
                kind = step.kind,
                label = step.label,
                confirmedTeamIds = step.confirmedTeamIds,
                signature = battleTeamEditorSignature(result),
                dispatchedAt = timestampMillis,
                previousNormalTeamSignature = previousNormalTeamSignature.takeIf {
                    step.kind == LabyrinthBattleTeamExecutionKind.START_BATTLE
                },
            )
        }
        // 无论哪种动作，派发前都把会话级节流戳盖上（动作被拒时的回退由各自的 Rejected 分支负责）。
        lastSessionActionAt = timestampMillis
        dispatchBattleTeamAction(sessionId, step, timestampMillis)
    }

    /**
     * 「编组页动作是否被画面收下」的签名：页面类型 + Boss 队伍页签。
     *
     * Boss 切换队伍时页面类型仍是编组页，只有页签变，所以签名必须带上页签；否则一次成功的「下一队」
     * 会被当成「点了没反应」。
     */
    fun battleTeamEditorSignature(result: LabyrinthEntryFrameResult): String =
        result.observation.state.name + ":" + (result.battleTeamSelection?.bossTeamIndex ?: -1)

    fun dispatchBattleTeamAction(
        sessionId: AutomationSessionId,
        step: LabyrinthBattleTeamExecutionStep,
        timestampMillis: Long,
    ) {
        if (!beginGuardedAction()) return
        battleTeamDispatchSequence.incrementAndGet()
        traceAction(step.label)
        battleTeamExecutionAttempts++
        if (step.kind == LabyrinthBattleTeamExecutionKind.SCROLL_CHARACTERS) {
            battleTeamScrollActions++
        }
        lastBattleTeamActionAt = timestampMillis
        launchGuardedAction {
            val executor = actionExecutor
            val actionResult = if (executor == null) {
                AutomationActionResult.Rejected(step.action, "动作执行器不可用")
            } else {
                executor.execute(sessionId, step.action)
            }
            when (actionResult) {
                is AutomationActionResult.Executed -> if (activeSessionId == sessionId) {
                    if (step.kind == LabyrinthBattleTeamExecutionKind.SCROLL_CHARACTERS) {
                        val direction = step.scrollDirection
                        val originPosition = step.scrollOriginPosition
                        if (direction != null && originPosition != null) {
                            battleRosterSearch.recordExecutedScroll(direction, originPosition)
                        }
                    }
                    if (step.kind == LabyrinthBattleTeamExecutionKind.NEXT_BOSS_TEAM) {
                        pendingBossTeamAdvance = ((bossEditorTeamIndex ?: 1) + 1) to step.confirmedTeamIds
                    }
                    if (step.kind == LabyrinthBattleTeamExecutionKind.START_BATTLE) {
                        battleWait.onStartExecuted(clock())
                        if (step.confirmedTeamIds.isNotEmpty()) {
                            synchronized(currentBattleTeamSignatures) {
                                currentBattleTeamSignatures += labyrinthBattleTeamSignature(step.confirmedTeamIds)
                            }
                        }
                        synchronized(committedBattleCharacterIds) {
                            committedBattleCharacterIds += step.confirmedTeamIds
                        }
                    }
                    val current = _state.value
                    _state.value = current.copy(
                        actionCount = current.actionCount + 1,
                        lastActionLabel = step.label,
                        message = "已执行：${step.label}；等待画面确认",
                    )
                    publishOverlay(sessionId)
                }
                is AutomationActionResult.Rejected -> {
                    if (actionResult.reason == GAME_NOT_FOREGROUND_REASON) {
                        battleTeamExecutionAttempts = (battleTeamExecutionAttempts - 1).coerceAtLeast(0)
                        if (step.kind == LabyrinthBattleTeamExecutionKind.SCROLL_CHARACTERS) {
                            battleTeamScrollActions = (battleTeamScrollActions - 1).coerceAtLeast(0)
                        }
                        lastBattleTeamActionAt = Long.MIN_VALUE
                        publishWaitingForGameForeground(sessionId)
                    } else {
                        stopFromRunLogic("自动编组动作被拒绝：${actionResult.reason}")
                    }
                }
                AutomationActionResult.StaleSession -> Unit
                AutomationActionResult.Paused -> Unit
                is AutomationActionResult.DryRun -> Unit
            }
        }
    }

    fun resetBattleTeamExecutionTracking() {
        battleRosterSearch.reset()
        lastBattleTeamExecutionKey = null
        battleTeamExecutionAttempts = 0
        battleTeamScrollActions = 0
        lastBattleTeamActionAt = Long.MIN_VALUE
    }

    /**
     * 画面已经离开编组页（或换了 Boss 页签）⇒ 上一次编组页动作被游戏收下了。
     *
     * 撤掉「没被收下」的记账并清零连续失败计数，让后面的动作重新从零开始。
     *
     * 「进度」的判据是**编组页签名变了**（页面类型 + Boss 队伍页签），不是「页面类型变了」：
     * Boss 切队成功后页面类型仍然是编组页，只有页签变——只看页面类型会把一次成功的切队当成「点了
     * 没反应」，pending 与连续失败计数就会带到下一队去。签名没变的编组页帧什么都不能清（那正是
     * 「派发了但画面没动」的本体），没有 pending 时也不能清（刚被判未确认的那一次要留住计数）。
     */
    fun acknowledgeBattleTeamActionProgress(result: LabyrinthEntryFrameResult) {
        if (result.observation.state == LabyrinthEntryPageState.BATTLE_TEAM_SELECTION) {
            val pending = pendingBattleTeamAction
            if (pending == null || battleTeamEditorSignature(result) == pending.signature) return
        }
        pendingBattleTeamAction = null
        battleTeamActionUnconfirmedAttempts = 0
    }

    /**
     * 「编组页动作派发之后画面原地不动」的统一兜底：START_BATTLE 与 NEXT_BOSS_TEAM 共用。
     *
     * 判定条件刻意最窄——从派发那一刻起**每一帧都还是同一个编组页签名**：游戏只要收下了这一下，
     * 画面必然换页（加载 / 战斗 / 结算 / 未知过渡）或换 Boss 页签。超过
     * [BATTLE_TEAM_ACTION_ACK_TIMEOUT_MILLIS] 仍原地不动 ⇒ 认定这一下没被接受（实机上被弹窗关闭动画
     * 吞掉），回滚本次提交并重置，让下一帧重建计划重试；重复失败落在
     * [MAX_BATTLE_TEAM_ACTION_UNCONFIRMED] 次以内，超限停机并保留诊断，所以既不会静默无限等待，
     * 也不会无限重发。
     */
    fun handleUnconfirmedBattleTeamActionFrame(
        sessionId: AutomationSessionId,
        result: LabyrinthEntryFrameResult,
        timestampMillis: Long,
    ): Boolean {
        battleTeamActionStopReason?.let { reason ->
            // 停机申请还没落定：每帧再要一次（申请本身可能被单动作锁瞬时挡下）。
            finishFromPlanner(sessionId, reason)
            return true
        }
        val pending = pendingBattleTeamAction ?: return false
        if (result.observation.state != LabyrinthEntryPageState.BATTLE_TEAM_SELECTION ||
            battleTeamEditorSignature(result) != pending.signature
        ) {
            acknowledgeBattleTeamActionProgress(result)
            return false
        }
        if (timestampMillis - pending.dispatchedAt < BATTLE_TEAM_ACTION_ACK_TIMEOUT_MILLIS) return false
        pendingBattleTeamAction = null
        battleTeamActionUnconfirmedAttempts++
        if (battleTeamActionUnconfirmedAttempts > MAX_BATTLE_TEAM_ACTION_UNCONFIRMED) {
            val reason = "队伍编组页动作连续${battleTeamActionUnconfirmedAttempts}次派发后画面仍未推进" +
                "（${pending.label}）；已停止并保留诊断状态"
            battleTeamActionStopReason = reason
            finishFromPlanner(sessionId, reason)
            return true
        }
        recoverUnconfirmedBattleTeamAction(sessionId, pending)
        return true
    }

    /**
     * 「编组页动作已派发、画面却没动」的回收。
     *
     * START_BATTLE 的提交（角色 id、队伍签名、以及普通关卡的上一场编组记忆）是在派发成功的同一处写进去的，
     * 所以回收也必须一起还回去；NEXT_BOSS_TEAM 在派发时并没有提交任何角色（提交发生在页签真的变化之后），
     * 只撤销它的待办推进。两条路径都重置战斗等待与编组执行跟踪，并丢掉推荐/计划的缓存，让下一帧重建。
     */
    fun recoverUnconfirmedBattleTeamAction(
        sessionId: AutomationSessionId,
        pending: PendingBattleTeamAction,
    ) {
        if (
            pending.kind == LabyrinthBattleTeamExecutionKind.START_BATTLE &&
            pending.confirmedTeamIds.isNotEmpty()
        ) {
            val released = pending.confirmedTeamIds.toSet()
            synchronized(committedBattleCharacterIds) { committedBattleCharacterIds.removeAll(released) }
            synchronized(currentBattleTeamSignatures) {
                currentBattleTeamSignatures.remove(labyrinthBattleTeamSignature(pending.confirmedTeamIds))
            }
        }
        if (pending.kind == LabyrinthBattleTeamExecutionKind.START_BATTLE) {
            // 这一下没被游戏收下，普通关卡的编组记忆还原成当次捕获的原值（最近一次派发之前的值）。
            lastNormalBattleTeamSignature = pending.previousNormalTeamSignature
        }
        if (pending.kind == LabyrinthBattleTeamExecutionKind.NEXT_BOSS_TEAM) {
            pendingBossTeamAdvance = null
        }
        battleWait.reset()
        resetBattleTeamExecutionTracking()
        battleTeamPageStallSince = Long.MIN_VALUE
        lastBattleTeamRecommendationKey = null
        lastBattleTeamSelectionPlanLog = null
        if (activeSessionId == sessionId) {
            _state.value = _state.value.copy(
                battleTeamRecommendation = null,
                battleTeamRecommendationUnavailableReason = null,
                battleTeamSelectionPlan = null,
                message = "编组页动作派发后画面未推进（第${battleTeamActionUnconfirmedAttempts}次）：" +
                    "${pending.label}；已释放本次编组提交与队伍签名，重新规划后重试",
            )
        }
        nodeLog(
            "battle-team-action-unconfirmed kind=${pending.kind} " +
                "released=${pending.confirmedTeamIds.joinToString(",")} " +
                "attempt=${battleTeamActionUnconfirmedAttempts}",
            warning = true,
        )
    }

    fun onBattlePageChanged(pageState: LabyrinthEntryPageState) {
        if (pageState != LabyrinthEntryPageState.BATTLE_TEAM_SELECTION) {
            lastBattleTeamRecommendationKey = null
            lastBattleTeamSelectionPlanLog = null
            battleTeamPageStallSince = Long.MIN_VALUE
            _state.value = _state.value.copy(
                battleTeamRecommendation = null,
                battleTeamRecommendationUnavailableReason = null,
                battleTeamSelectionPlan = null,
            )
        }
    }
}
