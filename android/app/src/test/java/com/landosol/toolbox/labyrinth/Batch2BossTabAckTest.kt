package com.landosol.toolbox.labyrinth

import com.landosol.toolbox.automation.AutomationAction
import com.landosol.toolbox.automation.AutomationBackendResult
import com.landosol.toolbox.automation.ScreenPoint
import com.landosol.toolbox.automation.SessionBoundActionExecutor
import com.landosol.toolbox.automation.accessibility.GAME_NOT_FOREGROUND_REASON
import com.landosol.toolbox.labyrinth.vision.LabyrinthBattleElementFilter
import com.landosol.toolbox.labyrinth.vision.LabyrinthEntryFrameResult
import com.landosol.toolbox.labyrinth.vision.LabyrinthEntryPageState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

/**
 * Batch 2 Boss Tab ACK contract. Pure receipt cases pin capture-time evidence rules; integration
 * cases use the real session executor and processFrameResult, not the old signature-change helper.
 * Only an input-completed, fresh, stable pair may commit a NEXT team or finish RESET navigation.
 * Fake time advances explicitly; no capture cadence, scoring, or production thread is changed.
 */
class Batch2BossTabAckTest {
    private val teamPage = LabyrinthEntryPageState.BATTLE_TEAM_SELECTION

    // ---- Pure receipt: input completion, fresh ordered evidence, and the fixed deadline ----

    @Test
    fun `receipt cannot acknowledge any target frames before input completes`() {
        val receipt = LabyrinthBossTabReceipt(1, 2, 1_000L)
        receipt.markDispatched(1_100L)
        assertEquals(LabyrinthBossTabAck.WAIT, receipt.observe(teamPage, true, 2, 1_400L))
        assertEquals(LabyrinthBossTabAck.WAIT, receipt.observe(teamPage, true, 2, 1_700L))
        assertEquals(LabyrinthBossTabAck.WAIT, receipt.observe(teamPage, true, 2, 8_000L))
        assertEquals(0, receipt.snapshot().ackFrames)
        assertNull(receipt.snapshot().feedbackAfterMillis)
        assertNull(receipt.snapshot().lastEvidenceAtMillis)
        receipt.inputFinished(8_100L)
        assertEquals(LabyrinthBossTabAck.WAIT, receipt.observe(teamPage, true, 2, 8_400L))
        assertEquals(LabyrinthBossTabAck.ACKNOWLEDGED, receipt.observe(teamPage, true, 2, 8_700L))
    }

    @Test
    fun `receipt ignores screenshots at or before completion and repeated or reversed captures`() {
        val receipt = finishedReceipt()
        for (at in listOf(900L, 1_000L)) {
            assertEquals(LabyrinthBossTabAck.WAIT, receipt.observe(teamPage, true, 2, at))
        }
        assertEquals(0, receipt.snapshot().ackFrames)
        assertEquals(LabyrinthBossTabAck.WAIT, receipt.observe(teamPage, true, 2, 1_300L))
        // Duplicate evidence cannot count as frame two; an old wrong-tab frame cannot erase it.
        assertEquals(LabyrinthBossTabAck.WAIT, receipt.observe(teamPage, true, 2, 1_300L))
        assertEquals(LabyrinthBossTabAck.WAIT, receipt.observe(teamPage, true, 3, 1_200L))
        assertEquals(1, receipt.snapshot().ackFrames)
        assertEquals(1_300L, receipt.snapshot().lastEvidenceAtMillis)
        assertEquals(LabyrinthBossTabAck.ACKNOWLEDGED, receipt.observe(teamPage, true, 2, 1_600L))
    }

    @Test
    fun `receipt requires at least two stable target frames separated by one capture cadence`() {
        val receipt = finishedReceipt()
        assertEquals(LabyrinthBossTabAck.WAIT, receipt.observe(teamPage, true, 2, 1_300L))
        assertEquals(LabyrinthBossTabAck.WAIT, receipt.observe(teamPage, true, 2, 1_599L))
        assertEquals(LabyrinthBossTabAck.ACKNOWLEDGED, receipt.observe(teamPage, true, 2, 1_600L))
        assertEquals("ACKED", receipt.snapshot().phase)
        assertEquals(LabyrinthBossTabAck.ACKNOWLEDGED, receipt.observe(teamPage, false, 1, 7_000L))
    }

