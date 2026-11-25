package com.landosol.toolbox.labyrinth.kernel.strategy

import com.landosol.toolbox.labyrinth.LabyrinthRoleProfile
import com.landosol.toolbox.labyrinth.LabyrinthTeamOptimizer
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

    fun search(
        valueContext: BattleValueContext,
        eligibleProfiles: List<LabyrinthRoleProfile>,
        ruleTeams: List<List<String>>,
        failedTeamSignatures: Set<String>,
        requestedBossTeamCount: Int,
        supplementLead: Boolean,
    ): Result? {
        val teamSize = minOf(TEAM_SIZE, eligibleProfiles.size)
        if (teamSize == 0) return null
        val ownedByCharacter = valueContext.ownedUnits.associateBy { it.profile.characterId }
        val eligibleByUnitId = eligibleProfiles.associateBy {
            ownedByCharacter[it.characterId]?.unitId ?: return null
        }
        val context = valueContext.ruleContext
        val requireVanguard = context.frontlineSurvivalGateEnabled && !supplementLead
        val positionOrder = compareBy<Int> { eligibleByUnitId.getValue(it).position ?: Int.MAX_VALUE }
            .thenBy { eligibleByUnitId.getValue(it).characterId }
        val byPosition = eligibleByUnitId.keys.sortedWith(positionOrder)
        val affinities = estimator.unitAffinities(valueContext).orEmpty()
        val byAffinity = eligibleByUnitId.keys.sortedWith(
            compareByDescending<Int> { affinities[it] ?: Double.NEGATIVE_INFINITY }.thenBy { it },
        )
        val talentByUnitId = valueContext.ownedUnits.associate { it.unitId to it.talentId }
        val evaluated = linkedMapOf<List<Int>, EvaluatedTeam>()

        fun evaluate(candidates: Sequence<List<Int>>, budget: Int): Boolean {
            val pending = linkedSetOf<List<Int>>()
            for (candidate in candidates) {
                if (candidate.size != teamSize || candidate.distinct().size != teamSize) continue
                if (candidate.any { it !in eligibleByUnitId }) continue
                val ids = candidate.sorted()
                if (ids in evaluated || ids in pending) continue
                if (failedTeamSignatures.isNotEmpty() &&
                    labyrinthBattleTeamSignature(ids.map { eligibleByUnitId.getValue(it).characterId }) in failedTeamSignatures
                ) continue
                if (requireVanguard) {
                    val front = ids.minWith(positionOrder)
                    if (!eligibleByUnitId.getValue(front).isEligibleBattleVanguard(context)) continue
                }
                pending += ids
                if (pending.size == budget) break
            }
            for (batch in pending.chunked(BATCH_SIZE)) {
                val estimate = estimator.estimateWithSource(valueContext, batch)
                if (estimate.source != BattleValueSource.MODEL || estimate.values.size != batch.size) return false
                batch.zip(estimate.values).forEach { (ids, value) -> evaluated[ids] = EvaluatedTeam(ids, value) }
            }
            return true
        }

        val seeds = sequence {
            yieldAll(ruleTeams.asSequence().map { ids -> ids.mapNotNull { ownedByCharacter[it]?.unitId } })
            val optimizerContext = context.copy(frontlineSurvivalGateEnabled = requireVanguard)
            val optimizerSeeds = optimizer?.rankedBattleFormations(eligibleProfiles, optimizerContext, failedTeamSignatures)
                .orEmpty()
            for (team in optimizerSeeds) {
                yield(team.members.map { ownedByCharacter.getValue(it.characterId).unitId })
            }
            if (affinities.isNotEmpty()) {
                yieldAll(byAffinity.chunked(teamSize).asSequence())
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
        if (!evaluate(seeds, MAX_CANDIDATES)) return null
        if (evaluated.isEmpty()) {
            val fallback = if (requireVanguard) sequence {
                for ((index, front) in byPosition.withIndex()) {
                    if (!eligibleByUnitId.getValue(front).isEligibleBattleVanguard(context)) continue
                    yieldAll(combinations(byPosition.drop(index + 1), teamSize - 1).map { listOf(front) + it })
                }
            } else combinations(byAffinity, teamSize)
            if (!evaluate(fallback, 1) || evaluated.isEmpty()) return null
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
            if (!evaluate(neighbors, maxOf(1, remaining / (SEARCH_ROUNDS - round)))) return null
            if (evaluated.size == before) break
        }

        val ranked = evaluated.values.sortedWith(PRIORITY)
        val maxTeams = minOf(requestedBossTeamCount, eligibleByUnitId.size / teamSize)
        val packing = if (requestedBossTeamCount == 1) ranked.take(1) else packTeams(ranked, maxTeams)
        val joint = if (requestedBossTeamCount > 1) {
            1.0 - packing.fold(1.0) { miss, team -> miss * (1.0 - team.value.pWin) }
        } else null
        val lead = packing.first().value
        val tier = BattleConfidenceTier.classify(joint ?: lead.pWin)
        return Result(
            packing.map { team -> team.unitIds.sortedWith(positionOrder).map(eligibleByUnitId::getValue) },
            BattleModelAssessment(tier, lead.pWin, lead.winRemTime, lead.damageMean, evaluated.size,
                adopted = tier != BattleConfidenceTier.UNSAFE, jointWinProbability = joint),
        )
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
