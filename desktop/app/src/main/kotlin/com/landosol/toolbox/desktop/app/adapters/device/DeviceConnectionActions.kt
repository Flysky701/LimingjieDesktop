package com.landosol.toolbox.desktop.app.adapters.device

import com.landosol.toolbox.desktop.device.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One connection operation across the toolbar, device page and setup wizard. No queued retries. */
class DeviceConnectionActions(
    private val device: DeviceService,
    private val configuredPath: () -> String,
    private val onError: (String) -> Unit,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val admission = Any()
    @Volatile private var operation: Job? = null
    private var closed = false
    private var disconnecting = false
    private val activityState = MutableStateFlow<String?>(null)
    val activity = activityState.asStateFlow()
    private val devicesState = MutableStateFlow<List<DeviceDescriptor>>(emptyList())
    val devices = devicesState.asStateFlow()
    private val messageState = MutableStateFlow<String?>(null)
    val message = messageState.asStateFlow()
    @Volatile private var discoveryPath: String? = null
    @Volatile private var connectedKey: Triple<String, String, TouchMode>? = null

    suspend fun discover(force: Boolean = true) {
        if (!force && (discoveryPath == configuredPath().trim() || device.state.value is DeviceConnectionState.Ready)) return
        perform("正在检测模拟器") {
            val path = configuredPath().trim()
            if (!force && (discoveryPath == path || device.state.value is DeviceConnectionState.Ready)) return@perform
            check(!device.state.value.inProgress) { "请等待连接完成，或先取消连接" }
            // Remember failed/empty automatic attempts too. Re-entering a page must not retry indefinitely.
            if (discoveryPath != path) devicesState.value = emptyList()
            discoveryPath = path
            messageState.value = null
            devicesState.value = device.discover()
            if (devicesState.value.isEmpty()) messageState.value = "没有发现可用设备，请启动模拟器并开启 ADB 调试，或填写本机连接地址。"
        }
    }

    suspend fun connect(serial: String, mode: TouchMode, reconnect: Boolean = false,
                        beforeConnect: suspend () -> Unit = {}, onReady: (DeviceReady) -> Unit = {}) {
        if (!reconnect && connectedKey == Triple(configuredPath().trim(), serial, mode) &&
            (device.state.value as? DeviceConnectionState.Ready)?.device?.serial == serial) return
        perform("正在连接 $serial") {
            require(serial.isNotBlank()) { "请先选择设备或填写地址" }
            val key = Triple(configuredPath().trim(), serial, mode)
            if (!reconnect && connectedKey == key && (device.state.value as? DeviceConnectionState.Ready)?.device?.serial == serial) return@perform
            messageState.value = null
            try {
                beforeConnect()
                if (reconnect) device.disconnect()
                device.connect(serial, mode)
                when (val result = device.state.value) {
                    is DeviceConnectionState.Ready -> { connectedKey = key; onReady(result.device) }
                    is DeviceConnectionState.Failed -> error(result.reason)
                    else -> Unit
                }
            } catch (cancelled: CancellationException) {
                withContext(NonCancellable) { device.disconnect() }
                throw cancelled
            }
        }
    }

    suspend fun configure(block: suspend () -> Unit) = perform("正在检查 ADB 配置") {
        check(device.state.value !is DeviceConnectionState.Ready && !device.state.value.inProgress) { "请先断开设备，再更换 ADB" }
        messageState.value = null
        discoveryPath = null; connectedKey = null; devicesState.value = emptyList()
        block()
    }

    suspend fun cancel() = stop(disconnect = false)
    suspend fun disconnect(beforeDisconnect: suspend () -> Unit = {}) = stop(disconnect = true, beforeDisconnect)

    private suspend fun stop(disconnect: Boolean, beforeDisconnect: suspend () -> Unit = {}) {
        // Cancel both locator/discovery and connect. Waiting for the lifecycle lock alone would queue behind boot polling.
        val running = synchronized(admission) {
            if (closed || disconnecting) return
            disconnecting = true
            operation
        }
        try {
            withContext(NonCancellable) {
                running?.cancelAndJoin()
                mutex.withLock {
                    activityState.value = if (disconnect) "正在断开" else "正在取消"
                    try {
                        if (disconnect || device.state.value.inProgress) {
                            beforeDisconnect()
                            device.disconnect()
                            connectedKey = null
                        }
                        messageState.value = null
                    } finally { activityState.value = null }
                }
            }
        } finally { synchronized(admission) { disconnecting = false } }
    }

    private suspend fun perform(label: String, block: suspend () -> Unit) {
        currentCoroutineContext().ensureActive()
        val work = synchronized(admission) {
            if (closed || disconnecting || !mutex.tryLock()) return
            activityState.value = label
            scope.async(start = CoroutineStart.LAZY) {
                try { block() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) {
                    messageState.value = error.message ?: "设备操作失败，请重试"
                    onError(messageState.value!!)
                }
            }.also { job ->
                operation = job
                job.invokeOnCompletion {
                    synchronized(admission) { operation = null; activityState.value = null; mutex.unlock() }
                }
            }
        }
        // The application owns the work. Disposing the caller/page only cancels await, not this job.
        work.start()
        work.await()
    }

    suspend fun closeAndJoin() {
        val running = synchronized(admission) { closed = true; operation }
        scope.cancel()
        running?.cancelAndJoin()
    }
}

val DeviceConnectionState.inProgress: Boolean get() = this is DeviceConnectionState.Connecting ||
    this is DeviceConnectionState.Verifying || this is DeviceConnectionState.Reconnecting || this is DeviceConnectionState.Lost
