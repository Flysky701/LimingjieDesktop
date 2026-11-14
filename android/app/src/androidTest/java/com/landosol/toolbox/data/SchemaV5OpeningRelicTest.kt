package com.landosol.toolbox.data

import android.content.Context
import androidx.room.Room
import androidx.room.migration.AutoMigrationSpec
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.landosol.toolbox.data.local.AppDatabase
import com.landosol.toolbox.labyrinth.*
import com.landosol.toolbox.labyrinth.vision.EntryPixelRect
import com.landosol.toolbox.labyrinth.vision.LabyrinthRelicMatch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SchemaV5OpeningRelicTest {
    @get:Rule val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(), AppDatabase::class.java,
        emptyList<AutoMigrationSpec>(), FrameworkSQLiteOpenHelperFactory(),
    )

    @Test fun migrationKeepsExistingOwnershipWithoutInventingAnOpeningReceipt() {
        val name = "schema-v5-opening-migration.db"
        helper.createDatabase(name, 4).apply {
            execSQL("INSERT INTO labyrinth_run_states VALUES ('account:unbound', NULL, 'ACTIVE', 'NODE_SELECTION', 0, 100, 200)")
            execSQL("INSERT INTO labyrinth_run_relics VALUES ('account:unbound', '40003', 'base', '加速', '1', '', 1.0, 100, 200, 7)")
            close()
        }
        helper.runMigrationsAndValidate(name, 5, true, AppDatabase.MIGRATION_4_5).use { db ->
            db.query("SELECT * FROM labyrinth_run_states").use {
                assertTrue(it.moveToFirst())
                assertTrue(it.isNull(it.getColumnIndexOrThrow("opening_enter_id")))
                assertEquals(0, it.getInt(it.getColumnIndexOrThrow("opening_relics_complete")))
                assertEquals("NODE_SELECTION", it.getString(it.getColumnIndexOrThrow("current_page")))
            }
            db.query("SELECT * FROM labyrinth_run_relics").use { assertEquals(1, it.count) }
            db.query("PRAGMA foreign_key_check").use { assertEquals(0, it.count) }
        }
    }

    @Test fun openingReceiptSurvivesPageChangesAndReopenButNotNewRun() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "opening-relic-receipt-reopen.db"
        context.deleteDatabase(name)
        fun open() = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
        var database = open()
        try {
            var store = RoomLabyrinthRunStateStore(database)
            val run = store.startNew(null, 100)
            val rect = EntryPixelRect(0, 0, 1, 1)
            val relic = LabyrinthRelicMatch("opening:40003", "40003", "base", "加速", "1", "", 1.0, rect, iconRect = rect)
            val receipt = LabyrinthOpeningRelicReceipt(42, true)
            store.recordPage(null, "INITIAL_CHARACTER_SELECTION", emptyList(), 110, relics = listOf(relic),
                expectedStartedAt = run.startedAt, openingRelicReceipt = receipt)
            store.recordPage(null, "CHARACTER_JOINED", emptyList(), 120, expectedStartedAt = run.startedAt)
            store.recordPage(null, "NODE_SELECTION", emptyList(), 130, expectedStartedAt = run.startedAt)
            database.close()
            database = open()
            store = RoomLabyrinthRunStateStore(database)
            val loaded = requireNotNull(store.load(null))
            assertEquals(receipt, loaded.openingRelicReceipt)
            assertEquals("NODE_SELECTION", loaded.currentPage)
            assertEquals(listOf("40003"), loaded.observedRelics.map { it.relicId })
            var rejected = false
            try { store.recordPage(null, "UNKNOWN", emptyList(), 140, relics = listOf(relic.copy(relicId = "50003")),
                expectedStartedAt = run.startedAt, openingRelicReceipt = LabyrinthOpeningRelicReceipt(43, true))
            } catch (_: IllegalStateException) { rejected = true }
            assertTrue(rejected)
            assertEquals(listOf("40003"), store.load(null)!!.observedRelics.map { it.relicId })
            store.recordPage(null, "UNKNOWN", emptyList(), 150, expectedStartedAt = run.startedAt,
                openingRelicReceipt = LabyrinthOpeningRelicReceipt(42, false))
            assertEquals(receipt, store.load(null)!!.openingRelicReceipt)
            assertNull(store.startNew(null, 200).openingRelicReceipt)
        } finally { database.close(); context.deleteDatabase(name) }
    }
}
