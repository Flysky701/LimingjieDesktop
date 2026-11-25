package com.landosol.toolbox.labyrinth.kernel.planning

import com.landosol.toolbox.labyrinth.LabyrinthBossCatalog
import com.landosol.toolbox.labyrinth.LabyrinthBossEncounterCatalog
import com.landosol.toolbox.labyrinth.LabyrinthRelicMark
import com.landosol.toolbox.labyrinth.LabyrinthRoleDecisionContext
import com.landosol.toolbox.labyrinth.LabyrinthRoleProfile
import com.landosol.toolbox.labyrinth.LabyrinthTeamOptimizer
import com.landosol.toolbox.labyrinth.canonicalLabyrinthRoleId
import com.landosol.toolbox.labyrinth.hasEligibleVanguardAtActualFront
import com.landosol.toolbox.labyrinth.isEligibleBattleVanguard
import com.landosol.toolbox.labyrinth.kernel.strategy.BattleRosterUnit
import com.landosol.toolbox.labyrinth.kernel.strategy.BattleValue
import com.landosol.toolbox.labyrinth.kernel.strategy.BattleValueSource
import com.landosol.toolbox.labyrinth.kernel.strategy.ContextualBattleValueEstimator
import com.landosol.toolbox.labyrinth.kernel.strategy.buildBattleValueContext

class ModelBattlePassEstimator(
    private val estimator: ContextualBattleValueEstimator,
    private val profiles: Map<String, LabyrinthRoleProfile>,
    private val optimizer: LabyrinthTeamOptimizer? = null,
) : BattlePassEstimator {
    override fun estimate(state: PlanningState, exam: BattleExam): PassEstimate? {
        val owned = state.ownedCharacterIds.map(::canonicalLabyrinthRoleId).distinct().sorted()
        if (owned.size < TEAM_SIZE || owned.any { it !in profiles } || exam.maxTeams < 1) return null
        val encounter = if (exam.isBoss) {
            LabyrinthBossEncounterCatalog.forUnitId(LabyrinthBossCatalog.unitIdForQuest(exam.area, exam.questId))
        } else null
        val defense = state.markStacks[LabyrinthRelicMark.DEFENSE] ?: 0
        if (defense < 0) return null
        val rule = LabyrinthRoleDecisionContext(
            defenseMarkStacks = defense,
            targetCount = encounter?.targetCount ?: 1,
            optimizeBossVanguardSynergy = exam.isBoss,
            encounterStrategy = encounter,
            currentArea = exam.area,
            frontlineSurvivalGateEnabled = true,
        )
        val context = buildBattleValueContext(
            owned, profiles, exam.questId, exam.area, state.difficulty, rule,
            state.relicIds.sorted(), state.markStacks, state.alphaRupee, state.reviveAvailable,
        ) ?: return null
        val affinities = estimator.unitAffinities(context).orEmpty()
        var remaining = context.ownedUnits.sortedWith(
            compareByDescending<BattleRosterUnit> { affinities[it.unitId] ?: Double.NEGATIVE_INFINITY }
                .thenBy { it.unitId },
        )
        val teamCount = if (exam.isBoss) minOf(exam.maxTeams, owned.size / TEAM_SIZE, MAX_CANDIDATES) else 1
        val evaluated = linkedMapOf<List<Int>, BattleValue>()
        var miss = 1.0
        var expectedTeams = 0.0
        val probabilities = ArrayList<Double>(teamCount)
        for (index in 0 until teamCount) {
            val roundRule = rule.copy(frontlineSurvivalGateEnabled = index == 0)
            val budget = (MAX_CANDIDATES - evaluated.size) / (teamCount - index)
            val teams = candidates(remaining, roundRule, budget, evaluated.keys)
            if (teams.isNotEmpty()) {
                val estimate = estimator.estimateWithSource(context, teams)
                if (estimate.source != BattleValueSource.MODEL || estimate.values.size != teams.size ||
                    estimate.values.any { it.pWin !in 0.0..1.0 }
                ) return null
                teams.zip(estimate.values).forEach { (team, value) -> evaluated[team] = value }
            }
            val available = remaining.mapTo(hashSetOf()) { it.unitId }
            val best = evaluated.entries.asSequence().filter { (team, _) -> team.all { it in available } }.maxWithOrNull(
                compareBy<Map.Entry<List<Int>, BattleValue>> { it.value.pWin }
                    .thenBy { it.value.winRemTime }
                    .thenBy { it.value.damageMean },
            ) ?: return null
            val p = best.value.pWin
            probabilities += p
            expectedTeams += miss
            miss *= 1.0 - p
            val selected = best.key.toSet()
            remaining = remaining.filterNot { it.unitId in selected }
        }
        return PassEstimate(1.0 - miss, expectedTeams, probabilities)
    }

    private fun candidates(
        ranked: List<BattleRosterUnit>,
        rule: LabyrinthRoleDecisionContext,
        budget: Int,
        evaluated: Set<List<Int>>,
    ): List<List<Int>> {
        val gate = optimizer?.scoringConfig?.vanguardGate(rule)
        fun legal(team: List<BattleRosterUnit>) = !rule.frontlineSurvivalGateEnabled ||
            team.map { it.profile }.hasEligibleVanguardAtActualFront(rule, gate)

        val vanguardSeeds = sequence {
            if (rule.frontlineSurvivalGateEnabled) {
                for (front in ranked) {
                    val position = front.profile.position ?: continue
                    if (!front.profile.isEligibleBattleVanguard(rule, gate)) continue
                    val followers = ranked.asSequence().filter {
                        val other = it.profile.position
                        it != front && other != null &&
                            (other > position || (other == position && it.profile.isEligibleBattleVanguard(rule, gate)))
                    }.take(TEAM_SIZE - 1).toList()
                    if (followers.size == TEAM_SIZE - 1) yield(listOf(front) + followers)
                }
            }
        }
        val lead = ranked.take(TEAM_SIZE).takeIf(::legal) ?: vanguardSeeds.firstOrNull() ?: return emptyList()
        val seeds = sequence {
            yield(lead)
            if (optimizer != null) {
                val shortlist = (lead + ranked).distinct().take(RULE_SEED_ROSTER_SIZE)
                val byCharacter = shortlist.associateBy { it.profile.characterId }
                val formations = optimizer.rankedBattleFormations(shortlist.map { it.profile }, rule)
                for (formation in formations.take(MAX_RULE_SEEDS)) {
                    yield(formation.members.map { byCharacter.getValue(it.characterId) })
                }
            }
            yieldAll(ranked.chunked(TEAM_SIZE).asSequence().filter { it.size == TEAM_SIZE })
            yieldAll(vanguardSeeds)
            for (talent in ranked.map { it.talentId }.distinct().filter { it != 0 }) {
                val (same, other) = ranked.partition { it.talentId == talent }
                yield((same + other).take(TEAM_SIZE))
            }
            for (incoming in ranked) {
                if (incoming in lead) continue
                for (outgoing in lead.asReversed()) yield(lead - outgoing + incoming)
            }
        }
        return seeds.filter(::legal).map { team -> team.map { it.unitId }.sorted() }
            .filterNot { it in evaluated }.distinct().take(budget).toList()
    }

    private companion object {
        const val TEAM_SIZE = 5
        const val MAX_CANDIDATES = 16
        const val MAX_RULE_SEEDS = 3
        const val RULE_SEED_ROSTER_SIZE = 8
    }
}