    @Test
    fun `receipt restarts target stability after unknown wrong tab null index or unstable frame`() {
        val interruptions = listOf(
            Triple(LabyrinthEntryPageState.UNKNOWN, true, 2),
            Triple(teamPage, true, 3),
            Triple(teamPage, true, null),
            Triple(teamPage, false, 2),
        )
        for ((page, stable, index) in interruptions) {
            val receipt = finishedReceipt()
            assertEquals(LabyrinthBossTabAck.WAIT, receipt.observe(teamPage, true, 2, 1_300L))
            assertEquals(LabyrinthBossTabAck.WAIT, receipt.observe(page, stable, index, 1_600L))
            assertEquals(0, receipt.snapshot().ackFrames)
            assertEquals(LabyrinthBossTabAck.WAIT, receipt.observe(teamPage, true, 2, 1_900L))
            assertEquals(LabyrinthBossTabAck.ACKNOWLEDGED, receipt.observe(teamPage, true, 2, 2_200L))
        }
    }

    @Test
    fun `receipt ordinary page and tab changes do not move the four second deadline`() {
        val receipt = finishedReceipt()
        assertEquals(LabyrinthBossTabAck.WAIT, receipt.observe(teamPage, true, 1, 1_300L))
        assertEquals(LabyrinthBossTabAck.WAIT, receipt.observe(LabyrinthEntryPageState.UNKNOWN, true, 2, 2_200L))
        assertEquals(LabyrinthBossTabAck.WAIT, receipt.observe(teamPage, true, 3, 3_100L))
        assertEquals(LabyrinthBossTabAck.WAIT, receipt.observe(teamPage, false, 2, 4_999L))
        assertEquals(1_000L, receipt.snapshot().feedbackAfterMillis)
        assertEquals(LabyrinthBossTabAck.TIMED_OUT, receipt.observe(teamPage, true, 1, 5_000L))
        assertEquals("TIMED_OUT", receipt.snapshot().phase)
        assertEquals(LabyrinthBossTabAck.TIMED_OUT, receipt.observe(teamPage, true, 2, 5_300L))
    }

    @Test
    fun `receipt target first seen at deadline gets exactly one fresh frame recheck`() {
        val receipt = finishedReceipt()
        assertEquals(LabyrinthBossTabAck.WAIT, receipt.observe(teamPage, true, 2, 5_000L))
        assertEquals("RECHECK_AFTER_TIMEOUT", receipt.snapshot().phase)
        assertEquals(1_000L, receipt.snapshot().feedbackAfterMillis)
        assertEquals(LabyrinthBossTabAck.WAIT, receipt.observe(teamPage, true, 2, 5_000L))
        assertEquals(1, receipt.snapshot().ackFrames)
        assertEquals(LabyrinthBossTabAck.ACKNOWLEDGED, receipt.observe(teamPage, true, 2, 5_300L))
    }

    @Test
    fun `receipt unsuccessful deadline recheck times out rather than opening a new window`() {
        val receipt = finishedReceipt()
        assertEquals(LabyrinthBossTabAck.WAIT, receipt.observe(teamPage, true, 2, 5_000L))
        assertEquals(LabyrinthBossTabAck.TIMED_OUT, receipt.observe(teamPage, false, 2, 5_300L))
        assertEquals(LabyrinthBossTabAck.TIMED_OUT, receipt.observe(teamPage, true, 2, 5_600L))
        assertEquals("TIMED_OUT", receipt.snapshot().phase)
        assertEquals(0, receipt.snapshot().ackFrames)
    }

    @Test
    fun `receipt late fresh target cannot turn an expired final recheck into an ACK`() {
        val receipt = finishedReceipt()
        assertEquals(LabyrinthBossTabAck.WAIT, receipt.observe(teamPage, true, 2, 5_000L))
        assertEquals("RECHECK_AFTER_TIMEOUT", receipt.snapshot().phase)
        assertEquals(LabyrinthBossTabAck.TIMED_OUT, receipt.observe(teamPage, true, 2, 10_000L))
        assertEquals("TIMED_OUT", receipt.snapshot().phase)
        assertEquals(1_000L, receipt.snapshot().feedbackAfterMillis)
        // The one recheck has a 600 ms outer bound, not a new four-second window.
        val withinBound = finishedReceipt()
        assertEquals(LabyrinthBossTabAck.WAIT, withinBound.observe(teamPage, true, 2, 5_000L))
        assertEquals(LabyrinthBossTabAck.ACKNOWLEDGED, withinBound.observe(teamPage, true, 2, 5_600L))
        val pastBound = finishedReceipt()
        assertEquals(LabyrinthBossTabAck.WAIT, pastBound.observe(teamPage, true, 2, 5_000L))
        assertEquals(LabyrinthBossTabAck.TIMED_OUT, pastBound.observe(teamPage, true, 2, 5_601L))
    }

