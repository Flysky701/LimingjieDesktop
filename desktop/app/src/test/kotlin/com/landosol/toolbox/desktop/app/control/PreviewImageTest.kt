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
            val image = decode(encodePreviewPng(frame))
            assertEquals(width / 2, image.width)
            assertEquals(height / 2, image.height)
            assertEquals(0xffcc2200.toInt(), image.getRGB(image.width / 2, image.height / 4))
            assertEquals(0xff0044cc.toInt(), image.getRGB(image.width / 2, image.height * 3 / 4))
            assertEquals(width, frame.width)
            assertEquals(height, frame.height)
            assertEquals(123L, frame.timestampMillis)
            assertSame(pixels, frame.pixels)
            assertArrayEquals(original, frame.pixels)
        }
    }

    @Test fun smallPreviewIsNotEnlargedOrRecolored() {
        val pixels = intArrayOf(0xff112233.toInt(), 0xff445566.toInt(), 0xff778899.toInt(), 0xffaabbcc.toInt())
        val frame = CapturePixels(2, 2, pixels, 456L)
        val image = decode(encodePreviewPng(frame))
        assertEquals(2, image.width)
        assertEquals(2, image.height)
        assertArrayEquals(pixels, image.getRGB(0, 0, 2, 2, null, 0, 2))
    }

    private fun decode(png: String) = checkNotNull(ImageIO.read(Base64.getDecoder().decode(png).inputStream()))
}
