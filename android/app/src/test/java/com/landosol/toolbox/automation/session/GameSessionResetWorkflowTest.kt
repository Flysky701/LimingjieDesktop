package com.landosol.toolbox.automation.session

import com.landosol.toolbox.automation.AutomationMode
import com.landosol.toolbox.automation.AutomationSessionId
import com.landosol.toolbox.automation.AutomationSessionManager
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
    fun `planner failure reason is preserved through reset workflow and auto run failure`() = runTest {
        val manager = AutomationSessionManager()
        val now = AtomicLong(0L)
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
            clock = now::get,
            entryActionPlannerFactory = { planner },
        )
        val handleObservation = GameSessionResetWorkflow::class.java.declaredMethods
            .single { it.name.startsWith("handleObservation") }
            .apply { isAccessible = true }
        val inFlightField = GameSessionResetWorkflow::class.java.getDeclaredField("inFlight")
            .apply { isAccessible = true }

        fun invokeObservation(sessionId: AutomationSessionId, result: LabyrinthEntryFrameResult) {
            val firstParam = handleObservation.parameterTypes[0]
            val idArg: Any = if (firstParam.name == "long") {
                AutomationSessionId::class.java.declaredFields.first { !java.lang.reflect.Modifier.isStatic(it.modifiers) }
                    .apply { isAccessible = true }.get(sessionId)
            } else {
                sessionId
            }
            handleObservation.invoke(session, idArg, result, SessionBlockKind.NONE, 1920, 1080, planner)
        }

        fun frame(state: LabyrinthEntryPageState) = LabyrinthEntryFrameResult(
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

        try {
            val started = session.start(clientHardClosed = true) as GameSessionResetStartResult.Started
            withContext(Dispatchers.Default.limitedParallelism(1)) {
                withTimeout(2_000L) {
                    while (session.state.value.stage != GameSessionResetStage.WAITING_LOGIN) delay(10L)
                }
                val inFlight = inFlightField.get(session) as java.util.concurrent.atomic.AtomicBoolean
                now.set(10L)
                invokeObservation(started.sessionId, frame(LabyrinthEntryPageState.TITLE_WAITING_TAP))
                withTimeout(2_000L) { while (inFlight.get()) delay(5L) }
                now.set(50L)
                invokeObservation(started.sessionId, frame(LabyrinthEntryPageState.GAME_LOADING_PROGRESS))
                withTimeout(2_000L) { while (inFlight.get()) delay(5L) }
                now.set(300L)
                invokeObservation(started.sessionId, frame(LabyrinthEntryPageState.GAME_LOADING_PROGRESS))
                withTimeout(2_000L) {
                    while (session.state.value.running) delay(10L)
                }
            }

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
}
