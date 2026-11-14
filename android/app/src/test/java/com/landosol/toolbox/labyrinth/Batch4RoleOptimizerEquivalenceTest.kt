package com.landosol.toolbox.labyrinth

import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class Batch4RoleOptimizerEquivalenceTest {
    @Test fun allPublicSearchesMatchFrozenOptimizerSerialAndParallelAtBoundaries() {
        for (size in listOf(0, 1, 4, 5, 10, 11, 12, 14)) {
            val input = batch3SyntheticInput(size)
            val roster = input.acquiredIds.map(input.profiles::getValue)
            val serial = Batch4OptimizerPair(searchThreads = 1).compare(roster, input.context, "serial-$size")
            val parallel = Batch4OptimizerPair(searchThreads = 4).compare(roster, input.context, "parallel-$size")
            batch3AssertExact("serial-vs-parallel-$size", serial, parallel)
        }
    }

    @Test fun equalScoresAndEqualPositionsKeepEnumerationOrderForReversedRosters() {
        val input = batch3SyntheticInput(14, tied = true)
        val roster = input.acquiredIds.map(input.profiles::getValue)
        for (threads in listOf(1, 4)) {
            val pair = Batch4OptimizerPair(searchThreads = threads)
            for (order in listOf(roster, roster.reversed())) {
                val outputs = pair.compare(order, input.context, "tie-$threads-${order.first().characterId}")
                val first = outputs[0] as LabyrinthTeamEvaluation
                assertEquals(order.take(5).map { it.characterId }, first.members.map { it.characterId })
            }
        }
    }

    @Test fun contextsProfilesDuplicateIdsAndMissingRequiredIdsNeverBecomeSemanticCacheKeys() {
        val input = batch3SyntheticInput(12)
        val original = input.acquiredIds.map(input.profiles::getValue)
        val pair = Batch4OptimizerPair()
        val contexts = listOf(input.context,
            input.context.copy(defenseMarkStacks = 0, targetCount = 2, enemyDamageType = LabyrinthEnemyDamageType.MAGIC),
            input.context.copy(targetCount = 5, survivalRecovery = true, preferSingleDamageSystem = true),
            input.context.copy(optimizeBossVanguardSynergy = true, relaxedVanguardGate = true,
                effectiveCharacterIds = setOf("r0", "r4"),
                encounterStrategy = requireNotNull(LabyrinthExEncounterCatalog.matchObservedName("好朋友X"))),
            input.context)
        val results = contexts.mapIndexed { index, context -> pair.compare(original, context, "context-$index") }
        batch3AssertExact("return-context", results.first(), results.last())
        assertTrue(results[0] != results[1])
        val changed = original.map { if (it.characterId == "r0") it.copy(position = 700,
            userScore = 100.0, damageType = LabyrinthRoleDamageType.MAGIC,
            physicalDamagePotential = 0.0, magicDamagePotential = 95.0) else it }
        pair.compare(changed, input.context, "same-ids-new-profile")
        pair.compare(original + original.first().copy(userScore = 0.0), input.context, "duplicate-id")
        pair.compare(original.map { it.copy(position = null) }, input.context, "no-valid-battle-front")
        batch3AssertExact("return-profile", results.first(), pair.compare(original, input.context, "return-original"))
    }

    @Test fun over24RosterTrimmingAndWeakRequiredMembersStayIdentical() {
        val pair = Batch4OptimizerPair()
        for (size in listOf(25, 26)) {
            val input = batch3SyntheticInput(size)
            val roster = input.acquiredIds.map(input.profiles::getValue).map { role ->
                if (role.characterId == "r${size - 1}") role.copy(userScore = 0.0,
                    physicalDamagePotential = 0.0, magicDamagePotential = 0.0, functions = LabyrinthRoleFunctions()) else role
            }
            pair.compare(roster, input.context, "over24-$size")
            val required = roster.last().characterId
            val expected = pair.reference.bestSystemFormation(roster, input.context, required)
            val actual = pair.optimizer.bestSystemFormation(roster, input.context, required)
            batch3AssertExact("weak-required-$size", expected, actual)
            assertTrue(requireNotNull(actual).members.any { it.characterId == required })
        }
    }

    @Test fun concurrentOptimizerCallsOnSharedInstanceStayExactWithDifferentOrdersAndDimensions() {
        val pair = Batch4OptimizerPair()
        val cases = (0 until 6).map { index ->
            val input = batch3SyntheticInput(12 + index)
            val roster = input.acquiredIds.map(input.profiles::getValue)
            (if (index % 2 == 0) roster else roster.reversed()) to
                input.context.copy(targetCount = index % 3 + 1, defenseMarkStacks = index)
        }
        val expected = cases.map { (roster, context) -> pair.snapshotReference(roster, context) }
        val executor = Executors.newFixedThreadPool(3)
        val start = CountDownLatch(1)
        try {
            val futures = cases.map { (roster, context) -> executor.submit(Callable {
                check(start.await(30, TimeUnit.SECONDS))
                pair.snapshotOptimized(roster, context)
            }) }
            start.countDown()
            futures.forEachIndexed { index, future -> batch3AssertExact("concurrent-$index", expected[index], future.get(90, TimeUnit.SECONDS)) }
        } finally {
            start.countDown()
            executor.shutdownNow()
            check(executor.awaitTermination(30, TimeUnit.SECONDS))
        }
    }

    @Test fun scorerReturnsIndependentMemberListsForBothReusedSearchWorkLists() {
        val input = batch3SyntheticInput(6, tied = true)
        val original = input.acquiredIds.map(input.profiles::getValue).take(5)
        val working = ArrayList(original)
        val scorer = LabyrinthTeamScorer(batch3ScoringConfig())
        val first = scorer.evaluateForSearch(working, input.context)
        val explained = scorer.evaluate(working, input.context)
        assertNotSame(working, first.members)
        assertNotSame(working, explained.members)
        working.clear()
        working.add(input.profiles.getValue("c0"))
        assertEquals(original, first.members)
        assertEquals(original, explained.members)
    }

    @Test fun primitiveCandidateComparisonMatchesOriginalComparatorEvenForSignedZeroAndNaN() {
        val type = LabyrinthTeamOptimizer::class.java.declaredClasses.first { it.simpleName == "RankedCandidate" }
        val constructor = type.declaredConstructors.first { !it.isSynthetic }.also { it.isAccessible = true }
        val pair = Batch4OptimizerPair()
        val method = pair.optimizer.javaClass.getDeclaredMethod("isBetter", Double::class.javaPrimitiveType,
            Int::class.javaPrimitiveType, Long::class.javaPrimitiveType, type).also { it.isAccessible = true }
        data class Key(val score: Double, val risk: Int, val ordinal: Long)
        val order = compareByDescending<Key> { it.risk }.thenBy { it.score }.thenByDescending { it.ordinal }
        val scores = listOf(Double.NEGATIVE_INFINITY, -1.0, -0.0, 0.0, 1.0, Double.POSITIVE_INFINITY, Double.NaN)
        for (score in scores) for (worstScore in scores) for (risk in listOf(0, 2)) for (worstRisk in listOf(0, 2)) {
            for (ordinal in listOf(0L, 5L)) for (worstOrdinal in listOf(0L, 5L)) {
                val worst = constructor.newInstance(emptyList<LabyrinthRoleProfile>(), worstScore, worstOrdinal, worstRisk)
                val actual = method.invoke(pair.optimizer, score, risk, ordinal, worst) as Boolean
                assertEquals(order.compare(Key(score, risk, ordinal), Key(worstScore, worstRisk, worstOrdinal)) > 0, actual)
            }
        }
    }

    @Test fun unchangedChooseOneRoleMatchesFrozenBatch3OnObserved25And26RoleInputs() {
        val runtime = batch3ProductionRuntime()
        val pair = Batch4RolePolicyPair(runtime.document.scoring, runtime.document.roleChoice)
        batch3ObservedInputs(runtime).forEachIndexed { index, input -> pair.compare(input, "observed-$index") }
        val tied = batch3SyntheticInput(12, tied = true)
        val syntheticPair = Batch4RolePolicyPair()
        syntheticPair.compare(tied, "policy-tie")
        syntheticPair.compare(tied.copy(candidateIds = tied.candidateIds.reversed(),
            acquiredIds = LinkedHashSet(tied.acquiredIds.toList().reversed())), "policy-reversed")
        syntheticPair.compare(tied.copy(context = tied.context.copy(targetCount = 3, defenseMarkStacks = 0)), "policy-context")
        syntheticPair.compare(tied.copy(profiles = tied.profiles + ("c0" to tied.profiles.getValue("c0").copy(userScore = 99.0))), "policy-profile")
        syntheticPair.compare(tied, "policy-return")
    }
}

