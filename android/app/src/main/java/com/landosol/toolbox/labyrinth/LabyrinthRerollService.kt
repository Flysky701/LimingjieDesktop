package com.landosol.toolbox.labyrinth

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.landosol.toolbox.LandosolToolboxApplication
import com.landosol.toolbox.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield

/** Network-only reroll service. Never starts a capture session or replays an interrupted enter. */
class LabyrinthRerollService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val controller get() = (application as LandosolToolboxApplication).labyrinthController
    private var observing = false
    private var finished = false
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "刷开局状态", NotificationManager.IMPORTANCE_LOW),
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) {
            controller.stop()
            if (!observing) finishNotification("已请求停止刷取")
            return START_NOT_STICKY
        }
        if (intent?.action != START) { stopSelf(); return START_NOT_STICKY }
        try {
            val notice = notification("正在启动刷取", ongoing = true)
            if (Build.VERSION.SDK_INT >= 29) startForeground(ID, notice, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            else startForeground(ID, notice)
        } catch (failure: Exception) {
            controller.stop()
            controller.reportMessage("后台服务启动失败：${failure.message}")
            finished = true
            stopSelf()
            return START_NOT_STICKY
        }
        val accepted = controller.startFromService()
        if (observing) return START_NOT_STICKY
        if (!accepted && !controller.hasActiveReroll) {
            finishNotification("没有待启动的刷取任务")
            return START_NOT_STICKY
        }
        finished = false
        observing = true
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = getSystemService(PowerManager::class.java).newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK, "landosol:reroll",
        ).also { it.acquire(MAX_RUN_MILLIS + 10_000) }
        scope.launch {
            yield() // Allow the eagerly shared UI state to publish the service's start transition.
            val deadline = android.os.SystemClock.elapsedRealtime() + MAX_RUN_MILLIS
            while (true) {
                val state = controller.uiState.value
                if (!controller.hasActiveReroll) {
                    finishNotification(if (state.isWorking) "已请求停止，保留服务端开局并核对检查点" else labyrinthRerollStatusText(state))
                    break
                }
                if (android.os.SystemClock.elapsedRealtime() >= deadline) {
                    stopForLimit()
                    break
                }
                getSystemService(NotificationManager::class.java).notify(ID,
                    notification(labyrinthRerollStatusText(state), ongoing = true))
                delay(2_000)
            }
        }
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) = stopForLimit()

    private fun stopForLimit() {
        val message = "后台运行达到系统/安全时限，已停止；返回应用读取现有开局后继续"
        controller.stopWithReason(message)
        finishNotification(message)
    }

    private fun finishNotification(text: String) {
        finished = true
        observing = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        getSystemService(NotificationManager::class.java).notify(ID, notification(text, ongoing = false))
        stopSelf()
    }

    private fun notification(text: String, ongoing: Boolean): Notification {
        val open = PendingIntent.getActivity(this, 7101,
            Intent(this, MainActivity::class.java).putExtra("openLabyrinth", true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 7102, Intent(this, javaClass).setAction(STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(if (ongoing) "黎明界 · 刷开局运行中" else "黎明界 · 刷取已结束或待处理")
            .setContentText(text).setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open).setOnlyAlertOnce(true).setOngoing(ongoing)
            .setAutoCancel(!ongoing).setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .apply { if (ongoing) addAction(0, "停止", stop) }
            .build()
    }

    override fun onDestroy() {
        if (!finished && observing) controller.stop()
        scope.cancel()
        wakeLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL = "labyrinth-reroll"
        private const val ID = 7100
        private const val START = "com.landosol.toolbox.REROLL_START"
        private const val STOP = "com.landosol.toolbox.REROLL_STOP"
        private const val MAX_RUN_MILLIS = 5 * 60 * 60_000L + 55 * 60_000L
        fun start(context: Context) {
            check(androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled()) {
                "请开启应用通知权限"
            }
            check(context.getSystemService(NotificationManager::class.java).getNotificationChannel(CHANNEL)?.importance !=
                NotificationManager.IMPORTANCE_NONE) { "请开启刷开局状态通知频道" }
            ContextCompat.startForegroundService(context, Intent(context, LabyrinthRerollService::class.java).setAction(START))
        }
    }
}
