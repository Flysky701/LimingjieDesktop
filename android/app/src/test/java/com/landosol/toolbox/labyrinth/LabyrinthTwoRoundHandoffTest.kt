package com.landosol.toolbox.labyrinth

import com.landosol.toolbox.automation.*
import com.landosol.toolbox.automation.session.*
import com.landosol.toolbox.labyrinth.batch.*
import com.landosol.toolbox.labyrinth.vision.*
import com.landosol.toolbox.labyrinth.vision.LabyrinthResultHandoffRegressionTest.Companion.frame
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test

/** Offline transport only: production classifier, run session, reset workflow, tracker,
 * terminator and batch controller are all used. No ADB, network or user data. */
class LabyrinthTwoRoundHandoffTest {
    private fun page(vararg anchors: String): LabyrinthEntryFrameResult {
        val scores = LabyrinthAnchorScores(anchors.associateWith { 0.99 })
        return LabyrinthEntryFrameResult(
            observation = LabyrinthEntryPageClassifier().classify(scores), matchedFeatures = emptyList(),
            elapsedMillis = 0, frameWidth = 1920, frameHeight = 1080,
            anchorMatches = anchors.associateWith { EntryAnchorMatch(0.99, EntryPixelRect(800, 700, 200, 80)) },
        )
    }

    @Test fun `two rounds use actual reset navigation and leave the last score page visible`() = replay(false)

    @Test fun `a result page reappearing after title is not a synchronized next-round state`() = replay(true)

