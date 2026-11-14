package com.landosol.toolbox.labyrinth

import java.io.File
import java.lang.reflect.Modifier
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Batch3RoleChoicePolicyEquivalenceTest {
    @Test fun acquiredPoolBoundaries_0_4_5_9_10_14() {
        for (size in listOf(0, 4, 5, 9, 10, 14)) {
            val input = batch3SyntheticInput(size)
            val ready = Batch3PolicyPair().compare(input, "acquired-$size") as LabyrinthOneRoleDecision.Ready
            assertEquals(3, ready.rankings.size)
            assertEquals(minOf(5, size + 1), ready.rankings.first().firstTeam.members.size)
            ready.rankings.forEach { ranking ->
                val teams = listOfNotNull(ranking.firstTeam, ranking.secondTeam, ranking.thirdTeam)
                val ids = teams.flatMap { it.members.map(LabyrinthRoleProfile::characterId) }
                assertEquals(ids.size, ids.distinct().size)
                assertTrue(ranking.candidateTeam.members.any { it.characterId == ranking.characterId })
            }
        }
    }

    @Test fun exactTiesPreserveCandidateAndEqualPositionRosterOrder() {
        val input = batch3SyntheticInput(9, tied = true)
        val orders = listOf(input.candidateIds, input.candidateIds.reversed(), listOf("c1", "c2", "c0"))
        val pair = Batch3PolicyPair()
        for (candidateOrder in orders) for (reverseAcquired in listOf(false, true)) {
            val rosterOrder = if (reverseAcquired) input.acquiredIds.toList().reversed() else input.acquiredIds.toList()
            val changed = input.copy(candidateIds = candidateOrder, acquiredIds = LinkedHashSet(rosterOrder))
            val decision = pair.compare(changed, "tie-$candidateOrder-$reverseAcquired") as LabyrinthOneRoleDecision.Ready
            assertEquals(candidateOrder, decision.rankings.map { it.characterId })
            assertEquals(candidateOrder.first(), decision.chosen.characterId)
        }
    }

    @Test fun staleAcquiredCollisionKeepsAllVisibleCardsAndExactReasons() {
        val input = batch3SyntheticInput(9)
        val changed = input.copy(acquiredIds = LinkedHashSet(input.acquiredIds + listOf("c0", "c2")))
        val decision = Batch3PolicyPair().compare(changed, "stale-acquired") as LabyrinthOneRoleDecision.Ready
        assertEquals(3, decision.rankings.size)
        assertTrue(decision.reasons.any { it.contains("本地已获得名单冲突") })
    }

    @Test fun unavailableAndLooseIncompleteInputsMatchWithoutGuessing() {
        val input = batch3SyntheticInput(4)
        val incompleteCandidate = input.profiles + ("c0" to input.profiles.getValue("c0").copy(position = null, damageType = null))
        val incompleteOwned = input.profiles + ("r0" to input.profiles.getValue("r0").copy(dataConfidence = LabyrinthRoleDataConfidence(support = "missing")))
        val cases = listOf(
            input.copy(candidateIds = emptyList()),
            input.copy(candidateIds = listOf("c0", "c0", "c1")),
            input.copy(candidateIds = listOf("missing", "c1", "c2")),
            input.copy(acquiredIds = LinkedHashSet(input.acquiredIds + "missing-owned")),
            input.copy(profiles = incompleteCandidate),
            input.copy(profiles = incompleteOwned),
            input.copy(profiles = incompleteCandidate, strict = false),
        )
        val pair = Batch3PolicyPair()
        cases.forEachIndexed { index, case ->
            val result = pair.compare(case, "unavailable-$index")
            if (index < 6) assertTrue(result is LabyrinthOneRoleDecision.Unavailable)
            else assertTrue(result is LabyrinthOneRoleDecision.Ready)
        }
    }

    @Test fun contextChangesAndReturnToOriginalDoNotLeakAcrossCalls() {
        val input = batch3SyntheticInput(9)
        val contexts = listOf(
            input.context,
            input.context.copy(defenseMarkStacks = 0, enemyDamageType = LabyrinthEnemyDamageType.MAGIC, targetCount = 2),
            input.context.copy(targetCount = 5, survivalRecovery = true, preferSingleDamageSystem = true),
            input.context.copy(optimizeBossVanguardSynergy = true, relaxedVanguardGate = true,
                effectiveCharacterIds = setOf("c1", "r0"),
                encounterStrategy = requireNotNull(LabyrinthExEncounterCatalog.matchObservedName("好朋友X"))),
            input.context,
        )
        val pair = Batch3PolicyPair()
        val results = contexts.mapIndexed { index, context -> pair.compare(input.copy(context = context), "context-$index") }
        batch3AssertExact("context-return", results.first(), results.last())
        assertTrue("context fixture must actually change output", results[0] != results[1])
    }

    @Test fun sameIdsChangedProfilesAndReturnToOriginalDoNotLeakAcrossCalls() {
        val input = batch3SyntheticInput(9)
        val preference = input.profiles + ("c1" to input.profiles.getValue("c1").copy(personalScore = 100.0))
        val combat = input.profiles + ("r0" to input.profiles.getValue("r0").copy(
            physicalDamagePotential = 0.0, magicDamagePotential = 90.0, damageType = LabyrinthRoleDamageType.MAGIC,
            functions = LabyrinthRoleFunctions(healing = 100.0, reliableVanguard = 0.0),
        ))
        val pair = Batch3PolicyPair()
        val results = listOf(input.profiles, preference, combat, input.profiles).mapIndexed { index, profiles ->
            pair.compare(input.copy(profiles = profiles), "profile-$index")
        }
        batch3AssertExact("profile-return", results.first(), results.last())
        assertTrue("profile fixture must actually change output", results[0] != results[1])
    }

    @Test fun scoringAndDeploymentWeightProfilesUseTheSameOptimizerForBothPolicies() {
        val input = batch3SyntheticInput(10)
        val base = batch3ScoringConfig()
        val configurations = listOf(
            base to LabyrinthRoleChoiceConfig(),
            base.copy(playerWeight = 0.20) to LabyrinthRoleChoiceConfig(),
            base.copy(damageSoftCeiling = 2.0) to LabyrinthRoleChoiceConfig(),
            base to LabyrinthRoleChoiceConfig(forcedCandidateTeamWeight = 0.50, secondTeamPotentialWeight = 0.10),
            base to LabyrinthRoleChoiceConfig(candidateTeamWeights = listOf(1.0, 0.80, 0.45)),
        )
        configurations.forEachIndexed { index, (scoring, choice) ->
            Batch3PolicyPair(scoring, choice).compare(input, "scoring-$index")
        }
    }

    @Test fun consecutiveRosterGrowthShrinkAndReturnDoNotReuseOldSearches() {
        val pair = Batch3PolicyPair()
        listOf(5, 9, 14, 4, 9).forEachIndexed { index, size ->
            pair.compare(batch3SyntheticInput(size), "consecutive-$index-$size")
        }
    }

    @Test fun concurrentCallsOnOnePolicyWithDifferentContextsProfilesAndOrdersStayIsolated() {
        val input = batch3SyntheticInput(9)
        val pair = Batch3PolicyPair()
        val cases = (0 until 6).map { index ->
            input.copy(
                candidateIds = if (index % 2 == 0) input.candidateIds else input.candidateIds.reversed(),
                acquiredIds = LinkedHashSet(if (index % 2 == 0) input.acquiredIds else input.acquiredIds.toList().reversed()),
                context = input.context.copy(defenseMarkStacks = index, targetCount = index % 3 + 1,
                    effectiveCharacterIds = setOf("r$index")),
                profiles = input.profiles + ("r0" to input.profiles.getValue("r0").copy(personalScore = index * 17.0)),
            )
        }
        val expected = cases.map(pair::baseline)
        val executor = Executors.newFixedThreadPool(3)
        val start = CountDownLatch(1)
        try {
            val futures = cases.map { case -> executor.submit(Callable {
                check(start.await(30, TimeUnit.SECONDS))
                pair.optimized(case)
            }) }
            start.countDown()
            futures.forEachIndexed { index, future ->
                batch3AssertExact("concurrent-$index", expected[index], future.get(60, TimeUnit.SECONDS))
            }
        } finally {
            start.countDown()
            executor.shutdownNow()
            check(executor.awaitTermination(30, TimeUnit.SECONDS))
        }
    }

    @Test fun productionObservedCardsAndActual25Then26RoleLedgersMatch() {
        val runtime = batch3ProductionRuntime()
        val pair = Batch3PolicyPair(runtime.document.scoring, runtime.document.roleChoice)
        batch3ObservedInputs(runtime).forEachIndexed { index, input ->
            assertTrue(input.acquiredIds.size > runtime.document.scoring.exhaustiveRosterLimit)
            assertTrue((input.acquiredIds + input.candidateIds).all { input.profiles.getValue(it).isStrictDecisionReady })
            pair.compare(input, "observed-frame-${if (index == 0) 2017 else 2028}")
        }
    }

    @Test fun over24PoolStillReservesAWeakRequiredCandidateAfterPriorityTrimming() {
        val input = batch3SyntheticInput(25)
        val profiles = input.profiles.toMutableMap()
        input.candidateIds.forEach { id ->
            profiles[id] = profiles.getValue(id).copy(physicalDamagePotential = 0.0, magicDamagePotential = 0.0,
                userScore = 0.0, functions = LabyrinthRoleFunctions())
        }
        val ready = Batch3PolicyPair().compare(input.copy(profiles = profiles), "over24-forced-candidates") as LabyrinthOneRoleDecision.Ready
        ready.rankings.forEach { ranking -> assertTrue(ranking.candidateTeam.members.any { it.characterId == ranking.characterId }) }
    }
}

