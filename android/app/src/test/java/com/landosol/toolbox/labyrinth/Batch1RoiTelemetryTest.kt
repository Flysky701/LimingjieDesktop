package com.landosol.toolbox.labyrinth

import com.landosol.toolbox.labyrinth.vision.RelicRoiOcrTelemetry
import org.junit.Assert.*
import org.junit.Test

class Batch1RoiTelemetryTest {
    @Test fun `engine completion duration is measured once without changing OCR scheduling`() {
        val before = RelicRoiOcrTelemetry.snapshot()
        val id = RelicRoiOcrTelemetry.requested("node_relic_mark_1", 1000L, 10L)
        assertEquals(before.inFlight + 1, RelicRoiOcrTelemetry.snapshot().inFlight)
        RelicRoiOcrTelemetry.completed(id, 65L, 2)
        val after = RelicRoiOcrTelemetry.snapshot()
        assertEquals(before.requests + 1, after.requests)
        assertEquals(before.completions + 1, after.completions)
        assertEquals(before.inFlight, after.inFlight)
        assertEquals(55L, after.last?.durationMillis)
        RelicRoiOcrTelemetry.completed(id, 80L, 2)
        assertEquals(after.completions, RelicRoiOcrTelemetry.snapshot().completions)
    }
}
