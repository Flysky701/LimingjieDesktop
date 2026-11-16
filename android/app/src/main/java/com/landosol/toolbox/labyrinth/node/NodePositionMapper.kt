package com.landosol.toolbox.labyrinth.node

import com.landosol.toolbox.labyrinth.vision.EntryPixelRect
import com.landosol.toolbox.protocol.labyrinth.LabyrinthMapNode

/**
 * 节点位置映射结果
 */
data class NodePositionMapping(
    val blockId: Long,
    val blockType: Int,
    val column: Int,
    val row: Int,
    val confidence: Double,
    val screenRect: EntryPixelRect? = null,
    val isClickable: Boolean = false,
    val detectedBlockType: Int? = null,
    val topologyConfidence: Double? = null,
    val topologyBindingKind: NodeTopologyBindingKind? = null,
    /**
     * True when protocol reachability/topology retained this screen slot even though the source
     * crop was not visually active/clickable. This is positional evidence only: planners must
     * never convert it into a click without independent visual activation evidence.
     */
    val protocolPromoted: Boolean = false,
    /** Local magenta/purple activation evidence retained for narrow EX conflict handling. */
    val purpleGlowScore: Double = 0.0,
    /** True when a unique protocol-reachable visual type repaired a contradictory topology slot. */
    val routeSemanticRepair: Boolean = false,
    /** Actual classifier evidence, never the route/topology binding score. */
    val visualConfidence: Double = confidence,
    val typeConfidence: Double = visualConfidence,
)

enum class NodePositionConflictReason {
    TYPE_CONFLICT,
}

/**
 * A visual crop was positionally aligned with a logical node, but its recognized semantic type
 * disagreed with the map/route type. Keep this separate from [NodePositionMapping]: route metadata
 * may identify which block occupies a screen slot, but it must never overwrite the visual type and
 * turn a contradictory crop into an auto-click target.
 */
data class NodePositionConflict(
    val blockId: Long,
    val expectedBlockType: Int,
    val detectedBlockType: Int,
    val visualColumn: Int,
    val visualRow: Int,
    /** Compatibility/ranking score; may describe topology, never infer visual quality from it. */
    val confidence: Double,
    val screenRect: EntryPixelRect? = null,
    val reason: NodePositionConflictReason = NodePositionConflictReason.TYPE_CONFLICT,
    /** The source crop's purple activation evidence; never inferred from route metadata. */
    val purpleGlowScore: Double = 0.0,
    /** Whether the contradictory visual crop itself is currently active/clickable. */
    val sourceClickable: Boolean = true,
    /** Null only for legacy callers; production mappings always provide classifier evidence. */
    val visualConfidence: Double? = null,
    /** Positional binding evidence, deliberately separate from visual classification. */
    val topologyConfidence: Double? = null,
)

data class NodePositionMapResult(
    val mappings: List<NodePositionMapping>,
    val conflicts: List<NodePositionConflict>,
    val logicalColumnOffset: Int,
    val topologyMappings: List<NodeTopologyMapping> = emptyList(),
    val topologyAlignmentScore: Double = 0.0,
    val topologyObservedColumns: List<NodeTopologyObservedColumn> = emptyList(),
)

/**
 * 列的定位键。blockId 为 AACCNN：AA=区域(Area)，CC=列(Column)。
 */
data class NodeColumnKey(
    val area: Int,
    val column: Int,
)

/**
 * 按「区域 + 列」统计每列节点数量。
 * 每列可能有 1、2 或 3 个节点，不能假设都是 3 个。
 */
fun countNodesByColumn(
    allNodes: List<LabyrinthMapNode>,
): Map<NodeColumnKey, Int> {
    return allNodes.groupingBy { node ->
        NodeColumnKey(
            area = (node.blockId / 10_000).toInt(),
            column = (node.blockId / 100 % 100).toInt(),
        )
    }.eachCount()
}

/**
 * 计算节点在其所在列内、从上到下的视觉索引。
 *
 * blockId 尾号（NodeNumber）不是从上到下，而是从下到上编号：
 *   三个节点：03=最上方、02=中间、01=最下方
 *   两个节点：02=上方、01=下方
 *   一个节点：01=唯一节点
 *
 * 返回值从 0 开始，0=最上方。
 *
 * @throws IllegalArgumentException 当 nodeNumber 超出该列节点数时抛出，避免静默生成错误坐标
 */
fun resolveVisualIndexFromTop(
    blockId: Int,
    nodesInColumn: Int,
): Int {
    val nodeNumber = blockId % 100

    require(nodeNumber in 1..nodesInColumn) {
        "Invalid nodeNumber=$nodeNumber, blockId=$blockId, nodesInColumn=$nodesInColumn"
    }

    return nodesInColumn - nodeNumber
}

