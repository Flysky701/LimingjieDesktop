package com.landosol.toolbox.labyrinth.node

import com.landosol.toolbox.labyrinth.vision.EntryPixelRect
import com.landosol.toolbox.labyrinth.vision.EntryReferenceRect
import com.landosol.toolbox.labyrinth.vision.EntryReferenceSize
import com.landosol.toolbox.labyrinth.vision.ReferenceFitMapper
import kotlin.math.roundToInt

/**
 * 黎明界地图节点的屏幕候选区域。
 *
 * 节点的纵向位置会随地图滚动和当前路线变化，因此这些区域只作为搜索中心，
 * [LabyrinthNodeClassifier] 会在中心附近做有限偏移搜索。横向则保留两套实际素材
 * 参考系：1920x1080 普通宽屏，以及 2780x1264 的 MuMu 宽屏截图。
 */
object NodeAnchorDefinitions {
    private val STANDARD_REFERENCE = EntryReferenceSize(1920, 1080)
    private val WIDE_REFERENCE = EntryReferenceSize(2780, 1264)

    /** 保留公开列表，供路线映射和调试使用。 */
    val NODE_ICON_RECTS: List<NodeIconDef> by lazy {
        buildList {
            NODE_COLUMNS.forEach { column ->
                column.rows.forEachIndexed { index, row ->
                    add(
                        NodeIconDef(
                            column = column.column,
                            row = index + 1,
                            standard = row.standard,
                            wide = row.wide,
                        ),
                    )
                }
            }
        }
    }

    /**
     * 返回当前帧内可见的节点候选区域。超出画面的候选会被过滤，避免把右侧裁切节点
     * 当成可点击目标。
     */
    fun visibleNodeRects(frameWidth: Int, frameHeight: Int): List<NodeIconCandidate> =
        NODE_ICON_RECTS.mapNotNull { definition ->
            getNodeIconRect(definition, frameWidth, frameHeight)?.let { rect ->
                NodeIconCandidate(definition.column, definition.row, rect)
            }
        }

    /** 特殊地图标记使用独立尺寸，不能塞进普通 280x350 节点框。 */
    fun visibleSpecialNodeRects(
        frameWidth: Int,
        frameHeight: Int,
    ): List<NodeSpecialCandidate> = SPECIAL_NODE_RECTS.mapNotNull { definition ->
        getSpecialNodeRect(definition, frameWidth, frameHeight)?.let { rect ->
            NodeSpecialCandidate(definition.id, definition.column, rect)
        }
    }

    /**
     * 将节点参考区域映射到实际截图。超宽画面使用 2780x1264 参考系，其余横屏画面
     * 使用 1920x1080 参考系；两者都采用 FIT，保证换分辨率后不会拉伸坐标。
     */
    fun getNodeIconRect(
        def: NodeIconDef,
        frameWidth: Int,
        frameHeight: Int,
    ): EntryPixelRect? = getNodeIconRectAtOffset(
        def = def,
        frameWidth = frameWidth,
        frameHeight = frameHeight,
        offsetX = 0,
        offsetY = 0,
    )

    /**
     * Map a node anchor after applying a search offset.
     *
     * This deliberately performs the bounds check *after* the offset. Column 4 starts at x=1900
     * in the 1920 reference canvas, so its 280px search box is outside the base viewport. Once the
     * map camera moves left, however, a -240/-320 offset brings that same node fully on screen. The
     * old `visibleNodeRects() -> offsetInside()` flow discarded column 4 before those offsets were
     * ever tried, making an already-visible 204xx node impossible to classify.
     */
    fun getNodeIconRectAtOffset(
        def: NodeIconDef,
        frameWidth: Int,
        frameHeight: Int,
        offsetX: Int,
        offsetY: Int,
    ): EntryPixelRect? {
        require(frameWidth > 0 && frameHeight > 0)
        val useWideReference = frameWidth.toDouble() / frameHeight > WIDE_ASPECT_THRESHOLD
        val reference = if (useWideReference) WIDE_REFERENCE else STANDARD_REFERENCE
        val referenceRect = if (useWideReference) def.wide else def.standard
        val scale = minOf(
            frameWidth.toDouble() / reference.width,
            frameHeight.toDouble() / reference.height,
        )
        val fitOffsetX = (frameWidth - reference.width * scale) / 2.0
        val fitOffsetY = (frameHeight - reference.height * scale) / 2.0
        val left = (fitOffsetX + referenceRect.x * scale).roundToInt() + offsetX
        val top = (fitOffsetY + referenceRect.y * scale).roundToInt() + offsetY
        val right = (fitOffsetX + (referenceRect.x + referenceRect.width) * scale).roundToInt() + offsetX
        val bottom = (fitOffsetY + (referenceRect.y + referenceRect.height) * scale).roundToInt() + offsetY
        if (left < 0 || top < 0 || right > frameWidth || bottom > frameHeight) return null
        if (right - left < 3 || bottom - top < 3) return null
        return EntryPixelRect(left, top, right - left, bottom - top)
    }

