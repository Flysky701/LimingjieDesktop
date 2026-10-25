package com.landosol.toolbox.desktop.app.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.landosol.toolbox.AppVersion
import com.landosol.toolbox.automation.GameClientResolution
import com.landosol.toolbox.desktop.app.DesktopAppGraph
import com.landosol.toolbox.desktop.device.*
import com.landosol.toolbox.desktop.ocr.OcrEngineStatus
import com.landosol.toolbox.desktop.platform.*
import com.landosol.toolbox.labyrinth.LabyrinthCnDatabaseUpdateResult
import com.landosol.toolbox.protocol.bilibili.GameServer
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.awt.Desktop
import java.io.File
import java.nio.file.Files
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter

@Composable internal fun DevicesPage(graph: DesktopAppGraph) {
    val busy = LocalTaskBusy.current
    val settings by graph.settings.state.collectAsState()
    val state by graph.device.state.collectAsState()
    val ready = (state as? DeviceConnectionState.Ready)?.device
    val actions = rememberActions(graph)
    var adbPath by remember(settings.adbPath) { mutableStateOf(settings.adbPath) }
    var location by remember { mutableStateOf<AdbLocation?>(null) }
    PageBody("设备", "选择一个模拟器连接。切换设备或触控模式前，请先停止任务。") {
        Message(actions.message)
        DeviceConnectionPanel(graph)
        Panel("ADB 工具") {
            OutlinedTextField(adbPath, { adbPath = it }, label = { Text("adb.exe 路径（留空自动查找）") }, singleLine = true, modifier = Modifier.fillMaxWidth(), enabled = !actions.working && !busy)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { chooseFile(false, "选择 adb.exe", "exe")?.let { adbPath = it.absolutePath } }, enabled = !actions.working && !busy) { Text("选择文件") }
                Button(onClick = { actions.run { withContext(Dispatchers.IO) { graph.settings.update { it.copy(adbPath = adbPath.trim()) }; location = AdbLocator.find(adbPath.trim().ifEmpty { null }, graph.dataDirectory.resolve("platform-tools")) }; check(location != null) { "未找到有效 ADB，请检查路径或下载 Platform Tools" } } }, enabled = !actions.working && !busy && ready == null) { Text("保存并检查") }
                OutlinedButton(onClick = { actions.run { val found = withContext(Dispatchers.IO) { AdbLocator.downloadPlatformTools(graph.dataDirectory) }; location = found; adbPath = found.path; withContext(Dispatchers.IO) { graph.settings.update { it.copy(adbPath = found.path) } }; graph.log.add("Platform Tools 已下载并配置") } }, enabled = !actions.working && !busy && ready == null) { Text("下载 Platform Tools") }
            }
            location?.let { Detail("来源", it.source); Detail("版本", it.version) }
            Text("更换 ADB 前请先断开设备。下载使用 Android 官方 Platform Tools。", color = Muted, fontSize = 12.sp)
        }
        Panel("截图与触控") {
            ready?.let { device ->
                Detail("截图方式", device.selectedMode.name)
                device.timing.forEach { timing -> Detail(timing.mode.name, "p50 ${timing.p50Millis} ms · p95 ${timing.p95Millis} ms") }
                Detail("触控", if (device.touchReady) "就绪" else "未就绪，仅可使用只读功能")
                Detail("屏幕状态", if (device.awake) "已唤醒" else "未唤醒")
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(settings.touchMode == PreferredTouchMode.MAATOUCH, { actions.run { withContext(Dispatchers.IO) { graph.settings.update { it.copy(touchMode = PreferredTouchMode.MAATOUCH) } } } }, enabled = !actions.working && !busy)
                Text("MaaTouch", Modifier.weight(1f))
                RadioButton(settings.touchMode == PreferredTouchMode.COMPATIBILITY, { actions.run { withContext(Dispatchers.IO) { graph.settings.update { it.copy(touchMode = PreferredTouchMode.COMPATIBILITY) } } } }, enabled = !actions.working && !busy)
                Text("兼容模式", Modifier.weight(1f))
            }
            Text("MaaTouch 支持独立按住与滑动；兼容模式使用 ADB 输入，动作更慢。切换后重新连接生效。", color = Muted, fontSize = 12.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = { actions.run { checkNotNull(ready); graph.disconnectDevice(); graph.connectDevice(ready.serial) } }, enabled = ready != null && !busy && !actions.working) { Text("重新连接与测速") }
                OutlinedButton(onClick = { actions.run {
                    val current = checkNotNull(ready)
                    val foreground = graph.device.queryForeground() ?: error("无法确定前台应用，未执行点击")
                    check(GameServer.entries.none { it.packageName == foreground }) { "测试点击仅允许在游戏以外的前台页面执行" }
                    when (val result = graph.device.tap(current.width / 2f, current.height / 2f, foreground)) {
                        TouchResult.Completed -> graph.log.add("已在非游戏前台页面中央执行一次测试点击")
                        is TouchResult.Rejected -> error(result.reason)
                    }
                } }, enabled = ready != null && !busy && !actions.working) { Text("测试点击") }
            }
        }
        Panel("设备画像与客户端") {
            val snapshot = graph.identityStore.current()
            if (snapshot == null) Text("连接完成后会读取模拟器信息。", color = Muted)
            else {
                Detail("设备", "${snapshot.brand} ${snapshot.model}")
                Detail("系统", "Android ${snapshot.release} · API ${snapshot.sdkInt}")
                Detail("设备标识", if (snapshot.androidId.isBlank()) "未提供（使用安装标识回落）" else "••••${snapshot.androidId.takeLast(4)}")
                Detail("尺寸", "${snapshot.displayWidth} × ${snapshot.displayHeight}")
                if (snapshot.packages.isEmpty()) Text("未检测到支持的游戏客户端", color = MaterialTheme.colorScheme.error)
                snapshot.packages.forEach { (pkg, identity) -> Detail(GameServer.entries.firstOrNull { it.packageName == pkg }?.displayName ?: pkg, "${identity.versionName} (${identity.versionCode})") }
            }
        }
    }
}

