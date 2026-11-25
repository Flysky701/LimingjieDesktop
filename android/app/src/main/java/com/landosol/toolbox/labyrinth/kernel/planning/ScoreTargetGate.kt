package com.landosol.toolbox.labyrinth.kernel.planning

import com.landosol.toolbox.labyrinth.canonicalLabyrinthRoleId
import com.landosol.toolbox.labyrinth.node.LabyrinthNodeTypes as Node
import com.landosol.toolbox.protocol.labyrinth.LabyrinthMapNode
import java.math.BigDecimal
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
    )
    val dp = solveRouteDp(
        graph = graph,
        settings = settings,
        difficulty = difficulty,
        outcomeSpecs = built.specs,
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
        usedModel = built.usedModel,
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

    val nextSpendingShopId = if (stage == ScoreTargetStage.MID_RUN && planningState?.alphaRupee != null) {
        graph.order.firstOrNull { id -> isSpendingShopNode(graph.nodes.getValue(id), maxArea) }
            ?: graph.order.lastOrNull { id -> graph.nodes.getValue(id).blockType == Node.SHOP }
    } else null

    val remainingShopCount = graph.order.count { id ->
        graph.nodes.getValue(id).blockType == Node.SHOP
    }
    var usedModel = false

    val specs = graph.nodes.mapValues { (id, node) ->
        val blockBase = if (node.blockType == Node.CLEAR || node.blockType == Node.UNKNOWN) {
            0L
        } else {
            settings.blockPts.getValue(node.area).toLong()
        }
        val areaClearBase = if (id in graph.areaEnds && node.blockType != Node.CLEAR && node.blockType != Node.UNKNOWN) {
            settings.areaClearPts.getValue(node.area).toLong()
        } else 0L
        val fixedAreaBlock = blockBase + areaClearBase
        val shopAhead = hasSpendingShopAhead[id] == true

        when (node.blockType) {
            Node.CLEAR, Node.UNKNOWN -> NodeOutcomeSpec(directPts = 0L, expectedTimeSec = 0.0)
            Node.START -> NodeOutcomeSpec(
                directPts = scalePts(settings, difficulty, fixedAreaBlock),
                expectedTimeSec = NON_BATTLE_TIME_SEC,
            )
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
                if (id == nextSpendingShopId && planningState?.alphaRupee != null) {
                    return@mapValues buildKnownGoldShopSpec(
                        fixedAreaBlock = fixedAreaBlock,
                        gold = planningState.alphaRupee,
                        remainingShops = remainingShopCount,
                        settings = settings,
                        difficulty = difficulty,
                        discount = discount,
                        ownedCharacterIds = planningState.ownedCharacterIds,
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
        shelfModel = CALIBRATED_SHOP_SHELF_MODEL,
        maxRefreshes = 3,
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

private val CALIBRATED_SHOP_SHELF_MODEL = ShopShelfModel(
    shelves = listOf(
        0.5 to listOf(
            ShopItem("ROLE_IMPRINT", ShopItemKind.ROLE_IMPRINT, 1200),
            ShopItem("RELIC_1STAR", ShopItemKind.RELIC_1STAR, 1800),
            ShopItem("RELIC_2STAR", ShopItemKind.RELIC_2STAR, 2800),
        ),
        0.5 to listOf(
            ShopItem("ROLE_CHOICE_IMPRINT", ShopItemKind.ROLE_CHOICE_IMPRINT, 2800),
            ShopItem("RELIC_1STAR", ShopItemKind.RELIC_1STAR, 1800),
            ShopItem("RELIC_2STAR", ShopItemKind.RELIC_2STAR, 2800),
        ),
    ),
)
