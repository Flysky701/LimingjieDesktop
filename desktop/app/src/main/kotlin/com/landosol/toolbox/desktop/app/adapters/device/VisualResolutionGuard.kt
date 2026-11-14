package com.landosol.toolbox.desktop.app.adapters.device

import com.landosol.toolbox.desktop.device.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class VisualResolutionStatus(val width: Int? = null, val height: Int? = null, val message: String) {
    val supported: Boolean get() = (width == 1920 && height == 1080) || (width == 1280 && height == 720)
    val dimensionsLabel: String get() = if (width == null || height == null) "画面尺寸待检查" else "$width×$height"
}

/** Checks real screenshot dimensions at task startup; actions reuse that startup permission. */
class VisualResolutionGuard(private val device: DeviceService) {
    private val lock = Any()
    private var generation = 0L
    private var armed = false
    private var clientTransition = false
    private var transitionPortrait = false
    private var landscapeDeadline = Long.MAX_VALUE
    private val mutableStatus = MutableStateFlow(VisualResolutionStatus(message = UNCHECKED))
    val status = mutableStatus.asStateFlow()

    /** Called before any task startup changes or business/network work. Always obtains a new frame. */
    suspend fun checkForStart() {
        val ticket = synchronized(lock) { armed = false; clientTransition = false; transitionPortrait = false; ++generation }
        val frame = device.capture()
        synchronized(lock) {
            check(ticket == generation) { "检查已取消，请重新启动任务" }
            val result = statusFor(frame.width, frame.height)
            mutableStatus.value = result
            armed = result.supported
            check(result.supported) { result.message }
        }
    }

    /** Device-page check does not grant action permission or resume an interrupted task. */
    suspend fun recheck(): VisualResolutionStatus {
        try {
            val ticket = synchronized(lock) { generation }
            val frame = device.capture()
            synchronized(lock) {
                check(ticket == generation) { "检查已取消，请重新检查" }
                mutableStatus.value = statusFor(frame.width, frame.height)
            }
            return status.value
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            disarm(clearStatus = true)
            throw failure
        }
    }

    fun disarm(clearStatus: Boolean = false) = synchronized(lock) {
        armed = false
        clientTransition = false
        transitionPortrait = false
        generation++
        if (clearStatus) mutableStatus.value = VisualResolutionStatus(message = UNCHECKED)
    }

    /** No device I/O here: pause/stop and foreground checks remain at their dispatch boundaries. */
    suspend fun actionRejection(): String? = synchronized(lock) {
        when {
            !armed -> rejection()
            transitionPortrait -> "客户端正在重启，请等待游戏恢复横屏。"
            else -> null
        }
    }

    /** Home/force-stop can rotate the launcher. Preserve only the checked no-coordinate reset. */
    suspend fun beforeClientReset(): String? = synchronized(lock) {
        if (!armed) return rejection()
        clientTransition = true
        landscapeDeadline = Long.MAX_VALUE // Server-side reroll may finish before relaunch.
        null
    }

    suspend fun beforeClientLaunch(): String? = synchronized(lock) {
        if (armed) null else rejection()
    }

    fun clientLaunchRequested() = synchronized(lock) {
        if (armed) {
            clientTransition = true
            landscapeDeadline = System.nanoTime() + 30_000_000_000L
        }
    }

    fun deferTransitionFrame(width: Int, height: Int): Boolean = synchronized(lock) {
        if (!armed || !clientTransition) return false
        val checked = status.value
        transitionPortrait = width == checked.height && height == checked.width
        if (width == checked.width && height == checked.height && landscapeDeadline != Long.MAX_VALUE) {
            clientTransition = false
        }
        transitionPortrait && System.nanoTime() <= landscapeDeadline
    }

    private fun rejection(): String = if (!status.value.supported) status.value.message
        else "自动操作未获本次启动检查放行，请重新启动任务。"

    private fun statusFor(width: Int, height: Int) = VisualResolutionStatus(width, height,
        if ((width == 1920 && height == 1080) || (width == 1280 && height == 720))
            "实际截图：$width × $height，符合自动执行要求。" + if (width == 1280) "720p 已开放测试。" else ""
        else "当前实际截图为 $width × $height；自动执行支持 1920 × 1080 或 1280 × 720 横屏。请在模拟器中调整后重新启动任务。")

    companion object { const val UNCHECKED = "自动执行支持 1920 × 1080 或 1280 × 720 横屏，任务启动时检查实际截图尺寸。" }
}

/** Shared boundary for every automated device action, including channel login and client reset. */
class ResolutionGuardedDevice(private val delegate: DeviceService, private val guard: VisualResolutionGuard) : DeviceService by delegate {
    override suspend fun tap(x: Float, y: Float, expectedPackage: String): TouchResult =
        guard.actionRejection()?.let(TouchResult::Rejected) ?: delegate.tap(x, y, expectedPackage)
    override suspend fun swipe(fromX: Float, fromY: Float, toX: Float, toY: Float, durationMillis: Long, holdMillis: Long, expectedPackage: String): TouchResult =
        guard.actionRejection()?.let(TouchResult::Rejected) ?: delegate.swipe(fromX, fromY, toX, toY, durationMillis, holdMillis, expectedPackage)
    override suspend fun back(expectedPackage: String): TouchResult =
        guard.actionRejection()?.let(TouchResult::Rejected) ?: delegate.back(expectedPackage)
    override suspend fun home(): Boolean = guard.beforeClientReset() == null && delegate.home()
    override suspend fun launch(packageName: String): Boolean {
        if (guard.beforeClientLaunch() != null) return false
        // Announce before dispatch so the capture worker can see the expected launcher rotation.
        guard.clientLaunchRequested()
        return delegate.launch(packageName)
    }
    override suspend fun forceStop(packageName: String): Boolean = guard.beforeClientReset() == null && delegate.forceStop(packageName)
}
