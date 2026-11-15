package com.landosol.toolbox.labyrinth.node

import com.landosol.toolbox.clanbattle.recognition.PixelImage
import com.landosol.toolbox.labyrinth.vision.EntryPixelRect
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Real classifier/asset regressions. Reflection only prepares private search memo geometry and
 * excludes the separate tracked fast path; classification, viewport comparison, image preparation,
 * and classifyMapNodes all execute production code. Resources are required, never skipped.
 */
class NodeClickClassifierEvidenceTest {
    @Test
    fun `twelve memo reuses recheck every crop without buying another full search`() {
        val fixture = fixtureData
        val classifier = LabyrinthNodeClassifier()
        val nodes = fixture.memoNodes.take(2)
        assertEquals("fixture must exercise multiple memo crops", 2, nodes.size)
        val memo = installMemo(classifier, fixture.frame, fixture.templates, nodes)
        var previous: List<NodeClassification> = nodes
        var roiChecks = 0
        var searchWindows = 0
        repeat(12) { index ->
            val frame = copyFrame(fixture.frame)
            val expected = currentClassifications(frame, nodes, fixture.templates)
            val result = classifier.classifyMapNodes(frame, fixture.templates)
            assertEquals("reuse ${index + 1}", expected, result)
            assertNotSame("memo list is not current evidence", nodes, result)
            assertNotSame("each capture needs a new result", previous, result)
            result.zip(nodes).forEach { (now, old) -> assertNotSame(old, now) }
            assertEquals("full", classifier.lastSearchMode)
            assertEquals(NodeFrameEvidenceSource.MEMO_REVALIDATED, classifier.lastEvidenceSource)
            assertTrue(classifier.lastEvidenceSource.currentFrameVerified)
            assertEquals(0, classifier.lastSearchWindowCount)
            assertEquals(nodes.size, classifier.lastRevalidationCount)
            assertEquals(index + 1, field(classifier, "scanMemoFrames"))
            assertTrue(field(classifier, "scanMemo") === memo)
            roiChecks += classifier.lastRevalidationCount
            searchWindows += classifier.lastSearchWindowCount
            previous = result
        }
        assertEquals(24, roiChecks)
        assertEquals(0, searchWindows)
        // Preserve the existing cap: the thirteenth call must really search again.
        classifier.classifyMapNodes(copyFrame(fixture.frame), fixture.templates)
        assertEquals(NodeFrameEvidenceSource.SEARCH, classifier.lastEvidenceSource)
        assertEquals("full", classifier.lastSearchMode)
        assertEquals(fixture.fullSearchWindows, classifier.lastSearchWindowCount)
        assertEquals(0, classifier.lastRevalidationCount)
        assertNotSame(memo, field(classifier, "scanMemo"))
    }

    @Test
    fun `same type and activation return current scores rather than old memo values`() {
        val fixture = fixtureData
        val old = fixture.activeLink
        val variant = scoreVariant(fixture, old)
        val classifier = LabyrinthNodeClassifier()
        val oldList = listOf(old)
        installMemo(classifier, fixture.frame, fixture.templates, oldList)
        assertStableSignature(classifier, fixture.frame, variant.frame)
        val result = classifier.classifyMapNodes(variant.frame, fixture.templates)
        val expected = variant.classification.copy(column = old.column, row = old.row)
        assertEquals(old.blockType, expected.blockType)
        assertEquals(old.isClickable, expected.isClickable)
        assertTrue("fixture must actually change a score", scoresDiffer(old, expected))
        assertEquals(listOf(expected), result)
        assertNotSame(oldList, result)
        assertNotSame(old, result.single())
        assertEquals(NodeFrameEvidenceSource.MEMO_REVALIDATED, classifier.lastEvidenceSource)
        assertEquals(1, classifier.lastRevalidationCount)
        assertEquals(0, classifier.lastSearchWindowCount)
    }

    @Test
    fun `a later memo capture uses the newest score not the first refreshed score`() {
        val fixture = fixtureData
        val old = fixture.activeLink
        val variant = scoreVariant(fixture, old)
        val classifier = LabyrinthNodeClassifier()
        installMemo(classifier, fixture.frame, fixture.templates, listOf(old))
        val tinted = classifier.classifyMapNodes(variant.frame, fixture.templates).single()
        val restored = classifier.classifyMapNodes(copyFrame(fixture.frame), fixture.templates).single()
        assertEquals(variant.classification.copy(column = old.column, row = old.row), tinted)
        assertEquals(old, restored)
        assertTrue(scoresDiffer(tinted, restored))
        assertNotSame(tinted, restored)
        assertNotSame(old, restored)
        assertEquals(NodeFrameEvidenceSource.MEMO_REVALIDATED, classifier.lastEvidenceSource)
        assertEquals(1, classifier.lastRevalidationCount)
        assertEquals(0, classifier.lastSearchWindowCount)
    }

