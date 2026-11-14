package com.landosol.toolbox.labyrinth

import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test

class LabyrinthConfigShareTest {
    private val customEvent = LabyrinthCustomEventCatalog.cards.first { card -> card.choices.any { it.slot != card.defaultSlot } }
    private val customChoice = customEvent.choices.first { it.slot != customEvent.defaultSlot }
    private val values = LabyrinthConfigurationValues(
        strategy = LabyrinthStrategySettings(
            playerWeight = 43, buyRelics = false, refreshShop = false,
            personalRoleScores = mapOf("100101" to 0, "100201" to 83),
            openingRosters = mapOf(1 to listOf(listOf("100101", "100201"), listOf("100301"), listOf("100401"))),
            eventCustomizationEnabled = false,
            eventChoiceOverrides = LabyrinthStrategySettings.DEFAULT_EVENT_CHOICE_OVERRIDES +
                (customEvent.runtimeName to customChoice.runtimeName),
        ),
        rerolls = mapOf(7L to LabyrinthRerollSettings(guildId = 2, difficulty = 3, maxAttempts = "25", retireExisting = false)),
    )
    private fun shared(modules: Set<LabyrinthConfigModule> = LabyrinthConfigModule.entries.toSet()) =
        LabyrinthConfigShareCodec.create(values, modules, 7, "test-version", 1000)

    @Test fun `version one round trip and preview retain source values including disabled custom choices`() {
        assertNotEquals(LabyrinthStrategySettings.DEFAULT_EVENT_CHOICE_OVERRIDES[customEvent.runtimeName], customChoice.runtimeName)
        val expected = shared()
        val code = LabyrinthConfigShareCodec.encode(expected)
        val payload = Json.parseToJsonElement(unpack(code)).jsonObject
        assertTrue(code.startsWith("LABY1."))
        assertEquals(JsonPrimitive(1), payload["schemaVersion"])
        val restored = LabyrinthConfigShareCodec.decode(code)
        assertEquals(expected, restored)
        // Compare with the saved source too: create() must not silently discard custom values.
        assertEquals(values.strategy.copy(personalRoleScores = emptyMap()), restored.strategy)
        assertEquals(values.strategy.personalRoleScores, restored.personalScores)
        assertEquals(values.rerolls.getValue(7L), restored.reroll)
        val strategy = requireNotNull(restored.strategy)
        assertFalse(strategy.eventCustomizationEnabled)
        assertEquals(customChoice.runtimeName, strategy.eventChoiceOverrides[customEvent.runtimeName])

        val target = LabyrinthConfigurationValues(rerolls = mapOf(9L to LabyrinthRerollSettings()))
        LabyrinthConfigImportPlan(restored, restored.modules, 9L, target).changeSummary(mapOf("100101" to "角色甲"))
        // Preview must not change the payload; GZIP byte-for-byte identity is not the contract.
        assertEquals(payload, Json.parseToJsonElement(unpack(LabyrinthConfigShareCodec.encode(restored))))
    }

    @Test fun `portable payload contains only explicit modules and no identity or execution target`() {
        val text = unpack(LabyrinthConfigShareCodec.encode(shared()))
        assertFalse(text.contains("personalRoleScores"))
        listOf("accountId", "credentialKey", "password", "loginId", "gameUid", "batchId", "completedCount", "targetCount").forEach {
            assertFalse(it, text.contains("\"$it\""))
        }
        assertTrue(text.contains("\"personalScores\""))
    }

    @Test fun `each module works independently`() {
        LabyrinthConfigModule.entries.forEach { module ->
            val decoded = LabyrinthConfigShareCodec.decode(LabyrinthConfigShareCodec.encode(shared(setOf(module))))
            assertEquals(setOf(module), decoded.modules)
            assertEquals(values.strategy.copy(personalRoleScores = emptyMap()).takeIf { module == LabyrinthConfigModule.STRATEGY }, decoded.strategy)
            assertEquals(values.strategy.personalRoleScores.takeIf { module == LabyrinthConfigModule.PERSONAL_SCORES }, decoded.personalScores)
            assertEquals(values.rerolls.getValue(7L).takeIf { module == LabyrinthConfigModule.REROLL }, decoded.reroll)
        }
    }

    @Test fun `whitespace from multiline paste is accepted`() {
        val code = LabyrinthConfigShareCodec.encode(shared())
        assertEquals(LabyrinthConfigShareCodec.decode(code), LabyrinthConfigShareCodec.decode(" \t" + code.chunked(60).joinToString("\r\n") + "\n"))
    }

