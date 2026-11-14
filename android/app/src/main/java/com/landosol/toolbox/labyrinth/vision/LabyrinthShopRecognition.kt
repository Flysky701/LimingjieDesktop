package com.landosol.toolbox.labyrinth.vision

import com.landosol.toolbox.clanbattle.recognition.PixelImage
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

enum class LabyrinthShopDialogState {
    NONE,
    PURCHASE_CONFIRMATION,
    PURCHASE_COMPLETE,
    EXIT_CONFIRMATION,
}

enum class LabyrinthShopItemStatus {
    AVAILABLE,
    DISABLED,
    UNKNOWN,
}

enum class LabyrinthShopItemKind {
    UNKNOWN,
    RELIC,
    ROLE_IMPRINT,
}

enum class LabyrinthShopCategoryState { READY, READING, EXHAUSTED }

data class LabyrinthShopItemMatch(
    val slotId: String,
    val screenRect: EntryPixelRect,
    val buyButtonRect: EntryPixelRect,
    val buyButtonScore: Double,
    val buyButtonEnabledEvidence: Double = 0.0,
    val status: LabyrinthShopItemStatus,
    val relicMatch: LabyrinthRelicMatch? = null,
    /** Semantic title classification. Android OCR fills ROLE_IMPRINT for "XX职能...印记" goods. */
    val kind: LabyrinthShopItemKind = LabyrinthShopItemKind.UNKNOWN,
    val titleText: String? = null,
    val roleImprintLabel: String? = null,
    /** True for 选择印记, which opens a character picker after purchase. */
    val choiceRoleImprint: Boolean = false,
    val titleEvidenceId: Long? = null,
    val categoryState: LabyrinthShopCategoryState = LabyrinthShopCategoryState.READY,
    val categoryAttempt: Int = 0,
    /**
     * A READY role imprint whose role name did not survive OCR, while the remaining title crop
     * variants still get a bounded grace window to recover it. The role name is what authorizes
     * spending coins, so the shop policy waits on this good instead of ending the visit.
     */
    val imprintLabelPending: Boolean = false,
) {
    val purchasable: Boolean get() = status == LabyrinthShopItemStatus.AVAILABLE
}

/** Pure helper so noisy shop-title OCR can be tested without Android/ML Kit. */
object LabyrinthShopItemText {
    private val roleLabels = listOf("坦克", "治疗", "强化", "增益", "减益", "干扰", "攻击", "破防")

    private fun normalized(text: String?): String = text.orEmpty()
        .replace(Regex("\\s+"), "")
        .replace('職', '职')
        .replace('隨', '随')
        .replace("選擇", "选择")
        .replace('記', '记')
        .replace('紀', '记')
        .replace('纪', '记')
        .let { value ->
            // A single substitution is allowed only inside a complete, known imprint title.
            // Do not globally replace OCR characters in ordinary relic names.
            roleLabels.fold(value) { result, role ->
                result.replace(Regex("${role}型(?:[职识職取][能]|职.)的?(随机|选择)印记")) { match ->
                    "${role}型职能的${match.groupValues[1]}印记"
                }
            }
        }

    /**
     * Coarse semantic gate: enough to prove this good is a role-imprint and therefore not a relic,
     * even when OCR did not recover the exact role label safely.
     */
    fun looksLikeRoleImprint(text: String?): Boolean {
        val value = normalized(text)
        if (value.contains("全体选择印记")) return true
        if (!value.contains("职能")) return false
        // Shop OCR occasionally drops/misreads the last "印记" glyph while preserving the much
        // more distinctive "职能...随机/选择" title. That evidence is sufficient to reject the
        // relic hypothesis; ordinary relic titles do not contain "职能".
        return value.contains("印记") || value.contains("随机") || value.contains("选择")
    }

