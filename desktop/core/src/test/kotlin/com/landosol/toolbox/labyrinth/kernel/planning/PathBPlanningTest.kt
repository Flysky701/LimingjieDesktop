package com.landosol.toolbox.labyrinth.kernel.planning

import com.landosol.toolbox.labyrinth.LabyrinthNodeJson
import com.landosol.toolbox.labyrinth.LabyrinthRelicMark
import com.landosol.toolbox.labyrinth.LabyrinthRoleFunctions
import com.landosol.toolbox.labyrinth.LabyrinthRoleProfile
import com.landosol.toolbox.labyrinth.LabyrinthRouteJson
import com.landosol.toolbox.labyrinth.kernel.strategy.BattleValue
import com.landosol.toolbox.labyrinth.kernel.strategy.BattleValueContext
import com.landosol.toolbox.labyrinth.kernel.strategy.BattleValueEstimate
import com.landosol.toolbox.labyrinth.kernel.strategy.BattleValueSource
import com.landosol.toolbox.labyrinth.kernel.strategy.ContextualBattleValueEstimator
import com.landosol.toolbox.labyrinth.node.LabyrinthNodeTypes as Node
import com.landosol.toolbox.protocol.labyrinth.LabyrinthOpeningRelicState
import org.junit.Assert.*
import org.junit.Test

class PathBPlanningTest {
    private val official = ScoreSettings.currentOfficial()
    private val discount = ShopDiscountModel(enhanceNode21 = true, relic40003 = true)
    private val random = ShopItem("random", ShopItemKind.ROLE_IMPRINT, 1200)
    private val cheap = ShopItem("cheap", ShopItemKind.RELIC_1STAR, 1800)
    private val expensive = ShopItem("expensive", ShopItemKind.RELIC_3STAR, 3800)
    private val shelf = ShopShelfModel(listOf(1.0 to listOf(cheap)))
    private val baseState = PlanningState(5, 2, emptySet(), emptySet(), emptyMap(), 0, true, false)
    private val boss = BattleExam(77030101, 3, isBoss = true)

    @Test fun discountsUseExactFloorsAndDefaultShelfWeights() {
        assertEquals(0.0, ShopDiscountModel().rate, 0.0)
        assertEquals(0.35, discount.rate, 0.0)
        assertEquals(0.10, ShopDiscountModel(relic40003 = true).rate, 0.0)
        assertEquals(listOf(780, 1820, 1170, 1820, 2470, 6499), listOf(1200, 2800, 1800, 2800, 3800, 9999).map(discount::price))
        assertEquals(7499, ShopDiscountModel(enhanceNode21 = true).price(9999))
        assertEquals(Int.MAX_VALUE, ShopDiscountModel().price(Int.MAX_VALUE))
        assertEquals(mapOf(0L to .5, 400L to .5), shopScoreDistribution(1800, 1))
    }

    @Test fun visiblePurchasesUseExactSubsetAndRespectDarkRefresh() {
        val offers = listOf(expensive, random, cheap)
        assertEquals(listOf(cheap, random), clearanceLadder(offers, 1950, discountModel = discount))
        for (final in listOf(true, false)) {
            assertEquals(
                ShopPlanResult(listOf(2, 1), false, 600.0, 1950, 0),
                planShopVisit(offers, 1950, discountModel = discount, shelfModel = shelf,
                    refreshAvailable = final, isFinalArea = final),
            )
        }
        val premium = ShopItem("premium", ShopItemKind.RELIC_2STAR, 2800, extraBattlePts = 310.0)
        val optimum = planShopVisit(listOf(premium, cheap, cheap), 2340, discountModel = discount,
            shelfModel = shelf, isFinalArea = true)
        assertEquals(listOf(1, 2), optimum.buyIndices)
        assertEquals(800.0, optimum.expectedPts, 0.0)
        val final = planShopVisit(listOf(cheap), 2640, discountModel = discount, shelfModel = shelf,
            isFinalArea = true, remainingShops = 2)
        assertEquals(ShopPlanResult(listOf(0), false, 800.0, 1170, 1470), final)
    }

