package com.landosol.toolbox.labyrinth.kernel.planning

import com.landosol.toolbox.labyrinth.canonicalLabyrinthRoleId
import com.landosol.toolbox.labyrinth.node.LabyrinthNodeTypes as Node
import com.landosol.toolbox.protocol.labyrinth.LabyrinthMapNode
import java.math.BigDecimal
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt

enum class ScoreTargetStage {
    OPENING,
    MID_RUN,
}

data class ScoreTargetGateConfig(
    val lambdaRate: Double = 1e-4,
    val minOpeningReachProb: Double = 0.05,
    val minMidRunReachProb: Double = 0.03,
    val openingNormalWinProb: Double = 0.995,
    val openingExWinProb: Double = 0.98,
    val openingBossWinProb: Double = 0.99,
    val midRunNormalWinProb: Double = 0.995,
    val midRunExWinProb: Double = 0.98,
    val midRunBossWinProb: Double = 0.99,
    val midRunHardUpperOnly: Boolean = true,
) {
    init {
        require(lambdaRate.isFinite() && lambdaRate >= 0.0)
        require(minOpeningReachProb in 0.0..1.0 && minMidRunReachProb in 0.0..1.0)
        require(openingNormalWinProb in 0.0..1.0 && openingExWinProb in 0.0..1.0 && openingBossWinProb in 0.0..1.0)
        require(midRunNormalWinProb in 0.0..1.0 && midRunExWinProb in 0.0..1.0 && midRunBossWinProb in 0.0..1.0)
    }
}

data class ScoreTargetEvaluation(
    val stage: ScoreTargetStage,
    val targetScore: Long,
    val confirmedPts: Long,
    val expectedScore: Double,
    val hardUpperBound: Long,
    val probReachTarget: Double,
    val expectedTimeSec: Double,
    val gate: EarlyStopDecision,
    val bestRoute: List<LabyrinthMapNode>,
    val usedModel: Boolean,
) {
    val shouldRetire: Boolean get() = gate.shouldRetire
}

fun maxShopBasePts(
    gold: Int,
    discountModel: ShopDiscountModel,
    settings: ScoreSettings = ScoreSettings.currentOfficial(),
): Long {
    if (gold <= 0 || settings.unitPts <= 0) return 0L
    val relicPrice = maxOf(1, discountModel.price(1800))
    val unitPrice = maxOf(1, discountModel.price(1200))
    val relicRatio = maxOf(1L, settings.relicPts.toLong() / settings.unitPts.toLong())
    val costPerUnitStep = minOf(maxOf(1L, relicPrice.toLong() / relicRatio), unitPrice.toLong())
    return (gold.toLong() / costPerUnitStep) * settings.unitPts.toLong()
}

internal fun normalizePmf(raw: Map<Long, Double>): Map<Long, Double> {
    val filtered = raw.entries.filter { (pts, p) -> pts >= 0L && p.isFinite() && p > 0.0 }
    if (filtered.isEmpty()) return mapOf(0L to 1.0)
    val sum = filtered.sumOf { it.value }
    require(sum > 0.0) { "PMF total mass must be positive" }
    val normalized = linkedMapOf<Long, Double>()
    var peakKey = filtered.first().key
    var peakValue = -1.0
    var running = BigDecimal.ZERO
    for ((pts, p) in filtered) {
        val scaled = p / sum
        normalized[pts] = scaled
        running += BigDecimal(scaled)
        if (scaled > peakValue) {
            peakValue = scaled
            peakKey = pts
        }
    }
    val delta = BigDecimal.ONE.subtract(running).toDouble()
    val adjusted = (normalized.getValue(peakKey) + delta).coerceIn(0.0, 1.0)
    normalized[peakKey] = adjusted
    return normalized
}

private fun scaledPmf(
    extraBasePmf: Map<Long, Double>,
    fixedBase: Long,
    settings: ScoreSettings,
    difficulty: Int,
): Map<Long, Double> {
    val raw = linkedMapOf<Long, Double>()
    for ((extraBase, p) in extraBasePmf) {
        if (p <= 0.0) continue
        val pts = scalePts(settings, difficulty, fixedBase + extraBase)
        raw[pts] = (raw[pts] ?: 0.0) + p
    }
    return normalizePmf(raw)
}

fun applyScoreTargetGate(
    stage: ScoreTargetStage,
    targetScore: Long,
    confirmedPts: Long,
    routeResult: RouteDpResult,
    config: ScoreTargetGateConfig = ScoreTargetGateConfig(),
    planningState: PlanningState? = null,
): EarlyStopDecision {
    if (targetScore <= 0L) {
        return EarlyStopDecision(PlanningDecision.ACCEPT, "刷分目标未开启")
    }
    val upper = routeResult.hardUpperBound
    val hardUpperOnly = stage == ScoreTargetStage.MID_RUN &&
        (config.midRunHardUpperOnly || (planningState != null && planningState.currentArea < 3))
    if (hardUpperOnly) {
        if (upper < targetScore) {
            return EarlyStopDecision(PlanningDecision.RETIRE, "单条可达路径的分数上界 $upper 低于目标 $targetScore")
        }
    } else {
        val earlyStop = evaluateEarlyStop(
            confirmedPts = confirmedPts,
            maxAchievableRemainingPts = (upper - confirmedPts).coerceAtLeast(0L),
            targetScore = targetScore,
            probReachTarget = routeResult.probReachTarget,
            expectedTimeSec = routeResult.expectedTimeSec,
            lambdaRate = config.lambdaRate,
        )
        if (earlyStop.shouldRetire) return earlyStop
    }

    val minProb = when (stage) {
        ScoreTargetStage.OPENING -> config.minOpeningReachProb
        ScoreTargetStage.MID_RUN -> config.minMidRunReachProb
    }
    val pct = (routeResult.probReachTarget * 100.0).roundToInt()
    val exp = routeResult.expectedScore.toLong()
    if (!hardUpperOnly && routeResult.probReachTarget < minProb) {
        val minPct = (minProb * 100.0).roundToInt()
        return EarlyStopDecision(
            PlanningDecision.RETIRE,
            "刷分目标 $targetScore 达标概率 $pct% 低于门禁 $minPct%（期望 $exp，上界 $upper）",
        )
    }
    return EarlyStopDecision(
        PlanningDecision.ACCEPT,
        "刷分目标 $targetScore 可达（达标率 $pct%，期望 $exp，上界 $upper）",
    )
}

