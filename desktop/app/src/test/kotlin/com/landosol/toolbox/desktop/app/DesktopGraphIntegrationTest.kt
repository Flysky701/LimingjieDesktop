package com.landosol.toolbox.desktop.app

import com.landosol.toolbox.automation.*
import com.landosol.toolbox.automation.capture.CaptureFrameBus
import com.landosol.toolbox.automation.capture.CaptureStateRegistry
import com.landosol.toolbox.data.local.AccountEntity
import com.landosol.toolbox.data.local.AppDatabase
import com.landosol.toolbox.desktop.device.*
import com.landosol.toolbox.desktop.ocr.OcrEngineStatus
import com.landosol.toolbox.desktop.platform.PowerMode
import com.landosol.toolbox.desktop.shared.DesktopBindings
import com.landosol.toolbox.labyrinth.vision.LabyrinthEntryPageState
import com.landosol.toolbox.labyrinth.*
import com.landosol.toolbox.protocol.labyrinth.*
import com.landosol.toolbox.protocol.bilibili.CaptchaChallenge
import com.landosol.toolbox.protocol.bilibili.GameServer
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.CopyOnWriteArrayList
import javax.imageio.ImageIO

/** Only device I/O is substituted. OCR, classifiers, templates, Room and application wiring are real. */
class DesktopGraphIntegrationTest {
    @get:Rule val directory = TemporaryFolder()

