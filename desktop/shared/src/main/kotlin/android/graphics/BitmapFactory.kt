package android.graphics

import java.io.InputStream
import java.io.ByteArrayInputStream
import java.io.File
import javax.imageio.ImageIO

object BitmapFactory {
    class Options {
        var inJustDecodeBounds = false
        var inSampleSize = 1
        var outWidth = -1
        var outHeight = -1
    }
    fun decodeStream(input: InputStream, padding: Any? = null, options: Options? = null): Bitmap? = try {
        val image = ImageIO.read(input)
        if (image == null) { options?.outWidth = -1; options?.outHeight = -1; null }
        else {
            options?.outWidth = image.width; options?.outHeight = image.height
            if (options?.inJustDecodeBounds == true) null
            else {
                val full = Bitmap.createBitmap(image.getRGB(0, 0, image.width, image.height, null, 0, image.width), image.width, image.height, Bitmap.Config.ARGB_8888)
                val requested = options?.inSampleSize?.coerceAtLeast(1) ?: 1
                val sample = Integer.highestOneBit(requested)
                if (sample == 1) full else Bitmap.createScaledBitmap(full, (image.width / sample).coerceAtLeast(1), (image.height / sample).coerceAtLeast(1), false).also { full.recycle() }
            }
        }
    } catch (_: java.io.IOException) { options?.outWidth = -1; options?.outHeight = -1; null }
    fun decodeByteArray(bytes: ByteArray, offset: Int, length: Int): Bitmap? = decodeStream(ByteArrayInputStream(bytes, offset, length))
    fun decodeFile(path: String, options: Options? = null): Bitmap? = try { File(path).inputStream().use { decodeStream(it, null, options) } } catch (_: java.io.IOException) { null }
}
