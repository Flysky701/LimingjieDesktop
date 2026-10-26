package com.landosol.toolbox.labyrinth.execution.battle

import com.landosol.toolbox.labyrinth.*
import android.util.Log
import com.landosol.toolbox.automation.AutomationSessionId
import com.landosol.toolbox.labyrinth.vision.LabyrinthBattleElementFilter
import com.landosol.toolbox.labyrinth.vision.LabyrinthEntryFrameResult
import com.landosol.toolbox.labyrinth.vision.LabyrinthEntryPageState
import com.landosol.toolbox.labyrinth.vision.LabyrinthBattleTeamObservation
import com.landosol.toolbox.labyrinth.vision.LabyrinthBattleTeamRecognitionState
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import com.landosol.toolbox.labyrinth.LabyrinthExecutionLimits.TEAM_RECOMMENDATION_LOG_TAG
import com.landosol.toolbox.labyrinth.LabyrinthExecutionLimits.TEAM_SELECTION_PLAN_LOG_TAG

internal interface LabyrinthBattleRecommendationPort {
    val _state: MutableStateFlow<LabyrinthEntryRecognitionSessionState>
    val actionInFlight: AtomicBoolean
    val activeSessionId: AutomationSessionId?
    val battleRetryCount: Int
    val battleTeamRecommendationPlanner: LabyrinthBattleTeamRecommendationPlanner?
    val battleTeamRecommendationUnavailableReason: String?
    val battleTeamSelectionPlanner: LabyrinthBattleTeamSelectionPlanner?
    var bossEditorBlocked: Boolean
    val bossEditorPreparationStage: LabyrinthBossEditorPreparationStage
    var bossEditorTeamIndex: Int?
    val bossFallbackMultiTeamActive: Boolean
    var bossMultiTeamTargetCount: Int?
    val bossTeamMode: LabyrinthBossTeamMode
    var combatContext: LabyrinthCombatContext?
    val committedBattleCharacterIds: MutableSet<String>
    val currentBattleTeamSignatures: MutableSet<String>
    val currentExEncounterStrategy: LabyrinthExEncounterStrategy?
    val effectiveCharacterScanStage: LabyrinthEffectiveCharacterScanStage
    val effectiveExCharacterIds: MutableSet<String>
    val exhaustedBattleRosterFilters: MutableSet<com.landosol.toolbox.labyrinth.vision.LabyrinthBattleElementFilter>
    var exhaustedBattleRosterFiltersKey: String?
    val failedBattleTeamSignatures: MutableSet<String>
    var lastBattleTeamRecommendationKey: String?
    var lastBattleTeamSelectionPlanLog: String?
    var pendingBossTeamAdvance: Pair<Int, List<String>>?
    val preferPureBossDamageSystem: Boolean
    var preparedBossFirstTeamIds: List<String>
    val relicChoicePolicy: LabyrinthRelicChoicePolicy
    val relicMarkStackCalibration: Map<LabyrinthRelicMark, Int>
    val validatedRoute: LabyrinthRouteJson?
    fun resetBattleTeamExecutionTracking(): Unit
}