    @Test fun realGraphRecognizesUpstreamFixturePausesStopsAndReleasesResources() = runBlocking {
        val existingThreads = ownedThreads().map { it.threadId() }.toSet()
        val root = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
            .first { Files.exists(it.resolve("android/app/src/test/resources/shop/shop.png")) }
        val fixture = ImageIO.read(root.resolve("android/app/src/test/resources/shop/shop.png").toFile())
        // Same fixture and expectation as upstream LabyrinthEntryFrameProcessorTest.
        val fake = FixtureDevice(fixture.width, fixture.height, fixture.getRGB(0, 0, fixture.width, fixture.height, null, 0, fixture.width))
        val data = directory.root.toPath().resolve("graph")
        val graph = DesktopAppGraph(data, suppliedDevice = fake, startupUpdates = false)
        fake.onLost = { graph.stopAll(it) }
        var debugPort: Int? = null
        var captchaPort = 0
        try {
            val ocr = withTimeout(60_000) { graph.ocr.status.first { it is OcrEngineStatus.Ready || it is OcrEngineStatus.Failed } }
            assertTrue("real OCR failed to initialize: $ocr", ocr is OcrEngineStatus.Ready)
            graph.connectDevice("fixture-only")
            assertEquals(GameServer.CN_BILIBILI, graph.selectedGameServer)
            val id = graph.database.accountDao().insert(AccountEntity(alias = "offline-integration", serverId = GameServer.CN_BILIBILI.storageId,
                gameUid = null, credentialKey = "synthetic-unused", isSelected = false, createdAt = 1, updatedAt = 1))
            assertEquals("offline-integration", graph.database.accountDao().getById(id)?.alias)
            debugPort = graph.debugPort
            graph.captchaHost.open(CaptchaChallenge("synthetic", "synthetic", "synthetic", "geetest"), {}, {}).use {
                captchaPort = URI(it.url).port
            }

            withTimeout(30_000) { graph.startRecognition(dryRun = true).join() }
            assertTrue(graph.log.text() + "\n" + graph.labyrinthEntryRecognitionSession.state.value, graph.labyrinthEntryRecognitionSession.state.value.running)
            val recognized = withTimeout(30_000) { graph.labyrinthEntryRecognitionSession.state.first { it.frameCount > 0 } }
            assertTrue(recognized.running); assertTrue(recognized.dryRun)
            assertEquals(LabyrinthEntryPageState.SHOP, recognized.lastResult?.observation?.state)
            assertNotNull(graph.preview.value); assertNotNull(CaptureFrameBus.currentOwner())
            withTimeout(3000) { while (powerMode(graph) != PowerMode.VISUAL) delay(10) }
            assertEquals(0, fake.acceptedActions.get())
            val sessionId = checkNotNull(recognized.sessionId)
            assertTrue(graph.automationActionExecutor.execute(sessionId, AutomationAction.Tap(ScreenPoint(10f, 20f))) is AutomationActionResult.DryRun)
            graph.pauseTask()
            withTimeout(3000) { graph.automationOverlayCoordinator.state.first { it?.paused == true } }
            assertTrue(graph.labyrinthEntryRecognitionSession.state.value.paused)
            assertEquals(AutomationActionResult.Paused, graph.automationActionExecutor.execute(sessionId, AutomationAction.Back))
            graph.pauseTask()
            withTimeout(3000) { graph.automationOverlayCoordinator.state.first { it?.paused == false } }
            assertFalse(graph.labyrinthEntryRecognitionSession.state.value.paused)
            withTimeout(5000) { graph.stopAll("integration stop").join() }
            assertFalse(graph.labyrinthEntryRecognitionSession.state.value.running)
            assertNull(CaptureFrameBus.currentOwner()); assertFalse(CaptureStateRegistry.isActive())
            assertEquals(AutomationActionResult.StaleSession, graph.automationActionExecutor.execute(sessionId, AutomationAction.Back))
            withTimeout(3000) {
                while (powerMode(graph) != PowerMode.IDLE || taskJobs(graph).any { !it.isCompleted }) delay(10)
            }
            val capturesAfterStop = fake.captures.get()
            delay(650)
            assertEquals("stopped capture must not keep polling", capturesAfterStop, fake.captures.get())

            val foregroundSession = graph.automationSessionManager.start(AutomationMode.LABYRINTH, dryRun = false) as AutomationSessionStartResult.Started
            fake.foreground = null
            val rejected = graph.automationActionExecutor.execute(foregroundSession.session.id, AutomationAction.Back)
            // A caller cannot bypass resolution checks by creating a session directly.
            assertTrue(rejected.toString(), rejected is AutomationActionResult.Rejected)
            assertEquals(0, fake.acceptedActions.get())
            graph.automationSessionManager.stop(foregroundSession.session.id)
            fake.foreground = GameServer.CN_BILIBILI.packageName

            withTimeout(30_000) { graph.startRecognition(dryRun = true).join() }
            assertTrue(graph.labyrinthEntryRecognitionSession.state.value.running)
            fake.lose()
            withTimeout(5000) { graph.taskStopping.first { !it }; graph.labyrinthEntryRecognitionSession.state.first { !it.running } }
            assertNull(CaptureFrameBus.currentOwner()); assertFalse(CaptureStateRegistry.isActive())
            val capturedAtStop = fake.captures.get()
            fake.reconnect()
            delay(650)
            assertFalse(graph.labyrinthEntryRecognitionSession.state.value.running)
            assertEquals(capturedAtStop, fake.captures.get())

            // Stop during the three-frame startup gate must not leave a late task or frame owner.
            val starting = graph.startRecognition(dryRun = true)
            withTimeout(5000) { graph.stopAll("startup cancellation").join(); starting.join() }
            assertFalse(graph.busy); assertNull(CaptureFrameBus.currentOwner()); assertFalse(CaptureStateRegistry.isActive())
        } finally {
            val began = System.nanoTime()
            withTimeout(5000) {
                graph.shutdown()
                while (ownedScopes(graph).any { it.coroutineContext[Job]?.isCompleted == false } ||
                    ownedThreads().any { it.threadId() !in existingThreads }) delay(10)
            }
            val elapsedMillis = (System.nanoTime() - began) / 1_000_000
            assertTrue("shutdown and owned workers took ${elapsedMillis}ms", elapsedMillis <= 5000)
            println("GRAPH_LIFECYCLE shutdownMillis=$elapsedMillis graphJobs=0 ownedThreads=0 power=${powerMode(graph)}")
        }
        assertTrue(fake.closed)
        assertEquals(PowerMode.IDLE, powerMode(graph))
        assertNull(graph.preview.value); assertNull(CaptureFrameBus.currentOwner())
        assertThrows(IllegalStateException::class.java) { DesktopBindings.services }
        for (port in listOfNotNull(debugPort, captchaPort.takeIf { it > 0 })) ServerSocket().use { socket ->
            socket.reuseAddress = false; socket.bind(InetSocketAddress("127.0.0.1", port))
        }
        val database = data.resolve("databases/${AppDatabase.FILE_NAME}")
        val moved = database.resolveSibling("closed-database.db")
        Files.move(database, moved); Files.move(moved, database)
        // A second real graph proves bindings and the persisted database can be reopened in one JVM.
        val reopened = DesktopAppGraph(data, suppliedDevice = FixtureDevice(fixture.width, fixture.height, intArrayOf()), startupUpdates = false)
        try { assertEquals(1, reopened.database.accountDao().count()) }
        finally { withTimeout(15_000) { reopened.shutdown() } }
    }

