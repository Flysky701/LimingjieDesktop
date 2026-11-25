package com.landosol.toolbox.automation

import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.async
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomationSessionManagerTest {
    @Test
    fun `old session action is rejected after a new session starts`() = runTest {
        val manager = AutomationSessionManager(clock = { 123L })
        val backendCalls = mutableListOf<AutomationAction>()
        val executor = SessionBoundActionExecutor(manager) { action ->
            backendCalls += action
            AutomationBackendResult.Completed
        }
        val first = (manager.start(AutomationMode.LABYRINTH, dryRun = false) as AutomationSessionStartResult.Started)
            .session
        assertTrue(manager.stop(first.id))
        val second = (manager.start(AutomationMode.CLAN_BATTLE, dryRun = false) as AutomationSessionStartResult.Started)
            .session

        val result = executor.execute(first.id, AutomationAction.Back)

        assertEquals(AutomationActionResult.StaleSession, result)
        assertTrue(backendCalls.isEmpty())
        assertEquals(AutomationMode.CLAN_BATTLE, manager.current()?.mode)
        assertEquals(123L, second.startedAt)
    }

    @Test
    fun `dry run reports action without calling backend`() = runTest {
        val manager = AutomationSessionManager()
        var backendCalled = false
        val executor = SessionBoundActionExecutor(manager) {
            backendCalled = true
            AutomationBackendResult.Completed
        }
        val session = (manager.start(AutomationMode.LABYRINTH, dryRun = true) as AutomationSessionStartResult.Started)
            .session

        val result = executor.execute(session.id, AutomationAction.Tap(ScreenPoint(1f, 2f)))

        assertEquals(AutomationActionResult.DryRun(AutomationAction.Tap(ScreenPoint(1f, 2f))), result)
        assertTrue(!backendCalled)
    }

    @Test
    fun `paused session blocks actions until resumed`() = runTest {
        val manager = AutomationSessionManager()
        val executor = SessionBoundActionExecutor(manager) { AutomationBackendResult.Completed }
        val session = (manager.start(AutomationMode.LABYRINTH, dryRun = false) as AutomationSessionStartResult.Started)
            .session

        assertTrue(manager.setPaused(session.id, paused = true))
        assertEquals(AutomationActionResult.Paused, executor.execute(session.id, AutomationAction.Back))
        assertTrue(manager.setPaused(session.id, paused = false))
        assertEquals(AutomationActionResult.Executed(AutomationAction.Back), executor.execute(session.id, AutomationAction.Back))
    }

    @Test
    fun `only one automation session can run at a time`() = runTest {
        val manager = AutomationSessionManager()
        val first = (manager.start(AutomationMode.LABYRINTH, dryRun = true) as AutomationSessionStartResult.Started)
            .session

        val result = manager.start(AutomationMode.CLAN_BATTLE, dryRun = true)

        assertEquals(AutomationSessionStartResult.AlreadyRunning(first), result)
    }

    @Test
    fun `stop waits for in-flight action before another session can start`() = runTest {
        val manager = AutomationSessionManager()
        val actionStarted = CompletableDeferred<Unit>()
        val releaseAction = CompletableDeferred<Unit>()
        val executor = SessionBoundActionExecutor(manager) {
            actionStarted.complete(Unit)
            releaseAction.await()
            AutomationBackendResult.Completed
        }
        val session = (manager.start(AutomationMode.LABYRINTH, dryRun = false) as AutomationSessionStartResult.Started)
            .session

        val action = async { executor.execute(session.id, AutomationAction.Back) }
        actionStarted.await()
        val stop = async { manager.stop(session.id) }

        assertTrue(!stop.isCompleted)
        releaseAction.complete(Unit)
        assertEquals(AutomationActionResult.Executed(AutomationAction.Back), action.await())
        assertTrue(stop.await())
    }
}
