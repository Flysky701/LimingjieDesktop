package android.graphics

import java.io.InputStream
import java.io.ByteArrayInputStream
import java.io.File
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo

object BitmapFactory {
    class Options {
        var inJustDecodeBounds = false
        var inSampleSize = 1
        var outWidth = -1
        var outHeight = -1
    }
    fun decodeStream(input: InputStream, padding: Any? = null, options: Options? = null): Bitmap? = try {
        Image.makeFromEncoded(input.readBytes()).use { image ->
            options?.outWidth = image.width; options?.outHeight = image.height
            if (options?.inJustDecodeBounds == true) null
            else {
                val info = ImageInfo(image.width, image.height, ColorType.BGRA_8888, ColorAlphaType.UNPREMUL)
                val pixels = org.jetbrains.skia.Bitmap().use { decoded ->
                    check(decoded.allocPixels(info) && image.readPixels(decoded)) { "Image pixels could not be decoded" }
                    val bytes = checkNotNull(decoded.readPixels(info, image.width * 4, 0, 0))
                    IntArray(image.width * image.height) { index ->
                        val offset = index * 4
                        (bytes[offset].toInt() and 255) or ((bytes[offset + 1].toInt() and 255) shl 8) or
                            ((bytes[offset + 2].toInt() and 255) shl 16) or ((bytes[offset + 3].toInt() and 255) shl 24)
                    }
                }
                val full = Bitmap.createBitmap(pixels, image.width, image.height, Bitmap.Config.ARGB_8888)
                val requested = options?.inSampleSize?.coerceAtLeast(1) ?: 1
                val sample = Integer.highestOneBit(requested)
                if (sample == 1) full else Bitmap.createScaledBitmap(full, (image.width / sample).coerceAtLeast(1), (image.height / sample).coerceAtLeast(1), false).also { full.recycle() }
            }
        }
    } catch (_: java.io.IOException) { options?.outWidth = -1; options?.outHeight = -1; null }
      catch (_: IllegalArgumentException) { options?.outWidth = -1; options?.outHeight = -1; null }
    fun decodeByteArray(bytes: ByteArray, offset: Int, length: Int): Bitmap? = decodeStream(ByteArrayInputStream(bytes, offset, length))
    fun decodeFile(path: String, options: Options? = null): Bitmap? = try { File(path).inputStream().use { decodeStream(it, null, options) } } catch (_: java.io.IOException) { null }
}
