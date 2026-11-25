package com.landosol.toolbox.labyrinth

import com.landosol.toolbox.automation.*
import com.landosol.toolbox.labyrinth.concurrent.LabyrinthFourThreadRuntime
import com.landosol.toolbox.labyrinth.vision.*
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test

/** Real Session refresh/plan/queue/executor, synthetic frame evidence; no Bitmap or device input. */
class EventChainSessionActionTest {
    private class Fixture(private val blockNative: Boolean = false) {
        val manager = AutomationSessionManager()
        val clock = AtomicLong(System.currentTimeMillis())
        val calls = AtomicInteger()
        val nativeEntries = AtomicInteger()
        val lastAction = AtomicReference<AutomationAction>()
        val nativeEntered = CompletableDeferred<Unit>()
        val nativeRelease = CompletableDeferred<Unit>()
        val backend = object : AutomationActionBackend {
            override suspend fun execute(action: AutomationAction): AutomationBackendResult {
                calls.incrementAndGet(); lastAction.set(action)
                return AutomationBackendResult.Completed
            }
            override suspend fun executeGuarded(action: AutomationAction, permit: () -> Boolean): AutomationBackendResult {
                nativeEntries.incrementAndGet()
                nativeEntered.complete(Unit)
                if (blockNative) nativeRelease.await()
                return if (!permit()) AutomationBackendResult.Rejected(AUTOMATION_PRE_DISPATCH_SUPPRESSED_REASON)
                    else execute(action)
            }
        }
        val session = LabyrinthEntryRecognitionSession(
            sessionManager = manager,
            captureActive = { assertTrue(LabyrinthFourThreadRuntime.isControlThread()); true },
            processorFactory = { { error("fixture has no Bitmap") } },
            fastProcessorFactory = { { error("fixture has no Bitmap") } },
            actionExecutor = SessionBoundActionExecutor(manager, backend),
            actionsAvailable = { true }, clock = { clock.get() },
        )
        val id get() = requireNotNull(session.state.value.sessionId)
        val event = com.landosol.toolbox.gamedata.EventResource("1112", "活动会场出现了魔物！", choices = listOf(
            com.landosol.toolbox.gamedata.EventChoiceResource("11121", "见识下御久间流的 剑术吧！", slot = 1),
            com.landosol.toolbox.gamedata.EventChoiceResource("11122", "这里就 交给我们吧！", slot = 2),
        ))
        val crop = EntryPixelRect(281, 72, 717, 86)
        fun field(name: String) = session.javaClass.getDeclaredField(name).apply { isAccessible = true }
        fun get(name: String): Any? = field(name).get(session)
        fun set(name: String, value: Any?) = field(name).set(session, value)
        @Suppress("UNCHECKED_CAST")
        fun state(update: (LabyrinthEntryRecognitionSessionState) -> LabyrinthEntryRecognitionSessionState) {
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
            val method = session.javaClass.declaredMethods.first {
                it.name.startsWith(name) && !it.name.contains('$') && it.parameterCount == args.size
            }.apply { isAccessible = true }
            return try { method.invoke(session, *adapt(method.parameterTypes, args)) }
                catch (error: InvocationTargetException) { throw error.targetException }
        }
        suspend fun start() {
            assertTrue(session.startAutomation() is LabyrinthEntryRecognitionStartResult.Started)
            withContext(LabyrinthFourThreadRuntime.controlDispatcher) {
                set("entryPhaseComplete", true)
                set("eventCustomizationEnabledSnapshot", false)
                set("eventChoicePlanner", LabyrinthEventChoicePlanner(emptyMap(), LabyrinthTeamOptimizer(LabyrinthTeamScorer(
                    LabyrinthTeamScoringConfig(attributeDamageBonus = (1..5).associateWith { 0.0 }),
                )), LabyrinthStrategySettings(eventCustomizationEnabled = false)))
            }
        }
        fun observed(trusted: Boolean = true, stable: Int = 2) = LabyrinthEventChoiceObservation(event,
            event.choices.map { LabyrinthEventChoiceVisual(it,
                EntryPixelRect(if (it.slot == 1) 353 else 712, 540, 214, 115), 0.8, 0.8) },
            event.name, 1.0, 0.4, stable, trusted)
        fun frame(observed: LabyrinthEventChoiceObservation? = observed(), expired: Boolean = false) = LabyrinthEntryFrameResult(
            observation = LabyrinthEntryPageObservation(LabyrinthEntryPageState.EVENT_CHOICE, 1.0,
                mapOf(LabyrinthEntryPageState.EVENT_CHOICE to 1.0),
                LabyrinthAnchorScores(mapOf(EntryAnchorId.EVENT_CHOICE_TITLE to 1.0, EntryAnchorId.EVENT_CHOICE_OPTION to 1.0))),
            matchedFeatures = emptyList(), elapsedMillis = 0,
            eventChoiceSelection = observed,
            eventOcrDiagnostics = LabyrinthEventOcrDiagnostics(
                status = if (expired) "TITLE_WAITING_OCR" else "TITLE_TRUSTED_MATCH",
                cropRect = crop, fingerprint = "fixture-title", completeLayoutCount = 2,
                buttonConfidencesByOptionCount = mapOf(2 to listOf(0.8, 0.8)),
                cache = LabyrinthEventOcrCacheDiagnostics(
                    status = if (expired) "EXPIRED" else "HIT", ageMillis = if (expired) 5_200L else 100L,
                    fingerprintDistance = 0, cachedCropRect = crop, cachedRequestId = 1L,
                    pendingRequestId = null, pendingAgeMillis = null, lastCompletedRequestId = 1L,
                    lastRequestDurationMillis = 60L, lastResultTextLength = 10, lastResultStatus = "TEXT",
                    cacheLifetimeMillis = 5_000L,
                ),
            ), frameWidth = 1280, frameHeight = 720,
        )
        fun seed(frame: LabyrinthEntryFrameResult, capturedAt: Long) {
            clock.set(capturedAt)
            set("balancedDetailCapturedAt", capturedAt)
            set("balancedDetailWidth", 1280); set("balancedDetailHeight", 720)
            set("balancedDetailEpoch", get("balancedSceneEpoch"))
            state { it.copy(lastResult = frame) }
        }
        suspend fun refresh(frame: LabyrinthEntryFrameResult, capturedAt: Long = System.currentTimeMillis()) =
            withContext(LabyrinthFourThreadRuntime.controlDispatcher) {
                seed(frame, capturedAt)
                invoke("refreshEventChoiceDecision", id, frame)
            }
        suspend fun awaitReady() = withTimeout(5_000L) {
            while (!withContext(LabyrinthFourThreadRuntime.controlDispatcher) {
                val lane = get("eventPlanning") as LabyrinthEventPlanningLane
                lane.hasReady && !lane.busy
            }) delay(2)
        }
        suspend fun confirmedPlan(): Pair<LabyrinthPostEntryTapPlan, Long> {
            refresh(frame())
            awaitReady()
            delay(2) // A distinct current capture, never a fake clock=1000 accepted by native Main.
            return withContext(LabyrinthFourThreadRuntime.controlDispatcher) {
                val capturedAt = System.currentTimeMillis()
                seed(frame(), capturedAt)
                invoke("refreshEventChoiceDecision", id, frame())
                val selected = session.state.value.eventChoiceDecision as LabyrinthEventChoiceDecision.Select
                assertTrue(selected.actionSafe)
                val plan = invoke("catalogEventTapPlan", id, selected, capturedAt) as LabyrinthPostEntryTapPlan
                assertNotNull(plan.eventPermit)
                plan to capturedAt
            }
        }
        fun dispatch(plan: LabyrinthPostEntryTapPlan, capturedAt: Long) {
            invoke("dispatchPostEntryTap", id, plan.kind, plan.label, plan.rect, capturedAt,
                null, null, null, null, null, false, emptyList<String>(), plan.eventPermit, plan.eventChoiceId)
        }
        suspend fun awaitActions() = withTimeout(5_000L) {
            while (withContext(LabyrinthFourThreadRuntime.controlDispatcher) { (get("actionInFlight") as AtomicBoolean).get() }) delay(2)
        }
        suspend fun feedFull(frame: LabyrinthEntryFrameResult, capturedAt: Long = System.currentTimeMillis()) =
            withContext(LabyrinthFourThreadRuntime.controlDispatcher) {
                seed(frame, capturedAt)
                suspendCoroutine<Unit> { continuation ->
                    val method = session.javaClass.declaredMethods.first {
                        it.name.startsWith("processFrameResult") && it.parameterCount == 6
                    }.apply { isAccessible = true }
                    val args = arrayOf<Any?>(id, frame, 1280, 720, capturedAt, continuation)
                    try {
                        if (method.invoke(session, *adapt(method.parameterTypes, args)) !== COROUTINE_SUSPENDED) continuation.resume(Unit)
                    } catch (error: InvocationTargetException) { continuation.resumeWithException(error.targetException) }
                }
            }
        suspend fun close() {
            nativeRelease.complete(Unit)
            awaitActions()
            session.stop("event fixture done")
        }
    }

