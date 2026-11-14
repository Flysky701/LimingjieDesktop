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
class SchemaV5RelicTest {
    @get:Rule val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(), AppDatabase::class.java,
        emptyList<AutoMigrationSpec>(), FrameworkSQLiteOpenHelperFactory(),
    )

    @Test fun migrationPreservesUniqueRelicsWithoutInventingCalibration() {
        val name = "schema-v5-relic-migration.db"
        helper.createDatabase(name, 4).apply {
            execSQL("INSERT INTO labyrinth_run_states VALUES ('account:unbound', NULL, 'ACTIVE', 'NODE_SELECTION', 0, 100, 200)")
            execSQL("INSERT INTO labyrinth_run_relics VALUES ('account:unbound', 'a', 'a', '守备', '2', '', 0.9, 100, 200, 7)")
            execSQL("INSERT INTO labyrinth_run_characters VALUES ('account:unbound', '1001', 'role', 0, 0.9, 100, 200)")
            close()
        }
        helper.runMigrationsAndValidate(name, 5, true, AppDatabase.MIGRATION_4_5).use { db ->
            db.query("SELECT * FROM labyrinth_run_relics").use {
                assertTrue(it.moveToFirst())
                assertEquals(-1, it.getColumnIndex("observation_count"))
                assertEquals("2", it.getString(it.getColumnIndexOrThrow("attribute_bonus")))
                assertEquals(1, it.count)
            }
            db.query("SELECT * FROM labyrinth_run_relic_stacks").use { assertEquals(0, it.count) }
            db.query("SELECT * FROM labyrinth_run_characters").use { assertEquals(1, it.count) }
            db.query("PRAGMA foreign_key_check").use { assertEquals(0, it.count) }
        }
    }

    @Test fun allMigrationsReachCurrentSchema() {
        val name = "schema-v5-full-chain.db"
        helper.createDatabase(name, 1).close()
        helper.runMigrationsAndValidate(name, 5, true,
            AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3, AppDatabase.MIGRATION_3_4, AppDatabase.MIGRATION_4_5).close()
    }

    @Test fun relicsAndCalibrationSurviveReopenAndRejectReplayedOwnership() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "relic-state-reopen-test.db"
        context.deleteDatabase(name)
        fun open() = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
        val mark = LabyrinthRelicMark.DEFENSE
        var database = open()
        try {
            var store: LabyrinthRunStateStore = RoomLabyrinthRunStateStore(database)
            val run = store.startNew(null, 100)
            val stacks = mapOf(mark to LabyrinthRelicStackRecord(9, LabyrinthRelicStackSource.DETAIL),
                LabyrinthRelicMark.CRITICAL to LabyrinthRelicStackRecord(0, LabyrinthRelicStackSource.CHOICE))
            store.recordPage(null, "NODE_SELECTION", emptyList(), 200, listOf(relic("a")),
                expectedStartedAt = run.startedAt, relicStacks = stacks)
            database.close()
            database = open()
            store = RoomLabyrinthRunStateStore(database)
            val loaded = requireNotNull(store.load(null))
            assertEquals(stacks, loaded.relicStacks)
            assertEquals(listOf("a"), loaded.observedRelics.map { it.relicId })
            store.recordPage(null, "NODE_SELECTION", emptyList(), 300, listOf(relic("a"), relic("a")),
                expectedStartedAt = run.startedAt,
                relicStacks = mapOf(mark to LabyrinthRelicStackRecord(11, LabyrinthRelicStackSource.ACQUISITION)))
            assertEquals(stacks, store.load(null)!!.relicStacks)
            store.recordPage(null, "CHARACTER_JOINED", emptyList(), 400, expectedStartedAt = run.startedAt)
            assertEquals(stacks, store.load(null)!!.relicStacks)
            val next = store.startNew(null, 50)
            assertTrue(next.startedAt > run.startedAt)
            assertTrue(next.observedRelics.isEmpty())
            assertTrue(next.relicStacks.isEmpty())
            val stale = runCatching {
                store.recordPage(null, "NODE_SELECTION", emptyList(), 500, listOf(relic("a")),
                    expectedStartedAt = run.startedAt, relicStacks = stacks)
            }
            assertTrue(stale.isFailure)
            assertTrue(store.load(null)!!.observedRelics.isEmpty())
        } finally {
            database.close()
            context.deleteDatabase(name)
        }
    }

    @Test fun invalidStackRollsBackOwnershipInTheSameTransaction() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val store: LabyrinthRunStateStore = RoomLabyrinthRunStateStore(database)
            val run = store.begin(null, 100)
            val failed = runCatching {
                store.recordPage(null, "NODE_SELECTION", emptyList(), 200, listOf(relic("a")),
                    expectedStartedAt = run.startedAt,
                    relicStacks = mapOf(LabyrinthRelicMark.DEFENSE to LabyrinthRelicStackRecord(99, LabyrinthRelicStackSource.DETAIL)))
            }
            assertTrue(failed.isFailure)
            assertTrue(store.load(null)!!.observedRelics.isEmpty())
            assertTrue(store.load(null)!!.relicStacks.isEmpty())
        } finally {
            database.close()
        }
    }

    @Test fun distinctRelicsAddOnceAndPartialCorrectionsPreserveOtherSeries() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val store: LabyrinthRunStateStore = RoomLabyrinthRunStateStore(database)
            val run = store.startNew(null, 100)
            val mark = LabyrinthRelicMark.DEFENSE
            val other = LabyrinthRelicMark.CRITICAL
            store.recordPage(null, "NODE_SELECTION", emptyList(), 200, listOf(relic("a")),
                expectedStartedAt = run.startedAt,
                relicStacks = mapOf(mark to LabyrinthRelicStackRecord(2, LabyrinthRelicStackSource.ACQUISITION),
                    other to LabyrinthRelicStackRecord(0, LabyrinthRelicStackSource.CHOICE)))
            repeat(2) {
                store.recordPage(null, "NODE_SELECTION", emptyList(), 300, listOf(relic("b")),
                    expectedStartedAt = run.startedAt,
                    relicStacks = mapOf(mark to LabyrinthRelicStackRecord(4, LabyrinthRelicStackSource.ACQUISITION)))
            }
            assertEquals(2, store.load(null)!!.observedRelics.size)
            assertEquals(4, store.load(null)!!.relicStacks.getValue(mark).stacks)
            store.recordPage(null, "NODE_SELECTION", emptyList(), 400, expectedStartedAt = run.startedAt,
                relicStacks = mapOf(mark to LabyrinthRelicStackRecord(4, LabyrinthRelicStackSource.DETAIL)))
            val corrected = store.load(null)!!.relicStacks
            assertEquals(LabyrinthRelicStackSource.DETAIL, corrected.getValue(mark).source)
            assertEquals(LabyrinthRelicStackRecord(0, LabyrinthRelicStackSource.CHOICE), corrected[other])
        } finally { database.close() }
    }

    @Test fun newRunIsMonotonicAccountScopedAndRejectsStaleRoleWrites() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            fun account(alias: String) = com.landosol.toolbox.data.local.AccountEntity(
                alias = alias, serverId = "test", gameUid = null, credentialKey = "test-$alias",
                isSelected = false, createdAt = 0, updatedAt = 0,
            )
            val a = database.accountDao().insert(account("a"))
            val b = database.accountDao().insert(account("b"))
            val store: LabyrinthRunStateStore = RoomLabyrinthRunStateStore(database)
            val firstA = store.startNew(a, 100)
            val firstB = store.startNew(b, 100)
            val record = mapOf(LabyrinthRelicMark.DEFENSE to LabyrinthRelicStackRecord(2, LabyrinthRelicStackSource.ACQUISITION))
            store.recordPage(b, "NODE_SELECTION", emptyList(), 200, listOf(relic("a")),
                expectedStartedAt = firstB.startedAt, relicStacks = record)
            val secondA = store.startNew(a, 100)
            assertEquals(firstA.startedAt + 1, secondA.startedAt)
            val thirdA = store.startNew(a, 50)
            assertEquals(secondA.startedAt + 1, thirdA.startedAt)
            val role = com.landosol.toolbox.labyrinth.vision.LabyrinthCharacterMatch(
                "joined", "1001", "role", 0.99, EntryPixelRect(0, 0, 10, 10))
            assertTrue(runCatching {
                store.recordPage(a, "CHARACTER_JOINED", listOf(role), 300, expectedStartedAt = firstA.startedAt)
            }.isFailure)
            assertTrue(store.load(a)!!.joinedCharacters.isEmpty())
            assertTrue(store.load(a)!!.observedRelics.isEmpty())
            assertEquals(listOf("a"), store.load(b)!!.observedRelics.map { it.relicId })
            assertEquals(record, store.load(b)!!.relicStacks)
        } finally { database.close() }
    }

    private fun relic(id: String) = LabyrinthRelicMatch("slot", id, id, "守备", "2", "", 0.9,
        EntryPixelRect(0, 0, 10, 10), iconRect = EntryPixelRect(0, 0, 5, 5))
}
