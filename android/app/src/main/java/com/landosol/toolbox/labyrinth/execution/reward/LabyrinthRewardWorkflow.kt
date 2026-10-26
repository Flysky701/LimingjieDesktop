package com.landosol.toolbox.labyrinth.execution.reward

import com.landosol.toolbox.labyrinth.*
import android.util.Log
import com.landosol.toolbox.automation.AutomationSessionId
import com.landosol.toolbox.labyrinth.vision.EntryAnchorId
import com.landosol.toolbox.labyrinth.vision.EntryPixelRect
import com.landosol.toolbox.labyrinth.vision.LabyrinthEntryFrameResult
import com.landosol.toolbox.labyrinth.vision.LabyrinthCharacterMatch
import com.landosol.toolbox.labyrinth.vision.LabyrinthEntryPageState
import com.landosol.toolbox.labyrinth.vision.LabyrinthRelicMatch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.landosol.toolbox.labyrinth.LabyrinthExecutionLimits.MAX_SELECTION_COMMIT_ATTEMPTS
import com.landosol.toolbox.labyrinth.LabyrinthExecutionLimits.POST_ENTRY_SELECTION_MIN_SCORE
import com.landosol.toolbox.labyrinth.LabyrinthExecutionLimits.POST_ENTRY_STABLE_FRAMES

internal interface LabyrinthRewardPort {
    val _state: MutableStateFlow<LabyrinthEntryRecognitionSessionState>
    var activeNodeType: Int?
    val activeRunAccountId: Long?
    val activeSessionId: AutomationSessionId?
    var characterAcquisitionActive: Boolean
    var characterAcquisitionClicks: Int
    var characterAcquisitionStartedAt: Long
    val clock: () -> Long
    var committedRoleRewardSelectionSignature: String?
    var lastCharacterAcquisitionActionAt: Long
    var lastObservedRoleRewardSelectionSignature: String?
    var pendingAcquiredCharacterId: String?
    val pendingAcquiredCharacterIds: MutableSet<String>
    var pendingRelicSelection: LabyrinthRelicMatch?
    val persistenceMutex: Mutex
    val postBossStage: LabyrinthPostBossStage
    var postEntryAttempts: Int
    val postEntryStableFrames: Int
    val relicAcquisitionGate: LabyrinthRelicAcquisitionGate
    var relicChoiceCommitAttempts: Int
    var relicChoiceCommitted: Boolean
    var relicChoiceCommittedAt: Long
    val relicChoicePolicy: LabyrinthRelicChoicePolicy
    var relicFocusMark: LabyrinthRelicMark?
    val relicMarkStackCalibration: Map<LabyrinthRelicMark, Int>
    val relicStackLedger: LabyrinthRelicStackLedger
    var roleRewardBatchActive: Boolean
    var roleRewardChoiceCommitted: Boolean
    val roleRewardChoicePlanner: LabyrinthRoleRewardChoicePlanner?
    val roleRewardChoiceUnavailableReason: String?
    var roleRewardDecisionCacheKey: String?
    var roleRewardJoinedSequenceStarted: Boolean
    val runStateStore: LabyrinthRunStateStore?
    var skipRoleRewardJoinedRecognition: Boolean
    var waitingForManualRoleSelection: Boolean
    fun anchorRect(result: LabyrinthEntryFrameResult, anchorId: String): EntryPixelRect?
    fun finishFromPlanner(sessionId: AutomationSessionId, reason: String): Unit
    fun nodeLog(message: String, warning: Boolean = false): Unit
}

