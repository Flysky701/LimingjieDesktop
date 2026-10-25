package com.landosol.toolbox.automation.overlay

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.os.IBinder
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.landosol.toolbox.LandosolToolboxApplication
import com.landosol.toolbox.automation.AutomationSessionId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class AutomationOverlayService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val windowManager by lazy { getSystemService(WindowManager::class.java) }
    private var rootView: LinearLayout? = null
    private var annotationView: AutomationOverlayAnnotationView? = null
    private var sessionId: AutomationSessionId? = null
    private var paused = false
    private var titleView: TextView? = null
    private var statusView: TextView? = null
    private var detailView: TextView? = null
    private var pauseButton: TextView? = null
    private var overlayParams: WindowManager.LayoutParams? = null

    override fun onCreate() {
        super.onCreate()
        val app = application as LandosolToolboxApplication
        scope.launch {
            app.automationOverlayCoordinator.state.collectLatest { presentation ->
                if (presentation?.sessionId == sessionId) render(presentation)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SHOW -> show(intent.getLongExtra(EXTRA_SESSION_ID, -1L))
            ACTION_HIDE -> hide()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        removeView()
        scope.cancel()
        super.onDestroy()
    }

    private fun show(rawSessionId: Long) {
        if (rawSessionId <= 0L) return
        val requestedSessionId = AutomationSessionId(rawSessionId)
        if (!Settings.canDrawOverlays(this)) {
            reportOverlayError(requestedSessionId, "悬浮窗权限不可用")
            return
        }
        sessionId = requestedSessionId
        if (rootView != null) return
        val app = application as LandosolToolboxApplication
        val title = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 9f
            setShadowLayer(dp(1).toFloat(), 0f, 0f, Color.BLACK)
            setPadding(0, 0, 0, dp(1))
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            text = "自动化控制"
        }
        val status = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 8f
            setShadowLayer(dp(1).toFloat(), 0f, 0f, Color.BLACK)
            setPadding(0, 0, 0, dp(1))
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            text = "运行中"
        }
        val pause = TextView(this).apply {
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            textSize = 14f
            setShadowLayer(dp(1).toFloat(), 0f, 0f, Color.BLACK)
            contentDescription = "暂停"
            text = "Ⅱ"
            setOnClickListener {
                val id = sessionId ?: return@setOnClickListener
                scope.launch {
                    val nextPaused = !paused
                    if (!app.automationOverlayCoordinator.setPaused(id, nextPaused)) {
                        status.text = "会话已结束"
                        hide()
                    } else {
                        paused = nextPaused
                        render(app.automationOverlayCoordinator.state.value)
                    }
                }
            }
        }
        val stop = TextView(this).apply {
            gravity = Gravity.CENTER
            setTextColor(0xffff6b6b.toInt())
            textSize = 14f
            setShadowLayer(dp(1).toFloat(), 0f, 0f, Color.BLACK)
            contentDescription = "紧急停止"
            text = "■"
            setOnClickListener {
                val id = sessionId ?: return@setOnClickListener
                scope.launch {
                    app.automationOverlayCoordinator.stop(id)
                    hide()
                }
            }
        }
        val detail = TextView(this).apply {
            setTextColor(0xffe2e8f0.toInt())
            textSize = 7.5f
            setShadowLayer(dp(1).toFloat(), 0f, 0f, Color.BLACK)
            setPadding(0, 0, 0, dp(2))
            maxLines = 3
            ellipsize = TextUtils.TruncateAt.END
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(3), dp(4), dp(3))
            background = GradientDrawable().apply {
                setColor(0xcc1f2937.toInt())
                cornerRadius = dp(5).toFloat()
            }
            elevation = dp(2).toFloat()
            addView(title)
            addView(status)
            addView(detail)
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(pause, LinearLayout.LayoutParams(0, dp(20), 1f))
                addView(stop, LinearLayout.LayoutParams(0, dp(20), 1f))
            })
        }
        val dragListener = createDragListener(root)
        root.setOnTouchListener(dragListener)
        title.setOnTouchListener(dragListener)
        status.setOnTouchListener(dragListener)
        detail.setOnTouchListener(dragListener)
        root.contentDescription = "可拖动的自动化悬浮窗"
        title.contentDescription = "拖动悬浮窗"
        status.contentDescription = "拖动悬浮窗"
        detail.contentDescription = "拖动悬浮窗"
        val savedPosition = loadOverlayPosition()
        val params = WindowManager.LayoutParams(
            dp(112),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = clampX(savedPosition?.first ?: (resources.displayMetrics.widthPixels - dp(112) - dp(4)))
            y = clampY(savedPosition?.second ?: dp(72), root)
        }
        runCatching { windowManager.addView(root, params) }
            .onSuccess {
                rootView = root
                overlayParams = params
                titleView = title
                statusView = status
                detailView = detail
                pauseButton = pause
                addAnnotationView()
                render(app.automationOverlayCoordinator.state.value)
            }
            .onFailure { error ->
                Log.e(TAG, "无法显示自动化悬浮窗", error)
                reportOverlayError(requestedSessionId, "悬浮窗显示失败：${error.message ?: "未知错误"}")
            }
    }

    private fun render(presentation: AutomationOverlayPresentation?) {
        if (presentation == null || presentation.sessionId != sessionId) return
        paused = presentation.paused
        titleView?.text = presentation.title
        statusView?.text = buildString {
            append(presentation.status)
            if (presentation.dryRun) append(" · Dry Run")
        }
        detailView?.text = presentation.detail.orEmpty()
        // The full-screen annotation layer is itself captured by MediaProjection on MuMu. During
        // Labyrinth live recognition that caused the black diagnostic labels and outline boxes to
        // be fed back into the next frame, covering the very portraits we were trying to match.
        // Keep the boxes in the presentation for the local web dashboard, but do not paint them
        // back onto the game while the Labyrinth recognizer is active.
        val captureSafeBoxes = presentation.boxes.takeIf { presentation.renderBoxesOnDevice }.orEmpty()
        annotationView?.setBoxes(captureSafeBoxes)
        pauseButton?.apply {
            text = if (presentation.paused) "▶" else "Ⅱ"
            contentDescription = if (presentation.paused) "继续" else "暂停"
        }
    }

    private fun hide() {
        removeView()
        stopSelf()
    }

    private fun removeView() {
        annotationView?.let { view -> runCatching { windowManager.removeView(view) } }
        annotationView = null
        rootView?.let { view -> runCatching { windowManager.removeView(view) } }
        rootView = null
        overlayParams = null
        sessionId = null
        titleView = null
        statusView = null
        detailView = null
        pauseButton = null
    }

    private fun addAnnotationView() {
        if (annotationView != null) return
        val view = AutomationOverlayAnnotationView(this)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }
        runCatching { windowManager.addView(view, params) }
            .onSuccess { annotationView = view }
            .onFailure { error ->
                Log.e(TAG, "无法显示识别标注层", error)
                sessionId?.let { id ->
                    reportOverlayError(id, "悬浮窗标注层显示失败：${error.message ?: "未知错误"}")
                }
            }
    }

    private fun reportOverlayError(id: AutomationSessionId, reason: String) {
        Log.e(TAG, reason)
        val app = application as? LandosolToolboxApplication ?: return
        scope.launch {
            app.automationOverlayCoordinator.stop(id)
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun createDragListener(root: View): View.OnTouchListener {
        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop
        var downRawX = 0f
        var downRawY = 0f
        var downX = 0
        var downY = 0
        var dragging = false

        return View.OnTouchListener { _, event ->
            val params = overlayParams ?: return@OnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    downX = params.x
                    downY = params.y
                    dragging = false
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val deltaX = event.rawX - downRawX
                    val deltaY = event.rawY - downRawY
                    if (!dragging && (kotlin.math.abs(deltaX) > touchSlop || kotlin.math.abs(deltaY) > touchSlop)) {
                        dragging = true
                    }
                    if (dragging) {
                        params.x = clampX((downX + deltaX).toInt())
                        params.y = clampY((downY + deltaY).toInt(), root)
                        runCatching { windowManager.updateViewLayout(root, params) }
                    }
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (dragging) saveOverlayPosition(params.x, params.y)
                    dragging = false
                    true
                }

                else -> true
            }
        }
    }

    private fun clampX(value: Int): Int {
        val max = (resources.displayMetrics.widthPixels - (rootView?.width ?: dp(112))).coerceAtLeast(0)
        return value.coerceIn(0, max)
    }

    private fun clampY(value: Int, root: View): Int {
        val rootHeight = root.height.takeIf { it > 0 } ?: dp(160)
        val max = (resources.displayMetrics.heightPixels - rootHeight).coerceAtLeast(0)
        return value.coerceIn(0, max)
    }

    private fun loadOverlayPosition(): Pair<Int, Int>? {
        val preferences = getSharedPreferences(PREFERENCES_NAME, MODE_PRIVATE)
        if (!preferences.contains(POSITION_X) || !preferences.contains(POSITION_Y)) return null
        return preferences.getInt(POSITION_X, 0) to preferences.getInt(POSITION_Y, 0)
    }

    private fun saveOverlayPosition(x: Int, y: Int) {
        getSharedPreferences(PREFERENCES_NAME, MODE_PRIVATE)
            .edit()
            .putInt(POSITION_X, x)
            .putInt(POSITION_Y, y)
            .apply()
    }

    companion object {
        private const val TAG = "LandosolOverlay"
        private const val ACTION_SHOW = "com.landosol.toolbox.overlay.SHOW"
        private const val ACTION_HIDE = "com.landosol.toolbox.overlay.HIDE"
        private const val EXTRA_SESSION_ID = "session_id"
        private const val PREFERENCES_NAME = "automation_overlay"
        private const val POSITION_X = "position_x"
        private const val POSITION_Y = "position_y"
        fun show(context: Context, sessionId: AutomationSessionId) {
            if (!Settings.canDrawOverlays(context)) return
            context.startService(
                Intent(context, AutomationOverlayService::class.java)
                    .setAction(ACTION_SHOW)
                    .putExtra(EXTRA_SESSION_ID, sessionId.value),
            )
        }

        fun hide(context: Context) {
            context.startService(
                Intent(context, AutomationOverlayService::class.java).setAction(ACTION_HIDE),
            )
        }
    }
}

