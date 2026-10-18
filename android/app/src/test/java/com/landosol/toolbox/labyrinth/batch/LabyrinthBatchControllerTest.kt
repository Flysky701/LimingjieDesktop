package com.landosol.toolbox.labyrinth.batch

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Covers the ten JVM cases enumerated in labyrinth-full-automation.md §16.1. */
class LabyrinthBatchControllerTest {

    private class FakePorts : LabyrinthBatchPorts {
        val log = mutableListOf<String>()
        val saved = mutableListOf<LabyrinthBatchCheckpoint>()
        var nextEnterId = 100L
        var rerollFails = false
        var invalidationFails = false
        var runStartFails = false
        var reusableOpeningCheck: LabyrinthBatchReusableOpeningCheck = LabyrinthBatchReusableOpeningCheck.Valid
        val startedRuns = mutableListOf<Triple<Long, Int, String>>()
        val stoppedRuns = mutableListOf<String>()

        override suspend fun reroll(accountId: Long, guildId: Int, difficulty: Int): LabyrinthBatchRerollResult {
            log += "reroll:$guildId"
            if (rerollFails) return LabyrinthBatchRerollResult.Failure("reroll failed")
            return LabyrinthBatchRerollResult.Success(nextEnterId++)
        }

        override suspend fun invalidateClientSessionAndReturn(accountId: Long): LabyrinthBatchInvalidationResult {
            log += "invalidate"
            return if (invalidationFails) {
                LabyrinthBatchInvalidationResult.Failure("capture stopped")
            } else {
                LabyrinthBatchInvalidationResult.Success("tap-home-tab")
            }
        }

        override suspend fun verifyReusableOpening(
            accountId: Long,
            opening: LabyrinthBatchReusableOpening,
        ): LabyrinthBatchReusableOpeningCheck {
            log += "verify:${opening.enterId}"
            return reusableOpeningCheck
        }

        override suspend fun startRun(accountId: Long, guildId: Int, runId: String): Boolean {
            log += "start:$runId"
            startedRuns += Triple(accountId, guildId, runId)
            return !runStartFails
        }

        override suspend fun stopRun(reason: String) {
            log += "stop:$reason"
            stoppedRuns += reason
        }

        override suspend fun saveCheckpoint(checkpoint: LabyrinthBatchCheckpoint) {
            saved += checkpoint
        }
    }

    private fun controller(ports: FakePorts): LabyrinthBatchController {
        var now = 1_000L
        return LabyrinthBatchController(ports = ports, clock = { now++ })
    }

    private fun goal(guild: Int, count: Int, mode: LabyrinthBatchGoalMode = LabyrinthBatchGoalMode.CLEARS) =
        LabyrinthBatchGoal(guildId = guild, targetCount = count, mode = mode)

    private fun currentRunId(controller: LabyrinthBatchController): String =
        requireNotNull(controller.state.value?.currentRunId)

    // 1. Cleared -> reroll -> invalidate -> title -> next run
    @Test
    fun `cleared run rerolls invalidates and starts the next run`() = runTest {
        val ports = FakePorts()
        val batch = controller(ports)
        assertTrue(batch.start("b1", 7L, listOf(goal(guild = 1, count = 2)), difficulty = 1))
        assertEquals(LabyrinthBatchStage.RUNNING_LABYRINTH, batch.state.value?.stage)
        val firstRun = currentRunId(batch)

        batch.onRunTerminal(LabyrinthRunTerminalEvent.Cleared(firstRun))

        assertEquals(
            listOf("reroll:1", "invalidate", "start:$firstRun", "reroll:1", "invalidate", "start:b1-run002"),
            ports.log,
        )
        assertEquals(LabyrinthBatchStage.RUNNING_LABYRINTH, batch.state.value?.stage)
        assertEquals(1, batch.state.value?.activeGoal?.completedCount)
        assertEquals("tap-home-tab", batch.state.value?.lastSessionInvalidationAction)
    }