/** 管理角色和遗物选择、连续奖励批次及获得确认。 */
internal class LabyrinthRewardWorkflow(private val port: LabyrinthRewardPort) : LabyrinthRewardPort by port {
    /**
     * Reconcile history-derived mark stacks with the value printed by the game itself.
     * Multiple cards with the same mark must agree before that mark is accepted as calibration.
     */
    fun refreshRelicStackCalibration(result: LabyrinthEntryFrameResult) {
        result.relicDetailObservation?.let { detail ->
            if (detail.titleConfirmed) detail.entries.forEach { (mark, value) ->
                relicStackLedger.observe(mark, value, LabyrinthRelicStackLedger.Source.DETAIL,
                    mark.name, detail.evidenceId)
            }
        }
        result.relicChoiceSelection?.choices.orEmpty().filter { it.recognized }
            .groupBy { LabyrinthRelicMark.fromLabel(it.attribute) }.forEach { (mark, choices) ->
            if (mark == null) return@forEach
            val conflicting = choices.mapNotNull { it.currentMarkStacks }.distinct().size > 1
            choices.forEach { choice ->
                relicStackLedger.observe(mark, choice.currentMarkStacks.takeUnless { conflicting },
                    LabyrinthRelicStackLedger.Source.CHOICE, choice.slotId, choice.currentMarkStacksEvidenceId)
            }
        }
        result.nodeRelicStackObservation?.entries.orEmpty().forEach { entry ->
            val mark = LabyrinthRelicMark.fromLabel(entry.attribute) ?: return@forEach
            relicStackLedger.observe(mark, entry.stacks, LabyrinthRelicStackLedger.Source.MAP,
                entry.slotId, entry.evidenceId)
        }
    }

    fun advanceRelicStackCalibrationAfterAcquisition(relic: LabyrinthRelicMatch) {
        val mark = LabyrinthRelicMark.fromLabel(relic.attribute) ?: return
        val bonus = relic.attributeBonus?.trim()?.toIntOrNull()?.takeIf { it > 0 } ?: return
        val fallback = relicChoicePolicy.markStacks(_state.value.observedRelics).getValue(mark)
        relicStackLedger.acquired(mark, bonus, fallback)
    }

    /** Evaluates role reward identity/choice in both dry-run and live sessions. */
    fun refreshRoleRewardChoiceDecision(
        sessionId: AutomationSessionId,
        result: LabyrinthEntryFrameResult,
        frameWidth: Int,
        frameHeight: Int,
    ) {
        if (activeSessionId != sessionId) return
        if (!labyrinthIsRoleRewardPage(result)) {
            roleRewardDecisionCacheKey = null
            if (_state.value.roleRewardChoiceDecision != null) {
                _state.value = _state.value.copy(roleRewardChoiceDecision = null)
            }
            return
        }
        if (committedRoleRewardSelectionSignature != null &&
            labyrinthRoleRewardSelectionSignature(result) == committedRoleRewardSelectionSignature
        ) return
        val planner = roleRewardChoicePlanner
        val decision = if (planner == null) {
            LabyrinthRoleRewardChoiceDecision.Wait(
                roleRewardChoiceUnavailableReason ?: "角色三选一决策资料未加载",
            )
        } else {
            val defenseStacks = relicChoicePolicy.markStacks(
                _state.value.observedRelics,
                relicMarkStackCalibration,
            )
                .getValue(LabyrinthRelicMark.DEFENSE)
            val pendingSelectedIds = synchronized(pendingAcquiredCharacterIds) {
                pendingAcquiredCharacterIds.toSet()
            }
            val acquiredIds = _state.value.joinedCharacters.map(LabyrinthJoinedCharacter::characterId)
                .plus(pendingSelectedIds).toSet()
            val cacheKey = buildString {
                append("$frameWidth/$frameHeight/$defenseStacks/")
                append(acquiredIds.sorted().joinToString(","))
                result.characterMatches.forEach {
                    append("|${it.slotId}:${it.characterId}:${it.displayName}:${it.screenRect}:")
                    append(labyrinthRoleRewardIdentityIsActionSafe(it))
                }
            }
            if (cacheKey == roleRewardDecisionCacheKey &&
                _state.value.roleRewardChoiceDecision is LabyrinthRoleRewardChoiceDecision.Select
            ) return
            if (cacheKey != roleRewardDecisionCacheKey) {
                val collision = result.characterMatches.mapNotNull { it.characterId }
                    .map(::canonicalLabyrinthRoleId)
                    .filter(acquiredIds::contains)
                    .distinct()
                if (collision.isNotEmpty()) {
                    nodeLog("role-reward-roster-conflict candidateIds=${collision.joinToString()} " +
                        "action=rank-visible-candidates", warning = true)
                }
            }
            roleRewardDecisionCacheKey = cacheKey
            val decisionStarted = System.nanoTime()
            planner.decide(
                candidates = result.characterMatches,
                acquiredCharacterIds = acquiredIds,
                context = LabyrinthRoleDecisionContext(defenseMarkStacks = defenseStacks),
                frameWidth = frameWidth,
                frameHeight = frameHeight,
            ).also {
                runCatching {
                    Log.d("LabyrinthEntry", "roleRewardDecisionMillis=${(System.nanoTime() - decisionStarted) / 1_000_000}")
                }
            }
        }
        val message = when (decision) {
            is LabyrinthRoleRewardChoiceDecision.Select -> buildString {
                append("角色三选一建议：${decision.displayName}")
                if (!decision.actionSafe) append("（仅推荐，不自动点击）")
                if (decision.explanation.isNotEmpty()) {
                    append("；${decision.explanation.joinToString("；")}")
                }
                decision.safetyNote?.let { append("；$it") }
            }
            is LabyrinthRoleRewardChoiceDecision.Wait -> decision.reason
        }
        _state.value = _state.value.copy(
            roleRewardChoiceDecision = decision,
            message = message,
        )
    }

