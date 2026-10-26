package com.landosol.toolbox.labyrinth.execution.runtime

import com.landosol.toolbox.labyrinth.*
import com.landosol.toolbox.automation.AutomationSessionId
import com.landosol.toolbox.labyrinth.vision.EntryPixelRect
import com.landosol.toolbox.labyrinth.vision.LabyrinthEntryFrameResult
import com.landosol.toolbox.labyrinth.vision.LabyrinthCharacterMatch
import com.landosol.toolbox.labyrinth.vision.LabyrinthEntryPageState
import com.landosol.toolbox.labyrinth.vision.LabyrinthBattleTeamObservation
import com.landosol.toolbox.labyrinth.vision.LabyrinthRelicMatch
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal interface LabyrinthPersistencePort {
    val _state: MutableStateFlow<LabyrinthEntryRecognitionSessionState>
    val actionScope: CoroutineScope
    val activeRunAccountId: Long?
    val activeSessionId: AutomationSessionId?
    var lastPersistedObservationSignature: String?
    val openingStateReset: AtomicBoolean
    var pendingAcquiredCharacterId: String?
    val persistenceMutex: Mutex
    val runStateStore: LabyrinthRunStateStore?
    val validatedRoute: LabyrinthRouteJson?
    fun acknowledgeAcquiredCharacters(characters: List<LabyrinthCharacterMatch>): Unit
    fun advanceRelicStackCalibrationAfterAcquisition(relic: LabyrinthRelicMatch): Unit
    fun observeRelicAcquisition(result: LabyrinthEntryFrameResult): LabyrinthRelicMatch?
    fun observeShopPurchase(result: LabyrinthEntryFrameResult): LabyrinthRelicMatch?
    fun publishOverlay(sessionId: AutomationSessionId): Unit
    fun refreshBattleTeamRecommendation(
            sessionId: AutomationSessionId,
            pageState: LabyrinthEntryPageState,
        ): Unit
    fun refreshBattleTeamSelectionPlan(
            sessionId: AutomationSessionId,
            pageState: LabyrinthEntryPageState,
            observation: com.landosol.toolbox.labyrinth.vision.LabyrinthBattleTeamObservation?,
        ): Unit
    fun resetObservedOpeningRewards(): Unit
}

/** 保存已核对的阵容与遗物观察，并发布持久化后的运行快照。 */
internal class LabyrinthPersistenceWorkflow(private val port: LabyrinthPersistencePort) : LabyrinthPersistencePort by port {
    fun persistObservation(
        sessionId: AutomationSessionId,
        result: LabyrinthEntryFrameResult,
        timestampMillis: Long,
    ) {
        val store = runStateStore ?: return
        val accountId = activeRunAccountId
        if (
            result.observation.state == LabyrinthEntryPageState.INITIAL_CHARACTER_SELECTION &&
                !labyrinthIsRoleRewardPage(result) &&
                openingStateReset.compareAndSet(false, true)
        ) {
            lastPersistedObservationSignature = null
            resetObservedOpeningRewards()
            actionScope.launch {
                runCatching {
                    persistenceMutex.withLock { store.startNew(accountId, timestampMillis) }
                }
                    .onSuccess { snapshot ->
                        if (activeSessionId == sessionId) {
                            _state.value = _state.value.copy(
                                joinedCharacters = snapshot.joinedCharacters,
                                observedRelics = snapshot.observedRelics,
                            )
                            publishOverlay(sessionId)
                        }
                    }
                    .onFailure { failure ->
                        if (activeSessionId == sessionId) {
                            _state.value = _state.value.copy(message = "清理旧队伍状态失败：${failure.message ?: "未知错误"}")
                        }
                    }
            }
            return
        }
        val purchasedRelic = observeShopPurchase(result)
        val reconciled = labyrinthRosterReconciliationMatches(result)
        val characters = if (labyrinthRosterReplacesExisting(result, reconciled)) {
            // The three confirmed picks replace the roster; add the guild's automatic grant
            // (2026-09-17: 拉比林斯 → 菈比莉斯塔) so it is never lost to an unrecognised popup.
            reconciled + labyrinthGuildGrantedRosterMatches(
                guildId = validatedRoute?.guildId,
                frameRect = EntryPixelRect(0, 0, maxOf(1, result.frameWidth), maxOf(1, result.frameHeight)),
            )
        } else {
            reconciled
        }
        val confirmedRelic = observeRelicAcquisition(result)
        val relics = when {
            confirmedRelic != null -> listOf(confirmedRelic)
            purchasedRelic != null -> listOf(purchasedRelic)
            else -> emptyList()
        }
        relics.forEach(::advanceRelicStackCalibrationAfterAcquisition)
        val signature = buildString {
            append(result.observation.state.name)
            characters.forEach { character ->
                append('|')
                append(character.slotId)
                append(':')
                append(character.characterId)
                append(':')
                append(character.displayName)
            }
            relics.forEach { relic ->
                append('|')
                append(relic.slotId)
                append(':')
                append(relic.relicId)
                append(':')
                append(relic.attribute)
            }
        }
        if (signature == lastPersistedObservationSignature) return
        lastPersistedObservationSignature = signature
        actionScope.launch {
            runCatching {
                persistenceMutex.withLock {
                    store.recordPage(
                        accountId = accountId,
                        page = result.observation.state.name,
                        characters = characters,
                        nowMillis = timestampMillis,
                        relics = relics,
                        replaceCharacters = labyrinthRosterReplacesExisting(result, reconciled),
                    )
                }
            }.onSuccess { snapshot ->
                if (activeSessionId == sessionId) {
                    acknowledgeAcquiredCharacters(characters)
                    _state.value = _state.value.copy(
                        joinedCharacters = snapshot.joinedCharacters,
                        observedRelics = snapshot.observedRelics,
                        pendingAcquiredCharacterId = pendingAcquiredCharacterId,
                        message = confirmedRelic?.displayName?.let { "选择遗物已入库：$it" }
                            ?: purchasedRelic?.displayName?.let { "购买遗物已入库：$it" }
                            ?: "页面识别已更新",
                    )
                    pendingAcquiredCharacterId = _state.value.pendingAcquiredCharacterId
                    refreshBattleTeamRecommendation(
                        sessionId,
                        _state.value.lastResult?.observation?.state ?: LabyrinthEntryPageState.UNKNOWN,
                    )
                    refreshBattleTeamSelectionPlan(
                        sessionId = sessionId,
                        pageState = _state.value.lastResult?.observation?.state ?: LabyrinthEntryPageState.UNKNOWN,
                        observation = _state.value.lastResult?.battleTeamSelection,
                    )
                    publishOverlay(sessionId)
                }
            }.onFailure { failure ->
                lastPersistedObservationSignature = null
                if (activeSessionId == sessionId) {
                    _state.value = _state.value.copy(message = "保存本局队伍/遗物失败：${failure.message ?: "未知错误"}")
                }
            }
        }
    }
}