    @Test
    fun `receipt rejection cannot leave a successful acknowledgement`() {
        val receipt = finishedReceipt()
        assertEquals(LabyrinthBossTabAck.WAIT, receipt.observe(teamPage, true, 2, 1_300L))
        receipt.rejected()
        assertEquals(LabyrinthBossTabAck.TIMED_OUT, receipt.observe(teamPage, true, 2, 1_600L))
        assertEquals("REJECTED", receipt.snapshot(active = false).phase)
        assertFalse(receipt.snapshot(active = false).active)
    }

    // ---- Session integration: real planning -> real dispatch -> real per-frame feedback ----

    @Test
    fun `planned NEXT really dispatches and commits only after the second fresh target frame`() = runBlocking {
        withBossSession { fixture ->
            val source = fixture.team(1)
            installReadyPlan(fixture, source)
            fixture.clock.set(1_000L)
            fixture.invoke("handleBattleTeamSelectionExecution", fixture.id, source, 1920, 1080, 1_000L)
            fixture.awaitActions()
            assertEquals("NEXT must reach the backend, not be rejected by its own pending record", 1, fixture.calls.get())
            assertEquals(LabyrinthBattleTeamExecutionKind.NEXT_BOSS_TEAM, pendingKind(fixture))
            assertNotNull(activeReceipt(fixture))
            assertTrue(committedIds(fixture).isEmpty())
            assertTrue(signatures(fixture).isEmpty())
            assertNotNull(fixture.get("pendingBossTeamAdvance"))
            fixture.set("battleTeamActionUnconfirmedAttempts", 2)

            fixture.feed(fixture.team(2, members = false), 1_300L)
            assertNotNull(fixture.pending())
            assertEquals(1, requireNotNull(activeReceipt(fixture)).snapshot().ackFrames)
            assertEquals(1, fixture.get("bossEditorTeamIndex"))
            assertEquals(2, attempts(fixture))
            assertTrue(committedIds(fixture).isEmpty())

            fixture.feed(fixture.team(2, members = false), 1_600L)
            fixture.awaitActions()
            assertNull(fixture.pending())
            assertEquals("ACKED", diagnostic(fixture).phase)
            assertFalse(diagnostic(fixture).active)
            assertEquals(0, attempts(fixture))
            assertEquals(2, fixture.get("bossEditorTeamIndex"))
            assertEquals(2, (fixture.get("combatContext") as LabyrinthCombatContext).teamIndex)
            assertEquals(fixture.ids.toSet(), committedIds(fixture))
            assertEquals(setOf(labyrinthBattleTeamSignature(fixture.ids)), signatures(fixture))
            assertNull(fixture.get("pendingBossTeamAdvance"))
            assertEquals(1, fixture.calls.get())
        }
    }

    @Test
    fun `planned RESET return navigation dispatches and completes only after two stable frames`() = runBlocking {
        withBossSession { fixture ->
            fixture.set("bossEditorTeamIndex", 3)
            fixture.set("bossEditorPreparationStage", LabyrinthBossEditorPreparationStage.RETURN_TEAM_1)
            fixture.feed(fixture.team(3, members = false), 700L)
            fixture.awaitActions()
            assertEquals("first post-entry frame only establishes page stability", 0, fixture.calls.get())
            fixture.feed(fixture.team(3, members = false), 1_000L)
            fixture.awaitActions()
            assertEquals(1, fixture.calls.get())
            assertEquals(LabyrinthBattleTeamExecutionKind.RESET_BOSS_SWITCH_TEAM, pendingKind(fixture))
            assertEquals(1, requireNotNull(activeReceipt(fixture)).expectedIndex)
            fixture.set("battleTeamActionUnconfirmedAttempts", 2)
            fixture.feed(fixture.team(1, members = false), 1_300L)
            assertNotNull(fixture.pending())
            assertEquals(LabyrinthBossEditorPreparationStage.RETURN_TEAM_1, fixture.get("bossEditorPreparationStage"))
            assertEquals(3, fixture.get("bossEditorTeamIndex"))
            assertEquals(2, attempts(fixture))
            fixture.feed(fixture.team(1, members = false), 1_600L)
            fixture.awaitActions()
            assertNull(fixture.pending())
            assertEquals("ACKED", diagnostic(fixture).phase)
            assertEquals(LabyrinthBossEditorPreparationStage.COMPLETE, fixture.get("bossEditorPreparationStage"))
            assertEquals(1, fixture.get("bossEditorTeamIndex"))
            assertEquals(0, attempts(fixture))
            assertTrue("RESET is cleanup navigation, never a committed team", committedIds(fixture).isEmpty())
            assertTrue(signatures(fixture).isEmpty())
            assertEquals(1, fixture.calls.get())
        }
    }

