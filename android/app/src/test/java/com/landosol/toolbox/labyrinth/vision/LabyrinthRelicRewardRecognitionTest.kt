package com.landosol.toolbox.labyrinth.vision

import com.landosol.toolbox.clanbattle.recognition.PixelImage
import java.io.File
import org.junit.Assert.*
import org.junit.Test

class LabyrinthRelicRewardRecognitionTest {
    private val iconRoot = File("src/main/assets/resource-packs/cn-bilibili/icons/relics")
    private val templates = listOf("40003", "50003", "50006", "11001", "50011").map { id ->
        LabyrinthRelicTemplate(id, id, if (id == "50011") "特殊" else "加速", if (id == "50011") "0" else "1",
            "", readImage(File(iconRoot, "$id.png")))
    }
    private val recognizer = LabyrinthRelicRewardRecognizer(templates)

    @Test fun `only the explicit relic effect title is accepted as a relic receipt`() {
        assertEquals(LabyrinthRelicRewardKind.RELIC_EFFECT, LabyrinthRelicRewardText.kind("迷宫遗物效果"))
        assertNull(LabyrinthRelicRewardText.kind("迷宫遗物"))
        assertNull(LabyrinthRelicRewardText.kind("遗物效果结果"))
        assertNull(LabyrinthRelicRewardText.kind("获得道具"))
    }

    @Test fun `effect icon scan retains every row including the imprint for text exclusion`() {
        for (width in listOf(1_280, 1_920)) {
            val frame = frame(width, listOf("11001", "40003", "50003"), details = setOf(1, 2))
            assertTrue(recognizer.candidate(frame, result()))
            val layout = recognizer.recognize(frame)
            assertTrue(layout.complete)
            assertEquals(3, layout.rows.size)
            assertFalse(layout.rows.first().hasDetailButton)
            assertEquals(listOf("40003", "50003"), layout.rows.filter { it.hasDetailButton }.map { it.match.relicId })
        }
    }

    @Test fun `effect receipt recognizes a relic in the first row and retains special zero bonus relics`() {
        for (id in listOf("11001", "50011")) {
            val matches = recognizer.recognize(frame(1_280, listOf(id), details = setOf(0)))
            assertEquals(listOf(id), matches.rows.map { it.match.relicId })
        }
    }

    @Test fun `unrecognized detail row remains pending instead of being silently skipped`() {
        val matches = recognizer.recognize(frame(1_280, listOf(null), details = setOf(0)))
        assertEquals(1, matches.rows.size)
        assertFalse(matches.rows.single().match.recognized)
    }

    @Test fun `missing detail button cannot erase a relic row and clipped icons block completeness`() {
        val full = frame(1_280, listOf("40003", "50003"), details = setOf(0))
        val layout = recognizer.recognize(full)
        assertTrue(layout.complete)
        assertEquals(2, layout.rows.size)
        assertEquals("50003", layout.rows.last().match.relicId)
        assertFalse(layout.rows.last().hasDetailButton)
        val clipped = frame(1_280, listOf("40003", "50003", "50006"), details = setOf(0, 1, 2))
        // Move the third icon down into the viewport edge to model a partially scrolled row.
        for (y in 556 downTo 457) for (x in 363..444) {
            clipped.pixels[y * 1_280 + x] = if (y >= 505) clipped[x, y - 48] else -1
        }
        assertFalse(recognizer.recognize(clipped).complete)
    }

    @Test fun `selection imprint is a textual negative class not a particular guild or position`() {
        assertTrue(LabyrinthRelicRewardText.nonRelic("【咲恋救济院】的选择印记×1"))
        assertTrue(LabyrinthRelicRewardText.nonRelic("随机 印记"))
        assertFalse(LabyrinthRelicRewardText.nonRelic("咲恋救济院誓约"))
        assertFalse(LabyrinthRelicRewardText.nonRelic(null))
        assertTrue(LabyrinthRelicRewardText.ordinaryTitle("获得道具"))
        assertFalse(LabyrinthRelicRewardText.ordinaryTitle(null))
        assertTrue(LabyrinthRelicRewardText.seriesTitle("系列 效果一览"))
        assertFalse(LabyrinthRelicRewardText.seriesTitle("迷宫遗物效果"))
    }

    @Test fun `alpha coin rewards are a non-relic class independent of amount`() {
        for (text in listOf("阿尔法金币×3000", "阿尔法 金币 × 3000", "阿尔法金币x3000",
            "阿尔法金币X3000", "阿尔法金币3000", "阿尔法金币×200", "阿尔法金币")) {
            assertTrue(text, LabyrinthRelicRewardText.nonRelic(text))
        }
    }

