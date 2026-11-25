package com.landosol.toolbox.labyrinth.node

import com.landosol.toolbox.labyrinth.vision.EntryPixelRect
import kotlin.math.abs

/** Horizontal direction used while scanning a complete labyrinth area in viewport-sized pieces. */
enum class LabyrinthMapScanDirection {
    FORWARD,
    BACKWARD,
}

/**
 * A protocol-bound node expressed in an area-wide coordinate system.
 *
 * [worldX] is intentionally independent from the current camera. The 540px reference pitch is
 * the median logical-column spacing measured from the area-2 stitched ground truth and the
 * 1920x1080 node anchors. [screenRect] remains diagnostic-only and is never reused for a click in
 * another viewport.
 */
data class LabyrinthGlobalNodeCoordinate(
    val blockId: Long,
    val area: Int,
    val logicalColumn: Int,
    val visualRow: Int,
    val worldX: Double,
    val screenRect: EntryPixelRect,
    val viewportSignature: String,
)

data class LabyrinthNodeViewportSnapshot(
    val area: Int,
    val signature: String,
    val mappedLogicalColumns: Set<Int>,
    val globalNodes: List<LabyrinthGlobalNodeCoordinate>,
    /** World X coordinate represented by screen x=0, when topology geometry is usable. */
    val viewportWorldLeft: Double?,
    val columnSpacingResidual: Double?,
    val geometryReliable: Boolean,
    val geometryInferred: Boolean = false,
    /**
     * Sampled pixels of the frame this snapshot was measured from. Animated scenery changes the
     * signature hash almost every frame while the camera stays put, so an exact signature match
     * is too strict to recognise the same viewport twice; these samples let
     * [LabyrinthNodeViewportScanner.expectedScreenCenterX] fall back to a pixel comparison.
     */
    val pixels: NodeViewportPixels? = null,
)

data class LabyrinthNodeScanPlan(
    val direction: LabyrinthMapScanDirection,
    val reason: String,
)

/**
 * Retains a segmented, area-wide map scan across horizontal swipes.
 *
 * It never produces a node tap coordinate. Its only action output is a swipe direction; a target
 * still needs a fresh visual rectangle in the current frame before [LabyrinthNodeActionPlanner]
 * can click it.
 */
class LabyrinthNodeViewportScanner {
    private data class PendingSwipe(
        val direction: LabyrinthMapScanDirection,
        val sourceSignature: String,
        val unchangedObservations: Int = 0,
        val uncertainObservations: Int = 0,
        val sourcePixels: NodeViewportPixels? = null,
    )

    private var area: Int? = null
    private var frameWidth: Int = 0
    private var frameHeight: Int = 0
    private var currentSignature: String = ""
    private var currentPixels: NodeViewportPixels? = null
    private val uncertainSwipes = mutableMapOf<LabyrinthMapScanDirection, Int>()
    private var activeTargetBlockId: Long? = null
    private var pendingSwipe: PendingSwipe? = null
    private var lastDirection: LabyrinthMapScanDirection? = null
    private var consecutiveInferredGeometryFrames: Int = 0
    private val snapshots = linkedMapOf<String, LabyrinthNodeViewportSnapshot>()
    private val globalNodesByBlockId = linkedMapOf<Long, MutableList<LabyrinthGlobalNodeCoordinate>>()
    private val boundarySignatures = mutableMapOf(
        LabyrinthMapScanDirection.FORWARD to linkedSetOf<String>(),
        LabyrinthMapScanDirection.BACKWARD to linkedSetOf<String>(),
    )
    private val reachedBoundaries = linkedSetOf<LabyrinthMapScanDirection>()

    fun reset() {
        area = null
        frameWidth = 0
        frameHeight = 0
        currentSignature = ""
        currentPixels = null
        activeTargetBlockId = null
        pendingSwipe = null
        lastDirection = null
        consecutiveInferredGeometryFrames = 0
        snapshots.clear()
        globalNodesByBlockId.clear()
        resetTargetTraversal()
    }

