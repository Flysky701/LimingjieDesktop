package com.landosol.toolbox.labyrinth.node

import com.landosol.toolbox.labyrinth.vision.EntryPixelRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LabyrinthNodeViewportScannerTest {
    @Test
    fun `area 2 ground truth pitch points a later target toward the forward segment`() {
        val scanner = LabyrinthNodeViewportScanner()
        val snapshot = scanner.observe(
            area = 2,
            viewportSignature = "area2-middle",
            frameWidth = 1920,
            frameHeight = 1080,
            matchResult = match(
                mapping(blockId = 20402, logicalColumn = 4, left = 500),
                mapping(blockId = 20501, logicalColumn = 5, left = 1040),
            ),
        )!!

        assertTrue(snapshot.geometryReliable)
        assertEquals(0.0, snapshot.columnSpacingResidual!!, 0.01)
        assertEquals(
            LabyrinthMapScanDirection.FORWARD,
            scanner.plan(targetBlockId = 20701, targetLogicalColumn = 7)!!.direction,
        )
    }

    @Test
    fun `global node coordinate is stable across overlapping viewport segments`() {
        val scanner = LabyrinthNodeViewportScanner()
        scanner.observe(
            area = 2,
            viewportSignature = "segment-a",
            frameWidth = 1920,
            frameHeight = 1080,
            matchResult = match(
                mapping(blockId = 20402, logicalColumn = 4, left = 500),
                mapping(blockId = 20501, logicalColumn = 5, left = 1040),
            ),
        )
        scanner.recordSwipe(LabyrinthMapScanDirection.FORWARD, "segment-a")
        scanner.observe(
            area = 2,
            viewportSignature = "segment-b",
            frameWidth = 1920,
            frameHeight = 1080,
            matchResult = match(
                mapping(blockId = 20501, logicalColumn = 5, left = 500),
                mapping(blockId = 20601, logicalColumn = 6, left = 1040),
            ),
        )

        val observations = scanner.globalCoordinates(20501)
        assertEquals(2, observations.size)
        assertEquals(observations[0].worldX, observations[1].worldX, 0.01)
        assertEquals(2, scanner.snapshots().size)
    }

    @Test
    fun `unchanged viewport marks an edge and reverses until both sides are exhausted`() {
        val scanner = LabyrinthNodeViewportScanner()
        val edge = match(
            mapping(blockId = 20601, logicalColumn = 6, left = 500),
            mapping(blockId = 20701, logicalColumn = 7, left = 1040),
        )
        scanner.observe(2, "right-edge", 1920, 1080, edge)
        val forward = scanner.plan(targetBlockId = 20701, targetLogicalColumn = 7)!!
        assertEquals(LabyrinthMapScanDirection.FORWARD, forward.direction)

        scanner.recordSwipe(forward.direction, "right-edge")
        repeat(3) { scanner.observe(2, "right-edge", 1920, 1080, edge) }
        val reverse = scanner.plan(targetBlockId = 20701, targetLogicalColumn = 7)!!
        assertEquals(LabyrinthMapScanDirection.BACKWARD, reverse.direction)

        scanner.recordSwipe(reverse.direction, "right-edge")
        repeat(3) { scanner.observe(2, "right-edge", 1920, 1080, edge) }
        assertNull(scanner.plan(targetBlockId = 20701, targetLogicalColumn = 7))
    }

    @Test
    fun `signature jitter without camera movement still marks the edge and reverses`() {
        val scanner = LabyrinthNodeViewportScanner()
        val edge = match(
            mapping(blockId = 20601, logicalColumn = 6, left = 500),
            mapping(blockId = 20701, logicalColumn = 7, left = 1040),
        )
        scanner.observe(2, "edge-source", 1920, 1080, edge)
        val forward = requireNotNull(scanner.plan(targetBlockId = 20801, targetLogicalColumn = 8))
        scanner.recordSwipe(forward.direction, "edge-source")
        assertTrue(scanner.awaitingSwipeOutcome())

        // The classifier/signature can jitter at a hard map edge even though the exact same
        // topology remains at the exact same screen coordinates.
        repeat(3) { index ->
            scanner.observe(2, "edge-jitter-$index", 1920, 1080, edge)
        }

        assertFalse(scanner.awaitingSwipeOutcome())
        assertEquals(
            LabyrinthMapScanDirection.BACKWARD,
            requireNotNull(scanner.plan(targetBlockId = 20801, targetLogicalColumn = 8)).direction,
        )
    }

    @Test
    fun `reliable geometry keeps an on-screen target still and only scans off-screen columns`() {
        val scanner = LabyrinthNodeViewportScanner()
        scanner.observe(
            area = 2,
            viewportSignature = "middle",
            frameWidth = 1920,
            frameHeight = 1080,
            matchResult = match(
                mapping(blockId = 20402, logicalColumn = 4, left = 500),
                mapping(blockId = 20501, logicalColumn = 5, left = 1040),
            ),
        )

        assertTrue(scanner.targetLikelyVisible(4))
        assertTrue(scanner.targetLikelyVisible(5))
        assertFalse(scanner.targetLikelyVisible(7))
    }

    @Test
    fun `actual camera movement clears pending swipe instead of declaring a boundary`() {
        val scanner = LabyrinthNodeViewportScanner()
        scanner.observe(
            2,
            "segment-a",
            1920,
            1080,
            match(
                mapping(blockId = 20402, logicalColumn = 4, left = 500),
                mapping(blockId = 20501, logicalColumn = 5, left = 1040),
            ),
        )
        scanner.recordSwipe(LabyrinthMapScanDirection.FORWARD, "segment-a")
        assertTrue(scanner.awaitingSwipeOutcome())

        scanner.observe(
            2,
            "segment-b",
            1920,
            1080,
            match(
                mapping(blockId = 20501, logicalColumn = 5, left = 500),
                mapping(blockId = 20601, logicalColumn = 6, left = 1040),
            ),
        )

        assertFalse(scanner.awaitingSwipeOutcome())
        assertEquals(
            LabyrinthMapScanDirection.FORWARD,
            requireNotNull(scanner.plan(targetBlockId = 20701, targetLogicalColumn = 7)).direction,
        )
    }

    @Test
    fun `inconsistent logical column spacing disables geometric camera projection`() {
        val scanner = LabyrinthNodeViewportScanner()
        val snapshot = scanner.observe(
            area = 2,
            viewportSignature = "warped",
            frameWidth = 1920,
            frameHeight = 1080,
            matchResult = match(
                mapping(blockId = 20402, logicalColumn = 4, left = 360),
                mapping(blockId = 20501, logicalColumn = 5, left = 1260),
            ),
        )!!

        assertFalse(snapshot.geometryReliable)
        assertNull(snapshot.viewportWorldLeft)
        assertTrue(snapshot.columnSpacingResidual!! > 150.0)
    }

    @Test
    fun `low confidence topology inherits camera geometry for at most three frames`() {
        val scanner = LabyrinthNodeViewportScanner()
        val direct = requireNotNull(scanner.observe(
            area = 2,
            viewportSignature = "direct",
            frameWidth = 1920,
            frameHeight = 1080,
            matchResult = match(
                mapping(blockId = 20402, logicalColumn = 4, left = 500),
                mapping(blockId = 20501, logicalColumn = 5, left = 1040),
            ),
        ))
        assertTrue(direct.geometryReliable)
        assertFalse(direct.geometryInferred)

        repeat(3) { index ->
            val inferred = requireNotNull(scanner.observe(
                area = 2,
                viewportSignature = "inferred-$index",
                frameWidth = 1920,
                frameHeight = 1080,
                matchResult = match(
                    mapping(blockId = 20402, logicalColumn = 4, left = 470 - index * 30, topologyConfidence = 0.40),
                    mapping(blockId = 20501, logicalColumn = 5, left = 1010 - index * 30, topologyConfidence = 0.40),
                ),
            ))
            assertTrue("frame=$index snapshot=$inferred", inferred.geometryReliable)
            assertTrue("frame=$index snapshot=$inferred", inferred.geometryInferred)
        }

        val exhausted = requireNotNull(scanner.observe(
            area = 2,
            viewportSignature = "inference-exhausted",
            frameWidth = 1920,
            frameHeight = 1080,
            matchResult = match(
                mapping(blockId = 20402, logicalColumn = 4, left = 350, topologyConfidence = 0.40),
                mapping(blockId = 20501, logicalColumn = 5, left = 890, topologyConfidence = 0.40),
            ),
        ))
        assertFalse(exhausted.geometryReliable)
        assertFalse(exhausted.geometryInferred)
        assertNull(exhausted.viewportWorldLeft)
    }

    @Test
    fun `still camera with a jittering signature keeps its prediction through pixel recall`() {
        val scanner = LabyrinthNodeViewportScanner()
        val geometry = match(
            mapping(blockId = 20402, logicalColumn = 4, left = 500),
            mapping(blockId = 20501, logicalColumn = 5, left = 1040),
        )
        val still = NodeViewportPixels.sample(texturedFrame(shift = 0))
        scanner.observe(2, "fitted", 1920, 1080, geometry, viewportPixels = still)
        val expected = requireNotNull(scanner.expectedScreenCenterX(6))

        // The glow animation changes the classification-bucketed signature and the topology fit
        // drops out, but the sampled pixels (with one localized animation patch) are the same.
        val animated = texturedFrame(shift = 0)
        for (y in 300..330) for (x in 700..730) animated.pixels[y * 1920 + x] = 0xffffffff.toInt()
        scanner.observe(
            2, "glow-jitter", 1920, 1080,
            match(mapping(blockId = 20402, logicalColumn = 4, left = 500, topologyConfidence = 0.40)),
            viewportPixels = NodeViewportPixels.sample(animated),
        )
        // Four low-confidence frames exhaust the inferred-geometry budget, so without recall the
        // prediction would be gone here.
        repeat(3) { index ->
            scanner.observe(
                2, "glow-jitter-$index", 1920, 1080,
                match(mapping(blockId = 20402, logicalColumn = 4, left = 500, topologyConfidence = 0.40)),
                viewportPixels = NodeViewportPixels.sample(animated),
            )
        }
        assertEquals(expected, requireNotNull(scanner.expectedScreenCenterX(6)), 0.01)
    }

    @Test
    fun `a different map segment never inherits a camera fit by pixel recall`() {
        val scanner = LabyrinthNodeViewportScanner()
        scanner.observe(
            2, "segment-a", 1920, 1080,
            match(
                mapping(blockId = 20402, logicalColumn = 4, left = 500),
                mapping(blockId = 20501, logicalColumn = 5, left = 1040),
            ),
            viewportPixels = NodeViewportPixels.sample(texturedFrame(shift = 0)),
        )
        assertNotNull(scanner.expectedScreenCenterX(6))

        // Same backdrop pattern scrolled by a full gesture: a different segment whose nodes could
        // not be fitted. Pixel comparison sees a translation, not a still camera.
        val scrolled = texturedFrame(shift = 768)
        repeat(4) { index ->
            scanner.observe(
                2, "segment-b-$index", 1920, 1080,
                match(mapping(blockId = 20601, logicalColumn = 6, left = 500, topologyConfidence = 0.40)),
                viewportPixels = NodeViewportPixels.sample(scrolled),
            )
        }
        assertNull(scanner.expectedScreenCenterX(6))

        // A textureless frame cannot prove anything either way.
        val blank = NodeViewportPixels.sample(
            com.landosol.toolbox.vision.PixelImage(1920, 1080, IntArray(1920 * 1080)),
        )
        scanner.observe(
            2, "blank", 1920, 1080,
            match(mapping(blockId = 20601, logicalColumn = 6, left = 500, topologyConfidence = 0.40)),
            viewportPixels = blank,
        )
        assertNull(scanner.expectedScreenCenterX(6))
    }

    @Test
    fun `pixel recall only looks at the most recent snapshots`() {
        val scanner = LabyrinthNodeViewportScanner()
        val still = NodeViewportPixels.sample(texturedFrame(shift = 0))
        scanner.observe(
            2, "old-fit", 1920, 1080,
            match(
                mapping(blockId = 20402, logicalColumn = 4, left = 500),
                mapping(blockId = 20501, logicalColumn = 5, left = 1040),
            ),
            viewportPixels = still,
        )
        val unfitted = match(mapping(blockId = 20402, logicalColumn = 4, left = 500, topologyConfidence = 0.40))
        val other = NodeViewportPixels.sample(texturedFrame(shift = 300))
        // Push the fitted snapshot past the recall window (three snapshots) with frames that do
        // not match it.
        repeat(4) { index ->
            scanner.observe(2, "filler-$index", 1920, 1080, unfitted, viewportPixels = other)
        }
        scanner.observe(2, "back-to-still", 1920, 1080, unfitted, viewportPixels = still)
        assertNull(scanner.expectedScreenCenterX(6))
    }

    private fun texturedFrame(shift: Int) = com.landosol.toolbox.vision.PixelImage(
        1920, 1080,
        IntArray(1920 * 1080) { index ->
            val cell = (index % 1920 - shift) / 20
            val row = index / 1920 / 6
            val value = ((cell * 73 + row * 151) xor (cell * 197 + row * 23)) and 255
            0xff000000.toInt() or (value * 0x010101)
        },
    )

    private fun match(vararg mappings: NodeTopologyMapping) = NodeMatchResult(
        currentNodeId = 20402,
        currentNodeType = LabyrinthNodeTypes.RELIC,
        nextNode = null,
        visibleNodes = emptyList(),
        topologyMappings = mappings.toList(),
        isComplete = false,
    )

    private fun mapping(
        blockId: Long,
        logicalColumn: Int,
        left: Int,
        topologyConfidence: Double = 0.90,
    ) = NodeTopologyMapping(
        blockId = blockId,
        expectedBlockType = LabyrinthNodeTypes.NORMAL_BATTLE,
        logicalColumn = logicalColumn,
        visualColumn = logicalColumn,
        visualRow = 1,
        screenRect = EntryPixelRect(left = left, top = 300, width = 280, height = 350),
        detectedBlockType = LabyrinthNodeTypes.NORMAL_BATTLE,
        visualConfidence = 0.80,
        topologyConfidence = topologyConfidence,
        isClickable = true,
        bindingKind = NodeTopologyBindingKind.FULL_COLUMN,
    )
}
