package com.landosol.toolbox.labyrinth

import com.landosol.toolbox.labyrinth.concurrent.LabyrinthFourThreadRuntime
import com.landosol.toolbox.labyrinth.concurrent.WorkKind
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Host JDK17/Windows diagnostic, NOT an Android performance guarantee.
 * Both algorithms run as PLANNING roots on the same actual two-worker executor. JUnit only
 * submits, waits, samples and asserts; it never computes policy work. Full roots/children quiesce
 * before both workers are parked for each endpoint snapshot. Shared index cache is identical.
 */
class Batch6Round1BenchmarkTest {
    @Test fun twoLargestRealLedgersConsecutiveGrowthTwoWarmupFourAlternatingMeasuredPairs() {
        assertEquals("17", System.getProperty("java.specification.version"))
        assertTrue(System.getProperty("os.name").startsWith("Windows"))
        val runtime = batch3ProductionRuntime() // parser/assets excluded
        val fixtures = batch6Round1Fixtures(runtime).takeLast(2)
        val inputs = fixtures.map { it.input }
        val pair = Batch6Round1PolicyPair(runtime.document.scoring, runtime.document.roleChoice)
        val metrics = Batch6Round1JvmMetrics()
        val controllerId = Thread.currentThread().id
        val initial = parkWorkers()
        val workerIds = try { initial.ids.sorted().toLongArray() } finally { initial.releaseAndJoin() }
        assertEquals(2, workerIds.distinct().size)
        assertFalse(controllerId in workerIds)
        val ids = workerIds + controllerId
        val threadNames = ids.map(metrics::threadName)
        repeat(2) { i -> runPair(pair, inputs, i % 2 == 0, metrics, ids, "warmup-$i") }
        val measured = (0 until 4).map { i ->
            runPair(pair, inputs, i % 2 == 0, metrics, ids, "measured-$i")
        }
        val old = measured.map { it.first }
        val new = measured.map { it.second }
        val oldMedian = median(old.map { it.workerAllocatedBytes.toDouble() })
        val newMedian = median(new.map { it.workerAllocatedBytes.toDouble() })
        val report = JsonObject(linkedMapOf(
            "schemaVersion" to JsonPrimitive(1),
            "kind" to JsonPrimitive("batch6_round1_two_largest_real_ledgers_consecutive_addition"),
            "fixtures" to JsonArray(fixtures.map { JsonPrimitive(it.label) }),
            "rosterSizes" to JsonArray(inputs.map { JsonPrimitive(it.acquiredIds.size) }),
            "warmupPairs" to JsonPrimitive(2), "measuredPairs" to JsonPrimitive(4),
            "order" to JsonArray(listOf("AB", "BA", "AB", "BA").map(::JsonPrimitive)),
            "scope" to JsonPrimitive("host_jdk17_windows_two_actual_shared_compute_workers_not_android_or_jvm_total"),
            "root" to JsonPrimitive("shared_runtime_submitWork_PLANNING"),
            "controller" to JsonPrimitive("JUnit caller only submits/waits/samples/asserts; no policy computation; raw counters reported separately and not included in compute totals"),
            "sampling" to JsonPrimitive("root and descendants quiesced, then BOTH workers blocked in endpoint parking tasks; no result-completion vs cleanup sampling race"),
            "coordinationOverhead" to JsonPrimitive("worker CPU/allocation includes symmetric start-parking release cleanup and end-parking entry; wall is policy bodies only; excludes assets, fixtures, JSON and assertions"),
            "tailCaveat" to JsonPrimitive("4 paired observations only; max is observed tail, not stable P95 or performance guarantee"),
            "sharedExecutorAndIndexCache" to JsonPrimitive(true),
            "allPairOutputsExact" to JsonPrimitive(true),
            "javaVersion" to JsonPrimitive(System.getProperty("java.version")),
            "os" to JsonPrimitive(System.getProperty("os.name")),
            "maxHeapBytes" to JsonPrimitive(Runtime.getRuntime().maxMemory()),
            "threads" to JsonArray(ids.mapIndexed { i, id -> JsonObject(linkedMapOf(
                "id" to JsonPrimitive(id), "name" to JsonPrimitive(threadNames[i]),
                "role" to JsonPrimitive(if (i < 2) "actual_shared_compute_worker" else "noncompute_controller_sampler")
            )) }),
            "baseline" to samplesJson(old), "new" to samplesJson(new),
            "baselineSummary" to summaryJson(old), "newSummary" to summaryJson(new),
            "allocationReductionBytes" to JsonPrimitive(oldMedian - newMedian),
            "allocationReductionFraction" to JsonPrimitive((oldMedian - newMedian) / oldMedian),
            "allocationMedianDecreased" to JsonPrimitive(newMedian < oldMedian)
        ))
        // Emit raw samples even if allocation validation fails. Never assert an absolute wall bound.
        println("BATCH6_ROUND1_BENCHMARK_JSON=$report")
        assertTrue("Covered actual-worker allocations must be positive/readable", oldMedian > 0 && newMedian > 0)
        assertTrue("Measured median actual-worker allocation must decrease", newMedian < oldMedian)
    }

