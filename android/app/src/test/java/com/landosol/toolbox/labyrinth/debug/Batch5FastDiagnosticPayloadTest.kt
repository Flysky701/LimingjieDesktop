package com.landosol.toolbox.labyrinth.debug

import com.landosol.toolbox.labyrinth.LabyrinthBalancedDiagnostics
import com.landosol.toolbox.labyrinth.LabyrinthEntryRecognitionSessionState
import com.landosol.toolbox.labyrinth.LabyrinthEntryRecognitionStatus
import com.landosol.toolbox.labyrinth.vision.LabyrinthAnchorScores
import com.landosol.toolbox.labyrinth.vision.LabyrinthEntryPageObservation
import com.landosol.toolbox.labyrinth.vision.LabyrinthEntryPageState
import com.landosol.toolbox.labyrinth.vision.LabyrinthFastFrameObservation
import org.junit.Assert.*
import org.junit.Test

class Batch5FastDiagnosticPayloadTest {
    private fun observation() = LabyrinthFastFrameObservation(
        observation = LabyrinthEntryPageObservation(LabyrinthEntryPageState.NODE_SELECTION, 0.8,
            emptyMap(), LabyrinthAnchorScores(emptyMap())),
        frameSequence = 20, timestampMillis = 10_000, width = 1280, height = 720,
        elapsedMillis = 45,
    )

    @Test fun fastAndDetailedEvidenceKeepIndependentSourcesAndCannotBecomeActionAck() {
        val state = LabyrinthEntryRecognitionSessionState(status = LabyrinthEntryRecognitionStatus.RUNNING,
            frameCount = 7, message = "后台规划中", balancedDiagnostics = LabyrinthBalancedDiagnostics(
                controlThread = true, fastObservationCount = 20, fastFrameSequence = 20,
                fastTimestampMillis = 10_000, detailFrameSequence = 12, detailTimestampMillis = 8_000,
                plannerPendingCount = 1))
        val payload = labyrinthFastDiagnosticPayload(observation(), state, 11_000, "test", 10086)
        assertEquals(10_000L, payload["timestamp"])
        assertEquals(11_000L, payload["publishedAtMillis"])
        assertEquals(20L, payload["frameSequence"])
        assertEquals(12L, payload["detailFrameSequence"])
        assertEquals(8_000L, payload["detailTimestampMillis"])
        assertEquals(false, payload["evidenceForActions"])
        assertEquals("fast_observation_only_not_action_ack", payload["scope"])
        assertFalse(payload.containsKey("boxes"))
        assertFalse(payload.containsKey("nodes"))
        val diagnostics = payload["balancedDiagnostics"] as Map<*, *>
        assertEquals(4, diagnostics["businessThreadBudget"])
        assertEquals(2, diagnostics["cpuWorkerBudget"])
        assertEquals(1, diagnostics["plannerPendingCount"])
    }

    @Test fun stoppedOrPausedStateCanBePublishedWithoutPretendingANewCaptureExists() {
        val obs = observation()
        val paused = LabyrinthEntryRecognitionSessionState(status = LabyrinthEntryRecognitionStatus.RUNNING,
            paused = true, message = "已暂停")
        val p = labyrinthFastDiagnosticPayload(obs, paused, 12_000, "test", 10086)
        assertEquals(true, p["paused"])
        assertEquals(10_000L, p["timestamp"])
        assertEquals(12_000L, p["publishedAtMillis"])
        val stopped = paused.copy(status = LabyrinthEntryRecognitionStatus.STOPPED, paused = false, message = "已停止")
        val s = labyrinthFastDiagnosticPayload(obs, stopped, 13_000, "test", 10086)
        assertEquals(false, s["running"])
        assertEquals("STOPPED", s["status"])
        assertEquals(10_000L, s["timestamp"])
        assertEquals(13_000L, s["publishedAtMillis"])
        assertNull(s["balancedDiagnostics"])
    }
}
