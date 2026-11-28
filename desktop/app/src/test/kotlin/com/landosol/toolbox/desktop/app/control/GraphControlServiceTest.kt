package com.landosol.toolbox.desktop.app.control

import com.landosol.toolbox.account.AccountEditorState
import com.landosol.toolbox.account.AccountUiState
import com.landosol.toolbox.automation.AutomationSessionId
import com.landosol.toolbox.automation.GameClientResolution
import com.landosol.toolbox.vision.PixelImage
import com.landosol.toolbox.desktop.app.DesktopAppGraph
import com.landosol.toolbox.desktop.app.UserLogKind
import com.landosol.toolbox.desktop.app.UserLogLine
import com.landosol.toolbox.desktop.app.adapters.device.VisualResolutionStatus
import com.landosol.toolbox.desktop.app.adapters.storage.DesktopGoal
import com.landosol.toolbox.desktop.app.adapters.storage.DesktopPreferences
import com.landosol.toolbox.desktop.app.adapters.storage.DesktopRunRecord
import com.landosol.toolbox.desktop.app.adapters.storage.StrategyLibrary
import com.landosol.toolbox.desktop.app.strategy.BattleModelStatus
import com.landosol.toolbox.desktop.app.ui.RunConsoleState
import com.landosol.toolbox.desktop.device.*
import com.landosol.toolbox.desktop.ocr.OcrEngineStatus
import com.landosol.toolbox.desktop.ocr.OcrEngineTelemetry
import com.landosol.toolbox.desktop.platform.DesktopSettings
import com.landosol.toolbox.desktop.platform.AppConfig
import com.landosol.toolbox.desktop.platform.UserResourceLeases
import com.landosol.toolbox.desktop.shared.DeviceIdentitySnapshot
import com.landosol.toolbox.desktop.shared.GamePackageIdentity
import com.landosol.toolbox.gamedata.EventChoiceResource
import com.landosol.toolbox.gamedata.EventResource
import com.landosol.toolbox.labyrinth.*
import com.landosol.toolbox.labyrinth.kernel.event.GameEventCatalogRegistry
import com.landosol.toolbox.labyrinth.node.NodeClassification
import com.landosol.toolbox.labyrinth.node.NodeColumnKey
import com.landosol.toolbox.labyrinth.node.NodeMatchResult
import com.landosol.toolbox.labyrinth.node.NodeViewportPixels
import com.landosol.toolbox.labyrinth.vision.*
import kotlin.reflect.KClass
import kotlin.reflect.KType
import kotlin.reflect.full.primaryConstructor
import kotlin.reflect.full.memberProperties
import kotlin.reflect.full.createType
import com.landosol.toolbox.protocol.bilibili.GameServer
import com.landosol.toolbox.protocol.bilibili.BilibiliGameSession
import com.landosol.toolbox.protocol.bilibili.GameAccountProfile
import com.landosol.toolbox.protocol.bilibili.GameSessionResult
import com.landosol.toolbox.protocol.bilibili.GameLoginResult
import com.landosol.toolbox.protocol.bilibili.BilibiliGameGateway
import com.landosol.toolbox.protocol.bilibili.SdkSession
import com.landosol.toolbox.protocol.bilibili.CaptchaSolution
import com.landosol.toolbox.account.NormalizedAccountInput
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipFile

/** Synthetic local data only. Device calls fail unless a test explicitly supplies a fake result. */
class GraphControlServiceTest {
    @get:Rule val directory = TemporaryFolder()

    @Test fun generatedSerializersMatchTheConstructorBasedWireFormat() {
        // Independent v1 encoder for the field types used by these seven models.
        fun legacy(value: Any?): JsonElement = when (value) {
            null -> JsonNull
            is String -> JsonPrimitive(value)
            is Number -> JsonPrimitive(value)
            is Boolean -> JsonPrimitive(value)
            is Enum<*> -> JsonPrimitive(value.name)
            is Iterable<*> -> JsonArray(value.map(::legacy))
            is Map<*, *> -> JsonObject(value.entries.associate { (key, item) -> key.toString() to legacy(item) })
            else -> buildJsonObject {
                val type = value::class
                put("_type", type.simpleName)
                val properties = type.memberProperties.associateBy { it.name }
                for (parameter in checkNotNull(type.primaryConstructor).parameters) {
                    val name = checkNotNull(parameter.name)
                    put(name, legacy(properties.getValue(name).getter.call(value)))
                }
            }
        }

        val goal = DesktopGoal(4, 2, clears = false)
        val config = AppConfig(DesktopSettings(windowY = -10), DesktopPreferences(goals = listOf(goal)), true)
        val strategy = LabyrinthStrategySettings(personalRoleScores = mapOf("_type" to 3),
            openingRosters = mapOf(4 to listOf(listOf("101"))))
        val identity = DeviceIdentitySnapshot("test", "brand", "model", "14", 34, listOf("x86"), "id", 1920, 1080,
            mapOf("_type" to GamePackageIdentity("v", 12, "test")), 15L)
        for (model in listOf(config, config.environment, config.ui, goal, strategy, LabyrinthRerollSettings(), identity)) {
            val wire = legacy(model)
            assertEquals(model::class.simpleName, wire.toString(), ControlModelCodec.encode(model).toString())
            assertEquals(model, ControlModelCodec.decode(wire, model::class.createType()))
        }
        val entries = Json.parseToJsonElement("""{"personalRoleScores":[{"_k":"101","_v":4}]}""")
        assertEquals(mapOf("101" to 4), ControlModelCodec.decode<LabyrinthStrategySettings>(entries).personalRoleScores)
    }

