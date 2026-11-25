package com.landosol.toolbox.labyrinth

import com.landosol.toolbox.core.AppContext
import java.io.File

/** Configuration data only; credentials, execution records and game assets stay in their own stores. */
object DesktopLabyrinthConfigurationStore {
    fun create(context: AppContext): LabyrinthConfigurationStore {
        val file = LabyrinthAtomicConfigurationFile(File(context.filesDir, "labyrinth-configuration-v1.json"))
        val initial = file.read()?.let(LabyrinthConfigurationPersistence::decode) ?: run {
            val strategy = context.preferences("labyrinth-strategy")
            val rawStrategy = strategy.getString("settings.v1", null)
            val savedStrategy = LabyrinthStrategySettingsCodec.decode(rawStrategy).let {
                if (rawStrategy != null && !strategy.getBoolean("settings.response-interval-default.migrated", false)) {
                    it.copy(responseIntervalMillis = DEFAULT_RESPONSE_INTERVAL_MILLIS)
                } else it
            }
            val reroll = context.preferences("labyrinth-reroll-settings")
            val oldStore = JsonLabyrinthRerollSettingsStore(
                read = { key -> reroll.getString(key, null) },
                write = { _, _ -> error("旧配置仅用于读取") },
            )
            val rerolls = reroll.all.keys.mapNotNull { key ->
                key.takeIf { it.startsWith("account.") }?.removePrefix("account.")?.toLongOrNull()
                    ?.takeIf { it > 0 }?.let { id -> id to oldStore.load(id) }
            }.toMap()
            LabyrinthConfigurationValues(savedStrategy, rerolls).also {
                file.write(LabyrinthConfigurationPersistence.encode(it))
            }
        }
        return LabyrinthConfigurationStore(initial) { file.write(LabyrinthConfigurationPersistence.encode(it)) }
    }
}
