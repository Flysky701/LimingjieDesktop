package com.landosol.toolbox.labyrinth

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable

@Serializable
data class LabyrinthConfigurationValues(
    val strategy: LabyrinthStrategySettings = LabyrinthStrategySettings(),
    val rerolls: Map<Long, LabyrinthRerollSettings> = emptyMap(),
    val undoImport: LabyrinthConfigurationUndo? = null,
)

@Serializable
data class LabyrinthConfigurationUndo(
    val strategy: LabyrinthStrategySettings,
    val rerolls: Map<Long, LabyrinthRerollSettings>,
    val modules: Set<LabyrinthConfigModule>,
    val accountId: Long?,
)

/** A single commit publishes all selected modules; failed writes never publish a new snapshot. */
class LabyrinthConfigurationStore(
    initial: LabyrinthConfigurationValues,
    private val persist: (LabyrinthConfigurationValues) -> Unit,
) : LabyrinthRerollSettingsStore {
    private val lock = Any()
    private val current = MutableStateFlow(initial)
    val state = current.asStateFlow()
    private val strategy = MutableStateFlow(initial.strategy)
    val strategyState = strategy.asStateFlow()

    override fun load(accountId: Long): LabyrinthRerollSettings =
        current.value.rerolls[accountId] ?: LabyrinthRerollSettings()

    override fun save(accountId: Long, settings: LabyrinthRerollSettings) = synchronized(lock) {
        require(accountId > 0) { "账号无效" }
        settings.validationError()?.let { throw IllegalArgumentException(it) }
        commit(current.value.copy(rerolls = current.value.rerolls + (accountId to settings), undoImport = null))
    }

    fun saveStrategy(settings: LabyrinthStrategySettings) = synchronized(lock) {
        settings.validationError()?.let { throw IllegalArgumentException(it) }
        commit(current.value.copy(strategy = settings, undoImport = null))
    }

    fun importConfiguration(
        configuration: LabyrinthSharedConfiguration,
        selected: Set<LabyrinthConfigModule>,
        targetAccountId: Long?,
        confirmEmptyScores: Boolean,
        expected: LabyrinthConfigurationValues,
    ): LabyrinthConfigurationValues = synchronized(lock) {
        check(current.value == expected) { "本机配置已变化，请重新预览后确认导入" }
        val next = configuration.replacement(current.value, selected, targetAccountId, confirmEmptyScores)
        validate(next)
        commit(next.copy(undoImport = LabyrinthConfigurationUndo(expected.strategy, expected.rerolls, selected, targetAccountId)))
        current.value
    }

    fun undoImport(expected: LabyrinthConfigurationValues): LabyrinthConfigurationValues = synchronized(lock) {
        check(current.value == expected) { "本机配置已变化，请重新确认" }
        val undo = current.value.undoImport ?: error("没有可撤销的导入，或导入后已手动保存其他配置")
        commit(LabyrinthConfigurationValues(undo.strategy, undo.rerolls))
        current.value
    }

    private fun commit(next: LabyrinthConfigurationValues) {
        validate(next)
        // Round-tripping prevents mutable collections owned by a caller from changing a saved snapshot.
        val detached = LabyrinthConfigurationPersistence.decode(LabyrinthConfigurationPersistence.encode(next))
        persist(detached)
        current.value = detached
        strategy.value = detached.strategy
    }

    companion object {
        fun validate(values: LabyrinthConfigurationValues) {
            values.strategy.validationError()?.let { throw IllegalArgumentException(it) }
            values.undoImport?.let { undo ->
                validate(LabyrinthConfigurationValues(undo.strategy, undo.rerolls))
                require(undo.modules.isNotEmpty()) { "恢复点不包含模块" }
            }
            values.rerolls.forEach { (accountId, settings) ->
                require(accountId > 0) { "账号配置 ID 无效" }
                settings.validationError()?.let { throw IllegalArgumentException(it) }
            }
        }
    }
}

internal object LabyrinthConfigurationPersistence {
    private val json = kotlinx.serialization.json.Json { encodeDefaults = true; ignoreUnknownKeys = true }
    fun encode(values: LabyrinthConfigurationValues): String =
        json.encodeToString(LabyrinthConfigurationValues.serializer(), values)
    fun decode(raw: String): LabyrinthConfigurationValues =
        json.decodeFromString(LabyrinthConfigurationValues.serializer(), raw).also(LabyrinthConfigurationStore::validate)
}