fun evaluateOpeningScoreTarget(
    graph: RouteGraph,
    difficulty: Int,
    targetScore: Long,
    settings: ScoreSettings = ScoreSettings.currentOfficial(),
    guildId: Int = 0,
    enhanceNodeIds: Set<Int>? = null,
    config: ScoreTargetGateConfig = ScoreTargetGateConfig(),
): ScoreTargetEvaluation {
    val enhanceNode21 = enhanceNodeIds?.contains(21) == true
    val enhanceNode31 = enhanceNodeIds?.contains(31)
    val openingRelics = if (enhanceNode31 == false) 1 else 2
    val confirmedCounts = ScoreCounts(units = OPENING_UNIT_COUNT, relics = openingRelics)
    val confirmedPts = computeFinalScore(settings, difficulty, confirmedCounts)
    val reviveAvailable = guildId == 2 && enhanceNode31 == true
    val discount = ShopDiscountModel(enhanceNode21 = enhanceNode21, relic40003 = guildId == 3)

    val specs = buildOutcomeSpecs(
        graph = graph,
        stage = ScoreTargetStage.OPENING,
        difficulty = difficulty,
        settings = settings,
        discount = discount,
        planningState = null,
        passEstimator = null,
        config = config,
    ).specs

    val dp = solveRouteDp(
        graph = graph,
        settings = settings,
        difficulty = difficulty,
        outcomeSpecs = specs,
        targetScore = targetScore,
        lambdaRate = config.lambdaRate,
        reviveAvailable = reviveAvailable,
        confirmedPts = confirmedPts,
    )
    val gate = applyScoreTargetGate(ScoreTargetStage.OPENING, targetScore, confirmedPts, dp, config)
    return ScoreTargetEvaluation(
        stage = ScoreTargetStage.OPENING,
        targetScore = targetScore,
        confirmedPts = confirmedPts,
        expectedScore = dp.expectedScore,
        hardUpperBound = dp.hardUpperBound,
        probReachTarget = dp.probReachTarget,
        expectedTimeSec = dp.expectedTimeSec,
        gate = gate,
        bestRoute = dp.bestRoute,
        usedModel = false,
    )
}

fun evaluateMidRunScoreTarget(
    remainingRouteNodes: List<LabyrinthMapNode>,
    confirmedCounts: ScoreCounts,
    difficulty: Int,
    targetScore: Long,
    planningState: PlanningState,
    passEstimator: BattlePassEstimator? = null,
    settings: ScoreSettings = ScoreSettings.currentOfficial(),
    config: ScoreTargetGateConfig = ScoreTargetGateConfig(),
    shelfModel: ShopShelfModel? = null,
): ScoreTargetEvaluation {
    val rate = settings.scoreRates.getValue(difficulty).toLong()
    val confirmedBase = baseScore(settings, confirmedCounts)
    val confirmedTicks = Math.multiplyExact(confirmedBase, rate)
    val confirmedPts = Math.floorDiv(confirmedTicks, 100L)
    val scoreRemainder = Math.floorMod(confirmedTicks, 100L).toInt()

    if (remainingRouteNodes.isEmpty()) {
        val hit = if (targetScore <= 0L || confirmedPts >= targetScore) 1.0 else 0.0
        val emptyDp = RouteDpResult(
            bestRoute = emptyList(),
            scoreDistribution = mapOf(confirmedPts to 1.0),
            expectedScore = confirmedPts.toDouble(),
            clearProb = 1.0,
            probReachTarget = hit,
            expectedTimeSec = 0.0,
            hardUpperBound = confirmedPts,
            numBuckets = 300L,
            bucketSize = maxOf(1L, scoreGcd(settings, difficulty)).toDouble(),
            policy = emptyMap(),
        )
        val gate = applyScoreTargetGate(ScoreTargetStage.MID_RUN, targetScore, confirmedPts, emptyDp, config, planningState)
        return ScoreTargetEvaluation(
            stage = ScoreTargetStage.MID_RUN,
            targetScore = targetScore,
            confirmedPts = confirmedPts,
            expectedScore = emptyDp.expectedScore,
            hardUpperBound = emptyDp.hardUpperBound,
            probReachTarget = emptyDp.probReachTarget,
            expectedTimeSec = emptyDp.expectedTimeSec,
            gate = gate,
            bestRoute = emptyList(),
            usedModel = false,
        )
    }

    val linearNodes = remainingRouteNodes.mapIndexed { index, node ->
        val next = remainingRouteNodes.getOrNull(index + 1)
        node.copy(
            nextBlockIds = listOfNotNull(next?.blockId),
            isAreaLastPoint = next?.area != node.area,
        )
    }
    val graph = linearNodes.toPlanningRouteGraph()
    val built = buildOutcomeSpecs(
        graph = graph,
        stage = ScoreTargetStage.MID_RUN,
        difficulty = difficulty,
        settings = settings,
        discount = planningState.discount,
        planningState = planningState,
        passEstimator = passEstimator,
        config = config,
        shelfModel = shelfModel ?: ShopShelfModel.forOwnedRelics(planningState.relicIds),
    )
    return evaluateMidRunOnGraph(
        graph = graph,
        specs = built,
        confirmedPts = confirmedPts,
        scoreRemainder = scoreRemainder,
        difficulty = difficulty,
        targetScore = targetScore,
        planningState = planningState,
        settings = settings,
        config = config,
    )
}

fun evaluateMidRunScoreTargetOnDag(
    allMapNodes: List<LabyrinthMapNode>,
    currentNodeId: Long,
    confirmedCounts: ScoreCounts,
    difficulty: Int,
    targetScore: Long,
    planningState: PlanningState,
    passEstimator: BattlePassEstimator? = null,
    area3BossIds: Set<Int> = emptySet(),
    area5BossIds: Set<Int> = emptySet(),
    settings: ScoreSettings = ScoreSettings.currentOfficial(),
    config: ScoreTargetGateConfig = ScoreTargetGateConfig(),
    shelfModel: ShopShelfModel? = null,
): ScoreTargetEvaluation? {
    val graph = buildForwardReachableGraph(allMapNodes, currentNodeId, area3BossIds, area5BossIds) ?: return null
    val (confirmedPts, scoreRemainder) = confirmedPointsAndRemainder(confirmedCounts, settings, difficulty)
    val built = buildOutcomeSpecs(
        graph = graph,
        stage = ScoreTargetStage.MID_RUN,
        difficulty = difficulty,
        settings = settings,
        discount = planningState.discount,
        planningState = planningState,
        passEstimator = passEstimator,
        config = config,
        zeroRewardNodeIds = setOf(currentNodeId),
        shelfModel = shelfModel ?: ShopShelfModel.forOwnedRelics(planningState.relicIds),
    )
    return evaluateMidRunOnGraph(
        graph = graph,
        specs = built,
        confirmedPts = confirmedPts,
        scoreRemainder = scoreRemainder,
        difficulty = difficulty,
        targetScore = targetScore,
        planningState = planningState,
        settings = settings,
        config = config,
    )
}

