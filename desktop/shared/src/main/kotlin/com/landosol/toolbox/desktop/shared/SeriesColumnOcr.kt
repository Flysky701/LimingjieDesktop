package com.landosol.toolbox.desktop.shared

import com.landosol.toolbox.clanbattle.recognition.PixelImage
import com.landosol.toolbox.labyrinth.LabyrinthRelicMark
import kotlin.coroutines.resume
import kotlin.math.roundToInt
import kotlinx.coroutines.suspendCancellableCoroutine

/** The confirmed series panel has a heading over each coloured icon, with outlined count text at its lower right. */
suspend fun readSeriesColumn(image: PixelImage, ocr: OcrPort): String? {
    suspend fun read(region: PixelImage): String? = suspendCancellableCoroutine { next ->
        ocr.submit(region.width, region.height, region.pixels) { next.resume(it) }
    }

    val layout = SeriesColumn(image)
    val rows = mutableListOf<Reading>()
    for (icon in layout.icons) {
        val heading = layout.heading(icon) ?: continue
        // A null callback means the engine rejected or failed the request. Discard the partial snapshot.
        val name = (read(heading.crop(image)) ?: return null).filterNot(Char::isWhitespace)
        val mark = LabyrinthRelicMark.fromLabel(name) ?: continue
        val badge = layout.badge(icon)
        val count = badge?.let { candidate ->
            val text = (read(candidate.area.crop(image)) ?: return null).trim()
            val number = text.toIntOrNull() ?: return@let null
            if (text.length != candidate.glyphCount || number !in 0..30 || number.toString() != text) return@let null
            // Patterned icon backgrounds can turn a 3 or 6 into a 0. Require agreement with isolated ink.
            val isolated = read(candidate.isolated) ?: return null
            number.takeIf { isolated.trim() == text }
        }
        rows += Reading(mark, name, count)
    }
    return rows.groupBy { it.mark }.values.mapNotNull { group ->
        val value = group.map { it.count }.distinct().singleOrNull() ?: return@mapNotNull null
        "${group.first().name}\n$value"
    }.joinToString("\n")
}

private data class Reading(val mark: LabyrinthRelicMark, val name: String, val count: Int?)
private data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width get() = right - left
    val height get() = bottom - top
    fun crop(image: PixelImage) = image.crop(left, top, width, height)
}

private data class Component(val box: Box, val id: Int, val size: Int)
private data class Badge(val area: Box, val glyphCount: Int, val isolated: PixelImage)

private class SeriesColumn(private val image: PixelImage) {
    private val width = image.width
    private val height = image.height
    private val ids = IntArray(image.pixels.size)
    private val colour = mutableListOf<Component>()
    private val ink = mutableListOf<Component>()
    private val white = panelWhite()
    val icons: List<Box>

    init {
        for (i in image.pixels.indices) {
            val pixel = image.pixels[i]
            val lo = low(pixel)
            val hi = high(pixel)
            ids[i] = when {
                hi - lo >= white * .21 && hi >= white * .39 -> -1
                lo >= white * .75 && hi - lo <= white * .16 -> -2
                else -> 0
            }
        }
        labelComponents()
        icons = colour.filter { (box, _, size) ->
            box.width.toDouble() in (width * .27)..(width * .83) && box.height.toDouble() in (box.width * .82)..(box.width * 1.24) &&
                size >= .28 * box.width * box.height && box.left > 0 && box.right < width &&
                box.top > 0 && box.bottom < height
        }.map { it.box }.sortedBy { it.top }
    }

    private fun panelWhite(): Int {
        val levels = IntArray(256)
        var neutral = 0
        for (pixel in image.pixels) {
            val lo = low(pixel)
            if (high(pixel) - lo <= 35) { levels[lo]++; neutral++ }
        }
        var count = 0
        for (level in 255 downTo 0) {
            count += levels[level]
            if (count > neutral / 10) return level.coerceAtLeast(1)
        }
        return 255
    }

    private fun labelComponents() {
        val queue = IntArray(ids.size)
        var id = 0
        for (start in ids.indices) {
            val type = ids[start]
            if (type >= 0) continue
            val current = ++id
            var head = 0
            var tail = 0
            fun visit(index: Int) {
                if (ids[index] == type) { ids[index] = current; queue[tail++] = index }
            }
            visit(start)
            var left = width
            var top = height
            var right = 0
            var bottom = 0
            while (head < tail) {
                val i = queue[head++]
                val x = i % width
                val y = i / width
                left = minOf(left, x); right = maxOf(right, x + 1)
                top = minOf(top, y); bottom = maxOf(bottom, y + 1)
                if (x > 0) visit(i - 1)
                if (x + 1 < width) visit(i + 1)
                if (y > 0) visit(i - width)
                if (y + 1 < height) visit(i + width)
            }
            val component = Component(Box(left, top, right, bottom), current, tail)
            if (type == -1) colour += component else ink += component
        }
    }

