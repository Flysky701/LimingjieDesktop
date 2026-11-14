package com.landosol.toolbox.labyrinth

import com.landosol.toolbox.labyrinth.node.LabyrinthNodeTypes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LabyrinthRoutePreviewTest {
    @Test
    fun `custom mode lists one cell per column and marks the chosen columns`() {
        val saved = route(
            types = listOf(1, 4, 2, 6),
            mode = LabyrinthRouteEvaluationMode.CUSTOM_NODES,
        )

        val preview = LabyrinthRoutePreviewBuilder.build(saved)

        assertTrue(preview.issues.toString(), preview.topologyVerified)
        val floor = preview.floors.single()
        assertEquals(1, floor.area)
        assertEquals(4, floor.columnCount)
        assertEquals(listOf("起点", "角色", "普战", "遗物"), floor.nodes.map { it.label })
        assertEquals(listOf(1, 2, 3, 4), floor.nodes.map { it.column })
        assertEquals(listOf(false, false, true, false), floor.nodes.map { it.specified })
        assertTrue(preview.modeLabel.contains("自定义"))
        assertEquals("仅路线结构，不含收益估算", preview.note)
    }

    @Test
    fun `columns chosen in the custom plan are highlighted`() {
        val saved = route(
            types = listOf(1, 2, 4, 2, 5, 6),
            mode = LabyrinthRouteEvaluationMode.CUSTOM_NODES,
        )

        val preview = LabyrinthRoutePreviewBuilder.build(saved)

        assertEquals(listOf(false, false, true, false, true, false), preview.floors.single().nodes.map { it.specified })
    }

    @Test
    fun `legacy mode has no highlighted column`() {
        val saved = route(types = listOf(1, 2, 4, 2), mode = LabyrinthRouteEvaluationMode.LEGACY_TEMPLATE)

        assertTrue(LabyrinthRoutePreviewBuilder.build(saved).floors.single().nodes.none { it.specified })
    }

    @Test
    fun `missing map snapshot is reported instead of pretending the route is verified`() {
        val saved = route(types = listOf(1, 2, 4), mode = LabyrinthRouteEvaluationMode.CUSTOM_NODES, withMap = false)

        val preview = LabyrinthRoutePreviewBuilder.build(saved)

        assertFalse(preview.topologyVerified)
        assertTrue(preview.issues.toString(), preview.issues.any { it.contains("地图快照缺失") })
        assertEquals(3, preview.floors.single().nodes.size)
    }

    @Test
    fun `broken edge is reported with both ids`() {
        val saved = route(types = listOf(1, 2, 4), mode = LabyrinthRouteEvaluationMode.CUSTOM_NODES)
            .let { value ->
                val nodes = value.nodes.toMutableList()
                nodes[0] = nodes[0].copy(nextBlockIds = emptyList())
                value.copy(nodes = nodes)
            }

        val preview = LabyrinthRoutePreviewBuilder.build(saved)

        assertFalse(preview.topologyVerified)
        assertTrue(preview.issues.toString(), preview.issues.any { it.contains("路线边未验证") })
    }

    @Test
    fun `node missing from the saved map is reported`() {
        val saved = route(types = listOf(1, 2, 4), mode = LabyrinthRouteEvaluationMode.CUSTOM_NODES)
            .let { it.copy(allNodes = it.allNodes.drop(1)) }

        val preview = LabyrinthRoutePreviewBuilder.build(saved)

        assertFalse(preview.topologyVerified)
        assertTrue(preview.issues.toString(), preview.issues.any { it.contains("不在保存的地图中") })
    }

    @Test
    fun `duplicate and empty routes are rejected`() {
        val empty = route(types = emptyList(), mode = LabyrinthRouteEvaluationMode.CUSTOM_NODES)
        assertTrue(LabyrinthRoutePreviewBuilder.build(empty).issues.contains("保存路线为空"))
        assertTrue(LabyrinthRoutePreviewBuilder.build(empty).floors.isEmpty())

        val duplicated = route(types = listOf(1, 2, 4), mode = LabyrinthRouteEvaluationMode.CUSTOM_NODES)
            .let { it.copy(nodes = it.nodes + it.nodes.last()) }
        assertTrue(
            LabyrinthRoutePreviewBuilder.build(duplicated).issues.any { it.contains("重复节点") },
        )
    }

    @Test
    fun `cache key follows the current block so advancing refreshes once`() {
        val start = route(types = listOf(1, 2, 4), mode = LabyrinthRouteEvaluationMode.CUSTOM_NODES)
        LabyrinthRoutePreviewCache.publish(start)
        val firstKey = LabyrinthRoutePreviewCache.key()
        val firstPayload = String(LabyrinthRoutePreviewCache.payload(), Charsets.UTF_8)

        LabyrinthRoutePreviewCache.publish(start)
        assertEquals(firstKey, LabyrinthRoutePreviewCache.key())

        val advanced = start.copy(currentBlockId = start.nodes[1].blockId)
        LabyrinthRoutePreviewCache.publish(advanced)

        assertNotEquals(firstKey, LabyrinthRoutePreviewCache.key())
        assertTrue(firstPayload.contains("\"enterId\""))
        assertTrue(firstPayload.contains("\"floors\""))
        assertTrue(String(LabyrinthRoutePreviewCache.payload(), Charsets.UTF_8).contains("\"currentBlockId\""))

        LabyrinthRoutePreviewCache.clear()
        assertEquals("null", String(LabyrinthRoutePreviewCache.payload(), Charsets.UTF_8))
    }

    private fun route(
        types: List<Int>,
        mode: LabyrinthRouteEvaluationMode,
        withMap: Boolean = true,
    ): LabyrinthRouteJson {
        val nodes = types.mapIndexed { index, type ->
            LabyrinthNodeJson(
                area = 1,
                column = index + 1,
                row = 1,
                blockId = 100L + index + 1,
                blockType = type,
                questId = null,
                nextBlockIds = if (index == types.lastIndex) emptyList() else listOf(100L + index + 2),
                isAreaLastPoint = index == types.lastIndex,
            )
        }
        return LabyrinthRouteJson(
            enterId = 4242,
            guildId = LabyrinthRerollOptions.DEFAULT_GUILD_ID,
            difficulty = 5,
            attempt = 1,
            rerollUntilFound = true,
            perfectStart = true,
            thirdBlockChoice = LabyrinthThirdBlockChoice.RELIC.name,
            area3BossIds = emptyList(),
            area5BossIds = emptyList(),
            nodes = nodes,
            allNodes = if (withMap) nodes else emptyList(),
            routeEvaluationMode = mode.name,
        )
    }

    @Test
    fun `type labels stay short but keep every protocol type`() {
        assertEquals("起点", LabyrinthRoutePreviewBuilder.labelOf(LabyrinthNodeTypes.START))
        assertEquals("普战", LabyrinthRoutePreviewBuilder.labelOf(LabyrinthNodeTypes.NORMAL_BATTLE))
        assertEquals("EX战", LabyrinthRoutePreviewBuilder.labelOf(LabyrinthNodeTypes.EX_BATTLE))
        assertEquals("角色", LabyrinthRoutePreviewBuilder.labelOf(LabyrinthNodeTypes.LINK))
        assertEquals("事件", LabyrinthRoutePreviewBuilder.labelOf(LabyrinthNodeTypes.EVENT))
        assertEquals("遗物", LabyrinthRoutePreviewBuilder.labelOf(LabyrinthNodeTypes.RELIC))
        assertEquals("商店", LabyrinthRoutePreviewBuilder.labelOf(LabyrinthNodeTypes.SHOP))
        assertEquals("Boss", LabyrinthRoutePreviewBuilder.labelOf(LabyrinthNodeTypes.BOSS))
    }
}
