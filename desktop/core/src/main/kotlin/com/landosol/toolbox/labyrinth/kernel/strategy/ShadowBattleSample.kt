package com.landosol.toolbox.labyrinth.kernel.strategy

import com.landosol.toolbox.labyrinth.kernel.BattleSamplePayload
import com.landosol.toolbox.labyrinth.kernel.KernelDomain
import com.landosol.toolbox.labyrinth.kernel.KernelEventKinds
import com.landosol.toolbox.labyrinth.kernel.KernelRuntime
import java.util.UUID
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Raw result codes follow the simulator: victory=0, defeat=1, time over=2. */
data class ObservedBattleResult(
    val result: Int,
    val battleFrames: Int,
    val limitSeconds: Int,
    val damageRatio: Double,
    val totalDamage: Long,
    val enemyFinalHps: List<Long> = emptyList(),
    val seed: Int = -1, // A live battle generally has no observed simulator seed.
    val hasExactFrames: Boolean = true,
) {
    init {
        require(result in 0..2 && battleFrames >= 0 && limitSeconds > 0)
        require(damageRatio in 0.0..1.0 && totalDamage >= 0 && enemyFinalHps.all { it >= 0 })
    }
}

@Serializable
data class ShadowBattleRun(
    val seed: Int, val result: Int,
    @SerialName("outcome_class") val outcomeClass: Int,
    @SerialName("battle_frames") val battleFrames: Int,
    @SerialName("limit_time") val limitTime: Int,
    @SerialName("elapsed_sec") val elapsedSec: Double,
    @SerialName("rem_time_ratio") val remTimeRatio: Double,
    @SerialName("total_damage") val totalDamage: Long,
    @SerialName("damage_ratio") val damageRatio: Double,
    @SerialName("enemy_final_hps") val enemyFinalHps: List<Long>,
)

@Serializable
class ShadowBattleSample internal constructor(
    @SerialName("sample_id") val sampleId: String,
    @SerialName("round_id") val roundId: String,
    val source: String,
    @SerialName("db_version") val dbVersion: String,
    @SerialName("quest_id") val questId: Int,
    @SerialName("canonical_wave_sig") val canonicalWaveSig: String,
    val bucket: String, val area: Int, val difficulty: Int,
    @SerialName("guild_id") val labyrinthGuildId: Int,
    @SerialName("team_unit_ids") val teamUnitIds: List<Int>,
    @SerialName("owned_unit_ids") val ownedUnitIds: List<Int>,
    @SerialName("owned_unit_count") val ownedUnitCount: Int,
    @SerialName("owned_role_counts") val ownedRoleCounts: List<Int>,
    @SerialName("owned_talent_counts") val ownedTalentCounts: List<Int>,
    @SerialName("relic_ids") val relicIds: List<Int>,
    @SerialName("mark_stacks") val markStacks: List<Int>,
    @SerialName("alpha_rupee") val alphaRupee: Int?,
    @SerialName("revive_available") val reviveAvailable: Boolean,
    val runs: List<ShadowBattleRun>,
    @SerialName("outcome_counts") val outcomeCounts: List<Int>,
    @SerialName("mean_damage_ratio") val meanDamageRatio: Double,
    @SerialName("mean_win_rem_time") val meanWinRemTime: Double?,
)

/** Only catalog facts, anonymous local UUIDs and numeric battle telemetry cross the journal boundary. */
fun shadowBattleSample(
    context: BattleValueContext, team: List<Int>, observed: ObservedBattleResult,
    sampleId: UUID, roundId: UUID, canonicalWaveSig: String, bucket: String,
    labyrinthGuildId: Int = 0, dbVersion: String = "",
): ShadowBattleSample {
    require(canonicalWaveSig.matches(Regex("[a-f0-9]{64}")))
    require(bucket.matches(Regex("normal|special_dual_ex|robot_ex|other_ex|boss_other|boss_[0-9]+f_d5")))
    require(dbVersion.isEmpty() || dbVersion.matches(Regex("[0-9]+([._-][0-9]+)*")))
    val owned = context.ownedUnits.map { it.unitId }
    require(team.size in 1..5 && team.distinct().size == team.size && owned.containsAll(team))
    val outcome = when {
        observed.result == 0 -> 0
        observed.result == 2 || (observed.hasExactFrames && observed.battleFrames >= observed.limitSeconds * 60L - 2) -> 1
        else -> 2
    }
    val elapsed = observed.battleFrames / 60.0
    val remaining = (1.0 - elapsed / observed.limitSeconds).coerceIn(0.0, 1.0)
    val run = ShadowBattleRun(observed.seed, observed.result, outcome, observed.battleFrames, observed.limitSeconds,
        elapsed, remaining, observed.totalDamage, observed.damageRatio, observed.enemyFinalHps.toList())
    return ShadowBattleSample(sampleId.toString(), roundId.toString(), "live", dbVersion, context.questId,
        canonicalWaveSig, bucket, context.area, context.difficulty, labyrinthGuildId, team.toList(), owned, owned.size,
        context.roleCounts(), context.talentCounts(), context.relicIds.toList(), List(5) { context.markStacks.getOrElse(it) { 0 } },
        context.alphaRupee, context.reviveAvailable, listOf(run), List(3) { if (it == outcome) 1 else 0 },
        observed.damageRatio, remaining.takeIf { outcome == 0 })
}

/** The runtime supplies the journal's sequence and trace. Only call once the observed labels are available. */
fun KernelRuntime.recordBattleSample(
    sample: ShadowBattleSample,
    damageObserved: Boolean = true,
    timeSource: String = "sim",
    reviveTriggered: Boolean = false,
) = emit(
    KernelDomain.BATTLE,
    KernelEventKinds.BATTLE_SAMPLE_OBSERVED,
    BattleSamplePayload(sample, damageObserved, timeSource, reviveTriggered),
)
