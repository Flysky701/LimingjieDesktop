package com.landosol.toolbox.labyrinth

import com.landosol.toolbox.protocol.labyrinth.LabyrinthMapNode
import com.landosol.toolbox.protocol.labyrinth.LabyrinthRoute

private val exCounts = listOf(0, 1, 1, 2, 2)

// Search only actual forward edges. A cached failure includes every remaining constraint.
internal fun highScoreRoute(
    enterId: Long, nodes: List<LabyrinthMapNode>, policy: LabyrinthRoutePolicy, currentId: Long?,
): RouteSearchResult {
    val byId = nodes.associateBy { it.blockId }
    if (byId.size != nodes.size || currentId != null && currentId !in byId) return RouteSearchResult.NoRoute
    data class State(val id: Long, val ex: Int, val missingIds: Set<Long>, val missingTypes: Set<Int>)
    val failed = mutableSetOf<State>()
    val route = mutableListOf<LabyrinthMapNode>()
    fun walk(n: LabyrinthMapNode, previousEx: Int, ids: Set<Long>, types: Set<Int>): Boolean {
        val last = policy.lastColumn(n.area) ?: return false
        val ex = previousEx + if (n.blockType == 3) 1 else 0
        if (n.blockId in policy.forbiddenBlockIds || n.blockType in policy.forbiddenBlockTypes ||
            ex > exCounts[n.area - 1] || n.column == last && ex != exCounts[n.area - 1]) return false
        val expected = when (n.area to n.column) {
            1 to 3, 2 to 6, 3 to 4, 4 to 2, 4 to 7 -> 4
            1 to 5, 2 to 2 -> 5
            2 to 4 -> 7
            else -> null
        }
        if (expected != null && n.blockType != expected) return false
        if (n.column == last && n.area in setOf(3, 5) &&
            (n.blockType != LabyrinthRoutePolicy.BOSS_BLOCK_TYPE || !policy.bossMatches(n.area, listOf(n)))) return false
        val remaining = State(n.blockId, ex, ids - n.blockId, types - n.blockType)
        if (remaining in failed) return false
        route.add(n)
        if (n.column == last && n.area == 5) {
            if (remaining.missingIds.isEmpty() && remaining.missingTypes.isEmpty() && policy.acceptsGeneric(route)) return true
        } else for (id in n.nextBlockIds) {
            val next = byId[id] ?: continue
            val valid = if (n.column == last) next.area == n.area + 1 && next.column == 1
                else next.area == n.area && next.column == n.column + 1
            if (valid && walk(next, if (next.area == n.area) ex else 0, remaining.missingIds, remaining.missingTypes)) return true
        }
        route.removeAt(route.lastIndex)
        failed.add(remaining)
        return false
    }
    val required = if (currentId == null) policy.requiredBlockIds else policy.requiredBlockIds + currentId
    for (start in nodes.filter { it.area == 1 && it.column == 1 }) {
        if (walk(start, 0, required, policy.requiredBlockTypes)) return RouteSearchResult.Found(LabyrinthRoute(enterId, route.toList()))
    }
    return RouteSearchResult.NoRoute
}
