package com.landosol.toolbox.labyrinth

import com.landosol.toolbox.protocol.labyrinth.LabyrinthMapNode
import com.landosol.toolbox.protocol.labyrinth.LabyrinthRoute

data class RouteConstraints(
    val maxNodes: Int = 100,
    val isGoal: (LabyrinthMapNode) -> Boolean,
    val acceptRoute: (List<LabyrinthMapNode>) -> Boolean = { true },
    /** Optional explicit start filter; the original labyrinth matcher starts from column 1. */
    val startPredicate: ((LabyrinthMapNode) -> Boolean)? = null,
)

/**
 * 可解释的路线筛选条件。它只负责“接受/拒绝”路线，不改变服务端地图拓扑，
 * 便于以后把真实策略数据替换进来而不改搜索器。
 */
data class LabyrinthRoutePolicy(
    // 通用条件保留用于后续路线扩展；当前页面使用下面的原版开局条件。
    val requiredBlockIds: Set<Long> = emptySet(),
    val forbiddenBlockIds: Set<Long> = emptySet(),
    val requiredBlockTypes: Set<Int> = emptySet(),
    val forbiddenBlockTypes: Set<Int> = emptySet(),
    val requiredAreas: Set<Int> = emptySet(),
    val perfectStart: Boolean = false,
    val evaluationMode: LabyrinthRouteEvaluationMode = LabyrinthRouteEvaluationMode.LEGACY_TEMPLATE,
    val valueAllowance: Int = 0,
    val thirdBlockChoice: LabyrinthThirdBlockChoice = LabyrinthThirdBlockChoice.EITHER,
    val area3BossIds: Set<Int> = LabyrinthRerollOptions.defaultArea3BossIds,
    val area5BossIds: Set<Int> = LabyrinthRerollOptions.defaultArea5BossIds,
    val customNodePlan: LabyrinthCustomNodePlan = LabyrinthCustomNodePlan(),
) {
    fun targetAreas(difficulty: Int): List<Int> = if (difficulty == 1) listOf(1, 2, 3) else AREA_REQUIREMENTS.keys.toList()

    fun accepts(area: Int, route: List<LabyrinthMapNode>): Boolean {
        if (route.isEmpty()) return false
        if (!acceptsGeneric(route)) return false
        if (evaluationMode == LabyrinthRouteEvaluationMode.CUSTOM_NODES) {
            if (customNodePlan.validationError() != null || !customNodePlan.accepts(area, route)) return false
        } else if (perfectStart) {
            val requiredColumns = AREA_REQUIREMENTS[area]?.keys ?: return false
            if (route.mapTo(mutableSetOf(), LabyrinthMapNode::column) != requiredColumns) return false
            if (route.any { node -> node.blockType !in expectedTypes(area, node.column) }) return false
        }
        val selectedBosses = when (area) {
            3 -> area3BossIds
            5 -> area5BossIds
            else -> return true
        }
        if (selectedBosses.isEmpty()) return true
        val boss = route.lastOrNull { it.blockType == BOSS_BLOCK_TYPE } ?: return false
        return LabyrinthBossCatalog.unitIdForQuest(area, boss.questId) in selectedBosses
    }

    /** Compatibility overload for callers that only use generic route conditions. */
    fun accepts(route: List<LabyrinthMapNode>): Boolean = route.isNotEmpty() && acceptsGeneric(route)

    internal fun acceptsGeneric(route: List<LabyrinthMapNode>): Boolean {
        val blockIds = route.mapTo(mutableSetOf(), LabyrinthMapNode::blockId)
        val blockTypes = route.mapTo(mutableSetOf(), LabyrinthMapNode::blockType)
        val areas = route.mapTo(mutableSetOf(), LabyrinthMapNode::area)
        return requiredBlockIds.all(blockIds::contains) &&
            forbiddenBlockIds.none(blockIds::contains) &&
            requiredBlockTypes.all(blockTypes::contains) &&
            forbiddenBlockTypes.none(blockTypes::contains) &&
            requiredAreas.all(areas::contains)
    }

    fun lastColumn(area: Int): Int? = AREA_REQUIREMENTS[area]?.keys?.maxOrNull()

    internal fun bossMatches(area: Int, route: List<LabyrinthMapNode>): Boolean {
        val selectedBosses = when (area) {
            3 -> area3BossIds
            5 -> area5BossIds
            else -> return true
        }
        if (selectedBosses.isEmpty()) return true
        val boss = route.lastOrNull { it.blockType == BOSS_BLOCK_TYPE } ?: return false
        return LabyrinthBossCatalog.unitIdForQuest(area, boss.questId) in selectedBosses
    }

    private fun expectedTypes(area: Int, column: Int): Set<Int> {
        if (area in setOf(3, 5) && column == 3) {
            return when (thirdBlockChoice) {
                LabyrinthThirdBlockChoice.RELIC -> setOf(RELIC_BLOCK_TYPE)
                LabyrinthThirdBlockChoice.EVENT -> setOf(EVENT_BLOCK_TYPE)
                LabyrinthThirdBlockChoice.EITHER -> setOf(RELIC_BLOCK_TYPE, EVENT_BLOCK_TYPE)
            }
        }
        return setOfNotNull(AREA_REQUIREMENTS[area]?.get(column))
    }

    companion object {
        const val EVENT_BLOCK_TYPE = 5
        const val RELIC_BLOCK_TYPE = 6
        const val BOSS_BLOCK_TYPE = 8

        val AREA_REQUIREMENTS: Map<Int, Map<Int, Int>> = linkedMapOf(
            1 to mapOf(1 to 1, 2 to 2, 3 to 4, 4 to 2, 5 to 4, 6 to 6),
            2 to mapOf(1 to 1, 2 to 4, 3 to 2, 4 to 6, 5 to 3, 6 to 4, 7 to 6),
            3 to mapOf(1 to 1, 2 to 2, 3 to 6, 4 to 4, 5 to 3, 6 to 7, 7 to 8),
            4 to mapOf(1 to 1, 2 to 4, 3 to 3, 4 to 5, 5 to 3, 6 to 2, 7 to 4, 8 to 7),
            5 to mapOf(1 to 1, 2 to 2, 3 to 6, 4 to 3, 5 to 6, 6 to 3, 7 to 7, 8 to 8),
        )
    }
}