    @Test
    fun `session ignores targets during held input and screenshots captured before completion for both kinds`() = runBlocking {
        for (kind in switchKinds) withBossSession { fixture ->
            val started = CompletableDeferred<Unit>()
            val finishInput = CompletableDeferred<AutomationBackendResult>()
            // Substitute only the backend to hold real asynchronous input; the production latch,
            // dispatch receipt and executor completion handling remain in use.
            fixture.set("actionExecutor", SessionBoundActionExecutor(fixture.manager) {
                fixture.calls.incrementAndGet()
                started.complete(Unit)
                finishInput.await()
            })
            try {
                val step = dispatchSwitch(fixture, kind, 1_000L)
                withTimeout(2_000L) { started.await() }
                val receipt = requireNotNull(activeReceipt(fixture))
                fixture.feed(targetFrame(fixture, kind), 1_300L)
                fixture.feed(targetFrame(fixture, kind), 1_600L)
                assertEquals("DISPATCHED", receipt.snapshot().phase)
                assertEquals(0, receipt.snapshot().ackFrames)
                assertNull(receipt.snapshot().feedbackAfterMillis)
                assertNotNull(fixture.pending())
                assertTrue(committedIds(fixture).isEmpty())
                // A second dispatch attempt while input is held must not enqueue another tap.
                fixture.invoke("dispatchBattleTeamAction", fixture.id, step, 1_600L)
                assertEquals(1, fixture.calls.get())
                fixture.clock.set(2_100L)
                finishInput.complete(AutomationBackendResult.Completed)
                fixture.awaitActions()
                assertEquals(2_100L, receipt.snapshot().feedbackAfterMillis)
                for (staleAt in listOf(1_600L, 2_100L)) {
                    fixture.feed(targetFrame(fixture, kind), staleAt)
                    assertNotNull(fixture.pending())
                    assertEquals(0, receipt.snapshot().ackFrames)
                }
                fixture.feed(targetFrame(fixture, kind), 2_400L)
                assertNotNull(fixture.pending())
                fixture.feed(targetFrame(fixture, kind), 2_700L)
                fixture.awaitActions()
                assertNull(fixture.pending())
                assertEquals("ACKED", diagnostic(fixture).phase)
                assertEquals(1, fixture.calls.get())
            } finally {
                finishInput.complete(AutomationBackendResult.Completed)
                fixture.awaitActions()
            }
        }
    }

    @Test
    fun `session pending receipt blocks both frame planning and direct duplicate dispatch after input returns`() = runBlocking {
        for (kind in switchKinds) withBossSession { fixture ->
            val step = dispatchSwitch(fixture, kind, 1_000L)
            fixture.awaitActions()
            val originalPending = fixture.pending()
            for (at in listOf(1_300L, 1_600L, 1_900L, 2_200L)) {
                fixture.feed(sourceFrame(fixture, kind), at)
                assertSame(originalPending, fixture.pending())
                assertEquals(1, fixture.calls.get())
            }
            fixture.state { it.copy(frameTrace = LabyrinthFrameTrace()) }
            fixture.invoke("handleBattleTeamSelectionExecution", fixture.id, sourceFrame(fixture, kind), 1920, 1080, 2_500L)
            fixture.invoke("dispatchBattleTeamAction", fixture.id, step, 2_500L)
            fixture.awaitActions()
            assertSame(originalPending, fixture.pending())
            assertNull(fixture.session.state.value.frameTrace.actionLabel)
            assertEquals("boss-tab-awaiting-ack", fixture.session.state.value.frameTrace.actionRejectReason)
            assertEquals(1, fixture.calls.get())
        }
    }

    @Test
    fun `session wrong tab or unstable target cannot acknowledge clear failures or advance the boss editor`() = runBlocking {
        withBossSession { fixture ->
            dispatchSwitch(fixture, LabyrinthBattleTeamExecutionKind.NEXT_BOSS_TEAM, 1_000L)
            fixture.awaitActions()
            fixture.set("battleTeamActionUnconfirmedAttempts", 2)
            for ((frame, at) in listOf(
                fixture.team(3, members = false) to 1_300L,
                fixture.team(2, members = false, stable = false) to 1_600L,
                fixture.team(null, members = false) to 1_900L,
            )) {
                fixture.feed(frame, at)
                assertNotNull(fixture.pending())
                assertEquals(0, requireNotNull(activeReceipt(fixture)).snapshot().ackFrames)
                assertEquals(1, fixture.get("bossEditorTeamIndex"))
                assertEquals(2, attempts(fixture))
                assertTrue(committedIds(fixture).isEmpty())
            }
            fixture.feed(fixture.team(2, members = false), 2_200L)
            assertNotNull(fixture.pending())
            fixture.feed(fixture.team(2, members = false, stable = false), 2_500L)
            assertEquals(0, requireNotNull(activeReceipt(fixture)).snapshot().ackFrames)
            fixture.feed(fixture.team(2, members = false), 2_800L)
            assertNotNull(fixture.pending())
            fixture.feed(fixture.team(2, members = false), 3_100L)
            fixture.awaitActions()
            assertNull(fixture.pending())
            assertEquals(0, attempts(fixture))
            assertEquals(fixture.ids.toSet(), committedIds(fixture))
            assertEquals(1, fixture.calls.get())
        }
    }

