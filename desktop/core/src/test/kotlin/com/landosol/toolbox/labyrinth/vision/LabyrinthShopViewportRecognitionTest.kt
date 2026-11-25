package com.landosol.toolbox.labyrinth.vision

import com.landosol.toolbox.vision.PixelImage
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class LabyrinthShopViewportRecognitionTest {
    @Test fun `actual top and bottom thumbs exclude gutter padding and missing rail is not a boundary`() {
        val cases = listOf(
            Triple("shoplog2", true, false),
            Triple("shoplog-20261117-204428", false, true),
        )
        for ((captureDirectory, atTop, atBottom) in cases) {
            val frame = shopFrame(captureDirectory)
            val observed = LabyrinthShopRecognizer().recognize(frame)
            val viewport = requireNotNull(observed.viewport)
            assertEquals(captureDirectory, atTop, viewport.atTop)
            assertEquals(captureDirectory, atBottom, viewport.atBottom)
            assertEquals(captureDirectory, if (atTop) 0 else 1000, viewport.position)
            assertNotEquals(0L, observed.visualFingerprint)
            val withoutThumb = frame.copy(pixels = frame.pixels.copyOf())
            val rail = requireNotNull(ReferenceFitMapper.map(frame.width, frame.height,
                EntryReferenceSize(1920, 1080), EntryReferenceRect(1820, 226, 20, 636)))
            for (y in rail.top until rail.top + rail.height) {
                for (x in rail.left until rail.left + rail.width) withoutThumb.pixels[y * frame.width + x] = -1
            }
            assertNull(LabyrinthShopViewportRecognition.recognize(withoutThumb))
            assertEquals(observed.visualFingerprint, LabyrinthShopViewportRecognition.visualFingerprint(withoutThumb))
        }
    }

    @Test fun `unknown FULL button waits for recognition while non FULL controls never enter OCR`() {
        // No button template means no trusted button, regardless of the visible title/icon.
        val unlocalized = LabyrinthShopRecognizer().recognize(shopFrame())
        assertFalse(unlocalized.layoutReady)
        assertTrue(unlocalized.items.all { it.visibility == LabyrinthShopItemVisibility.UNRESOLVED })
        val unknown = unlocalized.items.first().copy(visibility = LabyrinthShopItemVisibility.FULL, categoryAttempt = 2)
        assertEquals(LabyrinthShopItemStatus.UNKNOWN, unknown.status)
        val stale = unknown.copy(kind = LabyrinthShopItemKind.ROLE_IMPRINT,
            titleText = "攻击型职能的随机印记", roleImprintLabel = "攻击型职能印记",
            titleEvidenceId = 42, imprintLabelPending = true)
        val skipped = requireNotNull(stale.skipOcrIfButtonUnknown())
        assertEquals(LabyrinthShopItemStatus.UNKNOWN, skipped.status)
        assertEquals(LabyrinthShopCategoryState.READING, skipped.categoryState)
        assertEquals(2, skipped.categoryAttempt)
        assertEquals(LabyrinthShopItemKind.UNKNOWN, skipped.kind)
        assertFalse(skipped.purchasable)
        assertFalse(skipped.imprintLabelPending)
        assertNull(skipped.titleText)
        assertNull(skipped.titleEvidenceId)
        assertNull(skipped.roleImprintLabel)
        assertNull(stale.copy(status = LabyrinthShopItemStatus.AVAILABLE).skipOcrIfButtonUnknown())
        assertNull(stale.copy(status = LabyrinthShopItemStatus.DISABLED).skipOcrIfButtonUnknown())
        for (visibility in listOf(LabyrinthShopItemVisibility.PARTIAL, LabyrinthShopItemVisibility.UNSEEN,
            LabyrinthShopItemVisibility.UNRESOLVED)) {
            val clipped = stale.copy(status = LabyrinthShopItemStatus.AVAILABLE, visibility = visibility)
            assertFalse(clipped.purchasable)
            val noOcr = requireNotNull(clipped.skipOcrIfButtonUnknown())
            assertEquals(LabyrinthShopItemStatus.UNKNOWN, noOcr.status)
            assertNull(noOcr.titleEvidenceId)
            assertFalse(noOcr.purchasable)
        }
    }

    @Test fun `real and shifted rows localize title and button together without stale cache authorization`() {
        val templateFile = File("src/main/resources/assets/resource-packs/cn-bilibili/vision/shop/shop_buy_button.png")
        val recognizer = LabyrinthShopRecognizer(buyButtonTemplate = pixelImage(templateFile))
        val top = shopFrame()
        val initial = recognizer.recognize(top)
        assertTrue(initial.layoutReady)
        val initialFull = initial.items.filter { it.visibility == LabyrinthShopItemVisibility.FULL }
        assertEquals(3, initialFull.size)
        assertTrue(initialFull.all { it.buyButtonScore >= 0.55 && it.titleRect != null })

        val bottom = recognizer.recognize(shopFrame("shoplog-20261117-204428"))
        assertTrue(bottom.layoutReady)
        assertEquals(3, bottom.items.count { it.visibility == LabyrinthShopItemVisibility.FULL })
        val partial = bottom.items.filter { it.visibility == LabyrinthShopItemVisibility.PARTIAL }
        assertEquals(3, partial.size) // Upper buttons are visible, but their titles are above the clip.
        assertTrue(partial.all { !it.purchasable && it.titleRect == null })

        for (dy in listOf(40, 84)) {
            val shifted = translateList(top, dy)
            val observed = recognizer.recognize(shifted)
            assertTrue(observed.layoutReady)
            val full = observed.items.filter { it.visibility == LabyrinthShopItemVisibility.FULL }
            assertEquals(3, full.size)
            for ((column, item) in full.withIndex()) {
                val original = initialFull[column]
                val title = requireNotNull(item.titleRect)
                val originalTitle = requireNotNull(original.titleRect)
                assertEquals(original.buyButtonRect.left, item.buyButtonRect.left)
                assertEquals(original.buyButtonRect.top + dy, item.buyButtonRect.top)
                assertEquals(original.screenRect.top + dy, item.screenRect.top)
                assertEquals(originalTitle.top + dy, title.top)
                assertEquals(originalTitle.left, title.left)
                assertTrue(item.buyButtonScore >= 0.55)
                assertEquals(top[originalTitle.left + 20, originalTitle.top + 10],
                    shifted[title.left + 20, title.top + 10])
            }
        }
        val clipped = recognizer.recognize(translateList(top, -120))
        assertFalse(clipped.layoutReady) // Cached FULL rows cannot authorize a now-clipped title.
        assertTrue(clipped.items.none { it.visibility == LabyrinthShopItemVisibility.FULL || it.purchasable })
        assertTrue(clipped.items.any { it.visibility == LabyrinthShopItemVisibility.UNRESOLVED })
    }

    private fun translateList(source: PixelImage, dy: Int): PixelImage {
        val output = source.copy(pixels = source.pixels.copyOf())
        val clip = requireNotNull(LabyrinthShopVisibleLayout.clip(source))
        for (y in clip.top until clip.top + clip.height) {
            for (x in clip.left until clip.left + clip.width) {
                output.pixels[y * source.width + x] = if (y - dy in clip.top until clip.top + clip.height)
                    source[x, y - dy] else -1
            }
        }
        return output
    }

    /** Finds existing real captures from either project/Android or workspace working directories. */
    private fun shopFrame(captureDirectory: String = "shoplog2"): PixelImage {
        val file = generateSequence(File(".").canonicalFile, File::getParentFile)
            .flatMap { sequenceOf(File(it, "$captureDirectory/frame/latest.jpg"), File(it, "extracted/$captureDirectory/frame/latest.jpg")) }
            .firstOrNull { it.isFile }
        assumeTrue("local shop capture is unavailable: $captureDirectory/frame/latest.jpg", file != null)
        return pixelImage(requireNotNull(file))
    }

    private fun pixelImage(file: File): PixelImage {
        val image = Class.forName("javax.imageio.ImageIO").getMethod("read", File::class.java).invoke(null, file)
        val width = image.javaClass.getMethod("getWidth").invoke(image) as Int
        val height = image.javaClass.getMethod("getHeight").invoke(image) as Int
        val pixels = IntArray(width * height)
        image.javaClass.getMethod("getRGB", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, IntArray::class.java,
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            .invoke(image, 0, 0, width, height, pixels, 0, width)
        return PixelImage(width, height, pixels)
    }
}
