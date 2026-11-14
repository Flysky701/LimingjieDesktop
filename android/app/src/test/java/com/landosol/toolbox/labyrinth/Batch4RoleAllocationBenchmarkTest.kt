package com.landosol.toolbox.labyrinth

import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.*
import org.junit.Test

/** Host-JVM diagnostic ONLY. Allocation is Controller + the four explicitly covered workers,
 * not JVM-total/Android-total. GC counters are JVM-wide and cannot be attributed to a search.
 */
class Batch4RoleAllocationBenchmarkTest {
    @Test fun real25And26RoleInputsTwoWarmupsFourAlternatingPairsReportWallAllocationAndGc() {
        assertEquals("This benchmark is specified for Java17", "17", System.getProperty("java.specification.version"))
        assertTrue("This benchmark is specified for Windows", System.getProperty("os.name").startsWith("Windows"))
        val runtime = batch3ProductionRuntime()
        withCoverage { controller, ids, bean ->
            batch3ObservedInputs(runtime).forEachIndexed { scenario, input ->
                val pair = Batch4RolePolicyPair(runtime.document.scoring, runtime.document.roleChoice)
                repeat(2) { index -> runPolicyPair(pair, input, index % 2 == 0, "warmup-$scenario-$index", controller, ids, bean) }
                val samples = (0 until 4).map { index ->
                    runPolicyPair(pair, input, index % 2 == 0, "measured-$scenario-$index", controller, ids, bean)
                }
                val baselineBytes = samples.map { it.first.bytes.toDouble() }
                val batch4Bytes = samples.map { it.second.bytes.toDouble() }
                // Deterministic covered-thread allocation, not a wall-clock speed threshold.
                // Warm worker creation and fixture/parser/equality work are excluded from samples.
                assertTrue("Hot-cache covered-thread median allocation must decrease", median(batch4Bytes) < median(baselineBytes))
                val chosen = samples.last().second.value as LabyrinthOneRoleDecision.Ready
                val record = linkedMapOf(
                    "schemaVersion" to JsonPrimitive(1),
                    "kind" to JsonPrimitive("observed_role_choice"),
                    "scope" to JsonPrimitive("java17_windows_host_controller_plus_fixed_role_workers_not_android_total"),
                    "scenario" to JsonPrimitive(if (scenario == 0) "observed_frame_2017_maho" else "observed_frame_2028_tsumugi"),
                    "acquiredCount" to JsonPrimitive(input.acquiredIds.size),
                    "candidateIds" to JsonArray(input.candidateIds.map { JsonPrimitive(it) }),
                    "warmupPairs" to JsonPrimitive(2),
                    "measuredPairs" to JsonPrimitive(4),
                    "order" to JsonArray(listOf("AB", "BA", "AB", "BA").map { JsonPrimitive(it) }),
                    "batch3SamplesMs" to doubles(samples.map { it.first.nanos / 1_000_000.0 }),
                    "batch4SamplesMs" to doubles(samples.map { it.second.nanos / 1_000_000.0 }),
                    "batch3MedianMs" to JsonPrimitive(median(samples.map { it.first.nanos / 1_000_000.0 })),
                    "batch4MedianMs" to JsonPrimitive(median(samples.map { it.second.nanos / 1_000_000.0 })),
                    "batch3AllocationBytes" to longs(samples.map { it.first.bytes }),
                    "batch4AllocationBytes" to longs(samples.map { it.second.bytes }),
                    "batch3MedianAllocationBytes" to JsonPrimitive(median(baselineBytes)),
                    "batch4MedianAllocationBytes" to JsonPrimitive(median(batch4Bytes)),
                    "allocationReductionBytes" to JsonPrimitive(median(baselineBytes) - median(batch4Bytes)),
                    "batch3AllocationBytesByCoveredThread" to JsonArray(samples.map { longs(it.first.byThreadBytes.toList()) }),
                    "batch4AllocationBytesByCoveredThread" to JsonArray(samples.map { longs(it.second.byThreadBytes.toList()) }),
                    "batch3JvmGcCountDeltas" to longs(samples.map { it.first.gcCount }),
                    "batch4JvmGcCountDeltas" to longs(samples.map { it.second.gcCount }),
                    "batch3JvmGcTimeMsDeltas" to longs(samples.map { it.first.gcTimeMs }),
                    "batch4JvmGcTimeMsDeltas" to longs(samples.map { it.second.gcTimeMs }),
                    "gcScope" to JsonPrimitive("jvm_wide_unattributed_not_android"),
                    "outputsExactEveryPair" to JsonPrimitive(true),
                    "chosenId" to JsonPrimitive(chosen.chosen.characterId),
                    "selectedLayer" to JsonPrimitive(chosen.rankings.first().candidateLayer),
                    "sameProductionExecutorForBoth" to JsonPrimitive(true),
                    "residentBytes" to JsonPrimitive(batch4ResidentBytes(LabyrinthCombinationIndexCache.shared)),
                    "residentEntries" to JsonPrimitive(batch4ResidentEntryCount(LabyrinthCombinationIndexCache.shared)),
                    "residentByteLimit" to JsonPrimitive(2L * 1024 * 1024),
                    "residentEntryLimit" to JsonPrimitive(8),
                    "javaVersion" to JsonPrimitive(System.getProperty("java.version")),
                    "os" to JsonPrimitive(System.getProperty("os.name")),
                    "maxHeapBytes" to JsonPrimitive(Runtime.getRuntime().maxMemory()),
                )
                record["coveredThreads"] = coverageJson(ids, bean)
                println("BATCH4_ROLE_BENCHMARK_JSON=${JsonObject(record)}")
                batch4AssertResidentBounds(LabyrinthCombinationIndexCache.shared)
            }
        }
    }

