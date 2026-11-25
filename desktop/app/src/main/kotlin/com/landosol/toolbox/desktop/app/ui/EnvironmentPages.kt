package com.landosol.toolbox.desktop.app.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.landosol.toolbox.AppVersion
import com.landosol.toolbox.automation.GameClientResolution
import com.landosol.toolbox.desktop.app.UserLogKind
import com.landosol.toolbox.desktop.app.control.*
import com.landosol.toolbox.desktop.app.adapters.device.inProgress
import com.landosol.toolbox.desktop.app.ui.theme.DesktopCorners
import com.landosol.toolbox.desktop.app.ui.theme.LocalDesktopColors
import com.landosol.toolbox.desktop.device.*
import com.landosol.toolbox.desktop.ocr.OcrEngineStatus
import com.landosol.toolbox.desktop.platform.*
import com.landosol.toolbox.labyrinth.LabyrinthCnDatabaseUpdateResult
import com.landosol.toolbox.protocol.bilibili.GameServer
import kotlinx.coroutines.*
import kotlinx.serialization.json.*

@Composable
internal fun DevicesPage(graph: ControlSessionUi, managed: Boolean = false) {
    val busy = LocalTaskBusy.current
    val settings by graph.settings.state.collectAsState()
    val state by graph.device.state.collectAsState()
    val connectionActivity by graph.deviceConnection.activity.collectAsState()
    val connectionBusy = connectionActivity != null || state.inProgress
    val ready = (state as? DeviceConnectionState.Ready)?.device
    val actions = rememberActions(graph)
    val hostWindow = LocalDesktopWindow.current
    var adbPath by remember(settings.adbPath) { mutableStateOf(settings.adbPath) }
    var location by remember { mutableStateOf<AdbLocation?>(null) }
    var advanced by remember { mutableStateOf(false) }
    PageBody("设备", "选择一个模拟器连接。切换设备或触控模式前，请先停止任务。") {
        Message(actions.message, onDismiss = { actions.message = null })
        if (!managed) DeviceConnectionPanel(graph)
        if (!managed) TextButton(onClick = { advanced = !advanced }) { Text(if (advanced) "收起高级设置" else "高级：ADB 工具与路径") }
        if (advanced && !managed) Panel("ADB 工具") {
            OutlinedTextField(adbPath, { adbPath = it }, label = { Text("adb.exe 路径（留空自动查找）") }, singleLine = true, modifier = Modifier.fillMaxWidth(), enabled = !actions.working && !busy)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(shape = MaterialTheme.shapes.small, onClick = { actions.run { chooseFile(false, "选择 adb.exe", "exe", owner = hostWindow)?.let { adbPath = it.absolutePath } } }, enabled = !actions.working && !busy) { Text("选择文件") }
                Button(shape = MaterialTheme.shapes.small, onClick = { actions.run { location = graph.configureAdb(adbPath.trim()) } }, enabled = !actions.working && !busy && !connectionBusy && ready == null) { Text("保存并检查") }
                OutlinedButton(shape = MaterialTheme.shapes.small, onClick = { actions.run { val found = graph.downloadAdb(); location = found; adbPath = found.path } }, enabled = !actions.working && !busy && !connectionBusy && ready == null) { Text("下载 Platform Tools") }
            }
            location?.let { Detail("来源", it.source); Detail("版本", it.version) }
            Text("更换 ADB 前请先断开设备。下载使用 Android 官方 Platform Tools。", color = Muted, fontSize = 12.5.sp)
        }
        Panel("截图与触控") {
            ready?.let { device ->
                Detail("截图方式", device.selectedMode.name)
                device.timing.forEach { timing ->
                    Detail(timing.mode.name, "p50 ${timing.p50Millis} ms · p95 ${timing.p95Millis} ms")
                    val accent = Accent
                    val muted = Line
                    Canvas(Modifier.fillMaxWidth().height(18.dp)) {
                        val max = device.timing.maxOf { it.p95Millis }.coerceAtLeast(1).toFloat()
                        drawRect(muted, size = androidx.compose.ui.geometry.Size(size.width * timing.p95Millis / max, 7f))
                        drawRect(accent, topLeft = androidx.compose.ui.geometry.Offset(0f, 10f), size = androidx.compose.ui.geometry.Size(size.width * timing.p50Millis / max, 7f))
                    }
                }
                Detail("触控", if (device.touchReady) "就绪" else "未就绪，仅可使用只读功能")
                Detail("屏幕状态", if (device.awake) "已唤醒" else "未唤醒")
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(settings.touchMode == PreferredTouchMode.MAATOUCH, { actions.run { withContext(Dispatchers.IO) { graph.settings.update { it.copy(touchMode = PreferredTouchMode.MAATOUCH) } } } }, enabled = !actions.working && !busy && !connectionBusy)
                Text("MaaTouch", Modifier.weight(1f))
                RadioButton(settings.touchMode == PreferredTouchMode.COMPATIBILITY, { actions.run { withContext(Dispatchers.IO) { graph.settings.update { it.copy(touchMode = PreferredTouchMode.COMPATIBILITY) } } } }, enabled = !actions.working && !busy && !connectionBusy)
                Text("兼容模式", Modifier.weight(1f))
            }
            Text("MaaTouch 支持独立按住与滑动；兼容模式使用 ADB 输入，动作更慢。切换后重新连接生效。", color = Muted, fontSize = 12.5.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(shape = MaterialTheme.shapes.small, onClick = { actions.run { graph.connectDevice(checkNotNull(ready).serial, reconnect = true) } }, enabled = ready != null && !busy && !actions.working && !connectionBusy) { Text("重新连接与测速") }
                OutlinedButton(shape = MaterialTheme.shapes.small, onClick = {
                    actions.run {
                        val current = checkNotNull(ready)
                        val foreground = graph.device.queryForeground() ?: error("无法确定前台应用，未执行点击")
                        check(GameServer.entries.none { it.packageName == foreground }) { "测试点击仅允许在游戏以外的前台页面执行" }
                        when (val result = graph.device.tap(current.width / 2f, current.height / 2f, foreground)) {
                            TouchResult.Completed -> graph.log.add("已在非游戏前台页面中央执行一次测试点击")
                            is TouchResult.Rejected -> error(result.reason)
                        }
                    }
                }, enabled = ready != null && !busy && !actions.working) { Text("测试点击") }
            }
        }
        Panel("设备画像与客户端") {
            val identityState by graph.identityStore.state.collectAsState()
            val snapshot = identityState
            if (snapshot == null) Text("连接完成后会读取模拟器信息。", color = Muted)
            else {
                Detail("设备", "${snapshot.brand} ${snapshot.model}")
                Detail("系统", "Android ${snapshot.release} · API ${snapshot.sdkInt}")
                Detail("设备标识", if (snapshot.androidId.isBlank()) "未提供（使用安装标识回落）" else "••••${snapshot.androidId.takeLast(4)}")
                Detail("设备报告尺寸", "${snapshot.displayWidth} × ${snapshot.displayHeight}")
                if (snapshot.packages.isEmpty()) Text("未检测到支持的游戏客户端", color = MaterialTheme.colorScheme.error)
                snapshot.packages.forEach { (pkg, identity) -> Detail(GameServer.entries.firstOrNull { it.packageName == pkg }?.displayName ?: pkg, "${identity.versionName} (${identity.versionCode})") }
            }
        }
    }
}