    @Test fun `unknown version malformed base64 and bad gzip are rejected`() {
        val code = LabyrinthConfigShareCodec.encode(shared())
        listOf("", "LABY2." + code.substring(6), "LABY1.!", "LABY1.A", "LABY1.AAAA", code.dropLast(12), code + "=").forEach(::reject)
        val bytes = compressed(code)
        bytes[bytes.lastIndex - 4] = (bytes[bytes.lastIndex - 4].toInt() xor 1).toByte()
        reject(code(bytes))
        reject(code(compressed(code) + byteArrayOf(0)))
        reject(code(compressed(code) + compressed(code)))
    }

    @Test fun `input length accepts the limit and rejects one extra whitespace character`() {
        val expected = shared()
        val code = LabyrinthConfigShareCodec.encode(expected)
        val atLimit = code.padEnd(LabyrinthConfigShareCodec.MAX_INPUT_LENGTH, ' ')
        assertEquals(LabyrinthConfigShareCodec.MAX_INPUT_LENGTH, atLimit.length)
        assertEquals(expected, LabyrinthConfigShareCodec.decode(atLimit))
        val failure = assertThrows(IllegalArgumentException::class.java) { LabyrinthConfigShareCodec.decode(atLimit + " ") }
        assertEquals("分享码过长", failure.message)
    }

    @Test fun `decompression accepts the byte limit and rejects one extra valid json whitespace byte`() {
        val expected = shared()
        val text = unpack(LabyrinthConfigShareCodec.encode(expected))
        // Count UTF-8 bytes, not characters: the payload contains non-ASCII event names.
        val atLimit = text + " ".repeat(LabyrinthConfigShareCodec.MAX_JSON_BYTES - text.toByteArray(Charsets.UTF_8).size)
        assertEquals(LabyrinthConfigShareCodec.MAX_JSON_BYTES, atLimit.toByteArray(Charsets.UTF_8).size)
        assertEquals(expected, LabyrinthConfigShareCodec.decode(pack(atLimit)))
        val failure = assertThrows(IllegalArgumentException::class.java) { LabyrinthConfigShareCodec.decode(pack(atLimit + " ")) }
        assertEquals("分享码解压后的内容过大", failure.message)
    }

    @Test fun `depth sixteen reaches schema validation but seventeen is rejected by the depth guard`() {
        fun nested(depth: Int) = pack("[".repeat(depth) + "0" + "]".repeat(depth))
        // Arrays are deliberately invalid configuration objects. Distinct diagnostics prove
        // which guard rejected them, rather than passing on any later schema failure.
        val atLimit = assertThrows(IllegalArgumentException::class.java) { LabyrinthConfigShareCodec.decode(nested(16)) }
        assertEquals("分享码不是有效的配置对象", atLimit.message)
        val aboveLimit = assertThrows(IllegalArgumentException::class.java) { LabyrinthConfigShareCodec.decode(nested(17)) }
        assertEquals("配置嵌套层数过多", aboveLimit.message)
    }

    @Test fun `strict schema rejects missing extra null and duplicate module fields`() {
        val text = unpack(LabyrinthConfigShareCodec.encode(shared()))
        reject(pack(text.replace("\"schemaVersion\":1", "\"schemaVersion\":2")))
        reject(pack(text.replace("\"playerWeight\":43,", "")))
        reject(pack(text.replace("\"playerWeight\":43", "\"playerWeight\":101")))
        reject(pack(text.replace("\"playerWeight\":43", "\"playerWeight\":\"43\"")))
        reject(pack(text.replace("\"playerWeight\":43", "\"playerWeight\":43,\"playerWeight\":70")))
        reject(pack(text.replace("\"playerWeight\":43", "\"playerWeight\":43,\"pl\\u0061yerWeight\":70")))
        reject(pack(text.replace("\"playerWeight\":43", "\"personalRoleScores\":{},\"playerWeight\":43")))
        reject(pack(text.replace("\"MULTI_TEAM\"", "\"UNKNOWN\"")))
        reject(pack(text.replace("\"100101\":0", "\"100101\":null")))
        reject(pack(text.replace("\"modules\":{", "\"modules\":{\"credentials\":{},")))
    }

