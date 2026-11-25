package com.landosol.toolbox.labyrinth.node

import com.landosol.toolbox.labyrinth.vision.EntryPixelRect
import com.landosol.toolbox.protocol.labyrinth.LabyrinthMapNode
import kotlin.math.abs
import kotlin.math.min

enum class NodeTopologyBindingKind {
    FULL_COLUMN,
    PARTIAL_COLUMN,
    REACHABLE_SUBSET,
    SINGLE_TARGET,
}

/**
 * A purely geometric/topological binding between one detected screen node and one protocol node.
 *
 * [detectedBlockType] is deliberately retained only as evidence. It does not participate in the
 * blockId binding itself. The caller may use it afterwards as a semantic consistency check.
 */
data class NodeTopologyMapping(
    val blockId: Long,
    val expectedBlockType: Int,
    val logicalColumn: Int,
    val visualColumn: Int,
    val visualRow: Int,
    val screenRect: EntryPixelRect,
    val detectedBlockType: Int,
    val visualConfidence: Double,
    val topologyConfidence: Double,
    val isClickable: Boolean,
    val bindingKind: NodeTopologyBindingKind,
)

data class NodeTopologyObservedColumn(
    val visualColumn: Int,
    val centerX: Int,
    val nodeCount: Int,
    val mappedLogicalColumn: Int?,
)

data class NodeTopologyResult(
    val mappings: List<NodeTopologyMapping> = emptyList(),
    val observedColumns: List<NodeTopologyObservedColumn> = emptyList(),
    val logicalColumnOffset: Int? = null,
    val alignmentScore: Double = 0.0,
)

/**
 * Aligns screen node geometry with the protocol DAG without trusting visual node types.
 *
 * The map is a layered graph: columns keep their left-to-right order and every logical column has
 * a known node count. That structural signature is enough to bind a complete visible column, and
 * current-node adjacency can bind an incomplete highlighted subset. This is intentionally kept
 * independent from the template classifier so a blue relic misclassified as LINK can still be
 * located as blockId=20402 and reported as a type conflict rather than "not visible".
 */
class NodeTopologyMapper {

    fun map(
        classifications: List<NodeClassification>,
        areaNodes: List<LabyrinthMapNode>,
        currentColumn: Int,
        reachableNodeIds: Set<Long> = emptySet(),
        preferredNodeId: Long? = null,
    ): NodeTopologyResult {
        if (areaNodes.isEmpty()) return NodeTopologyResult()

        val detections = deduplicate(classifications)
        if (detections.isEmpty()) return NodeTopologyResult()

        val visualColumns = clusterColumns(detections)
        if (visualColumns.isEmpty()) return NodeTopologyResult()

        val logicalColumns = areaNodes
            .groupBy(LabyrinthMapNode::column)
            .toSortedMap()
        if (logicalColumns.isEmpty()) return NodeTopologyResult()

        val minimumLogicalColumn = (currentColumn - 1).coerceAtLeast(logicalColumns.firstKey())
        val eligibleLogicalColumns = logicalColumns.keys.filter { it >= minimumLogicalColumn }
        val preferredColumn = preferredNodeId?.let { id ->
            areaNodes.firstOrNull { it.blockId == id }?.column
        }
        val bestAlignment = chooseBestAlignment(
            visualColumns = visualColumns,
            eligibleLogicalColumns = eligibleLogicalColumns,
            logicalColumns = logicalColumns,
            currentColumn = currentColumn,
            reachableNodeIds = reachableNodeIds,
            preferredColumn = preferredColumn,
        ) ?: return NodeTopologyResult(
            observedColumns = visualColumns.map { it.toObservedColumn(null) },
        )

        val mappings = mutableListOf<NodeTopologyMapping>()
        bestAlignment.logicalColumns.forEachIndexed { index, logicalColumn ->
            if (logicalColumn == null) return@forEachIndexed
            val visualColumn = visualColumns[index]
            val protocolNodes = logicalColumns[logicalColumn].orEmpty()
            mappings += bindColumn(
                visualColumn = visualColumn,
                logicalColumn = logicalColumn,
                protocolNodes = protocolNodes,
                reachableNodeIds = reachableNodeIds,
                preferredNodeId = preferredNodeId,
                columnAlignmentConfidence = bestAlignment.confidence,
            )
        }

        val observed = visualColumns.mapIndexed { index, column ->
            column.toObservedColumn(bestAlignment.logicalColumns.getOrNull(index))
        }
        val firstMapped = observed.firstOrNull { it.mappedLogicalColumn != null }
        val logicalColumnOffset = firstMapped?.let { mapped ->
            requireNotNull(mapped.mappedLogicalColumn) - mapped.visualColumn
        }

        return NodeTopologyResult(
            mappings = mappings.distinctBy(NodeTopologyMapping::blockId),
            observedColumns = observed,
            logicalColumnOffset = logicalColumnOffset,
            alignmentScore = bestAlignment.score,
        )
    }

