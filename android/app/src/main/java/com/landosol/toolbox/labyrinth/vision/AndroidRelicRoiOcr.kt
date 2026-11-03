package com.landosol.toolbox.labyrinth.vision

import android.graphics.Bitmap

/** Owns only one crop; the OCR callback, not a timer, releases it. */
internal class AndroidRelicRoiOcr(private val submit: (Bitmap, (String?) -> Unit) -> Unit, maximumAttempts: Int = 2) {
    private val scheduler = RelicOcrScheduler({ android.os.SystemClock.elapsedRealtime() }, maximumAttempts = maximumAttempts)
    fun clear() = scheduler.clear()

    /** See [RelicOcrScheduler.lastCompletionAt]. */
    fun lastCompletionAt(): Long = scheduler.lastCompletionAt()

    fun read(bitmap: Bitmap, slot: String, rect: EntryPixelRect, scale: Int = 1,
             readText: (Bitmap, (String?) -> Unit) -> Unit = submit): RelicOcrScheduler.Read? {
        val fingerprint = relicRoiFingerprint(bitmap, rect)
        val cached = scheduler.cached(slot, fingerprint)
        val ticket = scheduler.request(slot, fingerprint) ?: return cached
        var input: Bitmap? = null
        try {
            val crop = Bitmap.createBitmap(bitmap, rect.left, rect.top, rect.width, rect.height)
            input = crop
            if (scale > 1) {
                input = Bitmap.createScaledBitmap(crop, crop.width * scale, crop.height * scale, false)
                if (input !== crop) crop.recycle()
            }
            val owned = requireNotNull(input)
            readText(owned) { text ->
                scheduler.complete(ticket, text)
                if (!owned.isRecycled) owned.recycle()
            }
        } catch (failure: Exception) {
            scheduler.complete(ticket, null)
            input?.let { if (!it.isRecycled) it.recycle() }
        }
        return cached
    }
}

/** Exact pixels of the small ROI; sparse dHash can miss a changed digit. */
internal fun relicRoiFingerprint(bitmap: Bitmap, rect: EntryPixelRect): Long {
    var hash = 1125899906842597L + rect.width * 31L + rect.height
    for (y in rect.top until rect.top + rect.height) {
        for (x in rect.left until rect.left + rect.width) hash = hash * 31 + bitmap.getPixel(x, y)
    }
    return hash
}
