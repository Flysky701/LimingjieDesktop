package com.landosol.toolbox.labyrinth

import androidx.room.withTransaction
import com.landosol.toolbox.data.local.AppDatabase
import com.landosol.toolbox.data.local.LabyrinthRunCharacterEntity
import com.landosol.toolbox.data.local.LabyrinthRunRelicEntity
import com.landosol.toolbox.data.local.LabyrinthRunRelicStackEntity
import com.landosol.toolbox.data.local.LabyrinthRunStateEntity
import com.landosol.toolbox.labyrinth.vision.LabyrinthCharacterMatch
import com.landosol.toolbox.labyrinth.vision.LabyrinthRelicMatch

data class LabyrinthJoinedCharacter(
    val characterId: String,
    val displayName: String,
    val orderIndex: Int,
    val confidence: Double,
    val firstSeenAt: Long,
    val lastSeenAt: Long,
)

data class LabyrinthObservedRelic(
    val relicId: String,
    val displayName: String,
    val attribute: String,
    val attributeBonus: String,
    val effect: String,
    val confidence: Double,
    val firstSeenAt: Long,
    val lastSeenAt: Long,
)

data class LabyrinthOpeningRelicReceipt(val enterId: Long, val complete: Boolean)

data class LabyrinthRunSnapshot(
    val runKey: String,
    val accountId: Long?,
    val status: String,
    val currentPage: String?,
    val unrecognizedCharacterCount: Int,
    val joinedCharacters: List<LabyrinthJoinedCharacter>,
    val updatedAt: Long,
    val observedRelics: List<LabyrinthObservedRelic> = emptyList(),
    val startedAt: Long = 0,
    val relicStacks: Map<LabyrinthRelicMark, LabyrinthRelicStackRecord> = emptyMap(),
    val openingRelicReceipt: LabyrinthOpeningRelicReceipt? = null,
)

interface LabyrinthRunStateStore {
    suspend fun begin(accountId: Long?, nowMillis: Long): LabyrinthRunSnapshot

    suspend fun startNew(accountId: Long?, nowMillis: Long): LabyrinthRunSnapshot

    suspend fun recordPage(
        accountId: Long?,
        page: String,
        characters: List<LabyrinthCharacterMatch>,
        nowMillis: Long,
        relics: List<LabyrinthRelicMatch> = emptyList(),
        replaceCharacters: Boolean = false,
        expectedStartedAt: Long? = null,
        relicStacks: Map<LabyrinthRelicMark, LabyrinthRelicStackRecord>? = null,
        openingRelicReceipt: LabyrinthOpeningRelicReceipt? = null,
    ): LabyrinthRunSnapshot

    suspend fun load(accountId: Long?): LabyrinthRunSnapshot?
}

object LabyrinthRosterMerger {
    fun merge(
        existing: List<LabyrinthJoinedCharacter>,
        matches: List<LabyrinthCharacterMatch>,
        nowMillis: Long,
        replaceExisting: Boolean = false,
    ): List<LabyrinthJoinedCharacter> {
        val merged = existing
            .takeUnless { replaceExisting }
            .orEmpty()
            .sortedBy(LabyrinthJoinedCharacter::orderIndex)
            .map { character ->
                character.copy(characterId = canonicalLabyrinthRoleId(character.characterId))
            }
            .distinctBy(LabyrinthJoinedCharacter::characterId)
            .toMutableList()
        var nextOrder = (merged.maxOfOrNull(LabyrinthJoinedCharacter::orderIndex) ?: -1) + 1
        matches
            .filter {
                it.trusted && !it.characterId.isNullOrBlank() && !it.displayName.isNullOrBlank()
            }
            .distinctBy { canonicalLabyrinthRoleId(requireNotNull(it.characterId)) }
            .forEach { match ->
                val characterId = canonicalLabyrinthRoleId(requireNotNull(match.characterId))
                val displayName = requireNotNull(match.displayName)
                val index = merged.indexOfFirst { it.characterId == characterId }
                if (index >= 0) {
                    merged[index] = merged[index].copy(
                        displayName = displayName,
                        confidence = maxOf(merged[index].confidence, match.confidence),
                        lastSeenAt = nowMillis,
                    )
                } else {
                    merged += LabyrinthJoinedCharacter(
                        characterId = characterId,
                        displayName = displayName,
                        orderIndex = nextOrder++,
                        confidence = match.confidence,
                        firstSeenAt = nowMillis,
                        lastSeenAt = nowMillis,
                    )
                }
            }
        return merged
    }
}

