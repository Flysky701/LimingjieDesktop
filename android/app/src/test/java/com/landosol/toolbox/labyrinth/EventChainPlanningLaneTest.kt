package com.landosol.toolbox.labyrinth

import com.landosol.toolbox.gamedata.EventChoiceResource
import com.landosol.toolbox.gamedata.EventResource
import com.landosol.toolbox.labyrinth.concurrent.LabyrinthFourThreadRuntime
import com.landosol.toolbox.labyrinth.concurrent.WorkToken
import com.landosol.toolbox.labyrinth.vision.EntryPixelRect
import com.landosol.toolbox.labyrinth.vision.LabyrinthEntryPageState
import com.landosol.toolbox.labyrinth.vision.LabyrinthEventChoiceObservation
import com.landosol.toolbox.labyrinth.vision.LabyrinthEventChoiceVisual
import com.landosol.toolbox.labyrinth.vision.LabyrinthEventOcrCacheDiagnostics
import com.landosol.toolbox.labyrinth.vision.LabyrinthEventOcrDiagnostics
import com.landosol.toolbox.labyrinth.vision.LabyrinthEventVisualIdentity
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Real shared-pool tests; recorded evidence replay is not pixel OCR or a performance benchmark. */
class EventChainPlanningLaneTest {
    private val crop = EntryPixelRect(281, 72, 717, 86)
    private val session = Any()
    private val planner = Any()
    private val stacks = LabyrinthRelicMark.entries.associateWith { if (it == LabyrinthRelicMark.DEFENSE) 1 else 0 }
    private val context = LabyrinthRoleDecisionContext(defenseMarkStacks = 1)

    @Test
    fun stableOneAndUntrustedFramesNeverSubmitOrRevalidate() = runBlocking {
        val fixture = Fixture()
        val calls = AtomicInteger()
        val revalidations = AtomicInteger()
        try {
            val confirmed = observation()
            listOf(
                confirmed.copy(stableFrames = 1, trusted = false),
                confirmed.copy(stableFrames = 1, trusted = true),
                confirmed.copy(stableFrames = 2, trusted = false),
            ).forEach { current ->
                assertWait(fixture.update(key(current), current, visual(), 10_000L,
                    calculate = { calls.incrementAndGet(); selected(current) },
                    revalidate = { revalidations.incrementAndGet(); it }))
            }
            withContext(fixture.control) {
                assertFalse(fixture.lane.busy)
                assertFalse(fixture.lane.hasReady)
                assertNull(fixture.lane.heldObservation)
                assertEquals(0L, fixture.lane.diagnostics().submittedCount)
            }
            assertEquals(0, calls.get())
            assertEquals(0, revalidations.get())
        } finally { fixture.close() }
    }

    @Test
    fun sameSemanticCalculationSurvivesStrictExpiryAndStableOneReconfirmation() = runBlocking {
        val fixture = Fixture()
        val confirmed = observation()
        val semantic = key(confirmed)
        val currentVisual = requireNotNull(visual())
        val work = BlockedCalculation(selected(confirmed))
        val validations = AtomicInteger()
        try {
            assertWait(fixture.update(semantic, confirmed, currentVisual, 1_000L, work::calculate))
            work.entered.awaitChecked()
            repeat(4) { index ->
                fixture.clock.set(7_000L + index * 6_000L)
                withContext(fixture.control) {
                    assertTrue(fixture.lane.holdExpired(semantic, currentVisual,
                        LabyrinthEntryPageState.EVENT_CHOICE, expiredDiagnostic()))
                    assertEquals("OCR_REFRESH_WAIT", fixture.lane.diagnostics().phase)
                    assertTrue(fixture.lane.busy)
                }
                val candidate = confirmed.copy(trusted = false, stableFrames = 1)
                assertWait(fixture.update(key(candidate), candidate, currentVisual, fixture.clock.get(),
                    work::calculate, { validations.incrementAndGet(); it }))
                // Leave the last cycle awaiting reconfirmation, like the final recorded frames.
                if (index < 3) assertWait(fixture.update(semantic, confirmed.copy(stableFrames = 2 + index),
                    currentVisual, fixture.clock.get() + 1L, work::calculate))
            }
            assertEquals(1, work.calls.get())
            assertFalse(requireNotNull(work.token.get()).isCancelled)
            assertEquals(0, validations.get())
            withContext(fixture.control) {
                val diagnostic = fixture.lane.diagnostics()
                assertEquals(1L, diagnostic.submittedCount)
                assertEquals(0L, diagnostic.cancelledCount)
                assertEquals(4L, diagnostic.ocrRefreshRetainedCount)
            }
            fixture.clock.set(50_000L)
            work.release.countDown()
            fixture.awaitState { it.hasReady && !it.busy }
            val readyAt = withContext(fixture.control) { fixture.lane.readyAt }
            val candidate = confirmed.copy(trusted = false, stableFrames = 1)
            assertWait(fixture.update(key(candidate), candidate, currentVisual, readyAt + 1,
                calculate = { error("candidate must not compute") },
                revalidate = { error("untrusted candidate must not receive a ready result") }))
            withContext(fixture.control) { assertTrue(fixture.lane.hasReady) }
            val decision = fixture.update(semantic, confirmed, currentVisual, readyAt + 1,
                calculate = { error("same-key ready result must be reused") },
                revalidate = { validations.incrementAndGet(); it })
            assertTrue(decision is LabyrinthEventChoiceDecision.Select)
            assertEquals(1, validations.get())
            assertEquals(1, work.calls.get())
        } finally { fixture.close(work) }
    }