    @Test fun compactSnapshotsKeepFullRecordsAvailableAndRefreshFinishedRows() = runBlocking {
        val graph = DesktopAppGraph(directory.root.toPath(), suppliedDevice = NoIoDevice(), startupUpdates = false, dataOnly = true)
        val service = GraphControlService(graph, "compact", "离线测试")
        try {
            val now = System.currentTimeMillis()
            graph.log.add("synthetic-key", kind = UserLogKind.KEY)
            val beforeFlood = service.snapshot(false)
            repeat(60) { i ->
                graph.ux.history.put(com.landosol.toolbox.desktop.app.adapters.storage.DesktopRunRecord(
                    "run-$i", now + i, "synthetic", "test", "test"))
                graph.log.add("synthetic-line-$i")
            }
            val first = service.snapshot(false)
            val unchanged = service.snapshot(false)
            assertEquals(50, first.getValue("history").jsonArray.size)
            assertEquals(60, first.getValue("historyTotal").jsonPrimitive.int)
            assertEquals(50, first.getValue("logs").jsonArray.size)
            val keyLogs = ControlModelCodec.decode<List<UserLogLine>>(first.getValue("keyLogs"))
            assertEquals(UserLogKind.KEY, keyLogs.single { it.text == "synthetic-key" }.kind)
            assertSame(beforeFlood["keyLogs"], first["keyLogs"])
            assertSame(first["history"], unchanged["history"])
            assertSame(first["settings"], unchanged["settings"])
            assertFalse(first.containsKey("crashes"))
            assertEquals(60, service.execute(ControlOps.HISTORY_LOAD, emptyArgs()).getValue("items").jsonArray.size)
            assertEquals(graph.log.lines.value.size, service.execute(ControlOps.LOG_LOAD, emptyArgs()).getValue("items").jsonArray.size)
            // Completion can update an older row without changing list length or the last 50.
            graph.ux.history.finish("run-0", "完成", "合成数据")
            val completed = service.snapshot(false)
            assertEquals(first["history"], completed["history"])
            assertNotEquals(first["historyRevision"], completed["historyRevision"])
            val all = service.execute(ControlOps.HISTORY_LOAD, emptyArgs()).getValue("items").jsonArray
            assertEquals("完成", all.first().jsonObject.getValue("result").jsonPrimitive.content)
        } finally { service.shutdown() }
    }

    @Test fun discoveryFailureCannotBeReturnedAsAValidEmptyInventory() = runBlocking {
        val fake = com.landosol.toolbox.desktop.app.adapters.device.ConnectionFixture()
        val graph = DesktopAppGraph(directory.root.toPath(), suppliedDevice = fake, startupUpdates = false, dataOnly = true)
        val service = GraphControlService(graph, "discovery", "检测")
        try {
            assertEquals(1, service.execute(ControlOps.DEVICE_DISCOVER, emptyArgs()).getValue("devices").jsonArray.size)
            fake.onDiscover = { error("ADB 检测失败") }
            assertEquals("ADB 检测失败", runCatching { service.execute(ControlOps.DEVICE_DISCOVER, emptyArgs()) }.exceptionOrNull()?.message)
            assertTrue(runCatching { service.execute(ControlOps.DEVICE_DISCOVER, buildJsonObject { put("force", false) }) }.isFailure)
            fake.onDiscover = {}
            fake.descriptors = emptyList()
            assertTrue(service.execute(ControlOps.DEVICE_DISCOVER, emptyArgs()).getValue("devices").jsonArray.isEmpty())
        } finally { service.shutdown() }
    }

