package com.landosol.toolbox.desktop.shared

import android.graphics.Bitmap
import com.landosol.toolbox.automation.capture.*
import java.util.concurrent.Executors
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class CapturePixels(val width: Int, val height: Int, val pixels: IntArray, val timestampMillis: Long)

/** Owns the capture thread; upstream owns each Bitmap after bus dispatch. */
class DesktopCaptureService(
    private val source: suspend () -> CapturePixels,
    private val preview: (CapturePixels) -> Unit = {},
    private val onWarning: (String) -> Unit = {},
    private val onLost: (String) -> Unit = {},
) : AutoCloseable {
    private val dispatcher = Executors.newSingleThreadExecutor { Thread(it, "desktop-capture").apply { isDaemon = true } }.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val lifecycle = Mutex()
    private var job: Job? = null
    suspend fun start() = lifecycle.withLock {
        if (job?.isActive == true) return@withLock
        job?.join()
        val ready = CompletableDeferred<Unit>()
        job = scope.launch {
            val stability = CaptureDisplayStabilityGate()
            val rotation = CaptureTransientRotationGate()
            var captureSize: CaptureDisplayMetrics? = null
            var failures = 0
            var blackSince: Long? = null
            var warnedBlack = false
            var lastPreview = Long.MIN_VALUE
            try {
                while (isActive) {
                    val started = System.nanoTime()
                    val frame = try { source().also { failures = 0 } }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Exception) {
                        failures++
                        if (failures >= 5) throw IllegalStateException("连续 5 次截图失败：${failure.message}", failure)
                        delay(300); continue
                    }
                    val size = CaptureDisplayMetrics(frame.width, frame.height, 160)
                    if (captureSize == null) {
                        captureSize = stability.observe(size)
                        if (captureSize != null) {
                            CaptureStateRegistry.update(CaptureState.Running(frame.width, frame.height, frame.timestampMillis))
                            ready.complete(Unit)
                        } else if (stability.exhausted) throw IllegalStateException("截图尺寸未稳定为横屏，请调整模拟器方向后重新连接")
                    }
                    val initial = captureSize
                    if (initial != null) {
                        val decision = rotation.observe(initial.width, initial.height, frame.width, frame.height,
                            android.os.SystemClock.elapsedRealtime(), CaptureRotationPolicy.toleratesTransientRotation())
                        if (decision is CaptureRotationDecision.Stop) throw IllegalStateException(decision.reason)
                        if (decision.deliversFrame) {
                            if (lastPreview == Long.MIN_VALUE || frame.timestampMillis - lastPreview >= 500) {
                                preview(frame.copy(pixels = frame.pixels.copyOf()))
                                lastPreview = frame.timestampMillis
                            }
                            val bitmap = Bitmap.createBitmap(frame.pixels, frame.width, frame.height, Bitmap.Config.ARGB_8888)
                            CaptureFrameBus.dispatch(CapturedFrame(bitmap, frame.timestampMillis))
                        }
                    }
                    if (frame.pixels.all { it and 0xffffff == 0 }) {
                        if (blackSince == null) blackSince = System.nanoTime()
                        if (!warnedBlack && System.nanoTime() - blackSince!! >= 10_000_000_000L) {
                            warnedBlack = true; onWarning("截图持续全黑：模拟器窗口可能被最小化，或渲染模式不兼容")
                        }
                    } else { blackSince = null; warnedBlack = false }
                    val elapsed = (System.nanoTime() - started) / 1_000_000
                    if (elapsed < 300) delay(300 - elapsed)
                }
            } catch (cancelled: CancellationException) {
                ready.cancel(cancelled)
                throw cancelled
            } catch (failure: Exception) {
                val reason = failure.message ?: "屏幕捕获失败"
                CaptureStateRegistry.update(CaptureState.Stopped(reason))
                ready.completeExceptionally(failure)
                onLost(reason)
            } finally {
                if (CaptureStateRegistry.isActive()) CaptureStateRegistry.update(CaptureState.Stopped("屏幕捕获已停止"))
            }
        }
        try { withTimeout(5_000) { ready.await() } }
        catch (failure: TimeoutCancellationException) {
            job?.cancel()
            throw IllegalStateException("屏幕捕获启动超过 5 秒，请检查模拟器方向、连接和截图速度后重试", failure)
        }
        catch (failure: Exception) { job?.cancel(); throw failure }
    }
    fun stop(reason: String = "用户停止屏幕捕获") {
        CaptureStateRegistry.update(CaptureState.Stopped(reason))
        job?.cancel()
    }
    suspend fun stopAndJoin(reason: String) { stop(reason); job?.join(); job = null }
    override fun close() { stop("应用退出"); scope.cancel(); dispatcher.close() }
}