    @Test
    fun duplicateHandlingOfTheSameExpiryCountsOneHoldWithoutAnotherJob() = runBlocking {
        val fixture = Fixture()
        val confirmed = observation()
        val semantic = key(confirmed)
        val currentVisual = requireNotNull(visual())
        val work = BlockedCalculation(selected(confirmed))
        try {
            assertWait(fixture.update(semantic, confirmed, currentVisual, 1_000L, work::calculate))
            work.entered.awaitChecked()
            withContext(fixture.control) {
                repeat(3) {
                    assertTrue(fixture.lane.holdExpired(semantic, currentVisual,
                        LabyrinthEntryPageState.EVENT_CHOICE, expiredDiagnostic()))
                }
                assertEquals(1L, fixture.lane.diagnostics().ocrRefreshRetainedCount)
                assertEquals(1L, fixture.lane.diagnostics().submittedCount)
                assertEquals(0L, fixture.lane.diagnostics().cancelledCount)
            }
            assertEquals(1, work.calls.get())
            assertFalse(requireNotNull(work.token.get()).isCancelled)
        } finally { fixture.close(work) }
    }

    @Test
    fun readyResultNeedsANewerCapturedFrameAndCurrentTrustBeforeRevalidation() = runBlocking {
        val fixture = Fixture()
        val confirmed = observation()
        val semantic = key(confirmed)
        val work = BlockedCalculation(selected(confirmed))
        val validations = AtomicInteger()
        try {
            assertWait(fixture.update(semantic, confirmed, visual(), 1_000L, work::calculate))
            work.entered.awaitChecked()
            fixture.clock.set(2_000L)
            work.release.countDown()
            fixture.awaitState { it.hasReady && !it.busy }
            assertEquals(2_000L, withContext(fixture.control) { fixture.lane.readyAt })
            listOf<Long?>(null, 1_999L, 2_000L).forEach { capturedAt ->
                assertWait(fixture.update(semantic, confirmed, visual(), capturedAt,
                    calculate = { error("ready result must not restart") },
                    revalidate = { validations.incrementAndGet(); it }))
            }
            val untrusted = confirmed.copy(trusted = false, stableFrames = 8)
            assertWait(fixture.update(key(untrusted), untrusted, visual(), 2_001L,
                calculate = { error("untrusted frame must not submit") },
                revalidate = { error("untrusted frame must not revalidate") }))
            assertEquals(0, validations.get())
            withContext(fixture.control) { assertFalse(fixture.lane.hasReady) }
            // A non-TTL loss of trust clears even a completed ready result. Recompute after recovery.
            assertWait(fixture.update(semantic, confirmed, visual(), 2_001L,
                calculate = { selected(confirmed) }))
            fixture.awaitState { it.hasReady && !it.busy }
            val changedRect = EntryPixelRect(360, 542, 214, 115)
            val current = confirmed.copy(choices = confirmed.choices.mapIndexed { index, item ->
                if (index == 0) item.copy(buttonRect = changedRect) else item
            })
            assertEquals(semantic, key(current))
            val freshAt = withContext(fixture.control) { fixture.lane.readyAt + 1L }
            val decision = fixture.update(key(current), current, visual(), freshAt,
                calculate = { error("rectangle jitter is not a semantic calculation") },
                revalidate = { ready ->
                    validations.incrementAndGet()
                    assertTrue(current.trusted && current.stableFrames >= 2)
                    (ready as LabyrinthEventChoiceDecision.Select).copy(buttonRect = current.choices.first().buttonRect)
                }) as LabyrinthEventChoiceDecision.Select
            assertEquals(changedRect, decision.buttonRect)
            assertEquals(1, validations.get())
        } finally { fixture.close(work) }
    }

    @Test
    fun lossOfTrustWithoutAnExplicitExpiryHoldDoesNotRetainInFlightWork() = runBlocking {
        val fixture = Fixture()
        val confirmed = observation()
        val work = BlockedCalculation(selected(confirmed))
        try {
            assertWait(fixture.update(key(confirmed), confirmed, visual(), 1_000L, work::calculate))
            work.entered.awaitChecked()
            val candidate = confirmed.copy(trusted = false, stableFrames = 1)
            assertWait(fixture.update(key(candidate), candidate, visual(), 1_500L, work::calculate))
            assertTrue("only an explicit TTL refresh may retain a lost-trust calculation",
                requireNotNull(work.token.get()).isCancelled)
            withContext(fixture.control) {
                assertTrue("cancelled Future is not actual quiescence", fixture.lane.busy)
                assertFalse(fixture.lane.hasReady)
                assertNull(fixture.lane.heldObservation)
            }
            work.release.countDown()
            fixture.awaitState { !it.busy }
            withContext(fixture.control) { assertFalse(fixture.lane.hasReady) }
        } finally { fixture.close(work) }
    }