    @Test fun formsUseOriginalValidationAndSnapshotsNeverPublishEditorPasswords() = runBlocking {
        val fake = NoIoDevice()
        val graph = DesktopAppGraph(directory.root.toPath().resolve("data"), suppliedDevice = fake, startupUpdates = false)
        val runtime = directory.root.toPath().resolve("runtime")
        val leases = UserResourceLeases(runtime)
        val service = GraphControlService(graph, "synthetic-profile", "离线测试", leases, runtime)
        try {
            val earlyGoals = listOf(DesktopGoal(1, 1))
            service.execute(ControlOps.SAVE_SETUP, controlArgs(
                "settings" to ControlModelCodec.encode(LabyrinthRerollSettings()), "goals" to ControlModelCodec.encode(earlyGoals)))
            assertEquals(earlyGoals, graph.ux.preferences.state.value.goals)
            service.execute(ControlOps.ACCOUNT_NEW, emptyArgs())
            val editor = AccountEditorState(alias = "synthetic-account", loginId = "unused-login", password = "synthetic-password-unused",
                server = GameServer.CN_BILIBILI, gameUid = "99999")
            service.execute(ControlOps.ACCOUNT_SAVE, controlArgs("editor" to ControlModelCodec.encode(editor)))
            val row = withTimeout(10_000) { graph.accounts.uiState.first { it.accounts.size == 1 } }.accounts.single()
            service.execute(ControlOps.ACCOUNT_SELECT, controlArgs("id" to JsonPrimitive(row.id)))
            service.execute(ControlOps.ACCOUNT_EDIT, controlArgs("id" to JsonPrimitive(row.id)))
            withContext(Dispatchers.Main) { graph.accounts.updateEditor { it.copy(password = "never-publish-this-draft") } }
            val opened = service.snapshot(false)
            assertFalse(opened.toString().contains("never-publish-this-draft"))
            val accountState = ControlModelCodec.decode<AccountUiState>(opened.getValue("accounts"))
            assertEquals("", accountState.editor?.password)
            assertEquals("unused-login", accountState.editor?.loginId)
            service.execute(ControlOps.ACCOUNT_DISMISS, emptyArgs())

            val strategy = LabyrinthStrategySettings(bossTeamMode = LabyrinthBossTeamMode.SINGLE_TEAM,
                personalRoleScores = mapOf("100101" to 83), specialEquipmentMode = LabyrinthSpecialEquipmentMode.AUTO_EQUIP,
                specialEquipmentKinds = setOf(LabyrinthCombatKind.EX))
            service.execute(ControlOps.STRATEGY_SAVE, controlArgs("value" to ControlModelCodec.encode(strategy)))
            assertEquals(strategy, graph.labyrinthStrategySettings.state.value)
            val invalid = strategy.copy(playerWeight = 101)
            val rejected = runCatching { service.execute(ControlOps.STRATEGY_SAVE, controlArgs("value" to ControlModelCodec.encode(invalid))) }.exceptionOrNull()
            assertNotNull(rejected)
            assertEquals(strategy, graph.labyrinthStrategySettings.state.value)

            val reroll = graph.labyrinthController.uiState.value.rerollSettings().copy(difficulty = 2, maxAttempts = "19", rerollUntilFound = false)
            val goals = listOf(DesktopGoal(1, 2, false))
            service.execute(ControlOps.SAVE_SETUP, controlArgs("settings" to ControlModelCodec.encode(reroll), "goals" to ControlModelCodec.encode(goals)))
            assertEquals(goals, graph.ux.preferences.state.value.goals)
            withTimeout(5_000) { graph.labyrinthController.uiState.first { it.maxAttempts == "19" } }
            val snapshot = service.snapshot(false)
            assertEquals(reroll, ControlModelCodec.decode<RunConsoleState>(snapshot.getValue("console")).task.rerollSettings())
            assertEquals(JsonNull, snapshot["preview"])
            assertFalse(snapshot.getValue("accountVerified").jsonPrimitive.boolean)
            verifyNonEmptyRecognitionSnapshot(service, graph)

            // A manually entered UID is informational; normal task startup no longer requires a manual read.
            assertEquals(0, fake.ioCalls.get())

            // Simulate the gateway's successful authentication callback, without any request.
            graph.authenticatedAccountGuard!!.invoke(GameServer.CN_BILIBILI, 1234567)
            assertThrows(IllegalStateException::class.java) { leases.acquireAccount("cn-bilibili", "1234567") }
            service.execute(ControlOps.STOP, emptyArgs())
            leases.acquireAccount("cn-bilibili", "1234567").close()

            // An authenticated new account can then reach the original protocol workflow.
            // The synthetic session returns one offline failure before any write request.
            val requested = CompletableDeferred<String>()
            graph.gameSessionRegistry.save(row.id, object : BilibiliGameSession {
                override val profile = GameAccountProfile(1234567, "synthetic", 100, "synthetic")
                override suspend fun request(path: String, fields: LinkedHashMap<String, Any?>): GameSessionResult {
                    requested.complete(path)
                    return GameSessionResult.ProtocolFailure("synthetic-protocol-stop")
                }
            })
            service.execute(ControlOps.START, controlArgs("task" to JsonPrimitive("REROLL"), "retreatConfirmed" to JsonPrimitive(true)))
            assertTrue(withTimeout(5_000) { requested.await() }.contains("labyrinth"))
            withTimeout(5_000) { graph.labyrinthController.uiState.first { !it.isWorking && it.message.orEmpty().contains("synthetic-protocol-stop") } }
            service.revokeActions("synthetic disconnect")
            assertNotNull(runCatching { service.execute(ControlOps.ACCOUNT_NEW, emptyArgs()) }.exceptionOrNull())
        } finally { withTimeout(15_000) {
            coroutineScope { listOf(async { service.shutdown() }, async { service.shutdown() }, async { graph.shutdown() }).awaitAll() }
        } }
        assertEquals(0, fake.ioCalls.get())
        assertEquals(1, fake.closeCalls.get())
    }

