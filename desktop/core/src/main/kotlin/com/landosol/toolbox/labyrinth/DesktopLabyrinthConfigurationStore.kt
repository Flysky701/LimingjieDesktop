package com.landosol.toolbox.labyrinth

import com.landosol.toolbox.core.AppContext
import com.landosol.toolbox.core.config.AtomicConfigStore
import com.landosol.toolbox.core.config.LegacyConfigFormat
import com.landosol.toolbox.labyrinth.config.*
import kotlinx.serialization.json.JsonElement
import java.nio.file.Path
import java.util.UUID

class DesktopLabyrinthConfigurationStore(private val decodeLegacyLibrary: (JsonElement) -> StrategyLibrary) {
    fun create(context: AppContext, transient: Boolean = false, onRecovered: (Path) -> Unit = {}): AtomicConfigStore<StrategyLibrary> =
        open(context.dataDir.toPath(), legacyStrategy = {
            val raw = if (transient) null else context.preferences("labyrinth-strategy").getString("settings.v1", null)
            LabyrinthStrategySettingsCodec.decode(raw)
        }, legacyRerolls = {
            val prefs = context.preferences("labyrinth-reroll-settings")
            val old = JsonLabyrinthRerollSettingsStore(
                read = { prefs.getString(it, null) }, write = { _, _ -> error("旧配置仅用于读取") },
            )
            prefs.all.keys.filter { it.startsWith("account.") }.mapNotNull { key ->
                key.removePrefix("account.").toLongOrNull()?.takeIf { it > 0 }?.let { it to old.load(it) }
            }.toMap()
        }, transient, onRecovered)

    fun open(
        directory: Path,
        legacyStrategy: () -> LabyrinthStrategySettings,
        legacyRerolls: () -> Map<Long, LabyrinthRerollSettings>,
        transient: Boolean,
        onRecovered: (Path) -> Unit,
    ): AtomicConfigStore<StrategyLibrary> {
        fun legacySnapshot() = AtomicConfigStore.readLegacy(directory.resolve("files/labyrinth-configuration-v1.json"),
            onRecovered, LabyrinthConfigurationPersistence::decode)

        fun initialLibrary(): StrategyLibrary {
            val values = if (transient) LabyrinthConfigurationValues(legacyStrategy())
                else legacySnapshot() ?: LabyrinthConfigurationValues(legacyStrategy(), legacyRerolls())
            val profile = StrategyProfile(UUID.randomUUID().toString(), "我的策略", settings = values.strategy)
            val undo = values.undoImport?.let {
                StrategyImportUndo(profile.id, listOf(profile.copy(settings = it.strategy)), it.rerolls)
            }
            return StrategyLibrary(profile.id, listOf(profile), values.rerolls, undoImport = undo)
        }

        return AtomicConfigStore(
            directory.resolve("settings/strategies.json").takeUnless { transient }, StrategyLibrary.serializer(), ::initialLibrary,
            schemaVersion = 3, validate = StrategyLibrary::validate, onRecovered = onRecovered,
            legacyFormat = LegacyConfigFormat("version") { version, document ->
                val library = decodeLegacyLibrary(requireNotNull(document["library"]))
                if (version == 1) library.copy(rerolls = legacySnapshot()?.rerolls ?: legacyRerolls()) else library
            },
        )
    }
}
