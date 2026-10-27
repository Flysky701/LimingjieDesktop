package com.landosol.toolbox.desktop.app.control

import com.landosol.toolbox.labyrinth.LabyrinthRoleRatingItem
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull

/** One instance per GraphControlService, used on its snapshot thread. */
internal class RoleCatalogJson(
    private val encodeValue: (List<LabyrinthRoleRatingItem>) -> JsonElement = ControlModelCodec::encode,
) {
    private var previous: List<LabyrinthRoleRatingItem>? = null
    private var encoded: JsonElement = JsonNull

    fun encode(items: List<LabyrinthRoleRatingItem>): JsonElement {
        if (previous != items) {
            // Entries are immutable; copy the list so an edit to its backing list is observable.
            val snapshot = items.toList()
            val next = encodeValue(snapshot)
            previous = snapshot
            encoded = next
        }
        return encoded
    }
}