    @Test fun real36RolePlannerWithIndexColdBeforeEverySide() {
        val runtime = batch3ProductionRuntime()
        val input = batch6Round1Fixtures(runtime).first { it.input.acquiredIds.size == 36 }.input
        val pair = Batch6Round1PolicyPair(runtime.document.scoring, runtime.document.roleChoice)
        withIsolatedSharedIndexCache { clear ->
            runBoundaryBenchmark("real36_index_cold_not_jit_or_process_cold", pair, listOf(input),
                repeats = 1, beforeSample = clear, requireBothWorkersCpu = true)
        }
    }

    @Test fun smallPoolsContinuousOwned4Through10ExposePrepareOverhead() {
        val source = batch3SyntheticInput(10)
        val sequence = (4..10).map { size ->
            source.copy(acquiredIds = LinkedHashSet(source.acquiredIds.take(size)))
        }
        // Same profiles/policy, consecutive new owned role at each step. No cross-call prep cache.
        // Batch 16 whole sequences per side to reduce Windows CPU-clock quantization effects.
        runBoundaryBenchmark("small_owned4_through10_prepare_overhead", Batch6Round1PolicyPair(), sequence,
            repeats = 16, requireBothWorkersCpu = false)
    }

    private fun runBoundaryBenchmark(kind: String, pair: Batch6Round1PolicyPair,
        inputs: List<Batch3RoleInput>, repeats: Int, beforeSample: () -> Unit = {},
        requireBothWorkersCpu: Boolean) {
        assertEquals("17", System.getProperty("java.specification.version"))
        assertTrue(System.getProperty("os.name").startsWith("Windows"))
        val metrics = Batch6Round1JvmMetrics()
        val initial = parkWorkers()
        val workers = try { initial.ids.sorted().toLongArray() } finally { initial.releaseAndJoin() }
        val ids = workers + Thread.currentThread().id
        assertEquals(3, ids.distinct().size)
        fun side(baseline: Boolean): Measured = measure(metrics, ids, beforeSample, requireBothWorkersCpu) {
            buildList { repeat(repeats) { inputs.forEach { input ->
                add(if (baseline) pair.baseline(input) else pair.optimized(input))
            } } }
        }
        fun paired(index: Int): Pair<Measured, Measured> {
            val a: Measured
            val b: Measured
            if (index % 2 == 0) { a = side(true); b = side(false) }
            else { b = side(false); a = side(true) }
            batch3AssertExact("$kind/$index", a.values, b.values)
            return a to b
        }
        repeat(2) { paired(it) }
        val samples = (0 until 4).map(::paired)
        val old = samples.map { it.first }
        val new = samples.map { it.second }
        val oldBytes = median(old.map { it.workerAllocatedBytes.toDouble() })
        val newBytes = median(new.map { it.workerAllocatedBytes.toDouble() })
        println("BATCH6_ROUND1_BENCHMARK_JSON=" + JsonObject(linkedMapOf(
            "schemaVersion" to JsonPrimitive(1), "kind" to JsonPrimitive(kind),
            "warmupPairs" to JsonPrimitive(2), "measuredPairs" to JsonPrimitive(4),
            "order" to JsonArray(listOf("AB", "BA", "AB", "BA").map(::JsonPrimitive)),
            "rosterSizesInSequence" to JsonArray(inputs.map { JsonPrimitive(it.acquiredIds.size) }),
            "sequenceRepeatsPerSide" to JsonPrimitive(repeats),
            "policyCallsPerSide" to JsonPrimitive(repeats * inputs.size),
            "indexCold" to JsonPrimitive(kind.contains("index_cold")),
            "coldScope" to JsonPrimitive("Only shared combination-index entries are cleared before each cold side; JIT, process, profiles and runtime stay warm. Clear is outside counter/wall samples; original LRU object references/order/accounting restored in finally."),
            "scope" to JsonPrimitive("shared_compute_root_two_actual_workers_cpu_and_allocation_not_android_total"),
            "smallPoolCpuCaveat" to JsonPrimitive("Below parallel threshold only root worker computes; both actual workers sampled regardless, zero CPU on unused worker is valid; no artificial second-worker workload."),
            "controller" to JsonPrimitive("third covered thread submits/waits/samples only; excluded from compute totals"),
            "sampling" to JsonPrimitive("Both workers parked at endpoints, root and descendants quiesced; symmetric parking overhead included in worker counters, assets/fixture/assertions excluded."),
            "tailCaveat" to JsonPrimitive("four paired batches; max observed is not stable P95; no absolute wall assertion"),
            "allPairOutputsExact" to JsonPrimitive(true),
            "threads" to JsonArray(ids.mapIndexed { i, id -> JsonObject(linkedMapOf(
                "id" to JsonPrimitive(id), "name" to JsonPrimitive(metrics.threadName(id)),
                "role" to JsonPrimitive(if (i < 2) "actual_shared_compute_worker" else "noncompute_controller_sampler")
            )) }),
            "javaVersion" to JsonPrimitive(System.getProperty("java.version")),
            "os" to JsonPrimitive(System.getProperty("os.name")),
            "baseline" to samplesJson(old), "new" to samplesJson(new),
            "baselineSummary" to summaryJson(old), "newSummary" to summaryJson(new),
            "allocationReductionBytes" to JsonPrimitive(oldBytes - newBytes),
            "allocationMedianDecreased" to JsonPrimitive(newBytes < oldBytes)
        )))
        assertTrue("Worker allocation must be measured, positive and readable", oldBytes > 0 && newBytes > 0)
        // Small-pool prep can legitimately regress: report the measured signed delta, do not hide it.
        // The cold large planner must still reduce allocation, while small-pool results remain diagnostic.
        if (requireBothWorkersCpu) assertTrue("Cold planner median allocation must decrease", newBytes < oldBytes)
    }

