package com.landosol.toolbox.labyrinth.vision

import com.landosol.toolbox.clanbattle.recognition.PixelImage
import kotlin.math.roundToInt

/** Scroll-thumb offset along its available travel, not a count of goods or OCR signatures. */
data class LabyrinthShopViewport(val atTop: Boolean, val atBottom: Boolean, val position: Int) {
    init { require(position in 0..1000) }
}

/** Pure, bounded-cost pixel evidence. Uncertain/missing thumbs never prove a shop boundary. */
object LabyrinthShopViewportRecognition {
    private val REFERENCE = EntryReferenceSize(1920, 1080)
    // Search ROI only: real 1280x720 rails end at y563..565, before the ROI bottom y574.
    // New ROIs only: the existing six item/title rectangles intentionally stay unchanged.
    private val TRACK = EntryReferenceRect(1820, 226, 20, 636)
    private val GOODS = EntryReferenceRect(96, 240, 1680, 600)

    fun recognize(frame: PixelImage): LabyrinthShopViewport? {
        val track = map(frame, TRACK) ?: return null
        if (track.width < 5 || track.height < 60) return null
        var firstBlue = -1
        var lastBlue = -1
        var blueRows = 0
        var firstRail = -1
        var lastRail = -1
        var railRows = 0
        for (y in track.top until track.top + track.height) {
            var blue = 0
            var rail = 0
            // Stay inside the rail's central body, away from its white/gold gutter.
            for (column in 0 until 5) {
                val x = track.left + ((0.30 + column * 0.10) * (track.width - 1)).roundToInt()
                val color = frame[x, y]
                if (isBlueThumb(color)) {
                    blue++
                    rail++
                } else if (isGreyRail(color)) {
                    rail++
                }
            }
            if (rail >= 3) {
                if (firstRail < 0) firstRail = y
                lastRail = y
                railRows++
            }
            if (blue >= 3) {
                if (firstBlue < 0) firstBlue = y
                lastBlue = y
                blueRows++
            }
        }
        if (firstBlue < 0 || firstRail < 0) return null
        val thumbHeight = lastBlue - firstBlue + 1
        val railHeight = lastRail - firstRail + 1
        // The search ROI includes end-cap/gutter padding, not usable thumb travel.
        // Trim only a small supported margin (16px at 720p); missing rail must stay uncertain.
        val maxPadding = maxOf(2, (track.height * 24.0 / TRACK.height).roundToInt())
        if (firstRail - track.top > maxPadding ||
            track.top + track.height - 1 - lastRail > maxPadding
        ) return null
        // Require substantially continuous blue/grey support; allow small JPEG edge holes.
        // Visible inner-rail travel rejects a blue background masquerading as a full-length thumb.
        if (thumbHeight < maxOf(8, (railHeight * 0.12).roundToInt()) ||
            thumbHeight > railHeight * 0.95 ||
            blueRows < thumbHeight * 0.96 || railRows < track.height * 0.90 ||
            railRows < railHeight * 0.96
        ) return null
        val tolerance = maxOf(2, (track.height * 10.0 / TRACK.height).roundToInt())
        val topGap = firstBlue - firstRail
        val bottomGap = lastRail - lastBlue
        val atTop = topGap <= tolerance
        val atBottom = bottomGap <= tolerance
        val travel = railHeight - thumbHeight
        val position = when {
            atTop -> 0
            atBottom -> 1000
            else -> (topGap * 1000.0 / travel).roundToInt().coerceIn(0, 1000)
        }
        return LabyrinthShopViewport(atTop, atBottom, position)
    }

    /** 18x8 color cells, five samples each (720 pixels); excludes the rail and bottom wallet. */
    fun visualFingerprint(frame: PixelImage): Long {
        val goods = map(frame, GOODS) ?: return 0
        var hash = 1125899906842597L
        for (row in 0 until 8) {
            for (column in 0 until 18) {
                var red = 0
                var green = 0
                var blue = 0
                for (sample in 0 until 5) {
                    val dx = when (sample) { 1, 3 -> 0.25; 2, 4 -> 0.75; else -> 0.5 }
                    val dy = when (sample) { 1, 2 -> 0.25; 3, 4 -> 0.75; else -> 0.5 }
                    val x = goods.left + ((column + dx) * goods.width / 18).toInt()
                    val y = goods.top + ((row + dy) * goods.height / 8).toInt()
                    val color = frame[x, y]
                    red += color ushr 16 and 0xff
                    green += color ushr 8 and 0xff
                    blue += color and 0xff
                }
                // Coarse mean RGB suppresses small JPEG/capture noise; no OCR is involved.
                val quantized = ((red / 5 ushr 5) shl 6) or
                    ((green / 5 ushr 5) shl 3) or (blue / 5 ushr 5)
                hash = hash * 31 + quantized
            }
        }
        return if (hash == 0L) 1L else hash
    }

    private fun map(frame: PixelImage, rect: EntryReferenceRect): EntryPixelRect? =
        ReferenceFitMapper.map(frame.width, frame.height, REFERENCE, rect)

    private fun isBlueThumb(color: Int): Boolean {
        val red = color ushr 16 and 0xff
        val green = color ushr 8 and 0xff
        val blue = color and 0xff
        return blue >= 180 && green >= 100 && blue - red >= 60 && blue - green >= 25
    }

    private fun isGreyRail(color: Int): Boolean {
        val red = color ushr 16 and 0xff
        val green = color ushr 8 and 0xff
        val blue = color and 0xff
        return red in 85..205 && green in 90..210 && blue in 100..230 &&
            maxOf(red, green, blue) - minOf(red, green, blue) <= 55
    }
}