@Composable internal fun DeviceConnectionPanel(graph: DesktopAppGraph) {
    val busy = LocalTaskBusy.current
    val state by graph.device.state.collectAsState()
    val actions = rememberActions(graph)
    var devices by remember { mutableStateOf<List<DeviceDescriptor>>(emptyList()) }
    var address by remember { mutableStateOf(graph.settings.state.value.lastDevice) }
    Panel("连接模拟器") {
        Text(deviceLabel(state), color = if (state is DeviceConnectionState.Ready) Color(0xff258469) else Muted)
        Message(actions.message)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(address, { address = it }, modifier = Modifier.weight(1f), singleLine = true, label = { Text("设备序列号或本机地址：端口") }, enabled = !actions.working && !busy)
            Button(onClick = { actions.run { require(address.isNotBlank()) { "请先选择设备或填写地址" }; graph.connectDevice(address.trim()) } }, enabled = address.isNotBlank() && !actions.working && !busy) { Text("连接") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = { actions.run { devices = graph.device.discover(); if (devices.isEmpty()) actions.message = "没有发现可用设备，请启动模拟器并开启 ADB 调试，或填写本机连接地址。" } }, enabled = !actions.working && !busy) { Text("重新检测") }
            OutlinedButton(onClick = { actions.run { graph.disconnectDevice() } }, enabled = state is DeviceConnectionState.Ready && !actions.working && !busy) { Text("断开") }
        }
        if (actions.working) LinearProgressIndicator(Modifier.fillMaxWidth())
        devices.forEach { device -> Surface(onClick = { address = device.serial }, color = Background, shape = RoundedCornerShape(8.dp)) {
            Row(Modifier.fillMaxWidth().padding(10.dp)) { Text(device.label, Modifier.weight(1f)); Text("${device.serial} · ${device.state}", color = Muted, fontSize = 12.sp) }
        } }
    }
}

