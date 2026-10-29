package com.landosol.toolbox.labyrinth

import android.graphics.Bitmap
import com.landosol.toolbox.automation.*
import com.landosol.toolbox.automation.capture.CapturedFrame
import com.landosol.toolbox.automation.capture.CaptureFrameBus
import com.landosol.toolbox.automation.session.*
import com.landosol.toolbox.labyrinth.batch.*
import com.landosol.toolbox.labyrinth.vision.*
import com.landosol.toolbox.clanbattle.recognition.PixelImage
import java.io.File
import javax.imageio.ImageIO
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test

/** Offline transport only: production classifier, run session, reset workflow, tracker,
 * terminator, block classifier and batch controller are all used through CaptureFrameBus.
 * Recognition results come from recorded pixels/synthetic anchors; no ADB, network or user data. */
class LabyrinthTwoRoundHandoffTest {
    private fun page(vararg anchors: String): LabyrinthEntryFrameResult {
        val scores = LabyrinthAnchorScores(anchors.associateWith { 0.99 })
        return LabyrinthEntryFrameResult(
            observation = LabyrinthEntryPageClassifier().classify(scores), matchedFeatures = emptyList(),
            elapsedMillis = 0, frameWidth = 1920, frameHeight = 1080,
            anchorMatches = anchors.associateWith { EntryAnchorMatch(0.99, EntryPixelRect(800, 700, 200, 80)) },
        )
    }