    private data class Detection(
        val source: NodeClassification,
        val rect: EntryPixelRect,
    ) {
        val centerX: Int get() = rect.left + rect.width / 2
        val centerY: Int get() = rect.top + rect.height / 2
    }

    private data class VisualColumn(
        val visualIndex: Int,
        val centerX: Int,
        val detections: List<Detection>,
    ) {
        fun toObservedColumn(logicalColumn: Int?): NodeTopologyObservedColumn =
            NodeTopologyObservedColumn(
                visualColumn = visualIndex,
                centerX = centerX,
                nodeCount = detections.size,
                mappedLogicalColumn = logicalColumn,
            )
    }

    private data class Alignment(
        val logicalColumns: List<Int?>,
        val score: Double,
        val confidence: Double,
    )

    private fun deduplicate(classifications: List<NodeClassification>): List<Detection> {
        val ordered = classifications
            .mapNotNull { classification ->
                classification.screenRect?.let { rect -> Detection(classification, rect) }
            }
            .sortedWith(
                compareByDescending<Detection> { it.source.isClickable }
                    .thenByDescending { it.source.confidence },
            )
        val kept = mutableListOf<Detection>()
        ordered.forEach { candidate ->
            if (kept.none { accepted -> samePhysicalNode(candidate, accepted) }) {
                kept += candidate
            }
        }
        return kept.sortedWith(compareBy<Detection> { it.centerX }.thenBy { it.centerY })
    }

    private fun samePhysicalNode(a: Detection, b: Detection): Boolean {
        val maxWidth = maxOf(a.rect.width, b.rect.width)
        val maxHeight = maxOf(a.rect.height, b.rect.height)
        return abs(a.centerX - b.centerX) <= maxWidth * DUPLICATE_CENTER_X_RATIO &&
            abs(a.centerY - b.centerY) <= maxHeight * DUPLICATE_CENTER_Y_RATIO
    }

    private fun clusterColumns(detections: List<Detection>): List<VisualColumn> {
        if (detections.isEmpty()) return emptyList()
        val groups = mutableListOf<MutableList<Detection>>()
        detections.sortedBy(Detection::centerX).forEach { detection ->
            val current = groups.lastOrNull()
            val currentCenter = current?.map(Detection::centerX)?.average()
            val threshold = detection.rect.width * SAME_COLUMN_CENTER_X_RATIO
            if (current == null || currentCenter == null || abs(detection.centerX - currentCenter) > threshold) {
                groups += mutableListOf(detection)
            } else {
                current += detection
            }
        }
        return groups.mapIndexed { index, group ->
            VisualColumn(
                visualIndex = index + 1,
                centerX = group.map(Detection::centerX).average().toInt(),
                detections = group.sortedBy(Detection::centerY),
            )
        }
    }