    @Test fun isolated24Choose5IndexColdHotAllocationAndResidentLimitsAreMeasured() {
        withCoverage { controller, ids, bean ->
            val n = 24
            val k = 5
            val count = batch4CombinationCount(n, k)
            val hotCache = LabyrinthCombinationIndexCache()
            hotCache.get(n, k, count)
            repeat(2) { index -> runIndexPair(n, k, count, hotCache, index % 2 == 0, controller, ids, bean) }
            val samples = (0 until 4).map { index -> runIndexPair(n, k, count, hotCache, index % 2 == 0, controller, ids, bean) }
            val coldBytes = samples.map { it.first.bytes.toDouble() }
            val hotBytes = samples.map { it.second.bytes.toDouble() }
            assertTrue("Cold miss allocates the payload, hot hit does not", median(coldBytes) - median(hotBytes) >= count.toLong() * k * 4)
            batch4AssertResidentBounds(hotCache)
            val record = JsonObject(linkedMapOf(
                "schemaVersion" to JsonPrimitive(1),
                "kind" to JsonPrimitive("isolated_combination_index_cold_hot"),
                "scope" to JsonPrimitive("java17_windows_host_controller_plus_fixed_role_workers_not_android_total"),
                "n" to JsonPrimitive(n), "k" to JsonPrimitive(k), "flatIntCount" to JsonPrimitive(count * k),
                "payloadBytes" to JsonPrimitive(count.toLong() * k * 4),
                "warmupPairs" to JsonPrimitive(2), "measuredPairs" to JsonPrimitive(4),
                "order" to JsonArray(listOf("AB", "BA", "AB", "BA").map { JsonPrimitive(it) }),
                "coldSamplesMs" to doubles(samples.map { it.first.nanos / 1_000_000.0 }),
                "hotSamplesMs" to doubles(samples.map { it.second.nanos / 1_000_000.0 }),
                "coldAllocationBytes" to longs(samples.map { it.first.bytes }),
                "hotAllocationBytes" to longs(samples.map { it.second.bytes }),
                "coldMedianAllocationBytes" to JsonPrimitive(median(coldBytes)),
                "hotMedianAllocationBytes" to JsonPrimitive(median(hotBytes)),
                "coldJvmGcCountDeltas" to longs(samples.map { it.first.gcCount }),
                "hotJvmGcCountDeltas" to longs(samples.map { it.second.gcCount }),
                "coldJvmGcTimeMsDeltas" to longs(samples.map { it.first.gcTimeMs }),
                "hotJvmGcTimeMsDeltas" to longs(samples.map { it.second.gcTimeMs }),
                "gcScope" to JsonPrimitive("jvm_wide_unattributed_not_android"),
                "coveredThreads" to coverageJson(ids, bean),
                "residentBytes" to JsonPrimitive(batch4ResidentBytes(hotCache)),
                "residentEntries" to JsonPrimitive(batch4ResidentEntryCount(hotCache)),
                "residentByteLimit" to JsonPrimitive(2L * 1024 * 1024), "residentEntryLimit" to JsonPrimitive(8),
                "outputsExactEveryPair" to JsonPrimitive(true),
                "coldCacheConstructionExcluded" to JsonPrimitive(true),
                "javaVersion" to JsonPrimitive(System.getProperty("java.version")),
                "os" to JsonPrimitive(System.getProperty("os.name")),
            ))
            println("BATCH4_ROLE_BENCHMARK_JSON=$record")
        }
    }

