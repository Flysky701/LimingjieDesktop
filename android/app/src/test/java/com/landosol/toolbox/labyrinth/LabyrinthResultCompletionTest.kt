package com.landosol.toolbox.labyrinth

import com.landosol.toolbox.labyrinth.vision.LabyrinthEntryPageState as Page
import com.landosol.toolbox.labyrinth.vision.parseRunResultScore
import org.junit.Assert.*
import org.junit.Test

class LabyrinthResultCompletionTest {
    @Test fun `stable result reports score and ends at arrival rather than reward dismissal`() {
        val completion = LabyrinthResultCompletion()
        assertTrue(completion.observe(Page.RUN_CLEAR_RESULT, null, 10_000, 1_000))
        completion.observe(Page.RUN_CLEAR_RESULT, 42_000, 10_500, 1_000)
        completion.observe(Page.RUN_CLEAR_RESULT, 46_000, 11_000, 1_000)
        assertNull(completion.summary)
        completion.observe(Page.RUN_CLEAR_RESULT, 46_000, 12_000, 1_000)
        assertEquals(LabyrinthRunSummary(1_000, 10_000, 46_000), completion.summary)
        completion.observe(Page.NODE_SELECTION, null, 15_000, 1_000)
        assertEquals(46_000L, completion.summary?.score)
    }

    @Test fun `confirmed result owns map frames and missing score is bounded`() {
        val completion = LabyrinthResultCompletion()
        completion.observe(Page.RUN_CLEAR_RESULT, null, 10_000, 1_000)
        completion.observe(Page.RUN_CLEAR_RESULT, null, 10_500, 1_000)
        assertTrue(completion.observe(Page.NODE_SELECTION, null, 11_000, 1_000))
        assertNull(completion.summary)
        assertTrue(completion.observe(Page.NODE_SELECTION, null, 16_000, 1_000))
        assertEquals(LabyrinthRunSummary(1_000, 10_000, null), completion.summary)
    }

    @Test fun `single misclassified frame is not a clear and does not leak score`() {
        val completion = LabyrinthResultCompletion()
        completion.observe(Page.RUN_CLEAR_RESULT, 46_000, 10_000, 1_000)
        assertFalse(completion.observe(Page.NODE_SELECTION, null, 11_000, 1_000))
        assertNull(completion.summary)
        assertFalse(completion.observe(Page.BATTLE_RESULT, null, 17_000, 1_000))
    }

    @Test fun `score parser rejects rewards lists confidence and unreadable text`() {
        assertEquals(46_000L, parseRunResultScore("46,000"))
        assertEquals(46_000L, parseRunResultScore("46，000"))
        for (bad in listOf("", "0", "0.95", "46000 4600", "46,00", "4O,000", "-46000")) {
            assertNull(bad, parseRunResultScore(bad))
        }
    }
}
