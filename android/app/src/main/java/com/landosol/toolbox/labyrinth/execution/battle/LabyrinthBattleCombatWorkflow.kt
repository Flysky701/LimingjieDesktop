package com.landosol.toolbox.labyrinth.execution.battle

import com.landosol.toolbox.labyrinth.*
import com.landosol.toolbox.automation.AutomationSessionId
import com.landosol.toolbox.labyrinth.vision.EntryAnchorId
import com.landosol.toolbox.labyrinth.vision.EntryPixelRect
import com.landosol.toolbox.labyrinth.vision.LabyrinthBattleFailureLayout
import com.landosol.toolbox.labyrinth.vision.LabyrinthEntryFrameResult
import com.landosol.toolbox.labyrinth.vision.LabyrinthEntryPageState
import com.landosol.toolbox.labyrinth.node.LabyrinthNodeTypes
import kotlinx.coroutines.flow.MutableStateFlow
import com.landosol.toolbox.labyrinth.LabyrinthExecutionLimits.POST_ENTRY_ANCHOR_MIN_SCORE

internal interface LabyrinthBattleCombatPort {
    val _state: MutableStateFlow<LabyrinthEntryRecognitionSessionState>
    var activeNodeType: Int?
    val activeSessionId: AutomationSessionId?
    var battleRetryCount: Int
    var battleTeamActionStopReason: String?
    var battleTeamActionUnconfirmedAttempts: Int
    var battleTeamPageStallSince: Long
    val battleWait: LabyrinthBattleWaitPolicy
    var bossEditorBlocked: Boolean
    var bossEditorPreparationStage: LabyrinthBossEditorPreparationStage
    var bossEditorTeamIndex: Int?
    var bossFallbackMultiTeamActive: Boolean
    var bossMultiTeamTargetCount: Int?
    var bossTeamMode: LabyrinthBossTeamMode
    var combatContext: LabyrinthCombatContext?
    val committedBattleCharacterIds: MutableSet<String>
    val configuredBossTeamMode: LabyrinthBossTeamMode
    val currentBattleTeamSignatures: MutableSet<String>
    val currentExEncounterStrategy: LabyrinthExEncounterStrategy?
    val currentRunId: String?
    val exEncounterFallbackApproved: Boolean
    val failedBattleTeamSignatures: MutableSet<String>
    var lastBattleTeamRecommendationKey: String?
    var lastBattleTeamSelectionPlanLog: String?
    var lastNormalBattleTeamSignature: String?
    var lastPostEntryState: LabyrinthEntryPageState?
    var pendingBattleTeamAction: PendingBattleTeamAction?
    var pendingBossTeamAdvance: Pair<Int, List<String>>?
    var pendingSingleBossFallbackToMulti: Boolean
    var postEntryAttempts: Int
    var postEntryStableFrames: Int
    var postEntryUnknownSince: Long
    var preparedBossFirstTeamIds: List<String>
    val rerollAfterThreeBattleFailures: Boolean
    val singleBossFallbackToMultiAfterThreeFailures: Boolean
    val singleBossRetryCountBeforeMulti: Int
    val validatedRoute: LabyrinthRouteJson?
    var waitingForManualRoleSelection: Boolean
    fun acknowledgeBattleTeamActionProgress(result: LabyrinthEntryFrameResult): Unit
    fun anchorRect(result: LabyrinthEntryFrameResult, anchorId: String): EntryPixelRect?
    fun clearCharacterAcquisitionContext(): Unit
    fun finishFromPlanner(sessionId: AutomationSessionId, reason: String): Unit
    fun finishFromPlannerAndRequestReroll(
            sessionId: AutomationSessionId,
            reason: String,
        ): Unit
    fun handleUnconfirmedBattleTeamActionFrame(
            sessionId: AutomationSessionId,
            result: LabyrinthEntryFrameResult,
            timestampMillis: Long,
        ): Boolean
    fun publishOverlay(sessionId: AutomationSessionId): Unit
    fun resetBattleTeamExecutionTracking(): Unit
    fun resetPostEntryStall(): Unit
    fun resetSpecialEquipmentRound(): Unit
    fun traceWorkflow(owner: String, phase: String): Unit
}

