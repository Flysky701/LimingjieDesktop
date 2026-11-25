package com.landosol.toolbox.labyrinth

import com.landosol.toolbox.labyrinth.vision.EntryPixelRect
import com.landosol.toolbox.labyrinth.vision.LabyrinthShopDialogState
import com.landosol.toolbox.labyrinth.vision.LabyrinthShopItemKind
import com.landosol.toolbox.labyrinth.vision.LabyrinthShopItemMatch
import com.landosol.toolbox.labyrinth.vision.LabyrinthShopItemStatus
import com.landosol.toolbox.labyrinth.vision.LabyrinthShopObservation
import com.landosol.toolbox.labyrinth.vision.LabyrinthShopRefreshDialogKind
import com.landosol.toolbox.labyrinth.vision.LabyrinthShopRefreshDialogText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LabyrinthShopRefreshTransactionTest {
    @Test fun `REQUESTED and confirmation are not commits and unchanged timeout cancels`() {
        for (confirm in listOf(false, true)) {
            val transaction = LabyrinthShopRefreshTransaction(timeoutMillis = 1_000)
            assertTrue(transaction.begin(100, 10_000, 7, 300))
            assertEquals(LabyrinthShopRefreshPhase.REQUESTED, transaction.pending!!.phase)
            assertFalse(transaction.begin(101, 10_000, 7, 600))
            if (confirm) assertTrue(transaction.confirmed(200))
            assertNull(transaction.lastSettled)
            assertEquals(LabyrinthShopRefreshOutcome.WAIT, transaction.observe(shop(10_000, 7), 500, 500))
            assertEquals(LabyrinthShopRefreshOutcome.CANCELLED, transaction.observe(shop(10_000, 7), 1_300, 1_300))
            assertNull(transaction.pending)
            assertEquals(LabyrinthShopRefreshOutcome.WAIT, transaction.observe(shop(9_700, 8), 1_400, 1_400))
        }
        val uncertain = LabyrinthShopRefreshTransaction(timeoutMillis = 1_000)
        assertTrue(uncertain.begin(100, 10_000, 7, 300))
        assertEquals(LabyrinthShopRefreshOutcome.WAIT, uncertain.observe(shop(9_700, 8, trusted = false), 500, 500))
        assertEquals(LabyrinthShopRefreshOutcome.UNRESOLVED, uncertain.observe(shop(9_700, 8, trusted = false), 1_200, 1_200))
        assertFalse(uncertain.begin(1_201, 9_700, 8, 600))
        assertEquals(LabyrinthShopRefreshPhase.UNRESOLVED, uncertain.pending!!.phase)
    }

    @Test fun `exact payment commits once with two distinct new frames and manual payment reconciles`() {
        for (confirm in listOf(false, true)) {
            val transaction = LabyrinthShopRefreshTransaction(timeoutMillis = 1_000)
            assertTrue(transaction.begin(100, 10_000, 7, 300))
            if (confirm) assertTrue(transaction.confirmed(200))
            val paid = shop(9_700, 8)
            assertEquals(LabyrinthShopRefreshOutcome.WAIT, transaction.observe(paid, 250, 100))
            assertEquals(LabyrinthShopRefreshOutcome.WAIT, transaction.observe(paid, 300, 300))
            assertEquals(LabyrinthShopRefreshOutcome.WAIT, transaction.observe(paid, 400, 300))
            assertEquals(LabyrinthShopRefreshOutcome.COMMITTED, transaction.observe(paid, 500, 500))
            assertNull(transaction.pending)
            assertEquals(LabyrinthShopRefreshOutcome.WAIT, transaction.observe(paid, 600, 600))
            assertTrue(transaction.begin(700, 9_700, 8, 600))
            assertEquals(LabyrinthShopRefreshOutcome.WAIT, transaction.observe(shop(9_400, 9), 800, 800))
            assertEquals(LabyrinthShopRefreshOutcome.UNRESOLVED, transaction.observe(shop(9_400, 9), 1_800, 1_800))
            assertFalse(transaction.begin(1_801, 9_400, 9, 600))
            assertEquals(LabyrinthShopRefreshOutcome.UNRESOLVED, transaction.observe(shop(9_100, 9), 1_900, 1_900))
            assertEquals(LabyrinthShopRefreshOutcome.COMMITTED, transaction.observe(shop(9_100, 9), 2_000, 2_000))
        }
        val scrolled = LabyrinthShopRefreshTransaction(timeoutMillis = 1_000)
        assertTrue(scrolled.begin(100, 10_000, 7, 300))
        assertTrue(scrolled.confirmed(200))
        assertEquals(LabyrinthShopRefreshOutcome.WAIT, scrolled.observe(shop(10_000, 8), 500, 500))
        assertEquals(LabyrinthShopRefreshOutcome.UNRESOLVED, scrolled.observe(shop(10_000, 8), 1_300, 1_300))
        assertTrue(scrolled.pending != null)
    }

    @Test fun `generic confirmation and exit never authorize refresh and fees require two OCR reads`() {
        val actual = "更新确认 要消耗阿尔法金币更新列表吗？ 所需阿尔法金币300 持有阿尔法金币7,460"
        assertTrue(LabyrinthShopRefreshDialogText.parseRegions(actual, 2, "300", 2).authorizesRefresh(300))
        assertFalse(LabyrinthShopRefreshDialogText.parseRegions(actual, 2, null, 2).authorizesRefresh(300))
        assertFalse(LabyrinthShopRefreshDialogText.parseRegions(actual, 2, "7,460", 2).authorizesRefresh(300))
        assertFalse(LabyrinthShopRefreshDialogText.parseRegions(actual, 1, "300", 2).authorizesRefresh(300))
        assertFalse(LabyrinthShopRefreshDialogText.parseRegions(actual, 2, "300", 1).authorizesRefresh(300))
        assertTrue(LabyrinthShopRefreshDialogText.parseRegions("更新确认", 2, "６００", 2).authorizesRefresh(600))
        assertFalse(LabyrinthShopRefreshDialogText.parseRegions(actual, 2, "600", 2).authorizesRefresh(300))
        assertFalse(LabyrinthShopRefreshDialogText.parseRegions("退出商店", 2, "300", 2).authorizesRefresh(300))
        assertFalse(LabyrinthShopRefreshDialogText.parse("确认", 2).authorizesRefresh(300))
        val first = LabyrinthShopRefreshDialogText.parse("更新商品，是否消耗300阿尔法金币？", 1)
        assertEquals(LabyrinthShopRefreshDialogKind.REFRESH, first.kind)
        assertEquals(300, first.fee)
        assertFalse(first.authorizesRefresh(300))
        assertTrue(LabyrinthShopRefreshDialogText.parse("更新商品，是否消耗300阿尔法金币？", 2).authorizesRefresh(300))
        assertTrue(LabyrinthShopRefreshDialogText.parse("刷新商品，费用：６００金币", 2).authorizesRefresh(600))
        assertFalse(LabyrinthShopRefreshDialogText.parse("刷新商品，费用600金币", 2).authorizesRefresh(300))
        assertFalse(LabyrinthShopRefreshDialogText.parse("更新商品", 2).authorizesRefresh(300))
        assertFalse(LabyrinthShopRefreshDialogText.parse("更新商品，费用900金币", 2).authorizesRefresh(300))
        assertFalse(LabyrinthShopRefreshDialogText.parse("更新商品，费用300金币或费用600金币", 2).authorizesRefresh(300))
        for (text in listOf("关闭商店", "离开商店，不再刷新，费用300金币", "离开这个商店，不再更新，费用300金币", "退出商店")) {
            val exit = LabyrinthShopRefreshDialogText.parse(text, 2)
            assertEquals(LabyrinthShopRefreshDialogKind.EXIT, exit.kind)
            assertFalse(exit.authorizesRefresh(300))
        }
    }

    @Test fun `area four pending random imprint waits only above 8000 and area three five gates stay intact`() {
        val policy = LabyrinthShopPolicy()
        val pending = imprint().copy(roleImprintLabel = null, imprintLabelPending = true)
        val saving = decide(policy, 4, listOf(pending), 8_000)
        assertTrue(saving is LabyrinthShopDecision.Close && saving.immediate)
        val wait = decide(policy, 4, listOf(pending), 8_001)
        assertTrue(wait is LabyrinthShopDecision.Wait && wait.kind == LabyrinthShopWaitKind.ITEMS)
        assertTrue(decide(policy, 4, listOf(pending.copy(choiceRoleImprint = true)), 8_001) is LabyrinthShopDecision.Close)
        assertTrue(decide(policy, 4, listOf(imprint()), 8_001) is LabyrinthShopDecision.BuyRoleImprint)
        for ((round, fee) in listOf(0 to 300, 1 to 600)) {
            for (coins in listOf(8_000 + fee - 1, 8_000 + fee)) {
                val blocked = decide(policy, 4, emptyList(), coins, round)
                assertTrue("would fall to saving line after fee", blocked is LabyrinthShopDecision.Close)
                assertTrue((blocked as LabyrinthShopDecision.Close).reason.contains("刷新后余额"))
            }
            assertTrue(decide(policy, 4, emptyList(), 8_000 + fee + 1, round) is LabyrinthShopDecision.Refresh)
            // The original rule still permits a purchase above the line, even if it falls below afterward.
            assertTrue(decide(policy, 4, listOf(imprint()), 8_001, round) is LabyrinthShopDecision.BuyRoleImprint)
        }
        for ((refreshes, below, threshold) in listOf(Triple(0, 7_619, 7_620), Triple(1, 3_509, 3_510))) {
            assertTrue(decide(policy, 3, listOf(imprint()), below, refreshes) is LabyrinthShopDecision.Refresh)
            assertTrue(decide(policy, 3, listOf(imprint()), threshold, refreshes) is LabyrinthShopDecision.BuyRoleImprint)
        }
        assertTrue(decide(policy, 3, emptyList(), 1_768, 1) is LabyrinthShopDecision.Close)
        assertTrue(decide(policy, 3, emptyList(), 1_769, 1) is LabyrinthShopDecision.Refresh)
        assertTrue(decide(policy, 5, listOf(imprint()), 780, 2) is LabyrinthShopDecision.BuyRoleImprint)
        assertTrue(decide(policy, 5, listOf(imprint().copy(choiceRoleImprint = true)), 10_000, 2) is LabyrinthShopDecision.BuyRoleImprint)
    }

    private fun shop(coins: Int, visual: Long, trusted: Boolean = true) = LabyrinthShopObservation(
        items = emptyList(), dialogState = LabyrinthShopDialogState.NONE,
        coinBalance = coins, coinBalanceConfirmed = trusted, visualFingerprint = visual,
    )

    private fun imprint(): LabyrinthShopItemMatch {
        val rect = EntryPixelRect(0, 0, 10, 10)
        return LabyrinthShopItemMatch(
            slotId = "imprint", screenRect = rect, buyButtonRect = rect,
            buyButtonScore = 0.9, status = LabyrinthShopItemStatus.AVAILABLE,
            kind = LabyrinthShopItemKind.ROLE_IMPRINT, roleImprintLabel = "攻击型职能印记",
        )
    }

    private fun decide(policy: LabyrinthShopPolicy, area: Int, items: List<LabyrinthShopItemMatch>, coins: Int,
        refreshes: Int = 0) = policy.decide(
        currentArea = area, items = items, acquired = emptyList(), refreshAvailable = true,
        refreshesUsedInVisit = refreshes, coinBalance = coins,
    )
}
