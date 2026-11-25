package com.landosol.toolbox.vision

import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.jetbrains.skia.Codec
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Data
import org.jetbrains.skia.ImageInfo

object NativeBitmapCodec {
    class Options {
        var inJustDecodeBounds = false
        var inSampleSize = 1
        var outWidth = -1
        var outHeight = -1
    }

    fun decodeStream(input: InputStream, options: Options? = null): NativeBitmap? = try {
        Data.makeFromBytes(input.readBytes()).use { encoded ->
            Codec.makeFromData(encoded).use { codec ->
                options?.outWidth = codec.width
                options?.outHeight = codec.height
                if (options?.inJustDecodeBounds == true) null else decodePixels(codec, options?.inSampleSize ?: 1)
            }
        }
    } catch (_: IOException) {
        invalidImage(options)
    } catch (_: IllegalArgumentException) {
        invalidImage(options)
    }

    private fun decodePixels(codec: Codec, sampleSize: Int): NativeBitmap {
        // Decode directly to straight alpha so translucent colors never pass through a premultiplied image.
        val width = codec.width
        val height = codec.height
        val info = ImageInfo(width, height, ColorType.BGRA_8888, ColorAlphaType.UNPREMUL)
        val pixels = org.jetbrains.skia.Bitmap().use { decoded ->
            check(decoded.allocPixels(info)) { "Image pixels could not be allocated" }
            codec.readPixels(decoded)
            val bytes = checkNotNull(decoded.readPixels(info, width * 4, 0, 0))
            val argb = IntArray(width * height)
            ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer().get(argb)
            argb
        }
        val full = NativeBitmap.fromOwnedPixels(pixels, width, height)
        val sample = Integer.highestOneBit(sampleSize.coerceAtLeast(1))
        if (sample == 1) return full
        val scaled = NativeBitmap.createScaled(full, (width / sample).coerceAtLeast(1), (height / sample).coerceAtLeast(1), false)
        if (scaled !== full) full.recycle()
        return scaled
    }

    private fun invalidImage(options: Options?): NativeBitmap? {
        options?.outWidth = -1
        options?.outHeight = -1
        return null
    }

    fun decodeByteArray(bytes: ByteArray, offset: Int, length: Int): NativeBitmap? =
        decodeStream(ByteArrayInputStream(bytes, offset, length))

    fun decodeFile(path: String, options: Options? = null): NativeBitmap? = try {
        File(path).inputStream().use { decodeStream(it, options) }
    } catch (_: IOException) {
        invalidImage(options)
    }
}
