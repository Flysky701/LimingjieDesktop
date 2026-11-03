package com.landosol.toolbox.labyrinth

import com.landosol.toolbox.labyrinth.vision.LabyrinthEntryPageState as Page
import com.landosol.toolbox.labyrinth.vision.parseRunResultScore
import org.junit.Assert.*
import org.junit.Test

class LabyrinthResultCompletionTest {
    @Test fun `latest confirmed score survives reward pages and map transitions`() {
        val collector = LabyrinthRunScoreCollector()
        collector.observe(Page.RUN_CLEAR_RESULT, null, 10_000)
        collector.observe(Page.RUN_CLEAR_RESULT, 42_000, 10_500)
        collector.observe(Page.RUN_CLEAR_RESULT, 46_000, 11_000)
        assertNull(collector.score)
        collector.observe(Page.RUN_CLEAR_RESULT, 46_000, 12_000)
        for (page in listOf(Page.NODE_SELECTION, Page.RUN_CLEAR_CHEST_RESULT, Page.ITEM_REWARD, Page.DAWN_REALM_HOME_IDLE)) {
            collector.observe(page, null, 15_000)
            assertEquals(46_000L, collector.score)
        }
    }

    @Test fun `unread score stays absent through settlement`() {
        val collector = LabyrinthRunScoreCollector()
        repeat(20) { collector.observe(Page.RUN_CLEAR_RESULT, null, it * 1_000L) }
        collector.observe(Page.ITEM_REWARD, null, 21_000)
        collector.observe(Page.DAWN_REALM_HOME_IDLE, null, 22_000)
        assertNull(collector.score)
    }

    @Test fun `single misclassified frame and ordinary battle do not leak score`() {
        val collector = LabyrinthRunScoreCollector()
        collector.observe(Page.RUN_CLEAR_RESULT, 46_000, 10_000)
        collector.observe(Page.NODE_SELECTION, null, 11_000)
        collector.observe(Page.BATTLE_RESULT, 46_000, 12_000)
        collector.observe(Page.RUN_CLEAR_RESULT, null, 13_000)
        collector.observe(Page.RUN_CLEAR_RESULT, null, 15_000)
        assertNull(collector.score)
    }

    @Test fun `score parser rejects rewards lists confidence and unreadable text`() {
        assertEquals(46_000L, parseRunResultScore("46,000"))
        assertEquals(46_000L, parseRunResultScore("46，000"))
        for (bad in listOf("", "0", "0.95", "46000 4600", "46,00", "4O,000", "-46000")) {
            assertNull(bad, parseRunResultScore(bad))
        }
    }
}
