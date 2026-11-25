package com.landosol.toolbox.labyrinth.vision

import com.landosol.toolbox.vision.DigitMatchResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AndroidLabyrinthNodeRelicStackResolverTest {
    private val resolver = AndroidLabyrinthNodeRelicStackResolver(submitTextRead = { _, callback -> callback("") })

    @Test
    fun `parses one visible stack counter`() {
        assertEquals(1, resolver.parseStacks("1"))
        assertEquals(15, resolver.parseStacks("15"))
    }

    @Test
    fun `rejects ambiguous or implausible counter text`() {
        assertNull(resolver.parseStacks("1 3"))
        assertNull(resolver.parseStacks("99"))
        assertNull(resolver.parseStacks(""))
        assertNull(resolver.parseStacks("1\n3"))
        assertNull(resolver.parseStacks("100"))
    }

    @Test
    fun `maps live badge hues to relic marks`() {
        assertEquals("强化", resolver.classifyHue(16.0))
        assertEquals("会心", resolver.classifyHue(50.0))
        assertEquals("守备", resolver.classifyHue(112.0))
        assertEquals("加速", resolver.classifyHue(200.0))
        assertEquals("弱体", resolver.classifyHue(285.0))
    }

    @Test
    fun `accepts only separated structural digit matches`() {
        assertEquals(2, resolver.acceptTemplateStack(match(digit = 2, score = 0.496, margin = 0.293)))
        assertEquals(1, resolver.acceptTemplateStack(match(digit = 1, score = 0.4375, margin = 0.108)))
        assertNull(resolver.acceptTemplateStack(match(digit = 1, score = 0.399, margin = 0.20)))
        assertNull(resolver.acceptTemplateStack(match(digit = 1, score = 0.60, margin = 0.079)))
        assertNull(resolver.acceptTemplateStack(match(digit = 4, score = 0.80, margin = 0.30)))
    }

    @Test
    fun `conflicting single digit evidence waits but preserves larger OCR values`() {
        assertNull(resolver.resolveStackValue(ocr = 2, template = 1))
        assertEquals(1, resolver.resolveStackValue(ocr = null, template = 1))
        assertEquals(4, resolver.resolveStackValue(ocr = 4, template = 1))
        assertEquals(12, resolver.resolveStackValue(ocr = 12, template = 1))
        assertNull(resolver.resolveStackValue(ocr = null, template = null))
    }

    private fun match(digit: Int, score: Double, margin: Double) = DigitMatchResult(
        digit = digit,
        score = score,
        confidence = 0.0,
        margin = margin,
        secondDigit = (digit + 1) % 10,
        secondScore = score - margin,
        thirdDigit = (digit + 2) % 10,
        thirdScore = score - margin - 0.01,
    )
}
