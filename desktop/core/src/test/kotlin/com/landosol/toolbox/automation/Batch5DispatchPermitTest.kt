package com.landosol.toolbox.automation

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/** Exercises the permit at dispatch time using a backend with a controllable queue. */
class Batch5DispatchPermitTest {
    private class FakeGuardedBackend(
        private val result: AutomationBackendResult = AutomationBackendResult.Completed,
        private val callbackGate: CompletableDeferred<Unit>? = null,
    ) : AutomationActionBackend {
        val prepared = CompletableDeferred<Unit>()
        val enterMain = CompletableDeferred<Unit>()
        val submitted = CompletableDeferred<Unit>()
        val nativeCalls = AtomicInteger()
        override suspend fun execute(action: AutomationAction): AutomationBackendResult {
            nativeCalls.incrementAndGet()
            submitted.complete(Unit)
            callbackGate?.await()
            return result
        }
        override suspend fun executeGuarded(action: AutomationAction, permit: () -> Boolean): AutomationBackendResult {
            prepared.complete(Unit) // T2 has prepared/queued input, native main task has not run.
            enterMain.await()
            if (!permit()) return AutomationBackendResult.Rejected(AUTOMATION_PRE_DISPATCH_SUPPRESSED_REASON)
            nativeCalls.incrementAndGet()
            submitted.complete(Unit)
            callbackGate?.await()
            return result
        }
    }

    private suspend fun AutomationSessionManager.startSession(dryRun: Boolean = false): AutomationSession =
        (start(AutomationMode.LABYRINTH, dryRun) as AutomationSessionStartResult.Started).session

    @Test fun pauseStopOrEpochChangeAfterPreparationSuppressesNativeInputAndReturnsPaused() = runTest {
        for (change in listOf("pause", "stop", "epoch")) {
            val manager = AutomationSessionManager()
            val session = manager.startSession()
            val backend = FakeGuardedBackend()
            val executor = SessionBoundActionExecutor(manager, backend)
            val pauseRequested = AtomicBoolean(false)
            val stopRequested = AtomicBoolean(false)
            val lifecycleEpoch = AtomicLong(7)
            val expectedEpoch = lifecycleEpoch.get()
            // Capture only atomics and one scalar, not manager/session/bitmap.
            val permit = { !pauseRequested.get() && !stopRequested.get() && lifecycleEpoch.get() == expectedEpoch }
            val result = async { executor.executeWithPermit(session.id, AutomationAction.Back, permit) }
            backend.prepared.await()
            assertEquals(0, backend.nativeCalls.get())
            when (change) {
                "pause" -> pauseRequested.set(true)
                "stop" -> stopRequested.set(true)
                else -> lifecycleEpoch.incrementAndGet()
            }
            backend.enterMain.complete(Unit)
            assertEquals(AutomationActionResult.Paused, result.await())
            assertEquals(0, backend.nativeCalls.get())
        }
    }

    @Test fun submittedInputIsNotRetroactivelySuppressedWhenPermitChanges() = runTest {
        val manager = AutomationSessionManager()
        val session = manager.startSession()
        val callback = CompletableDeferred<Unit>()
        val backend = FakeGuardedBackend(callbackGate = callback)
        val executor = SessionBoundActionExecutor(manager, backend)
        val allowed = AtomicBoolean(true)
        val result = async { executor.executeWithPermit(session.id, AutomationAction.Back) { allowed.get() } }
        backend.prepared.await(); backend.enterMain.complete(Unit); backend.submitted.await()
        allowed.set(false) // Already native-submitted: do NOT rewrite its callback or cancel it.
        callback.complete(Unit)
        assertEquals(AutomationActionResult.Executed(AutomationAction.Back), result.await())
        assertEquals(1, backend.nativeCalls.get())
    }

    @Test fun guardedSystemRejectionIncludingSystemCancelledReasonStaysRejected() = runTest {
        for (reason in listOf("系统拒绝返回操作", "显示器 0 的无障碍手势被系统取消", "显示器 0 的无障碍手势回调超时")) {
            val manager = AutomationSessionManager()
            val session = manager.startSession()
            val backend = FakeGuardedBackend(AutomationBackendResult.Rejected(reason))
            val executor = SessionBoundActionExecutor(manager, backend)
            val result = async { executor.executeWithPermit(session.id, AutomationAction.Back) { true } }
            backend.prepared.await(); backend.enterMain.complete(Unit)
            assertEquals(AutomationActionResult.Rejected(AutomationAction.Back, reason), result.await())
            assertEquals(1, backend.nativeCalls.get())
        }
    }

    @Test fun legacySamAndReservedReasonKeepLegacyExecuteSemantics() = runTest {
        val manager = AutomationSessionManager()
        val session = manager.startSession()
        var calls = 0
        val backend = AutomationActionBackend {
            calls++
            AutomationBackendResult.Rejected(AUTOMATION_PRE_DISPATCH_SUPPRESSED_REASON)
        }
        val executor = SessionBoundActionExecutor(manager, backend)
        assertEquals(AutomationActionResult.Rejected(AutomationAction.Back, AUTOMATION_PRE_DISPATCH_SUPPRESSED_REASON),
            executor.execute(session.id, AutomationAction.Back))
        assertEquals(1, calls)
        // Default executeGuarded preserves the SAM: false must not enter its legacy body.
        assertEquals(AutomationActionResult.Paused, executor.executeWithPermit(session.id, AutomationAction.Back) { false })
        assertEquals(1, calls)
    }

    @Test fun allowedDefaultGuardAndLegacyExecutionStillCompleteNormally() = runTest {
        val manager = AutomationSessionManager()
        val session = manager.startSession()
        var calls = 0
        val executor = SessionBoundActionExecutor(manager) { calls++; AutomationBackendResult.Completed }
        val tap = AutomationAction.Tap(ScreenPoint(1f, 2f))
        assertEquals(AutomationActionResult.Executed(tap), executor.execute(session.id, tap))
        assertEquals(AutomationActionResult.Executed(tap), executor.executeWithPermit(session.id, tap) { true })
        assertEquals(2, calls)
    }

    @Test fun dryRunStaleAndPausedGatesDoNotEvaluatePermitOrCallBackend() = runTest {
        for (state in listOf("dry", "stale", "paused")) {
            val manager = AutomationSessionManager()
            val session = manager.startSession(dryRun = state == "dry")
            when (state) {
                "stale" -> manager.stop(session.id)
                "paused" -> manager.setPaused(session.id, true)
            }
            var backendCalls = 0
            var permitCalls = 0
            val executor = SessionBoundActionExecutor(manager) { backendCalls++; AutomationBackendResult.Completed }
            val expected = when (state) {
                "dry" -> AutomationActionResult.DryRun(AutomationAction.Back)
                "stale" -> AutomationActionResult.StaleSession
                else -> AutomationActionResult.Paused
            }
            assertEquals(expected, executor.executeWithPermit(session.id, AutomationAction.Back) { permitCalls++; false })
            assertEquals(expected, executor.execute(session.id, AutomationAction.Back))
            assertEquals(0, backendCalls)
            assertEquals(0, permitCalls)
        }
    }
}
