package com.landosol.toolbox.labyrinth.vision

import com.landosol.toolbox.clanbattle.recognition.PixelImage

/** Catalog-backed template for one maze relic icon. */
data class LabyrinthRelicTemplate(
    val relicId: String,
    val displayName: String,
    val attribute: String,
    val attributeBonus: String,
    val effect: String,
    val image: PixelImage,
    /** Workbook rarity (1-4). 0 means the pack entry carried no rarity. */
    val rarity: Int = 0,
)

data class LabyrinthRelicMatch(
    val slotId: String,
    val relicId: String?,
    val displayName: String?,
    val attribute: String?,
    val attributeBonus: String?,
    val effect: String?,
    val confidence: Double,
    val screenRect: EntryPixelRect,
    val selectionButtonRect: EntryPixelRect = screenRect,
    val iconRect: EntryPixelRect,
    /** Rarity of the matched template; 0 when unrecognized. */
    val rarity: Int = 0,
    val suspectedRelicId: String? = relicId,
    val suspectedDisplayName: String? = displayName,
    val suspectedAttribute: String? = attribute,
    val rivalConfidence: Double = 0.0,
    val rivalMargin: Double = if (relicId != null) 1.0 else 0.0,
    val currentMarkStacks: Int? = null,
    val currentMarkStacksEvidenceText: String? = null,
    val currentMarkStacksEvidenceId: Long? = null,
    val currentMarkStacksConfirmations: Int = 0,
    val currentMarkStacksCapturedAtMillis: Long = Long.MIN_VALUE,
) {
    val recognized: Boolean get() = relicId != null
}

data class LabyrinthRelicChoiceObservation(
    val choices: List<LabyrinthRelicMatch>,
) {
    val recognizedCount: Int get() = choices.count(LabyrinthRelicMatch::recognized)
}

/** One mark/count pair shown in the compact relic summary panel on the node map. */
data class LabyrinthNodeRelicMarkStack(
    val slotId: String,
    val attribute: String?,
    val stacks: Int?,
    val iconConfidence: Double,
    val evidenceText: String? = null,
    /** Repeated cached OCR reads share an ID and must not count as new confirmations. */
    val evidenceId: Long? = null,
    val capturedAtMillis: Long = Long.MIN_VALUE,
) {
    val recognized: Boolean get() = !attribute.isNullOrBlank() && stacks != null
}

data class LabyrinthNodeRelicStackObservation(
    val entries: List<LabyrinthNodeRelicMarkStack>,
) {
    val recognizedCount: Int get() = entries.count(LabyrinthNodeRelicMarkStack::recognized)
}

/**
 * Matches the fixed three-card relic-choice layout against the catalog icons.
 *
 * The icon supplies the stable identity. The attribute is read from the same catalog entry,
 * rather than guessed from the card color, so a relic is persisted with its actual mark
 * (强化/弱体/守备/加速) even when the page is resized or animated.
 */
