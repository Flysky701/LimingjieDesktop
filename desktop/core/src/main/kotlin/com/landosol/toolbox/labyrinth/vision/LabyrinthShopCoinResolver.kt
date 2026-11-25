package com.landosol.toolbox.labyrinth.vision

import com.landosol.toolbox.core.MonotonicClock
import com.landosol.toolbox.vision.NativeBitmap

/**
 * Reads the wallet balance shown at the shop bottom bar (「持有阿尔法金币 <N>」).
 *
 * The balance gates every purchase/refresh decision, so a single noisy OCR read must never be
 * trusted: this resolver keeps re-reading the same crop (its cache is retired when a read is not
 * confirmed) and only reports [LabyrinthShopObservation.coinBalanceConfirmed] once two independent
 * reads agree. Until then the policy waits instead of clicking.
 */
class LabyrinthShopCoinResolver(
    submitTextRead: (NativeBitmap, (String?) -> Unit) -> Unit = DesktopChineseRoleNameOcr::readAsync,
    clock: () -> Long = MonotonicClock::elapsedRealtime,
) {
    private val ocr = RelicRoiOcr(submitTextRead, maximumAttempts = 2, clock = clock)

    @Synchronized
    fun clear() {
        ocr.clear()
    }

    @Synchronized
    fun resolve(bitmap: NativeBitmap, result: LabyrinthEntryFrameResult): LabyrinthEntryFrameResult {
        val shop = result.shopObservation
        if (result.observation.state != LabyrinthEntryPageState.SHOP ||
            shop == null ||
            shop.dialogState != LabyrinthShopDialogState.NONE
        ) {
            clear()
            return result
        }
        val rect = ReferenceFitMapper.map(
            frameWidth = bitmap.width,
            frameHeight = bitmap.height,
            referenceSize = STANDARD_REFERENCE,
            referenceRect = COIN_ROI,
        ) ?: return result
        // Dual preprocessing redundancy: compare distinct scale/padding variants rather than
        // repeating the exact same deterministic CPU ORT crop twice.
        val read1 = ocr.read(bitmap, "shop-coin-s2", rect, scale = 2)
        val val1 = parseCoinBalance(read1?.text)
        val read2 = if (read1 != null) ocr.read(bitmap, "shop-coin-s3", rect, scale = 3) else null
        val val2 = parseCoinBalance(read2?.text)
        if (val1 != null && val2 == val1) {
            return result.copy(
                shopObservation = shop.copy(coinBalance = val1, coinBalanceConfirmed = true),
            )
        }
        val read3 = if (read1 != null && read2 != null) {
            val padLeft = (rect.left - 4).coerceAtLeast(0)
            val padTop = (rect.top - 4).coerceAtLeast(0)
            val padRight = (rect.left + rect.width + 4).coerceAtMost(bitmap.width)
            val padBottom = (rect.top + rect.height + 4).coerceAtMost(bitmap.height)
            val padded = EntryPixelRect(padLeft, padTop, padRight - padLeft, padBottom - padTop)
            ocr.read(bitmap, "shop-coin-s4", padded, scale = 4)
        } else null
        val val3 = parseCoinBalance(read3?.text)
        val agreed = listOfNotNull(val1, val2, val3)
            .groupingBy { it }.eachCount()
            .entries.firstOrNull { it.value >= 2 }?.key
        val candidate = agreed ?: val3 ?: val2 ?: val1
        return result.copy(
            shopObservation = shop.copy(
                coinBalance = candidate,
                coinBalanceConfirmed = agreed != null,
            ),
        )
    }

    private companion object {
        val STANDARD_REFERENCE = EntryReferenceSize(1920, 1080)

        /** Wallet value right of 「持有阿尔法金币」, widened for 5-digit balances. */
        val COIN_ROI = EntryReferenceRect(1215, 942, 210, 72)

        /** Accepts only a pure digit/separator amount (e.g. 6,685 / 6685 / 12,345). */
        fun parseCoinBalance(text: String?): Int? {
            if (text.isNullOrBlank()) return null
            val sb = StringBuilder()
            for (c in text) {
                when {
                    c in '0'..'9' -> sb.append(c)
                    c == ',' || c == '\uFF0C' -> {}
                    c.isWhitespace() -> {}
                    else -> return null
                }
            }
            if (sb.isEmpty()) return null
            val value = sb.toString().toLongOrNull() ?: return null
            if (value < 0L || value > 999_999_999L) return null
            return value.toInt()
        }
    }
}
