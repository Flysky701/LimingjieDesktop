package com.landosol.toolbox.labyrinth

import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import org.junit.Assert.*
import org.junit.Test

/** The unchanged scorer is the oracle; no tolerance, rounding, or NaN canonicalization. */
class Batch6PreparedSearchScorerTest {
    @Test fun productionAssetsAndObservedUnionsMatchAcrossContextsAndStrategies() {
        val runtime = batch3ProductionRuntime()
        val config = runtime.document.scoring
        val all = runtime.profiles.values.toList()
        assertTrue(all.size > 100)
        val contexts = listOf(
            LabyrinthRoleDecisionContext(0),
            LabyrinthRoleDecisionContext(1, LabyrinthEnemyDamageType.PHYSICAL, 2),
            LabyrinthRoleDecisionContext(2, LabyrinthEnemyDamageType.MAGIC, 3, survivalRecovery = true),
            LabyrinthRoleDecisionContext(3, LabyrinthEnemyDamageType.MIXED, 5, preferSingleDamageSystem = true),
            LabyrinthRoleDecisionContext(4, optimizeBossVanguardSynergy = true, relaxedVanguardGate = true),
            LabyrinthRoleDecisionContext(9, targetCount = Int.MAX_VALUE, effectiveCharacterIds = setOf("1001", "1010", "1222")),
        ) + (LabyrinthExEncounterCatalog.singleTarget + LabyrinthExEncounterCatalog.specialDualTarget +
            LabyrinthExEncounterCatalog.multiTarget).map { strategy ->
            LabyrinthRoleDecisionContext(4, targetCount = strategy.targetCount,
                encounterStrategy = strategy, optimizeBossVanguardSynergy = true,
                effectiveCharacterIds = all.take(10).mapTo(linkedSetOf()) { canonicalLabyrinthRoleId(it.characterId) })
        }
        for ((contextIndex, context) in contexts.withIndex()) {
            val prepared = LabyrinthPreparedSearchScorer(config, context, all)
            val scratch = prepared.newScratch()
            val baseline = LabyrinthTeamScorer(config)
            // Every asset is used, with small, full, reversed and mixed-system subset teams.
            for (start in all.indices) {
                val team = (0 until (start % 5 + 1)).map { all[(start + it * 17) % all.size] }
                assertExact("assets-$contextIndex-$start", baseline, context, prepared, scratch, team)
                assertExact("assets-reverse-$contextIndex-$start", baseline, context, prepared, scratch, team.reversed())
            }
        }
        for ((inputIndex, input) in batch3ObservedInputs(runtime).withIndex()) {
            val union = (input.acquiredIds + input.candidateIds).map(input.profiles::getValue)
            val prepared = LabyrinthPreparedSearchScorer(config, input.context, union)
            val scratch = prepared.newScratch()
            val baseline = LabyrinthTeamScorer(config)
            for (candidate in input.candidateIds) {
                val subset = input.acquiredIds.map(input.profiles::getValue) + input.profiles.getValue(candidate)
                for (index in subset.indices) {
                    val team = (0 until 5).map { subset[(index + it) % subset.size] }
                    assertExact("observed-$inputIndex-$candidate-$index", baseline, input.context, prepared, scratch, team)
                }
            }
        }
    }

