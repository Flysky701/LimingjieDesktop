package com.landosol.toolbox.labyrinth.kernel.planning

import com.landosol.toolbox.labyrinth.LabyrinthRelicMark
import com.landosol.toolbox.labyrinth.node.LabyrinthNodeTypes as Node
import com.landosol.toolbox.protocol.labyrinth.LabyrinthMapNode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Test

class CrossLanguagePathBParityTest {
    private val tol = 1e-9

    private fun vectors(): JsonObject {
        val raw = requireNotNull(javaClass.getResourceAsStream("/labyrinth/path_b_golden_vectors.json"))
            .bufferedReader().use { it.readText() }
        return Json.parseToJsonElement(raw).jsonObject
    }

    @Test fun scoringMatchesPython() {
        val root = vectors().getValue("scoring").jsonObject
        val official = ScoreSettings.currentOfficial()

        val off = root.getValue("official").jsonObject
        val cFull = ScoreCounts(AreaClears.Count(5), BlockClears.Count(45), 35, 15, 5, 5, 5)
        assertEquals(off.getValue("d5_base").jsonPrimitive.long, baseScore(official, cFull))
        assertEquals(off.getValue("d5_final").jsonPrimitive.long, computeFinalScore(official, 5, cFull))
        assertEquals(off.getValue("d1_final").jsonPrimitive.long, computeFinalScore(official, 1, cFull))
        assertEquals(off.getValue("d7_final").jsonPrimitive.long, computeFinalScore(official, 7, cFull))
        assertEquals(off.getValue("d10_final").jsonPrimitive.long, computeFinalScore(official, 10, cFull))
        assertEquals(off.getValue("d5_gcd").jsonPrimitive.long, scoreGcd(official, 5))

        val deltas = off.getValue("d5_deltas").jsonObject
        assertEquals(deltas.getValue("unit").jsonPrimitive.long, unitDeltaPts(official, 5))
        assertEquals(deltas.getValue("relic").jsonPrimitive.long, relicDeltaPts(official, 5))
        assertEquals(deltas.getValue("normal").jsonPrimitive.long, normalWinDeltaPts(official, 5))
        assertEquals(deltas.getValue("ex").jsonPrimitive.long, exWinDeltaPts(official, 5))
        assertEquals(deltas.getValue("boss").jsonPrimitive.long, bossWinDeltaPts(official, 5))
        assertEquals(deltas.getValue("block3").jsonPrimitive.long, blockDeltaPts(official, 5, 3))
        assertEquals(deltas.getValue("area3").jsonPrimitive.long, areaClearDeltaPts(official, 5, 3))
        assertEquals(
            deltas.getValue("ex_vs_normal_extra").jsonPrimitive.long,
            deltaExVsNormalPts(official, 5, extraRelics = 1, extraUnits = -1),
        )

        val before = ScoreCounts(AreaClears.Count(4), BlockClears.Count(44), 30, 15, 5, 4, 4)
        val gPass = scoreCheckGate(official, 5, before, 50_000)
        val passVec = off.getValue("gate_pass").jsonObject
        assertEquals(passVec.getValue("current").jsonPrimitive.long, gPass.currentScore)
        assertEquals(passVec.getValue("projected").jsonPrimitive.long, gPass.projectedClearScore)
        assertEquals(passVec.getValue("shortfall").jsonPrimitive.long, gPass.shortfall)
        assertEquals(passVec.getValue("should_retire").jsonPrimitive.boolean, gPass.shouldRetire)

        val gFail = scoreCheckGate(official, 5, before.copy(areasCleared = AreaClears.Ids((1..5).toSet())), 53_000)
        val failVec = off.getValue("gate_fail").jsonObject
        assertEquals(failVec.getValue("current").jsonPrimitive.long, gFail.currentScore)
        assertEquals(failVec.getValue("projected").jsonPrimitive.long, gFail.projectedClearScore)
        assertEquals(failVec.getValue("shortfall").jsonPrimitive.long, gFail.shortfall)
        assertEquals(failVec.getValue("should_retire").jsonPrimitive.boolean, gFail.shouldRetire)

        val nonuniform = official.copy(
            areaClearPts = (1..10).associateWith { 10 * it },
            blockPts = (1..10).associateWith { it },
            scoreRates = mapOf(1 to 100, 5 to 200, 7 to 135, 10 to 300),
        )
        val nuCounts = ScoreCounts(AreaClears.Count(10), BlockClears.ByArea(mapOf(1 to 2, 10 to 3)), units = 2, relics = 1)
        val nu = root.getValue("nonuniform").jsonObject
        assertEquals(nu.getValue("base").jsonPrimitive.long, baseScore(nonuniform, nuCounts))
        assertEquals(nu.getValue("final").jsonPrimitive.long, computeFinalScore(nonuniform, 7, nuCounts))
        assertEquals(nu.getValue("gcd").jsonPrimitive.long, scoreGcd(nonuniform, 7))
        assertEquals(nu.getValue("negative_floor").jsonPrimitive.long, scalePts(nonuniform, 7, -1))

        val revive = root.getValue("revive").jsonObject
        assertEquals(
            revive.getValue("can_prevent_non_boss").jsonPrimitive.boolean,
            ReviveState.fromRelics(listOf(REVIVE_RELIC_ID)).canPreventDefeat(false),
        )
        assertEquals(
            revive.getValue("can_prevent_boss").jsonPrimitive.boolean,
            ReviveState.fromRelics(listOf(REVIVE_RELIC_ID)).canPreventDefeat(true),
        )
    }

