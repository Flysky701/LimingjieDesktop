package com.landosol.toolbox.labyrinth.vision

import com.landosol.toolbox.vision.PixelImage
import com.landosol.toolbox.labyrinth.LabyrinthCharacterAttribute
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** Legacy name-row fixture retained only for historical screenshot tests. */
data class LabyrinthCharacterTemplate(
    val slotId: String,
    val characterId: String,
    val displayName: String,
    val image: PixelImage,
)

/** Legacy name-row fixture retained only for historical screenshot tests. */
data class LabyrinthCharacterTemplateSet(
    val templates: List<LabyrinthCharacterTemplate> = emptyList(),
)

data class LabyrinthCharacterMatch(
    val slotId: String,
    val characterId: String?,
    val displayName: String?,
    val confidence: Double,
    val screenRect: EntryPixelRect,
    val iconVariant: String? = null,
    val trusted: Boolean = characterId != null,
    val rivalMargin: Double = if (trusted) 1.0 else 0.0,
    val suspectedCharacterId: String? = characterId,
    val suspectedDisplayName: String? = displayName,
    val iconCandidates: List<LabyrinthCharacterIconCandidate> = emptyList(),
    val nameEvidenceText: String? = null,
    val nameEvidenceScore: Double = 0.0,
    val nameEvidenceMargin: Double = 0.0,
    val nameAssisted: Boolean = false,
)

/**
 * Defines the page-specific icon slots for role reward and character-joined pages. Character
 * Reward portraits are deferred until name OCR is unreliable; joined pages normally use icons.
 */
