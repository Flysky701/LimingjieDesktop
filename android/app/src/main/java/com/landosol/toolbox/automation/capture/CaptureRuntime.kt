package com.landosol.toolbox.automation.capture

import android.view.Display
import java.nio.ByteBuffer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class CaptureDisplayMetrics(
    val width: Int,
    val height: Int,
    val densityDpi: Int,
)

/** Pure state gate kept separate so the portrait-to-landscape startup race is JVM-testable. */
internal class CaptureDisplayStabilityGate(
    private val requiredStableSamples: Int = 3,
    private val maxSamples: Int = 15,
) {
    private var previous: CaptureDisplayMetrics? = null
    private var stableSampleCount = 0
    private var totalSampleCount = 0

    val exhausted: Boolean
        get() = totalSampleCount >= maxSamples

    init {
        require(requiredStableSamples > 0)
        require(maxSamples >= requiredStableSamples)
    }

    fun observe(metrics: CaptureDisplayMetrics): CaptureDisplayMetrics? {
        totalSampleCount++
        stableSampleCount = if (metrics == previous) stableSampleCount + 1 else 1
        previous = metrics
        // Every recognizer and action reference is calibrated against a landscape game frame.
        // A device configured as 1080x1920 is supported by rotating the activity/display to
        // 1920x1080; accepting the pre-rotation portrait metrics would consume the one-shot
        // MediaProjection token and strand the run on UNKNOWN after the game rotates.
        return metrics.takeIf {
            metrics.width > metrics.height && stableSampleCount >= requiredStableSamples
        }
    }
}

/**
 * Whether a pure rotation of the display may be ridden out instead of stopping capture. Set by the
 * application from the selected account's channel; off by default, which is upstream's behaviour.
 */
object CaptureRotationPolicy {
    @Volatile
    var toleratesTransientRotation: () -> Boolean = { false }
}

internal sealed interface CaptureRotationDecision {
    /** Whether a frame observed with this decision may be handed to recognizers. */
    val deliversFrame: Boolean get() = false

    /** Display and capture surface agree; frames flow normally. */
    data object Deliver : CaptureRotationDecision {
        override val deliversFrame: Boolean get() = true
    }

    /** The display has just turned from the capture size to its transpose. */
    data class SuspensionStarted(
        val captureWidth: Int,
        val captureHeight: Int,
        val displayWidth: Int,
        val displayHeight: Int,
    ) : CaptureRotationDecision

    /** Still rotated, inside the grace period. */
    data object Suspended : CaptureRotationDecision

    /** The original orientation is back; the next frames may still carry the rotation animation. */
    data object Resumed : CaptureRotationDecision

    /** Back for less than the settle period. */
    data object Settling : CaptureRotationDecision

    data class Stop(val reason: String) : CaptureRotationDecision
}

/**
 * Decides what to do with frames while the default display differs from the capture surface.
 *
 * Only a pure rotation (display == transposed capture size) is tolerated, and only for
 * [graceMillis]: a channel SDK login page turns the screen portrait for a few seconds and then
 * hands the landscape game back. Any other size change, or a rotation that outlasts the grace
 * period, keeps the old fail-closed behaviour and stops capture with the original message.
 * Pure and single-threaded (the capture thread), so it is JVM-testable.
 */
internal class CaptureTransientRotationGate(
    val graceMillis: Long = 120_000L,
    private val settleMillis: Long = 1_000L,
) {
    private var suspendedSince = Long.MIN_VALUE
    private var resumedAt = Long.MIN_VALUE

    init {
        require(graceMillis > 0)
        require(settleMillis >= 0)
    }

    fun reset() {
        suspendedSince = Long.MIN_VALUE
        resumedAt = Long.MIN_VALUE
    }

    fun observe(
        captureWidth: Int,
        captureHeight: Int,
        displayWidth: Int,
        displayHeight: Int,
        nowMillis: Long,
        tolerateRotation: Boolean,
    ): CaptureRotationDecision {
        if (displayWidth == captureWidth && displayHeight == captureHeight) {
            if (suspendedSince != Long.MIN_VALUE) {
                suspendedSince = Long.MIN_VALUE
                resumedAt = nowMillis
                return CaptureRotationDecision.Resumed
            }
            if (resumedAt != Long.MIN_VALUE && nowMillis - resumedAt < settleMillis) {
                return CaptureRotationDecision.Settling
            }
            resumedAt = Long.MIN_VALUE
            return CaptureRotationDecision.Deliver
        }
        val stopReason = "屏幕尺寸从${captureWidth}x${captureHeight}变为${displayWidth}x${displayHeight}，" +
            "为避免投影画面缩在角落，请保持游戏当前方向并重新授权屏幕捕获"
        val rotatedOnly = displayWidth == captureHeight && displayHeight == captureWidth
        if (!rotatedOnly || !tolerateRotation) return CaptureRotationDecision.Stop(stopReason)
        if (suspendedSince == Long.MIN_VALUE) {
            suspendedSince = nowMillis
            resumedAt = Long.MIN_VALUE
            return CaptureRotationDecision.SuspensionStarted(captureWidth, captureHeight, displayWidth, displayHeight)
        }
        if (nowMillis - suspendedSince > graceMillis) {
            return CaptureRotationDecision.Stop("屏幕旋转超过${graceMillis / 1_000} 秒未恢复；$stopReason")
        }
        return CaptureRotationDecision.Suspended
    }
}

