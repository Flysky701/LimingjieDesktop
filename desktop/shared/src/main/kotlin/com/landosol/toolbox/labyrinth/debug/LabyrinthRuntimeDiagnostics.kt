package com.landosol.toolbox.labyrinth.debug
import android.content.Context
import com.landosol.toolbox.AppVersion
import java.lang.management.ManagementFactory
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.json.JSONObject

class LabyrinthRuntimeDiagnostics(context: Context) : AutoCloseable {
    @Volatile var environmentDetails: () -> JSONObject = { JSONObject() }
    private val samples = ArrayDeque<String>()
    private var executor: java.util.concurrent.ScheduledExecutorService? = null
    @Synchronized fun start() {
        if (executor != null) return
        executor = Executors.newSingleThreadScheduledExecutor { Thread(it, "desktop-runtime-diagnostics").apply { isDaemon = true } }.also {
            it.scheduleWithFixedDelay({ sample() }, 0, 5, TimeUnit.SECONDS)
        }
    }
    @Synchronized private fun sample() {
        val runtime = Runtime.getRuntime()
        samples.addLast(JSONObject().put("timestamp", System.currentTimeMillis()).put("pid", ProcessHandle.current().pid())
            .put("javaHeapUsedBytes", runtime.totalMemory() - runtime.freeMemory()).put("javaHeapMaxBytes", runtime.maxMemory())
            .put("threadCount", ManagementFactory.getThreadMXBean().threadCount)
            .put("processCpuNanos", ProcessHandle.current().info().totalCpuDuration().map { it.toNanos() }.orElse(0L))
            .put("desktop", runCatching { environmentDetails() }.getOrElse { JSONObject() }).toString())
        while (samples.size > 720) samples.removeFirst()
    }
    fun environmentJson(): ByteArray = JSONObject().put("appVersion", AppVersion.label).put("platform", "desktop")
        .put("os", System.getProperty("os.name")).put("arch", System.getProperty("os.arch")).put("javaVersion", System.getProperty("java.version"))
        .put("desktop", runCatching { environmentDetails() }.getOrElse { JSONObject() }).toString().toByteArray(Charsets.UTF_8)
    @Synchronized fun historyNdjson(): ByteArray = samples.joinToString("\n", postfix = "\n").toByteArray(Charsets.UTF_8)
    @Synchronized override fun close() { executor?.shutdownNow(); executor = null }
}
