package com.landosol.toolbox.labyrinth.kernel

import com.landosol.toolbox.labyrinth.*
import com.landosol.toolbox.labyrinth.kernel.event.*
import com.landosol.toolbox.labyrinth.kernel.event.GameEventCondition.*
import com.landosol.toolbox.labyrinth.vision.*
import com.landosol.toolbox.gamedata.EventResource
import org.junit.Assert.*
import org.junit.Test

internal fun eventPlanner(pack: KernelEventExtensionPack = KernelEventExtensionPack()) =
    LabyrinthEventChoicePlanner(emptyMap(), LabyrinthTeamOptimizer(LabyrinthTeamScorer(
        LabyrinthTeamScoringConfig(attributeDamageBonus = (1..5).associateWith { 0.0 }))),
        LabyrinthStrategySettings(), pack)

internal fun observed(event: EventResource, trusted: Boolean = true, blue: Double = 0.8, known: Boolean = true) =
    LabyrinthEventChoiceObservation(event, event.choices.map {
        LabyrinthEventChoiceVisual(it, EntryPixelRect(200 * it.slot, 500, 150, 100), 0.8, blue)
    }, event.name, 1.0, 0.5, if (trusted) 2 else 1, trusted, catalogMatched = known)

class GameEventRuleEngineTest {
    @Test fun codecAndConditionsIncludeUnknownBalance() {
        val ctx = GameEventContext("e", "family", "庆典", 2, "发现礼物", acquiredRoleIds = setOf("a"),
            relicStacks = mapOf(LabyrinthRelicMark.DEFENSE to 2), alphaCoin = 500, optionCount = 2, enabledChoiceIds = setOf("c"))
        val clause = AllOf(listOf(EventIdIn(setOf("e")), EventFamilyIn(setOf("family")), EventNameEquals("庆典"),
            TitleRegex("庆."), OcrTextRegex("礼物"), AreaIn(setOf(2)), OptionCountEquals(2), AcquiredRoleContains("a"),
            AcquiredRoleCountRange(1, 1), RelicMarkStackRange(LabyrinthRelicMark.DEFENSE, 2, 3), AlphaCoinRange(100,500),
            ChoiceEnabled("c"), AnyOf(listOf(Not(EventIdIn(emptySet()))))))
        val rule = GameEventRule("choose", 200, clause, GameEventRuleAction.SelectBySlot(2))
        val pack = KernelEventExtensionPack(rules = listOf(rule))
        assertEquals(pack, KernelEventExtensionPackCodec.decode(KernelEventExtensionPackCodec.encode(pack)))
        assertThrows(IllegalArgumentException::class.java) { KernelEventExtensionPackCodec.decode("""{"schemaVersion":2}""") }
        val engine = GameEventRuleEngine(pack.rules)
        assertEquals(rule, engine.evaluate(ctx).rule)
        assertNull(engine.evaluate(ctx.copy(alphaCoin = null)).rule)
        assertNull(engine.evaluate(ctx.copy(rejectedChoiceIds = setOf("c"))).rule)
        assertNull(engine.evaluate(ctx.copy(relicStacks = emptyMap())).rule)
    }

    @Test fun higherPriorityCanOverrideBothFixedPoliciesWithoutWaivingSafety() {
        val fixtures = realEvents().filter { it.id in setOf("1101","1107") }
        for (event in fixtures) {
            val alternative = event.choices.first { it.slot == (if (event.id == "1101") 1 else 2) }
            val rule = GameEventRule("user", 101, EventIdIn(setOf(event.id)), GameEventRuleAction.SelectByChoiceId(alternative.id))
            fun decide(priority: Int, observation: LabyrinthEventChoiceObservation, rejected: Set<String> = emptySet()) =
                eventPlanner(KernelEventExtensionPack(rules = listOf(rule.copy(priority = priority)))).evaluate(
                    observation, emptySet(), LabyrinthRoleDecisionContext(0), emptyMap(), rejected).decision as LabyrinthEventChoiceDecision.Select
            assertNotEquals(alternative.id, decide(99, observed(event)).choiceId)
            assertEquals(alternative.id, decide(101, observed(event)).choiceId)
            assertFalse(decide(101, observed(event, trusted = false)).actionSafe)
            assertFalse(decide(101, observed(event, blue = 0.0)).actionSafe)
            assertFalse(decide(101, observed(event), setOf(alternative.id)).actionSafe)
        }
    }

    @Test fun selectionActionsWaitAndExplicitUnknownPolicies() {
        val event = realEvents().single { it.id == "1108" }
        val choice = event.choices.last()
        val observation = observed(event)
        val condition = GameEventCondition.EventIdIn(setOf(event.id))
        fun decide(action: GameEventRuleAction) = eventPlanner(KernelEventExtensionPack(rules =
            listOf(GameEventRule("choice", 150, condition, action)))).decide(observation, emptySet(), LabyrinthRoleDecisionContext(0), emptyMap())
        for (action in listOf(GameEventRuleAction.SelectBySlot(choice.slot), GameEventRuleAction.SelectByChoiceName(choice.name),
                GameEventRuleAction.SelectByChoiceRegex("右边"))) {
            assertEquals(choice.id, (decide(action) as LabyrinthEventChoiceDecision.Select).choiceId)
        }
        assertTrue(decide(GameEventRuleAction.SelectBySlot(9)) is LabyrinthEventChoiceDecision.Wait)
        assertEquals(LabyrinthEventChoiceDecision.Wait("保留金币"), decide(GameEventRuleAction.WaitWithReason("保留金币")))
        val paid = choice.copy(description = "金币×200", conditionType = 1)
        assertFalse(UnknownGameEventPolicy.FIRST_FREE_ENABLED.allows(paid))
        assertTrue(UnknownGameEventPolicy.FIRST_ENABLED_SLOT.allows(paid))
        assertTrue(UnknownGameEventPolicy.FIRST_FREE_ENABLED.allows(paid.copy(description = "免费", conditionType = 0)))
    }

    @Test fun settingsCompileToExactSavedNamesAndNovelChoicesUseEffectUtility() {
        val settings = LabyrinthStrategySettings(eventCustomizationEnabled = true)
        val rules = GameEventRuleEngine.buildDefaultRules(settings)
        for ((name, choice) in settings.effectiveEventChoiceOverrides()) {
            assertEquals(GameEventRuleAction.SelectByChoiceName(choice),
                GameEventRuleEngine(rules).evaluate(GameEventContext("novel", name = name)).action)
        }
        val event = EventResource(id = "festival", name = "庆典", choices = listOf(
            com.landosol.toolbox.gamedata.EventChoiceResource("empty", "等等", 1, "待补充"),
            com.landosol.toolbox.gamedata.EventChoiceResource("gift", "免费", 2, "阿尔法金币×500")))
        val result = eventPlanner().evaluate(observed(event), emptySet(), LabyrinthRoleDecisionContext(0), emptyMap())
        val best = result.decision as LabyrinthEventChoiceDecision.Select
        assertEquals("gift", best.choiceId)
        assertTrue(best.utility > 0.0 && best.actionSafe)
        assertEquals(listOf(GameEventEffectPrimitive.AlphaCoin(500)), result.primitives["gift"])
    }
}
