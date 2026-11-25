package com.landosol.toolbox.labyrinth

import com.landosol.toolbox.protocol.labyrinth.LabyrinthMapNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LabyrinthRouteSearcherTest {
    private val nodes = listOf(
        node(1, 1, listOf(2, 3)),
        node(2, 1, listOf(4)),
        node(3, 1, listOf(4)),
        node(4, 2, listOf(5)),
        node(5, 2, emptyList()),
    )

    @Test
    fun `finds deterministic route to goal`() {
        val result = LabyrinthRouteSearcher().search(
            enterId = 77,
            nodes = nodes,
            constraints = RouteConstraints(maxNodes = 5, isGoal = { it.blockId == 5L }),
        )

        assertTrue(result is RouteSearchResult.Found)
        assertEquals(listOf(1L, 2L, 4L, 5L), (result as RouteSearchResult.Found).route.blockIds)
    }

    @Test
    fun `backtracks when first route violates third slot constraint`() {
        val result = LabyrinthRouteSearcher().search(
            enterId = 77,
            nodes = nodes,
            constraints = RouteConstraints(
                maxNodes = 5,
                isGoal = { it.blockId == 5L },
                acceptRoute = { route -> route.any { it.blockId == 3L } },
            ),
        )

        assertTrue(result is RouteSearchResult.Found)
        assertEquals(listOf(1L, 3L, 4L, 5L), (result as RouteSearchResult.Found).route.blockIds)
    }

    @Test
    fun `does not loop on cyclic map`() {
        val cyclic = listOf(node(1, 1, listOf(2)), node(2, 1, listOf(1)))

        val result = LabyrinthRouteSearcher().search(
            enterId = 77,
            nodes = cyclic,
            constraints = RouteConstraints(maxNodes = 10, isGoal = { it.blockId == 99L }),
        )

        assertEquals(RouteSearchResult.NoRoute, result)
    }

    @Test
    fun `explicit start predicate does not accept disconnected later column`() {
        val disconnected = listOf(
            node(1, 1, emptyList()),
            node(2, 1, listOf(3)),
            node(3, 1, emptyList()).copy(isAreaLastPoint = true),
        )

        val result = LabyrinthRouteSearcher().search(
            enterId = 77,
            nodes = disconnected,
            constraints = RouteConstraints(
                maxNodes = 3,
                isGoal = LabyrinthMapNode::isAreaLastPoint,
                startPredicate = { it.column == 1 },
            ),
        )

        assertEquals(RouteSearchResult.NoRoute, result)
    }

    @Test
    fun `route acceptance callback backtracks to matching typed route`() {
        val typedNodes = listOf(
            node(1, 1, listOf(2, 3)).copy(blockType = 1),
            node(2, 1, listOf(4)).copy(blockType = 9),
            node(3, 1, listOf(4)).copy(blockType = 2),
            node(4, 2, listOf(5)).copy(blockType = 3),
            node(5, 2, emptyList()).copy(blockType = 4, isAreaLastPoint = true),
        )

        val result = LabyrinthRouteSearcher().search(
            enterId = 77,
            nodes = typedNodes,
            constraints = RouteConstraints(
                maxNodes = 5,
                isGoal = LabyrinthMapNode::isAreaLastPoint,
                acceptRoute = { route -> route.any { it.blockType == 2 } && route.none { it.blockType == 9 } },
            ),
        )

        assertTrue(result is RouteSearchResult.Found)
        assertEquals(listOf(1L, 3L, 4L, 5L), (result as RouteSearchResult.Found).route.blockIds)
    }

    private fun node(id: Long, area: Int, next: List<Long>) = LabyrinthMapNode(
        area = area,
        column = id.toInt(),
        row = 0,
        blockId = id,
        blockType = 0,
        questId = null,
        nextBlockIds = next,
        isAreaLastPoint = false,
    )
}