    @Test
    fun transientFailureIsNotCachedAsWaitAndTheSameConfirmedKeyRetries() = runBlocking {
        val fixture = Fixture()
        val confirmed = observation()
        val semantic = key(confirmed)
        val attempts = AtomicInteger()
        try {
            assertWait(fixture.update(semantic, confirmed, visual(), 1_000L, calculate = {
                attempts.incrementAndGet()
                throw IllegalStateException("transient event evaluation failure")
            }))
            fixture.awaitState { !it.busy && it.diagnostics().completedCount == 1L }
            withContext(fixture.control) {
                assertFalse(fixture.lane.hasReady)
                assertTrue(requireNotNull(fixture.lane.diagnostics().lastFailure).contains("transient"))
            }
            fixture.clock.set(2_000L)
            assertWait(fixture.update(semantic, confirmed, visual(), 2_000L, calculate = {
                attempts.incrementAndGet(); selected(confirmed)
            }))
            fixture.awaitState { !it.busy && it.hasReady }
            val freshAt = withContext(fixture.control) { fixture.lane.readyAt + 1L }
            val result = fixture.update(semantic, confirmed, visual(), freshAt,
                calculate = { error("successful retry must now be cached") })
            assertTrue(result is LabyrinthEventChoiceDecision.Select)
            assertEquals(2, attempts.get())
            withContext(fixture.control) {
                assertEquals(2L, fixture.lane.diagnostics().submittedCount)
                assertNull(fixture.lane.diagnostics().lastFailure)
            }
        } finally { fixture.close() }
    }

    @Test
    fun changedSemanticInputWaitsForActualExitBeforeTheReplacementStarts() = runBlocking {
        val fixture = Fixture()
        val confirmed = observation()
        val firstKey = key(confirmed)
        val replacementKey = key(confirmed, acquired = listOf("r1", "r2", "new-role"))
        val old = BlockedCalculation(selected(confirmed).copy(explanation = "obsolete"))
        val new = BlockedCalculation(selected(confirmed).copy(explanation = "replacement"), old.exited)
        try {
            assertWait(fixture.update(firstKey, confirmed, visual(), 1_000L, old::calculate))
            old.entered.awaitChecked()
            withContext(fixture.control) {
                assertFalse(fixture.lane.holdExpired(replacementKey, visual(),
                    LabyrinthEntryPageState.EVENT_CHOICE, expiredDiagnostic()))
                assertTrue(fixture.lane.busy)
                assertNull(fixture.lane.heldObservation)
            }
            assertWait(fixture.update(replacementKey, confirmed, visual(), 1_500L, new::calculate))
            assertTrue(requireNotNull(old.token.get()).isCancelled)
            assertEquals(0, new.calls.get())
            assertFalse(old.exited.get())
            fixture.clock.set(2_000L)
            old.release.countDown()
            new.entered.awaitChecked()
            assertTrue(old.exited.get())
            withContext(fixture.control) {
                assertTrue(fixture.lane.busy)
                assertFalse(fixture.lane.hasReady)
            }
            fixture.clock.set(3_000L)
            new.release.countDown()
            fixture.awaitState { !it.busy && it.hasReady }
            val freshAt = withContext(fixture.control) { fixture.lane.readyAt + 1L }
            val result = fixture.update(replacementKey, confirmed, visual(), freshAt,
                calculate = { error("replacement ready result must survive old callbacks") })
                as LabyrinthEventChoiceDecision.Select
            assertEquals("replacement", result.explanation)
            assertEquals(1, old.calls.get())
            assertEquals(1, new.calls.get())
            withContext(fixture.control) {
                assertEquals(1L, fixture.lane.discarded)
                assertEquals(1L, fixture.lane.diagnostics().cancelledCount)
            }
        } finally { fixture.close(old, new) }
    }

