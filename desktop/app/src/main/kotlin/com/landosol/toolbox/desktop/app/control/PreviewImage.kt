package com.landosol.toolbox.desktop.app.control

import com.landosol.toolbox.desktop.shared.CapturePixels
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.awt.image.DataBufferInt
import java.awt.image.DirectColorModel
import java.awt.image.Raster
import java.io.ByteArrayOutputStream
import java.util.Base64
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import javax.imageio.IIOImage
import javax.imageio.stream.MemoryCacheImageOutputStream

private const val PREVIEW_MAX_EDGE = 960

/** Scale only the shell's JPEG payload; never modify or replace the source capture pixels. */
internal fun encodePreviewJpeg(frame: CapturePixels): String {
    // Encoding only reads this immutable preview snapshot; avoid another full-screen copy.
    val masks = intArrayOf(0xff0000, 0xff00, 0xff)
    val raster = Raster.createPackedRaster(DataBufferInt(frame.pixels, frame.pixels.size),
        frame.width, frame.height, frame.width, masks, null)
    val source = BufferedImage(DirectColorModel(24, masks[0], masks[1], masks[2]), raster, false, null)
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
        val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
        try {
            MemoryCacheImageOutputStream(bytes).use { output ->
                writer.output = output
                val options = writer.defaultWriteParam.apply {
                    compressionMode = ImageWriteParam.MODE_EXPLICIT
                    compressionQuality = 0.8f
                }
                writer.write(null, IIOImage(image, null, null), options)
            }
        } finally { writer.dispose() }
        Base64.getEncoder().encodeToString(bytes.toByteArray())
    }
}