class LabyrinthCharacterRecognizer(
    private val legacyTemplates: LabyrinthCharacterTemplateSet = LabyrinthCharacterTemplateSet(),
    private val legacyMatcher: GradientTemplateMatcher = GradientTemplateMatcher(maxSamples = 1_200),
    private val iconMatcher: LabyrinthCharacterIconMatcher? = null,
) {
    constructor(iconMatcher: LabyrinthCharacterIconMatcher) : this(
        legacyTemplates = LabyrinthCharacterTemplateSet(),
        iconMatcher = iconMatcher,
    )

    fun recognizeRoleRewardChoices(frame: PixelImage): List<LabyrinthCharacterMatch> =
        recognizeSlots(frame, ROLE_REWARD_ICON_SLOTS, ROLE_REWARD_LEGACY_NAME_SLOTS)

    fun roleRewardSlots(frame: PixelImage): List<LabyrinthCharacterMatch> =
        ROLE_REWARD_ICON_SLOTS.map { slot ->
            emptyMatch(slot.id).copy(screenRect = ReferenceFitMapper.map(
                frame.width, frame.height, STANDARD_REFERENCE, slot.referenceRect,
            ) ?: emptyMatch(slot.id).screenRect)
        }

    fun recognizeRoleRewardSlot(frame: PixelImage, slotId: String): LabyrinthCharacterMatch =
        recognizeIconSlot(frame, ROLE_REWARD_ICON_SLOTS.single { it.id == slotId })

    fun recognizeJoinedCharacters(frame: PixelImage): List<LabyrinthCharacterMatch> =
        recognizeSlots(frame, JOINED_ICON_SLOTS, JOINED_LEGACY_NAME_SLOTS)

    private fun recognizeSlots(
        frame: PixelImage,
        iconSlots: List<IconSlot>,
        legacySlots: List<LegacySlot>,
    ): List<LabyrinthCharacterMatch> = if (iconMatcher != null) {
        iconSlots.map { slot -> recognizeIconSlot(frame, slot) }
    } else {
        recognizeLegacySlots(frame, legacySlots)
    }

    private fun recognizeIconSlot(frame: PixelImage, slot: IconSlot): LabyrinthCharacterMatch {
        val rect = ReferenceFitMapper.map(
            frameWidth = frame.width,
            frameHeight = frame.height,
            referenceSize = STANDARD_REFERENCE,
            referenceRect = slot.referenceRect,
        ) ?: return emptyMatch(slot.id)
        val requiredAttribute = if (slot.useAttributeBadge) recognizeRoleCardAttribute(frame, rect) else null
        val matcher = requireNotNull(iconMatcher)
        val initial = matcher.match(
            frame = frame,
            iconRect = rect,
            mask = slot.mask,
            requiredAttribute = requiredAttribute,
        )
        // Reward portraits are large and stationary, but their rendered card border can shift a
        // few pixels between emulator scaling paths.  The shared icon matcher intentionally has a
        // permissive global trust floor; the irreversible three-choice page is stricter.  When an
        // otherwise plausible reward portrait is close to that stricter line, spend a bounded
        // local geometry search instead of repeatedly returning the same marginal crop forever.
        val match = if (
            slot.refineAmbiguousGeometry &&
            (initial.confidence < ROLE_REWARD_GEOMETRY_REFINE_CONFIDENCE ||
                initial.rivalMargin < ROLE_REWARD_GEOMETRY_REFINE_MARGIN)
        ) {
            matcher.refineMemberGeometry(
                frame = frame,
                iconRect = rect,
                initial = initial,
                mask = slot.mask,
                requiredAttribute = requiredAttribute,
                force = true,
            )
        } else {
            initial
        }
        return LabyrinthCharacterMatch(
            slotId = slot.id,
            characterId = match.characterId,
            displayName = match.displayName,
            confidence = match.confidence,
            screenRect = rect,
            iconVariant = match.iconVariant,
            trusted = match.trusted,
            rivalMargin = match.rivalMargin,
            suspectedCharacterId = match.suspectedCharacterId,
            suspectedDisplayName = match.suspectedDisplayName,
            iconCandidates = match.candidates,
        )
    }

    /** Old fixed-name matching stays reachable only for the legacy fixture constructor. */
    private fun recognizeLegacySlots(
        frame: PixelImage,
        slots: List<LegacySlot>,
    ): List<LabyrinthCharacterMatch> = slots.map { slot ->
        val rect = ReferenceFitMapper.map(
            frameWidth = frame.width,
            frameHeight = frame.height,
            referenceSize = STANDARD_REFERENCE,
            referenceRect = slot.referenceRect,
        ) ?: return@map emptyMatch(slot.id)
        val best = legacyTemplates.templates
            .filter { it.slotId == slot.id }
            .map { template ->
                template to legacyMatcher.score(
                    frame = frame,
                    rect = rect,
                    template = template.image,
                    minimumChannelScore = LEGACY_MINIMUM_CHANNEL_SCORE,
                )
            }
            .maxByOrNull { (_, score) -> score }
        val score = best?.second ?: 0.0
        val trusted = best != null && score >= LEGACY_MINIMUM_CONFIDENCE
        val template = best?.first?.takeIf { trusted }
        LabyrinthCharacterMatch(
            slotId = slot.id,
            characterId = template?.characterId,
            displayName = template?.displayName,
            confidence = score,
            screenRect = rect,
            trusted = trusted,
            suspectedCharacterId = best?.first?.characterId,
            suspectedDisplayName = best?.first?.displayName,
        )
    }

    private fun emptyMatch(slotId: String) = LabyrinthCharacterMatch(
        slotId = slotId,
        characterId = null,
        displayName = null,
        confidence = 0.0,
        screenRect = EntryPixelRect(0, 0, 3, 3),
        trusted = false,
    )

    private data class IconSlot(
        val id: String,
        val referenceRect: EntryReferenceRect,
        val mask: LabyrinthCharacterIconMask = LabyrinthCharacterIconMask(),
        val useAttributeBadge: Boolean = false,
        val refineAmbiguousGeometry: Boolean = false,
    )

    /**
     * Reads the elemental badge at the bottom-right of a role-reward portrait. The badge is used
     * only to narrow the full-icon candidate set; an uncertain badge simply returns null and the
     * original all-character matcher remains unchanged.
     */
    private fun recognizeRoleCardAttribute(
        frame: PixelImage,
        iconRect: EntryPixelRect,
    ): LabyrinthCharacterAttribute? {
        val left = iconRect.left + (iconRect.width * ATTRIBUTE_BADGE_LEFT_RATIO).toInt()
        val top = iconRect.top + (iconRect.height * ATTRIBUTE_BADGE_TOP_RATIO).toInt()
        val width = (iconRect.width * ATTRIBUTE_BADGE_WIDTH_RATIO).toInt().coerceAtLeast(8)
        val height = (iconRect.height * ATTRIBUTE_BADGE_HEIGHT_RATIO).toInt().coerceAtLeast(8)
        if (left < 0 || top < 0 || left + width > frame.width || top + height > frame.height) return null

        var vectorX = 0.0
        var vectorY = 0.0
        var accepted = 0
        var total = 0
        val step = maxOf(1, min(width, height) / 16)
        var y = top
        while (y < top + height) {
            var x = left
            while (x < left + width) {
                total++
                val hsv = rgbToHsv(frame[x, y])
                if (hsv.saturation >= ATTRIBUTE_MIN_SATURATION && hsv.value >= ATTRIBUTE_MIN_VALUE) {
                    val angle = hsv.hue * TWO_PI
                    vectorX += cos(angle) * hsv.saturation
                    vectorY += sin(angle) * hsv.saturation
                    accepted++
                }
                x += step
            }
            y += step
        }
        if (total == 0 || accepted.toDouble() / total < ATTRIBUTE_MIN_SIGNAL) return null
        var hue = atan2(vectorY, vectorX) / TWO_PI
        if (hue < 0.0) hue += 1.0
        val ranked = ATTRIBUTE_HUES
            .map { (attribute, expectedHue) -> attribute to hueScore(hue, expectedHue) }
            .sortedByDescending { it.second }
        val best = ranked.firstOrNull() ?: return null
        val second = ranked.getOrNull(1)?.second ?: 0.0
        return best.first.takeIf {
            best.second >= ATTRIBUTE_MIN_CONFIDENCE && best.second - second >= ATTRIBUTE_MIN_MARGIN
        }
    }

    private fun hueScore(first: Double, second: Double): Double {
        val distance = kotlin.math.abs(first - second).let { min(it, 1.0 - it) }
        return (1.0 - distance / 0.5).coerceIn(0.0, 1.0)
    }

    private fun rgbToHsv(color: Int): Hsv {
        val red = (color ushr 16 and 0xff) / 255.0
        val green = (color ushr 8 and 0xff) / 255.0
        val blue = (color and 0xff) / 255.0
        val maximum = maxOf(red, green, blue)
        val minimum = minOf(red, green, blue)
        val delta = maximum - minimum
        val hue = when {
            delta <= 1e-9 -> 0.0
            maximum == red -> ((green - blue) / delta / 6.0).let { if (it < 0) it + 1.0 else it }
            maximum == green -> ((blue - red) / delta + 2.0) / 6.0
            else -> ((red - green) / delta + 4.0) / 6.0
        }
        return Hsv(
            hue = hue,
            saturation = if (maximum <= 1e-9) 0.0 else delta / maximum,
            value = maximum,
        )
    }

    private data class Hsv(val hue: Double, val saturation: Double, val value: Double)

    private data class LegacySlot(
        val id: String,
        val referenceRect: EntryReferenceRect,
    )

    private companion object {
        const val LEGACY_MINIMUM_CHANNEL_SCORE = 0.25
        const val LEGACY_MINIMUM_CONFIDENCE = 0.55
        val STANDARD_REFERENCE = EntryReferenceSize(1920, 1080)

        val ROLE_REWARD_ICON_SLOTS = listOf(
            IconSlot(
                "role_reward_left",
                EntryReferenceRect(286, 365, 240, 240),
                LabyrinthCharacterIconMask(ignoreRightEdge = true, ignoreBottomLeft = true),
                useAttributeBadge = true,
                refineAmbiguousGeometry = true,
            ),
            IconSlot(
                "role_reward_center",
                EntryReferenceRect(841, 365, 240, 240),
                LabyrinthCharacterIconMask(ignoreRightEdge = true, ignoreBottomLeft = true),
                useAttributeBadge = true,
                refineAmbiguousGeometry = true,
            ),
            IconSlot(
                "role_reward_right",
                EntryReferenceRect(1395, 365, 240, 240),
                LabyrinthCharacterIconMask(ignoreRightEdge = true, ignoreBottomLeft = true),
                useAttributeBadge = true,
                refineAmbiguousGeometry = true,
            ),
        )

        // This is not a relaxed identity threshold. It only decides whether the fixed reward-card
        // crop deserves the existing bounded ±pixel geometry calibration before the normal safety
        // gates are evaluated by the role-reward planner.
        const val ROLE_REWARD_GEOMETRY_REFINE_CONFIDENCE = 0.60
        const val ROLE_REWARD_GEOMETRY_REFINE_MARGIN = 0.12

        const val ATTRIBUTE_BADGE_LEFT_RATIO = 0.78
        const val ATTRIBUTE_BADGE_TOP_RATIO = 0.76
        const val ATTRIBUTE_BADGE_WIDTH_RATIO = 0.19
        const val ATTRIBUTE_BADGE_HEIGHT_RATIO = 0.21
        const val ATTRIBUTE_MIN_SATURATION = 0.30
        const val ATTRIBUTE_MIN_VALUE = 0.20
        const val ATTRIBUTE_MIN_SIGNAL = 0.10
        const val ATTRIBUTE_MIN_CONFIDENCE = 0.72
        const val ATTRIBUTE_MIN_MARGIN = 0.10
        const val TWO_PI = Math.PI * 2.0
        val ATTRIBUTE_HUES = mapOf(
            LabyrinthCharacterAttribute.FIRE to 0.021,
            LabyrinthCharacterAttribute.DARK to 0.729,
            LabyrinthCharacterAttribute.LIGHT to 0.104,
            LabyrinthCharacterAttribute.WIND to 0.271,
            LabyrinthCharacterAttribute.WATER to 0.604,
        )

        val JOINED_ICON_SLOTS = listOf(
            IconSlot("joined_first", EntryReferenceRect(530, 350, 90, 90), refineAmbiguousGeometry = true),
            IconSlot("joined_second", EntryReferenceRect(530, 490, 90, 90), refineAmbiguousGeometry = true),
            IconSlot("joined_third", EntryReferenceRect(530, 632, 90, 90), refineAmbiguousGeometry = true),
        )

        val ROLE_REWARD_LEGACY_NAME_SLOTS = listOf(
            LegacySlot("role_reward_left", EntryReferenceRect(260, 650, 270, 110)),
            LegacySlot("role_reward_center", EntryReferenceRect(810, 650, 300, 100)),
            LegacySlot("role_reward_right", EntryReferenceRect(1360, 650, 300, 100)),
        )
        val JOINED_LEGACY_NAME_SLOTS = listOf(
            LegacySlot("joined_first", EntryReferenceRect(630, 350, 350, 90)),
            LegacySlot("joined_second", EntryReferenceRect(630, 495, 430, 90)),
            LegacySlot("joined_third", EntryReferenceRect(630, 635, 370, 90)),
        )
    }
}
