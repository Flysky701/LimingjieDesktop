package com.landosol.toolbox.core

import com.landosol.toolbox.desktop.shared.AtomicFiles
import com.landosol.toolbox.vision.NativeBitmap
import com.landosol.toolbox.vision.NativeBitmapCodec
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class NativeCorePrimitivesTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun preferencesSurviveRestartAndPreserveCorruptFile() {
        val name = "sample"
        val prefs = AppContext(temp.root).preferences(name)
        assertTrue(prefs.edit().putString("setting", "中文").commit())
        assertEquals("中文", AppContext(temp.root).preferences(name).getString("setting", null))

        temp.root.resolve("shared_prefs/$name.json").writeText("{broken")
        var backup: File? = null
        val recovered = AppContext(temp.root) { backup = it }.preferences(name)
        assertNull(recovered.getString("setting", null))
        assertEquals("{broken", requireNotNull(backup).readText())
    }

    @Test fun atomicReplacementPreservesTheOriginalDuringARead() {
        val path = temp.root.toPath().resolve("settings.json")
        AtomicFiles.write(path, "original".toByteArray())
        Files.newInputStream(path).use { reader ->
            try {
                AtomicFiles.write(path, "replacement".toByteArray())
            } catch (_: AccessDeniedException) {
                // Windows can hold the destination open without delete sharing.
                assertEquals("original", Files.readString(path))
            }
            assertEquals("original", reader.readBytes().decodeToString())
        }
        AtomicFiles.write(path, "final".toByteArray())
        assertEquals("final", Files.readString(path))
        assertEquals(listOf("settings.json"), temp.root.list()!!.toList())
    }

    @Test fun pngRetainsStraightArgbAndRecycledPixelsAreInaccessible() {
        val color = 0x4080fc10
        val bitmap = NativeBitmap.create(intArrayOf(color), 1, 1)
        assertThrows(IllegalArgumentException::class.java) { bitmap.getPixel(1, 0) }
        val encoded = ByteArrayOutputStream()
        assertTrue(bitmap.compress(NativeBitmap.CompressFormat.PNG, 100, encoded))
        bitmap.recycle()
        assertTrue(bitmap.isRecycled)
        assertThrows(IllegalStateException::class.java) { bitmap.copyToPixelImage() }

        val options = NativeBitmapCodec.Options().apply { inSampleSize = 4 }
        val sampled = requireNotNull(NativeBitmapCodec.decodeStream(encoded.toByteArray().inputStream(), options))
        assertEquals(color, sampled.getPixel(0, 0))
        sampled.recycle()
    }

    @Test fun indexedWebpDecodesWithExpectedBoundsAndChannels() {
        val assets = AppAssets()
        val path = "resource-packs/cn-bilibili/icons/characters/icon_unit_100661.webp"
        assertTrue(path in assets.paths())
        assertThrows(FileNotFoundException::class.java) { assets.open("missing.webp") }
        val bounds = NativeBitmapCodec.Options().apply { inJustDecodeBounds = true }
        assets.open(path).use { assertNull(NativeBitmapCodec.decodeStream(it, bounds)) }
        assertEquals(128, bounds.outWidth)
        assertEquals(128, bounds.outHeight)
        val bitmap = assets.open(path).use { requireNotNull(NativeBitmapCodec.decodeStream(it)) }
        assertEquals(0xfff3c0d0.toInt(), bitmap.getPixel(60, 60))
        assertEquals(0, bitmap.getPixel(0, 0) ushr 24)
        bitmap.recycle()
    }
}
