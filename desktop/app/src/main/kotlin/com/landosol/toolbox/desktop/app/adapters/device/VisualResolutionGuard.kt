package com.landosol.toolbox.desktop.app.adapters.device

import com.landosol.toolbox.desktop.device.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class VisualResolutionStatus(val width: Int? = null, val height: Int? = null, val message: String) {
    val supported: Boolean get() = width == 1920 && height == 1080
    val dimensionsLabel: String get() = if (width == null || height == null) "画面尺寸待检查" else "$width×$height"
}

/** Real screenshot dimensions are authoritative; Android's wm size may use portrait orientation. */
class VisualResolutionGuard(
    private val device: DeviceService,
    private val onInvalidated: (String) -> Unit = {},
) {
    private val lock = Any()
    private var generation = 0L
    private var armed = false
    private var clientTransition = false
    private var landscapeDeadline = Long.MAX_VALUE
    private val mutableStatus = MutableStateFlow(VisualResolutionStatus(message = UNCHECKED))
    val status = mutableStatus.asStateFlow()

    /** Called before any task startup changes or business/network work. Always obtains a new frame. */
    suspend fun checkForStart() {
        val ticket = synchronized(lock) { armed = false; clientTransition = false; ++generation }
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
                observe(frame.width, frame.height)
            }
            return status.value
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            disarm(clearStatus = true)
            throw failure
        }
    }

    fun observe(width: Int, height: Int, requireLandscape: Boolean = false) {
        val result = statusFor(width, height)
        val invalidated = synchronized(lock) {
            mutableStatus.value = result
            val controlledPortrait = clientTransition && width == 1080 && height == 1920 &&
                System.nanoTime() <= landscapeDeadline && !requireLandscape
            if (result.supported && landscapeDeadline != Long.MAX_VALUE) clientTransition = false
            if (armed && !result.supported && !controlledPortrait) {
                armed = false
                generation++
                true
            } else false
        }
        // No joining/capture shutdown here: this can run on the capture worker itself.
        if (invalidated) onInvalidated(result.message)
    }

    fun disarm(clearStatus: Boolean = false) = synchronized(lock) {
        armed = false
        clientTransition = false
        generation++
        if (clearStatus) mutableStatus.value = VisualResolutionStatus(message = UNCHECKED)
    }

    /** A fresh frame closes the gap between periodic capture updates and the next physical action. */
    suspend fun actionRejection(): String? = verifyAction(requireLandscape = true)

    private suspend fun verifyAction(requireLandscape: Boolean): String? {
        val ticket = synchronized(lock) {
            if (!armed) return rejection()
            generation
        }
        try {
            val frame = device.capture()
            observe(frame.width, frame.height, requireLandscape)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            val reason = "无法核实截图尺寸，已禁止自动操作：${failure.message ?: "截图失败"}。请到设备页重新检查。"
            val notify = synchronized(lock) {
                val wasArmed = armed
                armed = false
                generation++
                mutableStatus.value = VisualResolutionStatus(message = reason)
                wasArmed
            }
            if (notify) onInvalidated(reason)
            return reason
        }
        return synchronized(lock) { if (armed && generation == ticket) null else rejection() }
    }

    /** Home/force-stop can rotate the launcher. Preserve only the checked no-coordinate reset. */
    suspend fun beforeClientReset(): String? {
        if (synchronized(lock) { armed && clientTransition }) return verifyAction(requireLandscape = false)
        actionRejection()?.let { return it }
        synchronized(lock) {
            if (!armed) return rejection()
            clientTransition = true
            landscapeDeadline = Long.MAX_VALUE // Server-side reroll may finish before relaunch.
        }
        return null
    }

    suspend fun beforeClientLaunch(): String? {
        if (synchronized(lock) { armed && clientTransition }) return verifyAction(requireLandscape = false)
        return actionRejection()
    }

    fun clientLaunchRequested() = synchronized(lock) {
        if (armed) {
            clientTransition = true
            landscapeDeadline = System.nanoTime() + 30_000_000_000L
        }
    }

    fun deferTransitionFrame(width: Int, height: Int): Boolean = synchronized(lock) {
        armed && clientTransition && width == 1080 && height == 1920 && System.nanoTime() <= landscapeDeadline
    }

    private fun rejection(): String = if (!status.value.supported) status.value.message
        else "自动操作未获本次启动检查放行，请重新启动任务。"

    private fun statusFor(width: Int, height: Int) = VisualResolutionStatus(width, height,
        if (width == 1920 && height == 1080) "实际截图：1920 × 1080，符合自动执行要求。"
        else "当前实际截图为 $width × $height；自动执行要求 1920 × 1080 横屏。请在模拟器中调整后，到设备页重新检查并启动任务。")

    companion object { const val UNCHECKED = "自动执行要求 1920 × 1080 横屏，请在设备页检查实际截图尺寸。" }
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
