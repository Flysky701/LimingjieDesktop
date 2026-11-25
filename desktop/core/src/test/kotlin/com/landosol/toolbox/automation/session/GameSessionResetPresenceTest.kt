package com.landosol.toolbox.automation.session

import com.landosol.toolbox.labyrinth.vision.LabyrinthEntryPageState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GameSessionResetPresenceTest {
    @Test fun `a round that never sees the client has no session to invalidate`() {
        // 2026-09-19 bundle 192858: a batch round began while the game was still opening. Every
        // frame was the portrait launcher, so the observation handler never read a page and the
        // peek stayed on its initial UNKNOWN. UNKNOWN otherwise means a labyrinth page that failed
        // to classify, so the reset tried to invalidate a session that was not there and failed
        // the whole batch with CLIENT_INVALIDATION_FAILED.
        assertTrue(
            gameSessionResetSkipsTermination(LabyrinthEntryPageState.UNKNOWN, clientObserved = false),
        )
        assertTrue(
            gameSessionResetSkipsTermination(LabyrinthEntryPageState.NODE_SELECTION, clientObserved = false),
        )
    }

    @Test fun `an unclassified page on a visible client still gets invalidated`() {
        assertFalse(
            gameSessionResetSkipsTermination(LabyrinthEntryPageState.UNKNOWN, clientObserved = true),
        )
        assertFalse(
            gameSessionResetSkipsTermination(LabyrinthEntryPageState.NODE_SELECTION, clientObserved = true),
        )
    }

    @Test fun `pre-labyrinth pages keep skipping as before`() {
        listOf(
            LabyrinthEntryPageState.TITLE_WAITING_TAP,
            LabyrinthEntryPageState.PRE_HOME_DATA_LOADING,
            LabyrinthEntryPageState.HOME_ANNOUNCEMENT,
            LabyrinthEntryPageState.HOME,
            LabyrinthEntryPageState.ADVENTURE,
        ).forEach { page ->
            assertTrue("$page", gameSessionResetSkipsTermination(page, clientObserved = true))
            assertTrue("$page", gameSessionResetSkipsTermination(page))
        }
    }
}