object LabyrinthRelicMerger {
    fun merge(
        existing: List<LabyrinthObservedRelic>,
        matches: List<LabyrinthRelicMatch>,
        nowMillis: Long,
    ): List<LabyrinthObservedRelic> {
        val merged = existing.distinctBy(LabyrinthObservedRelic::relicId).toMutableList()
        matches
            .filter {
                !it.relicId.isNullOrBlank() &&
                    !it.displayName.isNullOrBlank() &&
                    !it.attribute.isNullOrBlank()
            }
            .distinctBy(LabyrinthRelicMatch::relicId)
            .forEach { match ->
                val relicId = requireNotNull(match.relicId)
                val displayName = requireNotNull(match.displayName)
                val attribute = requireNotNull(match.attribute)
                if (merged.none { it.relicId == relicId }) {
                    merged += LabyrinthObservedRelic(
                        relicId = relicId,
                        displayName = displayName,
                        attribute = attribute,
                        attributeBonus = match.attributeBonus.orEmpty(),
                        effect = match.effect.orEmpty(),
                        confidence = match.confidence,
                        firstSeenAt = nowMillis,
                        lastSeenAt = nowMillis,
                    )
                }
            }
        return merged.sortedWith(compareBy(LabyrinthObservedRelic::firstSeenAt, LabyrinthObservedRelic::relicId))
    }
}

class RoomLabyrinthRunStateStore(
    private val database: AppDatabase,
) : LabyrinthRunStateStore {
    override suspend fun begin(accountId: Long?, nowMillis: Long): LabyrinthRunSnapshot = database.withTransaction {
        val key = runKey(accountId)
        val dao = database.labyrinthRunStateDao()
        val current = dao.getState(key)
        dao.upsertState(
            current?.copy(status = ACTIVE, updatedAt = nowMillis) ?: LabyrinthRunStateEntity(
                runKey = key,
                accountId = accountId,
                status = ACTIVE,
                currentPage = null,
                unrecognizedCharacterCount = 0,
                startedAt = nowMillis,
                updatedAt = nowMillis,
            ),
        )
        snapshot(key)
    }

    override suspend fun startNew(accountId: Long?, nowMillis: Long): LabyrinthRunSnapshot = database.withTransaction {
        val key = runKey(accountId)
        val dao = database.labyrinthRunStateDao()
        val previous = dao.getState(key)
        check(previous?.startedAt != Long.MAX_VALUE) { "本局代际已耗尽" }
        val startedAt = maxOf(nowMillis, previous?.startedAt?.plus(1) ?: nowMillis)
        dao.deleteCharacters(key)
        dao.deleteRelics(key)
        dao.deleteRelicStacks(key)
        dao.upsertState(
            LabyrinthRunStateEntity(
                runKey = key,
                accountId = accountId,
                status = ACTIVE,
                currentPage = null,
                unrecognizedCharacterCount = 0,
                startedAt = startedAt,
                updatedAt = nowMillis,
            ),
        )
        snapshot(key)
    }

    override suspend fun recordPage(
        accountId: Long?,
        page: String,
        characters: List<LabyrinthCharacterMatch>,
        nowMillis: Long,
        relics: List<LabyrinthRelicMatch>,
        replaceCharacters: Boolean,
        expectedStartedAt: Long?,
        relicStacks: Map<LabyrinthRelicMark, LabyrinthRelicStackRecord>?,
        openingRelicReceipt: LabyrinthOpeningRelicReceipt?,
    ): LabyrinthRunSnapshot = database.withTransaction {
        val key = runKey(accountId)
        val dao = database.labyrinthRunStateDao()
        val savedState = dao.getState(key)
        check(expectedStartedAt == null || savedState?.startedAt == expectedStartedAt) { "本局已切换，拒绝旧页面记录" }
        val current = savedState ?: LabyrinthRunStateEntity(
            runKey = key,
            accountId = accountId,
            status = ACTIVE,
            currentPage = null,
            unrecognizedCharacterCount = 0,
            startedAt = nowMillis,
            updatedAt = nowMillis,
        )
        openingRelicReceipt?.let { receipt ->
            require(receipt.enterId > 0)
            check(current.openingEnterId == null || current.openingEnterId == receipt.enterId) { "本局开局身份不一致" }
        }
        dao.upsertState(current)
        val existing = dao.getCharacters(key).map(LabyrinthRunCharacterEntity::toDomain)
        val merged = LabyrinthRosterMerger.merge(
            existing = existing,
            matches = characters,
            nowMillis = nowMillis,
            replaceExisting = replaceCharacters,
        )
        dao.deleteCharacters(key)
        dao.insertCharacters(merged.map { it.toEntity(key) })
        val existingRelics = dao.getRelics(key).map(LabyrinthRunRelicEntity::toDomain)
        val mergedRelics = LabyrinthRelicMerger.merge(existingRelics, relics, nowMillis)
        dao.deleteRelics(key)
        dao.insertRelics(mergedRelics.map { it.toEntity(key) })
        if (relicStacks != null) {
            val newIds = mergedRelics.map { it.relicId }.toSet() - existingRelics.map { it.relicId }.toSet()
            val replayedMarks = relics.filter { it.relicId !in newIds }
                .mapNotNull { LabyrinthRelicMark.fromLabel(it.attribute) }.toSet()
            val acquiredMarks = relics.filter { it.relicId in newIds }
                .mapNotNull { LabyrinthRelicMark.fromLabel(it.attribute) }.toSet()
            val updates = relicStacks.filter { (mark, record) ->
                require(record.stacks in 0..30)
                record.source != LabyrinthRelicStackSource.ACQUISITION || mark !in replayedMarks || mark in acquiredMarks
            }
            dao.insertRelicStacks(updates.map { (mark, record) ->
                LabyrinthRunRelicStackEntity(key, mark.name, record.stacks, record.source.name)
            })
        }
        dao.upsertState(
            current.copy(
                status = ACTIVE,
                currentPage = page,
                openingEnterId = openingRelicReceipt?.enterId ?: current.openingEnterId,
                openingRelicsComplete = current.openingRelicsComplete || openingRelicReceipt?.complete == true,
                unrecognizedCharacterCount = if (characters.isNotEmpty()) {
                    characters.count { it.characterId == null || it.displayName == null }
                } else {
                    current.unrecognizedCharacterCount
                },
                updatedAt = nowMillis,
            ),
        )
        snapshot(key)
    }

    override suspend fun load(accountId: Long?): LabyrinthRunSnapshot? = database.withTransaction {
        val key = runKey(accountId)
        if (database.labyrinthRunStateDao().getState(key) == null) null else snapshot(key)
    }

    private suspend fun snapshot(key: String): LabyrinthRunSnapshot {
        val dao = database.labyrinthRunStateDao()
        val state = requireNotNull(dao.getState(key))
        return LabyrinthRunSnapshot(
            runKey = state.runKey,
            accountId = state.accountId,
            status = state.status,
            currentPage = state.currentPage,
            unrecognizedCharacterCount = state.unrecognizedCharacterCount,
            joinedCharacters = dao.getCharacters(key).map(LabyrinthRunCharacterEntity::toDomain),
            updatedAt = state.updatedAt,
            observedRelics = dao.getRelics(key).map(LabyrinthRunRelicEntity::toDomain),
            startedAt = state.startedAt,
            openingRelicReceipt = state.openingEnterId?.takeIf { it > 0 }?.let {
                LabyrinthOpeningRelicReceipt(it, state.openingRelicsComplete)
            },
            relicStacks = dao.getRelicStacks(key).mapNotNull { entry ->
                val mark = LabyrinthRelicMark.entries.firstOrNull { it.name == entry.mark } ?: return@mapNotNull null
                val source = LabyrinthRelicStackSource.entries.firstOrNull { it.name == entry.source } ?: return@mapNotNull null
                if (entry.stacks !in 0..30) return@mapNotNull null
                mark to LabyrinthRelicStackRecord(entry.stacks, source)
            }.toMap(),
        )
    }

    private fun runKey(accountId: Long?): String = accountId?.let { "account:$it" } ?: UNBOUND_RUN_KEY

    private companion object {
        const val ACTIVE = "ACTIVE"
        const val UNBOUND_RUN_KEY = "account:unbound"
    }
}

