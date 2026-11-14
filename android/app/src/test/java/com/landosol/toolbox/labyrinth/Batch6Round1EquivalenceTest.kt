package com.landosol.toolbox.labyrinth

import com.landosol.toolbox.labyrinth.concurrent.LabyrinthFourThreadRuntime
import com.landosol.toolbox.labyrinth.concurrent.WorkKind
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Full data equality, recursive raw Double bits and insertion order; never a new-scorer baseline. */
class Batch6Round1EquivalenceTest {
    @Test fun fourRealSlowSegmentsAndObservedTwoSafeToThreeSafeVariants() {
        val runtime = batch3ProductionRuntime()
        val pair = Batch6Round1PolicyPair(runtime.document.scoring, runtime.document.roleChoice)
        val fixtures = batch6Round1Fixtures(runtime)
        assertEquals(listOf(31, 32, 36, 37), fixtures.map { it.input.acquiredIds.size })
        fixtures.forEach { fixture ->
            fixture.variants.forEachIndexed { index, input -> pair.compare(input, "${fixture.label}/qualified-$index") }
        }
        // Real ledgers retain order, including multiple consecutive additions between slow segments.
        fixtures.zipWithNext().forEach { (a, b) ->
            assertEquals(a.input.acquiredIds.toList(), b.input.acquiredIds.take(a.input.acquiredIds.size))
            assertTrue(b.input.acquiredIds.size > a.input.acquiredIds.size)
        }
    }

    @Test fun pool23_24_25WeakRequiredAndFullRankOrderMatch() {
        val scoring = batch3ScoringConfig()
        val baseline = Batch5Round1OptimizerReference(Batch5Round1ScorerReference(scoring))
        val optimized = LabyrinthTeamOptimizer(LabyrinthTeamScorer(scoring))
        for (size in listOf(23, 24, 25)) {
            val input = batch3SyntheticInput(size)
            val weak = input.profiles.getValue("c0").copy(userScore = 0.0,
                physicalDamagePotential = 0.0, magicDamagePotential = 0.0, functions = LabyrinthRoleFunctions())
            val roster = input.acquiredIds.toList().dropLast(1).map(input.profiles::getValue) + weak
            val expected = batch6Round1Root { baseline.bestSystemFormation(roster, input.context, weak.characterId) }
            val actual = batch6Round1Root { optimized.bestSystemFormation(roster, input.context, weak.characterId) }
            batch3AssertExact("pool-$size/required", expected, actual)
            assertTrue(requireNotNull(actual).members.any { it.characterId == weak.characterId })
        }
        val small = batch3SyntheticInput(9, tied = true)
        val roster = small.acquiredIds.map(small.profiles::getValue)
        batch3AssertExact("ranked-full-order", batch6Round1Root { baseline.rankedFormations(roster, small.context) },
            batch6Round1Root { optimized.rankedFormations(roster, small.context) })
    }

    @Test fun requiredFirstMiddleLastPreservesTiedMemberOrderAndVisitsOnlyLegalCombinations() {
        fun combinations(n: Int, k: Int): Long {
            var value = 1L
            for (i in 0 until k) value = value * (n - i) / (i + 1)
            return value
        }
        for (size in listOf(9, 14)) for (requiredAt in listOf(0, size / 2, size - 1)) {
            val input = batch3SyntheticInput(size, tied = true)
            val roster = input.acquiredIds.map(input.profiles::getValue)
            val required = roster[requiredAt].characterId
            val baseline = Batch5Round1OptimizerReference(Batch5Round1ScorerReference(batch3ScoringConfig()))
            val optimized = LabyrinthTeamOptimizer(LabyrinthTeamScorer(batch3ScoringConfig()))
            val reports = mutableListOf<LabyrinthSearchWorkReport>()
            optimized.searchWorkObserver = { reports += it }
            val expected = batch6Round1Root { baseline.bestSystemFormation(roster, input.context, required) }
            val actual = batch6Round1Root { optimized.bestSystemFormation(roster, input.context, required) }
            batch3AssertExact("required-position-$size-$requiredAt", expected, actual)
            val report = reports.single()
            assertEquals(requiredAt, report.requiredIndex)
            assertEquals(combinations(size, 5), report.fullCombinationCount)
            assertEquals(combinations(size - 1, 4), report.visitedCombinationCount)
            assertEquals(report.visitedCombinationCount, report.scoredCombinationCount)
            assertEquals(report.visitedCombinationCount, report.shardVisitedCounts.sum())
            assertEquals(if (size == 9) 1 else 2, report.shardVisitedCounts.size)
            assertTrue(report.shardVisitedCounts.maxOrNull()!! - report.shardVisitedCounts.minOrNull()!! <= 1)
            assertTrue(report.retainedCandidateCopies in 1..report.scoredCombinationCount)
            println("BATCH6_ROUND1_WORK_COUNTS=$report") // untimed; benchmark leaves observer null
        }
    }