/** 管理战斗等待、失败重试、Boss 模式切换和战斗边界重置。 */
internal class LabyrinthBattleCombatWorkflow(private val port: LabyrinthBattleCombatPort) : LabyrinthBattleCombatPort by port {
    /** Battle waiting gates all route taps before UNKNOWN can reach any animation fallback. */
    fun handleBattleWaitFrame(
        sessionId: AutomationSessionId,
        result: LabyrinthEntryFrameResult,
        timestampMillis: Long,
    ): Boolean {
        if (result.relicDetailObservation != null || result.nodeMoveConfirmation != null) {
            battleWait.reset()
            return false
        }
        val restartSafeBossContext = combatContext ?: labyrinthPersistedBossNode(validatedRoute)?.let {
            LabyrinthCombatContext(LabyrinthCombatKind.BOSS)
        }
        val bossSettlementNextRect = labyrinthBossSettlementNextButtonRect(
            pageState = result.observation.state,
            combatContext = restartSafeBossContext,
            nextButtonMatch = result.anchorMatches[EntryAnchorId.BATTLE_RESULT_NEXT_BUTTON],
            bossSummaryNextButtonMatch = result.anchorMatches[EntryAnchorId.BATTLE_RESULT_BOSS_SUMMARY_NEXT_BUTTON],
        )
        if (bossSettlementNextRect != null) {
            if (combatContext?.kind != LabyrinthCombatKind.BOSS) {
                activeNodeType = LabyrinthNodeTypes.BOSS
                combatContext = restartSafeBossContext
                _state.value = _state.value.copy(combatContext = combatContext)
            }
            // The current client renders area-3/area-5 Boss victory summaries as UNKNOWN pages.
            // Do not let the generic battle waiter swallow a real post-battle "下一步" screen
            // for up to five minutes. The post-entry handler below will use this exact rect.
            battleWait.reset()
            postEntryUnknownSince = Long.MIN_VALUE
            if (activeSessionId == sessionId) {
                _state.value = _state.value.copy(
                    message = "已确认进入Boss战后结算，结束战斗等待并准备点击下一步",
                )
            }
            return false
        }
        val battleResultNextReady = result.observation.state == LabyrinthEntryPageState.BATTLE_RESULT &&
            (result.anchorMatches[EntryAnchorId.BATTLE_RESULT_NEXT_BUTTON]?.score ?: 0.0) >=
            POST_ENTRY_ANCHOR_MIN_SCORE
        // 「编组页动作派发后画面没动」的兜底必须先于战斗等待判定：编组页在战斗等待的 3 秒宽限内是
        // WAIT，宽限一过又变成 NONE，两条路都会把这一帧继续交下去，而卡住的运行永远等不到结果。
        if (handleUnconfirmedBattleTeamActionFrame(sessionId, result, timestampMillis)) return true.also { traceWorkflow("battle", "wait-result") }
        when (battleWait.observe(
            page = result.observation.state,
            now = timestampMillis,
            battleResultNextReady = battleResultNextReady,
        )) {
            LabyrinthBattleWaitDecision.NONE -> {
                acknowledgeBattleTeamActionProgress(result)
                return false
            }
            LabyrinthBattleWaitDecision.TIMED_OUT -> {
                finishFromPlanner(sessionId, "等待战斗结算超过5分钟，已停止并保留诊断状态")
                return true.also { traceWorkflow("battle", "wait-result") }
            }
            LabyrinthBattleWaitDecision.WAIT -> {
                acknowledgeBattleTeamActionProgress(result)
                clearCharacterAcquisitionContext()
                waitingForManualRoleSelection = false
                postEntryUnknownSince = Long.MIN_VALUE
                resetPostEntryStall()
                // Do not inherit reward animation evidence when battle ends on an unknown frame.
                lastPostEntryState = LabyrinthEntryPageState.BATTLE_IN_PROGRESS
                postEntryStableFrames = 0
                postEntryAttempts = 0
                if (activeSessionId == sessionId) {
                    _state.value = _state.value.copy(
                        message = "等待战斗结算（最长5分钟）；暂停动画补点",
                    )
                    publishOverlay(sessionId)
                }
                return true.also { traceWorkflow("battle", "wait-result") }
            }
        }
    }