/**
 * 生成节点校准用的调试日志（内部编号 + 视觉行号）。
 */
fun formatNodeDebugLog(
    node: LabyrinthMapNode,
    nodesInColumn: Int,
): String = buildString {
    appendLine("blockId=${node.blockId}")
    appendLine("area=${node.area}")
    appendLine("column=${node.column}")
    appendLine("nodeNumber=${node.blockId % 100}")
    appendLine("nodesInColumn=$nodesInColumn")
    appendLine("visualIndexFromTop=${resolveVisualIndexFromTop(node.blockId.toInt(), nodesInColumn)}")
    appendLine("blockType=${node.blockType}")
    appendLine("label=${LabyrinthNodeTypes.labelOf(node.blockType)}")
}

/**
 * 屏幕位置到 blockId 的映射器
 * 根据当前区域和识别到的节点位置，匹配到具体的 blockId
 */
class NodePositionMapper {

    /**
     * 将识别到的节点位置映射到 blockId
     * @param classifications 识别到的节点分类结果
     * @param areaNodes 当前区域的所有节点（来自完整地图）
     * @param currentColumn 当前所在列（已走过的最远列）
     * @param reachableNodeIds 当前节点可直接到达的节点。为空时回退到整列映射。
     * @return 映射结果列表
     */
    fun mapNodes(
        classifications: List<NodeClassification>,
        areaNodes: List<LabyrinthMapNode>,
        currentColumn: Int,
        reachableNodeIds: Set<Long> = emptySet(),
        preferredNodeId: Long? = null,
    ): List<NodePositionMapping> = mapNodesDetailed(
        classifications = classifications,
        areaNodes = areaNodes,
        currentColumn = currentColumn,
        reachableNodeIds = reachableNodeIds,
        preferredNodeId = preferredNodeId,
    ).mappings

