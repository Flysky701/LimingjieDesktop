package com.landosol.toolbox.labyrinth.kernel.reward

import com.landosol.toolbox.labyrinth.LabyrinthEventFreeRoleBlockedAction
import com.landosol.toolbox.labyrinth.LabyrinthFallbackTap
import com.landosol.toolbox.labyrinth.LabyrinthInviteButtonState
import com.landosol.toolbox.labyrinth.LabyrinthObservedRelic
import com.landosol.toolbox.labyrinth.LabyrinthPostEntryActionKind
import com.landosol.toolbox.labyrinth.LabyrinthRelicChoicePhase
import com.landosol.toolbox.labyrinth.LabyrinthRelicChoicePolicy
import com.landosol.toolbox.labyrinth.LabyrinthRelicIdentityGate
import com.landosol.toolbox.labyrinth.LabyrinthRelicMark
import com.landosol.toolbox.labyrinth.LabyrinthRelicStackLedger
import com.landosol.toolbox.labyrinth.LabyrinthRelicStackSource
import com.landosol.toolbox.labyrinth.LabyrinthRoleDecisionContext
import com.landosol.toolbox.labyrinth.LabyrinthRoleRewardChoiceDecision
import com.landosol.toolbox.labyrinth.LabyrinthRoleRewardChoicePlanner
import com.landosol.toolbox.labyrinth.LabyrinthRoleRewardRound
import com.landosol.toolbox.labyrinth.canonicalLabyrinthRoleId
import com.landosol.toolbox.labyrinth.labyrinthEventFreeRoleBlockedAction
import com.landosol.toolbox.labyrinth.labyrinthEventFreeRoleNeedsAnotherPick
import com.landosol.toolbox.labyrinth.labyrinthEventFreeRoleReconcileSelection
import com.landosol.toolbox.labyrinth.labyrinthEventFreeRoleSelectionOwnsFrame
import com.landosol.toolbox.labyrinth.labyrinthInviteButtonState
import com.landosol.toolbox.labyrinth.labyrinthKeepsRoleRewardBatchOnPage
import com.landosol.toolbox.labyrinth.labyrinthRoleDecisionCanPublish
import com.landosol.toolbox.labyrinth.labyrinthRoleRewardIdentityIsActionSafe
import com.landosol.toolbox.labyrinth.labyrinthSelectionCommitExpired
import com.landosol.toolbox.labyrinth.shouldArmCharacterAcquisitionFallback
import com.landosol.toolbox.labyrinth.kernel.ActionDispatchedPayload
import com.landosol.toolbox.labyrinth.kernel.ActionReceiptPayload
import com.landosol.toolbox.labyrinth.kernel.DecisionProposedPayload
import com.landosol.toolbox.labyrinth.kernel.FrameObservedPayload
import com.landosol.toolbox.labyrinth.kernel.KernelActionPermit
import com.landosol.toolbox.labyrinth.kernel.KernelDecision
import com.landosol.toolbox.labyrinth.kernel.KernelDomain
import com.landosol.toolbox.labyrinth.kernel.KernelDomainModule
import com.landosol.toolbox.labyrinth.kernel.KernelError
import com.landosol.toolbox.labyrinth.kernel.KernelErrorCategory
import com.landosol.toolbox.labyrinth.kernel.KernelEvent
import com.landosol.toolbox.labyrinth.kernel.KernelEventPayload
import com.landosol.toolbox.labyrinth.kernel.KernelEventKinds as K
import com.landosol.toolbox.labyrinth.kernel.KernelHookVerdict
import com.landosol.toolbox.labyrinth.kernel.KernelRuntime
import com.landosol.toolbox.labyrinth.kernel.PermitTransitionPayload
import com.landosol.toolbox.labyrinth.kernel.PolicyRuleEvaluatedPayload
import com.landosol.toolbox.labyrinth.kernel.SessionLifecyclePayload
import com.landosol.toolbox.labyrinth.kernel.StateReducedPayload
import com.landosol.toolbox.labyrinth.vision.EntryAnchorId
import com.landosol.toolbox.labyrinth.vision.EntryPixelRect
import com.landosol.toolbox.labyrinth.vision.LabyrinthBattleTeamRecognitionState
import com.landosol.toolbox.labyrinth.vision.LabyrinthCharacterMatch
import com.landosol.toolbox.labyrinth.vision.LabyrinthEntryFrameResult
import com.landosol.toolbox.labyrinth.vision.LabyrinthEntryPageState
import com.landosol.toolbox.labyrinth.vision.LabyrinthEntryPageState as Page
import com.landosol.toolbox.labyrinth.vision.LabyrinthLinkChoiceObservation
import com.landosol.toolbox.labyrinth.vision.LabyrinthNodeRelicStackObservation
import com.landosol.toolbox.labyrinth.vision.LabyrinthRelicChoiceObservation
import com.landosol.toolbox.labyrinth.vision.LabyrinthRelicMatch
import com.landosol.toolbox.labyrinth.vision.LabyrinthRelicRewardKind
import com.landosol.toolbox.labyrinth.vision.LabyrinthRelicRewardObservation

enum class RewardActionKind {
    SELECT_RELIC, SELECT_LINK_IMPRINT, SELECT_ROLE_REWARD, CLOSE_CHARACTER_JOINED, CLOSE_ITEM_REWARD,
    ADVANCE_CHARACTER_ACQUISITION, EVENT_FREE_ROLE_SELECT, EVENT_FREE_ROLE_CONFIRM;

    internal val actionKind: String get() = LabyrinthPostEntryActionKind.valueOf(name).name
}

data class RewardActionKey(
    val kind: RewardActionKind,
    val pageState: LabyrinthEntryPageState,
    val sceneEpoch: Long,
    val targetId: String,
    val slotId: String?,
    val rect: EntryPixelRect,
    val roundEpoch: Long? = null,
    val frameSeq: Long? = null,
)

data class RewardStep(val decision: KernelDecision, val key: RewardActionKey? = null)

data class RelicRewardHoldState(
    val active: Boolean = false,
    val holdSinceMillis: Long = Long.MIN_VALUE,
    val frameAtMillis: Long = Long.MIN_VALUE,
    val rowSlots: Set<String> = emptySet(),
    val layoutSignature: String? = null,
    val layoutConfirmations: Int = 0,
    val elapsedMillis: Long = 0,
    val timedOut: Boolean = false,
    val ready: Boolean = false,
)

data class RelicRewardHoldInput(
    val reward: LabyrinthRelicRewardObservation?,
    val recordedRelicIds: Set<String>,
    val relicEvidenceVersion: Long,
    val frameEvidenceVersion: Long,
    val evidenceAfterMillis: Long,
    val atMillis: Long,
    val dryRun: Boolean = false,
)

data class RelicRewardHoldOutcome(val holding: Boolean, val decision: KernelDecision, val state: RelicRewardHoldState)

data class RelicChoiceInput(
    val observation: LabyrinthRelicChoiceObservation?,
    val observedRelics: List<LabyrinthObservedRelic>,
    val openingRelicBaselineKnown: Boolean,
    val calibratedStacks: Map<LabyrinthRelicMark, Int>,
    val currentArea: Int?,
    val evidenceAfterMillis: Long,
    val atMillis: Long,
    val frameWidth: Int,
    val frameHeight: Int,
    val relicEvidenceVersion: Long = 0,
    val frameEvidenceVersion: Long = relicEvidenceVersion,
)