    /** Require both a horizontal rule and its small heading marker directly above this icon. */
    fun heading(icon: Box): Box? {
        val h = icon.height
        val rule = colour.singleOrNull { (b, _, size) ->
            b.width >= .55 * width && b.width >= 12 * b.height && size > .42 * b.width * b.height &&
                (icon.top - b.bottom).toDouble() in (.12 * h)..(.63 * h) &&
                b.left <= icon.left + .15 * icon.width && b.right >= icon.right - .15 * icon.width
        }?.box ?: return null
        val marker = colour.singleOrNull { (b) ->
            b.width.toDouble() in (.12 * h)..(.38 * h) && b.height.toDouble() in (.14 * h)..(.38 * h) &&
                (rule.top - b.bottom).toDouble() in (.05 * h)..(.25 * h) &&
                b.left < icon.left + .25 * icon.width && b.right < icon.left + .45 * icon.width
        }?.box ?: return null
        val top = (rule.top - .60 * h).roundToInt()
        if (top < 0) return null
        return Box(marker.right, top, minOf(width, (icon.right + .45 * icon.width).roundToInt()), rule.top)
    }

    fun badge(icon: Box): Badge? {
        val w = icon.width
        val h = icon.height
        val parts = ink.filter { (b, _, size) ->
            b.left >= icon.left + .54 * w && b.right <= icon.right + .25 * w &&
                b.top >= icon.top + .57 * h && b.bottom <= icon.bottom + .10 * h &&
                b.width.toDouble() in (.025 * w)..(.53 * w) && b.height.toDouble() in (.04 * h)..(.40 * h) && size >= .002 * w * h
        }.sortedBy { it.box.left }
        if (parts.isEmpty()) return null
        val b = Box(parts.first().box.left, parts.minOf { it.box.top }, parts.maxOf { it.box.right }, parts.maxOf { it.box.bottom })
        if (b.height.toDouble() !in (.12 * h)..(.38 * h) || b.width.toDouble() !in (.045 * w)..(.55 * w) ||
            b.right < icon.right - .075 * w || b.bottom < icon.bottom - .155 * h) return null
        val glyphCount = countGlyphs(parts, b.height) ?: return null
        if (!hasOutline(parts, b, h)) return null
        val padX = (.10 * w).roundToInt()
        val padY = (.09 * h).roundToInt()
        val crop = Box(b.left - padX, b.top - padY, b.right + padX, b.bottom + padY)
        if (crop.left <= 0 || crop.top <= 0 || crop.right >= width || crop.bottom >= height) return null
        // Bright background can show through the hollow of an outlined glyph.
        val chosen = parts.filterNot { inner ->
            parts.any { outer -> outer.box.left < inner.box.left && outer.box.right > inner.box.right &&
                outer.box.top < inner.box.top && outer.box.bottom > inner.box.bottom }
        }.map { it.id }.toSet()
        val isolated = IntArray(crop.width * crop.height) { index ->
            val source = (crop.top + index / crop.width) * width + crop.left + index % crop.width
            if (ids[source] in chosen) 0xff171717.toInt() else 0xffeeeeee.toInt()
        }
        return Badge(crop, glyphCount, PixelImage(crop.width, crop.height, isolated))
    }

    /** Merge disconnected strokes with overlapping x spans. Reject widely separated numbers and broad icon shapes. */
    private fun countGlyphs(parts: List<Component>, height: Int): Int? {
        var start = parts.first().box.left
        var end = start
        var count = 1
        for ((b) in parts) {
            if (b.left > end) {
                if (end - start > .84 * height || b.left - end > .30 * height || ++count > 2) return null
                start = b.left
            }
            end = maxOf(end, b.right)
        }
        return count.takeIf { end - start <= .84 * height }
    }

    private fun hasOutline(parts: List<Component>, box: Box, iconHeight: Int): Boolean {
        val selected = parts.map { it.id }.toSet()
        val reach = maxOf(2, (.044 * iconHeight).roundToInt())
        var boundary = 0
        var dark = 0
        var bright = 0
        var pixels = 0
        for (y in box.top until box.bottom) {
            var first = box.right
            var last = box.left - 1
            for (x in box.left until box.right) {
                val index = y * width + x
                if (ids[index] !in selected) continue
                first = minOf(first, x); last = x
                pixels++
                val color = image.pixels[index]
                if (low(color) >= .84 * white && high(color) - low(color) <= .14 * white) bright++
            }
            if (first > last) continue
            boundary += 2
            fun darkAt(x: Int): Boolean {
                val color = image.pixels[y * width + x]
                return high(color) < white * .55 && high(color) - low(color) < white * .25
            }
            if ((maxOf(0, first - reach) until first).any(::darkAt)) dark++
            if ((last + 1 until minOf(width, last + 1 + reach)).any(::darkAt)) dark++
        }
        return boundary > 0 && dark >= .78 * boundary && bright >= .42 * pixels
    }
}

private fun low(c: Int) = minOf(c ushr 16 and 255, c ushr 8 and 255, c and 255)
private fun high(c: Int) = maxOf(c ushr 16 and 255, c ushr 8 and 255, c and 255)
