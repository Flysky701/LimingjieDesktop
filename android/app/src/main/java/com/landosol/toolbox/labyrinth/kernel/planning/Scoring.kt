package com.landosol.toolbox.labyrinth.kernel.planning

import java.math.BigInteger

data class ScoreSettings(
    val areaClearPts: Map<Int, Int>,
    val blockPts: Map<Int, Int>,
    val scoreRates: Map<Int, Int>,
    val unitPts: Int,
    val relicPts: Int,
    val normalPts: Int,
    val exPts: Int,
    val bossPts: Int,
    val shopRefreshCosts: List<Int> = listOf(300, 600, 1200, 2400, 4800, 9999),
) {
    companion object {
        fun currentOfficial(): ScoreSettings = ScoreSettings(
            areaClearPts = (1..5).associateWith { 500 },
            blockPts = (1..5).associateWith { 100 },
            scoreRates = listOf(100, 120, 140, 170, 200, 220, 240, 260, 280, 300)
                .mapIndexed { index, rate -> index + 1 to rate }.toMap(),
            unitPts = 100,
            relicPts = 200,
            normalPts = 100,
            exPts = 1000,
            bossPts = 1500,
        )
    }
}

sealed interface AreaClears {
    data class Count(val value: Int) : AreaClears
    data class Ids(val values: Set<Int>) : AreaClears
}

sealed interface BlockClears {
    data class Count(val value: Int) : BlockClears
    data class ByArea(val counts: Map<Int, Int>) : BlockClears
    data class AreaIds(val values: List<Int>) : BlockClears
}

data class ScoreCounts(
    val areasCleared: AreaClears = AreaClears.Count(0),
    val blocksCleared: BlockClears = BlockClears.Count(0),
    val units: Int = 0,
    val relics: Int = 0,
    val normalWins: Int = 0,
    val exWins: Int = 0,
    val bossWins: Int = 0,
) {
    fun areaIds(settings: ScoreSettings): Set<Int> = when (val areas = areasCleared) {
        is AreaClears.Count -> {
            require(areas.value in 0..settings.areaClearPts.size) { "Area count outside configured floors" }
            settings.areaClearPts.keys.sorted().take(areas.value).toSet()
        }
        is AreaClears.Ids -> areas.values
    }
}

fun baseScore(settings: ScoreSettings, counts: ScoreCounts): Long {
    val blockScore = when (val blocks = counts.blocksCleared) {
        is BlockClears.Count -> {
            val prices = settings.blockPts.values.toSet()
            require(blocks.value == 0 || prices.size == 1) {
                "Supply block area IDs or counts per area for nonuniform block points"
            }
            blocks.value.toLong() * (prices.firstOrNull() ?: 0)
        }
        is BlockClears.ByArea -> blocks.counts.entries.sumOf { (area, count) ->
            settings.blockPts.getValue(area).toLong() * count
        }
        is BlockClears.AreaIds -> blocks.values.sumOf { settings.blockPts.getValue(it).toLong() }
    }
    return counts.areaIds(settings).sumOf { settings.areaClearPts.getValue(it).toLong() } +
        blockScore + counts.units.toLong() * settings.unitPts + counts.relics.toLong() * settings.relicPts +
        counts.normalWins.toLong() * settings.normalPts + counts.exWins.toLong() * settings.exPts +
        counts.bossWins.toLong() * settings.bossPts
}

fun scalePts(settings: ScoreSettings, difficulty: Int, baseDelta: Long): Long {
    val points = scalePts(settings, difficulty, baseDelta.toBigInteger())
    check(points.bitLength() <= 63) { "Scaled score outside the Long range" }
    return points.toLong()
}

fun scalePts(settings: ScoreSettings, difficulty: Int, baseDelta: BigInteger): BigInteger {
    val product = baseDelta * settings.scoreRates.getValue(difficulty).toBigInteger()
    // Subtract the nonnegative remainder before dividing to floor signed deltas.
    val hundred = 100.toBigInteger()
    return (product - product.mod(hundred)) / hundred
}

fun computeFinalScore(settings: ScoreSettings, difficulty: Int, counts: ScoreCounts): Long =
    scalePts(settings, difficulty, baseScore(settings, counts))

