package com.landosol.toolbox.labyrinth.vision

import com.landosol.toolbox.core.AppContext
import com.landosol.toolbox.vision.NativeBitmapCodec
import com.landosol.toolbox.vision.PixelImage
import com.landosol.toolbox.gamedata.CharacterResource
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Loads the character-name templates captured from the currently verified reward screen. */
class LabyrinthCharacterTemplateLoader(
    private val context: AppContext,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    fun load(): LabyrinthCharacterTemplateSet {
        val roster = decode<CharacterRosterDocument>(CHARACTERS_PATH)
            .characters
            .associateBy(CharacterResource::id)
        return LabyrinthCharacterTemplateSet(
            TEMPLATE_DEFINITIONS.map { definition ->
                LabyrinthCharacterTemplate(
                    slotId = definition.slotId,
                    characterId = definition.characterId,
                    displayName = roster[definition.characterId]?.displayName ?: definition.displayName,
                    image = loadImage(definition.path),
                )
            },
        )
    }

    private inline fun <reified T> decode(path: String): T = context.assets.open(path).use { input ->
        json.decodeFromString(input.reader().use { it.readText() })
    }

    private fun loadImage(path: String): PixelImage {
        val bitmap = context.assets.open(path).use { input ->
            requireNotNull(NativeBitmapCodec.decodeStream(input)) { "Cannot decode character template: $path" }
        }
        return bitmap.copyToPixelImage().also { bitmap.recycle() }
    }

    private data class Definition(
        val slotId: String,
        val characterId: String,
        val displayName: String,
        val path: String,
    )

    @Serializable
    private data class CharacterRosterDocument(
        val schemaVersion: Int = 1,
        val characters: List<CharacterResource> = emptyList(),
    )

    private companion object {
        const val CHARACTERS_PATH = "resource-packs/cn-bilibili/characters.landosolroster.json"
        const val ROLE_REWARD_ROOT = "resource-packs/cn-bilibili/vision/initial_role_reward/characters"
        const val JOINED_ROOT = "resource-packs/cn-bilibili/vision/character_joined/characters"
        val TEMPLATE_DEFINITIONS = listOf(
            Definition("role_reward_left", "1335", "咲恋(新年)", "$ROLE_REWARD_ROOT/character_1335_name.png"),
            Definition("role_reward_center", "1233", "涅娅", "$ROLE_REWARD_ROOT/character_1233_name.png"),
            Definition("role_reward_right", "1256", "碧卡拉", "$ROLE_REWARD_ROOT/character_1256_name.png"),
            Definition("joined_first", "1209", "伊莉亚(新年)", "$JOINED_ROOT/character_1209_name.png"),
            Definition("joined_second", "1257", "花凛(炼金术师)", "$JOINED_ROOT/character_1257_name.png"),
            Definition("joined_third", "1139", "纺希(万圣节)", "$JOINED_ROOT/character_1139_name.png"),
        )
    }
}