    @Test fun syntheticMissingProfilesModeledFallbackAndEveryTeamTypeMatchRawBits() {
        val random = Random(6001)
        fun percent(): Double? = when (random.nextInt(10)) {
            0 -> null
            1 -> -0.0
            2 -> 0.0
            3 -> 100.0
            else -> random.nextDouble() * 100.0
        }
        val roles = (0 until 45).map { index ->
            LabyrinthRoleProfile(
                characterId = "synthetic-$index", displayName = "synthetic-$index",
                roleClass = listOf(null, "攻击者", "治疗者", "掩护者", "增益者", "妨碍者")[index % 6],
                attribute = if (index % 7 == 0) null else LabyrinthCharacterAttribute.values()[index % 5],
                damageType = if (index % 5 == 0) null else LabyrinthRoleDamageType.values()[index % 4],
                position = if (index % 8 == 0) null else 100 + index % 4,
                userScore = percent(), personalScore = percent(), baseQuality = percent(),
                physicalDamagePotential = percent(), magicDamagePotential = percent(),
                physicalDamage1Target = percent(), physicalDamage2Target = percent(), physicalDamage3Target = percent(),
                magicDamage1Target = percent(), magicDamage2Target = percent(), magicDamage3Target = percent(),
                physicalTeamUplift = if (index % 3 == 0) null else random.nextDouble(-2.0, 2.0),
                magicTeamUplift = if (index % 4 == 0) null else random.nextDouble(-2.0, 2.0),
                modelStatus = if (index % 2 == 0) "database-v2" else "unavailable",
                support = LabyrinthRoleSupport(percent(), percent(), percent(), percent(), percent()),
                functions = LabyrinthRoleFunctions(reliableVanguard = percent(), selfSustain = percent(),
                    healing = percent(), regeneration = percent(), physicalAttackReduction = percent(),
                    magicAttackReduction = percent(), physicalDefenseDown = percent(), magicDefenseDown = percent(),
                    aoeDamage = percent(), singleTargetDamage = percent(), twoTargetDamage = percent(),
                    threeTargetDamage = percent(), bossMechanismValue = percent(), pureTank = percent(), pureHealer = percent()),
                vanguardProfile = if (index % 3 == 0) null else LabyrinthVanguardProfile(
                    physicalDurability = percent(), magicDurability = percent(), untargetable = percent(),
                    invulnerability = percent(), physicalEvasion = percent(), magicEvasion = percent(),
                    revive = percent(), requiresAllyInFront = index % 11 == 0),
            )
        } + listOf(LabyrinthRoleProfile("missing", "missing"))
        val configs = listOf(batch3ScoringConfig(), batch3ScoringConfig().copy(
            playerWeight = 0.0, maximumActiveAttributeBonuses = 1), batch3ScoringConfig().copy(
            playerWeight = 1.0, compositeOffenseThreshold = 35.0, defenseBaselineStacks = 2))
        for ((configIndex, config) in configs.withIndex()) {
            for (target in listOf(1, 2, 3, 5, Int.MAX_VALUE)) {
                for (enemy in LabyrinthEnemyDamageType.values()) {
                    val context = LabyrinthRoleDecisionContext(target % 5, enemy, target,
                        survivalRecovery = target % 2 == 0, preferSingleDamageSystem = true,
                        optimizeBossVanguardSynergy = true, effectiveCharacterIds = setOf("synthetic-1", "missing"))
                    val prepared = LabyrinthPreparedSearchScorer(config, context, roles)
                    val scratch = prepared.newScratch()
                    val baseline = LabyrinthTeamScorer(config)
                    for (index in roles.indices) {
                        assertExact("single-$configIndex-$target-$enemy-$index", baseline, context, prepared, scratch, listOf(roles[index]))
                    }
                    repeat(100) { index ->
                        val team = roles.shuffled(random).take(index % 5 + 1)
                        assertExact("random-$configIndex-$target-$enemy-$index", baseline, context, prepared, scratch, team)
                    }
                }
            }
        }
    }

    @Test fun pureTanksHealersCompositeSupportAndUniversalClassificationRemainSeparate() {
        fun role(id: String, type: LabyrinthRoleDamageType, tank: Double, healer: Double,
            support: LabyrinthRoleSupport = LabyrinthRoleSupport(), functions: LabyrinthRoleFunctions? = null) =
            LabyrinthRoleProfile(id, id, roleClass = "攻击者", attribute = LabyrinthCharacterAttribute.FIRE,
                damageType = type, position = 100, physicalDamagePotential = 0.0, magicDamagePotential = 0.0,
                support = support, functions = functions ?: LabyrinthRoleFunctions(pureTank = tank, pureHealer = healer))
        val tank = role("tank", LabyrinthRoleDamageType.PHYSICAL, 80.0, 0.0)
        val dual = role("dual", LabyrinthRoleDamageType.PHYSICAL, 80.0, 80.0)
        val healer = role("healer", LabyrinthRoleDamageType.MAGIC, 0.0, 80.0)
        val roles = listOf(tank, dual, healer,
            tank.copy(characterId = "physical-composite", support = LabyrinthRoleSupport(physicalOffense = 60.0)),
            healer.copy(characterId = "magic-composite", support = LabyrinthRoleSupport(magicOffense = 60.0)),
            healer.copy(characterId = "universal-composite", support = LabyrinthRoleSupport(universalOffense = 60.0)),
            tank.copy(characterId = "damage-composite", physicalDamagePotential = 60.0),
            tank.copy(characterId = "boss-specialist", functions = tank.functions.copy(bossMechanismValue = 50.0)),
            tank.copy(characterId = "self-specialist", functions = tank.functions.copy(selfSustain = 50.0)),
            tank.copy(characterId = "universal-vanguard", roleClass = "掩护者", functions = tank.functions.copy(reliableVanguard = 80.0)),
            healer.copy(characterId = "universal-healer", roleClass = "治疗者", functions = healer.functions.copy(healing = 35.0)),
            healer.copy(characterId = "universal-buffer", roleClass = "增益者", physicalTeamUplift = 0.08, magicTeamUplift = 0.08),
        )
        for (stacks in 0..5) for (recovery in listOf(false, true)) {
            val context = LabyrinthRoleDecisionContext(stacks, targetCount = 2, survivalRecovery = recovery)
            val config = batch3ScoringConfig()
            val baseline = LabyrinthTeamScorer(config)
            val prepared = LabyrinthPreparedSearchScorer(config, context, roles)
            val scratch = prepared.newScratch()
            for (first in roles.indices) for (second in first + 1 until roles.size) {
                assertExact("pure-$stacks-$recovery-$first-$second", baseline, context, prepared, scratch,
                    listOf(roles[first], roles[second]))
                assertExact("pure-full-$stacks-$recovery-$first-$second", baseline, context, prepared, scratch,
                    listOf(roles[first], roles[second], tank, dual, healer))
            }
        }
    }