    @Test
    fun `activation loss hidden from viewport samples cannot retain an old active node`() {
        val fixture = fixtureData
        val old = fixture.activeLink
        val rect = requireNotNull(old.screenRect)
        val erased = editCropOutsideViewportSamples(fixture.frame, rect) { _, _, _ -> 0xff101010.toInt() }
        val classifier = LabyrinthNodeClassifier()
        val memo = installMemo(classifier, fixture.frame, fixture.templates, listOf(old))
        assertStableSignature(classifier, fixture.frame, erased)
        val current = LabyrinthNodeClassifier().classifyNode(erased, rect, fixture.templates)
        assertFalse("erased crop unexpectedly active: $current", current?.isClickable == true)
        val result = classifier.classifyMapNodes(erased, fixture.templates)
        assertEquals(NodeFrameEvidenceSource.SEARCH, classifier.lastEvidenceSource)
        assertEquals(1, classifier.lastRevalidationCount)
        assertTrue("changed evidence must trigger real search", classifier.lastSearchWindowCount > 0)
        assertNotSame(memo, field(classifier, "scanMemo"))
        assertFalse("old active crop survived: $result", result.any { it.screenRect == rect && it.isClickable })
        assertFalse(result.any { it === old })
    }

    @Test
    fun `different active node beneath an unchanged sampled signature invalidates memo semantics`() {
        val fixture = fixtureData
        val old = fixture.activeLink
        val replacement = semanticVariant(fixture, old)
        val classifier = LabyrinthNodeClassifier()
        val memo = installMemo(classifier, fixture.frame, fixture.templates, listOf(old))
        assertStableSignature(classifier, fixture.frame, replacement.frame)
        assertTrue(replacement.classification.isClickable)
        assertTrue(replacement.classification.blockType != old.blockType)
        val result = classifier.classifyMapNodes(replacement.frame, fixture.templates)
        assertEquals(NodeFrameEvidenceSource.SEARCH, classifier.lastEvidenceSource)
        assertEquals(1, classifier.lastRevalidationCount)
        assertTrue(classifier.lastSearchWindowCount > 0)
        assertNotSame(memo, field(classifier, "scanMemo"))
        assertFalse("old semantic type survived: $result", result.any {
            it.screenRect == old.screenRect && it.blockType == old.blockType && it.isClickable
        })
    }

    @Test
    fun `resetTracking clears memo and diagnostic evidence before the next full search`() {
        val fixture = fixtureData
        val classifier = LabyrinthNodeClassifier()
        val memo = installMemo(classifier, fixture.frame, fixture.templates, listOf(fixture.activeLink))
        classifier.classifyMapNodes(copyFrame(fixture.frame), fixture.templates)
        assertEquals(NodeFrameEvidenceSource.MEMO_REVALIDATED, classifier.lastEvidenceSource)
        assertEquals(1, classifier.lastRevalidationCount)
        classifier.resetTracking()
        assertNull(field(classifier, "scanMemo"))
        assertNull(field(classifier, "previousFrame"))
        assertEquals(0, field(classifier, "scanMemoFrames"))
        assertEquals(NodeFrameEvidenceSource.NONE, classifier.lastEvidenceSource)
        assertFalse(classifier.lastEvidenceSource.currentFrameVerified)
        assertEquals(0, classifier.lastRevalidationCount)
        classifier.classifyMapNodes(copyFrame(fixture.frame), fixture.templates)
        assertEquals(NodeFrameEvidenceSource.SEARCH, classifier.lastEvidenceSource)
        assertEquals(fixture.fullSearchWindows, classifier.lastSearchWindowCount)
        assertEquals(0, classifier.lastRevalidationCount)
        assertNotSame(memo, field(classifier, "scanMemo"))
    }