    fun resetBattleRetryTracking() {
        battleRetryCount = 0
        synchronized(currentBattleTeamSignatures) { currentBattleTeamSignatures.clear() }
        synchronized(failedBattleTeamSignatures) { failedBattleTeamSignatures.clear() }
        // 提交与签名是一体的：只清签名会把两者留在不一致的状态（提交还在、签名没了），下一次失败重试
        // 就再也对不上这一队的失败记录。
        synchronized(committedBattleCharacterIds) { committedBattleCharacterIds.clear() }
        pendingBattleTeamAction = null
        battleTeamActionUnconfirmedAttempts = 0
        battleTeamActionStopReason = null
    }

    fun handleBattleFailureFrame(sessionId: AutomationSessionId, result: LabyrinthEntryFrameResult): Boolean {
        val pageState = result.observation.state
        if (pageState == LabyrinthEntryPageState.BATTLE_FAILED) {
            val context = combatContext
            if (context == null) {
                finishFromPlanner(sessionId, "已识别战斗失败页，但缺少本次战斗上下文；未自动点击结束或重新挑战")
                return true
            }
            // The two-button layout's lower-right button reads "下一步": it advances to the
            // "遗物效果结果" popup instead of retrying, so the three stop conditions below — which
            // exist for a run that keeps losing the same fight — must not apply to it. They would
            // otherwise reroll or end the run that was just revived. The three-button page and the
            // defensive path for a missing observation keep their exact previous behaviour.
            val advanceOnly = result.battleFailure?.layout == LabyrinthBattleFailureLayout.TWO_BUTTON
            if (!advanceOnly) {
                if (!pendingSingleBossFallbackToMulti) {
                    pendingSingleBossFallbackToMulti = labyrinthShouldSwitchSingleBossToMulti(
                        enabled = singleBossFallbackToMultiAfterThreeFailures,
                        kind = context.kind,
                        mode = bossTeamMode,
                        battleRetryCount = battleRetryCount,
                        retryCountBeforeMulti = singleBossRetryCountBeforeMulti,
                    )
                }
                val singleBossFallbackOwnsFailurePolicy =
                    context.kind == LabyrinthCombatKind.BOSS &&
                        bossTeamMode == LabyrinthBossTeamMode.SINGLE_TEAM &&
                        singleBossFallbackToMultiAfterThreeFailures
                if (
                    !_state.value.dryRun &&
                    !pendingSingleBossFallbackToMulti &&
                    !singleBossFallbackOwnsFailurePolicy &&
                    labyrinthShouldRerollAfterBattleFailure(
                        rerollAfterThreeFailures = rerollAfterThreeBattleFailures,
                        battleRetryCount = battleRetryCount,
                    )
                ) {
                    finishFromPlannerAndRequestReroll(
                        sessionId,
                        "${labyrinthBattleKindLabel(context.kind)}连续3次挑战失败，不结算当前战斗",
                    )
                    return true
                }
                val retryLimit = labyrinthEffectiveBattleRetryLimit(
                    kind = context.kind,
                    rerollAfterThreeFailures = rerollAfterThreeBattleFailures,
                    singleBossFallbackRetryCount = singleBossRetryCountBeforeMulti.takeIf {
                        singleBossFallbackToMultiAfterThreeFailures &&
                            bossTeamMode == LabyrinthBossTeamMode.SINGLE_TEAM
                    },
                )
                if (
                    !_state.value.dryRun &&
                    !pendingSingleBossFallbackToMulti &&
                    labyrinthBatchOwnedRunEndsAtRetryLimit(
                        batchOwnsRun = currentRunId != null,
                        battleRetryCount = battleRetryCount,
                        retryLimit = retryLimit,
                    )
                ) {
                    // The batch rerolls and invalidates the failure page itself; the run just ends
                    // with FAILED_MAX_RETRY and leaves capture alive for the next run.
                    finishFromPlannerAndRequestReroll(
                        sessionId,
                        "${labyrinthBattleKindLabel(context.kind)}已达到自动重试上限：" +
                            "已重试${battleRetryCount}次；本局按失败计入批次",
                    )
                    return true
                }
                if (!pendingSingleBossFallbackToMulti && battleRetryCount >= retryLimit) {
                    finishFromPlanner(
                        sessionId,
                        "${labyrinthBattleKindLabel(context.kind)}已达到自动重试上限：" +
                            "已重试${battleRetryCount}次；保留在失败页，不自动点击结束",
                    )
                    return true
                }
                if (result.battleFailure == null) {
                    finishFromPlanner(sessionId, "战斗失败页按钮结构未达到安全线；不执行固定坐标重试")
                    return true
                }
            }
        }
        return false
    }

