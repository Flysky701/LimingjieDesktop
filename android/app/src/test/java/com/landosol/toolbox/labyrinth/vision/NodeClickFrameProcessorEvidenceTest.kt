package com.landosol.toolbox.labyrinth.vision

import com.landosol.toolbox.clanbattle.recognition.PixelImage
import com.landosol.toolbox.labyrinth.node.AndroidLabyrinthNodeTemplateLoader
import com.landosol.toolbox.labyrinth.node.LabyrinthNodeActionPlanner
import com.landosol.toolbox.labyrinth.node.LabyrinthNodeClassifier
import com.landosol.toolbox.labyrinth.node.LabyrinthNodeTypes
import com.landosol.toolbox.labyrinth.node.NodeFrameEvidenceSource
import com.landosol.toolbox.labyrinth.node.NodeSearchHint
import com.landosol.toolbox.labyrinth.node.NodeTemplateSet
import com.landosol.toolbox.labyrinth.node.finalBossPlatformClickRect
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Mandatory producer integration tests: real page anchors, classifier, ROI revalidation and Boss
 * locator, using only shipped assets and checked-in screenshots. No external crop tree, assumption
 * skips, fabricated frame results, or reflection into recognition state.
 */
class NodeClickFrameProcessorEvidenceTest {
    @Test
    fun `actual processor forwards search memo revalidation and tracked evidence`() {
        val fixture = ProcessorFixture()
        val memo = warmThroughRealMemo(fixture)
        assertEquals(NodeFrameEvidenceSource.MEMO_REVALIDATED, memo.nodeEvidenceSource)
        assertEquals(0, memo.nodeSearchWindowCount)
        assertTrue(memo.nodeRevalidationCount > 0)

        // Route feedback, not a fabricated classifier result, enables the ordinary tracked path.
        fixture.hint = fixture.hint.copy(matchedTargetBlockId = fixture.hint.targetBlockId)
        val tracked = fixture.processNodeFrame()
        assertNodePage(tracked)
        assertClassifierEvidence(fixture, tracked)
        assertEquals("tracked", tracked.nodeSearchMode)
        assertEquals(NodeFrameEvidenceSource.TRACKED, tracked.nodeEvidenceSource)
        assertTrue(tracked.nodeEvidenceSource.currentFrameVerified)
        assertEquals(memo.nodeClassifications.size, tracked.nodeRevalidationCount)
        assertEquals(tracked.nodeClassifications.size, tracked.nodeSearchWindowCount)
        assertNotSame(memo.nodeClassifications, tracked.nodeClassifications)
    }

    @Test
    fun `deferred map does not inherit positive ROI evidence from the previous frame`() {
        val fixture = ProcessorFixture()
        val memo = warmThroughRealMemo(fixture)
        fixture.consumable = false

        val deferred = fixture.processNodeFrame()

        assertNodePage(deferred)
        assertNoNodeEvidence(deferred, NODE_SEARCH_MODE_DEFERRED)
        // Deferral intentionally preserves classifier state. FrameResult must nevertheless say NONE.
        assertEquals(memo.nodeEvidenceSource, fixture.nodeClassifier.lastEvidenceSource)
        assertEquals(memo.nodeRevalidationCount, fixture.nodeClassifier.lastRevalidationCount)
        assertTrue(deferred.matchedFeatures.isNotEmpty())
        assertTrue(deferred.anchorMatches.isNotEmpty())

        fixture.consumable = true
        val resumed = fixture.processNodeFrame()
        assertNodePage(resumed)
        assertClassifierEvidence(fixture, resumed)
        assertTrue(resumed.nodeEvidenceSource.currentFrameVerified)
        assertTrue(resumed.nodeClassifications.isNotEmpty())
    }

    @Test
    fun `map with scanning not requested publishes NONE and clears classifier diagnostics`() {
        val fixture = ProcessorFixture()
        warmThroughRealMemo(fixture)
        fixture.scanRequested = false

        val notRequested = fixture.processNodeFrame()

        assertNodePage(notRequested)
        assertNoNodeEvidence(notRequested, "none")
        assertEquals(NodeFrameEvidenceSource.NONE, fixture.nodeClassifier.lastEvidenceSource)
        assertEquals(0, fixture.nodeClassifier.lastRevalidationCount)
        assertEquals(0, fixture.nodeClassifier.lastSearchWindowCount)

        fixture.scanRequested = true
        val restarted = fixture.processNodeFrame()
        assertNodePage(restarted)
        assertClassifierEvidence(fixture, restarted)
        assertEquals(NodeFrameEvidenceSource.SEARCH, restarted.nodeEvidenceSource)
        assertTrue("a disabled scan must not leave reusable old proof", restarted.nodeSearchWindowCount > 0)
        assertEquals(0, restarted.nodeRevalidationCount)
    }

