package com.landosol.toolbox.labyrinth

import com.landosol.toolbox.automation.*
import com.landosol.toolbox.labyrinth.concurrent.LabyrinthFourThreadRuntime
import com.landosol.toolbox.labyrinth.kernel.KernelEventKinds
import com.landosol.toolbox.labyrinth.kernel.SessionLifecyclePayload
import com.landosol.toolbox.labyrinth.vision.*
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import com.landosol.toolbox.labyrinth.concurrent.WorkToken
import kotlin.coroutines.*
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test

/** Uses real processFrameResult / RoleRound / executor; never constructs an Android Bitmap. */
class Batch5SessionAdmissionTest {
    private class Fixture {
        val manager = AutomationSessionManager()
        val clock = AtomicLong(1000)
        val calls = AtomicInteger()
        val session = LabyrinthEntryRecognitionSession(
            sessionManager = manager,
            captureActive = { assertTrue(LabyrinthFourThreadRuntime.isControlThread()); true },
            processorFactory = { assertTrue(LabyrinthFourThreadRuntime.isControlThread()); { error("fixture has no bitmap") } },
            fastProcessorFactory = { assertTrue(LabyrinthFourThreadRuntime.isControlThread()); { error("fixture has no bitmap") } },
            actionExecutor = SessionBoundActionExecutor(manager) { calls.incrementAndGet(); AutomationBackendResult.Completed },
            actionsAvailable = { true }, clock = { clock.get() },
        )
        val id get() = requireNotNull(session.state.value.sessionId)
        fun field(name: String) = session.javaClass.getDeclaredField(name).apply { isAccessible = true }
        fun get(name: String): Any? = field(name).get(session)
        fun set(name: String, value: Any?) { field(name).set(session, value) }
        @Suppress("UNCHECKED_CAST") fun state(update: (LabyrinthEntryRecognitionSessionState) -> LabyrinthEntryRecognitionSessionState) {
            val flow = get("_state") as MutableStateFlow<LabyrinthEntryRecognitionSessionState>
            flow.value = update(flow.value)
        }
        fun adapt(types: Array<Class<*>>, args: Array<out Any?>) = types.mapIndexed { index, type ->
            val value = args[index]
            when (type.name) {
                "long" -> (value as? AutomationSessionId)?.value ?: (value as Number).toLong()
                "int" -> (value as Number).toInt()
                else -> value
            }
        }.toTypedArray()
        fun invoke(name: String, vararg args: Any?): Any? {
            val method = session.javaClass.declaredMethods.first { (it.name == name || it.name.startsWith("$name-")) && it.parameterCount == args.size }
            method.isAccessible = true
            return method.invoke(session, *adapt(method.parameterTypes, args))
        }
        suspend fun start() {
            assertTrue(session.startAutomation() is LabyrinthEntryRecognitionStartResult.Started)
            withContext(LabyrinthFourThreadRuntime.controlDispatcher) { set("entryPhaseComplete", true) }
        }
        suspend fun feed(result: LabyrinthEntryFrameResult, capturedAt: Long, handledAt: Long = capturedAt, sourceEpoch: Long? = null) =
            withContext(LabyrinthFourThreadRuntime.controlDispatcher) {
                clock.set(handledAt)
                set("balancedDetailCapturedAt", capturedAt)
                set("balancedDetailWidth", 1920); set("balancedDetailHeight", 1080)
                set("balancedDetailEpoch", sourceEpoch ?: get("balancedSceneEpoch"))
                suspendCoroutine<Unit> { continuation ->
                    val method = session.javaClass.declaredMethods.first { it.name.startsWith("processFrameResult") && it.parameterCount == 6 }
                    method.isAccessible = true
                    val args = arrayOf<Any?>(id, result, 1920, 1080, capturedAt, continuation)
                    try {
                        if (method.invoke(session, *adapt(method.parameterTypes, args)) !== COROUTINE_SUSPENDED) continuation.resume(Unit)
                    } catch (error: InvocationTargetException) { continuation.resumeWithException(error.targetException) }
                }
            }
        suspend fun choose(frame: LabyrinthEntryFrameResult) = withContext(LabyrinthFourThreadRuntime.controlDispatcher) {
            val chosen = requireNotNull(frame.characterMatches.first().characterId)
            val rect = requireNotNull(LabyrinthRoleRewardChoicePlanner.selectionButtonRect("role_reward_left", 1920, 1080))
            state { it.copy(lastResult = frame,
                roleRewardChoiceDecision = LabyrinthRoleRewardChoiceDecision.Select(chosen, chosen, rect, emptyList())) }
            invoke("dispatchPostEntryTap", id, LabyrinthPostEntryActionKind.SELECT_ROLE_REWARD, "选择$chosen", rect,
                clock.get(), labyrinthRoleRewardSelectionSignature(frame), chosen, null, null, null, false, emptyList<String>())
        }
        suspend fun awaitActions() = withTimeout(3000) {
            while ((get("actionInFlight") as AtomicBoolean).get()) delay(2)
        }
        fun round() = get("roleRewardRound") as LabyrinthRoleRewardRound
        fun roleFrame() = LabyrinthEntryFrameResult(
            observation = LabyrinthEntryPageObservation(LabyrinthEntryPageState.INITIAL_CHARACTER_SELECTION, 1.0, emptyMap(),
                LabyrinthAnchorScores(mapOf(EntryAnchorId.ROLE_REWARD_TITLE to 1.0, EntryAnchorId.ROLE_REWARD_INSTRUCTION to 1.0,
                    EntryAnchorId.ROLE_REWARD_SELECT_BUTTON to 1.0))),
            matchedFeatures = emptyList(), elapsedMillis = 0,
            characterMatches = listOf("role_reward_left", "role_reward_center", "role_reward_right").mapIndexed { index, slot ->
                LabyrinthCharacterMatch(slotId = slot, characterId = "r${index + 1}", displayName = "角色${index + 1}", confidence = .95,
                    screenRect = EntryPixelRect(260 + index * 555, 650, 290, 155), trusted = true, nameAssisted = true)
            }, frameWidth = 1920, frameHeight = 1080,
        )
    }

