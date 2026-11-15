package com.landosol.toolbox.labyrinth

import com.landosol.toolbox.automation.*
import com.landosol.toolbox.labyrinth.concurrent.LabyrinthFourThreadRuntime
import com.landosol.toolbox.labyrinth.node.*
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
import org.junit.Assert.*
import org.junit.Test

/** Actual Session -> node matcher/planner -> queued action -> executor, with synthetic vision input. */
class NodeClickSessionEvidenceTest {
    private class Fixture(
        val type: Int = LabyrinthNodeTypes.NORMAL_BATTLE,
        val boss: Boolean = false,
        val balanced: Boolean = true,
        private val blockNative: Boolean = false,
    ) {
        val manager = AutomationSessionManager()
        val clock = AtomicLong(System.currentTimeMillis())
        val calls = AtomicInteger()
        val nativeEntries = AtomicInteger()
        val lastAction = AtomicReference<AutomationAction>()
        val enteredAction = AtomicReference<AutomationAction>()
        val nativePermit = AtomicReference<(() -> Boolean)>()
        val nativeEntered = CompletableDeferred<Unit>()
        val nativeRelease = CompletableDeferred<Unit>()
        val rect = EntryPixelRect(540, 140, 186, 233)
        val bossLabel = EntryPixelRect(720, 400, 81, 31)
        private var issuedAt = 0L
        private var sequence = 0L
        var lastAcceptedAt = 0L
            private set
        val route = route(type, boss)
        val nodes = LabyrinthNodeSession()
        val backend = object : AutomationActionBackend {
            override suspend fun execute(action: AutomationAction): AutomationBackendResult {
                calls.incrementAndGet()
                lastAction.set(action)
                return AutomationBackendResult.Completed
            }
            override suspend fun executeGuarded(action: AutomationAction, permit: () -> Boolean): AutomationBackendResult {
                nativeEntries.incrementAndGet()
                enteredAction.set(action)
                nativePermit.set(permit)
                nativeEntered.complete(Unit)
                if (blockNative) nativeRelease.await()
                return if (permit()) execute(action)
                    else AutomationBackendResult.Rejected(AUTOMATION_PRE_DISPATCH_SUPPRESSED_REASON)
            }
        }
        val session = LabyrinthEntryRecognitionSession(
            sessionManager = manager,
            captureActive = { true },
            processorFactory = { { error("synthetic detail fixture never processes Bitmap") } },
            fastProcessorFactory = if (balanced) ({ { error("synthetic fast fixture never processes Bitmap") } }) else null,
            actionExecutor = SessionBoundActionExecutor(manager, backend),
            actionsAvailable = { true },
            clock = { clock.get() },
            routeLoader = { route },
            routeExecutionGate = { LabyrinthExecutionGateResult.Allowed(route, "synthetic route admitted") },
            nodeTemplateLoader = { NodeTemplateSet(emptyMap()) },
            nodeSessionFactory = { nodes },
        )
        val id get() = requireNotNull(session.state.value.sessionId)
        val evidence get() = get("nodeActionEvidence") as LabyrinthNodeActionEvidence
        fun field(name: String) = session.javaClass.getDeclaredField(name).apply { isAccessible = true }
        fun get(name: String): Any? = field(name).get(session)
        fun set(name: String, value: Any?) = field(name).set(session, value)
        private fun adapt(types: Array<Class<*>>, args: Array<out Any?>) = types.mapIndexed { index, klass ->
            val value = args[index]
            when (klass.name) {
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
            assertTrue(session.startAutomation(accountId = 1L) is LabyrinthEntryRecognitionStartResult.Started)
            withContext(LabyrinthFourThreadRuntime.controlDispatcher) {
                nodes.initialize(route.toFullMap(), route.toLabyrinthRoute().nodes,
                    NodeTemplateSet(emptyMap()), route.currentBlockId)
                set("nodeSession", nodes)
                set("routeNodeCount", route.nodes.size)
                set("entryPhaseComplete", true)
                if (balanced) fast(LabyrinthEntryPageState.NODE_SELECTION, 1280, 720, clock.get())
            }
        }
        fun frame(
            source: NodeFrameEvidenceSource = if (boss) NodeFrameEvidenceSource.FINAL_BOSS_PLATFORM else NodeFrameEvidenceSource.SEARCH,
            candidateRect: EntryPixelRect = rect,
            mode: String = if (boss) "final-boss-platform" else "full",
            windowCount: Int = if (boss) 0 else 1,
            page: LabyrinthEntryPageState = LabyrinthEntryPageState.NODE_SELECTION,
        ): LabyrinthEntryFrameResult = LabyrinthEntryFrameResult(
            observation = LabyrinthEntryPageObservation(page, 1.0, emptyMap(), LabyrinthAnchorScores(emptyMap())),
            matchedFeatures = emptyList(), elapsedMillis = 0L,
            nodeClassifications = if (boss) emptyList() else listOf(NodeClassification(
                column = 2, row = 1, blockType = type, confidence = 0.95,
                isClickable = true, screenRect = candidateRect,
                cyanGlowScore = 0.4, cyanGlowRowCoverage = 0.8,
                purpleGlowScore = if (type == LabyrinthNodeTypes.EX_BATTLE) 0.4 else 0.0,
                purpleGlowRowCoverage = 0.8, purpleGlowSideScore = 0.4,
                purpleGlowLowerScore = 0.4, colorRoiScore = 0.95, typeConfidence = 0.95,
            )),
            finalBossPlatforms = if (boss) listOf(FinalBossPlatformMatch(
                labelRect = bossLabel, confidence = 0.95, colorScore = 1.0, purpleActivationScore = 0.5,
            )) else emptyList(),
            frameWidth = 1280, frameHeight = 720,
            nodeSearchMode = mode, nodeSearchWindowCount = windowCount,
            nodeEvidenceSource = source,
            nodeRevalidationCount = if (source == NodeFrameEvidenceSource.MEMO_REVALIDATED) 1 else 0,
            nodeViewportSignature = "synthetic-current-viewport",
        )
        suspend fun nextCapture(): Long {
            var now = System.currentTimeMillis()
            while (now <= issuedAt) {
                delay(2L)
                now = System.currentTimeMillis()
            }
            issuedAt = now
            return now
        }
        suspend fun feedFull(
            result: LabyrinthEntryFrameResult = frame(),
            at: Long? = null,
            callbackWidth: Int = 1280,
            callbackHeight: Int = 720,
        ) {
            val capturedAt = at ?: nextCapture()
            withContext(LabyrinthFourThreadRuntime.controlDispatcher) {
                clock.set(capturedAt)
                lastAcceptedAt = capturedAt
                set("balancedDetailCapturedAt", capturedAt)
                set("balancedDetailSequence", ++sequence)
                set("balancedDetailWidth", callbackWidth)
                set("balancedDetailHeight", callbackHeight)
                set("balancedDetailEpoch", get("balancedSceneEpoch"))
                suspendCoroutine<Unit> { continuation ->
                    val method = session.javaClass.declaredMethods.first {
                        it.name.startsWith("processFrameResult") && it.parameterCount == 6
                    }.apply { isAccessible = true }
                    val args = arrayOf<Any?>(id, result, callbackWidth, callbackHeight, capturedAt, continuation)
                    try {
                        if (method.invoke(session, *adapt(method.parameterTypes, args)) !== COROUTINE_SUSPENDED) {
                            continuation.resume(Unit)
                        }
                    } catch (error: InvocationTargetException) {
                        continuation.resumeWithException(error.targetException)
                    }
                }
            }
        }
        suspend fun freshFrames(result: LabyrinthEntryFrameResult = frame(), count: Int = 3): Long {
            repeat(count) { feedFull(result) }
            return lastAcceptedAt
        }
        fun fast(page: LabyrinthEntryPageState, width: Int, height: Int, at: Long) {
            invoke("observeBalancedFast", id, LabyrinthFastFrameObservation(
                LabyrinthEntryPageObservation(page, 1.0, emptyMap(), LabyrinthAnchorScores(emptyMap())),
                ++sequence, at, width, height, 0L,
            ))
        }
        suspend fun awaitNative() = withTimeout(5_000L) { nativeEntered.await() }
        suspend fun awaitActions() = withTimeout(5_000L) {
            while (withContext(LabyrinthFourThreadRuntime.controlDispatcher) {
                (get("actionInFlight") as AtomicBoolean).get()
            }) delay(2L)
        }
        suspend fun close() = withContext(NonCancellable) {
            // The executor holds the session-manager mutex across executeGuarded. Always release
            // our artificial native barrier before asking the manager to stop, even after timeout.
            nativeRelease.complete(Unit)
            withTimeout(5_000L) {
                awaitActions()
                session.stop("node evidence fixture complete")
            }
            Unit
        }
        companion object {
            private fun route(type: Int, boss: Boolean): LabyrinthRouteJson {
                val specs = if (boss) listOf(
                    Triple(30601L, 3, LabyrinthNodeTypes.SHOP),
                    Triple(30701L, 3, LabyrinthNodeTypes.BOSS),
                    Triple(40101L, 4, LabyrinthNodeTypes.START),
                ) else listOf(
                    Triple(10101L, 1, LabyrinthNodeTypes.START), Triple(10201L, 1, type),
                )
                val nodes = specs.mapIndexed { index, spec -> LabyrinthNodeJson(
                    area = spec.second, column = (spec.first / 100 % 100).toInt(), row = 1,
                    blockId = spec.first, blockType = spec.third, questId = null,
                    nextBlockIds = specs.getOrNull(index + 1)?.let { listOf(it.first) }.orEmpty(),
                    isAreaLastPoint = false,
                ) }
                return LabyrinthRouteJson(
                    enterId = 1L, guildId = 1, difficulty = 1, attempt = 1,
                    rerollUntilFound = false, perfectStart = false, thirdBlockChoice = "fixture",
                    area3BossIds = emptyList(), area5BossIds = emptyList(),
                    nodes = nodes, allNodes = nodes, currentBlockId = nodes.first().blockId,
                )
            }
        }
    }

    @Test fun allSixOrdinaryNodeTypesUseTheActualSharedClickPath() = runBlocking {
        for (type in listOf(LabyrinthNodeTypes.NORMAL_BATTLE, LabyrinthNodeTypes.EX_BATTLE,
            LabyrinthNodeTypes.LINK, LabyrinthNodeTypes.EVENT, LabyrinthNodeTypes.RELIC, LabyrinthNodeTypes.SHOP)) {
            val f = Fixture(type = type)
            try {
                f.start()
                f.freshFrames(count = 2)
                assertEquals("must wait for three current frames: " + type, 0, f.calls.get())
                f.feedFull()
                f.awaitActions()
                assertEquals("shared node path did not dispatch: " + type + " / " + f.session.state.value.message,
                    1, f.calls.get())
                assertEquals(1, f.nativeEntries.get())
                val tap = f.lastAction.get() as AutomationAction.Tap
                val expected = LabyrinthNodeActionPlanner().getBaseClickPosition(f.rect)
                assertEquals(expected.first.toFloat(), tap.point.x, 0f)
                assertEquals(expected.second.toFloat(), tap.point.y, 0f)
                assertEquals(1, f.get("nodeTapAttempts"))
                assertNotNull(f.get("pendingNodeTransition"))
                assertEquals(10101L, f.nodes.getState().currentNodeId)
            } finally { f.close() }
        }
    }

    @Test fun trackedAndMemoRevalidatedSourcesProvideCurrentFrameProof() = runBlocking {
        for (source in listOf(NodeFrameEvidenceSource.TRACKED, NodeFrameEvidenceSource.MEMO_REVALIDATED)) {
            val f = Fixture()
            try {
                f.start()
                f.freshFrames(f.frame(source = source, windowCount = 0))
                f.awaitActions()
                assertEquals(source.name, 1, f.calls.get())
            } finally { f.close() }
        }
    }

    @Test fun noneOrDeferredEvidenceCannotBorrowVisibleCandidateOrWindowCount() = runBlocking {
        val f = Fixture()
        try {
            f.start()
            f.freshFrames(count = 2)
            f.freshFrames(f.frame(source = NodeFrameEvidenceSource.NONE, mode = "deferred", windowCount = 0), count = 3)
            f.freshFrames(f.frame(source = NodeFrameEvidenceSource.NONE, mode = "full", windowCount = 999), count = 9)
            assertEquals(0, f.calls.get())
            assertEquals(0, f.nativeEntries.get())
            withContext(LabyrinthFourThreadRuntime.controlDispatcher) {
                assertFalse(f.evidence.isCurrentFrame(f.lastAcceptedAt))
                assertEquals(0, f.get("pendingNodeClickStableFrames"))
            }
            f.freshFrames()
            f.awaitActions()
            assertEquals(1, f.calls.get())
        } finally { f.close() }
    }

    @Test fun duplicateCaptureTimestampResetsStabilityInsteadOfCountingNewVision() = runBlocking {
        val f = Fixture()
        try {
            f.start()
            val repeated = f.freshFrames(count = 2)
            repeat(12) { f.feedFull(at = repeated) }
            assertEquals(0, f.calls.get())
            withContext(LabyrinthFourThreadRuntime.controlDispatcher) {
                assertFalse(f.evidence.isCurrentFrame(repeated))
                assertEquals(0, f.get("pendingNodeClickStableFrames"))
            }
            f.freshFrames()
            f.awaitActions()
            assertEquals(1, f.calls.get())
        } finally { f.close() }
    }

    @Test fun mismatchedDetailDimensionsNeverAuthorizeCurrentNodeCoordinates() = runBlocking {
        val f = Fixture()
        try {
            f.start()
            repeat(3) { f.feedFull(f.frame().copy(frameWidth = 1920, frameHeight = 1080)) }
            assertEquals(0, f.calls.get())
            withContext(LabyrinthFourThreadRuntime.controlDispatcher) {
                assertFalse(f.evidence.isCurrentFrame(f.lastAcceptedAt))
            }
            f.freshFrames()
            f.awaitActions()
            assertEquals(1, f.calls.get())
        } finally { f.close() }
    }

    @Test fun dedicatedBossLocatorWithZeroOrdinaryWindowsStillGetsSharedProtection() = runBlocking {
        val f = Fixture(boss = true)
        try {
            f.start()
            assertEquals(30701L, f.nodes.directFinalBossTarget()?.blockId)
            f.freshFrames(f.frame(source = NodeFrameEvidenceSource.NONE))
            assertEquals(0, f.calls.get())
            f.freshFrames()
            f.awaitActions()
            assertEquals(1, f.calls.get())
            assertEquals(1, f.nativeEntries.get())
            val expectedRect = requireNotNull(finalBossPlatformClickRect(f.frame().finalBossPlatforms, 1280, 720, null))
            val expected = LabyrinthNodeActionPlanner().getBaseClickPosition(expectedRect)
            val tap = f.lastAction.get() as AutomationAction.Tap
            assertEquals(expected.first.toFloat(), tap.point.x, 0f)
            assertEquals(expected.second.toFloat(), tap.point.y, 0f)
        } finally { f.close() }
    }

    @Test fun nextUnverifiedDetailInSameControlTurnCancelsQueuedNodeTap() = runBlocking {
        val f = Fixture()
        try {
            f.start(); f.freshFrames(count = 2)
            val third = f.nextCapture()
            val fourth = f.nextCapture()
            withContext(LabyrinthFourThreadRuntime.controlDispatcher) {
                f.feedFull(at = third)
                f.feedFull(f.frame(source = NodeFrameEvidenceSource.NONE, mode = "deferred"), fourth)
            }
            f.awaitActions()
            assertEquals(0, f.calls.get())
            assertEquals(0, f.nativeEntries.get())
            assertEquals(0, f.get("nodeTapAttempts"))
            assertEquals(0, f.session.state.value.actionCount)
            assertEquals(Long.MIN_VALUE, f.get("lastNodeActionAt"))
        } finally { f.close() }
    }

    @Test fun newTrustedRectangleCannotLoanItsEvidenceToOldQueuedCoordinates() = runBlocking {
        val f = Fixture()
        try {
            f.start(); f.freshFrames(count = 2)
            val third = f.nextCapture()
            val fourth = f.nextCapture()
            val newRect = f.rect.copy(left = f.rect.left + 200)
            withContext(LabyrinthFourThreadRuntime.controlDispatcher) {
                f.feedFull(at = third)
                f.feedFull(f.frame(candidateRect = newRect), fourth)
            }
            f.awaitActions()
            assertEquals(0, f.calls.get())
            assertEquals(0, f.nativeEntries.get())
            f.freshFrames(f.frame(candidateRect = newRect))
            f.awaitActions()
            assertEquals(1, f.calls.get())
            assertEquals((newRect.left + newRect.width / 2).toFloat(),
                (f.lastAction.get() as AutomationAction.Tap).point.x, 0f)
        } finally { f.close() }
    }

    @Test fun acceptedDetailEarlyReturnRevokesNodeAlreadyWaitingAtNativeBoundary() = runBlocking {
        val f = Fixture(blockNative = true)
        try {
            f.start(); f.freshFrames(); f.awaitNative()
            assertTrue(requireNotNull(f.nativePermit.get()).invoke())
            withContext(LabyrinthFourThreadRuntime.controlDispatcher) {
                f.set("channelPromptVisibleUntil", System.currentTimeMillis() + 5_000L)
            }
            f.feedFull(f.frame())
            assertFalse(requireNotNull(f.nativePermit.get()).invoke())
            f.nativeRelease.complete(Unit); f.awaitActions()
            assertEquals(1, f.nativeEntries.get())
            assertEquals(0, f.calls.get())
            assertEquals(0, f.get("nodeTapAttempts"))
        } finally { f.close() }
    }

    @Test fun resizeRevokesNativeTokenWithoutRequiringAnotherAcceptedDetail() = runBlocking {
        val f = Fixture(blockNative = true)
        try {
            f.start(); f.freshFrames(); f.awaitNative()
            withContext(LabyrinthFourThreadRuntime.controlDispatcher) {
                f.fast(LabyrinthEntryPageState.NODE_SELECTION, 1920, 1080, System.currentTimeMillis())
            }
            assertFalse(requireNotNull(f.nativePermit.get()).invoke())
            f.nativeRelease.complete(Unit); f.awaitActions()
            assertEquals(0, f.calls.get())
        } finally { f.close() }
    }

    @Test fun firstDifferentPositiveFastRevokesNativeNodeBeforeSceneConfirmation() = runBlocking {
        val f = Fixture(blockNative = true)
        try {
            withTimeout(5_000L) {
                f.start(); f.freshFrames(); f.awaitNative()
                withContext(LabyrinthFourThreadRuntime.controlDispatcher) {
                    val oldScene = f.get("balancedSceneEpoch")
                    val oldCapture = f.lastAcceptedAt
                    val oldDetail = f.session.state.value.lastResult
                    val oldPermit = requireNotNull(f.nativePermit.get())
                    assertTrue(oldPermit())
                    f.fast(LabyrinthEntryPageState.SHOP, 1280, 720, System.currentTimeMillis())
                    assertEquals(LabyrinthEntryPageState.SHOP, f.get("balancedPendingPositivePage"))
                    assertEquals(LabyrinthEntryPageState.NODE_SELECTION, f.get("balancedPositivePage"))
                    assertEquals("one fast page must not yet advance the scene", oldScene, f.get("balancedSceneEpoch"))
                    assertEquals(oldCapture, f.lastAcceptedAt)
                    assertSame("no new detail was accepted", oldDetail, f.session.state.value.lastResult)
                    assertFalse("the provisional positive page must already fence native input", oldPermit())
                }
                f.nativeRelease.complete(Unit); f.awaitActions()
                assertEquals(1, f.nativeEntries.get())
                assertEquals(0, f.calls.get())
                assertEquals(0, f.get("nodeTapAttempts"))
            }
        } finally { f.close() }
    }

    @Test fun legacySelfConsistentResizeStartsNewStabilityEvenForTheSameRectangle() = runBlocking {
        val f = Fixture(balanced = false)
        try {
            withTimeout(5_000L) {
                f.start(); f.freshFrames(count = 2); f.awaitActions()
                assertEquals(0, f.calls.get())
                assertEquals(2, f.get("pendingNodeClickStableFrames"))
                val resized = f.frame().copy(frameWidth = 1920, frameHeight = 1080)
                // Exact same rect defeats the old geometric tolerance gate. Only size history
                // can prevent this third capture from inheriting the old two stable observations.
                assertEquals(f.rect, resized.nodeClassifications.single().screenRect)
                f.feedFull(resized, callbackWidth = 1920, callbackHeight = 1080)
                f.awaitActions()
                withContext(LabyrinthFourThreadRuntime.controlDispatcher) {
                    assertTrue("the resized frame is valid, not a callback/result mismatch",
                        f.evidence.isCurrentFrame(f.lastAcceptedAt))
                    assertEquals(1, f.get("pendingNodeClickStableFrames"))
                }
                assertEquals(0, f.calls.get())
                assertEquals(0, f.nativeEntries.get())
                f.feedFull(resized, callbackWidth = 1920, callbackHeight = 1080)
                f.awaitActions()
                assertEquals(0, f.calls.get())
                f.feedFull(resized, callbackWidth = 1920, callbackHeight = 1080)
                f.awaitActions()
                assertEquals(1, f.calls.get())
                assertEquals(1, f.nativeEntries.get())
            }
        } finally { f.close() }
    }

    @Test fun confirmedFastSceneChangeRevokesNodeWaitingForNativeDispatch() = runBlocking {
        val f = Fixture(blockNative = true)
        try {
            f.start(); f.freshFrames(); f.awaitNative()
            withContext(LabyrinthFourThreadRuntime.controlDispatcher) {
                val at = System.currentTimeMillis()
                f.fast(LabyrinthEntryPageState.SHOP, 1280, 720, at)
                f.fast(LabyrinthEntryPageState.SHOP, 1280, 720, at + 301L)
            }
            assertFalse(requireNotNull(f.nativePermit.get()).invoke())
            f.nativeRelease.complete(Unit); f.awaitActions()
            assertEquals(0, f.calls.get())
        } finally { f.close() }
    }

    @Test fun balancedPauseRevokesNativeInputBeforeAcknowledgementWithoutFixtureDeadlock() = runBlocking {
        val f = Fixture(blockNative = true)
        try {
            withTimeout(5_000L) {
                f.start(); f.freshFrames(); f.awaitNative()
                val originalPermit = requireNotNull(f.nativePermit.get())
                assertTrue(originalPermit())
                // setPaused publishes its atomics before waiting for the manager mutex held by
                // executeGuarded. Do not await its acknowledgement before releasing that holder.
                val pause = async(start = CoroutineStart.UNDISPATCHED) { f.session.setPaused(true) }
                assertFalse(originalPermit())
                assertFalse(pause.isCompleted)
                f.nativeRelease.complete(Unit)
                assertTrue(pause.await())
                f.awaitActions()
                assertTrue(f.session.setPaused(false))
                assertFalse("resume must not revive the pre-pause native permit", originalPermit())
                assertEquals(1, f.nativeEntries.get())
                assertEquals(0, f.calls.get())
                assertEquals(0, f.get("nodeTapAttempts"))
            }
        } finally { f.close() }
    }

    @Test fun legacyPauseResumeRevokesOldFrameProofAndRestartsNodeStability() = runBlocking {
        val f = Fixture(balanced = false)
        try {
            withTimeout(5_000L) {
                f.start(); f.freshFrames(count = 2); f.awaitActions()
                assertEquals(0, f.calls.get())
                assertEquals(2, f.get("pendingNodeClickStableFrames"))
                val oldToken = withContext(LabyrinthFourThreadRuntime.controlDispatcher) {
                    requireNotNull(f.evidence.capture(f.lastAcceptedAt))
                }
                assertTrue(oldToken.isCurrent())
                // No in-flight manager lock: verify completed legacy lifecycle changes, not
                // preemption that its serialized pause API does not promise.
                assertTrue(f.session.setPaused(true))
                assertFalse(oldToken.isCurrent())
                assertTrue(f.session.setPaused(false))
                assertFalse(oldToken.isCurrent())
                assertEquals(0, f.get("pendingNodeClickStableFrames"))
                assertEquals(0, f.calls.get())
                f.freshFrames(count = 2); f.awaitActions()
                assertEquals(0, f.calls.get())
                f.feedFull(); f.awaitActions()
                assertEquals(1, f.calls.get())
                assertEquals(1, f.nativeEntries.get())
                assertFalse(oldToken.isCurrent())
            }
        } finally { f.close() }
    }

    @Test fun nodeExecutionResetRevokesOldNativeInputEvenWhileSessionIdIsUnchanged() = runBlocking {
        val f = Fixture(blockNative = true)
        try {
            f.start(); f.freshFrames(); f.awaitNative()
            val originalId = f.id
            withContext(LabyrinthFourThreadRuntime.controlDispatcher) { f.invoke("resetNodeExecutionState") }
            assertEquals(originalId, f.id)
            assertFalse(requireNotNull(f.nativePermit.get()).invoke())
            f.nativeRelease.complete(Unit); f.awaitActions()
            assertEquals(0, f.calls.get())
        } finally { f.close() }
    }

    @Test fun nativeDelayCannotUseEvidencePastItsOriginalCaptureDeadline() = runBlocking {
        val f = Fixture(blockNative = true)
        try {
            f.start(); val capturedAt = f.freshFrames(); f.awaitNative()
            val remaining = (capturedAt + LabyrinthBalancedEvidence.MAX_ACTION_EVIDENCE_AGE_MILLIS + 30L -
                System.currentTimeMillis()).coerceAtLeast(0L)
            delay(remaining)
            withContext(LabyrinthFourThreadRuntime.controlDispatcher) {
                // No new frame/revision: this must be the age check, not a replacement-frame veto.
                assertTrue(f.evidence.isCurrentFrame(capturedAt))
            }
            assertFalse(requireNotNull(f.nativePermit.get()).invoke())
            f.nativeRelease.complete(Unit); f.awaitActions()
            assertEquals(0, f.calls.get())
        } finally { f.close() }
    }

    @Test fun existingNonNodeLegacyEventDispatchDoesNotRequireNodeEvidence() = runBlocking {
        val f = Fixture()
        try {
            f.start()
            val at = f.nextCapture()
            f.feedFull(f.frame(source = NodeFrameEvidenceSource.NONE, page = LabyrinthEntryPageState.EVENT_CHOICE), at)
            withContext(LabyrinthFourThreadRuntime.controlDispatcher) {
                assertNull(f.evidence.capture(at))
                f.invoke("dispatchPostEntryTap", f.id, LabyrinthPostEntryActionKind.SELECT_EVENT,
                    "legacy single event selection", EntryPixelRect(500, 540, 200, 100), at,
                    null, null, null, null, null, false, emptyList<String>())
            }
            f.awaitActions()
            assertEquals(1, f.calls.get())
        } finally { f.close() }
    }
}
