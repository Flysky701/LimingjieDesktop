package com.landosol.toolbox.vision

import android.graphics.Bitmap

object AndroidPixelImageAdapter {
    fun from(bitmap: Bitmap): PixelImage {
        require(!bitmap.isRecycled) { "截图 Bitmap 已释放" }
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return PixelImage(bitmap.width, bitmap.height, pixels)
    }
}

