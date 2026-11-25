package com.landosol.toolbox.labyrinth

import com.landosol.toolbox.labyrinth.debug.labyrinthInitial1088ContextPayload
import com.landosol.toolbox.labyrinth.debug.labyrinthInitial1088RefinementPayload
import com.landosol.toolbox.labyrinth.debug.labyrinthInitial1088RecoveryPayload
import com.landosol.toolbox.labyrinth.node.LabyrinthNodeTypes
import com.landosol.toolbox.labyrinth.node.NodeSessionState
import com.landosol.toolbox.labyrinth.vision.*
import org.junit.Assert.*
import org.junit.Test

class LabyrinthInitial1088RecoveryTest {
    private val roi = EntryPixelRect(505, 329, 130, 130)
    private fun context(allowed: Boolean, reason: String = if (allowed) "SUCCESSFUL_START" else "NO_POSITIVE_START_EVIDENCE") =
        LabyrinthInitial1088RefinementContext(allowed, reason, allowed, false, false, "NOT_STARTED",
            if (allowed) 1 else null, if (allowed) 10101L else null, if (allowed) 1 else 0,
            null, null, false, false)
    private fun evidence(outcome: LabyrinthInitial1088RefinementOutcome) = LabyrinthInitial1088RefinementEvidence(
        outcome, LabyrinthInitial1088RefinementSnapshot(0.363, 0.031, "1088", listOf(
            LabyrinthInitial1088RefinementCandidate("1088", 0.363), LabyrinthInitial1088RefinementCandidate("9999", 0.332))),
        LabyrinthInitial1088RefinementSnapshot(0.363, 0.031, "1088", listOf(
            LabyrinthInitial1088RefinementCandidate("1088", 0.363), LabyrinthInitial1088RefinementCandidate("9999", 0.332))),
        roi, attempts = if (outcome == LabyrinthInitial1088RefinementOutcome.EXECUTED_UNTRUSTED) 13 else 0,
        permitReason = if (outcome == LabyrinthInitial1088RefinementOutcome.BLOCKED_PERMISSION) "PERMISSION_NOT_GRANTED" else null)
    private fun card(id: String, selected: Boolean = false, trusted: Boolean = true) = LabyrinthBattleCharacterMatch(
        slotId = id, characterId = if (trusted) id else null, displayName = if (trusted) id else null,
        iconVariant = if (trusted) "31" else null, confidence = if (trusted) 0.8 else 0.363,
        screenRect = roi, selected = selected, trusted = trusted,
        rivalMargin = if (trusted) 0.2 else 0.031, suspectedCharacterId = id,
        initial1088RefinementEvidence = if (!trusted && id == "1088") evidence(LabyrinthInitial1088RefinementOutcome.BLOCKED_PERMISSION) else null)
    private val anchors = LabyrinthAnchorScores(mapOf(EntryAnchorId.SELECTION_HEADER_STANDARD to 1.0,
        EntryAnchorId.INVITE_DISABLED_STANDARD to 1.0, EntryAnchorId.SELECTION_COUNT_NONE_STANDARD to 1.0))
    private fun planner() = LabyrinthEntryActionPlanner(LabyrinthEntryActionPlannerConfig(stableFrames = 1)).apply {
        start(0); configureOpeningRoster(2)
    }
    private fun decide(planner: LabyrinthEntryActionPlanner, matches: List<LabyrinthBattleCharacterMatch>, at: Long) =
        planner.decide(LabyrinthEntryPageState.INITIAL_CHARACTER_SELECTION, 1280, 720, at,
            anchorScores = anchors, openingCharacterMatches = matches)

