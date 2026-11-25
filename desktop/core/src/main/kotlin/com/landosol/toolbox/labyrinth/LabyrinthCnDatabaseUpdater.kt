package com.landosol.toolbox.labyrinth

import java.io.File
import java.security.MessageDigest
import kotlinx.serialization.Serializable

data class LabyrinthCnDatabaseVersion(
    val version: String,
    val hash: String,
) {
    init {
        require(version.matches(Regex("^\\d{12}$")))
        require(hash.matches(Regex("^[a-fA-F0-9]{64}$")))
    }
}

@Serializable
data class LabyrinthCnDatabaseMetadata(
    val version: String,
    val upstreamHash: String,
    val fileSha256: String,
    val installedAt: Long,
)

fun interface LabyrinthCnDatabaseCandidateValidator {
    fun validate(candidate: File): LabyrinthCnDatabaseValidation
}

sealed interface LabyrinthCnDatabaseValidation {
    data class Valid(val schema: LabyrinthMasterSchemaResolution) : LabyrinthCnDatabaseValidation
    data class Invalid(val reason: String) : LabyrinthCnDatabaseValidation
}

interface LabyrinthCnDatabaseSource {
    fun fetchVersion(): LabyrinthCnDatabaseVersion
    fun downloadDatabase(version: LabyrinthCnDatabaseVersion, destination: File)
}

fun interface LabyrinthCnDatabaseInstaller {
    /** Must preserve the existing database unless the validated candidate can be atomically installed. */
    fun install(candidate: File)
}

interface LabyrinthCnDatabaseMetadataStore {
    fun load(): LabyrinthCnDatabaseMetadata?
    fun save(metadata: LabyrinthCnDatabaseMetadata)
}

sealed interface LabyrinthCnDatabaseUpdateResult {
    data class UpToDate(val metadata: LabyrinthCnDatabaseMetadata) : LabyrinthCnDatabaseUpdateResult
    data class Updated(
        val metadata: LabyrinthCnDatabaseMetadata,
        val schema: LabyrinthMasterSchemaResolution,
    ) : LabyrinthCnDatabaseUpdateResult
    data class Failed(val reason: String) : LabyrinthCnDatabaseUpdateResult
}

/** Download -> SQLite/schema validation -> atomic install -> metadata commit. */
class LabyrinthCnDatabaseUpdater(
    private val source: LabyrinthCnDatabaseSource,
    private val candidateFileFactory: () -> File,
    private val validator: LabyrinthCnDatabaseCandidateValidator,
    private val installer: LabyrinthCnDatabaseInstaller,
    private val metadataStore: LabyrinthCnDatabaseMetadataStore,
    private val databaseAvailable: () -> Boolean = { true },
    private val clock: () -> Long = System::currentTimeMillis,
) {
    fun updateIfNeeded(): LabyrinthCnDatabaseUpdateResult {
        val upstream = runCatching(source::fetchVersion).getOrElse { failure ->
            return LabyrinthCnDatabaseUpdateResult.Failed(
                "检查国服数据库版本失败：${failure.message ?: "网络错误"}",
            )
        }
        val installed = runCatching(metadataStore::load).getOrElse { failure ->
            return LabyrinthCnDatabaseUpdateResult.Failed(
                "读取本地国服数据库版本失败：${failure.message ?: "元数据损坏"}",
            )
        }
        if (
            installed?.version == upstream.version &&
            installed.upstreamHash.equals(upstream.hash, ignoreCase = true) &&
            databaseAvailable()
        ) {
            return LabyrinthCnDatabaseUpdateResult.UpToDate(installed)
        }

        val candidate = runCatching(candidateFileFactory).getOrElse { failure ->
            return LabyrinthCnDatabaseUpdateResult.Failed(
                "创建国服数据库临时文件失败：${failure.message ?: "存储不可用"}",
            )
        }
        return try {
            source.downloadDatabase(upstream, candidate)
            val fileSha256 = sha256(candidate)
            if (!fileSha256.equals(upstream.hash, ignoreCase = true)) {
                return LabyrinthCnDatabaseUpdateResult.Failed(
                    "国服数据库SHA-256不匹配：期望${upstream.hash.lowercase()}，实际$fileSha256",
                )
            }
            when (val validation = validator.validate(candidate)) {
                is LabyrinthCnDatabaseValidation.Invalid ->
                    LabyrinthCnDatabaseUpdateResult.Failed("国服数据库校验失败：${validation.reason}")

                is LabyrinthCnDatabaseValidation.Valid -> {
                    installer.install(candidate)
                    val metadata = LabyrinthCnDatabaseMetadata(
                        version = upstream.version,
                        upstreamHash = upstream.hash.lowercase(),
                        fileSha256 = fileSha256,
                        installedAt = clock(),
                    )
                    metadataStore.save(metadata)
                    LabyrinthCnDatabaseUpdateResult.Updated(metadata, validation.schema)
                }
            }
        } catch (failure: Exception) {
            LabyrinthCnDatabaseUpdateResult.Failed(
                "更新国服数据库失败：${failure.message ?: failure::class.simpleName.orEmpty()}",
            )
        } finally {
            if (candidate.exists()) candidate.delete()
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count > 0) digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }
}
