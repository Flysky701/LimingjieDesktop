package com.landosol.toolbox.desktop.app

import com.landosol.toolbox.automation.AutomationSessionId
import com.landosol.toolbox.automation.overlay.AutomationOverlayHost
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Window and tray observe the same session lifetime; commands still belong to the coordinator. */
class DesktopTaskHost : AutomationOverlayHost {
    private val current = MutableStateFlow<AutomationSessionId?>(null)
    val active = current.asStateFlow()
    override fun show(sessionId: AutomationSessionId) { current.value = sessionId }
    override fun hide(sessionId: AutomationSessionId) { current.compareAndSet(sessionId, null) }
}
