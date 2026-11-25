package com.landosol.toolbox.labyrinth.vision

import android.graphics.Bitmap

/** Uses the existing OCR callback owner; no additional OCR engine or request queue. */
class AndroidLabyrinthShopRefreshResolver(
    submitTextRead: (Bitmap, (String?) -> Unit) -> Unit = AndroidChineseRoleNameOcr::readAsync,
) {
    private val ocr = AndroidRelicRoiOcr(submitTextRead, maximumAttempts = 6)
    // Separate crop ownership on the same submitter/engine; neither field can starve the other.
    private val feeOcr = AndroidRelicRoiOcr(submitTextRead, maximumAttempts = 6)

    @Synchronized
    fun clear() { ocr.clear(); feeOcr.clear() }

    @Synchronized
    fun resolve(bitmap: Bitmap, result: LabyrinthEntryFrameResult, capturedAtMillis: Long): LabyrinthEntryFrameResult {
        // The common modal detector is the structural gate. Do not OCR every shop/map page,
        // and do not depend on SHOP: the legacy classifier sometimes calls this modal EXIT.
        if (result.nodeMoveConfirmation == null) {
            clear()
            return result.copy(shopRefreshDialog = null)
        }
        val rect = ReferenceFitMapper.map(
            frameWidth = bitmap.width,
            frameHeight = bitmap.height,
            referenceSize = STANDARD_REFERENCE,
            referenceRect = TITLE_AND_BODY_ROI,
        ) ?: return result.copy(shopRefreshDialog = LabyrinthShopRefreshDialog())
        val feeRect = ReferenceFitMapper.map(bitmap.width, bitmap.height, STANDARD_REFERENCE, REQUIRED_FEE_ROI)
            ?: return result.copy(shopRefreshDialog = LabyrinthShopRefreshDialog())
        val read = ocr.read(bitmap, "shop-refresh-dialog", rect, scale = 2, capturedAtMillis = capturedAtMillis)
        val feeRead = feeOcr.read(bitmap, "shop-refresh-required-fee", feeRect, scale = 2, capturedAtMillis = capturedAtMillis)
        val dialog = LabyrinthShopRefreshDialogText.parseRegions(
            read?.text, read?.confirmations ?: 0, feeRead?.text, feeRead?.confirmations ?: 0,
        ).copy(
            evidenceId = feeRead?.id,
            capturedAtMillis = minOf(read?.capturedAtMillis ?: Long.MIN_VALUE, feeRead?.capturedAtMillis ?: Long.MIN_VALUE),
        )
        if (read != null || feeRead != null) android.util.Log.d("LabyrinthShopRefresh",
            "kind=" + dialog.kind + " fee=" + dialog.fee + " ready=" + dialog.confirmed +
                " textReads=" + (read?.confirmations ?: 0) + " feeReads=" + (feeRead?.confirmations ?: 0) +
                " text=" + read?.text + " feeText=" + feeRead?.text)
        return result.copy(shopRefreshDialog = dialog)
    }

    private companion object {
        val STANDARD_REFERENCE = EntryReferenceSize(1920, 1080)
        // Modal title/question only: excludes both amount rows, buttons and rounded outer corners.
        val TITLE_AND_BODY_ROI = EntryReferenceRect(690, 270, 540, 225)
        // 1280x720: x820..930, y356..400. Includes required 300/600, excludes held amount at y416.
        val REQUIRED_FEE_ROI = EntryReferenceRect(1230, 534, 165, 66)
    }
}
