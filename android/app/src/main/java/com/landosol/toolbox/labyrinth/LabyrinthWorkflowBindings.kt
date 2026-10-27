package com.landosol.toolbox.labyrinth

import com.landosol.toolbox.labyrinth.execution.battle.LabyrinthBattleRecommendationWorkflow
import com.landosol.toolbox.labyrinth.execution.battle.LabyrinthBattleRecommendationPort
import com.landosol.toolbox.labyrinth.execution.battle.LabyrinthBattleRosterWorkflow
import com.landosol.toolbox.labyrinth.execution.battle.LabyrinthBattleRosterPort
import com.landosol.toolbox.labyrinth.execution.battle.LabyrinthBattleTeamWorkflow
import com.landosol.toolbox.labyrinth.execution.battle.LabyrinthBattleTeamPort
import com.landosol.toolbox.labyrinth.execution.battle.LabyrinthBattleCombatWorkflow
import com.landosol.toolbox.labyrinth.execution.battle.LabyrinthBattleCombatPort
import com.landosol.toolbox.labyrinth.execution.battle.LabyrinthEncounterWorkflow
import com.landosol.toolbox.labyrinth.execution.battle.LabyrinthEncounterPort
import com.landosol.toolbox.labyrinth.execution.battle.LabyrinthEquipmentWorkflow
import com.landosol.toolbox.labyrinth.execution.battle.LabyrinthEquipmentPort
import com.landosol.toolbox.labyrinth.execution.reward.LabyrinthRewardWorkflow
import com.landosol.toolbox.labyrinth.execution.reward.LabyrinthRewardPort
import com.landosol.toolbox.labyrinth.execution.reward.LabyrinthSettlementWorkflow
import com.landosol.toolbox.labyrinth.execution.reward.LabyrinthSettlementPort
import com.landosol.toolbox.labyrinth.execution.map.LabyrinthMapNavigationWorkflow
import com.landosol.toolbox.labyrinth.execution.map.LabyrinthMapNavigationPort
import com.landosol.toolbox.labyrinth.execution.map.LabyrinthMapTransitionWorkflow
import com.landosol.toolbox.labyrinth.execution.map.LabyrinthMapTransitionPort
import com.landosol.toolbox.labyrinth.execution.map.LabyrinthOrphanMoveRecoveryPort
import com.landosol.toolbox.labyrinth.execution.map.LabyrinthOrphanMoveRecoveryWorkflow
import com.landosol.toolbox.labyrinth.execution.map.LabyrinthMapViewportWorkflow
import com.landosol.toolbox.labyrinth.execution.map.LabyrinthMapViewportPort
import com.landosol.toolbox.labyrinth.execution.event.LabyrinthEventWorkflow
import com.landosol.toolbox.labyrinth.execution.event.LabyrinthEventPort
import com.landosol.toolbox.labyrinth.execution.shop.LabyrinthShopWorkflow
import com.landosol.toolbox.labyrinth.execution.shop.LabyrinthShopPort
import com.landosol.toolbox.labyrinth.execution.entry.LabyrinthEntryWorkflow
import com.landosol.toolbox.labyrinth.execution.entry.LabyrinthEntryPort
import com.landosol.toolbox.labyrinth.execution.presentation.LabyrinthPresentationWorkflow
import com.landosol.toolbox.labyrinth.execution.presentation.LabyrinthPresentationPort
import com.landosol.toolbox.labyrinth.execution.runtime.LabyrinthRunBoundaryWorkflow
import com.landosol.toolbox.labyrinth.execution.runtime.LabyrinthRunBoundaryPort
import com.landosol.toolbox.labyrinth.execution.runtime.LabyrinthRecoveryWorkflow
import com.landosol.toolbox.labyrinth.execution.runtime.LabyrinthRecoveryPort
import com.landosol.toolbox.labyrinth.execution.runtime.LabyrinthPersistenceWorkflow
import com.landosol.toolbox.labyrinth.execution.runtime.LabyrinthPersistencePort
import com.landosol.toolbox.labyrinth.execution.runtime.LabyrinthPageRouterWorkflow
import com.landosol.toolbox.labyrinth.execution.runtime.LabyrinthPageRouterPort
import com.landosol.toolbox.labyrinth.execution.runtime.LabyrinthPageActionWorkflow
import com.landosol.toolbox.labyrinth.execution.runtime.LabyrinthPageActionPort
import com.landosol.toolbox.automation.AutomationSessionId
import com.landosol.toolbox.automation.SessionBoundActionExecutor
import com.landosol.toolbox.automation.session.SessionBlockClassifier
import com.landosol.toolbox.automation.session.SessionBlockKind
import com.landosol.toolbox.labyrinth.vision.EntryPixelRect
import com.landosol.toolbox.labyrinth.vision.LabyrinthBattleElementFilter
import com.landosol.toolbox.labyrinth.vision.LabyrinthEntryFrameResult
import com.landosol.toolbox.labyrinth.vision.LabyrinthCharacterMatch
import com.landosol.toolbox.labyrinth.vision.LabyrinthEntryPageState
import com.landosol.toolbox.labyrinth.vision.LabyrinthBattleTeamObservation
import com.landosol.toolbox.labyrinth.vision.LabyrinthRelicMatch
import com.landosol.toolbox.labyrinth.vision.LabyrinthShopDialogState
import com.landosol.toolbox.labyrinth.node.LabyrinthNodeSession
import com.landosol.toolbox.labyrinth.node.LabyrinthNodeActionPlanner
import com.landosol.toolbox.labyrinth.node.LabyrinthNodeViewportScanner
import com.landosol.toolbox.labyrinth.batch.LabyrinthRunTerminalEvent
import com.landosol.toolbox.labyrinth.node.NodeClassification
import com.landosol.toolbox.labyrinth.node.NodeTemplateSet
import com.landosol.toolbox.protocol.labyrinth.LabyrinthMapNode
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex

/** Composition only: binds scoped workflow ports to the compatibility facade.
 * Legacy backing fields and private entry points preserve existing consumers; workflow-owned
 * recovery state stays private and is connected only through queries and lifecycle/effect calls.
 * Business workflows never receive the facade itself. Only these adapters may cross that boundary.
 */
internal fun LabyrinthEntryRecognitionSession.createBattleRecommendationWorkflow(): LabyrinthBattleRecommendationWorkflow =
    LabyrinthBattleRecommendationWorkflow(object : LabyrinthBattleRecommendationPort {
        override val _state: MutableStateFlow<LabyrinthEntryRecognitionSessionState>
            get() = this@createBattleRecommendationWorkflow._state
        override val actionInFlight: AtomicBoolean
            get() = this@createBattleRecommendationWorkflow.actionInFlight
        override val activeSessionId: AutomationSessionId?
            get() = this@createBattleRecommendationWorkflow.activeSessionId
        override val battleRetryCount: Int
            get() = this@createBattleRecommendationWorkflow.battleRetryCount
        override val battleTeamRecommendationPlanner: LabyrinthBattleTeamRecommendationPlanner?
            get() = this@createBattleRecommendationWorkflow.battleTeamRecommendationPlanner
        override val battleTeamRecommendationUnavailableReason: String?
            get() = this@createBattleRecommendationWorkflow.battleTeamRecommendationUnavailableReason
        override val battleTeamSelectionPlanner: LabyrinthBattleTeamSelectionPlanner?
            get() = this@createBattleRecommendationWorkflow.battleTeamSelectionPlanner
        override var bossEditorBlocked: Boolean
            get() = this@createBattleRecommendationWorkflow.bossEditorBlocked
            set(value) { this@createBattleRecommendationWorkflow.bossEditorBlocked = value }
        override val bossEditorPreparationStage: LabyrinthBossEditorPreparationStage
            get() = this@createBattleRecommendationWorkflow.bossEditorPreparationStage
        override var bossEditorTeamIndex: Int?
            get() = this@createBattleRecommendationWorkflow.bossEditorTeamIndex
            set(value) { this@createBattleRecommendationWorkflow.bossEditorTeamIndex = value }
        override val bossFallbackMultiTeamActive: Boolean
            get() = this@createBattleRecommendationWorkflow.bossFallbackMultiTeamActive
        override var bossMultiTeamTargetCount: Int?
            get() = this@createBattleRecommendationWorkflow.bossMultiTeamTargetCount
            set(value) { this@createBattleRecommendationWorkflow.bossMultiTeamTargetCount = value }
        override val bossTeamMode: LabyrinthBossTeamMode
            get() = this@createBattleRecommendationWorkflow.bossTeamMode
        override var combatContext: LabyrinthCombatContext?
            get() = this@createBattleRecommendationWorkflow.combatContext
            set(value) { this@createBattleRecommendationWorkflow.combatContext = value }
        override val committedBattleCharacterIds: MutableSet<String>
            get() = this@createBattleRecommendationWorkflow.committedBattleCharacterIds
        override val currentBattleTeamSignatures: MutableSet<String>
            get() = this@createBattleRecommendationWorkflow.currentBattleTeamSignatures
        override val currentExEncounterStrategy: LabyrinthExEncounterStrategy?
            get() = this@createBattleRecommendationWorkflow.currentExEncounterStrategy
        override val effectiveCharacterScanStage: LabyrinthEffectiveCharacterScanStage
            get() = this@createBattleRecommendationWorkflow.effectiveCharacterScanStage
        override val effectiveExCharacterIds: MutableSet<String>
            get() = this@createBattleRecommendationWorkflow.effectiveExCharacterIds
        override val exhaustedBattleRosterFilters: MutableSet<com.landosol.toolbox.labyrinth.vision.LabyrinthBattleElementFilter>
            get() = this@createBattleRecommendationWorkflow.exhaustedBattleRosterFilters
        override var exhaustedBattleRosterFiltersKey: String?
            get() = this@createBattleRecommendationWorkflow.exhaustedBattleRosterFiltersKey
            set(value) { this@createBattleRecommendationWorkflow.exhaustedBattleRosterFiltersKey = value }
        override val failedBattleTeamSignatures: MutableSet<String>
            get() = this@createBattleRecommendationWorkflow.failedBattleTeamSignatures
        override var lastBattleTeamRecommendationKey: String?
            get() = this@createBattleRecommendationWorkflow.lastBattleTeamRecommendationKey
            set(value) { this@createBattleRecommendationWorkflow.lastBattleTeamRecommendationKey = value }
        override var lastBattleTeamSelectionPlanLog: String?
            get() = this@createBattleRecommendationWorkflow.lastBattleTeamSelectionPlanLog
            set(value) { this@createBattleRecommendationWorkflow.lastBattleTeamSelectionPlanLog = value }
        override var pendingBossTeamAdvance: Pair<Int, List<String>>?
            get() = this@createBattleRecommendationWorkflow.pendingBossTeamAdvance
            set(value) { this@createBattleRecommendationWorkflow.pendingBossTeamAdvance = value }
        override val preferPureBossDamageSystem: Boolean
            get() = this@createBattleRecommendationWorkflow.preferPureBossDamageSystem
        override var preparedBossFirstTeamIds: List<String>
            get() = this@createBattleRecommendationWorkflow.preparedBossFirstTeamIds
            set(value) { this@createBattleRecommendationWorkflow.preparedBossFirstTeamIds = value }
        override val relicChoicePolicy: LabyrinthRelicChoicePolicy
            get() = this@createBattleRecommendationWorkflow.relicChoicePolicy
        override val relicMarkStackCalibration: Map<LabyrinthRelicMark, Int>
            get() = this@createBattleRecommendationWorkflow.relicMarkStackCalibration
        override val validatedRoute: LabyrinthRouteJson?
            get() = this@createBattleRecommendationWorkflow.validatedRoute
        override fun resetBattleTeamExecutionTracking(): Unit =
            this@createBattleRecommendationWorkflow.battleTeamWorkflow.resetBattleTeamExecutionTracking()
    })

internal fun LabyrinthEntryRecognitionSession.createBattleRosterWorkflow(): LabyrinthBattleRosterWorkflow =
    LabyrinthBattleRosterWorkflow(object : LabyrinthBattleRosterPort {
        override val _state: MutableStateFlow<LabyrinthEntryRecognitionSessionState>
            get() = this@createBattleRosterWorkflow._state
        override val actionExecutor: SessionBoundActionExecutor?
            get() = this@createBattleRosterWorkflow.actionExecutor
        override val actionInFlight: AtomicBoolean
            get() = this@createBattleRosterWorkflow.actionInFlight
        override val activeSessionId: AutomationSessionId?
            get() = this@createBattleRosterWorkflow.activeSessionId
        override val battleTeamRecommendationUnavailableReason: String?
            get() = this@createBattleRosterWorkflow.battleTeamRecommendationUnavailableReason
        override val bossEditorPreparationStage: LabyrinthBossEditorPreparationStage
            get() = this@createBattleRosterWorkflow.bossEditorPreparationStage
        override val combatContext: LabyrinthCombatContext?
            get() = this@createBattleRosterWorkflow.combatContext
        override var effectiveCharacterScanRosterStamp: Long
            get() = this@createBattleRosterWorkflow.effectiveCharacterScanRosterStamp
            set(value) { this@createBattleRosterWorkflow.effectiveCharacterScanRosterStamp = value }
        override var effectiveCharacterScanScrollActions: Int
            get() = this@createBattleRosterWorkflow.effectiveCharacterScanScrollActions
            set(value) { this@createBattleRosterWorkflow.effectiveCharacterScanScrollActions = value }
        override var effectiveCharacterScanSkippedUnsafe: Boolean
            get() = this@createBattleRosterWorkflow.effectiveCharacterScanSkippedUnsafe
            set(value) { this@createBattleRosterWorkflow.effectiveCharacterScanSkippedUnsafe = value }
        override var effectiveCharacterScanStage: LabyrinthEffectiveCharacterScanStage
            get() = this@createBattleRosterWorkflow.effectiveCharacterScanStage
            set(value) { this@createBattleRosterWorkflow.effectiveCharacterScanStage = value }
        override var effectiveCharacterUnsafeStartedAt: Long
            get() = this@createBattleRosterWorkflow.effectiveCharacterUnsafeStartedAt
            set(value) { this@createBattleRosterWorkflow.effectiveCharacterUnsafeStartedAt = value }
        override val effectiveExCharacterIds: MutableSet<String>
            get() = this@createBattleRosterWorkflow.effectiveExCharacterIds
        override val effectiveRosterSearch: LabyrinthBattleRosterSearch
            get() = this@createBattleRosterWorkflow.effectiveRosterSearch
        override var lastBattleTeamRecommendationKey: String?
            get() = this@createBattleRosterWorkflow.lastBattleTeamRecommendationKey
            set(value) { this@createBattleRosterWorkflow.lastBattleTeamRecommendationKey = value }
        override var lastBattleTeamSelectionPlanLog: String?
            get() = this@createBattleRosterWorkflow.lastBattleTeamSelectionPlanLog
            set(value) { this@createBattleRosterWorkflow.lastBattleTeamSelectionPlanLog = value }
        override var lastEffectiveCharacterScanActionAt: Long
            get() = this@createBattleRosterWorkflow.lastEffectiveCharacterScanActionAt
            set(value) { this@createBattleRosterWorkflow.lastEffectiveCharacterScanActionAt = value }
        override fun beginGuardedAction(): Boolean =
            this@createBattleRosterWorkflow.runtimeBeginGuardedAction()
        override fun launchGuardedAction(block: suspend () -> Unit): Unit =
            this@createBattleRosterWorkflow.runtimeLaunchGuardedAction(block = block)
        override fun nodeLog(message: String, warning: Boolean): Unit =
            this@createBattleRosterWorkflow.runtimeNodeLog(message = message, warning = warning)
        override fun publishOverlay(sessionId: AutomationSessionId): Unit =
            this@createBattleRosterWorkflow.runtimePublishOverlay(sessionId = sessionId)
        override fun publishWaitingForGameForeground(sessionId: AutomationSessionId): Unit =
            this@createBattleRosterWorkflow.runtimePublishWaitingForGameForeground(sessionId = sessionId)
        override suspend fun stopFromRunLogic(reason: String): Boolean =
            this@createBattleRosterWorkflow.runtimeStopFromRunLogic(reason = reason)
        override fun traceAction(label: String): Unit =
            this@createBattleRosterWorkflow.runtimeTraceAction(label = label)
    })

