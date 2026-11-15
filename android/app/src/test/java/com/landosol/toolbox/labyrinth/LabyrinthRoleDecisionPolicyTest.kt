package com.landosol.toolbox.labyrinth

import com.landosol.toolbox.labyrinth.vision.LabyrinthLinkChoicePriority
import com.landosol.toolbox.labyrinth.vision.LabyrinthLinkElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LabyrinthRoleDecisionPolicyTest {
    private val scoringConfig = LabyrinthTeamScoringConfig(
        attributeDamageBonus = mapOf(
            1 to 0.0,
            2 to 0.08,
            3 to 0.16,
            4 to 0.25,
            5 to 0.75,
        ),
    )
    private val scorer = LabyrinthTeamScorer(scoringConfig)
    private val optimizer = LabyrinthTeamOptimizer(scorer)
    private val rolePolicy = LabyrinthRoleChoicePolicy(optimizer)

    @Test
    fun `visible reward collision is logged and all three cards remain eligible`() {
        val owned = listOf(
            physical("front", "前卫", position = 1, reliableVanguard = 100.0),
            physical("ally2", "队友二", position = 2),
            physical("ally3", "队友三", position = 3),
            physical("ally4", "队友四", position = 4),
        )
        val candidates = listOf(
            physical("stale", "本地误记角色", position = 5),
            physical("other1", "候选二", position = 5),
            physical("other2", "候选三", position = 5),
        )
        val profiles = (owned + candidates).associateBy(LabyrinthRoleProfile::characterId)
        val context = legacyFrontlineContext(defenseMarkStacks = 0)
        val baseline = rolePolicy.chooseOneRole(
            candidates.map(LabyrinthRoleProfile::characterId),
            owned.map(LabyrinthRoleProfile::characterId).toSet(), profiles, context,
        ) as LabyrinthOneRoleDecision.Ready
        val recovered = rolePolicy.chooseOneRole(
            candidates.map(LabyrinthRoleProfile::characterId),
            owned.map(LabyrinthRoleProfile::characterId).toSet() + "stale", profiles, context,
        ) as LabyrinthOneRoleDecision.Ready

        assertEquals(baseline.chosen.characterId, recovered.chosen.characterId)
        assertEquals(3, recovered.rankings.size)
        assertTrue(recovered.reasons.any { it.contains("本地已获得名单冲突") })
    }

    @Test
    fun `connect attribute remains fixed and ignores all role decision inputs`() {
        val priority = LabyrinthLinkChoicePriority.DEFAULT

        assertEquals(
            LabyrinthLinkElement.DARK,
            priority.chooseConnectAttribute(
                listOf(LabyrinthLinkElement.WATER, LabyrinthLinkElement.DARK, LabyrinthLinkElement.WIND),
            ),
        )
        assertEquals(
            LabyrinthLinkElement.LIGHT,
            priority.chooseConnectAttribute(
                listOf(LabyrinthLinkElement.WATER, LabyrinthLinkElement.LIGHT),
            ),
        )
    }

    @Test
    fun `new year karyl uses displacement only when wide aoe core is incomplete`() {
        val strategy = requireNotNull(LabyrinthExEncounterCatalog.matchObservedName("凯露（新年）的暗影"))
        val fixed = listOf(
            physical("front", "前卫", position = 1, reliableVanguard = 100.0, wideAoe = 80.0),
            physical("wide2", "广域二", position = 2, wideAoe = 80.0),
            physical("d3", "输出三", position = 3),
            physical("d4", "输出四", position = 4),
        )
        val noMove = physical("move", "普通位", position = 5)
        val push = physical("move", "击退位", position = 5, enemyPush = 82.0)
        val context = legacyFrontlineContext(
            defenseMarkStacks = 4,
            targetCount = 5,
            encounterStrategy = strategy,
        )

        val baseline = scorer.evaluate(fixed + noMove, context)
        val rescued = scorer.evaluate(fixed + push, context)
        assertEquals(
            scoringConfig.encounterPreferredCapabilityBonus * 0.82,
            rescued.score - baseline.score,
            0.000001,
        )

        val fullWide = fixed.toMutableList().also {
            it[2] = physical("wide3", "广域三", position = 3, wideAoe = 80.0)
        }
        val fullWithoutMove = scorer.evaluate(fullWide + noMove, context)
        val fullWithMove = scorer.evaluate(fullWide + push, context)
        assertEquals(fullWithoutMove.score, fullWithMove.score, 0.000001)
    }

    @Test
    fun `role choice refuses incomplete profile data instead of guessing`() {
        val profiles = listOf(
            physical("owned", "已有角色", position = 1, reliableVanguard = 100.0),
            physical("left", "左候选"),
            physical("right", "右候选"),
        ).associateBy(LabyrinthRoleProfile::characterId)

        val decision = rolePolicy.chooseOneRole(
            candidateIds = listOf("left", "missing", "right"),
            acquiredCharacterIds = setOf("owned"),
            profiles = profiles,
            context = legacyFrontlineContext(defenseMarkStacks = 0),
        ) as LabyrinthOneRoleDecision.Unavailable

        assertEquals(listOf("missing"), decision.missingProfileIds)
        assertTrue(decision.reason.contains("不盲选"))
    }

    @Test
    fun `strict role choice refuses high rated profile with unknown damage type and position`() {
        val acquired = listOf(
            physical("owned1", "已有一", position = 1, reliableVanguard = 100.0),
            physical("owned2", "已有二"),
            physical("owned3", "已有三"),
            physical("owned4", "已有四"),
        )
        val violet = physical("1331", "薇欧莉特", quality = 100.0).copy(
            damageType = null,
            position = null,
        )
        val candidates = listOf(violet, physical("other1", "候选二"), physical("other2", "候选三"))
        val profiles = (acquired + candidates).associateBy(LabyrinthRoleProfile::characterId)

        val decision = rolePolicy.chooseOneRole(
            candidateIds = candidates.map(LabyrinthRoleProfile::characterId),
            acquiredCharacterIds = acquired.map(LabyrinthRoleProfile::characterId).toSet(),
            profiles = profiles,
            context = legacyFrontlineContext(defenseMarkStacks = 4),
        ) as LabyrinthOneRoleDecision.Unavailable

        assertEquals(listOf("1331"), decision.incompleteProfileIds)
        assertTrue(decision.reason.contains("严格决策"))
    }

    @Test
    fun `fifth same attribute can outweigh a stronger off attribute candidate`() {
        val acquired = listOf(
            physical(
                "front",
                "光前卫",
                attribute = LabyrinthCharacterAttribute.LIGHT,
                position = 1,
                quality = 65.0,
                damage = 55.0,
                reliableVanguard = 100.0,
                physicalDefenseDown = 70.0,
            ),
            physical(
                "light2",
                "光二",
                attribute = LabyrinthCharacterAttribute.LIGHT,
                position = 2,
                quality = 65.0,
                damage = 60.0,
                aoe = 70.0,
            ),
            physical(
                "light3",
                "光三",
                attribute = LabyrinthCharacterAttribute.LIGHT,
                position = 3,
                quality = 65.0,
                damage = 60.0,
                single = 70.0,
            ),
            physical(
                "light4",
                "光四",
                attribute = LabyrinthCharacterAttribute.LIGHT,
                position = 4,
                quality = 65.0,
                damage = 60.0,
            ),
        )
        val candidates = listOf(
            physical(
                "light5",
                "较弱光角色",
                attribute = LabyrinthCharacterAttribute.LIGHT,
                quality = 72.0,
                damage = 45.0,
            ),
            physical(
                "fire",
                "较强火角色",
                attribute = LabyrinthCharacterAttribute.FIRE,
                quality = 72.0,
                damage = 72.0,
            ),
            physical(
                "water",
                "水角色",
                attribute = LabyrinthCharacterAttribute.WATER,
                quality = 60.0,
                damage = 55.0,
            ),
        )
        val profiles = (acquired + candidates).associateBy(LabyrinthRoleProfile::characterId)

        val decision = rolePolicy.chooseOneRole(
            candidateIds = candidates.map(LabyrinthRoleProfile::characterId),
            acquiredCharacterIds = acquired.map(LabyrinthRoleProfile::characterId).toSet(),
            profiles = profiles,
            context = legacyFrontlineContext(defenseMarkStacks = 4),
        ) as LabyrinthOneRoleDecision.Ready

        assertEquals("light5", decision.chosen.characterId)
        assertEquals(0.75, decision.rankings.first().candidateTeam.activeAttributeBonuses.getValue(
            LabyrinthCharacterAttribute.LIGHT,
        ), 0.0001)
        assertTrue(decision.reasons.any { it.contains("5人档") })
    }

    @Test
    fun `four defense lowers pure healer and prefers damage with compound functions`() {
        val acquired = listOf(
            physical(
                "front",
                "前卫",
                position = 1,
                quality = 65.0,
                damage = 45.0,
                reliableVanguard = 100.0,
                physicalDefenseDown = 75.0,
            ),
            physical("d2", "输出二", quality = 65.0, damage = 65.0, aoe = 70.0),
            physical("d3", "输出三", quality = 65.0, damage = 65.0, single = 70.0),
            physical("d4", "输出四", quality = 60.0, damage = 60.0),
        )
        val healer = LabyrinthRoleProfile(
            characterId = "healer",
            displayName = "纯治疗",
            roleClass = "healer",
            attribute = LabyrinthCharacterAttribute.WATER,
            damageType = LabyrinthRoleDamageType.NONE,
            position = 8,
            baseQuality = 55.0,
            physicalDamagePotential = 0.0,
            magicDamagePotential = 0.0,
            support = LabyrinthRoleSupport(universalSurvival = 100.0),
            functions = LabyrinthRoleFunctions(healing = 100.0, pureHealer = 100.0),
        )
        val candidates = listOf(
            healer,
            physical("compound", "复合输出", quality = 68.0, damage = 88.0, aoe = 80.0),
            physical("average", "普通输出", quality = 62.0, damage = 62.0),
        )
        val profiles = (acquired + candidates).associateBy(LabyrinthRoleProfile::characterId)

        val decision = rolePolicy.chooseOneRole(
            candidateIds = candidates.map(LabyrinthRoleProfile::characterId),
            acquiredCharacterIds = acquired.map(LabyrinthRoleProfile::characterId).toSet(),
            profiles = profiles,
            context = legacyFrontlineContext(defenseMarkStacks = 4),
        ) as LabyrinthOneRoleDecision.Ready

        assertEquals("compound", decision.chosen.characterId)
        val healerEvaluation = decision.rankings.single { it.characterId == "healer" }.candidateTeam
        assertTrue(healerEvaluation.components.getValue("4守备后纯职能扣分") < 0.0)
    }

    @Test
    fun `team type is mixed when non universal members span physical and magic systems`() {
        val team = listOf(
            physical("p1", "物理一", position = 1, damage = 90.0, reliableVanguard = 100.0),
            physical("p2", "物理二", damage = 90.0, physicalDefenseDown = 80.0),
            physical("p3", "物理三", damage = 90.0, aoe = 70.0, single = 70.0),
            magic(
                "m-support",
                "魔法伤害物理辅助",
                damage = 8.0,
                support = LabyrinthRoleSupport(physicalOffense = 100.0),
            ),
            magic(
                "u-support",
                "通用辅助",
                damage = 5.0,
                support = LabyrinthRoleSupport(universalOffense = 100.0),
            ),
        )

        val evaluation = scorer.evaluate(team, legacyFrontlineContext(defenseMarkStacks = 4))

        assertEquals(LabyrinthTeamDamageType.MIXED, evaluation.damageType)
        assertTrue(evaluation.physicalRatio >= 0.70)
    }

    @Test
    fun `universal healer can join physical core but magic breaker cannot`() {
        val physicalCore = listOf(
            combatRole("p1", "物理一", physical = 90.0, position = 1, reliableVanguard = 100.0),
            combatRole("p2", "物理二", physical = 90.0, position = 2),
            combatRole("p3", "物理三", physical = 90.0, position = 3),
            combatRole("p4", "物理四", physical = 90.0, position = 4),
        )
        val universalHealer = combatRole(
            "healer",
            "通用治疗",
            type = LabyrinthRoleDamageType.MAGIC,
            roleClass = "治疗者",
            magic = 5.0,
            healing = 90.0,
            position = 5,
        )
        val magicBreaker = combatRole(
            "breaker",
            "法术破防",
            type = LabyrinthRoleDamageType.MAGIC,
            roleClass = "破防者",
            magic = 35.0,
            magicDefenseDown = 90.0,
            position = 5,
        )

        assertEquals(
            LabyrinthTeamDamageType.PHYSICAL,
            scorer.evaluate(
                physicalCore + universalHealer,
                legacyFrontlineContext(defenseMarkStacks = 4),
            ).damageType,
        )
        assertEquals(
            LabyrinthTeamDamageType.MIXED,
            scorer.evaluate(
                physicalCore + magicBreaker,
                legacyFrontlineContext(defenseMarkStacks = 4),
            ).damageType,
        )
    }

    @Test
    fun `official effective effect roles only raise the system side before player blending`() {
        val team = listOf(
            physical("front", "前卫", position = 1, reliableVanguard = 100.0, physicalDefenseDown = 80.0),
            physical("effective1", "有效一", position = 2, damage = 70.0, aoe = 70.0),
            physical("effective2", "有效二", position = 3, damage = 70.0, aoe = 70.0),
            physical("d4", "输出四", position = 4, damage = 70.0, aoe = 70.0),
            physical("d5", "输出五", position = 5, damage = 70.0, aoe = 70.0),
        )
        val baseline = scorer.evaluate(team, legacyFrontlineContext(defenseMarkStacks = 4, targetCount = 3))
        val prioritized = scorer.evaluate(
            team,
            legacyFrontlineContext(
                defenseMarkStacks = 4,
                targetCount = 3,
                effectiveCharacterIds = setOf("effective1", "effective2"),
            ),
        )

        val expectedSystemDelta = scoringConfig.encounterEffectiveCharacterBonus * 2 / team.size
        assertEquals(expectedSystemDelta, prioritized.systemScore - baseline.systemScore, 0.000001)
        assertEquals(
            expectedSystemDelta * prioritized.effectivePlayerWeight.let { 1.0 - it },
            prioritized.score - baseline.score,
            0.000001,
        )
        assertTrue(prioritized.reasons.any { it.contains("每名系统侧+8") })
    }

    @Test
    fun `large battle roster does not force reserve a low scoring effective effect role`() {
        val tank = physical(
            "tank",
            "前排T",
            position = 1,
            damage = 35.0,
            reliableVanguard = 100.0,
            physicalDefenseDown = 80.0,
            roleClass = "掩护者",
        )
        val generic = (1..23).map { index ->
            physical(
                "generic$index",
                "普通$index",
                position = index + 1,
                damage = 70.0,
                aoe = 70.0,
            )
        }
        // The Effective Effect hint is intentionally insufficient to rescue a role whose weighted
        // individual score falls below the bounded search pool cutoff.
        val effective = physical(
            "effective-low",
            "低通用分有效角色",
            position = 30,
            damage = 50.0,
            aoe = 70.0,
        )
        val context = legacyFrontlineContext(
            defenseMarkStacks = 4,
            targetCount = 3,
            effectiveCharacterIds = setOf(effective.characterId),
        )

        val pool = optimizer.battleSearchPool(listOf(tank) + generic + effective, context)

        assertEquals(scoringConfig.exhaustiveRosterLimit, pool.size)
        assertFalse(
            "effective role must not be force-reserved into the bounded battle search pool",
            pool.any { it.characterId == effective.characterId },
        )
    }

    @Test
    fun `modeled physical support does not raise a pure magic damage chain`() {
        val magicCore = (1..4).map { index ->
            combatRole("m$index", "法师$index", magic = 20.0, position = index + 1)
        }
        val neutral = combatRole(
            "support",
            "物理专辅",
            type = LabyrinthRoleDamageType.NONE,
            modelStatus = "database-v2",
            physicalTeamUplift = 0.0,
            magicTeamUplift = 0.0,
        )
        val physicalOnly = neutral.copy(physicalTeamUplift = 0.60)

        val baseline = scorer.evaluate(magicCore + neutral, legacyFrontlineContext(4))
        val supported = scorer.evaluate(magicCore + physicalOnly, legacyFrontlineContext(4))

        assertEquals(baseline.magicDamage, supported.magicDamage, 0.000001)
        assertEquals(0.0, supported.magicTeamUplift, 0.000001)
    }

    @Test
    fun `modeled magic support does not raise a pure physical damage chain`() {
        val physicalCore = (1..4).map { index ->
            combatRole("p$index", "战士$index", physical = 20.0, position = index + 1)
        }
        val neutral = combatRole(
            "support",
            "法术专辅",
            type = LabyrinthRoleDamageType.NONE,
            modelStatus = "database-v2",
            physicalTeamUplift = 0.0,
            magicTeamUplift = 0.0,
        )
        val magicOnly = neutral.copy(magicTeamUplift = 0.60)

        val baseline = scorer.evaluate(physicalCore + neutral, legacyFrontlineContext(4))
        val supported = scorer.evaluate(physicalCore + magicOnly, legacyFrontlineContext(4))

        assertEquals(baseline.physicalDamage, supported.physicalDamage, 0.000001)
        assertEquals(0.0, supported.physicalTeamUplift, 0.000001)
    }

    @Test
    fun `modeled universal support raises both matching output chains`() {
        val core = listOf(
            combatRole("p1", "物理一", physical = 30.0, position = 1),
            combatRole("p2", "物理二", physical = 30.0, position = 2),
            combatRole("m1", "法术一", magic = 30.0, position = 3),
            combatRole("m2", "法术二", magic = 30.0, position = 4),
        )
        val neutral = combatRole(
            "support",
            "通辅",
            type = LabyrinthRoleDamageType.NONE,
            modelStatus = "database-v2",
            physicalTeamUplift = 0.0,
            magicTeamUplift = 0.0,
        )
        val universal = neutral.copy(physicalTeamUplift = 0.25, magicTeamUplift = 0.25)

        val baseline = scorer.evaluate(core + neutral, legacyFrontlineContext(4))
        val supported = scorer.evaluate(core + universal, legacyFrontlineContext(4))

        assertTrue(supported.physicalDamage > baseline.physicalDamage)
        assertTrue(supported.magicDamage > baseline.magicDamage)
    }

    @Test
    fun `modeled support uplifts compound multiplicatively with configured caps`() {
        val core = listOf(
            combatRole("p1", "物理一", physical = 50.0, position = 1),
            combatRole("p2", "物理二", physical = 50.0, position = 2),
            combatRole("f1", "辅助一", type = LabyrinthRoleDamageType.NONE, modelStatus = "database-v2", physicalTeamUplift = 0.50),
            combatRole("f2", "辅助二", type = LabyrinthRoleDamageType.NONE, modelStatus = "database-v2", physicalTeamUplift = 0.50),
            combatRole("f3", "异常大辅助", type = LabyrinthRoleDamageType.NONE, modelStatus = "database-v2", physicalTeamUplift = 9.0),
        )

        val evaluation = scorer.evaluate(core, legacyFrontlineContext(4))

        // (1.5 * 1.5 * 1.8) - 1 would be 3.05, then the team cap keeps it at +200%.
        assertEquals(2.0, evaluation.physicalTeamUplift, 0.000001)
    }

    @Test
    fun `missing player ratings transfer their weight to system score`() {
        val rated = (1..4).map { index ->
            combatRole("r$index", "有评分$index", physical = 20.0, position = index, userScore = 100.0)
        }
        val unrated = combatRole("u", "未评分", physical = 20.0, position = 5, userScore = null)

        val evaluation = scorer.evaluate(rated + unrated, legacyFrontlineContext(4))

        assertEquals(0.80, evaluation.playerCoverage, 0.000001)
        assertEquals(0.56, evaluation.effectivePlayerWeight, 0.000001)
        assertEquals(0.44, 1.0 - evaluation.effectivePlayerWeight, 0.000001)
        assertEquals(100.0, evaluation.playerScore ?: error("missing rated average"), 0.000001)
    }

    @Test
    fun `target count selects one two and three target output fields`() {
        val modeled = combatRole(
            "scenario",
            "场景输出",
            physical = 99.0,
            physicalTargets = listOf(10.0, 30.0, 60.0),
            position = 1,
        )
        val fillers = listOf(
            combatRole("f1", "填充一", type = LabyrinthRoleDamageType.NONE, attribute = LabyrinthCharacterAttribute.FIRE),
            combatRole("f2", "填充二", type = LabyrinthRoleDamageType.NONE, attribute = LabyrinthCharacterAttribute.WATER),
            combatRole("f3", "填充三", type = LabyrinthRoleDamageType.NONE, attribute = LabyrinthCharacterAttribute.WIND),
            combatRole("f4", "填充四", type = LabyrinthRoleDamageType.NONE, attribute = LabyrinthCharacterAttribute.DARK),
        )

        val one = scorer.evaluate(listOf(modeled) + fillers, legacyFrontlineContext(4, targetCount = 1))
        val two = scorer.evaluate(listOf(modeled) + fillers, legacyFrontlineContext(4, targetCount = 2))
        val three = scorer.evaluate(listOf(modeled) + fillers, legacyFrontlineContext(4, targetCount = 3))

        assertEquals(10.0, one.physicalDamage, 0.000001)
        assertEquals(30.0, two.physicalDamage, 0.000001)
        assertEquals(60.0, three.physicalDamage, 0.000001)
    }

    @Test
    fun `cohesive physical team beats an otherwise equal 57 43 mixed team`() {
        val pure = listOf(
            combatRole("p1", "物一", physical = 20.0, position = 1, reliableVanguard = 100.0),
            combatRole("p2", "物二", physical = 20.0, physicalDefenseDown = 100.0),
            combatRole("p3", "物三", physical = 20.0, single = 100.0),
            combatRole("p4", "物四", physical = 20.0),
            combatRole("p5", "物五", physical = 20.0),
        )
        val mixed = listOf(
            combatRole("x1", "混物一", physical = 19.0, position = 1, reliableVanguard = 100.0),
            combatRole("x2", "混物二", physical = 19.0, physicalDefenseDown = 100.0),
            combatRole("x3", "混物三", physical = 19.0, single = 100.0),
            combatRole("x4", "混法一", magic = 21.5, magicDefenseDown = 100.0),
            combatRole("x5", "混法二", magic = 21.5),
        )

        val pureEvaluation = scorer.evaluate(pure, legacyFrontlineContext(4))
        val mixedEvaluation = scorer.evaluate(mixed, legacyFrontlineContext(4))

        assertEquals(0.57, mixedEvaluation.physicalRatio, 0.000001)
        assertTrue(pureEvaluation.systemComponents.getValue("体系一致性") > mixedEvaluation.systemComponents.getValue("体系一致性"))
        assertTrue(pureEvaluation.systemScore > mixedEvaluation.systemScore)
    }

    @Test
    fun `boss pure damage preference softly penalizes mixed output only when enabled`() {
        val mixed = listOf(
            combatRole("m1", "T", roleClass = "掩护者", position = 1, physical = 20.0, reliableVanguard = 100.0),
            combatRole("m2", "物一", physical = 20.0),
            combatRole("m3", "物二", physical = 20.0),
            combatRole("m4", "法一", magic = 20.0),
            combatRole("m5", "法二", magic = 20.0),
        )
        val normal = scorer.evaluate(mixed, legacyFrontlineContext(4))
        val preferred = scorer.evaluate(
            mixed,
            legacyFrontlineContext(4, preferSingleDamageSystem = true),
        )

        assertEquals(LabyrinthTeamDamageType.MIXED, preferred.damageType)
        assertEquals(scoringConfig.battleMixedDamagePenalty, normal.score - preferred.score, 0.000001)
        assertTrue(preferred.reasons.any { it.contains("单一物/法体系偏好") })
    }

    @Test
    fun `boss battle search chooses cohesive system before higher scoring mixed team`() {
        val roster = listOf(
            combatRole("tank", "T", roleClass = "掩护者", position = 1, physical = 60.0, reliableVanguard = 100.0),
            combatRole("p1", "物强一", physical = 100.0),
            combatRole("p2", "物强二", physical = 100.0),
            combatRole("p3", "物弱三", physical = 40.0),
            combatRole("p4", "物弱四", physical = 40.0),
            combatRole("m1", "法强一", magic = 100.0),
            combatRole("m2", "法强二", magic = 100.0),
        )

        val unrestricted = requireNotNull(
            optimizer.bestBattleFormation(roster, legacyFrontlineContext(defenseMarkStacks = 4)),
        )
        val cohesive = requireNotNull(
            optimizer.bestBattleFormation(
                roster,
                legacyFrontlineContext(defenseMarkStacks = 4, preferSingleDamageSystem = true),
            ),
        )

        assertEquals(LabyrinthTeamDamageType.MIXED, unrestricted.damageType)
        assertEquals(LabyrinthTeamDamageType.PHYSICAL, cohesive.damageType)
        assertTrue(cohesive.physicalRatio >= scoringConfig.bossCohesiveDamageRatioThreshold)
    }

    @Test
    fun `low defense team without frontline and sustain is heavily gated`() {
        val glass = listOf(
            combatRole("g1", "玻璃一", physical = 20.0, position = 1),
            combatRole("g2", "玻璃二", physical = 20.0, physicalDefenseDown = 100.0),
            combatRole("g3", "玻璃三", physical = 20.0, single = 100.0),
            combatRole("g4", "玻璃四", physical = 20.0),
            combatRole("g5", "玻璃五", physical = 20.0),
        )
        val fronted = glass.map { role ->
            if (role.characterId == "g1") role.copy(functions = role.functions.copy(reliableVanguard = 100.0)) else role
        }

        val unsafe = scorer.evaluate(glass, legacyFrontlineContext(0))
        val safer = scorer.evaluate(fronted, legacyFrontlineContext(0))

        assertEquals(0.58, unsafe.survivalMultiplier, 0.000001)
        assertEquals(0.88, safer.survivalMultiplier, 0.000001)
        assertTrue(safer.score - unsafe.score > 10.0)
    }

    @Test
    fun `only actual first position protector grants reliable frontline`() {
        val attacker = combatRole("front", "实际一号位", physical = 20.0, position = 1)
        val protector = combatRole(
            "tank",
            "后排掩护者",
            physical = 5.0,
            position = 10,
            roleClass = "掩护者",
            reliableVanguard = 100.0,
        )
        val rest = (1..3).map { combatRole("r$it", "其余$it", physical = 20.0, position = 20 + it) }

        val behind = scorer.evaluate(listOf(attacker, protector) + rest, legacyFrontlineContext(0))
        val inFront = scorer.evaluate(
            listOf(attacker.copy(position = 10), protector.copy(position = 1)) + rest,
            legacyFrontlineContext(0),
        )

        assertEquals("front", behind.vanguardCharacterId)
        assertFalse(behind.hasReliableFrontline)
        assertEquals("tank", inFront.vanguardCharacterId)
        assertTrue(inFront.hasReliableFrontline)
    }

    @Test
    fun `high value magic candidate may be recommended for second team without entering physical first team`() {
        val neutralAttributeConfig = scoringConfig.copy(
            attributeDamageBonus = (1..5).associateWith { 0.0 },
            teamSearchBeamWidth = 300,
        )
        val candidatePolicy = LabyrinthRoleChoicePolicy(
            LabyrinthTeamOptimizer(LabyrinthTeamScorer(neutralAttributeConfig)),
        )
        val physicalCore = listOf(
            combatRole("p1", "物核一", physical = 95.0, position = 1, userScore = 85.0, reliableVanguard = 100.0),
            combatRole("p2", "物核二", physical = 95.0, userScore = 85.0, physicalDefenseDown = 100.0),
            combatRole("p3", "物核三", physical = 95.0, userScore = 85.0, single = 100.0),
            combatRole("p4", "物核四", physical = 95.0, userScore = 85.0),
            combatRole("p5", "物核五", physical = 95.0, userScore = 85.0),
        )
        val magicReserve = listOf(
            combatRole("m1", "法储一", magic = 48.0, position = 6, userScore = 55.0, reliableVanguard = 60.0),
            combatRole("m2", "法储二", magic = 48.0, userScore = 55.0, magicDefenseDown = 100.0),
            combatRole("m3", "法储三", magic = 48.0, userScore = 55.0, single = 100.0),
            combatRole("m4", "法储四", magic = 48.0, userScore = 55.0),
            combatRole("m5", "法储五", magic = 48.0, userScore = 55.0),
        )
        val candidates = listOf(
            combatRole("mage", "高分法师", magic = 80.0, userScore = 100.0),
            combatRole("average", "普通候选", physical = 35.0, userScore = 45.0),
            combatRole("weak", "较弱候选", magic = 30.0, userScore = 35.0),
        )
        val acquired = physicalCore + magicReserve
        val profiles = (acquired + candidates).associateBy(LabyrinthRoleProfile::characterId)

        val decision = candidatePolicy.chooseOneRole(
            candidateIds = candidates.map(LabyrinthRoleProfile::characterId),
            acquiredCharacterIds = acquired.map(LabyrinthRoleProfile::characterId).toSet(),
            profiles = profiles,
            context = legacyFrontlineContext(4),
        ) as LabyrinthOneRoleDecision.Ready
        val ranking = decision.rankings.first()

        assertEquals("mage", decision.chosen.characterId)
        assertEquals(1, ranking.candidateLayer)
        assertFalse(ranking.firstTeam.members.any { it.characterId == "mage" })
        assertTrue(ranking.candidateTeam.members.any { it.characterId == "mage" })
        assertEquals(LabyrinthTeamDamageType.PHYSICAL, ranking.firstTeam.damageType)
    }

    @Test
    fun `team planner always sends one team on the first attempt`() {
        val roster = listOf(
            physical("p1", "一", position = 1, damage = 90.0, reliableVanguard = 100.0, roleClass = "掩护者"),
            physical("p2", "二", damage = 90.0, physicalDefenseDown = 90.0),
            physical("p3", "三", damage = 90.0, aoe = 90.0),
            physical("p4", "四", damage = 90.0, single = 90.0),
            physical("p5", "五", damage = 90.0),
            physical("p6", "六", damage = 60.0),
            physical("p7", "七", damage = 60.0),
            physical("p8", "八", damage = 60.0),
            physical("p9", "九", damage = 60.0),
            physical("p10", "十", damage = 60.0),
        )
        val bestScore = requireNotNull(
            optimizer.bestFormation(roster, legacyFrontlineContext(defenseMarkStacks = 4)),
        ).score
        val searcher = LabyrinthTeamPlanSearcher(
            optimizer,
            LabyrinthTeamPlanSearchConfig(
                oneTeamKillScore = bestScore - 0.01,
                mainTeamScore = 0.0,
                cleanupTeamScore = 0.0,
                mainPlusCleanupCombinedScore = 0.0,
                stableTeamScore = 0.0,
                fallbackTeamScore = 0.0,
                threeTeamCombinedScore = 0.0,
            ),
        )

        val plan = searcher.search(roster, legacyFrontlineContext(defenseMarkStacks = 4))

        assertEquals(LabyrinthTeamPlanKind.FIRST_ATTEMPT_ONE_TEAM, plan.kind)
        assertEquals(1, plan.teams.size)
    }

    @Test
    fun `boss multi team downgrades to two and maximizes disjoint pair total score`() {
        val roster = listOf(
            physical("t1", "坦一", position = 1, reliableVanguard = 100.0, roleClass = "掩护者", damage = 15.0),
            physical("t2", "坦二", position = 2, reliableVanguard = 90.0, roleClass = "掩护者", damage = 20.0),
            physical("d1", "输出一", position = 3, damage = 100.0, aoe = 100.0),
            physical("d2", "输出二", position = 4, damage = 98.0, physicalDefenseDown = 100.0),
            physical("d3", "输出三", position = 5, damage = 96.0, single = 100.0),
            physical("d4", "输出四", position = 6, damage = 92.0),
            physical("d5", "输出五", position = 7, damage = 88.0),
            physical("d6", "输出六", position = 8, damage = 84.0),
            physical("d7", "输出七", position = 9, damage = 80.0),
            physical("d8", "输出八", position = 10, damage = 76.0),
        )
        val context = legacyFrontlineContext(defenseMarkStacks = 4, targetCount = 3)
        val searcher = LabyrinthTeamPlanSearcher(
            optimizer,
            LabyrinthTeamPlanSearchConfig(
                oneTeamKillScore = 0.0,
                mainTeamScore = 0.0,
                cleanupTeamScore = 0.0,
                mainPlusCleanupCombinedScore = 0.0,
                stableTeamScore = 0.0,
                fallbackTeamScore = 0.0,
                threeTeamCombinedScore = 0.0,
                multiTeamBeamWidth = 300,
            ),
        )
        val ranked = optimizer.rankedBattleFormations(roster, context).take(300)
        var expected = Double.NEGATIVE_INFINITY
        ranked.forEachIndexed { index, first ->
            val firstIds = first.members.map(LabyrinthRoleProfile::characterId).toSet()
            ranked.drop(index + 1).forEach { second ->
                if (second.members.none { it.characterId in firstIds }) {
                    expected = maxOf(expected, first.score + second.score)
                }
            }
        }
        val plan = searcher.bossMultiTeamSearch(roster, context, requestedTeams = 3)
        assertEquals(2, plan.teams.size)
        val firstIds = plan.teams[0].members.map(LabyrinthRoleProfile::characterId).toSet()
        assertTrue(plan.teams[1].members.none { it.characterId in firstIds })
        assertEquals(expected, plan.teams.sumOf(LabyrinthTeamEvaluation::score), 0.000001)
        assertTrue(plan.reason.contains("安全容量2队"))
        assertTrue(plan.reason.contains("按2队总评分优化"))
    }

    @Test
    fun `relaxed vanguard gate lets boss fallback field a second team behind a near miss front`() {
        // Reported 2026-09-14: the single team wiped three times, the session switched to
        // multi-team, but the roster had exactly one strict-gate tank so the planner kept
        // returning one team. Under the fallback gate a 40+ frontliner may lead the second team.
        val roster = listOf(
            physical("t1", "唯一坦", position = 1, reliableVanguard = 100.0, roleClass = "掩护者", damage = 15.0),
            physical("t2", "半坦", position = 2, reliableVanguard = 60.0, roleClass = "攻击者", damage = 40.0),
            physical("d1", "输出一", position = 3, damage = 100.0),
            physical("d2", "输出二", position = 4, damage = 98.0),
            physical("d3", "输出三", position = 5, damage = 96.0),
            physical("d4", "输出四", position = 6, damage = 94.0),
            physical("d5", "输出五", position = 7, damage = 92.0),
            physical("d6", "输出六", position = 8, damage = 90.0),
            physical("d7", "输出七", position = 9, damage = 88.0),
            physical("d8", "输出八", position = 10, damage = 86.0),
        )
        val strict = legacyFrontlineContext(defenseMarkStacks = 4)
        val relaxed = strict.copy(relaxedVanguardGate = true)
        // 60 * 0.75 = 45: below the strict 55 gate, above the relaxed 40 gate.
        assertFalse(roster[1].isEligibleBattleVanguard(strict))
        assertTrue(roster[1].isEligibleBattleVanguard(relaxed))
        val searcher = LabyrinthTeamPlanSearcher(
            optimizer,
            LabyrinthTeamPlanSearchConfig(
                oneTeamKillScore = 0.0, mainTeamScore = 0.0, cleanupTeamScore = 0.0,
                mainPlusCleanupCombinedScore = 0.0, stableTeamScore = 0.0, fallbackTeamScore = 0.0,
                threeTeamCombinedScore = 0.0, multiTeamBeamWidth = 300,
            ),
        )

        val strictPlan = searcher.bossMultiTeamSearch(roster, strict, requestedTeams = 3)
        assertEquals(1, strictPlan.safeTeamCount)
        assertEquals(2, strictPlan.teams.size) // second slot filled by a supplement team

        val relaxedPlan = searcher.bossMultiTeamSearch(roster, relaxed, requestedTeams = 3, survivalRecovery = true)
        assertEquals(2, relaxedPlan.teams.size)
        assertEquals(2, relaxedPlan.safeTeamCount)
        assertEquals(setOf("t1", "t2"), relaxedPlan.teams.map { it.vanguardCharacterId }.toSet())
        assertTrue(relaxedPlan.reason, relaxedPlan.reason.contains("放宽一号位生存线至40.00"))
        val firstIds = relaxedPlan.teams[0].members.map(LabyrinthRoleProfile::characterId).toSet()
        assertTrue(relaxedPlan.teams[1].members.none { it.characterId in firstIds })
    }

    @Test
    fun `boss multi team downgrades to one when only one safe vanguard exists`() {
        val roster = listOf(
            physical("t1", "唯一坦", position = 1, reliableVanguard = 100.0, roleClass = "掩护者", damage = 15.0),
            physical("d1", "输出一", position = 2, damage = 100.0),
            physical("d2", "输出二", position = 3, damage = 98.0),
            physical("d3", "输出三", position = 4, damage = 96.0),
            physical("d4", "输出四", position = 5, damage = 94.0),
            physical("d5", "输出五", position = 6, damage = 92.0),
            physical("d6", "输出六", position = 7, damage = 90.0),
            physical("d7", "输出七", position = 8, damage = 88.0),
            physical("d8", "输出八", position = 9, damage = 86.0),
            physical("d9", "输出九", position = 10, damage = 84.0),
        )
        val context = legacyFrontlineContext(defenseMarkStacks = 4)
        val searcher = LabyrinthTeamPlanSearcher(
            optimizer,
            LabyrinthTeamPlanSearchConfig(
                oneTeamKillScore = 0.0,
                mainTeamScore = 0.0,
                cleanupTeamScore = 0.0,
                mainPlusCleanupCombinedScore = 0.0,
                stableTeamScore = 0.0,
                fallbackTeamScore = 0.0,
                threeTeamCombinedScore = 0.0,
                multiTeamBeamWidth = 300,
            ),
        )
        val strictOnly = searcher.bossMultiTeamSearch(roster, context, requestedTeams = 3, supplementTeams = false)
        assertEquals(1, strictOnly.teams.size)
        assertEquals("t1", strictOnly.teams.single().vanguardCharacterId)
        assertTrue(strictOnly.reason.contains("仅能安全组成1队"))
        val bestSingle = requireNotNull(optimizer.bestBattleFormation(roster, context))
        assertEquals(bestSingle.score, strictOnly.teams.single().score, 0.000001)

        // 2026-09-17: the open slot is filled with a vanguard-less damage team, not left empty.
        val plan = searcher.bossMultiTeamSearch(roster, context, requestedTeams = 3)
        assertEquals(2, plan.teams.size)
        assertEquals(1, plan.safeTeamCount)
        assertEquals("t1", plan.teams[0].vanguardCharacterId)
        assertEquals(bestSingle.score, plan.teams[0].score, 0.000001)
        val firstIds = plan.teams[0].members.map(LabyrinthRoleProfile::characterId).toSet()
        assertTrue(plan.teams[1].members.none { it.characterId in firstIds })
        assertEquals(5, plan.teams[1].members.size)
        assertTrue(plan.reason, plan.reason.contains("补刀队填充"))
    }

    @Test
    fun `boss follow-up slot with no qualified vanguard at all is still filled`() {
        val roster = listOf(
            physical("d1", "输出一", position = 2, damage = 100.0),
            physical("d2", "输出二", position = 3, damage = 98.0),
            physical("d3", "输出三", position = 4, damage = 96.0),
            physical("d4", "输出四", position = 5, damage = 94.0),
            physical("d5", "输出五", position = 6, damage = 92.0),
        )
        val context = legacyFrontlineContext(defenseMarkStacks = 4)
        val searcher = LabyrinthTeamPlanSearcher(
            optimizer,
            LabyrinthTeamPlanSearchConfig(
                oneTeamKillScore = 0.0, mainTeamScore = 0.0, cleanupTeamScore = 0.0,
                mainPlusCleanupCombinedScore = 0.0, stableTeamScore = 0.0, fallbackTeamScore = 0.0,
                threeTeamCombinedScore = 0.0, multiTeamBeamWidth = 300,
            ),
        )
        val plan = searcher.bossMultiTeamSearch(roster, context, requestedTeams = 1)
        assertEquals(LabyrinthTeamPlanKind.SUPPLEMENT_FILL, plan.kind)
        assertEquals(1, plan.teams.size)
        assertEquals(0, plan.safeTeamCount)
        assertEquals(5, plan.teams.single().members.size)

        val strict = searcher.bossMultiTeamSearch(roster, context, requestedTeams = 1, supplementTeams = false)
        assertEquals(LabyrinthTeamPlanKind.INSUFFICIENT_ROLES, strict.kind)
    }

    @Test
    fun `boss main team ignores setup dependent untargetable glass cannon`() {
        val protector = physical(
            "protector", "传统T", position = 1, reliableVanguard = 80.0, roleClass = "掩护者", damage = 15.0,
        )
        val special = physical(
            "special", "机制前排", position = 2, reliableVanguard = 0.0, roleClass = "增幅者", damage = 95.0,
        ).copy(
            vanguardProfile = LabyrinthVanguardProfile(
                physicalDurability = 30.0, magicDurability = 30.0, untargetable = 98.0,
            ),
        )
        assertFalse(special.isEligibleBattleVanguard(legacyFrontlineContext(defenseMarkStacks = 4)))
        val roster = listOf(
            protector, special,
            physical("d1", "输出一", position = 3, damage = 95.0),
            physical("d2", "输出二", position = 4, damage = 94.0),
            physical("d3", "输出三", position = 5, damage = 93.0),
            physical("d4", "输出四", position = 6, damage = 92.0),
        )
        val searcher = LabyrinthTeamPlanSearcher(
            optimizer,
            LabyrinthTeamPlanSearchConfig(
                oneTeamKillScore = 0.0, mainTeamScore = 0.0, cleanupTeamScore = 0.0,
                mainPlusCleanupCombinedScore = 0.0, stableTeamScore = 0.0, fallbackTeamScore = 0.0,
                threeTeamCombinedScore = 0.0,
            ),
        )

        val plan = searcher.initialSearch(
            roster,
            legacyFrontlineContext(defenseMarkStacks = 4, optimizeBossVanguardSynergy = true),
        )

        assertEquals("protector", plan.teams.single().vanguardCharacterId)
        assertTrue(plan.reason.contains("联合优化"))
    }

    @Test
    fun `boss main team chooses adequate buff tank over over-tanky pure tank when team score wins`() {
        val roster = listOf(
            combatRole(
                id = "buff-tank", name = "进攻T", roleClass = "掩护者", position = 1,
                userScore = 60.0, physical = 5.0, modelStatus = "database-v2",
                physicalTeamUplift = 0.50, magicTeamUplift = 0.0, reliableVanguard = 75.0,
            ),
            combatRole(
                id = "pure-tank", name = "纯肉T", roleClass = "掩护者", position = 2,
                userScore = 60.0, physical = 5.0, modelStatus = "database-v2",
                physicalTeamUplift = 0.0, magicTeamUplift = 0.0, reliableVanguard = 100.0,
            ),
            combatRole("d1", "输出一", userScore = 95.0, physical = 100.0, position = 3),
            combatRole("d2", "输出二", userScore = 94.0, physical = 98.0, position = 4),
            combatRole("d3", "输出三", userScore = 93.0, physical = 96.0, position = 5),
            combatRole("d4", "输出四", userScore = 92.0, physical = 94.0, position = 6),
        )
        val searcher = LabyrinthTeamPlanSearcher(
            optimizer,
            LabyrinthTeamPlanSearchConfig(
                oneTeamKillScore = 0.0, mainTeamScore = 0.0, cleanupTeamScore = 0.0,
                mainPlusCleanupCombinedScore = 0.0, stableTeamScore = 0.0, fallbackTeamScore = 0.0,
                threeTeamCombinedScore = 0.0,
            ),
        )
        val context = legacyFrontlineContext(
            defenseMarkStacks = 4,
            targetCount = 1,
            optimizeBossVanguardSynergy = true,
        )

        assertTrue(roster[0].isEligibleBattleVanguard(context))
        assertTrue(roster[1].labyrinthVanguardStrength(context) > roster[0].labyrinthVanguardStrength(context))
        val plan = searcher.initialSearch(roster, context)

        assertEquals("buff-tank", plan.teams.single().vanguardCharacterId)
        assertFalse(plan.teams.single().members.any { it.characterId == "pure-tank" })
        assertTrue(plan.teams.single().physicalTeamUplift > 0.40)
        assertTrue(plan.reason.contains("联合优化"))
        assertTrue(plan.teams.single().reasons.any { it.contains("物理队增益50%") })
    }

    @Test
    fun `team planner only opens multi team fallback after observed failure`() {
        val fallbackOptimizer = LabyrinthTeamOptimizer(
            LabyrinthTeamScorer(scoringConfig.copy(teamSearchBeamWidth = 300)),
        )
        val roster = listOf(
            physical("p1", "一", position = 1, damage = 90.0, reliableVanguard = 100.0, roleClass = "掩护者"),
            physical("p2", "二", damage = 90.0, physicalDefenseDown = 90.0),
            physical("p3", "三", damage = 90.0, aoe = 90.0),
            physical("p4", "四", damage = 90.0, single = 90.0),
            physical("p5", "五", damage = 90.0),
            physical("p6", "六", position = 2, damage = 60.0, reliableVanguard = 100.0, roleClass = "掩护者"),
            physical("p7", "七", damage = 60.0, physicalDefenseDown = 60.0),
            physical("p8", "八", damage = 60.0, aoe = 60.0),
            physical("p9", "九", damage = 60.0, single = 60.0),
            physical("p10", "十", damage = 60.0),
        )
        val bestScore = requireNotNull(
            fallbackOptimizer.bestFormation(roster, legacyFrontlineContext(defenseMarkStacks = 4)),
        ).score
        val searcher = LabyrinthTeamPlanSearcher(
            fallbackOptimizer,
            LabyrinthTeamPlanSearchConfig(
                oneTeamKillScore = bestScore + 100.0,
                mainTeamScore = 0.0,
                cleanupTeamScore = 0.0,
                mainPlusCleanupCombinedScore = 0.0,
                stableTeamScore = 0.0,
                fallbackTeamScore = 0.0,
                threeTeamCombinedScore = 0.0,
                multiTeamBeamWidth = 300,
            ),
        )

        val initial = searcher.search(roster, legacyFrontlineContext(defenseMarkStacks = 4))
        val fallback = searcher.fallbackSearch(roster, legacyFrontlineContext(defenseMarkStacks = 4))

        assertEquals(LabyrinthTeamPlanKind.FIRST_ATTEMPT_ONE_TEAM, initial.kind)
        assertEquals(1, initial.teams.size)
        assertEquals(LabyrinthTeamPlanKind.MAIN_PLUS_CLEANUP, fallback.kind)
        assertEquals(2, fallback.teams.size)
    }

    @Test
    fun `bounded search preserves exhaustive rankings scores and explanations`() {
        val roster = (0 until 8).map { index ->
            physical("r$index", "角色$index", position = 1 + index % 3,
                attribute = LabyrinthCharacterAttribute.entries[index % 5],
                quality = 40.0 + index * 4, damage = 100.0 - index * 7,
                reliableVanguard = if (index % 2 == 0) 90.0 else 0.0,
                aoe = index * 12.0, single = 100.0 - index * 10)
        }.reversed()
        for (width in listOf(1, 7, 120)) {
            val referenceScorer = LabyrinthTeamScorer(scoringConfig.copy(teamSearchBeamWidth = width))
            val search = LabyrinthTeamOptimizer(referenceScorer)
            for (targets in 1..3) {
                val context = legacyFrontlineContext(defenseMarkStacks = if (targets == 1) 0 else 4,
                    targetCount = targets)
                for (required in listOf(null, "r2")) {
                    val all = referenceCombinations(roster, 5)
                        .filter { required == null || it.any { role -> role.characterId == required } }
                        .map { referenceScorer.evaluate(it, context) }
                    val expected = all.sortedByDescending { it.score }.take(width)
                    assertEquals(expected, search.rankedFormations(roster + roster.first(), context, required))
                    assertEquals(expected.first(), search.bestFormation(roster, context, required))
                    assertEquals(all.sortedByDescending { it.systemScore }.first(),
                        search.bestSystemFormation(roster, context, required))
                }
            }
        }
    }

    @Test
    fun `bounded search preserves tied enumeration and handles small or missing rosters`() {
        val config = scoringConfig.copy(teamSearchBeamWidth = 3)
        val referenceScorer = LabyrinthTeamScorer(config)
        val search = LabyrinthTeamOptimizer(referenceScorer)
        val roster = (0 until 8).map { physical("r$it", "同值$it") }
        val context = legacyFrontlineContext(defenseMarkStacks = 4)
        assertEquals(referenceCombinations(roster, 5).take(3).map { referenceScorer.evaluate(it, context) },
            search.rankedFormations(roster, context))
        assertEquals(listOf(referenceScorer.evaluate(roster.take(3), context)),
            search.rankedFormations(roster.take(3), context))
        assertTrue(search.rankedFormations(emptyList(), context).isEmpty())
        assertTrue(search.rankedFormations(roster, context, "missing").isEmpty())
    }

    @Test
    fun `maximum roster search retains only beam results with complete explanations`() {
        val roster = (0 until 24).map { index ->
            physical("r$index", "角色$index", position = 1 + index % 4,
                quality = 30.0 + index * 2, damage = 100.0 - index,
                reliableVanguard = if (index % 4 == 0) 90.0 else 0.0)
        }
        // Leave substantial background occupancy while the 192 MiB worker searches all 42,504 teams.
        val background = ByteArray(72 * 1024 * 1024) { 1 }
        val result = optimizer.rankedFormations(roster, legacyFrontlineContext(defenseMarkStacks = 4))
        assertEquals(1, background.last().toInt())
        assertEquals(scoringConfig.teamSearchBeamWidth, result.size)
        assertTrue(result.zipWithNext().all { (a, b) -> a.score >= b.score })
        assertEquals(result.size, result.map { team -> team.members.map { it.characterId }.toSet() }.distinct().size)
        assertTrue(result.all { it.reasons.isNotEmpty() && it.components.isNotEmpty() && it.systemComponents.isNotEmpty() })
    }

    private fun <T> referenceCombinations(values: List<T>, size: Int): List<List<T>> {
        if (size == 0) return listOf(emptyList())
        return (0..values.size - size).flatMap { index ->
            referenceCombinations(values.drop(index + 1), size - 1).map { listOf(values[index]) + it }
        }
    }

    private fun physical(
        id: String,
        name: String,
        attribute: LabyrinthCharacterAttribute = LabyrinthCharacterAttribute.LIGHT,
        position: Int = 5,
        quality: Double = 60.0,
        damage: Double = 60.0,
        reliableVanguard: Double = 0.0,
        physicalDefenseDown: Double = 0.0,
        aoe: Double = 0.0,
        single: Double = 0.0,
        threeTarget: Double = 0.0,
        wideAoe: Double = 0.0,
        enemyPush: Double = 0.0,
        enemyPull: Double = 0.0,
        roleClass: String = "fighter",
    ) = LabyrinthRoleProfile(
        characterId = id,
        displayName = name,
        roleClass = roleClass,
        attribute = attribute,
        damageType = LabyrinthRoleDamageType.PHYSICAL,
        position = position,
        baseQuality = quality,
        physicalDamagePotential = damage,
        magicDamagePotential = 0.0,
        functions = LabyrinthRoleFunctions(
            reliableVanguard = reliableVanguard,
            physicalDefenseDown = physicalDefenseDown,
            aoeDamage = aoe,
            singleTargetDamage = single,
            threeTargetDamage = threeTarget,
            wideAoeCoverage = wideAoe,
            enemyPush = enemyPush,
            enemyPull = enemyPull,
        ),
    )

    private fun magic(
        id: String,
        name: String,
        damage: Double,
        support: LabyrinthRoleSupport,
    ) = LabyrinthRoleProfile(
        characterId = id,
        displayName = name,
        roleClass = "support",
        attribute = LabyrinthCharacterAttribute.DARK,
        damageType = LabyrinthRoleDamageType.MAGIC,
        position = 7,
        baseQuality = 60.0,
        physicalDamagePotential = 0.0,
        magicDamagePotential = damage,
        support = support,
    )

    private fun combatRole(
        id: String,
        name: String,
        type: LabyrinthRoleDamageType? = null,
        attribute: LabyrinthCharacterAttribute = LabyrinthCharacterAttribute.LIGHT,
        roleClass: String = "攻击者",
        position: Int = 5,
        userScore: Double? = null,
        physical: Double = 0.0,
        magic: Double = 0.0,
        physicalTargets: List<Double>? = null,
        magicTargets: List<Double>? = null,
        modelStatus: String = "unavailable",
        physicalTeamUplift: Double? = null,
        magicTeamUplift: Double? = null,
        reliableVanguard: Double = 0.0,
        selfSustain: Double = 0.0,
        healing: Double = 0.0,
        regeneration: Double = 0.0,
        physicalDefenseDown: Double = 0.0,
        magicDefenseDown: Double = 0.0,
        aoe: Double = 0.0,
        single: Double = 0.0,
    ): LabyrinthRoleProfile {
        val resolvedType = type ?: when {
            physical > 0.0 && magic > 0.0 -> LabyrinthRoleDamageType.MIXED
            magic > 0.0 -> LabyrinthRoleDamageType.MAGIC
            physical > 0.0 -> LabyrinthRoleDamageType.PHYSICAL
            else -> LabyrinthRoleDamageType.NONE
        }
        return LabyrinthRoleProfile(
            characterId = id,
            displayName = name,
            roleClass = roleClass,
            attribute = attribute,
            damageType = resolvedType,
            position = position,
            userScore = userScore,
            physicalDamagePotential = physical,
            magicDamagePotential = magic,
            physicalDamage1Target = physicalTargets?.get(0),
            physicalDamage2Target = physicalTargets?.get(1),
            physicalDamage3Target = physicalTargets?.get(2),
            magicDamage1Target = magicTargets?.get(0),
            magicDamage2Target = magicTargets?.get(1),
            magicDamage3Target = magicTargets?.get(2),
            physicalTeamUplift = physicalTeamUplift,
            magicTeamUplift = magicTeamUplift,
            modelStatus = modelStatus,
            functions = LabyrinthRoleFunctions(
                reliableVanguard = reliableVanguard,
                selfSustain = selfSustain,
                healing = healing,
                regeneration = regeneration,
                physicalDefenseDown = physicalDefenseDown,
                magicDefenseDown = magicDefenseDown,
                aoeDamage = aoe,
                singleTargetDamage = single,
            ),
        )
    }
}
