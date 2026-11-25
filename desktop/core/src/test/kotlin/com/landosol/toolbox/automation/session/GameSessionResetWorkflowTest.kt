package com.landosol.toolbox.automation.session

import com.landosol.toolbox.automation.AutomationBackendResult
import com.landosol.toolbox.automation.AutomationMode
import com.landosol.toolbox.automation.AutomationSessionId
import com.landosol.toolbox.automation.AutomationSessionManager
import com.landosol.toolbox.automation.SessionBoundActionExecutor
import com.landosol.toolbox.labyrinth.LabyrinthAutoRunConfig
import com.landosol.toolbox.labyrinth.LabyrinthAutoRunRoundOutcome
import com.landosol.toolbox.labyrinth.LabyrinthAutoRunResult
import com.landosol.toolbox.labyrinth.LabyrinthAutoRunWorkflow
import com.landosol.toolbox.labyrinth.LabyrinthEntryActionPlanner
import com.landosol.toolbox.labyrinth.LabyrinthEntryActionPlannerConfig
import com.landosol.toolbox.labyrinth.vision.EntryAnchorId
import com.landosol.toolbox.labyrinth.vision.LabyrinthAnchorScores
import com.landosol.toolbox.labyrinth.vision.LabyrinthEntryFrameResult
import com.landosol.toolbox.labyrinth.vision.LabyrinthEntryPageObservation
import com.landosol.toolbox.labyrinth.vision.LabyrinthEntryPageState
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GameSessionResetWorkflowTest {
    private class FakeBackend(
        private val supported: Boolean = true,
        override val kind: ClientTerminationKind = ClientTerminationKind.FORCE_STOP,
    ) : GameSessionResetBackend {
        var terminateCalls = 0
        var relaunchCalls = 0
        var terminateResult = ClientTerminationResult.TERMINATED
        var relaunchResult = GameClientRelaunchResult.LAUNCH_REQUESTED

        override suspend fun terminateClient(): ClientTerminationResult {
            terminateCalls++
            return terminateResult
        }

        override suspend fun relaunchClient(): GameClientRelaunchResult {
            relaunchCalls++
            return relaunchResult
        }

        override fun isSupported(): Boolean = supported
    }

    private fun workflow(
        manager: AutomationSessionManager = AutomationSessionManager(),
        backend: FakeBackend = FakeBackend(),
        captureActive: () -> Boolean = { true },
        onNextRoundReady: suspend (AutomationSessionId) -> Unit = {},
    ) = GameSessionResetWorkflow(
        sessionManager = manager,
        backend = backend,
        captureActive = captureActive,
        processorFactory = { { error("test does not dispatch frames") } },
        blockClassifier = { _: LabyrinthEntryFrameResult -> SessionBlockKind.NONE },
        onNextRoundReady = onNextRoundReady,
        presence = FixedGameClientPresenceObserver(foreground = true),
    )

    private fun invokeObservation(
        session: GameSessionResetWorkflow,
        sessionId: AutomationSessionId,
        result: LabyrinthEntryFrameResult,
        planner: LabyrinthEntryActionPlanner,
        block: SessionBlockKind = SessionBlockKind.NONE,
    ) {
        // Feed observations without a bitmap, recording the classification as onFrame does.
        @Suppress("UNCHECKED_CAST")
        val state = GameSessionResetWorkflow::class.java.getDeclaredField("_state")
            .apply { isAccessible = true }
            .get(session) as MutableStateFlow<GameSessionResetState>
        state.value = state.value.copy(lastBlock = block)
        val handleObservation = GameSessionResetWorkflow::class.java.declaredMethods
            .single { it.name.startsWith("handleObservation") }
            .apply { isAccessible = true }
        val idArg: Any = if (handleObservation.parameterTypes[0].name == "long") sessionId.value else sessionId
        handleObservation.invoke(session, idArg, result, block, 1920, 1080, planner)
    }

    private fun frame(state: LabyrinthEntryPageState) = LabyrinthEntryFrameResult(
        observation = LabyrinthEntryPageObservation(
            state = state,
            confidence = 0.95,
            stateScores = mapOf(state to 0.95),
            anchorScores = LabyrinthAnchorScores(EntryAnchorId.required.associateWith { 0.0 }),
            reason = null,
        ),
        matchedFeatures = emptyList(),
        elapsedMillis = 1L,
        frameWidth = 1920,
        frameHeight = 1080,
    )

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun TestScope.expiryWorkflow(
        backend: FakeBackend,
        planner: LabyrinthEntryActionPlanner,
        presence: GameClientPresenceObserver = FixedGameClientPresenceObserver(foreground = true),
        onBlocked: suspend (AutomationSessionId, SessionBlockKind) -> Unit = { _, _ -> },
    ): GameSessionResetWorkflow {
        val manager = AutomationSessionManager()
        return GameSessionResetWorkflow(
            sessionManager = manager,
            backend = backend,
            captureActive = { true },
            processorFactory = { { error("unused") } },
            blockClassifier = { error("unused") },
            presence = presence,
            clock = { testScheduler.currentTime },
            actionScope = this,
            actionExecutor = SessionBoundActionExecutor(manager) { AutomationBackendResult.Completed },
            entryActionPlannerFactory = { planner },
            onBlocked = onBlocked,
            debugLog = {},
        )
    }

    @Test
    fun `start owns session mode session-reset and stop releases everything`() = runTest {
        val manager = AutomationSessionManager()
        val session = workflow(manager = manager)

        val result = session.start()

        assertTrue(result is GameSessionResetStartResult.Started)
        assertTrue(session.state.value.running)
        assertEquals(AutomationMode.SESSION_RESET, manager.current()?.mode)

        assertTrue(session.stop())
        assertFalse(session.state.value.running)
        assertNull(manager.current())
        assertEquals(GameSessionResetStatus.STOPPED, session.state.value.status)
    }

    @Test
    fun `blocks when capture is unavailable`() = runTest {
        val manager = AutomationSessionManager()
        val session = workflow(manager = manager, captureActive = { false })

        val result = session.start()

        assertTrue(result is GameSessionResetStartResult.Blocked)
        assertNull(manager.current())
        assertEquals(GameSessionResetStatus.ERROR, session.state.value.status)
    }

    @Test
    fun `blocks when no termination backend is supported`() = runTest {
        val manager = AutomationSessionManager()
        val session = workflow(manager = manager, backend = FakeBackend(supported = false))

        val result = session.start()

        assertTrue(result is GameSessionResetStartResult.Blocked)
        assertNull(manager.current())
    }

    @Test
    fun `blocks when another automation session already runs`() = runTest {
        val manager = AutomationSessionManager()
        manager.start(AutomationMode.LABYRINTH, dryRun = true)
        val session = workflow(manager = manager)

        val result = session.start()

        assertTrue(result is GameSessionResetStartResult.Blocked)
        assertEquals(AutomationMode.LABYRINTH, manager.current()?.mode)
    }

    @Test
    fun `runOnceAfterRound saves result and returns true`() = runTest {
        val manager = AutomationSessionManager()
        val saves = AtomicInteger(0)
        val session = workflow(
            manager = manager,
            onNextRoundReady = {},
        )

        try {
            val started = session.runOnceAfterRound(saveResult = { saves.incrementAndGet() })

            assertTrue(started)
            // The workflow does this work on real dispatchers, but runTest's delay/withTimeout
            // run on virtual time and complete instantly. Waiting in virtual time therefore
            // burned the whole 2 s budget before the real callback could land, and the test
            // failed whenever the machine was busy — which is exactly when a full suite runs.
            withContext(Dispatchers.Default.limitedParallelism(1)) {
                withTimeout(2_000L) {
                    while (saves.get() == 0) delay(10L)
                }
                assertEquals(1, saves.get())
                // A real pause, so a duplicate callback has an actual chance to arrive.
                delay(50L)
            }
            assertEquals("保存回调不能重复执行", 1, saves.get())
            assertTrue(session.state.value.running)
        } finally {
            session.stop()
        }
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `pending expiry popup with no foreground still terminates without waiting for presence`() = runTest {
        val backend = FakeBackend(kind = ClientTerminationKind.SESSION_EXPIRY)
        val planner = LabyrinthEntryActionPlanner()
        var foreground = false
        val session = expiryWorkflow(
            backend, planner,
            presence = object : GameClientPresenceObserver {
                override suspend fun isGameForeground(): Boolean = foreground
            },
        )

        try {
            val started = session.start(clientHardClosed = false) as GameSessionResetStartResult.Started
            testScheduler.runCurrent()
            assertEquals(GameSessionResetStage.PENDING, session.state.value.stage)

            invokeObservation(
                session, started.sessionId, frame(LabyrinthEntryPageState.UNKNOWN), planner,
                SessionBlockKind.RECONNECT_PROMPTED,
            )
            testScheduler.runCurrent()
            assertEquals(GameSessionResetStage.PENDING, session.state.value.stage)

            testScheduler.advanceTimeBy(500L)
            assertEquals(1, backend.terminateCalls)
            assertEquals(0, backend.relaunchCalls)
            assertEquals(GameSessionResetStage.WAITING_LOGIN, session.state.value.stage)

            foreground = true
            invokeObservation(session, started.sessionId, frame(LabyrinthEntryPageState.DAWN_REALM_HOME_IDLE), planner)
            testScheduler.advanceUntilIdle()
            assertEquals(GameSessionResetStage.READY_FOR_NEXT_ROUND, session.state.value.stage)
            assertEquals(GameSessionResetStatus.STOPPED, session.state.value.status)
        } finally {
            session.stop()
        }
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `expiry popup after skipping termination returns to title and resumes login`() = runTest {
        val backend = FakeBackend(kind = ClientTerminationKind.SESSION_EXPIRY)
        val planner = LabyrinthEntryActionPlanner(
            LabyrinthEntryActionPlannerConfig(stableFrames = 1, maxPageActionAttempts = 1),
        )
        val session = expiryWorkflow(backend, planner)
        val title = frame(LabyrinthEntryPageState.TITLE_WAITING_TAP)

        try {
            val started = session.start() as GameSessionResetStartResult.Started
            invokeObservation(session, started.sessionId, title, planner)
            testScheduler.advanceTimeBy(500L)
            assertEquals(GameSessionResetStage.WAITING_LOGIN, session.state.value.stage)
            assertEquals(0, backend.terminateCalls)

            invokeObservation(session, started.sessionId, title, planner)
            testScheduler.runCurrent()
            assertEquals(1, session.state.value.actionCount)
            invokeObservation(
                session, started.sessionId, title, planner,
                SessionBlockKind.RECONNECT_PROMPTED,
            )
            testScheduler.advanceTimeBy(500L)
            assertEquals(1, backend.terminateCalls)
            assertEquals(0, backend.relaunchCalls)
            assertEquals(GameSessionResetStage.WAITING_LOGIN, session.state.value.stage)
            assertEquals(1, session.state.value.actionCount)

            invokeObservation(session, started.sessionId, title, planner)
            testScheduler.runCurrent()
            assertEquals(2, session.state.value.actionCount)
            invokeObservation(session, started.sessionId, frame(LabyrinthEntryPageState.DAWN_REALM_HOME_IDLE), planner)
            testScheduler.advanceUntilIdle()
            assertEquals(GameSessionResetStage.READY_FOR_NEXT_ROUND, session.state.value.stage)
            assertEquals(GameSessionResetStatus.STOPPED, session.state.value.status)
        } finally {
            session.stop()
        }
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `third expiry popup after two recovery attempts blocks the reset`() = runTest {
        val backend = FakeBackend(kind = ClientTerminationKind.SESSION_EXPIRY)
        val planner = LabyrinthEntryActionPlanner()
        val blocks = mutableListOf<SessionBlockKind>()
        val session = expiryWorkflow(backend, planner, onBlocked = { _, block -> blocks.add(block) })
        val reconnect = SessionBlockKind.RECONNECT_PROMPTED

        try {
            val started = session.start() as GameSessionResetStartResult.Started
            invokeObservation(session, started.sessionId, frame(LabyrinthEntryPageState.TITLE_WAITING_TAP), planner)
            testScheduler.advanceTimeBy(500L)
            assertEquals(GameSessionResetStage.WAITING_LOGIN, session.state.value.stage)
            assertEquals(0, backend.terminateCalls)

            repeat(2) { attempt ->
                invokeObservation(session, started.sessionId, frame(LabyrinthEntryPageState.UNKNOWN), planner, reconnect)
                testScheduler.advanceTimeBy(500L)
                assertEquals(attempt + 1, backend.terminateCalls)
                assertEquals(GameSessionResetStage.WAITING_LOGIN, session.state.value.stage)
                invokeObservation(session, started.sessionId, frame(LabyrinthEntryPageState.HOME), planner)
                testScheduler.runCurrent()
                assertEquals(GameSessionResetStage.NAVIGATING_HOME, session.state.value.stage)
            }

            invokeObservation(session, started.sessionId, frame(LabyrinthEntryPageState.UNKNOWN), planner, reconnect)
            testScheduler.runCurrent()
            assertEquals(GameSessionResetStage.SESSION_BLOCKED, session.state.value.stage)
            testScheduler.advanceTimeBy(500L)
            assertEquals(GameSessionResetStatus.ERROR, session.state.value.status)
            assertEquals(listOf(reconnect), blocks)
            assertEquals(2, backend.terminateCalls)
            assertEquals(0, backend.relaunchCalls)
        } finally {
            session.stop()
        }
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `expiry popup after a reported hard close blocks without terminating`() = runTest {
        val backend = FakeBackend(kind = ClientTerminationKind.SESSION_EXPIRY)
        val planner = LabyrinthEntryActionPlanner()
        val session = expiryWorkflow(backend, planner)
        val reconnect = SessionBlockKind.RECONNECT_PROMPTED

        try {
            val started = session.start(clientHardClosed = true) as GameSessionResetStartResult.Started
            testScheduler.advanceTimeBy(1L)
            assertEquals(GameSessionResetStage.WAITING_LOGIN, session.state.value.stage)

            invokeObservation(session, started.sessionId, frame(LabyrinthEntryPageState.UNKNOWN), planner, reconnect)
            testScheduler.runCurrent()
            assertEquals(GameSessionResetStage.SESSION_BLOCKED, session.state.value.stage)
            testScheduler.advanceTimeBy(500L)
            assertEquals(GameSessionResetStatus.ERROR, session.state.value.status)
            assertEquals(reconnect, session.state.value.lastBlock)
            assertEquals(0, backend.terminateCalls)
            assertEquals(1, backend.relaunchCalls)
        } finally {
            session.stop()
        }
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `pending relogin block fails promptly without terminating or relaunching`() = runTest {
        val backend = FakeBackend(kind = ClientTerminationKind.SESSION_EXPIRY)
        val planner = LabyrinthEntryActionPlanner()
        val session = expiryWorkflow(
            backend, planner,
            presence = FixedGameClientPresenceObserver(foreground = false),
        )
        val relogin = SessionBlockKind.RELOGIN_REQUIRED

        try {
            val started = session.start() as GameSessionResetStartResult.Started
            testScheduler.runCurrent()
            assertEquals(GameSessionResetStage.PENDING, session.state.value.stage)

            invokeObservation(session, started.sessionId, frame(LabyrinthEntryPageState.UNKNOWN), planner, relogin)
            testScheduler.runCurrent()
            assertEquals(GameSessionResetStage.SESSION_BLOCKED, session.state.value.stage)
            testScheduler.advanceTimeBy(500L)

            assertEquals(GameSessionResetStatus.ERROR, session.state.value.status)
            assertEquals(GameSessionResetStage.FAILED, session.state.value.stage)
            assertTrue(session.state.value.message.orEmpty().contains("会话被阻塞：RELOGIN_REQUIRED"))
            assertEquals(0, backend.terminateCalls)
            assertEquals(0, backend.relaunchCalls)
        } finally {
            session.stop()
        }
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `planner failure reason is preserved through reset workflow and auto run failure`() = runTest {
        val manager = AutomationSessionManager()
        val planner = LabyrinthEntryActionPlanner(
            LabyrinthEntryActionPlannerConfig(
                stableFrames = 1,
                preAnnouncementTimeoutMillis = 100L,
                preAnnouncementLoadingTimeoutMillis = 200L,
                totalTimeoutMillis = 120_000L,
            ),
        )
        val session = GameSessionResetWorkflow(
            sessionManager = manager,
            backend = FakeBackend(),
            captureActive = { true },
            processorFactory = { { error("unused") } },
            blockClassifier = { SessionBlockKind.NONE },
            presence = FixedGameClientPresenceObserver(foreground = true),
            clock = { testScheduler.currentTime },
            actionScope = this,
            actionExecutor = SessionBoundActionExecutor(manager) { AutomationBackendResult.Completed },
            debugLog = {},
            entryActionPlannerFactory = { planner },
        )
        try {
            val started = session.start(clientHardClosed = true) as GameSessionResetStartResult.Started
            testScheduler.runCurrent()
            assertEquals(GameSessionResetStage.WAITING_LOGIN, session.state.value.stage)
            invokeObservation(session, started.sessionId, frame(LabyrinthEntryPageState.TITLE_WAITING_TAP), planner)
            testScheduler.runCurrent()
            assertEquals(1, session.state.value.actionCount)
            invokeObservation(session, started.sessionId, frame(LabyrinthEntryPageState.GAME_LOADING_PROGRESS), planner)
            testScheduler.runCurrent()
            testScheduler.advanceTimeBy(201L) // Cross the planner's 200 ms loading deadline.
            invokeObservation(session, started.sessionId, frame(LabyrinthEntryPageState.GAME_LOADING_PROGRESS), planner)
            testScheduler.advanceUntilIdle()

            assertEquals(GameSessionResetStatus.ERROR, session.state.value.status)
            assertEquals(GameSessionResetStage.FAILED, session.state.value.stage)
            val resetMessage = session.state.value.message.orEmpty()
            assertTrue("unexpected message: $resetMessage", resetMessage.contains("启动加载页面（GAME_LOADING_PROGRESS）等待超时"))
            assertFalse("generic double-prefix regressed: $resetMessage", resetMessage.contains("会话重置失败：会话重置失败"))

            val autoRun = LabyrinthAutoRunWorkflow(
                startRun = { true },
                awaitRunFinished = { LabyrinthAutoRunRoundOutcome.Completed },
                resetSession = { false },
                resetFailureReason = { session.state.value.message },
            )
            val failure = autoRun.run(LabyrinthAutoRunConfig(accountId = 1L, targetRuns = 2)) as LabyrinthAutoRunResult.Failure
            assertEquals(resetMessage, failure.message)
        } finally {
            session.stop()
        }
    }

    @Test
    fun `pause blocks terminate and relaunch, preserves paused against frames, and stop cancels active flow`() = runBlocking {
        val manager = AutomationSessionManager()
        val backend = FakeBackend(kind = ClientTerminationKind.FORCE_STOP)
        val clock = AtomicLong(1_000L)
        val saveEntered = CompletableDeferred<Unit>()
        val releaseSave = CompletableDeferred<Unit>()
        val readyCalls = AtomicInteger(0)
        val session = GameSessionResetWorkflow(
            sessionManager = manager,
            backend = backend,
            captureActive = { true },
            processorFactory = { { error("unused") } },
            blockClassifier = { SessionBlockKind.NONE },
            presence = FixedGameClientPresenceObserver(foreground = true),
            clock = clock::get,
            totalTimeoutMillis = 5_000L,
            onNextRoundReady = { readyCalls.incrementAndGet() },
            debugLog = {},
        )
        val handleObservation = GameSessionResetWorkflow::class.java.declaredMethods
            .single { it.name.startsWith("handleObservation") }
            .apply { isAccessible = true }
        val planner = LabyrinthEntryActionPlanner()
        fun idleFrame() = LabyrinthEntryFrameResult(
            observation = LabyrinthEntryPageObservation(
                state = LabyrinthEntryPageState.DAWN_REALM_HOME_IDLE,
                confidence = 0.95,
                stateScores = mapOf(LabyrinthEntryPageState.DAWN_REALM_HOME_IDLE to 0.95),
                anchorScores = LabyrinthAnchorScores(EntryAnchorId.required.associateWith { 0.0 }),
                reason = null,
            ),
            matchedFeatures = emptyList(),
            elapsedMillis = 1L,
            frameWidth = 1920,
            frameHeight = 1080,
        )

        try {
            assertTrue(session.runOnceAfterRound(saveResult = {
                saveEntered.complete(Unit)
                releaseSave.await()
            }))
            withTimeout(2_000L) { saveEntered.await() }
            assertTrue(session.setPaused(true))
            releaseSave.complete(Unit)
            // Advance clock far past totalTimeoutMillis while paused; flow must stay paused without advancing or timing out.
            clock.addAndGet(30_000L)
            delay(400L)

            assertTrue(session.state.value.running)
            assertTrue(session.state.value.paused)
            assertEquals(0, backend.terminateCalls)
            assertEquals(0, backend.relaunchCalls)
            assertEquals(0, readyCalls.get())

            // Simulate a late frame observation while paused; paused must not be overwritten or reach READY.
            val sessionId = requireNotNull(session.state.value.sessionId)
            val idArg: Any = if (handleObservation.parameterTypes[0].name == "long") {
                sessionId.value
            } else {
                sessionId
            }
            handleObservation.invoke(session, idArg, idleFrame(), SessionBlockKind.NONE, 1920, 1080, planner)
            delay(100L)
            assertTrue(session.state.value.paused)
            assertEquals(0, readyCalls.get())
            assertFalse(session.state.value.stage == GameSessionResetStage.READY_FOR_NEXT_ROUND)

            assertTrue(session.stop("用户停止"))
            assertFalse(session.state.value.running)
            assertFalse(session.state.value.paused)
            assertEquals(GameSessionResetStatus.STOPPED, session.state.value.status)
            assertEquals(0, backend.terminateCalls)
            assertEquals(0, backend.relaunchCalls)
        } finally {
            releaseSave.complete(Unit)
            session.stop()
        }
    }
}
