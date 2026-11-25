package com.landosol.toolbox.labyrinth

import com.landosol.toolbox.protocol.labyrinth.LabyrinthTop
import org.junit.Assert.assertTrue
import org.junit.Test

class LabyrinthExecutionGateTest {
    @Test
    fun `execution requires target checkpoint and matching server enter id`() {
        val route = route()
        val checkpoint = checkpoint(LabyrinthRouteVerdict.TARGET, route.enterId)
        val allowed = validateLabyrinthExecution(route, checkpoint, top(route.enterId))

        assertTrue(allowed is LabyrinthExecutionGateResult.Allowed)
    }

    @Test
    fun `old local route is rejected when server has a new enter id`() {
        val route = route(101L)
        val result = validateLabyrinthExecution(
            route = route,
            checkpoint = checkpoint(LabyrinthRouteVerdict.TARGET, route.enterId),
            top = top(202L),
        )

        assertTrue(result is LabyrinthExecutionGateResult.Blocked)
        assertTrue((result as LabyrinthExecutionGateResult.Blocked).message.contains("不一致"))
    }

    @Test
    fun `pending checkpoint is not executable but non target can reuse same cached opening`() {
        val route = route()

        assertTrue(
            validateLabyrinthExecution(
                route,
                checkpoint(LabyrinthRouteVerdict.PENDING_VERIFICATION, route.enterId),
                top(route.enterId),
            ) is LabyrinthExecutionGateResult.Blocked,
        )
        val cached = validateLabyrinthExecution(
            route,
            checkpoint(LabyrinthRouteVerdict.NOT_TARGET, route.enterId),
            top = null,
        )
        assertTrue(cached is LabyrinthExecutionGateResult.Allowed)
        assertTrue((cached as LabyrinthExecutionGateResult.Allowed).message.contains("Enter ID"))
    }

    @Test
    fun `pending checkpoint remains blocked even when enter id matches`() {
        val route = route()
        val cached = validateLabyrinthExecution(
            route,
            checkpoint(LabyrinthRouteVerdict.PENDING_VERIFICATION, route.enterId),
            top = null,
        )

        assertTrue(cached is LabyrinthExecutionGateResult.Blocked)
    }

    private fun route(enterId: Long = 101L) = LabyrinthRouteJson(
        enterId = enterId,
        guildId = 1,
        difficulty = 1,
        attempt = 1,
        rerollUntilFound = false,
        perfectStart = false,
        thirdBlockChoice = LabyrinthThirdBlockChoice.EITHER.name,
        area3BossIds = emptyList(),
        area5BossIds = emptyList(),
        nodes = listOf(node(10001L)),
        allNodes = listOf(node(10001L)),
    )

    private fun checkpoint(verdict: LabyrinthRouteVerdict, enterId: Long) = LabyrinthRerollCheckpoint(
        accountId = 1L,
        attempt = 1,
        enterId = enterId,
        guildId = 1,
        difficulty = 1,
        policy = LabyrinthRoutePolicy(),
        verdict = verdict,
        message = null,
        updatedAt = 1L,
    )

    private fun top(enterId: Long) = LabyrinthTop(
        enterId = enterId,
        guildId = 1,
        difficulty = 1,
        clearedDifficulties = emptyList(),
    )

    private fun node(blockId: Long) = LabyrinthNodeJson(
        area = 1,
        column = 1,
        row = 1,
        blockId = blockId,
        blockType = 1,
        questId = null,
        nextBlockIds = emptyList(),
        isAreaLastPoint = true,
    )
}
