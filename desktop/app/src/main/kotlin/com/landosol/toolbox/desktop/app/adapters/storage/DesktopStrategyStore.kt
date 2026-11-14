package com.landosol.toolbox.desktop.app.adapters.storage

import com.landosol.toolbox.desktop.app.control.ControlModelCodec
import com.landosol.toolbox.desktop.shared.AtomicFiles
import com.landosol.toolbox.labyrinth.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.UUID
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

data class StrategyIdentity(val id: String, val name: String, val revision: Long)
data class StrategyProfile(val id: String, val name: String, val note: String = "", val revision: Long = 1,
    val settings: LabyrinthStrategySettings = LabyrinthStrategySettings()) {
    val identity get() = StrategyIdentity(id, name, revision)
}
data class StrategyImportUndo(val activeId: String, val profiles: List<StrategyProfile>, val rerolls: Map<Long, LabyrinthRerollSettings>)
data class StrategyLibrary(val activeId: String = "", val profiles: List<StrategyProfile> = emptyList(),
    val rerolls: Map<Long, LabyrinthRerollSettings> = emptyMap(), val revision: Long = 1,
    val undoImport: StrategyImportUndo? = null) {
    val active get() = profiles.first { it.id == activeId }
}

/** Explicit portable envelope. Never serialize accounts, runtime state or a Graph. */
object StrategyShareCodec {
    private const val PREFIX = "LMJ1:"
    private const val MAX_BYTES = 256 * 1024
    private val json = Json { encodeDefaults = true }
    private val settingKeys = json.encodeToJsonElement(LabyrinthStrategySettings.serializer(), LabyrinthStrategySettings()).jsonObject.keys

    fun encode(profile: StrategyProfile): String {
        validate(profile.name, profile.note, profile.settings)
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
        val addedFields = setOf("eventCustomizationEnabled", "eventChoiceOverrides")
        require(keys.all { it in settingKeys } && keys.containsAll(settingKeys - addedFields)) { "策略字段不完整或不兼容，请复制完整分享码或更新助手" }
        val profile = try {
            StrategyProfile(UUID.randomUUID().toString(), document.getValue("name").jsonPrimitive.content,
                document.getValue("note").jsonPrimitive.content, settings =
                    json.decodeFromJsonElement(LabyrinthStrategySettings.serializer(), document.getValue("settings")))
        } catch (failure: Exception) { throw IllegalArgumentException("策略内容不兼容或格式错误，未导入", failure) }
        validate(profile.name, profile.note, profile.settings)
        return profile
    }

    internal fun validate(name: String, note: String, settings: LabyrinthStrategySettings) {
        require(name.isNotBlank() && name.length <= 60 && name.none { it.isISOControl() }) { "策略名称须为 1–60 个字符，不能含换行" }
        require(note.length <= 300) { "备注最多 300 个字符" }
        settings.validationError()?.let { error(it) }
    }
}

