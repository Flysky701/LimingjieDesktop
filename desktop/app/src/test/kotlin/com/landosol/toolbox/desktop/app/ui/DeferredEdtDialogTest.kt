package com.landosol.toolbox.desktop.app.ui

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.ArrayDeque

class DeferredEdtDialogTest {
    @Test fun runDeferredOnEdtPostsOutsideCallerStackAndReturnsValue() = runBlocking {
        val pending = ArrayDeque<Runnable>()
        var executed = false
        val deferred = async {
            runDeferredOnEdt(schedule = { pending.addLast(it) }) {
                executed = true
                "selected.zip"
            }
        }
        // Let the coroutine reach suspension before flushing scheduled EDT work.
        kotlinx.coroutines.yield()
        assertFalse("Dialog block must not run inline on the Compose dispatch stack", executed)
        assertEquals(1, pending.size)

        pending.removeFirst().run()
        assertTrue(executed)
        assertEquals("selected.zip", deferred.await())
    }

    @Test fun runDeferredOnEdtPropagatesFailureAndHonorsPreExecutionCancel() = runBlocking {
        supervisorScope {
            val failingQueue = ArrayDeque<Runnable>()
            val failing = async {
                runDeferredOnEdt<String>(schedule = { failingQueue.addLast(it) }) {
                    error("dialog failed")
                }
            }
            kotlinx.coroutines.yield()
            failingQueue.removeFirst().run()
            val thrown = runCatching { failing.await() }.exceptionOrNull()
            assertEquals("dialog failed", thrown?.message)
        }

        val cancelledQueue = ArrayDeque<Runnable>()
        var cancelledCallback = false
        var bodyRan = false
        val cancelled = async {
            runDeferredOnEdt(
                schedule = { cancelledQueue.addLast(it) },
                onCancel = { cancelledCallback = true },
            ) {
                bodyRan = true
                "unused"
            }
        }
        kotlinx.coroutines.yield()
        cancelled.cancel(CancellationException("stop"))
        kotlinx.coroutines.yield()
        assertTrue(cancelledCallback)
        cancelledQueue.removeFirst().run()
        assertFalse(bodyRan)
    }
}
