package com.landosol.toolbox.labyrinth.vision

import com.landosol.toolbox.clanbattle.recognition.PixelImage
import com.landosol.toolbox.labyrinth.AdaptiveContextCheckingExecutor
import com.landosol.toolbox.labyrinth.runAdaptiveShardRoot
import com.landosol.toolbox.labyrinth.withAdaptiveShardExecutor
import com.landosol.toolbox.labyrinth.concurrent.LabyrinthFourThreadRuntime
import com.landosol.toolbox.labyrinth.concurrent.WorkKind
import com.landosol.toolbox.labyrinth.concurrent.WorkToken
import java.lang.reflect.Method
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicIntegerArray
import kotlin.random.Random
import org.junit.Assert.*
import org.junit.Test

class AdaptiveIconShardEquivalenceTest {
    @Test fun twoAndFourWorkersAndShardBudgetsPreserveEveryScoreBitAndCandidateIndexAtBoundaries() {
        val matcher = LabyrinthCharacterIconMatcher()
        val sizes = listOf(0, 1, 63, 64, 127, 128, 129, 191, 192, 193, 255, 256, 257, 511, 512, 795, 797)
        for (workers in listOf(2, 4)) withAdaptiveShardExecutor(workers, "icon-matrix") { pool, checked ->
            for (budget in listOf(1, 2, 4)) for (size in sizes) {
                val hits = AtomicIntegerArray(size)
                val token = WorkToken()
                val before = checked.executedShardCount.get()
                val expected = DoubleArray(size, ::scoreAt)
                val actual = runAdaptiveShardRoot(pool, token, WorkKind.DETAILED_VISION) {
                    val scores = matcher.scoreAllWithBudget(size, budget, checked) { index ->
                        assertSame(token, LabyrinthFourThreadRuntime.currentToken())
                        hits.incrementAndGet(index)
                        scoreAt(index)
                    }
                    assertSame(token, LabyrinthFourThreadRuntime.currentToken())
                    scores
                }
                assertRawScores("workers=$workers/budget=$budget/size=$size", expected, actual)
                expected.indices.forEach { assertEquals("every candidate is scored exactly once", 1, hits.get(it)) }
                val shards = if (size < 128) 1 else minOf(budget, size / 64).coerceAtLeast(1)
                assertEquals("the existing 128/64 thresholds must be retained", if (shards <= 1) 0 else shards,
                    checked.executedShardCount.get() - before)
            }
            assertTrue(checked.childThreadNames.all { it.startsWith("adaptive-icon-matrix-$workers-") })
            assertTrue(checked.childThreadNames.size <= workers)
        }
    }

    @Test fun realCoarseAndDetailedScoringCallbacksMatchSequentialBitsForTheSameInputs() {
        val images = (0 until 5).map { seed ->
            val random = Random(905 + seed)
            PixelImage(128, 128, IntArray(128 * 128) {
                (0xff shl 24) or (random.nextInt(256) shl 16) or
                    (random.nextInt(256) shl 8) or random.nextInt(256)
            })
        }
        val uniqueTemplates = images.mapIndexed { index, image ->
            LabyrinthBattleCharacterTemplate("role-$index", "Role $index", "31", image)
        }
        // The exact same candidate sequence is used for every worker/shard configuration.
        val templates = List(257) { uniqueTemplates[it % uniqueTemplates.size] }
        val matcher = LabyrinthCharacterIconMatcher(templates)
        // The observed frame is the first source image, using the same precompiled crop grids.
        val observedTemplate = matcher.pack.first()
        val masks = listOf(LabyrinthCharacterIconMask(),
            LabyrinthCharacterIconMask(ignoreTopLeft = true, ignoreBottomBand = true),
            LabyrinthCharacterIconMask(minYRatio = 0.2, maxYRatio = 0.9))
        for (mask in masks) for (detailed in listOf(false, true)) {
            val shape = if (detailed) IconSampleShape.FINE else IconSampleShape.COARSE
            val gridMask = method(matcher, "maskOf").invoke(matcher, mask, shape)
            val observedGrid = if (detailed) observedTemplate.fine else observedTemplate.coarse
            val observed = method(matcher, "center").invoke(matcher, observedGrid, gridMask)
            // As in match(), center observations before parallel scoring; template grids are immutable.
            val pack = matcher.pack.map { if (detailed) it.fine else it.coarse }
            val scorer = method(matcher, if (detailed) "iconScore" else "coarseIconScore")
            val score: (Int) -> Double = { index -> scorer.invoke(matcher, pack[index], observed, gridMask) as Double }
            val expected = DoubleArray(templates.size, score)
            for (workers in listOf(2, 4)) withAdaptiveShardExecutor(workers, "icon-real") { pool, checked ->
                for (budget in listOf(2, 4)) {
                    val actual = runAdaptiveShardRoot(pool, kind = WorkKind.DETAILED_VISION) {
                        matcher.scoreAllWithBudget(templates.size, budget, checked, score)
                    }
                    assertRawScores("real-$detailed-$mask-$workers-$budget", expected, actual)
                }
            }
        }
    }

