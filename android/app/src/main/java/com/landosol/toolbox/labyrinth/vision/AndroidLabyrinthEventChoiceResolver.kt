package com.landosol.toolbox.labyrinth.vision

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.landosol.toolbox.gamedata.EventResource
import com.landosol.toolbox.gamedata.LabyrinthEventOcrDocument
import kotlin.math.roundToInt
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

data class AndroidLabyrinthEventRecognitionData(
    val events: List<EventResource>,
    val ocr: LabyrinthEventOcrDocument,
)

class AndroidLabyrinthEventRecognitionDataLoader(
    private val context: Context,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    fun load(): AndroidLabyrinthEventRecognitionData {
        val eventDocument = context.assets.open(EVENTS_PATH).bufferedReader().use { reader ->
            json.decodeFromString<EventCategoryDocument>(reader.readText())
        }
        val ocrDocument = context.assets.open(OCR_PATH).bufferedReader().use { reader ->
            json.decodeFromString<LabyrinthEventOcrDocument>(reader.readText())
        }
        require(eventDocument.schemaVersion == 1 && eventDocument.events.isNotEmpty()) {
            "事件目录为空或版本不受支持"
        }
        return AndroidLabyrinthEventRecognitionData(eventDocument.events, ocrDocument)
    }

    @Serializable
    private data class EventCategoryDocument(
        val schemaVersion: Int = 1,
        val events: List<EventResource> = emptyList(),
    )

    private companion object {
        const val ROOT = "resource-packs/cn-bilibili"
        const val EVENTS_PATH = "$ROOT/events.placeholder.json"
        const val OCR_PATH = "$ROOT/labyrinth-event-ocr.json"
    }
}

/**
 * Asynchronous Chinese-OCR fallback for event pages. It can promote an otherwise UNKNOWN frame
 * to EVENT_CHOICE, but [LabyrinthEventChoiceObservation.trusted] becomes true only after the same
 * catalog event is observed repeatedly. Button pixel evidence remains a separate click gate.
 */
