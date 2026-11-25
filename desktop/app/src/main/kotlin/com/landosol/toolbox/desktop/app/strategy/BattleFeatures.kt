package com.landosol.toolbox.desktop.app.strategy

import ai.onnxruntime.OnnxJavaType
import com.landosol.toolbox.labyrinth.kernel.strategy.BattleValueContext
import kotlin.math.abs

/** One unbatched input, in row-major order. IDs are 32-bit database keys until tensorization. */
internal data class BattleFeature(val type: OnnxJavaType, val shape: List<Int>, val values: DoubleArray)

internal data class ContextFeatures(
    val inputs: Map<String, BattleFeature>,
    val rosterPosByUnitId: Map<Int, Int>,
    val relicIds: List<Int>,
    val gold: Double,
    val ownedNorm: Double,
)

private fun indices(ids: List<Int>, scalar: Boolean = false) = BattleFeature(OnnxJavaType.INT64,
    if (scalar) emptyList() else listOf(ids.size), DoubleArray(ids.size) { ids[it].toDouble() })
private fun mask(ids: List<Int>) = BattleFeature(OnnxJavaType.BOOL, listOf(ids.size),
    DoubleArray(ids.size) { (ids[it] != 0).numeric() })
private fun floats(values: List<Double>, vararg shape: Int) =
    BattleFeature(OnnxJavaType.FLOAT, shape.toList(), values.toDoubleArray())
private fun List<Int>.pad(size: Int) = List(size) { getOrElse(it) { 0 } }

internal class BattleFeaturizer(val catalog: BattleFeatureCatalog, val rosterLength: Int, val relicLength: Int) {
    init { require(rosterLength >= 1 && relicLength >= 0) }

    private fun width(id: Int) = catalog.units[id]?.width ?: 1_000_000_000
    private val positions = compareBy<Int>({ width(it) }, { it })
    private fun sortedTeam(candidate: List<Int>) = candidate.filterNot { it == 0 }.distinct().sortedWith(positions).take(5)

    fun row(context: BattleValueContext, candidate: List<Int>): Map<String, BattleFeature> {
        val owned = context.ownedUnits.map { it.unitId }
        val team = sortedTeam(candidate)
        require(team.size <= rosterLength) { "Team exceeds model roster capacity" }
        val roster = (team + owned.filterNot { it in team }.sortedWith(positions)).pad(rosterLength)
        val contextFeatures = contextFeatures(context, roster)
        return contextFeatures.inputs + teamFeatures(contextFeatures, team)
    }

    fun contextRow(context: BattleValueContext): ContextFeatures {
        val roster = context.ownedUnits.map { it.unitId }.sortedWith(positions).pad(rosterLength)
        return contextFeatures(context, roster)
    }

    fun teamRow(context: ContextFeatures, candidate: List<Int>): Map<String, BattleFeature>? {
        if (candidate.any { it != 0 && it !in context.rosterPosByUnitId }) return null
        return teamFeatures(context, sortedTeam(candidate))
    }

