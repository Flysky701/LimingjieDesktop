package com.landosol.toolbox.labyrinth.vision

import org.junit.Assert.*
import org.junit.Test

class LabyrinthRoleRewardTextIdentityTest {
    private val names = listOf(
        LabyrinthCharacterNameCandidate("1001", "千歌", listOf("チカ")),
        LabyrinthCharacterNameCandidate("1002", "千歌（礼服）", listOf("千歌(祭服)")),
        LabyrinthCharacterNameCandidate("1003", "露"),
    )
    private val slot = LabyrinthCharacterMatch("role_reward_left", null, null, 0.0, EntryPixelRect(10, 20, 240, 240))

    @Test fun `complete OCR identifies outfit immediately without any portrait work`() {
        val result = roleRewardTextFirstMatch("千歌\n（ 礼服 ）", names, slot) { error("portrait must not run") }
        assertEquals("1002", result.characterId)
        assertTrue(result.nameAssisted)
        assertTrue(com.landosol.toolbox.labyrinth.labyrinthRoleRewardIdentityIsActionSafe(result))
        assertEquals(slot.screenRect, result.screenRect)
    }

    @Test fun `pending OCR does not trigger portraits or fabricate an identity`() {
        assertEquals(slot, roleRewardTextFirstMatch(null, names, slot) { error("OCR still pending") })
    }

    @Test fun `failed or incomplete OCR invokes the portrait fallback`() {
        val portrait = slot.copy(characterId = "1002", displayName = "千歌（礼服）", trusted = true)
        for (text in listOf("", "千歌（礼", "路", "礼服")) {
            var calls = 0
            val result = roleRewardTextFirstMatch(text, names, slot) { calls++; portrait }
            assertEquals(1, calls)
            assertEquals(portrait, result)
        }
    }

    @Test fun `base name with outfit variants requires matching characterId instead of overwriting outfit portrait`() {
        // The outfit suffix is printed on a second line, so OCR returning "千歌" alone may be a
        // truncated "千歌（礼服）". Only a portrait matching the base role confirms "1001"; an outfit
        // portrait ("1002") is preserved instead of overwritten.
        val basePortrait = slot.copy(characterId = "1001", displayName = "千歌", trusted = true)
        var calls = 0
        val confirmed = roleRewardTextFirstMatch("千歌", names, slot) { calls++; basePortrait }
        assertEquals(1, calls)
        assertEquals("1001", confirmed.characterId)
        assertTrue(confirmed.nameAssisted)

        val outfitPortrait = slot.copy(characterId = "1002", displayName = "千歌（礼服）", trusted = true)
        val preservedOutfit = roleRewardTextFirstMatch("千歌", names, slot) { outfitPortrait }
        assertEquals(outfitPortrait, preservedOutfit)

        val otherPortrait = slot.copy(characterId = "1003", displayName = "露", trusted = true)
        val rejected = roleRewardTextFirstMatch("千歌", names, slot) { otherPortrait }
        assertEquals(otherPortrait, rejected)

        val unresolved = slot.copy(characterId = null, trusted = false)
        assertEquals(unresolved, roleRewardTextFirstMatch("千歌", names, slot) { unresolved })

        // A name with no outfit variants keeps the fast path.
        roleRewardTextFirstMatch("露", names, slot) { error("portrait must not run") }
        // A complete outfit name keeps the fast path too.
        roleRewardTextFirstMatch("千歌（礼服）", names, slot) { error("portrait must not run") }
    }

    @Test fun `aliases must identify exactly one character`() {
        assertEquals("1002", roleRewardExactName("千歌(祭服)", names)?.characterId)
        assertEquals("1003", roleRewardExactName("露", names)?.characterId)
        assertNull(roleRewardExactName("千歌", names + LabyrinthCharacterNameCandidate("1004", "千歌（夏日）", listOf("千歌"))))
        assertNull(roleRewardExactName("千歌（新年）", names))
    }

    @Test fun `repeated icon variants of the same identity are not competing names`() {
        assertEquals("1002", roleRewardExactName("千歌（礼服）", names + names[1])?.characterId)
    }
}
