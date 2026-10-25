package com.landosol.toolbox.desktop.app.control

import com.landosol.toolbox.account.AccountEditorState
import com.landosol.toolbox.account.AccountUiState
import com.landosol.toolbox.desktop.app.DesktopAppGraph
import com.landosol.toolbox.desktop.app.adapters.storage.*
import com.landosol.toolbox.desktop.app.ux.UiTestNoIoDevice
import com.landosol.toolbox.desktop.platform.DesktopSettings
import com.landosol.toolbox.desktop.platform.UserResourceLeases
import com.landosol.toolbox.labyrinth.*
import com.landosol.toolbox.protocol.bilibili.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList

/** Temporary stores and synthetic protocol sessions only. Never connects a device or game server. */
class SharedDataIntegrationTest {
    @get:Rule val directory = TemporaryFolder()

    @Test fun soleDataOwnerAuthenticatesRpcAndUnifiesAccountsStrategyOptionsAndRecords() = runBlocking {
        val host = SharedDataHost.start(directory.root.toPath().resolve("authority"), startupUpdates = false)
        try {
            val port = host.control
            port.request(ControlOps.ACCOUNT_NEW)
            val editor = AccountEditorState(alias = "shared-synthetic", loginId = "synthetic-login", password = "unused-shared-secret",
                server = GameServer.CN_BILIBILI, gameUid = "99999")
            port.request(ControlOps.ACCOUNT_SAVE, controlArgs("editor" to ControlModelCodec.encode(editor)))
            val document = withTimeout(10_000) { port.snapshots.first { raw ->
                raw["accounts"]?.let { ControlModelCodec.decode<AccountUiState>(it).accounts.isNotEmpty() } == true
            } }
            val id = ControlModelCodec.decode<AccountUiState>(document.getValue("accounts")).accounts.single().id
            assertFalse(document.toString().contains("unused-shared-secret"))
            assertFalse(document.toString().contains(host.endpoint.token))
            val client = SharedDataClient(host.endpoint)
            val first = client.taskData(id)
            assertEquals("unused-shared-secret", first.password)
            assertNull(first.authenticatedUid)

            val strategy = LabyrinthStrategySettings(playerWeight = 61)
            port.request(ControlOps.STRATEGY_SAVE, controlArgs("value" to ControlModelCodec.encode(strategy)))
            val reroll = LabyrinthRerollSettings(difficulty = 2, maxAttempts = "17", rerollUntilFound = false)
            client.saveReroll(id, reroll)
            port.request(ControlOps.PREFS_PATCH, buildJsonObject {
                put("goals", ControlModelCodec.encode(listOf(DesktopGoal(1, 3))))
                put("selectedTask", "REROLL")
            })
            client.updateGameUid(id, "1234567")
            val next = client.taskData(id)
            assertEquals(strategy, next.strategy)
            assertTrue(next.strategyRevision > first.strategyRevision)
            assertEquals(reroll, next.reroll)
            assertEquals(1234567L, next.authenticatedUid)
            assertEquals(listOf(DesktopGoal(1, 3)), next.taskDefaults.goals)
            assertEquals("REROLL", next.taskDefaults.selectedTask)

            val record = DesktopRunRecord("same-batch-id", System.currentTimeMillis(), "shared-synthetic", "离线测试", "目标")
            client.putRecord("device-one", "设备 1", id, record)
            client.putRecord("device-two", "设备 2", id, record)
            val records = withTimeout(10_000) { port.snapshots.first { (it["history"] as? JsonArray)?.size == 2 } }
                .getValue("history").let { ControlModelCodec.decode<List<DesktopRunRecord>>(it) }
            assertEquals(2, records.map { it.id }.distinct().size)
            assertEquals(setOf("device-one", "device-two"), records.map { it.deviceId }.toSet())
            assertTrue(records.all { it.accountId == id })

            assertTrue(runCatching { port.request(ControlOps.START, buildJsonObject { put("task", "REROLL") }) }.isFailure)
            assertTrue(runCatching { SharedDataClient(host.endpoint.copy(token = "invalid-token-value-that-is-long-enough")).taskData(id) }.isFailure)
            port.request(ControlOps.ACCOUNT_SAVE, controlArgs("editor" to ControlModelCodec.encode(editor.copy(id = id, password = "replacement-unused-secret"))))
            assertNull(client.taskData(id).authenticatedUid)
        } finally { host.shutdown() }
    }