    /**
     * Whether this imprint lets the player pick the character (选择印记) rather than rolling one
     * (随机印记).
     *
     * The two are priced and titled alike and both are role imprints, but only the choice variant
     * opens a full-roster 角色选择 page after purchase, which the run then has to answer.
     */
    fun isChoiceRoleImprint(text: String?): Boolean {
        val value = normalized(text)
        if (!looksLikeRoleImprint(value)) return false
        if (value.contains("随机")) return false
        return value.contains("选择")
    }

    fun roleImprintLabel(text: String?): String? {
        val normalized = normalized(text)
        if (!looksLikeRoleImprint(normalized)) return null
        val role = roleLabels.firstOrNull { label ->
            normalized.contains("${label}型职能") || normalized.contains("${label}职能")
        }
        if (role != null && (normalized.contains("选择") || normalized.contains("随机"))) {
            return "${role}型职能印记"
        }
        if (normalized.contains("全体选择印记")) return "全体选择印记"
        if (normalized.contains("随机职能") &&
            (normalized.contains("选择印记") || normalized.contains("随机印记"))
        ) return "随机职能印记"
        return null
    }
}

data class LabyrinthShopObservation(
    val items: List<LabyrinthShopItemMatch>,
    val dialogState: LabyrinthShopDialogState = LabyrinthShopDialogState.NONE,
    val refreshButtonEnabledEvidence: Double = 0.0,
    /** The relic shown in the purchase-confirmation dialog, if the icon is trusted. */
    val purchaseCandidate: LabyrinthRelicMatch? = null,
) {
    val availableItemCount: Int get() = items.count(LabyrinthShopItemMatch::purchasable)
    val recognizedButtonCount: Int get() = items.count { it.buyButtonScore >= BUTTON_MIN_CONFIDENCE }
    val refreshButtonEnabled: Boolean get() = refreshButtonEnabledEvidence >= 0.50

    private companion object {
        const val BUTTON_MIN_CONFIDENCE = 0.55
    }
}

/**
 * Reads the stable part of the shop layout, the three visible purchase controls, and relic icons.
 * The purchase confirmation dialog contains a larger copy of the purchased relic icon. That icon
 * is used as the commit candidate; the run store is updated only after the following purchase-
 * complete dialog, so opening the shop or cancelling a purchase cannot add a relic.
 */
