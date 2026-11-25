package com.landosol.toolbox.labyrinth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LabyrinthTeamCompositionPolicyTest {
    private val config = LabyrinthTeamScoringConfig(attributeDamageBonus = (1..5).associateWith { 0.0 })
    private val scorer = LabyrinthTeamScorer(config)
    private fun role(id: String, tank: Boolean = false, healer: Boolean = false) = LabyrinthRoleProfile(
        characterId = id, displayName = id,
        roleClass = if (tank) "掩护者" else if (healer) "治疗者" else "攻击者",
        attribute = LabyrinthCharacterAttribute.LIGHT,
        damageType = LabyrinthRoleDamageType.PHYSICAL,
        position = if (tank) 1 else 10,
        userScore = 80.0,
        physicalDamagePotential = if (tank || healer) 0.0 else 80.0,
        magicDamagePotential = 0.0,
        functions = LabyrinthRoleFunctions(
            reliableVanguard = if (tank) 100.0 else 0.0,
            selfSustain = if (tank) 100.0 else 0.0,
            healing = if (healer) 100.0 else 0.0,
            pureTank = if (tank) 100.0 else 0.0,
            pureHealer = if (healer) 100.0 else 0.0,
            singleTargetDamage = if (tank || healer) 0.0 else 80.0,
        ),
    )
    private fun tankTeam() = listOf(role("tank1", tank = true), role("tank2", tank = true),
        role("heal", healer = true), role("dps1"), role("dps2"))

    /**
     * The parallel combination walk must rank exactly like the serial one, ties included. A
     * 24-role pool is C(24,5) = 42,504 combinations, above the parallel threshold; many roles
     * share identical stats so score ties are common and the tie-break is actually exercised.
     */
    @Test
    fun `parallel formation search ranks identically to the serial walk`() {
        val attributes = listOf(LabyrinthCharacterAttribute.FIRE, LabyrinthCharacterAttribute.WATER,
            LabyrinthCharacterAttribute.WIND, LabyrinthCharacterAttribute.LIGHT, LabyrinthCharacterAttribute.DARK)
        val pool = (0 until 24).map { index ->
            val tank = index % 8 == 0
            val healer = index % 8 == 1
            LabyrinthRoleProfile(
                characterId = "r$index", displayName = "r$index",
                roleClass = if (tank) "掩护者" else if (healer) "治疗者" else "攻击者",
                attribute = attributes[index % 5],
                damageType = if (index % 3 == 0) LabyrinthRoleDamageType.MAGIC else LabyrinthRoleDamageType.PHYSICAL,
                position = if (tank) 1 + index else 10 + index,
                userScore = 60.0 + (index % 4) * 10,
                physicalDamagePotential = if (tank || healer) 0.0 else if (index % 3 == 0) 0.0 else 50.0 + (index % 5) * 8,
                magicDamagePotential = if (tank || healer) 0.0 else if (index % 3 == 0) 50.0 + (index % 5) * 8 else 0.0,
                functions = LabyrinthRoleFunctions(
                    reliableVanguard = if (tank) 100.0 else 0.0, selfSustain = if (tank) 100.0 else 0.0,
                    healing = if (healer) 100.0 else 0.0, pureTank = if (tank) 100.0 else 0.0,
                    pureHealer = if (healer) 100.0 else 0.0,
                    singleTargetDamage = if (tank || healer) 0.0 else 60.0,
                ),
            )
        }
        val bonusConfig = LabyrinthTeamScoringConfig(
            attributeDamageBonus = mapOf(1 to 0.0, 2 to 0.08, 3 to 0.16, 4 to 0.25, 5 to 0.75),
            teamSearchBeamWidth = 40,
        )
        val serial = LabyrinthTeamOptimizer(LabyrinthTeamScorer(bonusConfig), searchThreads = 1)
        val parallel = LabyrinthTeamOptimizer(LabyrinthTeamScorer(bonusConfig), searchThreads = 4)
        for (context in listOf(
            legacyFrontlineContext(0),
            legacyFrontlineContext(3, targetCount = 3, preferSingleDamageSystem = true),
        )) {
            fun ids(list: List<LabyrinthTeamEvaluation>) = list.map { e -> e.members.map { it.characterId } to e.score }
            assertEquals(ids(serial.rankedFormations(pool, context)), ids(parallel.rankedFormations(pool, context)))
            assertEquals(ids(serial.rankedBattleFormations(pool, context)), ids(parallel.rankedBattleFormations(pool, context)))
            assertEquals(
                ids(serial.rankedFormations(pool, context, requiredCharacterId = "r7")),
                ids(parallel.rankedFormations(pool, context, requiredCharacterId = "r7")),
            )
        }
    }

    /**
     * The scorer's attribute-bonus pass was rewritten from a groupingBy/sort/take chain into a
     * single array pass. This pins the observable contract of that chain: first-appearance order
     * for attribute counts, top-N by incremental damage with first-appearance tie-breaking, and
     * bonuses only for attributes that actually earn one.
     */
    @Test
    fun `attribute bonus selection keeps first appearance order and stable top two`() {
        val bonusConfig = LabyrinthTeamScoringConfig(
            attributeDamageBonus = mapOf(1 to 0.0, 2 to 0.08, 3 to 0.16, 4 to 0.25, 5 to 0.75),
            maximumActiveAttributeBonuses = 2,
        )
        val bonusScorer = LabyrinthTeamScorer(bonusConfig)
        fun attributed(id: String, attribute: LabyrinthCharacterAttribute?, damage: Double) = LabyrinthRoleProfile(
            characterId = id, displayName = id, roleClass = "攻击者", attribute = attribute,
            damageType = LabyrinthRoleDamageType.PHYSICAL, position = 10, userScore = 80.0,
            physicalDamagePotential = damage, magicDamagePotential = 0.0,
            functions = LabyrinthRoleFunctions(singleTargetDamage = damage),
        )
        val context = legacyFrontlineContext(0)

        // DARK appears first but is a singleton (no bonus); WATER and FIRE both have pairs with
        // identical damage, so their tie resolves to appearance order: WATER before FIRE.
        val tied = bonusScorer.evaluate(
            listOf(
                attributed("d", LabyrinthCharacterAttribute.DARK, 90.0),
                attributed("w1", LabyrinthCharacterAttribute.WATER, 50.0),
                attributed("f1", LabyrinthCharacterAttribute.FIRE, 50.0),
                attributed("w2", LabyrinthCharacterAttribute.WATER, 50.0),
                attributed("f2", LabyrinthCharacterAttribute.FIRE, 50.0),
            ),
            context,
        )
        assertEquals(
            listOf(LabyrinthCharacterAttribute.DARK, LabyrinthCharacterAttribute.WATER, LabyrinthCharacterAttribute.FIRE),
            tied.attributeCounts.keys.toList(),
        )
        assertEquals(listOf(1, 2, 2), tied.attributeCounts.values.toList())
        assertEquals(
            listOf(LabyrinthCharacterAttribute.WATER, LabyrinthCharacterAttribute.FIRE),
            tied.activeAttributeBonuses.keys.toList(),
        )
        assertEquals(listOf(0.08, 0.08), tied.activeAttributeBonuses.values.toList())

        // Two attributes qualify with different incremental damage: the stronger pair is listed
        // first even though it appeared later, and a singleton earns nothing.
        val threeWay = bonusScorer.evaluate(
            listOf(
                attributed("l1", LabyrinthCharacterAttribute.LIGHT, 10.0),
                attributed("l2", LabyrinthCharacterAttribute.LIGHT, 10.0),
                attributed("w1", LabyrinthCharacterAttribute.WATER, 40.0),
                attributed("w2", LabyrinthCharacterAttribute.WATER, 40.0),
                attributed("f1", LabyrinthCharacterAttribute.FIRE, 30.0),
            ),
            context,
        )
        assertEquals(
            listOf(LabyrinthCharacterAttribute.WATER, LabyrinthCharacterAttribute.LIGHT),
            threeWay.activeAttributeBonuses.keys.toList(),
        )
        assertTrue(LabyrinthCharacterAttribute.FIRE !in threeWay.activeAttributeBonuses)

        // With room for one bonus only, the later, stronger WATER pair displaces LIGHT.
        val displaced = LabyrinthTeamScorer(bonusConfig.copy(maximumActiveAttributeBonuses = 1)).evaluate(
            listOf(
                attributed("l1", LabyrinthCharacterAttribute.LIGHT, 10.0),
                attributed("l2", LabyrinthCharacterAttribute.LIGHT, 10.0),
                attributed("w1", LabyrinthCharacterAttribute.WATER, 40.0),
                attributed("w2", LabyrinthCharacterAttribute.WATER, 40.0),
                attributed("n", null, 30.0),
            ),
            context,
        )
        assertEquals(listOf(LabyrinthCharacterAttribute.WATER), displaced.activeAttributeBonuses.keys.toList())
        assertEquals(listOf(LabyrinthCharacterAttribute.LIGHT, LabyrinthCharacterAttribute.WATER),
            displaced.attributeCounts.keys.toList())

        val none = bonusScorer.evaluate(listOf(attributed("n1", null, 30.0), attributed("n2", null, 30.0)), context)
        assertTrue(none.attributeCounts.isEmpty())
        assertTrue(none.activeAttributeBonuses.isEmpty())
    }

    @Test
    fun `second pure role costs four six eight points as defense grows`() {
        for ((stacks, penalty) in listOf(0 to 4.0, 1 to 4.0, 2 to 6.0, 3 to 6.0, 4 to 8.0, 15 to 8.0)) {
            val context = legacyFrontlineContext(stacks)
            val actual = scorer.evaluate(tankTeam(), context)
            val noPenalty = LabyrinthTeamScorer(config.copy(duplicatePureRolePenalty = 0.0)).evaluate(tankTeam(), context)
            assertEquals(2, actual.pureTankCount)
            assertEquals(1, actual.pureHealerCount)
            assertEquals(penalty, actual.duplicateRolePenalty, 1e-9)
            assertEquals(noPenalty.score - penalty, actual.score, 1e-9)
            assertEquals(noPenalty.systemScore - penalty, actual.systemScore, 1e-9)
            assertEquals(-penalty, actual.components.getValue("重复纯职能"), 1e-9)
            assertTrue(actual.reasons.first().contains("重复纯职能"))
        }
    }

    @Test
    fun `two tanks and two healers accumulate independent penalties`() {
        val team = listOf(role("t1", tank = true), role("t2", tank = true),
            role("h1", healer = true), role("h2", healer = true), role("dps"))
        val actual = scorer.evaluate(team, legacyFrontlineContext(4))
        assertEquals(2, actual.pureHealerCount)
        assertEquals(16.0, actual.duplicateRolePenalty, 1e-9)
    }

    @Test
    fun `class labels alone do not impose caps and no healer slot is mandatory`() {
        val team = (0 until 5).map { role("r$it").copy(roleClass = if (it < 3) "掩护者" else "治疗者") }
        val evaluation = scorer.evaluate(team, legacyFrontlineContext(4))
        assertEquals(0, evaluation.pureTankCount)
        assertEquals(0, evaluation.pureHealerCount)
        assertEquals(0.0, evaluation.duplicateRolePenalty, 1e-9)
        assertEquals(5, requireNotNull(LabyrinthTeamOptimizer(scorer).bestFormation(team,
            legacyFrontlineContext(4))).members.size)
    }

    @Test
    fun `composite damage buffs and matching defense down exempt defensive roles`() {
        val pure = tankTeam()[1]
        val composites = listOf(
            pure.copy(physicalDamagePotential = 60.0),
            pure.copy(support = LabyrinthRoleSupport(universalOffense = 60.0)),
            pure.copy(support = LabyrinthRoleSupport(physicalOffense = 60.0)),
            pure.copy(functions = pure.functions.copy(physicalDefenseDown = 60.0)),
        )
        for (composite in composites) {
            val team = tankTeam().toMutableList().also { it[1] = composite }
            val actual = scorer.evaluate(team, legacyFrontlineContext(4))
            assertEquals(1, actual.pureTankCount)
            assertEquals(0.0, actual.duplicateRolePenalty, 1e-9)
        }
        val offSystem = tankTeam().toMutableList().also {
            it[1] = pure.copy(support = LabyrinthRoleSupport(magicOffense = 100.0))
        }
        assertEquals(8.0, scorer.evaluate(offSystem, legacyFrontlineContext(4)).duplicateRolePenalty, 1e-9)
    }

    @Test
    fun `damage exemption follows target scenario and dual tags count only once`() {
        val team = tankTeam().toMutableList().also {
            it[1] = it[1].copy(physicalDamage1Target = 20.0, physicalDamage3Target = 80.0,
                functions = it[1].functions.copy(pureHealer = 100.0))
        }
        assertEquals(8.0, scorer.evaluate(team, legacyFrontlineContext(4, targetCount = 1)).duplicateRolePenalty, 1e-9)
        assertEquals(0.0, scorer.evaluate(team, legacyFrontlineContext(4, targetCount = 3)).duplicateRolePenalty, 1e-9)
    }

    @Test
    fun `missing survival and explicit fallback halve but never eliminate penalties`() {
        val withoutHealing = tankTeam().toMutableList().also { it[2] = role("dps3") }
        assertEquals(2.0, scorer.evaluate(withoutHealing, legacyFrontlineContext(0)).duplicateRolePenalty, 1e-9)
        assertEquals(4.0, scorer.evaluate(tankTeam(), legacyFrontlineContext(4, survivalRecovery = true))
            .duplicateRolePenalty, 1e-9)
        val searcher = LabyrinthTeamPlanSearcher(LabyrinthTeamOptimizer(scorer),
            LabyrinthTeamPlanSearchConfig(oneTeamKillScore = 0.0, mainTeamScore = 0.0,
                cleanupTeamScore = 0.0, mainPlusCleanupCombinedScore = 0.0,
                stableTeamScore = 0.0, fallbackTeamScore = 0.0, threeTeamCombinedScore = 0.0))
        assertEquals(8.0, searcher.initialSearch(tankTeam(), legacyFrontlineContext(4)).teams.single()
            .duplicateRolePenalty, 1e-9)
        assertEquals(4.0, searcher.fallbackSearch(tankTeam(), legacyFrontlineContext(4)).teams.single()
            .duplicateRolePenalty, 1e-9)
    }

    @Test
    fun `optimizer prefers useful replacement but still returns forced double tank teams`() {
        val roster = tankTeam() + role("extraDamage")
        val best = requireNotNull(LabyrinthTeamOptimizer(scorer).bestFormation(roster, legacyFrontlineContext(4)))
        assertEquals(1, best.pureTankCount)
        assertTrue(best.members.any { it.characterId == "extraDamage" })
        assertEquals(5, requireNotNull(LabyrinthTeamOptimizer(scorer).bestFormation(tankTeam(),
            legacyFrontlineContext(4))).members.size)
    }

    @Test
    fun `invalid rule parameters fail fast`() {
        for (invalid in listOf<() -> Any>(
            { config.copy(duplicatePureRolePenalty = -1.0) },
            { config.copy(duplicatePureRolePenalty = Double.NaN) },
            { config.copy(compositeOffenseThreshold = 101.0) },
            { config.copy(lowDefenseDuplicateMultiplier = 1.1) },
        )) {
            assertTrue(runCatching(invalid).exceptionOrNull() is IllegalArgumentException)
        }
    }
}
