package com.landosol.toolbox.labyrinth

import com.landosol.toolbox.automation.AutomationSessionId
import com.landosol.toolbox.automation.AutomationSessionManager
import com.landosol.toolbox.labyrinth.vision.EntryPixelRect
import com.landosol.toolbox.labyrinth.vision.LabyrinthBattleCharacterMatch
import com.landosol.toolbox.labyrinth.vision.LabyrinthBattleElementFilter
import com.landosol.toolbox.labyrinth.vision.LabyrinthBattleFilterObservation
import com.landosol.toolbox.labyrinth.vision.LabyrinthBattleScrollbarObservation
import com.landosol.toolbox.labyrinth.vision.LabyrinthBattleTeamObservation
import com.landosol.toolbox.labyrinth.vision.LabyrinthBattleTeamRecognitionState
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class LabyrinthFrontlineProgressionTest {
    private val aggressive = LabyrinthFrontlineSurvivalMode.VERY_AGGRESSIVE
    private val stable = LabyrinthFrontlineSurvivalMode.STABLE
    private val cautious = LabyrinthFrontlineSurvivalMode.CAUTIOUS
    private val sessionId = AutomationSessionId(42L)
    private val planner = LabyrinthBattleTeamSelectionPlanner(
        mapOf("T" to LabyrinthRoleProfile("T", "坦克", roleClass = "掩护者")),
    )

    @Test fun initialShortageKeepsThisCombatStableButNeverChangesSavedMode() {
        val state = LabyrinthBattleFormationState()
        assertEquals(aggressive, state.requestedMode(aggressive))
        state.observe(aggressive, 1, stable, LabyrinthBattleFormationFallbackReason.INSUFFICIENT_NON_PROTECTORS)
        assertEquals(stable, state.requestedMode(aggressive))
        repeat(3) { assertEquals(stable, state.requestedMode(aggressive)) }
        assertEquals(cautious, state.requestedMode(cautious))
        state.reset()
        assertEquals(aggressive, state.requestedMode(aggressive))
    }

    @Test fun bossLaterTeamShortageDoesNotChangeWholeCombatMode() {
        val state = LabyrinthBattleFormationState()
        state.observe(aggressive, 2, stable, LabyrinthBattleFormationFallbackReason.INSUFFICIENT_NON_PROTECTORS)
        assertEquals(aggressive, state.requestedMode(aggressive))
        assertNull(state.stableFallbackReason)
    }

    @Test fun exhaustedAggressiveSearchKeepsStableAcrossSubsequentRetries() {
        val state = LabyrinthBattleFormationState()
        state.observe(aggressive, 2, stable, LabyrinthBattleFormationFallbackReason.NO_UNFAILED_FORMATION)
        assertEquals(stable, state.requestedMode(aggressive))
        state.observe(aggressive, 1, stable, null)
        assertEquals(stable, state.requestedMode(aggressive))
        assertNotNull(state.fallbackNote)
    }

    @Test fun otherModesNeverAcquireAggressiveFallbackState() {
        for (mode in listOf(stable, cautious)) {
            val state = LabyrinthBattleFormationState()
            state.observe(mode, 1, stable, LabyrinthBattleFormationFallbackReason.NO_UNFAILED_FORMATION)
            assertNull(state.stableFallbackReason)
            assertEquals(mode, state.requestedMode(mode))
        }
    }

    @Test fun legacyContextAndNewModeOnlyChangeHardGateNotOtherInputs() {
        val old = LabyrinthRoleDecisionContext(defenseMarkStacks = 4, targetCount = 3, frontlineSurvivalGateEnabled = true)
        assertEquals(cautious, old.effectiveFrontlineSurvivalMode)
        val next = old.withFrontlineSurvivalMode(aggressive)
        assertEquals(aggressive, next.effectiveFrontlineSurvivalMode)
        assertFalse(next.frontlineSurvivalGateEnabled)
        assertEquals(old.defenseMarkStacks, next.defenseMarkStacks)
        assertEquals(old.targetCount, next.targetCount)
        assertEquals(old.survivalRecovery, next.survivalRecovery)
        assertTrue(next.withFrontlineSurvivalMode(cautious).frontlineSurvivalGateEnabled)
    }

    @Test fun onlyConfirmedAggressiveExhaustionUsesExistingRerollPermission() {
        for (mode in LabyrinthFrontlineSurvivalMode.entries) {
            for (failure in LabyrinthBattleFormationFailure.entries) {
                for (reroll in listOf(false, true)) for (dry in listOf(false, true)) {
                    val expected = if (mode != aggressive || dry || failure != LabyrinthBattleFormationFailure.EXHAUSTED_AFTER_STABLE) {
                        LabyrinthExhaustedFormationAction.NONE
                    } else if (reroll) LabyrinthExhaustedFormationAction.REROLL else LabyrinthExhaustedFormationAction.PROMPT
                    assertEquals(expected, labyrinthExhaustedFormationAction(mode, failure, reroll, dry))
                }
            }
        }
    }

    @Test fun bossReductionRequiresAlreadyConfirmedTeamsAndInsufficientRemainingPool() {
        for (remaining in 0..4) {
            assertEquals(1, labyrinthAggressiveBossRemainingTeamTarget(aggressive, true, 2, remaining, 5))
            assertEquals(2, labyrinthAggressiveBossRemainingTeamTarget(aggressive, true, 3, remaining, 10))
        }
        assertNull(labyrinthAggressiveBossRemainingTeamTarget(aggressive, true, 1, 0, 0))
        assertNull(labyrinthAggressiveBossRemainingTeamTarget(aggressive, true, 2, 4, 4))
        assertNull(labyrinthAggressiveBossRemainingTeamTarget(aggressive, true, 3, 4, 5))
        assertNull(labyrinthAggressiveBossRemainingTeamTarget(aggressive, true, 2, 5, 5))
        assertNull(labyrinthAggressiveBossRemainingTeamTarget(aggressive, false, 2, 0, 5))
        assertNull(labyrinthAggressiveBossRemainingTeamTarget(stable, true, 2, 0, 5))
    }

    @Test fun bossTargetCountsAllRolesSoLaterProtectorTeamsCanLocallyFallBackToStable() {
        // One pure non-protector team plus five protectors still supports a second team.
        assertEquals(2, labyrinthAggressiveBossPlannedRemainingTeams(aggressive, true, 3, 10, 1))
        // Two pure teams plus five protectors must not silently leave the third tab empty.
        assertEquals(3, labyrinthAggressiveBossPlannedRemainingTeams(aggressive, true, 3, 15, 2))
        assertEquals(2, labyrinthAggressiveBossPlannedRemainingTeams(aggressive, true, 2, 10, 1))
        assertEquals(1, labyrinthAggressiveBossPlannedRemainingTeams(aggressive, true, 2, 9, 1))
        assertEquals(1, labyrinthAggressiveBossPlannedRemainingTeams(stable, true, 3, 15, 1))
        assertEquals(1, labyrinthAggressiveBossPlannedRemainingTeams(aggressive, false, 3, 15, 1))
    }

    @Test fun confirmedCurrentTeamIncludingProtectorStartsWithoutNewRecommendation() {
        val observation = observation()
        val plan = planner.plan(sessionId, null, observation, holdCurrentTeamRequested = true)
        assertTrue(plan.holdCurrentTeam)
        assertTrue(plan.teamReady)
        assertTrue(plan.needSelectIds.isEmpty())
        assertTrue(plan.needDeselectIds.isEmpty())
        assertTrue(plan.recommendedIds.contains("T"))
        assertEquals(LabyrinthBattleTeamExecutionKind.START_BATTLE, requireNotNull(
            labyrinthBattleTeamExecutionStep(plan, sessionId, null, observation, EntryPixelRect(1500, 900, 300, 100), 1920, 1080),
        ).kind)
    }

    @Test fun missingRecommendationCannotStartWhenCurrentTeamShortcutWasNotRequested() {
        val observation = observation()
        val plan = planner.plan(sessionId, null, observation, holdCurrentTeamRequested = false)
        assertFalse(plan.holdCurrentTeam)
        assertFalse(plan.readyToExecute)
        assertNull(labyrinthBattleTeamExecutionStep(plan, sessionId, null, observation,
            EntryPixelRect(1500, 900, 300, 100), 1920, 1080))
    }

    @Test fun shortUnknownAndLowConfidenceTeamsCannotBypassNormalSelection() {
        val variants = listOf(
            observation().copy(selectedCharacters = observation().selectedCharacters.take(4)),
            observation().copy(selectedCharacters = observation().selectedCharacters.mapIndexed { index, m ->
                if (index == 0) m.copy(characterId = null, trusted = false) else m
            }),
            observation().copy(selectedCharacters = observation().selectedCharacters.mapIndexed { index, m ->
                if (index == 0) m.copy(confidence = 0.1) else m
            }),
            observation().copy(recognitionState = LabyrinthBattleTeamRecognitionState.WAITING_FOR_STABILITY),
            observation().copy(currentFilter = LabyrinthBattleElementFilter.UNKNOWN),
        )
        for (view in variants) {
            val plan = planner.plan(sessionId, null, view, holdCurrentTeamRequested = true)
            assertFalse(plan.teamReady)
            assertNull(labyrinthBattleTeamExecutionStep(plan, sessionId, null, view,
                EntryPixelRect(1500, 900, 300, 100), 1920, 1080))
        }
    }

    @Test fun heldPlanRechecksFreshIdentityConfidenceSessionAndViewport() {
        val view = observation()
        val plan = planner.plan(sessionId, null, view, holdCurrentTeamRequested = true)
        assertTrue(plan.isStillValid(sessionId, null, view))
        assertFalse(plan.isStillValid(AutomationSessionId(43L), null, view))
        assertFalse(plan.isStillValid(sessionId, null, view.copy(viewportRevision = 2L)))
        val weaker = view.copy(selectedCharacters = view.selectedCharacters.mapIndexed { index, m ->
            if (index == 0) m.copy(confidence = 0.1) else m
        })
        assertFalse(plan.isStillValid(sessionId, null, weaker))
        assertNull(labyrinthBattleTeamExecutionStep(plan, sessionId, null, view, null, 1920, 1080))
    }

    @Test fun savedSkipSwitchStillOnlyRequestsFirstNormalBattle() {
        for (mode in LabyrinthFrontlineSurvivalMode.entries) {
            val settings = LabyrinthStrategySettings().withFrontlineSurvivalMode(mode)
                .copy(normalBattleSkipTeamSelectionEnabled = true)
            assertTrue(labyrinthRequestsCurrentNormalBattleTeam(settings, LabyrinthCombatKind.NORMAL, 0))
            assertFalse(labyrinthRequestsCurrentNormalBattleTeam(settings, LabyrinthCombatKind.NORMAL, 1))
            assertFalse(labyrinthRequestsCurrentNormalBattleTeam(settings, LabyrinthCombatKind.EX, 0))
            assertFalse(labyrinthRequestsCurrentNormalBattleTeam(settings, LabyrinthCombatKind.BOSS, 0))
        }
    }

    @Test fun sessionCanHoldSafeCurrentTeamEvenBeforeRecommendationRuntimeIsLoaded() = runTest {
        val saved = LabyrinthStrategySettings().withFrontlineSurvivalMode(aggressive)
            .copy(normalBattleSkipTeamSelectionEnabled = true)
        val session = session(saved)
        try {
            val started = session.start() as LabyrinthEntryRecognitionStartResult.Started
            setCombatContext(session, LabyrinthCombatKind.NORMAL)
            assertTrue(session.publishCurrentNormalTeamHold(started.sessionId, observation()))
            assertNull(session.state.value.battleTeamRecommendation)
            assertTrue(requireNotNull(session.state.value.battleTeamSelectionPlan).teamReady)
            val snapshot = LabyrinthEntryRecognitionSession::class.java.getDeclaredField("battleStrategySettingsSnapshot")
                .apply { isAccessible = true }.get(session) as LabyrinthStrategySettings
            assertEquals(aggressive, snapshot.effectiveFrontlineSurvivalMode)
            session.stop()
            assertFalse(session.publishCurrentNormalTeamHold(started.sessionId, observation()))
        } finally { session.stop() }
    }

    @Test fun sessionDoesNotTurnExOrDisabledSkipIntoCurrentTeamShortcut() = runTest {
        for (enabled in listOf(false, true)) {
            val saved = LabyrinthStrategySettings().withFrontlineSurvivalMode(aggressive)
                .copy(normalBattleSkipTeamSelectionEnabled = enabled)
            val session = session(saved)
            try {
                val started = session.start() as LabyrinthEntryRecognitionStartResult.Started
                setCombatContext(session, if (enabled) LabyrinthCombatKind.EX else LabyrinthCombatKind.NORMAL)
                assertFalse(session.publishCurrentNormalTeamHold(started.sessionId, observation()))
                assertNull(session.state.value.battleTeamSelectionPlan)
            } finally { session.stop() }
        }
    }

    private fun session(settings: LabyrinthStrategySettings) = LabyrinthEntryRecognitionSession(
        sessionManager = AutomationSessionManager(), captureActive = { true }, captureStop = {},
        strategyProvider = { LabyrinthStrategySnapshot(settings, null) },
        processorFactory = { { error("test does not dispatch frames") } },
    )

    private fun setCombatContext(session: LabyrinthEntryRecognitionSession, kind: LabyrinthCombatKind) {
        LabyrinthEntryRecognitionSession::class.java.getDeclaredField("combatContext")
            .apply { isAccessible = true }.set(session, LabyrinthCombatContext(kind))
    }

    private fun observation() = LabyrinthBattleTeamObservation(
        currentFilter = LabyrinthBattleElementFilter.ALL,
        filters = listOf(LabyrinthBattleFilterObservation(LabyrinthBattleElementFilter.ALL,
            EntryPixelRect(10, 10, 20, 10), selectionScore = 0.99, selected = true)),
        visibleCharacters = emptyList(),
        selectedCharacters = listOf("T", "A", "B", "C", "D").mapIndexed { i, id ->
            LabyrinthBattleCharacterMatch(slotId = "current_member_" + (i + 1), characterId = id,
                displayName = id, iconVariant = "test", confidence = 0.99,
                screenRect = EntryPixelRect(100 + i * 120, 200, 100, 100), selected = true)
        },
        scrollbar = LabyrinthBattleScrollbarObservation(EntryPixelRect(1800, 200, 20, 500),
            thumbRect = null, visible = false, canScroll = false, position = 0.0),
        recognitionState = LabyrinthBattleTeamRecognitionState.STABLE,
        viewportRevision = 1L,
    )
}
