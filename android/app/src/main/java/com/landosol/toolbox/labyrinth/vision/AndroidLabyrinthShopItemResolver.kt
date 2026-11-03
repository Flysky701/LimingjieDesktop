package com.landosol.toolbox.labyrinth.vision

import android.graphics.Bitmap

/** Every visible title is classified before a relic match can authorize a purchase. */
class AndroidLabyrinthShopItemResolver(
    submitTextRead: (Bitmap, (String?) -> Unit) -> Unit = AndroidChineseRoleNameOcr::readAsync,
    scoreShop: Boolean = false,
) {
    private val ocr = AndroidRelicRoiOcr(submitTextRead, maximumAttempts = 1)
    private val prices = if (scoreShop) ShopPriceReader(submitTextRead) else null
    private val categories = LabyrinthShopCategoryResolution()
    private val accepted = mutableMapOf<String, Pair<Long, RelicOcrScheduler.Read>>()

    @Synchronized
    fun resolve(bitmap: Bitmap, result: LabyrinthEntryFrameResult): LabyrinthEntryFrameResult {
        if (result.observation.state != LabyrinthEntryPageState.SHOP) {
            ocr.clear()
            categories.clear()
            prices?.clear()
            accepted.clear()
            return result
        }
        val shop = result.shopObservation ?: return result
        val items = shop.items.map { item ->
            val referenceRect = TITLE_RECTS[item.slotId] ?: return@map item
            val titleRect = ReferenceFitMapper.map(bitmap.width, bitmap.height, STANDARD_REFERENCE, referenceRect)
                ?: return@map item
            val fingerprint = relicRoiFingerprint(bitmap, titleRect)
            accepted[item.slotId]?.takeIf { it.first == fingerprint }?.let { (_, read) ->
                return@map categories.resolve(item, read.text, read.id, 0)
            }
            accepted.remove(item.slotId)
            val now = android.os.SystemClock.elapsedRealtime()
            val attempt = categories.attempt(item.slotId, fingerprint, now)
            val variant = minOf(attempt, 2)
            val rect = if (variant == 2) titleRect.copy(
                top = maxOf(0, titleRect.top - 3),
                height = minOf(bitmap.height - maxOf(0, titleRect.top - 3), titleRect.height + 6),
            ) else titleRect
            val read = ocr.read(bitmap, "shop-title-${item.slotId}-$variant", rect,
                scale = if (variant == 0) 2 else 3)
            val resolved = categories.resolve(item, read?.text, read?.id, attempt)
            if (resolved.categoryState == LabyrinthShopCategoryState.READY && read != null) {
                accepted[item.slotId] = fingerprint to read
            } else {
                // Retire this crop variant only once its OCR actually completed (read != null),
                // not on a fixed interval: the reads share one serial queue and a time clock was
                // failing titles that were still being processed. The engine's own last answer
                // tells the difference between "queued" and "dead".
                categories.noteVariantOutcome(
                    slot = item.slotId,
                    fingerprint = fingerprint,
                    variant = variant,
                    completed = read != null,
                    engineLastCompletedAt = ocr.lastCompletionAt(),
                    now = now,
                )
            }
            resolved
        }
        val classified = shop.copy(items = items)
        return result.copy(shopObservation = prices?.observe(bitmap, classified) ?: classified)
    }

    private companion object {
        val STANDARD_REFERENCE = EntryReferenceSize(1920, 1080)
        val TITLE_RECTS = mapOf(
            "shop_item_1" to EntryReferenceRect(125, 230, 390, 55),
            "shop_item_2" to EntryReferenceRect(703, 230, 390, 55),
            "shop_item_3" to EntryReferenceRect(1278, 230, 390, 55),
        )
    }
}
