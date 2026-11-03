package com.landosol.toolbox.labyrinth.execution.runtime

import com.landosol.toolbox.labyrinth.*
import com.landosol.toolbox.automation.AutomationSessionId
import com.landosol.toolbox.labyrinth.vision.EntryPixelRect
import com.landosol.toolbox.labyrinth.vision.LabyrinthEntryFrameResult
import com.landosol.toolbox.labyrinth.vision.LabyrinthEntryPageState
import com.landosol.toolbox.labyrinth.vision.LabyrinthBattleTeamObservation
import com.landosol.toolbox.labyrinth.vision.LabyrinthRelicMatch
import com.landosol.toolbox.labyrinth.vision.labyrinthRelicDetailHoldsFrame
import com.landosol.toolbox.labyrinth.node.LabyrinthNodeSession
import com.landosol.toolbox.labyrinth.node.LabyrinthNodeTypes
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import com.landosol.toolbox.labyrinth.LabyrinthExecutionLimits.CHARACTER_ACQUISITION_ACTION_INTERVAL_MILLIS
import com.landosol.toolbox.labyrinth.LabyrinthExecutionLimits.MAX_CHARACTER_ACQUISITION_CLICKS
import com.landosol.toolbox.labyrinth.LabyrinthExecutionLimits.MAX_EVENT_ACTIONS
import com.landosol.toolbox.labyrinth.LabyrinthExecutionLimits.MAX_POST_BOSS_UNKNOWN_ATTEMPTS
import com.landosol.toolbox.labyrinth.LabyrinthExecutionLimits.MAX_POST_ENTRY_ATTEMPTS
import com.landosol.toolbox.labyrinth.LabyrinthExecutionLimits.POST_ENTRY_ACTION_INTERVAL_MILLIS
import com.landosol.toolbox.labyrinth.LabyrinthExecutionLimits.POST_ENTRY_STABLE_FRAMES
import com.landosol.toolbox.labyrinth.LabyrinthExecutionLimits.UNKNOWN_POST_ENTRY_TIMEOUT_MILLIS