internal fun LabyrinthEntryRecognitionSession.createBattleTeamWorkflow(): LabyrinthBattleTeamWorkflow =
    LabyrinthBattleTeamWorkflow(object : LabyrinthBattleTeamPort {
        override val _state: MutableStateFlow<LabyrinthEntryRecognitionSessionState>
            get() = this@createBattleTeamWorkflow._state
        override val actionExecutor: SessionBoundActionExecutor?
            get() = this@createBattleTeamWorkflow.actionExecutor
        override val actionInFlight: AtomicBoolean
            get() = this@createBattleTeamWorkflow.actionInFlight
        override val activeSessionId: AutomationSessionId?
            get() = this@createBattleTeamWorkflow.activeSessionId
        override val battleRosterSearch: LabyrinthBattleRosterSearch
            get() = this@createBattleTeamWorkflow.battleRosterSearch
        override var battleTeamActionStopReason: String?
            get() = this@createBattleTeamWorkflow.battleTeamActionStopReason
            set(value) { this@createBattleTeamWorkflow.battleTeamActionStopReason = value }
        override var battleTeamActionUnconfirmedAttempts: Int
            get() = this@createBattleTeamWorkflow.battleTeamActionUnconfirmedAttempts
            set(value) { this@createBattleTeamWorkflow.battleTeamActionUnconfirmedAttempts = value }
        override val battleTeamDispatchSequence: AtomicLong
            get() = this@createBattleTeamWorkflow.battleTeamDispatchSequence
        override var battleTeamExecutionAttempts: Int
            get() = this@createBattleTeamWorkflow.battleTeamExecutionAttempts
            set(value) { this@createBattleTeamWorkflow.battleTeamExecutionAttempts = value }
        override var battleTeamPageStallSince: Long
            get() = this@createBattleTeamWorkflow.battleTeamPageStallSince
            set(value) { this@createBattleTeamWorkflow.battleTeamPageStallSince = value }
        override val battleTeamRecommendationUnavailableReason: String?
            get() = this@createBattleTeamWorkflow.battleTeamRecommendationUnavailableReason
        override var battleTeamScrollActions: Int
            get() = this@createBattleTeamWorkflow.battleTeamScrollActions
            set(value) { this@createBattleTeamWorkflow.battleTeamScrollActions = value }
        override val battleWait: LabyrinthBattleWaitPolicy
            get() = this@createBattleTeamWorkflow.battleWait
        override var bossEditorBlocked: Boolean
            get() = this@createBattleTeamWorkflow.bossEditorBlocked
            set(value) { this@createBattleTeamWorkflow.bossEditorBlocked = value }
        override var bossEditorPreparationStage: LabyrinthBossEditorPreparationStage
            get() = this@createBattleTeamWorkflow.bossEditorPreparationStage
            set(value) { this@createBattleTeamWorkflow.bossEditorPreparationStage = value }
        override var bossEditorTeamIndex: Int?
            get() = this@createBattleTeamWorkflow.bossEditorTeamIndex
            set(value) { this@createBattleTeamWorkflow.bossEditorTeamIndex = value }
        override var bossMultiTeamTargetCount: Int?
            get() = this@createBattleTeamWorkflow.bossMultiTeamTargetCount
            set(value) { this@createBattleTeamWorkflow.bossMultiTeamTargetCount = value }
        override val bossTeamMode: LabyrinthBossTeamMode
            get() = this@createBattleTeamWorkflow.bossTeamMode
        override val clock: () -> Long
            get() = this@createBattleTeamWorkflow.clock
        override var combatContext: LabyrinthCombatContext?
            get() = this@createBattleTeamWorkflow.combatContext
            set(value) { this@createBattleTeamWorkflow.combatContext = value }
        override val committedBattleCharacterIds: MutableSet<String>
            get() = this@createBattleTeamWorkflow.committedBattleCharacterIds
        override val currentBattleTeamSignatures: MutableSet<String>
            get() = this@createBattleTeamWorkflow.currentBattleTeamSignatures
        override val exhaustedBattleRosterFilters: MutableSet<com.landosol.toolbox.labyrinth.vision.LabyrinthBattleElementFilter>
            get() = this@createBattleTeamWorkflow.exhaustedBattleRosterFilters
        override var lastBattleTeamActionAt: Long
            get() = this@createBattleTeamWorkflow.lastBattleTeamActionAt
            set(value) { this@createBattleTeamWorkflow.lastBattleTeamActionAt = value }
        override var lastBattleTeamExecutionKey: String?
            get() = this@createBattleTeamWorkflow.lastBattleTeamExecutionKey
            set(value) { this@createBattleTeamWorkflow.lastBattleTeamExecutionKey = value }
        override var lastBattleTeamRecommendationKey: String?
            get() = this@createBattleTeamWorkflow.lastBattleTeamRecommendationKey
            set(value) { this@createBattleTeamWorkflow.lastBattleTeamRecommendationKey = value }
        override var lastBattleTeamSelectionPlanLog: String?
            get() = this@createBattleTeamWorkflow.lastBattleTeamSelectionPlanLog
            set(value) { this@createBattleTeamWorkflow.lastBattleTeamSelectionPlanLog = value }
        override var lastNormalBattleTeamSignature: String?
            get() = this@createBattleTeamWorkflow.lastNormalBattleTeamSignature
            set(value) { this@createBattleTeamWorkflow.lastNormalBattleTeamSignature = value }
        override var lastSessionActionAt: Long
            get() = this@createBattleTeamWorkflow.lastSessionActionAt
            set(value) { this@createBattleTeamWorkflow.lastSessionActionAt = value }
        override var pendingBattleTeamAction: PendingBattleTeamAction?
            get() = this@createBattleTeamWorkflow.pendingBattleTeamAction
            set(value) { this@createBattleTeamWorkflow.pendingBattleTeamAction = value }
        override var pendingBossTeamAdvance: Pair<Int, List<String>>?
            get() = this@createBattleTeamWorkflow.pendingBossTeamAdvance
            set(value) { this@createBattleTeamWorkflow.pendingBossTeamAdvance = value }
        override var preparedBossFirstTeamIds: List<String>
            get() = this@createBattleTeamWorkflow.preparedBossFirstTeamIds
            set(value) { this@createBattleTeamWorkflow.preparedBossFirstTeamIds = value }
        override val specialEquipmentFlow: LabyrinthSpecialEquipmentFlow
            get() = this@createBattleTeamWorkflow.specialEquipmentFlow
        override fun anchorRect(result: LabyrinthEntryFrameResult, anchorId: String): EntryPixelRect? =
            this@createBattleTeamWorkflow.pageActionWorkflow.anchorRect(result = result, anchorId = anchorId)
        override fun beginGuardedAction(): Boolean =
            this@createBattleTeamWorkflow.runtimeBeginGuardedAction()
        override fun excludeUnfindableBattleCharacters(
                sessionId: AutomationSessionId,
                unfindable: Set<String>,
                missingLabel: String,
                scannedLabel: String,
            ): Unit =
            this@createBattleTeamWorkflow.battleRosterWorkflow.excludeUnfindableBattleCharacters(sessionId = sessionId, unfindable = unfindable, missingLabel = missingLabel, scannedLabel = scannedLabel)
        override fun finishFromPlanner(sessionId: AutomationSessionId, reason: String): Unit =
            this@createBattleTeamWorkflow.runtimeFinishFromPlanner(sessionId = sessionId, reason = reason)
        override fun handleSpecialEquipmentFlowFrame(
                sessionId: AutomationSessionId,
                result: LabyrinthEntryFrameResult,
                timestampMillis: Long,
            ): Boolean =
            this@createBattleTeamWorkflow.equipmentWorkflow.handleSpecialEquipmentFlowFrame(sessionId = sessionId, result = result, timestampMillis = timestampMillis)
        override fun launchGuardedAction(block: suspend () -> Unit): Unit =
            this@createBattleTeamWorkflow.runtimeLaunchGuardedAction(block = block)
        override fun nodeLog(message: String, warning: Boolean): Unit =
            this@createBattleTeamWorkflow.runtimeNodeLog(message = message, warning = warning)
        override fun publishOverlay(sessionId: AutomationSessionId): Unit =
            this@createBattleTeamWorkflow.runtimePublishOverlay(sessionId = sessionId)
        override fun publishWaitingForGameForeground(sessionId: AutomationSessionId): Unit =
            this@createBattleTeamWorkflow.runtimePublishWaitingForGameForeground(sessionId = sessionId)
        override fun sessionActionIntervalElapsed(timestampMillis: Long): Boolean =
            this@createBattleTeamWorkflow.runtimeSessionActionIntervalElapsed(timestampMillis = timestampMillis)
        override suspend fun stopFromRunLogic(reason: String): Boolean =
            this@createBattleTeamWorkflow.runtimeStopFromRunLogic(reason = reason)
        override fun traceAction(label: String): Unit =
            this@createBattleTeamWorkflow.runtimeTraceAction(label = label)
        override fun traceReject(reason: String): Unit =
            this@createBattleTeamWorkflow.runtimeTraceReject(reason = reason)
        override fun traceWorkflow(owner: String, phase: String): Unit =
            this@createBattleTeamWorkflow.runtimeTraceWorkflow(owner = owner, phase = phase)
    })

internal fun LabyrinthEntryRecognitionSession.createBattleCombatWorkflow(): LabyrinthBattleCombatWorkflow =
    LabyrinthBattleCombatWorkflow(object : LabyrinthBattleCombatPort {
        override val _state: MutableStateFlow<LabyrinthEntryRecognitionSessionState>
            get() = this@createBattleCombatWorkflow._state
        override var activeNodeType: Int?
            get() = this@createBattleCombatWorkflow.activeNodeType
            set(value) { this@createBattleCombatWorkflow.activeNodeType = value }
        override val activeSessionId: AutomationSessionId?
            get() = this@createBattleCombatWorkflow.activeSessionId
        override var battleRetryCount: Int
            get() = this@createBattleCombatWorkflow.battleRetryCount
            set(value) { this@createBattleCombatWorkflow.battleRetryCount = value }
        override var battleTeamActionStopReason: String?
            get() = this@createBattleCombatWorkflow.battleTeamActionStopReason
            set(value) { this@createBattleCombatWorkflow.battleTeamActionStopReason = value }
        override var battleTeamActionUnconfirmedAttempts: Int
            get() = this@createBattleCombatWorkflow.battleTeamActionUnconfirmedAttempts
            set(value) { this@createBattleCombatWorkflow.battleTeamActionUnconfirmedAttempts = value }
        override var battleTeamPageStallSince: Long
            get() = this@createBattleCombatWorkflow.battleTeamPageStallSince
            set(value) { this@createBattleCombatWorkflow.battleTeamPageStallSince = value }
        override val battleWait: LabyrinthBattleWaitPolicy
            get() = this@createBattleCombatWorkflow.battleWait
        override var bossEditorBlocked: Boolean
            get() = this@createBattleCombatWorkflow.bossEditorBlocked
            set(value) { this@createBattleCombatWorkflow.bossEditorBlocked = value }
        override var bossEditorPreparationStage: LabyrinthBossEditorPreparationStage
            get() = this@createBattleCombatWorkflow.bossEditorPreparationStage
            set(value) { this@createBattleCombatWorkflow.bossEditorPreparationStage = value }
        override var bossEditorTeamIndex: Int?
            get() = this@createBattleCombatWorkflow.bossEditorTeamIndex
            set(value) { this@createBattleCombatWorkflow.bossEditorTeamIndex = value }
        override var bossFallbackMultiTeamActive: Boolean
            get() = this@createBattleCombatWorkflow.bossFallbackMultiTeamActive
            set(value) { this@createBattleCombatWorkflow.bossFallbackMultiTeamActive = value }
        override var bossMultiTeamTargetCount: Int?
            get() = this@createBattleCombatWorkflow.bossMultiTeamTargetCount
            set(value) { this@createBattleCombatWorkflow.bossMultiTeamTargetCount = value }
        override var bossTeamMode: LabyrinthBossTeamMode
            get() = this@createBattleCombatWorkflow.bossTeamMode
            set(value) { this@createBattleCombatWorkflow.bossTeamMode = value }
        override var combatContext: LabyrinthCombatContext?
            get() = this@createBattleCombatWorkflow.combatContext
            set(value) { this@createBattleCombatWorkflow.combatContext = value }
        override val committedBattleCharacterIds: MutableSet<String>
            get() = this@createBattleCombatWorkflow.committedBattleCharacterIds
        override val configuredBossTeamMode: LabyrinthBossTeamMode
            get() = this@createBattleCombatWorkflow.configuredBossTeamMode
        override val currentBattleTeamSignatures: MutableSet<String>
            get() = this@createBattleCombatWorkflow.currentBattleTeamSignatures
        override val currentExEncounterStrategy: LabyrinthExEncounterStrategy?
            get() = this@createBattleCombatWorkflow.currentExEncounterStrategy
        override val currentRunId: String?
            get() = this@createBattleCombatWorkflow.currentRunId
        override val exEncounterFallbackApproved: Boolean
            get() = this@createBattleCombatWorkflow.exEncounterFallbackApproved
        override val failedBattleTeamSignatures: MutableSet<String>
            get() = this@createBattleCombatWorkflow.failedBattleTeamSignatures
        override var lastBattleTeamRecommendationKey: String?
            get() = this@createBattleCombatWorkflow.lastBattleTeamRecommendationKey
            set(value) { this@createBattleCombatWorkflow.lastBattleTeamRecommendationKey = value }
        override var lastBattleTeamSelectionPlanLog: String?
            get() = this@createBattleCombatWorkflow.lastBattleTeamSelectionPlanLog
            set(value) { this@createBattleCombatWorkflow.lastBattleTeamSelectionPlanLog = value }
        override var lastNormalBattleTeamSignature: String?
            get() = this@createBattleCombatWorkflow.lastNormalBattleTeamSignature
            set(value) { this@createBattleCombatWorkflow.lastNormalBattleTeamSignature = value }
        override var lastPostEntryState: LabyrinthEntryPageState?
            get() = this@createBattleCombatWorkflow.lastPostEntryState
            set(value) { this@createBattleCombatWorkflow.lastPostEntryState = value }
        override var pendingBattleTeamAction: PendingBattleTeamAction?
            get() = this@createBattleCombatWorkflow.pendingBattleTeamAction
            set(value) { this@createBattleCombatWorkflow.pendingBattleTeamAction = value }
        override var pendingBossTeamAdvance: Pair<Int, List<String>>?
            get() = this@createBattleCombatWorkflow.pendingBossTeamAdvance
            set(value) { this@createBattleCombatWorkflow.pendingBossTeamAdvance = value }
        override var pendingSingleBossFallbackToMulti: Boolean
            get() = this@createBattleCombatWorkflow.pendingSingleBossFallbackToMulti
            set(value) { this@createBattleCombatWorkflow.pendingSingleBossFallbackToMulti = value }
        override var postEntryAttempts: Int
            get() = this@createBattleCombatWorkflow.postEntryAttempts
            set(value) { this@createBattleCombatWorkflow.postEntryAttempts = value }
        override var postEntryStableFrames: Int
            get() = this@createBattleCombatWorkflow.postEntryStableFrames
            set(value) { this@createBattleCombatWorkflow.postEntryStableFrames = value }
        override var postEntryUnknownSince: Long
            get() = this@createBattleCombatWorkflow.postEntryUnknownSince
            set(value) { this@createBattleCombatWorkflow.postEntryUnknownSince = value }
        override var preparedBossFirstTeamIds: List<String>
            get() = this@createBattleCombatWorkflow.preparedBossFirstTeamIds
            set(value) { this@createBattleCombatWorkflow.preparedBossFirstTeamIds = value }
        override val rerollAfterThreeBattleFailures: Boolean
            get() = this@createBattleCombatWorkflow.rerollAfterThreeBattleFailures
        override val singleBossFallbackToMultiAfterThreeFailures: Boolean
            get() = this@createBattleCombatWorkflow.singleBossFallbackToMultiAfterThreeFailures
        override val singleBossRetryCountBeforeMulti: Int
            get() = this@createBattleCombatWorkflow.singleBossRetryCountBeforeMulti
        override val validatedRoute: LabyrinthRouteJson?
            get() = this@createBattleCombatWorkflow.validatedRoute
        override var waitingForManualRoleSelection: Boolean
            get() = this@createBattleCombatWorkflow.waitingForManualRoleSelection
            set(value) { this@createBattleCombatWorkflow.waitingForManualRoleSelection = value }
        override fun acknowledgeBattleTeamActionProgress(result: LabyrinthEntryFrameResult): Unit =
            this@createBattleCombatWorkflow.battleTeamWorkflow.acknowledgeBattleTeamActionProgress(result = result)
        override fun anchorRect(result: LabyrinthEntryFrameResult, anchorId: String): EntryPixelRect? =
            this@createBattleCombatWorkflow.pageActionWorkflow.anchorRect(result = result, anchorId = anchorId)
        override fun clearCharacterAcquisitionContext(): Unit =
            this@createBattleCombatWorkflow.rewardWorkflow.clearCharacterAcquisitionContext()
        override fun finishFromPlanner(sessionId: AutomationSessionId, reason: String): Unit =
            this@createBattleCombatWorkflow.runtimeFinishFromPlanner(sessionId = sessionId, reason = reason)
        override fun finishFromPlannerAndRequestReroll(
                sessionId: AutomationSessionId,
                reason: String,
            ): Unit =
            this@createBattleCombatWorkflow.settlementWorkflow.finishFromPlannerAndRequestReroll(sessionId = sessionId, reason = reason)
        override fun handleUnconfirmedBattleTeamActionFrame(
                sessionId: AutomationSessionId,
                result: LabyrinthEntryFrameResult,
                timestampMillis: Long,
            ): Boolean =
            this@createBattleCombatWorkflow.battleTeamWorkflow.handleUnconfirmedBattleTeamActionFrame(sessionId = sessionId, result = result, timestampMillis = timestampMillis)
        override fun publishOverlay(sessionId: AutomationSessionId): Unit =
            this@createBattleCombatWorkflow.runtimePublishOverlay(sessionId = sessionId)
        override fun resetBattleTeamExecutionTracking(): Unit =
            this@createBattleCombatWorkflow.battleTeamWorkflow.resetBattleTeamExecutionTracking()
        override fun resetPostEntryStall(): Unit =
            this@createBattleCombatWorkflow.pageRouterWorkflow.resetPostEntryStall()
        override fun resetSpecialEquipmentRound(): Unit =
            this@createBattleCombatWorkflow.equipmentWorkflow.resetSpecialEquipmentRound()
        override fun traceWorkflow(owner: String, phase: String): Unit =
            this@createBattleCombatWorkflow.runtimeTraceWorkflow(owner = owner, phase = phase)
    })

internal fun LabyrinthEntryRecognitionSession.createEncounterWorkflow(): LabyrinthEncounterWorkflow =
    LabyrinthEncounterWorkflow(object : LabyrinthEncounterPort {
        override val _state: MutableStateFlow<LabyrinthEntryRecognitionSessionState>
            get() = this@createEncounterWorkflow._state
        override var activeNodeArea: Int?
            get() = this@createEncounterWorkflow.activeNodeArea
            set(value) { this@createEncounterWorkflow.activeNodeArea = value }
        override var activeNodeType: Int?
            get() = this@createEncounterWorkflow.activeNodeType
            set(value) { this@createEncounterWorkflow.activeNodeType = value }
        override val activeSessionId: AutomationSessionId?
            get() = this@createEncounterWorkflow.activeSessionId
        override var combatContext: LabyrinthCombatContext?
            get() = this@createEncounterWorkflow.combatContext
            set(value) { this@createEncounterWorkflow.combatContext = value }
        override var currentExEncounterStrategy: LabyrinthExEncounterStrategy?
            get() = this@createEncounterWorkflow.currentExEncounterStrategy
            set(value) { this@createEncounterWorkflow.currentExEncounterStrategy = value }
        override val effectiveExCharacterIds: MutableSet<String>
            get() = this@createEncounterWorkflow.effectiveExCharacterIds
        override var exEncounterFallbackApproved: Boolean
            get() = this@createEncounterWorkflow.exEncounterFallbackApproved
            set(value) { this@createEncounterWorkflow.exEncounterFallbackApproved = value }
        override var exEncounterProbeStartedAt: Long
            get() = this@createEncounterWorkflow.exEncounterProbeStartedAt
            set(value) { this@createEncounterWorkflow.exEncounterProbeStartedAt = value }
        override var exIdentityProbeDescription: String
            get() = this@createEncounterWorkflow.exIdentityProbeDescription
            set(value) { this@createEncounterWorkflow.exIdentityProbeDescription = value }
        override var exLastKnownPage: LabyrinthEntryPageState?
            get() = this@createEncounterWorkflow.exLastKnownPage
            set(value) { this@createEncounterWorkflow.exLastKnownPage = value }
        override var exSlot3ProbeAttempts: Int
            get() = this@createEncounterWorkflow.exSlot3ProbeAttempts
            set(value) { this@createEncounterWorkflow.exSlot3ProbeAttempts = value }
        override var exSlot3ProbePending: Boolean
            get() = this@createEncounterWorkflow.exSlot3ProbePending
            set(value) { this@createEncounterWorkflow.exSlot3ProbePending = value }
        override var lastBattleTeamRecommendationKey: String?
            get() = this@createEncounterWorkflow.lastBattleTeamRecommendationKey
            set(value) { this@createEncounterWorkflow.lastBattleTeamRecommendationKey = value }
        override val lastPostEntryActionAt: Long
            get() = this@createEncounterWorkflow.lastPostEntryActionAt
        override val postEntryStableFrames: Int
            get() = this@createEncounterWorkflow.postEntryStableFrames
        override val validatedRoute: LabyrinthRouteJson?
            get() = this@createEncounterWorkflow.validatedRoute
        override fun dispatchPostEntryTap(
                sessionId: AutomationSessionId,
                kind: LabyrinthPostEntryActionKind,
                label: String,
                rect: EntryPixelRect,
                timestampMillis: Long,
                roleRewardSelectionSignature: String?,
                roleRewardSelectedCharacterId: String?,
                shopPurchaseRelicId: String?,
                shopPurchaseRoleImprintLabel: String?,
                eventFreeRoleCandidateId: String?,
                eventFreeRoleConfirm: Boolean,
                eventFreeRoleConfirmIds: List<String>,
            ): Unit =
            this@createEncounterWorkflow.pageActionWorkflow.dispatchPostEntryTap(sessionId = sessionId, kind = kind, label = label, rect = rect, timestampMillis = timestampMillis, roleRewardSelectionSignature = roleRewardSelectionSignature, roleRewardSelectedCharacterId = roleRewardSelectedCharacterId, shopPurchaseRelicId = shopPurchaseRelicId, shopPurchaseRoleImprintLabel = shopPurchaseRoleImprintLabel, eventFreeRoleCandidateId = eventFreeRoleCandidateId, eventFreeRoleConfirm = eventFreeRoleConfirm, eventFreeRoleConfirmIds = eventFreeRoleConfirmIds)
        override fun finishFromPlanner(sessionId: AutomationSessionId, reason: String): Unit =
            this@createEncounterWorkflow.runtimeFinishFromPlanner(sessionId = sessionId, reason = reason)
        override fun nodeLog(message: String, warning: Boolean): Unit =
            this@createEncounterWorkflow.runtimeNodeLog(message = message, warning = warning)
        override fun traceWorkflow(owner: String, phase: String): Unit =
            this@createEncounterWorkflow.runtimeTraceWorkflow(owner = owner, phase = phase)
    })

