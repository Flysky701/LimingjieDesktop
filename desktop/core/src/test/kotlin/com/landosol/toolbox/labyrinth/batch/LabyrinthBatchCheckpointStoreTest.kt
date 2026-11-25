package com.landosol.toolbox.labyrinth.batch

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LabyrinthBatchCheckpointStoreTest {
    @Test
    fun `checkpoint survives a JSON round trip and clears per account`() = runTest {
        val backing = mutableMapOf<String, String>()
        val store = JsonLabyrinthBatchCheckpointStore(
            read = { backing[it] },
            write = { key, value -> if (value == null) backing.remove(key) else backing[key] = value },
        )
        val checkpoint = LabyrinthBatchCheckpoint(
            batchId = "20260916-001",
            accountId = 7L,
            goals = listOf(
                LabyrinthBatchGoal(guildId = 1, targetCount = 10, mode = LabyrinthBatchGoalMode.ATTEMPTS, completedCount = 3, failedCount = 1),
                LabyrinthBatchGoal(guildId = 3, targetCount = 5, mode = LabyrinthBatchGoalMode.CLEARS),
            ),
            activeGoalIndex = 0,
            stage = LabyrinthBatchStage.RUNNING_LABYRINTH,
            currentRunId = "20260916-001-run004",
            currentEnterId = 38196L,
            currentGuildId = 1,
            currentDifficulty = 1,
            rerollCompletedForNextRun = true,
            lastSessionInvalidationAction = "session-expiry-return-title",
            lastRecordedRunId = "20260916-001-run003",
            runsStarted = 4,
            abnormalRuns = 0,
            message = "第 4 局开始",
            updatedAt = 1_000L,
        )

        store.save(checkpoint)
        assertEquals(checkpoint, store.load(7L))
        assertNull(store.load(8L))

        store.clear(7L)
        assertNull(store.load(7L))
    }

    @Test
    fun `corrupt payload loads as absent instead of throwing`() = runTest {
        val store = JsonLabyrinthBatchCheckpointStore(read = { "{not json" }, write = { _, _ -> })
        assertNull(store.load(1L))
    }
}
