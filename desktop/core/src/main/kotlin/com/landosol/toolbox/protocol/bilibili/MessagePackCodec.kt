package com.landosol.toolbox.protocol.bilibili

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

class MessagePackCodec {
    fun encode(value: Any?): ByteArray = ByteArrayOutputStream().use { output ->
        Writer(output).write(value)
        output.toByteArray()
    }

    fun decode(bytes: ByteArray): Any? {
        val reader = Reader(bytes)
        return reader.read().also { require(reader.isAtEnd()) { "Trailing MessagePack data" } }
    }

    private class Writer(
        private val output: ByteArrayOutputStream,
    ) {
        fun write(value: Any?) {
            when (value) {
                null -> byte(0xc0)
                false -> byte(0xc2)
                true -> byte(0xc3)
                is Byte -> integer(value.toLong())
                is Short -> integer(value.toLong())
                is Int -> integer(value.toLong())
                is Long -> integer(value)
                is Float -> {
                    byte(0xca)
                    unsigned(value.toRawBits().toLong() and UINT_MASK, 4)
                }
                is Double -> {
                    byte(0xcb)
                    unsigned(value.toRawBits(), 8)
                }
                is String -> string(value)
                is ByteArray -> binary(value)
                is List<*> -> array(value)
                is Array<*> -> array(value.asList())
                is Map<*, *> -> map(value)
                else -> error("Unsupported MessagePack value: ${value::class.java.name}")
            }
        }

        private fun integer(value: Long) {
            when {
                value in 0..0x7f -> byte(value.toInt())
                value in -32..-1 -> byte(value.toInt() and 0xff)
                value in 0..0xff -> {
                    byte(0xcc)
                    unsigned(value, 1)
                }
                value in 0..0xffff -> {
                    byte(0xcd)
                    unsigned(value, 2)
                }
                value in 0..UINT_MASK -> {
                    byte(0xce)
                    unsigned(value, 4)
                }
                value >= 0 -> {
                    byte(0xcf)
                    unsigned(value, 8)
                }
                value >= Byte.MIN_VALUE -> {
                    byte(0xd0)
                    unsigned(value, 1)
                }
                value >= Short.MIN_VALUE -> {
                    byte(0xd1)
                    unsigned(value, 2)
                }
                value >= Int.MIN_VALUE -> {
                    byte(0xd2)
                    unsigned(value, 4)
                }
                else -> {
                    byte(0xd3)
                    unsigned(value, 8)
                }
            }
        }

        private fun string(value: String) {
            val bytes = value.toByteArray(StandardCharsets.UTF_8)
            when {
                bytes.size <= 31 -> byte(0xa0 or bytes.size)
                bytes.size <= 0xff -> {
                    byte(0xd9)
                    unsigned(bytes.size.toLong(), 1)
                }
                bytes.size <= 0xffff -> {
                    byte(0xda)
                    unsigned(bytes.size.toLong(), 2)
                }
                else -> {
                    byte(0xdb)
                    unsigned(bytes.size.toLong(), 4)
                }
            }
            output.write(bytes)
        }

        private fun binary(value: ByteArray) {
            when {
                value.size <= 0xff -> {
                    byte(0xc4)
                    unsigned(value.size.toLong(), 1)
                }
                value.size <= 0xffff -> {
                    byte(0xc5)
                    unsigned(value.size.toLong(), 2)
                }
                else -> {
                    byte(0xc6)
                    unsigned(value.size.toLong(), 4)
                }
            }
            output.write(value)
        }

        private fun array(value: List<*>) {
            when {
                value.size <= 15 -> byte(0x90 or value.size)
                value.size <= 0xffff -> {
                    byte(0xdc)
                    unsigned(value.size.toLong(), 2)
                }
                else -> {
                    byte(0xdd)
                    unsigned(value.size.toLong(), 4)
                }
            }
            value.forEach(::write)
        }

        private fun map(value: Map<*, *>) {
            when {
                value.size <= 15 -> byte(0x80 or value.size)
                value.size <= 0xffff -> {
                    byte(0xde)
                    unsigned(value.size.toLong(), 2)
                }
                else -> {
                    byte(0xdf)
                    unsigned(value.size.toLong(), 4)
                }
            }
            value.forEach { (key, item) ->
                write(key)
                write(item)
            }
        }

        private fun byte(value: Int) {
            output.write(value and 0xff)
        }

        private fun unsigned(value: Long, width: Int) {
            for (shift in (width - 1) * 8 downTo 0 step 8) {
                byte((value ushr shift).toInt())
            }
        }
    }

