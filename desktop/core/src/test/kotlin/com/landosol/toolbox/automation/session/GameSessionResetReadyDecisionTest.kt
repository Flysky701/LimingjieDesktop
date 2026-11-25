package com.landosol.toolbox.automation.session

import com.landosol.toolbox.labyrinth.vision.LabyrinthEntryPageState
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 2026-09-17 live report: the batch started the reset while the client still showed the old
 * labyrinth home. The first frame after TERMINATING was DAWN_REALM_HOME_IDLE, the workflow
 * marked itself READY, the terminator's later TIMEOUT was ignored, and the batch stopped with
 * CLIENT_INVALIDATION_FAILED without a single home-tab tap having been observed.
 */
class GameSessionResetReadyDecisionTest {
    @Test
    fun `batch started from the login flow skips termination but not from the labyrinth`() {
        for (page in listOf(
            LabyrinthEntryPageState.TITLE_WAITING_TAP,
            LabyrinthEntryPageState.PRE_HOME_DATA_LOADING,
            LabyrinthEntryPageState.HOME_ANNOUNCEMENT,
            LabyrinthEntryPageState.HOME,
            LabyrinthEntryPageState.ADVENTURE,
        )) {
            assertEquals(page.name, true, gameSessionResetSkipsTermination(page))
        }
        for (page in listOf(
            LabyrinthEntryPageState.DAWN_REALM_HOME_IDLE,
            LabyrinthEntryPageState.DAWN_REALM_HOME_ACTIVE,
            LabyrinthEntryPageState.NODE_SELECTION,
            LabyrinthEntryPageState.BATTLE_FAILED,
            LabyrinthEntryPageState.UNKNOWN,
        )) {
            assertEquals(page.name, false, gameSessionResetSkipsTermination(page))
        }
    }

    @Test
    fun `labyrinth home seen while terminating is held not ready`() {
        for (page in listOf(
            LabyrinthEntryPageState.DAWN_REALM_HOME_IDLE,
            LabyrinthEntryPageState.DAWN_REALM_HOME_ACTIVE,
            LabyrinthEntryPageState.NODE_SELECTION,
        )) {
            assertEquals(
                page.name,
                GameSessionResetReadyDecision.HOLD_FOR_TERMINATION,
                gameSessionResetReadyDecision(GameSessionResetStage.PENDING, page),
            )
            assertEquals(
                page.name,
                GameSessionResetReadyDecision.HOLD_FOR_TERMINATION,
                gameSessionResetReadyDecision(GameSessionResetStage.TERMINATING, page),
            )
            assertEquals(
                page.name,
                GameSessionResetReadyDecision.HOLD_FOR_TERMINATION,
                gameSessionResetReadyDecision(GameSessionResetStage.RELAUNCHING, page),
            )
        }
    }

    @Test
    fun `labyrinth home after navigation completes the reset`() {
        for (stage in listOf(
            GameSessionResetStage.WAITING_LOGIN,
            GameSessionResetStage.NAVIGATING_HOME,
            GameSessionResetStage.NAVIGATING_ADVENTURE,
            GameSessionResetStage.NAVIGATING_DAWN_REALM,
        )) {
            assertEquals(
                stage.name,
                GameSessionResetReadyDecision.READY,
                gameSessionResetReadyDecision(stage, LabyrinthEntryPageState.DAWN_REALM_HOME_IDLE),
            )
        }
    }

    @Test
    fun `other pages continue normal navigation`() {
        assertEquals(
            GameSessionResetReadyDecision.CONTINUE,
            gameSessionResetReadyDecision(GameSessionResetStage.WAITING_LOGIN, LabyrinthEntryPageState.HOME),
        )
        assertEquals(
            GameSessionResetReadyDecision.CONTINUE,
            gameSessionResetReadyDecision(GameSessionResetStage.NAVIGATING_HOME, LabyrinthEntryPageState.ADVENTURE),
        )
    }
}