    fun onBattleTapExecuted(kind: LabyrinthPostEntryActionKind) {
        when (kind) {
                        LabyrinthPostEntryActionKind.BATTLE_FAILURE_NEXT -> {
                            // "下一步" advances the relic-revive chain, it does not retry: spending
                            // the retry budget or clearing the failed-team books here would book the
                            // run wrongly. Only cancel the battle wait and reset the frame budget,
                            // so the "遗物效果结果" popup that follows is handled as a new page.
                            battleWait.reset()
                            postEntryStableFrames = 0
                            postEntryAttempts = 0
                        }
                        LabyrinthPostEntryActionKind.BATTLE_RETRY,
                        LabyrinthPostEntryActionKind.BATTLE_RETRY_SWITCH_MULTI,
                        -> {
                            synchronized(failedBattleTeamSignatures) {
                                failedBattleTeamSignatures += currentBattleTeamSignatures
                            }
                            synchronized(currentBattleTeamSignatures) {
                                currentBattleTeamSignatures.clear()
                            }
                            synchronized(committedBattleCharacterIds) {
                                committedBattleCharacterIds.clear()
                            }
                            if (pendingSingleBossFallbackToMulti) {
                                bossTeamMode = LabyrinthBossTeamMode.MULTI_TEAM
                                bossFallbackMultiTeamActive = true
                                pendingSingleBossFallbackToMulti = false
                                battleRetryCount = 0
                                synchronized(failedBattleTeamSignatures) { failedBattleTeamSignatures.clear() }
                            } else {
                                battleRetryCount++
                            }
                            battleWait.reset()
                            bossEditorTeamIndex = null
                            pendingBossTeamAdvance = null
                            bossEditorBlocked = false
                            bossEditorPreparationStage = if (combatContext?.kind == LabyrinthCombatKind.BOSS) {
                                LabyrinthBossEditorPreparationStage.TEAM_1
                            } else {
                                LabyrinthBossEditorPreparationStage.INACTIVE
                            }
                            preparedBossFirstTeamIds = emptyList()
                            bossMultiTeamTargetCount = null
                            combatContext = combatContext?.copy(teamIndex = 1)
                            lastBattleTeamRecommendationKey = null
                            lastBattleTeamSelectionPlanLog = null
                            resetBattleTeamExecutionTracking()
                            postEntryStableFrames = 0
                            postEntryAttempts = 0
                        }
            else -> Unit
        }
    }

    fun combatContextFor(blockType: Int): LabyrinthCombatContext? = when (blockType) {
        LabyrinthNodeTypes.NORMAL_BATTLE -> LabyrinthCombatContext(LabyrinthCombatKind.NORMAL)
        LabyrinthNodeTypes.EX_BATTLE -> LabyrinthCombatContext(LabyrinthCombatKind.EX)
        LabyrinthNodeTypes.BOSS -> LabyrinthCombatContext(LabyrinthCombatKind.BOSS)
        else -> null
    }

    fun resetCombatRuntimeState() {
        combatContext = null
        lastBattleTeamRecommendationKey = null
        lastBattleTeamSelectionPlanLog = null
        resetBattleTeamExecutionTracking()
        // 会话/账号边界：特别装备轮次与「上一场普通战斗的编组记忆」都属于**这一次运行**的状态。
        // 应用复用同一个会话对象跑下一个账号，不能把上一轮的 stage（可能正停在 DONE，于是新账号的
        // 第一场直接跳过穿戴）、旧轮次的超时起点、或上一场的普通编组记忆带过去。
        // 这条重置只在 start()/stop() 走这里，普通战斗之间不会触发，所以「编组未变则跳过」照常生效。
        resetSpecialEquipmentRound()
        lastNormalBattleTeamSignature = null
        pendingBattleTeamAction = null
        battleTeamActionUnconfirmedAttempts = 0
        battleTeamActionStopReason = null
        battleTeamPageStallSince = Long.MIN_VALUE
        synchronized(committedBattleCharacterIds) { committedBattleCharacterIds.clear() }
        resetBattleRetryTracking()
        bossEditorTeamIndex = null
        pendingBossTeamAdvance = null
        bossEditorBlocked = false
        bossEditorPreparationStage = LabyrinthBossEditorPreparationStage.INACTIVE
        preparedBossFirstTeamIds = emptyList()
        bossMultiTeamTargetCount = null
    }