    @Test
    fun `another template-set identity cannot reuse the old template memo`() {
        val fixture = fixtureData
        val classifier = LabyrinthNodeClassifier()
        val memo = installMemo(classifier, fixture.frame, fixture.templates, listOf(fixture.activeLink))
        val reloaded = NodeTemplateSet(fixture.templates.templates.toMap())
        assertNotSame(fixture.templates, reloaded)
        classifier.classifyMapNodes(copyFrame(fixture.frame), reloaded)
        assertEquals(NodeFrameEvidenceSource.SEARCH, classifier.lastEvidenceSource)
        assertEquals("full", classifier.lastSearchMode)
        assertEquals(fixture.fullSearchWindows, classifier.lastSearchWindowCount)
        assertEquals(0, classifier.lastRevalidationCount)
        assertNotSame(memo, field(classifier, "scanMemo"))
    }

    @Test
    fun `target change invalidates memo even with identical pixels templates and search rung`() {
        val fixture = fixtureData
        val classifier = LabyrinthNodeClassifier()
        val rect = requireNotNull(fixture.activeLink.screenRect)
        val oldHint = NodeSearchHint(99901, rect.left + rect.width / 2, setOf(LabyrinthNodeTypes.LINK), 1..3)
        val memo = installMemo(classifier, fixture.frame, fixture.templates, listOf(fixture.activeLink), oldHint, "DIRECTED")
        classifier.classifyMapNodes(copyFrame(fixture.frame), fixture.templates, oldHint.copy(targetBlockId = 99902))
        assertEquals(NodeFrameEvidenceSource.SEARCH, classifier.lastEvidenceSource)
        assertEquals("directed", classifier.lastSearchMode)
        assertEquals(0, classifier.lastRevalidationCount)
        assertTrue(classifier.lastSearchWindowCount > 0)
        assertNotSame(memo, field(classifier, "scanMemo"))
        assertEquals(99902L, field(classifier, "hintedTargetBlockId"))
    }

    @Test
    fun `no templates publish no evidence and cannot inherit previous ROI counters`() {
        val fixture = fixtureData
        val classifier = LabyrinthNodeClassifier()
        installMemo(classifier, fixture.frame, fixture.templates, listOf(fixture.activeLink))
        classifier.classifyMapNodes(copyFrame(fixture.frame), fixture.templates)
        assertEquals(1, classifier.lastRevalidationCount)
        val result = classifier.classifyMapNodes(copyFrame(fixture.frame), NodeTemplateSet(emptyMap()))
        assertTrue(result.isEmpty())
        assertEquals(NodeFrameEvidenceSource.NONE, classifier.lastEvidenceSource)
        assertFalse(classifier.lastEvidenceSource.currentFrameVerified)
        assertEquals(0, classifier.lastSearchWindowCount)
        assertEquals(0, classifier.lastRevalidationCount)
    }

    @Test
    fun `empty memo may save search work but cannot claim a verified current crop`() {
        val fixture = fixtureData
        val classifier = LabyrinthNodeClassifier()
        installMemo(classifier, fixture.frame, fixture.templates, emptyList())
        val result = classifier.classifyMapNodes(copyFrame(fixture.frame), fixture.templates)
        assertTrue(result.isEmpty())
        assertEquals(NodeFrameEvidenceSource.NONE, classifier.lastEvidenceSource)
        assertFalse(classifier.lastEvidenceSource.currentFrameVerified)
        assertEquals(0, classifier.lastSearchWindowCount)
        assertEquals(0, classifier.lastRevalidationCount)
    }

    private data class Fixture(
        val frame: PixelImage, val templates: NodeTemplateSet,
        val memoNodes: List<NodeClassification>, val activeLink: NodeClassification,
        val fullSearchWindows: Int,
    )
    private data class CurrentVariant(val frame: PixelImage, val classification: NodeClassification)

    private fun currentClassifications(frame: PixelImage, nodes: List<NodeClassification>, templates: NodeTemplateSet): List<NodeClassification> {
        val probe = LabyrinthNodeClassifier()
        return nodes.map { node ->
            requireNotNull(probe.classifyNode(frame, requireNotNull(node.screenRect), templates)) {
                "Expected fixture crop disappeared: $node"
            }.copy(column = node.column, row = node.row)
        }
    }

