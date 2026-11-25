package com.landosol.toolbox.labyrinth.vision

/**
 * Current-frame visual evidence only. This identity deliberately contains neither trusted OCR
 * text nor button rectangles/actionability: retaining a calculation never authorizes a click.
 * The caller owns the previously trusted event and the unchanged semantic planning inputs.
 */
internal data class LabyrinthEventVisualIdentity(
    val sceneEpoch: Long,
    val frameWidth: Int,
    val frameHeight: Int,
    val fingerprint: String,
    val cropRect: EntryPixelRect,
    val layoutCount: Int,
) {
    companion object {
        /** Fail closed unless the diagnostic describes a complete, in-frame event layout. */
        fun from(
            sceneEpoch: Long,
            frameWidth: Int,
            frameHeight: Int,
            diagnostic: LabyrinthEventOcrDiagnostics?,
        ): LabyrinthEventVisualIdentity? {
            if (sceneEpoch < 0L || frameWidth <= 0 || frameHeight <= 0) return null
            val evidence = diagnostic ?: return null
            val fingerprint = evidence.fingerprint?.takeIf { it.isNotBlank() } ?: return null
            val crop = evidence.cropRect ?: return null
            // Long arithmetic avoids wrapping an otherwise valid EntryPixelRect's endpoints.
            if (crop.left.toLong() + crop.width > frameWidth.toLong() ||
                crop.top.toLong() + crop.height > frameHeight.toLong()
            ) return null
            val layoutCount = evidence.completeLayoutCount?.takeIf { it in 1..3 } ?: return null
            val confidences = evidence.buttonConfidencesByOptionCount[layoutCount] ?: return null
            if (confidences.size != layoutCount ||
                confidences.any { !it.isFinite() || it !in MINIMUM_LAYOUT_BUTTON_CONFIDENCE..1.0 }
            ) return null
            if (labyrinthCompleteEventLayoutCount(
                    evidence.buttonConfidencesByOptionCount, MINIMUM_LAYOUT_BUTTON_CONFIDENCE,
                ) != layoutCount
            ) return null
            return LabyrinthEventVisualIdentity(
                sceneEpoch, frameWidth, frameHeight, fingerprint, crop, layoutCount,
            )
        }
    }
}

/**
 * A bounded calculation-only grace period for a normal OCR cache refresh. The caller must first
 * verify the same previously trusted event and semantic calculation key, and must prohibit clicks
 * while the current event observation is absent/untrusted. No prior OCR/button evidence is reused.
 */
internal fun canRetainEventCalculationDuringOcrExpiry(
    lastTrusted: LabyrinthEventVisualIdentity?,
    current: LabyrinthEventVisualIdentity?,
    page: LabyrinthEntryPageState,
    diagnostic: LabyrinthEventOcrDiagnostics?,
): Boolean {
    if (page != LabyrinthEntryPageState.EVENT_CHOICE) return false
    val previous = lastTrusted ?: return false
    val visual = current ?: return false
    if (previous != visual) return false
    val evidence = diagnostic ?: return false
    if (evidence.status != "TITLE_WAITING_OCR" && evidence.status != "WAITING_OCR") return false
    // Validate that the supplied identity really belongs to this current diagnostic.
    if (LabyrinthEventVisualIdentity.from(
            visual.sceneEpoch, visual.frameWidth, visual.frameHeight, evidence,
        ) != visual
    ) return false
    val cache = evidence.cache ?: return false
    if (cache.status != "EXPIRED" || cache.cachedCropRect != visual.cropRect ||
        cache.fingerprintDistance != 0 || cache.lastResultStatus != "TEXT"
    ) return false
    val age = cache.ageMillis ?: return false
    val lifetime = cache.cacheLifetimeMillis
    if (lifetime <= 0L || age < lifetime) return false
    // Both operands are now nonnegative and age >= lifetime, so subtraction cannot overflow.
    // At the exclusive upper boundary, one OCR request-timeout window has elapsed: fail closed.
    return age - lifetime < MAXIMUM_OCR_REFRESH_GRACE_MILLIS
}

private const val MINIMUM_LAYOUT_BUTTON_CONFIDENCE = 0.12
private const val MAXIMUM_OCR_REFRESH_GRACE_MILLIS = 5_000L
