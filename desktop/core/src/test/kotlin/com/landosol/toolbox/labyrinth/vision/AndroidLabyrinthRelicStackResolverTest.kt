package com.landosol.toolbox.labyrinth.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AndroidLabyrinthRelicStackResolverTest {
    private val resolver = AndroidLabyrinthRelicStackResolver(submitTextRead = { _, callback -> callback("") })

    @Test
    fun `reads explicit current stack text`() {
        assertEquals(0, resolver.parseCurrentStacks("（当前：0）"))
        assertEquals(12, resolver.parseCurrentStacks("当前: 12"))
    }

    @Test
    fun `accepts a single surviving number inside narrow roi`() {
        assertEquals(4, resolver.parseCurrentStacks("前：4"))
    }

    @Test
    fun `rejects ambiguous or out of range numeric text`() {
        assertNull(resolver.parseCurrentStacks("1 4"))
        assertNull(resolver.parseCurrentStacks("当前：99"))
        assertNull(resolver.parseCurrentStacks("当前：100"))
        assertNull(resolver.parseCurrentStacks("当前：-1"))
        assertNull(resolver.parseCurrentStacks("【守备+1】当前：4"))
    }
}