    @Test fun refreshUsesIncreasingFeesAndObservedShelfProbabilities() {
        fun plan(gold: Int, refreshCount: Int = 0, settings: ScoreSettings = official) =
            planShopVisit(emptyList(), gold, settings, discountModel = discount, shelfModel = shelf,
                refreshCount = refreshCount, maxRefreshes = 2)
        assertEquals(ShopPlanResult(emptyList(), true, 800.0, 0, 3240), plan(3240))
        assertEquals(400.0, plan(3239).expectedPts, 0.0)
        assertTrue(plan(1770, refreshCount = 1).shouldRefresh)
        assertFalse(plan(1769, refreshCount = 1).shouldRefresh)
        assertEquals(0.0, plan(1490, settings = official.copy(shopRefreshCosts = listOf(400, 900))).expectedPts, 0.0)
        val observations = ShopShelfModel.fromObservations(listOf(emptyList(), listOf(cheap), listOf(cheap), listOf(cheap)))
        assertEquals(300.0, planShopVisit(emptyList(), 1470, discountModel = discount,
            shelfModel = observations, maxRefreshes = 1).expectedPts, 0.0)
    }

    @Test fun goldCurveCarriesCoinsAndSupportsTerminalRetention() {
        assertEquals(listOf(0.0, 400.0, 800.0), (0..2).map {
            goldToPtsCurve(2340, it, discountModel = discount, shelfModel = shelf)
        })
        val curve = (0..2900 step 75).map { goldToPtsCurve(it, 2, discountModel = discount, shelfModel = shelf) }
        assertEquals(curve.sorted(), curve)
        assertEquals(400.0, marginalGoldPts(2339, 1, 2, discountModel = discount, shelfModel = shelf), 0.0)
        assertEquals(mapOf(800L to 1.0), shopScoreDistribution(2340, 2, discountModel = discount, shelfModel = shelf))
        assertEquals(ShopPlanResult(emptyList(), false, 800.0, 0, 2470),
            planShopVisit(listOf(expensive), 2470, discountModel = discount, shelfModel = shelf,
                maxRefreshes = 0, remainingShops = 3))
        fun retain(gold: Int) = planShopVisit(listOf(cheap), gold, discountModel = discount,
            maxRefreshes = 0, coinRetentionValueFn = { minOf(10_000, it).toDouble() })
        assertTrue(retain(10_000).buyIndices.isEmpty())
        assertEquals(400.0, retain(11_170).expectedPts, 0.0)
    }

    @Test fun namedImprintsAndFractionalRewardsShareOneFinalFloor() {
        val named = random.copy(characterId = "6001")
        val namedShelf = ShopShelfModel(listOf(1.0 to listOf(named)))
        assertEquals(200.0, goldToPtsCurve(3600, 3, shelfModel = namedShelf), 0.0)
        assertEquals(0.0, goldToPtsCurve(3600, 3, shelfModel = namedShelf, ownedCharacterIds = setOf("6001")), 0.0)
        assertTrue(planShopVisit(listOf(named, named), 1200, boughtMask = 1).buyIndices.isEmpty())
        val fractional = official.copy(unitPts = 1, scoreRates = mapOf(5 to 125))
        assertEquals(5.0, planShopVisit(List(4) { random }, 4800, fractional, isFinalArea = true).expectedPts, 0.0)
        assertEquals(2.0, goldToPtsCurve(1200, 1, fractional, shelfModel = ShopShelfModel(listOf(1.0 to listOf(random))),
            scoreRemainder = 75), 0.0)
    }

