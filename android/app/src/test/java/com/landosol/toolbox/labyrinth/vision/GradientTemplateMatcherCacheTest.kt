package com.landosol.toolbox.labyrinth.vision

import com.landosol.toolbox.clanbattle.recognition.PixelImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GradientTemplateMatcherCacheTest {
    @Test
    fun `borrowed snapshots survive the owner preparing another frame of the same size`() {
        fun frame(seed: Int) = PixelImage(64, 48, IntArray(64 * 48) { i ->
            0xff000000.toInt() or ((seed * i * 7919 + 17) and 0xffffff)
        })
        val first = frame(7)
        val second = frame(19)
        val rect = EntryPixelRect(5, 3, 22, 18)
        val template = first.crop(rect.left, rect.top, rect.width, rect.height)
        val owner = GradientTemplateMatcher()
        val borrower = GradientTemplateMatcher()
        owner.prepareFrame(first)
        borrower.sharePreparedFrame(owner)
        val expected = owner.score(first, rect, template)
        first.pixels.fill(0)
        owner.clearPreparedFrame()
        owner.prepareFrame(second)
        assertEquals(expected, borrower.score(first, rect, template), 0.0)
        borrower.clearPreparedFrame()
        assertEquals(0.0, borrower.score(first, rect, template), 0.0)
    }

    @Test
    fun `prepared and shared features preserve scores across scale and frame changes`() {
        fun pattern(seed: Int) = PixelImage(96, 72, IntArray(96 * 72) { index ->
            0xff000000.toInt() or ((index * 7919 + seed * 104729) and 0xffffff)
        })
        val frame = pattern(1)
        val changed = pattern(2)
        val template = PixelImage(24, 20, IntArray(24 * 20) { index ->
            frame[5 + index % 24, 7 + index / 24]
        })
        val baseline = GradientTemplateMatcher(maxSamples = 240)
        val prepared = GradientTemplateMatcher(maxSamples = 240)
        val shared = GradientTemplateMatcher(maxSamples = 240)
        prepared.prepareFrame(frame)
        shared.sharePreparedFrame(prepared)
        val rectangles = listOf(
            EntryPixelRect(5, 7, 24, 20),
            EntryPixelRect(0, 0, 96, 72),
            EntryPixelRect(60, 40, 36, 32),
            EntryPixelRect(0, 0, 3, 3),
        )
        for (image in listOf(frame, changed)) {
            for (rect in rectangles) {
                val expected = baseline.score(image, rect, template)
                assertEquals(expected, prepared.score(image, rect, template), 1e-12)
                assertEquals(expected, shared.score(image, rect, template), 1e-12)
            }
        }
        prepared.clearPreparedFrame()
        assertEquals(baseline.score(frame, rectangles[0], template),
            prepared.score(frame, rectangles[0], template), 1e-12)
    }

    @Test
    fun `reused feature storage is fully rewritten between frames`() {
        fun pattern(seed: Int) = PixelImage(96, 72, IntArray(96 * 72) { index ->
            0xff000000.toInt() or ((index * 7919 + seed * 104729) and 0xffffff)
        })
        val first = pattern(1)
        val second = pattern(2)
        val template = PixelImage(24, 20, IntArray(24 * 20) { index -> second[5 + index % 24, 7 + index / 24] })
        val baseline = GradientTemplateMatcher(maxSamples = 240)
        val reused = GradientTemplateMatcher(maxSamples = 240)
        val rectangles = listOf(EntryPixelRect(5, 7, 24, 20), EntryPixelRect(0, 0, 96, 72), EntryPixelRect(60, 40, 36, 32))

        reused.prepareFrame(first)
        assertTrue(reused.hasPreparedFrame(first))
        assertTrue(!reused.hasPreparedFrame(second))
        reused.clearPreparedFrame()
        assertTrue(!reused.hasPreparedFrame(first))

        // Same dimensions: the luminance/gradient arrays are recycled, and must carry nothing of
        // the first frame into the second frame's scores.
        reused.prepareFrame(second)
        for (rect in rectangles) {
            assertEquals(baseline.score(second, rect, template), reused.score(second, rect, template), 1e-12)
        }
        // A different size reallocates transparently.
        val wide = PixelImage(120, 72, IntArray(120 * 72) { index -> 0xff000000.toInt() or ((index * 31) and 0xffffff) })
        reused.prepareFrame(wide)
        assertEquals(baseline.score(wide, EntryPixelRect(10, 10, 40, 30), template),
            reused.score(wide, EntryPixelRect(10, 10, 40, 30), template), 1e-12)
    }
}