data class RoleRewardFrameInput(
    val isRoleRewardPage: Boolean,
    val characterMatches: List<LabyrinthCharacterMatch>,
    val frameWidth: Int,
    val frameHeight: Int,
    val defenseStacks: Int,
    val acquiredIds: Set<String>,
    val atMillis: Long,
)

data class EventFreeRoleFrameInput(
    val result: LabyrinthEntryFrameResult,
    val activeNodeType: Int?,
    val isRoleRewardPage: Boolean,
    val frameWidth: Int,
    val frameHeight: Int,
    val defenseStacks: Int,
    val acquiredIds: Set<String>,
    val atMillis: Long,
    val shopChoiceImprintPending: Boolean = false,
    val routeActive: Boolean = false,
)

data class CharacterAcquisitionState(
    val active: Boolean = false,
    val startedAtMillis: Long = Long.MIN_VALUE,
    val clicks: Int = 0,
    val lastClickAtMillis: Long = Long.MIN_VALUE,
)

class RewardDispatch internal constructor(
    val key: RewardActionKey,
    val event: KernelEvent,
    internal val roleToken: Long?,
    internal val addedPendingRole: Boolean,
    internal val acquisitionAtDispatch: CharacterAcquisitionState?,
    internal val confirmedFreeRoleIds: Set<String>,
)

