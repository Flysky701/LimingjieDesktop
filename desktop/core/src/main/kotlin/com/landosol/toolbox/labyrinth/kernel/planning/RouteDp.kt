package com.landosol.toolbox.labyrinth.kernel.planning

import com.landosol.toolbox.labyrinth.node.LabyrinthNodeTypes as Node
import com.landosol.toolbox.protocol.labyrinth.LabyrinthMapNode
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import kotlin.math.abs
import kotlin.math.pow

/** A supplied outcome replaces the node's defaults. Rewards are already difficulty-scaled. */
data class NodeOutcomeSpec(
    val directPtsPmf: Map<Long, Double> = mapOf(0L to 1.0),
    val winProb: Double = 1.0,
    val expectedTimeSec: Double = 0.0,
    val failTimeSec: Double? = null,
    val isBoss: Boolean? = null,
    val isNonBossBattle: Boolean? = null,
    val goldDelta: Int = 0,
) {
    constructor(
        directPts: Long,
        winProb: Double = 1.0,
        expectedTimeSec: Double = 0.0,
        failTimeSec: Double? = null,
        isBoss: Boolean? = null,
        isNonBossBattle: Boolean? = null,
        goldDelta: Int = 0,
    ) : this(mapOf(directPts to 1.0), winProb, expectedTimeSec, failTimeSec, isBoss, isNonBossBattle, goldDelta)
}

/** Joint incremental collection and wallet after a shop/event, conditional on node success. */
data class WalletOutcome(
    val extraBasePts: Long,
    val goldAfter: Int,
    val probability: Double,
    val elapsedSec: Double = 0.0,
)

typealias WalletModel = (LabyrinthMapNode, Long, Int, Boolean, Int) -> List<WalletOutcome>

/** The state after a node's successful reward, before choosing a successor. */
data class RoutePolicyKey(
    val blockId: Long,
    val scoreBucket: Long,
    val reviveAvailable: Boolean,
    val gold: Int = 0,
)

data class RouteDpResult(
    val bestRoute: List<LabyrinthMapNode>,
    val scoreDistribution: Map<Long, Double>,
    val expectedScore: Double,
    val clearProb: Double,
    val probReachTarget: Double,
    val expectedTimeSec: Double,
    val hardUpperBound: Long,
    val numBuckets: Long,
    val bucketSize: Double,
    val policy: Map<RoutePolicyKey, Long>,
)

private data class BucketState(val bucket: Long, val reviveAvailable: Boolean, val gold: Int = 0) {
    fun after(step: RewardStep) = BucketState(Math.addExact(bucket, step.bucketDelta), step.reviveAvailable, step.goldAfter)
    fun policyKey(blockId: Long, carryGold: Boolean = false) =
        RoutePolicyKey(blockId, bucket, reviveAvailable, if (carryGold) gold else 0)
}

private data class RewardStep(
    val bucketDelta: Long,
    val reviveAvailable: Boolean,
    val probability: Double,
    val goldAfter: Int = 0,
)
private data class BattleSteps(val successes: List<RewardStep>, val wipeProb: Double, val time: Double)
private data class Battle(
    val spec: NodeOutcomeSpec,
    val canRetry: Boolean,
    val rewards: List<Pair<Long, Double>>,
) {
    fun steps(ticks: Long, charge: Boolean): BattleSteps {
        val pass = spec.winProb
        val fail = 1.0 - pass
        val retry = charge && canRetry
        val failSec = spec.failTimeSec ?: spec.expectedTimeSec
        val oneTryTime = pass * spec.expectedTimeSec + fail * failSec
        val time = oneTryTime * (1.0 + fail * if (retry) 1 else 0)
        val successes = buildList {
            if (pass != 0.0) {
                for ((reward, probability) in rewards) add(RewardStep(reward / ticks, charge, pass * probability))
            }
            val retryPass = fail * pass
            if (retry && retryPass != 0.0) {
                for ((reward, probability) in rewards) add(RewardStep(reward / ticks, false, retryPass * probability))
            }
        }
        return BattleSteps(successes, if (retry) fail.pow(2) else fail, time)
    }
}