class AndroidLabyrinthEventChoiceResolver(
    data: AndroidLabyrinthEventRecognitionData,
    private val submitTextRead: (Bitmap, (String?) -> Unit) -> Unit = AndroidChineseRoleNameOcr::readAsync,
    eventChoiceOverrides: Map<String, String> = emptyMap(),
    private val eventCustomizationEnabled: Boolean = false,
) {
    private var fullOcrFallback = false
    private val recognizer = LabyrinthEventTextRecognizer(data.events)
    private val customTitleEvents = if (eventCustomizationEnabled) {
        recognizer.customTitleEvents(eventChoiceOverrides)
    } else emptyList()
    private val layoutMapper = LabyrinthEventLayoutMapper(data.ocr.layouts)
    private val ocrCache = LabyrinthEventOcrCache()
    private var lastEventId: String? = null
    private var stableFrames = 0
    private var lastOcrRequestAtMillis = Long.MIN_VALUE

    @Synchronized
    fun resolve(bitmap: Bitmap, result: LabyrinthEntryFrameResult): LabyrinthEntryFrameResult {
        if (result.nodeMoveConfirmation != null || result.observation.state !in ELIGIBLE_STATES) {
            clearPageState()
            return result.copy(eventOcrDiagnostics = LabyrinthEventOcrDiagnostics("INELIGIBLE_PAGE_OR_MOVE_DIALOG"))
        }
        // UNKNOWN occurs during many unrelated animations.  Infer the visible option count from
        // the calibrated blue buttons instead of merely asking whether *any* layout overlaps blue
        // pixels.  This also lets OCR use the matching layout's tighter text bounds.
        val confidencesByOptionCount = (1..3).associateWith { optionCount ->
            val rects = layoutMapper.buttonRects(optionCount, bitmap.width, bitmap.height)
            if (rects.size != optionCount) emptyList()
            else rects.map { rect -> blueButtonConfidence(bitmap, rect) }
        }
        val hasAnyEventButtonEvidence = confidencesByOptionCount.values
            .flatten()
            .any { it >= MINIMUM_EVENT_BUTTON_CANDIDATE }
        if (!hasAnyEventButtonEvidence) {
            clearPageState()
            return result.copy(eventOcrDiagnostics = LabyrinthEventOcrDiagnostics(
                "NO_BUTTON_EVIDENCE", buttonConfidencesByOptionCount = confidencesByOptionCount,
            ))
        }
        val completeLayoutCount = labyrinthCompleteEventLayoutCount(
            confidencesByOptionCount = confidencesByOptionCount,
            minimumButtonConfidence = MINIMUM_EVENT_BUTTON_CANDIDATE,
        )
        // Probe only complete two-choice pages. Other layouts retain full catalog OCR.
        val titleOnly = customTitleEvents.isNotEmpty() && !fullOcrFallback && completeLayoutCount == 2
        val cropRect = (if (titleOnly) {
            layoutMapper.titleBounds(2, bitmap.width, bitmap.height)
        } else {
            completeLayoutCount?.takeIf { it > 1 }
                ?.let { optionCount -> layoutMapper.ocrBounds(optionCount, bitmap.width, bitmap.height) }
        }) ?: ReferenceFitMapper.map(
            frameWidth = bitmap.width,
            frameHeight = bitmap.height,
            referenceSize = STANDARD_REFERENCE,
            referenceRect = OCR_REFERENCE_RECT,
        ) ?: return result.copy(eventOcrDiagnostics = LabyrinthEventOcrDiagnostics("INVALID_CROP"))
        val customTitleScope = customTitleEvents.isNotEmpty() && completeLayoutCount == 2
        val fingerprint = if (customTitleScope) {
            val titleRect = layoutMapper.titleBounds(2, bitmap.width, bitmap.height) ?: cropRect
            titleFingerprint(bitmap, titleRect) xor
                (if (titleOnly) 0L else differenceHash(bitmap, cropRect))
        } else differenceHash(bitmap, cropRect)
        val lookup = ocrCache.lookup(
            LabyrinthEventOcrCache.Key(fingerprint, cropRect),
            android.os.SystemClock.elapsedRealtime(),
        )
        val diagnostic = LabyrinthEventOcrDiagnostics(
            status = "CACHE_LOOKUP",
            cropRect = cropRect,
            fingerprint = java.lang.Long.toHexString(fingerprint),
            completeLayoutCount = completeLayoutCount,
            buttonConfidencesByOptionCount = confidencesByOptionCount,
            cache = lookup.diagnostics,
        )
        val observedText = lookup.text.takeIf {
            !customTitleScope || lookup.diagnostics.fingerprintDistance == 0
        }
        if (observedText == null) {
            lastEventId = null
            stableFrames = 0
            // Failed/empty/expired title probes have a bounded exit to full OCR.
            val completedWithoutText = lookup.diagnostics.pendingRequestId == null &&
                lookup.diagnostics.lastCompletedRequestId != null &&
                lookup.diagnostics.lastResultStatus in setOf("EMPTY_TEXT", "FAILED_OR_UNAVAILABLE")
            val titleTimedOut = (lookup.diagnostics.pendingAgeMillis ?: 0L) >= 5_000L
            if (titleOnly && (completedWithoutText || titleTimedOut)) {
                fullOcrFallback = true
                ocrCache.clear()
                return result.copy(eventOcrDiagnostics = diagnostic.copy(status = "TITLE_FALLBACK"))
            }
            if (eventCustomizationEnabled && !titleOnly && completedWithoutText) {
                // Preserve the original dual-anchor escape for genuine single-choice events.
                // Multi-choice layouts remain blocked by their count in runtime policy.
                retryOcr(bitmap, cropRect, fingerprint)
                return result.copy(eventOcrDiagnostics = diagnostic.copy(status = "OCR_UNAVAILABLE"))
            }
            val scheduled = schedule(bitmap, cropRect, fingerprint)
            return result.copy(eventOcrDiagnostics = diagnostic.copy(
                status = if (titleOnly) "TITLE_WAITING_OCR" else "WAITING_OCR",
                requestScheduled = scheduled,
            ))
        }

        // Full OCR can recover a title without needing regional arena reward digits.
        val titleMatch = if (customTitleEvents.isNotEmpty() && completeLayoutCount == 2) {
            recognizer.recognizeCustomTitle(observedText, customTitleEvents)
        } else null
        val familyMatched = titleMatch?.event != null
        val match = if (titleOnly || familyMatched) requireNotNull(titleMatch)
            else recognizer.recognize(observedText)
        val event = match.event ?: run {
            lastEventId = null
            stableFrames = 0
            if (titleOnly) {
                fullOcrFallback = true
                ocrCache.clear()
                return result.copy(eventOcrDiagnostics = diagnostic.copy(
                    status = "TITLE_FALLBACK", rawText = observedText.take(MAX_DIAGNOSTIC_TEXT_LENGTH),
                    candidateScore = match.score, rivalMargin = match.margin,
                ))
            }
            val scheduled = retryOcr(bitmap, cropRect, fingerprint)
            return result.copy(eventOcrDiagnostics = diagnostic.copy(
                status = "NO_CATALOG_MATCH", requestScheduled = scheduled,
                rawText = observedText.take(MAX_DIAGNOSTIC_TEXT_LENGTH),
                candidateScore = match.score, rivalMargin = match.margin,
            ))
        }
        stableFrames = if (lastEventId == event.id) stableFrames + 1 else 1
        lastEventId = event.id
        val orderedChoices = event.choices.sortedBy { it.slot }
        val buttonRects = layoutMapper.buttonRects(orderedChoices.size, bitmap.width, bitmap.height)
        if (buttonRects.size != orderedChoices.size) return result.copy(eventOcrDiagnostics = diagnostic.copy(
            status = "BUTTON_MAPPING_FAILED", candidateEventId = event.id, stableFrames = stableFrames,
        ))
        val visuals = orderedChoices.zip(buttonRects).map { (choice, rect) ->
            LabyrinthEventChoiceVisual(
                choice = choice,
                buttonRect = rect,
                buttonConfidence = blueButtonConfidence(bitmap, rect),
                enabledConfidence = enabledButtonConfidence(bitmap, rect),
            )
        }
        val layoutConfirmed = !familyMatched || completeLayoutCount == 2
        val trusted = match.trusted && layoutConfirmed && stableFrames >= REQUIRED_STABLE_FRAMES
        val scheduled = if (!match.trusted && stableFrames >= REQUIRED_STABLE_FRAMES) {
            retryOcr(bitmap, cropRect, fingerprint)
        } else false
        val eventObservation = LabyrinthEventChoiceObservation(
            event = event,
            choices = visuals,
            rawText = observedText,
            confidence = match.score,
            rivalMargin = match.margin,
            stableFrames = stableFrames,
            trusted = trusted,
        )
        val anchorScore = match.score.coerceIn(0.0, 1.0)
        val updatedAnchors = LabyrinthAnchorScores(
            result.observation.anchorScores.values + mapOf(
                EntryAnchorId.EVENT_CHOICE_TITLE to match.titleScore.coerceIn(0.0, 1.0),
                EntryAnchorId.EVENT_CHOICE_INSTRUCTION to match.choiceScore.coerceIn(0.0, 1.0),
                EntryAnchorId.EVENT_CHOICE_OPTION to anchorScore,
            ),
        )
        return result.copy(
            observation = result.observation.copy(
                state = LabyrinthEntryPageState.EVENT_CHOICE,
                confidence = anchorScore,
                stateScores = result.observation.stateScores +
                    (LabyrinthEntryPageState.EVENT_CHOICE to anchorScore),
                anchorScores = updatedAnchors,
                reason = if (trusted) {
                    "事件OCR已稳定匹配 ${event.id}"
                } else {
                    "事件OCR候选 ${event.id}，等待重复确认"
                },
            ),
            matchedFeatures = (result.matchedFeatures + EVENT_OCR_FEATURE).distinct(),
            eventChoiceSelection = eventObservation,
            eventOcrDiagnostics = diagnostic.copy(
                status = if (familyMatched) {
                    if (trusted) "TITLE_TRUSTED_MATCH" else "TITLE_CANDIDATE_MATCH"
                } else {
                    if (trusted) "TRUSTED_MATCH" else "CANDIDATE_MATCH"
                },
                requestScheduled = scheduled,
                rawText = observedText.take(MAX_DIAGNOSTIC_TEXT_LENGTH),
                candidateEventId = event.id,
                candidateScore = match.score,
                rivalMargin = match.margin,
                stableFrames = stableFrames,
                trusted = trusted,
            ),
        )
    }

    private fun schedule(bitmap: Bitmap, rect: EntryPixelRect, fingerprint: Long): Boolean {
        val now = android.os.SystemClock.elapsedRealtime()
        val requestId = ocrCache.begin(LabyrinthEventOcrCache.Key(fingerprint, rect), now) ?: return false
        val crop = Bitmap.createBitmap(bitmap, rect.left, rect.top, rect.width, rect.height)
        lastOcrRequestAtMillis = now
        Log.d(LOG_TAG, "ocr-request id=$requestId elapsedMillis=$now crop=$rect fingerprint=${java.lang.Long.toHexString(fingerprint)}")
        runCatching {
            submitTextRead(crop) { text ->
                val completedAt = android.os.SystemClock.elapsedRealtime()
                val applied = synchronized(this) {
                    ocrCache.complete(requestId, text, completedAt)
                }
                Log.d(LOG_TAG, "ocr-result id=$requestId applied=$applied elapsedMillis=$completedAt durationMillis=${completedAt - now} textLength=${text?.trim()?.length ?: 0}")
                if (!crop.isRecycled) crop.recycle()
            }
        }.onFailure {
            Log.w(LOG_TAG, "ocr-request-failed id=$requestId", it)
            ocrCache.complete(requestId, null, android.os.SystemClock.elapsedRealtime())
            if (!crop.isRecycled) crop.recycle()
        }
        return true
    }

    /** A poor first OCR read must never pin a static event page forever. */
    private fun retryOcr(bitmap: Bitmap, rect: EntryPixelRect, fingerprint: Long): Boolean {
        val now = android.os.SystemClock.elapsedRealtime()
        if (lastOcrRequestAtMillis != Long.MIN_VALUE &&
            now - lastOcrRequestAtMillis < MIN_OCR_RETRY_INTERVAL_MILLIS
        ) return false
        return schedule(bitmap, rect, fingerprint)
    }

    private fun enabledButtonConfidence(bitmap: Bitmap, rect: EntryPixelRect): Double =
        labyrinthEventEnabledButtonConfidence(rect) { x, y -> bitmap.getPixel(x, y) }

    private fun blueButtonConfidence(bitmap: Bitmap, rect: EntryPixelRect): Double =
        labyrinthEventBlueButtonConfidence(rect, bitmap::getPixel)

    /** Dense text-mask fingerprint keeps consecutive short stage titles from sharing white-space hashes. */
    private fun titleFingerprint(bitmap: Bitmap, rect: EntryPixelRect): Long {
        var hash = -3750763034362895579L
        for (y in rect.top until rect.top + rect.height step 2) {
            for (x in rect.left until rect.left + rect.width step 2) {
                val ink = if (luminance(bitmap.getPixel(x, y)) < 135) 1L else 0L
                hash = (hash xor ink) * 1099511628211L
            }
        }
        return hash
    }

    private fun differenceHash(bitmap: Bitmap, rect: EntryPixelRect): Long {
        var output = 0L
        var bit = 0
        for (sampleY in 0 until HASH_ROWS) {
            val y = rect.top +
                (sampleY.toDouble() * (rect.height - 1) / (HASH_ROWS - 1)).roundToInt()
            for (sampleX in 0 until HASH_COLUMNS - 1) {
                val firstX = rect.left +
                    (sampleX.toDouble() * (rect.width - 1) / (HASH_COLUMNS - 1)).roundToInt()
                val secondX = rect.left +
                    ((sampleX + 1).toDouble() * (rect.width - 1) / (HASH_COLUMNS - 1)).roundToInt()
                if (luminance(bitmap.getPixel(firstX, y)) > luminance(bitmap.getPixel(secondX, y))) {
                    output = output or (1L shl bit)
                }
                bit++
            }
        }
        return output
    }

    private fun luminance(color: Int): Int {
        val red = color ushr 16 and 0xff
        val green = color ushr 8 and 0xff
        val blue = color and 0xff
        return (red * 299 + green * 587 + blue * 114 + 500) / 1000
    }

    private fun clearPageState() {
        fullOcrFallback = false
        ocrCache.clear()
        lastEventId = null
        stableFrames = 0
        lastOcrRequestAtMillis = Long.MIN_VALUE
    }

    private companion object {
        val ELIGIBLE_STATES = setOf(
            LabyrinthEntryPageState.UNKNOWN,
            LabyrinthEntryPageState.EVENT_CHOICE,
        )
        val STANDARD_REFERENCE = EntryReferenceSize(1920, 1080)
        val OCR_REFERENCE_RECT = EntryReferenceRect(150, 95, 1620, 750)
        const val EVENT_OCR_FEATURE = "entry.event_choice.ocr"
        const val REQUIRED_STABLE_FRAMES = 2
        const val HASH_COLUMNS = 9
        const val HASH_ROWS = 8
        const val MINIMUM_EVENT_BUTTON_CANDIDATE = 0.12
        const val MIN_OCR_RETRY_INTERVAL_MILLIS = 1_500L
        const val MAX_DIAGNOSTIC_TEXT_LENGTH = 2_000
        const val LOG_TAG = "LabyrinthEventOcr"
    }
}
