package com.landosol.toolbox.labyrinth.node

import com.landosol.toolbox.clanbattle.recognition.PixelImage
import com.landosol.toolbox.labyrinth.node.batch4baseline.Batch4NodeBaselineReference
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Diagnostic host-JVM only. No Android timing, allocation, original slow-frame or speedup claim. */
class Batch4VisionAllocationBenchmarkTest {
    @Test fun coldMatcherAndWarmForcedFullScansEmitExactABBAAllocationGcAndTime() {
        val allocation = Batch4ThreadAllocationCounter.create()
        assumeTrue("Host JDK ThreadMXBean thread allocation measurement is required", allocation != null)
        val counter = requireNotNull(allocation)
        val templates = batch4NodeTemplates() // resident masked templates; decoding is NOT timed
        val frame = batch4ReadImage(batch4Fixture("node-link-20260914.png"))
        val cold = (0 until 2).map { index ->
            val old = Batch4NodeBaselineReference()
            val now = LabyrinthNodeClassifier()
            nodePair(counter, old, now, frame, templates, index % 2 == 0, clearTracking = false)
        }
        emit("real_golden_full_node_scan_cold_matcher_caches_resident_templates", frame, cold, 0,
            listOf("AB", "BA"), iterationsPerSample = 1)
        val baseline = Batch4NodeBaselineReference()
        val optimized = LabyrinthNodeClassifier()
        repeat(2) { index -> nodePair(counter, baseline, optimized, frame, templates,
            index % 2 == 0, clearTracking = true) }
        val hot = (0 until 4).map { index -> nodePair(counter, baseline, optimized, frame, templates,
            index % 2 == 0, clearTracking = true) }
        emit("real_golden_full_node_scan_hot_matcher_caches_tracking_memo_cleared_test_only", frame,
            hot, 2, listOf("AB", "BA", "AB", "BA"), iterationsPerSample = 1)
        assertEquals("full", baseline.lastSearchMode)
        assertEquals("full", optimized.lastSearchMode)
        assertEquals(baseline.lastSearchWindowCount, optimized.lastSearchWindowCount)
    }

    @Test fun independentViewportSignatureOnlyEmitExactABBAAllocationGcAndTime() {
        val allocation = Batch4ThreadAllocationCounter.create()
        assumeTrue("Host JDK ThreadMXBean thread allocation measurement is required", allocation != null)
        val counter = requireNotNull(allocation)
        val frame = batch4Pattern(1920, 1080, 17)
        val old = Batch4NodeBaselineReference()
        val now = LabyrinthNodeClassifier()
        fun pair(baselineFirst: Boolean): Batch4VisionPair {
            var oldKey = ""; var newKey = ""
            val a: Batch4VisionMeasurement; val b: Batch4VisionMeasurement
            if (baselineFirst) {
                a = measured(counter) { repeat(200) { oldKey = old.viewportSignatureKey(frame) } }
                b = measured(counter) { repeat(200) { newKey = now.viewportSignatureKey(frame) } }
            } else {
                b = measured(counter) { repeat(200) { newKey = now.viewportSignatureKey(frame) } }
                a = measured(counter) { repeat(200) { oldKey = old.viewportSignatureKey(frame) } }
            }
            assertEquals(oldKey, newKey)
            return Batch4VisionPair(a, b, null)
        }
        repeat(2) { pair(it % 2 == 0) }
        val samples = (0 until 4).map { pair(it % 2 == 0) }
        emit("viewport_signature_only_synthetic_1920x1080_not_full_frame_adapter_or_node_scan",
            frame, samples, 2, listOf("AB", "BA", "AB", "BA"), 200)
    }

