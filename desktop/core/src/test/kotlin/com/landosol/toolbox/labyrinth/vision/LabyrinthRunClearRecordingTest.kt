package com.landosol.toolbox.labyrinth.vision

import com.landosol.toolbox.vision.PixelImage
import java.io.File
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Runs the shipped resource pack over the 2026-09-17 final settlement recording
 * (公主连结(28).mp4: RESULT → map → chest animation → 宝箱开封结果 → 获得道具 → loading → home)
 * and pins the page each key frame must classify as. Frames that legitimately have no page
 * (the chest animation, the loading screen) are asserted UNKNOWN so a future template cannot
 * silently start tapping them.
 */
class LabyrinthRunClearRecordingTest {
    private val projectRoot = locateProjectRoot()
    private val assetRoot = File(projectRoot, "desktop/core/src/main/resources/assets/resource-packs/cn-bilibili/vision")
    private val fixtureRoot = File(projectRoot, "desktop/core/src/test/resources/labyrinth/run-clear-20260917")

    @Test
    fun `every recording frame classifies as the page the settlement chain expects`() {
        assumeTrue(fixtureRoot.isDirectory)
        val processor = LabyrinthEntryFrameProcessor(templates = loadShippedTemplates())
        val expected = mapOf(
            "t0.5.jpg" to LabyrinthEntryPageState.RUN_CLEAR_RESULT,
            "t2.0.jpg" to LabyrinthEntryPageState.NODE_SELECTION,
            "t3.0.jpg" to LabyrinthEntryPageState.NODE_SELECTION,
            "t3.4.jpg" to LabyrinthEntryPageState.NODE_SELECTION,
            "t4.5.jpg" to LabyrinthEntryPageState.UNKNOWN,
            "t5.5.jpg" to LabyrinthEntryPageState.RUN_CLEAR_CHEST_RESULT,
            "t6.5.jpg" to LabyrinthEntryPageState.RUN_CLEAR_CHEST_RESULT,
            "t7.5.jpg" to LabyrinthEntryPageState.ITEM_REWARD,
            "t9.0.jpg" to LabyrinthEntryPageState.UNKNOWN,
            "t10.5.jpg" to LabyrinthEntryPageState.DAWN_REALM_HOME_IDLE,
        )
        val failures = mutableListOf<String>()
        val frames = fixtureRoot.listFiles { f -> f.name.endsWith(".jpg") }!!.sortedBy { it.name.removePrefix("t").removeSuffix(".jpg").toDouble() }
        val interesting = listOf(
            EntryAnchorId.RUN_RESULT_LOGO, EntryAnchorId.RUN_RESULT_CLOSE_BUTTON,
            EntryAnchorId.ITEM_REWARD_TITLE, EntryAnchorId.ITEM_REWARD_INSTRUCTION, EntryAnchorId.ITEM_REWARD_CLOSE,
            EntryAnchorId.NODE_HEADER_STANDARD, EntryAnchorId.NODE_CHARACTERS_STANDARD, EntryAnchorId.NODE_RETURN_STANDARD,
            EntryAnchorId.DAWN_START_STANDARD, EntryAnchorId.DAWN_TICKET_LABEL_STANDARD,
            EntryAnchorId.DATA_CONNECTING, EntryAnchorId.LOADING_INDICATOR,
            EntryAnchorId.JOINED_CLOSE_STANDARD, EntryAnchorId.SHOP_CLOSE,
            EntryAnchorId.RUN_CLEAR_CHEST_RESULT_TITLE, EntryAnchorId.RUN_CLEAR_CHEST_CONFIRM_BUTTON,
            EntryAnchorId.DAWN_HOME_MY_HOME_TAB,
        )
        val tabScores = mutableMapOf<String, Double>()
        for (file in frames) {
            val result = processor.process(readImage(file))
            val scores = result.observation.anchorScores
            val top = scores.values.entries.sortedByDescending { it.value }.take(5)
                .joinToString(" ") { "${it.key}=${"%.2f".format(it.value)}" }
            val focus = interesting.joinToString(" ") { "${it.substringAfterLast('.')}=${"%.2f".format(scores[it])}" }
            println("PROBE ${file.name}: page=${result.observation.state} conf=${"%.2f".format(result.observation.confidence)} | top: $top")
            val ranked = result.observation.stateScores.entries.sortedByDescending { it.value }.take(4)
                .joinToString(" ") { "${it.key}=${"%.2f".format(it.value)}" }
            println("PROBE       states: $ranked")
            println("PROBE       focus: $focus")
            result.anchorMatches[EntryAnchorId.ITEM_REWARD_CLOSE]?.let { println("PROBE       item_close_rect=${it.rect} score=${"%.2f".format(it.score)}") }
            result.anchorMatches[EntryAnchorId.RUN_RESULT_CLOSE_BUTTON]?.let { println("PROBE       result_close_rect=${it.rect} score=${"%.2f".format(it.score)}") }
            expected[file.name]?.let { want ->
                if (result.observation.state != want) failures += "${file.name}: expected $want got ${result.observation.state} ($ranked)"
            }
            tabScores[file.name] = scores[EntryAnchorId.DAWN_HOME_MY_HOME_TAB]
        }
        // The session-invalidation trigger must be strong on the labyrinth home and dead everywhere
        // else in the chain (RESULT, map, chest, item reward, loading), so the terminator never
        // taps the bottom bar on a page that has none.
        org.junit.Assert.assertTrue("tab on home ${tabScores["t10.5.jpg"]}", tabScores.getValue("t10.5.jpg") >= 0.90)
        for ((name, score) in tabScores) {
            if (name == "t10.5.jpg") continue
            org.junit.Assert.assertTrue("tab leaked on $name: $score", score < 0.50)
        }
        org.junit.Assert.assertTrue(failures.joinToString("\n"), failures.isEmpty())
        // The chest confirm button must resolve to a rect the session can tap.
        val chest = processor.process(readImage(File(fixtureRoot, "t5.5.jpg")))
        val confirm = requireNotNull(chest.anchorMatches[EntryAnchorId.RUN_CLEAR_CHEST_CONFIRM_BUTTON])
        org.junit.Assert.assertTrue("confirm score ${confirm.score}", confirm.score >= 0.90)
        val cx = confirm.rect.left + confirm.rect.width / 2
        val cy = confirm.rect.top + confirm.rect.height / 2
        org.junit.Assert.assertTrue("confirm centre ($cx,$cy)", cx in 900..1020 && cy in 913..1003)
    }

