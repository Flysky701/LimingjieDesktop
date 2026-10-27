package com.landosol.toolbox.labyrinth.execution.battle

import com.landosol.toolbox.labyrinth.*
import android.util.Log
import com.landosol.toolbox.automation.AutomationSessionId
import com.landosol.toolbox.labyrinth.vision.EntryPixelRect
import com.landosol.toolbox.labyrinth.vision.LabyrinthEntryFrameResult
import com.landosol.toolbox.labyrinth.vision.LabyrinthEntryPageState
import com.landosol.toolbox.labyrinth.node.LabyrinthNodeTypes
import kotlinx.coroutines.flow.MutableStateFlow
import com.landosol.toolbox.labyrinth.LabyrinthExecutionLimits.EX_ENCOUNTER_DETAIL_TIMEOUT_MILLIS
import com.landosol.toolbox.labyrinth.LabyrinthExecutionLimits.EX_ENCOUNTER_OPEN_DETAIL_TIMEOUT_MILLIS
import com.landosol.toolbox.labyrinth.LabyrinthExecutionLimits.EX_ENCOUNTER_SINGLE_NAME_TIMEOUT_MILLIS
import com.landosol.toolbox.labyrinth.LabyrinthExecutionLimits.MAX_EX_SLOT3_PROBE_ATTEMPTS
import com.landosol.toolbox.labyrinth.LabyrinthExecutionLimits.POST_ENTRY_ACTION_INTERVAL_MILLIS
import com.landosol.toolbox.labyrinth.LabyrinthExecutionLimits.POST_ENTRY_STABLE_FRAMES

internal interface LabyrinthEncounterPort {
    val _state: MutableStateFlow<LabyrinthEntryRecognitionSessionState>
    var activeNodeArea: Int?
    var activeNodeType: Int?
    val activeSessionId: AutomationSessionId?
    var combatContext: LabyrinthCombatContext?
    var currentExEncounterStrategy: LabyrinthExEncounterStrategy?
    val effectiveExCharacterIds: MutableSet<String>
    var exEncounterFallbackApproved: Boolean
    var exEncounterProbeStartedAt: Long
    var exIdentityProbeDescription: String
    var exLastKnownPage: LabyrinthEntryPageState?
    var exSlot3ProbeAttempts: Int
    var exSlot3ProbePending: Boolean
    val lastPostEntryActionAt: Long
    val postEntryStableFrames: Int
    val validatedRoute: LabyrinthRouteJson?
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
    fun traceWorkflow(owner: String, phase: String): Unit
    fun invalidateBattleTeamRecommendation()
}

/** 识别 EX 遭遇、探查详情并管理身份确认的时限。 */
internal class LabyrinthEncounterWorkflow(private val port: LabyrinthEncounterPort) : LabyrinthEncounterPort by port {
    /**
     * Resolve the actual EX encounter before the challenge button is allowed to fire.
     *
     * Route block ids are deliberately ignored here: EX placement is randomized and event nodes
     * can also enter an EX.  A single EX is identified from challenge-page text.  A multi-monster
     * EX requires the stable five-info-button layout, then exactly slot 3 is opened and its fixed
     * detail-name row is OCR'd.  A missing local guide never borrows another encounter's guide:
     * the automation closes its own detail modal and proceeds with the generic EX battle path.
     */
    fun continueUnknownExAfterDetail(
        sessionId: AutomationSessionId,
        close: EntryPixelRect?,
        observedName: String?,
        timestampMillis: Long,
    ) {
        val displayName = observedName?.takeIf(String::isNotBlank) ?: "OCR无可靠文本"
        if (close == null) {
            // The modal belongs to this session, but without its own close control a generic
            // coordinate tap could affect the monster list behind it. Keep waiting for the
            // structural close anchor instead of converting an unknown guide into a blind tap.
            if (activeSessionId == sessionId) {
                _state.value = _state.value.copy(
                    message = "$exIdentityProbeDescription 未匹配本地攻略（$displayName）；等待详情关闭按钮确认",
                )
            }
            return
        }
        if (lastPostEntryActionAt != Long.MIN_VALUE &&
            timestampMillis - lastPostEntryActionAt < POST_ENTRY_ACTION_INTERVAL_MILLIS
        ) return

        // This is deliberately narrower than a global UNKNOWN fallback. It is enabled only after
        // the session itself opened an EX monster-detail modal, so it cannot dismiss a panel the
        // user opened manually. The existing EX team planner still runs, simply without a
        // encounter-specific guide.
        exEncounterFallbackApproved = true
        invalidateBattleTeamRecommendation()
        nodeLog("ex-guide-fallback name=$displayName action=close-detail-and-continue", warning = true)
        dispatchPostEntryTap(
            sessionId = sessionId,
            kind = LabyrinthPostEntryActionKind.EX_CLOSE_DETAIL,
            label = "EX攻略未建档：关闭魔物详情并继续挑战",
            rect = close,
            timestampMillis = timestampMillis,
        )
        if (activeSessionId == sessionId) {
            _state.value = _state.value.copy(
                combatContext = combatContext,
                message = "$exIdentityProbeDescription 未匹配本地攻略（$displayName）；已关闭详情并按通用EX流程继续",
            )
        }
    }

