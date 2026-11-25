package com.landosol.toolbox.labyrinth.kernel.strategy

import com.landosol.toolbox.labyrinth.LabyrinthRoleProfile
import com.landosol.toolbox.labyrinth.LabyrinthTeamOptimizer
import com.landosol.toolbox.labyrinth.hasEligibleVanguardAtActualFront
import com.landosol.toolbox.labyrinth.isEligibleBattleVanguard
import com.landosol.toolbox.labyrinth.labyrinthBattleTeamSignature

internal class ModelGuidedBattleTeamSearcher(
    private val estimator: ContextualBattleValueEstimator,
    private val optimizer: LabyrinthTeamOptimizer?,
) {
    data class Result(
        val teams: List<List<LabyrinthRoleProfile>>,
        val assessment: BattleModelAssessment,
    )

    private data class EvaluatedTeam(val unitIds: List<Int>, val value: BattleValue)

    sealed interface Outcome {
        data class Resolved(val result: Result) : Outcome
        data class Fallback(val status: BattleModelDecisionStatus, val reason: String?) : Outcome
    }

    fun search(
        valueContext: BattleValueContext,
        eligibleProfiles: List<LabyrinthRoleProfile>,
        ruleTeams: List<List<String>>,
        failedTeamSignatures: Set<String>,
        requestedBossTeamCount: Int,
        supplementLead: Boolean,
    ): Result? = (searchOutcome(
        valueContext, eligibleProfiles, ruleTeams, failedTeamSignatures, requestedBossTeamCount, supplementLead,
    ) as? Outcome.Resolved)?.result

    fun searchOutcome(
        valueContext: BattleValueContext,
        eligibleProfiles: List<LabyrinthRoleProfile>,
        ruleTeams: List<List<String>>,
        failedTeamSignatures: Set<String>,
        requestedBossTeamCount: Int,
        supplementLead: Boolean,
    ): Outcome {
        val noEligibleCandidate = Outcome.Fallback(
            BattleModelDecisionStatus.NO_ELIGIBLE_CANDIDATE, "无满足前排与排除约束的合法候选队",
        )
        val teamSize = minOf(TEAM_SIZE, eligibleProfiles.size)
        if (teamSize == 0) return noEligibleCandidate
        val ownedByCharacter = valueContext.ownedUnits.associateBy { it.profile.characterId }
        val eligibleByUnitId = eligibleProfiles.associateBy {
            ownedByCharacter[it.characterId]?.unitId ?: return Outcome.Fallback(
                BattleModelDecisionStatus.CONTEXT_UNAVAILABLE, "模型上下文缺少可用角色 ${it.characterId}",
            )
        }
        val context = valueContext.ruleContext
        val requireVanguard = context.frontlineSurvivalGateEnabled && !supplementLead
        val vanguardGate = optimizer?.scoringConfig?.vanguardGate(context)
        val positionOrder = compareBy<Int> { eligibleByUnitId.getValue(it).position ?: Int.MAX_VALUE }
            .thenBy { eligibleByUnitId.getValue(it).characterId }
        val affinities = estimator.unitAffinities(valueContext).orEmpty()
        val affinityOrder = compareByDescending<Int> { affinities[it] ?: Double.NEGATIVE_INFINITY }.thenBy { it }
        val talentByUnitId = valueContext.ownedUnits.associate { it.unitId to it.talentId }

        fun rankCandidates(
            availableIds: Set<Int>,
            enforceVanguard: Boolean,
            ruleSeeds: List<List<String>>,
            evaluated: MutableMap<List<Int>, EvaluatedTeam>,
        ): Outcome.Fallback? {
            val byPosition = availableIds.sortedWith(positionOrder)
            val byAffinity = availableIds.sortedWith(affinityOrder)

            fun evaluate(candidates: Sequence<List<Int>>, budget: Int): Outcome.Fallback? {
                val pending = linkedSetOf<List<Int>>()
                for (candidate in candidates) {
                    if (candidate.size != teamSize || candidate.distinct().size != teamSize) continue
                    if (candidate.any { it !in availableIds }) continue
                    val ids = candidate.sorted()
                    if (ids in evaluated || ids in pending) continue
                    val candidateProfiles = ids.map(eligibleByUnitId::getValue)
                    if (enforceVanguard && !candidateProfiles.hasEligibleVanguardAtActualFront(context, vanguardGate)) continue
                    if (failedTeamSignatures.isNotEmpty() &&
                        labyrinthBattleTeamSignature(candidateProfiles.map { it.characterId }) in failedTeamSignatures
                    ) continue
                    pending += ids
                    if (pending.size == budget) break
                }
                for (batch in pending.chunked(BATCH_SIZE)) {
                    val estimate = estimator.estimateWithSource(valueContext, batch)
                    if (estimate.source != BattleValueSource.MODEL) {
                        return Outcome.Fallback(BattleModelDecisionStatus.LOAD_OR_INFERENCE_FAILED, estimate.fallbackReason)
                    }
                    if (estimate.values.size != batch.size) {
                        return Outcome.Fallback(BattleModelDecisionStatus.LOAD_OR_INFERENCE_FAILED, "模型返回的胜率数量与候选队数不一致")
                    }
                    batch.zip(estimate.values).forEach { (ids, value) -> evaluated[ids] = EvaluatedTeam(ids, value) }
                }
                return null
            }

            val seeds = sequence {
                yieldAll(ruleSeeds.asSequence().map { ids -> ids.mapNotNull { ownedByCharacter[it]?.unitId } })
                val optimizerContext = context.copy(frontlineSurvivalGateEnabled = enforceVanguard)
                val optimizerSeeds = optimizer?.rankedBattleFormations(
                    availableIds.map(eligibleByUnitId::getValue), optimizerContext, failedTeamSignatures,
                ).orEmpty()
                for (team in optimizerSeeds) {
                    yield(team.members.map { ownedByCharacter.getValue(it.characterId).unitId })
                }
                yieldAll(byAffinity.chunked(teamSize).asSequence())
                if (affinities.isNotEmpty()) {
                    for (talent in 1..5) {
                        val (same, other) = byAffinity.partition { talentByUnitId[it] == talent }
                        if (same.isNotEmpty()) yield((same + other).take(teamSize))
                    }
                    val front = byPosition.take(maxOf(1, byPosition.size / 3)).toSet()
                    val (vanguard, rear) = byAffinity.partition { it in front }
                    for (count in 1..2) {
                        yield((vanguard.take(count) + rear + vanguard.drop(count)).take(teamSize))
                    }
                }
            }
            evaluate(seeds, MAX_CANDIDATES)?.let { return it }
            if (evaluated.isEmpty()) {
                val fallback = if (enforceVanguard) sequence {
                    val knownPositions = byPosition.filter { eligibleByUnitId.getValue(it).position != null }
                    for ((index, frontId) in knownPositions.withIndex()) {
                        val front = eligibleByUnitId.getValue(frontId)
                        if (!front.isEligibleBattleVanguard(context, vanguardGate)) continue
                        val followers = knownPositions.drop(index + 1).filter { id ->
                            val profile = eligibleByUnitId.getValue(id)
                            profile.position != front.position || profile.isEligibleBattleVanguard(context, vanguardGate)
                        }
                        yieldAll(combinations(followers, teamSize - 1).map { listOf(frontId) + it })
                    }
                } else combinations(byAffinity, teamSize)
                evaluate(fallback, 1)?.let { return it }
                if (evaluated.isEmpty()) return null
            }

            for (round in 0 until SEARCH_ROUNDS) {
                val remaining = MAX_CANDIDATES - evaluated.size
                if (remaining == 0) break
                val beam = evaluated.values.sortedWith(PRIORITY).take(BEAM_SIZE)
                val neighbors = sequence {
                    val streams = ArrayDeque(beam.flatMap { team ->
                        (1..2).map { swaps(team.unitIds, byAffinity, it).iterator() }
                    })
                    while (streams.isNotEmpty()) {
                        val stream = streams.removeFirst()
                        if (stream.hasNext()) {
                            yield(stream.next())
                            streams.addLast(stream)
                        }
                    }
                }
                val before = evaluated.size
                evaluate(neighbors, maxOf(1, remaining / (SEARCH_ROUNDS - round)))?.let { return it }
                if (evaluated.size == before) break
            }
            return null
        }

        val evaluated = linkedMapOf<List<Int>, EvaluatedTeam>()
        rankCandidates(eligibleByUnitId.keys, requireVanguard, ruleTeams, evaluated)?.let { return it }
        if (evaluated.isEmpty()) return noEligibleCandidate

        val ranked = evaluated.values.sortedWith(PRIORITY)
        val maxTeams = minOf(requestedBossTeamCount, eligibleByUnitId.size / teamSize)
        val safePacked = if (requestedBossTeamCount == 1) ranked.take(1) else packTeams(ranked, maxTeams)
        val supplements = linkedMapOf<List<Int>, EvaluatedTeam>()
        val supplementPacked = if (requireVanguard && safePacked.size < maxTeams) {
            val remainingIds = eligibleByUnitId.keys - safePacked.flatMap { it.unitIds }.toSet()
            if (remainingIds.size >= teamSize) {
                rankCandidates(remainingIds, enforceVanguard = false, ruleSeeds = emptyList(), evaluated = supplements)
                    ?.let { return it }
            }
            packTeams(supplements.values.sortedWith(PRIORITY), maxTeams - safePacked.size)
        } else emptyList()
        val packing = safePacked + supplementPacked
        val joint = if (requestedBossTeamCount > 1) {
            1.0 - packing.fold(1.0) { miss, team -> miss * (1.0 - team.value.pWin) }
        } else null
        val lead = packing.first().value
        val tier = BattleConfidenceTier.classify(joint ?: lead.pWin)
        val teams = packing.map { team -> team.unitIds.sortedWith(positionOrder).map(eligibleByUnitId::getValue) }
        return Outcome.Resolved(Result(
            teams,
            BattleModelAssessment(
                tier, lead.pWin, lead.winRemTime, lead.damageMean, evaluated.size + supplements.size,
                adopted = tier != BattleConfidenceTier.UNSAFE, jointWinProbability = joint,
                plannedTeams = teams.map { team -> team.map(LabyrinthRoleProfile::characterId) },
                teamWinProbabilities = packing.map { it.value.pWin },
            ),
        ))
    }

    private fun swaps(team: List<Int>, order: List<Int>, count: Int): Sequence<List<Int>> = sequence {
        val available = order.filterNot { it in team }
        val outgoing = combinations(team, count).toList()
        for (incoming in combinations(available, count)) {
            for (removed in outgoing) yield(team.filterNot { it in removed } + incoming)
        }
    }

    private fun combinations(ids: List<Int>, count: Int, start: Int = 0): Sequence<List<Int>> = sequence {
        if (count == 0) {
            yield(emptyList())
        } else {
            for (index in start..(ids.size - count)) {
                yieldAll(combinations(ids, count - 1, index + 1).map { listOf(ids[index]) + it })
            }
        }
    }

    /** Exact over the evaluated pool. The small depth limit makes pairwise conflicts sufficient. */
    private fun packTeams(ranked: List<EvaluatedTeam>, maxTeams: Int): List<EvaluatedTeam> {
        val conflicts = Array(ranked.size) { index ->
            BooleanArray(index) { prior -> ranked[index].unitIds.any { it in ranked[prior].unitIds } }
        }
        val chosen = IntArray(maxTeams)
        var best = emptyList<EvaluatedTeam>()
        var bestMiss = Double.POSITIVE_INFINITY
        var bestDamage = Double.NEGATIVE_INFINITY

        fun search(start: Int, size: Int, miss: Double, damage: Double) {
            if (size > 0 && (miss < bestMiss || (miss == bestMiss && damage > bestDamage))) {
                best = List(size) { ranked[chosen[it]] }
                bestMiss = miss
                bestDamage = damage
            }
            if (size == maxTeams) return
            for (index in start until ranked.size) {
                if ((0 until size).any { conflicts[index][chosen[it]] }) continue
                val value = ranked[index].value
                chosen[size] = index
                search(index + 1, size + 1, miss * (1.0 - value.pWin), damage + value.damageMean)
            }
        }
        search(0, 0, 1.0, 0.0)
        return best
    }

    private companion object {
        const val TEAM_SIZE = 5
        const val MAX_CANDIDATES = 256
        const val BATCH_SIZE = 64
        const val BEAM_SIZE = 6
        const val SEARCH_ROUNDS = 2
        val PRIORITY = compareByDescending<EvaluatedTeam> { it.value.pWin }
            .thenByDescending { it.value.winRemTime }
            .thenByDescending { it.value.damageMean }
    }
}