    /** Observe real owners instead of substituting lifecycle collaborators in the graph. */
    private fun ownedScopes(owner: Any): List<CoroutineScope> = owner.javaClass.declaredFields
        .filter { CoroutineScope::class.java.isAssignableFrom(it.type) }
        .map { it.isAccessible = true; it.get(owner) as CoroutineScope }

    private fun taskJobs(graph: DesktopAppGraph): List<Job> = listOf("recognitionScope", "resetScope")
        .flatMap { name ->
            val field = graph.javaClass.getDeclaredField(name).apply { isAccessible = true }
            (field.get(graph) as CoroutineScope).coroutineContext[Job]!!.children.toList()
        } + ownedScopes(graph.captureService).flatMap { it.coroutineContext[Job]!!.children.toList() }

    private fun powerMode(graph: DesktopAppGraph): PowerMode {
        val power = graph.javaClass.getDeclaredField("power").apply { isAccessible = true }.get(graph)
        return power.javaClass.getDeclaredField("mode").apply { isAccessible = true }.get(power) as PowerMode
    }

    private fun ownedThreads(): Set<Thread> = Thread.getAllStackTraces().keys.filterTo(mutableSetOf()) {
        it.isAlive && (it.name in setOf("desktop-capture", "landosol-ocr", "desktop-power-state",
            "desktop-runtime-diagnostics", "labyrinth-debug-dashboard", "labyrinth-debug-http") ||
            it.name.startsWith("desktop-captcha-"))
    }