    @Test fun shopDpMatchesPython() {
        val root = vectors().getValue("shop_dp").jsonObject
        val official = ScoreSettings.currentOfficial()
        val disc35 = ShopDiscountModel(enhanceNode21 = true, relic40003 = true, extraDiscountPct = 0)
        val discCapped = ShopDiscountModel(enhanceNode21 = true, relic40003 = true, extraDiscountPct = 80)

        assertEquals(
            root.getValue("discount_35_prices").jsonArray.map { it.jsonPrimitive.int },
            listOf(1200, 1800, 2800, 3800, 9999).map(disc35::price),
        )
        assertEquals(
            root.getValue("discount_capped_prices").jsonArray.map { it.jsonPrimitive.int },
            listOf(1200, 1800, 2800, 9999).map(discCapped::price),
        )

        val shelfItems = listOf(
            ShopItem("301", ShopItemKind.RELIC_3STAR, 3800, relicId = 301),
            ShopItem("imp", ShopItemKind.ROLE_IMPRINT, 1200),
            ShopItem("101", ShopItemKind.RELIC_1STAR, 1800, relicId = 101),
        )
        fun pythonKindName(kind: ShopItemKind): String = when (kind) {
            ShopItemKind.ROLE_IMPRINT -> "RANDOM_IMPRINT"
            ShopItemKind.ROLE_CHOICE_IMPRINT -> "CHOICE_IMPRINT"
            else -> kind.name
        }
        assertEquals(
            root.getValue("clearance_ladder_kinds").jsonArray.map { it.jsonPrimitive.content },
            clearanceLadder(shelfItems, 1950, official, 5, disc35).map { pythonKindName(it.kind) },
        )

        val singleCheap = ShopShelfModel(listOf(1.0 to listOf(ShopItem("1star", ShopItemKind.RELIC_1STAR, 1800))))
        val mixedModel = ShopShelfModel(
            listOf(
                0.6 to listOf(
                    ShopItem("rand6001", ShopItemKind.ROLE_IMPRINT, 1200, characterId = "6001"),
                    ShopItem("1star", ShopItemKind.RELIC_1STAR, 1800),
                ),
                0.4 to listOf(
                    ShopItem("choice", ShopItemKind.ROLE_CHOICE_IMPRINT, 2800),
                    ShopItem("2star", ShopItemKind.RELIC_2STAR, 2800, extraBattlePts = 120.0),
                ),
            ),
        )

        fun assertShopEval(vecKey: String, actual: ShopPlanEvaluation) {
            val vec = root.getValue(vecKey).jsonObject
            assertEquals(vec.getValue("buy_indices").jsonArray.map { it.jsonPrimitive.int }, actual.plan.buyIndices)
            assertEquals(vec.getValue("should_refresh").jsonPrimitive.boolean, actual.plan.shouldRefresh)
            vec["refresh_cost"]?.jsonPrimitive?.int?.let { assertEquals(it, actual.refreshCost) }
            assertEquals(vec.getValue("expected_pts").jsonPrimitive.double, actual.plan.expectedPts, tol)
            assertEquals(vec.getValue("expected_value").jsonPrimitive.double, actual.expectedValue, tol)
            assertEquals(vec.getValue("remaining_gold").jsonPrimitive.int, actual.plan.remainingGold)
        }

        assertShopEval(
            "plan_visible",
            evaluateShopVisit(
                listOf(
                    ShopItem("p", ShopItemKind.RELIC_2STAR, 2800, extraBattlePts = 310.0),
                    ShopItem("c1", ShopItemKind.RELIC_1STAR, 1800),
                    ShopItem("c2", ShopItemKind.RELIC_1STAR, 1800),
                ),
                2340, official, 5, disc35, singleCheap, isFinalArea = true,
            ),
        )
        assertShopEval(
            "plan_refresh",
            evaluateShopVisit(emptyList(), 3240, official, 5, disc35, singleCheap, refreshCount = 0, maxRefreshes = 2),
        )
        assertShopEval(
            "plan_retain",
            evaluateShopVisit(
                listOf(ShopItem("c", ShopItemKind.RELIC_1STAR, 1800)), 11170, official, 5, disc35,
                maxRefreshes = 0, coinRetentionValueFn = { minOf(10000, it).toDouble() },
            ),
        )
        assertShopEval(
            "plan_mixed",
            evaluateShopVisit(
                listOf(
                    ShopItem("rand6001", ShopItemKind.ROLE_IMPRINT, 1200, characterId = "6001"),
                    ShopItem("2star", ShopItemKind.RELIC_2STAR, 2800, extraBattlePts = 50.0),
                    ShopItem("4star", ShopItemKind.RELIC_4STAR, 9999),
                ),
                4500, official, 7, disc35, mixedModel,
                refreshCount = 1, maxRefreshes = 2, remainingShops = 2, boughtMask = 1,
                ownedCharacterIds = setOf("6002"), scoreRemainder = 40,
            ),
        )

        assertEquals(
            root.getValue("gold_curve_d7_rem").jsonPrimitive.double,
            goldToPtsCurve(3600, 2, official, 7, disc35, mixedModel, maxRefreshes = 1, ownedCharacterIds = setOf("6001"), scoreRemainder = 40),
            tol,
        )
        val distExpected = root.getValue("dist_default_1800").jsonObject
        val distActual = shopScoreDistribution(1800, 1)
        assertEquals(distExpected.size, distActual.size)
        for ((k, v) in distExpected) assertEquals(v.jsonPrimitive.double, distActual.getValue(k.toLong()), tol)

        assertEquals(
            root.getValue("marginal_gold_mixed").jsonPrimitive.double,
            marginalGoldPts(2000, 1200, 2, official, 5, disc35, mixedModel, maxRefreshes = 1),
            tol,
        )
    }