    private fun scoreVariant(fixture: Fixture, old: NodeClassification): CurrentVariant {
        val diagnostics = mutableListOf<String>()
        for (delta in listOf(1, 3, 6, 12)) {
            val changed = editCropOutsideViewportSamples(fixture.frame, requireNotNull(old.screenRect)) { _, _, color ->
                (color and 0xff000000.toInt()) or
                    (((color ushr 16 and 255) + delta).coerceAtMost(255) shl 16) or
                    (((color ushr 8 and 255) + delta).coerceAtMost(255) shl 8) or
                    ((color and 255) + delta).coerceAtMost(255)
            }
            val current = LabyrinthNodeClassifier().classifyNode(changed, requireNotNull(old.screenRect), fixture.templates)
            diagnostics += "delta=$delta current=$current"
            if (current != null && current.blockType == old.blockType &&
                current.isClickable == old.isClickable && scoresDiffer(old, current)
            ) return CurrentVariant(changed, current)
        }
        error("Required same-type score variant not found: $diagnostics")
    }

    private fun semanticVariant(fixture: Fixture, old: NodeClassification): CurrentVariant {
        val rect = requireNotNull(old.screenRect)
        val diagnostics = mutableListOf<String>()
        // Replacement pixels are shipped assets, not fabricated classification objects.
        for (id in listOf("node.shop.active", "node.event.active", "node.normal_battle.active", "node.relic.active")) {
            val template = requireNotNull(fixture.templates.templates[id])
            val changed = editCropOutsideViewportSamples(fixture.frame, rect) { x, y, _ ->
                template[x * (template.width - 1) / (rect.width - 1), y * (template.height - 1) / (rect.height - 1)]
            }
            val current = LabyrinthNodeClassifier().classifyNode(changed, rect, fixture.templates)
            diagnostics += "$id current=$current"
            if (current != null && current.isClickable && current.blockType != old.blockType) {
                return CurrentVariant(changed, current)
            }
        }
        error("Required current-frame type-change fixture not found: $diagnostics")
    }

    private fun scoresDiffer(first: NodeClassification, second: NodeClassification): Boolean =
        first.confidence != second.confidence || first.typeConfidence != second.typeConfidence ||
            first.colorRoiScore != second.colorRoiScore

    private fun copyFrame(frame: PixelImage): PixelImage = PixelImage(frame.width, frame.height, frame.pixels.copyOf())

    /** Data preparation only; tests verify the resulting identity with the production signature. */
    private fun editCropOutsideViewportSamples(frame: PixelImage, rect: EntryPixelRect, edit: (Int, Int, Int) -> Int): PixelImage {
        val samples = BooleanArray(frame.pixels.size)
        val step = maxOf(8, frame.height / 45)
        for (y in frame.height / 7 until frame.height * 6 / 7 step step) {
            for (x in frame.width / 5 until frame.width * 19 / 20 step step) samples[y * frame.width + x] = true
        }
        val pixels = frame.pixels.copyOf()
        var changed = 0
        for (y in 0 until rect.height) {
            for (x in 0 until rect.width) {
                val index = (rect.top + y) * frame.width + rect.left + x
                if (!samples[index]) {
                    val replacement = edit(x, y, pixels[index])
                    if (replacement != pixels[index]) changed++
                    pixels[index] = replacement
                }
            }
        }
        assertTrue("adversarial fixture must change node pixels", changed > 0)
        return PixelImage(frame.width, frame.height, pixels)
    }

    private fun assertStableSignature(classifier: LabyrinthNodeClassifier, before: PixelImage, now: PixelImage) {
        assertEquals(classifier.viewportSignatureKey(before), classifier.viewportSignatureKey(now))
        val signature = viewportSignature(classifier, before)
        val comparison = LabyrinthNodeClassifier::class.java.getDeclaredMethod(
            "isStableViewport", signature.javaClass, PixelImage::class.java,
        ).apply { isAccessible = true }
        assertEquals(true, comparison.invoke(classifier, signature, now))
    }

    private fun viewportSignature(classifier: LabyrinthNodeClassifier, frame: PixelImage): Any =
        requireNotNull(LabyrinthNodeClassifier::class.java.getDeclaredMethod(
            "viewportSignature", PixelImage::class.java,
        ).apply { isAccessible = true }.invoke(classifier, frame))

    private fun installMemo(
        classifier: LabyrinthNodeClassifier, frame: PixelImage, templates: NodeTemplateSet,
        nodes: List<NodeClassification>, hint: NodeSearchHint? = null, modeName: String = "FULL",
    ): Any {
        classifier.resetTracking()
        val memoClass = LabyrinthNodeClassifier::class.java.declaredClasses.single { it.simpleName == "ScanMemo" }
        val constructor = memoClass.declaredConstructors.single { it.parameterTypes.size == 5 }.apply { isAccessible = true }
        val mode = constructor.parameterTypes[1].enumConstants.single { (it as Enum<*>).name == modeName }
        val memo = constructor.newInstance(viewportSignature(classifier, frame), mode, hint, templates, nodes)
        setField(classifier, "scanMemo", memo)
        setField(classifier, "hintedTargetBlockId", hint?.targetBlockId)
        if (hint != null && modeName == "FULL") {
            setField(classifier, "directedMissFrames", 1)
            setField(classifier, "typedMissFrames", 2)
        }
        assertNull("tracked path must not hide the memo path", field(classifier, "previousFrame"))
        return memo
    }