    @Test fun stopPreemptsAnInFlightDiscoveryInsteadOfWaitingForTheCommandMutex() = runBlocking {
        val fake = NoIoDevice().apply { discoveryMayWait = true }
        val graph = DesktopAppGraph(directory.root.toPath().resolve("data"), suppliedDevice = fake, startupUpdates = false)
        val runtime = directory.root.toPath().resolve("runtime")
        val service = GraphControlService(graph, "stop-profile", "停止测试", UserResourceLeases(runtime), runtime)
        try {
            val pending = async { runCatching { service.execute(ControlOps.DEVICE_DISCOVER, emptyArgs()) } }
            withTimeout(5_000) { fake.discoveryStarted.await() }
            withTimeout(5_000) { service.execute(ControlOps.STOP, emptyArgs()) }
            assertTrue(withTimeout(5_000) { pending.await() }.isFailure)
            assertFalse(graph.busy)
            assertNull(graph.deviceConnection.activity.value)
            assertEquals(1, fake.ioCalls.get())
        } finally { withTimeout(15_000) { service.shutdown() } }
    }

    @Test fun accountRowStartupLockAllowsOtherAccountsAndReleasesOnClose() {
        val runtime = directory.root.toPath().resolve("runtime")
        AccountStartLease.acquire(runtime, "synthetic-authority", 1).use {
            assertThrows(IllegalStateException::class.java) { AccountStartLease.acquire(runtime, "synthetic-authority", 1) }
            AccountStartLease.acquire(runtime, "synthetic-authority", 2).close()
            AccountStartLease.acquire(runtime, "another-authority", 1).close()
        }
        AccountStartLease.acquire(runtime, "synthetic-authority", 1).close()
    }

    @Test fun unknownUidStartsNormallyAndAcquiresUidLeaseAfterAuthenticatedLogin() = runBlocking {
        val runtime = directory.root.toPath().resolve("runtime")
        val data = directory.root.toPath().resolve("normal-start")
        val leases = UserResourceLeases(runtime)
        val requestSeen = CompletableDeferred<Unit>()
        var accountId = 0L
        val authenticatedProfile = GameAccountProfile(7654321, "synthetic-channel", 100, "synthetic")
        val session = object : BilibiliGameSession {
            override val profile = authenticatedProfile
            override suspend fun request(path: String, fields: LinkedHashMap<String, Any?>): GameSessionResult {
                assertThrows(IllegalStateException::class.java) { leases.acquireAccount("cn-channel", "7654321") }
                requestSeen.complete(Unit)
                return GameSessionResult.ProtocolFailure("synthetic-normal-start-stop")
            }
        }
        val gateway = object : BilibiliGameGateway {
            override suspend fun loginAndLoadProfile(sdkSession: SdkSession, deviceSeed: String, captcha: CaptchaSolution?): GameLoginResult {
                assertThrows(IllegalStateException::class.java) { AccountStartLease.acquire(runtime, AccountStartLease.authority(data), accountId) }
                // The UID lease is not guessed from loginId or a manually filled UID.
                leases.acquireAccount("cn-channel", "7654321").close()
                return GameLoginResult.Success(authenticatedProfile, session)
            }
        }
        val fake = NoIoDevice()
        val graph = DesktopAppGraph(data, suppliedDevice = fake, startupUpdates = false, suppliedGameGateway = { gateway })
        val service = GraphControlService(graph, "normal-start", "离线正常启动", leases, runtime)
        try {
            accountId = graph.accountRepository.create(NormalizedAccountInput("synthetic-channel", "unused-uid", "unused-access-key", null, GameServer.CN_XIAOMI))
            withTimeout(10_000) { graph.labyrinthController.uiState.first { it.selectedAccount?.id == accountId && it.settingsReady } }
            service.execute(ControlOps.START, controlArgs("task" to JsonPrimitive("REROLL"), "retreatConfirmed" to JsonPrimitive(true)))
            withTimeout(5_000) { requestSeen.await() }
            withTimeout(5_000) { graph.labyrinthController.uiState.first { !it.isWorking && it.message.orEmpty().contains("synthetic-normal-start-stop") } }
            service.execute(ControlOps.STOP, emptyArgs())
            AccountStartLease.acquire(runtime, AccountStartLease.authority(data), accountId).close()
            leases.acquireAccount("cn-channel", "7654321").close()
            assertEquals(0, fake.ioCalls.get())
        } finally { service.shutdown() }
    }

