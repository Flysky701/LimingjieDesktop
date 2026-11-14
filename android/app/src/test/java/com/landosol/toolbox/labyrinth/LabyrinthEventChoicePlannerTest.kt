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

    @Test
    fun `logical arena selects configured branch without regional reward id`() {
        val arena = com.landosol.toolbox.labyrinth.vision.LabyrinthArenaEvent.event
        fun observe(trusted: Boolean = true, rightEnabled: Double = 0.8) = LabyrinthEventChoiceObservation(
            arena, arena.choices.map { choice -> LabyrinthEventChoiceVisual(choice,
                EntryPixelRect((choice.slot - 1) * 200, 500, 100, 100), 0.8,
                if (choice.slot == 2) rightEnabled else 0.8) }, arena.name, 1.0, 0.5, 2, trusted,
        )
        fun decide(settings: LabyrinthStrategySettings, observation: LabyrinthEventChoiceObservation = observe(),
            rejected: Set<String> = emptySet()): LabyrinthEventChoiceDecision = LabyrinthEventChoicePlanner(
                emptyMap(), LabyrinthTeamOptimizer(LabyrinthTeamScorer(LabyrinthTeamScoringConfig(
                    attributeDamageBonus = mapOf(1 to 0.0, 2 to 0.08, 3 to 0.16, 4 to 0.25, 5 to 0.75)))), settings,
            ).decide(observation, emptySet(), LabyrinthRoleDecisionContext(0), emptyMap(), rejected)
        val disabled = LabyrinthStrategySettings()
        assertFalse(disabled.eventCustomizationEnabled)
        assertTrue(decide(disabled) is LabyrinthEventChoiceDecision.Wait)
        val defaults = disabled.copy(eventCustomizationEnabled = true)
        val extreme = decide(defaults) as LabyrinthEventChoiceDecision.Select
        assertEquals("arena.extreme", extreme.choiceId)
        assertEquals("arena", extreme.eventId)
        assertTrue(extreme.actionSafe)
        val normal = decide(defaults.copy(eventChoiceOverrides = mapOf(arena.name to arena.choices[0].name)))
            as LabyrinthEventChoiceDecision.Select
        assertEquals("arena.normal", normal.choiceId)
        assertTrue(normal.actionSafe)
        assertFalse((decide(defaults, observe(false)) as LabyrinthEventChoiceDecision.Select).actionSafe)
        assertFalse((decide(defaults, observe().copy(stableFrames = 1)) as LabyrinthEventChoiceDecision.Select).actionSafe)
        assertEquals("arena.normal", (decide(defaults, observe(rightEnabled = 0.0)) as LabyrinthEventChoiceDecision.Select).choiceId)
        assertEquals("arena.normal", (decide(defaults, rejected = setOf("arena.extreme")) as LabyrinthEventChoiceDecision.Select).choiceId)
        assertTrue(decide(defaults, rejected = setOf("arena.extreme", "arena.normal")) is LabyrinthEventChoiceDecision.Wait)
        assertEquals("arena.extreme", (decide(defaults.copy(eventChoiceOverrides = emptyMap()))
            as LabyrinthEventChoiceDecision.Select).choiceId)
        assertTrue(decide(defaults.copy(eventCustomizationEnabled = false)) is LabyrinthEventChoiceDecision.Wait)
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

    /** Independent slate policy must never fall back to either paid branch. */
    @Test
    fun `slate always selects left and waits when that branch cannot safely run`() {
        val event = EventResource(
            id = "1107",
            name = "发现了不可思议的石板！要怎么办？",
            choices = listOf(
                EventChoiceResource(id = "11073", slot = 1, name = "摸一下 应该没关系吧？", description = "阿尔法金币"),
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
        fun decide(
            customizationEnabled: Boolean,
            rejected: Set<String> = emptySet(),
            observed: LabyrinthEventChoiceObservation = observation,
        ): LabyrinthEventChoiceDecision = LabyrinthEventChoicePlanner(
            emptyMap(), LabyrinthTeamOptimizer(LabyrinthTeamScorer(
                LabyrinthTeamScoringConfig(attributeDamageBonus = (1..5).associateWith { 0.0 }),
            )), LabyrinthStrategySettings(eventCustomizationEnabled = customizationEnabled),
        ).decide(observed, emptySet(), LabyrinthRoleDecisionContext(0), emptyMap(), rejected)

        for (enabled in listOf(false, true)) {
            val first = decide(enabled) as LabyrinthEventChoiceDecision.Select
            assertEquals("11073", first.choiceId)
            assertTrue(first.actionSafe)
            assertEquals(observation.choices[0].buttonRect, first.buttonRect)
            assertTrue(decide(enabled, setOf("11073")) is LabyrinthEventChoiceDecision.Wait)
            assertTrue(decide(enabled, setOf("11072", "11071")) is LabyrinthEventChoiceDecision.Select)
            assertTrue(decide(enabled, observed = observation.copy(trusted = false)) is LabyrinthEventChoiceDecision.Wait)
            assertTrue(decide(enabled, observed = observation.copy(stableFrames = 1)) is LabyrinthEventChoiceDecision.Wait)
            val disabledLeft = observation.copy(choices = observation.choices.map {
                if (it.choice.id == "11073") it.copy(enabledConfidence = 0.0) else it
            })
            assertTrue(decide(enabled, observed = disabledLeft) is LabyrinthEventChoiceDecision.Wait)
            val wrongIdentity = observation.copy(choices = observation.choices.map {
                if (it.choice.id == "11073") it.copy(choice = it.choice.copy(name = "错误分支")) else it
            })
            assertTrue(decide(enabled, observed = wrongIdentity) is LabyrinthEventChoiceDecision.Wait)
        }
    }
}
