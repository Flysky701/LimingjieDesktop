package com.landosol.toolbox.desktop.app.adapters.storage

import com.landosol.toolbox.core.AppContext
import com.landosol.toolbox.core.config.AtomicConfigStore
import com.landosol.toolbox.core.config.select
import com.landosol.toolbox.desktop.shared.AtomicFiles
import com.landosol.toolbox.labyrinth.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.UUID
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

typealias StrategyIdentity = com.landosol.toolbox.labyrinth.config.StrategyIdentity
typealias StrategyProfile = com.landosol.toolbox.labyrinth.config.StrategyProfile
typealias StrategyImportUndo = com.landosol.toolbox.labyrinth.config.StrategyImportUndo
typealias StrategyLibrary = com.landosol.toolbox.labyrinth.config.StrategyLibrary

/** Explicit portable envelope. Never serialize accounts, runtime state or a Graph. */
object StrategyShareCodec {
    private const val PREFIX = "LMJ1:"
    private const val MAX_BYTES = 256 * 1024
    private val json = Json { encodeDefaults = true }
    private val settingKeys = json.encodeToJsonElement(LabyrinthStrategySettings.serializer(), LabyrinthStrategySettings()).jsonObject.keys

    fun encode(profile: StrategyProfile): String {
        StrategyLibrary.validateProfile(profile.name, profile.note, profile.settings)
        val document = buildJsonObject {
            put("format", "limingjie-strategy"); put("version", 1)
            put("name", profile.name); put("note", profile.note)
            put("settings", json.encodeToJsonElement(LabyrinthStrategySettings.serializer(), profile.settings))
        }.toString().toByteArray(Charsets.UTF_8)
        require(document.size <= MAX_BYTES) { "策略内容过大，无法分享" }
        val output = ByteArrayOutputStream()
        GZIPOutputStream(output).use { it.write(document) }
        return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(output.toByteArray())
    }

    fun decode(code: String): StrategyProfile {
        require(code.length <= MAX_BYTES * 2) { "分享码过长" }
        val text = code.trim()
        require(text.startsWith(PREFIX)) { "不是支持的策略分享码，请复制完整分享码或更新助手" }
        val document = try {
            val compressed = Base64.getUrlDecoder().decode(text.removePrefix(PREFIX))
            val bytes = GZIPInputStream(compressed.inputStream()).use { it.readNBytes(MAX_BYTES + 1) }
            require(bytes.size <= MAX_BYTES) { "策略内容过大" }
            json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
        } catch (failure: Exception) { throw IllegalArgumentException("分享码损坏或不完整，未导入", failure) }
        require(document["format"]?.jsonPrimitive?.content == "limingjie-strategy" &&
            document["version"]?.jsonPrimitive?.intOrNull == 1) { "不支持此策略格式，请更新助手" }
        require(document.keys == setOf("format", "version", "name", "note", "settings")) { "策略包含不支持的内容，请更新助手" }
        val keys = (document["settings"] as? JsonObject)?.keys.orEmpty()
        val addedFields = setOf("eventCustomizationEnabled", "eventChoiceOverrides",
            "frontlineSurvivalGateEnabled", "normalBattleSkipTeamSelectionEnabled", "frontlineSurvivalMode")
        require(keys.all { it in settingKeys } && keys.containsAll(settingKeys - addedFields)) { "策略字段不完整或不兼容，请复制完整分享码或更新助手" }
        val profile = try {
            StrategyProfile(UUID.randomUUID().toString(), document.getValue("name").jsonPrimitive.content,
                document.getValue("note").jsonPrimitive.content, settings =
                    json.decodeFromJsonElement(LabyrinthStrategySettings.serializer(), document.getValue("settings")))
        } catch (failure: Exception) { throw IllegalArgumentException("策略内容不兼容或格式错误，未导入", failure) }
        StrategyLibrary.validateProfile(profile.name, profile.note, profile.settings)
        return profile
    }
}

