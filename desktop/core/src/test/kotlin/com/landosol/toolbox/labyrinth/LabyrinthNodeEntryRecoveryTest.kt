package com.landosol.toolbox.labyrinth

import com.landosol.toolbox.labyrinth.node.LabyrinthNodeTypes
import com.landosol.toolbox.labyrinth.vision.LabyrinthEntryPageState
import org.junit.Assert.*
import org.junit.Test

class LabyrinthNodeEntryRecoveryTest {
    @Test fun `confirmed relic move survives title loading and entry until destination appears`() {
        val pages = listOf(LabyrinthEntryPageState.NODE_SELECTION, LabyrinthEntryPageState.UNKNOWN,
            LabyrinthEntryPageState.TITLE_WAITING_TAP, LabyrinthEntryPageState.GAME_LOADING_PROGRESS,
            LabyrinthEntryPageState.HOME, LabyrinthEntryPageState.ADVENTURE,
            LabyrinthEntryPageState.DAWN_REALM_HOME_ACTIVE, LabyrinthEntryPageState.NODE_SELECTION)
        pages.forEachIndexed { index, page ->
            assertFalse(labyrinthCanRecoverConfirmedNodeEntry(LabyrinthNodeTypes.RELIC, page,
                30201, 30201, true, 1000, 2000L + index * 10_000))
        }
        assertTrue(labyrinthCanRecoverConfirmedNodeEntry(LabyrinthNodeTypes.RELIC,
            LabyrinthEntryPageState.RELIC_CHOICE, 30201, 30201, true, 1000, 100_000))
    }

    @Test fun `receipt does not skip progress from map reward count or mismatched page`() {
        for (page in listOf(LabyrinthEntryPageState.NODE_SELECTION, LabyrinthEntryPageState.LINK_CHOICE,
            LabyrinthEntryPageState.ITEM_REWARD)) {
            assertFalse(labyrinthCanRecoverConfirmedNodeEntry(LabyrinthNodeTypes.RELIC, page,
                30201, 30201, true, 1000, 100_000))
        }
    }

    @Test fun `consumed stale unconfirmed and different route receipts cannot advance`() {
        val page = LabyrinthEntryPageState.RELIC_CHOICE
        assertFalse(labyrinthCanRecoverConfirmedNodeEntry(LabyrinthNodeTypes.RELIC, page, 30201, 30302, true, 1000, 2000))
        assertFalse(labyrinthCanRecoverConfirmedNodeEntry(LabyrinthNodeTypes.RELIC, page, 30201, 30201, false, 1000, 2000))
        assertFalse(labyrinthCanRecoverConfirmedNodeEntry(LabyrinthNodeTypes.RELIC, page, 30201, 30201, true, null, 2000))
        assertFalse(labyrinthCanRecoverConfirmedNodeEntry(LabyrinthNodeTypes.RELIC, page, 30201, 30201, true, 1000, 700_000))
    }
}
