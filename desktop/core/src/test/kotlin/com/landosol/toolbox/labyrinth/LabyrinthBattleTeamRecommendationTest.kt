package com.landosol.toolbox.labyrinth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LabyrinthBattleTeamRecommendationTest {
    private val dotGuide = LabyrinthExEncounterStrategy(
        id = "best_effort_dot", identityName = "好朋友X", targetCount = 1,
        requirements = listOf(LabyrinthEncounterRequirement(
            anyOf = setOf(LabyrinthEncounterCapability.DOT), minimumCount = 2,
            label = "至少2名持续伤害角色",
        )),
    )

    @Test fun `missing or insufficient guide capabilities still produce initial and retry teams`() {
        for (missingScore in listOf(null, 0.0)) {
            val profiles = (1..7).map { i ->
                val role = role("r$i", "角色$i", i, 80.0, 70.0,
                    reliableVanguard = if (i == 1) 100.0 else 0.0)
                role.copy(functions = role.functions.copy(dot = if (i == 2) 80.0 else missingScore))
            }.associateBy { it.characterId }
            val planner = recommendationPlanner(profiles)
            val context = legacyFrontlineContext(defenseMarkStacks = 4, encounterStrategy = dotGuide)
            val first = planner.initialRecommendation(profiles.keys, context) as LabyrinthBattleTeamRecommendationResult.Ready
            assertEquals(5, first.recommendation.members.size)
            assertTrue(first.recommendation.members.any { it.characterId == "r2" })
            assertTrue(first.recommendation.reasons.any { it.contains("本队确认1/2") && it.contains("继续编组") })
            val failed = labyrinthBattleTeamSignature(first.recommendation.members.map { it.characterId })
            val retry = planner.retryRecommendation(profiles.keys, context, setOf(failed)) as LabyrinthBattleTeamRecommendationResult.Ready
            assertEquals(5, retry.recommendation.members.size)
            assertFalse(failed == labyrinthBattleTeamSignature(retry.recommendation.members.map { it.characterId }))
            assertTrue(retry.recommendation.reasons.any { it.contains("继续编组") })
            assertEquals(missingScore, profiles.getValue("r3").functions.dot)
        }
    }

    @Test fun `second EX failure can add healer despite unmet guide requirements`() {
        val profiles = (1..6).map { i ->
            role("r$i", "角色$i", i, 80.0, 70.0,
                reliableVanguard = if (i == 1) 100.0 else 0.0,
                healing = if (i == 6) 100.0 else 0.0)
        }.associateBy { it.characterId }
        val failed = labyrinthBattleTeamSignature((1..5).map { "r$it" })
        val result = recommendationPlanner(profiles).retryRecommendation(profiles.keys,
            legacyFrontlineContext(defenseMarkStacks = 4, encounterStrategy = dotGuide),
            setOf(failed), retryNumber = 2, lastFailedTeamSignature = failed) as LabyrinthBattleTeamRecommendationResult.Ready
        assertTrue(result.recommendation.members.any { it.characterId == "r6" })
        assertTrue(result.recommendation.reasons.any { it.contains("本队确认0/2") })
    }

    @Test fun `guide core is reserved even when generic attackers out-score it`() {
        // Two DOT dealers rated far below five generic attackers. A scoring bonus alone loses
        // to the player rating gap; the guide core must be reserved before the search runs.
        val profiles = buildList {
            add(role("tank", "T", 1, 90.0, 20.0, reliableVanguard = 100.0))
            (1..5).forEach { add(role("gen$it", "高分输出$it", 1 + it, 95.0, 95.0)) }
            (1..2).forEach { i ->
                val r = role("dot$i", "持续伤害$i", 10 + i, 55.0, 55.0)
                add(r.copy(functions = r.functions.copy(dot = 85.0)))
            }
        }.associateBy { it.characterId }
        val planner = recommendationPlanner(profiles)
        val context = legacyFrontlineContext(defenseMarkStacks = 4, encounterStrategy = dotGuide)
        val first = planner.initialRecommendation(profiles.keys, context) as LabyrinthBattleTeamRecommendationResult.Ready
        val ids = first.recommendation.members.map { it.characterId }.toSet()
        assertTrue("$ids", ids.containsAll(setOf("dot1", "dot2", "tank")))
        assertTrue(first.recommendation.reasons.any { it.contains("已按攻略锁定核心角色") })
        assertFalse(first.recommendation.reasons.any { it.contains("本队确认0/2") })

        // An effective-effect role with no DOT is reserved too, ahead of the requirement fill.
        val withEffective = planner.initialRecommendation(
            profiles.keys, context.copy(effectiveCharacterIds = setOf("gen5")),
        ) as LabyrinthBattleTeamRecommendationResult.Ready
        val ids2 = withEffective.recommendation.members.map { it.characterId }.toSet()
        assertTrue("$ids2", ids2.containsAll(setOf("gen5", "dot1", "dot2", "tank")))
    }

    @Test fun `guide core that cannot sit behind any eligible tank falls back to the normal search`() {
        val profiles = buildList {
            add(role("tank", "T", 5, 90.0, 20.0, reliableVanguard = 100.0))
            (1..5).forEach { add(role("gen$it", "输出$it", 5 + it, 80.0, 80.0)) }
            // DOT dealer positioned in front of the only tank: fielding her breaks the front line.
            val front = role("dotFront", "前排持续", 1, 70.0, 60.0)
            add(front.copy(functions = front.functions.copy(dot = 90.0)))
        }.associateBy { it.characterId }
        val first = recommendationPlanner(profiles).initialRecommendation(
            profiles.keys, legacyFrontlineContext(defenseMarkStacks = 4, encounterStrategy = dotGuide),
        ) as LabyrinthBattleTeamRecommendationResult.Ready
        assertEquals("tank", first.recommendation.vanguard.characterId)
        assertFalse(first.recommendation.members.any { it.characterId == "dotFront" })
        assertFalse(first.recommendation.reasons.any { it.contains("已按攻略锁定核心角色") })
    }

    @Test fun `guide capability coverage remains preferred when enough characters exist`() {
        val profiles = (1..7).map { i ->
            val role = role("r$i", "角色$i", i, 80.0, 70.0,
                reliableVanguard = if (i == 1) 100.0 else 0.0)
            role.copy(functions = role.functions.copy(dot = if (i >= 6) 80.0 else 0.0))
        }.associateBy { it.characterId }
        val result = recommendationPlanner(profiles).initialRecommendation(profiles.keys,
            legacyFrontlineContext(defenseMarkStacks = 4, encounterStrategy = dotGuide)) as LabyrinthBattleTeamRecommendationResult.Ready
        assertTrue(result.recommendation.members.map { it.characterId }.containsAll(listOf("r6", "r7")))
        assertFalse(result.recommendation.reasons.any { it.contains("继续编组") })
    }

    private val scoringConfig = LabyrinthTeamScoringConfig(
        attributeDamageBonus = mapOf(
            1 to 0.0,
            2 to 0.08,
            3 to 0.16,
            4 to 0.25,
            5 to 0.75,
        ),
    )

    @Test fun `partial damage data keeps front T constraint without completing unknown facts`() {
        val tank = role("T", "坦克", 10, 50.0, 20.0, reliableVanguard = 100.0)
            .copy(attribute = null, damageType = null, physicalDamagePotential = null, magicDamagePotential = null)
        val rear = role("rear", "输出", 20, 90.0, 90.0).copy(attribute = null)
        val profiles = listOf(tank, rear).associateBy { it.characterId }
        val decision = recommendationPlanner(profiles).initialRecommendation(profiles.keys,
            legacyFrontlineContext(defenseMarkStacks = 4)) as LabyrinthBattleTeamRecommendationResult.Ready
        assertEquals("T", decision.recommendation.vanguard.characterId)
        assertEquals(2, decision.recommendation.members.size)
        assertEquals(1, decision.recommendation.safeBossTeamCount)
        assertEquals(null, tank.physicalDamagePotential)
        val unknownPosition = profiles + ("rear" to rear.copy(position = null))
        // Small opening rosters use the existing supplement fallback when safe positioning
        // cannot be established; they must not be labelled as a safe tank-led team.
        val fallback = recommendationPlanner(unknownPosition).initialRecommendation(unknownPosition.keys,
            legacyFrontlineContext(defenseMarkStacks = 4)) as LabyrinthBattleTeamRecommendationResult.Ready
        assertEquals(profiles.keys, fallback.recommendation.members.map { it.characterId }.toSet())
        assertEquals(0, fallback.recommendation.safeBossTeamCount)
        assertTrue(fallback.recommendation.reasons.any { it.contains("资料不完整") })
        assertEquals(null, unknownPosition.getValue("rear").position)
    }

    @Test fun `one missing profile does not block a known five member T team`() {
        val profiles = (1..5).map { index -> role("r$index", "角色$index", index, 80.0, 70.0,
            reliableVanguard = if (index == 1) 100.0 else 0.0) }.associateBy { it.characterId }
        val decision = recommendationPlanner(profiles).initialRecommendation(profiles.keys + "missing",
            legacyFrontlineContext(defenseMarkStacks = 4)) as LabyrinthBattleTeamRecommendationResult.Ready
        assertEquals(profiles.keys, decision.recommendation.members.map { it.characterId }.toSet())
        assertTrue(decision.recommendation.reasons.any { it.contains("missing") })
    }

    @Test
    fun `initial recommendation exposes first team diagnostics for automatic selection`() {
        val profiles = listOf(
            role("tank", "前排", position = 1, userScore = 90.0, damage = 30.0, reliableVanguard = 100.0),
            role("core1", "主力一", position = 2, userScore = 95.0, damage = 95.0),
            role("core2", "主力二", position = 3, userScore = 94.0, damage = 93.0),
            role("core3", "主力三", position = 4, userScore = 93.0, damage = 91.0),
            role("core4", "主力四", position = 5, userScore = 92.0, damage = 89.0),
            role("weak", "替补", position = 6, userScore = 20.0, damage = 20.0),
        ).associateBy(LabyrinthRoleProfile::characterId)
        val planner = recommendationPlanner(profiles)

        val result = planner.initialRecommendation(
            acquiredCharacterIds = profiles.keys,
            context = legacyFrontlineContext(defenseMarkStacks = 2, targetCount = 2),
        ) as LabyrinthBattleTeamRecommendationResult.Ready
        val recommendation = result.recommendation

        assertEquals(5, recommendation.members.size)
        assertFalse(recommendation.members.any { it.characterId == "weak" })
        assertEquals(LabyrinthTeamDamageType.PHYSICAL, recommendation.damageType)
        assertTrue(recommendation.score > 0.0)
        assertEquals("tank", recommendation.vanguard.characterId)
        assertEquals("tank", recommendation.survivalAnchor?.characterId)
        assertEquals(2, recommendation.defenseMarkStacks)
        assertEquals(2, recommendation.targetCount)
        assertTrue(recommendation.reasons.isNotEmpty())

        val lines = recommendation.overlayLines()
        assertEquals(3, lines.size)
        assertTrue(lines[0].contains("推荐第一队："))
        assertTrue(lines[0].contains("core1 主力一"))
        assertTrue(lines[1].contains("体系：物理"))
        assertTrue(lines[1].contains("评分："))
        assertTrue(lines[1].contains("一号位：tank 前排"))
        assertTrue(lines[1].contains("生存锚点：tank 前排"))
        assertTrue(lines[2].contains("推荐原因："))

        val stateMessage = labyrinthBattleTeamSelectionMessage(
            combatContext = LabyrinthCombatContext(LabyrinthCombatKind.NORMAL),
            recommendation = recommendation,
            unavailableReason = null,
        )
        assertTrue(stateMessage.contains("推荐第一队"))
        assertTrue(stateMessage.contains("准备普通战编组"))
        assertFalse(stateMessage.contains("不执行编组点击"))
    }

    @Test
    fun `incomplete scoring profiles participate without invented facts`() {
        val strict = (1..5).map { index ->
            role(
                "strict$index",
                "严格$index",
                position = index,
                userScore = 70.0 + index,
                damage = 60.0 + index,
                reliableVanguard = if (index == 1) 100.0 else 0.0,
            )
        }
        val incomplete = role("unknown", "资料不完整", position = 6, userScore = 100.0, damage = 100.0)
            .copy(damageType = null, attribute = null)
        val profiles = (strict + incomplete).associateBy(LabyrinthRoleProfile::characterId)

        val result = recommendationPlanner(profiles).initialRecommendation(
            acquiredCharacterIds = profiles.keys,
            context = legacyFrontlineContext(defenseMarkStacks = 4),
        ) as LabyrinthBattleTeamRecommendationResult.Ready

        assertTrue(result.recommendation.excludedIncompleteCharacterIds.isEmpty())
        assertTrue(result.recommendation.members.any { it.characterId == "unknown" })
        assertTrue(result.recommendation.reasons.any { it.contains("按已有信息") })
        assertEquals(null, profiles.getValue("unknown").attribute)
        assertEquals(null, profiles.getValue("unknown").damageType)
    }

    @Test
    fun `recommendation uses every reliable role when opening roster has only three`() {
        val profiles = (1..3).map { index ->
            role(
                "role$index",
                "角色$index",
                position = index,
                userScore = 80.0,
                damage = 80.0,
                reliableVanguard = if (index == 1) 100.0 else 0.0,
            )
        }.associateBy(LabyrinthRoleProfile::characterId)

        val result = recommendationPlanner(profiles).initialRecommendation(
            acquiredCharacterIds = profiles.keys,
            context = legacyFrontlineContext(defenseMarkStacks = 0),
        ) as LabyrinthBattleTeamRecommendationResult.Ready

        assertEquals(3, result.recommendation.members.size)
        assertEquals(profiles.keys, result.recommendation.members.map { it.characterId }.toSet())
        assertEquals("role1", result.recommendation.vanguard.characterId)
    }

    @Test
    fun `automatic battle excludes a stronger non tank that would stand in front of the tank`() {
        val profiles = listOf(
            role("frontDps", "前置高分输出", position = 1, userScore = 100.0, damage = 100.0),
            role("tank", "低分T", position = 2, userScore = 10.0, damage = 5.0, reliableVanguard = 100.0),
            role("dps3", "输出3", position = 3, userScore = 95.0, damage = 95.0),
            role("dps4", "输出4", position = 4, userScore = 94.0, damage = 94.0),
            role("dps5", "输出5", position = 5, userScore = 93.0, damage = 93.0),
            role("dps6", "输出6", position = 6, userScore = 92.0, damage = 92.0),
        ).associateBy(LabyrinthRoleProfile::characterId)

        val result = recommendationPlanner(profiles).initialRecommendation(
            acquiredCharacterIds = profiles.keys,
            context = legacyFrontlineContext(defenseMarkStacks = 4),
        ) as LabyrinthBattleTeamRecommendationResult.Ready

        assertEquals("tank", result.recommendation.vanguard.characterId)
        assertTrue(result.recommendation.members.any { it.characterId == "tank" })
        assertFalse(result.recommendation.members.any { it.characterId == "frontDps" })
        assertTrue(result.recommendation.reasons.any { it.contains("一号位生存资格") })
    }

    @Test
    fun `a roster without any tank still fields a team, marked as leading without one`() {
        // 2026-09-19, by request: blocking 编组 left the run with nothing to send while the fight
        // still had to be played. The team is composed anyway; what must not happen is a damage
        // dealer being quietly promoted into a "safe" lead.
        val profiles = (1..6).map { index ->
            role("dps$index", "输出$index", position = index, userScore = 90.0, damage = 90.0)
        }.associateBy(LabyrinthRoleProfile::characterId)

        val result = recommendationPlanner(profiles).initialRecommendation(
            acquiredCharacterIds = profiles.keys,
            context = legacyFrontlineContext(defenseMarkStacks = 4),
        ) as LabyrinthBattleTeamRecommendationResult.Ready

        assertEquals(5, result.recommendation.members.size)
        // Zero safe teams is how "this lead is not a qualified vanguard" is reported.
        assertEquals(0, result.recommendation.safeBossTeamCount)
        assertTrue(result.recommendation.reasons.any { it.contains("无T阵容") })
    }

    @Test
    fun `setup dependent untargetable alone does not make a glass cannon vanguard`() {
        val grace = role("grace", "格蕾斯", position = 2, userScore = 95.0, damage = 95.0).copy(
            roleClass = "增幅者",
            vanguardProfile = LabyrinthVanguardProfile(
                physicalDurability = 25.0,
                magicDurability = 20.0,
                untargetable = 98.0,
            ),
        )
        val profiles = listOf(
            role("frontDps", "更靠前输出", position = 1, userScore = 100.0, damage = 100.0),
            grace,
            role("dps3", "输出3", position = 3, userScore = 94.0, damage = 94.0),
            role("dps4", "输出4", position = 4, userScore = 93.0, damage = 93.0),
            role("dps5", "输出5", position = 5, userScore = 92.0, damage = 92.0),
            role("dps6", "输出6", position = 6, userScore = 91.0, damage = 91.0),
        ).associateBy(LabyrinthRoleProfile::characterId)

        val result = recommendationPlanner(profiles).initialRecommendation(
            acquiredCharacterIds = profiles.keys,
            context = legacyFrontlineContext(defenseMarkStacks = 4),
        ) as LabyrinthBattleTeamRecommendationResult.Ready

        // The team is fielded, but a setup-dependent untargetable is never counted as a safe lead.
        assertEquals(0, result.recommendation.safeBossTeamCount)
        assertTrue(result.recommendation.reasons.any { it.contains("无T阵容") })
    }

    @Test
    fun `temporary invulnerability alone does not turn glass cannon into universal vanguard`() {
        val glass = role("glass", "短暂无敌输出", position = 1, userScore = 100.0, damage = 100.0).copy(
            roleClass = "攻击者",
            vanguardProfile = LabyrinthVanguardProfile(
                physicalDurability = 20.0,
                magicDurability = 20.0,
                invulnerability = 82.0,
            ),
        )
        val profiles = listOf(
            glass,
            role("tank", "真正一号位", position = 2, userScore = 50.0, damage = 20.0, reliableVanguard = 100.0),
            role("dps3", "输出3", position = 3, userScore = 95.0, damage = 95.0),
            role("dps4", "输出4", position = 4, userScore = 94.0, damage = 94.0),
            role("dps5", "输出5", position = 5, userScore = 93.0, damage = 93.0),
            role("dps6", "输出6", position = 6, userScore = 92.0, damage = 92.0),
        ).associateBy(LabyrinthRoleProfile::characterId)

        val result = recommendationPlanner(profiles).initialRecommendation(
            acquiredCharacterIds = profiles.keys,
            context = legacyFrontlineContext(defenseMarkStacks = 4),
        ) as LabyrinthBattleTeamRecommendationResult.Ready

        assertEquals("tank", result.recommendation.vanguard.characterId)
        assertFalse(result.recommendation.members.any { it.characterId == "glass" })
    }

    @Test
    fun `frontline mechanism requiring ally in front cannot qualify as vanguard`() {
        val blocked = role("blocked", "前方有人才减伤", position = 1, userScore = 100.0, damage = 100.0).copy(
            roleClass = "破防者",
            vanguardProfile = LabyrinthVanguardProfile(
                physicalDurability = 100.0,
                magicDurability = 100.0,
                requiresAllyInFront = true,
            ),
        )
        val profiles = listOf(
            blocked,
            role("tank", "真正一号位", position = 2, userScore = 50.0, damage = 20.0, reliableVanguard = 100.0),
            role("dps3", "输出3", position = 3, userScore = 95.0, damage = 95.0),
            role("dps4", "输出4", position = 4, userScore = 94.0, damage = 94.0),
            role("dps5", "输出5", position = 5, userScore = 93.0, damage = 93.0),
            role("dps6", "输出6", position = 6, userScore = 92.0, damage = 92.0),
        ).associateBy(LabyrinthRoleProfile::characterId)

        val result = recommendationPlanner(profiles).initialRecommendation(
            acquiredCharacterIds = profiles.keys,
            context = legacyFrontlineContext(defenseMarkStacks = 4),
        ) as LabyrinthBattleTeamRecommendationResult.Ready

        assertEquals("tank", result.recommendation.vanguard.characterId)
        assertFalse(result.recommendation.members.any { it.characterId == "blocked" })
    }

    @Test
    fun `bounded battle search preserves low score tank and enough backline teammates`() {
        val constrainedConfig = scoringConfig.copy(exhaustiveRosterLimit = 5, teamSearchBeamWidth = 20)
        val optimizer = LabyrinthTeamOptimizer(LabyrinthTeamScorer(constrainedConfig))
        val roster = listOf(
            role("frontDps", "前置输出", position = 1, userScore = 100.0, damage = 100.0),
            role("tank", "低分T", position = 2, userScore = 1.0, damage = 1.0, reliableVanguard = 100.0),
            role("dps3", "输出3", position = 3, userScore = 99.0, damage = 99.0),
            role("dps4", "输出4", position = 4, userScore = 98.0, damage = 98.0),
            role("dps5", "输出5", position = 5, userScore = 97.0, damage = 97.0),
            role("dps6", "输出6", position = 6, userScore = 96.0, damage = 96.0),
            role("dps7", "输出7", position = 7, userScore = 95.0, damage = 95.0),
        )

        val best = requireNotNull(
            optimizer.bestBattleFormation(roster, legacyFrontlineContext(defenseMarkStacks = 4)),
        )

        assertEquals("tank", best.vanguardCharacterId)
        assertTrue(best.members.any { it.characterId == "tank" })
        assertFalse(best.members.any { it.characterId == "frontDps" })
    }

    @Test
    fun `retry recommendation never repeats an exact failed team`() {
        val profiles = listOf(
            role("tank", "T", 1, 100.0, 20.0, reliableVanguard = 100.0),
            role("a", "A", 2, 100.0, 100.0),
            role("b", "B", 3, 99.0, 99.0),
            role("c", "C", 4, 98.0, 98.0),
            role("d", "D", 5, 97.0, 97.0),
            role("e", "E", 6, 20.0, 20.0),
        ).associateBy(LabyrinthRoleProfile::characterId)
        val planner = recommendationPlanner(profiles)
        val context = legacyFrontlineContext(defenseMarkStacks = 4)
        val initial = planner.initialRecommendation(profiles.keys, context)
            as LabyrinthBattleTeamRecommendationResult.Ready
        val failedSignature = labyrinthBattleTeamSignature(
            initial.recommendation.members.map(LabyrinthRecommendedTeamMember::characterId),
        )

        val retry = planner.retryRecommendation(
            acquiredCharacterIds = profiles.keys,
            context = context,
            failedTeamSignatures = setOf(failedSignature),
        ) as LabyrinthBattleTeamRecommendationResult.Ready
        val retrySignature = labyrinthBattleTeamSignature(
            retry.recommendation.members.map(LabyrinthRecommendedTeamMember::characterId),
        )

        assertFalse(retrySignature == failedSignature)
        assertTrue(retry.recommendation.members.any { it.characterId == "e" })
        assertEquals("tank", retry.recommendation.vanguard.characterId)
    }

    @Test
    fun `second ex failure keeps tank and replaces lowest score non tank with healer`() {
        val profiles = listOf(
            role("tank", "T", 1, 95.0, 20.0, reliableVanguard = 100.0),
            role("a", "A", 2, 96.0, 96.0),
            role("b", "B", 3, 90.0, 90.0),
            role("low", "低分输出", 4, 35.0, 35.0),
            role("d", "D", 5, 88.0, 88.0),
            role("heal", "奶", 6, 80.0, 10.0, healing = 95.0),
        ).associateBy(LabyrinthRoleProfile::characterId)
        val planner = recommendationPlanner(profiles)
        val context = legacyFrontlineContext(
            defenseMarkStacks = 1,
            encounterStrategy = LabyrinthExEncounterStrategy(
                id = "test-ex",
                identityName = "测试EX",
                targetCount = 1,
            ),
        )
        val failed = labyrinthBattleTeamSignature(listOf("tank", "a", "b", "low", "d"))

        val retry = planner.retryRecommendation(
            acquiredCharacterIds = profiles.keys,
            context = context,
            failedTeamSignatures = setOf(failed),
            retryNumber = 2,
            lastFailedTeamSignature = failed,
        ) as LabyrinthBattleTeamRecommendationResult.Ready
        val ids = retry.recommendation.members.map(LabyrinthRecommendedTeamMember::characterId).toSet()

        assertTrue("tank must be kept: $ids", "tank" in ids)
        assertFalse("lowest non-tank must be removed: $ids", "low" in ids)
        assertTrue("healer must be added: $ids", "heal" in ids)
        assertTrue(retry.recommendation.reasons.any { it.contains("EX第二次失败生存兜底") })
    }

    @Test
    fun `second ex failure never removes an effective effect member`() {
        val profiles = listOf(
            role("tank", "T", 1, 95.0, 20.0, reliableVanguard = 100.0),
            role("a", "A", 2, 96.0, 96.0),
            role("b", "B", 3, 90.0, 90.0),
            role("effectiveLow", "低分有效角色", 4, 10.0, 10.0),
            role("replace", "次低分普通角色", 5, 35.0, 35.0),
            role("heal", "奶", 6, 80.0, 10.0, healing = 95.0),
        ).associateBy(LabyrinthRoleProfile::characterId)
        val planner = recommendationPlanner(profiles)
        val context = legacyFrontlineContext(
            defenseMarkStacks = 1,
            encounterStrategy = LabyrinthExEncounterStrategy(
                id = "test-ex-effective",
                identityName = "测试EX有效角色保护",
                targetCount = 1,
            ),
            effectiveCharacterIds = setOf("effectiveLow"),
        )
        val failed = labyrinthBattleTeamSignature(
            listOf("tank", "a", "b", "effectiveLow", "replace"),
        )

        val retry = planner.retryRecommendation(
            acquiredCharacterIds = profiles.keys,
            context = context,
            failedTeamSignatures = setOf(failed),
            retryNumber = 2,
            lastFailedTeamSignature = failed,
        ) as LabyrinthBattleTeamRecommendationResult.Ready
        val ids = retry.recommendation.members.map(LabyrinthRecommendedTeamMember::characterId).toSet()

        assertTrue("effective role must be locked: $ids", "effectiveLow" in ids)
        assertFalse("lowest removable non-effective role must leave: $ids", "replace" in ids)
        assertTrue("healer must be added: $ids", "heal" in ids)
        assertTrue(retry.recommendation.reasons.any { it.contains("有效效果角色全部保留") })
    }

    @Test
    fun `second ex failure swaps out the lowest effective role when every non tank is effective`() {
        val profiles = listOf(
            role("tank", "T", 1, 95.0, 20.0, reliableVanguard = 100.0),
            role("a", "A", 2, 96.0, 96.0),
            role("b", "B", 3, 90.0, 90.0),
            role("c", "C", 4, 80.0, 80.0),
            role("d", "D", 5, 70.0, 70.0),
            role("heal", "奶", 6, 80.0, 10.0, healing = 95.0),
        ).associateBy(LabyrinthRoleProfile::characterId)
        val planner = recommendationPlanner(profiles)
        val context = legacyFrontlineContext(
            defenseMarkStacks = 1,
            encounterStrategy = LabyrinthExEncounterStrategy(
                id = "test-ex-all-effective",
                identityName = "测试EX全有效角色保护",
                targetCount = 1,
            ),
            effectiveCharacterIds = setOf("a", "b", "c", "d"),
        )
        val failed = labyrinthBattleTeamSignature(listOf("tank", "a", "b", "c", "d"))

        val retry = planner.retryRecommendation(
            acquiredCharacterIds = profiles.keys,
            context = context,
            failedTeamSignatures = setOf(failed),
            retryNumber = 2,
            lastFailedTeamSignature = failed,
        ) as LabyrinthBattleTeamRecommendationResult.Ready
        val ids = retry.recommendation.members.map(LabyrinthRecommendedTeamMember::characterId).toSet()

        // 2026-09-17: refusing here stalled the run. The lowest-scored effective role yields.
        assertTrue("tank must be kept: $ids", "tank" in ids)
        assertFalse("lowest effective role must leave: $ids", "d" in ids)
        assertTrue("healer must be added: $ids", "heal" in ids)
        assertTrue(retry.recommendation.reasons.any { it.contains("有效效果角色不得不换出") })
    }

    @Test
    fun `second ex failure without any healer falls through to the ordinary fallback`() {
        val profiles = listOf(
            role("tank", "T", 1, 95.0, 20.0, reliableVanguard = 100.0),
            role("a", "A", 2, 96.0, 96.0),
            role("b", "B", 3, 90.0, 90.0),
            role("c", "C", 4, 80.0, 80.0),
            role("d", "D", 5, 70.0, 70.0),
            role("e", "E", 6, 60.0, 60.0),
        ).associateBy(LabyrinthRoleProfile::characterId)
        val planner = recommendationPlanner(profiles)
        val context = legacyFrontlineContext(
            defenseMarkStacks = 1,
            encounterStrategy = LabyrinthExEncounterStrategy(id = "test-ex-no-healer", identityName = "无奶EX", targetCount = 1),
            effectiveCharacterIds = setOf("a", "b", "c", "d"),
        )
        val failed = labyrinthBattleTeamSignature(listOf("tank", "a", "b", "c", "d"))

        val retry = planner.retryRecommendation(
            acquiredCharacterIds = profiles.keys,
            context = context,
            failedTeamSignatures = setOf(failed),
            retryNumber = 2,
            lastFailedTeamSignature = failed,
        )

        assertTrue("retry=$retry", retry is LabyrinthBattleTeamRecommendationResult.Ready)
        val ids = (retry as LabyrinthBattleTeamRecommendationResult.Ready).recommendation.members.map { it.characterId }
        assertFalse(labyrinthBattleTeamSignature(ids) == failed)
    }

    @Test
    fun `retry limits are two for normal ex and one for boss`() {
        assertEquals(2, labyrinthBattleRetryLimit(LabyrinthCombatKind.NORMAL))
        assertEquals(2, labyrinthBattleRetryLimit(LabyrinthCombatKind.EX))
        assertEquals(1, labyrinthBattleRetryLimit(LabyrinthCombatKind.BOSS))
    }

    @Test
    fun `three failure reroll gives every combat three total attempts`() {
        assertEquals(2, labyrinthEffectiveBattleRetryLimit(LabyrinthCombatKind.NORMAL, true))
        assertEquals(2, labyrinthEffectiveBattleRetryLimit(LabyrinthCombatKind.EX, true))
        assertEquals(2, labyrinthEffectiveBattleRetryLimit(LabyrinthCombatKind.BOSS, true))
        assertFalse(labyrinthShouldRerollAfterBattleFailure(true, 1))
        assertTrue(labyrinthShouldRerollAfterBattleFailure(true, 2))
        assertFalse(labyrinthShouldRerollAfterBattleFailure(false, 2))
    }

    @Test
    fun `batch owned run ends at the retry limit regardless of the reroll toggle`() {
        // 2026-09-17 live: 刷开局 off, EX limit 2 reached → must still be FAILED_MAX_RETRY for a batch.
        assertTrue(labyrinthBatchOwnedRunEndsAtRetryLimit(batchOwnsRun = true, battleRetryCount = 2, retryLimit = 2))
        assertFalse(labyrinthBatchOwnedRunEndsAtRetryLimit(batchOwnsRun = true, battleRetryCount = 1, retryLimit = 2))
        // Interactive runs keep the old "stay on the failure page" behaviour.
        assertFalse(labyrinthBatchOwnedRunEndsAtRetryLimit(batchOwnsRun = false, battleRetryCount = 2, retryLimit = 2))
        assertFalse(labyrinthShouldRerollAfterBattleFailure(false, 2))
    }

    @Test
    fun `single boss fallback retry count is configurable and switches before reroll`() {
        assertEquals(
            4,
            labyrinthEffectiveBattleRetryLimit(
                LabyrinthCombatKind.BOSS,
                rerollAfterThreeFailures = false,
                singleBossFallbackRetryCount = 4,
            ),
        )
        assertFalse(
            labyrinthShouldSwitchSingleBossToMulti(
                enabled = true,
                kind = LabyrinthCombatKind.BOSS,
                mode = LabyrinthBossTeamMode.SINGLE_TEAM,
                battleRetryCount = 3,
                retryCountBeforeMulti = 4,
            ),
        )
        assertTrue(
            labyrinthShouldSwitchSingleBossToMulti(
                enabled = true,
                kind = LabyrinthCombatKind.BOSS,
                mode = LabyrinthBossTeamMode.SINGLE_TEAM,
                battleRetryCount = 4,
                retryCountBeforeMulti = 4,
            ),
        )
        assertTrue(
            labyrinthShouldSwitchSingleBossToMulti(
                enabled = true,
                kind = LabyrinthCombatKind.BOSS,
                mode = LabyrinthBossTeamMode.SINGLE_TEAM,
                battleRetryCount = 0,
                retryCountBeforeMulti = 0,
            ),
        )
        assertFalse(
            labyrinthShouldSwitchSingleBossToMulti(
                enabled = true,
                kind = LabyrinthCombatKind.BOSS,
                mode = LabyrinthBossTeamMode.MULTI_TEAM,
                battleRetryCount = 4,
                retryCountBeforeMulti = 4,
            ),
        )
        assertFalse(
            labyrinthShouldSwitchSingleBossToMulti(
                enabled = false,
                kind = LabyrinthCombatKind.BOSS,
                mode = LabyrinthBossTeamMode.SINGLE_TEAM,
                battleRetryCount = 4,
                retryCountBeforeMulti = 4,
            ),
        )
    }

    @Test fun `a tankless retry is fielded instead of parking the run on the failure page`() {
        // The first attempt's fallback would be pointless if the retry still refused: the run
        // would stop on the failure page with nothing to submit.
        val residual = (1..5).map { i -> role("d$i", "输出$i", i + 1, 70.0, 80.0) }
            .associateBy { it.characterId }
        val context = legacyFrontlineContext(defenseMarkStacks = 4)

        val retry = recommendationPlanner(residual).retryRecommendation(
            acquiredCharacterIds = residual.keys,
            context = context,
            failedTeamSignatures = emptySet(),
            retryNumber = 1,
        ) as LabyrinthBattleTeamRecommendationResult.Ready
        assertEquals(5, retry.recommendation.members.size)
        assertEquals(0, retry.recommendation.safeBossTeamCount)

        // A formation that already failed is still never resubmitted.
        val signature = labyrinthBattleTeamSignature(retry.recommendation.members.map { it.characterId })
        val again = recommendationPlanner(residual).retryRecommendation(
            acquiredCharacterIds = residual.keys,
            context = context,
            failedTeamSignatures = setOf(signature),
            retryNumber = 2,
        )
        assertTrue(again is LabyrinthBattleTeamRecommendationResult.Unavailable)
    }

    @Test fun `retry replaces a failed top ranked team even with a one team search beam`() {
        val context = legacyFrontlineContext(defenseMarkStacks = 4)
        for (withVanguard in listOf(false, true)) {
            val profiles = (1..6).map { i ->
                role("d$i", "角色$i", i, 70.0, 80.0, reliableVanguard = if (withVanguard && i == 1) 100.0 else 0.0)
            }.associateBy { it.characterId }
            val planner = recommendationPlanner(profiles, scoringConfig.copy(teamSearchBeamWidth = 1))
            for (requestedTeams in listOf(1, 2)) {
                val first = planner.retryRecommendation(
                    profiles.keys, context, emptySet(), requestedBossTeamCount = requestedTeams,
                ) as LabyrinthBattleTeamRecommendationResult.Ready
                val failed = labyrinthBattleTeamSignature(first.recommendation.members.map { it.characterId })
                val next = planner.retryRecommendation(
                    profiles.keys, context, setOf(failed), requestedBossTeamCount = requestedTeams,
                ) as LabyrinthBattleTeamRecommendationResult.Ready
                assertEquals(5, next.recommendation.members.size)
                assertEquals(if (withVanguard) 1 else 0, next.recommendation.safeBossTeamCount)
                assertTrue(labyrinthBattleTeamSignature(next.recommendation.members.map { it.characterId }) != failed)
            }
        }
    }

    @Test fun `boss follow-up slot fields a supplement team when the residual roster has no tank`() {
        // 2026-09-17: teams 1/2 took both tanks; slot 3 must still be filled with the leftover
        // damage dealers instead of leaving the Boss on a sliver.
        val residual = (1..5).map { i -> role("d$i", "输出$i", i + 1, 70.0, 80.0) }
            .associateBy { it.characterId }
        val context = legacyFrontlineContext(defenseMarkStacks = 4)

        // Since 2026-09-19 a tankless pool is fielded rather than refused, so both the strict and
        // the explicit follow-up call produce a team; neither may report a safe lead.
        val strict = recommendationPlanner(residual)
            .initialRecommendation(residual.keys, context, requestedBossTeamCount = 1)
            as LabyrinthBattleTeamRecommendationResult.Ready
        assertEquals(0, strict.recommendation.safeBossTeamCount)

        val filled = recommendationPlanner(residual).initialRecommendation(
            residual.keys, context, requestedBossTeamCount = 1, allowSupplementLead = true,
        ) as LabyrinthBattleTeamRecommendationResult.Ready
        assertEquals(5, filled.recommendation.members.size)
        assertEquals(0, filled.recommendation.safeBossTeamCount)
        assertTrue(filled.recommendation.reasons.any { it.contains("补刀队") })

        val retry = recommendationPlanner(residual).retryRecommendation(
            residual.keys, context, failedTeamSignatures = emptySet(), requestedBossTeamCount = 1, allowSupplementLead = true,
        )
        assertTrue(retry is LabyrinthBattleTeamRecommendationResult.Ready)
    }

    private fun recommendationPlanner(
        profiles: Map<String, LabyrinthRoleProfile>,
        config: LabyrinthTeamScoringConfig = scoringConfig,
    ): LabyrinthBattleTeamRecommendationPlanner {
        val optimizer = LabyrinthTeamOptimizer(LabyrinthTeamScorer(config))
        return LabyrinthBattleTeamRecommendationPlanner(
            profiles = profiles,
            teamPlanSearcher = LabyrinthTeamPlanSearcher(
                optimizer,
                LabyrinthTeamPlanSearchConfig(
                    oneTeamKillScore = 0.0,
                    mainTeamScore = 0.0,
                    cleanupTeamScore = 0.0,
                    mainPlusCleanupCombinedScore = 0.0,
                    stableTeamScore = 0.0,
                    fallbackTeamScore = 0.0,
                    threeTeamCombinedScore = 0.0,
                ),
            ),
        )
    }

    private fun role(
        id: String,
        name: String,
        position: Int,
        userScore: Double,
        damage: Double,
        reliableVanguard: Double = 0.0,
        healing: Double = 0.0,
    ) = LabyrinthRoleProfile(
        characterId = id,
        displayName = name,
        roleClass = when {
            reliableVanguard > 0.0 -> "掩护者"
            healing > 0.0 -> "治疗者"
            else -> "攻击者"
        },
        attribute = LabyrinthCharacterAttribute.LIGHT,
        damageType = LabyrinthRoleDamageType.PHYSICAL,
        position = position,
        userScore = userScore,
        userRatingCount = 5,
        physicalDamagePotential = damage,
        magicDamagePotential = 0.0,
        functions = LabyrinthRoleFunctions(
            reliableVanguard = reliableVanguard,
            healing = healing,
            pureHealer = if (healing > 0.0) healing else null,
        ),
    )
}