    @Test fun saveSetupRejectsAccountMismatchAndControllerRejectionWhilePreservingGoalsOnly() = runBlocking {
        val fake = NoIoDevice()
        val graph = DesktopAppGraph(directory.root.toPath().resolve("save-guard"), suppliedDevice = fake, startupUpdates = false)
        val service = GraphControlService(graph, "save-guard", "保存守卫")
        try {
            val earlyGoals = listOf(DesktopGoal(3, 5))
            service.execute(ControlOps.SAVE_SETUP, controlArgs(
                "settings" to ControlModelCodec.encode(LabyrinthRerollSettings()),
                "goals" to ControlModelCodec.encode(earlyGoals),
            ))
            assertEquals(earlyGoals, graph.ux.preferences.state.value.goals)

            val accountId = graph.accountRepository.create(NormalizedAccountInput("guard-account", "uid-1", "pw", null, GameServer.CN_BILIBILI))
            withTimeout(10_000) { graph.labyrinthController.uiState.first { it.selectedAccount?.id == accountId && it.settingsReady } }

            // Account mismatch must reject before persisting.
            val mismatchError = runCatching {
                service.execute(ControlOps.SAVE_SETUP, buildJsonObject {
                    put("settings", ControlModelCodec.encode(graph.labyrinthController.uiState.value.rerollSettings().copy(targetScore = 50_000L)))
                    put("goals", ControlModelCodec.encode(earlyGoals))
                    put("accountId", accountId + 999)
                })
            }.exceptionOrNull()
            assertTrue(mismatchError?.message.orEmpty().contains("当前账号已切换"))
            assertEquals(0L, graph.labyrinthController.uiState.value.targetScore)

            // Valid save persists targetScore and includes it in the controller confirmation message.
            val updated = graph.labyrinthController.uiState.value.rerollSettings().copy(targetScore = 50_000L)
            service.execute(ControlOps.SAVE_REROLL, buildJsonObject {
                put("value", ControlModelCodec.encode(updated))
                put("accountId", accountId)
            })
            val refreshed = withTimeout(5_000) { graph.labyrinthController.uiState.first { it.targetScore == 50_000L } }
            assertTrue(refreshed.message.orEmpty().contains("50000"))

            // Controller-level rejection (e.g. working) must surface instead of returning accepted.
            val activeField = graph.labyrinthController.javaClass.getDeclaredField("chrome").apply { isAccessible = true }
            @Suppress("UNCHECKED_CAST")
            val chrome = activeField.get(graph.labyrinthController) as MutableStateFlow<*>
            assertNull(graph.labyrinthController.saveSettingsChecked(updated.copy(targetScore = 52_000L)))
            assertNotNull(chrome.value)
            val err = graph.labyrinthController.saveSettingsChecked(updated.copy(targetScore = -1L))
            assertTrue(err.orEmpty().contains("刷分目标无效"))
        } finally {
            service.shutdown()
        }
    }