    private fun chooseBestAlignment(
        visualColumns: List<VisualColumn>,
        eligibleLogicalColumns: List<Int>,
        logicalColumns: Map<Int, List<LabyrinthMapNode>>,
        currentColumn: Int,
        reachableNodeIds: Set<Long>,
        preferredColumn: Int?,
    ): Alignment? {
        val assignments = mutableListOf<List<Int?>>()
        fun choose(visualIndex: Int, logicalStart: Int, selected: MutableList<Int?>) {
            if (visualIndex == visualColumns.size) {
                assignments += selected.toList()
                return
            }

            // A classifier false positive can create an extra apparent column. Keep it in the
            // diagnostics, but allow the topology alignment to mark it as noise instead of
            // shifting every later protocol column.
            selected += null
            choose(visualIndex + 1, logicalStart, selected)
            selected.removeAt(selected.lastIndex)

            for (index in logicalStart until eligibleLogicalColumns.size) {
                selected += eligibleLogicalColumns[index]
                choose(visualIndex + 1, index + 1, selected)
                selected.removeAt(selected.lastIndex)
            }
        }
        choose(0, 0, mutableListOf())

        val scored = assignments.map { assignment ->
            var score = 0.0
            visualColumns.forEachIndexed { index, visualColumn ->
                val logicalColumn = assignment[index]
                if (logicalColumn == null) {
                    score -= VISUAL_NOISE_COLUMN_PENALTY
                    return@forEachIndexed
                }
                val protocolNodes = logicalColumns[logicalColumn].orEmpty()
                val countDifference = abs(visualColumn.detections.size - protocolNodes.size)
                score += if (
                    logicalColumn == currentColumn &&
                    visualColumn.detections.isNotEmpty() &&
                    visualColumn.detections.size <= protocolNodes.size
                ) {
                    // The current column is special: after a battle only the visited/current node
                    // may remain highlighted or reliably detected, while its inactive siblings are
                    // easy to miss. Treat a partial current column as valid topology evidence
                    // instead of punishing it like a future column. Otherwise a live sequence such
                    // as [current:1 node][next:2 nodes][following:2 nodes] gets shifted to
                    // [next][following][following+1], which is exactly how 20402 was incorrectly
                    // bound to the old 20303 screen position.
                    if (countDifference == 0) {
                        EXACT_COLUMN_COUNT_SCORE
                    } else {
                        CURRENT_COLUMN_PARTIAL_SCORE
                    }
                } else {
                    when (countDifference) {
                        0 -> EXACT_COLUMN_COUNT_SCORE
                        1 -> NEAR_COLUMN_COUNT_SCORE
                        else -> -COUNT_MISMATCH_PENALTY * countDifference
                    }
                }
                // Geometry remains the primary evidence. Visual confidence is only a weak
                // tie-breaker between two geometrically equivalent shifted crops of the same
                // logical column; semantic node type is intentionally not used here.
                score += visualColumn.detections.maxOfOrNull { it.source.confidence }
                    ?.times(VISUAL_CONFIDENCE_TIE_BREAK_WEIGHT)
                    ?: 0.0

                val clickableCount = visualColumn.detections.count { it.source.isClickable }
                val reachableCount = protocolNodes.count { it.blockId in reachableNodeIds }
                if (reachableCount > 0) {
                    score += if (clickableCount == reachableCount) {
                        EXACT_REACHABLE_COUNT_SCORE
                    } else {
                        -abs(clickableCount - reachableCount) * REACHABLE_COUNT_MISMATCH_PENALTY
                    }
                }
                if (logicalColumn == preferredColumn) {
                    score += PREFERRED_COLUMN_SCORE
                    if (clickableCount > 0) score += PREFERRED_COLUMN_CLICKABLE_SCORE
                }
                if (logicalColumn == currentColumn) score += CURRENT_COLUMN_PRIOR_SCORE
                if (logicalColumn == currentColumn + 1) {
                    score += NEXT_COLUMN_PRIOR_SCORE
                    if (clickableCount > 0) score += NEXT_COLUMN_CLICKABLE_PRIOR_SCORE
                }
            }

            val matchedLogicalColumns = assignment.filterNotNull()
            matchedLogicalColumns.zipWithNext().forEach { (left, right) ->
                val skippedLogicalColumns = (right - left - 1).coerceAtLeast(0)
                score -= skippedLogicalColumns * SKIPPED_COLUMN_PENALTY
            }
            // Adjacent protocol columns are separated by about 540 px for a 280 px node crop.
            // Template scan offsets can produce a second crop only ~240 px to the right of the
            // same physical column. Without a spacing term that shifted crop looks like another
            // logical column and can steal the explicit route target. Keep absolute camera
            // position free, but require the distance between mapped columns to agree with their
            // logical-column gap.
            assignment.mapIndexedNotNull { visualIndex, logicalColumn ->
                logicalColumn?.let { visualIndex to it }
            }.zipWithNext().forEach { (left, right) ->
                val leftVisual = visualColumns[left.first]
                val rightVisual = visualColumns[right.first]
                val logicalGap = right.second - left.second
                val nodeWidth = (
                    leftVisual.detections.map { it.rect.width } +
                        rightVisual.detections.map { it.rect.width }
                    ).average().coerceAtLeast(1.0)
                val spacingPerLogicalColumnInNodeWidths =
                    (rightVisual.centerX - leftVisual.centerX).toDouble() / logicalGap / nodeWidth
                if (spacingPerLogicalColumnInNodeWidths < MIN_COLUMN_SPACING_NODE_WIDTH_RATIO) {
                    // Two crops this close cannot be adjacent map columns. Treat that assignment
                    // as structurally invalid so a strong but shifted template cannot buy its way
                    // back in through count/preferred-column bonuses.
                    score -= TOO_CLOSE_COLUMN_ASSIGNMENT_PENALTY
                } else if (spacingPerLogicalColumnInNodeWidths <= MAX_COLUMN_SPACING_NODE_WIDTH_RATIO) {
                    score += COLUMN_SPACING_MATCH_SCORE
                } else {
                    val distanceFromValidRange =
                        spacingPerLogicalColumnInNodeWidths - MAX_COLUMN_SPACING_NODE_WIDTH_RATIO
                    score -= min(
                        COLUMN_SPACING_MAX_PENALTY,
                        distanceFromValidRange * COLUMN_SPACING_PENALTY_PER_NODE_WIDTH,
                    )
                }
            }
            matchedLogicalColumns.firstOrNull()?.let { first ->
                if (first > currentColumn + 1) {
                    score -= (first - currentColumn - 1) * LEADING_SKIP_PENALTY
                }
            }
            if (matchedLogicalColumns.isEmpty()) score -= EMPTY_ALIGNMENT_PENALTY
            assignment to score
        }

        val best = scored.maxByOrNull { it.second } ?: return null
        val second = scored.asSequence()
            .filterNot { it.first == best.first }
            .maxOfOrNull { it.second }
        val margin = if (second == null) MIN_ALIGNMENT_MARGIN_FOR_FULL_CONFIDENCE else best.second - second
        val confidence = (BASE_ALIGNMENT_CONFIDENCE + margin / ALIGNMENT_MARGIN_SCALE)
            .coerceIn(MIN_ALIGNMENT_CONFIDENCE, MAX_ALIGNMENT_CONFIDENCE)
        return Alignment(best.first, best.second, confidence)
    }

