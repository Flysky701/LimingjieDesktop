package com.landosol.toolbox.desktop.app.ui

import android.content.res.Configuration
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.landosol.toolbox.AppVersion
import com.landosol.toolbox.desktop.app.DesktopAppGraph
import com.landosol.toolbox.desktop.device.DeviceConnectionState
import com.landosol.toolbox.desktop.shared.desktopNotification
import com.landosol.toolbox.labyrinth.labyrinthRerollStatusText
import kotlinx.coroutines.*
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.awt.image.BufferedImage

internal val Accent = Color(0xff6952d5)
internal val Muted = Color(0xff77798b)
internal val Background = Color(0xfff6f7fa)
internal val Line = Color(0xffe9eaf0)
internal val LocalTaskBusy = staticCompositionLocalOf { false }
internal enum class Page(val label: String, val mark: String) {
    Overview("总览", "◈"), Tasks("黎明界任务", "▶"), Accounts("账号库", "▤"),
    Strategies("策略与编组", "◇"), Devices("设备", "▣"), Logs("日志与诊断", "≡"), Settings("设置与环境", "⚙")
}

@Composable
@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
fun DesktopApp(graph: DesktopAppGraph) {
    val font = remember { if (java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().availableFontFamilyNames.any { it == "Microsoft YaHei UI" }) FontFamily("Microsoft YaHei UI") else FontFamily.Default }
    MaterialTheme(colorScheme = lightColorScheme(primary = Accent, primaryContainer = Color(0xffefecfb),
        onPrimaryContainer = Accent, background = Background, surface = Color.White,
        onSurface = Color(0xff252638), outlineVariant = Line, error = Color(0xffbd485e)),
        typography = Typography(bodyLarge = androidx.compose.ui.text.TextStyle(fontFamily = font, fontSize = 14.sp),
            bodyMedium = androidx.compose.ui.text.TextStyle(fontFamily = font, fontSize = 13.sp),
            bodySmall = androidx.compose.ui.text.TextStyle(fontFamily = font, fontSize = 12.sp),
            labelLarge = androidx.compose.ui.text.TextStyle(fontFamily = font, fontSize = 13.sp),
            titleSmall = androidx.compose.ui.text.TextStyle(fontFamily = font, fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
            titleLarge = androidx.compose.ui.text.TextStyle(fontFamily = font, fontSize = 23.sp, fontWeight = FontWeight.SemiBold),
            titleMedium = androidx.compose.ui.text.TextStyle(fontFamily = font, fontSize = 16.sp, fontWeight = FontWeight.SemiBold))) {
        var page by remember { mutableStateOf(Page.Overview) }
        val settings by graph.settings.state.collectAsState()
        var reopenWizard by remember { mutableStateOf(false) }
        val device by graph.device.state.collectAsState()
        val accounts by graph.accounts.uiState.collectAsState()
        val task by graph.labyrinthController.uiState.collectAsState()
        val overlay by graph.automationOverlayCoordinator.state.collectAsState()
        val entry by graph.labyrinthEntryRecognitionSession.state.collectAsState()
        val batch by graph.labyrinthBatchController.state.collectAsState()
        val reset by graph.gameSessionResetWorkflow.state.collectAsState()
        val starting by graph.taskStarting.collectAsState()
        val stopping by graph.taskStopping.collectAsState()
        val busy = starting || stopping || task.isWorking || task.captcha != null || entry.running || reset.running || graph.busy
        Surface(Modifier.fillMaxSize(), color = Background) {
            Row {
                Surface(Modifier.width(176.dp).fillMaxHeight(), color = Color.White) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Row(Modifier.padding(vertical = 22.dp), verticalAlignment = Alignment.CenterVertically) {
                            Surface(shape = RoundedCornerShape(10.dp), color = Accent) { Text("黎", Modifier.padding(10.dp), color = Color.White, fontWeight = FontWeight.Bold) }
                            Column(Modifier.padding(start = 9.dp)) { Text("黎明界助手", fontWeight = FontWeight.SemiBold); Text("DESKTOP", fontSize = 10.sp, color = Muted, letterSpacing = 1.sp) }
                        }
                        Text("工作空间", Modifier.padding(10.dp), color = Muted, fontSize = 11.sp)
                        Page.entries.forEach { destination ->
                            Surface(onClick = { page = destination }, shape = RoundedCornerShape(8.dp),
                                color = if (page == destination) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                                modifier = Modifier.fillMaxWidth()) {
                                Row(Modifier.padding(horizontal = 12.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(11.dp)) {
                                    Text(destination.mark, color = if (page == destination) Accent else Muted)
                                    Text(destination.label, color = if (page == destination) Accent else MaterialTheme.colorScheme.onSurface)
                                }
                            }
                        }
                        Spacer(Modifier.weight(1f))
                        Text("Windows 桌面端\n单设备 · 单任务\n${AppVersion.name}", Modifier.padding(10.dp), color = Muted, fontSize = 11.sp, lineHeight = 20.sp)
                    }
                }
                VerticalDivider(color = Line)
                Column(Modifier.weight(1f)) {
                    Surface(color = Color.White) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(deviceLabel(device), Modifier.weight(1f), color = Muted, fontSize = 12.sp)
                            Text("当前账号：${accounts.accounts.firstOrNull { it.isSelected }?.alias ?: "未选择"}", fontSize = 12.sp)
                            TextButton(onClick = { page = Page.Devices }) { Text(if (device is DeviceConnectionState.Ready) "设备详情" else "连接设备") }
                        }
                    }
                    HorizontalDivider(color = Line)
                    BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                        val showRail = maxWidth > 990.dp
                        CompositionLocalProvider(LocalContext provides graph.context, LocalTaskBusy provides busy,
                            LocalConfiguration provides Configuration(maxWidth.value.toInt(), maxHeight.value.toInt())) {
                            Row(Modifier.fillMaxSize()) {
                                Box(Modifier.weight(1f).fillMaxHeight()) {
                                    when (page) {
                                        Page.Overview -> OverviewPage(graph, { page = it })
                                        Page.Tasks -> TasksPage(graph, { page = Page.Accounts }, { page = Page.Strategies })
                                        Page.Accounts -> AccountsPage(graph)
                                        Page.Strategies -> StrategiesPage(graph)
                                        Page.Devices -> DevicesPage(graph)
                                        Page.Logs -> DiagnosticsPage(graph)
                                        Page.Settings -> SettingsPage(graph, { reopenWizard = true }, { page = Page.Devices })
                                    }
                                }
                                if (showRail && page in listOf(Page.Overview, Page.Tasks)) {
                                    VerticalDivider(color = Line)
                                    TaskRail(graph, Modifier.width(250.dp))
                                }
                            }
                            if (!settings.wizardCompleted || reopenWizard) {
                                SetupWizard(graph) { reopenWizard = false }
                            }
                            if (page != Page.Tasks) task.captcha?.let { captcha ->
                                com.landosol.toolbox.ui.account.GeetestCaptchaDialog(captcha, task.isWorking,
                                    graph.labyrinthController::submitCaptcha, graph.labyrinthController::reportCaptchaError,
                                    graph.labyrinthController::cancelCaptcha)
                            }
                        }
                    }
                    HorizontalDivider(color = Line)
                    val commands = rememberActions(graph)
                    Surface(color = Color.White) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Column(Modifier.weight(1f)) {
                                Text(overlay?.desktopNotification()?.title ?: if (task.isWorking) "黎明界刷开局" else if (busy) "任务进行中" else "准备就绪", fontWeight = FontWeight.SemiBold)
                                Text(overlay?.desktopNotification()?.summary ?: if (task.isWorking) labyrinthRerollStatusText(task) else if (busy) task.message ?: "正在准备任务" else "选择账号和任务后开始", color = Muted, fontSize = 12.sp, maxLines = 2)
                            }
                            overlay?.let { current ->
                                OutlinedButton(onClick = { commands.run { graph.automationOverlayCoordinator.setPaused(current.sessionId, !current.paused) } }, enabled = !commands.working) {
                                    Text(if (current.paused) "继续" else "暂停")
                                }
                            }
                            Button(onClick = { commands.run { graph.stopAll().join() } }, enabled = busy && !stopping && !commands.working) { Text(if (commands.working || stopping) "正在停止…" else "停止任务") }
                        }
                    }
                }
            }
        }
    }
}