internal data class Batch3RoleInput(
    val candidateIds: List<String>,
    val acquiredIds: Set<String>,
    val profiles: Map<String, LabyrinthRoleProfile>,
    val context: LabyrinthRoleDecisionContext,
    val strict: Boolean = true,
)

internal class Batch3PolicyPair(
    scoring: LabyrinthTeamScoringConfig = batch3ScoringConfig(),
    choice: LabyrinthRoleChoiceConfig = LabyrinthRoleChoiceConfig(),
) {
    // Deliberately the very same scorer/optimizer/config instance, not copied algorithms.
    private val scorer = LabyrinthTeamScorer(scoring)
    private val optimizer = LabyrinthTeamOptimizer(scorer)
    private val reference = Batch2RoleChoicePolicyReference(optimizer, choice)
    private val policy = LabyrinthRoleChoicePolicy(optimizer, choice)
    fun baseline(input: Batch3RoleInput): LabyrinthOneRoleDecision = reference.chooseOneRole(
        input.candidateIds, input.acquiredIds, input.profiles, input.context, input.strict,
    )
    fun optimized(input: Batch3RoleInput): LabyrinthOneRoleDecision = policy.chooseOneRole(
        input.candidateIds, input.acquiredIds, input.profiles, input.context, input.strict,
    )
    fun compare(input: Batch3RoleInput, label: String): LabyrinthOneRoleDecision {
        val expected = baseline(input)
        val actual = optimized(input)
        batch3AssertExact(label, expected, actual)
        return actual
    }
}