private fun confirmedPointsAndRemainder(
    confirmedCounts: ScoreCounts,
    settings: ScoreSettings,
    difficulty: Int,
): Pair<Long, Int> {
    val rate = settings.scoreRates.getValue(difficulty).toLong()
    val confirmedBase = baseScore(settings, confirmedCounts)
    val confirmedTicks = Math.multiplyExact(confirmedBase, rate)
    return Math.floorDiv(confirmedTicks, 100L) to Math.floorMod(confirmedTicks, 100L).toInt()
}

private fun evaluateMidRunOnGraph(
    graph: RouteGraph,
    specs: BuiltOutcomeSpecs,
    confirmedPts: Long,
    scoreRemainder: Int,
    difficulty: Int,
    targetScore: Long,
    planningState: PlanningState,
    settings: ScoreSettings,
    config: ScoreTargetGateConfig,
): ScoreTargetEvaluation {
    val dp = solveRouteDp(
        graph = graph,
        settings = settings,
        difficulty = difficulty,
        outcomeSpecs = specs.specs,
        targetScore = targetScore,
        lambdaRate = config.lambdaRate,
        reviveAvailable = planningState.reviveAvailable,
        confirmedPts = confirmedPts,
        scoreRemainder = scoreRemainder,
    )
    val gate = applyScoreTargetGate(ScoreTargetStage.MID_RUN, targetScore, confirmedPts, dp, config, planningState)
    return ScoreTargetEvaluation(
        stage = ScoreTargetStage.MID_RUN,
        targetScore = targetScore,
        confirmedPts = confirmedPts,
        expectedScore = dp.expectedScore,
        hardUpperBound = dp.hardUpperBound,
        probReachTarget = dp.probReachTarget,
        expectedTimeSec = dp.expectedTimeSec,
        gate = gate,
        bestRoute = dp.bestRoute,
        usedModel = specs.usedModel,
    )
}

data class MidRunRouteReplanResult(
    val bestRemainingRoute: List<LabyrinthMapNode>,
    val expectedScore: Double,
    val incumbentExpectedScore: Double,
    val probReachTarget: Double,
    val incumbentProbReachTarget: Double,
    val hardUpperBound: Long,
    val incumbentHardUpperBound: Long,
    val usedModel: Boolean,
    val shouldSwitch: Boolean,
    val reason: String,
)

fun quantizeOfficialWalletGold(gold: Int): Int =
    ((gold.coerceIn(0, OFFICIAL_WALLET_MAX_GOLD) + OFFICIAL_WALLET_GOLD_STEP / 2) / OFFICIAL_WALLET_GOLD_STEP) *
        OFFICIAL_WALLET_GOLD_STEP

fun buildOfficialWalletModel(
    discount: ShopDiscountModel,
    settings: ScoreSettings,
    difficulty: Int,
    maxArea: Int,
    shelfModel: ShopShelfModel = ShopShelfModel.liveCalibrated(),
): WalletModel {
    val shopMeanCache = mutableMapOf<Pair<Int, Boolean>, Double>()
    val rate = settings.scoreRates.getValue(difficulty).toLong()
    val unitPtsLong = settings.unitPts.toLong()
    val unitScaled = unitPtsLong * rate / 100.0
    return { node, _, _, _, walletBefore ->
        val safeWallet = walletBefore.coerceAtLeast(0)
        if (node.blockType != Node.SHOP || !isSpendingShopNode(node, maxArea) || safeWallet <= 0 || unitScaled <= 0.0) {
            listOf(WalletOutcome(0L, quantizeOfficialWalletGold(safeWallet), 1.0))
        } else {
            val isFinalShop = node.area >= maxArea
            val spendBudget = if (isFinalShop) safeWallet else minOf(safeWallet, OFFICIAL_NON_FINAL_SHOP_SPEND_CAP)
            val goldAfter = if (isFinalShop) 0 else quantizeOfficialWalletGold(safeWallet - spendBudget)
            val quantizedSpend = quantizeOfficialWalletGold(spendBudget).coerceAtMost(MAX_SHOP_DP_GOLD)
            val meanShopPts = shopMeanCache.getOrPut(quantizedSpend to isFinalShop) {
                if (quantizedSpend <= 0) 0.0
                else shopScoreDistribution(
                    gold = quantizedSpend,
                    remainingShops = 1,
                    settings = settings,
                    difficulty = difficulty,
                    discountModel = discount,
                    shelfModel = shelfModel,
                    maxRefreshes = if (isFinalShop) 2 else 1,
                ).entries.sumOf { (pts, p) -> pts * p }
            }
            if (meanShopPts <= 0.0) {
                listOf(WalletOutcome(0L, goldAfter, 1.0))
            } else {
                val kFloat = meanShopPts / unitScaled
                val kLow = floor(kFloat).toLong().coerceAtLeast(0L)
                val frac = (kFloat - kLow).coerceIn(0.0, 1.0)
                when {
                    frac <= 1e-12 -> listOf(WalletOutcome(kLow * unitPtsLong, goldAfter, 1.0))
                    1.0 - frac <= 1e-12 -> listOf(WalletOutcome((kLow + 1L) * unitPtsLong, goldAfter, 1.0))
                    else -> listOf(
                        WalletOutcome(kLow * unitPtsLong, goldAfter, 1.0 - frac),
                        WalletOutcome((kLow + 1L) * unitPtsLong, goldAfter, frac),
                    )
                }
            }
        }
    }
}

data class OfficialScoreRouteEval(
    val dp: RouteDpResult,
    val usedModel: Boolean,
)