    /**
     * Same mapping pass as [mapNodes], with contradictions retained for diagnostics. A conflict is
     * never returned in [NodePositionMapResult.mappings], so callers cannot accidentally click it.
     */
    fun mapNodesDetailed(
        classifications: List<NodeClassification>,
        areaNodes: List<LabyrinthMapNode>,
        currentColumn: Int,
        reachableNodeIds: Set<Long> = emptySet(),
        preferredNodeId: Long? = null,
    ): NodePositionMapResult {
        val nodesInColumnByColumn = countNodesByColumn(areaNodes)
        // Topology is now the primary blockId binder. It only consumes screen geometry, protocol
        // column structure and current-node adjacency; the classifier's semantic type is retained
        // afterwards as a consistency check. The legacy type-led mapper below remains as a
        // temporary fallback for columns that topology cannot yet bind.
        val topologyResult = NodeTopologyMapper().map(
            classifications = classifications,
            areaNodes = areaNodes,
            currentColumn = currentColumn,
            reachableNodeIds = reachableNodeIds,
            preferredNodeId = preferredNodeId,
        )
        fun visualTypeConfidence(rect: EntryPixelRect?, detectedType: Int, fallback: Double): Double =
            classifications.asSequence()
                .filter { it.screenRect == rect && it.blockType == detectedType }
                .minOfOrNull(NodeClassification::typeConfidence) ?: fallback

        val preferredFutureNodeId = preferredNodeId?.takeIf { id ->
            areaNodes.any { node -> node.blockId == id && node.column > currentColumn }
        }
        val topologyActionableIds = if (reachableNodeIds.isNotEmpty() || preferredFutureNodeId != null) {
            reachableNodeIds + listOfNotNull(preferredFutureNodeId)
        } else {
            areaNodes.asSequence()
                .filter { it.column > currentColumn }
                .mapTo(hashSetOf(), LabyrinthMapNode::blockId)
        }
        // Current/old columns are valuable alignment anchors but must never leak into the action
        // mapping. Only future route/reachable blocks can become resolved action candidates.
        val actionableTopology = topologyResult.mappings.filter { topology ->
            topology.blockId in topologyActionableIds &&
                areaNodes.firstOrNull { it.blockId == topology.blockId }?.column?.let { it > currentColumn } == true
        }
        // If topology either missed the route target or bound it to a crop of a contradictory
        // type, a unique active crop can repair the binding only when the protocol says exactly
        // one reachable node has that type. This handles a partially detected three-node column
        // without allowing an ambiguous EVENT/NORMAL crop to guess between siblings.
        val preferredTargetSemanticRepair = preferredFutureNodeId?.let { targetId ->
            val target = areaNodes.firstOrNull { it.blockId == targetId }
                ?.takeIf { targetId in reachableNodeIds }
                ?: return@let null
            val sameTypeReachableNodes = areaNodes.filter { node ->
                node.blockId in reachableNodeIds && node.blockType == target.blockType
            }
            if (sameTypeReachableNodes.singleOrNull()?.blockId != targetId) return@let null
            val uniqueVisual = classifications.filter { classification ->
                classification.isClickable &&
                    classification.blockType == target.blockType &&
                    classification.screenRect != null
            }.singleOrNull() ?: return@let null
            // Uniqueness is not visual evidence: a lone 0.404 background crop must not acquire
            // a route identity just because it has the expected type. Keep activation owned by
            // the classifier and require an independent visual floor for semantic-only repair.
            if (!NodeClickAdmission.hasSemanticEvidence(uniqueVisual)) return@let null
            // One crop is one node. If topology has already bound this very crop to a different
            // block, adopting it here would put two block ids on one rect, and the only thing
            // saying it belongs to the target is the crop's own type read — which is precisely
            // what topology is disputing. Believing it because it matches what the route wants is
            // circular.
            //
            // 2026-09-20 bundle 020234: the crop at (820,220) was bound to event#30403 by a
            // PARTIAL_COLUMN binding at 0.810 and logged as a TYPE_CONFLICT (expected 事件,
            // detected 连结 at 0.664). The route wanted link#30402, the repair adopted that crop,
            // and the tap entered the event. Topology's own rows said as much: it had 30403 at
            // visual row 1 and 30401 at row 3, so 30402 could only be the undetected middle cell.
            val rectClaimedByAnotherBlock = actionableTopology.any { topology ->
                topology.blockId != targetId &&
                    topology.topologyConfidence >= SEMANTIC_REPAIR_RECT_OWNER_MIN_CONFIDENCE &&
                    labyrinthNodeRectsOverlapSameCrop(topology.screenRect, uniqueVisual.screenRect)
            }
            if (rectClaimedByAnotherBlock) return@let null
            val topologyTarget = actionableTopology.firstOrNull { it.blockId == targetId }
            // A multi-node full column has enough cardinality and vertical order to identify the
            // route slot. A one-node column has no row-order evidence: an isolated background
            // false positive can look like a complete column and shift the true target left.
            // A noisy semantic classifier can still swap the apparent types of two different
            // columns (for example 10503 LINK -> RELIC and 10601 RELIC -> LINK). In that case the
            // globally unique matching type is precisely the wrong crop, so it must not replace
            // a strong multi-node FULL_COLUMN binding. For a single-node column, only allow an
            // override when the topology crop is inactive/wrong-type and the matching active crop
            // is both strong and materially stronger.
            val targetColumnNodeCount = areaNodes.count { node ->
                node.area == target.area && node.column == target.column
            }
            val strongOrderedTopologyTarget = topologyTarget?.let { candidate ->
                candidate.topologyConfidence >= TOPOLOGY_ORDERED_ROUTE_TARGET_MIN_CONFIDENCE &&
                    when (candidate.bindingKind) {
                        NodeTopologyBindingKind.FULL_COLUMN -> true
                        NodeTopologyBindingKind.PARTIAL_COLUMN,
                        NodeTopologyBindingKind.REACHABLE_SUBSET,
                        -> actionableTopology.any { sibling ->
                                sibling.logicalColumn == candidate.logicalColumn &&
                                    sibling.blockId != candidate.blockId &&
                                    sibling.isClickable &&
                                    sibling.detectedBlockType == sibling.expectedBlockType &&
                                    sibling.topologyConfidence >= TOPOLOGY_ORDERED_ROUTE_TARGET_MIN_CONFIDENCE
                            }
                        NodeTopologyBindingKind.SINGLE_TARGET -> false
                    }
            } == true
            val singleNodeSemanticOverride =
                targetColumnNodeCount == 1 &&
                    topologyTarget != null &&
                    topologyTarget.detectedBlockType != target.blockType &&
                    !topologyTarget.isClickable &&
                    uniqueVisual.confidence >= SINGLE_NODE_SEMANTIC_REPAIR_MIN_CONFIDENCE &&
                    uniqueVisual.confidence - topologyTarget.visualConfidence >=
                    SINGLE_NODE_SEMANTIC_REPAIR_MIN_MARGIN
            if (
                strongOrderedTopologyTarget &&
                !singleNodeSemanticOverride
            ) {
                return@let null
            }
            if (
                topologyTarget != null &&
                topologyTarget.detectedBlockType == target.blockType &&
                topologyTarget.screenRect == uniqueVisual.screenRect
            ) {
                return@let null
            }
            NodePositionMapping(
                blockId = target.blockId,
                blockType = target.blockType,
                column = target.column,
                row = target.row,
                confidence = uniqueVisual.confidence,
                screenRect = uniqueVisual.screenRect,
                isClickable = true,
                detectedBlockType = uniqueVisual.blockType,
                purpleGlowScore = uniqueVisual.purpleGlowScore,
                routeSemanticRepair = true,
                visualConfidence = uniqueVisual.confidence,
                typeConfidence = uniqueVisual.typeConfidence,
            )
        }
        val semanticallyRepairedBlockIds = setOfNotNull(preferredTargetSemanticRepair?.blockId)
        val trustedActionableTopology = actionableTopology.filter { topology ->
            topology.bindingKind != NodeTopologyBindingKind.SINGLE_TARGET &&
                topology.blockId !in semanticallyRepairedBlockIds
        }
        fun isProtocolReachableRouteTarget(topology: NodeTopologyMapping): Boolean =
            topology.blockId == preferredFutureNodeId &&
                topology.blockId in reachableNodeIds &&
                topology.bindingKind == NodeTopologyBindingKind.FULL_COLUMN &&
                topology.topologyConfidence >= TOPOLOGY_FULL_COLUMN_ROUTE_TARGET_MIN_CONFIDENCE

        val topologyResolvedBlockIds = trustedActionableTopology.mapTo(hashSetOf(), NodeTopologyMapping::blockId)
        val topologyMappings = trustedActionableTopology.mapNotNull { topology ->
            val protocolReachabilityOverride = isProtocolReachableRouteTarget(topology)
            topology.takeIf { it.isClickable || protocolReachabilityOverride }?.let {
                NodePositionMapping(
                    blockId = it.blockId,
                    blockType = it.expectedBlockType,
                    column = it.logicalColumn,
                    row = areaNodes.firstOrNull { node -> node.blockId == it.blockId }?.row ?: it.visualRow,
                    confidence = it.topologyConfidence,
                    screenRect = it.screenRect,
                    // Protocol topology may retain a slot so the route target can be located and
                    // diagnosed, but it must never manufacture visual activation evidence.
                    isClickable = it.isClickable,
                    detectedBlockType = it.detectedBlockType,
                    topologyConfidence = it.topologyConfidence,
                    topologyBindingKind = it.bindingKind,
                    protocolPromoted = protocolReachabilityOverride && !it.isClickable,
                    visualConfidence = it.visualConfidence,
                    typeConfidence = visualTypeConfidence(it.screenRect, it.detectedBlockType, it.visualConfidence),
                )
            }
        }
        val topologyConflicts = trustedActionableTopology.mapNotNull { topology ->
            topology.takeIf {
                (it.isClickable || isProtocolReachableRouteTarget(it)) &&
                    it.detectedBlockType != it.expectedBlockType
            }?.let {
                NodePositionConflict(
                    blockId = it.blockId,
                    expectedBlockType = it.expectedBlockType,
                    detectedBlockType = it.detectedBlockType,
                    visualColumn = it.visualColumn,
                    visualRow = it.visualRow,
                    confidence = it.topologyConfidence,
                    screenRect = it.screenRect,
                    sourceClickable = it.isClickable,
                    visualConfidence = it.visualConfidence,
                    topologyConfidence = it.topologyConfidence,
                )
            }
        }
        val rawClickableClassifications = classifications.filter(NodeClassification::isClickable)
        // The saved route is the action source of truth. A resumed/partially populated map can
        // have an empty or incomplete nextBlockIds list, which must not make the explicit route
        // target disappear from the visual candidates (for example 20303 after 20203). Always
        // retain the route target when it belongs to this area; otherwise a stale candidate from
        // the previous visual column can be mapped onto the target by row alone.
        val preferredNodeInArea = preferredNodeId?.takeIf { id ->
            areaNodes.any { node ->
                node.blockId == id && LabyrinthNodeTypes.isClickable(node.blockType)
            }
        }
        val candidateNodeIds = if (preferredNodeInArea != null) {
            reachableNodeIds + preferredNodeInArea
        } else {
            reachableNodeIds
        }
        val preferredBlockType = preferredNodeInArea?.let { id ->
            areaNodes.firstOrNull { it.blockId == id }?.blockType
        }
        val preferredNodeColumn = preferredNodeInArea?.let { id ->
            areaNodes.firstOrNull { it.blockId == id }?.column
        }
        val hasReachableNodeConstraints = reachableNodeIds.isNotEmpty()
        // An unlit crop is not a safe tap target. Waiting for the active glow is preferable to
        // clicking an offset/inactive template that happens to match the route type. The route
        // target is still retained in candidateNodeIds, so it will be selected as soon as the
        // real active crop is visible.
        val candidateClassifications = rawClickableClassifications
        // Transition frames can leave a stale active icon from another node type on the
        // previous visual column. It must not determine the camera offset for the current
        // route branch. Prefer classifications whose type is actually reachable from the
        // current node; if there are no route constraints, retain the generic fallback.
        val reachableBlockTypes = areaNodes.asSequence()
            .filter { it.blockId in candidateNodeIds }
            .map(LabyrinthMapNode::blockType)
            .toSet()
        val offsetAnchorClassifications = if (candidateNodeIds.isNotEmpty()) {
            candidateClassifications.filter { it.blockType in reachableBlockTypes }
        } else {
            candidateClassifications
        }
        fun candidatesForLogicalColumn(logicalColumn: Int): List<LabyrinthMapNode> =
            areaNodes.filter { node ->
                node.column > currentColumn &&
                    node.column == logicalColumn &&
                    // A resumed route can have an explicit next node while the current
                    // node's nextBlockIds are empty. Keep the whole target column for
                    // visual-row/count calibration in that case; using only the target
                    // would make a complete two/three-node column look incomplete and
                    // leave the target without a screen rect. The explicit target type
                    // and preferred-node checks below still prevent a stale type from
                    // being remapped onto it.
                    (
                        (!hasReachableNodeConstraints && node.column == preferredNodeColumn) ||
                            candidateNodeIds.isEmpty() ||
                            node.blockId in candidateNodeIds
                        )
            }

        val clickableByVisualColumn = candidateClassifications.groupBy(NodeClassification::column)
        // If every visible active crop has the wrong semantic type, type-filtered anchors are
        // empty. Still use their geometry to infer the viewport so we can report a TYPE_CONFLICT
        // against the expected block instead of degrading to an indistinguishable "off screen".
        // Final mappings remain type-strict below, therefore this positional fallback cannot make
        // an incorrect crop clickable.
        val firstVisualColumn = offsetAnchorClassifications.minOfOrNull(NodeClassification::column)
            ?: candidateClassifications.minOfOrNull(NodeClassification::column)
        // 路线约束存在时，不能只取最左侧可点击图标作为相机偏移：过渡帧中的旧列图标
        // 可能恰好也是可达类型（例如事件），会把下一列整体错移一列。对每个可能的偏移
        // 计算路线证据，优先选择能完整解释可达列、且更符合路线下一节点类型的偏移。
        data class OffsetEvidence(
            val offset: Int,
            val completeColumns: Int,
            val preferredTypeMatches: Int,
            val reachableTypeMatches: Int,
            val confidence: Double,
        )
        fun offsetEvidence(offset: Int): OffsetEvidence {
            var completeColumns = 0
            var preferredTypeMatches = 0
            var reachableTypeMatches = 0
            var confidence = 0.0
            clickableByVisualColumn.forEach { (visualColumn, nodes) ->
                val candidates = candidatesForLogicalColumn(visualColumn + offset)
                if (candidates.isEmpty()) return@forEach
                val candidateTypes = candidates.mapTo(hashSetOf(), LabyrinthMapNode::blockType)
                val matchingNodes = nodes.filter { it.blockType in candidateTypes }
                reachableTypeMatches += matchingNodes.size
                preferredTypeMatches += preferredBlockType?.let { type ->
                    nodes.count { it.blockType == type }
                } ?: 0
                confidence += matchingNodes.sumOf(NodeClassification::confidence)
                if (nodes.size == candidates.size && nodes.all { it.screenRect != null }) {
                    completeColumns++
                }
            }
            return OffsetEvidence(
                offset = offset,
                completeColumns = completeColumns,
                preferredTypeMatches = preferredTypeMatches,
                reachableTypeMatches = reachableTypeMatches,
                confidence = confidence,
            )
        }
        val logicalColumnOffset = if (currentColumn > 0 && firstVisualColumn != null) {
            val fallback = currentColumn + 1 - firstVisualColumn
            if (candidateNodeIds.isEmpty()) {
                fallback
            } else {
                clickableByVisualColumn.keys
                    .map { visualColumn -> currentColumn + 1 - visualColumn }
                    .plus(fallback)
                    .distinct()
                    .map(::offsetEvidence)
                    .maxWithOrNull(
                        compareBy<OffsetEvidence> { it.completeColumns }
                            .thenBy { it.preferredTypeMatches }
                            .thenBy { it.reachableTypeMatches }
                            .thenBy { it.confidence },
                    )
                    ?.offset
                    ?: fallback
            }
        } else {
            0
        }
        val completeLogicalColumns = clickableByVisualColumn.mapNotNullTo(mutableSetOf()) { (visualColumn, nodes) ->
            val logicalColumn = visualColumn + logicalColumnOffset
            val expectedNodeCount = candidatesForLogicalColumn(logicalColumn).size
            logicalColumn.takeIf { nodes.size == expectedNodeCount && nodes.all { it.screenRect != null } }
        }
        val clickableClassifications = clickableByVisualColumn
            .flatMap { (visualColumn, nodes) ->
                val logicalColumn = visualColumn + logicalColumnOffset
                val expectedNodeCount = candidatesForLogicalColumn(logicalColumn).size
                if (nodes.size == expectedNodeCount && nodes.all { it.screenRect != null }) {
                    nodes.sortedBy { it.screenRect?.top }.mapIndexed { index, node ->
                        node.copy(row = index + 1)
                    }
                } else {
                    nodes
                }
            }

        val bestMappingsByBlockId = linkedMapOf<Long, NodePositionMapping>()
        val bestConflictsByBlockId = linkedMapOf<Long, NodePositionConflict>()
        for (classNode in clickableClassifications) {
            val logicalColumn = classNode.column + logicalColumnOffset
            val columnCandidates = candidatesForLogicalColumn(logicalColumn)
            // 弹窗退出或地图动画期间可能残留一个错误候选，导致正确节点的视觉行号偏移。
            // 先要求「行号 + 类型」同时一致；若行号受污染，则只在该列的节点类型唯一时回退。
            // 绝不把已识别出的连结/事件等节点按错误行映射成另一种节点。
            val exactRowAndType = columnCandidates.firstOrNull { node ->
                node.blockType == classNode.blockType &&
                    (candidateNodeIds.isEmpty() || logicalColumn in completeLogicalColumns) &&
                    resolveVisualIndexFromTop(
                        blockId = node.blockId.toInt(),
                        nodesInColumn = nodesInColumnByColumn[NodeColumnKey(node.area, node.column)] ?: 1,
                    ) == classNode.row - 1
            }
            val exactRow = columnCandidates.firstOrNull { node ->
                resolveVisualIndexFromTop(
                    blockId = node.blockId.toInt(),
                    nodesInColumn = nodesInColumnByColumn[NodeColumnKey(node.area, node.column)] ?: 1,
                    ) == classNode.row - 1
            }
            // A route branch can contain inactive event/relic nodes while the selected link
            // alone is highlighted. In that case the clickable subset is smaller than the
            // reachable subset, so row matching is deliberately unavailable. A type that occurs
            // exactly once in the complete logical column is still safe to bind to its reachable
            // node (for example 10502=LINK between two EVENT nodes); this also preserves the
            // protection against mapping an ambiguous type by guesswork.
            val uniqueReachableTypeMatch = if (candidateNodeIds.isNotEmpty()) {
                areaNodes
                    .filter { node ->
                        node.column > currentColumn &&
                            node.column == logicalColumn &&
                            node.blockType == classNode.blockType
                    }
                    .singleOrNull()
                    ?.takeIf { node -> node.blockId in candidateNodeIds }
            } else {
                null
            }
            val orderedReachableColumn = columnCandidates.sortedBy { node ->
                resolveVisualIndexFromTop(
                    blockId = node.blockId.toInt(),
                    nodesInColumn = nodesInColumnByColumn[NodeColumnKey(node.area, node.column)] ?: 1,
                )
            }
            val positionalReachableNode = if (logicalColumn in completeLogicalColumns) {
                orderedReachableColumn.getOrNull(classNode.row - 1)
            } else {
                null
            }
            val matchedByReachableOrder = if (
                candidateNodeIds.isNotEmpty() && logicalColumn in completeLogicalColumns
            ) {
                // 只有可达节点会发光并被标记为 clickable。它们可能只是完整列的一个子集，
                // 所以这里按完整地图的上下顺序给这组可达节点重新编号，而不是把它们
                // 当成完整列的前三行。这样 10301 -> 10402 的两个普通战节点可直接区分。
                positionalReachableNode?.takeIf { node -> node.blockType == classNode.blockType }
            } else {
                null
            }
            val matched = matchedByReachableOrder ?: exactRowAndType ?: uniqueReachableTypeMatch ?: if (logicalColumn !in completeLogicalColumns) {
                if (candidateNodeIds.isEmpty()) {
                    columnCandidates
                        .filter { node -> node.blockType == classNode.blockType }
                        .singleOrNull()
                } else {
                    null
                }
            } else {
                null
            }

            if (matched != null) {
                val mapping = NodePositionMapping(
                    blockId = matched.blockId,
                    blockType = matched.blockType,
                    column = matched.column,
                    row = matched.row,
                    confidence = classNode.confidence,
                    screenRect = classNode.screenRect,
                    isClickable = classNode.isClickable,
                    purpleGlowScore = classNode.purpleGlowScore,
                    visualConfidence = classNode.confidence,
                    typeConfidence = classNode.typeConfidence,
                )
                val previous = bestMappingsByBlockId[matched.blockId]
                if (previous == null || mapping.isPreferredTo(previous)) {
                    bestMappingsByBlockId[matched.blockId] = mapping
                }
            } else if (
                logicalColumn in completeLogicalColumns &&
                (positionalReachableNode ?: exactRow)?.blockType != null &&
                (positionalReachableNode ?: exactRow)?.blockType != classNode.blockType
            ) {
                val conflictingNode = requireNotNull(positionalReachableNode ?: exactRow)
                val conflict = NodePositionConflict(
                    blockId = conflictingNode.blockId,
                    expectedBlockType = conflictingNode.blockType,
                    detectedBlockType = classNode.blockType,
                    visualColumn = classNode.column,
                    visualRow = classNode.row,
                    confidence = classNode.confidence,
                    screenRect = classNode.screenRect,
                    purpleGlowScore = classNode.purpleGlowScore,
                    sourceClickable = classNode.isClickable,
                    visualConfidence = classNode.confidence,
                    topologyConfidence = null,
                )
                val previous = bestConflictsByBlockId[conflict.blockId]
                if (previous == null || conflict.confidence > previous.confidence) {
                    bestConflictsByBlockId[conflict.blockId] = conflict
                }
            }
        }

        // Do not let the legacy type-led fallback overwrite a block that topology already bound.
        // A topology/type disagreement remains a first-class conflict, while a type agreement is
        // immediately usable as the action rect. Unresolved blocks can still use the old mapper
        // until the topology model covers every map state.
        // The rects topology already claimed are also off-limits to the legacy fallback.
        //
        // Without this, the fallback can pick the same crop that topology assigned to a
        // different block and put two block ids on one screen position. When the two ids have
        // different types the route-planner prefers the one it wants and taps the wrong node.
        //
        // 2026-09-20 bundle 020234: topology bound (820,220) to event#30403. The fallback then
        // type-matched the same crop to link#30402, which is what the route wanted. The session
        // adopted the fallback mapping, tapped row 1, and entered the event instead of the link.
        val topologyClaimedRects = topologyMappings.mapNotNullTo(hashSetOf(), NodePositionMapping::screenRect)
        val resolvedActionableBlockIds = topologyResolvedBlockIds + semanticallyRepairedBlockIds
        val mergedMappings = topologyMappings + listOfNotNull(preferredTargetSemanticRepair) +
            bestMappingsByBlockId.values.filterNot { legacy ->
                legacy.blockId in resolvedActionableBlockIds ||
                    (legacy.screenRect != null && topologyClaimedRects.any { claimed ->
                        labyrinthNodeRectsOverlapSameCrop(legacy.screenRect, claimed)
                    })
            }
        val mergedConflicts = topologyConflicts + bestConflictsByBlockId.values
            .filterNot { it.blockId in resolvedActionableBlockIds }

        // Topology bindings deliberately do not depend on semantic templates, so they are
        // created without classifier-only evidence. Reattach that evidence by the exact crop
        // rectangle before returning the result; this lets the action planner distinguish a
        // purple EX activation from an ordinary LINK/type conflict without making topology
        // itself trust the visual type.
        fun purpleGlowFor(rect: EntryPixelRect?): Double = rect?.let { targetRect ->
            classifications.asSequence()
                .filter { it.screenRect == targetRect }
                .maxOfOrNull(NodeClassification::purpleGlowScore)
                ?: 0.0
        } ?: 0.0
        val enrichedMappings = mergedMappings.map { mapping ->
            mapping.copy(
                purpleGlowScore = maxOf(mapping.purpleGlowScore, purpleGlowFor(mapping.screenRect)),
            )
        }
        val enrichedConflicts = mergedConflicts.map { conflict ->
            conflict.copy(
                purpleGlowScore = maxOf(conflict.purpleGlowScore, purpleGlowFor(conflict.screenRect)),
            )
        }

        return NodePositionMapResult(
            mappings = enrichedMappings,
            conflicts = enrichedConflicts,
            logicalColumnOffset = topologyResult.logicalColumnOffset ?: logicalColumnOffset,
            topologyMappings = topologyResult.mappings,
            topologyAlignmentScore = topologyResult.alignmentScore,
            topologyObservedColumns = topologyResult.observedColumns,
        )
    }

