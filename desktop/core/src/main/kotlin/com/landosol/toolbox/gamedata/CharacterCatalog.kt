package com.landosol.toolbox.gamedata

sealed interface CharacterLookupResult {
    data class Found(val character: CharacterResource) : CharacterLookupResult
    data class Unavailable(val character: CharacterResource) : CharacterLookupResult
    data class Ambiguous(
        val query: String,
        val candidates: List<CharacterResource>,
    ) : CharacterLookupResult

    data class NotFound(val query: String) : CharacterLookupResult
}

/** Resolves a resource-pack character by ID, canonical name, or alias without guessing collisions. */
class CharacterCatalog(characters: List<CharacterResource>) {
    private val index: Map<String, List<CharacterResource>> =
        mutableMapOf<String, MutableList<CharacterResource>>().apply {
            characters.forEach { character ->
                (listOf(character.id, character.name, character.officialName) + character.aliases)
                    .map(String::normalizedCharacterName)
                    .filter(String::isNotEmpty)
                    .distinct()
                    .forEach { key ->
                        getOrPut(key) { mutableListOf() }.add(character)
                    }
            }
        }.mapValues { (_, values) ->
            values.distinctBy(CharacterResource::id).sortedBy(CharacterResource::id)
        }

    fun lookup(query: String): CharacterLookupResult {
        val normalized = query.normalizedCharacterName()
        if (normalized.isEmpty()) return CharacterLookupResult.NotFound(query)
        val candidates = index[normalized].orEmpty()
        return when (candidates.size) {
            0 -> CharacterLookupResult.NotFound(query)
            1 -> candidates.single().let { character ->
                if (character.available) CharacterLookupResult.Found(character)
                else CharacterLookupResult.Unavailable(character)
            }
            else -> CharacterLookupResult.Ambiguous(query, candidates)
        }
    }
}
