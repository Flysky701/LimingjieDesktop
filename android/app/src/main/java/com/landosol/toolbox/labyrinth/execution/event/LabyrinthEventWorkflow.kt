package com.landosol.toolbox.labyrinth.execution.event

import com.landosol.toolbox.labyrinth.*
import com.landosol.toolbox.automation.AutomationSessionId
import com.landosol.toolbox.labyrinth.vision.EntryAnchorId
import com.landosol.toolbox.labyrinth.vision.EntryPixelRect
import com.landosol.toolbox.labyrinth.vision.LabyrinthEntryFrameResult
import com.landosol.toolbox.labyrinth.vision.LabyrinthEntryPageState
import com.landosol.toolbox.labyrinth.vision.LabyrinthBattleTeamRecognitionState
import com.landosol.toolbox.labyrinth.node.LabyrinthNodeTypes
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import com.landosol.toolbox.labyrinth.LabyrinthExecutionLimits.MAX_EVENT_CHOICE_COMMIT_ATTEMPTS
import com.landosol.toolbox.labyrinth.LabyrinthExecutionLimits.POST_ENTRY_ACTION_INTERVAL_MILLIS

internal interface LabyrinthEventPort {
    fun requestScoreRestart(sessionId: AutomationSessionId, reason: String): Boolean
    val _state: MutableStateFlow<LabyrinthEntryRecognitionSessionState>
    val actionInFlight: AtomicBoolean
    var activeNodeArea: Int?
    var activeNodeType: Int?
    val activeSessionId: AutomationSessionId?
    val clock: () -> Long
    val combatContext: LabyrinthCombatContext?
    var eventActionAttempts: Int
    var eventChoiceCommitAttempts: Int
    var eventChoiceCommitted: Boolean
    var eventChoiceCommittedAt: Long
    var eventChoiceCommittedChoiceId: String?
    val eventChoicePlanner: LabyrinthEventChoicePlanner?
    val eventChoiceRejectedIds: MutableSet<String>
    var eventFreeRoleBlockedSince: Long
    var eventFreeRoleLastSelectAt: Long
    var eventFreeRoleObservedSelectedCount: Int
    val eventFreeRoleSelectedCharacterIds: MutableSet<String>
    val lastPostEntryActionAt: Long
    val lastPostEntryActionLandedAt: Long
    val relicChoicePolicy: LabyrinthRelicChoicePolicy
    val relicMarkStackCalibration: Map<LabyrinthRelicMark, Int>
    val roleRewardChoicePlanner: LabyrinthRoleRewardChoicePlanner?
    val roleRewardChoiceUnavailableReason: String?
    val shopChoiceImprintRolePending: Boolean
    val validatedRoute: LabyrinthRouteJson?
    fun anchorRect(result: LabyrinthEntryFrameResult, anchorId: String): EntryPixelRect?
    fun clearShopChoiceImprintRolePending(): Unit
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
    fun finishFromPlanner(sessionId: AutomationSessionId, reason: String): Unit
    fun nodeLog(message: String, warning: Boolean = false): Unit
    fun postEntryIsRoleRewardPage(result: LabyrinthEntryFrameResult): Boolean
    fun traceWorkflow(owner: String, phase: String): Unit
    fun pendingCharacterIds(): Set<String>
    fun recordEventCharacterConfirmation(confirmed: List<String>)
}

