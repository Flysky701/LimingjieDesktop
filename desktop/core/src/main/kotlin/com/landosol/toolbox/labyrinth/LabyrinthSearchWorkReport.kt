package com.landosol.toolbox.labyrinth

/** Optional test/diagnostic counters. No resident score cache or per-combination callback. */
internal data class LabyrinthSearchWorkReport(
    val poolSize: Int,
    val teamSize: Int,
    val requiredIndex: Int,
    val fullCombinationCount: Long,
    val visitedCombinationCount: Long,
    val scoredCombinationCount: Long,
    val retainedCandidateCopies: Long,
    val shardVisitedCounts: List<Long>,
)

internal class LabyrinthSearchWorkCounts {
    var visited = 0L
    var scored = 0L
    var retained = 0L
}
