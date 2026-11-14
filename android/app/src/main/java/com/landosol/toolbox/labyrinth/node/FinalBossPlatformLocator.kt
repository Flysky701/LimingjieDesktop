package com.landosol.toolbox.labyrinth.node

import com.landosol.toolbox.clanbattle.recognition.PixelImage
import com.landosol.toolbox.labyrinth.vision.EntryPixelRect
import com.landosol.toolbox.labyrinth.vision.GradientTemplateMatcher
import com.landosol.toolbox.labyrinth.vision.PixelImageScaler
import kotlin.math.abs
import kotlin.math.roundToInt

data class FinalBossPlatformMatch(
    val labelRect: EntryPixelRect,
    val confidence: Double,
    val colorScore: Double = 1.0,
    val purpleActivationScore: Double = 0.0,
) {
    val reliable: Boolean get() = confidence >= FinalBossPlatformLocator.MIN_CONFIDENCE &&
        // Activation may recolor the panel, but cannot replace a strong glyph match.
        (colorScore >= 0.80 || (confidence >= 0.80 && purpleActivationScore >= 0.20))
}

/** Locates only the static 首领 nameplate; the supplied platform asset also contains moving art. */
class FinalBossPlatformLocator(platform: PixelImage?) {
    private val label = platform?.let { source ->
        // Coordinates in the existing 500x350 asset: exclude feet, chest, lock and outer glow.
        val left = source.width * 225 / 500
        val top = source.height * 197 / 350
        val width = source.width * 122 / 500
        val height = source.height * 46 / 350
        if (width < 3 || height < 3) null else PixelImage(width, height,
            IntArray(width * height) { i -> source[left + i % width, top + i / width] })
    }
    private val coarse = GradientTemplateMatcher(maxSamples = 160)
    private val precise = GradientTemplateMatcher(maxSamples = 1_000)

    /** Cached nameplate for the current capture size; a 1080p capture never populates it. */
    private var scaledLabel: PixelImage? = null
    private var scaledLabelWidth = -1
    private var scaledLabelHeight = -1

    var lastCandidates: List<FinalBossPlatformMatch> = emptyList()
        private set

    /**
     * Score the nameplate at the size it actually occupies on this capture.
     *
     * The crop is static, so template and rect must be compared 1:1. Handing the matcher a 122x46
     * crop for an 81x31 rect makes it resample with nearest-neighbour, which drops 41 columns and
     * 15 rows outright; that single mismatch took a 1280x720 capture from 0.96 down to 0.78, under
     * the 0.80 gate. A 1080p capture asks for exactly the crop size, so the source object is
     * returned unchanged and every score stays bit-identical.
     */
    private fun templateOf(width: Int, height: Int): PixelImage? {
        val source = label ?: return null
        if (source.width == width && source.height == height) return source
        // Upscaling has no source pixels to average; keep the crop and let the matcher map it.
        if (width >= source.width || height >= source.height) return source
        val cached = scaledLabel
        if (cached != null && scaledLabelWidth == width && scaledLabelHeight == height) return cached
        return PixelImageScaler.scale(source, width, height).also {
            scaledLabel = it
            scaledLabelWidth = width
            scaledLabelHeight = height
        }
    }

    /** Fresh pixels on every map frame, independently of the whole-node tracking cache. */
    fun locate(frame: PixelImage): List<FinalBossPlatformMatch> {
        lastCandidates = emptyList()
        val scale = frame.height / 1080.0
        val width = (122 * scale).roundToInt().coerceAtLeast(3)
        val height = (46 * scale).roundToInt().coerceAtLeast(3)
        val template = templateOf(width, height) ?: return emptyList()
        val step = (6 * scale).roundToInt().coerceAtLeast(2)
        // One bounded horizontal platform band, never the animated body or bottom HUD.
        val left = (frame.width * 0.18).roundToInt()
        val right = (frame.width * 0.96).roundToInt() - width
        val top = (frame.height * 0.46).roundToInt()
        val bottom = (frame.height * 0.69).roundToInt() - height
        if (right < left || bottom < top) return emptyList()
        val proposals = ArrayList<FinalBossPlatformMatch>()
        for (y in top..bottom step step) for (x in left..right step step) {
            val rect = EntryPixelRect(x, y, width, height)
            proposals += FinalBossPlatformMatch(rect, coarse.score(frame, rect, template))
        }
        val seeds = ArrayList<FinalBossPlatformMatch>()
        for (candidate in proposals.sortedByDescending { it.confidence }) {
            if (seeds.none { near(it.labelRect, candidate.labelRect) }) seeds += candidate
            if (seeds.size == 8) break
        }
        val candidates = seeds.map { seed ->
            var best = seed.copy(confidence = 0.0)
            fun examine(cx: Int, cy: Int, radius: Int, stride: Int) {
                for (dy in -radius..radius step stride) for (dx in -radius..radius step stride) {
                    val x = cx + dx
                    val y = cy + dy
                    if (x !in left..right || y !in top..bottom) continue
                    val rect = EntryPixelRect(x, y, width, height)
                    val score = precise.score(frame, rect, template, minimumChannelScore = 0.35)
                    if (score > best.confidence) best = FinalBossPlatformMatch(rect, score)
                }
            }
            examine(seed.labelRect.left, seed.labelRect.top, step, maxOf(1, step / 3))
            examine(best.labelRect.left, best.labelRect.top, 2, 1)
            best.copy(colorScore = colorScore(frame, best.labelRect, template),
                purpleActivationScore = purpleActivationScore(frame, best.labelRect))
        }.sortedByDescending { it.confidence }
        lastCandidates = candidates
        val matches = candidates.filter { it.reliable }
        val distinct = ArrayList<FinalBossPlatformMatch>()
        matches.forEach { match -> if (distinct.none { near(it.labelRect, match.labelRect) }) distinct += match }
        return distinct
    }

