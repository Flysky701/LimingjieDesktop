package com.landosol.toolbox.labyrinth

import com.landosol.toolbox.labyrinth.batch.LabyrinthBatchReusableOpening
import com.landosol.toolbox.labyrinth.batch.LabyrinthBatchReusableOpeningCheck
import com.landosol.toolbox.protocol.labyrinth.LabyrinthMapNode
import com.landosol.toolbox.protocol.labyrinth.LabyrinthResume
import com.landosol.toolbox.protocol.labyrinth.LabyrinthTop
import org.junit.Assert.assertTrue
import org.junit.Test

class LabyrinthReusableOpeningSnapshotTest {
    private val opening = LabyrinthBatchReusableOpening(enterId = 77L, guildId = 2, difficulty = 5)
    private val node = LabyrinthMapNode(
        area = 1,
        column = 1,
        row = 1,
        blockId = 101L,
        blockType = 1,
        questId = null,
        nextBlockIds = emptyList(),
        isAreaLastPoint = false,
    )
    private val top = LabyrinthTop(
        enterId = 77L,
        guildId = 2,
        difficulty = 5,
        clearedDifficulties = emptyList(),
    )
    private val route = LabyrinthRouteJson(
        enterId = 77L,
        guildId = 2,
        difficulty = 5,
        attempt = 1,
        rerollUntilFound = true,
        perfectStart = false,
        thirdBlockChoice = "RANDOM",
        area3BossIds = emptyList(),
        area5BossIds = emptyList(),
        nodes = listOf(node.toJson()),
    )

    @Test
    fun `unadvanced matching opening is reusable`() {
        val result = labyrinthReusableOpeningSnapshotCheck(
            opening = opening,
            top = top,
            resume = LabyrinthResume(77L, 2, currentBlockId = null, map = listOf(node)),
            route = route,
        )

        assertTrue(result is LabyrinthBatchReusableOpeningCheck.Valid)
    }

    @Test
    fun `advanced opening is rejected because roles and relics cannot be reconstructed`() {
        val result = labyrinthReusableOpeningSnapshotCheck(
            opening = opening,
            top = top,
            resume = LabyrinthResume(77L, 2, currentBlockId = 101L, map = listOf(node)),
            route = route.copy(currentBlockId = 101L),
        )

        assertTrue(result is LabyrinthBatchReusableOpeningCheck.Stale)
        assertTrue((result as LabyrinthBatchReusableOpeningCheck.Stale).message.contains("角色和遗物"))
    }

    @Test
    fun `server map must contain every saved route node`() {
        val result = labyrinthReusableOpeningSnapshotCheck(
            opening = opening,
            top = top,
            resume = LabyrinthResume(77L, 2, currentBlockId = null, map = listOf(node.copy(blockId = 999L))),
            route = route,
        )

        assertTrue(result is LabyrinthBatchReusableOpeningCheck.Stale)
    }

    @Test
    fun `saved default guild cannot stand in for the verified server guild`() {
        val result = labyrinthReusableOpeningSnapshotCheck(
            opening, top, LabyrinthResume(77L, 2, null, listOf(node)), route.copy(guildId = 1),
        )
        assertTrue(result is LabyrinthBatchReusableOpeningCheck.Stale)
    }

    private fun LabyrinthMapNode.toJson() = LabyrinthNodeJson(
        area = area,
        column = column,
        row = row,
        blockId = blockId,
        blockType = blockType,
        questId = questId,
        nextBlockIds = nextBlockIds,
        isAreaLastPoint = isAreaLastPoint,
    )
}
