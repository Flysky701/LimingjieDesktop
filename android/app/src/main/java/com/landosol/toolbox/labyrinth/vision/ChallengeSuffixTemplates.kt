package com.landosol.toolbox.labyrinth.vision

import com.landosol.toolbox.clanbattle.recognition.PixelImage
import java.util.IdentityHashMap
import kotlin.math.roundToInt

/** Keep the small difficulty glyphs and the observed ROI on the same pixel scale. */
internal class ChallengeSuffixTemplates {
    private val cache = IdentityHashMap<PixelImage, MutableMap<Pair<Int, Int>, PixelImage>>()

    fun forRect(template: PixelImage, rect: EntryPixelRect): PixelImage {
        // Preserve native/upscaled recognition. At 720p, comparing 1080p gradients against
        // floor-mapped frame pixels loses enough glyph detail to reject a clear normal title.
        if (rect.width >= template.width || rect.height >= template.height) return template
        return cache.getOrPut(template) { mutableMapOf() }.getOrPut(rect.width to rect.height) {
            downsample(template, rect.width, rect.height)
        }
    }

    private fun downsample(source: PixelImage, width: Int, height: Int): PixelImage {
        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            val sy = ((y + 0.5) * source.height / height - 0.5).coerceIn(0.0, source.height - 1.0)
            val y0 = sy.toInt()
            val y1 = (y0 + 1).coerceAtMost(source.height - 1)
            val fy = sy - y0
            for (x in 0 until width) {
                val sx = ((x + 0.5) * source.width / width - 0.5).coerceIn(0.0, source.width - 1.0)
                val x0 = sx.toInt()
                val x1 = (x0 + 1).coerceAtMost(source.width - 1)
                val fx = sx - x0
                var color = 0
                for (shift in intArrayOf(0, 8, 16, 24)) {
                    fun channel(x: Int, y: Int) = (source[x, y] ushr shift and 255).toDouble()
                    val top = channel(x0, y0) * (1 - fx) + channel(x1, y0) * fx
                    val bottom = channel(x0, y1) * (1 - fx) + channel(x1, y1) * fx
                    color = color or ((top * (1 - fy) + bottom * fy).roundToInt() shl shift)
                }
                pixels[y * width + x] = color
            }
        }
        return PixelImage(width, height, pixels)
    }
}
