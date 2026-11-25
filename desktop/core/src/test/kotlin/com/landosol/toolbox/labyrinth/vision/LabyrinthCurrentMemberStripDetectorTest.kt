package com.landosol.toolbox.labyrinth.vision

import com.landosol.toolbox.vision.PixelImage
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt
import org.junit.Assert.*
import org.junit.Test
import kotlinx.serialization.json.Json
import com.landosol.toolbox.gamedata.GameIconPackDocument
import com.landosol.toolbox.labyrinth.labyrinthEffectiveScanFrameEvidence

class LabyrinthCurrentMemberStripDetectorTest {
    private val detector = LabyrinthCurrentMemberStripDetector()

    @Test
    fun `single effective portrait is identified using packaged icons without counting members`() {
        val root = generateSequence(File(".").canonicalFile, File::getParentFile)
            .map { File(it, "desktop/core/src/main/resources/assets/resource-packs/cn-bilibili") }
            .first(File::isDirectory)
        val pack = Json { ignoreUnknownKeys = true }
            .decodeFromString<GameIconPackDocument>(File(root, "icons.json").readText())
        val templates = pack.characterIcons.filter { it.format == "png" }.map {
            LabyrinthBattleCharacterTemplate(it.ownerId, it.ownerId, it.variant, readImage(File(root, it.file)))
        }
        val result = LabyrinthBattleTeamRecognizer(templates = templates, requiredStableFrames = 1)
            .recognize(screenshot("effective-single-20260913.png"))
        val evidence = labyrinthEffectiveScanFrameEvidence(result)
        assertEquals(result.visibleCharacters.toString(), 1, evidence.confirmedCharacterIds.size)
        assertTrue(result.visibleCharacters.toString(), evidence.unsafeVisibleCharacters.isEmpty())
    }

    @Test
    fun `single effective character is the whole upper roster`() {
        val result = LabyrinthBattleTeamRecognizer(requiredStableFrames = 1)
            .recognize(screenshot("effective-single-20260913.png"))
        assertEquals(1, result.visibleCharacters.size)
        assertEquals(5, result.selectedCharacters.size)
        assertEquals(LabyrinthBattleElementFilter.EFFECTIVE_EFFECT, result.currentFilter)
        assertTrue(result.scrollbar.thumbRect!!.height > 200)
        assertTrue(result.scrollbar.trackRect.left in 1390..1410)
        assertTrue(result.scrollbar.contentEndVisible)
        assertEquals(com.landosol.toolbox.labyrinth.LabyrinthBattleRosterSearchDecision.EXHAUSTED,
            com.landosol.toolbox.labyrinth.LabyrinthBattleRosterSearch().observe(
                com.landosol.toolbox.automation.AutomationSessionId(1L), result, listOf("effective-scan")))
    }

    @Test
    fun `cropped member screenshot aligns all five frames to paired outer borders`() {
        val frame = screenshot()
        val expectedLefts = listOf(64, 234, 404, 575, 745)
        val result = detector.refine(frame, hints())
        assertEquals(5, result.size)
        result.forEachIndexed { index, rect ->
            assertTrue("slot $index: $rect", abs(rect.left - expectedLefts[index]) <= 4)
            assertTrue("slot $index: $rect", rect.top in 131..141)
            assertTrue("slot $index: $rect", rect.width in 153..161)
            assertTrue("slot $index: $rect", rect.top + rect.height in 287..296)
        }
        assertTrue(result.zipWithNext().all { (a, b) -> a.left + a.width < b.left })
    }

    @Test
    fun `member detection follows screenshot scaling without accumulating column drift`() {
        val original = screenshot()
        for (factor in listOf(0.5, 1.3)) {
            val width = (original.width * factor).roundToInt()
            val height = (original.height * factor).roundToInt()
            val scaled = PixelImage(width, height, IntArray(width * height) { i ->
                original[(i % width) * original.width / width, (i / width) * original.height / height]
            })
            val expected = hints().map { r -> EntryPixelRect((r.left * factor).roundToInt(),
                (r.top * factor).roundToInt(), (r.width * factor).roundToInt(), (r.height * factor).roundToInt()) }
            val result = detector.refine(scaled, expected)
            result.forEachIndexed { index, rect ->
                assertTrue("scale=$factor slot=$index rect=$rect",
                    abs(rect.left / factor - listOf(64, 234, 404, 575, 745)[index]) <= 7)
            }
        }
    }

    @Test
    fun `empty leading member slots do not renumber or shift later portraits`() {
        val frame = screenshot()
        val pixels = IntArray(frame.width * frame.height) { i ->
            if (i % frame.width < 400) 0xffffffff.toInt() else frame[i % frame.width, i / frame.width]
        }
        val result = detector.refine(PixelImage(frame.width, frame.height, pixels), hints())
        assertEquals(hints()[0], result[0])
        assertEquals(hints()[1], result[1])
        assertTrue(abs(result[2].left - 404) <= 4)
        assertTrue(abs(result[4].left - 745) <= 4)
    }

    @Test
    fun `full boss editor preserves empty leading slots and returns only three occupied members`() {
        val frame = screenshot("boss-member-nephy-20260910.jpg")
        val result = LabyrinthBattleTeamRecognizer(requiredStableFrames = 1).recognize(frame)
        assertEquals(listOf("current_member_3", "current_member_4", "current_member_5"),
            result.selectedCharacters.map { it.slotId })
        result.selectedCharacters.forEach { member ->
            assertTrue("${member.screenRect}", member.screenRect.top in 799..818)
            assertTrue("${member.screenRect}", member.screenRect.height in 188..206)
        }
        // No icon templates were supplied: geometrical calibration must not invent identities.
        assertTrue(result.selectedCharacters.all { it.characterId == null })
    }

    @Test
    fun `full empty boss editor does not report unknown members from blank slots`() {
        val result = LabyrinthBattleTeamRecognizer(requiredStableFrames = 1)
            .recognize(screenshot("boss-team-third-20260909.jpg"))
        assertTrue(result.selectedCharacters.isEmpty())
    }

    @Test
    fun `empty strip keeps calibrated geometry and does not invent borders`() {
        val empty = PixelImage(1490, 356, IntArray(1490 * 356) { 0xffffffff.toInt() })
        assertEquals(hints(), detector.refine(empty, hints()))
    }

    // This is a cropped diagnostic strip, not a full game frame. Supply local geometry hints;
    // production maps its 1920x1080 calibration before calling the same detector.
    private fun hints() = listOf(74, 244, 413, 582, 752).map { EntryPixelRect(it, 141, 152, 152) }

    private fun screenshot(name: String = "current-member-strip-20260913.png"): PixelImage {
        val file = generateSequence(File(".").canonicalFile, File::getParentFile)
            .map { File(it, "desktop/core/src/test/resources/labyrinth/$name") }
            .first(File::isFile)
        return readImage(file)
    }

    private fun readImage(file: File): PixelImage {
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
