package com.landosol.toolbox.labyrinth

import com.landosol.toolbox.labyrinth.vision.LabyrinthEntryPageState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LabyrinthOrphanDialogBudgetTest {
    @Test fun `a cleared screen gives the cancel budget back`() {
        // 2026-09-19 report: a run stopped with "移动确认弹窗多次取消无效，请手动关闭后继续" without
        // tapping 取消 even once. The budget only ever reset on a session error, so three stray
        // dialogs cancelled successfully earlier in the run left nothing for the fourth.
        assertFalse(labyrinthOrphanMoveConfirmationBudgetRestored(0))
        assertFalse(labyrinthOrphanMoveConfirmationBudgetRestored(ORPHAN_MOVE_CONFIRMATION_CLEAR_FRAMES - 1))
        assertTrue(labyrinthOrphanMoveConfirmationBudgetRestored(ORPHAN_MOVE_CONFIRMATION_CLEAR_FRAMES))
        // One dialog-free frame is a fade-out, not proof; a flickering dialog must not refill it.
        assertTrue("needs more than a single frame", ORPHAN_MOVE_CONFIRMATION_CLEAR_FRAMES >= 2)
    }

    @Test fun `an exhausted budget still blocks while the dialog is on screen`() {
        // The budget's real job is unchanged: stop pressing a dialog that refuses to close.
        assertFalse(
            labyrinthShouldDismissOrphanNodeMoveConfirmation(
                pageState = LabyrinthEntryPageState.NODE_SELECTION,
                hasPendingNodeTransition = false,
                canRecover = false,
                stableFrames = ORPHAN_MOVE_CONFIRMATION_DISMISS_STABLE_FRAMES,
                dismissAttempts = MAX_ORPHAN_MOVE_CONFIRMATION_DISMISS_ATTEMPTS,
                lastDismissAt = Long.MIN_VALUE,
                now = 10_000L,
            ),
        )
        assertTrue(
            labyrinthShouldDismissOrphanNodeMoveConfirmation(
                pageState = LabyrinthEntryPageState.NODE_SELECTION,
                hasPendingNodeTransition = false,
                canRecover = false,
                stableFrames = ORPHAN_MOVE_CONFIRMATION_DISMISS_STABLE_FRAMES,
                dismissAttempts = 0,
                lastDismissAt = Long.MIN_VALUE,
                now = 10_000L,
            ),
        )
    }
}
