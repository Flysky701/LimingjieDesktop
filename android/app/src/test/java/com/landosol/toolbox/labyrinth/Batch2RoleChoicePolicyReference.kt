package com.landosol.toolbox.labyrinth

// Frozen verbatim from batch2, except class/private helper names needed to coexist.
// Source class SHA-256 (UTF-8, LF, no trailing newline): dbeedaff7a41c772f603b55ac42bd6afcbb6a2e4d9bc11dd9ed80322218621e0
/** Evaluates three known characters; it is deliberately unrelated to connect-attribute choice. */
class Batch2RoleChoicePolicyReference(
    private val optimizer: LabyrinthTeamOptimizer,
    private val config: LabyrinthRoleChoiceConfig = LabyrinthRoleChoiceConfig(),
) {
    fun chooseOneRole(
        candidateIds: List<String>,
        acquiredCharacterIds: Set<String>,
        profiles: Map<String, LabyrinthRoleProfile>,
        context: LabyrinthRoleDecisionContext,
        requireStrictProfiles: Boolean = config.requireStrictProfiles,
    ): LabyrinthOneRoleDecision {
        if (candidateIds.isEmpty() || candidateIds.distinct().size != candidateIds.size) {
            return LabyrinthOneRoleDecision.Unavailable("候选角色必须稳定识别且互不重复")
        }
        // The game does not offer an already-owned role in a three-card reward. A collision is
        // therefore stale local roster evidence, not a reason to strand the run. Exclude the
        // visible candidates from the *scoring* roster so none is counted twice, but still rank
        // all three visible cards normally. The caller records the discrepancy for diagnosis.
        val scoringAcquiredIds = acquiredCharacterIds - candidateIds.toSet()
        val requiredIds = candidateIds.toSet() + scoringAcquiredIds
        val missing = requiredIds.filterNot(profiles::containsKey).sorted()
        if (missing.isNotEmpty()) {
            return LabyrinthOneRoleDecision.Unavailable(
                reason = "角色资料不完整，保持等待而不盲选：${missing.joinToString()}",
                missingProfileIds = missing,
            )
        }
        val incomplete = requiredIds.filterNot { profiles.getValue(it).isStrictDecisionReady }.sorted()
        if (requireStrictProfiles && incomplete.isNotEmpty()) {
            return LabyrinthOneRoleDecision.Unavailable(
                reason = "角色资料缺少严格决策所需的属性/职阶/物法/站位，保持等待：${incomplete.joinToString()}",
                incompleteProfileIds = incomplete,
            )
        }
        val acquired = scoringAcquiredIds.map(profiles::getValue)
        val rankings = candidateIds.map { candidateId ->
            val candidate = profiles.getValue(candidateId)
            val pool = acquired + candidate
            val placement = requireNotNull(bestCandidatePlacementPlan(pool, candidate, context))
            val playerScore = candidate.effectiveUserScore
            val playerWeight = optimizer.scoringConfig.playerWeight
            LabyrinthRoleCandidateRanking(
                characterId = candidateId,
                displayName = candidate.displayName,
                score = if (playerScore == null) {
                    placement.systemScore
                } else {
                    playerScore * playerWeight + placement.systemScore * (1.0 - playerWeight)
                },
                playerScore = playerScore,
                systemScore = placement.systemScore,
                candidateLayer = placement.candidateLayer,
                firstTeam = placement.teams.first(),
                candidateTeam = placement.candidateTeam,
                secondTeam = placement.teams.getOrNull(1),
                thirdTeam = placement.teams.getOrNull(2),
            )
        }.sortedWith(
            compareByDescending<LabyrinthRoleCandidateRanking> { it.score }
                .thenByDescending { profiles.getValue(it.characterId).effectiveUserScore.batch2OrZero() }
                .thenBy { candidateIds.indexOf(it.characterId) },
        )
        val best = rankings.first()
        val chosen = profiles.getValue(best.characterId)
        val reasons = buildList {
            val conflicted = candidateIds.filter(acquiredCharacterIds::contains)
            if (conflicted.isNotEmpty()) {
                add("画面候选与本地已获得名单冲突（${conflicted.joinToString()}）；按实际三选一画面重新评分")
            }
            add("${chosen.displayName}的最佳部署落点为第${best.candidateLayer + 1}队；第一队保持当前池最优组合")
            chosen.attribute?.let { attribute ->
                val sameAttribute = best.candidateTeam.attributeCounts[attribute]
                val bonus = best.candidateTeam.activeAttributeBonuses[attribute]
                if (bonus != null && sameAttribute != null) {
                    add("与队内${attribute.label}属性组成${sameAttribute}人档，角色自身及同属性角色获得${(bonus * 100).toInt()}%伤害")
                }
            }
            addAll(best.candidateTeam.reasons.filterNot { it.startsWith("预计") }.take(3))
            val runnerUp = rankings.getOrNull(1)
            if (runnerUp != null) {
                add("综合边际评分${batch2FormatScore(best.score)}，高于${runnerUp.displayName}的${batch2FormatScore(runnerUp.score)}")
            }
        }
        return LabyrinthOneRoleDecision.Ready(chosen, rankings, reasons)
    }

    private fun bestCandidatePlacementPlan(
        pool: List<LabyrinthRoleProfile>,
        candidate: LabyrinthRoleProfile,
        context: LabyrinthRoleDecisionContext,
    ): CandidatePlacementPlan? {
        val plans = buildList {
            config.effectiveCandidateTeamWeights().indices.forEach { candidateLayer ->
                if (pool.distinctBy(LabyrinthRoleProfile::characterId).size - 1 < candidateLayer * TEAM_SIZE) {
                    return@forEach
                }
                candidatePlacementPlan(pool, candidate, context, candidateLayer)?.let(::add)
            }
        }
        return plans.maxWithOrNull(
            compareBy<CandidatePlacementPlan> { it.systemScore }
                .thenBy { -it.candidateLayer },
        )
    }

    private fun candidatePlacementPlan(
        pool: List<LabyrinthRoleProfile>,
        candidate: LabyrinthRoleProfile,
        context: LabyrinthRoleDecisionContext,
        candidateLayer: Int,
    ): CandidatePlacementPlan? {
        var remaining = pool.distinctBy(LabyrinthRoleProfile::characterId)
        val teams = mutableListOf<LabyrinthTeamEvaluation>()
        config.effectiveCandidateTeamWeights().indices.forEach { layer ->
            if (layer > 0 && remaining.size < TEAM_SIZE) return@forEach
            val teamPool: List<LabyrinthRoleProfile>
            val requiredId: String?
            when {
                layer < candidateLayer -> {
                    teamPool = remaining.filterNot { it.characterId == candidate.characterId }
                    if (teamPool.size < TEAM_SIZE) return null
                    requiredId = null
                }
                layer == candidateLayer -> {
                    if (remaining.none { it.characterId == candidate.characterId }) return null
                    teamPool = remaining
                    requiredId = candidate.characterId
                }
                else -> {
                    teamPool = remaining
                    requiredId = null
                }
            }
            val team = optimizer.bestSystemFormation(teamPool, context, requiredId) ?: return null
            teams += team
            val used = team.members.map(LabyrinthRoleProfile::characterId).toSet()
            remaining = remaining.filterNot { it.characterId in used }
        }
        val candidateTeam = teams.getOrNull(candidateLayer)
            ?.takeIf { team -> team.members.any { it.characterId == candidate.characterId } }
            ?: return null
        val weights = config.effectiveCandidateTeamWeights()
        val weightSum = teams.indices.sumOf { weights[it] }
        val systemScore = teams.indices.sumOf { teams[it].systemScore * weights[it] } / weightSum
        return CandidatePlacementPlan(candidateLayer, teams, candidateTeam, systemScore)
    }

    private data class CandidatePlacementPlan(
        val candidateLayer: Int,
        val teams: List<LabyrinthTeamEvaluation>,
        val candidateTeam: LabyrinthTeamEvaluation,
        val systemScore: Double,
    )

    private companion object {
        const val TEAM_SIZE = 5

        /**
         * Distinct-vanguard first teams tried when the beam holds no disjoint set. Each seed costs
         * one bounded search per follow-up team, so this stays small enough for on-device planning.
         */
        const val MAX_RESIDUAL_FIRST_TEAM_SEEDS = 6
    }
}

private fun Double?.batch2OrZero(): Double = this ?: 0.0

private fun batch2FormatScore(value: Double): String = "%.2f".format(value)
