package com.landosol.toolbox.automation.capture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.Parcelable
import android.os.SystemClock
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.ByteBuffer

class MediaProjectionCaptureService : Service() {
    private val projectionManager by lazy {
        getSystemService(MediaProjectionManager::class.java)
    }
    private val notificationManager by lazy {
        getSystemService(NotificationManager::class.java)
    }
    private val windowManager by lazy {
        getSystemService(WindowManager::class.java)
    }
    private val displayManager by lazy {
        getSystemService(DisplayManager::class.java)
    }
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit

        override fun onDisplayRemoved(displayId: Int) = Unit

        override fun onDisplayChanged(displayId: Int) {
            if (displayId != Display.DEFAULT_DISPLAY) return
            postDisplayReconfiguration()
        }
    }
    private val _state = MutableStateFlow<CaptureState>(CaptureState.Idle)
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var callback: MediaProjection.Callback? = null
    private var frameThread: HandlerThread? = null
    private var lastFrameAt = 0L
    private var captureStartedAt = 0L
    private val rotationGate = CaptureTransientRotationGate()
    /** ImageReader buffers must not be closed while a callback is copying a frame. */
    private val captureLock = Any()
    /** Only ever touched inside [captureLock], one frame conversion at a time. */
    private val frameScratch = CaptureFrameScratch()

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        displayManager.registerDisplayListener(displayListener, Handler(Looper.getMainLooper()))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startCapture(intent)
            ACTION_STOP -> stopCapture("用户停止屏幕捕获")
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        postDisplayReconfiguration()
    }

    override fun onDestroy() {
        displayManager.unregisterDisplayListener(displayListener)
        stopCapture("捕获服务销毁")
        CaptureStateRegistry.clear()
        super.onDestroy()
    }

    private fun startCapture(intent: Intent) {
        if (projection != null) return
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Int.MIN_VALUE)
        val data = intent.parcelableExtra<Intent>(EXTRA_DATA) ?: run {
            stopCapture("缺少系统截图授权数据")
            return
        }
        if (resultCode == Int.MIN_VALUE) {
            stopCapture("缺少系统截图授权结果")
            return
        }
        try {
            startAsForeground()
            val mediaProjection = projectionManager.getMediaProjection(resultCode, data)
                ?: error("系统未返回屏幕投影")
            projection = mediaProjection
            captureStartedAt = System.currentTimeMillis()
            Log.i("LandosolCapture", "projection-start sdk=${Build.VERSION.SDK_INT} elapsedMillis=${SystemClock.elapsedRealtime()}")
            val thread = HandlerThread("landosol-capture").also { it.start() }
            frameThread = thread
            val projectionCallback = object : MediaProjection.Callback() {
                override fun onStop() {
                    // Android provides no reason argument; do not infer a specific user action.
                    Log.w("LandosolCapture", "projection-onStop elapsedMillis=${SystemClock.elapsedRealtime()} captureStartedAt=$captureStartedAt")
                    stopCapture("系统撤销屏幕捕获授权")
                }

                override fun onCapturedContentResize(width: Int, height: Int) {
                    Log.i("LandosolCapture", "projection-content-resize width=$width height=$height elapsedMillis=${SystemClock.elapsedRealtime()}")
                }

                override fun onCapturedContentVisibilityChanged(isVisible: Boolean) {
                    Log.i("LandosolCapture", "projection-content-visibility visible=$isVisible elapsedMillis=${SystemClock.elapsedRealtime()}")
                }
            }
            callback = projectionCallback
            mediaProjection.registerCallback(projectionCallback, Handler(thread.looper))
            awaitStableInitialDisplay(mediaProjection, thread)
        } catch (error: Throwable) {
            stopCapture("启动屏幕捕获失败：${error.message ?: "未知错误"}")
        }
    }

    /**
     * Returning from Android's capture picker can bring the landscape game to the foreground a few
     * milliseconds after this service starts. Creating the VirtualDisplay immediately therefore
     * races the old portrait toolbox metrics against the new landscape game metrics. Since a
     * MediaProjection token may create only one VirtualDisplay on Android 14+, wait for consecutive
     * equal samples before consuming the token.
     */
    private fun awaitStableInitialDisplay(mediaProjection: MediaProjection, thread: HandlerThread) {
        val handler = Handler(thread.looper)
        val stabilityGate = CaptureDisplayStabilityGate()
        val sampler = object : Runnable {
            override fun run() {
                if (projection !== mediaProjection || virtualDisplay != null) return
                val stableMetrics = stabilityGate.observe(currentDisplayMetrics())
                if (stableMetrics == null) {
                    if (stabilityGate.exhausted) {
                        stopCapture(
                            "屏幕捕获仍为竖屏；请确认模拟器允许自动旋转，并在横屏的助手或游戏界面重新授权",
                        )
                        return
                    }
                    handler.postDelayed(this, INITIAL_DISPLAY_SAMPLE_INTERVAL_MILLIS)
                    return
                }
                runCatching { configureDisplay(mediaProjection, thread, stableMetrics) }
                    .onFailure { error ->
                        stopCapture("启动屏幕捕获失败：${error.message ?: "未知错误"}")
                    }
            }
        }
        handler.post(sampler)
    }

    private fun reconfigureDisplayIfNeeded() {
        val snapshot = synchronized(captureLock) { projection to frameThread }
        snapshot.first ?: return
        val thread = snapshot.second ?: return
        val metrics = currentDisplayMetrics()
        val readerSize = synchronized(captureLock) {
            imageReader?.let { reader -> reader.width to reader.height }
        } ?: return
        applyRotationDecision(
            rotationGate.observe(
                captureWidth = readerSize.first,
                captureHeight = readerSize.second,
                displayWidth = metrics.width,
                displayHeight = metrics.height,
                nowMillis = System.currentTimeMillis(),
                tolerateRotation = CaptureRotationPolicy.toleratesTransientRotation(),
            ),
            thread,
        )
    }

    /**
     * Android 14+ does not allow creating a second VirtualDisplay from the same projection token.
     * Reusing the old display with resize()/setSurface() looks valid on AOSP, but MuMu can keep the
     * old mirror transform and render the new landscape game into only one corner of the larger
     * buffer. That produces a syntactically valid Bitmap which every downstream recognizer then
     * reports as UNKNOWN. So the capture surface is never resized: a lasting size change still
     * requires a fresh MediaProjection authorization.
     *
     * A channel SDK login page is the exception. 2026-09-25 vivo diag: after 返回标题 the vivo SDK
     * opened its portrait UnionActivity, logged in by itself and rotated back four seconds later,
     * but the first portrait callback had already stopped capture, so the session reset sat in
     * WAITING_LOGIN until the user re-authorised. 4399's 账号授权 page is portrait as well. While the
     * display is only rotated, the untouched landscape VirtualDisplay keeps mirroring; its frames
     * are dropped until the original orientation is back, and only then are frames delivered again.
     */
    private fun applyRotationDecision(decision: CaptureRotationDecision, thread: HandlerThread) {
        when (decision) {
            CaptureRotationDecision.Deliver,
            CaptureRotationDecision.Settling,
            CaptureRotationDecision.Suspended,
            -> Unit
            is CaptureRotationDecision.SuspensionStarted -> {
                android.util.Log.w(
                    LOG_TAG,
                    "屏幕临时旋转为${decision.displayWidth}x${decision.displayHeight}，暂停投递画面，" +
                        "等待恢复${decision.captureWidth}x${decision.captureHeight}" +
                        "（最长${rotationGate.graceMillis / 1_000} 秒）",
                )
                Handler(thread.looper).postDelayed(
                    { reconfigureDisplayIfNeeded() },
                    rotationGate.graceMillis + ROTATION_RECHECK_SLACK_MILLIS,
                )
            }
            CaptureRotationDecision.Resumed ->
                android.util.Log.i(LOG_TAG, "屏幕方向已恢复，继续投递画面")
            is CaptureRotationDecision.Stop -> stopCapture(decision.reason)
        }
    }

    private fun postDisplayReconfiguration() {
        val thread = frameThread ?: return
        Handler(thread.looper).post { reconfigureDisplayIfNeeded() }
    }

    private fun configureDisplay(
        mediaProjection: MediaProjection,
        thread: HandlerThread,
        metrics: CaptureDisplayMetrics = currentDisplayMetrics(),
    ) {
        synchronized(captureLock) {
            val width = metrics.width
            val height = metrics.height
            val density = metrics.densityDpi
            Log.i("LandosolCapture", "configure-display width=$width height=$height densityDpi=$density flags=${captureDisplayFlags()} elapsedMillis=${SystemClock.elapsedRealtime()}")
            require(width > 0 && height > 0) { "系统返回了无效的屏幕尺寸" }

            val handler = Handler(thread.looper)
            val reader = ImageReader.newInstance(
                width,
                height,
                android.graphics.PixelFormat.RGBA_8888,
                2,
            )
            val oldReader = imageReader
            val existingDisplay = virtualDisplay
            if (existingDisplay == null) {
                mediaProjection.createVirtualDisplay(
                    "LandosolToolboxCapture",
                    width,
                    height,
                    density,
                    captureDisplayFlags(),
                    reader.surface,
                    null,
                    handler,
                ).also { created ->
                    virtualDisplay = created
                }
            } else {
                // Android 14+ permits only one createVirtualDisplay() call per MediaProjection
                // instance. Rotation therefore must reuse the original VirtualDisplay instead of
                // releasing it and creating a second one with the same projection token.
                oldReader?.setOnImageAvailableListener(null, null)
                existingDisplay.resize(width, height, density)
                existingDisplay.setSurface(reader.surface)
            }
            imageReader = reader
            if (oldReader !== reader) {
                oldReader?.close()
            }
            lastFrameAt = 0L
            rotationGate.reset()
            reader.setOnImageAvailableListener({ source -> onImageAvailable(source) }, handler)
            setState(
                CaptureState.Running(
                    width = width,
                    height = height,
                    startedAt = captureStartedAt,
                    // MediaProjection's VirtualDisplay is only the capture output. Input must
                    // target the real display being mirrored, not this synthetic display id.
                    gestureDisplayId = Display.DEFAULT_DISPLAY,
                ),
            )
        }
    }

    @Suppress("DEPRECATION")
    private fun currentDisplayMetrics(): CaptureDisplayMetrics {
        val defaultDisplay = displayManager.getDisplay(Display.DEFAULT_DISPLAY)
        if (defaultDisplay != null) {
            val metrics = DisplayMetrics().also(defaultDisplay::getRealMetrics)
            if (metrics.widthPixels > 0 && metrics.heightPixels > 0) {
                return CaptureDisplayMetrics(metrics.widthPixels, metrics.heightPixels, metrics.densityDpi)
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.currentWindowMetrics.bounds
            return CaptureDisplayMetrics(bounds.width(), bounds.height(), resources.displayMetrics.densityDpi)
        }
        val metrics = DisplayMetrics().also(windowManager.defaultDisplay::getRealMetrics)
        return CaptureDisplayMetrics(metrics.widthPixels, metrics.heightPixels, metrics.densityDpi)
    }

    private fun onImageAvailable(source: ImageReader) {
        val now = System.currentTimeMillis()
        var bitmap: Bitmap? = null
        var conversionError: Throwable? = null
        // Orientation is judged only for frames the interval would convert, so a rotated display
        // costs one metrics read per FRAME_INTERVAL_MILLIS rather than one per mirrored frame.
        val rotation = if (now - lastFrameAt < FRAME_INTERVAL_MILLIS) {
            null
        } else {
            val metrics = currentDisplayMetrics()
            rotationGate.observe(
                captureWidth = source.width,
                captureHeight = source.height,
                displayWidth = metrics.width,
                displayHeight = metrics.height,
                nowMillis = now,
                tolerateRotation = CaptureRotationPolicy.toleratesTransientRotation(),
            ).also { decision -> frameThread?.let { thread -> applyRotationDecision(decision, thread) } }
        }
        synchronized(captureLock) {
            val image = runCatching { source.acquireLatestImage() }.getOrNull() ?: return
            if (rotation == null) {
                runCatching { image.close() }
            } else if (!rotation.deliversFrame) {
                // A rotated mirror is letterboxed portrait content; never hand it to a recognizer.
                lastFrameAt = now
                runCatching { image.close() }
            } else {
                lastFrameAt = now
                try {
                    bitmap = imageToBitmap(image)
                } catch (error: Throwable) {
                    conversionError = error
                } finally {
                    // Close the acquired image before another thread can close/rebuild the reader.
                    runCatching { image.close() }
                }
            }
        }
        bitmap?.let { CaptureFrameBus.dispatch(CapturedFrame(it, now)) }
        conversionError?.let { error ->
            stopCapture("读取屏幕帧失败：${error.message ?: "未知错误"}")
        }
    }

    private fun imageToBitmap(image: Image): Bitmap {
        val plane = image.planes.firstOrNull() ?: error("屏幕帧没有像素平面")
        val pixels = rgba8888ToArgbPixels(
            width = image.width,
            height = image.height,
            pixelStride = plane.pixelStride,
            rowStride = plane.rowStride,
            buffer = plane.buffer,
            // createBitmap below copies these pixels, so the buffer is free again on return.
            scratch = frameScratch,
        )
        return Bitmap.createBitmap(
            pixels,
            image.width,
            image.height,
            Bitmap.Config.ARGB_8888,
        )
    }

    private fun startAsForeground() {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle(getString(com.landosol.toolbox.R.string.capture_notification_title))
            .setContentText(getString(com.landosol.toolbox.R.string.capture_notification_text))
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun stopCapture(reason: String) {
        val hadResources: Boolean
        synchronized(captureLock) {
            hadResources = projection != null || virtualDisplay != null || imageReader != null
            Log.w("LandosolCapture", "capture-stop reason=$reason hadResources=$hadResources " +
                "elapsedMillis=${SystemClock.elapsedRealtime()} captureStartedAt=$captureStartedAt " +
                "reader=${imageReader?.width}x${imageReader?.height}")
            imageReader?.setOnImageAvailableListener(null, null)
            imageReader?.close()
            imageReader = null
            virtualDisplay?.release()
            virtualDisplay = null
            callback?.let { projection?.unregisterCallback(it) }
            callback = null
            projection?.stop()
            projection = null
        }
        frameThread?.quitSafely()
        frameThread = null
        captureStartedAt = 0L
        if (hadResources) setState(CaptureState.Stopped(reason))
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun setState(value: CaptureState) {
        _state.value = value
        CaptureStateRegistry.update(value)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        notificationManager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(com.landosol.toolbox.R.string.capture_notification_channel),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }

    private inline fun <reified T : Parcelable> Intent.parcelableExtra(key: String): T? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(key, T::class.java)
        } else {
            @Suppress("DEPRECATION")
            getParcelableExtra(key)
        }

    companion object {
        private const val ACTION_START = "com.landosol.toolbox.capture.START"
        private const val ACTION_STOP = "com.landosol.toolbox.capture.STOP"
        private const val EXTRA_RESULT_CODE = "result_code"
        private const val EXTRA_DATA = "projection_data"
        private const val CHANNEL_ID = "landosol-capture"
        private const val NOTIFICATION_ID = 4101
        /**
         * Minimum interval between two converted screen frames.
         *
         * The recognition session consumes at most one frame per 500 ms. Converting every 100 ms
         * produced an 8.3 MB pixel buffer plus an 8.3 MB bitmap five times per consumed frame,
         * all recycled immediately: the four 2026-09-20 bundles show ~350 GC events and ~10.6 s of
         * GC pause per run with the heap pinned at 176/200 MB. 300 ms still stays ahead of the
         * 500 ms consumer, so frame freshness is unchanged.
         */
        private const val FRAME_INTERVAL_MILLIS = 300L
        private const val INITIAL_DISPLAY_SAMPLE_INTERVAL_MILLIS = 100L
        /** Lets the grace deadline pass before the re-check, so the gate is sure to have expired. */
        private const val ROTATION_RECHECK_SLACK_MILLIS = 500L
        private const val LOG_TAG = "LandosolCapture"
        // 公共镜像显示器会进入 Accessibility 的有效显示器集合；否则 MuMu 将游戏输入
        // 克隆到捕获显示器后，无障碍只能把手势发往不可触达游戏的默认显示器。
        private fun captureDisplayFlags(): Int =
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR or
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC
                } else {
                    0
                }

        fun start(context: Context, resultCode: Int, data: Intent) {
            val intent = Intent(context, MediaProjectionCaptureService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_DATA, data)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val stopped = context.stopService(
                Intent(context, MediaProjectionCaptureService::class.java),
            )
            if (!stopped) CaptureStateRegistry.clear()
        }
    }

}