    private fun NodePositionMapping.isPreferredTo(other: NodePositionMapping): Boolean =
        compareValuesBy(
            this,
            other,
            { it.isClickable },
            { it.screenRect != null },
            { it.confidence },
        ) > 0

    /**
     * 找到当前应该点击的下一个节点
     * @param currentNodeId 当前所在节点的 blockId
     * @param routeNodes 路线中的节点列表
     * @param allAreaNodes 当前区域所有节点
     * @return 下一个应该点击的节点，如果没有则返回 null
     */
    fun findNextNode(
        currentNodeId: Long?,
        routeNodes: List<LabyrinthMapNode>,
        allAreaNodes: List<LabyrinthMapNode>,
    ): LabyrinthMapNode? {
        if (currentNodeId == null) {
            // 起始点是当前地图锚点，不是待点击目标。中途接管或服务端没有返回
            // currentBlockId 时，不能把列1的 START 节点当成下一步，否则会一直
            // 尝试在地图边界寻找一个本来就不可点击的节点。
            val areaNodeIds = allAreaNodes.mapTo(hashSetOf(), LabyrinthMapNode::blockId)
            val currentArea = allAreaNodes.firstOrNull()?.area
            return routeNodes.firstOrNull {
                it.area == currentArea &&
                    it.blockId in areaNodeIds &&
                    LabyrinthNodeTypes.isClickable(it.blockType)
            }
        }

        // 找到当前节点在路线中的位置
        val currentIndex = routeNodes.indexOfFirst { it.blockId == currentNodeId }
        if (currentIndex < 0) return null

        // 起始点/CLEAR 等地图锚点只用于表示位置，不能成为点击动作目标。
        return routeNodes.asSequence()
            .drop(currentIndex + 1)
            .firstOrNull { LabyrinthNodeTypes.isClickable(it.blockType) }
    }

