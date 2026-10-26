package com.landosol.toolbox.labyrinth.execution.battle

import com.landosol.toolbox.labyrinth.*
import com.landosol.toolbox.automation.AutomationActionResult
import com.landosol.toolbox.automation.AutomationSessionId
import com.landosol.toolbox.automation.SessionBoundActionExecutor
import com.landosol.toolbox.automation.AutomationAction
import com.landosol.toolbox.automation.ScreenPoint
import com.landosol.toolbox.automation.accessibility.GAME_NOT_FOREGROUND_REASON
import com.landosol.toolbox.labyrinth.vision.EntryAnchorId
import com.landosol.toolbox.labyrinth.vision.LabyrinthBattleElementFilter
import com.landosol.toolbox.labyrinth.vision.LabyrinthEntryFrameResult
import com.landosol.toolbox.labyrinth.vision.LabyrinthEntryPageState
import com.landosol.toolbox.labyrinth.vision.LabyrinthBattleTeamRecognitionState
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import com.landosol.toolbox.labyrinth.LabyrinthExecutionLimits.EFFECTIVE_SCAN_CONTEXT_IDS
import com.landosol.toolbox.labyrinth.LabyrinthExecutionLimits.EFFECTIVE_SCAN_SCROLL_DURATION_MILLIS
import com.landosol.toolbox.labyrinth.LabyrinthExecutionLimits.EFFECTIVE_SCAN_UNSAFE_SETTLE_MILLIS
import com.landosol.toolbox.labyrinth.LabyrinthExecutionLimits.MAX_EFFECTIVE_SCAN_SCROLL_ACTIONS

internal interface LabyrinthBattleRosterPort {
    val _state: MutableStateFlow<LabyrinthEntryRecognitionSessionState>
    val actionExecutor: SessionBoundActionExecutor?
    val actionInFlight: AtomicBoolean
    val activeSessionId: AutomationSessionId?
    val battleTeamRecommendationUnavailableReason: String?
    val bossEditorPreparationStage: LabyrinthBossEditorPreparationStage
    val combatContext: LabyrinthCombatContext?
    var effectiveCharacterScanRosterStamp: Long
    var effectiveCharacterScanScrollActions: Int
    var effectiveCharacterScanSkippedUnsafe: Boolean
    var effectiveCharacterScanStage: LabyrinthEffectiveCharacterScanStage
    var effectiveCharacterUnsafeStartedAt: Long
    val effectiveExCharacterIds: MutableSet<String>
    val effectiveRosterSearch: LabyrinthBattleRosterSearch
    var lastBattleTeamRecommendationKey: String?
    var lastBattleTeamSelectionPlanLog: String?
    var lastEffectiveCharacterScanActionAt: Long
    fun beginGuardedAction(): Boolean
    fun launchGuardedAction(block: suspend () -> Unit): Unit
    fun nodeLog(message: String, warning: Boolean = false): Unit
    fun publishOverlay(sessionId: AutomationSessionId): Unit
    fun publishWaitingForGameForeground(sessionId: AutomationSessionId): Unit
    suspend fun stopFromRunLogic(reason: String): Boolean
    fun traceAction(label: String): Unit
}

