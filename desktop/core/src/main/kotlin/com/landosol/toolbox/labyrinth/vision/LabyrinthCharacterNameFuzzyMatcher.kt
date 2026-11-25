package com.landosol.toolbox.labyrinth.vision

import kotlin.math.max

data class LabyrinthCharacterNameCandidate(
    val characterId: String,
    val displayName: String,
    val aliases: List<String> = emptyList(),
)

data class LabyrinthCharacterNameEvidence(
    val characterId: String?,
    val displayName: String?,
    val observedText: String,
    val score: Double,
    val rivalScore: Double,
    val margin: Double,
    val matchedFragment: String,
    val trusted: Boolean,
)

/**
 * Scores a small icon-derived candidate list against noisy OCR text from the role-name row.
 *
 * This is deliberately not a standalone OCR identity system. The icon matcher first narrows the
 * page to a handful of plausible roles; text only breaks close ties. Partial core-name evidence is
 * intentionally useful ("蕾西亚", "普蕾", or ordered "普西"), while generic variant suffixes such
 * as "夏日" are capped below the trust threshold when no core-name character is present.
 */
class LabyrinthCharacterNameFuzzyMatcher(
    private val minimumScore: Double = MINIMUM_TRUST_SCORE,
    private val minimumMargin: Double = MINIMUM_TRUST_MARGIN,
) {
    init {
        require(minimumScore in 0.0..1.0)
        require(minimumMargin in 0.0..1.0)
    }

    fun match(
        observedText: String,
        candidates: List<LabyrinthCharacterNameCandidate>,
    ): LabyrinthCharacterNameEvidence {
        val normalizedObserved = normalize(observedText)
        if (normalizedObserved.isEmpty() || candidates.isEmpty()) {
            return emptyEvidence(observedText)
        }

        val prepared = candidates
            .distinctBy(LabyrinthCharacterNameCandidate::characterId)
            .map { candidate ->
                val names = (listOf(candidate.displayName) + candidate.aliases)
                    .map(String::trim)
                    .filter(String::isNotEmpty)
                    .distinct()
                    .map(::splitName)
                PreparedCandidate(candidate, names)
            }
        // The whole OCR name row, including any variant, is stronger than a shared core.
        // Do not treat a visibly cut-off parenthesized suffix as a complete base name.
        val completeName = hasCompleteParentheses(observedText)
        val exact = prepared
            .filter { preparedCandidate ->
                completeName && preparedCandidate.names.any { it.full == normalizedObserved }
            }
            .singleOrNull()
            ?.candidate
        val scored = prepared.map { preparedCandidate ->
            val candidate = preparedCandidate.candidate
            val rivals = prepared
                .asSequence()
                .filter { it.candidate.characterId != candidate.characterId }
                .flatMap { it.names.asSequence() }
                .map { it.full }
                .toList()
            val score = preparedCandidate.names
                .map { name -> scoreCandidate(normalizedObserved, name, rivals) }
                .maxByOrNull(CandidateScore::score)
                ?: CandidateScore(0.0, "")
            val resolvedScore = when {
                exact == null -> score.score
                candidate.characterId == exact.characterId -> 1.0
                else -> minOf(score.score, 0.70)
            }
            ScoredCandidate(candidate, resolvedScore, score.fragment)
        }.sortedByDescending(ScoredCandidate::score)

        val best = scored.firstOrNull() ?: return emptyEvidence(observedText)
        val rivalScore = scored.getOrNull(1)?.score ?: 0.0
        val margin = best.score - rivalScore
        val trusted = best.score >= minimumScore && margin >= minimumMargin
        return LabyrinthCharacterNameEvidence(
            characterId = best.candidate.characterId.takeIf { trusted },
            displayName = best.candidate.displayName.takeIf { trusted },
            observedText = observedText,
            score = best.score,
            rivalScore = rivalScore,
            margin = margin,
            matchedFragment = best.fragment,
            trusted = trusted,
        )
    }

    private fun scoreCandidate(
        observed: String,
        name: PreparedName,
        rivalNames: List<String>,
    ): CandidateScore {
        if (name.full.isEmpty()) return CandidateScore(0.0, "")
        val core = name.core.ifEmpty { name.full }
        val coreSubstring = longestCommonSubstring(observed, core)
        val coreSubsequence = longestCommonSubsequence(observed, core)
        val coreChars = core.toSet()
        val matchedCoreChars = coreChars.intersect(observed.toSet())
        val uniqueCoreChars = coreChars.filter { character -> rivalNames.none { character in it } }.toSet()
        val uniqueMatched = uniqueCoreChars.intersect(observed.toSet()).size
        val matchedBigrams = core
            .windowed(size = 2, step = 1, partialWindows = false)
            .filter(observed::contains)

        // A one-character canonical name can still be decisive if none of the icon rivals uses it.
        if (core.length == 1 && observed.contains(core) && uniqueCoreChars.contains(core.single())) {
            return CandidateScore(0.95, core)
        }

        val contiguousEvidence = when (coreSubstring.length) {
            0 -> 0.0
            1 -> 0.22
            2 -> 0.62
            else -> (0.80 + (coreSubstring.length - 3) * 0.05).coerceAtMost(0.96)
        }
        val bigramEvidence = when (matchedBigrams.size) {
            0 -> 0.0
            1 -> 0.68
            else -> (0.76 + (matchedBigrams.size - 2) * 0.07).coerceAtMost(0.96)
        }
        val orderedCoverage = if (core.isEmpty()) 0.0 else coreSubsequence.length.toDouble() / core.length
        val orderedEvidence = when {
            coreSubsequence.length >= 3 -> (0.62 + orderedCoverage * 0.28).coerceAtMost(0.92)
            coreSubsequence.length == 2 -> 0.48 + orderedCoverage * 0.20
            else -> orderedCoverage * 0.25
        }
        val discriminativeEvidence = when {
            uniqueMatched >= 3 -> 0.90
            uniqueMatched == 2 -> 0.75
            uniqueMatched == 1 && core.length <= 2 -> 0.45
            uniqueMatched == 1 -> 0.30
            else -> 0.0
        }
        val typoEvidence = typoTolerantEvidence(observed, core)

        var score = maxOf(
            contiguousEvidence,
            bigramEvidence,
            orderedEvidence,
            discriminativeEvidence,
            typoEvidence,
        )
        if (matchedCoreChars.size >= 2) score += 0.05
        if (uniqueMatched >= 2) score += 0.04

        // A version suffix may reinforce a core-name match, but by itself it is intentionally weak:
        // many different roles share suffixes like 夏日 / 新年 / 圣诞节.
        if (name.variant.isNotEmpty() && observed.contains(name.variant)) {
            score = if (matchedCoreChars.isEmpty()) {
                max(score, VARIANT_ONLY_SCORE_CAP)
            } else {
                score + 0.04
            }
        }

        val fragment = when {
            coreSubstring.length >= 2 -> coreSubstring
            coreSubsequence.length >= 2 -> coreSubsequence
            else -> matchedCoreChars.joinToString(separator = "").take(2)
        }
        return CandidateScore(score.coerceIn(0.0, 1.0), fragment)
    }

    /**
     * OCR often substitutes one visually similar Han character (for example 蕾 -> 雷). Compare the
     * candidate core to all observed windows of roughly the same length and allow one edit to be
     * strong evidence, never enough on a one-character name.
     */
    private fun typoTolerantEvidence(observed: String, core: String): Double {
        if (core.length < 2 || observed.isEmpty()) return 0.0
        val windowLengths = setOf(
            (core.length - 1).coerceAtLeast(1),
            core.length,
            core.length + 1,
        )
        var bestDistance = Int.MAX_VALUE
        for (length in windowLengths) {
            if (length > observed.length) continue
            for (start in 0..observed.length - length) {
                val window = observed.substring(start, start + length)
                bestDistance = minOf(bestDistance, levenshtein(window, core))
            }
        }
        if (bestDistance == Int.MAX_VALUE && observed.length < core.length) {
            bestDistance = levenshtein(observed, core)
        }
        return when {
            bestDistance == 0 -> 0.92
            bestDistance == 1 && core.length >= 3 -> 0.78
            bestDistance == 1 && core.length == 2 -> 0.68
            bestDistance == 2 && core.length >= 5 -> 0.60
            else -> 0.0
        }
    }

    private fun splitName(displayName: String): PreparedName {
        val openIndex = displayName.indexOfFirst { it == '(' || it == '（' }
        if (openIndex < 0) {
            val full = normalize(displayName)
            return PreparedName(core = full, variant = "", full = full)
        }
        val closeIndex = displayName.indexOfLast { it == ')' || it == '）' }
        val core = normalize(displayName.substring(0, openIndex))
        val variant = if (closeIndex > openIndex) {
            normalize(displayName.substring(openIndex + 1, closeIndex))
        } else {
            normalize(displayName.substring(openIndex + 1))
        }
        return PreparedName(core = core, variant = variant, full = core + variant)
    }

    private fun hasCompleteParentheses(text: String): Boolean {
        var depth = 0
        for (character in text) {
            when (character) {
                '(', '（' -> depth++
                ')', '）' -> if (--depth < 0) return false
            }
        }
        return depth == 0
    }

    private fun normalize(value: String): String = buildString(value.length) {
        value.lowercase().forEach { character ->
            if (character.isLetterOrDigit()) append(character)
        }
    }

    private fun longestCommonSubstring(first: String, second: String): String {
        if (first.isEmpty() || second.isEmpty()) return ""
        val previous = IntArray(second.length + 1)
        var bestLength = 0
        var bestEnd = 0
        for (firstIndex in first.indices) {
            val current = IntArray(second.length + 1)
            for (secondIndex in second.indices) {
                if (first[firstIndex] == second[secondIndex]) {
                    current[secondIndex + 1] = previous[secondIndex] + 1
                    if (current[secondIndex + 1] > bestLength) {
                        bestLength = current[secondIndex + 1]
                        bestEnd = firstIndex + 1
                    }
                }
            }
            current.copyInto(previous)
        }
        return first.substring(bestEnd - bestLength, bestEnd)
    }

    private fun longestCommonSubsequence(first: String, second: String): String {
        if (first.isEmpty() || second.isEmpty()) return ""
        val lengths = Array(first.length + 1) { IntArray(second.length + 1) }
        for (firstIndex in 1..first.length) {
            for (secondIndex in 1..second.length) {
                lengths[firstIndex][secondIndex] = if (first[firstIndex - 1] == second[secondIndex - 1]) {
                    lengths[firstIndex - 1][secondIndex - 1] + 1
                } else {
                    max(lengths[firstIndex - 1][secondIndex], lengths[firstIndex][secondIndex - 1])
                }
            }
        }
        var firstIndex = first.length
        var secondIndex = second.length
        val reversed = StringBuilder()
        while (firstIndex > 0 && secondIndex > 0) {
            if (first[firstIndex - 1] == second[secondIndex - 1]) {
                reversed.append(first[firstIndex - 1])
                firstIndex--
                secondIndex--
            } else if (lengths[firstIndex - 1][secondIndex] >= lengths[firstIndex][secondIndex - 1]) {
                firstIndex--
            } else {
                secondIndex--
            }
        }
        return reversed.reverse().toString()
    }

    private fun levenshtein(first: String, second: String): Int {
        if (first.isEmpty()) return second.length
        if (second.isEmpty()) return first.length
        var previous = IntArray(second.length + 1) { it }
        for (firstIndex in first.indices) {
            val current = IntArray(second.length + 1)
            current[0] = firstIndex + 1
            for (secondIndex in second.indices) {
                val substitution = previous[secondIndex] +
                    if (first[firstIndex] == second[secondIndex]) 0 else 1
                current[secondIndex + 1] = minOf(
                    current[secondIndex] + 1,
                    previous[secondIndex + 1] + 1,
                    substitution,
                )
            }
            previous = current
        }
        return previous[second.length]
    }

    private fun emptyEvidence(observedText: String) = LabyrinthCharacterNameEvidence(
        characterId = null,
        displayName = null,
        observedText = observedText,
        score = 0.0,
        rivalScore = 0.0,
        margin = 0.0,
        matchedFragment = "",
        trusted = false,
    )

    private data class PreparedName(
        val core: String,
        val variant: String,
        val full: String,
    )

    private data class PreparedCandidate(
        val candidate: LabyrinthCharacterNameCandidate,
        val names: List<PreparedName>,
    )

    private data class CandidateScore(val score: Double, val fragment: String)

    private data class ScoredCandidate(
        val candidate: LabyrinthCharacterNameCandidate,
        val score: Double,
        val fragment: String,
    )

    companion object {
        const val MINIMUM_TRUST_SCORE = 0.66
        const val MINIMUM_TRUST_MARGIN = 0.18
        const val VARIANT_ONLY_SCORE_CAP = 0.34
    }
}
