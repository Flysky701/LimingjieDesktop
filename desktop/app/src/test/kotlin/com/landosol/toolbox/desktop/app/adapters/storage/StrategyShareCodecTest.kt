package com.landosol.toolbox.desktop.app.adapters.storage

import com.landosol.toolbox.labyrinth.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.Base64
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

class StrategyShareCodecTest {
    @get:Rule val directory = TemporaryFolder()
    private fun code(document: String): String {
        val output = ByteArrayOutputStream()
        GZIPOutputStream(output).use { it.write(document.toByteArray(Charsets.UTF_8)) }
        return "LMJ1:" + Base64.getUrlEncoder().withoutPadding().encodeToString(output.toByteArray())
    }
    private fun document(code: String) = GZIPInputStream(Base64.getUrlDecoder().decode(code.removePrefix("LMJ1:")).inputStream())
        .use { Json.parseToJsonElement(it.readAllBytes().toString(Charsets.UTF_8)).jsonObject }
    private fun rejects(block: () -> Unit) { assertThrows(Exception::class.java, block) }

    @Test fun shareRoundTripPreservesAllSettingsButNotLocalIdentity() {
        val settings = LabyrinthStrategySettings(playerWeight = 37, personalRoleScores = mapOf("100101" to 0, "100201" to 100),
            bossTeamMode = LabyrinthBossTeamMode.SINGLE_TEAM, specialEquipmentMode = LabyrinthSpecialEquipmentMode.AUTO_EQUIP,
            specialEquipmentKinds = setOf(LabyrinthCombatKind.EX), responseIntervalMillis = RESPONSE_INTERVAL_STANDARD)
        val original = StrategyProfile("local-id", "个人方案", "测试备注", 9, settings)
        val encoded = StrategyShareCodec.encode(original)
        val imported = StrategyShareCodec.decode("  $encoded\n")
        assertEquals(settings, imported.settings)
        assertEquals(original.name, imported.name)
        assertEquals(original.note, imported.note)
        assertNotEquals(original.id, imported.id)
        assertEquals(1L, imported.revision)
        assertEquals(setOf("format", "version", "name", "note", "settings"), document(encoded).keys)
    }

    @Test fun shareRoundTripPreservesVeryAggressiveFrontlineSurvivalMode() {
        val settings = LabyrinthStrategySettings().withFrontlineSurvivalMode(LabyrinthFrontlineSurvivalMode.VERY_AGGRESSIVE)
        val encoded = StrategyShareCodec.encode(StrategyProfile("id", "策略", settings = settings))
        val imported = StrategyShareCodec.decode(encoded)
        assertEquals(settings, imported.settings)
        assertEquals(LabyrinthFrontlineSurvivalMode.VERY_AGGRESSIVE, imported.settings.effectiveFrontlineSurvivalMode)
    }

    @Test fun malformedIncompatibleAndOversizedInputCannotBecomeDefaultStrategy() {
        val valid = document(StrategyShareCodec.encode(StrategyProfile("id", "策略")))
        rejects { StrategyShareCodec.decode("LMJ1:broken") }
        rejects { StrategyShareCodec.decode("LMJ2:anything") }
        rejects { StrategyShareCodec.decode(code(JsonObject(valid + ("version" to JsonPrimitive(2))).toString())) }
        rejects { StrategyShareCodec.decode(code(JsonObject(valid - "settings").toString())) }
        val settings = valid.getValue("settings").jsonObject
        rejects { StrategyShareCodec.decode(code(JsonObject(valid + ("settings" to JsonObject(emptyMap()))).toString())) }
        rejects { StrategyShareCodec.decode(code(JsonObject(valid + ("settings" to JsonObject(settings - "playerWeight"))).toString())) }
        for (patch in listOf("playerWeight" to JsonPrimitive(101), "bossTeamMode" to JsonPrimitive("NEW_MODE"), "newOption" to JsonPrimitive(true),
            "frontlineSurvivalGateEnabled" to JsonNull, "normalBattleSkipTeamSelectionEnabled" to JsonPrimitive("invalid"))) {
            rejects { StrategyShareCodec.decode(code(JsonObject(valid + ("settings" to JsonObject(settings + patch))).toString())) }
        }
        rejects { StrategyShareCodec.decode(code(" ".repeat(300_000))) }
    }