    // 2. FailedMaxRetry -> reroll -> invalidate -> title -> next run
    @Test
    fun `failed max retry also rerolls invalidates and starts the next run`() = runTest {
        val ports = FakePorts()
        val batch = controller(ports)
        batch.start("b1", 7L, listOf(goal(guild = 1, count = 2)), difficulty = 1)
        val firstRun = currentRunId(batch)

        batch.onRunTerminal(LabyrinthRunTerminalEvent.FailedMaxRetry(firstRun))

        assertEquals(6, ports.log.size)
        assertEquals("start:b1-run002", ports.log.last())
        assertEquals(1, batch.state.value?.activeGoal?.failedCount)
        assertEquals(0, batch.state.value?.activeGoal?.completedCount)
    }

    // 3. reroll failure must not touch the client
    @Test
    fun `reroll failure halts before any client action`() = runTest {
        val ports = FakePorts().apply { rerollFails = true }
        val batch = controller(ports)
        batch.start("b1", 7L, listOf(goal(guild = 1, count = 1)), difficulty = 1)

        assertEquals(listOf("reroll:1"), ports.log)
        assertEquals(LabyrinthBatchStage.FAILED, batch.state.value?.stage)
        assertEquals(LabyrinthBatchHaltReason.REROLL_FAILED, batch.haltReason.value)
        assertTrue(ports.startedRuns.isEmpty())
    }

    // 4. reroll success but invalidation unconfirmed must not start a run
    @Test
    fun `unconfirmed client invalidation never starts a run`() = runTest {
        val ports = FakePorts().apply { invalidationFails = true }
        val batch = controller(ports)
        batch.start("b1", 7L, listOf(goal(guild = 1, count = 1)), difficulty = 1)

        assertEquals(listOf("reroll:1", "invalidate"), ports.log)
        assertEquals(LabyrinthBatchStage.FAILED, batch.state.value?.stage)
        assertEquals(LabyrinthBatchHaltReason.CLIENT_INVALIDATION_FAILED, batch.haltReason.value)
        assertTrue(batch.state.value?.message?.contains("capture stopped") == true)
        assertTrue(batch.state.value?.clientSessionNeedsInvalidation == true)
        assertTrue(ports.startedRuns.isEmpty())
    }

    // 5. the stale labyrinth home must be handled by the invalidation port, not by an entry tap
    @Test
    fun `invalidation is the only client step between reroll and run start`() = runTest {
        val ports = FakePorts()
        val batch = controller(ports)
        batch.start("b1", 7L, listOf(goal(guild = 1, count = 1)), difficulty = 1)

        val rerollIndex = ports.log.indexOf("reroll:1")
        val startIndex = ports.log.indexOfFirst { it.startsWith("start:") }
        assertEquals(listOf("invalidate"), ports.log.subList(rerollIndex + 1, startIndex))
    }

    // 6. switching from guild A to guild B changes the guild handed to startRun
    @Test
    fun `next goal switches the guild passed to the run`() = runTest {
        val ports = FakePorts()
        val batch = controller(ports)
        batch.start("b1", 7L, listOf(goal(guild = 1, count = 1), goal(guild = 2, count = 1)), difficulty = 1)
        assertEquals(1, ports.startedRuns.single().second)

        batch.onRunTerminal(LabyrinthRunTerminalEvent.Cleared(currentRunId(batch)))

        assertEquals(2, ports.startedRuns.last().second)
        assertEquals(2, batch.state.value?.currentGuildId)
        assertEquals(1, batch.state.value?.activeGoalIndex)
        assertTrue(ports.log.contains("reroll:2"))
    }

