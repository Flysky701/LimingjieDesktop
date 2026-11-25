package com.landosol.toolbox.labyrinth.batch

import android.content.Context
import kotlinx.serialization.json.Json

/** Persisted batch checkpoint, one per account. */
interface LabyrinthBatchCheckpointStore {
    suspend fun save(checkpoint: LabyrinthBatchCheckpoint)
    suspend fun load(accountId: Long): LabyrinthBatchCheckpoint?
    suspend fun clear(accountId: Long)
}

/**
 * JSON-in-preferences store. The checkpoint is small, written a handful of times per run, and
 * read once at startup; a Room table would add a migration for no query benefit. The same
 * shape as [com.landosol.toolbox.labyrinth.JsonLabyrinthRerollSettingsStore].
 */
internal class JsonLabyrinthBatchCheckpointStore(
    private val read: (String) -> String?,
    private val write: (String, String?) -> Unit,
) : LabyrinthBatchCheckpointStore {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    override suspend fun save(checkpoint: LabyrinthBatchCheckpoint) {
        write(key(checkpoint.accountId), json.encodeToString(LabyrinthBatchCheckpoint.serializer(), checkpoint))
    }

    override suspend fun load(accountId: Long): LabyrinthBatchCheckpoint? = runCatching {
        read(key(accountId))?.let { json.decodeFromString(LabyrinthBatchCheckpoint.serializer(), it) }
    }.getOrNull()

    override suspend fun clear(accountId: Long) = write(key(accountId), null)

    private fun key(accountId: Long) = "account.$accountId"
}

class AndroidLabyrinthBatchCheckpointStore(context: Context) : LabyrinthBatchCheckpointStore {
    private val preferences =
        context.applicationContext.getSharedPreferences("labyrinth-batch-checkpoints", Context.MODE_PRIVATE)
    private val storage = JsonLabyrinthBatchCheckpointStore(
        read = { key -> preferences.getString(key, null) },
        write = { key, value ->
            preferences.edit().apply { if (value == null) remove(key) else putString(key, value) }.apply()
        },
    )

    override suspend fun save(checkpoint: LabyrinthBatchCheckpoint) = storage.save(checkpoint)
    override suspend fun load(accountId: Long) = storage.load(accountId)
    override suspend fun clear(accountId: Long) = storage.clear(accountId)
}
