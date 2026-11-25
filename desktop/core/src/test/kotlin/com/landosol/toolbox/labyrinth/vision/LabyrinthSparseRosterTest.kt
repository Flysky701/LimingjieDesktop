package com.landosol.toolbox.labyrinth.vision

import com.landosol.toolbox.vision.PixelImage
import com.landosol.toolbox.gamedata.GameIconPackDocument
import java.io.File
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A roster filtered down to a single card must still be found.
 *
 * 2026-09-19 live: 有效效果 listed exactly one character, the recogniser resolved none, and the
 * effective-effect scan rewound the list every 17 seconds for the rest of the session.
 */
class LabyrinthSparseRosterTest {
    @Test
    fun `homare and eriko keep distinct identities in the current member strip`() {
        val frame = screenshot("team-homare-eriko-20261002.png")
        val templates = iconTemplates()
        for (roster in listOf(null, setOf("1067", "1027"))) {
            val recognizer = LabyrinthBattleTeamRecognizer(templates = templates, requiredStableFrames = 1)
            val result = recognizer.recognize(frame, rosterCharacterIds = roster.orEmpty())
            assertEquals(result.selectedCharacters.toString(), listOf("1067", "1027"),
                result.selectedCharacters.map { it.characterId })
        }
    }

    @Test
    fun `one remaining card is located and identified`() {
        val frame = screenshot(FIXTURE)
        val recognizer = LabyrinthBattleTeamRecognizer(templates = iconTemplates(), requiredStableFrames = 1)
        val result = recognizer.recognize(frame)

        assertEquals(LabyrinthBattleElementFilter.EFFECTIVE_EFFECT, result.currentFilter)
        assertEquals(result.visibleCharacters.toString(), 1, result.visibleCharacters.size)
        val card = result.visibleCharacters.single()
        // 七七香（万圣节）, the only role this run held with an effective effect.
        assertEquals("1237", card.characterId)
        assertTrue("card=$card", card.trusted)
        // The card sits below the 用角色名搜索 box, not in the first calibrated row.
        assertTrue("rect=${card.screenRect}", card.screenRect.top in 330..380)
        assertEquals(5, result.selectedCharacters.size)
    }

    @Test
    fun `the search box cannot masquerade as a roster row`() {
        // The box spans several predicted columns. While predicted columns carried row evidence,
        // it alone lifted the row-projection peak above the single real card, which left the
        // detector with no rows at all.
        val detector = LabyrinthCharacterGridDetector()
        val viewport = EntryReferenceRect(60, 230, 1800, 516)
        for (name in listOf(FIXTURE, "effective-single-20260913.png")) {
            val slots = detector.detect(screenshot(name), viewport, 195, 212, 218, 8)
            assertEquals("$name -> $slots", 1, slots.size)
            assertEquals("$name row", 0, slots.single().rowIndex)
            assertEquals("$name column", 0, slots.single().columnIndex)
        }
    }

    private fun iconTemplates(): List<LabyrinthBattleCharacterTemplate> {
        val root = generateSequence(File(".").canonicalFile, File::getParentFile)
            .map { File(it, "desktop/core/src/main/resources/assets/resource-packs/cn-bilibili") }
            .first(File::isDirectory)
        val pack = Json { ignoreUnknownKeys = true }
            .decodeFromString<GameIconPackDocument>(File(root, "icons.json").readText())
        return pack.characterIcons.filter { it.format == "png" }.map {
            LabyrinthBattleCharacterTemplate(it.ownerId, it.ownerId, it.variant, readImage(File(root, it.file)))
        }
    }

    private fun screenshot(name: String): PixelImage {
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
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType).invoke(image, 0, 0, width, height, pixels, 0, width)
        return PixelImage(width, height, pixels)
    }

    private companion object {
        const val FIXTURE = "effective-single-unresolved-20260919.jpg"
    }
}
