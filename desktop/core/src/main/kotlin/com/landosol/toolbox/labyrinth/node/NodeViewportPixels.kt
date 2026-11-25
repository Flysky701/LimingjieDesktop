package com.landosol.toolbox.labyrinth.node

import com.landosol.toolbox.vision.PixelImage
import kotlin.math.abs
import kotlin.math.ceil

enum class NodeViewportMotion { MOVED, UNCHANGED, UNKNOWN }

/** Small grayscale sample of the map interior, independent of successful node recognition. */
class NodeViewportPixels private constructor(
    private val width: Int,
    private val height: Int,
    private val values: IntArray,
) {
    fun motionFrom(source: NodeViewportPixels): NodeViewportMotion {
        if (width != source.width || height != source.height) return NodeViewportMotion.UNKNOWN
        val differences = values.indices.map { abs(values[it] - source.values[it]) }
        val sourceTexture = values.indices.filter { it % COLS != 0 }
            .map { abs(source.values[it] - source.values[it - 1]) }.average()
        if (sourceTexture < 6.0) return NodeViewportMotion.UNKNOWN
        // Tolerate minor rendering noise and localized node/background animations.
        val unchanged = differences.count { it <= 12 } >= values.size * 0.90 &&
            differences.average() <= 8.0
        val zeroError = differences.average()
        val minShift = ceil(labyrinthReferenceColumnPitch(height) * 0.16 / (width * 0.75 / COLS)).toInt().coerceAtLeast(2)
        // The segmented map scroll swipes 40% of the frame width and the recovery nudge 12%, so
        // the shift search must reach past the larger gesture or a real full-width scroll would
        // never be confirmed once no node geometry is available.
        val maxShift = ceil(width * MAX_SWIPE_WIDTH_RATIO / (width * 0.75 / COLS)).toInt().coerceIn(minShift, COLS - 2)
        for (shift in -maxShift..maxShift) {
            if (abs(shift) < minShift) continue
            var goodRows = 0
            var totalError = 0.0
            for (y in 0 until ROWS) {
                var error = 0.0
                var samples = 0
                var texture = 0.0
                for (x in 1 until COLS) {
                    val oldX = x - shift
                    if (oldX !in 1 until COLS) continue
                    error += abs(values[y * COLS + x] - source.values[y * COLS + oldX])
                    texture += abs(source.values[y * COLS + oldX] - source.values[y * COLS + oldX - 1])
                    samples++
                }
                if (samples < MIN_OVERLAP_SAMPLES) {
                    error = Double.MAX_VALUE
                    samples = 1
                }
                error /= samples
                totalError += error
                // A real translation rarely lands on a whole sample cell, so a row is also good
                // when the shifted comparison explains most of that row's unshifted change.
                var rowZeroError = 0.0
                for (x in 0 until COLS) rowZeroError += differences[y * COLS + x]
                rowZeroError /= COLS
                val rowAligned = error < 12 || (rowZeroError > 20 && error <= rowZeroError * 0.40)
                if (rowAligned && texture / samples >= 10) goodRows++
            }
            val shiftedError = totalError / ROWS
            if (goodRows >= ROWS * 0.70 && shiftedError < zeroError * 0.60 && zeroError - shiftedError > 5) {
                return NodeViewportMotion.MOVED
            }
        }
        return if (unchanged) NodeViewportMotion.UNCHANGED else NodeViewportMotion.UNKNOWN
    }

    companion object {
        private const val COLS = 64
        private const val ROWS = 24
        /** Largest horizontal gesture the map scroller issues, as a fraction of frame width. */
        private const val MAX_SWIPE_WIDTH_RATIO = 0.44
        private const val MIN_OVERLAP_SAMPLES = 8

        fun sample(frame: PixelImage): NodeViewportPixels {
            val pixels = IntArray(COLS * ROWS) { index ->
                val x = (frame.width * (0.20 + 0.75 * (index % COLS) / COLS)).toInt()
                val y = (frame.height * (0.18 + 0.62 * (index / COLS) / ROWS)).toInt()
                val color = frame[x, y]
                ((color ushr 16 and 255) * 299 + (color ushr 8 and 255) * 587 + (color and 255) * 114) / 1000
            }
            return NodeViewportPixels(frame.width, frame.height, pixels)
        }
    }
}
