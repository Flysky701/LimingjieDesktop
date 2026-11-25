package com.landosol.toolbox.labyrinth.node

import com.landosol.toolbox.vision.PixelImage
import org.junit.Assert.*
import org.junit.Test

class NodeViewportPixelsTest {
    private fun frame(shift: Int = 0): PixelImage = PixelImage(1280, 720, IntArray(1280 * 720) { index ->
        val cell = (index % 1280 - shift) / 15
        val row = index / 1280 / 5
        val value = ((cell * 73 + row * 151) xor (cell * 197 + row * 23)) and 255
        0xff000000.toInt() or (value * 0x010101)
    })
    private fun emptyMatch() = NodeMatchResult(null, 0, null, emptyList(), isComplete = false)

    @Test fun `minor brightness and localized animation do not count as map movement`() {
        val original = frame()
        val changed = original.pixels.copyOf()
        for (y in 200..220) for (x in 500..520) changed[y * 1280 + x] = 0xffffffff.toInt()
        val before = NodeViewportPixels.sample(original)
        assertEquals(NodeViewportMotion.UNCHANGED,
            NodeViewportPixels.sample(PixelImage(1280, 720, changed)).motionFrom(before))
    }

    @Test fun `coherent horizontal translation is movement even without any recognized nodes`() {
        val before = NodeViewportPixels.sample(frame())
        // 0.40 × width is the segmented scroll gesture; 512 = that gesture at 1280 wide.
        for (shift in listOf(-90, 90, 180, 400, -512, 512)) {
            val now = NodeViewportPixels.sample(frame(shift))
            assertEquals("shift=$shift", NodeViewportMotion.MOVED, now.motionFrom(before))
        }
    }

    @Test fun `unrelated pixel change remains unknown instead of confirming a swipe`() {
        val before = NodeViewportPixels.sample(frame())
        val changed = NodeViewportPixels.sample(PixelImage(1280, 720, IntArray(1280 * 720) { 0xffeeeeee.toInt() }))
        assertEquals(NodeViewportMotion.UNKNOWN, changed.motionFrom(before))
    }

    @Test fun `animated hash changes at a stationary boundary still reverse after three observations`() {
        val scanner = LabyrinthNodeViewportScanner()
        val classifier = LabyrinthNodeClassifier()
        val original = frame()
        val pixels = original.pixels.copyOf()
        val sourceSignature = classifier.viewportSignatureKey(original)
        scanner.observe(2, sourceSignature, 1280, 720, emptyMatch(), NodeViewportPixels.sample(original))
        val plan = requireNotNull(scanner.plan(20701, 7))
        scanner.recordSwipe(plan.direction, sourceSignature)
        repeat(3) { index ->
            pixels[(720 / 7) * 1280 + 1280 / 5] += 1
            val animated = PixelImage(1280, 720, pixels.copyOf())
            val signature = classifier.viewportSignatureKey(animated)
            assertNotEquals(sourceSignature, signature)
            scanner.observe(2, signature, 1280, 720, emptyMatch(), NodeViewportPixels.sample(animated))
            assertEquals(index < 2, scanner.awaitingSwipeOutcome())
        }
        assertEquals(LabyrinthMapScanDirection.BACKWARD, scanner.plan(20701, 7)?.direction)
    }

    @Test fun `full width scroll gesture is recognised at 1080p`() {
        fun frame1080(shift: Int) = PixelImage(1920, 1080, IntArray(1920 * 1080) { index ->
            val cell = (index % 1920 - shift) / 20
            val row = index / 1920 / 6
            val value = ((cell * 73 + row * 151) xor (cell * 197 + row * 23)) and 255
            0xff000000.toInt() or (value * 0x010101)
        })
        val before = NodeViewportPixels.sample(frame1080(0))
        for (shift in listOf(-768, 768, 230, -230)) {
            assertEquals("shift=$shift", NodeViewportMotion.MOVED,
                NodeViewportPixels.sample(frame1080(shift)).motionFrom(before))
        }
    }

    @Test fun `textureless frames cannot prove an edge`() {
        val blank = NodeViewportPixels.sample(PixelImage(1280, 720, IntArray(1280 * 720)))
        assertEquals(NodeViewportMotion.UNKNOWN, blank.motionFrom(blank))
    }

    @Test fun `real translation completes pending swipe without inventing a boundary`() {
        val scanner = LabyrinthNodeViewportScanner()
        scanner.observe(2, "before", 1280, 720, emptyMatch(), NodeViewportPixels.sample(frame()))
        val plan = requireNotNull(scanner.plan(20701, 7))
        scanner.recordSwipe(plan.direction, "before")
        scanner.observe(2, "after", 1280, 720, emptyMatch(), NodeViewportPixels.sample(frame(-90)))
        assertFalse(scanner.awaitingSwipeOutcome())
        assertEquals(LabyrinthMapScanDirection.FORWARD, scanner.plan(20701, 7)?.direction)
    }

    @Test fun `unknown motion permits finite probes and never manufactures a boundary`() {
        val scanner = LabyrinthNodeViewportScanner()
        scanner.observe(2, "start", 1280, 720, emptyMatch())
        val directions = mutableListOf<LabyrinthMapScanDirection>()
        repeat(4) { attempt ->
            val plan = requireNotNull(scanner.plan(20701, 7))
            directions += plan.direction
            scanner.recordSwipe(plan.direction, "start")
            repeat(6) { observation ->
                scanner.observe(2, "$attempt-$observation", 1280, 720, emptyMatch())
                assertEquals(observation < 5, scanner.awaitingSwipeOutcome())
            }
        }
        assertEquals(listOf(LabyrinthMapScanDirection.FORWARD, LabyrinthMapScanDirection.FORWARD,
            LabyrinthMapScanDirection.BACKWARD, LabyrinthMapScanDirection.BACKWARD), directions)
        assertNull(scanner.plan(20701, 7))
        assertTrue(scanner.diagnostics(20701).contains("forward=0"))
        assertTrue(scanner.diagnostics(20701).contains("backward=0"))
        assertEquals("map-motion-probes-exhausted", scanner.exhaustionReason())
        scanner.reset()
        scanner.observe(2, "new-session", 1280, 720, emptyMatch())
        assertNotNull(scanner.plan(20701, 7))
    }
}