    @Test fun tiesMemberOrderContextsProfilesAndReentryRemainExact() {
        val pair = Batch6Round1PolicyPair()
        val input = batch3SyntheticInput(9, tied = true)
        val first = pair.compare(input, "original")
        pair.compare(input.copy(candidateIds = input.candidateIds.reversed(),
            acquiredIds = LinkedHashSet(input.acquiredIds.toList().reversed())), "reversed-ties")
        pair.compare(input.copy(context = input.context.copy(defenseMarkStacks = 1, targetCount = 3,
            enemyDamageType = LabyrinthEnemyDamageType.MAGIC, survivalRecovery = true,
            effectiveCharacterIds = linkedSetOf("c1", "r0"))), "context-change")
        pair.compare(input.copy(profiles = input.profiles + ("c1" to input.profiles.getValue("c1").copy(
            personalScore = 10.0, physicalTeamUplift = -0.15, modelStatus = "database-v2"))), "profile-change")
        batch3AssertExact("return-original", first, pair.compare(input, "return-original"))
        val missing = input.copy(candidateIds = listOf("missing", "c1", "c2"))
        assertTrue(pair.compare(missing, "missing") is LabyrinthOneRoleDecision.Unavailable)
    }

    @Test fun consecutiveNewRolesEnterTheSamePairWithoutStaleSearchReuse() {
        val source = batch3SyntheticInput(14)
        val pair = Batch6Round1PolicyPair()
        val acquired = LinkedHashSet(source.acquiredIds.take(9))
        for (newRole in listOf("r9", "r10", "r11")) {
            val input = source.copy(candidateIds = listOf(newRole, "c0", "c1"), acquiredIds = LinkedHashSet(acquired))
            pair.compare(input, "before-joining-$newRole")
            acquired += newRole
        }
        pair.compare(source.copy(acquiredIds = acquired), "after-three-joins")
    }

    @Test fun twoConcurrentSharedComputeRootsRemainIsolated() {
        val pair = Batch6Round1PolicyPair()
        val cases = listOf(batch3SyntheticInput(9), batch3SyntheticInput(10).let {
            it.copy(candidateIds = it.candidateIds.reversed(), context = it.context.copy(defenseMarkStacks = 0, targetCount = 2))
        })
        val expected = cases.map { input -> batch6Round1Root { pair.baseline(input) } }
        val done = cases.map { CountDownLatch(1) }
        val handles = cases.mapIndexed { i, input -> LabyrinthFourThreadRuntime.submitWork(
            WorkKind.PLANNING, onQuiesced = { done[i].countDown() }) { pair.optimized(input) } }
        handles.forEachIndexed { i, handle ->
            val actual = handle.future.get(180, TimeUnit.SECONDS)
            check(done[i].await(30, TimeUnit.SECONDS))
            batch3AssertExact("concurrent-root-$i", expected[i], actual)
        }
    }

