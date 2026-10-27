package com.landosol.toolbox.labyrinth

import com.landosol.toolbox.automation.AutomationSessionManager
import com.landosol.toolbox.automation.AutomationMode
import com.landosol.toolbox.automation.AutomationBackendResult
import com.landosol.toolbox.automation.SessionBoundActionExecutor
import com.landosol.toolbox.automation.capture.CaptureFrameBus
import com.landosol.toolbox.labyrinth.batch.LabyrinthRunTerminalEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LabyrinthEntryRecognitionSessionTest {
    @Test
    fun `result ends real session once without reward taps and reports summary`() = runTest {
        val manager = AutomationSessionManager()
        val events = mutableListOf<LabyrinthRunTerminalEvent>()
        var now = 1_000L
        var taps = 0
        var captureStops = 0
        val session = LabyrinthEntryRecognitionSession(
            sessionManager = manager, captureActive = { true }, captureStop = { captureStops++ },
            processorFactory = { { error("no capture needed") } }, clock = { now },
            actionExecutor = SessionBoundActionExecutor(manager) { taps++; AutomationBackendResult.Completed },
            actionsAvailable = { true }, runTerminalListener = { events += it }, actionScope = this,
        )
        assertTrue(session.startAutomation(accountId = 1L, runId = "result-run") is LabyrinthEntryRecognitionStartResult.Started)
        val sessionId = session.state.value.sessionId!!
        val method = session.javaClass.declaredMethods.single { it.name.startsWith("processFrameResult") }.apply { isAccessible = true }
        fun feed(page: com.landosol.toolbox.labyrinth.vision.LabyrinthEntryPageState, time: Long, score: Long? = null) {
            now = time
            val result = com.landosol.toolbox.labyrinth.vision.LabyrinthEntryFrameResult(
                observation = com.landosol.toolbox.labyrinth.vision.LabyrinthEntryPageObservation(
                    page, 1.0, mapOf(page to 1.0), com.landosol.toolbox.labyrinth.vision.LabyrinthAnchorScores(emptyMap())),
                matchedFeatures = emptyList(), elapsedMillis = 0, frameWidth = 1920, frameHeight = 1080, runResultScore = score,
            )
            method.invoke(session, sessionId.value, result, 1920, 1080, time)
        }
        val resultPage = com.landosol.toolbox.labyrinth.vision.LabyrinthEntryPageState.RUN_CLEAR_RESULT
        feed(resultPage, 61_000)
        feed(resultPage, 61_500)
        // A map transition belongs to result collection even with a stale node-error streak.
        session.javaClass.getDeclaredField("nodeErrorStreak").apply { isAccessible = true; setInt(session, 19) }
        feed(com.landosol.toolbox.labyrinth.vision.LabyrinthEntryPageState.NODE_SELECTION, 62_000)
        feed(resultPage, 62_500, 46_000)
        feed(resultPage, 63_500, 46_000)
        runCurrent()
        feed(resultPage, 64_000, 46_000)
        runCurrent()
        org.junit.Assert.assertEquals(LabyrinthRunTerminalEvent.Cleared("result-run", LabyrinthRunSummary(1_000, 61_000, 46_000)), events.single())
        assertFalse(session.state.value.running)
        assertTrue(manager.current() == null)
        assertTrue(CaptureFrameBus.currentOwner() == null)
        org.junit.Assert.assertEquals(0, taps)
        org.junit.Assert.assertEquals(0, captureStops)
    }
    @Test
    fun `starts dry run and releases the shared automation session`() = runTest {
        val manager = AutomationSessionManager()
        var captureStopCalls = 0
        val session = LabyrinthEntryRecognitionSession(
            sessionManager = manager,
            captureActive = { true },
            captureStop = { captureStopCalls++ },
            processorFactory = { { error("test does not dispatch frames") } },
        )

        val result = session.start()

        assertTrue(result is LabyrinthEntryRecognitionStartResult.Started)
        assertTrue(session.state.value.running)
        assertTrue(manager.current()?.mode == AutomationMode.LABYRINTH)
        assertTrue(manager.current()?.dryRun == true)

        assertTrue(session.stop())
        assertFalse(session.state.value.running)
        assertTrue(manager.current() == null)
        assertTrue(captureStopCalls == 1)
    }

    @Test
    fun `stop can end the run while keeping the capture session for the next run`() = runTest {
        val manager = AutomationSessionManager()
        var captureStopCalls = 0
        val session = LabyrinthEntryRecognitionSession(
            sessionManager = manager,
            captureActive = { true },
            captureStop = { captureStopCalls++ },
            processorFactory = { { error("test does not dispatch frames") } },
        )

        assertTrue(session.start() is LabyrinthEntryRecognitionStartResult.Started)
        assertTrue(session.stop("handing off to reroll", releaseCapture = false))

        // Run lifetime ended: shared automation session and frame ownership are released.
        assertFalse(session.state.value.running)
        assertTrue(manager.current() == null)
        assertTrue(CaptureFrameBus.currentOwner() == null)
        // Capture lifetime continues: the projection is untouched.
        assertTrue(captureStopCalls == 0)

        // A second run can start on the same capture without a new authorization.
        assertTrue(session.start() is LabyrinthEntryRecognitionStartResult.Started)
        assertTrue(session.stop())
        assertTrue(captureStopCalls == 1)
    }

    @Test
    fun `stop without an active run still honours the capture flag`() = runTest {
        var captureStopCalls = 0
        val session = LabyrinthEntryRecognitionSession(
            sessionManager = AutomationSessionManager(),
            captureActive = { true },
            captureStop = { captureStopCalls++ },
            processorFactory = { { error("test does not dispatch frames") } },
        )

        assertFalse(session.stop(releaseCapture = false))
        assertTrue(captureStopCalls == 0)
        assertFalse(session.stop())
        assertTrue(captureStopCalls == 1)
    }

    @Test
    fun `run logic stops keep capture alive while a batch owns the run`() = runTest {
        val manager = AutomationSessionManager()
        var captureStopCalls = 0
        val events = mutableListOf<LabyrinthRunTerminalEvent>()
        val session = LabyrinthEntryRecognitionSession(
            sessionManager = manager,
            captureActive = { true },
            captureStop = { captureStopCalls++ },
            processorFactory = { { error("test does not dispatch frames") } },
            actionExecutor = SessionBoundActionExecutor(manager) { AutomationBackendResult.Completed },
            actionsAvailable = { true },
            runTerminalListener = { events += it },
        )

        // Batch-owned: a planner/terminal-page stop must not touch capture.
        assertTrue(session.startAutomation(accountId = 1L, runId = "b1-run001") is LabyrinthEntryRecognitionStartResult.Started)
        assertTrue(session.stopFromRunLogic("已完成最终结算并返回黎明界主页"))
        awaitEvent(events)
        assertTrue(captureStopCalls == 0)
        assertTrue(manager.current() == null)
        assertTrue(events.single().runId == "b1-run001")

        // Interactive: the same stop releases capture as before.
        events.clear()
        assertTrue(session.startAutomation(accountId = 1L) is LabyrinthEntryRecognitionStartResult.Started)
        assertTrue(session.stopFromRunLogic("点击被拒绝：test"))
        settle()
        assertTrue(captureStopCalls == 1)
        assertTrue(events.isEmpty())
    }

    @Test
    fun `batch run reports a formal terminal event and an interactive run reports none`() = runTest {
        val manager = AutomationSessionManager()
        val events = mutableListOf<LabyrinthRunTerminalEvent>()
        val session = LabyrinthEntryRecognitionSession(
            sessionManager = manager,
            captureActive = { true },
            processorFactory = { { error("test does not dispatch frames") } },
            actionExecutor = SessionBoundActionExecutor(manager) { AutomationBackendResult.Completed },
            actionsAvailable = { true },
            runTerminalListener = { events += it },
        )

        // Batch-owned run stopped by the user (capture released, no explicit outcome).
        assertTrue(session.startAutomation(accountId = 1L, runId = "b1-run001") is LabyrinthEntryRecognitionStartResult.Started)
        assertTrue(session.stop("用户停止"))
        awaitEvent(events)
        assertTrue(events.single() is LabyrinthRunTerminalEvent.UserStopped)
        assertTrue(events.single().runId == "b1-run001")

        // Batch-owned run stopped for the reroll handoff: capture retained, no explicit outcome
        // set by the test path, so it is an unexplained stop the batch must not count.
        events.clear()
        assertTrue(session.startAutomation(accountId = 1L, runId = "b1-run002") is LabyrinthEntryRecognitionStartResult.Started)
        assertTrue(session.stop("环境异常", releaseCapture = false))
        awaitEvent(events)
        assertTrue(events.single() is LabyrinthRunTerminalEvent.FatalUnknown)

        // Interactive run: no run id, no event.
        events.clear()
        assertTrue(session.startAutomation(accountId = 1L) is LabyrinthEntryRecognitionStartResult.Started)
        assertTrue(session.stop())
        settle()
        assertTrue(events.isEmpty())
    }

    @Test
    fun `blocks before loading templates when capture is unavailable`() = runTest {
        var factoryCalled = false
        val session = LabyrinthEntryRecognitionSession(
            sessionManager = AutomationSessionManager(),
            captureActive = { false },
            processorFactory = {
                factoryCalled = true
                error("must not load")
            },
        )

        val result = session.start()

        assertTrue(result is LabyrinthEntryRecognitionStartResult.Blocked)
        assertFalse(factoryCalled)
        assertTrue(session.state.value.status == LabyrinthEntryRecognitionStatus.ERROR)
    }

    @Test
    fun `live mode blocks before templates when accessibility is unavailable`() = runTest {
        var factoryCalled = false
        val manager = AutomationSessionManager()
        val session = LabyrinthEntryRecognitionSession(
            sessionManager = manager,
            captureActive = { true },
            processorFactory = {
                factoryCalled = true
                error("must not load")
            },
            actionExecutor = SessionBoundActionExecutor(manager) { AutomationBackendResult.Completed },
            actionsAvailable = { false },
        )

        val result = session.startAutomation()

        assertTrue(result is LabyrinthEntryRecognitionStartResult.Blocked)
        assertFalse(factoryCalled)
        assertTrue(manager.current() == null)
    }

    @Test
    fun `live mode checks accessibility before capture`() = runTest {
        var captureChecked = false
        val manager = AutomationSessionManager()
        val session = LabyrinthEntryRecognitionSession(
            sessionManager = manager,
            captureActive = {
                captureChecked = true
                false
            },
            processorFactory = { error("must not load") },
            actionExecutor = SessionBoundActionExecutor(manager) { AutomationBackendResult.Completed },
            actionsAvailable = { false },
        )

        val result = session.startAutomation()

        assertTrue(result is LabyrinthEntryRecognitionStartResult.Blocked)
        assertFalse(captureChecked)
        assertTrue(session.state.value.message?.contains("无障碍服务未连接") == true)
    }

    @Test
    fun `live mode owns a non dry run session and launches the game`() = runTest {
        var launchCalls = 0
        val manager = AutomationSessionManager()
        val session = LabyrinthEntryRecognitionSession(
            sessionManager = manager,
            captureActive = { true },
            processorFactory = { { error("test does not dispatch frames") } },
            actionExecutor = SessionBoundActionExecutor(manager) { AutomationBackendResult.Completed },
            actionsAvailable = { true },
            gameLauncher = { launchCalls++; true },
        )

        val result = session.startAutomation()

        assertTrue(result is LabyrinthEntryRecognitionStartResult.Started)
        assertFalse(session.state.value.dryRun)
        assertFalse(manager.current()!!.dryRun)
        assertTrue(launchCalls == 1)
        assertTrue(session.stop())
    }

    @Test
    fun `failed game launch releases capture and shared session`() = runTest {
        val manager = AutomationSessionManager()
        var captureStopCalls = 0
        val session = LabyrinthEntryRecognitionSession(
            sessionManager = manager,
            captureActive = { true },
            captureStop = { captureStopCalls++ },
            processorFactory = { { error("test does not dispatch frames") } },
            actionExecutor = SessionBoundActionExecutor(manager) { AutomationBackendResult.Completed },
            actionsAvailable = { true },
            gameLauncher = { false },
        )

        val result = session.startAutomation()

        assertTrue(result is LabyrinthEntryRecognitionStartResult.Blocked)
        assertTrue(manager.current() == null)
        assertTrue(CaptureFrameBus.currentOwner() == null)
        assertTrue(captureStopCalls == 1)
    }

    /**
     * Terminal events are delivered from the session's own Dispatchers.Default scope, so runTest's
     * virtual-time delay proves nothing about them; wait on the wall clock instead.
     */
    private suspend fun awaitEvent(events: List<LabyrinthRunTerminalEvent>) =
        withContext(Dispatchers.Default) {
            withTimeout(5_000L) { while (events.isEmpty()) delay(10L) }
        }

    private suspend fun settle() = withContext(Dispatchers.Default) { delay(200L) }
}
