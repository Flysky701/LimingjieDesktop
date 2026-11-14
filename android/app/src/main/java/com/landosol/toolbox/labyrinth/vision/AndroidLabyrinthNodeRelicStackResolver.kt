package com.landosol.toolbox.labyrinth.vision

import android.graphics.Bitmap
import com.landosol.toolbox.clanbattle.recognition.AndroidPixelImageAdapter
import com.landosol.toolbox.clanbattle.recognition.DigitMatchResult
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Reads the compact mark counters displayed to the right of the bottom-left "迷宫遗物" button
 * while the map is open.
 *
 * The coloured mark badge is classified without OCR. Only its tiny numeric counter is sent to
 * ML Kit, at most once per captured frame, and stable counters are cached. This gives us a second
 * in-game calibration source that remains visible on ordinary node-selection pages.
 */
class AndroidLabyrinthNodeRelicStackResolver(
    submitTextRead: (Bitmap, (String?) -> Unit) -> Unit = AndroidChineseRoleNameOcr::readAsync,
) {
    private val ocr = AndroidRelicRoiOcr(submitTextRead, maximumAttempts = 3)
    private val templateCache = mutableMapOf<String, Pair<Long, DigitMatchResult?>>()
    private var evidenceVersion = Long.MIN_VALUE
    private val slotMarks = mutableMapOf<String, String>()

    @Synchronized
    fun resolve(
        bitmap: Bitmap,
        result: LabyrinthEntryFrameResult,
        capturedAtMillis: Long = Long.MIN_VALUE,
        version: Long = 0,
        priorityMarks: Set<String> = emptySet(),
    ): LabyrinthEntryFrameResult {
        if (evidenceVersion != version) {
            ocr.clear(); templateCache.clear(); slotMarks.clear(); evidenceVersion = version
        }
        if (result.observation.state != LabyrinthEntryPageState.NODE_SELECTION) {
            ocr.clear()
            templateCache.clear()
            slotMarks.clear()
            return result
        }

        val entries = SLOT_RECTS.mapIndexedNotNull { index, referenceRect ->
            val iconRect = ReferenceFitMapper.map(
                frameWidth = bitmap.width,
                frameHeight = bitmap.height,
                referenceSize = STANDARD_REFERENCE,
                referenceRect = referenceRect,
            ) ?: return@mapIndexedNotNull null
            val panelSupport = panelSupport(bitmap, iconRect)
            if (panelSupport < MIN_PANEL_SUPPORT) return@mapIndexedNotNull null

            val mark = classifyIcon(bitmap, iconRect) ?: return@mapIndexedNotNull null
            if (priorityMarks.isNotEmpty() && mark.label !in priorityMarks) return@mapIndexedNotNull null
            val ocrDigitRect = ReferenceFitMapper.map(
                frameWidth = bitmap.width,
                frameHeight = bitmap.height,
                referenceSize = STANDARD_REFERENCE,
                referenceRect = OCR_DIGIT_RECTS[index],
            ) ?: return@mapIndexedNotNull null
            val slotId = "node_relic_mark_${index + 1}"
            if (slotMarks.put(slotId, mark.label) != mark.label) ocr.clear()
            val read = ocr.read(bitmap, slotId, ocrDigitRect, OCR_SCALE, capturedAtMillis)
            val text = read?.text
            val parsedOcr = text?.let(::parseStacks)
            val templateMatch = run {
                val templateDigitRect = ReferenceFitMapper.map(
                    frameWidth = bitmap.width,
                    frameHeight = bitmap.height,
                    referenceSize = STANDARD_REFERENCE,
                    referenceRect = TEMPLATE_DIGIT_RECTS[index],
                ) ?: return@mapIndexedNotNull null
                val fingerprint = relicRoiFingerprint(bitmap, templateDigitRect)
                val cached = templateCache[slotId]
                if (cached?.first == fingerprint) cached.second else {
                    matchTemplateDigit(bitmap, templateDigitRect).also {
                        templateCache[slotId] = fingerprint to it
                    }
                }
            }
            val templateStack = templateMatch?.let(::acceptTemplateStack)
            val resolvedStack = resolveStackValue(parsedOcr, templateStack)
            LabyrinthNodeRelicMarkStack(
                slotId = slotId,
                attribute = mark.label,
                stacks = resolvedStack,
                iconConfidence = mark.confidence,
                evidenceId = read?.id,
                capturedAtMillis = read?.capturedAtMillis ?: Long.MIN_VALUE,
                evidenceText = when {
                    parsedOcr != null && templateStack != null && resolvedStack == null ->
                        "数字冲突：OCR=$parsedOcr，模板=$templateStack"
                    templateStack != null && resolvedStack == templateStack -> buildString {
                        append("模板 ")
                        append(templateStack)
                        append(" · ")
                        append("%.2f/%.2f".format(templateMatch.score, templateMatch.margin))
                    }
                    parsedOcr != null -> text
                    else -> text?.takeIf(String::isNotBlank)
                },
            )
        }
        if (entries.isEmpty() && result.nodeRelicStackObservation == null) return result
        return result.copy(nodeRelicStackObservation = LabyrinthNodeRelicStackObservation(entries))
    }

    internal fun parseStacks(text: String): Int? {
        val compact = text.trim()
        if (!NUMBER_PATTERN.matches(compact)) return null
        return compact.toIntOrNull()?.takeIf { it in VALID_STACK_RANGE }
    }

    internal fun classifyHue(hue: Double): String? = nearestMark(hue)?.label

    internal fun acceptTemplateStack(match: DigitMatchResult): Int? = match.digit.takeIf {
        it in SINGLE_DIGIT_STACK_RANGE &&
            match.score >= MIN_TEMPLATE_SCORE &&
            match.margin >= MIN_TEMPLATE_MARGIN
    }

    internal fun resolveStackValue(ocr: Int?, template: Int?): Int? = when {
        ocr != null && template != null && ocr in SINGLE_DIGIT_STACK_RANGE && ocr != template -> null
        ocr == null -> template
        else -> ocr
    }

    private fun matchTemplateDigit(bitmap: Bitmap, rect: EntryPixelRect): DigitMatchResult? {
        val crop = Bitmap.createBitmap(bitmap, rect.left, rect.top, rect.width, rect.height)
        return try {
            LabyrinthCompactCounterRecognizer.match(AndroidPixelImageAdapter.from(crop))
        } finally {
            if (!crop.isRecycled) crop.recycle()
        }
    }

    private fun classifyIcon(bitmap: Bitmap, rect: EntryPixelRect): ClassifiedMark? {
        val hues = ArrayList<Double>()
        var samples = 0
        for (y in rect.top + rect.height / 10 until rect.top + rect.height * 68 / 100 step SAMPLE_STEP) {
            for (x in rect.left + rect.width / 10 until rect.left + rect.width * 75 / 100 step SAMPLE_STEP) {
                samples++
                val hsv = rgbToHsv(bitmap.getPixel(x, y))
                if (hsv.saturation >= MIN_ICON_SATURATION && hsv.value >= MIN_ICON_VALUE) {
                    hues += hsv.hue
                }
            }
        }
        if (samples <= 0 || hues.size.toDouble() / samples < MIN_ICON_COLORED_FRACTION) return null
        val votes = hues.mapNotNull { hue ->
            nearestMark(hue)?.takeIf { hueDistance(hue, it.hue) <= MAX_MARK_HUE_DISTANCE }
        }.groupingBy { it }.eachCount()
        val winner = votes.maxByOrNull { it.value } ?: return null
        val dominance = winner.value.toDouble() / hues.size
        if (dominance < 0.65) return null
        val confidence = dominance *
            (hues.size.toDouble() / samples / EXPECTED_ICON_COLORED_FRACTION).coerceIn(0.0, 1.0)
        return ClassifiedMark(winner.key.label, confidence)
    }

    /**
     * Fixed slots after the relic button may extend past the actual panel when only one or two
     * marks exist. The real panel has a light, low-saturation bottom strip; the map background
     * does not. Checking that strip prevents colourful map art from becoming a fake mark icon.
     */
    private fun panelSupport(bitmap: Bitmap, rect: EntryPixelRect): Double {
        val stripTop = (rect.top + rect.height * PANEL_SUPPORT_Y_RATIO).roundToInt()
            .coerceIn(rect.top, rect.top + rect.height - 1)
        var supported = 0
        var samples = 0
        for (y in stripTop until rect.top + rect.height step SAMPLE_STEP) {
            for (x in rect.left until rect.left + rect.width step SAMPLE_STEP) {
                samples++
                val hsv = rgbToHsv(bitmap.getPixel(x, y))
                if (hsv.value >= PANEL_MIN_VALUE && hsv.saturation <= PANEL_MAX_SATURATION) supported++
            }
        }
        return if (samples == 0) 0.0 else supported.toDouble() / samples
    }

    private fun rgbToHsv(color: Int): Hsv {
        val red = (color ushr 16 and 0xff) / 255.0
        val green = (color ushr 8 and 0xff) / 255.0
        val blue = (color and 0xff) / 255.0
        val maxValue = max(red, max(green, blue))
        val minValue = min(red, min(green, blue))
        val delta = maxValue - minValue
        val saturation = if (maxValue <= 0.0) 0.0 else delta / maxValue
        val hue = when {
            delta <= 1e-9 -> 0.0
            maxValue == red -> 60.0 * (((green - blue) / delta) % 6.0)
            maxValue == green -> 60.0 * (((blue - red) / delta) + 2.0)
            else -> 60.0 * (((red - green) / delta) + 4.0)
        }.let { if (it < 0.0) it + 360.0 else it }
        return Hsv(hue, saturation, maxValue)
    }

    private fun nearestMark(hue: Double): MarkHue? = MARK_HUES.minByOrNull { hueDistance(hue, it.hue) }

    private fun hueDistance(first: Double, second: Double): Double {
        val raw = abs(first - second) % 360.0
        return min(raw, 360.0 - raw)
    }

    private data class Hsv(val hue: Double, val saturation: Double, val value: Double)
    private data class MarkHue(val label: String, val hue: Double)
    private data class ClassifiedMark(val label: String, val confidence: Double)

    private companion object {
        val STANDARD_REFERENCE = EntryReferenceSize(1920, 1080)

        // Screenshot-derived layout for the compact bottom-left mark panel. Active mark cells are
        // spaced about 88 reference pixels apart. The circular info button occupies the next cell
        // but has too little saturated area to pass mark classification.
        val SLOT_RECTS = List(5) { index -> EntryReferenceRect(310 + index * 88, 930, 82, 110) }
        // OCR keeps a wider crop so counters >= 10 remain readable. The old crop began at y=948
        // and mostly contained the icon/chevrons; live 16:9 captures put the number near y=986.
        val OCR_DIGIT_RECTS = List(5) { index -> EntryReferenceRect(360 + index * 88, 970, 48, 48) }

        // The map font is not the battle font. This crop surrounds the outlined counter and enough
        // margin for its bright interior to be separated from edge-touching badge/panel highlights.
        // OCR remains responsible for counters >= 10; this structural fallback covers visible 1-3.
        val TEMPLATE_DIGIT_RECTS = List(5) { index -> EntryReferenceRect(380 + index * 88, 978, 32, 44) }

        // UI badge colours. The current live screenshot gives ~112° for 守备 and ~16° for 强化;
        // the other three use the game's standard cyan/gold/purple mark palette.
        val MARK_HUES = listOf(
            MarkHue("强化", 16.0),
            MarkHue("会心", 50.0),
            MarkHue("守备", 112.0),
            MarkHue("加速", 200.0),
            MarkHue("弱体", 285.0),
        )

        val NUMBER_PATTERN = Regex("[0-9]{1,2}")
        val VALID_STACK_RANGE = 0..30
        val SINGLE_DIGIT_STACK_RANGE = 1..3
        const val MIN_ICON_SATURATION = 0.45
        const val MIN_ICON_VALUE = 0.30
        const val MIN_ICON_COLORED_FRACTION = 0.18
        const val EXPECTED_ICON_COLORED_FRACTION = 0.45
        const val MAX_MARK_HUE_DISTANCE = 48.0
        const val PANEL_SUPPORT_Y_RATIO = 0.82
        const val PANEL_MIN_VALUE = 0.58
        const val PANEL_MAX_SATURATION = 0.38
        const val MIN_PANEL_SUPPORT = 0.32
        const val SAMPLE_STEP = 3
        const val OCR_SCALE = 4
        const val MIN_TEMPLATE_SCORE = 0.40
        const val MIN_TEMPLATE_MARGIN = 0.08
    }
}