    private fun viewportMovedAfterSwipe(
        source: LabyrinthNodeViewportSnapshot?,
        current: LabyrinthNodeViewportSnapshot,
        sourcePixels: NodeViewportPixels?,
        pixels: NodeViewportPixels?,
    ): NodeViewportMotion {
        if (source == null) return pixels?.let { now -> sourcePixels?.let(now::motionFrom) } ?: NodeViewportMotion.UNKNOWN

        val sourceWorldLeft = source.viewportWorldLeft
        val currentWorldLeft = current.viewportWorldLeft
        if (source.geometryReliable && current.geometryReliable &&
            sourceWorldLeft != null && currentWorldLeft != null
        ) {
            val threshold = labyrinthReferenceColumnPitch(frameHeight) * MIN_CAMERA_MOVE_PITCH_RATIO
            return if (abs(currentWorldLeft - sourceWorldLeft) >= threshold) NodeViewportMotion.MOVED else NodeViewportMotion.UNCHANGED
        }

        val sourceById = source.globalNodes.associateBy(LabyrinthGlobalNodeCoordinate::blockId)
        val commonDeltas = current.globalNodes.mapNotNull { node ->
            sourceById[node.blockId]?.let { previous ->
                val previousCenter = previous.screenRect.left + previous.screenRect.width / 2.0
                val currentCenter = node.screenRect.left + node.screenRect.width / 2.0
                abs(currentCenter - previousCenter)
            }
        }
        if (commonDeltas.isNotEmpty()) {
            val medianDelta = commonDeltas.medianOrNull() ?: 0.0
            val threshold = labyrinthReferenceColumnPitch(frameHeight) * MIN_CAMERA_MOVE_PITCH_RATIO
            return if (medianDelta >= threshold) NodeViewportMotion.MOVED else NodeViewportMotion.UNCHANGED
        }

        // A different hash is not evidence of translation: animation changes hashes too.
        return pixels?.let { now -> sourcePixels?.let(now::motionFrom) } ?: NodeViewportMotion.UNKNOWN
    }

