package com.landosol.toolbox.labyrinth.node

import com.landosol.toolbox.clanbattle.recognition.PixelImage
import com.landosol.toolbox.protocol.labyrinth.LabyrinthMapNode

/**
 * 节点匹配结果
 */
data class NodeMatchResult(
    val currentNodeId: Long?,
    val currentNodeType: Int,
    val nextNode: LabyrinthMapNode?,
    val visibleNodes: List<NodePositionMapping>,
    val conflicts: List<NodePositionConflict> = emptyList(),
    val logicalColumnOffset: Int = 0,
    val topologyMappings: List<NodeTopologyMapping> = emptyList(),
    val topologyAlignmentScore: Double = 0.0,
    val topologyObservedColumns: List<NodeTopologyObservedColumn> = emptyList(),
    val isComplete: Boolean,
    val columnNodeCounts: Map<NodeColumnKey, Int> = emptyMap(),
)

/**
 * 黎明界节点匹配器
 * 结合视觉识别和地图数据，确定当前状态和下一步操作
 */
class LabyrinthNodeMatcher(
    private val classifier: LabyrinthNodeClassifier = LabyrinthNodeClassifier(),
    private val mapper: NodePositionMapper = NodePositionMapper(),
) {
    /**
     * 匹配当前帧的节点状态
     * @param frame 当前屏幕帧
     * @param allMapNodes 完整地图数据
     * @param routeNodes 选定的路线节点
     * @param currentArea 当前区域 (1-5)
     * @param currentNodeId 当前所在节点的 blockId
     * @param templates 节点模板集合
     * @return 匹配结果
     */
    fun match(
        frame: PixelImage,
        allMapNodes: List<LabyrinthMapNode>,
        routeNodes: List<LabyrinthMapNode>,
        currentArea: Int,
        currentNodeId: Long?,
        templates: NodeTemplateSet,
    ): NodeMatchResult = matchClassified(
        classifications = classifier.classifyMapNodes(frame = frame, templates = templates),
        allMapNodes = allMapNodes,
        routeNodes = routeNodes,
        currentArea = currentArea,
        currentNodeId = currentNodeId,
    )

    /**
     * 使用外部已完成的节点分类结果做匹配。入口帧处理器每帧已经跑过一次节点分类，
     * 走这个入口可以避免对同一帧重复做模板匹配。
     */
    fun matchClassified(
        classifications: List<NodeClassification>,
        allMapNodes: List<LabyrinthMapNode>,
        routeNodes: List<LabyrinthMapNode>,
        currentArea: Int,
        currentNodeId: Long?,
    ): NodeMatchResult {
        // 获取当前区域的所有节点
        val areaNodes = allMapNodes.filter { it.area == currentArea }

        // 查找路线下一节点后再映射视觉列，用其类型作为相机偏移的额外证据；
        // 这样旧列中的事件误识别不会把真正的连结节点推到错误逻辑列。
        val nextNode = mapper.findNextNode(
            currentNodeId = currentNodeId,
            routeNodes = routeNodes,
            allAreaNodes = areaNodes,
        )

        // 获取当前节点信息
        val currentNode = currentNodeId?.let { id ->
            areaNodes.firstOrNull { it.blockId == id }
        }

        // 映射到 blockId
        val currentNodeColumn = currentNode?.column ?: 0
        val positionResult = mapper.mapNodesDetailed(
            classifications = classifications,
            areaNodes = areaNodes,
            currentColumn = currentNodeColumn,
            reachableNodeIds = currentNode?.nextBlockIds?.toSet().orEmpty(),
            preferredNodeId = nextNode?.blockId,
        )

        // 确定当前节点类型
        val currentNodeType = currentNode?.blockType ?: LabyrinthNodeTypes.START

        // 判断是否完成当前区域
        val isComplete = currentNode?.isAreaLastPoint == true ||
            (currentNodeId != null && routeNodes.lastOrNull()?.blockId == currentNodeId && nextNode == null)

        return NodeMatchResult(
            currentNodeId = currentNodeId,
            currentNodeType = currentNodeType,
            nextNode = nextNode,
            visibleNodes = positionResult.mappings,
            conflicts = positionResult.conflicts,
            logicalColumnOffset = positionResult.logicalColumnOffset,
            topologyMappings = positionResult.topologyMappings,
            topologyAlignmentScore = positionResult.topologyAlignmentScore,
            topologyObservedColumns = positionResult.topologyObservedColumns,
            isComplete = isComplete,
            columnNodeCounts = countNodesByColumn(allMapNodes),
        )
    }

    /**
     * 根据点击后的 blockId 更新状态
     */
    fun afterClick(
        clickedBlockId: Long,
        allMapNodes: List<LabyrinthMapNode>,
        currentArea: Int,
    ): Pair<Long?, Int> {
        val areaNodes = allMapNodes.filter { it.area == currentArea }
        val clickedNode = areaNodes.firstOrNull { it.blockId == clickedBlockId }
        return clickedNode?.let { it.blockId to it.blockType } ?: (clickedBlockId to LabyrinthNodeTypes.UNKNOWN)
    }
}
