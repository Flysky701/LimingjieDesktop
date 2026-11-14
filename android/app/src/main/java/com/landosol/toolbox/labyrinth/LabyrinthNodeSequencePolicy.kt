package com.landosol.toolbox.labyrinth

import com.landosol.toolbox.labyrinth.node.LabyrinthNodeTypes
import com.landosol.toolbox.protocol.labyrinth.LabyrinthMapNode
import com.landosol.toolbox.protocol.labyrinth.LabyrinthRoute
import kotlinx.serialization.Serializable

/** 新模式的六行选择独立保存，不覆盖旧模式的第三列偏好或价值容差。 */
@Serializable
data class LabyrinthCustomNodePlan(
    val area1Column3: Int = LabyrinthNodeTypes.LINK,
    val area1Column5: Int = LabyrinthNodeTypes.LINK,
    val area2Columns2And6: Int = LabyrinthNodeTypes.LINK,
    val area3Column4: Int = LabyrinthNodeTypes.LINK,
    val area4Column2: Int = LabyrinthNodeTypes.LINK,
    val area4Column7: Int = LabyrinthNodeTypes.LINK,
    val thirdBlockChoice: LabyrinthThirdBlockChoice = LabyrinthThirdBlockChoice.RELIC,
) {
    fun validationError(): String? {
        val characterOrEvent = setOf(LabyrinthNodeTypes.LINK, LabyrinthNodeTypes.EVENT)
        val characterOrRelic = setOf(LabyrinthNodeTypes.LINK, LabyrinthNodeTypes.RELIC)
        return when {
            area1Column3 !in characterOrEvent -> "区域1第3列只能选择角色或事件"
            area1Column5 !in characterOrEvent -> "区域1第5列只能选择角色或事件"
            area2Columns2And6 !in characterOrEvent -> "区域2第2、6列只能选择角色或事件"
            area3Column4 !in characterOrEvent -> "区域3第4列只能选择角色或事件"
            area4Column2 !in characterOrRelic -> "区域4第2列只能选择角色或遗物"
            area4Column7 !in characterOrRelic -> "区域4第7列只能选择角色或遗物"
            else -> null
        }
    }

    fun expectedTypes(area: Int, column: Int): Set<Int> {
        val selected = when {
            area == 1 && column == 3 -> area1Column3
            area == 1 && column == 5 -> area1Column5
            area == 2 && column in setOf(2, 6) -> area2Columns2And6
            area == 3 && column == 4 -> area3Column4
            area == 4 && column == 2 -> area4Column2
            area == 4 && column == 7 -> area4Column7
            area in setOf(3, 5) && column == 3 -> return when (thirdBlockChoice) {
                LabyrinthThirdBlockChoice.RELIC -> setOf(LabyrinthNodeTypes.RELIC)
                LabyrinthThirdBlockChoice.EVENT -> setOf(LabyrinthNodeTypes.EVENT)
                LabyrinthThirdBlockChoice.EITHER -> setOf(LabyrinthNodeTypes.RELIC, LabyrinthNodeTypes.EVENT)
            }
            else -> FIXED_COLUMNS[area]?.getOrNull(column - 1)
        }
        return setOfNotNull(selected)
    }

    fun accepts(area: Int, route: List<LabyrinthMapNode>): Boolean {
        val length = columnCount(area) ?: return false
        if (route.size != length) return false
        return route.withIndex().all { (index, node) ->
            node.area == area && node.column == index + 1 &&
                node.blockType in expectedTypes(area, index + 1)
        }
    }

    companion object {
        // 区域2第4列固定遗物；区域4第5列EX、第6列普通战，沿用目标工程。
        private val FIXED_COLUMNS = mapOf(
            1 to listOf(1, 2, 4, 2, 4, 6),
            2 to listOf(1, 4, 2, 6, 3, 4, 6),
            3 to listOf(1, 2, 6, 4, 3, 7, 8),
            4 to listOf(1, 4, 3, 5, 3, 2, 4, 7),
            5 to listOf(1, 2, 6, 3, 6, 3, 7, 8),
        )

        fun columnCount(area: Int): Int? = FIXED_COLUMNS[area]?.size

        fun labelOf(type: Int): String = if (type == LabyrinthNodeTypes.LINK) "角色"
            else LabyrinthNodeTypes.labelOf(type)
    }
}

