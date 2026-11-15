package com.landosol.toolbox.desktop.app.control

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WorkerReplyTest {
    @Test fun slowDeviceConnectionOutlivesOrdinaryRequestDeadline() = runTest {
        val connectionReply = CompletableDeferred<JsonObject>()
        val connection = async { awaitWorkerReply(ControlOps.DEVICE_CONNECT, connectionReply) }
        val ordinary = async { runCatching { awaitWorkerReply(ControlOps.DEVICE_DISCOVER, CompletableDeferred()) } }
        advanceTimeBy(240_000)
        runCurrent()
        assertFalse(connection.isCompleted)
        assertTrue(ordinary.await().exceptionOrNull() is TimeoutCancellationException)
        val ready = JsonObject(emptyMap())
        connectionReply.complete(ready)
        assertEquals(ready, connection.await())
    }

    @Test fun slowDeviceConnectionStillStopsOnCancellationOrTransportFailure() = runTest {
        val cancelled = async { awaitWorkerReply(ControlOps.DEVICE_CONNECT, CompletableDeferred()) }
        runCurrent()
        cancelled.cancelAndJoin()
        assertTrue(cancelled.isCancelled)

        val reply = CompletableDeferred<JsonObject>()
        val disconnected = async { runCatching { awaitWorkerReply(ControlOps.DEVICE_CONNECT, reply) } }
        advanceTimeBy(240_000)
        val failure = IllegalStateException("synthetic disconnected worker")
        reply.completeExceptionally(failure)
        val error = disconnected.await().exceptionOrNull()
        assertTrue(error is IllegalStateException)
        assertEquals(failure.message, error?.message)
    }
}
