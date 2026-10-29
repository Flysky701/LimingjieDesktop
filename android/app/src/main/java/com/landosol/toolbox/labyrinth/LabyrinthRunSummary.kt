package com.landosol.toolbox.labyrinth

import com.landosol.toolbox.labyrinth.vision.LabyrinthEntryPageState
import kotlinx.serialization.Serializable

/** Published after settlement returns to the idle maze home; score collection is optional. */
@Serializable
data class LabyrinthRunSummary(val startedAt: Long, val endedAt: Long, val score: Long? = null) {
    val durationMillis: Long get() = (endedAt - startedAt).coerceAtLeast(0)
    fun description(): String = "本局通关 · 用时${durationMillis / 60_000}分${durationMillis / 1000 % 60}秒 · 分数${score?.toString() ?: "未识别"}"
}

/** Observes confirmed score reads without owning frames, waiting, or ending the run. */
internal class LabyrinthRunScoreCollector {
    private var resultFrames = 0
    private var resultSince = 0L
    private var candidateScore: Long? = null
    private var scoreSince = 0L
    var score: Long? = null
        private set

    fun observe(page: LabyrinthEntryPageState, confirmedScore: Long?, now: Long) {
        if (page != LabyrinthEntryPageState.RUN_CLEAR_RESULT) {
            resultFrames = 0
            candidateScore = null
            return
        }
        if (resultFrames == 0) resultSince = now
        resultFrames++
        if (candidateScore != confirmedScore) {
            candidateScore = confirmedScore
            scoreSince = now
            if (confirmedScore != null && confirmedScore != score) score = null
        }
        // Keep the count-up stability thresholds, but never delay settlement for OCR.
        // The resolver also requires two independent OCR reads of the same pixels.
        if (resultFrames >= 2 && confirmedScore != null &&
            now - scoreSince >= 1_000 && now - resultSince >= 2_000) {
            score = confirmedScore
        }
    }
}
