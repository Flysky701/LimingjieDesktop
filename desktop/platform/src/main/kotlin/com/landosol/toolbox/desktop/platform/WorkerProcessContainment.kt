package com.landosol.toolbox.desktop.platform

import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.WString
import com.sun.jna.platform.win32.BaseTSD
import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.WinNT
import com.sun.jna.win32.StdCallLibrary
import com.sun.jna.win32.W32APIOptions
import java.util.concurrent.atomic.AtomicBoolean

/** Keep the returned non-inheritable handle in the UI, before allowing a worker to connect. */
object WorkerProcessContainment {
    fun own(pid: Long): AutoCloseable {
        check(System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) { "工作进程退出约束需要 Windows" }
        val child = ProcessHandle.of(pid).orElseThrow { IllegalStateException("工作进程已退出") }
        require(child.parent().orElse(null)?.pid() == ProcessHandle.current().pid()) { "只能约束本进程直接启动的 worker" }
        val api = Native.load("kernel32", JobApi::class.java, W32APIOptions.UNICODE_OPTIONS)
        val job = api.CreateJobObjectW(null, null) ?: error("无法创建工作进程约束：${Native.getLastError()}")
        try {
            val limits = ExtendedLimits().apply {
                // KILL_ON_JOB_CLOSE + SILENT_BREAKAWAY_OK: only worker, not shared ADB children.
                basic.limitFlags = 0x2000 or 0x1000
                write()
            }
            check(api.SetInformationJobObject(job, 9, limits.pointer, limits.size())) { "无法设置工作进程约束：${Native.getLastError()}" }
            val process = Kernel32.INSTANCE.OpenProcess(0x0100 or 0x0001, false, pid.toInt())
                ?: error("无法访问工作进程：${Native.getLastError()}")
            try {
                check(api.AssignProcessToJobObject(job, process)) { "无法绑定工作进程退出约束：${Native.getLastError()}" }
            } finally { Kernel32.INSTANCE.CloseHandle(process) }
            val closed = AtomicBoolean()
            return AutoCloseable { if (closed.compareAndSet(false, true)) Kernel32.INSTANCE.CloseHandle(job) }
        } catch (failure: Throwable) { Kernel32.INSTANCE.CloseHandle(job); throw failure }
    }

    interface JobApi : StdCallLibrary {
        fun CreateJobObjectW(attributes: Pointer?, name: WString?): WinNT.HANDLE?
        fun SetInformationJobObject(job: WinNT.HANDLE, infoClass: Int, information: Pointer, length: Int): Boolean
        fun AssignProcessToJobObject(job: WinNT.HANDLE, process: WinNT.HANDLE): Boolean
    }
    @Structure.FieldOrder("processTime", "jobTime", "limitFlags", "minimumWorkingSet", "maximumWorkingSet", "activeProcesses", "affinity", "priority", "scheduling")
    class BasicLimits : Structure() {
        @JvmField var processTime: Long = 0
        @JvmField var jobTime: Long = 0
        @JvmField var limitFlags: Int = 0
        @JvmField var minimumWorkingSet = BaseTSD.SIZE_T()
        @JvmField var maximumWorkingSet = BaseTSD.SIZE_T()
        @JvmField var activeProcesses: Int = 0
        @JvmField var affinity = BaseTSD.ULONG_PTR()
        @JvmField var priority: Int = 0
        @JvmField var scheduling: Int = 0
    }
    @Structure.FieldOrder("basic", "io", "processMemory", "jobMemory", "peakProcessMemory", "peakJobMemory")
    class ExtendedLimits : Structure() {
        @JvmField var basic = BasicLimits()
        @JvmField var io = WinNT.IO_COUNTERS()
        @JvmField var processMemory = BaseTSD.SIZE_T()
        @JvmField var jobMemory = BaseTSD.SIZE_T()
        @JvmField var peakProcessMemory = BaseTSD.SIZE_T()
        @JvmField var peakJobMemory = BaseTSD.SIZE_T()
    }
}