    @Test fun `fresh current automatic event goes through captured plan and executor once`() = runBlocking {
        val f = Fixture()
        try {
            f.start()
            val (plan, at) = f.confirmedPlan()
            assertEquals("11121", plan.eventChoiceId)
            withContext(LabyrinthFourThreadRuntime.controlDispatcher) { f.dispatch(plan, at) }
            f.awaitActions()
            assertEquals(1, f.calls.get())
            val tap = f.lastAction.get() as AutomationAction.Tap
            assertEquals(plan.rect.left + plan.rect.width / 2f, tap.point.x, 0f)
            assertEquals(plan.rect.top + plan.rect.height / 2f, tap.point.y, 0f)
        } finally { f.close() }
    }

    @Test fun `same T2 turn loss of event trust revokes queued plan before executor`() = runBlocking {
        val f = Fixture()
        try {
            f.start(); val (plan, at) = f.confirmedPlan()
            withContext(LabyrinthFourThreadRuntime.controlDispatcher) {
                f.dispatch(plan, at)
                f.refresh(f.frame(f.observed(trusted = false)))
                assertFalse(requireNotNull(plan.eventPermit).isCurrent())
            }
            f.awaitActions()
            assertEquals(0, f.calls.get())
            assertEquals(0, f.nativeEntries.get())
            withContext(LabyrinthFourThreadRuntime.controlDispatcher) {
                assertEquals(0, f.get("postEntryAttempts"))
                assertEquals(0, f.get("eventActionAttempts"))
            }
        } finally { f.close() }
    }

