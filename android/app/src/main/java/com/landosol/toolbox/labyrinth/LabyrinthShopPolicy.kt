package com.landosol.toolbox.labyrinth

import com.landosol.toolbox.labyrinth.vision.LabyrinthShopItemMatch
import com.landosol.toolbox.labyrinth.vision.LabyrinthShopItemKind
import com.landosol.toolbox.labyrinth.vision.LabyrinthShopCategoryState
import com.landosol.toolbox.labyrinth.vision.LabyrinthShopObservation
import com.landosol.toolbox.labyrinth.vision.LabyrinthShopItemStatus

sealed interface LabyrinthShopDecision {
    data class BuyRelic(
        val item: LabyrinthShopItemMatch,
        val relicDecision: LabyrinthRelicChoiceDecision,
    ) : LabyrinthShopDecision

    data class BuyRoleImprint(
        val item: LabyrinthShopItemMatch,
        val label: String,
        val reason: String,
    ) : LabyrinthShopDecision {
        /** 选择印记 opens a full-roster picker after purchase; 随机印记 grants a role outright. */
        val opensRolePicker: Boolean get() = item.choiceRoleImprint
    }

    data class Refresh(val reason: String) : LabyrinthShopDecision
    data class Close(val reason: String) : LabyrinthShopDecision
    data class Wait(val reason: String) : LabyrinthShopDecision
}

/**
 * Shop policy for route execution.
 *
 * Rules requested by the live Dry Run:
 * - relics always outrank role-imprint goods;
 * - refresh follows the configured starting area and enable switch;
 * - completed relic cycles, or a fully observed row with no actionable relic, can refresh;
 * - when no relic can still be bought and another relic cycle cannot be refreshed, a safely
 *   identified purchasable role imprint may consume the remaining coins and enter role selection;
 * - every live good passes bounded title classification before purchase; unresolved goods are
 *   skipped after three crop variants and never count as purchased relic stock.
 *
 * The visible row is evaluated again after every completed purchase. The game compacts later
 * goods into the open slot, so the next decision intentionally does not remember a physical slot.
 */
