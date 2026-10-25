package com.landosol.toolbox.desktop.app.adapters.cndb

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.landosol.toolbox.desktop.shared.DesktopContext
import com.landosol.toolbox.labyrinth.*
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.security.MessageDigest

class DesktopCnDatabaseTest {
    private fun database(semantic: Boolean = false): File {
        val file = Files.createTempDirectory("cn-fixture").resolve("fixture.db").toFile()
        BundledSQLiteDriver().open(file.absolutePath).use { db ->
            fun execute(sql: String) = db.prepare(sql).use { it.step(); Unit }
            if (!semantic) {
                execute("CREATE TABLE v1_a1(unit_id INTEGER PRIMARY KEY,name TEXT,kana TEXT,search_area_width INTEGER,atk_type INTEGER)")
                execute("CREATE TABLE v1_b2(unit_id INTEGER PRIMARY KEY,union_burst_1 INTEGER,main_skill_1 INTEGER,main_skill_2 INTEGER,ex_skill_1 INTEGER)")
                execute("CREATE TABLE v1_c3(skill_id INTEGER PRIMARY KEY,name TEXT,description TEXT,skill_action_1 INTEGER,skill_action_2 INTEGER)")
                execute("CREATE TABLE v1_d4(action_id INTEGER PRIMARY KEY,action_type INTEGER,action_detail_1 INTEGER,target_assignment INTEGER,target_area INTEGER)")
            } else {
                fun create(table: String, ints: Int, texts: Int, reals: Int) {
                    val definitions = (0 until ints).map { "\"${it}a\" INTEGER${if (it == 0) " PRIMARY KEY" else ""}" } +
                        (0 until texts).map { "\"${it}b\" TEXT" } + (0 until reals).map { "\"${it}c\" REAL" }
                    execute("CREATE TABLE $table(${definitions.joinToString(",")})")
                }
                create("v1_aa", 18, 5, 1); create("v1_bb", 33, 0, 0)
                create("v1_cc", 24, 2, 2); create("v1_dd", 12, 2, 7)
                LabyrinthMasterSemanticSchemaResolver.UNIT_ID_ANCHORS.forEachIndexed { index, unit ->
                    val skill = 1_000_001L + index; val action = 100_000_001L + index
                    execute("INSERT INTO v1_aa(\"0a\") VALUES($unit)")
                    execute("INSERT INTO v1_bb(\"0a\",\"1a\") VALUES($unit,$skill)")
                    execute("INSERT INTO v1_cc(\"0a\",\"1a\") VALUES($skill,$action)")
                    execute("INSERT INTO v1_dd(\"0a\",\"1a\",\"2a\") VALUES($action,1,2)")
                }
            }
            // Same download minimum as production, without network or real user data.
            execute("CREATE TABLE padding(payload BLOB)")
            execute("INSERT INTO padding VALUES(zeroblob(1000000))")
        }
        return file
    }
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun source(bytes: ByteArray, expected: String = hash(bytes)) = object : LabyrinthCnDatabaseSource {
        override fun fetchVersion() = LabyrinthCnDatabaseVersion("202610250001", expected)
        override fun downloadDatabase(version: LabyrinthCnDatabaseVersion, destination: File) { destination.writeBytes(bytes) }
    }

    @Test fun readOnlyValidatorSupportsNamedAndHashedColumnsAndRejectsInvalidSqlite() {
        val validator = DesktopCandidateValidator()
        listOf(false, true).forEach { semantic ->
            val fixture = database(semantic); val before = fixture.readBytes()
            val result = validator.validate(fixture)
            assertTrue(result.toString(), result is LabyrinthCnDatabaseValidation.Valid)
            assertArrayEquals(before, fixture.readBytes())
        }
        val file = Files.createTempFile("invalid-cn", ".db").toFile()
        file.writeText("not a SQLite database")
        assertTrue(validator.validate(file) is LabyrinthCnDatabaseValidation.Invalid)
        file.delete()
        BundledSQLiteDriver().open(file.absolutePath).use { db -> db.prepare("CREATE TABLE incomplete(x INTEGER)").use { it.step() } }
        assertTrue(validator.validate(file) is LabyrinthCnDatabaseValidation.Invalid)
    }

