package com.landosol.toolbox.gamedata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CharacterCatalogTest {
    @Test
    fun `resolves id canonical name and case insensitive alias`() {
        val character = CharacterResource(
            id = "1001",
            name = "日和",
            aliases = listOf("Hiyori", "猫拳"),
        )
        val catalog = CharacterCatalog(listOf(character))

        assertEquals(character, (catalog.lookup("1001") as CharacterLookupResult.Found).character)
        assertEquals(character, (catalog.lookup(" 日和 ") as CharacterLookupResult.Found).character)
        assertEquals(character, (catalog.lookup("HIYORI") as CharacterLookupResult.Found).character)
    }

    @Test
    fun `resolves official name and prefers it for display`() {
        val character = CharacterResource(
            id = "1297",
            name = "涅妃‧涅罗",
            officialName = "涅妃＝涅菈",
            aliases = listOf("涅妃"),
        )
        val catalog = CharacterCatalog(listOf(character))

        assertEquals("涅妃＝涅菈", character.displayName)
        assertEquals(character, (catalog.lookup("涅妃＝涅菈") as CharacterLookupResult.Found).character)
        assertEquals(character, (catalog.lookup("涅妃‧涅罗") as CharacterLookupResult.Found).character)
    }

    @Test
    fun `unavailable character cannot be returned as selectable`() {
        val character = CharacterResource(id = "1072", name = "测试不可用角色", available = false)

        val result = CharacterCatalog(listOf(character)).lookup("1072")

        assertEquals(character, (result as CharacterLookupResult.Unavailable).character)
    }

    @Test
    fun `shared alias is ambiguous instead of selecting arbitrary character`() {
        val first = CharacterResource(id = "1", name = "角色甲", aliases = listOf("同名"))
        val second = CharacterResource(id = "2", name = "角色乙", aliases = listOf("同名"))

        val result = CharacterCatalog(listOf(second, first)).lookup("同名")

        assertTrue(result is CharacterLookupResult.Ambiguous)
        assertEquals(listOf("1", "2"), (result as CharacterLookupResult.Ambiguous).candidates.map { it.id })
    }

    @Test
    fun `blank and unknown queries remain not found`() {
        val catalog = CharacterCatalog(listOf(CharacterResource(id = "1", name = "角色甲")))

        assertTrue(catalog.lookup("  ") is CharacterLookupResult.NotFound)
        assertTrue(catalog.lookup("不存在") is CharacterLookupResult.NotFound)
    }
}
