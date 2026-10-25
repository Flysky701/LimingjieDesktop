package com.landosol.toolbox.desktop.app.control

import com.landosol.toolbox.desktop.device.*
import com.landosol.toolbox.desktop.ocr.OcrEngine
import com.landosol.toolbox.desktop.ocr.OcrEngineStatus
import com.landosol.toolbox.labyrinth.LabyrinthRerollSettings
import com.landosol.toolbox.labyrinth.LabyrinthRerollSettingsStore
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.concurrent.ConcurrentHashMap

/** Per-process account options cache. The authoritative copy is always in SharedDataHost. */
internal class SharedRerollCache : LabyrinthRerollSettingsStore {
    private val values = ConcurrentHashMap<Long, LabyrinthRerollSettings>()
    override fun load(accountId: Long) = values[accountId] ?: LabyrinthRerollSettings()
    override fun save(accountId: Long, settings: LabyrinthRerollSettings) { values[accountId] = settings }
}

internal class DataOnlyOcr : OcrEngine {
    override val status = MutableStateFlow<OcrEngineStatus>(OcrEngineStatus.NotLoaded)
    override fun submit(width: Int, height: Int, pixels: IntArray, onResult: (String?) -> Unit) = onResult(null)
    override fun warmUp() = Unit
    override fun close() = Unit
}

/** Global discovery can list transports but can never establish a control/capture session. */
internal class DiscoveryOnlyDevice(private val delegate: DeviceService) : DeviceService {
    override val state = MutableStateFlow<DeviceConnectionState>(DeviceConnectionState.Disconnected)
    override val foregroundPackage: String? = null
    private fun forbidden(): Nothing = error("全局数据服务不执行设备任务，请使用设备运行会话")
    override suspend fun discover() = delegate.discover()
    override suspend fun resolveDevice(serial: String) = delegate.resolveDevice(serial)
    override suspend fun connect(serial: String, touchMode: TouchMode): Unit = forbidden()
    override suspend fun disconnect() = delegate.disconnect()
    override suspend fun capture(): DeviceFrame = forbidden()
    override suspend fun queryForeground(): String? = null
    override suspend fun tap(x: Float, y: Float, expectedPackage: String): TouchResult = forbidden()
    override suspend fun swipe(fromX: Float, fromY: Float, toX: Float, toY: Float, durationMillis: Long, holdMillis: Long, expectedPackage: String): TouchResult = forbidden()
    override suspend fun back(expectedPackage: String): TouchResult = forbidden()
    override suspend fun home(): Boolean = forbidden()
    override suspend fun launch(packageName: String): Boolean = forbidden()
    override suspend fun forceStop(packageName: String): Boolean = forbidden()
    override suspend fun uiTree(): List<UiNode> = forbidden()
    override fun close() = delegate.close()
}