    private fun field(classifier: LabyrinthNodeClassifier, name: String): Any? =
        LabyrinthNodeClassifier::class.java.getDeclaredField(name).apply { isAccessible = true }.get(classifier)

    private fun setField(classifier: LabyrinthNodeClassifier, name: String, value: Any?) {
        LabyrinthNodeClassifier::class.java.getDeclaredField(name).apply { isAccessible = true }.set(classifier, value)
    }

    private companion object {
        val fixtureData: Fixture by lazy {
            val root = locateProjectRoot()
            val frame = readRequiredImage(File(root, "android/app/src/test/resources/labyrinth/node-link-20260914.png"))
            val vision = File(root, "android/app/src/main/assets/resource-packs/cn-bilibili/vision")
            val templates = NodeTemplateSet(TEMPLATE_FILES.mapValues { (_, filename) -> readRequiredImage(File(vision, filename)) })
            val classifier = LabyrinthNodeClassifier()
            val found = classifier.classifyMapNodes(frame, templates)
            check(classifier.lastEvidenceSource == NodeFrameEvidenceSource.SEARCH)
            check(classifier.lastSearchWindowCount > 0)
            // Obtain authentic current classifications; never fabricate active flags or scores.
            val probe = LabyrinthNodeClassifier()
            val nodes = found.mapNotNull { old ->
                old.screenRect?.let { probe.classifyNode(frame, it, templates) }?.copy(column = old.column, row = old.row)
            }.distinctBy { it.screenRect }
            check(nodes.size >= 2) { "Expected multiple fixture crops: $found" }
            val active = requireNotNull(nodes.firstOrNull { it.isClickable && it.blockType == LabyrinthNodeTypes.LINK }) {
                "Required live LINK fixture was not recognized: $nodes"
            }
            Fixture(frame, templates, nodes, active, classifier.lastSearchWindowCount)
        }

        fun locateProjectRoot(): File {
            var current = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
            repeat(8) {
                if (File(current, "android/app/build.gradle.kts").isFile) return current
                current = current.parentFile ?: return@repeat
            }
            error("Cannot locate required project resources from ${System.getProperty("user.dir")}")
        }

        fun readRequiredImage(file: File): PixelImage {
            check(file.isFile) { "Required fixture/template missing: ${file.absolutePath}" }
            val imageIo = Class.forName("javax.imageio.ImageIO")
            val image = requireNotNull(imageIo.getMethod("read", File::class.java).invoke(null, file)) {
                "Cannot decode required image ${file.absolutePath}"
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

        val TEMPLATE_FILES = linkedMapOf(
            "node.normal_battle.active" to "node_normal_battle_active.png",
            "node.normal_battle.active.bottom" to "node_normal_battle_active_bottom.png",
            "node.normal_battle.inactive" to "node_normal_battle_inactive.png",
            "node.normal_battle.inactive.bottom" to "node_normal_battle_inactive_bottom.png",
            "node.normal_battle.inactive.video" to "node_normal_battle_inactive_video.png",
            "node.ex_battle.inactive" to "node_ex_battle_inactive.png",
            "node.boss.yellow" to "node_boss_yellow.png",
            "node.boss.dragon" to "node_boss_dragon.png",
            "node.boss.platform" to "node_boss_platform.png",
            "node.clear" to "node_clear.png",
            "node.relic.active" to "node_relic_active.png",
            "node.relic.inactive" to "node_relic_inactive.png",
            "node.relic.map" to "node_relic_map.png",
            "node.event.active" to "node_event_active.png",
            "node.event.active.bottom" to "node_event_active_bottom.png",
            "node.event.inactive" to "node_event_inactive.png",
            "node.event.inactive.bottom" to "node_event_inactive_bottom.png",
            "node.link.active" to "node_link_active.png",
            "node.link.active.map" to "node_link_map.png",
            "node.link.inactive" to "node_link_inactive.png",
            "node.shop.active" to "node_shop_active.png",
        )
    }
}
