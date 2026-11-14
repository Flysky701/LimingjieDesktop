package com.landosol.toolbox.labyrinth.vision

import com.landosol.toolbox.clanbattle.recognition.PixelImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EntryTemplatePrescalerTest {
    private fun template(width: Int, height: Int) =
        PixelImage(width, height, IntArray(width * height) { 0xff808080.toInt() })

    /** Every required id is present; the one anchor under test is large enough to be shrunk. */
    private fun templateSet(): LabyrinthEntryTemplateSet {
        val values = EntryAnchorId.required.associateWith { template(4, 3) }.toMutableMap()
        values[EntryAnchorId.DAWN_START_STANDARD] = template(130, 58)
        return LabyrinthEntryTemplateSet(values)
    }

    @Test fun `baseline capture keeps the original templates`() {
        // 1920x1080 is the size the templates were cut for; building a table there would change
        // every score instead of preserving it.
        assertNull(EntryTemplatePrescaler.build(templateSet(), 1920, 1080))
    }

    @Test fun `720p table is built for the capture size`() {
        val table = requireNotNull(EntryTemplatePrescaler.build(templateSet(), 1280, 720))
        assertEquals(1280, table.frameWidth)
        assertEquals(720, table.frameHeight)
        assertTrue(table.templates.isNotEmpty())
    }

    @Test fun `every scaled template matches its own rect exactly`() {
        val table = requireNotNull(EntryTemplatePrescaler.build(templateSet(), 1280, 720))
        assertTrue(table.templates.isNotEmpty())
        for ((definition, image) in table.templates) {
            val rect = when (definition.mapping) {
                EntryReferenceMapping.COVER -> ReferenceCoverMapper.map(
                    frameWidth = 1280,
                    frameHeight = 720,
                    referenceSize = definition.referenceSize,
                    referenceRect = definition.referenceRect,
                )
                EntryReferenceMapping.FIT -> ReferenceFitMapper.map(
                    frameWidth = 1280,
                    frameHeight = 720,
                    referenceSize = definition.referenceSize,
                    referenceRect = definition.referenceRect,
                )
            }
            val expected = requireNotNull(rect) { "table holds a definition that does not map" }
            assertEquals("${definition.id} width", expected.width, image.width)
            assertEquals("${definition.id} height", expected.height, image.height)
        }
    }

    @Test fun `captures larger than the templates never produce an upscaled copy`() {
        // A box filter cannot fill the extra target pixels, so anything that would grow is left
        // on the original path instead of being guessed at.
        val table = requireNotNull(EntryTemplatePrescaler.build(templateSet(), 3840, 2160))
        assertTrue(table.templates.isEmpty())
    }

    @Test fun `shrinking is driven by the definition mapping, not by a global ratio`() {
        val table = requireNotNull(EntryTemplatePrescaler.build(templateSet(), 1280, 720))
        val definition = LabyrinthEntryFrameProcessor.DEFINITIONS.first {
            it.id == EntryAnchorId.DAWN_START_STANDARD && it.referenceSize.width == 1920
        }
        val scaled = requireNotNull(table.templates[definition])
        // 130x58 does not scale by the global 1280/1920 = 0.667 alone: the rect is what matters.
        assertTrue("${scaled.width}x${scaled.height}", scaled.width < 130 && scaled.height < 58)
        assertEquals(
            ReferenceFitMapper.map(1280, 720, definition.referenceSize, definition.referenceRect)?.width,
            scaled.width,
        )
    }
}