    @Test
    fun `session ordinary page churn cannot acknowledge or postpone timeout and recovery count for either kind`() = runBlocking {
        for (kind in switchKinds) withBossSession { fixture ->
            dispatchSwitch(fixture, kind, 1_000L)
            fixture.awaitActions()
            val receipt = requireNotNull(activeReceipt(fixture))
            fixture.feed(fixture.team(null, members = false, page = LabyrinthEntryPageState.UNKNOWN), 1_300L)
            fixture.feed(fixture.team(if (kind == LabyrinthBattleTeamExecutionKind.NEXT_BOSS_TEAM) 3 else 2, members = false), 2_200L)
            fixture.feed(fixture.team(null, members = false, page = LabyrinthEntryPageState.GAME_LOADING_PROGRESS), 3_100L)
            fixture.feed(sourceFrame(fixture, kind), 4_999L)
            assertSame(receipt, activeReceipt(fixture))
            assertEquals(0, attempts(fixture))
            assertEquals(1_000L, receipt.snapshot().feedbackAfterMillis)
            fixture.feed(sourceFrame(fixture, kind), 5_000L)
            assertNull(fixture.pending())
            assertEquals("TIMED_OUT", diagnostic(fixture).phase)
            assertFalse(diagnostic(fixture).active)
            assertEquals(1, attempts(fixture))
            assertEquals(true, fixture.get("bossSwitchRecoveryPending"))
            assertNull(fixture.get("pendingBossTeamAdvance"))
            assertNull(fixture.get("lastBattleTeamExecutionKey"))
            assertNull(fixture.session.state.value.battleTeamSelectionPlan)
            assertTrue(committedIds(fixture).isEmpty())
            assertTrue(signatures(fixture).isEmpty())
            fixture.feed(fixture.team(null, members = false, page = LabyrinthEntryPageState.UNKNOWN), 5_300L)
            assertEquals("generic page progress after recovery must not forgive a failed switch", 1, attempts(fixture))
            assertEquals(1, fixture.calls.get())
        }
    }

    @Test
    fun `session target at deadline rechecks once without sending another tap`() = runBlocking {
        withBossSession { fixture ->
            dispatchSwitch(fixture, LabyrinthBattleTeamExecutionKind.NEXT_BOSS_TEAM, 1_000L)
            fixture.awaitActions()
            fixture.feed(fixture.team(1), 4_900L)
            fixture.feed(fixture.team(2, members = false), 5_000L)
            assertNotNull(fixture.pending())
            assertEquals("RECHECK_AFTER_TIMEOUT", requireNotNull(activeReceipt(fixture)).snapshot().phase)
            assertEquals(0, attempts(fixture))
            assertEquals(1, fixture.calls.get())
            fixture.feed(fixture.team(2, members = false), 5_300L)
            fixture.awaitActions()
            assertNull(fixture.pending())
            assertEquals("ACKED", diagnostic(fixture).phase)
            assertEquals(fixture.ids.toSet(), committedIds(fixture))
            assertEquals(1, fixture.calls.get())
        }
    }

    @Test
    fun `session late final recheck times out and never commits or advances either kind`() = runBlocking {
        for (kind in switchKinds) withBossSession { fixture ->
            dispatchSwitch(fixture, kind, 1_000L)
            fixture.awaitActions()
            fixture.feed(targetFrame(fixture, kind), 5_000L)
            assertNotNull(fixture.pending())
            assertEquals("RECHECK_AFTER_TIMEOUT", requireNotNull(activeReceipt(fixture)).snapshot().phase)
            fixture.feed(targetFrame(fixture, kind), 10_000L)
            assertNull(fixture.pending())
            assertEquals("TIMED_OUT", diagnostic(fixture).phase)
            assertEquals(1, attempts(fixture))
            assertEquals(if (kind == LabyrinthBattleTeamExecutionKind.NEXT_BOSS_TEAM) 1 else 3, fixture.get("bossEditorTeamIndex"))
            assertNull(fixture.get("pendingBossTeamAdvance"))
            assertTrue(committedIds(fixture).isEmpty())
            assertTrue(signatures(fixture).isEmpty())
            assertEquals(1, fixture.calls.get())
        }
    }