    @Test fun upstreamRerollPersistsAndActionReplayUsesRealGraph() = runBlocking {
        val root = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
            .first { Files.exists(it.resolve("android/app/src/test/resources/shop/exit_confirmation.png")) }
        val original = ImageIO.read(root.resolve("android/app/src/test/resources/shop/exit_confirmation.png").toFile())
        val fixture = java.awt.image.BufferedImage(1920,1080,java.awt.image.BufferedImage.TYPE_INT_RGB).apply {
            createGraphics().apply { drawImage(original,0,0,1920,1080,null);dispose() }
        }
        val fake = FixtureDevice(fixture.width, fixture.height, fixture.getRGB(0, 0, fixture.width, fixture.height, null, 0, fixture.width))
        val data = directory.root.toPath().resolve("reroll")
        val graph = DesktopAppGraph(data, suppliedDevice = fake, startupUpdates = false)
        val settings = LabyrinthRerollSettings(guildId = 1, difficulty = 2, maxAttempts = "1", rerollUntilFound = true,
            routeEvaluationMode = LabyrinthRouteEvaluationMode.LEGACY_TEMPLATE)
        var accountId = 0L
        try {
            accountId = graph.database.accountDao().insert(AccountEntity(alias = "offline-reroll", serverId = GameServer.CN_BILIBILI.storageId,
                gameUid = null, credentialKey = "synthetic-unused", isSelected = true, createdAt = 1, updatedAt = 1))
            AndroidLabyrinthRerollSettingsStore(graph.context).save(accountId, settings)
            val api = RerollApi()
            // Upstream LabyrinthRerollWorkflowTest: retires unmatched map; reroll-until-found ignores maxAttempts=1.
            val result = LabyrinthRerollWorkflow(api, graph.labyrinthRouteStore,
                checkpointStore = RoomLabyrinthRerollCheckpointStore(graph.database)).run(settings.toConfig(accountId))
            assertTrue(result.toString(), result is LabyrinthRerollResult.Success)
            assertEquals(2, (result as LabyrinthRerollResult.Success).attempt)
            assertEquals(listOf(1L), api.retired)
            assertEquals(2L, graph.labyrinthRouteStore.loadLatest(accountId)?.enterId)
            assertEquals(LabyrinthRouteVerdict.TARGET, RoomLabyrinthRerollCheckpointStore(graph.database).load(accountId)?.verdict)

            val ocr = withTimeout(60_000) { graph.ocr.status.first { it is OcrEngineStatus.Ready || it is OcrEngineStatus.Failed } }
            assertTrue(ocr.toString(), ocr is OcrEngineStatus.Ready)
            graph.connectDevice("fixture-only")
            withTimeout(5000) { graph.labyrinthController.uiState.first { it.selectedAccount?.id == accountId } }
            withTimeout(30_000) { graph.startRecognition(dryRun = false).join() }
            assertTrue(graph.log.text(), graph.labyrinthEntryRecognitionSession.state.value.running)
            withTimeout(15_000) { while (fake.actions.isEmpty()) delay(20) }
            // The capture worker must revoke actions and stop asynchronously, without self-joining.
            fake.nextSize = 1280 to 720
            withTimeout(5000) { while (graph.busy || CaptureStateRegistry.isActive()) delay(10) }
            assertTrue(graph.log.text().contains("1280 × 720"))
            // Real fixture classification: LabyrinthEntryFrameProcessorTest:1035.
            // SHOP_EXIT_CONFIRM_BUTTON rect (980,706,400,80), FrameProcessor:851, at 1920x1080.
            // Session:5771-5776 taps this matched anchor's centre; no handwritten action is submitted.
            assertEquals(listOf(AutomationAction.Tap(ScreenPoint(1180f, 746f))), fake.actions.toList())
        } finally { withTimeout(15_000) { graph.shutdown() } }
        val reopened = DesktopAppGraph(data, suppliedDevice = FixtureDevice(480, 270, intArrayOf()), startupUpdates = false)
        try {
            assertEquals(settings, AndroidLabyrinthRerollSettingsStore(reopened.context).load(accountId))
            assertEquals(2L, reopened.labyrinthRouteStore.loadLatest(accountId)?.enterId)
            val checkpoint = RoomLabyrinthRerollCheckpointStore(reopened.database).load(accountId)
            assertEquals(2L, checkpoint?.enterId); assertEquals(2, checkpoint?.attempt)
            assertEquals(LabyrinthRouteVerdict.TARGET, checkpoint?.verdict)
        } finally { withTimeout(15_000) { reopened.shutdown() } }
    }