/** 管理事件选项、拒绝记录、自由选角与事件动作回执。 */
internal class LabyrinthEventWorkflow(private val port: LabyrinthEventPort) : LabyrinthEventPort by port {
    /** Evaluates a catalog-resolved event in both Dry Run and guarded live sessions. */
    fun refreshEventChoiceDecision(
        sessionId: AutomationSessionId,
        result: LabyrinthEntryFrameResult,
    ) {
        if (activeSessionId != sessionId) return
        val observation = result.eventChoiceSelection
        if (result.observation.state != LabyrinthEntryPageState.EVENT_CHOICE || observation == null) {
            if (_state.value.eventChoiceDecision != null) {
                nodeLog("event-choice-decision-cleared page=${result.observation.state} " +
                    "ocr=${result.eventOcrDiagnostics?.status} cache=${result.eventOcrDiagnostics?.cache?.status} " +
                    "cacheAgeMillis=${result.eventOcrDiagnostics?.cache?.ageMillis}")
                _state.value = _state.value.copy(eventChoiceDecision = null)
            }
            return
        }
        val planner = eventChoicePlanner
        val decision = if (planner == null) {
            LabyrinthEventChoiceDecision.Wait("事件推荐资料未加载")
        } else {
            val current = _state.value
            val stacks = relicChoicePolicy.markStacks(current.observedRelics, relicMarkStackCalibration)
            runCatching {
                planner.decide(
                    observation = observation,
                    acquiredCharacterIds = current.joinedCharacters
                        .map(LabyrinthJoinedCharacter::characterId)
                        .toSet(),
                    context = LabyrinthRoleDecisionContext(
                        defenseMarkStacks = stacks.getValue(LabyrinthRelicMark.DEFENSE),
                        targetCount = combatContext?.targetCount ?: 1,
                    ),
                    relicStacks = stacks,
                    rejectedChoiceIds = eventChoiceRejectedIds.toSet(),
                )
            }.getOrElse { failure ->
                LabyrinthEventChoiceDecision.Wait(
                    "事件${observation.event.id}推荐失败：${failure.message ?: "未知错误"}",
                )
            }
        }
        val message = when (decision) {
            is LabyrinthEventChoiceDecision.Select -> buildString {
                append("事件${decision.eventId}建议：${decision.label}")
                if (!decision.actionSafe) append("（仅推荐）")
                append("；${decision.explanation}")
                decision.safetyNote?.let { append("；$it") }
            }
            is LabyrinthEventChoiceDecision.Wait -> decision.reason
        }
        _state.value = _state.value.copy(eventChoiceDecision = decision, message = message)
    }

    fun resetEventChoiceCommit() {
        eventChoiceCommitted = false
        eventChoiceCommittedAt = Long.MIN_VALUE
        eventChoiceCommitAttempts = 0
        eventChoiceCommittedChoiceId = null
        eventChoiceRejectedIds.clear()
    }

