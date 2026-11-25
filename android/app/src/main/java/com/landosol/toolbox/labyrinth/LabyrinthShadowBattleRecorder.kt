package com.landosol.toolbox.labyrinth

import com.landosol.toolbox.labyrinth.kernel.KernelRuntime
import com.landosol.toolbox.labyrinth.kernel.strategy.BattleValueContext
import com.landosol.toolbox.labyrinth.kernel.strategy.ObservedBattleResult
import com.landosol.toolbox.labyrinth.kernel.strategy.ShadowBattleSample
import com.landosol.toolbox.labyrinth.kernel.strategy.recordBattleSample
import com.landosol.toolbox.labyrinth.kernel.strategy.shadowBattleSample
import java.security.MessageDigest
import java.util.UUID

/**
 * Captures an anonymized [ShadowBattleSample] on `START_BATTLE` and emits it once on battle completion
 * (`BATTLE_RESULT` win = 0, `BATTLE_FAILED` wipe = 1, battle wait timeout = 2).
 */
class LabyrinthShadowBattleRecorder {
    private data class PendingBattleAttempt(
        val context: BattleValueContext,
        val teamUnitIds: List<Int>,
        val sampleId: UUID,
        val roundId: UUID,
        val canonicalWaveSig: String,
        val bucket: String,
        val labyrinthGuildId: Int,
        val startedAtMillis: Long,
        val limitSeconds: Int,
    )

    private var pendingAttempt: PendingBattleAttempt? = null
    private var activeRoundId: UUID? = null

    @Synchronized
    fun hasPendingAttempt(): Boolean = pendingAttempt != null

    @Synchronized
    fun cancelPendingAttempt() {
        pendingAttempt = null
    }

    @Synchronized
    fun reset() {
        pendingAttempt = null
        activeRoundId = null
    }

    @Synchronized
    fun onNodeTransition() {
        pendingAttempt = null
        activeRoundId = null
    }

    @Synchronized
    fun beginAttempt(
        context: BattleValueContext,
        teamUnitIds: List<Int>,
        kind: LabyrinthCombatKind,
        exEncounterId: String?,
        labyrinthGuildId: Int,
        startedAtMillis: Long,
        limitSeconds: Int = DEFAULT_BATTLE_LIMIT_SECONDS,
    ): Boolean {
        val ownedIds = context.ownedUnits.mapTo(hashSetOf()) { it.unitId }
        val normalizedTeam = teamUnitIds.distinct()
        if (normalizedTeam.size !in 1..5 || normalizedTeam.size != teamUnitIds.size || !ownedIds.containsAll(normalizedTeam) || limitSeconds <= 0) {
            pendingAttempt = null
            return false
        }
        val roundId = activeRoundId ?: UUID.randomUUID().also { activeRoundId = it }
        pendingAttempt = PendingBattleAttempt(
            context = context,
            teamUnitIds = normalizedTeam,
            sampleId = UUID.randomUUID(),
            roundId = roundId,
            canonicalWaveSig = canonicalWaveSignature(context, kind, exEncounterId),
            bucket = shadowBattleBucket(kind, context.area, context.difficulty, exEncounterId),
            labyrinthGuildId = labyrinthGuildId,
            startedAtMillis = startedAtMillis,
            limitSeconds = limitSeconds,
        )
        return true
    }

    @Synchronized
    fun completeAttempt(
        resultCode: Int,
        observedAtMillis: Long,
        runtime: KernelRuntime,
    ): ShadowBattleSample? {
        val pending = pendingAttempt ?: return null
        pendingAttempt = null
        if (resultCode !in 0..2) return null
        val elapsedSec = ((observedAtMillis - pending.startedAtMillis).coerceAtLeast(0L) / 1000.0)
            .coerceIn(0.0, pending.limitSeconds.toDouble())
        val battleFrames = (elapsedSec * 60.0).toInt().coerceIn(0, pending.limitSeconds * 60)
        val observed = ObservedBattleResult(
            result = resultCode,
            battleFrames = battleFrames,
            limitSeconds = pending.limitSeconds,
            damageRatio = if (resultCode == 0) 1.0 else 0.0,
            totalDamage = 0L,
        )
        val sample = shadowBattleSample(
            context = pending.context,
            team = pending.teamUnitIds,
            observed = observed,
            sampleId = pending.sampleId,
            roundId = pending.roundId,
            canonicalWaveSig = pending.canonicalWaveSig,
            bucket = pending.bucket,
            labyrinthGuildId = pending.labyrinthGuildId,
        )
        runtime.recordBattleSample(sample)
        return sample
    }

    companion object {
        const val DEFAULT_BATTLE_LIMIT_SECONDS = 90

        internal fun shadowBattleBucket(
            kind: LabyrinthCombatKind,
            area: Int,
            difficulty: Int,
            exEncounterId: String?,
        ): String = when (kind) {
            LabyrinthCombatKind.NORMAL -> "normal"
            LabyrinthCombatKind.BOSS -> if (difficulty == 5 && area > 0) "boss_${area}f_d5" else "boss_other"
            LabyrinthCombatKind.EX -> when {
                exEncounterId == null -> "other_ex"
                "robot" in exEncounterId || "lima" in exEncounterId -> "robot_ex"
                "dual" in exEncounterId || "twin" in exEncounterId || "double" in exEncounterId -> "special_dual_ex"
                else -> "other_ex"
            }
        }

        internal fun canonicalWaveSignature(
            context: BattleValueContext,
            kind: LabyrinthCombatKind,
            exEncounterId: String?,
        ): String {
            val raw = "quest:${context.questId}|area:${context.area}|diff:${context.difficulty}|kind:${kind.name}|ex:${exEncounterId.orEmpty()}"
            return MessageDigest.getInstance("SHA-256")
                .digest(raw.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
        }
    }
}

internal fun labyrinthShouldRecordShadowBattleAttempt(
    kind: LabyrinthCombatKind?,
    bossTeamMode: LabyrinthBossTeamMode,
    plannedTeams: Int,
    committedTeamCount: Int,
    bossFallbackMultiTeamActive: Boolean,
    bossEditorTeamIndex: Int?,
    combatTeamIndex: Int,
): Boolean {
    if (kind == null) return false
    if (kind != LabyrinthCombatKind.BOSS) return true
    val sequentialBossContinuation = bossEditorTeamIndex == null && combatTeamIndex > 1
    return bossTeamMode == LabyrinthBossTeamMode.SINGLE_TEAM &&
        !bossFallbackMultiTeamActive &&
        !sequentialBossContinuation &&
        plannedTeams <= 1 &&
        committedTeamCount <= 1
}
