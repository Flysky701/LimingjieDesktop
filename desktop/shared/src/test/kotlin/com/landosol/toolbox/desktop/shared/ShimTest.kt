package com.landosol.toolbox.desktop.shared

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.LruCache
import java.nio.file.Files
import java.io.ByteArrayOutputStream
import org.junit.Assert.*
import org.junit.Test

class ShimTest {
    @Test fun bitmapOwnershipAndRoundTrip() {
        val pixels = intArrayOf(0xff123456.toInt(), 0xffabcdef.toInt(), 0xff00ff00.toInt(), 0xffffffff.toInt())
        val bitmap = Bitmap.createBitmap(pixels, 2, 2, Bitmap.Config.ARGB_8888)
        pixels[0] = 0
        assertEquals(0xff123456.toInt(), bitmap.getPixel(0, 0))
        assertSame(bitmap, Bitmap.createBitmap(bitmap, 0, 0, 2, 2))
        assertSame(bitmap, Bitmap.createScaledBitmap(bitmap, 2, 2, true))
        assertEquals(0xffabcdef.toInt(), Bitmap.createBitmap(bitmap, 1, 0, 1, 1).getPixel(0, 0))
        val bytes = ByteArrayOutputStream().also { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }.toByteArray()
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)!!
        assertEquals(bitmap.getPixel(1, 1), decoded.getPixel(1, 1))
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        assertNull(BitmapFactory.decodeStream(bytes.inputStream(), null, options))
        assertEquals(2, options.outWidth)
        bitmap.recycle()
        assertTrue(bitmap.isRecycled)
        assertThrows(IllegalStateException::class.java) { bitmap.getPixel(0, 0) }
    }
    @Test fun bitmapInvalidBoundsAndDecode() {
        val image = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        assertThrows(IllegalArgumentException::class.java) { Bitmap.createBitmap(image, 1, 0, 2, 1) }
        assertNull(BitmapFactory.decodeStream(byteArrayOf(1, 2, 3).inputStream()))
    }
    @Test fun bitmapDecodesUpstreamWebpCharacterIcon() {
        val encoded = android.content.res.AssetManager().open("resource-packs/cn-bilibili/icons/characters/icon_unit_100661.webp").use { it.readBytes() }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        assertNull(BitmapFactory.decodeStream(encoded.inputStream(), null, bounds))
        assertEquals(128, bounds.outWidth); assertEquals(128, bounds.outHeight)
        val decoded = BitmapFactory.decodeByteArray(encoded, 0, encoded.size)!!
        // Opaque source pixels also catch BGR/ARGB channel-order regressions.
        assertEquals(0xfff3c0d0.toInt(), decoded.getPixel(60, 60))
        assertEquals(0, decoded.getPixel(0, 0) ushr 24)
        decoded.recycle()
        val sampled = BitmapFactory.decodeStream(encoded.inputStream(), null, BitmapFactory.Options().apply { inSampleSize = 2 })!!
        assertEquals(64, sampled.width); assertEquals(64, sampled.height)
        sampled.recycle()
    }
    @Test fun base64FlagsAndWhitespace() {
        assertEquals("YQ==\n", Base64.encodeToString(byteArrayOf(97), Base64.DEFAULT))
        assertEquals("YQ", Base64.encodeToString(byteArrayOf(97), Base64.NO_WRAP or Base64.NO_PADDING))
        assertArrayEquals(byteArrayOf(97), Base64.decode("Y Q==\r\n", Base64.DEFAULT))
        val bytes = byteArrayOf(-1, -2, -3)
        assertArrayEquals(bytes, Base64.decode(Base64.encodeToString(bytes, Base64.URL_SAFE), Base64.URL_SAFE))
    }
    @Test fun prefsTypesPersistenceAndCorruptionBackup() {
        val dir = Files.createTempDirectory("desktop 中文 prefs").toFile()
        val context = DesktopContext(dir)
        val prefs = context.getSharedPreferences("sample", 0)
        assertSame(prefs, context.getSharedPreferences("sample", 0))
        assertTrue(prefs.edit().putString("s", "中文").putInt("i", 3).putLong("l", Long.MAX_VALUE).putBoolean("b", true).putFloat("f", 0.5f).commit())
        val restored = DesktopContext(dir).getSharedPreferences("sample", 0)
        assertEquals("中文", restored.getString("s", null)); assertEquals(Long.MAX_VALUE, restored.getLong("l", 0))
        assertEquals(3, restored.getInt("i", 0)); assertTrue(restored.getBoolean("b", false)); assertEquals(0.5f, restored.getFloat("f", 0f))
        assertThrows(ClassCastException::class.java) { restored.getString("i", null) }
        restored.edit().remove("s").apply()
        assertFalse(DesktopContext(dir).getSharedPreferences("sample", 0).contains("s"))
        java.io.File(dir, "shared_prefs/sample.json").writeText("{broken")
        var backup: java.io.File? = null
        assertNull(DesktopContext(dir) { backup = it }.getSharedPreferences("sample", 0).getString("s", null))
        assertEquals("{broken", backup!!.readText())
    }
    @Test fun lruSizeAndAccessOrder() {
        val cache = object : LruCache<String, String>(3) { override fun sizeOf(key: String, value: String) = value.length }
        cache.put("a", "aa"); cache.put("b", "b"); cache.get("a"); cache.put("c", "c")
        assertEquals("aa", cache.get("a")); assertNull(cache.get("b")); assertEquals("c", cache.get("c"))
        cache.evictAll(); assertNull(cache.get("a"))
    }
    @Test fun monotonicClockAndProcess() {
        val before = android.os.SystemClock.elapsedRealtime()
        assertTrue(android.os.SystemClock.uptimeMillis() >= before)
        assertEquals(ProcessHandle.current().pid().toInt(), android.os.Process.myPid())
        assertNotNull(android.content.res.AssetManager().open("resource-packs/cn-bilibili/icons.json").use { it.read() })
    }
}
