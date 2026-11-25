package com.landosol.toolbox.labyrinth

import com.landosol.toolbox.labyrinth.kernel.planning.ShopDiscountModel

/**
 * In-run alpha-rupee balance tracker. Anchors on confirmed shop OCR reads, updates on deterministic
 * shop purchases/refreshes and event deltas, and clears to null when an unmeasurable change occurs
 * (e.g., variable event payouts or unpriced shop purchases) so stale balances are never assumed.
 */
class LabyrinthAlphaRupeeLedger {
    @get:Synchronized
    var currentBalance: Int? = null
        private set

    @get:Synchronized
    var lastUpdatedAtMillis: Long = Long.MIN_VALUE
        private set

    @Synchronized
    fun reset() {
        currentBalance = null
        lastUpdatedAtMillis = Long.MIN_VALUE
    }

    @Synchronized
    fun onShopCoinsObserved(coins: Int?, confirmed: Boolean, timestampMillis: Long) {
        if (!confirmed || coins == null || coins < 0) return
        currentBalance = coins
        lastUpdatedAtMillis = timestampMillis
    }

    @Synchronized
    fun onShopRefreshCommitted(fee: Int, observedCoinsAfter: Int?, timestampMillis: Long) {
        val anchored = observedCoinsAfter?.takeIf { it >= 0 }
        if (anchored != null) {
            currentBalance = anchored
            lastUpdatedAtMillis = timestampMillis
            return
        }
        val known = currentBalance ?: return
        currentBalance = (known - fee.coerceAtLeast(0)).coerceAtLeast(0)
        lastUpdatedAtMillis = timestampMillis
    }

    @Synchronized
    fun onShopPurchaseCommitted(itemPrice: Int?, timestampMillis: Long) {
        if (itemPrice == null || itemPrice < 0) {
            currentBalance = null
            lastUpdatedAtMillis = timestampMillis
            return
        }
        val known = currentBalance ?: return
        currentBalance = (known - itemPrice).coerceAtLeast(0)
        lastUpdatedAtMillis = timestampMillis
    }

    @Synchronized
    fun onEventChoiceCommitted(choiceId: String?, timestampMillis: Long) {
        val id = choiceId?.trim()?.takeIf(String::isNotEmpty) ?: return
        if (id in VARIABLE_GOLD_EVENT_CHOICES) {
            currentBalance = null
            lastUpdatedAtMillis = timestampMillis
            return
        }
        val delta = DETERMINISTIC_GOLD_EVENT_DELTAS[id] ?: return
        val known = currentBalance ?: return
        currentBalance = (known + delta).coerceAtLeast(0)
        lastUpdatedAtMillis = timestampMillis
    }

    companion object {
        private val DETERMINISTIC_GOLD_EVENT_DELTAS: Map<String, Int> = mapOf(
            "11011" to 200, "11012" to 200,
            "11021" to 200, "11022" to 200,
            "11031" to 200, "11032" to 200,
            "11041" to 200, "11042" to 200,
            "11082" to 1000,
            "11111" to 2000,
            "11121" to 2000,
            "11071" to -2000,
            "11072" to -2000,
        )

        private val VARIABLE_GOLD_EVENT_CHOICES: Set<String> = setOf(
            "11073",
            "11101", "11102", "11103",
            "13141", "23141", "33141",
            "13151", "23151", "33151",
            "13161", "23161", "33161",
            "11051", "11052",
            "11091", "11092",
            "11131", "11132",
            "11171", "11172",
        )

        fun estimatedShopItemPrice(
            rarity: Int?,
            isRoleImprint: Boolean,
            isChoiceImprint: Boolean,
            enhanceNode21: Boolean,
            hasRelic40003: Boolean,
        ): Int? {
            val basePrice = when {
                isRoleImprint -> if (isChoiceImprint) 2800 else 1200
                rarity == 1 -> 1800
                rarity == 2 -> 2800
                rarity == 3 -> 3800
                rarity == 4 -> 9999
                else -> return null
            }
            return ShopDiscountModel(enhanceNode21, hasRelic40003).price(basePrice)
        }
    }
}