    @Test fun routeDpMatchesPython() {
        val root = vectors().getValue("route_dp").jsonObject
        val official = ScoreSettings.currentOfficial()

        val nodesFork = listOf(
            LabyrinthMapNode(1, 1, 0, 1L, Node.START, null, listOf(2L, 3L), false),
            LabyrinthMapNode(1, 2, 0, 2L, Node.NORMAL_BATTLE, null, listOf(4L), false),
            LabyrinthMapNode(1, 2, 1, 3L, Node.EX_BATTLE, null, listOf(4L), false),
            LabyrinthMapNode(1, 3, 0, 4L, Node.BOSS, 77030101, emptyList(), true),
        ).toPlanningRouteGraph()
        val specsFork = mapOf(
            1L to NodeOutcomeSpec(mapOf(0L to 0.5, 400L to 0.5), winProb = 1.0, expectedTimeSec = 1.0),
            2L to NodeOutcomeSpec(600L, winProb = 1.0, expectedTimeSec = 1.0, goldDelta = 200),
            3L to NodeOutcomeSpec(1000L, winProb = 0.5, expectedTimeSec = 1.0),
            4L to NodeOutcomeSpec(1000L, winProb = 0.8, expectedTimeSec = 5.0, failTimeSec = 7.0),
        )
        val dpFork = solveRouteDp(nodesFork, official, 5, specsFork, targetScore = 1000, lambdaRate = 0.01, reviveAvailable = true)
        val fVec = root.getValue("dp_fork").jsonObject
        assertEquals(fVec.getValue("best_route").jsonArray.map { it.jsonPrimitive.long }, dpFork.bestRoute.map { it.blockId })
        assertEquals(fVec.getValue("expected_score").jsonPrimitive.double, dpFork.expectedScore, tol)
        assertEquals(fVec.getValue("clear_prob").jsonPrimitive.double, dpFork.clearProb, tol)
        assertEquals(fVec.getValue("prob_reach_target").jsonPrimitive.double, dpFork.probReachTarget, tol)
        assertEquals(fVec.getValue("expected_time_sec").jsonPrimitive.double, dpFork.expectedTimeSec, tol)
        assertEquals(fVec.getValue("hard_upper_bound").jsonPrimitive.long, dpFork.hardUpperBound)
        assertEquals(fVec.getValue("num_buckets").jsonPrimitive.long, dpFork.numBuckets)
        assertEquals(fVec.getValue("bucket_size").jsonPrimitive.double, dpFork.bucketSize, tol)
        for ((k, v) in fVec.getValue("distribution").jsonObject) {
            assertEquals(v.jsonPrimitive.double, dpFork.scoreDistribution.getValue(k.toLong()), tol)
        }

        val nodesWallet = listOf(
            LabyrinthMapNode(1, 1, 0, 1L, Node.START, null, listOf(2L), false),
            LabyrinthMapNode(1, 2, 0, 2L, Node.SHOP, null, listOf(3L), false),
            LabyrinthMapNode(1, 3, 0, 3L, Node.BOSS, null, emptyList(), true),
        ).toPlanningRouteGraph()
        val walletModel: WalletModel = { node, _, _, _, gold ->
            if (node.blockType == Node.SHOP && gold >= 1200) {
                listOf(
                    WalletOutcome(200L, gold - 1200, 0.75, 2.0),
                    WalletOutcome(100L, gold - 1000, 0.25, 1.0),
                )
            } else {
                listOf(WalletOutcome(0L, gold, 1.0, if (node.blockType == Node.SHOP) 0.5 else 0.0))
            }
        }
        val dpWallet = solveRouteDp(
            nodesWallet, official, 5,
            mapOf(
                1L to NodeOutcomeSpec(200L, expectedTimeSec = 1.0, goldDelta = 1200),
                2L to NodeOutcomeSpec(200L, expectedTimeSec = 1.0),
                3L to NodeOutcomeSpec(1000L, expectedTimeSec = 10.0),
            ),
            targetScore = 1600, lambdaRate = 0.005, reviveAvailable = false, confirmedPts = 400, scoreRemainder = 50,
            walletModel = walletModel, initialGold = 100,
        )
        val wVec = root.getValue("dp_wallet").jsonObject
        assertEquals(wVec.getValue("best_route").jsonArray.map { it.jsonPrimitive.long }, dpWallet.bestRoute.map { it.blockId })
        assertEquals(wVec.getValue("expected_score").jsonPrimitive.double, dpWallet.expectedScore, tol)
        assertEquals(wVec.getValue("clear_prob").jsonPrimitive.double, dpWallet.clearProb, tol)
        assertEquals(wVec.getValue("prob_reach_target").jsonPrimitive.double, dpWallet.probReachTarget, tol)
        assertEquals(wVec.getValue("expected_time_sec").jsonPrimitive.double, dpWallet.expectedTimeSec, tol)
        assertEquals(wVec.getValue("hard_upper_bound").jsonPrimitive.long, dpWallet.hardUpperBound)
        for ((k, v) in wVec.getValue("distribution").jsonObject) {
            assertEquals(v.jsonPrimitive.double, dpWallet.scoreDistribution.getValue(k.toLong()), tol)
        }

        val opening = scoreOpeningMap(
            nodesFork, official, 7, specsFork, targetScore = 1800, lambdaRate = 0.20,
            restartTimeSec = 3.25, reviveAvailable = true, confirmedPts = 400, scoreRemainder = 75,
        )
        val oVec = root.getValue("opening_frac").jsonObject
        assertEquals(oVec.getValue("expected_score").jsonPrimitive.double, opening.route.expectedScore, tol)
        assertEquals(oVec.getValue("prob_reach_target").jsonPrimitive.double, opening.route.probReachTarget, tol)
        assertEquals(oVec.getValue("expected_time_sec").jsonPrimitive.double, opening.route.expectedTimeSec, tol)
        assertEquals(oVec.getValue("should_reroll").jsonPrimitive.boolean, opening.shouldReroll)
        assertEquals(oVec.getValue("attempt_time_sec").jsonPrimitive.double, opening.attemptTimeSec, tol)

        val early = evaluateEarlyStop(400, 1000, 1800, 0.9, 4.5, 0.20345)
        val eVec = root.getValue("early_stop").jsonObject
        assertEquals(eVec.getValue("should_retire").jsonPrimitive.boolean, early.shouldRetire)
        assertEquals(eVec.getValue("reason").jsonPrimitive.content, early.reason)
    }