    /**
     * EVENT-only full-roster selector.  This deliberately does not reuse the opening roster's
     * fixed guild policy or 3/3 counter.  The current viewport is ranked with the same role model
     * used by three-choice rewards, then the page's 1-person invite button is confirmed.
     */
    fun handleEventFreeRoleSelection(
        sessionId: AutomationSessionId,
        result: LabyrinthEntryFrameResult,
        timestampMillis: Long,
    ) {
        traceWorkflow("event", "free-role-selection")
        if (activeSessionId != sessionId || actionInFlight.get()) return
        val viewport = result.openingCharacterSelection ?: run {
            noteEventFreeRoleBlocked(sessionId, timestampMillis, "事件自由选角：等待角色视口生成")
            return
        }
        if (viewport.recognitionState != LabyrinthBattleTeamRecognitionState.STABLE) {
            // One unrecognizable portrait among a full roster used to hold the page for ever.
            noteEventFreeRoleBlocked(sessionId, timestampMillis, "事件自由选角：等待当前视口角色稳定识别")
            return
        }

        // The roster, not our own dispatch log, decides who is selected: a tap the game ignored
        // must not pin the page on the confirm branch for ever.
        val visibleIds = viewport.visibleCharacters
            .mapNotNull { it.characterId?.takeIf(String::isNotBlank) }
        val visibleSelectedIds = viewport.visibleCharacters
            .filter { it.selected && it.trusted }
            .mapNotNull { it.characterId?.takeIf(String::isNotBlank) }
        val selectedIds = labyrinthEventFreeRoleReconcileSelection(
            rememberedIds = eventFreeRoleSelectedCharacterIds,
            visibleSelectedIds = visibleSelectedIds,
            visibleIds = visibleIds,
        )
        eventFreeRoleSelectedCharacterIds.clear()
        eventFreeRoleSelectedCharacterIds += selectedIds
        if (selectedIds.size > eventFreeRoleObservedSelectedCount) {
            // Observed progress is the only thing allowed to restart the give-up clock.
            eventFreeRoleObservedSelectedCount = selectedIds.size
            resetEventFreeRoleProgress()
        }

        val scores = result.observation.anchorScores
        val inviteState = labyrinthInviteButtonState(
            enabledScore = maxOf(
                scores[EntryAnchorId.INVITE_ENABLED],
                scores[EntryAnchorId.INVITE_ENABLED_STANDARD],
            ),
            disabledScore = maxOf(
                scores[EntryAnchorId.INVITE_DISABLED],
                scores[EntryAnchorId.INVITE_DISABLED_STANDARD],
            ),
        )
        val inviteRect = anchorRect(result, EntryAnchorId.INVITE_ENABLED)
            ?: anchorRect(result, EntryAnchorId.INVITE_ENABLED_STANDARD)
        if (
            selectedIds.isNotEmpty() &&
            inviteRect != null &&
            inviteState != LabyrinthInviteButtonState.DISABLED
        ) {
            if (labyrinthPostEntryCooldownRemaining(
                    frameTimestampMillis = timestampMillis,
                    dispatchedAtMillis = lastPostEntryActionAt,
                    landedAtMillis = lastPostEntryActionLandedAt,
                    intervalMillis = POST_ENTRY_ACTION_INTERVAL_MILLIS,
                ) > 0L
            ) return
            if (eventFreeRoleBlockedSince == Long.MIN_VALUE) eventFreeRoleBlockedSince = timestampMillis
            if (
                labyrinthEventFreeRoleBlockedAction(timestampMillis - eventFreeRoleBlockedSince) ==
                LabyrinthEventFreeRoleBlockedAction.STOP
            ) {
                finishFromPlanner(
                    sessionId,
                    "事件自由选角无法继续：已选择 ${selectedIds.size} 名角色但“去邀请”始终未生效",
                )
                return
            }
            dispatchPostEntryTap(
                sessionId = sessionId,
                kind = LabyrinthPostEntryActionKind.EVENT_FREE_ROLE_CONFIRM,
                label = "确认事件自由选角（${selectedIds.size}名）",
                rect = inviteRect,
                timestampMillis = timestampMillis,
                eventFreeRoleCandidateId = selectedIds.first(),
                eventFreeRoleConfirm = true,
                eventFreeRoleConfirmIds = selectedIds,
            )
            return
        }
        if (selectedIds.isNotEmpty()) {
            val needsAnother = labyrinthEventFreeRoleNeedsAnotherPick(
                selectedCount = selectedIds.size,
                inviteEnabled = inviteState == LabyrinthInviteButtonState.ENABLED,
                inviteDisabled = inviteState == LabyrinthInviteButtonState.DISABLED,
                millisSinceLastSelect = timestampMillis - eventFreeRoleLastSelectAt,
            )
            if (!needsAnother) {
                // Even "the button is not ready yet" is a wait, so it runs on the give-up clock.
                noteEventFreeRoleBlocked(
                    sessionId,
                    timestampMillis,
                    "事件自由选角：已选择 ${selectedIds.size} 名角色，等待“去邀请”按钮就绪",
                )
                return
            }
            // 去邀请 stays disabled after the pick settled: this event wants more than one.
        }

        val planner = roleRewardChoicePlanner ?: run {
            _state.value = _state.value.copy(
                message = roleRewardChoiceUnavailableReason ?: "事件自由选角：角色评分资料未加载",
            )
            return
        }
        val defenseStacks = relicChoicePolicy.markStacks(
            _state.value.observedRelics,
            relicMarkStackCalibration,
        ).getValue(LabyrinthRelicMark.DEFENSE)
        val pendingSelectedIds = pendingCharacterIds()
        val acquiredIds = _state.value.joinedCharacters
            .map(LabyrinthJoinedCharacter::characterId)
            .plus(pendingSelectedIds)
            .plus(selectedIds)
            .toSet()
        val decision = planner.decideFreeVisibleRole(
            candidates = viewport.visibleCharacters,
            acquiredCharacterIds = acquiredIds,
            context = LabyrinthRoleDecisionContext(defenseMarkStacks = defenseStacks),
        )
        when (decision) {
            is LabyrinthRoleRewardChoiceDecision.Wait -> {
                // Nothing usable *here*: with a 32-role party and a roster several screens long,
                // the first viewport can legitimately hold only already-owned characters.
                noteEventFreeRoleBlocked(sessionId, timestampMillis, decision.reason)
            }

            is LabyrinthRoleRewardChoiceDecision.Select -> {
                if (!decision.actionSafe) {
                    noteEventFreeRoleBlocked(
                        sessionId,
                        timestampMillis,
                        decision.safetyNote ?: "事件自由选角：推荐尚未达到自动点击安全条件",
                    )
                    return
                }
                // Session recovery clears the action timestamp to Long.MIN_VALUE. Subtracting
                // that sentinel overflows and would hold every stable picker frame indefinitely.
                if (labyrinthPostEntryCooldownRemaining(
                        frameTimestampMillis = timestampMillis,
                        dispatchedAtMillis = lastPostEntryActionAt,
                        landedAtMillis = lastPostEntryActionLandedAt,
                        intervalMillis = POST_ENTRY_ACTION_INTERVAL_MILLIS,
                    ) > 0L
                ) return
                _state.value = _state.value.copy(
                    message = "事件自由选角建议（第${selectedIds.size + 1}名）：${decision.displayName}；${decision.explanation.take(2).joinToString("；")}",
                )
                dispatchPostEntryTap(
                    sessionId = sessionId,
                    kind = LabyrinthPostEntryActionKind.EVENT_FREE_ROLE_SELECT,
                    label = "事件自由选角：${decision.displayName}",
                    rect = decision.buttonRect,
                    timestampMillis = timestampMillis,
                    eventFreeRoleCandidateId = decision.characterId,
                )
            }
        }
    }

