package com.landosol.toolbox.labyrinth.vision

internal fun roleRewardTextFirstMatch(
    text: String?,
    candidates: List<LabyrinthCharacterNameCandidate>,
    slot: LabyrinthCharacterMatch,
    portraitFallback: () -> LabyrinthCharacterMatch,
): LabyrinthCharacterMatch {
    if (text == null) return slot
    val named = roleRewardExactName(text, candidates) ?: return portraitFallback()
    if (roleRewardNameHasOutfitVariants(named, candidates)) {
        // The reward card prints the outfit suffix on its own line. An OCR result that equals a
        // plain base name may therefore be a truncated "千歌（礼服）", so the portrait must agree
        // on the exact characterId before the base name is allowed to confirm as trusted.
        val portrait = portraitFallback()
        if (portrait.characterId != named.characterId) return portrait
    }
    return slot.copy(
        characterId = named.characterId, displayName = named.displayName,
        trusted = true, confidence = 1.0, rivalMargin = 1.0,
        nameEvidenceText = text, nameEvidenceScore = 1.0, nameEvidenceMargin = 1.0,
        nameAssisted = true,
    )
}

/** Only a complete, unique name can bypass portrait recognition, including outfit variants. */
internal fun roleRewardExactName(
    text: String,
    candidates: List<LabyrinthCharacterNameCandidate>,
): LabyrinthCharacterNameCandidate? {
    val observed = roleRewardNormalizeName(text)
    if (observed.isEmpty()) return null
    if (text.count { it == '(' || it == '（' } != text.count { it == ')' || it == '）' }) return null
    return candidates.filter { candidate ->
        (listOf(candidate.displayName) + candidate.aliases).any { roleRewardNormalizeName(it) == observed }
    }.distinctBy { it.characterId }.singleOrNull()
}

/** True when [named] is a plain base name and the roster also has outfit variants of it. */
internal fun roleRewardNameHasOutfitVariants(
    named: LabyrinthCharacterNameCandidate,
    candidates: List<LabyrinthCharacterNameCandidate>,
): Boolean {
    if (roleRewardNameFamily(named) != roleRewardNormalizeName(named.displayName)) return false
    val family = roleRewardNameFamily(named)
    return candidates.any { it.characterId != named.characterId && roleRewardNameFamily(it) == family }
}

internal fun roleRewardNameFamily(candidate: LabyrinthCharacterNameCandidate): String =
    roleRewardNormalizeName(candidate.displayName.substringBefore('（').substringBefore('('))

private fun roleRewardNormalizeName(value: String): String = value.filter(Char::isLetterOrDigit).lowercase()
