package com.landosol.toolbox.desktop.app.adapters.storage

import com.landosol.toolbox.desktop.app.control.ControlModelCodec
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
