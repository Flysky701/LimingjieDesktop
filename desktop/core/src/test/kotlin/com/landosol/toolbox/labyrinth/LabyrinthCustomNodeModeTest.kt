package com.landosol.toolbox.labyrinth

import com.landosol.toolbox.labyrinth.node.LabyrinthNodeTypes
import com.landosol.toolbox.protocol.labyrinth.LabyrinthMapNode
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** 覆盖新增的 CUSTOM_NODES：连通路径、失败原因、旧模式等价、配置与分享码兼容。 */
class LabyrinthCustomNodeModeTest {
    private val defaultPlan = LabyrinthCustomNodePlan()

    @Test
    fun `custom mode finds a connected route for the selected columns`() {
        val nodes = listOf(1, 2, 3).flatMap { area -> linearArea(area, legacyTypes(area), bossQuest(area)) }

        val result = matcher().search(7, nodes, difficulty = 1, policy = customPolicy())

        assertTrue(result is RouteSearchResult.Found)
        val route = (result as RouteSearchResult.Found).route
        assertEquals(setOf(1, 2, 3), route.nodes.mapTo(mutableSetOf(), LabyrinthMapNode::area))
        assertEquals(6 + 7 + 7, route.nodes.size)
        assertEquals(listOf(1, 2, 3, 4, 5, 6), route.nodes.filter { it.area == 1 }.map { it.column })
    }

    @Test
    fun `default plan reproduces the legacy template route exactly`() {
        val nodes = (1..5).flatMap { area -> linearArea(area, legacyTypes(area), bossQuest(area)) }

        val custom = matcher().search(9, nodes, difficulty = 5, policy = customPolicy())
        val legacy = matcher().search(
            9,
            nodes,
            difficulty = 5,
            policy = LabyrinthRoutePolicy(
                perfectStart = true,
                evaluationMode = LabyrinthRouteEvaluationMode.LEGACY_TEMPLATE,
                thirdBlockChoice = LabyrinthThirdBlockChoice.RELIC,
            ),
        )

        assertTrue(custom is RouteSearchResult.Found)
        assertTrue(legacy is RouteSearchResult.Found)
        assertEquals(
            (legacy as RouteSearchResult.Found).route.blockIds,
            (custom as RouteSearchResult.Found).route.blockIds,
        )
    }

    @Test
    fun `selecting event rejects a column that only has a character node`() {
        val nodes = listOf(1, 2, 3).flatMap { area -> linearArea(area, legacyTypes(area), bossQuest(area)) }
        val policy = customPolicy(defaultPlan.copy(area1Column5 = LabyrinthNodeTypes.EVENT))

        val result = matcher().search(7, nodes, difficulty = 1, policy = policy)

        assertTrue(result is RouteSearchResult.Rejected)
        val rejection = result as RouteSearchResult.Rejected
        assertFalse(rejection.fatal)
        assertTrue(rejection.message, rejection.message.contains("区域1 第5列"))
    }

    @Test
    fun `type that exists but is unreachable is reported as not connected`() {
        val area1 = linearArea(1, legacyTypes(1)).toMutableList()
        val start = area1.first { it.column == 1 }
        val bridge = area1.first { it.column == 2 }
        val reachableEvent = area1.first { it.column == 3 }.copy(blockType = LabyrinthNodeTypes.EVENT)
        val orphanCharacter = area1.first { it.column == 3 }.copy(
            row = 3,
            blockId = 199,
            nextBlockIds = listOf(area1.first { it.column == 4 }.blockId),
        )
        area1[area1.indexOf(start)] = start.copy(nextBlockIds = listOf(bridge.blockId))
        area1[area1.indexOf(bridge)] = bridge.copy(nextBlockIds = listOf(reachableEvent.blockId))
        area1[area1.indexOf(area1.first { it.column == 3 })] = reachableEvent
        area1 += orphanCharacter
        val nodes = area1 + linearArea(2, legacyTypes(2)) + linearArea(3, legacyTypes(3), bossQuest(3))

        val result = matcher().search(7, nodes, difficulty = 1, policy = customPolicy())

        assertTrue(result is RouteSearchResult.Rejected)
        val rejection = result as RouteSearchResult.Rejected
        assertFalse(rejection.fatal)
        assertTrue(rejection.message, rejection.message.contains("区域1 第3列"))
        assertTrue(rejection.message, rejection.message.contains("无法连通"))
    }

    @Test
    fun `boss outside the selected list is rejected`() {
        val nodes = listOf(1, 2).flatMap { area -> linearArea(area, legacyTypes(area), bossQuest(area)) } +
            linearArea(3, legacyTypes(3), bossQuest = 770330501)

        val result = matcher().search(7, nodes, difficulty = 1, policy = customPolicy())

        assertTrue(result is RouteSearchResult.Rejected)
        val rejection = result as RouteSearchResult.Rejected
        assertFalse(rejection.fatal)
        assertTrue(rejection.message, rejection.message.contains("Boss"))
        assertEquals(306604, LabyrinthBossCatalog.unitIdForQuest(3, 770330501))
    }