@Composable
internal fun DeviceConnectionPanel(graph: ControlSessionUi, autoDetect: Boolean = false) {
    val resolution by graph.resolutionGuard.status.collectAsState()
    val busy = LocalTaskBusy.current
    val state by graph.device.state.collectAsState()
    val actions = rememberActions(graph)
    val devices by graph.deviceConnection.devices.collectAsState()
    val activity by graph.deviceConnection.activity.collectAsState()
    val message by graph.deviceConnection.message.collectAsState()
    val settings by graph.settings.state.collectAsState()
    val connecting = activity != null || state.inProgress
    val canConnect = !busy && !connecting && !actions.working
    val ready = (state as? DeviceConnectionState.Ready)?.device
    val cancelActions = rememberActions(graph)
    var address by remember { mutableStateOf(graph.settings.state.value.lastDevice) }
    var advanced by remember { mutableStateOf(false) }
    LaunchedEffect(autoDetect, settings.adbPath) { if (autoDetect && !busy) graph.deviceConnection.discover(force = false) }
    Panel("连接模拟器") {
        Text(
            if (state is DeviceConnectionState.Failed && message != null) "连接未完成" else deviceLabel(state, resolution.dimensionsLabel),
            color = if (ready != null) LocalDesktopColors.current.ok else Muted,
        )
        Message(message)
        Message(actions.message, onDismiss = { actions.message = null })
        Message(cancelActions.message, onDismiss = { cancelActions.message = null })
        if (connecting) {
            if (!state.inProgress) Text(activity ?: "正在连接", color = Muted, style = MaterialTheme.typography.bodySmall)
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        if (busy) Text("任务运行中，停止后可连接、断开或修改设备。", color = Muted, style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = { advanced = !advanced }) { Text(if (advanced) "收起手动地址" else "高级：手动填写地址") }
        if (advanced) Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(address, { address = it }, modifier = Modifier.weight(1f), singleLine = true, label = { Text("设备序列号或本机地址：端口") }, enabled = canConnect)
            Button(shape = MaterialTheme.shapes.small, onClick = { actions.run { graph.connectDevice(address.trim()) } }, enabled = address.isNotBlank() && canConnect) { Text("连接") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(shape = MaterialTheme.shapes.small, onClick = { actions.run { graph.deviceConnection.discover() } }, enabled = canConnect) { Text("重新检测") }
            OutlinedButton(shape = MaterialTheme.shapes.small, onClick = { cancelActions.run { if (connecting) graph.deviceConnection.cancel() else graph.disconnectDevice() } }, enabled = (ready != null || connecting) && !cancelActions.working && !busy) { Text(if (connecting) "取消" else "断开") }
            if (message != null && address.isNotBlank()) TextButton(onClick = { actions.run { graph.connectDevice(address.trim()) } }, enabled = canConnect) { Text("重试连接") }
        }
        Detail("实际画面", if (ready == null) "等待连接" else resolution.dimensionsLabel)
        Text("自动执行支持 1920 × 1080 或 1280 × 720 横屏，任务启动时检查；720p 已开放测试。", color = Muted, fontSize = 12.5.sp)
        OutlinedButton(onClick = { actions.run { graph.recheckResolution() } }, enabled = ready != null && canConnect) { Text("重新检查画面尺寸") }
        if (devices.isEmpty() && !connecting && message == null) Text("启动模拟器后点击“重新检测”，或展开手动地址。", color = Muted, style = MaterialTheme.typography.bodySmall)
        devices.forEach { device ->
            Surface(onClick = { address = device.serial; actions.run { graph.connectDevice(device.serial) } }, enabled = canConnect && ready?.serial != device.serial, color = Background, shape = DesktopCorners.sm) {
                Row(Modifier.fillMaxWidth().padding(10.dp)) {
                    Text(device.label, Modifier.weight(1f))
                    Text("${device.serial} · ${if (ready?.serial == device.serial) "已连接" else "${device.state} · 连接 →"}", color = Muted, fontSize = 12.5.sp)
                }
            }
        }
    }
}

private enum class SettingsGroup(val label: String, val icon: String) {
    APPEARANCE("外观与通知", "dark_mode"),
    RUNTIME("运行环境", "tune"),
    NETWORK("网络与窗口", "smartphone"),
    STORAGE("数据库与本地数据", "bar_chart"),
    ABOUT("关于", "settings"),
}

@Composable
internal fun SettingsPage(graph: ControlSessionUi, openWizard: () -> Unit, openDevices: () -> Unit, shared: Boolean = false) {
    val resolution by graph.resolutionGuard.status.collectAsState()
    val busy = LocalTaskBusy.current
    val saved by graph.settings.state.collectAsState()
    val device by graph.device.state.collectAsState()
    val ocr by graph.ocr.status.collectAsState()
    val database by graph.ux.database.collectAsState()
    val ready = (device as? DeviceConnectionState.Ready)?.device
    val actions = rememberActions(graph)
    val hostWindow = LocalDesktopWindow.current
    var encryption by remember { mutableStateOf("尚未检查") }
    var networkResult by remember { mutableStateOf<String?>(null) }
    var updateResult by remember { mutableStateOf<String?>(null) }
    var replayResult by remember { mutableStateOf<String?>(null) }
    var replayStats by remember(graph) { mutableStateOf<Pair<Int, Int>?>(null) }
    var group by remember { mutableStateOf(SettingsGroup.APPEARANCE) }

    LaunchedEffect(group, graph) {
        if (group == SettingsGroup.STORAGE) {
            runCatching { graph.replayStats() }.getOrNull()?.let { replayStats = it }
        }
    }

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 24.dp, vertical = 18.dp)) {
            Text("设置", style = MaterialTheme.typography.titleLarge)
            Text("查看任务前置条件和桌面偏好，每项检查都在此处反馈。", color = Muted, style = MaterialTheme.typography.bodySmall)
        }
        HorizontalDivider(color = Line)
        Row(Modifier.weight(1f).fillMaxWidth()) {
            Surface(Modifier.width(176.dp).fillMaxHeight(), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Column(Modifier.verticalScroll(rememberScrollState()).padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    SettingsGroup.entries.forEach { item ->
                        val active = group == item
                        Surface(
                            onClick = { group = item },
                            shape = DesktopCorners.sm,
                            color = if (active) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                        ) {
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 9.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Symbol(item.icon, tint = if (active) Accent else Muted, modifier = Modifier.size(17.dp))
                                Text(item.label, style = MaterialTheme.typography.bodySmall, color = if (active) Accent else LocalContentColor.current)
                            }
                        }
                    }
                }
            }
            VerticalDivider(color = Line)
            Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Message(actions.message, onDismiss = { actions.message = null })
                when (group) {
                    SettingsGroup.APPEARANCE -> AppearanceSettings(graph)
                    SettingsGroup.RUNTIME -> {
                        if (shared) Panel("运行环境") {
                            Text("各设备的连接、画面尺寸、触控和游戏客户端信息统一在设备管理中查看。", color = Muted)
                            OutlinedButton(onClick = openDevices) { Text("打开设备管理") }
                            Detail("凭据加密", encryption)
                            OutlinedButton(onClick = { actions.run { encryption = graph.messageRequest(ControlOps.ENCRYPTION_TEST) } }, enabled = !actions.working) { Text("检查加密") }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(saved.gameSettingsConfirmed, { checked ->
                                    actions.run { graph.settings.update { it.copy(gameSettingsConfirmed = checked) } }
                                })
                                Text("我已在游戏黎明界设置中关闭“播放角色加入动画”", fontSize = 12.5.sp)
                            }
                        } else Panel("运行环境") {
                            Detail("模拟器连接", deviceLabel(device, resolution.dimensionsLabel))
                            Detail("截图", ready?.timing?.firstOrNull { it.mode == ready.selectedMode }?.let { "p95 ${it.p95Millis} ms${if (it.p95Millis > 600) "，偏慢" else ""}" } ?: "未完成测速")
                            Detail("实际画面", if (ready == null) "等待连接" else resolution.dimensionsLabel)
                            Text("自动执行支持 1920 × 1080 或 1280 × 720 横屏，任务启动时检查；720p 已开放测试。", color = Muted, fontSize = 12.5.sp)
                            OutlinedButton(onClick = { actions.run { graph.recheckResolution() } }, enabled = ready != null && !busy && !actions.working) { Text("重新检查画面尺寸") }
                            Detail("触控", if (ready?.touchReady == true) if (saved.touchMode == PreferredTouchMode.COMPATIBILITY) "兼容模式（速度较慢）" else "MaaTouch 就绪" else "未就绪")
                            Detail("屏幕", if (ready == null) "未读取" else if (ready.awake) "已唤醒" else "未唤醒")
                            val client by graph.gameClient.collectAsState()
                            Detail("游戏客户端", when (val currentClient = client) {
                                is GameClientResolution.Available -> "${currentClient.server.displayName} 已安装"
                                is GameClientResolution.NotInstalled -> "请在模拟器中安装 ${currentClient.server.displayName} 客户端"
                                is GameClientResolution.Ambiguous -> "检测到多个客户端，请先选择账号"
                                is GameClientResolution.UnknownServer -> "账号服务器无法识别，请在账号库重新选择"
                                GameClientResolution.Unavailable -> "未检测到支持的游戏客户端"
                            })
                            val foreground by graph.device.foreground.collectAsState()
                            Detail("前台应用", foreground ?: "尚未读取")
                            Detail("OCR", when (ocr) { OcrEngineStatus.Ready -> "就绪"; OcrEngineStatus.Loading -> "正在加载"; OcrEngineStatus.NotLoaded -> "尚未加载"; is OcrEngineStatus.Failed -> (ocr as OcrEngineStatus.Failed).reason })
                            Detail("凭据加密", encryption)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(shape = MaterialTheme.shapes.small, onClick = openDevices) { Text("设备设置") }
                                OutlinedButton(shape = MaterialTheme.shapes.small, onClick = { actions.run { encryption = graph.messageRequest(ControlOps.ENCRYPTION_TEST) } }, enabled = !actions.working) { Text("检查加密") }
                                OutlinedButton(shape = MaterialTheme.shapes.small, onClick = { actions.run { graph.device.queryForeground() ?: error("前台应用暂时无法读取") } }, enabled = ready != null && !actions.working) { Text("刷新前台状态") }
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(saved.gameSettingsConfirmed, { checked -> actions.run { withContext(Dispatchers.IO) { graph.settings.update { it.copy(gameSettingsConfirmed = checked) } } } })
                                Text("我已在游戏黎明界设置中关闭“播放角色加入动画”", fontSize = 12.5.sp)
                            }
                        }
                    }
                    SettingsGroup.NETWORK -> Panel("网络与窗口") {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(saved.networkMode == NetworkMode.SYSTEM, { actions.run { withContext(Dispatchers.IO) { graph.settings.update { it.copy(networkMode = NetworkMode.SYSTEM) } } } }); Text("跟随系统代理", Modifier.weight(1f))
                            RadioButton(saved.networkMode == NetworkMode.DIRECT, { actions.run { withContext(Dispatchers.IO) { graph.settings.update { it.copy(networkMode = NetworkMode.DIRECT) } } } }); Text("直连", Modifier.weight(1f))
                        }
                        Text("支持静态系统代理；Java 不支持 PAC 自动配置脚本。模式切换后对新请求生效。", color = Muted, fontSize = 12.5.sp)
                        OutlinedButton(shape = MaterialTheme.shapes.small, onClick = { actions.run { networkResult = graph.messageRequest(ControlOps.NETWORK_TEST) } }, enabled = !actions.working) { Text("测试 SDK 连通性") }
                        Message(networkResult)
                        Text("关闭总控窗口时统一确认：停止全部并退出，或最小化到托盘继续。关闭单个标签只影响该标签。", fontSize = 12.5.sp, color = Muted)
                    }
                    SettingsGroup.STORAGE -> {
                        Panel("数据库与本地数据") {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("国服数据库", Modifier.width(125.dp), color = Muted, style = MaterialTheme.typography.bodySmall)
                                StatusBadge(
                                    database,
                                    tone = when {
                                        database.startsWith("已就绪") -> StatusTone.OK
                                        database.startsWith("正在") -> StatusTone.NEUTRAL
                                        else -> StatusTone.WARN
                                    },
                                )
                            }
                            Detail("数据目录", graph.dataDirectory.toString())
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(shape = MaterialTheme.shapes.small, onClick = { actions.run { graph.request(ControlOps.OPEN_DATA) } }) { Text("打开数据目录") }
                                OutlinedButton(shape = MaterialTheme.shapes.small, onClick = { actions.run { graph.stopCapture() } }, enabled = !actions.working) { Text("停止截图") }
                                OutlinedButton(shape = MaterialTheme.shapes.small, onClick = { actions.run { updateResult = graph.messageRequest(ControlOps.DATABASE_UPDATE) } }, enabled = !actions.working) { Text("检查国服数据库") }
                            }
                            Message(updateResult)
                            LaunchedEffect(updateResult) { if (updateResult != null) graph.ux.refreshDatabase() }
                        }
                        Panel("Path B 匿名回放数据导出") {
                            Text(
                                "导出当前节点下保存的匿名路线与实机影子战斗样本（replays.jsonl），供离线评测使用，不含账号信息。",
                                style = MaterialTheme.typography.bodySmall,
                                color = Muted,
                            )
                            replayStats?.let { (episodes, samples) ->
                                Detail("当前节点样本", "路线 $episodes 局 · 影子战斗样本 $samples 条")
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(
                                    shape = MaterialTheme.shapes.small,
                                    onClick = {
                                        actions.run {
                                            val target = chooseFile(true, "导出匿名回放", "jsonl", "replays.jsonl", hostWindow) ?: return@run
                                            replayResult = graph.exportReplays(target.absolutePath)
                                            replayStats = graph.replayStats()
                                        }
                                    },
                                    enabled = !actions.working,
                                ) { Text("导出 replays.jsonl") }
                            }
                            Message(replayResult)
                        }
                    }
                    SettingsGroup.ABOUT -> Panel("关于") {
                        Text("黎明界助手 ${AppVersion.name} · Windows 社区移植")
                        ProjectOriginsPanel()
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(shape = MaterialTheme.shapes.small, onClick = openWizard) { Text("重新打开使用向导") }
                            TextButton(onClick = { BrowserLauncher.open("https://github.com/wbero/limingjie-assistant/blob/main/LICENSE") }) { Text("许可证") }
                            TextButton(onClick = { BrowserLauncher.open("https://github.com/wbero/limingjie-assistant/blob/main/THIRD_PARTY_NOTICES.md") }) { Text("第三方声明") }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DiagnosticsPage(graph: ControlSessionUi, targetLabel: String? = null) {
    var lines by remember(graph) { mutableStateOf(graph.log.lines.value) }
    var crashes by remember(graph) { mutableStateOf(emptyList<String>()) }
    var replayStats by remember(graph) { mutableStateOf<Pair<Int, Int>?>(null) }
    var replayMessage by remember(graph) { mutableStateOf<String?>(null) }
    val ocr by graph.ocr.status.collectAsState()
    val ocrTelemetry by graph.ocr.telemetry.collectAsState()
    val incidentCount by graph.incidentCount.collectAsState()
    val revision by graph.log.revision.collectAsState()
    val connection by graph.port.connection.collectAsState()
    val visible = LocalControlVisible.current
    val actions = rememberActions(graph)
    val hostWindow = LocalDesktopWindow.current

    var logFilter by remember { mutableStateOf("全部") }
    var logQuery by remember { mutableStateOf("") }

    LaunchedEffect(graph, revision, connection, visible) {
        if (visible && connection == WorkerConnectionStatus.CONNECTED) {
            try { lines = graph.log.load() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { actions.message = failure.message ?: "读取日志失败" }
        }
    }
    LaunchedEffect(graph, connection, visible) {
        if (visible && connection == WorkerConnectionStatus.CONNECTED) {
            try { crashes = graph.request(ControlOps.CRASHES_LOAD).getValue("items").jsonArray.map { it.jsonPrimitive.content } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { actions.message = failure.message ?: "读取崩溃记录失败" }
            runCatching { graph.replayStats() }.getOrNull()?.let { replayStats = it }
        }
    }

    val shownLines = remember(lines, logFilter, logQuery) {
        lines.asReversed().filter {
            (logFilter == "全部" || logFilter == "关键" && it.kind != UserLogKind.NORMAL ||
                logFilter == "警告与错误" && it.kind in listOf(UserLogKind.WARNING, UserLogKind.ERROR)) &&
                (logQuery.isBlank() || it.text.contains(logQuery, true))
        }
    }

    PageBody(
        title = if (targetLabel != null) "日志与诊断 · $targetLabel" else "日志与诊断",
        subtitle = "查看任务事件、OCR 健康度与事故现场，或导出诊断包与匿名回放。",
    ) {
        Message(actions.message, onDismiss = { actions.message = null })
        Panel("诊断面板与事故现场") {
            Text(graph.debugUrl ?: "诊断服务不可用，请查看当前实例日志。", color = Muted, style = MaterialTheme.typography.bodySmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                StatusBadge(
                    "OCR：${when (ocr) {
                        OcrEngineStatus.Ready -> "就绪"
                        OcrEngineStatus.Loading -> "加载中"
                        OcrEngineStatus.NotLoaded -> "未加载"
                        is OcrEngineStatus.Failed -> "失败"
                    }}",
                    tone = if (ocr is OcrEngineStatus.Ready) StatusTone.OK else if (ocr is OcrEngineStatus.Failed) StatusTone.ERROR else StatusTone.NEUTRAL,
                )
                StatusBadge("OCR 提交 ${ocrTelemetry.submitted}", tone = StatusTone.NEUTRAL)
                if (ocrTelemetry.queueDrops > 0) StatusBadge("队列丢帧 ${ocrTelemetry.queueDrops}", tone = StatusTone.WARN)
                if (ocrTelemetry.consecutiveFailures > 0) StatusBadge("连续失败 ${ocrTelemetry.consecutiveFailures}", tone = StatusTone.ERROR)
                StatusBadge(
                    if (incidentCount > 0) "事故快照 $incidentCount 条" else "事故快照 0 条",
                    tone = if (incidentCount > 0) StatusTone.WARN else StatusTone.NEUTRAL,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(shape = MaterialTheme.shapes.small, onClick = { graph.debugUrl?.let(BrowserLauncher::open) }, enabled = graph.debugUrl != null) { Text("在浏览器打开") }
                OutlinedButton(
                    shape = MaterialTheme.shapes.small,
                    onClick = {
                        actions.run {
                            chooseFile(true, "保存诊断包（含事故现场）", "zip", "limingjie-debug.zip", hostWindow)?.let { target ->
                                graph.request(ControlOps.DIAGNOSTICS_EXPORT, buildJsonObject { put("path", target.absolutePath) })
                                actions.message = "诊断包已导出：${target.name}"
                            }
                        }
                    },
                    enabled = graph.debugUrl != null && !actions.working,
                ) { Text("导出诊断包") }
                OutlinedButton(shape = MaterialTheme.shapes.small, onClick = { actions.run { graph.request(ControlOps.OPEN_LOGS) } }) { Text("打开日志目录") }
            }
            if (actions.working) LinearProgressIndicator(Modifier.fillMaxWidth())
        }

        Panel("M3 匿名回放导出（replays.jsonl）") {
            val stats = replayStats
            Text(
                if (stats != null) "当前数据源可导出 ${stats.first} 局路线（含 ${stats.second} 条影子战斗样本）"
                else "正在统计可导出的路线与影子战斗样本…",
                style = MaterialTheme.typography.bodySmall,
                color = Muted,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    shape = MaterialTheme.shapes.small,
                    onClick = {
                        actions.run {
                            val target = chooseFile(true, "导出匿名回放", "jsonl", "replays.jsonl", hostWindow) ?: return@run
                            replayMessage = graph.exportReplays(target.absolutePath)
                            replayStats = graph.replayStats()
                        }
                    },
                    enabled = !actions.working && connection == WorkerConnectionStatus.CONNECTED,
                ) { Text("导出 replays.jsonl") }
                OutlinedButton(
                    shape = MaterialTheme.shapes.small,
                    onClick = { actions.run { replayStats = graph.replayStats() } },
                    enabled = !actions.working && connection == WorkerConnectionStatus.CONNECTED,
                ) { Text("刷新统计") }
            }
            Message(replayMessage)
        }

        Panel("任务日志 · ${lines.size} 条（当前显示 ${shownLines.size} 条）") {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("全部", "关键", "警告与错误").forEach { label ->
                    FilterChip(selected = logFilter == label, onClick = { logFilter = label }, label = { Text(label, style = MaterialTheme.typography.bodySmall) })
                }
                OutlinedTextField(
                    value = logQuery,
                    onValueChange = { logQuery = it },
                    placeholder = { Text("过滤日志关键字") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                OutlinedButton(shape = MaterialTheme.shapes.small, onClick = { copyText(graph.log.text(lines)) }) { Text("复制全部") }
                TextButton(onClick = graph.log::clear) { Text("清空") }
            }
            if (shownLines.isEmpty()) {
                Text(if (lines.isEmpty()) "暂无任务日志" else "没有匹配的日志", color = Muted, style = MaterialTheme.typography.bodySmall)
            } else {
                SelectionContainer {
                    LazyColumn(Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 380.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        itemsIndexed(shownLines) { _, line ->
                            Column {
                                Text(
                                    "${line.time}  ${line.text}" + if (line.repeats > 1) " ×${line.repeats}" else "",
                                    fontSize = 12.5.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = when (line.kind) {
                                        UserLogKind.ERROR -> LocalDesktopColors.current.error
                                        UserLogKind.WARNING -> LocalDesktopColors.current.warn
                                        UserLogKind.KEY -> Accent
                                        else -> MaterialTheme.colorScheme.onSurface
                                    },
                                )
                                HorizontalDivider(color = Line, modifier = Modifier.padding(top = 4.dp))
                            }
                        }
                    }
                }
            }
        }
        Panel("最近崩溃记录") {
            if (crashes.isEmpty()) Text("没有崩溃记录", color = Muted)
            crashes.take(10).forEach { name ->
                TextButton(onClick = { actions.run { graph.request(ControlOps.OPEN_CRASH, buildJsonObject { put("name", name) }) } }) { Text(name) }
            }
        }
    }
}

@Composable
internal fun SetupWizard(graph: ControlSessionUi, shared: Boolean = false, deviceContent: (@Composable () -> Unit)? = null, onFinished: () -> Unit) {
    val steps = listOf("项目介绍", "使用须知", "连接模拟器", "添加账号")
    var step by remember { mutableStateOf(0) }
    var accepted by remember { mutableStateOf(false) }
    val actions = rememberActions(graph)
    if (LocalControlVisible.current) Dialog(onDismissRequest = {}, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnBackPress = false, dismissOnClickOutside = false)) {
        Surface(Modifier.width(800.dp).fillMaxHeight(.9f), shape = DesktopCorners.lg) {
            Column(Modifier.padding(28.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("欢迎使用黎明界助手", style = MaterialTheme.typography.titleLarge)
                Text("${step + 1} / ${steps.size}  ·  ${steps[step]}", color = Accent)
                Column(Modifier.weight(1f).verticalScroll(remember(step) { ScrollState(0) }), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    when (step) {
                        0 -> ProjectOriginsPanel()
                        1 -> {
                            Text("使用助手和模拟器时，请勿同时使用 VPN 或其他网络代理。", style = MaterialTheme.typography.titleMedium)
                            Text("这是 Windows 社区移植版。以下使用风险沿用上游说明：", color = Muted)
                            Text("当前为测试版本，流程可能卡死、停在未知页面、误识别或误操作，不保证自动完成。仅测试过 MuMu 模拟器（B 服）与雷电模拟器 9（小米、华为、vivo 渠道服）、1920 × 1080 分辨率，手机及其他环境暂未测试。请留意运行状态，出现异常及时暂停，不建议无人值守运行。")
                            Text("本项目与游戏官方无关。自动操作和非官方接口访问可能带来账号限制或封禁风险，不保证账号安全；使用前请自行确认游戏相关规则。")
                            Text("角色选择、商店购买、刷新、战斗及彻底撤退会改变真实游戏状态，可能消耗资源或丢失当前开局。启动前核对账号、策略和撤退选项。")
                            Text("“已执行”仅表示操作已派发，不代表游戏已完成预期操作。当前角色列表滚动、边界回弹、识别与流程衔接仍可能异常，需要人工接管。")
                            Text("请勿公开账号密码、访问令牌、验证码、未脱敏日志或包含个人信息的截图。交流群不是账号托管渠道。")
                            Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(accepted, { accepted = it }); Text("我已阅读并理解") }
                        }
                        2 -> {
                            Text("推荐 MuMu 12 或雷电模拟器 9，使用 1920 × 1080 横屏并打开 ADB 调试。")
                            Text("游戏内黎明界设置请关闭“播放角色加入动画”。连接也可以稍后在设备页完成。", color = Muted)
                            if (deviceContent != null) Box(Modifier.height(400.dp)) { deviceContent() } else DeviceConnectionPanel(graph, autoDetect = true)
                        }
                        3 -> {
                            Text(if (shared) "添加一次账号，之后在设备管理页选择绑定。此操作不会切换模拟器里的游戏账号。" else "添加并选择助手使用的账号。此操作不会切换模拟器里的游戏账号。", color = Muted)
                            Box(Modifier.heightIn(min = 280.dp, max = 480.dp)) { AccountsPage(graph, shared = shared) }
                        }
                    }
                    Message(actions.message, onDismiss = { actions.message = null })
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (step > 0) OutlinedButton(shape = MaterialTheme.shapes.small, onClick = { step-- }, enabled = !actions.working) { Text("上一步") }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { if (step < steps.lastIndex) step++ else actions.run { withContext(Dispatchers.IO) { graph.settings.update { it.copy(wizardCompleted = true) } }; onFinished() } }, enabled = !actions.working) { Text("稍后设置 / 跳过") }
                    Button(
                        shape = MaterialTheme.shapes.small,
                        onClick = { if (step < steps.lastIndex) step++ else actions.run { withContext(Dispatchers.IO) { graph.settings.update { it.copy(wizardCompleted = true) } }; onFinished() } },
                        enabled = !actions.working && (step != 1 || accepted),
                    ) { Text(if (step == steps.lastIndex) "进入运行台" else "继续") }
                }
            }
        }
    }
}
