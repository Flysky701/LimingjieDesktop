package com.landosol.toolbox.labyrinth.vision

import com.landosol.toolbox.vision.PixelImage
import kotlin.math.abs
import kotlin.math.roundToInt

/** Pixel slots are derived from the original reference rectangles; no column/X search is allowed. */
internal data class LabyrinthShopLayoutSlot(
    val id: String,
    val cardRect: EntryPixelRect,
    val buyButtonRect: EntryPixelRect,
    val iconRect: EntryPixelRect,
    val titleRect: EntryPixelRect,
    val visibility: LabyrinthShopItemVisibility = LabyrinthShopItemVisibility.UNRESOLVED,
    val buttonScore: Double = 0.0,
)

internal data class LabyrinthShopLayout(
    val slots: List<LabyrinthShopLayoutSlot>,
    val ready: Boolean,
)

/**
 * Locate row Y from the unchanged buy-button template inside the goods clip. A row needs two
 * independently matching columns; an unknown third control does not turn that FULL good into empty
 * stock. Cached Y is only a search hint and is rescored on every frame. Title and button must both
 * fit completely inside the clip before a slot is FULL. All mapped ROIs share the same pixel dy.
 */
internal class LabyrinthShopVisibleLayout(
    private val matcher: GradientTemplateMatcher,
    private val minButtonConfidence: Double,
) {
    private data class Row(val y: Int, val scores: List<Double>) {
        // Two-column support prevents a title/info icon from anchoring an entire row.
        val confidence: Double get() = scores.sortedDescending()[1]
    }

    private var cachedSize: Pair<Int, Int>? = null
    private var cachedRows: List<Int> = emptyList()

    fun recognize(
        frame: PixelImage,
        template: PixelImage?,
        fixedSlots: List<LabyrinthShopLayoutSlot>,
    ): LabyrinthShopLayout {
        val clip = clip(frame) ?: return LabyrinthShopLayout(fixedSlots, false)
        if (template == null || fixedSlots.size != 6) return LabyrinthShopLayout(fixedSlots, false)
        val size = frame.width to frame.height
        if (cachedSize != size) {
            cachedSize = size
            cachedRows = emptyList()
        }
        val columns = fixedSlots.take(3).map { it.buyButtonRect }
        val firstY = clip.top
        val lastY = clip.top + clip.height - columns.maxOf { it.height }
        if (lastY < firstY) return LabyrinthShopLayout(fixedSlots, false)
        val scale = minOf(frame.width / 1920.0, frame.height / 1080.0)
        val step = maxOf(2, (8 * scale).roundToInt())
        val separation = maxOf(3, columns.maxOf { it.height })
        val scored = mutableMapOf<Int, Row>()
        fun score(y: Int): Row = scored.getOrPut(y) {
            Row(y, columns.map { rect ->
                matcher.score(frame, rect.copy(top = y), template, minimumChannelScore = 0.0)
            })
        }
        fun refine(y: Int): Row = (maxOf(firstY, y - step)..minOf(lastY, y + step))
            .map(::score).maxByOrNull { it.confidence } ?: score(y)
        fun distinctRows(rows: List<Row>, limit: Int): List<Row> {
            val selected = mutableListOf<Row>()
            for (row in rows.sortedByDescending { it.confidence }) {
                if (selected.none { abs(it.y - row.y) < separation }) selected += row
                if (selected.size == limit) break
            }
            return selected
        }

        // Fast path: fixed rectangles first, then the last accepted Ys (never cached authorization).
        val hints = (fixedSlots.map { it.buyButtonRect.top }.distinct() + cachedRows)
            .distinct().filter { it in firstY..lastY }
        val fast = distinctRows(hints.map(::refine).filter { it.confidence >= minButtonConfidence }, 2)
        // One supported row may coexist with another shifted row. Search the remaining bounded
        // range too, rather than mistaking fixed-slot UNKNOWNs for off-screen/empty goods.
        val rows = if (fast.size == 2) fast else {
            val coarse = (firstY..lastY step step).map(::score) + score(lastY)
            val peaks = distinctRows(coarse, MAX_REFINEMENT_PEAKS)
            distinctRows(fast + peaks.map { refine(it.y) }
                .filter { it.confidence >= minButtonConfidence }, 2)
        }.sortedBy { it.y }
        cachedRows = rows.map { it.y }
        if (rows.isEmpty()) return LabyrinthShopLayout(fixedSlots, false)

        // Slot IDs are viewport-local, not global goods identities. Preserve row groups when only
        // one row is detected by choosing its nearest fixed group; with two rows use top/bottom.
        val groups = fixedSlots.chunked(3)
        val rowGroups = if (rows.size == 2) listOf(0, 1) else listOf(
            groups.indices.minByOrNull { abs(groups[it][0].buyButtonRect.top - rows[0].y) } ?: 0,
        )
        val mapped = fixedSlots.toMutableList()
        for ((rowIndex, row) in rows.withIndex()) {
            val group = rowGroups[rowIndex]
            for ((column, slot) in groups[group].withIndex()) {
                val dy = row.y - slot.buyButtonRect.top
                val titleTop = slot.titleRect.top + dy
                val buttonTop = slot.buyButtonRect.top + dy
                val titleInside = inside(clip, slot.titleRect, titleTop)
                val buttonInside = inside(clip, slot.buyButtonRect, buttonTop)
                val cardTop = slot.cardRect.top + dy
                val cardIntersects = cardTop < clip.top + clip.height &&
                    cardTop + slot.cardRect.height > clip.top
                mapped[group * 3 + column] = slot.copy(
                    cardRect = translate(frame, slot.cardRect, dy),
                    buyButtonRect = translate(frame, slot.buyButtonRect, dy),
                    iconRect = translate(frame, slot.iconRect, dy),
                    titleRect = translate(frame, slot.titleRect, dy),
                    visibility = when {
                        titleInside && buttonInside -> LabyrinthShopItemVisibility.FULL
                        cardIntersects -> LabyrinthShopItemVisibility.PARTIAL
                        else -> LabyrinthShopItemVisibility.UNSEEN
                    },
                    buttonScore = row.scores[column],
                )
            }
        }
        val full = mapped.filter { it.visibility == LabyrinthShopItemVisibility.FULL }
        if (full.isEmpty()) return LabyrinthShopLayout(mapped, false)
        // An unlocated group is NOT assumed off-screen. Use independently located, non-overlapping
        // card footprints to rule out room for another fully visible title-through-button span.
        // If that geometric proof is unavailable, keep the view unresolved instead of ending it.
        val requiredSpan = full.minOf { it.buyButtonRect.top + it.buyButtonRect.height - it.titleRect.top }
        var cursor = clip.top
        var roomForUnlocatedFullRow = false
        for (slot in mapped.filter { it.visibility != LabyrinthShopItemVisibility.UNRESOLVED }
            .distinctBy { it.cardRect.top }.sortedBy { it.cardRect.top }) {
            val start = maxOf(clip.top, slot.cardRect.top)
            if (start - cursor >= requiredSpan) roomForUnlocatedFullRow = true
            cursor = maxOf(cursor, minOf(clip.top + clip.height, slot.cardRect.top + slot.cardRect.height))
        }
        if (clip.top + clip.height - cursor >= requiredSpan) roomForUnlocatedFullRow = true
        val hasUnlocated = mapped.any { it.visibility == LabyrinthShopItemVisibility.UNRESOLVED }
        return LabyrinthShopLayout(mapped, !hasUnlocated || !roomForUnlocatedFullRow)
    }

    private fun inside(clip: EntryPixelRect, rect: EntryPixelRect, top: Int): Boolean =
        rect.left >= clip.left && rect.left + rect.width <= clip.left + clip.width &&
            top >= clip.top && top + rect.height <= clip.top + clip.height

    // FULL coordinates are unchanged except dy. Only non-FULL crops can need frame-bound clipping;
    // they are diagnostic-only and must never enter OCR, icon matching or purchase authorization.
    private fun translate(frame: PixelImage, rect: EntryPixelRect, dy: Int): EntryPixelRect {
        val top = (rect.top + dy).coerceIn(0, frame.height - 1)
        return rect.copy(top = top, height = minOf(rect.height, frame.height - top))
    }

    companion object {
        private const val MAX_REFINEMENT_PEAKS = 6
        private val REFERENCE = EntryReferenceSize(1920, 1080)
        // New list clip only; all existing slot/title parameters stay unchanged.
        private val LIST_CLIP = EntryReferenceRect(80, 204, 1720, 666)
        fun clip(frame: PixelImage): EntryPixelRect? =
            ReferenceFitMapper.map(frame.width, frame.height, REFERENCE, LIST_CLIP)

        // Same original title rectangles as LabyrinthShopItemResolver's legacy fallback.
        // Kept pure here so Y localization can be tested without loading Android OCR classes.
        val TITLE_RECTS = mapOf(
            "shop_item_1" to EntryReferenceRect(125, 230, 390, 55),
            "shop_item_2" to EntryReferenceRect(703, 230, 390, 55),
            "shop_item_3" to EntryReferenceRect(1278, 230, 390, 55),
            "shop_item_4" to EntryReferenceRect(125, 513, 390, 55),
            "shop_item_5" to EntryReferenceRect(703, 513, 390, 55),
            "shop_item_6" to EntryReferenceRect(1278, 513, 390, 55),
        )
    }
}