    @Test fun `two rounds finish rewards and return home before next-run handoff`() = replay(false)

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
        val route = LabyrinthRouteJson(
            enterId = 100, guildId = 1, difficulty = 1, attempt = 1, rerollUntilFound = false,
            perfectStart = false, thirdBlockChoice = "", area3BossIds = emptyList(), area5BossIds = emptyList(),
            nodes = emptyList(),
        )
        val session = LabyrinthEntryRecognitionSession(
            sessionManager = manager, captureActive = { true }, captureStop = {}, clock = now::get,
            processorFactory = { { visible.get() } }, actionScope = scope, actionTargetReady = { true },
            actionExecutor = SessionBoundActionExecutor(manager) { runTaps += it; AutomationBackendResult.Completed },
            actionsAvailable = { true }, runTerminalListener = { events += it },
            routeLoader = { route },
            nodeTemplateLoader = { com.landosol.toolbox.labyrinth.node.NodeTemplateSet(emptyMap()) },
            routeExecutionGate = { LabyrinthExecutionGateResult.Allowed(route, "offline fixture") },
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
                    triggerTapDelayMillis = 1, returnTapDelayMillis = 30, popupAfterTapTimeoutMillis = 350,
                )
                val reset = GameSessionResetWorkflow(
                    sessionManager = manager,
                    backend = CompositeSessionResetBackend(terminator, Relauncher { error("No process launch allowed") }),
                    captureActive = { true }, processorFactory = { { captured ->
                        tracker.trackFrame(captured.bitmap.width, captured.bitmap.height)
                        visible.get().also(tracker::record)
                    } },
                    blockClassifier = { result -> SessionBlockClassifier().classify(
                        result.observation.anchorScores.toSessionBlockScores(),
                        result.observation.stateScores[LabyrinthEntryPageState.TITLE_WAITING_TAP] ?: 0.0,
                    ) }, presence = FixedGameClientPresenceObserver(true),
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
                val replay = scope.launch {
                    while (reset.state.value.running) {
                        CaptureFrameBus.dispatch(CapturedFrame(
                            Bitmap.createBitmap(1920, 1080, Bitmap.Config.ARGB_8888), System.currentTimeMillis()))
                        delay(250)
                    }
                }
                try {
                    val end = try { withTimeout(17_000) { reset.state.first { !it.running } } }
                    catch (failure: TimeoutCancellationException) {
                        throw AssertionError("reset=$resets state=${reset.state.value} visible=${visible.get().observation.state} " +
                            "owner=${CaptureFrameBus.currentOwner()} taps=$resetTaps", failure)
                    }
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
        suspend fun feed(result: LabyrinthEntryFrameResult, time: Long) {
            now.set(time); visible.set(result)
            CaptureFrameBus.dispatch(CapturedFrame(Bitmap.createBitmap(1920, 1080, Bitmap.Config.ARGB_8888), time))
            delay(30)
        }
        try {
            assertTrue(batch.start("recorded-two-rounds", 1, listOf(LabyrinthBatchGoal(1, 2, LabyrinthBatchGoalMode.CLEARS)), 1))
            assertEquals(1, readyCallbacks)
            // Ordinary battle results do not produce a whole-run terminal outcome.
            val ordinary = page(EntryAnchorId.BATTLE_RESULT_NEXT_BUTTON)
            val collector = LabyrinthRunScoreCollector()
            collector.observe(ordinary.observation.state, null, 4_000)
            assertNull(collector.score)
            for (round in 0..1) {
                val base = 10_000L + round * 40_000
                repeat(4) { feed(summary, base + it * 1_500) }
                assertEquals(round, events.size)
                assertTrue("summary must navigate", runTaps.size > round)
                feed(score, base + 7_000)
                feed(score, base + 7_500)
                if (round == 0) {
                    feed(score.copy(runResultScore = 46_000), base + 8_000)
                    feed(score.copy(runResultScore = 46_000), base + 10_500)
                } else feed(score, base + 13_000)
                assertEquals("RESULT must not end the run, even without a score", round, events.size)
                // The run session, not the next-run reset workflow, must finish the reward chain.
                val tapsBeforeChest = runTaps.size
                repeat(3) { feed(chest, base + 15_000 + it * 1_500) }
                assertTrue("The run must advance the chest page", runTaps.size > tapsBeforeChest)
                val tapsBeforeItem = runTaps.size
                repeat(3) { feed(item, base + 20_000 + it * 1_500) }
                assertTrue("The run must close the final item page", runTaps.size > tapsBeforeItem)
                assertEquals("Rewards alone do not prove a return home", round, events.size)
                feed(dawn, base + 25_000)
                assertEquals("The home page must be stable", round, events.size)
                feed(dawn, base + 26_500)
                withTimeout(2_000) { while (events.size <= round) delay(10) }
                val event = events[round] as LabyrinthRunTerminalEvent.Cleared
                assertEquals(base + 26_500, event.summary!!.endedAt)
                assertEquals(if (round == 0) 46_000L else null, event.summary!!.score)
                val tapsBeforeTerminal = runTaps.size
                feed(dawn, base + 28_000)
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
            assertEquals(LabyrinthEntryPageState.DAWN_REALM_HOME_IDLE, visible.get().observation.state)
            assertEquals(listOf(LabyrinthEntryPageState.DAWN_REALM_HOME_IDLE,
                LabyrinthEntryPageState.UNKNOWN), resetTaps)
        } finally { session.stop(); scope.cancel() }
    }

    companion object {
        private val root = generateSequence(File(".").canonicalFile, File::getParentFile)
            .first { File(it, "android/app/build.gradle.kts").isFile }
        private val templates by lazy {
            val paths = AndroidLabyrinthEntryTemplateLoader.TEMPLATE_PATHS + AndroidLabyrinthEntryTemplateLoader.OPTIONAL_TEMPLATE_PATHS
            LabyrinthEntryTemplateSet(paths.mapNotNull { (id, path) ->
                File(root, "android/app/src/main/assets/$path").takeIf { it.isFile }?.let { id to read(it) }
            }.toMap())
        }
        fun frame(name: String): LabyrinthEntryFrameResult = LabyrinthEntryFrameProcessor(templates = templates)
            .process(read(File(root, "android/app/src/test/resources/labyrinth/$name")))
        private fun read(file: File): PixelImage {
            val image = requireNotNull(ImageIO.read(file))
            return PixelImage(image.width, image.height,
                image.getRGB(0, 0, image.width, image.height, null, 0, image.width))
        }
    }
}