    @Test fun roleChoicesCountNewRolesAndUseComparableModelEstimates() {
        val state = baseState.copy(ownedCharacterIds = setOf("6001"))
        val estimator = BattlePassEstimator { s, _ -> PassEstimate(if ("6002" in s.ownedCharacterIds) .75 else .5, 1.0) }
        val results = evaluateRoleChoices(state, listOf("6001", "6003", "6002"), listOf(boss), estimator)
        assertEquals(listOf("6002", "6003", "6001"), results.map { it.characterId })
        assertEquals(RoleChoiceEval("6002", 950.0, 750.0, 200.0, .5, .75, true), results.first())
        assertEquals(0.0, results.last().totalScore, 0.0)
        val incomplete = BattlePassEstimator { s, _ -> if ("6002" in s.ownedCharacterIds) PassEstimate(.75, 1.0) else null }
        val fallback = evaluateRoleChoices(state, listOf("6002"), listOf(boss), incomplete).single()
        assertFalse(fallback.usedModel)
        assertEquals(200.0, fallback.totalScore, 0.0)
    }

    @Test fun relic40003UnlocksTheNextPurchaseAndDuplicatesAwardNothing() {
        val state = baseState.copy(alphaRupee = 1170, remainingShops = 1)
        val results = evaluateRelicChoices(state, listOf(50001, 40003), shelfModel = shelf)
        assertEquals(listOf(40003, 50001), results.map { it.relicId })
        assertEquals(400.0, results.first().collectionDeltaPts, 0.0)
        assertEquals(400.0, results.first().shopDiscountDeltaPts, 0.0)
        assertEquals(800.0, results.first().totalScore, 0.0)
        val mark = LabyrinthRelicMark.ACCELERATION
        val awards = mapOf(101 to RelicMarkAward(mark, 1))
        val awarded = awardCollections(state, relicIds = listOf(101, 101), relicMarks = awards)
        assertEquals(mapOf(mark to 1), awarded.markStacks)
        assertEquals(400L, awarded.confirmedPts)
        assertEquals(0.0, evaluateRelicChoices(awarded, listOf(101), relicMarks = awards).single().totalScore, 0.0)
        val markModel = BattlePassEstimator { s, _ -> PassEstimate(if (s.markStacks[mark] == 4) .75 else .5, 1.0) }
        val boosted = evaluateRelicChoices(state.copy(markStacks = mapOf(mark to 3)), listOf(101),
            listOf(boss), markModel, relicMarks = awards).single()
        assertEquals(750.0, boosted.markSynergyPts, 0.0)
        assertEquals(1150.0, boosted.totalScore, 0.0)
    }

    @Test fun reviveIsSharedAcrossNonBossExamsAndDoesNotRetryABoss() {
        val estimator = BattlePassEstimator { _, _ -> PassEstimate(.5, 1.0) }
        val normal = BattleExam(1, 2, isBoss = false)
        val state = awardCollections(baseState, relicIds = listOf(REVIVE_RELIC_ID))
        assertEquals(.375, passProfile(state, listOf(normal, boss), estimator)!!.pPass, 0.0)
        assertEquals(.5, passProfile(state, listOf(normal, normal), estimator)!!.pPass, 0.0)
        assertEquals(2.25, passProfile(state, listOf(normal, boss), estimator)!!.expectedTeams, 0.0)
        assertEquals(0.0, evaluateRelicChoices(baseState, listOf(REVIVE_RELIC_ID), listOf(boss), estimator)
            .single().battleDeltaPts, 0.0)
        assertEquals(50.0, evaluateRelicChoices(baseState, listOf(REVIVE_RELIC_ID), listOf(normal), estimator)
            .single().battleDeltaPts, 0.0)
    }