private fun LabyrinthRunCharacterEntity.toDomain() = LabyrinthJoinedCharacter(
    characterId = characterId,
    displayName = displayName,
    orderIndex = orderIndex,
    confidence = confidence,
    firstSeenAt = firstSeenAt,
    lastSeenAt = lastSeenAt,
)

private fun LabyrinthJoinedCharacter.toEntity(runKey: String) = LabyrinthRunCharacterEntity(
    runKey = runKey,
    characterId = characterId,
    displayName = displayName,
    orderIndex = orderIndex,
    confidence = confidence,
    firstSeenAt = firstSeenAt,
    lastSeenAt = lastSeenAt,
)

private fun LabyrinthRunRelicEntity.toDomain() = LabyrinthObservedRelic(
    relicId = relicId,
    displayName = displayName,
    attribute = attribute,
    attributeBonus = attributeBonus,
    effect = effect,
    confidence = confidence,
    firstSeenAt = firstSeenAt,
    lastSeenAt = lastSeenAt,
)

private fun LabyrinthObservedRelic.toEntity(runKey: String) = LabyrinthRunRelicEntity(
    runKey = runKey,
    relicId = relicId,
    displayName = displayName,
    attribute = attribute,
    attributeBonus = attributeBonus,
    effect = effect,
    confidence = confidence,
    firstSeenAt = firstSeenAt,
    lastSeenAt = lastSeenAt,
)
