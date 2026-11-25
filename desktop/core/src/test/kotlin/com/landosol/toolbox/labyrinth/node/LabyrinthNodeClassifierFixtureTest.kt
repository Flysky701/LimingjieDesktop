package com.landosol.toolbox.labyrinth.node

import com.landosol.toolbox.vision.PixelImage
import com.landosol.toolbox.labyrinth.vision.EntryPixelRect
import com.landosol.toolbox.labyrinth.vision.GradientTemplateMatcher
import com.landosol.toolbox.protocol.labyrinth.LabyrinthMapNode
import java.io.File
import org.junit.Assume.assumeTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LabyrinthNodeClassifierFixtureTest {
    @Test fun `visible neighbors cannot prevent expansion when route target is unconfirmed`() {
        val root = locateProjectRoot()
        val frame = readImage(File(root, "desktop/core/src/test/resources/labyrinth/node-link-20260914.png"))
        val templates = NodeTemplateSet(NODE_TEMPLATE_FILES.mapValues { (_, name) ->
            readImage(File(root, "desktop/core/src/main/resources/assets/resource-packs/cn-bilibili/vision/$name"))
        })
        val classifier = LabyrinthNodeClassifier()
        val hint = NodeSearchHint(30302, 400, setOf(LabyrinthNodeTypes.LINK,
            LabyrinthNodeTypes.EVENT, LabyrinthNodeTypes.NORMAL_BATTLE, LabyrinthNodeTypes.RELIC), 2..4)
        val modes = (1..6).map {
            assertTrue(classifier.classifyMapNodes(frame, templates, hint).isNotEmpty())
            classifier.lastSearchMode
        }
        // One aimed frame, then the restriction is dropped. Measured over two live runs a directed
        // scan acquired the target on 1 of 53 and 1 of 50 frames while typed acquired 40-79%, so a
        // second aimed frame only delays the rung that works.
        assertEquals(listOf("directed", "typed", "typed", "full", "full", "full"), modes)
        classifier.classifyMapNodes(frame, templates, hint.copy(matchedTargetBlockId = 30302))
        assertTrue(classifier.lastSearchMode in setOf("tracked", "directed"))
        classifier.classifyMapNodes(frame, templates, hint.copy(targetBlockId = 30401, matchedTargetBlockId = 30302))
        assertEquals("directed", classifier.lastSearchMode)
        // Without a camera prediction the template set is still restricted to the route
        // neighbourhood; only two misses in that mode fall back to the unrestricted scan.
        // A changed hint invalidates the stable-frame memo, so these are real scans.
        classifier.classifyMapNodes(frame, templates, hint.copy(targetBlockId = 30401, expectedCenterX = null))
        assertEquals("typed", classifier.lastSearchMode)
        classifier.classifyMapNodes(frame, templates, hint.copy(targetBlockId = 30401, expectedCenterX = null))
        assertEquals("typed", classifier.lastSearchMode)
        classifier.classifyMapNodes(frame, templates, hint.copy(targetBlockId = 30401, expectedCenterX = null))
        assertEquals("full", classifier.lastSearchMode)
    }

    @Test fun `a stalled ladder stops paying for scans instead of re-climbing forever`() {
        // Two live failures shaped this. First the ladder was a one-way ratchet into full: 2026-09-18
        // bundle 133550 spent 68 minutes with every frame a 6.2 s full scan. The fix then reset the
        // ladder back to directed after two full frames, which replaced the ratchet with a loop:
        // 2026-09-20 bundle 212345 shows one 普通战斗#10402 hop cycling directed/wide/typed/full
        // three times for 99.8 s — 39% of that run's entire node budget — before an ordinary scan
        // acquired it as soon as the frame changed.
        //
        // Re-climbing cannot help, because the scan is a pure function of (frame, templates, hint):
        // a rung that missed on this frame misses again on the next identical frame. The escape is
        // to stop scanning while nothing changes, which bounds a stall at one real scan per rung
        // plus one every MAX_SCAN_MEMO_FRAMES, instead of one per frame.
        val root = locateProjectRoot()
        val frame = readImage(File(root, "desktop/core/src/test/resources/labyrinth/node-link-20260914.png"))
        val templates = NodeTemplateSet(NODE_TEMPLATE_FILES.mapValues { (_, name) ->
            readImage(File(root, "desktop/core/src/main/resources/assets/resource-packs/cn-bilibili/vision/$name"))
        })
        val classifier = LabyrinthNodeClassifier()
        // A target this route never acquires: every frame is a miss, as in the stuck bundles.
        val hint = NodeSearchHint(
            targetBlockId = 99901,
            expectedCenterX = 400,
            expectedTypes = setOf(LabyrinthNodeTypes.LINK, LabyrinthNodeTypes.EVENT),
            reachableColumns = 2..4,
        )
        val windows = (1..14).map {
            classifier.classifyMapNodes(frame, templates, hint)
            classifier.lastSearchWindowCount
        }

        // The ladder still climbs all the way: a real full scan happens.
        assertTrue("the ladder never reached a full scan: $windows", windows.max() >= 2_000)
        // But each rung is paid for once, not once per frame. Measured: [695, 1464, 0, 2629, 0...]
        // — directed, typed and full each scanned once and the remaining frames cost nothing.
        val scanned = windows.count { it > 0 }
        assertTrue("a stalled ladder kept scanning ($scanned of ${windows.size}): $windows", scanned <= 4)
    }

    @Test fun `without a camera fit the ladder still settles on full`() {
        // The release is conditional on a usable prediction: with none, a directed scan has
        // nothing to aim at and full remains the only honest option.
        val root = locateProjectRoot()
        val frame = readImage(File(root, "desktop/core/src/test/resources/labyrinth/node-link-20260914.png"))
        val templates = NodeTemplateSet(NODE_TEMPLATE_FILES.mapValues { (_, name) ->
            readImage(File(root, "desktop/core/src/main/resources/assets/resource-packs/cn-bilibili/vision/$name"))
        })
        val classifier = LabyrinthNodeClassifier()
        val hint = NodeSearchHint(
            targetBlockId = 99902,
            expectedCenterX = null,
            expectedTypes = setOf(LabyrinthNodeTypes.LINK),
            reachableColumns = 2..4,
        )
        val modes = (1..8).map {
            classifier.classifyMapNodes(frame, templates, hint)
            classifier.lastSearchMode
        }
        assertEquals("full", modes.last())
        assertTrue("no directed scan is possible without a fit: $modes", modes.none { it == "directed" })
    }

    @Test fun `directed search keeps target while scoring fewer candidate windows`() {
        val root = locateProjectRoot()
        val frame = readImage(File(root, "desktop/core/src/test/resources/labyrinth/node-link-20260914.png"))
        val templates = NodeTemplateSet(NODE_TEMPLATE_FILES.mapValues { (_, name) ->
            readImage(File(root, "desktop/core/src/main/resources/assets/resource-packs/cn-bilibili/vision/$name"))
        })
        val fullClassifier = LabyrinthNodeClassifier()
        val full = fullClassifier.classifyMapNodes(frame, templates)
        val target = requireNotNull(full.firstOrNull { it.isClickable && it.blockType == LabyrinthNodeTypes.LINK })
        val targetRect = requireNotNull(target.screenRect)
        val fullWindows = fullClassifier.lastSearchWindowCount

        val directedClassifier = LabyrinthNodeClassifier()
        val directed = directedClassifier.classifyMapNodes(
            frame = frame,
            templates = templates,
            searchHint = NodeSearchHint(
                targetBlockId = 99901,
                expectedCenterX = targetRect.left + targetRect.width / 2,
                expectedTypes = setOf(LabyrinthNodeTypes.LINK),
                reachableColumns = 1..3,
            ),
        )
        val directedWindows = directedClassifier.lastSearchWindowCount

        println(
            "node-directed-benchmark fixture=node-link-20260914.png " +
                "fullWindows=$fullWindows directedWindows=$directedWindows " +
                "reduction=${100 - directedWindows * 100 / fullWindows}%",
        )

        assertEquals("mode=${directedClassifier.lastSearchMode}", "directed", directedClassifier.lastSearchMode)
        assertTrue("directed=$directed", directed.any { node ->
            node.blockType == target.blockType && node.isClickable && node.screenRect?.let { rect ->
                kotlin.math.abs((rect.left + rect.width / 2) - (targetRect.left + targetRect.width / 2)) <= 30
            } == true
        })
        assertTrue("full=$fullWindows directed=$directedWindows", directedWindows < fullWindows)
    }

    @Test fun `directed search survives a coarse prediction with a realistic type set`() {
        val root = locateProjectRoot()
        val frame = readImage(File(root, "desktop/core/src/test/resources/labyrinth/node-link-20260914.png"))
        val templates = NodeTemplateSet(NODE_TEMPLATE_FILES.mapValues { (_, name) ->
            readImage(File(root, "desktop/core/src/main/resources/assets/resource-packs/cn-bilibili/vision/$name"))
        })
        val fullClassifier = LabyrinthNodeClassifier()
        var full = emptyList<NodeClassification>()
        val fullNanos = kotlin.system.measureNanoTime { full = fullClassifier.classifyMapNodes(frame, templates) }
        println(
            "node-directed-benchmark baseline windows=${fullClassifier.lastSearchWindowCount} " +
                "ms=${fullNanos / 1_000_000}",
        )
        val target = requireNotNull(full.firstOrNull { it.isClickable && it.blockType == LabyrinthNodeTypes.LINK })
        val targetCenterX = requireNotNull(target.screenRect).let { it.left + it.width / 2 }
        // The live session hands the classifier the whole route neighbourhood, and the camera
        // fit is rarely exact: a prediction 100 px off on either side must still hit the target
        // while the window budget stays far below the 2266-window full scan.
        val neighbourhood = setOf(
            LabyrinthNodeTypes.LINK, LabyrinthNodeTypes.RELIC,
            LabyrinthNodeTypes.NORMAL_BATTLE, LabyrinthNodeTypes.EX_BATTLE, LabyrinthNodeTypes.EVENT,
        )
        for (error in listOf(-100, 0, 100)) {
            val classifier = LabyrinthNodeClassifier()
            var directed = emptyList<NodeClassification>()
            val nanos = kotlin.system.measureNanoTime {
                directed = classifier.classifyMapNodes(
                    frame = frame,
                    templates = templates,
                    searchHint = NodeSearchHint(99901, targetCenterX + error, neighbourhood, 1..3),
                )
            }
            val windows = classifier.lastSearchWindowCount
            println("node-directed-benchmark error=$error windows=$windows ms=${nanos / 1_000_000}")
            assertEquals("directed", classifier.lastSearchMode)
            assertTrue("error=$error directed=$directed", directed.any { node ->
                node.blockType == LabyrinthNodeTypes.LINK && node.isClickable &&
                    node.screenRect?.let { kotlin.math.abs(it.left + it.width / 2 - targetCenterX) <= 30 } == true
            })
            assertTrue("error=$error windows=$windows", windows <= DIRECTED_WINDOW_BUDGET)
            assertTrue(
                "error=$error directed=$windows full=${fullClassifier.lastSearchWindowCount}",
                windows * 2 <= fullClassifier.lastSearchWindowCount,
            )
        }
    }

    @Test fun `pink link statue in reported map is not a blue relic`() {
        val frame = readImage(File(locateProjectRoot(), "desktop/core/src/test/resources/labyrinth/node-link-20260914.png"))
        val templates = NodeTemplateSet(NODE_TEMPLATE_FILES.mapValues { (_, name) ->
            readImage(File(locateProjectRoot(), "desktop/core/src/main/resources/assets/resource-packs/cn-bilibili/vision/$name"))
        })
        val rect = EntryPixelRect(348, 42, 119, 148)
        val color = activeNodeColorEvidence(frame, rect)
        assertEquals("color=$color", LabyrinthNodeTypes.LINK, color?.let(::inferActiveNodeType)?.blockType)
        val nodes = LabyrinthNodeClassifier().classifyMapNodes(frame, templates)
        val result = nodes.firstOrNull { it.isClickable && it.screenRect?.let { r ->
            r.left + r.width / 2 in 370..445 && r.top in 0..90
        } == true }
        assertEquals("nodes=$nodes", LabyrinthNodeTypes.LINK, result?.blockType)
    }
    @Test
    fun `stable color ROI rejects a same-shape background with the wrong platform colors`() {
        val width = 80
        val height = 100
        fun patterned(bright: Int, dark: Int) = PixelImage(
            width = width,
            height = height,
            pixels = IntArray(width * height) { index ->
                val x = index % width
                val y = index / width
                if ((x / 8 + y / 8) % 2 == 0) bright else dark
            },
        )
        val template = patterned(bright = 0xff00d8ff.toInt(), dark = 0xff003060.toInt())
        val wrongColorFrame = patterned(bright = 0xffffa000.toInt(), dark = 0xff601000.toInt())
        val rect = EntryPixelRect(0, 0, width, height)

        val gradientScore = GradientTemplateMatcher().score(wrongColorFrame, rect, template)
        val colorScore = requireNotNull(stableNodeColorRoiScore(wrongColorFrame, rect, template))
        val classification = LabyrinthNodeClassifier().classifyNode(
            frame = wrongColorFrame,
            iconRect = rect,
            templates = NodeTemplateSet(mapOf("node.link.active.map" to template)),
        )

        assertTrue("gradient=$gradientScore", gradientScore >= LabyrinthNodeClassifier.MIN_CONFIDENCE)
        assertTrue("color=$colorScore", colorScore < 0.75)
        assertEquals(null, classification)
    }

    @Test
    fun `stable color ROI keeps a matching node platform`() {
        val width = 80
        val height = 100
        val template = PixelImage(
            width = width,
            height = height,
            pixels = IntArray(width * height) { index ->
                val x = index % width
                val y = index / width
                if ((x / 8 + y / 8) % 2 == 0) 0xff00d8ff.toInt() else 0xff003060.toInt()
            },
        )
        val rect = EntryPixelRect(0, 0, width, height)

        val classification = requireNotNull(
            LabyrinthNodeClassifier().classifyNode(
                frame = template,
                iconRect = rect,
                // Cyan/blue represents a relic; an active link also needs its pink icon.
                templates = NodeTemplateSet(mapOf("node.relic.active" to template)),
            ),
        )

        assertEquals(1.0, classification.colorRoiScore, 0.0001)
        assertEquals(null, LabyrinthNodeClassifier().classifyNode(
            frame = template,
            iconRect = rect,
            templates = NodeTemplateSet(mapOf("node.link.active.map" to template)),
        ))
    }

    @Test
    fun `matching bottom HUD cannot validate a wrong node body`() {
        val width = 80
        val height = 100
        fun image(nodeColor: Int) = PixelImage(
            width = width,
            height = height,
            pixels = IntArray(width * height) { index ->
                val x = index % width
                val y = index / width
                when {
                    y >= 78 -> if ((x / 5 + y / 5) % 2 == 0) {
                        0xfff8f8f8.toInt()
                    } else {
                        0xff305080.toInt()
                    }
                    y >= 30 -> nodeColor
                    else -> 0xff101020.toInt()
                }
            },
        )
        val template = image(0xff00d8ff.toInt())
        val wrongNodeWithSameHud = image(0xffffa000.toInt())

        val colorScore = requireNotNull(
            stableNodeColorRoiScore(
                wrongNodeWithSameHud,
                EntryPixelRect(0, 0, width, height),
                template,
            ),
        )

        assertTrue("color=$colorScore", colorScore < 0.75)
    }

    @Test
    fun `bottom node template masks the fixed retreat and return controls`() {
        val width = 80
        val height = 100
        val template = PixelImage(
            width = width,
            height = height,
            pixels = IntArray(width * height) { index ->
                val y = index / width
                if (y >= 78) 0xfff8f8f8.toInt() else 0xff203050.toInt()
            },
        )

        val gradientTemplate = NodeTemplateSet(
            mapOf("node.event.inactive.bottom" to template),
        ).gradientTemplates.getValue("node.event.inactive.bottom")

        assertEquals(0xff, gradientTemplate[20, 70] ushr 24 and 0xff)
        assertEquals(0, gradientTemplate[20, 78] ushr 24 and 0xff)
        assertEquals(0, gradientTemplate[20, 99] ushr 24 and 0xff)
        assertEquals(0xff, template[20, 99] ushr 24 and 0xff)
    }

    @Test
    fun `explicit active template without activation glow is rejected`() {
        val width = 80
        val height = 100
        val darkMatchingFrame = PixelImage(
            width = width,
            height = height,
            pixels = IntArray(width * height) { index ->
                if ((index % width / 8 + index / width / 8) % 2 == 0) {
                    0xff403040.toInt()
                } else {
                    0xff181520.toInt()
                }
            },
        )

        val classification = LabyrinthNodeClassifier().classifyNode(
            frame = darkMatchingFrame,
            iconRect = EntryPixelRect(0, 0, width, height),
            templates = NodeTemplateSet(mapOf("node.link.active.map" to darkMatchingFrame)),
        )

        assertEquals(null, classification)
    }

    @Test
    fun `purple EX activation is clickable even when visual template is inactive`() {
        val width = 280
        val height = 350
        val pixels = IntArray(width * height) { 0xff18152a.toInt() }
        val sideBand = 50
        val lowerBandStart = 238
        repeat(height) { y ->
            repeat(width) { x ->
                if (x < sideBand || x >= width - sideBand || y >= lowerBandStart) {
                    pixels[y * width + x] = 0xffff22d8.toInt()
                }
            }
        }
        val frame = PixelImage(width, height, pixels)
        val templates = NodeTemplateSet(
            mapOf("node.ex_battle.inactive" to frame),
        )

        val classification = requireNotNull(
            LabyrinthNodeClassifier().classifyNode(
                frame = frame,
                iconRect = EntryPixelRect(0, 0, width, height),
                templates = templates,
            ),
        )

        assertTrue(classification.purpleGlowScore >= 0.08)
        assertTrue(classification.purpleGlowRowCoverage >= 0.30)
        assertTrue(classification.purpleGlowSideScore >= 0.06)
        assertTrue(classification.purpleGlowLowerScore >= 0.05)
        assertTrue(classification.isClickable)
        assertFalse(classification.cyanGlowScore >= 0.08)
    }

    @Test
    fun `purple scenery in the center does not count as EX activation`() {
        val width = 280
        val height = 350
        val pixels = IntArray(width * height) { 0xff18152a.toInt() }
        repeat(160) { y ->
            repeat(120) { x ->
                pixels[(y + 95) * width + x + 80] = 0xffff22d8.toInt()
            }
        }
        val frame = PixelImage(width, height, pixels)
        val templates = NodeTemplateSet(
            mapOf("node.ex_battle.inactive" to frame),
        )

        val classification = requireNotNull(
            LabyrinthNodeClassifier().classifyNode(
                frame = frame,
                iconRect = EntryPixelRect(0, 0, width, height),
                templates = templates,
            ),
        )

        assertTrue(classification.purpleGlowScore < 0.08)
        assertFalse(classification.isClickable)
    }

    @Test
    fun `dark purple map background does not make an inactive EX clickable`() {
        val width = 280
        val height = 350
        val pixels = IntArray(width * height) { 0xff18152a.toInt() }
        val sideBand = 50
        val lowerBandStart = 238
        repeat(height) { y ->
            repeat(width) { x ->
                if (x < sideBand || x >= width - sideBand || y >= lowerBandStart) {
                    // This passes the former broad-purple thresholds but is much darker than the
                    // neon magenta frame of a genuinely reachable EX node.
                    pixels[y * width + x] = 0xffba309f.toInt()
                }
            }
        }
        val frame = PixelImage(width, height, pixels)

        val classification = requireNotNull(
            LabyrinthNodeClassifier().classifyNode(
                frame = frame,
                iconRect = EntryPixelRect(0, 0, width, height),
                templates = NodeTemplateSet(mapOf("node.ex_battle.inactive" to frame)),
            ),
        )

        assertTrue(classification.purpleGlowScore < 0.06)
        assertFalse(classification.isClickable)
        assertEquals(LabyrinthNodeTypes.EX_BATTLE, classification.blockType)
    }

    @Test
    fun `bright magenta bottom control alone is not an EX activation frame`() {
        val width = 280
        val height = 350
        val pixels = IntArray(width * height) { index ->
            if (index / width >= 238) 0xffff22d8.toInt() else 0xff18152a.toInt()
        }
        val frame = PixelImage(width, height, pixels)

        val classification = requireNotNull(
            LabyrinthNodeClassifier().classifyNode(
                frame = frame,
                iconRect = EntryPixelRect(0, 0, width, height),
                templates = NodeTemplateSet(mapOf("node.ex_battle.inactive" to frame)),
            ),
        )

        assertTrue(classification.purpleGlowLowerScore >= 0.05)
        assertTrue(classification.purpleGlowSideScore < 0.06)
        assertFalse(classification.isClickable)
    }

    @Test
    fun `initial map fixture maps the two active battles to logical column two`() {
        val root = locateProjectRoot()
        val visionRoot = File(root, "desktop/core/src/main/resources/assets/resource-packs/cn-bilibili/vision")
        val localMap = File(root, "素材/ui/黎明界进入/当前版本_节点选择.png")
        assumeTrue("local diagnostic fixture is unavailable: ${localMap.absolutePath}", localMap.isFile)
        val templates = NodeTemplateSet(
            NODE_TEMPLATE_FILES.mapValues { (_, fileName) -> readImage(File(visionRoot, fileName)) },
        )
        val frame = readImage(localMap)

        val detections = LabyrinthNodeClassifier().classifyMapNodes(frame, templates)
        val activeBattles = detections.filter(NodeClassification::isClickable)
        assertEquals(
            "detections=$detections diagnostics=${diagnose(frame, templates)}",
            2,
            activeBattles.size,
        )

        val areaNodes = listOf(
            node(10101, LabyrinthNodeTypes.START),
            node(10201, LabyrinthNodeTypes.NORMAL_BATTLE),
            node(10202, LabyrinthNodeTypes.NORMAL_BATTLE),
        )
        val mappings = NodePositionMapper().mapNodes(
            classifications = detections,
            areaNodes = areaNodes,
            currentColumn = 1,
        )

        assertEquals("detections=$detections mappings=$mappings", setOf(10201L, 10202L), mappings.mapTo(mutableSetOf()) { it.blockId })
        assertTrue(mappings.all { it.screenRect != null })

        val session = LabyrinthNodeSession().apply {
            initialize(
                allNodes = areaNodes,
                route = listOf(areaNodes[0], areaNodes[1]),
                nodeTemplates = templates,
                currentNodeId = 10101,
            )
        }
        val action = session.processClassifications(detections) as NodeAction.ClickNode
        assertEquals(10201L, action.blockId)
        assertEquals(mappings.single { it.blockId == 10201L }.screenRect, action.screenRect)
    }

    private fun node(blockId: Long, blockType: Int) = LabyrinthMapNode(
        area = 1,
        column = (blockId / 100 % 100).toInt(),
        row = (blockId % 100).toInt(),
        blockId = blockId,
        blockType = blockType,
        questId = null,
        nextBlockIds = emptyList(),
        isAreaLastPoint = false,
    )

    private fun diagnose(frame: PixelImage, templates: NodeTemplateSet): List<String> {
        val matcher = GradientTemplateMatcher(maxSamples = 2_400)
        return NodeAnchorDefinitions.visibleNodeRects(frame.width, frame.height)
            .flatMap { candidate ->
                NodeAnchorDefinitions.searchOffsets(frame.width, frame.height).mapNotNull { (dx, dy) ->
                    val rect = candidate.rect.offsetInside(dx, dy, frame.width, frame.height)
                        ?: return@mapNotNull null
                    templates.templates.map { (id, template) ->
                        val raw = matcher.score(frame, rect, template)
                        val gated = matcher.score(frame, rect, template, minimumChannelScore = 0.35)
                        Triple(raw, gated, "${candidate.column}/${candidate.row}+$dx/$dy:$id@$rect")
                    }
                }.flatten()
            }
            .sortedByDescending { it.first }
            .filter { (_, _, label) -> "node.normal_battle.active" in label }
            .take(30)
            .map { (raw, gated, label) -> "raw=${"%.3f".format(raw)},g35=${"%.3f".format(gated)}:$label" }
    }

    private fun readImage(file: File): PixelImage {
        val imageIo = Class.forName("javax.imageio.ImageIO")
        val image = requireNotNull(
            imageIo.getMethod("read", File::class.java).invoke(null, file),
        ) { "Cannot read $file" }
        val width = image.javaClass.getMethod("getWidth").invoke(image) as Int
        val height = image.javaClass.getMethod("getHeight").invoke(image) as Int
        val pixels = IntArray(width * height)
        image.javaClass.getMethod(
            "getRGB",
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            IntArray::class.java,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
        ).invoke(image, 0, 0, width, height, pixels, 0, width)
        return PixelImage(width, height, pixels)
    }

    private fun locateProjectRoot(): File {
        val workingDirectory = requireNotNull(System.getProperty("user.dir"))
        var current = File(workingDirectory).absoluteFile
        repeat(8) {
            if (File(current, "desktop/core/build.gradle.kts").isFile) return current
            current = current.parentFile ?: return@repeat
        }
        error("Cannot locate project root from ${System.getProperty("user.dir")}")
    }

    private companion object {
        /**
         * Ceiling for an aimed scan, against roughly 2 ms per window on device.
         *
         * Raised from 800 on 2026-09-20 when directed moved onto the gap-free offset grid with a
         * 4+2 proposal budget. That is a deliberate purchase, not drift: the old directed scan
         * came in under budget by being blind in 80-px bands, so it missed on nearly every hop
         * and handed the frame to a 5.3 s unrestricted rescan. Paying ~35% more for a scan that
         * actually finds the node removes both the wasted directed frames and the escalation.
         * The scan must still stay under half of a full scan, which the test also asserts.
         * Worst case here is an exactly-centred prediction over a five-type neighbourhood, which
         * keeps the most rectangles inside the tolerance: 1056 windows against a 2629-window
         * full scan on node-link-20260914.
         */
        const val DIRECTED_WINDOW_BUDGET = 1_100

        val NODE_TEMPLATE_FILES = mapOf(
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

private fun EntryPixelRect.offsetInside(
    dx: Int,
    dy: Int,
    frameWidth: Int,
    frameHeight: Int,
): EntryPixelRect? {
    val movedLeft = left + dx
    val movedTop = top + dy
    if (movedLeft < 0 || movedTop < 0 || movedLeft + width > frameWidth || movedTop + height > frameHeight) {
        return null
    }
    return EntryPixelRect(movedLeft, movedTop, width, height)
}