    private fun loadShippedTemplates(): LabyrinthEntryTemplateSet {
        val all = AndroidLabyrinthEntryTemplateLoader.TEMPLATE_PATHS + AndroidLabyrinthEntryTemplateLoader.OPTIONAL_TEMPLATE_PATHS
        val prefix = "resource-packs/cn-bilibili/vision/"
        val values = all.mapNotNull { (id, path) ->
            val file = File(assetRoot, path.removePrefix(prefix))
            if (file.isFile) id to readImage(file) else null
        }.toMap()
        return LabyrinthEntryTemplateSet(values)
    }

    private fun readImage(file: File): PixelImage {
        val imageIo = Class.forName("javax.imageio.ImageIO")
        val image = requireNotNull(imageIo.getMethod("read", File::class.java).invoke(null, file))
        val width = image.javaClass.getMethod("getWidth").invoke(image) as Int
        val height = image.javaClass.getMethod("getHeight").invoke(image) as Int
        val pixels = IntArray(width * height)
        image.javaClass.getMethod(
            "getRGB",
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType, IntArray::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
        ).invoke(image, 0, 0, width, height, pixels, 0, width)
        return PixelImage(width, height, pixels)
    }

    private fun locateProjectRoot(): File =
        generateSequence(File(".").canonicalFile, File::getParentFile).take(6)
            .firstOrNull { File(it, "desktop/core/build.gradle.kts").isFile }
            ?: error("Cannot locate project root")
}