internal class UiActions(private val scope: CoroutineScope, private val graph: DesktopAppGraph) {
    var working by mutableStateOf(false); private set
    var message by mutableStateOf<String?>(null)
    fun run(block: suspend () -> Unit) {
        if (working) return
        scope.launch {
            working = true; message = null
            try { block() } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { message = failure.message ?: "操作失败，请重试"; graph.log.add(message!!, true) }
            finally { working = false }
        }
    }
}
@Composable internal fun rememberActions(graph: DesktopAppGraph): UiActions {
    val scope = rememberCoroutineScope(); return remember(graph, scope) { UiActions(scope, graph) }
}
@Composable internal fun PageBody(title: String, subtitle: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(26.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text("LIMINGJIE ASSISTANT", color = Accent, fontSize = 10.sp, letterSpacing = 1.4.sp)
            Text(title, style = MaterialTheme.typography.titleLarge)
            Text(subtitle, color = Muted, style = MaterialTheme.typography.bodySmall)
        }
        content()
    }
}
@Composable internal fun Panel(title: String, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier.fillMaxWidth(), color = Color.White, shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, Line)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}
@Composable internal fun Detail(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(label, Modifier.width(125.dp), color = Muted, fontSize = 12.sp)
        Text(value, Modifier.weight(1f), fontSize = 12.sp)
    }
}
@Composable internal fun Message(text: String?, onDismiss: (() -> Unit)? = null) {
    if (text != null) Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(8.dp)) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text, Modifier.weight(1f), color = Accent, fontSize = 12.sp)
            if (onDismiss != null) TextButton(onClick = onDismiss) { Text("关闭") }
        }
    }
}
internal fun deviceLabel(state: DeviceConnectionState): String = when (state) {
    DeviceConnectionState.Disconnected -> "未连接模拟器"
    is DeviceConnectionState.Ready -> "${state.device.label} · ${state.device.serial} · ${state.device.width} × ${state.device.height} · ${state.device.selectedMode}"
    is DeviceConnectionState.Connecting -> "正在连接 ${state.serial}"
    is DeviceConnectionState.Verifying -> "正在检查 · ${state.detail}"
    is DeviceConnectionState.Lost -> "连接已中断 · ${state.reason}"
    is DeviceConnectionState.Failed -> "连接失败 · ${state.reason}"
    is DeviceConnectionState.Reconnecting -> "正在重新连接（${state.attempt}）"
}
internal fun copyText(text: String) = Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)

