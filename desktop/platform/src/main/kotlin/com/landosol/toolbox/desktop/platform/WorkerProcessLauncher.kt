package com.landosol.toolbox.desktop.platform

import java.nio.file.Files
import java.util.concurrent.TimeUnit

/** A handle for the exact child Process, never a saved/reused PID or a process-name match. */
class OwnedWorkerProcess internal constructor(private val process: Process, val profile: InstanceProfile,
                                             private val containment: AutoCloseable) : AutoCloseable {
    init { process.onExit().thenRun { containment.close() } }
    val pid: Long get() = process.pid()
    val isAlive: Boolean get() = process.isAlive
    fun onExit(callback: (Int) -> Unit) { process.onExit().thenAccept { callback(it.exitValue()) } }

    /** IPC owner supplies graceful shutdown; only this child is terminated after the deadline. */
    fun stop(graceMillis: Long = 5000, requestShutdown: () -> Unit = {}) {
        require(graceMillis in 0..60_000)
        try { requestShutdown() } finally {
            try {
            if (!process.waitFor(graceMillis, TimeUnit.MILLISECONDS)) {
                process.destroy()
                if (!process.waitFor(1000, TimeUnit.MILLISECONDS)) {
                    process.destroyForcibly()
                    check(process.waitFor(3000, TimeUnit.MILLISECONDS)) { "工作进程尚未退出，不能复用实例" }
                }
            }
            } finally { containment.close() }
        }
    }
    override fun close() = stop(graceMillis = 0)
}

object WorkerProcessLauncher {
    /** command contains executable plus worker/IPC arguments; profile identity is appended here. */
    fun start(command: List<String>, profile: InstanceProfile): OwnedWorkerProcess {
        require(command.isNotEmpty() && command.none { '\u0000' in it })
        require(command.none { it == "--data-dir" || it == "--profile-id" }) { "实例参数由启动器统一提供" }
        val logs = profile.dataDirectory.resolve("logs")
        Files.createDirectories(logs)
        val process = ProcessBuilder(command + listOf("--profile-id", profile.id.toString(), "--data-dir", profile.dataDirectory.toString()))
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.appendTo(logs.resolve("worker-launch.log").toFile()))
            .start()
        try {
            return OwnedWorkerProcess(process, profile, WorkerProcessContainment.own(process.pid()))
        } catch (failure: Throwable) {
            process.destroyForcibly().waitFor(3000, TimeUnit.MILLISECONDS)
            throw failure
        }
    }
}
