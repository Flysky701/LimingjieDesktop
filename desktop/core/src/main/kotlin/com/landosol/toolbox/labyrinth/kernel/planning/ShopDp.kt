package com.landosol.toolbox.labyrinth.kernel.planning

import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

data class ShopDiscountModel(
    val enhanceNode21: Boolean = false,
    val relic40003: Boolean = false,
    val extraDiscountPct: Int = 0,
) {
    init {
        require(extraDiscountPct >= 0)
    }

    private val pct: Int = minOf(90, (if (enhanceNode21) 25 else 0) + (if (relic40003) 10 else 0) + extraDiscountPct)
    val rate: Double get() = pct / 100.0

    fun price(basePrice: Int): Int = Math.floorDiv(basePrice.toLong() * (100 - pct), 100L).toInt()
}

enum class ShopItemKind { RELIC_1STAR, RELIC_2STAR, RELIC_3STAR, RELIC_4STAR, ROLE_IMPRINT, ROLE_CHOICE_IMPRINT }

data class ShopItem(
    val itemId: String,
    val kind: ShopItemKind,
    val basePrice: Int,
    val extraBattlePts: Double = 0.0,
    val relicId: Int? = null,
    /** Known role awarded by an observed imprint. itemId identifies the offer. */
    val characterId: String? = null,
    val collectionCount: Int = 1,
) {
    init {
        require(basePrice >= 0 && extraBattlePts.isFinite() && collectionCount >= 0)
    }

    internal val isRelic: Boolean
        get() = kind != ShopItemKind.ROLE_IMPRINT && kind != ShopItemKind.ROLE_CHOICE_IMPRINT
}

