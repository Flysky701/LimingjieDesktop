package com.landosol.toolbox.labyrinth.concurrent

import java.util.PriorityQueue
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.Callable
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import java.util.concurrent.Future
import java.util.concurrent.FutureTask
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.RunnableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Owns exactly workerCount CPU workers. Queue capacity is a hard bound; rejection is never CallerRuns.
 * Future.get/invokeAll on a CPU worker help queued CPU tasks instead of requiring an extra worker.
 * Only this executor's tasks are helped. Every help saves/restores the complete root context.
 */
class LabyrinthHelpingExecutor(
    val queueCapacity: Int = 64,
    private val threadPrefix: String = "labyrinth-b5-compute",
    val workerCount: Int = 2,
) : AbstractExecutorService() {
    init { require(queueCapacity > 0); require(workerCount > 0) }
    private val lock = ReentrantLock()
    private val changed = lock.newCondition()
    private val sequence = AtomicLong()
    private val queue = PriorityQueue<WorkFuture<*>>(compareBy<WorkFuture<*>> { it.kind.priority }.thenBy { it.sequence })
    // Rare depth-limited scans reuse this bounded array instead of allocating an iterator/Sequence.
    // Accessed only under lock; every copied slot is cleared before leaving the scan.
    private val depthScanBuffer = arrayOfNulls<WorkFuture<*>>(queueCapacity)
    private var stopping = false
    private var workersExited = 0
    private val workers = (1..workerCount).map { index ->
        Thread({ workerLoop() }, "$threadPrefix-$index").apply { isDaemon = true }
    }.also { threads -> threads.forEach(Thread::start) }

    private data class Context(
        val executor: LabyrinthHelpingExecutor,
        val task: WorkFuture<*>?,
        val scope: WorkScope?,
        val kind: WorkKind,
        val depth: Int,
    )

    companion object {
        private val current = ThreadLocal<Context?>()
        internal val controlThread = ThreadLocal<Boolean>()
        fun isComputeThread(): Boolean = current.get() != null
        fun currentToken(): WorkToken? = current.get()?.scope?.token
        fun checkpoint() {
            val context = current.get() ?: return
            context.scope?.token?.checkpoint()
            if (context.task?.isCancelled == true) throw CancellationException("Labyrinth CPU child cancelled")
        }
        fun cooperate() {
            val context = current.get() ?: return
            checkpoint()
            // Highest-priority CPU work has nothing it can preempt; keep frequent vision/gradient
            // checkpoints off the queue/iterator/comparator path entirely.
            if (context.kind.priority == 0) return
            // Only strictly higher-priority work preempts a loop; same-priority single-flight
            // matchers are never recursively entered merely because a loop cooperated.
            context.executor.helpOne(context.kind.priority)
            checkpoint()
        }
    }

    val queuedTaskCount: Int get() = lock.withLock { queue.size }

    private fun workerLoop() {
        current.set(Context(this, null, null, WorkKind.PLANNING, 0))
        try {
            while (true) {
                val task = lock.withLock {
                    while (queue.isEmpty() && !stopping) changed.await()
                    if (queue.isEmpty() && stopping) null else queue.poll()
                } ?: break
                task.run()
            }
        } finally {
            current.remove()
            lock.withLock { workersExited++; changed.signalAll() }
        }
    }

    private fun inheritedScope(): WorkScope = current.get()?.takeIf { it.executor === this }?.scope
        ?: WorkScope(WorkToken())
    private fun inheritedKind(): WorkKind = current.get()?.takeIf { it.executor === this }?.kind ?: WorkKind.PLANNING

    override fun <T> newTaskFor(callable: Callable<T>): RunnableFuture<T> =
        WorkFuture(callable, inheritedScope(), inheritedKind())
    override fun <T> newTaskFor(runnable: Runnable, value: T): RunnableFuture<T> =
        newTaskFor(Callable { runnable.run(); value })

    override fun execute(command: Runnable) {
        val task = if (command is LabyrinthHelpingExecutor.WorkFuture<*> && command.owner === this) command
            else newTaskFor(command, Unit) as WorkFuture<*>
        task.attachCancellation()
        val rejected = lock.withLock {
            if (task.isDone) false
            else if (stopping || queue.size >= queueCapacity) true
            else { queue.add(task); changed.signalAll(); false }
        }
        if (rejected) {
            task.cancel(false)
            throw RejectedExecutionException("Labyrinth CPU queue full or shut down (capacity=$queueCapacity)")
        }
    }

    internal fun <T> submitRoot(
        kind: WorkKind,
        token: WorkToken,
        onResult: (Result<T>) -> Unit,
        onQuiesced: () -> Unit,
        body: () -> T,
    ): WorkHandle<T> {
        val scope = WorkScope(token)
        val task = WorkFuture(Callable { token.checkpoint(); body() }, scope, kind)
        val handle = WorkHandle(task, token, scope)
        task.onCompleted { outcome -> handle.complete(outcome); runCatching { onResult(outcome) } }
        scope.onQuiesced(onQuiesced)
        execute(task) // On rejection cancel+quiescence run before the exception reaches the caller.
        return handle
    }

    private fun signalCompletion(task: WorkFuture<*>) = lock.withLock {
        queue.remove(task)
        changed.signalAll()
    }

    private fun helpOne(priorityExclusive: Int = Int.MAX_VALUE): Boolean {
        val context = current.get() ?: return false
        if (context.executor !== this) return false
        val next = lock.withLock {
            if (context.depth < 16) {
                // The heap head is already the minimum (priority, sequence), and all tasks are
                // eligible at ordinary depths. No scan or per-check comparator allocation.
                val head = queue.peek()
                if (head != null && head.kind.priority < priorityExclusive) queue.poll() else null
            } else {
                // At the root-depth limit exclude roots but still help synchronous CPU children.
                // PriorityQueue.iterator allocates, so copy into one capacity-sized reusable array
                // and scan by index. Hard queue bounds guarantee toArray returns that same array.
                val count = queue.size
                queue.toArray(depthScanBuffer)
                var candidate: WorkFuture<*>? = null
                for (index in 0 until count) {
                    val item = checkNotNull(depthScanBuffer[index])
                    depthScanBuffer[index] = null
                    if (item.isChild && item.kind.priority < priorityExclusive) {
                        val best = candidate
                        if (best == null || item.kind.priority < best.kind.priority ||
                            (item.kind.priority == best.kind.priority && item.sequence < best.sequence)) {
                            candidate = item
                        }
                    }
                }
                if (candidate != null) queue.remove(candidate)
                candidate
            }
        } ?: return false
        next.run()
        return true
    }

    private fun <T> helpingGet(task: WorkFuture<T>, timeoutNanos: Long?): T {
        check(controlThread.get() != true) { "T2 must use WorkHandle.await/callback, never blocking Future.get" }
        val context = current.get()
        if (context?.executor !== this) return if (timeoutNanos == null) task.rawGet()
            else task.rawGet(timeoutNanos, TimeUnit.NANOSECONDS)
        check(context.task !== task) { "A CPU task cannot join itself" }
        val deadline = timeoutNanos?.let { System.nanoTime() + it.coerceAtLeast(0) }
        while (!task.isDone) {
            checkpoint()
            if (deadline != null && deadline - System.nanoTime() <= 0) throw TimeoutException()
            if (helpOne()) continue
            lock.withLock {
                checkpoint() // A local cancellation may have signalled just before acquiring the lock.
                // Completion and enqueue both signal this condition. Recheck under its lock:
                // no sleep polling and no missed wakeup between empty queue and Future.done.
                val runnablePending = queue.any {
                    context.depth < 16 || it.isChild
                }
                if (!task.isDone && !runnablePending) {
                    if (deadline == null) changed.await()
                    else {
                        val remaining = deadline - System.nanoTime()
                        if (remaining <= 0) throw TimeoutException()
                        changed.awaitNanos(remaining)
                    }
                }
            }
        }
        return task.rawGet()
    }

    // AbstractExecutorService.invokeAll waits using its default FutureTask; explicitly override
    // both variants so all joins are helping joins and partial enqueue/rejection cancels siblings.
    override fun <T> invokeAll(tasks: MutableCollection<out Callable<T>>): MutableList<Future<T>> {
        check(controlThread.get() != true) { "T2 cannot call synchronous invokeAll" }
        val futures = ArrayList<Future<T>>(tasks.size)
        var completed = false
        try {
            tasks.forEach { futures.add(submit(it)) }
            futures.forEach { future ->
                try { future.get() } catch (_: CancellationException) {} catch (_: ExecutionException) {}
            }
            completed = true
            return futures
        } finally { if (!completed) futures.forEach { it.cancel(false) } }
    }

    override fun <T> invokeAll(
        tasks: MutableCollection<out Callable<T>>, timeout: Long, unit: TimeUnit,
    ): MutableList<Future<T>> {
        check(controlThread.get() != true) { "T2 cannot call synchronous invokeAll" }
        val deadline = System.nanoTime() + unit.toNanos(timeout)
        val futures = ArrayList<Future<T>>(tasks.size)
        var completed = false
        try {
            tasks.forEach { futures.add(newTaskFor(it)) }
            for (future in futures) {
                if (deadline - System.nanoTime() <= 0) return futures
                execute(future as Runnable)
            }
            for (future in futures) {
                if (!future.isDone) {
                    val remaining = deadline - System.nanoTime()
                    if (remaining <= 0) return futures
                    try { future.get(remaining, TimeUnit.NANOSECONDS) }
                    catch (_: CancellationException) {} catch (_: ExecutionException) {}
                    catch (_: TimeoutException) { return futures }
                }
            }
            completed = true
            return futures
        } finally { if (!completed) futures.forEach { it.cancel(false) } }
    }

    override fun shutdown() = lock.withLock { stopping = true; changed.signalAll() }
    override fun shutdownNow(): MutableList<Runnable> {
        val pending = lock.withLock {
            stopping = true
            queue.toList().also { queue.clear(); changed.signalAll() }
        }
        pending.forEach { it.cancel(false) }
        // No interrupt: CPU jobs cooperate via tokens, and interrupting an unrelated helped root
        // would contaminate its context. Test/local executors should cancel roots before shutdown.
        return pending.toMutableList<Runnable>()
    }
    override fun isShutdown(): Boolean = lock.withLock { stopping }
    override fun isTerminated(): Boolean = lock.withLock { workersExited == workerCount }
    override fun awaitTermination(timeout: Long, unit: TimeUnit): Boolean {
        var remaining = unit.toNanos(timeout)
        return lock.withLock {
            while (workersExited != workerCount) {
                if (remaining <= 0) return@withLock false
                remaining = changed.awaitNanos(remaining)
            }
            true
        }
    }

    internal inner class WorkFuture<T>(callable: Callable<T>, val scope: WorkScope, val kind: WorkKind) : FutureTask<T>(callable) {
        val owner: LabyrinthHelpingExecutor get() = this@LabyrinthHelpingExecutor
        val sequence = this@LabyrinthHelpingExecutor.sequence.getAndIncrement()
        val isChild = current.get()?.let { it.executor === owner && it.scope === scope } == true
        private val lifecycleLock = Any()
        private var state = 0 // 0 queued/not-started; 1 body executing; 2 actual exit
        private var detachCancellation: (() -> Unit)? = null
        private var outcome: Result<T>? = null
        private val completedCallbacks = ArrayList<(Result<T>) -> Unit>()
        init { scope.retain() }
        fun attachCancellation() {
            val detach = scope.token.listen { cancel(false) }
            synchronized(lifecycleLock) { if (state == 2) detach() else detachCancellation = detach }
        }
        fun onCompleted(callback: (Result<T>) -> Unit) {
            val ready = synchronized(lifecycleLock) {
                outcome.also { if (it == null) completedCallbacks.add(callback) }
            }
            if (ready != null) callback(ready)
        }
        override fun run() {
            val enter = synchronized(lifecycleLock) { if (state != 0) false else { state = 1; true } }
            if (!enter) return
            val previous = current.get()
            current.set(Context(owner, this, scope, kind, (previous?.depth ?: 0) + 1))
            try {
                if (scope.token.isCancelled) cancel(false)
                super.run()
            } finally {
                // Clear the frame/root TL BEFORE resource-release callbacks or another root.
                if (previous == null) current.remove() else current.set(previous)
                actualExit()
            }
        }
        override fun cancel(mayInterruptIfRunning: Boolean): Boolean {
            val accepted = super.cancel(false)
            if (accepted && !isChild) scope.token.cancel()
            return accepted
        }
        override fun done() {
            val result = try { Result.success(super.get()) }
                catch (error: CancellationException) { Result.failure<T>(error) }
                catch (error: ExecutionException) { Result.failure<T>(error.cause ?: error) }
            val callbacks = synchronized(lifecycleLock) {
                outcome = result
                completedCallbacks.toList().also { completedCallbacks.clear() }
            }
            signalCompletion(this)
            callbacks.forEach { callback -> runCatching { callback(result) } }
            val queued = synchronized(lifecycleLock) { state == 0 }
            if (queued) actualExit(onlyQueued = true)
        }
        private fun actualExit(onlyQueued: Boolean = false) {
            val release = synchronized(lifecycleLock) {
                if (state == 2 || (onlyQueued && state != 0)) false else {
                    state = 2
                    detachCancellation?.invoke()
                    detachCancellation = null
                    true
                }
            }
            if (release) scope.release()
        }
        fun rawGet(): T = super.get()
        fun rawGet(timeout: Long, unit: TimeUnit): T = super.get(timeout, unit)
        override fun get(): T = helpingGet(this, null)
        override fun get(timeout: Long, unit: TimeUnit): T = helpingGet(this, unit.toNanos(timeout))
    }
}
