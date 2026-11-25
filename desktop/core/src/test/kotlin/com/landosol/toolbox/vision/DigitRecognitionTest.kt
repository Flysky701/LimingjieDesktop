package com.landosol.toolbox.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DigitRecognitionTest {
    @Test
    fun `normalizer and structural matcher choose identical synthetic digit`() {
        val image = digit("8")
        val templates = DigitTemplates((0..9).associateWith { digit(it.toString()) })

        val result = DigitMatcher(templates).match(image, 0..9)

        assertEquals(8, result.digit)
        assertTrue(result.score > 0.95)
        assertTrue(result.confidence >= 0.0)
    }

    private fun digit(value: String): PixelImage {
        val patterns = mapOf(
            '0' to listOf("111", "101", "101", "101", "111"),
            '1' to listOf("010", "110", "010", "010", "111"),
            '2' to listOf("111", "001", "111", "100", "111"),
            '3' to listOf("111", "001", "111", "001", "111"),
            '4' to listOf("101", "101", "111", "001", "001"),
            '5' to listOf("111", "100", "111", "001", "111"),
            '6' to listOf("111", "100", "111", "101", "111"),
            '7' to listOf("111", "001", "001", "001", "001"),
            '8' to listOf("111", "101", "111", "101", "111"),
            '9' to listOf("111", "101", "111", "001", "111"),
        )
        val rows = patterns.getValue(value.single())
        val pixels = IntArray(3 * 5) { 0xffffffff.toInt() }
        rows.forEachIndexed { y, row -> row.forEachIndexed { x, cell -> if (cell == '1') pixels[y * 3 + x] = 0xff000000.toInt() } }
        return PixelImage(3, 5, pixels)
    }
}