    @Test fun `stale input is rejected before RoleRound submit and fresh input keeps original ACK ownership`() = runBlocking {
        val fixture = Fixture()
        try {
            fixture.start()
            val frame = fixture.roleFrame()
            fixture.feed(frame, capturedAt = 1300, handledAt = 3000)
            fixture.choose(frame); fixture.awaitActions()
            assertEquals(0, fixture.calls.get())
            assertFalse(fixture.round().blocksPlanning())
            assertEquals("OPEN", fixture.round().snapshot().phase)
            assertTrue(fixture.session.state.value.running)
            fixture.feed(frame, capturedAt = 3300)
            fixture.choose(frame); fixture.awaitActions()
            assertEquals(1, fixture.calls.get())
            assertTrue(fixture.round().blocksPlanning())
            assertEquals("INPUT_FINISHED", fixture.round().snapshot().phase)
            fixture.choose(frame); fixture.awaitActions()
            assertEquals("same round does not rescore or redispatch", 1, fixture.calls.get())
        } finally { fixture.awaitActions(); fixture.session.stop("fixture done") }
    }

    @Test fun `queued role input suppressed before executor rolls back token without stopping`() = runBlocking {
        val fixture = Fixture()
        try {
            fixture.start()
            val frame = fixture.roleFrame()
            fixture.feed(frame, 1300)
            withContext(LabyrinthFourThreadRuntime.controlDispatcher) {
                fixture.choose(frame) // launch is queued behind this same deterministic T2 turn.
                assertEquals("DISPATCHED", fixture.round().snapshot().phase)
                (fixture.get("manualPauseRequested") as AtomicBoolean).set(true)
            }
            fixture.awaitActions()
            assertEquals(0, fixture.calls.get())
            assertEquals("RETRY_READY", fixture.round().snapshot().phase)
            assertFalse(fixture.round().blocksDispatch())
            assertEquals(0, fixture.get("postEntryAttempts"))
            assertTrue(fixture.session.state.value.running)
            assertTrue(fixture.session.setPaused(true))
        } finally { fixture.awaitActions(); fixture.session.stop("fixture done") }
    }