class LabyrinthShopRecognizer(
    private val buyButtonTemplate: PixelImage? = null,
    private val relicTemplates: List<LabyrinthRelicTemplate> = emptyList(),
    private val matcher: GradientTemplateMatcher = GradientTemplateMatcher(maxSamples = 1_200),
    private val minButtonConfidence: Double = 0.55,
    private val minRelicConfidence: Double = 0.50,
    private val minRelicMargin: Double = 0.05,
) {
    private val relicMatcher = AlignedRgbRelicMatcher()

    init {
        require(minButtonConfidence in 0.0..1.0)
        require(minRelicConfidence in 0.0..1.0)
        require(minRelicMargin in 0.0..1.0)
        require(relicTemplates.all { it.relicId.isNotBlank() && it.attribute.isNotBlank() })
    }

    fun recognize(
        frame: PixelImage,
        dialogState: LabyrinthShopDialogState = LabyrinthShopDialogState.NONE,
    ): LabyrinthShopObservation {
        val items = TOP_ROW_SLOTS.map { slot ->
            val screenRect = map(frame, slot.cardRect)
            val buyButtonRect = map(frame, slot.buyButtonRect)
            val iconRect = map(frame, slot.iconRect)
            val score = buyButtonTemplate?.let {
                matcher.score(frame, buyButtonRect, it, minimumChannelScore = 0.0)
            } ?: 0.0
            val enabledEvidence = buyButtonEnabledEvidence(frame, buyButtonRect)
            val relicMatch = matchRelic(frame, slot.id, screenRect, iconRect)
            LabyrinthShopItemMatch(
                slotId = slot.id,
                screenRect = screenRect,
                buyButtonRect = buyButtonRect,
                buyButtonScore = score,
                buyButtonEnabledEvidence = enabledEvidence,
                status = when {
                    score < minButtonConfidence -> LabyrinthShopItemStatus.UNKNOWN
                    enabledEvidence >= MIN_ENABLED_BUTTON_EVIDENCE -> LabyrinthShopItemStatus.AVAILABLE
                    else -> LabyrinthShopItemStatus.DISABLED
                },
                relicMatch = relicMatch,
                kind = if (relicMatch?.recognized == true) {
                    LabyrinthShopItemKind.RELIC
                } else {
                    LabyrinthShopItemKind.UNKNOWN
                },
            )
        }
        val purchaseCandidate = if (dialogState == LabyrinthShopDialogState.PURCHASE_CONFIRMATION) {
            val trustedCandidates = PURCHASE_DIALOG_ICON_RECTS.mapNotNull { referenceRect ->
                val dialogIconRect = map(frame, referenceRect)
                matchRelic(frame, "shop_purchase_confirmation", dialogIconRect, dialogIconRect)
                    ?.takeIf(LabyrinthRelicMatch::recognized)
            }
            // Layout variants should identify the same relic. If two trusted ROIs disagree,
            // keep the confirmation blocked instead of choosing the numerically larger score.
            val trustedIds = trustedCandidates.mapNotNull(LabyrinthRelicMatch::relicId).distinct()
            if (trustedIds.size == 1) trustedCandidates.maxByOrNull(LabyrinthRelicMatch::confidence) else null
        } else {
            null
        }
        return LabyrinthShopObservation(
            items = items,
            dialogState = dialogState,
            refreshButtonEnabledEvidence = buyButtonEnabledEvidence(frame, map(frame, REFRESH_BUTTON_RECT)),
            purchaseCandidate = purchaseCandidate,
        )
    }

    private fun map(frame: PixelImage, rect: EntryReferenceRect): EntryPixelRect =
        ReferenceFitMapper.map(
            frameWidth = frame.width,
            frameHeight = frame.height,
            referenceSize = STANDARD_REFERENCE,
            referenceRect = rect,
        ) ?: rect.toPixelRect()

    /**
     * The game keeps the same button frame/text when a shop purchase is unaffordable, but dims
     * the blue fill substantially. Gradient/template correlation therefore remains high and is
     * not sufficient to decide whether the control is actionable.
     *
     * Measure only the inner blue body so the bright gold/white border does not make a disabled
     * button look enabled. Live 1920x1080 fixtures give about 156 average luma for an enabled Buy
     * button, while the insufficient-currency state is about 95; the deliberately wide transition
     * band below leaves room for capture/compression variance.
     */
    internal fun buyButtonEnabledEvidence(frame: PixelImage, rect: EntryPixelRect): Double {
        val left = (rect.left + rect.width * 0.15).roundToInt().coerceIn(0, frame.width)
        val right = (rect.left + rect.width * 0.85).roundToInt().coerceIn(0, frame.width)
        val top = (rect.top + rect.height * 0.20).roundToInt().coerceIn(0, frame.height)
        val bottom = (rect.top + rect.height * 0.80).roundToInt().coerceIn(0, frame.height)
        if (right <= left || bottom <= top) return 0.0

        var luminanceSum = 0.0
        var samples = 0
        var y = top
        while (y < bottom) {
            var x = left
            while (x < right) {
                val color = frame[x, y]
                val red = color ushr 16 and 0xff
                val green = color ushr 8 and 0xff
                val blue = color and 0xff
                luminanceSum += red * 0.2126 + green * 0.7152 + blue * 0.0722
                samples++
                x += 3
            }
            y += 3
        }
        if (samples == 0) return 0.0
        val meanLuminance = luminanceSum / samples
        return ((meanLuminance - DISABLED_BUTTON_LUMA) /
            (ENABLED_BUTTON_LUMA - DISABLED_BUTTON_LUMA)).coerceIn(0.0, 1.0)
    }

    private fun matchRelic(
        frame: PixelImage,
        slotId: String,
        screenRect: EntryPixelRect,
        iconRect: EntryPixelRect,
    ): LabyrinthRelicMatch? {
        if (relicTemplates.isEmpty()) return null
        val ranked = relicMatcher.rank(frame, iconRect, relicTemplates)
        val best = ranked.firstOrNull()
        val secondScore = ranked.getOrNull(1)?.second ?: 0.0
        val score = best?.second ?: 0.0
        val trusted = best != null &&
            score >= minRelicConfidence &&
            score - secondScore >= minRelicMargin
        val template = best?.first?.takeIf { trusted }
        return LabyrinthRelicMatch(
            slotId = slotId,
            relicId = template?.relicId,
            displayName = template?.displayName,
            attribute = template?.attribute,
            attributeBonus = template?.attributeBonus,
            effect = template?.effect,
            rarity = template?.rarity ?: 0,
            confidence = score,
            screenRect = screenRect,
            iconRect = iconRect,
            suspectedRelicId = best?.first?.relicId,
            suspectedDisplayName = best?.first?.displayName,
            suspectedAttribute = best?.first?.attribute,
            rivalConfidence = secondScore,
            rivalMargin = score - secondScore,
        )
    }

    private data class Slot(
        val id: String,
        val cardRect: EntryReferenceRect,
        val buyButtonRect: EntryReferenceRect,
        val iconRect: EntryReferenceRect,
    )

    private companion object {
        val STANDARD_REFERENCE = EntryReferenceSize(1920, 1080)
        val TOP_ROW_SLOTS = listOf(
            Slot(
                id = "shop_item_1",
                cardRect = EntryReferenceRect(84, 220, 558, 357),
                buyButtonRect = EntryReferenceRect(350, 480, 290, 90),
                iconRect = EntryReferenceRect(100, 315, 155, 155),
            ),
            Slot(
                id = "shop_item_2",
                cardRect = EntryReferenceRect(662, 220, 556, 357),
                buyButtonRect = EntryReferenceRect(928, 480, 290, 90),
                iconRect = EntryReferenceRect(679, 315, 155, 155),
            ),
            Slot(
                id = "shop_item_3",
                cardRect = EntryReferenceRect(1237, 220, 559, 357),
                buyButtonRect = EntryReferenceRect(1506, 480, 290, 90),
                iconRect = EntryReferenceRect(1254, 315, 155, 155),
            ),
        )
        val PURCHASE_DIALOG_ICON_RECTS = listOf(
            // Original 480x270 video fixture mapped to the standard 1920x1080 canvas.
            EntryReferenceRect(525, 410, 205, 205),
            // Current client: live scan locates the 195px icon at (534, 419).
            EntryReferenceRect(534, 419, 195, 195),
        )
        val REFRESH_BUTTON_RECT = EntryReferenceRect(500, 930, 350, 85)

        const val DISABLED_BUTTON_LUMA = 110.0
        const val ENABLED_BUTTON_LUMA = 145.0
        const val MIN_ENABLED_BUTTON_EVIDENCE = 0.50

        fun EntryReferenceRect.toPixelRect() = EntryPixelRect(x, y, width, height)
    }
}