    @Test fun `strict TTL refresh retains ready calculation but suppresses queued catalog input`() = runBlocking {
        val f = Fixture()
        try {
            f.start(); val (plan, at) = f.confirmedPlan()
            withContext(LabyrinthFourThreadRuntime.controlDispatcher) {
                f.dispatch(plan, at)
                f.refresh(f.frame(observed = null, expired = true))
                val lane = f.get("eventPlanning") as LabyrinthEventPlanningLane
                assertTrue(lane.hasReady)
                assertEquals(1L, lane.diagnostics().ocrRefreshRetainedCount)
                assertTrue(f.session.state.value.eventChoiceDecision is LabyrinthEventChoiceDecision.Wait)
                assertFalse(requireNotNull(plan.eventPermit).isCurrent())
            }
            f.awaitActions()
            assertEquals(0, f.calls.get())
        } finally { f.close() }
    }

    @Test fun `accepted detail fences old token before channel prompt early return`() = runBlocking {
        val f = Fixture()
        try {
            f.start(); val (plan, at) = f.confirmedPlan()
            withContext(LabyrinthFourThreadRuntime.controlDispatcher) {
                f.dispatch(plan, at)
                f.set("channelPromptVisibleUntil", System.currentTimeMillis() + 5_000L)
                f.feedFull(f.frame(f.observed(trusted = false)))
                // The early handler need not rewrite the displayed old decision to revoke native permission.
                assertFalse(requireNotNull(plan.eventPermit).isCurrent())
            }
            f.awaitActions()
            assertEquals(0, f.calls.get())
            assertEquals(0, f.nativeEntries.get())
        } finally { f.close() }
    }

    @Test fun `native dispatch permit rechecks trust after queued executor body already began`() = runBlocking {
        val f = Fixture(blockNative = true)
        try {
            f.start(); val (plan, at) = f.confirmedPlan()
            withContext(LabyrinthFourThreadRuntime.controlDispatcher) { f.dispatch(plan, at) }
            withTimeout(5_000L) { f.nativeEntered.await() }
            f.refresh(f.frame(f.observed(trusted = false)))
            f.nativeRelease.complete(Unit)
            f.awaitActions()
            assertEquals(1, f.nativeEntries.get())
            assertEquals(0, f.calls.get())
            withContext(LabyrinthFourThreadRuntime.controlDispatcher) {
                assertFalse(f.get("eventChoiceCommitted") as Boolean)
                assertEquals(0, f.get("postEntryAttempts"))
                assertEquals(0, f.get("eventActionAttempts"))
            }
        } finally { f.close() }
    }

