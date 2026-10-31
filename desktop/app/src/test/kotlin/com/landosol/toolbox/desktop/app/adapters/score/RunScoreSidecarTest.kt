package com.landosol.toolbox.desktop.app.adapters.score

import android.graphics.Bitmap
import com.landosol.toolbox.desktop.ocr.OcrEngine
import com.landosol.toolbox.desktop.ocr.OcrEngineStatus
import com.landosol.toolbox.labyrinth.vision.LabyrinthEntryPageState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO

class RunScoreSidecarTest {
    private fun fixture(name: String): Bitmap {
        val image = ImageIO.read(File("../../android/app/src/test/resources/labyrinth/$name"))
        return Bitmap.createBitmap(image.getRGB(0, 0, image.width, image.height, null, 0, image.width), image.width, image.height, Bitmap.Config.ARGB_8888)
    }

    private fun capture(sidecar: RunScoreSidecar, id: String, bitmap: Bitmap, time: Long = 1) {
        sidecar.observe(id, LabyrinthEntryPageState.RUN_CLEAR_RESULT, bitmap, time)
        sidecar.observe(id, LabyrinthEntryPageState.RUN_CLEAR_RESULT, bitmap, time + 1)
        sidecar.observe(id, LabyrinthEntryPageState.UNKNOWN, bitmap, time + 2)
    }

    @Test fun recordedDetailsAreReadAfterSourceIsRecycledAndSummaryIsRejected() = runBlocking {
        val values = mutableListOf<Pair<String, Long>>()
        val notices = mutableListOf<String>()
        val sidecar = RunScoreSidecar({ id, score -> values += id to score }, notices::add)
        val details = fixture("run-clear-20260917/t0.5.jpg")
        val summary = fixture("run-result-next-button-20260920.jpg")
        try {
            capture(sidecar, "run:details", details)
            details.recycle()
            capture(sidecar, "run:summary", summary)
            summary.recycle()
        } finally { sidecar.closeAndJoin() }
        assertEquals(notices.toString(), listOf("run:details" to 46_000L), values)
    }

    @Test fun slowOcrDoesNotHoldFramesAndLateResultKeepsItsOriginalRun() = runBlocking {
        val requested = CountDownLatch(1)
        val delayed = CompletableFuture<(String?) -> Unit>()
        val result = CompletableFuture<Pair<String, Long>>()
        val engine = object : FakeEngine() {
            var first = true
            override fun submit(width: Int, height: Int, pixels: IntArray, onResult: (String?) -> Unit) {
                if (first) { first = false; delayed.complete(onResult); requested.countDown() }
                else super.submit(width, height, pixels, onResult)
            }
        }
        val sidecar = RunScoreSidecar({ id, score -> result.complete(id to score) }, {}, { engine })
        val bitmap = Bitmap.createBitmap(1920, 1080, Bitmap.Config.ARGB_8888)
        try {
            capture(sidecar, "run:old", bitmap)
            assertTrue(requested.await(3, TimeUnit.SECONDS))
            sidecar.observe("run:new", LabyrinthEntryPageState.UNKNOWN, bitmap, 4)
            bitmap.recycle()
            assertFalse(result.isDone)
            delayed.get().invoke("分数明细")
            assertEquals("run:old" to 46_000L, result.get(3, TimeUnit.SECONDS))
        } finally { sidecar.closeAndJoin() }
    }

    @Test fun changingTotalsDuplicateFramesAndSaveFailureNeverProduceAFalseScore() = runBlocking {
        var totalReads = 0
        val notices = mutableListOf<String>()
        val saved = mutableListOf<String>()
        val engine = object : FakeEngine() {
            override fun submit(width: Int, height: Int, pixels: IntArray, onResult: (String?) -> Unit) {
                if (width == 460) onResult(if (++totalReads == 1) "46,000" else if (totalReads == 2) "38,000" else "46,000")
                else onResult("分数明细")
            }
        }
        val sidecar = RunScoreSidecar({ id, _ -> if (id == "broken") error("storage unavailable") else saved += id }, notices::add, { engine })
        val bitmap = Bitmap.createBitmap(1920, 1080, Bitmap.Config.ARGB_8888)
        try {
            capture(sidecar, "counting", bitmap)
            sidecar.observe("duplicate", LabyrinthEntryPageState.RUN_CLEAR_RESULT, bitmap, 1)
            sidecar.observe("duplicate", LabyrinthEntryPageState.RUN_CLEAR_RESULT, bitmap, 1)
            sidecar.observe("duplicate", LabyrinthEntryPageState.UNKNOWN, bitmap, 2)
            capture(sidecar, "broken", bitmap)
            capture(sidecar, "next", bitmap)
        } finally { bitmap.recycle(); sidecar.closeAndJoin() }
        assertEquals(listOf("next"), saved)
        assertEquals(1, notices.size)
    }

    @Test fun parserAcceptsOnlyPositiveWholeTotalsAndDetailsTitle() {
        assertEquals(46_000L, parseSidecarScore(" 46，000 "))
        assertEquals(46_000L, parseSidecarScore("46000"))
        listOf(null, "", "0", "-1", "46,00", "46.000", "46000 4600", "分数46000").forEach { assertNull(it, parseSidecarScore(it)) }
        assertTrue(isScoreDetailsTitle(" 分数 明细\n"))
        assertFalse(isScoreDetailsTitle("加入的角色"))
        assertFalse(isScoreDetailsTitle("RESULT"))
    }

    private open class FakeEngine : OcrEngine {
        override val status = MutableStateFlow<OcrEngineStatus>(OcrEngineStatus.Ready)
        override fun warmUp() {}
        override fun close() {}
        override fun submit(width: Int, height: Int, pixels: IntArray, onResult: (String?) -> Unit) {
            onResult(if (width == 165) "分数明细" else "46,000")
        }
    }
}
