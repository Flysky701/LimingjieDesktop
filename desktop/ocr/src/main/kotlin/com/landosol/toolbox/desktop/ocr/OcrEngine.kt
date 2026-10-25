package com.landosol.toolbox.desktop.ocr
import kotlinx.coroutines.flow.StateFlow

sealed interface OcrEngineStatus {
    data object NotLoaded : OcrEngineStatus
    data object Loading : OcrEngineStatus
    data object Ready : OcrEngineStatus
    data class Failed(val reason: String) : OcrEngineStatus
}
interface OcrEngine : AutoCloseable {
    /** ARGB, non-premultiplied, owned by the request; every submission completes once. */
    fun submit(width: Int, height: Int, pixels: IntArray, onResult: (String?) -> Unit)
    val status: StateFlow<OcrEngineStatus>
    fun warmUp()
}