    fun postEntryIsRoleRewardPage(result: LabyrinthEntryFrameResult): Boolean {
        return labyrinthIsRoleRewardPage(result, POST_ENTRY_SELECTION_MIN_SCORE)
    }

    fun clearCharacterAcquisitionContext() {
        characterAcquisitionActive = false
        characterAcquisitionClicks = 0
        characterAcquisitionStartedAt = Long.MIN_VALUE
        lastCharacterAcquisitionActionAt = Long.MIN_VALUE
    }

    fun clearRoleRewardBatchTracking() {
        skipRoleRewardJoinedRecognition = false
        roleRewardDecisionCacheKey = null
        roleRewardBatchActive = false
        roleRewardChoiceCommitted = false
        committedRoleRewardSelectionSignature = null
        roleRewardJoinedSequenceStarted = false
        lastObservedRoleRewardSelectionSignature = null
        waitingForManualRoleSelection = false
        synchronized(pendingAcquiredCharacterIds) {
            pendingAcquiredCharacterIds.clear()
            pendingAcquiredCharacterId = null
        }
    }

    fun planRelicChoice(sessionId: AutomationSessionId, result: LabyrinthEntryFrameResult, timestampMillis: Long): LabyrinthPostEntryTapPlan? =
run {
                if (relicChoiceCommitted &&
                    !labyrinthSelectionCommitExpired(relicChoiceCommittedAt, timestampMillis)
                ) {
                    null
                } else if (relicChoiceCommitted &&
                    relicChoiceCommitAttempts >= MAX_SELECTION_COMMIT_ATTEMPTS
                ) {
                    finishFromPlanner(
                        sessionId,
                        "遗物选择已点击${relicChoiceCommitAttempts}次仍停留在遗物页；停止以免反复空点",
                    )
                    null
                } else {
                    if (relicChoiceCommitted) {
                        // The tap was sent and the page never moved on. The latch used to clear
                        // only on a page change, and the page was the thing that never changed, so
                        // the run sat until the page-stall watchdog ended it (2026-10-12 bundles:
                        // 120 s on one RELIC_CHOICE page with actionCount frozen). Re-arm and retry
                        // now that the tap is provably lost.
                        relicChoiceCommitted = false
                        relicChoiceCommittedAt = Long.MIN_VALUE
                        relicChoiceCommitAttempts++
                        pendingRelicSelection = null
                        nodeLog("relic-choice-retry attempts=$relicChoiceCommitAttempts", warning = true)
                    }
                    relicChoicePolicy.choose(
                        acquired = _state.value.observedRelics,
                        candidates = result.relicChoiceSelection?.choices.orEmpty(),
                        lockedFocus = relicFocusMark,
                        calibratedStacks = relicMarkStackCalibration,
                        currentArea = _state.value.routeProgress?.currentArea,
                    )?.let { decision ->
                        if (decision.phase != LabyrinthRelicChoicePhase.BUILD_BASELINE) {
                            relicFocusMark = decision.focusMark
                        }
                        pendingRelicSelection = decision.choice
                        LabyrinthPostEntryTapPlan(
                            LabyrinthPostEntryActionKind.SELECT_RELIC,
                            "选择遗物：${decision.choice.displayName}；${decision.reason}",
                            decision.choice.selectionButtonRect,
                        )
                    }
                }
            }

