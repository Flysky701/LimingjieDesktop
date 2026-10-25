package com.landosol.toolbox.labyrinth.vision

import android.graphics.Bitmap
import com.landosol.toolbox.labyrinth.labyrinthRoleRewardNeedsNameVerification
import kotlin.math.roundToInt

/** Read all three names first; only uncertain rows invoke the portrait matcher. */
class AndroidLabyrinthRoleRewardNameResolver(
    private val fuzzyMatcher: LabyrinthCharacterNameFuzzyMatcher = LabyrinthCharacterNameFuzzyMatcher(),
    private val submitTextRead: (Bitmap, (String?) -> Unit) -> Unit = AndroidChineseRoleNameOcr::readAsync,
    private val names: List<LabyrinthCharacterNameCandidate> = emptyList(),
    private val recognizePortrait: ((Bitmap, String) -> LabyrinthCharacterMatch)? = null,
    private val clock: () -> Long = android.os.SystemClock::elapsedRealtime,
) {
    private val cache = mutableMapOf<String, CachedOcr>()
    private val pending = mutableMapOf<String, Long>()
    private var remainingNewOcrRuns = 0
    private var generation = 0L
    private val startedAt = mutableMapOf<String, Long>()
    private val resolvedPortraits = mutableMapOf<String, Pair<Long, LabyrinthCharacterMatch>>()

    @Synchronized
    fun resolve(
        bitmap: Bitmap,
        result: LabyrinthEntryFrameResult,
    ): LabyrinthEntryFrameResult {
        if (!com.landosol.toolbox.labyrinth.labyrinthIsRoleRewardPage(result)) {
            // Every role-reward page is separated by another page/state in the live flow. Clearing
            // here avoids carrying a perceptual-hash hit into a later reward choice while keeping
            // the cache useful for a stationary page.
            cache.clear()
            pending.clear()
            startedAt.clear()
            resolvedPortraits.clear()
            generation++
            return result
        }
        if (result.characterMatches.isEmpty()) return result

        // Schedule all three name rows together; reuse completed OCR on subsequent frames.
        remainingNewOcrRuns = MAX_NEW_OCR_RUNS_PER_FRAME
        var changed = false
        val resolved = result.characterMatches.map { match ->
            val updated = resolveNameFirst(bitmap, match)
            if (updated != match) changed = true
            updated
        }
        return if (changed) result.copy(characterMatches = resolved) else result
    }

    private fun resolveNameFirst(bitmap: Bitmap, match: LabyrinthCharacterMatch): LabyrinthCharacterMatch {
        val reference = NAME_RECTS[match.slotId] ?: return match
        val rect = ReferenceFitMapper.map(bitmap.width, bitmap.height, STANDARD_REFERENCE, reference)
            ?: return match
        val text = readCachedOrSchedule(bitmap, match.slotId, rect)
        if (text == null && clock() - (startedAt[match.slotId] ?: clock()) < 2_500L) return match
        return roleRewardTextFirstMatch(text.orEmpty(), names, match) {
            val hash = differenceHash(bitmap, rect)
            resolvedPortraits[match.slotId]?.let { (previous, resolved) ->
                if (java.lang.Long.bitCount(previous xor hash) <= MAX_CACHE_HASH_DISTANCE) return@roleRewardTextFirstMatch resolved
            }
            val portrait = recognizePortrait?.invoke(bitmap, match.slotId) ?: match
            val resolved = resolveMatch(bitmap, portrait)
            if (com.landosol.toolbox.labyrinth.labyrinthRoleRewardIdentityIsActionSafe(resolved)) {
                resolvedPortraits[match.slotId] = hash to resolved
            }
            resolved
        }
    }

    private fun resolveMatch(bitmap: Bitmap, match: LabyrinthCharacterMatch): LabyrinthCharacterMatch {
        if (!labyrinthRoleRewardNeedsNameVerification(match)) return match

        val referenceRect = NAME_RECTS[match.slotId] ?: return match
        val screenRect = ReferenceFitMapper.map(
            frameWidth = bitmap.width,
            frameHeight = bitmap.height,
            referenceSize = STANDARD_REFERENCE,
            referenceRect = referenceRect,
        ) ?: return match
        val candidates = roleRewardNameCandidates(match)
        if (candidates.isEmpty()) return match

        val observedText = readCachedOrSchedule(bitmap, match.slotId, screenRect) ?: return match
        if (observedText.isBlank()) return match
        val evidence = fuzzyMatcher.match(
            observedText = observedText,
            candidates = candidates.map { candidate ->
                LabyrinthCharacterNameCandidate(
                    characterId = candidate.characterId,
                    displayName = candidate.displayName,
                    aliases = candidate.aliases,
                )
            },
        )
        val evidenceOnly = match.copy(
            nameEvidenceText = observedText,
            nameEvidenceScore = evidence.score,
            nameEvidenceMargin = evidence.margin,
        )
        if (!evidence.trusted) {
            val singleCharacter = roleRewardSingleCharacterOcrAssist(
                observedText = observedText,
                candidates = candidates,
                suspectedCharacterId = match.suspectedCharacterId,
                iconConfidence = match.confidence,
                iconMargin = match.rivalMargin,
            ) ?: return evidenceOnly
            val strongestOther = candidates
                .filter { it.characterId != singleCharacter.characterId }
                .maxOfOrNull(LabyrinthCharacterIconCandidate::confidence)
                ?: 0.0
            return evidenceOnly.copy(
                characterId = singleCharacter.characterId,
                displayName = singleCharacter.displayName,
                iconVariant = singleCharacter.iconVariant,
                confidence = singleCharacter.confidence,
                rivalMargin = singleCharacter.confidence - strongestOther,
                trusted = true,
                suspectedCharacterId = singleCharacter.characterId,
                suspectedDisplayName = singleCharacter.displayName,
                nameEvidenceScore = SINGLE_CHARACTER_OCR_ASSIST_SCORE,
                nameEvidenceMargin = SINGLE_CHARACTER_OCR_ASSIST_MARGIN,
                nameAssisted = true,
            )
        }

        val chosen = candidates.firstOrNull { it.characterId == evidence.characterId }
            ?: return evidenceOnly
        val strongestOther = candidates
            .filter { it.characterId != chosen.characterId }
            .maxOfOrNull(LabyrinthCharacterIconCandidate::confidence)
            ?: 0.0
        return evidenceOnly.copy(
            characterId = chosen.characterId,
            displayName = chosen.displayName,
            iconVariant = chosen.iconVariant,
            confidence = chosen.confidence,
            rivalMargin = chosen.confidence - strongestOther,
            trusted = true,
            suspectedCharacterId = chosen.characterId,
            suspectedDisplayName = chosen.displayName,
            nameAssisted = true,
        )
    }

    private fun readCachedOrSchedule(bitmap: Bitmap, slotId: String, rect: EntryPixelRect): String? {
        val fingerprint = differenceHash(bitmap, rect)
        cache[slotId]?.let { cached ->
            if (java.lang.Long.bitCount(cached.fingerprint xor fingerprint) <= MAX_CACHE_HASH_DISTANCE) {
                return cached.text
            }
        }
        // OCR is asynchronous so the MediaProjection/ImageReader callback is never blocked by ML
        // Kit model work. A later 500 ms frame consumes the cached result once it is ready.
        if (pending.containsKey(slotId)) return null
        if (remainingNewOcrRuns <= 0) return null
        remainingNewOcrRuns--
        val crop = Bitmap.createBitmap(bitmap, rect.left, rect.top, rect.width, rect.height)
        val requestGeneration = generation
        pending[slotId] = fingerprint
        startedAt[slotId] = clock()
        runCatching {
            submitTextRead(crop) { text ->
                synchronized(this) {
                    if (requestGeneration == generation && pending[slotId] == fingerprint) {
                        pending.remove(slotId)
                        cache[slotId] = CachedOcr(fingerprint, text.orEmpty().trim())
                    }
                }
                if (!crop.isRecycled) crop.recycle()
            }
        }.onFailure {
            pending.remove(slotId)
            cache[slotId] = CachedOcr(fingerprint, "")
            if (!crop.isRecycled) crop.recycle()
        }
        return null
    }

    /** 9x8 dHash: cheap enough to evaluate on every ambiguous frame and robust to tiny UI noise. */
    private fun differenceHash(bitmap: Bitmap, rect: EntryPixelRect): Long {
        var result = 0L
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
                    result = result or (1L shl bit)
                }
                bit++
            }
        }
        return result
    }

    private fun luminance(color: Int): Int {
        val red = color ushr 16 and 0xff
        val green = color ushr 8 and 0xff
        val blue = color and 0xff
        return (red * 299 + green * 587 + blue * 114 + 500) / 1000
    }

    private data class CachedOcr(val fingerprint: Long, val text: String)

    private companion object {
        val STANDARD_REFERENCE = EntryReferenceSize(1920, 1080)
        val NAME_RECTS = mapOf(
            // Include the complete name now that OCR is the primary source of identity.
            "role_reward_left" to EntryReferenceRect(260, 650, 355, 135),
            "role_reward_center" to EntryReferenceRect(820, 650, 300, 135),
            "role_reward_right" to EntryReferenceRect(1370, 650, 300, 135),
        )
        const val MAX_ICON_GAP_FOR_NAME_ASSIST = 0.08
        const val MAX_CACHE_HASH_DISTANCE = 6
        const val MAX_NEW_OCR_RUNS_PER_FRAME = 3
        const val HASH_COLUMNS = 9
        const val HASH_ROWS = 8
        const val SINGLE_CHARACTER_OCR_ASSIST_SCORE = 0.82
        const val SINGLE_CHARACTER_OCR_ASSIST_MARGIN = 0.16
    }
}

