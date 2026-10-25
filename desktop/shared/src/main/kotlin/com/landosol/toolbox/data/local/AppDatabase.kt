package com.landosol.toolbox.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.migration.Migration
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.withTransaction
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.Dispatchers

@Database(
    entities = [
        AccountEntity::class,
        FeatureProfileEntity::class,
        AutomationRunEntity::class,
        LabyrinthRouteEntity::class,
        LabyrinthRerollCheckpointEntity::class,
        LabyrinthRunStateEntity::class,
        LabyrinthRunCharacterEntity::class,
        LabyrinthRunRelicEntity::class,
        MigrationCheckpointEntity::class,
    ],
    version = 4,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun accountDao(): AccountDao
    abstract fun featureProfileDao(): FeatureProfileDao
    abstract fun automationRunDao(): AutomationRunDao
    abstract fun labyrinthRouteDao(): LabyrinthRouteDao
    abstract fun labyrinthRerollCheckpointDao(): LabyrinthRerollCheckpointDao
    abstract fun labyrinthRunStateDao(): LabyrinthRunStateDao
    abstract fun migrationCheckpointDao(): MigrationCheckpointDao

    suspend fun seedRelatedRecordsForTest(accountId: Long) = withTransaction {
        val now = 1L
        featureProfileDao().insert(
            FeatureProfileEntity(
                accountId = accountId,
                name = "test-profile",
                featureType = "labyrinth",
                configurationJson = "{}",
                createdAt = now,
                updatedAt = now,
            ),
        )
        labyrinthRouteDao().insert(
            LabyrinthRouteEntity(
                accountId = accountId,
                runId = null,
                enterId = 1L,
                difficulty = 1,
                attemptCount = 1,
                routeJson = "{}",
                createdAt = now,
            ),
        )
    }

    companion object {
        const val FILE_NAME = "landosol-toolbox.db"

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SQLiteConnection) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `labyrinth_run_states` (
                        `run_key` TEXT NOT NULL,
                        `account_id` INTEGER,
                        `status` TEXT NOT NULL,
                        `current_page` TEXT,
                        `unrecognized_character_count` INTEGER NOT NULL,
                        `started_at` INTEGER NOT NULL,
                        `updated_at` INTEGER NOT NULL,
                        PRIMARY KEY(`run_key`),
                        FOREIGN KEY(`account_id`) REFERENCES `accounts`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_labyrinth_run_states_account_id_status` " +
                        "ON `labyrinth_run_states` (`account_id`, `status`) ",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_labyrinth_run_states_updated_at` " +
                        "ON `labyrinth_run_states` (`updated_at`) ",
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `labyrinth_run_characters` (
                        `run_key` TEXT NOT NULL,
                        `character_id` TEXT NOT NULL,
                        `display_name` TEXT NOT NULL,
                        `order_index` INTEGER NOT NULL,
                        `confidence` REAL NOT NULL,
                        `first_seen_at` INTEGER NOT NULL,
                        `last_seen_at` INTEGER NOT NULL,
                        PRIMARY KEY(`run_key`, `character_id`),
                        FOREIGN KEY(`run_key`) REFERENCES `labyrinth_run_states`(`run_key`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_labyrinth_run_characters_run_key_order_index` " +
                        "ON `labyrinth_run_characters` (`run_key`, `order_index`) ",
                )
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SQLiteConnection) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `labyrinth_run_relics` (
                        `run_key` TEXT NOT NULL,
                        `relic_id` TEXT NOT NULL,
                        `display_name` TEXT NOT NULL,
                        `relic_attribute` TEXT NOT NULL,
                        `attribute_bonus` TEXT NOT NULL,
                        `effect` TEXT NOT NULL,
                        `confidence` REAL NOT NULL,
                        `first_seen_at` INTEGER NOT NULL,
                        `last_seen_at` INTEGER NOT NULL,
                        `observation_count` INTEGER NOT NULL,
                        PRIMARY KEY(`run_key`, `relic_id`),
                        FOREIGN KEY(`run_key`) REFERENCES `labyrinth_run_states`(`run_key`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_labyrinth_run_relics_run_key_last_seen_at` " +
                        "ON `labyrinth_run_relics` (`run_key`, `last_seen_at`) ",
                )
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SQLiteConnection) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `labyrinth_reroll_checkpoints` (
                        `account_id` INTEGER NOT NULL,
                        `attempt_count` INTEGER NOT NULL,
                        `enter_id` INTEGER,
                        `guild_id` INTEGER NOT NULL,
                        `difficulty` INTEGER NOT NULL,
                        `policy_json` TEXT NOT NULL,
                        `status` TEXT NOT NULL,
                        `message` TEXT,
                        `updated_at` INTEGER NOT NULL,
                        PRIMARY KEY(`account_id`),
                        FOREIGN KEY(`account_id`) REFERENCES `accounts`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_labyrinth_reroll_checkpoints_status` " +
                        "ON `labyrinth_reroll_checkpoints` (`status`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_labyrinth_reroll_checkpoints_updated_at` " +
                        "ON `labyrinth_reroll_checkpoints` (`updated_at`)",
                )
            }
        }

        fun create(context: Context): AppDatabase = Room.databaseBuilder<AppDatabase>(
            name = java.io.File(context.filesDir.parentFile, "databases/$FILE_NAME").apply { parentFile.mkdirs() }.path,
        )
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.IO)
            .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
            .build()
    }
}