/** 扫描有效角色、维护查找进度与不可见角色排除条件。 */
internal class LabyrinthBattleRosterWorkflow(private val port: LabyrinthBattleRosterPort) : LabyrinthBattleRosterPort by port {
    /**
     * EX/Boss-only discovery pass for the game's official “有效效果” filter.
     *
     * The filter is useful encounter-specific evidence, but it is not allowed to become a blind
     * team selector. We first switch to the filter, rewind/scan the complete roster with the same
     * visual scrollbar gates used by normal auto-team search, record only safely identified cards,
     * then restore “全部”. Only after that visual round-trip is confirmed may team scoring run.
     */
    fun refreshEffectiveCharacterScan(
        sessionId: AutomationSessionId,
        result: LabyrinthEntryFrameResult,
        frameWidth: Int,
        frameHeight: Int,
        timestampMillis: Long,
    ) {
        if (activeSessionId != sessionId || _state.value.dryRun) return
        if (result.observation.state != LabyrinthEntryPageState.BATTLE_TEAM_SELECTION) return
        if (combatContext?.kind !in setOf(LabyrinthCombatKind.EX, LabyrinthCombatKind.BOSS)) return
        if (combatContext?.kind == LabyrinthCombatKind.BOSS &&
            bossEditorPreparationStage != LabyrinthBossEditorPreparationStage.COMPLETE
        ) return
        val observation = result.battleTeamSelection ?: return

        // A completed sweep survives the EX -> Boss hand-off of one encounter, but only while the
        // owned roster is unchanged, because the filtered list only shows roles this account owns.
        if (effectiveCharacterScanStage == LabyrinthEffectiveCharacterScanStage.COMPLETE &&
            effectiveCharacterScanRosterStamp != currentEffectiveScanRosterStamp()
        ) {
            effectiveCharacterScanStage = LabyrinthEffectiveCharacterScanStage.IDLE
        }
        if (effectiveCharacterScanStage == LabyrinthEffectiveCharacterScanStage.IDLE) {
            synchronized(effectiveExCharacterIds) { effectiveExCharacterIds.clear() }
            effectiveRosterSearch.reset()
            effectiveCharacterScanScrollActions = 0
            effectiveCharacterUnsafeStartedAt = Long.MIN_VALUE
            effectiveCharacterScanSkippedUnsafe = false
            effectiveCharacterScanStage = LabyrinthEffectiveCharacterScanStage.SWITCH_TO_EFFECTIVE
        }
        if (effectiveCharacterScanStage == LabyrinthEffectiveCharacterScanStage.COMPLETE) return
        if (actionInFlight.get()) return
        if (observation.recognitionState != LabyrinthBattleTeamRecognitionState.STABLE) {
            _state.value = _state.value.copy(message = "有效效果扫描：等待编组页稳定")
            return
        }
        if (lastEffectiveCharacterScanActionAt != Long.MIN_VALUE &&
            timestampMillis - lastEffectiveCharacterScanActionAt < BATTLE_TEAM_ACTION_INTERVAL_MILLIS
        ) return

        when (effectiveCharacterScanStage) {
            LabyrinthEffectiveCharacterScanStage.IDLE -> Unit

            LabyrinthEffectiveCharacterScanStage.SWITCH_TO_EFFECTIVE -> {
                if (observation.currentFilter == LabyrinthBattleElementFilter.EFFECTIVE_EFFECT) {
                    effectiveRosterSearch.reset()
                    effectiveCharacterScanStage = LabyrinthEffectiveCharacterScanStage.SCANNING
                    _state.value = _state.value.copy(message = "有效效果扫描：已进入筛选，开始从顶部完整扫描")
                    return
                }
                val target = observation.filters.firstOrNull {
                    it.filter == LabyrinthBattleElementFilter.EFFECTIVE_EFFECT
                }
                if (target == null) {
                    _state.value = _state.value.copy(message = "有效效果扫描：尚未可靠识别“有效效果”筛选按钮")
                    return
                }
                dispatchEffectiveCharacterScanAction(
                    sessionId = sessionId,
                    label = "有效效果扫描：切换有效效果",
                    action = AutomationAction.Tap(
                        ScreenPoint(
                            target.screenRect.left + target.screenRect.width / 2f,
                            target.screenRect.top + target.screenRect.height / 2f,
                        ),
                    ),
                    timestampMillis = timestampMillis,
                )
            }

            LabyrinthEffectiveCharacterScanStage.SCANNING -> {
                if (observation.currentFilter != LabyrinthBattleElementFilter.EFFECTIVE_EFFECT) {
                    effectiveRosterSearch.reset()
                    effectiveCharacterScanStage = LabyrinthEffectiveCharacterScanStage.SWITCH_TO_EFFECTIVE
                    _state.value = _state.value.copy(message = "有效效果扫描：筛选状态变化，重新确认有效效果页")
                    return
                }
                if (
                    labyrinthEffectiveFilterIsEmpty(
                        observation = observation,
                        emptyNoticeScore = result.observation.anchorScores[EntryAnchorId.BATTLE_TEAM_ROSTER_EMPTY_NOTICE],
                    )
                ) {
                    // 「未搜索到该角色」: zero effective roles is a complete answer.
                    effectiveRosterSearch.reset()
                    effectiveCharacterScanStage = LabyrinthEffectiveCharacterScanStage.RETURN_TO_ALL
                    _state.value = _state.value.copy(
                        message = "有效效果筛选下没有角色（未搜索到该角色）；本局无对策角色，准备恢复全部筛选",
                    )
                    return
                }

                val frameEvidence = labyrinthEffectiveScanFrameEvidence(observation)
                val unsafe = frameEvidence.unsafeVisibleCharacters
                synchronized(effectiveExCharacterIds) {
                    frameEvidence.confirmedCharacterIds.forEach(effectiveExCharacterIds::add)
                }

                if (unsafe.isNotEmpty()) {
                    if (effectiveCharacterUnsafeStartedAt == Long.MIN_VALUE) {
                        effectiveCharacterUnsafeStartedAt = timestampMillis
                    }
                    if (timestampMillis - effectiveCharacterUnsafeStartedAt <
                        EFFECTIVE_SCAN_UNSAFE_SETTLE_MILLIS
                    ) {
                        _state.value = _state.value.copy(
                            message = "有效效果扫描：当前页有${unsafe.size}个角色身份未过安全线；短暂等待稳定后仍会继续滚动",
                        )
                        return
                    }
                    // An unresolved portrait must never be promoted into an effective-role id,
                    // but it also must not deadlock the whole Boss/EX preparation. Keep every
                    // independently confirmed role from this viewport and continue traversing the
                    // filtered roster. A later overlapping viewport may identify the same card
                    // reliably; otherwise the unresolved card simply contributes no bonus.
                    effectiveCharacterScanSkippedUnsafe = true
                } else {
                    effectiveCharacterUnsafeStartedAt = Long.MIN_VALUE
                }

                val searchDecision = effectiveRosterSearch.observe(
                    sessionId = sessionId,
                    observation = observation,
                    recommendedIds = EFFECTIVE_SCAN_CONTEXT_IDS,
                )
                when (searchDecision) {
                    LabyrinthBattleRosterSearchDecision.WAIT_FOR_SETTLE -> {
                        _state.value = _state.value.copy(message = "有效效果扫描：等待滚动回弹并稳定")
                    }
                    LabyrinthBattleRosterSearchDecision.EXHAUSTED -> {
                        effectiveCharacterScanStage = LabyrinthEffectiveCharacterScanStage.RETURN_TO_ALL
                        _state.value = _state.value.copy(
                            message = "有效效果扫描到底：共确认" +
                                synchronized(effectiveExCharacterIds) { effectiveExCharacterIds.size } +
                                "名" +
                                if (effectiveCharacterScanSkippedUnsafe) "；未可靠身份已跳过；准备恢复全部筛选"
                                else "；准备恢复全部筛选",
                        )
                    }
                    LabyrinthBattleRosterSearchDecision.UNAVAILABLE -> {
                        effectiveRosterSearch.reset()
                        effectiveCharacterScanScrollActions = 0
                        _state.value = _state.value.copy(message = "有效效果扫描暂时无法确认列表反馈；保持编组页并重新从顶部建立扫描")
                        return
                    }
                    LabyrinthBattleRosterSearchDecision.TO_TOP,
                    LabyrinthBattleRosterSearchDecision.NEXT_PAGE,
                    -> {
                        if (effectiveCharacterScanScrollActions >= MAX_EFFECTIVE_SCAN_SCROLL_ACTIONS) {
                            effectiveRosterSearch.reset()
                            effectiveCharacterScanScrollActions = 0
                            _state.value = _state.value.copy(message = "有效效果扫描达到单轮滚动上限；保持编组页并重新从顶部复扫")
                            return
                        }
                        val toTop = searchDecision == LabyrinthBattleRosterSearchDecision.TO_TOP
                        // Match the normal roster-search gesture: the Boss editor has a shorter
                        // viewport, so use a smaller drag there and keep at least one full card
                        // height overlapped between consecutive captures.  Without this, the old
                        // 0.67H -> 0.43H drag could move ~259 px on a 1080p frame while the Boss
                        // viewport is only ~386 px tall, leaving less than one ~195 px card of
                        // overlap and making a skipped row possible if the list moves as far as
                        // the gesture.
                        val upper = ScreenPoint(
                            frameWidth * 0.75f,
                            frameHeight * if (observation.bossTeamIndex != null) 0.50f else 0.43f,
                        )
                        val lower = ScreenPoint(frameWidth * 0.75f, frameHeight * 0.67f)
                        dispatchEffectiveCharacterScanAction(
                            sessionId = sessionId,
                            label = if (toTop) "有效效果扫描：返回列表顶部" else "有效效果扫描：查看下一页",
                            action = AutomationAction.Swipe(
                                start = if (toTop) upper else lower,
                                end = if (toTop) lower else upper,
                                durationMillis = EFFECTIVE_SCAN_SCROLL_DURATION_MILLIS,
                            ),
                            timestampMillis = timestampMillis,
                            scrollDirection = if (toTop) {
                                LabyrinthBattleRosterScrollDirection.TO_TOP
                            } else {
                                LabyrinthBattleRosterScrollDirection.NEXT_PAGE
                            },
                            scrollOriginPosition = observation.scrollbar.position
                                .takeIf { it.isFinite() && it in 0.0..1.0 } ?: 0.5,
                        )
                    }
                }
            }

            LabyrinthEffectiveCharacterScanStage.RETURN_TO_ALL -> {
                if (observation.currentFilter == LabyrinthBattleElementFilter.ALL) {
                    effectiveCharacterScanStage = LabyrinthEffectiveCharacterScanStage.COMPLETE
                    effectiveCharacterScanRosterStamp = currentEffectiveScanRosterStamp()
                    effectiveRosterSearch.reset()
                    lastBattleTeamRecommendationKey = null
                    _state.value = _state.value.copy(
                        message = "有效效果扫描完成：确认" +
                            synchronized(effectiveExCharacterIds) { effectiveExCharacterIds.size } +
                            "名对策角色；开始生成阵容",
                    )
                    return
                }
                val target = observation.filters.firstOrNull { it.filter == LabyrinthBattleElementFilter.ALL }
                if (target == null) {
                    _state.value = _state.value.copy(message = "有效效果扫描完成，等待可靠识别“全部”筛选按钮")
                    return
                }
                dispatchEffectiveCharacterScanAction(
                    sessionId = sessionId,
                    label = "有效效果扫描：恢复全部",
                    action = AutomationAction.Tap(
                        ScreenPoint(
                            target.screenRect.left + target.screenRect.width / 2f,
                            target.screenRect.top + target.screenRect.height / 2f,
                        ),
                    ),
                    timestampMillis = timestampMillis,
                )
            }

            LabyrinthEffectiveCharacterScanStage.COMPLETE -> Unit
        }
    }