    @Test fun unsupportedResolutionBlocksRealGraphBeforeBatchMutationAndEntryActions() = runBlocking {
        val fake = FixtureDevice(1280,720,IntArray(1280*720))
        val graph = DesktopAppGraph(directory.root.toPath().resolve("resolution"),suppliedDevice=fake,startupUpdates=false)
        try {
            val accountId=graph.database.accountDao().insert(AccountEntity(alias="resolution-test",serverId=GameServer.CN_BILIBILI.storageId,
                gameUid=null,credentialKey="synthetic-unused",isSelected=true,createdAt=1,updatedAt=1))
            graph.connectDevice("fixture-only")
            withTimeout(60_000) { graph.ocr.status.first { it is OcrEngineStatus.Ready } }
            withTimeout(5000) { graph.labyrinthController.uiState.first { it.selectedAccount?.id==accountId } }
            val ledger=graph.javaClass.getDeclaredField("gameClientHardCloseLedger").apply { isAccessible=true }.get(graph)
                as com.landosol.toolbox.automation.session.GameClientHardCloseLedger
            ledger.issue(accountId,GameServer.CN_BILIBILI.packageName)
            assertTrue(graph.startLabyrinthAutoRun(accountId,listOf(com.landosol.toolbox.labyrinth.batch.LabyrinthBatchGoal(
                1,1,com.landosol.toolbox.labyrinth.batch.LabyrinthBatchGoalMode.ATTEMPTS))))
            withTimeout(5000) { graph.taskStarting.first { !it } }
            assertNull("batch controller was never started",graph.labyrinthBatchController.state.value)
            assertTrue("rejected startup must not clear the ledger",ledger.consume(accountId,GameServer.CN_BILIBILI.packageName))
            assertTrue(graph.log.text().contains("1280 × 720"))
            assertFalse(graph.labyrinthController.uiState.value.isWorking)
            graph.startRecognition(dryRun=false).join()
            assertFalse(graph.labyrinthEntryRecognitionSession.state.value.running)
            assertEquals(0,fake.acceptedActions.get())
            assertEquals(0,fake.lifecycleActions.get())
            assertFalse(CaptureStateRegistry.isActive())
        } finally { withTimeout(15_000) { graph.shutdown() } }
    }

    @Test fun batchProcessPreflightPrecedesRealBusinessAndDoesNotRepeatOnReusableFallback() = runBlocking {
        for (mode in listOf("query-failure","stop-failure","user-stop","running","absent","reuse-stale")) {
            val fake=FixtureDevice(1920,1080,IntArray(1920*1080)).apply {
                packageRunning=mode!="absent"
                processQueryFails=mode=="query-failure"
                processQueryWaits=mode=="user-stop"
                forceStopSucceeds=mode!="stop-failure"
                rotateOnStop=true
            }
            val graph=DesktopAppGraph(directory.root.toPath().resolve(mode),suppliedDevice=fake,startupUpdates=false)
            try {
                val accountId=graph.database.accountDao().insert(AccountEntity(alias="offline-preflight",serverId=GameServer.CN_BILIBILI.storageId,
                    gameUid=null,credentialKey="synthetic-preflight",isSelected=true,createdAt=1,updatedAt=1))
                com.landosol.toolbox.security.AndroidKeystoreCredentialStore(graph.context).save("synthetic-preflight",
                    com.landosol.toolbox.security.AccountCredentials("synthetic","synthetic"))
                graph.connectDevice("fixture-only")
                withTimeout(60_000) { graph.ocr.status.first { it is OcrEngineStatus.Ready } }
                val ui=withTimeout(5000) { graph.labyrinthController.uiState.first { it.selectedAccount?.id==accountId && it.settingsReady } }
                var businessCalls=0
                graph.gameSessionRegistry.save(accountId,object:com.landosol.toolbox.protocol.bilibili.BilibiliGameSession {
                    override val profile=com.landosol.toolbox.protocol.bilibili.GameAccountProfile(1,"synthetic",1,"synthetic")
                    override suspend fun request(path:String,fields:LinkedHashMap<String,Any?>):com.landosol.toolbox.protocol.bilibili.GameSessionResult {
                        fake.processEvents+="business:$path"
                        businessCalls++
                        // A stale reusable opening falls back to reroll and invokes the login hook again.
                        return if(mode=="reuse-stale" && businessCalls==1)
                            com.landosol.toolbox.protocol.bilibili.GameSessionResult.Success(mapOf("enter_id" to 0L))
                        else com.landosol.toolbox.protocol.bilibili.GameSessionResult.NetworkFailure("offline test stops here")
                    }
                })
                val candidateField=graph.labyrinthController.javaClass.getDeclaredField("explicitlyReadInitialOpening").apply { isAccessible=true }
                val candidate=com.landosol.toolbox.labyrinth.batch.LabyrinthBatchReusableOpening(1,1,ui.selectedDifficulty)
                if(mode.endsWith("failure") || mode=="user-stop" || mode=="reuse-stale") candidateField.set(graph.labyrinthController,candidate)
                assertTrue(mode,graph.startLabyrinthAutoRun(accountId,listOf(com.landosol.toolbox.labyrinth.batch.LabyrinthBatchGoal(
                    1,1,com.landosol.toolbox.labyrinth.batch.LabyrinthBatchGoalMode.ATTEMPTS))))
                if(mode=="user-stop") {
                    withTimeout(5000) { while(fake.processEvents.isEmpty()) delay(10) }
                    withTimeout(5000) { graph.stopAll().join() }
                }
                withTimeout(10_000) { graph.taskStarting.first { !it } }
                val pkg=GameServer.CN_BILIBILI.packageName
                assertEquals(mode, "query:$pkg",fake.processEvents.first())
                if(mode.endsWith("failure") || mode=="user-stop") {
                    if(mode!="user-stop") assertNull(mode,graph.labyrinthBatchController.state.value)
                    assertEquals(mode,0,businessCalls)
                    if(mode.endsWith("failure")) assertEquals(candidate,candidateField.get(graph.labyrinthController))
                } else {
                    assertTrue(mode,businessCalls >= if(mode=="reuse-stale") 2 else 1)
                    assertEquals(mode,if(mode=="absent") 0 else 1,fake.processEvents.count { it=="stop:$pkg" })
                    val businessIndex=fake.processEvents.indexOfFirst { it.startsWith("business:") }
                    assertEquals(mode,if(mode=="absent") listOf("query:$pkg") else listOf("query:$pkg","stop:$pkg","query:$pkg"),fake.processEvents.take(businessIndex))
                }
                assertNotNull("failed startup must revoke actions",graph.resolutionGuard.actionRejection())
                assertEquals(0,fake.acceptedActions.get())
            } finally { withTimeout(15_000) { graph.shutdown() } }
        }
    }

