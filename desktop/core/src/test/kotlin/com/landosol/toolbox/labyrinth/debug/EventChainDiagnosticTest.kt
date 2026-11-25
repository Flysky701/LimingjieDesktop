package com.landosol.toolbox.labyrinth.debug

import com.landosol.toolbox.labyrinth.LabyrinthBalancedDiagnostics
import com.landosol.toolbox.labyrinth.LabyrinthEventPlanningDiagnostics
import org.junit.Assert.*
import org.junit.Test

class EventChainDiagnosticTest {
    @Test fun eventTelemetryIsIndependentOfAggregateSlotsAndNotActionPermission() {
        val event = LabyrinthEventPlanningDiagnostics(eventId = "1112", phase = "OCR_REFRESH_WAIT",
            semanticKeyHash = 42, active = true, ready = false, requestCount = 1, submittedCount = 1,
            completedCount = 0, cancelledCount = 0, discardedCount = 0, ocrRefreshRetainedCount = 10,
            submittedAtMillis = 1000, startedAtMillis = 1100, waitingMillis = 5500)
        val d = LabyrinthBalancedDiagnostics(businessThreadBudget = 4, cpuWorkerBudget = 2,
            availableProcessors = 4, plannerPendingCount = 3, plannerDiscardCount = 23, eventPlanning = event)
        val payload = labyrinthBalancedDiagnosticPayload(d)
        assertEquals(3, payload["plannerPendingCount"])
        assertEquals(23L, payload["plannerDiscardCount"])
        val e = payload["eventPlanning"] as Map<*, *>
        assertEquals("event_planning_only_not_action_permission", e["scope"])
        assertEquals("1112", e["eventId"])
        assertEquals("OCR_REFRESH_WAIT", e["phase"])
        assertEquals(1L, e["submittedCount"])
        assertEquals(0L, e["cancelledCount"])
        assertEquals(10L, e["ocrRefreshRetainedCount"])
        assertFalse(e.containsKey("actionSafe"))
        assertFalse(e.containsKey("button"))
        assertEquals(5500L, e["waitingMillis"])
    }

    @Test fun optionalTelemetryDoesNotChangeLegacyPayloadWhenAbsent() {
        val d = LabyrinthBalancedDiagnostics(businessThreadBudget = 6, cpuWorkerBudget = 4, availableProcessors = 6)
        assertFalse(labyrinthBalancedDiagnosticPayload(d).containsKey("eventPlanning"))
    }

    @Test fun eventTelemetryKeepsResultAndQuiescenceTimesSeparate() {
        val e = labyrinthEventPlanningDiagnosticPayload(LabyrinthEventPlanningDiagnostics(
            eventId = "2315", phase = "WAITING_FRESH_DETAIL", ready = true,
            submittedAtMillis = 1000, startedAtMillis = 1010, endedAtMillis = 7000,
            resultAtMillis = 7002, quiescedAtMillis = 7003, readyAtMillis = 7004,
            lastCancellationReason = "SEMANTIC_INPUT_CHANGED", lastFailure = "failure"))
        assertEquals(7002L, e["resultAtMillis"])
        assertEquals(7003L, e["quiescedAtMillis"])
        assertEquals(7004L, e["readyAtMillis"])
        assertEquals("SEMANTIC_INPUT_CHANGED", e["lastCancellationReason"])
        assertEquals("failure", e["lastFailure"])
    }
}