internal class Batch4OptimizerPair(scoring: LabyrinthTeamScoringConfig = batch3ScoringConfig(), searchThreads: Int = 4) {
    private val scorer = LabyrinthTeamScorer(scoring)
    val reference = Batch3TeamOptimizerReference(scorer, searchThreads)
    val optimizer = LabyrinthTeamOptimizer(scorer, searchThreads)
    fun snapshotReference(roster: List<LabyrinthRoleProfile>, context: LabyrinthRoleDecisionContext): List<Any?> = listOf(
        reference.bestFormation(roster, context), reference.bestSystemFormation(roster, context),
        reference.rankedFormations(roster, context), reference.rankedBattleFormations(roster, context),
        reference.bestBattleFormation(roster, context), reference.bestSupplementFormation(roster, context),
        reference.bestSystemFormation(roster, context, roster.lastOrNull()?.characterId ?: "missing"),
        reference.bestFormation(roster, context, "missing"),
        reference.bestBattleFormationIncluding(roster, context, setOf("missing")),
        reference.bestBattleFormationIncluding(roster, context, roster.take(2).mapTo(linkedSetOf()) { it.characterId }),
        reference.bestBattleFormationIncluding(roster, context, roster.take(6).mapTo(linkedSetOf()) { it.characterId }),
    )
    fun snapshotOptimized(roster: List<LabyrinthRoleProfile>, context: LabyrinthRoleDecisionContext): List<Any?> = listOf(
        optimizer.bestFormation(roster, context), optimizer.bestSystemFormation(roster, context),
        optimizer.rankedFormations(roster, context), optimizer.rankedBattleFormations(roster, context),
        optimizer.bestBattleFormation(roster, context), optimizer.bestSupplementFormation(roster, context),
        optimizer.bestSystemFormation(roster, context, roster.lastOrNull()?.characterId ?: "missing"),
        optimizer.bestFormation(roster, context, "missing"),
        optimizer.bestBattleFormationIncluding(roster, context, setOf("missing")),
        optimizer.bestBattleFormationIncluding(roster, context, roster.take(2).mapTo(linkedSetOf()) { it.characterId }),
        optimizer.bestBattleFormationIncluding(roster, context, roster.take(6).mapTo(linkedSetOf()) { it.characterId }),
    )
    fun compare(roster: List<LabyrinthRoleProfile>, context: LabyrinthRoleDecisionContext, label: String): List<Any?> {
        val expected = snapshotReference(roster, context)
        val actual = snapshotOptimized(roster, context)
        batch3AssertExact(label, expected, actual)
        return actual
    }
}

internal class Batch4RolePolicyPair(scoring: LabyrinthTeamScoringConfig = batch3ScoringConfig(),
    choice: LabyrinthRoleChoiceConfig = LabyrinthRoleChoiceConfig()) {
    private val scorer = LabyrinthTeamScorer(scoring)
    private val reference = Batch3RoleChoicePolicyReference(Batch3TeamOptimizerReference(scorer), choice)
    private val policy = LabyrinthRoleChoicePolicy(LabyrinthTeamOptimizer(scorer), choice)
    fun baseline(input: Batch3RoleInput): LabyrinthOneRoleDecision = reference.chooseOneRole(
        input.candidateIds, input.acquiredIds, input.profiles, input.context, input.strict)
    fun optimized(input: Batch3RoleInput): LabyrinthOneRoleDecision = policy.chooseOneRole(
        input.candidateIds, input.acquiredIds, input.profiles, input.context, input.strict)
    fun compare(input: Batch3RoleInput, label: String): LabyrinthOneRoleDecision {
        val expected = baseline(input)
        val actual = optimized(input)
        batch3AssertExact(label, expected, actual)
        return actual
    }
}
