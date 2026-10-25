package com.landosol.toolbox.labyrinth.vision
import android.graphics.Bitmap
import com.landosol.toolbox.desktop.shared.DesktopBindings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

internal object AndroidChineseRoleNameOcr {
    private val callbacks = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    fun readAsync(bitmap: Bitmap, callback: (String?) -> Unit) {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        DesktopBindings.services.ocr.submit(bitmap.width, bitmap.height, pixels) { result -> callbacks.launch { callback(result?.trim()) } }
    }
}