    @Test fun normalSelectionAndSharedPickerGuardsRemainUnchanged() {
        val handoff = LabyrinthInitial1088HandoffEvidence()
        handoff.record(LabyrinthEntryPageState.NODE_SELECTION, true, true, 100)
        assertTrue(handoff.retained(100)); assertTrue(handoff.retained(5_100)); assertFalse(handoff.retained(5_101))
        handoff.reset(); assertFalse(handoff.retained(100))
        handoff.record(LabyrinthEntryPageState.NODE_SELECTION, false, true, 100); assertFalse(handoff.retained(101))
        handoff.record(LabyrinthEntryPageState.EVENT_CHOICE, true, true, 100); assertFalse(handoff.retained(101))
        val state = NodeSessionState(1, 10101L, listOf(10101L))
        assertTrue(labyrinthAllowsInitial1088GeometryRefinement(null, null, false, null, false, true))
        assertFalse(labyrinthAllowsInitial1088GeometryRefinement(state, null, false, LabyrinthNodeTypes.EVENT, false, true))
        assertFalse(labyrinthAllowsInitial1088GeometryRefinement(state, null, false, LabyrinthNodeTypes.SHOP, false, true))
        assertFalse(labyrinthAllowsInitial1088GeometryRefinement(state, null, true, null, false, true))
        assertFalse(labyrinthAllowsInitial1088GeometryRefinement(state, null, false, null, true, true))
        assertFalse(labyrinthAllowsInitial1088GeometryRefinement(null, null, false, null, false))
        val gate = LabyrinthInitial1088RecoveryGate()
        assertFalse(gate.evaluate(false, 100, context(false), context(true), evidence(LabyrinthInitial1088RefinementOutcome.BLOCKED_PERMISSION)).shouldWait)
        assertEquals("BLOCKED", gate.evaluate(true, 100, context(false), context(false), evidence(LabyrinthInitial1088RefinementOutcome.BLOCKED_PERMISSION)).status)
        assertEquals("BLOCKED", gate.evaluate(true, 100, context(false), context(true, "ROUTE_START_DEFAULT_NOT_OBSERVED"), evidence(LabyrinthInitial1088RefinementOutcome.BLOCKED_PERMISSION)).status)
        val planner = planner()
        val normal = listOf(card("1089"), card("1088"), card("1002"), card("1003"))
        assertFalse(planner.shouldDeferInitial1088Fallback(normal))
        assertTrue(decide(planner, normal, 1_000) is LabyrinthEntryActionDecision.Execute)
        val afterFirst = normal.map { it.copy(selected = it.characterId == "1089") }
        assertFalse(planner.shouldDeferInitial1088Fallback(afterFirst))
        assertTrue(decide(planner, afterFirst, 4_000) is LabyrinthEntryActionDecision.Execute)
        assertEquals("1088", planner.pendingInitialCharacterId())
        val duplicate = afterFirst + card("1088", trusted = false).copy(slotId = "uncertain-duplicate")
        assertFalse(planner().shouldDeferInitial1088Fallback(duplicate))
        val otherFallback = planner().apply { configureOpeningRoster(2, mapOf(2 to
            listOf(listOf("1089"), listOf("1088", "1226"), listOf("1003")))) }
        assertFalse(otherFallback.shouldDeferInitial1088Fallback(listOf(card("1089", selected = true),
            card("1088", trusted = false), card("1226"), card("1003"))))
        assertEquals("UNOBSERVED", gate.evaluate(true, 100, null, context(true), null).status)
    }

