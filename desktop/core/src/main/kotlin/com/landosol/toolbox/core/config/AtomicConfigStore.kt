package com.landosol.toolbox.core.config

import com.landosol.toolbox.desktop.shared.AtomicFiles
import kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.*
import java.nio.charset.CharacterCodingException
import java.nio.file.Files
import java.nio.file.Path

/** The version key is retained on writes so earlier readers can also refuse a downgrade. */
class LegacyConfigFormat<T>(val versionKey: String, val decode: (Int, JsonObject) -> T)

/** A null path is an isolated, memory-only store for a worker process. */
class AtomicConfigStore<T : Any>(
    val file: Path?,
    private val serializer: KSerializer<T>,
    private val defaultValue: () -> T,
    private val schemaVersion: Int = 1,
    private val validate: (T) -> Unit = {},
    private val onRecovered: (Path) -> Unit = {},
    private val migrateLegacy: (() -> T?)? = null,
    private val legacyFormat: LegacyConfigFormat<T>? = null,
) {
    init { require(schemaVersion > 0) }

    // The application owns each data directory with an instance lock. Coordinate facades
    // opened independently inside that JVM as well, without retaining closed stores.
    private val lock = file?.toAbsolutePath()?.normalize()?.let { locks[Math.floorMod(it.hashCode(), locks.size)] } ?: Any()
    private val current = MutableStateFlow(synchronized(lock) { file?.let(::load) ?: defaultValue().also(validate) })
    val state: StateFlow<T> = current.asStateFlow()

    fun update(transform: (T) -> T): T = synchronized(lock) {
        // Re-read the on-disk version and value even when this store was opened earlier.
        val before = file?.let(::load) ?: current.value
        persist(transform(before)).also { current.value = it }
    }

    private fun load(path: Path): T {
        if (Files.notExists(path)) return persist(migrateLegacy?.invoke() ?: defaultValue())
        val upgrade: Boolean
        val loaded = try {
            val doc = json.parseToJsonElement(readText(path)).jsonObject
            if ("schemaVersion" in doc) {
                val version = version(doc, "schemaVersion")
                check(version <= schemaVersion) { "${path.fileName} 需要更新的应用版本（配置版本 $version）" }
                upgrade = version < schemaVersion
                json.decodeFromJsonElement(serializer, requireNotNull(doc["data"]))
            } else {
                val legacy = requireNotNull(legacyFormat) { "缺少配置版本" }
                val version = version(doc, legacy.versionKey)
                check(version < schemaVersion) { "${path.fileName} 需要更新的应用版本（配置版本 $version）" }
                upgrade = true
                legacy.decode(version, doc)
            }.also(validate)
        } catch (_: IllegalArgumentException) {
            val backup = backup(path, "corrupt")
            return persist(defaultValue()).also { onRecovered(backup) }
        }
        if (upgrade) {
            backup(path, "before-upgrade")
            return persist(loaded)
        }
        return loaded
    }

    private fun version(document: JsonObject, key: String): Int =
        requireNotNull((document[key] as? JsonPrimitive)?.intOrNull?.takeIf { it > 0 }) { "无效配置版本" }

    private fun persist(value: T): T {
        validate(value)
        val data = json.encodeToJsonElement(serializer, value)
        val detached = json.decodeFromJsonElement(serializer, data)
        if (file != null) {
            val envelope = buildJsonObject {
                put("schemaVersion", schemaVersion)
                legacyFormat?.let { put(it.versionKey, schemaVersion) }
                put("data", data)
            }
            AtomicFiles.write(file, envelope.toString().toByteArray(Charsets.UTF_8))
        }
        return detached
    }

    companion object {
        private val locks = Array(32) { Any() }
        private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

        private fun readText(path: Path): String = try {
            Files.readString(path)
        } catch (failure: CharacterCodingException) {
            throw IllegalArgumentException("配置文件包含无效的 UTF-8", failure)
        }

        private fun backup(path: Path, reason: String): Path {
            val name = path.fileName.toString().removeSuffix(".json")
            var timestamp = System.currentTimeMillis()
            while (true) {
                val copy = path.resolveSibling("$name.$reason-${timestamp++}.json")
                try { return Files.copy(path, copy) }
                catch (_: java.nio.file.FileAlreadyExistsException) { /* retain both originals */ }
            }
        }

        /** Separate legacy files are left intact; an invalid source cannot discard its siblings. */
        fun <T> readLegacy(path: Path, onRecovered: (Path) -> Unit, decode: (String) -> T): T? {
            if (Files.notExists(path)) return null
            return try { decode(readText(path)) } catch (_: IllegalArgumentException) {
                onRecovered(backup(path, "corrupt"))
                null
            }
        }
    }
}

/** Synchronous projection, including value immediately after a successful write. */
@OptIn(ExperimentalForInheritanceCoroutinesApi::class)
fun <T, R> StateFlow<T>.select(field: (T) -> R): StateFlow<R> = object : StateFlow<R> {
    override val value: R get() = field(this@select.value)
    override val replayCache: List<R> get() = listOf(value)
    override suspend fun collect(collector: FlowCollector<R>): Nothing {
        this@select.map { field(it) }.distinctUntilChanged().collect(collector)
        error("StateFlow completed")
    }
}