    // 7. CLEARS mode: a failure does not advance completion
    @Test
    fun `clears mode does not count a failed run toward the target`() = runTest {
        val ports = FakePorts()
        val batch = controller(ports)
        batch.start("b1", 7L, listOf(goal(guild = 1, count = 1, mode = LabyrinthBatchGoalMode.CLEARS)), difficulty = 1)

        batch.onRunTerminal(LabyrinthRunTerminalEvent.FailedMaxRetry(currentRunId(batch)))

        assertEquals(0, batch.state.value?.activeGoal?.completedCount)
        assertEquals(LabyrinthBatchStage.RUNNING_LABYRINTH, batch.state.value?.stage)
        assertEquals(2, ports.startedRuns.size)
    }

    // 8. ATTEMPTS mode: a failure counts as a completed attempt
    @Test
    fun `attempts mode counts a failed run and completes the batch`() = runTest {
        val ports = FakePorts()
        val batch = controller(ports)
        batch.start("b1", 7L, listOf(goal(guild = 1, count = 1, mode = LabyrinthBatchGoalMode.ATTEMPTS)), difficulty = 1)

        batch.onRunTerminal(LabyrinthRunTerminalEvent.FailedMaxRetry(currentRunId(batch)))

        // After COMPLETED the active index sits past the last goal, so read the goal itself.
        assertEquals(1, batch.state.value?.goals?.single()?.completedCount)
        assertEquals(1, batch.state.value?.goals?.single()?.failedCount)
        assertEquals(LabyrinthBatchStage.COMPLETED, batch.state.value?.stage)
        assertNull(batch.state.value?.activeGoal)
        assertEquals(1, ports.startedRuns.size)
    }

    // 9. resume from checkpoint does not double count
    @Test
    fun `resume does not repeat a recorded result and refuses to replay a mid-run checkpoint`() = runTest {
        val ports = FakePorts()
        val batch = controller(ports)
        batch.start("b1", 7L, listOf(goal(guild = 1, count = 2)), difficulty = 1)
        val firstRun = currentRunId(batch)
        batch.onRunTerminal(LabyrinthRunTerminalEvent.Cleared(firstRun))
        val afterFirst = requireNotNull(batch.state.value)
        assertEquals(1, afterFirst.activeGoal?.completedCount)

        // A checkpoint that died while RUNNING must not be replayed blindly.
        val restarted = controller(FakePorts())
        assertFalse(restarted.resume(afterFirst))
        assertEquals(LabyrinthBatchStage.PAUSED, restarted.state.value?.stage)
        assertEquals(1, restarted.state.value?.activeGoal?.completedCount)

        // A checkpoint saved after recording (before the next cycle) resumes without re-counting.
        val recorded = ports.saved.last { it.stage == LabyrinthBatchStage.RECORDING_RESULT }
        val resumePorts = FakePorts()
        val resumed = controller(resumePorts)
        assertTrue(resumed.resume(recorded))
        assertEquals(1, resumed.state.value?.activeGoal?.completedCount)
        assertEquals(LabyrinthBatchStage.RUNNING_LABYRINTH, resumed.state.value?.stage)
        assertEquals(listOf("reroll:1", "invalidate", "start:b1-run002"), resumePorts.log)
    }

    // 10. duplicate terminal events are idempotent
    @Test
    fun `duplicate terminal event for the same run is ignored`() = runTest {
        val ports = FakePorts()
        val batch = controller(ports)
        batch.start("b1", 7L, listOf(goal(guild = 1, count = 3)), difficulty = 1)
        val firstRun = currentRunId(batch)

        batch.onRunTerminal(LabyrinthRunTerminalEvent.Cleared(firstRun))
        val startsAfterFirst = ports.startedRuns.size
        batch.onRunTerminal(LabyrinthRunTerminalEvent.Cleared(firstRun))
        batch.onRunTerminal(LabyrinthRunTerminalEvent.FailedMaxRetry(firstRun))

        assertEquals(1, batch.state.value?.activeGoal?.completedCount)
        assertEquals(0, batch.state.value?.activeGoal?.failedCount)
        assertEquals(startsAfterFirst, ports.startedRuns.size)
    }