    fun updateRewardPageProgress(sessionId: AutomationSessionId, result: LabyrinthEntryFrameResult, previousPostEntryState: LabyrinthEntryPageState?, timestampMillis: Long) {
        val pageState = result.observation.state
        val roleRewardPage = postEntryIsRoleRewardPage(result)
        val roleRewardSelectionSignature = if (roleRewardPage) labyrinthRoleRewardSelectionSignature(result) else null
        if (roleRewardPage) {
            if (!roleRewardBatchActive) roleRewardJoinedSequenceStarted = false
            roleRewardBatchActive = true
            // Choice rounds come first. Any generic reward fallback inherited from the Boss/
            // event result must stop here so an inter-choice UNKNOWN transition is never tapped.
            if (!roleRewardJoinedSequenceStarted && characterAcquisitionActive) {
                clearCharacterAcquisitionContext()
            }
            // The page enum can remain INITIAL_CHARACTER_SELECTION across several reward rounds.
            // Only the candidate-set signature tells us whether this exact round was already
            // clicked. A new candidate set immediately re-arms automatic selection.
            roleRewardChoiceCommitted = roleRewardSelectionSignature != null &&
                roleRewardSelectionSignature == committedRoleRewardSelectionSignature
        }

        // The role-card selection is human input, but the following portrait/召唤 animation is
        // not. Some event/link/reward paths skip a separately recognizable role-selection page
        // and expose only UNKNOWN frames, so arm the bounded safe-area fallback from the last
        // known reward page instead of waiting forever for a role-selection transition.
        if (!characterAcquisitionActive &&
            !(roleRewardBatchActive && !roleRewardJoinedSequenceStarted) &&
            shouldArmCharacterAcquisitionFallback(
                previousPage = previousPostEntryState,
                currentPage = pageState,
                activeNodeType = activeNodeType,
            )
        ) {
            characterAcquisitionActive = true
            waitingForManualRoleSelection = false
            characterAcquisitionClicks = 0
            characterAcquisitionStartedAt = timestampMillis
            lastCharacterAcquisitionActionAt = Long.MIN_VALUE
            postEntryAttempts = 0
            if (activeSessionId == sessionId) {
                _state.value = _state.value.copy(message = "已进入角色/奖励动画兜底，连续点击安全区域推进")
            }
        }

        // A multi-role settlement is one batch: several choice pages can be followed by several
        // CHARACTER_JOINED popups. Do not clear the acquisition driver between those pages.
        val keepsRoleRewardBatch = labyrinthKeepsRoleRewardBatchOnPage(pageState, roleRewardPage)
        val keepsAcquisitionPage = pageState in setOf(
            LabyrinthEntryPageState.UNKNOWN,
            LabyrinthEntryPageState.EVENT_ANIMATION,
            LabyrinthEntryPageState.CHARACTER_JOINED,
            LabyrinthEntryPageState.ITEM_REWARD,
        ) || (roleRewardBatchActive && roleRewardPage)
        if (characterAcquisitionActive && !keepsAcquisitionPage) {
            clearCharacterAcquisitionContext()
        }
        if (roleRewardBatchActive && !keepsRoleRewardBatch) {
            clearRoleRewardBatchTracking()
        }

    }

    fun captureRoleRewardTap(roleRewardSelectedCharacterId: String?): LabyrinthRoleRewardTapContext {
        val selectedReward = (_state.value.roleRewardChoiceDecision as? LabyrinthRoleRewardChoiceDecision.Select)
            ?.takeIf { it.characterId == roleRewardSelectedCharacterId && it.actionSafe }
        val selectedRewardAccountId = activeRunAccountId
        val selectedRewardMatch = _state.value.lastResult?.characterMatches
            ?.firstOrNull { it.characterId == selectedReward?.characterId && it.trusted }
        return LabyrinthRoleRewardTapContext(selectedReward, selectedRewardAccountId, selectedRewardMatch)
    }