    private fun bindColumn(
        visualColumn: VisualColumn,
        logicalColumn: Int,
        protocolNodes: List<LabyrinthMapNode>,
        reachableNodeIds: Set<Long>,
        preferredNodeId: Long?,
        columnAlignmentConfidence: Double,
    ): List<NodeTopologyMapping> {
        if (protocolNodes.isEmpty()) return emptyList()

        val orderedProtocolNodes = protocolNodes.sortedBy { node ->
            resolveVisualIndexFromTop(node.blockId.toInt(), protocolNodes.size)
        }
        val orderedVisual = visualColumn.detections.sortedBy(Detection::centerY)

        // Strongest case: the whole logical column is visible. Node type is irrelevant; row order
        // alone binds every screen node to its protocol blockId.
        if (
            orderedVisual.size == orderedProtocolNodes.size &&
            hasReliableFullColumnGeometry(orderedVisual)
        ) {
            return orderedVisual.zip(orderedProtocolNodes).mapIndexed { index, (visual, node) ->
                visual.toTopologyMapping(
                    node = node,
                    logicalColumn = logicalColumn,
                    visualColumn = visualColumn.visualIndex,
                    visualRow = index + 1,
                    topologyConfidence = (columnAlignmentConfidence + FULL_COLUMN_BIND_BONUS).coerceAtMost(0.99),
                    bindingKind = NodeTopologyBindingKind.FULL_COLUMN,
                )
            }
        }

        // A three-node column is often detected as top + bottom because the inactive middle
        // template is weak. Their vertical gap is close to two row pitches, so geometry can bind
        // the two surviving crops without using semantic type or pretending they are adjacent.
        // This is materially stronger than SINGLE_TARGET and distinguishes the two same-type
        // reachable events in a column such as 30402/30401.
        if (
            orderedProtocolNodes.size == 3 &&
            orderedVisual.size == 2 &&
            hasMissingMiddleRowGeometry(orderedVisual)
        ) {
            return listOf(
                orderedVisual.first().toTopologyMapping(
                    node = orderedProtocolNodes.first(),
                    logicalColumn = logicalColumn,
                    visualColumn = visualColumn.visualIndex,
                    visualRow = 1,
                    topologyConfidence = (columnAlignmentConfidence + PARTIAL_COLUMN_BIND_BONUS)
                        .coerceAtMost(0.95),
                    bindingKind = NodeTopologyBindingKind.PARTIAL_COLUMN,
                ),
                orderedVisual.last().toTopologyMapping(
                    node = orderedProtocolNodes.last(),
                    logicalColumn = logicalColumn,
                    visualColumn = visualColumn.visualIndex,
                    visualRow = 3,
                    topologyConfidence = (columnAlignmentConfidence + PARTIAL_COLUMN_BIND_BONUS)
                        .coerceAtMost(0.95),
                    bindingKind = NodeTopologyBindingKind.PARTIAL_COLUMN,
                ),
            )
        }

        // Second case: only reachable nodes glow/detect reliably. If the number of clickable
        // detections equals the protocol adjacency subset, bind the two ordered subsets.
        //
        // Do not compare NodeClassification.row directly with protocol rows here: the classifier
        // renumbers only the crops that survived detection, so a real middle+bottom pair can be
        // reported as visual rows 1/2. A future row-integrity gate needs independent geometric
        // row anchors rather than this compressed ordinal.
        val reachableNodes = orderedProtocolNodes.filter { it.blockId in reachableNodeIds }
        val clickableVisual = orderedVisual.filter { it.source.isClickable }
        if (reachableNodes.isNotEmpty() && clickableVisual.size == reachableNodes.size) {
            if (orderedProtocolNodes.size == 3 && reachableNodes.size == 1 && clickableVisual.size == 1) {
                val detection = clickableVisual.single()
                val targetIndex = resolveVisualIndexFromTop(reachableNodes.single().blockId.toInt(), 3)
                val heightUnits = detection.rect.height.coerceAtLeast(1).toDouble()
                val centerInHeights = detection.centerY / heightUnits
                val roughBand = when {
                    centerInHeights < 0.85 -> 0
                    centerInHeights > 2.15 -> 2
                    else -> 1
                }
                if (abs(roughBand - targetIndex) > 1) return emptyList()
            }
            return clickableVisual.zip(reachableNodes).mapIndexed { index, (visual, node) ->
                visual.toTopologyMapping(
                    node = node,
                    logicalColumn = logicalColumn,
                    visualColumn = visualColumn.visualIndex,
                    visualRow = index + 1,
                    topologyConfidence = (columnAlignmentConfidence + REACHABLE_SUBSET_BIND_BONUS).coerceAtMost(0.95),
                    bindingKind = NodeTopologyBindingKind.REACHABLE_SUBSET,
                )
            }
        }

        // Explicit route target can still be located when it is the only highlighted crop in its
        // column. This is lower confidence and remains guarded by the later visual-type check.
        val preferredNode = preferredNodeId?.let { id ->
            orderedProtocolNodes.firstOrNull { it.blockId == id }
        }
        if (preferredNode != null && clickableVisual.size == 1) {
            return listOf(
                clickableVisual.single().toTopologyMapping(
                    node = preferredNode,
                    logicalColumn = logicalColumn,
                    visualColumn = visualColumn.visualIndex,
                    visualRow = 1,
                    topologyConfidence = (columnAlignmentConfidence - SINGLE_TARGET_PENALTY)
                        .coerceIn(0.55, 0.85),
                    bindingKind = NodeTopologyBindingKind.SINGLE_TARGET,
                ),
            )
        }

        return emptyList()
    }

