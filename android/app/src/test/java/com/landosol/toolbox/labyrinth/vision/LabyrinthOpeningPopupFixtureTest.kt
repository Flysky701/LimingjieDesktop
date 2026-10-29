package com.landosol.toolbox.labyrinth.vision

import com.landosol.toolbox.clanbattle.recognition.PixelImage
import com.landosol.toolbox.labyrinth.labyrinthGenericConfirmDialogRect
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * 2026-09-17 user screenshots (scaled to 1080p): the 拉比林斯 opening grant popup (角色加入
 * 菈比莉斯塔) and the 迷宫大师 opening relic popup (迷宫遗物效果). Both must be recognised as
 * closeable pages so the opening chain keeps advancing.
 */
class LabyrinthOpeningPopupFixtureTest {
    private val projectRoot = generateSequence(File(".").canonicalFile, File::getParentFile).take(6)
        .firstOrNull { File(it, "android/app/build.gradle.kts").isFile }
    private val assetRoot = projectRoot?.let { File(it, "android/app/src/main/assets/resource-packs/cn-bilibili/vision") }
    private val fixtureRoot = projectRoot?.let { File(it, "android/app/src/test/resources/labyrinth") }

    @Test
    fun `relic effect popup classifies as item reward with the shared close button`() {
        assumeTrue(fixtureRoot?.isDirectory == true)
        val processor = LabyrinthEntryFrameProcessor(templates = loadShippedTemplates())
        val result = processor.process(readImage(File(fixtureRoot, "relic-effect-popup-20260917.png")))
        assertEquals(result.observation.stateScores.toString(), LabyrinthEntryPageState.ITEM_REWARD, result.observation.state)
        val close = requireNotNull(result.anchorMatches[EntryAnchorId.ITEM_REWARD_CLOSE])
        assertTrue("close ${close.score}", close.score >= 0.90)
        assertTrue(result.observation.anchorScores[EntryAnchorId.RELIC_EFFECT_TITLE] >= 0.90)
        assertTrue(result.observation.anchorScores[EntryAnchorId.RELIC_EFFECT_INSTRUCTION] >= 0.90)
    }

    @Test
    fun `no-reward confirm dialog after an event resolves to its single 确认 button`() {
        assumeTrue(fixtureRoot?.isDirectory == true)
        val processor = LabyrinthEntryFrameProcessor(templates = loadShippedTemplates())
        for (name in listOf(
            "event-no-reward-confirm-20260917.png",
            "event-no-reward-confirm-live-20260918.png",
            // 2026-09-18 live over the node map: the dialog was recognised for 279 actions while
            // the run kept swiping the map, because a NODE_SELECTION frame was routed to the node
            // search and never reached the handler that presses this button.
            "event-no-reward-over-map-20260918.jpg",
        )) {
            val result = processor.process(readImage(File(fixtureRoot, name)))
            val rect = requireNotNull(labyrinthGenericConfirmDialogRect(result))
            val cx = rect.left + rect.width / 2
            val cy = rect.top + rect.height / 2
            // 确认 centre ≈ (958, 741) at 1080p.
            assertTrue("$name confirm centre ($cx,$cy)", cx in 900..1020 && cy in 700..780)
            // The map behind the dialog owns the page classification; the dialog must still win.
            assertTrue(
                "$name page ${result.observation.state}",
                result.observation.state in setOf(
                    LabyrinthEntryPageState.UNKNOWN,
                    LabyrinthEntryPageState.NODE_SELECTION,
                    LabyrinthEntryPageState.NODE_MAP_VIEW,
                ),
            )
            // 2026-09-18 live: the map behind the dialog reads as NODE_SELECTION and the title
            // bar scores as the expiry popup; neither may turn this into a session block.
            assertEquals(
                com.landosol.toolbox.automation.session.SessionBlockKind.NONE,
                com.landosol.toolbox.labyrinth.labyrinthSessionBlockObservation(result).kind,
            )
        }

        // The end-confirmation (three-button) and relic-effect (own page) frames never qualify.
        val relic = processor.process(readImage(File(fixtureRoot, "relic-effect-popup-20260917.png")))
        assertEquals(null, labyrinthGenericConfirmDialogRect(relic))
    }

