package com.landosol.toolbox.labyrinth.vision

import com.landosol.toolbox.clanbattle.recognition.PixelImage

data class LabyrinthExChallengeObservation(
    val infoButtonRects: List<EntryPixelRect>,
    val infoButtonScores: List<Double>,
    val multiMonsterLikely: Boolean,
    val slot3InfoButtonRect: EntryPixelRect?,
    val specialDualLikely: Boolean = false,
    val specialDualIdentityInfoButtonRect: EntryPixelRect? = null,
    val confidence: Double,
)

data class LabyrinthExEncounterObservation(
    /** OCR text from the challenge header/body, used for 极难 and single-EX identity. */
    val challengeText: String? = null,
    /** Dedicated title OCR. Non-null text alone is not enough; challengeDifficultyResolved below
     * requires the stable "战斗格子" title itself so unrelated OCR noise can never release a tap. */
    val challengeTitleText: String? = null,
    val challengeDifficultyResolved: Boolean = false,
    val extremeChallenge: Boolean = false,
    /** True only after the fixed five-slot structure is visually confirmed. */
    val multiMonsterLikely: Boolean = false,
    val slot3InfoButtonRect: EntryPixelRect? = null,
    /** Special EX: unknown relic on the left and the identifying enemy on the right. */
    val specialDualLikely: Boolean = false,
    val specialDualIdentityInfoButtonRect: EntryPixelRect? = null,
    /** Stable monster-detail modal evidence. */
    val monsterDetailOpen: Boolean = false,
    val monsterDetailText: String? = null,
    val encounterId: String? = null,
    val encounterName: String? = null,
    val trusted: Boolean = false,
    val closeButtonRect: EntryPixelRect? = null,
    val evidenceId: Long? = null,
)

enum class LabyrinthChallengeDifficulty {
    NORMAL,
    EXTREME,
    UNKNOWN,
}

/**
 * The two suffix templates occupy the same title ROI.  A winner must clear both an absolute
 * safety line and a margin; a miss is UNKNOWN rather than implicitly NORMAL.
 */
object LabyrinthChallengeDifficultyResolver {
    fun resolve(
        normalScore: Double,
        extremeScore: Double,
        extremeAlternativeScore: Double = 0.0,
        minimumScore: Double = 0.78,
        minimumMargin: Double = 0.10,
    ): LabyrinthChallengeDifficulty {
        // Either calibrated 极难 template may match the current banner style; the stronger one wins.
        val resolvedExtreme = maxOf(extremeScore, extremeAlternativeScore)
        val best = maxOf(normalScore, resolvedExtreme)
        val margin = kotlin.math.abs(normalScore - resolvedExtreme)
        if (best < minimumScore || margin < minimumMargin) return LabyrinthChallengeDifficulty.UNKNOWN
        return if (resolvedExtreme > normalScore) {
            LabyrinthChallengeDifficulty.EXTREME
        } else {
            LabyrinthChallengeDifficulty.NORMAL
        }
    }
}

/**
 * Read-only detector for fixed monster-info circles on an EX challenge page.
 *
 * The monsters themselves animate; the small circular `i` controls do not.  We therefore never
 * fingerprint the monster artwork. A five-monster page requires at least four of five fixed
 * circles. A special EX instead has two fixed circles near the centre; both must pass and the
 * five-monster structure must be absent before the right-side identity rect is exposed.
 */