    fun handleExEncounterRecognitionFrame(
        sessionId: AutomationSessionId,
        result: LabyrinthEntryFrameResult,
        timestampMillis: Long,
    ): Boolean {
        val observation = result.exEncounter
        val page = result.observation.state

        val freshChallengeVisit = labyrinthExIdentityProbeRestarts(
            lastKnownPage = exLastKnownPage,
            page = page,
            encounterResolved = currentExEncounterStrategy != null,
        )
        if (page != LabyrinthEntryPageState.UNKNOWN) exLastKnownPage = page
        if (freshChallengeVisit && combatContext?.kind == LabyrinthCombatKind.EX) {
            // Coming back from a lost battle (重新挑战) or the map: give name OCR and the detail
            // probe their full budget again instead of inheriting the previous visit's clock.
            exSlot3ProbePending = false
            exSlot3ProbeAttempts = 0
            exEncounterProbeStartedAt = timestampMillis
            Log.d("LabyrinthNode", "ex-identity-probe-restarted page=$page")
        }

        // Restarting/reinstalling the app clears the in-memory activeNodeType/combatContext while
        // the game may remain on an already-entered Boss challenge page.  The route cursor is
        // persisted as soon as node entry is confirmed, so use that semantic evidence as the
        // restart-safe fallback instead of forcing the Boss title through the normal/extreme OCR
        // gate.  Live traversal still prefers activeNodeType from the actual dispatched node tap.
        val persistedBossNode = labyrinthPersistedBossNode(validatedRoute)
        val bossRouteEvidence = activeNodeType == LabyrinthNodeTypes.BOSS ||
            (activeNodeType == null && combatContext == null && persistedBossNode != null)

        // Boss uses the same BATTLE_CHALLENGE shell, but its title is "首领战格子" rather than
        // "战斗格子（普通/极难）".  The route node type is already semantic evidence that this
        // is a Boss; recover that context here before the ordinary/EX difficulty gate runs.
        // This also heals a transient/null combatContext or a restarted session without asking
        // the Boss title to match either of the normal/extreme suffix templates.
        if (
            page == LabyrinthEntryPageState.BATTLE_CHALLENGE &&
            bossRouteEvidence &&
            combatContext?.kind != LabyrinthCombatKind.BOSS
        ) {
            activeNodeType = LabyrinthNodeTypes.BOSS
            combatContext = LabyrinthCombatContext(
                kind = LabyrinthCombatKind.BOSS,
                teamIndex = 1,
                targetCount = combatContext?.targetCount ?: 1,
            )
            currentExEncounterStrategy = null
            exEncounterFallbackApproved = false
            exSlot3ProbePending = false
            exEncounterProbeStartedAt = Long.MIN_VALUE
            invalidateBattleTeamRecommendation()
            if (activeSessionId == sessionId) {
                _state.value = _state.value.copy(
                    combatContext = combatContext,
                    message = "已确认首领节点挑战页；Boss不参与普通/极难二分类",
                )
            }
        }

        // The game can enter EX from an event even though the route node itself is EVENT.  “极难”
        // is game-visible evidence and therefore has priority over the route-derived combat kind.
        if (page == LabyrinthEntryPageState.BATTLE_CHALLENGE &&
            observation?.extremeChallenge == true &&
            combatContext?.kind != LabyrinthCombatKind.EX
        ) {
            combatContext = LabyrinthCombatContext(
                kind = LabyrinthCombatKind.EX,
                targetCount = when {
                    observation.multiMonsterLikely -> 5
                    observation.specialDualLikely -> 2
                    else -> 1
                },
            )
            currentExEncounterStrategy = null
            exEncounterFallbackApproved = false
            exSlot3ProbePending = false
            exSlot3ProbeAttempts = 0
            exEncounterProbeStartedAt = timestampMillis
            synchronized(effectiveExCharacterIds) { effectiveExCharacterIds.clear() }
            invalidateBattleTeamRecommendation()
            _state.value = _state.value.copy(
                combatContext = combatContext,
                message = "挑战页识别到极难，已按EX遭遇接管；正在识别具体攻略",
            )
        }

        // EVENT arena choices can open an ordinary challenge page while the route-derived
        // combatContext is still null (the clicked map node itself is EVENT).  Once the challenge
        // title has positively resolved as non-EX, restore an explicit NORMAL context so retry,
        // team recommendation and failure policy never run with an untyped battle.
        val persistedCurrentNode = labyrinthPersistedCurrentNode(validatedRoute)
        val eventRouteEvidence = activeNodeType == LabyrinthNodeTypes.EVENT ||
            (activeNodeType == null && persistedCurrentNode?.blockType == LabyrinthNodeTypes.EVENT)
        if (
            page == LabyrinthEntryPageState.BATTLE_CHALLENGE &&
            eventRouteEvidence &&
            observation?.challengeDifficultyResolved == true &&
            observation.extremeChallenge != true &&
            combatContext == null
        ) {
            if (activeNodeType == null) {
                activeNodeType = LabyrinthNodeTypes.EVENT
                activeNodeArea = persistedCurrentNode?.area
            }
            combatContext = LabyrinthCombatContext(LabyrinthCombatKind.NORMAL)
            invalidateBattleTeamRecommendation()
            if (activeSessionId == sessionId) {
                _state.value = _state.value.copy(
                    combatContext = combatContext,
                    message = "事件进入普通战挑战页，已恢复普通战上下文",
                )
            }
        }

        // Challenge-title OCR is asynchronous. In particular, an EVENT may enter an EX while the
        // route-derived combat context is still null/non-EX. The first recognized challenge frame
        // must therefore wait for a positive "战斗格子" title read before a normal challenge is
        // allowed. Otherwise the generic challenge branch can win the race one frame before
        // "极难" arrives.
        if (page == LabyrinthEntryPageState.BATTLE_CHALLENGE &&
            combatContext?.kind != LabyrinthCombatKind.BOSS &&
            combatContext?.kind != LabyrinthCombatKind.EX &&
            observation?.challengeDifficultyResolved != true
        ) {
            if (exEncounterProbeStartedAt == Long.MIN_VALUE) exEncounterProbeStartedAt = timestampMillis
            if (timestampMillis - exEncounterProbeStartedAt >= EX_ENCOUNTER_SINGLE_NAME_TIMEOUT_MILLIS) {
                finishFromPlanner(
                    sessionId,
                    "挑战页标题在限定时间内未可靠确认普通/极难；不执行盲目挑战",
                )
            } else if (activeSessionId == sessionId) {
                _state.value = _state.value.copy(
                    message = "挑战页：正在确认普通/极难，确认前禁止点击挑战",
                )
            }
            return true.also { traceWorkflow("battle", "encounter-recognition") }
        }

        // A monster-detail modal is action-owned only when this session opened slot 3.  Manually
        // opened details remain read-only, which prevents a generic UNKNOWN fallback from closing
        // a panel the user is inspecting.
        if (observation?.monsterDetailOpen == true) {
            if (!exSlot3ProbePending) {
                if (activeSessionId == sessionId) {
                    _state.value = _state.value.copy(
                        message = "检测到魔物详情；非程序发起的EX识别，保持人工控制",
                    )
                }
                return true.also { traceWorkflow("battle", "encounter-recognition") }
            }
            if (!observation.trusted || observation.encounterId == null) {
                if (exEncounterProbeStartedAt != Long.MIN_VALUE &&
                    timestampMillis - exEncounterProbeStartedAt >= EX_ENCOUNTER_DETAIL_TIMEOUT_MILLIS
                ) {
                    continueUnknownExAfterDetail(
                        sessionId = sessionId,
                        close = observation.closeButtonRect,
                        observedName = observation.monsterDetailText,
                        timestampMillis = timestampMillis,
                    )
                } else if (activeSessionId == sessionId) {
                    _state.value = _state.value.copy(
                        message = "正在识别$exIdentityProbeDescription：${observation.monsterDetailText ?: "等待两次独立OCR"}",
                    )
                }
                return true.also { traceWorkflow("battle", "encounter-recognition") }
            }
            val strategy = LabyrinthExEncounterCatalog.all.firstOrNull { it.id == observation.encounterId }
            if (strategy == null || strategy.identityName.startsWith("？？？")) {
                continueUnknownExAfterDetail(
                    sessionId = sessionId,
                    close = observation.closeButtonRect,
                    observedName = observation.encounterName ?: observation.encounterId,
                    timestampMillis = timestampMillis,
                )
                return true.also { traceWorkflow("battle", "encounter-recognition") }
            }
            currentExEncounterStrategy = strategy
            combatContext = (combatContext ?: LabyrinthCombatContext(LabyrinthCombatKind.EX)).copy(
                kind = LabyrinthCombatKind.EX,
                targetCount = strategy.targetCount,
            )
            invalidateBattleTeamRecommendation()
            val close = observation.closeButtonRect
            if (close == null) {
                if (activeSessionId == sessionId) {
                    _state.value = _state.value.copy(
                        combatContext = combatContext,
                        message = "已识别EX：${strategy.identityName}，但详情关闭按钮结构未确认",
                    )
                }
                return true.also { traceWorkflow("battle", "encounter-recognition") }
            }
            if (lastPostEntryActionAt != Long.MIN_VALUE &&
                timestampMillis - lastPostEntryActionAt < POST_ENTRY_ACTION_INTERVAL_MILLIS
            ) return true.also { traceWorkflow("battle", "encounter-recognition") }
            dispatchPostEntryTap(
                sessionId = sessionId,
                kind = LabyrinthPostEntryActionKind.EX_CLOSE_DETAIL,
                label = "EX识别完成：关闭魔物详情",
                rect = close,
                timestampMillis = timestampMillis,
            )
            if (activeSessionId == sessionId) {
                _state.value = _state.value.copy(
                    combatContext = combatContext,
                    message = "已识别EX：${strategy.identityName}；关闭详情后按攻略编组",
                )
            }
            return true.also { traceWorkflow("battle", "encounter-recognition") }
        }

        if (page != LabyrinthEntryPageState.BATTLE_CHALLENGE) return false
        val isEx = combatContext?.kind == LabyrinthCombatKind.EX || observation?.extremeChallenge == true
        if (!isEx) return false

        // Single-target EX can be identified directly from the stable challenge-page text.
        if (currentExEncounterStrategy == null && observation?.trusted == true && observation.encounterId != null) {
            LabyrinthExEncounterCatalog.all.firstOrNull { it.id == observation.encounterId }?.let { strategy ->
                currentExEncounterStrategy = strategy
                combatContext = (combatContext ?: LabyrinthCombatContext(LabyrinthCombatKind.EX)).copy(
                    kind = LabyrinthCombatKind.EX,
                    targetCount = strategy.targetCount,
                )
                invalidateBattleTeamRecommendation()
                if (activeSessionId == sessionId) {
                    _state.value = _state.value.copy(
                        combatContext = combatContext,
                        message = "已识别EX：${strategy.identityName}；准备按攻略进入编组",
                    )
                }
            }
        }
        // A guide-less EX was confirmed from an automation-owned details modal and deliberately
        // dismissed.  Do not reopen the modal or wait for a strategy that cannot arrive; allow
        // the existing generic EX team-selection path to take over instead.
        if (currentExEncounterStrategy != null || exEncounterFallbackApproved) return false

        if (exEncounterProbeStartedAt == Long.MIN_VALUE) exEncounterProbeStartedAt = timestampMillis
        if (exSlot3ProbePending) {
            if (timestampMillis - exEncounterProbeStartedAt >= EX_ENCOUNTER_OPEN_DETAIL_TIMEOUT_MILLIS) {
                if (exSlot3ProbeAttempts >= MAX_EX_SLOT3_PROBE_ATTEMPTS) {
                    finishFromPlanner(sessionId, "已尝试打开$exIdentityProbeDescription 详情${exSlot3ProbeAttempts}次仍未出现详情页，停止EX自动挑战")
                } else {
                    exSlot3ProbePending = false
                    exEncounterProbeStartedAt = timestampMillis
                    if (activeSessionId == sessionId) {
                        _state.value = _state.value.copy(message = "$exIdentityProbeDescription 详情未出现，准备有限重试")
                    }
                }
            }
            return true.also { traceWorkflow("battle", "encounter-recognition") }
        }

        val structuredProbe = when {
            observation?.multiMonsterLikely == true ->
                "第3只魔物" to observation.slot3InfoButtonRect
            observation?.specialDualLikely == true ->
                "特殊EX右侧本体" to observation.specialDualIdentityInfoButtonRect
            else -> null
        }
        if (structuredProbe != null) {
            val (probeDescription, rect) = structuredProbe
            exIdentityProbeDescription = probeDescription
            if (rect == null) {
                if (activeSessionId == sessionId) {
                    _state.value = _state.value.copy(message = "已确认EX布局，但$probeDescription 信息按钮未达到安全识别线")
                }
                return true.also { traceWorkflow("battle", "encounter-recognition") }
            }
            if (exSlot3ProbeAttempts >= MAX_EX_SLOT3_PROBE_ATTEMPTS) {
                finishFromPlanner(sessionId, "$probeDescription 详情探测达到上限，未识别具体EX；不发起挑战")
                return true.also { traceWorkflow("battle", "encounter-recognition") }
            }
            if (postEntryStableFrames < POST_ENTRY_STABLE_FRAMES) return true.also { traceWorkflow("battle", "encounter-recognition") }
            exSlot3ProbeAttempts++
            // Set pending before dispatch so a very fast next capture cannot be treated as a
            // manually opened modal while the executor callback is still finishing.
            exSlot3ProbePending = true
            exEncounterProbeStartedAt = timestampMillis
            dispatchPostEntryTap(
                sessionId = sessionId,
                kind = LabyrinthPostEntryActionKind.EX_PROBE_DETAIL,
                label = "识别EX：查看$probeDescription 详情",
                rect = rect,
                timestampMillis = timestampMillis,
            )
            return true.also { traceWorkflow("battle", "encounter-recognition") }
        }

        // Give direct challenge-page OCR a short chance, then fall back to the fixed Details
        // button for a single-target EX.  Opening Details is non-destructive and lets the existing
        // detail-modal resolver read the name from a much cleaner row.  This also covers future
        // single EX names whose challenge-page text shifts slightly between client revisions.
        val singleDetailRect = labyrinthSingleExDetailProbeRect(
            pageState = page,
            isEx = isEx,
            encounterResolved = currentExEncounterStrategy != null,
            multiMonsterLikely = observation?.multiMonsterLikely == true,
            specialDualLikely = observation?.specialDualLikely == true,
            elapsedMillis = timestampMillis - exEncounterProbeStartedAt,
            stableFrames = postEntryStableFrames,
            attempts = exSlot3ProbeAttempts,
            frameWidth = result.frameWidth,
            frameHeight = result.frameHeight,
            maxAttempts = MAX_EX_SLOT3_PROBE_ATTEMPTS,
        )
        if (singleDetailRect != null) {
            exIdentityProbeDescription = "单体魔物"
            exSlot3ProbeAttempts++
            exSlot3ProbePending = true
            exEncounterProbeStartedAt = timestampMillis
            dispatchPostEntryTap(
                sessionId = sessionId,
                kind = LabyrinthPostEntryActionKind.EX_PROBE_DETAIL,
                label = "识别EX：查看单体魔物详情",
                rect = singleDetailRect,
                timestampMillis = timestampMillis,
            )
            return true.also { traceWorkflow("battle", "encounter-recognition") }
        }

        // If neither direct OCR nor the bounded detail probe can establish identity, retain the
        // old safe stop rather than challenging an unknown EX.
        if (timestampMillis - exEncounterProbeStartedAt >= EX_ENCOUNTER_SINGLE_NAME_TIMEOUT_MILLIS) {
            finishFromPlanner(
                sessionId,
                "已确认EX挑战页，但单体名称OCR/详情识别均未建立可信身份；不发起盲目挑战",
            )
        } else if (activeSessionId == sessionId) {
            _state.value = _state.value.copy(
                message = "EX挑战页：等待单体名称OCR；失败后将安全打开详情识别",
            )
        }
        return true.also { traceWorkflow("battle", "encounter-recognition") }
    }

    fun resetExEncounterTracking() {
        currentExEncounterStrategy = null
        exEncounterFallbackApproved = false
        exSlot3ProbePending = false
        exSlot3ProbeAttempts = 0
        exEncounterProbeStartedAt = Long.MIN_VALUE
        exLastKnownPage = null
        exIdentityProbeDescription = "EX识别目标"
        // The official "有效效果" sweep describes the encounter rather than the node, so it is
        // cleared by [resetEffectiveCharacterScan] instead of here.
    }

    fun onEncounterTapExecuted(kind: LabyrinthPostEntryActionKind) {
        when (kind) {
            LabyrinthPostEntryActionKind.EX_CLOSE_DETAIL -> {
                exSlot3ProbePending = false
                exEncounterProbeStartedAt = Long.MIN_VALUE
            }
            else -> Unit
        }
    }

    fun onEncounterTapRejected(kind: LabyrinthPostEntryActionKind): Unit {
        if (kind == LabyrinthPostEntryActionKind.EX_PROBE_DETAIL) {
            exSlot3ProbePending = false
            exSlot3ProbeAttempts = (exSlot3ProbeAttempts - 1).coerceAtLeast(0)
            exEncounterProbeStartedAt = Long.MIN_VALUE
        }
    }
}
