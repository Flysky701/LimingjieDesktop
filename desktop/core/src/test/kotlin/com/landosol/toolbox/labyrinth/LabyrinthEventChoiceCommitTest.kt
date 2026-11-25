package com.landosol.toolbox.labyrinth

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LabyrinthEventChoiceCommitTest {
    @Test fun `a committed tap the game ignored is retried instead of latching forever`() {
        // 2026-09-20 bundle 133934: 600 consecutive EVENT_CHOICE frames over 323 s with
        // actionCount frozen at 271. The event OCR was 可信 and the recommendation 可执行, yet the
        // run reported "事件推荐已生成，等待按钮稳定". That message is only reachable once
        // eventChoiceCommitted is true, so the tap had been sent and lost; the flag cleared only
        // on a page change, and the page was the thing that never changed.
        val committedAt = 1_000L
        assertFalse(labyrinthEventChoiceCommitExpired(committedAt, committedAt))
        // A real selection leaves the page well inside the window, so nothing is retried early.
        assertFalse(labyrinthEventChoiceCommitExpired(committedAt, committedAt + 2_000L))
        assertFalse(
            labyrinthEventChoiceCommitExpired(committedAt, committedAt + EVENT_CHOICE_COMMIT_RETRY_MILLIS - 1),
        )
        assertTrue(
            labyrinthEventChoiceCommitExpired(committedAt, committedAt + EVENT_CHOICE_COMMIT_RETRY_MILLIS),
        )
        // The 323 s this actually cost is far past the point of retrying.
        assertTrue(labyrinthEventChoiceCommitExpired(committedAt, committedAt + 323_000L))
    }

    @Test fun `nothing expires before a tap has been committed`() {
        assertFalse(labyrinthEventChoiceCommitExpired(Long.MIN_VALUE, 500_000L))
    }

    @Test fun `a clock that goes backwards does not retry immediately`() {
        assertFalse(labyrinthEventChoiceCommitExpired(10_000L, 9_000L))
    }
}
