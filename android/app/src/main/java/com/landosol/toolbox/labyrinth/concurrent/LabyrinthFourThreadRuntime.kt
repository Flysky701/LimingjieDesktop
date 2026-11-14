package com.landosol.toolbox.labyrinth.concurrent

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import kotlin.coroutines.CoroutineContext

/** Future completion and actual resource-safe exit are deliberately separate signals. */
class WorkHandle<T> internal constructor(
    val future: Future<T>,
    val token: WorkToken,
    private val scope: WorkScope,
) {
    private val result = CompletableDeferred<T>()
    internal fun complete(outcome: Result<T>) {
        outcome.fold({ result.complete(it) }, { result.completeExceptionally(it) })
    }
    /** Suspends T2 without blocking it; cancellation also cancels the root and its children. */
    suspend fun await(): T = try { result.await() } catch (cancelled: CancellationException) {
        cancel(false)
        throw cancelled
    }
    /** Never interrupt a worker: it may be helping a different root with a different lease. */
    fun cancel(mayInterruptIfRunning: Boolean = false): Boolean {
        val cancelledToken = token.cancel()
        val cancelledFuture = future.cancel(false)
        return cancelledToken || cancelledFuture
    }
    /** Exactly once per registration, including queued cancel/reject; running cancel waits for exit. */
    fun onQuiesced(callback: () -> Unit) = scope.onQuiesced(callback)
    val isDone: Boolean get() = future.isDone
}

/**
 * Batch5 main business chain: existing capture T1 + dedicated control T2 + two CPU workers.
 * SDK/Room/network/Android helper threads are outside this business-thread budget.
 * Process lifetime object: do not close these dispatchers or shut down the shared executor.
 */
object LabyrinthFourThreadRuntime {
    private val controlExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread({
            LabyrinthHelpingExecutor.controlThread.set(true)
            try { runnable.run() } finally { LabyrinthHelpingExecutor.controlThread.remove() }
        }, "labyrinth-b5-control").apply { isDaemon = true }
    }
    val controlDispatcher: CoroutineDispatcher = controlExecutor.asCoroutineDispatcher()
    private val sharedCompute = LabyrinthHelpingExecutor()
    val computeExecutor: ExecutorService = sharedCompute
    // Do not use Executor.asCoroutineDispatcher for the bounded CPU queue: its rejection fallback
    // dispatches to Dispatchers.IO, silently breaking the two-worker budget.
    val computeDispatcher: CoroutineDispatcher = object : CoroutineDispatcher() {
        override fun dispatch(context: CoroutineContext, block: Runnable) = sharedCompute.execute(block)
    }
    fun isControlThread(): Boolean = LabyrinthHelpingExecutor.controlThread.get() == true
    fun isComputeThread(): Boolean = LabyrinthHelpingExecutor.isComputeThread()
    fun currentToken(): WorkToken? = LabyrinthHelpingExecutor.currentToken()
    fun checkpoint() = LabyrinthHelpingExecutor.checkpoint()
    fun cooperate() = LabyrinthHelpingExecutor.cooperate()

    /**
     * callbacks are inline, NOT implicitly T2-dispatched. Post state commits/resource bookkeeping
     * to controlDispatcher. Root body and its sync children must not suspend or write session state.
     * Full queue throws RejectedExecutionException AFTER onResult(cancel) and onQuiesced.
     */
    fun <T> submitWork(
        kind: WorkKind,
        token: WorkToken = WorkToken(),
        onResult: (Result<T>) -> Unit = {},
        onQuiesced: () -> Unit = {},
        body: () -> T,
    ): WorkHandle<T> = sharedCompute.submitRoot(kind, token, onResult, onQuiesced, body)
}