    fun dispatchEffectiveCharacterScanAction(
        sessionId: AutomationSessionId,
        label: String,
        action: AutomationAction,
        timestampMillis: Long,
        scrollDirection: LabyrinthBattleRosterScrollDirection? = null,
        scrollOriginPosition: Double? = null,
    ) {
        if (!beginGuardedAction()) return
        traceAction(label)
        lastEffectiveCharacterScanActionAt = timestampMillis
        if (scrollDirection != null) effectiveCharacterScanScrollActions++
        launchGuardedAction {
            val executor = actionExecutor
            val result = if (executor == null) {
                AutomationActionResult.Rejected(action, "动作执行器不可用")
            } else {
                executor.execute(sessionId, action)
            }
            when (result) {
                is AutomationActionResult.Executed -> if (activeSessionId == sessionId) {
                    if (scrollDirection != null && scrollOriginPosition != null) {
                        effectiveRosterSearch.recordExecutedScroll(scrollDirection, scrollOriginPosition)
                        // Give each newly exposed viewport its own short recognition grace period.
                        effectiveCharacterUnsafeStartedAt = Long.MIN_VALUE
                    }
                    val current = _state.value
                    _state.value = current.copy(
                        actionCount = current.actionCount + 1,
                        lastActionLabel = label,
                        message = "已执行：$label；等待视觉确认",
                    )
                    publishOverlay(sessionId)
                }
                is AutomationActionResult.Rejected -> {
                    if (scrollDirection != null) {
                        effectiveCharacterScanScrollActions = (effectiveCharacterScanScrollActions - 1).coerceAtLeast(0)
                    }
                    if (result.reason == GAME_NOT_FOREGROUND_REASON) {
                        lastEffectiveCharacterScanActionAt = Long.MIN_VALUE
                        publishWaitingForGameForeground(sessionId)
                    } else {
                        stopFromRunLogic("有效效果扫描动作被拒绝：${result.reason}")
                    }
                }
                AutomationActionResult.StaleSession -> Unit
                AutomationActionResult.Paused -> Unit
                is AutomationActionResult.DryRun -> Unit
            }
        }
    }