    /** Reject a false "full column" assembled from horizontally scattered or uneven crops. */
    private fun hasReliableFullColumnGeometry(orderedVisual: List<Detection>): Boolean {
        if (orderedVisual.size <= 1) return true
        val averageWidth = orderedVisual.map { it.rect.width }.average().coerceAtLeast(1.0)
        val centerXSpread = orderedVisual.maxOf(Detection::centerX) - orderedVisual.minOf(Detection::centerX)
        if (centerXSpread > averageWidth * FULL_COLUMN_MAX_X_SPREAD_RATIO) return false
        if (orderedVisual.size <= 2) return true

        val averageHeight = orderedVisual.map { it.rect.height }.average().coerceAtLeast(1.0)
        val gaps = orderedVisual.zipWithNext { upper, lower -> lower.centerY - upper.centerY }
        if (gaps.any { it < averageHeight * FULL_COLUMN_MIN_ROW_GAP_RATIO }) return false
        return requireNotNull(gaps.maxOrNull()) - requireNotNull(gaps.minOrNull()) <=
            averageHeight * FULL_COLUMN_MAX_GAP_RESIDUAL_RATIO
    }

    private fun hasMissingMiddleRowGeometry(orderedVisual: List<Detection>): Boolean {
        if (orderedVisual.size != 2) return false
        val averageWidth = orderedVisual.map { it.rect.width }.average().coerceAtLeast(1.0)
        val averageHeight = orderedVisual.map { it.rect.height }.average().coerceAtLeast(1.0)
        val centerXSpread = abs(orderedVisual.last().centerX - orderedVisual.first().centerX)
        val centerYGap = orderedVisual.last().centerY - orderedVisual.first().centerY
        return centerXSpread <= averageWidth * PARTIAL_COLUMN_MAX_X_SPREAD_RATIO &&
            centerYGap >= averageHeight * PARTIAL_COLUMN_MISSING_ROW_MIN_GAP_RATIO
    }

