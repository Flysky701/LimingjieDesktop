package com.landosol.toolbox.labyrinth.vision

import com.landosol.toolbox.labyrinth.LabyrinthRelicMark
import org.junit.Assert.*
import org.junit.Test

class LabyrinthRelicDetailTextTest {
    @Test fun `only series title matches not inventory or reward dialogs`() {
        assertTrue(LabyrinthRelicDetailText.isSeriesTitle("系列效果 一览"))
        assertFalse(LabyrinthRelicDetailText.isSeriesTitle("现在的状况 迷宫遗物"))
    }
    @Test fun `visible rows use screen label aliases and omit clipped last row`() {
        val result = LabyrinthRelicDetailText.parseVisibleSeries("守备\nHP4\n削弱\n1\n暴击\n1\n加速")
        assertEquals(mapOf(LabyrinthRelicMark.DEFENSE to 4, LabyrinthRelicMark.DEBUFF to 1,
            LabyrinthRelicMark.CRITICAL to 1), result)
    }
    @Test fun `partial top row and ambiguous numbers never become false totals`() {
        val result = LabyrinthRelicDetailText.parseVisibleSeries("9\n守备\n4 9\n弱体\n99\n暴击\n+1\n加速\n12")
        assertEquals(mapOf(LabyrinthRelicMark.ACCELERATION to 12), result)
    }
    @Test fun `contradictory duplicate headings are omitted`() {
        assertTrue(LabyrinthRelicDetailText.parseVisibleSeries("守备 4\n守备 5").isEmpty())
    }
}
