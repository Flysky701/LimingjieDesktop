package com.landosol.toolbox.labyrinth

import com.landosol.toolbox.gamedata.EventChoiceResource
import com.landosol.toolbox.gamedata.EventResource
import com.landosol.toolbox.labyrinth.vision.EntryPixelRect
import com.landosol.toolbox.labyrinth.vision.LabyrinthEventChoiceObservation
import com.landosol.toolbox.labyrinth.vision.LabyrinthEventChoiceVisual
import org.junit.Assert.*
import org.junit.Test

/** Fixed-input numerical baselines plus deterministic work counts, never timing assertions. */
class LabyrinthEventChoiceComputationReuseTest {
    private val roleClasses = listOf("掩护者", "治疗者", "增幅者", "增益者", "减益者", "妨碍者", "攻击者", "破防者")
    private val owned = (0 until 8).map { profile(it) }
    private val candidates = (8 until 16).map { profile(it) }
    private val profiles = (owned + candidates).associateBy { it.characterId }
    private val acquired = owned.map { it.characterId }.toSet()
    private val events = listOf(
        EventResource("1315", "是个非常适合郊游的好天气！", choices = listOf(
            EventChoiceResource("13151", "大家一起 做料理吧☆", slot = 1))),
        EventResource("2315", "料理完成了！不过食材还有剩余哦！", choices = listOf(
            EventChoiceResource("23152", "这样应该就 足够了吧？", slot = 1),
            EventChoiceResource("23151", "我还没 吃够呢☆", slot = 2))),
        EventResource("3315", "完美！不过食材还有很多哦！", choices = listOf(
            EventChoiceResource("33152", "这么多 根本吃不完吧！", slot = 1),
            EventChoiceResource("33151", "诶嘿嘿～ 再一下就好……", slot = 2))),
        EventResource("9901", "测试：职能池与全角色池", choices = listOf(
            EventChoiceResource("11072", "测试双池", slot = 1))),
    )

    private fun profile(index: Int) = LabyrinthRoleProfile(
        characterId = (1000 + index).toString(), displayName = "fixture-$index",
        roleClass = roleClasses[index % roleClasses.size],
        attribute = LabyrinthCharacterAttribute.entries[index % 5],
        damageType = if (index % 3 == 1) LabyrinthRoleDamageType.MAGIC else LabyrinthRoleDamageType.PHYSICAL,
        position = 100 + index * 37, userScore = 45.0 + (index % 7) * 6,
        physicalDamagePotential = 12.0 + (index % 5) * 10,
        magicDamagePotential = 9.0 + (index % 4) * 13,
        physicalDamage1Target = 20.0 + (index % 4) * 10,
        physicalDamage2Target = 15.0 + (index % 5) * 10,
        physicalDamage3Target = 10.0 + (index % 6) * 10,
        magicDamage1Target = 12.0 + (index % 5) * 10,
        magicDamage2Target = 18.0 + (index % 4) * 10,
        magicDamage3Target = 16.0 + (index % 5) * 10,
        functions = LabyrinthRoleFunctions(
            reliableVanguard = if (index % 8 == 0) 70.0 else 10.0,
            healing = if (index % 8 == 1) 60.0 else 0.0,
            physicalDefenseDown = if (index % 8 == 7) 55.0 else 0.0,
            magicDefenseDown = if (index % 8 == 4) 40.0 else 0.0,
        ),
    )

    private class Work {
        var searches = 0
        var baselineSearches = 0
        var visited = 0L
    }

    private fun planner(
        source: Map<String, LabyrinthRoleProfile> = profiles,
        work: Work = Work(), ownedCount: Int = owned.size,
        settings: LabyrinthStrategySettings = LabyrinthStrategySettings(eventCustomizationEnabled = false),
    ): LabyrinthEventChoicePlanner {
        val optimizer = LabyrinthTeamOptimizer(LabyrinthTeamScorer(LabyrinthTeamScoringConfig(
            attributeDamageBonus = mapOf(1 to 0.0, 2 to 0.08, 3 to 0.16, 4 to 0.25, 5 to 0.75),
        )), searchThreads = 1)
        optimizer.searchWorkObserver = { report ->
            work.searches++
            if (report.poolSize == ownedCount && report.requiredIndex < 0) work.baselineSearches++
            work.visited += report.visitedCombinationCount
        }
        return LabyrinthEventChoicePlanner(source, optimizer, settings)
    }

    private fun observe(event: EventResource, trusted: Boolean = true, stable: Int = 2, enabled: Double = 0.8) =
        LabyrinthEventChoiceObservation(event, event.choices.map { choice ->
            LabyrinthEventChoiceVisual(choice, EntryPixelRect(choice.slot * 250, 500, 180, 100), 0.8, enabled)
        }, event.name, 1.0, 0.4, stable, trusted)