    @Test
    fun `session failed RESET switches replan and retry but stop on the fourth unconfirmed dispatch`() = runBlocking {
        withBossSession { fixture ->
            fixture.set("bossEditorTeamIndex", 3)
            fixture.set("bossEditorPreparationStage", LabyrinthBossEditorPreparationStage.RETURN_TEAM_1)
            fixture.feed(fixture.team(3, members = false), 700L)
            fixture.awaitActions()
            assertEquals("first post-entry frame only establishes page stability", 0, fixture.calls.get())
            var dispatchedAt = 1_000L
            for (attempt in 1..4) {
                fixture.feed(fixture.team(3, members = false), dispatchedAt)
                fixture.awaitActions()
                assertEquals("one real gesture per recovery attempt", attempt, fixture.calls.get())
                assertNotNull(fixture.pending())
                assertEquals(LabyrinthBattleTeamExecutionKind.RESET_BOSS_SWITCH_TEAM, pendingKind(fixture))
                fixture.feed(fixture.team(3, members = false), dispatchedAt + 3_999L)
                assertNotNull(fixture.pending())
                fixture.feed(fixture.team(3, members = false), dispatchedAt + 4_000L)
                if (attempt < 4) {
                    assertNull(fixture.pending())
                    assertEquals(attempt, attempts(fixture))
                    assertEquals("TIMED_OUT", diagnostic(fixture).phase)
                    assertEquals(true, fixture.get("bossSwitchRecoveryPending"))
                    assertNull(fixture.get("lastBattleTeamExecutionKey"))
                    assertTrue(committedIds(fixture).isEmpty())
                } else {
                    withTimeout(2_000L) {
                        while (fixture.session.state.value.status != LabyrinthEntryRecognitionStatus.STOPPED) delay(1L)
                    }
                    val message = fixture.session.state.value.message.orEmpty()
                    assertTrue(message, message.contains("Boss切队连续4次反馈未确认"))
                    assertTrue(message, message.contains("已停止并保留诊断状态"))
                    assertEquals(4, fixture.calls.get())
                }
                dispatchedAt += 4_300L
            }
        }
    }

    @Test
    fun `session backend rejection removes pending switch and cannot later become an ACK for either kind`() = runBlocking {
        for (kind in switchKinds) withBossSession { fixture ->
            fixture.outcome.set(AutomationBackendResult.Rejected(GAME_NOT_FOREGROUND_REASON))
            dispatchSwitch(fixture, kind, 1_000L)
            fixture.awaitActions()
            assertEquals(1, fixture.calls.get())
            assertNull(fixture.pending())
            assertNull(fixture.get("pendingBossTeamAdvance"))
            assertEquals("REJECTED", diagnostic(fixture).phase)
            assertFalse(diagnostic(fixture).active)
            assertNull(diagnostic(fixture).feedbackAfterMillis)
            assertEquals(Long.MIN_VALUE, fixture.get("lastSessionActionAt"))
            assertTrue(committedIds(fixture).isEmpty())
            assertEquals(0, attempts(fixture))
            fixture.feed(targetFrame(fixture, kind), 1_300L)
            fixture.feed(targetFrame(fixture, kind), 1_600L)
            fixture.awaitActions()
            assertTrue("no detached failed dispatch may be acknowledged", (fixture.get("lastBossTabDiagnostic") as? LabyrinthBossTabDiagnostic)?.phase != "ACKED")
            assertTrue(committedIds(fixture).isEmpty())
            assertEquals(LabyrinthEntryRecognitionStatus.RUNNING, fixture.session.state.value.status)
        }
    }

    @Test
    fun `session paused executor never reaches backend and leaves no switch ACK receipt`() = runBlocking {
        withBossSession { fixture ->
            assertTrue(fixture.manager.setPaused(fixture.id, true))
            dispatchSwitch(fixture, LabyrinthBattleTeamExecutionKind.NEXT_BOSS_TEAM, 1_000L)
            fixture.awaitActions()
            assertEquals(0, fixture.calls.get())
            assertNull(fixture.pending())
            assertNull(fixture.get("pendingBossTeamAdvance"))
            assertEquals("REJECTED", diagnostic(fixture).phase)
            assertNull(diagnostic(fixture).feedbackAfterMillis)
            assertTrue(committedIds(fixture).isEmpty())
            assertTrue(fixture.manager.setPaused(fixture.id, false))
        }
    }

