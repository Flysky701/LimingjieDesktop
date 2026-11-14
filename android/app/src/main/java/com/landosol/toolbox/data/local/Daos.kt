package com.landosol.toolbox.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface AccountDao {
    @Query("SELECT * FROM accounts ORDER BY is_selected DESC, updated_at DESC, id DESC")
    fun observeAll(): Flow<List<AccountEntity>>

    @Query("SELECT * FROM accounts WHERE id = :id")
    suspend fun getById(id: Long): AccountEntity?

    @Query("SELECT * FROM accounts WHERE is_selected = 1 LIMIT 1")
    suspend fun getSelected(): AccountEntity?

    @Query("SELECT * FROM accounts ORDER BY updated_at DESC, id DESC LIMIT 1")
    suspend fun getFirst(): AccountEntity?

    @Query("SELECT COUNT(*) FROM accounts")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(account: AccountEntity): Long

    @Update(onConflict = OnConflictStrategy.ABORT)
    suspend fun update(account: AccountEntity)

    @Query("DELETE FROM accounts WHERE id = :id")
    suspend fun deleteById(id: Long): Int

    @Query("UPDATE accounts SET is_selected = 0 WHERE is_selected = 1")
    suspend fun clearSelection()

    @Query("UPDATE accounts SET is_selected = 1, updated_at = :updatedAt WHERE id = :id")
    suspend fun markSelected(id: Long, updatedAt: Long): Int

    @Transaction
    suspend fun selectAccount(id: Long, updatedAt: Long = System.currentTimeMillis()) {
        require(getById(id) != null) { "Account not found" }
        clearSelection()
        check(markSelected(id, updatedAt) == 1) { "Account selection failed" }
    }
}

@Dao
interface FeatureProfileDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(profile: FeatureProfileEntity): Long

    @Query("SELECT COUNT(*) FROM feature_profiles WHERE account_id = :accountId")
    suspend fun countForAccount(accountId: Long): Int
}

@Dao
interface AutomationRunDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(run: AutomationRunEntity): Long
}

@Dao
interface LabyrinthRouteDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(route: LabyrinthRouteEntity): Long

    @Query("SELECT COUNT(*) FROM labyrinth_routes WHERE account_id = :accountId")
    suspend fun countForAccount(accountId: Long): Int

    @Query("SELECT * FROM labyrinth_routes WHERE account_id = :accountId ORDER BY created_at DESC, id DESC LIMIT 1")
    suspend fun getLatestByAccount(accountId: Long): LabyrinthRouteEntity?

    @Query("UPDATE labyrinth_routes SET route_json = :routeJson WHERE id = :id")
    suspend fun updateRouteJson(id: Long, routeJson: String): Int
}

@Dao
interface LabyrinthRerollCheckpointDao {
    @Upsert
    suspend fun upsert(checkpoint: LabyrinthRerollCheckpointEntity)

    @Query("SELECT * FROM labyrinth_reroll_checkpoints WHERE account_id = :accountId")
    suspend fun get(accountId: Long): LabyrinthRerollCheckpointEntity?

    @Query("DELETE FROM labyrinth_reroll_checkpoints WHERE account_id = :accountId")
    suspend fun delete(accountId: Long)
}

@Dao
interface LabyrinthRunStateDao {
    @Query("SELECT * FROM labyrinth_run_states WHERE run_key = :runKey")
    suspend fun getState(runKey: String): LabyrinthRunStateEntity?

    // REPLACE deletes and reinserts the parent row. With the child roster's
    // ON DELETE CASCADE foreign key that would silently erase saved roles.
    @Upsert
    suspend fun upsertState(state: LabyrinthRunStateEntity)

    @Query("DELETE FROM labyrinth_run_states WHERE run_key = :runKey")
    suspend fun deleteState(runKey: String)

    @Query("SELECT * FROM labyrinth_run_characters WHERE run_key = :runKey ORDER BY order_index ASC")
    suspend fun getCharacters(runKey: String): List<LabyrinthRunCharacterEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCharacters(characters: List<LabyrinthRunCharacterEntity>)

    @Query("DELETE FROM labyrinth_run_characters WHERE run_key = :runKey")
    suspend fun deleteCharacters(runKey: String)

    @Query("SELECT * FROM labyrinth_run_relics WHERE run_key = :runKey ORDER BY first_seen_at ASC, relic_id ASC")
    suspend fun getRelics(runKey: String): List<LabyrinthRunRelicEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRelics(relics: List<LabyrinthRunRelicEntity>)

    @Query("DELETE FROM labyrinth_run_relics WHERE run_key = :runKey")
    suspend fun deleteRelics(runKey: String)

    @Query("SELECT * FROM labyrinth_run_relic_stacks WHERE run_key = :runKey")
    suspend fun getRelicStacks(runKey: String): List<LabyrinthRunRelicStackEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRelicStacks(stacks: List<LabyrinthRunRelicStackEntity>)

    @Query("DELETE FROM labyrinth_run_relic_stacks WHERE run_key = :runKey")
    suspend fun deleteRelicStacks(runKey: String)
}

@Dao
interface MigrationCheckpointDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(checkpoint: MigrationCheckpointEntity)

    @Query("SELECT * FROM migration_checkpoints WHERE checkpoint_key = :key")
    suspend fun get(key: String): MigrationCheckpointEntity?
}