    @Test fun capabilitySortingFallbackCountsAndRequirementsReuseOfficialSemantics() {
        val healFact = LabyrinthSkillEffectFact("heal", LabyrinthSkillEffectType.HEAL,
            LabyrinthSkillEffectSystem.UNIVERSAL, "1", target = LabyrinthSkillTarget(LabyrinthSkillTargetScope.ALLIES))
        val roles = (0..7).map { index ->
            LabyrinthRoleProfile("cap-$index", "cap-$index", skillFacts = if (index == 7) listOf(healFact) else emptyList(),
                functions = LabyrinthRoleFunctions(dot = if (index == 0) null else index * 13.0,
                    wideAoeCoverage = if (index % 2 == 0) 50.0 else null, enemyPush = index * 12.0,
                    healing = if (index == 0) null else 49.0 + index * 7.0, control = if (index > 3) 80.0 else null))
        }
        val strategy = LabyrinthExEncounterStrategy("numeric-test", "numeric-test", targetCount = 5,
            preferredCapabilities = linkedSetOf(LabyrinthEncounterCapability.HEAL, LabyrinthEncounterCapability.DOT),
            preferredCapabilityMemberTargets = mapOf(LabyrinthEncounterCapability.HEAL to 3, LabyrinthEncounterCapability.DOT to 9),
            fallbackPreferences = listOf(LabyrinthEncounterFallbackPreference(LabyrinthEncounterCapability.WIDE_AOE, 3,
                linkedSetOf(LabyrinthEncounterCapability.PUSH, LabyrinthEncounterCapability.HEAL), maximumCount = 2)),
            requirements = listOf(
                LabyrinthEncounterRequirement(linkedSetOf(LabyrinthEncounterCapability.CONTROL, LabyrinthEncounterCapability.DOT),
                    minimumCount = 3, label = "confirmed"),
                LabyrinthEncounterRequirement(setOf(LabyrinthEncounterCapability.SHIELD), minimumCount = 2, label = "unknown"),
                LabyrinthEncounterRequirement(setOf(LabyrinthEncounterCapability.HEAL), minimumCount = 8, minimumScore = 0.0, label = "zero threshold")),
        )
        val config = batch3ScoringConfig()
        for (preferEffective in listOf(false, true)) {
            val context = LabyrinthRoleDecisionContext(4, targetCount = 5,
                encounterStrategy = strategy.copy(preferEffectiveCharacters = preferEffective),
                effectiveCharacterIds = setOf("cap-1", "cap-3", "cap-7"))
            val prepared = LabyrinthPreparedSearchScorer(config, context, roles)
            val scratch = prepared.newScratch()
            val baseline = LabyrinthTeamScorer(config)
            for (mask in 1 until (1 shl roles.size)) {
                val team = roles.filterIndexed { index, _ -> mask and (1 shl index) != 0 }
                if (team.size <= 5) {
                    assertExact("capability-$preferEffective-$mask", baseline, context, prepared, scratch, team)
                    assertExact("capability-reversed-$preferEffective-$mask", baseline, context, prepared, scratch, team.reversed())
                }
            }
        }
    }

