package com.landosol.toolbox.labyrinth

import java.util.PriorityQueue
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService

/** Frozen batch3 optimizer and chooseOneRole algorithm. Only class/private helper/constant
 * names are substituted. Scorer, model and non-private formation gates remain shared unchanged.
 * Executor is the SAME production fixed pool via test-only reflection (no new worker pool).
 */
private const val BATCH3_REFERENCE_ROLE_SEARCH_THREADS = 4
private const val BATCH3_REFERENCE_PARALLEL_MIN_COMBINATIONS = 512
private val BATCH3_REFERENCE_ROLE_SEARCH_EXECUTOR: ExecutorService = batch4ProductionExecutor()

internal class Batch3TeamOptimizerReference(
    private val scorer: LabyrinthTeamScorer,
    /**
     * Worker threads for the combination walk. Deliberately not tied to the CPU count: the game
     * and this app share one virtual machine, and saturating every core makes the game drop
     * frames, which lengthens the run more than the search it was meant to speed up. Set to 1 to
     * force the serial path.
     */
    private val searchThreads: Int = BATCH3_REFERENCE_ROLE_SEARCH_THREADS,
) {
    val scoringConfig: LabyrinthTeamScoringConfig
        get() = scorer.config

    fun bestFormation(
        roster: Collection<LabyrinthRoleProfile>,
        context: LabyrinthRoleDecisionContext,
        requiredCharacterId: String? = null,
    ): LabyrinthTeamEvaluation? = rankedFormationsInternal(
        roster, context, requiredCharacterId, systemOnly = false, limit = 1,
        requireFrontmostTank = false,
    ).firstOrNull()

    /**
     * Battle-only formation search. Reward/event marginal-value calculations deliberately keep
     * using [bestFormation], so this hard tank rule does not rewrite acquisition preferences.
     */
    /**
     * Best battle formation that contains every id in [requiredIds]. The bounded search pool
     * keeps the required roles in, and only combinations holding all of them are scored, so a
     * guide-mandated or official effective-effect role cannot be out-scored by a generic
     * high-rated pick. Returns null when no valid formation includes them (no compatible tank,
     * position clash).
     */
    fun bestBattleFormationIncluding(
        roster: Collection<LabyrinthRoleProfile>,
        context: LabyrinthRoleDecisionContext,
        requiredIds: Set<String>,
    ): LabyrinthTeamEvaluation? {
        if (requiredIds.isEmpty()) return bestBattleFormation(roster, context)
        val unique = roster.distinctBy(LabyrinthRoleProfile::characterId)
        if (!requiredIds.all { id -> unique.any { it.characterId == id } }) return null
        if (context.preferSingleDamageSystem && context.encounterStrategy?.vanguardIsolationRadius == null) {
            val cohesive = rankedFormationsInternal(
                roster = unique, context = context, requiredCharacterId = null, systemOnly = false,
                limit = 1, requireFrontmostTank = true, forbidMixedDamageSystem = true,
                requiredCharacterIds = requiredIds,
            ).firstOrNull()
            if (cohesive != null) return cohesive
        }
        return rankedFormationsInternal(
            roster = unique, context = context, requiredCharacterId = null, systemOnly = false,
            limit = 1, requireFrontmostTank = true, requiredCharacterIds = requiredIds,
        ).firstOrNull()
    }

    fun bestBattleFormation(
        roster: Collection<LabyrinthRoleProfile>,
        context: LabyrinthRoleDecisionContext,
    ): LabyrinthTeamEvaluation? {
        if (context.preferSingleDamageSystem && context.encounterStrategy?.vanguardIsolationRadius == null) {
            val cohesive = rankedFormationsInternal(
                roster = roster,
                context = context,
                requiredCharacterId = null,
                systemOnly = false,
                limit = 1,
                requireFrontmostTank = true,
                forbidMixedDamageSystem = true,
            ).firstOrNull()
            if (cohesive != null) return cohesive
        }
        return rankedFormationsInternal(
            roster = roster,
            context = context,
            requiredCharacterId = null,
            systemOnly = false,
            limit = 1,
            requireFrontmostTank = true,
        ).firstOrNull()
    }

    /**
     * Best formation with the frontmost-tank gate switched off. Used only to fill Boss follow-up
     * slots that would otherwise stay empty: an unsafe team that lands a few hits is strictly
     * better than no team when the Boss is down to a sliver.
     */
    fun bestSupplementFormation(
        roster: Collection<LabyrinthRoleProfile>,
        context: LabyrinthRoleDecisionContext,
    ): LabyrinthTeamEvaluation? = rankedFormationsInternal(
        roster = roster,
        context = context,
        requiredCharacterId = null,
        systemOnly = false,
        limit = 1,
        requireFrontmostTank = false,
    ).firstOrNull()

    fun bestSystemFormation(
        roster: Collection<LabyrinthRoleProfile>,
        context: LabyrinthRoleDecisionContext,
        requiredCharacterId: String? = null,
    ): LabyrinthTeamEvaluation? = rankedFormationsInternal(
        roster = roster,
        context = context,
        requiredCharacterId = requiredCharacterId,
        systemOnly = true,
        limit = 1,
        requireFrontmostTank = false,
    ).firstOrNull()

    fun rankedFormations(
        roster: Collection<LabyrinthRoleProfile>,
        context: LabyrinthRoleDecisionContext,
        requiredCharacterId: String? = null,
    ): List<LabyrinthTeamEvaluation> = rankedFormationsInternal(
        roster = roster,
        context = context,
        requiredCharacterId = requiredCharacterId,
        systemOnly = false,
        requireFrontmostTank = false,
    )

    fun rankedBattleFormations(
        roster: Collection<LabyrinthRoleProfile>,
        context: LabyrinthRoleDecisionContext,
    ): List<LabyrinthTeamEvaluation> = rankedFormationsInternal(
        roster = roster,
        context = context,
        requiredCharacterId = null,
        systemOnly = false,
        requireFrontmostTank = true,
    )

    private fun rankedFormationsInternal(
        roster: Collection<LabyrinthRoleProfile>,
        context: LabyrinthRoleDecisionContext,
        requiredCharacterId: String?,
        systemOnly: Boolean,
        limit: Int = scorer.config.teamSearchBeamWidth,
        requireFrontmostTank: Boolean,
        forbidMixedDamageSystem: Boolean = false,
        requiredCharacterIds: Set<String> = emptySet(),
    ): List<LabyrinthTeamEvaluation> {
        val unique = roster.distinctBy(LabyrinthRoleProfile::characterId)
        if (unique.isEmpty()) return emptyList()
        if (requiredCharacterId != null && unique.none { it.characterId == requiredCharacterId }) return emptyList()
        if (requireFrontmostTank && unique.none {
                it.isEligibleBattleVanguard(context, scorer.config.vanguardGate(context))
            }) {
            return emptyList()
        }
        val searchPool = if (unique.size <= scorer.config.exhaustiveRosterLimit) {
            unique
        } else if (requireFrontmostTank) {
            battleSearchPool(unique, context, reservedIds = requiredCharacterIds)
        } else {
            unique.sortedByDescending { individualSearchPriority(it, context) }
                .take(scorer.config.exhaustiveRosterLimit)
                .let { limited ->
                    if (requiredCharacterId == null || limited.any { it.characterId == requiredCharacterId }) {
                        limited
                    } else {
                        limited.dropLast(1) + requireNotNull(unique.firstOrNull { it.characterId == requiredCharacterId })
                    }
                }
        }
        val size = minOf(TEAM_SIZE, searchPool.size)
        // Worst candidate first. For equal scores keep the earlier enumeration, exactly like
        // the old stable full sort, without retaining tens of thousands of evaluations.
        val order = compareByDescending<RankedCandidate> { it.formationRisk }
            .thenBy { it.score }.thenByDescending { it.ordinal }
        val comboCount = binomialCount(searchPool.size, size)
        val filter = CombinationFilter(
            context = context,
            requiredCharacterId = requiredCharacterId,
            requiredCharacterIds = requiredCharacterIds,
            vanguardGate = if (requireFrontmostTank) scorer.config.vanguardGate(context) else null,
            forbidMixedDamageSystem = forbidMixedDamageSystem,
            systemOnly = systemOnly,
        )
        val ranked = if (searchThreads > 1 && comboCount >= BATCH3_REFERENCE_PARALLEL_MIN_COMBINATIONS) {
            searchFormationsParallel(searchPool, size, comboCount.toInt(), filter, limit, order)
        } else {
            val best = PriorityQueue(order)
            var ordinal = 0L
            visitCombinations(searchPool, size) { team ->
                val candidate = filter.evaluate(team, ordinal++) ?: return@visitCombinations
                offer(best, candidate, limit)
            }
            best.sortedWith(order.reversed())
        }
        return ranked.map { scorer.evaluate(it.members, context) }
    }

    /** The per-team gate and score shared verbatim by the serial and parallel walks. */
    private inner class CombinationFilter(
        private val context: LabyrinthRoleDecisionContext,
        private val requiredCharacterId: String?,
        private val requiredCharacterIds: Set<String>,
        private val vanguardGate: Double?,
        private val forbidMixedDamageSystem: Boolean,
        private val systemOnly: Boolean,
    ) {
        fun evaluate(team: List<LabyrinthRoleProfile>, ordinal: Long): RankedCandidate? {
            if (requiredCharacterId != null && team.none { it.characterId == requiredCharacterId }) return null
            if (requiredCharacterIds.isNotEmpty() && !requiredCharacterIds.all { id -> team.any { it.characterId == id } }) return null
            if (vanguardGate != null && !team.hasEligibleVanguardAtActualFront(context, vanguardGate)) return null
            val evaluation = scorer.evaluateForSearch(team, context)
            if (forbidMixedDamageSystem &&
                evaluation.damageType != LabyrinthTeamDamageType.PHYSICAL &&
                evaluation.damageType != LabyrinthTeamDamageType.MAGIC
            ) {
                return null
            }
            val score = if (systemOnly) evaluation.systemScore else evaluation.score
            return RankedCandidate(evaluation.members, score, ordinal,
                labyrinthEncounterFormationRisk(team, context))
        }
    }

    private fun offer(best: PriorityQueue<RankedCandidate>, candidate: RankedCandidate, limit: Int) {
        val worst = best.peek()
        if (best.size < limit || (worst != null && requireNotNull(best.comparator()).compare(candidate, worst) > 0)) {
            if (best.size == limit) best.poll()
            best.add(candidate)
        }
    }

    /**
     * Splits the [comboCount] lexicographic combinations into [searchThreads] contiguous ranges,
     * ranks each range to its local top [limit], then merges. The merge order is (score desc,
     * enumeration index asc), which is the serial path's order: the serial ordinal counts only
     * teams that passed the gate, this one counts every enumerated combination, but both are
     * strictly increasing along the same enumeration, so every tie resolves to the same team.
     */
    private fun searchFormationsParallel(
        searchPool: List<LabyrinthRoleProfile>,
        size: Int,
        comboCount: Int,
        filter: CombinationFilter,
        limit: Int,
        order: Comparator<RankedCandidate>,
    ): List<RankedCandidate> {
        // One flat index buffer for every combination (C(24,5) x 5 = 212,520 ints, ~850 KB)
        // instead of tens of thousands of small arrays, which would be a single-threaded
        // allocation storm that eats the parallel gain.
        val flat = IntArray(comboCount * size)
        fillCombinationIndices(searchPool.size, size, flat)
        val chunkCount = minOf(searchThreads, comboCount)
        val chunkSize = (comboCount + chunkCount - 1) / chunkCount
        val tasks = (0 until chunkCount).map { chunk ->
            Callable {
                val best = PriorityQueue(order)
                val team = ArrayList<LabyrinthRoleProfile>(size)
                val from = chunk * chunkSize
                val until = minOf(comboCount, from + chunkSize)
                for (index in from until until) {
                    team.clear()
                    val base = index * size
                    for (slot in 0 until size) team.add(searchPool[flat[base + slot]])
                    val candidate = filter.evaluate(team, index.toLong()) ?: continue
                    // The candidate keeps evaluation.members, a fresh list, so reusing `team` is safe.
                    offer(best, candidate, limit)
                }
                best.toList()
            }
        }
        val partials = BATCH3_REFERENCE_ROLE_SEARCH_EXECUTOR.invokeAll(tasks).map { it.get() }
        val merged = ArrayList<RankedCandidate>(partials.sumOf { it.size })
        partials.forEach(merged::addAll)
        merged.sortWith(order.reversed())
        return merged.take(limit)
    }

    /** C(n, k), used to size the parallel split. */
    private fun binomialCount(n: Int, k: Int): Long {
        if (k < 0 || k > n) return 0L
        var result = 1L
        for (index in 0 until k) result = result * (n - index) / (index + 1)
        return result
    }

    /** Writes every k-combination of 0 until n in the same lexicographic order as [visitCombinations]. */
    private fun fillCombinationIndices(n: Int, k: Int, target: IntArray) {
        val working = IntArray(k)
        var position = 0
        fun visit(start: Int, depth: Int) {
            if (depth == k) {
                val base = position * k
                for (slot in 0 until k) target[base + slot] = working[slot]
                position++
                return
            }
            val remaining = k - depth
            for (index in start..n - remaining) {
                working[depth] = index
                visit(index + 1, depth + 1)
            }
        }
        visit(0, 0)
    }

    /**
     * A bounded search must not trim away a low-scoring tank before combinations are generated.
     * Reserve one structurally valid cohort around the database-frontmost available tank, then
     * fill remaining beam input slots by the ordinary individual priority.
     */
    internal fun battleSearchPool(
        unique: List<LabyrinthRoleProfile>,
        context: LabyrinthRoleDecisionContext,
        reservedIds: Set<String> = emptySet(),
    ): List<LabyrinthRoleProfile> {
        val limit = scorer.config.exhaustiveRosterLimit
        val teamSize = minOf(TEAM_SIZE, unique.size)
        val gate = scorer.config.vanguardGate(context)
        val frontmostTank = unique
            .asSequence()
            .filter { it.isEligibleBattleVanguard(context, gate) }
            .minWithOrNull(compareBy<LabyrinthRoleProfile> { it.position }.thenBy { it.characterId })
            ?: return emptyList()
        val tankPosition = requireNotNull(frontmostTank.position)
        val compatible = unique
            .asSequence()
            .filter { role ->
                role.characterId != frontmostTank.characterId &&
                    role.position != null &&
                    (role.isEligibleBattleVanguard(context, gate) ||
                        requireNotNull(role.position) > tankPosition)
            }
            .sortedWith(compareBy<LabyrinthRoleProfile> {
                labyrinthEncounterFormationRisk(listOf(frontmostTank, it), context)
            }.thenByDescending { individualSearchPriority(it, context) })
            .take(teamSize - 1)
            .toList()
        if (compatible.size != teamSize - 1) return emptyList()

        val structuralCohort = listOf(frontmostTank) + compatible
        // Explicitly reserved roles (a guide core the caller decided to build the team around)
        // always enter the pool. The generic Effective Effect hint alone does not: it contributes
        // only through the role's weighted individual score, so a low score can still leave that
        // role outside the bounded pool unless the caller reserves it.
        val reserved = unique.filter { it.characterId in reservedIds && it.characterId != frontmostTank.characterId }
        val forcedIds = (structuralCohort + reserved).mapTo(hashSetOf(), LabyrinthRoleProfile::characterId)
        val remainder = unique
            .asSequence()
            .filterNot { it.characterId in forcedIds }
            .sortedByDescending { individualSearchPriority(it, context) }
            .toList()
        return (structuralCohort + reserved + remainder).distinctBy { it.characterId }.take(limit)
    }

    private data class RankedCandidate(
        val members: List<LabyrinthRoleProfile>,
        val score: Double,
        val ordinal: Long,
        val formationRisk: Int,
    )

    private fun individualSearchPriority(role: LabyrinthRoleProfile, context: LabyrinthRoleDecisionContext): Double {
        val targetCount = context.targetCount
        val physical = when (targetCount.coerceAtMost(3)) {
            1 -> role.physicalDamage1Target
            2 -> role.physicalDamage2Target
            else -> role.physicalDamage3Target
        } ?: role.physicalDamagePotential
        val magic = when (targetCount.coerceAtMost(3)) {
            1 -> role.magicDamage1Target
            2 -> role.magicDamage2Target
            else -> role.magicDamage3Target
        } ?: role.magicDamagePotential
        val scenarioFunction = if (targetCount == 1) {
            role.functions.singleTargetDamage.batch3ReferenceOrZero()
        } else {
            maxOf(
                role.functions.aoeDamage.batch3ReferenceOrZero(),
                if (targetCount >= 3) role.functions.threeTargetDamage.batch3ReferenceOrZero()
                else role.functions.twoTargetDamage.batch3ReferenceOrZero(),
            )
        }
        val effectiveSystemBonus = if (
            canonicalLabyrinthRoleId(role.characterId) in context.effectiveCharacterIds &&
            (context.encounterStrategy?.preferEffectiveCharacters ?: true)
        ) {
            scorer.config.encounterEffectiveCharacterBonus
        } else {
            0.0
        }
        val guideCapabilityPriority = context.encounterStrategy?.preferredCapabilities.orEmpty().sumOf { capability ->
            if ((role.encounterCapabilityScore(capability) ?: -1.0) >= 50.0) {
                scorer.config.encounterPreferredCapabilityBonus
            } else {
                0.0
            }
        }
        // Keep the prefilter on the same conceptual scale as the final player/system blend.
        // Effective Effect is added only to the system side, so a low player score remains low
        // instead of being bypassed by an unconditional post-blend bonus.
        val modeledTeamUpliftPriority = maxOf(
            role.physicalTeamUplift.batch3ReferenceOrZero(),
            role.magicTeamUplift.batch3ReferenceOrZero(),
        ).coerceAtLeast(0.0).times(100.0).coerceAtMost(100.0)
        val baseSystemPriority = (
            maxOf(physical.batch3ReferenceOrZero(), magic.batch3ReferenceOrZero()) +
                role.functions.reliableVanguard.batch3ReferenceOrZero() * 0.5 +
                modeledTeamUpliftPriority * 0.45 +
                maxOf(
                    role.functions.physicalDefenseDown.batch3ReferenceOrZero(),
                    role.functions.magicDefenseDown.batch3ReferenceOrZero(),
                ) * 0.3 +
                scenarioFunction * 0.2
            ) / 2.45
        val requirementPriority = encounterRequirementContribution(listOf(role), context.encounterStrategy) *
            scorer.config.encounterPreferredCapabilityBonus
        val systemPriority = (baseSystemPriority + effectiveSystemBonus + guideCapabilityPriority + requirementPriority)
            .coerceIn(0.0, 100.0)
        val playerScore = role.effectiveUserScore
        val playerWeight = if (playerScore == null) 0.0 else scorer.config.playerWeight
        return (playerScore ?: 0.0) * playerWeight + systemPriority * (1.0 - playerWeight)
    }

    private fun <T> visitCombinations(values: List<T>, size: Int, consume: (List<T>) -> Unit) {
        val working = ArrayList<T>(size)
        fun visit(start: Int) {
            if (working.size == size) {
                consume(working)
                return
            }
            val remaining = size - working.size
            for (index in start..values.size - remaining) {
                working += values[index]
                visit(index + 1)
                working.removeAt(working.lastIndex)
            }
        }
        visit(0)
    }

    private companion object {
        const val TEAM_SIZE = 5

        /**
         * Distinct-vanguard first teams tried when the beam holds no disjoint set. Each seed costs
         * one bounded search per follow-up team, so this stays small enough for on-device planning.
         */
        const val MAX_RESIDUAL_FIRST_TEAM_SEEDS = 6
    }
}

