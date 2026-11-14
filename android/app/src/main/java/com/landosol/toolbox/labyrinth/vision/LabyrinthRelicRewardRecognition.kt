package com.landosol.toolbox.labyrinth.vision

import com.landosol.toolbox.clanbattle.recognition.PixelImage
import kotlin.math.roundToInt

enum class LabyrinthRelicRewardKind { RELIC_EFFECT }

/** Relic-effect rewards already granted by the panel. */
data class LabyrinthRelicRewardObservation(
    val kind: LabyrinthRelicRewardKind? = null,
    val titleConfirmed: Boolean = false,
    val relics: List<LabyrinthRelicMatch> = emptyList(),
    val capturedAtMillis: Long = Long.MIN_VALUE,
    val layoutComplete: Boolean = false,
    val rowSlots: Set<String> = emptySet(),
)

internal object LabyrinthRelicRewardText {
    private val coinReward = Regex("阿尔法金币(?:[×xX]?[0-9]+)?")
    private fun normalized(text: String?) = text?.filterNot(Char::isWhitespace)
    fun kind(text: String?): LabyrinthRelicRewardKind? = when (normalized(text)) {
        "迷宫遗物效果" -> LabyrinthRelicRewardKind.RELIC_EFFECT
        else -> null
    }
    fun ordinaryTitle(text: String?): Boolean = normalized(text) == "获得道具"
    fun seriesTitle(text: String?): Boolean = normalized(text) == "系列效果一览"
    fun nonRelic(text: String?): Boolean = normalized(text)?.let {
        "选择印记" in it || "随机印记" in it || coinReward.matches(it)
    } == true
}

data class LabyrinthRelicRewardRow(val match: LabyrinthRelicMatch, val hasDetailButton: Boolean, val nameRect: EntryPixelRect)
data class LabyrinthRelicRewardLayout(val rows: List<LabyrinthRelicRewardRow>, val complete: Boolean)

/** Scans the icon column independently of detail buttons, including rows without one. */
class LabyrinthRelicRewardRecognizer(private val templates: List<LabyrinthRelicTemplate>) {
    private val matcher = AlignedRgbRelicMatcher()

    fun candidate(frame: PixelImage, result: LabyrinthEntryFrameResult): Boolean {
        if (labyrinthSpecialEquipmentOwnsFrame(result)) return false
        if (result.observation.state !in setOf(LabyrinthEntryPageState.UNKNOWN,
                LabyrinthEntryPageState.ITEM_REWARD, LabyrinthEntryPageState.NODE_SELECTION,
                LabyrinthEntryPageState.NODE_MAP_VIEW)) return false
        if (result.observation.anchorScores[EntryAnchorId.ITEM_REWARD_CLOSE] < 0.55) return false
        // Structural cue; the title is confirmed by two independent OCR reads.
        if (!listOf(600, 1320).all { x ->
                val rect = map(frame, EntryReferenceRect(x, 75, 8, 8)) ?: return@all false
                blue(frame[rect.left, rect.top])
            }) return false
        return listOf(EntryReferenceRect(500, 230, 8, 8), EntryReferenceRect(1400, 230, 8, 8),
            EntryReferenceRect(500, 865, 8, 8), EntryReferenceRect(1400, 865, 8, 8)).all { reference ->
            val rect = map(frame, reference) ?: return@all false
            val color = frame[rect.left, rect.top]
            val channels = listOf(color ushr 16 and 255, color ushr 8 and 255, color and 255)
            channels.min() >= 205 && channels.max() - channels.min() < 35
        }
    }