@Composable internal fun SettingsPage(graph: DesktopAppGraph, openWizard: () -> Unit, openDevices: () -> Unit) {
    val saved by graph.settings.state.collectAsState()
    val device by graph.device.state.collectAsState()
    val ocr by graph.ocr.status.collectAsState()
    val ready = (device as? DeviceConnectionState.Ready)?.device
    val actions = rememberActions(graph)
    var encryption by remember { mutableStateOf("尚未检查") }
    var networkResult by remember { mutableStateOf<String?>(null) }
    var updateResult by remember { mutableStateOf<String?>(null) }
    PageBody("设置与环境", "查看任务前置条件和桌面偏好，每项检查都在此处反馈。") {
        Message(actions.message)
        Panel("运行环境") {
            Detail("模拟器连接", deviceLabel(device))
            Detail("截图", ready?.timing?.firstOrNull { it.mode == ready.selectedMode }?.let { "p95 ${it.p95Millis} ms${if (it.p95Millis > 600) "，偏慢" else ""}" } ?: "未完成测速")
            Detail("分辨率", ready?.let { "${it.width} × ${it.height}${if (it.width != 1920 || it.height != 1080) "，建议 1920 × 1080 横屏" else ""}" } ?: "未读取")
            Detail("触控", if (ready?.touchReady == true) if (saved.touchMode == PreferredTouchMode.COMPATIBILITY) "兼容模式（速度较慢）" else "MaaTouch 就绪" else "未就绪")
            Detail("屏幕", if (ready == null) "未读取" else if (ready.awake) "已唤醒" else "未唤醒")
            Detail("游戏客户端", when (val client = graph.gameClientResolution) {
                is GameClientResolution.Available -> "${client.server.displayName} 已安装"
                is GameClientResolution.NotInstalled -> "请在模拟器中安装 ${client.server.displayName} 客户端"
                is GameClientResolution.Ambiguous -> "检测到多个客户端，请先选择账号"
                is GameClientResolution.UnknownServer -> "账号服务器无法识别，请在账号库重新选择"
                GameClientResolution.Unavailable -> "未检测到支持的游戏客户端"
            })
            Detail("前台应用", graph.device.foregroundPackage ?: "尚未读取")
            Detail("OCR", when (ocr) { OcrEngineStatus.Ready -> "就绪"; OcrEngineStatus.Loading -> "正在加载"; OcrEngineStatus.NotLoaded -> "尚未加载"; is OcrEngineStatus.Failed -> (ocr as OcrEngineStatus.Failed).reason })
            Detail("凭据加密", encryption)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = openDevices) { Text("设备设置") }
                OutlinedButton(onClick = { actions.run { encryption = withContext(Dispatchers.IO) { val fixture = "desktop-self-check".toByteArray(); val entropy = "fixture".toByteArray(); check(WindowsDpapi.unprotect(WindowsDpapi.protect(fixture, entropy), entropy).contentEquals(fixture)); "DPAPI 当前用户自检通过" } } }, enabled = !actions.working) { Text("检查加密") }
                OutlinedButton(onClick = { actions.run { graph.device.queryForeground() ?: error("前台应用暂时无法读取") } }, enabled = ready != null && !actions.working) { Text("刷新前台状态") }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(saved.gameSettingsConfirmed, { checked -> actions.run { withContext(Dispatchers.IO) { graph.settings.update { it.copy(gameSettingsConfirmed = checked) } } } })
                Text("我已在游戏黎明界设置中关闭“播放角色加入动画”", fontSize = 12.sp)
            }
        }
        Panel("网络与窗口") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(saved.networkMode == NetworkMode.SYSTEM, { actions.run { withContext(Dispatchers.IO) { graph.settings.update { it.copy(networkMode = NetworkMode.SYSTEM) } } } }); Text("跟随系统代理", Modifier.weight(1f))
                RadioButton(saved.networkMode == NetworkMode.DIRECT, { actions.run { withContext(Dispatchers.IO) { graph.settings.update { it.copy(networkMode = NetworkMode.DIRECT) } } } }); Text("直连", Modifier.weight(1f))
            }
            Text("支持静态系统代理；Java 不支持 PAC 自动配置脚本。模式切换后对新请求生效。", color = Muted, fontSize = 12.sp)
            OutlinedButton(onClick = { actions.run { networkResult = withContext(Dispatchers.IO) { OkHttpClient.Builder().callTimeout(10, java.util.concurrent.TimeUnit.SECONDS).build().newCall(Request.Builder().url("https://line1-sdk-center-login-sh.biligame.net/").head().build()).execute().use { "SDK 主机可连接（HTTP ${it.code}）" } } } }, enabled = !actions.working) { Text("测试 SDK 连通性") }
            Message(networkResult)
            Text("任务运行时关闭窗口", fontSize = 12.sp)
            CloseBehavior.entries.forEach { choice -> Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(saved.closeBehavior == choice, { actions.run { withContext(Dispatchers.IO) { graph.settings.update { it.copy(closeBehavior = choice) } } } })
                Text(when (choice) { CloseBehavior.ASK -> "每次询问"; CloseBehavior.MINIMIZE -> "最小化到托盘"; CloseBehavior.STOP_AND_EXIT -> "停止任务并退出" })
            } }
        }
        Panel("数据库与本地数据") {
            Detail("数据目录", graph.dataDirectory.toString())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { actions.run { withContext(Dispatchers.IO) { Desktop.getDesktop().open(graph.dataDirectory.toFile()) } } }) { Text("打开数据目录") }
                OutlinedButton(onClick = { actions.run { graph.captureService.stopAndJoin("用户停止截图") } }, enabled = !actions.working) { Text("停止截图") }
                OutlinedButton(onClick = { actions.run { updateResult = withContext(Dispatchers.IO) { when (val result = graph.labyrinthCnDatabaseRepository.updateIfNeeded()) {
                    is LabyrinthCnDatabaseUpdateResult.Updated -> "国服数据库已更新：${result.metadata.version}"
                    is LabyrinthCnDatabaseUpdateResult.UpToDate -> "已经是最新版本：${result.metadata.version}"
                    is LabyrinthCnDatabaseUpdateResult.Failed -> result.reason
                } } } }, enabled = !actions.working) { Text("检查国服数据库") }
            }
            Message(updateResult)
        }
        Panel("关于") {
            Text("黎明界助手 ${AppVersion.name} · Windows 社区移植")
            Text("原项目：wbero/limingjie-assistant。业务识别、策略与协议继续使用上游实现。", color = Muted)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = openWizard) { Text("重新打开使用向导") }
                TextButton(onClick = { BrowserLauncher.open("https://github.com/wbero/limingjie-assistant") }) { Text("上游项目") }
                TextButton(onClick = { BrowserLauncher.open("https://github.com/wbero/limingjie-assistant/blob/main/LICENSE") }) { Text("许可证") }
                TextButton(onClick = { BrowserLauncher.open("https://github.com/wbero/limingjie-assistant/blob/main/THIRD_PARTY_NOTICES.md") }) { Text("第三方声明") }
            }
        }
    }
}

