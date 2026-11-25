package com.landosol.toolbox.labyrinth.kernel.planning

import com.landosol.toolbox.labyrinth.node.LabyrinthNodeTypes as Node
import com.landosol.toolbox.protocol.labyrinth.LabyrinthMapNode

class RouteGraph internal constructor(
    val nodes: Map<Long, LabyrinthMapNode>,
    val children: Map<Long, List<Long>>,
    val starts: List<Long>,
    val order: List<Long>,
    val areaEnds: Set<Long>,
    val clearEnds: Set<Long>,
)

/**
 * Adapts the full map from LabyrinthEnter.map, LabyrinthResume.map or a saved route's toFullMap().
 * Every alternative needs to be present for the planner to evaluate its recorded edges.
 */
fun Iterable<LabyrinthMapNode>.toPlanningRouteGraph(): RouteGraph {
    val byId = mutableMapOf<Long, LabyrinthMapNode>()
    for (node in this) {
        require(byId.put(node.blockId, node) == null) { "Duplicate block ID ${node.blockId}" }
    }
    require(byId.isNotEmpty()) { "Empty map" }

    val areas = byId.values.groupBy(LabyrinthMapNode::area).toSortedMap()
    val floors = areas.keys.toList()
    val starts = mutableMapOf<Int, List<Long>>()
    val areaEnds = mutableSetOf<Long>()
    for ((area, group) in areas) {
        val first = group.minOf(LabyrinthMapNode::column)
        val scored = group.filter { it.blockType != Node.CLEAR && it.blockType != Node.UNKNOWN }
        val last = scored.maxOfOrNull(LabyrinthMapNode::column) ?: first
        starts[area] = group.filter { it.column == first }.map(LabyrinthMapNode::blockId)
        scored.filter { it.isAreaLastPoint || it.column == last }.mapTo(areaEnds, LabyrinthMapNode::blockId)
    }

    val children = mutableMapOf<Long, List<Long>>()
    val parents = byId.keys.associateWith { mutableListOf<Long>() }
    val clearEnds = mutableSetOf<Long>()
    for ((index, area) in floors.withIndex()) {
        val nextArea = floors.getOrNull(index + 1)
        for (node in areas.getValue(area)) {
            val id = node.blockId
            val edges = node.nextBlockIds.filter { it != Node.CLEAR.toLong() || it in byId }
            for (child in edges) {
                require(child in byId) { "Missing block $child referenced by $id" }
            }
            val crossesArea = edges.any { byId.getValue(it).area != area }
            if (crossesArea) areaEnds.add(id)
            val stitched = if (nextArea != null && id in areaEnds && !crossesArea) {
                edges + starts.getValue(nextArea)
            } else {
                edges
            }
            children[id] = stitched.distinct()
            for (child in children.getValue(id)) parents.getValue(child).add(id)
            if (stitched.isEmpty() && nextArea == null && (id in areaEnds || node.blockType == Node.CLEAR)) {
                clearEnds.add(id)
            }
        }
    }
    return RouteGraph(byId, children, starts.getValue(floors.first()), topologicalOrder(parents), areaEnds, clearEnds)
}

private fun topologicalOrder(parents: Map<Long, List<Long>>): List<Long> {
    val pending = mutableMapOf<Long, Int>()
    val successors = mutableMapOf<Long, MutableList<Long>>()
    // Match graphlib's insertion order so converging paths accumulate mass in the same order.
    for ((id, predecessors) in parents) {
        pending[id] = predecessors.size
        for (parent in predecessors) {
            pending.putIfAbsent(parent, 0)
            successors.getOrPut(parent) { mutableListOf() }.add(id)
        }
    }
    val ready = ArrayDeque(pending.filterValues { it == 0 }.keys)
    return buildList {
        while (ready.isNotEmpty()) {
            val id = ready.removeFirst()
            add(id)
            for (child in successors[id].orEmpty()) {
                val count = pending.getValue(child) - 1
                pending[child] = count
                if (count == 0) ready.addLast(child)
            }
        }
        require(size == parents.size) { "Cyclic labyrinth map" }
    }
}