internal interface LabyrinthPageRouterPort {
    val _state: MutableStateFlow<LabyrinthEntryRecognitionSessionState>
    val actionInFlight: AtomicBoolean
    var actionInFlightSince: Long
    var actionPlanner: LabyrinthEntryActionPlanner?
    val actionPlannerLock: Any
    val activeNodeType: Int?
    val activeSessionId: AutomationSessionId?
    val battleTeamRecommendationUnavailableReason: String?
    val battleWait: LabyrinthBattleWaitPolicy
    val channelPromptMessage: String?
    val channelPromptVisibleUntil: Long
    val clock: () -> Long
    var combatContext: LabyrinthCombatContext?
    var entryPhaseComplete: Boolean
    var existingRunResumeHandoffArmed: Boolean
    var firstFrameWatchdog: Job?
    var lastPostEntryActionAt: Long
    var lastPostEntryActionLandedAt: Long
    var lastPostEntryState: LabyrinthEntryPageState?
    val nodeSession: LabyrinthNodeSession?
    val plannedShopPurchaseRelicId: String?
    val plannedShopRoleImprintLabel: String?
    val postBossStage: LabyrinthPostBossStage
    var postEntryAttempts: Int
    var postEntryStableFrames: Int
    var postEntryStallMessage: String?
    var postEntryStallSince: Long
    var postEntryUnknownSince: Long
    var relicDetailHoldSince: Long
    val roleRewardChoicePlanner: LabyrinthRoleRewardChoicePlanner?
    val roleRewardChoiceUnavailableReason: String?
    val shopImprintRewardConfirmedAt: Long
    val skipRoleRewardJoinedRecognition: Boolean
    fun specialEquipmentActive(): Boolean
    fun advanceBossTeamOnResultPage(sessionId: AutomationSessionId, result: LabyrinthEntryFrameResult, previousPostEntryState: LabyrinthEntryPageState?): Unit
    fun clearCharacterAcquisitionContext(): Unit
    fun clearRoleRewardBatchTracking(): Unit
    fun confirmPendingNodeTransition(
            sessionId: AutomationSessionId,
            pageState: LabyrinthEntryPageState,
        ): Unit
    fun createActionPlanner(): LabyrinthEntryActionPlanner
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
        ): Unit
    fun endGuardedAction(): Unit
    fun finishFromPlanner(sessionId: AutomationSessionId, reason: String): Unit
    fun handleActionDecision(
            sessionId: AutomationSessionId,
            result: LabyrinthEntryFrameResult,
            frameWidth: Int,
            frameHeight: Int,
            timestampMillis: Long,
        ): Unit
    fun handleBattleFailureFrame(sessionId: AutomationSessionId, result: LabyrinthEntryFrameResult): Boolean
    fun handleBattleTeamSelectionExecution(
            sessionId: AutomationSessionId,
            result: LabyrinthEntryFrameResult,
            frameWidth: Int,
            frameHeight: Int,
            timestampMillis: Long,
        ): Unit
    fun handleBattleWaitFrame(
            sessionId: AutomationSessionId,
            result: LabyrinthEntryFrameResult,
            timestampMillis: Long,
        ): Boolean
    fun handleEventFreeRoleFrame(sessionId: AutomationSessionId, result: LabyrinthEntryFrameResult, timestampMillis: Long, dryRun: Boolean, routeHasPassedOpening: Boolean): Boolean
    fun handleExEncounterRecognitionFrame(
            sessionId: AutomationSessionId,
            result: LabyrinthEntryFrameResult,
            timestampMillis: Long,
        ): Boolean
    fun handleNodeMoveConfirmationFrame(
            sessionId: AutomationSessionId,
            result: LabyrinthEntryFrameResult,
            timestampMillis: Long,
        ): Boolean
    fun handleNodeSelectionFrame(
            sessionId: AutomationSessionId,
            result: LabyrinthEntryFrameResult,
            frameWidth: Int,
            frameHeight: Int,
            timestampMillis: Long,
        ): Unit
    fun hasPendingNodeTransition(): Boolean
    fun handleOrphanMoveConfirmation(sessionId: AutomationSessionId, result: LabyrinthEntryFrameResult, timestampMillis: Long, dryRun: Boolean): Boolean
    fun handleSessionBlockFrame(
            sessionId: AutomationSessionId,
            result: LabyrinthEntryFrameResult,
            frameWidth: Int,
            frameHeight: Int,
            timestampMillis: Long,
            dryRun: Boolean,
        ): Boolean
    fun handleSettlementProgress(sessionId: AutomationSessionId, pageState: LabyrinthEntryPageState, timestampMillis: Long): Boolean
    fun handleShopRefreshConfirmationFrame(
            sessionId: AutomationSessionId,
            result: LabyrinthEntryFrameResult,
            timestampMillis: Long,
        ): Boolean
    fun handleSpecialEquipmentFlowFrame(
            sessionId: AutomationSessionId,
            result: LabyrinthEntryFrameResult,
            timestampMillis: Long,
        ): Boolean
    fun invalidatePreviousPageState(
            pageState: LabyrinthEntryPageState,
            preservePendingNodeMoveConfirmation: Boolean = false,
        ): Unit
    fun nodeExecutionConfigured(): Boolean
    fun nodeLog(message: String, warning: Boolean = false): Unit
    fun persistObservation(
            sessionId: AutomationSessionId,
            result: LabyrinthEntryFrameResult,
            timestampMillis: Long,
        ): Unit
    fun planBattlePage(result: LabyrinthEntryFrameResult, frameWidth: Int, frameHeight: Int): LabyrinthPostEntryTapPlan?
    fun planEventAnimationPage(result: LabyrinthEntryFrameResult, frameWidth: Int, frameHeight: Int, characterAcquisitionActive: Boolean): LabyrinthPostEntryTapPlan?
    fun planEventChoice(sessionId: AutomationSessionId, result: LabyrinthEntryFrameResult, timestampMillis: Long): LabyrinthPostEntryTapPlan?
    fun planRelicChoice(sessionId: AutomationSessionId, result: LabyrinthEntryFrameResult, timestampMillis: Long): LabyrinthPostEntryTapPlan?
    fun planRewardPage(result: LabyrinthEntryFrameResult, frameWidth: Int, frameHeight: Int): LabyrinthPostEntryTapPlan?
    fun planRoleRewardPage(result: LabyrinthEntryFrameResult): LabyrinthPageActionProposal
    fun planSettlementPage(result: LabyrinthEntryFrameResult, frameWidth: Int, frameHeight: Int, finalAnimationActive: Boolean, finalAnimationPage: Boolean): LabyrinthPostEntryTapPlan?
    fun planShopPage(result: LabyrinthEntryFrameResult, frameWidth: Int, frameHeight: Int, timestampMillis: Long): LabyrinthPageActionProposal
    fun postEntryIsRoleRewardPage(result: LabyrinthEntryFrameResult): Boolean
    fun prepareMapTransitionFrame(sessionId: AutomationSessionId, pageState: LabyrinthEntryPageState, result: LabyrinthEntryFrameResult, timestampMillis: Long, dryRun: Boolean): Boolean?
    fun publishOverlay(sessionId: AutomationSessionId): Unit
    fun refreshBattleTeamRecommendation(
            sessionId: AutomationSessionId,
            pageState: LabyrinthEntryPageState,
        ): Unit
    fun refreshBattleTeamSelectionPlan(
            sessionId: AutomationSessionId,
            pageState: LabyrinthEntryPageState,
            observation: com.landosol.toolbox.labyrinth.vision.LabyrinthBattleTeamObservation?,
        ): Unit
    fun refreshEffectiveCharacterScan(
            sessionId: AutomationSessionId,
            result: LabyrinthEntryFrameResult,
            frameWidth: Int,
            frameHeight: Int,
            timestampMillis: Long,
        ): Unit
    fun refreshEventChoiceDecision(
            sessionId: AutomationSessionId,
            result: LabyrinthEntryFrameResult,
        ): Unit
    fun refreshRelicStackCalibration(result: LabyrinthEntryFrameResult): Unit
    fun refreshRoleRewardChoiceDecision(
            sessionId: AutomationSessionId,
            result: LabyrinthEntryFrameResult,
            frameWidth: Int,
            frameHeight: Int,
        ): Unit
    fun resetBattleTeamExecutionTracking(): Unit
    fun resetEventChoiceCommit(): Unit
    fun synchronizeBossEditor(result: LabyrinthEntryFrameResult): Unit
    fun traceReject(reason: String): Unit
    fun traceWorkflow(owner: String, phase: String): Unit
    fun updateRewardAnimationProgress(sessionId: AutomationSessionId, result: LabyrinthEntryFrameResult, timestampMillis: Long): Unit
    fun updateRewardPageProgress(sessionId: AutomationSessionId, result: LabyrinthEntryFrameResult, previousPostEntryState: LabyrinthEntryPageState?, timestampMillis: Long): Unit
    val characterAcquisitionActive: Boolean
    fun observeRunScore(result: LabyrinthEntryFrameResult, timestampMillis: Long)
    fun updateRoleRewardJoinedRecognitionGate(result: LabyrinthEntryFrameResult)
    fun resumeRoleRewardBatch(pageState: LabyrinthEntryPageState, timestampMillis: Long)
    fun resetRelicSelectionOnPageEntry()
    fun observeRoleRewardSelectionSignature(signature: String): Boolean
    fun characterAcquisitionTimedOut(timestampMillis: Long): Boolean
    fun isRoleRewardBatchWaitingForJoin(): Boolean
}