    fun resetEventFreeRoleProgress() {
        eventFreeRoleBlockedSince = Long.MIN_VALUE
    }

    /**
     * The free-role page has nothing it can act on right now.
     *
     * Every branch that reports this used to just publish a message and return, which is an
     * unbounded wait on a page with no other timeout: an unreadable portrait, a viewport holding
     * nothing but already-owned characters, or a recommendation that never clears the safety bar
     * would each park the run there silently. A short wait is still right (the list animates, OCR
     * catches up); after that, look further down the roster; and once the roster has been walked
     * without a usable candidate, stop with the reason instead of holding a dead session open.
     */
    fun noteEventFreeRoleBlocked(
        sessionId: AutomationSessionId,
        timestampMillis: Long,
        reason: String,
    ) {
        if (eventFreeRoleBlockedSince == Long.MIN_VALUE) eventFreeRoleBlockedSince = timestampMillis
        when (labyrinthEventFreeRoleBlockedAction(timestampMillis - eventFreeRoleBlockedSince)) {
            LabyrinthEventFreeRoleBlockedAction.WAIT ->
                if (_state.value.message != reason) _state.value = _state.value.copy(message = reason)

            LabyrinthEventFreeRoleBlockedAction.STOP ->
                finishFromPlanner(sessionId, "事件自由选角无法继续：$reason")
        }
    }

    private fun rejectOffTargetEvent(sessionId: AutomationSessionId, result: LabyrinthEntryFrameResult): Boolean {
        val route = validatedRoute?.takeIf { it.seekScoreEvent } ?: return false
        val node = labyrinthPersistedCurrentNode(route)?.takeIf { it.blockType == LabyrinthRoutePolicy.EVENT_BLOCK_TYPE } ?: return false
        val event = result.eventChoiceSelection?.takeIf { it.trusted }?.event ?: return false
        val wanted = when (node.area to node.column) { 1 to 5 -> "1105"; 2 to 2 -> "1111"; else -> return false }
        return event.id != wanted && requestScoreRestart(sessionId, "凹分目标事件 $wanted，已确认本格为 ${event.id}")
    }