    @Test fun `cooking first round selects its only branch without estimating the next stages`() {
        val work = Work()
        val p = planner(work = work)
        val context = LabyrinthRoleDecisionContext(0)
        val first = p.decide(observe(events[0]), acquired, context, emptyMap()) as LabyrinthEventChoiceDecision.Select
        assertEquals("13151", first.choiceId)
        assertEquals(0L, first.utility.toBits())
        assertFalse(first.utilityEstimated)
        assertTrue(first.actionSafe)
        assertEquals(0, work.searches)
        assertEquals(0, work.baselineSearches)

        val unstable = p.decide(observe(events[0], trusted = false, stable = 1), acquired, context, emptyMap())
            as LabyrinthEventChoiceDecision.Select
        assertEquals(first.choiceId, unstable.choiceId)
        assertFalse(unstable.actionSafe)
        assertTrue(unstable.safetyNote.orEmpty().contains("连续稳定"))

        val disabled = p.decide(observe(events[0], enabled = 0.0), acquired, context, emptyMap())
            as LabyrinthEventChoiceDecision.Select
        assertEquals(first.choiceId, disabled.choiceId)
        assertFalse(disabled.actionSafe)
        assertTrue(disabled.safetyNote.orEmpty().contains("灰蓝"))
    }

    @Test fun `cooking second and third stages use fixed left branches without estimation`() {
        val cases = listOf(events[1] to "23152", events[2] to "33152")
        for ((event, expectedChoice) in cases) {
            val work = Work()
            val p = planner(work = work)
            val context = LabyrinthRoleDecisionContext(0)
            val result = p.decide(observe(event), acquired, context, emptyMap()) as LabyrinthEventChoiceDecision.Select
            assertEquals(expectedChoice, result.choiceId)
            assertEquals(0L, result.utility.toBits())
            assertFalse(result.utilityEstimated)
            assertTrue(result.actionSafe)
            assertEquals(0, work.searches)
            assertEquals(0, work.baselineSearches)

            val unstable = p.decide(observe(event, trusted = false, stable = 1), acquired, context, emptyMap())
                as LabyrinthEventChoiceDecision.Select
            assertFalse(unstable.actionSafe)
            assertTrue(unstable.safetyNote.orEmpty().contains("连续稳定"))

            val disabled = p.decide(observe(event, enabled = 0.0), acquired, context, emptyMap())
                as LabyrinthEventChoiceDecision.Select
            assertFalse(disabled.actionSafe)
            assertTrue(disabled.safetyNote.orEmpty().contains("灰蓝"))
            assertEquals(0, work.searches)
        }
    }

    @Test fun `candidate marginal is reused across role pool and all-role pool`() {
        val work = Work()
        val p = planner(work = work)
        val context = LabyrinthRoleDecisionContext(0)
        val first = p.decide(observe(events.last()), acquired, context, emptyMap()) as LabyrinthEventChoiceDecision.Select
        assertTrue(first.actionSafe)
        assertEquals(17, work.searches)
        assertEquals(1, work.baselineSearches)

        val bits = first.utility.toBits()
        val second = p.decide(observe(events.last()), acquired, context, emptyMap()) as LabyrinthEventChoiceDecision.Select
        assertEquals(bits, second.utility.toBits())
        assertEquals(17, work.searches)
    }

    @Test fun `cached ranking never bypasses unstable frame or disabled button revalidation`() {
        val event = events.last()
        val context = LabyrinthRoleDecisionContext(0)
        val p = planner()
        val safe = p.decide(observe(event), acquired, context, emptyMap()) as LabyrinthEventChoiceDecision.Select
        assertTrue(safe.actionSafe)

        val unstable = p.decide(observe(event, trusted = false, stable = 1), acquired, context, emptyMap())
            as LabyrinthEventChoiceDecision.Select
        assertEquals(safe.choiceId, unstable.choiceId)
        assertEquals(safe.utility.toBits(), unstable.utility.toBits())
        assertFalse(unstable.actionSafe)
        assertTrue(unstable.safetyNote.orEmpty().contains("连续稳定"))

        val disabled = p.decide(observe(event, enabled = 0.0), acquired, context, emptyMap())
            as LabyrinthEventChoiceDecision.Select
        assertEquals(safe.choiceId, disabled.choiceId)
        assertFalse(disabled.actionSafe)
        assertTrue(disabled.safetyNote.orEmpty().contains("灰蓝"))
    }

    @Test fun `candidate cache distinguishes profiles that share a character id`() {
        val source = profiles + ("alias" to candidates.first().copy(
            userScore = 95.0, physicalDamagePotential = 95.0,
        ))
        val baseline = planner().decide(
            observe(events.last()), acquired, LabyrinthRoleDecisionContext(0), emptyMap(),
        ) as LabyrinthEventChoiceDecision.Select
        val alias = planner(source = source).decide(
            observe(events.last()), acquired, LabyrinthRoleDecisionContext(0), emptyMap(),
        ) as LabyrinthEventChoiceDecision.Select
        assertNotEquals(baseline.utility.toBits(), alias.utility.toBits())
    }
}