fun evaluateOfficialScoreRoute(
    graph: RouteGraph,
    difficulty: Int,
    settings: ScoreSettings = ScoreSettings.currentOfficial(),
    guildId: Int = 0,
    enhanceNodeIds: Set<Int>? = null,
    planningState: PlanningState? = null,
    passEstimator: BattlePassEstimator? = null,
    config: ScoreTargetGateConfig = ScoreTargetGateConfig(),
    initialGold: Int? = null,
    zeroRewardNodeIds: Set<Long> = emptySet(),
    confirmedPts: Long = 0L,
    scoreRemainder: Int = 0,
    shelfModel: ShopShelfModel? = null,
): OfficialScoreRouteEval {
    val resolvedShelfModel = shelfModel
        ?: planningState?.let { ShopShelfModel.forOwnedRelics(it.relicIds) }
        ?: ShopShelfModel.liveCalibrated()
    val discount = planningState?.discount ?: ShopDiscountModel(
        enhanceNode21 = enhanceNodeIds?.contains(21) == true,
        relic40003 = guildId == 3,
    )
    val reviveAvailable = planningState?.reviveAvailable
        ?: (guildId == 2 && enhanceNodeIds?.contains(31) == true)
    val resolvedGold = quantizeOfficialWalletGold(initialGold ?: planningState?.alphaRupee ?: OFFICIAL_OPENING_GOLD)
    val maxArea = graph.nodes.values.maxOfOrNull(LabyrinthMapNode::area) ?: 1
    var usedModel = false

    val specs = graph.nodes.mapValues { (id, node) ->
        if (id in zeroRewardNodeIds) {
            return@mapValues NodeOutcomeSpec(directPts = 0L, winProb = 1.0, expectedTimeSec = 0.0, goldDelta = 0)
        }
        val isUnscoredBlock = node.blockType == Node.CLEAR ||
            node.blockType == Node.UNKNOWN ||
            node.blockType == Node.START
        val blockBase = if (isUnscoredBlock) 0L else settings.blockPts.getValue(node.area).toLong()
        val areaClearBase = if (id in graph.areaEnds && !isUnscoredBlock) {
            settings.areaClearPts.getValue(node.area).toLong()
        } else 0L
        val fixedAreaBlock = blockBase + areaClearBase
        when (node.blockType) {
            Node.CLEAR, Node.UNKNOWN, Node.START -> NodeOutcomeSpec(directPts = 0L, expectedTimeSec = 0.0, goldDelta = 0)
            Node.NORMAL_BATTLE -> {
                val winProb = if (planningState != null) config.midRunNormalWinProb else config.openingNormalWinProb
                NodeOutcomeSpec(
                    directPts = scalePts(settings, difficulty, fixedAreaBlock + settings.normalPts),
                    winProb = winProb,
                    expectedTimeSec = NORMAL_TIME_SEC,
                    failTimeSec = NORMAL_TIME_SEC,
                    isBoss = false,
                    isNonBossBattle = true,
                    goldDelta = 300,
                )
            }
            Node.EX_BATTLE -> {
                val estimated = if (
                    passEstimator != null && planningState != null && node.questId != null &&
                    canUsePassEstimatorForNode(planningState, node.area)
                ) {
                    passEstimator.estimate(
                        planningState,
                        BattleExam(node.questId, node.area, isBoss = false, maxTeams = 1, isEx = true),
                    )?.pPass?.also { usedModel = true }
                } else null
                val winProb = estimated
                    ?: if (planningState != null) config.midRunExWinProb else config.openingExWinProb
                NodeOutcomeSpec(
                    directPts = scalePts(settings, difficulty, fixedAreaBlock + settings.exPts + settings.unitPts + settings.relicPts),
                    winProb = winProb,
                    expectedTimeSec = EX_TIME_SEC,
                    failTimeSec = EX_TIME_SEC,
                    isBoss = false,
                    isNonBossBattle = true,
                    goldDelta = 500,
                )
            }
            Node.LINK -> NodeOutcomeSpec(
                directPts = scalePts(settings, difficulty, fixedAreaBlock + 2L * settings.unitPts),
                winProb = 1.0,
                expectedTimeSec = NON_BATTLE_TIME_SEC,
                goldDelta = 200,
            )
            Node.RELIC -> NodeOutcomeSpec(
                directPts = scalePts(settings, difficulty, fixedAreaBlock + settings.relicPts),
                winProb = 1.0,
                expectedTimeSec = NON_BATTLE_TIME_SEC,
                goldDelta = 200,
            )
            Node.EVENT -> NodeOutcomeSpec(
                directPts = scalePts(settings, difficulty, fixedAreaBlock + settings.unitPts),
                winProb = 1.0,
                expectedTimeSec = NON_BATTLE_TIME_SEC,
                goldDelta = 600,
            )
            Node.SHOP -> NodeOutcomeSpec(
                directPts = scalePts(settings, difficulty, fixedAreaBlock),
                winProb = 1.0,
                expectedTimeSec = SHOP_TIME_SEC,
                goldDelta = 0,
            )
            Node.BOSS -> {
                val estimated = if (
                    passEstimator != null && planningState != null && node.questId != null &&
                    canUsePassEstimatorForNode(planningState, node.area)
                ) {
                    passEstimator.estimate(
                        planningState,
                        BattleExam(node.questId, node.area, isBoss = true, maxTeams = 3, isEx = false),
                    )?.pPass?.also { usedModel = true }
                } else null
                val winProb = estimated
                    ?: if (planningState != null) config.midRunBossWinProb else config.openingBossWinProb
                val clearDropBase = if (id in graph.clearEnds) 0L else settings.unitPts.toLong() + settings.relicPts
                NodeOutcomeSpec(
                    directPts = scalePts(settings, difficulty, fixedAreaBlock + settings.bossPts + clearDropBase),
                    winProb = winProb,
                    expectedTimeSec = BOSS_TIME_SEC,
                    failTimeSec = BOSS_TIME_SEC,
                    isBoss = true,
                    isNonBossBattle = false,
                    goldDelta = if (id in graph.clearEnds) 0 else 600,
                )
            }
            else -> NodeOutcomeSpec(
                directPts = scalePts(settings, difficulty, fixedAreaBlock),
                expectedTimeSec = NON_BATTLE_TIME_SEC,
                goldDelta = 0,
            )
        }
    }

    val walletModel = buildOfficialWalletModel(discount, settings, difficulty, maxArea, resolvedShelfModel)
    val dp = solveRouteDp(
        graph = graph,
        settings = settings,
        difficulty = difficulty,
        outcomeSpecs = specs,
        targetScore = 0L,
        lambdaRate = 0.0,
        reviveAvailable = reviveAvailable,
        confirmedPts = confirmedPts,
        scoreRemainder = scoreRemainder,
        walletModel = walletModel,
        initialGold = resolvedGold,
    )
    return OfficialScoreRouteEval(dp, usedModel)
}