internal fun gcd(a: Long, b: Long): Long {
    var left = a
    var right = b
    while (right != 0L) {
        val remainder = left % right
        left = right
        right = remainder
    }
    return left
}

fun scoreGcd(settings: ScoreSettings, difficulty: Int): Long {
    val values = sequenceOf(settings.unitPts, settings.relicPts, settings.normalPts, settings.exPts, settings.bossPts) +
        settings.areaClearPts.values.asSequence() + settings.blockPts.values.asSequence()
    val baseGcd = values.filter { it > 0 }.fold(0L) { divisor, value -> gcd(divisor, value.toLong()) }
    val product = baseGcd * settings.scoreRates.getValue(difficulty)
    return if (product % 100 == 0L) product / 100 else 1
}

fun unitDeltaPts(settings: ScoreSettings, difficulty: Int): Long =
    scalePts(settings, difficulty, settings.unitPts.toLong())

fun relicDeltaPts(settings: ScoreSettings, difficulty: Int): Long =
    scalePts(settings, difficulty, settings.relicPts.toLong())

fun normalWinDeltaPts(settings: ScoreSettings, difficulty: Int): Long =
    scalePts(settings, difficulty, settings.normalPts.toLong())

fun exWinDeltaPts(settings: ScoreSettings, difficulty: Int): Long =
    scalePts(settings, difficulty, settings.exPts.toLong())

fun bossWinDeltaPts(settings: ScoreSettings, difficulty: Int): Long =
    scalePts(settings, difficulty, settings.bossPts.toLong())

fun blockDeltaPts(settings: ScoreSettings, difficulty: Int, area: Int): Long =
    scalePts(settings, difficulty, settings.blockPts.getValue(area).toLong())

fun areaClearDeltaPts(settings: ScoreSettings, difficulty: Int, area: Int): Long =
    scalePts(settings, difficulty, settings.areaClearPts.getValue(area).toLong())

fun deltaExVsNormalPts(
    settings: ScoreSettings,
    difficulty: Int,
    extraRelics: Int = 0,
    extraUnits: Int = 0,
): Long = scalePts(
    settings, difficulty, settings.exPts.toLong() - settings.normalPts +
        extraRelics.toLong() * settings.relicPts + extraUnits.toLong() * settings.unitPts,
)

data class ScoreGateResult(
    val currentScore: Long,
    val projectedClearScore: Long,
    val minimumScore: Long,
    val shortfall: Long,
) {
    val passed: Boolean get() = shortfall == 0L
    val shouldRetire: Boolean get() = shortfall > 0L
}

fun scoreCheckGate(
    settings: ScoreSettings,
    difficulty: Int,
    counts: ScoreCounts,
    minimumScore: Long,
    finalArea: Int = settings.areaClearPts.keys.max(),
): ScoreGateResult {
    val current = baseScore(settings, counts)
    var pending = settings.bossPts.toLong() + settings.blockPts.getValue(finalArea)
    if (finalArea !in counts.areaIds(settings)) pending += settings.areaClearPts.getValue(finalArea)
    val projected = scalePts(settings, difficulty, current + pending)
    return ScoreGateResult(
        scalePts(settings, difficulty, current), projected, minimumScore,
        (minimumScore - projected).coerceAtLeast(0),
    )
}

const val REVIVE_RELIC_ID = 50002

data class ReviveState(
    val hasRelic: Boolean = false,
    val remainingCharges: Int = 0,
    val usedCount: Int = 0,
) {
    companion object {
        fun fromRelics(relicIds: Iterable<Int>, usedCount: Int = 0): ReviveState {
            require(usedCount >= 0) { "Used count must be nonnegative" }
            val present = REVIVE_RELIC_ID in relicIds
            return ReviveState(present, if (present && usedCount == 0) 1 else 0, usedCount)
        }
    }

    fun canPreventDefeat(isBoss: Boolean): Boolean = hasRelic && remainingCharges > 0 && !isBoss

    fun applyBattleLoss(isBoss: Boolean): ReviveState =
        if (canPreventDefeat(isBoss)) copy(remainingCharges = 0, usedCount = usedCount + 1) else this
}
