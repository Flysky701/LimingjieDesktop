package com.landosol.toolbox.labyrinth

import com.landosol.toolbox.labyrinth.concurrent.LabyrinthHelpingExecutor
import com.landosol.toolbox.labyrinth.concurrent.WorkKind
import com.landosol.toolbox.labyrinth.concurrent.WorkToken
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Host-only diagnostic: same JVM/input/algorithm/cache, alternating two/four owned CPU workers. */
class LabyrinthAdaptiveThreadBenchmarkTest {
    @Test fun sameRealLedgersTwoAndFourWorkersAlternatingPairsHaveExactOutputs() {
        assertEquals("17", System.getProperty("java.specification.version"))
        val runtime = batch3ProductionRuntime()
        val fixtures = batch6Round1Fixtures(runtime).takeLast(2)
        val inputs = fixtures.map { it.input }
        val metrics = Metrics()
        val two = Lane(2, runtime.document.scoring, runtime.document.roleChoice)
        val four = Lane(4, runtime.document.scoring, runtime.document.roleChoice)
        try {
            fun pair(index: Int): Pair<Sample, Sample> {
                val a: Sample
                val b: Sample
                if (index % 2 == 0) { a = two.measure(inputs, metrics); b = four.measure(inputs, metrics) }
                else { b = four.measure(inputs, metrics); a = two.measure(inputs, metrics) }
                batch3AssertExact("adaptive-worker-pair-$index", a.values, b.values)
                return a to b
            }
            repeat(2) { pair(it) }
            val observations = (0 until 4).map(::pair)
            val a = observations.map { it.first }
            val b = observations.map { it.second }
            val report = JsonObject(linkedMapOf(
                "schemaVersion" to JsonPrimitive(1),
                "scope" to JsonPrimitive("host_jdk17_same_jvm_two_and_four_actual_owned_workers_not_android_guest_or_host_total"),
                "algorithm" to JsonPrimitive("same_current_role_policy_only_worker_and_shard_budget_differ"),
                "fixtures" to JsonArray(fixtures.map { JsonPrimitive(it.label) }),
                "rosterSizes" to JsonArray(inputs.map { JsonPrimitive(it.acquiredIds.size) }),
                "warmupPairs" to JsonPrimitive(2), "measuredPairs" to JsonPrimitive(4),
                "order" to JsonArray(listOf("AB", "BA", "AB", "BA").map(::JsonPrimitive)),
                "allPairOutputsExact" to JsonPrimitive(true),
                "androidPerformanceGuarantee" to JsonPrimitive(false),
                "visibleProcessors" to JsonPrimitive(Runtime.getRuntime().availableProcessors()),
                "javaVersion" to JsonPrimitive(System.getProperty("java.version")),
                "maxHeapBytes" to JsonPrimitive(Runtime.getRuntime().maxMemory()),
                "measurement" to JsonPrimitive("Wall measures policy body only; CPU/allocation sums actual owned worker counters, excludes controller/assets/JSON; root and descendants quiesced then every worker parked at endpoints. Counters include symmetric parking/cleanup overhead, with two additional parking tasks on four-worker side."),
                "tailCaveat" to JsonPrimitive("Four paired batches only; maximum is an observed tail, not stable P95. No assertion of speedup or lower total CPU/allocation."),
                "twoWorkers" to samples(a), "fourWorkers" to samples(b),
                "twoWorkerSummary" to summary(a), "fourWorkerSummary" to summary(b)
            ))
            println("ADAPTIVE_THREADS_BENCHMARK_JSON=$report")
        } finally { two.close(); four.close() }
    }

