package com.landosol.toolbox.desktop.app.ui

import com.landosol.toolbox.desktop.app.adapters.storage.DesktopRunRecord
import org.junit.Assert.assertEquals
import org.junit.Test

class RunChartSamplesTest {
    @Test fun rangeCountsOnlyDistinctCompletedScoredRunsInFinishOrder() {
        val clear = DesktopRunRecord("a", 1, "账号", "单局", "通关", endedAt = 10, result = "通关",
            singleRun = true, cleared = 1, score = 500)
        val later = clear.copy(id = "b", endedAt = 20)
        val rows = listOf(
            later.copy(score = null), clear.copy(id = "older", endedAt = 5), clear, later, later,
            clear.copy(id = "running", endedAt = null),
            clear.copy(id = "stopped", endedAt = 30, cleared = 0, result = "手动停止"),
            clear.copy(id = "batch", endedAt = 31, singleRun = false),
            clear.copy(id = "no-score", endedAt = 32, score = null),
            clear.copy(id = "zero", endedAt = 33, score = 0),
            clear.copy(id = "invalid", endedAt = 34, score = -1),
        )
        assertEquals(listOf(clear, later), scoredClears(rows, 2))
    }
}
