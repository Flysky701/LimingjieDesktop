package com.landosol.toolbox.labyrinth.replay

import com.landosol.toolbox.vision.PixelImage
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Phase zero of the revised plan: replay stored frames through the real recognition pipeline and
 * print the statistics later phases are measured against. Never dispatches an action.
 *
 * Set `LABYRINTH_REPLAY_BUNDLE` to an unzipped debug bundle that was captured with the dashboard's
 * per-frame archive enabled to replay a live run instead of the checked-in fixtures.
 */
class LabyrinthReplayHarnessTest {
    private val projectRoot = locateProjectRoot()
    private val assetRoot = File(projectRoot, "desktop/core/src/main/resources/assets/resource-packs/cn-bilibili/vision")
    private val fixtureRoot = File(projectRoot, "desktop/core/src/test/resources/labyrinth")

    @Test
    fun `history line parser reads the scalar fields the harness compares`() {
        val line = "{\"timestamp\":1,\"page\":\"NODE_SELECTION\",\"elapsedMillis\":2430,\"nodeSearchMode\":\"full\"," +
            "\"nodeSearchWindowCount\":812,\"archivedFrame\":\"000012-f40.jpg\"," +
            "\"trace\":{\"actionLabel\":\"点击节点 商店#40201\",\"actionRejectReason\":null}}"
        val recorded = requireNotNull(LabyrinthReplayHarness.parseRecordedFrame(line))
        assertEquals("NODE_SELECTION", recorded.page)
        assertEquals(2430L, recorded.elapsedMillis)
        assertEquals("full", recorded.nodeSearchMode)
        assertEquals(812, recorded.nodeSearchWindowCount)
        assertEquals("000012-f40.jpg", recorded.archivedFrame)
        assertEquals("点击节点 商店#40201", recorded.actionLabel)
        assertEquals(null, recorded.actionRejectReason)
    }

    @Test
    fun `checked in fixtures replay through the real pipeline and report search cost`() {
        val fixtures = fixtureRoot.listFiles().orEmpty()
            .filter { it.isFile && it.extension.lowercase() in setOf("png", "jpg", "jpeg") }
            .sortedBy(File::getName)
        assumeTrue("no fixtures under ${fixtureRoot.absolutePath}", fixtures.isNotEmpty())
        val harness = LabyrinthReplayHarness(
            processorFactory = { LabyrinthReplayHarness.defaultProcessor(assetRoot, ::readImage) },
            readImage = ::readImage,
        )

        val frames = harness.replayScreenshots(fixtures)
        val summary = harness.summarize(frames)
        println("replay-fixtures\n" + summary.report())
        frames.forEach { frame ->
            println(
                "replay-frame ${frame.source} page=${frame.result.observation.state} " +
                    "ms=${frame.wallMillis} mode=${frame.result.nodeSearchMode} windows=${frame.result.nodeSearchWindowCount}",
            )
        }

        assertEquals(fixtures.size, summary.totalFrames)
        // Every fixture that reached the node scan must report how many windows it scored.
        assertTrue(frames.filter { it.result.nodeSearchMode == "full" }.all { it.result.nodeSearchWindowCount > 0 })
        assertTrue(frames.filter { it.result.nodeSearchMode == "none" }.all { it.result.nodeSearchWindowCount == 0 })
    }

    @Test
    fun `optional live bundle replays against its recorded pages`() {
        val bundle = System.getenv("LABYRINTH_REPLAY_BUNDLE")?.takeIf(String::isNotBlank)?.let(::File)
        assumeTrue("LABYRINTH_REPLAY_BUNDLE not set", bundle != null && bundle.isDirectory)
        val harness = LabyrinthReplayHarness(
            processorFactory = { LabyrinthReplayHarness.defaultProcessor(assetRoot, ::readImage) },
            readImage = ::readImage,
        )
        val frames = harness.replayBundle(requireNotNull(bundle))
        assumeTrue("bundle has no archived frames; enable 逐帧归档 on the dashboard before downloading", frames.isNotEmpty())
        val summary = harness.summarize(frames)
        println("replay-bundle ${bundle.name}\n" + summary.report())
        frames.filter { it.recorded != null && it.recorded.page != it.result.observation.state.name }.forEach {
            println("replay-disagreement ${it.source} recorded=${it.recorded?.page} replayed=${it.result.observation.state}")
        }
    }

    private fun readImage(file: File): PixelImage {
        val imageIo = Class.forName("javax.imageio.ImageIO")
        val image = requireNotNull(imageIo.getMethod("read", File::class.java).invoke(null, file)) {
            "Cannot decode ${file.absolutePath}"
        }
        val width = image.javaClass.getMethod("getWidth").invoke(image) as Int
        val height = image.javaClass.getMethod("getHeight").invoke(image) as Int
        val pixels = IntArray(width * height)
        image.javaClass.getMethod(
            "getRGB",
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
            IntArray::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
        ).invoke(image, 0, 0, width, height, pixels, 0, width)
        return PixelImage(width, height, pixels)
    }

    private fun locateProjectRoot(): File {
        var current = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        repeat(8) {
            if (File(current, "desktop/core/build.gradle.kts").isFile) return current
            current = current.parentFile ?: return@repeat
        }
        error("Cannot locate project root")
    }
}