    /**
     * Drops the official "有效效果" roster sweep, forcing a fresh filtered-list scan.
     *
     * The sweep belongs to the encounter, so an EX fight and the Boss of that same encounter share
     * one. [prepareNodeTransitionContext] keeps it across exactly that hand-off and clears it for
     * every other node entry; [refreshEffectiveCharacterScan] also drops it when the owned roster
     * changed in between. Boss contexts that are recovered without a node transition (settlement
     * or challenge-page fallbacks) never reach the hand-off branch and therefore always re-sweep,
     * which is the safe direction.
     */
    fun resetEffectiveCharacterScan() {
        synchronized(effectiveExCharacterIds) { effectiveExCharacterIds.clear() }
        effectiveRosterSearch.reset()
        effectiveCharacterScanStage = LabyrinthEffectiveCharacterScanStage.IDLE
        effectiveCharacterScanRosterStamp = Long.MIN_VALUE
        effectiveCharacterScanScrollActions = 0
        effectiveCharacterUnsafeStartedAt = Long.MIN_VALUE
        effectiveCharacterScanSkippedUnsafe = false
        lastEffectiveCharacterScanActionAt = Long.MIN_VALUE
        lastBattleTeamRecommendationKey = null
    }

    /** Owned-roster fingerprint used to decide whether a saved sweep is still valid. */
    fun currentEffectiveScanRosterStamp(): Long = labyrinthEffectiveScanRosterStamp(
        _state.value.joinedCharacters.map(LabyrinthJoinedCharacter::characterId),
    )