    fun planEventChoice(sessionId: AutomationSessionId, result: LabyrinthEntryFrameResult, timestampMillis: Long): LabyrinthPostEntryTapPlan? =
            if (!eventChoiceCommitted && rejectOffTargetEvent(sessionId, result)) null
            else if (
                eventChoiceCommitted &&
                !labyrinthEventChoiceCommitExpired(eventChoiceCommittedAt, timestampMillis)
            ) {
                null
            } else if (
                eventChoiceCommitted &&
                eventChoiceCommitAttempts >= maxOf(
                    MAX_EVENT_CHOICE_COMMIT_ATTEMPTS,
                    (result.eventChoiceSelection?.event?.choices?.size ?: 0) + 1,
                )
            ) {
                finishFromPlanner(
                    sessionId,
                    "事件选项已点击${eventChoiceCommitAttempts}次仍停留在事件页；停止以免反复空点",
                )
                null
            } else {
                // A committed tap that the game ignored used to latch this page forever: the flag
                // only cleared on a page change, and the page never changed. The run then sat on
                // "事件推荐已生成，等待按钮稳定" — a message that describes waiting for a button,
                // while the truth was that the tap had already been sent and lost (2026-09-20
                // bundle 133934: 323 s on one EVENT_CHOICE page, actionCount frozen at 271).
                if (eventChoiceCommitted) {
                    nodeLog("event-choice-retry elapsedMillis=${timestampMillis - eventChoiceCommittedAt} " +
                        "choiceId=$eventChoiceCommittedChoiceId attempts=$eventChoiceCommitAttempts " +
                        "event=${result.eventChoiceSelection?.event?.id} " +
                        "ocr=${result.eventOcrDiagnostics?.status} cache=${result.eventOcrDiagnostics?.cache?.status}")
                    eventChoiceCommitted = false
                    eventChoiceCommitAttempts++
                    // The tap landed and the page did not move on. That option is gated -- the
                    // usual reason is a price the current alpha-coin balance cannot meet, which
                    // no pixel on the button reveals. Retire it and let the next-best option run.
                    eventChoiceCommittedChoiceId?.let { rejected ->
                        eventChoiceRejectedIds += rejected
                        nodeLog("event-choice-refused id=$rejected rejected=$eventChoiceRejectedIds", warning = true)
                    }
                    eventChoiceCommittedChoiceId = null
                }
                when (val decision = _state.value.eventChoiceDecision) {
                    is LabyrinthEventChoiceDecision.Select -> if (decision.actionSafe) {
                        LabyrinthPostEntryTapPlan(
                            LabyrinthPostEntryActionKind.SELECT_EVENT,
                            "选择事件：${decision.choiceId} ${decision.label}；${decision.explanation}",
                            decision.buttonRect,
                        )
                    } else {
                        null
                    }
                    is LabyrinthEventChoiceDecision.Wait -> null
                    null -> labyrinthSingleChoiceEventButtonRect(result)?.let { rect ->
                        nodeLog("event-choice-fallback source=SINGLE_CHOICE_ANCHORS rect=$rect " +
                            "layoutCount=${result.eventOcrDiagnostics?.completeLayoutCount} " +
                            "ocr=${result.eventOcrDiagnostics?.status} " +
                            "cache=${result.eventOcrDiagnostics?.cache?.status} rejected=$eventChoiceRejectedIds")
                        LabyrinthPostEntryTapPlan(
                            LabyrinthPostEntryActionKind.SELECT_EVENT,
                            "选择事件：唯一选项（单选事件结构确认）",
                            rect,
                        )
                    }
                }
            }

    fun handleEventFreeRoleFrame(sessionId: AutomationSessionId, result: LabyrinthEntryFrameResult, timestampMillis: Long, dryRun: Boolean, routeHasPassedOpening: Boolean): Boolean {
        val pageState = result.observation.state
        val persistedCurrentNode = labyrinthPersistedCurrentNode(validatedRoute)
        val effectiveNodeType = activeNodeType ?: persistedCurrentNode?.blockType
        val eventFreeRoleSelection = labyrinthEventFreeRoleSelectionOwnsFrame(
            pageState = pageState,
            activeNodeType = effectiveNodeType,
            roleRewardPage = postEntryIsRoleRewardPage(result),
            hasOpeningViewport = result.openingCharacterSelection != null,
            shopChoiceImprintPending = shopChoiceImprintRolePending,
            routeActive = routeHasPassedOpening,
        )
        if (eventFreeRoleSelection) {
            if (activeNodeType == null && persistedCurrentNode?.blockType == LabyrinthNodeTypes.EVENT) {
                activeNodeType = LabyrinthNodeTypes.EVENT
                activeNodeArea = persistedCurrentNode.area
            }
            if (dryRun) {
                if (_state.value.message != "事件自由选角页：Dry Run 不点击") {
                    _state.value = _state.value.copy(message = "事件自由选角页：Dry Run 不点击")
                }
            } else {
                handleEventFreeRoleSelection(sessionId, result, timestampMillis)
            }
            return true
        }
        return false
    }