    private fun Detection.toTopologyMapping(
        node: LabyrinthMapNode,
        logicalColumn: Int,
        visualColumn: Int,
        visualRow: Int,
        topologyConfidence: Double,
        bindingKind: NodeTopologyBindingKind,
    ): NodeTopologyMapping = NodeTopologyMapping(
        blockId = node.blockId,
        expectedBlockType = node.blockType,
        logicalColumn = logicalColumn,
        visualColumn = visualColumn,
        visualRow = visualRow,
        screenRect = rect,
        detectedBlockType = source.blockType,
        visualConfidence = source.confidence,
        topologyConfidence = topologyConfidence,
        isClickable = source.isClickable,
        bindingKind = bindingKind,
    )

    private companion object {
        const val DUPLICATE_CENTER_X_RATIO = 0.60
        const val DUPLICATE_CENTER_Y_RATIO = 0.48
        const val SAME_COLUMN_CENTER_X_RATIO = 0.72

        const val EXACT_COLUMN_COUNT_SCORE = 8.0
        const val NEAR_COLUMN_COUNT_SCORE = 2.0
        const val CURRENT_COLUMN_PARTIAL_SCORE = 5.0
        const val CURRENT_COLUMN_PRIOR_SCORE = 2.5
        const val COUNT_MISMATCH_PENALTY = 4.0
        const val EXACT_REACHABLE_COUNT_SCORE = 4.0
        const val REACHABLE_COUNT_MISMATCH_PENALTY = 1.5
        const val PREFERRED_COLUMN_SCORE = 5.0
        const val PREFERRED_COLUMN_CLICKABLE_SCORE = 2.0
        const val NEXT_COLUMN_PRIOR_SCORE = 1.0
        const val NEXT_COLUMN_CLICKABLE_PRIOR_SCORE = 3.0
        const val VISUAL_CONFIDENCE_TIE_BREAK_WEIGHT = 2.0
        const val SKIPPED_COLUMN_PENALTY = 1.5
        const val LEADING_SKIP_PENALTY = 2.0
        const val VISUAL_NOISE_COLUMN_PENALTY = 2.5
        const val EMPTY_ALIGNMENT_PENALTY = 100.0
        // The stitched map pitch is about 540/280=1.93 node widths, while shifted scan anchors
        // compress valid live observations down to roughly 324/280=1.16. A ratio below 1.05 is
        // therefore duplicate/shift noise, not an adjacent protocol column.
        const val MIN_COLUMN_SPACING_NODE_WIDTH_RATIO = 1.05
        const val MAX_COLUMN_SPACING_NODE_WIDTH_RATIO = 2.40
        const val COLUMN_SPACING_MATCH_SCORE = 4.0
        const val TOO_CLOSE_COLUMN_ASSIGNMENT_PENALTY = 60.0
        const val COLUMN_SPACING_PENALTY_PER_NODE_WIDTH = 24.0
        const val COLUMN_SPACING_MAX_PENALTY = 20.0

        const val BASE_ALIGNMENT_CONFIDENCE = 0.68
        const val MIN_ALIGNMENT_CONFIDENCE = 0.55
        const val MAX_ALIGNMENT_CONFIDENCE = 0.92
        const val MIN_ALIGNMENT_MARGIN_FOR_FULL_CONFIDENCE = 5.0
        const val ALIGNMENT_MARGIN_SCALE = 20.0
        const val FULL_COLUMN_BIND_BONUS = 0.15
        const val PARTIAL_COLUMN_BIND_BONUS = 0.08
        const val REACHABLE_SUBSET_BIND_BONUS = 0.02
        const val SINGLE_TARGET_PENALTY = 0.10

        const val FULL_COLUMN_MAX_X_SPREAD_RATIO = 0.52
        const val FULL_COLUMN_MIN_ROW_GAP_RATIO = 0.62
        const val FULL_COLUMN_MAX_GAP_RESIDUAL_RATIO = 0.35
        const val PARTIAL_COLUMN_MAX_X_SPREAD_RATIO = 0.52
        const val PARTIAL_COLUMN_MISSING_ROW_MIN_GAP_RATIO = 1.20

    }
}
