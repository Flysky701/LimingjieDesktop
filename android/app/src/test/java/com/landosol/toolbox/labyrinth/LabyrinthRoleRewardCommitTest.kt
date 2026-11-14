package com.landosol.toolbox.labyrinth

import com.landosol.toolbox.automation.*
import com.landosol.toolbox.labyrinth.vision.*
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test

class LabyrinthRoleRewardCommitTest {
    @Test fun `successful choice persists before any joined page and stop clears the bypass`() =
        exercise(AutomationBackendResult.Completed, failStore = false, expectedWrites = 1, expectedBypass = true,
            rewardFrame = Batch2SessionFixture().roleFrame(listOf("1002", "1003", "1004")))

    @Test fun `rejected choice never records ownership or bypasses joined recognition`() =
        exercise(AutomationBackendResult.Rejected("test rejection"), failStore = false, expectedWrites = 0, expectedBypass = false)

    @Test fun `failed persistence keeps joined recognition available for recovery`() =
        exercise(AutomationBackendResult.Completed, failStore = true, expectedWrites = 1, expectedBypass = false,
            rewardFrame = Batch2SessionFixture().roleFrame(listOf("1002", "1003", "1004")))

    private fun exercise(backend: AutomationBackendResult, failStore: Boolean, expectedWrites: Int, expectedBypass: Boolean,
        rewardFrame: LabyrinthEntryFrameResult? = null) = runBlocking {
        val manager = AutomationSessionManager()
        val store = MemoryStore(failStore)
        val session = LabyrinthEntryRecognitionSession(
            sessionManager = manager, captureActive = { true },
            processorFactory = { { error("no captured frames in this action test") } },
            actionExecutor = SessionBoundActionExecutor(manager) { backend },
            actionsAvailable = { true }, gameLauncher = { true }, runStateStore = store,
        )
        try {
            assertTrue(session.startAutomation() is LabyrinthEntryRecognitionStartResult.Started)
            @Suppress("UNCHECKED_CAST")
            val state = field(session, "_state").get(session) as MutableStateFlow<LabyrinthEntryRecognitionSessionState>
            val rect = EntryPixelRect(100, 100, 80, 40)
            // A safe visible selection lets dispatch mint its current-round token before input.
            state.value = state.value.copy(lastResult = rewardFrame,
                roleRewardChoiceDecision = LabyrinthRoleRewardChoiceDecision.Select(
                    "1002", "千歌（礼服）", rect, emptyList(),
                ))
            val dispatch = session.javaClass.declaredMethods.single {
                it.name.startsWith("dispatchPostEntryTap-") && !it.name.endsWith("\$default")
            }.apply { isAccessible = true }
            // The label is display text only; the commit path keys on the action kind. A label
            // that looks nothing like the UI copy must still persist the selection.
            dispatch.invoke(session, manager.current()!!.id.value,
                LabyrinthPostEntryActionKind.SELECT_ROLE_REWARD, "random-label-9f3a", rect,
                System.currentTimeMillis(), rewardFrame?.let(::labyrinthRoleRewardSelectionSignature) ?: "left:1002",
                "1002", null, null, null, false, emptyList<String>())
            val inFlight = field(session, "actionInFlight").get(session) as AtomicBoolean
            withTimeout(5_000) { while (inFlight.get()) delay(10) }
            assertEquals(expectedWrites, store.writes)
            assertEquals(expectedBypass, session.skipRoleRewardJoinedRecognition)
            if (expectedBypass) {
                assertEquals(listOf("1002"), store.snapshot.joinedCharacters.map { it.characterId })
                assertEquals(listOf("1002"), session.state.value.joinedCharacters.map { it.characterId })
            } else if (expectedWrites == 0) {
                assertTrue(store.snapshot.joinedCharacters.isEmpty())
            }
        } finally {
            session.stop()
        }
        assertFalse(session.skipRoleRewardJoinedRecognition)
    }

    private fun field(session: LabyrinthEntryRecognitionSession, name: String) =
        session.javaClass.getDeclaredField(name).apply { isAccessible = true }

    private class MemoryStore(private val fail: Boolean) : LabyrinthRunStateStore {
        var writes = 0
        var snapshot = LabyrinthRunSnapshot("test", null, "ACTIVE", null, 0, emptyList(), 0)
        override suspend fun begin(accountId: Long?, nowMillis: Long) = snapshot
        override suspend fun startNew(accountId: Long?, nowMillis: Long) = snapshot
        override suspend fun load(accountId: Long?) = snapshot
        override suspend fun recordPage(accountId: Long?, page: String, characters: List<LabyrinthCharacterMatch>,
            nowMillis: Long, relics: List<LabyrinthRelicMatch>, replaceCharacters: Boolean,
            expectedStartedAt: Long?, relicStacks: Map<LabyrinthRelicMark, LabyrinthRelicStackRecord>?,
            openingRelicReceipt: LabyrinthOpeningRelicReceipt?): LabyrinthRunSnapshot {
            writes++
            if (fail) error("test disk failure")
            snapshot = snapshot.copy(joinedCharacters = LabyrinthRosterMerger.merge(
                snapshot.joinedCharacters, characters, nowMillis,
            ))
            return snapshot
        }
    }
}