    @Test
    fun `START_BATTLE still commits on execution and accepts ordinary battle page progress without a tab receipt`() = runBlocking {
        withBossSession { fixture ->
            fixture.set("combatContext", LabyrinthCombatContext(LabyrinthCombatKind.NORMAL))
            val source = fixture.team(null)
            installReadyPlan(fixture, source)
            fixture.clock.set(1_000L)
            fixture.invoke("handleBattleTeamSelectionExecution", fixture.id, source, 1920, 1080, 1_000L)
            fixture.awaitActions()
            assertEquals(1, fixture.calls.get())
            assertEquals(LabyrinthBattleTeamExecutionKind.START_BATTLE, pendingKind(fixture))
            assertNull(activeReceipt(fixture))
            assertEquals(fixture.ids.toSet(), committedIds(fixture))
            assertEquals(setOf(labyrinthBattleTeamSignature(fixture.ids)), signatures(fixture))
            fixture.set("battleTeamActionUnconfirmedAttempts", 2)
            fixture.feed(fixture.team(null, page = LabyrinthEntryPageState.BATTLE_IN_PROGRESS), 1_300L)
            assertNull(fixture.pending())
            assertEquals(0, attempts(fixture))
            assertNull(fixture.get("lastBossTabDiagnostic"))
            assertEquals(1, fixture.calls.get())
        }
    }

    @Test
    fun `swallowed START_BATTLE retains its original four second rollback and retry behavior`() = runBlocking {
        withBossSession { fixture ->
            fixture.set("combatContext", LabyrinthCombatContext(LabyrinthCombatKind.NORMAL))
            fixture.set("lastNormalBattleTeamSignature", "previous-normal-team")
            val source = fixture.team(null)
            installReadyPlan(fixture, source)
            fixture.clock.set(1_000L)
            fixture.invoke("handleBattleTeamSelectionExecution", fixture.id, source, 1920, 1080, 1_000L)
            fixture.awaitActions()
            assertEquals(labyrinthBattleTeamSignature(fixture.ids), fixture.get("lastNormalBattleTeamSignature"))
            fixture.feed(source, 4_999L)
            assertNotNull(fixture.pending())
            assertEquals(fixture.ids.toSet(), committedIds(fixture))
            fixture.feed(source, 5_000L)
            assertNull(fixture.pending())
            assertEquals(1, attempts(fixture))
            assertTrue(committedIds(fixture).isEmpty())
            assertTrue(signatures(fixture).isEmpty())
            assertEquals("previous-normal-team", fixture.get("lastNormalBattleTeamSignature"))
            assertNull(fixture.get("lastBossTabDiagnostic"))
            installReadyPlan(fixture, source)
            fixture.clock.set(5_600L)
            fixture.invoke("handleBattleTeamSelectionExecution", fixture.id, source, 1920, 1080, 5_600L)
            fixture.awaitActions()
            assertEquals(2, fixture.calls.get())
            assertEquals(LabyrinthBattleTeamExecutionKind.START_BATTLE, pendingKind(fixture))
            assertNull(activeReceipt(fixture))
        }
    }

    // ---- Small test-only setup helpers; session handlers and receipts remain production ones ----

    private fun finishedReceipt() = LabyrinthBossTabReceipt(1, 2, 1_000L).apply { inputFinished(1_000L) }

    private suspend fun withBossSession(body: suspend (Batch2SessionFixture) -> Unit) {
        // No recommendation search is needed: tests install an already-ready visual plan when
        // testing NEXT/START planning, keeping these ACK regressions independent of scoring.
        val fixture = Batch2SessionFixture()
        fixture.start()
        // The fixture has no map assets. Supply only the route-owned-page presence gate so a
        // real processFrameResult reaches handlePostEntryPage rather than the entry planner.
        // These tests never visit NODE_SELECTION, so neither loader may actually be called.
        val unusedRouteLoader: suspend (Long?) -> LabyrinthRouteJson? = { error("ACK test must not load a map route") }
        val unusedTemplateLoader: () -> com.landosol.toolbox.labyrinth.node.NodeTemplateSet = { error("ACK test must not load map templates") }
        fixture.set("routeLoader", unusedRouteLoader)
        fixture.set("nodeTemplateLoader", unusedTemplateLoader)
        fixture.set("combatContext", LabyrinthCombatContext(LabyrinthCombatKind.BOSS))
        fixture.set("bossEditorTeamIndex", 1)
        fixture.set("bossEditorPreparationStage", LabyrinthBossEditorPreparationStage.COMPLETE)
        try { body(fixture) } finally { fixture.close() }
    }