    @Test
    fun `current block outside the target route is rejected while later areas still evaluate`() {
        val nodes = listOf(1, 2, 3).flatMap { area -> linearArea(area, legacyTypes(area), bossQuest(area)) } +
            LabyrinthMapNode(
                area = 2,
                column = 2,
                row = 5,
                blockId = 299,
                blockType = LabyrinthNodeTypes.LINK,
                questId = null,
                nextBlockIds = emptyList(),
                isAreaLastPoint = false,
            )

        val result = matcher().search(7, nodes, difficulty = 1, policy = customPolicy(), currentBlockId = 299)

        assertTrue(result is RouteSearchResult.Rejected)
        val rejection = result as RouteSearchResult.Rejected
        assertTrue(rejection.message, rejection.message.contains("无法包含当前节点"))
    }

    @Test
    fun `duplicate block ids and incomplete columns stop the run instead of rerolling`() {
        val duplicated = listOf(1, 2, 3).flatMap { area -> linearArea(area, legacyTypes(area), bossQuest(area)) }
            .let { it + it.last() }
        val duplicatedResult = matcher().search(7, duplicated, difficulty = 1, policy = customPolicy())
        assertTrue(duplicatedResult is RouteSearchResult.Rejected)
        assertTrue((duplicatedResult as RouteSearchResult.Rejected).fatal)

        val shortArea = listOf(1, 2).flatMap { area -> linearArea(area, legacyTypes(area)) } +
            linearArea(3, legacyTypes(3).dropLast(1))
        val missingResult = matcher().search(7, shortArea, difficulty = 1, policy = customPolicy())
        assertTrue(missingResult is RouteSearchResult.Rejected)
        val missing = missingResult as RouteSearchResult.Rejected
        assertTrue(missing.fatal)
        assertTrue(missing.message, missing.message.contains("列不完整"))
    }

    @Test
    fun `invalid plan is rejected before the search starts`() {
        val invalid = customPolicy(defaultPlan.copy(area2Columns2And6 = LabyrinthNodeTypes.RELIC))
        val result = matcher().search(7, linearArea(1, legacyTypes(1)), difficulty = 1, policy = invalid)

        assertTrue(result is RouteSearchResult.Rejected)
        assertTrue((result as RouteSearchResult.Rejected).fatal)
        assertTrue(invalid.customNodePlan.validationError()!!.contains("区域2"))
    }

    @Test
    fun `third block preference belongs to the custom plan`() {
        val eventRoute = linearArea(3, legacyTypes(3), bossQuest(3)).map { node ->
            if (node.column == 3) node.copy(blockType = LabyrinthNodeTypes.EVENT) else node
        }
        val nodes = linearArea(1, legacyTypes(1)) + linearArea(2, legacyTypes(2)) + eventRoute

        val rejected = matcher().search(7, nodes, difficulty = 1, policy = customPolicy())
        val accepted = matcher().search(
            7,
            nodes,
            difficulty = 1,
            policy = customPolicy(defaultPlan.copy(thirdBlockChoice = LabyrinthThirdBlockChoice.EVENT)),
        )

        assertTrue((rejected as RouteSearchResult.Rejected).message.contains("区域3 第3列"))
        assertTrue(accepted is RouteSearchResult.Found)
    }

    @Test
    fun `settings validation rejects a type outside the row options`() {
        assertNull(LabyrinthRerollSettings(customNodePlan = defaultPlan).validationError())
        val invalid = LabyrinthRerollSettings(
            customNodePlan = defaultPlan.copy(area4Column7 = LabyrinthNodeTypes.EVENT),
        )
        assertTrue(invalid.validationError()!!.contains("区域4第7列"))
        assertThrows(IllegalArgumentException::class.java) { invalid.toConfig(1) }
    }

    @Test
    fun `share code round trips the custom plan`() {
        val settings = LabyrinthRerollSettings(
            routeEvaluationMode = LabyrinthRouteEvaluationMode.CUSTOM_NODES,
            customNodePlan = LabyrinthCustomNodePlan(
                area1Column3 = LabyrinthNodeTypes.EVENT,
                area1Column5 = LabyrinthNodeTypes.LINK,
                area2Columns2And6 = LabyrinthNodeTypes.EVENT,
                area3Column4 = LabyrinthNodeTypes.LINK,
                area4Column2 = LabyrinthNodeTypes.RELIC,
                area4Column7 = LabyrinthNodeTypes.LINK,
                thirdBlockChoice = LabyrinthThirdBlockChoice.EITHER,
            ),
        )
        val code = codec().encode(shareCode(settings))

        assertEquals(settings, codec().decode(code).reroll)
    }