    /** Map and expected route copied from the upstream workflow test; only network I/O is fake. */
    private class RerollApi : LabyrinthApi {
        val retired = mutableListOf<Long>()
        private var nextId = 0L
        override suspend fun top() = LabyrinthOperationResult.Success(LabyrinthTop(null, null, null, listOf(ClearedDifficulty(1, 1))))
        override suspend fun enter(request: LabyrinthEnterRequest): LabyrinthOperationResult<LabyrinthEnter> {
            val id = ++nextId
            val nodes = LabyrinthRoutePolicy.AREA_REQUIREMENTS.flatMap { (area, requirements) ->
                requirements.map { (column, blockType) ->
                    val blockId = area * 100L + column
                    val lastColumn = requirements.keys.max()
                    LabyrinthMapNode(area = area, column = column, row = 1, blockId = blockId, blockType = blockType,
                        questId = when (area) { 3 -> (if (id == 1L) 770330101 else 770330401).takeIf { column == lastColumn }
                            5 -> (if (id == 1L) 770530101 else 770530301).takeIf { column == lastColumn }; else -> null },
                        nextBlockIds = if (column == lastColumn) emptyList() else listOf(blockId + 1), isAreaLastPoint = column == lastColumn)
                }
            }
            return LabyrinthOperationResult.Success(LabyrinthEnter(id, nodes))
        }
        override suspend fun resume(enterId: Long): LabyrinthOperationResult<LabyrinthResume> = error("fixture does not resume")
        override suspend fun retire(enterId: Long): LabyrinthOperationResult<Unit> { retired += enterId; return LabyrinthOperationResult.Success(Unit) }
    }

