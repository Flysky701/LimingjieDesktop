package com.landosol.toolbox.labyrinth.vision

import android.content.Context
import android.graphics.BitmapFactory
import com.landosol.toolbox.clanbattle.recognition.AndroidPixelImageAdapter
import com.landosol.toolbox.clanbattle.recognition.PixelImage
import com.landosol.toolbox.gamedata.CharacterResource
import com.landosol.toolbox.gamedata.GameIconPackDocument
import com.landosol.toolbox.labyrinth.LabyrinthCharacterAttribute
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Loads every indexed character icon and its preferred CN name for battle-page matching. */
class AndroidLabyrinthBattleTeamTemplateLoader(
    private val context: Context,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    fun load(
        characterAttributes: Map<String, LabyrinthCharacterAttribute?> = emptyMap(),
    ): List<LabyrinthBattleCharacterTemplate> = templates(characterAttributes).toList()

    fun loadMatcher(
        characterAttributes: Map<String, LabyrinthCharacterAttribute?> = emptyMap(),
    ): LabyrinthCharacterIconMatcher = LabyrinthCharacterIconMatcher(templates(characterAttributes))

    private fun templates(
        characterAttributes: Map<String, LabyrinthCharacterAttribute?>,
    ): Sequence<LabyrinthBattleCharacterTemplate> {
        val iconDocument = decode<GameIconPackDocument>(ICONS_PATH)
        val roster = decode<CharacterRosterDocument>(CHARACTERS_PATH)
            .characters
            .associateBy(CharacterResource::id)
        return iconDocument.characterIcons.asSequence().mapNotNull { icon ->
            val character = roster[icon.ownerId] ?: return@mapNotNull null
            LabyrinthBattleCharacterTemplate(
                characterId = icon.ownerId,
                displayName = character.displayName,
                iconVariant = icon.variant,
                image = loadImage("$ROOT/${icon.file}"),
                attribute = characterAttributes[icon.ownerId],
                aliases = buildList {
                    add(character.name)
                    add(character.officialName)
                    addAll(character.aliases)
                }
                    .map(String::trim)
                    .filter(String::isNotEmpty)
                    .filterNot { it == character.displayName }
                    .distinct(),
            )
        }
    }

    private inline fun <reified T> decode(path: String): T = context.assets.open(path).use { input ->
        json.decodeFromString(input.reader().use { it.readText() })
    }

    private fun loadImage(path: String): PixelImage {
        val bitmap = context.assets.open(path).use { input ->
            requireNotNull(BitmapFactory.decodeStream(input)) { "Cannot decode battle character icon: $path" }
        }
        return AndroidPixelImageAdapter.from(bitmap).also { bitmap.recycle() }
    }

    @Serializable
    private data class CharacterRosterDocument(
        val schemaVersion: Int = 1,
        val characters: List<CharacterResource> = emptyList(),
    )

    private companion object {
        const val ROOT = "resource-packs/cn-bilibili"
        const val ICONS_PATH = "$ROOT/icons.json"
        const val CHARACTERS_PATH = "$ROOT/characters.landosolroster.json"
    }
}
