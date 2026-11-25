package com.landosol.toolbox.labyrinth.vision

import com.landosol.toolbox.vision.PixelImage

/** Which 结束确认 dialog is showing on top of the battle-failure page. */
enum class LabyrinthBattleEndConfirmationStage {
    /** 取消 / 撤退（无报酬） / 返回（有报酬） */
    CHOICE,
    /** 取消 / 确认 (after 撤退 was chosen; confirming retires the run and returns the pass) */
    RETREAT_CONFIRM,
}

data class LabyrinthBattleEndConfirmationObservation(
    val stage: LabyrinthBattleEndConfirmationStage,
    val confidence: Double,
    /** 撤退（无报酬） on CHOICE, 确认 on RETREAT_CONFIRM: the button that advances the retreat. */
    val advanceButtonRect: EntryPixelRect,
)

/**
 * Detects the 结束确认 dialog family on the battle-failure page by structure only.
 *
 * Both dialogs share the blue title bar and a row of buttons at the same height. They differ in
 * layout: CHOICE has three buttons (pale 取消, blue 撤退, blue 返回); RETREAT_CONFIRM has two
 * (a wide pale 取消 that extends into where 撤退 sat, and a blue 确认 on the right). The
 * dimmed failure page behind them keeps its 结束/重新挑战 buttons visible but darkened, so the
 * battle-failure detector still fires; callers must let this observation own the frame.
 *
 * 2026-09-17: the failure page's 重新挑战 only returns to the EX challenge page and does not
 * contact the server; the retreat path (结束 → 撤退 → 确认) is what invalidates a stale
 * client session after a batch reroll.
 */
class LabyrinthBattleEndConfirmationDetector(
    private val minTitleBlueCoverage: Double = 0.60,
    private val minBlueButtonCoverage: Double = 0.55,
    private val minLightButtonCoverage: Double = 0.60,
) {
    fun detect(frame: PixelImage): LabyrinthBattleEndConfirmationObservation? {
        if (frame.width <= frame.height) return null
        val title = map(frame, TITLE_BAR_SAMPLE) ?: return null
        val left = map(frame, LEFT_BUTTON_SAMPLE) ?: return null
        val middle = map(frame, MIDDLE_BUTTON_SAMPLE) ?: return null
        val right = map(frame, RIGHT_BUTTON_SAMPLE) ?: return null
        val middleLeftHalf = map(frame, MIDDLE_LEFT_HALF_SAMPLE) ?: return null

        val titleBlue = coverage(frame, title, ::isBlue)
        if (titleBlue < minTitleBlueCoverage) return null
        val leftLight = coverage(frame, left, ::isLight)
        val rightBlue = coverage(frame, right, ::isBlue)
        if (leftLight < minLightButtonCoverage || rightBlue < minBlueButtonCoverage) return null

        val middleBlue = coverage(frame, middle, ::isBlue)
        val middleLeftLight = coverage(frame, middleLeftHalf, ::isLight)
        return when {
            middleBlue >= minBlueButtonCoverage -> LabyrinthBattleEndConfirmationObservation(
                stage = LabyrinthBattleEndConfirmationStage.CHOICE,
                confidence = (titleBlue + leftLight + middleBlue + rightBlue) / 4.0,
                advanceButtonRect = map(frame, MIDDLE_BUTTON_RECT) ?: return null,
            )
            middleLeftLight >= minLightButtonCoverage -> LabyrinthBattleEndConfirmationObservation(
                stage = LabyrinthBattleEndConfirmationStage.RETREAT_CONFIRM,
                confidence = (titleBlue + leftLight + middleLeftLight + rightBlue) / 4.0,
                advanceButtonRect = map(frame, RIGHT_BUTTON_RECT) ?: return null,
            )
            else -> null
        }
    }

    private fun map(frame: PixelImage, rect: EntryReferenceRect): EntryPixelRect? =
        ReferenceFitMapper.map(
            frameWidth = frame.width,
            frameHeight = frame.height,
            referenceSize = STANDARD_REFERENCE,
            referenceRect = rect,
        )

    private fun coverage(
        frame: PixelImage,
        rect: EntryPixelRect,
        predicate: (red: Int, green: Int, blue: Int) -> Boolean,
    ): Double {
        var matches = 0
        var samples = 0
        var y = rect.top
        while (y < rect.top + rect.height) {
            var x = rect.left
            while (x < rect.left + rect.width) {
                val color = frame[x, y]
                if (predicate(color shr 16 and 0xff, color shr 8 and 0xff, color and 0xff)) matches++
                samples++
                x += SAMPLE_STEP
            }
            y += SAMPLE_STEP
        }
        return if (samples == 0) 0.0 else matches.toDouble() / samples
    }

    companion object {
        val STANDARD_REFERENCE = EntryReferenceSize(1920, 1080)
        // Measured on 1178x663 user screenshots (2026-09-17), scaled to the 1080p reference.
        val TITLE_BAR_SAMPLE = EntryReferenceRect(560, 278, 800, 30)
        val LEFT_BUTTON_SAMPLE = EntryReferenceRect(580, 720, 180, 40)
        val MIDDLE_BUTTON_SAMPLE = EntryReferenceRect(870, 720, 180, 40)
        /** On RETREAT_CONFIRM the pale 取消 spans this strip; on CHOICE it is blue 撤退. */
        val MIDDLE_LEFT_HALF_SAMPLE = EntryReferenceRect(850, 720, 75, 40)
        val RIGHT_BUTTON_SAMPLE = EntryReferenceRect(1150, 720, 200, 40)
        val MIDDLE_BUTTON_RECT = EntryReferenceRect(835, 700, 255, 90)
        val RIGHT_BUTTON_RECT = EntryReferenceRect(1120, 700, 265, 90)
        const val SAMPLE_STEP = 4

        fun isBlue(red: Int, green: Int, blue: Int): Boolean =
            blue >= 150 && blue - red >= 35 && blue - green >= 10

        fun isLight(red: Int, green: Int, blue: Int): Boolean =
            red >= 205 && green >= 205 && blue >= 205
    }
}
