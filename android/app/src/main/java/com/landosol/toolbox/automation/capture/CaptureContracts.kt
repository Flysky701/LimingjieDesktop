package com.landosol.toolbox.automation.capture

import android.graphics.Bitmap
import java.util.concurrent.atomic.AtomicLong

sealed interface CaptureState {
    data object Idle : CaptureState
    data class Running(
        val width: Int,
        val height: Int,
        val startedAt: Long,
        /** The real source display that receives gestures; never the MediaProjection output. */
        val gestureDisplayId: Int = DEFAULT_DISPLAY_ID,
    ) : CaptureState
    data class Stopped(val reason: String) : CaptureState

    private companion object {
        const val DEFAULT_DISPLAY_ID = 0
    }
}

data class CapturedFrame(
    val bitmap: Bitmap,
    val timestampMillis: Long,
    /** Diagnostics only. Null means this producer does not publish monotonic metadata. */
    val frameSequence: Long = 0L,
    val captureConvertMillis: Long? = null,
    val capturedAtElapsedRealtimeMillis: Long? = null,
    val enqueuedAtElapsedRealtimeMillis: Long? = null,
    /**
     * Optional bitmap.getPixels snapshot, owned by this frame and read-only to every consumer.
     * Never the capture RGBA scratch, pooled storage, or an array reused by another frame.
     * Keep the frame's lease alive while reading either this array or [bitmap].
     */
    val argbPixels: IntArray? = null,
)

/**
 * CaptureFrameBus 的消费者拥有 bitmap 的释放责任；没有消费者时服务会立即 recycle。
 * A consumer that publishes retained frame handles MUST contain its own exceptions and close any
 * untransferred handles: dispatch's legacy failure fallback otherwise recycles an in-use bitmap.
 */
@JvmInline
value class CaptureFrameConsumerId(val value: Long)

data class CaptureFrameConsumerLease internal constructor(
    val id: CaptureFrameConsumerId,
    val owner: String,
)

sealed interface CaptureFrameRegistrationResult {
    data class Registered(val lease: CaptureFrameConsumerLease) : CaptureFrameRegistrationResult
    data class Busy(val owner: String) : CaptureFrameRegistrationResult
}

object CaptureFrameBus {
    private data class Registration(
        val lease: CaptureFrameConsumerLease,
        val consumer: (CapturedFrame) -> Unit,
        val wantsArgbSnapshot: Boolean,
    )

    private val sequence = AtomicLong(0)
    private var registration: Registration? = null

    @Synchronized
    fun register(owner: String, consumer: (CapturedFrame) -> Unit): CaptureFrameRegistrationResult =
        register(owner, wantsArgbSnapshot = false, consumer = consumer)

    @Synchronized
    fun register(
        owner: String,
        wantsArgbSnapshot: Boolean,
        consumer: (CapturedFrame) -> Unit,
    ): CaptureFrameRegistrationResult {
        require(owner.isNotBlank())
        registration?.let { return CaptureFrameRegistrationResult.Busy(it.lease.owner) }
        val lease = CaptureFrameConsumerLease(CaptureFrameConsumerId(sequence.incrementAndGet()), owner)
        registration = Registration(lease, consumer, wantsArgbSnapshot)
        return CaptureFrameRegistrationResult.Registered(lease)
    }

    @Synchronized
    fun unregister(lease: CaptureFrameConsumerLease): Boolean {
        if (registration?.lease?.id != lease.id) return false
        registration = null
        return true
    }

    @Synchronized
    fun currentOwner(): String? = registration?.lease?.owner

    /** Capture checks per conversion; legacy consumers never request the extra allocation. */
    @Synchronized
    fun currentWantsArgbSnapshot(): Boolean = registration?.wantsArgbSnapshot == true

    internal fun dispatch(frame: CapturedFrame) {
        val current = synchronized(this) { registration }
        if (current == null) {
            CaptureTelemetry.noConsumerDropped()
            frame.bitmap.recycle()
            return
        }
        runCatching { current.consumer(frame) }
            .onFailure { if (!frame.bitmap.isRecycled) frame.bitmap.recycle() }
    }
}