private data class Metrics(
    val score: Double = 0.0,
    val clear: Double = 0.0,
    val hit: Double = 0.0,
    val time: Double = 0.0,
    val upper: Long = -1,
)

private fun nodeScoreCounts(node: LabyrinthMapNode, areaEnd: Boolean): ScoreCounts {
    val type = node.blockType
    if (type == Node.CLEAR || type == Node.UNKNOWN) return ScoreCounts()
    return ScoreCounts(
        areasCleared = AreaClears.Ids(if (areaEnd) setOf(node.area) else emptySet()),
        blocksCleared = BlockClears.AreaIds(listOf(node.area)),
        units = if (type == Node.LINK) 1 else 0,
        relics = if (type == Node.RELIC) 1 else 0,
        normalWins = if (type == Node.NORMAL_BATTLE) 1 else 0,
        exWins = if (type == Node.EX_BATTLE) 1 else 0,
        bossWins = if (type == Node.BOSS) 1 else 0,
    )
}

private fun prepareBattle(
    node: LabyrinthMapNode,
    areaEnd: Boolean,
    settings: ScoreSettings,
    rate: Long,
    override: NodeOutcomeSpec?,
): Battle {
    val type = node.blockType
    val nonBoss = type == Node.NORMAL_BATTLE || type == Node.EX_BATTLE
    val boss = type == Node.BOSS
    if (override == null) {
        // Carry the fractional part of official rewards until the final settlement floor.
        val reward = Math.multiplyExact(baseScore(settings, nodeScoreCounts(node, areaEnd)), rate)
        require(reward >= 0) { "Negative default reward on block ${node.blockId}" }
        val seconds = when {
            nonBoss || boss -> 30.0
            type == Node.CLEAR -> 0.0
            else -> 1.0
        }
        return Battle(NodeOutcomeSpec(expectedTimeSec = seconds), nonBoss, listOf(reward to 1.0))
    }

    val pmf = override.directPtsPmf
    val failSec = override.failTimeSec
    require(override.winProb in 0.0..1.0 &&
        override.expectedTimeSec.isFinite() && override.expectedTimeSec >= 0 &&
        (failSec == null || (failSec.isFinite() && failSec >= 0)) &&
        pmf.all { (points, probability) -> points >= 0 && probability in 0.0..1.0 }
    ) { "Invalid outcome distribution or time on block ${node.blockId}" }
    // Sum the original binary doubles accurately for the same relative tolerance as math.isclose.
    val total = pmf.values.fold(BigDecimal.ZERO) { sum, p -> sum + BigDecimal(p) }.toDouble()
    require(abs(total - 1.0) <= 1e-9 * maxOf(total, 1.0)) {
        "Invalid outcome distribution on block ${node.blockId}"
    }
    val canRetry = (override.isNonBossBattle ?: nonBoss) && !(override.isBoss ?: boss)
    val rewards = pmf.entries.filter { it.value != 0.0 }.map { (points, probability) ->
        Math.multiplyExact(points, 100L) to probability
    }
    return Battle(override, canRetry, rewards)
}

/**
 * Finds the optimal policy over reachable score buckets, then propagates its unconditional PMF.
 * Bucket width is an exact divisor of the modeled rewards and 300 is the minimum reported size.
 */