/** Weighted whole shelves preserve correlations between offers after a refresh or at a later shop. */
data class ShopShelfModel(
    val shelves: List<Pair<Double, List<ShopItem>>> = listOf(
        0.5 to listOf(
            ShopItem("ROLE_IMPRINT", ShopItemKind.ROLE_IMPRINT, 1200),
            ShopItem("RELIC_1STAR", ShopItemKind.RELIC_1STAR, 1800),
        ),
        0.5 to listOf(
            ShopItem("ROLE_CHOICE_IMPRINT", ShopItemKind.ROLE_CHOICE_IMPRINT, 2800),
            ShopItem("RELIC_2STAR", ShopItemKind.RELIC_2STAR, 2800),
        ),
    ),
) {
    init {
        require(shelves.all { (p, _) -> p in 0.0..1.0 } && abs(shelves.sumOf { it.first } - 1.0) <= 1e-12)
    }

    companion object {
        private const val REGULAR_SHOP_POOL_1STAR = 25
        private const val REGULAR_SHOP_POOL_2STAR = 25
        private const val REGULAR_SHOP_POOL_3STAR = 25
        private const val WEIGHT_1STAR = 65.0
        private const val WEIGHT_2STAR = 30.0
        private const val WEIGHT_3STAR = 5.0
        private const val MIN_SHELF_PROB = 1e-4

        private val REMAINING_POOL_CACHE = ConcurrentHashMap<Triple<Int, Int, Int>, ShopShelfModel>()

        fun liveCalibrated(): ShopShelfModel = LIVE_CALIBRATED_SHELVES

        /**
         * Exact weighted without-replacement 3-slot relic shelf distribution over the remaining
         * regular shop pool `(rem1, rem2, rem3)` with per-relic star weights `65 : 30 : 5`
         * (calibrated against 1,793 live non-Area-4 shelves / 5,379 relic offers + owned snapshots).
         */
        fun forRemainingRelicPool(rem1: Int, rem2: Int, rem3: Int): ShopShelfModel {
            val r1 = rem1.coerceAtLeast(0)
            val r2 = rem2.coerceAtLeast(0)
            val r3 = rem3.coerceAtLeast(0)
            if (r1 + r2 + r3 <= 0) return LIVE_CALIBRATED_SHELVES
            val key = Triple(r1, r2, r3)
            return REMAINING_POOL_CACHE.getOrPut(key) {
                buildWeightedWithoutReplacementShelves(r1, r2, r3)
            }
        }

        /**
         * Depletes the regular 25/25/25 purchasable relic pool by [ownedRelicIds]
         * (excluding 4xxxx/5xxxx guild relics and 3x006 4★ relics) and returns the resulting shelf model.
         */
        fun forOwnedRelics(ownedRelicIds: Set<Int>): ShopShelfModel {
            if (ownedRelicIds.isEmpty()) return forRemainingRelicPool(
                REGULAR_SHOP_POOL_1STAR,
                REGULAR_SHOP_POOL_2STAR,
                REGULAR_SHOP_POOL_3STAR,
            )
            val owned1 = ownedRelicIds.count { it in 10_000..19_999 }
            val owned2 = ownedRelicIds.count { it in 20_000..29_999 }
            val owned3 = ownedRelicIds.count { it in 31_001..35_999 && it % 10 != 6 }
            val rem1 = (REGULAR_SHOP_POOL_1STAR - owned1).coerceIn(0, REGULAR_SHOP_POOL_1STAR)
            val rem2 = (REGULAR_SHOP_POOL_2STAR - owned2).coerceIn(0, REGULAR_SHOP_POOL_2STAR)
            val rem3 = (REGULAR_SHOP_POOL_3STAR - owned3).coerceIn(0, REGULAR_SHOP_POOL_3STAR)
            if (rem1 + rem2 + rem3 < 3) return liveCalibrated()
            return forRemainingRelicPool(rem1, rem2, rem3)
        }

        fun fromObservations(observations: Iterable<Iterable<ShopItem>>): ShopShelfModel {
            val shelves = observations.map { it.toList() }
            return ShopShelfModel(shelves.groupingBy { it }.eachCount().map { (shelf, count) ->
                count.toDouble() / shelves.size to shelf
            })
        }

        private fun buildWeightedWithoutReplacementShelves(r1: Int, r2: Int, r3: Int): ShopShelfModel {
            val weights = doubleArrayOf(WEIGHT_1STAR, WEIGHT_2STAR, WEIGHT_3STAR)
            val counts = intArrayOf(r1, r2, r3)
            val kinds = arrayOf(ShopItemKind.RELIC_1STAR, ShopItemKind.RELIC_2STAR, ShopItemKind.RELIC_3STAR)
            val grouped = linkedMapOf<Triple<Int, Int, Int>, Double>()

            fun draw(slot: Int, prob: Double, p0: Int, p1: Int, p2: Int) {
                if (slot == 3) {
                    val sorted = intArrayOf(p0, p1, p2).apply { sort() }
                    val triple = Triple(sorted[0], sorted[1], sorted[2])
                    grouped[triple] = (grouped[triple] ?: 0.0) + prob
                    return
                }
                val totalWeight = counts[0] * weights[0] + counts[1] * weights[1] + counts[2] * weights[2]
                if (totalWeight <= 0.0) {
                    draw(slot + 1, prob, p0, p1, p2)
                    return
                }
                for (star in 0..2) {
                    val c = counts[star]
                    if (c <= 0) continue
                    val branchProb = prob * (c * weights[star] / totalWeight)
                    counts[star] = c - 1
                    when (slot) {
                        0 -> draw(1, branchProb, star + 1, p1, p2)
                        1 -> draw(2, branchProb, p0, star + 1, p2)
                        else -> draw(3, branchProb, p0, p1, star + 1)
                    }
                    counts[star] = c
                }
            }

            draw(0, 1.0, 0, 0, 0)
            val filtered = grouped.entries
                .filter { it.value >= MIN_SHELF_PROB }
                .ifEmpty { grouped.entries.toList() }
                .sortedByDescending { it.value }
            val rawSum = filtered.sumOf { it.value }
            val normalizedProbs = filtered.map { it.value / rawSum }.toMutableList()
            val residual = 1.0 - normalizedProbs.sum()
            if (normalizedProbs.isNotEmpty() && abs(residual) > 0.0) {
                normalizedProbs[0] = (normalizedProbs[0] + residual).coerceIn(0.0, 1.0)
            }
            val shelves = filtered.mapIndexed { idx, (triple, _) ->
                val relicKinds = listOf(triple.first, triple.second, triple.third)
                    .filter { it in 1..3 }
                    .map { kinds[it - 1] }
                normalizedProbs[idx] to calibratedShelf(*relicKinds.toTypedArray())
            }
            return ShopShelfModel(shelves)
        }
    }
}

