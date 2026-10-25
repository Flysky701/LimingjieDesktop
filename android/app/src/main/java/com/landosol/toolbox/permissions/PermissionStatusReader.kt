package com.landosol.toolbox.permissions

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.core.content.ContextCompat
import com.landosol.toolbox.automation.accessibility.LandosolAccessibilityService
import com.landosol.toolbox.automation.capture.CaptureStateRegistry
import com.landosol.toolbox.automation.overlay.AndroidAutomationNotificationHost

object PermissionStatusReader {
    fun read(context: Context): PermissionCenterState {
        val accessibilityEnabled = isAccessibilityEnabled(context)
        return PermissionCenterState(
            accessibilityEnabled = accessibilityEnabled,
            accessibilityConnected = accessibilityEnabled && LandosolAccessibilityService.isConnected(),
            overlayEnabled = Settings.canDrawOverlays(context),
            notificationsEnabled = AndroidAutomationNotificationHost.isAvailable(context) &&
                (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED),
            captureSessionActive = CaptureStateRegistry.isActive(),
        )
    }

    fun isAccessibilityEnabled(context: Context): Boolean {
        val manager = context.getSystemService(AccessibilityManager::class.java) ?: return false
        val expected = ComponentName(context, LandosolAccessibilityService::class.java)
        return manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { info ->
                val serviceInfo = info.resolveInfo?.serviceInfo ?: return@any false
                ComponentName(serviceInfo.packageName, serviceInfo.name) == expected
            }
    }
}