    private fun near(a: EntryPixelRect, b: EntryPixelRect): Boolean =
        abs(a.left - b.left) < a.width * 0.65 && abs(a.top - b.top) < a.height

    private fun colorScore(frame: PixelImage, rect: EntryPixelRect, template: PixelImage): Double {
        var distance = 0L
        var samples = 0
        for (y in 1 until template.height - 1 step 2) for (x in 1 until template.width - 1 step 2) {
            val actual = frame[rect.left + x * (rect.width - 1) / (template.width - 1),
                rect.top + y * (rect.height - 1) / (template.height - 1)]
            val expected = template[x, y]
            distance += abs((actual and 255) - (expected and 255)) +
                abs((actual ushr 8 and 255) - (expected ushr 8 and 255)) +
                abs((actual ushr 16 and 255) - (expected ushr 16 and 255))
            samples++
        }
        return if (samples == 0) 0.0 else 1.0 - distance / (samples * 765.0)
    }

    /** Activated Boss platforms turn purple; require both side rails and the lower platform. */
    private fun purpleActivationScore(frame: PixelImage, label: EntryPixelRect): Double {
        val cx = label.left + label.width / 2.0
        fun band(x1: Double, x2: Double, y1: Double, y2: Double): Double {
            val left = (cx + label.width * x1).roundToInt()
            val right = (cx + label.width * x2).roundToInt()
            val top = (label.top + label.height * y1).roundToInt()
            val bottom = (label.top + label.height * y2).roundToInt()
            if (left < 0 || right >= frame.width || top < 0 || bottom >= frame.height) return 0.0
            var samples = 0
            var purple = 0
            for (y in top..bottom step 3) for (x in left..right step 3) {
                val c = frame[x, y]
                val r = c ushr 16 and 255
                val g = c ushr 8 and 255
                val b = c and 255
                if (r >= 130 && b >= 140 && r - g >= 30 && b - g >= 35) purple++
                samples++
            }
            return if (samples == 0) 0.0 else purple.toDouble() / samples
        }
        return minOf(band(-1.35, -0.95, -0.7, 2.6), band(0.95, 1.35, -0.7, 2.6),
            band(-0.8, 0.8, 2.0, 3.0))
    }

    companion object { const val MIN_CONFIDENCE = 0.72 }
}

/** Route semantics authorize identity; only an unambiguous current-frame label authorizes pixels. */
internal fun finalBossPlatformClickRect(
    matches: List<FinalBossPlatformMatch>,
    frameWidth: Int,
    frameHeight: Int,
    predictedX: Double?,
): EntryPixelRect? {
    val match = matches.filter { it.reliable }.singleOrNull()
        ?: return null
    val label = match.labelRect
    if (label.width < 3 || label.height < 3 || label.left < 0 || label.top < frameHeight * 0.46 ||
        label.left + label.width > frameWidth || label.top + label.height > frameHeight * 0.69) return null
    val x = label.left + label.width / 2.0
    if (predictedX != null) {
        if (!predictedX.isFinite()) return null
        val topologyMismatch = abs(predictedX - x) > labyrinthReferenceColumnPitch(frameHeight) * 0.45
        // The fitted viewport can become stale after the game recenters/pans the map. A single
        // current-frame "首领" glyph match with strong activated-purple platform evidence is more
        // authoritative than that stale topology hint. We still reject weaker contradictory
        // matches, so this never degenerates into a synthesized/fixed coordinate fallback.
        val strongCurrentFrameBoss =
            match.confidence >= STRONG_BOSS_LABEL_CONFIDENCE &&
                match.purpleActivationScore >= STRONG_BOSS_PURPLE_ACTIVATION
        if (topologyMismatch && !strongCurrentFrameBoss) return null
    }
    // The shared node executor taps at 32% height: put that point at the nameplate's center.
    return label.copy(top = label.top + (label.height * 0.18).roundToInt())
}

private const val STRONG_BOSS_LABEL_CONFIDENCE = 0.90
private const val STRONG_BOSS_PURPLE_ACTIVATION = 0.40
