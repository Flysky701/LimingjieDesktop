package com.landosol.toolbox.desktop.device

import com.landosol.toolbox.desktop.platform.UserResourceLeases
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.withContext
import java.nio.file.Path

@OptIn(ExperimentalCoroutinesApi::class)
internal fun TestScope.virtualAdbDeviceService(
    adb: FakeAdb,
    dataDirectory: Path,
    touchFactory: TouchConnectionFactory,
    leases: UserResourceLeases,
    onEvent: (String) -> Unit = {},
    onLost: (String) -> Unit = {},
): AdbDeviceService = AdbDeviceService(
    adb, dataDirectory, emptySet(), onEvent, onLost, touchFactory, leases,
    expectedBootId = null,
    verifiedPackageCache = dataDirectory.resolve("cache/verified-packages"),
    backgroundDispatcher = StandardTestDispatcher(testScheduler),
    // The service reserves zero for unset timestamps.
    clockNanos = { 1_000_000_000_000L + testScheduler.currentTime * 1_000_000L },
)

internal suspend fun <T> AdbDeviceService.useWithVirtualTime(block: suspend (AdbDeviceService) -> T): T = use { device ->
    try {
        block(device)
    } finally {
        // Let the scheduler run cancellation before the blocking close joins service jobs.
        withContext(NonCancellable) { device.disconnect() }
    }
}
