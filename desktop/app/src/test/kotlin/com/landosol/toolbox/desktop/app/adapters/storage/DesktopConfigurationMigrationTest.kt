package com.landosol.toolbox.desktop.app.adapters.storage

import com.landosol.toolbox.desktop.app.control.ControlModelCodec
import com.landosol.toolbox.desktop.platform.*
import com.landosol.toolbox.labyrinth.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files

class DesktopConfigurationMigrationTest {
    @get:Rule val directory = TemporaryFolder()

    @Test fun desktopAndUiMigrateLosslesslyAndAreNeverReimported() {
        val root = directory.root.toPath()
        val settings = Files.createDirectories(root.resolve("settings"))
        val environment = DesktopSettings("D:/中文/adb.exe", "emulator-5554", PreferredTouchMode.COMPATIBILITY,
            CloseBehavior.MINIMIZE, NetworkMode.DIRECT, 1550, 900, -12, 35, true, true, true, "D:/模型", true, "AGGRESSIVE")
        val desktopJson = Json.encodeToString(DesktopSettings.serializer(), environment)
        val uiJson = """{"theme":"DARK","largeText":true,"notifications":false,"sound":true,"originalRatings":true,
            "page":"Strategies","rightTab":"history","previewExpanded":true,"animationConfirmedVersion":"abc",
            "goals":[{"guildId":10,"count":3,"clears":false},{"guildId":2,"count":5}],"selectedTask":"REROLL"}"""
        val ui = DesktopPreferences(DesktopThemeMode.DARK, true, false, true, true, "Strategies", "history", true, "abc",
            listOf(DesktopGoal(10, 3, false), DesktopGoal(2, 5)), "REROLL")
        Files.writeString(settings.resolve("desktop.json"), desktopJson)
        Files.writeString(settings.resolve("ui.json"), uiJson)

        val app = AppConfigStore(root)
        assertEquals(AppConfig(environment, ui, true), app.state.value)
        assertEquals(desktopJson, Files.readString(settings.resolve("desktop.json")))
        assertEquals(uiJson, Files.readString(settings.resolve("ui.json")))
        val before = Files.readString(settings.resolve("app.json"))
        Files.writeString(settings.resolve("ui.json"), "invalid after migration")
        val reopened = AppConfigStore(root) { fail("unexpected recovery of $it") }
        assertEquals(app.state.value, reopened.state.value)
        assertEquals(before, Files.readString(settings.resolve("app.json")))
        DesktopSettingsStore(reopened).update { it.copy(windowX = 0) }
        DesktopPreferencesStore(reopened).update { it.copy(page = "Run") }
        assertEquals(AppConfig(environment.copy(windowX = 0), ui.copy(page = "Run"), true), AppConfigStore(root).state.value)
    }

    @Test fun corruptLegacyUiDoesNotDiscardEnvironmentOrDisableSharedTaskDefaults() {
        val root = directory.root.toPath()
        val settings = Files.createDirectories(root.resolve("settings"))
        Files.writeString(settings.resolve("desktop.json"), """{"adbPath":"kept"}""")
        Files.writeString(settings.resolve("ui.json"), "broken")
        val backups = mutableListOf<java.nio.file.Path>()
        val app = AppConfigStore(root, backups::add)
        assertEquals("broken", Files.readString(backups.single()))
        assertEquals("kept", app.state.value.environment.adbPath)
        assertFalse(app.state.value.uiPreferencesSaved)
        DesktopPreferencesStore(app).update { DesktopPreferences() }
        assertTrue(AppConfigStore(root).state.value.uiPreferencesSaved)
    }

    @Test fun versionTwoLibraryPreservesAllProfilesRerollsAndUndoWithoutReadingFallbacks() {
        val root = directory.root.toPath()
        val settings = Files.createDirectories(root.resolve("settings"))
        val profile = StrategyProfile("kept", "原策略", revision = 6)
        val other = StrategyProfile("other", "备选", "备注", 8, LabyrinthStrategySettings(playerWeight = 11))
        val rerolls = mapOf(18L to LabyrinthRerollSettings(guildId = 8))
        val undo = StrategyImportUndo(profile.id, listOf(profile, other), emptyMap())
        val library = StrategyLibrary(other.id, listOf(profile, other), rerolls, 22, undo)
        val old = buildJsonObject { put("version", 2); put("library", ControlModelCodec.encode(library)) }
        Files.writeString(settings.resolve("strategies.json"), old.toString())
        val store = DesktopStrategyStore(root, { error("unexpected migration") }, {}, legacyRerolls = { error("unexpected migration") })
        assertEquals(library, store.library.value)
        assertEquals(library, DesktopStrategyStore(root, { error("already migrated") }, {}).library.value)
    }

