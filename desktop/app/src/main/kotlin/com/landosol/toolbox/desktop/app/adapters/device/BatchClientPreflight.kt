package com.landosol.toolbox.desktop.app.adapters.device

import com.landosol.toolbox.desktop.device.DeviceService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/** Only the external batch-start boundary calls this, after device/resolution checks pass. */
internal suspend fun prepareBatchClient(device: DeviceService, packageName: String): Boolean {
    try {
        if (!device.isPackageRunning(packageName)) return false
        check(device.forceStop(packageName)) { "未能确认 PCR 进程已退出" }
        val exited = withTimeoutOrNull(3000) {
            while (device.isPackageRunning(packageName)) delay(100)
            true
        } == true
        check(exited) { "PCR 或其子进程仍在运行" }
        return true
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        throw IllegalStateException("批量任务未启动：PCR 启动前清理（$packageName）失败：${failure.message ?: "未知错误"}", failure)
    }
}
