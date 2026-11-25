package com.landosol.toolbox.labyrinth

import com.landosol.toolbox.gamedata.GameIconPackDocument

data class LabyrinthRoleRatingItem(
    val id: String,
    val name: String,
    val originalScore: Double?,
    val iconAsset: String?,
    val detail: String,
)

fun labyrinthRoleRatingCatalog(
    document: LabyrinthRoleDecisionDocument,
    icons: GameIconPackDocument?,
): List<LabyrinthRoleRatingItem> {
    val byOwner = icons?.characterIcons.orEmpty().groupBy { it.ownerId }
    return document.characters.filter { it.characterId != "1000" }.map { role ->
        val icon = byOwner[role.characterId].orEmpty()
            .sortedWith(compareBy({ if (it.variant == "31") 0 else 1 }, { it.variant }))
            .firstOrNull { it.file.startsWith("icons/characters/") && ".." !in it.file && '\\' !in it.file }
        LabyrinthRoleRatingItem(role.characterId, role.displayName, role.userScore ?: role.baseQuality,
            icon?.let { "resource-packs/cn-bilibili/${it.file}" },
            listOfNotNull(role.attribute?.label, role.roleClass).joinToString(" · "))
    }.sortedWith(compareBy(LabyrinthRoleRatingItem::name, LabyrinthRoleRatingItem::id))
}