    private fun runPolicyPair(pair: Batch4RolePolicyPair, input: Batch3RoleInput, baselineFirst: Boolean,
        label: String, controller: ExecutorService, ids: LongArray, bean: Batch4RoleJvmMetrics): Pair<Measured<LabyrinthOneRoleDecision>, Measured<LabyrinthOneRoleDecision>> {
        val baseline: Measured<LabyrinthOneRoleDecision>
        val optimized: Measured<LabyrinthOneRoleDecision>
        if (baselineFirst) {
            baseline = measure(controller, ids, bean) { pair.baseline(input) }
            optimized = measure(controller, ids, bean) { pair.optimized(input) }
        } else {
            optimized = measure(controller, ids, bean) { pair.optimized(input) }
            baseline = measure(controller, ids, bean) { pair.baseline(input) }
        }
        batch3AssertExact(label, baseline.value, optimized.value)
        return baseline to optimized
    }

    private fun runIndexPair(n: Int, k: Int, count: Int, hotCache: LabyrinthCombinationIndexCache,
        coldFirst: Boolean, controller: ExecutorService, ids: LongArray, bean: Batch4RoleJvmMetrics):
        Pair<Measured<LabyrinthCombinationIndexCache.IndexTable>, Measured<LabyrinthCombinationIndexCache.IndexTable>> {
        val coldCache = LabyrinthCombinationIndexCache() // outside both timed/sampled calls
        val cold: Measured<LabyrinthCombinationIndexCache.IndexTable>
        val hot: Measured<LabyrinthCombinationIndexCache.IndexTable>
        if (coldFirst) {
            cold = measure(controller, ids, bean) { coldCache.get(n, k, count) }
            hot = measure(controller, ids, bean) { hotCache.get(n, k, count) }
        } else {
            hot = measure(controller, ids, bean) { hotCache.get(n, k, count) }
            cold = measure(controller, ids, bean) { coldCache.get(n, k, count) }
        }
        assertEquals(cold.value.size, hot.value.size)
        for (index in 0 until cold.value.size) assertEquals(cold.value[index], hot.value[index])
        batch4AssertResidentBounds(coldCache)
        return cold to hot
    }

    private fun withCoverage(action: (ExecutorService, LongArray, Batch4RoleJvmMetrics) -> Unit) {
        val bean = Batch4RoleJvmMetrics()
        val pool = batch4ProductionExecutor()
        // Four blocking starters force every existing production worker to be initialized before
        // recording IDs; completion ensures startup allocations never enter measured intervals.
        val started = CountDownLatch(4)
        val release = CountDownLatch(1)
        val controller = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "batch4-role-benchmark-controller").apply { isDaemon = true }
        }
        try {
            val workers = (0 until 4).map { pool.submit(Callable {
                started.countDown()
                check(release.await(30, TimeUnit.SECONDS))
                Thread.currentThread().id
            }) }
            check(started.await(30, TimeUnit.SECONDS))
            release.countDown()
            val workerIds = workers.map { it.get(30, TimeUnit.SECONDS) }.distinct().sorted()
            assertEquals(4, workerIds.size)
            val controllerId = controller.submit(Callable { Thread.currentThread().id }).get(30, TimeUnit.SECONDS)
            val ids = (listOf(controllerId) + workerIds).toLongArray()
            assertEquals(5, ids.distinct().size)
            assertTrue(bean.getThreadAllocatedBytes(ids).all { it >= 0 })
            action(controller, ids, bean)
        } finally {
            release.countDown()
            controller.shutdownNow()
            check(controller.awaitTermination(30, TimeUnit.SECONDS))
            // The shared production executor intentionally remains alive for all other tests.
        }
    }

    private fun <T> measure(controller: ExecutorService, ids: LongArray, bean: Batch4RoleJvmMetrics, action: () -> T): Measured<T> {
        val beforeGc = bean.gcTotals()
        val beforeBytes = bean.getThreadAllocatedBytes(ids)
        val result = controller.submit(Callable {
            val start = System.nanoTime()
            val value = action()
            Timed(value, System.nanoTime() - start)
        }).get(180, TimeUnit.SECONDS)
        val afterBytes = bean.getThreadAllocatedBytes(ids)
        val afterGc = bean.gcTotals()
        assertTrue("Every covered thread stays readable", beforeBytes.all { it >= 0 } && afterBytes.all { it >= 0 })
        val deltas = LongArray(ids.size) { index -> afterBytes[index] - beforeBytes[index] }
        assertTrue("Allocation counters must be monotonic", deltas.all { it >= 0 })
        return Measured(result.value, result.nanos, deltas.sum(), deltas,
            afterGc.first - beforeGc.first, afterGc.second - beforeGc.second)
    }

    private fun coverageJson(ids: LongArray, bean: Batch4RoleJvmMetrics): JsonArray = JsonArray(ids.mapIndexed { index, id -> JsonObject(linkedMapOf(
        "threadId" to JsonPrimitive(id),
        "name" to JsonPrimitive(bean.threadName(id)),
        "role" to JsonPrimitive(if (index == 0) "fixed_controller" else "shared_production_role_search_worker"),
    )) })
    private fun median(values: List<Double>): Double = values.sorted().let { (it[it.size / 2 - 1] + it[it.size / 2]) / 2.0 }
    private fun doubles(values: List<Double>): JsonArray = JsonArray(values.map { JsonPrimitive(it) })
    private fun longs(values: List<Long>): JsonArray = JsonArray(values.map { JsonPrimitive(it) })
    private data class Timed<T>(val value: T, val nanos: Long)
    private data class Measured<T>(val value: T, val nanos: Long, val bytes: Long, val byThreadBytes: LongArray,
        val gcCount: Long, val gcTimeMs: Long)
}

