package com.landosol.toolbox.labyrinth

import com.landosol.toolbox.gamedata.EventChoiceResource
import com.landosol.toolbox.gamedata.EventResource
import com.landosol.toolbox.labyrinth.kernel.planning.BattleExam
import com.landosol.toolbox.labyrinth.kernel.planning.LabyrinthRunPlanningContext
import com.landosol.toolbox.labyrinth.kernel.planning.ModelBattlePassEstimator
import com.landosol.toolbox.labyrinth.kernel.strategy.BattleValue
import com.landosol.toolbox.labyrinth.kernel.strategy.BattleValueContext
import com.landosol.toolbox.labyrinth.kernel.strategy.BattleValueEstimate
import com.landosol.toolbox.labyrinth.kernel.strategy.BattleValueSource
import com.landosol.toolbox.labyrinth.kernel.strategy.ContextualBattleValueEstimator
import com.landosol.toolbox.labyrinth.vision.EntryPixelRect
import com.landosol.toolbox.labyrinth.vision.LabyrinthBattleCharacterMatch
import com.landosol.toolbox.labyrinth.vision.LabyrinthCharacterMatch
import com.landosol.toolbox.labyrinth.vision.LabyrinthEventChoiceObservation
import com.landosol.toolbox.labyrinth.vision.LabyrinthEventChoiceVisual
import com.landosol.toolbox.labyrinth.vision.LabyrinthRelicMatch
import com.landosol.toolbox.labyrinth.vision.LabyrinthShopItemKind
import com.landosol.toolbox.labyrinth.vision.LabyrinthShopItemMatch
import com.landosol.toolbox.labyrinth.vision.LabyrinthShopItemStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PathBPlannersWiringTest {
    private val rect = EntryPixelRect(40, 50, 100, 120)
    private val profiles = (1001..1008).map { id ->
        LabyrinthRoleProfile(
            characterId = id.toString(), displayName = "角色$id", roleClass = if (id == 1001) "掩护者" else "攻击者",
            attribute = LabyrinthCharacterAttribute.FIRE, damageType = LabyrinthRoleDamageType.PHYSICAL,
            position = if (id == 1001) 100 else 400,
            userScore = if (id == 1008) 10.0 else 90.0, physicalDamagePotential = 60.0,
            functions = if (id == 1001) LabyrinthRoleFunctions(reliableVanguard = 100.0, selfSustain = 100.0)
                else LabyrinthRoleFunctions(),
        )
    }.associateBy { it.characterId }
    private val owned = (1001..1005).map(Int::toString).toSet()
    private val rules = LabyrinthRoleDecisionContext(0)
    private val optimizer = LabyrinthTeamOptimizer(
        LabyrinthTeamScorer(LabyrinthTeamScoringConfig(attributeDamageBonus = (1..5).associateWith { 0.0 })),
        searchThreads = 1,
    )
    private val model = object : ContextualBattleValueEstimator {
        override fun estimateWithSource(context: BattleValueContext, candidates: List<List<Int>>) =
            BattleValueEstimate(candidates.map { team ->
                val pWin = if (100801 in team || 41002 in context.relicIds) 0.9 else 0.35
                BattleValue(pWin, 1.0 - pWin, 0.0, pWin, 0.0, 0.5)
            }, BattleValueSource.MODEL)
    }
    private val planning = LabyrinthRunPlanningContext(
        enabled = true, difficulty = 5, currentArea = 3,
        upcomingExams = listOf(BattleExam(770330401, 3, isBoss = true)),
        remainingShops = 1, enhanceNode21 = false, reviveAvailable = false,
        observedRelicIds = emptySet(), markStacks = emptyMap(), alphaRupee = null,
        passEstimator = ModelBattlePassEstimator(model, profiles), cacheFingerprint = "path-b",
    )

    @Test fun roleRewardAndFreePickerUseModelOnlyForSafeCandidates() {
        val planner = LabyrinthRoleRewardChoicePlanner(profiles, LabyrinthRoleChoicePolicy(optimizer))
        val roleIds = listOf("1006", "1007", "1008")
        val cards = LabyrinthRoleRewardChoicePlanner.SLOT_IDS.zip(roleIds).map { (slot, id) ->
            LabyrinthCharacterMatch(slotId = slot, characterId = id, displayName = profiles.getValue(id).displayName,
                confidence = 0.9, screenRect = rect, trusted = true)
        }
        fun select(context: LabyrinthRunPlanningContext?) =
            planner.decide(cards, owned, rules, 1920, 1080, context) as LabyrinthRoleRewardChoiceDecision.Select
        val legacy = select(null)
        assertEquals(legacy, select(planning.copy(enabled = false)))
        assertEquals(legacy, select(planning.copy(passEstimator = null)))
        assertEquals("规则回退", legacy.planningSource)
        assertTrue(legacy.characterId != "1008")
        val ranked = select(planning)
        assertEquals("1008", ranked.characterId)
        assertEquals("PathB-NN", ranked.planningSource)
        assertTrue(ranked.explanation.first().contains("35%→90%"))
        val weak = cards.dropLast(1) + cards.last().copy(confidence = 0.3)
        val safe = planner.decide(weak, owned, rules, 1920, 1080, planning) as LabyrinthRoleRewardChoiceDecision.Select
        assertTrue(safe.characterId in roleIds.take(2))
        assertTrue(planner.decide(cards.take(2), owned, rules, 1920, 1080, planning) is LabyrinthRoleRewardChoiceDecision.Wait)

        val visible = cards.map { card ->
            LabyrinthBattleCharacterMatch(card.slotId, card.characterId, card.displayName, null, 0.9, rect, selected = false)
        }
        val free = planner.decideFreeVisibleRole(visible, owned, rules, planning) as LabyrinthRoleRewardChoiceDecision.Select
        assertEquals("1008", free.characterId)
        assertEquals("PathB-NN", free.planningSource)
        val alreadyOwned = planner.decideFreeVisibleRole(visible, owned + "1008", rules, planning)
            as LabyrinthRoleRewardChoiceDecision.Select
        assertTrue(alreadyOwned.characterId in roleIds.take(2))
    }

    @Test fun relicModelRespectsCapstoneAndFallsBackWhenUnavailable() {
        val policy = LabyrinthRelicChoicePolicy()
        val baseline = relic("41001", "加速", "3")
        val modelFavorite = relic("41002", "强化", "1")
        val choices = listOf(baseline, modelFavorite)
        fun choose(context: LabyrinthRunPlanningContext?) = requireNotNull(policy.choose(
            emptyList(), choices, currentArea = 3, planningContext = context, acquiredCharacterIds = owned,
        ))
        val legacy = choose(null)
        assertEquals(baseline, legacy.choice)
        assertEquals(legacy, choose(planning.copy(enabled = false)))
        assertEquals(legacy, choose(planning.copy(passEstimator = null)))
        val ranked = choose(planning)
        assertEquals(modelFavorite, ranked.choice)
        assertEquals("PathB-NN", ranked.planningSource)
        assertEquals(LabyrinthRelicChoicePhase.REACH_ANY_FIFTEEN, ranked.phase)
        val capstone = requireNotNull(policy.choose(emptyList(), choices,
            calibratedStacks = mapOf(LabyrinthRelicMark.ACCELERATION to 12), currentArea = 3,
            planningContext = planning, acquiredCharacterIds = owned))
        assertEquals(baseline, capstone.choice)
        assertEquals("规则", capstone.planningSource)
    }

    @Test fun shopKeepsButtonAndAreaGatesAndUsesOfficialDpWithObservedGold() {
        val policy = LabyrinthShopPolicy(finalArea = 3)
        val items = listOf(relic("41001", "加速", "3"), relic("41002", "强化", "1")).map { relic ->
            LabyrinthShopItemMatch(relic.slotId, rect, rect, 0.9, status = LabyrinthShopItemStatus.AVAILABLE,
                kind = LabyrinthShopItemKind.RELIC, relicMatch = relic)
        }
        fun shop(area: Int, context: LabyrinthRunPlanningContext?, refreshes: Int = 0,
                 shelf: List<LabyrinthShopItemMatch> = items) = policy.decide(area, shelf, emptyList(),
            refreshAvailable = true, refreshesUsedInVisit = refreshes,
            coinBalance = context?.alphaRupee ?: 999999,
            planningContext = context, acquiredCharacterIds = owned)
        val legacy = shop(3, null) as LabyrinthShopDecision.BuyRelic
        assertEquals(items.first(), legacy.item)
        assertEquals("规则", legacy.planningSource)
        val ranked = shop(3, planning) as LabyrinthShopDecision.BuyRelic
        assertEquals(items.last(), ranked.item)
        assertEquals("PathB-NN", ranked.planningSource)
        val knownWallet = planning.copy(alphaRupee = 1800, passEstimator = null)
        assertTrue(shop(4, knownWallet) is LabyrinthShopDecision.Close)
        val dp = shop(3, knownWallet, shelf = items.take(1)) as LabyrinthShopDecision.BuyRelic
        assertEquals("PathB-官方分", dp.planningSource)
        val depleted = List(3) { index -> items.first().copy(slotId = "sold$index", status = LabyrinthShopItemStatus.DISABLED) }
        assertTrue(shop(3, knownWallet, refreshes = 10, shelf = depleted) is LabyrinthShopDecision.Close)
    }

    @Test fun eventRanksOfficialRewardsAndRetainsFixedPolicies() {
        val planner = LabyrinthEventChoicePlanner(profiles, optimizer)
        fun event(id: String, coinId: String, relicId: String): LabyrinthEventChoiceObservation {
            val options = listOf(
                EventChoiceResource(coinId, "金币", 0, "获得α币1500"),
                EventChoiceResource(relicId, "遗物", 1, "获得★1遗物"),
            )
            return LabyrinthEventChoiceObservation(EventResource(id = id, name = "测试事件", choices = options),
                options.map { LabyrinthEventChoiceVisual(it, rect, 0.9) }, "", 0.9, 0.2, 2, true)
        }
        fun choose(observation: LabyrinthEventChoiceObservation, context: LabyrinthRunPlanningContext?) =
            planner.decide(observation, owned, rules, emptyMap(), planningContext = context) as LabyrinthEventChoiceDecision.Select
        val utility = event("1900", "19001", "19002")
        assertEquals("19001", choose(utility, null).choiceId)
        assertNull(choose(utility, planning.copy(enabled = false)).planningSource)
        val official = choose(utility, planning)
        assertEquals(official.toString(), "19002", official.choiceId)
        assertEquals("PathB-官方分", official.planningSource)
        assertEquals(400.0, official.utility, 1e-6)

        val challenge = EventChoiceResource("19001", "挑战", 0, "极难战斗")
        val eventBattle = utility.copy(
            event = utility.event.copy(choices = listOf(challenge, utility.event.choices.last())),
            choices = listOf(LabyrinthEventChoiceVisual(challenge, rect, 0.9), utility.choices.last()),
        )
        val withExam = planning.copy(upcomingExams = listOf(BattleExam(770220901, 3, isBoss = false)),
            cacheFingerprint = "event-battle")
        assertEquals("19002", choose(eventBattle, null).choiceId)
        val battle = choose(eventBattle, withExam)
        assertEquals("19001", battle.choiceId)
        assertEquals("PathB-NN", battle.planningSource)
        assertEquals(700.0, battle.utility, 1e-6)

        val fixedOption = EventChoiceResource("11073", "摸一下 应该没关系吧？", 1, "获得α币1500")
        val fixed = utility.copy(
            event = EventResource(id = "1107", name = "发现了不可思议的石板！要怎么办？", choices = listOf(fixedOption)),
            choices = listOf(LabyrinthEventChoiceVisual(fixedOption, rect, 0.9)),
        )
        val fixedDecision = choose(fixed, planning)
        assertEquals("11073", fixedDecision.choiceId)
        assertNull(fixedDecision.planningSource)
        assertEquals(choose(fixed, null), fixedDecision)
    }

    private fun relic(id: String, mark: String, bonus: String) = LabyrinthRelicMatch(
        slotId = id, relicId = id, displayName = "遗物$id", attribute = mark, attributeBonus = bonus,
        effect = null, confidence = 0.9, screenRect = rect, iconRect = rect, rarity = 1,
    )
}
