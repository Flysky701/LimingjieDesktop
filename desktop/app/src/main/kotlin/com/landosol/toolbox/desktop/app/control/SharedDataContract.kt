package com.landosol.toolbox.desktop.app.control

import com.landosol.toolbox.desktop.app.adapters.storage.DesktopRunRecord
import com.landosol.toolbox.desktop.app.adapters.storage.DesktopPreferences
import com.landosol.toolbox.desktop.platform.DesktopSettings
import com.landosol.toolbox.labyrinth.LabyrinthRerollSettings
import com.landosol.toolbox.labyrinth.LabyrinthStrategySettings

/** Passed through worker environment, never command lines, snapshots, logs, or device files. */
data class SharedDataEndpoint(val port: Int, val token: String, val authorityId: String) {
    override fun toString() = "SharedDataEndpoint(port=$port, authorityId=$authorityId, token=REDACTED)"
    companion object {
        const val PORT_ENV = "LIMINGJIE_SHARED_DATA_PORT"
        const val TOKEN_ENV = "LIMINGJIE_SHARED_DATA_TOKEN"
        const val AUTHORITY_ENV = "LIMINGJIE_SHARED_DATA_AUTHORITY"
        fun fromEnvironment(environment: Map<String, String> = System.getenv()): SharedDataEndpoint {
            val port = requireNotNull(environment[PORT_ENV]?.toIntOrNull()) { "共享数据服务地址缺失" }
            val token = requireNotNull(environment[TOKEN_ENV]) { "共享数据服务认证信息缺失" }
            val authority = requireNotNull(environment[AUTHORITY_ENV]) { "共享数据身份缺失" }
            require(port in 1..65535 && token.length >= 32 && authority.matches(Regex("[a-f0-9]{64}"))) { "共享数据服务参数无效" }
            return SharedDataEndpoint(port, token, authority)
        }
    }
}

data class SharedAccountMetadata(val id: Long, val alias: String, val serverId: String, val gameUid: String?, val updatedAt: Long)

/** Ephemeral task data; credentials remain in worker memory and never enter its durable cache. */
data class SharedTaskData(
    val account: SharedAccountMetadata,
    val loginId: String,
    val password: String,
    val reroll: LabyrinthRerollSettings,
    val strategy: LabyrinthStrategySettings,
    val strategyRevision: Long,
    val settings: DesktopSettings,
    val authenticatedUid: Long? = null,
    val taskDefaults: DesktopPreferences = DesktopPreferences(),
) {
    override fun toString() = "SharedTaskData(accountId=${account.id}, strategyRevision=$strategyRevision, credentials=REDACTED)"
}

/** One authority; runtime clients do not open or edit its database. */
interface SharedDataAccess {
    val authorityId: String
    suspend fun taskData(accountId: Long): SharedTaskData
    suspend fun settings(): DesktopSettings
    suspend fun updateGameUid(accountId: Long, uid: String)
    suspend fun saveReroll(accountId: Long, value: LabyrinthRerollSettings)
    suspend fun putRecord(sessionId: String, deviceName: String, accountId: Long?, record: DesktopRunRecord)
}

/* Frozen wiring:
 * suspend SharedDataHost.start(directory: Path, startupUpdates: Boolean = true): SharedDataHost
 *   val endpoint: SharedDataEndpoint; val control: ControlSessionPort; suspend fun shutdown()
 * SharedDataClient(endpoint: SharedDataEndpoint): SharedDataAccess
 * DesktopAppGraph(..., sharedData: SharedDataAccess? = null, dataOnly: Boolean = false)
 * WorkerMain: SharedDataEndpoint.fromEnvironment() -> SharedDataClient -> Graph(sharedData=client)
 * Host owns its sole Graph(dataOnly=true); never create another Graph in the UI process.
 * Existing runtime GraphControlService.account.select(id) binds a shared account; does not
 * select it globally. Global pages use host.control and never a runtime account/strategy editor.
 * DesktopUxController.history should use graph.runHistory (one forwarding journal per runtime).
 */
