package com.landosol.toolbox.automation.session

import com.landosol.toolbox.labyrinth.vision.LabyrinthEntryPageState
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GameSessionResetPlannerTest {
    private val planner = GameSessionResetPlanner()

    private fun observation(
        page: LabyrinthEntryPageState = LabyrinthEntryPageState.UNKNOWN,
        block: SessionBlockKind = SessionBlockKind.NONE,
        foreground: Boolean = true,
    ) = GameSessionResetObservation(pageState = page, block = block, clientForeground = foreground)

    private fun decide(
        stage: GameSessionResetStage,
        page: LabyrinthEntryPageState = LabyrinthEntryPageState.UNKNOWN,
        block: SessionBlockKind = SessionBlockKind.NONE,
        foreground: Boolean = true,
        stableFrames: Int = 2,
        now: Long = 5_000L,
        lastChange: Long = 1_000L,
    ) = planner.decide(
        stage = stage,
        observation = observation(page, block, foreground),
        stableFrames = stableFrames,
        nowMillis = now,
        lastStageChangeAt = lastChange,
    )

    @Test
    fun `pending advances to terminating`() = runTest {
        val decision = decide(stage = GameSessionResetStage.PENDING)
        assertEquals(GameSessionResetDecision.Transition(GameSessionResetStage.TERMINATING), decision)
    }

    @Test
    fun `terminating waits while client still foreground and advances once gone`() = runTest {
        val waiting = decide(
            stage = GameSessionResetStage.TERMINATING,
            foreground = true,
        )
        assertTrue(waiting is GameSessionResetDecision.Wait)

        val advancing = decide(
            stage = GameSessionResetStage.TERMINATING,
            foreground = false,
        )
        assertEquals(GameSessionResetDecision.Transition(GameSessionResetStage.RELAUNCHING), advancing)
    }

    @Test
    fun `relaunching advances to waiting login`() = runTest {
        val decision = decide(stage = GameSessionResetStage.RELAUNCHING)
        assertEquals(GameSessionResetDecision.Transition(GameSessionResetStage.WAITING_LOGIN), decision)
    }

    @Test
    fun `navigating home waits for home page then advances to adventure`() = runTest {
        val waiting = decide(
            stage = GameSessionResetStage.NAVIGATING_HOME,
            page = LabyrinthEntryPageState.TITLE_WAITING_TAP,
        )
        assertTrue(waiting is GameSessionResetDecision.Wait)

        val advancing = decide(
            stage = GameSessionResetStage.NAVIGATING_HOME,
            page = LabyrinthEntryPageState.HOME,
        )
        assertEquals(GameSessionResetDecision.Transition(GameSessionResetStage.NAVIGATING_ADVENTURE), advancing)
    }

    @Test
    fun `full linear flow reaches ready for next round and completes`() = runTest {
        assertEquals(
            GameSessionResetDecision.Transition(GameSessionResetStage.NAVIGATING_ADVENTURE),
            decide(stage = GameSessionResetStage.NAVIGATING_HOME, page = LabyrinthEntryPageState.HOME),
        )
        assertEquals(
            GameSessionResetDecision.Transition(GameSessionResetStage.NAVIGATING_DAWN_REALM),
            decide(stage = GameSessionResetStage.NAVIGATING_ADVENTURE, page = LabyrinthEntryPageState.ADVENTURE),
        )
        val ready = decide(
            stage = GameSessionResetStage.NAVIGATING_DAWN_REALM,
            page = LabyrinthEntryPageState.DAWN_REALM_HOME_IDLE,
        )
        assertEquals(GameSessionResetDecision.Transition(GameSessionResetStage.READY_FOR_NEXT_ROUND), ready)
        assertTrue(decide(stage = GameSessionResetStage.READY_FOR_NEXT_ROUND) is GameSessionResetDecision.Complete)
    }

    @Test
    fun `block overrides every stage including pending`() = runTest {
        for (stage in GameSessionResetStage.entries) {
            val blocked = planner.decide(
                stage = stage,
                observation = observation(block = SessionBlockKind.RELOGIN_REQUIRED),
                stableFrames = 2,
                nowMillis = 5_000L,
                lastStageChangeAt = 1_000L,
            )
            assertTrue("stage $stage must be blocked", blocked is GameSessionResetDecision.Blocked)
            assertEquals(
                SessionBlockKind.RELOGIN_REQUIRED,
                (blocked as GameSessionResetDecision.Blocked).kind,
            )
        }
    }

    @Test
    fun `waits for stable frames and click cooldown before advancing`() = runTest {
        val unstable = decide(
            stage = GameSessionResetStage.NAVIGATING_HOME,
            page = LabyrinthEntryPageState.HOME,
            stableFrames = 1,
        )
        assertTrue(unstable is GameSessionResetDecision.Wait)

        val cooldown = planner.decide(
            stage = GameSessionResetStage.NAVIGATING_HOME,
            observation = observation(page = LabyrinthEntryPageState.HOME),
            stableFrames = 2,
            nowMillis = 1_100L,
            lastStageChangeAt = 1_000L,
        )
        assertTrue(cooldown is GameSessionResetDecision.Wait)
    }

    @Test
    fun `client not in foreground stalls navigation`() = runTest {
        val decision = decide(
            stage = GameSessionResetStage.NAVIGATING_HOME,
            page = LabyrinthEntryPageState.HOME,
            foreground = false,
        )
        assertTrue(decision is GameSessionResetDecision.Wait)
    }

    @Test
    fun `missing rule for stage fails`() = runTest {
        val decision = decide(stage = GameSessionResetStage.FAILED)
        assertTrue(decision is GameSessionResetDecision.Fail)
    }

    @Test
    fun `default rules cover the full linear flow`() = runTest {
        val rules = GameSessionResetPlanner.defaultRules()
        assertEquals(
            listOf(
                GameSessionResetStage.WAITING_LOGIN,
                GameSessionResetStage.NAVIGATING_HOME,
                GameSessionResetStage.NAVIGATING_ADVENTURE,
                GameSessionResetStage.NAVIGATING_DAWN_REALM,
            ),
            rules.map { it.stage },
        )
        assertEquals(
            listOf(
                GameSessionResetStage.NAVIGATING_HOME,
                GameSessionResetStage.NAVIGATING_ADVENTURE,
                GameSessionResetStage.NAVIGATING_DAWN_REALM,
                GameSessionResetStage.READY_FOR_NEXT_ROUND,
            ),
            rules.map { it.nextStage },
        )
    }
}