    @Test
    fun pauseInvalidationWaitsForQuiescenceAndOldCompletionCannotOverrideResume() = runBlocking {
        val fixture = Fixture()
        val confirmed = observation()
        val semantic = key(confirmed)
        val old = BlockedCalculation(selected(confirmed).copy(explanation = "before-pause"))
        val resumed = BlockedCalculation(selected(confirmed).copy(explanation = "after-resume"), old.exited)
        try {
            assertWait(fixture.update(semantic, confirmed, visual(), 1_000L, old::calculate))
            old.entered.awaitChecked()
            withContext(fixture.control) {
                fixture.lane.invalidate("PAUSE")
                assertTrue(fixture.lane.busy)
                assertFalse(fixture.lane.hasReady)
                assertNull(fixture.lane.heldObservation)
                assertEquals("PAUSE", fixture.lane.diagnostics().lastCancellationReason)
            }
            assertWait(fixture.update(semantic, confirmed, visual(), 1_500L, resumed::calculate))
            assertEquals(0, resumed.calls.get())
            old.release.countDown()
            resumed.entered.awaitChecked()
            fixture.clock.set(3_000L)
            resumed.release.countDown()
            fixture.awaitState { it.hasReady && !it.busy }
            val freshAt = withContext(fixture.control) { fixture.lane.readyAt + 1L }
            val selected = fixture.update(semantic, confirmed, visual(), freshAt,
                calculate = { error("old callback must not clear resumed ready result") })
                as LabyrinthEventChoiceDecision.Select
            assertEquals("after-resume", selected.explanation)
            assertEquals(1, resumed.calls.get())
            withContext(fixture.control) { assertEquals(1L, fixture.lane.discarded) }
        } finally { fixture.close(old, resumed) }
    }

    @Test
    fun pageSceneDimensionsTitleCropAndLayoutChangesHardInvalidateExpiryHold() = runBlocking {
        val base = requireNotNull(visual())
        val variants = listOf(
            Triple(base.copy(sceneEpoch = 244L), expiredDiagnostic(), LabyrinthEntryPageState.EVENT_CHOICE),
            Triple(base.copy(frameWidth = 1920), expiredDiagnostic(), LabyrinthEntryPageState.EVENT_CHOICE),
            Triple(base.copy(frameHeight = 1080), expiredDiagnostic(), LabyrinthEntryPageState.EVENT_CHOICE),
            Triple(base.copy(fingerprint = "different-title"), expiredDiagnostic().copy(fingerprint = "different-title"), LabyrinthEntryPageState.EVENT_CHOICE),
            Triple(base.copy(cropRect = crop.copy(left = 282)), expiredDiagnostic().copy(cropRect = crop.copy(left = 282)), LabyrinthEntryPageState.EVENT_CHOICE),
            Triple(base.copy(layoutCount = 3), expiredDiagnostic().copy(completeLayoutCount = 3,
                buttonConfidencesByOptionCount = mapOf(3 to listOf(0.8, 0.8, 0.8))), LabyrinthEntryPageState.EVENT_CHOICE),
            Triple(base, expiredDiagnostic(), LabyrinthEntryPageState.UNKNOWN),
        )
        for ((currentVisual, diagnostic, page) in variants) {
            val fixture = Fixture()
            val confirmed = observation()
            val semantic = key(confirmed)
            val work = BlockedCalculation(selected(confirmed))
            try {
                assertWait(fixture.update(semantic, confirmed, base, 1_000L, work::calculate))
                work.entered.awaitChecked()
                withContext(fixture.control) {
                    assertFalse(fixture.lane.holdExpired(semantic, currentVisual, page, diagnostic))
                    assertTrue(fixture.lane.busy)
                    assertFalse(fixture.lane.hasReady)
                    assertNull(fixture.lane.heldObservation)
                }
                assertTrue(requireNotNull(work.token.get()).isCancelled)
                assertFalse(work.exited.get())
                work.release.countDown()
                fixture.awaitState { !it.busy }
                withContext(fixture.control) { assertFalse(fixture.lane.hasReady) }
            } finally { fixture.close(work) }
        }
    }

    @Test
    fun semanticKeyExcludesConfirmationAndGeometryButRetainsEveryComputationInput() {
        val confirmed = observation()
        val original = key(confirmed)
        val nonSemantic = confirmed.copy(
            trusted = false, stableFrames = 100, rawText = "different formatting",
            confidence = 0.8, rivalMargin = 0.2,
            choices = confirmed.choices.map { it.copy(
                buttonRect = it.buttonRect.copy(left = it.buttonRect.left + 1),
                enabledConfidence = 0.9, buttonConfidence = 0.9,
            ) },
        )
        assertEquals(original, key(nonSemantic))
        assertNotEquals(original, key(confirmed, sessionId = Any()))
        assertNotEquals(original, key(confirmed, generation = 2L))
        assertNotEquals(original, key(confirmed, sceneEpoch = 244L))
        assertNotEquals(original, key(confirmed, calculator = Any()))
        assertNotEquals(original, key(confirmed.copy(event = confirmed.event.copy(name = "another event"))))
        assertNotEquals(original, key(observation("1112")))
        assertNotEquals(original, key(confirmed.copy(choices = confirmed.choices.reversed())))
        assertNotEquals(original, key(confirmed.copy(choices = confirmed.choices.mapIndexed { index, item ->
            if (index == 0) item.copy(enabledConfidence = 0.349) else item
        })))
        assertNotEquals(original, key(confirmed.copy(choices = confirmed.choices.mapIndexed { index, item ->
            if (index == 0) item.copy(buttonConfidence = 0.119) else item
        })))
        assertNotEquals(original, key(confirmed, acquired = listOf("r2", "r1")))
        assertNotEquals(original, key(confirmed, acquired = listOf("r1", "r2", "r3")))
        assertNotEquals(original, key(confirmed, evaluationContext = context.copy(defenseMarkStacks = 2)))
        assertNotEquals(original, key(confirmed, evaluationContext = context.copy(targetCount = 2)))
        assertNotEquals(original, key(confirmed, relicStacks = stacks + (LabyrinthRelicMark.CRITICAL to 2)))
        assertNotEquals(original, key(confirmed, rejected = listOf(confirmed.choices.first().choice.id)))
    }