@Composable internal fun DiagnosticsPage(graph: DesktopAppGraph) {
    val lines by graph.log.lines.collectAsState()
    val actions = rememberActions(graph)
    var crashes by remember { mutableStateOf<List<File>>(emptyList()) }
    LaunchedEffect(Unit) { crashes = withContext(Dispatchers.IO) { graph.dataDirectory.resolve("logs").toFile().listFiles()?.filter { it.name.startsWith("crash-") }?.sortedByDescending { it.lastModified() }.orEmpty() } }
    PageBody("日志与诊断", "查看任务事件，或导出包含当前状态与运行记录的诊断包。") {
        Message(actions.message)
        Panel("诊断面板") {
            Text(graph.debugUrl ?: "没有可用诊断端口（8765–8775），请关闭占用端口的程序后重启助手。", color = Muted)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { graph.debugUrl?.let(BrowserLauncher::open) }, enabled = graph.debugUrl != null) { Text("在浏览器打开") }
                OutlinedButton(onClick = { actions.run {
                    val url = checkNotNull(graph.debugUrl) { "诊断面板不可用" } + "logs.zip"
                    val download = withContext(Dispatchers.IO) {
                        OkHttpClient.Builder().callTimeout(60, java.util.concurrent.TimeUnit.SECONDS).build().newCall(Request.Builder().url(url).build()).execute().use { response ->
                            check(response.isSuccessful) { "诊断包导出失败：HTTP ${response.code}" }
                            val name = Regex("filename=\"([^\"]+)\"").find(response.header("Content-Disposition").orEmpty())?.groupValues?.get(1) ?: "limingjie-debug.zip"
                            name to checkNotNull(response.body).bytes()
                        }
                    }
                    chooseFile(true, "保存诊断包", "zip", download.first)?.let { target ->
                        withContext(Dispatchers.IO) { Files.write(target.toPath(), download.second) }
                        graph.log.add("诊断包已导出：${target.name}")
                    }
                } }, enabled = graph.debugUrl != null && !actions.working) { Text("导出诊断包") }
                OutlinedButton(onClick = { actions.run { withContext(Dispatchers.IO) { Desktop.getDesktop().open(Files.createDirectories(graph.dataDirectory.resolve("logs")).toFile()) } } }) { Text("打开日志目录") }
            }
            if (actions.working) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        Panel("任务日志 · ${lines.size} 条") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { copyText(graph.log.text()) }) { Text("复制全部") }
                TextButton(onClick = graph.log::clear) { Text("清空") }
            }
            if (lines.isEmpty()) Text("暂无任务日志", color = Muted)
            Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                lines.asReversed().forEach { line -> Text("${line.time}  ${line.text}", fontSize = 12.sp, color = if (line.error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface) }
            }
        }
        Panel("最近崩溃记录") {
            if (crashes.isEmpty()) Text("没有崩溃记录", color = Muted)
            crashes.take(10).forEach { file -> TextButton(onClick = { actions.run { withContext(Dispatchers.IO) { Desktop.getDesktop().open(file) } } }) { Text(file.name) } }
        }
    }
}