internal fun LabyrinthEntryRecognitionSession.createEquipmentWorkflow(): LabyrinthEquipmentWorkflow =
    LabyrinthEquipmentWorkflow(object : LabyrinthEquipmentPort {
        override val _state: MutableStateFlow<LabyrinthEntryRecognitionSessionState>
            get() = this@createEquipmentWorkflow._state
        override val actionExecutor: SessionBoundActionExecutor?
            get() = this@createEquipmentWorkflow.actionExecutor
        override val activeSessionId: AutomationSessionId?
            get() = this@createEquipmentWorkflow.activeSessionId
        override val battleTeamDispatchSequence: AtomicLong
            get() = this@createEquipmentWorkflow.battleTeamDispatchSequence
        override val bossTeamMode: LabyrinthBossTeamMode
            get() = this@createEquipmentWorkflow.bossTeamMode
        override val combatContext: LabyrinthCombatContext?
            get() = this@createEquipmentWorkflow.combatContext
        override val lastNormalBattleTeamSignature: String?
            get() = this@createEquipmentWorkflow.lastNormalBattleTeamSignature
        override var lastSessionActionAt: Long
            get() = this@createEquipmentWorkflow.lastSessionActionAt
            set(value) { this@createEquipmentWorkflow.lastSessionActionAt = value }
        override val specialEquipmentFlow: LabyrinthSpecialEquipmentFlow
            get() = this@createEquipmentWorkflow.specialEquipmentFlow
        override val specialEquipmentSettings: LabyrinthStrategySettings?
            get() = this@createEquipmentWorkflow.specialEquipmentSettings
        override fun beginGuardedAction(): Boolean =
            this@createEquipmentWorkflow.runtimeBeginGuardedAction()
        override fun finishFromPlanner(sessionId: AutomationSessionId, reason: String): Unit =
            this@createEquipmentWorkflow.runtimeFinishFromPlanner(sessionId = sessionId, reason = reason)
        override fun launchGuardedAction(block: suspend () -> Unit): Unit =
            this@createEquipmentWorkflow.runtimeLaunchGuardedAction(block = block)
        override fun publishOverlay(sessionId: AutomationSessionId): Unit =
            this@createEquipmentWorkflow.runtimePublishOverlay(sessionId = sessionId)
        override fun publishWaitingForGameForeground(sessionId: AutomationSessionId): Unit =
            this@createEquipmentWorkflow.runtimePublishWaitingForGameForeground(sessionId = sessionId)
        override fun sessionActionIntervalElapsed(timestampMillis: Long): Boolean =
            this@createEquipmentWorkflow.runtimeSessionActionIntervalElapsed(timestampMillis = timestampMillis)
        override suspend fun stopFromRunLogic(reason: String): Boolean =
            this@createEquipmentWorkflow.runtimeStopFromRunLogic(reason = reason)
        override fun traceAction(label: String): Unit =
            this@createEquipmentWorkflow.runtimeTraceAction(label = label)
        override fun traceWorkflow(owner: String, phase: String): Unit =
            this@createEquipmentWorkflow.runtimeTraceWorkflow(owner = owner, phase = phase)
    })

internal fun LabyrinthEntryRecognitionSession.createRewardWorkflow(): LabyrinthRewardWorkflow =
    LabyrinthRewardWorkflow(object : LabyrinthRewardPort {
        override val _state: MutableStateFlow<LabyrinthEntryRecognitionSessionState>
            get() = this@createRewardWorkflow._state
        override var activeNodeType: Int?
            get() = this@createRewardWorkflow.activeNodeType
            set(value) { this@createRewardWorkflow.activeNodeType = value }
        override val activeRunAccountId: Long?
            get() = this@createRewardWorkflow.activeRunAccountId
        override val activeSessionId: AutomationSessionId?
            get() = this@createRewardWorkflow.activeSessionId
        override var characterAcquisitionActive: Boolean
            get() = this@createRewardWorkflow.characterAcquisitionActive
            set(value) { this@createRewardWorkflow.characterAcquisitionActive = value }
        override var characterAcquisitionClicks: Int
            get() = this@createRewardWorkflow.characterAcquisitionClicks
            set(value) { this@createRewardWorkflow.characterAcquisitionClicks = value }
        override var characterAcquisitionStartedAt: Long
            get() = this@createRewardWorkflow.characterAcquisitionStartedAt
            set(value) { this@createRewardWorkflow.characterAcquisitionStartedAt = value }
        override val clock: () -> Long
            get() = this@createRewardWorkflow.clock
        override var committedRoleRewardSelectionSignature: String?
            get() = this@createRewardWorkflow.committedRoleRewardSelectionSignature
            set(value) { this@createRewardWorkflow.committedRoleRewardSelectionSignature = value }
        override var lastCharacterAcquisitionActionAt: Long
            get() = this@createRewardWorkflow.lastCharacterAcquisitionActionAt
            set(value) { this@createRewardWorkflow.lastCharacterAcquisitionActionAt = value }
        override var lastObservedRoleRewardSelectionSignature: String?
            get() = this@createRewardWorkflow.lastObservedRoleRewardSelectionSignature
            set(value) { this@createRewardWorkflow.lastObservedRoleRewardSelectionSignature = value }
        override var pendingAcquiredCharacterId: String?
            get() = this@createRewardWorkflow.pendingAcquiredCharacterId
            set(value) { this@createRewardWorkflow.pendingAcquiredCharacterId = value }
        override val pendingAcquiredCharacterIds: MutableSet<String>
            get() = this@createRewardWorkflow.pendingAcquiredCharacterIds
        override var pendingRelicSelection: LabyrinthRelicMatch?
            get() = this@createRewardWorkflow.pendingRelicSelection
            set(value) { this@createRewardWorkflow.pendingRelicSelection = value }
        override val persistenceMutex: Mutex
            get() = this@createRewardWorkflow.persistenceMutex
        override val postBossStage: LabyrinthPostBossStage
            get() = this@createRewardWorkflow.postBossStage
        override var postEntryAttempts: Int
            get() = this@createRewardWorkflow.postEntryAttempts
            set(value) { this@createRewardWorkflow.postEntryAttempts = value }
        override val postEntryStableFrames: Int
            get() = this@createRewardWorkflow.postEntryStableFrames
        override val relicAcquisitionGate: LabyrinthRelicAcquisitionGate
            get() = this@createRewardWorkflow.relicAcquisitionGate
        override var relicChoiceCommitAttempts: Int
            get() = this@createRewardWorkflow.relicChoiceCommitAttempts
            set(value) { this@createRewardWorkflow.relicChoiceCommitAttempts = value }
        override var relicChoiceCommitted: Boolean
            get() = this@createRewardWorkflow.relicChoiceCommitted
            set(value) { this@createRewardWorkflow.relicChoiceCommitted = value }
        override var relicChoiceCommittedAt: Long
            get() = this@createRewardWorkflow.relicChoiceCommittedAt
            set(value) { this@createRewardWorkflow.relicChoiceCommittedAt = value }
        override val relicChoicePolicy: LabyrinthRelicChoicePolicy
            get() = this@createRewardWorkflow.relicChoicePolicy
        override var relicFocusMark: LabyrinthRelicMark?
            get() = this@createRewardWorkflow.relicFocusMark
            set(value) { this@createRewardWorkflow.relicFocusMark = value }
        override val relicMarkStackCalibration: Map<LabyrinthRelicMark, Int>
            get() = this@createRewardWorkflow.relicMarkStackCalibration
        override val relicStackLedger: LabyrinthRelicStackLedger
            get() = this@createRewardWorkflow.relicStackLedger
        override var roleRewardBatchActive: Boolean
            get() = this@createRewardWorkflow.roleRewardBatchActive
            set(value) { this@createRewardWorkflow.roleRewardBatchActive = value }
        override var roleRewardChoiceCommitted: Boolean
            get() = this@createRewardWorkflow.roleRewardChoiceCommitted
            set(value) { this@createRewardWorkflow.roleRewardChoiceCommitted = value }
        override val roleRewardChoicePlanner: LabyrinthRoleRewardChoicePlanner?
            get() = this@createRewardWorkflow.roleRewardChoicePlanner
        override val roleRewardChoiceUnavailableReason: String?
            get() = this@createRewardWorkflow.roleRewardChoiceUnavailableReason
        override var roleRewardDecisionCacheKey: String?
            get() = this@createRewardWorkflow.roleRewardDecisionCacheKey
            set(value) { this@createRewardWorkflow.roleRewardDecisionCacheKey = value }
        override var roleRewardJoinedSequenceStarted: Boolean
            get() = this@createRewardWorkflow.roleRewardJoinedSequenceStarted
            set(value) { this@createRewardWorkflow.roleRewardJoinedSequenceStarted = value }
        override val runStateStore: LabyrinthRunStateStore?
            get() = this@createRewardWorkflow.runStateStore
        override var skipRoleRewardJoinedRecognition: Boolean
            get() = this@createRewardWorkflow.skipRoleRewardJoinedRecognition
            set(value) { this@createRewardWorkflow.skipRoleRewardJoinedRecognition = value }
        override var waitingForManualRoleSelection: Boolean
            get() = this@createRewardWorkflow.waitingForManualRoleSelection
            set(value) { this@createRewardWorkflow.waitingForManualRoleSelection = value }
        override fun anchorRect(result: LabyrinthEntryFrameResult, anchorId: String): EntryPixelRect? =
            this@createRewardWorkflow.pageActionWorkflow.anchorRect(result = result, anchorId = anchorId)
        override fun finishFromPlanner(sessionId: AutomationSessionId, reason: String): Unit =
            this@createRewardWorkflow.runtimeFinishFromPlanner(sessionId = sessionId, reason = reason)
        override fun nodeLog(message: String, warning: Boolean): Unit =
            this@createRewardWorkflow.runtimeNodeLog(message = message, warning = warning)
    })

internal fun LabyrinthEntryRecognitionSession.createEventWorkflow(): LabyrinthEventWorkflow =
    LabyrinthEventWorkflow(object : LabyrinthEventPort {
        override val _state: MutableStateFlow<LabyrinthEntryRecognitionSessionState>
            get() = this@createEventWorkflow._state
        override val actionInFlight: AtomicBoolean
            get() = this@createEventWorkflow.actionInFlight
        override var activeNodeArea: Int?
            get() = this@createEventWorkflow.activeNodeArea
            set(value) { this@createEventWorkflow.activeNodeArea = value }
        override var activeNodeType: Int?
            get() = this@createEventWorkflow.activeNodeType
            set(value) { this@createEventWorkflow.activeNodeType = value }
        override val activeSessionId: AutomationSessionId?
            get() = this@createEventWorkflow.activeSessionId
        override val characterAcquisitionActive: Boolean
            get() = this@createEventWorkflow.characterAcquisitionActive
        override val clock: () -> Long
            get() = this@createEventWorkflow.clock
        override val combatContext: LabyrinthCombatContext?
            get() = this@createEventWorkflow.combatContext
        override var eventActionAttempts: Int
            get() = this@createEventWorkflow.eventActionAttempts
            set(value) { this@createEventWorkflow.eventActionAttempts = value }
        override var eventChoiceCommitAttempts: Int
            get() = this@createEventWorkflow.eventChoiceCommitAttempts
            set(value) { this@createEventWorkflow.eventChoiceCommitAttempts = value }
        override var eventChoiceCommitted: Boolean
            get() = this@createEventWorkflow.eventChoiceCommitted
            set(value) { this@createEventWorkflow.eventChoiceCommitted = value }
        override var eventChoiceCommittedAt: Long
            get() = this@createEventWorkflow.eventChoiceCommittedAt
            set(value) { this@createEventWorkflow.eventChoiceCommittedAt = value }
        override var eventChoiceCommittedChoiceId: String?
            get() = this@createEventWorkflow.eventChoiceCommittedChoiceId
            set(value) { this@createEventWorkflow.eventChoiceCommittedChoiceId = value }
        override val eventChoicePlanner: LabyrinthEventChoicePlanner?
            get() = this@createEventWorkflow.eventChoicePlanner
        override val eventChoiceRejectedIds: MutableSet<String>
            get() = this@createEventWorkflow.eventChoiceRejectedIds
        override var eventFreeRoleBlockedSince: Long
            get() = this@createEventWorkflow.eventFreeRoleBlockedSince
            set(value) { this@createEventWorkflow.eventFreeRoleBlockedSince = value }
        override var eventFreeRoleLastSelectAt: Long
            get() = this@createEventWorkflow.eventFreeRoleLastSelectAt
            set(value) { this@createEventWorkflow.eventFreeRoleLastSelectAt = value }
        override var eventFreeRoleObservedSelectedCount: Int
            get() = this@createEventWorkflow.eventFreeRoleObservedSelectedCount
            set(value) { this@createEventWorkflow.eventFreeRoleObservedSelectedCount = value }
        override val eventFreeRoleSelectedCharacterIds: MutableSet<String>
            get() = this@createEventWorkflow.eventFreeRoleSelectedCharacterIds
        override val lastPostEntryActionAt: Long
            get() = this@createEventWorkflow.lastPostEntryActionAt
        override val lastPostEntryActionLandedAt: Long
            get() = this@createEventWorkflow.lastPostEntryActionLandedAt
        override var pendingAcquiredCharacterId: String?
            get() = this@createEventWorkflow.pendingAcquiredCharacterId
            set(value) { this@createEventWorkflow.pendingAcquiredCharacterId = value }
        override val pendingAcquiredCharacterIds: MutableSet<String>
            get() = this@createEventWorkflow.pendingAcquiredCharacterIds
        override val relicChoicePolicy: LabyrinthRelicChoicePolicy
            get() = this@createEventWorkflow.relicChoicePolicy
        override val relicMarkStackCalibration: Map<LabyrinthRelicMark, Int>
            get() = this@createEventWorkflow.relicMarkStackCalibration
        override val roleRewardChoicePlanner: LabyrinthRoleRewardChoicePlanner?
            get() = this@createEventWorkflow.roleRewardChoicePlanner
        override val roleRewardChoiceUnavailableReason: String?
            get() = this@createEventWorkflow.roleRewardChoiceUnavailableReason
        override val shopChoiceImprintRolePending: Boolean
            get() = this@createEventWorkflow.shopChoiceImprintRolePending
        override val validatedRoute: LabyrinthRouteJson?
            get() = this@createEventWorkflow.validatedRoute
        override fun anchorRect(result: LabyrinthEntryFrameResult, anchorId: String): EntryPixelRect? =
            this@createEventWorkflow.pageActionWorkflow.anchorRect(result = result, anchorId = anchorId)
        override fun clearShopChoiceImprintRolePending(): Unit =
            this@createEventWorkflow.shopWorkflow.clearShopChoiceImprintRolePending()
        override fun dispatchPostEntryTap(
                sessionId: AutomationSessionId,
                kind: LabyrinthPostEntryActionKind,
                label: String,
                rect: EntryPixelRect,
                timestampMillis: Long,
                roleRewardSelectionSignature: String?,
                roleRewardSelectedCharacterId: String?,
                shopPurchaseRelicId: String?,
                shopPurchaseRoleImprintLabel: String?,
                eventFreeRoleCandidateId: String?,
                eventFreeRoleConfirm: Boolean,
                eventFreeRoleConfirmIds: List<String>,
            ): Unit =
            this@createEventWorkflow.pageActionWorkflow.dispatchPostEntryTap(sessionId = sessionId, kind = kind, label = label, rect = rect, timestampMillis = timestampMillis, roleRewardSelectionSignature = roleRewardSelectionSignature, roleRewardSelectedCharacterId = roleRewardSelectedCharacterId, shopPurchaseRelicId = shopPurchaseRelicId, shopPurchaseRoleImprintLabel = shopPurchaseRoleImprintLabel, eventFreeRoleCandidateId = eventFreeRoleCandidateId, eventFreeRoleConfirm = eventFreeRoleConfirm, eventFreeRoleConfirmIds = eventFreeRoleConfirmIds)
        override fun finishFromPlanner(sessionId: AutomationSessionId, reason: String): Unit =
            this@createEventWorkflow.runtimeFinishFromPlanner(sessionId = sessionId, reason = reason)
        override fun nodeLog(message: String, warning: Boolean): Unit =
            this@createEventWorkflow.runtimeNodeLog(message = message, warning = warning)
        override fun postEntryIsRoleRewardPage(result: LabyrinthEntryFrameResult): Boolean =
            this@createEventWorkflow.rewardWorkflow.postEntryIsRoleRewardPage(result = result)
        override fun traceWorkflow(owner: String, phase: String): Unit =
            this@createEventWorkflow.runtimeTraceWorkflow(owner = owner, phase = phase)
    })

