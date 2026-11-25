package com.landosol.toolbox.labyrinth

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LabyrinthAutoRunWorkflowTest {

    @Test
    fun `runs the configured number of rounds with resets in between`() = runTest {
        var starts = 0
        var resets = 0
        val workflow = LabyrinthAutoRunWorkflow(
            startRun = { starts++; true },
            awaitRunFinished = { LabyrinthAutoRunRoundOutcome.Completed },
            resetSession = { resets++; true },
        )

        val result = workflow.run(LabyrinthAutoRunConfig(accountId = 1L, targetRuns = 3))

        assertTrue(result is LabyrinthAutoRunResult.Completed)
        assertEquals(3, (result as LabyrinthAutoRunResult.Completed).runs)
        assertEquals(3, starts)
        // 最后一轮之后不再重置
        assertEquals(2, resets)
        assertEquals(3, workflow.progress.value.completedRuns)
        assertTrue(!workflow.progress.value.running)
    }

    @Test
    fun `aborts with completed count when a round ends early`() = runTest {
        var round = 0
        val workflow = LabyrinthAutoRunWorkflow(
            startRun = { true },
            awaitRunFinished = {
                round++
                if (round < 2) {
                    LabyrinthAutoRunRoundOutcome.Completed
                } else {
                    LabyrinthAutoRunRoundOutcome.Aborted("悬浮窗紧急停止")
                }
            },
            resetSession = { true },
        )

        val result = workflow.run(LabyrinthAutoRunConfig(accountId = 1L, targetRuns = 5))

        assertTrue(result is LabyrinthAutoRunResult.Aborted)
        result as LabyrinthAutoRunResult.Aborted
        assertEquals(1, result.runs)
        assertEquals("悬浮窗紧急停止", result.reason)
    }

    @Test
    fun `fails when the session cannot start`() = runTest {
        val workflow = LabyrinthAutoRunWorkflow(
            startRun = { false },
            awaitRunFinished = { error("must not be awaited") },
            resetSession = { error("must not reset") },
        )

        val result = workflow.run(LabyrinthAutoRunConfig(accountId = null, targetRuns = 1))

        assertTrue(result is LabyrinthAutoRunResult.Failure)
        assertEquals(0, (result as LabyrinthAutoRunResult.Failure).runs)
    }

    @Test
    fun `fails when the reset between rounds fails`() = runTest {
        val workflow = LabyrinthAutoRunWorkflow(
            startRun = { true },
            awaitRunFinished = { LabyrinthAutoRunRoundOutcome.Completed },
            resetSession = { false },
        )

        val result = workflow.run(LabyrinthAutoRunConfig(accountId = 1L, targetRuns = 2))

        assertTrue(result is LabyrinthAutoRunResult.Failure)
        assertEquals(1, (result as LabyrinthAutoRunResult.Failure).runs)
    }

    @Test
    fun `rejects an out of range run count`() {
        var failed = false
        try {
            LabyrinthAutoRunConfig(accountId = 1L, targetRuns = 0)
        } catch (expected: IllegalArgumentException) {
            failed = true
        }
        assertTrue(failed)
    }
}