fun buildForwardReachableGraph(
    allMapNodes: List<LabyrinthMapNode>,
    startNodeId: Long,
    area3BossIds: Set<Int> = emptySet(),
    area5BossIds: Set<Int> = emptySet(),
): RouteGraph? {
    val playable = allMapNodes.filter {
        it.blockId == startNodeId || (it.blockType != Node.CLEAR && it.blockType != Node.UNKNOWN)
    }
    val byId = playable.associateBy(LabyrinthMapNode::blockId)
    val startNode = byId[startNodeId] ?: return null
    val minArea = startNode.area
    val areas = playable.filter { it.area >= minArea }.groupBy(LabyrinthMapNode::area).toSortedMap()
    if (areas.isEmpty()) return null
    val areaList = areas.keys.toList()
    for (i in 0 until areaList.lastIndex) {
        if (areaList[i + 1] != areaList[i] + 1) return null
    }
    val maxArea = areaList.last()
    val lastColByArea = areas.mapValues { (_, list) -> list.maxOf(LabyrinthMapNode::column) }
    val firstColByArea = areas.mapValues { (_, list) -> list.minOf(LabyrinthMapNode::column) }

    fun bossAllowed(node: LabyrinthMapNode, areaEnds: List<LabyrinthMapNode>): Boolean {
        val allowed = when (node.area) {
            3 -> area3BossIds
            5 -> area5BossIds
            else -> emptySet()
        }
        if (allowed.isEmpty()) return true
        val matchedEnds = areaEnds.any { end ->
            end.blockType == Node.BOSS &&
                com.landosol.toolbox.labyrinth.LabyrinthBossCatalog.unitIdForQuest(end.area, end.questId) in allowed
        }
        if (!matchedEnds) return true
        return node.blockType == Node.BOSS &&
            com.landosol.toolbox.labyrinth.LabyrinthBossCatalog.unitIdForQuest(node.area, node.questId) in allowed
    }

    val areaEndNodes = areas.mapValues { (area, list) ->
        val lastCol = lastColByArea.getValue(area)
        list.filter { it.column == lastCol || it.isAreaLastPoint }
    }

    val rawEdges = mutableMapOf<Long, List<Long>>()
    for (area in areaList) {
        val areaNodes = areas.getValue(area)
        val endSet = areaEndNodes.getValue(area)
        val nextAreaStarts = if (area < maxArea) {
            val firstCol = firstColByArea.getValue(area + 1)
            areas.getValue(area + 1).filter { it.column == firstCol }.map(LabyrinthMapNode::blockId)
        } else emptyList()
        for (node in areaNodes) {
            if (node in endSet) {
                val explicitCross = node.nextBlockIds.filter { cid ->
                    byId[cid]?.let { it.area == area + 1 } == true
                }
                rawEdges[node.blockId] = if (explicitCross.isNotEmpty()) explicitCross else nextAreaStarts
            } else {
                rawEdges[node.blockId] = node.nextBlockIds.filter { cid ->
                    byId[cid]?.let { it.area == area && it.column > node.column } == true
                }
            }
        }
    }

    val forward = linkedSetOf<Long>()
    val queue = ArrayDeque<Long>()
    forward.add(startNodeId)
    queue.addLast(startNodeId)
    while (queue.isNotEmpty()) {
        val cur = queue.removeFirst()
        for (child in rawEdges[cur].orEmpty()) {
            if (forward.add(child)) queue.addLast(child)
        }
    }

    val reverseParents = mutableMapOf<Long, MutableList<Long>>()
    for ((u, vs) in rawEdges) {
        if (u !in forward) continue
        val uNode = byId.getValue(u)
        if (uNode in areaEndNodes.getValue(uNode.area) && !bossAllowed(uNode, areaEndNodes.getValue(uNode.area))) continue
        for (v in vs) {
            if (v in forward) reverseParents.getOrPut(v) { mutableListOf() }.add(u)
        }
    }
    val valid = linkedSetOf<Long>()
    val backQueue = ArrayDeque<Long>()
    val maxEnds = areaEndNodes.getValue(maxArea)
    for (end in maxEnds) {
        if (end.blockId in forward && bossAllowed(end, maxEnds)) {
            valid.add(end.blockId)
            backQueue.addLast(end.blockId)
        }
    }
    while (backQueue.isNotEmpty()) {
        val cur = backQueue.removeFirst()
        for (p in reverseParents[cur].orEmpty()) {
            if (valid.add(p)) backQueue.addLast(p)
        }
    }
    if (startNodeId !in valid) return null

    val subNodes = valid.map { id ->
        val node = byId.getValue(id)
        val filteredNext = rawEdges.getValue(id).filter { it in valid }
        node.copy(nextBlockIds = filteredNext, isAreaLastPoint = node in areaEndNodes.getValue(node.area))
    }
    return try {
        subNodes.toPlanningRouteGraph()
    } catch (_: IllegalArgumentException) {
        null
    }
}