internal fun batch3ScoringConfig() = LabyrinthTeamScoringConfig(
    attributeDamageBonus = linkedMapOf(1 to 0.0, 2 to 0.08, 3 to 0.16, 4 to 0.25, 5 to 0.75),
)

internal fun batch3SyntheticInput(acquiredCount: Int, tied: Boolean = false): Batch3RoleInput {
    fun role(id: String, index: Int): LabyrinthRoleProfile {
        val magic = !tied && index % 3 == 1
        val value = if (tied) 60.0 else 40.0 + (index * 13 % 50)
        return LabyrinthRoleProfile(
            characterId = id, displayName = id, roleClass = "攻击者",
            attribute = if (tied) LabyrinthCharacterAttribute.FIRE else LabyrinthCharacterAttribute.values()[index % 5],
            damageType = if (magic) LabyrinthRoleDamageType.MAGIC else LabyrinthRoleDamageType.PHYSICAL,
            position = if (tied) 200 else 100 + index % 6 * 40,
            userScore = if (tied) 60.0 else if (index % 7 == 0) null else value,
            physicalDamagePotential = if (magic) 0.0 else value,
            magicDamagePotential = if (magic) value else 0.0,
            support = LabyrinthRoleSupport(universalSurvival = if (tied) 40.0 else index % 4 * 20.0),
            functions = LabyrinthRoleFunctions(reliableVanguard = if (tied || index % 5 == 0) 80.0 else 30.0,
                healing = if (tied) 40.0 else index % 4 * 15.0,
                physicalDefenseDown = if (tied) 40.0 else index % 3 * 25.0,
                magicDefenseDown = if (tied) 40.0 else index % 4 * 20.0,
                singleTargetDamage = value, aoeDamage = value, twoTargetDamage = value, threeTargetDamage = value),
        )
    }
    val owned = (0 until acquiredCount).map { role("r$it", it) }
    val candidates = (0..2).map { role("c$it", 31 + it) }
    return Batch3RoleInput(candidates.map { it.characterId }, owned.mapTo(LinkedHashSet()) { it.characterId },
        (owned + candidates).associateBy { it.characterId }, LabyrinthRoleDecisionContext(defenseMarkStacks = 4))
}

