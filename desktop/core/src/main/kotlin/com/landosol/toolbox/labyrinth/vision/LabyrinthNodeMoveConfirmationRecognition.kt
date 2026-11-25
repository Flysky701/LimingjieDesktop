package com.landosol.toolbox.labyrinth.vision

import com.landosol.toolbox.vision.PixelImage

data class LabyrinthNodeMoveConfirmationObservation(
    val confidence: Double,
    val confirmButtonRect: EntryPixelRect,
    /** Light cancel button; the only in-dialog control that closes the modal without moving. */
    val cancelButtonRect: EntryPixelRect,
)

/**
 * Detects the common two-button dialog shown after selecting any labyrinth map node.
 *
 * The node icon and message vary by node type, so recognition deliberately uses only the stable
 * dialog structure: blue title bar, light body, light cancel button, and blue confirm button.
 * The session applies this result only while a node transition is pending.
 */
class LabyrinthNodeMoveConfirmationDetector(
    private val minTitleBlueCoverage: Double = 0.55,
    private val minBodyLightCoverage: Double = 0.75,
    private val minConfirmBlueCoverage: Double = 0.65,
    private val minCancelLightCoverage: Double = 0.55,
) {
    init {
        require(minTitleBlueCoverage in 0.0..1.0)
        require(minBodyLightCoverage in 0.0..1.0)
        require(minConfirmBlueCoverage in 0.0..1.0)
        require(minCancelLightCoverage in 0.0..1.0)
    }

    fun detect(frame: PixelImage): LabyrinthNodeMoveConfirmationObservation? {
        if (frame.width <= frame.height) return null
        val titleBar = map(frame, TITLE_BAR_SAMPLE) ?: return null
        val body = map(frame, DIALOG_BODY_SAMPLE) ?: return null
        val confirmSample = map(frame, CONFIRM_BUTTON_SAMPLE) ?: return null
        val cancelSample = map(frame, CANCEL_BUTTON_SAMPLE) ?: return null
        val confirmButton = map(frame, CONFIRM_BUTTON_RECT) ?: return null
        val cancelButton = map(frame, CANCEL_BUTTON_RECT) ?: return null

        val titleBlue = coverage(frame, titleBar, ::isDialogBlue)
        val bodyLight = coverage(frame, body, ::isDialogLight)
        val confirmBlue = coverage(frame, confirmSample, ::isDialogBlue)
        val cancelLight = coverage(frame, cancelSample, ::isDialogLight)
        if (
            titleBlue < minTitleBlueCoverage ||
            bodyLight < minBodyLightCoverage ||
            confirmBlue < minConfirmBlueCoverage ||
            cancelLight < minCancelLightCoverage
        ) {
            return null
        }

        return LabyrinthNodeMoveConfirmationObservation(
            confidence = (titleBlue + bodyLight + confirmBlue + cancelLight) / 4.0,
            confirmButtonRect = confirmButton,
            cancelButtonRect = cancelButton,
        )
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
                val red = color shr 16 and 0xff
                val green = color shr 8 and 0xff
                val blue = color and 0xff
                if (predicate(red, green, blue)) matches++
                samples++
                x += SAMPLE_STEP
            }
            y += SAMPLE_STEP
        }
        return if (samples == 0) 0.0 else matches.toDouble() / samples
    }

    private companion object {
        val STANDARD_REFERENCE = EntryReferenceSize(1920, 1080)
        val TITLE_BAR_SAMPLE = EntryReferenceRect(485, 260, 950, 65)
        val DIALOG_BODY_SAMPLE = EntryReferenceRect(500, 330, 920, 350)
        val CANCEL_BUTTON_SAMPLE = EntryReferenceRect(570, 710, 340, 60)
        val CONFIRM_BUTTON_SAMPLE = EntryReferenceRect(1_000, 710, 350, 60)
        val CONFIRM_BUTTON_RECT = EntryReferenceRect(975, 690, 415, 105)
        val CANCEL_BUTTON_RECT = EntryReferenceRect(545, 690, 415, 105)
        const val SAMPLE_STEP = 4

        fun isDialogBlue(red: Int, green: Int, blue: Int): Boolean =
            blue >= 150 && blue - red >= 35 && blue - green >= 10

        fun isDialogLight(red: Int, green: Int, blue: Int): Boolean =
            red >= 205 && green >= 205 && blue >= 205
    }
}
