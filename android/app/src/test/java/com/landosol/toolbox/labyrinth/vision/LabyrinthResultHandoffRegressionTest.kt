package com.landosol.toolbox.labyrinth.vision

import com.landosol.toolbox.automation.session.SessionExpiryFrameTracker
import com.landosol.toolbox.clanbattle.recognition.PixelImage
import java.io.File
import javax.imageio.ImageIO
import org.junit.Assert.*
import org.junit.Test
import com.landosol.toolbox.automation.ScreenPoint
import com.landosol.toolbox.automation.session.AnchorTriggerSessionExpiryTerminator
import com.landosol.toolbox.automation.session.ClientTerminationResult
import kotlinx.coroutines.runBlocking

/** Recorded pixels, shipped templates, and the production tracker; no game or account access. */
class LabyrinthResultHandoffRegressionTest {
    @Test fun `character summary is not the final score page`() {
        val summary = frame("run-result-next-button-20260920.jpg")
        assertEquals(LabyrinthEntryPageState.RUN_CLEAR_CHARACTER_SUMMARY, summary.observation.state)
        assertEquals(LabyrinthEntryPageState.RUN_CLEAR_RESULT, frame("run-clear-20260917/t0.5.jpg").observation.state)
    }

    @Test fun `final result provides a bounded navigation starting point for next round`() {
        val tracker = SessionExpiryFrameTracker()
        tracker.record(frame("run-clear-20260917/t0.5.jpg"))
        assertNotNull("RESULT must be dismissible by the real reset adapter", tracker.sessionInvalidationTrigger)
        assertFalse("Dismissal is not synchronization", tracker.clientSynchronizedWithoutExpiry)
    }

    @Test fun `boss victory summary never completes a run`() {
        val completion = com.landosol.toolbox.labyrinth.LabyrinthResultCompletion()
        val page = frame("boss-win-summary-20260911.png").observation.state
        // This old fixture is UNKNOWN on the release baseline. Do not confuse a battle
        // victory (or an untrusted frame) with the whole-run terminal boundary.
        assertEquals("Known baseline fixture limitation", LabyrinthEntryPageState.UNKNOWN, page)
        assertNotEquals(LabyrinthEntryPageState.RUN_CLEAR_RESULT, page)
        assertFalse(completion.observe(page, null, 10_000, 1_000))
        assertFalse(completion.observe(page, null, 17_000, 1_000))
        assertNull(completion.summary)
    }

    @Test fun `a RESULT logo without a trusted button cannot expose the map or complete`() {
        val page = LabyrinthEntryPageClassifier().classify(LabyrinthAnchorScores(mapOf(
            EntryAnchorId.RUN_RESULT_LOGO to 0.99, EntryAnchorId.RUN_RESULT_CLOSE_BUTTON to 0.60,
            EntryAnchorId.NODE_HEADER_STANDARD to 0.90, EntryAnchorId.NODE_CHARACTERS_STANDARD to 0.90,
            EntryAnchorId.NODE_RETURN_STANDARD to 0.90,
        )))
        assertEquals(LabyrinthEntryPageState.UNKNOWN, page.state)
    }

    @Test fun `dismissal alone cannot report successful synchronization`() = runBlocking {
        val tracker = SessionExpiryFrameTracker().apply {
            trackFrame(1920, 1080); record(frame("run-clear-20260917/t0.5.jpg"))
        }
        var taps = 0
        val terminator = AnchorTriggerSessionExpiryTerminator(
            onTap = {
                taps++
                tracker.record(LabyrinthEntryFrameResult(
                    LabyrinthEntryPageClassifier().classify(LabyrinthAnchorScores(emptyMap())), emptyList(), 0))
                true
            },
            triggerPoint = { tracker.sessionInvalidationTrigger }, returnTitlePoint = ScreenPoint(960f, 740f),
            popupVisible = { tracker.popupVisible }, titleReached = { tracker.titleReached },
            synchronizedWithoutExpiry = { tracker.clientSynchronizedWithoutExpiry }, frameSize = { tracker.frameSize },
            popupTimeoutMillis = 60, popupAfterTapTimeoutMillis = 1, triggerTapDelayMillis = 0,
        )
        assertEquals(ClientTerminationResult.TIMEOUT, terminator.terminate())
        assertEquals(1, taps)
        assertFalse(tracker.clientSynchronizedWithoutExpiry)
    }

    companion object {
        private val root = generateSequence(File(".").canonicalFile, File::getParentFile)
            .first { File(it, "android/app/build.gradle.kts").isFile }
        private val templates by lazy {
            val paths = AndroidLabyrinthEntryTemplateLoader.TEMPLATE_PATHS + AndroidLabyrinthEntryTemplateLoader.OPTIONAL_TEMPLATE_PATHS
            LabyrinthEntryTemplateSet(paths.mapNotNull { (id, path) ->
                File(root, "android/app/src/main/assets/$path").takeIf { it.isFile }?.let { id to read(it) }
            }.toMap())
        }
        fun frame(name: String): LabyrinthEntryFrameResult = LabyrinthEntryFrameProcessor(templates = templates)
            .process(read(File(root, "android/app/src/test/resources/labyrinth/$name")))
        private fun read(file: File): PixelImage {
            val image = requireNotNull(ImageIO.read(file))
            return PixelImage(image.width, image.height,
                image.getRGB(0, 0, image.width, image.height, null, 0, image.width))
        }
    }
}
