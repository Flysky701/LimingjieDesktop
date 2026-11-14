package com.landosol.toolbox.labyrinth

import com.landosol.toolbox.protocol.labyrinth.LabyrinthOpeningRelicState
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

internal fun openingRelicRoute(guildId: Int = 3, nodes: Set<Int>? = setOf(31), enterId: Long = 42) = LabyrinthRouteJson(
    enterId = enterId, guildId = guildId, difficulty = 2, attempt = 1, rerollUntilFound = false,
    perfectStart = false, thirdBlockChoice = "EITHER", area3BossIds = emptyList(), area5BossIds = emptyList(),
    nodes = emptyList(), openingRelics = LabyrinthOpeningRelicState(guildId, nodes),
)

class LabyrinthOpeningRelicStateTest {
    @Test fun `serialized route retains nodes and empty versus missing state`() {
        for (nodes in listOf<Set<Int>?>(setOf(31), emptySet(), null)) {
            val route = openingRelicRoute(nodes = nodes)
            val restored = Json.decodeFromString(LabyrinthRouteJson.serializer(), Json.encodeToString(LabyrinthRouteJson.serializer(), route))
            assertEquals(route.openingRelics, restored.openingRelics)
            assertEquals(route.openingRelics, restored.toLabyrinthRoute().openingRelics)
        }
        val legacy = openingRelicRoute().copy(openingRelics = null)
        assertNull(Json.decodeFromString(LabyrinthRouteJson.serializer(), Json.encodeToString(LabyrinthRouteJson.serializer(), legacy)).openingRelics)
    }

    @Test fun `missing top field preserves only an identical opening snapshot`() {
        val saved = openingRelicRoute()
        val unknown = saved.copy(openingRelics = LabyrinthOpeningRelicState(3))
        assertEquals(setOf(31), unknown.withOpeningRelicsFrom(saved).openingRelics?.enhanceNodeIds)
        assertNull(unknown.copy(enterId = 43).withOpeningRelicsFrom(saved).openingRelics?.enhanceNodeIds)
        assertNull(openingRelicRoute(guildId = 2, nodes = null).withOpeningRelicsFrom(saved).openingRelics?.enhanceNodeIds)
        assertEquals(emptySet<Int>(), openingRelicRoute(nodes = emptySet()).withOpeningRelicsFrom(saved).openingRelics?.enhanceNodeIds)
        assertNull(saved.copy(openingRelics = LabyrinthOpeningRelicState(5, setOf(31))).withOpeningRelicsFrom(null).openingRelics)
    }

    @Test fun `advancing route progress retains its initial grant conditions`() {
        val node = LabyrinthNodeJson(1, 2, 1, 10201, 1, null, emptyList(), false)
        val route = openingRelicRoute().copy(nodes = listOf(node))
        assertEquals(route.openingRelics, route.withAdvancedCurrentBlock(10201)?.openingRelics)
    }
}
