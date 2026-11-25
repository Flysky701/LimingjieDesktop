package com.landosol.toolbox.labyrinth.vision

import com.landosol.toolbox.vision.PixelImage
import kotlin.math.abs
import kotlin.math.roundToInt

/** The fixed member strip has white gaps and square card borders, unlike the scrolling roster. */
internal class LabyrinthCurrentMemberStripDetector {
    fun refine(frame: PixelImage, expected: List<EntryPixelRect>): List<EntryPixelRect> {
        if (expected.isEmpty()) return expected
        val size = expected.first().width
        val distance = maxOf(2, (size * 0.18).roundToInt())
        val edgeOffset = maxOf(2, (size * 0.02).roundToInt())
        fun evidence(outX: Int, outY: Int, inX: Int, inY: Int): Double {
            if (outX !in 0 until frame.width || inX !in 0 until frame.width ||
                outY !in 0 until frame.height || inY !in 0 until frame.height) return 0.0
            fun light(color: Int) = minOf(color ushr 16 and 255, color ushr 8 and 255, color and 255)
            val outside = light(frame[outX, outY])
            return if (outside >= 215) maxOf(0, outside - light(frame[inX, inY])).toDouble() else 0.0
        }

        // Use the bottom rim: power labels, and diagnostic text above a card, obscure its top.
        val bottomOffset = (-distance..distance).maxByOrNull { offset ->
            expected.map { rect ->
                val bottom = rect.top + rect.height + offset
                (1..6).map { sample ->
                    val x = rect.left + (rect.width * (0.06 + sample * 0.12)).roundToInt()
                    evidence(x, bottom + edgeOffset, x, bottom - edgeOffset)
                }.average()
            }.sortedDescending().take(2).average() - abs(offset) * 0.10
        } ?: 0

        val refined = expected.map { rect ->
            var bestScore = 25.0
            var best = rect
            val widthTolerance = maxOf(2, (rect.width * 0.05).roundToInt())
            val bottom = rect.top + rect.height + bottomOffset
            for (width in rect.width - widthTolerance..rect.width + widthTolerance) {
                for (left in rect.left - distance..rect.left + distance) {
                    val top = bottom - width
                    if (left < 0 || top < 0 || left + width > frame.width || bottom > frame.height) continue
                    var leftScore = 0.0
                    var rightScore = 0.0
                    for (sample in 0..6) {
                        val y = top + (width * (0.25 + sample / 12.0)).roundToInt()
                        leftScore += evidence(left - edgeOffset, y, left + edgeOffset, y)
                        rightScore += evidence(left + width + edgeOffset, y, left + width - edgeOffset, y)
                    }
                    // Both borders must agree; one strong hair/face edge is insufficient.
                    val score = minOf(leftScore, rightScore) / 7 -
                        abs(width - rect.width) * 0.15 - abs(left - rect.left) * 0.04
                    if (score > bestScore) {
                        bestScore = score
                        best = EntryPixelRect(left, top, width, width)
                    }
                }
            }
            best
        }
        // Never collapse gaps or make two slots share the same portrait.
        if (refined.zipWithNext().any { (left, right) -> left.left + left.width > right.left }) return expected
        return refined
    }
}
