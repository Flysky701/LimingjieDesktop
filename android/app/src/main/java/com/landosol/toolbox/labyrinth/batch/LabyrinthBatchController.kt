package com.landosol.toolbox.labyrinth.batch

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Outcome of the client-session invalidation step. */
sealed interface LabyrinthBatchInvalidationResult {
    /** [action] names the invalidation trigger used, for the checkpoint and the debug bundle. */
    data class Success(val action: String) : LabyrinthBatchInvalidationResult
    data class Failure(val message: String) : LabyrinthBatchInvalidationResult
}

/** Outcome of the network reroll requested by the batch. */
sealed interface LabyrinthBatchRerollResult {
    data class Success(val enterId: Long) : LabyrinthBatchRerollResult
    data class Failure(val message: String) : LabyrinthBatchRerollResult
}

/** Fresh server-side validation of a one-shot opening selected by the UI. */
sealed interface LabyrinthBatchReusableOpeningCheck {
    data object Valid : LabyrinthBatchReusableOpeningCheck

    /** The opening was consumed or replaced; the batch may safely fall back to its normal reroll. */
    data class Stale(val message: String) : LabyrinthBatchReusableOpeningCheck

    /** The server could not be queried, so changing or entering anything would be unsafe. */
    data class Failure(val message: String) : LabyrinthBatchReusableOpeningCheck
}

/**
 * Ports the batch controller depends on. Each is a thin, suspendable boundary over an existing
 * component so the sequencing can be tested without capture, recognition or the network.
 */
interface LabyrinthBatchPorts {
    /** Runs the network reroll for [guildId] / [difficulty] and returns the new enterId. */
    suspend fun reroll(accountId: Long, guildId: Int, difficulty: Int): LabyrinthBatchRerollResult

    /** Re-reads the server immediately before a previously read opening is consumed. */
    suspend fun verifyReusableOpening(
        accountId: Long,
        opening: LabyrinthBatchReusableOpening,
    ): LabyrinthBatchReusableOpeningCheck

    /**
     * Makes the client drop its stale local labyrinth state and navigate back to the labyrinth
     * home ready for a fresh entry. [accountId] is the batch's account, so state the reroll left
     * behind for another account is never acted on.
     */
    suspend fun invalidateClientSessionAndReturn(accountId: Long): LabyrinthBatchInvalidationResult

    /** Starts one labyrinth run. Returns a stable run id, or null if the run could not start. */
    suspend fun startRun(accountId: Long, guildId: Int, runId: String): Boolean

    /** Stops a run that the batch is abandoning (pause/stop). Capture must be left alive. */
    suspend fun stopRun(reason: String)

    suspend fun saveCheckpoint(checkpoint: LabyrinthBatchCheckpoint)
}

/** Explicit reasons the batch cannot continue; surfaced verbatim to the user. */
enum class LabyrinthBatchHaltReason {
    CAPTURE_LOST,
    ACCESSIBILITY_LOST,
    REROLL_FAILED,
    REUSABLE_OPENING_VALIDATION_FAILED,
    CLIENT_INVALIDATION_FAILED,
    RUN_START_FAILED,
    FATAL_RUN,
    USER_STOPPED,
}

/**
 * Sequences many labyrinth runs. Owns nothing but the checkpoint.
 *
 * The controller never touches capture, never taps, and never guesses a run outcome. It reacts
 * to two inputs only: the operator (start/pause/stop) and [LabyrinthRunTerminalEvent] delivered
 * by the single-run FSM. Everything else is delegated through [LabyrinthBatchPorts].
 *
 * Every irreversible step persists the checkpoint before and after so a process restart can
 * report precisely where it stopped.
 */