class LabyrinthRelicRecognizer(
    private val templates: List<LabyrinthRelicTemplate> = emptyList(),
    private val matcher: GradientTemplateMatcher = GradientTemplateMatcher(maxSamples = 1_200),
    private val minConfidence: Double = 0.55,
    private val minMargin: Double = 0.05,
) {
    init {
        require(minConfidence in 0.0..1.0)
        require(minMargin in 0.0..1.0)
        require(templates.all { it.relicId.isNotBlank() && it.attribute.isNotBlank() })
    }

    fun recognize(frame: PixelImage): LabyrinthRelicChoiceObservation =
        SLOT_LAYOUTS.map { slot ->
            val screenRect = ReferenceFitMapper.map(
                frameWidth = frame.width,
                frameHeight = frame.height,
                referenceSize = STANDARD_REFERENCE,
                referenceRect = slot.cardRect,
            )
            val iconRect = ReferenceFitMapper.map(
                frameWidth = frame.width,
                frameHeight = frame.height,
                referenceSize = STANDARD_REFERENCE,
                referenceRect = slot.iconRect,
            )
            val selectionButtonRect = ReferenceFitMapper.map(
                frameWidth = frame.width,
                frameHeight = frame.height,
                referenceSize = STANDARD_REFERENCE,
                referenceRect = slot.selectionButtonRect,
            )
            if (screenRect == null || iconRect == null || selectionButtonRect == null) {
                unknown(slot, screenRect, selectionButtonRect, iconRect)
            } else {
                recognizeSlot(frame, slot, screenRect, selectionButtonRect, iconRect)
            }
        }.let(::LabyrinthRelicChoiceObservation)

    private fun recognizeSlot(
        frame: PixelImage,
        slot: Slot,
        screenRect: EntryPixelRect,
        selectionButtonRect: EntryPixelRect,
        iconRect: EntryPixelRect,
    ): LabyrinthRelicMatch {
        val ranked = templates
            .map { template ->
                template to matcher.score(
                    frame = frame,
                    rect = iconRect,
                    template = template.image,
                    minimumChannelScore = MINIMUM_CHANNEL_SCORE,
                )
            }
            .sortedByDescending { (_, score) -> score }
        val best = ranked.firstOrNull()
        val secondScore = ranked.getOrNull(1)?.second ?: 0.0
        val score = best?.second ?: 0.0
        val trusted = best != null &&
            score >= minConfidence &&
            score - secondScore >= minMargin
        val template = best?.first?.takeIf { trusted }
        return LabyrinthRelicMatch(
            slotId = slot.id,
            relicId = template?.relicId,
            displayName = template?.displayName,
            attribute = template?.attribute,
            attributeBonus = template?.attributeBonus,
            effect = template?.effect,
            rarity = template?.rarity ?: 0,
            confidence = score,
            screenRect = screenRect,
            selectionButtonRect = selectionButtonRect,
            iconRect = iconRect,
            suspectedRelicId = best?.first?.relicId,
            suspectedDisplayName = best?.first?.displayName,
            suspectedAttribute = best?.first?.attribute,
            rivalConfidence = secondScore,
            rivalMargin = score - secondScore,
        )
    }

    private fun unknown(
        slot: Slot,
        screenRect: EntryPixelRect?,
        selectionButtonRect: EntryPixelRect?,
        iconRect: EntryPixelRect?,
    ) = LabyrinthRelicMatch(
        slotId = slot.id,
        relicId = null,
        displayName = null,
        attribute = null,
        attributeBonus = null,
        effect = null,
        confidence = 0.0,
        screenRect = screenRect ?: slot.cardRect.toPixelRect(),
        selectionButtonRect = selectionButtonRect ?: slot.selectionButtonRect.toPixelRect(),
        iconRect = iconRect ?: slot.iconRect.toPixelRect(),
    )

    private data class Slot(
        val id: String,
        val cardRect: EntryReferenceRect,
        val selectionButtonRect: EntryReferenceRect,
        val iconRect: EntryReferenceRect,
    )

    private companion object {
        const val MINIMUM_CHANNEL_SCORE = 0.20
        val STANDARD_REFERENCE = EntryReferenceSize(1920, 1080)
        val SLOT_LAYOUTS = listOf(
            Slot(
                id = "relic_choice_1",
                cardRect = EntryReferenceRect(158, 297, 495, 695),
                selectionButtonRect = EntryReferenceRect(264, 812, 282, 142),
                iconRect = EntryReferenceRect(284, 324, 240, 240),
            ),
            Slot(
                id = "relic_choice_2",
                cardRect = EntryReferenceRect(713, 297, 495, 695),
                selectionButtonRect = EntryReferenceRect(819, 812, 282, 142),
                iconRect = EntryReferenceRect(839, 324, 240, 240),
            ),
            Slot(
                id = "relic_choice_3",
                cardRect = EntryReferenceRect(1268, 297, 495, 695),
                selectionButtonRect = EntryReferenceRect(1374, 812, 282, 142),
                iconRect = EntryReferenceRect(1394, 324, 240, 240),
            ),
        )

        fun EntryReferenceRect.toPixelRect() = EntryPixelRect(x, y, width, height)
    }
}
