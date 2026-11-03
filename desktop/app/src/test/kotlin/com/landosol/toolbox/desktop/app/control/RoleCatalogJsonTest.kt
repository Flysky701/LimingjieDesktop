package com.landosol.toolbox.desktop.app.control

import com.landosol.toolbox.labyrinth.LabyrinthRoleRatingItem
import kotlinx.serialization.json.JsonArray
import org.junit.Assert.*
import org.junit.Test

class RoleCatalogJsonTest {
    @Test fun threeUnchangedSnapshotsEncodeOnceInsteadOfThreeTimesWithIdenticalJson() {
        val items = listOf(role("a"), role("b"))
        val inputs = listOf(items, items.toList(), items.map { it.copy() })
        var beforeCalls = 0
        val before = inputs.map { beforeCalls++; ControlModelCodec.encode(it) }
        var afterCalls = 0
        val cached = RoleCatalogJson { afterCalls++; ControlModelCodec.encode(it) }
        val after = inputs.map(cached::encode)
        assertEquals(3, beforeCalls)
        assertEquals(1, afterCalls)
        assertEquals(before, after)
        assertSame(after.first(), after.last())
    }

    @Test fun changesAndClearsKeepTheWireFormatAndDoNotCrossServiceInstances() {
        val items = mutableListOf(role("a"))
        val original = items.toList()
        var calls = 0
        val first = RoleCatalogJson { calls++; ControlModelCodec.encode(it) }
        val second = RoleCatalogJson()
        val initial = first.encode(items)
        val other = second.encode(listOf(role("other")))
        items[0] = items[0].copy(name = "updated", iconAsset = "icons/updated.png", originalScore = 75.0)
        assertEquals(ControlModelCodec.encode(items), first.encode(items))
        assertEquals(2, calls)
        assertEquals(ControlModelCodec.encode(original), initial)
        items += role("added")
        assertEquals(ControlModelCodec.encode(items), first.encode(items))
        assertEquals(3, calls)
        items.clear()
        assertEquals(JsonArray(emptyList()), first.encode(items))
        assertEquals(4, calls)
        items += original
        assertEquals(initial, first.encode(items))
        assertEquals(5, calls)
        assertSame(other, second.encode(listOf(role("other"))))
        assertEquals(JsonArray(emptyList()), RoleCatalogJson().encode(emptyList()))
    }

    private fun role(id: String) = LabyrinthRoleRatingItem(id, id, 50.0, null, "synthetic")
}