internal fun batch3ProductionRuntime(): LabyrinthRoleDecisionRuntime {
    val asset = listOf(
        File("app/src/main/assets/resource-packs/cn-bilibili/labyrinth-role-decision.json"),
        File("src/main/assets/resource-packs/cn-bilibili/labyrinth-role-decision.json"),
    ).first { it.isFile }
    return when (val parsed = LabyrinthRoleDecisionDataParser.parse(asset.readText())) {
        is LabyrinthRoleDecisionDataResult.Ready -> parsed.runtime
        is LabyrinthRoleDecisionDataResult.Unavailable -> error(parsed.reason)
    }
}

internal fun batch3ObservedInputs(runtime: LabyrinthRoleDecisionRuntime): List<Batch3RoleInput> {
    // Exact joined-name order from batch2 state/history.ndjson frames 2017 and 2028.
    // Aliases are resolved against the asset facts; see _batch3-benchmark/fixture-evidence.json.
    val beforeMaho = listOf("1025", "1308", "1145", "1150", "1258", "1191", "1050", "1240", "1157",
        "1014", "1285", "1008", "1168", "1113", "1331", "1299", "1141", "1200", "1330", "1114",
        "1006", "1140", "1066", "1276", "1084")
    val context = LabyrinthRoleDecisionContext(defenseMarkStacks = 4)
    return listOf(
        Batch3RoleInput(listOf("1222", "1010", "1129"), LinkedHashSet(beforeMaho), runtime.profiles, context),
        Batch3RoleInput(listOf("1001", "1094", "1348"), LinkedHashSet(beforeMaho + "1010"), runtime.profiles, context),
    )
}

/** Data-class equality plus recursive raw-double and map-entry-order equality, no tolerance. */
internal fun batch3AssertExact(path: String, expected: Any?, actual: Any?) {
    assertEquals(path, expected, actual)
    if (expected == null || actual == null) return
    assertEquals("$path.type", expected.javaClass, actual.javaClass)
    when (expected) {
        is Double -> assertEquals("$path.rawBits", java.lang.Double.doubleToRawLongBits(expected),
            java.lang.Double.doubleToRawLongBits(actual as Double))
        is Map<*, *> -> {
            val other = (actual as Map<*, *>).entries.toList()
            expected.entries.forEachIndexed { index, entry ->
                batch3AssertExact("$path.key[$index]", entry.key, other[index].key)
                batch3AssertExact("$path.value[$index]", entry.value, other[index].value)
            }
        }
        is List<*> -> expected.forEachIndexed { index, value -> batch3AssertExact("$path[$index]", value, (actual as List<*>)[index]) }
        is Set<*> -> expected.toList().forEachIndexed { index, value ->
            batch3AssertExact("$path.set[$index]", value, (actual as Set<*>).toList()[index])
        }
        is String, is Number, is Boolean, is Enum<*> -> Unit
        else -> expected.javaClass.declaredFields.filterNot { Modifier.isStatic(it.modifiers) || it.isSynthetic }.forEach { field ->
            field.isAccessible = true
            batch3AssertExact("$path.${field.name}", field.get(expected), field.get(actual))
        }
    }
}