internal data class LabyrinthValueRouteResult(
    val route: List<LabyrinthMapNode>,
    val score: Int,
    val upperBound: Int,
) {
    val shortfall: Int get() = upperBound - score
}

/**
 * Value-route v2: derive the useful-node upper bound from the map itself, then find the
 * highest-value path through the real nextBlockIds DAG.  This deliberately does not bind
 * EX/character/relic/shop to fixed columns, so harmless generator layout shifts do not make
 * an otherwise good opening look invalid.
 */
internal class LabyrinthValueRouteEvaluator(
    private val policy: LabyrinthRoutePolicy,
) {
    fun best(
        area: Int,
        nodes: List<LabyrinthMapNode>,
        requiredBlockId: Long? = null,
    ): LabyrinthValueRouteResult? {
        if (nodes.isEmpty()) return null
        val byId = nodes.associateBy(LabyrinthMapNode::blockId)
        if (byId.size != nodes.size) return null
        val byColumn = nodes.groupBy(LabyrinthMapNode::column)
        val lastColumn = nodes.maxOfOrNull(LabyrinthMapNode::column) ?: return null
        if ((1..lastColumn).any { it !in byColumn }) return null
        if (requiredBlockId != null && requiredBlockId !in byId) return null

        val valuable = valuableTypesByColumn(area, nodes)
        val upperBound = valuable.values.count { it.isNotEmpty() }
        val memo = mutableMapOf<Pair<Long, Boolean>, ScoredPath?>()
        val inProgress = mutableSetOf<Pair<Long, Boolean>>()

        fun walk(node: LabyrinthMapNode, seenRequiredBefore: Boolean): ScoredPath? {
            val seenRequired = seenRequiredBefore || node.blockId == requiredBlockId
            val key = node.blockId to seenRequired
            if (key in memo) return memo[key]
            if (!inProgress.add(key)) return null

            val gain = if (node.blockType in valuable[node.column].orEmpty()) 1 else 0
            val preference = tieBreakPreference(area, node)
            var best: ScoredPath? = null
            if (node.column == lastColumn) {
                val candidateRoute = listOf(node)
                if ((requiredBlockId == null || seenRequired) &&
                    policy.bossMatches(area, candidateRoute)
                ) {
                    best = ScoredPath(gain, preference, candidateRoute)
                }
            } else {
                val children = node.nextBlockIds.asSequence()
                    .mapNotNull(byId::get)
                    .filter { it.area == area && it.column > node.column }
                    .sortedWith(compareBy(LabyrinthMapNode::column, LabyrinthMapNode::row, LabyrinthMapNode::blockId))
                    .toList()
                for (child in children) {
                    val suffix = walk(child, seenRequired) ?: continue
                    val candidateRoute = listOf(node) + suffix.route
                    val candidate = ScoredPath(
                        score = gain + suffix.score,
                        preference = preference + suffix.preference,
                        route = candidateRoute,
                    )
                    if (best == null || candidate.betterThan(best!!)) best = candidate
                }
            }
            inProgress.remove(key)
            memo[key] = best
            return best
        }

        var best: ScoredPath? = null
        for (start in byColumn[1].orEmpty().sortedWith(compareBy(LabyrinthMapNode::row, LabyrinthMapNode::blockId))) {
            val candidate = walk(start, false) ?: continue
            if (!policy.acceptsGeneric(candidate.route) || !policy.bossMatches(area, candidate.route)) continue
            if (best == null || candidate.betterThan(best!!)) best = candidate
        }
        val winner = best ?: return null
        return LabyrinthValueRouteResult(winner.route, winner.score, upperBound)
    }

    private fun valuableTypesByColumn(
        area: Int,
        nodes: List<LabyrinthMapNode>,
    ): Map<Int, Set<Int>> = nodes.groupBy(LabyrinthMapNode::column).mapValues { (_, columnNodes) ->
        val present = columnNodes.mapTo(linkedSetOf(), LabyrinthMapNode::blockType)
        val valuable = present.filterTo(linkedSetOf()) { it in BASE_VALUABLE_TYPES }

        // Match the v2 defaults from the open implementation: when these valuable types
        // compete in the same column, character beats relic and relic beats shop.
        if (CHARACTER_BLOCK_TYPE in present && RELIC_BLOCK_TYPE in present) {
            valuable.remove(RELIC_BLOCK_TYPE)
            valuable.add(CHARACTER_BLOCK_TYPE)
        }
        if (RELIC_BLOCK_TYPE in present && SHOP_BLOCK_TYPE in present) {
            valuable.remove(SHOP_BLOCK_TYPE)
            valuable.add(RELIC_BLOCK_TYPE)
        }

        // Existing UI already exposes the area 3/5 relic-vs-event preference. Under EITHER
        // both count toward the upper bound, so accepting either never makes reroll harder.
        if (area in setOf(3, 5) && RELIC_BLOCK_TYPE in present && EVENT_BLOCK_TYPE in present) {
            when (policy.thirdBlockChoice) {
                LabyrinthThirdBlockChoice.RELIC -> {
                    valuable.remove(EVENT_BLOCK_TYPE)
                    valuable.add(RELIC_BLOCK_TYPE)
                }
                LabyrinthThirdBlockChoice.EVENT -> {
                    valuable.remove(RELIC_BLOCK_TYPE)
                    valuable.add(EVENT_BLOCK_TYPE)
                }
                LabyrinthThirdBlockChoice.EITHER -> {
                    valuable.add(RELIC_BLOCK_TYPE)
                    valuable.add(EVENT_BLOCK_TYPE)
                }
            }
        }
        valuable
    }

    private fun tieBreakPreference(area: Int, node: LabyrinthMapNode): Int = when {
        area in setOf(3, 5) && policy.thirdBlockChoice == LabyrinthThirdBlockChoice.EITHER &&
            node.blockType == RELIC_BLOCK_TYPE -> 1
        else -> 0
    }

    private data class ScoredPath(
        val score: Int,
        val preference: Int,
        val route: List<LabyrinthMapNode>,
    ) {
        fun betterThan(other: ScoredPath): Boolean =
            score > other.score || (score == other.score && preference > other.preference)
    }

    companion object {
        private const val EX_BLOCK_TYPE = 3
        private const val CHARACTER_BLOCK_TYPE = 4
        private const val EVENT_BLOCK_TYPE = 5
        private const val RELIC_BLOCK_TYPE = 6
        private const val SHOP_BLOCK_TYPE = 7
        private val BASE_VALUABLE_TYPES = setOf(
            EX_BLOCK_TYPE,
            CHARACTER_BLOCK_TYPE,
            RELIC_BLOCK_TYPE,
            SHOP_BLOCK_TYPE,
        )
    }
}

