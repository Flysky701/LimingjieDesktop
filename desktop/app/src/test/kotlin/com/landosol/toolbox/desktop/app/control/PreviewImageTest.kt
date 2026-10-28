package com.landosol.toolbox.desktop.app.control

import com.landosol.toolbox.desktop.shared.CapturePixels
import java.util.Base64
import javax.imageio.ImageIO
import org.junit.Assert.*
import org.junit.Test

class PreviewImageTest {
    @Test fun largePreviewsKeepTheirAspectAndSourcePixels() {
        for ((width, height) in listOf(1920 to 1080, 1080 to 1920)) {
            val pixels = IntArray(width * height) { index ->
                if (index / width < height / 2) 0xffcc2200.toInt() else 0xff0044cc.toInt()
            }
            val original = pixels.copyOf()
            val frame = CapturePixels(width, height, pixels, 123L)
            val image = decode(encodePreviewJpeg(frame))
            assertEquals(width / 2, image.width)
            assertEquals(height / 2, image.height)
            assertColorNear(0xffcc2200.toInt(), image.getRGB(image.width / 2, image.height / 4))
            assertColorNear(0xff0044cc.toInt(), image.getRGB(image.width / 2, image.height * 3 / 4))
            assertEquals(width, frame.width)
            assertEquals(height, frame.height)
            assertEquals(123L, frame.timestampMillis)
            assertSame(pixels, frame.pixels)
            assertArrayEquals(original, frame.pixels)
        }
    }

    @Test fun smallPreviewIsNotEnlargedAndSourceRemainsLossless() {
        val pixels = IntArray(16 * 16) { 0xff112233.toInt() }
        val original = pixels.copyOf()
        val frame = CapturePixels(16, 16, pixels, 456L)
        val encoded = encodePreviewJpeg(frame)
        val bytes = Base64.getDecoder().decode(encoded)
        assertEquals(0xff, bytes[0].toInt() and 0xff)
        assertEquals(0xd8, bytes[1].toInt() and 0xff) // JPEG, not the old PNG payload
        val image = decode(encoded)
        assertEquals(16, image.width)
        assertEquals(16, image.height)
        assertColorNear(pixels[0], image.getRGB(8, 8))
        assertArrayEquals(original, frame.pixels)
    }

    private fun assertColorNear(expected: Int, actual: Int) {
        for (shift in listOf(0, 8, 16)) {
            assertTrue(kotlin.math.abs(((expected shr shift) and 255) - ((actual shr shift) and 255)) <= 3)
        }
    }

    private fun decode(png: String) = checkNotNull(ImageIO.read(Base64.getDecoder().decode(png).inputStream()))
}
