package com.landosol.toolbox.labyrinth.vision

import android.graphics.Bitmap

/** Read only the large total under RESULT, never reward amounts or template confidence. */
class AndroidLabyrinthResultScoreResolver(
    submitTextRead: (Bitmap, (String?) -> Unit) -> Unit = AndroidChineseRoleNameOcr::readAsync,
) {
    private val ocr = AndroidRelicRoiOcr(submitTextRead)

    fun resolve(bitmap: Bitmap, result: LabyrinthEntryFrameResult): LabyrinthEntryFrameResult {
        if (result.observation.state != LabyrinthEntryPageState.RUN_CLEAR_RESULT) {
            ocr.clear()
            return result
        }
        val rect = ReferenceFitMapper.map(bitmap.width, bitmap.height,
            EntryReferenceSize(1920, 1080), EntryReferenceRect(730, 195, 460, 105)) ?: return result
        val read = ocr.read(bitmap, "run_total_score", rect)
        return result.copy(runResultScore = read?.takeIf { it.confirmations >= 2 }?.text?.let(::parseRunResultScore))
    }
}

internal fun parseRunResultScore(text: String): Long? {
    val value = text.trim().replace('，', ',')
    if (!Regex("(?:[0-9]{1,9}|[0-9]{1,3}(?:,[0-9]{3}){1,2})").matches(value)) return null
    return value.replace(",", "").toLongOrNull()?.takeIf { it > 0 }
}