/** 保持原有页面优先级与动作预算，协调各业务对当前帧的处理。 */
internal class LabyrinthPageRouterWorkflow(private val port: LabyrinthPageRouterPort) : LabyrinthPageRouterPort by port {
    fun processFrameResult(
        sessionId: AutomationSessionId,
        result: LabyrinthEntryFrameResult,
        frameWidth: Int,
        frameHeight: Int,
        timestampMillis: Long,
    ) {
        if (activeSessionId != sessionId) return
        firstFrameWatchdog?.cancel()
        firstFrameWatchdog = null
        if (result.frameWidth != frameWidth || result.frameHeight != frameHeight) {
            nodeLog(
                "frame-size-mismatch callback=${frameWidth}x${frameHeight} " +
                    "result=${result.frameWidth}x${result.frameHeight}",
                warning = true,
            )
        }

        if (labyrinthActionLatchStuck(actionInFlightSince, timestampMillis) && actionInFlight.get()) {
            val heldFor = timestampMillis - actionInFlightSince
            endGuardedAction()
            finishFromPlanner(
                sessionId,
                "动作执行超过${heldFor / 1000}秒未返回（手势可能未落地），已释放动作锁并停止以保留诊断状态",
            )
            return
        }
        val current = _state.value
        _state.value = current.copy(
            frameCount = current.frameCount + 1,
            lastResult = result,
            message = if (result.observation.state == LabyrinthEntryPageState.UNKNOWN) {
                "未知或低置信度页面，动作已禁用"
            } else {
                "页面识别已更新"
            },
            frameTrace = LabyrinthFrameTrace(
                workflowOwner = "router",
                workflowPhase = "observe",
                expectedPages = LabyrinthEntryPageState.entries.map(LabyrinthEntryPageState::name),
                nodeSearchWindowCount = result.nodeSearchWindowCount,
            ),
        )
        val pageState = result.observation.state
        if (!current.dryRun) observeRunScore(result, timestampMillis)
        if (clock() < channelPromptVisibleUntil) {
            // The channel prompt poll owns the screen; nothing behind the dialog may be tapped.
            channelPromptMessage?.let { message -> _state.value = _state.value.copy(message = message) }
            return
        }
        if (
            handleSessionBlockFrame(
                sessionId = sessionId,
                result = result,
                frameWidth = frameWidth,
                frameHeight = frameHeight,
                timestampMillis = timestampMillis,
                dryRun = current.dryRun,
            )
        ) {
            traceWorkflow("recovery", "session-block")
            return
        }
        updateRoleRewardJoinedRecognitionGate(result)
        val shopJoinedReward = labyrinthShopJoinedRewardOwnsRoute(result, shopImprintRewardConfirmedAt, timestampMillis)
        if (!current.dryRun &&
            !entryPhaseComplete &&
            (shopJoinedReward || (existingRunResumeHandoffArmed && labyrinthResumedRunRewardPageOwnsRoute(result)))
        ) {
            // Resuming an existing run can restore directly into an unfinished reward batch.
            // Do not let the opening invitation planner interpret that CHARACTER_JOINED page.
            entryPhaseComplete = true
            existingRunResumeHandoffArmed = false
            resumeRoleRewardBatch(pageState, timestampMillis)
            _state.value = _state.value.copy(
                message = if (pageState == LabyrinthEntryPageState.CHARACTER_JOINED) {
                    if (shopJoinedReward) "商店印记触发角色加入，已交接奖励流程"
                    else "继续挑战后恢复到角色加入奖励，已交接连续奖励链"
                } else {
                    "继续挑战后恢复到角色奖励选择，已交接连续奖励链"
                },
            )
            nodeLog("existing-run resume handed off directly to role reward page: ${pageState.name}")
        }
        val routeHasPassedOpening = labyrinthRouteHasPassedOpening(
            nodeSession?.getState(),
            hasNodeTransition = hasPendingNodeTransition() || activeNodeType != null,
        )
        if (!current.dryRun && labyrinthShouldResumeOpeningSelection(
                entryPhaseComplete = entryPhaseComplete,
                result = result,
                routeActive = routeHasPassedOpening,
            )
        ) {
            entryPhaseComplete = false
            existingRunResumeHandoffArmed = false
            battleWait.reset()
            clearCharacterAcquisitionContext()
            clearRoleRewardBatchTracking()
            synchronized(actionPlannerLock) {
                actionPlanner = createActionPlanner()
            }
            nodeLog("opening-selection reclaimed entry flow after map handoff")
        }
        if (!current.dryRun) {
            // Destination recognition may confirm the just-dispatched node before its page-local
            // proposal is invalidated below.
            confirmPendingNodeTransition(sessionId, pageState)
        }
        val preserveMapTransition = prepareMapTransitionFrame(sessionId, pageState, result, timestampMillis, current.dryRun) ?: return
        invalidatePreviousPageState(pageState, preserveMapTransition)
        synchronizeBossEditor(result)
        refreshRelicStackCalibration(result)
        refreshRoleRewardChoiceDecision(sessionId, result, frameWidth, frameHeight)
        refreshEventChoiceDecision(sessionId, result)
        refreshEffectiveCharacterScan(sessionId, result, frameWidth, frameHeight, timestampMillis)
        refreshBattleTeamRecommendation(sessionId, pageState)
        refreshBattleTeamSelectionPlan(sessionId, pageState, result.battleTeamSelection)
        publishOverlay(sessionId)
        persistObservation(sessionId, result, timestampMillis)
        if (!current.dryRun && entryPhaseComplete &&
            handleBattleWaitFrame(sessionId, result, timestampMillis)
        ) return
        val relicDetail = result.relicDetailObservation
        if (relicDetail != null && labyrinthRelicDetailHoldsFrame(result)) {
            // This popup is only ever opened by hand, and the run deliberately does not close it
            // so the operator can read it. That made it an unbounded wait: a popup left open (or
            // one the classifier keeps seeing) parked the route with no deadline at all. Reading
            // it is still uninterrupted for as long as anyone plausibly needs.
            if (relicDetailHoldSince == Long.MIN_VALUE) relicDetailHoldSince = timestampMillis
            val heldFor = timestampMillis - relicDetailHoldSince
            if (heldFor >= RELIC_DETAIL_HOLD_TIMEOUT_MILLIS) {
                finishFromPlanner(
                    sessionId,
                    "系列详情弹窗持续${heldFor / 1000}秒未关闭，路线无法继续；已停止并保留诊断状态",
                )
                return
            }
            _state.value = _state.value.copy(message = if (relicDetail.titleConfirmed) {
                "系列详情核对中：只更新已确认的可见系列；可手动滚动，关闭后继续路线"
            } else "正在确认系列详情弹窗，暂不执行点击")
            publishOverlay(sessionId)
            return
        }
        relicDetailHoldSince = Long.MIN_VALUE
        if (!current.dryRun && handleShopRefreshConfirmationFrame(
                sessionId = sessionId,
                result = result,
                timestampMillis = timestampMillis,
            )
        ) {
            return
        }
        if (handleOrphanMoveConfirmation(sessionId, result, timestampMillis, current.dryRun)) return
        if (!current.dryRun && labyrinthNodeMoveConfirmationOwnsFrame(
                pageState = pageState,
                hasPendingNodeTransition = hasPendingNodeTransition(),
            )
        ) {
            if (handleNodeMoveConfirmationFrame(sessionId, result, timestampMillis)) {
                return
            }
        }
        if (handleEventFreeRoleFrame(sessionId, result, timestampMillis, current.dryRun, routeHasPassedOpening)) return
        val nodeReplayActive = nodeExecutionConfigured() &&
            (if (current.dryRun) pageState == LabyrinthEntryPageState.NODE_SELECTION else entryPhaseComplete)
        // A one-button 确认 dialog is drawn over the map, so the page classifier still reports
        // NODE_SELECTION and the branch below would hand the frame to the node search. 2026-09-18
        // live: the 「无法获得报酬」 popup after an event sat open while the run swiped the map
        // looking for 普通战斗#40501 for 279 actions. The dialog was recognised the whole time --
        // its 确认 button scored 0.985 -- but the only code that acts on it lives in the
        // post-entry page handler, which a NODE_SELECTION frame never reaches. A modal owns the
        // frame: nothing behind it can be clicked, and no map gesture can reach the map.
        val genericConfirmOwnsFrame = !current.dryRun &&
            labyrinthGenericConfirmDialogRect(result) != null
        // 「特别装备」弹窗是编组页之上的模态：它自己的按钮与上面共享的「确认」模板互相干扰，而压暗
        // 底层页之后页面分类可能落到 UNKNOWN，那条路根本到不了编组页处理器。所以这一支**必须排在
        // genericConfirmOwnsFrame 之前**，并且只凭观测的 stage（或本轮已武装）进入，与当帧 pageState
        // 无关。流程没接管（返回 false）时不改变任何既有分支的行为。
        val specialEquipmentOwnsFrame = !current.dryRun &&
            (
                result.specialEquipment?.stage != null ||
                    specialEquipmentActive()
                )
        if (
            specialEquipmentOwnsFrame &&
            handleSpecialEquipmentFlowFrame(sessionId, result, timestampMillis)
        ) {
            return
        }
        when {
            genericConfirmOwnsFrame ->
                handlePostEntryPage(sessionId, result, frameWidth, frameHeight, timestampMillis)

            shopJoinedReward && !current.dryRun ->
                handlePostEntryPage(sessionId, result, frameWidth, frameHeight, timestampMillis)
            nodeReplayActive && pageState == LabyrinthEntryPageState.NODE_SELECTION ->
                handleNodeSelectionFrame(sessionId, result, frameWidth, frameHeight, timestampMillis)

            // 入口流程已交接给路线执行：点击节点后出现的事件页在这里处理，
            // 处理完回到节点页继续走路线。
            nodeReplayActive ->
                if (!current.dryRun) {
                    handlePostEntryPage(sessionId, result, frameWidth, frameHeight, timestampMillis)
                }

            !current.dryRun ->
                handleActionDecision(sessionId, result, frameWidth, frameHeight, timestampMillis)
        }
    }

