package com.landosol.toolbox.labyrinth

import com.landosol.toolbox.labyrinth.vision.LabyrinthEntryPageState

internal enum class LabyrinthBossTabAck { WAIT, ACKNOWLEDGED, TIMED_OUT }
data class LabyrinthBossTabDiagnostic(
    val sourceIndex: Int, val expectedIndex: Int, val phase: String,
    val dispatchedAtMillis: Long, val feedbackAfterMillis: Long?,
    val ackFrames: Int, val lastEvidenceAtMillis: Long?, val active: Boolean = true,
)

/** One tab-switch receipt. No task waiting or extra worker thread is created. */
internal class LabyrinthBossTabReceipt(
    val sourceIndex: Int, val expectedIndex: Int, queuedAt: Long,
    private val timeoutMillis: Long = 4_000L,
) {
    private var dispatchedAt = queuedAt
    private var feedbackAfter: Long? = null
    private var phase = "DISPATCHED"
    private var lastFrameAt = Long.MIN_VALUE
    private var firstAckAt = Long.MIN_VALUE
    private var ackFrames = 0
    private var finalRecheckUsed = false
    init { require(sourceIndex in 1..3 && expectedIndex in 1..3 && sourceIndex != expectedIndex) }

    @Synchronized fun markDispatched(at: Long) { dispatchedAt = at }
    @Synchronized fun inputFinished(at: Long) {
        feedbackAfter = maxOf(dispatchedAt, at); phase = "WAITING_ACK"
    }
    @Synchronized fun rejected() { phase = "REJECTED" }
    @Synchronized fun snapshot(active: Boolean = true) = LabyrinthBossTabDiagnostic(
        sourceIndex, expectedIndex, phase, dispatchedAt, feedbackAfter, ackFrames,
        lastFrameAt.takeIf { it != Long.MIN_VALUE }, active)

    @Synchronized fun observe(page: LabyrinthEntryPageState, stable: Boolean, index: Int?, capturedAt: Long): LabyrinthBossTabAck {
        if (phase == "ACKED") return LabyrinthBossTabAck.ACKNOWLEDGED
        if (phase == "TIMED_OUT" || phase == "REJECTED") return LabyrinthBossTabAck.TIMED_OUT
        val after = feedbackAfter ?: return LabyrinthBossTabAck.WAIT
        if (capturedAt <= after || capturedAt <= lastFrameAt) return LabyrinthBossTabAck.WAIT
        lastFrameAt = capturedAt
        // Keep the original 4s deadline. The existing one-frame final recheck has only
        // two capture intervals of scheduling tolerance, never an unbounded late ACK.
        if (capturedAt - after > timeoutMillis + 600L) {
            phase = "TIMED_OUT"; return LabyrinthBossTabAck.TIMED_OUT
        }
        val valid = page == LabyrinthEntryPageState.BATTLE_TEAM_SELECTION && stable && index == expectedIndex
        if (valid) {
            if (ackFrames == 0) firstAckAt = capturedAt
            ackFrames++
        } else { ackFrames = 0; firstAckAt = Long.MIN_VALUE }
        if (ackFrames >= 2 && capturedAt - firstAckAt >= 300L) {
            phase = "ACKED"; return LabyrinthBossTabAck.ACKNOWLEDGED
        }
        if (capturedAt - after >= timeoutMillis) {
            // A target that first appears at the deadline gets only one fresh-frame recheck.
            // The original deadline is never reset, and no second tap is dispatched while rechecking.
            if (valid && ackFrames == 1 && !finalRecheckUsed) {
                finalRecheckUsed = true; phase = "RECHECK_AFTER_TIMEOUT"
                return LabyrinthBossTabAck.WAIT
            }
            phase = "TIMED_OUT"; return LabyrinthBossTabAck.TIMED_OUT
        }
        return LabyrinthBossTabAck.WAIT
    }
}

internal fun labyrinthIsBossTabSwitch(kind: LabyrinthBattleTeamExecutionKind): Boolean =
    kind == LabyrinthBattleTeamExecutionKind.NEXT_BOSS_TEAM ||
        kind == LabyrinthBattleTeamExecutionKind.RESET_BOSS_SWITCH_TEAM

internal fun labyrinthBossTabTarget(kind: LabyrinthBattleTeamExecutionKind, key: String, sourceIndex: Int?): Int? = when (kind) {
    LabyrinthBattleTeamExecutionKind.NEXT_BOSS_TEAM -> sourceIndex?.takeIf { it in 1..2 }?.plus(1)
    LabyrinthBattleTeamExecutionKind.RESET_BOSS_SWITCH_TEAM -> key.takeIf { it.startsWith("boss-reset-tab:") }
        ?.substringAfter("->", "")?.substringBefore(":")?.toIntOrNull()?.takeIf { it in 1..3 }
    else -> null
}
