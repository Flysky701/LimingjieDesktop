package com.landosol.toolbox.desktop.app.control

import java.nio.file.Path
import kotlinx.coroutines.flow.StateFlow

/** Resolved by read-only device discovery, never from an account's global selected flag.
 * deviceId is the inventory's canonical ID; aliases for a verified boot share this ID.
 * bootId is a current-boot proof, NOT a permanent hardware identity.
 */
data class RuntimeDeviceTarget(
    val deviceId: String,
    val serial: String,
    val name: String,
    val aliases: Set<String>,
    val bootId: String?,
    val connectionState: String = "device",
)

/** The global owner implements discovery. Unknown identity must fail resolve, not guess. */
interface RuntimeDeviceInventory {
    suspend fun discover(force: Boolean = true): List<RuntimeDeviceTarget>
    suspend fun resolve(serial: String): RuntimeDeviceTarget
}

/** Runtime directories contain no account library, persistent credentials or shared strategy. */
data class RuntimeWorkerSpec(
    val sessionId: String,
    val device: RuntimeDeviceTarget,
    val runtimeDirectory: Path,
    val boundAccountId: Long? = null,
)

fun interface RuntimeWorkerFactory {
    fun start(spec: RuntimeWorkerSpec): WorkerLink
}

data class DeviceControlSession(
    val id: String,
    val deviceId: String,
    val name: String,
    val serial: String,
    val boundAccountId: Long?,
    val link: WorkerLink,
)

data class ManagedRuntimeDevice(
    val id: String,
    val serial: String,
    val name: String,
    val aliases: Set<String>,
    val sessionId: String? = null,
    val boundAccountId: Long? = null,
    val connected: Boolean = false,
    val busy: Boolean = false,
    val status: String = "未连接",
    val connecting: Boolean = false,
)

/**
 * Planned concrete construction (Main owns sharedHost and closes it AFTER supervisor.shutdown):
 * SharedSupervisorModel(global: ControlSessionPort, endpoint: SharedDataEndpoint,
 *                       launcher: WorkerLauncher, root: Path, scope: CoroutineScope)
 *
 * Main uses global directly and maps each session.link through RemoteControlSession.
 * Main maps ManagedRuntimeDevice -> ManagedDeviceUi; id/name/serial/sessionId/boundAccountId/
 * connected/busy/status correspond directly. DeviceControlSession -> DeviceRunTab also directly.
 */
interface RunSessionSupervisor {
    val global: ControlSessionPort
    val sessions: StateFlow<List<DeviceControlSession>>
    val devices: StateFlow<List<ManagedRuntimeDevice>>
    val selectedSessionId: StateFlow<String?>
    val selectedId: StateFlow<String?>
    val busy: StateFlow<Boolean>
    val error: StateFlow<String?>
    val dirtyInstances: StateFlow<Set<String>>

    /** Restore UI/runtime metadata only. Never scan D6 profiles, copy credentials or start a task. */
    suspend fun restore()
    suspend fun refreshDevices(force: Boolean = true)
    /** Resolves aliases before creating a worker; returns the one canonical session ID. */
    suspend fun connectDevice(serial: String): String
    /** Worker must authoritatively reject a binding change while starting/running/stopping. */
    suspend fun bindAccount(sessionId: String, accountId: Long?)
    suspend fun disconnectDevice(sessionId: String)
    /** UI already confirmed stopping this session and discarding its local drafts. */
    suspend fun closeSession(sessionId: String)
    suspend fun reconnectSession(sessionId: String)
    /** Only selectedSessionId and preview subscription change. No connect/login/start/stop. */
    suspend fun selectSession(sessionId: String)
    suspend fun stopAll()
    /** Cleans runtime workers only. Shared host outlives their final record submissions. */
    suspend fun shutdown()
    fun markDirty(id: String, dirty: Boolean)
    fun reportError(message: String)
    fun dismissError()
}

/** Global handler: read-only identity resolution; args serial:String, reply device:DeviceDescriptor
 * (including bootId). No touch deployment, no game launch, no account selection.
 * Runtime binding uses account.select with id:Long?; null explicitly unbinds when idle.
 */
object SharedSupervisorOps { const val DEVICE_RESOLVE = "device.resolve" }