internal class Batch3RoleChoicePolicyReference(
    private val optimizer: Batch3TeamOptimizerReference,
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
        // Context, optimizer and config stay fixed only within this invocation. Share repeated
        // sub-searches (including the candidates' common preceding teams), never across calls.
        val systemFormationCache = mutableMapOf<SystemFormationKey, LabyrinthTeamEvaluation?>()
        val rankings = candidateIds.map { candidateId ->
            val candidate = profiles.getValue(candidateId)
            val pool = acquired + candidate
            val placement = requireNotNull(bestCandidatePlacementPlan(pool, candidate, context, systemFormationCache))
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
                .thenByDescending { profiles.getValue(it.characterId).effectiveUserScore.batch3ReferenceOrZero() }
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
                add("综合边际评分${batch3ReferenceFormatScore(best.score)}，高于${runnerUp.displayName}的${batch3ReferenceFormatScore(runnerUp.score)}")
            }
        }
        return LabyrinthOneRoleDecision.Ready(chosen, rankings, reasons)
    }

    private fun bestCandidatePlacementPlan(
        pool: List<LabyrinthRoleProfile>,
        candidate: LabyrinthRoleProfile,
        context: LabyrinthRoleDecisionContext,
        systemFormationCache: MutableMap<SystemFormationKey, LabyrinthTeamEvaluation?>,
    ): CandidatePlacementPlan? {
        val plans = buildList {
            config.effectiveCandidateTeamWeights().indices.forEach { candidateLayer ->
                if (pool.distinctBy(LabyrinthRoleProfile::characterId).size - 1 < candidateLayer * TEAM_SIZE) {
                    return@forEach
                }
                candidatePlacementPlan(pool, candidate, context, candidateLayer, systemFormationCache)?.let(::add)
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
        systemFormationCache: MutableMap<SystemFormationKey, LabyrinthTeamEvaluation?>,
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
            // Snapshot the ordered pool, not sorted ids: stable enumeration breaks score ties.
            val searchKey = SystemFormationKey(teamPool.toList(), requiredId)
            val team = (if (systemFormationCache.containsKey(searchKey)) {
                systemFormationCache[searchKey]
            } else {
                optimizer.bestSystemFormation(teamPool, context, requiredId).also {
                    // containsKey above also reuses a cached null (no valid formation).
                    systemFormationCache[searchKey] = it
                }
            }) ?: return null
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

    private data class SystemFormationKey(
        val orderedPool: List<LabyrinthRoleProfile>,
        val requiredId: String?,
    )

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

private fun Double?.batch3ReferenceOrZero(): Double = this ?: 0.0
private fun batch3ReferenceFormatScore(value: Double): String = "%.2f".format(value)

internal fun batch4ProductionExecutor(): ExecutorService =
    Class.forName("com.landosol.toolbox.labyrinth.LabyrinthRoleDecisionPolicyKt")
        .getDeclaredField("ROLE_SEARCH_EXECUTOR").also { it.isAccessible = true }
        .get(null) as ExecutorService