private fun calibratedShelf(vararg relicKinds: ShopItemKind): List<ShopItem> =
    relicKinds.mapIndexed { index, kind ->
        val price = when (kind) {
            ShopItemKind.RELIC_1STAR -> 1800
            ShopItemKind.RELIC_2STAR -> 2800
            ShopItemKind.RELIC_3STAR -> 3800
            else -> 9999
        }
        ShopItem("${kind.name}_$index", kind, price)
    } + listOf(
        ShopItem("ROLE_IMPRINT_0", ShopItemKind.ROLE_IMPRINT, 1200),
        ShopItem("ROLE_IMPRINT_1", ShopItemKind.ROLE_IMPRINT, 1200),
        ShopItem("ROLE_CHOICE_IMPRINT_0", ShopItemKind.ROLE_CHOICE_IMPRINT, 2800),
    )

// Fallback mid-run shelf distribution matching 1,793 non-Area-4 shelves / 5,379 relic offers
// (1★ ~59.8%, 2★ ~34.4%, 3★ ~5.9%) under 65:30:5 weighted without-replacement from (18, 22, 23).
private val LIVE_CALIBRATED_SHELVES: ShopShelfModel by lazy {
    ShopShelfModel.forRemainingRelicPool(18, 22, 23)
}

/** Wallet totals reflect the selected purchases on the visible shelf before any refresh fee. */
data class ShopPlanResult(
    val buyIndices: List<Int>,
    val shouldRefresh: Boolean,
    val expectedPts: Double,
    val spentGold: Int,
    val remainingGold: Int,
)

data class ShopPlanEvaluation(
    val plan: ShopPlanResult,
    val refreshCost: Int,
    val expectedValue: Double,
)

private val defaultShelves = ShopShelfModel()

private fun efficiency(price: Int, gain: Double): Double =
    if (gain > 0.0) price / gain else Double.POSITIVE_INFINITY

fun clearanceLadder(
    items: Iterable<ShopItem>,
    gold: Int,
    settings: ScoreSettings = ScoreSettings.currentOfficial(),
    difficulty: Int = 5,
    discountModel: ShopDiscountModel = ShopDiscountModel(),
    coinRetentionValueFn: ((Int) -> Double)? = null,
): List<ShopItem> {
    val unitPts = unitDeltaPts(settings, difficulty)
    val relicPts = relicDeltaPts(settings, difficulty)
    fun gain(item: ShopItem) =
        item.collectionCount.toDouble() * (if (item.isRelic) relicPts else unitPts) + item.extraBattlePts

    var wallet = gold
    return buildList {
        for (item in items.sortedBy { efficiency(discountModel.price(it.basePrice), gain(it)) }) {
            val price = discountModel.price(item.basePrice)
            if (price > wallet) continue
            val opportunity = coinRetentionValueFn?.let { it(wallet) - it(wallet - price) } ?: 0.0
            if (gain(item) > opportunity) {
                add(item)
                wallet -= price
            }
        }
    }
}

private data class ShopWallet(val gold: Int, val owned: Set<String>, val remainder: Int)

private data class VisibleShop(
    val shelf: List<ShopItem>,
    val wallet: ShopWallet,
    val shops: Int,
    val refreshCount: Int,
    val bought: Set<Int>,
    val refreshAvailable: Boolean,
)

