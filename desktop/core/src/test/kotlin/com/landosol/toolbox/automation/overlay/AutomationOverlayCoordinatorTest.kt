package com.landosol.toolbox.automation.overlay

import com.landosol.toolbox.automation.AutomationSessionId
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomationOverlayCoordinatorTest {
    @Test
    fun `failed control host rolls back attachment and allows a later retry`() = runTest {
        var fail = true
        val hidden = mutableListOf<AutomationSessionId>()
        val coordinator = AutomationOverlayCoordinator(object : AutomationOverlayHost {
            override fun show(sessionId: AutomationSessionId) { check(!fail) { "通知不可用" } }
            override fun hide(sessionId: AutomationSessionId) { hidden += sessionId }
        })
        val handler = object : AutomationOverlaySessionHandler {
            override suspend fun setPaused(paused: Boolean) = true
            override suspend fun stop() = true
        }
        val id = AutomationSessionId(9)
        assertFalse(coordinator.attach(presentation(id), handler))
        assertNull(coordinator.state.value)
        assertEquals(listOf(id), hidden)
        assertFalse(coordinator.setPaused(id, true))
        fail = false
        assertTrue(coordinator.attach(presentation(id), handler))
    }

    @Test
    fun `commands are routed to the attached session and detach hides host`() = runTest {
        val host = RecordingHost()
        val coordinator = AutomationOverlayCoordinator(host)
        val sessionId = AutomationSessionId(7)
        var pausedState = false
        var stopped = false
        val trackingHandler = object : AutomationOverlaySessionHandler {
            override suspend fun setPaused(paused: Boolean): Boolean {
                pausedState = paused
                return true
            }

            override suspend fun stop(): Boolean {
                stopped = true
                return true
            }
        }

        assertTrue(coordinator.attach(presentation(sessionId), trackingHandler))
        assertEquals(sessionId, coordinator.state.value?.sessionId)
        assertEquals(listOf(sessionId), host.shown)

        assertTrue(coordinator.setPaused(sessionId, true))
        assertTrue(pausedState)
        assertTrue(coordinator.state.value?.paused == true)

        assertTrue(coordinator.stop(sessionId))
        assertTrue(stopped)
        assertNull(coordinator.state.value)
        assertEquals(listOf(sessionId), host.hidden)
    }

    @Test
    fun `stale session cannot mutate active overlay`() = runTest {
        val coordinator = AutomationOverlayCoordinator()
        val active = AutomationSessionId(1)
        val stale = AutomationSessionId(2)
        val handler = object : AutomationOverlaySessionHandler {
            override suspend fun setPaused(paused: Boolean) = true
            override suspend fun stop() = true
        }
        assertTrue(coordinator.attach(presentation(active), handler))

        assertFalse(coordinator.setPaused(stale, true))
        assertFalse(coordinator.stop(stale))
        assertEquals(active, coordinator.state.value?.sessionId)
    }

    private fun presentation(sessionId: AutomationSessionId) = AutomationOverlayPresentation(
        sessionId = sessionId,
        title = "测试任务",
        status = "运行中",
        dryRun = true,
    )

    private class RecordingHost : AutomationOverlayHost {
        val shown = mutableListOf<AutomationSessionId>()
        val hidden = mutableListOf<AutomationSessionId>()

        override fun show(sessionId: AutomationSessionId) {
            shown += sessionId
        }

        override fun hide(sessionId: AutomationSessionId) {
            hidden += sessionId
        }
    }
}
