package com.landosol.toolbox.desktop.app.adapters.device
import com.landosol.toolbox.automation.*
import com.landosol.toolbox.desktop.device.DeviceService
import com.landosol.toolbox.desktop.device.TouchResult

class DeviceActionBackend(private val device: DeviceService, private val expectedPackage: () -> String) : AutomationActionBackend {
    override suspend fun execute(action: AutomationAction): AutomationBackendResult {
        val target = expectedPackage()
        val result = when (action) {
            is AutomationAction.Tap -> device.tap(action.point.x, action.point.y, target)
            is AutomationAction.Swipe -> device.swipe(action.start.x, action.start.y, action.end.x, action.end.y, action.durationMillis, action.holdMillis, target)
            AutomationAction.Back -> device.back(target)
        }
        return when (result) { TouchResult.Completed -> AutomationBackendResult.Completed; is TouchResult.Rejected -> AutomationBackendResult.Rejected(result.reason) }
    }
}
