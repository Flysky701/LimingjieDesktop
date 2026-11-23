package com.landosol.toolbox.labyrinth.vision

/** Diagnostic outcomes only; none of these values authorize recognition or selection. */
enum class LabyrinthInitial1088RefinementOutcome {
    SKIPPED_TRUSTED,
    BLOCKED_PERMISSION,
    BLOCKED_CLIPPED,
    BLOCKED_VIEWPORT,
    EXECUTED_TRUSTED,
    EXECUTED_UNTRUSTED,
}

/** Distinct character identities, not competing icon variants of the same character. */
data class LabyrinthInitial1088RefinementCandidate(
    val characterId: String,
    val score: Double,
)

data class LabyrinthInitial1088RefinementSnapshot(
    val score: Double,
    val margin: Double,
    val suspectedCharacterId: String?,
    val topCandidates: List<LabyrinthInitial1088RefinementCandidate>,
)

/**
 * Per-card, immutable evidence from the existing opening-1088 refinement path.
 * Rectangles use frame pixels; no frame image or Android logging dependency is retained.
 */
data class LabyrinthInitial1088RefinementEvidence(
    val outcome: LabyrinthInitial1088RefinementOutcome,
    val before: LabyrinthInitial1088RefinementSnapshot,
    val after: LabyrinthInitial1088RefinementSnapshot,
    val originalMatchRoi: EntryPixelRect,
    /** Highest-scoring crop supporting the final winner, including the original match ROI. */
    val bestCrop: EntryPixelRect? = null,
    /** Executed refinement comparisons, including the original ROI; zero when blocked/skipped. */
    val attempts: Int = 0,
    val cacheHit: Boolean = false,
    /** The caller may enrich this from the same-frame route/session permission context. */
    val permitReason: String? = null,
    /** Best distinct identities across attempted crops; after remains the actual returned result. */
    val searchTopCandidates: List<LabyrinthInitial1088RefinementCandidate> = emptyList(),
)

internal fun LabyrinthCharacterIconMatch.initial1088RefinementSnapshot() =
    LabyrinthInitial1088RefinementSnapshot(
        score = confidence,
        margin = rivalMargin,
        suspectedCharacterId = suspectedCharacterId,
        topCandidates = candidates.take(2).map { candidate ->
            LabyrinthInitial1088RefinementCandidate(candidate.characterId, candidate.confidence)
        },
    )

internal fun LabyrinthCharacterIconMatch.isInitial1088RefinementSubject(): Boolean =
    characterId == "1088" || suspectedCharacterId == "1088"