/**
 * Copies an ImageReader RGBA_8888 plane into Android ARGB pixel integers.
 *
 * ImageReader rows may contain padding. Reading only the validated row bytes avoids passing the
 * Image's native buffer directly to Bitmap.copyPixelsFromBuffer(), which can crash in native
 * code when the reader is being closed or its buffer limit does not include the row padding.
 */
internal fun rgba8888ToArgbPixels(
    width: Int,
    height: Int,
    pixelStride: Int,
    rowStride: Int,
    buffer: ByteBuffer,
    scratch: CaptureFrameScratch? = null,
): IntArray {
    require(width > 0 && height > 0) { "屏幕帧尺寸无效：${width}x$height" }
    require(pixelStride >= 4) { "RGBA 像素步长无效：$pixelStride" }
    require(rowStride >= width * pixelStride) {
        "RGBA 行步长无效：rowStride=$rowStride pixelStride=$pixelStride width=$width"
    }

    val lastByteExclusive = (height - 1).toLong() * rowStride +
        (width - 1).toLong() * pixelStride + 4L
    require(lastByteExclusive <= buffer.limit().toLong()) {
        "RGBA 缓冲区长度不足：需要$lastByteExclusive，实际${buffer.limit()}"
    }

    val source = buffer.duplicate().apply { position(0) }
    val rowBytes = scratch?.rowBytes(width * pixelStride) ?: ByteArray(width * pixelStride)
    val pixels = scratch?.pixels(width * height) ?: IntArray(width * height)
    for (row in 0 until height) {
        source.position(row * rowStride)
        source.get(rowBytes, 0, rowBytes.size)
        var sourceOffset = 0
        var pixelOffset = row * width
        repeat(width) {
            val red = rowBytes[sourceOffset].toInt() and 0xff
            val green = rowBytes[sourceOffset + 1].toInt() and 0xff
            val blue = rowBytes[sourceOffset + 2].toInt() and 0xff
            val alpha = rowBytes[sourceOffset + 3].toInt() and 0xff
            pixels[pixelOffset] =
                (alpha shl 24) or (red shl 16) or (green shl 8) or blue
            sourceOffset += pixelStride
            pixelOffset++
        }
    }
    return pixels
}

/**
 * Reusable per-frame conversion buffers.
 *
 * A 1920x1080 frame needs an 8.3 MB int[] and a ~7.7 KB row buffer, and a fresh pair per frame is
 * what exhausted the heap in the 2026-09-20 report ("Failed to allocate a 8294416 byte
 * allocation"). Both buffers are write-before-read for every pixel of every frame and the int[] is
 * copied by Bitmap.createBitmap before the next frame is converted, so neither survives the call
 * as live data and both can be pooled. Reuse is confined to one capture callback at a time by the
 * caller's lock; a size change simply reallocates.
 */
internal class CaptureFrameScratch {
    private var pixels = IntArray(0)
    private var rowBytes = ByteArray(0)

    fun pixels(size: Int): IntArray {
        if (pixels.size != size) pixels = IntArray(size)
        return pixels
    }

    fun rowBytes(size: Int): ByteArray {
        if (rowBytes.size != size) rowBytes = ByteArray(size)
        return rowBytes
    }
}

object CaptureStateRegistry {
    private val state = MutableStateFlow<CaptureState>(CaptureState.Idle)
    @Volatile
    private var lastStopReason: String? = null

    fun observe(): StateFlow<CaptureState> = state.asStateFlow()

    fun isActive(): Boolean = state.value is CaptureState.Running

    fun lastStopReason(): String? = lastStopReason

    fun gestureDisplayId(): Int =
        (state.value as? CaptureState.Running)?.gestureDisplayId ?: Display.DEFAULT_DISPLAY

    internal fun update(value: CaptureState) {
        when (value) {
            is CaptureState.Running -> lastStopReason = null
            is CaptureState.Stopped -> lastStopReason = value.reason
            CaptureState.Idle -> Unit
        }
        state.value = value
    }

    internal fun clear() {
        // Keep lastStopReason until the next successful Running state. The service calls clear()
        // from onDestroy immediately after reporting Stopped, and erasing the reason here makes
        // post-mortem first-frame diagnostics impossible.
        state.value = CaptureState.Idle
    }
}