    @Test fun `unknown events and choices are rejected on import and export`() {
        val original = shared()
        val root = Json.parseToJsonElement(unpack(LabyrinthConfigShareCodec.encode(original))).jsonObject
        val modules = root.getValue("modules").jsonObject
        val strategy = modules.getValue("strategy").jsonObject
        listOf(
            mapOf("unsupported-event" to customChoice.runtimeName),
            mapOf(customEvent.runtimeName to "unsupported-choice"),
        ).forEach { overrides ->
            val fields = JsonObject(strategy + ("eventChoiceOverrides" to JsonObject(overrides.mapValues { JsonPrimitive(it.value) })))
            val payload = JsonObject(root + ("modules" to JsonObject(modules + ("strategy" to fields))))
            // Bypass encode validation to exercise the untrusted import boundary directly.
            val imported = assertThrows(IllegalArgumentException::class.java) { LabyrinthConfigShareCodec.decode(pack(payload.toString())) }
            assertEquals("事件或分支不受当前版本支持", imported.message)
            val exported = assertThrows(IllegalArgumentException::class.java) {
                LabyrinthConfigShareCodec.encode(original.copy(strategy = requireNotNull(original.strategy).copy(eventChoiceOverrides = overrides)))
            }
            assertEquals("事件或分支不受当前版本支持", exported.message)
        }
    }

    @Test fun `unknown role IDs remain explicit references and are reported`() {
        val decoded = LabyrinthConfigShareCodec.decode(LabyrinthConfigShareCodec.encode(shared()))
        assertEquals(setOf("100201", "100301", "100401"), decoded.unknownRoleIds(setOf("100101")))
    }

    @Test fun `module replacement preserves unselected data and other accounts`() {
        val local = LabyrinthConfigurationValues(
            LabyrinthStrategySettings(playerWeight = 88, personalRoleScores = mapOf("100501" to 50)),
            mapOf(10L to LabyrinthRerollSettings(guildId = 5), 11L to LabyrinthRerollSettings(difficulty = 1)),
        )
        LabyrinthConfigModule.entries.forEach { module ->
            val next = shared().replacement(local, setOf(module), 10L.takeIf { module == LabyrinthConfigModule.REROLL }, false)
            val expectedStrategy = when (module) {
                LabyrinthConfigModule.STRATEGY -> values.strategy.copy(personalRoleScores = local.strategy.personalRoleScores)
                LabyrinthConfigModule.PERSONAL_SCORES -> local.strategy.copy(personalRoleScores = values.strategy.personalRoleScores)
                LabyrinthConfigModule.REROLL -> local.strategy
            }
            val expectedRerolls = if (module == LabyrinthConfigModule.REROLL) local.rerolls + (10L to values.rerolls.getValue(7L)) else local.rerolls
            assertEquals("$module must only replace its own strategy fields", expectedStrategy, next.strategy)
            assertEquals("$module must preserve other accounts", expectedRerolls, next.rerolls)
        }
        // An absent score module differs from an available but unselected one.
        val withoutScores = shared(setOf(LabyrinthConfigModule.STRATEGY))
        assertNull(withoutScores.personalScores)
        assertEquals(local.copy(strategy = values.strategy.copy(personalRoleScores = local.strategy.personalRoleScores)),
            withoutScores.replacement(local, withoutScores.modules, null, false))
        assertThrows(IllegalArgumentException::class.java) {
            withoutScores.replacement(local, setOf(LabyrinthConfigModule.PERSONAL_SCORES), null, true)
        }
    }

    @Test fun `empty scores default unselected and require explicit clearing confirmation`() {
        val empty = shared().copy(personalScores = emptyMap())
        assertFalse(LabyrinthConfigModule.PERSONAL_SCORES in empty.defaultSelection())
        assertThrows(IllegalArgumentException::class.java) {
            empty.replacement(values, setOf(LabyrinthConfigModule.PERSONAL_SCORES), null, false)
        }
        val restored = empty.replacement(values, setOf(LabyrinthConfigModule.PERSONAL_SCORES), null, true)
        assertTrue(restored.strategy.personalRoleScores.isEmpty())
        assertEquals(values.strategy.copy(personalRoleScores = emptyMap()), restored.strategy)
        assertEquals(values.rerolls, restored.rerolls)
    }

    private fun reject(input: String) {
        assertThrows(IllegalArgumentException::class.java) { LabyrinthConfigShareCodec.decode(input) }
    }
    private fun compressed(code: String) = Base64.getUrlDecoder().decode(code.removePrefix("LABY1."))
    private fun code(bytes: ByteArray) = "LABY1." + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    private fun unpack(code: String) = GZIPInputStream(compressed(code).inputStream()).bufferedReader().use { it.readText() }
    private fun pack(text: String): String {
        val output = ByteArrayOutputStream()
        GZIPOutputStream(output).use { it.write(text.toByteArray(Charsets.UTF_8)) }
        return code(output.toByteArray())
    }
}