/**
 * Relic icons are full-colour catalog assets. The generic gradient matcher intentionally drops
 * chroma and works well for page chrome, but on the shop it made visually unrelated relics rank
 * almost identically. This matcher keeps RGB spatial structure and tolerates the few-pixel
 * rendering offset seen between the bundled 128px icon and the in-game shop card.
 *
 * To keep frame latency bounded, every template is scored only once at the nominal position.
 * Only the best coarse candidates receive the small alignment search.
 */
internal class AlignedRgbRelicMatcher(
    private val maxSamples: Int = 600,
    private val refinementCandidates: Int = 8,
) {
    init {
        require(maxSamples > 0)
        require(refinementCandidates > 0)
    }

    fun rank(
        frame: PixelImage,
        rect: EntryPixelRect,
        templates: List<LabyrinthRelicTemplate>,
    ): List<Pair<LabyrinthRelicTemplate, Double>> {
        if (templates.isEmpty() || rect.width < 3 || rect.height < 3) return emptyList()
        val coarse = templates.map { template ->
            template to score(frame, rect, template.image, offsetX = 0, offsetY = 0)
        }.sortedByDescending { it.second }
        val refineIds = coarse.take(refinementCandidates).mapTo(hashSetOf()) { it.first.relicId }
        val radiusX = max(2, (rect.width * ALIGN_RADIUS_RATIO).roundToInt())
        val radiusY = max(2, (rect.height * ALIGN_RADIUS_RATIO).roundToInt())
        val stepX = max(1, (rect.width * ALIGN_STEP_RATIO).roundToInt())
        val stepY = max(1, (rect.height * ALIGN_STEP_RATIO).roundToInt())

        return coarse.map { (template, coarseScore) ->
            if (template.relicId !in refineIds) {
                template to coarseScore
            } else {
                var best = coarseScore
                var dx = -radiusX
                while (dx <= radiusX) {
                    var dy = -radiusY
                    while (dy <= radiusY) {
                        if (dx != 0 || dy != 0) {
                            best = max(best, score(frame, rect, template.image, dx, dy))
                        }
                        dy += stepY
                    }
                    dx += stepX
                }
                template to best
            }
        }.sortedByDescending { it.second }
    }

    private fun score(
        frame: PixelImage,
        rect: EntryPixelRect,
        template: PixelImage,
        offsetX: Int,
        offsetY: Int,
    ): Double {
        if (template.width < 3 || template.height < 3) return 0.0
        val step = max(
            1,
            ceil(sqrt(template.width.toDouble() * template.height / maxSamples)).toInt(),
        )
        var squaredError = 0.0
        var samples = 0
        var templateY = 1
        while (templateY < template.height - 1) {
            var templateX = 1
            while (templateX < template.width - 1) {
                val templatePixel = template[templateX, templateY]
                if ((templatePixel ushr 24 and 0xff) >= MIN_TEMPLATE_ALPHA) {
                    val frameX = rect.left + offsetX +
                        mapCoordinate(templateX, template.width, rect.width)
                    val frameY = rect.top + offsetY +
                        mapCoordinate(templateY, template.height, rect.height)
                    if (frameX in 0 until frame.width && frameY in 0 until frame.height) {
                        val framePixel = frame[frameX, frameY]
                        val red = (framePixel ushr 16 and 0xff) - (templatePixel ushr 16 and 0xff)
                        val green = (framePixel ushr 8 and 0xff) - (templatePixel ushr 8 and 0xff)
                        val blue = (framePixel and 0xff) - (templatePixel and 0xff)
                        squaredError += (red * red + green * green + blue * blue).toDouble()
                        samples++
                    }
                }
                templateX += step
            }
            templateY += step
        }
        if (samples == 0) return 0.0
        val rmse = sqrt(squaredError / (samples * 3.0))
        return (1.0 - rmse / 255.0).coerceIn(0.0, 1.0)
    }

    private fun mapCoordinate(value: Int, sourceSize: Int, targetSize: Int): Int =
        (value.toLong() * (targetSize - 1) / (sourceSize - 1)).toInt()

    private companion object {
        const val MIN_TEMPLATE_ALPHA = 128
        const val ALIGN_RADIUS_RATIO = 0.04
        const val ALIGN_STEP_RATIO = 0.013
    }
}

fun LabyrinthEntryPageState.toShopDialogState(): LabyrinthShopDialogState = when (this) {
    LabyrinthEntryPageState.SHOP -> LabyrinthShopDialogState.NONE
    LabyrinthEntryPageState.SHOP_PURCHASE_CONFIRMATION -> LabyrinthShopDialogState.PURCHASE_CONFIRMATION
    LabyrinthEntryPageState.SHOP_PURCHASE_COMPLETE -> LabyrinthShopDialogState.PURCHASE_COMPLETE
    LabyrinthEntryPageState.SHOP_EXIT_CONFIRMATION -> LabyrinthShopDialogState.EXIT_CONFIRMATION
    else -> LabyrinthShopDialogState.NONE
}
