package com.landosol.toolbox.labyrinth.node

import com.landosol.toolbox.protocol.labyrinth.LabyrinthMapNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LabyrinthNodeSessionFinalBossTest {
    @Test
    fun `area three final boss is route authoritative even without visual classification`() {
        val session = LabyrinthNodeSession()
        val route = listOf(
            node(30601, 3, 6, LabyrinthNodeTypes.SHOP),
            node(30701, 3, 7, LabyrinthNodeTypes.BOSS),
            node(40101, 4, 1, LabyrinthNodeTypes.START),
        )
        session.initialize(route, route, NodeTemplateSet(emptyMap()), currentNodeId = 30601)

        assertEquals(30701L, session.directFinalBossTarget()?.blockId)
    }

    @Test
    fun `area five route ending boss is direct target`() {
        val session = LabyrinthNodeSession()
        val route = listOf(
            node(50601, 5, 6, LabyrinthNodeTypes.RELIC),
            node(50701, 5, 7, LabyrinthNodeTypes.BOSS),
        )
        session.initialize(route, route, NodeTemplateSet(emptyMap()), currentNodeId = 50601)

        assertEquals(50701L, session.directFinalBossTarget()?.blockId)
    }

    @Test
    fun `non final or non target boss never uses direct shortcut`() {
        val session = LabyrinthNodeSession()
        val route = listOf(
            node(30401, 3, 4, LabyrinthNodeTypes.EVENT),
            node(30501, 3, 5, LabyrinthNodeTypes.BOSS),
            node(30601, 3, 6, LabyrinthNodeTypes.SHOP),
        )
        session.initialize(route, route, NodeTemplateSet(emptyMap()), currentNodeId = 30401)
        assertNull(session.directFinalBossTarget())

        val area2 = LabyrinthNodeSession()
        val area2Route = listOf(
            node(20601, 2, 6, LabyrinthNodeTypes.SHOP),
            node(20701, 2, 7, LabyrinthNodeTypes.BOSS),
        )
        area2.initialize(area2Route, area2Route, NodeTemplateSet(emptyMap()), currentNodeId = 20601)
        assertNull(area2.directFinalBossTarget())
    }

    @Test
    fun `incomplete area three route cannot prove final boss`() {
        val session = LabyrinthNodeSession()
        val route = listOf(node(30601, 3, 6, LabyrinthNodeTypes.SHOP),
            node(30701, 3, 7, LabyrinthNodeTypes.BOSS))
        session.initialize(route, route, NodeTemplateSet(emptyMap()), currentNodeId = 30601)
        assertNull(session.directFinalBossTarget())
    }

    @Test
    fun `boss must be adjacent and next area must not be skipped`() {
        for (route in listOf(
            listOf(node(30501, 3, 5, LabyrinthNodeTypes.SHOP), node(30701, 3, 7, LabyrinthNodeTypes.BOSS),
                node(40101, 4, 1, LabyrinthNodeTypes.START)),
            listOf(node(30601, 3, 6, LabyrinthNodeTypes.SHOP), node(30701, 3, 7, LabyrinthNodeTypes.BOSS),
                node(50101, 5, 1, LabyrinthNodeTypes.START)),
        )) {
            val session = LabyrinthNodeSession()
            session.initialize(route, route, NodeTemplateSet(emptyMap()), currentNodeId = route.first().blockId)
            assertNull(session.directFinalBossTarget())
        }
    }

    private fun node(
        blockId: Long,
        area: Int,
        column: Int,
        type: Int,
    ) = LabyrinthMapNode(
        area = area,
        column = column,
        row = 1,
        blockId = blockId,
        blockType = type,
        questId = null,
        nextBlockIds = emptyList(),
        isAreaLastPoint = false,
    )
}