    @Test fun eventPrincipalChangesRankingAndAffordabilityStillApplies() {
        val options = defaultEventTree().getValue(3316)
        val state = baseState.copy(alphaRupee = 4000, confirmedPts = 50_100, remainingShops = 1)
        fun rank(s: PlanningState, deduct: Boolean = true, target: Long = 0) = evaluateEventOptions(
            s, options, deductConditionPrincipal = deduct, shopCurve = ::slotCurve, targetScore = target, lambdaRate = .01,
        )
        assertEquals(33162, rank(state).first().choiceId)
        assertEquals(33161, rank(state, deduct = false).first().choiceId)
        assertEquals(listOf(500, 8000), rank(state).last().outcomes.mapNotNull { it.state.alphaRupee }.sorted())
        val behind = rank(state, target = 52_500).first()
        assertEquals(33161, behind.choiceId)
        assertEquals(.5, behind.probReachTarget, 0.0)
        assertEquals(.49, behind.totalScore, 0.0)
        assertEquals(33162, rank(state.copy(confirmedPts = 50_500), target = 52_500).first().choiceId)
        assertEquals(listOf(33162), rank(state.copy(alphaRupee = 3999), deduct = false).map { it.choiceId })
    }

    @Test fun eventContinuationsAndThresholdUseOutcomeDistributions() {
        val tree = defaultEventTree()
        val state = baseState.copy(alphaRupee = 0, confirmedPts = 50_100, targetScore = 52_500, remainingShops = 1)
        val play = evaluateEventOptions(state, tree.getValue(1316), eventTree = tree, shopCurve = ::slotCurve).single()
        assertEquals(.125, play.probReachTarget, 0.0)
        assertEquals(1.75, play.expectedTimeSec, 0.0)
        assertEquals(listOf(.125, .125, .25, .5), play.outcomes.map { it.probability }.sorted())
        assertTrue(play.outcomes.any { it.choices == listOf(13161, 23161, 33161) && it.state.alphaRupee == 8000 })
        val options = listOf(EventOptionSpec(1), EventOptionSpec(2, listOf(EventBranchSpec(goldDelta = 1))))
        val results = evaluateEventOptions(
            state.copy(confirmedPts = 52_100), options,
            shopDistributionFn = { if (it.alphaRupee == 0) mapOf(200L to 1.0) else mapOf(0L to .75, 800L to .25) },
            futureScoreDistribution = mapOf(0L to .5, 200L to .5),
        )
        assertEquals(listOf(1 to .5, 2 to .25), results.map { it.choiceId to it.probReachTarget })
    }

    @Test fun modelUsesBoundedDisjointTeamsWithVanguardOnlyOnTheLead() {
        val profiles = profiles()
        val calls = mutableListOf<List<List<Int>>>()
        val lead = listOf(600101, 601501, 601601, 601701, 601801)
        val second = (601001..601401 step 100).toList()
        val state = baseState.copy(
            ownedCharacterIds = profiles.keys, relicIds = setOf(40003, 50002),
            markStacks = mapOf(LabyrinthRelicMark.DEFENSE to 4), alphaRupee = 2300, reviveAvailable = true,
        )
        val model = object : ContextualBattleValueEstimator {
            override fun unitAffinities(context: BattleValueContext) = context.ownedUnits.associate { it.unitId to it.unitId.toDouble() }

            override fun estimateWithSource(context: BattleValueContext, candidates: List<List<Int>>): BattleValueEstimate {
                assertEquals(boss.questId, context.questId)
                assertEquals(3, context.area)
                assertEquals(5, context.difficulty)
                assertEquals(listOf(40003, 50002), context.relicIds)
                assertEquals(listOf(0, 0, 0, 0, 4), context.markStacks)
                assertEquals(2300, context.alphaRupee)
                assertTrue(context.reviveAvailable)
                assertTrue(candidates.all { it.size == 5 && it.distinct().size == 5 })
                if (calls.isEmpty()) {
                    assertTrue(candidates.all { 600101 in it && 600201 !in it })
                }
                calls += candidates
                return BattleValueEstimate(candidates.map { team ->
                    value(when (team) {
                        lead -> .6
                        second -> .5
                        else -> .25
                    })
                }, BattleValueSource.MODEL)
            }
        }
        val estimate = ModelBattlePassEstimator(model, profiles).estimate(state, boss)!!
        assertEquals(listOf(.6, .5, .25), estimate.teamWinProbs)
        assertEquals(.85, estimate.pPass, 1e-12)
        assertEquals(1.6, estimate.expectedTeams, 1e-12)
        assertTrue(calls.flatten().size <= 48)
        assertEquals(calls.flatten().size, calls.flatten().distinct().size)
        assertTrue(calls[1].all { team -> team.none { it in lead } })
        assertTrue(calls[2].all { team -> team.none { it in lead || it in second } })
    }

