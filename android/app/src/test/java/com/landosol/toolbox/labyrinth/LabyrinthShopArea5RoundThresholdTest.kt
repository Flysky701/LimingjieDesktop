package com.landosol.toolbox.labyrinth

import com.landosol.toolbox.labyrinth.vision.EntryPixelRect
import com.landosol.toolbox.labyrinth.vision.LabyrinthRelicMatch
import com.landosol.toolbox.labyrinth.vision.LabyrinthShopCategoryState
import com.landosol.toolbox.labyrinth.vision.LabyrinthShopItemKind
import com.landosol.toolbox.labyrinth.vision.LabyrinthShopItemMatch
import com.landosol.toolbox.labyrinth.vision.LabyrinthShopItemStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Locks the user-requested area-5 round-1/2 star gates. */
class LabyrinthShopArea5RoundThresholdTest {
    private val rect = EntryPixelRect(0, 0, 10, 10)

    @Test
    fun roundOneBelow15000BuysNoStarTwoOrStarThree() {
        val policy = LabyrinthShopPolicy()
        val items = mutableListOf(relic("r1", 1), relic("r2", 2), relic("r3", 3), imprint("random"))
        assertEquals("r1", (policy.decide(5, items.toList(), emptyList(), refreshAvailable = true, refreshesUsedInVisit = 0, coinBalance = 14_999) as LabyrinthShopDecision.BuyRelic).item.slotId)
        items.removeAt(0)
        assertTrue(policy.decide(5, items.toList(), emptyList(), refreshAvailable = true, refreshesUsedInVisit = 0, coinBalance = 14_999) is LabyrinthShopDecision.BuyRoleImprint)
        items.removeAt(items.indexOfFirst { it.kind == LabyrinthShopItemKind.ROLE_IMPRINT })
        val next = policy.decide(5, items.toList(), emptyList(), refreshAvailable = true, refreshesUsedInVisit = 0, coinBalance = 14_999)
        assertTrue(next is LabyrinthShopDecision.Refresh)
    }

    @Test
    fun roundOneAt15000BuysStarTwoOnly() {
        val policy = LabyrinthShopPolicy()
        val items = mutableListOf(relic("r1", 1), relic("r2", 2), relic("r3", 3), imprint("random"))
        items.removeAt(0)
        items.removeAt(items.indexOfFirst { it.kind == LabyrinthShopItemKind.ROLE_IMPRINT })
        assertEquals("r2", (policy.decide(5, items.toList(), emptyList(), refreshAvailable = true, refreshesUsedInVisit = 0, coinBalance = 15_000) as LabyrinthShopDecision.BuyRelic).item.slotId)
        items.removeAt(items.indexOfFirst { it.slotId == "r2" })
        assertTrue(policy.decide(5, items.toList(), emptyList(), refreshAvailable = true, refreshesUsedInVisit = 0, coinBalance = 15_000) is LabyrinthShopDecision.Refresh)
    }

    @Test
    fun roundOneAt17000BuysStarTwoThenStarThree() {
        val policy = LabyrinthShopPolicy()
        val items = mutableListOf(relic("r2", 2), relic("r3", 3))
        assertEquals("r2", (policy.decide(5, items.toList(), emptyList(), refreshAvailable = true, refreshesUsedInVisit = 0, coinBalance = 17_000) as LabyrinthShopDecision.BuyRelic).item.slotId)
        items.removeAt(0)
        assertEquals("r3", (policy.decide(5, items.toList(), emptyList(), refreshAvailable = true, refreshesUsedInVisit = 0, coinBalance = 17_000) as LabyrinthShopDecision.BuyRelic).item.slotId)
    }

    @Test
    fun roundTwoBelow12320BuysNoStarTwoOrStarThree() {
        val policy = LabyrinthShopPolicy()
        val items = mutableListOf(relic("r2", 2), relic("r3", 3))
        val next = policy.decide(5, items.toList(), emptyList(), refreshAvailable = true, refreshesUsedInVisit = 1, coinBalance = 12_319)
        assertTrue(next is LabyrinthShopDecision.Refresh)
    }

    @Test
    fun roundTwoAt12320BuysStarTwoOnly() {
        val policy = LabyrinthShopPolicy()
        val items = mutableListOf(relic("r2", 2), relic("r3", 3))
        assertEquals("r2", (policy.decide(5, items.toList(), emptyList(), refreshAvailable = true, refreshesUsedInVisit = 1, coinBalance = 12_320) as LabyrinthShopDecision.BuyRelic).item.slotId)
        items.removeAt(0)
        assertTrue(policy.decide(5, items.toList(), emptyList(), refreshAvailable = true, refreshesUsedInVisit = 1, coinBalance = 12_320) is LabyrinthShopDecision.Refresh)
    }

    @Test
    fun roundTwoAt14520BuysStarTwoThenStarThree() {
        val policy = LabyrinthShopPolicy()
        val items = mutableListOf(relic("r2", 2), relic("r3", 3))
        assertEquals("r2", (policy.decide(5, items.toList(), emptyList(), refreshAvailable = true, refreshesUsedInVisit = 1, coinBalance = 14_520) as LabyrinthShopDecision.BuyRelic).item.slotId)
        items.removeAt(0)
        assertEquals("r3", (policy.decide(5, items.toList(), emptyList(), refreshAvailable = true, refreshesUsedInVisit = 1, coinBalance = 14_520) as LabyrinthShopDecision.BuyRelic).item.slotId)
    }

    private fun relic(id: String, rarity: Int): LabyrinthShopItemMatch {
        val match = LabyrinthRelicMatch(
            slotId = id,
            relicId = id,
            displayName = id,
            attribute = "强化",
            attributeBonus = "5",
            effect = null,
            confidence = 0.95,
            screenRect = rect,
            iconRect = rect,
            rarity = rarity,
            suspectedRelicId = id,
            suspectedDisplayName = id,
            suspectedAttribute = "强化",
            rivalMargin = 0.2,
        )
        return LabyrinthShopItemMatch(
            slotId = id,
            screenRect = rect,
            buyButtonRect = rect,
            buyButtonScore = 0.9,
            buyButtonEnabledEvidence = 1.0,
            status = LabyrinthShopItemStatus.AVAILABLE,
            relicMatch = match,
            kind = LabyrinthShopItemKind.RELIC,
        )
    }

    private fun imprint(id: String): LabyrinthShopItemMatch = LabyrinthShopItemMatch(
        slotId = id,
        screenRect = rect,
        buyButtonRect = rect,
        buyButtonScore = 0.9,
        buyButtonEnabledEvidence = 1.0,
        status = LabyrinthShopItemStatus.AVAILABLE,
        kind = LabyrinthShopItemKind.ROLE_IMPRINT,
        roleImprintLabel = "增益型职能的随机印记",
        categoryState = LabyrinthShopCategoryState.READY,
    )
}