    @Test
    fun semanticKeySnapshotsMutableRosterStacksAndRejectedInputs() {
        val confirmed = observation()
        val acquired = mutableListOf("r1", "r2")
        val relicStacks = stacks.toMutableMap()
        val rejected = mutableListOf<String>()
        val snapshot = key(confirmed, acquired = acquired, relicStacks = relicStacks, rejected = rejected)
        val immutable = key(confirmed)
        acquired.add("new-role")
        relicStacks[LabyrinthRelicMark.CRITICAL] = 4
        rejected.add(confirmed.choices.first().choice.id)
        assertEquals(immutable, snapshot)
        assertNotEquals(snapshot, key(confirmed, acquired = acquired, relicStacks = relicStacks, rejected = rejected))
    }

    @Test
    fun dedicatedDiagnosticsExposeSubmissionCompletionAndActualExit() = runBlocking {
        val fixture = Fixture()
        val confirmed = observation()
        val work = BlockedCalculation(selected(confirmed))
        try {
            assertWait(fixture.update(key(confirmed), confirmed, visual(), 1_000L, work::calculate))
            work.entered.awaitChecked()
            withContext(fixture.control) {
                val diagnostic = fixture.lane.diagnostics()
                assertEquals("2315", diagnostic.eventId)
                assertEquals(1L, diagnostic.requestCount)
                assertEquals(1L, diagnostic.submittedCount)
                assertEquals(1_000L, diagnostic.submittedAtMillis)
                assertEquals(1_000L, diagnostic.startedAtMillis)
                assertTrue(diagnostic.active)
                assertFalse(diagnostic.ready)
            }
            fixture.clock.set(2_000L)
            work.release.countDown()
            fixture.awaitState { it.hasReady && !it.busy }
            withContext(fixture.control) {
                val diagnostic = fixture.lane.diagnostics()
                assertEquals(1L, diagnostic.completedCount)
                assertEquals(2_000L, diagnostic.endedAtMillis)
                assertEquals(2_000L, diagnostic.resultAtMillis)
                assertEquals(2_000L, diagnostic.quiescedAtMillis)
                assertEquals(2_000L, diagnostic.readyAtMillis)
                assertFalse(diagnostic.active)
                assertTrue(diagnostic.ready)
                assertEquals(0L, diagnostic.discardedCount)
            }
        } finally { fixture.close(work) }
    }

