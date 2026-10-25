package com.landosol.toolbox.desktop.app

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.*
import com.landosol.toolbox.AppVersion
import com.landosol.toolbox.desktop.app.adapters.storage.DatabaseRecovery
import com.landosol.toolbox.desktop.app.ui.DesktopApp
import com.landosol.toolbox.desktop.platform.*
import com.landosol.toolbox.desktop.shared.*
import java.awt.Dimension
import java.awt.GraphicsEnvironment
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.JOptionPane
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlin.system.exitProcess

@Volatile private var crashGraph: DesktopAppGraph? = null
private val crashDialogOpen = java.util.concurrent.atomic.AtomicBoolean(false)

fun main(args: Array<String>) {
    System.setProperty("java.net.useSystemProxies", "true")
    if ("--self-test" in args) { exitProcess(DesktopSelfTest.run(args)) }
    val messages = mutableListOf<String>()
    var lock: InstanceLock? = null
    val graph = try {
        val directory = DataDirectories.resolve(args)
        DataDirectories.ensureWritable(directory)
        lock = InstanceLock.acquire(directory)
        DataDirectories.cleanTemporaryFiles(directory)
        DesktopLog.initialize(directory.resolve("logs"))
        installCrashHandler(directory)
        val database = runBlocking { DatabaseRecovery.open(DesktopContext(directory.toFile())) { messages.add(it) } }
        DesktopAppGraph(directory, database).also { app -> crashGraph = app; messages.forEach(app::addNotice) }
    } catch (failure: Exception) {
        lock?.close()
        JOptionPane.showMessageDialog(null, failure.message ?: "启动失败", "黎明界助手", JOptionPane.ERROR_MESSAGE)
        exitProcess(1)
    }
    val instanceLock = lock!!
    application {
        val saved = graph.settings.state.value
        val restorePosition = saved.windowX?.let { x -> saved.windowY?.let { y ->
            GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices.any { device -> device.defaultConfiguration.bounds.intersects(java.awt.Rectangle(x, y, saved.windowWidth, saved.windowHeight)) }
                .takeIf { it }?.let { WindowPosition.Absolute(x.dp, y.dp) }
        } } ?: WindowPosition(Alignment.Center)
        val windowState = rememberWindowState(position = restorePosition, width = saved.windowWidth.coerceAtLeast(1100).dp, height = saved.windowHeight.coerceAtLeast(700).dp)
        var visible by remember { mutableStateOf(true) }
        var confirmExit by remember { mutableStateOf(false) }
        var exiting by remember { mutableStateOf(false) }
        var rememberChoice by remember { mutableStateOf(false) }
        val scope = rememberCoroutineScope()
        var trayCommandPending by remember { mutableStateOf(false) }
        var wasTaskActive by remember { mutableStateOf(false) }
        val presentation by graph.automationOverlayCoordinator.state.collectAsState()
        val reroll by graph.labyrinthController.uiState.collectAsState()
        val batch by graph.labyrinthBatchController.state.collectAsState()
        val recognition by graph.labyrinthEntryRecognitionSession.state.collectAsState()
        val reset by graph.gameSessionResetWorkflow.state.collectAsState()
        val starting by graph.taskStarting.collectAsState()
        val stopping by graph.taskStopping.collectAsState()
        val notices by graph.notices.collectAsState()
        val taskActive = starting || stopping || reroll.isWorking || reroll.captcha != null || presentation != null || recognition.running || reset.running || batch?.stage?.name in setOf("REROLLING", "INVALIDATING_OLD_CLIENT_SESSION", "RUNNING_LABYRINTH", "RECORDING_RESULT")
        val trayState = rememberTrayState()
        val trayAvailable = java.awt.SystemTray.isSupported()
        val icon = remember { AppPainter() }
        fun quit() {
            if (exiting) return
            exiting = true
            scope.launch {
                try { withContext(Dispatchers.IO) { graph.shutdown() } }
                finally { instanceLock.close(); exitApplication(); exitProcess(0) }
            }
        }
        fun closeRequested() {
            if (!taskActive) quit()
            else when (graph.settings.state.value.closeBehavior) {
                CloseBehavior.MINIMIZE -> if (trayAvailable) visible = false else windowState.isMinimized = true
                CloseBehavior.STOP_AND_EXIT -> quit()
                CloseBehavior.ASK -> confirmExit = true
            }
        }
        LaunchedEffect(windowState) {
            snapshotFlow { Triple(windowState.position, windowState.size, windowState.placement) }.debounce(500).collect { (position, size, placement) ->
                if (placement == WindowPlacement.Floating && size.width.value.isFinite() && size.height.value.isFinite()) {
                    withContext(Dispatchers.IO) { graph.settings.update { it.copy(windowWidth = size.width.value.toInt(), windowHeight = size.height.value.toInt(), windowX = position.x.value.toInt(), windowY = position.y.value.toInt()) } }
                }
            }
        }
        LaunchedEffect(taskActive) {
            if (!taskActive && wasTaskActive) trayState.sendNotification(Notification("黎明界助手", recognition.message ?: reroll.message ?: "任务已结束"))
            wasTaskActive = taskActive
        }
        if (trayAvailable) Tray(icon, state = trayState, tooltip = "黎明界助手 · ${presentation?.status ?: reroll.progress ?: "空闲"}", onAction = { visible = true; windowState.isMinimized = false }) {
            Item("显示主窗口", onClick = { visible = true; windowState.isMinimized = false })
            Separator()
            Item(if (presentation?.paused == true) "继续" else "暂停", enabled = presentation != null && !exiting && !trayCommandPending && !stopping, onClick = {
                presentation?.let { current ->
                    trayCommandPending = true
                    scope.launch { try { graph.automationOverlayCoordinator.setPaused(current.sessionId, !current.paused) } finally { trayCommandPending = false } }
                }
            })
            Item("停止", enabled = taskActive && !exiting && !trayCommandPending && !stopping, onClick = {
                trayCommandPending = true
                scope.launch { try { graph.stopAll().join() } finally { trayCommandPending = false } }
            })
            Separator()
            Item("退出", onClick = { if (taskActive) { visible = true; confirmExit = true } else quit() })
        }
        Window(onCloseRequest = ::closeRequested, state = windowState, visible = visible, title = "黎明界助手 ${AppVersion.name}", icon = icon) {
            window.minimumSize = Dimension(1100, 700)
            DesktopApp(graph)
            MaterialTheme {
                if (notices.isNotEmpty()) AlertDialog(onDismissRequest = graph::dismissNotices,
                    title = { Text("本地数据恢复提示") }, text = { Text(notices.joinToString("\n\n")) },
                    confirmButton = { TextButton(onClick = graph::dismissNotices) { Text("我知道了") } })
                if (confirmExit) AlertDialog(
                    onDismissRequest = { if (!exiting) confirmExit = false }, title = { Text("任务正在运行") },
                    text = { Column { Text("关闭窗口后，可以让任务继续运行，也可以停止任务并退出。"); Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(rememberChoice, { rememberChoice = it }); Text("记住我的选择") } } },
                    confirmButton = { TextButton(enabled = !exiting, onClick = {
                        if (rememberChoice) graph.settings.update { it.copy(closeBehavior = CloseBehavior.STOP_AND_EXIT) }
                        quit()
                    }) { Text(if (exiting) "正在停止…" else "停止任务并退出") } },
                    dismissButton = { Row {
                        TextButton(enabled = !exiting, onClick = {
                            if (rememberChoice) graph.settings.update { it.copy(closeBehavior = CloseBehavior.MINIMIZE) }
                            confirmExit = false
                            if (trayAvailable) visible = false else windowState.isMinimized = true
                        }) { Text("最小化到托盘") }
                        TextButton(enabled = !exiting, onClick = { confirmExit = false }) { Text("取消") }
                    } },
                )
            }
        }
    }
}