    private fun contextFeatures(context: BattleValueContext, roster: List<Int>): ContextFeatures {
        val catalogWave = catalog.quests[context.questId]
        val wave = context.enemyIds ?: catalogWave
            ?: throw IllegalArgumentException("Unknown quest ${context.questId} in battle catalog")
        require(catalogWave == null || wave == catalogWave) { "Observed wave differs from catalog" }
        val enemies = wave.pad(5)
        val relicIds = context.relicIds.toList()
        val relics = relicIds.pad(relicLength)
        val count = context.ownedUnits.size
        val gold = (context.alphaRupee ?: 0).coerceIn(0, 10000) / 10000.0
        val ownedNorm = minOf(count, 25) / 25.0
        val stacks = context.markStacks.pad(5)
        val roleCounts = IntArray(9)
        val talentCounts = IntArray(6)
        for (unit in context.ownedUnits) {
            val catalogUnit = catalog.units[unit.unitId]
            val role = if (unit.roleId == 0) catalogUnit?.role ?: 0 else unit.roleId
            val talent = if (unit.talentId == 0) catalogUnit?.talent ?: 0 else unit.talentId
            if (role in roleCounts.indices) roleCounts[role]++
            if (talent in talentCounts.indices) talentCounts[talent]++
        }
        val global = buildList {
            addAll(stacks.map { it / 15.0 })
            for (threshold in listOf(4, 9, 15)) addAll(stacks.map { (it >= threshold).numeric() })
            add(ownedNorm)
            add(count / 60.0)
            addAll(roleCounts.drop(1).map { it / 20.0 })
            addAll(talentCounts.drop(1).map { it / 20.0 })
            add(gold)
            add((context.alphaRupee != null).numeric())
            add(context.difficulty.toDouble() / maxOf(1, catalog.maxDifficulty))
            add(context.area.toDouble() / maxOf(1, catalog.maxArea))
            add(relicIds.isEmpty().numeric())
            add(context.reviveAvailable.numeric())
        }
        val enemyDim = BattleFeatureCatalog.ENEMY_FEATURES
        val unitDim = BattleFeatureCatalog.UNIT_FEATURES
        val inputs = mapOf(
            "quest_idx" to indices(listOf(catalog.questIndex[context.questId] ?: 0), scalar = true),
            "area_idx" to indices(listOf(maxOf(0, context.area)), scalar = true),
            "difficulty_idx" to indices(listOf(maxOf(0, context.difficulty)), scalar = true),
            "enemy_ids" to indices(enemies.map { catalog.enemyIndex[it] ?: 0 }),
            "enemy_mask" to mask(enemies),
            "enemy_feats" to floats(enemies.flatMap { catalog.enemies[it] ?: List(enemyDim) { 0.0 } }, 5, enemyDim),
            "roster_unit_ids" to indices(roster.map { catalog.unitIndex[it] ?: 0 }),
            "roster_mask" to mask(roster),
            "roster_unit_feats" to floats(roster.flatMap { catalog.units[it]?.features ?: List(unitDim) { 0.0 } }, rosterLength, unitDim),
            "relic_ids" to indices(relics.map { catalog.relicIndex[it] ?: 0 }),
            "relic_mask" to mask(relics),
            "global_feats" to floats(global, global.size),
        )
        val rosterPositions = buildMap {
            roster.forEachIndexed { index, id -> if (id != 0) put(id, index) }
        }
        return ContextFeatures(inputs, rosterPositions, relicIds, gold, ownedNorm)
    }

    private fun teamFeatures(context: ContextFeatures, team: List<Int>): Map<String, BattleFeature> {
        val slots = team.pad(5)
        val matrix = buildList {
            for (i in slots.indices) for (j in slots.indices) {
                if (i < team.size && j < team.size) {
                    val diff = width(slots[j]).toDouble() - width(slots[i])
                    add(diff / 1000.0)
                    add((abs(diff) <= 300).numeric())
                    add((abs(diff) <= 400).numeric())
                } else addAll(listOf(0.0, 0.0, 0.0))
            }
        }
        val synergy = synergy(team, context.relicIds, context.gold, context.ownedNorm)
        return mapOf(
            "team_roster_pos" to indices(slots.map { id -> if (id == 0) 0 else context.rosterPosByUnitId.getValue(id) }),
            "team_slot_mask" to mask(slots),
            "team_pos_matrix" to floats(matrix, 5, 5, 3),
            "team_synergy" to floats(synergy, synergy.size),
        )
    }

    private fun synergy(team: List<Int>, relicIds: List<Int>, gold: Double, ownedNorm: Double): List<Double> {
        val units = team.mapNotNull(catalog.units::get)
        val talents = (1..5).map { id -> units.count { it.talent == id } }
        val most = talents.max()
        val front = team.firstOrNull()?.let(catalog.units::get)
        val relics = relicIds.mapNotNull(catalog.relics::get)
        return buildList {
            add(team.size / 5.0)
            addAll(talents.map { it / 5.0 })
            add(most / 5.0)
            add(listOf(0.0, 0.0, 0.08, 0.16, 0.25, 0.75)[most])
            add((most == 5).numeric())
            add(units.count { it.attackType == 1 || it.features[1] > 0.5 }.toDouble() / maxOf(1, team.size))
            add(units.count { it.attackType == 2 || it.features[2] > 0.5 }.toDouble() / maxOf(1, team.size))
            add(if (team.size >= 2) (width(team[1]).toDouble() - width(team[0])) / 1000.0 else 0.0)
            add((front?.defense ?: 0.0) / 1000.0 * relics.sumOf { it.pdef })
            add((front?.magicDefense ?: 0.0) / 1000.0 * relics.sumOf { it.mdef })
            add(gold * relics.count { it.gold })
            add(ownedNorm * relics.count { it.owned })
            add(relics.sumOf { it.amp })
        }
    }
}
