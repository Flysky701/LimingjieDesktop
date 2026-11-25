package com.landosol.toolbox.labyrinth.kernel.planning

import com.landosol.toolbox.labyrinth.LabyrinthRoutePolicy
import com.landosol.toolbox.labyrinth.node.LabyrinthNodeTypes as Node
import com.landosol.toolbox.protocol.labyrinth.LabyrinthMapNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScoreTargetGateTest {
    private val official = ScoreSettings.currentOfficial()
    private val areaOneNodeThreeCounts = ScoreCounts(
        blocksCleared = BlockClears.Count(3),
        units = 5,
        relics = 2,
        normalWins = 1,
    )

    @Test
    fun `targetScore zero disables the gate`() {
        val graph = fullStandardFiveAreaMap().toPlanningRouteGraph()
        val evaluation = evaluateOpeningScoreTarget(graph, difficulty = 5, targetScore = 0L, settings = official)
        assertFalse(evaluation.shouldRetire)
        assertEquals(PlanningDecision.ACCEPT, evaluation.gate.decision)
    }

    @Test
    fun `standard five-area six-EX difficulty five map passes reachable target and rejects impossible target`() {
        val graph = fullStandardFiveAreaMap().toPlanningRouteGraph()
        val reachable = evaluateOpeningScoreTarget(
            graph = graph,
            difficulty = 5,
            targetScore = 50_000L,
            settings = official,
            guildId = 1,
            enhanceNodeIds = setOf(21, 31),
        )
        assertFalse(reachable.gate.reason, reachable.shouldRetire)
        assertTrue("expectedScore=${reachable.expectedScore}", reachable.expectedScore in 40_000.0..54_000.0)
        assertTrue("hardUpperBound=${reachable.hardUpperBound}", reachable.hardUpperBound >= 55_000L)

        val impossible = evaluateOpeningScoreTarget(
            graph = graph,
            difficulty = 5,
            targetScore = 70_000L,
            settings = official,
            guildId = 1,
            enhanceNodeIds = setOf(21, 31),
        )
        assertTrue(impossible.shouldRetire)
        assertTrue(impossible.gate.reason.contains("低于目标"))
    }

    @Test
    fun `weak three-EX opening fails target 50000 probability gate`() {
        val weakNodes = fullStandardFiveAreaMap(exCountInArea = mapOf(2 to 1, 3 to 1, 5 to 1))
        val graph = weakNodes.toPlanningRouteGraph()
        val evaluation = evaluateOpeningScoreTarget(
            graph = graph,
            difficulty = 5,
            targetScore = 50_000L,
            settings = official,
            guildId = 1,
            enhanceNodeIds = emptySet(),
        )
        assertTrue("expectedScore=${evaluation.expectedScore} prob=${evaluation.probReachTarget}", evaluation.shouldRetire)
    }

    @Test
    fun `Area 1 node 3 keeps reachable targets with unknown gold and an obscured opening roster`() {
        val remaining = fullStandardFiveAreaMap().drop(3)
        val config = ScoreTargetGateConfig(midRunHardUpperOnly = false)
        val pessimisticEstimator = BattlePassEstimator { _, _ -> PassEstimate(pPass = 0.05, expectedTeams = 3.0) }
        for (observedUnits in 4..5) {
            for (target in listOf(50_000L, 52_000L)) {
                val evaluation = evaluateMidRunScoreTarget(
                    remainingRouteNodes = remaining,
                    confirmedCounts = areaOneNodeThreeCounts,
                    difficulty = 5,
                    targetScore = target,
                    planningState = areaOneNodeThreeState(observedUnits, target),
                    passEstimator = pessimisticEstimator,
                    settings = official,
                    config = config,
                )
                assertFalse(evaluation.gate.reason, evaluation.shouldRetire)
                assertFalse(evaluation.usedModel)
                assertTrue(evaluation.hardUpperBound >= target)
                assertTrue(evaluation.expectedScore > evaluation.confirmedPts)
                val minExpectedProbability = if (target == 50_000L) 0.1 else 0.01
                assertTrue(
                    "target=$target prob=${evaluation.probReachTarget}",
                    evaluation.probReachTarget in minExpectedProbability..1.0,
                )
                if (target == 52_000L) {
                    assertTrue(evaluation.probReachTarget < config.minMidRunReachProb)
                    assertTrue(evaluation.probReachTarget < config.lambdaRate * evaluation.expectedTimeSec)
                }
            }
        }
    }

    @Test
    fun `Area 1 still retires when the target exceeds the hard upper bound`() {
        val target = 75_000L
        val evaluation = evaluateMidRunScoreTarget(
            remainingRouteNodes = fullStandardFiveAreaMap().drop(3),
            confirmedCounts = areaOneNodeThreeCounts,
            difficulty = 5,
            targetScore = target,
            planningState = areaOneNodeThreeState(observedUnits = 5, targetScore = target),
            settings = official,
        )

        assertTrue(evaluation.hardUpperBound < target)
        assertTrue(evaluation.shouldRetire)
        assertTrue(evaluation.gate.reason.contains("低于目标"))
    }

    @Test
    fun `a known empty wallet still credits income at later spending shops`() {
        val target = 50_000L
        val remaining = fullStandardFiveAreaMap().drop(3)
        val withoutAreaThreeShop = remaining.map {
            if (it.area == 3 && it.blockType == Node.SHOP) it.copy(blockType = Node.START) else it
        }
        val state = areaOneNodeThreeState(observedUnits = 5, targetScore = target).copy(alphaRupee = 0)
        val evaluation = evaluateMidRunScoreTarget(remaining, areaOneNodeThreeCounts, 5, target, state, settings = official)
        val finalSpendingShopOnly = evaluateMidRunScoreTarget(
            withoutAreaThreeShop, areaOneNodeThreeCounts, 5, target, state.copy(remainingShops = 2), settings = official,
        )

        assertEquals(finalSpendingShopOnly.hardUpperBound + 2_800L, evaluation.hardUpperBound)
    }

    @Test
    fun `Area 3 retires a weak roster using current area EX and boss estimates only with soft early stop`() {
        val target = 50_000L
        val remaining = fullStandardFiveAreaMap().filter { it.area >= 3 }
        val config = ScoreTargetGateConfig()
        val estimatedExams = mutableListOf<BattleExam>()
        val estimator = BattlePassEstimator { _, exam ->
            estimatedExams += exam
            PassEstimate(pPass = 0.05, expectedTeams = 3.0)
        }
        fun evaluate(config: ScoreTargetGateConfig) = evaluateMidRunScoreTarget(
            remainingRouteNodes = remaining,
            confirmedCounts = ScoreCounts(
                areasCleared = AreaClears.Count(2),
                blocksCleared = BlockClears.Count(13),
                units = 8,
                relics = 5,
                normalWins = 3,
                exWins = 1,
            ),
            difficulty = 5,
            targetScore = target,
            planningState = PlanningState(
                difficulty = 5,
                currentArea = 3,
                ownedCharacterIds = (1..8).mapTo(linkedSetOf()) { "10%02d01".format(it) },
                relicIds = setOf(40003),
                markStacks = emptyMap(),
                remainingShops = 3,
                enhanceNode21 = true,
                reviveAvailable = false,
                targetScore = target,
            ),
            passEstimator = estimator,
            settings = official,
            config = config,
        )

        val evaluation = evaluate(config)
        assertTrue(evaluation.usedModel)
        assertEquals(listOf(3, 3), estimatedExams.map { it.area })
        assertTrue(estimatedExams.any { it.isEx })
        assertTrue(estimatedExams.any { it.isBoss })
        assertTrue(evaluation.hardUpperBound >= target)
        assertTrue(evaluation.probReachTarget < config.lambdaRate * evaluation.expectedTimeSec)
        assertFalse(evaluation.gate.reason, evaluation.shouldRetire)

        val softEvaluation = evaluate(ScoreTargetGateConfig(midRunHardUpperOnly = false))
        assertEquals(evaluation.hardUpperBound, softEvaluation.hardUpperBound)
        assertEquals(evaluation.probReachTarget, softEvaluation.probReachTarget, 0.0)
        assertTrue(softEvaluation.gate.reason, softEvaluation.shouldRetire)
    }

    @Test
    fun `post Area 5 shop hardUpperBound matches scoreCheckGate projectedClearScore exactly`() {
        val finalBoss = LabyrinthMapNode(
            area = 5,
            column = 8,
            row = 1,
            blockId = 508L,
            blockType = Node.BOSS,
            questId = 77050301,
            nextBlockIds = emptyList(),
            isAreaLastPoint = true,
        )
        val beforeFinalBoss = ScoreCounts(
            areasCleared = AreaClears.Ids(setOf(1, 2, 3, 4)),
            blocksCleared = BlockClears.ByArea(mapOf(1 to 6, 2 to 7, 3 to 7, 4 to 8, 5 to 7)),
            units = 32,
            relics = 16,
            normalWins = 6,
            exWins = 5,
            bossWins = 1,
        )
        val planningState = PlanningState(
            difficulty = 5,
            currentArea = 5,
            ownedCharacterIds = (1..32).mapTo(linkedSetOf()) { "10%02d01".format(it) },
            relicIds = setOf(40001, 50001),
            markStacks = emptyMap(),
            remainingShops = 0,
            enhanceNode21 = false,
            reviveAvailable = false,
            alphaRupee = 0,
            targetScore = 50_000L,
        )
        val gate50k = scoreCheckGate(official, 5, beforeFinalBoss, 50_000L)
        val eval50k = evaluateMidRunScoreTarget(
            remainingRouteNodes = listOf(finalBoss),
            confirmedCounts = beforeFinalBoss,
            difficulty = 5,
            targetScore = 50_000L,
            planningState = planningState,
            settings = official,
        )
        assertEquals(gate50k.projectedClearScore, eval50k.hardUpperBound)
        assertEquals(gate50k.shouldRetire, eval50k.shouldRetire)

        val impossibleTarget = gate50k.projectedClearScore + 200L
        val evalTooHigh = evaluateMidRunScoreTarget(
            remainingRouteNodes = listOf(finalBoss),
            confirmedCounts = beforeFinalBoss,
            difficulty = 5,
            targetScore = impossibleTarget,
            planningState = planningState.copy(targetScore = impossibleTarget),
            settings = official,
        )
        assertEquals(gate50k.projectedClearScore, evalTooHigh.hardUpperBound)
        assertTrue(evalTooHigh.shouldRetire)
    }

    @Test
    fun `mid-run gate responds to BattlePassEstimator win probabilities`() {
        val finalBoss = LabyrinthMapNode(
            area = 5,
            column = 8,
            row = 1,
            blockId = 508L,
            blockType = Node.BOSS,
            questId = 77050301,
            nextBlockIds = emptyList(),
            isAreaLastPoint = true,
        )
        val beforeFinalBoss = ScoreCounts(
            areasCleared = AreaClears.Ids(setOf(1, 2, 3, 4)),
            blocksCleared = BlockClears.ByArea(mapOf(1 to 6, 2 to 7, 3 to 7, 4 to 8, 5 to 7)),
            units = 45,
            relics = 24,
            normalWins = 6,
            exWins = 6,
            bossWins = 1,
        )
        val planningState = PlanningState(
            difficulty = 5,
            currentArea = 5,
            ownedCharacterIds = (1..35).mapTo(linkedSetOf()) { "10%02d01".format(it) },
            relicIds = emptySet(),
            markStacks = emptyMap(),
            remainingShops = 0,
            enhanceNode21 = false,
            reviveAvailable = false,
            alphaRupee = 0,
            targetScore = 50_000L,
        )
        val lowProbEstimator = BattlePassEstimator { _, _ -> PassEstimate(pPass = 0.01, expectedTeams = 3.0) }
        val highProbEstimator = BattlePassEstimator { _, _ -> PassEstimate(pPass = 0.95, expectedTeams = 1.0) }

        fun evaluate(
            estimator: BattlePassEstimator,
            config: ScoreTargetGateConfig = ScoreTargetGateConfig(),
        ) = evaluateMidRunScoreTarget(
            listOf(finalBoss), beforeFinalBoss, 5, 50_000L, planningState, estimator, official, config,
        )

        val lowEval = evaluate(lowProbEstimator)
        assertTrue(lowEval.usedModel)
        assertEquals(50_000L, lowEval.hardUpperBound)
        assertFalse(lowEval.gate.reason, lowEval.shouldRetire)

        val softConfig = ScoreTargetGateConfig(midRunHardUpperOnly = false)
        assertTrue(lowEval.probReachTarget < softConfig.minMidRunReachProb)
        assertTrue(evaluate(lowProbEstimator, softConfig).shouldRetire)
        val highEval = evaluate(highProbEstimator, softConfig)
        assertTrue(highEval.usedModel)
        assertFalse(highEval.shouldRetire)
    }

    @Test
    fun `mid-run gate at Area 5 shop reflects alphaRupee wallet`() {
        val area5Shop = LabyrinthMapNode(5, 7, 1, 507L, Node.SHOP, null, listOf(508L), isAreaLastPoint = false)
        val area5Boss = LabyrinthMapNode(5, 8, 1, 508L, Node.BOSS, 77050301, emptyList(), isAreaLastPoint = true)
        val beforeShop = ScoreCounts(
            areasCleared = AreaClears.Ids(setOf(1, 2, 3, 4)),
            blocksCleared = BlockClears.ByArea(mapOf(1 to 6, 2 to 7, 3 to 7, 4 to 8, 5 to 6)),
            units = 40,
            relics = 21,
            normalWins = 6,
            exWins = 6,
            bossWins = 1,
        )
        val richState = PlanningState(
            difficulty = 5,
            currentArea = 5,
            ownedCharacterIds = emptySet(),
            relicIds = setOf(40003),
            markStacks = emptyMap(),
            remainingShops = 1,
            enhanceNode21 = true,
            reviveAvailable = false,
            alphaRupee = 10_000,
            targetScore = 50_000L,
        )
        val poorState = richState.copy(alphaRupee = 200)
        val richEval = evaluateMidRunScoreTarget(listOf(area5Shop, area5Boss), beforeShop, 5, 50_000L, richState, null, official)
        val poorEval = evaluateMidRunScoreTarget(listOf(area5Shop, area5Boss), beforeShop, 5, 50_000L, poorState, null, official)

        assertTrue("richUpper=${richEval.hardUpperBound} poorUpper=${poorEval.hardUpperBound}", richEval.hardUpperBound > poorEval.hardUpperBound)
        assertFalse(richEval.gate.reason, richEval.shouldRetire)
        assertTrue(poorEval.hardUpperBound < 50_000L)
        assertTrue(poorEval.shouldRetire)
    }

    @Test
    fun `opening evaluation completes well within 50ms latency budget`() {
        val graph = fullStandardFiveAreaMap().toPlanningRouteGraph()
        evaluateOpeningScoreTarget(graph, difficulty = 5, targetScore = 50_000L, settings = official)
        val started = System.nanoTime()
        repeat(5) {
            evaluateOpeningScoreTarget(graph, difficulty = 5, targetScore = 50_000L, settings = official, guildId = 1, enhanceNodeIds = setOf(21, 31))
        }
        val avgMillis = (System.nanoTime() - started) / 5_000_000.0
        assertTrue("avgOpeningDpMillis=$avgMillis", avgMillis < 50.0)
    }

    private fun areaOneNodeThreeState(observedUnits: Int, targetScore: Long): PlanningState = PlanningState(
        difficulty = 5,
        currentArea = 1,
        ownedCharacterIds = (1..observedUnits).mapTo(linkedSetOf()) { "10%02d01".format(it) },
        relicIds = setOf(40003),
        markStacks = emptyMap(),
        remainingShops = 3,
        enhanceNode21 = true,
        reviveAvailable = false,
        alphaRupee = null,
        targetScore = targetScore,
    )

    private fun fullStandardFiveAreaMap(
        exCountInArea: Map<Int, Int> = mapOf(2 to 1, 3 to 1, 4 to 2, 5 to 2),
    ): List<LabyrinthMapNode> {
        val templates = LabyrinthRoutePolicy.AREA_REQUIREMENTS
        val nodes = mutableListOf<LabyrinthMapNode>()
        var previousAreaLastId: Long? = null
        val ordered = mutableListOf<LabyrinthMapNode>()
        for ((area, cols) in templates) {
            var remainingEx = exCountInArea[area] ?: 0
            for (col in cols.keys.sorted()) {
                val baseType = cols.getValue(col)
                val type = if (baseType == Node.EX_BATTLE) {
                    if (remainingEx > 0) {
                        remainingEx--
                        Node.EX_BATTLE
                    } else {
                        Node.NORMAL_BATTLE
                    }
                } else {
                    baseType
                }
                val id = area * 100L + col
                val isAreaLast = col == cols.keys.max()
                val questId = when {
                    area == 3 && type == Node.BOSS -> 770330401
                    area == 5 && type == Node.BOSS -> 770530301
                    type == Node.EX_BATTLE -> 77000001 + area * 100 + col
                    else -> null
                }
                val node = LabyrinthMapNode(
                    area = area,
                    column = col,
                    row = 1,
                    blockId = id,
                    blockType = type,
                    questId = questId,
                    nextBlockIds = emptyList(),
                    isAreaLastPoint = isAreaLast,
                )
                ordered += node
            }
        }
        for (index in ordered.indices) {
            val current = ordered[index]
            val next = ordered.getOrNull(index + 1)
            val nextIds = if (next != null && next.area == current.area) listOf(next.blockId) else emptyList()
            nodes += current.copy(nextBlockIds = nextIds)
            previousAreaLastId = current.blockId
        }
        check(previousAreaLastId != null)
        return nodes
    }
}