class LabyrinthOpeningRouteMatcher(
    private val searcher: LabyrinthRouteSearcher = LabyrinthRouteSearcher(),
) {
    fun search(
        enterId: Long,
        nodes: List<LabyrinthMapNode>,
        difficulty: Int,
        policy: LabyrinthRoutePolicy,
        currentBlockId: Long? = null,
    ): RouteSearchResult {
        if (policy.evaluationMode == LabyrinthRouteEvaluationMode.CUSTOM_NODES) {
            return LabyrinthCustomNodeRouteEvaluator(policy).search(enterId, nodes, difficulty, currentBlockId)
        }
        if (policy.evaluationMode == LabyrinthRouteEvaluationMode.VALUE_ROUTE) {
            return searchValueRoute(enterId, nodes, difficulty, policy, currentBlockId)
        }
        return searchLegacyTemplate(enterId, nodes, difficulty, policy, currentBlockId)
    }

    private fun searchLegacyTemplate(
        enterId: Long,
        nodes: List<LabyrinthMapNode>,
        difficulty: Int,
        policy: LabyrinthRoutePolicy,
        currentBlockId: Long?,
    ): RouteSearchResult {
        val currentNode = currentBlockId?.let { id ->
            nodes.firstOrNull { it.blockId == id } ?: return RouteSearchResult.NoRoute
        }
        val matchedNodes = mutableListOf<LabyrinthMapNode>()
        val targetAreas = policy.targetAreas(difficulty)
            .filter { area -> currentNode == null || area >= currentNode.area }
        if (targetAreas.isEmpty()) return RouteSearchResult.NoRoute
        for (area in targetAreas) {
            val areaNodes = nodes.filter { it.area == area }
            val lastColumn = policy.lastColumn(area) ?: return RouteSearchResult.NoRoute
            val requiredCurrentBlockId = currentNode?.takeIf { it.area == area }?.blockId
            if ((1..lastColumn).any { column -> areaNodes.none { it.column == column } }) {
                return RouteSearchResult.NoRoute
            }
            val result = searcher.search(
                enterId = enterId,
                nodes = areaNodes,
                constraints = RouteConstraints(
                    maxNodes = lastColumn,
                    isGoal = { it.column == lastColumn },
                    acceptRoute = { route ->
                        policy.accepts(area, route) &&
                            (requiredCurrentBlockId == null || route.any { it.blockId == requiredCurrentBlockId })
                    },
                    startPredicate = { it.column == 1 },
                ),
            )
            if (result !is RouteSearchResult.Found) return RouteSearchResult.NoRoute
            matchedNodes += result.route.nodes
        }
        return RouteSearchResult.Found(LabyrinthRoute(enterId, matchedNodes))
    }

    private fun searchValueRoute(
        enterId: Long,
        nodes: List<LabyrinthMapNode>,
        difficulty: Int,
        policy: LabyrinthRoutePolicy,
        currentBlockId: Long?,
    ): RouteSearchResult {
        if (policy.valueAllowance !in 0..LabyrinthRerollOptions.MAX_VALUE_ALLOWANCE) {
            return RouteSearchResult.NoRoute
        }
        val currentNode = currentBlockId?.let { id ->
            nodes.firstOrNull { it.blockId == id } ?: return RouteSearchResult.NoRoute
        }
        val evaluator = LabyrinthValueRouteEvaluator(policy)
        val matchedNodes = mutableListOf<LabyrinthMapNode>()
        val targetAreas = policy.targetAreas(difficulty)
            .filter { area -> currentNode == null || area >= currentNode.area }
        if (targetAreas.isEmpty()) return RouteSearchResult.NoRoute
        for (area in targetAreas) {
            val areaNodes = nodes.filter { it.area == area }
            val requiredCurrentBlockId = currentNode?.takeIf { it.area == area }?.blockId
            val best = evaluator.best(area, areaNodes, requiredCurrentBlockId) ?: return RouteSearchResult.NoRoute
            if (best.shortfall > policy.valueAllowance) return RouteSearchResult.NoRoute
            matchedNodes += best.route
        }
        return RouteSearchResult.Found(LabyrinthRoute(enterId, matchedNodes))
    }
}

