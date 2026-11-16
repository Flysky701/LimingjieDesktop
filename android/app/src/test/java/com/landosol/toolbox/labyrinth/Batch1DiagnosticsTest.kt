package com.landosol.toolbox.labyrinth

import com.landosol.toolbox.automation.capture.FrameQueueMetrics
import com.landosol.toolbox.labyrinth.debug.LABYRINTH_DEBUG_HISTORY_LIMIT
import com.landosol.toolbox.BuildConfig
import org.junit.Assert.*
import org.junit.Test

class Batch1DiagnosticsTest {
    @Test fun `history limit is exactly six hundred`() { assertEquals(600, LABYRINTH_DEBUG_HISTORY_LIMIT) }
    @Test fun `queue counters distinguish acceptance replacement and filtering`() {
        val m = FrameQueueMetrics()
        m.seen(); m.accepted(); m.seen(); m.accepted(); m.replaced()
        m.seen(); m.intervalDropped(); m.seen(); m.invalidDropped()
        val s = m.snapshot()
        assertEquals(4L, s.seen); assertEquals(2L, s.accepted)
        assertEquals(1L, s.replaced); assertEquals(1L, s.intervalDropped); assertEquals(1L, s.invalidDropped)
        m.reset(); assertEquals(0L, m.snapshot().seen)
    }
    @Test fun `safe and planned boss counts are not mixed in logs`() {
        val member = LabyrinthRecommendedTeamMember("1001", "T")
        val r = LabyrinthBattleTeamRecommendation(listOf(member), LabyrinthTeamDamageType.PHYSICAL,
            1.0, member, null, emptyList(), 0, 1, plannedBossTeamCount = 3, safeBossTeamCount = 1)
        assertTrue(r.logText().contains("Boss计划队数=3"))
        assertTrue(r.logText().contains("Boss安全队数=1"))
    }
    @Test fun `application id respects the requested coexist suffix`() {
        val suffix = System.getProperty("test.coexistSuffix", "").takeIf { it.isNotBlank() }.orEmpty()
        assertEquals("com.landosol.toolbox" + suffix, BuildConfig.APPLICATION_ID)
    }
}
