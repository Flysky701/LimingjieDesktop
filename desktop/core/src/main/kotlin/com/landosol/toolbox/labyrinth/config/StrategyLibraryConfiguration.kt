package com.landosol.toolbox.labyrinth.config

import com.landosol.toolbox.labyrinth.LabyrinthConfigurationValues
import com.landosol.toolbox.labyrinth.LabyrinthRerollSettings
import com.landosol.toolbox.labyrinth.LabyrinthStrategySettings
import kotlinx.serialization.Serializable

@Serializable
data class StrategyIdentity(val id: String, val name: String, val revision: Long)

@Serializable
data class StrategyProfile(val id: String, val name: String, val note: String = "", val revision: Long = 1,
    val settings: LabyrinthStrategySettings = LabyrinthStrategySettings()) {
    val identity get() = StrategyIdentity(id, name, revision)
}

@Serializable
data class StrategyImportUndo(val activeId: String, val profiles: List<StrategyProfile>, val rerolls: Map<Long, LabyrinthRerollSettings>)

@Serializable
data class StrategyLibrary(val activeId: String = "", val profiles: List<StrategyProfile> = emptyList(),
    val rerolls: Map<Long, LabyrinthRerollSettings> = emptyMap(), val revision: Long = 1,
    val undoImport: StrategyImportUndo? = null) {
    val active get() = profiles.first { it.id == activeId }

    /** The import preview and commit both take their strategy and account switches from this snapshot. */
    fun configurationFor(profileId: String): LabyrinthConfigurationValues {
        val profile = profiles.firstOrNull { it.id == profileId } ?: error("目标策略不存在")
        return LabyrinthConfigurationValues(profile.settings, rerolls)
    }

    fun validate() {
        require(profiles.any { it.id == activeId }) { "当前策略不存在" }
        require(profiles.distinctBy { it.id }.size == profiles.size) { "策略 ID 重复" }
        require(revision > 0)
        profiles.forEach {
            require(it.id.isNotBlank() && it.revision > 0)
            validateProfile(it.name, it.note, it.settings)
        }
        rerolls.forEach { (accountId, settings) ->
            require(accountId > 0) { "账号配置 ID 无效" }
            settings.validationError()?.let { throw IllegalArgumentException(it) }
        }
        undoImport?.let { StrategyLibrary(it.activeId, it.profiles, it.rerolls).validate() }
    }

    companion object {
        fun validateProfile(name: String, note: String, settings: LabyrinthStrategySettings) {
            require(name.isNotBlank() && name.length <= 60 && name.none { it.isISOControl() }) { "策略名称须为 1–60 个字符，不能含换行" }
            require(note.length <= 300) { "备注最多 300 个字符" }
            settings.validationError()?.let { throw IllegalArgumentException(it) }
        }
    }
}
