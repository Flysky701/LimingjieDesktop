package com.landosol.toolbox.permissions

data class PermissionCenterState(
    val accessibilityEnabled: Boolean,
    val accessibilityConnected: Boolean,
    val overlayEnabled: Boolean,
    val notificationsEnabled: Boolean,
    val captureSessionActive: Boolean = false,
) {
    val canStartVisualAutomation: Boolean
        get() = accessibilityEnabled && accessibilityConnected &&
            notificationsEnabled && captureSessionActive
}

