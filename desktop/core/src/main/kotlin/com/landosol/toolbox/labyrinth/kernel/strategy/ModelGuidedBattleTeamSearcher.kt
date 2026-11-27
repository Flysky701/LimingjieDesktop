package com.landosol.toolbox.labyrinth.kernel.strategy

import com.landosol.toolbox.labyrinth.LabyrinthRoleProfile
import com.landosol.toolbox.labyrinth.LabyrinthTeamOptimizer
import com.landosol.toolbox.labyrinth.canonicalLabyrinthRoleId
import com.landosol.toolbox.labyrinth.formatBattleWinProbability
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

    private data class EvaluatedTeam(val unitIds: List<Int>, val value: BattleValue, val utility: Double)

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
        autoDowngradeBossMaxTeams: Int = 1,
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
        val isBoss = valueContext.isBossBattle || requestedBossTeamCount > 1 || context.optimizeBossVanguardSynergy
        val teamPriority = compareByDescending<EvaluatedTeam> { it.utility }
            .thenByDescending { it.value.pWin }
            .thenByDescending { it.value.damageMean }
            .thenByDescending { it.value.winRemTime }
        val requireVanguard = context.frontlineSurvivalGateEnabled && !supplementLead
        val vanguardGate = optimizer?.scoringConfig?.vanguardGate(context)
        val positionOrder = compareBy<Int> { eligibleByUnitId.getValue(it).position ?: Int.MAX_VALUE }
            .thenBy { eligibleByUnitId.getValue(it).characterId }
        val affinities = estimator.unitAffinities(valueContext).orEmpty()
        val affinityOrder = compareByDescending<Int> { affinities[it] ?: Double.NEGATIVE_INFINITY }.thenBy { it }
        val talentByUnitId = valueContext.ownedUnits.associate { it.unitId to it.talentId }
        val canonicalOwnedByChar = valueContext.ownedUnits.associate {
            canonicalLabyrinthRoleId(it.profile.characterId) to it.unitId
        }
        val failedUnitIdSets = failedTeamSignatures.mapNotNull { signature ->
            val unitSet = signature.split(',').mapNotNull { canonicalOwnedByChar[it.trim()] }.toSet()
            unitSet.takeIf { it.isNotEmpty() }
        }
        fun maxFailedOverlap(ids: List<Int>): Int =
            if (failedUnitIdSets.isEmpty()) 0 else failedUnitIdSets.maxOf { failed -> ids.count { it in failed } }
        val ruleSeeds = ruleTeams.mapNotNull { team ->
            team.map { ownedByCharacter[it]?.unitId ?: return@mapNotNull null }.sorted()
        }

        fun rankCandidates(
            availableIds: Set<Int>,
            enforceVanguard: Boolean,
            ruleSeeds: List<List<Int>>,
            evaluated: MutableMap<List<Int>, EvaluatedTeam>,
        ): Outcome.Fallback? {
            val byPosition = availableIds.sortedWith(positionOrder)
            val byAffinity = availableIds.sortedWith(affinityOrder)
            val byRuleScore = byAffinity.sortedByDescending {
                val profile = eligibleByUnitId.getValue(it)
                profile.effectiveUserScore ?: maxOf(profile.physicalDamagePotential ?: 0.0, profile.magicDamagePotential ?: 0.0)
            }

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
                    batch.zip(estimate.values).forEach { (ids, value) ->
                        val utility = battleTeamUtility(value, valueContext.strategyStyle, isBoss, valueContext.reviveAvailable)
                        evaluated[ids] = EvaluatedTeam(ids, value, utility)
                    }
                }
                return null
            }

            val seeds = sequence {
                yieldAll(ruleSeeds)
                val optimizerContext = context.copy(frontlineSurvivalGateEnabled = enforceVanguard)
                val optimizerSeeds = optimizer?.rankedBattleFormations(
                    availableIds.map(eligibleByUnitId::getValue), optimizerContext, failedTeamSignatures,
                ).orEmpty()
                for (team in optimizerSeeds.take(MAX_RULE_SEEDS)) {
                    yield(team.members.map { ownedByCharacter.getValue(it.characterId).unitId })
                }
                yieldAll(byAffinity.chunked(teamSize).asSequence())
                val seedOrders = listOf(byAffinity, byRuleScore)
                for (talent in 1..5) {
                    for (order in seedOrders) {
                        val (same, other) = order.partition { talentByUnitId[it] == talent }
                        if (same.isNotEmpty()) yield((same + other).take(teamSize))
                    }
                }
                for (order in seedOrders) {
                    val (magic, physical) = order.partition {
                        val profile = eligibleByUnitId.getValue(it)
                        (profile.magicDamagePotential ?: 0.0) > (profile.physicalDamagePotential ?: 0.0)
                    }
                    if (magic.isNotEmpty()) yield((magic + physical).take(teamSize))
                    if (physical.isNotEmpty()) yield((physical + magic).take(teamSize))
                }
                val front = byPosition.take(maxOf(1, byPosition.size / 3)).toSet()
                val (vanguard, rear) = byAffinity.partition { it in front }
                for (count in 1..2) {
                    yield((vanguard.take(count) + rear + vanguard.drop(count)).take(teamSize))
                }
                if (failedUnitIdSets.isNotEmpty() && availableIds.size >= teamSize + 2) {
                    val latestFailed = failedUnitIdSets.last()
                    val nonFailedByAffinity = byAffinity.filter { it !in latestFailed }
                    if (nonFailedByAffinity.size >= teamSize) {
                        yield(nonFailedByAffinity.take(teamSize))
                    }
                    for (order in seedOrders) {
                        val retained = order.filter { it in latestFailed }
                        val fresh = order.filter { it !in latestFailed }
                        if (fresh.size >= 2) {
                            yield((retained.take((teamSize - 2).coerceAtLeast(0)) + fresh).take(teamSize))
                        }
                        if (fresh.size >= 3) {
                            yield((retained.take((teamSize - 3).coerceAtLeast(0)) + fresh).take(teamSize))
                        }
                    }
                    val latestFailedList = byAffinity.filter { it in latestFailed }
                    for (removedPair in combinations(latestFailedList, minOf(2, latestFailedList.size))) {
                        val kept = latestFailedList.filterNot { it in removedPair }
                        if (kept.size + nonFailedByAffinity.size >= teamSize) {
                            yield((kept + nonFailedByAffinity).take(teamSize))
                        }
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
                val roundBudget = maxOf(1, remaining / (SEARCH_ROUNDS - round))
                val beam = evaluated.values.sortedWith(teamPriority).take(BEAM_SIZE)
                val before = evaluated.size
                if (failedUnitIdSets.isNotEmpty() && availableIds.size >= teamSize + 2) {
                    val diverseNeighbors = sequence {
                        val streams = ArrayDeque(beam.map { team ->
                            swaps(team.unitIds, byAffinity, 2).filter { maxFailedOverlap(it) <= 3 }.iterator()
                        })
                        while (streams.isNotEmpty()) {
                            val stream = streams.removeFirst()
                            if (stream.hasNext()) {
                                yield(stream.next())
                                streams.addLast(stream)
                            }
                        }
                    }
                    val diverseBudget = minOf(roundBudget, maxOf(12, roundBudget / 2))
                    evaluate(diverseNeighbors, diverseBudget)?.let { return it }
                }
                val stillRemaining = MAX_CANDIDATES - evaluated.size
                if (stillRemaining == 0) break
                val normalBudget = maxOf(1, (roundBudget - (evaluated.size - before)).coerceAtLeast(1))
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
                evaluate(neighbors, minOf(stillRemaining, normalBudget))?.let { return it }
                if (evaluated.size == before) break
            }
            return null
        }

        val evaluated = linkedMapOf<List<Int>, EvaluatedTeam>()
        rankCandidates(eligibleByUnitId.keys, requireVanguard, ruleSeeds, evaluated)?.let { return it }
        if (evaluated.isEmpty()) return noEligibleCandidate

        val ranked = evaluated.values.sortedWith(teamPriority)
        val rawLeadSolo = ranked.first()
        val fullTeamSlots = eligibleByUnitId.size / TEAM_SIZE
        val autoDowngraded = requestedBossTeamCount == 1 && autoDowngradeBossMaxTeams > 1 &&
            rawLeadSolo.value.pWin < valueContext.strategyStyle.bossSoloMinPWin && fullTeamSlots >= 2
        val effectiveTeamCount = minOf(
            if (autoDowngraded) autoDowngradeBossMaxTeams else requestedBossTeamCount,
            fullTeamSlots,
        )
        val leadSolo = if (effectiveTeamCount == 1 && failedUnitIdSets.isNotEmpty() && maxFailedOverlap(rawLeadSolo.unitIds) >= 4) {
            val bestDiverse = ranked.firstOrNull { maxFailedOverlap(it.unitIds) <= 3 }
            val acceptDiverse = bestDiverse != null && when {
                failedUnitIdSets.size >= 2 ->
                    bestDiverse.value.pWin >= BattleConfidenceTier.MARGINAL_THRESHOLD ||
                        rawLeadSolo.value.pWin - bestDiverse.value.pWin <= 0.20
                else ->
                    rawLeadSolo.value.pWin - bestDiverse.value.pWin <= 0.08
            }
            if (acceptDiverse) requireNotNull(bestDiverse) else rawLeadSolo
        } else {
            rawLeadSolo
        }
        val safePacked = if (effectiveTeamCount > 1) {
            packTeams(ranked, effectiveTeamCount, teamPriority)
        } else listOf(leadSolo)
        val supplements = linkedMapOf<List<Int>, EvaluatedTeam>()
        val supplementPacked = if (safePacked.size < effectiveTeamCount) {
            val remainingIds = eligibleByUnitId.keys - safePacked.flatMap { it.unitIds }.toSet()
            if (remainingIds.size >= teamSize) {
                rankCandidates(remainingIds, enforceVanguard = false, ruleSeeds = emptyList(), evaluated = supplements)
                    ?.let { return it }
            }
            packTeams(supplements.values, effectiveTeamCount - safePacked.size, teamPriority)
        } else emptyList()
        val packing = safePacked + supplementPacked
        val joint = if (packing.size >= 2) combinedMultiTeamPassProb(packing.map { it.value }) else null
        val lead = packing.first().value
        val effectiveProb = joint ?: lead.pWin
        val tier = BattleConfidenceTier.classify(effectiveProb)
        val ruleValues = mutableListOf<BattleValue>()
        if (ruleSeeds.isNotEmpty()) {
            val usedRuleUnits = hashSetOf<Int>()
            val ruleTeamLimit = effectiveTeamCount.coerceAtLeast(1)
            for (ids in ruleSeeds) {
                if (ruleValues.size == ruleTeamLimit) break
                val team = evaluated[ids] ?: supplements[ids] ?: continue
                if (ids.any { it in usedRuleUnits }) continue
                usedRuleUnits += ids
                ruleValues += team.value
            }
        }
        val ruleLeadProb = ruleValues.firstOrNull()?.pWin
        val adoptionNote = if (tier == BattleConfidenceTier.UNSAFE && ruleValues.isNotEmpty()) {
            val ruleProb = combinedMultiTeamPassProb(ruleValues)
            val ruleTotalDamage = ruleValues.sumOf { it.damageMean }
            val nnTotalDamage = packing.sumOf { it.value.damageMean }
            if (effectiveProb >= ruleProb + 0.15 ||
                (packing.size >= 2 && nnTotalDamage >= ruleTotalDamage + 0.25)) {
                "NN低胜率优选（${formatBattleWinProbability(effectiveProb)} 优于规则队 ${formatBattleWinProbability(ruleProb)}）"
            } else null
        } else null
        val teams = packing.map { team -> team.unitIds.sortedWith(positionOrder).map(eligibleByUnitId::getValue) }
        return Outcome.Resolved(Result(
            teams,
            BattleModelAssessment(
                tier, lead.pWin, lead.winRemTime, lead.damageMean, evaluated.size + supplements.size,
                adopted = tier != BattleConfidenceTier.UNSAFE || adoptionNote != null, jointWinProbability = joint,
                plannedTeams = teams.map { team -> team.map(LabyrinthRoleProfile::characterId) },
                teamWinProbabilities = packing.map { it.value.pWin },
                autoDowngradedToMultiTeam = autoDowngraded,
                adoptionNote = adoptionNote,
                ruleLeadWinProbability = ruleLeadProb,
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

    /** Exact over the shortlisted pool. The small depth limit makes pairwise conflicts sufficient. */
    private fun packTeams(
        evaluatedPool: Collection<EvaluatedTeam>,
        maxTeams: Int,
        teamPriority: Comparator<EvaluatedTeam>,
    ): List<EvaluatedTeam> {
        // Visit teams in opening order so the first selected team's utility breaks packing ties.
        val topByUtility = evaluatedPool.sortedWith(teamPriority).take(PACK_POOL_LIMIT)
        val topByDamage = evaluatedPool.sortedByDescending { it.value.damageMean }.take(PACK_POOL_LIMIT)
        val pool = (topByUtility + topByDamage).distinctBy { it.unitIds }
            .sortedWith(compareByDescending<EvaluatedTeam> { it.value.pWin }.thenByDescending { it.value.damageMean })
        val conflicts = Array(pool.size) { index ->
            BooleanArray(index) { prior -> pool[index].unitIds.any { it in pool[prior].unitIds } }
        }
        val chosen = IntArray(maxTeams)
        val chosenValues = ArrayList<BattleValue>(maxTeams)
        var best = emptyList<EvaluatedTeam>()
        var bestProbability = Double.NEGATIVE_INFINITY
        var bestDamage = Double.NEGATIVE_INFINITY
        var bestLeadUtility = Double.NEGATIVE_INFINITY

        fun search(start: Int, damage: Double) {
            val size = chosenValues.size
            if (size > 0) {
                val probability = combinedMultiTeamPassProb(chosenValues)
                val leadUtility = pool[chosen[0]].utility
                if (probability > bestProbability ||
                    (probability == bestProbability && (damage > bestDamage ||
                        (damage == bestDamage && leadUtility > bestLeadUtility)))) {
                    best = List(size) { pool[chosen[it]] }
                    bestProbability = probability
                    bestDamage = damage
                    bestLeadUtility = leadUtility
                }
            }
            if (size == maxTeams) return
            for (index in start until pool.size) {
                if ((0 until size).any { conflicts[index][chosen[it]] }) continue
                val value = pool[index].value
                chosen[size] = index
                chosenValues += value
                search(index + 1, damage + value.damageMean)
                chosenValues.removeAt(size)
            }
        }
        search(0, 0.0)
        return best
    }

    private companion object {
        const val TEAM_SIZE = 5
        const val MAX_CANDIDATES = 384
        const val MAX_RULE_SEEDS = 32
        const val BATCH_SIZE = 64
        const val BEAM_SIZE = 8
        const val SEARCH_ROUNDS = 3
        const val PACK_POOL_LIMIT = 48
    }
}