    @Test fun legalSignedZeroInfinityGeneratedNaNsAndAttributeFirstAppearanceStayExact() {
        assertTrue(runCatching { LabyrinthRoleDecisionContext(0, targetCount = 0) }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching { LabyrinthRoleProfile("nan", "nan", userScore = Double.NaN) }.exceptionOrNull() is IllegalArgumentException)
        val roles = (0..4).map { index ->
            LabyrinthRoleProfile("zero-$index", "zero-$index", position = 100,
                attribute = LabyrinthCharacterAttribute.values()[index / 2],
                damageType = if (index % 2 == 0) LabyrinthRoleDamageType.PHYSICAL else LabyrinthRoleDamageType.MAGIC,
                userScore = -0.0, physicalDamagePotential = if (index < 2) -0.0 else 50.0,
                magicDamagePotential = -0.0, support = LabyrinthRoleSupport(universalSurvival = -0.0),
                functions = LabyrinthRoleFunctions(healing = -0.0, regeneration = -0.0,
                    singleTargetDamage = -0.0, physicalDefenseDown = -0.0, magicDefenseDown = -0.0))
        }
        assertEquals(Long.MIN_VALUE, java.lang.Double.doubleToRawLongBits(roles[0].userScore!!))
        val config = batch3ScoringConfig()
        // Nonnegative config values permit +Infinity; NaN must arise through legal arithmetic,
        // rather than bypassing profile constructors or canonicalizing raw NaN bits in assertions.
        val configs = listOf(config, config.copy(maximumActiveAttributeBonuses = 1),
            config.copy(attributeDamageBonus = config.attributeDamageBonus + (2 to Double.POSITIVE_INFINITY)),
            config.copy(supportMultiplierAt100 = Double.POSITIVE_INFINITY),
            config.copy(damageSoftCeiling = Double.POSITIVE_INFINITY),
            config.copy(riskGatePenaltyWeight = Double.POSITIVE_INFINITY),
            config.copy(formationMissingFunctionPenalty = Double.POSITIVE_INFINITY))
        var encounteredNaN = false
        for ((configIndex, variant) in configs.withIndex()) {
            val context = LabyrinthRoleDecisionContext(0)
            val baseline = LabyrinthTeamScorer(variant)
            val prepared = LabyrinthPreparedSearchScorer(variant, context, roles)
            val scratch = prepared.newScratch()
            for (shift in roles.indices) {
                val ordered = roles.drop(shift) + roles.take(shift)
                for (team in listOf(ordered, ordered.reversed(), ordered.take(2), ordered.take(1))) {
                    assertExact("special-$configIndex-$shift-${team.size}", baseline, context, prepared, scratch, team)
                    encounteredNaN = encounteredNaN || scratch.score.isNaN() || scratch.systemScore.isNaN()
                }
            }
        }
        assertTrue("legal config must exercise NaN", encounteredNaN)
        val tied = roles.mapIndexed { index, role -> role.copy(physicalDamagePotential = if (index % 2 == 0) 40.0 else 0.0,
            magicDamagePotential = if (index % 2 == 0) 0.0 else 40.0, physicalTeamUplift = index * 0.1,
            modelStatus = "database-v2") }
        for (maximum in 1..2) {
            val variant = config.copy(maximumActiveAttributeBonuses = maximum)
            val context = LabyrinthRoleDecisionContext(4)
            val prepared = LabyrinthPreparedSearchScorer(variant, context, tied)
            val scratch = prepared.newScratch()
            for (shift in tied.indices) {
                assertExact("attribute-tie-$maximum-$shift", LabyrinthTeamScorer(variant), context, prepared, scratch,
                    tied.drop(shift) + tied.take(shift))
            }
        }
    }