/**
 * Single-character role names are a special OCR failure mode: a visually similar Han glyph can be
 * substituted every frame (露 -> 路 in a live report), while edit-distance=1 is meaningless for an
 * arbitrary one-character vocabulary.  Do not maintain a hand-written glyph table.  Instead allow
 * text to break the tie only when the icon shortlist itself supplies exactly one one-character
 * role and that role is already the portrait matcher's moderately separated best hypothesis.
 */
internal fun roleRewardSingleCharacterOcrAssist(
    observedText: String,
    candidates: List<LabyrinthCharacterIconCandidate>,
    suspectedCharacterId: String?,
    iconConfidence: Double,
    iconMargin: Double,
): LabyrinthCharacterIconCandidate? {
    val observed = observedText.filter(Char::isLetterOrDigit)
    if (observed.length != 1) return null
    if (iconConfidence < 0.45 || iconMargin < 0.06) return null
    val singleCharacterCandidates = candidates.filter { candidate ->
        val names = sequenceOf(candidate.displayName) + candidate.aliases.asSequence()
        names.any { name ->
            name.substringBefore('(').substringBefore('（')
                .filter(Char::isLetterOrDigit)
                .length == 1
        }
    }
    if (singleCharacterCandidates.size != 1) return null
    return singleCharacterCandidates.single()
        .takeIf { it.characterId == suspectedCharacterId }
}

/**
 * Keeps OCR constrained to the portrait matcher's nearby alternatives without reapplying the
 * portrait trust floor. A low best score is itself a reason to ask the visible name row for help.
 */
internal fun roleRewardNameCandidates(
    match: LabyrinthCharacterMatch,
    maximumIconGap: Double = 0.08,
): List<LabyrinthCharacterIconCandidate> {
    val bestIconScore = match.iconCandidates.firstOrNull()?.confidence ?: return emptyList()
    return match.iconCandidates.filter { candidate ->
        bestIconScore - candidate.confidence <= maximumIconGap
    }
}

