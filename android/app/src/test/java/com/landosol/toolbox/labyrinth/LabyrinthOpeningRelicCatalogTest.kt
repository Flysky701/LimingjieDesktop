package com.landosol.toolbox.labyrinth

import com.landosol.toolbox.gamedata.RelicResource
import com.landosol.toolbox.labyrinth.vision.EntryPixelRect
import com.landosol.toolbox.protocol.labyrinth.LabyrinthOpeningRelicState
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test

internal fun openingRelicResources(): List<RelicResource> {
    val file = listOf(File("src/main/assets/resource-packs/cn-bilibili/relics.laby-workbook.json"),
        File("app/src/main/assets/resource-packs/cn-bilibili/relics.laby-workbook.json"))
        .first { it.isFile }
    val json = Json { ignoreUnknownKeys = true }
    return json.parseToJsonElement(file.readText()).jsonObject.getValue("relics").jsonArray
        .map { json.decodeFromJsonElement<RelicResource>(it) }
}

class LabyrinthOpeningRelicCatalogTest {
    private val resources = openingRelicResources()
    private val catalog = LabyrinthOpeningRelicCatalog(resources)
    private val rect = EntryPixelRect(0, 0, 1, 1)
    private val expectedGrants = mapOf(
        1 to listOf("40001", "50001"), 2 to listOf("40002", "50002"),
        3 to listOf("40003", "50003"), 4 to listOf("40004", "50004"),
        5 to listOf("40005", "50005"), 6 to listOf("40006", "50006"),
        7 to listOf("40007", "50007"), 8 to listOf("40008", "50008"),
        9 to listOf("40009", "50009"), 10 to listOf("40010", "50010"),
    )

    @Test fun `every guild grants its base relic and node 31 alone unlocks the second`() {
        assertEquals(LabyrinthRerollOptions.guilds.map { it.guildId }.toSet(), expectedGrants.keys)
        for ((guild, ids) in expectedGrants) {
            val base = catalog.grants(LabyrinthOpeningRelicState(guild, setOf(28, 29, 30, 32)), rect)
            assertEquals("guild $guild", ids.take(1), base.relics.map { it.relicId })
            assertTrue(base.complete)
            val both = catalog.grants(LabyrinthOpeningRelicState(guild, setOf(31)), rect)
            assertEquals("guild $guild", ids, both.relics.map { it.relicId })
            assertTrue(both.complete)
            assertTrue(both.relics.all { it.attributeBonus == "1" && it.displayName == resources.first { r -> r.id == it.relicId }.name })
        }
    }

    @Test fun `missing account nodes do not fabricate a second relic or a complete baseline`() {
        for ((guild, ids) in expectedGrants) {
            val unknown = catalog.grants(LabyrinthOpeningRelicState(guild), rect)
            assertEquals("guild $guild", ids.take(1), unknown.relics.map { it.relicId })
            assertFalse(unknown.complete)
            val invalid = catalog.grants(LabyrinthOpeningRelicState(guild, setOf(0, 31)), rect)
            assertEquals(ids.take(1), invalid.relics.map { it.relicId })
            assertFalse(invalid.complete)
            val locked = catalog.grants(LabyrinthOpeningRelicState(guild, emptySet()), rect)
            assertEquals(ids.take(1), locked.relics.map { it.relicId })
            assertTrue(locked.complete)
        }
    }

    @Test fun `unknown guild and missing catalog entry never claim a complete grant`() {
        assertTrue(catalog.grants(null, rect).relics.isEmpty())
        val unknown = catalog.grants(LabyrinthOpeningRelicState(99, setOf(31)), rect)
        assertTrue(unknown.relics.isEmpty())
        assertFalse(unknown.complete)
        for ((guild, ids) in expectedGrants) {
            val incomplete = LabyrinthOpeningRelicCatalog(resources.filterNot { it.id == ids.last() })
                .grants(LabyrinthOpeningRelicState(guild, setOf(31)), rect)
            assertFalse(incomplete.complete)
            assertEquals("guild $guild", ids.take(1), incomplete.relics.map { it.relicId })
        }
    }

    @Test fun `fixed series totals follow the catalog without imprint or random effect guesses`() {
        val marks = listOf(
            listOf("守备", "强化"), listOf("强化", "守备"), listOf("加速", "会心"),
            listOf("会心", "弱体"), listOf("弱体", "加速"), listOf("强化", "强化"),
            listOf("加速", "加速"), listOf("守备", "守备"), listOf("会心", "会心"),
            listOf("弱体", "弱体"),
        )
        for ((index, expectedMarks) in marks.withIndex()) {
            val grant = catalog.grants(LabyrinthOpeningRelicState(index + 1, setOf(31)), rect)
            assertEquals("guild ${index + 1}", expectedMarks, grant.relics.map { it.attribute })
            assertEquals(2, grant.relics.size)
        }
    }

    @Test fun `new guild relics use official CN names`() {
        val expected = mapOf(
            "40006" to "光耀慈乐之音", "50006" to "为你低吟浅唱",
            "40007" to "苍蓝子夜", "50007" to "恶魔伪王国军之战栗",
            "40008" to "伊丽莎白守护者", "50008" to "橡实精魂",
            "40009" to "高贵紫藤庐", "50009" to "利维坦召唤",
            "40010" to "兵法二海一流", "50010" to "缚誓玫瑰",
        )
        val actual = (6..10).flatMap { guild ->
            catalog.grants(LabyrinthOpeningRelicState(guild, setOf(31)), rect).relics
        }.associate { requireNotNull(it.relicId) to it.displayName }
        assertEquals(expected, actual)
    }
}