    @Test fun modelBossPassIncludesDamageFromDisjointTeams() {
        val profiles = profiles().filterKeys { it == "6001" || it.toInt() in 6003..6016 }
        val state = baseState.copy(ownedCharacterIds = profiles.keys)
        val teams = profiles.keys.sorted().map { it.toInt() * 100 + 1 }.chunked(5)
        val damage = teams.zip(listOf(.55, .50, .45)).toMap()
        val model = object : ContextualBattleValueEstimator {
            override fun estimateWithSource(context: BattleValueContext, candidates: List<List<Int>>) =
                BattleValueEstimate(candidates.map { value(.05, damage.getOrDefault(it, .10)) }, BattleValueSource.MODEL)
        }

        val estimate = ModelBattlePassEstimator(model, profiles).estimate(state, boss)!!
        val independentPass = 1.0 - .95 * .95 * .95
        assertEquals(List(3) { .05 }, estimate.teamWinProbs)
        assertTrue("pPass=${estimate.pPass}, independentPass=$independentPass", estimate.pPass > independentPass + .50)
    }

    @Test fun modelCanChooseDamageAndRuleScoreSeedsWithoutALeadVanguard() {
        val profiles = (6001..6016).associate { index ->
            val id = index.toString()
            val magic = index % 2 == 1
            val damage = if (index == 6014) 25.0 else 10.0
            id to LabyrinthRoleProfile(
                id, id, position = index,
                userScore = 90.0.takeIf { index in 6010..6013 },
                physicalDamagePotential = damage.takeUnless { magic },
                magicDamagePotential = damage.takeIf { magic },
            )
        }
        val state = baseState.copy(ownedCharacterIds = profiles.keys)
        val exam = boss.copy(maxTeams = 1)
        val strongTeams = listOf(
            listOf(6010, 6011, 6012, 6013, 6014),
            listOf(6001, 6003, 6005, 6007, 6009),
            listOf(6002, 6004, 6006, 6008, 6010),
            listOf(6001, 6003, 6005, 6011, 6013),
            listOf(6002, 6004, 6010, 6012, 6014),
        )
        for (strongTeam in strongTeams) {
            val target = strongTeam.map { it * 100 + 1 }
            val model = object : ContextualBattleValueEstimator {
                override fun estimateWithSource(context: BattleValueContext, candidates: List<List<Int>>): BattleValueEstimate {
                    assertFalse(context.ruleContext.frontlineSurvivalGateEnabled)
                    return BattleValueEstimate(candidates.map { value(if (it == target) .95 else .05) }, BattleValueSource.MODEL)
                }
            }
            assertNull(ModelBattlePassEstimator(model, profiles).estimate(state, exam))
            val estimate = ModelBattlePassEstimator(model, profiles, requireLeadVanguard = false).estimate(state, exam)!!
            assertEquals("team=$strongTeam", .95, estimate.pPass, 1e-12)
        }
    }

    @Test fun modelRuleFallbackAndMissingProfilesReturnNull() {
        val profiles = profiles()
        var calls = 0
        val fallback = object : ContextualBattleValueEstimator {
            override fun estimateWithSource(context: BattleValueContext, candidates: List<List<Int>>): BattleValueEstimate {
                calls++
                return BattleValueEstimate(candidates.map { value(.99) }, BattleValueSource.RULE)
            }
        }
        val bridge = ModelBattlePassEstimator(fallback, profiles)
        assertNull(bridge.estimate(baseState.copy(ownedCharacterIds = profiles.keys.take(4).toSet()), boss))
        assertNull(bridge.estimate(baseState.copy(ownedCharacterIds = profiles.keys + "9999"), boss))
        assertNull(bridge.estimate(baseState.copy(ownedCharacterIds = profiles.keys - "6001"), boss))
        assertEquals(0, calls)
        assertNull(bridge.estimate(baseState.copy(ownedCharacterIds = profiles.keys), boss))
        assertEquals(1, calls)
    }