fun solveRouteDp(
    graph: RouteGraph,
    settings: ScoreSettings,
    difficulty: Int,
    outcomeSpecs: Map<Long, NodeOutcomeSpec> = emptyMap(),
    targetScore: Long = 0,
    lambdaRate: Double = 0.0,
    reviveAvailable: Boolean = false,
    confirmedPts: Long = 0,
    scoreRemainder: Int = 0,
    startNodeIds: List<Long> = graph.starts,
    walletModel: WalletModel? = null,
    initialGold: Int = 0,
): RouteDpResult {
    require(lambdaRate.isFinite() && lambdaRate >= 0 && scoreRemainder in 0..99) {
        "Invalid time price or partial score remainder"
    }
    require(outcomeSpecs.keys.all { it in graph.nodes }) { "Outcome spec references an unknown block" }
    require(startNodeIds.isNotEmpty() && startNodeIds.all { it in graph.nodes }) {
        "Supply at least one valid entry block"
    }

    val rate = settings.scoreRates.getValue(difficulty).toLong()
    val battles = graph.nodes.mapValues { (id, node) ->
        prepareBattle(node, id in graph.areaEnds, settings, rate, outcomeSpecs[id])
    }
    var ticks = maxOf(1L, scoreGcd(settings, difficulty)) * 100
    if (walletModel != null) {
        ticks = gcd(ticks, Math.multiplyExact(settings.unitPts.toLong(), rate))
        ticks = gcd(ticks, Math.multiplyExact(settings.relicPts.toLong(), rate))
    }
    for (battle in battles.values) {
        for ((reward, _) in battle.rewards) ticks = gcd(ticks, reward)
    }
    val initialTicks = Math.addExact(Math.multiplyExact(confirmedPts, 100L), scoreRemainder.toLong())
    val initial = BucketState(Math.floorDiv(initialTicks, ticks), reviveAvailable, initialGold)
    val offset = Math.floorMod(initialTicks, ticks)

    fun points(bucket: Long): Long =
        Math.floorDiv(Math.addExact(Math.multiplyExact(bucket, ticks), offset), 100L)

    val carryGold = walletModel != null
    val battleSteps = mutableMapOf<Pair<Long, Boolean>, BattleSteps>()
    fun stepsFor(id: Long, state: BucketState): BattleSteps =
        battleSteps.getOrPut(id to state.reviveAvailable) { battles.getValue(id).steps(ticks, state.reviveAvailable) }

    val extendedSteps = mutableMapOf<Pair<Long, BucketState>, BattleSteps>()
    fun transitions(id: Long, state: BucketState): BattleSteps {
        val raw = stepsFor(id, state)
        if (walletModel == null) return raw
        return extendedSteps.getOrPut(id to state) {
            val node = graph.nodes.getValue(id)
            val walletBefore = state.gold + (outcomeSpecs[id]?.goldDelta ?: 0)
            var totalTime = raw.time
            val out = mutableListOf<RewardStep>()
            for (step in raw.successes) {
                val nextBucket = Math.addExact(state.bucket, step.bucketDelta)
                val stepTicks = Math.addExact(Math.multiplyExact(nextBucket, ticks), offset)
                val pts = Math.floorDiv(stepTicks, 100L)
                val rem = Math.floorMod(stepTicks, 100L).toInt()
                val outcomes = walletModel(node, pts, rem, step.reviveAvailable, walletBefore)
                val totalProb = outcomes.fold(BigDecimal.ZERO) { acc, o -> acc + BigDecimal(o.probability) }.toDouble()
                require(abs(totalProb - 1.0) <= 1e-12) { "Wallet rewards must have unit probability mass" }
                for (o in outcomes) {
                    val extraTicks = Math.multiplyExact(o.extraBasePts, rate)
                    val extraBuckets = Math.floorDiv(extraTicks, ticks)
                    val residue = Math.floorMod(extraTicks, ticks)
                    require(residue == 0L && o.goldAfter >= 0 && o.elapsedSec.isFinite() && o.elapsedSec >= 0.0 && o.probability in 0.0..1.0) {
                        "Invalid conditional wallet reward"
                    }
                    if (o.probability != 0.0) {
                        val mass = step.probability * o.probability
                        out += RewardStep(Math.addExact(step.bucketDelta, extraBuckets), step.reviveAvailable, mass, o.goldAfter)
                        totalTime += mass * o.elapsedSec
                    }
                }
            }
            BattleSteps(out, raw.wipeProb, totalTime)
        }
    }

    fun prefers(candidate: Metrics, incumbent: Metrics): Boolean {
        if (targetScore > 0) {
            val candidateValue = candidate.hit - lambdaRate * candidate.time
            val incumbentValue = incumbent.hit - lambdaRate * incumbent.time
            if (candidateValue != incumbentValue) return candidateValue > incumbentValue
        }
        if (candidate.score != incumbent.score) return candidate.score > incumbent.score
        if (candidate.clear != incumbent.clear) return candidate.clear > incumbent.clear
        return candidate.time < incumbent.time
    }

    val entriesAt = mutableMapOf<Long, MutableSet<BucketState>>()
    for (id in startNodeIds) entriesAt.getOrPut(id) { mutableSetOf() }.add(initial)
    for (id in graph.order) {
        val states = entriesAt[id] ?: continue
        val exits = mutableSetOf<BucketState>()
        for (state in states) {
            transitions(id, state).successes.mapTo(exits, state::after)
        }
        for (child in graph.children.getValue(id)) {
            entriesAt.getOrPut(child) { mutableSetOf() }.addAll(exits)
        }
    }

    val values = mutableMapOf<Long, Map<BucketState, Metrics>>()
    val continuations = mutableMapOf<RoutePolicyKey, Metrics>()
    val policy = mutableMapOf<RoutePolicyKey, Long>()
    fun afterNode(id: Long, state: BucketState): Metrics = continuations.getOrPut(state.policyKey(id, carryGold)) {
        val children = graph.children.getValue(id)
        if (children.isEmpty()) {
            if (id in graph.clearEnds) {
                val score = points(state.bucket)
                Metrics(score.toDouble(), 1.0, if (score >= targetScore) 1.0 else 0.0, upper = state.bucket)
            } else {
                Metrics()
            }
        } else {
            var selected = children.first()
            var best = values.getValue(selected).getValue(state)
            var upper = best.upper
            for (index in 1 until children.size) {
                val child = children[index]
                val candidate = values.getValue(child).getValue(state)
                if (prefers(candidate, best)) {
                    selected = child
                    best = candidate
                }
                // Alternatives are mutually exclusive. The bound is the best single continuation.
                upper = maxOf(upper, candidate.upper)
            }
            policy[state.policyKey(id, carryGold)] = selected
            best.copy(upper = upper)
        }
    }

    for (id in graph.order.asReversed()) {
        val states = entriesAt.remove(id) ?: continue
        values[id] = states.associateWith { state ->
            val steps = transitions(id, state)
            var score = 0.0
            var clear = 0.0
            var hit = 0.0
            var time = steps.time
            var upper = -1L
            for (step in steps.successes) {
                val continuation = afterNode(id, state.after(step))
                score += step.probability * continuation.score
                clear += step.probability * continuation.clear
                hit += step.probability * continuation.hit
                time += step.probability * continuation.time
                upper = maxOf(upper, continuation.upper)
            }
            Metrics(score, clear, hit, time, upper)
        }
    }

    var entry = startNodeIds.first()
    var metrics = values.getValue(entry).getValue(initial)
    var bound = metrics.upper
    for (index in 1 until startNodeIds.size) {
        val id = startNodeIds[index]
        val candidate = values.getValue(id).getValue(initial)
        if (prefers(candidate, metrics)) {
            entry = id
            metrics = candidate
        }
        bound = maxOf(bound, candidate.upper)
    }

    val mass = mutableMapOf(entry to mutableMapOf(initial to 1.0))
    val distribution = mutableMapOf<Long, Double>()
    for (id in graph.order) {
        for ((state, incoming) in mass.remove(id).orEmpty()) {
            val steps = transitions(id, state)
            if (steps.wipeProb != 0.0) distribution[0L] = (distribution[0L] ?: 0.0) + incoming * steps.wipeProb
            for (step in steps.successes) {
                val after = state.after(step)
                val probability = incoming * step.probability
                val child = policy[after.policyKey(id, carryGold)]
                if (child != null) {
                    val childMass = mass.getOrPut(child) { mutableMapOf() }
                    childMass[after] = (childMass[after] ?: 0.0) + probability
                } else {
                    val score = if (id in graph.clearEnds) points(after.bucket) else 0L
                    distribution[score] = (distribution[score] ?: 0.0) + probability
                }
            }
        }
    }

    val route = mutableListOf<LabyrinthMapNode>()
    var next: Long? = entry
    var state = initial
    while (next != null) {
        val id = next
        route.add(graph.nodes.getValue(id))
        val step = transitions(id, state).successes.maxByOrNull {
            it.probability * afterNode(id, state.after(it)).clear
        } ?: break
        state = state.after(step)
        next = policy[state.policyKey(id, carryGold)]
    }
    return RouteDpResult(
        route, distribution.toSortedMap(), metrics.score, metrics.clear, metrics.hit, metrics.time,
        if (bound < 0) 0 else points(bound), maxOf(300L, bound), ticks / 100.0, policy,
    )
}

