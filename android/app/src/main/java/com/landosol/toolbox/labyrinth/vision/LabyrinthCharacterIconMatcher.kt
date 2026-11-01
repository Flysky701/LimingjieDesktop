package com.landosol.toolbox.labyrinth.vision

import com.landosol.toolbox.clanbattle.recognition.PixelImage
import com.landosol.toolbox.labyrinth.LabyrinthCharacterAttribute
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Worker threads for the two scoring passes.
 *
 * Deliberately not the CPU count. The game shares the same virtual machine, and the formation
 * search in this project already measured 4 threads at 0.58x wall clock while 8 threads made the
 * whole run slower, so 4 is the measured sweet spot for this kind of per-frame number crunching.
 */
private const val ICON_SCORING_THREADS = 4

/** Below this many candidates the thread hand-off costs more than the scoring it saves. */
private const val ICON_SCORING_MIN_CANDIDATES = 128

/** No shard should carry fewer than this, otherwise the per-shard fixed cost dominates. */
private const val ICON_SCORING_MIN_PER_SHARD = 64

/** One process-wide pool: a run calls [LabyrinthCharacterIconMatcher.match] tens of thousands of times. */
private val ICON_SCORING_EXECUTOR: ExecutorService =
    Executors.newFixedThreadPool(ICON_SCORING_THREADS) { runnable ->
        Thread(runnable, "labyrinth-icon-score").apply { isDaemon = true }
    }

// Three RGB planes on the coarse grid, plus luminance and gradient on the fine grid.
private typealias IconSampleGrid = Array<ShortArray>

/** Shared safety floor for every page that identifies a character from the full icon pack. */
const val LABYRINTH_CHARACTER_ICON_SAFE_CONFIDENCE = 0.35

/** Shared separation required between the best character and the best different character. */
const val LABYRINTH_CHARACTER_ICON_SAFE_RIVAL_MARGIN = 0.045

data class LabyrinthCharacterIconMask(
    val ignoreRightEdge: Boolean = false,
    val ignoreTopLeft: Boolean = false,
    val ignoreTopRight: Boolean = false,
    val ignoreBottomLeft: Boolean = false,
    val ignoreBottomBand: Boolean = false,
    val minYRatio: Double = 0.0,
    val maxYRatio: Double = 1.0,
)

data class LabyrinthCharacterIconCandidate(
    val characterId: String,
    val displayName: String,
    val iconVariant: String,
    val confidence: Double,
    val aliases: List<String> = emptyList(),
)

data class LabyrinthCharacterIconMatch(
    val characterId: String?,
    val displayName: String?,
    val iconVariant: String?,
    val confidence: Double,
    val rivalConfidence: Double,
    val rivalMargin: Double,
    val trusted: Boolean,
    val suspectedCharacterId: String?,
    val suspectedDisplayName: String?,
    val candidates: List<LabyrinthCharacterIconCandidate> = emptyList(),
)

/**
 * Matches the stable centre of an observed square character icon against the complete icon pack.
 *
 * All role-related pages provide only the full icon rectangle. Page-specific card frames, labels,
 * buttons and list geometry stay outside this class. Different star variants of one character do
 * not compete with each other when the rival margin is calculated.
 */
