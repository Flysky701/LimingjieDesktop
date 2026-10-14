package com.landosol.toolbox.labyrinth.vision

import com.landosol.toolbox.clanbattle.recognition.PixelImage
import com.landosol.toolbox.gamedata.GameIconPackDocument
import java.io.File
import java.util.zip.ZipFile
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class LabyrinthFreeRolePickerRegressionTest {
    private val root = generateSequence(File(".").canonicalFile, File::getParentFile)
        .first { File(it, "android/app/build.gradle.kts").isFile }

    @Test fun `free picker remains unrestricted after a roster filtered battle and reset`() {
        val resource = File(root, "android/app/src/main/assets/resource-packs/cn-bilibili")
        val index = Json { ignoreUnknownKeys = true }.decodeFromString<GameIconPackDocument>(
            File(resource, "icons.json").readText(),
        )
        val templates = index.characterIcons.filter { it.format == "png" }.map {
            LabyrinthBattleCharacterTemplate(it.ownerId, it.ownerId, it.variant, readImage(File(resource, it.file)))
        }
        val frame = readImage(File(root, "android/app/src/test/resources/labyrinth/battle-team-roster-20260914.jpg"))
        val reference = LabyrinthBattleTeamRecognizer(templates, requiredStableFrames = 1).recognizeOpening(frame)
        assertTrue(reference.visibleCharacters.isNotEmpty())
        val actualRecognizer = LabyrinthBattleTeamRecognizer(templates, requiredStableFrames = 1)
        actualRecognizer.recognize(frame, rosterCharacterIds = setOf("1001"))
        // The frame processor resets between the battle and picker pages, but this must also
        // release battle-only identity restrictions before a picker offers unowned roles.
        actualRecognizer.reset()
        val actual = actualRecognizer.recognizeOpening(frame)
        val changes = reference.visibleCharacters.zip(actual.visibleCharacters).filter { (left, right) ->
            left.characterId != right.characterId || left.confidence != right.confidence || left.rivalMargin != right.rivalMargin
        }
        println("picker changes=${changes.map { (a, b) -> "${a.slotId}: ${a.characterId}/${a.confidence}/${a.rivalMargin} -> ${b.characterId}/${b.confidence}/${b.rivalMargin}" }}")
        assertEquals(reference.visibleCharacters, actual.visibleCharacters)
    }

    @Test fun `archived one person shop picker still produces the full roster viewport`() {
        val bundle = File(root, "log/归档/limingjie-debug-20260920-155513.zip")
        assumeTrue("optional historical picker bundle unavailable", bundle.isFile)
        val frame = ZipFile(bundle).use { zip -> zip.getInputStream(zip.getEntry("frame/latest.jpg")).use {
            pixels(requireNotNull(Class.forName("javax.imageio.ImageIO").getMethod("read", java.io.InputStream::class.java).invoke(null, it)))
        } }
        val assetRoot = File(root, "android/app/src/main/assets")
        val paths = AndroidLabyrinthEntryTemplateLoader.TEMPLATE_PATHS + AndroidLabyrinthEntryTemplateLoader.OPTIONAL_TEMPLATE_PATHS
        val templates = LabyrinthEntryTemplateSet(paths.mapNotNull { (id, path) ->
            File(assetRoot, path).takeIf(File::isFile)?.let { id to readImage(it) }
        }.toMap())
        val result = LabyrinthEntryFrameProcessor(templates).process(frame)
        println("archived picker page=${result.observation.state} header=${result.observation.anchorScores[EntryAnchorId.SELECTION_HEADER_STANDARD]} opening=${result.openingCharacterSelection != null}")
        assertEquals(LabyrinthEntryPageState.INITIAL_CHARACTER_SELECTION, result.observation.state)
        assertNotNull(result.openingCharacterSelection)
        assertFalse(labyrinthRoleRewardOwnsInitialSelection(result.observation.anchorScores))
    }

    private fun readImage(file: File): PixelImage = pixels(requireNotNull(
        Class.forName("javax.imageio.ImageIO").getMethod("read", File::class.java).invoke(null, file),
    ))
    private fun pixels(image: Any): PixelImage {
        val width = image.javaClass.getMethod("getWidth").invoke(image) as Int
        val height = image.javaClass.getMethod("getHeight").invoke(image) as Int
        val pixels = IntArray(width * height)
        image.javaClass.getMethod("getRGB", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, IntArray::class.java,
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
        ).invoke(image, 0, 0, width, height, pixels, 0, width)
        return PixelImage(width, height, pixels)
    }
}