    @Test fun marginalEvalMatchesPython() {
        val root = vectors().getValue("marginal_eval").jsonObject
        val official = ScoreSettings.currentOfficial()
        val singleCheap = ShopShelfModel(listOf(1.0 to listOf(ShopItem("1star", ShopItemKind.RELIC_1STAR, 1800))))
        val stub = BattlePassEstimator { state, exam ->
            var bonus = if ("6002" in state.ownedCharacterIds) 0.25 else 0.0
            if ((state.markStacks[LabyrinthRelicMark.ACCELERATION] ?: 0) >= 4) bonus += 0.15
            if (exam.isBoss && 50002 in state.relicIds) bonus += 0.05
            val p = minOf(0.95, 0.45 + bonus + if (!exam.isBoss) 0.10 else 0.0)
            PassEstimate(p, 1.0)
        }
        val exams = listOf(
            BattleExam(1, 1, isBoss = false, expectedTimeSec = 12.0),
            BattleExam(77030101, 3, isBoss = true, expectedTimeSec = 40.0),
        )
        val state = PlanningState(
            difficulty = 5, currentArea = 1, ownedCharacterIds = setOf("6001"),
            relicIds = emptySet(), markStacks = mapOf(LabyrinthRelicMark.ACCELERATION to 3),
            remainingShops = 1, enhanceNode21 = true, reviveAvailable = false,
            alphaRupee = 1170, confirmedPts = 50_100, scoreRemainder = 0,
        )

        val profile = requireNotNull(passProfile(state.copy(relicIds = setOf(50002), reviveAvailable = true), exams, stub))
        val pVec = root.getValue("pass_profile_with_revive").jsonObject
        val pCond = pVec.getValue("conditional").jsonArray.map { it.jsonPrimitive.double }
        assertEquals(pCond.size, profile.conditionalPassProbs.size)
        for (i in pCond.indices) assertEquals(pCond[i], profile.conditionalPassProbs[i], tol)
        assertEquals(pVec.getValue("p_pass").jsonPrimitive.double, profile.pPass, tol)
        assertEquals(pVec.getValue("expected_time_sec").jsonPrimitive.double, profile.expectedTimeSec, tol)

        val roles = evaluateRoleChoices(state, listOf("6001", "6002", "6003"), exams, stub, official, singleCheap)
        val rolesVec = root.getValue("roles").jsonArray.map { it.jsonObject }
        assertEquals(rolesVec.size, roles.size)
        for ((exp, act) in rolesVec.zip(roles)) {
            assertEquals(exp.getValue("id").jsonPrimitive.int, act.characterId.toInt())
            assertEquals(exp.getValue("value").jsonPrimitive.double, act.totalScore, tol)
            assertEquals(exp.getValue("direct").jsonPrimitive.double, act.collectionDeltaPts, tol)
            assertEquals(exp.getValue("battle").jsonPrimitive.double, act.battleDeltaPts, tol)
        }

        val marks = mapOf(
            101 to RelicMarkAward(LabyrinthRelicMark.ACCELERATION, 1),
            40003 to RelicMarkAward(LabyrinthRelicMark.CRITICAL, 1),
            50002 to RelicMarkAward(LabyrinthRelicMark.DEBUFF, 1),
        )
        val relics = evaluateRelicChoices(state, listOf(101, 40003, 50002), exams, stub, official, singleCheap, marks)
        val relicsVec = root.getValue("relics").jsonArray.map { it.jsonObject }
        assertEquals(relicsVec.size, relics.size)
        for ((exp, act) in relicsVec.zip(relics)) {
            assertEquals(exp.getValue("id").jsonPrimitive.int, act.relicId)
            assertEquals(exp.getValue("value").jsonPrimitive.double, act.totalScore, tol)
            assertEquals(exp.getValue("direct").jsonPrimitive.double, act.collectionDeltaPts, tol)
            assertEquals(exp.getValue("battle").jsonPrimitive.double, act.battleDeltaPts + act.markSynergyPts, tol)
        }

        val tree = defaultEventTree()
        val evState = state.copy(alphaRupee = 4000)
        val evDeduct = evaluateEventOptions(
            evState, tree.getValue(3316), exams, stub, deductConditionPrincipal = true,
            settings = official, shelfModel = singleCheap, futureScoreDistribution = mapOf(0L to 0.5, 400L to 0.5),
            targetScore = 54_500, lambdaRate = 0.002,
        )
        val evNoDeduct = evaluateEventOptions(
            evState, tree.getValue(3316), exams, stub, deductConditionPrincipal = false,
            settings = official, shelfModel = singleCheap, futureScoreDistribution = mapOf(0L to 0.5, 400L to 0.5),
            targetScore = 54_500, lambdaRate = 0.002,
        )
        val evChain = evaluateEventOptions(
            state.copy(alphaRupee = 0), tree.getValue(1316), exams, stub, eventTree = tree,
            settings = official, shelfModel = singleCheap, targetScore = 54_500, lambdaRate = 0.001,
        )

        for ((exp, act) in root.getValue("event_3316_deduct").jsonArray.map { it.jsonObject }.zip(evDeduct)) {
            assertEquals(exp.getValue("choice_id").jsonPrimitive.int, act.choiceId)
            assertEquals(exp.getValue("value").jsonPrimitive.double, act.totalScore, tol)
            assertEquals(exp.getValue("direct").jsonPrimitive.double, act.collectionDeltaPts, tol)
            assertEquals(exp.getValue("shop").jsonPrimitive.double, act.shopDeltaPts, tol)
            assertEquals(exp.getValue("battle").jsonPrimitive.double, act.battleDeltaPts, tol)
            assertEquals(exp.getValue("prob_reach_target").jsonPrimitive.double, act.probReachTarget, tol)
            assertEquals(exp.getValue("expected_time_sec").jsonPrimitive.double, act.expectedTimeSec, tol)
        }
        for ((exp, act) in root.getValue("event_3316_nodeduct").jsonArray.map { it.jsonObject }.zip(evNoDeduct)) {
            assertEquals(exp.getValue("choice_id").jsonPrimitive.int, act.choiceId)
            assertEquals(exp.getValue("value").jsonPrimitive.double, act.totalScore, tol)
            assertEquals(exp.getValue("prob_reach_target").jsonPrimitive.double, act.probReachTarget, tol)
            assertEquals(exp.getValue("expected_time_sec").jsonPrimitive.double, act.expectedTimeSec, tol)
        }
        for ((exp, act) in root.getValue("event_1316_chain").jsonArray.map { it.jsonObject }.zip(evChain)) {
            assertEquals(exp.getValue("choice_id").jsonPrimitive.int, act.choiceId)
            assertEquals(exp.getValue("value").jsonPrimitive.double, act.totalScore, tol)
            assertEquals(exp.getValue("prob_reach_target").jsonPrimitive.double, act.probReachTarget, tol)
            assertEquals(exp.getValue("expected_time_sec").jsonPrimitive.double, act.expectedTimeSec, tol)
            assertEquals(exp.getValue("outcome_count").jsonPrimitive.int, act.outcomes.size)
        }
    }
}
