package com.landosol.toolbox.labyrinth.vision

import android.graphics.Bitmap

/**
 * Reads the wallet balance shown at the shop bottom bar (「持有阿尔法金币 <N>」).
 *
 * The balance gates every purchase/refresh decision, so a single noisy OCR read must never be
 * trusted: this resolver keeps re-reading the same crop (its cache is retired when a read is not
 * confirmed) and only reports [LabyrinthShopObservation.coinBalanceConfirmed] once two independent
 * reads agree. Until then the policy waits instead of clicking.
 */
class AndroidLabyrinthShopCoinResolver(
    submitTextRead: (Bitmap, (String?) -> Unit) -> Unit = AndroidChineseRoleNameOcr::readAsync,
) {
    private val ocr = AndroidRelicRoiOcr(submitTextRead, maximumAttempts = 6)

    @Synchronized
    fun clear() {
        ocr.clear()
    }

    @Synchronized
    fun resolve(bitmap: Bitmap, result: LabyrinthEntryFrameResult): LabyrinthEntryFrameResult {
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
        val read = ocr.read(bitmap, "shop-coin", rect, scale = 2)
        val value = parseCoinBalance(read?.text)
        val confirmed = value != null && read != null && read.confirmations >= 2
        return result.copy(
            shopObservation = shop.copy(
                coinBalance = value,
                coinBalanceConfirmed = confirmed,
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
