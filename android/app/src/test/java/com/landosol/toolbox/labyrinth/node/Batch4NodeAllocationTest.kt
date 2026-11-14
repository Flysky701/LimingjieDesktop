package com.landosol.toolbox.labyrinth.node

import com.landosol.toolbox.clanbattle.recognition.PixelImage
import com.landosol.toolbox.labyrinth.node.batch4baseline.Batch4NodeBaselineReference
import com.landosol.toolbox.labyrinth.vision.EntryPixelRect
import com.landosol.toolbox.labyrinth.vision.GradientTemplateMatcher
import java.io.File
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class Batch4NodeAllocationTest {
    @Test fun filteredMaximumPreservesFirstTieNaNSignedZeroAndOriginalEntries() {
        val values = listOf(-1.0, -0.0, 0.0, 1.0, Double.NEGATIVE_INFINITY,
            Double.POSITIVE_INFINITY, Double.NaN)
        for (a in values) for (b in values) for (c in values) {
            val scores = linkedMapOf("first" to a, "second" to b, "third" to c)
            for (accepted in listOf(emptySet(), setOf("first"), setOf("second", "third"), scores.keys)) {
                val old = scores.filterKeys { it in accepted }.maxByOrNull { it.value }
                val now = scores.strongestNodeEntryMatching { it in accepted }
                assertEquals(old?.key, now?.key)
                assertEquals(old?.value?.toRawBits(), now?.value?.toRawBits())
                if (now != null) assertSame(scores.entries.first { it.key == now.key }, now)
            }
        }
    }

    @Test fun realMapGoldensHaveExactlyTheSameCompleteOrderedNodeResults() {
        val templates = batch4NodeTemplates()
        val before = batch4TemplateSnapshot(templates)
        for (fixture in BATCH4_MAP_GOLDENS) {
            val frame = batch4MapFrame(fixture)
            val baseline = Batch4NodeBaselineReference()
            val optimized = LabyrinthNodeClassifier()
            val expected = baseline.classifyMapNodes(frame, templates)
            val actual = optimized.classifyMapNodes(frame, templates)
            batch4AssertNodeResultsExact(fixture, expected, actual)
            if (fixture.startsWith("node-")) assertTrue("real node golden must not be vacuous: $fixture", expected.isNotEmpty())
            if (fixture == "node-shop-relic-dashboard.png") assertEquals(
                listOf(LabyrinthNodeTypes.RELIC, LabyrinthNodeTypes.SHOP),
                actual.filter { it.isClickable }.map { it.blockType })
            assertEquals(baseline.lastSearchMode, optimized.lastSearchMode)
            assertEquals(baseline.lastSearchWindowCount, optimized.lastSearchWindowCount)
            assertEquals(baseline.viewportSignatureKey(frame), optimized.viewportSignatureKey(frame))
            println("BATCH4_NODE_GOLDEN_JSON=" + JsonObject(linkedMapOf(
                "scope" to JsonPrimitive("existing_real_map_goldens_not_original_736_frame_replay"),
                "fixture" to JsonPrimitive(fixture),
                "fixtureTransform" to JsonPrimitive(if (fixture == "node-shop-relic-dashboard.png")
                    "same_existing_golden_crop_x0_y23_1484x835_nearest_resize_1920x1080" else "none"),
                "width" to JsonPrimitive(frame.width), "height" to JsonPrimitive(frame.height),
                "mode" to JsonPrimitive(optimized.lastSearchMode),
                "windows" to JsonPrimitive(optimized.lastSearchWindowCount),
                "outputsExact" to JsonPrimitive(true), "nodes" to batch4NodesJson(actual),
            )))
        }
        batch4AssertTemplatesUnchanged(templates, before)
    }

    @Test fun sameFrameNewPixelsSizeChangesAndRetainedOldResultsStayExact() {
        val templates = batch4NodeTemplates()
        val first = batch4ReadImage(batch4Fixture("node-link-20260914.png"))
        val changed = PixelImage(first.width, first.height,
            IntArray(first.pixels.size) { first.pixels[it] xor 0x00ffffff })
        val resized = batch4Pattern(192, 108, 7)
        val old = Batch4NodeBaselineReference()
        val now = LabyrinthNodeClassifier()
        val expectedSaved = old.classifyMapNodes(first, templates)
        val actualSaved = now.classifyMapNodes(first, templates)
        val savedCopy = actualSaved.map { it.copy() }
        batch4AssertNodeResultsExact("first", expectedSaved, actualSaved)
        for ((index, frame) in listOf(first, first, changed, resized, first).withIndex()) {
            batch4AssertNodeResultsExact("temporal-$index", old.classifyMapNodes(frame, templates),
                now.classifyMapNodes(frame, templates))
            assertEquals(old.lastSearchMode, now.lastSearchMode)
            assertEquals(old.lastSearchWindowCount, now.lastSearchWindowCount)
            batch4AssertNodeResultsExact("retained-$index", savedCopy, actualSaved)
        }
    }

    @Test fun directedTypedFullAndMemoLadderRetainsExactlyTheBaselineOrderAndWindows() {
        val frame = batch4MapFrame("node-link-20260914.png")
        val templates = batch4NodeTemplates()
        val baseline = Batch4NodeBaselineReference()
        val optimized = LabyrinthNodeClassifier()
        val hint = NodeSearchHint(99901, 400, setOf(LabyrinthNodeTypes.LINK, LabyrinthNodeTypes.EVENT), 2..4)
        val modes = (0 until 6).map { index ->
            batch4AssertNodeResultsExact("ladder-$index", baseline.classifyMapNodes(frame, templates, hint),
                optimized.classifyMapNodes(frame, templates, hint))
            assertEquals(baseline.lastSearchWindowCount, optimized.lastSearchWindowCount)
            assertEquals(baseline.lastSearchMode, optimized.lastSearchMode)
            optimized.lastSearchMode
        }
        assertEquals(listOf("directed", "typed", "typed", "full", "full", "full"), modes)
    }

    @Test fun primitiveSignatureMatchesBoxedBaselineForOddTinyAndChangingDimensions() {
        val baseline = Batch4NodeBaselineReference()
        val optimized = LabyrinthNodeClassifier()
        val dimensions = listOf(1 to 1, 2 to 2, 7 to 5, 9 to 11, 23 to 17, 96 to 72,
            853 to 479, 1920 to 1080, 768 to 432, 1920 to 1080)
        for ((w, h) in dimensions) {
            val frame = batch4Pattern(w, h, w + h)
            assertEquals(baseline.viewportSignatureKey(frame), optimized.viewportSignatureKey(frame))
            val expected = batch4SignatureColors(baseline, frame)
            val actual = batch4SignatureColors(optimized, frame)
            assertArrayEquals(expected, actual)
            val saved = actual.copyOf()
            frame.pixels.fill(0)
            batch4SignatureColors(optimized, batch4Pattern(w, h, 11))
            assertArrayEquals("signature owns its array after caller/new-frame writes", saved, actual)
            assertNotSame(frame.pixels, actual)
        }
    }

    @Test fun exceptionFinallyClearsAllBorrowersButNotTheEntryOwnersPreparedFrame() {
        val frame = batch4Pattern(192, 108, 1)
        val template = batch4Pattern(40, 50, 2)
        val source = GradientTemplateMatcher().also { it.prepareFrame(frame) }
        val matcher = GradientTemplateMatcher(maxSamples = 2_400)
        val optimized = LabyrinthNodeClassifier(matcher, source)
        val baselineMatcher = GradientTemplateMatcher(maxSamples = 2_400)
        val baseline = Batch4NodeBaselineReference(baselineMatcher, source)
        val throwing = Batch4ThrowingEntriesMap(linkedMapOf("node.relic.active" to template))
        val templates = NodeTemplateSet(throwing)
        throwing.failEntries = true
        for (classifier in listOf<Any>(baseline, optimized)) {
            try {
                if (classifier is LabyrinthNodeClassifier) classifier.classifyMapNodes(frame, templates)
                else (classifier as Batch4NodeBaselineReference).classifyMapNodes(frame, templates)
                fail("expected a filter exception after sharing prepared features")
            } catch (expected: IllegalStateException) {
                assertEquals("batch4 forced template filter exception", expected.message)
            }
            for (name in listOf("matcher", "proposalMatcher", "refinementMatcher")) {
                val borrowed = batch4Field(classifier, name) as GradientTemplateMatcher
                assertFalse("$name must be cleared in finally", borrowed.hasPreparedFrame(frame))
            }
            assertTrue("entry owner stays prepared", source.hasPreparedFrame(frame))
        }
        throwing.failEntries = false
        batch4AssertNodeResultsExact("after-exception", baseline.classifyMapNodes(frame, templates),
            optimized.classifyMapNodes(frame, templates))
        assertTrue(source.hasPreparedFrame(frame))
        source.clearPreparedFrame()
    }
}