    @Test fun manualStopCancelsAuthenticatedRequestWithoutRevocationErrorWhileRevokeDenies() = runBlocking {
        val runtime = directory.root.toPath().resolve("stop-guard-runtime")
        val data = directory.root.toPath().resolve("stop-guard-data")
        val leases = UserResourceLeases(runtime)
        val fake = NoIoDevice()
        val graph = DesktopAppGraph(data, suppliedDevice = fake, startupUpdates = false)
        val service = GraphControlService(graph, "stop-guard", "停止守卫", leases, runtime)
        try {
            val guard = checkNotNull(graph.authenticatedAccountGuard)
            val stoppingField = service.javaClass.getDeclaredField("stopping").apply { isAccessible = true }
            val stoppingFlag = stoppingField.get(service) as java.util.concurrent.atomic.AtomicBoolean
            stoppingFlag.set(true)
            val stopFailure = runCatching { guard(GameServer.CN_BILIBILI, 987654L) }.exceptionOrNull()
            assertTrue("Expected CancellationException during manual stop, got $stopFailure", stopFailure is CancellationException)
            // Must not have acquired a UID lease.
            leases.acquireAccount("cn-bilibili", "987654").close()

            stoppingFlag.set(false)
            service.revokeActions("synthetic revoke")
            val revokeFailure = runCatching { guard(GameServer.CN_BILIBILI, 987654L) }.exceptionOrNull()
            assertTrue("Expected IllegalStateException after revoke, got $revokeFailure", revokeFailure is IllegalStateException)
            assertTrue(revokeFailure?.message.orEmpty().contains("控制连接已停止"))
        } finally {
            service.shutdown()
        }
    }

    private fun emptyArgs() = JsonObject(emptyMap())

    @Suppress("UNCHECKED_CAST")
    private suspend fun verifyNonEmptyRecognitionSnapshot(service: GraphControlService, graph: DesktopAppGraph) {
        val pixels = NodeViewportPixels.sample(PixelImage(1920, 1080, IntArray(1920 * 1080)))
        val rect = EntryPixelRect(100, 200, 120, 80)
        val choice = EventChoiceResource(id = "choice-1", name = "示例选项", slot = 1, description = "获得金币")
        val event = EventResource(id = "event-1", name = "示例事件", area = 3, choices = listOf(choice))
        val catalog = GameEventCatalogRegistry(listOf(event))
        val eventObservation = LabyrinthEventChoiceObservation(
            event = event,
            choices = listOf(LabyrinthEventChoiceVisual(choice, rect, 0.95, 0.92)),
            rawText = "事件标题",
            confidence = 0.96,
            rivalMargin = 0.25,
            stableFrames = 2,
            trusted = true,
            catalogMatched = true,
            sourceCatalog = catalog,
        )
        val result = LabyrinthEntryFrameResult(
            observation = LabyrinthEntryPageObservation(LabyrinthEntryPageState.NODE_MAP_VIEW, .98,
                mapOf(LabyrinthEntryPageState.NODE_MAP_VIEW to .98, LabyrinthEntryPageState.SHOP to .12),
                LabyrinthAnchorScores(mapOf(EntryAnchorId.NODE_HEADER to .97))),
            matchedFeatures = listOf("synthetic-map"), elapsedMillis = 8,
            anchorMatches = mapOf("synthetic" to EntryAnchorMatch(.97, rect)),
            nodeClassifications = listOf(NodeClassification(2, 1, 1, .97, true, rect)),
            characterMatches = listOf(LabyrinthCharacterMatch("joined-1", "100101", "示例角色", .99, rect)),
            eventChoiceSelection = eventObservation,
            relicChoiceSelection = LabyrinthRelicChoiceObservation(listOf(
                LabyrinthRelicMatch("choice-1", "1", "示例遗物", "火", "+1", "示例效果", .95, rect, iconRect = rect))),
            frameWidth = 1920, frameHeight = 1080, stageMillis = mapOf("nodes" to 7L),
            nodeSearchMode = "typed", nodeSearchWindowCount = 12, nodeViewportSignature = "synthetic", nodeViewportPixels = pixels,
        )
        val member = LabyrinthRecommendedTeamMember("100101", "示例角色")
        val nodeBindings = NodeMatchResult(
            currentNodeId = 30101L,
            currentNodeType = 1,
            nextNode = null,
            visibleNodes = emptyList(),
            isComplete = false,
            columnNodeCounts = mapOf(NodeColumnKey(3, 2) to 3),
        )
        assertEquals(nodeBindings, ControlModelCodec.decode<NodeMatchResult>(ControlModelCodec.encode(nodeBindings)))
        val state = LabyrinthEntryRecognitionSessionState(status = LabyrinthEntryRecognitionStatus.RUNNING,
            sessionId = AutomationSessionId(17L), dryRun = true, paused = true, receivedFrameCount = 7, frameCount = 5,
            lastResult = result, joinedCharacters = listOf(LabyrinthJoinedCharacter("100101", "示例角色", 0, .99, 1, 2)),
            observedRelics = listOf(LabyrinthObservedRelic("1", "示例遗物", "火", "+1", "效果", .95, 1, 2)),
            routeProgress = LabyrinthRouteProgress(2, 4, 21, "下个节点", rect),
            combatContext = LabyrinthCombatContext(LabyrinthCombatKind.EX, 1, 3),
            battleTeamRecommendation = LabyrinthBattleTeamRecommendation(listOf(member), LabyrinthTeamDamageType.PHYSICAL,
                83.5, member, null, listOf("示例推荐原因"), 4, 3),
            pathBDecisionSummary = LabyrinthPathBDecisionSummary("PathB-NN", "节点", "普通战斗", "test"),
            nodeBindings = nodeBindings,
            alphaRupeeBalance = 120,
            frameTrace = LabyrinthFrameTrace(listOf("NODE_MAP_VIEW"), actionRejectReason = "已暂停", nodeTargetBlockId = 123L,
                nodeSearchWindowCount = 12, nodeTrackerReliable = true, nodeTrackerWorldLeft = 16.5))
        // A complete synthetic session state exercises the real snapshot path without starting capture.
        val field = graph.labyrinthEntryRecognitionSession.javaClass.getDeclaredField("_state").apply { isAccessible = true }
        val mutable = field.get(graph.labyrinthEntryRecognitionSession) as MutableStateFlow<LabyrinthEntryRecognitionSessionState>
        val original = mutable.value
        try {
            mutable.value = state
            val document = service.snapshot(false)
            val restored = ControlModelCodec.decode<RunConsoleState>(document.getValue("console"))
            assertEquals(state.copy(lastResult = result.copy(
                nodeViewportPixels = null,
                eventChoiceSelection = eventObservation.copy(sourceCatalog = null),
            ), nodeBindings = null), restored.entry)
            assertTrue(restored.busy)
            assertNull(restored.entry.lastResult?.nodeViewportPixels)
            assertNull(restored.entry.lastResult?.eventChoiceSelection?.sourceCatalog)
            assertNull(restored.entry.nodeBindings)
            assertSame(pixels, mutable.value.lastResult?.nodeViewportPixels)
            assertSame(catalog, mutable.value.lastResult?.eventChoiceSelection?.sourceCatalog)
            assertSame(nodeBindings, mutable.value.nodeBindings)
            assertTrue("snapshot must not contain raw viewport buffers", document.toString().length < 500_000)
        } finally { mutable.value = original }
    }