    suspend fun onRewardTapExecuted(sessionId: AutomationSessionId, kind: LabyrinthPostEntryActionKind, roleRewardSelectionSignature: String?, roleRewardSelectedCharacterId: String?, context: LabyrinthRoleRewardTapContext, rect: EntryPixelRect): Unit {
        val selectedReward = context.decision
        val selectedRewardAccountId = context.accountId
        val selectedRewardMatch = context.recognizedCharacter
        if (kind == LabyrinthPostEntryActionKind.SELECT_RELIC) {
            relicChoiceCommitted = true
            relicChoiceCommittedAt = clock()
        }
        if (kind == LabyrinthPostEntryActionKind.SELECT_ROLE_REWARD) {
            roleRewardChoiceCommitted = true
            committedRoleRewardSelectionSignature = roleRewardSelectionSignature
            roleRewardBatchActive = true
            roleRewardSelectedCharacterId?.let { selectedId ->
                    synchronized(pendingAcquiredCharacterIds) {
                        pendingAcquiredCharacterIds += selectedId
                        pendingAcquiredCharacterId = pendingAcquiredCharacterIds.firstOrNull()
                    }
            }
            selectedReward?.let { selected ->
                val match = selectedRewardMatch ?: LabyrinthCharacterMatch(
                    slotId = "role_reward_selected", characterId = selected.characterId,
                    displayName = selected.displayName, confidence = ROLE_REWARD_STRONG_ICON_CONFIDENCE,
                    screenRect = rect, trusted = true,
                )
                val persisted = runCatching {
                    persistenceMutex.withLock {
                        runStateStore?.recordPage(
                            accountId = selectedRewardAccountId,
                            page = LabyrinthEntryPageState.INITIAL_CHARACTER_SELECTION.name,
                            characters = listOf(match), nowMillis = clock(),
                        )
                    }
                }.onFailure {
                    val failure = it
                    runCatching {
                        Log.e("LabyrinthEntry", "Selected role persistence failed; retain joined recognition", failure)
                    }
                }
                if (activeSessionId == sessionId) {
                    _state.value = _state.value.copy(
                        joinedCharacters = persisted.getOrNull()?.joinedCharacters ?: LabyrinthRosterMerger.merge(
                            _state.value.joinedCharacters, listOf(match), clock(),
                        ),
                    )
                    skipRoleRewardJoinedRecognition = persisted.isSuccess
                    synchronized(pendingAcquiredCharacterIds) {
                        pendingAcquiredCharacterIds.remove(selected.characterId)
                        pendingAcquiredCharacterId = pendingAcquiredCharacterIds.firstOrNull()
                    }
                }
            }
        }
    }

    fun onRewardTapDispatched(kind: LabyrinthPostEntryActionKind, timestampMillis: Long): Unit {
        if (kind == LabyrinthPostEntryActionKind.ADVANCE_CHARACTER_ACQUISITION) {
            characterAcquisitionClicks++
            lastCharacterAcquisitionActionAt = timestampMillis
        }
    }

    fun onRewardPageClosed(kind: LabyrinthPostEntryActionKind, timestampMillis: Long): Unit {
        when (kind) {
            LabyrinthPostEntryActionKind.CLOSE_CHARACTER_JOINED -> if (!roleRewardBatchActive) {
                clearCharacterAcquisitionContext()
            } else {
                // More joined popups may follow immediately with the same page state.
                // Keep the batch/fallback alive and refresh only its progress timeout.
                characterAcquisitionStartedAt = timestampMillis
            }
            LabyrinthPostEntryActionKind.CLOSE_ITEM_REWARD -> if (characterAcquisitionActive && !roleRewardBatchActive) {
                clearCharacterAcquisitionContext()
            }
            else -> Unit
        }
    }

    fun observeRelicAcquisition(result: LabyrinthEntryFrameResult): LabyrinthRelicMatch? {
        val confirmedRelic = if (relicAcquisitionGate.observe(relicChoiceCommitted, result.observation.state)) {
            pendingRelicSelection.also {
                pendingRelicSelection = null
                relicChoiceCommitted = false
                relicChoiceCommittedAt = Long.MIN_VALUE
                relicChoiceCommitAttempts = 0
                relicAcquisitionGate.reset()
            }
        } else {
            null
        }
        return confirmedRelic
    }

    fun resetRewardRuntimeState() {
        pendingRelicSelection = null
        relicChoiceCommitted = false
        relicChoiceCommittedAt = Long.MIN_VALUE
        relicChoiceCommitAttempts = 0
        relicFocusMark = null
        relicStackLedger.clear()
        clearCharacterAcquisitionContext()
        clearRoleRewardBatchTracking()
    }

