package com.landosol.toolbox.labyrinth.batch

import kotlinx.serialization.Serializable

/**
 * Batch-level orchestration model for unattended multi-run labyrinth execution.
 *
 * These types are deliberately pure Kotlin: no Android, no capture, no recognition. The batch
 * controller only sequences three existing, independently owned lifetimes:
 *
 * ```
 * Capture lifetime   — the whole batch; owned by MediaProjectionCaptureService, never touched here
 * Batch lifetime     — every goal in [LabyrinthBatchCheckpoint.goals]
 * Run lifetime       — one labyrinth entry; owned by LabyrinthEntryRecognitionSession
 * ```
 */
enum class LabyrinthBatchGoalMode {
    /** Any run that reaches a terminal outcome counts, cleared or not. */
    ATTEMPTS,

    /** Only a cleared run counts. */
    CLEARS,
}

@Serializable
data class LabyrinthBatchGoal(
    val guildId: Int,
    val targetCount: Int,
    val mode: LabyrinthBatchGoalMode,
    val completedCount: Int = 0,
    val failedCount: Int = 0,
) {
    init {
        require(targetCount > 0) { "targetCount must be positive" }
        require(completedCount >= 0 && failedCount >= 0)
    }

    val isSatisfied: Boolean get() = completedCount >= targetCount
}

enum class LabyrinthBatchStage {
    IDLE,
    REROLLING,
    INVALIDATING_OLD_CLIENT_SESSION,
    RUNNING_LABYRINTH,
    RECORDING_RESULT,
    COMPLETED,
    PAUSED,
    FAILED,
}

/**
 * A server opening that was explicitly read and confirmed before starting a batch.
 *
 * The batch may reuse it for the first run only when its guild and difficulty match the first
 * active goal. Later runs always return to the normal reroll/invalidation cycle.
 */
data class LabyrinthBatchReusableOpening(
    val enterId: Long,
    val guildId: Int,
    val difficulty: Int,
) {
    init {
        require(enterId > 0L) { "enterId must be positive" }
    }

    fun matches(goal: LabyrinthBatchGoal, selectedDifficulty: Int): Boolean =
        guildId == goal.guildId && difficulty == selectedDifficulty
}

/** Terminal outcome of one run, as reported by the single-run FSM. */
enum class LabyrinthRunOutcome {
    CLEARED,
    FAILED_MAX_RETRY,
    ABORTED_UNKNOWN,
    USER_STOPPED,
}

/**
 * What the single-run FSM tells the batch when a run ends. The batch never infers an outcome
 * from UI text or from whether the session merely stopped.
 */
sealed interface LabyrinthRunTerminalEvent {
    val runId: String

    data class Cleared(override val runId: String, val summary: com.landosol.toolbox.labyrinth.LabyrinthRunSummary? = null) : LabyrinthRunTerminalEvent
    data class FailedMaxRetry(override val runId: String) : LabyrinthRunTerminalEvent
    data class FatalUnknown(override val runId: String, val reason: String) : LabyrinthRunTerminalEvent
    data class UserStopped(override val runId: String) : LabyrinthRunTerminalEvent

    val outcome: LabyrinthRunOutcome
        get() = when (this) {
            is Cleared -> LabyrinthRunOutcome.CLEARED
            is FailedMaxRetry -> LabyrinthRunOutcome.FAILED_MAX_RETRY
            is FatalUnknown -> LabyrinthRunOutcome.ABORTED_UNKNOWN
            is UserStopped -> LabyrinthRunOutcome.USER_STOPPED
        }
}

/**
 * Persisted batch state. Written before and after every irreversible step so an app restart can
 * report exactly where the batch stopped instead of repeating an enter or retire.
 *
 * [lastRecordedRunId] makes result recording idempotent: the same terminal event delivered twice
 * (a retried callback, a replayed message) must not count twice.
 */
