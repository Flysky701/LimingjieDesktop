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

/** Locks the user-requested area-5 third-round order. */
class LabyrinthShopArea5Round3OrderTest {
    private val rect = EntryPixelRect(0, 0, 10, 10)
    private val policy = LabyrinthShopPolicy()

    @Test
    fun roundThreeBuysStarOneRandomImprintStarTwoStarThreeThenChoice() {
        val starOne = relic("r1", rarity = 1)
        val starTwo = relic("r2", rarity = 2)
        val starThree = relic("r3", rarity = 3)
        val random = imprint("random", choice = false)
        val choice = imprint("choice", choice = true)
        val items = mutableListOf(starOne, starTwo, starThree, random, choice)

        fun next(): LabyrinthShopDecision = policy.decide(
            currentArea = 5,
            items = items.toList(),
            acquired = emptyList(),
            refreshAvailable = false,
            refreshAvailabilityKnown = true,
            refreshesUsedInVisit = 2,
            coinBalance = 50_000,
            stockScanComplete = true,
        )

        val first = next()
        assertTrue(first is LabyrinthShopDecision.BuyRelic)
        assertEquals("r1", (first as LabyrinthShopDecision.BuyRelic).item.slotId)
        items.remove(starOne)

        val second = next()
        assertTrue(second is LabyrinthShopDecision.BuyRoleImprint)
        assertFalse((second as LabyrinthShopDecision.BuyRoleImprint).item.choiceRoleImprint)
        assertEquals("random", second.item.slotId)
        items.remove(random)

        val third = next()
        assertTrue(third is LabyrinthShopDecision.BuyRelic)
        assertEquals("r2", (third as LabyrinthShopDecision.BuyRelic).item.slotId)
        items.remove(starTwo)

        val fourth = next()
        assertTrue(fourth is LabyrinthShopDecision.BuyRelic)
        assertEquals("r3", (fourth as LabyrinthShopDecision.BuyRelic).item.slotId)
        items.remove(starThree)

        val fifth = next()
        assertTrue(fifth is LabyrinthShopDecision.BuyRoleImprint)
        assertTrue((fifth as LabyrinthShopDecision.BuyRoleImprint).item.choiceRoleImprint)
        assertEquals("choice", fifth.item.slotId)
        items.remove(choice)

        assertTrue(next() is LabyrinthShopDecision.Close)
    }

    @Test
    fun roundTwoBelowItsStarGateDoesNotBuyStarThreeOrChoiceImprint() {
        val decision = policy.decide(
            currentArea = 5,
            items = listOf(relic("r3", rarity = 3), imprint("choice", choice = true)),
            acquired = emptyList(),
            refreshAvailable = false,
            refreshAvailabilityKnown = true,
            refreshesUsedInVisit = 1,
            coinBalance = 12_000,
            stockScanComplete = true,
        )
        assertTrue(decision is LabyrinthShopDecision.Close)
    }

    @Test
    fun roundTwoNeverBuysChoiceImprintEvenWithHighBalance() {
        val decision = policy.decide(
            currentArea = 5,
            items = listOf(imprint("choice", choice = true)),
            acquired = emptyList(),
            refreshAvailable = false,
            refreshAvailabilityKnown = true,
            refreshesUsedInVisit = 1,
            coinBalance = 50_000,
            stockScanComplete = true,
        )
        assertTrue(decision is LabyrinthShopDecision.Close)
    }

    @Test
    fun roundThreeNeverBuysStarFour() {
        val decision = policy.decide(
            currentArea = 5,
            items = listOf(relic("r4", rarity = 4), imprint("choice", choice = true)),
            acquired = emptyList(),
            refreshAvailable = false,
            refreshAvailabilityKnown = true,
            refreshesUsedInVisit = 2,
            coinBalance = 50_000,
            stockScanComplete = true,
        )
        // ★4 is refused, but the low-value choice imprint is still the last fallback.
        assertTrue(decision is LabyrinthShopDecision.BuyRoleImprint)
        assertEquals("choice", (decision as LabyrinthShopDecision.BuyRoleImprint).item.slotId)
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

    private fun imprint(id: String, choice: Boolean): LabyrinthShopItemMatch = LabyrinthShopItemMatch(
        slotId = id,
        screenRect = rect,
        buyButtonRect = rect,
        buyButtonScore = 0.9,
        buyButtonEnabledEvidence = 1.0,
        status = LabyrinthShopItemStatus.AVAILABLE,
        kind = LabyrinthShopItemKind.ROLE_IMPRINT,
        roleImprintLabel = if (choice) "增益型职能的选择印记" else "增益型职能的随机印记",
        choiceRoleImprint = choice,
        categoryState = LabyrinthShopCategoryState.READY,
    )
}
