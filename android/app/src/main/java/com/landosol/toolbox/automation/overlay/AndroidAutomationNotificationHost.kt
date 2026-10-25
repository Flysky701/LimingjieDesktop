package com.landosol.toolbox.automation.overlay

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.landosol.toolbox.LandosolToolboxApplication
import com.landosol.toolbox.MainActivity
import com.landosol.toolbox.automation.AutomationSessionId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.UUID

/** Uses the existing capture/reroll process lifetime; never creates a window or a new FGS. */
class AndroidAutomationNotificationHost(context: Context) : AutomationOverlayHost {
    private val app = context.applicationContext as LandosolToolboxApplication
    private val manager = app.getSystemService(NotificationManager::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observation: Job? = null
    private var active: AutomationSessionId? = null
    private var lastContent: AutomationNotificationContent? = null

    @Synchronized
    override fun show(sessionId: AutomationSessionId) {
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "自动化任务状态与控制", NotificationManager.IMPORTANCE_LOW))
        check(isAvailable(app)) { "请开启自动化任务通知，以便暂停或停止任务" }
        val presentation = app.automationOverlayCoordinator.state.value
        check(presentation?.sessionId == sessionId) { "任务已结束" }
        active = sessionId
        lastContent = null
        render(requireNotNull(presentation).notificationContent())
        observation?.cancel()
        observation = scope.launch {
            app.automationOverlayCoordinator.state
                .map { it?.notificationContent() }
                .distinctUntilChanged()
                .conflate()
                .collect { content ->
                    try {
                        if (content?.sessionId == sessionId) render(content)
                    } catch (failure: Exception) {
                        Log.e("AutomationNotification", "任务通知不可用，停止对应会话", failure)
                        app.automationOverlayCoordinator.stop(sessionId)
                    }
                    // Coalesce rapid recognition frames; do not repeatedly alert or queue them.
                    delay(1_000)
                }
        }
    }

    @Synchronized
    override fun hide(sessionId: AutomationSessionId) {
        if (active == sessionId) {
            active = null
            observation?.cancel()
            observation = null
            lastContent = null
        }
        manager.cancel(tag(sessionId), ID)
    }

    @Synchronized
    private fun render(content: AutomationNotificationContent) {
        if (content.sessionId != active || content == lastContent) return
        val open = PendingIntent.getActivity(app, 0,
            Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        fun command(action: String) = PendingIntent.getBroadcast(app, 0,
            Intent(app, AutomationNotificationActionReceiver::class.java)
                .setAction(action)
                .setData(Uri.parse("landosol://automation/$PROCESS_TOKEN/${content.sessionId.value}/$action"))
                .putExtra(SESSION_ID, content.sessionId.value).putExtra(TOKEN, PROCESS_TOKEN),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(app, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(content.title).setContentText(content.summary)
            .setStyle(NotificationCompat.BigTextStyle().bigText(content.expanded))
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true).setSilent(true).setShowWhen(false)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .addAction(0, if (content.paused) "继续" else "暂停", command(if (content.paused) RESUME else PAUSE))
            .addAction(0, "停止", command(STOP))
            .build()
        manager.notify(tag(content.sessionId), ID, notification)
        lastContent = content
    }

    companion object {
        internal const val CHANNEL = "automation-task-controls"
        internal const val ID = 8100
        internal const val SESSION_ID = "automation_session_id"
        internal const val TOKEN = "automation_process_token"
        internal val PROCESS_TOKEN = UUID.randomUUID().toString()
        internal const val PAUSE = "com.landosol.toolbox.automation.PAUSE"
        internal const val RESUME = "com.landosol.toolbox.automation.RESUME"
        internal const val STOP = "com.landosol.toolbox.automation.STOP"
        internal fun tag(id: AutomationSessionId, token: String = PROCESS_TOKEN) = "automation-$token-${id.value}"

        fun isAvailable(context: Context): Boolean =
            NotificationManagerCompat.from(context).areNotificationsEnabled() &&
                context.getSystemService(NotificationManager::class.java).getNotificationChannel(CHANNEL)?.importance !=
                NotificationManager.IMPORTANCE_NONE

        fun settingsIntent(context: Context): Intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
    }
}

/** Explicit, non-exported receiver. Old notification actions can never control a new session. */
class AutomationNotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val rawId = intent.getLongExtra(AndroidAutomationNotificationHost.SESSION_ID, -1L)
        if (rawId <= 0L) return
        val id = AutomationSessionId(rawId)
        val coordinator = (context.applicationContext as LandosolToolboxApplication).automationOverlayCoordinator
        val token = intent.getStringExtra(AndroidAutomationNotificationHost.TOKEN)
        if (!automationNotificationActionMatches(rawId, token, coordinator.state.value?.sessionId,
                AndroidAutomationNotificationHost.PROCESS_TOKEN)) {
            if (token != null) context.getSystemService(NotificationManager::class.java)
                .cancel(AndroidAutomationNotificationHost.tag(id, token), AndroidAutomationNotificationHost.ID)
            return
        }
        val command = intent.action
        if (command !in setOf(AndroidAutomationNotificationHost.PAUSE, AndroidAutomationNotificationHost.RESUME,
                AndroidAutomationNotificationHost.STOP)) return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).launch {
            try {
                when (command) {
                    AndroidAutomationNotificationHost.PAUSE -> coordinator.setPaused(id, true)
                    AndroidAutomationNotificationHost.RESUME -> coordinator.setPaused(id, false)
                    AndroidAutomationNotificationHost.STOP -> coordinator.stop(id)
                }
            } catch (failure: Exception) {
                Log.e("AutomationNotification", "通知控制动作失败", failure)
            } finally {
                pending.finish()
            }
        }
    }
}