    @Test fun `queued Boss tab suppression drops receipt without charging failed ACK budget`() = runBlocking {
        val fixture = Fixture()
        try {
            fixture.start(); fixture.feed(fixture.roleFrame(), 1300)
            withContext(LabyrinthFourThreadRuntime.controlDispatcher) {
                fixture.set("bossEditorTeamIndex", 1)
                fixture.invoke("dispatchBattleTeamAction", fixture.id,
                    LabyrinthBattleTeamExecutionStep(LabyrinthBattleTeamExecutionKind.NEXT_BOSS_TEAM,
                        "next-team", "下一队", AutomationAction.Tap(ScreenPoint(300f, 200f))), 1300L)
                assertNotNull(fixture.get("pendingBattleTeamAction"))
                (fixture.get("manualPauseRequested") as AtomicBoolean).set(true)
            }
            fixture.awaitActions()
            assertEquals(0, fixture.calls.get())
            assertNull(fixture.get("pendingBattleTeamAction"))
            assertNull(fixture.get("pendingBossTeamAdvance"))
            assertEquals(0, fixture.get("battleTeamExecutionAttempts"))
            assertEquals(0, fixture.get("battleTeamActionUnconfirmedAttempts"))
            assertEquals(0, fixture.get("battleRetryCount"))
            assertTrue(fixture.session.state.value.running)
        } finally { fixture.awaitActions(); fixture.session.stop("fixture done") }
    }

    @Test fun `one false positive fast page preserves planning while two fresh captures commit migration`() = runBlocking {
        val fixture = Fixture()
        val entered = CountDownLatch(1); val exit = CountDownLatch(1)
        val token = AtomicReference<WorkToken>()
        try {
            fixture.start()
            val frame = fixture.roleFrame()
            val epoch = withContext(LabyrinthFourThreadRuntime.controlDispatcher) {
                fixture.invoke("observeBalancedFast", fixture.id, LabyrinthFastFrameObservation(frame.observation, 1, 1300, 1920, 1080, 1))
                @Suppress("UNCHECKED_CAST")
                val planning = fixture.get("freeRolePlanning") as LabyrinthBalancedPlanningSlot<List<Any?>, LabyrinthRoleRewardChoiceDecision>
                planning.request(listOf("stable-scene")) {
                    token.set(LabyrinthFourThreadRuntime.currentToken())
                    entered.countDown(); exit.await()
                    LabyrinthRoleRewardChoiceDecision.Wait("test root")
                }
                fixture.get("balancedSceneEpoch") as Long
            }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            fixture.feed(frame, 1300, sourceEpoch = epoch)
            withContext(LabyrinthFourThreadRuntime.controlDispatcher) {
                fun fast(page: LabyrinthEntryPageState, sequence: Long, at: Long) {
                    fixture.clock.set(at)
                    fixture.invoke("observeBalancedFast", fixture.id,
                        LabyrinthFastFrameObservation(frame.observation.copy(state = page), sequence, at, 1920, 1080, 1))
                }
                fast(LabyrinthEntryPageState.BATTLE_FAILED, 2, 1600)
                assertEquals(epoch, fixture.get("balancedSceneEpoch"))
                assertFalse(token.get().isCancelled)
                assertEquals(false, fixture.invoke("balancedFreshInput")) // Provisional page blocks only older detail.
                fast(frame.observation.state, 3, 1900)
                assertEquals(epoch, fixture.get("balancedSceneEpoch"))
                assertFalse(token.get().isCancelled)
                assertEquals(true, fixture.invoke("balancedFreshInput"))
                fast(LabyrinthEntryPageState.BATTLE_FAILED, 4, 2200)
                fast(LabyrinthEntryPageState.BATTLE_FAILED, 4, 2500) // Same sequence is not independent evidence.
                assertEquals(epoch, fixture.get("balancedSceneEpoch"))
                assertFalse(token.get().isCancelled)
                fast(LabyrinthEntryPageState.BATTLE_FAILED, 5, 2500)
                assertEquals(epoch + 1, fixture.get("balancedSceneEpoch"))
                assertTrue(token.get().isCancelled)
            }
        } finally { exit.countDown(); fixture.awaitActions(); fixture.session.stop("fixture done") }
    }

