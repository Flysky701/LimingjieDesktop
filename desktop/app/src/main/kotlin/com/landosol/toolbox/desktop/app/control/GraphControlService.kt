package com.landosol.toolbox.desktop.app.control

import com.landosol.toolbox.account.*
import com.landosol.toolbox.desktop.app.DesktopAppGraph
import com.landosol.toolbox.desktop.app.adapters.storage.DesktopGoal
import com.landosol.toolbox.desktop.app.adapters.storage.DesktopPreferences
import com.landosol.toolbox.desktop.app.ui.DesktopTask
import com.landosol.toolbox.desktop.device.*
import com.landosol.toolbox.desktop.platform.*
import com.landosol.toolbox.desktop.shared.AtomicFiles
import com.landosol.toolbox.desktop.shared.CaptchaSession
import com.landosol.toolbox.labyrinth.*
import com.landosol.toolbox.labyrinth.batch.*
import com.landosol.toolbox.protocol.bilibili.GameServer
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.awt.Desktop
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.StandardOpenOption.READ
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.util.Base64
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.imageio.ImageIO

/** Owns one existing graph in its worker JVM. This class performs no transport or tab routing. */
class GraphControlService(
    private val graph: DesktopAppGraph,
    private val profileId: String,
    private val profileName: String,
    private val leases: UserResourceLeases = UserResourceLeases(),
    private val accountAdmissionDirectory: Path = UserRuntimeDirectory.resolve(),
) : WorkerDomain {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val commands = Mutex()
    private val epoch = AtomicLong()
    private val revoked = AtomicBoolean()
    private val closing = AtomicBoolean()
    private val stopping = AtomicBoolean()
    private val admission = Any()
    private var commandActive = false
    private var activeCommand: Deferred<JsonObject>? = null
    private var accountLease: ResourceLease? = null
    private var accountAdmission: AccountTaskAdmission? = null
    private var leasedIdentity: Pair<String, Long>? = null
    private val authenticatedAccounts = mutableMapOf<Long, Pair<String, Long>>()
    private val bindingFile = graph.dataDirectory.resolve("settings/instance-binding.json")
    @Volatile private var binding = readBinding()
    private var captchaKey: AccountCaptchaState? = null
    private var captchaSession: CaptchaSession? = null
    private var captchaTicket: String? = null
    private var previewTimestamp: Long? = null
    private var previewPng: String? = null

    init {
        graph.authenticatedAccountGuard = ::guardAuthenticatedAccount
        // Upstream view models stop observing their repositories without a subscriber.
        scope.launch { graph.accounts.uiState.collect {} }
        scope.launch { graph.labyrinthController.uiState.collect {} }
    }

    override suspend fun snapshot(includePreview: Boolean): JsonObject = withContext(Dispatchers.Main) {
        releaseAccountIfIdle()
        refreshCaptcha()
        val accounts = graph.accounts.uiState.value
        val entry = graph.labyrinthEntryRecognitionSession.state.value
        val console = graph.ux.console.value.copy(task = graph.labyrinthController.uiState.value,
            entry = entry.copy(lastResult = entry.lastResult?.copy(nodeViewportPixels = null)), batch = graph.labyrinthBatchController.state.value,
            halt = graph.labyrinthBatchController.haltReason.value, overlay = graph.automationOverlayCoordinator.state.value,
            device = graph.device.state.value, busy = graph.busy || stopping.get(),
            starting = graph.taskStarting.value, stopping = graph.taskStopping.value || stopping.get())
        val crashes = withContext(Dispatchers.IO) { crashNames() }
        val preview = if (includePreview) previewDocument() else JsonNull
        buildJsonObject {
            put("version", ControlSnapshot.VERSION)
            put("profileId", profileId)
            put("title", profileName)
            put("console", model(console))
            put("settings", model(graph.settings.state.value))
            put("preferences", model(graph.ux.preferences.state.value))
            put("accounts", model(accounts.copy(editor = accounts.editor?.copy(password = ""))))
            put("strategy", model(graph.labyrinthStrategySettings.state.value))
            put("catalog", model(graph.ux.catalog.value))
            put("resolution", model(graph.resolutionGuard.status.value))
            put("ocr", model(graph.ocr.status.value))
            put("credentials", model(graph.ux.credentials.value))
            put("identity", model(graph.identityStore.current()))
            put("gameClient", model(graph.gameClientResolution))
            put("database", graph.ux.database.value)
            put("foreground", graph.device.foregroundPackage)
            put("connectionActivity", graph.deviceConnection.activity.value)
            put("connectionMessage", graph.deviceConnection.message.value)
            put("devices", model(graph.deviceConnection.devices.value))
            put("history", model(graph.ux.history.records.value.takeLast(500)))
            put("historyTotal", graph.ux.history.records.value.size)
            put("logs", model(graph.log.lines.value.takeLast(400)))
            put("logTotal", graph.log.lines.value.size)
            put("dataDirectory", graph.dataDirectory.toString())
            put("debugUrl", graph.debugUrl)
            put("crashes", model(crashes))
            put("preview", preview)
            put("notices", model(graph.notices.value))
            put("binding", binding)
            put("accountVerified", synchronized(admission) { console.task.selectedAccount?.id in authenticatedAccounts })
            put("accountReminder", "助手绑定只选择任务凭据，不会切换 PCR 登录账号；请人工确认此模拟器登录的是同一账号。")
            put("captcha", captchaDocument())
        }
    }

    override suspend fun execute(op: String, args: JsonObject): JsonObject {
        if (op == ControlOps.STOP) return stop(args.string("reason") ?: "用户停止任务")
        if (op == ControlOps.DEVICE_CANCEL) {
            epoch.incrementAndGet()
            graph.deviceConnection.cancel()
            return accepted()
        }
        val ticket = epoch.get()
        return commands.withLock {
            val work = scope.async(Dispatchers.Main, start = CoroutineStart.LAZY) { dispatch(op, args) }
            synchronized(admission) {
                check(!revoked.get() && !closing.get()) { "控制连接已结束，不能启动新操作" }
                check(!stopping.get() && ticket == epoch.get()) { "操作已被停止，请重新操作" }
                commandActive = true
                activeCommand = work
            }
            try {
                work.await()
            } catch (cancelled: CancellationException) {
                currentCoroutineContext().ensureActive()
                error("操作已停止")
            } finally {
                withContext(NonCancellable) { work.cancelAndJoin() }
                synchronized(admission) { commandActive = false; activeCommand = null }
                releaseAccountIfIdle()
            }
        }
    }

    private suspend fun dispatch(op: String, args: JsonObject): JsonObject {
        when (op) {
            ControlOps.START -> startTask(args)
            ControlOps.PAUSE -> graph.pauseTask()
            ControlOps.DISMISS_MESSAGE -> graph.labyrinthController.dismissMessage()
            ControlOps.SAVE_REROLL -> saveReroll(decode(args.required("value")))
            ControlOps.SAVE_SETUP -> {
                requireIdle()
                val settings: LabyrinthRerollSettings = decode(args.required("settings"))
                val goals: List<DesktopGoal> = decode(args.required("goals"))
                validateGoals(goals)
                settings.validationError()?.let { error(it) }
                saveReroll(settings)
                withContext(Dispatchers.IO) { graph.ux.preferences.update { it.copy(goals = goals) } }
            }
            ControlOps.PREFS_PATCH -> withContext(Dispatchers.IO) {
                val value: DesktopPreferences = decode(patch(model(graph.ux.preferences.state.value), args))
                validateGoals(value.goals)
                graph.ux.preferences.update { value }
            }
            ControlOps.SETTINGS_PATCH -> {
                if (args.keys.any { it in setOf("adbPath", "lastDevice", "touchMode") }) requireIdle()
                check("adbPath" !in args && "lastDevice" !in args) { "请通过设备连接或 ADB 配置操作修改设备绑定" }
                val value: DesktopSettings = decode(patch(model(graph.settings.state.value), args))
                withContext(Dispatchers.IO) { graph.settings.save(value) }
            }
            ControlOps.ACCOUNT_NEW -> { requireIdle(); graph.accounts.openNewAccount() }
            ControlOps.ACCOUNT_EDIT -> {
                requireIdle()
                val id = args.long("id")
                accountOperation({ graph.accounts.openEditAccount(id) }) { it.editor?.id == id }
                return buildJsonObject { put("editor", model(graph.accounts.uiState.value.editor?.copy(password = ""))) }
            }
            ControlOps.ACCOUNT_SAVE -> saveAccount(decode(args.required("editor")))
            ControlOps.ACCOUNT_SELECT -> {
                requireIdle()
                val id = args.long("id")
                accountOperation({ graph.accounts.selectAccount(id) }) { it.accounts.any { row -> row.id == id && row.isSelected } }
                withTimeout(10_000) { graph.labyrinthController.uiState.first { it.selectedAccount?.id == id && it.settingsReady } }
                releaseAccount()
                saveBinding(binding + ("accountId" to JsonPrimitive(id)))
            }
            ControlOps.ACCOUNT_DELETE -> {
                requireIdle()
                val id = args.long("id")
                val row = graph.accounts.uiState.value.accounts.firstOrNull { it.id == id } ?: error("账号不存在")
                graph.accounts.requestDelete(row)
                accountOperation({ graph.accounts.confirmDelete() }) { it.accounts.none { row -> row.id == id } }
                synchronized(admission) { authenticatedAccounts.remove(id) }
                if (binding["accountId"]?.jsonPrimitive?.longOrNull == id) {
                    releaseAccount(); saveBinding(binding - "accountId")
                }
            }
            ControlOps.ACCOUNT_DISMISS -> {
                graph.accounts.dismissEditor(); graph.accounts.dismissDelete(); graph.accounts.dismissMessage()
            }
            ControlOps.STRATEGY_SAVE -> graph.labyrinthStrategySettings.save(decode(args.required("value")))
            ControlOps.CATALOG -> return buildJsonObject { put("items", model(graph.loadLabyrinthRoleRatings())) }
            ControlOps.DEVICE_DISCOVER -> { requireIdle(); graph.deviceConnection.discover(args.boolean("force", true)) }
            ControlOps.DEVICE_CONNECT -> {
                requireIdle()
                graph.connectDevice(args.requiredString("serial"), args.boolean("reconnect"))
                val ready = (graph.device.state.value as? DeviceConnectionState.Ready)?.device
                    ?: error(graph.deviceConnection.message.value ?: "设备尚未连接")
                saveBinding(binding + ("serial" to JsonPrimitive(ready.serial)))
            }
            ControlOps.DEVICE_DISCONNECT -> { requireIdle(); graph.disconnectDevice() }
            ControlOps.ADB_CONFIGURE -> {
                requireIdle()
                var location: AdbLocation? = null
                graph.deviceConnection.configure {
                    withContext(Dispatchers.IO) { graph.settings.update { it.copy(adbPath = args.requiredString("path").trim()) } }
                    location = graph.checkAdb()
                }
                return buildJsonObject { put("location", model(checkNotNull(location) { graph.deviceConnection.message.value ?: "ADB 配置失败" })) }
            }
            ControlOps.ADB_DOWNLOAD -> {
                requireIdle()
                var location: AdbLocation? = null
                graph.deviceConnection.configure {
                    location = withContext(Dispatchers.IO) { AdbLocator.downloadPlatformTools(graph.dataDirectory) }
                    withContext(Dispatchers.IO) { graph.settings.update { it.copy(adbPath = checkNotNull(location).path) } }
                    graph.checkAdb()
                }
                return buildJsonObject { put("location", model(checkNotNull(location) { graph.deviceConnection.message.value ?: "ADB 下载失败" })) }
            }
            ControlOps.DEVICE_TEST -> testTap()
            ControlOps.REFRESH -> {
                requireIdle()
                if (graph.device.state.value is DeviceConnectionState.Ready) {
                    graph.recheckResolution(); graph.ux.refreshForeground()
                }
                graph.ux.refreshDatabase()
            }
            ControlOps.FOREGROUND -> return buildJsonObject { put("value", graph.device.queryForeground()) }
            ControlOps.PREVIEW -> { check(!revoked.get()); graph.startPreview() }
            ControlOps.CAPTURE_STOP -> { requireIdle(); graph.captureService.stopAndJoin("用户停止截图") }
            ControlOps.DATABASE_UPDATE -> {
                val message = withContext(Dispatchers.IO) {
                    when (val result = graph.labyrinthCnDatabaseRepository.updateIfNeeded()) {
                        is LabyrinthCnDatabaseUpdateResult.Updated -> "国服数据库已更新：${result.metadata.version}"
                        is LabyrinthCnDatabaseUpdateResult.UpToDate -> "已经是最新版本：${result.metadata.version}"
                        is LabyrinthCnDatabaseUpdateResult.Failed -> result.reason
                    }
                }
                graph.ux.refreshDatabase()
                return message(message)
            }
            ControlOps.NETWORK_TEST -> return message(withContext(Dispatchers.IO) {
                OkHttpClient.Builder().callTimeout(10, java.util.concurrent.TimeUnit.SECONDS).build()
                    .newCall(Request.Builder().url("https://line1-sdk-center-login-sh.biligame.net/").head().build())
                    .execute().use { "SDK 主机可连接（HTTP ${it.code}）" }
            })
            ControlOps.ENCRYPTION_TEST -> return message(withContext(Dispatchers.IO) {
                val fixture = "desktop-self-check".toByteArray(); val entropy = "fixture".toByteArray()
                check(WindowsDpapi.unprotect(WindowsDpapi.protect(fixture, entropy), entropy).contentEquals(fixture))
                "DPAPI 当前用户自检通过"
            })
            ControlOps.LOG_CLEAR -> graph.log.clear()
            ControlOps.LOG_ADD -> graph.log.add(args.requiredString("text"), kind = enumValueOf(args.string("kind") ?: "NORMAL"))
            ControlOps.DIAGNOSTICS_EXPORT -> {
                val target = Path.of(args.requiredString("path"))
                withContext(Dispatchers.IO) {
                    val url = checkNotNull(graph.debugUrl) { "诊断面板不可用" } + "logs.zip"
                    java.net.URI(url).toURL().openConnection().apply { connectTimeout = 5_000; readTimeout = 15_000 }.getInputStream().use { input ->
                        Files.newOutputStream(target, CREATE_NEW, WRITE).use(input::copyTo)
                    }
                }
                graph.log.add("诊断包已导出：${target.fileName}")
            }
            ControlOps.OPEN_DATA -> openFolder(graph.dataDirectory)
            ControlOps.OPEN_LOGS -> openFolder(graph.dataDirectory.resolve("logs"))
            ControlOps.OPEN_CRASH -> withContext(Dispatchers.IO) {
                val name = args.requiredString("name")
                require(name in crashNames()) { "崩溃记录不存在" }
                Desktop.getDesktop().open(graph.dataDirectory.resolve("logs").resolve(name).toFile())
            }
            ControlOps.CAPTCHA_CANCEL -> {
                check(args.requiredString("ticketId") == captchaTicket) { "验证码已失效，请使用当前实例的验证" }
                graph.labyrinthController.cancelCaptcha(); closeCaptcha()
            }
            else -> error("不支持的控制操作：$op")
        }
        return accepted()
    }

    private suspend fun startTask(args: JsonObject) {
        requireIdle()
        val task = enumValueOf<DesktopTask>(args.requiredString("task"))
        val account = graph.labyrinthController.uiState.value.selectedAccount
        if (task != DesktopTask.RECOGNIZE) {
            val id = account?.id ?: error("请先选择此实例的账号")
            binding["accountId"]?.jsonPrimitive?.longOrNull?.let { check(it == id) { "账号与实例绑定不同，请重新选择账号" } }
            saveBinding(binding + ("accountId" to JsonPrimitive(id)))
            val material = graph.accountRepository.loadLoginMaterial(id)
            val session = graph.gameSessionRegistry.read(id)
            val known = synchronized(admission) { authenticatedAccounts[id] }
                ?: session?.profile?.viewerId?.let { accountServerKey(material.server) to it }
            check(known != null || task == DesktopTask.READ) { "首次使用此账号，请先选择“读取当前开局”校验服务端 UID，再启动任务" }
            synchronized(admission) {
                if (accountAdmission == null) accountAdmission = AccountTaskAdmission.acquire(accountAdmissionDirectory, bootstrap = known == null)
            }
            if (known != null) guardAuthenticatedAccount(material.server, known.second)
        }
        if (task in setOf(DesktopTask.BATCH, DesktopTask.ENTRY, DesktopTask.RECOGNIZE)) {
            val ready = (graph.device.state.value as? DeviceConnectionState.Ready)?.device ?: error("请先连接此实例的模拟器")
            binding.string("serial")?.let { check(it == ready.serial) { "模拟器与实例绑定不同，请重新连接" } }
        }
        graph.ux.expect(task)
        when (task) {
            DesktopTask.BATCH -> {
                val goals = args["goals"]?.let { decode<List<LabyrinthBatchGoal>>(it) }
                    ?: graph.ux.preferences.state.value.goals.map {
                        LabyrinthBatchGoal(it.guildId, it.count, if (it.clears) LabyrinthBatchGoalMode.CLEARS else LabyrinthBatchGoalMode.ATTEMPTS)
                    }
                check(graph.startLabyrinthAutoRun(account?.id, goals)) { "自动执行未启动，请检查账号、设备、触控和 OCR" }
            }
            DesktopTask.REROLL -> {
                val confirmed = args.boolean("retreatConfirmed")
                check(!graph.labyrinthController.uiState.value.retireExisting || confirmed) { "请先确认允许撤退现有开局" }
                graph.labyrinthController.start(confirmed, args["guildId"]?.jsonPrimitive?.intOrNull)
            }
            DesktopTask.READ -> graph.labyrinthController.checkStatus()
            DesktopTask.RECOGNIZE -> graph.startRecognition(true)
            DesktopTask.ENTRY -> graph.startRecognition(false)
        }
        yield()
    }

    private fun saveReroll(value: LabyrinthRerollSettings) {
        requireIdle()
        check(graph.labyrinthController.uiState.value.settingsReady) { "请先选择账号，等待配置加载" }
        value.validationError()?.let { error(it) }
        check(value.difficulty in graph.labyrinthController.uiState.value.availableDifficulties) { "所选难度尚未解锁" }
        graph.labyrinthController.saveSettings(value)
    }

    private suspend fun saveAccount(editor: AccountEditorState) {
        requireIdle()
        val id = editor.id
        val authoritative = id?.let { withContext(Dispatchers.IO) { graph.accountRepository.loadEditor(it) } }
        if (id == null) graph.accounts.openNewAccount()
        else accountOperation({ graph.accounts.openEditAccount(id) }) { it.editor?.id == id }
        graph.accounts.updateEditor { editor.copy(savedServerId = authoritative?.savedServerId) }
        accountOperation({ graph.accounts.saveEditor() }) { it.editor == null }
        editor.id?.let { synchronized(admission) { authenticatedAccounts.remove(it) } }
        releaseAccount()
    }

    private suspend fun accountOperation(action: () -> Unit, done: (AccountUiState) -> Boolean) {
        action(); yield()
        val state = withTimeout(10_000) { graph.accounts.uiState.first { !it.isWorking && (it.message != null || done(it)) } }
        state.message?.let { error(it) }
    }

    private suspend fun testTap() {
        requireIdle()
        val ready = (graph.device.state.value as? DeviceConnectionState.Ready)?.device ?: error("请先连接设备")
        val foreground = graph.device.queryForeground() ?: error("无法确定前台应用，未执行点击")
        check(GameServer.entries.none { it.packageName == foreground }) { "测试点击只能在非游戏前台页面进行" }
        when (val result = graph.device.tap(ready.width / 2f, ready.height / 2f, foreground)) {
            TouchResult.Completed -> graph.log.add("已在非游戏前台页面中央执行一次测试点击")
            is TouchResult.Rejected -> error(result.reason)
        }
    }

    private suspend fun stop(reason: String): JsonObject {
        epoch.incrementAndGet()
        stopping.set(true)
        synchronized(admission) { activeCommand?.cancel() }
        val job = graph.stopAll(reason)
        try {
            graph.deviceConnection.cancel()
            job.join()
            withContext(Dispatchers.Main) { closeCaptcha() }
            releaseAccount()
        } finally { stopping.set(false) }
        return accepted()
    }

    override fun revokeActions(reason: String) {
        revoked.set(true)
        epoch.incrementAndGet()
        synchronized(admission) { activeCommand?.cancel() }
        graph.resolutionGuard.disarm()
        graph.stopAll(reason)
    }

    override suspend fun shutdown() {
        if (!closing.compareAndSet(false, true)) return
        revokeActions("控制实例退出，任务已停止")
        try {
            withContext(NonCancellable) {
                graph.deviceConnection.cancel()
                commands.withLock {
                    withContext(Dispatchers.Main) { closeCaptcha() }
                    graph.shutdown()
                }
            }
        } finally {
            releaseAccount()
            scope.cancel()
        }
    }

    private fun requireIdle() {
        check(!revoked.get() && !stopping.get()) { "控制连接已停止" }
        check(!graph.busy) { "请先停止当前任务，再修改账号、设备或任务配置" }
    }

    private fun guardAuthenticatedAccount(server: GameServer, uid: Long) = synchronized(admission) {
        check(!revoked.get() && !stopping.get() && !closing.get()) { "控制连接已停止，禁止新的账号操作" }
        check(uid > 0) { "服务端 UID 无效" }
        // All channel clients use the same game-server account namespace.
        val key = accountServerKey(server) to uid
        graph.labyrinthController.uiState.value.selectedAccount?.id?.let { id ->
            authenticatedAccounts[id]?.let { check(it == key) { "服务端账号身份已变化，请停止后重新读取账号" } }
        }
        if (accountAdmission == null) accountAdmission = AccountTaskAdmission.acquire(accountAdmissionDirectory, bootstrap = false)
        if (leasedIdentity != key || accountLease?.isValid != true) {
            check(accountLease == null) { "服务端账号身份已变化，任务已禁止继续" }
            accountLease = leases.acquireAccount(key.first, uid.toString())
            leasedIdentity = key
        }
        graph.labyrinthController.uiState.value.selectedAccount?.id?.let { authenticatedAccounts[it] = key }
    }

    private fun releaseAccountIfIdle() = synchronized(admission) {
        if (!commandActive && !graph.busy && !stopping.get()) releaseAccount()
    }

    private fun releaseAccount() = synchronized(admission) {
        try { accountLease?.close() }
        finally {
            accountLease = null; leasedIdentity = null
            accountAdmission?.close(); accountAdmission = null
        }
    }

    private fun accountServerKey(server: GameServer) = if (server.isChannelServer) "cn-channel" else server.storageId

    private fun readBinding(): JsonObject = try {
        if (Files.exists(bindingFile)) Json.parseToJsonElement(Files.readString(bindingFile)).jsonObject
        else buildJsonObject { graph.settings.state.value.lastDevice.takeIf(String::isNotBlank)?.let { put("serial", it) } }
    } catch (_: Exception) { graph.addNotice("实例绑定无法读取，请重新选择账号和设备。"); JsonObject(emptyMap()) }

    private fun saveBinding(fields: Map<String, JsonElement>) {
        val next = JsonObject(fields)
        AtomicFiles.write(bindingFile, next.toString().toByteArray(Charsets.UTF_8))
        binding = next
    }

    private fun refreshCaptcha() {
        val current = graph.labyrinthController.uiState.value.captcha
        if (captchaKey?.accountId == current?.accountId && captchaKey?.challenge == current?.challenge &&
            (current == null || captchaSession != null)) return
        closeCaptcha()
        if (current == null || revoked.get()) return
        val ticket = UUID.randomUUID().toString()
        captchaKey = current; captchaTicket = ticket
        captchaSession = graph.captchaHost.open(current.challenge,
            onSolved = { result ->
                if (!revoked.get() && captchaTicket == ticket) {
                    graph.labyrinthController.submitCaptcha(result); closeCaptcha()
                }
            },
            onError = { message ->
                if (!revoked.get() && captchaTicket == ticket) {
                    graph.labyrinthController.reportCaptchaError(message); closeCaptcha()
                }
            })
    }

    private fun closeCaptcha() {
        captchaSession?.close(); captchaSession = null; captchaKey = null; captchaTicket = null
    }

    private fun captchaDocument(): JsonElement {
        val current = graph.labyrinthController.uiState.value.captcha ?: return JsonNull
        val session = captchaSession ?: return JsonNull
        return buildJsonObject {
            put("ticketId", captchaTicket); put("url", session.url)
            put("accountAlias", current.accountAlias); put("error", current.error)
        }
    }

    private suspend fun previewDocument(): JsonElement {
        val frame = graph.preview.value ?: return JsonNull
        if (previewTimestamp != frame.timestampMillis) {
            previewPng = withContext(Dispatchers.Default) {
                val image = BufferedImage(frame.width, frame.height, BufferedImage.TYPE_INT_RGB)
                image.setRGB(0, 0, frame.width, frame.height, frame.pixels, 0, frame.width)
                ByteArrayOutputStream().use { bytes ->
                    check(ImageIO.write(image, "png", bytes)) { "无法编码预览画面" }
                    Base64.getEncoder().encodeToString(bytes.toByteArray())
                }
            }
            previewTimestamp = frame.timestampMillis
        }
        return buildJsonObject { put("png", previewPng); put("timestampMillis", frame.timestampMillis) }
    }

    private fun crashNames(): List<String> = graph.dataDirectory.resolve("logs").toFile().listFiles()
        ?.filter { it.isFile && it.name.startsWith("crash-") }?.sortedByDescending { it.lastModified() }?.map { it.name }.orEmpty()

    private suspend fun openFolder(path: Path) = withContext(Dispatchers.IO) {
        Desktop.getDesktop().open(Files.createDirectories(path).toFile())
    }

    private fun validateGoals(goals: List<DesktopGoal>) {
        require(goals.map { it.guildId }.distinct().size == goals.size) { "公会目标不能重复" }
        require(goals.all { goal -> goal.count in 1..99 && LabyrinthRerollOptions.guilds.any { it.guildId == goal.guildId } }) { "请选择有效公会，次数须为 1–99" }
    }

    private fun model(value: Any?): JsonElement = ControlModelCodec.encode(value)
    private inline fun <reified T> decode(value: JsonElement): T = ControlModelCodec.decode(value)
    private fun patch(current: JsonElement, fields: JsonObject) = JsonObject(current.jsonObject + fields)
    private fun accepted() = buildJsonObject { put("accepted", true) }
    private fun message(text: String) = buildJsonObject { put("message", text) }
    private fun JsonObject.required(name: String): JsonElement = this[name] ?: error("缺少参数：$name")
    private fun JsonObject.requiredString(name: String): String = string(name) ?: error("缺少参数：$name")
    private fun JsonObject.string(name: String): String? = (this[name] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.long(name: String): Long = (this[name] as? JsonPrimitive)?.longOrNull ?: error("参数无效：$name")
    private fun JsonObject.boolean(name: String, default: Boolean = false): Boolean = (this[name] as? JsonPrimitive)?.booleanOrNull ?: default
}

/** Unknown accounts can bootstrap through one explicit read while all other account tasks are idle.
 * Known account tasks share this admission lock, then take their independent server+UID lease. */
internal class AccountTaskAdmission private constructor(private val channel: FileChannel, private val lock: FileLock) : AutoCloseable {
    override fun close() { try { if (lock.isValid) lock.release() } finally { channel.close() } }
    companion object {
        fun acquire(directory: Path, bootstrap: Boolean): AccountTaskAdmission {
            Files.createDirectories(directory.resolve("leases"))
            val channel = FileChannel.open(directory.resolve("leases/account-bootstrap.lock"), READ, WRITE, CREATE)
            try {
                val lock = try { channel.tryLock(0, Long.MAX_VALUE, !bootstrap) } catch (_: OverlappingFileLockException) { null }
                return AccountTaskAdmission(channel, lock ?: error(if (bootstrap)
                    "其他实例正在使用账号；请在账号任务空闲时执行首次“读取当前开局”校验" else "另一实例正在首次校验账号，请稍后重试"))
            } catch (failure: Throwable) { channel.close(); throw failure }
        }
    }
}
