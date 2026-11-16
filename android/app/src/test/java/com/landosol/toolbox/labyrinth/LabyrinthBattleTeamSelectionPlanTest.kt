package com.landosol.toolbox.labyrinth

import com.landosol.toolbox.automation.AutomationSessionId
import com.landosol.toolbox.automation.AutomationAction
import com.landosol.toolbox.labyrinth.vision.EntryPixelRect
import com.landosol.toolbox.labyrinth.vision.LabyrinthBattleCharacterMatch
import com.landosol.toolbox.labyrinth.vision.LabyrinthBattleElementFilter
import com.landosol.toolbox.labyrinth.vision.LabyrinthBattleFilterObservation
import com.landosol.toolbox.labyrinth.vision.LabyrinthBattleScrollbarObservation
import com.landosol.toolbox.labyrinth.vision.LabyrinthBattleTeamObservation
import com.landosol.toolbox.labyrinth.vision.LabyrinthBattleTeamRecognitionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LabyrinthBattleTeamSelectionPlanTest {

    @Test fun `switch request defaults off and only permits first normal battle`() {
        for (frontline in listOf(false, true)) for (kind in LabyrinthCombatKind.entries + null) {
            for (retry in listOf(0, 1, 2)) {
                val disabled = LabyrinthStrategySettings(frontlineSurvivalGateEnabled = frontline)
                assertFalse(labyrinthRequestsCurrentNormalBattleTeam(disabled, kind, retry))
                val enabled = disabled.copy(normalBattleSkipTeamSelectionEnabled = true)
                assertEquals(kind == LabyrinthCombatKind.NORMAL && retry == 0,
                    labyrinthRequestsCurrentNormalBattleTeam(enabled, kind, retry))
            }
        }
    }

    @Test fun `switch chooses between recommendation diff and retained team start`() {
        val planner = planner(listOf("A", "B", "C", "D", "E", "F"))
        val recommendation = recommendation("A", "B", "C", "D", "F")
        val current = listOf("A", "B", "C", "D", "E")
        val observation = observation(
            selected = current.mapIndexed { index, id -> match("s-$index", id, selected = true) },
            visible = listOf(match("visible-F", "F")),
        )
        for (enabled in listOf(false, true)) {
            val requested = labyrinthRequestsCurrentNormalBattleTeam(
                LabyrinthStrategySettings(normalBattleSkipTeamSelectionEnabled = enabled), LabyrinthCombatKind.NORMAL, 0,
            )
            val plan = planner.plan(sessionId, recommendation, observation, holdCurrentTeamRequested = requested)
            assertEquals(enabled, plan.holdCurrentTeam)
            if (enabled) {
                assertTrue(plan.teamReady)
                assertTrue(plan.needSelectIds.isEmpty())
                assertTrue(plan.needDeselectIds.isEmpty())
                assertEquals(LabyrinthBattleTeamExecutionKind.START_BATTLE,
                    requireNotNull(labyrinthBattleTeamExecutionStep(plan, sessionId, recommendation, observation,
                        EntryPixelRect(1500, 900, 300, 100), 1920, 1080)).kind)
            } else {
                assertEquals(listOf("A", "B", "C", "D"), plan.alreadyCorrectIds)
                assertEquals(listOf("E"), plan.needDeselectIds)
                assertEquals(listOf("F"), plan.needSelectIds)
                assertFalse(plan.teamReady)
            }
        }
    }

    @Test
    fun `effective scan tolerates selected dimmed cards but keeps ordinary unsafe cards blocking`() {
        val selectedMember = match("current_member_1", "B", selected = true)
        val safeVisible = match("visible-A", "A")
        val selectedDimmed = LabyrinthBattleCharacterMatch(
            slotId = "visible-B",
            characterId = null,
            displayName = null,
            iconVariant = null,
            confidence = 0.42,
            screenRect = EntryPixelRect(300, 200, 100, 100),
            selected = true,
            trusted = false,
            rivalMargin = 0.01,
            suspectedCharacterId = "B",
            suspectedDisplayName = "名称B",
        )
        val unsafeOrdinary = LabyrinthBattleCharacterMatch(
            slotId = "visible-C",
            characterId = null,
            displayName = null,
            iconVariant = null,
            confidence = 0.40,
            screenRect = EntryPixelRect(500, 200, 100, 100),
            selected = false,
            trusted = false,
            rivalMargin = 0.01,
            suspectedCharacterId = "C",
            suspectedDisplayName = "名称C",
        )
        val evidence = labyrinthEffectiveScanFrameEvidence(
            observation(
                currentFilter = LabyrinthBattleElementFilter.EFFECTIVE_EFFECT,
                selected = listOf(selectedMember),
                visible = listOf(safeVisible, selectedDimmed, unsafeOrdinary),
            ),
        )

        assertEquals(setOf("A", "B"), evidence.confirmedCharacterIds)
        assertEquals(listOf("visible-C"), evidence.unsafeVisibleCharacters.map { it.slotId })
        assertEquals(1, evidence.toleratedSelectedDimmedCount)
    }

    @Test
    fun `effective scan does not forgive a fake selected-looking unsafe card without member strip evidence`() {
        val selectedLooking = LabyrinthBattleCharacterMatch(
            slotId = "visible-X",
            characterId = null,
            displayName = null,
            iconVariant = null,
            confidence = 0.40,
            screenRect = EntryPixelRect(300, 200, 100, 100),
            selected = true,
            trusted = false,
            rivalMargin = 0.01,
            suspectedCharacterId = "X",
            suspectedDisplayName = "名称X",
        )
        val evidence = labyrinthEffectiveScanFrameEvidence(
            observation(
                currentFilter = LabyrinthBattleElementFilter.EFFECTIVE_EFFECT,
                selected = emptyList(),
                visible = listOf(selectedLooking),
            ),
        )

        assertTrue(evidence.confirmedCharacterIds.isEmpty())
        assertEquals(listOf("visible-X"), evidence.unsafeVisibleCharacters.map { it.slotId })
        assertEquals(0, evidence.toleratedSelectedDimmedCount)
    }
    private val bossTabs = listOf(
        EntryPixelRect(98, 150, 195, 65),
        EntryPixelRect(334, 150, 195, 65),
        EntryPixelRect(571, 150, 195, 65),
    )

    @Test
    fun `boss preparation recovers from team three clears all tabs and returns to team one`() {
        val inherited = match("current_member_1", "A", selected = true)

        val recover = labyrinthBossEditorPreparationDecision(
            observation(selected = listOf(inherited)).copy(bossTeamIndex = 3, bossTeamTabs = bossTabs),
            LabyrinthBossEditorPreparationStage.TEAM_1,
        )
        assertEquals(LabyrinthBossEditorPreparationStage.TEAM_1, recover.stage)
        assertEquals(LabyrinthBattleTeamExecutionKind.RESET_BOSS_SWITCH_TEAM, recover.step?.kind)

        val clearOne = labyrinthBossEditorPreparationDecision(
            observation(selected = listOf(inherited)).copy(bossTeamIndex = 1, bossTeamTabs = bossTabs),
            recover.stage,
        )
        assertEquals(LabyrinthBattleTeamExecutionKind.RESET_BOSS_DESELECT_CHARACTER, clearOne.step?.kind)
        assertEquals(LabyrinthBossEditorPreparationStage.TEAM_1, clearOne.stage)

        val toTwo = labyrinthBossEditorPreparationDecision(
            observation().copy(bossTeamIndex = 1, bossTeamTabs = bossTabs),
            clearOne.stage,
        )
        assertEquals(LabyrinthBossEditorPreparationStage.TEAM_2, toTwo.stage)
        assertEquals(LabyrinthBattleTeamExecutionKind.RESET_BOSS_SWITCH_TEAM, toTwo.step?.kind)

        val toThree = labyrinthBossEditorPreparationDecision(
            observation().copy(bossTeamIndex = 2, bossTeamTabs = bossTabs),
            toTwo.stage,
        )
        assertEquals(LabyrinthBossEditorPreparationStage.TEAM_3, toThree.stage)

        val returnOne = labyrinthBossEditorPreparationDecision(
            observation().copy(bossTeamIndex = 3, bossTeamTabs = bossTabs),
            toThree.stage,
        )
        assertEquals(LabyrinthBossEditorPreparationStage.RETURN_TEAM_1, returnOne.stage)
        assertEquals(LabyrinthBattleTeamExecutionKind.RESET_BOSS_SWITCH_TEAM, returnOne.step?.kind)

        val complete = labyrinthBossEditorPreparationDecision(
            observation().copy(bossTeamIndex = 1, bossTeamTabs = bossTabs),
            returnOne.stage,
        )
        assertEquals(LabyrinthBossEditorPreparationStage.COMPLETE, complete.stage)
        assertNull(complete.step)
    }

    @Test
    fun `boss preparation gives compacted next member a fresh deselect execution key`() {
        val first = match("current_member_1", "A", selected = true)
        val second = match("current_member_2", "B", selected = true)
        val before = labyrinthBossEditorPreparationDecision(
            observation(selected = listOf(first, second)).copy(
                bossTeamIndex = 1,
                bossTeamTabs = bossTabs,
                viewportRevision = 7L,
            ),
            LabyrinthBossEditorPreparationStage.TEAM_1,
        )

        // After A is removed, B shifts into the exact same compacted slot while the roster
        // viewport revision intentionally does not change.
        val compactedSecond = second.copy(slotId = "current_member_1", screenRect = first.screenRect)
        val after = labyrinthBossEditorPreparationDecision(
            observation(selected = listOf(compactedSecond)).copy(
                bossTeamIndex = 1,
                bossTeamTabs = bossTabs,
                viewportRevision = 7L,
            ),
            LabyrinthBossEditorPreparationStage.TEAM_1,
        )

        assertEquals(LabyrinthBattleTeamExecutionKind.RESET_BOSS_DESELECT_CHARACTER, before.step?.kind)
        assertEquals(LabyrinthBattleTeamExecutionKind.RESET_BOSS_DESELECT_CHARACTER, after.step?.kind)
        assertTrue(before.step?.key != after.step?.key)
    }

    @Test
    fun `boss team mode decides whether confirmed team is committed or kept for single-team traversal`() {
        val multi = labyrinthBossTeamAdvanceDisposition(
            LabyrinthBossTeamMode.MULTI_TEAM,
            listOf("A", "B", "A"),
        )
        assertEquals(listOf("A", "B"), multi.committedIds)
        assertTrue(multi.preparedSingleTeamIds.isEmpty())

        val single = labyrinthBossTeamAdvanceDisposition(
            LabyrinthBossTeamMode.SINGLE_TEAM,
            listOf("A", "B", "A"),
        )
        assertTrue(single.committedIds.isEmpty())
        assertEquals(listOf("A", "B"), single.preparedSingleTeamIds)
    }

    private val sessionId = AutomationSessionId(42L)

    @Test fun `unknown attribute uses all tab and still allows trusted visible selection`() {
        val selector = LabyrinthBattleTeamSelectionPlanner(mapOf("A" to profile("A", LabyrinthCharacterAttribute.FIRE).copy(attribute = null)))
        val recommendation = recommendation("A")
        val visible = observation(currentFilter = LabyrinthBattleElementFilter.WATER, visible = listOf(match("A", "A")))
        val visiblePlan = selector.plan(sessionId, recommendation, visible)
        assertTrue(visiblePlan.readyToExecute)
        assertEquals(LabyrinthBattleTeamExecutionKind.SELECT_CHARACTER,
            labyrinthBattleTeamExecutionStep(visiblePlan, sessionId, recommendation, visible, null, 1920, 1080)?.kind)
        val hidden = observation(currentFilter = LabyrinthBattleElementFilter.WATER, canScroll = true)
        val hiddenPlan = selector.plan(sessionId, recommendation, hidden)
        assertTrue(hiddenPlan.readyToExecute)
        assertEquals(LabyrinthBattleElementFilter.ALL, hiddenPlan.recommendedNextFilter)
        val all = hidden.copy(currentFilter = LabyrinthBattleElementFilter.ALL)
        val allPlan = selector.plan(sessionId, recommendation, all)
        assertTrue(allPlan.readyToExecute)
        assertTrue(allPlan.scrollRequired)
    }

    @Test fun `ordinary and boss searches reverse gesture and keep overlapping pages`() {
        val ids = listOf("A", "B", "C", "D", "E")
        val recommendation = recommendation(*ids.toTypedArray())
        for (boss in listOf(null, 1)) {
            val observation = observation(
                currentFilter = LabyrinthBattleElementFilter.FIRE,
                selected = ids.dropLast(1).map { match("s$it", it, selected = true) },
                canScroll = true,
            ).copy(bossTeamIndex = boss)
            val plan = planner(ids).plan(sessionId, recommendation, observation)
            val steps = LabyrinthBattleRosterScrollDirection.entries.map { direction ->
                requireNotNull(labyrinthBattleTeamExecutionStep(plan, sessionId, recommendation,
                    observation, null, 1920, 1080, direction))
            }
            val back = steps[0].action as AutomationAction.Swipe
            val forward = steps[1].action as AutomationAction.Swipe
            assertTrue(back.start.y < back.end.y)
            assertTrue(forward.start.y > forward.end.y)
            assertEquals(back.start, forward.end)
            assertEquals(back.end, forward.start)
            assertTrue(steps[0].key != steps[1].key)
            val viewportTop = if (boss == null) 230 else 360
            assertTrue(back.start.y > viewportTop && back.end.y < 746)
            assertTrue("overlap must fit a full card", back.end.y - back.start.y <= 746 - viewportTop - 195)
        }
    }

    @Test fun `offscreen unwanted member is deselected from member strip without roster scrolling`() {
        val ids = listOf("A", "B", "C", "D", "E", "F")
        val observation = observation(
            currentFilter = LabyrinthBattleElementFilter.FIRE,
            selected = listOf(match("sA", "A", selected = true), match("sF", "F", selected = true)),
            canScroll = true,
        )
        val recommendation = recommendation("A")
        val plan = planner(ids).plan(sessionId, recommendation, observation)
        assertTrue(plan.notCurrentlyVisibleIds.isEmpty())
        assertEquals(listOf("F"), plan.needDeselectIds)
        assertEquals("sF", plan.visibleDeselectTargets.single().slotId)
        assertTrue(plan.readyToExecute)
        assertFalse(plan.scrollRequired)
        assertEquals(LabyrinthBattleTeamExecutionKind.DESELECT_CHARACTER,
            labyrinthBattleTeamExecutionStep(plan, sessionId, recommendation, observation, null, 1920, 1080)?.kind)
    }

    @Test fun `single first team permits empty final tab but requires first team evidence and start anchor`() {
        val empty = observation(selected = emptyList()).copy(bossTeamIndex = 3)
        val start = EntryPixelRect(1565,850,270,110)
        val step = requireNotNull(labyrinthBossSingleTeamExecutionStep(empty, listOf("A"), start))
        assertEquals(LabyrinthBattleTeamExecutionKind.START_BATTLE, step.kind)
        assertEquals(listOf("A"), step.confirmedTeamIds)
        assertEquals(null, labyrinthBossSingleTeamExecutionStep(empty, emptyList(), start))
        assertEquals(null, labyrinthBossSingleTeamExecutionStep(empty, listOf("A"), null))
        assertEquals(null, labyrinthBossSingleTeamExecutionStep(empty.copy(bossTeamIndex = 1), listOf("A"), start))
        assertEquals(null, labyrinthBossSingleTeamExecutionStep(empty.copy(selectedCharacters = listOf(match("slot", "B", selected = true))), listOf("A"), start))
    }

    @Test
    fun `partial boss multi team leaves unused tabs empty and starts from team three`() {
        val start = EntryPixelRect(1565, 850, 270, 110)
        val team2Empty = observation(selected = emptyList()).copy(
            bossTeamIndex = 2,
            bossTeamTabs = bossTabs,
        )
        val skipToThree = requireNotNull(
            labyrinthBossPartialMultiTeamExecutionStep(team2Empty, completedTeamCount = 1, startButtonRect = start),
        )
        assertEquals(LabyrinthBattleTeamExecutionKind.NEXT_BOSS_TEAM, skipToThree.kind)
        assertTrue(skipToThree.confirmedTeamIds.isEmpty())

        val team3Empty = team2Empty.copy(bossTeamIndex = 3)
        val startWithOne = requireNotNull(
            labyrinthBossPartialMultiTeamExecutionStep(team3Empty, completedTeamCount = 1, startButtonRect = start),
        )
        assertEquals(LabyrinthBattleTeamExecutionKind.START_BATTLE, startWithOne.kind)

        val startWithTwo = requireNotNull(
            labyrinthBossPartialMultiTeamExecutionStep(team3Empty, completedTeamCount = 2, startButtonRect = start),
        )
        assertEquals(LabyrinthBattleTeamExecutionKind.START_BATTLE, startWithTwo.kind)
        assertEquals(
            null,
            labyrinthBossPartialMultiTeamExecutionStep(
                team3Empty.copy(selectedCharacters = listOf(match("slot", "A", selected = true))),
                completedTeamCount = 2,
                startButtonRect = start,
            ),
        )
        assertEquals(null, labyrinthBossPartialMultiTeamExecutionStep(team2Empty, 2, start))
    }

    @Test
    fun `boss ready team advances by observed tab and never arms battle for team one or two`() {
        val ids = listOf("A", "B", "C", "D", "E")
        val recommendation = recommendation(*ids.toTypedArray())
        for (index in 1..3) {
            val observation = observation(selected = ids.mapIndexed { i, id -> match("s$i", id, selected = true) })
                .copy(bossTeamIndex = index, bossTeamTabs = listOf(EntryPixelRect(98,150,195,65),
                    EntryPixelRect(334,150,195,65),EntryPixelRect(571,150,195,65)))
            val plan = planner(ids).plan(sessionId, recommendation, observation)
            val step = requireNotNull(labyrinthBattleTeamExecutionStep(plan, sessionId, recommendation,
                observation, EntryPixelRect(1570,850,270,110),1920,1080))
            assertEquals(if (index < 3) LabyrinthBattleTeamExecutionKind.NEXT_BOSS_TEAM else
                LabyrinthBattleTeamExecutionKind.START_BATTLE, step.kind)
            val tap = step.action as AutomationAction.Tap
            assertTrue(if (index < 3) tap.point.y < 220 else tap.point.y > 850)
            assertEquals(null, labyrinthBattleTeamExecutionStep(plan, sessionId, recommendation,
                observation.copy(bossTeamTabs = emptyList()), null, 1920, 1080))
        }
    }

    @Test
    fun `plan keeps intersection and only selects and deselects the difference`() {
        val planner = planner(ids = listOf("A", "B", "C", "D", "E", "F"))
        val plan = planner.plan(
            sessionId = sessionId,
            recommendation = recommendation("A", "B", "C", "D", "E"),
            observation = observation(
                selected = listOf(match("selected-A", "A", selected = true), match("selected-B", "B", selected = true), match("selected-F", "F", selected = true)),
                visible = listOf(
                    match("visible-C", "C"),
                    match("visible-D", "D"),
                    match("visible-E", "E"),
                    match("visible-F", "F"),
                ),
            ),
        )

        assertEquals(listOf("A", "B"), plan.alreadyCorrectIds)
        assertEquals(listOf("F"), plan.needDeselectIds)
        assertEquals(listOf("C", "D", "E"), plan.needSelectIds)
        assertEquals(listOf("F"), plan.visibleDeselectTargets.map(LabyrinthBattleTeamSelectionTarget::characterId))
        assertEquals(listOf("C", "D", "E"), plan.visibleSelectTargets.map(LabyrinthBattleTeamSelectionTarget::characterId))
        assertTrue(plan.readyToExecute)
        assertFalse(plan.teamReady)
    }

    @Test
    fun `ordinary battle holds a full identified team and starts without touching it`() {
        val planner = planner(ids = listOf("A", "B", "C", "D", "E", "F", "G"))
        val recommendation = recommendation("A", "B", "C", "D", "G")
        val current = listOf("A", "B", "C", "D", "E")
        val observation = observation(
            selected = current.mapIndexed { index, id -> match("selected-$index", id, selected = true) },
            visible = listOf(match("visible-G", "G")),
        )
        val held = planner.plan(sessionId, recommendation, observation, holdCurrentTeamRequested = true)
        assertTrue(held.holdCurrentTeam)
        assertTrue(held.teamReady)
        assertTrue(held.needSelectIds.isEmpty())
        assertTrue(held.needDeselectIds.isEmpty())
        assertEquals(current, held.recommendedIds)
        // A recommendation that differs from the held team does not invalidate the plan; the
        // page itself still does.
        assertTrue(held.isStillValid(sessionId, recommendation("A", "B", "C", "D", "G"), observation))
        assertFalse(held.isStillValid(sessionId, recommendation, observation(
            selected = current.mapIndexed { index, id -> match("selected-$index", id, selected = true) },
            viewportRevision = 2L,
        )))
        assertEquals(
            LabyrinthBattleTeamExecutionKind.START_BATTLE,
            requireNotNull(labyrinthBattleTeamExecutionStep(held, sessionId, recommendation, observation,
                EntryPixelRect(1500, 900, 300, 100), 1920, 1080)).kind,
        )

        // Not requested (retry, EX, Boss): the ordinary diff applies.
        val normal = planner.plan(sessionId, recommendation, observation)
        assertFalse(normal.holdCurrentTeam)
        assertEquals(listOf("E"), normal.needDeselectIds)
        assertEquals(listOf("G"), normal.needSelectIds)
    }

    @Test
    fun `ordinary battle never holds a short or partly unidentified team`() {
        val planner = planner(ids = listOf("A", "B", "C", "D", "E"))
        val recommendation = recommendation("A", "B", "C", "D", "E")
        val short = planner.plan(
            sessionId, recommendation,
            observation(selected = listOf("A", "B", "C", "D").mapIndexed { i, id -> match("s-$i", id, selected = true) }),
            holdCurrentTeamRequested = true,
        )
        assertFalse(short.holdCurrentTeam)
        assertEquals(listOf("E"), short.needSelectIds)

        val unidentified = planner.plan(
            sessionId, recommendation,
            observation(
                selected = listOf("A", "B", "C", "D").mapIndexed { i, id -> match("s-$i", id, selected = true) } +
                    LabyrinthBattleCharacterMatch(
                        slotId = "s-4", characterId = null, displayName = null, iconVariant = "test",
                        confidence = 0.2, screenRect = EntryPixelRect(500, 200, 100, 100), selected = true,
                    ),
            ),
            holdCurrentTeamRequested = true,
        )
        assertFalse(unidentified.holdCurrentTeam)
        assertFalse(unidentified.teamReady)

        assertTrue(labyrinthHoldsCurrentTeam(requested = true, selectedSlotCount = 5, identifiedSelectedCount = 5))
        assertFalse(labyrinthHoldsCurrentTeam(requested = true, selectedSlotCount = 5, identifiedSelectedCount = 4))
        assertFalse(labyrinthHoldsCurrentTeam(requested = true, selectedSlotCount = 4, identifiedSelectedCount = 4))
        assertFalse(labyrinthHoldsCurrentTeam(requested = false, selectedSlotCount = 5, identifiedSelectedCount = 5))
    }

    @Test
    fun `fully matching selected team is executable and starts battle`() {
        val ids = listOf("A", "B", "C", "D", "E")
        val recommendation = recommendation(*ids.toTypedArray())
        val observation = observation(
            selected = ids.mapIndexed { index, id -> match("selected-$index", id, selected = true) },
        )
        val plan = planner(ids).plan(
            sessionId,
            recommendation,
            observation,
        )
        val startRect = EntryPixelRect(1500, 900, 300, 100)
        val step = requireNotNull(
            labyrinthBattleTeamExecutionStep(
                plan,
                sessionId,
                recommendation,
                observation,
                startRect,
                1920,
                1080,
            ),
        )

        assertTrue(plan.teamReady)
        assertTrue(plan.readyToExecute)
        assertFalse(plan.dryRun)
        assertTrue(plan.needSelectIds.isEmpty())
        assertTrue(plan.needDeselectIds.isEmpty())
        assertEquals(LabyrinthBattleTeamExecutionKind.START_BATTLE, step.kind)
        assertEquals(AutomationAction.Tap(com.landosol.toolbox.automation.ScreenPoint(1650f, 950f)), step.action)
        assertTrue(plan.logText().contains("可开始战斗"))
    }

    @Test
    fun `three opening characters form a ready team and start battle`() {
        val ids = listOf("A", "B", "C")
        val recommendation = recommendation(*ids.toTypedArray())
        val observation = observation(
            selected = ids.mapIndexed { index, id -> match("selected-$index", id, selected = true) },
        )
        val plan = planner(ids).plan(sessionId, recommendation, observation)
        val step = requireNotNull(
            labyrinthBattleTeamExecutionStep(
                plan,
                sessionId,
                recommendation,
                observation,
                EntryPixelRect(1500, 900, 300, 100),
                1920,
                1080,
            ),
        )

        assertEquals(3, plan.recommendedIds.size)
        assertTrue(plan.teamReady)
        assertEquals(LabyrinthBattleTeamExecutionKind.START_BATTLE, step.kind)
    }

    @Test
    fun `roster checkmark guesses cannot invent selected team members`() {
        val ids = listOf("A", "B", "C")
        val recommendation = recommendation(*ids.toTypedArray())
        val observation = observation(
            visible = ids.mapIndexed { index, id ->
                match("visible-$index", id, selected = true)
            },
        )
        val plan = planner(ids).plan(sessionId, recommendation, observation)
        assertTrue(plan.alreadyCorrectIds.isEmpty())
        assertEquals(ids, plan.needSelectIds)
        assertFalse(plan.teamReady)
        assertTrue(plan.compactOverlayLines().first().contains("0/3"))
    }

    @Test
    fun `deselect membership and tap both come from current member strip`() {
        val selectedWrong = match("current-member-1", "F", selected = true)
        val observation = observation(
            selected = listOf(selectedWrong),
            visible = emptyList(),
        )
        val recommendation = recommendation("A", "B", "C", "D", "E")
        val plan = planner(listOf("A", "B", "C", "D", "E", "F")).plan(
            sessionId,
            recommendation,
            observation,
        )

        assertEquals(listOf("F"), plan.needDeselectIds)
        assertEquals("current-member-1", plan.visibleDeselectTargets.single().slotId)
        assertFalse(plan.notCurrentlyVisibleIds.contains("F"))
        val step = requireNotNull(
            labyrinthBattleTeamExecutionStep(
                plan,
                sessionId,
                recommendation,
                observation,
                null,
                1920,
                1080,
            ),
        )
        assertEquals(LabyrinthBattleTeamExecutionKind.DESELECT_CHARACTER, step.kind)
        assertEquals(selectedWrong.screenRect, (step.action as AutomationAction.Tap).let {
            EntryPixelRect((it.point.x - 50).toInt(), (it.point.y - 50).toInt(), 100, 100)
        })
    }

    @Test
    fun `selected wrong member never becomes a roster search target`() {
        val recommendation = recommendation("A", "B", "C", "D", "E")
        val selected = listOf(
            match("selected-A", "A", selected = true),
            match("selected-B", "B", selected = true),
            match("selected-C", "C", selected = true),
            match("selected-D", "D", selected = true),
            match("selected-F", "F", selected = true),
        )
        val observation = observation(
            currentFilter = LabyrinthBattleElementFilter.FIRE,
            selected = selected,
            visible = emptyList(),
            canScroll = true,
        )
        val plan = planner(listOf("A", "B", "C", "D", "E", "F")).plan(
            sessionId,
            recommendation,
            observation,
        )

        assertEquals(listOf("F"), plan.needDeselectIds)
        assertEquals(listOf("E"), plan.needSelectIds)
        assertEquals(listOf("E"), plan.notCurrentlyVisibleIds)
        assertEquals("selected-F", plan.visibleDeselectTargets.single().slotId)
        // The current FIRE roster may still need scrolling to find E, but F must never be part of
        // the missing/search set merely because it needs to be removed from the team.
        assertFalse(plan.notCurrentlyVisibleIds.contains("F"))
    }

    @Test
    fun `execution step deselects before selecting and rejects a stale viewport`() {
        val recommendation = recommendation("A", "B", "C", "D", "E")
        val observation = observation(
            selected = listOf(match("selected-F", "F", selected = true)),
            visible = listOf(match("visible-A", "A"), match("visible-F", "F")),
            viewportRevision = 7L,
        )
        val plan = planner(listOf("A", "B", "C", "D", "E", "F")).plan(
            sessionId,
            recommendation,
            observation,
        )

        val step = requireNotNull(
            labyrinthBattleTeamExecutionStep(
                plan,
                sessionId,
                recommendation,
                observation,
                null,
                1920,
                1080,
            ),
        )

        assertEquals(LabyrinthBattleTeamExecutionKind.DESELECT_CHARACTER, step.kind)
        assertNull(
            labyrinthBattleTeamExecutionStep(
                plan,
                sessionId,
                recommendation,
                observation.copy(viewportRevision = 8L),
                null,
                1920,
                1080,
            ),
        )
    }

    @Test
    fun `execution step switches filters and scrolls when targets are not visible`() {
        val ids = listOf("A", "B", "C", "D", "E")
        val recommendation = recommendation(*ids.toTypedArray())
        val selected = listOf("A", "B", "C", "D").mapIndexed { index, id ->
            match("selected-$index", id, selected = true)
        }
        val selector = planner(ids)
        val wrongFilter = observation(
            currentFilter = LabyrinthBattleElementFilter.WATER,
            selected = selected,
        )
        val filterPlan = selector.plan(sessionId, recommendation, wrongFilter)
        val filterStep = requireNotNull(
            labyrinthBattleTeamExecutionStep(
                filterPlan,
                sessionId,
                recommendation,
                wrongFilter,
                null,
                1920,
                1080,
            ),
        )
        val scrollable = observation(
            currentFilter = LabyrinthBattleElementFilter.FIRE,
            selected = selected,
            canScroll = true,
            viewportRevision = 2L,
        )
        val scrollPlan = selector.plan(sessionId, recommendation, scrollable)
        val scrollStep = requireNotNull(
            labyrinthBattleTeamExecutionStep(
                scrollPlan,
                sessionId,
                recommendation,
                scrollable,
                null,
                1920,
                1080,
            ),
        )

        assertEquals(LabyrinthBattleTeamExecutionKind.SELECT_FILTER, filterStep.kind)
        assertEquals(LabyrinthBattleTeamExecutionKind.SCROLL_CHARACTERS, scrollStep.kind)
        assertTrue(scrollStep.action is AutomationAction.Swipe)
    }

    @Test
    fun `missing scrollbar does not block a safe roster scroll on a stable team page`() {
        val ids = listOf("A", "B", "C", "D", "E")
        val recommendation = recommendation(*ids.toTypedArray())
        val selected = listOf("A", "B", "C", "D").mapIndexed { index, id ->
            match("selected-$index", id, selected = true)
        }
        val observation = observation(
            currentFilter = LabyrinthBattleElementFilter.FIRE,
            selected = selected,
            canScroll = false,
            viewportRevision = 3L,
        )
        val plan = planner(ids).plan(sessionId, recommendation, observation)
        val step = requireNotNull(
            labyrinthBattleTeamExecutionStep(
                plan,
                sessionId,
                recommendation,
                observation,
                null,
                1920,
                1080,
            ),
        )

        assertTrue(plan.scrollRequired)
        assertTrue(plan.readyToExecute)
        assertEquals(LabyrinthBattleTeamExecutionKind.SCROLL_CHARACTERS, step.kind)
        assertTrue(step.action is AutomationAction.Swipe)
        assertEquals(0.0, step.scrollOriginPosition ?: Double.NaN, 0.0001)
    }

    @Test
    fun `moving character list is blocked until observation is stable`() {
        val plan = planner(listOf("A", "B", "C", "D", "E")).plan(
            sessionId,
            recommendation("A", "B", "C", "D", "E"),
            observation(recognitionState = LabyrinthBattleTeamRecognitionState.WAITING_FOR_STABILITY),
        )

        assertFalse(plan.readyToExecute)
        assertEquals("角色列表变化中，等待稳定", plan.blockedReason?.substringBefore('；'))
        assertTrue(plan.visibleSelectTargets.isEmpty())
    }

    @Test
    fun `low confidence visible target is diagnostic only and blocks execution`() {
        val plan = planner(listOf("A", "B", "C", "D", "E")).plan(
            sessionId,
            recommendation("A", "B", "C", "D", "E"),
            observation(
                selected = listOf("A", "B", "C", "D").mapIndexed { index, id ->
                    match("selected-$index", id, selected = true)
                },
                visible = listOf(match("visible-E", "E", confidence = 0.34)),
            ),
        )

        assertFalse(plan.readyToExecute)
        assertTrue(plan.visibleSelectTargets.isEmpty())
        assertEquals(listOf("E"), plan.unsafeVisibleMatches.map(LabyrinthBattleTeamUnsafeMatch::characterId))
        assertTrue(plan.blockedReason?.contains("低置信度") == true)
    }

    @Test
    fun `missing target in matching scrollable filter requests scroll without inventing a target`() {
        val ids = listOf("A", "B", "C", "D", "E")
        val plan = planner(ids).plan(
            sessionId,
            recommendation(*ids.toTypedArray()),
            observation(
                currentFilter = LabyrinthBattleElementFilter.FIRE,
                selected = listOf("A", "B", "C", "D").mapIndexed { index, id ->
                    match("selected-$index", id, selected = true)
                },
                canScroll = true,
            ),
        )

        assertEquals(listOf("E"), plan.notCurrentlyVisibleIds)
        assertEquals(LabyrinthBattleElementFilter.FIRE, plan.recommendedNextFilter)
        assertTrue(plan.scrollRequired)
        assertTrue(plan.visibleSelectTargets.isEmpty())
        assertTrue(plan.readyToExecute)
    }

    @Test
    fun `wrong current attribute recommends the missing targets attribute`() {
        val ids = listOf("A", "B", "C", "D", "E")
        val plan = planner(ids).plan(
            sessionId,
            recommendation(*ids.toTypedArray()),
            observation(
                currentFilter = LabyrinthBattleElementFilter.WATER,
                selected = listOf("A", "B", "C", "D").mapIndexed { index, id ->
                    match("selected-$index", id, selected = true)
                },
                canScroll = false,
            ),
        )

        assertEquals(LabyrinthBattleElementFilter.FIRE, plan.recommendedNextFilter)
        assertEquals(LabyrinthBattleElementFilter.FIRE, plan.recommendedFilterTarget?.filter)
        assertFalse(plan.scrollRequired)
        assertTrue(plan.readyToExecute)
    }

    @Test
    fun `combined member visual id canonicalizes to recommended battle unit`() {
        val ids = listOf("1807", "B", "C", "D", "E")
        val plan = planner(ids).plan(
            sessionId,
            recommendation(*ids.toTypedArray()),
            observation(
                selected = listOf(
                    match("selected-combined", "1183", selected = true, displayName = "初音"),
                    match("selected-B", "B", selected = true),
                ),
                visible = listOf("C", "D", "E").map { match("visible-$it", it) },
            ),
        )

        assertEquals(listOf("1807", "B"), plan.alreadyCorrectIds)
        assertFalse(plan.needSelectIds.contains("1807"))
        assertFalse(plan.needDeselectIds.contains("1807"))
    }

    @Test
    fun `viewport revision change invalidates an otherwise ready old plan`() {
        val ids = listOf("A", "B", "C", "D", "E")
        val recommendation = recommendation(*ids.toTypedArray())
        val stable = observation(
            selected = listOf("A", "B", "C", "D").mapIndexed { index, id ->
                match("selected-$index", id, selected = true)
            },
            visible = listOf(match("visible-E", "E")),
            viewportRevision = 7L,
        )
        val plan = planner(ids).plan(sessionId, recommendation, stable)

        assertTrue(plan.isStillValid(sessionId, recommendation, stable))
        assertFalse(plan.isStillValid(sessionId, recommendation, stable.copy(viewportRevision = 8L)))
        assertFalse(plan.isStillValid(AutomationSessionId(43L), recommendation, stable))
    }

    @Test
    fun `effective effect is never recommended for the generic first team`() {
        val ids = listOf("A", "B", "C", "D", "E")
        val plan = planner(ids).plan(
            sessionId,
            recommendation(*ids.toTypedArray()),
            observation(
                currentFilter = LabyrinthBattleElementFilter.EFFECTIVE_EFFECT,
                selected = listOf("A", "B", "C", "D").mapIndexed { index, id ->
                    match("selected-$index", id, selected = true)
                },
            ),
        )

        assertEquals(LabyrinthBattleElementFilter.FIRE, plan.recommendedNextFilter)
        assertFalse(plan.recommendedNextFilter == LabyrinthBattleElementFilter.EFFECTIVE_EFFECT)
        assertNull(plan.blockedReason)
    }

    @Test
    fun `missing roles are searched on their own attribute tab before the all tab`() {
        // 全部 shows several rows at once; a single-attribute tab is one or two rows, so it is the
        // preferred place to look for a role that was not found on the current page.
        val profiles = mapOf(
            "A" to profile("A", LabyrinthCharacterAttribute.FIRE),
            "B" to profile("B", LabyrinthCharacterAttribute.FIRE),
            "C" to profile("C", LabyrinthCharacterAttribute.FIRE),
            "D" to profile("D", LabyrinthCharacterAttribute.FIRE),
            "E" to profile("E", LabyrinthCharacterAttribute.DARK),
        )
        val selector = LabyrinthBattleTeamSelectionPlanner(profiles)
        val recommendation = recommendation("A", "B", "C", "D", "E")
        val selected = listOf("A", "B", "C", "D").mapIndexed { index, id -> match("selected-$index", id, selected = true) }
        val onAll = observation(currentFilter = LabyrinthBattleElementFilter.ALL, selected = selected, canScroll = true)

        val plan = selector.plan(sessionId, recommendation, onAll)

        assertEquals(listOf("E"), plan.notCurrentlyVisibleIds)
        assertEquals(LabyrinthBattleElementFilter.DARK, plan.recommendedNextFilter)
        assertEquals(LabyrinthBattleElementFilter.DARK, plan.recommendedFilterTarget?.filter)
        assertEquals(listOf(LabyrinthBattleElementFilter.DARK, LabyrinthBattleElementFilter.ALL), plan.missingTargetFilters)
        assertFalse(plan.scrollRequired)
        assertTrue(plan.readyToExecute)
        assertEquals(
            LabyrinthBattleTeamExecutionKind.SELECT_FILTER,
            labyrinthBattleTeamExecutionStep(plan, sessionId, recommendation, onAll, null, 1920, 1080)?.kind,
        )
    }

    @Test
    fun `exhausted attribute tab falls back to all and never bounces back`() {
        val ids = listOf("A", "B", "C", "D", "E")
        val selector = planner(ids)
        val recommendation = recommendation(*ids.toTypedArray())
        val selected = listOf("A", "B", "C", "D").mapIndexed { index, id -> match("selected-$index", id, selected = true) }
        val onFire = observation(currentFilter = LabyrinthBattleElementFilter.FIRE, selected = selected, canScroll = true)

        val fresh = selector.plan(sessionId, recommendation, onFire)
        assertEquals(LabyrinthBattleElementFilter.FIRE, fresh.recommendedNextFilter)
        assertTrue(fresh.scrollRequired)

        val fireExhausted = setOf(LabyrinthBattleElementFilter.FIRE)
        val recovery = requireNotNull(labyrinthBattleRosterFilterRecoveryStep(fresh, onFire, fireExhausted))
        assertEquals(LabyrinthBattleTeamExecutionKind.SELECT_FILTER, recovery.kind)
        assertTrue(recovery.key.startsWith("filter-recovery:ALL:"))
        assertTrue(recovery.label, recovery.label.contains("切换全部"))

        val afterFire = selector.plan(sessionId, recommendation, onFire, exhaustedFilters = fireExhausted)
        assertEquals(LabyrinthBattleElementFilter.ALL, afterFire.recommendedNextFilter)
        assertFalse(afterFire.scrollRequired)

        val onAll = onFire.copy(currentFilter = LabyrinthBattleElementFilter.ALL, viewportRevision = 9L)
        val scanningAll = selector.plan(sessionId, recommendation, onAll, exhaustedFilters = fireExhausted)
        assertEquals(LabyrinthBattleElementFilter.ALL, scanningAll.recommendedNextFilter)
        assertTrue(scanningAll.scrollRequired)

        val everything = fireExhausted + LabyrinthBattleElementFilter.ALL
        assertNull(labyrinthBattleRosterFilterRecoveryStep(scanningAll, onAll, everything))
    }

    @Test
    fun `fully swept editor excludes unfindable roles from the run roster instead of restarting the sweep`() {
        // 2026-09-15 20:58 bundle: the restored roster listed 13 names, the editor could show 4.
        // Every tab (火, 光, 全部) was scanned to its end 8 times, 66 swipes, because the
        // exhausted-filter set was cleared each time the sweep finished.
        fun joined(id: String, order: Int) = LabyrinthJoinedCharacter(id, "名称$id", order, 0.9, 0L, 0L)
        val roster = listOf("1095", "1236", "1091", "1346", "1011", "1068").mapIndexed { i, id -> joined(id, i) }
        val unfindable = setOf("1095", "1236", "1346")

        val remaining = requireNotNull(labyrinthRosterAfterUnfindableExclusion(roster, unfindable))
        assertEquals(listOf("1091", "1011", "1068"), remaining.map { it.characterId })

        // Nothing to exclude means no change: the caller must not loop on a no-op.
        assertNull(labyrinthRosterAfterUnfindableExclusion(roster, emptySet()))
        assertNull(labyrinthRosterAfterUnfindableExclusion(roster, setOf("9999")))

        // Combined-member ids canonicalize before comparison, like every other roster lookup.
        val combined = listOf(joined("1811", 0), joined("1011", 1))
        val afterCombined = requireNotNull(labyrinthRosterAfterUnfindableExclusion(combined, setOf("1811")))
        assertEquals(listOf("1011"), afterCombined.map { it.characterId })
    }

    private fun planner(
        ids: List<String>,
        attribute: LabyrinthCharacterAttribute = LabyrinthCharacterAttribute.FIRE,
    ) = LabyrinthBattleTeamSelectionPlanner(
        ids.associateWith { id -> profile(id, attribute) },
    )

    private fun recommendation(vararg ids: String): LabyrinthBattleTeamRecommendation {
        val members = ids.map { LabyrinthRecommendedTeamMember(it, "名称$it") }
        return LabyrinthBattleTeamRecommendation(
            members = members,
            damageType = LabyrinthTeamDamageType.PHYSICAL,
            score = 88.0,
            vanguard = members.first(),
            survivalAnchor = members.first(),
            reasons = listOf("测试推荐"),
            defenseMarkStacks = 4,
            targetCount = 1,
        )
    }

    private fun profile(id: String, attribute: LabyrinthCharacterAttribute) = LabyrinthRoleProfile(
        characterId = id,
        displayName = "名称$id",
        roleClass = "攻击者",
        attribute = attribute,
        damageType = LabyrinthRoleDamageType.PHYSICAL,
        position = id.hashCode().let { kotlin.math.abs(it % 1000) + 1 },
        physicalDamagePotential = 50.0,
        magicDamagePotential = 0.0,
    )

    private fun observation(
        currentFilter: LabyrinthBattleElementFilter = LabyrinthBattleElementFilter.ALL,
        selected: List<LabyrinthBattleCharacterMatch> = emptyList(),
        visible: List<LabyrinthBattleCharacterMatch> = emptyList(),
        canScroll: Boolean = false,
        recognitionState: LabyrinthBattleTeamRecognitionState = LabyrinthBattleTeamRecognitionState.STABLE,
        viewportRevision: Long = 1L,
    ) = LabyrinthBattleTeamObservation(
        currentFilter = currentFilter,
        filters = LabyrinthBattleElementFilter.entries
            .filter { it != LabyrinthBattleElementFilter.UNKNOWN }
            .mapIndexed { index, filter ->
                LabyrinthBattleFilterObservation(
                    filter = filter,
                    screenRect = EntryPixelRect(10 + index * 20, 10, 18, 10),
                    selectionScore = if (filter == currentFilter) 0.9 else 0.0,
                    selected = filter == currentFilter,
                )
            },
        visibleCharacters = visible,
        selectedCharacters = selected,
        scrollbar = LabyrinthBattleScrollbarObservation(
            trackRect = EntryPixelRect(1800, 200, 20, 500),
            thumbRect = if (canScroll) EntryPixelRect(1800, 200, 20, 200) else null,
            visible = canScroll,
            canScroll = canScroll,
            position = 0.0,
        ),
        recognitionState = recognitionState,
        viewportRevision = viewportRevision,
    )

    private fun match(
        slotId: String,
        characterId: String,
        confidence: Double = 0.90,
        selected: Boolean = false,
        displayName: String = "名称$characterId",
    ) = LabyrinthBattleCharacterMatch(
        slotId = slotId,
        characterId = characterId,
        displayName = displayName,
        iconVariant = "test",
        confidence = confidence,
        screenRect = EntryPixelRect(100 + slotId.hashCode().let { kotlin.math.abs(it % 900) }, 200, 100, 100),
        selected = selected,
    )
}