/** The library is the sole durable source. Workers use an in-memory projection only. */
class DesktopStrategyStore private constructor(
    private val store: AtomicConfigStore<StrategyLibrary>,
) : LabyrinthRerollSettingsStore {
    constructor(directory: Path, legacy: () -> LabyrinthStrategySettings, notice: (String) -> Unit,
        transient: Boolean = false, legacyRerolls: () -> Map<Long, LabyrinthRerollSettings> = { emptyMap() },
    ) : this(DesktopLabyrinthConfigurationStore.open(directory, legacy, legacyRerolls, transient) {
            notice("配置损坏，原文件已备份为 ${it.fileName}")
        })

    constructor(context: AppContext, transient: Boolean, notice: (String) -> Unit) :
        this(DesktopLabyrinthConfigurationStore.create(context, transient) { notice("配置损坏，原文件已备份为 ${it.fileName}") })

    val library = store.state
    val state = library.select { it.active.settings }

    private suspend fun change(keepUndo: Boolean = false, update: (StrategyLibrary) -> StrategyLibrary) {
        withContext(Dispatchers.IO) {
            store.update { before ->
                val next = update(before)
                next.copy(revision = before.revision + 1, undoImport = if (keepUndo) next.undoImport else null)
            }
        }
    }

    override fun load(accountId: Long): LabyrinthRerollSettings = library.value.rerolls[accountId] ?: LabyrinthRerollSettings()

    override fun save(accountId: Long, settings: LabyrinthRerollSettings) {
        store.update { it.copy(rerolls = it.rerolls + (accountId to settings), revision = it.revision + 1, undoImport = null) }
    }

    suspend fun importConfiguration(configuration: LabyrinthSharedConfiguration, modules: Set<LabyrinthConfigModule>,
        profileId: String, accountId: Long?, confirmEmptyScores: Boolean, expectedRevision: Long,
    ) = change(keepUndo = true) { value ->
        check(value.revision == expectedRevision) { "配置已变化，请重新预览后导入" }
        val next = configuration.replacement(value.configurationFor(profileId), modules, accountId, confirmEmptyScores)
        value.copy(profiles = value.profiles.map { if (it.id == profileId) it.copy(settings = next.strategy, revision = it.revision + 1) else it },
            rerolls = next.rerolls, undoImport = StrategyImportUndo(value.activeId, value.profiles, value.rerolls))
    }

    suspend fun undoImport(expectedRevision: Long) = change { value ->
        check(value.revision == expectedRevision) { "配置已变化，请重新确认" }
        val undo = value.undoImport ?: error("没有可撤销的导入，或导入后已保存其他配置")
        // Preserve monotonically increasing profile revisions so editors opened before import cannot overwrite an undo.
        value.copy(activeId = undo.activeId, profiles = undo.profiles.map { old ->
            old.copy(revision = maxOf(old.revision, value.profiles.first { it.id == old.id }.revision) + 1)
        }, rerolls = undo.rerolls)
    }

    suspend fun save(settings: LabyrinthStrategySettings) = change { value ->
        value.copy(profiles = value.profiles.map { if (it.id == value.activeId)
            it.copy(settings = settings, revision = it.revision + if (it.settings == settings) 0 else 1) else it })
    }

    suspend fun edit(id: String, revision: Long, name: String, note: String, settings: LabyrinthStrategySettings) = change { value ->
        val old = value.profiles.firstOrNull { it.id == id } ?: error("策略已删除，请重新打开")
        check(old.revision == revision) { "策略已被修改，请重新打开后编辑" }
        require(value.profiles.none { it.id != id && it.name == name.trim() }) { "已有同名策略" }
        value.copy(profiles = value.profiles.map { if (it.id == id) old.copy(name = name.trim(), note = note.trim(),
            settings = settings, revision = old.revision + 1) else it })
    }

    suspend fun add(name: String, note: String = "", settings: LabyrinthStrategySettings = LabyrinthStrategySettings()): String {
        val id = UUID.randomUUID().toString()
        change { value ->
            val base = name.trim().take(60)
            var unique = base; var suffix = 2
            while (value.profiles.any { it.name == unique }) { unique = "${base.take(50)} ($suffix)"; suffix++ }
            value.copy(profiles = value.profiles + StrategyProfile(id, unique, note.trim(), settings = settings))
        }
        return id
    }

    suspend fun activate(id: String) = change { value ->
        require(value.profiles.any { it.id == id }) { "策略不存在" }; value.copy(activeId = id)
    }

    suspend fun delete(id: String) = change { value ->
        require(id != value.activeId) { "请先启用其他策略，再删除此策略" }
        require(value.profiles.any { it.id == id }) { "策略不存在" }
        // Keep the immediately preceding library for recovery of a mistaken deletion.
        store.file?.let { AtomicFiles.write(it.resolveSibling("strategies.before-delete.json"), Files.readAllBytes(it)) }
        value.copy(profiles = value.profiles.filterNot { it.id == id })
    }
}
