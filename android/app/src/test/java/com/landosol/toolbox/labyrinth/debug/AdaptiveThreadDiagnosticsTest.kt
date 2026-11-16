package com.landosol.toolbox.labyrinth.debug

import com.landosol.toolbox.labyrinth.LabyrinthBalancedDiagnostics
import com.landosol.toolbox.labyrinth.LabyrinthEntryRecognitionSessionState
import com.landosol.toolbox.labyrinth.LabyrinthEntryRecognitionStatus
import com.landosol.toolbox.labyrinth.concurrent.LabyrinthFourThreadRuntime
import com.landosol.toolbox.labyrinth.concurrent.LabyrinthThreadBudget
import com.landosol.toolbox.labyrinth.vision.LabyrinthAnchorScores
import com.landosol.toolbox.labyrinth.vision.LabyrinthEntryPageObservation
import com.landosol.toolbox.labyrinth.vision.LabyrinthEntryPageState
import com.landosol.toolbox.labyrinth.vision.LabyrinthFastFrameObservation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveThreadDiagnosticsTest {
    // Explicit fixtures do not initialize the shared runtime merely to serialize diagnostic data.
    private fun diagnostics(processors: Int, workers: Int) = LabyrinthBalancedDiagnostics(
        businessThreadBudget = 2 + workers,
        cpuWorkerBudget = workers,
        availableProcessors = processors,
        controlThread = true,
        fastObservationCount = 20,
        fastFrameSequence = 20,
        fastTimestampMillis = 10_000,
        detailFrameSequence = 12,
        detailTimestampMillis = 8_000,
    )

    private fun observation() = LabyrinthFastFrameObservation(
        observation = LabyrinthEntryPageObservation(
            LabyrinthEntryPageState.NODE_SELECTION, 0.8,
            emptyMap(), LabyrinthAnchorScores(emptyMap()),
        ),
        frameSequence = 20, timestampMillis = 10_000, width = 1280, height = 720,
        elapsedMillis = 45,
    )

    @Test fun baselinePayloadCarriesFourBusinessThreadsAndTwoWorkers() {
        val payload = labyrinthBalancedDiagnosticPayload(diagnostics(processors = 4, workers = 2))
        assertEquals(4, payload["availableProcessors"])
        assertEquals(1, payload["captureThreadBudget"])
        assertEquals(1, payload["controlThreadBudget"])
        assertEquals(2, payload["cpuWorkerBudget"])
        assertEquals(4, payload["businessThreadBudget"])
        assertEquals(
            "4（采集1＋控制1＋计算2） · 启动可见CPU 4（预算，非同时运行数）",
            payload["threadBudgetDescription"],
        )
    }

    @Test fun expandedPayloadCarriesSixBusinessThreadsAndFourWorkers() {
        val payload = labyrinthBalancedDiagnosticPayload(diagnostics(processors = 8, workers = 4))
        assertEquals(8, payload["availableProcessors"])
        assertEquals(1, payload["captureThreadBudget"])
        assertEquals(1, payload["controlThreadBudget"])
        assertEquals(4, payload["cpuWorkerBudget"])
        assertEquals(6, payload["businessThreadBudget"])
        assertEquals(
            "6（采集1＋控制1＋计算4） · 启动可见CPU 8（预算，非同时运行数）",
            payload["threadBudgetDescription"],
        )
    }

    @Test fun balancedPayloadDoesNotInferWorkerCountFromProcessorCount() {
        val payload = labyrinthBalancedDiagnosticPayload(diagnostics(processors = 12, workers = 2))
        assertEquals(12, payload["availableProcessors"])
        assertEquals(2, payload["cpuWorkerBudget"])
        assertEquals(4, payload["businessThreadBudget"])
        assertTrue((payload["threadBudgetDescription"] as String).contains("计算2"))
    }

    @Test fun defaultDiagnosticsMatchActualInitializedRuntime() {
        // Run this class in separate 4-CPU and 6-CPU JVMs to cover both process-startup selections.
        val d = LabyrinthBalancedDiagnostics()
        assertEquals(LabyrinthFourThreadRuntime.availableProcessors, d.availableProcessors)
        assertEquals(LabyrinthFourThreadRuntime.cpuWorkerBudget, d.cpuWorkerBudget)
        assertEquals(LabyrinthFourThreadRuntime.businessThreadBudget, d.businessThreadBudget)
        assertEquals(2 + d.cpuWorkerBudget, d.businessThreadBudget)
        assertTrue(d.cpuWorkerBudget == 2 || d.cpuWorkerBudget == 4)
    }

    @Test fun copiedDiagnosticEvidenceKeepsItsOriginalBudgetSnapshot() {
        val original = diagnostics(processors = 6, workers = 4)
        val copied = original.copy(fastObservationCount = 21, sceneEpoch = 9)
        assertEquals(original.availableProcessors, copied.availableProcessors)
        assertEquals(original.cpuWorkerBudget, copied.cpuWorkerBudget)
        assertEquals(original.businessThreadBudget, copied.businessThreadBudget)
        assertEquals(21L, copied.fastObservationCount)
        assertEquals(9L, copied.sceneEpoch)
    }

    @Test fun fastPayloadCarriesActualBudgetWithoutRelabellingDetailedEvidence() {
        val state = LabyrinthEntryRecognitionSessionState(
            status = LabyrinthEntryRecognitionStatus.RUNNING,
            frameCount = 7,
            balancedDiagnostics = diagnostics(processors = 6, workers = 4),
        )
        val payload = labyrinthFastDiagnosticPayload(observation(), state, 11_000, "test", 10087)
        val balanced = payload["balancedDiagnostics"] as Map<*, *>
        assertEquals(6, balanced["availableProcessors"])
        assertEquals(4, balanced["cpuWorkerBudget"])
        assertEquals(6, balanced["businessThreadBudget"])
        assertEquals(20L, payload["frameSequence"])
        assertEquals(12L, payload["detailFrameSequence"])
        assertEquals(8_000L, payload["detailTimestampMillis"])
        assertEquals(false, payload["evidenceForActions"])
        assertEquals("fast_observation_only_not_action_ack", payload["scope"])
        assertFalse(payload.containsKey("boxes"))
    }

    @Test fun startupSelectionIsExplicitlyNotALiveWorkerCount() {
        val payload = labyrinthThreadBudgetSelectionPayload(LabyrinthThreadBudget.forProcessors(6))
        assertEquals("startup_selection_not_live_worker_count", payload["scope"])
        assertEquals(6, payload["availableProcessors"])
        assertEquals(1, payload["captureThreadBudget"])
        assertEquals(1, payload["controlThreadBudget"])
        assertEquals(4, payload["selectedCpuWorkerBudget"])
        assertEquals(6, payload["selectedBusinessThreadBudget"])
        assertFalse(payload.containsKey("cpuWorkerBudget"))
        assertFalse(payload.containsKey("businessThreadBudget"))
    }

    @Test fun startupSelectionStaysStableWhenAnotherBudgetIsComputed() {
        val startup = LabyrinthThreadBudget.forProcessors(6)
        val before = labyrinthThreadBudgetSelectionPayload(startup)
        val other = LabyrinthThreadBudget.forProcessors(4)
        assertEquals(2, other.cpuWorkerBudget)
        val after = labyrinthThreadBudgetSelectionPayload(startup)
        assertEquals(before, after)
        assertEquals(6, after["availableProcessors"])
        assertEquals(4, after["selectedCpuWorkerBudget"])
    }

    @Test fun absentBalancedStateDoesNotInventActualWorkerDiagnostics() {
        val payload = labyrinthFastDiagnosticPayload(
            observation(), LabyrinthEntryRecognitionSessionState(), 11_000, "test", 10087,
        )
        assertNull(payload["balancedDiagnostics"])
        assertFalse(payload.containsKey("cpuWorkerBudget"))
        assertFalse(payload.containsKey("businessThreadBudget"))
    }
}