    @Test fun identityUnionSourceMutationAndInterleavedScratchAreIsolated() {
        val input = batch3SyntheticInput(8)
        val original = input.profiles.values.toList()
        val changed = original[0].copy(userScore = 100.0, physicalDamagePotential = 100.0,
            magicDamagePotential = 0.0, position = 999, damageType = LabyrinthRoleDamageType.MAGIC)
        val source = (original + changed).toMutableList()
        val config = batch3ScoringConfig()
        val context = input.context
        val prepared = LabyrinthPreparedSearchScorer(config, context, source)
        val first = prepared.newScratch()
        val second = prepared.newScratch()
        val baseline = LabyrinthTeamScorer(config)
        source.clear()
        val unpreparedEqualCopy = original[0].copy()
        source.add(unpreparedEqualCopy)
        val team = original.take(5)
        assertExact("identity-original", baseline, context, prepared, first, team)
        val savedScore = java.lang.Double.doubleToRawLongBits(first.score)
        val savedSystem = java.lang.Double.doubleToRawLongBits(first.systemScore)
        val savedType = first.damageType
        assertExact("identity-replacement", baseline, context, prepared, second, listOf(changed) + team.drop(1))
        assertEquals(savedScore, java.lang.Double.doubleToRawLongBits(first.score))
        assertEquals(savedSystem, java.lang.Double.doubleToRawLongBits(first.systemScore))
        assertEquals(savedType, first.damageType)
        val working = team.toMutableList()
        assertExact("mutable-team", baseline, context, prepared, second, working)
        working.clear()
        working.add(original.last())
        assertExact("small-after-large", baseline, context, prepared, second, working)
        assertExact("large-after-small", baseline, context, prepared, second, team.reversed())
        assertTrue(runCatching { prepared.score(listOf(unpreparedEqualCopy), second) }.exceptionOrNull() is IllegalArgumentException)
        assertExact("after-rejected-identity", baseline, context, prepared, second, team)
        val other = LabyrinthPreparedSearchScorer(config, context.copy(targetCount = 3, defenseMarkStacks = 0), original)
        assertTrue(runCatching { other.score(team, second) }.exceptionOrNull() is IllegalArgumentException)
        assertExact("return-to-original-context", baseline, context, prepared, first, team)
    }

    @Test fun concurrentShardsSharePreparedDataButNeverScratchOrOutput() {
        val input = batch3SyntheticInput(15)
        val roles = input.profiles.values.toList()
        val config = batch3ScoringConfig()
        val context = input.context.copy(targetCount = 3, survivalRecovery = true)
        val baseline = LabyrinthTeamScorer(config)
        val prepared = LabyrinthPreparedSearchScorer(config, context, roles)
        val executor = Executors.newFixedThreadPool(4)
        try {
            val futures = (0..3).map { shard -> executor.submit(Callable {
                val scratch = prepared.newScratch()
                val random = Random(6100 + shard)
                repeat(200) { index ->
                    val team = roles.shuffled(random).take(index % 5 + 1)
                    assertExact("parallel-$shard-$index", baseline, context, prepared, scratch, team)
                }
            }) }
            futures.forEach { it.get(30, TimeUnit.SECONDS) }
        } finally {
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(30, TimeUnit.SECONDS))
        }
    }

    @Test fun preparedOptimizerRejectsContextOrScoringMismatchBeforeSelecting() {
        val input = batch3SyntheticInput(7)
        val roles = input.acquiredIds.map(input.profiles::getValue)
        val config = batch3ScoringConfig()
        val optimizer = LabyrinthTeamOptimizer(LabyrinthTeamScorer(config))
        val prepared = optimizer.prepareSearchScoring(roles, input.context)
        assertTrue(runCatching { optimizer.bestPreparedSystemFormation(roles,
            input.context.copy(defenseMarkStacks = input.context.defenseMarkStacks + 1), null, prepared)
        }.exceptionOrNull() is IllegalArgumentException)
        val other = LabyrinthTeamOptimizer(LabyrinthTeamScorer(config.copy(playerWeight = 0.1)))
        assertTrue(runCatching { other.bestPreparedSystemFormation(roles, input.context, null, prepared)
        }.exceptionOrNull() is IllegalArgumentException)
        batch3AssertExact("equal-context-prepared", optimizer.bestSystemFormation(roles, input.context),
            optimizer.bestPreparedSystemFormation(roles, input.context.copy(), null, prepared))
    }

    private fun assertExact(label: String, baseline: LabyrinthTeamScorer, context: LabyrinthRoleDecisionContext,
        prepared: LabyrinthPreparedSearchScorer, scratch: LabyrinthPreparedSearchScorer.Scratch,
        members: List<LabyrinthRoleProfile>) {
        val expected = baseline.evaluateForSearch(members, context)
        prepared.score(members, scratch)
        assertEquals("$label.score.rawBits", java.lang.Double.doubleToRawLongBits(expected.score),
            java.lang.Double.doubleToRawLongBits(scratch.score))
        assertEquals("$label.systemScore.rawBits", java.lang.Double.doubleToRawLongBits(expected.systemScore),
            java.lang.Double.doubleToRawLongBits(scratch.systemScore))
        assertEquals("$label.damageType", expected.damageType, scratch.damageType)
    }
}