    /** Dispatch recognized route pages and bounded reward/settlement animation recovery. */
    fun handlePostEntryPage(
        sessionId: AutomationSessionId,
        result: LabyrinthEntryFrameResult,
        frameWidth: Int,
        frameHeight: Int,
        timestampMillis: Long,
    ) {
        traceWorkflow(labyrinthWorkflowOwner(result), "post-entry")
        val pageState = result.observation.state
        val previousPostEntryState = lastPostEntryState
        val roleRewardPage = postEntryIsRoleRewardPage(result)
        val roleRewardSelectionSignature = if (roleRewardPage) {
            labyrinthRoleRewardSelectionSignature(result)
        } else {
            null
        }
        if (lastPostEntryState != pageState) {
            lastPostEntryState = pageState
            postEntryStableFrames = 0
            postEntryAttempts = 0
            resetPostEntryStall()
            resetEventChoiceCommit()
            if (pageState == LabyrinthEntryPageState.RELIC_CHOICE) {
                resetRelicSelectionOnPageEntry()
            }
            if (pageState == LabyrinthEntryPageState.BATTLE_TEAM_SELECTION) {
                resetBattleTeamExecutionTracking()
            }
        }
        if (roleRewardPage &&
            roleRewardSelectionSignature != null &&
            observeRoleRewardSelectionSignature(roleRewardSelectionSignature)
        ) {
            // A new choice round can reuse the exact same page enum and geometry. Treat the
            // candidate-set change as a page transition for stability/attempt accounting.
            postEntryStableFrames = 0
            postEntryAttempts = 0
        }
        postEntryStableFrames++

        if (handleExEncounterRecognitionFrame(sessionId, result, timestampMillis)) return

        updateRewardPageProgress(sessionId, result, previousPostEntryState, timestampMillis)

        advanceBossTeamOnResultPage(sessionId, result, previousPostEntryState)
        if (pageState == LabyrinthEntryPageState.UNKNOWN) {
            if (postEntryUnknownSince == Long.MIN_VALUE) postEntryUnknownSince = timestampMillis
            if (!characterAcquisitionActive && postBossStage == LabyrinthPostBossStage.NONE &&
                timestampMillis - postEntryUnknownSince > UNKNOWN_POST_ENTRY_TIMEOUT_MILLIS
            ) {
                finishFromPlanner(sessionId, "路线阶段未知页面超过限定时间，已停止并保留诊断状态")
                return
            }
        } else {
            postEntryUnknownSince = Long.MIN_VALUE
        }
        if (handleSettlementProgress(sessionId, pageState, timestampMillis)) return

        updateRewardAnimationProgress(sessionId, result, timestampMillis)

        if (postEntryStableFrames < POST_ENTRY_STABLE_FRAMES && !roleRewardPage &&
            !(pageState == LabyrinthEntryPageState.CHARACTER_JOINED && skipRoleRewardJoinedRecognition)
        ) {
            traceReject("stable-frames:$postEntryStableFrames/$POST_ENTRY_STABLE_FRAMES")
            return
        }
        if (handleBattleFailureFrame(sessionId, result)) return
        if (pageState == LabyrinthEntryPageState.BATTLE_TEAM_SELECTION) {
            handleBattleTeamSelectionExecution(
                sessionId = sessionId,
                result = result,
                frameWidth = frameWidth,
                frameHeight = frameHeight,
                timestampMillis = timestampMillis,
            )
            return
        }
        val postEntryActionInterval = if (characterAcquisitionActive) {
            CHARACTER_ACQUISITION_ACTION_INTERVAL_MILLIS
        } else {
            POST_ENTRY_ACTION_INTERVAL_MILLIS
        }
        val cooldownRemaining = labyrinthPostEntryCooldownRemaining(
            frameTimestampMillis = timestampMillis,
            dispatchedAtMillis = lastPostEntryActionAt,
            landedAtMillis = lastPostEntryActionLandedAt,
            intervalMillis = postEntryActionInterval,
        )
        if (cooldownRemaining > 0L) {
            traceReject("cooldown:${postEntryActionInterval - cooldownRemaining}/$postEntryActionInterval")
            return
        }

        val finalAnimationPage = pageState == LabyrinthEntryPageState.UNKNOWN ||
            pageState == LabyrinthEntryPageState.RUN_CLEAR_CONGRATULATIONS ||
            pageState == LabyrinthEntryPageState.RUN_CLEAR_CHARACTER_SUMMARY ||
            pageState == LabyrinthEntryPageState.RUN_CLEAR_REWARD_ANIMATION ||
            pageState == LabyrinthEntryPageState.RUN_CLEAR_CHEST_ANIMATION ||
            pageState == LabyrinthEntryPageState.RUN_CLEAR_CHEST_RESULT
        val finalAnimationActive = postBossStage == LabyrinthPostBossStage.BEFORE_SCORE ||
            postBossStage == LabyrinthPostBossStage.CHEST_SEQUENCE
        val maxAttempts = when {
            characterAcquisitionActive -> MAX_CHARACTER_ACQUISITION_CLICKS
            finalAnimationActive && finalAnimationPage -> MAX_POST_BOSS_UNKNOWN_ATTEMPTS
            activeNodeType == LabyrinthNodeTypes.EVENT -> MAX_EVENT_ACTIONS
            else -> MAX_POST_ENTRY_ATTEMPTS
        }
        if (characterAcquisitionTimedOut(timestampMillis)) {
            finishFromPlanner(sessionId, "角色获得流程超过限定时间，已停止并保留诊断状态")
            return
        }
        if (postEntryAttempts >= maxAttempts) {
            val reason = if (characterAcquisitionActive) {
                "角色获得动画推进达到上限，已停止并保留诊断状态"
            } else if (finalAnimationActive && finalAnimationPage) {
                "最终结算动画推进达到上限，未找到下一结算页面"
            } else {
                "${pageState.name} 页面重复点击达到上限，已暂停等待人工检查"
            }
            finishFromPlanner(sessionId, reason)
            return
        }

        val roleRewardProposal = planRoleRewardPage(result)
        val roleRewardPlan = roleRewardProposal.plan
        val roleRewardWaitReason = roleRewardProposal.waitReason
        val manualInputMessage = if (
            pageState == LabyrinthEntryPageState.INITIAL_CHARACTER_SELECTION &&
            postEntryIsRoleRewardPage(result) &&
            roleRewardChoicePlanner == null
        ) {
            listOfNotNull(
                "角色三选一暂由人工完成",
                roleRewardChoiceUnavailableReason,
            ).joinToString("；")
        } else if (pageState == LabyrinthEntryPageState.BATTLE_TEAM_SELECTION) {
            labyrinthBattleTeamSelectionMessage(
                combatContext = combatContext,
                recommendation = _state.value.battleTeamRecommendation,
                unavailableReason = _state.value.battleTeamRecommendationUnavailableReason,
                selectionPlan = _state.value.battleTeamSelectionPlan,
            )
        } else {
            labyrinthManualInputMessage(pageState)
        }
        var shopWaitReason: String? = null
        // Page ownership is the hard action boundary. Workflow flags may refine the action
        // *inside* the current page, but they can never make one page execute another page's
        // proposal. This is intentionally page-first rather than flow-first.
        val genericConfirmRect = labyrinthGenericConfirmDialogRect(result)
        val plan: LabyrinthPostEntryTapPlan? = if (genericConfirmRect != null) {
            // A modal 确认 owns the frame whatever the map behind it classifies as.
            LabyrinthPostEntryTapPlan(
                LabyrinthPostEntryActionKind.CONFIRM_GENERIC_DIALOG,
                "确认提示弹窗",
                genericConfirmRect,
            )
        } else when (pageState) {
            LabyrinthEntryPageState.INITIAL_CHARACTER_SELECTION -> when {
                roleRewardPlan != null -> roleRewardPlan
                roleRewardWaitReason != null || manualInputMessage != null -> null
                else -> null
            }

            LabyrinthEntryPageState.CHARACTER_JOINED,
            LabyrinthEntryPageState.ITEM_REWARD,
            LabyrinthEntryPageState.LINK_CHOICE -> planRewardPage(result, frameWidth, frameHeight)

            LabyrinthEntryPageState.UNKNOWN,
            LabyrinthEntryPageState.RUN_CLEAR_CONGRATULATIONS,
            LabyrinthEntryPageState.RUN_CLEAR_CHARACTER_SUMMARY,
            LabyrinthEntryPageState.RUN_CLEAR_REWARD_ANIMATION,
            LabyrinthEntryPageState.RUN_CLEAR_CHEST_ANIMATION,
            LabyrinthEntryPageState.RUN_CLEAR_CHEST_RESULT,
            LabyrinthEntryPageState.RUN_CLEAR_RESULT -> planSettlementPage(result, frameWidth, frameHeight, finalAnimationActive, finalAnimationPage)

            LabyrinthEntryPageState.EVENT_ANIMATION -> planEventAnimationPage(result, frameWidth, frameHeight, characterAcquisitionActive)

            LabyrinthEntryPageState.RELIC_CHOICE -> planRelicChoice(sessionId, result, timestampMillis)

            LabyrinthEntryPageState.EVENT_CHOICE -> planEventChoice(sessionId, result, timestampMillis)

            LabyrinthEntryPageState.SHOP,
            LabyrinthEntryPageState.SHOP_PURCHASE_CONFIRMATION,
            LabyrinthEntryPageState.SHOP_PURCHASE_COMPLETE,
            LabyrinthEntryPageState.SHOP_EXIT_CONFIRMATION,
            -> planShopPage(result, frameWidth, frameHeight, timestampMillis).let {
                shopWaitReason = it.waitReason
                it.plan
            }

            LabyrinthEntryPageState.BATTLE_TEAM_SELECTION -> null

            LabyrinthEntryPageState.BATTLE_FAILED,
            LabyrinthEntryPageState.BATTLE_CHALLENGE,
            LabyrinthEntryPageState.BATTLE_RESULT -> planBattlePage(result, frameWidth, frameHeight)

            else -> null
        }
        if (plan == null) {
            traceReject("no-plan:${pageState.name}")
            if (activeSessionId == sessionId && postEntryStableFrames >= POST_ENTRY_STABLE_FRAMES) {
                val message = when (pageState) {
                    LabyrinthEntryPageState.BATTLE_IN_PROGRESS ->
                        "战斗中，等待结算"
                    LabyrinthEntryPageState.BATTLE_FAILED ->
                        "战斗失败页已识别，等待安全重试条件"
                    LabyrinthEntryPageState.BATTLE_CHALLENGE ->
                        when {
                            combatContext?.kind == LabyrinthCombatKind.EX || result.exEncounter?.extremeChallenge == true ->
                                "EX挑战页：等待具体遭遇攻略识别完成，禁止直接进入编组"
                            result.exEncounter?.challengeDifficultyResolved != true ->
                                "挑战页：等待确认普通/极难，禁止盲目挑战"
                            else -> "挑战页已确认，等待安全挑战条件"
                        }
                    LabyrinthEntryPageState.SHOP ->
                        shopWaitReason ?: "商店策略等待商品识别稳定"
                    LabyrinthEntryPageState.SHOP_PURCHASE_CONFIRMATION,
                    LabyrinthEntryPageState.SHOP_PURCHASE_COMPLETE,
                    -> shopWaitReason ?: "商店购买弹窗等待安全条件"
                    LabyrinthEntryPageState.EVENT_CHOICE ->
                        when (val decision = _state.value.eventChoiceDecision) {
                            is LabyrinthEventChoiceDecision.Select ->
                                decision.safetyNote ?: "事件推荐已生成，等待按钮稳定"
                            is LabyrinthEventChoiceDecision.Wait -> decision.reason
                            null -> "事件选项已识别，等待OCR语义决策"
                        }
                    LabyrinthEntryPageState.UNKNOWN ->
                        when {
                            isRoleRewardBatchWaitingForJoin() ->
                                "角色奖励批次选择过渡中；等待下一次三选一，不执行盲点"
                            finalAnimationActive -> "最终结算动画中，程序会在有限次数内推进"
                            else -> "未知页面，自动点击已暂停；等待页面识别恢复"
                        }
                    LabyrinthEntryPageState.RUN_CLEAR_RESULT,
                    LabyrinthEntryPageState.RUN_CLEAR_CONGRATULATIONS,
                    LabyrinthEntryPageState.RUN_CLEAR_CHARACTER_SUMMARY,
                    LabyrinthEntryPageState.RUN_CLEAR_REWARD_ANIMATION,
                    LabyrinthEntryPageState.RUN_CLEAR_CHEST_ANIMATION,
                    LabyrinthEntryPageState.RUN_CLEAR_CHEST_RESULT,
                    -> "${pageState.name}：暂不自动处理（结算阶段 ${postBossStage.name}，已尝试 $postEntryAttempts 次）"
                    else -> roleRewardWaitReason
                        ?: manualInputMessage
                        ?: "${pageState.name}：暂不自动处理，等待画面推进"
                }
                _state.value = _state.value.copy(message = message)
                // A different message means some sub-state advanced, so the stretch restarts.
                if (postEntryStallMessage != message || postEntryStallSince == Long.MIN_VALUE) {
                    postEntryStallMessage = message
                    postEntryStallSince = timestampMillis
                } else if (
                    labyrinthPageStallExpired(
                        stalledMillis = timestampMillis - postEntryStallSince,
                        battlePage = pageState == LabyrinthEntryPageState.BATTLE_IN_PROGRESS,
                    )
                ) {
                    finishFromPlanner(
                        sessionId,
                        "${pageState.name} 页面连续${(timestampMillis - postEntryStallSince) / 1000}秒无进展：$message；已停止并保留诊断状态",
                    )
                }
            }
            return
        }
        val (kind, label, rect) = plan
        if (rect.left + rect.width > frameWidth || rect.top + rect.height > frameHeight) {
            traceReject("target-outside-frame:$label")
            return
        }
        val roleRewardTap = kind == LabyrinthPostEntryActionKind.SELECT_ROLE_REWARD
        val relicPurchaseTap = kind == LabyrinthPostEntryActionKind.SHOP_BUY_RELIC ||
            kind == LabyrinthPostEntryActionKind.SHOP_CONFIRM_RELIC
        val imprintPurchaseTap = kind == LabyrinthPostEntryActionKind.SHOP_BUY_IMPRINT ||
            kind == LabyrinthPostEntryActionKind.SHOP_CONFIRM_IMPRINT
        dispatchPostEntryTap(
            sessionId = sessionId,
            kind = kind,
            label = label,
            rect = rect,
            timestampMillis = timestampMillis,
            roleRewardSelectionSignature = if (roleRewardTap) roleRewardSelectionSignature else null,
            roleRewardSelectedCharacterId = if (roleRewardTap) {
                (_state.value.roleRewardChoiceDecision as? LabyrinthRoleRewardChoiceDecision.Select)?.characterId
            } else {
                null
            },
            shopPurchaseRelicId = if (relicPurchaseTap) plannedShopPurchaseRelicId else null,
            shopPurchaseRoleImprintLabel = if (imprintPurchaseTap) plannedShopRoleImprintLabel else null,
        )
    }

    fun resetPostEntryStall() {
        postEntryStallSince = Long.MIN_VALUE
        postEntryStallMessage = null
    }

    fun resetPostEntryRuntimeState() {
        lastPostEntryState = null
        postEntryStableFrames = 0
        postEntryAttempts = 0
        lastPostEntryActionAt = Long.MIN_VALUE
        lastPostEntryActionLandedAt = Long.MIN_VALUE
        postEntryUnknownSince = Long.MIN_VALUE
        actionInFlightSince = Long.MIN_VALUE
        resetPostEntryStall()
    }
}
