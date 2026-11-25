package com.landosol.toolbox.labyrinth

import com.landosol.toolbox.labyrinth.node.LabyrinthNodeTypes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LabyrinthRouteProgressTest {
    @Test
    fun `advances saved cursor to a later selected route node`() {
        val route = route(currentBlockId = 20601L)

        val advanced = route.withAdvancedCurrentBlock(30301L)

        assertEquals(30301L, advanced?.currentBlockId)
    }

    @Test
    fun `rejects moving saved cursor backwards`() {
        val route = route(currentBlockId = 30301L)

        assertNull(route.withAdvancedCurrentBlock(20601L))
    }

    @Test
    fun `rejects a cursor outside the selected route`() {
        val route = route(currentBlockId = 20601L)

        assertNull(route.withAdvancedCurrentBlock(99999L))
    }

    @Test
    fun `next area start cursor still resolves the just entered area boss`() {
        val boss = node(
            30701L,
            area = 3,
            column = 7,
            blockType = LabyrinthNodeTypes.BOSS,
        ).copy(questId = 770331801)
        val nextAreaStart = node(
            40101L,
            area = 4,
            column = 1,
            blockType = LabyrinthNodeTypes.START,
        )
        val base = route(currentBlockId = 30401L)
        val persisted = base.copy(
            nodes = base.nodes + boss + nextAreaStart,
            currentBlockId = 40101L,
        )

        val resolved = requireNotNull(labyrinthPersistedBossNode(persisted))
        assertEquals(30701L, resolved.blockId)
        assertEquals(319604, LabyrinthBossCatalog.unitIdForQuest(resolved.area, resolved.questId))
        assertEquals("boss_frost_wolf", labyrinthBossEncounterStrategy(persisted)?.id)
    }

    @Test
    fun `persisted current event node survives app restart as semantic event evidence`() {
        val persisted = route(currentBlockId = 30301L)

        val resolved = requireNotNull(labyrinthPersistedCurrentNode(persisted))

        assertEquals(30301L, resolved.blockId)
        assertEquals(LabyrinthNodeTypes.EVENT, resolved.blockType)
        assertEquals(3, resolved.area)
    }

    private fun route(currentBlockId: Long?) = LabyrinthRouteJson(
        enterId = 10698L,
        guildId = 101,
        difficulty = 2,
        attempt = 1,
        rerollUntilFound = true,
        perfectStart = false,
        thirdBlockChoice = "ANY",
        area3BossIds = emptyList(),
        area5BossIds = emptyList(),
        nodes = listOf(
            node(20601L, area = 2, column = 6, blockType = LabyrinthNodeTypes.LINK),
            node(20701L, area = 2, column = 7, blockType = LabyrinthNodeTypes.RELIC),
            node(30101L, area = 3, column = 1, blockType = LabyrinthNodeTypes.START),
            node(30201L, area = 3, column = 2, blockType = LabyrinthNodeTypes.NORMAL_BATTLE),
            node(30301L, area = 3, column = 3, blockType = LabyrinthNodeTypes.EVENT),
            node(30401L, area = 3, column = 4, blockType = LabyrinthNodeTypes.EVENT),
        ),
        currentBlockId = currentBlockId,
    )

    private fun node(
        blockId: Long,
        area: Int,
        column: Int,
        blockType: Int,
    ) = LabyrinthNodeJson(
        area = area,
        column = column,
        row = 1,
        blockId = blockId,
        blockType = blockType,
        questId = null,
        nextBlockIds = emptyList(),
        isAreaLastPoint = false,
    )
}
