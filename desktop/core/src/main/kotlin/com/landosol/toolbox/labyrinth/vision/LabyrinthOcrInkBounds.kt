package com.landosol.toolbox.labyrinth.vision

import com.landosol.toolbox.vision.PixelImage

/**
 * Bounds of dark text ink inside an already-isolated crop, in crop-local coordinates.
 *
 * ML Kit reports no text at all when a short name covers only a small fraction of a wide crop.
 * The static monster-detail name band is 500 reference px wide, while a two-glyph name such as
 * 美空 is roughly 60 px of ink in its top-left corner; longer names on the very same band read
 * fine. Tightening the crop to the ink before OCR keeps one static, per-page calibration while
 * handing the recognizer an image the text actually fills.
 *
 * Only neutral dark pixels count as ink. The decorative blue rule under the name row is both
 * brighter than the glyphs and strongly blue-shifted, so it can never stretch the box out to the
 * full panel width. This is trustworthy only over a flat light panel: a crop laid over animated
 * artwork has dark pixels everywhere and would tighten to nothing useful.
 */
internal fun labyrinthOcrInkBounds(
    crop: PixelImage,
    maximumInkLuminance: Int = 150,
    maximumInkBlueShift: Int = 24,
    minimumInkPixelsPerLine: Int = 2,
    padding: Int = 6,
): EntryPixelRect? {
    var top = Int.MAX_VALUE
    var bottom = Int.MIN_VALUE
    for (y in 0 until crop.height) {
        var ink = 0
        for (x in 0 until crop.width) {
            if (isOcrInk(crop[x, y], maximumInkLuminance, maximumInkBlueShift)) ink++
        }
        if (ink < minimumInkPixelsPerLine) continue
        if (y < top) top = y
        if (y > bottom) bottom = y
    }
    if (top > bottom) return null

    var left = Int.MAX_VALUE
    var right = Int.MIN_VALUE
    for (x in 0 until crop.width) {
        var ink = 0
        for (y in top..bottom) {
            if (isOcrInk(crop[x, y], maximumInkLuminance, maximumInkBlueShift)) ink++
        }
        if (ink < minimumInkPixelsPerLine) continue
        if (x < left) left = x
        if (x > right) right = x
    }
    if (left > right) return null

    val paddedLeft = (left - padding).coerceAtLeast(0)
    val paddedTop = (top - padding).coerceAtLeast(0)
    val paddedRight = (right + padding).coerceAtMost(crop.width - 1)
    val paddedBottom = (bottom + padding).coerceAtMost(crop.height - 1)
    return EntryPixelRect(
        left = paddedLeft,
        top = paddedTop,
        width = paddedRight - paddedLeft + 1,
        height = paddedBottom - paddedTop + 1,
    )
}

private fun isOcrInk(color: Int, maximumLuminance: Int, maximumBlueShift: Int): Boolean {
    val red = color shr 16 and 0xff
    val green = color shr 8 and 0xff
    val blue = color and 0xff
    val luminance = (red * 299 + green * 587 + blue * 114 + 500) / 1000
    return luminance <= maximumLuminance && blue - red <= maximumBlueShift
}
