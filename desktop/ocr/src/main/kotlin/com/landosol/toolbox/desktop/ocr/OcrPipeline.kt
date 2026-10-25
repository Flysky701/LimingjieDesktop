package com.landosol.toolbox.desktop.ocr

import java.awt.RenderingHints
import java.awt.image.BufferedImage
import kotlin.math.*

/** Image and model operations are internal so production and deterministic tests use one pipeline. */
internal data class OcrImage(val width: Int, val height: Int, val pixels: IntArray) {
    init { require(width > 0 && height > 0 && width.toLong() * height == pixels.size.toLong()) }

    fun resize(targetWidth: Int, targetHeight: Int): OcrImage {
        if (width == targetWidth && height == targetHeight) return this
        val source = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        source.setRGB(0, 0, width, height, pixels, 0, width)
        val target = BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_RGB)
        target.createGraphics().useGraphics { g ->
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            g.drawImage(source, 0, 0, targetWidth, targetHeight, null)
        }
        return OcrImage(targetWidth, targetHeight, target.getRGB(0, 0, targetWidth, targetHeight, null, 0, targetWidth))
    }

    fun crop(box: TextBox): OcrImage {
        val copy = IntArray(box.width * box.height)
        repeat(box.height) { y -> pixels.copyInto(copy, y * box.width, (box.top + y) * width + box.left, (box.top + y) * width + box.right) }
        return OcrImage(box.width, box.height, copy)
    }
}

private inline fun java.awt.Graphics2D.useGraphics(block: (java.awt.Graphics2D) -> Unit) = try { block(this) } finally { dispose() }

internal data class TextBox(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width get() = right - left
    val height get() = bottom - top
}

internal data class TensorImage(val width: Int, val height: Int, val values: FloatArray)
internal data class RecognizedText(val box: TextBox, val text: String, val confidence: Float)

internal object OcrPreprocessing {
    fun detection(image: OcrImage): TensorImage {
        val minScale = max(1.0, 32.0 / min(image.width, image.height))
        val maxScale = min(1.0, 960.0 / (max(image.width, image.height) * minScale))
        val ratio = minScale * maxScale
        val width = max(32, (image.width * ratio / 32.0).roundToInt() * 32)
        val height = max(32, (image.height * ratio / 32.0).roundToInt() * 32)
        return normalized(image.resize(width, height), floatArrayOf(.485f, .456f, .406f), floatArrayOf(.229f, .224f, .225f))
    }

    fun recognition(image: OcrImage): TensorImage {
        val contentWidth = ceil(48.0 * image.width / image.height).toInt().coerceIn(1, 1280)
        // PaddleOCR uses rec_image_shape=(3,48,320): short lines are padded on the right,
        // rather than presenting the recognizer with an arbitrarily narrow sequence.
        val width = max(320, contentWidth)
        // The recognizer expects height 48; extremely wide text is downscaled to fit then padded.
        val height = if (48.0 * image.width / image.height > 1280) max(1, (image.height * 1280.0 / image.width).roundToInt()) else 48
        val normalized = normalized(image.resize(contentWidth, height), floatArrayOf(.5f, .5f, .5f), floatArrayOf(.5f, .5f, .5f))
        if (height == 48 && contentWidth == width) return normalized
        val values = FloatArray(3 * 48 * width)
        repeat(3) { c -> repeat(height) { y ->
            normalized.values.copyInto(values, c * 48 * width + y * width,
                c * contentWidth * height + y * contentWidth, c * contentWidth * height + (y + 1) * contentWidth)
        } }
        return TensorImage(width, 48, values)
    }

    private fun normalized(image: OcrImage, mean: FloatArray, std: FloatArray): TensorImage {
        val size = image.width * image.height
        val values = FloatArray(size * 3)
        image.pixels.forEachIndexed { i, pixel ->
            repeat(3) { channel -> values[channel * size + i] = (((pixel ushr (channel * 8)) and 255) / 255f - mean[channel]) / std[channel] }
        }
        return TensorImage(image.width, image.height, values)
    }
}

