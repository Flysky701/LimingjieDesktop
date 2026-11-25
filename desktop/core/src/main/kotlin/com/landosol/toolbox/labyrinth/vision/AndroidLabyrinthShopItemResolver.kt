package com.landosol.toolbox.labyrinth.vision

import android.graphics.Bitmap

/** Every visible title is classified before a relic match can authorize a purchase. */
class AndroidLabyrinthShopItemResolver(
    submitTextRead: (Bitmap, (String?) -> Unit) -> Unit = AndroidChineseRoleNameOcr::readAsync,
    private val clock: () -> Long = android.os.SystemClock::elapsedRealtime,
) {
    constructor(submitTextRead: (Bitmap, (String?) -> Unit) -> Unit) :
        this(submitTextRead, android.os.SystemClock::elapsedRealtime)

    private val ocr = AndroidRelicRoiOcr(submitTextRead, maximumAttempts = 2, clock = clock)
    private val categories = LabyrinthShopCategoryResolution()
    private val accepted = mutableMapOf<String, Pair<Long, RelicOcrScheduler.Read>>()

    @Synchronized
    fun clear() {
        ocr.clear()
        categories.clear()
        accepted.clear()
    }

    @Synchronized
    fun resolve(bitmap: Bitmap, result: LabyrinthEntryFrameResult): LabyrinthEntryFrameResult {
        if (result.observation.state != LabyrinthEntryPageState.SHOP) {
            clear()
            return result
        }
        val shop = result.shopObservation ?: return result
        val items = shop.items.map { item ->
            item.skipOcrIfButtonUnknown()?.let { unknown ->
                // Non-FULL/unknown controls do not consume OCR or reuse pre-scroll title evidence.
                // FULL UNKNOWN remains READING so the controller requests another recognition.
                accepted.remove(item.slotId)
                return@map unknown
            }
            if (item.status == LabyrinthShopItemStatus.DISABLED) {
                accepted.remove(item.slotId)
                categories.resetSlot(item.slotId)
                return@map item
            }
            // Real frames carry the localized pixel crop. Original fixed rectangles remain a mock/legacy fallback.
            val titleRect = item.titleRect ?: TITLE_RECTS[item.slotId]?.let { referenceRect ->
                ReferenceFitMapper.map(bitmap.width, bitmap.height, STANDARD_REFERENCE, referenceRect)
            } ?: return@map item.copy(categoryState = LabyrinthShopCategoryState.READING)
            val fingerprint = relicRoiFingerprint(bitmap, titleRect)
            val acceptedRead = accepted[item.slotId]?.takeIf { it.first == fingerprint }?.second
            if (acceptedRead != null) {
                val settled = categories.resolve(item, acceptedRead.text, acceptedRead.id, 0)
                // A nameless imprint must not freeze its accepted read: the role name is what
                // authorizes spending coins on it, so the remaining crop variants keep their
                // chance instead of ending the visit with the good still on the shelf.
                if (!categories.needsRoleName(settled)) return@map settled
                accepted.remove(item.slotId)
            }
            val now = clock()
            val titleAttempt = categories.attempt(item.slotId, fingerprint, now)
            val variant = minOf(titleAttempt, 2)
            val rect = if (variant == 2) titleRect.copy(
                top = maxOf(0, titleRect.top - 3),
                height = minOf(bitmap.height - maxOf(0, titleRect.top - 3), titleRect.height + 6),
            ) else titleRect
            val ocrFingerprint = if (rect == titleRect) fingerprint else relicRoiFingerprint(bitmap, rect)
            val attempt = if (variant == 2) categories.noteVariantChurn(item.slotId, ocrFingerprint, now) else titleAttempt
            if (categories.isJitterExhausted(item.slotId, now)) {
                return@map categories.resolve(item, null, null, attempt)
            }
            val ocrSlot = "shop-title-${item.slotId}-$variant"
            val read = ocr.read(bitmap, ocrSlot, rect, scale = if (variant == 0) 2 else 3, fingerprint = ocrFingerprint)
            val resolved = categories.resolve(item, read?.text, read?.id, attempt)
            val labelPending = categories.needsRoleName(resolved) &&
                categories.labelGraceOpen(item.slotId, now)
            if (resolved.categoryState == LabyrinthShopCategoryState.READY && read?.text != null && !labelPending) {
                accepted[item.slotId] = fingerprint to read
                categories.resetSlot(item.slotId)
            } else {
                val variantDone = read?.text != null || ocr.isExhaustedWithoutRead(ocrSlot, ocrFingerprint)
                categories.noteVariantOutcome(
                    slot = item.slotId,
                    fingerprint = fingerprint,
                    variant = variant,
                    completed = variantDone,
                    engineLastCompletedAt = ocr.lastCompletionAt(),
                    now = now,
                )
            }
            if (labelPending) resolved.copy(imprintLabelPending = true) else resolved
        }
        return result.copy(shopObservation = shop.copy(items = items))
    }

    private companion object {
        val STANDARD_REFERENCE = EntryReferenceSize(1920, 1080)
        val TITLE_RECTS = mapOf(
            "shop_item_1" to EntryReferenceRect(125, 230, 390, 55),
            "shop_item_2" to EntryReferenceRect(703, 230, 390, 55),
            "shop_item_3" to EntryReferenceRect(1278, 230, 390, 55),
            "shop_item_4" to EntryReferenceRect(125, 513, 390, 55),
            "shop_item_5" to EntryReferenceRect(703, 513, 390, 55),
            "shop_item_6" to EntryReferenceRect(1278, 513, 390, 55),
        )
    }
}