    @Test fun failedPreferredWaitsForFreshFrameBeforeFallbackWithoutResettingBudget() {
        val planner = planner()
        val matches = listOf(card("1089"), card("1088", trusted = false), card("1002"), card("1003"))
        assertFalse(planner.shouldDeferInitial1088Fallback(matches)) // First good pick is not delayed.
        assertTrue(decide(planner, matches, 1_000) is LabyrinthEntryActionDecision.Execute)
        assertEquals("1089", planner.pendingInitialCharacterId())
        assertFalse(planner.shouldDeferInitial1088Fallback(matches)) // Pending tap feedback remains original.
        val selected = matches.map { it.copy(selected = it.characterId == "1089") }
        assertTrue(planner.shouldDeferInitial1088Fallback(selected)) // This same frame could otherwise pick the fallback.
        val gate = LabyrinthInitial1088RecoveryGate()
        val blocked = evidence(LabyrinthInitial1088RefinementOutcome.BLOCKED_PERMISSION)
        val executed = evidence(LabyrinthInitial1088RefinementOutcome.EXECUTED_UNTRUSTED)
        val waiting = gate.evaluate(true, 2_000, context(false), context(true), blocked)
        assertTrue(waiting.shouldWait); assertEquals(2_000L, waiting.requiredNewerThanMillis)
        assertTrue(gate.evaluate(true, 2_000, context(true), context(true), executed).shouldWait)
        assertEquals("READY", gate.evaluate(true, 4_000, context(true), context(true), executed).status)
        assertTrue(decide(planner, selected, 4_000) is LabyrinthEntryActionDecision.Execute)
        assertEquals("1002", planner.pendingInitialCharacterId()) // Same priority and same safety floors.
        val timeout = LabyrinthInitial1088RecoveryGate()
        assertTrue(timeout.evaluate(true, 1_000, context(false), context(true), blocked).shouldWait)
        assertTrue(timeout.evaluate(true, 2_000, context(false), context(true), blocked).shouldWait)
        assertEquals("TIMED_OUT", timeout.evaluate(true, 6_000, context(false), context(true), blocked).status)
        assertFalse(timeout.evaluate(true, 7_000, context(false), context(true), blocked).shouldWait)
        val satisfied = selected.map { if (it.characterId == "1002") it.copy(selected = true) else it }
        assertFalse(planner.shouldDeferInitial1088Fallback(satisfied))
        val custom = planner().apply { configureOpeningRoster(2, mapOf(2 to listOf(listOf("1089"), listOf("1002"), listOf("1003")))) }
        assertFalse(custom.shouldDeferInitial1088Fallback(selected))
    }

    @Test fun diagnosticsCaptureExactReasonScoresCropsAndCacheWithoutFrameLogFlood() {
        val blocked = evidence(LabyrinthInitial1088RefinementOutcome.BLOCKED_PERMISSION)
        val captured = context(false).copy(capturedAtMillis = 1_234, frameSequence = 33)
        val payload = labyrinthInitial1088RefinementPayload("card-1088", blocked, captured)
        assertEquals("NO_POSITIVE_START_EVIDENCE", payload["reason"])
        assertEquals(false, payload["cacheHit"])
        val before = payload["before"] as Map<*, *>
        assertEquals(0.363, before["score"])
        assertEquals(2, (before["topCandidates"] as List<*>).size)
        assertEquals(505, (payload["originalMatchRoi"] as Map<*, *>)["left"])
        assertEquals(1_234L, labyrinthInitial1088ContextPayload(captured)["capturedAtMillis"])
        assertEquals(33L, labyrinthInitial1088ContextPayload(captured)["frameSequence"])
        val key = labyrinthInitial1088RefinementLogKey(2, captured, blocked)
        assertEquals(key, labyrinthInitial1088RefinementLogKey(2, captured.copy(capturedAtMillis = 2_000, frameSequence = 50), blocked))
        val logs = LabyrinthInitial1088LogDeduplicator()
        assertTrue(logs.accept(key)); assertFalse(logs.accept(key))
        val cachedKey = labyrinthInitial1088RefinementLogKey(2, captured, blocked.copy(cacheHit = true))
        assertTrue(logs.accept(cachedKey)); assertFalse(logs.accept(cachedKey))
        assertTrue(logs.accept(key, "slot-a")); assertTrue(logs.accept(key, "slot-b"))
        assertFalse(logs.accept(key, "slot-a")); assertFalse(logs.accept(key, "slot-b"))
        assertNull(LabyrinthBalancedVisionContext.currentInitial1088RefinementContext())
        LabyrinthBalancedVisionContext.withInitial1088RefinementContext(captured) {
            assertEquals(captured, LabyrinthBalancedVisionContext.currentInitial1088RefinementContext())
            assertNull(LabyrinthBalancedVisionContext.current()) // Unrelated live callbacks stay live.
        }
        assertNull(LabyrinthBalancedVisionContext.currentInitial1088RefinementContext())
        val recovery = labyrinthInitial1088RecoveryPayload(LabyrinthInitial1088RecoveryDiagnostic("WAITING", "PERMISSION_RECOVERED_WAIT_FRESH_FRAME", 300, 1_234))
        assertEquals(300L, recovery["waitedMillis"])
        assertEquals(1_234L, recovery["requiredNewerThanMillis"])
    }
}
