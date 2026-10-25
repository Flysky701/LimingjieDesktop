package android.graphics

import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.OutputStream
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam

class Bitmap private constructor(val width: Int, val height: Int, private var pixels: IntArray?) {
    enum class Config { ARGB_8888 }
    enum class CompressFormat { JPEG, PNG }
    val isRecycled: Boolean get() = pixels == null
    val allocationByteCount: Int get() = data().size * 4
    val byteCount: Int get() = allocationByteCount
    fun recycle() { pixels = null }
    private fun data(): IntArray = checkNotNull(pixels) { "Cannot access a recycled bitmap" }
    fun getPixel(x: Int, y: Int): Int { checkRect(x, y, 1, 1); return data()[y * width + x] }
    fun getPixels(destination: IntArray, offset: Int, stride: Int, x: Int, y: Int, width: Int, height: Int) {
        val source = data()
        checkRect(x, y, width, height)
        require(kotlin.math.abs(stride) >= width)
        for (row in 0 until height) source.copyInto(destination, offset + row * stride, (y + row) * this.width + x, (y + row) * this.width + x + width)
    }
    private fun checkRect(x: Int, y: Int, w: Int, h: Int) {
        require(x >= 0 && y >= 0 && w >= 0 && h >= 0 && x.toLong() + w <= width && y.toLong() + h <= height)
    }
    fun toBufferedImage(rgb: Boolean = false): BufferedImage = BufferedImage(width, height, if (rgb) BufferedImage.TYPE_INT_RGB else BufferedImage.TYPE_INT_ARGB).apply {
        setRGB(0, 0, width, height, data(), 0, width)
    }
    fun compress(format: CompressFormat, quality: Int, stream: OutputStream): Boolean {
        require(quality in 0..100)
        val image = toBufferedImage(format == CompressFormat.JPEG)
        if (format == CompressFormat.PNG) return ImageIO.write(image, "png", stream)
        val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
        val output = javax.imageio.stream.MemoryCacheImageOutputStream(stream)
        try {
            writer.output = output
            val parameters = writer.defaultWriteParam.apply { compressionMode = ImageWriteParam.MODE_EXPLICIT; compressionQuality = quality / 100f }
            writer.write(null, javax.imageio.IIOImage(image, null, null), parameters)
            output.flush()
            return true
        } finally { writer.dispose() }
    }
    companion object {
        fun createBitmap(pixels: IntArray, width: Int, height: Int, config: Config): Bitmap {
            require(width > 0 && height > 0 && width.toLong() * height <= pixels.size)
            return Bitmap(width, height, pixels.copyOf(width * height))
        }
        fun createBitmap(width: Int, height: Int, config: Config): Bitmap {
            require(width > 0 && height > 0)
            return createBitmap(IntArray(Math.multiplyExact(width, height)), width, height, config)
        }
        fun createBitmap(source: Bitmap, x: Int, y: Int, width: Int, height: Int): Bitmap {
            source.data(); source.checkRect(x, y, width, height); require(width > 0 && height > 0)
            if (x == 0 && y == 0 && width == source.width && height == source.height) return source
            val data = IntArray(width * height)
            source.getPixels(data, 0, width, x, y, width, height)
            return Bitmap(width, height, data)
        }
        fun createScaledBitmap(source: Bitmap, width: Int, height: Int, filter: Boolean): Bitmap {
            source.data(); require(width > 0 && height > 0)
            if (width == source.width && height == source.height) return source
            val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
            image.createGraphics().let { g ->
                try {
                    g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, if (filter) RenderingHints.VALUE_INTERPOLATION_BILINEAR else RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR)
                    g.drawImage(source.toBufferedImage(), 0, 0, width, height, null)
                } finally { g.dispose() }
            }
            return createBitmap(image.getRGB(0, 0, width, height, null, 0, width), width, height, Config.ARGB_8888)
        }
    }
}