fun replanMidRunRouteFromCurrentNode(
    allMapNodes: List<LabyrinthMapNode>,
    incumbentRemainingRoute: List<LabyrinthMapNode>,
    currentNodeId: Long,
    confirmedCounts: ScoreCounts,
    difficulty: Int,
    targetScore: Long,
    planningState: PlanningState,
    passEstimator: BattlePassEstimator? = null,
    area3BossIds: Set<Int> = emptySet(),
    area5BossIds: Set<Int> = emptySet(),
    settings: ScoreSettings = ScoreSettings.currentOfficial(),
    config: ScoreTargetGateConfig = ScoreTargetGateConfig(),
    shelfModel: ShopShelfModel? = null,
): MidRunRouteReplanResult? {
    val resolvedShelfModel = shelfModel ?: ShopShelfModel.forOwnedRelics(planningState.relicIds)
    val dag = buildForwardReachableGraph(allMapNodes, currentNodeId, area3BossIds, area5BossIds) ?: return null
    val currentNode = dag.nodes[currentNodeId] ?: return null
    if (dag.children[currentNodeId].orEmpty().size < 2) return null

    val linearRaw = listOf(currentNode) + incumbentRemainingRoute
    if (linearRaw.map { it.blockId }.distinct().size != linearRaw.size) return null
    val linearNodes = linearRaw.mapIndexed { index, node ->
        val next = linearRaw.getOrNull(index + 1)
        node.copy(
            nextBlockIds = listOfNotNull(next?.blockId),
            isAreaLastPoint = next?.area != node.area,
        )
    }
    val incumbentGraph = try {
        linearNodes.toPlanningRouteGraph()
    } catch (_: IllegalArgumentException) {
        return null
    }
    val (confirmedPts, scoreRemainder) = confirmedPointsAndRemainder(confirmedCounts, settings, difficulty)
    val zeroSet = setOf(currentNodeId)

    val candidateDp: RouteDpResult
    val incumbentDp: RouteDpResult
    val usedModel: Boolean
    if (targetScore > 0L) {
        val candBuilt = buildOutcomeSpecs(
            graph = dag,
            stage = ScoreTargetStage.MID_RUN,
            difficulty = difficulty,
            settings = settings,
            discount = planningState.discount,
            planningState = planningState,
            passEstimator = passEstimator,
            config = config,
            zeroRewardNodeIds = zeroSet,
            shelfModel = resolvedShelfModel,
        )
        val incBuilt = buildOutcomeSpecs(
            graph = incumbentGraph,
            stage = ScoreTargetStage.MID_RUN,
            difficulty = difficulty,
            settings = settings,
            discount = planningState.discount,
            planningState = planningState,
            passEstimator = passEstimator,
            config = config,
            zeroRewardNodeIds = zeroSet,
            shelfModel = resolvedShelfModel,
        )
        candidateDp = solveRouteDp(
            graph = dag,
            settings = settings,
            difficulty = difficulty,
            outcomeSpecs = candBuilt.specs,
            targetScore = targetScore,
            lambdaRate = config.lambdaRate,
            reviveAvailable = planningState.reviveAvailable,
            confirmedPts = confirmedPts,
            scoreRemainder = scoreRemainder,
        )
        incumbentDp = solveRouteDp(
            graph = incumbentGraph,
            settings = settings,
            difficulty = difficulty,
            outcomeSpecs = incBuilt.specs,
            targetScore = targetScore,
            lambdaRate = config.lambdaRate,
            reviveAvailable = planningState.reviveAvailable,
            confirmedPts = confirmedPts,
            scoreRemainder = scoreRemainder,
        )
        usedModel = candBuilt.usedModel || incBuilt.usedModel
    } else {
        val candEval = evaluateOfficialScoreRoute(
            graph = dag,
            difficulty = difficulty,
            settings = settings,
            planningState = planningState,
            passEstimator = passEstimator,
            config = config,
            initialGold = planningState.alphaRupee,
            zeroRewardNodeIds = zeroSet,
            confirmedPts = confirmedPts,
            scoreRemainder = scoreRemainder,
            shelfModel = resolvedShelfModel,
        )
        val incEval = evaluateOfficialScoreRoute(
            graph = incumbentGraph,
            difficulty = difficulty,
            settings = settings,
            planningState = planningState,
            passEstimator = passEstimator,
            config = config,
            initialGold = planningState.alphaRupee,
            zeroRewardNodeIds = zeroSet,
            confirmedPts = confirmedPts,
            scoreRemainder = scoreRemainder,
            shelfModel = resolvedShelfModel,
        )
        candidateDp = candEval.dp
        incumbentDp = incEval.dp
        usedModel = candEval.usedModel || incEval.usedModel
    }

    val newNext = candidateDp.bestRoute.getOrNull(1)?.blockId
    val incNext = incumbentRemainingRoute.firstOrNull()?.blockId
    val branchChanged = newNext != null && newNext != incNext && candidateDp.bestRoute.firstOrNull()?.blockId == currentNodeId
    val switchByTarget = if (targetScore > 0L) {
        (candidateDp.hardUpperBound >= targetScore && incumbentDp.hardUpperBound < targetScore) ||
            (candidateDp.probReachTarget > incumbentDp.probReachTarget + REPLAN_PROB_HYSTERESIS) ||
            (abs(candidateDp.probReachTarget - incumbentDp.probReachTarget) <= REPLAN_PROB_HYSTERESIS &&
                candidateDp.expectedScore > incumbentDp.expectedScore + REPLAN_SCORE_HYSTERESIS)
    } else {
        candidateDp.expectedScore > incumbentDp.expectedScore + REPLAN_SCORE_HYSTERESIS
    }
    val shouldSwitch = branchChanged && switchByTarget
    val reason = if (shouldSwitch) {
        "岔路重规划：下一节点由 #$incNext 切换为 #$newNext（期望 ${incumbentDp.expectedScore.toLong()}→${candidateDp.expectedScore.toLong()}）"
    } else {
        "维持原路线（期望 ${incumbentDp.expectedScore.toLong()} vs 最优 ${candidateDp.expectedScore.toLong()}）"
    }
    return MidRunRouteReplanResult(
        bestRemainingRoute = candidateDp.bestRoute,
        expectedScore = candidateDp.expectedScore,
        incumbentExpectedScore = incumbentDp.expectedScore,
        probReachTarget = candidateDp.probReachTarget,
        incumbentProbReachTarget = incumbentDp.probReachTarget,
        hardUpperBound = candidateDp.hardUpperBound,
        incumbentHardUpperBound = incumbentDp.hardUpperBound,
        usedModel = usedModel,
        shouldSwitch = shouldSwitch,
        reason = reason,
    )
}

private data class BuiltOutcomeSpecs(
    val specs: Map<Long, NodeOutcomeSpec>,
    val usedModel: Boolean,
)