enum class PlanningDecision { ACCEPT, RETIRE }

data class EarlyStopDecision(val decision: PlanningDecision, val reason: String) {
    val shouldRetire: Boolean get() = decision == PlanningDecision.RETIRE
}

fun evaluateEarlyStop(
    confirmedPts: Long,
    maxAchievableRemainingPts: Long,
    targetScore: Long,
    probReachTarget: Double = 1.0,
    expectedTimeSec: Double = 0.0,
    lambdaRate: Double = 0.0,
): EarlyStopDecision {
    val top = confirmedPts + maxAchievableRemainingPts
    if (targetScore > 0 && top < targetScore) {
        return EarlyStopDecision(PlanningDecision.RETIRE, "单条可达路径的分数上界 $top 低于目标 $targetScore")
    }
    if (targetScore > 0 && lambdaRate > 0 && probReachTarget < lambdaRate * expectedTimeSec) {
        return EarlyStopDecision(
            PlanningDecision.RETIRE,
            "剩余命中率 ${probReachTarget.gateText()} 低于时间成本 ${lambdaRate.gateText()} × ${expectedTimeSec.gateText()} 秒",
        )
    }
    return EarlyStopDecision(PlanningDecision.ACCEPT, "当前后缀通过分数上界与时间收益门禁")
}

