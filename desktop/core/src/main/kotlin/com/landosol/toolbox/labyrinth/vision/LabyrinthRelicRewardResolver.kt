package com.landosol.toolbox.labyrinth.vision

import com.landosol.toolbox.vision.NativeBitmap
import com.landosol.toolbox.vision.PixelImage

/** A confirmed receipt stays held until its full list is recorded. */
class LabyrinthRelicRewardResolver(
    private val recognizer: LabyrinthRelicRewardRecognizer,
    submitTextRead: (NativeBitmap, (String?) -> Unit) -> Unit = DesktopChineseRoleNameOcr::readAsync,
) {
    private val ocr = RelicRoiOcr(submitTextRead, maximumAttempts = 3)
    private var active = false
    private var missingFrames = 0
    private var missingAt = Long.MIN_VALUE

    fun resolve(bitmap: NativeBitmap, frame: PixelImage, result: LabyrinthEntryFrameResult,
        capturedAtMillis: Long): LabyrinthEntryFrameResult {
        // Exact-pixel content-name reads belong to this popup and use its own read epoch.
        // Recording these relics invalidates map counters; the receipt is read once.
        if (labyrinthSpecialEquipmentOwnsFrame(result)) {
            ocr.clear(); active = false; missingFrames = 0
            return result
        }
        if (!recognizer.candidate(frame, result)) {
            if (capturedAtMillis > missingAt) { missingFrames++; missingAt = capturedAtMillis }
            val effectTitle = result.observation.anchorScores[EntryAnchorId.RELIC_EFFECT_TITLE]
            val otherTitle = maxOf(result.observation.anchorScores[EntryAnchorId.ITEM_REWARD_TITLE],
                result.observation.anchorScores[EntryAnchorId.RELIC_EFFECT_RESULT_TITLE])
            val otherReward = otherTitle >= 0.80 && otherTitle >= effectTitle
            val itemRewardPage = result.observation.state == LabyrinthEntryPageState.ITEM_REWARD
            val effectPage = itemRewardPage && effectTitle > otherTitle
            // Page classification can still own this popup below the content-proof threshold.
            // Keep its receipt held across weak frames; only a clear other reward releases it.
            if (!otherReward && (effectTitle >= 0.80 || effectPage ||
                    active && (itemRewardPage || missingFrames < 2))) {
                active = true
                return hold(result, LabyrinthRelicRewardObservation(capturedAtMillis = capturedAtMillis))
            }
            ocr.clear()
            active = false
            return result
        }
        active = true
        missingFrames = 0
        // candidate() already proves the exact effect window by its three templates.
        // Crystal and Mercury now use the same path; OCR below is content classification only.
        val kind = LabyrinthRelicRewardKind.RELIC_EFFECT
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
