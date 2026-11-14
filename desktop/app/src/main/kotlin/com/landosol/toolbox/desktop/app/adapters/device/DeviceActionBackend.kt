package com.landosol.toolbox.desktop.app.adapters.device
import com.landosol.toolbox.automation.*
import com.landosol.toolbox.desktop.device.DeviceService
import com.landosol.toolbox.desktop.device.TouchResult
import com.landosol.toolbox.desktop.device.DeviceDispatchPermit
import com.landosol.toolbox.desktop.device.DEVICE_DISPATCH_SUPPRESSED
import kotlinx.coroutines.withContext

class DeviceActionBackend(private val device: DeviceService, private val expectedPackage: () -> String) : AutomationActionBackend {
    override suspend fun executeGuarded(action: AutomationAction, permit: () -> Boolean): AutomationBackendResult {
        if (!permit()) return AutomationBackendResult.Rejected(AUTOMATION_PRE_DISPATCH_SUPPRESSED_REASON)
        return withContext(DeviceDispatchPermit(permit)) { execute(action) }
    }
    override suspend fun execute(action: AutomationAction): AutomationBackendResult {
        val target = expectedPackage()
        val result = when (action) {
            is AutomationAction.Tap -> device.tap(action.point.x, action.point.y, target)
            is AutomationAction.Swipe -> device.swipe(action.start.x, action.start.y, action.end.x, action.end.y, action.durationMillis, action.holdMillis, target)
            AutomationAction.Back -> device.back(target)
        }
        return when (result) {
            TouchResult.Completed -> AutomationBackendResult.Completed
            is TouchResult.Rejected -> AutomationBackendResult.Rejected(
                if (result.reason == DEVICE_DISPATCH_SUPPRESSED) AUTOMATION_PRE_DISPATCH_SUPPRESSED_REASON else result.reason,
            )
        }
    }
}