    /** Test-only cache isolation: exact existing entry objects and their LRU order survive this test. */
    private fun withIsolatedSharedIndexCache(action: (() -> Unit) -> Unit) {
        val cache = LabyrinthCombinationIndexCache.shared
        val entriesField = cache.javaClass.getDeclaredField("entries").apply { isAccessible = true }
        val bytesField = cache.javaClass.getDeclaredField("residentBytes").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val entries = entriesField.get(cache) as MutableMap<Any, Any>
        val parked = parkWorkers()
        val savedState = try { synchronized(cache) {
            entries.entries.map { it.key to it.value } to bytesField.getLong(cache)
        } } finally { parked.releaseAndJoin() }
        val (saved, savedBytes) = savedState
        try {
            action {
                // measure() invokes this only while BOTH workers are parked, before its snapshot.
                synchronized(cache) {
                    entries.clear()
                    bytesField.setLong(cache, 512L)
                    check(entries.isEmpty() && bytesField.getLong(cache) == 512L)
                }
            }
        } finally {
            val restorePark = parkWorkers()
            try { synchronized(cache) {
                entries.clear()
                saved.forEach { (key, value) -> entries[key] = value }
                bytesField.setLong(cache, savedBytes)
                check(entries.entries.map { it.key to it.value } == saved)
                check(bytesField.getLong(cache) == savedBytes)
            } } finally { restorePark.releaseAndJoin() }
        }
    }

