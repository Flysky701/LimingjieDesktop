package com.landosol.toolbox.labyrinth.node

import com.landosol.toolbox.clanbattle.recognition.PixelImage
import com.landosol.toolbox.labyrinth.vision.EntryPixelRect
import java.io.File
import kotlin.math.roundToInt
import org.junit.Assume.assumeTrue
import org.junit.Assert.*
import org.junit.Test

class FinalBossPlatformLocatorTest {
    @Test fun `real active area three boss is found`() {
        val frame = readImage(File("src/test/resources/labyrinth/final-boss-active-20260909.jpg"))
        val locator = FinalBossPlatformLocator(platform)
        val matches = locator.locate(frame)
        println("active-boss matches=$matches candidates=${locator.lastCandidates}")
        val rect = requireNotNull(finalBossPlatformClickRect(matches, frame.width, frame.height, null))
        assertEquals(1, matches.size)
        assertTrue(matches.single().confidence > 0.90)
        assertTrue(matches.single().colorScore < 0.80)
        assertTrue(matches.single().purpleActivationScore > 0.20)
        val (x, y) = LabyrinthNodeActionPlanner().getBaseClickPosition(rect)
        assertTrue("click=$x,$y", x in 1450..1520 && y in 600..644)
    }

    @Test fun `active color exception still requires both text and complete platform activation`() {
        val frame = readImage(File("src/test/resources/labyrinth/final-boss-active-20260909.jpg"))
        fun masked(left: Int, top: Int, width: Int, height: Int): PixelImage {
            val pixels = frame.pixels.copyOf()
            for (y in top until top + height) for (x in left until left + width)
                pixels[y * frame.width + x] = 0xff403848.toInt()
            return PixelImage(frame.width, frame.height, pixels)
        }
        val locator = FinalBossPlatformLocator(platform)
        assertTrue("glyphs without left activation rail must fail",
            locator.locate(masked(1320, 562, 56, 160)).isEmpty())
        assertTrue("purple platform without glyphs must fail",
            locator.locate(masked(1425, 597, 122, 46)).isEmpty())
        assertFalse(FinalBossPlatformMatch(EntryPixelRect(1425, 597, 122, 46), 0.75, 0.68, 0.60).reliable)
    }

    @Test fun `real active label follows horizontal pan and capture scaling`() {
        val original = readImage(File("src/test/resources/labyrinth/final-boss-active-20260909.jpg"))
        val shifted = PixelImage(original.width, original.height, IntArray(original.pixels.size) { i ->
            val sx = i % original.width + 521
            if (sx < original.width) original[sx, i / original.width] else 0xff403848.toInt()
        })
        val scaled = PixelImage(960, 540, IntArray(960 * 540) { i -> original[(i % 960) * 2, (i / 960) * 2] })
        val locator = FinalBossPlatformLocator(platform)
        for ((frame, expectedX) in listOf(shifted to 965, scaled to 743)) {
            val rect = requireNotNull(finalBossPlatformClickRect(locator.locate(frame), frame.width, frame.height, null)) {
                "size=${frame.width} candidates=${locator.lastCandidates}"
            }
            val (x, _) = LabyrinthNodeActionPlanner().getBaseClickPosition(rect)
            assertEquals(expectedX.toDouble(), x.toDouble(), 3.0)
        }
    }

    private val platform = readImage(File("src/main/assets/resource-packs/cn-bilibili/vision/node_boss_platform.png"))