internal fun chooseFile(save: Boolean, title: String, extension: String, suggested: String? = null): File? {
    val chooser = JFileChooser().apply { dialogTitle = title; fileFilter = FileNameExtensionFilter(extension.uppercase(), extension); suggested?.let { selectedFile = File(it) } }
    if ((if (save) chooser.showSaveDialog(null) else chooser.showOpenDialog(null)) != JFileChooser.APPROVE_OPTION) return null
    val file = chooser.selectedFile
    if (save && file.exists() && javax.swing.JOptionPane.showConfirmDialog(null, "替换已有文件“${file.name}”？", "确认保存", javax.swing.JOptionPane.YES_NO_OPTION) != javax.swing.JOptionPane.YES_OPTION) return null
    return file
}

@Composable internal fun SetupWizard(graph: DesktopAppGraph, onFinished: () -> Unit) {
    var step by remember { mutableStateOf(0) }
    var accepted by remember { mutableStateOf(false) }
    var prepared by remember { mutableStateOf(false) }
    val actions = rememberActions(graph)
    Dialog(onDismissRequest = {}, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnBackPress = false, dismissOnClickOutside = false)) {
        Surface(Modifier.width(800.dp).fillMaxHeight(.9f), shape = RoundedCornerShape(16.dp)) {
            Column(Modifier.padding(28.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("欢迎使用黎明界助手", style = MaterialTheme.typography.titleLarge)
                Text("${step + 1} / 4  ·  ${listOf("使用风险", "模拟器准备", "连接设备", "开始使用")[step]}", color = Accent)
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    when (step) {
                        0 -> {
                            Text("这是 Windows 社区移植版。以下使用风险沿用上游说明：", color = Muted)
                            Text("当前为测试版本，流程可能卡死、停在未知页面、误识别或误操作，不保证自动完成。仅测试过 MuMu 模拟器（B 服）与雷电模拟器 9（小米、华为、vivo 渠道服）、1920 × 1080 分辨率，手机及其他环境暂未测试。请留意运行状态，出现异常及时暂停，不建议无人值守运行。")
                            Text("本项目与游戏官方无关。自动操作和非官方接口访问可能带来账号限制或封禁风险，不保证账号安全；使用前请自行确认游戏相关规则。")
                            Text("角色选择、商店购买、刷新、战斗及彻底撤退会改变真实游戏状态，可能消耗资源或丢失当前开局。启动前核对账号、策略和撤退选项。")
                            Text("“已执行”仅表示操作已派发，不代表游戏已完成预期操作。当前角色列表滚动、边界回弹、识别与流程衔接仍可能异常，需要人工接管。")
                            Text("请勿公开账号密码、访问令牌、验证码、未脱敏日志或包含个人信息的截图。交流群不是账号托管渠道。")
                            Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(accepted, { accepted = it }); Text("我已阅读并理解") }
                        }
                        1 -> {
                            Text("推荐 MuMu 12 或雷电模拟器 9，使用 1920 × 1080 横屏。")
                            Text("在模拟器的设置中启用 ADB 调试并保持模拟器运行。记录本机 ADB 连接端口；不同模拟器实例的端口可能不同。")
                            Text("助手的账号、配置与控制都在 Windows 内完成，模拟器只需安装游戏。游戏内黎明界设置请关闭“播放角色加入动画”。")
                            Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(prepared, { prepared = it }); Text("我已完成模拟器准备") }
                        }
                        2 -> { DeviceConnectionPanel(graph); Text("可以跳过连接，稍后在设备页选择 ADB 并连接模拟器。", color = Muted) }
                        3 -> {
                            Text("先添加并选择账号，再进入任务页。")
                            Text("只读识别（dryRun）只分析游戏画面，不发送触控操作；单次执行与批量自动执行会实际操作游戏，可能消耗游戏资源。")
                            Text("运行期间保持模拟器游戏处于前台；窗口底部与托盘可以暂停、继续或停止。刷开局网络任务只提供停止。")
                            Text("验证码在系统浏览器中由你手动完成。策略修改必须保存后，才会在下一次任务启动时生效。")
                        }
                    }
                    Message(actions.message)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (step > 0) OutlinedButton(onClick = { step-- }, enabled = !actions.working) { Text("上一步") }
                    Spacer(Modifier.weight(1f))
                    Button(onClick = { if (step < 3) step++ else actions.run { withContext(Dispatchers.IO) { graph.settings.update { it.copy(wizardCompleted = true) } }; onFinished() } },
                        enabled = !actions.working && (step != 0 || accepted) && (step != 1 || prepared)) { Text(if (step == 3) "进入助手" else if (step == 2) "继续 / 稍后连接" else "继续") }
                }
            }
        }
    }
}
