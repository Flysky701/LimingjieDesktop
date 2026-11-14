package com.landosol.toolbox.labyrinth

import com.landosol.toolbox.automation.ScreenPoint
import com.landosol.toolbox.labyrinth.vision.EntryPixelRect
import com.landosol.toolbox.labyrinth.vision.LabyrinthCharacterMatch
import com.landosol.toolbox.labyrinth.vision.LabyrinthRelicMatch
import org.junit.Assert.assertEquals
import org.junit.Test

class LabyrinthRunStateTest {
    @Test
    fun `roster merge preserves first seen order and deduplicates later pages`() {
        val firstPage = listOf(
            match("1209", "伊莉亚(新年)", 0.91),
            match("1257", "花凛(炼金术师)", 0.88),
        )
        val afterFirst = LabyrinthRosterMerger.merge(emptyList(), firstPage, nowMillis = 100L)

        val secondPage = listOf(
            match("1257", "花凛(炼金术师)", 0.94),
            match("1139", "纺希(万圣节)", 0.90),
        )
        val merged = LabyrinthRosterMerger.merge(afterFirst, secondPage, nowMillis = 200L)

        assertEquals(listOf("1209", "1257", "1139"), merged.map(LabyrinthJoinedCharacter::characterId))
        assertEquals(0, merged[0].orderIndex)
        assertEquals(1, merged[1].orderIndex)
        assertEquals(2, merged[2].orderIndex)
        assertEquals(0.94, merged[1].confidence, 0.0001)
        assertEquals(100L, merged[0].firstSeenAt)
        assertEquals(200L, merged[1].lastSeenAt)
    }

    @Test
    fun `unknown matches never enter the selectable roster`() {
        val merged = LabyrinthRosterMerger.merge(
            emptyList(),
            listOf(
                LabyrinthCharacterMatch(
                    slotId = "joined_first",
                    characterId = null,
                    displayName = null,
                    confidence = 0.31,
                    screenRect = EntryPixelRect(0, 0, 10, 10),
                ),
            ),
            nowMillis = 100L,
        )

        assertEquals(emptyList<LabyrinthJoinedCharacter>(), merged)
    }

    @Test
    fun `relic ownership is unique and replay cannot change recorded attributes`() {
        val first = LabyrinthRelicMerger.merge(
            emptyList(),
            listOf(
                relic("14004", "蛇紋のツルギ", "强化", 0.93),
                relic("12003", "暗剣ポワゾン", "弱体", 0.91),
            ),
            nowMillis = 100L,
        )
        val merged = LabyrinthRelicMerger.merge(
            first,
            listOf(relic("14004", "蛇紋のツルギ", "强化", 0.88)),
            nowMillis = 200L,
        )

        assertEquals(listOf("12003", "14004"), merged.map(LabyrinthObservedRelic::relicId))
        assertEquals(listOf("弱体", "强化"), merged.map(LabyrinthObservedRelic::attribute))
        val firstRelic = requireNotNull(merged.first { it.relicId == "14004" })
        assertEquals(0.93, firstRelic.confidence, 0.0001)
        assertEquals(first, merged)
        assertEquals(100L, firstRelic.firstSeenAt)
        assertEquals(100L, firstRelic.lastSeenAt)

        val repeated = LabyrinthRelicMerger.merge(
            first,
            listOf(relic("14004", "incorrect", "守备", 0.96), relic("14004", "incorrect", "守备", 0.96)),
            nowMillis = 300L,
        )
        val repeatedRelic = requireNotNull(repeated.first { it.relicId == "14004" })
        assertEquals(first, repeated)
        assertEquals("强化", repeatedRelic.attribute)
        assertEquals(100L, repeatedRelic.lastSeenAt)
        assertEquals(1, LabyrinthRelicChoicePolicy().markStacks(repeated + repeated)[LabyrinthRelicMark.ENHANCEMENT])
    }

    private fun match(id: String, name: String, confidence: Double) = LabyrinthCharacterMatch(
        slotId = "joined",
        characterId = id,
        displayName = name,
        confidence = confidence,
        screenRect = EntryPixelRect(0, 0, 10, 10),
    )

    private fun relic(id: String, name: String, attribute: String, confidence: Double) = LabyrinthRelicMatch(
        slotId = "relic_choice_1",
        relicId = id,
        displayName = name,
        attribute = attribute,
        attributeBonus = "1",
        effect = "",
        confidence = confidence,
        screenRect = EntryPixelRect(0, 0, 10, 10),
        iconRect = EntryPixelRect(0, 0, 10, 10),
    )
}