    fun onEventTapExecuted(kind: LabyrinthPostEntryActionKind, eventFreeRoleCandidateId: String?, eventFreeRoleConfirm: Boolean, eventFreeRoleConfirmIds: List<String>): Unit {
        if (kind == LabyrinthPostEntryActionKind.EVENT_FREE_ROLE_SELECT && eventFreeRoleCandidateId != null) {
            eventFreeRoleSelectedCharacterIds += eventFreeRoleCandidateId
            eventFreeRoleLastSelectAt = clock()
        }
        if (eventFreeRoleConfirm) {
            // Whatever opened this picker has now been answered.
            clearShopChoiceImprintRolePending()
            val confirmed = eventFreeRoleConfirmIds.ifEmpty { listOfNotNull(eventFreeRoleCandidateId) }
            if (confirmed.isNotEmpty()) {
                recordEventCharacterConfirmation(confirmed)
            }
        }
        if (kind == LabyrinthPostEntryActionKind.SELECT_EVENT) {
            eventChoiceCommitted = true
            eventChoiceCommittedAt = clock()
            eventChoiceCommittedChoiceId =
                (_state.value.eventChoiceDecision as? LabyrinthEventChoiceDecision.Select)?.choiceId
        }
    }

    fun onEventTapDispatched(kind: LabyrinthPostEntryActionKind): Unit {
        if (kind == LabyrinthPostEntryActionKind.SELECT_EVENT || kind == LabyrinthPostEntryActionKind.ADVANCE_EVENT_ANIMATION) {
            eventActionAttempts++
        }
    }

    fun resetEventRosterState() {
        eventFreeRoleSelectedCharacterIds.clear()
        eventFreeRoleLastSelectAt = Long.MIN_VALUE
        eventFreeRoleObservedSelectedCount = 0
        resetEventFreeRoleProgress()
    }

    fun onEventPageChanged(pageState: LabyrinthEntryPageState) {
        if (pageState != LabyrinthEntryPageState.EVENT_CHOICE) {
            _state.value = _state.value.copy(eventChoiceDecision = null)
            resetEventChoiceCommit()
        }
    }

    fun prepareEventEntry() {
        eventFreeRoleSelectedCharacterIds.clear()
        eventFreeRoleLastSelectAt = Long.MIN_VALUE
        eventFreeRoleObservedSelectedCount = 0
        resetEventFreeRoleProgress()
    }

    fun planEventAnimationPage(result: LabyrinthEntryFrameResult, frameWidth: Int, frameHeight: Int, characterAcquisitionActive: Boolean): LabyrinthPostEntryTapPlan? {
        return when (result.observation.state) {
            LabyrinthEntryPageState.EVENT_ANIMATION -> when {
                labyrinthHasBattleControls(result) -> null
                characterAcquisitionActive ->
                    LabyrinthPostEntryTapPlan(
                        LabyrinthPostEntryActionKind.ADVANCE_CHARACTER_ACQUISITION,
                        "推进角色获得动画",
                        LabyrinthFallbackTap.CENTER.rect(frameWidth, frameHeight),
                    )
                else -> LabyrinthPostEntryTapPlan(
                    LabyrinthPostEntryActionKind.ADVANCE_EVENT_ANIMATION,
                    "推进事件动画",
                    anchorRect(result, EntryAnchorId.EVENT_ANIMATION_SKIP)
                        ?: LabyrinthFallbackTap.CENTER.rect(frameWidth, frameHeight),
                )
            }

            else -> null
        }
    }
}
