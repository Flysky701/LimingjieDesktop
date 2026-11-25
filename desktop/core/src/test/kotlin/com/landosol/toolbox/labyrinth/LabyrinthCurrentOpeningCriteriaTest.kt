package com.landosol.toolbox.labyrinth

import org.junit.Assert.assertEquals
import org.junit.Test
import com.landosol.toolbox.protocol.labyrinth.LabyrinthFailureKind
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

class LabyrinthCurrentOpeningCriteriaTest {
    @Test
    fun `current selections replace frozen checkpoint target when revalidating same run`() {
        val oldPolicy = LabyrinthRoutePolicy(
            perfectStart = false,
            thirdBlockChoice = LabyrinthThirdBlockChoice.RELIC,
            area3BossIds = setOf(301),
            area5BossIds = setOf(501),
        )
        val selectedPolicy = LabyrinthRoutePolicy(
            perfectStart = true,
            thirdBlockChoice = LabyrinthThirdBlockChoice.EVENT,
            area3BossIds = setOf(302),
            area5BossIds = setOf(502),
        )
        val checkpoint = LabyrinthRerollCheckpoint(
            accountId = 7,
            attempt = 19,
            enterId = 88001,
            guildId = 1,
            difficulty = 2,
            policy = oldPolicy,
            verdict = LabyrinthRouteVerdict.NOT_TARGET,
            message = "旧条件不匹配",
            updatedAt = 100,
        )

        val criteria = labyrinthCurrentOpeningCriteria(
            selectedDifficulty = 5,
            selectedRoutePolicy = selectedPolicy,
            savedCheckpoint = checkpoint,
            currentEnterId = 88001,
        )

        assertEquals(5, criteria.difficulty)
        assertEquals(selectedPolicy, criteria.routePolicy)
        assertEquals(19, criteria.attempt)
    }

    @Test
    fun `checkpoint from another run cannot contribute its attempt counter`() {
        val checkpoint = LabyrinthRerollCheckpoint(
            accountId = 7,
            attempt = 19,
            enterId = 88001,
            guildId = 1,
            difficulty = 2,
            policy = LabyrinthRoutePolicy(),
            verdict = LabyrinthRouteVerdict.TARGET,
            message = null,
            updatedAt = 100,
        )

        val criteria = labyrinthCurrentOpeningCriteria(
            selectedDifficulty = 3,
            selectedRoutePolicy = LabyrinthRoutePolicy(),
            savedCheckpoint = checkpoint,
            currentEnterId = 99002,
        )

        assertEquals(0, criteria.attempt)
    }

    @Test
    fun `a kicked api session is retried with a fresh login but other failures are not`() {
        // 2026-09-17: "连接中断。回到标题界面。" arrives as REJECTED after the client re-logs in.
        assertTrue(labyrinthReadRetriesWithFreshLogin(LabyrinthFailureKind.REJECTED, 0, 3))
        assertTrue(labyrinthReadRetriesWithFreshLogin(LabyrinthFailureKind.REJECTED, 2, 3))
        assertFalse(labyrinthReadRetriesWithFreshLogin(LabyrinthFailureKind.REJECTED, 3, 3))
        assertFalse(labyrinthReadRetriesWithFreshLogin(LabyrinthFailureKind.NETWORK, 0, 3))
        assertFalse(labyrinthReadRetriesWithFreshLogin(LabyrinthFailureKind.PROTOCOL, 0, 3))
    }
}