/** 同步 Boss 队次并生成配队建议与编组计划。 */
internal class LabyrinthBattleRecommendationWorkflow(private val port: LabyrinthBattleRecommendationPort) : LabyrinthBattleRecommendationPort by port {
    /** A Boss tab change is a team boundary even though the page type stays the same. */
    fun synchronizeBossEditor(result: LabyrinthEntryFrameResult) {
        if (actionInFlight.get()) return
        val observation = result.battleTeamSelection ?: return
        if (observation.recognitionState != com.landosol.toolbox.labyrinth.vision.LabyrinthBattleTeamRecognitionState.STABLE) return
        val index = observation.bossTeamIndex ?: return
        if (combatContext?.kind == LabyrinthCombatKind.BOSS &&
            bossEditorPreparationStage !in setOf(
                LabyrinthBossEditorPreparationStage.INACTIVE,
                LabyrinthBossEditorPreparationStage.COMPLETE,
            )
        ) {
            // Preparation deliberately visits tabs out of the normal 1 -> 2 -> 3 commit flow.
            // Treat those transitions as cleanup navigation, never as a submitted team boundary.
            bossEditorTeamIndex = index
            pendingBossTeamAdvance = null
            bossEditorBlocked = false
            combatContext = (combatContext ?: LabyrinthCombatContext(LabyrinthCombatKind.BOSS))
                .copy(kind = LabyrinthCombatKind.BOSS, teamIndex = index)
            _state.value = _state.value.copy(combatContext = combatContext)
            return
        }
        if (bossEditorTeamIndex == index) return
        val pending = pendingBossTeamAdvance
        if (bossEditorTeamIndex == null) {
            bossEditorBlocked = index != 1
        } else if (pending?.first == index) {
            val advance = labyrinthBossTeamAdvanceDisposition(bossTeamMode, pending.second)
            if (advance.committedIds.isNotEmpty()) {
                synchronized(currentBattleTeamSignatures) {
                    currentBattleTeamSignatures += labyrinthBattleTeamSignature(advance.committedIds)
                }
                synchronized(committedBattleCharacterIds) {
                    committedBattleCharacterIds += advance.committedIds
                }
            }
            preparedBossFirstTeamIds = advance.preparedSingleTeamIds
        } else {
            // Untracked manual tab changes may hide other teams' members. Never silently reuse
            // the first team's recommendation against a different editor.
            bossEditorBlocked = true
        }
        pendingBossTeamAdvance = null
        bossEditorTeamIndex = index
        combatContext = (combatContext ?: LabyrinthCombatContext(LabyrinthCombatKind.BOSS))
            .copy(kind = LabyrinthCombatKind.BOSS, teamIndex = index)
        lastBattleTeamRecommendationKey = null
        resetBattleTeamExecutionTracking()
        _state.value = _state.value.copy(combatContext = combatContext)
    }

