package com.landosol.toolbox.labyrinth.vision

import com.landosol.toolbox.labyrinth.concurrent.LabyrinthFourThreadRuntime
import com.landosol.toolbox.labyrinth.concurrent.WorkKind
import com.landosol.toolbox.labyrinth.node.LabyrinthNodeClassifier
import com.landosol.toolbox.labyrinth.node.NodeClassification
import com.landosol.toolbox.labyrinth.node.batch4AssertNodeResultsExact
import com.landosol.toolbox.labyrinth.node.batch4MapFrame
import com.landosol.toolbox.labyrinth.node.batch4NodeTemplates
import com.landosol.toolbox.labyrinth.node.batch4NodesJson
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.*
import org.junit.Test

/**
 * Diagnostic host JVM ONLY: real same-image pure CPU work, not synthetic T2 counter timing.
 * No Android Bitmap/getPixels, MediaProjection, SDK/OCR, original 736-frame replay, or speedup gate.
 */
class Batch5FastObservationBenchmarkTest {
    @Test fun realSameMapFastControlAndFullComputeEmitBitExactWarmABBAWorkTimings() = runBlocking {
        // Decode and all construction happen outside every timed action.
        val entryTemplates = Batch5FrameFixtures.templates()
        val nodeTemplates = batch4NodeTemplates()
        val frame = batch4MapFrame("node-link-20260914.png")
        val fastObserver = LabyrinthFastFrameObserver(entryTemplates)
        val fullMatcher = GradientTemplateMatcher()
        val nodeClassifier = LabyrinthNodeClassifier(sharedFrameFeatures = fullMatcher)
        val fullProcessor = LabyrinthEntryFrameProcessor(
            templates = entryTemplates,
            matcher = fullMatcher,
            nodeClassifier = nodeClassifier,
            nodeTemplates = nodeTemplates,
        )
        // Test-only cache control: keep resident matcher/template/coordinate caches but force a
        // real full map scan for each sample. resetTracking alone DOES NOT clear scanMemo.
        val memoField = nodeClassifier.javaClass.getDeclaredField("scanMemo").also { it.isAccessible = true }
        val cpuClock = Batch5ObservationCpuClock.create()
        val savedPixels = frame.pixels.copyOf()
        var goldenNodes: List<NodeClassification>? = null
        var goldenWindows: Int? = null
        var goldenPage: LabyrinthEntryPageObservation? = null

        suspend fun fastSample(): Batch5ObservationSample<LabyrinthFastFrameObservation> =
            withContext(LabyrinthFourThreadRuntime.controlDispatcher) {
                measured(cpuClock) { fastObserver.process(frame, frameSequence = 5L, timestampMillis = 0L) }
            }

        suspend fun fullSample(): Batch5ObservationSample<LabyrinthEntryFrameResult> {
            val quiesced = CompletableDeferred<Unit>()
            val handle = LabyrinthFourThreadRuntime.submitWork(
                kind = WorkKind.DETAILED_VISION,
                onQuiesced = { quiesced.complete(Unit) },
            ) {
                // These actions are outside nanoTime and thread CPU-time deltas.
                nodeClassifier.resetTracking()
                memoField.set(nodeClassifier, null)
                measured(cpuClock) { fullProcessor.process(frame) }
            }
            try {
                return handle.await()
            } catch (error: Throwable) {
                handle.cancel(false)
                throw error
            } finally {
                // No Future.done-based lifetime assumption or shared-runtime shutdown.
                withContext(NonCancellable) { quiesced.await() }
            }
        }

        suspend fun pair(fastFirst: Boolean): Batch5ObservationPair {
            val fast: Batch5ObservationSample<LabyrinthFastFrameObservation>
            val full: Batch5ObservationSample<LabyrinthEntryFrameResult>
            if (fastFirst) { fast = fastSample(); full = fullSample() }
            else { full = fullSample(); fast = fastSample() }
            assertTrue("fast must run on T2", fast.onControl)
            assertFalse("fast must not run on shared CPU worker", fast.onCompute)
            assertTrue("full must run in the shared two-worker pool", full.onCompute)
            assertFalse("full must not run on control", full.onControl)
            assertNotEquals(fast.threadId, full.threadId)
            assertEquals("real node scan must not be a page-only/no-node run",
                LabyrinthEntryPageState.NODE_SELECTION, full.result.observation.state)
            assertEquals("same image must classify to exactly the same page",
                full.result.observation, fast.result.observation)
            assertEquals(full.result.anchorMatches.keys, fast.result.anchorMatches.keys)
            for (id in full.result.anchorMatches.keys) {
                val a = full.result.anchorMatches.getValue(id)
                val b = fast.result.anchorMatches.getValue(id)
                assertEquals("anchor rect $id", a.rect, b.rect)
                assertEquals("anchor IEEE bits $id", a.score.toRawBits(), b.score.toRawBits())
            }
            assertEquals(full.result.observation.anchorScores.values.keys, fast.result.observation.anchorScores.values.keys)
            for ((id, value) in full.result.observation.anchorScores.values) {
                assertEquals("all page-score IEEE bits $id", value.toRawBits(),
                    fast.result.observation.anchorScores[id].toRawBits())
            }
            assertEquals("tracked/memo/deferred is not a full-scanning benchmark", "full", full.result.nodeSearchMode)
            assertTrue("full processor must actually score node windows", full.result.nodeSearchWindowCount > 0)
            assertTrue("real node golden must not produce vacuous output", full.result.nodeClassifications.isNotEmpty())
            goldenNodes?.let { batch4AssertNodeResultsExact("repeated complete full entry-node result", it, full.result.nodeClassifications) }
                ?: run { goldenNodes = full.result.nodeClassifications.map { it.copy() } }
            goldenWindows?.let { assertEquals(it, full.result.nodeSearchWindowCount) }
                ?: run { goldenWindows = full.result.nodeSearchWindowCount }
            goldenPage?.let { assertEquals("page must remain stable across warm/order changes", it, full.result.observation) }
                ?: run { goldenPage = full.result.observation }
            assertEquals(full.result.nodeMoveConfirmation, fast.result.nodeMoveConfirmation)
            assertEquals(full.result.battleFailure, fast.result.battleFailure)
            assertEquals(full.result.battleEndConfirmation, fast.result.battleEndConfirmation)
            assertEquals(full.result.specialEquipment, fast.result.specialEquipment)
            assertEquals(full.result.exChallenge, fast.result.exChallenge)
            return Batch5ObservationPair(fast, full)
        }

        repeat(2) { pair(fastFirst = it % 2 == 0) } // Warm BOTH paths, AB then BA; no warm samples reported.
        val samples = (0 until 4).map { pair(fastFirst = it % 2 == 0) }
        assertArrayEquals("read-only shared pixels", savedPixels, frame.pixels)
        assertEquals("one dedicated control thread", 1, samples.map { it.fast.threadId }.toSet().size)
        assertTrue("at most the existing two compute threads", samples.map { it.full.threadId }.toSet().size <= 2)
        val fast = samples.map { it.fast }
        val full = samples.map { it.full }
        val record = linkedMapOf(
            "schemaVersion" to JsonPrimitive(1),
            "scope" to JsonPrimitive("host_jvm_not_android"),
            "scenario" to JsonPrimitive("same_real_node_link_page_fast_vs_complete_entry_core_forced_full_node_scan"),
            "fixture" to JsonPrimitive("node-link-20260914.png"),
            "fixtureTransform" to JsonPrimitive("none"),
            "original736SlowFrameReplay" to JsonPrimitive(false),
            "width" to JsonPrimitive(frame.width), "height" to JsonPrimitive(frame.height),
            "entryTemplateCount" to JsonPrimitive(entryTemplates.values.size),
            "nodeTemplateCount" to JsonPrimitive(nodeTemplates.templates.size),
            "warmupPairs" to JsonPrimitive(2), "measuredPairs" to JsonPrimitive(4),
            "order" to JsonArray(listOf("AB", "BA", "AB", "BA").map { JsonPrimitive(it) }),
            "A" to JsonPrimitive("fast_control_page_and_cheap_ROI"),
            "B" to JsonPrimitive("full_compute_entry_core_with_real_node_templates"),
            "iterationsPerSample" to JsonPrimitive(1),
            "timingScope" to JsonPrimitive("synchronous_pure_CPU_process_body_nanoTime_excludes_decode_construction_dispatch_queue_wait_assertions"),
            "fastSamplesMs" to numbers(fast.map { it.nanos / 1_000_000.0 }),
            "fullSamplesMs" to numbers(full.map { it.nanos / 1_000_000.0 }),
            "fastMedianMs" to JsonPrimitive(median(fast.map { it.nanos }) / 1_000_000.0),
            "fullMedianMs" to JsonPrimitive(median(full.map { it.nanos }) / 1_000_000.0),
            "threadCpuTimingScope" to JsonPrimitive("optional_host_ThreadMXBean_current_thread_only_no_Android_claim"),
            "fastThreadCpuSamplesMs" to JsonArray(fast.map { it.cpuNanos?.let { ns -> JsonPrimitive(ns / 1_000_000.0) } ?: JsonNull }),
            "fullThreadCpuSamplesMs" to JsonArray(full.map { it.cpuNanos?.let { ns -> JsonPrimitive(ns / 1_000_000.0) } ?: JsonNull }),
            "controlThreads" to JsonArray(fast.map { it.threadJson() }),
            "computeThreads" to JsonArray(full.map { it.threadJson() }),
            "cacheControl" to JsonPrimitive("test_only_resetTracking_and_scanMemo_null_before_each_full_sample_outside_timer_resident_matcher_template_caches"),
            "fullCoverage" to JsonPrimitive("complete_PixelImage_entry_core_real_node_scan_default_other_recognizers_unreached_on_map_no_Android_resolvers"),
            "fullNodeSearchMode" to JsonPrimitive(samples.last().full.result.nodeSearchMode),
            "fullNodeSearchWindowCount" to JsonPrimitive(samples.last().full.result.nodeSearchWindowCount),
            "page" to JsonPrimitive(samples.last().full.result.observation.state.name),
            "pageOutputsExact" to JsonPrimitive(true), "anchorScoreBitsExact" to JsonPrimitive(true),
            "completeOrderedNodesStable" to JsonPrimitive(true),
            "nodes" to batch4NodesJson(samples.last().full.result.nodeClassifications),
            "javaVersion" to JsonPrimitive(System.getProperty("java.version")),
            "maxHeapBytes" to JsonPrimitive(Runtime.getRuntime().maxMemory()),
            "speedupThresholdApplied" to JsonPrimitive(false),
            "androidBitmapCaptureOrSdkTiming" to JsonPrimitive(false),
        )
        println("BATCH5_OBSERVATION_BENCHMARK_JSON=" + JsonObject(record))
        // Diagnostic only: no fast/full time ratio or absolute timing threshold can fail the test.
    }

