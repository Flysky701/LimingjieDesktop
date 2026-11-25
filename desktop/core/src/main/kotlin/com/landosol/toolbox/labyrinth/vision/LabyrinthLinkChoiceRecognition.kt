package com.landosol.toolbox.labyrinth.vision

import com.landosol.toolbox.vision.PixelImage
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** The five element types displayed on a link-mark choice card. */
enum class LabyrinthLinkElement(val label: String) {
    FIRE("火"),
    DARK("暗"),
    LIGHT("光"),
    WIND("风"),
    WATER("水"),
    UNKNOWN("未知"),
}

/**
 * The order is deliberately kept outside the recognizer. The same observation can therefore be
 * reused later by an in-game choice policy without changing the image recognition code.
 */
data class LabyrinthLinkChoicePriority(
    val orderedElements: List<LabyrinthLinkElement> = DEFAULT_ORDER,
) {
    init {
        require(orderedElements.size == LabyrinthLinkElement.entries.size - 1)
        require(orderedElements.toSet() == LabyrinthLinkElement.entries.filterNot { it == LabyrinthLinkElement.UNKNOWN }.toSet())
    }

    fun rankOf(element: LabyrinthLinkElement): Int =
        orderedElements.indexOf(element).takeIf { it >= 0 }?.plus(1) ?: Int.MAX_VALUE

    /**
     * Chooses only by the fixed connect-node order. Character quality, remaining pools, relics,
     * and team gaps are intentionally not accepted as inputs to this function.
     */
    fun chooseConnectAttribute(attributes: Iterable<LabyrinthLinkElement>): LabyrinthLinkElement? {
        val displayed = attributes.filterNot { it == LabyrinthLinkElement.UNKNOWN }.toSet()
        return orderedElements.firstOrNull(displayed::contains)
    }

    val label: String
        get() = orderedElements.joinToString(" > ", transform = LabyrinthLinkElement::label)

    companion object {
        /** Default requested policy: fire, dark, light, wind, water. */
        val DEFAULT_ORDER = listOf(
            LabyrinthLinkElement.FIRE,
            LabyrinthLinkElement.DARK,
            LabyrinthLinkElement.LIGHT,
            LabyrinthLinkElement.WIND,
            LabyrinthLinkElement.WATER,
        )
        val DEFAULT = LabyrinthLinkChoicePriority()
    }
}

data class LabyrinthLinkChoiceElementTemplate(
    val element: LabyrinthLinkElement,
    val image: PixelImage,
)

data class LabyrinthLinkChoiceMatch(
    val slotId: String,
    val element: LabyrinthLinkElement,
    val confidence: Double,
    val screenRect: EntryPixelRect,
    /** The lower button that commits this card, not the property badge inside the card. */
    val selectionButtonRect: EntryPixelRect,
    val badgeRect: EntryPixelRect,
    val priorityRank: Int,
) {
    val recognized: Boolean get() = element != LabyrinthLinkElement.UNKNOWN
}

data class LabyrinthLinkChoiceObservation(
    val choices: List<LabyrinthLinkChoiceMatch>,
    val priority: LabyrinthLinkChoicePriority = LabyrinthLinkChoicePriority.DEFAULT,
) {
    val recognizedCount: Int get() = choices.count(LabyrinthLinkChoiceMatch::recognized)

    val preferredChoice: LabyrinthLinkChoiceMatch?
        get() {
            val preferredElement = priority.chooseConnectAttribute(choices.map { it.element }) ?: return null
            return choices
                .filter { it.element == preferredElement }
                .maxByOrNull(LabyrinthLinkChoiceMatch::confidence)
        }
}

/**
 * Recognizes the property badge at the lower-right of each link-mark card.
 *
 * The badge is used instead of OCR: the card title can be resized and the background is animated,
 * while the five badges have stable hue/shape signatures. Templates calibrate the hue signature;
 * the small built-in fallback keeps unit and diagnostic processors useful when no asset pack is
 * loaded yet.
 */