/** 仅用于新模式：完整逐列走真实连接，不改写旧模板或价值路线搜索。 */
internal class LabyrinthCustomNodeRouteEvaluator(private val policy: LabyrinthRoutePolicy) {
    fun search(
        enterId: Long,
        nodes: List<LabyrinthMapNode>,
        difficulty: Int,
        currentBlockId: Long?,
    ): RouteSearchResult {
        policy.customNodePlan.validationError()?.let { return rejected(it, fatal = true) }
        val byId = nodes.associateBy(LabyrinthMapNode::blockId)
        if (byId.size != nodes.size) return rejected("地图包含重复节点ID，已保留当前开局", fatal = true)
        val current = currentBlockId?.let { id ->
            byId[id] ?: return rejected("当前节点不在地图中，已保留当前开局", fatal = true)
        }
        val areas = policy.targetAreas(difficulty).filter { current == null || it >= current.area }
        if (areas.isEmpty()) return rejected("当前区域不属于此难度的目标区域，已保留当前开局", fatal = true)
        // 所有参与区域先校验，不能因前区普通不命中就掩盖后区布局错误。
        for (area in areas) {
            val length = LabyrinthCustomNodePlan.columnCount(area)
                ?: return rejected("不支持区域$area 的地图布局", fatal = true)
            val areaNodes = nodes.filter { it.area == area }
            if (areaNodes.map { it.column }.toSet() != (1..length).toSet()) {
                return rejected("区域$area 的地图列不完整或布局不受支持，已保留当前开局", fatal = true)
            }
            if (areaNodes.any { node -> node.nextBlockIds.any { it !in byId } }) {
                return rejected("区域$area 的地图连接指向不存在的节点，已保留当前开局", fatal = true)
            }
        }
        val matched = mutableListOf<LabyrinthMapNode>()
        for (area in areas) {
            val areaNodes = nodes.filter { it.area == area }
            val length = LabyrinthCustomNodePlan.columnCount(area) ?: return rejected("区域$area 布局无效", true)
            for (column in 1..length) {
                val expected = policy.customNodePlan.expectedTypes(area, column)
                if (areaNodes.none { it.column == column && it.blockType in expected }) {
                    val labels = expected.joinToString("或") { LabyrinthCustomNodePlan.labelOf(it) }
                    return rejected("区域$area 第${column}列缺少$labels")
                }
            }
            var deepestColumn = 0
            var bossRejected = false
            var currentRejected = false
            var genericRejected = false
            val requiredCurrent = current?.takeIf { it.area == area }?.blockId
            fun walk(node: LabyrinthMapNode, prefix: List<LabyrinthMapNode>): List<LabyrinthMapNode>? {
                if (node.blockType !in policy.customNodePlan.expectedTypes(area, node.column)) return null
                deepestColumn = maxOf(deepestColumn, node.column)
                val path = prefix + node
                if (node.column == length) {
                    if (!policy.bossMatches(area, path)) { bossRejected = true; return null }
                    if (requiredCurrent != null && path.none { it.blockId == requiredCurrent }) {
                        currentRejected = true
                        return null
                    }
                    if (!policy.acceptsGeneric(path)) { genericRejected = true; return null }
                    return path
                }
                val children = node.nextBlockIds.mapNotNull(byId::get)
                    .filter { it.area == area && it.column == node.column + 1 }
                    .sortedWith(compareBy(LabyrinthMapNode::row, LabyrinthMapNode::blockId))
                for (child in children) {
                    walk(child, path)?.let { return it }
                }
                return null
            }
            var winner: List<LabyrinthMapNode>? = null
            for (start in areaNodes.filter { it.column == 1 }
                .sortedWith(compareBy(LabyrinthMapNode::row, LabyrinthMapNode::blockId))) {
                winner = walk(start, emptyList())
                if (winner != null) break
            }
            if (winner == null) {
                val message = when {
                    currentRejected -> "区域$area 的目标路线无法包含当前节点"
                    genericRejected -> "区域$area 的节点路线不符合附加筛选条件"
                    bossRejected -> "区域$area 的Boss不在所选名单中"
                    else -> "区域$area 第${(deepestColumn + 1).coerceAtMost(length)}列的目标节点无法连通"
                }
                return rejected(message)
            }
            matched += winner
        }
        return RouteSearchResult.Found(LabyrinthRoute(enterId, matched))
    }

    private fun rejected(message: String, fatal: Boolean = false) = RouteSearchResult.Rejected(message, fatal)
}
