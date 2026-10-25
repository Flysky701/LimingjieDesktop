package com.landosol.toolbox.desktop.app.adapters.cndb

import android.content.Context
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.driver.bundled.SQLITE_OPEN_READONLY
import com.landosol.toolbox.desktop.shared.AtomicFiles
import com.landosol.toolbox.labyrinth.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.*
import java.security.MessageDigest

/** Uses the upstream update pipeline; only filesystem and SQLite platform access are replaced. */
class DesktopLabyrinthCnDatabaseRepository(
    context: Context,
    client: OkHttpClient = OkHttpClient(),
    private val onNotice: (String) -> Unit = {},
    source: LabyrinthCnDatabaseSource? = null,
    private val moveFile: (Path, Path) -> Unit = ::moveAtomic,
) {
    private val directory = File(context.filesDir, DATABASE_DIRECTORY)
    private val databaseFile = File(directory, DATABASE_FILE_NAME)
    private val metadataFile = File(directory, METADATA_FILE_NAME)
    private val pendingFile = File(directory, "master_cn.pending.db")
    private val pendingMetadata = File(directory, "master_cn.pending.metadata.json")
    private val json = Json { ignoreUnknownKeys = true }
    private val validator = DesktopCandidateValidator()
    private var deferred = false
    private val updater = LabyrinthCnDatabaseUpdater(
        source = source ?: OkHttpSource(client),
        candidateFileFactory = { Files.createDirectories(directory.toPath()); File.createTempFile("master-cn-candidate-", ".db", directory) },
        validator = validator,
        installer = LabyrinthCnDatabaseInstaller { candidate -> installOrDefer(candidate) },
        metadataStore = object : LabyrinthCnDatabaseMetadataStore {
            override fun load(): LabyrinthCnDatabaseMetadata? = readMetadata(metadataFile)
            override fun save(metadata: LabyrinthCnDatabaseMetadata) {
                AtomicFiles.write((if (deferred) pendingMetadata else metadataFile).toPath(), json.encodeToString(metadata).toByteArray(Charsets.UTF_8))
            }
        },
        databaseAvailable = databaseFile::isFile,
    )

    init { installPendingBeforeRead() }

    @Synchronized fun updateIfNeeded(): LabyrinthCnDatabaseUpdateResult {
        if (pendingFile.isFile && pendingMetadata.isFile) return LabyrinthCnDatabaseUpdateResult.Failed(PENDING_MESSAGE)
        deferred = false
        val result = updater.updateIfNeeded()
        return if (deferred && result is LabyrinthCnDatabaseUpdateResult.Updated) {
            onNotice(PENDING_MESSAGE); LabyrinthCnDatabaseUpdateResult.Failed(PENDING_MESSAGE)
        } else result
    }
    fun currentDatabaseFile(): File? = databaseFile.takeIf(File::isFile)
    fun currentMetadata(): LabyrinthCnDatabaseMetadata? = readMetadata(metadataFile)
    fun hasPendingInstall(): Boolean = pendingFile.isFile
    private fun readMetadata(file: File): LabyrinthCnDatabaseMetadata? = if (file.isFile) json.decodeFromString(file.readText(Charsets.UTF_8)) else null

    private fun replace(candidate: File) {
        require(candidate.canonicalFile.parentFile == directory.canonicalFile) { "Candidate escaped database directory" }
        require(databaseFile.canonicalFile.parentFile == directory.canonicalFile) { "Target escaped database directory" }
        require(candidate.isFile) { "Candidate disappeared before install" }
        if (databaseFile.isFile) Files.copy(databaseFile.toPath(), File(directory, BACKUP_FILE_NAME).toPath(), REPLACE_EXISTING)
        moveFile(candidate.toPath(), databaseFile.toPath())
    }

    private fun installOrDefer(candidate: File) {
        try { replace(candidate) }
        catch (failure: java.nio.file.AtomicMoveNotSupportedException) { throw failure }
        catch (_: java.nio.file.FileSystemException) {
            // FB-08: the validated candidate is preserved rather than reported as installed.
            moveAtomic(candidate.toPath(), pendingFile.toPath())
            deferred = true
        }
    }

    private fun installPendingBeforeRead() {
        if (!pendingFile.isFile) return
        var installing: Path? = null
        try {
            val metadata = readMetadata(pendingMetadata) ?: error("缺少暂存数据库版本信息")
            check(sha256(pendingFile).equals(metadata.fileSha256, ignoreCase = true)) { "暂存数据库 SHA-256 不匹配" }
            check(validator.validate(pendingFile) is LabyrinthCnDatabaseValidation.Valid) { "暂存数据库校验失败" }
            // Keep the pending pair recoverable until both replacement and metadata commit succeed.
            installing = Files.createTempFile(directory.toPath(), "master_cn.db.tmp-", ".db")
            Files.copy(pendingFile.toPath(), installing, REPLACE_EXISTING)
            replace(installing.toFile())
            AtomicFiles.write(metadataFile.toPath(), json.encodeToString(metadata).toByteArray(Charsets.UTF_8))
            Files.deleteIfExists(pendingFile.toPath())
            Files.deleteIfExists(pendingMetadata.toPath())
            onNotice("已安装上次下载的国服数据库")
        } catch (failure: Exception) {
            onNotice("暂存国服数据库安装尚未完成：${failure.message ?: "未知错误"}。已保留暂存文件供下次启动重试。")
        } finally { installing?.let(Files::deleteIfExists) }
    }

    class OkHttpSource(private val client: OkHttpClient) : LabyrinthCnDatabaseSource {
        override fun fetchVersion(): LabyrinthCnDatabaseVersion {
            client.newCall(Request.Builder().url(MANIFEST_URL).get().build()).execute().use { response ->
                check(response.isSuccessful) { "manifest HTTP ${response.code}" }
                val manifest = JSONObject(response.body?.string() ?: error("empty manifest response"))
                return LabyrinthCnDatabaseVersion(manifest.getString("db_version"), manifest.getString("checksum_sha256"))
            }
        }
        override fun downloadDatabase(version: LabyrinthCnDatabaseVersion, destination: File) {
            client.newCall(Request.Builder().url(DATABASE_URL_TEMPLATE.replace("{version}", version.version)).get().build()).execute().use { response ->
                check(response.isSuccessful) { "database HTTP ${response.code}" }
                val body = response.body ?: error("empty database response")
                destination.outputStream().buffered().use { output -> body.byteStream().use { it.copyTo(output) }; output.flush() }
                check(destination.length() >= MINIMUM_DATABASE_BYTES) { "downloaded database is too small" }
            }
        }
    }

    companion object {
        const val MANIFEST_URL = "https://github.com/wbero/autopcr-db-builder/releases/latest/download/manifest.json"
        const val DATABASE_URL_TEMPLATE = "https://github.com/wbero/autopcr-db-builder/releases/download/db-v{version}/{version}.db"
        const val DATABASE_DIRECTORY = "labyrinth/master-cn"
        const val DATABASE_FILE_NAME = "master_cn.db"
        const val BACKUP_FILE_NAME = "master_cn.previous.db"
        const val METADATA_FILE_NAME = "master_cn.metadata.json"
        const val MINIMUM_DATABASE_BYTES = 1_000_000L
        const val PENDING_MESSAGE = "数据库更新已下载，将在下次启动时生效"
        private fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().buffered().use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}

/** FB-04; will be shared with AtomicFiles.move when the assembly owner lands that common primitive. */
private fun moveAtomic(source: Path, target: Path) {
    for (attempt in 0..4) {
        try { Files.move(source, target, ATOMIC_MOVE, REPLACE_EXISTING); return }
        catch (failure: java.nio.file.AtomicMoveNotSupportedException) { throw failure }
        catch (failure: java.nio.file.FileSystemException) { if (attempt == 4) throw failure; Thread.sleep(100) }
    }
}

class DesktopCandidateValidator : LabyrinthCnDatabaseCandidateValidator {
    override fun validate(candidate: File): LabyrinthCnDatabaseValidation {
        val header = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)
        if (!candidate.isFile || candidate.length() < header.size) return LabyrinthCnDatabaseValidation.Invalid("文件不存在或为空")
        val actual = candidate.inputStream().use { it.readNBytes(header.size) }
        if (actual.size != header.size) return LabyrinthCnDatabaseValidation.Invalid("文件头不完整")
        if (!actual.contentEquals(header)) return LabyrinthCnDatabaseValidation.Invalid("文件头不是SQLite format 3")
        return try {
            BundledSQLiteDriver().open(candidate.absolutePath, SQLITE_OPEN_READONLY).use { database ->
                val quickCheck = database.prepare("PRAGMA quick_check").use { if (it.step()) it.getText(0) else null }
                if (quickCheck != "ok") LabyrinthCnDatabaseValidation.Invalid("quick_check未通过")
                else {
                    val schemas = inspectSchemas(database)
                    val named = LabyrinthMasterSchemaResolver().resolve(schemas)
                    val resolution = if (named.complete) named else LabyrinthMasterSemanticSchemaResolver().resolve(schemas, DesktopMasterDataProbe(database))
                    if (resolution.complete) LabyrinthCnDatabaseValidation.Valid(resolution)
                    else LabyrinthCnDatabaseValidation.Invalid("逻辑表识别不完整：${resolution.issues.joinToString("；")}")
                }
            }
        } catch (failure: Exception) { LabyrinthCnDatabaseValidation.Invalid("SQLite只读校验异常：${failure.message ?: "未知错误"}") }
    }

    private fun inspectSchemas(database: SQLiteConnection): List<LabyrinthSqliteTableSchema> {
        val names = database.prepare("SELECT name FROM sqlite_master WHERE type = ? AND name NOT LIKE ? ORDER BY name").use { query ->
            query.bindText(1, "table"); query.bindText(2, "sqlite_%")
            buildList { while (query.step()) query.getText(0).takeIf(LabyrinthSqlIdentifier::isSafe)?.let(::add) }
        }
        return names.mapNotNull { name ->
            val definitions = database.prepare("PRAGMA table_info(${LabyrinthSqlIdentifier.quoteEnumerated(name)})").use { query ->
                val columnNames = (0 until query.getColumnCount()).map(query::getColumnName)
                val nameIndex = columnNames.indexOf("name").also { check(it >= 0) }
                val typeIndex = columnNames.indexOf("type").also { check(it >= 0) }
                val primaryKeyIndex = columnNames.indexOf("pk").also { check(it >= 0) }
                buildList {
                    while (query.step()) {
                        val column = query.getText(nameIndex)
                        if (LabyrinthSqlIdentifier.isSafe(column)) add(LabyrinthSqliteColumnSchema(column, query.getText(typeIndex), query.getLong(primaryKeyIndex).toInt()))
                    }
                }
            }
            definitions.takeIf { it.isNotEmpty() }?.let { LabyrinthSqliteTableSchema(name, it.mapTo(linkedSetOf(), LabyrinthSqliteColumnSchema::physicalName), it) }
        }
    }

    private class DesktopMasterDataProbe(private val database: SQLiteConnection) : LabyrinthMasterDataProbe {
        override fun primaryKeyValuesMatching(schema: LabyrinthSqliteTableSchema, candidates: Set<Long>): Set<Long> {
            val primaryKey = schema.singleIntegerPrimaryKey ?: return emptySet()
            if (candidates.isEmpty()) return emptySet()
            val table = LabyrinthSqlIdentifier.quoteEnumerated(schema.physicalName)
            val column = LabyrinthSqlIdentifier.quoteEnumerated(primaryKey.physicalName)
            return candidates.chunked(MAX_BIND_ARGUMENTS).flatMapTo(linkedSetOf()) { chunk ->
                val placeholders = List(chunk.size) { "?" }.joinToString(",")
                database.prepare("SELECT DISTINCT $column FROM $table WHERE $column IN ($placeholders)").use { query ->
                    chunk.forEachIndexed { index, value -> query.bindLong(index + 1, value) }
                    buildList { while (query.step()) add(query.getLong(0)) }
                }
            }
        }
        override fun integerValuesForPrimaryKeys(schema: LabyrinthSqliteTableSchema, primaryKeys: Set<Long>, range: LongRange): Set<Long> {
            val primaryKey = schema.singleIntegerPrimaryKey ?: return emptySet()
            if (primaryKeys.isEmpty()) return emptySet()
            val table = LabyrinthSqlIdentifier.quoteEnumerated(schema.physicalName)
            val key = LabyrinthSqlIdentifier.quoteEnumerated(primaryKey.physicalName)
            val columns = schema.columnDefinitions.filter { it.normalizedType == "INTEGER" }.map { LabyrinthSqlIdentifier.quoteEnumerated(it.physicalName) }
            if (columns.isEmpty()) return emptySet()
            return primaryKeys.chunked(MAX_BIND_ARGUMENTS).flatMapTo(linkedSetOf()) { chunk ->
                val placeholders = List(chunk.size) { "?" }.joinToString(",")
                database.prepare("SELECT ${columns.joinToString(",")} FROM $table WHERE $key IN ($placeholders)").use { query ->
                    chunk.forEachIndexed { index, value -> query.bindLong(index + 1, value) }
                    buildList {
                        while (query.step()) for (index in columns.indices) if (!query.isNull(index)) query.getLong(index).takeIf(range::contains)?.let(::add)
                    }
                }
            }
        }
        companion object { const val MAX_BIND_ARGUMENTS = 400 }
    }
}
