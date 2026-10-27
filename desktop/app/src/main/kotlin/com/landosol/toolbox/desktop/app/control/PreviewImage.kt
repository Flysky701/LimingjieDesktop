package com.landosol.toolbox.desktop.app.control

import com.landosol.toolbox.desktop.shared.CapturePixels
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.Base64
import javax.imageio.ImageIO

private const val PREVIEW_MAX_EDGE = 960

/** Scale only the shell's PNG payload; never modify or replace the source capture pixels. */
internal fun encodePreviewPng(frame: CapturePixels): String {
    val source = BufferedImage(frame.width, frame.height, BufferedImage.TYPE_INT_RGB).apply {
        setRGB(0, 0, frame.width, frame.height, frame.pixels, 0, frame.width)
    }
    val longestEdge = maxOf(frame.width, frame.height)
    val image = if (longestEdge <= PREVIEW_MAX_EDGE) source else {
        val width = (frame.width.toLong() * PREVIEW_MAX_EDGE / longestEdge).toInt().coerceAtLeast(1)
        val height = (frame.height.toLong() * PREVIEW_MAX_EDGE / longestEdge).toInt().coerceAtLeast(1)
        BufferedImage(width, height, BufferedImage.TYPE_INT_RGB).apply {
            val graphics = createGraphics()
            try {
                graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
                graphics.drawImage(source, 0, 0, width, height, null)
            } finally {
                graphics.dispose()
            }
        }
    }
    return ByteArrayOutputStream().use { bytes ->
        check(ImageIO.write(image, "png", bytes)) { "无法编码预览画面" }
        Base64.getEncoder().encodeToString(bytes.toByteArray())
    }
}