    private fun installReadyPlan(fixture: Batch2SessionFixture, result: LabyrinthEntryFrameResult) {
        val members = fixture.ids.map { LabyrinthRecommendedTeamMember(it, it) }
        val recommendation = LabyrinthBattleTeamRecommendation(
            members = members, damageType = LabyrinthTeamDamageType.PHYSICAL, score = 1_000.0,
            vanguard = members.first(), survivalAnchor = null, reasons = emptyList(),
            defenseMarkStacks = 0, targetCount = 1,
        )
        val plan = LabyrinthBattleTeamSelectionPlan(
            sessionId = fixture.id, recommendedIds = fixture.ids, recommendedNames = emptyMap(),
            currentlySelectedIds = fixture.ids, currentlySelectedNames = emptyMap(), alreadyCorrectIds = fixture.ids,
            needSelectIds = emptyList(), needDeselectIds = emptyList(), visibleSelectTargets = emptyList(),
            visibleDeselectTargets = emptyList(), unsafeVisibleMatches = emptyList(), notCurrentlyVisibleIds = emptyList(),
            currentFilter = LabyrinthBattleElementFilter.ALL, recommendedNextFilter = null, recommendedFilterTarget = null,
            missingTargetFilters = emptyList(), scrollRequired = false, viewportRevision = 0L,
            readyToExecute = true, blockedReason = null, teamReady = true, minimumCharacterConfidence = 0.0,
        )
        fixture.state { it.copy(lastResult = result, frameTrace = LabyrinthFrameTrace(),
            battleTeamRecommendation = recommendation, battleTeamSelectionPlan = plan) }
    }

    private fun dispatchSwitch(fixture: Batch2SessionFixture, kind: LabyrinthBattleTeamExecutionKind, at: Long): LabyrinthBattleTeamExecutionStep {
        val next = kind == LabyrinthBattleTeamExecutionKind.NEXT_BOSS_TEAM
        fixture.set("bossEditorTeamIndex", if (next) 1 else 3)
        fixture.set("bossEditorPreparationStage", if (next) LabyrinthBossEditorPreparationStage.COMPLETE else LabyrinthBossEditorPreparationStage.RETURN_TEAM_1)
        fixture.clock.set(at)
        fixture.state { it.copy(lastResult = sourceFrame(fixture, kind), frameTrace = LabyrinthFrameTrace()) }
        val step = LabyrinthBattleTeamExecutionStep(
            kind = kind, key = if (next) "boss-next:1:${fixture.ids.joinToString(",")}" else "boss-reset-tab:3->1:0",
            label = if (next) "Boss第1队编组完成，切换队伍2" else "Boss预处理：切换队伍1",
            action = AutomationAction.Tap(ScreenPoint(if (next) 315f else 135f, 165f)),
            confirmedTeamIds = if (next) fixture.ids else emptyList(),
        )
        fixture.invoke("dispatchBattleTeamAction", fixture.id, step, at)
        return step
    }

    private fun sourceFrame(fixture: Batch2SessionFixture, kind: LabyrinthBattleTeamExecutionKind) =
        fixture.team(if (kind == LabyrinthBattleTeamExecutionKind.NEXT_BOSS_TEAM) 1 else 3,
            members = kind == LabyrinthBattleTeamExecutionKind.NEXT_BOSS_TEAM)

    private fun targetFrame(fixture: Batch2SessionFixture, kind: LabyrinthBattleTeamExecutionKind) =
        fixture.team(if (kind == LabyrinthBattleTeamExecutionKind.NEXT_BOSS_TEAM) 2 else 1, members = false)

    private fun pendingField(fixture: Batch2SessionFixture, name: String): Any? = fixture.pending()?.let { pending ->
        pending.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(pending)
    }

    private fun pendingKind(fixture: Batch2SessionFixture) = pendingField(fixture, "kind") as? LabyrinthBattleTeamExecutionKind
    private fun activeReceipt(fixture: Batch2SessionFixture) = pendingField(fixture, "switchReceipt") as? LabyrinthBossTabReceipt
    private fun diagnostic(fixture: Batch2SessionFixture) = requireNotNull(fixture.get("lastBossTabDiagnostic") as? LabyrinthBossTabDiagnostic)
    private fun attempts(fixture: Batch2SessionFixture) = fixture.get("battleTeamActionUnconfirmedAttempts") as Int
    private fun committedIds(fixture: Batch2SessionFixture) = (fixture.get("committedBattleCharacterIds") as Set<*>).filterIsInstance<String>().toSet()
    private fun signatures(fixture: Batch2SessionFixture) = (fixture.get("currentBattleTeamSignatures") as Set<*>).filterIsInstance<String>().toSet()

    private val switchKinds = listOf(LabyrinthBattleTeamExecutionKind.NEXT_BOSS_TEAM, LabyrinthBattleTeamExecutionKind.RESET_BOSS_SWITCH_TEAM)
}