    @Test fun `cheap UNKNOWN does not suppress a fresh trusted full page`() = runBlocking {
        val fixture = Fixture()
        try {
            fixture.start()
            val frame = fixture.roleFrame()
            val epoch = withContext(LabyrinthFourThreadRuntime.controlDispatcher) {
                fixture.invoke("observeBalancedFast", fixture.id, LabyrinthFastFrameObservation(frame.observation, 1, 1300, 1920, 1080, 1))
                fixture.get("balancedSceneEpoch") as Long
            }
            fixture.feed(frame, 1300, sourceEpoch = epoch)
            withContext(LabyrinthFourThreadRuntime.controlDispatcher) {
                fixture.clock.set(1600)
                fixture.invoke("observeBalancedFast", fixture.id,
                    LabyrinthFastFrameObservation(frame.observation.copy(state = LabyrinthEntryPageState.UNKNOWN), 2, 1600, 1920, 1080, 1))
                assertEquals(epoch, fixture.get("balancedSceneEpoch"))
                assertEquals(true, fixture.invoke("balancedFreshInput"))
            }
        } finally { fixture.awaitActions(); fixture.session.stop("fixture done") }
    }

    @Test fun `new fast dimensions invalidate old detailed result even on UNKNOWN page`() = runBlocking {
        val fixture = Fixture()
        try {
            fixture.start()
            val frame = fixture.roleFrame()
            val sourceEpoch = withContext(LabyrinthFourThreadRuntime.controlDispatcher) {
                fixture.invoke("observeBalancedFast", fixture.id, LabyrinthFastFrameObservation(frame.observation, 1, 1300, 1920, 1080, 1))
                fixture.get("balancedSceneEpoch") as Long
            }
            fixture.feed(frame, 1300, sourceEpoch = sourceEpoch)
            val accepted = fixture.session.state.value.frameCount
            withContext(LabyrinthFourThreadRuntime.controlDispatcher) {
                val unknown = frame.observation.copy(state = LabyrinthEntryPageState.UNKNOWN)
                fixture.invoke("observeBalancedFast", fixture.id, LabyrinthFastFrameObservation(unknown, 2, 1600, 1280, 720, 1))
                assertEquals(sourceEpoch + 1, fixture.get("balancedSceneEpoch"))
                assertEquals(false, fixture.invoke("balancedFreshInput"))
            }
            fixture.feed(frame, 1700, sourceEpoch = sourceEpoch)
            assertEquals("old geometry never becomes a new detailed result", accepted, fixture.session.state.value.frameCount)
            assertEquals(frame, fixture.session.state.value.lastResult)
        } finally { fixture.awaitActions(); fixture.session.stop("fixture done") }
    }

    @Test fun `pause rejects detailed completion and lifecycle remains control-owned`() = runBlocking {
        val fixture = Fixture()
        try {
            fixture.start()
            fixture.feed(fixture.roleFrame(), 1300)
            val accepted = fixture.session.state.value.frameCount
            assertTrue(fixture.session.setPaused(true))
            fixture.feed(fixture.roleFrame(), 1600)
            assertEquals(accepted, fixture.session.state.value.frameCount)
            assertTrue(fixture.session.state.value.paused)
            fixture.choose(fixture.roleFrame()); fixture.awaitActions()
            assertEquals(0, fixture.calls.get())
            assertFalse(fixture.round().blocksPlanning())
            assertTrue(fixture.session.state.value.balancedDiagnostics!!.controlThread)
        } finally { fixture.awaitActions(); fixture.session.stop("fixture done") }
    }