class LabyrinthCharacterIconMatcher internal constructor(
    templates: Sequence<LabyrinthBattleCharacterTemplate>,
    private val minimumConfidence: Double = LABYRINTH_CHARACTER_ICON_SAFE_CONFIDENCE,
    private val minimumRivalMargin: Double = LABYRINTH_CHARACTER_ICON_SAFE_RIVAL_MARGIN,
) {
    constructor(
        templates: List<LabyrinthBattleCharacterTemplate> = emptyList(),
        minimumConfidence: Double = LABYRINTH_CHARACTER_ICON_SAFE_CONFIDENCE,
        minimumRivalMargin: Double = LABYRINTH_CHARACTER_ICON_SAFE_RIVAL_MARGIN,
    ) : this(templates.asSequence(), minimumConfidence, minimumRivalMargin)

    constructor() : this(emptyList())

    init {
        require(minimumConfidence in 0.0..1.0)
        require(minimumRivalMargin in 0.0..1.0)
    }

    private class SampledTemplate(
        val character: LabyrinthCharacterNameCandidate,
        val iconVariant: String,
        val attribute: LabyrinthCharacterAttribute?,
        val width: Int,
        val height: Int,
        val coarse: IconSampleGrid,
        val fine: IconSampleGrid,
    )

    /** Keep the scoring grids, never the decoded icon or the sequence that produced it. */
    private val pack = templates.map { template ->
        val image = template.image
        val crop = EntryPixelRect(0, ICON_CROP_TOP, image.width, ICON_CROP_HEIGHT)
        SampledTemplate(
            LabyrinthCharacterNameCandidate(template.characterId, template.displayName, template.aliases),
            template.iconVariant, template.attribute, image.width, image.height,
            sampleGrid(image, crop, COARSE_SAMPLE_WIDTH, COARSE_SAMPLE_HEIGHT),
            sampleGrid(image, crop, SCORE_SAMPLE_WIDTH, SCORE_SAMPLE_HEIGHT, withLuminanceAndGradient = true),
        )
    }.toList()

    // Legacy callers may pass a short icon for an attribute that is never selected. Preserve the
    // original failure at match time only if that icon survives the attribute filter.
    private val hasShortTemplate = pack.any { it.coarse.isEmpty() }

    internal fun characterNames() = pack.map { it.character }.distinctBy { it.characterId }

    /** The cells one mask keeps, in the row-major order the scoring loops visit them. */
    private class GridMask(val indices: IntArray, val count: Int)

    /**
     * @param rosterCharacterIds the characters that can actually appear here, or null for any.
     *
     * A labyrinth run's team pages can only show roles that joined that run: roughly 30 of the
     * ~800 icons in the pack. The other 770 are not merely unlikely, they are impossible, and
     * letting them compete costs real identifications — an impossible rival that scores close to
     * the true role eats the margin gate and the slot comes back unidentified, which blocks the
     * whole team plan (2026-09-20 bundle 204642: 成员2 unresolved for 162 consecutive frames while
     * 祈梨（怪盗） was sitting in the run's own joined list).
     *
     * The roster narrows which candidates may *win* and may *veto*; it never lowers the absolute
     * confidence bar, and [ROSTER_OUTSIDER_OVERRIDE_MARGIN] still hands the frame back to the full
     * pack when the best icon overall is clearly outside the roster — that means the roster itself
     * is incomplete, and a confident wrong answer would be worse than an unidentified slot.
     */
    fun match(
        frame: PixelImage,
        iconRect: EntryPixelRect,
        mask: LabyrinthCharacterIconMask = LabyrinthCharacterIconMask(),
        requiredAttribute: LabyrinthCharacterAttribute? = null,
        rosterCharacterIds: Set<String>? = null,
    ): LabyrinthCharacterIconMatch {
        val candidateTemplates = if (requiredAttribute == null) {
            pack
        } else {
            // Attribute is auxiliary evidence from the visible card badge. Keep templates whose
            // attribute metadata is still unknown so incomplete data never makes a real role
            // impossible to recognize, while known mismatched attributes stop competing.
            pack.filter { template ->
                template.attribute == null || template.attribute == requiredAttribute
            }
        }
        if (hasShortTemplate && candidateTemplates.any { it.coarse.isEmpty() }) {
            throw ArrayIndexOutOfBoundsException("Character icon does not cover template rows 20..99")
        }
        // Sample the frame side once for this call. Every candidate shares the same comparison
        // rectangle (the pack ships icons of a single size), so these grids are shared across all
        // candidates. They stay locals, so the whole-frame PixelImage is released as soon as match()
        // returns instead of being retained in a field. The mask is not applied here: the grid is
        // stored complete so it can serve any mask, and the GridMask below decides what is read.
        val observedTarget = candidateTemplates.firstOrNull()
            ?.let { comparisonRect(frame, iconRect, it) }
        val coarseMask = maskOf(mask, COARSE_SAMPLE_WIDTH, COARSE_SAMPLE_HEIGHT)
        val scoreMask = maskOf(mask, SCORE_SAMPLE_WIDTH, SCORE_SAMPLE_HEIGHT)
        val coarseObserved = observedTarget?.let {
            center(sampleGrid(frame, it, COARSE_SAMPLE_WIDTH, COARSE_SAMPLE_HEIGHT), coarseMask)
        }
        val scoreObserved = observedTarget?.let {
            center(sampleGrid(frame, it, SCORE_SAMPLE_WIDTH, SCORE_SAMPLE_HEIGHT, withLuminanceAndGradient = true), scoreMask)
        }
        val coarseScores = scoreAll(candidateTemplates.size) { index ->
            coarseIconScore(candidateTemplates[index].coarse, coarseObserved, coarseMask)
        }
        val coarse = candidateTemplates.indices
            .map { index -> candidateTemplates[index] to coarseScores[index] }
            .sortedByDescending { (_, score) -> score }
        // The coarse pass is cheap and still sees the whole pack, so it can answer whether the
        // roster is trustworthy before the expensive pass commits to it.
        val roster = rosterCharacterIds?.takeIf(Set<String>::isNotEmpty)
        val insideRoster = if (roster == null) coarse else coarse.filter { (template, _) ->
            template.character.characterId in roster
        }
        val rosterUsable = roster != null && insideRoster.isNotEmpty() &&
            (coarse.firstOrNull()?.second ?: 0.0) - (insideRoster.firstOrNull()?.second ?: 0.0) <
            ROSTER_OUTSIDER_OVERRIDE_MARGIN
        val considered = if (rosterUsable) insideRoster else coarse
        val coarseCutoff = (considered.firstOrNull()?.second ?: 0.0) * COARSE_KEEP_RATIO
        val finalists = considered
            .filterIndexed { index, (_, score) -> index < MIN_ICON_CANDIDATES || score >= coarseCutoff }
            .take(MAX_ICON_CANDIDATES)
        val finalScores = scoreAll(finalists.size) { index ->
            iconScore(finalists[index].first.fine, scoreObserved, scoreMask)
        }
        val ranked = finalists.indices
            .map { index -> finalists[index].first to finalScores[index] }
            .sortedByDescending { (_, score) -> score }
        // Keep only the strongest icon variant for each logical character. A character can have
        // multiple star/rarity icon variants and those variants must never compete with each other
        // when we calculate the rival margin or expose the short list to the name verifier.
        val characterCandidates = ranked
            .groupBy { (template, _) -> template.character.characterId }
            .mapNotNull { (_, variants) ->
                variants.maxByOrNull { (_, score) -> score }?.let { (template, score) ->
                    LabyrinthCharacterIconCandidate(
                        characterId = template.character.characterId,
                        displayName = template.character.displayName,
                        iconVariant = template.iconVariant,
                        confidence = score,
                        aliases = template.character.aliases,
                    )
                }
            }
            .sortedByDescending(LabyrinthCharacterIconCandidate::confidence)
        val best = characterCandidates.firstOrNull()
        val rivalScore = characterCandidates.getOrNull(1)?.confidence ?: 0.0
        val bestScore = best?.confidence ?: 0.0
        val rivalMargin = bestScore - rivalScore
        val trusted = best != null &&
            bestScore >= minimumConfidence &&
            rivalMargin >= minimumRivalMargin
        val trustedTemplate = best?.takeIf { trusted }
        return LabyrinthCharacterIconMatch(
            characterId = trustedTemplate?.characterId,
            displayName = trustedTemplate?.displayName,
            iconVariant = trustedTemplate?.iconVariant,
            confidence = bestScore,
            rivalConfidence = rivalScore,
            rivalMargin = rivalMargin,
            trusted = trusted,
            suspectedCharacterId = best?.characterId,
            suspectedDisplayName = best?.displayName,
            candidates = characterCandidates.take(MAX_EXPOSED_CHARACTER_CANDIDATES),
        )
    }

    /**
     * Only failed real-card matches pay for a small bounded geometry calibration.
     *
     * A detected card border can be off by a few pixels after scaling/scroll settling.  Trying
     * only symmetric inset/expand crops misses pure X/Y translations, which is especially visible
     * on the long effective-effect roster where one otherwise valid card can block the complete
     * scan.  Every alternative still has to pass the same global confidence and rival-margin
     * floors in [reconcileGeometryMatches]; this is geometry recovery, not a relaxed identity
     * threshold.
     */
    internal fun refineMemberGeometry(
        frame: PixelImage,
        iconRect: EntryPixelRect,
        initial: LabyrinthCharacterIconMatch,
        mask: LabyrinthCharacterIconMask = LabyrinthCharacterIconMask(
            ignoreRightEdge = true,
            ignoreTopLeft = true,
        ),
        requiredAttribute: LabyrinthCharacterAttribute? = null,
        force: Boolean = false,
        rosterCharacterIds: Set<String>? = null,
    ): LabyrinthCharacterIconMatch {
        if (initial.trusted && !force) return initial
        val radius = (iconRect.width / 90.0).roundToInt().coerceIn(1, 3)
        val deltas = listOf(-radius, -1, 1, radius).distinct()
        val candidateRects = buildList {
            // Border thickness / size calibration.
            deltas.forEach { inset ->
                add(
                    iconRect.copy(
                        left = iconRect.left + inset,
                        top = iconRect.top + inset,
                        width = iconRect.width - inset * 2,
                        height = iconRect.height - inset * 2,
                    ),
                )
            }
            // Pure translation calibration.  These four directions cover the common measured
            // border error without turning recovery into an unbounded local image search.
            deltas.forEach { delta ->
                add(iconRect.copy(left = iconRect.left + delta))
                add(iconRect.copy(top = iconRect.top + delta))
            }
        }.distinct()
        val alternatives = candidateRects.map { rect ->
            match(
                frame = frame,
                iconRect = rect,
                mask = mask,
                requiredAttribute = requiredAttribute,
                rosterCharacterIds = rosterCharacterIds,
            )
        }
        return reconcileGeometryMatches(initial, alternatives, force = force)
    }

    internal fun reconcileGeometryMatches(
        initial: LabyrinthCharacterIconMatch,
        alternatives: List<LabyrinthCharacterIconMatch>,
        force: Boolean = false,
    ): LabyrinthCharacterIconMatch {
        if (initial.trusted && !force) return initial
        // A rival from ANY crop must compete with the winner. Do not accept the first crop that
        // happens to clear the threshold, or compare star variants as different characters.
        val candidates = (listOf(initial) + alternatives).flatMap { it.candidates }
            .groupBy { it.characterId }.values.map { variants -> variants.maxBy { it.confidence } }
            .sortedByDescending { it.confidence }
        val best = candidates.firstOrNull() ?: return initial
        val rival = candidates.getOrNull(1)?.confidence ?: 0.0
        if (best.confidence < minimumConfidence || best.confidence - rival < minimumRivalMargin) return initial
        return LabyrinthCharacterIconMatch(
            characterId = best.characterId, displayName = best.displayName, iconVariant = best.iconVariant,
            confidence = best.confidence, rivalConfidence = rival, rivalMargin = best.confidence - rival,
            trusted = true, suspectedCharacterId = best.characterId, suspectedDisplayName = best.displayName,
            candidates = candidates.take(MAX_EXPOSED_CHARACTER_CANDIDATES),
        )
    }

    /**
     * Coarse-pass score for one pack icon.
     *
     * Both sides are already resolved when this runs, so the body only reads: no scratch buffer, no
     * cache lookup and no writes to anything another worker could see.
     */
    private fun coarseIconScore(
        pack: IconSampleGrid,
        observed: Array<CenteredChannel>?,
        mask: GridMask,
    ): Double {
        // observed is null only when the comparison rectangle does not exist for this frame and
        // rectangle (the pack ships icons of a single size, so the rectangle is shared by every
        // candidate and was sampled once up in [match]).
        if (observed == null) return 0.0
        val count = mask.count
        if (count < 8) return 0.0
        return colorScore(observed, pack, mask)
    }

    /** Scoring-pass score for one pack icon; read-only for the same reason as [coarseIconScore]. */
    private fun iconScore(
        pack: IconSampleGrid,
        observed: Array<CenteredChannel>?,
        mask: GridMask,
    ): Double {
        // observed is null only when the comparison rectangle does not exist for this frame and
        // rectangle (the pack ships icons of a single size, so the rectangle is shared by every
        // candidate and was sampled once up in [match]).
        if (observed == null) return 0.0
        val count = mask.count
        if (count < 20) return 0.0
        val colorScore = colorScore(observed, pack, mask)
        val luminanceScore = correlation(observed[LUMINANCE], pack[LUMINANCE], mask).coerceAtLeast(0.0)
        val gradientScore = correlation(observed[GRADIENT], pack[GRADIENT], mask).coerceAtLeast(0.0)
        return (colorScore * 0.55 + luminanceScore * 0.25 + gradientScore * 0.20).coerceIn(0.0, 1.0)
    }

    /**
     * Which grid cells [mask] keeps, in the row-major order the scoring loops visit them.
     *
     * Rebuilt per call on purpose. The mask carries the clip ratios of the card that produced it,
     * so caching by mask would grow with the number of distinct clip positions a run sees, while a
     * rebuild is only a few hundred comparisons.
     */
    private fun maskOf(
        mask: LabyrinthCharacterIconMask,
        sampleWidth: Int,
        sampleHeight: Int,
    ): GridMask {
        val indices = IntArray(sampleWidth * sampleHeight)
        var count = 0
        repeat(sampleHeight) { sampleY ->
            val yRatio = sampleY.toDouble() / (sampleHeight - 1)
            for (sampleX in 0 until sampleWidth) {
                val xRatio = sampleX.toDouble() / (sampleWidth - 1)
                if (isMasked(xRatio, yRatio, mask)) continue
                indices[count] = sampleY * sampleWidth + sampleX
                count++
            }
        }
        return GridMask(indices, count)
    }

    /**
     * Samples the same grid from a template crop or an observed frame.
     *
     * The mask is deliberately not applied here: the grid is stored complete so the same samples
     * can serve any mask, and [GridMask] then decides which cells a correlation actually reads.
     */
    private fun sampleGrid(
        frame: PixelImage,
        target: EntryPixelRect,
        sampleWidth: Int,
        sampleHeight: Int,
        withLuminanceAndGradient: Boolean = false,
    ): IconSampleGrid {
        if (target.top + target.height > frame.height) return emptyArray()
        // Colour/luminance range from 0..255 and the gradient from 0..510.
        val samples = Array(if (withLuminanceAndGradient) 5 else 3) { ShortArray(sampleWidth * sampleHeight) }
        var grid = 0
        repeat(sampleHeight) { sampleY ->
            val yRatio = sampleY.toDouble() / (sampleHeight - 1)
            for (sampleX in 0 until sampleWidth) {
                val xRatio = sampleX.toDouble() / (sampleWidth - 1)
                val frameX = target.left + (xRatio * (target.width - 1)).roundToInt()
                val frameY = target.top + (yRatio * (target.height - 1)).roundToInt()
                val pixel = frame[frameX, frameY]
                for (channel in 0..2) {
                    samples[channel][grid] = channel(pixel, channel).toShort()
                }
                if (withLuminanceAndGradient) {
                    samples[LUMINANCE][grid] = luminance(pixel).toShort()
                    samples[GRADIENT][grid] = localGradient(frame, frameX, frameY, target).toShort()
                }
                grid++
            }
        }
        return samples
    }

    private fun comparisonRect(
        frame: PixelImage,
        iconRect: EntryPixelRect,
        template: SampledTemplate,
    ): EntryPixelRect? {
        if (template.width < 3 || template.height < ICON_CROP_TOP + ICON_CROP_HEIGHT) return null
        val top = iconRect.top +
            (iconRect.height * ICON_CROP_TOP.toDouble() / template.height).roundToInt()
        val height =
            (iconRect.height * ICON_CROP_HEIGHT.toDouble() / template.height).roundToInt()
                .coerceAtLeast(16)
        val target = EntryPixelRect(iconRect.left, top, iconRect.width, height)
        return target.takeIf {
            it.left >= 0 && it.top >= 0 &&
                it.left + it.width <= frame.width && it.top + it.height <= frame.height
        }
    }

    private fun isMasked(xRatio: Double, yRatio: Double, mask: LabyrinthCharacterIconMask): Boolean =
        yRatio < mask.minYRatio ||
            yRatio > mask.maxYRatio ||
            (mask.ignoreRightEdge && xRatio > RIGHT_EDGE_MAX_X_RATIO) ||
            (mask.ignoreTopLeft &&
                xRatio <= TOP_LEFT_OVERLAY_IGNORE_X_RATIO &&
                yRatio <= TOP_LEFT_OVERLAY_IGNORE_Y_RATIO) ||
            (mask.ignoreTopRight &&
                xRatio >= TOP_RIGHT_OVERLAY_IGNORE_X_RATIO &&
                yRatio <= TOP_RIGHT_OVERLAY_IGNORE_Y_RATIO) ||
            (mask.ignoreBottomLeft &&
                xRatio <= BOTTOM_LEFT_OVERLAY_IGNORE_X_RATIO &&
                yRatio >= BOTTOM_LEFT_OVERLAY_IGNORE_Y_RATIO) ||
            (mask.ignoreBottomBand && yRatio >= BOTTOM_BAND_OVERLAY_IGNORE_Y_RATIO)

    private fun localGradient(image: PixelImage, x: Int, y: Int, bounds: EntryPixelRect): Int {
        val right = (x + 1).coerceAtMost(bounds.left + bounds.width - 1)
        val bottom = (y + 1).coerceAtMost(bounds.top + bounds.height - 1)
        val previousX = (x - 1).coerceAtLeast(bounds.left)
        val previousY = (y - 1).coerceAtLeast(bounds.top)
        return kotlin.math.abs(luminance(image[right, y]) - luminance(image[previousX, y])) +
            kotlin.math.abs(luminance(image[x, bottom]) - luminance(image[x, previousY]))
    }

    private class CenteredChannel(val deltas: DoubleArray, val square: Double)

    // All candidates use the same observed values and mask. Center them once, retaining the
    // original addition order for the square and later dot products.
    private fun center(grid: IconSampleGrid, mask: GridMask): Array<CenteredChannel> =
        Array(grid.size) { channel ->
            val values = grid[channel]
            var sum = 0
            for (i in 0 until mask.count) sum += values[mask.indices[i]]
            val mean = sum.toDouble() / mask.count.coerceAtLeast(1)
            var square = 0.0
            val deltas = DoubleArray(mask.count) { i ->
                val delta = values[mask.indices[i]] - mean
                square += delta * delta
                delta
            }
            CenteredChannel(deltas, square)
        }

    private fun colorScore(observed: Array<CenteredChannel>, pack: IconSampleGrid, mask: GridMask): Double {
        var sum = 0.0
        for (channel in 0..2) sum += correlation(observed[channel], pack[channel], mask)
        return (sum / 3).coerceAtLeast(0.0)
    }

    /** Pearson correlation over the cells retained by the mask, in row-major order. */
    private fun correlation(observed: CenteredChannel, pack: ShortArray, mask: GridMask): Double {
        var sum = 0
        for (i in 0 until mask.count) sum += pack[mask.indices[i]]
        // At most 240 integral values <= 510: summing in Int gives exactly the previous Double sum.
        val mean = sum.toDouble() / mask.count
        var numerator = 0.0
        var square = 0.0
        for (i in 0 until mask.count) {
            val delta = pack[mask.indices[i]] - mean
            numerator += observed.deltas[i] * delta
            square += delta * delta
        }
        val denominator = sqrt(observed.square * square)
        return if (denominator <= 1e-9) 0.0 else numerator / denominator
    }

    /**
     * Scores [size] candidates with [score], on the scoring pool when there is enough work.
     *
     * Determinism does not depend on how the work is split: every candidate is scored entirely by
     * one shard, from values it only reads, and the shards are collected in submission order, so
     * the returned array is identical for any shard count. That is also why the shard count may
     * vary with the candidate count.
     */
    private fun scoreAll(size: Int, score: (Int) -> Double): DoubleArray {
        val scores = DoubleArray(size)
        val shards = if (size < ICON_SCORING_MIN_CANDIDATES) {
            1
        } else {
            minOf(ICON_SCORING_THREADS, size / ICON_SCORING_MIN_PER_SHARD).coerceAtLeast(1)
        }
        if (shards <= 1) {
            for (index in 0 until size) scores[index] = score(index)
            return scores
        }
        val perShard = (size + shards - 1) / shards
        val tasks = (0 until shards).map { shard ->
            val from = shard * perShard
            val to = minOf(size, from + perShard)
            Callable<DoubleArray> {
                val part = DoubleArray(to - from)
                for (index in from until to) part[index - from] = score(index)
                part
            }
        }
        var at = 0
        for (future in ICON_SCORING_EXECUTOR.invokeAll(tasks)) {
            val part = future.get()
            System.arraycopy(part, 0, scores, at, part.size)
            at += part.size
        }
        return scores
    }

    private fun channel(color: Int, channel: Int): Int = when (channel) {
        0 -> color ushr 16 and 0xff
        1 -> color ushr 8 and 0xff
        else -> color and 0xff
    }

    private fun luminance(color: Int): Int =
        (channel(color, 0) * 299 + channel(color, 1) * 587 + channel(color, 2) * 114 + 500) / 1000

    private companion object {
        const val LUMINANCE = 3
        const val GRADIENT = 4
        const val ICON_CROP_TOP = 20
        const val ICON_CROP_HEIGHT = 80

        const val COARSE_SAMPLE_WIDTH = 12
        const val COARSE_SAMPLE_HEIGHT = 9
        const val SCORE_SAMPLE_WIDTH = 20
        const val SCORE_SAMPLE_HEIGHT = 12

        const val MAX_ICON_CANDIDATES = 512
        const val MIN_ICON_CANDIDATES = 64
        const val MAX_EXPOSED_CHARACTER_CANDIDATES = 5
        const val COARSE_KEEP_RATIO = 0.5
        /**
         * How far the best icon in the whole pack may beat the best roster icon before the roster
         * is treated as incomplete and ignored for this slot.
         *
         * Coarse scores for the true role sit well above its rivals, so a gap this large means the
         * face on screen is not in the roster at all — a run whose joined list missed a character,
         * or a page that is not a labyrinth team page. Trusting the roster there would convert an
         * honest "unidentified" into a confident wrong role, which the planner would act on.
         */
        const val ROSTER_OUTSIDER_OVERRIDE_MARGIN = 0.08
        const val RIGHT_EDGE_MAX_X_RATIO = 0.82
        const val TOP_LEFT_OVERLAY_IGNORE_X_RATIO = 0.46
        const val TOP_LEFT_OVERLAY_IGNORE_Y_RATIO = 0.38
        const val TOP_RIGHT_OVERLAY_IGNORE_X_RATIO = 0.58
        const val TOP_RIGHT_OVERLAY_IGNORE_Y_RATIO = 0.42
        const val BOTTOM_LEFT_OVERLAY_IGNORE_X_RATIO = 0.32
        const val BOTTOM_LEFT_OVERLAY_IGNORE_Y_RATIO = 0.72
        const val BOTTOM_BAND_OVERLAY_IGNORE_Y_RATIO = 0.74
    }
}
