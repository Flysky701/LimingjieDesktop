package com.landosol.toolbox.labyrinth.vision

import android.graphics.Bitmap
import com.landosol.toolbox.clanbattle.recognition.PixelImage

/** A confirmed receipt stays held until its full list is recorded. */
class AndroidLabyrinthRelicRewardResolver(
    private val recognizer: LabyrinthRelicRewardRecognizer,
    submitTextRead: (Bitmap, (String?) -> Unit) -> Unit = AndroidChineseRoleNameOcr::readAsync,
) {
    private val ocr = AndroidRelicRoiOcr(submitTextRead, maximumAttempts = 3)
    private var active = false
    private var missingFrames = 0
    private var missingAt = Long.MIN_VALUE

    fun resolve(bitmap: Bitmap, frame: PixelImage, result: LabyrinthEntryFrameResult,
        capturedAtMillis: Long): LabyrinthEntryFrameResult {
        // Exact-pixel title/name reads belong to this popup and use its own read epoch.
        // Recording these relics invalidates map counters; the receipt is read once.
        if (labyrinthSpecialEquipmentOwnsFrame(result)) {
            ocr.clear(); active = false; missingFrames = 0
            return result
        }
        if (!recognizer.candidate(frame, result)) {
            if (capturedAtMillis > missingAt) { missingFrames++; missingAt = capturedAtMillis }
            if (active && (result.observation.state == LabyrinthEntryPageState.ITEM_REWARD || missingFrames < 2)) {
                return hold(result, LabyrinthRelicRewardObservation(capturedAtMillis = capturedAtMillis))
            }
            ocr.clear()
            active = false
            return result
        }
        active = true
        missingFrames = 0
        val rect = ReferenceFitMapper.map(bitmap.width, bitmap.height, EntryReferenceSize(1920, 1080),
            EntryReferenceRect(780, 52, 360, 65)) ?: return result
        val title = ocr.read(bitmap, "reward-title", rect, scale = 2, capturedAtMillis = capturedAtMillis)
        if (title != null && title.confirmations >= 2 &&
            (LabyrinthRelicRewardText.ordinaryTitle(title.text) || LabyrinthRelicRewardText.seriesTitle(title.text))) {
            active = false
            return result
        }
        val kind = LabyrinthRelicRewardText.kind(title?.text)
        val confirmed = title != null && title.confirmations >= 2 && kind != null
        if (!confirmed) return hold(result, LabyrinthRelicRewardObservation(capturedAtMillis = capturedAtMillis))
        val layout = recognizer.recognize(frame)
        var complete = layout.complete
        val relics = layout.rows.mapNotNull { row ->
            if (row.hasDetailButton) return@mapNotNull row.match
            // Coins and imprints can share a relic's colours, so a row without a
            // detail button is classified by its own confirmed name text.
            val name = ocr.read(bitmap, "reward-name:${row.match.slotId}", row.nameRect, scale = 2,
                capturedAtMillis = capturedAtMillis)
            if (name != null && name.confirmations >= 2 && !name.text.isNullOrBlank()) {
                if (LabyrinthRelicRewardText.nonRelic(name.text)) null else row.match
            } else {
                complete = false
                row.match.copy(relicId = null, displayName = null, attribute = null, attributeBonus = null)
            }
        }
        return hold(result, LabyrinthRelicRewardObservation(kind, true, relics, capturedAtMillis,
            complete && relics.all { it.recognized }, layout.rows.map { it.match.slotId }.toSet()))
    }

    private fun hold(result: LabyrinthEntryFrameResult, receipt: LabyrinthRelicRewardObservation) = result.copy(
        observation = result.observation.copy(state = LabyrinthEntryPageState.ITEM_REWARD,
            reason = if (receipt.titleConfirmed) "奖励结果弹窗" else "正在确认奖励标题"),
        relicRewardObservation = receipt,
        nodeClassifications = emptyList(), nodeMoveConfirmation = null,
        relicDetailObservation = null, nodeRelicStackObservation = null,
    )
}
