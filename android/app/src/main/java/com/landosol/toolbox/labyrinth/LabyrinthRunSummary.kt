package com.landosol.toolbox.labyrinth

import com.landosol.toolbox.labyrinth.vision.LabyrinthEntryPageState
import kotlinx.serialization.Serializable

/** The result page ends the run; optional score collection never changes that outcome. */
@Serializable
data class LabyrinthRunSummary(val startedAt: Long, val endedAt: Long, val score: Long? = null) {
    val durationMillis: Long get() = (endedAt - startedAt).coerceAtLeast(0)
    fun description(): String = "本局通关 · 用时${durationMillis / 60_000}分${durationMillis / 1000 % 60}秒 · 分数${score?.toString() ?: "未识别"}"
}

/** Owns frames from the first RESULT candidate, so map transitions cannot restart node planning. */
internal class LabyrinthResultCompletion {
    private var candidateAt: Long? = null
    private var resultAt: Long? = null
    private var resultFrames = 0
    private var candidateScore: Long? = null
    private var scoreSince = 0L
    var summary: LabyrinthRunSummary? = null
        private set

    fun observe(page: LabyrinthEntryPageState, score: Long?, now: Long, startedAt: Long): Boolean {
        if (summary != null) return true
        if (page == LabyrinthEntryPageState.RUN_CLEAR_RESULT) {
            if (candidateAt == null) candidateAt = now
            resultFrames++
            if (resultFrames >= 2 && resultAt == null) resultAt = candidateAt
        } else if (resultAt == null) {
            candidateAt = null
            resultFrames = 0
            return false
        }
        val first = candidateAt ?: return false
        val currentScore = score.takeIf { page == LabyrinthEntryPageState.RUN_CLEAR_RESULT }
        if (candidateScore != currentScore) {
            candidateScore = currentScore
            scoreSince = now
        }
        val endedAt = resultAt ?: return true
        // Two OCR reads are required by the resolver; also let count-up animation settle.
        val stableScore = candidateScore?.takeIf { now - scoreSince >= 1_000 && now - first >= 2_000 }
        if (stableScore != null || now - first >= 6_000) {
            summary = LabyrinthRunSummary(startedAt, endedAt, stableScore)
        }
        return true
    }
}
