package com.landosol.toolbox.labyrinth.node

import com.landosol.toolbox.clanbattle.recognition.PixelImage
import com.landosol.toolbox.labyrinth.vision.EntryPixelRect
import com.landosol.toolbox.protocol.labyrinth.LabyrinthMapNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class LabyrinthNodePositionTest {

    // ---------- resolveVisualIndexFromTop ----------

    @Test
    fun `three node column maps top to 0`() {
        assertEquals(0, resolveVisualIndexFromTop(40303, 3))
        assertEquals(1, resolveVisualIndexFromTop(40502, 3))
        assertEquals(2, resolveVisualIndexFromTop(50301, 3))
    }

    @Test
    fun `inactive wrong-type route crop waits instead of raising type conflict`() {
        val rect = EntryPixelRect(1360, 650, 280, 350)
        val target = node(40401, LabyrinthNodeTypes.EVENT)
        val action = LabyrinthNodeActionPlanner().planAction(
            NodeMatchResult(
                currentNodeId = 40301L,
                currentNodeType = LabyrinthNodeTypes.NORMAL_BATTLE,
                nextNode = target,
                visibleNodes = listOf(
                    NodePositionMapping(
                        blockId = target.blockId,
                        blockType = target.blockType,
                        column = target.column,
                        row = target.row,
                        confidence = 0.96,
                        screenRect = rect,
                        isClickable = false,
                        detectedBlockType = LabyrinthNodeTypes.NORMAL_BATTLE,
                        topologyConfidence = 0.96,
                        topologyBindingKind = NodeTopologyBindingKind.FULL_COLUMN,
                        protocolPromoted = true,
                    ),
                ),
                conflicts = listOf(
                    NodePositionConflict(
                        blockId = target.blockId,
                        expectedBlockType = target.blockType,
                        detectedBlockType = LabyrinthNodeTypes.NORMAL_BATTLE,
                        visualColumn = 3,
                        visualRow = 3,
                        confidence = 0.96,
                        screenRect = rect,
                        sourceClickable = false,
                    ),
                ),
                isComplete = false,
            ),
        )

        assertTrue("action=$action", action is NodeAction.WaitForLoad)
    }

    @Test
    fun `strong partial topology can recover inactive route target after reward transition`() {
        val targetRect = EntryPixelRect(1335, 650, 280, 350)
        val siblingRect = EntryPixelRect(1335, 126, 280, 350)
        val target = node(40401, LabyrinthNodeTypes.EVENT)
        val action = LabyrinthNodeActionPlanner().planAction(
            NodeMatchResult(
                currentNodeId = 40302L,
                currentNodeType = LabyrinthNodeTypes.EX_BATTLE,
                nextNode = target,
                visibleNodes = emptyList(),
                topologyMappings = listOf(
                    NodeTopologyMapping(
                        blockId = 40403L,
                        expectedBlockType = LabyrinthNodeTypes.EVENT,
                        logicalColumn = 4,
                        visualColumn = 2,
                        visualRow = 1,
                        screenRect = siblingRect,
                        detectedBlockType = LabyrinthNodeTypes.EVENT,
                        visualConfidence = 0.688,
                        topologyConfidence = 0.835,
                        isClickable = false,
                        bindingKind = NodeTopologyBindingKind.PARTIAL_COLUMN,
                    ),
                    NodeTopologyMapping(
                        blockId = 40401L,
                        expectedBlockType = LabyrinthNodeTypes.EVENT,
                        logicalColumn = 4,
                        visualColumn = 2,
                        visualRow = 3,
                        screenRect = targetRect,
                        detectedBlockType = LabyrinthNodeTypes.EVENT,
                        visualConfidence = 0.860,
                        topologyConfidence = 0.835,
                        isClickable = false,
                        bindingKind = NodeTopologyBindingKind.PARTIAL_COLUMN,
                    ),
                ),
                isComplete = false,
            ),
        )

        assertTrue("action=$action", action is NodeAction.ClickNode)
        assertEquals(targetRect, (action as NodeAction.ClickNode).screenRect)
    }

    @Test
    fun `inactive partial topology without ordered sibling never becomes clickable`() {
        val targetRect = EntryPixelRect(1335, 650, 280, 350)
        val target = node(40401, LabyrinthNodeTypes.EVENT)
        val action = LabyrinthNodeActionPlanner().planAction(
            NodeMatchResult(
                currentNodeId = 40302L,
                currentNodeType = LabyrinthNodeTypes.EX_BATTLE,
                nextNode = target,
                visibleNodes = emptyList(),
                topologyMappings = listOf(
                    NodeTopologyMapping(
                        blockId = 40401L,
                        expectedBlockType = LabyrinthNodeTypes.EVENT,
                        logicalColumn = 4,
                        visualColumn = 2,
                        visualRow = 3,
                        screenRect = targetRect,
                        detectedBlockType = LabyrinthNodeTypes.EVENT,
                        visualConfidence = 0.860,
                        topologyConfidence = 0.835,
                        isClickable = false,
                        bindingKind = NodeTopologyBindingKind.PARTIAL_COLUMN,
                    ),
                ),
                isComplete = false,
            ),
        )

        assertTrue("action=$action", action is NodeAction.ClickNode)
        assertEquals(null, (action as NodeAction.ClickNode).screenRect)
    }

    @Test
    fun `column four anchor becomes visible after camera offset on 1920 frame`() {
        val definition = NodeAnchorDefinitions.NODE_ICON_RECTS.first {
            it.column == 4 && it.row == 1
        }

        assertEquals(null, NodeAnchorDefinitions.getNodeIconRect(definition, 1920, 1080))

        val shifted = NodeAnchorDefinitions.getNodeIconRectAtOffset(
            def = definition,
            frameWidth = 1920,
            frameHeight = 1080,
            offsetX = -320,
            offsetY = 0,
        )

        assertEquals(EntryPixelRect(1580, 70, 280, 350), shifted)
    }

    @Test
    fun `two node column maps top to 0`() {
        assertEquals(0, resolveVisualIndexFromTop(40202, 2))
        assertEquals(1, resolveVisualIndexFromTop(40201, 2))
    }

    @Test
    fun `single node column maps to 0`() {
        assertEquals(0, resolveVisualIndexFromTop(50501, 1))
        assertEquals(0, resolveVisualIndexFromTop(30601, 1))
    }

    @Test
    fun `invalid nodeNumber throws clear exception`() {
        expectIllegalArgument { resolveVisualIndexFromTop(40304, 3) }
        expectIllegalArgument { resolveVisualIndexFromTop(20203, 2) }
        expectIllegalArgument { resolveVisualIndexFromTop(40300, 3) }
        (try {
            resolveVisualIndexFromTop(40304, 3)
            null
        } catch (e: IllegalArgumentException) {
            e.message
        })?.let { assertTrue(it.startsWith("Invalid nodeNumber=4")) }
    }

    // ---------- node type labels ----------

    @Test
    fun `labelOf uses real game meanings`() {
        assertEquals("起始点", LabyrinthNodeTypes.labelOf(1))
        assertEquals("普通战斗", LabyrinthNodeTypes.labelOf(2))
        assertEquals("EX战斗", LabyrinthNodeTypes.labelOf(3))
        assertEquals("连结", LabyrinthNodeTypes.labelOf(4))
        assertEquals("事件", LabyrinthNodeTypes.labelOf(5))
        assertEquals("遗物", LabyrinthNodeTypes.labelOf(6))
        assertEquals("商店", LabyrinthNodeTypes.labelOf(7))
        assertEquals("Boss", LabyrinthNodeTypes.labelOf(8))
        assertEquals("未知(9)", LabyrinthNodeTypes.labelOf(9))
    }

    @Test
    fun `shop and ex battle are clickable`() {
        assertTrue(LabyrinthNodeTypes.isClickable(3))
        assertTrue(LabyrinthNodeTypes.isClickable(7))
    }

    @Test
    fun `special map anchors keep boss and clear outside regular node grid`() {
        val specials = NodeAnchorDefinitions.visibleSpecialNodeRects(1920, 1080)

        assertEquals(setOf("boss", "boss.platform", "clear"), specials.map { it.id }.toSet())
        assertEquals(650, specials.first { it.id == "boss" }.rect.width)
        assertEquals(700, specials.first { it.id == "boss" }.rect.height)
        assertTrue(
            NodeAnchorDefinitions.specialSearchOffsets(
                specials.first { it.id == "boss.platform" },
                1920,
                1080,
            ).contains(300 to 0),
        )
        assertEquals(-1, LabyrinthNodeTypes.CLEAR)
        assertTrue(!LabyrinthNodeTypes.isClickable(LabyrinthNodeTypes.CLEAR))
    }

    @Test
    fun `final boss click lands inside observed nameplate`() {
        val rect = requireNotNull(finalBossPlatformClickRect(
            listOf(FinalBossPlatformMatch(EntryPixelRect(904, 598, 122, 46), 0.99)),
            1920, 1080, null,
        ))
        val (x, y) = LabyrinthNodeActionPlanner().getBaseClickPosition(rect)

        assertTrue(x in 955..975)
        assertTrue(y in 610..630)
        assertTrue(rect.left >= 900)
        assertTrue(rect.top >= 400)
        assertTrue(rect.left + rect.width <= 1400)
        assertTrue(rect.top + rect.height <= 750)
    }

    @Test
    fun `final boss resumed map uses observed platform not a fixed fallback`() {
        val rect = requireNotNull(
            finalBossPlatformClickRect(
                matches = listOf(FinalBossPlatformMatch(EntryPixelRect(1439, 598, 122, 46), 0.99)),
                frameWidth = 1920,
                frameHeight = 1080,
                predictedX = null,
            ),
        )
        val (x, y) = LabyrinthNodeActionPlanner().getBaseClickPosition(rect)

        assertEquals(1500, x)
        assertTrue(y in 610..630)
    }

    @Test
    fun `final boss topology validates but does not override actual label pixels`() {
        val rect = requireNotNull(
            finalBossPlatformClickRect(
                matches = listOf(FinalBossPlatformMatch(EntryPixelRect(1407, 598, 122, 46), 0.99)),
                frameWidth = 1920,
                frameHeight = 1080,
                predictedX = 1500.0,
            ),
        )
        val (x, y) = LabyrinthNodeActionPlanner().getBaseClickPosition(rect)

        assertEquals(1468, x)
        assertTrue(y in 610..630)
    }

    @Test
    fun `strong active final boss pixels override stale viewport prediction`() {
        val rect = requireNotNull(
            finalBossPlatformClickRect(
                matches = listOf(
                    FinalBossPlatformMatch(
                        labelRect = EntryPixelRect(900, 597, 122, 46),
                        confidence = 0.95,
                        colorScore = 0.68,
                        purpleActivationScore = 0.56,
                    ),
                ),
                frameWidth = 1920,
                frameHeight = 1080,
                predictedX = 1340.0,
            ),
        )
        val (x, y) = LabyrinthNodeActionPlanner().getBaseClickPosition(rect)

        assertTrue(x in 950..975)
        assertTrue(y in 610..630)
    }

    @Test
    fun `weak contradictory final boss pixels are still rejected`() {
        val rect = finalBossPlatformClickRect(
            matches = listOf(
                FinalBossPlatformMatch(
                    labelRect = EntryPixelRect(900, 597, 122, 46),
                    confidence = 0.80,
                    colorScore = 0.90,
                    purpleActivationScore = 0.10,
                ),
            ),
            frameWidth = 1920,
            frameHeight = 1080,
            predictedX = 1340.0,
        )

        assertEquals(null, rect)
    }

    // ---------- countNodesByColumn ----------

    @Test
    fun `countNodesByColumn derives counts from blockId tail digits`() {
        val nodes = listOf(
            node(40301, 2), node(40302, 2), node(40303, 3), // col 3 × 3
            node(40201, 6), node(40202, 4),                  // col 2 × 2
            node(40801, 7),                                  // col 8 × 1
        )
        val counts = countNodesByColumn(nodes)
        assertEquals(3, counts[NodeColumnKey(4, 3)])
        assertEquals(2, counts[NodeColumnKey(4, 2)])
        assertEquals(1, counts[NodeColumnKey(4, 8)])
        assertEquals(null, counts[NodeColumnKey(4, 1)])
    }

    // ---------- debug log format ----------

    @Test
    fun `debug log contains internal id and visual row`() {
        val log = formatNodeDebugLog(node(40303, 3), nodesInColumn = 3)
        assertTrue(log.contains("blockId=40303"))
        assertTrue(log.contains("area=4"))
        assertTrue(log.contains("column=3"))
        assertTrue(log.contains("nodeNumber=3"))
        assertTrue(log.contains("nodesInColumn=3"))
        assertTrue(log.contains("visualIndexFromTop=0"))
        assertTrue(log.contains("blockType=3"))
        assertTrue(log.contains("label=EX战斗"))
    }

    // ---------- mapNodes：视觉行匹配 ----------

    @Test
    fun `mapNodes matches visual top row to highest nodeNumber`() {
        val mapper = NodePositionMapper()
        val mappings = mapper.mapNodes(
            classifications = listOf(
                NodeClassification(column = 3, row = 1, blockType = 3, confidence = 0.9, isClickable = true),
                NodeClassification(column = 2, row = 2, blockType = 6, confidence = 0.9, isClickable = true),
                NodeClassification(column = 2, row = 1, blockType = 4, confidence = 0.9, isClickable = true),
            ),
            areaNodes = area4Nodes(),
            currentColumn = 0,
        )
        assertEquals(
            listOf(40303L, 40201L, 40202L),
            mappings.map { it.blockId },
        )
    }

    @Test
    fun `mapNodes maps bottom visual row to lowest nodeNumber`() {
        val mappings = NodePositionMapper().mapNodes(
            classifications = listOf(
                NodeClassification(column = 3, row = 3, blockType = 2, confidence = 0.9, isClickable = true),
            ),
            areaNodes = area4Nodes(),
            currentColumn = 0,
        )
        assertEquals(listOf(40301L), mappings.map { it.blockId })
    }

    @Test
    fun `mapNodes maps a shifted visual anchor to the next logical column`() {
        val visibleRect = com.landosol.toolbox.labyrinth.vision.EntryPixelRect(900, 350, 280, 350)
        val mappings = NodePositionMapper().mapNodes(
            classifications = listOf(
                // 逻辑第 4 列随地图平移后，出现在原第 2 列的视觉锚点。
                NodeClassification(
                    column = 2,
                    row = 2,
                    blockType = 5,
                    confidence = 0.93,
                    isClickable = true,
                    screenRect = visibleRect,
                ),
            ),
            areaNodes = area4Nodes(),
            currentColumn = 3,
        )

        assertEquals(listOf(40402L), mappings.map { it.blockId })
        assertEquals(visibleRect, mappings.single().screenRect)
    }

    @Test
    fun `mapNodes aligns the leftmost active visual column after the current logical column`() {
        val top = com.landosol.toolbox.labyrinth.vision.EntryPixelRect(500, 180, 180, 180)
        val bottom = com.landosol.toolbox.labyrinth.vision.EntryPixelRect(500, 390, 180, 180)
        val mappings = NodePositionMapper().mapNodes(
            classifications = listOf(
                NodeClassification(2, 1, 2, 0.91, true, top, "node.normal_battle.active"),
                NodeClassification(2, 2, 2, 0.92, true, bottom, "node.normal_battle.active.bottom"),
            ),
            areaNodes = listOf(node(10101, 1), node(10201, 2), node(10202, 2)),
            currentColumn = 1,
        )

        assertEquals(listOf(10202L, 10201L), mappings.map(NodePositionMapping::blockId))
        assertEquals(listOf(top, bottom), mappings.map(NodePositionMapping::screenRect))
    }

    @Test
    fun `mapNodes uses reachable active subset when full column has inactive siblings`() {
        val top = EntryPixelRect(500, 180, 180, 180)
        val bottom = EntryPixelRect(500, 390, 180, 180)
        val mappings = NodePositionMapper().mapNodes(
            classifications = listOf(
                NodeClassification(2, 1, 2, 0.91, true, top, "node.normal_battle.active"),
                NodeClassification(2, 2, 2, 0.92, true, bottom, "node.normal_battle.active.bottom"),
            ),
            areaNodes = listOf(
                node(10301, 4),
                node(10401, 2),
                node(10402, 2),
                node(10403, 2),
            ),
            currentColumn = 3,
            reachableNodeIds = setOf(10401L, 10402L),
        )

        // 10403 is the inactive top node. The two glowing nodes are 10402 (top) and 10401
        // (bottom), even though the full column contains three nodes.
        assertEquals(listOf(10402L, 10401L), mappings.map(NodePositionMapping::blockId))
        assertEquals(listOf(top, bottom), mappings.map(NodePositionMapping::screenRect))
    }

    @Test
    fun `mapNodes does not use a lone reachable sibling as the target`() {
        val targetRect = EntryPixelRect(1360, 650, 280, 350)
        val mappings = NodePositionMapper().mapNodes(
            classifications = listOf(
                // Stale link recognition from the previous visual column.
                NodeClassification(
                    column = 1,
                    row = 2,
                    blockType = LabyrinthNodeTypes.LINK,
                    confidence = 0.55,
                    isClickable = true,
                    screenRect = EntryPixelRect(302, 398, 280, 350),
                    templateId = "node.link.active.map",
                ),
                NodeClassification(
                    column = 3,
                    row = 1,
                    blockType = LabyrinthNodeTypes.NORMAL_BATTLE,
                    confidence = 0.78,
                    isClickable = false,
                    screenRect = EntryPixelRect(1360, 70, 280, 350),
                    templateId = "node.normal_battle.inactive",
                ),
                // The middle reachable battle was missed; the lower one is still visible.
                NodeClassification(
                    column = 3,
                    row = 2,
                    blockType = LabyrinthNodeTypes.NORMAL_BATTLE,
                    confidence = 0.69,
                    isClickable = true,
                    screenRect = targetRect,
                    templateId = "node.normal_battle.inactive.bottom",
                ),
            ),
            areaNodes = listOf(
                node(10301, LabyrinthNodeTypes.LINK),
                node(10401, LabyrinthNodeTypes.NORMAL_BATTLE),
                node(10402, LabyrinthNodeTypes.NORMAL_BATTLE),
                node(10403, LabyrinthNodeTypes.NORMAL_BATTLE),
            ),
            currentColumn = 3,
            reachableNodeIds = setOf(10401L, 10402L),
        )

        assertTrue(mappings.isEmpty())
    }

    @Test
    fun `mapNodes does not map an incomplete reachable column by full-column row`() {
        val mappings = NodePositionMapper().mapNodes(
            classifications = listOf(
                // This stale icon must not become the offset anchor for the target column.
                NodeClassification(
                    column = 1,
                    row = 2,
                    blockType = LabyrinthNodeTypes.LINK,
                    confidence = 0.55,
                    isClickable = true,
                    screenRect = EntryPixelRect(302, 398, 280, 350),
                    templateId = "node.link.active.map",
                ),
                NodeClassification(
                    column = 3,
                    row = 2,
                    blockType = LabyrinthNodeTypes.NORMAL_BATTLE,
                    confidence = 0.69,
                    isClickable = true,
                    screenRect = EntryPixelRect(1360, 650, 280, 350),
                    templateId = "node.normal_battle.inactive.bottom",
                ),
            ),
            areaNodes = listOf(
                node(10301, LabyrinthNodeTypes.LINK),
                node(10401, LabyrinthNodeTypes.NORMAL_BATTLE),
                node(10402, LabyrinthNodeTypes.NORMAL_BATTLE),
                node(10403, LabyrinthNodeTypes.NORMAL_BATTLE),
            ),
            currentColumn = 3,
            reachableNodeIds = setOf(10401L, 10402L),
        )

        assertTrue(mappings.isEmpty())
    }

    @Test
    fun `mapNodes keeps full-column topology mapping and reports visual type conflict`() {
        val middleRect = EntryPixelRect(1280, 322, 280, 350)
        val bottomRect = EntryPixelRect(1360, 650, 280, 350)
        val result = NodePositionMapper().mapNodesDetailed(
            classifications = listOf(
                NodeClassification(
                    column = 3,
                    row = 1,
                    blockType = LabyrinthNodeTypes.NORMAL_BATTLE,
                    confidence = 0.78,
                    isClickable = false,
                    screenRect = EntryPixelRect(1360, 70, 280, 350),
                    templateId = "node.normal_battle.inactive",
                ),
                NodeClassification(
                    column = 3,
                    row = 2,
                    blockType = LabyrinthNodeTypes.LINK,
                    confidence = 0.50,
                    isClickable = true,
                    screenRect = middleRect,
                    templateId = "node.link.active.map",
                ),
                NodeClassification(
                    column = 3,
                    row = 3,
                    blockType = LabyrinthNodeTypes.NORMAL_BATTLE,
                    confidence = 0.69,
                    isClickable = true,
                    screenRect = bottomRect,
                    templateId = "node.normal_battle.inactive.bottom",
                ),
            ),
            areaNodes = listOf(
                node(10301, LabyrinthNodeTypes.LINK),
                node(10401, LabyrinthNodeTypes.NORMAL_BATTLE),
                node(10402, LabyrinthNodeTypes.NORMAL_BATTLE),
                node(10403, LabyrinthNodeTypes.NORMAL_BATTLE),
            ),
            currentColumn = 3,
            reachableNodeIds = setOf(10401L, 10402L),
        )

        assertEquals(setOf(10401L, 10402L), result.mappings.mapTo(mutableSetOf(), NodePositionMapping::blockId))
        assertEquals(middleRect, result.mappings.single { it.blockId == 10402L }.screenRect)
        assertEquals(bottomRect, result.mappings.single { it.blockId == 10401L }.screenRect)
        assertEquals(1, result.conflicts.size)
        assertEquals(10402L, result.conflicts.single().blockId)
        assertEquals(LabyrinthNodeTypes.NORMAL_BATTLE, result.conflicts.single().expectedBlockType)
        assertEquals(LabyrinthNodeTypes.LINK, result.conflicts.single().detectedBlockType)
        assertEquals(middleRect, result.conflicts.single().screenRect)
        assertEquals(NodePositionConflictReason.TYPE_CONFLICT, result.conflicts.single().reason)
    }

    @Test
    fun `inactive nodes do not shift or enter the clickable route mapping`() {
        val activeRect = com.landosol.toolbox.labyrinth.vision.EntryPixelRect(900, 350, 180, 180)
        val mappings = NodePositionMapper().mapNodes(
            classifications = listOf(
                NodeClassification(2, 1, 2, 0.95, false, templateId = "node.normal_battle.inactive"),
                NodeClassification(3, 1, 4, 0.90, true, activeRect, "node.link.active"),
            ),
            areaNodes = listOf(node(10101, 1), node(10201, 4)),
            currentColumn = 1,
        )

        assertEquals(listOf(10201L), mappings.map(NodePositionMapping::blockId))
        assertEquals(activeRect, mappings.single().screenRect)
    }

    @Test
    fun `complete column topology keeps both rows and reports conflicting visual type`() {
        val top = EntryPixelRect(874, 118, 180, 225)
        val bottom = EntryPixelRect(823, 311, 180, 225)
        val result = NodePositionMapper().mapNodesDetailed(
            classifications = listOf(
                NodeClassification(3, 1, 2, 0.61, true, top, "node.normal_battle.inactive"),
                NodeClassification(3, 2, 5, 0.59, false, EntryPixelRect(669, 134, 180, 225)),
                NodeClassification(3, 3, 4, 0.49, true, bottom, "node.link.active.map"),
            ),
            areaNodes = listOf(node(10101, 1), node(10201, 2), node(10202, 2)),
            currentColumn = 1,
        )

        assertEquals(setOf(10201L, 10202L), result.mappings.mapTo(mutableSetOf(), NodePositionMapping::blockId))
        assertEquals(top, result.mappings.single { it.blockId == 10202L }.screenRect)
        assertEquals(bottom, result.mappings.single { it.blockId == 10201L }.screenRect)
        assertEquals(listOf(10201L), result.conflicts.map(NodePositionConflict::blockId))
        assertEquals(LabyrinthNodeTypes.LINK, result.conflicts.single().detectedBlockType)
        assertEquals(bottom, result.conflicts.single().screenRect)
    }

    @Test
    fun `unique node type repairs dirty visual row after popup transition`() {
        val targetRect = EntryPixelRect(1600, 510, 280, 350)
        val mappings = NodePositionMapper().mapNodes(
            classifications = listOf(
                NodeClassification(3, 1, 5, 0.68, false, templateId = "node.event.inactive"),
                // 残留候选把实际位于最下方的连结挤成了视觉第 2 行。
                NodeClassification(3, 2, 4, 0.64, true, targetRect, "node.link.active.map"),
                NodeClassification(3, 3, 5, 0.54, false, templateId = "node.event.inactive.bottom"),
            ),
            areaNodes = listOf(
                node(10101, 1),
                node(10201, 2),
                node(10301, 4),
                node(10302, 5),
                node(10303, 5),
            ),
            currentColumn = 2,
        )

        assertEquals(listOf(10301L), mappings.map(NodePositionMapping::blockId))
        assertEquals(targetRect, mappings.single().screenRect)
    }

    @Test
    fun `semantic repair leaves a crop topology already bound to another node`() {
        // 2026-09-20 bundle 020234. Route wanted link#30402. Topology read the column's rows and
        // bound the crop at (820,220) to event#30403 and the crop at (810,650) to event#30401,
        // which places 30402 in the undetected middle. The classifier called the top crop a link
        // at 0.664 against topology's 0.810 (logged as a TYPE_CONFLICT), semantic repair adopted
        // it because the route wanted a link, and the tap entered the event: "节点连结#30402
        // 进入页面不匹配：识别为EVENT_CHOICE".
        val topRect = EntryPixelRect(820, 220, 280, 350)
        val bottomRect = EntryPixelRect(810, 650, 280, 350)
        val mappings = NodePositionMapper().mapNodes(
            classifications = listOf(
                NodeClassification(4, 1, LabyrinthNodeTypes.LINK, 0.664, true, topRect, "node.link.inactive"),
                NodeClassification(4, 3, LabyrinthNodeTypes.EVENT, 0.658, false, bottomRect, "node.event.inactive.bottom"),
            ),
            areaNodes = listOf(
                node(30302, LabyrinthNodeTypes.RELIC),
                node(30401, LabyrinthNodeTypes.EVENT),
                node(30402, LabyrinthNodeTypes.LINK),
                node(30403, LabyrinthNodeTypes.EVENT),
            ),
            currentColumn = 3,
            reachableNodeIds = setOf(30402L, 30403L),
            preferredNodeId = 30402L,
        )

        val target = mappings.firstOrNull { it.blockId == 30402L }
        assertTrue(
            "the disputed crop must not be handed to the route target: $target",
            target?.screenRect != topRect,
        )
        // The crop keeps the owner topology gave it, so the run looks again instead of tapping.
        assertEquals(topRect, mappings.firstOrNull { it.blockId == 30403L }?.screenRect)
    }

    @Test
    fun `mapNodes maps the only highlighted link in a reachable mixed column`() {
        val targetRect = EntryPixelRect(1360, 350, 280, 350)
        val mappings = NodePositionMapper().mapNodes(
            classifications = listOf(
                // The selected route branch is the middle link. The two event nodes are
                // visible in the screenshot but are not reported as clickable by the glow gate.
                NodeClassification(
                    column = 3,
                    row = 2,
                    blockType = LabyrinthNodeTypes.LINK,
                    confidence = 0.90,
                    isClickable = true,
                    screenRect = targetRect,
                    templateId = "node.link.active.map",
                ),
            ),
            areaNodes = listOf(
                node(10402, LabyrinthNodeTypes.NORMAL_BATTLE),
                node(10501, LabyrinthNodeTypes.EVENT),
                node(10502, LabyrinthNodeTypes.LINK),
                node(10503, LabyrinthNodeTypes.EVENT),
            ),
            currentColumn = 4,
            reachableNodeIds = setOf(10501L, 10502L),
        )

        assertEquals(listOf(10502L), mappings.map(NodePositionMapping::blockId))
        assertEquals(targetRect, mappings.single().screenRect)
    }

    @Test
    fun `mapNodes keeps the explicit route target when adjacency omits it`() {
        val targetRect = EntryPixelRect(874, 45, 180, 225)
        val mappings = NodePositionMapper().mapNodes(
            classifications = listOf(
                NodeClassification(
                    column = 3,
                    row = 1,
                    blockType = LabyrinthNodeTypes.LINK,
                    confidence = 0.59,
                    isClickable = true,
                    screenRect = targetRect,
                    templateId = "node.link.inactive",
                ),
            ),
            areaNodes = listOf(
                node(20101, LabyrinthNodeTypes.START),
                node(20201, LabyrinthNodeTypes.EVENT),
                node(20202, LabyrinthNodeTypes.EVENT),
                node(20203, LabyrinthNodeTypes.LINK),
            ),
            currentColumn = 1,
            reachableNodeIds = setOf(20201L, 20202L),
            preferredNodeId = 20203L,
        )

        assertEquals(listOf(20203L), mappings.map(NodePositionMapping::blockId))
        assertEquals(targetRect, mappings.single().screenRect)
    }

    @Test
    fun `mapNodes keeps explicit target when adjacency is empty and stale prior column exists`() {
        val stalePreviousColumnRect = EntryPixelRect(580, 690, 280, 350)
        val targetRect = EntryPixelRect(1600, 462, 280, 350)
        val mappings = NodePositionMapper().mapNodes(
            classifications = listOf(
                // This is the stale active icon from the previous visual column. It must not be
                // mapped to the explicit battle target by visual row when the server adjacency is
                // empty.
                NodeClassification(
                    column = 2,
                    row = 2,
                    blockType = LabyrinthNodeTypes.LINK,
                    confidence = 0.42,
                    isClickable = true,
                    screenRect = stalePreviousColumnRect,
                    templateId = "node.link.active.map",
                ),
                NodeClassification(
                    column = 3,
                    row = 2,
                    blockType = LabyrinthNodeTypes.NORMAL_BATTLE,
                    confidence = 0.43,
                    isClickable = true,
                    screenRect = targetRect,
                    templateId = "node.normal_battle.active",
                ),
                NodeClassification(
                    column = 3,
                    row = 3,
                    blockType = LabyrinthNodeTypes.LINK,
                    confidence = 0.52,
                    isClickable = false,
                    screenRect = EntryPixelRect(1304, 678, 280, 350),
                    templateId = "node.link.active.map",
                ),
            ),
            areaNodes = listOf(
                node(20203, LabyrinthNodeTypes.LINK),
                node(20301, LabyrinthNodeTypes.LINK),
                node(20302, LabyrinthNodeTypes.LINK),
                node(20303, LabyrinthNodeTypes.NORMAL_BATTLE),
            ),
            currentColumn = 2,
            reachableNodeIds = emptySet(),
            preferredNodeId = 20303L,
        )

        assertEquals(listOf(20303L), mappings.map(NodePositionMapping::blockId))
        assertEquals(targetRect, mappings.single().screenRect)
    }

    @Test
    fun `mapNodes waits when the explicit route target glow is missed`() {
        val targetRect = EntryPixelRect(874, 45, 180, 225)
        val mappings = NodePositionMapper().mapNodes(
            classifications = listOf(
                NodeClassification(
                    column = 3,
                    row = 1,
                    blockType = LabyrinthNodeTypes.LINK,
                    confidence = 0.59,
                    isClickable = false,
                    screenRect = targetRect,
                    templateId = "node.link.active.map",
                ),
            ),
            areaNodes = listOf(
                node(20101, LabyrinthNodeTypes.START),
                node(20201, LabyrinthNodeTypes.EVENT),
                node(20202, LabyrinthNodeTypes.EVENT),
                node(20203, LabyrinthNodeTypes.LINK),
            ),
            currentColumn = 1,
            reachableNodeIds = setOf(20201L, 20202L),
            preferredNodeId = 20203L,
        )

        assertTrue(mappings.isEmpty())
    }

    @Test
    fun `active route crop wins over a higher scoring inactive shifted crop`() {
        val shiftedInactive = EntryPixelRect(1472, 70, 280, 350)
        val activeTarget = EntryPixelRect(1248, 14, 280, 350)
        val mappings = NodePositionMapper().mapNodes(
            classifications = listOf(
                NodeClassification(
                    column = 3,
                    row = 1,
                    blockType = LabyrinthNodeTypes.LINK,
                    confidence = 0.56,
                    isClickable = false,
                    screenRect = shiftedInactive,
                    templateId = "node.link.inactive",
                ),
                NodeClassification(
                    column = 3,
                    row = 1,
                    blockType = LabyrinthNodeTypes.LINK,
                    confidence = 0.47,
                    isClickable = true,
                    screenRect = activeTarget,
                    templateId = "node.link.active.map",
                ),
            ),
            areaNodes = listOf(
                node(20101, LabyrinthNodeTypes.START),
                node(20201, LabyrinthNodeTypes.EVENT),
                node(20202, LabyrinthNodeTypes.EVENT),
                node(20203, LabyrinthNodeTypes.LINK),
            ),
            currentColumn = 1,
            reachableNodeIds = setOf(20201L, 20202L),
            preferredNodeId = 20203L,
        )

        assertEquals(listOf(20203L), mappings.map(NodePositionMapping::blockId))
        assertEquals(activeTarget, mappings.single().screenRect)
        assertTrue(mappings.single().isClickable)
    }

    @Test
    fun `topology binds area2 relic by column geometry even when visual type is wrong`() {
        val relicRect = EntryPixelRect(1120, 120, 280, 350)
        val shopRect = EntryPixelRect(1120, 520, 280, 350)
        val topology = NodeTopologyMapper().map(
            classifications = listOf(
                // Current column: three battle slots. Their semantic labels are intentionally
                // unreliable; topology should only use their geometry/count.
                NodeClassification(2, 1, LabyrinthNodeTypes.LINK, 0.44, false, EntryPixelRect(650, 20, 280, 350)),
                NodeClassification(2, 2, LabyrinthNodeTypes.LINK, 0.48, false, EntryPixelRect(650, 330, 280, 350)),
                NodeClassification(2, 3, LabyrinthNodeTypes.LINK, 0.43, false, EntryPixelRect(650, 640, 280, 350)),
                // Next logical column has two nodes: top=20402 relic, bottom=20401 shop.
                // The relic is deliberately misclassified as LINK, matching the real failure.
                NodeClassification(3, 1, LabyrinthNodeTypes.LINK, 0.67, true, relicRect),
                NodeClassification(3, 2, LabyrinthNodeTypes.LINK, 0.51, false, shopRect),
            ),
            areaNodes = area2TopologyNodes(),
            currentColumn = 3,
            reachableNodeIds = setOf(20401L, 20402L),
            preferredNodeId = 20402L,
        )

        val relic = topology.mappings.single { it.blockId == 20402L }
        assertEquals(relicRect, relic.screenRect)
        assertEquals(LabyrinthNodeTypes.RELIC, relic.expectedBlockType)
        assertEquals(LabyrinthNodeTypes.LINK, relic.detectedBlockType)
        assertTrue(relic.topologyConfidence >= 0.70)
        assertEquals(4, topology.observedColumns.last().mappedLogicalColumn)
    }

    @Test
    fun `route target type repairs area3 column shift caused by a purple false column`() {
        val targetRect = EntryPixelRect(820, 650, 280, 350)
        val falseExRect = EntryPixelRect(1600, 390, 280, 350)
        val result = NodePositionMapper().mapNodesDetailed(
            classifications = listOf(
                NodeClassification(
                    column = 2,
                    row = 1,
                    blockType = LabyrinthNodeTypes.EVENT,
                    confidence = 0.56,
                    isClickable = true,
                    screenRect = targetRect,
                    templateId = "node.event.active.bottom",
                ),
                NodeClassification(
                    column = 3,
                    row = 1,
                    blockType = LabyrinthNodeTypes.LINK,
                    confidence = 0.97,
                    isClickable = false,
                    screenRect = EntryPixelRect(1360, 70, 280, 350),
                    templateId = "node.link.inactive",
                ),
                NodeClassification(
                    column = 3,
                    row = 2,
                    blockType = LabyrinthNodeTypes.EX_BATTLE,
                    confidence = 0.40,
                    isClickable = true,
                    screenRect = falseExRect,
                    templateId = "node.ex_battle.inactive",
                    purpleGlowScore = 0.53,
                ),
            ),
            areaNodes = listOf(
                node(30101, LabyrinthNodeTypes.START),
                node(30201, LabyrinthNodeTypes.NORMAL_BATTLE, next = listOf(30301)),
                node(30202, LabyrinthNodeTypes.NORMAL_BATTLE),
                node(30203, LabyrinthNodeTypes.NORMAL_BATTLE),
                node(30301, LabyrinthNodeTypes.EVENT),
                node(30302, LabyrinthNodeTypes.RELIC),
                node(30303, LabyrinthNodeTypes.EVENT),
                node(30401, LabyrinthNodeTypes.EVENT),
                node(30402, LabyrinthNodeTypes.EVENT),
                node(30403, LabyrinthNodeTypes.LINK),
            ),
            currentColumn = 2,
            reachableNodeIds = setOf(30301L),
            preferredNodeId = 30301L,
        )

        val target = result.mappings.single { it.blockId == 30301L }
        assertEquals(targetRect, target.screenRect)
        assertEquals(LabyrinthNodeTypes.EVENT, target.detectedBlockType)
        assertTrue(target.routeSemanticRepair)
        assertTrue(result.conflicts.none { it.blockId == 30301L })
        assertTrue(
            "result=$result",
            result.topologyObservedColumns.none { column ->
                column.centerX == 1740 && column.mappedLogicalColumn == 3
            },
        )
    }

    @Test
    fun `strong relic repairs a single target column shifted onto an inactive event false positive`() {
        val relicRect = EntryPixelRect(820, 350, 280, 350)
        val falseEventRect = EntryPixelRect(1360, 650, 280, 350)
        val current = node(
            10503,
            LabyrinthNodeTypes.LINK,
            next = listOf(10601L),
        )
        val result = NodePositionMapper().mapNodesDetailed(
            classifications = listOf(
                NodeClassification(
                    column = 2,
                    row = 1,
                    blockType = LabyrinthNodeTypes.RELIC,
                    confidence = 0.98,
                    isClickable = true,
                    screenRect = relicRect,
                    templateId = "node.relic.active",
                ),
                NodeClassification(
                    column = 3,
                    row = 1,
                    blockType = LabyrinthNodeTypes.EVENT,
                    confidence = 0.72,
                    isClickable = false,
                    screenRect = falseEventRect,
                    templateId = "node.event.inactive.bottom",
                ),
            ),
            areaNodes = listOf(
                node(10501, LabyrinthNodeTypes.EVENT),
                node(10502, LabyrinthNodeTypes.EVENT),
                current,
                node(10601, LabyrinthNodeTypes.RELIC),
            ),
            currentColumn = 5,
            reachableNodeIds = current.nextBlockIds.toSet(),
            preferredNodeId = 10601L,
        )

        val target = result.mappings.single { it.blockId == 10601L }
        assertEquals(relicRect, target.screenRect)
        assertEquals(LabyrinthNodeTypes.RELIC, target.detectedBlockType)
        assertTrue(target.routeSemanticRepair)
        assertTrue(result.conflicts.none { it.blockId == 10601L })
    }

    @Test
    fun `live area2 geometry keeps partial current column before the relic column`() {
        val currentRect = EntryPixelRect(876, 462, 280, 350)
        val relicRect = EntryPixelRect(1200, 70, 280, 350)
        val shopRect = EntryPixelRect(1200, 678, 280, 350)
        val topology = NodeTopologyMapper().map(
            classifications = listOf(
                // Live 20303 return-map frame: only one node of the current three-node column was
                // reliably detected. It must still occupy logical column 3, not column 4.
                NodeClassification(2, 1, LabyrinthNodeTypes.LINK, 0.52, true, currentRect),
                // Actual logical column 4: top relic + bottom shop, both visually misclassified.
                NodeClassification(3, 1, LabyrinthNodeTypes.LINK, 0.70, true, relicRect),
                NodeClassification(3, 3, LabyrinthNodeTypes.LINK, 0.47, true, shopRect),
                // A following partially visible column must not pull the alignment one step right.
                NodeClassification(3, 2, LabyrinthNodeTypes.LINK, 0.50, false, EntryPixelRect(1520, 70, 280, 350)),
                NodeClassification(3, 4, LabyrinthNodeTypes.LINK, 0.46, false, EntryPixelRect(1600, 678, 280, 350)),
            ),
            areaNodes = area2TopologyNodes() + listOf(
                node(20601, LabyrinthNodeTypes.LINK),
                node(20602, LabyrinthNodeTypes.EVENT),
                node(20603, LabyrinthNodeTypes.EVENT),
            ),
            currentColumn = 3,
            reachableNodeIds = setOf(20401L, 20402L),
            preferredNodeId = 20402L,
        )

        assertEquals(listOf(3, 4, 5), topology.observedColumns.map { it.mappedLogicalColumn })
        val relic = topology.mappings.single { it.blockId == 20402L }
        assertEquals(relicRect, relic.screenRect)
        assertEquals(NodeTopologyBindingKind.FULL_COLUMN, relic.bindingKind)
        assertTrue("topology=$topology relic=$relic", relic.topologyConfidence >= 0.90)
    }

    @Test
    fun `missing middle row binds bottom event 30401 without scrolling`() {
        val current = node(
            30301,
            LabyrinthNodeTypes.EVENT,
            next = listOf(30401L, 30402L),
        )
        val targetRect = EntryPixelRect(1332, 650, 280, 350)
        val result = NodePositionMapper().mapNodesDetailed(
            classifications = listOf(
                NodeClassification(
                    column = 3,
                    row = 1,
                    blockType = LabyrinthNodeTypes.LINK,
                    confidence = 0.69,
                    isClickable = false,
                    screenRect = EntryPixelRect(1360, 70, 280, 350),
                    templateId = "node.link.inactive",
                ),
                NodeClassification(
                    column = 3,
                    row = 2,
                    blockType = LabyrinthNodeTypes.EVENT,
                    confidence = 0.60,
                    isClickable = true,
                    screenRect = targetRect,
                    templateId = "node.event.active.bottom",
                    cyanGlowRowCoverage = 0.46,
                ),
            ),
            areaNodes = listOf(
                current,
                node(30401, LabyrinthNodeTypes.EVENT),
                node(30402, LabyrinthNodeTypes.EVENT),
                node(30403, LabyrinthNodeTypes.LINK),
            ),
            currentColumn = 3,
            reachableNodeIds = current.nextBlockIds.toSet(),
            preferredNodeId = 30401L,
        )

        val target = result.mappings.single { it.blockId == 30401L }
        assertEquals(targetRect, target.screenRect)
        assertEquals(NodeTopologyBindingKind.PARTIAL_COLUMN, target.topologyBindingKind)
        assertTrue(result.conflicts.none { it.blockId == 30401L })
        val action = LabyrinthNodeActionPlanner().planAction(
            NodeMatchResult(
                currentNodeId = current.blockId,
                currentNodeType = current.blockType,
                nextNode = node(30401, LabyrinthNodeTypes.EVENT),
                visibleNodes = result.mappings,
                conflicts = result.conflicts,
                isComplete = false,
            ),
        )
        assertTrue("result=$result action=$action", action is NodeAction.ClickNode)
        assertEquals(targetRect, (action as NodeAction.ClickNode).screenRect)
    }

    @Test
    fun `two reachable events stay on column four when inactive column five is also visible`() {
        val current = node(
            30301,
            LabyrinthNodeTypes.EVENT,
            next = listOf(30401L, 30402L),
        )
        val middleEventRect = EntryPixelRect(820, 350, 280, 350)
        val bottomEventRect = EntryPixelRect(792, 650, 280, 350)
        val classifications = listOf(
            NodeClassification(2, 1, LabyrinthNodeTypes.EVENT, 0.60, true, middleEventRect),
            NodeClassification(2, 2, LabyrinthNodeTypes.EVENT, 0.49, true, bottomEventRect),
            NodeClassification(
                3,
                1,
                LabyrinthNodeTypes.NORMAL_BATTLE,
                0.87,
                false,
                EntryPixelRect(1360, 70, 280, 350),
            ),
            NodeClassification(
                3,
                2,
                LabyrinthNodeTypes.EX_BATTLE,
                0.83,
                false,
                EntryPixelRect(1360, 350, 280, 350),
            ),
            NodeClassification(
                3,
                3,
                LabyrinthNodeTypes.NORMAL_BATTLE,
                0.71,
                false,
                EntryPixelRect(1360, 650, 280, 350),
            ),
        )
        val result = NodePositionMapper().mapNodesDetailed(
            classifications = classifications,
            areaNodes = listOf(
                current,
                node(30401, LabyrinthNodeTypes.EVENT),
                node(30402, LabyrinthNodeTypes.EVENT),
                node(30403, LabyrinthNodeTypes.LINK),
                node(30501, LabyrinthNodeTypes.NORMAL_BATTLE),
                node(30502, LabyrinthNodeTypes.EX_BATTLE),
                node(30503, LabyrinthNodeTypes.NORMAL_BATTLE),
            ),
            currentColumn = 3,
            reachableNodeIds = current.nextBlockIds.toSet(),
            preferredNodeId = 30401L,
        )

        assertEquals(listOf(4, 5), result.topologyObservedColumns.map { it.mappedLogicalColumn })
        val target = result.mappings.single { it.blockId == 30401L }
        assertEquals(bottomEventRect, target.screenRect)
        assertEquals(NodeTopologyBindingKind.REACHABLE_SUBSET, target.topologyBindingKind)
        assertTrue(result.conflicts.none { it.blockId == 30401L })
        val action = LabyrinthNodeActionPlanner().planAction(
            NodeMatchResult(
                currentNodeId = current.blockId,
                currentNodeType = current.blockType,
                nextNode = node(30401, LabyrinthNodeTypes.EVENT),
                visibleNodes = result.mappings,
                conflicts = result.conflicts,
                isComplete = false,
            ),
        )
        assertEquals(bottomEventRect, (action as NodeAction.ClickNode).screenRect)
    }

    @Test
    fun `shifted wrong-type bottom crop becomes conflict instead of scroll or click`() {
        val current = node(
            30301,
            LabyrinthNodeTypes.EVENT,
            next = listOf(30401L, 30402L),
        )
        val shiftedRect = EntryPixelRect(1332, 518, 280, 350)
        val result = NodePositionMapper().mapNodesDetailed(
            classifications = listOf(
                NodeClassification(
                    column = 3,
                    row = 1,
                    blockType = LabyrinthNodeTypes.LINK,
                    confidence = 0.69,
                    isClickable = false,
                    screenRect = EntryPixelRect(1360, 70, 280, 350),
                ),
                NodeClassification(
                    column = 3,
                    row = 2,
                    blockType = LabyrinthNodeTypes.LINK,
                    confidence = 0.62,
                    isClickable = true,
                    screenRect = shiftedRect,
                    templateId = "node.link.active.map",
                    cyanGlowRowCoverage = 0.37,
                ),
            ),
            areaNodes = listOf(
                current,
                node(30401, LabyrinthNodeTypes.EVENT),
                node(30402, LabyrinthNodeTypes.EVENT),
                node(30403, LabyrinthNodeTypes.LINK),
            ),
            currentColumn = 3,
            reachableNodeIds = current.nextBlockIds.toSet(),
            preferredNodeId = 30401L,
        )

        val conflict = result.conflicts.single { it.blockId == 30401L }
        assertEquals(shiftedRect, conflict.screenRect)
        assertEquals(NodeTopologyBindingKind.PARTIAL_COLUMN, result.mappings
            .single { it.blockId == 30401L }.topologyBindingKind)
        val action = LabyrinthNodeActionPlanner().planAction(
            NodeMatchResult(
                currentNodeId = current.blockId,
                currentNodeType = current.blockType,
                nextNode = node(30401, LabyrinthNodeTypes.EVENT),
                visibleNodes = result.mappings,
                conflicts = result.conflicts,
                isComplete = false,
            ),
        )
        assertTrue("result=$result action=$action", action is NodeAction.TypeConflict)
    }

    @Test
    fun `scattered uneven crops cannot gain full-column conflict override`() {
        val current = node(
            30301,
            LabyrinthNodeTypes.EVENT,
            next = listOf(30401L, 30402L),
        )
        val targetRect = EntryPixelRect(1520, 670, 280, 350)
        val result = NodePositionMapper().mapNodesDetailed(
            classifications = listOf(
                NodeClassification(3, 1, LabyrinthNodeTypes.LINK, 0.65, false, EntryPixelRect(1360, 70, 280, 350)),
                NodeClassification(3, 2, LabyrinthNodeTypes.EX_BATTLE, 0.41, true, EntryPixelRect(1416, 294, 280, 350)),
                NodeClassification(3, 3, LabyrinthNodeTypes.LINK, 0.44, true, targetRect),
            ),
            areaNodes = listOf(
                current,
                node(30401, LabyrinthNodeTypes.EVENT),
                node(30402, LabyrinthNodeTypes.EVENT),
                node(30403, LabyrinthNodeTypes.LINK),
            ),
            currentColumn = 3,
            reachableNodeIds = current.nextBlockIds.toSet(),
            preferredNodeId = 30401L,
        )

        assertTrue(result.topologyMappings.none {
            it.blockId == 30401L && it.bindingKind == NodeTopologyBindingKind.FULL_COLUMN
        })
        val action = LabyrinthNodeActionPlanner().planAction(
            NodeMatchResult(
                currentNodeId = current.blockId,
                currentNodeType = current.blockType,
                nextNode = node(30401, LabyrinthNodeTypes.EVENT),
                visibleNodes = result.mappings,
                conflicts = result.conflicts,
                isComplete = false,
            ),
        )
        assertTrue("result=$result action=$action", action is NodeAction.TypeConflict)
    }

    @Test
    fun `full column route target with missed glow is retained but never clicked`() {
        val topRect = EntryPixelRect(1216, 70, 280, 350)
        val middleRect = EntryPixelRect(1240, 360, 280, 350)
        val exBottomRect = EntryPixelRect(1264, 650, 280, 350)
        val current = node(
            20402,
            LabyrinthNodeTypes.RELIC,
            next = listOf(20501L, 20502L, 20503L),
        )
        val target = node(20501, LabyrinthNodeTypes.EX_BATTLE)
        val result = NodePositionMapper().mapNodesDetailed(
            classifications = listOf(
                // Live failure shape: all three nodes exist, but the bottom EX is visually
                // misclassified as LINK and its cyan-glow gate misses clickable=true.
                NodeClassification(3, 1, LabyrinthNodeTypes.LINK, 0.66, true, topRect, "node.link.active.map"),
                NodeClassification(3, 2, LabyrinthNodeTypes.LINK, 0.52, true, middleRect, "node.link.active.map"),
                NodeClassification(3, 3, LabyrinthNodeTypes.LINK, 0.44, false, exBottomRect, "node.link.active.map"),
            ),
            areaNodes = listOf(
                node(20401, LabyrinthNodeTypes.SHOP),
                current,
                target,
                node(20502, LabyrinthNodeTypes.NORMAL_BATTLE),
                node(20503, LabyrinthNodeTypes.NORMAL_BATTLE),
            ),
            currentColumn = 4,
            reachableNodeIds = current.nextBlockIds.toSet(),
            preferredNodeId = target.blockId,
        )

        val rawTopologyTarget = result.topologyMappings.single { it.blockId == 20501L }
        assertEquals(NodeTopologyBindingKind.FULL_COLUMN, rawTopologyTarget.bindingKind)
        assertFalse(rawTopologyTarget.isClickable)
        assertEquals(exBottomRect, rawTopologyTarget.screenRect)

        val actionableTarget = result.mappings.single { it.blockId == 20501L }
        assertFalse(actionableTarget.isClickable)
        assertTrue(actionableTarget.protocolPromoted)
        assertEquals(exBottomRect, actionableTarget.screenRect)
        assertTrue((actionableTarget.topologyConfidence ?: 0.0) >= 0.90)

        val action = LabyrinthNodeActionPlanner().planAction(
            NodeMatchResult(
                currentNodeId = current.blockId,
                currentNodeType = current.blockType,
                nextNode = target,
                visibleNodes = result.mappings,
                conflicts = result.conflicts,
                topologyMappings = result.topologyMappings,
                topologyAlignmentScore = result.topologyAlignmentScore,
                topologyObservedColumns = result.topologyObservedColumns,
                isComplete = false,
            ),
        )
        assertTrue("result=$result action=$action", action !is NodeAction.ClickNode)
    }

    @Test
    fun `same-type inactive protocol-promoted target waits for visual activation`() {
        val rect = EntryPixelRect(1264, 650, 280, 350)
        val target = node(20501, LabyrinthNodeTypes.NORMAL_BATTLE)
        val action = LabyrinthNodeActionPlanner().planAction(
            NodeMatchResult(
                currentNodeId = 20402L,
                currentNodeType = LabyrinthNodeTypes.RELIC,
                nextNode = target,
                visibleNodes = listOf(
                    NodePositionMapping(
                        blockId = target.blockId,
                        blockType = target.blockType,
                        column = target.column,
                        row = target.row,
                        confidence = 0.96,
                        screenRect = rect,
                        isClickable = false,
                        detectedBlockType = LabyrinthNodeTypes.NORMAL_BATTLE,
                        topologyConfidence = 0.96,
                        topologyBindingKind = NodeTopologyBindingKind.FULL_COLUMN,
                        protocolPromoted = true,
                    ),
                ),
                isComplete = false,
            ),
        )

        assertTrue("action=$action", action is NodeAction.WaitForLoad)
    }

    @Test
    fun `strong full column target is not replaced by matching type in adjacent column`() {
        val targetRect = EntryPixelRect(820, 90, 280, 350)
        val wrongRelicRect = EntryPixelRect(1360, 510, 280, 350)
        val current = node(
            10402,
            LabyrinthNodeTypes.NORMAL_BATTLE,
            next = listOf(10501L, 10502L, 10503L),
        )
        val target = node(10503, LabyrinthNodeTypes.LINK)
        val result = NodePositionMapper().mapNodesDetailed(
            classifications = listOf(
                // Live 2026-08-25 failure shape: the complete column-five target is visually
                // classified as RELIC while the single relic in column six is classified LINK.
                NodeClassification(2, 1, LabyrinthNodeTypes.RELIC, 0.54, true, targetRect),
                NodeClassification(2, 2, LabyrinthNodeTypes.EVENT, 0.46, true, EntryPixelRect(820, 350, 280, 350)),
                NodeClassification(2, 3, LabyrinthNodeTypes.EVENT, 0.53, true, EntryPixelRect(820, 650, 280, 350)),
                NodeClassification(3, 1, LabyrinthNodeTypes.LINK, 0.56, true, wrongRelicRect),
            ),
            areaNodes = listOf(
                current,
                node(10501, LabyrinthNodeTypes.EVENT),
                node(10502, LabyrinthNodeTypes.EVENT),
                target,
                node(10601, LabyrinthNodeTypes.RELIC),
            ),
            currentColumn = 4,
            reachableNodeIds = current.nextBlockIds.toSet(),
            preferredNodeId = target.blockId,
        )

        val targetMapping = result.mappings.single { it.blockId == target.blockId }
        assertEquals(targetRect, targetMapping.screenRect)
        assertEquals(NodeTopologyBindingKind.FULL_COLUMN, targetMapping.topologyBindingKind)
        assertTrue((targetMapping.topologyConfidence ?: 0.0) >= 0.90)
        assertFalse(targetMapping.routeSemanticRepair)
        assertTrue(result.conflicts.any {
            it.blockId == target.blockId &&
                it.expectedBlockType == LabyrinthNodeTypes.LINK &&
                it.detectedBlockType == LabyrinthNodeTypes.RELIC &&
                it.screenRect == targetRect
        })

        val action = LabyrinthNodeActionPlanner().planAction(
            NodeMatchResult(
                currentNodeId = current.blockId,
                currentNodeType = current.blockType,
                nextNode = target,
                visibleNodes = result.mappings,
                conflicts = result.conflicts,
                topologyMappings = result.topologyMappings,
                topologyAlignmentScore = result.topologyAlignmentScore,
                topologyObservedColumns = result.topologyObservedColumns,
                isComplete = false,
            ),
        )
        assertTrue("result=$result action=$action", action is NodeAction.ClickNode)
        assertEquals(targetRect, (action as NodeAction.ClickNode).screenRect)
    }

    @Test
    fun `ordered reachable subset target is not stolen by wrong semantic type in next column`() {
        val targetRect = EntryPixelRect(820, 90, 280, 350)
        val siblingRect = EntryPixelRect(820, 350, 280, 350)
        val wrongRelicRect = EntryPixelRect(1360, 510, 280, 350)
        val current = node(
            10402,
            LabyrinthNodeTypes.NORMAL_BATTLE,
            next = listOf(10503L, 10502L),
        )
        val target = node(10503, LabyrinthNodeTypes.LINK)
        val result = NodePositionMapper().mapNodesDetailed(
            classifications = listOf(
                // Live failure: only two reachable nodes of column five are visible. Their order
                // binds the top crop to 10503 even though the visual types of 10503/10601 swap.
                NodeClassification(2, 1, LabyrinthNodeTypes.RELIC, 0.546, true, targetRect),
                NodeClassification(2, 2, LabyrinthNodeTypes.EVENT, 0.457, true, siblingRect),
                NodeClassification(3, 1, LabyrinthNodeTypes.LINK, 0.556, true, wrongRelicRect),
            ),
            areaNodes = listOf(
                current,
                node(10501, LabyrinthNodeTypes.EVENT),
                node(10502, LabyrinthNodeTypes.EVENT),
                target,
                node(10601, LabyrinthNodeTypes.RELIC),
            ),
            currentColumn = 4,
            reachableNodeIds = current.nextBlockIds.toSet(),
            preferredNodeId = target.blockId,
        )

        val targetMapping = result.mappings.single { it.blockId == target.blockId }
        assertEquals(targetRect, targetMapping.screenRect)
        assertEquals(NodeTopologyBindingKind.REACHABLE_SUBSET, targetMapping.topologyBindingKind)
        assertFalse(targetMapping.routeSemanticRepair)
        assertTrue(result.conflicts.any { it.blockId == target.blockId && it.screenRect == targetRect })

        val action = LabyrinthNodeActionPlanner().planAction(
            NodeMatchResult(
                currentNodeId = current.blockId,
                currentNodeType = current.blockType,
                nextNode = target,
                visibleNodes = result.mappings,
                conflicts = result.conflicts,
                topologyMappings = result.topologyMappings,
                topologyAlignmentScore = result.topologyAlignmentScore,
                topologyObservedColumns = result.topologyObservedColumns,
                isComplete = false,
            ),
        )
        assertTrue("result=$result action=$action", action is NodeAction.ClickNode)
        assertEquals(targetRect, (action as NodeAction.ClickNode).screenRect)
    }

    @Test
    fun `position mapper keeps topology rect and conflict instead of treating target as offscreen`() {
        val relicRect = EntryPixelRect(1120, 120, 280, 350)
        val result = NodePositionMapper().mapNodesDetailed(
            classifications = listOf(
                NodeClassification(
                    3,
                    1,
                    LabyrinthNodeTypes.LINK,
                    0.67,
                    true,
                    relicRect,
                    purpleGlowScore = 0.13,
                ),
                NodeClassification(3, 2, LabyrinthNodeTypes.LINK, 0.51, false, EntryPixelRect(1120, 520, 280, 350)),
            ),
            areaNodes = area2TopologyNodes(),
            currentColumn = 3,
            reachableNodeIds = setOf(20401L, 20402L),
            preferredNodeId = 20402L,
        )

        assertTrue(result.topologyMappings.any { it.blockId == 20402L && it.screenRect == relicRect })
        assertTrue(result.mappings.any { it.blockId == 20402L && it.screenRect == relicRect })
        val conflict = result.conflicts.single { it.blockId == 20402L }
        assertEquals(LabyrinthNodeTypes.RELIC, conflict.expectedBlockType)
        assertEquals(LabyrinthNodeTypes.LINK, conflict.detectedBlockType)
        assertEquals(relicRect, conflict.screenRect)
        assertEquals(0.13, conflict.purpleGlowScore, 0.0001)
        assertEquals(0.13, result.mappings.single { it.blockId == 20402L }.purpleGlowScore, 0.0001)
    }

    @Test
    fun `mapNodes ignores a stale reachable-type icon from the previous visual column`() {
        val targetRect = EntryPixelRect(874, 136, 180, 225)
        val falseDuplicateRect = EntryPixelRect(1028, 332, 180, 225)
        val staleEventRect = EntryPixelRect(492, 453, 180, 225)
        val mappings = NodePositionMapper().mapNodes(
            classifications = listOf(
                // This event is from the old visual column. Its type is reachable, so type-only
                // filtering must not let it determine the camera offset.
                NodeClassification(2, 3, LabyrinthNodeTypes.EVENT, 0.45, true, staleEventRect),
                // The lower event is visually corrupted to LINK and produces a duplicate route
                // match. Keep the stronger, horizontally aligned middle-link detection once.
                NodeClassification(3, 1, LabyrinthNodeTypes.LINK, 0.72, true, targetRect),
                NodeClassification(3, 2, LabyrinthNodeTypes.LINK, 0.55, true, falseDuplicateRect),
            ),
            areaNodes = listOf(
                node(10402, LabyrinthNodeTypes.NORMAL_BATTLE),
                node(10501, LabyrinthNodeTypes.EVENT),
                node(10502, LabyrinthNodeTypes.LINK),
                node(10503, LabyrinthNodeTypes.EVENT),
            ),
            currentColumn = 4,
            reachableNodeIds = setOf(10501L, 10502L),
            preferredNodeId = 10502L,
        )

        assertEquals(listOf(10502L), mappings.map(NodePositionMapping::blockId))
        assertEquals(targetRect, mappings.single().screenRect)
    }

    @Test
    fun `dirty row is not mapped to a different node type when type is ambiguous`() {
        val mappings = NodePositionMapper().mapNodes(
            classifications = listOf(
                NodeClassification(3, 2, 4, 0.64, true, EntryPixelRect(1600, 510, 280, 350)),
            ),
            areaNodes = listOf(
                node(10101, 1),
                node(10201, 2),
                node(10301, 4),
                node(10302, 5),
                node(10303, 4),
            ),
            currentColumn = 2,
        )

        assertTrue(mappings.isEmpty())
    }

    @Test
    fun `regular node search excludes overlay edge and special templates`() {
        assertFalse(isInsideRegularNodeRecognitionRegion(EntryPixelRect(58, 219, 180, 225), 1234))
        assertTrue(isInsideRegularNodeRecognitionRegion(EntryPixelRect(823, 311, 180, 225), 1234))
        assertFalse(isRegularNodeTemplate("node.boss.platform"))
        assertFalse(isRegularNodeTemplate("node.clear"))
        assertTrue(isRegularNodeTemplate("node.normal_battle.active"))
    }

    @Test
    fun `inactive template is not clickable while active EX and boss remain supported`() {
        assertTrue(!isNodeTemplateClickable("node.normal_battle.inactive", LabyrinthNodeTypes.NORMAL_BATTLE))
        assertTrue(isNodeTemplateClickable("node.normal_battle.active", LabyrinthNodeTypes.NORMAL_BATTLE))
        assertTrue(isNodeTemplateClickable("node.ex_battle.inactive", LabyrinthNodeTypes.EX_BATTLE))
        assertTrue(isNodeTemplateClickable("node.boss.yellow", LabyrinthNodeTypes.BOSS))
    }

    // ---------- planner：点击位置按视觉行查找 ----------

    @Test
    fun `planner sends top node to top anchor rect`() {
        val action = planClick(
            nextNode = node(40303, 3),
            columnNodeCounts = mapOf(NodeColumnKey(4, 3) to 3),
        )
        val click = action as NodeAction.ClickNode
        assertEquals(2040, click.screenRect?.left) // 2780x1264 参考系列3锚点 x
        assertEquals(50, click.screenRect?.top)    // 视觉 row=1（最上方）
    }

    @Test
    fun `planner sends bottom node to bottom anchor rect`() {
        val action = planClick(
            nextNode = node(40301, 2),
            columnNodeCounts = mapOf(NodeColumnKey(4, 3) to 3),
        )
        val click = action as NodeAction.ClickNode
        assertEquals(2040, click.screenRect?.left)
        assertEquals(650, click.screenRect?.top) // 视觉 row=3（最下方）
    }

    @Test
    fun `planner scales node anchor for standard frame`() {
        val action = planClick(
            nextNode = node(40303, 3),
            columnNodeCounts = mapOf(NodeColumnKey(4, 3) to 3),
            frameWidth = 1920,
            frameHeight = 1080,
        )
        val click = action as NodeAction.ClickNode
        assertEquals(1360, click.screenRect?.left)
        assertEquals(70, click.screenRect?.top)
    }

    @Test
    fun `planner never synthesizes a click when the route node was not visually matched`() {
        val next = node(40402, 2)
        val action = LabyrinthNodeActionPlanner().planAction(
            NodeMatchResult(
                currentNodeId = 40301L,
                currentNodeType = 2,
                nextNode = next,
                visibleNodes = emptyList(),
                isComplete = false,
            ),
        ) as NodeAction.ClickNode

        assertEquals(40402L, action.blockId)
        assertEquals(null, action.screenRect)
    }

    @Test
    fun `planner chooses active mapping when the route target has duplicate crops`() {
        val inactive = EntryPixelRect(1472, 70, 280, 350)
        val active = EntryPixelRect(1248, 14, 280, 350)
        val next = node(20203, LabyrinthNodeTypes.LINK)
        val action = LabyrinthNodeActionPlanner().planAction(
            NodeMatchResult(
                currentNodeId = 20101L,
                currentNodeType = LabyrinthNodeTypes.START,
                nextNode = next,
                visibleNodes = listOf(
                    NodePositionMapping(
                        blockId = next.blockId,
                        blockType = next.blockType,
                        column = 2,
                        row = 1,
                        // Higher confidence must not make an inactive crop win.
                        confidence = 0.76,
                        screenRect = inactive,
                        isClickable = false,
                    ),
                    NodePositionMapping(
                        blockId = next.blockId,
                        blockType = next.blockType,
                        column = 2,
                        row = 1,
                        // Independently admissible, but still weaker than the inactive crop.
                        confidence = 0.67,
                        screenRect = active,
                        isClickable = true,
                    ),
                ),
                isComplete = false,
            ),
        ) as NodeAction.ClickNode

        assertEquals(active, action.screenRect)
    }

    @Test
    fun `planner allows high confidence full-column topology to override visual type conflict`() {
        val rect = EntryPixelRect(1120, 120, 280, 350)
        val target = node(20402, LabyrinthNodeTypes.RELIC)
        val action = LabyrinthNodeActionPlanner().planAction(
            NodeMatchResult(
                currentNodeId = 20303L,
                currentNodeType = LabyrinthNodeTypes.NORMAL_BATTLE,
                nextNode = target,
                visibleNodes = listOf(
                    NodePositionMapping(
                        blockId = 20402L,
                        blockType = LabyrinthNodeTypes.RELIC,
                        column = 4,
                        row = 2,
                        confidence = 0.96,
                        screenRect = rect,
                        isClickable = true,
                        detectedBlockType = LabyrinthNodeTypes.LINK,
                        topologyConfidence = 0.96,
                        topologyBindingKind = NodeTopologyBindingKind.FULL_COLUMN,
                    ),
                ),
                conflicts = listOf(
                    NodePositionConflict(
                        blockId = 20402L,
                        expectedBlockType = LabyrinthNodeTypes.RELIC,
                        detectedBlockType = LabyrinthNodeTypes.LINK,
                        visualColumn = 2,
                        visualRow = 1,
                        confidence = 0.96,
                        screenRect = rect,
                    ),
                ),
                isComplete = false,
            ),
        )

        assertTrue(action is NodeAction.ClickNode)
        assertEquals(rect, (action as NodeAction.ClickNode).screenRect)
    }

    @Test
    fun `planner keeps type conflict for reachable-subset topology`() {
        val rect = EntryPixelRect(1120, 120, 280, 350)
        val target = node(20402, LabyrinthNodeTypes.RELIC)
        val action = LabyrinthNodeActionPlanner().planAction(
            NodeMatchResult(
                currentNodeId = 20303L,
                currentNodeType = LabyrinthNodeTypes.NORMAL_BATTLE,
                nextNode = target,
                visibleNodes = listOf(
                    NodePositionMapping(
                        blockId = 20402L,
                        blockType = LabyrinthNodeTypes.RELIC,
                        column = 4,
                        row = 2,
                        confidence = 0.95,
                        screenRect = rect,
                        isClickable = true,
                        detectedBlockType = LabyrinthNodeTypes.LINK,
                        topologyConfidence = 0.95,
                        topologyBindingKind = NodeTopologyBindingKind.REACHABLE_SUBSET,
                    ),
                ),
                conflicts = listOf(
                    NodePositionConflict(
                        blockId = 20402L,
                        expectedBlockType = LabyrinthNodeTypes.RELIC,
                        detectedBlockType = LabyrinthNodeTypes.LINK,
                        visualColumn = 2,
                        visualRow = 1,
                        confidence = 0.95,
                        screenRect = rect,
                    ),
                ),
                isComplete = false,
            ),
        )

        assertTrue(action is NodeAction.TypeConflict)
    }

    @Test
    fun `planner allows link relic swap when reachable subset has ordered sibling evidence`() {
        val targetRect = EntryPixelRect(820, 90, 280, 350)
        val siblingRect = EntryPixelRect(820, 350, 280, 350)
        val target = node(10303, LabyrinthNodeTypes.LINK)
        val action = LabyrinthNodeActionPlanner().planAction(
            NodeMatchResult(
                currentNodeId = 10201L,
                currentNodeType = LabyrinthNodeTypes.NORMAL_BATTLE,
                nextNode = target,
                visibleNodes = listOf(
                    NodePositionMapping(
                        blockId = target.blockId,
                        blockType = target.blockType,
                        column = 3,
                        row = 1,
                        confidence = 0.72,
                        screenRect = targetRect,
                        isClickable = true,
                        detectedBlockType = LabyrinthNodeTypes.RELIC,
                        topologyConfidence = 0.82,
                        topologyBindingKind = NodeTopologyBindingKind.REACHABLE_SUBSET,
                    ),
                    NodePositionMapping(
                        blockId = 10302L,
                        blockType = LabyrinthNodeTypes.EVENT,
                        column = 3,
                        row = 2,
                        confidence = 0.80,
                        screenRect = siblingRect,
                        isClickable = true,
                        detectedBlockType = LabyrinthNodeTypes.EVENT,
                        topologyConfidence = 0.81,
                        topologyBindingKind = NodeTopologyBindingKind.REACHABLE_SUBSET,
                    ),
                ),
                conflicts = listOf(
                    NodePositionConflict(
                        blockId = target.blockId,
                        expectedBlockType = LabyrinthNodeTypes.LINK,
                        detectedBlockType = LabyrinthNodeTypes.RELIC,
                        visualColumn = 2,
                        visualRow = 1,
                        confidence = 0.72,
                        screenRect = targetRect,
                    ),
                ),
                isComplete = false,
            ),
        )

        assertTrue("action=$action", action is NodeAction.ClickNode)
        assertEquals(targetRect, (action as NodeAction.ClickNode).screenRect)
    }

    @Test
    fun `planner allows purple EX link conflict with reliable reachable topology`() {
        val rect = EntryPixelRect(1304, 670, 280, 350)
        val target = node(20501, LabyrinthNodeTypes.EX_BATTLE)
        val action = LabyrinthNodeActionPlanner().planAction(
            NodeMatchResult(
                currentNodeId = 20402L,
                currentNodeType = LabyrinthNodeTypes.RELIC,
                nextNode = target,
                visibleNodes = listOf(
                    NodePositionMapping(
                        blockId = target.blockId,
                        blockType = target.blockType,
                        column = 5,
                        row = 1,
                        confidence = 0.63,
                        screenRect = rect,
                        isClickable = true,
                        detectedBlockType = LabyrinthNodeTypes.LINK,
                        topologyConfidence = 0.84,
                        topologyBindingKind = NodeTopologyBindingKind.REACHABLE_SUBSET,
                        purpleGlowScore = 0.13,
                    ),
                ),
                conflicts = listOf(
                    NodePositionConflict(
                        blockId = target.blockId,
                        expectedBlockType = target.blockType,
                        detectedBlockType = LabyrinthNodeTypes.LINK,
                        visualColumn = 3,
                        visualRow = 3,
                        confidence = 0.84,
                        screenRect = rect,
                        purpleGlowScore = 0.13,
                    ),
                ),
                isComplete = false,
            ),
        )

        assertTrue(action is NodeAction.ClickNode)
        assertEquals(rect, (action as NodeAction.ClickNode).screenRect)
    }

    @Test
    fun `planner keeps purple EX conflict blocked below activation threshold`() {
        val rect = EntryPixelRect(1304, 670, 280, 350)
        val target = node(20501, LabyrinthNodeTypes.EX_BATTLE)
        val action = LabyrinthNodeActionPlanner().planAction(
            NodeMatchResult(
                currentNodeId = 20402L,
                currentNodeType = LabyrinthNodeTypes.RELIC,
                nextNode = target,
                visibleNodes = listOf(
                    NodePositionMapping(
                        blockId = target.blockId,
                        blockType = target.blockType,
                        column = 5,
                        row = 1,
                        confidence = 0.63,
                        screenRect = rect,
                        isClickable = true,
                        detectedBlockType = LabyrinthNodeTypes.LINK,
                        topologyConfidence = 0.84,
                        topologyBindingKind = NodeTopologyBindingKind.REACHABLE_SUBSET,
                        purpleGlowScore = 0.07,
                    ),
                ),
                conflicts = listOf(
                    NodePositionConflict(
                        blockId = target.blockId,
                        expectedBlockType = target.blockType,
                        detectedBlockType = LabyrinthNodeTypes.LINK,
                        visualColumn = 3,
                        visualRow = 3,
                        confidence = 0.84,
                        screenRect = rect,
                        purpleGlowScore = 0.07,
                    ),
                ),
                isComplete = false,
            ),
        )

        assertTrue(action is NodeAction.TypeConflict)
    }

    @Test
    fun `node click position targets the base instead of the icon center`() {
        val rect = EntryPixelRect(left = 1360, top = 350, width = 280, height = 350)

        assertEquals(Pair(1500, 462), LabyrinthNodeActionPlanner().getBaseClickPosition(rect))
        assertEquals(Pair(1500, 462), LabyrinthNodeActionPlanner().getClickPosition(rect))
        assertTrue(462 > rect.top)
        assertTrue(462 < rect.top + rect.height / 2)
    }

    @Test
    fun `bottom EX click stays above retreat controls`() {
        val rect = EntryPixelRect(left = 1304, top = 670, width = 280, height = 350)

        val click = LabyrinthNodeActionPlanner().getBaseClickPosition(rect)

        assertEquals(Pair(1444, 782), click)
        assertTrue(click.second < 930)
    }

    // ---------- 会话调试日志输出 ----------

    @Test
    fun `session logs node debug info via logger`() {
        val logs = mutableListOf<String>()
        val session = LabyrinthNodeSession(
            debugLogger = NodeDebugLogger { logs += it },
        )
        session.initialize(
            allNodes = area4Nodes(),
            route = area4Nodes().filter { it.blockId == 40101L || it.blockId == 40202L },
            nodeTemplates = NodeTemplateSet(emptyMap()),
        )

        session.processFrame(PixelImage(100, 100, IntArray(100 * 100)))

        val joined = logs.joinToString("\n")
        assertTrue(joined.contains("blockId=40202"))
        assertTrue(joined.contains("visualIndexFromTop=0"))
        assertTrue(joined.contains("label=连结"))
    }

    // ---------- helpers ----------

    private fun expectIllegalArgument(block: () -> Unit) {
        try {
            block()
            fail("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            // expected
        }
    }

    private fun planClick(
        nextNode: LabyrinthMapNode,
        columnNodeCounts: Map<NodeColumnKey, Int>,
        frameWidth: Int = 2780,
        frameHeight: Int = 1264,
    ): NodeAction {
        val nodesInColumn = columnNodeCounts[NodeColumnKey(nextNode.area, nextNode.column)] ?: 1
        val visualRow = resolveVisualIndexFromTop(nextNode.blockId.toInt(), nodesInColumn) + 1
        val definition = NodeAnchorDefinitions.NODE_ICON_RECTS.first {
            it.column == nextNode.column && it.row == visualRow
        }
        val screenRect = NodeAnchorDefinitions.getNodeIconRect(definition, frameWidth, frameHeight)
        val result = NodeMatchResult(
            currentNodeId = 40101L,
            currentNodeType = 1,
            nextNode = nextNode,
            visibleNodes = listOf(
                NodePositionMapping(
                    blockId = nextNode.blockId,
                    blockType = nextNode.blockType,
                    column = nextNode.column,
                    row = nextNode.row,
                    confidence = 0.95,
                    screenRect = screenRect,
                    isClickable = true,
                ),
            ),
            isComplete = false,
            columnNodeCounts = columnNodeCounts,
        )
        return LabyrinthNodeActionPlanner().planAction(result)
    }

    private fun area4Nodes(): List<LabyrinthMapNode> = listOf(
        node(40101, 1),
        node(40201, 6, next = listOf(40301, 40302)),
        node(40202, 4, next = listOf(40302, 40303)),
        node(40301, 2, next = listOf(40401, 40402)),
        node(40302, 2, next = listOf(40402)),
        node(40303, 3, next = listOf(40402, 40403)),
        node(40401, 5, next = listOf(40501, 40502)),
        node(40402, 5, next = listOf(40502, 40503)),
        node(40403, 5, next = listOf(40503)),
        node(40501, 2, next = listOf(40601)),
        node(40502, 3, next = listOf(40601, 40602)),
        node(40503, 2, next = listOf(40602, 40603)),
        node(40601, 2, next = listOf(40701)),
        node(40602, 2, next = listOf(40701)),
        node(40603, 2, next = listOf(40701, 40702)),
        node(40701, 4, next = listOf(40801)),
        node(40702, 6, next = listOf(40801)),
        node(40801, 7),
    )

    private fun area2TopologyNodes(): List<LabyrinthMapNode> = listOf(
        node(20201, LabyrinthNodeTypes.EVENT),
        node(20202, LabyrinthNodeTypes.EVENT),
        node(20203, LabyrinthNodeTypes.LINK),
        node(20301, LabyrinthNodeTypes.NORMAL_BATTLE),
        node(20302, LabyrinthNodeTypes.NORMAL_BATTLE),
        node(20303, LabyrinthNodeTypes.NORMAL_BATTLE, next = listOf(20401, 20402)),
        node(20401, LabyrinthNodeTypes.SHOP),
        node(20402, LabyrinthNodeTypes.RELIC, next = listOf(20501, 20502, 20503)),
        node(20501, LabyrinthNodeTypes.NORMAL_BATTLE),
        node(20502, LabyrinthNodeTypes.EX_BATTLE),
        node(20503, LabyrinthNodeTypes.NORMAL_BATTLE),
    )

    private fun node(blockId: Long, type: Int, next: List<Long> = emptyList()): LabyrinthMapNode {
        val area = (blockId / 10_000).toInt()
        val column = (blockId / 100 % 100).toInt()
        val nodeNumber = (blockId % 100).toInt()
        return LabyrinthMapNode(
            area = area,
            column = column,
            row = nodeNumber,
            blockId = blockId,
            blockType = type,
            questId = null,
            nextBlockIds = next,
            isAreaLastPoint = false,
        )
    }
}