private class ShopDp(
    settings: ScoreSettings,
    difficulty: Int,
    private val discount: ShopDiscountModel,
    shelfModel: ShopShelfModel,
    maxRefreshes: Int,
    private val retention: ((Int) -> Double)? = null,
    private val futureRefreshAvailable: List<Boolean> = emptyList(),
) {
    private data class Decision(val value: Double, val action: Int)
    private data class Purchase(val next: VisibleShop, val pts: Long)
    private data class Future(val wallet: ShopWallet, val shops: Int)

    private val fees = settings.shopRefreshCosts
    private val refreshLimit = minOf(fees.size, maxRefreshes)
    private val shelves = shelfModel.shelves.filter { it.first > 0.0 }
    private val rate = settings.scoreRates.getValue(difficulty)
    private val unitTicks = settings.unitPts.toLong() * rate
    private val relicTicks = settings.relicPts.toLong() * rate
    private val decisions = HashMap<VisibleShop, Decision>()
    private val futureValues = HashMap<Future, Double>()
    private val visibleScores = HashMap<VisibleShop, Map<Long, Double>>()
    private val futureScores = HashMap<Future, Map<Long, Double>>()

    init {
        require(maxRefreshes >= 0 && fees.all { it >= 0 })
    }

    private fun purchase(state: VisibleShop, index: Int): Purchase {
        val item = state.shelf[index]
        val wallet = state.wallet
        val role = item.characterId?.takeIf { !item.isRelic && item.collectionCount > 0 }
        val count = item.collectionCount - if (role != null && role in wallet.owned) 1 else 0
        val ticks = wallet.remainder + count * (if (item.isRelic) relicTicks else unitTicks)
        val after = ShopWallet(
            wallet.gold - discount.price(item.basePrice),
            if (role == null) wallet.owned else wallet.owned + role,
            Math.floorMod(ticks, 100L).toInt(),
        )
        return Purchase(state.copy(wallet = after, bought = state.bought + index), Math.floorDiv(ticks, 100L))
    }

    private fun future(wallet: ShopWallet, shops: Int): List<Pair<Double, VisibleShop>> {
        val allow = futureRefreshAvailable.getOrNull(futureRefreshAvailable.size - shops) ?: true
        return shelves.map { (p, shelf) -> p to VisibleShop(shelf, wallet, shops, 0, emptySet(), allow) }
    }

    private fun refresh(state: VisibleShop): List<Pair<Double, VisibleShop>> {
        val wallet = state.wallet.copy(gold = state.wallet.gold - fees[state.refreshCount])
        return shelves.map { (p, shelf) ->
            p to VisibleShop(shelf, wallet, state.shops, state.refreshCount + 1, emptySet(), true)
        }
    }

    private fun futureValue(wallet: ShopWallet, shops: Int): Double =
        futureValues.getOrPut(Future(wallet, shops)) {
            if (shops == 0) retention?.invoke(wallet.gold) ?: 0.0
            else future(wallet, shops).sumOf { (p, visible) -> p * decision(visible).value }
        }

    private fun decision(state: VisibleShop): Decision = decisions.getOrPut(state) {
        var best = Decision(futureValue(state.wallet, state.shops - 1), EXIT)
        for ((index, item) in state.shelf.withIndex()) {
            if (index in state.bought || discount.price(item.basePrice) > state.wallet.gold) continue
            if (index > 0 && (index - 1) !in state.bought) {
                val prev = state.shelf[index - 1]
                if (prev.characterId == null && item.characterId == null &&
                    prev.relicId == null && item.relicId == null &&
                    prev.kind == item.kind && prev.basePrice == item.basePrice &&
                    prev.extraBattlePts == item.extraBattlePts && prev.collectionCount == item.collectionCount
                ) continue
            }
            val (next, pts) = purchase(state, index)
            val value = pts + item.extraBattlePts + decision(next).value
            if (value > best.value) best = Decision(value, index)
        }
        if (state.refreshAvailable && state.refreshCount < refreshLimit && fees[state.refreshCount] <= state.wallet.gold) {
            val value = refresh(state).sumOf { (p, next) -> p * decision(next).value }
            if (value > best.value) best = Decision(value, REFRESH)
        }
        best
    }

    private fun mix(states: List<Pair<Double, VisibleShop>>): Map<Long, Double> = buildMap {
        for ((weight, state) in states) {
            for ((pts, p) in visibleDistribution(state)) {
                put(pts, (get(pts) ?: 0.0) + weight * p)
            }
        }
    }

    private fun visibleDistribution(state: VisibleShop): Map<Long, Double> = visibleScores.getOrPut(state) {
        when (val action = decision(state).action) {
            EXIT -> distribution(state.wallet, state.shops - 1)
            REFRESH -> mix(refresh(state))
            else -> {
                val (next, pts) = purchase(state, action)
                visibleDistribution(next).mapKeys { (later, _) -> pts + later }
            }
        }
    }

    fun distribution(wallet: ShopWallet, shops: Int): Map<Long, Double> = futureScores.getOrPut(Future(wallet, shops)) {
        if (shops == 0) mapOf(0L to 1.0) else mix(future(wallet, shops))
    }

    fun plan(initial: VisibleShop): ShopPlanEvaluation {
        var state = initial
        val initialDecision = decision(state)
        var action = initialDecision.action
        val indices = mutableListOf<Int>()
        while (action >= 0) {
            indices += action
            state = purchase(state, action).next
            action = decision(state).action
        }
        val buyIndices = indices.sortedBy { index ->
            val item = initial.shelf[index]
            val pts = (if (item.isRelic) relicTicks else unitTicks) / 100.0
            efficiency(discount.price(item.basePrice), item.collectionCount * pts + item.extraBattlePts)
        }
        val shouldRefresh = action == REFRESH
        val planResult = ShopPlanResult(
            buyIndices, shouldRefresh, visibleDistribution(initial).mean(),
            initial.wallet.gold - state.wallet.gold, state.wallet.gold,
        )
        return ShopPlanEvaluation(
            plan = planResult,
            refreshCost = if (shouldRefresh) fees[initial.refreshCount] else 0,
            expectedValue = initialDecision.value,
        )
    }

    private companion object {
        const val EXIT = -1
        const val REFRESH = -2
    }
}

