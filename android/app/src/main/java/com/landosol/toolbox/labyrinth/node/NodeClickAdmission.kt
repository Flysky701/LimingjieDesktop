package com.landosol.toolbox.labyrinth.node

/**
 * Admission for a semantic-only or weakly positioned node candidate, not a new detector.
 *
 * The classifier owns activation (including the low phase of EX purple glow). Route reachability
 * must never manufacture it. A 0.40 proposal is useful for search but not sufficient for assigning
 * a unique route identity. Keep this floor below the existing 0.56 event and 0.698 EX fixtures;
 * stronger ordered-column and explicitly guarded semantic-swap policies remain separate.
 */
internal object NodeClickAdmission {
    private const val MIN_SEMANTIC_VISUAL_CONFIDENCE = 0.55

    fun hasFiniteVisualEvidence(visualConfidence: Double, typeConfidence: Double): Boolean =
        visualConfidence.isFinite() && visualConfidence in 0.0..1.0 &&
            typeConfidence.isFinite() && typeConfidence in 0.0..1.0

    fun hasSemanticScores(visualConfidence: Double, typeConfidence: Double): Boolean =
        hasFiniteVisualEvidence(visualConfidence, typeConfidence) &&
            visualConfidence >= MIN_SEMANTIC_VISUAL_CONFIDENCE &&
            typeConfidence >= MIN_SEMANTIC_VISUAL_CONFIDENCE

    fun hasSemanticEvidence(candidate: NodeClassification): Boolean =
        candidate.isClickable &&
            LabyrinthNodeTypes.isClickable(candidate.blockType) &&
            candidate.screenRect?.let { it.width > 0 && it.height > 0 } == true &&
            hasSemanticScores(candidate.confidence, candidate.typeConfidence)
}