    private fun <T> measured(clock: Batch5ObservationCpuClock?, body: () -> T): Batch5ObservationSample<T> {
        val thread = Thread.currentThread()
        val onControl = LabyrinthFourThreadRuntime.isControlThread()
        val onCompute = LabyrinthFourThreadRuntime.isComputeThread()
        val cpuBefore = clock?.nanos()
        val start = System.nanoTime()
        val result = body()
        val elapsed = System.nanoTime() - start
        val cpuAfter = clock?.nanos()
        val cpuDelta = if (cpuBefore != null && cpuAfter != null && cpuAfter >= cpuBefore) cpuAfter - cpuBefore else null
        return Batch5ObservationSample(result, elapsed, cpuDelta, thread.id, thread.name, onControl, onCompute)
    }

    private fun numbers(values: List<Double>) = JsonArray(values.map { JsonPrimitive(it) })
    private fun median(values: List<Long>): Double {
        val sorted = values.sorted()
        return (sorted[(sorted.size - 1) / 2].toDouble() + sorted[sorted.size / 2].toDouble()) / 2.0
    }
}

private data class Batch5ObservationSample<T>(
    val result: T, val nanos: Long, val cpuNanos: Long?, val threadId: Long, val threadName: String,
    val onControl: Boolean, val onCompute: Boolean,
) {
    fun threadJson() = JsonObject(linkedMapOf(
        "id" to JsonPrimitive(threadId), "name" to JsonPrimitive(threadName),
        "isControlThread" to JsonPrimitive(onControl), "isComputeThread" to JsonPrimitive(onCompute),
    ))
}
private data class Batch5ObservationPair(
    val fast: Batch5ObservationSample<LabyrinthFastFrameObservation>,
    val full: Batch5ObservationSample<LabyrinthEntryFrameResult>,
)

/** Host-only optional CPU clock; reflection avoids Android java.management linkage. */
private class Batch5ObservationCpuClock(private val bean: Any, private val cpuTime: java.lang.reflect.Method) {
    fun nanos(): Long? = try { (cpuTime.invoke(bean) as Long).takeIf { it >= 0L } } catch (_: Exception) { null }
    companion object {
        fun create(): Batch5ObservationCpuClock? = try {
            val factory = Class.forName("java.lang.management.ManagementFactory")
            val type = Class.forName("java.lang.management.ThreadMXBean")
            val bean = factory.getMethod("getThreadMXBean").invoke(null)
            if (type.getMethod("isCurrentThreadCpuTimeSupported").invoke(bean) != true) null
            else {
                if (type.getMethod("isThreadCpuTimeEnabled").invoke(bean) != true)
                    type.getMethod("setThreadCpuTimeEnabled", Boolean::class.javaPrimitiveType).invoke(bean, true)
                Batch5ObservationCpuClock(bean, type.getMethod("getCurrentThreadCpuTime"))
            }
        } catch (_: Exception) { null }
    }
}
