package com.landosol.toolbox.labyrinth.node

import com.landosol.toolbox.labyrinth.vision.EntryPixelRect
import kotlin.math.abs

/**
 * Recovers a stable route-bound node when visual semantics disagree with the saved route.
 *
 * This is deliberately node-type agnostic. The route graph already says which block is next;
 * this layer only decides whether the conflicting on-screen crop is stable enough to trust as the
 * physical target. Full-column topology is preferred; partial/subset bindings need an independent
 * ordered sibling. A sole reachable target is only a fallback with genuinely strong current visual
 * evidence. Topology scores and repeated route metadata never stand in for classifier confidence.
 * Fresh-frame ownership is enforced by the caller before invoking this stateful recovery.
 */
internal class LabyrinthNodeConflictRecovery {
    private var sessionKey: String? = null
    private var blockId: Long? = null
    private var expectedType: Int? = null
    private var detectedType: Int? = null
    private var lastRect: EntryPixelRect? = null
    private var stableFrames = 0

    fun reset() {
        sessionKey = null
        blockId = null
        expectedType = null
        detectedType = null
        lastRect = null
        stableFrames = 0
    }

    fun resolve(
        session: String,
        action: NodeAction,
        visible: List<NodeClassification>,
        uniqueReachableRouteTarget: Boolean = false,
    ): NodeAction {
        val conflict = action as? NodeAction.TypeConflict ?: run {
            reset()
            return action
        }
        val rect = conflict.screenRect ?: run {
            reset()
            return action
        }
        val active = visible
            .filter { it.isClickable && it.screenRect != null }
            .distinct()
        // The current conflict was produced from this exact crop. A nearby stronger node is not
        // supporting evidence for it, nor is a new semantic type at the old physical location.
        val current = active.filter { it.screenRect == rect }.singleOrNull()
            ?.takeIf { it.blockType == conflict.detectedBlockType }
        val reportedVisualConfidence = conflict.visualConfidence ?: conflict.confidence
        val visualConfidence = current?.let { candidate ->
            if (NodeClickAdmission.hasFiniteVisualEvidence(candidate.confidence, candidate.typeConfidence) &&
                reportedVisualConfidence.isFinite() && reportedVisualConfidence in 0.0..1.0
            ) {
                minOf(reportedVisualConfidence, candidate.confidence, candidate.typeConfidence)
            } else {
                0.0
            }
        } ?: 0.0
        val topologyConfidence = conflict.topologyConfidence ?: 0.0
        val binding = conflict.topologyBindingKind
        val orderedTopology = topologyConfidence.isFinite() && topologyConfidence in 0.0..1.0 &&
            topologyConfidence >= MIN_ORDERED_TOPOLOGY_CONFIDENCE &&
            when (binding) {
                NodeTopologyBindingKind.FULL_COLUMN -> true
                NodeTopologyBindingKind.PARTIAL_COLUMN,
                NodeTopologyBindingKind.REACHABLE_SUBSET,
                -> conflict.hasOrderedSiblingEvidence
                NodeTopologyBindingKind.SINGLE_TARGET, null -> false
            }
        val isolatedTarget = uniqueReachableRouteTarget &&
            active.size == 1 && current != null &&
            visualConfidence >= MIN_ISOLATED_VISUAL_CONFIDENCE
        val supported = conflict.expectedBlockType != conflict.detectedBlockType &&
            LabyrinthNodeTypes.isClickable(conflict.expectedBlockType) &&
            LabyrinthNodeTypes.isClickable(conflict.detectedBlockType) &&
            !labyrinthBossSemanticConflict(conflict.expectedBlockType, conflict.detectedBlockType) &&
            rect.width > 0 && rect.height > 0 &&
            visualConfidence >= MIN_VISUAL_CONFIDENCE &&
            (orderedTopology || isolatedTarget)
        if (!supported) {
            reset()
            return action
        }

        val sameEvidence = sessionKey == session &&
            blockId == conflict.blockId &&
            expectedType == conflict.expectedBlockType &&
            detectedType == conflict.detectedBlockType &&
            rectsAreStable(lastRect, rect)
        stableFrames = if (sameEvidence) stableFrames + 1 else 1
        sessionKey = session
        blockId = conflict.blockId
        expectedType = conflict.expectedBlockType
        detectedType = conflict.detectedBlockType
        lastRect = rect

        return if (stableFrames >= REQUIRED_STABLE_FRAMES) {
            NodeAction.ClickNode(conflict.blockId, conflict.expectedBlockType, rect)
        } else {
            action
        }
    }

    private fun rectsAreStable(first: EntryPixelRect?, second: EntryPixelRect?): Boolean {
        if (first == null || second == null) return first == second
        val firstCenterX = first.left + first.width / 2
        val firstCenterY = first.top + first.height / 2
        val secondCenterX = second.left + second.width / 2
        val secondCenterY = second.top + second.height / 2
        val xTolerance = maxOf(18, minOf(first.width, second.width) / 5)
        val yTolerance = maxOf(18, minOf(first.height, second.height) / 5)
        return abs(firstCenterX - secondCenterX) <= xTolerance &&
            abs(firstCenterY - secondCenterY) <= yTolerance
    }

    private companion object {
        const val REQUIRED_STABLE_FRAMES = 3
        const val MIN_VISUAL_CONFIDENCE = 0.80
        const val MIN_ISOLATED_VISUAL_CONFIDENCE = 0.85
        const val MIN_ORDERED_TOPOLOGY_CONFIDENCE = 0.72
    }
}