private fun Map<Long, Double>.mean(): Double = entries.sumOf { (pts, p) -> pts * p }

fun evaluateShopVisit(
    items: Iterable<ShopItem>,
    gold: Int,
    settings: ScoreSettings = ScoreSettings.currentOfficial(),
    difficulty: Int = 5,
    discountModel: ShopDiscountModel = ShopDiscountModel(),
    shelfModel: ShopShelfModel = defaultShelves,
    refreshCount: Int = 0,
    maxRefreshes: Int = settings.shopRefreshCosts.size,
    refreshAvailable: Boolean = true,
    isFinalArea: Boolean = false,
    remainingShops: Int = 1,
    boughtMask: Int = 0,
    coinRetentionValueFn: ((Int) -> Double)? = null,
    ownedCharacterIds: Set<String> = emptySet(),
    scoreRemainder: Int = 0,
    futureRefreshAvailable: List<Boolean> = emptyList(),
): ShopPlanEvaluation {
    val shelf = items.toList()
    require(gold >= 0 && remainingShops >= 1 && refreshCount >= 0 && scoreRemainder in 0..99)
    require(boughtMask >= 0 && Int.SIZE_BITS - boughtMask.countLeadingZeroBits() <= shelf.size)
    val bought = shelf.indices.take(Int.SIZE_BITS - 1).filter { boughtMask and (1 shl it) != 0 }.toSet()
    val owned = ownedCharacterIds + bought.mapNotNull { index ->
        shelf[index].takeUnless(ShopItem::isRelic)?.characterId
    }
    val flags = if (isFinalArea) List(remainingShops - 1) { false } else futureRefreshAvailable
    val dp = ShopDp(settings, difficulty, discountModel, shelfModel, maxRefreshes, coinRetentionValueFn, flags)
    return dp.plan(VisibleShop(shelf, ShopWallet(gold, owned, scoreRemainder), remainingShops, refreshCount,
        bought, refreshAvailable && !isFinalArea))
}