@Composable private fun TaskRail(graph: DesktopAppGraph, modifier: Modifier) {
    val lines by graph.log.lines.collectAsState()
    Surface(modifier.fillMaxHeight(), color = Color.White) {
        Column(Modifier.padding(17.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("任务日志", fontWeight = FontWeight.SemiBold)
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(17.dp), reverseLayout = true) {
                items(lines.takeLast(30).reversed()) { line -> Column { Text(line.time, color = Muted, fontSize = 10.sp); Text(line.text, fontSize = 12.sp, color = if (line.error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface) } }
            }
            Preview(graph)
        }
    }
}

@Composable internal fun Preview(graph: DesktopAppGraph) {
    var expanded by remember { mutableStateOf(false) }
    val frame by graph.preview.collectAsState()
    val overlay by graph.automationOverlayCoordinator.state.collectAsState()
    val actions = rememberActions(graph)
    OutlinedButton(onClick = { expanded = !expanded; if (expanded) actions.run { graph.startPreview() } }, modifier = Modifier.fillMaxWidth()) { Text(if (expanded) "收起画面预览" else "展开画面预览") }
    if (expanded) {
        val bitmap = remember(frame) { frame?.let { pixels -> BufferedImage(pixels.width, pixels.height, BufferedImage.TYPE_INT_ARGB).apply { setRGB(0, 0, pixels.width, pixels.height, pixels.pixels.copyOf(), 0, pixels.width) }.toComposeImageBitmap() } }
        if (bitmap == null) Text(actions.message ?: "等待模拟器画面", color = Muted, fontSize = 12.sp)
        else Box(Modifier.fillMaxWidth().aspectRatio(bitmap.width.toFloat() / bitmap.height)) {
            Image(bitmap, "模拟器最新画面", Modifier.fillMaxSize())
            Canvas(Modifier.fillMaxSize()) {
                overlay?.boxes?.forEach { box -> drawRect(if (box.selected) Accent else Color.Green,
                    topLeft = Offset(box.left * size.width, box.top * size.height), size = Size(box.width * size.width, box.height * size.height), style = Stroke(1.5.dp.toPx())) }
            }
        }
    }
}
