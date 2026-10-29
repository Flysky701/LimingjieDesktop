package com.landosol.toolbox.desktop.app.adapters.storage

import com.landosol.toolbox.desktop.app.control.ControlModelCodec
import com.landosol.toolbox.desktop.shared.AtomicFiles
import com.landosol.toolbox.labyrinth.LabyrinthStrategySettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
data class StrategyLibrary(val activeId: String = "", val profiles: List<StrategyProfile> = emptyList()) {
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
        require((document["settings"] as? JsonObject)?.keys == settingKeys) { "策略字段不完整或不兼容，请复制完整分享码或更新助手" }
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
    private val notice: (String) -> Unit, private val transient: Boolean = false) {
    private val file = directory.resolve("settings/strategies.json")
    private val mutex = Mutex()
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
            check(document == null || document["version"]?.jsonPrimitive?.intOrNull in listOf(null, 1)) { "策略库格式较新，请使用更新的助手" }
            val loaded = try {
                require(document?.get("version")?.jsonPrimitive?.intOrNull == 1)
                ControlModelCodec.decode<StrategyLibrary>(document.getValue("library")).also(::validateLibrary)
            } catch (_: Exception) { null }
            if (loaded != null) return loaded
            val backup = file.resolveSibling("strategies.damaged-${UUID.randomUUID()}.json")
            Files.copy(file, backup)
            notice("策略库损坏，原文件已备份为 ${backup.fileName}；已从原配置恢复。")
        }
        val profile = StrategyProfile(UUID.randomUUID().toString(), "我的策略", settings = legacy())
        return StrategyLibrary(profile.id, listOf(profile)).also { if (!transient) persist(it) }
    }

    private fun validateLibrary(value: StrategyLibrary) {
        require(value.profiles.isNotEmpty() && value.profiles.map { it.id }.distinct().size == value.profiles.size)
        value.active
        value.profiles.forEach {
            require(it.id.isNotBlank() && it.revision > 0)
            StrategyShareCodec.validate(it.name, it.note, it.settings)
        }
    }

    private fun persist(value: StrategyLibrary) {
        val document = buildJsonObject { put("version", 1); put("library", ControlModelCodec.encode(value)) }
        AtomicFiles.write(file, document.toString().toByteArray(Charsets.UTF_8))
    }

    private suspend fun change(update: (StrategyLibrary) -> StrategyLibrary) = mutex.withLock {
        val next = update(libraryState.value)
        validateLibrary(next)
        withContext(Dispatchers.IO) { if (!transient) persist(next) }
        libraryState.value = next
        current.value = next.active.settings
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