    private fun nodePair(counter: Batch4ThreadAllocationCounter, old: Batch4NodeBaselineReference,
        now: LabyrinthNodeClassifier, frame: PixelImage, templates: NodeTemplateSet,
        baselineFirst: Boolean, clearTracking: Boolean): Batch4VisionPair {
        // ONLY benchmark reflection. Do not measure tracked/memo hits as if they were full scans.
        // Both sides keep resident GradientTemplateMatcher/template/coordinate caches, while the
        // previous-frame tracking and stable-scan memo are cleared OUTSIDE the timed action.
        if (clearTracking) { clearTestTracking(old); clearTestTracking(now) }
        var expected = emptyList<NodeClassification>(); var actual = emptyList<NodeClassification>()
        val a: Batch4VisionMeasurement; val b: Batch4VisionMeasurement
        if (baselineFirst) {
            a = measured(counter) { expected = old.classifyMapNodes(frame, templates) }
            b = measured(counter) { actual = now.classifyMapNodes(frame, templates) }
        } else {
            b = measured(counter) { actual = now.classifyMapNodes(frame, templates) }
            a = measured(counter) { expected = old.classifyMapNodes(frame, templates) }
        }
        batch4AssertNodeResultsExact("benchmark", expected, actual)
        assertEquals(old.lastSearchMode, now.lastSearchMode)
        assertEquals(old.lastSearchWindowCount, now.lastSearchWindowCount)
        return Batch4VisionPair(a, b, actual)
    }
    private fun clearTestTracking(owner: Any) {
        for (name in listOf("previousFrame", "scanMemo")) owner.javaClass.getDeclaredField(name)
            .also { it.isAccessible = true }.set(owner, null)
        // Frame identity has not changed here; null previousFrame and null scanMemo force full.
    }
    private fun measured(counter: Batch4ThreadAllocationCounter, action: () -> Unit): Batch4VisionMeasurement {
        val gcBefore = batch4GcSnapshot()
        val before = counter.bytes()
        val start = System.nanoTime()
        action()
        val nanos = System.nanoTime() - start
        val bytes = counter.bytes() - before
        val gcAfter = batch4GcSnapshot()
        return Batch4VisionMeasurement(nanos, bytes, gcAfter.first - gcBefore.first,
            gcAfter.second - gcBefore.second)
    }
    private fun emit(scenario: String, frame: PixelImage, pairs: List<Batch4VisionPair>, warmup: Int,
        order: List<String>, iterationsPerSample: Int) {
        fun numbers(values: List<Number>) = JsonArray(values.map { JsonPrimitive(it) })
        fun median(values: List<Long>): Double {
            val sorted = values.sorted()
            return (sorted[(sorted.size - 1) / 2].toDouble() + sorted[sorted.size / 2].toDouble()) / 2.0
        }
        val a = pairs.map { it.baseline }; val b = pairs.map { it.optimized }
        val record = linkedMapOf(
            "schemaVersion" to JsonPrimitive(1), "scope" to JsonPrimitive("host_jvm_not_android"),
            "scenario" to JsonPrimitive(scenario), "baseline" to JsonPrimitive("frozen_batch3_classifier"),
            "optimized" to JsonPrimitive("batch4_classifier_same_unmodified_matcher"),
            "fixture" to JsonPrimitive(if (pairs.first().nodes == null) "synthetic_pattern" else "node-link-20260914.png"),
            "original736SlowFrameReplay" to JsonPrimitive(false),
            "width" to JsonPrimitive(frame.width), "height" to JsonPrimitive(frame.height),
            "warmupPairs" to JsonPrimitive(warmup), "measuredPairs" to JsonPrimitive(pairs.size),
            "order" to JsonArray(order.map { JsonPrimitive(it) }),
            "iterationsPerSample" to JsonPrimitive(iterationsPerSample),
            "batch3SamplesMs" to numbers(a.map { it.nanos / 1_000_000.0 }),
            "batch4SamplesMs" to numbers(b.map { it.nanos / 1_000_000.0 }),
            "batch3MedianMs" to JsonPrimitive(median(a.map { it.nanos }) / 1_000_000.0),
            "batch4MedianMs" to JsonPrimitive(median(b.map { it.nanos }) / 1_000_000.0),
            "batch3SamplesAllocatedBytes" to numbers(a.map { it.allocatedBytes }),
            "batch4SamplesAllocatedBytes" to numbers(b.map { it.allocatedBytes }),
            "batch3MedianAllocatedBytes" to JsonPrimitive(median(a.map { it.allocatedBytes })),
            "batch4MedianAllocatedBytes" to JsonPrimitive(median(b.map { it.allocatedBytes })),
            "allocationScope" to JsonPrimitive("current_test_thread_only_ThreadMXBean_no_asset_decode_or_assertions"),
            "batch3GcCollectionCountDeltas" to numbers(a.map { it.gcCount }),
            "batch4GcCollectionCountDeltas" to numbers(b.map { it.gcCount }),
            "batch3GcCollectionTimeMsDeltas" to numbers(a.map { it.gcMillis }),
            "batch4GcCollectionTimeMsDeltas" to numbers(b.map { it.gcMillis }),
            "gcScope" to JsonPrimitive("process_wide_GarbageCollectorMXBeans_not_attributable_exclusively_to_test_thread"),
            "outputsExact" to JsonPrimitive(true), "javaVersion" to JsonPrimitive(System.getProperty("java.version")),
            "maxHeapBytes" to JsonPrimitive(Runtime.getRuntime().maxMemory()),
        )
        pairs.last().nodes?.let { record["nodes"] = batch4NodesJson(it) }
        println("BATCH4_VISION_BENCHMARK_JSON=" + JsonObject(record))
        // Diagnostic: never require timing, GC, or allocation improvement on a noisy host JVM.
    }
}
private data class Batch4VisionMeasurement(val nanos: Long, val allocatedBytes: Long,
    val gcCount: Long, val gcMillis: Long)