@Serializable
data class LabyrinthBatchCheckpoint(
    val batchId: String,
    val accountId: Long,
    val goals: List<LabyrinthBatchGoal>,
    val activeGoalIndex: Int = 0,
    val stage: LabyrinthBatchStage = LabyrinthBatchStage.IDLE,
    val currentRunId: String? = null,
    val currentEnterId: Long? = null,
    val currentGuildId: Int? = null,
    val currentDifficulty: Int,
    val currentRunOutcome: LabyrinthRunOutcome? = null,
    val rerollCompletedForNextRun: Boolean = false,
    val clientSessionNeedsInvalidation: Boolean = false,
    val lastSessionInvalidationAction: String? = null,
    val lastRecordedRunId: String? = null,
    val runsStarted: Int = 0,
    val abnormalRuns: Int = 0,
    val reusedVerifiedOpeningForFirstRun: Boolean = false,
    val message: String? = null,
    val updatedAt: Long,
    val lastRunSummary: com.landosol.toolbox.labyrinth.LabyrinthRunSummary? = null,
    val lastRecordedOutcome: LabyrinthRunOutcome? = null,
) {
    init {
        require(goals.isNotEmpty()) { "a batch needs at least one goal" }
        require(activeGoalIndex in 0..goals.size)
    }

    val activeGoal: LabyrinthBatchGoal? get() = goals.getOrNull(activeGoalIndex)
    val allGoalsSatisfied: Boolean get() = goals.all(LabyrinthBatchGoal::isSatisfied)
}

/**
 * Applies one terminal outcome to the active goal. Pure and idempotent on [runId].
 *
 * Counting rules (documented in labyrinth-full-automation.md §6.1):
 * - CLEARED           → completed +1 for both modes
 * - FAILED_MAX_RETRY  → failed +1; completed +1 only in ATTEMPTS mode
 * - ABORTED_UNKNOWN   → not a normal round; tracked separately in [LabyrinthBatchCheckpoint.abnormalRuns]
 * - USER_STOPPED      → nothing counted
 */
internal fun LabyrinthBatchCheckpoint.recordOutcome(
    runId: String,
    outcome: LabyrinthRunOutcome,
    now: Long,
): LabyrinthBatchCheckpoint {
    if (lastRecordedRunId == runId) return this
    val goal = activeGoal ?: return copy(
        stage = LabyrinthBatchStage.FAILED,
        message = "没有活动目标可记录结果",
        updatedAt = now,
    )
    val updatedGoal = when (outcome) {
        LabyrinthRunOutcome.CLEARED -> goal.copy(completedCount = goal.completedCount + 1)
        LabyrinthRunOutcome.FAILED_MAX_RETRY -> goal.copy(
            failedCount = goal.failedCount + 1,
            completedCount = if (goal.mode == LabyrinthBatchGoalMode.ATTEMPTS) {
                goal.completedCount + 1
            } else {
                goal.completedCount
            },
        )
        LabyrinthRunOutcome.ABORTED_UNKNOWN,
        LabyrinthRunOutcome.USER_STOPPED,
        -> goal
    }
    val goals = goals.toMutableList().also { it[activeGoalIndex] = updatedGoal }
    return copy(
        goals = goals,
        currentRunOutcome = outcome,
        lastRecordedOutcome = outcome,
        lastRecordedRunId = runId,
        abnormalRuns = if (outcome == LabyrinthRunOutcome.ABORTED_UNKNOWN) abnormalRuns + 1 else abnormalRuns,
        stage = LabyrinthBatchStage.RECORDING_RESULT,
        updatedAt = now,
    )
}

/** Moves to the first unsatisfied goal at or after the current index; COMPLETED if none remain. */
internal fun LabyrinthBatchCheckpoint.advanceGoal(now: Long): LabyrinthBatchCheckpoint {
    val next = goals.withIndex()
        .drop(activeGoalIndex)
        .firstOrNull { (_, goal) -> !goal.isSatisfied }
        ?.index
    return if (next == null) {
        copy(
            activeGoalIndex = goals.size,
            stage = LabyrinthBatchStage.COMPLETED,
            currentGuildId = null,
            currentRunId = null,
            currentEnterId = null,
            message = "批量任务全部完成",
            updatedAt = now,
        )
    } else {
        copy(
            activeGoalIndex = next,
            currentGuildId = goals[next].guildId,
            updatedAt = now,
        )
    }
}