private fun buildOutcomeSpecs(
    graph: RouteGraph,
    stage: ScoreTargetStage,
    difficulty: Int,
    settings: ScoreSettings,
    discount: ShopDiscountModel,
    planningState: PlanningState?,
    passEstimator: BattlePassEstimator?,
    config: ScoreTargetGateConfig,
    zeroRewardNodeIds: Set<Long> = emptySet(),
    shelfModel: ShopShelfModel = ShopShelfModel.liveCalibrated(),
): BuiltOutcomeSpecs {
    val maxArea = graph.nodes.values.maxOfOrNull(LabyrinthMapNode::area) ?: 1
    // Backward pass on topological DAG to determine if a spending shop is reachable later.
    val hasSpendingShopAhead = mutableMapOf<Long, Boolean>()
    for (id in graph.order.asReversed()) {
        val node = graph.nodes.getValue(id)
        val selfSpending = isSpendingShopNode(node, maxArea)
        val childHas = graph.children.getValue(id).any { hasSpendingShopAhead[it] == true }
        hasSpendingShopAhead[id] = selfSpending || childHas
    }

    val isBranching = graph.children.values.any { it.size > 1 } || graph.starts.size > 1
    val spendingShopWithAncestor = if (isBranching) {
        val ancestorSpending = mutableSetOf<Long>()
        val hasSpendingAncestor = mutableMapOf<Long, Boolean>()
        for (id in graph.order) {
            val here = id in ancestorSpending
            val node = graph.nodes.getValue(id)
            hasSpendingAncestor[id] = here
            val propagate = here || isSpendingShopNode(node, maxArea)
            if (propagate) {
                for (child in graph.children.getValue(id)) ancestorSpending += child
            }
        }
        hasSpendingAncestor
    } else emptyMap()

    val nextSpendingShopId = if (stage == ScoreTargetStage.MID_RUN && planningState?.alphaRupee != null && !isBranching) {
        graph.order.firstOrNull { id -> isSpendingShopNode(graph.nodes.getValue(id), maxArea) }
            ?: graph.order.lastOrNull { id -> graph.nodes.getValue(id).blockType == Node.SHOP }
    } else null

    val remainingShopCount = graph.order.count { id ->
        graph.nodes.getValue(id).blockType == Node.SHOP
    }
    var usedModel = false

    val specs = graph.nodes.mapValues { (id, node) ->
        if (id in zeroRewardNodeIds) {
            return@mapValues NodeOutcomeSpec(directPts = 0L, expectedTimeSec = 0.0)
        }
        val isUnscoredBlock = node.blockType == Node.CLEAR ||
            node.blockType == Node.UNKNOWN ||
            node.blockType == Node.START
        val blockBase = if (isUnscoredBlock) {
            0L
        } else {
            settings.blockPts.getValue(node.area).toLong()
        }
        val areaClearBase = if (id in graph.areaEnds && !isUnscoredBlock) {
            settings.areaClearPts.getValue(node.area).toLong()
        } else 0L
        val fixedAreaBlock = blockBase + areaClearBase
        val shopAhead = hasSpendingShopAhead[id] == true

        when (node.blockType) {
            Node.CLEAR, Node.UNKNOWN, Node.START -> NodeOutcomeSpec(directPts = 0L, expectedTimeSec = 0.0)
            Node.NORMAL_BATTLE -> {
                val winProb = if (stage == ScoreTargetStage.OPENING) config.openingNormalWinProb else config.midRunNormalWinProb
                val extra = if (shopAhead) NORMAL_DROP_WITH_SHOP else NORMAL_DROP_NO_SHOP
                NodeOutcomeSpec(
                    directPtsPmf = scaledPmf(extra, fixedAreaBlock + settings.normalPts, settings, difficulty),
                    winProb = winProb,
                    expectedTimeSec = NORMAL_TIME_SEC,
                    failTimeSec = NORMAL_TIME_SEC,
                    isBoss = false,
                    isNonBossBattle = true,
                )
            }
            Node.EX_BATTLE -> {
                val estimated = if (
                    passEstimator != null && planningState != null && node.questId != null &&
                    canUsePassEstimatorForNode(planningState, node.area)
                ) {
                    passEstimator.estimate(
                        planningState,
                        BattleExam(node.questId, node.area, isBoss = false, maxTeams = 1, isEx = true),
                    )?.pPass?.also { usedModel = true }
                } else null
                val winProb = estimated
                    ?: if (stage == ScoreTargetStage.OPENING) config.openingExWinProb else config.midRunExWinProb
                val extra = if (shopAhead) EX_DROP_WITH_SHOP else EX_DROP_NO_SHOP
                NodeOutcomeSpec(
                    directPtsPmf = scaledPmf(extra, fixedAreaBlock + settings.exPts, settings, difficulty),
                    winProb = winProb,
                    expectedTimeSec = EX_TIME_SEC,
                    failTimeSec = EX_TIME_SEC,
                    isBoss = false,
                    isNonBossBattle = true,
                )
            }
            Node.LINK -> NodeOutcomeSpec(
                directPtsPmf = scaledPmf(LINK_UNIT_PMF, fixedAreaBlock, settings, difficulty),
                winProb = 1.0,
                expectedTimeSec = NON_BATTLE_TIME_SEC,
            )
            Node.RELIC -> NodeOutcomeSpec(
                directPtsPmf = scaledPmf(RELIC_BASE_PMF, fixedAreaBlock, settings, difficulty),
                winProb = 1.0,
                expectedTimeSec = NON_BATTLE_TIME_SEC,
            )
            Node.EVENT -> {
                val extra = if (shopAhead) EVENT_BASE_WITH_SHOP else EVENT_BASE_NO_SHOP
                NodeOutcomeSpec(
                    directPtsPmf = scaledPmf(extra, fixedAreaBlock, settings, difficulty),
                    winProb = 1.0,
                    expectedTimeSec = NON_BATTLE_TIME_SEC,
                )
            }
            Node.SHOP -> {
                val applyKnownGold = planningState?.alphaRupee != null && stage == ScoreTargetStage.MID_RUN && (
                    if (isBranching) isSpendingShopNode(node, maxArea) && spendingShopWithAncestor[id] != true
                    else id == nextSpendingShopId
                )
                if (applyKnownGold) {
                    return@mapValues buildKnownGoldShopSpec(
                        fixedAreaBlock = fixedAreaBlock,
                        gold = requireNotNull(planningState?.alphaRupee),
                        remainingShops = if (isBranching) 1 else remainingShopCount,
                        settings = settings,
                        difficulty = difficulty,
                        discount = discount,
                        ownedCharacterIds = planningState.ownedCharacterIds,
                        shelfModel = shelfModel,
                    )
                }
                val extraPmf = openingShopExtraPmf(node, maxArea, discount)
                NodeOutcomeSpec(
                    directPtsPmf = scaledPmf(extraPmf, fixedAreaBlock, settings, difficulty),
                    winProb = 1.0,
                    expectedTimeSec = SHOP_TIME_SEC,
                )
            }
            Node.BOSS -> {
                val estimated = if (
                    passEstimator != null && planningState != null && node.questId != null &&
                    canUsePassEstimatorForNode(planningState, node.area)
                ) {
                    passEstimator.estimate(
                        planningState,
                        BattleExam(node.questId, node.area, isBoss = true, maxTeams = 3, isEx = false),
                    )?.pPass?.also { usedModel = true }
                } else null
                val winProb = estimated
                    ?: if (stage == ScoreTargetStage.OPENING) config.openingBossWinProb else config.midRunBossWinProb
                val extra = when {
                    id in graph.clearEnds -> mapOf(0L to 1.0)
                    shopAhead -> MID_BOSS_WITH_SHOP
                    else -> MID_BOSS_NO_SHOP
                }
                NodeOutcomeSpec(
                    directPtsPmf = scaledPmf(extra, fixedAreaBlock + settings.bossPts, settings, difficulty),
                    winProb = winProb,
                    expectedTimeSec = BOSS_TIME_SEC,
                    failTimeSec = BOSS_TIME_SEC,
                    isBoss = true,
                    isNonBossBattle = false,
                )
            }
            else -> NodeOutcomeSpec(
                directPts = scalePts(settings, difficulty, fixedAreaBlock),
                expectedTimeSec = NON_BATTLE_TIME_SEC,
            )
        }
    }
    return BuiltOutcomeSpecs(specs, usedModel)
}