    /** Builds the recommendation consumed by the guarded battle-team execution stage. */
    fun refreshBattleTeamRecommendation(
        sessionId: AutomationSessionId,
        pageState: LabyrinthEntryPageState,
    ) {
        if (activeSessionId != sessionId) return
        if (pageState != LabyrinthEntryPageState.BATTLE_TEAM_SELECTION) {
            if (
                lastBattleTeamRecommendationKey != null ||
                _state.value.battleTeamRecommendation != null ||
                _state.value.battleTeamRecommendationUnavailableReason != null
            ) {
                lastBattleTeamRecommendationKey = null
                _state.value = _state.value.copy(
                    battleTeamRecommendation = null,
                    battleTeamRecommendationUnavailableReason = null,
                )
            }
            return
        }

        if (combatContext?.kind == LabyrinthCombatKind.BOSS &&
            bossEditorPreparationStage != LabyrinthBossEditorPreparationStage.COMPLETE
        ) {
            lastBattleTeamRecommendationKey = null
            _state.value = _state.value.copy(
                battleTeamRecommendation = null,
                battleTeamRecommendationUnavailableReason = null,
                battleTeamSelectionPlan = null,
                message = "Boss编组预处理：清空队伍1/2/3并返回队伍1",
            )
            return
        }

        if (combatContext?.kind in setOf(LabyrinthCombatKind.EX, LabyrinthCombatKind.BOSS) &&
            effectiveCharacterScanStage != LabyrinthEffectiveCharacterScanStage.COMPLETE
        ) {
            lastBattleTeamRecommendationKey = null
            _state.value = _state.value.copy(
                battleTeamRecommendation = null,
                battleTeamRecommendationUnavailableReason = null,
                battleTeamSelectionPlan = null,
                message = "EX/Boss编组前正在扫描“有效效果”角色；完成后再生成阵容",
            )
            return
        }

        if (
            bossTeamMode == LabyrinthBossTeamMode.SINGLE_TEAM &&
            preparedBossFirstTeamIds.isNotEmpty() &&
            bossEditorTeamIndex in 2..3
        ) {
            _state.value = _state.value.copy(battleTeamRecommendation = null, battleTeamSelectionPlan = null,
                battleTeamRecommendationUnavailableReason = "第一队已确认；按单队首战策略核对空置队伍并前往第三队开始战斗")
            return
        }
        val partialBossTarget = bossMultiTeamTargetCount
        if (
            bossTeamMode == LabyrinthBossTeamMode.MULTI_TEAM &&
            partialBossTarget != null && partialBossTarget < 3 &&
            (bossEditorTeamIndex ?: 1) > partialBossTarget
        ) {
            _state.value = _state.value.copy(
                battleTeamRecommendation = null,
                battleTeamSelectionPlan = null,
                battleTeamRecommendationUnavailableReason =
                    "Boss已确认${partialBossTarget}支安全队伍；后续队伍留空并准备开始挑战",
            )
            return
        }

        val current = _state.value
        val currentCombatContext = combatContext
        val encounterStrategy = when (currentCombatContext?.kind) {
            LabyrinthCombatKind.EX -> currentExEncounterStrategy
            LabyrinthCombatKind.BOSS -> labyrinthBossEncounterStrategy(validatedRoute)
            else -> null
        }
        val preferCohesiveDamageSystem = when (currentCombatContext?.kind) {
            LabyrinthCombatKind.EX -> true
            LabyrinthCombatKind.BOSS -> preferPureBossDamageSystem
            else -> false
        }
        val defenseStacks = relicChoicePolicy.markStacks(
            current.observedRelics,
            relicMarkStackCalibration,
        )
            .getValue(LabyrinthRelicMark.DEFENSE)
        val targetCount = encounterStrategy?.targetCount ?: currentCombatContext?.targetCount ?: 1
        val requestedBossTeamCount = if (
            currentCombatContext?.kind == LabyrinthCombatKind.BOSS &&
            bossTeamMode == LabyrinthBossTeamMode.MULTI_TEAM
        ) {
            val absoluteTarget = bossMultiTeamTargetCount ?: 3
            (absoluteTarget - currentCombatContext.teamIndex + 1).coerceIn(1, 3)
        } else {
            1
        }
        val acquiredCharacterIds = current.joinedCharacters
            .map(LabyrinthJoinedCharacter::characterId)
            .map(::canonicalLabyrinthRoleId)
            .distinct()
        val cacheKey = buildString {
            append(acquiredCharacterIds.sorted().joinToString(","))
            append("|defense=")
            append(defenseStacks)
            append("|targets=")
            append(targetCount)
            append("|team=")
            append(currentCombatContext?.teamIndex ?: 1)
            append("|bossMode=")
            append(bossTeamMode.name)
            append("|fallbackMulti=")
            append(bossFallbackMultiTeamActive)
            append("|requestedBossTeams=")
            append(requestedBossTeamCount)
            append("|bossTarget=")
            append(bossMultiTeamTargetCount ?: 0)
            append("|retry=")
            append(battleRetryCount)
            append("|encounter=")
            append(encounterStrategy?.id ?: "none")
            append("|effective=")
            append(synchronized(effectiveExCharacterIds) { effectiveExCharacterIds.sorted().joinToString(",") })
            append("|cohesiveDamage=")
            append(preferCohesiveDamageSystem)
            append("|failedTeams=")
            append(synchronized(failedBattleTeamSignatures) { failedBattleTeamSignatures.sorted().joinToString(";") })
            append("|used=")
            append(
                synchronized(committedBattleCharacterIds) {
                    committedBattleCharacterIds.sorted().joinToString(",")
                },
            )
        }
        if (
            cacheKey == lastBattleTeamRecommendationKey &&
            (current.battleTeamRecommendation != null ||
                current.battleTeamRecommendationUnavailableReason != null)
        ) {
            val message = labyrinthBattleTeamSelectionMessage(
                combatContext = currentCombatContext,
                recommendation = current.battleTeamRecommendation,
                unavailableReason = current.battleTeamRecommendationUnavailableReason,
            )
            if (current.message != message) _state.value = current.copy(message = message)
            return
        }

        val usedCharacterIds = synchronized(committedBattleCharacterIds) {
            committedBattleCharacterIds.toSet()
        }
        val eligibleCharacterIds = acquiredCharacterIds.filterNot(usedCharacterIds::contains)
        val planner = battleTeamRecommendationPlanner
        // 「不可用」是否由 cacheKey 的输入唯一决定。规划器自己的结论（没有可用角色、没有合格一号位
        // 等）是输入的纯函数，可以缓存；真正的瞬时故障（资料未加载、计算抛错）不是，缓存住就等于把
        // 一次瞬时失败固定成终态、每帧都命中同一条原因。内存不足是唯一的例外：按既有注释，它绝不
        // 能每帧重算。
        var recommendationResultCacheable = true
        val recommendationResult = if (planner == null) {
            recommendationResultCacheable = false
            LabyrinthBattleTeamRecommendationResult.Unavailable(
                battleTeamRecommendationUnavailableReason ?: "角色决策资料未加载",
            )
        } else {
            runCatching {
                val decisionContext = LabyrinthRoleDecisionContext(
                    defenseMarkStacks = defenseStacks,
                    targetCount = targetCount,
                    optimizeBossVanguardSynergy = currentCombatContext?.kind == LabyrinthCombatKind.BOSS &&
                        currentCombatContext.teamIndex == 1,
                    preferSingleDamageSystem = preferCohesiveDamageSystem,
                    encounterStrategy = encounterStrategy,
                    effectiveCharacterIds = synchronized(effectiveExCharacterIds) {
                        effectiveExCharacterIds.toSet()
                    },
                    relaxedVanguardGate = currentCombatContext?.kind == LabyrinthCombatKind.BOSS &&
                        bossFallbackMultiTeamActive,
                )
                // Team 1 must be tank-led. Teams 2/3 fill their slot even without a qualified
                // vanguard: 2026-09-17 a Boss survived on a sliver because slot 3 was left empty.
                val allowSupplementLead = currentCombatContext?.kind == LabyrinthCombatKind.BOSS &&
                    bossTeamMode == LabyrinthBossTeamMode.MULTI_TEAM &&
                    currentCombatContext.teamIndex > 1
                if (battleRetryCount > 0) {
                    val failedSignatures = synchronized(failedBattleTeamSignatures) {
                        failedBattleTeamSignatures.toSet()
                    }
                    planner.retryRecommendation(
                        acquiredCharacterIds = eligibleCharacterIds,
                        context = decisionContext,
                        failedTeamSignatures = failedSignatures,
                        retryNumber = battleRetryCount,
                        lastFailedTeamSignature = synchronized(failedBattleTeamSignatures) {
                            failedBattleTeamSignatures.lastOrNull()
                        },
                        requestedBossTeamCount = requestedBossTeamCount,
                        allowSupplementLead = allowSupplementLead,
                    )
                } else {
                    planner.initialRecommendation(
                        acquiredCharacterIds = eligibleCharacterIds,
                        context = decisionContext,
                        requestedBossTeamCount = requestedBossTeamCount,
                        allowSupplementLead = allowSupplementLead,
                    )
                }
            }.getOrElse { failure ->
                recommendationResultCacheable = failure is OutOfMemoryError
                LabyrinthBattleTeamRecommendationResult.Unavailable(
                    if (failure is OutOfMemoryError) {
                        // Do not retry every frame or misreport this as missing character data.
                        "编组计算内存不足，已停止本次自动编组；请停止并重启辅助 App 后再试"
                    } else {
                        "生成第${currentCombatContext?.teamIndex ?: 1}队建议失败：${failure.message ?: "未知错误"}"
                    },
                )
            }
        }
        // 只缓存「由 cacheKey 唯一决定」的结果：瞬时故障不写缓存键，下一帧就会重算。
        lastBattleTeamRecommendationKey = cacheKey.takeIf { recommendationResultCacheable }
        when (recommendationResult) {
            is LabyrinthBattleTeamRecommendationResult.Ready -> {
                val recommendation = recommendationResult.recommendation
                if (
                    currentCombatContext?.kind == LabyrinthCombatKind.BOSS &&
                    bossTeamMode == LabyrinthBossTeamMode.MULTI_TEAM
                ) {
                    val teamIndex = currentCombatContext.teamIndex.coerceIn(1, 3)
                    val absoluteTarget = (teamIndex - 1 + recommendation.plannedBossTeamCount).coerceIn(teamIndex, 3)
                    if (bossMultiTeamTargetCount == null || teamIndex == 1) {
                        bossMultiTeamTargetCount = absoluteTarget
                    }
                }
                _state.value = current.copy(
                    battleTeamRecommendation = recommendation,
                    battleTeamRecommendationUnavailableReason = null,
                    message = labyrinthBattleTeamSelectionMessage(
                        combatContext = currentCombatContext,
                        recommendation = recommendation,
                        unavailableReason = null,
                    ),
                )
                runCatching { Log.i(TEAM_RECOMMENDATION_LOG_TAG, recommendation.logText()) }
            }

            is LabyrinthBattleTeamRecommendationResult.Unavailable -> {
                _state.value = current.copy(
                    battleTeamRecommendation = null,
                    battleTeamRecommendationUnavailableReason = recommendationResult.reason,
                    message = labyrinthBattleTeamSelectionMessage(
                        combatContext = currentCombatContext,
                        recommendation = null,
                        unavailableReason = recommendationResult.reason,
                    ),
                )
                runCatching {
                    Log.w(TEAM_RECOMMENDATION_LOG_TAG, "第一队建议不可用：${recommendationResult.reason}")
                }
            }
        }
    }

