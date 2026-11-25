package com.landosol.toolbox.labyrinth.kernel.planning

import com.landosol.toolbox.labyrinth.node.LabyrinthNodeTypes as Node
import com.landosol.toolbox.protocol.labyrinth.LabyrinthEnter
import com.landosol.toolbox.protocol.labyrinth.LabyrinthMapNode
import org.junit.Assert.*
import org.junit.Test

class PlanningAndScoringTest {
    private val official = ScoreSettings.currentOfficial()

    @Test fun officialSettlementDeltasAndFinalBossGate() {
        val counts = ScoreCounts(AreaClears.Count(5), BlockClears.Count(45), 30, 15, 5, 4, 5)
        assertEquals(25_000L, baseScore(official, counts))
        assertEquals(50_000L, computeFinalScore(official, 5, counts))
        assertEquals(53_000L, computeFinalScore(official, 5, counts.copy(units = 35, exWins = 5)))
        assertEquals(54_200L, computeFinalScore(official, 5, counts.copy(units = 36, relics = 17, normalWins = 6, exWins = 5)))
        assertEquals(200L, scoreGcd(official, 5))
        val before = counts.copy(areasCleared = AreaClears.Count(4), blocksCleared = BlockClears.Count(44), bossWins = 4)
        val gate = scoreCheckGate(official, 5, before, 50_000)
        assertEquals(45_800L, gate.currentScore)
        assertEquals(50_000L, gate.projectedClearScore)
        assertTrue(gate.passed)
        val below = scoreCheckGate(official, 5, before.copy(areasCleared = AreaClears.Ids((1..5).toSet())), 53_000)
        assertEquals(50_000L, below.projectedClearScore)
        assertEquals(3_000L, below.shortfall)
        assertTrue(below.shouldRetire)

        assertEquals(
            listOf(200L, 400L, 200L, 2_000L, 3_000L, 200L, 1_000L),
            listOf(
                unitDeltaPts(official, 5), relicDeltaPts(official, 5), normalWinDeltaPts(official, 5),
                exWinDeltaPts(official, 5), bossWinDeltaPts(official, 5),
                blockDeltaPts(official, 5, 3), areaClearDeltaPts(official, 5, 3),
            ),
        )
        assertEquals(1_800L, deltaExVsNormalPts(official, 5))
        assertEquals(2_000L, deltaExVsNormalPts(official, 5, extraRelics = 1, extraUnits = -1))
    }

    @Test fun nonuniformFloorPricesAndSignedIntegerFloor() {
        val settings = official.copy(
            areaClearPts = (1..10).associateWith { 10 * it },
            blockPts = (1..10).associateWith { it },
            scoreRates = mapOf(7 to 135),
        )
        val counts = ScoreCounts(AreaClears.Count(10), BlockClears.ByArea(mapOf(1 to 2, 10 to 3)))
        assertEquals(582L, baseScore(settings, counts))
        assertEquals(785L, computeFinalScore(settings, 7, counts))
        assertEquals(1L, scoreGcd(settings, 7))
        assertEquals(
            141L,
            baseScore(settings, ScoreCounts(AreaClears.Ids(setOf(2, 10)), BlockClears.AreaIds(listOf(1, 10, 10)))),
        )
        assertThrows(IllegalArgumentException::class.java) {
            baseScore(settings, ScoreCounts(blocksCleared = BlockClears.Count(3)))
        }
        assertEquals(-2L, scalePts(settings, 7, -1))
        assertEquals(
            "135000000000000000001".toBigInteger(),
            scalePts(settings, 7, "100000000000000000001".toBigInteger()),
        )
    }