    private fun readImage(file: File): PixelImage {
        val image = requireNotNull(Class.forName("javax.imageio.ImageIO")
            .getMethod("read", File::class.java).invoke(null, file))
        val width = image.javaClass.getMethod("getWidth").invoke(image) as Int
        val height = image.javaClass.getMethod("getHeight").invoke(image) as Int
        val pixels = IntArray(width * height)
        image.javaClass.getMethod("getRGB", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, IntArray::class.java,
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            .invoke(image, 0, 0, width, height, pixels, 0, width)
        return PixelImage(width, height, pixels)
    }

    private fun scene(centers: List<Int>, height: Int = 1080, body: Int = 0xff802040.toInt()): PixelImage {
        val scale = height / 1080.0
        val width = (1920 * scale).toInt()
        val pixels = IntArray(width * height) { i ->
            if (i / width < height * 0.50) body else 0xff182c40.toInt()
        }
        val labelWidth = (122 * scale).roundToInt()
        val labelHeight = (46 * scale).roundToInt()
        val top = (598 * scale).toInt()
        for (center in centers) {
            val left = (center * scale).toInt() - labelWidth / 2
            for (y in 0 until labelHeight) for (x in 0 until labelWidth) {
                pixels[(top + y) * width + left + x] = platform[
                    225 + x * 121 / (labelWidth - 1), 197 + y * 45 / (labelHeight - 1)]
            }
        }
        return PixelImage(width, height, pixels)
    }

    @Test fun `moving artwork does not change nameplate location and camera offset is measured`() {
        val locator = FinalBossPlatformLocator(platform)
        for ((center, body) in listOf(965 to 0xff802040.toInt(), 1500 to 0xff00ffff.toInt())) {
            val matches = locator.locate(scene(listOf(center), body = body))
            val rect = requireNotNull(finalBossPlatformClickRect(matches, 1920, 1080, null)) { "matches=$matches" }
            val (x, y) = LabyrinthNodeActionPlanner().getBaseClickPosition(rect)
            assertEquals(center.toDouble(), x.toDouble(), 3.0)
            assertEquals(621.0, y.toDouble(), 3.0)
        }
    }

    /** A 1280x720 capture must clear the same gate as 1080p: the nameplate is scored 1:1. */
    @Test fun `720p capture clears the nameplate confidence gate`() {
        val frame = scene(listOf(880), height = 720)
        val locator = FinalBossPlatformLocator(platform)
        val matches = locator.locate(frame)
        assertEquals("matches=$matches candidates=${locator.lastCandidates}", 1, matches.size)
        assertTrue("confidence=${matches.single().confidence}", matches.single().confidence >= 0.90)
        assertNotNull(finalBossPlatformClickRect(matches, 1280, 720, null))
    }

    @Test fun `scaled capture uses current label not original pixels`() {
        val matches = FinalBossPlatformLocator(platform).locate(scene(listOf(1468), height = 540))
        val rect = requireNotNull(finalBossPlatformClickRect(matches, 960, 540, 734.0)) { "$matches" }
        val (x, y) = LabyrinthNodeActionPlanner().getBaseClickPosition(rect)
        assertEquals(734.0, x.toDouble(), 2.0)
        assertEquals(310.5, y.toDouble(), 2.0)
    }

    @Test fun `absent label cannot reuse previous detection or predicted coordinates`() {
        val locator = FinalBossPlatformLocator(platform)
        assertFalse(locator.locate(scene(listOf(1500))).isEmpty())
        val empty = locator.locate(scene(emptyList()))
        assertTrue(empty.isEmpty())
        assertNull(finalBossPlatformClickRect(empty, 1920, 1080, 1500.0))
        assertTrue(FinalBossPlatformLocator(null).locate(scene(listOf(1500))).isEmpty())
    }

    @Test fun `multiple labels and topology contradiction cannot authorize a tap`() {
        val locator = FinalBossPlatformLocator(platform)
        val duplicate = locator.locate(scene(listOf(965, 1500)))
        assertEquals(2, duplicate.size)
        assertNull(finalBossPlatformClickRect(duplicate, 1920, 1080, 1500.0))
        val single = locator.locate(scene(listOf(1500)))
        assertNull(finalBossPlatformClickRect(single, 1920, 1080, 965.0))
        assertNotNull(finalBossPlatformClickRect(single, 1920, 1080, 1468.0))
    }

    @Test fun `weak clipped and HUD evidence is refused`() {
        for (match in listOf(
            FinalBossPlatformMatch(EntryPixelRect(1440, 598, 122, 46), 0.60),
            FinalBossPlatformMatch(EntryPixelRect(1880, 598, 122, 46), 0.99),
            FinalBossPlatformMatch(EntryPixelRect(1440, 900, 122, 46), 0.99),
        )) assertNull(finalBossPlatformClickRect(listOf(match), 1920, 1080, null))
    }

    @Test fun `real ordinary map cannot masquerade as boss nameplate`() {
        val file = File("../../素材/ui/黎明界进入/当前版本_节点选择.png")
        assumeTrue("local diagnostic fixture is unavailable: ${file.absolutePath}", file.isFile)
        val frame = readImage(file)
        assertTrue(FinalBossPlatformLocator(platform).locate(frame).isEmpty())
    }

    @Test fun `purple EX and other active map nodes do not pass the boss gate`() {
        val locator = FinalBossPlatformLocator(platform)
        for (name in listOf("MuMu-20260822-150505-982.png", "MuMu-20260822-153701-157.png",
            "MuMu-20260821-144152-092.png")) {
            val file = File("../../Test_Screenshots/$name")
            assumeTrue("local diagnostic fixture is unavailable: ${file.absolutePath}", file.isFile)
            val frame = readImage(file)
            assertTrue("$name: ${locator.lastCandidates}", locator.locate(frame).isEmpty())
        }
    }
}