    @Test fun failedHashAndSchemaPreserveExistingDatabase() {
        val root = Files.createTempDirectory("cn-preserve")
        val context = DesktopContext(root.toFile())
        val bytes = database().readBytes()
        val good = DesktopLabyrinthCnDatabaseRepository(context, source = source(bytes))
        assertTrue(good.updateIfNeeded() is LabyrinthCnDatabaseUpdateResult.Updated)
        assertTrue(good.updateIfNeeded() is LabyrinthCnDatabaseUpdateResult.UpToDate)
        val mismatch = DesktopLabyrinthCnDatabaseRepository(context, source = source(bytes, "0".repeat(64)))
        assertTrue((mismatch.updateIfNeeded() as LabyrinthCnDatabaseUpdateResult.Failed).reason.contains("SHA-256"))
        val invalid = DesktopLabyrinthCnDatabaseRepository(context, source = source("not sqlite".toByteArray()))
        assertTrue((invalid.updateIfNeeded() as LabyrinthCnDatabaseUpdateResult.Failed).reason.contains("校验失败"))
        assertArrayEquals(bytes, good.currentDatabaseFile()!!.readBytes())
    }

    @Test fun lockedInstallIsPendingUntilNextStartupAndKeepsMetadataHonest() {
        val context = DesktopContext(Files.createTempDirectory("cn-deferred").toFile())
        val original = database().readBytes(); val replacement = database(true).readBytes()
        val first = DesktopLabyrinthCnDatabaseRepository(context, source = source(original))
        assertTrue(first.updateIfNeeded() is LabyrinthCnDatabaseUpdateResult.Updated)
        val notices = mutableListOf<String>()
        val locked = DesktopLabyrinthCnDatabaseRepository(context, source = source(replacement), onNotice = notices::add,
            moveFile = { _, target -> throw AccessDeniedException(target.toString()) })
        assertEquals(DesktopLabyrinthCnDatabaseRepository.PENDING_MESSAGE, (locked.updateIfNeeded() as LabyrinthCnDatabaseUpdateResult.Failed).reason)
        assertTrue(locked.hasPendingInstall())
        assertEquals(hash(original), locked.currentMetadata()!!.fileSha256)
        assertArrayEquals(original, locked.currentDatabaseFile()!!.readBytes())
        val restarted = DesktopLabyrinthCnDatabaseRepository(context, source = source(replacement), onNotice = notices::add)
        assertFalse(restarted.hasPendingInstall())
        assertEquals(hash(replacement), restarted.currentMetadata()!!.fileSha256)
        assertArrayEquals(replacement, restarted.currentDatabaseFile()!!.readBytes())
        assertArrayEquals(original, File(restarted.currentDatabaseFile()!!.parentFile, DesktopLabyrinthCnDatabaseRepository.BACKUP_FILE_NAME).readBytes())
        assertEquals(2, notices.size)
    }

    @Test fun httpSourceUsesOfficialUrlsAndChecksDownloadMinimum() {
        val bytes = database().readBytes(); val calls = mutableListOf<String>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request(); calls.add(request.url.toString())
            val body = if (request.url.toString().endsWith("manifest.json"))
                "{\"db_version\":\"202610250001\",\"checksum_sha256\":\"${hash(bytes)}\"}".toResponseBody()
            else bytes.toResponseBody()
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("fixture").body(body).build()
        }.build()
        val context = DesktopContext(Files.createTempDirectory("cn-http-fixture").toFile())
        val repository = DesktopLabyrinthCnDatabaseRepository(context, client)
        assertTrue(repository.updateIfNeeded() is LabyrinthCnDatabaseUpdateResult.Updated)
        assertEquals(listOf(DesktopLabyrinthCnDatabaseRepository.MANIFEST_URL,
            DesktopLabyrinthCnDatabaseRepository.DATABASE_URL_TEMPLATE.replace("{version}", "202610250001")), calls)
        val small = OkHttpClient.Builder().addInterceptor { chain -> Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
            .code(200).message("fixture").body("tiny".toResponseBody()).build() }.build()
        assertThrows(IllegalStateException::class.java) {
            DesktopLabyrinthCnDatabaseRepository.OkHttpSource(small).downloadDatabase(LabyrinthCnDatabaseVersion("202610250001", hash(bytes)), Files.createTempFile("small", ".db").toFile())
        }
    }

    @Test fun downloadConstantsMatchUpstream() {
        val source = File("../../android/app/src/main/java/com/landosol/toolbox/labyrinth/AndroidLabyrinthCnDatabaseRepository.kt").readText()
        listOf(DesktopLabyrinthCnDatabaseRepository.MANIFEST_URL, DesktopLabyrinthCnDatabaseRepository.DATABASE_URL_TEMPLATE,
            DesktopLabyrinthCnDatabaseRepository.DATABASE_DIRECTORY, DesktopLabyrinthCnDatabaseRepository.DATABASE_FILE_NAME,
            DesktopLabyrinthCnDatabaseRepository.BACKUP_FILE_NAME, DesktopLabyrinthCnDatabaseRepository.METADATA_FILE_NAME).forEach {
            assertTrue("Upstream constant drift: $it", source.contains("\"$it\""))
        }
        assertTrue(source.contains("MINIMUM_DATABASE_BYTES = 1_000_000L"))
    }
}