/** The runtime monitor serializes frame reduction, permit consumption, and receipt application. */
class RewardTailModule(
    private val runtime: KernelRuntime,
    private val rolePlanner: LabyrinthRoleRewardChoicePlanner? = null,
    private val relicChoicePolicy: LabyrinthRelicChoicePolicy = LabyrinthRelicChoicePolicy(),
) : KernelDomainModule {
    override val id = "reward-tail"
    override val domain = KernelDomain.REWARD

    private val ledger = LabyrinthRelicStackLedger()
    private val relicIdentityGate = LabyrinthRelicIdentityGate(allowSpecial = false)
    private val relicRewardIdentityGate = LabyrinthRelicIdentityGate(allowSpecial = true)
    private val roleRewardRound = LabyrinthRoleRewardRound()

    private var frame: KernelEvent? = null
    private var parent: KernelEvent? = null
    private var page: Page? = null
    private var capturedAt = Long.MIN_VALUE
    private var sceneEpoch = 0L
    private var running = true
    private var paused = false
    private var generation = 0L
    private var pendingDispatch: RewardDispatch? = null
    private var dispatchedFrame: KernelEvent? = null
    private var issuedKey: RewardActionKey? = null
    private val permit = KernelActionPermit<RewardActionKey> { issued, key, revision, reason ->
        issuedKey = key.takeIf { issued }
        emit(
            if (issued) K.PERMIT_ISSUED else K.PERMIT_REVOKED,
            PermitTransitionPayload(key?.targetId, revision, reason),
            reason,
        )
    }

    private var hold = RelicRewardHoldState()
    private var holdEvidenceVersion: Long? = null
    private var choiceEvidenceVersion: Long? = null
    private var pendingRelicSelection: LabyrinthRelicMatch? = null
    private var relicChoiceCommittedAt = Long.MIN_VALUE
    private var relicChoiceCommitAttempts = 0
    private var relicFocusMark: LabyrinthRelicMark? = null

    private data class RoleCandidate(
        val slotId: String, val characterId: String?, val displayName: String?, val rect: EntryPixelRect,
        val actionSafe: Boolean,
    )
    private data class RoleCacheKey(
        val width: Int, val height: Int, val defenseStacks: Int, val acquired: Set<String>,
        val candidates: List<RoleCandidate>,
    )
    private var roleRewardDecisionCacheKey: RoleCacheKey? = null
    private var cachedRoleDecision: LabyrinthRoleRewardChoiceDecision.Select? = null
    private var plannedRoleDecision: LabyrinthRoleRewardChoiceDecision.Select? = null
    private var roleSlots: Map<String, String> = emptyMap()
    var committedRoleRewardSelectionSignature: Map<String, String>? = null
        private set
    var lockedRoleRewardDecision: LabyrinthRoleRewardChoiceDecision.Select? = null
        private set
    var roleRewardChoiceCommitted = false
        private set
    var roleRewardBatchActive = false
        private set
    var roleRewardJoinedSequenceStarted = false
        private set
    var waitingForManualRoleSelection = false
        private set

    private var acquisition = CharacterAcquisitionState()
    private val pendingAcquiredIds = linkedSetOf<String>()
    private var freeRoleSelectedIds: List<String> = emptyList()
    private var freeRoleObservedSelectedCount = 0
    private var freeRoleBlockedSince = Long.MIN_VALUE
    private var freeRoleLastSelectAt = Long.MIN_VALUE

    val relicRewardHoldState: RelicRewardHoldState get() = synchronized(runtime) { hold }
    val characterAcquisitionState: CharacterAcquisitionState get() = synchronized(runtime) { acquisition }
    val pendingAcquiredCharacterIds: Set<String> get() = synchronized(runtime) { pendingAcquiredIds.toSet() }
    val eventFreeRoleSelectedCharacterIds: List<String> get() = synchronized(runtime) { freeRoleSelectedIds.toList() }
    fun roleRoundSnapshot() = synchronized(runtime) { roleRewardRound.snapshot() }

    override fun onKernelEvent(event: KernelEvent, ctx: KernelRuntime) {
        if (event.kind != K.SESSION_LIFECYCLE) return
        val lifecycle = event.payload as? SessionLifecyclePayload ?: return
        when (lifecycle.phase) {
            "start" -> { running = true; paused = false }
            "pause" -> paused = true
            "resume" -> paused = false
            "stop" -> running = false
        }
        generation++
    }

    fun beginFrame(
        frameSeq: Long,
        capturedAtMillis: Long,
        sceneEpoch: Long,
        pageState: LabyrinthEntryPageState,
        cause: KernelEvent? = null,
    ): KernelEvent = synchronized(runtime) {
        val previousPage = page
        val sceneChanged = this.sceneEpoch != sceneEpoch
        val pageChanged = previousPage != pageState
        val observed = runtime.emit(
            domain, K.FRAME_OBSERVED, FrameObservedPayload(pageState.name, capturedAtMillis),
            cause?.takeIf { it.traceId == runtime.traceId }, frameSeq = frameSeq, sceneEpoch = sceneEpoch,
        )
        frame = observed
        parent = observed
        page = pageState
        capturedAt = capturedAtMillis
        this.sceneEpoch = sceneEpoch
        generation++
        if (pageChanged || sceneChanged) reduce("reward_page_changed")
        revokePermit(if (pageChanged || sceneChanged) "reward_page_changed" else "reward_frame_changed")
        if (pageChanged || sceneChanged) {
            relicIdentityGate.clear()
            choiceEvidenceVersion = null
            invalidateRelicHold("reward_page_changed")
            if (previousPage == Page.INITIAL_CHARACTER_SELECTION) clearEventFreeRole("reward_page_changed")
            if (pendingRelicSelection != null && relicChoiceCommittedAt == Long.MIN_VALUE) {
                cancelRelicAcquisition(pendingRelicSelection?.relicId)
            }
            if (pageState == Page.RELIC_CHOICE) {
                cancelRelicAcquisition(pendingRelicSelection?.relicId)
                relicChoiceCommittedAt = Long.MIN_VALUE
                relicChoiceCommitAttempts = 0
            }
        }
        observed
    }

    /** Use the runtime session reset to change the shared trace ID and reset registered domains. */
    override fun reset(traceId: String) = synchronized(runtime) {
        revokePermit("session_reset")
        frame = null
        parent = null
        page = null
        capturedAt = Long.MIN_VALUE
        sceneEpoch = 0
        running = true
        paused = false
        generation++
        pendingDispatch = null
        dispatchedFrame = null
        invalidateRelicHold()
        relicIdentityGate.clear()
        choiceEvidenceVersion = null
        ledger.clear()
        relicFocusMark = null
        pendingRelicSelection = null
        relicChoiceCommittedAt = Long.MIN_VALUE
        relicChoiceCommitAttempts = 0
        clearRoleRewardBatch()
        clearCharacterAcquisition()
        clearEventFreeRole()
        pendingAcquiredIds.clear()
    }

    fun evaluateRelicRewardHold(input: RelicRewardHoldInput): RelicRewardHoldOutcome = synchronized(runtime) {
        val reward = input.reward
        if (reward == null) {
            invalidateRelicHold()
            return RelicRewardHoldOutcome(false, KernelDecision.Complete(null, "遗物奖励弹窗已离开"), hold)
        }
        val currentPage = page
        if (currentPage == null || input.atMillis != capturedAt) {
            val decision = KernelDecision.Wait("等待当前帧的遗物奖励证据")
            return RelicRewardHoldOutcome(true, decision, hold)
        }
        if (holdEvidenceVersion != input.relicEvidenceVersion) {
            relicRewardIdentityGate.clear()
            hold = hold.copy(layoutSignature = null, layoutConfirmations = 0, ready = false)
            holdEvidenceVersion = input.relicEvidenceVersion
        }
        val validReward = reward.takeIf {
            it.kind == LabyrinthRelicRewardKind.RELIC_EFFECT && it.titleConfirmed &&
                input.frameEvidenceVersion == input.relicEvidenceVersion && it.capturedAtMillis > input.evidenceAfterMillis
        }
        val at = input.atMillis
        relicRewardIdentityGate.observe(currentPage, at, validReward?.relics.orEmpty())
        val before = hold
        if (at > before.frameAtMillis) {
            val rows = before.rowSlots + validReward?.rowSlots.orEmpty()
            val signature = validReward?.takeIf { it.layoutComplete && rows.isNotEmpty() && rows == it.rowSlots }
                ?.let(::relicRewardSignature)
            hold = hold.copy(
                holdSinceMillis = before.holdSinceMillis.takeUnless { it == Long.MIN_VALUE } ?: at,
                frameAtMillis = at,
                rowSlots = rows,
                layoutSignature = signature,
                layoutConfirmations = when (signature) {
                    null -> 0
                    before.layoutSignature -> before.layoutConfirmations + 1
                    else -> 1
                },
            )
        }
        val ready = validReward != null && validReward.layoutComplete && hold.rowSlots.isNotEmpty() &&
            validReward.rowSlots == hold.rowSlots && hold.layoutConfirmations >= 2 &&
            relicRewardSignature(validReward) == hold.layoutSignature &&
            validReward.relics.all(relicRewardIdentityGate::confirmed) &&
            validReward.relics.all { it.relicId in input.recordedRelicIds }
        val elapsed = (at - hold.holdSinceMillis).coerceAtLeast(0)
        val timedOut = !ready && elapsed >= RELIC_REWARD_HOLD_TIMEOUT_MILLIS
        hold = hold.copy(active = !ready, elapsedMillis = elapsed, timedOut = timedOut, ready = ready)
        if (before.holdSinceMillis == Long.MIN_VALUE || ready != before.ready) reduce("relic_reward_hold_changed")
        val decision = when {
            ready -> publish("relic_reward_hold", KernelDecision.Complete(null, "奖励内容已完整确认")).decision
            timedOut && !input.dryRun -> needEvidence("relic_reward_hold", "RELIC_REWARD_HOLD_TIMEOUT",
                KernelErrorCategory.TIMEOUT, "奖励内容未能完整确认，保留弹窗供核对").decision
            else -> waitFor("relic_reward_hold", "正在确认奖励内容，暂不关闭弹窗").decision
        }
        RelicRewardHoldOutcome(!ready, decision, hold)
    }

    fun invalidateRelicHold(reason: String = "relic_hold_invalidated") = synchronized(runtime) {
        if (hold.holdSinceMillis != Long.MIN_VALUE) {
            revokeIf(RewardActionKind.CLOSE_ITEM_REWARD, reason)
            reduce(reason)
        }
        hold = RelicRewardHoldState()
        holdEvidenceVersion = null
        relicRewardIdentityGate.clear()
    }

    private fun relicRewardSignature(reward: LabyrinthRelicRewardObservation): String =
        "${reward.kind}:${reward.rowSlots.sorted()}:${reward.relics.map { it.slotId to it.relicId }}"

    fun evaluateRelicChoice(input: RelicChoiceInput): RewardStep = synchronized(runtime) {
        evaluateRelicChoice(input, relicFocusMark)
    }

    fun evaluateRelicChoice(input: RelicChoiceInput, lockedFocus: LabyrinthRelicMark?): RewardStep =
        synchronized(runtime) {
            val rule = "relic_choice"
            if (!isCurrentFrame(Page.RELIC_CHOICE, input.atMillis)) return staleFrame()
            if (choiceEvidenceVersion != input.relicEvidenceVersion) {
                relicIdentityGate.clear()
                choiceEvidenceVersion = input.relicEvidenceVersion
                revokeIf(RewardActionKind.SELECT_RELIC, "relic_evidence_changed")
            }
            if (input.frameEvidenceVersion != input.relicEvidenceVersion) {
                relicIdentityGate.observe(Page.RELIC_CHOICE, input.atMillis, emptyList())
                if (relicChoiceCommittedAt == Long.MIN_VALUE) cancelRelicAcquisition(pendingRelicSelection?.relicId)
                return waitFor(rule, "等待当前版本的遗物候选")
            }
            val choices = input.observation?.choices.orEmpty()
            relicIdentityGate.observe(Page.RELIC_CHOICE, input.atMillis, choices)
            if (relicChoiceCommittedAt != Long.MIN_VALUE) {
                if (!labyrinthSelectionCommitExpired(relicChoiceCommittedAt, input.atMillis)) {
                    return waitFor(rule, "遗物选择已派发，等待页面确认")
                }
                cancelRelicAcquisition(pendingRelicSelection?.relicId)
                relicChoiceCommittedAt = Long.MIN_VALUE
                if (relicChoiceCommitAttempts < MAX_SELECTION_COMMIT_ATTEMPTS) {
                    revokeIf(RewardActionKind.SELECT_RELIC, "relic_choice_commit_retry")
                    reduce("relic_choice_commit_retry", mapOf("attempts" to relicChoiceCommitAttempts.toString()))
                }
            }
            if (relicChoiceCommitAttempts >= MAX_SELECTION_COMMIT_ATTEMPTS) {
                return needEvidence(rule, "RELIC_CHOICE_COMMIT_EXHAUSTED", KernelErrorCategory.TIMEOUT,
                    "遗物选择已点击3次仍停留在遗物页；停止以免反复空点")
            }
            val decision = relicChoicePolicy.choose(
                acquired = input.observedRelics, candidates = choices, lockedFocus = lockedFocus,
                calibratedStacks = input.calibratedStacks, currentArea = input.currentArea,
            ) ?: return waitForRelicChoice(rule, "等待可选择的遗物")
            val choice = decision.choice
            val relicId = choice.relicId
            if (pendingRelicSelection?.relicId != relicId) cancelRelicAcquisition(pendingRelicSelection?.relicId)
            if (relicId == null || !relicIdentityGate.confirmed(choice)) {
                return waitForRelicChoice(rule, "等待遗物身份跨帧确认")
            }
            val mark = LabyrinthRelicMark.fromLabel(choice.attribute)
            val cardConfirmed = choice.currentMarkStacks != null && choice.currentMarkStacksConfirmations >= 2 &&
                choice.currentMarkStacksCapturedAtMillis > input.evidenceAfterMillis &&
                input.calibratedStacks[mark] == choice.currentMarkStacks
            if (!cardConfirmed && relicIdentityGate.calibrationGraceOpen(input.atMillis)) {
                return waitForRelicChoice(rule, "等待确认遗物系列层数")
            }
            if (!choice.selectionButtonRect.fits(input.frameWidth, input.frameHeight)) {
                return waitForRelicChoice(rule, "遗物选择按钮超出当前画面")
            }
            if (!prepareRelicAcquisition(choice, input.observedRelics, input.openingRelicBaselineKnown)) {
                return waitForRelicChoice(rule, "等待可登记的遗物奖励")
            }
            pendingRelicSelection = choice
            if (decision.phase != LabyrinthRelicChoicePhase.BUILD_BASELINE) relicFocusMark = decision.focusMark
            act(rule, RewardActionKind.SELECT_RELIC, "选择遗物：${choice.displayName}；${decision.reason}",
                relicId, choice.selectionButtonRect, choice.slotId, reason = decision.reason)
        }

    private fun waitForRelicChoice(rule: String, reason: String): RewardStep {
        cancelRelicAcquisition(pendingRelicSelection?.relicId)
        return waitFor(rule, reason)
    }

    fun observeRelicIdentities(pageState: LabyrinthEntryPageState, atMillis: Long, relics: List<LabyrinthRelicMatch>) =
        synchronized(runtime) {
            if (!isCurrentFrame(pageState, atMillis)) return@synchronized
            relicIdentityGate.observe(pageState, atMillis, relics)
            val key = issuedKey
            if (key?.kind == RewardActionKind.SELECT_RELIC && relics.none {
                    it.slotId == key.slotId && it.relicId == key.targetId && relicIdentityGate.confirmed(it)
                }
            ) {
                revokePermit("relic_identity_lost")
                cancelRelicAcquisition(pendingRelicSelection?.relicId)
            }
        }

    fun relicIdentityConfirmed(relic: LabyrinthRelicMatch): Boolean =
        synchronized(runtime) { relicIdentityGate.confirmed(relic) }

    fun prepareRelicAcquisition(
        relic: LabyrinthRelicMatch, observedRelics: List<LabyrinthObservedRelic>, openingRelicBaselineKnown: Boolean,
    ): Boolean = synchronized(runtime) {
        val relicId = relic.relicId ?: return false
        if (observedRelics.any { it.relicId == relicId }) return false
        val mark = LabyrinthRelicMark.fromLabel(relic.attribute) ?: return false
        val bonus = relic.attributeBonus?.trim()?.toIntOrNull()?.takeIf { it > 0 } ?: return false
        val before = if (openingRelicBaselineKnown) relicChoicePolicy.markStacks(observedRelics).getValue(mark) else null
        ledger.prepare(relicId, mark, bonus, before)
    }

    fun hasPendingRelicAcquisition(relicId: String): Boolean = synchronized(runtime) { ledger.hasAcquisition(relicId) }

    fun cancelRelicAcquisition(relicId: String?) = synchronized(runtime) {
        ledger.cancel(relicId)
        if (relicId != null && pendingRelicSelection?.relicId == relicId) pendingRelicSelection = null
    }

    fun confirmRelicAcquired(relicId: String, atMillis: Long): Boolean = synchronized(runtime) {
        ledger.acquired(relicId, atMillis).also { if (pendingRelicSelection?.relicId == relicId) pendingRelicSelection = null }
    }

    fun markRelicAcquiredFromReceipt(
        relicId: String, mark: LabyrinthRelicMark?, bonus: Int, atMillis: Long, knownTotal: Int?,
    ): Boolean = synchronized(runtime) {
        val recorded = ledger.received(relicId, mark, bonus, atMillis, knownTotal)
        cancelRelicAcquisition(relicId)
        recorded
    }

    fun observeNodeRelicStacks(observation: LabyrinthNodeRelicStackObservation?, evidenceAfterMillis: Long) =
        synchronized(runtime) {
            observation?.entries.orEmpty().forEach { entry ->
                val mark = LabyrinthRelicMark.fromLabel(entry.attribute) ?: return@forEach
                if (entry.capturedAtMillis > evidenceAfterMillis) {
                    ledger.observe(mark, entry.stacks, LabyrinthRelicStackSource.MAP,
                        entry.slotId, entry.evidenceId, entry.capturedAtMillis)
                }
            }
        }

    fun relicStacksSnapshot(): Map<LabyrinthRelicMark, Int> = synchronized(runtime) { ledger.stacks }
    fun peekWaitingForMap(nowMillis: Long): Boolean = synchronized(runtime) { ledger.peekWaitingForMap(nowMillis) }
    fun waitingForMap(nowMillis: Long): Boolean = synchronized(runtime) { ledger.waitingForMap(nowMillis) }
    fun beginMapReread() = synchronized(runtime) { ledger.beginMapReread() }

    fun evaluateLinkChoice(
        observation: LabyrinthLinkChoiceObservation?, frameWidth: Int, frameHeight: Int, atMillis: Long,
    ): RewardStep = synchronized(runtime) {
        val rule = "link_choice"
        if (!isCurrentFrame(Page.LINK_CHOICE, atMillis)) return staleFrame()
        val choice = observation?.let { it.preferredChoice ?: it.choices.firstOrNull() }
        if (choice == null || !choice.selectionButtonRect.fits(frameWidth, frameHeight)) {
            return waitFor(rule, "等待连结印记和有效的选择按钮")
        }
        act(rule, RewardActionKind.SELECT_LINK_IMPRINT, "选择连结印记：${choice.element.label}",
            "${choice.slotId}:${choice.element.name}", choice.selectionButtonRect, choice.slotId)
    }

    fun observeRoleRewardRound(input: RoleRewardFrameInput): Boolean = synchronized(runtime) {
        if (frame == null || input.atMillis != capturedAt) return false
        val rewardPage = page == Page.INITIAL_CHARACTER_SELECTION && input.isRoleRewardPage
        if (rewardPage) enterRoleRewardBatch()
        roleSlots = safeRoleRewardSlots(input.characterMatches)
        if (!roleRewardRound.observe(rewardPage, roleSlots, input.atMillis)) return false
        clearRoleRewardDecision()
        revokeIf(RewardActionKind.SELECT_ROLE_REWARD, "role_reward_new_round")
        reduce("role_reward_new_round", mapOf("roundEpoch" to roleRewardRound.epoch().toString()))
        true
    }

    fun evaluateRoleRewardChoice(input: RoleRewardFrameInput): RewardStep = synchronized(runtime) {
        val rule = "role_reward"
        if (!isCurrentFrame(Page.INITIAL_CHARACTER_SELECTION, input.atMillis)) return staleFrame()
        observeRoleRewardRound(input)
        if (!input.isRoleRewardPage) {
            plannedRoleDecision = null
            roleRewardDecisionCacheKey = null
            cachedRoleDecision = null
            return publish(rule, KernelDecision.Complete(null, "当前画面未进入角色三选一"))
        }
        if (waitingForManualRoleSelection) return waitFor(rule, "等待手动选择角色")
        if (roleRewardRound.blocksPlanning()) {
            val locked = lockedRoleRewardDecision
            if (locked != null && !roleRewardRound.retryReady()) {
                return waitFor(rule, "本轮角色选择已派发，等待确认；不重新评分")
            }
            val safeSlot = roleSlots.entries.singleOrNull { it.value == roleRewardRound.selectedId() }?.key
            val freshRect = safeSlot?.let {
                LabyrinthRoleRewardChoicePlanner.selectionButtonRect(it, input.frameWidth, input.frameHeight)
            }
            if (locked == null || safeSlot == null || freshRect == null || !roleRewardRound.retryReady()) {
                return waitFor(rule, "本轮目标已锁定，等待可信画面或输入确认")
            }
            val rebound = locked.copy(buttonRect = freshRect)
            lockedRoleRewardDecision = rebound
            return proposeRoleReward(rebound, safeSlot)
        }

        val acquired = input.acquiredIds + pendingAcquiredIds
        val cacheKey = RoleCacheKey(input.frameWidth, input.frameHeight, input.defenseStacks, acquired,
            input.characterMatches.map {
                RoleCandidate(it.slotId, it.characterId, it.displayName, it.screenRect, labyrinthRoleRewardIdentityIsActionSafe(it))
            })
        val requestGeneration = generation
        val requestRound = roleRewardRound.epoch()
        val requestFrame = frame
        val choice = cachedRoleDecision?.takeIf { cacheKey == roleRewardDecisionCacheKey } ?: rolePlanner?.decide(
            input.characterMatches, acquired, LabyrinthRoleDecisionContext(defenseMarkStacks = input.defenseStacks),
            input.frameWidth, input.frameHeight,
        ) ?: LabyrinthRoleRewardChoiceDecision.Wait("角色三选一决策资料未加载")
        if (!labyrinthRoleDecisionCanPublish(
                requestFrame === frame, running, paused, requestGeneration, generation,
                requestRound, roleRewardRound.epoch(), roleRewardRound.blocksPlanning(),
            )
        ) return staleFrame()
        roleRewardDecisionCacheKey = cacheKey
        cachedRoleDecision = choice as? LabyrinthRoleRewardChoiceDecision.Select
        if (choice is LabyrinthRoleRewardChoiceDecision.Wait) return waitFor(rule, choice.reason)
        val selected = choice as LabyrinthRoleRewardChoiceDecision.Select
        val slot = roleSlots.entries.singleOrNull { it.value == selected.characterId }?.key
        if (!selected.actionSafe || slot == null) {
            return waitFor(rule, selected.safetyNote ?: "等待可靠的角色选择")
        }
        proposeRoleReward(selected, slot)
    }

    private fun proposeRoleReward(decision: LabyrinthRoleRewardChoiceDecision.Select, slot: String): RewardStep {
        plannedRoleDecision = decision
        return act("role_reward", RewardActionKind.SELECT_ROLE_REWARD, "选择角色：${decision.displayName}",
            decision.characterId, decision.buttonRect, slot, roleRewardRound.epoch(), decision.explanation.joinToString("；"))
    }

    private fun safeRoleRewardSlots(matches: List<LabyrinthCharacterMatch>): Map<String, String> =
        matches.mapNotNull { match ->
            val id = match.characterId?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            if (match.displayName.isNullOrBlank() || !labyrinthRoleRewardIdentityIsActionSafe(match)) null
            else match.slotId to canonicalLabyrinthRoleId(id)
        }.toMap()

    fun dispatchRoleRewardSelection(key: RewardActionKey, atMillis: Long): Long? = synchronized(runtime) {
        if (key.kind != RewardActionKind.SELECT_ROLE_REWARD) return null
        dispatchAction(key, atMillis)?.roleToken
    }

    fun recordRoleRewardReceipt(
        token: Long, executed: Boolean, atMillis: Long, reason: String = receiptReason(executed),
    ): KernelEvent? = synchronized(runtime) {
        val dispatch = pendingDispatch?.takeIf { it.roleToken == token } ?: return null
        recordActionReceipt(dispatch, executed, atMillis, reason)
    }

    private fun enterRoleRewardBatch() {
        if (!roleRewardBatchActive) {
            roleRewardBatchActive = true
            roleRewardJoinedSequenceStarted = false
            reduce("role_reward_batch_started")
        }
        if (!roleRewardJoinedSequenceStarted && acquisition.active) clearCharacterAcquisition("role_reward_batch_started")
        roleRewardChoiceCommitted = roleRewardRound.blocksDispatch()
    }

    private fun clearRoleRewardDecision() {
        lockedRoleRewardDecision = null
        cachedRoleDecision = null
        plannedRoleDecision = null
        roleRewardDecisionCacheKey = null
        roleRewardChoiceCommitted = false
        committedRoleRewardSelectionSignature = null
    }

    fun clearRoleRewardBatch(reason: String = "role_reward_batch_cleared") = synchronized(runtime) {
        val active = roleRewardBatchActive
        roleRewardRound.reset()
        roleRewardBatchActive = false
        roleRewardJoinedSequenceStarted = false
        waitingForManualRoleSelection = false
        clearRoleRewardDecision()
        roleSlots = emptyMap()
        revokeIf(RewardActionKind.SELECT_ROLE_REWARD, reason)
        if (active) reduce(reason)
    }

    fun observePageContext(
        previousPage: LabyrinthEntryPageState?, currentPage: LabyrinthEntryPageState, activeNodeType: Int?,
        isRoleRewardPage: Boolean, atMillis: Long,
    ) = synchronized(runtime) {
        if (!isCurrentFrame(currentPage, atMillis)) return@synchronized
        if (isRoleRewardPage && currentPage == Page.INITIAL_CHARACTER_SELECTION) enterRoleRewardBatch()
        if (!acquisition.active && !(roleRewardBatchActive && !roleRewardJoinedSequenceStarted) &&
            shouldArmCharacterAcquisitionFallback(previousPage, currentPage, activeNodeType)
        ) armCharacterAcquisition(atMillis)
        if (currentPage == Page.CHARACTER_JOINED && roleRewardBatchActive) {
            roleRewardJoinedSequenceStarted = true
            waitingForManualRoleSelection = false
            if (!acquisition.active) armCharacterAcquisition(atMillis)
        }
        if (currentPage != Page.INITIAL_CHARACTER_SELECTION) waitingForManualRoleSelection = false
        val keepsBatch = labyrinthKeepsRoleRewardBatchOnPage(currentPage, isRoleRewardPage)
        if (acquisition.active && !keepsBatch) clearCharacterAcquisition()
        if (roleRewardBatchActive && !keepsBatch) clearRoleRewardBatch()
    }

    fun setWaitingForManualRoleSelection(waiting: Boolean) = synchronized(runtime) {
        waitingForManualRoleSelection = waiting
        if (waiting) revokeIf(RewardActionKind.SELECT_ROLE_REWARD, "manual_role_selection")
    }

    private fun armCharacterAcquisition(atMillis: Long) {
        acquisition = CharacterAcquisitionState(active = true, startedAtMillis = atMillis)
        waitingForManualRoleSelection = false
        reduce("character_acquisition_started")
    }

    fun clearCharacterAcquisition(reason: String = "character_acquisition_cleared") = synchronized(runtime) {
        if (acquisition.active) {
            acquisition = CharacterAcquisitionState()
            revokeIf(RewardActionKind.ADVANCE_CHARACTER_ACQUISITION, reason)
            reduce(reason)
        }
    }

    fun evaluateCharacterJoined(
        result: LabyrinthEntryFrameResult, frameWidth: Int, frameHeight: Int,
    ): RewardStep = synchronized(runtime) {
        if (page != Page.CHARACTER_JOINED || result.observation.state != Page.CHARACTER_JOINED) return staleFrame()
        val rule = "character_joined"
        val rect = result.anchorRect(EntryAnchorId.JOINED_CLOSE, frameWidth, frameHeight)
            ?: result.anchorRect(EntryAnchorId.JOINED_CLOSE_STANDARD, frameWidth, frameHeight)
            ?: fallback(LabyrinthFallbackTap.MODAL_CLOSE, frameWidth, frameHeight)
            ?: return waitFor(rule, "等待角色加入关闭按钮")
        act(rule, RewardActionKind.CLOSE_CHARACTER_JOINED, "关闭角色加入结果", "character_joined", rect)
    }

    fun evaluateItemReward(
        result: LabyrinthEntryFrameResult, isFinalSettlementItemReward: Boolean, frameWidth: Int, frameHeight: Int,
    ): RewardStep = synchronized(runtime) {
        if (page != Page.ITEM_REWARD || result.observation.state != Page.ITEM_REWARD) return staleFrame()
        val rule = "item_reward"
        if (isFinalSettlementItemReward) {
            return publish(rule, KernelDecision.Complete(null, "最终道具奖励由结算流程处理"))
        }
        if (hold.active || (result.relicRewardObservation != null && (!hold.ready || hold.frameAtMillis != capturedAt))) {
            return waitFor(rule, "正在确认奖励内容，暂不关闭弹窗")
        }
        val rect = result.anchorRect(EntryAnchorId.RELIC_EFFECT_RESULT_CLOSE, frameWidth, frameHeight)
            ?: result.anchorRect(EntryAnchorId.ITEM_REWARD_CLOSE, frameWidth, frameHeight)
            ?: fallback(LabyrinthFallbackTap.MODAL_CLOSE, frameWidth, frameHeight)
            ?: return waitFor(rule, "等待道具奖励关闭按钮")
        act(rule, RewardActionKind.CLOSE_ITEM_REWARD, "关闭获得道具界面", "item_reward", rect)
    }

    fun evaluateCharacterAcquisitionAdvance(
        hasBattleControls: Boolean, frameWidth: Int, frameHeight: Int, atMillis: Long,
    ): RewardStep = synchronized(runtime) {
        val rule = "character_acquisition"
        if (frame == null || capturedAt != atMillis) return staleFrame()
        if (!acquisition.active) return publish(rule, KernelDecision.Complete(null, "角色获得动画兜底未启用"))
        if (atMillis - acquisition.startedAtMillis > CHARACTER_ACQUISITION_TIMEOUT_MILLIS) {
            return needEvidence(rule, "CHARACTER_ACQUISITION_TIMEOUT", KernelErrorCategory.TIMEOUT,
                "角色获得流程超过限定时间，已停止并保留诊断状态")
        }
        if (acquisition.clicks >= MAX_CHARACTER_ACQUISITION_CLICKS) {
            return needEvidence(rule, "CHARACTER_ACQUISITION_CLICK_LIMIT", KernelErrorCategory.EXECUTION,
                "角色获得动画推进达到上限，已停止并保留诊断状态")
        }
        if (page != Page.EVENT_ANIMATION || hasBattleControls) return waitFor(rule, "等待可推进的角色获得动画")
        val lastClick = acquisition.lastClickAtMillis
        if (lastClick != Long.MIN_VALUE && atMillis - lastClick < CHARACTER_ACQUISITION_INTERVAL_MILLIS) {
            return waitFor(rule, "等待角色获得动画推进间隔")
        }
        val rect = fallback(LabyrinthFallbackTap.CENTER, frameWidth, frameHeight)
            ?: return waitFor(rule, "等待有效的动画画面尺寸")
        act(rule, RewardActionKind.ADVANCE_CHARACTER_ACQUISITION, "推进角色获得动画", "character_acquisition", rect)
    }

    fun recordCharacterAcquisitionDispatched(key: RewardActionKey, atMillis: Long): RewardDispatch? =
        if (key.kind == RewardActionKind.ADVANCE_CHARACTER_ACQUISITION) dispatchAction(key, atMillis) else null

    fun rollbackCharacterAcquisitionClick(dispatch: RewardDispatch, atMillis: Long, reason: String): KernelEvent? =
        if (dispatch.key.kind == RewardActionKind.ADVANCE_CHARACTER_ACQUISITION) {
            recordActionReceipt(dispatch, false, atMillis, reason)
        } else null

    fun evaluateEventFreeRole(input: EventFreeRoleFrameInput): RewardStep = synchronized(runtime) {
        val result = input.result
        val currentPage = result.observation.state
        val rule = "event_free_role"
        if (!isCurrentFrame(currentPage, input.atMillis)) return staleFrame()
        val viewport = result.openingCharacterSelection
        if (!labyrinthEventFreeRoleSelectionOwnsFrame(
                currentPage, input.activeNodeType, input.isRoleRewardPage, viewport != null,
                input.shopChoiceImprintPending, input.routeActive,
            )
        ) {
            if (currentPage != Page.INITIAL_CHARACTER_SELECTION || input.isRoleRewardPage) clearEventFreeRole()
            return publish(rule, KernelDecision.Complete(null, "当前画面未进入事件自由选角"))
        }
        if (viewport == null || viewport.recognitionState != LabyrinthBattleTeamRecognitionState.STABLE) {
            return blockedFreeRole(input.atMillis, "事件自由选角：等待当前视口角色稳定识别")
        }
        val visible = viewport.visibleCharacters
        val selected = labyrinthEventFreeRoleReconcileSelection(
            rememberedIds = freeRoleSelectedIds,
            visibleSelectedIds = visible.filter { it.selected && it.trusted }.mapNotNull { it.characterId?.takeIf(String::isNotBlank) },
            visibleIds = visible.mapNotNull { it.characterId?.takeIf(String::isNotBlank) },
        )
        freeRoleSelectedIds = selected
        if (selected.size > freeRoleObservedSelectedCount) {
            freeRoleObservedSelectedCount = selected.size
            freeRoleBlockedSince = Long.MIN_VALUE
            if (freeRoleLastSelectAt == Long.MIN_VALUE) freeRoleLastSelectAt = input.atMillis
            reduce("event_free_role_selection_observed")
        }
        val scores = result.observation.anchorScores
        val invite = labyrinthInviteButtonState(
            maxOf(scores[EntryAnchorId.INVITE_ENABLED], scores[EntryAnchorId.INVITE_ENABLED_STANDARD]),
            maxOf(scores[EntryAnchorId.INVITE_DISABLED], scores[EntryAnchorId.INVITE_DISABLED_STANDARD]),
        )
        val inviteRect = result.anchorRect(EntryAnchorId.INVITE_ENABLED, input.frameWidth, input.frameHeight)
            ?: result.anchorRect(EntryAnchorId.INVITE_ENABLED_STANDARD, input.frameWidth, input.frameHeight)
        if (selected.isNotEmpty() && inviteRect != null && invite != LabyrinthInviteButtonState.DISABLED) {
            val message = "事件自由选角：已选择 ${selected.size} 名角色，等待“去邀请”生效"
            if (freeRoleExpired(input.atMillis)) return freeRoleTimeout(message)
            return act(rule, RewardActionKind.EVENT_FREE_ROLE_CONFIRM, "确认事件自由选角（${selected.size}名）",
                selected.joinToString(","), inviteRect)
        }
        if (selected.isNotEmpty() && !labyrinthEventFreeRoleNeedsAnotherPick(
                selected.size, invite == LabyrinthInviteButtonState.ENABLED, invite == LabyrinthInviteButtonState.DISABLED,
                input.atMillis - freeRoleLastSelectAt,
            )
        ) {
            return blockedFreeRole(input.atMillis, "事件自由选角：已选择 ${selected.size} 名角色，等待“去邀请”按钮就绪")
        }
        val choice = rolePlanner?.decideFreeVisibleRole(
            visible, input.acquiredIds + pendingAcquiredIds + selected,
            LabyrinthRoleDecisionContext(defenseMarkStacks = input.defenseStacks),
        )
        val select = (choice as? LabyrinthRoleRewardChoiceDecision.Select)?.takeIf {
            it.actionSafe && it.buttonRect.fits(input.frameWidth, input.frameHeight)
        }
        if (select == null) {
            val reason = (choice as? LabyrinthRoleRewardChoiceDecision.Wait)?.reason ?: "事件自由选角：等待安全推荐"
            return blockedFreeRole(input.atMillis, reason)
        }
        if (freeRoleExpired(input.atMillis)) return freeRoleTimeout("事件自由选角：选择始终未生效")
        val slot = visible.firstOrNull { it.characterId == select.characterId && it.trusted && !it.selected }?.slotId
        act(rule, RewardActionKind.EVENT_FREE_ROLE_SELECT, "事件自由选角：${select.displayName}",
            select.characterId, select.buttonRect, slot, reason = select.explanation.joinToString("；"))
    }

    private fun freeRoleExpired(atMillis: Long): Boolean {
        if (freeRoleBlockedSince == Long.MIN_VALUE) freeRoleBlockedSince = atMillis
        return labyrinthEventFreeRoleBlockedAction(atMillis - freeRoleBlockedSince) == LabyrinthEventFreeRoleBlockedAction.STOP
    }

    private fun blockedFreeRole(atMillis: Long, reason: String): RewardStep =
        if (freeRoleExpired(atMillis)) freeRoleTimeout(reason) else waitFor("event_free_role", reason)

    private fun freeRoleTimeout(reason: String): RewardStep =
        needEvidence("event_free_role", "EVENT_FREE_ROLE_TIMEOUT", KernelErrorCategory.TIMEOUT, reason)

    fun clearEventFreeRole(reason: String = "event_free_role_cleared") = synchronized(runtime) {
        val hadProgress = freeRoleSelectedIds.isNotEmpty() || freeRoleBlockedSince != Long.MIN_VALUE
        freeRoleSelectedIds = emptyList()
        freeRoleObservedSelectedCount = 0
        freeRoleBlockedSince = Long.MIN_VALUE
        freeRoleLastSelectAt = Long.MIN_VALUE
        revokeIf(RewardActionKind.EVENT_FREE_ROLE_SELECT, reason)
        revokeIf(RewardActionKind.EVENT_FREE_ROLE_CONFIRM, reason)
        if (hadProgress) reduce(reason)
    }

    fun revokePermit(reason: String) = synchronized(runtime) { permit.revoke(reason) }

    private fun revokeIf(kind: RewardActionKind, reason: String) {
        if (issuedKey?.kind == kind) permit.revoke(reason)
    }

    fun capturePermit(key: RewardActionKey, nowMillis: Long): KernelActionPermit.Token? = synchronized(runtime) {
        if (!running || paused || pendingDispatch != null || dispatchedFrame === frame) return null
        permit.capture(key, nowMillis)
    }

    fun dispatchAction(key: RewardActionKey, atMillis: Long): RewardDispatch? = synchronized(runtime) {
        if (capturePermit(key, atMillis) == null) return null
        var roleToken: Long? = null
        var addedPendingRole = false
        val acquisitionAtDispatch = when (key.kind) {
            RewardActionKind.ADVANCE_CHARACTER_ACQUISITION,
            RewardActionKind.CLOSE_CHARACTER_JOINED,
            RewardActionKind.CLOSE_ITEM_REWARD,
            -> acquisition
            else -> null
        }
        when (key.kind) {
            RewardActionKind.SELECT_ROLE_REWARD -> {
                if (roleRewardRound.blocksDispatch() || key.roundEpoch != roleRewardRound.epoch() ||
                    waitingForManualRoleSelection
                ) return null
                val selected = plannedRoleDecision?.takeIf { it.characterId == key.targetId && it.buttonRect == key.rect }
                    ?: return null
                roleToken = roleRewardRound.submit(key.targetId, roleSlots, atMillis) ?: return null
                lockedRoleRewardDecision = selected
                roleRewardChoiceCommitted = true
                committedRoleRewardSelectionSignature = roleSlots
                roleRewardBatchActive = true
                addedPendingRole = pendingAcquiredIds.add(key.targetId)
            }
            RewardActionKind.SELECT_RELIC -> {
                if (pendingRelicSelection?.relicId != key.targetId) return null
                relicChoiceCommittedAt = atMillis
                relicChoiceCommitAttempts++
            }
            RewardActionKind.ADVANCE_CHARACTER_ACQUISITION -> {
                acquisition = acquisition.copy(clicks = acquisition.clicks + 1, lastClickAtMillis = atMillis)
            }
            else -> Unit
        }
        val confirmedIds = if (key.kind == RewardActionKind.EVENT_FREE_ROLE_CONFIRM) freeRoleSelectedIds.toSet() else emptySet()
        dispatchedFrame = frame
        val event = emit(K.ACTION_DISPATCHED, ActionDispatchedPayload(key.targetId), key.kind.name)
        val dispatch = RewardDispatch(key, event, roleToken, addedPendingRole, acquisitionAtDispatch, confirmedIds)
        pendingDispatch = dispatch
        dispatch
    }

    fun recordActionReceipt(
        dispatch: RewardDispatch, executed: Boolean, atMillis: Long, reason: String = receiptReason(executed),
    ): KernelEvent? = synchronized(runtime) {
        if (dispatch !== pendingDispatch) return null
        val key = dispatch.key
        pendingDispatch = null
        val followsCurrentFrame = frame != null && dispatch.event.traceId == frame?.traceId &&
            dispatch.event.frameSeq == frame?.frameSeq && dispatch.event.sceneEpoch == sceneEpoch
        val receipt = runtime.emit(
            domain, K.ACTION_RECEIPT_OBSERVED, ActionReceiptPayload(key.targetId, reason),
            dispatch.event, reason, sceneEpoch = dispatch.event.sceneEpoch,
        )
        if (followsCurrentFrame) parent = receipt
        when (key.kind) {
            RewardActionKind.SELECT_ROLE_REWARD -> {
                val token = requireNotNull(dispatch.roleToken)
                if (executed) {
                    roleRewardRound.inputFinished(token, atMillis)
                } else {
                    if (dispatch.addedPendingRole) pendingAcquiredIds.remove(key.targetId)
                    if (roleRewardRound.notExecuted(token, atMillis)) {
                        roleRewardChoiceCommitted = false
                        committedRoleRewardSelectionSignature = null
                        revokeIf(RewardActionKind.SELECT_ROLE_REWARD, reason)
                        reduce("role_reward_retry_ready")
                    }
                }
            }
            RewardActionKind.SELECT_RELIC -> if (relicChoiceCommittedAt != Long.MIN_VALUE) {
                if (executed) {
                    relicChoiceCommittedAt = maxOf(relicChoiceCommittedAt, atMillis)
                } else {
                    relicChoiceCommittedAt = Long.MIN_VALUE
                    relicChoiceCommitAttempts = (relicChoiceCommitAttempts - 1).coerceAtLeast(0)
                    cancelRelicAcquisition(key.targetId)
                }
            }
            RewardActionKind.CLOSE_CHARACTER_JOINED -> if (executed && acquisitionMatches(dispatch)) {
                if (!roleRewardBatchActive) clearCharacterAcquisition()
                else acquisition = acquisition.copy(startedAtMillis = atMillis)
            }
            RewardActionKind.CLOSE_ITEM_REWARD -> if (executed && !roleRewardBatchActive && acquisitionMatches(dispatch)) {
                clearCharacterAcquisition()
            }
            RewardActionKind.ADVANCE_CHARACTER_ACQUISITION -> if (!executed && acquisitionMatches(dispatch)) {
                acquisition = requireNotNull(dispatch.acquisitionAtDispatch)
            }
            RewardActionKind.EVENT_FREE_ROLE_SELECT -> if (executed && page == Page.INITIAL_CHARACTER_SELECTION &&
                sceneEpoch == key.sceneEpoch
            ) {
                freeRoleSelectedIds = (freeRoleSelectedIds + key.targetId).distinct()
                freeRoleLastSelectAt = atMillis
            }
            RewardActionKind.EVENT_FREE_ROLE_CONFIRM -> if (executed) {
                pendingAcquiredIds.addAll(dispatch.confirmedFreeRoleIds.map(::canonicalLabyrinthRoleId))
                if (page == Page.INITIAL_CHARACTER_SELECTION && sceneEpoch == key.sceneEpoch) clearEventFreeRole()
            }
            RewardActionKind.SELECT_LINK_IMPRINT -> Unit
        }
        if (issuedKey == key) revokePermit(if (executed) "reward_action_completed" else reason)
        receipt
    }

    private fun acquisitionMatches(dispatch: RewardDispatch): Boolean =
        acquisition.active && acquisition.startedAtMillis == dispatch.acquisitionAtDispatch?.startedAtMillis

    fun acknowledgeJoinedCharacter(characterId: String): Boolean =
        synchronized(runtime) { pendingAcquiredIds.remove(canonicalLabyrinthRoleId(characterId)) }

    private fun isCurrentFrame(expectedPage: LabyrinthEntryPageState, atMillis: Long) =
        page == expectedPage && capturedAt == atMillis && frame != null

    private fun staleFrame() = RewardStep(KernelDecision.Wait("等待当前页面的新帧证据"))

    private fun reduce(reason: String, detail: Map<String, String> = emptyMap()) {
        page?.let { emit(K.STATE_REDUCED, StateReducedPayload(it.name, detail = detail), reason) }
    }

    private fun emit(kind: String, payload: KernelEventPayload, reason: String): KernelEvent =
        runtime.emit(domain, kind, payload, parent, reason).also { parent = it }

    private fun act(
        rule: String, kind: RewardActionKind, label: String, targetId: String, rect: EntryPixelRect,
        slotId: String? = null, roundEpoch: Long? = null, reason: String = rule,
    ): RewardStep {
        val currentPage = page ?: return staleFrame()
        val key = RewardActionKey(kind, currentPage, sceneEpoch, targetId, slotId, rect, roundEpoch, frame?.frameSeq)
        return publish(rule, KernelDecision.Act(kind.actionKind, label, targetId, rect, reason), key)
    }

    private fun waitFor(rule: String, message: String): RewardStep = publish(rule, KernelDecision.Wait(message))

    private fun needEvidence(rule: String, code: String, category: KernelErrorCategory, message: String): RewardStep =
        publish(rule, KernelDecision.NeedEvidence(KernelError(code, category, id, message), message))

    private fun publish(rule: String, decision: KernelDecision, key: RewardActionKey? = null): RewardStep {
        if (key != null && (pendingDispatch != null || dispatchedFrame === frame || !running || paused || capturedAt < 0)) {
            return waitFor(rule, "等待当前奖励动作可派发")
        }
        val reason = when (decision) {
            is KernelDecision.Act -> decision.reason
            is KernelDecision.Wait -> decision.reason
            is KernelDecision.NeedEvidence -> decision.reason
            is KernelDecision.Complete -> decision.summary
            is KernelDecision.FinishRun -> decision.reason
        }
        val code = (decision as? KernelDecision.NeedEvidence)?.error?.code ?: rule
        emit(K.EVENT_RULE_EVALUATED, PolicyRuleEvaluatedPayload(rule, decision.javaClass.simpleName), code)
        val proposal = emit(K.EVENT_DECISION_PROPOSED, DecisionProposedPayload(rule, key?.targetId, key != null, reason), code)
        val intervention = runtime.latestIntervention?.takeIf { it.eventSeq == proposal.eventSeq }
        val verdict = intervention?.verdict
        if (key != null && verdict is KernelHookVerdict.Veto) {
            parent = intervention.cause
            revokePermit("reward_hook_veto")
            return RewardStep(KernelDecision.Wait(verdict.reason))
        }
        if (key == null) revokePermit(code)
        else permit.issue(key, capturedAt, rule)
        return RewardStep(decision, key)
    }

    private fun EntryPixelRect.fits(frameWidth: Int, frameHeight: Int): Boolean =
        left.toLong() + width <= frameWidth && top.toLong() + height <= frameHeight

    private fun LabyrinthEntryFrameResult.anchorRect(id: String, width: Int, height: Int): EntryPixelRect? =
        anchorMatches[id]?.takeIf { it.score >= POST_ENTRY_ANCHOR_MIN_SCORE }?.rect?.takeIf { it.fits(width, height) }

    private fun fallback(tap: LabyrinthFallbackTap, width: Int, height: Int): EntryPixelRect? =
        if (width >= 3 && height >= 3) tap.rect(width, height) else null

    private companion object {
        const val RELIC_REWARD_HOLD_TIMEOUT_MILLIS = 8_000L
        const val MAX_SELECTION_COMMIT_ATTEMPTS = 3
        const val CHARACTER_ACQUISITION_INTERVAL_MILLIS = 650L
        const val CHARACTER_ACQUISITION_TIMEOUT_MILLIS = 30_000L
        const val MAX_CHARACTER_ACQUISITION_CLICKS = 40
        const val POST_ENTRY_ANCHOR_MIN_SCORE = 0.45

        fun receiptReason(executed: Boolean): String = if (executed) "executed" else "not_executed"
    }
}