    private fun runPair(pair: Batch6Round1PolicyPair, inputs: List<Batch3RoleInput>, baselineFirst: Boolean,
        metrics: Batch6Round1JvmMetrics, ids: LongArray, label: String): Pair<Measured, Measured> {
        val old: Measured
        val new: Measured
        if (baselineFirst) {
            old = measure(metrics, ids) { inputs.map(pair::baseline) }
            new = measure(metrics, ids) { inputs.map(pair::optimized) }
        } else {
            new = measure(metrics, ids) { inputs.map(pair::optimized) }
            old = measure(metrics, ids) { inputs.map(pair::baseline) }
        }
        batch3AssertExact(label, old.values, new.values) // outside all measurement windows
        return old to new
    }

    private fun measure(metrics: Batch6Round1JvmMetrics, ids: LongArray,
        beforeSample: () -> Unit = {}, requireBothWorkersCpu: Boolean = true,
        action: () -> List<LabyrinthOneRoleDecision>): Measured {
        val parkedBefore = parkWorkers()
        val before: Counters
        try {
            assertEquals(ids.take(2).toSet(), parkedBefore.ids.toSet())
            beforeSample() // cache reset only; both workers parked, outside counter and wall windows
            before = metrics.snapshot(ids)
        } finally { parkedBefore.releaseAndJoin() }
        val quiesced = CountDownLatch(1)
        val handle = LabyrinthFourThreadRuntime.submitWork(WorkKind.PLANNING,
            onQuiesced = { quiesced.countDown() }) {
            check(LabyrinthFourThreadRuntime.isComputeThread())
            val start = System.nanoTime()
            val values = action()
            Timed(values, System.nanoTime() - start)
        }
        val result = try { handle.future.get(300, TimeUnit.SECONDS) }
        finally { check(quiesced.await(300, TimeUnit.SECONDS)) { "Root and children did not quiesce" } }
        val parkedAfter = parkWorkers()
        val after: Counters
        try {
            assertEquals(ids.take(2).toSet(), parkedAfter.ids.toSet())
            after = metrics.snapshot(ids)
        } finally { parkedAfter.releaseAndJoin() }
        val cpu = ids.indices.map { after.cpu[it] - before.cpu[it] }
        val allocated = ids.indices.map { after.allocated[it] - before.allocated[it] }
        assertTrue("CPU counters remain readable and monotonic", cpu.all { it >= 0 })
        assertTrue("Allocation counters remain readable and monotonic", allocated.all { it >= 0 })
        assertTrue("Covered actual workers must contribute CPU in total", cpu.take(2).sum() > 0)
        assertTrue("Covered actual workers must allocate in total", allocated.take(2).sum() > 0)
        if (requireBothWorkersCpu) {
            assertTrue("Both actual compute workers contribute CPU", cpu.take(2).all { it > 0 })
            assertTrue("Both actual compute workers allocate", allocated.take(2).all { it > 0 })
        }
        return Measured(result.values, result.wallNanos, before, after, cpu, allocated)
    }

    /** Exactly TWO starters, not the deadlocking four-starter Batch4 helper. */
    private fun parkWorkers(): Parked {
        val arrived = CountDownLatch(2)
        val release = CountDownLatch(1)
        val ids = LongArray(2)
        val tasks = (0 until 2).map { i -> LabyrinthFourThreadRuntime.computeExecutor.submit(Callable {
            check(LabyrinthFourThreadRuntime.isComputeThread())
            ids[i] = Thread.currentThread().id
            arrived.countDown()
            check(release.await(30, TimeUnit.SECONDS))
        }) }
        if (!arrived.await(30, TimeUnit.SECONDS)) {
            release.countDown()
            error("Could not cover both actual shared workers")
        }
        check(ids.distinct().size == 2)
        return Parked(ids.toList(), release, tasks)
    }