class LabyrinthExChallengeDetector(
    private val minimumInfoScore: Double = 0.34,
    private val requiredInfoButtons: Int = 4,
) {
    fun detect(frame: PixelImage, pageState: LabyrinthEntryPageState): LabyrinthExChallengeObservation? {
        if (pageState != LabyrinthEntryPageState.BATTLE_CHALLENGE || frame.width <= frame.height) return null
        val scored = INFO_BUTTON_RECTS.mapNotNull { reference ->
            val rect = ReferenceFitMapper.map(frame.width, frame.height, STANDARD_REFERENCE, reference)
                ?: return@mapNotNull null
            rect to infoCircleScore(frame, rect)
        }
        if (scored.size != INFO_BUTTON_RECTS.size) return null
        val passed = scored.map { it.second >= minimumInfoScore }
        val count = passed.count { it }
        val multi = count >= requiredInfoButtons
        val slot3 = scored.getOrNull(2)?.takeIf { passed.getOrNull(2) == true }?.first

        // Special dual-target EX has shipped with two slightly different vertical layouts.
        // Score every calibrated pair and keep the strongest complete pair.  This avoids falling
        // through to the single-target fixed Details tap when 美空/黄蜂女王 uses the lower layout.
        val specialScored = SPECIAL_DUAL_INFO_BUTTON_RECT_VARIANTS
            .mapNotNull { pair ->
                val scoredPair = pair.mapNotNull inner@ { reference ->
                    val rect = ReferenceFitMapper.map(frame.width, frame.height, STANDARD_REFERENCE, reference)
                        ?: return@inner null
                    rect to infoCircleScore(frame, rect)
                }
                scoredPair.takeIf { it.size == pair.size }
            }
            .maxByOrNull { scoredPair -> scoredPair.map { it.second }.average() }
            .orEmpty()
        val specialPassed = specialScored.size == 2 &&
            specialScored.all { (_, score) -> score >= minimumInfoScore }
        val specialDual = !multi && specialPassed
        val specialIdentity = specialScored.getOrNull(1)
            ?.takeIf { specialDual }
            ?.first
        val structuralConfidence = if (specialDual) {
            specialScored.map { it.second }.average()
        } else {
            scored.map(Pair<EntryPixelRect, Double>::second).sortedDescending()
                .take(requiredInfoButtons).average()
        }
        return LabyrinthExChallengeObservation(
            infoButtonRects = scored.map(Pair<EntryPixelRect, Double>::first),
            infoButtonScores = scored.map(Pair<EntryPixelRect, Double>::second),
            multiMonsterLikely = multi,
            slot3InfoButtonRect = slot3,
            specialDualLikely = specialDual,
            specialDualIdentityInfoButtonRect = specialIdentity,
            confidence = structuralConfidence,
        )
    }

    private fun infoCircleScore(frame: PixelImage, rect: EntryPixelRect): Double {
        var bright = 0
        var cyan = 0
        var samples = 0
        var y = rect.top
        while (y < rect.top + rect.height) {
            var x = rect.left
            while (x < rect.left + rect.width) {
                val color = frame[x, y]
                val red = color shr 16 and 0xff
                val green = color shr 8 and 0xff
                val blue = color and 0xff
                if (red >= 180 && green >= 190 && blue >= 195) bright++
                if (blue >= 150 && blue - red >= 20 && blue - green >= 5) cyan++
                samples++
                x += SAMPLE_STEP
            }
            y += SAMPLE_STEP
        }
        if (samples == 0) return 0.0
        // The stable circle consists of a large pale centre plus a cyan/blue ring.  Cap each
        // channel before summing so bright/blue monster artwork alone cannot dominate one slot.
        val brightCoverage = bright.toDouble() / samples
        val cyanCoverage = cyan.toDouble() / samples
        return (brightCoverage.coerceAtMost(0.55) * 0.65 + cyanCoverage.coerceAtMost(0.50) * 0.35) / 0.5325
    }

    private companion object {
        val STANDARD_REFERENCE = EntryReferenceSize(1920, 1080)
        // Current 1920x1080 EX challenge layout.  Centres are stable even while monsters animate.
        val INFO_BUTTON_RECTS = listOf(
            EntryReferenceRect(395, 599, 70, 70),
            EntryReferenceRect(697, 599, 70, 70),
            EntryReferenceRect(995, 599, 70, 70),
            EntryReferenceRect(1295, 599, 70, 70),
            EntryReferenceRect(1595, 599, 70, 70),
        )
        // Screenshot-derived special-EX pair. ReferenceFitMapper keeps these proportional across
        // actual device resolutions; the social/video screenshots used for calibration may be
        // cropped, but the in-game 16:9 viewport uses this same centred geometry.
        val SPECIAL_DUAL_INFO_BUTTON_RECT_VARIANTS = listOf(
            // Older/current-alt layout: centres approximately (884, 598), (1166, 598).
            listOf(
                EntryReferenceRect(856, 570, 56, 56),
                EntryReferenceRect(1138, 570, 56, 56),
            ),
            // 2026-09 live 美空/黄蜂女王 layout: the two Details circles sit ~34 px lower;
            // the right identity target is also ~14 px farther right.
            listOf(
                EntryReferenceRect(852, 604, 56, 56),
                EntryReferenceRect(1152, 604, 56, 56),
            ),
        )
        const val SAMPLE_STEP = 3
    }
}

/** Pure text helper kept Android-free so noisy OCR variants are unit-testable. */
object LabyrinthExEncounterText {
    fun isChallengeTitle(text: String?): Boolean {
        val normalized = normalize(text ?: return false)
        return normalized.contains("战斗格子") || normalized.contains("戰鬥格子")
    }

    fun isExtremeChallenge(text: String?): Boolean {
        val normalized = normalize(text ?: return false)
        return normalized.contains("极难") || normalized.contains("極難")
    }

    private fun normalize(value: String): String = value
        .replace(" ", "")
        .replace("\n", "")
        .replace('（', '(')
        .replace('）', ')')
}