    fun prepareBattleEntry(blockType: Int) {
        if (blockType == LabyrinthNodeTypes.BOSS) {
            bossTeamMode = configuredBossTeamMode
            pendingSingleBossFallbackToMulti = false
            bossFallbackMultiTeamActive = false
        }
        synchronized(committedBattleCharacterIds) { committedBattleCharacterIds.clear() }
    }

    fun prepareBattleEditor(blockType: Int) {
        bossEditorTeamIndex = null
        pendingBossTeamAdvance = null
        bossEditorBlocked = false
        bossEditorPreparationStage = if (blockType == LabyrinthNodeTypes.BOSS) {
            LabyrinthBossEditorPreparationStage.TEAM_1
        } else {
            LabyrinthBossEditorPreparationStage.INACTIVE
        }
        preparedBossFirstTeamIds = emptyList()
        bossMultiTeamTargetCount = null
        combatContext = combatContextFor(blockType)    }

    fun planBattlePage(result: LabyrinthEntryFrameResult, frameWidth: Int, frameHeight: Int): LabyrinthPostEntryTapPlan? {
        return when (result.observation.state) {
            LabyrinthEntryPageState.BATTLE_FAILED ->
                result.battleFailure?.let { failure ->
                    // Both layouts tap the same lower-right button; only the kind and its
                    // accounting differ.
                    labyrinthBattleFailureTapPlan(failure, pendingSingleBossFallbackToMulti)
                }

            LabyrinthEntryPageState.BATTLE_CHALLENGE ->
                if (
                    labyrinthBattleChallengeCanAutoStart(
                        combatContext = combatContext,
                        challengeDifficultyResolved = result.exEncounter?.challengeDifficultyResolved == true,
                        extremeChallenge = result.exEncounter?.extremeChallenge == true,
                        exEncounterResolved = currentExEncounterStrategy != null,
                        exGuideFallbackApproved = exEncounterFallbackApproved,
                    )
                ) {
                    LabyrinthPostEntryTapPlan(
                        LabyrinthPostEntryActionKind.BATTLE_START_CHALLENGE,
                        "发起挑战",
                        anchorRect(result, EntryAnchorId.BATTLE_CHALLENGE_BUTTON)
                            ?: LabyrinthFallbackTap.CHALLENGE_START.rect(frameWidth, frameHeight),
                    )
                } else {
                    null
                }

            LabyrinthEntryPageState.BATTLE_RESULT ->
                LabyrinthPostEntryTapPlan(
                    LabyrinthPostEntryActionKind.BATTLE_RESULT_NEXT,
                    "战斗结算：下一步",
                    anchorRect(result, EntryAnchorId.BATTLE_RESULT_NEXT_BUTTON)
                        ?: LabyrinthFallbackTap.BOTTOM_RIGHT.rect(frameWidth, frameHeight),
                )

            LabyrinthEntryPageState.BATTLE_IN_PROGRESS,
            LabyrinthEntryPageState.NODE_SELECTION,
            -> null

            else -> null
        }
    }

    fun advanceBossTeamOnResultPage(sessionId: AutomationSessionId, result: LabyrinthEntryFrameResult, previousPostEntryState: LabyrinthEntryPageState?): Unit {
        val pageState = result.observation.state
        if (pageState == LabyrinthEntryPageState.BATTLE_TEAM_SELECTION &&
            previousPostEntryState == LabyrinthEntryPageState.BATTLE_RESULT &&
            result.battleTeamSelection?.bossTeamIndex == null &&
            combatContext?.kind == LabyrinthCombatKind.BOSS
        ) {
            combatContext = combatContext?.copy(
                teamIndex = (combatContext?.teamIndex ?: 1) + 1,
            )
            if (activeSessionId == sessionId) {
                _state.value = _state.value.copy(
                    combatContext = combatContext,
                    message = "Boss第${combatContext?.teamIndex}队编组：正在生成安全编组计划",
                )
            }
        }
    }
}
