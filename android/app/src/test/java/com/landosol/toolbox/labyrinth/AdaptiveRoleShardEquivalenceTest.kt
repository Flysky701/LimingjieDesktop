package com.landosol.toolbox.labyrinth

import com.landosol.toolbox.labyrinth.concurrent.LabyrinthFourThreadRuntime
import com.landosol.toolbox.labyrinth.concurrent.WorkKind
import com.landosol.toolbox.labyrinth.concurrent.WorkToken
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class AdaptiveRoleShardEquivalenceTest {
    @Test fun defaultShardLimitAndExecutorComeFromTheActualSharedRuntime() {
        val optimizer = LabyrinthTeamOptimizer(LabyrinthTeamScorer(batch3ScoringConfig()))
        val threads = optimizer.javaClass.getDeclaredField("searchThreads").also { it.isAccessible = true }
        val executor = optimizer.javaClass.getDeclaredField("searchExecutor").also { it.isAccessible = true }
        assertTrue(LabyrinthFourThreadRuntime.cpuWorkerBudget in listOf(2, 4))
        assertEquals(LabyrinthFourThreadRuntime.cpuWorkerBudget, threads.getInt(optimizer))
        assertSame(LabyrinthFourThreadRuntime.computeExecutor, executor.get(optimizer))
    }

    @Test fun everyPublicSearchMatchesTheFrozenSequentialBaselineOnTwoAndFourWorkersAndShards() {
        val scoring = batch3ScoringConfig()
        val sequential = Batch4OptimizerPair(scoring, searchThreads = 1)
        val cases = listOf(0, 1, 4, 5, 11, 12, 13, 14).map { size ->
            val input = batch3SyntheticInput(size)
            input.acquiredIds.map(input.profiles::getValue) to input.context
        }
        val expected = cases.map { (roster, context) -> sequential.snapshotReference(roster, context) }
        for (workers in listOf(2, 4)) withAdaptiveShardExecutor(workers, "role-matrix") { pool, checked ->
            for (shards in listOf(2, 4)) {
                val optimizer = LabyrinthTeamOptimizer(LabyrinthTeamScorer(scoring), shards, searchExecutor = checked)
                cases.forEachIndexed { index, (roster, context) ->
                    val actual = runAdaptiveShardRoot(pool) { snapshot(optimizer, roster, context) }
                    batch3AssertExact("workers=$workers/shards=$shards/size=${roster.size}", expected[index], actual)
                }
            }
            assertTrue(checked.executedShardCount.get() > 0)
            assertTrue(checked.childThreadNames.all { it.startsWith("adaptive-role-matrix-$workers-") })
            assertTrue(checked.childThreadNames.size <= workers)
        }
    }

    @Test fun tiedScoresReversedInputsAndRequiredMembersKeepTheExactSequentialEnumeration() {
        val input = batch3SyntheticInput(14, tied = true)
        val roster = input.acquiredIds.map(input.profiles::getValue)
        val scoring = batch3ScoringConfig()
        val sequential = Batch4OptimizerPair(scoring, searchThreads = 1)
        for (workers in listOf(2, 4)) withAdaptiveShardExecutor(workers, "role-ties") { pool, checked ->
            for (shards in listOf(2, 4)) for (ordered in listOf(roster, roster.reversed())) {
                val optimizer = LabyrinthTeamOptimizer(LabyrinthTeamScorer(scoring), shards, searchExecutor = checked)
                val expected = sequential.snapshotReference(ordered, input.context)
                val actual = runAdaptiveShardRoot(pool) { snapshot(optimizer, ordered, input.context) }
                batch3AssertExact("tie-$workers-$shards-${ordered.first().characterId}", expected, actual)
                val best = actual.first() as LabyrinthTeamEvaluation
                assertEquals(ordered.take(5).map { it.characterId }, best.members.map { it.characterId })
            }
        }
    }

    @Test fun smallSearchesRemainSerialAndUnevenConstrainedRangesVisitEveryOriginalIndexOnce() {
        data class Case(val size: Int, val required: String?, val visits: Long)
        val cases = listOf(Case(11, null, 462), Case(12, null, 792), Case(13, null, 1287),
            Case(14, "r0", 715), Case(14, "r7", 715), Case(14, "r13", 715))
        val scoring = batch3ScoringConfig()
        val sequential = Batch4OptimizerPair(scoring, searchThreads = 1)
        for (workers in listOf(2, 4)) withAdaptiveShardExecutor(workers, "role-ranges") { pool, checked ->
            for (shards in listOf(2, 4)) for (case in cases) {
                val input = batch3SyntheticInput(case.size, tied = true)
                val roster = input.acquiredIds.map(input.profiles::getValue)
                val optimizer = LabyrinthTeamOptimizer(LabyrinthTeamScorer(scoring), shards, searchExecutor = checked)
                val reports = ArrayList<LabyrinthSearchWorkReport>()
                optimizer.searchWorkObserver = { reports.add(it) }
                val expected = sequential.reference.bestSystemFormation(roster, input.context, case.required)
                val actual = runAdaptiveShardRoot(pool) {
                    optimizer.bestSystemFormation(roster, input.context, case.required)
                }
                batch3AssertExact("range-$workers-$shards-$case", expected, actual)
                assertEquals(1, reports.size)
                val report = reports.single()
                val count = if (case.visits < 512) 1 else shards
                val base = case.visits / count
                val extra = (case.visits % count).toInt()
                val expectedVisits = List(count) { base + if (it < extra) 1 else 0 }
                assertEquals(expectedVisits, report.shardVisitedCounts)
                assertEquals(case.visits, report.visitedCombinationCount)
                assertEquals(case.visits, report.shardVisitedCounts.sum())
                assertTrue("no empty scoring shard", report.shardVisitedCounts.all { it > 0 })
            }
        }
    }

    @Test fun saturatedCpuParentsRestoreTheirOwnContextsAndMatchSequentialSnapshots() {
        val scoring = batch3ScoringConfig()
        val sequential = Batch4OptimizerPair(scoring, searchThreads = 1)
        for (workers in listOf(2, 4)) withAdaptiveShardExecutor(workers, "role-simultaneous") { pool, checked ->
            val optimizer = LabyrinthTeamOptimizer(LabyrinthTeamScorer(scoring), 4, searchExecutor = checked)
            val cases = (0 until workers).map { index ->
                val input = batch3SyntheticInput(12 + index % 2)
                val roster = input.acquiredIds.map(input.profiles::getValue)
                (if (index % 2 == 0) roster else roster.reversed()) to
                    input.context.copy(defenseMarkStacks = index, targetCount = index % 3 + 1)
            }
            val expected = cases.map { (roster, context) -> sequential.snapshotReference(roster, context) }
            val barrier = CyclicBarrier(workers)
            val quiesced = List(workers) { CountDownLatch(1) }
            val handles = cases.mapIndexed { index, (roster, context) ->
                val token = WorkToken()
                pool.submitRoot(WorkKind.PLANNING, token, {}, quiesced[index]::countDown) {
                    barrier.await(10, TimeUnit.SECONDS)
                    val result = snapshot(optimizer, roster, context)
                    assertSame(token, LabyrinthFourThreadRuntime.currentToken())
                    result
                }
            }
            try {
                handles.forEachIndexed { index, handle ->
                    batch3AssertExact("simultaneous-$workers-$index", expected[index], handle.future.get(30, TimeUnit.SECONDS))
                }
            } finally {
                handles.forEach { if (!it.future.isDone) it.cancel(false) }
                quiesced.forEach { assertTrue(it.await(30, TimeUnit.SECONDS)) }
            }
            assertTrue(checked.executedShardCount.get() >= workers * 4)
            assertTrue(checked.childThreadNames.all { it.startsWith("adaptive-role-simultaneous-$workers-") })
            assertTrue(checked.childThreadNames.size <= workers)
        }
    }

    private fun snapshot(
        optimizer: LabyrinthTeamOptimizer,
        roster: List<LabyrinthRoleProfile>,
        context: LabyrinthRoleDecisionContext,
    ): List<Any?> = listOf(
        optimizer.bestFormation(roster, context), optimizer.bestSystemFormation(roster, context),
        optimizer.rankedFormations(roster, context), optimizer.rankedBattleFormations(roster, context),
        optimizer.bestBattleFormation(roster, context), optimizer.bestSupplementFormation(roster, context),
        optimizer.bestSystemFormation(roster, context, roster.lastOrNull()?.characterId ?: "missing"),
        optimizer.bestFormation(roster, context, "missing"),
        optimizer.bestBattleFormationIncluding(roster, context, setOf("missing")),
        optimizer.bestBattleFormationIncluding(roster, context, roster.take(2).mapTo(linkedSetOf()) { it.characterId }),
        optimizer.bestBattleFormationIncluding(roster, context, roster.take(6).mapTo(linkedSetOf()) { it.characterId }),
    )
}