class LabyrinthShopPolicy(
    private val relicChoicePolicy: LabyrinthRelicChoicePolicy = LabyrinthRelicChoicePolicy(),
    private val finalArea: Int = 5,
    private val noPurchaseAreas: Set<Int> = setOf(4),
    private val relicsPerStockCycle: Int = 3,
    private val ambiguousRelicLikeConfidence: Double = 0.45,
    private val buyRelics: Boolean = true,
    private val refreshEnabled: Boolean = true,
    val highScore: Boolean = false,
) {
    fun decide(
        currentArea: Int?,
        items: List<LabyrinthShopItemMatch>,
        acquired: List<LabyrinthObservedRelic>,
        calibratedStacks: Map<LabyrinthRelicMark, Int> = emptyMap(),
        refreshAvailable: Boolean,
        lockedFocus: LabyrinthRelicMark? = null,
        relicPurchasesInCycle: Int = 0,
        refreshAvailabilityKnown: Boolean = true,
        shop: LabyrinthShopObservation? = null,
        visitRefreshes: Int = 0,
    ): LabyrinthShopDecision {
        if (!highScore || shop == null) return ordinary(currentArea, items, acquired, calibratedStacks,
            refreshAvailable, lockedFocus, relicPurchasesInCycle, refreshAvailabilityKnown)
        if (!buyRelics || currentArea == null || currentArea == 4) return LabyrinthShopDecision.Close("凹分预设：本店不购买")
        val limit = when (currentArea) { 3 -> 1; 5 -> 2; else -> 0 }
        val canRefresh = refreshEnabled && refreshAvailable && visitRefreshes < limit
        if (shop.readingPrices) return LabyrinthShopDecision.Wait("凹分：正在复核本店价格")
        val wallet = shop.wallet
        val goods = items.filter { it.purchasable && it.categoryState == LabyrinthShopCategoryState.READY &&
            (it.relicMatch?.recognized == true || it.kind == LabyrinthShopItemKind.ROLE_IMPRINT && !it.roleImprintLabel.isNullOrBlank()) }
        if (wallet == null || items.any { it.purchasable && (it.price == null || it.categoryState != LabyrinthShopCategoryState.READY) }) {
            return ordinary(currentArea, items, acquired, calibratedStacks, false, lockedFocus, relicPurchasesInCycle, true)
        }
        val cheapest = goods.minOfOrNull { it.price!! }
        val fee = shop.refreshFee?.takeIf { it > 0 }
        val reserve = if (canRefresh && fee != null && cheapest != null && wallet >= fee + cheapest) fee + cheapest else 0
        val clearance = currentArea == 5 && reserve == 0
        val candidates = goods.filter { item ->
            val roleAllowed = !item.choiceRoleImprint || currentArea == 5 && reserve == 0
            val rarity = relicChoicePolicy.rarity(item.relicMatch?.relicId)
            val skipExpensive = currentArea in setOf(3, 5) && (rarity == 4 || rarity == 3 && !clearance)
            roleAllowed && !skipExpensive && item.price!! <= wallet - reserve
        }
        // The game exposes three slots. Enumerate these baskets; buy one item and re-read after compaction.
        val stacks = relicChoicePolicy.markStacks(acquired, calibratedStacks)
        var best = emptyList<LabyrinthShopItemMatch>()
        var bestGain = 0
        var bestCost = 0
        var bestProgress = 0
        if (candidates.size <= 3) for (mask in 1 until (1 shl candidates.size)) {
            val basket = candidates.filterIndexed { i, _ -> mask and (1 shl i) != 0 }
            val cost = basket.sumOf { it.price!! }
            if (cost > wallet - reserve) continue
            val gain = basket.size + basket.count { it.kind != LabyrinthShopItemKind.ROLE_IMPRINT }
            val bonuses = basket.mapNotNull { it.relicMatch }.groupBy { LabyrinthRelicMark.fromLabel(it.attribute) }
            val progress = bonuses.entries.sumOf { (mark, matches) ->
                val before = stacks[mark] ?: return@sumOf 0
                val after = (before + matches.sumOf { it.attributeBonus?.toIntOrNull() ?: 0 }).coerceAtMost(15)
                (after - before).coerceAtLeast(0) + listOf(4, 9, 15).count { before < it && it <= after } * 15
            }
            if (gain > bestGain || gain == bestGain && (progress > bestProgress || progress == bestProgress && cost < bestCost)) {
                best = basket
                bestGain = gain
                bestCost = cost
                bestProgress = progress
            }
        }
        val relic = relicChoicePolicy.choose(acquired, best.filterNot { it.kind == LabyrinthShopItemKind.ROLE_IMPRINT }.mapNotNull { it.relicMatch }, lockedFocus, calibratedStacks, currentArea)
        if (relic != null) return LabyrinthShopDecision.BuyRelic(best.first { it.relicMatch?.relicId == relic.choice.relicId }, relic)
        best.firstOrNull { it.kind == LabyrinthShopItemKind.ROLE_IMPRINT }?.let {
            return LabyrinthShopDecision.BuyRoleImprint(it, it.roleImprintLabel!!, "凹分预算：余额${wallet}，预留${reserve}")
        }
        if (reserve > 0) return LabyrinthShopDecision.Refresh("凹分：本店第${visitRefreshes + 1}/${limit}次刷新，费用${fee}")
        return LabyrinthShopDecision.Close("凹分：预算内无可确认商品或可用刷新")
    }

    private fun ordinary(
        currentArea: Int?,
        items: List<LabyrinthShopItemMatch>,
        acquired: List<LabyrinthObservedRelic>,
        calibratedStacks: Map<LabyrinthRelicMark, Int> = emptyMap(),
        refreshAvailable: Boolean,
        lockedFocus: LabyrinthRelicMark? = null,
        relicPurchasesInCycle: Int = 0,
        refreshAvailabilityKnown: Boolean = true,
    ): LabyrinthShopDecision {
        if (!buyRelics) return LabyrinthShopDecision.Close("策略已关闭商店购买，退出商店")
        val refreshAllowed = refreshEnabled && currentArea != null && currentArea >= finalArea
        // Preserve the default area-4 saving rule, unless the player explicitly permits refresh
        // there. An earlier configured starting area must not be defeated by this legacy rule.
        if (currentArea != null && currentArea in noPurchaseAreas && !refreshAllowed) {
            return LabyrinthShopDecision.Close("区域${currentArea}按策略不购买商店商品，直接退出")
        }

        // Exact role identity is required before spending coins on an imprint. A coarse
        // ROLE_IMPRINT classification is still useful because it proves the slot is not a relic
        // and can recover an exhausted stock cycle after an app restart/reinstall.
        // Prefer the random imprint over the choice imprint of the same role class. Both grant a
        // character of that class, but the choice variant costs well over twice as much (2026-09-22
        // shop: 随机印记 780 against 选择印记 1,820) and it opens a full-roster picker the run then
        // has to answer. Cheaper and fewer moving parts for the same reward, so it goes first;
        // within one variant the leftmost slot still wins, keeping the pick deterministic.
        val purchasableRoleImprint = items
            .filter { item ->
                item.categoryState == LabyrinthShopCategoryState.READY &&
                    item.purchasable && item.kind == LabyrinthShopItemKind.ROLE_IMPRINT &&
                    !item.roleImprintLabel.isNullOrBlank()
            }
            .minWithOrNull(compareBy({ it.choiceRoleImprint }, { it.slotId }))

        // The game exposes exactly three relic goods per stock cycle. In a continuous session the
        // counter below reaches 3 from purchase-complete commits. If the app is restarted while
        // still inside the shop, that volatile counter is lost; when all three active goods have
        // already compacted to role-imprints, the relic stock is nevertheless unambiguously
        // exhausted and the cycle can be recovered without re-identifying a nonexistent relic.
        val visibleStockExhausted = items.size == relicsPerStockCycle &&
            items.all { it.categoryState == LabyrinthShopCategoryState.READY && it.kind == LabyrinthShopItemKind.ROLE_IMPRINT }
        val effectiveRelicPurchasesInCycle = if (visibleStockExhausted) {
            maxOf(relicPurchasesInCycle, relicsPerStockCycle)
        } else {
            relicPurchasesInCycle
        }

        // A committed cycle is sufficient even when the refill titles are still being read.
        if (effectiveRelicPurchasesInCycle >= relicsPerStockCycle) {
            if (refreshAllowed && !refreshAvailabilityKnown) {
                return LabyrinthShopDecision.Wait("遗物已买完，等待刷新按钮稳定识别")
            }
            if (refreshAllowed && refreshAvailable) {
                return LabyrinthShopDecision.Refresh(
                    if (visibleStockExhausted && relicPurchasesInCycle < relicsPerStockCycle) {
                        "当前三个商品位均为职能印记，恢复为本轮三个遗物已售罄；按策略花300刷新下一轮遗物"
                    } else {
                        "本轮前三个遗物已买完，按策略花300刷新下一轮遗物"
                    },
                )
            }
            if (purchasableRoleImprint != null) {
                return LabyrinthShopDecision.BuyRoleImprint(
                    item = purchasableRoleImprint,
                    label = purchasableRoleImprint.roleImprintLabel ?: "职能印记",
                    reason = "本轮三个遗物已买完且无法继续刷新，用剩余金币购买可确认印记",
                )
            }
            if (items.any { it.purchasable && it.categoryState == LabyrinthShopCategoryState.READING }) {
                return LabyrinthShopDecision.Wait("遗物已买完，正在有限重试确认剩余商品类别")
            }
            return LabyrinthShopDecision.Close(
                if ((currentArea ?: 0) >= finalArea) {
                    "本轮前三个遗物已买完且无法刷新，也没有可购买印记，结束商店"
                } else {
                    "区域${currentArea ?: "?"}本轮前三个遗物已买完，没有可购买印记，结束商店"
                },
            )
        }
        val purchasable = items.filter { it.purchasable && it.categoryState == LabyrinthShopCategoryState.READY }
        val recognizedRelicItems = purchasable.filter {
            it.kind != LabyrinthShopItemKind.ROLE_IMPRINT && it.relicMatch?.recognized == true
        }
        if (recognizedRelicItems.isNotEmpty()) {
            val candidates = recognizedRelicItems.mapNotNull(LabyrinthShopItemMatch::relicMatch)
            val relicDecision = relicChoicePolicy.choose(
                acquired = acquired,
                candidates = candidates,
                calibratedStacks = calibratedStacks,
                currentArea = currentArea,
                lockedFocus = lockedFocus,
            )
            if (relicDecision != null) {
                val item = recognizedRelicItems.first { it.relicMatch?.relicId == relicDecision.choice.relicId }
                return LabyrinthShopDecision.BuyRelic(item, relicDecision)
            }
            return LabyrinthShopDecision.Wait("已识别到遗物，但属性/层数元数据不足，拒绝盲买")
        }

        if (refreshAllowed && items.any { it.status == LabyrinthShopItemStatus.UNKNOWN }) {
            return LabyrinthShopDecision.Wait("商店商品按钮尚未完整识别，等待稳定后再决定刷新或退出")
        }

        val reading = items.firstOrNull { it.purchasable && it.categoryState == LabyrinthShopCategoryState.READING }
        if (reading != null) {
            return LabyrinthShopDecision.Wait("${reading.slotId}正在确认商品类别（${reading.categoryAttempt}/3轮）")
        }
        val ambiguous = purchasable.firstOrNull { item ->
            if (item.kind == LabyrinthShopItemKind.ROLE_IMPRINT) return@firstOrNull false
            val relic = item.relicMatch ?: return@firstOrNull false
            !relic.recognized && relic.confidence >= ambiguousRelicLikeConfidence
        }
        if (ambiguous != null) {
            val relic = ambiguous.relicMatch
            return LabyrinthShopDecision.Wait(
                "${ambiguous.slotId}疑似遗物(${"%.2f".format(relic?.confidence ?: 0.0)})但身份未过安全线",
            )
        }

        // Purchase counts can be lost on restart, and sold-out/disabled titles may never pass
        // OCR. Button state independently proves that those goods cannot be bought. Require the
        // complete row: missing controls and unresolved affordable goods do not prove depletion.
        val noActionableRelicInRow = items.size == relicsPerStockCycle && items.all {
            it.status == LabyrinthShopItemStatus.DISABLED ||
                (it.purchasable && it.categoryState == LabyrinthShopCategoryState.READY &&
                    it.kind == LabyrinthShopItemKind.ROLE_IMPRINT)
        }
        if (refreshAllowed && noActionableRelicInRow) {
            if (!refreshAvailabilityKnown) {
                return LabyrinthShopDecision.Wait("当前商品行没有可购买遗物，等待刷新按钮稳定识别")
            }
            if (refreshAvailable) {
                return LabyrinthShopDecision.Refresh("当前商品行没有可购买遗物，按策略花300刷新下一轮遗物")
            }
        }

        if (purchasableRoleImprint != null) {
            return LabyrinthShopDecision.BuyRoleImprint(
                item = purchasableRoleImprint,
                label = purchasableRoleImprint.roleImprintLabel ?: "职能印记",
                reason = "当前没有可继续购买的遗物，购买可确认印记作为剩余金币兜底",
            )
        }

        return LabyrinthShopDecision.Close(
            when {
                items.any { it.categoryState == LabyrinthShopCategoryState.EXHAUSTED } ->
                    "未确认商品已完成三轮识别并跳过；没有可确认购买项，退出商店"
                !refreshEnabled -> "策略禁止刷新，当前无可确认遗物或可购买印记，结束商店"
                (currentArea ?: 0) >= finalArea && refreshAvailable ->
                    "当前商品行尚未确认已无可购买遗物，不提前刷新；且没有可购买印记，结束商店"
                (currentArea ?: 0) >= finalArea ->
                    "当前无可确认遗物、可购买印记或可用刷新，结束商店"
                else -> "区域${currentArea ?: "?"}当前无可确认遗物或可购买印记，结束商店"
            },
        )
    }
}