private class Batch4ThrowingEntriesMap(private val backing: Map<String, PixelImage>) :
    Map<String, PixelImage> by backing {
    var failEntries = false
    override val entries: Set<Map.Entry<String, PixelImage>>
        get() {
            if (failEntries) error("batch4 forced template filter exception")
            return backing.entries
        }
}

internal val BATCH4_MAP_GOLDENS = listOf("node-link-20260914.png", "node-target-missed-20260916.jpg",
    "node-shop-relic-dashboard.png", "final-boss-active-20260909.jpg")

internal fun batch4ProjectRoot(): File {
    var current = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
    repeat(8) {
        if (File(current, "android/app/build.gradle.kts").isFile) return current
        current = current.parentFile ?: error("Cannot locate batch4 project root")
    }
    error("Cannot locate batch4 project root")
}
internal fun batch4Fixture(name: String) = File(batch4ProjectRoot(), "android/app/src/test/resources/labyrinth/$name")
internal fun batch4MapFrame(name: String): PixelImage {
    val image = batch4ReadImage(batch4Fixture(name))
    if (name != "node-shop-relic-dashboard.png") return image
    // Exactly the existing LabyrinthNodeScreenshotBatchDiagnosticTest golden transform; dashboard
    // chrome is not a native map frame and must not enter node matching.
    return PixelImage(1920, 1080, IntArray(1920 * 1080) { index ->
        image[(index % 1920) * 1484 / 1920, 23 + (index / 1920) * 835 / 1080]
    })
}
internal fun batch4ReadImage(file: File): PixelImage {
    val imageIo = Class.forName("javax.imageio.ImageIO")
    val image = requireNotNull(imageIo.getMethod("read", File::class.java).invoke(null, file))
    val width = image.javaClass.getMethod("getWidth").invoke(image) as Int
    val height = image.javaClass.getMethod("getHeight").invoke(image) as Int
    val pixels = IntArray(width * height)
    image.javaClass.getMethod("getRGB", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
        Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, IntArray::class.java,
        Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
        .invoke(image, 0, 0, width, height, pixels, 0, width)
    return PixelImage(width, height, pixels)
}
internal fun batch4Pattern(w: Int, h: Int, seed: Int) = PixelImage(w, h,
    IntArray(w * h) { 0xff000000.toInt() or ((it * 7919 + seed * 104729) and 0xffffff) })
internal fun batch4Field(owner: Any, name: String): Any? = owner.javaClass.getDeclaredField(name)
    .also { it.isAccessible = true }.get(owner)
internal fun batch4SignatureColors(owner: Any, frame: PixelImage): IntArray {
    val method = owner.javaClass.getDeclaredMethod("viewportSignature", PixelImage::class.java)
        .also { it.isAccessible = true }
    return batch4Field(requireNotNull(method.invoke(owner, frame)), "colors") as IntArray
}
internal fun batch4AssertNodeResultsExact(label: String, old: List<NodeClassification>, now: List<NodeClassification>) {
    assertEquals("$label complete ordered DTO list", old, now)
    assertEquals(old.size, now.size)
    for (i in old.indices) {
        val a = old[i]; val b = now[i]
        val oldDoubles = listOf(a.confidence, a.cyanGlowScore, a.cyanGlowRowCoverage, a.purpleGlowScore,
            a.purpleGlowRowCoverage, a.purpleGlowSideScore, a.purpleGlowLowerScore, a.colorRoiScore, a.typeConfidence)
        val newDoubles = listOf(b.confidence, b.cyanGlowScore, b.cyanGlowRowCoverage, b.purpleGlowScore,
            b.purpleGlowRowCoverage, b.purpleGlowSideScore, b.purpleGlowLowerScore, b.colorRoiScore, b.typeConfidence)
        assertEquals("$label node-$i exact IEEE bits", oldDoubles.map { it.toRawBits() },
            newDoubles.map { it.toRawBits() })
    }
}
internal fun batch4NodesJson(nodes: List<NodeClassification>) = JsonArray(nodes.map { node ->
    JsonObject(linkedMapOf("column" to JsonPrimitive(node.column), "row" to JsonPrimitive(node.row),
        "blockType" to JsonPrimitive(node.blockType), "isClickable" to JsonPrimitive(node.isClickable),
        "templateId" to JsonPrimitive(node.templateId),
        "screenRect" to (node.screenRect?.let { JsonObject(linkedMapOf("left" to JsonPrimitive(it.left),
            "top" to JsonPrimitive(it.top), "width" to JsonPrimitive(it.width), "height" to JsonPrimitive(it.height))) }
            ?: kotlinx.serialization.json.JsonNull),
        "confidence" to JsonPrimitive(node.confidence), "typeConfidence" to JsonPrimitive(node.typeConfidence),
        "cyanGlowScore" to JsonPrimitive(node.cyanGlowScore), "cyanGlowRowCoverage" to JsonPrimitive(node.cyanGlowRowCoverage),
        "purpleGlowScore" to JsonPrimitive(node.purpleGlowScore), "purpleGlowRowCoverage" to JsonPrimitive(node.purpleGlowRowCoverage),
        "purpleGlowSideScore" to JsonPrimitive(node.purpleGlowSideScore), "purpleGlowLowerScore" to JsonPrimitive(node.purpleGlowLowerScore),
        "colorRoiScore" to JsonPrimitive(node.colorRoiScore)))
})
internal data class Batch4TemplateSnapshot(val images: List<PixelImage>, val arrays: List<IntArray>, val copies: List<IntArray>)
internal fun batch4TemplateSnapshot(templates: NodeTemplateSet): Batch4TemplateSnapshot {
    val images = templates.templates.values.toList() + templates.gradientTemplates.values + templates.platformTemplates.values
    return Batch4TemplateSnapshot(images, images.map { it.pixels }, images.map { it.pixels.copyOf() })
}
internal fun batch4AssertTemplatesUnchanged(templates: NodeTemplateSet, before: Batch4TemplateSnapshot) {
    val after = batch4TemplateSnapshot(templates)
    for (i in before.images.indices) {
        assertSame(before.images[i], after.images[i]); assertSame(before.arrays[i], after.arrays[i])
        assertArrayEquals(before.copies[i], after.arrays[i])
    }
}
internal fun batch4NodeTemplates(): NodeTemplateSet {
    val files = linkedMapOf(
        "node.normal_battle.active" to "node_normal_battle_active.png",
        "node.normal_battle.active.bottom" to "node_normal_battle_active_bottom.png",
        "node.normal_battle.inactive" to "node_normal_battle_inactive.png",
        "node.normal_battle.inactive.bottom" to "node_normal_battle_inactive_bottom.png",
        "node.normal_battle.inactive.video" to "node_normal_battle_inactive_video.png",
        "node.ex_battle.inactive" to "node_ex_battle_inactive.png",
        "node.boss.yellow" to "node_boss_yellow.png", "node.boss.dragon" to "node_boss_dragon.png",
        "node.boss.platform" to "node_boss_platform.png", "node.clear" to "node_clear.png",
        "node.relic.active" to "node_relic_active.png", "node.relic.inactive" to "node_relic_inactive.png",
        "node.relic.map" to "node_relic_map.png", "node.event.active" to "node_event_active.png",
        "node.event.active.bottom" to "node_event_active_bottom.png", "node.event.inactive" to "node_event_inactive.png",
        "node.event.inactive.bottom" to "node_event_inactive_bottom.png", "node.link.active" to "node_link_active.png",
        "node.link.active.map" to "node_link_map.png", "node.link.inactive" to "node_link_inactive.png",
        "node.shop.active" to "node_shop_active.png")
    return NodeTemplateSet(files.mapValues { (_, name) -> batch4ReadImage(File(batch4ProjectRoot(),
        "android/app/src/main/assets/resource-packs/cn-bilibili/vision/$name")) })
}
