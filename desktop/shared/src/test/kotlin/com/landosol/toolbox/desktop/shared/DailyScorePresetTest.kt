package com.landosol.toolbox.desktop.shared

import com.landosol.toolbox.gamedata.RelicResource
import com.landosol.toolbox.labyrinth.*
import com.landosol.toolbox.labyrinth.vision.*
import com.landosol.toolbox.protocol.labyrinth.LabyrinthMapNode
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DailyScorePresetTest {
    @get:Rule val folder = TemporaryFolder()
    private val rect = EntryPixelRect(0, 0, 10, 10)

    @Test fun openingRelicsDoNotDuplicateObservedInventory() {
        val policy = LabyrinthRelicChoicePolicy()
        val route = LabyrinthRouteJson(15L, 1, 5, 1, false, false, "EITHER",
            emptyList(), emptyList(), emptyList(), openingRelicIds = setOf(40001, 50001))
        policy.useOpening(route, listOf(RelicResource(id = "40001", mark = "守备", markBonus = "1"),
            RelicResource(id = "50001", mark = "强化", markBonus = "1")))
        val seen = LabyrinthObservedRelic("40001", "", "守备", "1", "", 1.0, 0, 0, 1)
        val stacks = policy.markStacks(listOf(seen))
        assertEquals(1, stacks[LabyrinthRelicMark.DEFENSE])
        assertEquals(1, stacks[LabyrinthRelicMark.ENHANCEMENT])
        assertEquals(4, policy.markStacks(listOf(seen),
            mapOf(LabyrinthRelicMark.DEFENSE to 4))[LabyrinthRelicMark.DEFENSE])
        policy.useOpening(null, emptyList())
        assertEquals(0, policy.markStacks(emptyList())[LabyrinthRelicMark.ENHANCEMENT])
    }

    @Test fun refreshAndEventLimitsSurviveReconnectAndIsolateAccounts() {
        fun open() = ScoreRunLedger(DesktopContext(folder.root))
        val ledger = open()
        ledger.refreshPending(1, 10, 30)
        assertEquals(Int.MAX_VALUE, open().refreshes(1, 10, 30))
        ledger.refreshConfirmed(1, 10, 30)
        ledger.refreshConfirmed(1, 10, 30)
        assertEquals(1, open().refreshes(1, 10, 30))
        assertEquals(0, open().refreshes(2, 10, 30))
        assertEquals(0, open().refreshes(1, 11, 30))
        assertTrue(ledger.allowEventRestart(1, 10, 1_000))
        assertFalse(open().allowEventRestart(1, 10, 2_000))
        assertTrue(open().allowEventRestart(1, 11, 2_000))
        assertFalse(open().allowEventRestart(1, 12, 3_000))
        assertTrue(open().allowEventRestart(2, 12, 3_000))
    }

    @Test fun pricesAreStrictAndBudgetCanReserveAnotherPurchase() {
        assertNull(shopAmount("300/500"))
        assertNull(shopAmount("1.600"))
        assertNull(shopAmount("999,999,999"))
        assertEquals(1600, shopAmount("1,600"))
        fun item(id: String, price: Int, bonus: Int) = LabyrinthShopItemMatch(id, rect, rect, 1.0, 1.0,
            LabyrinthShopItemStatus.AVAILABLE, LabyrinthRelicMatch(id, id, id, "加速", bonus.toString(), "", 1.0, rect, iconRect = rect),
            LabyrinthShopItemKind.RELIC, categoryState = LabyrinthShopCategoryState.READY, price = price)
        val goods = listOf(item("a", 1100, 3), item("b", 700, 2), item("c", 500, 1))
        val observation = LabyrinthShopObservation(goods, wallet = 1600, refreshFee = 300)
        val policy = LabyrinthShopPolicy(highScore = true)
        fun buy(visits: Int) = (policy.decide(5, goods, emptyList(), refreshAvailable = true, shop = observation,
            visitRefreshes = visits) as LabyrinthShopDecision.BuyRelic).item
        assertEquals("b", buy(0).slotId)
        assertEquals("a", buy(2).slotId)
        assertTrue(policy.decide(5,goods,emptyList(),refreshAvailable = true,shop = observation.copy(readingPrices = true)) is LabyrinthShopDecision.Wait)
        assertFalse(LabyrinthStrategySettings().highScorePreset)
    }

    @Test fun freeChoiceUsesMissingEffectsWithoutChangingShopTies() {
        val policy = LabyrinthRelicChoicePolicy()
        fun relic(id: String, effect: String) =
            LabyrinthRelicMatch(id, id, id, "弱体", "1", effect, 1.0, rect, iconRect = rect)
        val options = listOf(relic("first", "普通"), relic("second", "使敌人中毒"))
        assertEquals("first", policy.choose(emptyList(), options)?.choice?.relicId)
        assertEquals("second", policy.choose(emptyList(), options, freeReward = true)?.choice?.relicId)
        val boss = LabyrinthNodeJson(3, 7, 1, 30701, 8, 770330501, emptyList(), true)
        val route = LabyrinthRouteJson(15L, 1, 5, 1, false, false, "EITHER",
            emptyList(), emptyList(), listOf(boss))
        policy.useOpening(route, emptyList())
        val defensive = listOf(relic("shield", "护盾"), relic("heal", "持续恢复HP（每秒恢复【守备】数量 ×500）"))
        assertEquals("heal", policy.choose(emptyList(), defensive, currentArea = 2, freeReward = true)?.choice?.relicId)
        assertEquals("shield", policy.choose(emptyList(), defensive, currentArea = 4, freeReward = true)?.choice?.relicId)
        val owned = LabyrinthObservedRelic("other", "", "弱体", "1", "已经具有中毒", 1.0, 0, 0, 1)
        assertEquals("first", policy.choose(listOf(owned), options, freeReward = true)?.choice?.relicId)
    }

    @Test fun presetRoundTripsAndOldSettingsUseDaily() {
        assertFalse(LabyrinthStrategySettingsCodec.decode("{}").highScorePreset)
        assertEquals(8, LabyrinthStrategySettingsCodec.decode("""{"highScorePreset":true}""").scoreMapAttempts)
        val score = LabyrinthStrategySettings(highScorePreset = true, playerWeight = 66, scoreMapAttempts = 12)
        assertEquals(score, LabyrinthStrategySettingsCodec.decode(LabyrinthStrategySettingsCodec.encode(score)))
    }

    @Test fun mapCapUsesTheRunDifficultyAndOnlyAnEnabledAccountLimit() {
        val account = LabyrinthRerollSettings(difficulty = 5, maxAttempts = "3", rerollUntilFound = true)
        val preset = LabyrinthStrategySettings(highScorePreset = true, scoreMapAttempts = 12)
        val score = account.toConfig(1, preset)
        assertTrue(score.routePolicy.highScore)
        assertFalse(score.rerollUntilFound)
        assertEquals(12, score.maxAttempts)
        assertEquals(3, account.copy(rerollUntilFound = false).toConfig(1, preset).maxAttempts)
        assertEquals(1, account.toConfig(1, preset.copy(scoreMapAttempts = 1)).maxAttempts)
        assertEquals(800, account.toConfig(1, preset.copy(scoreMapAttempts = 800)).maxAttempts)
        assertEquals(account.toConfig(1), account.toConfig(1, preset.copy(highScorePreset = false)))
        val otherDifficulty = account.copy(difficulty = 4)
        assertEquals(otherDifficulty.toConfig(1), otherDifficulty.toConfig(1, preset))
        for (invalid in listOf(0, 801)) assertNotNull(preset.copy(scoreMapAttempts = invalid).validationError())
    }

    @Test fun sixExRouteRequiresCrossAreaEdgesAndExactCounts() {
        val lengths = listOf(6,7,7,8,8)
        val ex = listOf(emptySet(), setOf(3), setOf(3), setOf(3,4), setOf(3,4))
        val nodes = lengths.flatMapIndexed { i, end -> (1..end).map { col ->
            val area = i + 1
            val id = area * 100L + col
            val type = when {
                col == 1 -> 1
                col == end && area in setOf(3,5) -> 8
                col in ex[i] -> 3
                (area to col) in setOf(1 to 5,2 to 2) -> 5
                (area to col) == (2 to 4) -> 7
                else -> 4
            }
            val next = when { col < end -> listOf(id+1); area < 5 -> listOf((area+1)*100L+1); else -> emptyList() }
            LabyrinthMapNode(area,col,1,id,type,null,next,col==end)
        } }
        val policy = LabyrinthRoutePolicy(area3BossIds = emptySet(), area5BossIds = emptySet(), highScore = true)
        assertTrue(highScoreRoute(1,nodes,policy,null) is RouteSearchResult.Found)
        assertEquals(RouteSearchResult.NoRoute, highScoreRoute(1,nodes.map {
            if (it.blockId == 207L) it.copy(nextBlockIds = emptyList()) else it
        },policy,null))
        assertEquals(RouteSearchResult.NoRoute, highScoreRoute(1,nodes.map {
            if (it.blockId == 403L) it.copy(blockType = 4) else it
        },policy,null))
    }
}