    fun observe(
        area: Int,
        viewportSignature: String,
        frameWidth: Int,
        frameHeight: Int,
        matchResult: NodeMatchResult,
        viewportPixels: NodeViewportPixels? = null,
    ): LabyrinthNodeViewportSnapshot? {
        if (viewportSignature.isBlank() || frameWidth <= 0 || frameHeight <= 0) return null
        if (this.area != area) {
            reset()
            this.area = area
        }
        this.frameWidth = frameWidth
        this.frameHeight = frameHeight

        val pitch = labyrinthReferenceColumnPitch(frameHeight)
        val rawTopology = matchResult.topologyMappings
        val topology = rawTopology
            .filter { it.topologyConfidence >= MIN_TOPOLOGY_CONFIDENCE_FOR_GLOBAL_COORDINATE }
        val screenCenterByLogicalColumn = topology
            .groupBy(NodeTopologyMapping::logicalColumn)
            .mapValues { (_, nodes) ->
                nodes.map { it.screenRect.left + it.screenRect.width / 2.0 }.average()
            }
        val cameraOffsets = screenCenterByLogicalColumn.map { (logicalColumn, screenCenterX) ->
            logicalColumn * pitch - screenCenterX
        }
        val directlyFittedWorldLeft = cameraOffsets.medianOrNull()
        val residual = directlyFittedWorldLeft?.let { fitted ->
            cameraOffsets.map { offset -> abs(offset - fitted) }.medianOrNull()
        }
        val residualLimit = topology
            .map(NodeTopologyMapping::screenRect)
            .map(EntryPixelRect::width)
            .average()
            .takeIf { !it.isNaN() }
            ?.times(MAX_COLUMN_SPACING_RESIDUAL_NODE_WIDTH_RATIO)
            ?: 0.0
        val directlyReliable = directlyFittedWorldLeft != null &&
            (screenCenterByLogicalColumn.size == 1 || (residual ?: Double.MAX_VALUE) <= residualLimit)
        var geometryInferred = false
        var viewportWorldLeft = directlyFittedWorldLeft?.takeIf { directlyReliable }
        var geometryReliable = directlyReliable
        if (directlyReliable) {
            consecutiveInferredGeometryFrames = 0
        } else if (consecutiveInferredGeometryFrames < MAX_CONSECUTIVE_INFERRED_GEOMETRY_FRAMES) {
            val previous = snapshots[currentSignature]
            val previousWorldLeft = previous?.viewportWorldLeft
            if (previous?.geometryReliable == true && previousWorldLeft != null) {
                val previousById = previous.globalNodes.associateBy(LabyrinthGlobalNodeCoordinate::blockId)
                val signedDeltas = rawTopology.mapNotNull { mapping ->
                    val old = previousById[mapping.blockId] ?: return@mapNotNull null
                    val oldCenter = old.screenRect.left + old.screenRect.width / 2.0
                    val newCenter = mapping.screenRect.left + mapping.screenRect.width / 2.0
                    newCenter - oldCenter
                }
                val medianScreenDelta = signedDeltas.medianOrNull()
                if (medianScreenDelta != null) {
                    // worldLeft = worldX - screenX, therefore a rightward screen translation
                    // decreases the fitted camera origin by the same amount.
                    viewportWorldLeft = previousWorldLeft - medianScreenDelta
                    geometryReliable = true
                    geometryInferred = true
                    consecutiveInferredGeometryFrames++
                }
            }
        }
        // Once the three-frame inference budget is exhausted, require a direct reliable fit to
        // re-arm it. Otherwise a long low-confidence sequence would oscillate between inferred
        // and unreliable forever.
        val snapshotTopology = if (geometryInferred) rawTopology else topology
        val snapshotGlobalNodes = snapshotTopology.map { mapping ->
            LabyrinthGlobalNodeCoordinate(
                blockId = mapping.blockId,
                area = area,
                logicalColumn = mapping.logicalColumn,
                visualRow = mapping.visualRow,
                worldX = mapping.logicalColumn * pitch,
                screenRect = mapping.screenRect,
                viewportSignature = viewportSignature,
            )
        }
        val snapshot = LabyrinthNodeViewportSnapshot(
            area = area,
            signature = viewportSignature,
            mappedLogicalColumns = snapshotTopology.mapTo(sortedSetOf(), NodeTopologyMapping::logicalColumn),
            globalNodes = snapshotGlobalNodes,
            viewportWorldLeft = viewportWorldLeft,
            columnSpacingResidual = residual,
            geometryReliable = geometryReliable,
            geometryInferred = geometryInferred,
            pixels = viewportPixels,
        )

        // A map edge is a camera fact, not a classification-string fact. Node type/confidence can
        // jitter while the camera is completely stationary, so compare the fitted camera offset
        // (or overlapping node centers when geometry is unavailable) before deciding that a swipe
        // actually moved the viewport. Require several unchanged observations so the first frames
        // of the swipe animation are never mistaken for an edge.
        pendingSwipe?.let { swipe ->
            val source = snapshots[swipe.sourceSignature]
            val motion = viewportMovedAfterSwipe(source, snapshot, swipe.sourcePixels, viewportPixels)
            if (motion == NodeViewportMotion.MOVED) {
                pendingSwipe = null
                uncertainSwipes.remove(swipe.direction)
            } else if (motion == NodeViewportMotion.UNKNOWN) {
                val observations = swipe.uncertainObservations + 1
                if (observations >= MAX_UNCERTAIN_OBSERVATIONS) {
                    // Permit another bounded probe without claiming movement or a map edge.
                    uncertainSwipes[swipe.direction] = (uncertainSwipes[swipe.direction] ?: 0) + 1
                    pendingSwipe = null
                } else {
                    pendingSwipe = swipe.copy(uncertainObservations = observations, unchangedObservations = 0)
                }
            } else {
                val unchangedObservations = swipe.unchangedObservations + 1
                if (unchangedObservations >= MIN_UNCHANGED_OBSERVATIONS_FOR_BOUNDARY) {
                    boundarySignatures.getValue(swipe.direction) += viewportSignature
                    reachedBoundaries += swipe.direction
                    pendingSwipe = null
                } else {
                    pendingSwipe = swipe.copy(unchangedObservations = unchangedObservations, uncertainObservations = 0)
                }
            }
        }

        snapshots[viewportSignature] = snapshot
        snapshotGlobalNodes.forEach { coordinate ->
            val observations = globalNodesByBlockId.getOrPut(coordinate.blockId) { mutableListOf() }
            observations.removeAll { it.viewportSignature == viewportSignature }
            observations += coordinate
        }
        currentSignature = viewportSignature
        currentPixels = viewportPixels
        return snapshot
    }

    fun plan(targetBlockId: Long, targetLogicalColumn: Int): LabyrinthNodeScanPlan? {
        if (activeTargetBlockId != targetBlockId) {
            resetTargetTraversal()
            activeTargetBlockId = targetBlockId
        }
        val snapshot = snapshots[currentSignature] ?: return defaultPlan()
        val preferred = preferredDirection(snapshot, targetLogicalColumn)
        val opposite = preferred.opposite()
        val selected = when {
            preferred !in reachedBoundaries && (uncertainSwipes[preferred] ?: 0) < MAX_UNCERTAIN_SWIPES_PER_DIRECTION -> preferred
            opposite !in reachedBoundaries && (uncertainSwipes[opposite] ?: 0) < MAX_UNCERTAIN_SWIPES_PER_DIRECTION -> opposite
            else -> return null
        }
        val reason = buildString {
            append(if (selected == preferred) "target-global-column" else "reverse-after-boundary-or-probe-limit")
            append(" targetColumn=").append(targetLogicalColumn)
            append(" mapped=").append(snapshot.mappedLogicalColumns.joinToString(","))
            snapshot.viewportWorldLeft?.let { worldLeft ->
                val expectedScreenX = targetLogicalColumn * labyrinthReferenceColumnPitch(frameHeight) - worldLeft
                append(" expectedScreenX=").append("%.1f".format(expectedScreenX))
            }
        }
        lastDirection = selected
        return LabyrinthNodeScanPlan(selected, reason)
    }