    @Test
    fun `share code without the new field still imports with defaults`() {
        val settings = LabyrinthRerollSettings(difficulty = 3, maxAttempts = "25")
        val code = codec().encode(shareCode(settings))
        val json = Json { encodeDefaults = true }
        val root = json.parseToJsonElement(unpack(code)).jsonObject
        val modules = root.getValue("modules").jsonObject
        val reroll = modules.getValue("reroll").jsonObject - "customNodePlan"
        val legacyRoot = JsonObject(root + ("modules" to JsonObject(modules + ("reroll" to JsonObject(reroll)))))

        val decoded = codec().decode(pack(json.encodeToString(JsonElement.serializer(), legacyRoot)))

        assertEquals(settings, decoded.reroll)
        assertEquals(defaultPlan, decoded.reroll!!.customNodePlan)
    }

    @Test
    fun `legacy share code cannot claim the custom mode without a plan`() {
        val code = codec().encode(shareCode(LabyrinthRerollSettings()))
        val json = Json { encodeDefaults = true }
        val root = json.parseToJsonElement(unpack(code)).jsonObject
        val modules = root.getValue("modules").jsonObject
        val reroll = modules.getValue("reroll").jsonObject -
            "customNodePlan" +
            ("routeEvaluationMode" to JsonPrimitive(LabyrinthRouteEvaluationMode.CUSTOM_NODES.name))
        val broken = JsonObject(root + ("modules" to JsonObject(modules + ("reroll" to JsonObject(reroll)))))

        assertThrows(IllegalArgumentException::class.java) {
            codec().decode(pack(json.encodeToString(JsonElement.serializer(), broken)))
        }
    }

    @Test
    fun `import preview reports each changed column`() {
        val before = LabyrinthRerollSettings()
        val after = before.copy(
            customNodePlan = before.customNodePlan.copy(
                area1Column5 = LabyrinthNodeTypes.EVENT,
                area4Column2 = LabyrinthNodeTypes.RELIC,
            ),
        )
        val summary = LabyrinthConfigImportPlan(
            configuration = shareCode(after),
            selected = setOf(LabyrinthConfigModule.REROLL),
            targetAccountId = 7,
            expected = LabyrinthConfigurationValues(rerolls = mapOf(7L to before)),
        ).changeSummary()

        assertTrue(summary.toString(), summary.any { it.contains("区域1第5列") && it.contains("角色") && it.contains("事件") })
        assertTrue(summary.toString(), summary.any { it.contains("区域4第2列") && it.contains("遗物") })
    }

    private fun matcher() = LabyrinthOpeningRouteMatcher()

    private fun customPolicy(plan: LabyrinthCustomNodePlan = defaultPlan) = LabyrinthRoutePolicy(
        evaluationMode = LabyrinthRouteEvaluationMode.CUSTOM_NODES,
        customNodePlan = plan,
    )

    private fun legacyTypes(area: Int): List<Int> = when (area) {
        1 -> listOf(1, 2, 4, 2, 4, 6)
        2 -> listOf(1, 4, 2, 6, 3, 4, 6)
        3 -> listOf(1, 2, 6, 4, 3, 7, 8)
        4 -> listOf(1, 4, 3, 5, 3, 2, 4, 7)
        else -> listOf(1, 2, 6, 3, 6, 3, 7, 8)
    }

    private fun bossQuest(area: Int): Int? = when (area) {
        3 -> 770330401
        5 -> 770530301
        else -> null
    }

    private fun linearArea(area: Int, types: List<Int>, bossQuest: Int? = null): List<LabyrinthMapNode> {
        val ids = types.indices.map { area * 100L + it + 1 }
        return types.mapIndexed { index, type ->
            LabyrinthMapNode(
                area = area,
                column = index + 1,
                row = 1,
                blockId = ids[index],
                blockType = type,
                questId = bossQuest.takeIf { index == types.lastIndex },
                nextBlockIds = if (index == types.lastIndex) emptyList() else listOf(ids[index + 1]),
                isAreaLastPoint = index == types.lastIndex,
            )
        }
    }

    private fun shareCode(settings: LabyrinthRerollSettings) = LabyrinthConfigShareCodec.create(
        LabyrinthConfigurationValues(rerolls = mapOf(7L to settings)),
        setOf(LabyrinthConfigModule.REROLL),
        7,
        "test-version",
        1_000,
    )

    private fun codec() = LabyrinthConfigShareCodec

    private fun pack(text: String): String {
        val output = ByteArrayOutputStream()
        GZIPOutputStream(output).use { it.write(text.toByteArray(Charsets.UTF_8)) }
        return LabyrinthConfigShareCodec.PREFIX +
            Base64.getUrlEncoder().withoutPadding().encodeToString(output.toByteArray())
    }

    private fun unpack(code: String): String {
        val bytes = Base64.getUrlDecoder().decode(code.removePrefix(LabyrinthConfigShareCodec.PREFIX))
        return GZIPInputStream(bytes.inputStream()).bufferedReader().use { it.readText() }
    }

    private fun assertNull(value: String?) = assertTrue(value, value == null)
}