    @Test fun oneReviveRetriesOnlyNonBossBattles() {
        val full = ReviveState.fromRelics(listOf(REVIVE_RELIC_ID, REVIVE_RELIC_ID))
        assertTrue(full.canPreventDefeat(isBoss = false))
        assertFalse(full.canPreventDefeat(isBoss = true))
        assertSame(full, full.applyBattleLoss(isBoss = true))
        val used = full.applyBattleLoss(isBoss = false)
        assertEquals(ReviveState.fromRelics(listOf(REVIVE_RELIC_ID), usedCount = 1), used)
        assertSame(used, used.applyBattleLoss(isBoss = false))
        assertFalse(ReviveState.fromRelics(emptyList()).hasRelic)

        val nodes = listOf(node(1, Node.EX_BATTLE, listOf(2)), node(2, Node.BOSS))
        val specs = mapOf(
            1L to NodeOutcomeSpec(200, winProb = .5, expectedTimeSec = 2.0, failTimeSec = 6.0),
            2L to NodeOutcomeSpec(1000, winProb = .5, expectedTimeSec = 4.0),
        )
        val graph = nodes.toPlanningRouteGraph()
        val result = solveRouteDp(graph, official, 5, specs, reviveAvailable = true)
        assertEquals(mapOf(0L to .625, 1200L to .375), result.scoreDistribution)
        assertEquals(9.0, result.expectedTimeSec, 0.0)
        assertEquals(.25, solveRouteDp(graph, official, 5, specs).clearProb, 0.0)
        val twoNonBoss = listOf(nodes.first(), node(2, Node.NORMAL_BATTLE)).toPlanningRouteGraph()
        assertEquals(.5, solveRouteDp(twoNonBoss, official, 5, specs, reviveAvailable = true).clearProb, 0.0)
    }

    @Test fun openingUpperBoundUsesOneSideOfTheFork() {
        val entered = LabyrinthEnter(19, listOf(
            node(1, edges = listOf(2, 3)),
            node(2, Node.NORMAL_BATTLE, listOf(4), column = 2),
            node(3, Node.EX_BATTLE, listOf(4), column = 2),
            node(4, Node.BOSS),
        ))
        val graph = entered.map.toPlanningRouteGraph()
        val result = scoreOpeningMap(graph, official, 5)
        assertEquals(listOf(1L, 3L, 4L), result.route.bestRoute.map { it.blockId })
        assertEquals(6600L, result.route.hardUpperBound)
        assertEquals(6600.0, result.route.expectedScore, 0.0)
        val reject = scoreOpeningMap(graph, official, 5, targetScore = 6800, restartTimeSec = 7.0)
        assertEquals(PlanningDecision.RETIRE, reject.gate.decision)
        assertTrue(reject.shouldReroll)
        assertEquals(7.0, reject.attemptTimeSec, 0.0)
        assertFalse(evaluateEarlyStop(200, 400, 600).shouldRetire)
    }

    @Test fun graphStitchesTenFloorsAndPreservesExplicitEdges() {
        val settings = official.copy(
            areaClearPts = (1..10).associateWith { 3000 },
            blockPts = (1..10).associateWith { 100 },
        )
        val ten = (1..10).map { area -> node(3_000_000_000L + area, Node.BOSS, area = area, column = 1) }
        val result = solveRouteDp(ten.toPlanningRouteGraph(), settings, 5)
        assertEquals((1..10).toList(), result.bestRoute.map { it.area })
        assertEquals(ten.map { it.blockId }, result.bestRoute.map { it.blockId })
        assertEquals(mapOf(92_000L to 1.0), result.scoreDistribution)
        assertEquals(460L, result.numBuckets)
        val explicit = listOf(
            node(1, edges = listOf(3), last = true),
            node(2, area = 2, column = 1),
            node(3, area = 2, column = 1),
        ).toPlanningRouteGraph()
        assertEquals(listOf(3L), explicit.children.getValue(1))
        assertThrows(IllegalArgumentException::class.java) {
            listOf(node(1, edges = listOf(2)), node(2, edges = listOf(1))).toPlanningRouteGraph()
        }
    }

    @Test fun convolvingThreeUniformRewardsKeepsAll301BucketEndpoints() {
        val nodes = listOf(node(1, edges = listOf(2)), node(2, edges = listOf(3)), node(3))
        val pmf = (0L..100L).associate { it * 200 to 1.0 / 101 }
        val specs = nodes.associate { it.blockId to NodeOutcomeSpec(pmf, expectedTimeSec = 1.0) }
        val result = solveRouteDp(nodes.toPlanningRouteGraph(), official, 5, specs)
        assertEquals(200.0, result.bucketSize, 0.0)
        assertEquals(300L, result.numBuckets)
        assertEquals(60_000L, result.hardUpperBound)
        assertEquals(301, result.scoreDistribution.size)
        for (bucket in 0L..300L) {
            val symmetric = minOf(bucket, 300 - bucket)
            val unrestricted = (symmetric + 1) * (symmetric + 2) / 2
            val excess = if (symmetric >= 101) 3 * (symmetric - 100) * (symmetric - 99) / 2 else 0
            assertEquals((unrestricted - excess) / (101.0 * 101 * 101), result.scoreDistribution.getValue(200 * bucket), 1e-13)
        }
        assertEquals(30_000.0, result.expectedScore, 1e-8)
        assertEquals(1.0, result.clearProb, 1e-13)
    }

