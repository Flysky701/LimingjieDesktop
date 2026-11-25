package com.landosol.toolbox.labyrinth.vision

import com.landosol.toolbox.core.MonotonicClock
import com.landosol.toolbox.core.AppContext
import com.landosol.toolbox.vision.NativeBitmap
import com.landosol.toolbox.core.logging.AppLogger
import com.landosol.toolbox.gamedata.EventChoiceResource
import com.landosol.toolbox.gamedata.EventResource
import com.landosol.toolbox.labyrinth.kernel.event.GameEventCatalogRegistry
import java.util.UUID
import com.landosol.toolbox.gamedata.LabyrinthEventOcrDocument
import kotlin.math.roundToInt
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

data class LabyrinthEventRecognitionData(
    val events: List<EventResource>,
    val ocr: LabyrinthEventOcrDocument,
)

class LabyrinthEventRecognitionDataLoader(
    private val context: AppContext,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    fun load(): LabyrinthEventRecognitionData {
        val eventDocument = context.assets.open(EVENTS_PATH).bufferedReader().use { reader ->
            json.decodeFromString<EventCategoryDocument>(reader.readText())
        }
        val ocrDocument = context.assets.open(OCR_PATH).bufferedReader().use { reader ->
            json.decodeFromString<LabyrinthEventOcrDocument>(reader.readText())
        }
        require(eventDocument.schemaVersion == 1 && eventDocument.events.isNotEmpty()) {
            "事件目录为空或版本不受支持"
        }
        return LabyrinthEventRecognitionData(eventDocument.events, ocrDocument)
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
class LabyrinthEventChoiceResolver(
    private val data: LabyrinthEventRecognitionData,
    private val submitTextRead: (NativeBitmap, (String?) -> Unit) -> Unit = DesktopChineseRoleNameOcr::readAsync,
    private val eventChoiceOverrides: Map<String, String> = emptyMap(),
    private val eventCustomizationEnabled: Boolean = false,
    private val catalogSource: ((List<EventResource>) -> GameEventCatalogRegistry)? = null,
    private val readUnknownOptionText: () -> Boolean = { false },
) {
    private var fullOcrFallback = false
    private var catalog = catalogSource?.invoke(data.events)
    private var recognizer = LabyrinthEventTextRecognizer(data.events, catalogRegistry = catalog)
    private var customTitleEvents = titleEvents()
    private fun titleEvents() = if (eventCustomizationEnabled) {
        recognizer.customTitleEvents(eventChoiceOverrides)
    } else emptyList()
    private val layoutMapper = LabyrinthEventLayoutMapper(data.ocr.layouts)
    private val ocrCache = LabyrinthEventOcrCache()
    private var unknownPageId: String? = null
    private var slotCaches = emptyList<LabyrinthEventOcrCache>()
    private var nextSlotRead = 0
    private var lastEventId: String? = null
    private var stableFrames = 0
    private var lastOcrRequestAtMillis = Long.MIN_VALUE

    @Synchronized
    fun resolve(bitmap: NativeBitmap, result: LabyrinthEntryFrameResult): LabyrinthEntryFrameResult {
        val current = catalogSource?.invoke(data.events)
        if (current !== catalog) {
            clearPageState()
            catalog = current
            recognizer = LabyrinthEventTextRecognizer(data.events, catalogRegistry = current)
            customTitleEvents = titleEvents()
        }
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
        val titleRect = if (customTitleScope) layoutMapper.titleBounds(2, bitmap.width, bitmap.height) else null
        val currentTitleFingerprint = titleRect?.let { titleFingerprint(bitmap, it) }
        val fingerprint = if (customTitleScope) {
            (currentTitleFingerprint ?: titleFingerprint(bitmap, cropRect)) xor
                (if (titleOnly) 0L else differenceHash(bitmap, cropRect))
        } else differenceHash(bitmap, cropRect)
        val lookup = ocrCache.lookup(
            LabyrinthEventOcrCache.Key(fingerprint, cropRect, currentTitleFingerprint),
            MonotonicClock.elapsedRealtime(),
        )
        val diagnostic = LabyrinthEventOcrDiagnostics(
            status = "CACHE_LOOKUP",
            cropRect = cropRect,
            fingerprint = java.lang.Long.toHexString(fingerprint),
            completeLayoutCount = completeLayoutCount,
            buttonConfidencesByOptionCount = confidencesByOptionCount,
            cache = lookup.diagnostics,
        )
        // Match only cached text here; reuse still requires current-frame/request-source evidence.
        val titleMatch = if (customTitleScope) {
            lookup.text?.let { recognizer.recognizeCustomTitle(it, customTitleEvents) }
        } else null
        val observedText = lookup.text.takeIf {
            canReuseLabyrinthEventOcrText(lookup, customTitleScope, currentTitleFingerprint, titleMatch)
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
                retryOcr(bitmap, cropRect, fingerprint, currentTitleFingerprint)
                return result.copy(eventOcrDiagnostics = diagnostic.copy(status = "OCR_UNAVAILABLE"))
            }
            if (completedWithoutText) {
                val scheduled = retryOcr(bitmap, cropRect, fingerprint, currentTitleFingerprint)
                return result.copy(eventOcrDiagnostics = diagnostic.copy(
                    status = "OCR_UNAVAILABLE",
                    requestScheduled = scheduled,
                ))
            }
            // Avoid flooding the OCR queue with full-screen fallback crops on noisy UNKNOWN frames
            // when no complete calibrated event button layout is visible.
            val scheduled = if (result.observation.state == LabyrinthEntryPageState.EVENT_CHOICE || completeLayoutCount != null) {
                schedule(bitmap, cropRect, fingerprint, requestTitleFingerprint = currentTitleFingerprint)
            } else {
                false
            }
            return result.copy(eventOcrDiagnostics = diagnostic.copy(
                status = if (titleOnly) "TITLE_WAITING_OCR" else "WAITING_OCR",
                requestScheduled = scheduled,
            ))
        }

        // Full OCR can recover a title without needing regional arena reward digits.
        val familyMatched = titleMatch?.event != null
        val match = if (titleOnly || familyMatched) requireNotNull(titleMatch)
            else recognizer.recognize(observedText)
        val event = match.event ?: run {
            if (titleOnly) {
                lastEventId = null
                stableFrames = 0
                fullOcrFallback = true
                ocrCache.clear()
                return result.copy(eventOcrDiagnostics = diagnostic.copy(
                    status = "TITLE_FALLBACK", rawText = observedText.take(MAX_DIAGNOSTIC_TEXT_LENGTH),
                    candidateScore = match.score, rivalMargin = match.margin,
                ))
            }
            // An unmatched OCR block on an UNKNOWN animation cannot establish an event page.
            // Only a verified event page and a complete layout supply actionable slot geometry.
            if (result.observation.state == LabyrinthEntryPageState.EVENT_CHOICE && completeLayoutCount != null &&
                lookup.diagnostics.fingerprintDistance == 0) {
                val id = "ocr/$completeLayoutCount/" + UUID.nameUUIDFromBytes(observedText.toByteArray(Charsets.UTF_8))
                stableFrames = if (lastEventId == id) stableFrames + 1 else 1
                lastEventId = id
                val descriptions = if (readUnknownOptionText()) unknownChoiceDescriptions(bitmap, id, completeLayoutCount) else emptyList()
                val visuals = layoutMapper.buttonRects(completeLayoutCount, bitmap.width, bitmap.height).mapIndexed { index, rect ->
                    val choice = EventChoiceResource(id = "$id/${index + 1}", slot = index + 1, name = "选项${index + 1}",
                        description = descriptions.getOrNull(index).orEmpty())
                    LabyrinthEventChoiceVisual(choice, rect, blueButtonConfidence(bitmap, rect), enabledButtonConfidence(bitmap, rect))
                }
                val unknown = EventResource(id = id, name = observedText.lineSequence().first().take(100), choices = visuals.map { it.choice })
                return result.copy(eventChoiceSelection = LabyrinthEventChoiceObservation(unknown, visuals, observedText,
                    result.observation.confidence, 0.0, stableFrames, stableFrames >= REQUIRED_STABLE_FRAMES, catalogMatched = false, sourceCatalog = catalog),
                    eventOcrDiagnostics = diagnostic.copy(status = "NO_CATALOG_MATCH", rawText = observedText.take(MAX_DIAGNOSTIC_TEXT_LENGTH),
                        stableFrames = stableFrames))
            }
            lastEventId = null
            stableFrames = 0
            val scheduled = retryOcr(bitmap, cropRect, fingerprint, currentTitleFingerprint)
            return result.copy(eventOcrDiagnostics = diagnostic.copy(
                status = "NO_CATALOG_MATCH", requestScheduled = scheduled,
                rawText = observedText.take(MAX_DIAGNOSTIC_TEXT_LENGTH),
                candidateScore = match.score, rivalMargin = match.margin,
            ))
        }
        unknownPageId = null
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
            retryOcr(bitmap, cropRect, fingerprint, currentTitleFingerprint)
        } else false
        val eventObservation = LabyrinthEventChoiceObservation(
            event = event,
            choices = visuals,
            rawText = observedText,
            confidence = match.score,
            rivalMargin = match.margin,
            stableFrames = stableFrames,
            trusted = trusted,
            sourceCatalog = catalog,
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

    private fun unknownChoiceDescriptions(bitmap: NativeBitmap, id: String, count: Int): List<String?> {
        val rects = layoutMapper.optionBounds(count, bitmap.width, bitmap.height)
        if (rects.isEmpty()) return emptyList()
        if (unknownPageId != id) {
            unknownPageId = id
            slotCaches = List(count) { LabyrinthEventOcrCache() }
            nextSlotRead = 0
        }
        val now = MonotonicClock.elapsedRealtime()
        val fingerprints = rects.map { titleFingerprint(bitmap, it) }
        val texts = rects.mapIndexed { slot, rect ->
            val lookup = slotCaches[slot].lookup(LabyrinthEventOcrCache.Key(fingerprints[slot], rect), now)
            lookup.text.takeIf { lookup.diagnostics.fingerprintDistance == 0 }
        }
        // One extra submission per detail frame, only for a policy which needs slot-local text.
        for (offset in rects.indices) {
            val slot = (nextSlotRead + offset) % count
            if (texts[slot] == null && schedule(bitmap, rects[slot], fingerprints[slot], slotCaches[slot])) {
                nextSlotRead = (slot + 1) % count
                break
            }
        }
        return texts
    }

    private fun schedule(
        bitmap: NativeBitmap,
        rect: EntryPixelRect,
        fingerprint: Long,
        cache: LabyrinthEventOcrCache = ocrCache,
        requestTitleFingerprint: Long? = null,
    ): Boolean {
        val now = MonotonicClock.elapsedRealtime()
        val requestId = cache.begin(
            LabyrinthEventOcrCache.Key(fingerprint, rect, requestTitleFingerprint), now,
        ) ?: return false
        val crop = NativeBitmap.create(bitmap, rect.left, rect.top, rect.width, rect.height)
        if (cache === ocrCache) lastOcrRequestAtMillis = now
        AppLogger.d(LOG_TAG, "ocr-request id=$requestId elapsedMillis=$now crop=$rect fingerprint=${java.lang.Long.toHexString(fingerprint)}")
        runCatching {
            submitTextRead(crop) { text ->
                val completedAt = MonotonicClock.elapsedRealtime()
                val applied = synchronized(this) {
                    cache.complete(requestId, text, completedAt)
                }
                AppLogger.d(LOG_TAG, "ocr-result id=$requestId applied=$applied elapsedMillis=$completedAt durationMillis=${completedAt - now} textLength=${text?.trim()?.length ?: 0}")
                if (!crop.isRecycled) crop.recycle()
            }
        }.onFailure {
            AppLogger.w(LOG_TAG, "ocr-request-failed id=$requestId", it)
            cache.complete(requestId, null, MonotonicClock.elapsedRealtime())
            if (!crop.isRecycled) crop.recycle()
        }
        return true
    }

    /** A poor first OCR read must never pin a static event page forever. */
    private fun retryOcr(
        bitmap: NativeBitmap,
        rect: EntryPixelRect,
        fingerprint: Long,
        requestTitleFingerprint: Long? = null,
    ): Boolean {
        val now = MonotonicClock.elapsedRealtime()
        if (lastOcrRequestAtMillis != Long.MIN_VALUE &&
            now - lastOcrRequestAtMillis < MIN_OCR_RETRY_INTERVAL_MILLIS
        ) return false
        return schedule(bitmap, rect, fingerprint, requestTitleFingerprint = requestTitleFingerprint)
    }

    private fun enabledButtonConfidence(bitmap: NativeBitmap, rect: EntryPixelRect): Double =
        labyrinthEventEnabledButtonConfidence(rect) { x, y -> bitmap.getPixel(x, y) }

    private fun blueButtonConfidence(bitmap: NativeBitmap, rect: EntryPixelRect): Double =
        labyrinthEventBlueButtonConfidence(rect, bitmap::getPixel)

    /** Dense text-mask fingerprint keeps consecutive short stage titles from sharing white-space hashes. */
    private fun titleFingerprint(bitmap: NativeBitmap, rect: EntryPixelRect): Long {
        var hash = -3750763034362895579L
        for (y in rect.top until rect.top + rect.height step 2) {
            for (x in rect.left until rect.left + rect.width step 2) {
                val ink = if (luminance(bitmap.getPixel(x, y)) < 135) 1L else 0L
                hash = (hash xor ink) * 1099511628211L
            }
        }
        return hash
    }

    private fun differenceHash(bitmap: NativeBitmap, rect: EntryPixelRect): Long {
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
        unknownPageId = null
        slotCaches = emptyList()
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

/** Only the three requested titles may tolerate crop animation; this never authorizes a click. */
internal fun canReuseLabyrinthEventOcrText(
    lookup: LabyrinthEventOcrCache.Lookup,
    customTitleScope: Boolean,
    currentTitleFingerprint: Long?,
    titleMatch: LabyrinthEventTextMatch?,
): Boolean {
    if (lookup.text == null) return false
    if (!customTitleScope) return true
    val scopedMatch = titleMatch?.trusted == true &&
        titleMatch.event?.id in ANIMATED_CUSTOM_TITLE_EVENT_IDS
    if (!scopedMatch) return lookup.diagnostics.fingerprintDistance == 0
    // With identical title hashes, the existing XOR distance is exactly the crop dHash distance.
    // HIT already enforces the original crop geometry, lifetime and <=8-bit similarity policy.
    return lookup.diagnostics.status == "HIT" && currentTitleFingerprint != null &&
        lookup.requestTitleFingerprint == currentTitleFingerprint
}

private val ANIMATED_CUSTOM_TITLE_EVENT_IDS = setOf(LabyrinthArenaEvent.ID, "2316", "3316")
