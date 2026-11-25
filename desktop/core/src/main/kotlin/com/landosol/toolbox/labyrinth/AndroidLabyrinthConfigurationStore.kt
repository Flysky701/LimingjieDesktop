package com.landosol.toolbox.labyrinth

import android.content.Context
import java.io.File

/** Configuration data only; credentials, execution records and game assets stay in their own stores. */
object AndroidLabyrinthConfigurationStore {
    fun create(context: Context): LabyrinthConfigurationStore {
        val app = context.applicationContext
        val file = LabyrinthAtomicConfigurationFile(File(app.filesDir, "labyrinth-configuration-v1.json"))
        val initial = file.read()?.let(LabyrinthConfigurationPersistence::decode) ?: run {
            val strategy = app.getSharedPreferences("labyrinth-strategy", Context.MODE_PRIVATE)
            val rawStrategy = strategy.getString("settings.v1", null)
            val savedStrategy = LabyrinthStrategySettingsCodec.decode(rawStrategy).let {
                if (rawStrategy != null && !strategy.getBoolean("settings.response-interval-default.migrated", false)) {
                    it.copy(responseIntervalMillis = DEFAULT_RESPONSE_INTERVAL_MILLIS)
                } else it
            }
            val reroll = app.getSharedPreferences("labyrinth-reroll-settings", Context.MODE_PRIVATE)
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
