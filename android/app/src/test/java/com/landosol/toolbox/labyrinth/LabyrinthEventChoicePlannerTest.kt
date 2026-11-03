package com.landosol.toolbox.labyrinth

import com.landosol.toolbox.gamedata.EventChoiceResource
import com.landosol.toolbox.gamedata.EventResource
import com.landosol.toolbox.labyrinth.vision.EntryPixelRect
import com.landosol.toolbox.labyrinth.vision.LabyrinthEventChoiceObservation
import com.landosol.toolbox.labyrinth.vision.LabyrinthEventChoiceVisual
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LabyrinthEventChoicePlannerTest {
    @Test fun `configured win rates change battle choice without bypassing visual safety`() {
        val event = EventResource("1105", "斗技场", area = 1, choices = listOf(
            EventChoiceResource("11051", "普通", slot = 1), EventChoiceResource("11052", "极难", slot = 2)))
        fun decision(rate: Int, trusted: Boolean): LabyrinthEventChoiceDecision.Select {
            val planner = LabyrinthEventChoicePlanner(emptyMap(), LabyrinthTeamOptimizer(LabyrinthTeamScorer(
                LabyrinthTeamScoringConfig(attributeDamageBonus = mapOf(1 to 0.0, 2 to 0.08, 3 to 0.16, 4 to 0.25, 5 to 0.75)))),
                LabyrinthStrategySettings(extremeWinRate = rate))
            return planner.decide(LabyrinthEventChoiceObservation(event,
                event.choices.map { LabyrinthEventChoiceVisual(it, EntryPixelRect(0, 0, 100, 100), 0.8) },
                "斗技场", 1.0, 0.2, 2, trusted), emptySet(), LabyrinthRoleDecisionContext(0), emptyMap()) as LabyrinthEventChoiceDecision.Select
        }
        assertEquals("11052", decision(70, true).choiceId)
        assertEquals("11051", decision(20, true).choiceId)
        assertFalse(decision(20, false).actionSafe)
    }

    @Test fun dailyBanksVisibleRewardsWhileScorePresetTakesTheTargetEvent() {
        fun pick(event: EventResource, settings: LabyrinthStrategySettings): String {
            val planner = LabyrinthEventChoicePlanner(emptyMap(), LabyrinthTeamOptimizer(LabyrinthTeamScorer(
                LabyrinthTeamScoringConfig(attributeDamageBonus = mapOf(1 to 0.0, 2 to 0.08, 3 to 0.16, 4 to 0.25, 5 to 0.75)))), settings)
            val observed = LabyrinthEventChoiceObservation(event, event.choices.map {
                LabyrinthEventChoiceVisual(it, EntryPixelRect(0, 0, 100, 100), 0.8)
            }, event.name, 1.0, 0.2, 2, true)
            return (planner.decide(observed, emptySet(), LabyrinthRoleDecisionContext(0), emptyMap())
                as LabyrinthEventChoiceDecision.Select).choiceId
        }
        val bank = EventResource("2316", name = "挑战", choices = listOf(
            EventChoiceResource("23161", slot = 1, conditionType = 1, conditionValue = 2000),
            EventChoiceResource("23162", slot = 2)))
        assertEquals("23162", pick(bank, LabyrinthStrategySettings()))
        val arena = EventResource("1105", name = "斗技场", choices = listOf(
            EventChoiceResource("11051", slot = 1), EventChoiceResource("11052", slot = 2)))
        assertEquals("11051", pick(arena, LabyrinthStrategySettings(extremeWinRate = 20)))
        assertEquals("11052", pick(arena, LabyrinthStrategySettings(highScorePreset = true, extremeWinRate = 20)))
    }

    private val planner = LabyrinthEventChoicePlanner(
        profiles = emptyMap(),
        optimizer = LabyrinthTeamOptimizer(
            LabyrinthTeamScorer(
                LabyrinthTeamScoringConfig(
                    attributeDamageBonus = mapOf(1 to 0.0, 2 to 0.08, 3 to 0.16, 4 to 0.25, 5 to 0.75),
                ),
            ),
        ),
    )

    @Test
    fun `web utility model recommends fixed coins on lost-in-dungeon event`() {
        val decision = planner.decide(
            observation = observation(trusted = true, buttonConfidence = 0.8),
            acquiredCharacterIds = emptySet(),
            context = LabyrinthRoleDecisionContext(defenseMarkStacks = 0),
            relicStacks = LabyrinthRelicMark.entries.associateWith { 0 },
        ) as LabyrinthEventChoiceDecision.Select

        assertEquals("11082", decision.choiceId)
        assertTrue(decision.actionSafe)
    }

    @Test
    fun `recommendation remains visible but unsafe while mapped button is not usable blue`() {
        val decision = planner.decide(
            observation = observation(trusted = true, buttonConfidence = 0.0),
            acquiredCharacterIds = emptySet(),
            context = LabyrinthRoleDecisionContext(defenseMarkStacks = 0),
            relicStacks = LabyrinthRelicMark.entries.associateWith { 0 },
        ) as LabyrinthEventChoiceDecision.Select

        assertEquals("11082", decision.choiceId)
        assertFalse(decision.actionSafe)
        // The button is drawn but dimmed: the note must say so, since that is the observable
        // difference between "no button" and "an option this run cannot afford".
        assertTrue(decision.safetyNote.orEmpty().contains("灰蓝"))
    }

    @Test
    fun `disabled high utility conditional option falls back to best enabled choice`() {
        val event = EventResource(
            id = "2316",
            name = "再较量一次！",
            area = 2,
            choices = listOf(
                EventChoiceResource("23162", "到这里就行了……", slot = 1, description = "挑战结束"),
                EventChoiceResource(
                    "23161",
                    "今天真是走运！",
                    slot = 2,
                    description = "有50％的概率获得阿尔法金币×4000并进入下一个事件",
                    conditionType = 1,
                    conditionValue = 2000,
                ),
            ),
        )
        val observation = LabyrinthEventChoiceObservation(
            event = event,
            choices = listOf(
                LabyrinthEventChoiceVisual(event.choices[0], EntryPixelRect(100, 800, 300, 150), 0.85),
                LabyrinthEventChoiceVisual(event.choices[1], EntryPixelRect(600, 800, 300, 150), 0.02),
            ),
            rawText = event.name,
            confidence = 1.0,
            rivalMargin = 0.2,
            stableFrames = 2,
            trusted = true,
        )

        val decision = planner.decide(
            observation = observation,
            acquiredCharacterIds = emptySet(),
            context = LabyrinthRoleDecisionContext(defenseMarkStacks = 0),
            relicStacks = LabyrinthRelicMark.entries.associateWith { 0 },
        ) as LabyrinthEventChoiceDecision.Select

        assertEquals("23162", decision.choiceId)
        assertTrue(decision.actionSafe)
    }

    private fun observation(trusted: Boolean, buttonConfidence: Double): LabyrinthEventChoiceObservation {
        val event = EventResource(
            id = "1108",
            name = "在地下城迷路了！要走哪边？",
            area = 1,
            choices = listOf(
                EventChoiceResource("11082", "难道不是左边吗？", slot = 1, description = "阿尔法金币×1000"),
                EventChoiceResource(
                    "11081",
                    "当然是右边啊！",
                    slot = 2,
                    description = "50%★1遗物＋50%随机职阶随机角色×2",
                ),
            ),
        )
        return LabyrinthEventChoiceObservation(
            event = event,
            choices = event.choices.mapIndexed { index, choice ->
                LabyrinthEventChoiceVisual(
                    choice,
                    EntryPixelRect(500 + index * 500, 800, 300, 150),
                    buttonConfidence,
                )
            },
            rawText = event.name,
            confidence = 1.0,
            rivalMargin = 0.2,
            stableFrames = 2,
            trusted = trusted,
        )
    }

    /**
     * 2026-09-21 screenshot: 1,550 alpha coins in hand and two of the three options priced
     * 消耗2000. The game draws a cost-gated button in the same blue as a free one, so the tap is
     * dispatched, refused, and the page never moves. Retiring the refused option must hand the
     * run the next-best choice instead of retrying the same dead button until the run stops.
     */
    @Test
    fun `an option the game refused is retired so the next best one runs`() {
        val event = EventResource(
            id = "1107",
            name = "发现了不可思议的石板！要怎么办？",
            choices = listOf(
                EventChoiceResource(id = "11073", slot = 1, name = "摸一下", description = "阿尔法金币"),
                EventChoiceResource(
                    id = "11072", slot = 2, name = "全力挥击", description = "选择印记",
                    conditionType = 1, conditionValue = 2000,
                ),
                EventChoiceResource(
                    id = "11071", slot = 3, name = "小心点", description = "迷宫遗物",
                    conditionType = 1, conditionValue = 2000,
                ),
            ),
        )
        val observation = LabyrinthEventChoiceObservation(
            event = event,
            choices = event.choices.mapIndexed { index, choice ->
                LabyrinthEventChoiceVisual(
                    choice = choice,
                    buttonRect = EntryPixelRect(100 + index * 500, 880, 260, 80),
                    buttonConfidence = 0.9,
                )
            },
            rawText = "",
            confidence = 0.95,
            rivalMargin = 0.2,
            stableFrames = 3,
            trusted = true,
        )
        val planner = LabyrinthEventChoicePlanner(emptyMap(), LabyrinthTeamOptimizer(LabyrinthTeamScorer(
            LabyrinthTeamScoringConfig(attributeDamageBonus = (1..5).associateWith { 0.0 }),
        )))
        fun decide(rejected: Set<String>) = planner.decide(
            observation = observation,
            acquiredCharacterIds = emptySet(),
            context = LabyrinthRoleDecisionContext(0),
            relicStacks = emptyMap(),
            rejectedChoiceIds = rejected,
        )

        val first = decide(emptySet()) as LabyrinthEventChoiceDecision.Select
        assertTrue(first.actionSafe)

        // The winner was refused: it must not be recommended for action again, and the decision
        // must move to a different option rather than repeating the dead one.
        val second = decide(setOf(first.choiceId)) as LabyrinthEventChoiceDecision.Select
        assertTrue(second.choiceId != first.choiceId)
        assertTrue(second.actionSafe)

        val third = decide(setOf(first.choiceId, second.choiceId)) as LabyrinthEventChoiceDecision.Select
        assertTrue(third.choiceId !in setOf(first.choiceId, second.choiceId))
        assertTrue(third.actionSafe)

        // Every option refused: stop recommending taps and say so, instead of looping.
        val exhausted = decide(event.choices.mapTo(mutableSetOf()) { it.id })
        assertTrue(exhausted is LabyrinthEventChoiceDecision.Wait)
    }
}
