package com.landosol.toolbox.desktop.shared

import com.landosol.toolbox.automation.overlay.AutomationOverlayPresentation
import com.landosol.toolbox.automation.overlay.notificationContent

data class DesktopNotification(val title: String, val summary: String, val detail: String)
fun AutomationOverlayPresentation.desktopNotification(): DesktopNotification = notificationContent().let {
    DesktopNotification(it.title, it.summary, it.expanded)
}