    private class FixtureDevice(private val width: Int, private val height: Int, private val pixels: IntArray) : DeviceService {
        override val state = MutableStateFlow<DeviceConnectionState>(DeviceConnectionState.Disconnected)
        var onLost: (String) -> Unit = {}
        @Volatile var foreground: String? = GameServer.CN_BILIBILI.packageName
        override val foregroundPackage get() = foreground
        val captures = AtomicInteger()
        @Volatile var nextSize: Pair<Int,Int>? = null
        val acceptedActions = AtomicInteger()
        val lifecycleActions = AtomicInteger()
        var packageRunning=false
        var processQueryFails=false
        var processQueryWaits=false
        var forceStopSucceeds=true
        var rotateOnStop=false
        val processEvents=CopyOnWriteArrayList<String>()
        val actions = CopyOnWriteArrayList<AutomationAction>()
        var closed = false
        private val ready = DeviceReady("fixture-only", "upstream shop fixture", width, height, emptyList(), CaptureMode.Raw, true, true,
            DeviceIdentity("synthetic-device", "synthetic", "synthetic", "12", 31, listOf("x86_64"), "", width, height,
                mapOf(GameServer.CN_BILIBILI.packageName to InstalledPackage(GameServer.CN_BILIBILI.packageName, "synthetic", 1, "0".repeat(32), "synthetic")), 1))
        override suspend fun discover() = listOf(DeviceDescriptor(ready.serial, ready.label, "device"))
        override suspend fun connect(serial: String, touchMode: TouchMode) { reconnect() }
        override suspend fun disconnect() { state.value = DeviceConnectionState.Disconnected }
        override suspend fun capture(): DeviceFrame {
            check(state.value is DeviceConnectionState.Ready) { "synthetic lost connection" }
            captures.incrementAndGet()
            nextSize?.let { (w,h) -> return DeviceFrame(w,h,IntArray(w*h),System.currentTimeMillis()) }
            return DeviceFrame(width, height, pixels.copyOf(), System.currentTimeMillis())
        }
        fun lose() { state.value = DeviceConnectionState.Lost("synthetic device loss"); onLost("synthetic device loss") }
        fun reconnect() { state.value = DeviceConnectionState.Ready(ready) }
        override suspend fun queryForeground() = foreground
        private fun action(expectedPackage: String): TouchResult = if (foreground != expectedPackage) TouchResult.Rejected("游戏不在前台") else TouchResult.Completed.also { acceptedActions.incrementAndGet() }
        override suspend fun tap(x: Float, y: Float, expectedPackage: String) = action(expectedPackage).also { if (it is TouchResult.Completed) actions += AutomationAction.Tap(ScreenPoint(x, y)) }
        override suspend fun swipe(fromX: Float, fromY: Float, toX: Float, toY: Float, durationMillis: Long, holdMillis: Long, expectedPackage: String) = action(expectedPackage).also { if (it is TouchResult.Completed) actions += AutomationAction.Swipe(ScreenPoint(fromX, fromY), ScreenPoint(toX, toY), durationMillis, holdMillis) }
        override suspend fun back(expectedPackage: String) = action(expectedPackage).also { if (it is TouchResult.Completed) actions += AutomationAction.Back }
        override suspend fun home() = true.also { lifecycleActions.incrementAndGet() }
        override suspend fun launch(packageName: String) = true.also { lifecycleActions.incrementAndGet() }
        override suspend fun isPackageRunning(packageName:String):Boolean {
            processEvents+="query:$packageName"
            check(!processQueryFails) { "synthetic query failure" }
            if(processQueryWaits) kotlinx.coroutines.awaitCancellation()
            return packageRunning
        }
        override suspend fun forceStop(packageName: String):Boolean {
            lifecycleActions.incrementAndGet();processEvents+="stop:$packageName"
            if(!forceStopSucceeds)return false
            packageRunning=false
            if(rotateOnStop)nextSize=1080 to 1920
            return true
        }
        override suspend fun uiTree() = emptyList<UiNode>()
        override fun close() { closed = true; state.value = DeviceConnectionState.Disconnected }
    }
}