private fun Double.gateText(): String {
    if (isNaN()) return "nan"
    if (isInfinite()) return if (this < 0) "-inf" else "inf"
    if (this == 0.0) return if (toRawBits() < 0) "-0" else "0"
    val rounded = BigDecimal(this).round(MathContext(4, RoundingMode.HALF_EVEN)).stripTrailingZeros()
    val exponent = rounded.precision() - rounded.scale() - 1
    if (exponent in -4..3) return rounded.toPlainString()
    val sign = if (exponent < 0) "-" else "+"
    return "${rounded.movePointLeft(exponent).toPlainString()}e$sign${abs(exponent).toString().padStart(2, '0')}"
}

data class OpeningMapEvaluation(
    val route: RouteDpResult,
    val gate: EarlyStopDecision,
    val attemptTimeSec: Double,
) {
    val shouldReroll: Boolean get() = gate.shouldRetire
}

fun scoreOpeningMap(
    graph: RouteGraph,
    settings: ScoreSettings,
    difficulty: Int,
    outcomeSpecs: Map<Long, NodeOutcomeSpec> = emptyMap(),
    targetScore: Long = 0,
    lambdaRate: Double = 0.0,
    restartTimeSec: Double = 1.0,
    reviveAvailable: Boolean = false,
    confirmedPts: Long = 0,
    scoreRemainder: Int = 0,
): OpeningMapEvaluation {
    require(restartTimeSec.isFinite() && restartTimeSec >= 0) { "Invalid restart duration" }
    val route = solveRouteDp(
        graph, settings, difficulty, outcomeSpecs, targetScore, lambdaRate, reviveAvailable, confirmedPts, scoreRemainder,
    )
    val gate = evaluateEarlyStop(
        confirmedPts, route.hardUpperBound - confirmedPts, targetScore,
        route.probReachTarget, route.expectedTimeSec, lambdaRate,
    )
    return OpeningMapEvaluation(route, gate, if (gate.shouldRetire) restartTimeSec else route.expectedTimeSec)
}