    @Test fun routeContextUsesCurrentAreaAndFingerprintsPlanningInputs() {
        fun node(area: Int, id: Long, type: Int, questId: Int? = null) =
            LabyrinthNodeJson(area, 1, 1, id, type, questId, emptyList(), false)
        val map = listOf(node(2, 2, Node.SHOP), node(3, 3, Node.BOSS, boss.questId),
            node(3, 4, Node.SHOP), node(4, 5, Node.BOSS, 123), node(5, 6, Node.BOSS),
            node(5, 7, Node.BOSS, 77050101), node(5, 8, Node.SHOP))
        val route = LabyrinthRouteJson(
            100, 1, 7, 1, false, false, "", emptyList(), emptyList(), map, map,
            currentBlockId = 3, openingRelics = LabyrinthOpeningRelicState(1, setOf(21)),
        )
        fun context(route: LabyrinthRouteJson, area: Int? = null, tag: String = "model") = LabyrinthRunPlanningContext.fromRoute(
            true, route, currentArea = area, observedRelicIds = setOf(50002, 40003),
            markStacks = mapOf(LabyrinthRelicMark.DEFENSE to 4), reviveAvailable = true, alphaRupee = 1230,
            passEstimator = null, estimatorTag = tag,
        )
        val result = context(route)
        assertEquals(7, result.difficulty)
        assertEquals(3, result.currentArea)
        assertEquals(listOf(boss, BattleExam(77050101, 5, isBoss = true)), result.upcomingExams)
        assertEquals(2, result.remainingShops)
        assertTrue(result.enhanceNode21)
        assertEquals(result.cacheFingerprint, context(route.copy(allNodes = map.reversed())).cacheFingerprint)
        assertEquals(result.cacheFingerprint, context(route.copy(allNodes = emptyList())).cacheFingerprint)
        assertNotEquals(result.cacheFingerprint, context(route, tag = "other").cacheFingerprint)
        assertEquals(listOf(5), context(route, area = 4).upcomingExams.map { it.area })
    }

    @Test fun liveCalibratedShelvesMatchEmpiricalArchetypesAndSymmetryBreakingIsExact() {
        val calibrated = ShopShelfModel.liveCalibrated()
        assertTrue(calibrated.shelves.size in 7..10)
        assertEquals(1.0, calibrated.shelves.sumOf { it.first }, 1e-12)
        assertTrue(calibrated.shelves.all { (_, items) -> items.size == 6 })

        fun marginalShare(model: ShopShelfModel, kind: ShopItemKind): Double =
            model.shelves.sumOf { (p, items) ->
                val relicCount = items.count { it.isRelic }.coerceAtLeast(1)
                p * items.count { it.kind == kind } / relicCount.toDouble()
            }

        assertTrue(marginalShare(calibrated, ShopItemKind.RELIC_1STAR) in 0.57..0.62)
        assertTrue(marginalShare(calibrated, ShopItemKind.RELIC_2STAR) in 0.32..0.37)
        assertTrue(marginalShare(calibrated, ShopItemKind.RELIC_3STAR) in 0.05..0.07)

        // Weighted without-replacement pool depletion: A5-level 1★ ownership reduces future 1★ offer share.
        val area3Owned = setOf(11001, 11002, 11003, 11004, 21001, 21002, 31001, 40001, 50001)
        val area5Owned = (11001..11010).toSet() + setOf(21001, 21002, 21003, 21004, 31001, 31002, 40001, 31006)
        val a3Model = ShopShelfModel.forOwnedRelics(area3Owned)
        val a5Model = ShopShelfModel.forOwnedRelics(area5Owned)
        assertTrue(marginalShare(a3Model, ShopItemKind.RELIC_1STAR) > marginalShare(a5Model, ShopItemKind.RELIC_1STAR))
        assertTrue(marginalShare(a5Model, ShopItemKind.RELIC_1STAR) in 0.53..0.60)

        // Hard pool depletion bound: when only 1 unowned 1★ relic remains, no shelf may contain >=2 1★ relics.
        val nearExhausted = ShopShelfModel.forRemainingRelicPool(rem1 = 1, rem2 = 5, rem3 = 5)
        assertTrue(nearExhausted.shelves.all { (_, items) -> items.count { it.kind == ShopItemKind.RELIC_1STAR } <= 1 })

        // Compare against identical shelves where unique relicIds disable symmetry breaking -> must be exact!
        val noSymBreak = ShopShelfModel(calibrated.shelves.mapIndexed { sIdx, (p, items) ->
            p to items.mapIndexed { iIdx, item -> item.copy(relicId = sIdx * 10 + iIdx + 1) }
        })
        val actual = shopScoreDistribution(5000, 1, official, 5, discount, calibrated, maxRefreshes = 1)
        val expected = shopScoreDistribution(5000, 1, official, 5, discount, noSymBreak, maxRefreshes = 1)
        assertEquals(expected.keys, actual.keys)
        for ((pts, p) in expected) {
            assertEquals("pts=$pts", p, actual.getValue(pts), 1e-12)
        }
    }