internal fun LabyrinthEntryRecognitionSession.createShopWorkflow(): LabyrinthShopWorkflow =
    LabyrinthShopWorkflow(object : LabyrinthShopPort {
        override val _state: MutableStateFlow<LabyrinthEntryRecognitionSessionState>
            get() = this@createShopWorkflow._state
        override val activeNodeArea: Int?
            get() = this@createShopWorkflow.activeNodeArea
        override var activeNodeType: Int?
            get() = this@createShopWorkflow.activeNodeType
            set(value) { this@createShopWorkflow.activeNodeType = value }
        override val activeSessionId: AutomationSessionId?
            get() = this@createShopWorkflow.activeSessionId
        override val clock: () -> Long
            get() = this@createShopWorkflow.clock
        override val lastPostEntryActionAt: Long
            get() = this@createShopWorkflow.lastPostEntryActionAt
        override var lastShopDialogState: LabyrinthShopDialogState
            get() = this@createShopWorkflow.lastShopDialogState
            set(value) { this@createShopWorkflow.lastShopDialogState = value }
        override var pendingShopPurchase: LabyrinthRelicMatch?
            get() = this@createShopWorkflow.pendingShopPurchase
            set(value) { this@createShopWorkflow.pendingShopPurchase = value }
        override var plannedShopPurchaseCounted: Boolean
            get() = this@createShopWorkflow.plannedShopPurchaseCounted
            set(value) { this@createShopWorkflow.plannedShopPurchaseCounted = value }
        override var plannedShopPurchaseRelicId: String?
            get() = this@createShopWorkflow.plannedShopPurchaseRelicId
            set(value) { this@createShopWorkflow.plannedShopPurchaseRelicId = value }
        override var plannedShopPurchaseStartedAt: Long
            get() = this@createShopWorkflow.plannedShopPurchaseStartedAt
            set(value) { this@createShopWorkflow.plannedShopPurchaseStartedAt = value }
        override var plannedShopRefreshConfirmedAt: Long
            get() = this@createShopWorkflow.plannedShopRefreshConfirmedAt
            set(value) { this@createShopWorkflow.plannedShopRefreshConfirmedAt = value }
        override var plannedShopRefreshStartedAt: Long
            get() = this@createShopWorkflow.plannedShopRefreshStartedAt
            set(value) { this@createShopWorkflow.plannedShopRefreshStartedAt = value }
        override var plannedShopRoleImprintLabel: String?
            get() = this@createShopWorkflow.plannedShopRoleImprintLabel
            set(value) { this@createShopWorkflow.plannedShopRoleImprintLabel = value }
        override var postEntryAttempts: Int
            get() = this@createShopWorkflow.postEntryAttempts
            set(value) { this@createShopWorkflow.postEntryAttempts = value }
        override var postEntryStableFrames: Int
            get() = this@createShopWorkflow.postEntryStableFrames
            set(value) { this@createShopWorkflow.postEntryStableFrames = value }
        override var relicFocusMark: LabyrinthRelicMark?
            get() = this@createShopWorkflow.relicFocusMark
            set(value) { this@createShopWorkflow.relicFocusMark = value }
        override val relicMarkStackCalibration: Map<LabyrinthRelicMark, Int>
            get() = this@createShopWorkflow.relicMarkStackCalibration
        override var shopChoiceImprintRolePending: Boolean
            get() = this@createShopWorkflow.shopChoiceImprintRolePending
            set(value) { this@createShopWorkflow.shopChoiceImprintRolePending = value }
        override var shopImprintRewardConfirmedAt: Long
            get() = this@createShopWorkflow.shopImprintRewardConfirmedAt
            set(value) { this@createShopWorkflow.shopImprintRewardConfirmedAt = value }
        override val shopPolicy: LabyrinthShopPolicy
            get() = this@createShopWorkflow.shopPolicy
        override var shopRelicPurchasesThisCycle: Int
            get() = this@createShopWorkflow.shopRelicPurchasesThisCycle
            set(value) { this@createShopWorkflow.shopRelicPurchasesThisCycle = value }
        override fun anchorRect(result: LabyrinthEntryFrameResult, anchorId: String): EntryPixelRect? =
            this@createShopWorkflow.pageActionWorkflow.anchorRect(result = result, anchorId = anchorId)
        override fun dispatchPostEntryTap(
                sessionId: AutomationSessionId,
                kind: LabyrinthPostEntryActionKind,
                label: String,
                rect: EntryPixelRect,
                timestampMillis: Long,
                roleRewardSelectionSignature: String?,
                roleRewardSelectedCharacterId: String?,
                shopPurchaseRelicId: String?,
                shopPurchaseRoleImprintLabel: String?,
                eventFreeRoleCandidateId: String?,
                eventFreeRoleConfirm: Boolean,
                eventFreeRoleConfirmIds: List<String>,
            ): Unit =
            this@createShopWorkflow.pageActionWorkflow.dispatchPostEntryTap(sessionId = sessionId, kind = kind, label = label, rect = rect, timestampMillis = timestampMillis, roleRewardSelectionSignature = roleRewardSelectionSignature, roleRewardSelectedCharacterId = roleRewardSelectedCharacterId, shopPurchaseRelicId = shopPurchaseRelicId, shopPurchaseRoleImprintLabel = shopPurchaseRoleImprintLabel, eventFreeRoleCandidateId = eventFreeRoleCandidateId, eventFreeRoleConfirm = eventFreeRoleConfirm, eventFreeRoleConfirmIds = eventFreeRoleConfirmIds)
        override fun publishOverlay(sessionId: AutomationSessionId): Unit =
            this@createShopWorkflow.runtimePublishOverlay(sessionId = sessionId)
        override fun traceWorkflow(owner: String, phase: String): Unit =
            this@createShopWorkflow.runtimeTraceWorkflow(owner = owner, phase = phase)
    })

internal fun LabyrinthEntryRecognitionSession.createSettlementWorkflow(): LabyrinthSettlementWorkflow =
    LabyrinthSettlementWorkflow(object : LabyrinthSettlementPort {
        override val _state: MutableStateFlow<LabyrinthEntryRecognitionSessionState>
            get() = this@createSettlementWorkflow._state
        override val actionScope: CoroutineScope
            get() = this@createSettlementWorkflow.actionScope
        override val activeRunAccountId: Long?
            get() = this@createSettlementWorkflow.activeRunAccountId
        override val activeSessionId: AutomationSessionId?
            get() = this@createSettlementWorkflow.activeSessionId
        override var combatContext: LabyrinthCombatContext?
            get() = this@createSettlementWorkflow.combatContext
            set(value) { this@createSettlementWorkflow.combatContext = value }
        override var currentRunId: String?
            get() = this@createSettlementWorkflow.currentRunId
            set(value) { this@createSettlementWorkflow.currentRunId = value }
        override var pendingTerminalOutcome: LabyrinthRunTerminalOutcome?
            get() = this@createSettlementWorkflow.pendingTerminalOutcome
            set(value) { this@createSettlementWorkflow.pendingTerminalOutcome = value }
        override var postBossStage: LabyrinthPostBossStage
            get() = this@createSettlementWorkflow.postBossStage
            set(value) { this@createSettlementWorkflow.postBossStage = value }
        override var postBossStartedAt: Long
            get() = this@createSettlementWorkflow.postBossStartedAt
            set(value) { this@createSettlementWorkflow.postBossStartedAt = value }
        override var postBossUnknownAttempts: Int
            get() = this@createSettlementWorkflow.postBossUnknownAttempts
            set(value) { this@createSettlementWorkflow.postBossUnknownAttempts = value }
        override val postEntryStableFrames: Int
            get() = this@createSettlementWorkflow.postEntryStableFrames
        override val rerollRequester: (suspend (Long) -> Boolean)?
            get() = this@createSettlementWorkflow.rerollRequester
        override var routeNodeCount: Int
            get() = this@createSettlementWorkflow.routeNodeCount
            set(value) { this@createSettlementWorkflow.routeNodeCount = value }
        override val runTerminalListener: (suspend (LabyrinthRunTerminalEvent) -> Unit)?
            get() = this@createSettlementWorkflow.runTerminalListener
        override fun anchorRect(result: LabyrinthEntryFrameResult, anchorId: String): EntryPixelRect? =
            this@createSettlementWorkflow.pageActionWorkflow.anchorRect(result = result, anchorId = anchorId)
        override fun beginGuardedAction(): Boolean =
            this@createSettlementWorkflow.runtimeBeginGuardedAction()
        override fun finishFromPlanner(sessionId: AutomationSessionId, reason: String): Unit =
            this@createSettlementWorkflow.runtimeFinishFromPlanner(sessionId = sessionId, reason = reason)
        override fun launchGuardedAction(block: suspend () -> Unit): Unit =
            this@createSettlementWorkflow.runtimeLaunchGuardedAction(block = block)
        override fun nodeLog(message: String, warning: Boolean): Unit =
            this@createSettlementWorkflow.runtimeNodeLog(message = message, warning = warning)
        override suspend fun stop(
                reason: String,
                releaseCapture: Boolean,
            ): Boolean =
            this@createSettlementWorkflow.runtimeStop(reason = reason, releaseCapture = releaseCapture)
        override fun traceWorkflow(owner: String, phase: String): Unit =
            this@createSettlementWorkflow.runtimeTraceWorkflow(owner = owner, phase = phase)
    })

internal fun LabyrinthEntryRecognitionSession.createMapNavigationWorkflow(): LabyrinthMapNavigationWorkflow =
    LabyrinthMapNavigationWorkflow(object : LabyrinthMapNavigationPort {
        override val _state: MutableStateFlow<LabyrinthEntryRecognitionSessionState>
            get() = this@createMapNavigationWorkflow._state
        override val actionExecutor: SessionBoundActionExecutor?
            get() = this@createMapNavigationWorkflow.actionExecutor
        override val actionScope: CoroutineScope
            get() = this@createMapNavigationWorkflow.actionScope
        override val activeRunAccountId: Long?
            get() = this@createMapNavigationWorkflow.activeRunAccountId
        override val activeSessionId: AutomationSessionId?
            get() = this@createMapNavigationWorkflow.activeSessionId
        override val characterAcquisitionActive: Boolean
            get() = this@createMapNavigationWorkflow.characterAcquisitionActive
        override val clock: () -> Long
            get() = this@createMapNavigationWorkflow.clock
        override var combatContext: LabyrinthCombatContext?
            get() = this@createMapNavigationWorkflow.combatContext
            set(value) { this@createMapNavigationWorkflow.combatContext = value }
        override var entryPhaseComplete: Boolean
            get() = this@createMapNavigationWorkflow.entryPhaseComplete
            set(value) { this@createMapNavigationWorkflow.entryPhaseComplete = value }
        override var finalBossLocalizationStartedAt: Long
            get() = this@createMapNavigationWorkflow.finalBossLocalizationStartedAt
            set(value) { this@createMapNavigationWorkflow.finalBossLocalizationStartedAt = value }
        override var lastNodeActionAt: Long
            get() = this@createMapNavigationWorkflow.lastNodeActionAt
            set(value) { this@createMapNavigationWorkflow.lastNodeActionAt = value }
        override var lastNodeScrollSourceSignature: String?
            get() = this@createMapNavigationWorkflow.lastNodeScrollSourceSignature
            set(value) { this@createMapNavigationWorkflow.lastNodeScrollSourceSignature = value }
        override var lastNodeSwipeAvoidRects: List<EntryPixelRect>
            get() = this@createMapNavigationWorkflow.lastNodeSwipeAvoidRects
            set(value) { this@createMapNavigationWorkflow.lastNodeSwipeAvoidRects = value }
        override val lastPostEntryActionAt: Long
            get() = this@createMapNavigationWorkflow.lastPostEntryActionAt
        override val nodeActionPlanner: LabyrinthNodeActionPlanner
            get() = this@createMapNavigationWorkflow.nodeActionPlanner
        override val nodeConflictRecovery: com.landosol.toolbox.labyrinth.node.LabyrinthNodeConflictRecovery
            get() = this@createMapNavigationWorkflow.nodeConflictRecovery
        override var nodeErrorStreak: Int
            get() = this@createMapNavigationWorkflow.nodeErrorStreak
            set(value) { this@createMapNavigationWorkflow.nodeErrorStreak = value }
        override var nodeInitFailedReason: String?
            get() = this@createMapNavigationWorkflow.nodeInitFailedReason
            set(value) { this@createMapNavigationWorkflow.nodeInitFailedReason = value }
        override val nodeInitStarted: AtomicBoolean
            get() = this@createMapNavigationWorkflow.nodeInitStarted
        override var nodeScrollAttempts: Int
            get() = this@createMapNavigationWorkflow.nodeScrollAttempts
            set(value) { this@createMapNavigationWorkflow.nodeScrollAttempts = value }
        override var nodeSession: LabyrinthNodeSession?
            get() = this@createMapNavigationWorkflow.nodeSession
            set(value) { this@createMapNavigationWorkflow.nodeSession = value }
        override val nodeSessionFactory: () -> LabyrinthNodeSession
            get() = this@createMapNavigationWorkflow.nodeSessionFactory
        override var nodeTapAttempts: Int
            get() = this@createMapNavigationWorkflow.nodeTapAttempts
            set(value) { this@createMapNavigationWorkflow.nodeTapAttempts = value }
        override val nodeTemplateLoader: (() -> NodeTemplateSet)?
            get() = this@createMapNavigationWorkflow.nodeTemplateLoader
        override val nodeViewportScanner: LabyrinthNodeViewportScanner
            get() = this@createMapNavigationWorkflow.nodeViewportScanner
        override var pendingNodeClickBlockId: Long
            get() = this@createMapNavigationWorkflow.pendingNodeClickBlockId
            set(value) { this@createMapNavigationWorkflow.pendingNodeClickBlockId = value }
        override var pendingNodeClickRect: EntryPixelRect?
            get() = this@createMapNavigationWorkflow.pendingNodeClickRect
            set(value) { this@createMapNavigationWorkflow.pendingNodeClickRect = value }
        override var pendingNodeClickStableFrames: Int
            get() = this@createMapNavigationWorkflow.pendingNodeClickStableFrames
            set(value) { this@createMapNavigationWorkflow.pendingNodeClickStableFrames = value }
        override var pendingNodeScrollBlockId: Long
            get() = this@createMapNavigationWorkflow.pendingNodeScrollBlockId
            set(value) { this@createMapNavigationWorkflow.pendingNodeScrollBlockId = value }
        override var pendingNodeScrollStableFrames: Int
            get() = this@createMapNavigationWorkflow.pendingNodeScrollStableFrames
            set(value) { this@createMapNavigationWorkflow.pendingNodeScrollStableFrames = value }
        override var pendingNodeScrollStartedAt: Long
            get() = this@createMapNavigationWorkflow.pendingNodeScrollStartedAt
            set(value) { this@createMapNavigationWorkflow.pendingNodeScrollStartedAt = value }
        override var pendingNodeScrollViewportSignature: String?
            get() = this@createMapNavigationWorkflow.pendingNodeScrollViewportSignature
            set(value) { this@createMapNavigationWorkflow.pendingNodeScrollViewportSignature = value }
        override var postBossStage: LabyrinthPostBossStage
            get() = this@createMapNavigationWorkflow.postBossStage
            set(value) { this@createMapNavigationWorkflow.postBossStage = value }
        override var postBossStartedAt: Long
            get() = this@createMapNavigationWorkflow.postBossStartedAt
            set(value) { this@createMapNavigationWorkflow.postBossStartedAt = value }
        override var postBossUnknownAttempts: Int
            get() = this@createMapNavigationWorkflow.postBossUnknownAttempts
            set(value) { this@createMapNavigationWorkflow.postBossUnknownAttempts = value }
        override val routeLoader: (suspend (Long?) -> LabyrinthRouteJson?)?
            get() = this@createMapNavigationWorkflow.routeLoader
        override var routeNodeCount: Int
            get() = this@createMapNavigationWorkflow.routeNodeCount
            set(value) { this@createMapNavigationWorkflow.routeNodeCount = value }
        override var strongRouteTargetBindingAtMillis: Long
            get() = this@createMapNavigationWorkflow.strongRouteTargetBindingAtMillis
            set(value) { this@createMapNavigationWorkflow.strongRouteTargetBindingAtMillis = value }
        override var strongRouteTargetBindingBlockId: Long
            get() = this@createMapNavigationWorkflow.strongRouteTargetBindingBlockId
            set(value) { this@createMapNavigationWorkflow.strongRouteTargetBindingBlockId = value }
        override var strongRouteTargetBindingRect: EntryPixelRect?
            get() = this@createMapNavigationWorkflow.strongRouteTargetBindingRect
            set(value) { this@createMapNavigationWorkflow.strongRouteTargetBindingRect = value }
        override var validatedRoute: LabyrinthRouteJson?
            get() = this@createMapNavigationWorkflow.validatedRoute
            set(value) { this@createMapNavigationWorkflow.validatedRoute = value }
        override fun beginGuardedAction(): Boolean =
            this@createMapNavigationWorkflow.runtimeBeginGuardedAction()
        override fun clearCharacterAcquisitionContext(): Unit =
            this@createMapNavigationWorkflow.rewardWorkflow.clearCharacterAcquisitionContext()
        override fun describeNodeClassifications(classifications: List<NodeClassification>): String =
            this@createMapNavigationWorkflow.mapViewportWorkflow.describeNodeClassifications(classifications = classifications)
        override fun finishFromPlanner(sessionId: AutomationSessionId, reason: String): Unit =
            this@createMapNavigationWorkflow.runtimeFinishFromPlanner(sessionId = sessionId, reason = reason)
        override fun launchGuardedAction(block: suspend () -> Unit): Unit =
            this@createMapNavigationWorkflow.runtimeLaunchGuardedAction(block = block)
        override fun nodeClickRectsAreStable(
                previous: EntryPixelRect?,
                current: EntryPixelRect?,
            ): Boolean =
            this@createMapNavigationWorkflow.mapViewportWorkflow.nodeClickRectsAreStable(previous = previous, current = current)
        override fun nodeLog(message: String, warning: Boolean): Unit =
            this@createMapNavigationWorkflow.runtimeNodeLog(message = message, warning = warning)
        override fun resetMapTransitionRuntimeState() =
            this@createMapNavigationWorkflow.mapTransitionWorkflow.resetMapTransitionRuntimeState()
        override fun onNodeTapExecuted(blockId: Long, blockType: Int, label: String, area: Int?) =
            this@createMapNavigationWorkflow.mapTransitionWorkflow.onNodeTapExecuted(blockId, blockType, label, area)
        override fun pendingTransitionHoldsNextMapFrame(timestampMillis: Long): Boolean =
            this@createMapNavigationWorkflow.mapTransitionWorkflow.pendingTransitionHoldsNextMapFrame(timestampMillis)
        override fun handlePendingNodeTransitionOnMapFrame(
            sessionId: AutomationSessionId,
            pageState: LabyrinthEntryPageState,
            frameWidth: Int,
            frameHeight: Int,
            timestampMillis: Long,
            viewportSignature: String,
        ): Boolean = this@createMapNavigationWorkflow.mapTransitionWorkflow.handlePendingNodeTransitionOnMapFrame(
            sessionId, pageState, frameWidth, frameHeight, timestampMillis, viewportSignature,
        )
        override fun publishOverlay(sessionId: AutomationSessionId): Unit =
            this@createMapNavigationWorkflow.runtimePublishOverlay(sessionId = sessionId)
        override fun publishWaitingForGameForeground(sessionId: AutomationSessionId): Unit =
            this@createMapNavigationWorkflow.runtimePublishWaitingForGameForeground(sessionId = sessionId)
        override fun requestNodeMapNudge(
                sessionId: AutomationSessionId,
                label: String,
                targetBlockId: Long,
                frameWidth: Int,
                frameHeight: Int,
                timestampMillis: Long,
                viewportSignature: String,
                avoidRects: List<EntryPixelRect>,
                reason: String,
            ): Unit =
            this@createMapNavigationWorkflow.mapViewportWorkflow.requestNodeMapNudge(sessionId = sessionId, label = label, targetBlockId = targetBlockId, frameWidth = frameWidth, frameHeight = frameHeight, timestampMillis = timestampMillis, viewportSignature = viewportSignature, avoidRects = avoidRects, reason = reason)
        override fun requestNodeMapScroll(
                sessionId: AutomationSessionId,
                label: String,
                frameWidth: Int,
                frameHeight: Int,
                timestampMillis: Long,
                viewportSignature: String,
                avoidRects: List<EntryPixelRect>,
            ): Unit =
            this@createMapNavigationWorkflow.mapViewportWorkflow.requestNodeMapScroll(sessionId = sessionId, label = label, frameWidth = frameWidth, frameHeight = frameHeight, timestampMillis = timestampMillis, viewportSignature = viewportSignature, avoidRects = avoidRects)
        override fun resetNodeMoveConfirmationTracking(): Unit =
            this@createMapNavigationWorkflow.mapTransitionWorkflow.resetNodeMoveConfirmationTracking()
        override fun resetNodeRecoveryNudgeTracking(): Unit =
            this@createMapNavigationWorkflow.mapViewportWorkflow.resetNodeRecoveryNudgeTracking()
        override fun resetNodeScrollSearchGate(): Unit =
            this@createMapNavigationWorkflow.mapViewportWorkflow.resetNodeScrollSearchGate()
        override fun resetPendingNodeClickStability(): Unit =
            this@createMapNavigationWorkflow.mapViewportWorkflow.resetPendingNodeClickStability()
        override fun resetStrongRouteTargetBinding(): Unit =
            this@createMapNavigationWorkflow.mapViewportWorkflow.resetStrongRouteTargetBinding()
        override suspend fun stopFromRunLogic(reason: String): Boolean =
            this@createMapNavigationWorkflow.runtimeStopFromRunLogic(reason = reason)
        override fun traceAction(label: String): Unit =
            this@createMapNavigationWorkflow.runtimeTraceAction(label = label)
        override fun traceReject(reason: String): Unit =
            this@createMapNavigationWorkflow.runtimeTraceReject(reason = reason)
        override fun traceWorkflow(owner: String, phase: String): Unit =
            this@createMapNavigationWorkflow.runtimeTraceWorkflow(owner = owner, phase = phase)
    })