private fun canUsePassEstimatorForNode(planningState: PlanningState, nodeArea: Int): Boolean {
    val ownedCount = planningState.ownedCharacterIds.size
    val currentArea = planningState.currentArea
    return ownedCount >= 5 && currentArea >= 3 &&
        (nodeArea <= currentArea || (currentArea >= 4 && ownedCount >= 15))
}

private fun openingShopExtraPmf(node: LabyrinthMapNode, maxArea: Int, discount: ShopDiscountModel): Map<Long, Double> =
    when {
        !isSpendingShopNode(node, maxArea) -> SAVING_SHOP_BASE_PMF
        discount.rate >= 0.24 -> OPENING_SPEND_SHOP_DISCOUNTED
        discount.rate > 0.0 -> OPENING_SPEND_SHOP_SMALL_DISCOUNT
        else -> OPENING_SPEND_SHOP_STANDARD
    }

private fun buildKnownGoldShopSpec(
    fixedAreaBlock: Long,
    gold: Int,
    remainingShops: Int,
    settings: ScoreSettings,
    difficulty: Int,
    discount: ShopDiscountModel,
    ownedCharacterIds: Set<String>,
    shelfModel: ShopShelfModel = ShopShelfModel.liveCalibrated(),
): NodeOutcomeSpec {
    val fixedPts = scalePts(settings, difficulty, fixedAreaBlock)
    if (gold <= 0 || remainingShops <= 0) {
        return NodeOutcomeSpec(directPts = fixedPts, winProb = 1.0, expectedTimeSec = SHOP_TIME_SEC)
    }
    val shopDist = shopScoreDistribution(
        gold = gold.coerceAtMost(MAX_SHOP_DP_GOLD),
        remainingShops = minOf(remainingShops, 2),
        settings = settings,
        difficulty = difficulty,
        discountModel = discount,
        shelfModel = shelfModel,
        maxRefreshes = OFFICIAL_SHOP_MAX_REFRESHES,
        ownedCharacterIds = ownedCharacterIds.mapTo(hashSetOf(), ::canonicalLabyrinthRoleId),
    )
    val raw = linkedMapOf<Long, Double>()
    var distMax = 0L
    for ((shopPts, p) in shopDist) {
        if (p <= 0.0) continue
        val totalPts = fixedPts + shopPts
        raw[totalPts] = (raw[totalPts] ?: 0.0) + p
        if (totalPts > distMax) distMax = totalPts
    }
    val upperShopPts = scalePts(settings, difficulty, maxShopBasePts(gold, discount, settings))
    val conservativeMax = fixedPts + upperShopPts
    if (conservativeMax > distMax) {
        raw[conservativeMax] = (raw[conservativeMax] ?: 0.0) + CONSERVATIVE_TAIL_PROB
    }
    return NodeOutcomeSpec(
        directPtsPmf = normalizePmf(raw),
        winProb = 1.0,
        expectedTimeSec = SHOP_TIME_SEC,
    )
}

private fun isSpendingShopNode(node: LabyrinthMapNode, maxArea: Int): Boolean =
    node.blockType == Node.SHOP && !(node.area == 4 && maxArea >= 5)

private const val OPENING_UNIT_COUNT = 5
private const val OFFICIAL_OPENING_GOLD = 1_000
private const val OFFICIAL_WALLET_GOLD_STEP = 500
private const val OFFICIAL_WALLET_MAX_GOLD = 10_000
private const val OFFICIAL_NON_FINAL_SHOP_SPEND_CAP = 5_000
private const val OFFICIAL_SHOP_MAX_REFRESHES = 2
private const val REPLAN_SCORE_HYSTERESIS = 200.0
private const val REPLAN_PROB_HYSTERESIS = 0.02
private const val MAX_SHOP_DP_GOLD = 25_000
private const val CONSERVATIVE_TAIL_PROB = 1e-4
private const val NORMAL_TIME_SEC = 25.0
private const val EX_TIME_SEC = 45.0
private const val BOSS_TIME_SEC = 60.0
private const val SHOP_TIME_SEC = 12.0
private const val NON_BATTLE_TIME_SEC = 4.0

// Base point (pre-multiplier) calibrated prior distributions matching official Settlement scale.
private val NORMAL_DROP_WITH_SHOP = mapOf(100L to 0.45, 200L to 0.40, 300L to 0.15)
private val NORMAL_DROP_NO_SHOP = mapOf(0L to 0.60, 100L to 0.30, 200L to 0.10)
private val EX_DROP_WITH_SHOP = mapOf(200L to 0.25, 300L to 0.35, 400L to 0.25, 600L to 0.15)
private val EX_DROP_NO_SHOP = mapOf(200L to 0.50, 300L to 0.35, 400L to 0.15)
private val MID_BOSS_WITH_SHOP = mapOf(300L to 0.40, 400L to 0.40, 600L to 0.20)
private val MID_BOSS_NO_SHOP = mapOf(300L to 0.65, 400L to 0.35)
private val LINK_UNIT_PMF = mapOf(100L to 0.10, 200L to 0.40, 300L to 0.40, 400L to 0.10)
private val RELIC_BASE_PMF = mapOf(200L to 0.80, 300L to 0.15, 400L to 0.05)
private val EVENT_BASE_WITH_SHOP = mapOf(0L to 0.15, 200L to 0.35, 400L to 0.30, 600L to 0.15, 800L to 0.05)
private val EVENT_BASE_NO_SHOP = mapOf(0L to 0.25, 200L to 0.40, 400L to 0.25, 600L to 0.10)
private val SAVING_SHOP_BASE_PMF = mapOf(0L to 0.70, 200L to 0.30)
private val OPENING_SPEND_SHOP_STANDARD = mapOf(200L to 0.10, 400L to 0.25, 600L to 0.30, 800L to 0.25, 1000L to 0.08, 1200L to 0.02)
private val OPENING_SPEND_SHOP_SMALL_DISCOUNT = mapOf(300L to 0.10, 500L to 0.22, 700L to 0.30, 900L to 0.25, 1100L to 0.10, 1300L to 0.03)
private val OPENING_SPEND_SHOP_DISCOUNTED = mapOf(400L to 0.08, 600L to 0.22, 800L to 0.30, 1000L to 0.25, 1200L to 0.12, 1400L to 0.03)