sealed interface RouteSearchResult {
    data class Found(val route: LabyrinthRoute) : RouteSearchResult
    data object NoRoute : RouteSearchResult
    /** 新模式的具体拒绝原因；布局/配置异常必须保留现场而不是无限重刷。 */
    data class Rejected(val message: String, val fatal: Boolean = false) : RouteSearchResult
}

class LabyrinthRouteSearcher {
    fun search(
        enterId: Long,
        nodes: List<LabyrinthMapNode>,
        constraints: RouteConstraints,
    ): RouteSearchResult {
        require(constraints.maxNodes > 0) { "最大节点数必须大于 0" }
        val byId = nodes.associateBy(LabyrinthMapNode::blockId)
        if (byId.size != nodes.size) return RouteSearchResult.NoRoute
        val starts = constraints.startPredicate?.let(nodes::filter)
            ?: nodes.filter { node -> nodes.none { node.blockId in it.nextBlockIds } }
                .ifEmpty { nodes.filter { it.area == nodes.minOfOrNull(LabyrinthMapNode::area) } }
        for (start in starts.sortedWith(compareBy(LabyrinthMapNode::area, LabyrinthMapNode::column, LabyrinthMapNode::row))) {
            val result = depthFirst(start, byId, constraints, linkedSetOf(), emptyList())
            if (result != null) return RouteSearchResult.Found(LabyrinthRoute(enterId, result))
        }
        return RouteSearchResult.NoRoute
    }

    private fun depthFirst(
        current: LabyrinthMapNode,
        byId: Map<Long, LabyrinthMapNode>,
        constraints: RouteConstraints,
        visited: LinkedHashSet<Long>,
        path: List<LabyrinthMapNode>,
    ): List<LabyrinthMapNode>? {
        if (!visited.add(current.blockId)) return null
        val nextPath = path + current
        if (constraints.isGoal(current) && constraints.acceptRoute(nextPath)) return nextPath
        if (nextPath.size >= constraints.maxNodes) return null
        val result = current.nextBlockIds.asSequence()
            .mapNotNull(byId::get)
            .sortedWith(compareBy(LabyrinthMapNode::area, LabyrinthMapNode::column, LabyrinthMapNode::row))
            .mapNotNull { child ->
                depthFirst(child, byId, constraints, LinkedHashSet(visited), nextPath)
            }
            .firstOrNull()
        return result
    }
}