internal fun LabyrinthEntryRecognitionSession.createMapTransitionWorkflow(): LabyrinthMapTransitionWorkflow =
    LabyrinthMapTransitionWorkflow(object : LabyrinthMapTransitionPort {
        override val _state: MutableStateFlow<LabyrinthEntryRecognitionSessionState>
            get() = this@createMapTransitionWorkflow._state
        override val actionExecutor: SessionBoundActionExecutor?
            get() = this@createMapTransitionWorkflow.actionExecutor
        override val actionScope: CoroutineScope
            get() = this@createMapTransitionWorkflow.actionScope
        override val activeNodeArea: Int?
            get() = this@createMapTransitionWorkflow.activeNodeArea
        override val activeNodeType: Int?
            get() = this@createMapTransitionWorkflow.activeNodeType
        override val activeRunAccountId: Long?
            get() = this@createMapTransitionWorkflow.activeRunAccountId
        override val activeSessionId: AutomationSessionId?
            get() = this@createMapTransitionWorkflow.activeSessionId
        override val clock: () -> Long
            get() = this@createMapTransitionWorkflow.clock
        override var combatContext: LabyrinthCombatContext?
            get() = this@createMapTransitionWorkflow.combatContext
            set(value) { this@createMapTransitionWorkflow.combatContext = value }
        override var finalBossLocalizationStartedAt: Long
            get() = this@createMapTransitionWorkflow.finalBossLocalizationStartedAt
            set(value) { this@createMapTransitionWorkflow.finalBossLocalizationStartedAt = value }
        override var lastNodeScrollSourceSignature: String?
            get() = this@createMapTransitionWorkflow.lastNodeScrollSourceSignature
            set(value) { this@createMapTransitionWorkflow.lastNodeScrollSourceSignature = value }
        override var nodeScrollAttempts: Int
            get() = this@createMapTransitionWorkflow.nodeScrollAttempts
            set(value) { this@createMapTransitionWorkflow.nodeScrollAttempts = value }
        override val nodeSession: LabyrinthNodeSession?
            get() = this@createMapTransitionWorkflow.nodeSession
        override val lastNodeSwipeAvoidRects: List<EntryPixelRect>
            get() = this@createMapTransitionWorkflow.lastNodeSwipeAvoidRects
        override var nodeTapAttempts: Int
            get() = this@createMapTransitionWorkflow.nodeTapAttempts
            set(value) { this@createMapTransitionWorkflow.nodeTapAttempts = value }
        override val persistenceMutex: Mutex
            get() = this@createMapTransitionWorkflow.persistenceMutex
        override var postBossStage: LabyrinthPostBossStage
            get() = this@createMapTransitionWorkflow.postBossStage
            set(value) { this@createMapTransitionWorkflow.postBossStage = value }
        override var postBossStartedAt: Long
            get() = this@createMapTransitionWorkflow.postBossStartedAt
            set(value) { this@createMapTransitionWorkflow.postBossStartedAt = value }
        override var postBossUnknownAttempts: Int
            get() = this@createMapTransitionWorkflow.postBossUnknownAttempts
            set(value) { this@createMapTransitionWorkflow.postBossUnknownAttempts = value }
        override val routeProgressSaver: (suspend (Long, Long, Long) -> Boolean)?
            get() = this@createMapTransitionWorkflow.routeProgressSaver
        override var validatedRoute: LabyrinthRouteJson?
            get() = this@createMapTransitionWorkflow.validatedRoute
            set(value) { this@createMapTransitionWorkflow.validatedRoute = value }
        override fun beginGuardedAction(): Boolean =
            this@createMapTransitionWorkflow.runtimeBeginGuardedAction()
        override fun finishFromPlanner(sessionId: AutomationSessionId, reason: String): Unit =
            this@createMapTransitionWorkflow.runtimeFinishFromPlanner(sessionId = sessionId, reason = reason)
        override fun launchGuardedAction(block: suspend () -> Unit): Unit =
            this@createMapTransitionWorkflow.runtimeLaunchGuardedAction(block = block)
        override fun nodeLog(message: String, warning: Boolean): Unit =
            this@createMapTransitionWorkflow.runtimeNodeLog(message = message, warning = warning)
        override fun prepareNodeTransitionContext(blockType: Int, area: Int?): Unit =
            this@createMapTransitionWorkflow.runBoundaryWorkflow.prepareNodeTransitionContext(blockType = blockType, area = area)
        override fun publishOverlay(sessionId: AutomationSessionId): Unit =
            this@createMapTransitionWorkflow.runtimePublishOverlay(sessionId = sessionId)
        override fun publishRouteProgress(
                sessionId: AutomationSessionId,
                session: LabyrinthNodeSession,
                nextLabel: String?,
                nextRect: EntryPixelRect?,
                complete: Boolean,
            ): Unit =
            this@createMapTransitionWorkflow.mapNavigationWorkflow.publishRouteProgress(sessionId = sessionId, session = session, nextLabel = nextLabel, nextRect = nextRect, complete = complete)
        override fun publishWaitingForGameForeground(sessionId: AutomationSessionId): Unit =
            this@createMapTransitionWorkflow.runtimePublishWaitingForGameForeground(sessionId = sessionId)
        override fun requestNodeMapNudge(
                sessionId: AutomationSessionId,
                label: String,
                targetBlockId: Long,
                frameWidth: Int,
                frameHeight: Int,
                timestampMillis: Long,
                viewportSignature: String,
                avoidRects: List<EntryPixelRect>,
                reason: String,
            ): Unit =
            this@createMapTransitionWorkflow.mapViewportWorkflow.requestNodeMapNudge(sessionId = sessionId, label = label, targetBlockId = targetBlockId, frameWidth = frameWidth, frameHeight = frameHeight, timestampMillis = timestampMillis, viewportSignature = viewportSignature, avoidRects = avoidRects, reason = reason)
        override fun resetNodeRecoveryNudgeTracking(): Unit =
            this@createMapTransitionWorkflow.mapViewportWorkflow.resetNodeRecoveryNudgeTracking()
        override fun resetNodeScrollSearchGate(): Unit =
            this@createMapTransitionWorkflow.mapViewportWorkflow.resetNodeScrollSearchGate()
        override fun resetPendingNodeClickStability(): Unit =
            this@createMapTransitionWorkflow.mapViewportWorkflow.resetPendingNodeClickStability()
        override fun resetStrongRouteTargetBinding(): Unit =
            this@createMapTransitionWorkflow.mapViewportWorkflow.resetStrongRouteTargetBinding()
        override suspend fun stopFromRunLogic(reason: String): Boolean =
            this@createMapTransitionWorkflow.runtimeStopFromRunLogic(reason = reason)
        override fun traceAction(label: String): Unit =
            this@createMapTransitionWorkflow.runtimeTraceAction(label = label)
        override fun traceWorkflow(owner: String, phase: String): Unit =
            this@createMapTransitionWorkflow.runtimeTraceWorkflow(owner = owner, phase = phase)
    })

internal fun LabyrinthEntryRecognitionSession.createOrphanMoveRecoveryWorkflow(): LabyrinthOrphanMoveRecoveryWorkflow =
    LabyrinthOrphanMoveRecoveryWorkflow(object : LabyrinthOrphanMoveRecoveryPort {
        override val actionExecutor: SessionBoundActionExecutor?
            get() = this@createOrphanMoveRecoveryWorkflow.actionExecutor
        override val entryPhaseComplete: Boolean
            get() = this@createOrphanMoveRecoveryWorkflow.entryPhaseComplete
        override val hasNodeSession: Boolean
            get() = nodeSession != null
        override val hasPendingNodeTransition: Boolean
            get() = mapTransitionWorkflow.hasPendingNodeTransition()
        override fun uniqueReachableRouteTarget(): LabyrinthMapNode? =
            nodeSession?.uniqueReachableRouteTarget()
        override fun recoverNodeTransition(
            sessionId: AutomationSessionId,
            target: LabyrinthMapNode,
            timestampMillis: Long,
            confidence: Double,
        ) = mapTransitionWorkflow.recoverPendingNodeTransitionFromMoveConfirmation(
            sessionId = sessionId,
            session = requireNotNull(nodeSession),
            blockId = target.blockId,
            blockType = target.blockType,
            area = target.area,
            timestampMillis = timestampMillis,
            confidence = confidence,
        )
        override fun beginGuardedAction(): Boolean = runtimeBeginGuardedAction()
        override fun launchGuardedAction(block: suspend () -> Unit) = runtimeLaunchGuardedAction(block)
        override fun onDismissExecuted(sessionId: AutomationSessionId) {
            // The map behind the dialog is untouched, but the recovery counters that
            // accumulated while it was open describe a blocked screen, not the map.
            mapViewportWorkflow.resetNodeScrollSearchGate()
            mapViewportWorkflow.resetNodeRecoveryNudgeTracking()
            if (activeSessionId == sessionId) {
                val current = _state.value
                _state.value = current.copy(
                    actionCount = current.actionCount + 1,
                    lastActionLabel = "取消误触的移动确认",
                    message = "已取消非自动触发的移动确认弹窗，继续寻找路线节点",
                )
                runtimePublishOverlay(sessionId)
            }
        }
        override fun publishNodeMoveConfirmationMessage(sessionId: AutomationSessionId, message: String) =
            mapTransitionWorkflow.publishNodeMoveConfirmationMessage(sessionId, message)
        override fun publishWaitingForGameForeground(sessionId: AutomationSessionId) =
            runtimePublishWaitingForGameForeground(sessionId)
        override fun finishFromPlanner(sessionId: AutomationSessionId, reason: String) =
            runtimeFinishFromPlanner(sessionId, reason)
        override suspend fun stopFromRunLogic(reason: String): Boolean = runtimeStopFromRunLogic(reason)
        override fun nodeLog(message: String, warning: Boolean) = runtimeNodeLog(message, warning)
        override fun traceAction(label: String) = runtimeTraceAction(label)
        override fun traceWorkflow(owner: String, phase: String) = runtimeTraceWorkflow(owner, phase)
    })

internal fun LabyrinthEntryRecognitionSession.createMapViewportWorkflow(): LabyrinthMapViewportWorkflow =
    LabyrinthMapViewportWorkflow(object : LabyrinthMapViewportPort {
        override val _state: MutableStateFlow<LabyrinthEntryRecognitionSessionState>
            get() = this@createMapViewportWorkflow._state
        override val actionExecutor: SessionBoundActionExecutor?
            get() = this@createMapViewportWorkflow.actionExecutor
        override val activeSessionId: AutomationSessionId?
            get() = this@createMapViewportWorkflow.activeSessionId
        override var lastNodeActionAt: Long
            get() = this@createMapViewportWorkflow.lastNodeActionAt
            set(value) { this@createMapViewportWorkflow.lastNodeActionAt = value }
        override var lastNodeScrollSourceSignature: String?
            get() = this@createMapViewportWorkflow.lastNodeScrollSourceSignature
            set(value) { this@createMapViewportWorkflow.lastNodeScrollSourceSignature = value }
        override val nodeConflictRecovery: com.landosol.toolbox.labyrinth.node.LabyrinthNodeConflictRecovery
            get() = this@createMapViewportWorkflow.nodeConflictRecovery
        override var nodeErrorStreak: Int
            get() = this@createMapViewportWorkflow.nodeErrorStreak
            set(value) { this@createMapViewportWorkflow.nodeErrorStreak = value }
        override var nodeRecoveryNudgeBlockId: Long
            get() = this@createMapViewportWorkflow.nodeRecoveryNudgeBlockId
            set(value) { this@createMapViewportWorkflow.nodeRecoveryNudgeBlockId = value }
        override var nodeRecoveryNudgeCount: Int
            get() = this@createMapViewportWorkflow.nodeRecoveryNudgeCount
            set(value) { this@createMapViewportWorkflow.nodeRecoveryNudgeCount = value }
        override var nodeScrollAttempts: Int
            get() = this@createMapViewportWorkflow.nodeScrollAttempts
            set(value) { this@createMapViewportWorkflow.nodeScrollAttempts = value }
        override var nodeSearchRearmCount: Int
            get() = this@createMapViewportWorkflow.nodeSearchRearmCount
            set(value) { this@createMapViewportWorkflow.nodeSearchRearmCount = value }
        override val nodeSession: LabyrinthNodeSession?
            get() = this@createMapViewportWorkflow.nodeSession
        override val nodeViewportScanner: LabyrinthNodeViewportScanner
            get() = this@createMapViewportWorkflow.nodeViewportScanner
        override var pendingNodeClickBlockId: Long
            get() = this@createMapViewportWorkflow.pendingNodeClickBlockId
            set(value) { this@createMapViewportWorkflow.pendingNodeClickBlockId = value }
        override var pendingNodeClickRect: EntryPixelRect?
            get() = this@createMapViewportWorkflow.pendingNodeClickRect
            set(value) { this@createMapViewportWorkflow.pendingNodeClickRect = value }
        override var pendingNodeClickStableFrames: Int
            get() = this@createMapViewportWorkflow.pendingNodeClickStableFrames
            set(value) { this@createMapViewportWorkflow.pendingNodeClickStableFrames = value }
        override var pendingNodeScrollBlockId: Long
            get() = this@createMapViewportWorkflow.pendingNodeScrollBlockId
            set(value) { this@createMapViewportWorkflow.pendingNodeScrollBlockId = value }
        override var pendingNodeScrollStableFrames: Int
            get() = this@createMapViewportWorkflow.pendingNodeScrollStableFrames
            set(value) { this@createMapViewportWorkflow.pendingNodeScrollStableFrames = value }
        override var pendingNodeScrollStartedAt: Long
            get() = this@createMapViewportWorkflow.pendingNodeScrollStartedAt
            set(value) { this@createMapViewportWorkflow.pendingNodeScrollStartedAt = value }
        override var pendingNodeScrollViewportSignature: String?
            get() = this@createMapViewportWorkflow.pendingNodeScrollViewportSignature
            set(value) { this@createMapViewportWorkflow.pendingNodeScrollViewportSignature = value }
        override var strongRouteTargetBindingAtMillis: Long
            get() = this@createMapViewportWorkflow.strongRouteTargetBindingAtMillis
            set(value) { this@createMapViewportWorkflow.strongRouteTargetBindingAtMillis = value }
        override var strongRouteTargetBindingBlockId: Long
            get() = this@createMapViewportWorkflow.strongRouteTargetBindingBlockId
            set(value) { this@createMapViewportWorkflow.strongRouteTargetBindingBlockId = value }
        override var strongRouteTargetBindingRect: EntryPixelRect?
            get() = this@createMapViewportWorkflow.strongRouteTargetBindingRect
            set(value) { this@createMapViewportWorkflow.strongRouteTargetBindingRect = value }
        override fun beginGuardedAction(): Boolean =
            this@createMapViewportWorkflow.runtimeBeginGuardedAction()
        override fun finishFromPlanner(sessionId: AutomationSessionId, reason: String): Unit =
            this@createMapViewportWorkflow.runtimeFinishFromPlanner(sessionId = sessionId, reason = reason)
        override fun launchGuardedAction(block: suspend () -> Unit): Unit =
            this@createMapViewportWorkflow.runtimeLaunchGuardedAction(block = block)
        override fun nodeLog(message: String, warning: Boolean): Unit =
            this@createMapViewportWorkflow.runtimeNodeLog(message = message, warning = warning)
        override fun publishOverlay(sessionId: AutomationSessionId): Unit =
            this@createMapViewportWorkflow.runtimePublishOverlay(sessionId = sessionId)
        override fun publishWaitingForGameForeground(sessionId: AutomationSessionId): Unit =
            this@createMapViewportWorkflow.runtimePublishWaitingForGameForeground(sessionId = sessionId)
        override suspend fun stopFromRunLogic(reason: String): Boolean =
            this@createMapViewportWorkflow.runtimeStopFromRunLogic(reason = reason)
    })

