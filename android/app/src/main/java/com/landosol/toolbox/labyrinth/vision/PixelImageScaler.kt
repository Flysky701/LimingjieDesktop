package com.landosol.toolbox.labyrinth.vision

import com.landosol.toolbox.clanbattle.recognition.PixelImage

/**
 * Area-average (box filter) downscale for template images.
 *
 * Why not bilinear: shrinking a 130x58 template into an 87x39 window with a sampling kernel skips
 * whole source columns, and the skipped column is never compensated. That aliasing is one of the
 * two reasons the anchors score lower on a 1280x720 capture; the other is the integer truncation
 * in [GradientTemplateMatcher]'s coordinate mapping. Averaging every source pixel that a target
 * pixel covers removes this one.
 *
 * Alpha handling: RGB is averaged weighted by alpha (equivalent to premultiplying, averaging, then
 * dividing the alpha back out), and alpha itself is averaged separately. A plain RGB average would
 * bleed the black of transparent pixels into the edge of a node icon, and that artificial
 * transparent-to-opaque ramp is exactly what the gradient channel reads as a feature. Keeping the
 * averaged alpha also preserves [GradientTemplateMatcher]'s mask behaviour at the template border.
 *
 * Upscaling is rejected: a box filter has no source pixel to cover for the extra target pixels, so
 * the result would contain holes. Callers only downscale (the prescaler skips anything else).
 */
object PixelImageScaler {
    fun scale(source: PixelImage, targetWidth: Int, targetHeight: Int): PixelImage {
        require(targetWidth > 0 && targetHeight > 0) { "target size must be positive" }
        require(targetWidth <= source.width && targetHeight <= source.height) {
            "only downscale is supported: ${source.width}x${source.height} -> ${targetWidth}x$targetHeight"
        }
        if (source.width == targetWidth && source.height == targetHeight) return source

        val sourceWidth = source.width
        val sourceHeight = source.height
        val sourcePixels = source.pixels
        val targetPixels = IntArray(targetWidth * targetHeight)

        // Each target pixel covers exactly sourceWidth x sourceHeight units of source area, no
        // matter where it sits, so the weight total is a constant and the alpha average needs no
        // per-pixel weight accumulation.
        val totalWeight = sourceWidth.toLong() * sourceHeight.toLong()

        for (targetY in 0 until targetHeight) {
            val sourceTop = targetY.toLong() * sourceHeight
            val sourceBottom = (targetY + 1).toLong() * sourceHeight
            val firstRow = (sourceTop / targetHeight).toInt()
            val lastRow = ceilDiv(sourceBottom, targetHeight)
            for (targetX in 0 until targetWidth) {
                val sourceLeft = targetX.toLong() * sourceWidth
                val sourceRight = (targetX + 1).toLong() * sourceWidth
                val firstColumn = (sourceLeft / targetWidth).toInt()
                val lastColumn = ceilDiv(sourceRight, targetWidth)

                var alphaWeight = 0L
                var redSum = 0L
                var greenSum = 0L
                var blueSum = 0L
                var alphaSum = 0L
                for (row in firstRow until lastRow) {
                    val rowOverlap = minOf(sourceBottom, (row + 1).toLong() * targetHeight) -
                        maxOf(sourceTop, row.toLong() * targetHeight)
                    if (rowOverlap <= 0L) continue
                    val rowBase = row * sourceWidth
                    for (column in firstColumn until lastColumn) {
                        val columnOverlap = minOf(sourceRight, (column + 1).toLong() * targetWidth) -
                            maxOf(sourceLeft, column.toLong() * targetWidth)
                        if (columnOverlap <= 0L) continue
                        val weight = rowOverlap * columnOverlap
                        val color = sourcePixels[rowBase + column]
                        val alpha = color ushr 24 and 0xff
                        alphaSum += alpha * weight
                        if (alpha == 0) continue
                        alphaWeight += alpha * weight
                        redSum += (color ushr 16 and 0xff) * alpha * weight
                        greenSum += (color ushr 8 and 0xff) * alpha * weight
                        blueSum += (color and 0xff) * alpha * weight
                    }
                }

                val targetAlpha = (alphaSum / totalWeight).toInt().coerceIn(0, 255)
                val index = targetY * targetWidth + targetX
                targetPixels[index] = if (alphaWeight == 0L) {
                    // Fully transparent footprint: keep the colour at zero so nothing bleeds in.
                    targetAlpha shl 24
                } else {
                    val red = (redSum / alphaWeight).toInt().coerceIn(0, 255)
                    val green = (greenSum / alphaWeight).toInt().coerceIn(0, 255)
                    val blue = (blueSum / alphaWeight).toInt().coerceIn(0, 255)
                    (targetAlpha shl 24) or (red shl 16) or (green shl 8) or blue
                }
            }
        }
        return PixelImage(targetWidth, targetHeight, targetPixels)
    }

    private fun ceilDiv(value: Long, divisor: Int): Int =
        ((value + divisor - 1) / divisor).toInt()
}