    fun recordSwipe(direction: LabyrinthMapScanDirection, sourceSignature: String) {
        pendingSwipe = PendingSwipe(direction, sourceSignature, sourcePixels = currentPixels)
        lastDirection = direction
    }

    /** Do not dispatch another swipe until the previous one has produced movement or an edge. */
    fun awaitingSwipeOutcome(): Boolean = pendingSwipe != null

    /** Signature of the most recently observed viewport; the key [recordSwipe] should be given. */
    fun currentSignature(): String = currentSignature

    fun exhaustionReason(): String = if (uncertainSwipes.values.any { it >= MAX_UNCERTAIN_SWIPES_PER_DIRECTION }) {
        "map-motion-probes-exhausted"
    } else {
        "both-map-boundaries-scanned"
    }

    /**
     * True only when reliable current-view geometry says the target column should already be well
     * inside the visible screen. In that case scrolling is more likely to hide the target than to
     * help recognition, so the caller should keep the camera still and retry vision instead.
     */
    fun targetLikelyVisible(logicalColumn: Int): Boolean {
        if (frameWidth <= 0 || frameHeight <= 0) return false
        val snapshot = snapshots[currentSignature] ?: return false
        if (!snapshot.geometryReliable) return false
        if (logicalColumn in snapshot.mappedLogicalColumns) return true
        val expectedX = expectedScreenCenterX(logicalColumn) ?: return false
        val margin = frameWidth * TARGET_VISIBLE_EDGE_MARGIN_RATIO
        return expectedX in margin..(frameWidth - margin)
    }

    fun diagnostics(targetBlockId: Long?): String {
        val boundaries = LabyrinthMapScanDirection.entries.joinToString(",") { direction ->
            "${direction.name.lowercase()}=${boundarySignatures.getValue(direction).size}"
        }
        val targetSegments = targetBlockId
            ?.let(globalNodesByBlockId::get)
            .orEmpty()
            .map(LabyrinthGlobalNodeCoordinate::viewportSignature)
            .distinct()
            .size
        val current = snapshots[currentSignature]
        return "segments=${snapshots.size} globalNodes=${globalNodesByBlockId.size} " +
            "targetSegments=$targetSegments boundaries=[$boundaries] " +
            "uncertainSwipes=$uncertainSwipes geometryReliable=${current?.geometryReliable ?: false} " +
            "geometryInferred=${current?.geometryInferred ?: false} " +
            "spacingResidual=${current?.columnSpacingResidual?.let { "%.1f".format(it) } ?: "n/a"}"
    }

    fun snapshots(): List<LabyrinthNodeViewportSnapshot> = snapshots.values.toList()

    fun globalCoordinates(blockId: Long): List<LabyrinthGlobalNodeCoordinate> =
        globalNodesByBlockId[blockId].orEmpty().toList()

    /**
     * Predict the current-frame screen center for a logical column from the fitted viewport.
     * Returns null when topology geometry is not reliable enough to synthesize a tap coordinate.
     */
    fun expectedScreenCenterX(logicalColumn: Int): Double? {
        if (frameHeight <= 0) return null
        val snapshot = snapshots[currentSignature]?.takeIf { it.geometryReliable && it.viewportWorldLeft != null }
            ?: recalledStillSnapshot()
            ?: return null
        val worldLeft = snapshot.viewportWorldLeft ?: return null
        return logicalColumn * labyrinthReferenceColumnPitch(frameHeight) - worldLeft
    }

    /**
     * The most recent reliable snapshot whose sampled pixels prove the camera has not moved since.
     *
     * An exact signature match is the cheap path, but the signature hashes node classifications,
     * which the map's glow animation perturbs while the camera is still. The pixel comparison is
     * the same one the swipe logic trusts to tell an edge from a scroll. Only the last few
     * snapshots are candidates: the map repeats its backdrop, so a far-away segment that merely
     * looks alike must never lend its camera fit to the current frame, and the comparison itself
     * is not free.
     */
    private fun recalledStillSnapshot(): LabyrinthNodeViewportSnapshot? {
        val pixels = currentPixels ?: return null
        var examined = 0
        val iterator = snapshots.values.reversed().iterator()
        while (iterator.hasNext() && examined < MAX_PIXEL_RECALL_CANDIDATES) {
            val candidate = iterator.next()
            examined++
            if (!candidate.geometryReliable || candidate.viewportWorldLeft == null) continue
            val previous = candidate.pixels ?: continue
            if (pixels.motionFrom(previous) == NodeViewportMotion.UNCHANGED) return candidate
        }
        return null
    }

