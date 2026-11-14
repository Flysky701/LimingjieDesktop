package com.landosol.toolbox.labyrinth.concurrent

import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

/** Hot-path behavior checks only; no benchmark timing, sleeps, Android, or extra coordinator pool. */
class Batch5RuntimeHotPathTest {
    private fun CountDownLatch.awaitChecked() = assertTrue(await(10, TimeUnit.SECONDS))

    @Test fun highestPriorityCooperateChecksCancellationWithoutHelpingSameTier() {
        for (kind in listOf(WorkKind.DETAILED_VISION, WorkKind.OBSERVATION)) {
            val pool = LabyrinthHelpingExecutor(threadPrefix = "batch5-top-tier")
            val blockerEntered = CountDownLatch(1)
            val blockerRelease = CountDownLatch(1)
            val parentEntered = CountDownLatch(1)
            val parentProceed = CountDownLatch(1)
            val parentExited = CountDownLatch(1)
            val quiet = CountDownLatch(1)
            val sameTierRan = AtomicInteger()
            val cancellationObserved = AtomicBoolean()
            val token = WorkToken()
            try {
                pool.submit(Callable { blockerEntered.countDown(); blockerRelease.awaitChecked() })
                blockerEntered.awaitChecked()
                val parent = pool.submitRoot(kind, token, {}, { quiet.countDown() }) {
                    try {
                        parentEntered.countDown()
                        parentProceed.awaitChecked()
                        repeat(100) { LabyrinthHelpingExecutor.cooperate() }
                        assertEquals(0, sameTierRan.get())
                        assertSame(token, LabyrinthHelpingExecutor.currentToken())
                        token.cancel()
                        try {
                            LabyrinthHelpingExecutor.cooperate()
                            fail("top-tier fast return must still observe root cancellation")
                        } catch (_: java.util.concurrent.CancellationException) {
                            cancellationObserved.set(true)
                        }
                    } finally { parentExited.countDown() }
                }
                parentEntered.awaitChecked()
                val sameTier = pool.submitRoot(
                    if (kind == WorkKind.DETAILED_VISION) WorkKind.OBSERVATION else WorkKind.DETAILED_VISION,
                    WorkToken(), {}, {},
                ) { sameTierRan.incrementAndGet() }
                parentProceed.countDown()
                parentExited.awaitChecked(); quiet.awaitChecked()
                assertTrue(cancellationObserved.get())
                assertTrue(parent.future.isCancelled)
                assertEquals(1, sameTier.future.get(10, TimeUnit.SECONDS))
            } finally {
                parentProceed.countDown(); blockerRelease.countDown(); pool.shutdownNow()
                assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS))
            }
        }
    }

    private data class Entry(val label: String, val kind: WorkKind, val child: Boolean)

    /** Reflect the existing private Context for depth boundaries, without adding a runtime seam/API. */
    @Suppress("UNCHECKED_CAST")
    private fun <T> withDepthAndKind(depth: Int, kind: WorkKind, body: () -> T): T {
        val currentField = LabyrinthHelpingExecutor::class.java.getDeclaredField("current").also { it.isAccessible = true }
        val local = currentField.get(null) as ThreadLocal<Any?>
        val previous = checkNotNull(local.get())
        val type = previous.javaClass
        fun field(name: String): Any? = type.getDeclaredField(name).also { it.isAccessible = true }.get(previous)
        val constructor = type.declaredConstructors.first { it.parameterCount == 5 && !it.isSynthetic }
            .also { it.isAccessible = true }
        local.set(constructor.newInstance(field("executor"), field("task"), field("scope"), kind, depth))
        return try { body() } finally { local.set(previous) }
    }

    @Test fun heapHeadAndDepthLimitedChildScanMatchPrioritySequenceReferenceAtEveryTier() {
        val roots = listOf(
            Entry("root-diagnostic", WorkKind.DIAGNOSTIC, false),
            Entry("root-detailed", WorkKind.DETAILED_VISION, false),
            Entry("root-observation", WorkKind.OBSERVATION, false),
            Entry("root-planning", WorkKind.PLANNING, false),
        )
        val children = listOf(
            Entry("child-diagnostic", WorkKind.DIAGNOSTIC, true),
            Entry("child-planning-1", WorkKind.PLANNING, true),
            Entry("child-observation", WorkKind.OBSERVATION, true),
            Entry("child-detailed", WorkKind.DETAILED_VISION, true),
            Entry("child-planning-2", WorkKind.PLANNING, true),
        )
        for (depth in listOf(1, 15, 16, 20)) {
            for (exclusive in listOf(0, 1, 2, Int.MAX_VALUE)) {
                val pool = LabyrinthHelpingExecutor(threadPrefix = "batch5-depth-$depth-$exclusive")
                val blockerEntered = CountDownLatch(1)
                val blockerRelease = CountDownLatch(1)
                val parentEntered = CountDownLatch(1)
                val parentProceed = CountDownLatch(1)
                val observed = java.util.Collections.synchronizedList(ArrayList<String>())
                try {
                    pool.submit(Callable { blockerEntered.countDown(); blockerRelease.awaitChecked() })
                    blockerEntered.awaitChecked()
                    val parent = pool.submitRoot(WorkKind.PLANNING, WorkToken(), {}, {}) {
                        parentEntered.countDown()
                        parentProceed.awaitChecked()
                        // Every child inherits the same parent scope/token; only inherited kind
                        // is varied to exercise child priority ties underneath an ineligible root.
                        children.forEach { entry -> withDepthAndKind(depth, entry.kind) {
                            pool.submit(Callable { observed.add(entry.label) })
                        } }
                        val helpOne = pool.javaClass.getDeclaredMethod("helpOne", Int::class.javaPrimitiveType)
                            .also { it.isAccessible = true }
                        withDepthAndKind(depth, WorkKind.PLANNING) {
                            while (helpOne.invoke(pool, exclusive) as Boolean) { /* drain only eligible tasks */ }
                        }
                        val scratch = pool.javaClass.getDeclaredField("depthScanBuffer").also { it.isAccessible = true }
                            .get(pool) as Array<*>
                        assertTrue("scratch must not retain queued frame/root references", scratch.all { it == null })
                        observed.toList()
                    }
                    parentEntered.awaitChecked()
                    val rootHandles = roots.map { entry -> pool.submitRoot(entry.kind, WorkToken(), {}, {}) {
                        observed.add(entry.label)
                    } }
                    parentProceed.countDown()
                    val expected = (roots + children).withIndex()
                        .filter { (_, entry) -> entry.kind.priority < exclusive && (depth < 16 || entry.child) }
                        .sortedWith(compareBy<IndexedValue<Entry>> { it.value.kind.priority }.thenBy { it.index })
                        .map { it.value.label }
                    assertEquals("depth=$depth exclusive=$exclusive", expected, parent.future.get(10, TimeUnit.SECONDS))
                    blockerRelease.countDown()
                    rootHandles.forEach { it.future.get(10, TimeUnit.SECONDS) }
                } finally {
                    parentProceed.countDown(); blockerRelease.countDown(); pool.shutdownNow()
                    assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS))
                }
            }
        }
    }
}