/** The upstream baseline has no launcher art; this simple desktop mark has no external asset. */
private class AppPainter : Painter() {
    override val intrinsicSize = Size(64f, 64f)
    override fun DrawScope.onDraw() {
        drawRoundRect(Color(0xFF7560BD), cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.width * .24f))
        drawCircle(Color.White, size.minDimension * .27f)
        drawCircle(Color(0xFF7560BD), size.minDimension * .22f, center + androidx.compose.ui.geometry.Offset(size.width * .11f, -size.height * .07f))
        drawCircle(Color(0xFFFFDC92), size.minDimension * .07f, center + androidx.compose.ui.geometry.Offset(size.width * .22f, size.height * .2f))
    }
}

private fun installCrashHandler(directory: Path) {
    Thread.setDefaultUncaughtExceptionHandler { thread, failure ->
        runCatching {
            val crashes = directory.resolve("logs/crashes"); Files.createDirectories(crashes)
            val path = crashes.resolve("crash-${System.currentTimeMillis()}.log")
            Files.writeString(path, DesktopLog.redact("${AppVersion.display}\nJVM: ${System.getProperty("java.version")}\nOS: ${System.getProperty("os.name")} ${System.getProperty("os.arch")}\nThread: ${thread.name}\n${failure.stackTraceToString()}"))
            DesktopLog.write('E', "Crash", "线程 ${thread.name} 未处理异常", failure)
            if (crashDialogOpen.compareAndSet(false, true)) javax.swing.SwingUtilities.invokeLater {
                try {
                    val choice = JOptionPane.showOptionDialog(null, "程序发生异常。诊断记录已保存：\n$path", "黎明界助手", JOptionPane.DEFAULT_OPTION,
                        JOptionPane.ERROR_MESSAGE, null, arrayOf("导出诊断包", "打开日志目录", "关闭"), "关闭")
                    runCatching {
                        when (choice) {
                            0 -> crashGraph?.debugUrl?.let { BrowserLauncher.open("${it}logs.zip") }
                            1 -> java.awt.Desktop.getDesktop().open(directory.resolve("logs").toFile())
                        }
                    }
                } finally { crashDialogOpen.set(false) }
            }
        }
    }
}