    @Test fun seedLockingPreventsNonMonotonicPassDropWhenAddingCandidateCharacter() {
        val profiles = profiles()
        val initialOwned = (6001..6005).map { it.toString() }.toSet()
        val strongBefore = (600101..600501 step 100).toList()
        val state = baseState.copy(ownedCharacterIds = initialOwned)
        val calls = mutableListOf<List<List<Int>>>()
        val model = object : ContextualBattleValueEstimator {
            override fun unitAffinities(context: BattleValueContext): Map<Int, Double> =
                context.ownedUnits.associate {
                    // Artificially rank 6001 lowest once 6006 joins so heuristic lead shifts away from strongBefore.
                    it.unitId to if (it.unitId == 600101) -1000.0 else it.unitId.toDouble()
                }

            override fun estimateWithSource(context: BattleValueContext, candidates: List<List<Int>>): BattleValueEstimate {
                calls += candidates
                val values = candidates.map { team ->
                    when (team) {
                        strongBefore -> value(0.85)
                        listOf(600101, 600201, 600301, 600401, 600601) -> value(0.88)
                        else -> value(0.20)
                    }
                }
                return BattleValueEstimate(values, BattleValueSource.MODEL)
            }
        }
        val estimator = ModelBattlePassEstimator(model, profiles, requireLeadVanguard = false)
        val evals = evaluateRoleChoices(state, listOf("6006"), listOf(boss.copy(maxTeams = 1)), estimator)
        val eval = evals.single()
        assertEquals(0.85, eval.beforePassProb, 1e-12)
        assertTrue("afterPassProb=${eval.afterPassProb} dropped below beforePassProb=${eval.beforePassProb}",
            eval.afterPassProb >= eval.beforePassProb)
        assertEquals(0.88, eval.afterPassProb, 1e-12)
        assertTrue(calls.size == 2 && calls.all { it.size <= 48 })
    }

    private fun profiles(): Map<String, LabyrinthRoleProfile> = (6001..6018).associate { index ->
        val id = index.toString()
        val position = if (index == 6001 || index == 6002) 1 else index - 6000
        id to LabyrinthRoleProfile(id, id, position = position,
            functions = LabyrinthRoleFunctions(reliableVanguard = if (index == 6001) 100.0 else null))
    }

    private fun value(p: Double, damageMean: Double = 0.0) = BattleValue(p, 1.0 - p, 0.0, damageMean, 0.0, p)

    private fun slotCurve(state: PlanningState): Double {
        val gold = state.alphaRupee ?: 0
        return minOf(gold, 4000) * .5 + (gold - 4000).coerceIn(0, 4000) * .1
    }
}
