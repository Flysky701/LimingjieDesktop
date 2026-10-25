package com.landosol.toolbox.desktop.shared

import androidx.room.withTransaction
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import com.landosol.toolbox.data.local.AccountEntity
import com.landosol.toolbox.data.local.AppDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class RoomCompatibilityTest {
    private fun account(alias: String) = AccountEntity(alias = alias, serverId = "bilibili", gameUid = null, credentialKey = "test-$alias", isSelected = false, createdAt = 1, updatedAt = 1)
    @Test fun transactionsRollbackFlowAndForeignKeys() = runBlocking {
        val context = DesktopContext(Files.createTempDirectory("desktop room 中文").toFile())
        val db = AppDatabase.create(context)
        try {
            val id = db.withTransaction {
                val id = db.accountDao().insert(account("保留"))
                assertNotNull(db.accountDao().getById(id))
                id
            }
            try {
                db.withTransaction { db.accountDao().insert(account("回滚")); throw IllegalStateException("rollback fixture") }
                fail("Expected transaction failure")
            } catch (expected: IllegalStateException) { assertEquals("rollback fixture", expected.message) }
            assertEquals(1, db.accountDao().count())
            assertEquals("保留", withTimeout(5000) { db.accountDao().observeAll().first() }.single().alias)
            db.seedRelatedRecordsForTest(id)
            assertEquals(1, db.featureProfileDao().countForAccount(id))
            db.accountDao().deleteById(id)
            assertEquals(0, db.featureProfileDao().countForAccount(id))
            assertEquals(0, db.labyrinthRouteDao().countForAccount(id))
        } finally { db.close() }
    }
    @Test fun oldSchemasMigrateWithIdentityAndPreserveAccounts() = runBlocking {
        val schemas = File("schemas/com.landosol.toolbox.data.local.AppDatabase")
        val expectedIdentity = JSONObject(File(schemas, "4.json").readText()).getJSONObject("database").getString("identityHash")
        for (version in 1..4) {
            val context = DesktopContext(Files.createTempDirectory("migration-$version").toFile())
            val path = File(context.dataDir, "databases/${AppDatabase.FILE_NAME}").apply { parentFile.mkdirs() }
            val schema = JSONObject(File(schemas, "$version.json").readText()).getJSONObject("database")
            BundledSQLiteDriver().open(path.path).use { connection ->
                val entities = schema.getJSONArray("entities")
                for (i in 0 until entities.length()) {
                    val entity = entities.getJSONObject(i)
                    val table = entity.getString("tableName")
                    connection.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                    val indices = entity.getJSONArray("indices")
                    for (j in 0 until indices.length()) connection.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
                }
                val queries = schema.getJSONArray("setupQueries")
                for (i in 0 until queries.length()) connection.execSQL(queries.getString(i))
                connection.execSQL("PRAGMA user_version = $version")
                connection.execSQL("INSERT INTO accounts(alias,server_id,game_uid,credential_key,is_selected,created_at,updated_at) VALUES('migration','bilibili',NULL,'fixture',0,1,1)")
            }
            val db = AppDatabase.create(context)
            try { assertEquals("migration", db.accountDao().getFirst()!!.alias); assertEquals(1, db.accountDao().count()) }
            finally { db.close() }
            BundledSQLiteDriver().open(path.path).use { connection ->
                connection.prepare("SELECT identity_hash FROM room_master_table WHERE id=42").use { statement ->
                    assertTrue(statement.step()); assertEquals(expectedIdentity, statement.getText(0))
                }
            }
        }
    }
}