    @Test fun legacyLabyrinthSnapshotTakesPrecedenceAndRetainsUndo() = runBlocking {
        val root = directory.root.toPath()
        val file = Files.createDirectories(root.resolve("files")).resolve("labyrinth-configuration-v1.json")
        val previous = LabyrinthConfigurationUndo(LabyrinthStrategySettings(playerWeight = 18),
            mapOf(2L to LabyrinthRerollSettings(guildId = 6)), setOf(LabyrinthConfigModule.REROLL), 2L)
        val snapshot = LabyrinthConfigurationValues(LabyrinthStrategySettings(playerWeight = 35),
            mapOf(2L to LabyrinthRerollSettings(guildId = 4)), previous)
        val raw = Json.encodeToString(LabyrinthConfigurationValues.serializer(), snapshot)
        Files.writeString(file, raw)
        val store = DesktopStrategyStore(root, { error("older source") }, {}, legacyRerolls = { error("older source") })
        assertEquals(snapshot.strategy, store.state.value)
        assertEquals(snapshot.rerolls, store.library.value.rerolls)
        store.undoImport(store.library.value.revision)
        assertEquals(previous.strategy, store.state.value)
        assertEquals(previous.rerolls, store.library.value.rerolls)
        assertEquals(raw, Files.readString(file))
    }

    @Test fun oldLibraryAndAccountPreferencesMigrateOnceWithoutLosingIdentityOrCandidates() {
        val root = directory.root.toPath()
        val profile = StrategyProfile("kept-id", "旧策略", revision = 7, settings = LabyrinthStrategySettings(playerWeight = 19))
        val oldLibrary = JsonObject(ControlModelCodec.encode(StrategyLibrary(profile.id, listOf(profile))).jsonObject - setOf("rerolls", "revision", "undoImport"))
        val old = buildJsonObject { put("version", 1); put("library", oldLibrary) }.toString()
        val file = root.resolve("settings/strategies.json")
        Files.createDirectories(file.parent); Files.writeString(file, old)
        val rerolls = mapOf(17L to LabyrinthRerollSettings(guildId = 10, maxAttempts = "621"), 23L to LabyrinthRerollSettings(guildId = 6))
        val store = DesktopStrategyStore(root, { error("existing strategy") }, {}, legacyRerolls = { rerolls })
        assertEquals(profile, store.library.value.active)
        assertEquals(rerolls, store.library.value.rerolls)
        val backup = Files.list(file.parent).use { paths -> paths.filter { it.fileName.toString().startsWith("strategies.before-upgrade-") }.toList().single() }
        assertEquals(old, Files.readString(backup))
        val reopened = DesktopStrategyStore(root, { error("no remigration") }, {}, legacyRerolls = { error("no remigration") })
        assertEquals(store.library.value, reopened.library.value)
    }

    @Test fun moduleImportAndUndoAreAtomicAccountScopedAndRejectStalePreviews() = runBlocking {
        val root = directory.root.toPath()
        val originalSettings = LabyrinthStrategySettings(playerWeight = 29, personalRoleScores = mapOf("100101" to 77))
        var store = DesktopStrategyStore(root, { originalSettings }, {})
        store.save(7, LabyrinthRerollSettings(guildId = 7))
        store.save(9, LabyrinthRerollSettings(guildId = 9))
        val baseline = store.library.value
        val card = LabyrinthCustomEventCatalog.cards.first()
        val shared = LabyrinthSharedConfiguration("test", 1,
            strategy = originalSettings.copy(eventCustomizationEnabled = true,
                eventChoiceOverrides = mapOf(card.runtimeName to card.choices.last().runtimeName), personalRoleScores = emptyMap()),
            personalScores = emptyMap(), reroll = LabyrinthRerollSettings(guildId = 10))
        val modules = setOf(LabyrinthConfigModule.STRATEGY, LabyrinthConfigModule.REROLL)
        store.importConfiguration(shared, modules, baseline.activeId, 7, false, baseline.revision)
        assertEquals(originalSettings.personalRoleScores, store.state.value.personalRoleScores)
        assertTrue(store.state.value.eventCustomizationEnabled)
        assertEquals(10, store.load(7).guildId)
        assertEquals(9, store.load(9).guildId)
        assertEquals(originalSettings, baseline.active.settings)
        val imported = store.library.value
        assertThrows(IllegalStateException::class.java) { runBlocking {
            store.importConfiguration(shared, modules, baseline.activeId, 7, false, baseline.revision)
        } }
        assertEquals(imported, store.library.value)
        store = DesktopStrategyStore(root, { error("must load") }, {})
        store.undoImport(store.library.value.revision)
        assertEquals(originalSettings, store.state.value)
        assertEquals(baseline.rerolls, store.library.value.rerolls)
        assertTrue(store.library.value.active.revision > imported.active.revision)
        assertNull(store.library.value.undoImport)

        val beforeFailure = store.library.value
        assertThrows(IllegalArgumentException::class.java) { runBlocking {
            store.importConfiguration(shared, setOf(LabyrinthConfigModule.PERSONAL_SCORES), baseline.activeId,
                null, false, beforeFailure.revision)
        } }
        assertEquals(beforeFailure, store.library.value)
        val file = root.resolve("settings/strategies.json")
        val saved = Files.readString(file)
        Files.move(file, file.resolveSibling("retained.json")); Files.createDirectory(file)
        assertThrows(Exception::class.java) { runBlocking {
            store.importConfiguration(shared, modules, baseline.activeId, 7, false, beforeFailure.revision)
        } }
        assertEquals(beforeFailure, store.library.value)
        assertEquals(saved, Files.readString(file.resolveSibling("retained.json")))
    }
}
