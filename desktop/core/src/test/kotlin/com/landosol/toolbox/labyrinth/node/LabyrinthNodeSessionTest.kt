package com.landosol.toolbox.labyrinth.node

import com.landosol.toolbox.protocol.labyrinth.LabyrinthMapNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LabyrinthNodeSessionTest {

    private fun node(
        area: Int,
        column: Int,
        row: Int,
        blockId: Long,
        blockType: Int = LabyrinthNodeTypes.NORMAL_BATTLE,
        nextBlockIds: List<Long> = emptyList(),
        isAreaLastPoint: Boolean = false,
    ) = LabyrinthMapNode(
        area = area,
        column = column,
        row = row,
        blockId = blockId,
        blockType = blockType,
        questId = null,
        nextBlockIds = nextBlockIds,
        isAreaLastPoint = isAreaLastPoint,
    )

    private fun sessionWith(
        allNodes: List<LabyrinthMapNode>,
        route: List<LabyrinthMapNode>,
        currentNodeId: Long? = null,
    ): LabyrinthNodeSession = LabyrinthNodeSession().apply {
        initialize(
            allNodes = allNodes,
            route = route,
            nodeTemplates = NodeTemplateSet(emptyMap()),
            currentNodeId = currentNodeId,
        )
    }

    @Test
    fun `initial frame treats the start platform as current and plans the first selectable node`() {
        val start = node(area = 1, column = 1, row = 1, blockId = 101, blockType = LabyrinthNodeTypes.START)
        val second = node(area = 1, column = 2, row = 1, blockId = 102)
        val session = sessionWith(allNodes = listOf(start, second), route = listOf(start, second))

        val action = session.processClassifications(emptyList())

        assertTrue(action is NodeAction.ClickNode)
        assertEquals(102L, (action as NodeAction.ClickNode).blockId)
        assertEquals(listOf(101L), session.getState().visitedNodes)
    }

    @Test
    fun `afterClick advances along the route`() {
        val start = node(area = 1, column = 1, row = 1, blockId = 101, blockType = LabyrinthNodeTypes.START)
        val second = node(area = 1, column = 2, row = 1, blockId = 102)
        val session = sessionWith(allNodes = listOf(start, second), route = listOf(start, second))

        assertEquals(101L, session.getState().currentNodeId)
        assertEquals(listOf(101L), session.getState().visitedNodes)
        val action = session.processClassifications(emptyList())
        assertTrue(action is NodeAction.ClickNode)
        assertEquals(102L, (action as NodeAction.ClickNode).blockId)
    }

    @Test
    fun `clicking the area last point moves the session to the next area`() {
        val start = node(area = 1, column = 1, row = 1, blockId = 101, blockType = LabyrinthNodeTypes.START)
        val last = node(area = 1, column = 2, row = 1, blockId = 102, isAreaLastPoint = true)
        val area2Start = node(area = 2, column = 1, row = 1, blockId = 201, blockType = LabyrinthNodeTypes.START)
        val area2Next = node(area = 2, column = 2, row = 1, blockId = 202)
        val session = sessionWith(
            allNodes = listOf(start, last, area2Start, area2Next),
            route = listOf(start, last, area2Start, area2Next),
        )

        session.afterClick(102)

        val state = session.getState()
        assertEquals(2, state.currentArea)
        assertEquals(201L, state.currentNodeId)
        assertEquals(listOf(101L, 102L, 201L), state.visitedNodes)
        assertTrue(!state.isComplete)

        val action = session.processClassifications(emptyList())
        assertTrue(action is NodeAction.ClickNode)
        assertEquals(202L, (action as NodeAction.ClickNode).blockId)
    }

    @Test
    fun `resumed session continues after the current block`() {
        val start = node(area = 1, column = 1, row = 1, blockId = 101, blockType = LabyrinthNodeTypes.START)
        val current = node(area = 1, column = 2, row = 1, blockId = 102)
        val next = node(area = 1, column = 3, row = 1, blockId = 103)
        val route = listOf(start, current, next)
        val session = sessionWith(allNodes = route, route = route, currentNodeId = 102)

        val action = session.processClassifications(emptyList())

        assertTrue(action is NodeAction.ClickNode)
        assertEquals(103L, (action as NodeAction.ClickNode).blockId)
        assertEquals(listOf(101L, 102L), session.getState().visitedNodes)
    }

    @Test
    fun `resumed session crosses area when route boundary flag is missing`() {
        val area1Start = node(
            area = 1,
            column = 1,
            row = 1,
            blockId = 10101,
            blockType = LabyrinthNodeTypes.START,
        )
        val area1Last = node(area = 1, column = 6, row = 1, blockId = 10601)
        val area2Start = node(
            area = 2,
            column = 1,
            row = 1,
            blockId = 20101,
            blockType = LabyrinthNodeTypes.START,
        )
        val area2Target = node(area = 2, column = 2, row = 3, blockId = 20203)
        val route = listOf(area1Start, area1Last, area2Start, area2Target)
        val session = sessionWith(
            allNodes = route,
            route = route,
            currentNodeId = area1Last.blockId,
        )

        val state = session.getState()
        assertEquals(2, state.currentArea)
        assertEquals(area2Start.blockId, state.currentNodeId)
        assertEquals(
            listOf(area1Start.blockId, area1Last.blockId, area2Start.blockId),
            state.visitedNodes,
        )

        val action = session.processClassifications(emptyList())
        assertTrue(action is NodeAction.ClickNode)
        assertEquals(area2Target.blockId, (action as NodeAction.ClickNode).blockId)
    }

    @Test
    fun `clicking the final area last point completes the session`() {
        val start = node(area = 1, column = 1, row = 1, blockId = 101, blockType = LabyrinthNodeTypes.START)
        val last = node(area = 1, column = 2, row = 1, blockId = 102, isAreaLastPoint = true)
        val session = sessionWith(allNodes = listOf(start, last), route = listOf(start, last))

        session.afterClick(102)

        assertTrue(session.getState().isComplete)
        val action = session.processClassifications(emptyList())
        assertTrue(action is NodeAction.Complete)
    }

    @Test
    fun `matchClassified skips the start anchor when current node is unknown`() {
        val start = node(area = 1, column = 1, row = 1, blockId = 101, blockType = LabyrinthNodeTypes.START)
        val second = node(area = 1, column = 2, row = 1, blockId = 102)
        val matcher = LabyrinthNodeMatcher()

        val result = matcher.matchClassified(
            classifications = emptyList(),
            allMapNodes = listOf(start, second),
            routeNodes = listOf(start, second),
            currentArea = 1,
            currentNodeId = null,
        )

        assertEquals(102L, result.nextNode?.blockId)
        assertEquals(LabyrinthNodeTypes.START, result.currentNodeType)
    }

    @Test
    fun `movement confirmation can recover only a unique reachable saved-route successor`() {
        val current = node(
            area = 4,
            column = 5,
            row = 1,
            blockId = 40501,
            nextBlockIds = listOf(40601),
        )
        val target = node(
            area = 4,
            column = 6,
            row = 1,
            blockId = 40601,
            blockType = LabyrinthNodeTypes.EX_BATTLE,
        )
        val session = sessionWith(
            allNodes = listOf(current, target),
            route = listOf(current, target),
            currentNodeId = current.blockId,
        )

        assertEquals(target, session.uniqueReachableRouteTarget())
    }

    @Test
    fun `movement confirmation recovery refuses ambiguous reachable branches`() {
        val current = node(
            area = 4,
            column = 5,
            row = 1,
            blockId = 40501,
            nextBlockIds = listOf(40601, 40602),
        )
        val target = node(
            area = 4,
            column = 6,
            row = 1,
            blockId = 40601,
            blockType = LabyrinthNodeTypes.EX_BATTLE,
        )
        val alternate = node(
            area = 4,
            column = 6,
            row = 2,
            blockId = 40602,
            blockType = LabyrinthNodeTypes.EVENT,
        )
        val session = sessionWith(
            allNodes = listOf(current, target, alternate),
            route = listOf(current, target),
            currentNodeId = current.blockId,
        )

        assertEquals(null, session.uniqueReachableRouteTarget())
    }
}
