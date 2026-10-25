package com.landosol.toolbox.desktop.ocr

import org.junit.Assert.*
import org.junit.Test

class OcrPipelineTest {
    @Test fun `detection uses planar BGR and documented normalization`() {
        val input = OcrPreprocessing.detection(OcrImage(32, 32, IntArray(1024) { 0xffff0000.toInt() }))
        assertEquals(32, input.width)
        assertEquals(32, input.height)
        assertEquals(-.485f / .229f, input.values[0], .00001f)
        assertEquals(-.456f / .224f, input.values[1024], .00001f)
        assertEquals((1f - .406f) / .225f, input.values[2048], .00001f)
    }

    @Test fun `detection enlarges small crops and bounds large images`() {
        val small = OcrPreprocessing.detection(OcrImage(10, 4, IntArray(40)))
        assertTrue(small.width >= 32 && small.height >= 32)
        assertEquals(0, small.width % 32)
        assertEquals(0, small.height % 32)
        val large = OcrPreprocessing.detection(OcrImage(1920, 1080, IntArray(1920 * 1080)))
        assertEquals(960, large.width)
        assertTrue(large.height <= 960)
    }

    @Test fun `recognition keeps BGR and pads extreme aspect ratios`() {
        val regular = OcrPreprocessing.recognition(OcrImage(96, 48, IntArray(96 * 48) { 0xff0000ff.toInt() }))
        assertEquals(48, regular.height)
        assertEquals(320, regular.width)
        assertEquals(1f, regular.values[0], .00001f)
        assertEquals(-1f, regular.values[320 * 48], .00001f)
        assertEquals(-1f, regular.values[2 * 320 * 48], .00001f)
        assertEquals(0f, regular.values[96], .00001f)
        val wide = OcrPreprocessing.recognition(OcrImage(2000, 10, IntArray(20000) { -1 }))
        assertEquals(1280, wide.width)
        assertEquals(48, wide.height)
        assertEquals(0f, wide.values[47 * 1280], .00001f)
    }

    @Test fun `DB boxes expand confident connected regions and map to original coordinates`() {
        val map = FloatArray(20 * 20)
        for (y in 5..8) for (x in 4..12) map[y * 20 + x] = .9f
        val box = OcrPostprocessing.boxes(map, 20, 20, 40, 40).single()
        // Offset = 9 * 4 * 1.5 / (2 * (9 + 4)) = 2.0769; scale by two, then floor/ceil.
        assertEquals(TextBox(3, 5, 31, 23), box)
    }

    @Test fun `DB rejects low mean regions blank maps and tiny output boxes`() {
        assertTrue(OcrPostprocessing.boxes(FloatArray(64), 8, 8, 8, 8).isEmpty())
        assertTrue(OcrPostprocessing.boxes(FloatArray(64) { .4f }, 8, 8, 8, 8).isEmpty())
        assertTrue(OcrPostprocessing.boxes(FloatArray(64) { .9f }, 8, 8, 2, 2).isEmpty())
        assertEquals(TextBox(0, 0, 8, 8), OcrPostprocessing.boxes(FloatArray(64) { .9f }, 8, 8, 8, 8).single())
    }

    @Test fun `CTC suppresses adjacent duplicates and restores repetition after blank`() {
        val indices = listOf(0, 1, 1, 0, 1, 2, 2, 0)
        val logits = FloatArray(indices.size * 3) { .05f }
        indices.forEachIndexed { i, index -> logits[i * 3 + index] = .9f }
        val (text, confidence) = OcrPostprocessing.ctc(logits, indices.size, 3, listOf("", "甲", "乙"))
        assertEquals("甲甲乙", text)
        assertEquals(.9f, confidence, .00001f)
        assertEquals("", OcrPostprocessing.ctc(floatArrayOf(.1f, .4f, .3f), 1, 3, listOf("", "甲", "乙")).first)
    }

    @Test fun `join sorts rows and columns without character normalization`() {
        val items = listOf(
            RecognizedText(TextBox(100, 0, 150, 20), "乙", .8f),
            RecognizedText(TextBox(0, 40, 50, 60), "龍Ａ", .8f),
            RecognizedText(TextBox(0, 2, 50, 22), "甲", .8f),
            RecognizedText(TextBox(0, 80, 50, 100), "不可信", .4f),
        )
        assertEquals("甲 乙\n龍Ａ", OcrPostprocessing.join(items))
    }

    @Test fun `cropping uses half open pixel bounds`() {
        val image = OcrImage(4, 3, IntArray(12) { it })
        assertArrayEquals(intArrayOf(5, 6, 9, 10), image.crop(TextBox(1, 1, 3, 3)).pixels)
    }
}