/** The library is the sole durable source. Workers use an in-memory projection only. */
class DesktopStrategyStore(directory: Path, legacy: () -> LabyrinthStrategySettings,
    private val notice: (String) -> Unit, private val transient: Boolean = false,
    private val legacyRerolls: () -> Map<Long, LabyrinthRerollSettings> = { emptyMap() },
) : LabyrinthRerollSettingsStore {
    private val file = directory.resolve("settings/strategies.json")
    private val lock = Any()
    private val libraryState = MutableStateFlow(load(legacy))
    val library = libraryState.asStateFlow()
    private val current = MutableStateFlow(libraryState.value.active.settings)
    val state = current.asStateFlow()

    private fun load(legacy: () -> LabyrinthStrategySettings): StrategyLibrary {
        if (!transient && Files.exists(file)) {
            val raw = Files.readString(file)
            val document = try { Json.parseToJsonElement(raw).jsonObject }
                catch (_: Exception) { null }
            // A newer library must not be replaced by an older application.
            check(document == null || document["version"]?.jsonPrimitive?.intOrNull in listOf(null, 1, 2)) { "策略库格式较新，请使用更新的助手" }
            val loaded = try {
                require(document?.get("version")?.jsonPrimitive?.intOrNull in listOf(1, 2))
                ControlModelCodec.decode<StrategyLibrary>(requireNotNull(document).getValue("library")).also(::validateLibrary)
            } catch (_: Exception) { null }
            if (loaded != null) {
                if (document?.get("version")?.jsonPrimitive?.intOrNull == 2) return loaded
                Files.copy(file, file.resolveSibling("strategies.before-upgrade-${UUID.randomUUID()}.json"))
                return loaded.copy(rerolls = legacyRerolls()).also { validateLibrary(it); persist(it) }
            }
            val backup = file.resolveSibling("strategies.damaged-${UUID.randomUUID()}.json")
            Files.copy(file, backup)
            notice("策略库损坏，原文件已备份为 ${backup.fileName}；已从原配置恢复。")
        }
        val profile = StrategyProfile(UUID.randomUUID().toString(), "我的策略", settings = legacy())
        return StrategyLibrary(profile.id, listOf(profile), if (transient) emptyMap() else legacyRerolls()).also {
            validateLibrary(it)
            if (!transient) persist(it)
        }
    }

    private fun validateLibrary(value: StrategyLibrary) {
        require(value.profiles.isNotEmpty() && value.profiles.map { it.id }.distinct().size == value.profiles.size)
        value.active
        value.profiles.forEach {
            require(it.id.isNotBlank() && it.revision > 0)
            StrategyShareCodec.validate(it.name, it.note, it.settings)
        }
        value.rerolls.forEach { (id, settings) -> require(id > 0); require(settings.validationError() == null) }
        require(value.revision > 0)
        value.undoImport?.let { validateLibrary(StrategyLibrary(it.activeId, it.profiles, it.rerolls)) }
    }

    private fun persist(value: StrategyLibrary) {
        val document = buildJsonObject { put("version", 2); put("library", ControlModelCodec.encode(value)) }
        AtomicFiles.write(file, document.toString().toByteArray(Charsets.UTF_8))
    }

    private fun commit(next: StrategyLibrary) {
        validateLibrary(next)
        val detached = ControlModelCodec.decode<StrategyLibrary>(ControlModelCodec.encode(next))
        if (!transient) persist(detached)
        libraryState.value = detached
        current.value = detached.active.settings
    }

    private suspend fun change(keepUndo: Boolean = false, update: (StrategyLibrary) -> StrategyLibrary) = withContext(Dispatchers.IO) {
        synchronized(lock) {
            val before = libraryState.value
            val next = update(before)
            commit(next.copy(revision = before.revision + 1, undoImport = if (keepUndo) next.undoImport else null))
        }
    }

    override fun load(accountId: Long): LabyrinthRerollSettings = library.value.rerolls[accountId] ?: LabyrinthRerollSettings()

    override fun save(accountId: Long, settings: LabyrinthRerollSettings) = synchronized(lock) {
        require(accountId > 0)
        val before = libraryState.value
        commit(before.copy(rerolls = before.rerolls + (accountId to settings), revision = before.revision + 1, undoImport = null))
    }

    suspend fun importConfiguration(configuration: LabyrinthSharedConfiguration, modules: Set<LabyrinthConfigModule>,
        profileId: String, accountId: Long?, confirmEmptyScores: Boolean, expectedRevision: Long,
    ) = change(keepUndo = true) { value ->
        check(value.revision == expectedRevision) { "配置已变化，请重新预览后导入" }
        val profile = value.profiles.firstOrNull { it.id == profileId } ?: error("目标策略不存在")
        val next = configuration.replacement(LabyrinthConfigurationValues(profile.settings, value.rerolls), modules, accountId, confirmEmptyScores)
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
        if (!transient) AtomicFiles.write(file.resolveSibling("strategies.before-delete.json"), Files.readAllBytes(file))
        value.copy(profiles = value.profiles.filterNot { it.id == id })
    }
}