    fun getSpecialNodeRect(
        def: NodeSpecialDef,
        frameWidth: Int,
        frameHeight: Int,
    ): EntryPixelRect? {
        require(frameWidth > 0 && frameHeight > 0)
        val useWideReference = frameWidth.toDouble() / frameHeight > WIDE_ASPECT_THRESHOLD
        val reference = if (useWideReference) WIDE_REFERENCE else STANDARD_REFERENCE
        val referenceRect = if (useWideReference) def.wide else def.standard
        return ReferenceFitMapper.map(
            frameWidth = frameWidth,
            frameHeight = frameHeight,
            referenceSize = reference,
            referenceRect = referenceRect,
        )
    }

    /**
     * 参考区域的中心附近搜索偏移。偏移按参考系缩放，覆盖地图滚动造成的纵向漂移，
     * 又不会把搜索扩大到整个画面。
     */
    fun searchOffsets(
        frameWidth: Int,
        frameHeight: Int,
    ): List<Pair<Int, Int>> {
        val scale = if (frameWidth.toDouble() / frameHeight > WIDE_ASPECT_THRESHOLD) {
            frameHeight.toDouble() / WIDE_REFERENCE.height
        } else {
            frameHeight.toDouble() / STANDARD_REFERENCE.height
        }
        // The video shows horizontal map translations of roughly 280-320 reference pixels.
        //
        // A node template only clears its acceptance bar within a few pixels of the node — the
        // score is already below 0.60 eight pixels away — and the local refinement afterwards
        // reaches about +/-17 reference pixels from a proposal. A gap wider than 34 in this list
        // is therefore a horizontal band in which a perfectly visible node is invisible to the
        // scan, and which band the map came to rest in is decided by the previous swipe, so the
        // miss looks intermittent rather than systematic (2026-09-16 live: two unobstructed EVENT
        // nodes went undetected for minutes while the run reported the target as geometrically
        // visible but unclassifiable).
        //
        // The gap-free list is unconditional. It used to be reserved for searches with no camera
        // prediction, on the reasoning that a directed search is "already centred on the target"
        // and could keep the cheaper list with its 80-wide holes. That reasoning does not hold:
        // aiming at a column says nothing about where inside the column the holes fall, so a
        // directed scan was blind in the same bands. 2026-09-20 bundle 192445 paid for it on
        // nearly every hop — two directed misses (1.0 s each) followed by a 5.3 s unrestricted
        // rescan. On the node-target-missed fixture the coarse grid finds nothing at all while
        // the gap-free grid finds both EVENT nodes for the same ~180 ms, because the horizontal
        // restriction around the prediction has already cut the rectangle pool.
        val coarseXOffsets = listOf(
            -320, -240, -160, -112, -80, -70, -56, -28,
            0,
            28, 56, 80, 112, 160, 240, 320,
        )
        val xOffsets = (coarseXOffsets + GAP_FILLING_X_OFFSETS + GAP_FILLING_X_OFFSETS.map { -it })
            .sorted()
        val yOffsets = listOf(
            -168, -140, -112, -100, -84, -56, -28, 0,
            20, 28, 40, 56, 84, 112, 140, 160, 168,
        )
        return yOffsets.flatMap { y -> xOffsets.map { x -> (x * scale).toInt() to (y * scale).toInt() } }
    }

    /** Points that break the 48/80-wide holes in the base offsets down to at most 34. */
    private val GAP_FILLING_X_OFFSETS = listOf(136, 187, 213, 267, 293)

    /** 特殊节点的移动范围比普通节点小，避免每帧扩大成全屏穷举。 */
    fun specialSearchOffsets(
        candidate: NodeSpecialCandidate,
        frameWidth: Int,
        frameHeight: Int,
    ): List<Pair<Int, Int>> {
        val scale = if (frameWidth.toDouble() / frameHeight > WIDE_ASPECT_THRESHOLD) {
            frameHeight.toDouble() / WIDE_REFERENCE.height
        } else {
            frameHeight.toDouble() / STANDARD_REFERENCE.height
        }
        val xOffsets = when {
            candidate.id.startsWith("boss") -> listOf(-120, -60, 0, 50, 60, 120, 180, 200, 220, 240, 300)
            else -> listOf(-80, -40, 0, 40, 80, 120, 160, 220)
        }
        val yOffsets = listOf(-80, -40, 0, 40, 80, 120)
        return yOffsets.flatMap { y ->
            xOffsets.map { x -> (x * scale).toInt() to (y * scale).toInt() }
        }
    }

