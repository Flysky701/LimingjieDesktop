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
    private val locate: suspend (String?, Path) -> AdbLocation? = { selected, downloaded -> AdbLocator.find(selected, downloaded) },
    private val createService: (AdbLocation) -> DeviceService = { location ->
        AdbDeviceService(ProcessAdbExecutor(location.path, server = AdbServerClient()), directory, GameServer.entries.map { it.packageName }.toSet(), onEvent, onLost)
    },
) : DeviceService {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private var delegate: DeviceService? = null
    private var stateJob: Job? = null
    private var selection: String? = null
    private var location: AdbLocation? = null
    private val closing = Any()
    @Volatile private var closed = false
    private val current = MutableStateFlow<DeviceConnectionState>(DeviceConnectionState.Disconnected)
    override val state = current.asStateFlow()
    override val foregroundPackage get() = delegate?.foregroundPackage
    private suspend fun service(refresh: Boolean = false): DeviceService = mutex.withLock {
        check(!closed) { "设备服务已关闭" }
        val selectedPath = configuredPath()?.trim()?.takeIf { it.isNotEmpty() }
        if (!refresh && delegate != null && selection == selectedPath) return@withLock delegate!!
        check(delegate?.state?.value !is DeviceConnectionState.Ready) { "请先断开设备，再更换 ADB" }
        val found = locate(selectedPath, directory.resolve("tools/platform-tools"))
        if (found == null) {
            // An explicit failed recheck invalidates an earlier successful validation of the same path.
            stateJob?.cancelAndJoin()
            synchronized(closing) { delegate?.close(); delegate = null; location = null }
            error(if (selectedPath != null) "指定的 ADB 不可用。请在“设备 → ADB 工具”重新选择 adb.exe 并保存，或清空路径后使用自动查找。"
                else "未找到 ADB。请在“设备 → ADB 工具”选择模拟器自带的 adb.exe，或下载 Platform Tools。")
        }
        if (delegate != null && selection == selectedPath && location?.path == found.path) {
            location = found
            return@withLock delegate!!
        }
        stateJob?.cancelAndJoin()
        currentCoroutineContext().ensureActive()
        synchronized(closing) {
            check(!closed) { "设备服务已关闭" }
            delegate?.close()
            val next = createService(found)
            selection = selectedPath; location = found; delegate = next
            stateJob = scope.launch(start = CoroutineStart.UNDISPATCHED) { next.state.collect { current.value = it } }
            onEvent("ADB 已就绪：${found.source}")
        }
        delegate!!
    }
    suspend fun prepare(): AdbLocation { service(refresh = true); return checkNotNull(location) }
    private fun connected() = checkNotNull(delegate) { "请先连接设备" }
    override suspend fun discover() = service().discover()
    override suspend fun resolveDevice(serial: String) = service().resolveDevice(serial)
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
    override suspend fun isPackageRunning(packageName: String) = connected().isPackageRunning(packageName)
    override suspend fun forceStop(packageName: String) = connected().forceStop(packageName)
    override suspend fun uiTree() = connected().uiTree()
    override fun close() = synchronized(closing) { closed = true; scope.cancel(); delegate?.close(); delegate = null }
}
