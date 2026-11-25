package com.landosol.toolbox.desktop.ocr
import kotlinx.coroutines.flow.StateFlow

sealed interface OcrEngineStatus {
    data object NotLoaded : OcrEngineStatus
    data object Loading : OcrEngineStatus
    data object Ready : OcrEngineStatus
    data class Failed(val reason: String) : OcrEngineStatus
}
data class OcrEngineTelemetry(
    val submitted: Long = 0L,
    val queueDrops: Long = 0L,
    val nullFailures: Long = 0L,
    val consecutiveFailures: Int = 0,
)

interface OcrEngine : AutoCloseable {
    /** ARGB, non-premultiplied, owned by the request; every submission completes once. */
    fun submit(width: Int, height: Int, pixels: IntArray, onResult: (String?) -> Unit)
    val status: StateFlow<OcrEngineStatus>
    fun telemetry(): OcrEngineTelemetry = OcrEngineTelemetry()
    fun warmUp()
}