internal fun LabyrinthEntryRecognitionSession.createRunBoundaryWorkflow(): LabyrinthRunBoundaryWorkflow =
    LabyrinthRunBoundaryWorkflow(object : LabyrinthRunBoundaryPort {
        override val _state: MutableStateFlow<LabyrinthEntryRecognitionSessionState>
            get() = this@createRunBoundaryWorkflow._state
        override var actionPlanner: LabyrinthEntryActionPlanner?
            get() = this@createRunBoundaryWorkflow.actionPlanner
            set(value) { this@createRunBoundaryWorkflow.actionPlanner = value }
        override var activeNodeArea: Int?
            get() = this@createRunBoundaryWorkflow.activeNodeArea
            set(value) { this@createRunBoundaryWorkflow.activeNodeArea = value }
        override var activeNodeType: Int?
            get() = this@createRunBoundaryWorkflow.activeNodeType
            set(value) { this@createRunBoundaryWorkflow.activeNodeType = value }
        override var battleTeamRecommendationPlanner: LabyrinthBattleTeamRecommendationPlanner?
            get() = this@createRunBoundaryWorkflow.battleTeamRecommendationPlanner
            set(value) { this@createRunBoundaryWorkflow.battleTeamRecommendationPlanner = value }
        override val battleTeamRecommendationUnavailableReason: String?
            get() = this@createRunBoundaryWorkflow.battleTeamRecommendationUnavailableReason
        override var battleTeamSelectionPlanner: LabyrinthBattleTeamSelectionPlanner?
            get() = this@createRunBoundaryWorkflow.battleTeamSelectionPlanner
            set(value) { this@createRunBoundaryWorkflow.battleTeamSelectionPlanner = value }
        override val battleWait: LabyrinthBattleWaitPolicy
            get() = this@createRunBoundaryWorkflow.battleWait
        override var bossFallbackMultiTeamActive: Boolean
            get() = this@createRunBoundaryWorkflow.bossFallbackMultiTeamActive
            set(value) { this@createRunBoundaryWorkflow.bossFallbackMultiTeamActive = value }
        override var bossTeamMode: LabyrinthBossTeamMode
            get() = this@createRunBoundaryWorkflow.bossTeamMode
            set(value) { this@createRunBoundaryWorkflow.bossTeamMode = value }
        override var combatContext: LabyrinthCombatContext?
            get() = this@createRunBoundaryWorkflow.combatContext
            set(value) { this@createRunBoundaryWorkflow.combatContext = value }
        override var configuredBossTeamMode: LabyrinthBossTeamMode
            get() = this@createRunBoundaryWorkflow.configuredBossTeamMode
            set(value) { this@createRunBoundaryWorkflow.configuredBossTeamMode = value }
        override var configuredOpeningRosters: Map<Int, List<List<String>>>
            get() = this@createRunBoundaryWorkflow.configuredOpeningRosters
            set(value) { this@createRunBoundaryWorkflow.configuredOpeningRosters = value }
        override var eventActionAttempts: Int
            get() = this@createRunBoundaryWorkflow.eventActionAttempts
            set(value) { this@createRunBoundaryWorkflow.eventActionAttempts = value }
        override var eventChoicePlanner: LabyrinthEventChoicePlanner?
            get() = this@createRunBoundaryWorkflow.eventChoicePlanner
            set(value) { this@createRunBoundaryWorkflow.eventChoicePlanner = value }
        override var existingRunResumeHandoffArmed: Boolean
            get() = this@createRunBoundaryWorkflow.existingRunResumeHandoffArmed
            set(value) { this@createRunBoundaryWorkflow.existingRunResumeHandoffArmed = value }
        override var lastBattleTeamRecommendationKey: String?
            get() = this@createRunBoundaryWorkflow.lastBattleTeamRecommendationKey
            set(value) { this@createRunBoundaryWorkflow.lastBattleTeamRecommendationKey = value }
        override var lastBattleTeamSelectionPlanLog: String?
            get() = this@createRunBoundaryWorkflow.lastBattleTeamSelectionPlanLog
            set(value) { this@createRunBoundaryWorkflow.lastBattleTeamSelectionPlanLog = value }
        override var lastObservedPageState: LabyrinthEntryPageState?
            get() = this@createRunBoundaryWorkflow.lastObservedPageState
            set(value) { this@createRunBoundaryWorkflow.lastObservedPageState = value }
        override var lastPersistedObservationSignature: String?
            get() = this@createRunBoundaryWorkflow.lastPersistedObservationSignature
            set(value) { this@createRunBoundaryWorkflow.lastPersistedObservationSignature = value }
        override var lastShopDialogState: LabyrinthShopDialogState
            get() = this@createRunBoundaryWorkflow.lastShopDialogState
            set(value) { this@createRunBoundaryWorkflow.lastShopDialogState = value }
        override val openingStateReset: AtomicBoolean
            get() = this@createRunBoundaryWorkflow.openingStateReset
        override var pendingSingleBossFallbackToMulti: Boolean
            get() = this@createRunBoundaryWorkflow.pendingSingleBossFallbackToMulti
            set(value) { this@createRunBoundaryWorkflow.pendingSingleBossFallbackToMulti = value }
        override var preferPureBossDamageSystem: Boolean
            get() = this@createRunBoundaryWorkflow.preferPureBossDamageSystem
            set(value) { this@createRunBoundaryWorkflow.preferPureBossDamageSystem = value }
        override var relicChoicePolicy: LabyrinthRelicChoicePolicy
            get() = this@createRunBoundaryWorkflow.relicChoicePolicy
            set(value) { this@createRunBoundaryWorkflow.relicChoicePolicy = value }
        override val relicStackLedger: LabyrinthRelicStackLedger
            get() = this@createRunBoundaryWorkflow.relicStackLedger
        override var rerollAfterThreeBattleFailures: Boolean
            get() = this@createRunBoundaryWorkflow.rerollAfterThreeBattleFailures
            set(value) { this@createRunBoundaryWorkflow.rerollAfterThreeBattleFailures = value }
        override var responseIntervalMillis: Long
            get() = this@createRunBoundaryWorkflow.responseIntervalMillis
            set(value) { this@createRunBoundaryWorkflow.responseIntervalMillis = value }
        override var roleDisplayNames: Map<String, String>
            get() = this@createRunBoundaryWorkflow.roleDisplayNames
            set(value) { this@createRunBoundaryWorkflow.roleDisplayNames = value }
        override var roleRewardChoicePlanner: LabyrinthRoleRewardChoicePlanner?
            get() = this@createRunBoundaryWorkflow.roleRewardChoicePlanner
            set(value) { this@createRunBoundaryWorkflow.roleRewardChoicePlanner = value }
        override var shopPolicy: LabyrinthShopPolicy
            get() = this@createRunBoundaryWorkflow.shopPolicy
            set(value) { this@createRunBoundaryWorkflow.shopPolicy = value }
        override var singleBossFallbackToMultiAfterThreeFailures: Boolean
            get() = this@createRunBoundaryWorkflow.singleBossFallbackToMultiAfterThreeFailures
            set(value) { this@createRunBoundaryWorkflow.singleBossFallbackToMultiAfterThreeFailures = value }
        override var singleBossRetryCountBeforeMulti: Int
            get() = this@createRunBoundaryWorkflow.singleBossRetryCountBeforeMulti
            set(value) { this@createRunBoundaryWorkflow.singleBossRetryCountBeforeMulti = value }
        override var specialEquipmentSettings: LabyrinthStrategySettings?
            get() = this@createRunBoundaryWorkflow.specialEquipmentSettings
            set(value) { this@createRunBoundaryWorkflow.specialEquipmentSettings = value }
        override fun clearShopChoiceImprintRolePending(): Unit =
            this@createRunBoundaryWorkflow.shopWorkflow.clearShopChoiceImprintRolePending()
        override fun createActionPlanner(): LabyrinthEntryActionPlanner =
            this@createRunBoundaryWorkflow.entryWorkflow.createActionPlanner()
        override fun onBattlePageChanged(pageState: LabyrinthEntryPageState): Unit =
            this@createRunBoundaryWorkflow.battleTeamWorkflow.onBattlePageChanged(pageState = pageState)
        override fun onEventPageChanged(pageState: LabyrinthEntryPageState): Unit =
            this@createRunBoundaryWorkflow.eventWorkflow.onEventPageChanged(pageState = pageState)
        override fun onMapPageChanged(pageState: LabyrinthEntryPageState, preservePendingNodeMoveConfirmation: Boolean): Unit =
            this@createRunBoundaryWorkflow.mapTransitionWorkflow.onMapPageChanged(pageState = pageState, preservePendingNodeMoveConfirmation = preservePendingNodeMoveConfirmation)
        override fun onRewardPageChanged(pageState: LabyrinthEntryPageState): Unit =
            this@createRunBoundaryWorkflow.rewardWorkflow.onRewardPageChanged(pageState = pageState)
        override fun onShopPageChanged(pageState: LabyrinthEntryPageState): Unit =
            this@createRunBoundaryWorkflow.shopWorkflow.onShopPageChanged(pageState = pageState)
        override fun onShopPurchasePageObserved(pageState: LabyrinthEntryPageState): Unit =
            this@createRunBoundaryWorkflow.shopWorkflow.onShopPurchasePageObserved(pageState = pageState)
        override fun prepareBattleEditor(blockType: Int): Unit =
            this@createRunBoundaryWorkflow.battleCombatWorkflow.prepareBattleEditor(blockType = blockType)
        override fun prepareBattleEntry(blockType: Int): Unit =
            this@createRunBoundaryWorkflow.battleCombatWorkflow.prepareBattleEntry(blockType = blockType)
        override fun prepareEventEntry(): Unit =
            this@createRunBoundaryWorkflow.eventWorkflow.prepareEventEntry()
        override fun prepareMapEntry(): Unit =
            this@createRunBoundaryWorkflow.mapTransitionWorkflow.prepareMapEntry()
        override fun prepareShopEntry(blockType: Int): Unit =
            this@createRunBoundaryWorkflow.shopWorkflow.prepareShopEntry(blockType = blockType)
        override fun resetBattleRetryTracking(): Unit =
            this@createRunBoundaryWorkflow.battleCombatWorkflow.resetBattleRetryTracking()
        override fun resetCombatRuntimeState(): Unit =
            this@createRunBoundaryWorkflow.battleCombatWorkflow.resetCombatRuntimeState()
        override fun resetEffectiveCharacterScan(): Unit =
            this@createRunBoundaryWorkflow.battleRosterWorkflow.resetEffectiveCharacterScan()
        override fun resetEventRosterState(): Unit =
            this@createRunBoundaryWorkflow.eventWorkflow.resetEventRosterState()
        override fun resetExEncounterTracking(): Unit =
            this@createRunBoundaryWorkflow.encounterWorkflow.resetExEncounterTracking()
        override fun resetMapRuntimeState(): Unit =
            this@createRunBoundaryWorkflow.mapNavigationWorkflow.resetMapRuntimeState()
        override fun resetPostEntryRuntimeState(): Unit =
            this@createRunBoundaryWorkflow.pageRouterWorkflow.resetPostEntryRuntimeState()
        override fun resetRelicsOnStart(): Unit =
            this@createRunBoundaryWorkflow.rewardWorkflow.resetRelicsOnStart()
        override fun resetRewardOnStart(): Unit =
            this@createRunBoundaryWorkflow.rewardWorkflow.resetRewardOnStart()
        override fun resetRewardOnStop(): Unit =
            this@createRunBoundaryWorkflow.rewardWorkflow.resetRewardOnStop()
        override fun resetRewardRuntimeState(): Unit =
            this@createRunBoundaryWorkflow.rewardWorkflow.resetRewardRuntimeState()
        override fun resetSessionRecoveryTracking(): Unit =
            this@createRunBoundaryWorkflow.recoveryWorkflow.resetSessionRecoveryTracking()
        override fun resetSettlementRuntimeState(): Unit =
            this@createRunBoundaryWorkflow.settlementWorkflow.resetSettlementRuntimeState()
        override fun resetShopOnStart(): Unit =
            this@createRunBoundaryWorkflow.shopWorkflow.resetShopOnStart()
        override fun resetShopOnStop(): Unit =
            this@createRunBoundaryWorkflow.shopWorkflow.resetShopOnStop()
    })

internal fun LabyrinthEntryRecognitionSession.createEntryWorkflow(): LabyrinthEntryWorkflow =
    LabyrinthEntryWorkflow(object : LabyrinthEntryPort {
        override val _state: MutableStateFlow<LabyrinthEntryRecognitionSessionState>
            get() = this@createEntryWorkflow._state
        override val actionExecutor: SessionBoundActionExecutor?
            get() = this@createEntryWorkflow.actionExecutor
        override val actionInFlight: AtomicBoolean
            get() = this@createEntryWorkflow.actionInFlight
        override var actionPlanner: LabyrinthEntryActionPlanner?
            get() = this@createEntryWorkflow.actionPlanner
            set(value) { this@createEntryWorkflow.actionPlanner = value }
        override val actionPlannerFactory: () -> LabyrinthEntryActionPlanner
            get() = this@createEntryWorkflow.actionPlannerFactory
        override val actionPlannerLock: Any
            get() = this@createEntryWorkflow.actionPlannerLock
        override val activeSessionId: AutomationSessionId?
            get() = this@createEntryWorkflow.activeSessionId
        override val clock: () -> Long
            get() = this@createEntryWorkflow.clock
        override val configuredOpeningRosters: Map<Int, List<List<String>>>
            get() = this@createEntryWorkflow.configuredOpeningRosters
        override var entryPhaseComplete: Boolean
            get() = this@createEntryWorkflow.entryPhaseComplete
            set(value) { this@createEntryWorkflow.entryPhaseComplete = value }
        override var existingRunResumeHandoffArmed: Boolean
            get() = this@createEntryWorkflow.existingRunResumeHandoffArmed
            set(value) { this@createEntryWorkflow.existingRunResumeHandoffArmed = value }
        override val roleDisplayNames: Map<String, String>
            get() = this@createEntryWorkflow.roleDisplayNames
        override val validatedRoute: LabyrinthRouteJson?
            get() = this@createEntryWorkflow.validatedRoute
        override fun beginGuardedAction(): Boolean =
            this@createEntryWorkflow.runtimeBeginGuardedAction()
        override fun finishFromPlanner(sessionId: AutomationSessionId, reason: String): Unit =
            this@createEntryWorkflow.runtimeFinishFromPlanner(sessionId = sessionId, reason = reason)
        override fun launchGuardedAction(block: suspend () -> Unit): Unit =
            this@createEntryWorkflow.runtimeLaunchGuardedAction(block = block)
        override fun nodeExecutionConfigured(): Boolean =
            this@createEntryWorkflow.runtimeNodeExecutionConfigured()
        override fun publishOverlay(sessionId: AutomationSessionId): Unit =
            this@createEntryWorkflow.runtimePublishOverlay(sessionId = sessionId)
        override fun publishWaitingForGameForeground(sessionId: AutomationSessionId): Unit =
            this@createEntryWorkflow.runtimePublishWaitingForGameForeground(sessionId = sessionId)
        override suspend fun stopFromRunLogic(reason: String): Boolean =
            this@createEntryWorkflow.runtimeStopFromRunLogic(reason = reason)
        override fun traceAction(label: String): Unit =
            this@createEntryWorkflow.runtimeTraceAction(label = label)
        override fun traceWorkflow(owner: String, phase: String): Unit =
            this@createEntryWorkflow.runtimeTraceWorkflow(owner = owner, phase = phase)
    })

internal fun LabyrinthEntryRecognitionSession.createRecoveryWorkflow(): LabyrinthRecoveryWorkflow =
    LabyrinthRecoveryWorkflow(object : LabyrinthRecoveryPort {
        override val _state: MutableStateFlow<LabyrinthEntryRecognitionSessionState>
            get() = this@createRecoveryWorkflow._state
        override val actionExecutor: SessionBoundActionExecutor?
            get() = this@createRecoveryWorkflow.actionExecutor
        override val actionInFlight: AtomicBoolean
            get() = this@createRecoveryWorkflow.actionInFlight
        override var actionInFlightSince: Long
            get() = this@createRecoveryWorkflow.actionInFlightSince
            set(value) { this@createRecoveryWorkflow.actionInFlightSince = value }
        override var actionPlanner: LabyrinthEntryActionPlanner?
            get() = this@createRecoveryWorkflow.actionPlanner
            set(value) { this@createRecoveryWorkflow.actionPlanner = value }
        override val actionPlannerLock: Any
            get() = this@createRecoveryWorkflow.actionPlannerLock
        override val actionScope: CoroutineScope
            get() = this@createRecoveryWorkflow.actionScope
        override var activeNodeArea: Int?
            get() = this@createRecoveryWorkflow.activeNodeArea
            set(value) { this@createRecoveryWorkflow.activeNodeArea = value }
        override var activeNodeType: Int?
            get() = this@createRecoveryWorkflow.activeNodeType
            set(value) { this@createRecoveryWorkflow.activeNodeType = value }
        override val activeSessionId: AutomationSessionId?
            get() = this@createRecoveryWorkflow.activeSessionId
        override val battleTeamRecommendationUnavailableReason: String?
            get() = this@createRecoveryWorkflow.battleTeamRecommendationUnavailableReason
        override val battleWait: LabyrinthBattleWaitPolicy
            get() = this@createRecoveryWorkflow.battleWait
        override var channelPromptMessage: String?
            get() = this@createRecoveryWorkflow.channelPromptMessage
            set(value) { this@createRecoveryWorkflow.channelPromptMessage = value }
        override var channelPromptVisibleUntil: Long
            get() = this@createRecoveryWorkflow.channelPromptVisibleUntil
            set(value) { this@createRecoveryWorkflow.channelPromptVisibleUntil = value }
        override val channelPrompts: com.landosol.toolbox.automation.session.ChannelLoginPromptAssistant?
            get() = this@createRecoveryWorkflow.channelPrompts
        override val clock: () -> Long
            get() = this@createRecoveryWorkflow.clock
        override var combatContext: LabyrinthCombatContext?
            get() = this@createRecoveryWorkflow.combatContext
            set(value) { this@createRecoveryWorkflow.combatContext = value }
        override var entryPhaseComplete: Boolean
            get() = this@createRecoveryWorkflow.entryPhaseComplete
            set(value) { this@createRecoveryWorkflow.entryPhaseComplete = value }
        override var eventActionAttempts: Int
            get() = this@createRecoveryWorkflow.eventActionAttempts
            set(value) { this@createRecoveryWorkflow.eventActionAttempts = value }
        override var existingRunResumeHandoffArmed: Boolean
            get() = this@createRecoveryWorkflow.existingRunResumeHandoffArmed
            set(value) { this@createRecoveryWorkflow.existingRunResumeHandoffArmed = value }
        override var lastBattleTeamRecommendationKey: String?
            get() = this@createRecoveryWorkflow.lastBattleTeamRecommendationKey
            set(value) { this@createRecoveryWorkflow.lastBattleTeamRecommendationKey = value }
        override var lastBattleTeamSelectionPlanLog: String?
            get() = this@createRecoveryWorkflow.lastBattleTeamSelectionPlanLog
            set(value) { this@createRecoveryWorkflow.lastBattleTeamSelectionPlanLog = value }
        override var lastNodeActionAt: Long
            get() = this@createRecoveryWorkflow.lastNodeActionAt
            set(value) { this@createRecoveryWorkflow.lastNodeActionAt = value }
        override var lastNodeScrollSourceSignature: String?
            get() = this@createRecoveryWorkflow.lastNodeScrollSourceSignature
            set(value) { this@createRecoveryWorkflow.lastNodeScrollSourceSignature = value }
        override var lastNodeSwipeAvoidRects: List<EntryPixelRect>
            get() = this@createRecoveryWorkflow.lastNodeSwipeAvoidRects
            set(value) { this@createRecoveryWorkflow.lastNodeSwipeAvoidRects = value }
        override val lastObservedPageState: LabyrinthEntryPageState?
            get() = this@createRecoveryWorkflow.lastObservedPageState
        override var lastPostEntryActionAt: Long
            get() = this@createRecoveryWorkflow.lastPostEntryActionAt
            set(value) { this@createRecoveryWorkflow.lastPostEntryActionAt = value }
        override var lastPostEntryActionLandedAt: Long
            get() = this@createRecoveryWorkflow.lastPostEntryActionLandedAt
            set(value) { this@createRecoveryWorkflow.lastPostEntryActionLandedAt = value }
        override var lastPostEntryState: LabyrinthEntryPageState?
            get() = this@createRecoveryWorkflow.lastPostEntryState
            set(value) { this@createRecoveryWorkflow.lastPostEntryState = value }
        override var lastSessionBlockKind: SessionBlockKind
            get() = this@createRecoveryWorkflow.lastSessionBlockKind
            set(value) { this@createRecoveryWorkflow.lastSessionBlockKind = value }
        override var lastSessionReturnTitleActionAt: Long
            get() = this@createRecoveryWorkflow.lastSessionReturnTitleActionAt
            set(value) { this@createRecoveryWorkflow.lastSessionReturnTitleActionAt = value }
        override var nodeErrorStreak: Int
            get() = this@createRecoveryWorkflow.nodeErrorStreak
            set(value) { this@createRecoveryWorkflow.nodeErrorStreak = value }
        override var nodeScrollAttempts: Int
            get() = this@createRecoveryWorkflow.nodeScrollAttempts
            set(value) { this@createRecoveryWorkflow.nodeScrollAttempts = value }
        override var nodeTapAttempts: Int
            get() = this@createRecoveryWorkflow.nodeTapAttempts
            set(value) { this@createRecoveryWorkflow.nodeTapAttempts = value }
        override val nodeViewportScanner: LabyrinthNodeViewportScanner
            get() = this@createRecoveryWorkflow.nodeViewportScanner
        override var postEntryAttempts: Int
            get() = this@createRecoveryWorkflow.postEntryAttempts
            set(value) { this@createRecoveryWorkflow.postEntryAttempts = value }
        override var postEntryStableFrames: Int
            get() = this@createRecoveryWorkflow.postEntryStableFrames
            set(value) { this@createRecoveryWorkflow.postEntryStableFrames = value }
        override var postEntryUnknownSince: Long
            get() = this@createRecoveryWorkflow.postEntryUnknownSince
            set(value) { this@createRecoveryWorkflow.postEntryUnknownSince = value }
        override val sessionBlockClassifier: SessionBlockClassifier
            get() = this@createRecoveryWorkflow.sessionBlockClassifier
        override var sessionBlockStableFrames: Int
            get() = this@createRecoveryWorkflow.sessionBlockStableFrames
            set(value) { this@createRecoveryWorkflow.sessionBlockStableFrames = value }
        override var sessionReturnTitleAttempts: Int
            get() = this@createRecoveryWorkflow.sessionReturnTitleAttempts
            set(value) { this@createRecoveryWorkflow.sessionReturnTitleAttempts = value }
        override var sessionReturnTitleStableFrames: Int
            get() = this@createRecoveryWorkflow.sessionReturnTitleStableFrames
            set(value) { this@createRecoveryWorkflow.sessionReturnTitleStableFrames = value }
        override fun beginGuardedAction(): Boolean =
            this@createRecoveryWorkflow.runtimeBeginGuardedAction()
        override fun clearCharacterAcquisitionContext(): Unit =
            this@createRecoveryWorkflow.rewardWorkflow.clearCharacterAcquisitionContext()
        override fun clearRoleRewardBatchTracking(): Unit =
            this@createRecoveryWorkflow.rewardWorkflow.clearRoleRewardBatchTracking()
        override fun createActionPlanner(): LabyrinthEntryActionPlanner =
            this@createRecoveryWorkflow.entryWorkflow.createActionPlanner()
        override fun endGuardedAction(): Unit =
            this@createRecoveryWorkflow.runtimeEndGuardedAction()
        override fun launchGuardedAction(block: suspend () -> Unit): Unit =
            this@createRecoveryWorkflow.runtimeLaunchGuardedAction(block = block)
        override fun publishOverlay(sessionId: AutomationSessionId): Unit =
            this@createRecoveryWorkflow.runtimePublishOverlay(sessionId = sessionId)
        override fun publishWaitingForGameForeground(sessionId: AutomationSessionId): Unit =
            this@createRecoveryWorkflow.runtimePublishWaitingForGameForeground(sessionId = sessionId)
        override fun clearPendingNodeTransition() =
            this@createRecoveryWorkflow.mapTransitionWorkflow.clearPendingNodeTransition()
        override fun resetNodeEntryMismatchTracking() =
            this@createRecoveryWorkflow.mapTransitionWorkflow.resetNodeEntryMismatchTracking()
        override fun resetNodeMoveConfirmationTracking(): Unit =
            this@createRecoveryWorkflow.mapTransitionWorkflow.resetNodeMoveConfirmationTracking()
        override fun resetNodeRecoveryNudgeTracking(): Unit =
            this@createRecoveryWorkflow.mapViewportWorkflow.resetNodeRecoveryNudgeTracking()
        override fun resetNodeScrollSearchGate(): Unit =
            this@createRecoveryWorkflow.mapViewportWorkflow.resetNodeScrollSearchGate()
        override fun resetOrphanMoveConfirmationTracking(): Unit =
            this@createRecoveryWorkflow.orphanMoveRecoveryWorkflow.resetOrphanMoveConfirmationTracking()
        override fun resetPendingNodeClickStability(): Unit =
            this@createRecoveryWorkflow.mapViewportWorkflow.resetPendingNodeClickStability()
        override fun resetPostEntryStall(): Unit =
            this@createRecoveryWorkflow.pageRouterWorkflow.resetPostEntryStall()
        override fun resetStrongRouteTargetBinding(): Unit =
            this@createRecoveryWorkflow.mapViewportWorkflow.resetStrongRouteTargetBinding()
        override suspend fun stopFromRunLogic(reason: String): Boolean =
            this@createRecoveryWorkflow.runtimeStopFromRunLogic(reason = reason)
        override fun traceAction(label: String): Unit =
            this@createRecoveryWorkflow.runtimeTraceAction(label = label)
    })