    @Test fun `coin-like names and unknown rewards are not excluded as currency`() {
        for (text in listOf("阿尔法金币袋", "阿尔法金币的加护", "阿尔法金币×未知", "金币×3000",
            "未知奖励×3000", "3000", "高贵紫藤庐", "")) {
            assertFalse(text, LabyrinthRelicRewardText.nonRelic(text))
        }
    }

    @Test fun `reward popup reason is neutral about content and completion`() {
        val resolver = AndroidLabyrinthRelicRewardResolver(recognizer, submitTextRead = { _, _ -> })
        val hold = resolver.javaClass.getDeclaredMethod("hold", LabyrinthEntryFrameResult::class.java,
            LabyrinthRelicRewardObservation::class.java).apply { isAccessible = true }
        val pending = hold.invoke(resolver, result(), LabyrinthRelicRewardObservation()) as LabyrinthEntryFrameResult
        assertEquals("正在确认奖励标题", pending.observation.reason)
        for (complete in listOf(false, true)) {
            val receipt = LabyrinthRelicRewardObservation(kind = LabyrinthRelicRewardKind.RELIC_EFFECT,
                titleConfirmed = true, layoutComplete = complete, rowSlots = setOf("reward_6"))
            val resolved = hold.invoke(resolver, result(), receipt) as LabyrinthEntryFrameResult
            assertEquals(LabyrinthEntryPageState.ITEM_REWARD, resolved.observation.state)
            assertEquals("奖励结果弹窗", resolved.observation.reason)
        }
    }

    @Test fun `white panel without reward anchors does not become a receipt`() {
        val frame = frame(1_280, listOf("40003"), details = setOf(0))
        assertFalse(recognizer.candidate(frame, result(emptyMap())))
    }

    private fun result(scores: Map<String, Double> = mapOf(EntryAnchorId.ITEM_REWARD_INSTRUCTION to 0.9,
        EntryAnchorId.ITEM_REWARD_CLOSE to 0.9)) = LabyrinthEntryFrameResult(
        LabyrinthEntryPageObservation(LabyrinthEntryPageState.UNKNOWN, 0.0, emptyMap(), LabyrinthAnchorScores(scores)),
        emptyList(), 0,
    )

    private fun frame(width: Int, ids: List<String?>, details: Set<Int>): PixelImage {
        val scale = width / 1_920.0
        val frame = PixelImage(width, (1_080 * scale).toInt(), IntArray(width * (1_080 * scale).toInt()) { -1 })
        for (y in (50 * scale).toInt() until (120 * scale).toInt()) {
            for (x in (490 * scale).toInt() until (1430 * scale).toInt()) frame.pixels[y * width + x] = 0xff6496dc.toInt()
        }
        ids.forEachIndexed { index, id ->
            val cy = 356 + index * 195
            val left = (545 * scale).toInt()
            val top = ((cy - 61) * scale).toInt()
            val size = (123 * scale).toInt()
            if (id != null) {
                val source = templates.first { it.relicId == id }.image
                for (y in 0 until size) for (x in 0 until size) {
                    frame.pixels[(top + y) * width + left + x] = source[x * source.width / size, y * source.height / size]
                }
            } else {
                for (y in 0 until size) for (x in 0 until size) frame.pixels[(top + y) * width + left + x] = 0xff888888.toInt()
            }
            if (index in details) {
                for (y in ((cy - 27) * scale).toInt() until ((cy + 27) * scale).toInt()) {
                    for (x in (1297 * scale).toInt() until (1351 * scale).toInt()) {
                        val distance = (x / scale - 1324) * (x / scale - 1324) + (y / scale - cy) * (y / scale - cy)
                        if (distance in 23.0 * 23.0..27.0 * 27.0) frame.pixels[y * width + x] = 0xff6496dc.toInt()
                    }
                }
            }
        }
        return frame
    }

    private fun readImage(file: File): PixelImage {
        val image = requireNotNull(Class.forName("javax.imageio.ImageIO").getMethod("read", File::class.java).invoke(null, file))
        val width = image.javaClass.getMethod("getWidth").invoke(image) as Int
        val height = image.javaClass.getMethod("getHeight").invoke(image) as Int
        val pixels = IntArray(width * height)
        image.javaClass.getMethod("getRGB", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, IntArray::class.java,
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType).invoke(image, 0, 0, width, height, pixels, 0, width)
        return PixelImage(width, height, pixels)
    }
}
