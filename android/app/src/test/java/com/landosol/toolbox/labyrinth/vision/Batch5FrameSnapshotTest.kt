package com.landosol.toolbox.labyrinth.vision

import com.landosol.toolbox.automation.capture.RefCountedFrameLease
import com.landosol.toolbox.clanbattle.recognition.PixelImage
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class Batch5FrameSnapshotTest {
    @Test fun snapshotBorrowsSameReadOnlyPixelsAndDoesNotInvokeLegacyCopy() {
        val pixels = intArrayOf(0x00112233, 0x40123456, 0x80765432.toInt(), 0xffabcdef.toInt(), 0x01000001, 0xffffffff.toInt())
        val snapshot = labyrinthFramePixelImage(3, 2, pixels) { error("legacy copy must not run") }
        assertSame(pixels, snapshot.pixels)
        // Includes transparent / partial-alpha colors: the pure adapter must not transform bits.
        assertArrayEquals(pixels, snapshot.pixels)
        val legacy = labyrinthFramePixelImage(3, 2, null) { PixelImage(3, 2, pixels.copyOf()) }
        assertNotSame(pixels, legacy.pixels)
        assertArrayEquals(snapshot.pixels, legacy.pixels)
    }

    @Test fun dimensionsAndArrayLengthAreValidatedBeforeAnyLegacyFallback() {
        for ((width, height, pixels) in listOf(
            Triple(0, 1, null), Triple(1, 0, null), Triple(-1, 1, null),
            Triple(Int.MAX_VALUE, 2, null), Triple(3, 2, IntArray(5)), Triple(3, 2, IntArray(7)),
        )) {
            try {
                labyrinthFramePixelImage(width, height, pixels) { error("invalid shape reached fallback") }
                fail("invalid shape accepted: ${width}x$height / ${pixels?.size}")
            } catch (_: IllegalArgumentException) { }
        }
        try {
            labyrinthFramePixelImage(3, 2, null) { PixelImage(2, 3, IntArray(6)) }
            fail("legacy wrong dimensions accepted")
        } catch (_: IllegalArgumentException) { }
    }

    @Test fun fullCoreSnapshotAndLegacyInputsHaveIdenticalCompleteOutputsExceptTiming() {
        val templates = Batch5FrameFixtures.templates()
        for (name in listOf("date-change-popup-20260910.jpg", "final-boss-active-20260909.jpg",
            "opening-dawn-star-1088-20261107.jpg", "boss-team-editor-20260909.jpg",
            "guild-bonus-joined-20260917.png", "battle-failure-two-button-20261004.jpg")) {
            val frame = Batch5FrameFixtures.fixture(name)
            val snapshot = labyrinthFramePixelImage(frame.width, frame.height, frame.pixels) { error("legacy") }
            val legacy = labyrinthFramePixelImage(frame.width, frame.height, null) {
                PixelImage(frame.width, frame.height, frame.pixels.copyOf())
            }
            val fromSnapshot = LabyrinthEntryFrameProcessor(templates).process(snapshot)
            val fromLegacy = LabyrinthEntryFrameProcessor(templates).process(legacy)
            assertEquals("fixture=$name", normalize(fromLegacy), normalize(fromSnapshot))
            assertArrayEquals(frame.pixels, snapshot.pixels) // full core did not mutate the input
        }
    }

    @Test fun laterIndependentSnapshotAndQueuedReplacementDoNotChangeOldReaderPixels() {
        // Model the producer-owned getPixels result, not Android's premultiplication behavior.
        val source = IntArray(6) { it + 10 }
        val firstSnapshot = source.copyOf()
        val released = AtomicInteger()
        val owner = RefCountedFrameLease.create(PixelImage(3, 2, firstSnapshot)) { released.incrementAndGet() }
        val oldReader = owner.retain()
        val queued = owner.retain()
        owner.close()
        source.fill(99)
        val secondSnapshot = source.copyOf()
        val replacement = RefCountedFrameLease.create(PixelImage(3, 2, secondSnapshot)) { released.incrementAndGet() }
        queued.close()
        assertNotSame(firstSnapshot, secondSnapshot)
        assertArrayEquals(IntArray(6) { it + 10 }, oldReader.value.pixels)
        assertArrayEquals(IntArray(6) { 99 }, replacement.value.pixels)
        assertEquals(0, released.get())
        oldReader.close()
        assertEquals(1, released.get())
        replacement.close()
        assertEquals(2, released.get())
    }

    @Test fun androidBoundaryUsesBitmapGetPixelsNotCaptureScratchAndChecksRecycling() {
        // Static boundary check only. Actual Bitmap alpha round trip requires Android/device tests.
        val capture = Batch5FrameFixtures.source("automation/capture/MediaProjectionCaptureService.kt")
        assertTrue(capture.contains("if (CaptureFrameBus.currentWantsArgbSnapshot())"))
        assertTrue(capture.contains("val snapshot = IntArray(converted.width * converted.height)"))
        assertTrue(capture.contains("converted.getPixels(snapshot"))
        assertTrue(capture.indexOf("converted.getPixels(snapshot") < capture.indexOf("conversionMillis = (System.nanoTime() - conversionStarted)"))
        assertTrue(capture.contains("argbPixels = argbPixels"))
        assertFalse(capture.contains("argbPixels = frameScratch"))
        assertTrue(capture.contains("private const val FRAME_INTERVAL_MILLIS = 300L"))
        val boundary = Batch5FrameFixtures.source("labyrinth/vision/AndroidLabyrinthFastFrameObserver.kt")
        assertTrue(boundary.contains("require(!bitmap.isRecycled)"))
        assertTrue(boundary.contains("labyrinthFramePixelImage(bitmap.width, bitmap.height, frame.argbPixels)"))
        val full = Batch5FrameFixtures.source("labyrinth/vision/AndroidLabyrinthEntryFrameProcessor.kt")
        assertTrue(full.contains("val pixels = capturePixels ?: capturedFramePixelImage(frame)"))
        assertTrue(full.contains("exEncounterResolver.resolve(frame.bitmap, result)"))
        assertTrue(full.contains("relicDetailResolver.resolve(frame.bitmap, nodeResolved"))
    }

    private fun normalize(result: LabyrinthEntryFrameResult): LabyrinthEntryFrameResult =
        result.copy(elapsedMillis = 0L, stageMillis = emptyMap())
}
