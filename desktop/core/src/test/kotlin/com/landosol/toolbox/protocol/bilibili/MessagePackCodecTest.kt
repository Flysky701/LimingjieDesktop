package com.landosol.toolbox.protocol.bilibili

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class MessagePackCodecTest {
    private val codec = MessagePackCodec()

    @Test
    fun `round trips protocol value types`() {
        val encoded = codec.encode(
            linkedMapOf(
                "nil" to null,
                "flag" to true,
                "positive" to 70_000L,
                "negative" to -40L,
                "text" to "兰德索尔",
                "list" to listOf(1L, "two", false),
                "bytes" to byteArrayOf(1, 2, 3),
            ),
        )

        val decoded = codec.decode(encoded) as Map<*, *>

        assertEquals(null, decoded["nil"])
        assertEquals(true, decoded["flag"])
        assertEquals(70_000L, decoded["positive"])
        assertEquals(-40L, decoded["negative"])
        assertEquals("兰德索尔", decoded["text"])
        assertEquals(listOf(1L, "two", false), decoded["list"])
        assertArrayEquals(byteArrayOf(1, 2, 3), decoded["bytes"] as ByteArray)
    }
}