fun planShopVisit(
    items: Iterable<ShopItem>,
    gold: Int,
    settings: ScoreSettings = ScoreSettings.currentOfficial(),
    difficulty: Int = 5,
    discountModel: ShopDiscountModel = ShopDiscountModel(),
    shelfModel: ShopShelfModel = defaultShelves,
    refreshCount: Int = 0,
    maxRefreshes: Int = settings.shopRefreshCosts.size,
    refreshAvailable: Boolean = true,
    isFinalArea: Boolean = false,
    remainingShops: Int = 1,
    boughtMask: Int = 0,
    coinRetentionValueFn: ((Int) -> Double)? = null,
    ownedCharacterIds: Set<String> = emptySet(),
    scoreRemainder: Int = 0,
    futureRefreshAvailable: List<Boolean> = emptyList(),
): ShopPlanResult = evaluateShopVisit(
    items, gold, settings, difficulty, discountModel, shelfModel, refreshCount, maxRefreshes,
    refreshAvailable, isFinalArea, remainingShops, boughtMask, coinRetentionValueFn,
    ownedCharacterIds, scoreRemainder, futureRefreshAvailable,
).plan

fun shopScoreDistribution(
    gold: Int,
    remainingShops: Int,
    settings: ScoreSettings = ScoreSettings.currentOfficial(),
    difficulty: Int = 5,
    discountModel: ShopDiscountModel = ShopDiscountModel(),
    shelfModel: ShopShelfModel = defaultShelves,
    maxRefreshes: Int = 0,
    ownedCharacterIds: Set<String> = emptySet(),
    scoreRemainder: Int = 0,
): Map<Long, Double> {
    require(gold >= 0 && remainingShops >= 0 && scoreRemainder in 0..99)
    return ShopDp(settings, difficulty, discountModel, shelfModel, maxRefreshes)
        .distribution(ShopWallet(gold, ownedCharacterIds, scoreRemainder), remainingShops)
}

fun goldToPtsCurve(
    gold: Int,
    remainingShops: Int,
    settings: ScoreSettings = ScoreSettings.currentOfficial(),
    difficulty: Int = 5,
    discountModel: ShopDiscountModel = ShopDiscountModel(),
    shelfModel: ShopShelfModel = defaultShelves,
    maxRefreshes: Int = 0,
    ownedCharacterIds: Set<String> = emptySet(),
    scoreRemainder: Int = 0,
): Double = shopScoreDistribution(
    gold, remainingShops, settings, difficulty, discountModel, shelfModel, maxRefreshes, ownedCharacterIds, scoreRemainder,
).mean()

fun marginalGoldPts(
    goldBefore: Int,
    deltaGold: Int,
    remainingShops: Int,
    settings: ScoreSettings = ScoreSettings.currentOfficial(),
    difficulty: Int = 5,
    discountModel: ShopDiscountModel = ShopDiscountModel(),
    shelfModel: ShopShelfModel = defaultShelves,
    maxRefreshes: Int = 0,
    ownedCharacterIds: Set<String> = emptySet(),
    scoreRemainder: Int = 0,
): Double {
    val goldAfter = Math.addExact(goldBefore, deltaGold)
    require(goldBefore >= 0 && goldAfter >= 0 && remainingShops >= 0 && scoreRemainder in 0..99)
    val dp = ShopDp(settings, difficulty, discountModel, shelfModel, maxRefreshes)
    val wallet = ShopWallet(goldBefore, ownedCharacterIds, scoreRemainder)
    return dp.distribution(wallet.copy(gold = goldAfter), remainingShops).mean() - dp.distribution(wallet, remainingShops).mean()
}