    @Test fun completionOrderNeverChangesTheOriginalSubmissionOrderOfFourUnevenShards() {
        val matcher = LabyrinthCharacterIconMatcher()
        for (workers in listOf(2, 4)) withAdaptiveShardExecutor(workers, "icon-order") { pool, checked ->
            val tailDone = CountDownLatch(1)
            val completionOrder = ConcurrentLinkedQueue<Int>()
            val outOfOrder = object : ExecutorService by checked {
                override fun <T> invokeAll(tasks: MutableCollection<out Callable<T>>): MutableList<Future<T>> {
                    val tailIndex = tasks.size - 1
                    val reorderedCompletion = tasks.mapIndexed { index, task ->
                        Callable {
                            if (index == 0) assertTrue("tail shard must run while the first shard is waiting",
                                tailDone.await(10, TimeUnit.SECONDS))
                            try {
                                val result = task.call()
                                completionOrder.add(index)
                                result
                            } finally {
                                if (index == tailIndex) tailDone.countDown()
                            }
                        }
                    }
                    return checked.invokeAll(reorderedCompletion.toMutableList())
                }
            }
            val actual = runAdaptiveShardRoot(pool, kind = WorkKind.DETAILED_VISION) {
                matcher.scoreAllWithBudget(257, 4, outOfOrder, ::scoreAt)
            }
            assertRawScores("out-of-order-$workers", DoubleArray(257, ::scoreAt), actual)
            val completed = completionOrder.toList()
            assertEquals(4, completed.size)
            assertTrue("tail computation finishes before the first shard", completed.indexOf(3) < completed.indexOf(0))
            assertEquals(4, checked.executedShardCount.get())
        }
    }

    @Test fun unchangedPrivateEntryUsesTheSharedPoolAndPreservesTheRootToken() {
        val matcher = LabyrinthCharacterIconMatcher()
        val scoreAll = matcher.javaClass.getDeclaredMethod("scoreAll", Int::class.javaPrimitiveType,
            kotlin.jvm.functions.Function1::class.java).also { it.isAccessible = true }
        val token = WorkToken()
        val quiesced = CountDownLatch(1)
        val score: (Int) -> Double = { index ->
            assertTrue(LabyrinthFourThreadRuntime.isComputeThread())
            assertSame(token, LabyrinthFourThreadRuntime.currentToken())
            scoreAt(index)
        }
        val handle = LabyrinthFourThreadRuntime.submitWork(WorkKind.DETAILED_VISION, token,
            onQuiesced = quiesced::countDown) {
            scoreAll.invoke(matcher, 795, score) as DoubleArray
        }
        try {
            assertRawScores("production-alias", DoubleArray(795, ::scoreAt), handle.future.get(30, TimeUnit.SECONDS))
        } finally {
            if (!handle.future.isDone) handle.cancel(false)
            assertTrue(quiesced.await(30, TimeUnit.SECONDS))
        }
    }

    private fun method(matcher: LabyrinthCharacterIconMatcher, name: String): Method =
        matcher.javaClass.declaredMethods.single { it.name == name }.also { it.isAccessible = true }

    private fun assertRawScores(label: String, expected: DoubleArray, actual: DoubleArray) {
        assertEquals("$label/size", expected.size, actual.size)
        expected.indices.forEach { index ->
            assertEquals("$label/index=$index", expected[index].toRawBits(), actual[index].toRawBits())
        }
    }

    private fun scoreAt(index: Int): Double = when (index % 13) {
        0 -> -0.0
        1 -> 0.0
        2 -> Double.fromBits(0x7ff8000000000123L)
        3 -> Double.POSITIVE_INFINITY
        4 -> Double.NEGATIVE_INFINITY
        else -> (index - 400) * 0.03125
    }
}