internal fun LabyrinthEntryRecognitionSession.createPersistenceWorkflow(): LabyrinthPersistenceWorkflow =
    LabyrinthPersistenceWorkflow(object : LabyrinthPersistencePort {
        override val _state: MutableStateFlow<LabyrinthEntryRecognitionSessionState>
            get() = this@createPersistenceWorkflow._state
        override val actionScope: CoroutineScope
            get() = this@createPersistenceWorkflow.actionScope
        override val activeRunAccountId: Long?
            get() = this@createPersistenceWorkflow.activeRunAccountId
        override val activeSessionId: AutomationSessionId?
            get() = this@createPersistenceWorkflow.activeSessionId
        override var lastPersistedObservationSignature: String?
            get() = this@createPersistenceWorkflow.lastPersistedObservationSignature
            set(value) { this@createPersistenceWorkflow.lastPersistedObservationSignature = value }
        override val openingStateReset: AtomicBoolean
            get() = this@createPersistenceWorkflow.openingStateReset
        override var pendingAcquiredCharacterId: String?
            get() = this@createPersistenceWorkflow.pendingAcquiredCharacterId
            set(value) { this@createPersistenceWorkflow.pendingAcquiredCharacterId = value }
        override val persistenceMutex: Mutex
            get() = this@createPersistenceWorkflow.persistenceMutex
        override val runStateStore: LabyrinthRunStateStore?
            get() = this@createPersistenceWorkflow.runStateStore
        override val validatedRoute: LabyrinthRouteJson?
            get() = this@createPersistenceWorkflow.validatedRoute
        override fun acknowledgeAcquiredCharacters(characters: List<LabyrinthCharacterMatch>): Unit =
            this@createPersistenceWorkflow.rewardWorkflow.acknowledgeAcquiredCharacters(characters = characters)
        override fun advanceRelicStackCalibrationAfterAcquisition(relic: LabyrinthRelicMatch): Unit =
            this@createPersistenceWorkflow.rewardWorkflow.advanceRelicStackCalibrationAfterAcquisition(relic = relic)
        override fun observeRelicAcquisition(result: LabyrinthEntryFrameResult): LabyrinthRelicMatch? =
            this@createPersistenceWorkflow.rewardWorkflow.observeRelicAcquisition(result = result)
        override fun observeShopPurchase(result: LabyrinthEntryFrameResult): LabyrinthRelicMatch? =
            this@createPersistenceWorkflow.shopWorkflow.observeShopPurchase(result = result)
        override fun publishOverlay(sessionId: AutomationSessionId): Unit =
            this@createPersistenceWorkflow.runtimePublishOverlay(sessionId = sessionId)
        override fun refreshBattleTeamRecommendation(
                sessionId: AutomationSessionId,
                pageState: LabyrinthEntryPageState,
            ): Unit =
            this@createPersistenceWorkflow.battleRecommendationWorkflow.refreshBattleTeamRecommendation(sessionId = sessionId, pageState = pageState)
        override fun refreshBattleTeamSelectionPlan(
                sessionId: AutomationSessionId,
                pageState: LabyrinthEntryPageState,
                observation: com.landosol.toolbox.labyrinth.vision.LabyrinthBattleTeamObservation?,
            ): Unit =
            this@createPersistenceWorkflow.battleRecommendationWorkflow.refreshBattleTeamSelectionPlan(sessionId = sessionId, pageState = pageState, observation = observation)
        override fun resetObservedOpeningRewards(): Unit =
            this@createPersistenceWorkflow.rewardWorkflow.resetObservedOpeningRewards()
    })

internal fun LabyrinthEntryRecognitionSession.createPageRouterWorkflow(): LabyrinthPageRouterWorkflow =
    LabyrinthPageRouterWorkflow(object : LabyrinthPageRouterPort {
        override val _state: MutableStateFlow<LabyrinthEntryRecognitionSessionState>
            get() = this@createPageRouterWorkflow._state
        override val actionInFlight: AtomicBoolean
            get() = this@createPageRouterWorkflow.actionInFlight
        override var actionInFlightSince: Long
            get() = this@createPageRouterWorkflow.actionInFlightSince
            set(value) { this@createPageRouterWorkflow.actionInFlightSince = value }
        override var actionPlanner: LabyrinthEntryActionPlanner?
            get() = this@createPageRouterWorkflow.actionPlanner
            set(value) { this@createPageRouterWorkflow.actionPlanner = value }
        override val actionPlannerLock: Any
            get() = this@createPageRouterWorkflow.actionPlannerLock
        override val activeNodeType: Int?
            get() = this@createPageRouterWorkflow.activeNodeType
        override val activeSessionId: AutomationSessionId?
            get() = this@createPageRouterWorkflow.activeSessionId
        override val battleTeamRecommendationUnavailableReason: String?
            get() = this@createPageRouterWorkflow.battleTeamRecommendationUnavailableReason
        override val battleWait: LabyrinthBattleWaitPolicy
            get() = this@createPageRouterWorkflow.battleWait
        override val channelPromptMessage: String?
            get() = this@createPageRouterWorkflow.channelPromptMessage
        override val channelPromptVisibleUntil: Long
            get() = this@createPageRouterWorkflow.channelPromptVisibleUntil
        override var characterAcquisitionActive: Boolean
            get() = this@createPageRouterWorkflow.characterAcquisitionActive
            set(value) { this@createPageRouterWorkflow.characterAcquisitionActive = value }
        override var characterAcquisitionClicks: Int
            get() = this@createPageRouterWorkflow.characterAcquisitionClicks
            set(value) { this@createPageRouterWorkflow.characterAcquisitionClicks = value }
        override var characterAcquisitionStartedAt: Long
            get() = this@createPageRouterWorkflow.characterAcquisitionStartedAt
            set(value) { this@createPageRouterWorkflow.characterAcquisitionStartedAt = value }
        override val clock: () -> Long
            get() = this@createPageRouterWorkflow.clock
        override var combatContext: LabyrinthCombatContext?
            get() = this@createPageRouterWorkflow.combatContext
            set(value) { this@createPageRouterWorkflow.combatContext = value }
        override var entryPhaseComplete: Boolean
            get() = this@createPageRouterWorkflow.entryPhaseComplete
            set(value) { this@createPageRouterWorkflow.entryPhaseComplete = value }
        override var existingRunResumeHandoffArmed: Boolean
            get() = this@createPageRouterWorkflow.existingRunResumeHandoffArmed
            set(value) { this@createPageRouterWorkflow.existingRunResumeHandoffArmed = value }
        override var firstFrameWatchdog: Job?
            get() = this@createPageRouterWorkflow.firstFrameWatchdog
            set(value) { this@createPageRouterWorkflow.firstFrameWatchdog = value }
        override var lastCharacterAcquisitionActionAt: Long
            get() = this@createPageRouterWorkflow.lastCharacterAcquisitionActionAt
            set(value) { this@createPageRouterWorkflow.lastCharacterAcquisitionActionAt = value }
        override var lastObservedRoleRewardSelectionSignature: String?
            get() = this@createPageRouterWorkflow.lastObservedRoleRewardSelectionSignature
            set(value) { this@createPageRouterWorkflow.lastObservedRoleRewardSelectionSignature = value }
        override var lastPostEntryActionAt: Long
            get() = this@createPageRouterWorkflow.lastPostEntryActionAt
            set(value) { this@createPageRouterWorkflow.lastPostEntryActionAt = value }
        override var lastPostEntryActionLandedAt: Long
            get() = this@createPageRouterWorkflow.lastPostEntryActionLandedAt
            set(value) { this@createPageRouterWorkflow.lastPostEntryActionLandedAt = value }
        override var lastPostEntryState: LabyrinthEntryPageState?
            get() = this@createPageRouterWorkflow.lastPostEntryState
            set(value) { this@createPageRouterWorkflow.lastPostEntryState = value }
        override val nodeSession: LabyrinthNodeSession?
            get() = this@createPageRouterWorkflow.nodeSession
        override fun hasPendingNodeTransition(): Boolean =
            this@createPageRouterWorkflow.mapTransitionWorkflow.hasPendingNodeTransition()
        override var pendingRelicSelection: LabyrinthRelicMatch?
            get() = this@createPageRouterWorkflow.pendingRelicSelection
            set(value) { this@createPageRouterWorkflow.pendingRelicSelection = value }
        override val plannedShopPurchaseRelicId: String?
            get() = this@createPageRouterWorkflow.plannedShopPurchaseRelicId
        override val plannedShopRoleImprintLabel: String?
            get() = this@createPageRouterWorkflow.plannedShopRoleImprintLabel
        override val postBossStage: LabyrinthPostBossStage
            get() = this@createPageRouterWorkflow.postBossStage
        override var postEntryAttempts: Int
            get() = this@createPageRouterWorkflow.postEntryAttempts
            set(value) { this@createPageRouterWorkflow.postEntryAttempts = value }
        override var postEntryStableFrames: Int
            get() = this@createPageRouterWorkflow.postEntryStableFrames
            set(value) { this@createPageRouterWorkflow.postEntryStableFrames = value }
        override var postEntryStallMessage: String?
            get() = this@createPageRouterWorkflow.postEntryStallMessage
            set(value) { this@createPageRouterWorkflow.postEntryStallMessage = value }
        override var postEntryStallSince: Long
            get() = this@createPageRouterWorkflow.postEntryStallSince
            set(value) { this@createPageRouterWorkflow.postEntryStallSince = value }
        override var postEntryUnknownSince: Long
            get() = this@createPageRouterWorkflow.postEntryUnknownSince
            set(value) { this@createPageRouterWorkflow.postEntryUnknownSince = value }
        override var relicChoiceCommitAttempts: Int
            get() = this@createPageRouterWorkflow.relicChoiceCommitAttempts
            set(value) { this@createPageRouterWorkflow.relicChoiceCommitAttempts = value }
        override var relicChoiceCommitted: Boolean
            get() = this@createPageRouterWorkflow.relicChoiceCommitted
            set(value) { this@createPageRouterWorkflow.relicChoiceCommitted = value }
        override var relicChoiceCommittedAt: Long
            get() = this@createPageRouterWorkflow.relicChoiceCommittedAt
            set(value) { this@createPageRouterWorkflow.relicChoiceCommittedAt = value }
        override var relicDetailHoldSince: Long
            get() = this@createPageRouterWorkflow.relicDetailHoldSince
            set(value) { this@createPageRouterWorkflow.relicDetailHoldSince = value }
        override var roleRewardBatchActive: Boolean
            get() = this@createPageRouterWorkflow.roleRewardBatchActive
            set(value) { this@createPageRouterWorkflow.roleRewardBatchActive = value }
        override val roleRewardChoicePlanner: LabyrinthRoleRewardChoicePlanner?
            get() = this@createPageRouterWorkflow.roleRewardChoicePlanner
        override val roleRewardChoiceUnavailableReason: String?
            get() = this@createPageRouterWorkflow.roleRewardChoiceUnavailableReason
        override var roleRewardJoinedSequenceStarted: Boolean
            get() = this@createPageRouterWorkflow.roleRewardJoinedSequenceStarted
            set(value) { this@createPageRouterWorkflow.roleRewardJoinedSequenceStarted = value }
        override val shopImprintRewardConfirmedAt: Long
            get() = this@createPageRouterWorkflow.shopImprintRewardConfirmedAt
        override var skipRoleRewardJoinedRecognition: Boolean
            get() = this@createPageRouterWorkflow.skipRoleRewardJoinedRecognition
            set(value) { this@createPageRouterWorkflow.skipRoleRewardJoinedRecognition = value }
        override val specialEquipmentFlow: LabyrinthSpecialEquipmentFlow
            get() = this@createPageRouterWorkflow.specialEquipmentFlow
        override fun advanceBossTeamOnResultPage(sessionId: AutomationSessionId, result: LabyrinthEntryFrameResult, previousPostEntryState: LabyrinthEntryPageState?): Unit =
            this@createPageRouterWorkflow.battleCombatWorkflow.advanceBossTeamOnResultPage(sessionId = sessionId, result = result, previousPostEntryState = previousPostEntryState)
        override fun clearCharacterAcquisitionContext(): Unit =
            this@createPageRouterWorkflow.rewardWorkflow.clearCharacterAcquisitionContext()
        override fun clearRoleRewardBatchTracking(): Unit =
            this@createPageRouterWorkflow.rewardWorkflow.clearRoleRewardBatchTracking()
        override fun confirmPendingNodeTransition(
                sessionId: AutomationSessionId,
                pageState: LabyrinthEntryPageState,
            ): Unit =
            this@createPageRouterWorkflow.mapTransitionWorkflow.confirmPendingNodeTransition(sessionId = sessionId, pageState = pageState)
        override fun createActionPlanner(): LabyrinthEntryActionPlanner =
            this@createPageRouterWorkflow.entryWorkflow.createActionPlanner()
        override fun dispatchPostEntryTap(
                sessionId: AutomationSessionId,
                kind: LabyrinthPostEntryActionKind,
                label: String,
                rect: EntryPixelRect,
                timestampMillis: Long,
                roleRewardSelectionSignature: String?,
                roleRewardSelectedCharacterId: String?,
                shopPurchaseRelicId: String?,
                shopPurchaseRoleImprintLabel: String?,
                eventFreeRoleCandidateId: String?,
                eventFreeRoleConfirm: Boolean,
                eventFreeRoleConfirmIds: List<String>,
            ): Unit =
            this@createPageRouterWorkflow.pageActionWorkflow.dispatchPostEntryTap(sessionId = sessionId, kind = kind, label = label, rect = rect, timestampMillis = timestampMillis, roleRewardSelectionSignature = roleRewardSelectionSignature, roleRewardSelectedCharacterId = roleRewardSelectedCharacterId, shopPurchaseRelicId = shopPurchaseRelicId, shopPurchaseRoleImprintLabel = shopPurchaseRoleImprintLabel, eventFreeRoleCandidateId = eventFreeRoleCandidateId, eventFreeRoleConfirm = eventFreeRoleConfirm, eventFreeRoleConfirmIds = eventFreeRoleConfirmIds)
        override fun endGuardedAction(): Unit =
            this@createPageRouterWorkflow.runtimeEndGuardedAction()
        override fun finishFromPlanner(sessionId: AutomationSessionId, reason: String): Unit =
            this@createPageRouterWorkflow.runtimeFinishFromPlanner(sessionId = sessionId, reason = reason)
        override fun handleActionDecision(
                sessionId: AutomationSessionId,
                result: LabyrinthEntryFrameResult,
                frameWidth: Int,
                frameHeight: Int,
                timestampMillis: Long,
            ): Unit =
            this@createPageRouterWorkflow.entryWorkflow.handleActionDecision(sessionId = sessionId, result = result, frameWidth = frameWidth, frameHeight = frameHeight, timestampMillis = timestampMillis)
        override fun handleBattleFailureFrame(sessionId: AutomationSessionId, result: LabyrinthEntryFrameResult): Boolean =
            this@createPageRouterWorkflow.battleCombatWorkflow.handleBattleFailureFrame(sessionId = sessionId, result = result)
        override fun handleBattleTeamSelectionExecution(
                sessionId: AutomationSessionId,
                result: LabyrinthEntryFrameResult,
                frameWidth: Int,
                frameHeight: Int,
                timestampMillis: Long,
            ): Unit =
            this@createPageRouterWorkflow.battleTeamWorkflow.handleBattleTeamSelectionExecution(sessionId = sessionId, result = result, frameWidth = frameWidth, frameHeight = frameHeight, timestampMillis = timestampMillis)
        override fun handleBattleWaitFrame(
                sessionId: AutomationSessionId,
                result: LabyrinthEntryFrameResult,
                timestampMillis: Long,
            ): Boolean =
            this@createPageRouterWorkflow.battleCombatWorkflow.handleBattleWaitFrame(sessionId = sessionId, result = result, timestampMillis = timestampMillis)
        override fun handleEventFreeRoleFrame(sessionId: AutomationSessionId, result: LabyrinthEntryFrameResult, timestampMillis: Long, dryRun: Boolean, routeHasPassedOpening: Boolean): Boolean =
            this@createPageRouterWorkflow.eventWorkflow.handleEventFreeRoleFrame(sessionId = sessionId, result = result, timestampMillis = timestampMillis, dryRun = dryRun, routeHasPassedOpening = routeHasPassedOpening)
        override fun handleExEncounterRecognitionFrame(
                sessionId: AutomationSessionId,
                result: LabyrinthEntryFrameResult,
                timestampMillis: Long,
            ): Boolean =
            this@createPageRouterWorkflow.encounterWorkflow.handleExEncounterRecognitionFrame(sessionId = sessionId, result = result, timestampMillis = timestampMillis)
        override fun handleNodeMoveConfirmationFrame(
                sessionId: AutomationSessionId,
                result: LabyrinthEntryFrameResult,
                timestampMillis: Long,
            ): Boolean =
            this@createPageRouterWorkflow.mapTransitionWorkflow.handleNodeMoveConfirmationFrame(sessionId = sessionId, result = result, timestampMillis = timestampMillis)
        override fun handleNodeSelectionFrame(
                sessionId: AutomationSessionId,
                result: LabyrinthEntryFrameResult,
                frameWidth: Int,
                frameHeight: Int,
                timestampMillis: Long,
            ): Unit =
            this@createPageRouterWorkflow.mapNavigationWorkflow.handleNodeSelectionFrame(sessionId = sessionId, result = result, frameWidth = frameWidth, frameHeight = frameHeight, timestampMillis = timestampMillis)
        override fun handleOrphanMoveConfirmation(sessionId: AutomationSessionId, result: LabyrinthEntryFrameResult, timestampMillis: Long, dryRun: Boolean): Boolean =
            this@createPageRouterWorkflow.orphanMoveRecoveryWorkflow.handleOrphanMoveConfirmation(sessionId = sessionId, result = result, timestampMillis = timestampMillis, dryRun = dryRun)
        override fun handleSessionBlockFrame(
                sessionId: AutomationSessionId,
                result: LabyrinthEntryFrameResult,
                frameWidth: Int,
                frameHeight: Int,
                timestampMillis: Long,
                dryRun: Boolean,
            ): Boolean =
            this@createPageRouterWorkflow.recoveryWorkflow.handleSessionBlockFrame(sessionId = sessionId, result = result, frameWidth = frameWidth, frameHeight = frameHeight, timestampMillis = timestampMillis, dryRun = dryRun)
        override fun handleSettlementProgress(sessionId: AutomationSessionId, pageState: LabyrinthEntryPageState, timestampMillis: Long): Boolean =
            this@createPageRouterWorkflow.settlementWorkflow.handleSettlementProgress(sessionId = sessionId, pageState = pageState, timestampMillis = timestampMillis)
        override fun handleShopRefreshConfirmationFrame(
                sessionId: AutomationSessionId,
                result: LabyrinthEntryFrameResult,
                timestampMillis: Long,
            ): Boolean =
            this@createPageRouterWorkflow.shopWorkflow.handleShopRefreshConfirmationFrame(sessionId = sessionId, result = result, timestampMillis = timestampMillis)
        override fun handleSpecialEquipmentFlowFrame(
                sessionId: AutomationSessionId,
                result: LabyrinthEntryFrameResult,
                timestampMillis: Long,
            ): Boolean =
            this@createPageRouterWorkflow.equipmentWorkflow.handleSpecialEquipmentFlowFrame(sessionId = sessionId, result = result, timestampMillis = timestampMillis)
        override fun invalidatePreviousPageState(
                pageState: LabyrinthEntryPageState,
                preservePendingNodeMoveConfirmation: Boolean,
            ): Unit =
            this@createPageRouterWorkflow.runBoundaryWorkflow.invalidatePreviousPageState(pageState = pageState, preservePendingNodeMoveConfirmation = preservePendingNodeMoveConfirmation)
        override fun nodeExecutionConfigured(): Boolean =
            this@createPageRouterWorkflow.runtimeNodeExecutionConfigured()
        override fun nodeLog(message: String, warning: Boolean): Unit =
            this@createPageRouterWorkflow.runtimeNodeLog(message = message, warning = warning)
        override fun persistObservation(
                sessionId: AutomationSessionId,
                result: LabyrinthEntryFrameResult,
                timestampMillis: Long,
            ): Unit =
            this@createPageRouterWorkflow.persistenceWorkflow.persistObservation(sessionId = sessionId, result = result, timestampMillis = timestampMillis)
        override fun planBattlePage(result: LabyrinthEntryFrameResult, frameWidth: Int, frameHeight: Int): LabyrinthPostEntryTapPlan? =
            this@createPageRouterWorkflow.battleCombatWorkflow.planBattlePage(result = result, frameWidth = frameWidth, frameHeight = frameHeight)
        override fun planEventAnimationPage(result: LabyrinthEntryFrameResult, frameWidth: Int, frameHeight: Int, characterAcquisitionActive: Boolean): LabyrinthPostEntryTapPlan? =
            this@createPageRouterWorkflow.eventWorkflow.planEventAnimationPage(result = result, frameWidth = frameWidth, frameHeight = frameHeight, characterAcquisitionActive = characterAcquisitionActive)
        override fun planEventChoice(sessionId: AutomationSessionId, result: LabyrinthEntryFrameResult, timestampMillis: Long): LabyrinthPostEntryTapPlan? =
            this@createPageRouterWorkflow.eventWorkflow.planEventChoice(sessionId = sessionId, result = result, timestampMillis = timestampMillis)
        override fun planRelicChoice(sessionId: AutomationSessionId, result: LabyrinthEntryFrameResult, timestampMillis: Long): LabyrinthPostEntryTapPlan? =
            this@createPageRouterWorkflow.rewardWorkflow.planRelicChoice(sessionId = sessionId, result = result, timestampMillis = timestampMillis)
        override fun planRewardPage(result: LabyrinthEntryFrameResult, frameWidth: Int, frameHeight: Int): LabyrinthPostEntryTapPlan? =
            this@createPageRouterWorkflow.rewardWorkflow.planRewardPage(result = result, frameWidth = frameWidth, frameHeight = frameHeight)
        override fun planRoleRewardPage(result: LabyrinthEntryFrameResult): LabyrinthPageActionProposal =
            this@createPageRouterWorkflow.rewardWorkflow.planRoleRewardPage(result = result)
        override fun planSettlementPage(result: LabyrinthEntryFrameResult, frameWidth: Int, frameHeight: Int, finalAnimationActive: Boolean, finalAnimationPage: Boolean): LabyrinthPostEntryTapPlan? =
            this@createPageRouterWorkflow.settlementWorkflow.planSettlementPage(result = result, frameWidth = frameWidth, frameHeight = frameHeight, finalAnimationActive = finalAnimationActive, finalAnimationPage = finalAnimationPage)
        override fun planShopPage(result: LabyrinthEntryFrameResult, frameWidth: Int, frameHeight: Int, timestampMillis: Long): LabyrinthPageActionProposal =
            this@createPageRouterWorkflow.shopWorkflow.planShopPage(result = result, frameWidth = frameWidth, frameHeight = frameHeight, timestampMillis = timestampMillis)
        override fun postEntryIsRoleRewardPage(result: LabyrinthEntryFrameResult): Boolean =
            this@createPageRouterWorkflow.rewardWorkflow.postEntryIsRoleRewardPage(result = result)
        override fun prepareMapTransitionFrame(sessionId: AutomationSessionId, pageState: LabyrinthEntryPageState, result: LabyrinthEntryFrameResult, timestampMillis: Long, dryRun: Boolean): Boolean? =
            this@createPageRouterWorkflow.mapTransitionWorkflow.prepareMapTransitionFrame(sessionId = sessionId, pageState = pageState, result = result, timestampMillis = timestampMillis, dryRun = dryRun)
        override fun publishOverlay(sessionId: AutomationSessionId): Unit =
            this@createPageRouterWorkflow.runtimePublishOverlay(sessionId = sessionId)
        override fun refreshBattleTeamRecommendation(
                sessionId: AutomationSessionId,
                pageState: LabyrinthEntryPageState,
            ): Unit =
            this@createPageRouterWorkflow.battleRecommendationWorkflow.refreshBattleTeamRecommendation(sessionId = sessionId, pageState = pageState)
        override fun refreshBattleTeamSelectionPlan(
                sessionId: AutomationSessionId,
                pageState: LabyrinthEntryPageState,
                observation: com.landosol.toolbox.labyrinth.vision.LabyrinthBattleTeamObservation?,
            ): Unit =
            this@createPageRouterWorkflow.battleRecommendationWorkflow.refreshBattleTeamSelectionPlan(sessionId = sessionId, pageState = pageState, observation = observation)
        override fun refreshEffectiveCharacterScan(
                sessionId: AutomationSessionId,
                result: LabyrinthEntryFrameResult,
                frameWidth: Int,
                frameHeight: Int,
                timestampMillis: Long,
            ): Unit =
            this@createPageRouterWorkflow.battleRosterWorkflow.refreshEffectiveCharacterScan(sessionId = sessionId, result = result, frameWidth = frameWidth, frameHeight = frameHeight, timestampMillis = timestampMillis)
        override fun refreshEventChoiceDecision(
                sessionId: AutomationSessionId,
                result: LabyrinthEntryFrameResult,
            ): Unit =
            this@createPageRouterWorkflow.eventWorkflow.refreshEventChoiceDecision(sessionId = sessionId, result = result)
        override fun refreshRelicStackCalibration(result: LabyrinthEntryFrameResult): Unit =
            this@createPageRouterWorkflow.rewardWorkflow.refreshRelicStackCalibration(result = result)
        override fun refreshRoleRewardChoiceDecision(
                sessionId: AutomationSessionId,
                result: LabyrinthEntryFrameResult,
                frameWidth: Int,
                frameHeight: Int,
            ): Unit =
            this@createPageRouterWorkflow.rewardWorkflow.refreshRoleRewardChoiceDecision(sessionId = sessionId, result = result, frameWidth = frameWidth, frameHeight = frameHeight)
        override fun resetBattleTeamExecutionTracking(): Unit =
            this@createPageRouterWorkflow.battleTeamWorkflow.resetBattleTeamExecutionTracking()
        override fun resetEventChoiceCommit(): Unit =
            this@createPageRouterWorkflow.eventWorkflow.resetEventChoiceCommit()
        override fun synchronizeBossEditor(result: LabyrinthEntryFrameResult): Unit =
            this@createPageRouterWorkflow.battleRecommendationWorkflow.synchronizeBossEditor(result = result)
        override fun traceReject(reason: String): Unit =
            this@createPageRouterWorkflow.runtimeTraceReject(reason = reason)
        override fun traceWorkflow(owner: String, phase: String): Unit =
            this@createPageRouterWorkflow.runtimeTraceWorkflow(owner = owner, phase = phase)
        override fun updateRewardAnimationProgress(sessionId: AutomationSessionId, result: LabyrinthEntryFrameResult, timestampMillis: Long): Unit =
            this@createPageRouterWorkflow.rewardWorkflow.updateRewardAnimationProgress(sessionId = sessionId, result = result, timestampMillis = timestampMillis)
        override fun updateRewardPageProgress(sessionId: AutomationSessionId, result: LabyrinthEntryFrameResult, previousPostEntryState: LabyrinthEntryPageState?, timestampMillis: Long): Unit =
            this@createPageRouterWorkflow.rewardWorkflow.updateRewardPageProgress(sessionId = sessionId, result = result, previousPostEntryState = previousPostEntryState, timestampMillis = timestampMillis)
    })