private class AutomationOverlayAnnotationView(context: Context) : View(context) {
    private var boxes: List<AutomationOverlayBox> = emptyList()
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val labelBackgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
        textSize = dp(7.5f)
        isFakeBoldText = true
    }

    fun setBoxes(next: List<AutomationOverlayBox>) {
        boxes = next
        invalidate()
    }

    override fun onDraw(canvas: android.graphics.Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return
        boxes.forEach { box ->
            val rect = RectF(
                box.left * width,
                box.top * height,
                (box.left + box.width) * width,
                (box.top + box.height) * height,
            )
            val color = when {
                !box.recognized -> 0xffffa94d.toInt()
                box.selected -> 0xff60a5fa.toInt()
                else -> 0xff34d399.toInt()
            }
            strokePaint.color = color
            strokePaint.strokeWidth = dp(2f)
            canvas.drawRoundRect(rect, dp(4f), dp(4f), strokePaint)

            val label = box.label.ifBlank { "未收录" }
            val labelLeft = rect.left.coerceIn(0f, width.toFloat())
            val availableWidth = (minOf(rect.right, width.toFloat()) - labelLeft).coerceAtLeast(0f)
            val horizontalPadding = dp(2.5f)
            val verticalPadding = dp(1.5f)
            val lineHeight = (labelPaint.fontMetrics.descent - labelPaint.fontMetrics.ascent) + dp(2f)
            val maxTextWidth = (availableWidth - horizontalPadding * 2f).coerceAtLeast(0f)
            val insideAvailableHeight = rect.height().coerceAtLeast(0f)
            val maxLinesByHeight = if (box.labelAbove) {
                MAX_LABEL_LINES
            } else {
                ((insideAvailableHeight - verticalPadding * 2f) / lineHeight)
                    .toInt()
                    .coerceAtLeast(0)
            }
            val labelLines = wrapAutomationOverlayLabel(
                label = label,
                maxLineWidth = maxTextWidth,
                maxLines = minOf(MAX_LABEL_LINES, maxLinesByHeight.coerceAtLeast(1)),
                measureText = labelPaint::measureText,
            )
            if (labelLines.isEmpty() || availableWidth <= 0f) {
                return@forEach
            }
            val measuredLabelWidth = labelLines.maxOf(labelPaint::measureText) + horizontalPadding * 2f
            val minimumLabelWidth = minOf(dp(20f), availableWidth)
            val labelWidth = measuredLabelWidth.coerceAtMost(availableWidth).coerceAtLeast(minimumLabelWidth)
            val requestedLabelHeight = labelLines.size * lineHeight + verticalPadding * 2f
            val labelHeight = if (box.labelAbove) {
                requestedLabelHeight.coerceAtMost(height.toFloat())
            } else {
                requestedLabelHeight.coerceAtMost(insideAvailableHeight)
            }
            if (labelHeight <= 0f) return@forEach
            val labelTop = if (box.labelAbove) {
                // Prefer a label immediately above the icon so the diagnostic text never covers
                // pixels that the next recognition frame will compare. If an icon sits too close
                // to the top edge, fall back below the icon rather than overlapping its ROI.
                if (rect.top >= labelHeight) {
                    rect.top - labelHeight
                } else {
                    rect.bottom.coerceAtMost((height - labelHeight).coerceAtLeast(0f))
                }
            } else {
                rect.top.coerceIn(0f, height.toFloat())
            }
            val labelRight = labelLeft + labelWidth
            val labelBottom = labelTop + labelHeight
            labelBackgroundPaint.color = Color.argb(210, 15, 23, 42)
            canvas.drawRect(labelLeft, labelTop, labelRight, labelBottom, labelBackgroundPaint)
            labelPaint.color = Color.WHITE
            canvas.save()
            canvas.clipRect(labelLeft, labelTop, labelRight, labelBottom)
            val firstBaseline = labelTop + verticalPadding - labelPaint.fontMetrics.ascent
            labelLines.forEachIndexed { index, line ->
                canvas.drawText(
                    line,
                    labelLeft + horizontalPadding,
                    firstBaseline + index * lineHeight,
                    labelPaint,
                )
            }
            canvas.restore()
        }
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    private companion object {
        const val MAX_LABEL_LINES = 4
    }
}

class AndroidAutomationOverlayHost(context: Context) : AutomationOverlayHost {
    private val appContext = context.applicationContext

    override fun show(sessionId: AutomationSessionId) {
        AutomationOverlayService.show(appContext, sessionId)
    }

    override fun hide(sessionId: AutomationSessionId) {
        AutomationOverlayService.hide(appContext)
    }
}