    @Test
    fun `real non map popup cannot inherit node evidence or ROI counters`() {
        val fixture = ProcessorFixture()
        warmThroughRealMemo(fixture)

        val popup = fixture.processor.process(copyFrame(relicResultFrame))

        assertEquals(diagnostic(popup), LabyrinthEntryPageState.ITEM_REWARD, popup.observation.state)
        assertTrue("the supplied popup must really be recognized",
            popup.observation.anchorScores[EntryAnchorId.RELIC_EFFECT_RESULT_TITLE] >= 0.80)
        assertTrue("the popup must expose its own close control",
            popup.observation.anchorScores[EntryAnchorId.RELIC_EFFECT_RESULT_CLOSE] >= 0.80)
        assertNoNodeEvidence(popup, "none")
        assertEquals(NodeFrameEvidenceSource.NONE, fixture.nodeClassifier.lastEvidenceSource)
        assertEquals(0, fixture.nodeClassifier.lastRevalidationCount)

        val backOnMap = fixture.processNodeFrame()
        assertNodePage(backOnMap)
        assertClassifierEvidence(fixture, backOnMap)
        assertEquals(NodeFrameEvidenceSource.SEARCH, backOnMap.nodeEvidenceSource)
        assertTrue(backOnMap.nodeSearchWindowCount > 0)
    }

    @Test
    fun `dedicated Boss locator publishes current evidence with zero ordinary windows`() {
        // This case uses the complete shipped node pack, including the actual Boss platform asset.
        val fixture = ProcessorFixture(shippedNodeTemplates)
        val ordinary = fixture.processNodeFrame()
        assertNodePage(ordinary)
        assertClassifierEvidence(fixture, ordinary)
        assertEquals(NodeFrameEvidenceSource.SEARCH, ordinary.nodeEvidenceSource)
        assertTrue(ordinary.nodeSearchWindowCount > 0)

        fixture.finalBoss = true
        val boss = fixture.processor.process(copyFrame(bossFrame))

        assertNodePage(boss)
        assertEquals("final-boss-platform", boss.nodeSearchMode)
        assertEquals(NodeFrameEvidenceSource.FINAL_BOSS_PLATFORM, boss.nodeEvidenceSource)
        assertTrue(boss.nodeEvidenceSource.currentFrameVerified)
        assertEquals(0, boss.nodeSearchWindowCount)
        assertEquals(0, boss.nodeRevalidationCount)
        assertEquals(0L, boss.stageMillis["nodes"])
        assertTrue(boss.nodeClassifications.isEmpty())
        assertEquals(NodeFrameEvidenceSource.NONE, fixture.nodeClassifier.lastEvidenceSource)
        assertEquals(1, boss.finalBossPlatforms.size)
        assertTrue("real active platform must remain reliable: ${boss.finalBossPlatforms}",
            boss.finalBossPlatforms.single().reliable)
        val clickRect = requireNotNull(finalBossPlatformClickRect(
            boss.finalBossPlatforms, boss.frameWidth, boss.frameHeight, null,
        )) { "Boss evidence has no usable location: ${boss.finalBossPlatformCandidates}" }
        val (x, y) = LabyrinthNodeActionPlanner().getBaseClickPosition(clickRect)
        assertTrue("Boss click must stay on the shipped screenshot platform: $x,$y",
            x in 1450..1520 && y in 600..644)

        fixture.consumable = false
        val deferredBoss = fixture.processor.process(copyFrame(bossFrame))
        assertNodePage(deferredBoss)
        assertNoNodeEvidence(deferredBoss, NODE_SEARCH_MODE_DEFERRED)

        fixture.consumable = true
        fixture.finalBoss = false
        val ordinaryAgain = fixture.processNodeFrame()
        assertNodePage(ordinaryAgain)
        assertClassifierEvidence(fixture, ordinaryAgain)
        assertEquals(NodeFrameEvidenceSource.SEARCH, ordinaryAgain.nodeEvidenceSource)
        assertTrue(ordinaryAgain.nodeSearchWindowCount > 0)
        assertTrue(ordinaryAgain.finalBossPlatforms.isEmpty())
        assertTrue(ordinaryAgain.finalBossPlatformCandidates.isEmpty())
    }