    /**
     * 根据 blockId 获取节点信息
     */
    fun getNodeByBlockId(blockId: Long, areaNodes: List<LabyrinthMapNode>): LabyrinthMapNode? {
        return areaNodes.firstOrNull { it.blockId == blockId }
    }

    /**
     * 获取节点的所有可能下一步
     */
    fun getNextCandidates(
        currentNodeId: Long,
        areaNodes: List<LabyrinthMapNode>,
    ): List<LabyrinthMapNode> {
        val currentNode = areaNodes.firstOrNull { it.blockId == currentNodeId } ?: return emptyList()
        return currentNode.nextBlockIds.mapNotNull { nextId ->
            areaNodes.firstOrNull { it.blockId == nextId }
        }
    }

    private companion object {
        /**
         * A full visible column with exact protocol cardinality is strong enough to recover the
         * saved route target when the cyan-glow gate alone misses it. Keep this aligned with the
         * planner's high-confidence FULL_COLUMN safety threshold.
         */
        const val TOPOLOGY_FULL_COLUMN_ROUTE_TARGET_MIN_CONFIDENCE = 0.90
        const val TOPOLOGY_ORDERED_ROUTE_TARGET_MIN_CONFIDENCE = 0.90
        const val SINGLE_NODE_SEMANTIC_REPAIR_MIN_CONFIDENCE = 0.85
        const val SINGLE_NODE_SEMANTIC_REPAIR_MIN_MARGIN = 0.12

        /**
         * How sure topology must be about a crop before a semantic repair has to leave it alone.
         *
         * Below this the binding is weak enough that the repair is the better reading; at or above
         * it the two readings contradict each other and the run should look again rather than tap.
         */
        const val SEMANTIC_REPAIR_RECT_OWNER_MIN_CONFIDENCE = 0.70
    }
}

/**
 * Whether two rects are the same crop of the map rather than two neighbouring nodes.
 *
 * Node crops are a fixed 280x350 and their rows sit ~300 px apart, so comparing centres against a
 * fraction of the crop separates "the same detection, re-measured" from "the cell above".
 */
internal fun labyrinthNodeRectsOverlapSameCrop(
    first: EntryPixelRect?,
    second: EntryPixelRect?,
): Boolean {
    if (first == null || second == null) return false
    val xTolerance = maxOf(16, minOf(first.width, second.width) * 30 / 100)
    val yTolerance = maxOf(16, minOf(first.height, second.height) * 30 / 100)
    val dx = kotlin.math.abs((first.left + first.width / 2) - (second.left + second.width / 2))
    val dy = kotlin.math.abs((first.top + first.height / 2) - (second.top + second.height / 2))
    return dx <= xTolerance && dy <= yTolerance
}