    @Test fun originalPublicScorerExplainedOutputsRawBitsAndMapOrderAreFrozen() {
        val runtime = batch3ProductionRuntime()
        val old = Batch5Round1ScorerReference(runtime.document.scoring)
        val new = LabyrinthTeamScorer(runtime.document.scoring)
        val roles = runtime.profiles.values.take(15)
        for (defense in listOf(0, 2, 4, 6)) for (target in 1..3) {
            val context = LabyrinthRoleDecisionContext(defenseMarkStacks = defense, targetCount = target)
            for (team in listOf(roles.take(5), roles.take(5).reversed(), roles.drop(5).take(5), roles.take(1))) {
                batch3AssertExact("scorer/$defense/$target", old.evaluate(team, context), new.evaluate(team, context))
            }
        }
    }
}

internal class Batch6Round1PolicyPair(
    scoring: LabyrinthTeamScoringConfig = batch3ScoringConfig(),
    choice: LabyrinthRoleChoiceConfig = LabyrinthRoleChoiceConfig(),
) {
    private val reference = Batch5Round1RolePolicyReference(
        Batch5Round1OptimizerReference(Batch5Round1ScorerReference(scoring)), choice)
    private val policy = LabyrinthRoleChoicePolicy(LabyrinthTeamOptimizer(LabyrinthTeamScorer(scoring)), choice)
    fun baseline(input: Batch3RoleInput): LabyrinthOneRoleDecision = reference.chooseOneRole(
        input.candidateIds, input.acquiredIds, input.profiles, input.context, input.strict)
    fun optimized(input: Batch3RoleInput): LabyrinthOneRoleDecision = policy.chooseOneRole(
        input.candidateIds, input.acquiredIds, input.profiles, input.context, input.strict)
    fun compare(input: Batch3RoleInput, label: String): LabyrinthOneRoleDecision {
        val expected = batch6Round1Root { baseline(input) }
        val actual = batch6Round1Root { optimized(input) }
        batch3AssertExact(label, expected, actual)
        return actual
    }
}

internal fun <T> batch6Round1Root(action: () -> T): T {
    val quiesced = CountDownLatch(1)
    val handle = LabyrinthFourThreadRuntime.submitWork(WorkKind.PLANNING, onQuiesced = { quiesced.countDown() }) {
        check(LabyrinthFourThreadRuntime.isComputeThread())
        action()
    }
    try { return handle.future.get(240, TimeUnit.SECONDS) }
    finally { check(quiesced.await(240, TimeUnit.SECONDS)) { "Root and children must quiesce" } }
}

internal data class Batch6Round1Fixture(val label: String, val input: Batch3RoleInput, val variants: List<Batch3RoleInput>)

internal fun batch6Round1Fixtures(runtime: LabyrinthRoleDecisionRuntime): List<Batch6Round1Fixture> {
    val file = listOf(File("../_round1/fixture-slow-segments.json"), File("../../_round1/fixture-slow-segments.json"),
        File("_round1/fixture-slow-segments.json")).first { it.isFile }
    val root = Json.parseToJsonElement(file.readText()).jsonObject
    return root.getValue("fixtures").jsonArray.map { element ->
        val f = element.jsonObject
        fun strings(key: String) = f.getValue(key).jsonArray.map { it.jsonPrimitive.content }
        val ids = strings("acquiredIds")
        val names = strings("acquiredNames")
        val candidates = strings("candidateIds")
        // Preserve bundled incomplete profile 1360 (null damageType/position, real fallback skills).
        // Only genuinely absent IDs receive production's identity-only fallback; never invent combat facts.
        val profiles = runtime.profiles.withKnownRoleIdentities(ids + candidates,
            (ids.zip(names) + candidates.zip(strings("candidateNames"))).toMap())
        val input = Batch3RoleInput(candidates, LinkedHashSet(ids), profiles,
            LabyrinthRoleDecisionContext(f.getValue("defenseMarkStacks").jsonPrimitive.int), strict = false)
        val variants = f.getValue("qualificationVariants").jsonArray.map { v ->
            input.copy(candidateIds = v.jsonObject.getValue("candidateIds").jsonArray.map { it.jsonPrimitive.content })
        }
        Batch6Round1Fixture(f.getValue("label").jsonPrimitive.content, input, variants)
    }
}