    @Test
    fun `empty effective filter page shows the 未搜索到该角色 notice`() {
        assumeTrue(fixtureRoot?.isDirectory == true)
        val processor = LabyrinthEntryFrameProcessor(templates = loadShippedTemplates())
        val result = processor.process(readImage(File(fixtureRoot, "effective-filter-empty-20260918.png")))
        assertEquals(LabyrinthEntryPageState.BATTLE_TEAM_SELECTION, result.observation.state)
        val notice = result.observation.anchorScores[EntryAnchorId.BATTLE_TEAM_ROSTER_EMPTY_NOTICE]
        assertTrue("notice $notice", notice >= 0.90)
        // A populated roster page must not show the notice.
        val populated = processor.process(readImage(File(fixtureRoot, "battle-team-roster-20260914.jpg")))
        assertTrue(populated.observation.anchorScores[EntryAnchorId.BATTLE_TEAM_ROSTER_EMPTY_NOTICE] < 0.50)
    }

    @Test
    fun `the final RESULT page is recognised even with 下一步 instead of 关闭`() {
        // 2026-09-20 bundle 110329: the run finished (区域5 · 36/36) and sat on the RESULT page for
        // the whole 325 s recording with actionCount frozen at 440, reporting "未知或低置信度页面,
        // 动作已禁用". The RESULT banner matched at 0.995, but the page score is min(banner, close
        // button) and this variant of the page carries 下一步 in the 关闭 pill, so the button read
        // 0.60. That put RUN_CLEAR_RESULT 0.073 above NODE_MAP_VIEW — the map's header and side
        // controls stay visible behind the page — against the 0.080 ambiguity margin.
        assumeTrue(fixtureRoot?.isDirectory == true)
        val processor = LabyrinthEntryFrameProcessor(templates = loadShippedTemplates())
        val result = processor.process(readImage(File(fixtureRoot, "run-result-next-button-20260920.jpg")))
        assertEquals(
            result.observation.stateScores.toString(),
            LabyrinthEntryPageState.RUN_CLEAR_RESULT,
            result.observation.state,
        )
        // The close-button anchor still has to give the planner somewhere to tap.
        val close = requireNotNull(result.anchorMatches[EntryAnchorId.RUN_RESULT_CLOSE_BUTTON])
        assertTrue("close ${close.score}", close.score >= 0.45)
        val centreX = close.rect.left + close.rect.width / 2
        val centreY = close.rect.top + close.rect.height / 2
        assertTrue("下一步 centre ($centreX,$centreY)", centreX in 900..1020 && centreY in 950..1040)
    }

    @Test
    fun `a map frame without the RESULT banner is still an ordinary map frame`() {
        // The override bypasses the ambiguity margin, so it must not fire on the map it sits over.
        assumeTrue(fixtureRoot?.isDirectory == true)
        val processor = LabyrinthEntryFrameProcessor(templates = loadShippedTemplates())
        val result = processor.process(readImage(File(fixtureRoot, "event-no-reward-over-map-20260918.jpg")))
        assertTrue(
            "banner ${result.observation.anchorScores[EntryAnchorId.RUN_RESULT_LOGO]}",
            result.observation.anchorScores[EntryAnchorId.RUN_RESULT_LOGO] < 0.92,
        )
        assertNotEquals(LabyrinthEntryPageState.RUN_CLEAR_RESULT, result.observation.state)
    }

    @Test
    fun `guild grant popup classifies as character joined`() {
        assumeTrue(fixtureRoot?.isDirectory == true)
        val processor = LabyrinthEntryFrameProcessor(templates = loadShippedTemplates())
        val result = processor.process(readImage(File(fixtureRoot, "guild-bonus-joined-20260917.png")))
        assertEquals(LabyrinthEntryPageState.CHARACTER_JOINED, result.observation.state)
        // The new relic-effect anchors must not fire on the joined popup.
        assertTrue(result.observation.anchorScores[EntryAnchorId.RELIC_EFFECT_TITLE] < 0.50)
    }

    private fun loadShippedTemplates(): LabyrinthEntryTemplateSet {
        val all = AndroidLabyrinthEntryTemplateLoader.TEMPLATE_PATHS + AndroidLabyrinthEntryTemplateLoader.OPTIONAL_TEMPLATE_PATHS
        val prefix = "resource-packs/cn-bilibili/vision/"
        return LabyrinthEntryTemplateSet(
            all.mapNotNull { (id, path) ->
                val file = File(assetRoot, path.removePrefix(prefix))
                if (file.isFile) id to readImage(file) else null
            }.toMap(),
        )
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
}
