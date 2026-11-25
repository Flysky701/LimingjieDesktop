package com.landosol.toolbox.labyrinth

import com.landosol.toolbox.labyrinth.vision.LabyrinthEntryPageState

internal enum class LabyrinthBattleWaitDecision { NONE, WAIT, TIMED_OUT }

/**
 * Progress-aware bounded battle deadline:
 * - Static or unconfirmed battle frames keep the 5-minute base ceiling so frozen emulators or
 *   unrecognized overlays cannot stall a batch.
 * - Positively recognized BATTLE_IN_PROGRESS frames whose top-HUD digest advances extend the
 *   deadline by 60s beyond the latest HUD progress up to a hard cap of 5m + 3m * plannedTeams
 *   (8m for 1-team normal/EX/Boss, 11m for 2-team Boss, 14m for 3-team Boss).
 */
internal class LabyrinthBattleWaitPolicy {
    private var startedAt: Long? = null
    private var startPageGrace = false
    private var plannedTeams: Int = 1
    private var lastHudDigest: Long? = null
    private var lastHudProgressAt: Long? = null

    @Synchronized
    fun onStartExecuted(now: Long, plannedTeams: Int = 1) {
        this.plannedTeams = plannedTeams.coerceIn(1, MAX_PLANNED_TEAMS)
        if (startedAt == null) {
            startedAt = now
            startPageGrace = true
            lastHudDigest = null
            lastHudProgressAt = null
        }
    }

    @Synchronized
    fun observe(
        page: LabyrinthEntryPageState,
        now: Long,
        battleResultNextReady: Boolean = false,
        hudDigest: Long? = null,
        plannedTeams: Int? = null,
    ): LabyrinthBattleWaitDecision {
        if (plannedTeams != null) {
            this.plannedTeams = plannedTeams.coerceIn(1, MAX_PLANNED_TEAMS)
        }
        if (page == LabyrinthEntryPageState.BATTLE_IN_PROGRESS && startedAt == null) {
            // Also support resuming automation during an already recognized battle.
            startedAt = now
            lastHudDigest = null
            lastHudProgressAt = null
        }
        val start = startedAt ?: return LabyrinthBattleWaitDecision.NONE
        val elapsed = (now - start).coerceAtLeast(0L)
        val waitingPage = when (page) {
            LabyrinthEntryPageState.UNKNOWN,
            LabyrinthEntryPageState.GAME_LOADING_PROGRESS,
            LabyrinthEntryPageState.PRE_HOME_DATA_LOADING,
            LabyrinthEntryPageState.BATTLE_IN_PROGRESS -> true
            // WIN can classify as BATTLE_RESULT before its “下一步” button is drawn. Preserve
            // the battle deadline through that transient state so the following UNKNOWN frames
            // remain under battle waiting instead of reaching the generic 30-second stop.
            LabyrinthEntryPageState.BATTLE_RESULT -> !battleResultNextReady
            // Permit a short stale editor frame after injection, but do not hide a failed start.
            LabyrinthEntryPageState.BATTLE_TEAM_SELECTION -> startPageGrace && elapsed < START_PAGE_GRACE_MILLIS
            else -> false
        }
        if (!waitingPage) {
            reset()
            return LabyrinthBattleWaitDecision.NONE
        }
        if (page != LabyrinthEntryPageState.BATTLE_TEAM_SELECTION) startPageGrace = false
        if (page == LabyrinthEntryPageState.BATTLE_IN_PROGRESS && hudDigest != null) {
            val previous = lastHudDigest
            if (previous == null) {
                lastHudDigest = hudDigest
            } else if (previous != hudDigest) {
                lastHudDigest = hudDigest
                lastHudProgressAt = now
            }
        }
        return if (elapsed >= effectiveTimeoutMillis()) LabyrinthBattleWaitDecision.TIMED_OUT
        else LabyrinthBattleWaitDecision.WAIT
    }

    @Synchronized
    fun effectiveLimitMinutes(): Int =
        (effectiveTimeoutMillis() / 60_000L).toInt().coerceAtLeast(5)

    @Synchronized
    fun currentMaxMinutes(): Int {
        val millis = if (lastHudProgressAt != null) maxCeilingMillis() else BASE_TIMEOUT_MILLIS
        return (millis / 60_000L).toInt()
    }

    private fun maxCeilingMillis(): Long =
        BASE_TIMEOUT_MILLIS + PER_TEAM_EXTENSION_MILLIS * plannedTeams

    private fun effectiveTimeoutMillis(): Long {
        val start = startedAt ?: return BASE_TIMEOUT_MILLIS
        val progressAt = lastHudProgressAt ?: return BASE_TIMEOUT_MILLIS
        val progressElapsed = (progressAt - start).coerceAtLeast(0L)
        return (progressElapsed + HUD_STALL_TIMEOUT_MILLIS).coerceIn(BASE_TIMEOUT_MILLIS, maxCeilingMillis())
    }

    @Synchronized
    fun reset() {
        startedAt = null
        startPageGrace = false
        plannedTeams = 1
        lastHudDigest = null
        lastHudProgressAt = null
    }

    companion object {
        const val BASE_TIMEOUT_MILLIS = 300_000L
        const val PER_TEAM_EXTENSION_MILLIS = 180_000L
        const val HUD_STALL_TIMEOUT_MILLIS = 60_000L
        const val START_PAGE_GRACE_MILLIS = 3_000L
        const val MAX_PLANNED_TEAMS = 3
    }
}