/** DB component boxes use the SPEC-20 axis-aligned approximation; no recognition policy is changed. */
internal object OcrPostprocessing {
    fun boxes(probabilities: FloatArray, width: Int, height: Int, originalWidth: Int, originalHeight: Int): List<TextBox> {
        require(probabilities.size == width * height)
        val visited = BooleanArray(probabilities.size)
        val queue = IntArray(probabilities.size)
        val result = mutableListOf<TextBox>()
        for (start in probabilities.indices) {
            if (visited[start] || probabilities[start] <= .3f) continue
            var head = 0
            var tail = 1
            queue[0] = start
            visited[start] = true
            var minX = start % width
            var maxX = minX
            var minY = start / width
            var maxY = minY
            while (head < tail) {
                val index = queue[head++]
                val x = index % width
                val y = index / width
                minX = min(minX, x); maxX = max(maxX, x)
                minY = min(minY, y); maxY = max(maxY, y)
                for (dy in -1..1) for (dx in -1..1) {
                    if (dx == 0 && dy == 0) continue
                    val nx = x + dx
                    val ny = y + dy
                    if (nx !in 0 until width || ny !in 0 until height) continue
                    val next = ny * width + nx
                    if (!visited[next] && probabilities[next] > .3f) {
                        visited[next] = true
                        queue[tail++] = next
                    }
                }
            }
            val boxWidth = maxX - minX + 1
            val boxHeight = maxY - minY + 1
            var score = 0.0
            for (y in minY..maxY) for (x in minX..maxX) score += probabilities[y * width + x]
            if (score / (boxWidth * boxHeight) < .6) continue
            val offset = boxWidth.toDouble() * boxHeight * 1.5 / (2 * (boxWidth + boxHeight))
            val left = floor((minX - offset) * originalWidth / width).toInt().coerceIn(0, originalWidth)
            val top = floor((minY - offset) * originalHeight / height).toInt().coerceIn(0, originalHeight)
            val right = ceil((maxX + 1 + offset) * originalWidth / width).toInt().coerceIn(0, originalWidth)
            val bottom = ceil((maxY + 1 + offset) * originalHeight / height).toInt().coerceIn(0, originalHeight)
            if (right - left >= 3 && bottom - top >= 3) result += TextBox(left, top, right, bottom)
        }
        return result
    }

    fun ctc(logits: FloatArray, steps: Int, classes: Int, dictionary: List<String>): Pair<String, Float> {
        require(logits.size == steps * classes && dictionary.size == classes)
        val text = StringBuilder()
        var prior = -1
        var confidence = 0f
        var retained = 0
        repeat(steps) { step ->
            val offset = step * classes
            var best = 0
            for (candidate in 1 until classes) if (logits[offset + candidate] > logits[offset + best]) best = candidate
            if (best != 0 && best != prior) {
                text.append(dictionary[best]); confidence += logits[offset + best]; retained++
            }
            prior = best
        }
        val score = if (retained == 0) 0f else confidence / retained
        return (if (score < .5f) "" else text.toString()) to score
    }

    fun join(items: List<RecognizedText>): String {
        val rows = mutableListOf<MutableList<RecognizedText>>()
        for (item in items.filter { it.text.isNotBlank() && it.confidence >= .5f }.sortedBy { it.box.top + it.box.height / 2.0 }) {
            val row = rows.lastOrNull()
            val anchor = row?.firstOrNull()?.box
            val overlap = if (anchor == null) 0 else min(anchor.bottom, item.box.bottom) - max(anchor.top, item.box.top)
            if (anchor != null && overlap >= item.box.height * .5) row.add(item)
            else rows += mutableListOf(item)
        }
        return rows.joinToString("\n") { row -> row.sortedBy { it.box.left }.joinToString(" ") { it.text } }.trim()
    }
}
