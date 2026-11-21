package com.landosol.toolbox.labyrinth.kernel.battle

import com.landosol.toolbox.automation.AutomationAction
import com.landosol.toolbox.automation.AutomationActionResult
import com.landosol.toolbox.automation.AutomationSessionId
import com.landosol.toolbox.automation.ScreenPoint
import com.landosol.toolbox.labyrinth.*
import com.landosol.toolbox.labyrinth.kernel.*
import com.landosol.toolbox.labyrinth.kernel.KernelEventKinds as K
import com.landosol.toolbox.labyrinth.node.LabyrinthNodeTypes
import com.landosol.toolbox.labyrinth.vision.*
import com.landosol.toolbox.labyrinth.vision.LabyrinthEntryPageState.*
import java.util.UUID

data class BattleActionKey(
    val kind: String,
    val targetId: String? = null,
    val teamIndex: Int? = null,
    val viewportRevision: Long = 0L,
    val signature: String? = null,
    val rect: EntryPixelRect? = null,
)

data class BattleStepOutcome(
    val decision: KernelDecision,
    val actionKind: String? = null,
    val permitKey: BattleActionKey? = null,
    val permitToken: KernelActionPermit.Token? = null,
    val action: AutomationAction? = null,
    val message: String = "",
    val consumed: Boolean = true,
    val shouldReroll: Boolean = false,
    val causeEvent: KernelEvent? = null,
)

data class BattleRetryPolicyContext(
    val dryRun: Boolean = false,
    val batchOwnedRun: Boolean = false,
    val rerollAfterThreeFailures: Boolean = true,
    val singleBossFallbackToMultiAfterThreeFailures: Boolean = true,
    val singleBossRetryCountBeforeMulti: Int = 2,
    val route: LabyrinthRouteJson? = null,
    val activeNodeArea: Int? = null,
    val acquiredCharacterIds: List<String> = emptyList(),
    val defenseMarkStacks: Int = 0,
    val effectiveCharacterIds: Set<String> = emptySet(),
    val preferPureBossDamageSystem: Boolean = true,
    val frontlineSurvivalGateEnabled: Boolean = false,
    val currentArea: Int? = null,
)

/** Live evidence and strategy supplied by the owner of the capture and run roster. */
data class BattleFrameContext(
    val sessionId: AutomationSessionId = AutomationSessionId(0L),
    val activeNodeType: Int? = null,
    val retry: BattleRetryPolicyContext = BattleRetryPolicyContext(),
    val settings: LabyrinthStrategySettings = LabyrinthStrategySettings(),
    val planner: LabyrinthBattleTeamRecommendationPlanner? = null,
    val selectionPlanner: LabyrinthBattleTeamSelectionPlanner? = null,
    val recommendation: LabyrinthBattleTeamRecommendationResult? = null,
    val frameSeq: Long? = null,
    val sceneEpoch: Long = 0L,
)

data class BattleDomainConfig(
    val exOpenDetailTimeoutMillis: Long = BattleDomainModule.EX_ENCOUNTER_OPEN_DETAIL_TIMEOUT_MILLIS,
    val exDetailTimeoutMillis: Long = BattleDomainModule.EX_ENCOUNTER_DETAIL_TIMEOUT_MILLIS,
    val exSingleNameTimeoutMillis: Long = BattleDomainModule.EX_ENCOUNTER_SINGLE_NAME_TIMEOUT_MILLIS,
    val singleDetailMinimumWaitMillis: Long = 3_000L,
    val maxExProbeAttempts: Int = BattleDomainModule.MAX_EX_SLOT3_PROBE_ATTEMPTS,
    val actionAckTimeoutMillis: Long = BattleDomainModule.BATTLE_TEAM_ACTION_ACK_TIMEOUT_MILLIS,
    val maxUnconfirmedActions: Int = BattleDomainModule.MAX_BATTLE_TEAM_ACTION_UNCONFIRMED,
    val maxTeamStepAttempts: Int = BattleDomainModule.MAX_BATTLE_TEAM_STEP_ATTEMPTS,
    val maxScrollActions: Int = BattleDomainModule.MAX_BATTLE_TEAM_SCROLL_ACTIONS,
    val characterFeedbackTimeoutMillis: Long = BattleDomainModule.BATTLE_TEAM_CHARACTER_FEEDBACK_TIMEOUT_MILLIS,
    val actionIntervalMillis: Long = BATTLE_TEAM_ACTION_INTERVAL_MILLIS,
    val teamStallTimeoutMillis: Long = POST_ENTRY_STALL_TIMEOUT_MILLIS,
    val stableFrames: Int = BattleDomainModule.POST_ENTRY_STABLE_FRAMES,
    val anchorMinScore: Double = BattleDomainModule.POST_ENTRY_ANCHOR_MIN_SCORE,
    val postEntryActionIntervalMillis: Long = BattleDomainModule.POST_ENTRY_ACTION_INTERVAL_MILLIS,
    val specialEquipmentLeaveFrames: Int = SPECIAL_EQUIPMENT_LEAVE_STABLE_FRAMES,
    val specialEquipmentEscapeTimeoutMillis: Long = SPECIAL_EQUIPMENT_ESCAPE_TIMEOUT_MILLIS,
    val specialEquipmentStepTimeoutMillis: Long = SPECIAL_EQUIPMENT_STEP_TIMEOUT_MILLIS,
    val specialEquipmentMaxStepAttempts: Int = MAX_SPECIAL_EQUIPMENT_STEP_ATTEMPTS,
    val specialEquipmentEvidenceFrames: Int = SPECIAL_EQUIPMENT_EQUIP_EVIDENCE_STABLE_FRAMES,
    val specialEquipmentEvidenceMillis: Long = SPECIAL_EQUIPMENT_EQUIP_EVIDENCE_STABLE_MILLIS,
) {
    init {
        require(listOf(exOpenDetailTimeoutMillis, exDetailTimeoutMillis, exSingleNameTimeoutMillis,
            actionAckTimeoutMillis, characterFeedbackTimeoutMillis, teamStallTimeoutMillis,
            specialEquipmentEscapeTimeoutMillis, specialEquipmentStepTimeoutMillis).all { it > 0 })
        require(listOf(singleDetailMinimumWaitMillis, actionIntervalMillis, postEntryActionIntervalMillis,
            specialEquipmentEvidenceMillis).all { it >= 0 })
        require(listOf(maxExProbeAttempts, maxTeamStepAttempts, maxScrollActions, stableFrames,
            specialEquipmentLeaveFrames, specialEquipmentMaxStepAttempts, specialEquipmentEvidenceFrames).all { it > 0 })
        require(maxUnconfirmedActions >= 0 && anchorMinScore in 0.0..1.0)
    }
}

enum class BattleSpecialEquipmentPhase {
    INACTIVE, AWAIT_ENTRY, AWAIT_MAIN_DIALOG, AWAIT_AUTO_DIALOG, AWAIT_SECOND_MAIN_DIALOG,
    AWAIT_ACK_EQUIP, AWAIT_ACK_CANCEL, DONE,
}

data class BattleDomainSnapshot(
    val combatContext: LabyrinthCombatContext?,
    val battleRetryCount: Int,
    val awaitingRetryBattleAfterFailure: Boolean,
    val currentTeamSignatures: Set<String>,
    val failedTeamSignatures: Set<String>,
    val committedCharacterIds: Set<String>,
    val bossTeamMode: LabyrinthBossTeamMode,
    val bossEditorPreparationStage: LabyrinthBossEditorPreparationStage,
    val specialEquipmentPhase: BattleSpecialEquipmentPhase,
    val activeNestedEncounterId: String?,
    val activeArea: Int?,
    val exEncounterStrategy: LabyrinthExEncounterStrategy?,
    val exEncounterFallbackApproved: Boolean,
    val exSlot3ProbePending: Boolean,
    val exSlot3ProbeAttempts: Int,
    val exEncounterProbeStartedAt: Long?,
    val lastNormalBattleTeamSignature: String?,
    val bossEditorBlocked: Boolean,
    val bossTabDiagnostic: LabyrinthBossTabDiagnostic?,
    val unconfirmedTeamActions: Int,
    val unfindableCharacterIds: Set<String>,
)

/**
 * Plans from captured frames. The owner keeps the session and foreground guards, captures the
 * permit immediately before input, and reports the executor result to commit the transition.
 */
