package com.landosol.toolbox.labyrinth.vision
import android.graphics.Bitmap
import com.landosol.toolbox.clanbattle.recognition.AndroidPixelImageAdapter
import com.landosol.toolbox.desktop.shared.readSeriesColumn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import com.landosol.toolbox.desktop.shared.DesktopBindings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

internal object AndroidChineseRoleNameOcr {
    private val callbacks = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    fun readSeriesAsync(bitmap: Bitmap, callback: (String?) -> Unit) {
        val image = AndroidPixelImageAdapter.from(bitmap)
        val ocr = DesktopBindings.services.ocr
        callbacks.launch {
            var result: String? = null
            try {
                result = withContext(Dispatchers.Default) { readSeriesColumn(image, ocr) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Complete the scheduler ticket with an unconfirmed read.
            } finally {
                callback(result)
            }
        }
    }

    fun readAsync(bitmap: Bitmap, callback: (String?) -> Unit) {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        DesktopBindings.services.ocr.submit(bitmap.width, bitmap.height, pixels) { result -> callbacks.launch { callback(result?.trim()) } }
    }
}
