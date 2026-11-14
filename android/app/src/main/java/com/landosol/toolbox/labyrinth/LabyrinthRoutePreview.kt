package com.landosol.toolbox.labyrinth

import com.landosol.toolbox.labyrinth.node.LabyrinthNodeTypes
import java.util.concurrent.atomic.AtomicReference
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** 只描述已保存路线的结构，不做收益估算；数值含义见各字段注释。 */
@Serializable
data class LabyrinthRoutePreview(
    val enterId: Long,
    val guildId: Int,
    val difficulty: Int,
    val mode: String,
    val modeLabel: String,
    val currentBlockId: Long? = null,
    val topologyVerified: Boolean,
    val issues: List<String> = emptyList(),
    val floors: List<LabyrinthRoutePreviewFloor> = emptyList(),
    val note: String = "仅路线结构，不含收益估算",
)

@Serializable
data class LabyrinthRoutePreviewFloor(
    val area: Int,
    val columnCount: Int,
    val nodes: List<LabyrinthRoutePreviewNode>,
)

@Serializable
data class LabyrinthRoutePreviewNode(
    val blockId: Long,
    val column: Int,
    val row: Int,
    val blockType: Int,
    val label: String,
    /** 该列是否由用户在选择项里指定过，用于页面高亮。 */
    val specified: Boolean,
)

/**
 * 从保存的路线快照生成预览。只沿 [LabyrinthRouteJson.nodes] 与 [LabyrinthRouteJson.allNodes]
 * 做结构核对，不读取识别帧、不估算分数，因此可以安全地在后台线程随路线变化重建。
 */
object LabyrinthRoutePreviewBuilder {
    fun build(route: LabyrinthRouteJson): LabyrinthRoutePreview {
        val mode = runCatching { LabyrinthRouteEvaluationMode.valueOf(route.routeEvaluationMode) }.getOrNull()
        val specified = specifiedColumns(route)
        val issues = mutableListOf<String>()
        val nodes = route.nodes
        if (nodes.isEmpty()) issues += "保存路线为空"
        if (nodes.map { it.blockId }.toSet().size != nodes.size) issues += "路线包含重复节点"
        if (route.allNodes.map { it.blockId }.toSet().size != route.allNodes.size) issues += "地图包含重复节点ID"

        val byId = route.allNodes.associateBy { it.blockId }
        if (route.allNodes.isEmpty()) {
            issues += "地图快照缺失，未做拓扑校验"
        } else {
            nodes.forEach { node ->
                val mapped = byId[node.blockId]
                when {
                    mapped == null -> issues += "路线节点 #${node.blockId} 不在保存的地图中"
                    mapped.blockType != node.blockType -> issues += "路线节点 #${node.blockId} 类型与地图不一致"
                }
            }
        }
        nodes.zipWithNext().forEach { (current, next) ->
            if (current.area == next.area && next.blockId !in current.nextBlockIds) {
                issues += "路线边未验证：#${current.blockId} → #${next.blockId}"
            }
            if (next.area < current.area || (next.area == current.area && next.column <= current.column)) {
                issues += "路线顺序异常：#${current.blockId} → #${next.blockId}"
            }
        }

        val floors = nodes.groupBy { it.area }.toSortedMap().map { (area, areaNodes) ->
            val ordered = areaNodes.sortedBy { it.column }
            LabyrinthRoutePreviewFloor(
                area = area,
                columnCount = ordered.maxOfOrNull { it.column } ?: ordered.size,
                nodes = ordered.map { node ->
                    LabyrinthRoutePreviewNode(
                        blockId = node.blockId,
                        column = node.column,
                        row = node.row,
                        blockType = node.blockType,
                        label = labelOf(node.blockType),
                        specified = (area to node.column) in specified,
                    )
                },
            )
        }

        return LabyrinthRoutePreview(
            enterId = route.enterId,
            guildId = route.guildId,
            difficulty = route.difficulty,
            mode = route.routeEvaluationMode,
            modeLabel = mode?.label ?: route.routeEvaluationMode,
            currentBlockId = route.currentBlockId,
            topologyVerified = issues.isEmpty(),
            issues = issues,
            floors = floors,
        )
    }

    /** 自定义节点模式里由用户选值的那几列；其余模式没有指定列。 */
    internal fun specifiedColumns(route: LabyrinthRouteJson): Set<Pair<Int, Int>> {
        if (route.routeEvaluationMode != LabyrinthRouteEvaluationMode.CUSTOM_NODES.name) return emptySet()
        return setOf(
            1 to 3, 1 to 5,
            2 to 2, 2 to 6,
            3 to 4,
            4 to 2, 4 to 7,
        )
    }

    /** 页面用的短标签，不改变全局的节点名称定义。 */
    internal fun labelOf(blockType: Int): String = when (blockType) {
        LabyrinthNodeTypes.START -> "起点"
        LabyrinthNodeTypes.NORMAL_BATTLE -> "普战"
        LabyrinthNodeTypes.EX_BATTLE -> "EX战"
        LabyrinthNodeTypes.LINK -> "角色"
        LabyrinthNodeTypes.EVENT -> "事件"
        LabyrinthNodeTypes.RELIC -> "遗物"
        LabyrinthNodeTypes.SHOP -> "商店"
        LabyrinthNodeTypes.BOSS -> "Boss"
        else -> "未知($blockType)"
    }
}

/**
 * 保存路线时就地缓存一份预览，页面按 [key] 判断是否需要重新取。
 * 键包含当前节点，所以推进到下一格会触发一次刷新，而不是每 500ms 重建。
 */
object LabyrinthRoutePreviewCache {
    private data class Entry(val key: String, val payload: ByteArray)

    private val entry = AtomicReference<Entry?>(null)
    private val json = Json { encodeDefaults = true }
    private val emptyPayload = "null".toByteArray(Charsets.UTF_8)

    fun publish(route: LabyrinthRouteJson) {
        runCatching {
            val preview = LabyrinthRoutePreviewBuilder.build(route)
            val payload = json.encodeToString(preview).toByteArray(Charsets.UTF_8)
            entry.set(Entry(keyOf(route), payload))
        }
    }

    fun clear() {
        entry.set(null)
    }

    fun key(): String? = entry.get()?.key

    fun payload(): ByteArray = entry.get()?.payload ?: emptyPayload

    internal fun keyOf(route: LabyrinthRouteJson): String =
        "${route.enterId}:${route.currentBlockId ?: -1L}"
}
