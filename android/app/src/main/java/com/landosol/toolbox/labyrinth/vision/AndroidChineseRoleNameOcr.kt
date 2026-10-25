package com.landosol.toolbox.labyrinth.vision

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions

/** One bundled recognizer instance for the application process; model initialization is reused. */
internal object AndroidChineseRoleNameOcr {
    private val recognizer by lazy {
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    }

    fun readAsync(bitmap: Bitmap, callback: (String?) -> Unit) {
        recognizer
            .process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { result -> callback(result.text.trim()) }
            .addOnFailureListener { callback(null) }
    }
}
