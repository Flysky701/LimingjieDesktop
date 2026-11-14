package com.landosol.toolbox.labyrinth

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

/** Diagnostic only: these are host JVM wall-clock samples, NEVER Android performance claims. */
class Batch3RoleChoicePolicyBenchmarkTest {
    @Test fun hostJvmObservedRewardPairsWarmupThenAlternatingABBAEmitMedianMilliseconds() {
        val runtime = batch3ProductionRuntime()
        val inputs = batch3ObservedInputs(runtime)
        inputs.forEachIndexed { scenario, input ->
            val pair = Batch3PolicyPair(runtime.document.scoring, runtime.document.roleChoice)
            // A = unchanged batch2 oracle; B = batch3. Neither gets a different optimizer/thread count.
            // Asset parsing, fixture construction and all equality assertions stay outside timed calls.
            repeat(2) { index -> runPair(pair, input, baselineFirst = index % 2 == 0, label = "warmup-$scenario-$index") }
            val samples = (0 until 4).map { index ->
                runPair(pair, input, baselineFirst = index % 2 == 0, label = "measured-$scenario-$index")
            }
            val baselineMs = samples.map { it.baselineNanos / 1_000_000.0 }
            val batch3Ms = samples.map { it.batch3Nanos / 1_000_000.0 }
            val chosen = samples.last().decision as LabyrinthOneRoleDecision.Ready
            // No timing/speedup assertion: scheduler, GC and JIT variation are expected on a host.
            val record = JsonObject(linkedMapOf(
                "schemaVersion" to JsonPrimitive(1),
                "scope" to JsonPrimitive("host_jvm_not_android"),
                "scenario" to JsonPrimitive(if (scenario == 0) "observed_frame_2017_maho" else "observed_frame_2028_tsumugi"),
                "acquiredCount" to JsonPrimitive(input.acquiredIds.size),
                "candidateIds" to JsonArray(input.candidateIds.map { JsonPrimitive(it) }),
                "warmupPairs" to JsonPrimitive(2),
                "measuredPairs" to JsonPrimitive(samples.size),
                "order" to JsonArray(listOf("AB", "BA", "AB", "BA").map { JsonPrimitive(it) }),
                "timeUnit" to JsonPrimitive("milliseconds"),
                "batch2SamplesMs" to JsonArray(baselineMs.map { JsonPrimitive(it) }),
                "batch3SamplesMs" to JsonArray(batch3Ms.map { JsonPrimitive(it) }),
                "batch2MedianMs" to JsonPrimitive(median(baselineMs)),
                "batch3MedianMs" to JsonPrimitive(median(batch3Ms)),
                "outputsExact" to JsonPrimitive(true),
                "chosenId" to JsonPrimitive(chosen.chosen.characterId),
                "selectedLayer" to JsonPrimitive(chosen.rankings.first().candidateLayer),
                "exhaustiveRosterLimit" to JsonPrimitive(runtime.document.scoring.exhaustiveRosterLimit),
                "javaVersion" to JsonPrimitive(System.getProperty("java.version")),
                "availableProcessors" to JsonPrimitive(Runtime.getRuntime().availableProcessors()),
                "maxHeapBytes" to JsonPrimitive(Runtime.getRuntime().maxMemory()),
            ))
            println("BATCH3_BENCHMARK_JSON=$record")
        }
        assertEquals(2, inputs.size)
    }

    private fun runPair(pair: Batch3PolicyPair, input: Batch3RoleInput, baselineFirst: Boolean, label: String): Sample {
        val baseline: TimedDecision
        val optimized: TimedDecision
        if (baselineFirst) {
            baseline = timed { pair.baseline(input) }
            optimized = timed { pair.optimized(input) }
        } else {
            optimized = timed { pair.optimized(input) }
            baseline = timed { pair.baseline(input) }
        }
        batch3AssertExact(label, baseline.decision, optimized.decision)
        return Sample(baseline.nanos, optimized.nanos, optimized.decision)
    }

    private fun timed(action: () -> LabyrinthOneRoleDecision): TimedDecision {
        val start = System.nanoTime()
        val decision = action()
        return TimedDecision(decision, System.nanoTime() - start)
    }

    private fun median(values: List<Double>): Double {
        val sorted = values.sorted()
        return (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2.0
    }

    private data class TimedDecision(val decision: LabyrinthOneRoleDecision, val nanos: Long)
    private data class Sample(val baselineNanos: Long, val batch3Nanos: Long, val decision: LabyrinthOneRoleDecision)
}
