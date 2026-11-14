package com.landosol.toolbox.labyrinth

import com.landosol.toolbox.labyrinth.vision.LabyrinthShopItemMatch
import com.landosol.toolbox.labyrinth.vision.LabyrinthShopItemKind
import com.landosol.toolbox.labyrinth.vision.LabyrinthShopCategoryState
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
 * - area 4 never buys and never refreshes: it is a hard saving stop whose coins carry into
 *   area 5, whatever the configured refresh starting area happens to be;
 * - in every other area refresh follows the configured starting area and enable switch;
 * - completed relic cycles, or a fully observed row with no actionable relic, can refresh, but
 *   each shop visit is capped by [refreshLimitsByArea] so a refill that the remaining coins cannot
 *   buy from is not paid for twice by mistake;
 * - when no relic can still be bought and another relic cycle cannot be refreshed, a safely
 *   identified purchasable role imprint may consume the remaining coins and enter role selection;
 * - a purchasable imprint whose role name is still being re-read keeps the visit open for one
 *   bounded grace window instead of closing the shop with the good unsold;
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
    /**
     * Per-area relic rarity gates. A recognized relic whose rarity is listed for the current
     * area is never bought: the row keeps evaluating as if that good did not exist, so the run
     * falls through to the imprint fallback / close decisions instead of spending on it.
     * 2026-11-01 request: areas 3 and 5 only buy ★1/★2 relics, so ★3/★4 are skipped there.
     * Areas absent from this map keep the unrestricted behaviour.
     */
    private val skipRelicRaritiesByArea: Map<Int, Set<Int>> =
        mapOf(3 to setOf(3, 4), 5 to setOf(3, 4)),
    /**
     * Per-area imprint form gate. When true for the current area, only 随机 imprints are bought:
     * a role-specific 选择 imprint (choiceRoleImprint = true) is treated as if it did not exist
     * and the run falls through to the refresh / close decisions. 2026-11-01 request: 随机 780
     * against 选择 1,820 for the same role, and the choice variant additionally opens a roster
     * picker the run then has to answer. 全体选择印记 is deliberately NOT gated (user 2026-11-01:
     * the rule only covers role-specific 选择 imprints), so it keeps the fallback behaviour.
     * Areas absent from this map keep the unrestricted behaviour.
     */
    private val randomRoleImprintsOnlyByArea: Map<Int, Boolean> =
        mapOf(3 to true, 5 to true),
    /**
     * Per-area cap on 300-coin refills inside one shop visit. Area 3 defaults to a single refill:
     * a second refill there routinely returns a row the current balance cannot buy, so it only
     * spends coins. Areas absent from this map keep the unlimited behaviour.
     */
    private val refreshLimitsByArea: Map<Int, Int> = mapOf(3 to 1),
    /**
     * Areas that clear out the row once the refill button is known to be dark.
     *
     * 2026-11-01 request: in area 5 the coins cannot survive the run, so a dark refill button means
     * the remaining balance can only become score through goods the value gates below otherwise
     * refuse. The clearance ladder runs after every normal buy decision and follows the coin cost
     * per point: ★1/★2 relics (5.85–9.10) -> random imprint (9.00) -> ★3 relic (12.35) ->
     * role-specific 选择 imprint (21.00). ★4 relics stay refused (32.5). Areas absent from this
     * set keep the historical behaviour: buy what the gates allow, then close.
     */
    private val clearanceArea: Set<Int> = setOf(5),
    /** Rarities the clearance ladder may still buy after the refill dies. */
    private val clearanceRelicRarities: Set<Int> = setOf(3),
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
        refreshesUsedInVisit: Int = 0,
    ): LabyrinthShopDecision {
        if (!buyRelics) return LabyrinthShopDecision.Close("策略已关闭商店购买，退出商店")
        val refreshAllowed = refreshEnabled && currentArea != null && currentArea >= finalArea
        // The area budget is what keeps a shop from spending 300 coins on a refill that the
        // remaining balance cannot buy from. Running out of budget simply falls through to the
        // normal buy-imprint / close decisions below.
        val refreshBudget = currentArea?.let { refreshLimitsByArea[it] }
        val refreshPermitted = refreshAllowed && (refreshBudget == null || refreshesUsedInVisit < refreshBudget)
        // See [clearanceArea]: a dark refill button in area 5 unlocks the clearance ladder at the
        // end of this function, and suspends the "still reading / ambiguous / unknown" waits so a
        // row that never resolves cannot park the visit for ever.
        val clearance = currentArea != null && currentArea in clearanceArea &&
            refreshPermitted && refreshAvailabilityKnown && !refreshAvailable
        // Area 4 is a hard saving stop: no purchase and no 300-coin refresh, so the coins survive
        // into area 5. This is independent of the configured refresh starting area -- lowering
        // that to 3 must affect areas 3 and 5 only, never hand area 4 a spending license.
        if (currentArea != null && currentArea in noPurchaseAreas) {
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
        val randomImprintsOnly = currentArea?.let { randomRoleImprintsOnlyByArea[it] } ?: false
        val purchasableRoleImprint = items
            .filter { item ->
                item.categoryState == LabyrinthShopCategoryState.READY &&
                    item.purchasable && item.kind == LabyrinthShopItemKind.ROLE_IMPRINT &&
                    !item.roleImprintLabel.isNullOrBlank() &&
                    // 全体选择印记 keeps the fallback behaviour; only role-specific 选择 imprints are gated.
                    (!randomImprintsOnly || !item.choiceRoleImprint ||
                        item.roleImprintLabel == "全体选择印记")
            }
            .minWithOrNull(compareBy({ it.choiceRoleImprint }, { it.slotId }))

        // A purchasable imprint whose role name OCR has not recovered yet proves the shelf is not
        // empty, so the visit must not end on it. Recognition keeps a bounded grace window open
        // for the remaining title crops; only once that window closes may the close decisions
        // below fire.
        // 2026-11-09: areas 5 and 3 both closed with coins left and one such imprint unsold.
        val pendingRoleImprint = items.firstOrNull { item ->
            item.categoryState == LabyrinthShopCategoryState.READY && item.purchasable &&
                item.kind == LabyrinthShopItemKind.ROLE_IMPRINT &&
                item.roleImprintLabel.isNullOrBlank() && item.imprintLabelPending
        }
        fun waitForPendingImprint(reason: String): LabyrinthShopDecision = pendingRoleImprint?.let {
            LabyrinthShopDecision.Wait(
                "${it.slotId}已确认是职能印记，职能名尚未读出，等待重新识别后再决定",
            )
        } ?: LabyrinthShopDecision.Close(reason)

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
            if (refreshPermitted && !refreshAvailabilityKnown) {
                return LabyrinthShopDecision.Wait("遗物已买完，等待刷新按钮稳定识别")
            }
            if (refreshPermitted && refreshAvailable) {
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
            // During clearance the visit continues to the ladder at the end of this function
            // instead of closing here.
            if (!clearance) {
                return waitForPendingImprint(
                    if ((currentArea ?: 0) >= finalArea) {
                        "本轮前三个遗物已买完且无法刷新，也没有可购买印记，结束商店"
                    } else {
                        "区域${currentArea ?: "?"}本轮前三个遗物已买完，没有可购买印记，结束商店"
                    },
                )
            }
        }
        val purchasable = items.filter { item ->
            item.purchasable && item.categoryState == LabyrinthShopCategoryState.READY &&
                (item.kind == LabyrinthShopItemKind.ROLE_IMPRINT || acquired.none { it.relicId == item.relicMatch?.relicId })
        }
        val skipRelicRarities = currentArea?.let { skipRelicRaritiesByArea[it] } ?: emptySet()
        val recognizedRelicItems = purchasable.filter {
            it.kind != LabyrinthShopItemKind.ROLE_IMPRINT && it.relicMatch?.recognized == true &&
                (it.relicMatch?.rarity ?: 0) !in skipRelicRarities
        }
        // Clearance candidates: the goods the value gates refuse, kept aside so the ladder at the
        // end of this function can spend the dying balance on them in cost order.
        val clearanceRelicItems = if (clearance) {
            purchasable.filter {
                it.kind != LabyrinthShopItemKind.ROLE_IMPRINT &&
                    it.relicMatch?.recognized == true &&
                    (it.relicMatch?.rarity ?: 0) in clearanceRelicRarities
            }
        } else {
            emptyList()
        }
        val clearanceChoiceImprint = if (clearance) {
            items.filter { item ->
                item.categoryState == LabyrinthShopCategoryState.READY &&
                    item.purchasable && item.kind == LabyrinthShopItemKind.ROLE_IMPRINT &&
                    !item.roleImprintLabel.isNullOrBlank() && item.choiceRoleImprint
            }.minWithOrNull(compareBy({ it.slotId }))
        } else {
            null
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
        //
        // A relic the area gate refuses must count as gone as well. The buy branch above already
        // filters it out, so leaving it actionable here makes a row of "one refused relic plus
        // imprints" look like it still has stock: the run then neither buys nor refills, and
        // closes the shop with coins left over. 2026-11-01 area 5: 極醒护身符 ★3 on the shelf,
        // 7,760 coins and a usable 300-coin refresh -> three Close decisions in 8 seconds.
        val noActionableRelicInRow = items.size == relicsPerStockCycle && items.all { item ->
            val refusedByArea = item.kind != LabyrinthShopItemKind.ROLE_IMPRINT &&
                item.purchasable && item.categoryState == LabyrinthShopCategoryState.READY &&
                item.relicMatch?.recognized == true &&
                (item.relicMatch?.rarity ?: 0) in skipRelicRarities
            item.status == LabyrinthShopItemStatus.DISABLED || refusedByArea ||
                (item.purchasable && item.categoryState == LabyrinthShopCategoryState.READY &&
                    item.kind == LabyrinthShopItemKind.ROLE_IMPRINT)
        }
        if (refreshPermitted && noActionableRelicInRow) {
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

        // Clearance ladder (area 5 only, refill button known to be dark): the balance cannot buy
        // new stock any more, so pay for the refused goods in cost order. ★1/★2 relics and the
        // random imprint have already had their chance above; ★4 stays refused at 32.5 coins/point.
        if (clearanceRelicItems.isNotEmpty()) {
            val candidates = clearanceRelicItems.mapNotNull(LabyrinthShopItemMatch::relicMatch)
            val relicDecision = relicChoicePolicy.choose(
                acquired = acquired,
                candidates = candidates,
                calibratedStacks = calibratedStacks,
                currentArea = currentArea,
                lockedFocus = lockedFocus,
            )
            if (relicDecision != null) {
                val item = clearanceRelicItems.first { it.relicMatch?.relicId == relicDecision.choice.relicId }
                return LabyrinthShopDecision.BuyRelic(item, relicDecision)
            }
        }
        if (clearanceChoiceImprint != null) {
            return LabyrinthShopDecision.BuyRoleImprint(
                item = clearanceChoiceImprint,
                label = clearanceChoiceImprint.roleImprintLabel ?: "职能印记",
                reason = "刷新已用完，用剩余金币购买选择印记",
            )
        }

        return waitForPendingImprint(
            when {
                items.any { it.categoryState == LabyrinthShopCategoryState.EXHAUSTED } ->
                    "未确认商品已完成三轮识别并跳过；没有可确认购买项，退出商店"
                !refreshEnabled -> "策略禁止刷新，当前无可确认遗物或可购买印记，结束商店"
                currentArea != null && refreshBudget != null && refreshesUsedInVisit >= refreshBudget ->
                    "区域${currentArea}本店刷新额度已用完（上限${refreshBudget}次），结束商店"
                (currentArea ?: 0) >= finalArea && refreshAvailable ->
                    "当前商品行尚未确认已无可购买遗物，不提前刷新；且没有可购买印记，结束商店"
                (currentArea ?: 0) >= finalArea ->
                    "当前无可确认遗物、可购买印记或可用刷新，结束商店"
                else -> "区域${currentArea ?: "?"}当前无可确认遗物或可购买印记，结束商店"
            },
        )
    }
}
