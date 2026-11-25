package com.landosol.toolbox.labyrinth.vision

import com.landosol.toolbox.vision.PixelImage
import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Only the two supplied 720p panels and ownership/content safety for this exact window. */
class LabyrinthRelicEffectWindowTemplateTest {
    @Test fun bothProvidedPanelsUseOneTemplatePathAndKeepContentSeparate() {
        val assets = File("src/main/resources/assets/resource-packs/cn-bilibili")
        val templates = mapOf(
            EntryAnchorId.RELIC_EFFECT_TITLE to image(File(assets, "vision/item_reward/relic_effect_title.png")),
            EntryAnchorId.RELIC_EFFECT_INSTRUCTION to image(File(assets, "vision/item_reward/relic_effect_instruction.png")),
            EntryAnchorId.ITEM_REWARD_CLOSE to image(File(assets, "vision/item_reward/item_reward_close.png")),
        )
        val recognizer = LabyrinthRelicRewardRecognizer(listOf(
            LabyrinthRelicTemplate("12001", "紫晶琉璃", "弱体", "1", "", image(File(assets, "icons/relics/12001.png")))
        ))
        for (name in listOf("mercury", "labyrista")) {
            val frame = image(File("src/test/resources/relic-effect-window/" + name + "-720.png"))
            assertEquals(1280, frame.width); assertEquals(720, frame.height)
            val matcher = GradientTemplateMatcher()
            val scores = templates.mapValues { (id, template) ->
                val definition = LabyrinthEntryFrameProcessor.DEFINITIONS_BY_ID.getValue(id).single()
                val rect = requireNotNull(ReferenceFitMapper.map(frame.width, frame.height,
                    definition.referenceSize, definition.referenceRect))
                matcher.score(frame, rect, template, minimumChannelScore = 0.0)
            }
            println(name + " window template scores=" + scores)
            scores.forEach { (id, score) -> assertTrue(name + " " + id + "=" + score, score >= 0.80) }
            val page = LabyrinthEntryPageClassifier().classify(LabyrinthAnchorScores(scores))
            assertEquals(LabyrinthEntryPageState.ITEM_REWARD, page.state)
            val result = LabyrinthEntryFrameResult(page, emptyList(), 0)
            assertTrue(recognizer.templateConfirmed(result))
            assertTrue(recognizer.candidate(frame, result))
            val layout = recognizer.recognize(frame)
            assertTrue(layout.complete)
            assertEquals(1, layout.rows.size)
            if (name == "labyrista") {
                assertTrue(layout.rows.single().hasDetailButton)
                assertEquals("12001", layout.rows.single().match.relicId)
            } else {
                assertFalse(layout.rows.single().hasDetailButton)
                // This remains the existing name-based exclusion, not a new currency grant/ledger.
                assertTrue(LabyrinthRelicRewardText.nonRelic("阿尔法金币×3000"))
                assertFalse(LabyrinthRelicRewardText.nonRelic("紫晶琉璃×1"))
            }
            val weak = result.copy(observation = page.copy(anchorScores = LabyrinthAnchorScores(
                scores + (EntryAnchorId.RELIC_EFFECT_INSTRUCTION to 0.79))))
            assertFalse(recognizer.templateConfirmed(weak))
            assertFalse(recognizer.candidate(frame, weak))
            val ordinary = result.copy(observation = page.copy(anchorScores = LabyrinthAnchorScores(mapOf(
                EntryAnchorId.ITEM_REWARD_TITLE to 0.95, EntryAnchorId.ITEM_REWARD_INSTRUCTION to 0.95,
                EntryAnchorId.ITEM_REWARD_CLOSE to 0.95))))
            assertFalse(recognizer.templateConfirmed(ordinary))
            assertFalse(recognizer.candidate(frame, ordinary))
        }
    }

    private fun image(file: File): PixelImage {
        val decoded = requireNotNull(Class.forName("javax.imageio.ImageIO")
            .getMethod("read", File::class.java).invoke(null, file)) { "Cannot decode " + file }
        val width = decoded.javaClass.getMethod("getWidth").invoke(decoded) as Int
        val height = decoded.javaClass.getMethod("getHeight").invoke(decoded) as Int
        val pixels = IntArray(width * height)
        decoded.javaClass.getMethod("getRGB", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, IntArray::class.java,
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            .invoke(decoded, 0, 0, width, height, pixels, 0, width)
        return PixelImage(width, height, pixels)
    }
}
