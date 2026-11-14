package com.landosol.toolbox.labyrinth

import com.landosol.toolbox.labyrinth.vision.EntryPixelRect
import com.landosol.toolbox.labyrinth.vision.LabyrinthRelicMatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LabyrinthDecisionPolicyTest {
    @Test
    fun `opening roster always resolves exactly three configured slots`() {
        // 王宫骑士团: its third slot still ships alternates, so it exercises the fallback ordering.
        // 咲恋救济院 used to play that role until its picks were fixed to one candidate each.
        val policy = requireNotNull(LabyrinthOpeningRosterCatalog.policyFor(4))

        val decision = policy.choose(setOf("1242", "1339", "1115")) as LabyrinthOpeningRosterDecision.Ready

        assertEquals(listOf("1242", "1339", "1115"), decision.characters.map { it.characterId })
        assertTrue(decision.reasons[2].contains("第2顺位替代"))
    }

    @Test
    fun `opening roster never fills a missing slot with an unrelated character`() {
        val policy = requireNotNull(LabyrinthOpeningRosterCatalog.policyFor(2))

        val decision = policy.choose(setOf("1089", "1088", "9999")) as LabyrinthOpeningRosterDecision.Missing

        assertEquals(listOf(3), decision.missingSlotNumbers)
        assertTrue(decision.reason.contains("禁止随机补位"))
    }

    @Test
    fun `immediate capstone overrides unfinished baseline even in early areas`() {
        val policy = LabyrinthRelicChoicePolicy()
        val acquired = listOf(
            acquired("加速", 4),
            acquired("会心", 2),
            acquired("守备", 3),
            acquired("弱体", 12),
        )

        val decision = requireNotNull(
            policy.choose(
                acquired,
                listOf(candidate("弱体", 3, "debuff"), candidate("会心", 2, "critical"), candidate("守备", 1, "defense")),
                currentArea = 1,
            ),
        )

        assertEquals(LabyrinthRelicChoicePhase.PIVOT_TO_DEBUFF_FIFTEEN, decision.phase)
        assertEquals("弱体", decision.choice.attribute)
    }

    @Test
    fun `baseline policy prefers larger bonus when both choices finish the same missing mark`() {
        val policy = LabyrinthRelicChoicePolicy()
        val acquired = listOf(
            acquired("加速", 4),
            acquired("会心", 3),
            acquired("守备", 4),
        )

        val decision = requireNotNull(
            policy.choose(
                acquired,
                listOf(
                    candidate("会心", 1, "critical-plus-one"),
                    candidate("会心", 2, "critical-plus-two"),
                ),
            ),
        )

        assertEquals("critical-plus-two", decision.choice.relicId)
        assertTrue(decision.reason.contains("会心"))
    }

    @Test
    fun `relic policy stops averaging and focuses the mark nearest fifteen`() {
        val policy = LabyrinthRelicChoicePolicy()
        val acquired = listOf(
            acquired("加速", 8),
            acquired("会心", 4),
            acquired("守备", 4),
            acquired("强化", 6),
            acquired("弱体", 5),
        )

        val decision = requireNotNull(
            policy.choose(acquired, listOf(candidate("加速", 2, "speed"), candidate("强化", 3, "enhance"))),
        )

        assertEquals(LabyrinthRelicChoicePhase.REACH_ANY_FIFTEEN, decision.phase)
        assertEquals(LabyrinthRelicMark.ACCELERATION, decision.focusMark)
        assertTrue(decision.reason.contains("停止平均发展"))
    }

    @Test
    fun `relic policy pivots to debuff when its fifteen stack payoff is close`() {
        val policy = LabyrinthRelicChoicePolicy()
        val acquired = listOf(
            acquired("加速", 13),
            acquired("会心", 4),
            acquired("守备", 4),
            acquired("弱体", 12),
        )

        val decision = requireNotNull(
            policy.choose(acquired, listOf(candidate("加速", 1, "speed"), candidate("弱体", 2, "debuff"))),
        )

        assertEquals(LabyrinthRelicChoicePhase.PIVOT_TO_DEBUFF_FIFTEEN, decision.phase)
        assertEquals(LabyrinthRelicMark.DEBUFF, decision.focusMark)
    }

    @Test
    fun `locked capstone focus does not switch when one choice omits that mark`() {
        val policy = LabyrinthRelicChoicePolicy()
        val acquired = listOf(
            acquired("加速", 8),
            acquired("会心", 4),
            acquired("守备", 4),
            acquired("强化", 7),
        )

        val decision = requireNotNull(
            policy.choose(
                acquired = acquired,
                candidates = listOf(candidate("强化", 3, "enhance"), candidate("会心", 2, "critical")),
                lockedFocus = LabyrinthRelicMark.ACCELERATION,
            ),
        )

        assertEquals(LabyrinthRelicMark.ACCELERATION, decision.focusMark)
        assertTrue(decision.reason.contains("不切换主追印记"))
    }

    @Test
    fun `relic policy takes any immediately reachable fifteen stack capstone`() {
        val policy = LabyrinthRelicChoicePolicy()
        val acquired = listOf(acquired("加速", 9), acquired("会心", 4), acquired("守备", 4), acquired("强化", 14))

        val decision = requireNotNull(
            policy.choose(acquired, listOf(candidate("加速", 2, "speed"), candidate("强化", 1, "enhance"))),
        )

        assertEquals("强化", decision.choice.attribute)
        assertTrue(decision.reason.contains("直接到达15层"))
    }

    @Test
    fun `relic policy refuses unrecognized marks instead of guessing`() {
        val policy = LabyrinthRelicChoicePolicy()

        assertNull(policy.choose(emptyList(), listOf(candidate("未知", 3, "unknown"))))
    }

    @Test
    fun `baseline preference fades after area three and when area is unknown`() {
        val owned = listOf(acquired("加速", 8), acquired("会心", 4), acquired("守备", 3))
        val choices = listOf(candidate("守备", 1, "defense"), candidate("加速", 1, "speed"))
        val policy = LabyrinthRelicChoicePolicy()
        for (area in 1..3) assertEquals("defense", policy.choose(owned, choices, currentArea = area)?.choice?.relicId)
        for (area in listOf(4, 5, null)) assertEquals("speed", policy.choose(owned, choices, currentArea = area)?.choice?.relicId)
    }

    @Test
    fun `early baseline is a bonus not a hard filter`() {
        val owned = listOf(acquired("加速", 8), acquired("会心", 1), acquired("守备", 4))
        val decision = LabyrinthRelicChoicePolicy().choose(owned,
            listOf(candidate("会心", 1, "critical"), candidate("加速", 3, "speed")), currentArea = 1)
        assertEquals("speed", decision?.choice?.relicId)
    }

    @Test
    fun `clearly better available candidate can displace locked focus`() {
        val decision = LabyrinthRelicChoicePolicy().choose(
            listOf(acquired("加速", 7), acquired("强化", 12)),
            listOf(candidate("加速", 1, "speed"), candidate("强化", 2, "enhance")),
            lockedFocus = LabyrinthRelicMark.ACCELERATION, currentArea = 5)
        assertEquals("enhance", decision?.choice?.relicId)
        assertEquals(LabyrinthRelicMark.ENHANCEMENT, decision?.focusMark)
    }

    @Test
    fun `already capped series does not defeat productive layers`() {
        val decision = LabyrinthRelicChoicePolicy().choose(listOf(acquired("守备", 15)),
            listOf(candidate("守备", 3, "capped"), candidate("强化", 1, "useful")), currentArea = 5)
        assertEquals("useful", decision?.choice?.relicId)
    }

    @Test
    fun `untrusted identity with populated metadata is still rejected`() {
        assertNull(LabyrinthRelicChoicePolicy().choose(emptyList(),
            listOf(candidate("守备", 3, "suspect").copy(relicId = null)), currentArea = 1))
    }

    @Test fun `already owned relic is excluded before scoring the remaining candidates`() {
        val owned = acquired("守备", 1)
        val repeated = candidate("守备", 3, owned.relicId)
        val available = candidate("强化", 1, "available")
        val choice = LabyrinthRelicChoicePolicy().choose(listOf(owned), listOf(repeated, available), currentArea = 1)
        assertEquals("available", choice?.choice?.relicId)
        assertNull(LabyrinthRelicChoicePolicy().choose(listOf(owned), listOf(repeated), currentArea = 1))
    }

    private fun acquired(mark: String, stacks: Int) = LabyrinthObservedRelic(
        relicId = "owned-$mark",
        displayName = "owned-$mark",
        attribute = mark,
        attributeBonus = stacks.toString(),
        effect = "",
        confidence = 1.0,
        firstSeenAt = 0L,
        lastSeenAt = 0L,
    )

    private fun candidate(mark: String, bonus: Int, id: String) = LabyrinthRelicMatch(
        slotId = id,
        relicId = id,
        displayName = id,
        attribute = mark,
        attributeBonus = bonus.toString(),
        effect = "",
        confidence = 1.0,
        screenRect = EntryPixelRect(0, 0, 100, 100),
        iconRect = EntryPixelRect(0, 0, 20, 20),
    )
}