    @Test fun `setPaused cancels background planning, emits kernel lifecycle, and suppresses finishFromPlanner`() = runBlocking {
        val fixture = Fixture()
        val entered = CountDownLatch(1)
        val exit = CountDownLatch(1)
        val token = AtomicReference<WorkToken>()
        try {
            fixture.start()
            withContext(LabyrinthFourThreadRuntime.controlDispatcher) {
                @Suppress("UNCHECKED_CAST")
                val planning = fixture.get("battlePlanning") as LabyrinthBalancedPlanningSlot<List<Any?>, LabyrinthBalancedBattlePlanningOutput>
                planning.request(listOf("stable-battle-scene")) {
                    token.set(LabyrinthFourThreadRuntime.currentToken())
                    entered.countDown()
                    exit.await()
                    LabyrinthBalancedBattlePlanningOutput(LabyrinthBattleTeamRecommendationResult.Unavailable("test"), false)
                }
            }
            assertTrue(entered.await(2, TimeUnit.SECONDS))

            assertTrue(fixture.session.setPaused(true))
            assertTrue("pause must cancel active background planning", token.get().isCancelled)
            val pauseEvent = fixture.session.kernelRuntime.log.filter(kind = KernelEventKinds.SESSION_LIFECYCLE).last()
            assertEquals("pause", (pauseEvent.payload as SessionLifecyclePayload).phase)

            withContext(LabyrinthFourThreadRuntime.controlDispatcher) {
                fixture.invoke("finishFromPlanner", fixture.id, "pause should suppress stop", true)
            }
            delay(100)
            assertTrue(fixture.session.state.value.running)
            assertTrue(fixture.session.state.value.paused)

            assertTrue(fixture.session.setPaused(false))
            val resumeEvent = fixture.session.kernelRuntime.log.filter(kind = KernelEventKinds.SESSION_LIFECYCLE).last()
            assertEquals("resume", (resumeEvent.payload as SessionLifecyclePayload).phase)
        } finally {
            exit.countDown()
            fixture.awaitActions()
            fixture.session.stop("fixture done")
        }
    }

    @Test fun `balanced pipeline foreground gate skips vision and publishes waiting message when backgrounded`() = runBlocking {
        val manager = AutomationSessionManager()
        val fastCalls = AtomicInteger()
        val detailCalls = AtomicInteger()
        val foreground = AtomicBoolean(false)
        val session = LabyrinthEntryRecognitionSession(
            sessionManager = manager,
            captureActive = { true },
            processorFactory = { { detailCalls.incrementAndGet(); error("must not run detail processor") } },
            fastProcessorFactory = { { fastCalls.incrementAndGet(); error("must not run fast processor") } },
            actionExecutor = SessionBoundActionExecutor(manager) { AutomationBackendResult.Completed },
            actionTargetReady = { foreground.get() },
            actionsAvailable = { true },
        )
        try {
            assertTrue(session.startAutomation() is LabyrinthEntryRecognitionStartResult.Started)
            val pipeline = session.javaClass.getDeclaredField("balancedPipeline").apply { isAccessible = true }.get(session)!!
            @Suppress("UNCHECKED_CAST")
            val foregroundReady = pipeline.javaClass.getDeclaredField("foregroundReady").apply { isAccessible = true }.get(pipeline) as () -> Boolean
            @Suppress("UNCHECKED_CAST")
            val onNotForeground = pipeline.javaClass.getDeclaredField("onNotForeground").apply { isAccessible = true }.get(pipeline) as () -> Unit

            assertFalse(foregroundReady())
            onNotForeground()
            assertEquals("游戏不在前台，保持会话并等待返回", session.state.value.message)
            assertEquals(0, fastCalls.get())
            assertEquals(0, detailCalls.get())
            foreground.set(true)
            assertTrue(foregroundReady())
        } finally {
            session.stop("fixture done")
        }
    }
}