    @Test
    fun recorded177FramesRetainAll15ExpiriesWithOneCalculationPerEventAndNoStaleSelect() = runBlocking {
        val sequences = replaySequences()
        assertEquals(setOf("1112", "2315"), sequences.map { it.eventId }.toSet())
        assertEquals(177, sequences.sumOf { it.frames.size })
        var totalExpiryHolds = 0
        for (sequence in sequences) {
            val fixture = Fixture(sequence.frames.first().timestamp)
            val firstConfirmed = requireNotNull(sequence.frames.firstOrNull { it.observation?.trusted == true })
            val confirmed = requireNotNull(firstConfirmed.observation)
            val work = BlockedCalculation(selected(confirmed))
            val validations = AtomicInteger()
            var started = false
            var expiryHolds = 0
            try {
                for (frame in sequence.frames) {
                    fixture.clock.set(frame.timestamp + 1L)
                    val visual = LabyrinthEventVisualIdentity.from(frame.sceneEpoch, frame.frameWidth,
                        frame.frameHeight, frame.diagnostic)
                    val current = frame.observation
                    if (current != null) {
                        assertWait(fixture.update(key(current, sceneEpoch = frame.sceneEpoch), current,
                            visual, frame.timestamp, work::calculate,
                            revalidate = { validations.incrementAndGet(); error("blocked calculation cannot select") }))
                        if (!started && current.trusted && current.stableFrames >= 2) {
                            work.entered.awaitChecked()
                            started = true
                        }
                    } else {
                        assertEquals("EXPIRED", frame.diagnostic?.cache?.status)
                        val held = withContext(fixture.control) { requireNotNull(fixture.lane.heldObservation) }
                        val semantic = key(held, sceneEpoch = frame.sceneEpoch)
                        withContext(fixture.control) {
                            assertTrue("recorded expiry at " + frame.timestamp,
                                fixture.lane.holdExpired(semantic, visual,
                                    LabyrinthEntryPageState.EVENT_CHOICE, frame.diagnostic))
                            assertEquals("OCR_REFRESH_WAIT", fixture.lane.diagnostics().phase)
                            assertTrue(fixture.lane.busy)
                        }
                        expiryHolds++
                    }
                }
                assertTrue(started)
                assertEquals(if (sequence.eventId == "1112") 10 else 5, expiryHolds)
                totalExpiryHolds += expiryHolds
                assertEquals(1, work.calls.get())
                assertEquals(0, validations.get())
                assertFalse(requireNotNull(work.token.get()).isCancelled)
                withContext(fixture.control) {
                    assertEquals(1L, fixture.lane.diagnostics().submittedCount)
                    assertEquals(expiryHolds.toLong(), fixture.lane.diagnostics().ocrRefreshRetainedCount)
                    assertEquals(0L, fixture.lane.diagnostics().cancelledCount)
                }
                val last = sequence.frames.last()
                fixture.clock.set(last.timestamp + 10_000L)
                work.release.countDown()
                fixture.awaitState { it.hasReady && !it.busy }
                val readyAt = withContext(fixture.control) { fixture.lane.readyAt }
                val lastCandidate = requireNotNull(last.observation)
                assertFalse(lastCandidate.trusted)
                val semantic = key(confirmed, sceneEpoch = last.sceneEpoch)
                val lastVisual = LabyrinthEventVisualIdentity.from(last.sceneEpoch, last.frameWidth,
                    last.frameHeight, last.diagnostic)
                assertWait(fixture.update(key(lastCandidate, sceneEpoch = last.sceneEpoch), lastCandidate,
                    lastVisual, readyAt + 1L, calculate = { error("stable one must not restart") },
                    revalidate = { error("stable one cannot receive ready selection") }))
                val fresh = lastCandidate.copy(trusted = true, stableFrames = 2)
                listOf<Long?>(null, readyAt - 1L, readyAt).forEach { capturedAt ->
                    assertWait(fixture.update(semantic, fresh, lastVisual, capturedAt,
                        calculate = { error("recorded same-event result must be reused") },
                        revalidate = { error("old captured frame cannot receive ready selection") }))
                }
                val decision = fixture.update(semantic, fresh, lastVisual, readyAt + 1L,
                    calculate = { error("fresh revalidation must reuse the one calculation") },
                    revalidate = { ready ->
                        validations.incrementAndGet()
                        assertTrue(fresh.trusted && fresh.stableFrames >= 2)
                        val value = ready as LabyrinthEventChoiceDecision.Select
                        val button = fresh.choices.single { it.choice.id == value.choiceId }
                        assertTrue(button.enabledConfidence >= 0.35 && button.buttonConfidence >= 0.12)
                        value.copy(buttonRect = button.buttonRect)
                    }) as LabyrinthEventChoiceDecision.Select
                assertEquals(sequence.eventId, decision.eventId)
                assertTrue(decision.actionSafe)
                assertEquals(1, validations.get())
                assertEquals(1, work.calls.get())
                println("EVENT_CHAIN_REPLAY_JSON=" + buildJsonObject {
                    put("eventId", sequence.eventId)
                    put("frames", sequence.frames.size)
                    put("expiryHolds", expiryHolds)
                    put("calculationCalls", work.calls.get())
                    put("observedWorkers", LabyrinthFourThreadRuntime.cpuWorkerBudget)
                    put("freshActionSafe", decision.actionSafe)
                })
            } finally { fixture.close(work) }
        }
        assertEquals(15, totalExpiryHolds)
    }

    private class Fixture(initialTime: Long = 1_000L) {
        val control = LabyrinthFourThreadRuntime.controlDispatcher
        private val job = SupervisorJob()
        private val changes = Channel<Unit>(Channel.CONFLATED)
        val clock = AtomicLong(initialTime)
        val lane = LabyrinthEventPlanningLane(CoroutineScope(job + control), clock::get) {
            assertTrue(LabyrinthFourThreadRuntime.isControlThread())
            changes.trySend(Unit)
        }

        suspend fun update(
            key: List<Any?>, observation: LabyrinthEventChoiceObservation,
            visual: LabyrinthEventVisualIdentity?, capturedAt: Long?,
            calculate: () -> LabyrinthEventChoiceDecision,
            revalidate: (LabyrinthEventChoiceDecision) -> LabyrinthEventChoiceDecision = { it },
        ): LabyrinthEventChoiceDecision = withContext(control) {
            lane.update(key, observation, visual, capturedAt, calculate, revalidate)
        }

        suspend fun awaitState(predicate: (LabyrinthEventPlanningLane) -> Boolean) = withTimeout(10_000L) {
            while (!withContext(control) { predicate(lane) }) changes.receive()
        }

