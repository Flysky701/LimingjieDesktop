package com.landosol.toolbox.desktop.app.adapters.device

import com.landosol.toolbox.desktop.device.*
import com.landosol.toolbox.protocol.bilibili.GameServer
import java.nio.file.Path
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Keeps a stable application port while ADB can be selected after first launch. */
class ManagedDeviceService(
    private val directory: Path,
    private val configuredPath: () -> String?,
    private val onEvent: (String) -> Unit,
    private val onLost: (String) -> Unit,
) : DeviceService {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private var delegate: DeviceService? = null
    private var stateJob: Job? = null
    private var path: String? = null
    private val current = MutableStateFlow<DeviceConnectionState>(DeviceConnectionState.Disconnected)
    override val state = current.asStateFlow()
    override val foregroundPackage get() = delegate?.foregroundPackage
    private suspend fun service(): DeviceService = mutex.withLock {
        val location = AdbLocator.find(configuredPath()?.takeIf { it.isNotBlank() }, directory.resolve("platform-tools"))
            ?: error("未找到 ADB。请在设备页选择模拟器自带的 adb.exe，或安装 Android Platform Tools")
        if (delegate == null || path != location.path) {
            check(current.value !is DeviceConnectionState.Ready) { "请先断开设备，再更换 ADB" }
            stateJob?.cancelAndJoin()
            delegate?.close()
            val next = AdbDeviceService(ProcessAdbExecutor(location.path), directory, GameServer.entries.map { it.packageName }.toSet(), onEvent, onLost)
            path = location.path; delegate = next
            stateJob = scope.launch { next.state.collect { current.value = it } }
            onEvent("ADB 已就绪：${location.source}")
        }
        delegate!!
    }
    private fun connected() = checkNotNull(delegate) { "请先连接设备" }
    override suspend fun discover() = service().discover()
    override suspend fun connect(serial: String, touchMode: TouchMode) {
        val active = service()
        try { active.connect(serial, touchMode) } finally { current.value = active.state.value }
    }
    override suspend fun disconnect() { delegate?.disconnect(); current.value = DeviceConnectionState.Disconnected }
    override suspend fun capture() = connected().capture()
    override suspend fun queryForeground() = connected().queryForeground()
    override suspend fun tap(x: Float, y: Float, expectedPackage: String) = connected().tap(x, y, expectedPackage)
    override suspend fun swipe(fromX: Float, fromY: Float, toX: Float, toY: Float, durationMillis: Long, holdMillis: Long, expectedPackage: String) =
        connected().swipe(fromX, fromY, toX, toY, durationMillis, holdMillis, expectedPackage)
    override suspend fun back(expectedPackage: String) = connected().back(expectedPackage)
    override suspend fun home() = connected().home()
    override suspend fun launch(packageName: String) = connected().launch(packageName)
    override suspend fun forceStop(packageName: String) = connected().forceStop(packageName)
    override suspend fun uiTree() = connected().uiTree()
    override fun close() { scope.cancel(); delegate?.close(); delegate = null }
}
