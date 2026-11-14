package com.landosol.toolbox.labyrinth.vision

import com.landosol.toolbox.clanbattle.recognition.PixelImage

/**
 * Observation / safety fence only, never an action ACK or an identity / node inference.
 * Carries no pixels, detailed roster, map nodes, relic identity, or OCR result.
 */
data class LabyrinthFastFrameObservation(
    val observation: LabyrinthEntryPageObservation,
    val frameSequence: Long,
    val timestampMillis: Long,
    val width: Int,
    val height: Int,
    val elapsedMillis: Long,
    val anchorScores: LabyrinthAnchorScores? = null,
    val anchorMatches: Map<String, EntryAnchorMatch> = emptyMap(),
    val nodeMoveConfirmation: LabyrinthNodeMoveConfirmationObservation? = null,
    val battleFailure: LabyrinthBattleFailureObservation? = null,
    val battleEndConfirmation: LabyrinthBattleEndConfirmationObservation? = null,
    val specialEquipment: LabyrinthSpecialEquipmentObservation? = null,
    val exChallenge: LabyrinthExChallengeObservation? = null,
)

/**
 * Independent page-only core: no detail processor or heavy recognizer is constructed or invoked.
 * Owns its matcher and prepared arrays; sharing an immutable input does not share matcher state.
 * The anchor definitions, sample budget, scoring math and classifier are the full core's originals.
 * A single T2 worker serializes process calls to this instance.
 */
class LabyrinthFastFrameObserver(
    private val templates: LabyrinthEntryTemplateSet,
    private val preScaledTemplates: PreScaledEntryTemplates? = null,
) {
    private val matcher = GradientTemplateMatcher()
    private val classifier = LabyrinthEntryPageClassifier()
    private val battleFailureDetector = LabyrinthBattleFailureDetector()
    private val battleEndConfirmationDetector = LabyrinthBattleEndConfirmationDetector()
    private val specialEquipmentRecognizer = LabyrinthSpecialEquipmentRecognizer()
    private val exChallengeDetector = LabyrinthExChallengeDetector()
    private val nodeMoveConfirmationDetector = LabyrinthNodeMoveConfirmationDetector()

    fun process(
        frame: PixelImage,
        frameSequence: Long = 0L,
        timestampMillis: Long = 0L,
    ): LabyrinthFastFrameObservation {
        val started = System.nanoTime()
        if (frame.width <= frame.height) {
            val scores = LabyrinthAnchorScores(EntryAnchorId.required.associateWith { 0.0 })
            return LabyrinthFastFrameObservation(
                observation = LabyrinthEntryPageObservation(
                    state = LabyrinthEntryPageState.UNKNOWN,
                    confidence = 0.0,
                    stateScores = emptyMap(),
                    anchorScores = scores,
                    reason = "entry-frame-not-landscape",
                ),
                frameSequence = frameSequence,
                timestampMillis = timestampMillis,
                width = frame.width,
                height = frame.height,
                elapsedMillis = 0L,
                anchorScores = scores,
            )
        }
        matcher.prepareFrame(frame)
        try {
            val measurements = measureLabyrinthEntryAnchors(frame, templates, matcher, preScaledTemplates)
            val scores = LabyrinthAnchorScores(measurements.mapValues { (_, match) -> match?.score ?: 0.0 })
            val classified = classifier.classify(scores)
            val failure = battleFailureDetector.detect(frame)
            val endConfirmation = if (failure != null || classified.state == LabyrinthEntryPageState.UNKNOWN) {
                battleEndConfirmationDetector.detect(frame)
            } else null
            // Exactly the full core's failure override; no alternative classifier thresholds.
            val observation = failure?.let {
                classified.copy(
                    state = LabyrinthEntryPageState.BATTLE_FAILED,
                    confidence = it.confidence,
                    stateScores = classified.stateScores + (LabyrinthEntryPageState.BATTLE_FAILED to it.confidence),
                    reason = null,
                )
            } ?: classified
            val equipment = specialEquipmentRecognizer.recognize(frame, scores,
                observation.state == LabyrinthEntryPageState.BATTLE_TEAM_SELECTION)
            val challenge = exChallengeDetector.detect(frame, observation.state)
            val move = nodeMoveConfirmationDetector.detect(frame)
            return LabyrinthFastFrameObservation(
                observation = observation,
                frameSequence = frameSequence,
                timestampMillis = timestampMillis,
                width = frame.width,
                height = frame.height,
                elapsedMillis = (System.nanoTime() - started) / 1_000_000,
                anchorScores = scores,
                anchorMatches = measurements.mapNotNull { (id, match) -> match?.let { id to it } }.toMap(),
                nodeMoveConfirmation = move,
                battleFailure = failure,
                battleEndConfirmation = endConfirmation,
                specialEquipment = equipment,
                exChallenge = challenge,
            )
        } finally {
            matcher.clearPreparedFrame()
        }
    }
}

/** Pure shape-checked boundary used by Android fast/full adapters; snapshot is borrowed read-only. */
internal fun labyrinthFramePixelImage(
    width: Int,
    height: Int,
    argbPixels: IntArray?,
    legacyCopy: () -> PixelImage,
): PixelImage {
    require(width > 0 && height > 0) { "Invalid captured frame dimensions: ${width}x$height" }
    val count = width.toLong() * height
    require(count <= Int.MAX_VALUE) { "Captured frame is too large: ${width}x$height" }
    if (argbPixels != null) {
        require(argbPixels.size.toLong() == count) {
            "Captured frame snapshot length mismatch: expected=$count actual=${argbPixels.size}"
        }
        return PixelImage(width, height, argbPixels)
    }
    return legacyCopy().also {
        require(it.width == width && it.height == height && it.pixels.size.toLong() == count) {
            "Legacy captured frame pixel shape mismatch"
        }
    }
}