    @Test fun snapshotModelsContainOnlyCodecSupportedTypes() {
        val strippedProperties = setOf(
            LabyrinthEntryFrameResult::class to "nodeViewportPixels",
            LabyrinthEventChoiceObservation::class to "sourceCatalog",
        )
        val primitivesAndJson = setOf<KClass<*>>(
            String::class, Char::class, Boolean::class, Int::class, Long::class,
            Float::class, Double::class, Short::class, Byte::class,
            IntArray::class, LongArray::class, FloatArray::class, DoubleArray::class, ByteArray::class,
            JsonElement::class, JsonObject::class, JsonArray::class, JsonPrimitive::class,
        )
        val collections = setOf<KClass<*>>(
            List::class, Set::class, Collection::class, Iterable::class, Map::class,
        )
        val visited = mutableSetOf<KClass<*>>()
        val unsupported = mutableListOf<String>()

        val auditor = object {
            fun auditClass(type: KClass<*>, path: String) {
                if (type in primitivesAndJson || type.java.isEnum) return
                if (type in collections) return
                if (!visited.add(type)) return
                if (type.isSealed) {
                    val children = type.sealedSubclasses
                    if (children.isEmpty()) unsupported += "$path: sealed ${type.qualifiedName} has no subclasses"
                    children.forEach { auditClass(it, "$path->${it.simpleName}") }
                    return
                }
                if (type.objectInstance != null) return
                val qualified = type.qualifiedName.orEmpty()
                if (!qualified.startsWith("com.landosol.toolbox.") || !(type.isData || type.isValue)) {
                    unsupported += "$path: $qualified"
                    return
                }
                val constructor = type.primaryConstructor
                if (constructor == null) {
                    unsupported += "$path: missing primaryConstructor on $qualified"
                    return
                }
                constructor.parameters.forEach { param ->
                    val name = param.name ?: "<unnamed>"
                    if ((type to name) in strippedProperties) return@forEach
                    auditType(param.type, "$path.$name")
                }
            }

            fun auditType(type: KType, path: String) {
                val klass = type.classifier as? KClass<*> ?: run {
                    unsupported += "$path: non-KClass type $type"
                    return
                }
                if (klass in collections) {
                    type.arguments.forEachIndexed { idx, proj ->
                        val argType = proj.type ?: run {
                            unsupported += "$path[$idx]: star projection on $type"
                            return@forEachIndexed
                        }
                        auditType(argType, "$path[$idx]")
                    }
                    return
                }
                auditClass(klass, path)
            }
        }

        val roots = listOf(
            RunConsoleState::class,
            DesktopSettings::class,
            DesktopPreferences::class,
            AccountUiState::class,
            LabyrinthStrategySettings::class,
            StrategyLibrary::class,
            VisualResolutionStatus::class,
            OcrEngineStatus::class,
            OcrEngineTelemetry::class,
            BattleModelStatus::class,
            DeviceIdentitySnapshot::class,
            GameClientResolution::class,
            DeviceDescriptor::class,
            DesktopRunRecord::class,
            UserLogLine::class,
        )
        roots.forEach { root -> auditor.auditClass(root, root.simpleName ?: root.toString()) }
        assertTrue("Unsupported control model types reachable from snapshot: $unsupported", unsupported.isEmpty())
    }