    private data class NodeColumn(
        val column: Int,
        val rows: List<NodeReferenceRectPair>,
    )

    private data class NodeReferenceRectPair(
        val standard: EntryReferenceRect,
        val wide: EntryReferenceRect,
    )

    private const val WIDE_ASPECT_THRESHOLD = 2.0

    private val SPECIAL_NODE_RECTS = listOf(
        NodeSpecialDef(
            id = "boss",
            column = 6,
            standard = EntryReferenceRect(900, 80, 650, 700),
            wide = EntryReferenceRect(1260, 80, 900, 820),
        ),
        NodeSpecialDef(
            id = "boss.platform",
            column = 6,
            standard = EntryReferenceRect(900, 400, 500, 350),
            wide = EntryReferenceRect(1260, 440, 700, 420),
        ),
        NodeSpecialDef(
            id = "clear",
            column = 0,
            standard = EntryReferenceRect(0, 330, 500, 500),
            wide = EntryReferenceRect(0, 360, 700, 560),
        ),
    )

    /*
     * 每个候选框约覆盖一个完整节点（图标、底座和类型标签）。
     * row 是屏幕自上而下的视觉行，不是服务端 blockId 尾号。
     */
    private val NODE_COLUMNS = listOf(
        NodeColumn(
            column = 1,
            rows = listOf(
                // A three-node logical column can be scrolled into the far-left viewport. These
                // are scan anchors rather than protocol topology, so keep all three vertical
                // bands here as well. Global overlap suppression and NodeTopologyMapper regroup
                // the surviving detections by their actual screen coordinates.
                pair(330, 70, 280, 350, 820, 50, 300, 390),
                pair(330, 350, 280, 350, 820, 350, 300, 390),
                pair(330, 650, 280, 350, 820, 650, 300, 390),
            ),
        ),
        NodeColumn(
            column = 2,
            rows = listOf(
                // The video contains both two-node and three-node layouts in
                // this column. Use the three visual row anchors and let the
                // bounded offset search cover the two-node layout as well.
                pair(820, 70, 280, 350, 1430, 50, 300, 390),
                pair(820, 350, 280, 350, 1430, 350, 300, 390),
                pair(820, 650, 280, 350, 1430, 650, 300, 390),
            ),
        ),
        NodeColumn(
            column = 3,
            rows = listOf(
                pair(1360, 70, 280, 350, 2040, 50, 300, 390),
                pair(1360, 350, 280, 350, 2040, 350, 300, 390),
                pair(1360, 650, 280, 350, 2040, 650, 300, 390),
            ),
        ),
        NodeColumn(
            column = 4,
            rows = listOf(
                pair(1900, 70, 280, 350, 2650, 50, 300, 390),
                pair(1900, 350, 280, 350, 2650, 350, 300, 390),
                pair(1900, 650, 280, 350, 2650, 650, 300, 390),
            ),
        ),
        NodeColumn(
            column = 5,
            rows = listOf(
                pair(2440, 70, 280, 350, 3260, 50, 300, 390),
                pair(2440, 350, 280, 350, 3260, 350, 300, 390),
                pair(2440, 650, 280, 350, 3260, 650, 300, 390),
            ),
        ),
        NodeColumn(
            column = 6,
            rows = listOf(
                pair(2980, 230, 280, 350, 3870, 250, 300, 390),
            ),
        ),
    )

    private fun pair(
        standardX: Int,
        standardY: Int,
        standardWidth: Int,
        standardHeight: Int,
        wideX: Int,
        wideY: Int,
        wideWidth: Int,
        wideHeight: Int,
    ) = NodeReferenceRectPair(
        standard = EntryReferenceRect(standardX, standardY, standardWidth, standardHeight),
        wide = EntryReferenceRect(wideX, wideY, wideWidth, wideHeight),
    )
}

data class NodeIconDef(
    val column: Int,
    val row: Int,
    val standard: EntryReferenceRect,
    val wide: EntryReferenceRect,
)

data class NodeIconCandidate(
    val column: Int,
    val row: Int,
    val rect: EntryPixelRect,
)

data class NodeSpecialDef(
    val id: String,
    val column: Int,
    val standard: EntryReferenceRect,
    val wide: EntryReferenceRect,
)

data class NodeSpecialCandidate(
    val id: String,
    val column: Int,
    val rect: EntryPixelRect,
)

data class NodeConnectionDef(
    val fromColumn: Int,
    val toColumn: Int,
    val wide: EntryReferenceRect,
)