    private data class Parked(val ids: List<Long>, val release: CountDownLatch, val tasks: List<Future<Unit>>) {
        fun releaseAndJoin() { release.countDown(); tasks.forEach { it.get(30, TimeUnit.SECONDS) } }
    }
    private data class Timed(val values: List<LabyrinthOneRoleDecision>, val wallNanos: Long)
    private data class Measured(val values: List<LabyrinthOneRoleDecision>, val wallNanos: Long,
        val before: Counters, val after: Counters, val cpu: List<Long>, val allocated: List<Long>) {
        val workerCpuNanos get() = cpu.take(2).sum()
        val workerAllocatedBytes get() = allocated.take(2).sum()
    }
    private fun median(values: List<Double>): Double = values.sorted().let {
        if (it.size % 2 == 0) (it[it.size / 2 - 1] + it[it.size / 2]) / 2 else it[it.size / 2]
    }
    private fun longs(values: List<Long>) = JsonArray(values.map(::JsonPrimitive))
    private fun samplesJson(values: List<Measured>) = JsonArray(values.map { m -> JsonObject(linkedMapOf(
        "wallNanos" to JsonPrimitive(m.wallNanos), "workerCpuNanos" to JsonPrimitive(m.workerCpuNanos),
        "workerAllocatedBytes" to JsonPrimitive(m.workerAllocatedBytes),
        "beforeCpuNanosByThread" to longs(m.before.cpu), "afterCpuNanosByThread" to longs(m.after.cpu),
        "beforeAllocatedBytesByThread" to longs(m.before.allocated), "afterAllocatedBytesByThread" to longs(m.after.allocated),
        "deltaCpuNanosByThread" to longs(m.cpu), "deltaAllocatedBytesByThread" to longs(m.allocated)
    )) })
    private fun summaryJson(values: List<Measured>) = JsonObject(linkedMapOf(
        "medianWallNanos" to JsonPrimitive(median(values.map { it.wallNanos.toDouble() })),
        "maxObservedWallNanos" to JsonPrimitive(values.maxOf { it.wallNanos }),
        "medianWorkerCpuNanos" to JsonPrimitive(median(values.map { it.workerCpuNanos.toDouble() })),
        "maxObservedWorkerCpuNanos" to JsonPrimitive(values.maxOf { it.workerCpuNanos }),
        "medianWorkerAllocatedBytes" to JsonPrimitive(median(values.map { it.workerAllocatedBytes.toDouble() })),
        "maxObservedWorkerAllocatedBytes" to JsonPrimitive(values.maxOf { it.workerAllocatedBytes })
    ))
}

private data class Counters(val cpu: List<Long>, val allocated: List<Long>)

/** No java.management import: Android compile bootclasspath lacks these JDK-only interfaces. */
private class Batch6Round1JvmMetrics {
    private val factory = Class.forName("java.lang.management.ManagementFactory")
    private val bean = factory.getMethod("getThreadMXBean").invoke(null)
    private val threads = Class.forName("java.lang.management.ThreadMXBean")
    private val allocations = Class.forName("com.sun.management.ThreadMXBean")
    private val cpu = threads.getMethod("getThreadCpuTime", Long::class.javaPrimitiveType)
    private val bytes = allocations.getMethod("getThreadAllocatedBytes", LongArray::class.java)
    private val info = threads.getMethod("getThreadInfo", Long::class.javaPrimitiveType)
    private val name = Class.forName("java.lang.management.ThreadInfo").getMethod("getThreadName")
    init {
        check(allocations.isInstance(bean))
        check(threads.getMethod("isThreadCpuTimeSupported").invoke(bean) == true)
        if (threads.getMethod("isThreadCpuTimeEnabled").invoke(bean) != true)
            threads.getMethod("setThreadCpuTimeEnabled", Boolean::class.javaPrimitiveType).invoke(bean, true)
        check(threads.getMethod("isThreadCpuTimeEnabled").invoke(bean) == true)
        check(allocations.getMethod("isThreadAllocatedMemorySupported").invoke(bean) == true)
        if (allocations.getMethod("isThreadAllocatedMemoryEnabled").invoke(bean) != true)
            allocations.getMethod("setThreadAllocatedMemoryEnabled", Boolean::class.javaPrimitiveType).invoke(bean, true)
        check(allocations.getMethod("isThreadAllocatedMemoryEnabled").invoke(bean) == true)
    }
    fun threadName(id: Long): String = name.invoke(checkNotNull(info.invoke(bean, id))) as String
    fun snapshot(ids: LongArray): Counters {
        val cpuValues = ids.map { cpu.invoke(bean, it) as Long }
        val allocations = (bytes.invoke(bean, ids as Any) as LongArray).toList()
        check(cpuValues.all { it >= 0 } && allocations.all { it >= 0 }) { "Required per-thread counters unavailable" }
        return Counters(cpuValues, allocations)
    }
}
