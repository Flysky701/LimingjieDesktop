package com.landosol.toolbox.labyrinth

import com.landosol.toolbox.automation.AutomationSessionId
import com.landosol.toolbox.labyrinth.vision.LabyrinthEntryFrameResult

enum class LabyrinthEntryRecognitionStatus { IDLE, RUNNING, STOPPED, ERROR }

data class LabyrinthEntryRecognitionSessionState(
    val status: LabyrinthEntryRecognitionStatus = LabyrinthEntryRecognitionStatus.IDLE,
    val sessionId: AutomationSessionId? = null,
    val dryRun: Boolean = true,
    val paused: Boolean = false,
    val receivedFrameCount: Long = 0,
    val frameCount: Long = 0,
    val actionCount: Int = 0,
    val lastActionLabel: String? = null,
    val lastResult: LabyrinthEntryFrameResult? = null,
    val joinedCharacters: List<LabyrinthJoinedCharacter> = emptyList(),
    val observedRelics: List<LabyrinthObservedRelic> = emptyList(),
    val routeProgress: LabyrinthRouteProgress? = null,
    val combatContext: LabyrinthCombatContext? = null,
    val battleTeamRecommendation: LabyrinthBattleTeamRecommendation? = null,
    val battleTeamRecommendationUnavailableReason: String? = null,
    val battleTeamSelectionPlan: LabyrinthBattleTeamSelectionPlan? = null,
    val roleRewardChoiceDecision: LabyrinthRoleRewardChoiceDecision? = null,
    val eventChoiceDecision: LabyrinthEventChoiceDecision? = null,
    val pendingAcquiredCharacterId: String? = null,
    val message: String? = null,
    /** Diagnostic trace for the frame that produced [lastResult]; never read for decisions. */
    val frameTrace: LabyrinthFrameTrace = LabyrinthFrameTrace(),
) {
    val running: Boolean get() = status == LabyrinthEntryRecognitionStatus.RUNNING
}

sealed interface LabyrinthEntryRecognitionStartResult {
    data class Started(val sessionId: AutomationSessionId) : LabyrinthEntryRecognitionStartResult
    data class AlreadyRunning(val sessionId: AutomationSessionId) : LabyrinthEntryRecognitionStartResult
    data class Blocked(val reason: String) : LabyrinthEntryRecognitionStartResult
}

internal data class LabyrinthPageActionProposal(val plan: LabyrinthPostEntryTapPlan?, val waitReason: String? = null)