    fun refreshBattleTeamSelectionPlan(
        sessionId: AutomationSessionId,
        pageState: LabyrinthEntryPageState,
        observation: com.landosol.toolbox.labyrinth.vision.LabyrinthBattleTeamObservation?,
    ) {
        if (activeSessionId != sessionId) return
        val recommendation = _state.value.battleTeamRecommendation
        val planner = battleTeamSelectionPlanner
        if (
            pageState != LabyrinthEntryPageState.BATTLE_TEAM_SELECTION ||
            recommendation == null ||
            observation == null ||
            planner == null
        ) {
            if (_state.value.battleTeamSelectionPlan != null || lastBattleTeamSelectionPlanLog != null) {
                lastBattleTeamSelectionPlanLog = null
                _state.value = _state.value.copy(battleTeamSelectionPlan = null)
            }
            return
        }
        val recommendationKey = recommendation.members.joinToString(",") {
            canonicalLabyrinthRoleId(it.characterId)
        }
        if (exhaustedBattleRosterFiltersKey != recommendationKey) {
            exhaustedBattleRosterFiltersKey = recommendationKey
            exhaustedBattleRosterFilters.clear()
        }
        val plan = planner.plan(
            sessionId = sessionId,
            recommendation = recommendation,
            observation = observation,
            exhaustedFilters = exhaustedBattleRosterFilters.toSet(),
            // Ordinary battles keep whatever full team the game already has instead of paying for
            // a re-selection; a failed attempt (battleRetryCount > 0) goes back to the normal
            // recommendation path, which is the existing retry behaviour.
            holdCurrentTeamRequested = combatContext?.kind == LabyrinthCombatKind.NORMAL &&
                battleRetryCount == 0,
        )
        val logText = plan.logText()
        val current = _state.value
        _state.value = current.copy(
            battleTeamSelectionPlan = plan,
            message = labyrinthBattleTeamSelectionMessage(
                combatContext = combatContext,
                recommendation = recommendation,
                unavailableReason = null,
                selectionPlan = plan,
            ),
        )
        if (lastBattleTeamSelectionPlanLog != logText) {
            lastBattleTeamSelectionPlanLog = logText
            runCatching { Log.i(TEAM_SELECTION_PLAN_LOG_TAG, logText) }
        }
    }
}