private data class Batch4VisionPair(val baseline: Batch4VisionMeasurement,
    val optimized: Batch4VisionMeasurement, val nodes: List<NodeClassification>?)

/** Reflection keeps host-only java.management / jdk.management APIs out of Android production. */
private class Batch4ThreadAllocationCounter(private val bean: Any,
    private val type: Class<*>, private val threadId: Long) {
    private val method = type.getMethod("getThreadAllocatedBytes", Long::class.javaPrimitiveType)
    fun bytes(): Long = (method.invoke(bean, threadId) as Long).also { require(it >= 0) }
    companion object {
        fun create(): Batch4ThreadAllocationCounter? = try {
            val factory = Class.forName("java.lang.management.ManagementFactory")
            val bean = factory.getMethod("getThreadMXBean").invoke(null)
            val type = Class.forName("com.sun.management.ThreadMXBean")
            if (!type.isInstance(bean) || type.getMethod("isThreadAllocatedMemorySupported").invoke(bean) != true) null
            else {
                if (type.getMethod("isThreadAllocatedMemoryEnabled").invoke(bean) != true)
                    type.getMethod("setThreadAllocatedMemoryEnabled", Boolean::class.javaPrimitiveType).invoke(bean, true)
                Batch4ThreadAllocationCounter(bean, type, Thread.currentThread().id)
            }
        } catch (_: ClassNotFoundException) { null }
    }
}
private fun batch4GcSnapshot(): Pair<Long, Long> {
    val beans = Class.forName("java.lang.management.ManagementFactory")
        .getMethod("getGarbageCollectorMXBeans").invoke(null) as List<*>
    val type = Class.forName("java.lang.management.GarbageCollectorMXBean")
    val count = type.getMethod("getCollectionCount")
    val time = type.getMethod("getCollectionTime")
    var collections = 0L; var millis = 0L
    for (bean in beans) {
        val c = count.invoke(bean) as Long; val t = time.invoke(bean) as Long
        if (c >= 0) collections += c
        if (t >= 0) millis += t
    }
    return collections to millis
}