        suspend fun close(vararg work: BlockedCalculation) {
            work.forEach { it.release.countDown() }
            withContext(control) { lane.invalidate("TEST_CLEANUP") }
            awaitState { !it.busy }
            job.cancel()
            changes.close()
            // Process-lifetime control dispatcher and compute executor are never closed by a test.
        }
    }

    private class BlockedCalculation(
        private val answer: LabyrinthEventChoiceDecision.Select,
        private val priorExited: AtomicBoolean? = null,
    ) {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val calls = AtomicInteger()
        val exited = AtomicBoolean()
        val token = AtomicReference<WorkToken>()

        fun calculate(): LabyrinthEventChoiceDecision {
            calls.incrementAndGet()
            assertTrue(LabyrinthFourThreadRuntime.isComputeThread())
            priorExited?.let { assertTrue("replacement started before old work actually exited", it.get()) }
            token.set(requireNotNull(LabyrinthFourThreadRuntime.currentToken()))
            entered.countDown()
            try {
                assertTrue("test calculation was never released", release.await(10, TimeUnit.SECONDS))
                return answer
            } finally { exited.set(true) }
        }
    }

    private fun CountDownLatch.awaitChecked() = assertTrue("CPU body never entered", await(10, TimeUnit.SECONDS))

    private fun assertWait(decision: LabyrinthEventChoiceDecision) =
        assertTrue("expected Wait, got " + decision, decision is LabyrinthEventChoiceDecision.Wait)

    private fun key(
        observation: LabyrinthEventChoiceObservation,
        sessionId: Any = session, generation: Long = 1L, sceneEpoch: Long = 243L,
        calculator: Any = planner, acquired: List<String> = listOf("r1", "r2"),
        evaluationContext: LabyrinthRoleDecisionContext = context,
        relicStacks: Map<LabyrinthRelicMark, Int> = stacks, rejected: List<String> = emptyList(),
    ): List<Any?> = labyrinthEventPlanningSemanticKey(sessionId, generation, sceneEpoch, calculator,
        observation, acquired, evaluationContext, relicStacks, rejected)

    private fun selected(observation: LabyrinthEventChoiceObservation): LabyrinthEventChoiceDecision.Select {
        val option = observation.choices.first()
        return LabyrinthEventChoiceDecision.Select(observation.event.id, option.choice.id,
            option.choice.name, option.buttonRect, 10.0, "test semantic calculation", true)
    }

    private fun observation(eventId: String = "2315"): LabyrinthEventChoiceObservation {
        val event = if (eventId == "1112") EventResource(
            id = eventId, name = "活动会场出现了魔物！", choices = listOf(
                EventChoiceResource(id = "11121", slot = 1, name = "见识下御久间流的 剑术吧！"),
                EventChoiceResource(id = "11122", slot = 2, name = "这里就 交给我们吧！"),
            ),
        ) else EventResource(
            id = eventId, name = "料理完成了！不过食材还有剩余哦！", choices = listOf(
                EventChoiceResource(id = "23152", slot = 1, name = "这样应该就 足够了吧？"),
                EventChoiceResource(id = "23151", slot = 2, name = "我还没 吃够呢☆", conditionType = 1),
            ),
        )
        return LabyrinthEventChoiceObservation(event, event.choices.mapIndexed { index, choice ->
            LabyrinthEventChoiceVisual(choice, EntryPixelRect(if (index == 0) 353 else 712, 540, 214, 115),
                if (index == 0) 0.7890625 else 0.78125, 0.59375)
        }, event.name, 1.0, 0.4, 2, true)
    }

    private fun expiredDiagnostic(): LabyrinthEventOcrDiagnostics = LabyrinthEventOcrDiagnostics(
        status = "TITLE_WAITING_OCR", cropRect = crop, fingerprint = "43759ca5d5c2138c",
        completeLayoutCount = 2, buttonConfidencesByOptionCount = mapOf(
            1 to listOf(0.125), 2 to listOf(0.7890625, 0.78125),
            3 to listOf(0.0234375, 0.125, 0.1640625),
        ),
        cache = LabyrinthEventOcrCacheDiagnostics("EXPIRED", 5_127L, 0, crop, 185L,
            null, null, 185L, 64L, 16, "TEXT", 5_000L), requestScheduled = true,
    )

    private fun visual(): LabyrinthEventVisualIdentity? =
        LabyrinthEventVisualIdentity.from(243L, 1280, 720, expiredDiagnostic())

    private data class ReplaySequence(val eventId: String, val frames: List<ReplayFrame>)
    private data class ReplayFrame(
        val timestamp: Long, val sceneEpoch: Long, val frameWidth: Int, val frameHeight: Int,
        val diagnostic: LabyrinthEventOcrDiagnostics?, val observation: LabyrinthEventChoiceObservation?,
    )