/** Android's test compile bootclasspath omits java.management/jdk.management. Resolve their
 * public interfaces only on the Java17 host; unsupported/missing metrics FAIL, never skip.
 * All reflective setup and sampling execute on the excluded JUnit thread, not the Controller.
 */
private class Batch4RoleJvmMetrics {
    private val factory = Class.forName("java.lang.management.ManagementFactory")
    private val threadBean = factory.getMethod("getThreadMXBean").invoke(null)
    private val allocationType = Class.forName("com.sun.management.ThreadMXBean")
    private val threadType = Class.forName("java.lang.management.ThreadMXBean")
    private val threadInfoType = Class.forName("java.lang.management.ThreadInfo")
    private val allocated = allocationType.getMethod("getThreadAllocatedBytes", LongArray::class.java)
    private val threadInfo = threadType.getMethod("getThreadInfo", Long::class.javaPrimitiveType)
    private val threadName = threadInfoType.getMethod("getThreadName")
    private val collectors = factory.getMethod("getGarbageCollectorMXBeans").invoke(null) as List<*>
    private val collectorType = Class.forName("java.lang.management.GarbageCollectorMXBean")
    private val collectionCount = collectorType.getMethod("getCollectionCount")
    private val collectionTime = collectorType.getMethod("getCollectionTime")

    init {
        check(allocationType.isInstance(threadBean)) { "Host com.sun.management.ThreadMXBean required" }
        check(allocationType.getMethod("isThreadAllocatedMemorySupported").invoke(threadBean) == true) {
            "Host per-thread allocated-byte counters required"
        }
        if (allocationType.getMethod("isThreadAllocatedMemoryEnabled").invoke(threadBean) != true) {
            allocationType.getMethod("setThreadAllocatedMemoryEnabled", Boolean::class.javaPrimitiveType)
                .invoke(threadBean, true)
        }
        check(allocationType.getMethod("isThreadAllocatedMemoryEnabled").invoke(threadBean) == true)
    }

    fun getThreadAllocatedBytes(ids: LongArray): LongArray = allocated.invoke(threadBean, ids as Any) as LongArray

    fun threadName(id: Long): String {
        val info = threadInfo.invoke(threadBean, id) ?: return "not_live"
        return threadName.invoke(info) as String
    }

    fun gcTotals(): Pair<Long, Long> {
        var count = 0L
        var timeMs = 0L
        for (collector in collectors) {
            count += (collectionCount.invoke(collector) as Long).coerceAtLeast(0)
            timeMs += (collectionTime.invoke(collector) as Long).coerceAtLeast(0)
        }
        return count to timeMs
    }
}
