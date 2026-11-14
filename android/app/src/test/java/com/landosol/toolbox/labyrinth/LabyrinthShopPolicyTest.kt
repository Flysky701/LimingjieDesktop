package com.landosol.toolbox.labyrinth

import com.landosol.toolbox.labyrinth.vision.EntryPixelRect
import com.landosol.toolbox.labyrinth.vision.LabyrinthRelicMatch
import com.landosol.toolbox.labyrinth.vision.LabyrinthShopItemKind
import com.landosol.toolbox.labyrinth.vision.LabyrinthShopItemMatch
import com.landosol.toolbox.labyrinth.vision.LabyrinthShopItemStatus
import com.landosol.toolbox.labyrinth.vision.LabyrinthShopCategoryState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LabyrinthShopPolicyTest {
    private val rect = EntryPixelRect(0, 0, 10, 10)

    @Test fun `purchase switch closes even with available relic and refresh`() {
        val decision = LabyrinthShopPolicy(buyRelics = false).decide(5,
            listOf(item("r1", "会心", 1, true)), emptyList(), refreshAvailable = true)
        assertTrue(decision is LabyrinthShopDecision.Close)
    }

    @Test fun `refresh preference supports disabling or starting earlier`() {
        assertTrue(LabyrinthShopPolicy(refreshEnabled = false).decide(5, emptyList(), emptyList(),
            refreshAvailable = true) is LabyrinthShopDecision.Close)
        val policy = LabyrinthShopPolicy(finalArea = 3)
        assertTrue(policy.decide(2, emptyList(), emptyList(), refreshAvailable = true) is LabyrinthShopDecision.Close)
        assertTrue(policy.decide(3, emptyList(), emptyList(), refreshAvailable = true,
            relicPurchasesInCycle = 3) is LabyrinthShopDecision.Refresh)
        assertTrue(policy.decide(null, emptyList(), emptyList(), refreshAvailable = true) is LabyrinthShopDecision.Close)
    }

    @Test fun `configured baseline deadline changes actual relic choice`() {
        val stacks = mapOf(LabyrinthRelicMark.ACCELERATION to 8, LabyrinthRelicMark.CRITICAL to 4,
            LabyrinthRelicMark.DEFENSE to 3)
        val items = listOf(item("defense", "守备", 1, true), item("speed", "加速", 1, true))
        fun choose(end: Int) = (LabyrinthShopPolicy(LabyrinthStrategySettings(baselineLastArea = end).relicPolicy())
            .decide(3, items, emptyList(), stacks, false) as LabyrinthShopDecision.BuyRelic).relicDecision.choice.relicId
        assertEquals("defense", choose(3))
        assertEquals("speed", choose(2))
    }

    @Test
    fun `buys a recognized relic before considering refresh`() {
        val policy = LabyrinthShopPolicy()
        val decision = policy.decide(
            currentArea = 5,
            items = listOf(item("r1", "会心", 1, recognized = true)),
            acquired = emptyList(),
            refreshAvailable = true,
        )
        assertTrue(decision is LabyrinthShopDecision.BuyRelic)
    }

    @Test
    fun `does not refresh before final area`() {
        val policy = LabyrinthShopPolicy()
        val decision = policy.decide(
            currentArea = 3,
            items = listOf(item("role", null, null, recognized = false, confidence = 0.2)),
            acquired = emptyList(),
            refreshAvailable = true,
        )
        assertTrue(decision is LabyrinthShopDecision.Close)
    }

    @Test
    fun `unclassified affordable good does not prove an exhausted relic stock cycle`() {
        val policy = LabyrinthShopPolicy()
        val decision = policy.decide(
            currentArea = 5,
            items = listOf(item("role", null, null, recognized = false, confidence = 0.2)),
            acquired = emptyList(),
            refreshAvailable = true,
        )
        assertTrue(decision is LabyrinthShopDecision.Close)
    }

    @Test
    fun `ambiguous relic-like icon blocks refresh`() {
        val policy = LabyrinthShopPolicy()
        val decision = policy.decide(
            currentArea = 5,
            items = listOf(item("maybe", "会心", 1, recognized = false, confidence = 0.70)),
            acquired = emptyList(),
            refreshAvailable = true,
        )
        assertTrue(decision is LabyrinthShopDecision.Wait)
    }

    @Test
    fun `disabled recognized relic is never purchased and does not block close or refresh`() {
        val disabled = item(
            "too-expensive",
            "会心",
            1,
            recognized = true,
            status = LabyrinthShopItemStatus.DISABLED,
        )
        assertTrue(
            LabyrinthShopPolicy().decide(
                currentArea = 3,
                items = listOf(disabled),
                acquired = emptyList(),
                refreshAvailable = false,
            ) is LabyrinthShopDecision.Close,
        )
        assertTrue(
            LabyrinthShopPolicy().decide(
                currentArea = 5,
                items = listOf(disabled),
                acquired = emptyList(),
                refreshAvailable = true,
            ) is LabyrinthShopDecision.Close,
        )
    }

    @Test
    fun `recognized relic outranks purchasable role imprint`() {
        val imprint = item(
            "imprint",
            "守备",
            1,
            recognized = false,
            confidence = 0.72,
            kind = LabyrinthShopItemKind.ROLE_IMPRINT,
            roleImprintLabel = "破防型职能印记",
        )
        val decision = LabyrinthShopPolicy().decide(
            currentArea = 5,
            items = listOf(imprint, item("relic", "会心", 1, recognized = true)),
            acquired = emptyList(),
            refreshAvailable = true,
            relicPurchasesInCycle = 2,
        )
        assertTrue(decision is LabyrinthShopDecision.BuyRelic)
    }

    @Test
    fun `completed three relic cycle refresh outranks role imprint`() {
        val imprint = item(
            "imprint",
            "守备",
            1,
            recognized = false,
            confidence = 0.72,
            kind = LabyrinthShopItemKind.ROLE_IMPRINT,
            roleImprintLabel = "坦克型职能印记",
        )
        val decision = LabyrinthShopPolicy().decide(
            currentArea = 5,
            items = listOf(imprint),
            acquired = emptyList(),
            refreshAvailable = true,
            relicPurchasesInCycle = 3,
        )
        assertTrue(decision is LabyrinthShopDecision.Refresh)
    }

    @Test
    fun `three visible imprints recover exhausted relic stock after session restart`() {
        val items = (1..3).map { index ->
            item(
                "imprint$index",
                "守备",
                1,
                recognized = false,
                confidence = 0.72,
                kind = LabyrinthShopItemKind.ROLE_IMPRINT,
                roleImprintLabel = if (index == 1) "减益型职能印记" else null,
            )
        }
        val decision = LabyrinthShopPolicy().decide(
            currentArea = 5,
            items = items,
            acquired = emptyList(),
            refreshAvailable = true,
            relicPurchasesInCycle = 0,
        )
        assertTrue(decision is LabyrinthShopDecision.Refresh)
        assertTrue((decision as LabyrinthShopDecision.Refresh).reason.contains("恢复"))
    }

    @Test
    fun `sold out row refreshes without purchase count or title recognition`() {
        for (category in LabyrinthShopCategoryState.entries) {
            for (purchased in 0..2) {
                val decision = LabyrinthShopPolicy().decide(
                    currentArea = 5,
                    items = disabledRow().map { it.copy(categoryState = category) },
                    acquired = emptyList(),
                    refreshAvailable = true,
                    relicPurchasesInCycle = purchased,
                )
                assertTrue("category=$category purchased=$purchased: $decision", decision is LabyrinthShopDecision.Refresh)
            }
        }
    }

    @Test
    fun `depleted row follows every configured starting area except the area four saving stop`() {
        for (start in 1..5) {
            for (area in 1..5) {
                val decision = LabyrinthShopPolicy(finalArea = start).decide(
                    area, disabledRow(), emptyList(), refreshAvailable = true,
                )
                val expectRefresh = area != 4 && area >= start
                assertEquals("start=$start area=$area: $decision", expectRefresh, decision is LabyrinthShopDecision.Refresh)
            }
        }
        // Area 4 is a hard saving stop: no refresh licence and no relic purchase, even when the
        // starting area was lowered to 4 and an affordable relic is on the shelf.
        for (start in 1..5) {
            val policy = LabyrinthShopPolicy(finalArea = start)
            assertTrue(
                "start=$start area=4 refresh",
                policy.decide(4, disabledRow(), emptyList(), refreshAvailable = true) is LabyrinthShopDecision.Close,
            )
            assertTrue(
                "start=$start area=4 buy",
                policy.decide(
                    4, listOf(item("relic", "会心", 1, true)), emptyList(), refreshAvailable = true,
                ) is LabyrinthShopDecision.Close,
            )
        }
    }

    @Test
    fun `area three stops refreshing after its single refill budget`() {
        val policy = LabyrinthShopPolicy(finalArea = 3)
        assertTrue(
            "first refill",
            policy.decide(
                3, disabledRow(), emptyList(), refreshAvailable = true, refreshesUsedInVisit = 0,
            ) is LabyrinthShopDecision.Refresh,
        )
        // A second 300-coin refill in area 3 used to return a row the remaining balance could not
        // buy from, so the budget ends the visit instead.
        val spent = policy.decide(
            3, disabledRow(), emptyList(), refreshAvailable = true, refreshesUsedInVisit = 1,
        )
        assertTrue("second refill: $spent", spent is LabyrinthShopDecision.Close)
    }

    @Test
    fun `areas without a refill budget keep refreshing on every depleted row`() {
        val policy = LabyrinthShopPolicy()
        for (used in listOf(0, 1, 2, 5)) {
            assertTrue(
                "used=$used",
                policy.decide(
                    5, disabledRow(), emptyList(), refreshAvailable = true, refreshesUsedInVisit = used,
                ) is LabyrinthShopDecision.Refresh,
            )
        }
    }

    @Test
    fun `area three spends the remaining coins on an imprint instead of a second refill`() {
        val imprint = item("imprint", null, null, false,
            kind = LabyrinthShopItemKind.ROLE_IMPRINT, roleImprintLabel = "坦克型职能印记")
        val row = disabledRow().take(2) + imprint
        val policy = LabyrinthShopPolicy(finalArea = 3)
        assertTrue(
            policy.decide(
                3, row, emptyList(), refreshAvailable = true, refreshesUsedInVisit = 0,
            ) is LabyrinthShopDecision.Refresh,
        )
        assertTrue(
            policy.decide(
                3, row, emptyList(), refreshAvailable = true, refreshesUsedInVisit = 1,
            ) is LabyrinthShopDecision.BuyRoleImprint,
        )
    }

    @Test
    fun `depleted row respects disabled settings unknown area and insufficient refresh funds`() {
        assertTrue(LabyrinthShopPolicy(refreshEnabled = false).decide(
            5, disabledRow(), emptyList(), refreshAvailable = true,
        ) is LabyrinthShopDecision.Close)
        assertTrue(LabyrinthShopPolicy(buyRelics = false).decide(
            5, disabledRow(), emptyList(), refreshAvailable = true,
        ) is LabyrinthShopDecision.Close)
        assertTrue(LabyrinthShopPolicy().decide(
            null, disabledRow(), emptyList(), refreshAvailable = true,
        ) is LabyrinthShopDecision.Close)
        assertTrue(LabyrinthShopPolicy().decide(
            5, disabledRow(), emptyList(), refreshAvailable = false,
        ) is LabyrinthShopDecision.Close)
    }

    @Test
    fun `depleted row refresh outranks affordable imprint even with incomplete purchase count`() {
        val imprint = item("imprint", null, null, false, kind = LabyrinthShopItemKind.ROLE_IMPRINT,
            roleImprintLabel = "坦克型职能印记")
        val row = disabledRow().take(2) + imprint
        assertTrue(LabyrinthShopPolicy().decide(
            5, row, emptyList(), refreshAvailable = true, relicPurchasesInCycle = 1,
        ) is LabyrinthShopDecision.Refresh)
        assertTrue(LabyrinthShopPolicy().decide(
            5, row, emptyList(), refreshAvailable = false, relicPurchasesInCycle = 1,
        ) is LabyrinthShopDecision.BuyRoleImprint)
    }

    @Test
    fun `unknown refresh anchor waits instead of closing or buying imprint`() {
        for (purchased in listOf(0, 3)) {
            assertTrue(LabyrinthShopPolicy().decide(
                5, disabledRow(), emptyList(), refreshAvailable = false,
                relicPurchasesInCycle = purchased, refreshAvailabilityKnown = false,
            ) is LabyrinthShopDecision.Wait)
        }
        val imprints = (1..3).map { item("imprint$it", null, null, false,
            kind = LabyrinthShopItemKind.ROLE_IMPRINT, roleImprintLabel = "坦克型职能印记") }
        assertTrue(LabyrinthShopPolicy().decide(
            5, imprints, emptyList(), refreshAvailable = false, refreshAvailabilityKnown = false,
        ) is LabyrinthShopDecision.Wait)
        assertTrue(LabyrinthShopPolicy(refreshEnabled = false).decide(
            5, disabledRow(), emptyList(), refreshAvailable = false, refreshAvailabilityKnown = false,
        ) is LabyrinthShopDecision.Close)
    }

    @Test
    fun `depletion recovery does not skip an affordable relic or unread item`() {
        val policy = LabyrinthShopPolicy()
        val disabled = disabledRow().take(2)
        assertTrue(policy.decide(5, disabled + item("relic", "会心", 1, true), emptyList(),
            refreshAvailable = true) is LabyrinthShopDecision.BuyRelic)
        val reading = item("reading", null, null, false).copy(categoryState = LabyrinthShopCategoryState.READING)
        assertTrue(policy.decide(5, disabled + reading, emptyList(),
            refreshAvailable = true) is LabyrinthShopDecision.Wait)
        assertTrue(policy.decide(5, disabled + item("unread", null, null, false), emptyList(),
            refreshAvailable = true) !is LabyrinthShopDecision.Refresh)
        assertTrue(policy.decide(5, disabled + disabledRow().last().copy(status = LabyrinthShopItemStatus.UNKNOWN),
            emptyList(), refreshAvailable = true) is LabyrinthShopDecision.Wait)
        assertTrue(policy.decide(5, disabled, emptyList(),
            refreshAvailable = true) !is LabyrinthShopDecision.Refresh)
    }

    private fun disabledRow() = (1..3).map { index ->
        item("sold$index", null, null, false, status = LabyrinthShopItemStatus.DISABLED)
    }

    @Test
    fun `unknown imprint class is never bought blindly when refresh is unavailable`() {
        val imprint = item(
            "imprint",
            "守备",
            1,
            recognized = false,
            confidence = 0.72,
            kind = LabyrinthShopItemKind.ROLE_IMPRINT,
            roleImprintLabel = null,
        )
        val decision = LabyrinthShopPolicy().decide(
            currentArea = 5,
            items = listOf(imprint),
            acquired = emptyList(),
            refreshAvailable = false,
        )
        assertTrue(decision is LabyrinthShopDecision.Close)
    }

    @Test
    fun `role imprint is bought only after no relic purchase or completed cycle refresh remains`() {
        val imprint = item(
            "imprint",
            "守备",
            1,
            recognized = false,
            confidence = 0.72,
            kind = LabyrinthShopItemKind.ROLE_IMPRINT,
            roleImprintLabel = "增益型职能印记",
        )
        val decision = LabyrinthShopPolicy().decide(
            currentArea = 5,
            items = listOf(
                item("too-expensive", "会心", 1, recognized = true, status = LabyrinthShopItemStatus.DISABLED),
                imprint,
            ),
            acquired = emptyList(),
            refreshAvailable = false,
            relicPurchasesInCycle = 2,
        ) as LabyrinthShopDecision.BuyRoleImprint
        assertEquals("增益型职能印记", decision.label)
    }

    @Test
    fun `confirmed role imprint suppresses misleading ambiguous relic score`() {
        val imprint = item(
            "shop_item_3",
            "守备",
            1,
            recognized = false,
            confidence = 0.72,
            kind = LabyrinthShopItemKind.ROLE_IMPRINT,
            roleImprintLabel = "破防型职能印记",
        )
        val decision = LabyrinthShopPolicy().decide(
            currentArea = 3,
            items = listOf(imprint),
            acquired = emptyList(),
            refreshAvailable = false,
        )
        assertTrue(decision is LabyrinthShopDecision.BuyRoleImprint)
    }

    @Test
    fun `area three closes after its first three relic purchases even when refill goods look relic-like`() {
        val policy = LabyrinthShopPolicy()
        val decision = policy.decide(
            currentArea = 3,
            items = listOf(
                item("role1", "守备", 1, recognized = false, confidence = 0.72),
                item("role2", "守备", 1, recognized = false, confidence = 0.72),
                item("role3", "守备", 1, recognized = false, confidence = 0.72),
            ),
            acquired = emptyList(),
            refreshAvailable = true,
            relicPurchasesInCycle = 3,
        )
        assertTrue(decision is LabyrinthShopDecision.Close)
    }

    @Test
    fun `committed purchase count permits refresh while an incomplete count still checks ambiguous goods`() {
        val policy = LabyrinthShopPolicy()
        val ambiguous = listOf(item("role", "守备", 1, recognized = false, confidence = 0.72))
        assertTrue(
            policy.decide(
                currentArea = 5,
                items = ambiguous,
                acquired = emptyList(),
                refreshAvailable = true,
                relicPurchasesInCycle = 2,
            ) is LabyrinthShopDecision.Wait,
        )
        assertTrue(
            policy.decide(
                currentArea = 5,
                items = ambiguous,
                acquired = emptyList(),
                refreshAvailable = true,
                relicPurchasesInCycle = 3,
            ) is LabyrinthShopDecision.Refresh,
        )
    }

    @Test
    fun `area four never buys even a safely recognized relic`() {
        val decision = LabyrinthShopPolicy().decide(
            currentArea = 4,
            items = listOf(item("r1", "会心", 1, recognized = true)),
            acquired = emptyList(),
            refreshAvailable = true,
        )
        assertTrue(decision is LabyrinthShopDecision.Close)
    }

    @Test
    fun `re-evaluation is slot independent after shop compacts items`() {
        val policy = LabyrinthShopPolicy()
        val first = policy.decide(
            currentArea = 2,
            items = listOf(item("slot1", "会心", 1, recognized = true)),
            acquired = emptyList(),
            refreshAvailable = false,
        ) as LabyrinthShopDecision.BuyRelic
        val next = policy.decide(
            currentArea = 2,
            items = listOf(item("slot1", "守备", 1, recognized = true)),
            acquired = emptyList(),
            refreshAvailable = false,
        ) as LabyrinthShopDecision.BuyRelic
        assertEquals("会心", first.relicDecision.choice.attribute)
        assertEquals("守备", next.relicDecision.choice.attribute)
    }

    @Test
    fun `shop uses the same area aware priorities as reward choices`() {
        val stacks = mapOf(LabyrinthRelicMark.ACCELERATION to 8, LabyrinthRelicMark.CRITICAL to 4,
            LabyrinthRelicMark.DEFENSE to 3)
        fun choose(area: Int) = (LabyrinthShopPolicy().decide(area,
            listOf(item("defense", "守备", 1, true), item("speed", "加速", 1, true)),
            emptyList(), stacks, false) as LabyrinthShopDecision.BuyRelic).relicDecision.choice.relicId
        assertEquals("defense", choose(3))
        assertEquals("speed", choose(5))
    }

    @Test
    fun `areas skip three and four star relics until area five clears out`() {
        val policy = LabyrinthShopPolicy()
        // Area 3 never buys a refused relic.
        assertTrue("area 3 must not buy a ★3 relic",
            policy.decide(3, listOf(item("rare", "会心", 2, recognized = true, rarity = 3)),
                emptyList(), refreshAvailable = false) is LabyrinthShopDecision.Close)
        // Area 5 clears a ★3 relic out once the refill button is dead.
        assertTrue("area 5 must clear out a ★3 relic after the refill dies",
            policy.decide(5, listOf(item("rare", "会心", 2, recognized = true, rarity = 3)),
                emptyList(), refreshAvailable = false) is LabyrinthShopDecision.BuyRelic)
        // ★4 stays refused even during the clear-out.
        assertTrue("★4 stays refused in area 5",
            policy.decide(5, listOf(item("rare", "会心", 2, recognized = true, rarity = 4)),
                emptyList(), refreshAvailable = false) is LabyrinthShopDecision.Close)
    }

    @Test
    fun `area 3 and area 5 still buy one and two star relics`() {
        val policy = LabyrinthShopPolicy()
        for (area in intArrayOf(3, 5)) {
            for (rarity in intArrayOf(1, 2)) {
                val decision = policy.decide(
                    currentArea = area,
                    items = listOf(item("cheap", "会心", 1, recognized = true, rarity = rarity)),
                    acquired = emptyList(),
                    refreshAvailable = false,
                )
                assertTrue(
                    "area $area must buy a ★$rarity relic: $decision",
                    decision is LabyrinthShopDecision.BuyRelic,
                )
            }
        }
    }

    @Test
    fun `areas outside the rarity gate keep the unrestricted behaviour`() {
        val policy = LabyrinthShopPolicy()
        val decision = policy.decide(
            currentArea = 2,
            items = listOf(item("rare", "会心", 2, recognized = true, rarity = 3)),
            acquired = emptyList(),
            refreshAvailable = false,
        )
        assertTrue("area 2 has no rarity gate: $decision", decision is LabyrinthShopDecision.BuyRelic)
    }

    @Test
    fun `a skipped star row still falls through to refresh and imprint fallbacks`() {
        val policy = LabyrinthShopPolicy()
        // 区域5：三个位都是被跳过的 ★3，遗物已买满 → 刷新优先于关店。
        val refresh = policy.decide(
            currentArea = 5,
            items = listOf(
                item("s1", "会心", 2, recognized = true, rarity = 3),
                item("s2", "守备", 1, recognized = true, rarity = 4),
                item("s3", "强化", 1, recognized = true, rarity = 3),
            ),
            acquired = emptyList(),
            refreshAvailable = true,
            relicPurchasesInCycle = 3,
        )
        assertTrue("exhausted cycle with only gated relics must refresh: $refresh",
            refresh is LabyrinthShopDecision.Refresh)
        // 区域3：不能刷新 → 印记兜底。
        val imprint = policy.decide(
            currentArea = 3,
            items = listOf(
                item("s1", "会心", 2, recognized = true, rarity = 3),
                item("s2", null, null, recognized = false, kind = LabyrinthShopItemKind.ROLE_IMPRINT,
                    roleImprintLabel = "增益型职能印记"),
            ),
            acquired = emptyList(),
            refreshAvailable = false,
        )
        assertTrue("area 3 must fall through to the imprint fallback: $imprint",
            imprint is LabyrinthShopDecision.BuyRoleImprint)
    }

    @Test
    fun `area 3 skips choice imprints while area 5 only clears them out after the refill dies`() {
        val policy = LabyrinthShopPolicy()
        val choiceOnly = item("s1", null, null, recognized = false,
            kind = LabyrinthShopItemKind.ROLE_IMPRINT,
            roleImprintLabel = "坦克型职能印记", choiceRoleImprint = true)
        assertTrue("area 3 must skip a role-specific 选择 imprint",
            policy.decide(3, listOf(choiceOnly), emptyList(), refreshAvailable = false)
                is LabyrinthShopDecision.Close)
        assertTrue("area 5 clears the choice imprint out once the refill dies",
            policy.decide(5, listOf(choiceOnly), emptyList(), refreshAvailable = false)
                is LabyrinthShopDecision.BuyRoleImprint)
    }

    @Test
    fun `random imprints are still bought inside the gated areas`() {
        val policy = LabyrinthShopPolicy()
        for (area in intArrayOf(3, 5)) {
            val decision = policy.decide(
                currentArea = area,
                items = listOf(item("s1", null, null, recognized = false,
                    kind = LabyrinthShopItemKind.ROLE_IMPRINT,
                    roleImprintLabel = "坦克型职能印记", choiceRoleImprint = false)),
                acquired = emptyList(),
                refreshAvailable = false,
            )
            assertTrue("area $area must buy a random imprint: $decision",
                decision is LabyrinthShopDecision.BuyRoleImprint)
        }
    }

    @Test
    fun `the all-roster choice imprint is not gated`() {
        val policy = LabyrinthShopPolicy()
        val decision = policy.decide(
            currentArea = 3,
            items = listOf(item("s1", null, null, recognized = false,
                kind = LabyrinthShopItemKind.ROLE_IMPRINT,
                roleImprintLabel = "全体选择印记", choiceRoleImprint = true)),
            acquired = emptyList(),
            refreshAvailable = false,
        )
        assertTrue("全体选择印记 keeps the fallback behaviour: $decision",
            decision is LabyrinthShopDecision.BuyRoleImprint)
    }

    @Test
    fun `areas outside the imprint gate keep buying choice imprints`() {
        val policy = LabyrinthShopPolicy()
        val decision = policy.decide(
            currentArea = 2,
            items = listOf(item("s1", null, null, recognized = false,
                kind = LabyrinthShopItemKind.ROLE_IMPRINT,
                roleImprintLabel = "坦克型职能印记", choiceRoleImprint = true)),
            acquired = emptyList(),
            refreshAvailable = false,
        )
        assertTrue("area 2 has no imprint gate: $decision",
            decision is LabyrinthShopDecision.BuyRoleImprint)
    }

    @Test
    fun `a refused star relic no longer keeps the row from refilling`() {
        // 2026-11-01 C2 field log (area 5): 極醒护身符 ★3 purchasable on the shelf, a role-specific
        // 选择 imprint beside it, a sold-out third slot, 7,760 coins and a usable 300-coin refill.
        // The buy branch refuses the ★3 and the imprint gate refuses the 选择 imprint, so the row
        // had nothing actionable left -- yet the refill predicate still counted the ★3 as buyable
        // stock and the run closed the shop instead of refilling.
        val policy = LabyrinthShopPolicy()
        val decision = policy.decide(
            currentArea = 5,
            items = listOf(
                item("rare3", "强化", 3, recognized = true, rarity = 3),
                item("choice", null, null, recognized = false,
                    kind = LabyrinthShopItemKind.ROLE_IMPRINT,
                    roleImprintLabel = "减益型职能印记", choiceRoleImprint = true),
                item("gone", null, null, recognized = false,
                    status = LabyrinthShopItemStatus.DISABLED),
            ),
            acquired = emptyList(),
            refreshAvailable = true,
            relicPurchasesInCycle = 2,
        )
        assertTrue("a row with only refused goods must refill: $decision",
            decision is LabyrinthShopDecision.Refresh)
    }

    @Test
    fun `an unresolved affordable slot still waits before refilling`() {
        val policy = LabyrinthShopPolicy()
        val decision = policy.decide(
            currentArea = 5,
            items = listOf(
                item("rare3", "强化", 3, recognized = true, rarity = 3),
                item("pending", null, null, recognized = false,
                    categoryState = LabyrinthShopCategoryState.READING),
                item("gone", null, null, recognized = false,
                    status = LabyrinthShopItemStatus.DISABLED),
            ),
            acquired = emptyList(),
            refreshAvailable = true,
            relicPurchasesInCycle = 2,
        )
        assertTrue("an unread affordable slot must not be skipped by a refill: $decision",
            decision is LabyrinthShopDecision.Wait)
    }

    @Test
    fun `a refused star relic does not outrank an affordable one`() {
        val policy = LabyrinthShopPolicy()
        val decision = policy.decide(
            currentArea = 5,
            items = listOf(
                item("rare3", "强化", 3, recognized = true, rarity = 3),
                item("two", "加速", 1, recognized = true, rarity = 2),
                item("choice", null, null, recognized = false,
                    kind = LabyrinthShopItemKind.ROLE_IMPRINT,
                    roleImprintLabel = "减益型职能印记", choiceRoleImprint = true),
            ),
            acquired = emptyList(),
            refreshAvailable = true,
            relicPurchasesInCycle = 2,
        )
        assertTrue("the ★2 relic must still be bought: $decision",
            decision is LabyrinthShopDecision.BuyRelic)
        assertEquals("two", (decision as LabyrinthShopDecision.BuyRelic).item.relicMatch?.relicId)
    }

    @Test
    fun `area 3 closes instead of refilling a refused row`() {
        // Area 3 may not refill at all (refreshFromArea = 5), so the same row must end the visit
        // rather than spend coins the policy never allowed there.
        val policy = LabyrinthShopPolicy()
        val decision = policy.decide(
            currentArea = 3,
            items = listOf(
                item("rare3", "强化", 3, recognized = true, rarity = 3),
                item("choice", null, null, recognized = false,
                    kind = LabyrinthShopItemKind.ROLE_IMPRINT,
                    roleImprintLabel = "减益型职能印记", choiceRoleImprint = true),
                item("gone", null, null, recognized = false,
                    status = LabyrinthShopItemStatus.DISABLED),
            ),
            acquired = emptyList(),
            refreshAvailable = true,
            relicPurchasesInCycle = 2,
        )
        assertTrue("area 3 must not refill: $decision", decision is LabyrinthShopDecision.Close)
    }

    @Test
    fun `area 5 clear-out buys the refused star three relic once the refill dies`() {
        val policy = LabyrinthShopPolicy()
        val decision = policy.decide(
            currentArea = 5,
            items = listOf(
                item("rare3", "强化", 2, recognized = true, rarity = 3),
                item("gone1", null, null, recognized = false, status = LabyrinthShopItemStatus.DISABLED),
                item("gone2", null, null, recognized = false, status = LabyrinthShopItemStatus.DISABLED),
            ),
            acquired = emptyList(),
            refreshAvailable = false,
            relicPurchasesInCycle = 1,
        )
        assertTrue("area 5 must clear out a ★3 relic after the refill dies: $decision",
            decision is LabyrinthShopDecision.BuyRelic)
    }

    @Test
    fun `area 5 clear-out buys a choice imprint after everything cheaper is gone`() {
        val policy = LabyrinthShopPolicy()
        val decision = policy.decide(
            currentArea = 5,
            items = listOf(
                item("choice", null, null, recognized = false,
                    kind = LabyrinthShopItemKind.ROLE_IMPRINT,
                    roleImprintLabel = "破防型职能印记", choiceRoleImprint = true),
                item("gone1", null, null, recognized = false, status = LabyrinthShopItemStatus.DISABLED),
                item("gone2", null, null, recognized = false, status = LabyrinthShopItemStatus.DISABLED),
            ),
            acquired = emptyList(),
            refreshAvailable = false,
            relicPurchasesInCycle = 1,
        )
        assertTrue("area 5 must clear out a choice imprint after the refill dies: $decision",
            decision is LabyrinthShopDecision.BuyRoleImprint)
    }

    @Test
    fun `area 5 clear-out prefers the star three relic over the choice imprint`() {
        val policy = LabyrinthShopPolicy()
        val decision = policy.decide(
            currentArea = 5,
            items = listOf(
                item("rare3", "强化", 2, recognized = true, rarity = 3),
                item("choice", null, null, recognized = false,
                    kind = LabyrinthShopItemKind.ROLE_IMPRINT,
                    roleImprintLabel = "破防型职能印记", choiceRoleImprint = true),
                item("gone", null, null, recognized = false, status = LabyrinthShopItemStatus.DISABLED),
            ),
            acquired = emptyList(),
            refreshAvailable = false,
            relicPurchasesInCycle = 1,
        )
        assertTrue("★3 (12.35/point) must go before the choice imprint (21/point): $decision",
            decision is LabyrinthShopDecision.BuyRelic)
    }

    @Test
    fun `area 5 clear-out still buys the random imprint before the star three relic`() {
        val policy = LabyrinthShopPolicy()
        val decision = policy.decide(
            currentArea = 5,
            items = listOf(
                item("rare3", "强化", 2, recognized = true, rarity = 3),
                item("imprint", null, null, recognized = false,
                    kind = LabyrinthShopItemKind.ROLE_IMPRINT,
                    roleImprintLabel = "坦克型职能印记"),
                item("gone", null, null, recognized = false, status = LabyrinthShopItemStatus.DISABLED),
            ),
            acquired = emptyList(),
            refreshAvailable = false,
            relicPurchasesInCycle = 1,
        )
        assertTrue("the random imprint (9.00/point) must go before ★3 (12.35/point): $decision",
            decision is LabyrinthShopDecision.BuyRoleImprint)
    }

    @Test
    fun `area 5 clear-out stays locked while the refill is still available`() {
        val policy = LabyrinthShopPolicy()
        val decision = policy.decide(
            currentArea = 5,
            items = listOf(
                item("rare3", "强化", 2, recognized = true, rarity = 3),
                item("gone1", null, null, recognized = false, status = LabyrinthShopItemStatus.DISABLED),
                item("gone2", null, null, recognized = false, status = LabyrinthShopItemStatus.DISABLED),
            ),
            acquired = emptyList(),
            refreshAvailable = true,
            relicPurchasesInCycle = 1,
        )
        assertTrue("a live refill must still be preferred over a ★3 relic: $decision",
            decision is LabyrinthShopDecision.Refresh)
    }

    @Test
    fun `area 3 never clears out refused goods`() {
        val policy = LabyrinthShopPolicy(finalArea = 3)
        val decision = policy.decide(
            currentArea = 3,
            items = listOf(
                item("rare3", "强化", 2, recognized = true, rarity = 3),
                item("gone1", null, null, recognized = false, status = LabyrinthShopItemStatus.DISABLED),
                item("gone2", null, null, recognized = false, status = LabyrinthShopItemStatus.DISABLED),
            ),
            acquired = emptyList(),
            refreshAvailable = false,
            relicPurchasesInCycle = 1,
        )
        assertTrue("only area 5 clears out: $decision", decision is LabyrinthShopDecision.Close)
    }

    @Test
    fun `area 5 clear-out still refuses the star four relic`() {
        val policy = LabyrinthShopPolicy()
        val decision = policy.decide(
            currentArea = 5,
            items = listOf(
                item("rare4", "强化", 3, recognized = true, rarity = 4),
                item("gone1", null, null, recognized = false, status = LabyrinthShopItemStatus.DISABLED),
                item("gone2", null, null, recognized = false, status = LabyrinthShopItemStatus.DISABLED),
            ),
            acquired = emptyList(),
            refreshAvailable = false,
            relicPurchasesInCycle = 1,
        )
        assertTrue("★4 stays refused at 32.5 coins/point: $decision",
            decision is LabyrinthShopDecision.Close)
    }

    /**
     * 2026-11-09 bundle: area 5 shop, refill already dark, one purchasable role imprint whose title
     * OCR only produced "职能印记" without a role name. The policy used to close the visit with
     * coins left; it must wait on the remaining title crops instead, and only close once the
     * recognition grace window has expired.
     */
    @Test fun `area 5 waits on an imprint whose role name is still being read`() {
        val policy = LabyrinthShopPolicy()
        fun row(pending: Boolean, label: String? = null) = listOf(
            item("1", null, null, recognized = false, status = LabyrinthShopItemStatus.DISABLED),
            item("2", null, null, recognized = false, kind = LabyrinthShopItemKind.ROLE_IMPRINT,
                roleImprintLabel = label, imprintLabelPending = pending),
            item("3", null, null, recognized = false, status = LabyrinthShopItemStatus.DISABLED),
        )
        fun decide(items: List<LabyrinthShopItemMatch>) = policy.decide(
            currentArea = 5,
            items = items,
            acquired = emptyList(),
            refreshAvailable = false,
            relicPurchasesInCycle = 3,
            refreshAvailabilityKnown = true,
        )

        val waiting = decide(row(pending = true))
        assertTrue(waiting is LabyrinthShopDecision.Wait)
        assertTrue((waiting as LabyrinthShopDecision.Wait).reason.contains("职能名"))

        // Once recognition recovers the role name the same row is bought instead of closed.
        val recovered = decide(row(pending = false, label = "治疗型职能印记"))
        assertEquals("2", (recovered as LabyrinthShopDecision.BuyRoleImprint).item.slotId)

        // Grace window over: the visit closes so the run can never park on an unreadable title.
        assertTrue(decide(row(pending = false)) is LabyrinthShopDecision.Close)
    }

    /** Area 3 reports the same symptom, and it refreshes nothing, so the wait matters there too. */
    @Test fun `area 3 waits on an unread role name before closing`() {
        val policy = LabyrinthShopPolicy()
        val row = listOf(
            item("1", null, null, recognized = false, status = LabyrinthShopItemStatus.DISABLED),
            item("2", null, null, recognized = false, kind = LabyrinthShopItemKind.ROLE_IMPRINT,
                imprintLabelPending = true),
            item("3", null, null, recognized = false, status = LabyrinthShopItemStatus.DISABLED),
        )
        val waiting = policy.decide(
            currentArea = 3,
            items = row,
            acquired = emptyList(),
            refreshAvailable = false,
            relicPurchasesInCycle = 3,
            refreshAvailabilityKnown = true,
        )
        assertTrue(waiting is LabyrinthShopDecision.Wait)
    }

    @Test fun `a nameless imprint never outranks a purchasable one`() {
        val policy = LabyrinthShopPolicy()
        val decision = policy.decide(
            currentArea = 5,
            items = listOf(
                item("1", null, null, recognized = false, kind = LabyrinthShopItemKind.ROLE_IMPRINT,
                    imprintLabelPending = true),
                item("2", null, null, recognized = false, kind = LabyrinthShopItemKind.ROLE_IMPRINT,
                    roleImprintLabel = "增益型职能印记"),
            ),
            acquired = emptyList(),
            refreshAvailable = false,
        )
        assertEquals("2", (decision as LabyrinthShopDecision.BuyRoleImprint).item.slotId)
    }

    private fun item(
        id: String,
        mark: String?,
        bonus: Int?,
        recognized: Boolean,
        confidence: Double = if (recognized) 0.9 else 0.2,
        status: LabyrinthShopItemStatus = LabyrinthShopItemStatus.AVAILABLE,
        kind: LabyrinthShopItemKind = if (recognized) LabyrinthShopItemKind.RELIC else LabyrinthShopItemKind.UNKNOWN,
        roleImprintLabel: String? = null,
        rarity: Int = 0,
        choiceRoleImprint: Boolean = false,
        categoryState: LabyrinthShopCategoryState = LabyrinthShopCategoryState.READY,
        imprintLabelPending: Boolean = false,
    ): LabyrinthShopItemMatch {
        val relic = LabyrinthRelicMatch(
            slotId = id,
            relicId = if (recognized) id else null,
            displayName = if (recognized) id else null,
            attribute = if (recognized) mark else null,
            attributeBonus = if (recognized) bonus?.toString() else null,
            effect = null,
            rarity = rarity,
            confidence = confidence,
            screenRect = rect,
            iconRect = rect,
            suspectedRelicId = id,
            suspectedDisplayName = id,
            suspectedAttribute = mark,
            rivalMargin = if (recognized) 0.2 else 0.01,
        )
        return LabyrinthShopItemMatch(
            slotId = id,
            screenRect = rect,
            buyButtonRect = rect,
            buyButtonScore = 0.9,
            buyButtonEnabledEvidence = if (status == LabyrinthShopItemStatus.AVAILABLE) 1.0 else 0.0,
            status = status,
            relicMatch = relic,
            kind = kind,
            roleImprintLabel = roleImprintLabel,
            choiceRoleImprint = choiceRoleImprint,
            categoryState = categoryState,
            imprintLabelPending = imprintLabelPending,
        )
    }
}