    private class ProcessorFixture(nodeTemplates: NodeTemplateSet = linkNodeTemplates) {
        var scanRequested = true
        var consumable = true
        var finalBoss = false
        // A real session can have a route target but no camera fit or acquisition yet. Keeping
        // that feedback unconfirmed lets the real scan ladder reach its memo path naturally.
        var hint = NodeSearchHint(
            targetBlockId = 99902L,
            expectedCenterX = null,
            expectedTypes = setOf(LabyrinthNodeTypes.LINK),
            reachableColumns = 1..5,
        )
        private val matcher = GradientTemplateMatcher()
        val nodeClassifier = LabyrinthNodeClassifier(sharedFrameFeatures = matcher)
        val processor = LabyrinthEntryFrameProcessor(
            templates = shippedEntryTemplates,
            matcher = matcher,
            nodeClassifier = nodeClassifier,
            nodeTemplates = nodeTemplates,
            nodeSearchHint = { hint },
            nodeScanRequested = { scanRequested },
            nodeScanConsumable = { consumable },
            finalBossOnly = { finalBoss },
        )

        fun processNodeFrame(): LabyrinthEntryFrameResult = processor.process(copyFrame(nodeFrame))
    }

    private fun warmThroughRealMemo(fixture: ProcessorFixture): LabyrinthEntryFrameResult {
        val first = fixture.processNodeFrame()
        assertNodePage(first)
        assertClassifierEvidence(fixture, first)
        assertEquals(NodeFrameEvidenceSource.SEARCH, first.nodeEvidenceSource)
        assertTrue("initial frame must really search: ${diagnostic(first)}", first.nodeSearchWindowCount > 0)
        assertEquals(0, first.nodeRevalidationCount)
        assertTrue("checked-in node screenshot must produce a live LINK: ${first.nodeClassifications}",
            first.nodeClassifications.any { it.blockType == LabyrinthNodeTypes.LINK && it.isClickable })
        var previous = first
        val trace = mutableListOf(diagnostic(first))
        repeat(6) {
            val current = fixture.processNodeFrame()
            trace += diagnostic(current)
            assertNodePage(current)
            assertClassifierEvidence(fixture, current)
            assertTrue("ordinary node proof must be current: $trace", current.nodeEvidenceSource.currentFrameVerified)
            assertFalse(current.nodeClassifications.isEmpty())
            if (current.nodeEvidenceSource == NodeFrameEvidenceSource.MEMO_REVALIDATED) {
                assertEquals(0, current.nodeSearchWindowCount)
                assertEquals(previous.nodeClassifications.size, current.nodeRevalidationCount)
                assertEquals(previous.nodeClassifications.size, current.nodeClassifications.size)
                assertTrue(current.nodeRevalidationCount > 0)
                assertNotSame("the producer must expose new ROI results, not the old list",
                    previous.nodeClassifications, current.nodeClassifications)
                current.nodeClassifications.zip(previous.nodeClassifications).forEach { (now, old) ->
                    assertNotSame("each ROI was measured on this capture", old, now)
                }
                return current
            }
            previous = current
        }
        error("Required real memo revalidation was not reached; no skip or fabricated result: $trace")
    }

    private fun assertClassifierEvidence(fixture: ProcessorFixture, result: LabyrinthEntryFrameResult) {
        assertEquals(diagnostic(result), fixture.nodeClassifier.lastEvidenceSource, result.nodeEvidenceSource)
        assertEquals(diagnostic(result), fixture.nodeClassifier.lastSearchWindowCount, result.nodeSearchWindowCount)
        assertEquals(diagnostic(result), fixture.nodeClassifier.lastRevalidationCount, result.nodeRevalidationCount)
        assertTrue(result.nodeViewportSignature.isNotBlank())
        assertTrue(result.nodeViewportPixels != null)
    }

    private fun assertNodePage(result: LabyrinthEntryFrameResult) {
        assertEquals(diagnostic(result), LabyrinthEntryPageState.NODE_SELECTION, result.observation.state)
        assertNull("fixture must not be hidden by a movement dialog", result.nodeMoveConfirmation)
    }

