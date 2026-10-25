package com.landosol.toolbox.desktop.device

import kotlinx.coroutines.flow.StateFlow

enum class AdbFailureKind { AdbMissing, LaunchFailed, Timeout, DeviceOffline, Unauthorized, DeviceNotFound, ServerVersionConflict, ConnectionRefused, NonZeroExit }
sealed interface AdbResult {
    data class Success(val exitCode: Int, val stdout: ByteArray, val stderr: String) : AdbResult {
        fun text(): String = stdout.toString(Charsets.UTF_8).replace("\r\n", "\n")
    }
    data class Failure(val kind: AdbFailureKind, val detail: String) : AdbResult
}
/** All process I/O; shell arguments are escaped individually, binary stdout is never text-normalized. */
interface AdbExecutor : AutoCloseable {
    val executable: String
    suspend fun execute(arguments: List<String>, serial: String? = null, timeoutMillis: Long = 10_000, screenshot: Boolean = false): AdbResult
    suspend fun shell(serial: String, arguments: List<String>, timeoutMillis: Long = 10_000): AdbResult
}
data class AdbLocation(val path: String, val source: String, val version: String)
data class DeviceDescriptor(val serial: String, val label: String, val state: String, val aliases: Set<String> = setOf(serial))
data class DeviceFrame(val width: Int, val height: Int, val pixels: IntArray, val timestampMillis: Long)
enum class CaptureMode { Raw, Png }
enum class TouchMode { MaaTouch, Compatibility }
data class CaptureTiming(val mode: CaptureMode, val p50Millis: Long, val p95Millis: Long)
data class InstalledPackage(val name: String, val versionName: String, val versionCode: Long, val certificateMd5: String, val lastUpdateTime: String)
data class DeviceIdentity(
    val deviceKey: String, val brand: String, val model: String, val release: String, val sdkInt: Int,
    val supportedAbis: List<String>, val androidId: String, val displayWidth: Int, val displayHeight: Int,
    val packages: Map<String, InstalledPackage>, val capturedAtMillis: Long,
)
data class DeviceReady(val serial: String, val label: String, val width: Int, val height: Int, val timing: List<CaptureTiming>, val selectedMode: CaptureMode, val touchReady: Boolean, val awake: Boolean, val identity: DeviceIdentity)
sealed interface DeviceConnectionState {
    data object Disconnected : DeviceConnectionState
    data class Connecting(val serial: String) : DeviceConnectionState
    data class Verifying(val serial: String, val detail: String) : DeviceConnectionState
    data class Ready(val device: DeviceReady) : DeviceConnectionState
    data class Lost(val reason: String) : DeviceConnectionState
    data class Reconnecting(val attempt: Int) : DeviceConnectionState
    data class Failed(val reason: String) : DeviceConnectionState
}
sealed interface TouchResult { data object Completed : TouchResult; data class Rejected(val reason: String) : TouchResult }
data class UiBounds(val left: Int, val top: Int, val right: Int, val bottom: Int)
data class UiNode(val packageName: String, val text: String?, val description: String?, val clickable: Boolean, val bounds: UiBounds, val children: List<UiNode>)
/** Single device owner. Lost is observable before reconnect and never restarts the application task. */
interface DeviceService : AutoCloseable {
    val state: StateFlow<DeviceConnectionState>
    val foregroundPackage: String?
    suspend fun discover(): List<DeviceDescriptor>
    suspend fun connect(serial: String, touchMode: TouchMode = TouchMode.MaaTouch)
    suspend fun disconnect()
    suspend fun capture(): DeviceFrame
    suspend fun queryForeground(): String?
    suspend fun tap(x: Float, y: Float, expectedPackage: String): TouchResult
    suspend fun swipe(fromX: Float, fromY: Float, toX: Float, toY: Float, durationMillis: Long, holdMillis: Long, expectedPackage: String): TouchResult
    suspend fun back(expectedPackage: String): TouchResult
    suspend fun home(): Boolean
    suspend fun launch(packageName: String): Boolean
    /** Android package and package:child processes; query failure must not mean absent. */
    suspend fun isPackageRunning(packageName: String): Boolean = error("当前设备服务不支持进程检查")
    suspend fun forceStop(packageName: String): Boolean
    suspend fun uiTree(): List<UiNode>
}
