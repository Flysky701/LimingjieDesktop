package com.landosol.toolbox.labyrinth

import com.landosol.toolbox.automation.AutomationBackendResult
import com.landosol.toolbox.automation.AutomationSessionManager
import com.landosol.toolbox.automation.SessionBoundActionExecutor
import com.landosol.toolbox.labyrinth.vision.*
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.InvocationTargetException
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

class LabyrinthShopPurchaseTransitionTest {
    @Test fun `brief shop frame preserves the planned purchase until its completion receipt`() = replay(true)

    @Test fun `sustained shop frames cancel a purchase without recording an acquisition`() = replay(false)

    private fun replay(completed: Boolean) = runTest {
        val manager = AutomationSessionManager()
        var now = 1_000L
        val recorded = mutableListOf<String?>()
        val snapshot = LabyrinthRunSnapshot("shop", 1L, "ACTIVE", null, 0, emptyList(), now)
        val store = object : LabyrinthRunStateStore {
            override suspend fun begin(accountId: Long?, nowMillis: Long) = snapshot
            override suspend fun startNew(accountId: Long?, nowMillis: Long) = snapshot
            override suspend fun load(accountId: Long?) = snapshot
            override suspend fun recordPage(accountId: Long?, page: String,
                characters: List<LabyrinthCharacterMatch>, nowMillis: Long,
                relics: List<LabyrinthRelicMatch>, replaceCharacters: Boolean,
                expectedStartedAt: Long?, relicStacks: Map<LabyrinthRelicMark, LabyrinthRelicStackRecord>?,
                openingRelicReceipt: LabyrinthOpeningRelicReceipt?): LabyrinthRunSnapshot {
                recorded += relics.map { it.relicId }
                return snapshot
            }
        }
        val session = LabyrinthEntryRecognitionSession(
            sessionManager = manager, captureActive = { true }, clock = { now },
            processorFactory = { { error("no captured frames needed") } },
            actionExecutor = SessionBoundActionExecutor(manager) { AutomationBackendResult.Completed },
            actionsAvailable = { true }, runStateStore = store, actionScope = this,
        )
        assertTrue(session.startAutomation(accountId = 1L) is LabyrinthEntryRecognitionStartResult.Started)
        try {
            runCurrent()
            val sessionId = session.state.value.sessionId!!
            fun field(name: String) = session.javaClass.getDeclaredField(name).apply { isAccessible = true }
            field("plannedShopPurchaseRelicId").set(session, "relic-1")
            field("plannedShopPurchaseStartedAt").setLong(session, now)
            val rect = EntryPixelRect(100, 100, 100, 100)
            val relic = LabyrinthRelicMatch("confirmation", "relic-1", "遗物", "会心", "1", null,
                0.99, rect, iconRect = rect)
            val persist = session.javaClass.declaredMethods.single {
                it.name.startsWith("persistObservation-")
            }.apply { isAccessible = true }
            suspend fun feed(dialog: LabyrinthShopDialogState, time: Long) {
                now = time
                val page = when (dialog) {
                    LabyrinthShopDialogState.PURCHASE_CONFIRMATION -> LabyrinthEntryPageState.SHOP_PURCHASE_CONFIRMATION
                    LabyrinthShopDialogState.PURCHASE_COMPLETE -> LabyrinthEntryPageState.SHOP_PURCHASE_COMPLETE
                    else -> LabyrinthEntryPageState.SHOP
                }
                val result = LabyrinthEntryFrameResult(
                    observation = LabyrinthEntryPageObservation(page, 1.0, mapOf(page to 1.0),
                        LabyrinthAnchorScores(emptyMap())),
                    matchedFeatures = emptyList(), elapsedMillis = 0, frameWidth = 1920, frameHeight = 1080,
                    shopObservation = LabyrinthShopObservation(emptyList(), dialog, purchaseCandidate = relic),
                )
                session.javaClass.declaredMethods.single { it.name == "observeRelicIdentities" }
                    .apply { isAccessible = true }.invoke(session, result, now)
                val ledger = field("relicStackLedger").get(session) as LabyrinthRelicStackLedger
                val committed = suspendCoroutine { continuation: Continuation<Boolean> ->
                    try {
                        val value = persist.invoke(session, sessionId.value, result, now, ledger.fork(), continuation)
                        if (value !== COROUTINE_SUSPENDED) continuation.resume(value as Boolean)
                    } catch (failure: InvocationTargetException) {
                        continuation.resumeWithException(failure.targetException)
                    }
                }
                assertTrue(committed)
            }
            feed(LabyrinthShopDialogState.PURCHASE_CONFIRMATION, 1_000)
            feed(LabyrinthShopDialogState.PURCHASE_CONFIRMATION, 1_300)
            feed(LabyrinthShopDialogState.NONE, 1_400)
            assertEquals("relic-1", field("plannedShopPurchaseRelicId").get(session))
            assertTrue(recorded.isEmpty())
            if (completed) {
                feed(LabyrinthShopDialogState.PURCHASE_COMPLETE, 1_800)
                runCurrent()
                assertEquals("relic-1", field("plannedShopPurchaseRelicId").get(session))
                assertEquals(listOf("relic-1"), recorded)
                feed(LabyrinthShopDialogState.PURCHASE_COMPLETE, 2_100)
                runCurrent()
                assertEquals(listOf("relic-1"), recorded)
            } else {
                feed(LabyrinthShopDialogState.NONE, 3_500)
                runCurrent()
                assertNull(field("plannedShopPurchaseRelicId").get(session))
                assertTrue(recorded.isEmpty())
            }
        } finally {
            session.stop()
        }
    }
}