internal fun LabyrinthEntryRecognitionSession.createPageActionWorkflow(): LabyrinthPageActionWorkflow =
    LabyrinthPageActionWorkflow(object : LabyrinthPageActionPort {
        override val _state: MutableStateFlow<LabyrinthEntryRecognitionSessionState>
            get() = this@createPageActionWorkflow._state
        override val actionExecutor: SessionBoundActionExecutor?
            get() = this@createPageActionWorkflow.actionExecutor
        override val activeSessionId: AutomationSessionId?
            get() = this@createPageActionWorkflow.activeSessionId
        override val clock: () -> Long
            get() = this@createPageActionWorkflow.clock
        override var lastPostEntryActionAt: Long
            get() = this@createPageActionWorkflow.lastPostEntryActionAt
            set(value) { this@createPageActionWorkflow.lastPostEntryActionAt = value }
        override var lastPostEntryActionLandedAt: Long
            get() = this@createPageActionWorkflow.lastPostEntryActionLandedAt
            set(value) { this@createPageActionWorkflow.lastPostEntryActionLandedAt = value }
        override var pendingAcquiredCharacterId: String?
            get() = this@createPageActionWorkflow.pendingAcquiredCharacterId
            set(value) { this@createPageActionWorkflow.pendingAcquiredCharacterId = value }
        override var postEntryAttempts: Int
            get() = this@createPageActionWorkflow.postEntryAttempts
            set(value) { this@createPageActionWorkflow.postEntryAttempts = value }
        override fun beginGuardedAction(): Boolean =
            this@createPageActionWorkflow.runtimeBeginGuardedAction()
        override fun captureRoleRewardTap(roleRewardSelectedCharacterId: String?): LabyrinthRoleRewardTapContext =
            this@createPageActionWorkflow.rewardWorkflow.captureRoleRewardTap(roleRewardSelectedCharacterId = roleRewardSelectedCharacterId)
        override fun clearPlannedShopPurchase(): Unit =
            this@createPageActionWorkflow.shopWorkflow.clearPlannedShopPurchase()
        override fun launchGuardedAction(block: suspend () -> Unit): Unit =
            this@createPageActionWorkflow.runtimeLaunchGuardedAction(block = block)
        override fun onBattleTapExecuted(kind: LabyrinthPostEntryActionKind): Unit =
            this@createPageActionWorkflow.battleCombatWorkflow.onBattleTapExecuted(kind = kind)
        override fun onEncounterTapExecuted(kind: LabyrinthPostEntryActionKind): Unit =
            this@createPageActionWorkflow.encounterWorkflow.onEncounterTapExecuted(kind = kind)
        override fun onEncounterTapRejected(kind: LabyrinthPostEntryActionKind): Unit =
            this@createPageActionWorkflow.encounterWorkflow.onEncounterTapRejected(kind = kind)
        override fun onEventTapDispatched(kind: LabyrinthPostEntryActionKind): Unit =
            this@createPageActionWorkflow.eventWorkflow.onEventTapDispatched(kind = kind)
        override fun onEventTapExecuted(kind: LabyrinthPostEntryActionKind, eventFreeRoleCandidateId: String?, eventFreeRoleConfirm: Boolean, eventFreeRoleConfirmIds: List<String>): Unit =
            this@createPageActionWorkflow.eventWorkflow.onEventTapExecuted(kind = kind, eventFreeRoleCandidateId = eventFreeRoleCandidateId, eventFreeRoleConfirm = eventFreeRoleConfirm, eventFreeRoleConfirmIds = eventFreeRoleConfirmIds)
        override fun onRewardPageClosed(kind: LabyrinthPostEntryActionKind, timestampMillis: Long): Unit =
            this@createPageActionWorkflow.rewardWorkflow.onRewardPageClosed(kind = kind, timestampMillis = timestampMillis)
        override fun onRewardTapDispatched(kind: LabyrinthPostEntryActionKind, timestampMillis: Long): Unit =
            this@createPageActionWorkflow.rewardWorkflow.onRewardTapDispatched(kind = kind, timestampMillis = timestampMillis)
        override suspend fun onRewardTapExecuted(sessionId: AutomationSessionId, kind: LabyrinthPostEntryActionKind, roleRewardSelectionSignature: String?, roleRewardSelectedCharacterId: String?, context: LabyrinthRoleRewardTapContext, rect: EntryPixelRect): Unit =
            this@createPageActionWorkflow.rewardWorkflow.onRewardTapExecuted(sessionId = sessionId, kind = kind, roleRewardSelectionSignature = roleRewardSelectionSignature, roleRewardSelectedCharacterId = roleRewardSelectedCharacterId, context = context, rect = rect)
        override fun onRewardTapNotForeground(kind: LabyrinthPostEntryActionKind): Unit =
            this@createPageActionWorkflow.rewardWorkflow.onRewardTapNotForeground(kind = kind)
        override fun onSettlementTapDispatched(kind: LabyrinthPostEntryActionKind): Unit =
            this@createPageActionWorkflow.settlementWorkflow.onSettlementTapDispatched(kind = kind)
        override fun onSettlementTapExecuted(kind: LabyrinthPostEntryActionKind, timestampMillis: Long): Unit =
            this@createPageActionWorkflow.settlementWorkflow.onSettlementTapExecuted(kind = kind, timestampMillis = timestampMillis)
        override fun onSettlementTapNotForeground(kind: LabyrinthPostEntryActionKind): Unit =
            this@createPageActionWorkflow.settlementWorkflow.onSettlementTapNotForeground(kind = kind)
        override fun onShopImprintTapExecuted(kind: LabyrinthPostEntryActionKind): Unit =
            this@createPageActionWorkflow.shopWorkflow.onShopImprintTapExecuted(kind = kind)
        override fun onShopTapDispatched(shopPurchaseRelicId: String?, shopPurchaseRoleImprintLabel: String?, timestampMillis: Long): Unit =
            this@createPageActionWorkflow.shopWorkflow.onShopTapDispatched(shopPurchaseRelicId = shopPurchaseRelicId, shopPurchaseRoleImprintLabel = shopPurchaseRoleImprintLabel, timestampMillis = timestampMillis)
        override fun onShopTapExecuted(kind: LabyrinthPostEntryActionKind): Unit =
            this@createPageActionWorkflow.shopWorkflow.onShopTapExecuted(kind = kind)
        override fun publishOverlay(sessionId: AutomationSessionId): Unit =
            this@createPageActionWorkflow.runtimePublishOverlay(sessionId = sessionId)
        override fun publishWaitingForGameForeground(sessionId: AutomationSessionId): Unit =
            this@createPageActionWorkflow.runtimePublishWaitingForGameForeground(sessionId = sessionId)
        override fun resetPostEntryStall(): Unit =
            this@createPageActionWorkflow.pageRouterWorkflow.resetPostEntryStall()
        override suspend fun stopFromRunLogic(reason: String): Boolean =
            this@createPageActionWorkflow.runtimeStopFromRunLogic(reason = reason)
        override fun traceAction(label: String): Unit =
            this@createPageActionWorkflow.runtimeTraceAction(label = label)
    })

internal fun LabyrinthEntryRecognitionSession.createPresentationWorkflow(): LabyrinthPresentationWorkflow =
    LabyrinthPresentationWorkflow(object : LabyrinthPresentationPort {
        override val _state: StateFlow<LabyrinthEntryRecognitionSessionState>
            get() = this@createPresentationWorkflow._state
        override val battleTeamRecommendationUnavailableReason: String?
            get() = this@createPresentationWorkflow.battleTeamRecommendationUnavailableReason
        override val captureActive: () -> Boolean
            get() = this@createPresentationWorkflow.captureActive
        override val nodeActionPlanner: LabyrinthNodeActionPlanner
            get() = this@createPresentationWorkflow.nodeActionPlanner
        override val nodeSession: LabyrinthNodeSession?
            get() = this@createPresentationWorkflow.nodeSession
        override val pendingAcquiredCharacterId: String?
            get() = this@createPresentationWorkflow.pendingAcquiredCharacterId
        override val relicChoicePolicy: LabyrinthRelicChoicePolicy
            get() = this@createPresentationWorkflow.relicChoicePolicy
        override val relicFocusMark: LabyrinthRelicMark?
            get() = this@createPresentationWorkflow.relicFocusMark
        override val relicMarkStackCalibration: Map<LabyrinthRelicMark, Int>
            get() = this@createPresentationWorkflow.relicMarkStackCalibration
        override val relicStackLedger: LabyrinthRelicStackLedger
            get() = this@createPresentationWorkflow.relicStackLedger
        override val routeNodeCount: Int
            get() = this@createPresentationWorkflow.routeNodeCount
        override fun currentShopDecision(result: LabyrinthEntryFrameResult): LabyrinthShopDecision? =
            this@createPresentationWorkflow.shopWorkflow.currentShopDecision(result = result)
    })