    private fun defaultPlan(): LabyrinthNodeScanPlan? {
        if (currentSignature.isBlank()) return null
        val preferred = lastDirection ?: LabyrinthMapScanDirection.FORWARD
        val opposite = preferred.opposite()
        val selected = when {
            preferred !in reachedBoundaries && (uncertainSwipes[preferred] ?: 0) < MAX_UNCERTAIN_SWIPES_PER_DIRECTION -> preferred
            opposite !in reachedBoundaries && (uncertainSwipes[opposite] ?: 0) < MAX_UNCERTAIN_SWIPES_PER_DIRECTION -> opposite
            else -> return null
        }
        lastDirection = selected
        return LabyrinthNodeScanPlan(selected, "fallback-without-global-geometry")
    }

    private fun preferredDirection(
        snapshot: LabyrinthNodeViewportSnapshot,
        targetLogicalColumn: Int,
    ): LabyrinthMapScanDirection {
        snapshot.viewportWorldLeft?.let { worldLeft ->
            val expectedScreenX = targetLogicalColumn * labyrinthReferenceColumnPitch(frameHeight) - worldLeft
            return if (expectedScreenX >= frameWidth / 2.0) {
                LabyrinthMapScanDirection.FORWARD
            } else {
                LabyrinthMapScanDirection.BACKWARD
            }
        }
        val mapped = snapshot.mappedLogicalColumns
        return when {
            mapped.isEmpty() -> lastDirection ?: LabyrinthMapScanDirection.FORWARD
            targetLogicalColumn > mapped.max() -> LabyrinthMapScanDirection.FORWARD
            targetLogicalColumn < mapped.min() -> LabyrinthMapScanDirection.BACKWARD
            targetLogicalColumn >= mapped.average() -> LabyrinthMapScanDirection.FORWARD
            else -> LabyrinthMapScanDirection.BACKWARD
        }
    }

    private fun LabyrinthMapScanDirection.opposite(): LabyrinthMapScanDirection = when (this) {
        LabyrinthMapScanDirection.FORWARD -> LabyrinthMapScanDirection.BACKWARD
        LabyrinthMapScanDirection.BACKWARD -> LabyrinthMapScanDirection.FORWARD
    }

    private fun resetTargetTraversal() {
        pendingSwipe = null
        lastDirection = null
        reachedBoundaries.clear()
        uncertainSwipes.clear()
        boundarySignatures.values.forEach { it.clear() }
    }

    private fun List<Double>.medianOrNull(): Double? {
        if (isEmpty()) return null
        val sorted = sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 0) {
            (sorted[middle - 1] + sorted[middle]) / 2.0
        } else {
            sorted[middle]
        }
    }

    private companion object {
        const val MIN_TOPOLOGY_CONFIDENCE_FOR_GLOBAL_COORDINATE = 0.70
        /** Snapshots (newest first) that [expectedScreenCenterX] may recall by pixel comparison. */
        const val MAX_PIXEL_RECALL_CANDIDATES = 3
        const val MAX_COLUMN_SPACING_RESIDUAL_NODE_WIDTH_RATIO = 0.55
        const val MIN_UNCHANGED_OBSERVATIONS_FOR_BOUNDARY = 3
        const val MIN_CAMERA_MOVE_PITCH_RATIO = 0.16
        const val TARGET_VISIBLE_EDGE_MARGIN_RATIO = 0.08
        const val MAX_CONSECUTIVE_INFERRED_GEOMETRY_FRAMES = 3
        const val MAX_UNCERTAIN_OBSERVATIONS = 6
        const val MAX_UNCERTAIN_SWIPES_PER_DIRECTION = 2
    }
}

/** Area-2 stitched ground truth: adjacent logical columns are about 540px at 1920x1080. */
fun labyrinthReferenceColumnPitch(frameHeight: Int): Double =
    AREA2_COLUMN_PITCH_AT_1080 * frameHeight.coerceAtLeast(1) / 1080.0

private const val AREA2_COLUMN_PITCH_AT_1080 = 540.0
