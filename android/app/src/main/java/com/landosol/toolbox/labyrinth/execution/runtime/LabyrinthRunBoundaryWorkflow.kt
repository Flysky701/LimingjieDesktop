package com.landosol.toolbox.labyrinth.execution.runtime

import com.landosol.toolbox.labyrinth.*
import com.landosol.toolbox.labyrinth.vision.LabyrinthEntryPageState
import com.landosol.toolbox.labyrinth.vision.LabyrinthShopDialogState
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow

internal interface LabyrinthRunBoundaryPort {
    val _state: MutableStateFlow<LabyrinthEntryRecognitionSessionState>
    var actionPlanner: LabyrinthEntryActionPlanner?
    var activeNodeArea: Int?
    var activeNodeType: Int?
    var battleTeamRecommendationPlanner: LabyrinthBattleTeamRecommendationPlanner?
    val battleTeamRecommendationUnavailableReason: String?
    var battleTeamSelectionPlanner: LabyrinthBattleTeamSelectionPlanner?
    val battleWait: LabyrinthBattleWaitPolicy
    var bossFallbackMultiTeamActive: Boolean
    var bossTeamMode: LabyrinthBossTeamMode
    var combatContext: LabyrinthCombatContext?
    var configuredBossTeamMode: LabyrinthBossTeamMode
    var configuredOpeningRosters: Map<Int, List<List<String>>>
    var eventActionAttempts: Int
    var eventChoicePlanner: LabyrinthEventChoicePlanner?
    var existingRunResumeHandoffArmed: Boolean
    var lastObservedPageState: LabyrinthEntryPageState?
    var lastPersistedObservationSignature: String?
    var lastShopDialogState: LabyrinthShopDialogState
    val openingStateReset: AtomicBoolean
    var pendingSingleBossFallbackToMulti: Boolean
    var preferPureBossDamageSystem: Boolean
    var relicChoicePolicy: LabyrinthRelicChoicePolicy
    val relicStackLedger: LabyrinthRelicStackLedger
    var rerollAfterThreeBattleFailures: Boolean
    var responseIntervalMillis: Long
    var roleDisplayNames: Map<String, String>
    var roleRewardChoicePlanner: LabyrinthRoleRewardChoicePlanner?
    var shopPolicy: LabyrinthShopPolicy
    var singleBossFallbackToMultiAfterThreeFailures: Boolean
    var singleBossRetryCountBeforeMulti: Int
    var specialEquipmentSettings: LabyrinthStrategySettings?
    fun clearShopChoiceImprintRolePending(): Unit
    fun createActionPlanner(): LabyrinthEntryActionPlanner
    fun onBattlePageChanged(pageState: LabyrinthEntryPageState): Unit
    fun onEventPageChanged(pageState: LabyrinthEntryPageState): Unit
    fun onMapPageChanged(pageState: LabyrinthEntryPageState, preservePendingNodeMoveConfirmation: Boolean): Unit
    fun onRewardPageChanged(pageState: LabyrinthEntryPageState): Unit
    fun onShopPageChanged(pageState: LabyrinthEntryPageState): Unit
    fun onShopPurchasePageObserved(pageState: LabyrinthEntryPageState): Unit
    fun prepareBattleEditor(blockType: Int): Unit
    fun prepareBattleEntry(blockType: Int): Unit
    fun prepareEventEntry(): Unit
    fun prepareMapEntry(): Unit
    fun prepareShopEntry(blockType: Int): Unit
    fun resetBattleRetryTracking(): Unit
    fun resetCombatRuntimeState(): Unit
    fun resetEffectiveCharacterScan(): Unit
    fun resetEventRosterState(): Unit
    fun resetExEncounterTracking(): Unit
    fun resetMapRuntimeState(): Unit
    fun resetPostEntryRuntimeState(): Unit
    fun resetRelicsOnStart(): Unit
    fun resetRewardOnStart(): Unit
    fun resetRewardOnStop(): Unit
    fun resetRewardRuntimeState(): Unit
    fun resetSessionRecoveryTracking(): Unit
    fun resetSettlementRuntimeState(): Unit
    fun resetShopOnStart(): Unit
    fun resetShopOnStop(): Unit
    fun invalidateBattleTeamRecommendation()
    fun clearBattleTeamSelectionPlanLog()
}

/** 按原顺序协调启动、停止、换页和进入节点的业务交接。 */
internal class LabyrinthRunBoundaryWorkflow(private val port: LabyrinthRunBoundaryPort) : LabyrinthRunBoundaryPort by port {
    fun resetNodeExecutionState() {
        battleWait.reset()
        resetEventRosterState()
        resetMapRuntimeState()
        resetPostEntryRuntimeState()
        resetRewardRuntimeState()
        existingRunResumeHandoffArmed = false
        resetSettlementRuntimeState()
        resetCombatRuntimeState()
        activeNodeType = null
        eventActionAttempts = 0
        _state.value = _state.value.copy(
            combatContext = null,
            battleTeamRecommendation = null,
            battleTeamRecommendationUnavailableReason = null,
            battleTeamSelectionPlan = null,
        )
        resetSessionRecoveryTracking()
    }

