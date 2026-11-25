package com.landosol.toolbox.labyrinth.vision
import com.landosol.toolbox.vision.NativeBitmap
import com.landosol.toolbox.desktop.shared.readSeriesColumn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import com.landosol.toolbox.desktop.shared.DesktopBindings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

internal object DesktopChineseRoleNameOcr {
    private val callbacks = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    fun readSeriesAsync(bitmap: NativeBitmap, callback: (String?) -> Unit) {
        val image = bitmap.copyToPixelImage()
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

    fun readAsync(bitmap: NativeBitmap, callback: (String?) -> Unit) {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        DesktopBindings.services.ocr.submit(bitmap.width, bitmap.height, pixels) { result -> callbacks.launch { callback(result?.trim()) } }
    }
}
