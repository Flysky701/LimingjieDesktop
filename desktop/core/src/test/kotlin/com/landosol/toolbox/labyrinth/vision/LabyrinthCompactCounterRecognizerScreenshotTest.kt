package com.landosol.toolbox.labyrinth.vision

import com.landosol.toolbox.vision.PixelImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.InflaterInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class LabyrinthCompactCounterRecognizerScreenshotTest {
    @Test
    fun `replays compact counters from supplied screenshot folder`() {
        val root = generateSequence(File(requireNotNull(System.getProperty("user.dir"))).absoluteFile) { it.parentFile }
            .map { File(it, "Test_Screenshots") }
            .firstOrNull(File::isDirectory)
        if (root == null) {
            assumeTrue("Test_Screenshots folder is available", false)
            return
        }

        EXPECTED.forEach { (name, digits) ->
            val image = decodeRgbaPng(File(root, name))
            digits.forEachIndexed { slot, expected ->
                val rect = requireNotNull(
                    ReferenceFitMapper.map(
                        frameWidth = image.width,
                        frameHeight = image.height,
                        referenceSize = EntryReferenceSize(1920, 1080),
                        referenceRect = EntryReferenceRect(380 + slot * 88, 978, 32, 44),
                    ),
                )
                val match = LabyrinthCompactCounterRecognizer.match(
                    image.crop(rect.left, rect.top, rect.width, rect.height),
                )
                val label = "$name slot ${slot + 1}"
                assertEquals(label, expected, match.digit)
                assertTrue("$label score=${match.score}", match.score >= 0.40)
                assertTrue("$label margin=${match.margin}", match.margin >= 0.08)
            }
        }
    }

    /** Minimal non-interlaced RGBA8 PNG reader for JVM tests where java.desktop is unavailable. */
    private fun decodeRgbaPng(file: File): PixelImage {
        val bytes = file.readBytes()
        require(bytes.copyOfRange(0, PNG_SIGNATURE.size).contentEquals(PNG_SIGNATURE))
        var width = 0
        var height = 0
        val compressed = ByteArrayOutputStream()
        var offset = PNG_SIGNATURE.size
        while (offset + 12 <= bytes.size) {
            val length = readBigEndianInt(bytes, offset)
            val type = String(bytes, offset + 4, 4, Charsets.US_ASCII)
            val dataOffset = offset + 8
            when (type) {
                "IHDR" -> {
                    width = readBigEndianInt(bytes, dataOffset)
                    height = readBigEndianInt(bytes, dataOffset + 4)
                    require(bytes[dataOffset + 8].toInt() == 8) { "Only PNG bit depth 8 is supported" }
                    require(bytes[dataOffset + 9].toInt() == 6) { "Only RGBA PNG is supported" }
                    require(bytes[dataOffset + 12].toInt() == 0) { "Interlaced PNG is unsupported" }
                }
                "IDAT" -> compressed.write(bytes, dataOffset, length)
                "IEND" -> break
            }
            offset = dataOffset + length + 4
        }
        require(width > 0 && height > 0)
        val inflated = InflaterInputStream(ByteArrayInputStream(compressed.toByteArray())).readBytes()
        val bytesPerPixel = 4
        val stride = width * bytesPerPixel
        require(inflated.size == (stride + 1) * height)
        val reconstructed = ByteArray(stride * height)
        var sourceOffset = 0
        repeat(height) { y ->
            val filter = inflated[sourceOffset++].toInt() and 0xff
            repeat(stride) { column ->
                val raw = inflated[sourceOffset++].toInt() and 0xff
                val destination = y * stride + column
                val left = if (column >= bytesPerPixel) reconstructed[destination - bytesPerPixel].toInt() and 0xff else 0
                val up = if (y > 0) reconstructed[destination - stride].toInt() and 0xff else 0
                val upLeft = if (y > 0 && column >= bytesPerPixel) {
                    reconstructed[destination - stride - bytesPerPixel].toInt() and 0xff
                } else {
                    0
                }
                val predictor = when (filter) {
                    0 -> 0
                    1 -> left
                    2 -> up
                    3 -> (left + up) / 2
                    4 -> paeth(left, up, upLeft)
                    else -> error("Unsupported PNG filter $filter")
                }
                reconstructed[destination] = ((raw + predictor) and 0xff).toByte()
            }
        }
        val pixels = IntArray(width * height) { index ->
            val base = index * bytesPerPixel
            val red = reconstructed[base].toInt() and 0xff
            val green = reconstructed[base + 1].toInt() and 0xff
            val blue = reconstructed[base + 2].toInt() and 0xff
            val alpha = reconstructed[base + 3].toInt() and 0xff
            alpha shl 24 or (red shl 16) or (green shl 8) or blue
        }
        return PixelImage(width, height, pixels)
    }

    private fun readBigEndianInt(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xff) shl 24) or
            ((bytes[offset + 1].toInt() and 0xff) shl 16) or
            ((bytes[offset + 2].toInt() and 0xff) shl 8) or
            (bytes[offset + 3].toInt() and 0xff)

    private fun paeth(left: Int, up: Int, upLeft: Int): Int {
        val estimate = left + up - upLeft
        val leftDistance = kotlin.math.abs(estimate - left)
        val upDistance = kotlin.math.abs(estimate - up)
        val upLeftDistance = kotlin.math.abs(estimate - upLeft)
        return when {
            leftDistance <= upDistance && leftDistance <= upLeftDistance -> left
            upDistance <= upLeftDistance -> up
            else -> upLeft
        }
    }

    private companion object {
        val PNG_SIGNATURE = byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10)
        val EXPECTED = mapOf(
            "MuMu-20260820-023258-938.png" to listOf(1),
            "MuMu-20260821-144152-092.png" to listOf(1),
            "MuMu-20260821-233210-884.png" to listOf(2, 1),
            "MuMu-20260821-234605-965.png" to listOf(2, 1),
            "MuMu-20260822-042714-378.png" to listOf(2, 1),
            "MuMu-20260822-042912-251.png" to listOf(2, 1),
            "MuMu-20260822-042930-682.png" to listOf(2, 1),
            "MuMu-20260822-052512-562.png" to listOf(2, 1),
            "MuMu-20260822-052551-001.png" to listOf(2, 1),
            "MuMu-20260822-143514-927.png" to listOf(2, 2),
            "MuMu-20260822-150505-982.png" to listOf(2, 2),
            "MuMu-20260822-153537-137.png" to listOf(2, 2),
            "MuMu-20260822-153701-157.png" to listOf(2, 2),
            "MuMu-20260822-153704-362.png" to listOf(2, 2),
            "MuMu-20260822-182706-965.png" to listOf(2, 2),
            "MuMu-20260823-001443-771.png" to listOf(2, 2, 2),
            "MuMu-20260823-022330-010.png" to listOf(3, 2, 2),
        )
    }
}