class LabyrinthLinkChoiceRecognizer(
    private val templates: List<LabyrinthLinkChoiceElementTemplate> = emptyList(),
    private val priority: LabyrinthLinkChoicePriority = LabyrinthLinkChoicePriority.DEFAULT,
    private val minConfidence: Double = 0.55,
    private val minMargin: Double = 0.15,
) {
    init {
        require(minConfidence in 0.0..1.0)
        require(minMargin in 0.0..1.0)
        require(templates.map(LabyrinthLinkChoiceElementTemplate::element).distinct().none { it == LabyrinthLinkElement.UNKNOWN })
    }

    fun recognize(frame: PixelImage): LabyrinthLinkChoiceObservation {
        val choices = SLOT_LAYOUTS.map { slot ->
            val cardRect = ReferenceFitMapper.map(
                frameWidth = frame.width,
                frameHeight = frame.height,
                referenceSize = STANDARD_REFERENCE,
                referenceRect = slot.cardRect,
            ) ?: return@map unknownChoice(slot)
            val selectionButtonRect = ReferenceFitMapper.map(
                frameWidth = frame.width,
                frameHeight = frame.height,
                referenceSize = STANDARD_REFERENCE,
                referenceRect = slot.selectionButtonRect,
            ) ?: return@map unknownChoice(slot, cardRect)
            val badgeRect = ReferenceFitMapper.map(
                frameWidth = frame.width,
                frameHeight = frame.height,
                referenceSize = STANDARD_REFERENCE,
                referenceRect = slot.badgeRect,
            ) ?: return@map unknownChoice(slot, cardRect, selectionButtonRect)
            val badge = frame.crop(badgeRect.left, badgeRect.top, badgeRect.width, badgeRect.height)
            val targetSignature = dominantHue(badge)
            val ranked = signatures()
                .map { signature ->
                    signature.element to hueScore(targetSignature.hue, signature.hue)
                }
                .sortedByDescending { (_, score) -> score }
            val best = ranked.firstOrNull()
            val second = ranked.getOrNull(1)?.second ?: 0.0
            val bestScore = best?.second ?: 0.0
            val trusted = best != null &&
                targetSignature.signal >= MIN_SIGNAL &&
                bestScore >= minConfidence &&
                bestScore - second >= minMargin
            val element = best?.first?.takeIf { trusted } ?: LabyrinthLinkElement.UNKNOWN
            LabyrinthLinkChoiceMatch(
                slotId = slot.id,
                element = element,
                confidence = bestScore,
                screenRect = cardRect,
                selectionButtonRect = selectionButtonRect,
                badgeRect = badgeRect,
                priorityRank = priority.rankOf(element),
            )
        }
        return LabyrinthLinkChoiceObservation(choices = choices, priority = priority)
    }

    private fun unknownChoice(
        slot: Slot,
        cardRect: EntryPixelRect? = null,
        selectionButtonRect: EntryPixelRect? = null,
    ) = LabyrinthLinkChoiceMatch(
        slotId = slot.id,
        element = LabyrinthLinkElement.UNKNOWN,
        confidence = 0.0,
        screenRect = cardRect ?: slot.cardRect.toPixelRect(),
        selectionButtonRect = selectionButtonRect ?: slot.selectionButtonRect.toPixelRect(),
        badgeRect = slot.badgeRect.toPixelRect(),
        priorityRank = Int.MAX_VALUE,
    )

    private fun signatures(): List<ElementSignature> = LabyrinthLinkElement.entries
        .filterNot { it == LabyrinthLinkElement.UNKNOWN }
        .map { element ->
            val template = templates.firstOrNull { it.element == element }
            ElementSignature(
                element = element,
                hue = template?.let { dominantHue(it.image).hue } ?: FALLBACK_HUES[element]!!,
            )
        }

    private fun hueScore(first: Double, second: Double): Double {
        val distance = minOf(absHueDistance(first, second), 0.5)
        return (1.0 - distance / HUE_TOLERANCE).coerceIn(0.0, 1.0)
    }

    private fun dominantHue(image: PixelImage): HueSignature {
        val histogram = DoubleArray(HUE_BINS)
        var signal = 0.0
        var sampled = 0
        val centerX = (image.width - 1) / 2.0
        val centerY = (image.height - 1) / 2.0
        val radius = min(image.width, image.height) * BADGE_RADIUS_RATIO
        for (y in 0 until image.height) {
            for (x in 0 until image.width) {
                val dx = x - centerX
                val dy = y - centerY
                if (dx * dx + dy * dy > radius * radius) continue
                val hsv = hsv(image[x, y])
                sampled++
                if (hsv.saturation < MIN_SATURATION || hsv.value < MIN_VALUE) continue
                val weight = hsv.saturation * hsv.value
                histogram[(hsv.hue * HUE_BINS).toInt().coerceIn(0, HUE_BINS - 1)] += weight
                signal += weight
            }
        }
        val peak = histogram.maxOrNull() ?: 0.0
        if (peak == 0.0 || signal == 0.0 || sampled == 0) return HueSignature(0.0, 0.0)
        val peakBin = histogram.indices.maxByOrNull(histogram::get) ?: 0
        var sine = 0.0
        var cosine = 0.0
        for (y in 0 until image.height) {
            for (x in 0 until image.width) {
                val dx = x - centerX
                val dy = y - centerY
                if (dx * dx + dy * dy > radius * radius) continue
                val hsv = hsv(image[x, y])
                val bin = (hsv.hue * HUE_BINS).toInt().coerceIn(0, HUE_BINS - 1)
                if (hsv.saturation < MIN_SATURATION || hsv.value < MIN_VALUE ||
                    bin !in (peakBin - 1)..(peakBin + 1)
                ) continue
                val weight = hsv.saturation * hsv.value
                val angle = hsv.hue * TWO_PI
                sine += sin(angle) * weight
                cosine += cos(angle) * weight
            }
        }
        val angle = atan2(sine, cosine)
        val hue = ((angle / TWO_PI) + 1.0) % 1.0
        return HueSignature(hue = hue, signal = (peak / signal).coerceIn(0.0, 1.0))
    }

    private fun hsv(color: Int): Hsv {
        val red = ((color ushr 16) and 0xff) / 255.0
        val green = ((color ushr 8) and 0xff) / 255.0
        val blue = (color and 0xff) / 255.0
        val maximum = max(red, max(green, blue))
        val minimum = min(red, min(green, blue))
        val delta = maximum - minimum
        val hue = when {
            delta == 0.0 -> 0.0
            maximum == red -> ((green - blue) / delta / 6.0) % 1.0
            maximum == green -> ((blue - red) / delta + 2.0) / 6.0
            else -> ((red - green) / delta + 4.0) / 6.0
        }.let { if (it < 0.0) it + 1.0 else it }
        return Hsv(
            hue = hue,
            saturation = if (maximum == 0.0) 0.0 else delta / maximum,
            value = maximum,
        )
    }

    private fun absHueDistance(first: Double, second: Double): Double {
        val distance = kotlin.math.abs(first - second)
        return min(distance, 1.0 - distance)
    }

    private data class ElementSignature(val element: LabyrinthLinkElement, val hue: Double)
    private data class HueSignature(val hue: Double, val signal: Double)
    private data class Hsv(val hue: Double, val saturation: Double, val value: Double)
    private data class Slot(
        val id: String,
        val cardRect: EntryReferenceRect,
        val selectionButtonRect: EntryReferenceRect,
        val badgeRect: EntryReferenceRect,
    )

    private fun EntryReferenceRect.toPixelRect() = EntryPixelRect(x, y, width, height)

    private companion object {
        const val HUE_BINS = 24
        const val HUE_TOLERANCE = 0.16
        const val MIN_SIGNAL = 0.18
        const val MIN_SATURATION = 0.32
        const val MIN_VALUE = 0.20
        const val BADGE_RADIUS_RATIO = 0.31
        const val TWO_PI = Math.PI * 2.0
        val STANDARD_REFERENCE = EntryReferenceSize(1920, 1080)
        val FALLBACK_HUES = mapOf(
            LabyrinthLinkElement.FIRE to 0.021,
            LabyrinthLinkElement.DARK to 0.729,
            LabyrinthLinkElement.LIGHT to 0.104,
            LabyrinthLinkElement.WIND to 0.271,
            LabyrinthLinkElement.WATER to 0.604,
        )
        val SLOT_LAYOUTS = listOf(
            Slot(
                id = "link_choice_1",
                cardRect = EntryReferenceRect(158, 297, 495, 695),
                selectionButtonRect = EntryReferenceRect(264, 812, 282, 142),
                badgeRect = EntryReferenceRect(440, 518, 90, 90),
            ),
            Slot(
                id = "link_choice_2",
                cardRect = EntryReferenceRect(713, 297, 495, 695),
                selectionButtonRect = EntryReferenceRect(819, 812, 282, 142),
                badgeRect = EntryReferenceRect(990, 518, 90, 90),
            ),
            Slot(
                id = "link_choice_3",
                cardRect = EntryReferenceRect(1268, 297, 495, 695),
                selectionButtonRect = EntryReferenceRect(1374, 812, 282, 142),
                badgeRect = EntryReferenceRect(1545, 518, 90, 90),
            ),
        )
    }
}
