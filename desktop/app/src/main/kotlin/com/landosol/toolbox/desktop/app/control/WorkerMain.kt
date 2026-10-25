package com.landosol.toolbox.desktop.app.control

import com.landosol.toolbox.desktop.app.DesktopAppGraph
import com.landosol.toolbox.desktop.app.adapters.storage.DatabaseRecovery
import com.landosol.toolbox.desktop.platform.DataDirectories
import com.landosol.toolbox.desktop.platform.InstanceLock
import com.landosol.toolbox.desktop.shared.DesktopContext
import com.landosol.toolbox.desktop.shared.DesktopLog
import kotlinx.coroutines.*

/** Headless entrypoint. No Compose Window, tray, or native dialog can be opened by this process. */
internal suspend fun runWorkerMain(args: Array<String>): Int {
    var lock: InstanceLock? = null
    var domain: GraphControlService? = null
    return try {
        fun argument(key: String): String {
            val index = args.indexOf(key)
            require(index >= 0 && index + 1 < args.size && !args[index + 1].startsWith("--")) { "缺少实例启动参数" }
            return args[index + 1]
        }
        val id = java.util.UUID.fromString(argument("--instance-id")).toString()
        val runId = java.util.UUID.fromString(argument("--worker-run-id")).toString()
        val label = argument("--instance-name")
        val token = requireNotNull(System.getenv(WorkerProtocol.TOKEN_ENV)) { "没有总控认证信息" }
        val port = requireNotNull(System.getenv(WorkerProtocol.PORT_ENV)?.toIntOrNull()) { "没有总控连接地址" }
        require(port in 1..65535 && token.length >= 32) { "总控连接参数无效" }
        val directory = DataDirectories.resolve(args)
        DataDirectories.ensureWritable(directory)
        lock = InstanceLock.acquire(directory)
        DataDirectories.cleanTemporaryFiles(directory)
        DesktopLog.initialize(directory.resolve("logs"))
        val notices = mutableListOf<String>()
        val database = DatabaseRecovery.open(DesktopContext(directory.toFile())) { notices += it }
        val graph = DesktopAppGraph(directory, database, startupUpdates = "--offline-startup" !in args)
        notices.forEach(graph::addNotice)
        val service = GraphControlService(graph, id, label).also { domain = it }
        Thread.setDefaultUncaughtExceptionHandler { _, failure ->
            DesktopLog.write('E', "Worker", "实例进程发生异常", failure)
            service.revokeActions("实例发生异常，任务已停止")
        }
        runWorkerTransport(port, token, id, runId, service)
        0
    } catch (failure: Exception) {
        // No command payloads or authentication tokens are included in launcher logs.
        System.err.println("实例启动或运行失败：${DesktopLog.redact(failure.message ?: failure.javaClass.simpleName)}")
        1
    } finally {
        withContext(NonCancellable) { withTimeoutOrNull(8_000) { domain?.shutdown() } }
        lock?.close()
    }
}
