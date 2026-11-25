package com.landosol.toolbox.automation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GameClientLaunchGateTest {
    @Test
    fun `cold-start handoff accepts the foreground game without relaunching its SDK activity`() {
        // 2026-09-26: the reset reached the labyrinth home, but accessibility still remembered
        // AnnouncementActivity. Both B and channel clients must be taken over by package alone.
        for (target in listOf("com.bilibili.priconne", "com.bilibili.priconne.mi")) {
            var launches = 0
            val decision = GameClientLaunchGate.decide(target, target)

            assertEquals(GameLaunchDecision.ALREADY_FOREGROUND, decision)
            assertTrue(GameClientLaunchGate.execute(decision) { launches++; false })
            assertEquals("Must not restart an already foreground client", 0, launches)
        }
    }

    @Test
    fun `another foreground channel launches only the requested client and propagates failure`() {
        val decision = GameClientLaunchGate.decide(
            "com.bilibili.priconne.mi",
            "com.bilibili.priconne",
        )
        var launches = 0

        assertEquals(GameLaunchDecision.LAUNCH_GAME, decision)
        assertFalse(GameClientLaunchGate.execute(decision) { launches++; false })
        assertEquals(1, launches)
        assertTrue(GameClientLaunchGate.execute(decision) { true })
    }

    @Test
    fun `missing target blocks without launching even if a game is foreground`() {
        val decision = GameClientLaunchGate.decide(null, "com.bilibili.priconne")

        assertEquals(GameLaunchDecision.BLOCKED, decision)
        assertFalse(GameClientLaunchGate.execute(decision) { error("No target to launch") })
    }
}