    @Test fun `new safe rectangle does not lend its new revision to the old queued coordinates`() = runBlocking {
        val f = Fixture()
        try {
            f.start(); val (plan, at) = f.confirmedPlan()
            val changed = f.observed().copy(choices = f.observed().choices.map {
                if (it.choice.id == plan.eventChoiceId) it.copy(buttonRect = it.buttonRect.copy(left = it.buttonRect.left + 2)) else it
            })
            withContext(LabyrinthFourThreadRuntime.controlDispatcher) {
                f.dispatch(plan, at)
                f.refresh(f.frame(changed))
                val current = f.session.state.value.eventChoiceDecision as LabyrinthEventChoiceDecision.Select
                assertTrue(current.actionSafe)
                assertEquals(plan.rect.left + 2, current.buttonRect.left)
                assertFalse(requireNotNull(plan.eventPermit).isCurrent())
            }
            f.awaitActions()
            assertEquals(0, f.calls.get())
        } finally { f.close() }
    }

    @Test fun `automatic rejected commit refreshes same frame and selects a different branch later`() = runBlocking {
        val f = Fixture()
        try {
            f.start(); val (oldPlan, _) = f.confirmedPlan()
            assertEquals("11121", oldPlan.eventChoiceId)
            withContext(LabyrinthFourThreadRuntime.controlDispatcher) {
                val at = System.currentTimeMillis()
                f.seed(f.frame(), at)
                f.set("lastPostEntryState", LabyrinthEntryPageState.EVENT_CHOICE)
                f.set("postEntryStableFrames", 2)
                f.set("eventChoiceCommitted", true)
                f.set("eventChoiceCommittedAt", at - 7_000L)
                f.set("eventChoiceCommittedChoiceId", "11121")
                f.set("lastPostEntryActionAt", Long.MIN_VALUE)
                f.set("lastPostEntryActionLandedAt", Long.MIN_VALUE)
                f.invoke("handlePostEntryPage", f.id, f.frame(), 1280, 720, at)
                @Suppress("UNCHECKED_CAST") val rejected = f.get("eventChoiceRejectedIds") as Set<String>
                assertTrue(rejected.contains("11121"))
                assertFalse(requireNotNull(oldPlan.eventPermit).isCurrent())
                assertTrue(f.session.state.value.eventChoiceDecision is LabyrinthEventChoiceDecision.Wait)
                assertEquals(0, f.calls.get())
            }
            val (replacement, at) = f.confirmedPlan()
            assertEquals("11122", replacement.eventChoiceId)
            withContext(LabyrinthFourThreadRuntime.controlDispatcher) { f.dispatch(replacement, at) }
            f.awaitActions()
            assertEquals(1, f.calls.get())
            val tap = f.lastAction.get() as AutomationAction.Tap
            assertEquals(replacement.rect.left + replacement.rect.width / 2f, tap.point.x, 0f)
        } finally { f.close() }
    }

    @Test fun `a fresh Session frame cannot extend an old plan captured deadline`() = runBlocking {
        val f = Fixture()
        try {
            f.start(); f.confirmedPlan()
            withContext(LabyrinthFourThreadRuntime.controlDispatcher) {
                val selected = f.session.state.value.eventChoiceDecision as LabyrinthEventChoiceDecision.Select
                val oldAt = System.currentTimeMillis() - 1_201L
                val old = f.invoke("catalogEventTapPlan", f.id, selected, oldAt) as LabyrinthPostEntryTapPlan
                assertFalse(requireNotNull(old.eventPermit).isCurrent())
                f.refresh(f.frame())
                assertFalse(requireNotNull(old.eventPermit).isCurrent())
                f.dispatch(old, oldAt)
            }
            f.awaitActions()
            assertEquals(0, f.calls.get())
            assertEquals(0, f.nativeEntries.get())
        } finally { f.close() }
    }

    @Test fun `legacy noncatalog entry remains usable with no event token`() = runBlocking {
        val f = Fixture()
        try {
            f.start()
            withContext(LabyrinthFourThreadRuntime.controlDispatcher) {
                val at = System.currentTimeMillis()
                f.seed(f.frame(observed = null), at)
                f.set("balancedRequiredCapturedAfter", at - 1L)
                f.invoke("dispatchPostEntryTap", f.id, LabyrinthPostEntryActionKind.SELECT_EVENT,
                    "single-choice legacy fixture", EntryPixelRect(500, 540, 200, 100), at,
                    null, null, null, null, null, false, emptyList<String>())
            }
            f.awaitActions()
            assertEquals(1, f.calls.get())
        } finally { f.close() }
    }
}