    @Test fun noticesDismissAndReplayExportAndOverwriteDiagnosticsWork() = runBlocking {
        val graph = DesktopAppGraph(directory.root.toPath().resolve("data"), suppliedDevice = NoIoDevice(), startupUpdates = false)
        val service = GraphControlService(graph, "diag-replay", "诊断导出测试")
        try {
            graph.addNotice("配置损坏恢复测试通知")
            assertEquals(listOf("配置损坏恢复测试通知"), service.snapshot(false).getValue("notices").jsonArray.map { it.jsonPrimitive.content })
            service.execute(ControlOps.NOTICES_DISMISS, emptyArgs())
            assertTrue(service.snapshot(false).getValue("notices").jsonArray.isEmpty())

            val diagTarget = directory.root.toPath().resolve("existing-debug.zip")
            java.nio.file.Files.writeString(diagTarget, "old-content")
            service.execute(ControlOps.DIAGNOSTICS_EXPORT, buildJsonObject { put("path", diagTarget.toString()) })
            ZipFile(diagTarget.toFile()).use { zip ->
                fun entry(name: String) = zip.getInputStream(checkNotNull(zip.getEntry(name))).bufferedReader().use { it.readText() }
                assertTrue(Json.parseToJsonElement(entry("manifest.json")).jsonObject.containsKey("traceId"))
                assertEquals("waiting", Json.parseToJsonElement(entry("latest-snapshot.json")).jsonObject.getValue("status").jsonPrimitive.content)
                assertEquals("", entry("state-history.jsonl"))
                assertNotNull(zip.getEntry("process.log"))
            }

            val stats = service.execute(ControlOps.REPLAYS_STATS, emptyArgs())
            assertEquals(0, stats.getValue("episodes").jsonPrimitive.int)
            val replayTarget = directory.root.toPath().resolve("replays.jsonl")
            val exported = service.execute(ControlOps.REPLAYS_EXPORT, buildJsonObject { put("path", replayTarget.toString()) })
            assertEquals(0, exported.getValue("exported").jsonPrimitive.int)
            assertTrue(java.nio.file.Files.exists(replayTarget))
        } finally {
            service.shutdown()
        }
    }

    private class NoIoDevice : DeviceService {
        override val state = MutableStateFlow<DeviceConnectionState>(DeviceConnectionState.Disconnected)
        override val foregroundPackage: String? = null
        val ioCalls = AtomicInteger()
        val closeCalls = AtomicInteger()
        val discoveryStarted = CompletableDeferred<Unit>()
        var discoveryMayWait = false
        override suspend fun discover(): List<DeviceDescriptor> {
            ioCalls.incrementAndGet()
            check(discoveryMayWait) { "Unexpected device discovery" }
            discoveryStarted.complete(Unit)
            awaitCancellation()
        }
        private fun unexpected(): Nothing { ioCalls.incrementAndGet(); error("No device I/O allowed") }
        override suspend fun connect(serial: String, touchMode: TouchMode) = unexpected()
        override suspend fun disconnect() { state.value = DeviceConnectionState.Disconnected }
        override suspend fun capture(): DeviceFrame = unexpected()
        override suspend fun queryForeground(): String? = unexpected()
        override suspend fun tap(x: Float, y: Float, expectedPackage: String): TouchResult = unexpected()
        override suspend fun swipe(fromX: Float, fromY: Float, toX: Float, toY: Float, durationMillis: Long, holdMillis: Long, expectedPackage: String): TouchResult = unexpected()
        override suspend fun back(expectedPackage: String): TouchResult = unexpected()
        override suspend fun home(): Boolean = unexpected()
        override suspend fun launch(packageName: String): Boolean = unexpected()
        override suspend fun forceStop(packageName: String): Boolean = unexpected()
        override suspend fun uiTree(): List<UiNode> = unexpected()
        override fun close() { closeCalls.incrementAndGet() }
    }
}
