package com.landosol.toolbox.desktop.app

import android.util.Log
import java.nio.file.Path
import com.landosol.toolbox.AppVersion
import com.landosol.toolbox.desktop.shared.*
import com.landosol.toolbox.desktop.device.*
import com.landosol.toolbox.desktop.ocr.*
import com.landosol.toolbox.desktop.platform.*
import com.landosol.toolbox.desktop.app.adapters.storage.*
import com.landosol.toolbox.desktop.app.adapters.captcha.*
import com.landosol.toolbox.desktop.app.adapters.cndb.*
import com.landosol.toolbox.desktop.app.adapters.device.*
import com.landosol.toolbox.protocol.bilibili.BilibiliSdkGateway
import com.landosol.toolbox.automation.session.ChannelPromptNode
import com.landosol.toolbox.automation.session.ChannelPromptWindow
import com.landosol.toolbox.automation.session.ChannelPromptBounds
import com.landosol.toolbox.labyrinth.labyrinthRerollStatusText
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import com.landosol.toolbox.account.AccountRepository
import com.landosol.toolbox.account.SelectedAccountServer
import com.landosol.toolbox.automation.AutomationAction
import com.landosol.toolbox.automation.AutomationSessionManager
import com.landosol.toolbox.automation.GameClientLaunchGate
import com.landosol.toolbox.automation.GameClientResolution
import com.landosol.toolbox.automation.GameClientResolver
import com.landosol.toolbox.automation.ScreenPoint
import com.landosol.toolbox.automation.SessionBoundActionExecutor
import com.landosol.toolbox.automation.capture.CaptureFrameBus
import com.landosol.toolbox.automation.capture.CapturedFrame
import com.landosol.toolbox.automation.capture.CaptureStateRegistry
import com.landosol.toolbox.automation.session.AccessibilityForegroundPresenceObserver
import com.landosol.toolbox.automation.session.CompositeSessionResetBackend
import com.landosol.toolbox.automation.session.GameClientRelaunchResult
import com.landosol.toolbox.automation.session.GameSessionResetStartResult
import com.landosol.toolbox.automation.session.GameSessionResetStatus
import com.landosol.toolbox.automation.session.GameSessionResetWorkflow
import com.landosol.toolbox.automation.session.Relauncher
import com.landosol.toolbox.automation.session.SessionBlockClassifier
import com.landosol.toolbox.automation.session.SessionBlockKind
import com.landosol.toolbox.automation.session.SessionExpiryFrameTracker
import com.landosol.toolbox.automation.session.SessionExpiryTerminator
import com.landosol.toolbox.automation.session.toSessionBlockScores
import com.landosol.toolbox.automation.capture.CaptureState
import com.landosol.toolbox.clanbattle.ClanBattleRecognitionSession
import com.landosol.toolbox.automation.overlay.AutomationOverlayCoordinator
import com.landosol.toolbox.data.local.AppDatabase
import com.landosol.toolbox.labyrinth.LabyrinthEntryActionPlanner
import com.landosol.toolbox.labyrinth.LabyrinthEntryActionPlannerConfig
import com.landosol.toolbox.labyrinth.AndroidLabyrinthRoleDecisionDataLoader
import com.landosol.toolbox.labyrinth.LabyrinthCnDatabaseUpdateResult
import com.landosol.toolbox.labyrinth.LabyrinthRoleDecisionDataResult
import com.landosol.toolbox.labyrinth.RoomLabyrinthRunStateStore
import com.landosol.toolbox.labyrinth.RoomLabyrinthRouteStore
import com.landosol.toolbox.labyrinth.node.AndroidLabyrinthNodeTemplateLoader
import com.landosol.toolbox.labyrinth.vision.AndroidLabyrinthEntryFrameProcessor
import com.landosol.toolbox.labyrinth.vision.LabyrinthEntryFrameResult
import com.landosol.toolbox.labyrinth.vision.LabyrinthEntryPageState
import com.landosol.toolbox.labyrinth.vision.EntryAnchorId
import com.landosol.toolbox.protocol.bilibili.BilibiliLoginCoordinator
import com.landosol.toolbox.protocol.bilibili.BilibiliGameGateway
import com.landosol.toolbox.protocol.bilibili.BilibiliGameGatewayFactory
import com.landosol.toolbox.protocol.bilibili.GameServer
import com.landosol.toolbox.protocol.bilibili.BilibiliNativeLoginCoordinator
import com.landosol.toolbox.protocol.bilibili.BilibiliSdkGatewayFactory
import com.landosol.toolbox.protocol.bilibili.InMemoryGameSessionRegistry
import com.landosol.toolbox.security.AndroidKeystoreCredentialStore
import com.landosol.toolbox.security.AndroidKeystoreSdkSessionStore
import com.landosol.toolbox.labyrinth.LabyrinthEntryRecognitionSession
import com.landosol.toolbox.labyrinth.debug.LabyrinthDebugDashboardServer
import com.landosol.toolbox.labyrinth.LabyrinthEntryRecognitionStartResult
import com.landosol.toolbox.labyrinth.LabyrinthAutoRunConfig
import com.landosol.toolbox.labyrinth.LabyrinthAutoRunWorkflow
import com.landosol.toolbox.labyrinth.LabyrinthAutoRunRoundOutcome
import com.landosol.toolbox.labyrinth.LabyrinthExecutionGateResult
import com.landosol.toolbox.labyrinth.validateLabyrinthExecution
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class DesktopAppGraph(
    val dataDirectory: Path,
    private val openedDatabase: AppDatabase? = null,
    suppliedDevice: DeviceService? = null,
    private val startupUpdates: Boolean = true,
) : AutoCloseable {
    val log = UserLog()
    val ux by lazy { com.landosol.toolbox.desktop.app.ui.DesktopUxController(this) }
    private val noticeState = MutableStateFlow<List<String>>(emptyList())
    val notices = noticeState.asStateFlow()
    fun addNotice(message: String) { noticeState.update { it + message }; log.add(message, true) }
    fun dismissNotices() { noticeState.value = emptyList() }
    private val failures = CoroutineExceptionHandler { _, failure ->
        Log.e("DesktopTask", "后台任务异常", failure)
        log.add("任务发生异常：${failure.message ?: failure.javaClass.simpleName}。请查看诊断日志。", true)
        if (!closed) stopAll("后台任务异常，已停止当前任务")
    }
    val context = DesktopContext(dataDirectory.toFile()) { addNotice("配置损坏，已使用默认值并保留备份：${it.absolutePath}") }
    private val filesDir get() = context.filesDir
    private val cacheDir get() = context.cacheDir
    private val assets get() = context.assets
    val settings = DesktopSettingsStore(dataDirectory) { addNotice("桌面设置已从默认值恢复，原文件已备份：$it") }
    private val network = NetworkProxyController().also { it.setMode(settings.state.value.networkMode) }
    private val power = PowerSaveController()
    private val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main + failures)
    private val recognitionScope = CoroutineScope(SupervisorJob() + Dispatchers.Default + failures)
    private val resetScope = CoroutineScope(SupervisorJob() + Dispatchers.Default + failures)
    val identityStore = FileDeviceIdentityStore(dataDirectory) { log.add(it, true) }
    val ocr = OnnxOcrEngine()
    val captchaHost = LoopbackCaptchaHost()
    val device: DeviceService = suppliedDevice ?: ManagedDeviceService(dataDirectory, { settings.state.value.adbPath }, { log.add(it) }, { reason -> stopAll(reason) })
    val resolutionGuard = VisualResolutionGuard(device) { reason ->
        autoRunScope.launch {
            labyrinthController.reportMessage(reason)
            stopAll(reason)
        }
    }
    private val actionDevice: DeviceService = ResolutionGuardedDevice(device, resolutionGuard)
    val deviceConnection = DeviceConnectionActions(device, { settings.state.value.adbPath }, { log.add(it, true) })
    private val previewState = MutableStateFlow<CapturePixels?>(null)
    val preview = previewState.asStateFlow()
    val captureService = DesktopCaptureService(
        source = { device.capture().let {
            resolutionGuard.observe(it.width, it.height)
            CapturePixels(it.width, it.height, it.pixels, it.timestampMillis)
        } },
        preview = { previewState.value = it }, onWarning = { log.add(it, true) }, onLost = { stopAll(it) },
        deferFrame = { resolutionGuard.deferTransitionFrame(it.width, it.height) },
    )
    private val viewModels = androidx.lifecycle.ViewModelStore()
    val accounts by lazy { com.landosol.toolbox.account.AccountViewModel(accountRepository).also { viewModels.put("accounts", it) } }
    private val diagnostics by lazy { com.landosol.toolbox.labyrinth.debug.LabyrinthRuntimeDiagnostics(context).also { diagnostics ->
        diagnostics.environmentDetails = {
            val ready = (device.state.value as? DeviceConnectionState.Ready)?.device
            org.json.JSONObject().put("connectionState", device.state.value.javaClass.simpleName)
                .put("model", ready?.identity?.model ?: "unknown").put("width", ready?.width ?: 0).put("height", ready?.height ?: 0)
                .put("captureMode", ready?.selectedMode?.name ?: "none").put("captureRunning", CaptureStateRegistry.isActive())
                .put("touchReady", ready?.touchReady ?: false).put("awake", ready?.awake ?: false)
                .put("lastFrameMillis", previewState.value?.timestampMillis ?: 0)
                .put("screenshotP95Millis", ready?.timing?.firstOrNull { it.mode == ready.selectedMode }?.p95Millis ?: 0)
        }
    } }
    private var debugSocket: java.net.ServerSocket? = null
    val debugPort: Int? = (8765..8775).firstOrNull { port ->
        runCatching { java.net.ServerSocket().use { it.reuseAddress = false; it.bind(java.net.InetSocketAddress("127.0.0.1", port)) } }.isSuccess
    }
    val debugUrl get() = debugPort?.let { "http://127.0.0.1:$it/" }
    private var rerollHostJob: Job? = null
    @Volatile private var closed = false
    private val installedServices = DesktopServices(OcrPort(ocr::submit), WindowsSecretProtector(), identityStore, captchaHost, DesktopLog::snapshot)
    init {
        DesktopLog.initialize(dataDirectory.resolve("logs"))
        DesktopBindings.install(installedServices)
    }

    private val databaseUpdateScope = CoroutineScope(SupervisorJob() + Dispatchers.IO + failures)
    private val accountServerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO + failures)

    private fun start() {
        // First line of every process log, so a diagnostic bundle always names the build it came
        // from even when the interesting part of the run happened much later.
        Log.i(APP_LOG_TAG, "黎明界助手启动：${AppVersion.display}")
        logGameClient()
        // 只有登录时会临时转竖屏的渠道才容忍旋转；其他服务器保持上游「尺寸一变就停止捕获」。
        com.landosol.toolbox.automation.capture.CaptureRotationPolicy.toleratesTransientRotation = {
            com.landosol.toolbox.automation.session.ChannelSdkTraits.toleratesTransientRotation(selectedGameServer)
        }
        // 自动化操作的客户端跟随选中账号的服务器；切换账号即切换目标包。
        accountServerScope.launch {
            accountRepository.observeSelectedServer().collect { server ->
                selectedServer = server
                logGameClient()
            }
        }
        accountServerScope.launch { device.state.collect { state ->
            if (state is DeviceConnectionState.Ready) persistIdentity(state.device)
            else resolutionGuard.disarm(clearStatus = true)
        } }
        // The dashboard is loopback-only, so it is safe to start for test/release APKs too.
        // Starting it here also makes http://127.0.0.1:8765/ immediately reachable from a
        // browser running inside the emulator, before the first recognition session starts.
        labyrinthDebugDashboard
        autoRunScope.launch { settings.state.collect { network.setMode(it.networkMode) } }
        autoRunScope.launch {
            combine(CaptureStateRegistry.observe(), labyrinthController.uiState) { capture, state ->
                when { capture is CaptureState.Running -> PowerMode.VISUAL; state.isWorking -> PowerMode.NETWORK; else -> PowerMode.IDLE }
            }.distinctUntilChanged().collect { power.setMode(it) }
        }
        autoRunScope.launch { labyrinthController.uiState.collect { state -> (state.progress ?: state.message)?.let { log.add(it) } } }
        autoRunScope.launch { automationOverlayCoordinator.state.collect { it?.let { log.add("${it.status} ${it.detail.orEmpty()}") } } }
        autoRunScope.launch { ocr.warmUp() }
        if (startupUpdates) databaseUpdateScope.launch {
            when (val result = labyrinthCnDatabaseRepository.updateIfNeeded()) {
                is LabyrinthCnDatabaseUpdateResult.UpToDate ->
                    Log.i(DATABASE_UPDATE_LOG_TAG, "CN database is current: ${result.metadata.version}")
                is LabyrinthCnDatabaseUpdateResult.Updated ->
                    Log.i(DATABASE_UPDATE_LOG_TAG, "CN database updated: ${result.metadata.version}")
                is LabyrinthCnDatabaseUpdateResult.Failed ->
                    Log.w(DATABASE_UPDATE_LOG_TAG, result.reason)
            }
            ux.refreshDatabase()
        }
    }

    private fun logGameClient() {
        val selected = selectedServer
        Log.i(
            APP_LOG_TAG,
            "游戏客户端：${gameClientResolution}；选中账号服务器=$selected；" +
                "APP-VER=${gameClientVersionName ?: "未知"}；登录模式=" +
                when (selected) {
                    SelectedAccountServer.NoAccount -> "未选账号"
                    is SelectedAccountServer.Unknown -> "服务器未知，不登录"
                    is SelectedAccountServer.Known ->
                        if (selected.server.isChannelServer) "渠道服凭据直通（账号=uid，密码=access_key）"
                        else "B 服 SDK 登录"
                },
        )
    }

    val automationSessionManager by lazy { AutomationSessionManager() }
    val taskHost = DesktopTaskHost()
    val automationOverlayCoordinator by lazy {
        AutomationOverlayCoordinator(taskHost)
    }
    @Volatile
    private var selectedServer: SelectedAccountServer = SelectedAccountServer.NoAccount

    private val installedGamePackages get() = identityStore.current()?.packages?.keys.orEmpty()

    val gameClientResolution: GameClientResolution
        get() = GameClientResolver.resolve(selectedServer, installedGamePackages)

    val selectedGameServer: GameServer?
        get() = (gameClientResolution as? GameClientResolution.Available)?.server

    private fun channelLoginPromptAssistant(logTag: String) =
        com.landosol.toolbox.automation.session.ChannelLoginPromptAssistant(
            screen = DesktopChannelPromptScreen(
                readWindows = { runBlocking(Dispatchers.IO) { device.uiTree().map { ChannelPromptWindow(it.packageName, it.toPromptNode()) } } },
                foreground = { device.foregroundPackage },
                tap = { x, y, pkg -> actionDevice.tap(x, y, pkg) == TouchResult.Completed },
            ),
            rules = { com.landosol.toolbox.automation.session.ChannelLoginPromptRules.forServer(selectedGameServer) },
            trace = { step -> Log.i(logTag, step) },
        )

    private val gamePackageName: String?
        get() = (gameClientResolution as? GameClientResolution.Available)?.server?.packageName

    private val gameClientVersionName get() = gamePackageName?.let { identityStore.current()?.packages?.get(it)?.versionName }

    private val actionTargetPackageName: String
        get() = gamePackageName ?: NO_GAME_CLIENT_SENTINEL

    private val accessibilityActionBackend by lazy {
        DeviceActionBackend(actionDevice) { actionTargetPackageName }
    }
    val automationActionExecutor by lazy {
        SessionBoundActionExecutor(automationSessionManager, accessibilityActionBackend)
    }
    val clanBattleRecognitionSession by lazy {
        ClanBattleRecognitionSession(automationSessionManager, overlayCoordinator = automationOverlayCoordinator)
    }
    private val labyrinthRoleDecisionData by lazy {
        AndroidLabyrinthRoleDecisionDataLoader(context).load()
    }
    val labyrinthStrategySettings by lazy {
        com.landosol.toolbox.labyrinth.AndroidLabyrinthStrategySettingsStore(context)
    }
    suspend fun loadLabyrinthRoleRatings(): List<com.landosol.toolbox.labyrinth.LabyrinthRoleRatingItem> =
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            val data = labyrinthRoleDecisionData
            val document = (data as? LabyrinthRoleDecisionDataResult.Ready)?.runtime?.document
                ?: error((data as? LabyrinthRoleDecisionDataResult.Unavailable)?.reason ?: "角色资料不可用")
            val icons = try {
                assets.open("resource-packs/cn-bilibili/icons.json").bufferedReader().use {
                    kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
                        .decodeFromString<com.landosol.toolbox.gamedata.GameIconPackDocument>(it.readText())
                }
            } catch (_: Exception) { null }
            com.landosol.toolbox.labyrinth.labyrinthRoleRatingCatalog(document, icons)
        }
    val labyrinthCnDatabaseRepository by lazy {
        DesktopLabyrinthCnDatabaseRepository(context, onNotice = { log.add(it) })
    }
    private val labyrinthDebugDashboard by lazy {
        debugPort?.let { port -> LabyrinthDebugDashboardServer(
            port = port, processLogSource = DesktopLog::snapshot,
            serverSocketFactory = { java.net.ServerSocket().also { debugSocket = it } }, allowAddressReuse = false,
            frameArchiveDirectory = java.io.File(cacheDir, "labyrinth-frame-archive"),
            runtimeDiagnostics = diagnostics,
        ).also { server ->
            server.start()
        } }
    }
    val labyrinthEntryRecognitionSession: LabyrinthEntryRecognitionSession by lazy {
        // Start the debug endpoint with its initial waiting snapshot instead of waiting for the
        // first captured frame. That keeps first-frame capture failures observable in the browser.
        val debugDashboard = labyrinthDebugDashboard
        val roleDecisionRuntime = (labyrinthRoleDecisionData as? LabyrinthRoleDecisionDataResult.Ready)?.runtime
        val roleDecisionUnavailableReason =
            (labyrinthRoleDecisionData as? LabyrinthRoleDecisionDataResult.Unavailable)?.reason
        val characterAttributes = roleDecisionRuntime?.profiles
            ?.mapValues { (_, profile) -> profile.attribute }
            .orEmpty()
        LabyrinthEntryRecognitionSession(
            strategyProvider = {
                val settings = labyrinthStrategySettings.state.value
                com.landosol.toolbox.labyrinth.LabyrinthStrategySnapshot(
                    settings,
                    roleDecisionRuntime?.document?.let { document ->
                        com.landosol.toolbox.labyrinth.LabyrinthRoleDecisionDataParser.createRuntime(
                            settings.applyTo(document), settings,
                        )
                    },
                )
            },
            rerollRequester = { accountId ->
                // A batch-owned run does not reroll from inside the run: the batch controller
                // receives FailedMaxRetry and performs the reroll itself.
                if (labyrinthBatchController.state.value?.stage ==
                    com.landosol.toolbox.labyrinth.batch.LabyrinthBatchStage.RUNNING_LABYRINTH
                ) {
                    true
                } else {
                    labyrinthController.startAfterBattleFailureReroll(accountId)
                }
            },
            runTerminalListener = { event -> labyrinthBatchController.onRunTerminal(event) },
            channelPrompts = channelLoginPromptAssistant("LabyrinthEntry"),
            sessionManager = automationSessionManager,
            processorFactory = { finalBossOnly ->
                AndroidLabyrinthEntryFrameProcessor.create(
                    context = context,
                    characterAttributes = characterAttributes,
                    finalBossOnly = finalBossOnly,
                    skipJoinedCharacters = { labyrinthEntryRecognitionSession.skipRoleRewardJoinedRecognition },
                    nodeSearchHint = { labyrinthEntryRecognitionSession.currentNodeSearchHint() },
                    nodeScanRequested = { labyrinthEntryRecognitionSession.nodeScanRequested() },
                    nodeScanConsumable = { labyrinthEntryRecognitionSession.nodeScanConsumable() },
                    rosterCharacterIds = { labyrinthEntryRecognitionSession.rosterCharacterIds() },
                )::process
            },
            captureStop = { captureService.stop() },
            overlayCoordinator = automationOverlayCoordinator,
            actionExecutor = automationActionExecutor,
            actionsAvailable = ::actionsAvailable,
            actionTargetReady = {
                val target = gamePackageName
                target != null && device.foregroundPackage == target
            },
            actionPlannerFactory = {
                LabyrinthEntryActionPlanner(
                    LabyrinthEntryActionPlannerConfig(
                        manualCharacterSelection = false,
                        requireConfiguredOpeningRoster = true,
                    ),
                )
            },
            roleRewardChoicePlanner = roleDecisionRuntime?.rewardChoicePlanner,
            roleRewardChoiceUnavailableReason = roleDecisionUnavailableReason,
            eventChoicePlanner = roleDecisionRuntime?.eventChoicePlanner,
            battleTeamRecommendationPlanner = roleDecisionRuntime?.battleTeamRecommendationPlanner,
            battleTeamRecommendationUnavailableReason = roleDecisionUnavailableReason,
            battleTeamSelectionPlanner = roleDecisionRuntime?.battleTeamSelectionPlanner,
            runStateStore = labyrinthRunStateStore,
            routeLoader = { accountId ->
                accountId?.let { labyrinthRouteStore.loadLatest(it) }
            },
            routeProgressSaver = { accountId, enterId, currentBlockId ->
                labyrinthRouteStore.updateCurrentBlock(accountId, enterId, currentBlockId)
            },
            routeExecutionGate = ::validateLabyrinthExecutionForAccount,
            nodeTemplateLoader = { AndroidLabyrinthNodeTemplateLoader(context).load() },
            debugFramePublisher = debugDashboard?.let { dashboard ->
                { frame, state, result, presentation ->
                    dashboard.publish(
                        bitmap = frame.bitmap,
                        state = state,
                        result = result,
                        presentation = presentation,
                        nowMillis = frame.timestampMillis,
                    )
                }
            },
            actionScope = recognitionScope,
            gameLauncher = gameLauncher@{
                val target = gamePackageName ?: return@gameLauncher false
                // 目标游戏包已在前台时绝不重发启动 Intent：
                // 重发会拉起 SplashActivity → PCR 自身弹出 PermissionActivity →
                // MainActivity 被 stop、Surface 消失 → 黑屏。
                val foregroundActivity = device.foregroundPackage
                val decision = GameClientLaunchGate.decide(
                    targetPackage = target,
                    foregroundPackage = device.foregroundPackage,
                )
                Log.i(APP_LOG_TAG, "游戏启动决策：$decision 前台Activity=$foregroundActivity")
                GameClientLaunchGate.execute(decision) {
                    runBlocking(Dispatchers.IO) { actionDevice.launch(target) }
                }
            },
        )
    }

    val database: AppDatabase by lazy { openedDatabase ?: AppDatabase.create(context) }
    val labyrinthRunStateStore by lazy { RoomLabyrinthRunStateStore(database) }
    val labyrinthRouteStore by lazy { RoomLabyrinthRouteStore(database) }
    private val credentialStore by lazy { AndroidKeystoreCredentialStore(context) }
    private val sessionStore by lazy { AndroidKeystoreSdkSessionStore(context) }
    val gameSessionRegistry by lazy { InMemoryGameSessionRegistry() }
    private var sdkCache: Triple<DeviceIdentitySnapshot, BilibiliSdkGateway, BilibiliLoginCoordinator>? = null
    @Synchronized private fun sdkPair(): Pair<BilibiliSdkGateway, BilibiliLoginCoordinator> {
        val identity = DesktopBindings.identity()
        val cached = sdkCache
        if (cached != null && cached.first == identity) return cached.second to cached.third
        val gateway = BilibiliSdkGatewayFactory.create(context)
        val coordinator = BilibiliLoginCoordinator(gateway, sessionStore)
        sdkCache = Triple(identity, gateway, coordinator)
        return gateway to coordinator
    }
    val bilibiliNativeLoginCoordinator: BilibiliNativeLoginCoordinator by lazy {
        BilibiliNativeLoginCoordinator(
            sdkCoordinatorProvider = { sdkPair().second },
            sdkGatewayProvider = { sdkPair().first },
            sessionStore = sessionStore,
            gameGatewayFor = ::gameGatewayFor,
            gameSessionRegistry = gameSessionRegistry,
        )
    }

    private val gameGateways = ConcurrentHashMap<Pair<GameServer, DeviceIdentitySnapshot>, BilibiliGameGateway>()

    /** Worker control installs its account lease guard before accepting commands. */
    @Volatile
    var authenticatedAccountGuard: ((GameServer, Long) -> Unit)? = null

    private fun gameGatewayFor(server: GameServer): BilibiliGameGateway =
        gameGateways.computeIfAbsent(server to DesktopBindings.identity()) {
            check(server.packageName in installedGamePackages) {
                "未安装${server.displayName}客户端（${server.packageName}），无法登录该账号"
            }
            val delegate = BilibiliGameGatewayFactory.create(
                context,
                server.packageName,
                if (server.isChannelServer) {
                    BilibiliGameGatewayFactory.ChannelEndpoint.CHANNEL_UO
                } else {
                    BilibiliGameGatewayFactory.ChannelEndpoint.BILIBILI
                },
            )
            object : BilibiliGameGateway {
                override suspend fun loginAndLoadProfile(
                    sdkSession: com.landosol.toolbox.protocol.bilibili.SdkSession,
                    deviceSeed: String,
                    captcha: com.landosol.toolbox.protocol.bilibili.CaptchaSolution?,
                ): com.landosol.toolbox.protocol.bilibili.GameLoginResult {
                    val result = delegate.loginAndLoadProfile(sdkSession, deviceSeed, captcha)
                    if (result !is com.landosol.toolbox.protocol.bilibili.GameLoginResult.Success) return result
                    val guard = authenticatedAccountGuard ?: return result
                    val uid = result.profile.viewerId
                    guard(server, uid)
                    val session = object : com.landosol.toolbox.protocol.bilibili.BilibiliGameSession {
                        override val profile = result.session.profile
                        override suspend fun request(path: String, fields: LinkedHashMap<String, Any?>):
                            com.landosol.toolbox.protocol.bilibili.GameSessionResult {
                            authenticatedAccountGuard?.invoke(server, uid)
                            return result.session.request(path, fields)
                        }
                    }
                    return result.copy(session = session)
                }
            }
        }
    val accountRepository: AccountRepository by lazy {
        AccountRepository(database, credentialStore, sessionStore, gameSessionRegistry)
    }
    val labyrinthController by lazy {
        com.landosol.toolbox.labyrinth.LabyrinthController(
            accountRepository, gameSessionRegistry, database, { bilibiliNativeLoginCoordinator },
            com.landosol.toolbox.labyrinth.AndroidLabyrinthRerollSettingsStore(context),
            launchForeground = ::startRerollHost,
            scope = mainScope,
            beforeRerollLogin = { accountId -> withContext(Dispatchers.IO) { hardCloseChannelClientBeforeReroll(accountId) } },
            afterStandaloneReroll = { accountId -> coldStartChannelClientAfterReroll(accountId) },
        )
    }

    private val gameClientHardCloseLedger = com.landosol.toolbox.automation.session.GameClientHardCloseLedger()
    // Retained through reusable-opening validation and its stale-opening reroll fallback;
    // consumed only when the first client synchronization begins, never on later batch cycles.
    @Volatile private var batchPreparedClient: Pair<Long, String>? = null

    private val gameClientHardCloser by lazy {
        com.landosol.toolbox.automation.session.GameClientHardCloser(
            foregroundPackage = { device.foregroundPackage },
            pressHome = { actionDevice.home() },
            killBackgroundProcesses = { packageName -> check(runBlocking(Dispatchers.IO) { actionDevice.forceStop(packageName) }) { "无法强制停止客户端" } },
            canKillOtherApps = true,
            trace = { step -> Log.i(CLIENT_LOG_TAG, step) },
        )
    }

    private suspend fun hardCloseChannelClientBeforeReroll(accountId: Long): String? {
        val isBatch = labyrinthBatchController.state.value?.stage in ACTIVE_BATCH_STAGES
        val closesClient = selectedGameServer?.let(com.landosol.toolbox.automation.session.ChannelSdkTraits::closesClientBeforeReroll) == true
        if (isBatch && batchPreparedClient == (accountId to gamePackageName)) {
            return resolutionGuard.beforeClientReset()
        }
        if (isBatch) {
            val rejection = if (closesClient) resolutionGuard.beforeClientReset() else resolutionGuard.actionRejection()
            rejection?.let { return it }
        }
        else if (closesClient) {
            try { resolutionGuard.checkForStart() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { return failure.message ?: "截图尺寸检查失败，请到设备页重新检查" }
        }
        // Whatever an earlier reroll left behind is void from here on, whichever channel this is.
        gameClientHardCloseLedger.clear()
        val server = selectedGameServer
            ?.takeIf(com.landosol.toolbox.automation.session.ChannelSdkTraits::closesClientBeforeReroll)
            ?: return null
        if (!actionsAvailable()) {
            return "${server.displayName}刷开局前需要先大退游戏，但设备未连接或触控不可用"
        }
        return when (val result = gameClientHardCloser.close(server.packageName)) {
            com.landosol.toolbox.automation.session.GameClientHardCloseResult.KillRequested -> {
                // Only a request: the cold start that follows must still prove the process died.
                gameClientHardCloseLedger.issue(accountId, server.packageName)
                Log.i(CLIENT_LOG_TAG, "刷开局前已请求大退 ${server.packageName}（账号 $accountId）")
                null
            }
            is com.landosol.toolbox.automation.session.GameClientHardCloseResult.Failed ->
                "${server.displayName}刷开局前大退游戏失败：${result.reason}"
        }
    }

    private fun coldStartChannelClientAfterReroll(accountId: Long) {
        val target = gamePackageName
        if (!consumeGameClientHardClosed(accountId, target) || target == null) return
        autoRunScope.launch {
            if (actionDevice.launch(target)) log.add("刷开局结束，已启动游戏") else log.add("刷开局结束后启动游戏失败", true)
        }
    }

    private fun consumeGameClientHardClosed(accountId: Long?, packageName: String?): Boolean {
        batchPreparedClient = null
        val matched = gameClientHardCloseLedger.consume(accountId, packageName)
        Log.i(CLIENT_LOG_TAG, "大退凭证 账号=$accountId 包=$packageName 有效=$matched")
        return matched
    }

    private suspend fun validateLabyrinthExecutionForAccount(
        accountId: Long?,
    ): LabyrinthExecutionGateResult {
        val id = accountId
            ?: return LabyrinthExecutionGateResult.Blocked("未选择账号，已禁止执行保存路线")
        val route = labyrinthRouteStore.loadLatest(id)
        val checkpoint = com.landosol.toolbox.labyrinth.RoomLabyrinthRerollCheckpointStore(database).load(id)
        // Batch runs always prepare a fresh opening and synchronize the client first. The route
        // gate only needs to verify that the persisted route/checkpoint pair is internally sound.
        return validateLabyrinthExecution(route, checkpoint, top = null)
    }

    val gameSessionResetWorkflow by lazy {
        val frameTracker = SessionExpiryFrameTracker()
        val presence = AccessibilityForegroundPresenceObserver(
            gamePackageName = { actionTargetPackageName },
            foregroundPackage = { device.foregroundPackage },
        )
        // After a server-side reroll the client still holds the previous run's local state.
        // Tapping 进入黎明界 there opens the unrecognised guild-selection page and never
        // triggers the expiry popup; the bottom 主页 tab requests home data immediately and
        // is rejected by the server, which is the popup we want (labyrinth-full-automation §5.1).
        val terminator = com.landosol.toolbox.automation.session.AnchorTriggerSessionExpiryTerminator(
            onTap = { point ->
                when (accessibilityActionBackend.execute(AutomationAction.Tap(point))) {
                    com.landosol.toolbox.automation.AutomationBackendResult.Completed -> true
                    else -> false
                }
            },
            triggerPoint = { frameTracker.sessionInvalidationTrigger },
            returnTitlePoint = SESSION_RETURN_TITLE_POINT,
            popupVisible = { frameTracker.popupVisible },
            titleReached = { frameTracker.titleReached },
            synchronizedWithoutExpiry = { frameTracker.clientSynchronizedWithoutExpiry },
            frameSize = { frameTracker.frameSize },
            available = ::actionsAvailable,
            returnTitleAnchorPoint = { frameTracker.anchorCenter(EntryAnchorId.SESSION_RETURN_TITLE) },
            trace = { step -> android.util.Log.d("LabyrinthReset", "trigger-terminator $step") },
        )
        val resetDashboard = labyrinthDebugDashboard
        GameSessionResetWorkflow(
            actionScope = resetScope,
            sessionManager = automationSessionManager,
            backend = CompositeSessionResetBackend(
                terminator = terminator,
                relauncher = Relauncher {
                    val target = gamePackageName
                        ?: return@Relauncher GameClientRelaunchResult.LAUNCH_UNAVAILABLE
                    if (actionDevice.launch(target)) GameClientRelaunchResult.LAUNCH_REQUESTED else GameClientRelaunchResult.LAUNCH_UNAVAILABLE
                },
            ),
            captureActive = CaptureStateRegistry::isActive,
            processorFactory = {
                // The reset only needs page/dialog anchors. 2026-09-18 live: with the default
                // map-node scan on, one map frame behind the expiry popup took 6.9 s, the frame
                // tracker saw nothing for 10 s and the terminator timed out on a visible popup.
                val processor = AndroidLabyrinthEntryFrameProcessor.create(context, nodeScanRequested = { false })
                val processFrame: (CapturedFrame) -> LabyrinthEntryFrameResult = { frame ->
                    frameTracker.trackFrame(frame.bitmap.width, frame.bitmap.height)
                    processor.process(frame).also(frameTracker::record)
                }
                processFrame
            },
            blockClassifier = { result ->
                SessionBlockClassifier().classify(
                    scores = result.observation.anchorScores.toSessionBlockScores(),
                    pageTitleScore = result.observation.stateScores[LabyrinthEntryPageState.TITLE_WAITING_TAP] ?: 0.0,
                )
            },
            overlayCoordinator = automationOverlayCoordinator,
            presence = presence,
            actionExecutor = automationActionExecutor,
            entryActionPlannerFactory = { LabyrinthEntryActionPlanner() },
            channelPrompts = channelLoginPromptAssistant("LabyrinthReset"),
            debugFramePublisher = resetDashboard?.let { dashboard ->
                { frame, resetState, result, presentation ->
                    // The dashboard is keyed to the labyrinth session state; during a reset the
                    // session is stopped, so hand it a synthetic snapshot that carries the reset
                    // stage in the message and the real frame/page for the picture.
                    dashboard.publish(
                        bitmap = frame.bitmap,
                        state = com.landosol.toolbox.labyrinth.LabyrinthEntryRecognitionSessionState(
                            status = com.landosol.toolbox.labyrinth.LabyrinthEntryRecognitionStatus.RUNNING,
                            dryRun = false,
                            frameCount = resetState.frameCount,
                            actionCount = resetState.actionCount,
                            message = "会话重置 ${resetState.stage.name}：${resetState.message ?: ""}",
                        ),
                        result = result,
                        presentation = presentation,
                        nowMillis = frame.timestampMillis,
                    )
                }
            },
        )
    }

    val labyrinthBatchCheckpointStore by lazy {
        com.landosol.toolbox.labyrinth.batch.AndroidLabyrinthBatchCheckpointStore(context)
    }

    val labyrinthBatchController by lazy {
        com.landosol.toolbox.labyrinth.batch.LabyrinthBatchController(
            ports = object : com.landosol.toolbox.labyrinth.batch.LabyrinthBatchPorts {
                override suspend fun reroll(accountId: Long, guildId: Int, difficulty: Int) =
                    labyrinthController.rerollForBatch(accountId, guildId, difficulty)

                override suspend fun verifyReusableOpening(
                    accountId: Long,
                    opening: com.landosol.toolbox.labyrinth.batch.LabyrinthBatchReusableOpening,
                ) = labyrinthController.verifyReusableOpeningForBatch(accountId, opening)

                override suspend fun invalidateClientSessionAndReturn(accountId: Long):
                    com.landosol.toolbox.labyrinth.batch.LabyrinthBatchInvalidationResult {
                    // AnchorTriggerSessionExpiryTerminator taps the recognised trigger, expects the
                    // "session expired" popup, taps 返回标题, then the entry planner navigates
                    // title → home → adventure → labyrinth home. READY means the stale local
                    // state is gone and the new server entry is what the client sees.
                    // Spent before anything can fail, so it never outlives this sync.
                    val hardClosed = consumeGameClientHardClosed(accountId, gamePackageName)
                    if (!CaptureStateRegistry.isActive()) {
                        return com.landosol.toolbox.labyrinth.batch.LabyrinthBatchInvalidationResult.Failure(
                            "屏幕捕获已停止" + (CaptureStateRegistry.lastStopReason()?.let { "（$it）" } ?: ""),
                        )
                    }
                    return when (val started = gameSessionResetWorkflow.start(clientHardClosed = hardClosed)) {
                        is GameSessionResetStartResult.Started -> {
                            val final = gameSessionResetWorkflow.state.first { !it.running }
                            val ok = final.status == GameSessionResetStatus.STOPPED &&
                                final.lastBlock == SessionBlockKind.NONE
                            if (ok) {
                                com.landosol.toolbox.labyrinth.batch.LabyrinthBatchInvalidationResult.Success(
                                    if (hardClosed) "hard-close-cold-start" else "home-tab-session-expiry",
                                )
                            } else {
                                com.landosol.toolbox.labyrinth.batch.LabyrinthBatchInvalidationResult.Failure(
                                    "重置流程 ${final.stage.name}/${final.status.name}" +
                                        (final.message?.let { "：$it" } ?: "") +
                                        (final.lastBlock.takeIf { it != SessionBlockKind.NONE }?.let { "，阻塞 $it" } ?: ""),
                                )
                            }
                        }
                        is GameSessionResetStartResult.Blocked ->
                            com.landosol.toolbox.labyrinth.batch.LabyrinthBatchInvalidationResult.Failure(started.reason)
                        is GameSessionResetStartResult.AlreadyRunning ->
                            com.landosol.toolbox.labyrinth.batch.LabyrinthBatchInvalidationResult.Failure("会话重置已在运行")
                    }
                }

                override suspend fun startRun(accountId: Long, guildId: Int, runId: String): Boolean =
                    labyrinthEntryRecognitionSession.startAutomation(accountId, runId) is
                        LabyrinthEntryRecognitionStartResult.Started

                override suspend fun stopRun(reason: String) {
                    labyrinthEntryRecognitionSession.stop(reason, releaseCapture = false)
                }

                override suspend fun saveCheckpoint(
                    checkpoint: com.landosol.toolbox.labyrinth.batch.LabyrinthBatchCheckpoint,
                ) = labyrinthBatchCheckpointStore.save(checkpoint)
            },
        ).also { controller ->
            // Environment loss must pause the batch without widening any action (§18).
            autoRunScope.launch {
                CaptureStateRegistry.observe().collect { state ->
                    if (state !is CaptureState.Running && controller.state.value?.stage in ACTIVE_BATCH_STAGES) {
                        controller.haltForEnvironment(
                            com.landosol.toolbox.labyrinth.batch.LabyrinthBatchHaltReason.CAPTURE_LOST,
                            "屏幕捕获已停止" + (CaptureStateRegistry.lastStopReason()?.let { "：$it" } ?: ""),
                        )
                    }
                }
            }
        }
    }

    val labyrinthAutoRunWorkflow by lazy {
        LabyrinthAutoRunWorkflow(
            startRun = { accountId ->
                labyrinthEntryRecognitionSession.startAutomation(accountId) is
                    LabyrinthEntryRecognitionStartResult.Started
            },
            awaitRunFinished = {
                labyrinthEntryRecognitionSession.state.first { it.running }
                val final = labyrinthEntryRecognitionSession.state.first { !it.running }
                if (final.routeProgress?.complete == true) {
                    LabyrinthAutoRunRoundOutcome.Completed
                } else {
                    LabyrinthAutoRunRoundOutcome.Aborted(final.message ?: "路线执行提前结束")
                }
            },
            resetSession = {
                when (gameSessionResetWorkflow.start()) {
                    is GameSessionResetStartResult.Started -> {
                        gameSessionResetWorkflow.state.first { it.running }
                        val final = gameSessionResetWorkflow.state.first { !it.running }
                        final.status == GameSessionResetStatus.STOPPED &&
                            final.lastBlock == SessionBlockKind.NONE
                    }

                    else -> false
                }
            },
        )
    }

    private val autoRunScope = CoroutineScope(SupervisorJob() + Dispatchers.Default + failures)

    @Volatile
    private var autoRunJob: Job? = null
    private val startLock = Any()
    private val startingState = MutableStateFlow(false)
    val taskStarting = startingState.asStateFlow()
    private val stoppingState = MutableStateFlow(false)
    val taskStopping = stoppingState.asStateFlow()
    private var stopJob: Job? = null

    fun startLabyrinthAutoRun(
        accountId: Long?,
        goals: List<com.landosol.toolbox.labyrinth.batch.LabyrinthBatchGoal>,
    ): Boolean = synchronized(startLock) {
        val id = accountId ?: return false
        if (goals.isEmpty() || stoppingState.value || !actionsAvailable() || ocr.status.value !is OcrEngineStatus.Ready || gameClientResolution !is GameClientResolution.Available) return false
        if (autoRunJob?.isActive == true) return false
        // The launch job ends after start() hands control to the recognition session. The batch
        // stage remains the authoritative lock for the rest of that run.
        if (labyrinthBatchController.state.value?.stage in ACTIVE_BATCH_STAGES) return false
        val ui = labyrinthController.uiState.value
        if (ui.selectedAccount?.id != id || ui.isWorking || ui.captcha != null || labyrinthEntryRecognitionSession.state.value.running) {
            return false
        }
        val frozenPackage = (gameClientResolution as GameClientResolution.Available).server.packageName
        startingState.value = true
        autoRunJob = autoRunScope.launch {
          try {
            resolutionGuard.checkForStart()
            captureService.start()
            // Only a checked startup can consume the one-shot opening or clear task state.
            resolutionGuard.actionRejection()?.let { error(it) }
            check(ui.settingsReady) { "账号配置尚未加载完成" }
            check(goals.all { goal -> com.landosol.toolbox.labyrinth.LabyrinthRerollOptions.guilds.any { it.guildId == goal.guildId } }) { "请选择有效公会" }
            check(ui.selectedDifficulty in ui.availableDifficulties) { "请选择已解锁的有效难度" }
            check((ui.maxAttempts.toIntOrNull() ?: 0) in 1..com.landosol.toolbox.labyrinth.LabyrinthRerollOptions.MAX_ATTEMPTS) { "最大尝试次数无效" }
            // The account row is authoritative; selectedServer may lag behind the selected UI account.
            val targetPackage = accountRepository.loadLoginMaterial(id).server.packageName
            resolutionGuard.beforeClientReset()?.let { error(it) }
            check(labyrinthController.uiState.value.selectedAccount?.id == id &&
                frozenPackage == targetPackage && gamePackageName == targetPackage) {
                "账号服务器与游戏客户端尚未同步，请重新启动批量任务"
            }
            val wasRunning = prepareBatchClient(actionDevice, targetPackage)
            currentCoroutineContext().ensureActive()
            resolutionGuard.beforeClientReset()?.let { error(it) }
            log.add(if (wasRunning) "批量启动前已关闭 PCR：$targetPackage" else "PCR 未运行，继续准备批量任务：$targetPackage", kind = UserLogKind.KEY)
            val reusableOpening = labyrinthController.consumeReusableOpeningForBatch(
                expectedGuildId = goals.first().guildId,
                expectedDifficulty = ui.selectedDifficulty,
            )
            val batchId = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.ROOT).format(java.util.Date())
            gameClientHardCloseLedger.clear()
            gameClientHardCloseLedger.issue(id, targetPackage)
            batchPreparedClient = id to targetPackage
            labyrinthBatchController.start(
                batchId = batchId,
                accountId = id,
                goals = goals,
                difficulty = ui.selectedDifficulty,
                reusableOpening = reusableOpening,
            )
            if (labyrinthBatchController.state.value?.stage !in ACTIVE_BATCH_STAGES) {
                resolutionGuard.disarm()
                batchPreparedClient = null
                gameClientHardCloseLedger.clear()
                captureService.stop("批量任务未进入执行状态")
            }
          } catch (cancelled: CancellationException) {
              if (batchPreparedClient != null) gameClientHardCloseLedger.clear()
              resolutionGuard.disarm(); batchPreparedClient = null
              throw cancelled
          }
          catch (failure: Exception) {
              if (batchPreparedClient != null) gameClientHardCloseLedger.clear()
              batchPreparedClient = null
              resolutionGuard.disarm()
              val reason = failure.message ?: "自动执行启动失败"
              log.add(reason, true); labyrinthController.reportMessage(reason); captureService.stop()
          }
          finally { startingState.value = false }
        }
        return true
    }

    fun stopLabyrinthAutoRun() {
        stopAll("用户停止自动执行")
    }

    fun actionsAvailable() = (device.state.value as? DeviceConnectionState.Ready)?.device?.let { it.touchReady && it.awake } == true
    val busy: Boolean get() = startingState.value || stoppingState.value || autoRunJob?.isActive == true || accounts.uiState.value.isWorking || labyrinthBatchController.state.value?.stage in ACTIVE_BATCH_STAGES || labyrinthController.uiState.value.isWorking || labyrinthController.uiState.value.captcha != null || labyrinthEntryRecognitionSession.state.value.running || gameSessionResetWorkflow.state.value.running

    suspend fun connectDevice(serial: String, reconnect: Boolean = false) {
        check(!busy) { "请先停止当前任务，再切换设备" }
        deviceConnection.connect(serial.trim(), if (settings.state.value.touchMode == PreferredTouchMode.COMPATIBILITY) TouchMode.Compatibility else TouchMode.MaaTouch,
            reconnect = reconnect, beforeConnect = { resolutionGuard.disarm(clearStatus = true); captureService.stopAndJoin("切换设备") }, onReady = { ready ->
                persistIdentity(ready)
                settings.update { it.copy(lastDevice = ready.serial) }
                log.add("设备已就绪：${ready.label}",kind=UserLogKind.KEY)
            })
        if (device.state.value is DeviceConnectionState.Ready) {
            try { recheckResolution() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { log.add("连接成功，实际截图尺寸待检查：${failure.message}", true) }
        }
    }

    suspend fun checkAdb(): AdbLocation = (device as? ManagedDeviceService)?.prepare()
        ?: checkNotNull(AdbLocator.find(settings.state.value.adbPath, dataDirectory.resolve("tools/platform-tools"))) { "未找到有效 ADB，请检查路径" }

    @Synchronized private fun persistIdentity(ready: DeviceReady) {
        val identity = ready.identity
        val snapshot = DeviceIdentitySnapshot(identity.deviceKey, identity.brand, identity.model, identity.release, identity.sdkInt,
            identity.supportedAbis, identity.androidId, identity.displayWidth, identity.displayHeight,
            identity.packages.mapValues { (_, item) -> GamePackageIdentity(item.versionName, item.versionCode, item.certificateMd5, item.lastUpdateTime) }, identity.capturedAtMillis)
        if (identityStore.current() != snapshot) {
            identityStore.save(snapshot)
            gameGateways.clear(); sdkCache = null
        }
    }

    suspend fun disconnectDevice() { check(!busy) { "请先停止任务" }; resolutionGuard.disarm(clearStatus = true); deviceConnection.disconnect { captureService.stopAndJoin("断开设备") }; previewState.value = null }
    suspend fun recheckResolution(): VisualResolutionStatus {
        check(!busy) { "请先停止任务，再重新检查画面尺寸" }
        return resolutionGuard.recheck().also { log.add(it.message, !it.supported) }
    }
    suspend fun startPreview() { captureService.start() }
    fun startRecognition(dryRun: Boolean): Job = synchronized(startLock) {
        if (busy) { log.add("请先停止当前任务", true); return Job().apply { complete() } }
        startingState.value = true
        autoRunScope.launch {
        try {
            check(device.state.value is DeviceConnectionState.Ready) { "请先在设备页连接模拟器" }
            check((device.state.value as? DeviceConnectionState.Ready)?.device?.awake == true) { "模拟器屏幕正在休眠，请先手动唤醒，再启动任务" }
            if (!dryRun) resolutionGuard.checkForStart()
            (device.state.value as? DeviceConnectionState.Ready)?.device?.let(::persistIdentity)
            check(gameClientResolution is GameClientResolution.Available) { "无法确定游戏客户端，请检查账号服务器与模拟器中安装的客户端" }
            check(ocr.status.value is OcrEngineStatus.Ready) { "OCR 尚未就绪，请查看设置与环境" }
            check(dryRun || actionsAvailable()) { "触控服务未就绪，请在设备页重新连接" }
            captureService.start()
            when (val result = labyrinthEntryRecognitionSession.start(dryRun, labyrinthController.uiState.value.selectedAccount?.id)) {
                is LabyrinthEntryRecognitionStartResult.Blocked -> { resolutionGuard.disarm(); log.add(result.reason, true); captureService.stop() }
                else -> Unit
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { resolutionGuard.disarm(); val reason = failure.message ?: "启动失败"; log.add(reason, true); labyrinthController.reportMessage(reason) }
        finally { startingState.value = false }
        }.also { autoRunJob = it }
    }
    fun pauseTask() { automationOverlayCoordinator.state.value?.let { current -> autoRunScope.launch { automationOverlayCoordinator.setPaused(current.sessionId, !current.paused) } } }
    fun stopAll(reason: String = "用户停止任务"): Job = synchronized(startLock) {
        batchPreparedClient = null
        resolutionGuard.disarm()
        stopJob?.takeIf { it.isActive }?.let { return it }
        stoppingState.value = true
        log.add(reason,kind=UserLogKind.KEY)
        labyrinthController.stopWithReason(reason)
        gameClientHardCloseLedger.clear()
        val starting = autoRunJob
        starting?.cancel()
        autoRunScope.launch {
            try {
                starting?.join()
                labyrinthBatchController.stop(reason)
                gameSessionResetWorkflow.stop(reason)
                labyrinthEntryRecognitionSession.stop(reason)
                labyrinthController.uiState.first { !it.isWorking && it.captcha == null }
            } finally {
                withContext(NonCancellable) { captureService.stopAndJoin(reason) }
                autoRunJob = null
                startingState.value = false
                stoppingState.value = false
            }
        }.also { stopJob = it }
    }
    private fun startRerollHost() {
        val accepted = labyrinthController.startFromService()
        if (rerollHostJob?.isActive == true || (!accepted && !labyrinthController.hasActiveReroll)) return
        rerollHostJob = autoRunScope.launch {
            yield()
            val deadline = android.os.SystemClock.elapsedRealtime() + (5 * 60 + 55) * 60_000L
            while (labyrinthController.hasActiveReroll) {
                log.add(labyrinthRerollStatusText(labyrinthController.uiState.value))
                if (android.os.SystemClock.elapsedRealtime() >= deadline) {
                    labyrinthController.stopWithReason("后台运行达到系统/安全时限，已停止；返回应用读取现有开局后继续"); break
                }
                delay(2_000)
            }
        }
    }

    suspend fun shutdown() {
        if (closed) return
        closed = true
        try {
            val stopped = withTimeoutOrNull(10_000) {
                stopAll("应用退出").join()
                true
            }
            if (stopped != true) Log.w(APP_LOG_TAG, "等待任务停止超过 10 秒，继续释放桌面资源")
        } finally {
            withContext(NonCancellable) {
                deviceConnection.closeAndJoin()
                ux.closeAndJoin()
                withContext(Dispatchers.Main) { viewModels.clear() }
                databaseUpdateScope.cancel(); accountServerScope.cancel(); autoRunScope.cancel(); mainScope.cancel(); recognitionScope.cancel(); resetScope.cancel()
                listOf<() -> Unit>({ captureService.close() }, { device.close() }, { ocr.close() }, { captchaHost.close() },
                    { debugSocket?.close(); Unit }, { diagnostics.close() }, { database.close() }, { power.close() }, { network.close() })
                    .forEach { release -> runCatching(release).onFailure { Log.e(APP_LOG_TAG, "释放桌面资源失败", it) } }
                DesktopBindings.release(installedServices)
                previewState.value = null
            }
        }
    }
    override fun close() { runBlocking { shutdown() } }

    private fun UiNode.toPromptNode(): ChannelPromptNode = ChannelPromptNode(text, description, clickable,
        ChannelPromptBounds(bounds.left, bounds.top, bounds.right, bounds.bottom), children.map { it.toPromptNode() })

    init { start(); ux.start() }

    private companion object {
        val ACTIVE_BATCH_STAGES = setOf(
            com.landosol.toolbox.labyrinth.batch.LabyrinthBatchStage.REROLLING,
            com.landosol.toolbox.labyrinth.batch.LabyrinthBatchStage.INVALIDATING_OLD_CLIENT_SESSION,
            com.landosol.toolbox.labyrinth.batch.LabyrinthBatchStage.RUNNING_LABYRINTH,
            com.landosol.toolbox.labyrinth.batch.LabyrinthBatchStage.RECORDING_RESULT,
        )
        const val APP_LOG_TAG = "LandosolToolbox"
        const val CLIENT_LOG_TAG = "LandosolClient"
        const val DATABASE_UPDATE_LOG_TAG = "LabyrinthCnDatabase"

            const val NO_GAME_CLIENT_SENTINEL = "com.landosol.toolbox.no-game-client"
            @Suppress("unused")
        val SESSION_EXPIRY_TRIGGER_POINT = ScreenPoint(1735f, 805f)
            val SESSION_RETURN_TITLE_POINT = ScreenPoint(961f, 739f)
    }
}