    @Test fun runtimeUsesMemoryCredentialsAndFreezesStrategyUntilItsNextNormalStart() = runBlocking {
        val shared = SyntheticSharedData()
        val requests = Channel<Unit>(Channel.UNLIMITED)
        val authenticatedProfile = GameAccountProfile(2345678, "synthetic", 100, "synthetic")
        val protocolSession = object : BilibiliGameSession {
            override val profile = authenticatedProfile
            override suspend fun request(path: String, fields: LinkedHashMap<String, Any?>): GameSessionResult {
                requests.send(Unit); awaitCancellation()
            }
        }
        val gateway = object : BilibiliGameGateway {
            override suspend fun loginAndLoadProfile(sdkSession: SdkSession, deviceSeed: String, captcha: CaptchaSolution?) =
                GameLoginResult.Success(authenticatedProfile, protocolSession)
        }
        val data = directory.root.toPath().resolve("runtime")
        val device = UiTestNoIoDevice()
        val graph = DesktopAppGraph(data, suppliedDevice = device, startupUpdates = false, sharedData = shared, suppliedGameGateway = { gateway })
        val leaseRoot = directory.root.toPath().resolve("leases")
        val service = GraphControlService(graph, "runtime-one", "设备 1", UserResourceLeases(leaseRoot), leaseRoot)
        try {
            service.execute(ControlOps.ACCOUNT_SELECT, buildJsonObject { put("id", 5) })
            assertEquals(listOf(DesktopGoal(1, 3)), graph.ux.preferences.state.value.goals)
            assertEquals("REROLL", graph.ux.preferences.state.value.selectedTask)
            service.execute(ControlOps.PREFS_PATCH, buildJsonObject { put("goals", ControlModelCodec.encode(listOf(DesktopGoal(2, 7)))) })
            assertTrue(runCatching { service.execute(ControlOps.STRATEGY_SAVE, controlArgs("value" to ControlModelCodec.encode(LabyrinthStrategySettings()))) }.isFailure)

            service.execute(ControlOps.START, buildJsonObject { put("task", "REROLL"); put("retreatConfirmed", true) })
            withTimeout(10_000) { requests.receive() }
            assertEquals(2345678L, shared.verifiedUid)
            val firstStrategy = graph.labyrinthStrategySettings.state.value
            shared.currentStrategy = firstStrategy.copy(playerWeight = 29); shared.revision = 2
            shared.defaults = shared.defaults.copy(goals = listOf(DesktopGoal(3, 9)))
            assertEquals(firstStrategy, graph.labyrinthStrategySettings.state.value)
            assertEquals(1L, graph.strategyRevision.value)
            service.execute(ControlOps.STOP, JsonObject(emptyMap()))

            service.execute(ControlOps.START, buildJsonObject { put("task", "REROLL"); put("retreatConfirmed", true) })
            withTimeout(10_000) { requests.receive() }
            assertEquals(29, graph.labyrinthStrategySettings.state.value.playerWeight)
            assertEquals(2L, graph.strategyRevision.value)
            assertEquals(listOf(DesktopGoal(2, 7)), graph.ux.preferences.state.value.goals)
            service.execute(ControlOps.STOP, JsonObject(emptyMap()))
            service.execute(ControlOps.ACCOUNT_SELECT, buildJsonObject { put("id", JsonNull) })
            assertNull(graph.labyrinthController.uiState.value.selectedAccount)
            assertEquals(0, device.attemptedIo.get())
            Files.walk(data).use { paths -> assertFalse(paths.anyMatch { it.fileName.toString().contains("credential", ignoreCase = true) || it.fileName.toString().contains("sdk-session", ignoreCase = true) }) }
        } finally { service.shutdown() }
    }

    private class SyntheticSharedData : SharedDataAccess {
        override val authorityId = "synthetic-authority"
        var currentStrategy = LabyrinthStrategySettings(playerWeight = 63)
        var revision = 1L
        var verifiedUid: Long? = null
        var options = LabyrinthRerollSettings()
        var defaults = DesktopPreferences(goals = listOf(DesktopGoal(1, 3)), selectedTask = "REROLL")
        val records = CopyOnWriteArrayList<DesktopRunRecord>()
        override suspend fun taskData(accountId: Long) = SharedTaskData(
            SharedAccountMetadata(accountId, "synthetic", GameServer.CN_XIAOMI.storageId, null, 1), "unused-uid", "unused-memory-secret",
            options, currentStrategy, revision, DesktopSettings(), verifiedUid, defaults)
        override suspend fun settings() = DesktopSettings()
        override suspend fun updateGameUid(accountId: Long, uid: String) { verifiedUid = uid.toLong() }
        override suspend fun saveReroll(accountId: Long, value: LabyrinthRerollSettings) { options = value }
        override suspend fun putRecord(sessionId: String, deviceName: String, accountId: Long?, record: DesktopRunRecord) { records += record }
    }
}
