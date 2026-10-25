package com.landosol.toolbox.desktop.platform

import com.sun.jna.platform.win32.Kernel32
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

enum class PowerMode { IDLE, NETWORK, VISUAL }

/** Windows execution state is thread-local, so every set and clear uses this one dedicated thread. */
class PowerSaveController(
    private val setExecutionState: (Int) -> Int = { flags ->
        if (WindowsDpapi.available) Kernel32.INSTANCE.SetThreadExecutionState(flags) else flags
    },
) : AutoCloseable {
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "desktop-power-state").apply { isDaemon = true } }
    private val closed = AtomicBoolean(false)
    @Volatile private var mode = PowerMode.IDLE

    @Synchronized fun setMode(value: PowerMode) {
        check(!closed.get()) { "PowerSaveController 已关闭" }
        if (value == mode) return
        worker.submit {
            val flags = when (value) {
                PowerMode.IDLE -> ES_CONTINUOUS
                PowerMode.NETWORK -> ES_CONTINUOUS or ES_SYSTEM_REQUIRED
                PowerMode.VISUAL -> ES_CONTINUOUS or ES_SYSTEM_REQUIRED or ES_DISPLAY_REQUIRED
            }
            try {
                check(setExecutionState(flags) != 0) { "Windows 拒绝设置防睡眠状态" }
                mode = value
            } catch (failure: Throwable) {
                setExecutionState(ES_CONTINUOUS)
                mode = PowerMode.IDLE
                throw failure
            }
        }.get()
    }

    @Synchronized override fun close() {
        if (!closed.compareAndSet(false, true)) return
        try { worker.submit { setExecutionState(ES_CONTINUOUS); mode = PowerMode.IDLE }.get() }
        finally { worker.shutdown(); worker.awaitTermination(2, TimeUnit.SECONDS) }
    }

    companion object {
        const val ES_CONTINUOUS: Int = -0x80000000
        const val ES_SYSTEM_REQUIRED: Int = 0x00000001
        const val ES_DISPLAY_REQUIRED: Int = 0x00000002
        /** Resolve the native symbol without changing execution state (for --self-test). */
        fun verifyNativeSymbol() {
            if (WindowsDpapi.available) com.sun.jna.NativeLibrary.getInstance("kernel32").getFunction("SetThreadExecutionState")
        }
    }
}
