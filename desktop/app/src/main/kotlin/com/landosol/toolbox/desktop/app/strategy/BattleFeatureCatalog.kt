package com.landosol.toolbox.desktop.app.strategy

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlinx.serialization.json.*
import kotlin.math.ln1p

internal fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

internal fun Path.readObject(): JsonObject = Json.parseToJsonElement(Files.readString(this)).jsonObject
internal fun JsonObject.int(key: String) = getValue(key).jsonPrimitive.int
internal fun JsonObject.string(key: String) = getValue(key).jsonPrimitive.content
internal fun JsonObject.ints(key: String) = getValue(key).jsonArray.map { it.jsonPrimitive.int }
internal fun JsonObject.strings(key: String) = getValue(key).jsonArray.map { it.jsonPrimitive.content }
private fun JsonObject.number(key: String) = get(key)?.jsonPrimitive?.double ?: 0.0
private fun JsonObject.flag(key: String) = get(key)?.jsonPrimitive?.boolean ?: false
internal fun Boolean.numeric() = if (this) 1.0 else 0.0

// FeatureCatalog.save writes Python's numeric literals. Preserve them, including 1.0 and -0.0.
private fun StringBuilder.appendCanonical(value: JsonElement) {
    when (value) {
        is JsonObject -> {
            append('{')
            value.keys.sortedWith { a, b -> java.util.Arrays.compare(a.codePoints().toArray(), b.codePoints().toArray()) }
                .forEachIndexed { index, key ->
                    if (index > 0) append(',')
                    append(JsonPrimitive(key)).append(':')
                    appendCanonical(value.getValue(key))
                }
            append('}')
        }
        is JsonArray -> {
            append('[')
            value.forEachIndexed { index, element ->
                if (index > 0) append(',')
                appendCanonical(element)
            }
            append(']')
        }
        is JsonPrimitive -> append(value)
    }
}

internal data class CatalogUnit(val width: Int, val attackType: Int, val talent: Int, val role: Int,
    val defense: Double, val magicDefense: Double, val features: List<Double>)
internal data class CatalogRelic(val pdef: Double, val mdef: Double, val gold: Boolean, val owned: Boolean, val amp: Double)

internal data class BattleFeatureCatalog(
    val units: Map<Int, CatalogUnit>, val enemies: Map<Int, List<Double>>, val quests: Map<Int, List<Int>>,
    val relics: Map<Int, CatalogRelic>, val maxArea: Int, val maxDifficulty: Int,
    val unitIndex: Map<Int, Int>, val enemyIndex: Map<Int, Int>, val questIndex: Map<Int, Int>, val relicIndex: Map<Int, Int>,
) {
    companion object {
        const val UNIT_FEATURES = 27
        const val ENEMY_FEATURES = 11
        const val GLOBAL_FEATURES = 41
        const val SYNERGY_FEATURES = 17
        private val panels = listOf("hp", "atk", "magic_str", "def", "magic_def", "dodge", "life_steal",
            "hp_recovery_rate", "energy_recovery_rate", "energy_reduce_rate")
        private val mechanics = listOf("dot_slip", "cc_abnormal", "barrier_shield", "taunt_decoy", "move_or_knock",
            "tp_charge", "tp_drain", "heal_or_regen", "dispel", "aoe_dmg")

        fun load(path: Path, expectedHash: String): BattleFeatureCatalog {
            val root = path.readObject()
            val canonical = buildString { appendCanonical(JsonObject(root + ("catalog_sha256" to JsonPrimitive("")))) }
            require(sha256(canonical.toByteArray(Charsets.UTF_8)) == expectedHash &&
                root.string("catalog_sha256") == expectedHash) { "catalog SHA-256 mismatch" }
            fun entries(key: String) = root.getValue(key).jsonObject.entries.associate { (id, v) -> id.toInt() to v.jsonObject }
            fun indices(ids: Set<Int>) = ids.sorted().mapIndexed { i, id -> id to i + 1 }.toMap()
            val units = entries("units").mapValues { (_, u) ->
                val width = u.int("search_area_width")
                val attack = u.int("atk_type")
                val feats = u.staticFeatures(UNIT_FEATURES) {
                    listOf(width / 1000.0, (attack == 1).numeric(), (attack == 2).numeric()) +
                        listOf("has_6star", "has_ue1", "has_ue2", "has_labyrinth_replace").map { u.flag(it).numeric() } +
                        panels.map { ln1p(u.number("panel_$it").coerceAtLeast(0.0)) } +
                        mechanics.map { u["mech_flags"]?.jsonObject?.flag(it) == true }.map { it.numeric() }
                }
                CatalogUnit(width, attack, u.int("talent_id"), u.int("role_id"), u.number("panel_def"),
                    u.number("panel_magic_def"), feats)
            }
            val enemies = entries("enemies").mapValues { (_, e) ->
                e.staticFeatures(ENEMY_FEATURES) {
                    listOf(e.int("level") / 100.0) +
                        listOf("hp", "atk", "magic_str", "physical_def", "magic_def", "dodge", "accuracy")
                            .map { ln1p(e.number(it).coerceAtLeast(0.0)) } +
                        listOf("is_boss_unit", "has_dot_resist", "has_cc_resist").map { e.flag(it).numeric() }
                }
            }
            val quests = entries("quests").mapValues { it.value.ints("enemy_ids") }
            val relics = entries("relics").mapValues { (_, r) ->
                CatalogRelic(r.number("front_pdef_to_atk_mult"), r.number("front_mdef_to_atk_mult"),
                    r.flag("gold_scaling"), r.flag("owned_unit_scaling"), r.number("element_bonus_amp"))
            }
            return BattleFeatureCatalog(units, enemies, quests, relics, root.int("max_area"), root.int("max_difficulty"),
                indices(units.keys), indices(enemies.keys), indices(quests.keys), indices(relics.keys))
        }

        private fun JsonObject.staticFeatures(size: Int, fallback: () -> List<Double>): List<Double> =
            get("static_feats")?.jsonArray?.takeIf { it.size == size }?.map { it.jsonPrimitive.double } ?: fallback()
    }
}