    fun prepareNodeTransitionContext(blockType: Int, area: Int?) {
        prepareMapEntry()
        prepareEventEntry()
        val previousNodeType = activeNodeType
        activeNodeType = blockType
        activeNodeArea = area
        eventActionAttempts = 0
        prepareShopEntry(blockType)
        prepareBattleEntry(blockType)
        clearShopChoiceImprintRolePending()
        resetBattleRetryTracking()
        resetExEncounterTracking()
        // Walking out of an EX fight straight into the Boss of the same encounter used to wipe the
        // "有效效果" sweep that had just completed, so the Boss editor re-swept the whole filtered
        // roster for an identical answer. Keep that sweep across this one hand-off; every other
        // node entry (and any owned-roster change in between) still forces a fresh scan.
        if (!labyrinthKeepsEffectiveScanAcrossNodes(previousNodeType, blockType)) {
            resetEffectiveCharacterScan()
        }
        prepareBattleEditor(blockType)

    }

    /** Clear viewport-local proposals on page changes; route and acquisition context may survive. */
    fun invalidatePreviousPageState(
        pageState: LabyrinthEntryPageState,
        preservePendingNodeMoveConfirmation: Boolean = false,
    ) {
        val previous = lastObservedPageState
        if (previous == pageState) return
        lastObservedPageState = pageState

        onShopPurchasePageObserved(pageState)
        onMapPageChanged(pageState, preservePendingNodeMoveConfirmation)
        onBattlePageChanged(pageState)
        onRewardPageChanged(pageState)
        onEventPageChanged(pageState)
        onShopPageChanged(pageState)

    }

    fun configureRunStrategy(strategySnapshot: LabyrinthStrategySnapshot?): Unit {
        rerollAfterThreeBattleFailures = false
        singleBossFallbackToMultiAfterThreeFailures = false
        singleBossRetryCountBeforeMulti = 2
        pendingSingleBossFallbackToMulti = false
        bossFallbackMultiTeamActive = false
        strategySnapshot?.let { snapshot ->
            configuredBossTeamMode = snapshot.settings.bossTeamMode
            bossTeamMode = configuredBossTeamMode
            preferPureBossDamageSystem = snapshot.settings.preferPureBossDamageSystem
            specialEquipmentSettings = snapshot.settings
            responseIntervalMillis = snapshot.settings.responseIntervalMillis.toLong()
            singleBossFallbackToMultiAfterThreeFailures =
                snapshot.settings.singleBossFallbackToMultiAfterThreeFailures
            singleBossRetryCountBeforeMulti = snapshot.settings.singleBossRetryCountBeforeMulti
            rerollAfterThreeBattleFailures = snapshot.settings.rerollAfterThreeBattleFailures
            relicChoicePolicy = snapshot.settings.relicPolicy()
            shopPolicy = LabyrinthShopPolicy(
                relicChoicePolicy = relicChoicePolicy,
                finalArea = snapshot.settings.refreshFromArea,
                buyRelics = snapshot.settings.buyRelics,
                refreshEnabled = snapshot.settings.refreshShop,
                highScore = snapshot.settings.highScorePreset,
            )
            configuredOpeningRosters = snapshot.settings.openingRosters
            roleDisplayNames = snapshot.roleRuntime?.document?.characters
                ?.associate { it.characterId to it.displayName }
                .orEmpty()
            roleRewardChoicePlanner = snapshot.roleRuntime?.rewardChoicePlanner
            eventChoicePlanner = snapshot.roleRuntime?.eventChoicePlanner
            battleTeamRecommendationPlanner = snapshot.roleRuntime?.battleTeamRecommendationPlanner
            battleTeamSelectionPlanner = snapshot.roleRuntime?.battleTeamSelectionPlanner
        }
    }

    fun initializeRunBusinessState(dryRun: Boolean, initialRunSnapshot: LabyrinthRunSnapshot?): Unit {
        openingStateReset.set(false)
        lastPersistedObservationSignature = null
        invalidateBattleTeamRecommendation()
        clearBattleTeamSelectionPlanLog()
        resetBattleRetryTracking()
        lastObservedPageState = null
        resetRewardOnStart()
        existingRunResumeHandoffArmed = false
        resetShopOnStart()
        resetRelicsOnStart()
        lastShopDialogState = LabyrinthShopDialogState.NONE
        resetNodeExecutionState()
        resetExEncounterTracking()
        resetEffectiveCharacterScan()
        actionPlanner = if (dryRun) null else createActionPlanner()
        relicStackLedger.seedAcquisitions(relicChoicePolicy.markStacks(initialRunSnapshot?.observedRelics.orEmpty()))
    }

    fun clearStoppedRunBusinessState(): Unit {
        openingStateReset.set(false)
        lastPersistedObservationSignature = null
        invalidateBattleTeamRecommendation()
        clearBattleTeamSelectionPlanLog()
        lastObservedPageState = null
        resetRewardOnStop()
        existingRunResumeHandoffArmed = false
        resetShopOnStop()
        relicStackLedger.clearPending()
        resetNodeExecutionState()
    }
}