    private class Reader(
        private val bytes: ByteArray,
    ) {
        private var index = 0

        fun isAtEnd(): Boolean = index == bytes.size

        fun read(): Any? {
            val marker = unsignedByte()
            return when {
                marker <= 0x7f -> marker.toLong()
                marker in 0x80..0x8f -> map(marker and 0x0f)
                marker in 0x90..0x9f -> array(marker and 0x0f)
                marker in 0xa0..0xbf -> string(marker and 0x1f)
                marker >= 0xe0 -> (marker - 0x100).toLong()
                else -> when (marker) {
                    0xc0 -> null
                    0xc2 -> false
                    0xc3 -> true
                    0xc4 -> binary(unsigned(1).toInt())
                    0xc5 -> binary(unsigned(2).toInt())
                    0xc6 -> binary(length(unsigned(4)))
                    0xca -> Float.fromBits(unsigned(4).toInt())
                    0xcb -> Double.fromBits(unsigned(8))
                    0xcc -> unsigned(1)
                    0xcd -> unsigned(2)
                    0xce -> unsigned(4)
                    0xcf -> unsigned(8)
                    0xd0 -> signed(1)
                    0xd1 -> signed(2)
                    0xd2 -> signed(4)
                    0xd3 -> signed(8)
                    0xd9 -> string(unsigned(1).toInt())
                    0xda -> string(unsigned(2).toInt())
                    0xdb -> string(length(unsigned(4)))
                    0xdc -> array(unsigned(2).toInt())
                    0xdd -> array(length(unsigned(4)))
                    0xde -> map(unsigned(2).toInt())
                    0xdf -> map(length(unsigned(4)))
                    else -> error("Unsupported MessagePack marker: 0x${marker.toString(16)}")
                }
            }
        }

        private fun map(size: Int): Map<Any?, Any?> = LinkedHashMap<Any?, Any?>(size).apply {
            repeat(size) { put(read(), read()) }
        }

        private fun array(size: Int): List<Any?> = List(size) { read() }

        private fun string(size: Int): String = String(binary(size), StandardCharsets.UTF_8)

        private fun binary(size: Int): ByteArray {
            require(size >= 0 && index + size <= bytes.size) { "Invalid MessagePack length" }
            return bytes.copyOfRange(index, index + size).also { index += size }
        }

        private fun unsigned(width: Int): Long {
            require(index + width <= bytes.size) { "Unexpected end of MessagePack input" }
            var value = 0L
            repeat(width) { value = (value shl 8) or unsignedByte().toLong() }
            return value
        }

        private fun signed(width: Int): Long {
            val value = unsigned(width)
            if (width == 8) return value
            val signBit = 1L shl (width * 8 - 1)
            return if (value and signBit == 0L) value else value - (1L shl (width * 8))
        }

        private fun unsignedByte(): Int {
            require(index < bytes.size) { "Unexpected end of MessagePack input" }
            return bytes[index++].toInt() and 0xff
        }

        private fun length(value: Long): Int {
            require(value in 0..Int.MAX_VALUE.toLong()) { "MessagePack collection is too large" }
            return value.toInt()
        }
    }

    private companion object {
        const val UINT_MASK = 0xffff_ffffL
    }
}
