package com.landosol.toolbox.labyrinth

import com.landosol.toolbox.labyrinth.vision.EntryPixelRect
import com.landosol.toolbox.labyrinth.vision.LabyrinthRelicMatch
import com.landosol.toolbox.labyrinth.vision.LabyrinthShopCategoryState
import com.landosol.toolbox.labyrinth.vision.LabyrinthShopItemKind
import com.landosol.toolbox.labyrinth.vision.LabyrinthShopItemMatch
import com.landosol.toolbox.labyrinth.vision.LabyrinthShopItemStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LabyrinthShopFlowPolicyTest {
    private val policy = LabyrinthShopPolicy()
    private val rect = EntryPixelRect(0, 0, 10, 10)

    @Test fun `priority scan blocks imprints and refresh while area four preserves immediate saving stop`() {
        for (area in listOf(3, 5)) {
            for (items in listOf(emptyList(), listOf(imprint()))) {
                val decision = decide(area, items, 10_000, complete = false, secondary = false)
                assertTrue("area=$area items=$items: $decision", decision is LabyrinthShopDecision.Wait)
                assertEquals(LabyrinthShopWaitKind.SCAN, (decision as LabyrinthShopDecision.Wait).kind)
            }
            assertTrue(decide(area, emptyList(), 10_000, complete = true) is LabyrinthShopDecision.Refresh)
            val unknownCoins = decide(area, listOf(imprint()), null, complete = false, secondary = false)
            assertEquals(LabyrinthShopWaitKind.COINS, (unknownCoins as LabyrinthShopDecision.Wait).kind)
        }
        for (coins in listOf(0, 7_999, 8_000)) {
            val unknownItem = imprint().copy(status = LabyrinthShopItemStatus.UNKNOWN,
                categoryState = LabyrinthShopCategoryState.READING)
            val decision = decide(4, listOf(unknownItem), coins, complete = false, secondary = false)
            assertTrue("coins=$coins: $decision", decision is LabyrinthShopDecision.Close)
            assertTrue((decision as LabyrinthShopDecision.Close).immediate)
        }
        val unconfirmed = decide(4, listOf(imprint()), null, complete = false, secondary = false)
        assertEquals(LabyrinthShopWaitKind.COINS, (unconfirmed as LabyrinthShopDecision.Wait).kind)
        assertTrue(decide(4, listOf(imprint()), 8_001, complete = false) is LabyrinthShopDecision.BuyRoleImprint)
        assertEquals(LabyrinthShopWaitKind.SCAN,
            (decide(4, emptyList(), 8_001, complete = false) as LabyrinthShopDecision.Wait).kind)
    }

    @Test fun `area three retains exact imprint and second refresh thresholds`() {
        data class Case(val refreshes: Int, val coins: Int, val hasImprint: Boolean, val expected: String)
        val cases = listOf(
            Case(0, 7_619, true, "refresh"), Case(0, 7_620, true, "imprint"),
            Case(1, 3_509, true, "refresh"), Case(1, 3_510, true, "imprint"),
            Case(1, 1_768, false, "close"), Case(1, 1_769, false, "refresh"),
            Case(2, 780, true, "imprint"), Case(2, 10_000, false, "close"),
        )
        for (case in cases) {
            val decision = decide(3, if (case.hasImprint) listOf(imprint()) else emptyList(),
                case.coins, refreshes = case.refreshes)
            assertEquals(case.toString(), case.expected, kind(decision))
        }
        for (refreshes in 0..2) {
            val decision = decide(3, listOf(imprint(), relic("one", 1)), 10_000, refreshes)
            assertEquals("one", (decision as LabyrinthShopDecision.BuyRelic).item.slotId)
        }
    }

    @Test fun `area five keeps one star priority then imprint before final cycle two star`() {
        data class Case(val refreshes: Int, val items: List<LabyrinthShopItemMatch>, val expected: String)
        val cases = (0..2).map { Case(it, listOf(imprint(), relic("two", 2), relic("one", 1)), "one") } +
            listOf(
                Case(0, listOf(imprint(), relic("two", 2)), "imprint"),
                Case(2, listOf(imprint(), relic("two", 2)), "imprint"),
                Case(1, listOf(relic("two", 2)), "refresh"),
                Case(2, listOf(relic("two", 2)), "two"),
                Case(2, listOf(relic("three", 3), relic("four", 4)), "three"),
            )
        for (case in cases) {
            val decision = decide(5, case.items, 10_000, case.refreshes)
            val actual = if (decision is LabyrinthShopDecision.BuyRelic) decision.item.slotId else kind(decision)
            assertEquals(case.toString(), case.expected, actual)
        }
        val topTwoStar = listOf(relic("two", 2))
        val beforeImprintPass = decide(5, topTwoStar, 10_000, refreshes = 2,
            complete = false, secondary = true, clearance = false)
        assertEquals(LabyrinthShopWaitKind.SCAN, (beforeImprintPass as LabyrinthShopDecision.Wait).kind)
        assertTrue(decide(5, listOf(imprint(), relic("two", 2)), 10_000, refreshes = 2,
            complete = false, secondary = true, clearance = false) is LabyrinthShopDecision.BuyRoleImprint)
        val afterImprintPass = decide(5, topTwoStar, 10_000, refreshes = 2,
            complete = false, secondary = true, clearance = true)
        assertEquals("two", (afterImprintPass as LabyrinthShopDecision.BuyRelic).item.slotId)
    }

    private fun decide(area: Int, items: List<LabyrinthShopItemMatch>, coins: Int?, refreshes: Int = 0,
        complete: Boolean = true, secondary: Boolean = true, clearance: Boolean = true) = policy.decide(
        currentArea = area, items = items, acquired = emptyList(), refreshAvailable = true,
        refreshesUsedInVisit = refreshes, coinBalance = coins,
        stockScanComplete = complete, allowSecondaryPurchases = secondary,
        allowClearancePurchases = clearance,
    )

    private fun kind(decision: LabyrinthShopDecision): String = when (decision) {
        is LabyrinthShopDecision.BuyRoleImprint -> "imprint"
        is LabyrinthShopDecision.BuyRelic -> "relic"
        is LabyrinthShopDecision.Refresh -> "refresh"
        is LabyrinthShopDecision.Close -> "close"
        is LabyrinthShopDecision.Wait -> "wait"
    }

    private fun imprint() = LabyrinthShopItemMatch(
        slotId = "imprint", screenRect = rect, buyButtonRect = rect, buyButtonScore = 0.9,
        buyButtonEnabledEvidence = 1.0, status = LabyrinthShopItemStatus.AVAILABLE,
        kind = LabyrinthShopItemKind.ROLE_IMPRINT, roleImprintLabel = "攻击型职能印记",
        choiceRoleImprint = false, categoryState = LabyrinthShopCategoryState.READY,
    )

    private fun relic(id: String, rarity: Int): LabyrinthShopItemMatch = LabyrinthShopItemMatch(
        slotId = id, screenRect = rect, buyButtonRect = rect, buyButtonScore = 0.9,
        buyButtonEnabledEvidence = 1.0, status = LabyrinthShopItemStatus.AVAILABLE,
        kind = LabyrinthShopItemKind.RELIC, categoryState = LabyrinthShopCategoryState.READY,
        relicMatch = LabyrinthRelicMatch(
            slotId = id, relicId = id, displayName = id, attribute = "会心", attributeBonus = "1",
            effect = null, rarity = rarity, confidence = 0.9, screenRect = rect, iconRect = rect,
            suspectedRelicId = id, suspectedDisplayName = id, suspectedAttribute = "会心", rivalMargin = 0.2,
        ),
    )
}