class BattleDomainModule(
    private val runtime: KernelRuntime,
    private val encounterStack: KernelEncounterStack? = null,
    private val config: BattleDomainConfig = BattleDomainConfig(),
) : KernelDomainModule {
    override val id: String = "battle"
    override val domain: KernelDomain = KernelDomain.BATTLE

    companion object {
        const val EX_ENCOUNTER_OPEN_DETAIL_TIMEOUT_MILLIS = 5_000L
        const val EX_ENCOUNTER_DETAIL_TIMEOUT_MILLIS = 10_000L
        const val EX_ENCOUNTER_SINGLE_NAME_TIMEOUT_MILLIS = 10_000L
        const val MAX_EX_SLOT3_PROBE_ATTEMPTS = 2
        const val BATTLE_TEAM_ACTION_ACK_TIMEOUT_MILLIS = 4_000L
        const val MAX_BATTLE_TEAM_ACTION_UNCONFIRMED = 3
        const val MAX_BATTLE_TEAM_STEP_ATTEMPTS = 3
        const val MAX_BATTLE_TEAM_SCROLL_ACTIONS = 48
        const val BATTLE_TEAM_CHARACTER_FEEDBACK_TIMEOUT_MILLIS = 4_000L
        const val POST_ENTRY_STABLE_FRAMES = 2
        const val POST_ENTRY_ANCHOR_MIN_SCORE = 0.45
        const val POST_ENTRY_ACTION_INTERVAL_MILLIS = 1_500L
    }

    private data class Proposal(
        val outcome: BattleStepOutcome,
        val evidence: ActionEvidence,
        val onExecuted: (Long) -> Unit,
    )

    private data class ActionEvidence(
        val page: LabyrinthEntryPageState,
        val width: Int,
        val height: Int,
        val teamIndex: Int?,
        val viewportRevision: Long?,
        val editorStable: Boolean,
        val equipmentLayer: LabyrinthSpecialEquipmentStage?,
        val exDetailOpen: Boolean,
        val failureLayout: LabyrinthBattleFailureLayout?,
        val otherModalOpen: Boolean,
    )

    private data class Dispatch(val proposal: Proposal, val event: KernelEvent, val at: Long)

    private data class PendingEditorAction(
        val kind: LabyrinthBattleTeamExecutionKind,
        val signature: String,
        val at: Long,
        val cause: KernelEvent?,
        val tab: LabyrinthBossTabReceipt? = null,
        val addedIds: Set<String> = emptySet(),
        val addedSignature: String? = null,
        val previousNormalSignature: String? = null,
        val previousAwaitingRetry: Boolean = false,
    )

    private data class RecommendationKey(
        val planner: LabyrinthBattleTeamRecommendationPlanner,
        val roleContext: LabyrinthRoleDecisionContext,
        val roster: List<String>,
        val failed: Set<String>,
        val retryNumber: Int,
        val requestedTeams: Int,
        val supplement: Boolean,
    )

    private var cause: KernelEvent? = null
    private var nested: KernelEncounterFrame? = null
    private var proposal: Proposal? = null
    private var dispatch: Dispatch? = null
    private val permit = KernelActionPermit<BattleActionKey> { issued, key, revision, reason ->
        emit(if (issued) K.PERMIT_ISSUED else K.PERMIT_REVOKED,
            PermitTransitionPayload(key?.targetId ?: key?.kind, revision, reason), reason)
    }

    private var lastPage: LabyrinthEntryPageState? = null
    private var stableFrames = 0
    private var lastFrameAt: Long? = null
    private var lastFrameSeq: Long? = null
    private var lastSceneEpoch: Long? = null
    private var lastSessionActionAt: Long? = null
    private var lastPostEntryActionAt: Long? = null
    private var combatContext: LabyrinthCombatContext? = null
    private var activeArea: Int? = null
    private val battleWait = LabyrinthBattleWaitPolicy()
    private var battleRetryCount = 0
    private var awaitingRetryBattleAfterFailure = false
    private val currentTeamSignatures = linkedSetOf<String>()
    private val failedTeamSignatures = linkedSetOf<String>()
    private val committedCharacterIds = linkedSetOf<String>()
    private var lastNormalBattleTeamSignature: String? = null

    private var currentExEncounterStrategy: LabyrinthExEncounterStrategy? = null
    private var exEncounterFallbackApproved = false
    private var exSlot3ProbePending = false
    private var exSlot3ProbeAttempts = 0
    private var exEncounterProbeStartedAt: Long? = null
    private var exDetailOpenedAt: Long? = null
    private var exLastKnownPage: LabyrinthEntryPageState? = null
    private var exIdentityProbeDescription = "EX识别目标"

    private var bossTeamMode = LabyrinthBossTeamMode.MULTI_TEAM
    private var bossModePinned = false
    private var requestedBossTeams: Int? = null
    private var bossFallbackMultiTeamActive = false
    private var bossEditorPreparationStage = LabyrinthBossEditorPreparationStage.INACTIVE
    private var bossEditorTeamIndex: Int? = null
    private var bossEditorBlocked = false
    private var pendingBossTeamAdvance: Pair<Int, List<String>>? = null
    private var preparedBossFirstTeamIds = emptyList<String>()
    private var bossMultiTeamTargetCount: Int? = null
    private var pendingEditorAction: PendingEditorAction? = null
    private var bossTabDiagnostic: LabyrinthBossTabDiagnostic? = null
    private var unconfirmedTeamActions = 0
    private var teamActionStopReason: String? = null
    private var lastTeamProgressAt: Long? = null
    private var lastExecutionKey: String? = null
    private var executionAttempts = 0
    private var lastTeamActionAt: Long? = null
    private var scrollActions = 0
    private val rosterSearch = LabyrinthBattleRosterSearch()
    private val exhaustedFilters = linkedSetOf<LabyrinthBattleElementFilter>()
    private val unfindableCharacterIds = linkedSetOf<String>()
    private var rosterSearchTarget: List<String>? = null
    private var cachedRecommendation: Pair<RecommendationKey, LabyrinthBattleTeamRecommendationResult>? = null
    private val defaultSelectionPlanner = LabyrinthBattleTeamSelectionPlanner(emptyMap())

    private var equipmentStage = LabyrinthSpecialEquipmentFlowStage.INACTIVE
    private var equipmentStartedAt: Long? = null
    private var equipmentLastDispatchedAt: Long? = null
    private var equipmentAttempts = 0
    private var equipmentEvidence = LabyrinthSpecialEquipmentEquipEvidenceStreak()
    private var equipmentAwayFrames = 0
    private var equipmentTeamIndex: Int? = null
    private var equipmentDialogObserved = false
    private var specialEquipmentModalCloseSettlePending = false
    private var equipmentAbandonedReason: String? = null
    private val equipmentEscapeSpent = mutableSetOf<LabyrinthSpecialEquipmentStage>()
    private var equipmentPendingEscape: Pair<LabyrinthSpecialEquipmentStage, Long>? = null
    private var equipmentEscapeAckDeadline: Long? = null

    override fun onKernelEvent(event: KernelEvent, ctx: KernelRuntime) {
        val lifecycle = event.payload as? SessionLifecyclePayload ?: return
        if (ctx === runtime && event.domain == domain && event.traceId == ctx.traceId && lifecycle.phase == "stop") {
            reset(event.traceId)
        }
    }

    override fun reset(traceId: String) = synchronized(runtime) {
        exitNestedBattleIfActive("session_reset")
        clearBattle()
        cause = null
        nested = null
        lastNormalBattleTeamSignature = null
        lastSessionActionAt = null
        lastPostEntryActionAt = null
        lastFrameAt = null
        lastFrameSeq = null
        lastSceneEpoch = null
    }

    /** Starts an independent encounter while retaining the previous ordinary team and session cooldown. */
    fun beginBattle(
        context: LabyrinthCombatContext,
        area: Int? = null,
        bossMode: LabyrinthBossTeamMode = LabyrinthBossTeamMode.MULTI_TEAM,
        plannedBossTeams: Int? = null,
    ) = synchronized(runtime) {
        check(nested == null) { "An event-owned battle is already active" }
        require(plannedBossTeams == null || plannedBossTeams in 1..3)
        clearBattle()
        bossTeamMode = bossMode
        bossModePinned = true
        requestedBossTeams = plannedBossTeams
        bossMultiTeamTargetCount = plannedBossTeams
        combatContext = context
        activeArea = area
        reduce("BATTLE_STARTED", mapOf("combatKind" to context.kind.name))
        Unit
    }

    fun enterNestedBattle(parentEventEncounterId: String, combatKind: LabyrinthCombatKind, area: Int?): KernelEncounterFrame =
        synchronized(runtime) {
            require(parentEventEncounterId.isNotBlank())
            check(nested == null) { "An event-owned battle is already active" }
            if (encounterStack != null) {
                val parent = encounterStack.peek()
                check(parent?.id == parentEventEncounterId && parent.kind == KernelEncounterKind.EVENT) {
                    "The parent event must be at the top of the encounter stack"
                }
            }
            clearBattle()
            val parentCause = runtime.log.byTraceId(runtime.traceId).lastOrNull {
                (it.payload as? StateReducedPayload)?.encounterId == parentEventEncounterId
            }
            cause = parentCause ?: cause
            val frame = KernelEncounterFrame(
                id = "battle-${UUID.randomUUID()}",
                kind = KernelEncounterKind.BATTLE,
                parentId = parentEventEncounterId,
                label = labyrinthBattleKindLabel(combatKind),
                metadata = buildMap {
                    put("combatKind", combatKind.name)
                    if (area != null) put("area", area.toString())
                },
            )
            nested = encounterStack?.push(frame) ?: frame
            combatContext = LabyrinthCombatContext(combatKind)
            activeArea = area
            reduce("BATTLE_ENTERED", frame.metadata)
            requireNotNull(nested)
        }

    fun exitNestedBattleIfActive(outcome: String): KernelEncounterFrame? = synchronized(runtime) {
        val frame = nested ?: return@synchronized null
        if (encounterStack != null && encounterStack.peek()?.id != frame.id) return@synchronized null
        reduce("BATTLE_EXITED", mapOf("outcome" to outcome))
        clearBattle()
        encounterStack?.popExpected(frame.id, "BATTLE_EXITED")
        nested = null
        frame
    }

    fun resetExEncounterTracking() = synchronized(runtime) {
        if (proposal?.outcome?.actionKind in setOf("EX_PROBE_DETAIL", "EX_CLOSE_DETAIL", "BATTLE_START_CHALLENGE")) {
            invalidateProposal("ex_tracking_reset")
            dispatch = null
        }
        resetEx()
    }

    fun snapshot(): BattleDomainSnapshot = synchronized(runtime) {
        BattleDomainSnapshot(
            combatContext, battleRetryCount, awaitingRetryBattleAfterFailure, currentTeamSignatures.toSet(),
            failedTeamSignatures.toSet(), committedCharacterIds.toSet(), bossTeamMode, bossEditorPreparationStage,
            BattleSpecialEquipmentPhase.valueOf(equipmentStage.name), nested?.id, activeArea,
            currentExEncounterStrategy, exEncounterFallbackApproved, exSlot3ProbePending, exSlot3ProbeAttempts,
            exEncounterProbeStartedAt, lastNormalBattleTeamSignature, bossEditorBlocked, bossTabDiagnostic,
            unconfirmedTeamActions, unfindableCharacterIds.toSet(),
        )
    }

    fun evaluateFrame(
        frame: LabyrinthEntryFrameResult,
        timestampMillis: Long,
        context: BattleFrameContext = BattleFrameContext(),
    ): BattleStepOutcome = synchronized(runtime) {
        require(timestampMillis >= 0)
        val previousAt = lastFrameAt
        val previousSeq = lastFrameSeq
        val sameScene = lastSceneEpoch == context.sceneEpoch
        if ((previousAt != null && timestampMillis < previousAt) ||
            (sameScene && (
                (context.frameSeq != null && previousSeq != null && context.frameSeq <= previousSeq) ||
                (context.frameSeq == null && timestampMillis == previousAt)))) {
            return@synchronized BattleStepOutcome(KernelDecision.Wait("等待更新的战斗画面"), causeEvent = cause)
        }
        lastFrameAt = timestampMillis
        lastFrameSeq = context.frameSeq
        lastSceneEpoch = context.sceneEpoch
        val page = frame.observation.state
        cause = runtime.emit(domain, K.FRAME_OBSERVED, FrameObservedPayload(page.name, timestampMillis), cause,
            frameSeq = context.frameSeq, sceneEpoch = context.sceneEpoch)

        val inFlight = dispatch
        if (inFlight != null) {
            // A newer capture can invalidate input queued at the backend. The receipt still
            // belongs to this dispatch if native input had already finished.
            if (!sameScene || actionEvidence(frame) != inFlight.proposal.evidence) permit.revoke("dispatch_evidence_changed")
            return@synchronized wait("等待已派发战斗动作的执行回执")
        }
        invalidateProposal(if (sameScene) "new_frame" else "scene_changed")
        val previousPage = lastPage
        if (previousPage != page || !sameScene) {
            lastPage = page
            stableFrames = 0
            reduce("BATTLE_PAGE_CHANGED")
            if (page == BATTLE_TEAM_SELECTION && lastTeamProgressAt == null) lastTeamProgressAt = timestampMillis
            if (page != BATTLE_TEAM_SELECTION && page != UNKNOWN) lastTeamProgressAt = null
        }
        stableFrames++

        if (labyrinthExIdentityProbeRestarts(exLastKnownPage, page, currentExEncounterStrategy != null)) {
            exSlot3ProbePending = false
            exSlot3ProbeAttempts = 0
            exEncounterProbeStartedAt = timestampMillis
            exDetailOpenedAt = null
            exIdentityProbeDescription = "EX识别目标"
            reduce("EX_IDENTITY_PROBE_RESTARTED")
        }
        if (page != UNKNOWN) exLastKnownPage = page

        if (frame.relicDetailObservation != null || frame.nodeMoveConfirmation != null) {
            battleWait.reset()
            return@synchronized wait("当前画面由其他弹窗接管", consumed = false)
        }
        adoptBossMode(context.settings)
        val persistedBoss = labyrinthPersistedBossNode(context.retry.route)
        val settlementContext = combatContext ?: persistedBoss?.let { LabyrinthCombatContext(LabyrinthCombatKind.BOSS) }
        val bossNext = labyrinthBossSettlementNextButtonRect(page, settlementContext,
            frame.anchorMatches[EntryAnchorId.BATTLE_RESULT_NEXT_BUTTON],
            frame.anchorMatches[EntryAnchorId.BATTLE_RESULT_BOSS_SUMMARY_NEXT_BUTTON])
        if (bossNext != null) {
            if (combatContext == null) {
                activeArea = context.retry.activeNodeArea ?: persistedBoss?.area ?: activeArea
                setCombatContext(requireNotNull(settlementContext), "BOSS_CONTEXT_RESTORED")
                adoptBossMode(context.settings)
            }
            acknowledgeEditorAction()
            recordBattleProgress()
            battleWait.reset()
            return@synchronized postTap(frame, timestampMillis, LabyrinthPostEntryActionKind.BOSS_SETTLEMENT_NEXT,
                "Boss战后结算：下一步", bossNext, requireStable = false)
        }

        handleUnconfirmedEditorAction(frame, timestampMillis)?.let { return@synchronized it }
        if (page == BATTLE_IN_PROGRESS || page == BATTLE_RESULT) recordBattleProgress()

        if (page != BATTLE_TEAM_SELECTION || frame.specialEquipment?.stage != null) {
            handleEquipment(frame, timestampMillis, context.settings, teamReady = false)?.let { return@synchronized it }
        }
        if (frame.exEncounter?.monsterDetailOpen == true) {
            return@synchronized evaluateExDetail(frame, timestampMillis)
        }
        val resultNext = anchor(frame, EntryAnchorId.BATTLE_RESULT_NEXT_BUTTON)
        when (battleWait.observe(page, timestampMillis, resultNext != null, frame.battleHudDigest, plannedTeams())) {
            LabyrinthBattleWaitDecision.WAIT -> return@synchronized wait("等待战斗结算（最长${battleWait.currentMaxMinutes()}分钟）")
            LabyrinthBattleWaitDecision.TIMED_OUT -> return@synchronized need(
                "BATTLE_WAIT_TIMEOUT", KernelErrorCategory.TIMEOUT,
                "等待战斗结算达到${battleWait.effectiveLimitMinutes()}分钟",
            )
            LabyrinthBattleWaitDecision.NONE -> Unit
        }
        when (page) {
            BATTLE_CHALLENGE -> evaluateChallenge(frame, timestampMillis, context)
            BATTLE_TEAM_SELECTION -> evaluateTeam(frame, timestampMillis, context)
            BATTLE_FAILED -> evaluateFailure(frame, timestampMillis, context)
            BATTLE_RESULT -> postTap(frame, timestampMillis, LabyrinthPostEntryActionKind.BATTLE_RESULT_NEXT,
                "战斗结算：下一步", resultNext ?: fallback(frame, LabyrinthFallbackTap.BOTTOM_RIGHT))
            else -> wait("等待战斗域画面", consumed = false)
        }
    }

    fun evaluateBattleFailureFrame(
        frame: LabyrinthEntryFrameResult,
        timestampMillis: Long,
        policy: BattleRetryPolicyContext,
        planner: LabyrinthBattleTeamRecommendationPlanner? = null,
    ): BattleStepOutcome {
        require(frame.observation.state == BATTLE_FAILED)
        return evaluateFrame(frame, timestampMillis, BattleFrameContext(retry = policy, planner = planner))
    }

    /**
     * Captures the current proposal for one dispatch. Use the returned token at the native
     * input guard, and report the receipt even if input was suppressed or rejected.
     */
    fun onActionDispatched(outcome: BattleStepOutcome, timestampMillis: Long): KernelActionPermit.Token? =
        synchronized(runtime) {
            val current = proposal ?: return@synchronized null
            if (dispatch != null || current.outcome !== outcome) return@synchronized null
            val key = outcome.permitKey ?: return@synchronized null
            val token = permit.capture(key, timestampMillis) ?: run {
                invalidateProposal("dispatch_permit_expired")
                return@synchronized null
            }
            val target = key.targetId ?: key.kind
            emit(K.PERMIT_ISSUED, PermitTransitionPayload(target, permit.currentRevision, "dispatch_capture"), "dispatch_capture")
            val dispatched = emit(K.ACTION_DISPATCHED, ActionDispatchedPayload(target), key.kind)
            dispatch = Dispatch(current, dispatched, timestampMillis)
            token
        }

    fun onActionReceipt(
        outcome: BattleStepOutcome,
        result: AutomationActionResult,
        timestampMillis: Long,
    ): KernelEvent? = synchronized(runtime) {
        val active = dispatch ?: return@synchronized null
        if (active.proposal.outcome !== outcome || timestampMillis < active.at) return@synchronized null
        val verdict = when (result) {
            is AutomationActionResult.Executed -> if (result.action == outcome.action) "EXECUTED" else "ACTION_MISMATCH"
            is AutomationActionResult.DryRun -> "DRY_RUN"
            is AutomationActionResult.Rejected -> "REJECTED"
            AutomationActionResult.StaleSession -> "STALE_SESSION"
            AutomationActionResult.Paused -> "PAUSED"
        }
        completeDispatch(active, verdict, timestampMillis)
    }

    /** Convenience callback for a successfully executed, captured relic-revive action. */
    fun onBattleFailureNextExecuted() = acknowledgeDispatched("BATTLE_FAILURE_NEXT")

    /** Convenience callback for a successfully executed, captured retry or Boss mode switch. */
    fun onBattleRetryExecuted() = acknowledgeDispatched("BATTLE_RETRY", "BATTLE_RETRY_SWITCH_MULTI")

    private fun acknowledgeDispatched(vararg kinds: String): KernelEvent? = synchronized(runtime) {
        val active = dispatch ?: return@synchronized null
        if (active.proposal.outcome.actionKind !in kinds) return@synchronized null
        completeDispatch(active, "EXECUTED", maxOf(active.at, lastFrameAt ?: active.at))
    }

    private fun completeDispatch(active: Dispatch, verdict: String, at: Long): KernelEvent {
        val key = requireNotNull(active.proposal.outcome.permitKey)
        val receipt = emit(K.ACTION_RECEIPT_OBSERVED, ActionReceiptPayload(key.targetId ?: key.kind, verdict),
            verdict, parent = active.event)
        invalidateProposal("receipt_${verdict.lowercase()}")
        dispatch = null
        if (verdict == "EXECUTED") {
            lastSessionActionAt = at
            active.proposal.onExecuted(at)
        }
        return receipt
    }

    private fun invalidateProposal(reason: String) {
        proposal = null
        permit.revoke(reason)
    }

    private fun wait(message: String, consumed: Boolean = true): BattleStepOutcome =
        conclude(KernelDecision.Wait(message), message, consumed = consumed)

    private fun need(code: String, category: KernelErrorCategory, message: String): BattleStepOutcome =
        conclude(KernelDecision.NeedEvidence(KernelError(code, category, id, message), message), message, code)

    private fun finish(message: String, shouldReroll: Boolean): BattleStepOutcome =
        conclude(KernelDecision.FinishRun(cleared = false, reason = message), message, shouldReroll = shouldReroll)

    private fun conclude(
        decision: KernelDecision,
        message: String,
        rule: String = decision.javaClass.simpleName,
        consumed: Boolean = true,
        shouldReroll: Boolean = false,
        key: BattleActionKey? = null,
    ): BattleStepOutcome {
        emit(K.EVENT_RULE_EVALUATED, PolicyRuleEvaluatedPayload(rule, decision.javaClass.simpleName), rule, message)
        val proposed = emit(K.EVENT_DECISION_PROPOSED,
            DecisionProposedPayload(nested?.id ?: id, key?.targetId ?: key?.kind, decision is KernelDecision.Act, message),
            rule, message)
        val intervention = runtime.latestIntervention?.takeIf { it.eventSeq == proposed.eventSeq }
        val veto = intervention?.verdict as? KernelHookVerdict.Veto
        if (veto != null) {
            cause = intervention.cause
            invalidateProposal("hook_veto")
            val reduced = reduce("BATTLE_HOOK_VETO", mapOf("reason" to veto.reason))
            return BattleStepOutcome(KernelDecision.Wait(veto.reason), message = veto.reason,
                consumed = consumed, causeEvent = reduced)
        }
        if (dispatch == null && decision !is KernelDecision.Act) invalidateProposal(rule)
        return BattleStepOutcome(decision, message = message, consumed = consumed,
            shouldReroll = shouldReroll, causeEvent = proposed)
    }

    private fun offer(
        frame: LabyrinthEntryFrameResult,
        at: Long,
        key: BattleActionKey,
        label: String,
        action: AutomationAction,
        onExecuted: (Long) -> Unit = {},
    ): BattleStepOutcome {
        val width = frame.frameWidth
        val height = frame.frameHeight
        val rect = key.rect
        fun pointFits(point: ScreenPoint) = point.x >= 0 && point.y >= 0 && point.x < width && point.y < height
        val geometrySafe = when (action) {
            is AutomationAction.Tap -> pointFits(action.point) && rect != null &&
                rect.left >= 0 && rect.top >= 0 && rect.width > 0 && rect.height > 0 &&
                rect.left.toLong() + rect.width <= width && rect.top.toLong() + rect.height <= height
            is AutomationAction.Swipe -> pointFits(action.start) && pointFits(action.end)
            AutomationAction.Back -> false
        }
        if (!geometrySafe) return need("BATTLE_ACTION_GEOMETRY", KernelErrorCategory.EVIDENCE, "动作矩形与当前帧尺寸不符")
        val decision = KernelDecision.Act(if (action is AutomationAction.Swipe) "swipe" else "tap",
            label, key.targetId ?: key.kind, rect, key.kind)
        val evaluated = conclude(decision, label, key.kind, key = key)
        if (evaluated.decision !is KernelDecision.Act) return evaluated
        val token = permit.issue(key, at, key.kind)
        val outcome = evaluated.copy(actionKind = key.kind, permitKey = key,
            permitToken = token, action = action, causeEvent = cause)
        proposal = Proposal(outcome, actionEvidence(frame), onExecuted)
        return outcome
    }

    private fun actionEvidence(frame: LabyrinthEntryFrameResult): ActionEvidence {
        val editor = frame.battleTeamSelection
        return ActionEvidence(frame.observation.state, frame.frameWidth, frame.frameHeight, editor?.bossTeamIndex,
            editor?.viewportRevision, editor?.recognitionState == LabyrinthBattleTeamRecognitionState.STABLE,
            frame.specialEquipment?.stage, frame.exEncounter?.monsterDetailOpen == true, frame.battleFailure?.layout,
            frame.relicDetailObservation != null || frame.nodeMoveConfirmation != null || frame.battleEndConfirmation != null)
    }

    private fun tap(rect: EntryPixelRect) =
        AutomationAction.Tap(ScreenPoint(rect.left + rect.width / 2f, rect.top + rect.height / 2f))

    private fun anchor(frame: LabyrinthEntryFrameResult, id: String): EntryPixelRect? =
        frame.anchorMatches[id]?.takeIf { it.score >= config.anchorMinScore }?.rect

    private fun fallback(frame: LabyrinthEntryFrameResult, target: LabyrinthFallbackTap): EntryPixelRect? =
        if (frame.frameWidth >= 3 && frame.frameHeight >= 3) target.rect(frame.frameWidth, frame.frameHeight) else null

    private fun intervalElapsed(at: Long, since: Long?, interval: Long): Boolean =
        since == null || at - since >= interval

    private fun postTap(
        frame: LabyrinthEntryFrameResult,
        at: Long,
        kind: LabyrinthPostEntryActionKind,
        label: String,
        rect: EntryPixelRect?,
        requireStable: Boolean = true,
        onExecuted: (Long) -> Unit = {},
    ): BattleStepOutcome {
        if (requireStable && stableFrames < config.stableFrames) return wait("等待战斗页面稳定")
        if (rect == null) return wait("等待可信的战斗按钮矩形")
        if (!intervalElapsed(at, lastPostEntryActionAt, config.postEntryActionIntervalMillis) ||
            !intervalElapsed(at, lastSessionActionAt, config.actionIntervalMillis)) return wait("等待战斗动作间隔")
        return offer(frame, at, BattleActionKey(kind.name, rect = rect), label, tap(rect)) {
            lastPostEntryActionAt = it
            onExecuted(it)
        }
    }

    private fun setCombatContext(context: LabyrinthCombatContext, reason: String) {
        combatContext = context
        cachedRecommendation = null
        reduce(reason, mapOf("combatKind" to context.kind.name))
    }

    private fun recordBattleProgress() {
        if (!awaitingRetryBattleAfterFailure) return
        awaitingRetryBattleAfterFailure = false
        reduce("RETRY_BATTLE_STARTED")
    }

    private fun plannedTeams(): Int =
        if (combatContext?.kind == LabyrinthCombatKind.BOSS && bossTeamMode == LabyrinthBossTeamMode.MULTI_TEAM) {
            bossMultiTeamTargetCount ?: requestedBossTeams ?: currentTeamSignatures.size.takeIf { it > 0 }?.coerceAtMost(3) ?: 3
        } else 1

    private fun adoptBossMode(settings: LabyrinthStrategySettings) {
        if (bossModePinned || combatContext?.kind != LabyrinthCombatKind.BOSS) return
        bossTeamMode = settings.bossTeamMode
        bossModePinned = true
    }

    private fun evaluateChallenge(frame: LabyrinthEntryFrameResult, at: Long, input: BattleFrameContext): BattleStepOutcome {
        val evidence = frame.exEncounter
        val route = input.retry.route
        val persistedBoss = labyrinthPersistedBossNode(route)
        val bossOnRoute = input.activeNodeType == LabyrinthNodeTypes.BOSS ||
            (input.activeNodeType == null && combatContext == null && persistedBoss != null)
        when {
            evidence?.extremeChallenge == true && combatContext?.kind != LabyrinthCombatKind.EX -> {
                resetEx()
                exLastKnownPage = BATTLE_CHALLENGE
                exEncounterProbeStartedAt = at
                val targets = when {
                    evidence.multiMonsterLikely -> 5
                    evidence.specialDualLikely -> 2
                    else -> 1
                }
                setCombatContext(LabyrinthCombatContext(LabyrinthCombatKind.EX, targetCount = targets), "EX_CONTEXT_RECOGNIZED")
            }
            bossOnRoute && combatContext?.kind != LabyrinthCombatKind.BOSS && evidence?.extremeChallenge != true -> {
                resetEx()
                exLastKnownPage = BATTLE_CHALLENGE
                activeArea = input.retry.activeNodeArea ?: persistedBoss?.area ?: activeArea
                setCombatContext(LabyrinthCombatContext(LabyrinthCombatKind.BOSS,
                    targetCount = combatContext?.targetCount ?: 1), "BOSS_CONTEXT_RESTORED")
            }
            combatContext == null && evidence?.challengeDifficultyResolved == true -> {
                val node = labyrinthPersistedCurrentNode(route)
                val eventBattle = input.activeNodeType == LabyrinthNodeTypes.EVENT ||
                    (input.activeNodeType == null && node?.blockType == LabyrinthNodeTypes.EVENT)
                activeArea = input.retry.activeNodeArea ?: node?.area ?: activeArea
                setCombatContext(LabyrinthCombatContext(LabyrinthCombatKind.NORMAL),
                    if (eventBattle) "EVENT_NORMAL_CONTEXT_RESTORED" else "NORMAL_CONTEXT_RECOGNIZED")
            }
        }

        adoptBossMode(input.settings)
        val kind = combatContext?.kind
        if (kind != LabyrinthCombatKind.BOSS && kind != LabyrinthCombatKind.EX &&
            evidence?.challengeDifficultyResolved != true) {
            val started = exEncounterProbeStartedAt ?: at.also { exEncounterProbeStartedAt = it }
            return if (at - started >= config.exSingleNameTimeoutMillis) {
                need("BATTLE_CHALLENGE_TITLE_TIMEOUT", KernelErrorCategory.TIMEOUT, "未能可靠确认挑战页普通/极难标题")
            } else wait("等待挑战页普通/极难标题")
        }
        if (kind == LabyrinthCombatKind.EX) {
            if (exSlot3ProbePending) return evaluateExProbe(frame, at, evidence)
            if (currentExEncounterStrategy == null && evidence?.trusted == true &&
                !evidence.multiMonsterLikely && !evidence.specialDualLikely) {
                knownExStrategy(evidence.encounterId)?.let(::setExStrategy)
            }
            if (currentExEncounterStrategy == null && !exEncounterFallbackApproved) {
                return evaluateExProbe(frame, at, evidence)
            }
        }
        if (!labyrinthBattleChallengeCanAutoStart(combatContext, evidence?.challengeDifficultyResolved == true,
                evidence?.extremeChallenge == true, currentExEncounterStrategy != null, exEncounterFallbackApproved)) {
            return wait("等待安全挑战条件")
        }
        return postTap(frame, at, LabyrinthPostEntryActionKind.BATTLE_START_CHALLENGE, "发起挑战",
            anchor(frame, EntryAnchorId.BATTLE_CHALLENGE_BUTTON) ?: fallback(frame, LabyrinthFallbackTap.CHALLENGE_START)) {
            reduce("BATTLE_CHALLENGE_STARTED")
        }
    }

    private fun knownExStrategy(encounterId: String?): LabyrinthExEncounterStrategy? =
        LabyrinthExEncounterCatalog.all.firstOrNull { it.id == encounterId && !it.identityName.startsWith("？？？") }

    private fun setExStrategy(strategy: LabyrinthExEncounterStrategy) {
        if (currentExEncounterStrategy == strategy) return
        currentExEncounterStrategy = strategy
        setCombatContext((combatContext ?: LabyrinthCombatContext(LabyrinthCombatKind.EX))
            .copy(kind = LabyrinthCombatKind.EX, targetCount = strategy.targetCount), "EX_IDENTITY_RESOLVED")
    }

    private fun evaluateExDetail(frame: LabyrinthEntryFrameResult, at: Long): BattleStepOutcome {
        val evidence = requireNotNull(frame.exEncounter)
        if (!exSlot3ProbePending) return wait("魔物详情由人工打开，等待返回挑战页")
        val opened = exDetailOpenedAt ?: at.also { exDetailOpenedAt = it }
        val timedOut = at - opened >= config.exDetailTimeoutMillis
        val hasTrustedName = evidence.trusted && evidence.encounterId != null
        if (!hasTrustedName && !timedOut) return wait("等待${exIdentityProbeDescription}详情名称稳定识别")
        val strategy = if (hasTrustedName) knownExStrategy(evidence.encounterId) else currentExEncounterStrategy
        if (strategy != null) setExStrategy(strategy)
        val rect = evidence.closeButtonRect
        if (rect == null) {
            return if (timedOut) need("EX_DETAIL_CLOSE_UNCONFIRMED", KernelErrorCategory.EVIDENCE, "未确认EX魔物详情关闭按钮")
            else wait("等待可信的EX魔物详情关闭按钮")
        }
        val label = if (strategy == null) "关闭无已知攻略的EX详情" else "EX识别完成：关闭魔物详情"
        return postTap(frame, at, LabyrinthPostEntryActionKind.EX_CLOSE_DETAIL, label, rect, requireStable = false) {
            exSlot3ProbePending = false
            exEncounterProbeStartedAt = null
            exDetailOpenedAt = null
            exEncounterFallbackApproved = currentExEncounterStrategy == null
            reduce("EX_DETAIL_CLOSED", mapOf("fallbackApproved" to exEncounterFallbackApproved.toString()))
        }
    }

    private fun evaluateExProbe(
        frame: LabyrinthEntryFrameResult,
        at: Long,
        evidence: LabyrinthExEncounterObservation?,
    ): BattleStepOutcome {
        val started = exEncounterProbeStartedAt ?: at.also { exEncounterProbeStartedAt = it }
        if (exSlot3ProbePending) {
            if (at - started < config.exOpenDetailTimeoutMillis) return wait("等待${exIdentityProbeDescription}详情打开")
            if (exSlot3ProbeAttempts >= config.maxExProbeAttempts) {
                return need("EX_DETAIL_OPEN_TIMEOUT", KernelErrorCategory.TIMEOUT,
                    "${exIdentityProbeDescription}详情在${exSlot3ProbeAttempts}次探测后仍未打开")
            }
            exSlot3ProbePending = false
            exEncounterProbeStartedAt = at
            exDetailOpenedAt = null
            reduce("EX_DETAIL_PROBE_RETRY_READY")
            return wait("EX详情未打开，准备有限重试")
        }

        val multi = evidence?.multiMonsterLikely == true
        val dual = evidence?.specialDualLikely == true
        val elapsed = at - started
        val rect = when {
            evidence != null && multi -> evidence.slot3InfoButtonRect
            evidence != null && dual -> evidence.specialDualIdentityInfoButtonRect
            else -> labyrinthSingleExDetailProbeRect(
                frame.observation.state, isEx = true, encounterResolved = false,
                multiMonsterLikely = false, specialDualLikely = false, elapsedMillis = elapsed,
                stableFrames = stableFrames, attempts = exSlot3ProbeAttempts,
                frameWidth = frame.frameWidth, frameHeight = frame.frameHeight,
                minimumWaitMillis = config.singleDetailMinimumWaitMillis, minimumStableFrames = config.stableFrames,
                maxAttempts = config.maxExProbeAttempts,
            )
        }
        if (rect != null && exSlot3ProbeAttempts < config.maxExProbeAttempts) {
            val description = when {
                multi -> "第3只魔物"
                dual -> "特殊EX右侧本体"
                else -> "单体魔物"
            }
            return postTap(frame, at, LabyrinthPostEntryActionKind.EX_PROBE_DETAIL, "识别EX：查看${description}详情", rect) {
                exIdentityProbeDescription = description
                exSlot3ProbeAttempts++
                exSlot3ProbePending = true
                exEncounterProbeStartedAt = it
                exDetailOpenedAt = null
                reduce("EX_DETAIL_PROBE_STARTED", mapOf("attempt" to exSlot3ProbeAttempts.toString()))
            }
        }
        if (elapsed >= config.exSingleNameTimeoutMillis) {
            return need("EX_IDENTITY_TIMEOUT", KernelErrorCategory.TIMEOUT, "未能建立可信EX身份")
        }
        return if (multi || dual) wait("等待可信的EX信息按钮") else wait("等待单体EX名称或详情探测")
    }

    private fun evaluateFailure(frame: LabyrinthEntryFrameResult, at: Long, input: BattleFrameContext): BattleStepOutcome {
        if (frame.battleEndConfirmation != null) return wait("战斗结束确认弹窗保持人工控制")
        val failure = frame.battleFailure
        if (failure?.layout == LabyrinthBattleFailureLayout.TWO_BUTTON) {
            battleWait.reset()
            val plan = labyrinthBattleFailureTapPlan(failure, pendingSingleBossFallbackToMulti = false)
            return postTap(frame, at, plan.kind, plan.label, plan.rect) {
                battleWait.reset()
                stableFrames = 0
                reduce("BATTLE_FAILURE_NEXT_EXECUTED")
            }
        }
        if (awaitingRetryBattleAfterFailure) return wait("已执行重新挑战，等待下一场战斗开始")
        if (stableFrames < config.stableFrames) return wait("等待战斗失败页稳定")
        if (failure == null) return need("BATTLE_FAILURE_LAYOUT_UNCONFIRMED", KernelErrorCategory.EVIDENCE,
            "战斗失败页的按钮结构未达到安全识别线")
        val kind = combatContext?.kind ?: return need("BATTLE_CONTEXT_MISSING", KernelErrorCategory.STATE, "缺少本次战斗上下文")
        val policy = input.retry
        val singleFallback = kind == LabyrinthCombatKind.BOSS && bossTeamMode == LabyrinthBossTeamMode.SINGLE_TEAM &&
            policy.singleBossFallbackToMultiAfterThreeFailures
        val switchToMulti = labyrinthShouldSwitchSingleBossToMulti(policy.singleBossFallbackToMultiAfterThreeFailures,
            kind, bossTeamMode, battleRetryCount, policy.singleBossRetryCountBeforeMulti)

        if (!switchToMulti) {
            if (!policy.dryRun && !singleFallback &&
                labyrinthShouldRerollAfterBattleFailure(policy.rerollAfterThreeFailures, battleRetryCount)) {
                return finish("${labyrinthBattleKindLabel(kind)}连续3次挑战失败", shouldReroll = true)
            }
            val retryLimit = labyrinthEffectiveBattleRetryLimit(kind, policy.rerollAfterThreeFailures,
                policy.singleBossRetryCountBeforeMulti.takeIf { singleFallback })
            val limitMessage = "${labyrinthBattleKindLabel(kind)}达到自动重试上限（已重试${battleRetryCount}次）"
            if (!policy.dryRun && labyrinthBatchOwnedRunEndsAtRetryLimit(policy.batchOwnedRun, battleRetryCount, retryLimit)) {
                return finish(limitMessage, shouldReroll = true)
            }
            if (battleRetryCount >= retryLimit) return need("BATTLE_RETRY_LIMIT", KernelErrorCategory.POLICY, limitMessage)
            val failed = failedTeamSignatures + currentTeamSignatures
            val planner = input.planner
            if (planner != null && failed.isNotEmpty()) {
                val candidate = recommendation(planner, policy, roster(policy), failed, battleRetryCount + 1)
                if (candidate is LabyrinthBattleTeamRecommendationResult.Unavailable) {
                    val message = "无可替换的未失败重试阵容：${candidate.reason}"
                    return if (!policy.dryRun && (policy.batchOwnedRun || (!singleFallback && policy.rerollAfterThreeFailures))) {
                        finish(message, shouldReroll = true)
                    } else need("NO_RECOVERABLE_RETRY_TEAM", KernelErrorCategory.POLICY, message)
                }
            }
        }
        val plan = labyrinthBattleFailureTapPlan(failure, switchToMulti)
        return postTap(frame, at, plan.kind, plan.label, plan.rect) { commitRetry(switchToMulti) }
    }

    private fun commitRetry(switchToMulti: Boolean) {
        failedTeamSignatures += currentTeamSignatures
        currentTeamSignatures.clear()
        committedCharacterIds.clear()
        if (switchToMulti) {
            bossTeamMode = LabyrinthBossTeamMode.MULTI_TEAM
            bossFallbackMultiTeamActive = true
            requestedBossTeams = null
            battleRetryCount = 0
            failedTeamSignatures.clear()
        } else {
            battleRetryCount++
        }
        awaitingRetryBattleAfterFailure = true
        battleWait.reset()
        resetBossEditor()
        if (combatContext?.kind == LabyrinthCombatKind.BOSS) bossEditorPreparationStage = LabyrinthBossEditorPreparationStage.TEAM_1
        combatContext = combatContext?.copy(teamIndex = 1)
        resetTeamExecution()
        resetEquipment()
        lastTeamProgressAt = null
        stableFrames = 0
        reduce(if (switchToMulti) "BATTLE_RETRY_SWITCHED_MULTI" else "BATTLE_RETRY_EXECUTED",
            mapOf("retryCount" to battleRetryCount.toString()))
    }

    private fun roster(policy: BattleRetryPolicyContext): List<String> =
        policy.acquiredCharacterIds.map(::canonicalLabyrinthRoleId).distinct().filterNot { it in unfindableCharacterIds }

    private fun recommendation(
        planner: LabyrinthBattleTeamRecommendationPlanner,
        policy: BattleRetryPolicyContext,
        roster: List<String>,
        failed: Set<String>,
        retryNumber: Int,
        supplement: Boolean = false,
    ): LabyrinthBattleTeamRecommendationResult {
        val combat = requireNotNull(combatContext)
        val boss = combat.kind == LabyrinthCombatKind.BOSS
        val strategy = when (combat.kind) {
            LabyrinthCombatKind.EX -> currentExEncounterStrategy
            LabyrinthCombatKind.BOSS -> labyrinthBossEncounterStrategy(policy.route)
            LabyrinthCombatKind.NORMAL -> null
        }
        val roleContext = LabyrinthRoleDecisionContext(
            defenseMarkStacks = policy.defenseMarkStacks,
            targetCount = strategy?.targetCount ?: combat.targetCount,
            optimizeBossVanguardSynergy = boss,
            preferSingleDamageSystem = combat.kind == LabyrinthCombatKind.EX || (boss && policy.preferPureBossDamageSystem),
            encounterStrategy = strategy,
            effectiveCharacterIds = policy.effectiveCharacterIds.toSet(),
            relaxedVanguardGate = boss && bossFallbackMultiTeamActive,
            currentArea = labyrinthBattleTeamArea(policy.activeNodeArea ?: activeArea, combat.kind, policy.route, policy.currentArea),
            frontlineSurvivalGateEnabled = policy.frontlineSurvivalGateEnabled,
        )
        val teams = if (boss && bossTeamMode == LabyrinthBossTeamMode.MULTI_TEAM) {
            val total = bossMultiTeamTargetCount ?: requestedBossTeams ?: 3
            (total - if (supplement) combat.teamIndex - 1 else 0).coerceIn(1, 3)
        } else 1
        val key = RecommendationKey(planner, roleContext, roster, failed, retryNumber, teams, supplement)
        cachedRecommendation?.let { if (it.first == key) return it.second }
        val result = if (retryNumber > 0) planner.retryRecommendation(
            roster, roleContext, failed, retryNumber, failed.lastOrNull(), teams, supplement,
        ) else planner.initialRecommendation(roster, roleContext, teams, supplement)
        cachedRecommendation = key to result
        return result
    }

    private fun evaluateTeam(frame: LabyrinthEntryFrameResult, at: Long, input: BattleFrameContext): BattleStepOutcome {
        val since = lastTeamProgressAt ?: at.also { lastTeamProgressAt = it }
        if (at - since >= config.teamStallTimeoutMillis) {
            return need("BATTLE_TEAM_STALLED", KernelErrorCategory.TIMEOUT, "队伍编组页长时间没有进展")
        }
        val observation = frame.battleTeamSelection ?: return wait("等待队伍编组识别")
        if (observation.recognitionState != LabyrinthBattleTeamRecognitionState.STABLE) return wait("等待队伍编组稳定")
        val index = observation.bossTeamIndex
        if (index != null && index !in 1..3) return wait("等待可信的Boss队伍页签")
        if (combatContext == null) {
            if (index == null) return need("BATTLE_CONTEXT_MISSING", KernelErrorCategory.STATE, "缺少本次战斗上下文")
            setCombatContext(LabyrinthCombatContext(LabyrinthCombatKind.BOSS, teamIndex = index), "BOSS_EDITOR_CONTEXT_RESTORED")
        }
        adoptBossMode(input.settings)
        val boss = combatContext?.kind == LabyrinthCombatKind.BOSS
        if (boss != (index != null)) return wait("等待队伍页签与战斗类型一致")
        if (index != null) {
            if (bossEditorPreparationStage == LabyrinthBossEditorPreparationStage.INACTIVE) {
                bossEditorPreparationStage = LabyrinthBossEditorPreparationStage.TEAM_1
                reduce("BOSS_EDITOR_PREPARATION_STARTED")
            }
            synchronizeBossEditor(index, at)
            if (bossEditorBlocked) {
                bossEditorPreparationStage = LabyrinthBossEditorPreparationStage.TEAM_1
                bossEditorBlocked = false
                pendingBossTeamAdvance = null
                preparedBossFirstTeamIds = emptyList()
                bossMultiTeamTargetCount = null
                currentTeamSignatures.clear()
                committedCharacterIds.clear()
                resetTeamExecution()
                resetEquipment()
                lastTeamProgressAt = at
                reduce("BOSS_EDITOR_RESYNCHRONIZING")
                return wait("Boss页签未按计划推进，重新清空三队并返回队伍1")
            }
            if (bossEditorPreparationStage != LabyrinthBossEditorPreparationStage.COMPLETE) {
                val preparation = labyrinthBossEditorPreparationDecision(observation, bossEditorPreparationStage)
                val step = preparation.step
                if (step != null) {
                    return offerTeamStep(frame, at, observation, step) { commitBossPreparation(preparation.stage) }
                }
                if (preparation.stage == LabyrinthBossEditorPreparationStage.COMPLETE) {
                    commitBossPreparation(preparation.stage)
                    resetTeamExecution()
                    lastTeamProgressAt = at
                    return wait("Boss三队已清空并返回队伍1")
                }
                return wait("等待Boss预处理队伍稳定")
            }
        }

        val start = anchor(frame, EntryAnchorId.BATTLE_TEAM_START_BUTTON)
        val targetCount = bossMultiTeamTargetCount
        val emptyBossStep = when {
            !boss || index == null -> null
            bossTeamMode == LabyrinthBossTeamMode.MULTI_TEAM && targetCount != null && index > targetCount ->
                labyrinthBossPartialMultiTeamExecutionStep(observation, targetCount, start)
                    ?: return wait("等待Boss空队或开战按钮")
            bossTeamMode == LabyrinthBossTeamMode.SINGLE_TEAM && preparedBossFirstTeamIds.isNotEmpty() ->
                labyrinthBossSingleTeamExecutionStep(observation, preparedBossFirstTeamIds, start)
                    ?: return wait("等待Boss单队模式的空队或开战按钮")
            else -> null
        }
        if (emptyBossStep != null) {
            equipmentGate(frame, at, input.settings, teamReady = false)?.let { return it }
            return offerTeamStep(frame, at, observation, emptyBossStep)
        }

        val excluded = committedCharacterIds + unfindableCharacterIds
        val provided = input.recommendation
        val candidate = when {
            provided is LabyrinthBattleTeamRecommendationResult.Unavailable && unfindableCharacterIds.isEmpty() -> provided
            provided is LabyrinthBattleTeamRecommendationResult.Ready &&
                canUseRecommendation(provided.recommendation, excluded) -> provided
            input.planner != null -> recommendation(input.planner, input.retry, roster(input.retry).filterNot { it in excluded },
                failedTeamSignatures.toSet(), battleRetryCount, supplement = boss && (index ?: 1) > 1)
            else -> null
        }
        val selected = when (candidate) {
            is LabyrinthBattleTeamRecommendationResult.Ready -> candidate.recommendation
            is LabyrinthBattleTeamRecommendationResult.Unavailable -> {
                if (stableFrames < config.stableFrames) return wait("等待稳定编组证据")
                val message = "无法继续自动编组：${candidate.reason}"
                val policy = input.retry
                val singleFallback = boss && bossTeamMode == LabyrinthBossTeamMode.SINGLE_TEAM &&
                    policy.singleBossFallbackToMultiAfterThreeFailures
                return if (!policy.dryRun && (policy.batchOwnedRun ||
                        (battleRetryCount > 0 && policy.rerollAfterThreeFailures && !singleFallback))) {
                    finish(message, shouldReroll = true)
                } else need("BATTLE_TEAM_UNAVAILABLE", KernelErrorCategory.POLICY, message)
            }
            null -> return wait("等待可用的队伍推荐")
        }
        if (boss && index == 1 && bossTeamMode == LabyrinthBossTeamMode.MULTI_TEAM) {
            bossMultiTeamTargetCount = selected.plannedBossTeamCount.coerceAtMost(requestedBossTeams ?: 3)
        }
        val recommendedIds = selected.members.map { canonicalLabyrinthRoleId(it.characterId) }
        if (recommendedIds != rosterSearchTarget) {
            rosterSearchTarget = recommendedIds
            exhaustedFilters.clear()
            rosterSearch.reset()
            scrollActions = 0
        }
        val planner = input.selectionPlanner ?: defaultSelectionPlanner
        val plan = planner.plan(input.sessionId, selected, observation, exhaustedFilters,
            holdCurrentTeamRequested = labyrinthRequestsCurrentNormalBattleTeam(input.settings, combatContext?.kind, battleRetryCount))
        if (!plan.isStillValid(input.sessionId, selected, observation)) return wait(plan.blockedReason ?: "等待当前编组计划稳定")
        equipmentGate(frame, at, input.settings, plan.teamReady)?.let { return it }
        val search = rosterSearch.observe(input.sessionId, observation, plan.recommendedIds)
        if (plan.scrollRequired) {
            when (search) {
                LabyrinthBattleRosterSearchDecision.WAIT_FOR_SETTLE -> return wait("等待角色列表滑动后稳定")
                LabyrinthBattleRosterSearchDecision.EXHAUSTED -> {
                    exhaustedFilters += observation.currentFilter
                    val recovery = labyrinthBattleRosterFilterRecoveryStep(plan, observation, exhaustedFilters)
                    if (recovery != null) return offerTeamStep(frame, at, observation, recovery, plan)
                    val missing = plan.notCurrentlyVisibleIds.map(::canonicalLabyrinthRoleId)
                    if (unfindableCharacterIds.addAll(missing)) {
                        resetTeamExecution()
                        lastTeamProgressAt = at
                        reduce("BATTLE_ROSTER_CHARACTERS_EXCLUDED", mapOf("characterIds" to missing.joinToString(",")))
                    }
                    return wait("已扫描可用属性页签，排除未找到的角色并重新推荐")
                }
                LabyrinthBattleRosterSearchDecision.UNAVAILABLE -> {
                    rosterSearch.reset()
                    return wait("角色列表反馈暂不可确认，等待重新建立扫描")
                }
                else -> Unit
            }
        }
        val step = labyrinthBattleTeamExecutionStep(plan, input.sessionId, selected, observation, start,
            frame.frameWidth, frame.frameHeight, if (search == LabyrinthBattleRosterSearchDecision.TO_TOP)
                LabyrinthBattleRosterScrollDirection.TO_TOP else LabyrinthBattleRosterScrollDirection.NEXT_PAGE)
            ?: return wait("等待可信的编组动作")
        return offerTeamStep(frame, at, observation, step, plan)
    }

    private fun canUseRecommendation(recommendation: LabyrinthBattleTeamRecommendation, excluded: Set<String>): Boolean {
        val ids = recommendation.members.map { canonicalLabyrinthRoleId(it.characterId) }
        return ids.none { it in excluded } && labyrinthBattleTeamSignature(ids) !in failedTeamSignatures
    }

    private fun commitBossPreparation(stage: LabyrinthBossEditorPreparationStage) {
        if (stage == bossEditorPreparationStage) return
        bossEditorPreparationStage = stage
        reduce("BOSS_EDITOR_PREPARATION", mapOf("stage" to stage.name))
    }

    private fun synchronizeBossEditor(index: Int, at: Long) {
        val previous = bossEditorTeamIndex
        if (previous == index) return
        val advance = pendingBossTeamAdvance
        if (bossEditorPreparationStage == LabyrinthBossEditorPreparationStage.COMPLETE) {
            if (advance != null && advance.first == index) {
                val disposition = labyrinthBossTeamAdvanceDisposition(bossTeamMode, advance.second)
                commitLineup(disposition.committedIds)
                if (disposition.preparedSingleTeamIds.isNotEmpty()) {
                    preparedBossFirstTeamIds = disposition.preparedSingleTeamIds
                }
            } else if (previous != null || index != 1) bossEditorBlocked = true
        }
        pendingBossTeamAdvance = null
        bossEditorTeamIndex = index
        combatContext = combatContext?.copy(teamIndex = index)
        resetTeamExecution()
        lastTeamProgressAt = at
        reduce("BOSS_EDITOR_TEAM_OBSERVED", mapOf("teamIndex" to index.toString()))
    }

    private fun commitLineup(ids: List<String>) {
        if (ids.isEmpty()) return
        committedCharacterIds += ids.map(::canonicalLabyrinthRoleId)
        currentTeamSignatures += labyrinthBattleTeamSignature(ids)
    }

    private fun offerTeamStep(
        frame: LabyrinthEntryFrameResult,
        at: Long,
        observation: LabyrinthBattleTeamObservation,
        step: LabyrinthBattleTeamExecutionStep,
        plan: LabyrinthBattleTeamSelectionPlan? = null,
        commit: () -> Unit = {},
    ): BattleStepOutcome {
        if (step.key != lastExecutionKey) {
            lastExecutionKey = step.key
            executionAttempts = 0
        }
        val toggle = step.kind == LabyrinthBattleTeamExecutionKind.SELECT_CHARACTER ||
            step.kind == LabyrinthBattleTeamExecutionKind.DESELECT_CHARACTER ||
            step.kind == LabyrinthBattleTeamExecutionKind.RESET_BOSS_DESELECT_CHARACTER
        if (toggle && executionAttempts > 0) {
            return if (intervalElapsed(at, lastTeamActionAt, config.characterFeedbackTimeoutMillis)) {
                wait("角色切换未获确认，等待新的编组证据")
            } else wait("等待角色切换反馈")
        }
        if (step.kind == LabyrinthBattleTeamExecutionKind.SCROLL_CHARACTERS && scrollActions >= config.maxScrollActions) {
            return wait("达到编组滚动上限，等待新的角色视口")
        }
        if (!toggle && step.kind != LabyrinthBattleTeamExecutionKind.SCROLL_CHARACTERS &&
            executionAttempts >= config.maxTeamStepAttempts) return wait("等待新的编组动作证据")
        if (!intervalElapsed(at, lastSessionActionAt, config.actionIntervalMillis)) return wait("等待会话动作间隔")

        val sourceIndex = observation.bossTeamIndex
        val tabSwitch = labyrinthIsBossTabSwitch(step.kind)
        val targetIndex = labyrinthBossTabTarget(step.kind, step.key, sourceIndex)
        if (tabSwitch && (sourceIndex == null || targetIndex == null || sourceIndex == targetIndex)) {
            return wait("等待可信的Boss切队目标")
        }
        val role = when (step.kind) {
            LabyrinthBattleTeamExecutionKind.SELECT_CHARACTER -> plan?.visibleSelectTargets?.firstOrNull()
            LabyrinthBattleTeamExecutionKind.DESELECT_CHARACTER -> plan?.visibleDeselectTargets?.firstOrNull()
            else -> null
        }
        val filter = if (step.kind == LabyrinthBattleTeamExecutionKind.SELECT_FILTER) {
            observation.filters.firstOrNull { it.filter.name == step.key.substringAfter(':').substringBefore(':') }
        } else null
        val rect = when (step.kind) {
            LabyrinthBattleTeamExecutionKind.SELECT_CHARACTER, LabyrinthBattleTeamExecutionKind.DESELECT_CHARACTER -> role?.screenRect
            LabyrinthBattleTeamExecutionKind.RESET_BOSS_DESELECT_CHARACTER -> observation.selectedCharacters.firstOrNull()?.screenRect
            LabyrinthBattleTeamExecutionKind.SELECT_FILTER -> filter?.screenRect
            LabyrinthBattleTeamExecutionKind.START_BATTLE -> anchor(frame, EntryAnchorId.BATTLE_TEAM_START_BUTTON)
            LabyrinthBattleTeamExecutionKind.NEXT_BOSS_TEAM, LabyrinthBattleTeamExecutionKind.RESET_BOSS_SWITCH_TEAM ->
                targetIndex?.let { observation.bossTeamTabs.getOrNull(it - 1) }
            LabyrinthBattleTeamExecutionKind.SCROLL_CHARACTERS -> null
        }
        val key = BattleActionKey(step.kind.name, role?.characterId ?: filter?.filter?.name ?: targetIndex?.toString(),
            sourceIndex, observation.viewportRevision, step.key, rect)
        return offer(frame, at, key, step.label, step.action) { executedAt ->
            executionAttempts++
            lastTeamActionAt = executedAt
            lastTeamProgressAt = executedAt
            commit()
            if (step.kind == LabyrinthBattleTeamExecutionKind.SCROLL_CHARACTERS) {
                scrollActions++
                val direction = step.scrollDirection
                val position = step.scrollOriginPosition
                if (direction != null && position != null) rosterSearch.recordExecutedScroll(direction, position)
            }
            if (tabSwitch && sourceIndex != null && targetIndex != null) {
                val receipt = LabyrinthBossTabReceipt(sourceIndex, targetIndex, executedAt, config.actionAckTimeoutMillis)
                receipt.inputFinished(executedAt)
                pendingEditorAction = PendingEditorAction(step.kind, editorSignature(frame), executedAt, cause, tab = receipt)
                bossTabDiagnostic = receipt.snapshot()
                if (step.kind == LabyrinthBattleTeamExecutionKind.NEXT_BOSS_TEAM) {
                    pendingBossTeamAdvance = targetIndex to step.confirmedTeamIds.toList()
                }
            } else if (step.kind == LabyrinthBattleTeamExecutionKind.START_BATTLE) {
                commitStart(frame, step.confirmedTeamIds, executedAt)
            }
        }
    }

    private fun commitStart(frame: LabyrinthEntryFrameResult, ids: List<String>, at: Long) {
        val addedIds = ids.map(::canonicalLabyrinthRoleId).toSet() - committedCharacterIds
        val signature = ids.takeIf { it.isNotEmpty() }?.let(::labyrinthBattleTeamSignature)
        pendingEditorAction = PendingEditorAction(
            LabyrinthBattleTeamExecutionKind.START_BATTLE, editorSignature(frame), at, cause,
            addedIds = addedIds, addedSignature = signature?.takeUnless { it in currentTeamSignatures },
            previousNormalSignature = lastNormalBattleTeamSignature,
            previousAwaitingRetry = awaitingRetryBattleAfterFailure,
        )
        commitLineup(ids)
        if (combatContext?.kind == LabyrinthCombatKind.NORMAL && signature != null) lastNormalBattleTeamSignature = signature
        awaitingRetryBattleAfterFailure = false
        battleWait.onStartExecuted(at, plannedTeams())
        reduce("BATTLE_START_EXECUTED", mapOf("plannedTeams" to plannedTeams().toString()))
    }

    private fun editorSignature(frame: LabyrinthEntryFrameResult): String =
        "${frame.observation.state.name}:${frame.battleTeamSelection?.bossTeamIndex ?: -1}"

    private fun acknowledgeEditorAction() {
        val pending = pendingEditorAction ?: return
        pendingEditorAction = null
        unconfirmedTeamActions = 0
        emit(K.ACTION_RECEIPT_OBSERVED, ActionReceiptPayload(pending.kind.name, "ACKNOWLEDGED"),
            "BATTLE_TEAM_ACTION_ACKNOWLEDGED")
    }

    private fun handleUnconfirmedEditorAction(frame: LabyrinthEntryFrameResult, at: Long): BattleStepOutcome? {
        teamActionStopReason?.let { return need("BATTLE_TEAM_ACTION_UNCONFIRMED", KernelErrorCategory.EXECUTION, it) }
        val pending = pendingEditorAction ?: return null
        val tab = pending.tab
        val editor = frame.battleTeamSelection
        val ack = if (tab != null) tab.observe(frame.observation.state,
            editor?.recognitionState == LabyrinthBattleTeamRecognitionState.STABLE, editor?.bossTeamIndex, at)
        else when {
            editorSignature(frame) != pending.signature -> LabyrinthBossTabAck.ACKNOWLEDGED
            intervalElapsed(at, pending.at, config.actionAckTimeoutMillis) -> LabyrinthBossTabAck.TIMED_OUT
            else -> LabyrinthBossTabAck.WAIT
        }
        if (tab != null) bossTabDiagnostic = tab.snapshot(active = ack == LabyrinthBossTabAck.WAIT)
        when (ack) {
            LabyrinthBossTabAck.WAIT -> return wait(if (tab != null) "等待Boss队伍${tab.expectedIndex}稳定回显" else "等待离开编组页")
            LabyrinthBossTabAck.ACKNOWLEDGED -> {
                acknowledgeEditorAction()
                if (tab != null) synchronizeBossEditor(tab.expectedIndex, at)
                return null
            }
            LabyrinthBossTabAck.TIMED_OUT -> {
                pendingEditorAction = null
                unconfirmedTeamActions++
                emit(K.ACTION_RECEIPT_OBSERVED, ActionReceiptPayload(pending.kind.name, "PAGE_UNCHANGED"),
                    "BATTLE_TEAM_ACTION_UNCONFIRMED")
                if (pending.kind == LabyrinthBattleTeamExecutionKind.START_BATTLE) {
                    committedCharacterIds.removeAll(pending.addedIds)
                    pending.addedSignature?.let { currentTeamSignatures.remove(it) }
                    lastNormalBattleTeamSignature = pending.previousNormalSignature
                    awaitingRetryBattleAfterFailure = pending.previousAwaitingRetry
                }
                pendingBossTeamAdvance = null
                battleWait.reset()
                resetTeamExecution()
                lastTeamProgressAt = at
                reduce("BATTLE_TEAM_ACTION_ROLLED_BACK", mapOf(
                    "kind" to pending.kind.name, "attempts" to unconfirmedTeamActions.toString(),
                    "dispatchCause" to (pending.cause?.eventSeq?.toString() ?: ""),
                ))
                if (unconfirmedTeamActions > config.maxUnconfirmedActions) {
                    val reason = "编组动作连续${unconfirmedTeamActions}次未收到画面确认"
                    teamActionStopReason = reason
                    return need("BATTLE_TEAM_ACTION_UNCONFIRMED", KernelErrorCategory.EXECUTION, reason)
                }
                return wait("编组动作未收到画面确认，已撤回本次提交并重新规划")
            }
        }
    }

    private fun equipmentGate(
        frame: LabyrinthEntryFrameResult,
        at: Long,
        settings: LabyrinthStrategySettings,
        teamReady: Boolean,
    ): BattleStepOutcome? {
        handleEquipment(frame, at, settings, teamReady)?.let { return it }
        if (!specialEquipmentModalCloseSettlePending) return null
        specialEquipmentModalCloseSettlePending = false
        return wait("特别装备弹窗已关闭，等待一帧")
    }

    private fun handleEquipment(
        frame: LabyrinthEntryFrameResult,
        at: Long,
        settings: LabyrinthStrategySettings,
        teamReady: Boolean,
    ): BattleStepOutcome? {
        val page = frame.observation.state
        val observation = frame.specialEquipment
        val dialog = observation?.stage
        val onEditor = page == BATTLE_TEAM_SELECTION
        if (!onEditor && dialog == null) {
            equipmentAwayFrames = if (page == UNKNOWN) 0 else equipmentAwayFrames + 1
            if (equipmentAwayFrames >= config.specialEquipmentLeaveFrames &&
                equipmentStage != LabyrinthSpecialEquipmentFlowStage.INACTIVE) resetEquipment()
            return null
        }
        equipmentAwayFrames = 0
        val kind = combatContext?.kind
        if (kind == null || !settings.autoEquipsSpecialEquipment(kind)) {
            if (equipmentStage != LabyrinthSpecialEquipmentFlowStage.INACTIVE) resetEquipment()
            return if (dialog != null) wait("特别装备弹窗由人工控制") else null
        }
        val team = frame.battleTeamSelection
        val multiTeamIndex = team?.bossTeamIndex?.takeIf {
            kind == LabyrinthCombatKind.BOSS && bossTeamMode == LabyrinthBossTeamMode.MULTI_TEAM &&
                team.recognitionState == LabyrinthBattleTeamRecognitionState.STABLE
        }
        if (equipmentStage == LabyrinthSpecialEquipmentFlowStage.DONE && dialog == null &&
            multiTeamIndex != null && equipmentTeamIndex != null && multiTeamIndex != equipmentTeamIndex) resetEquipment()

        if (equipmentStage == LabyrinthSpecialEquipmentFlowStage.INACTIVE) {
            if (dialog != null) return wait("特别装备弹窗未经本轮编组打开")
            if (!onEditor || !teamReady || team?.recognitionState != LabyrinthBattleTeamRecognitionState.STABLE) return null
            val ids = team.selectedCharacters.map { it.characterId }
            if (labyrinthSpecialEquipmentTeamIsEmpty(ids)) return null
            if (kind == LabyrinthCombatKind.NORMAL && labyrinthSpecialEquipmentTeamIsUnchanged(ids, lastNormalBattleTeamSignature)) {
                return null
            }
            equipmentTeamIndex = multiTeamIndex
            advanceEquipment(LabyrinthSpecialEquipmentFlowStage.AWAIT_ENTRY, at)
        }
        if (dialog != null) equipmentDialogObserved = true
        val elapsed = at - (equipmentStartedAt ?: at)
        val sinceDispatch = equipmentLastDispatchedAt?.let { (at - it).coerceAtLeast(0L) } ?: Long.MAX_VALUE
        if (equipmentStage == LabyrinthSpecialEquipmentFlowStage.AWAIT_SECOND_MAIN_DIALOG) {
            equipmentEvidence = labyrinthSpecialEquipmentEquipEvidenceStreak(equipmentEvidence, observation, elapsed,
                config.specialEquipmentEvidenceFrames, config.specialEquipmentEvidenceMillis)
        }
        val decision = labyrinthSpecialEquipmentDecision(equipmentStage, observation, equipmentAttempts, elapsed,
            equipmentEvidence.confirmedEnabled, sinceDispatch, config.specialEquipmentMaxStepAttempts,
            config.specialEquipmentStepTimeoutMillis)
        if (decision.abandoned && equipmentAbandonedReason == null) equipmentAbandonedReason = decision.message
        if (decision.finished) return finishEquipmentStep(frame, at, observation, decision)

        val button = decision.tap
        if (button == null) {
            advanceEquipment(decision.stage, at)
            return wait(decision.message)
        }
        val rect = observation?.let { labyrinthSpecialEquipmentTapRect(button, it) } ?: return wait(decision.message)
        return offerEquipmentTap(frame, at, dialog, button, rect, decision.message) { executedAt ->
            advanceEquipment(decision.stage, executedAt)
            equipmentAttempts++
            equipmentLastDispatchedAt = executedAt
        }
    }

    private fun finishEquipmentStep(
        frame: LabyrinthEntryFrameResult,
        at: Long,
        observation: LabyrinthSpecialEquipmentObservation?,
        decision: LabyrinthSpecialEquipmentDecision,
    ): BattleStepOutcome? {
        val diagnostic = equipmentAbandonedReason ?: decision.message
        val layer = observation?.stage
        if (observation == null || layer == null) {
            advanceEquipment(decision.stage, at)
            equipmentPendingEscape = null
            equipmentEscapeAckDeadline = null
            if (equipmentDialogObserved) {
                equipmentDialogObserved = false
                specialEquipmentModalCloseSettlePending = true
            }
            return null
        }

        val oldEscape = equipmentPendingEscape
        if (oldEscape != null && (oldEscape.first != layer || at >= oldEscape.second)) {
            if (at >= oldEscape.second) equipmentEscapeSpent += oldEscape.first
            equipmentPendingEscape = null
        }
        val rect = labyrinthSpecialEquipmentTapRect(LabyrinthSpecialEquipmentTap.CANCEL, observation)
        if (equipmentAbandonedReason != null && rect != null && layer !in equipmentEscapeSpent) {
            if (equipmentPendingEscape == null) equipmentPendingEscape = layer to (at + config.specialEquipmentEscapeTimeoutMillis)
            return offerEquipmentTap(frame, at, layer, LabyrinthSpecialEquipmentTap.CANCEL, rect, diagnostic) { executedAt ->
                advanceEquipment(decision.stage, executedAt)
                equipmentEscapeSpent += layer
                equipmentPendingEscape = null
                equipmentEscapeAckDeadline = executedAt + config.specialEquipmentEscapeTimeoutMillis
                reduce("SPECIAL_EQUIPMENT_ESCAPE_EXECUTED", mapOf("layer" to layer.name))
            }
        }

        advanceEquipment(decision.stage, at)
        val deadline = equipmentEscapeAckDeadline
        if (layer in equipmentEscapeSpent && deadline != null && at < deadline) return wait(diagnostic)
        return need("SPECIAL_EQUIPMENT_DIALOG_BLOCKED", KernelErrorCategory.EVIDENCE,
            "特别装备弹窗仍未关闭，当前层无可用的逃生点击。${diagnostic}")
    }

    private fun offerEquipmentTap(
        frame: LabyrinthEntryFrameResult,
        at: Long,
        stage: LabyrinthSpecialEquipmentStage?,
        button: LabyrinthSpecialEquipmentTap,
        rect: EntryPixelRect,
        message: String,
        onExecuted: (Long) -> Unit,
    ): BattleStepOutcome {
        if (!intervalElapsed(at, lastSessionActionAt, config.actionIntervalMillis)) return wait("特别装备等待会话动作间隔")
        val team = frame.battleTeamSelection
        val key = BattleActionKey("SPECIAL_EQUIPMENT_${button.name}", teamIndex = team?.bossTeamIndex,
            viewportRevision = team?.viewportRevision ?: 0L, signature = stage?.name, rect = rect)
        return offer(frame, at, key, message, tap(rect)) {
            lastTeamProgressAt = it
            onExecuted(it)
        }
    }

    private fun advanceEquipment(stage: LabyrinthSpecialEquipmentFlowStage, at: Long) {
        if (equipmentStage == stage) return
        equipmentStage = stage
        equipmentStartedAt = at
        equipmentAttempts = 0
        equipmentLastDispatchedAt = null
        equipmentEvidence = LabyrinthSpecialEquipmentEquipEvidenceStreak()
        lastTeamProgressAt = at
        reduce("SPECIAL_EQUIPMENT_STAGE_CHANGED", mapOf("stage" to stage.name))
    }

    private fun resetEquipment() {
        equipmentStage = LabyrinthSpecialEquipmentFlowStage.INACTIVE
        equipmentStartedAt = null
        equipmentLastDispatchedAt = null
        equipmentAttempts = 0
        equipmentEvidence = LabyrinthSpecialEquipmentEquipEvidenceStreak()
        equipmentAwayFrames = 0
        equipmentTeamIndex = null
        equipmentDialogObserved = false
        specialEquipmentModalCloseSettlePending = false
        equipmentAbandonedReason = null
        equipmentEscapeSpent.clear()
        equipmentPendingEscape = null
        equipmentEscapeAckDeadline = null
    }

    private fun clearBattle() {
        invalidateProposal("battle_reset")
        dispatch = null
        battleWait.reset()
        combatContext = null
        activeArea = null
        battleRetryCount = 0
        awaitingRetryBattleAfterFailure = false
        currentTeamSignatures.clear()
        failedTeamSignatures.clear()
        committedCharacterIds.clear()
        bossTeamMode = LabyrinthBossTeamMode.MULTI_TEAM
        bossModePinned = false
        requestedBossTeams = null
        bossFallbackMultiTeamActive = false
        resetBossEditor()
        pendingEditorAction = null
        bossTabDiagnostic = null
        unconfirmedTeamActions = 0
        teamActionStopReason = null
        lastTeamProgressAt = null
        unfindableCharacterIds.clear()
        resetTeamExecution()
        resetEx()
        resetEquipment()
        lastPage = null
        stableFrames = 0
    }

    private fun resetEx() {
        currentExEncounterStrategy = null
        exEncounterFallbackApproved = false
        exSlot3ProbePending = false
        exSlot3ProbeAttempts = 0
        exEncounterProbeStartedAt = null
        exDetailOpenedAt = null
        exLastKnownPage = null
        exIdentityProbeDescription = "EX识别目标"
    }

    private fun resetBossEditor() {
        bossEditorPreparationStage = LabyrinthBossEditorPreparationStage.INACTIVE
        bossEditorTeamIndex = null
        bossEditorBlocked = false
        pendingBossTeamAdvance = null
        preparedBossFirstTeamIds = emptyList()
        bossMultiTeamTargetCount = null
    }

    private fun resetTeamExecution() {
        lastExecutionKey = null
        executionAttempts = 0
        lastTeamActionAt = null
        scrollActions = 0
        rosterSearch.reset()
        exhaustedFilters.clear()
        rosterSearchTarget = null
        cachedRecommendation = null
    }

    private fun emit(kind: String, payload: KernelEventPayload, reason: String, summary: String = reason,
                     parent: KernelEvent? = cause): KernelEvent =
        runtime.emit(domain, kind, payload, parent, reason, summary).also { cause = it }

    private fun reduce(reason: String, detail: Map<String, String> = emptyMap()): KernelEvent =
        emit(K.STATE_REDUCED, StateReducedPayload(lastPage?.name.orEmpty(), nested?.id, nested?.parentId,
            encounterStack?.depth(), detail), reason)
}