    @Test fun policyDependsOnObservedScoreAndIncludesWipeMass() {
        val nodes = listOf(node(1, edges = listOf(2, 3)), node(2, column = 2), node(3, column = 2))
        val specs = mapOf(
            1L to NodeOutcomeSpec(mapOf(0L to .5, 400L to .5), expectedTimeSec = 1.0),
            2L to NodeOutcomeSpec(600, expectedTimeSec = 1.0),
            3L to NodeOutcomeSpec(1000, winProb = .5, expectedTimeSec = 1.0),
        )
        val result = solveRouteDp(nodes.toPlanningRouteGraph(), official, 5, specs, targetScore = 1000)
        assertEquals(3L, result.policy.getValue(RoutePolicyKey(1, 0, false)))
        assertEquals(2L, result.policy.getValue(RoutePolicyKey(1, 2, false)))
        assertEquals(mapOf(0L to .25, 1000L to .75), result.scoreDistribution)
        assertEquals(750.0, result.expectedScore, 0.0)
        assertEquals(.75, result.clearProb, 0.0)
        assertEquals(.75, result.probReachTarget, 0.0)
        assertEquals(1400L, result.hardUpperBound)
    }

    @Test fun timePriceAffectsChoiceAndTheGateAcceptsEquality() {
        val graph = listOf(node(1, column = 0), node(2, column = 0)).toPlanningRouteGraph()
        val specs = mapOf(
            1L to NodeOutcomeSpec(1000, winProb = .9, expectedTimeSec = 100.0),
            2L to NodeOutcomeSpec(1000, winProb = .75, expectedTimeSec = 2.0),
        )
        val fast = scoreOpeningMap(graph, official, 5, specs, targetScore = 1000, lambdaRate = .01)
        assertEquals(2L, fast.route.bestRoute.first().blockId)
        assertEquals(2.0, fast.attemptTimeSec, 0.0)
        assertEquals(.75, fast.route.probReachTarget, 0.0)
        assertTrue(scoreOpeningMap(graph, official, 5, specs, targetScore = 1000, lambdaRate = .4).shouldReroll)
        assertFalse(evaluateEarlyStop(0, 1000, 1000, .75, 2.0, .375).shouldRetire)
    }

    @Test fun fractionalRewardsAndPartialScoreShareOneFinalFloor() {
        val settings = official.copy(areaClearPts = mapOf(1 to 0), blockPts = mapOf(1 to 1), scoreRates = mapOf(7 to 125))
        val nodes = listOf(node(1, edges = listOf(2)), node(2, edges = listOf(3)), node(3))
        val four = (nodes.take(2) + node(3, edges = listOf(4)) + node(4)).toPlanningRouteGraph()
        assertEquals(mapOf(5L to 1.0), solveRouteDp(four, settings, 7).scoreDistribution)
        val result = scoreOpeningMap(
            nodes.toPlanningRouteGraph(), settings, 7,
            mapOf(2L to NodeOutcomeSpec(mapOf(0L to .1, 1L to .2, 2L to .7), expectedTimeSec = 2.5)),
            targetScore = 8, lambdaRate = .20345, restartTimeSec = 3.25, confirmedPts = 4, scoreRemainder = 75,
        )
        assertEquals(mapOf(7L to .1, 8L to .2, 9L to .7), result.route.scoreDistribution)
        assertEquals(8.6, result.route.expectedScore, 0.0)
        assertEquals(.8999999999999999, result.route.probReachTarget, 0.0)
        assertEquals(.25, result.route.bucketSize, 0.0)
        assertEquals(9L, result.route.hardUpperBound)
        assertEquals(4.5, result.route.expectedTimeSec, 0.0)
        assertEquals("剩余命中率 0.9 低于时间成本 0.2034 × 4.5 秒", result.gate.reason)
        assertEquals(3.25, result.attemptTimeSec, 0.0)
    }

    private fun node(
        id: Long,
        type: Int = Node.START,
        edges: List<Long> = emptyList(),
        area: Int = 1,
        column: Int = id.toInt(),
        last: Boolean = false,
    ) = LabyrinthMapNode(area, column, 0, id, type, null, edges, last)
}