    // --- additional safety behaviour ---

    @Test
    fun `unknown fatal run halts without counting a round`() = runTest {
        val ports = FakePorts()
        val batch = controller(ports)
        batch.start("b1", 7L, listOf(goal(guild = 1, count = 1)), difficulty = 1)

        batch.onRunTerminal(LabyrinthRunTerminalEvent.FatalUnknown(currentRunId(batch), "capture size changed"))

        assertEquals(LabyrinthBatchStage.FAILED, batch.state.value?.stage)
        assertEquals(LabyrinthBatchHaltReason.FATAL_RUN, batch.haltReason.value)
        assertEquals(0, batch.state.value?.activeGoal?.completedCount)
        assertEquals(1, batch.state.value?.abnormalRuns)
        assertEquals(1, ports.startedRuns.size)
    }

    @Test
    fun `environment loss pauses and stops the run without ending the batch`() = runTest {
        val ports = FakePorts()
        val batch = controller(ports)
        batch.start("b1", 7L, listOf(goal(guild = 1, count = 1)), difficulty = 1)

        batch.haltForEnvironment(LabyrinthBatchHaltReason.CAPTURE_LOST, "MediaProjection stopped")

        assertEquals(LabyrinthBatchStage.PAUSED, batch.state.value?.stage)
        assertEquals(LabyrinthBatchHaltReason.CAPTURE_LOST, batch.haltReason.value)
        assertEquals(listOf("MediaProjection stopped"), ports.stoppedRuns)
        // A terminal event arriving after the pause is ignored: stage is no longer RUNNING.
        batch.onRunTerminal(LabyrinthRunTerminalEvent.Cleared(currentRunId(batch)))
        assertEquals(0, batch.state.value?.activeGoal?.completedCount)
    }

    @Test
    fun `run start failure halts with the running checkpoint persisted first`() = runTest {
        val ports = FakePorts().apply { runStartFails = true }
        val batch = controller(ports)
        batch.start("b1", 7L, listOf(goal(guild = 1, count = 1)), difficulty = 1)

        assertEquals(LabyrinthBatchStage.FAILED, batch.state.value?.stage)
        assertEquals(LabyrinthBatchHaltReason.RUN_START_FAILED, batch.haltReason.value)
        assertTrue(ports.saved.any { it.stage == LabyrinthBatchStage.RUNNING_LABYRINTH })
    }

    @Test
    fun `terminal event for an unknown run id is ignored`() = runTest {
        val ports = FakePorts()
        val batch = controller(ports)
        batch.start("b1", 7L, listOf(goal(guild = 1, count = 1)), difficulty = 1)

        batch.onRunTerminal(LabyrinthRunTerminalEvent.Cleared("someone-else"))

        assertEquals(0, batch.state.value?.activeGoal?.completedCount)
        assertEquals(LabyrinthBatchStage.RUNNING_LABYRINTH, batch.state.value?.stage)
    }

    @Test
    fun `already satisfied goals complete immediately without touching any port`() = runTest {
        val ports = FakePorts()
        val batch = controller(ports)
        val done = LabyrinthBatchGoal(guildId = 1, targetCount = 1, mode = LabyrinthBatchGoalMode.CLEARS, completedCount = 1)

        assertTrue(batch.start("b1", 7L, listOf(done), difficulty = 1))

        assertEquals(LabyrinthBatchStage.COMPLETED, batch.state.value?.stage)
        assertTrue(ports.log.isEmpty())
        assertNull(batch.haltReason.value)
    }

