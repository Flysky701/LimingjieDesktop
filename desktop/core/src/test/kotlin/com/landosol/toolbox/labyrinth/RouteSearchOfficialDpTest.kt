package com.landosol.toolbox.labyrinth

import com.landosol.toolbox.labyrinth.node.LabyrinthNodeTypes as Node
import com.landosol.toolbox.protocol.labyrinth.LabyrinthMapNode
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteSearchOfficialDpTest {
    private val policy = LabyrinthRoutePolicy(
        evaluationMode = LabyrinthRouteEvaluationMode.OFFICIAL_SCORE_DP,
        perfectStart = true,
    )

    @Test
    fun `boss lists remove higher scoring paths in both boss areas`() {
        val map = fiveAreaMap()
        val unrestricted = found(
            search(map, policy.copy(area3BossIds = emptySet(), area5BossIds = emptySet())),
        )
        assertTrue(unrestricted.nodes.filter { it.blockType == Node.BOSS }.all { it.row == 2 })

        val restricted = found(search(map))
        val bosses = restricted.nodes.filter { it.blockType == Node.BOSS }
        assertEquals(listOf(3, 5), bosses.map { it.area })
        assertEquals(listOf(312505, 310103), bosses.map { LabyrinthBossCatalog.unitIdForQuest(it.area, it.questId) })
        assertEquals(RouteSearchResult.NoRoute, search(map, policy.copy(area3BossIds = setOf(301206))))
        assertEquals(RouteSearchResult.NoRoute, search(map, policy.copy(area5BossIds = setOf(319401))))
    }

    @Test
    fun `third block choice constrains both areas and cannot be bypassed by an edge`() {
        val map = fiveAreaMap()
        val expected = mapOf(
            LabyrinthThirdBlockChoice.RELIC to Node.RELIC,
            LabyrinthThirdBlockChoice.EVENT to Node.EVENT,
            LabyrinthThirdBlockChoice.EITHER to Node.RELIC,
        )
        for ((choice, type) in expected) {
            val route = found(search(map, policy.copy(thirdBlockChoice = choice)))
            assertEquals(listOf(type, type), route.nodes
                .filter { it.area in setOf(3, 5) && it.column == 3 }
                .map { it.blockType })
        }

        val bypass = map.map { node ->
            if (node.blockId == 302L) node.copy(nextBlockIds = listOf(304)) else node
        }
        assertEquals(RouteSearchResult.NoRoute, search(bypass))
        val eventOnly = map.filterNot { it.blockId == 503L }
        val relic = policy.copy(thirdBlockChoice = LabyrinthThirdBlockChoice.RELIC)
        val either = policy.copy(thirdBlockChoice = LabyrinthThirdBlockChoice.EITHER)
        assertEquals(RouteSearchResult.NoRoute, search(eventOnly, relic))
        assertTrue(search(eventOnly, either) is RouteSearchResult.Found)
    }

    @Test
    fun `resume includes the current block and rejects a conflicting third block`() {
        val map = fiveAreaMap()
        val matcher = LabyrinthOpeningRouteMatcher()
        val relic = policy.copy(thirdBlockChoice = LabyrinthThirdBlockChoice.RELIC)
        assertEquals(
            RouteSearchResult.NoRoute,
            matcher.search(7, map, policy = relic, currentBlockId = 313),
        )
        val resumed = found(matcher.search(7, map, difficulty = 1, policy = policy, currentBlockId = 313))
        assertEquals(setOf(3), resumed.nodes.map { it.area }.toSet())
        assertTrue(313L in resumed.blockIds)
    }

    @Test
    fun `official score favors EX over normal and relic without double counting rewards`() {
        val map = scoreFork()
        val score = scoreRouteOfficialDp(map)
        assertEquals(listOf(101L, 112L, 113L, 104L, 105L, 106L), score.bestRoute.map { it.blockId })
        assertEquals(7400.0, score.expectedScore, 0.0)
        assertEquals(7400L, score.hardUpperBound)
        assertEquals(mapOf(3700L to 1.0), scoreRouteOfficialDp(map, difficulty = 1).scoreDistribution)

        val route = found(LabyrinthOpeningRouteMatcher().search(7, map + basicArea(2) + bossArea(3), 1, policy))
        assertEquals(score.bestRoute, route.nodes.filter { it.area == 1 })
        assertFalse(route.nodes.any { it.area > 3 })
    }

    @Test
    fun `additional required node types can select a lower scoring branch`() {
        val map = scoreFork() + basicArea(2) + bossArea(3)
        val choice = policy.copy(
            thirdBlockChoice = LabyrinthThirdBlockChoice.EVENT,
            requiredBlockTypes = setOf(Node.RELIC),
        )
        val route = found(LabyrinthOpeningRouteMatcher().search(7, map, 1, choice))
        assertTrue(103L in route.blockIds)
        assertFalse(112L in route.blockIds)
    }

    @Test
    fun `settings and shared config round trip the official mode and summarize the third block`() {
        val settings = LabyrinthRerollSettings(
            difficulty = 1,
            routeEvaluationMode = LabyrinthRouteEvaluationMode.OFFICIAL_SCORE_DP,
            thirdBlockChoice = LabyrinthThirdBlockChoice.EVENT,
        )
        val saved = Json.encodeToString(LabyrinthRerollSettings.serializer(), settings)
        val decoded = Json.decodeFromString(LabyrinthRerollSettings.serializer(), saved)
        assertEquals(settings, decoded)
        assertEquals(1, decoded.toConfig(7).difficulty)
        assertEquals(policy.evaluationMode, decoded.toConfig(7).routePolicy.evaluationMode)

        val share = LabyrinthSharedConfiguration(appVersion = "test", exportedAtMillis = 0, reroll = settings)
        val imported = LabyrinthConfigShareCodec.decode(LabyrinthConfigShareCodec.encode(share))
        assertEquals(settings, imported.reroll)
        val summary = LabyrinthConfigImportPlan(imported, imported.modules, 7, LabyrinthConfigurationValues()).changeSummary()
        assertTrue(summary.any { it.contains("官方高分路线") })
        assertTrue(summary.any { it.contains("区域 3 / 5 第三格（硬条件）") && it.contains("必须事件") })
    }

    @Test
    fun `route snapshot preview scores the saved path at its saved difficulty`() {
        val map = scoreFork()
        val snapshot = LabyrinthRouteJson(
            enterId = 7,
            guildId = 1,
            difficulty = 1,
            attempt = 1,
            rerollUntilFound = true,
            perfectStart = true,
            thirdBlockChoice = policy.thirdBlockChoice.name,
            area3BossIds = emptyList(),
            area5BossIds = emptyList(),
            nodes = map.filter { it.row == 1 }.map(::snapshotNode),
            allNodes = map.map(::snapshotNode),
            routeEvaluationMode = policy.evaluationMode.name,
        )
        val serialized = Json.encodeToString(LabyrinthRouteJson.serializer(), snapshot)
        val decoded = Json.decodeFromString(LabyrinthRouteJson.serializer(), serialized)
        val preview = LabyrinthRoutePreviewBuilder.build(decoded)

        assertEquals(policy.evaluationMode, decoded.evaluationMode)
        assertEquals("官方高分路线", preview.modeLabel)
        assertTrue(preview.issues.toString(), preview.topologyVerified)
        assertEquals(3000.0, requireNotNull(preview.expectedScore), 0.0)
        assertEquals(3000L, requireNotNull(preview.hardUpperBound))
        assertTrue(preview.note.contains("3000"))
    }

    @Test
    fun `opening matcher rejects routes that cannot reach configured targetScore`() {
        val map = fiveAreaMap()
        val matcher = LabyrinthOpeningRouteMatcher()
        val officialReject = matcher.search(
            enterId = 7L,
            nodes = map,
            difficulty = 5,
            policy = policy.copy(targetScore = 50_000L),
            guildId = 1,
        )
        assertTrue(officialReject is RouteSearchResult.Rejected)
        assertFalse((officialReject as RouteSearchResult.Rejected).fatal)

        val valueReject = matcher.search(
            enterId = 7L,
            nodes = map,
            difficulty = 5,
            policy = LabyrinthRoutePolicy(
                evaluationMode = LabyrinthRouteEvaluationMode.VALUE_ROUTE,
                valueAllowance = 3,
                targetScore = 50_000L,
            ),
            guildId = 1,
        )
        assertTrue(valueReject is RouteSearchResult.Rejected)
        assertFalse((valueReject as RouteSearchResult.Rejected).fatal)
    }

    private fun search(nodes: List<LabyrinthMapNode>, policy: LabyrinthRoutePolicy = this.policy): RouteSearchResult =
        LabyrinthOpeningRouteMatcher().search(7, nodes, policy = policy)

    private fun found(result: RouteSearchResult) = (result as RouteSearchResult.Found).route

    private fun fiveAreaMap() = basicArea(1) + basicArea(2) + bossArea(3) + basicArea(4) + bossArea(5)

    private fun basicArea(area: Int) = listOf(
        node(area, 1, Node.START, 2),
        node(area, 2, Node.NORMAL_BATTLE),
    )

    private fun bossArea(area: Int) = listOf(
        node(area, 1, Node.START, 2),
        node(area, 2, Node.NORMAL_BATTLE, 3, 13, 23),
        node(area, 3, Node.RELIC, 4, 14),
        node(area, 13, Node.EVENT, 4, 14),
        node(area, 23, Node.EX_BATTLE, 4, 14),
        node(area, 4, Node.NORMAL_BATTLE, 5),
        node(area, 14, Node.EX_BATTLE, 15),
        node(area, 5, Node.BOSS, questId = if (area == 3) 770330401 else 770530301),
        node(area, 15, Node.BOSS, questId = if (area == 3) 770330501 else 770530101),
    )

    private fun scoreFork() = listOf(
        node(1, 1, Node.START, 2, 12),
        node(1, 2, Node.NORMAL_BATTLE, 3),
        node(1, 3, Node.RELIC, 4),
        node(1, 12, Node.EX_BATTLE, 13),
        node(1, 13, Node.EVENT, 4),
        node(1, 4, Node.LINK, 5),
        node(1, 5, Node.SHOP, 6),
        node(1, 6, Node.BOSS),
    )

    private fun node(area: Int, id: Int, type: Int, vararg next: Int, questId: Int? = null) = LabyrinthMapNode(
        area = area,
        column = id % 10,
        row = id / 10 + 1,
        blockId = area * 100L + id,
        blockType = type,
        questId = questId,
        nextBlockIds = next.map { area * 100L + it },
        isAreaLastPoint = next.isEmpty(),
    )

    private fun snapshotNode(node: LabyrinthMapNode) = LabyrinthNodeJson(
        area = node.area,
        column = node.column,
        row = node.row,
        blockId = node.blockId,
        blockType = node.blockType,
        questId = node.questId,
        nextBlockIds = node.nextBlockIds,
        isAreaLastPoint = node.isAreaLastPoint,
    )
}
