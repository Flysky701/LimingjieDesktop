package com.landosol.toolbox.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "accounts",
    indices = [
        Index(value = ["credential_key"], unique = true),
        Index(value = ["server_id", "alias"], unique = true),
        Index(value = ["is_selected"]),
    ],
)
data class AccountEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val alias: String,
    @ColumnInfo(name = "server_id") val serverId: String,
    @ColumnInfo(name = "game_uid") val gameUid: String?,
    @ColumnInfo(name = "credential_key") val credentialKey: String,
    @ColumnInfo(name = "is_selected") val isSelected: Boolean,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

@Entity(
    tableName = "feature_profiles",
    foreignKeys = [
        ForeignKey(
            entity = AccountEntity::class,
            parentColumns = ["id"],
            childColumns = ["account_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["account_id"]),
        Index(value = ["account_id", "feature_type", "name"], unique = true),
    ],
)
data class FeatureProfileEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "account_id") val accountId: Long,
    val name: String,
    @ColumnInfo(name = "feature_type") val featureType: String,
    @ColumnInfo(name = "configuration_json") val configurationJson: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

@Entity(
    tableName = "automation_runs",
    foreignKeys = [
        ForeignKey(
            entity = AccountEntity::class,
            parentColumns = ["id"],
            childColumns = ["account_id"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [Index(value = ["account_id"]), Index(value = ["started_at"])],
)
data class AutomationRunEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "account_id") val accountId: Long?,
    @ColumnInfo(name = "feature_type") val featureType: String,
    val status: String,
    @ColumnInfo(name = "started_at") val startedAt: Long,
    @ColumnInfo(name = "ended_at") val endedAt: Long?,
    @ColumnInfo(name = "failure_stage") val failureStage: String?,
    val summary: String?,
)

@Entity(
    tableName = "labyrinth_routes",
    foreignKeys = [
        ForeignKey(
            entity = AccountEntity::class,
            parentColumns = ["id"],
            childColumns = ["account_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = AutomationRunEntity::class,
            parentColumns = ["id"],
            childColumns = ["run_id"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [Index(value = ["account_id"]), Index(value = ["run_id"]), Index(value = ["created_at"])],
)
data class LabyrinthRouteEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "account_id") val accountId: Long,
    @ColumnInfo(name = "run_id") val runId: Long?,
    @ColumnInfo(name = "enter_id") val enterId: Long,
    val difficulty: Int,
    @ColumnInfo(name = "attempt_count") val attemptCount: Int,
    @ColumnInfo(name = "route_json") val routeJson: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

@Entity(
    tableName = "labyrinth_reroll_checkpoints",
    foreignKeys = [
        ForeignKey(
            entity = AccountEntity::class,
            parentColumns = ["id"],
            childColumns = ["account_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["status"]), Index(value = ["updated_at"])],
)
data class LabyrinthRerollCheckpointEntity(
    @PrimaryKey @ColumnInfo(name = "account_id") val accountId: Long,
    @ColumnInfo(name = "attempt_count") val attemptCount: Int,
    @ColumnInfo(name = "enter_id") val enterId: Long?,
    @ColumnInfo(name = "guild_id") val guildId: Int,
    val difficulty: Int,
    @ColumnInfo(name = "policy_json") val policyJson: String,
    val status: String,
    val message: String?,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

@Entity(
    tableName = "labyrinth_run_states",
    foreignKeys = [
        ForeignKey(
            entity = AccountEntity::class,
            parentColumns = ["id"],
            childColumns = ["account_id"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [
        Index(value = ["account_id", "status"]),
        Index(value = ["updated_at"]),
    ],
)
data class LabyrinthRunStateEntity(
    @PrimaryKey @ColumnInfo(name = "run_key") val runKey: String,
    @ColumnInfo(name = "account_id") val accountId: Long?,
    val status: String,
    @ColumnInfo(name = "current_page") val currentPage: String?,
    @ColumnInfo(name = "opening_enter_id") val openingEnterId: Long? = null,
    @ColumnInfo(name = "opening_relics_complete", defaultValue = "0") val openingRelicsComplete: Boolean = false,
    @ColumnInfo(name = "unrecognized_character_count") val unrecognizedCharacterCount: Int,
    @ColumnInfo(name = "started_at") val startedAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

@Entity(
    tableName = "labyrinth_run_characters",
    primaryKeys = ["run_key", "character_id"],
    foreignKeys = [
        ForeignKey(
            entity = LabyrinthRunStateEntity::class,
            parentColumns = ["run_key"],
            childColumns = ["run_key"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["run_key", "order_index"], unique = true)],
)
data class LabyrinthRunCharacterEntity(
    @ColumnInfo(name = "run_key") val runKey: String,
    @ColumnInfo(name = "character_id") val characterId: String,
    @ColumnInfo(name = "display_name") val displayName: String,
    @ColumnInfo(name = "order_index") val orderIndex: Int,
    val confidence: Double,
    @ColumnInfo(name = "first_seen_at") val firstSeenAt: Long,
    @ColumnInfo(name = "last_seen_at") val lastSeenAt: Long,
)

@Entity(
    tableName = "labyrinth_run_relics",
    primaryKeys = ["run_key", "relic_id"],
    foreignKeys = [
        ForeignKey(
            entity = LabyrinthRunStateEntity::class,
            parentColumns = ["run_key"],
            childColumns = ["run_key"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["run_key", "last_seen_at"])],
)
data class LabyrinthRunRelicEntity(
    @ColumnInfo(name = "run_key") val runKey: String,
    @ColumnInfo(name = "relic_id") val relicId: String,
    @ColumnInfo(name = "display_name") val displayName: String,
    @ColumnInfo(name = "relic_attribute") val attribute: String,
    @ColumnInfo(name = "attribute_bonus") val attributeBonus: String,
    val effect: String,
    val confidence: Double,
    @ColumnInfo(name = "first_seen_at") val firstSeenAt: Long,
    @ColumnInfo(name = "last_seen_at") val lastSeenAt: Long,
)

@Entity(
    tableName = "labyrinth_run_relic_stacks",
    primaryKeys = ["run_key", "mark"],
    foreignKeys = [ForeignKey(
        entity = LabyrinthRunStateEntity::class,
        parentColumns = ["run_key"],
        childColumns = ["run_key"],
        onDelete = ForeignKey.CASCADE,
    )],
)
data class LabyrinthRunRelicStackEntity(
    @ColumnInfo(name = "run_key") val runKey: String,
    val mark: String,
    val stacks: Int,
    val source: String,
)

@Entity(tableName = "migration_checkpoints")
data class MigrationCheckpointEntity(
    @PrimaryKey @ColumnInfo(name = "checkpoint_key") val checkpointKey: String,
    @ColumnInfo(name = "completed_at") val completedAt: Long,
    @ColumnInfo(name = "payload_hash") val payloadHash: String?,
)