    @Test
    fun `verified matching opening skips reroll but still synchronizes the client`() = runTest {
        val ports = FakePorts()
        val batch = controller(ports)

        assertTrue(
            batch.start(
                batchId = "b1",
                accountId = 7L,
                goals = listOf(goal(guild = 2, count = 2)),
                difficulty = 5,
                reusableOpening = LabyrinthBatchReusableOpening(
                    enterId = 9876L,
                    guildId = 2,
                    difficulty = 5,
                ),
            ),
        )

        assertEquals(listOf("verify:9876", "invalidate", "start:b1-run001"), ports.log)
        assertEquals(9876L, batch.state.value?.currentEnterId)
        assertTrue(batch.state.value?.reusedVerifiedOpeningForFirstRun == true)

        batch.onRunTerminal(LabyrinthRunTerminalEvent.Cleared(currentRunId(batch)))

        assertEquals(
            listOf(
                "verify:9876", "invalidate", "start:b1-run001",
                "reroll:2", "invalidate", "start:b1-run002",
            ),
            ports.log,
        )
    }

    @Test
    fun `failed reused first run rerolls and never checks reuse again`() = runTest {
        val ports = FakePorts()
        val batch = controller(ports)
        batch.start(
            batchId = "b1",
            accountId = 7L,
            goals = listOf(goal(guild = 2, count = 1, mode = LabyrinthBatchGoalMode.CLEARS)),
            difficulty = 5,
            reusableOpening = LabyrinthBatchReusableOpening(9876L, 2, 5),
        )

        batch.onRunTerminal(LabyrinthRunTerminalEvent.FailedMaxRetry(currentRunId(batch)))

        assertEquals(
            listOf(
                "verify:9876", "invalidate", "start:b1-run001",
                "reroll:2", "invalidate", "start:b1-run002",
            ),
            ports.log,
        )
        assertEquals(1, ports.log.count { it.startsWith("verify:") })
        assertEquals(LabyrinthBatchStage.RUNNING_LABYRINTH, batch.state.value?.stage)
    }

    @Test
    fun `stale reusable opening falls back to a fresh reroll`() = runTest {
        val ports = FakePorts().apply {
            reusableOpeningCheck = LabyrinthBatchReusableOpeningCheck.Stale("already consumed")
        }
        val batch = controller(ports)

        batch.start(
            batchId = "b1",
            accountId = 7L,
            goals = listOf(goal(guild = 2, count = 1)),
            difficulty = 5,
            reusableOpening = LabyrinthBatchReusableOpening(9876L, 2, 5),
        )

        assertEquals(listOf("verify:9876", "reroll:2", "invalidate", "start:b1-run001"), ports.log)
        assertFalse(batch.state.value?.reusedVerifiedOpeningForFirstRun == true)
    }

    @Test
    fun `reusable opening verification failure stops before touching the client`() = runTest {
        val ports = FakePorts().apply {
            reusableOpeningCheck = LabyrinthBatchReusableOpeningCheck.Failure("network unavailable")
        }
        val batch = controller(ports)

        batch.start(
            batchId = "b1",
            accountId = 7L,
            goals = listOf(goal(guild = 2, count = 1)),
            difficulty = 5,
            reusableOpening = LabyrinthBatchReusableOpening(9876L, 2, 5),
        )

        assertEquals(listOf("verify:9876"), ports.log)
        assertEquals(LabyrinthBatchHaltReason.REUSABLE_OPENING_VALIDATION_FAILED, batch.haltReason.value)
        assertTrue(ports.startedRuns.isEmpty())
    }

    @Test
    fun `verified opening with different first goal uses normal reroll`() = runTest {
        val ports = FakePorts()
        val batch = controller(ports)

        batch.start(
            batchId = "b1",
            accountId = 7L,
            goals = listOf(goal(guild = 3, count = 1)),
            difficulty = 5,
            reusableOpening = LabyrinthBatchReusableOpening(
                enterId = 9876L,
                guildId = 2,
                difficulty = 5,
            ),
        )

        assertEquals(listOf("reroll:3", "invalidate", "start:b1-run001"), ports.log)
        assertFalse(batch.state.value?.reusedVerifiedOpeningForFirstRun == true)
    }
}
