package com.landosol.toolbox.labyrinth

import com.landosol.toolbox.labyrinth.concurrent.LabyrinthFourThreadRuntime
import com.landosol.toolbox.labyrinth.concurrent.LabyrinthHelpingExecutor
import com.landosol.toolbox.labyrinth.concurrent.WorkKind
import com.landosol.toolbox.labyrinth.concurrent.WorkToken
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue

/** Local TEST executors only; production aliases and the process runtime are never replaced. */
internal class AdaptiveContextCheckingExecutor(
    private val delegate: ExecutorService,
) : ExecutorService by delegate {
    val executedShardCount = AtomicInteger()
    val childThreadNames = ConcurrentHashMap.newKeySet<String>()

    override fun <T> invokeAll(tasks: MutableCollection<out Callable<T>>): MutableList<Future<T>> {
        val parentToken = checkNotNull(LabyrinthFourThreadRuntime.currentToken())
        val checked = tasks.map { task ->
            Callable {
                assertTrue("a shard must execute on its owning CPU pool", LabyrinthFourThreadRuntime.isComputeThread())
                assertSame("a shard must inherit its root token", parentToken, LabyrinthFourThreadRuntime.currentToken())
                childThreadNames.add(Thread.currentThread().name)
                try {
                    task.call()
                } finally {
                    assertSame("helping another task must restore this shard's token", parentToken,
                        LabyrinthFourThreadRuntime.currentToken())
                    executedShardCount.incrementAndGet()
                }
            }
        }
        return delegate.invokeAll(checked)
    }
}

internal fun <T> withAdaptiveShardExecutor(
    workerCount: Int,
    label: String,
    body: (LabyrinthHelpingExecutor, AdaptiveContextCheckingExecutor) -> T,
): T {
    val executor = LabyrinthHelpingExecutor(threadPrefix = "adaptive-$label-$workerCount", workerCount = workerCount)
    try {
        return body(executor, AdaptiveContextCheckingExecutor(executor))
    } finally {
        executor.shutdown()
        assertTrue("all $workerCount test workers must exit", executor.awaitTermination(30, TimeUnit.SECONDS))
    }
}

internal fun <T> runAdaptiveShardRoot(
    executor: LabyrinthHelpingExecutor,
    token: WorkToken = WorkToken(),
    kind: WorkKind = WorkKind.PLANNING,
    body: () -> T,
): T {
    val quiesced = CountDownLatch(1)
    val handle = executor.submitRoot(kind, token, {}, quiesced::countDown, body)
    try {
        return handle.future.get(30, TimeUnit.SECONDS)
    } finally {
        if (!handle.future.isDone) handle.cancel(false)
        assertTrue("root and all scoring children must actually exit", quiesced.await(30, TimeUnit.SECONDS))
    }
}