class LabyrinthBatchController(
    private val ports: LabyrinthBatchPorts,
    private val clock: () -> Long = System::currentTimeMillis,
    private val runIdFactory: (LabyrinthBatchCheckpoint) -> String = { checkpoint ->
        "${checkpoint.batchId}-run${(checkpoint.runsStarted + 1).toString().padStart(3, '0')}"
    },
) {
    private val mutex = Mutex()
    private val _state = MutableStateFlow<LabyrinthBatchCheckpoint?>(null)
    val state: StateFlow<LabyrinthBatchCheckpoint?> = _state.asStateFlow()

    private val _haltReason = MutableStateFlow<LabyrinthBatchHaltReason?>(null)
    val haltReason: StateFlow<LabyrinthBatchHaltReason?> = _haltReason.asStateFlow()

    /**
     * Starts a fresh batch. By default the first cycle rerolls immediately. When the caller has
     * just read and confirmed [reusableOpening], the matching first goal may start from that
     * opening without another reroll. It is still revalidated and the client is synchronized
     * before the route executor starts.
     */
    suspend fun start(
        batchId: String,
        accountId: Long,
        goals: List<LabyrinthBatchGoal>,
        difficulty: Int,
        reusableOpening: LabyrinthBatchReusableOpening? = null,
    ): Boolean = mutex.withLock {
        if (_state.value?.stage in ACTIVE_STAGES) return false
        _haltReason.value = null
        val initial = LabyrinthBatchCheckpoint(
            batchId = batchId,
            accountId = accountId,
            goals = goals,
            currentDifficulty = difficulty,
            updatedAt = clock(),
        ).advanceGoal(clock())
        persist(initial)
        if (initial.stage == LabyrinthBatchStage.COMPLETED) return true
        val opening = reusableOpening?.takeIf { candidate ->
            initial.activeGoal?.let { candidate.matches(it, difficulty) } == true
        }
        if (opening == null) {
            runCycle(initial)
        } else {
            when (val checked = ports.verifyReusableOpening(accountId, opening)) {
                LabyrinthBatchReusableOpeningCheck.Valid -> startVerifiedOpening(initial, opening)
                is LabyrinthBatchReusableOpeningCheck.Stale -> runCycle(
                    initial.copy(
                        message = "已读取开局不可再复用（${checked.message}），转入正常刷开局",
                        updatedAt = clock(),
                    ),
                )
                is LabyrinthBatchReusableOpeningCheck.Failure -> halt(
                    initial,
                    LabyrinthBatchHaltReason.REUSABLE_OPENING_VALIDATION_FAILED,
                    "无法重新确认已读取开局：${checked.message}",
                )
            }
        }
        true
    }

    /**
     * Resumes a persisted batch. Only stages that are safe to re-enter are resumed
     * automatically; a batch that died mid-run is reported, not replayed.
     */
    suspend fun resume(checkpoint: LabyrinthBatchCheckpoint): Boolean = mutex.withLock {
        if (_state.value?.stage in ACTIVE_STAGES) return false
        _haltReason.value = null
        when (checkpoint.stage) {
            LabyrinthBatchStage.IDLE,
            LabyrinthBatchStage.PAUSED,
            LabyrinthBatchStage.RECORDING_RESULT,
            -> {
                val next = checkpoint.advanceGoal(clock())
                persist(next)
                if (next.stage != LabyrinthBatchStage.COMPLETED) runCycle(next)
                true
            }
            LabyrinthBatchStage.REROLLING,
            LabyrinthBatchStage.INVALIDATING_OLD_CLIENT_SESSION,
            LabyrinthBatchStage.RUNNING_LABYRINTH,
            -> {
                // Unknown client/server state; require the operator to look before repeating
                // an enter, retire or tap.
                persist(
                    checkpoint.copy(
                        stage = LabyrinthBatchStage.PAUSED,
                        message = "上次批量任务停在 ${checkpoint.stage.name}（run=${checkpoint.currentRunId}），" +
                            "需要人工确认客户端状态后再继续",
                        updatedAt = clock(),
                    ),
                )
                false
            }
            LabyrinthBatchStage.COMPLETED,
            LabyrinthBatchStage.FAILED,
            -> {
                _state.value = checkpoint
                false
            }
        }
    }

    /** Delivered by the single-run FSM when a run ends. Idempotent on runId. */
    suspend fun onRunTerminal(event: LabyrinthRunTerminalEvent) = mutex.withLock {
        val current = _state.value ?: return@withLock
        if (current.stage != LabyrinthBatchStage.RUNNING_LABYRINTH) return@withLock
        if (current.currentRunId != event.runId) return@withLock
        if (current.lastRecordedRunId == event.runId) return@withLock

        val recorded = current.recordOutcome(event.runId, event.outcome, clock())
        persist(recorded)

        when (event) {
            is LabyrinthRunTerminalEvent.UserStopped -> halt(recorded, LabyrinthBatchHaltReason.USER_STOPPED, "用户停止")
            is LabyrinthRunTerminalEvent.FatalUnknown -> halt(recorded, LabyrinthBatchHaltReason.FATAL_RUN, event.reason)
            is LabyrinthRunTerminalEvent.Cleared,
            is LabyrinthRunTerminalEvent.FailedMaxRetry,
            -> {
                val advanced = recorded.advanceGoal(clock())
                persist(advanced)
                if (advanced.stage != LabyrinthBatchStage.COMPLETED) runCycle(advanced)
            }
        }
    }

    suspend fun pause(reason: String) = mutex.withLock {
        val current = _state.value ?: return@withLock
        if (current.stage !in ACTIVE_STAGES) return@withLock
        if (current.stage == LabyrinthBatchStage.RUNNING_LABYRINTH) ports.stopRun(reason)
        persist(current.copy(stage = LabyrinthBatchStage.PAUSED, message = reason, updatedAt = clock()))
    }

    suspend fun stop(reason: String) = mutex.withLock {
        val current = _state.value ?: return@withLock
        if (current.stage == LabyrinthBatchStage.RUNNING_LABYRINTH) ports.stopRun(reason)
        halt(current, LabyrinthBatchHaltReason.USER_STOPPED, reason)
    }

    /** External safety signal (capture revoked, accessibility gone). Never widens actions. */
    suspend fun haltForEnvironment(reason: LabyrinthBatchHaltReason, message: String) = mutex.withLock {
        val current = _state.value ?: return@withLock
        if (current.stage !in ACTIVE_STAGES) return@withLock
        if (current.stage == LabyrinthBatchStage.RUNNING_LABYRINTH) ports.stopRun(message)
        persist(current.copy(stage = LabyrinthBatchStage.PAUSED, message = message, updatedAt = clock()))
        _haltReason.value = reason
    }

    // ---------------------------------------------------------------------------------------------

    /**
     * One cycle: reroll → invalidate stale client → start run. Ends in RUNNING_LABYRINTH and then
     * waits for [onRunTerminal]; or in PAUSED/FAILED with a halt reason.
     */
    private suspend fun runCycle(start: LabyrinthBatchCheckpoint) {
        val goal = start.activeGoal ?: run {
            halt(start, LabyrinthBatchHaltReason.FATAL_RUN, "没有活动目标")
            return
        }

        // 1. Reroll on the server.
        var checkpoint = start.copy(
            stage = LabyrinthBatchStage.REROLLING,
            currentGuildId = goal.guildId,
            currentRunOutcome = null,
            rerollCompletedForNextRun = false,
            clientSessionNeedsInvalidation = false,
            message = "正在刷取 ${goal.guildId} 的开局",
            updatedAt = clock(),
        )
        persist(checkpoint)
        val reroll = ports.reroll(checkpoint.accountId, goal.guildId, checkpoint.currentDifficulty)
        checkpoint = when (reroll) {
            is LabyrinthBatchRerollResult.Failure -> {
                halt(checkpoint, LabyrinthBatchHaltReason.REROLL_FAILED, reroll.message)
                return
            }
            is LabyrinthBatchRerollResult.Success -> checkpoint.copy(
                currentEnterId = reroll.enterId,
                rerollCompletedForNextRun = true,
                clientSessionNeedsInvalidation = true,
                updatedAt = clock(),
            )
        }
        persist(checkpoint)

        // 2. The client still holds the previous run's local state; force it to reload.
        checkpoint = checkpoint.copy(
            stage = LabyrinthBatchStage.INVALIDATING_OLD_CLIENT_SESSION,
            message = "刷取成功，正在使旧客户端会话失效",
            updatedAt = clock(),
        )
        persist(checkpoint)
        val invalidationAction = when (val invalidation = ports.invalidateClientSessionAndReturn(checkpoint.accountId)) {
            is LabyrinthBatchInvalidationResult.Failure -> {
                halt(
                    checkpoint,
                    LabyrinthBatchHaltReason.CLIENT_INVALIDATION_FAILED,
                    "旧客户端会话未能失效并返回：${invalidation.message}",
                )
                return
            }
            is LabyrinthBatchInvalidationResult.Success -> invalidation.action
        }
        checkpoint = checkpoint.copy(
            clientSessionNeedsInvalidation = false,
            lastSessionInvalidationAction = invalidationAction,
            updatedAt = clock(),
        )
        persist(checkpoint)

        // 3. Start the run. RUNNING is persisted before the start call so a crash during start
        //    resumes as "needs operator confirmation" rather than a second enter.
        val runId = runIdFactory(checkpoint)
        checkpoint = checkpoint.copy(
            stage = LabyrinthBatchStage.RUNNING_LABYRINTH,
            currentRunId = runId,
            runsStarted = checkpoint.runsStarted + 1,
            message = "第 ${checkpoint.runsStarted + 1} 局开始：${goal.guildId}",
            updatedAt = clock(),
        )
        persist(checkpoint)
        val started = ports.startRun(checkpoint.accountId, goal.guildId, runId)
        if (!started) {
            halt(checkpoint, LabyrinthBatchHaltReason.RUN_START_FAILED, "无法启动单局识别")
        }
    }

    /** Starts the first batch run from the opening explicitly verified by the user in step 1. */
    private suspend fun startVerifiedOpening(
        start: LabyrinthBatchCheckpoint,
        opening: LabyrinthBatchReusableOpening,
    ) {
        val goal = start.activeGoal ?: run {
            halt(start, LabyrinthBatchHaltReason.FATAL_RUN, "没有活动目标")
            return
        }
        var checkpoint = start.copy(
            stage = LabyrinthBatchStage.INVALIDATING_OLD_CLIENT_SESSION,
            currentEnterId = opening.enterId,
            currentGuildId = goal.guildId,
            currentRunOutcome = null,
            rerollCompletedForNextRun = false,
            clientSessionNeedsInvalidation = true,
            message = "已确认首轮开局，正在同步客户端状态",
            updatedAt = clock(),
        )
        persist(checkpoint)
        val synchronizationAction = when (val sync = ports.invalidateClientSessionAndReturn(checkpoint.accountId)) {
            is LabyrinthBatchInvalidationResult.Failure -> {
                halt(
                    checkpoint,
                    LabyrinthBatchHaltReason.CLIENT_INVALIDATION_FAILED,
                    "已读取开局有效，但客户端状态未能同步：${sync.message}",
                )
                return
            }
            is LabyrinthBatchInvalidationResult.Success -> sync.action
        }
        val runId = runIdFactory(checkpoint)
        checkpoint = checkpoint.copy(
            stage = LabyrinthBatchStage.RUNNING_LABYRINTH,
            currentRunId = runId,
            clientSessionNeedsInvalidation = false,
            lastSessionInvalidationAction = synchronizationAction,
            runsStarted = start.runsStarted + 1,
            reusedVerifiedOpeningForFirstRun = true,
            message = "首轮已重新确认并同步客户端：${goal.guildId}",
            updatedAt = clock(),
        )
        persist(checkpoint)
        val started = ports.startRun(checkpoint.accountId, goal.guildId, runId)
        if (!started) {
            halt(checkpoint, LabyrinthBatchHaltReason.RUN_START_FAILED, "无法接续已读取的当前开局")
        }
    }

    private suspend fun halt(checkpoint: LabyrinthBatchCheckpoint, reason: LabyrinthBatchHaltReason, message: String) {
        val terminalStage = when (reason) {
            LabyrinthBatchHaltReason.USER_STOPPED,
            LabyrinthBatchHaltReason.CAPTURE_LOST,
            LabyrinthBatchHaltReason.ACCESSIBILITY_LOST,
            -> LabyrinthBatchStage.PAUSED
            else -> LabyrinthBatchStage.FAILED
        }
        persist(checkpoint.copy(stage = terminalStage, message = message, updatedAt = clock()))
        _haltReason.value = reason
    }

    private suspend fun persist(checkpoint: LabyrinthBatchCheckpoint) {
        _state.value = checkpoint
        ports.saveCheckpoint(checkpoint)
    }

    private companion object {
        val ACTIVE_STAGES = setOf(
            LabyrinthBatchStage.REROLLING,
            LabyrinthBatchStage.INVALIDATING_OLD_CLIENT_SESSION,
            LabyrinthBatchStage.RUNNING_LABYRINTH,
            LabyrinthBatchStage.RECORDING_RESULT,
        )
    }
}