    fun onRewardPageChanged(pageState: LabyrinthEntryPageState) {
        if (pageState != LabyrinthEntryPageState.INITIAL_CHARACTER_SELECTION) {
            _state.value = _state.value.copy(roleRewardChoiceDecision = null)
        }
    }

    fun resetRewardOnStart() {
        pendingAcquiredCharacterId = null
        synchronized(pendingAcquiredCharacterIds) { pendingAcquiredCharacterIds.clear() }
        skipRoleRewardJoinedRecognition = false
        roleRewardBatchActive = false
        committedRoleRewardSelectionSignature = null
        roleRewardJoinedSequenceStarted = false
        lastObservedRoleRewardSelectionSignature = null
    }

    fun resetRewardOnStop() {
        pendingAcquiredCharacterId = null
        synchronized(pendingAcquiredCharacterIds) { pendingAcquiredCharacterIds.clear() }
        skipRoleRewardJoinedRecognition = false
        roleRewardBatchActive = false
        committedRoleRewardSelectionSignature = null
        roleRewardJoinedSequenceStarted = false
        lastObservedRoleRewardSelectionSignature = null
    }

    fun resetRelicsOnStart() {
        pendingRelicSelection = null
        relicChoiceCommitted = false
        roleRewardChoiceCommitted = false
        relicFocusMark = null
        relicStackLedger.clear()
        relicStackLedger.clearPending()
    }

    fun planRewardPage(result: LabyrinthEntryFrameResult, frameWidth: Int, frameHeight: Int): LabyrinthPostEntryTapPlan? {
        return when (result.observation.state) {
            LabyrinthEntryPageState.CHARACTER_JOINED ->
                LabyrinthPostEntryTapPlan(
                    LabyrinthPostEntryActionKind.CLOSE_CHARACTER_JOINED,
                    "关闭角色加入结果",
                    anchorRect(result, EntryAnchorId.JOINED_CLOSE)
                        ?: anchorRect(result, EntryAnchorId.JOINED_CLOSE_STANDARD)
                        ?: LabyrinthFallbackTap.MODAL_CLOSE.rect(frameWidth, frameHeight),
                )

            LabyrinthEntryPageState.ITEM_REWARD -> when {
                postBossStage == LabyrinthPostBossStage.FINAL_ITEM_REWARD ->
                    LabyrinthPostEntryTapPlan(
                        LabyrinthPostEntryActionKind.CLOSE_FINAL_ITEM,
                        "关闭最终获得道具",
                        anchorRect(result, EntryAnchorId.ITEM_REWARD_CLOSE)
                            ?: LabyrinthFallbackTap.MODAL_CLOSE.rect(frameWidth, frameHeight),
                    )
                else ->
                    LabyrinthPostEntryTapPlan(
                        LabyrinthPostEntryActionKind.CLOSE_ITEM_REWARD,
                        "关闭获得道具界面",
                        // 遗物效果结果 is a short centred dialog whose 关闭 sits ~190 reference
                        // pixels above the full-height one, so the generic MODAL_CLOSE fallback
                        // would miss it entirely. Its own anchor is tried first and only ever
                        // matches while that dialog is the one on screen.
                        anchorRect(result, EntryAnchorId.RELIC_EFFECT_RESULT_CLOSE)
                            ?: anchorRect(result, EntryAnchorId.ITEM_REWARD_CLOSE)
                            ?: LabyrinthFallbackTap.MODAL_CLOSE.rect(frameWidth, frameHeight),
                    )
            }

            LabyrinthEntryPageState.LINK_CHOICE ->
                (result.linkChoiceSelection?.preferredChoice
                    ?: result.linkChoiceSelection?.choices?.firstOrNull())?.let { choice ->
                    LabyrinthPostEntryTapPlan(
                        LabyrinthPostEntryActionKind.SELECT_LINK_IMPRINT,
                        "选择连结印记：${choice.element.label}",
                        choice.selectionButtonRect,
                    )
                }

            else -> null
        }
    }

