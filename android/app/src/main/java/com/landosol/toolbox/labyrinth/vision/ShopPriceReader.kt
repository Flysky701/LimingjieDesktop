package com.landosol.toolbox.labyrinth.vision

import android.graphics.Bitmap
import android.os.SystemClock

/** Only instantiated for the score preset. Unconfirmed amounts expire with their exact crop. */
internal class ShopPriceReader(submit: (Bitmap, (String?) -> Unit) -> Unit) {
    private val reader = AndroidRelicRoiOcr(submit)
    private var firstFrame: Long? = null

    fun clear() {
        if (firstFrame != null) reader.clear()
        firstFrame = null
    }

    fun observe(bitmap: Bitmap, shop: LabyrinthShopObservation): LabyrinthShopObservation {
        if (shop.dialogState != LabyrinthShopDialogState.NONE) {
            clear()
            return shop
        }
        val now = SystemClock.elapsedRealtime()
        val start = firstFrame ?: now.also { firstFrame = it }
        fun number(slot: String, crop: EntryReferenceRect): Int? {
            val rect = ReferenceFitMapper.map(bitmap.width, bitmap.height, EntryReferenceSize(1920, 1080), crop) ?: return null
            val read = reader.read(bitmap, slot, rect, scale = 2) ?: return null
            return read.text?.takeIf { read.confirmations >= 2 }?.let(::shopAmount)
        }
        val wallet = number("wallet", EntryReferenceRect(1260, 940, 150, 64))
        val items = shop.items.map { item ->
            val rect = PRICES[item.slotId] ?: return@map item
            if (item.purchasable) item.copy(price = number(item.slotId, rect)?.takeIf { it > 0 }) else item
        }
        val fee = if (shop.refreshButtonEnabled) number("refresh", EntryReferenceRect(648, 945, 58, 55)) else null
        val incomplete = wallet == null || items.any { it.purchasable && it.price == null } ||
            (shop.refreshButtonEnabled && fee == null)
        return shop.copy(items = items, wallet = wallet, refreshFee = fee, readingPrices = incomplete && now - start < 8_000L)
    }

    private companion object {
        val PRICES = mapOf("shop_item_1" to EntryReferenceRect(175, 490, 170, 65),
            "shop_item_2" to EntryReferenceRect(753, 490, 170, 65), "shop_item_3" to EntryReferenceRect(1328, 490, 170, 65))
    }
}

private val amountPattern = Regex("[0-9]{1,8}|[0-9]{1,3}(?:,[0-9]{3}){1,2}")
internal fun shopAmount(text: String): Int? = text.trim().replace('，', ',')
    .takeIf(amountPattern::matches)?.replace(",", "")?.toIntOrNull()?.takeIf { it <= 99_999_999 }