    private fun assertNoNodeEvidence(result: LabyrinthEntryFrameResult, expectedMode: String) {
        assertEquals(diagnostic(result), expectedMode, result.nodeSearchMode)
        assertEquals(NodeFrameEvidenceSource.NONE, result.nodeEvidenceSource)
        assertFalse(result.nodeEvidenceSource.currentFrameVerified)
        assertEquals(0, result.nodeSearchWindowCount)
        assertEquals(0, result.nodeRevalidationCount)
        assertEquals(0L, result.stageMillis["nodes"])
        assertTrue(result.nodeClassifications.isEmpty())
        assertTrue(result.finalBossPlatforms.isEmpty())
        assertTrue(result.finalBossPlatformCandidates.isEmpty())
        assertEquals("", result.nodeViewportSignature)
        assertNull(result.nodeViewportPixels)
    }

    private fun diagnostic(result: LabyrinthEntryFrameResult): String =
        "page=${result.observation.state} confidence=${result.observation.confidence} " +
            "mode=${result.nodeSearchMode} source=${result.nodeEvidenceSource} " +
            "windows=${result.nodeSearchWindowCount} rois=${result.nodeRevalidationCount} " +
            "nodes=${result.nodeClassifications.size} features=${result.matchedFeatures}"

    private companion object {
        val projectRoot: File by lazy {
            var current = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
            repeat(8) {
                if (File(current, "android/app/build.gradle.kts").isFile) return@lazy current
                current = current.parentFile ?: error("Cannot locate project resources from ${System.getProperty("user.dir")}")
            }
            error("Cannot locate project resources from ${System.getProperty("user.dir")}")
        }
        val assetsRoot: File by lazy { File(projectRoot, "android/app/src/main/assets") }
        val shippedEntryTemplates: LabyrinthEntryTemplateSet by lazy {
            val required = AndroidLabyrinthEntryTemplateLoader.TEMPLATE_PATHS
                .mapValues { (_, path) -> readRequiredImage(File(assetsRoot, path)) }
            val optional = AndroidLabyrinthEntryTemplateLoader.OPTIONAL_TEMPLATE_PATHS
                .filterValues { File(assetsRoot, it).isFile }
                .mapValues { (_, path) -> readRequiredImage(File(assetsRoot, path)) }
            for (id in listOf(EntryAnchorId.RELIC_EFFECT_RESULT_TITLE, EntryAnchorId.RELIC_EFFECT_RESULT_CLOSE)) {
                check(id in optional) { "Required popup template missing: ${AndroidLabyrinthEntryTemplateLoader.OPTIONAL_TEMPLATE_PATHS[id]}" }
            }
            LabyrinthEntryTemplateSet(required + optional)
        }
        val shippedNodeTemplates: NodeTemplateSet by lazy {
            NodeTemplateSet(AndroidLabyrinthNodeTemplateLoader.TEMPLATE_PATHS
                .mapValues { (_, path) -> readRequiredImage(File(assetsRoot, path)) })
        }
        // Restrict this producer fixture to the real LINK asset family, not fabricated pixels or
        // classifications. Classifier-wide ambiguity is covered separately; here the same template
        // family is used for search and ROI revalidation, so the memo transition is reproducible.
        val linkNodeTemplates: NodeTemplateSet by lazy {
            val templates = shippedNodeTemplates.templates.filterKeys { it.startsWith("node.link.") }
            check(templates.keys.containsAll(setOf("node.link.active", "node.link.active.map", "node.link.inactive")))
            NodeTemplateSet(templates)
        }
        val nodeFrame: PixelImage by lazy { fixtureImage("node-link-20260914.png") }
        val bossFrame: PixelImage by lazy { fixtureImage("final-boss-active-20260909.jpg") }
        val relicResultFrame: PixelImage by lazy { fixtureImage("relic-effect-result-20260922.png") }

        fun fixtureImage(name: String): PixelImage =
            readRequiredImage(File(projectRoot, "android/app/src/test/resources/labyrinth/$name"))

        fun copyFrame(frame: PixelImage): PixelImage = PixelImage(frame.width, frame.height, frame.pixels.copyOf())

        fun readRequiredImage(file: File): PixelImage {
            check(file.isFile) { "Required checked-in image missing: ${file.absolutePath}" }
            val image = requireNotNull(Class.forName("javax.imageio.ImageIO")
                .getMethod("read", File::class.java).invoke(null, file)) {
                "Cannot decode required checked-in image: ${file.absolutePath}"
            }
            val width = image.javaClass.getMethod("getWidth").invoke(image) as Int
            val height = image.javaClass.getMethod("getHeight").invoke(image) as Int
            val pixels = IntArray(width * height)
            image.javaClass.getMethod(
                "getRGB", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, IntArray::class.java,
                Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
            ).invoke(image, 0, 0, width, height, pixels, 0, width)
            return PixelImage(width, height, pixels)
        }
    }
}