    fun recognize(frame: PixelImage): LabyrinthRelicRewardLayout {
        val column = map(frame, EntryReferenceRect(550, 270, 110, 565))
            ?: return LabyrinthRelicRewardLayout(emptyList(), false)
        val scale = column.width / 110.0
        val occupied = (column.top until column.top + column.height).filter { y ->
            (column.left until column.left + column.width).count { x ->
                val p = frame[x, y]
                minOf(p ushr 16 and 255, p ushr 8 and 255, p and 255) < 195
            } >= maxOf(3, (8 * scale).roundToInt())
        }
        val groups = mutableListOf<IntRange>()
        var start: Int? = null
        var previous = 0
        for (y in occupied) {
            if (start == null) start = y
            else if (y - previous > maxOf(2, (5 * scale).roundToInt())) {
                groups += start..previous
                start = y
            }
            previous = y
        }
        if (start != null) groups += start..previous
        // Thin separator lines are skipped; a clipped or obstructed icon marks the list incomplete.
        val spans = groups.filter { (it.last - it.first + 1) / scale >= 20 }
        val full = spans.filter { (it.last - it.first + 1) / scale in 110.0..136.0 &&
            it.first > column.top && it.last < column.top + column.height - 1 }
        val rows = full.map { span ->
            // Score a fixed-size square aligned to each visible edge;
            // the catalog ranking picks the fit.
            val referenceIcon = requireNotNull(map(frame, EntryReferenceRect(545, 270, 123, 123)))
            val candidates = listOf(span.first, span.last + 1 - referenceIcon.height).distinct().map { top ->
                EntryPixelRect(referenceIcon.left, top, referenceIcon.width, referenceIcon.height)
            }
            val ranked = candidates.flatMap { rect -> matcher.rank(frame, rect, templates).map { (template, score) ->
                Triple(template, score, rect)
            } }.groupBy { it.first.relicId }.values.map { matches -> matches.maxBy { it.second } }.sortedByDescending { it.second }
            val best = ranked.firstOrNull()
            val icon = best?.third ?: candidates.first()
            val centerY = icon.top + icon.height / 2
            val detailColumn = requireNotNull(map(frame, EntryReferenceRect(1292, 270, 64, 565)))
            val radius = (30 * scale).roundToInt()
            val detailPixels = (maxOf(column.top, centerY - radius)..minOf(column.top + column.height - 1, centerY + radius)).sumOf { y ->
                (detailColumn.left until detailColumn.left + detailColumn.width).count { x -> blue(frame[x, y]) }
            }
            val nameColumn = requireNotNull(map(frame, EntryReferenceRect(680, 270, 580, 565)))
            val nameRect = EntryPixelRect(nameColumn.left, maxOf(column.top, centerY - radius), nameColumn.width, radius * 2)
            val rival = ranked.getOrNull(1)?.second ?: 0.0
            val score = best?.second ?: 0.0
            val template = best?.first?.takeIf { score >= 0.80 && score - rival >= 0.06 }
            LabyrinthRelicRewardRow(LabyrinthRelicMatch(
                slotId = "reward_${rowsPosition(centerY, column.top, scale)}",
                relicId = template?.relicId, displayName = template?.displayName,
                attribute = template?.attribute, attributeBonus = template?.attributeBonus,
                effect = template?.effect, confidence = score, screenRect = icon, iconRect = icon,
                rarity = template?.rarity ?: 0, suspectedRelicId = best?.first?.relicId,
                suspectedDisplayName = best?.first?.displayName, suspectedAttribute = best?.first?.attribute,
                rivalConfidence = rival, rivalMargin = score - rival,
            ), detailPixels >= (40 * scale * scale).roundToInt(), nameRect)
        }
        return LabyrinthRelicRewardLayout(rows, rows.isNotEmpty() && full.size == spans.size)
    }

    private fun rowsPosition(centerY: Int, top: Int, scale: Double) = ((centerY - top) / scale / 15).roundToInt()
    private fun blue(pixel: Int): Boolean {
        val r = pixel ushr 16 and 255
        val g = pixel ushr 8 and 255
        val b = pixel and 255
        return b > 130 && b - r > 25 && g - r > 5
    }
    private fun map(frame: PixelImage, rect: EntryReferenceRect) =
        ReferenceFitMapper.map(frame.width, frame.height, EntryReferenceSize(1920, 1080), rect)
}