    @Test fun legacySharesDefaultMissingBattleSwitchesWithoutChangingOtherSettings() {
        val settings = LabyrinthStrategySettings(playerWeight = 37, buyRelics = false,
            personalRoleScores = mapOf("100101" to 83),
            frontlineSurvivalGateEnabled = true, normalBattleSkipTeamSelectionEnabled = true)
        val original = StrategyProfile("source", "旧版策略", "保留原偏好", settings = settings)
        val root = document(StrategyShareCodec.encode(original))
        val switches = setOf("frontlineSurvivalGateEnabled", "normalBattleSkipTeamSelectionEnabled")
        for (missing in listOf(switches, setOf("frontlineSurvivalGateEnabled"), setOf("normalBattleSkipTeamSelectionEnabled"))) {
            val fields = JsonObject(root.getValue("settings").jsonObject - missing)
            val restored = StrategyShareCodec.decode(code(JsonObject(root + ("settings" to fields)).toString()))
            assertEquals(original.name, restored.name)
            assertEquals(original.note, restored.note)
            assertEquals(settings.copy(frontlineSurvivalGateEnabled = "frontlineSurvivalGateEnabled" !in missing,
                normalBattleSkipTeamSelectionEnabled = "normalBattleSkipTeamSelectionEnabled" !in missing), restored.settings)
        }
    }

    @Test fun migrationImportAndStaleEditNeverOverwriteAnExistingStrategy() = runBlocking {
        val root = directory.root.toPath()
        val legacy = LabyrinthStrategySettings(playerWeight = 27)
        val store = DesktopStrategyStore(root, { legacy }, {})
        val original = store.library.value.active
        val imported = StrategyShareCodec.decode(StrategyShareCodec.encode(original))
        val id = store.add(imported.name, imported.note, imported.settings)
        assertEquals(original.id, store.library.value.activeId)
        assertEquals("我的策略 (2)", store.library.value.profiles.first { it.id == id }.name)
        store.edit(original.id, original.revision, original.name, "", legacy.copy(playerWeight = 30))
        rejects { runBlocking { store.edit(original.id, original.revision, "旧草稿", "", legacy) } }
        assertEquals(30, store.state.value.playerWeight)
        val reloaded = DesktopStrategyStore(root, { error("must not migrate twice") }, {})
        assertEquals(store.library.value, reloaded.library.value)
        assertEquals(legacy, original.settings) // previously captured run snapshot stays immutable
    }

    @Test fun deletingActiveStrategyIsRejectedAndDeletionKeepsRecoveryCopy() = runBlocking {
        val root = directory.root.toPath()
        val store = DesktopStrategyStore(root, { LabyrinthStrategySettings() }, {})
        val active = store.library.value.activeId
        rejects { runBlocking { store.delete(active) } }
        val other = store.add("备选")
        val before = Files.readString(root.resolve("settings/strategies.json"))
        store.delete(other)
        assertEquals(before, Files.readString(root.resolve("settings/strategies.before-delete.json")))
        assertEquals(active, store.library.value.activeId)
    }

    @Test fun damagedLibraryIsBackedUpAndNewerLibraryIsNeverReplaced() {
        val root = directory.root.toPath()
        val file = root.resolve("settings/strategies.json")
        Files.createDirectories(file.parent)
        Files.writeString(file, "{broken")
        val notices = mutableListOf<String>()
        DesktopStrategyStore(root, { LabyrinthStrategySettings(playerWeight = 32) }, notices::add)
        val backups = Files.list(file.parent).use { it.filter { p -> p.fileName.toString().startsWith("strategies.corrupt-") }.toList() }
        assertEquals("{broken", Files.readString(backups.single()))
        assertEquals(1, notices.size)
        val newer = "{\"version\":3,\"library\":{}}"
        Files.writeString(file, newer)
        rejects { DesktopStrategyStore(root, { LabyrinthStrategySettings() }, {}) }
        assertEquals(newer, Files.readString(file))
    }

    @Test fun legacySharesDefaultOnlyNewEventFieldsAndModernSharesPreserveTenGuilds() {
        val card = LabyrinthCustomEventCatalog.cards.first()
        val settings = LabyrinthStrategySettings(eventCustomizationEnabled = true,
            eventChoiceOverrides = mapOf(card.runtimeName to card.choices.last().runtimeName),
            openingRosters = LabyrinthOpeningRosterCatalog.configs.mapValues { (_, config) ->
                config.slots.map { slot -> slot.candidates.map { it.characterId } }
            })
        assertEquals((1..10).toSet(), settings.openingRosters.keys)
        val encoded = StrategyShareCodec.encode(StrategyProfile("fixture", "十公会", settings = settings))
        assertEquals(settings, StrategyShareCodec.decode(encoded).settings)
        val root = document(encoded)
        val oldSettings = JsonObject(root.getValue("settings").jsonObject - setOf("eventCustomizationEnabled", "eventChoiceOverrides",
            "frontlineSurvivalGateEnabled", "normalBattleSkipTeamSelectionEnabled", "frontlineSurvivalMode"))
        val legacy = StrategyShareCodec.decode(code(JsonObject(root + ("settings" to oldSettings)).toString())).settings
        assertFalse(legacy.eventCustomizationEnabled)
        assertEquals(LabyrinthFrontlineSurvivalMode.STABLE, legacy.effectiveFrontlineSurvivalMode)
        assertEquals(LabyrinthStrategySettings().eventChoiceOverrides, legacy.eventChoiceOverrides)
        assertEquals(settings.openingRosters, legacy.openingRosters)
    }
}