    fun planRoleRewardPage(result: LabyrinthEntryFrameResult): LabyrinthPageActionProposal {
        val pageState = result.observation.state
        var roleRewardWaitReason: String? = null
        val roleRewardPlan = if (
            pageState == LabyrinthEntryPageState.INITIAL_CHARACTER_SELECTION &&
            postEntryIsRoleRewardPage(result) &&
            !roleRewardChoiceCommitted
        ) {
            when (val decision = _state.value.roleRewardChoiceDecision) {
                is LabyrinthRoleRewardChoiceDecision.Select -> {
                    if (decision.actionSafe) {
                        LabyrinthPostEntryTapPlan(
                            LabyrinthPostEntryActionKind.SELECT_ROLE_REWARD,
                            "选择角色：${decision.displayName}；${decision.explanation.joinToString("；")}",
                            decision.buttonRect,
                        )
                    } else {
                        roleRewardWaitReason = decision.safetyNote ?: "当前角色推荐仅供参考，不满足自动点击安全条件"
                        null
                    }
                }
                is LabyrinthRoleRewardChoiceDecision.Wait -> {
                    roleRewardWaitReason = decision.reason
                    null
                }
                null -> {
                    roleRewardWaitReason = "角色三选一决策尚未生成"
                    null
                }
            }
        } else {
            null
        }
        return LabyrinthPageActionProposal(roleRewardPlan, roleRewardWaitReason)
    }

    fun updateRewardAnimationProgress(sessionId: AutomationSessionId, result: LabyrinthEntryFrameResult, timestampMillis: Long): Unit {
        val pageState = result.observation.state
        val roleRewardPage = postEntryIsRoleRewardPage(result)
        // Later role rewards are human card selections. The common "选择" template is visible
        // before the user chooses anything, so it must never be used as an automatic tap target.
        // We only arm the automatic animation driver after the page itself changes, which proves
        // that the user has completed the selection.
        if (roleRewardPage) {
            waitingForManualRoleSelection = true
            if (postEntryStableFrames == POST_ENTRY_STABLE_FRAMES && activeSessionId == sessionId) {
                _state.value = _state.value.copy(message = "角色奖励批次：继续完成当前三选一；全部选完后统一处理角色加入弹窗")
            }
        } else if (waitingForManualRoleSelection &&
            pageState != LabyrinthEntryPageState.INITIAL_CHARACTER_SELECTION
        ) {
            waitingForManualRoleSelection = false
            if (roleRewardBatchActive && activeSessionId == sessionId) {
                _state.value = _state.value.copy(
                    message = "角色奖励批次选择过渡中；不盲点，等待下一次三选一或首个角色加入弹窗",
                )
            }
        }

        if (pageState == LabyrinthEntryPageState.CHARACTER_JOINED && roleRewardBatchActive) {
            roleRewardJoinedSequenceStarted = true
            waitingForManualRoleSelection = false
            if (!characterAcquisitionActive) {
                characterAcquisitionActive = true
                characterAcquisitionClicks = 0
                characterAcquisitionStartedAt = timestampMillis
                lastCharacterAcquisitionActionAt = Long.MIN_VALUE
                postEntryAttempts = 0
            }
        }

    }

    fun onRewardTapNotForeground(kind: LabyrinthPostEntryActionKind): Unit {
        if (kind == LabyrinthPostEntryActionKind.ADVANCE_CHARACTER_ACQUISITION) {
            characterAcquisitionClicks = (characterAcquisitionClicks - 1).coerceAtLeast(0)
        }
    }

    fun acknowledgeAcquiredCharacters(characters: List<LabyrinthCharacterMatch>): Unit {
        if (characters.isNotEmpty()) {
            val confirmedIds = characters.mapNotNull { it.characterId }
                .map(::canonicalLabyrinthRoleId)
                .toSet()
            synchronized(pendingAcquiredCharacterIds) {
                pendingAcquiredCharacterIds.removeAll { pendingId ->
                    canonicalLabyrinthRoleId(pendingId) in confirmedIds
                }
                pendingAcquiredCharacterId = pendingAcquiredCharacterIds.firstOrNull()
            }
        }
    }

    fun resetObservedOpeningRewards(): Unit {
        relicFocusMark = null
        relicStackLedger.clear()
        pendingRelicSelection = null
        relicChoiceCommitted = false
        relicChoiceCommittedAt = Long.MIN_VALUE
        relicChoiceCommitAttempts = 0
    }
}
