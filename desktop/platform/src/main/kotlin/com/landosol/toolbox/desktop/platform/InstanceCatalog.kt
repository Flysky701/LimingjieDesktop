package com.landosol.toolbox.desktop.platform

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.WRITE
import java.util.UUID

data class InstanceProfile(val id: UUID, val name: String, val dataDirectory: Path)

/** The legacy directory stays in place. New profiles never copy credentials or account data. */
class InstanceCatalog(private val root: Path, legacyDataDirectory: Path) {
    private val legacy = legacyDataDirectory.toAbsolutePath().normalize()
    private val profilesRoot = root.toAbsolutePath().normalize().resolve("profiles")
    init {
        require(!profilesRoot.startsWith(legacy) && !legacy.startsWith(profilesRoot)) {
            "实例目录须位于旧数据目录之外，避免不同实例的数据目录相互嵌套"
        }
    }
    private val file get() = root.resolve("instances.json")
    private val json = Json { prettyPrint = true }
    @Serializable private data class Entry(val id: String, val name: String, val dataDirectory: String)
    @Serializable private data class Catalog(val version: Int = 1, val profiles: List<Entry>)

    fun list(): List<InstanceProfile> = locked { read().profiles.map(::profile) }
    fun create(name: String): InstanceProfile = locked {
        val current = read()
        val id = UUID.randomUUID()
        val entry = Entry(id.toString(), validName(name), profilesRoot.resolve(id.toString()).toString())
        write(current.copy(profiles = current.profiles + entry))
        profile(entry)
    }
    fun rename(id: UUID, name: String): InstanceProfile = locked {
        val current = read()
        val found = current.profiles.firstOrNull { it.id == id.toString() } ?: error("实例不存在")
        val changed = found.copy(name = validName(name))
        write(current.copy(profiles = current.profiles.map { if (it.id == found.id) changed else it }))
        profile(changed)
    }

    private fun read(): Catalog {
        if (!Files.exists(file)) {
            return Catalog(profiles = listOf(Entry(DEFAULT_PROFILE_ID.toString(), "实例 1", legacy.toString()))).also(::write)
        }
        return json.decodeFromString<Catalog>(Files.readString(file)).also { catalog ->
            check(catalog.version == 1 && catalog.profiles.isNotEmpty()) { "实例目录版本无效" }
            check(catalog.profiles.map { it.id }.distinct().size == catalog.profiles.size) { "实例 ID 重复" }
            val directories = catalog.profiles.map { profile(it).dataDirectory }
            check(directories.indices.all { i -> directories.indices.all { j -> i == j || !directories[i].startsWith(directories[j]) } }) { "实例数据目录重复或相互嵌套" }
            check(catalog.profiles.first().id == DEFAULT_PROFILE_ID.toString()) { "默认实例缺失" }
            catalog.profiles.forEach { validName(it.name); profile(it) }
        }
    }
    private fun write(catalog: Catalog) {
        Files.createDirectories(root)
        val temporary = Files.createTempFile(root, "instances-", ".writing")
        try {
            Files.writeString(temporary, json.encodeToString(catalog))
            FileChannel.open(temporary, WRITE).use { it.force(true) }
            Files.move(temporary, file, ATOMIC_MOVE, REPLACE_EXISTING)
        } finally { Files.deleteIfExists(temporary) }
    }
    private fun profile(entry: Entry): InstanceProfile {
        val path = Path.of(entry.dataDirectory)
        check(path.isAbsolute) { "实例数据目录必须为绝对路径" }
        return InstanceProfile(UUID.fromString(entry.id), entry.name, path.normalize())
    }
    private fun validName(name: String): String = name.trim().also {
        require(it.length in 1..64 && it.none(Char::isISOControl)) { "实例名称须为 1 到 64 个可显示字符" }
    }
    private fun <T> locked(block: () -> T): T {
        val deadline = System.nanoTime() + 2_000_000_000
        while (true) {
            val lease = try { UserResourceLeases.acquireFile(root.resolve("catalog.lock"), "实例目录正在更新") }
            catch (busy: IllegalStateException) {
                if (System.nanoTime() >= deadline) throw busy
                Thread.sleep(10)
                continue
            }
            return lease.use { block() }
        }
    }
    companion object {
        val DEFAULT_PROFILE_ID: UUID = UUID.fromString("00000000-0000-4000-8000-000000000001")
    }
}
