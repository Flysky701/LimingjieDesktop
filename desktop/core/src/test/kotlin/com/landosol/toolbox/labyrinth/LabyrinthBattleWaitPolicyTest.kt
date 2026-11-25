package com.landosol.toolbox.labyrinth

import com.landosol.toolbox.vision.PixelImage
import com.landosol.toolbox.labyrinth.vision.LabyrinthEntryPageState as Page
import com.landosol.toolbox.labyrinth.vision.computeBattleHudDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class LabyrinthBattleWaitPolicyTest {
    @Test
    fun `unknown and challenge alone do not start battle wait`() {
        val policy = LabyrinthBattleWaitPolicy()
        for (page in listOf(Page.UNKNOWN, Page.BATTLE_CHALLENGE, Page.BATTLE_TEAM_SELECTION)) {
            assertEquals(LabyrinthBattleWaitDecision.NONE, policy.observe(page, 60_000))
        }
    }

    @Test
    fun `executed start tolerates unknown beyond thirty seconds but stops at five minutes`() {
        val policy = started()
        assertEquals(LabyrinthBattleWaitDecision.WAIT, policy.observe(Page.UNKNOWN, 31_000))
        assertEquals(LabyrinthBattleWaitDecision.WAIT, policy.observe(Page.UNKNOWN, 299_999))
        assertEquals(LabyrinthBattleWaitDecision.TIMED_OUT, policy.observe(Page.UNKNOWN, 300_000))
    }

    @Test
    fun `battle loading jitter and duplicate start do not replenish deadline`() {
        val policy = started()
        for ((index, page) in listOf(
            Page.GAME_LOADING_PROGRESS, Page.BATTLE_IN_PROGRESS, Page.UNKNOWN,
            Page.PRE_HOME_DATA_LOADING, Page.BATTLE_IN_PROGRESS,
        ).withIndex()) {
            assertEquals(LabyrinthBattleWaitDecision.WAIT, policy.observe(page, index * 50_000L))
        }
        policy.onStartExecuted(250_000)
        assertEquals(LabyrinthBattleWaitDecision.TIMED_OUT, policy.observe(Page.BATTLE_IN_PROGRESS, 300_000))
    }

    @Test
    fun `recognized battle permits takeover with fixed deadline`() {
        val policy = LabyrinthBattleWaitPolicy()
        assertEquals(LabyrinthBattleWaitDecision.WAIT, policy.observe(Page.BATTLE_IN_PROGRESS, 10_000))
        assertEquals(LabyrinthBattleWaitDecision.TIMED_OUT, policy.observe(Page.UNKNOWN, 310_000))
    }

    @Test
    fun `result without next remains in battle wait through following unknown frames`() {
        val policy = started()
        assertEquals(
            LabyrinthBattleWaitDecision.WAIT,
            policy.observe(Page.BATTLE_RESULT, 1_000, battleResultNextReady = false),
        )
        assertEquals(LabyrinthBattleWaitDecision.WAIT, policy.observe(Page.UNKNOWN, 299_999))
        assertEquals(LabyrinthBattleWaitDecision.TIMED_OUT, policy.observe(Page.UNKNOWN, 300_000))
    }

    @Test
    fun `reliable battle result next releases wait for normal result handling`() {
        val policy = started()
        assertEquals(
            LabyrinthBattleWaitDecision.NONE,
            policy.observe(Page.BATTLE_RESULT, 1_000, battleResultNextReady = true),
        )
        assertEquals(LabyrinthBattleWaitDecision.NONE, policy.observe(Page.UNKNOWN, 2_000))
    }

    @Test
    fun `map rewards and explicit other pages exit even at deadline`() {
        listOf(
            Page.NODE_SELECTION, Page.ITEM_REWARD, Page.CHARACTER_JOINED,
            Page.RUN_CLEAR_RESULT, Page.SHOP, Page.RELIC_CHOICE, Page.TITLE_WAITING_TAP,
        ).forEach { page ->
            val policy = started()
            assertEquals(page.name, LabyrinthBattleWaitDecision.NONE, policy.observe(page, 300_000))
            assertEquals(LabyrinthBattleWaitDecision.NONE, policy.observe(Page.UNKNOWN, 301_000))
        }
    }

    @Test
    fun `stale editor is briefly tolerated but failed start is released`() {
        val policy = started()
        assertEquals(LabyrinthBattleWaitDecision.WAIT, policy.observe(Page.BATTLE_TEAM_SELECTION, 2_999))
        assertEquals(LabyrinthBattleWaitDecision.NONE, policy.observe(Page.BATTLE_TEAM_SELECTION, 3_000))
        assertEquals(LabyrinthBattleWaitDecision.NONE, policy.observe(Page.UNKNOWN, 4_000))
    }

    @Test
    fun `return to editor after battle is not mistaken for stale start page`() {
        val policy = started()
        policy.observe(Page.BATTLE_IN_PROGRESS, 1_000)
        assertEquals(LabyrinthBattleWaitDecision.NONE, policy.observe(Page.BATTLE_TEAM_SELECTION, 2_000))
        policy.onStartExecuted(3_000)
        assertEquals(LabyrinthBattleWaitDecision.WAIT, policy.observe(Page.UNKNOWN, 302_999))
        assertEquals(LabyrinthBattleWaitDecision.TIMED_OUT, policy.observe(Page.UNKNOWN, 303_000))
    }

    @Test
    fun `reset discards previous session deadline`() {
        val policy = started()
        policy.reset()
        assertEquals(LabyrinthBattleWaitDecision.NONE, policy.observe(Page.UNKNOWN, 50_000))
        policy.onStartExecuted(60_000)
        assertEquals(LabyrinthBattleWaitDecision.WAIT, policy.observe(Page.UNKNOWN, 359_999))
        assertEquals(LabyrinthBattleWaitDecision.TIMED_OUT, policy.observe(Page.UNKNOWN, 360_000))
    }

    @Test
    fun `static battle hud still stops at five minutes even for multi team boss`() {
        val policy = LabyrinthBattleWaitPolicy().apply { onStartExecuted(0L, plannedTeams = 3) }
        assertEquals(LabyrinthBattleWaitDecision.WAIT, policy.observe(Page.BATTLE_IN_PROGRESS, 10_000L, hudDigest = 42L))
        assertEquals(LabyrinthBattleWaitDecision.WAIT, policy.observe(Page.BATTLE_IN_PROGRESS, 299_999L, hudDigest = 42L))
        assertEquals(LabyrinthBattleWaitDecision.TIMED_OUT, policy.observe(Page.BATTLE_IN_PROGRESS, 300_000L, hudDigest = 42L))
        assertEquals(5, policy.effectiveLimitMinutes())
    }

    @Test
    fun `progressing hud extends single team and multi team boss bounds but stops sixty seconds after stall`() {
        val singleTeam = LabyrinthBattleWaitPolicy().apply { onStartExecuted(0L, plannedTeams = 1) }
        assertEquals(LabyrinthBattleWaitDecision.WAIT, singleTeam.observe(Page.BATTLE_IN_PROGRESS, 10_000L, hudDigest = 1L))
        assertEquals(LabyrinthBattleWaitDecision.WAIT, singleTeam.observe(Page.BATTLE_IN_PROGRESS, 280_000L, hudDigest = 2L))
        assertEquals(8, singleTeam.currentMaxMinutes())
        // Post-kill fade-out / UNKNOWN summary transition beyond 5 minutes survives within 60 s of HUD progress.
        assertEquals(LabyrinthBattleWaitDecision.WAIT, singleTeam.observe(Page.UNKNOWN, 315_000L))
        assertEquals(LabyrinthBattleWaitDecision.TIMED_OUT, singleTeam.observe(Page.UNKNOWN, 340_000L))

        val multiTeam = LabyrinthBattleWaitPolicy().apply { onStartExecuted(0L, plannedTeams = 3) }
        var tick = 10_000L
        var digest = 100L
        while (tick < 840_000L) {
            assertEquals(
                "tick=$tick",
                LabyrinthBattleWaitDecision.WAIT,
                multiTeam.observe(Page.BATTLE_IN_PROGRESS, tick, hudDigest = digest++),
            )
            tick += 30_000L
        }
        assertEquals(14, multiTeam.currentMaxMinutes())
        assertEquals(LabyrinthBattleWaitDecision.TIMED_OUT, multiTeam.observe(Page.BATTLE_IN_PROGRESS, 840_000L, hudDigest = digest))
        assertEquals(14, multiTeam.effectiveLimitMinutes())
    }

    @Test
    fun `battle hud digest reflects quantized top hud changes`() {
        val first = PixelImage(1920, 1080, IntArray(1920 * 1080) { 0xff202020.toInt() })
        val minorNoise = PixelImage(1920, 1080, IntArray(1920 * 1080) { 0xff222222.toInt() })
        val progressedPixels = IntArray(1920 * 1080) { 0xff202020.toInt() }
        for (y in 32 until 96) {
            for (x in 300 until 900) {
                progressedPixels[y * 1920 + x] = 0xffe05040.toInt()
            }
        }
        val progressed = PixelImage(1920, 1080, progressedPixels)

        val d1 = computeBattleHudDigest(first)
        val dNoise = computeBattleHudDigest(minorNoise)
        val d2 = computeBattleHudDigest(progressed)
        assertNotNull(d1)
        assertEquals(d1, dNoise)
        assertNotEquals(d1, d2)
    }

    private fun started() = LabyrinthBattleWaitPolicy().apply { onStartExecuted(0) }
}
