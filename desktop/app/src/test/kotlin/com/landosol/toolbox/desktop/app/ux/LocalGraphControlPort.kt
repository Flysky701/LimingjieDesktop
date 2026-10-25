package com.landosol.toolbox.desktop.app.ux

import com.landosol.toolbox.desktop.app.DesktopAppGraph
import com.landosol.toolbox.desktop.app.control.*
import com.landosol.toolbox.desktop.device.*
import com.landosol.toolbox.desktop.platform.UserResourceLeases
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** Test-only in-process transport. Production UI still talks exclusively to worker processes. */
internal class LocalGraphControlPort private constructor(
    private val domain: GraphControlService,
    initial: JsonObject,
    private val verifiedForUi: Boolean,
) : ControlSessionPort {
    override val instanceId = "ui-test"
    override val workerRunId = "ui-test-run"
    private val updates = MutableStateFlow(initial)
    override val snapshots = updates.asStateFlow()
    private val connectionState = MutableStateFlow(WorkerConnectionStatus.CONNECTED)
    override val connection = connectionState.asStateFlow()
    private val failureState = MutableStateFlow<String?>(null)
    override val error = failureState.asStateFlow()
    val requests = CopyOnWriteArrayList<String>()
    private val publishing = Mutex()
    private val shutdownLock = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main + CoroutineExceptionHandler { _, failure ->
        failureState.value = failure.message ?: "测试控制端口失败"
        connectionState.value = WorkerConnectionStatus.LOST
    })
    private val poller = scope.launch {
        while (isActive) { publish(); delay(50) }
    }

    private suspend fun publish() = publishing.withLock {
        val snapshot = domain.snapshot(includePreview = false)
        updates.value = if (verifiedForUi) verifiedSnapshot(snapshot) else snapshot
    }

    override suspend fun request(op: String, args: JsonObject): JsonObject {
        check(connectionState.value == WorkerConnectionStatus.CONNECTED) { "测试控制端口已关闭" }
        requests += op
        return domain.execute(op, args).also { publish() }
    }

    override fun setSelected(selected: Boolean) = Unit

    override suspend fun shutdown() = shutdownLock.withLock {
        if (connectionState.value == WorkerConnectionStatus.CLOSED) return@withLock
        connectionState.value = WorkerConnectionStatus.CLOSED
        poller.cancelAndJoin()
        scope.cancel()
        domain.shutdown()
    }

    companion object {
        suspend fun create(graph: DesktopAppGraph, verifiedForUi: Boolean = false): LocalGraphControlPort {
            val domain = GraphControlService(graph, "ui-test", "离线示例",
                UserResourceLeases(graph.dataDirectory.resolve("test-leases")), graph.dataDirectory.resolve("test-admission"))
            try {
                val initial = domain.snapshot(false)
                return LocalGraphControlPort(domain, if (verifiedForUi) verifiedSnapshot(initial) else initial, verifiedForUi)
            } catch (failure: Exception) {
                withContext(NonCancellable) { domain.shutdown() }
                throw failure
            }
        }

        // Only overrides UI readiness. The real adapter still refuses unverified account tasks.
        private fun verifiedSnapshot(snapshot: JsonObject) = JsonObject(snapshot + ("accountVerified" to JsonPrimitive(true)))
    }
}

/** No ADB locator, real device, capture source, or game input can be reached by a UI test. */
internal class UiTestNoIoDevice : DeviceService {
    override val state = MutableStateFlow<DeviceConnectionState>(DeviceConnectionState.Disconnected)
    override val foregroundPackage: String? = null
    val attemptedIo = AtomicInteger()
    private fun forbidden(): Nothing { attemptedIo.incrementAndGet(); error("UI tests must not perform device I/O") }
    override suspend fun discover(): List<DeviceDescriptor> = forbidden()
    override suspend fun connect(serial: String, touchMode: TouchMode): Unit = forbidden()
    override suspend fun disconnect() { state.value = DeviceConnectionState.Disconnected }
    override suspend fun capture(): DeviceFrame = forbidden()
    override suspend fun queryForeground(): String? = forbidden()
    override suspend fun tap(x: Float, y: Float, expectedPackage: String): TouchResult = forbidden()
    override suspend fun swipe(fromX: Float, fromY: Float, toX: Float, toY: Float, durationMillis: Long, holdMillis: Long, expectedPackage: String): TouchResult = forbidden()
    override suspend fun back(expectedPackage: String): TouchResult = forbidden()
    override suspend fun home(): Boolean = forbidden()
    override suspend fun launch(packageName: String): Boolean = forbidden()
    override suspend fun forceStop(packageName: String): Boolean = forbidden()
    override suspend fun uiTree(): List<UiNode> = forbidden()
    override fun close() = Unit
}