    /**
     * The run roster can contain characters this editor cannot show: a stale roster restored
     * from a previous run, or a recognition mistake at join time. Once every tab has been scanned
     * they are provably absent, so remove them from the roster the recommendation is built from.
     * Nothing is persisted: the store still holds the full list, this only shapes planning.
     */
    fun excludeUnfindableBattleCharacters(
        sessionId: AutomationSessionId,
        unfindable: Set<String>,
        missingLabel: String,
        scannedLabel: String,
    ) {
        if (activeSessionId != sessionId) return
        val current = _state.value
        val remaining = labyrinthRosterAfterUnfindableExclusion(current.joinedCharacters, unfindable)
        if (remaining == null) {
            _state.value = current.copy(
                battleTeamSelectionPlan = null,
                message = "已在${scannedLabel}筛选均扫到底仍未识别推荐角色：$missingLabel；未能从角色库排除，保持编组页",
            )
            return
        }
        lastBattleTeamRecommendationKey = null
        lastBattleTeamSelectionPlanLog = null
        _state.value = current.copy(
            joinedCharacters = remaining,
            battleTeamRecommendation = null,
            battleTeamSelectionPlan = null,
            battleTeamRecommendationUnavailableReason = null,
            message = "已在${scannedLabel}筛选均扫到底仍未识别推荐角色：$missingLabel；已从本局可用角色中排除，重新规划编组",
        )
        nodeLog(
            "battle-roster-exclude unfindable=${unfindable.joinToString(",")} " +
                "scanned=$scannedLabel remaining=${remaining.size}",
            warning = true,
        )
    }
}
