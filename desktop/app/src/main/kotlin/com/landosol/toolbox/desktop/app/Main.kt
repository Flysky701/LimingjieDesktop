package com.landosol.toolbox.desktop.app

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.*
import com.landosol.toolbox.AppVersion
import com.landosol.toolbox.desktop.app.adapters.storage.DesktopPreferences
import com.landosol.toolbox.desktop.app.adapters.storage.DesktopSettingsStore
import com.landosol.toolbox.desktop.app.control.*
import com.landosol.toolbox.desktop.app.ui.*
import com.landosol.toolbox.desktop.app.ui.theme.DesktopTheme
import com.landosol.toolbox.desktop.platform.*
import com.landosol.toolbox.desktop.shared.DesktopLog
import java.awt.Dimension
import java.awt.GraphicsEnvironment
import javax.swing.JOptionPane
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import kotlin.system.exitProcess

/** One shared data authority; each connected device owns an isolated task runtime. */
@OptIn(FlowPreview::class)
fun main(args: Array<String>) {
    System.setProperty("java.net.useSystemProxies", "true")
    if ("--worker" in args) exitProcess(runBlocking { runWorkerMain(args) })
    if ("--self-test" in args) exitProcess(DesktopSelfTest.run(args))
    val lifetime = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    var lock: InstanceLock? = null
    var shared: SharedDataHost? = null
    lateinit var settings: DesktopSettingsStore
    val model = try {
        val legacy = DataDirectories.resolve(args).toAbsolutePath().normalize()
        // Runtime caches remain outside D5 data and the preserved D6 profile catalog.
        val root = legacy.resolveSibling(legacy.fileName.toString() + "-run-tabs")
        DataDirectories.ensureWritable(root)
        lock = InstanceLock.acquire(root)
        settings = DesktopSettingsStore(root)
        DesktopLog.initialize(root.resolve("logs"))
        val host = runBlocking { SharedDataHost.start(legacy, startupUpdates = "--offline-startup" !in args) }.also { shared = it }
        SharedSupervisorModel(host.control, host.endpoint, WorkerLauncher(lifetime, WorkerProcessContainment::own), root, lifetime)
    } catch (failure: Exception) {
        runBlocking { shared?.shutdown() }
        lock?.close(); lifetime.cancel()
        JOptionPane.showMessageDialog(null, failure.message ?: "启动失败", "黎明界助手", JOptionPane.ERROR_MESSAGE)
        exitProcess(1)
    }
    val instanceLock = checkNotNull(lock)
    val sharedHost = checkNotNull(shared)
    Runtime.getRuntime().addShutdownHook(Thread({
        runBlocking { withTimeoutOrNull(15_000) { try { model.shutdown() } finally { sharedHost.shutdown() } } }
        instanceLock.close()
    }, "control-shutdown"))
    Thread.setDefaultUncaughtExceptionHandler { thread, failure ->
        DesktopLog.write('E', "Supervisor", "线程 ${thread.name} 未处理异常", failure)
        lifetime.launch { model.stopAll(); model.reportError("界面发生异常，已请求停止全部设备任务。请查看日志。") }
    }
    lifetime.launch { runCatching { model.restore() }.onFailure { model.reportError(it.message ?: "设备列表恢复失败") } }
    application {
        val saved = remember { settings.state.value }
        val screen = GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice.defaultConfiguration
        val bounds = screen.bounds
        val restorePosition = saved.windowX?.let { x -> saved.windowY?.let { y ->
            GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices.any { it.defaultConfiguration.bounds.intersects(java.awt.Rectangle(x, y, saved.windowWidth, saved.windowHeight)) }
                .takeIf { it }?.let { WindowPosition.Absolute(x.dp, y.dp) }
        } } ?: WindowPosition.Absolute((bounds.x + (bounds.width - saved.windowWidth).coerceAtLeast(0) / 2).dp, (bounds.y + (bounds.height - saved.windowHeight).coerceAtLeast(0) / 2).dp)
        val windowState = rememberWindowState(position = restorePosition, width = saved.windowWidth.coerceAtLeast(1100).dp, height = saved.windowHeight.coerceAtLeast(700).dp)
        val instances by model.sessions.collectAsState()
        val devices by model.devices.collectAsState()
        val selectedId by model.selectedId.collectAsState()
        val busy by model.busy.collectAsState()
        val dirty by model.dirtyInstances.collectAsState()
        val error by model.error.collectAsState()
        val sessions = remember { mutableMapOf<String, RemoteControlSession>() }
        val tabs = instances.map { item -> DeviceRunTab(item.id, item.name, sessions.getOrPut(item.link.workerRunId) { RemoteControlSession(item.link) }, item.deviceId, item.serial, item.boundAccountId) }
        LaunchedEffect(instances.map { it.link.workerRunId }) { sessions.keys.retainAll(instances.map { it.link.workerRunId }.toSet()) }
        val selected = instances.firstOrNull { it.id == selectedId }
        val emptySnapshot = remember { MutableStateFlow(JsonObject(emptyMap())) }
        val selectedSnapshot by (selected?.link?.snapshots ?: emptySnapshot).collectAsState()
        val projection = ControlUiSnapshot(selectedSnapshot)
        val globalSnapshot by sharedHost.control.snapshots.collectAsState()
        val preferences = ControlUiSnapshot(globalSnapshot).model("preferences", DesktopPreferences())
        val activeConsole = projection.console
        var visible by remember { mutableStateOf(true) }
        var confirmExit by remember { mutableStateOf(false) }
        var exiting by remember { mutableStateOf(false) }
        val scope = rememberCoroutineScope()
        val trayState = rememberTrayState()
        val trayAvailable = java.awt.SystemTray.isSupported()
        val icon = remember(busy) { AppPainter(if (busy) Color(0xff2ea043) else Color(0xff0078d4)) }
        fun action(block: suspend () -> Unit) { scope.launch { try { block() } catch (cancelled: CancellationException) { throw cancelled } catch (failure: Exception) { model.reportError(failure.message ?: "操作失败") } } }
        fun quit() {
            if (exiting) return
            exiting = true
            scope.launch {
                try { try { model.shutdown() } finally { sharedHost.shutdown() } }
                finally { instanceLock.close(); lifetime.cancel(); exitApplication(); exitProcess(0) }
            }
        }
        fun closeRequested() { if (busy || dirty.isNotEmpty()) confirmExit = true else quit() }
        val callbacks = SharedRunShellCallbacks(
            selectSession = { action { model.selectSession(it) } },
            discover = { action { model.refreshDevices(it) } },
            connect = { action { model.connectDevice(it) } },
            bindAccount = { id, account -> action { model.bindAccount(id, account) } },
            disconnect = { action { model.disconnectDevice(it) } },
            closeSession = { action { model.closeSession(it) } },
            reconnectSession = { action { model.reconnectSession(it) } },
            stopAll = { action { model.stopAll() } }, onDirtyChanged = model::markDirty,
        )
        LaunchedEffect(windowState) {
            snapshotFlow { Triple(windowState.position, windowState.size, windowState.placement) }.debounce(500).collect { (position, size, placement) ->
                if (placement == WindowPlacement.Floating && size.width.value.isFinite() && size.height.value.isFinite() && position.x.value.isFinite() && position.y.value.isFinite()) {
                    withContext(Dispatchers.IO) { runCatching { settings.update { it.copy(windowWidth = size.width.value.toInt().coerceAtLeast(1100), windowHeight = size.height.value.toInt().coerceAtLeast(700), windowX = position.x.value.toInt(), windowY = position.y.value.toInt()) } }.onFailure { model.reportError("窗口位置保存失败：${it.message}") } }
                }
            }
        }
        instances.forEach { item -> key(item.link.workerRunId) {
            LaunchedEffect(item.link) {
                var previousBusy = false
                var previousCaptcha: String? = null
                item.link.snapshots.collect { raw ->
                    val state = ControlUiSnapshot(raw)
                    val prefs = ControlUiSnapshot(sharedHost.control.snapshots.value).model("preferences", DesktopPreferences())
                    val ticket = (raw["captcha"] as? JsonObject)?.get("ticketId")?.jsonPrimitive?.contentOrNull
                    val message = when {
                        ticket != null && ticket != previousCaptcha -> "需要人工验证码，请切换到此标签完成验证。"
                        previousBusy && !state.console.busy -> state.console.entry.message ?: state.console.task.message ?: "任务已结束"
                        else -> null
                    }
                    if (message != null && prefs.notifications && trayAvailable) trayState.sendNotification(Notification("${item.name} · 黎明界助手", message))
                    if (message != null && prefs.sound) java.awt.Toolkit.getDefaultToolkit().beep()
                    previousBusy = state.console.busy; previousCaptcha = ticket
                }
            }
        } }
        Window(onCloseRequest = ::closeRequested, state = windowState, visible = visible, title = "黎明界助手 ${AppVersion.name}", icon = icon) {
            window.minimumSize = Dimension(1100, 700)
            DesktopTheme(preferences) {
                // Keep the tray in the same resolved theme (including system changes) as the window.
                if (trayAvailable) DesktopTray(icon, state = trayState, tooltip = "黎明界助手 · ${instances.size} 台设备", onAction = { visible = true; windowState.isMinimized = false }) {
                    Item("显示主窗口", onClick = { visible = true; windowState.isMinimized = false })
                    Separator()
                    Item(if (activeConsole.overlay?.paused == true) "继续当前标签" else "暂停当前标签", enabled = activeConsole.overlay != null && !exiting, onClick = { selected?.link?.let { action { it.request(ControlOps.PAUSE) } } })
                    Item("停止当前标签", enabled = selected != null && !exiting, onClick = { selected?.link?.let { action { it.request(ControlOps.STOP) } } })
                    Item("停止全部", enabled = instances.isNotEmpty() && !exiting, onClick = { action { model.stopAll() } })
                    Separator()
                    Item("退出", onClick = { visible = true; closeRequested() })
                }
                SharedRunTabsApp(sharedHost.control, tabs, selectedId, callbacks,
                    devices.map { ManagedDeviceUi(it.id, it.serial, it.name, it.sessionId, it.boundAccountId, it.connected, it.busy, it.status, it.connecting) }, window, error)
                if (confirmExit) AlertDialog(
                    onDismissRequest = { if (!exiting) confirmExit = false },
                    title = { Text("关闭黎明界助手") },
                    text = { Text(buildString {
                        if (busy) append("有设备任务正在运行。最小化后，所有标签的任务会继续。\n")
                        if (dirty.isNotEmpty()) append("有未保存的修改，退出会丢弃这些修改。\n")
                        append("退出会停止全部设备任务并关闭连接，已保存的账号和配置会保留。")
                    }) },
                    confirmButton = { TextButton(enabled = !exiting, onClick = ::quit) { Text(if (exiting) "正在停止…" else "停止全部并退出") } },
                    dismissButton = { Row {
                        TextButton(enabled = !exiting, onClick = { confirmExit = false; if (trayAvailable) visible = false else windowState.isMinimized = true }) { Text(if (trayAvailable) "最小化到托盘" else "最小化窗口") }
                        TextButton(enabled = !exiting, onClick = { confirmExit = false }) { Text("取消") }
                    } },
                )
            }
        }
    }
}

/** The upstream baseline has no launcher art; this simple desktop mark has no external asset. */
private class AppPainter(private val tone: Color = Color(0xff0078d4)) : Painter() {
    override val intrinsicSize = Size(64f, 64f)
    override fun DrawScope.onDraw() {
        drawRoundRect(tone, cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.width * .24f))
        drawCircle(Color.White, size.minDimension * .27f)
        drawCircle(tone, size.minDimension * .22f, center + androidx.compose.ui.geometry.Offset(size.width * .11f, -size.height * .07f))
        drawCircle(Color(0xFFFFDC92), size.minDimension * .07f, center + androidx.compose.ui.geometry.Offset(size.width * .22f, size.height * .2f))
    }
}