    private class Lane(val count: Int, scoring: LabyrinthTeamScoringConfig, choice: LabyrinthRoleChoiceConfig) {
        val pool = LabyrinthHelpingExecutor(threadPrefix = "adaptive-benchmark-$count", workerCount = count)
        val policy = LabyrinthRoleChoicePolicy(LabyrinthTeamOptimizer(LabyrinthTeamScorer(scoring), searchThreads = count, searchExecutor = pool), choice)
        fun park(): Parked {
            val arrived = CountDownLatch(count)
            val release = CountDownLatch(1)
            val ids = LongArray(count)
            val tasks = (0 until count).map { i -> pool.submit(Callable {
                ids[i] = Thread.currentThread().id
                arrived.countDown(); check(release.await(30, TimeUnit.SECONDS)); Unit
            }) }
            if (!arrived.await(30, TimeUnit.SECONDS)) { release.countDown(); error("not all workers parked") }
            check(ids.distinct().size == count)
            return Parked(ids.sorted().toLongArray(), release, tasks)
        }
        fun measure(inputs: List<Batch3RoleInput>, metrics: Metrics): Sample {
            val startPark = park()
            val ids = startPark.ids
            val before = try { metrics.snapshot(ids) } finally { startPark.releaseAndJoin() }
            val quiet = CountDownLatch(1)
            val handle = pool.submitRoot(WorkKind.PLANNING, WorkToken(), {}, { quiet.countDown() }) {
                val start = System.nanoTime()
                val values = inputs.map { input -> policy.chooseOneRole(input.candidateIds, input.acquiredIds, input.profiles, input.context, input.strict) }
                values to (System.nanoTime() - start)
            }
            val timed = try { handle.future.get(300, TimeUnit.SECONDS) }
                finally { check(quiet.await(300, TimeUnit.SECONDS)) }
            val endPark = park()
            val after = try { check(ids.contentEquals(endPark.ids)); metrics.snapshot(ids) }
                finally { endPark.releaseAndJoin() }
            val cpu = ids.indices.map { after.cpu[it] - before.cpu[it] }
            val allocated = ids.indices.map { after.allocated[it] - before.allocated[it] }
            check(cpu.all { it >= 0 } && allocated.all { it >= 0 })
            check(cpu.sum() > 0 && allocated.sum() > 0)
            return Sample(timed.first, timed.second, ids.toList(), before, after, cpu, allocated)
        }
        fun close() { pool.shutdownNow(); check(pool.awaitTermination(30, TimeUnit.SECONDS)) }
    }
    private data class Parked(val ids: LongArray, val release: CountDownLatch, val tasks: List<Future<Unit>>) {
        fun releaseAndJoin() { release.countDown(); tasks.forEach { it.get(30, TimeUnit.SECONDS) } }
    }
    private data class Counters(val cpu: List<Long>, val allocated: List<Long>)
    private data class Sample(val values: List<LabyrinthOneRoleDecision>, val wallNanos: Long,
        val ids: List<Long>, val before: Counters, val after: Counters, val cpu: List<Long>, val allocated: List<Long>)
    private fun longs(values: List<Long>) = JsonArray(values.map(::JsonPrimitive))
    private fun samples(values: List<Sample>) = JsonArray(values.map { s -> JsonObject(linkedMapOf(
        "wallNanos" to JsonPrimitive(s.wallNanos), "workerCpuNanos" to JsonPrimitive(s.cpu.sum()),
        "workerAllocatedBytes" to JsonPrimitive(s.allocated.sum()), "actualWorkerIds" to longs(s.ids),
        "beforeCpuByWorker" to longs(s.before.cpu), "afterCpuByWorker" to longs(s.after.cpu),
        "beforeAllocationByWorker" to longs(s.before.allocated), "afterAllocationByWorker" to longs(s.after.allocated),
        "deltaCpuByWorker" to longs(s.cpu), "deltaAllocationByWorker" to longs(s.allocated)
    )) })
    private fun median(values: List<Long>): Double = values.sorted().let { (it[it.size / 2 - 1].toDouble() + it[it.size / 2]) / 2 }
    private fun summary(values: List<Sample>) = JsonObject(linkedMapOf(
        "medianWallNanos" to JsonPrimitive(median(values.map { it.wallNanos })),
        "maxObservedWallNanos" to JsonPrimitive(values.maxOf { it.wallNanos }),
        "medianWorkerCpuNanos" to JsonPrimitive(median(values.map { it.cpu.sum() })),
        "medianWorkerAllocatedBytes" to JsonPrimitive(median(values.map { it.allocated.sum() }))
    ))
    /** JDK interfaces accessed reflectively: Android's compile bootclasspath lacks java.management. */
    private class Metrics {
        val bean = Class.forName("java.lang.management.ManagementFactory").getMethod("getThreadMXBean").invoke(null)
        val threads = Class.forName("java.lang.management.ThreadMXBean")
        val allocations = Class.forName("com.sun.management.ThreadMXBean")
        val cpu = threads.getMethod("getThreadCpuTime", Long::class.javaPrimitiveType)
        val bytes = allocations.getMethod("getThreadAllocatedBytes", LongArray::class.java)
        init {
            check(allocations.isInstance(bean))
            check(threads.getMethod("isThreadCpuTimeSupported").invoke(bean) == true)
            threads.getMethod("setThreadCpuTimeEnabled", Boolean::class.javaPrimitiveType).invoke(bean, true)
            check(allocations.getMethod("isThreadAllocatedMemorySupported").invoke(bean) == true)
            allocations.getMethod("setThreadAllocatedMemoryEnabled", Boolean::class.javaPrimitiveType).invoke(bean, true)
        }
        fun snapshot(ids: LongArray): Counters {
            val cpuValues = ids.map { cpu.invoke(bean, it) as Long }
            val allocated = (bytes.invoke(bean, ids as Any) as LongArray).toList()
            check(cpuValues.all { it >= 0 } && allocated.all { it >= 0 })
            return Counters(cpuValues, allocated)
        }
    }
}