    private fun replay(unexpectedResultAfterTitle: Boolean) = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val manager = AutomationSessionManager()
        val now = AtomicLong(1_000)
        val events = CopyOnWriteArrayList<LabyrinthRunTerminalEvent>()
        val runTaps = CopyOnWriteArrayList<AutomationAction>()
        val resetTaps = CopyOnWriteArrayList<LabyrinthEntryPageState>()
        val summary = frame("run-result-next-button-20260920.jpg")
        val score = frame("run-clear-20260917/t0.5.jpg")
        val chest = frame("run-clear-20260917/t5.5.jpg")
        val item = frame("run-clear-20260917/t7.5.jpg")
        val dawn = frame("run-clear-20260917/t10.5.jpg")
        val home = page(EntryAnchorId.HOME_LEVEL, EntryAnchorId.HOME_ADVENTURE)
        val adventure = page(EntryAnchorId.ADVENTURE_HEADER, EntryAnchorId.LABYRINTH_ENTRY)
        val popup = page(EntryAnchorId.SESSION_ERROR_TITLE, EntryAnchorId.SESSION_RETURN_TITLE)
        val title = page(EntryAnchorId.TITLE_LOGO, EntryAnchorId.TITLE_TAP_PROMPT)
        val visible = AtomicReference(home)
        val session = LabyrinthEntryRecognitionSession(
            sessionManager = manager, captureActive = { true }, captureStop = {}, clock = now::get,
            processorFactory = { { error("frames are replayed offline") } }, actionScope = scope,
            actionExecutor = SessionBoundActionExecutor(manager) { runTaps += it; AutomationBackendResult.Completed },
            actionsAvailable = { true }, runTerminalListener = { events += it },
        )
        var resets = 0
        var readyCallbacks = 0
        val ports = object : LabyrinthBatchPorts {
            override suspend fun verifyReusableOpening(accountId: Long, opening: LabyrinthBatchReusableOpening):
                LabyrinthBatchReusableOpeningCheck = error("This replay never reuses an opening")
            override suspend fun reroll(accountId: Long, guildId: Int, difficulty: Int) =
                LabyrinthBatchRerollResult.Success(100L + resets)
            override suspend fun invalidateClientSessionAndReturn(accountId: Long): LabyrinthBatchInvalidationResult {
                resets++
                val tracker = SessionExpiryFrameTracker().apply { trackFrame(1920, 1080); record(visible.get()) }
                val terminator = AnchorTriggerSessionExpiryTerminator(
                    onTap = {
                        val before = visible.get()
                        resetTaps += before.observation.state
                        visible.set(when {
                            tracker.popupVisible -> title
                            before.observation.state == LabyrinthEntryPageState.RUN_CLEAR_RESULT -> chest
                            before.observation.state == LabyrinthEntryPageState.RUN_CLEAR_CHEST_RESULT -> item
                            before.observation.state == LabyrinthEntryPageState.ITEM_REWARD -> dawn
                            before.observation.state == LabyrinthEntryPageState.DAWN_REALM_HOME_IDLE -> popup
                            else -> error("Unexpected reset tap: ${before.observation.state}")
                        })
                        true
                    },
                    triggerPoint = { tracker.sessionInvalidationTrigger }, returnTitlePoint = ScreenPoint(960f, 740f),
                    popupVisible = { tracker.popupVisible }, titleReached = { tracker.titleReached },
                    synchronizedWithoutExpiry = { tracker.clientSynchronizedWithoutExpiry }, frameSize = { tracker.frameSize },
                    returnTitleAnchorPoint = { tracker.anchorCenter(EntryAnchorId.SESSION_RETURN_TITLE) },
                    triggerTapDelayMillis = 1, returnTapDelayMillis = 30, popupAfterTapTimeoutMillis = 50,
                )
                val reset = GameSessionResetWorkflow(
                    sessionManager = manager,
                    backend = CompositeSessionResetBackend(terminator, Relauncher { error("No process launch allowed") }),
                    captureActive = { true }, processorFactory = { { error("offline replay") } },
                    blockClassifier = { SessionBlockKind.NONE }, presence = FixedGameClientPresenceObserver(true),
                    actionScope = scope, totalTimeoutMillis = 15_000,
                    onNextRoundReady = { readyCallbacks++ },
                    actionExecutor = SessionBoundActionExecutor(manager) {
                        visible.set(when (visible.get().observation.state) {
                            LabyrinthEntryPageState.TITLE_WAITING_TAP -> if (unexpectedResultAfterTitle) score else home
                            LabyrinthEntryPageState.HOME -> adventure
                            LabyrinthEntryPageState.ADVENTURE -> dawn
                            else -> error("Unexpected navigation tap")
                        })
                        AutomationBackendResult.Completed
                    },
                    entryActionPlannerFactory = { LabyrinthEntryActionPlanner(
                        LabyrinthEntryActionPlannerConfig(pageActionIntervalMillis = 1, titleTapIntervalMillis = 1)) },
                )
                assertTrue(reset.start() is GameSessionResetStartResult.Started)
                val id = manager.current()!!.id
                val observe = reset.javaClass.declaredMethods.single { it.name.startsWith("handleObservation") }
                    .apply { isAccessible = true }
                val planner = LabyrinthEntryActionPlanner(LabyrinthEntryActionPlannerConfig(pageActionIntervalMillis = 1))
                val replay = scope.launch {
                    while (reset.state.value.running) {
                        val current = visible.get()
                        tracker.record(current)
                        observe.invoke(reset, id.value, current, SessionBlockKind.NONE, 1920, 1080, planner)
                        delay(20)
                    }
                }
                try {
                    val end = withTimeout(17_000) { reset.state.first { !it.running } }
                    return if (end.status == GameSessionResetStatus.STOPPED &&
                        end.stage == GameSessionResetStage.READY_FOR_NEXT_ROUND && end.lastBlock == SessionBlockKind.NONE) {
                        LabyrinthBatchInvalidationResult.Success("verified-reset-ready")
                    } else LabyrinthBatchInvalidationResult.Failure("${end.stage}: ${end.message}")
                } finally { replay.cancelAndJoin(); reset.stop() }
            }
            override suspend fun startRun(accountId: Long, guildId: Int, runId: String) =
                session.startAutomation(accountId, runId) is LabyrinthEntryRecognitionStartResult.Started
            override suspend fun stopRun(reason: String) { session.stop(reason, releaseCapture = false) }
            override suspend fun saveCheckpoint(checkpoint: LabyrinthBatchCheckpoint) {}
        }
        val batch = LabyrinthBatchController(ports, clock = now::get)
        val feedMethod = session.javaClass.declaredMethods.single { it.name.startsWith("processFrameResult") }
            .apply { isAccessible = true }
        suspend fun feed(result: LabyrinthEntryFrameResult, time: Long) {
            now.set(time); visible.set(result)
            val id = session.state.value.sessionId ?: return
            feedMethod.invoke(session, id.value, result, 1920, 1080, time)
            delay(30)
        }
        try {
            assertTrue(batch.start("recorded-two-rounds", 1, listOf(LabyrinthBatchGoal(1, 2, LabyrinthBatchGoalMode.CLEARS)), 1))
            assertEquals(1, readyCallbacks)
            // Ordinary battle results do not produce a whole-run terminal outcome.
            val ordinary = page(EntryAnchorId.BATTLE_RESULT_NEXT_BUTTON)
            val completion = LabyrinthResultCompletion()
            assertFalse(completion.observe(ordinary.observation.state, null, 4_000, 1_000))
            assertNull(completion.summary)
            for (round in 0..1) {
                val base = 10_000L + round * 30_000
                repeat(4) { feed(summary, base + it * 1_500) }
                assertEquals(round, events.size)
                assertTrue("summary must navigate", runTaps.size > round)
                feed(score, base + 7_000)
                feed(score, base + 7_500)
                if (round == 0) {
                    feed(score.copy(runResultScore = 46_000), base + 8_000)
                    feed(score.copy(runResultScore = 46_000), base + 9_000)
                } else feed(score, base + 13_000) // missing OCR is bounded and remains a clear
                withTimeout(2_000) { while (events.size <= round) delay(10) }
                val event = events[round] as LabyrinthRunTerminalEvent.Cleared
                assertEquals(base + 7_000, event.summary!!.endedAt)
                assertEquals(if (round == 0) 46_000L else null, event.summary!!.score)
                val tapsBeforeTerminal = runTaps.size
                feed(score, base + 14_000)
                assertEquals(round + 1, events.size)
                assertEquals(tapsBeforeTerminal, runTaps.size)
                batch.onRunTerminal(event)
                batch.onRunTerminal(event)
                assertEquals(round + 1, batch.state.value!!.goals.single().completedCount)
                if (unexpectedResultAfterTitle) {
                    assertEquals("Unexpected RESULT must not authorize the second run", 1, batch.state.value!!.runsStarted)
                    assertEquals(LabyrinthBatchStage.FAILED, batch.state.value!!.stage)
                    assertEquals(1, readyCallbacks)
                    return@runBlocking
                }
            }
            assertEquals(LabyrinthBatchStage.COMPLETED, batch.state.value!!.stage)
            assertEquals(2, resets)
            assertEquals(2, readyCallbacks)
            assertEquals(LabyrinthEntryPageState.RUN_CLEAR_RESULT, visible.get().observation.state)
            assertEquals(listOf(LabyrinthEntryPageState.RUN_CLEAR_RESULT, LabyrinthEntryPageState.RUN_CLEAR_CHEST_RESULT,
                LabyrinthEntryPageState.ITEM_REWARD, LabyrinthEntryPageState.DAWN_REALM_HOME_IDLE,
                LabyrinthEntryPageState.UNKNOWN), resetTaps)
        } finally { session.stop(); scope.cancel() }
    }
}
