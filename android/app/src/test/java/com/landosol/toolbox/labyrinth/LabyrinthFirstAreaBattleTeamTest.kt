package com.landosol.toolbox.labyrinth

import com.landosol.toolbox.labyrinth.node.LabyrinthNodeTypes
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LabyrinthFirstAreaBattleTeamTest {
    private val runtime by lazy {
        val asset = listOf(
            File("app/src/main/assets/resource-packs/cn-bilibili/labyrinth-role-decision.json"),
            File("src/main/assets/resource-packs/cn-bilibili/labyrinth-role-decision.json"),
        ).first { it.isFile }
        when (val result = LabyrinthRoleDecisionDataParser.parse(asset.readText())) {
            is LabyrinthRoleDecisionDataResult.Ready -> result.runtime
            is LabyrinthRoleDecisionDataResult.Unavailable -> error(result.reason)
        }
    }
    private val opening = listOf("1026", "1033", "1038")
    private val expanded = opening + listOf("1182", "1165", "1096", "1297")

    @Test
    fun `first area permits the expanded roster for normal ex and boss battles`() {
        for (kind in LabyrinthCombatKind.entries) {
            val context = context(area = 1, kind = kind)
            val roster = expanded.map(runtime.profiles::getValue)
            assertEquals(listOf("1026"), roster.filter { it.isEligibleBattleVanguard(context) }.map { it.characterId })
            val plan = runtime.teamPlanSearcher.initialSearch(roster, context)
            assertEquals(LabyrinthTeamPlanKind.SUPPLEMENT_FILL, plan.kind)
            assertEquals(0, plan.safeTeamCount)
            for (teamCount in if (kind == LabyrinthCombatKind.BOSS) listOf(1, 3) else listOf(1)) {
                val result = runtime.battleTeamRecommendationPlanner.initialRecommendation(
                    expanded, context, requestedBossTeamCount = teamCount,
                )
                assertTrue("kind=$kind teams=$teamCount result=$result", result is LabyrinthBattleTeamRecommendationResult.Ready)
                val recommendation = (result as LabyrinthBattleTeamRecommendationResult.Ready).recommendation
                assertEquals(5, recommendation.members.size)
                assertEquals(0, recommendation.safeBossTeamCount)
                assertFalse(recommendation.members.map { runtime.profiles.getValue(it.characterId) }
                    .hasEligibleVanguardAtActualFront(context))
                assertTrue(recommendation.reasons.any { it.contains("第一层") && it.contains("无T阵容") })
            }
        }
    }

    @Test
    fun `later and unknown areas retain the original expanded roster refusal`() {
        for (area in listOf(null, 0, 2, 3, 4, 5)) {
            for (kind in LabyrinthCombatKind.entries) {
                val context = context(area, kind)
                val plan = runtime.teamPlanSearcher.initialSearch(expanded.map(runtime.profiles::getValue), context)
                assertEquals("area=$area kind=$kind", LabyrinthTeamPlanKind.INSUFFICIENT_ROLES, plan.kind)
                for (teamCount in if (kind == LabyrinthCombatKind.BOSS) listOf(1, 3) else listOf(1)) {
                    val planner = runtime.battleTeamRecommendationPlanner
                    assertTrue(planner.initialRecommendation(expanded, context, requestedBossTeamCount = teamCount)
                        is LabyrinthBattleTeamRecommendationResult.Unavailable)
                    assertTrue(planner.retryRecommendation(expanded, context, emptySet(), requestedBossTeamCount = teamCount)
                        is LabyrinthBattleTeamRecommendationResult.Unavailable)
                }
            }
        }
    }

    @Test
    fun `existing small roster and wholly tankless allowances remain available in later areas`() {
        val tankless = expanded.filterNot { it == "1026" }
        for (area in listOf(null, 2, 3, 4, 5)) {
            for (acquired in listOf(opening, expanded.take(5), tankless)) {
                val result = runtime.battleTeamRecommendationPlanner.initialRecommendation(acquired, context(area))
                    as LabyrinthBattleTeamRecommendationResult.Ready
                assertEquals(minOf(5, acquired.size), result.recommendation.members.size)
                assertEquals(0, result.recommendation.safeBossTeamCount)
            }
        }
    }

    @Test
    fun `first area still prefers a qualified actual vanguard when available`() {
        val tank = LabyrinthRoleProfile(
            characterId = "tank", displayName = "T", roleClass = "掩护者",
            attribute = LabyrinthCharacterAttribute.LIGHT, damageType = LabyrinthRoleDamageType.PHYSICAL,
            position = 1, userScore = 80.0, physicalDamagePotential = 20.0, magicDamagePotential = 0.0,
            functions = LabyrinthRoleFunctions(reliableVanguard = 100.0),
        )
        val profiles = runtime.profiles + (tank.characterId to tank)
        val planner = LabyrinthBattleTeamRecommendationPlanner(profiles, runtime.teamPlanSearcher)
        for (area in listOf(null, 1, 2, 3, 4, 5)) {
            val result = planner.initialRecommendation(expanded + tank.characterId, context(area))
                as LabyrinthBattleTeamRecommendationResult.Ready
            assertEquals("tank", result.recommendation.vanguard.characterId)
            assertEquals(1, result.recommendation.safeBossTeamCount)
        }
    }

    @Test
    fun `first area retry keeps the original failed signature and team size gates`() {
        val planner = runtime.battleTeamRecommendationPlanner
        for (kind in LabyrinthCombatKind.entries) {
            val context = context(area = 1, kind = kind)
            for (teamCount in if (kind == LabyrinthCombatKind.BOSS) listOf(1, 3) else listOf(1)) {
                val result = planner.retryRecommendation(expanded, context, emptySet(), requestedBossTeamCount = teamCount)
                    as LabyrinthBattleTeamRecommendationResult.Ready
                assertEquals(5, result.recommendation.members.size)
                assertEquals(0, result.recommendation.safeBossTeamCount)
                val failed = labyrinthBattleTeamSignature(result.recommendation.members.map { it.characterId })
                val again = planner.retryRecommendation(expanded, context, setOf(failed), requestedBossTeamCount = teamCount)
                assertFalse(again is LabyrinthBattleTeamRecommendationResult.Ready &&
                    labyrinthBattleTeamSignature(again.recommendation.members.map { it.characterId }) == failed)
                assertTrue(planner.retryRecommendation(opening, context, emptySet(), requestedBossTeamCount = teamCount)
                    is LabyrinthBattleTeamRecommendationResult.Unavailable)
            }
        }
    }

    @Test
    fun `actual node area takes precedence over stale route progress for every battle kind`() {
        for (kind in LabyrinthCombatKind.entries) {
            assertEquals(1, labyrinthBattleTeamArea(1, kind, route(2, LabyrinthNodeTypes.NORMAL_BATTLE), 2))
            assertEquals(2, labyrinthBattleTeamArea(2, kind, route(1, LabyrinthNodeTypes.NORMAL_BATTLE), 1))
        }
    }

    @Test
    fun `boss remains in its own area after the saved cursor advances to the next start`() {
        for (area in 1..4) {
            val boss = node(area, LabyrinthNodeTypes.BOSS)
            val nextStart = node(area + 1, LabyrinthNodeTypes.START)
            val route = route(area, LabyrinthNodeTypes.BOSS).copy(
                nodes = listOf(boss, nextStart), currentBlockId = nextStart.blockId,
            )
            assertEquals(area, labyrinthBattleTeamArea(null, LabyrinthCombatKind.BOSS, route, area + 1))
            assertEquals(area + 1, labyrinthBattleTeamArea(null, LabyrinthCombatKind.NORMAL, route, area))
            assertEquals(area + 1, labyrinthBattleTeamArea(null, LabyrinthCombatKind.EX, route, area))
        }
    }

    @Test
    fun `event battles use their node area and missing area does not default to the first`() {
        for (kind in listOf(LabyrinthCombatKind.NORMAL, LabyrinthCombatKind.EX)) {
            assertEquals(1, labyrinthBattleTeamArea(null, kind, route(1, LabyrinthNodeTypes.EVENT), 2))
            assertEquals(2, labyrinthBattleTeamArea(null, kind, route(2, LabyrinthNodeTypes.EVENT), 1))
        }
        assertNull(labyrinthBattleTeamArea(null, null, null, null))
        assertEquals(2, labyrinthBattleTeamArea(null, LabyrinthCombatKind.NORMAL, null, 2))
        assertNull(LabyrinthRoleDecisionContext(defenseMarkStacks = 2).currentArea)
    }

    private fun context(area: Int?, kind: LabyrinthCombatKind = LabyrinthCombatKind.NORMAL) = LabyrinthRoleDecisionContext(
        defenseMarkStacks = 2,
        currentArea = area,
        preferSingleDamageSystem = kind == LabyrinthCombatKind.EX,
        optimizeBossVanguardSynergy = kind == LabyrinthCombatKind.BOSS,
        targetCount = if (kind == LabyrinthCombatKind.BOSS) 3 else 1,
    )

    private fun route(area: Int, blockType: Int): LabyrinthRouteJson {
        val current = node(area, blockType)
        return LabyrinthRouteJson(
            enterId = 1L, guildId = 8, difficulty = 2, attempt = 1,
            rerollUntilFound = false, perfectStart = false, thirdBlockChoice = "ANY",
            area3BossIds = emptyList(), area5BossIds = emptyList(),
            nodes = listOf(current), currentBlockId = current.blockId,
        )
    }

    private fun node(area: Int, blockType: Int) = LabyrinthNodeJson(
        area = area, column = 1, row = 1, blockId = area * 10000L + 101L,
        blockType = blockType, questId = null, nextBlockIds = emptyList(), isAreaLastPoint = false,
    )
}
