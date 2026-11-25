package com.landosol.toolbox.labyrinth.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LabyrinthCharacterNameFuzzyMatcherTest {
    private val matcher = LabyrinthCharacterNameFuzzyMatcher()
    private val candidates = listOf(
        LabyrinthCharacterNameCandidate("1352", "普蕾西亚(夏日)"),
        LabyrinthCharacterNameCandidate("1253", "可可萝(游侠)"),
        LabyrinthCharacterNameCandidate("1170", "妮侬(万圣节)"),
    )

    private val yuiVariants = listOf(
        LabyrinthCharacterNameCandidate("base", "优衣"),
        LabyrinthCharacterNameCandidate("new_year", "优衣（新年）"),
        LabyrinthCharacterNameCandidate("princess", "优衣（公主）"),
    )

    @Test
    fun `complete base name separates original from same core variants`() {
        val result = matcher.match("优衣", yuiVariants)
        assertTrue(result.trusted)
        assertEquals("base", result.characterId)
        assertTrue(result.margin >= LabyrinthCharacterNameFuzzyMatcher.MINIMUM_TRUST_MARGIN)
    }

    @Test
    fun `single character OCR typo may assist only the unique one character icon candidate`() {
        val assisted = roleRewardSingleCharacterOcrAssist(
            observedText = "路",
            candidates = listOf(
                LabyrinthCharacterIconCandidate("1093", "露", "31", 0.503),
                LabyrinthCharacterIconCandidate("rival", "其他角色", "31", 0.430),
            ),
            suspectedCharacterId = "1093",
            iconConfidence = 0.503,
            iconMargin = 0.073,
        )
        assertEquals("1093", assisted?.characterId)

        val ambiguous = roleRewardSingleCharacterOcrAssist(
            observedText = "路",
            candidates = listOf(
                LabyrinthCharacterIconCandidate("1093", "露", "31", 0.503),
                LabyrinthCharacterIconCandidate("one", "怜", "31", 0.430),
            ),
            suspectedCharacterId = "1093",
            iconConfidence = 0.503,
            iconMargin = 0.073,
        )
        assertEquals(null, ambiguous)
    }

    @Test
    fun `complete multiline variant name separates variant from original`() {
        for (text in listOf("优衣\n（新年）", "优衣 ( 新年 )", "优衣 新年")) {
            val result = matcher.match(text, yuiVariants)
            assertTrue(text, result.trusted)
            assertEquals("new_year", result.characterId)
        }
    }

    @Test
    fun `truncated suffix must not turn variant into original`() {
        for (text in listOf("优衣（", "优衣）", "优衣（新", "优衣（新年")) {
            assertFalse(text, matcher.match(text, yuiVariants).trusted)
        }
    }

    @Test
    fun `core alone cannot choose among variants when original is not a candidate`() {
        assertFalse(matcher.match("优衣", yuiVariants.drop(1)).trusted)
    }

    @Test
    fun `unlisted variant and generic suffix do not select a same core rival`() {
        for (text in listOf("优衣（夏日）", "新年", "衣")) {
            assertFalse(text, matcher.match(text, yuiVariants).trusted)
        }
    }

    @Test
    fun `identical full names with different ids remain ambiguous`() {
        val result = matcher.match("优衣", listOf(
            LabyrinthCharacterNameCandidate("one", "优衣"),
            LabyrinthCharacterNameCandidate("two", "优衣"),
        ))
        assertFalse(result.trusted)
    }

    @Test
    fun `reward portrait ambiguity is resolved by complete name without lowering icon thresholds`() {
        val match = LabyrinthCharacterMatch(
            slotId = "role_reward_left", characterId = null, displayName = null,
            confidence = 0.438, rivalMargin = 0.010, trusted = false,
            screenRect = EntryPixelRect(0, 0, 128, 128),
            iconCandidates = yuiVariants.mapIndexed { index, candidate ->
                LabyrinthCharacterIconCandidate(candidate.characterId, candidate.displayName, "31", 0.438 - index * 0.010)
            } + LabyrinthCharacterIconCandidate("unrelated", "怜", "31", 0.20),
        )
        val nearby = roleRewardNameCandidates(match)
        assertEquals(3, nearby.size)
        val result = matcher.match("优衣", nearby.map {
            LabyrinthCharacterNameCandidate(it.characterId, it.displayName)
        })
        assertTrue(result.trusted)
        assertEquals("base", result.characterId)
        assertEquals(0.35, LABYRINTH_CHARACTER_ICON_SAFE_CONFIDENCE, 0.0)
        assertEquals(0.045, LABYRINTH_CHARACTER_ICON_SAFE_RIVAL_MARGIN, 0.0)
    }

    @Test
    fun `partial consecutive core name confirms icon candidate`() {
        val result = matcher.match("蕾西亚", candidates)

        assertTrue(result.trusted)
        assertEquals("1352", result.characterId)
        assertEquals("蕾西亚", result.matchedFragment)
    }

    @Test
    fun `two ordered discriminative core characters can confirm candidate`() {
        val result = matcher.match("普 西", candidates)

        assertTrue(result.trusted)
        assertEquals("1352", result.characterId)
    }

    @Test
    fun `one OCR substitution is tolerated when remaining core agrees`() {
        val result = matcher.match("普雷西亚", candidates)

        assertTrue(result.trusted)
        assertEquals("1352", result.characterId)
    }

    @Test
    fun `generic summer suffix alone never confirms role`() {
        val result = matcher.match("夏日", candidates)

        assertFalse(result.trusted)
        assertTrue(result.score <= LabyrinthCharacterNameFuzzyMatcher.VARIANT_ONLY_SCORE_CAP)
    }

    @Test
    fun `single partial character from a multi character core is insufficient`() {
        val result = matcher.match("蕾", candidates)

        assertFalse(result.trusted)
    }

    @Test
    fun `single character canonical name can be decisive among rivals`() {
        val result = matcher.match(
            observedText = "碧",
            candidates = listOf(
                LabyrinthCharacterNameCandidate("1042", "碧"),
                LabyrinthCharacterNameCandidate("1108", "铃奈"),
                LabyrinthCharacterNameCandidate("1009", "杏奈"),
            ),
        )

        assertTrue(result.trusted)
        assertEquals("1042", result.characterId)
    }

    @Test
    fun `exact alias resolves to canonical character id`() {
        val result = matcher.match(
            observedText = "诗夏",
            candidates = listOf(
                LabyrinthCharacterNameCandidate(
                    characterId = "1356",
                    displayName = "志那都",
                    aliases = listOf("シナツ", "Shinatsu", "诗夏"),
                ),
                LabyrinthCharacterNameCandidate("1012", "初音"),
                LabyrinthCharacterNameCandidate("1008", "珠希"),
            ),
        )

        assertTrue(result.trusted)
        assertEquals("1356", result.characterId)
        assertEquals("志那都", result.displayName)
        assertEquals(1.0, result.score, 0.0)
    }

    @Test
    fun `low confidence role reward keeps nearby candidates for name assistance`() {
        val match = LabyrinthCharacterMatch(
            slotId = "role_reward_center",
            characterId = null,
            displayName = null,
            confidence = 0.332557,
            screenRect = EntryPixelRect(841, 365, 240, 240),
            trusted = false,
            rivalMargin = 0.045064,
            suspectedCharacterId = "1012",
            suspectedDisplayName = "初音",
            iconCandidates = listOf(
                LabyrinthCharacterIconCandidate("1012", "初音", "31", 0.332557),
                LabyrinthCharacterIconCandidate("1253", "可可萝（游骑兵）", "31", 0.302796),
                LabyrinthCharacterIconCandidate("1146", "由加莉（圣诞节）", "31", 0.287493),
                LabyrinthCharacterIconCandidate("1051", "深月", "31", 0.279406),
                LabyrinthCharacterIconCandidate("1257", "花凛（炼金术师）", "31", 0.276375),
            ),
        )

        val candidates = roleRewardNameCandidates(match)
        val result = matcher.match(
            observedText = "由加莉\n（圣诞节）",
            candidates = candidates.map { candidate ->
                LabyrinthCharacterNameCandidate(candidate.characterId, candidate.displayName)
            },
        )

        assertEquals(5, candidates.size)
        assertTrue(result.trusted)
        assertEquals("1146", result.characterId)
    }
}