    private fun replaySequences(): List<ReplaySequence> {
        val text = requireNotNull(javaClass.getResourceAsStream("/event-chain-log-replay.json")) {
            "Missing recorded event-chain-log-replay.json; this regression must not silently skip"
        }.bufferedReader(Charsets.UTF_8).use { it.readText() }
        return Json.parseToJsonElement(text).jsonObject.getValue("sequences").jsonArray.map { element ->
            val sequence = element.jsonObject
            val eventId = requireNotNull(sequence.string("eventId"))
            val frames = sequence.getValue("frames").jsonArray.map { item ->
                val frame = item.jsonObject
                val diagnostic = parseDiagnostic(frame["eventOcr"] as? JsonObject)
                ReplayFrame(requireNotNull(frame.long("timestamp")), requireNotNull(frame.long("sceneEpoch")),
                    requireNotNull(frame.int("frameWidth")), requireNotNull(frame.int("frameHeight")),
                    diagnostic, parseObservation(eventId, frame["eventChoice"] as? JsonObject, diagnostic))
            }
            assertTrue(frames.zipWithNext().all { (a, b) -> b.timestamp > a.timestamp })
            ReplaySequence(eventId, frames)
        }
    }

    private fun parseDiagnostic(value: JsonObject?): LabyrinthEventOcrDiagnostics? {
        value ?: return null
        val cache = (value["cache"] as? JsonObject)?.let { cached ->
            LabyrinthEventOcrCacheDiagnostics(
                status = requireNotNull(cached.string("status")), ageMillis = cached.long("ageMillis"),
                fingerprintDistance = cached.int("fingerprintDistance"),
                cachedCropRect = rect(cached["cachedCrop"] as? JsonObject),
                cachedRequestId = cached.long("cachedRequestId"), pendingRequestId = cached.long("pendingRequestId"),
                pendingAgeMillis = cached.long("pendingAgeMillis"), lastCompletedRequestId = cached.long("lastCompletedRequestId"),
                lastRequestDurationMillis = cached.long("lastRequestDurationMillis"),
                lastResultTextLength = cached.int("lastResultTextLength"), lastResultStatus = cached.string("lastResultStatus"),
                cacheLifetimeMillis = requireNotNull(cached.long("lifetimeMillis")),
            )
        }
        val confidences = (value["buttonConfidencesByOptionCount"] as? JsonObject).orEmpty().map { (count, list) ->
            count.toInt() to list.jsonArray.map { it.jsonPrimitive.double }
        }.toMap()
        return LabyrinthEventOcrDiagnostics(
            status = requireNotNull(value.string("status")), cropRect = rect(value["crop"] as? JsonObject),
            fingerprint = value.string("fingerprint"), completeLayoutCount = value.int("completeLayoutCount"),
            buttonConfidencesByOptionCount = confidences, cache = cache,
            requestScheduled = value.boolean("requestScheduled") ?: false, rawText = value.string("rawText"),
            candidateEventId = value.string("candidateEventId"), candidateScore = value.double("candidateScore"),
            rivalMargin = value.double("rivalMargin"), stableFrames = value.int("stableFrames") ?: 0,
            trusted = value.boolean("trusted") ?: false,
        )
    }

    private fun parseObservation(
        eventId: String, value: JsonObject?, diagnostic: LabyrinthEventOcrDiagnostics?,
    ): LabyrinthEventChoiceObservation? {
        value ?: return null
        assertEquals(eventId, value.string("eventId"))
        val choices = value.getValue("choices").jsonArray.map { element ->
            val option = element.jsonObject
            val choice = EventChoiceResource(id = requireNotNull(option.string("choiceId")),
                name = requireNotNull(option.string("label")), slot = requireNotNull(option.int("slot")),
                conditionType = option.int("conditionType") ?: 0, conditionValue = option.int("conditionValue") ?: 0)
            LabyrinthEventChoiceVisual(choice, requireNotNull(rect(option["button"] as? JsonObject)),
                requireNotNull(option.double("buttonConfidence")), requireNotNull(option.double("enabledConfidence")))
        }
        val event = EventResource(id = eventId, name = observation(eventId).event.name,
            choices = choices.map { it.choice })
        return LabyrinthEventChoiceObservation(event, choices, diagnostic?.rawText.orEmpty(),
            diagnostic?.candidateScore ?: 0.0, diagnostic?.rivalMargin ?: 0.0,
            diagnostic?.stableFrames ?: 0, value.boolean("trusted") ?: false)
    }

    private fun rect(value: JsonObject?): EntryPixelRect? = value?.let {
        EntryPixelRect(requireNotNull(it.int("left")), requireNotNull(it.int("top")),
            requireNotNull(it.int("width")), requireNotNull(it.int("height")))
    }
    private fun JsonObject.string(name: String): String? = (this[name] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.long(name: String): Long? = (this[name] as? JsonPrimitive)?.longOrNull
    private fun JsonObject.int(name: String): Int? = (this[name] as? JsonPrimitive)?.intOrNull
    private fun JsonObject.double(name: String): Double? = (this[name] as? JsonPrimitive)?.doubleOrNull
    private fun JsonObject.boolean(name: String): Boolean? = (this[name] as? JsonPrimitive)?.booleanOrNull
}
