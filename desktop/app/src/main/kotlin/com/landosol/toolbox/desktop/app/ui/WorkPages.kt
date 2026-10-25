package com.landosol.toolbox.desktop.app.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.landosol.toolbox.account.*
import com.landosol.toolbox.desktop.app.DesktopAppGraph
import com.landosol.toolbox.desktop.device.DeviceConnectionState
import com.landosol.toolbox.labyrinth.*
import com.landosol.toolbox.protocol.bilibili.GameServer
import com.landosol.toolbox.ui.account.GeetestCaptchaDialog
import com.landosol.toolbox.ui.labyrinth.LabyrinthScreen
import com.landosol.toolbox.ui.labyrinth.LabyrinthStrategySettingsScreen
import kotlinx.coroutines.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable internal fun OverviewPage(graph: DesktopAppGraph, navigate: (Page) -> Unit) {
    val busy = LocalTaskBusy.current
    val account by graph.accounts.uiState.collectAsState()
    val device by graph.device.state.collectAsState()
    val task by graph.labyrinthController.uiState.collectAsState()
    val lines by graph.log.lines.collectAsState()
    PageBody("你的黎明界工作台", "在电脑中管理任务与策略，模拟器专注运行游戏。") {
        Panel("开始之前") {
            Detail("当前账号", task.selectedAccount?.let { "${it.alias} · ${it.serverName}" } ?: "尚未选择账号")
            Detail("模拟器", deviceLabel(device))
            Detail("任务状态", task.progress ?: if (busy) "任务进行中" else "空闲")
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = { navigate(Page.Tasks) }) { Text("前往黎明界任务") }
                OutlinedButton(onClick = { navigate(if (account.accounts.isEmpty()) Page.Accounts else Page.Devices) }) { Text(if (account.accounts.isEmpty()) "添加账号" else "连接与检查设备") }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Panel("账号库", Modifier.weight(1f)) { Text("${account.accounts.size} 个账号", style = MaterialTheme.typography.titleLarge); TextButton(onClick = { navigate(Page.Accounts) }) { Text("管理账号 →") } }
            Panel("策略与编组", Modifier.weight(1f)) { Text("所有账号共用", color = Muted); TextButton(onClick = { navigate(Page.Strategies) }) { Text("查看策略 →") } }
        }
        Panel("最近动态") {
            if (lines.isEmpty()) Text("暂无运行记录。连接模拟器并选择账号后，可以先使用只读识别。", color = Muted)
            lines.takeLast(5).reversed().forEach { line -> Detail(line.time, line.text) }
        }
        Message("首次使用建议先确认游戏内已关闭“播放角色加入动画”，并用只读识别检查画面。运行过程中请留意状态，出现异常及时暂停。")
    }
}

@Composable internal fun TasksPage(graph: DesktopAppGraph, openAccounts: () -> Unit, openStrategies: () -> Unit) {
    val busy = LocalTaskBusy.current
    val state by graph.labyrinthController.uiState.collectAsState()
    val entry by graph.labyrinthEntryRecognitionSession.state.collectAsState()
    val batch by graph.labyrinthBatchController.state.collectAsState()
    val halt by graph.labyrinthBatchController.haltReason.collectAsState()
    val device by graph.device.state.collectAsState()
    var showWorkflow by remember { mutableStateOf(false) }
    val actions = rememberActions(graph)
    PageBody("黎明界任务", "读取当前开局、设置批量目标，或按需使用单次辅助工具。") {
        Panel("当前账号") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(state.selectedAccount?.alias ?: "尚未选择账号", style = MaterialTheme.typography.titleMedium)
                    Text(state.selectedAccount?.serverName ?: "先在账号库添加并选择账号", color = Muted)
                }
                OutlinedButton(onClick = openAccounts) { Text("账号库") }
            }
        }
        Message(state.message, graph.labyrinthController::dismissMessage)
        Message(actions.message)
        Panel("当前开局") {
            Detail("读取状态", state.currentOpeningReadMessage ?: "尚未读取")
            state.verdictMessage?.let { Detail("路线判断", it) }
            state.currentDifficulty?.let { Detail("当前难度", it.toString()) }
            Button(onClick = graph.labyrinthController::checkStatus,
                enabled = state.selectedAccount != null && !busy && state.captcha == null) { Text("登录并读取") }
            Text("此操作读取助手侧会话，不会切换模拟器内的游戏账号。", color = Muted, fontSize = 12.sp)
        }
        Panel("批量自动执行 · 刷开局") {
            Detail("已保存的难度", state.selectedDifficulty.toString())
            Detail("当前执行阶段", batch?.stage?.name ?: "尚未开始")
            Detail("运行反馈", state.progress ?: entry.message ?: "配置公会目标后启动")
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = { showWorkflow = true }) { Text("配置目标与执行") }
                OutlinedButton(onClick = openStrategies) { Text("策略 · 编组 · 评分") }
            }
            Text("完整流程：刷开局 → 重置游戏会话 → 路线执行 → 记录结果。刷开局条件按账号保存，策略所有账号共用。", color = Muted, fontSize = 12.sp)
        }
        Panel("单次画面工具") {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("只读识别", fontWeight = FontWeight.SemiBold)
                    Text("分析画面与角色，不点击游戏。", color = Muted, fontSize = 12.sp)
                    OutlinedButton(onClick = { graph.startRecognition(true) }, enabled = !busy && device is DeviceConnectionState.Ready) { Text("开始只读识别") }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("单次自动执行", fontWeight = FontWeight.SemiBold)
                    Text("根据已保存的策略操作游戏。", color = Muted, fontSize = 12.sp)
                    OutlinedButton(onClick = { graph.startRecognition(false) }, enabled = !busy && device is DeviceConnectionState.Ready) { Text("执行入口流程") }
                }
            }
            if (device !is DeviceConnectionState.Ready) Text("请先在“设备”页连接模拟器。", color = MaterialTheme.colorScheme.error)
            if (entry.running) Detail("识别状态", "${entry.frameCount} 帧 · ${entry.actionCount} 次动作 · ${if (entry.dryRun) "只读" else "执行"}")
            Preview(graph)
        }
    }
    if (showWorkflow) Dialog(onDismissRequest = { showWorkflow = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize().padding(20.dp), shape = RoundedCornerShape(12.dp)) {
            Column {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("任务配置与执行", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    TextButton(onClick = { showWorkflow = false }) { Text("返回任务页") }
                }
                Box(Modifier.weight(1f)) {
                    LabyrinthScreen(state = state, entryRecognitionState = entry, batchCheckpoint = batch, batchHaltReason = halt,
                        onSaveSettings = graph.labyrinthController::saveSettings,
                        onCheckStatus = { if (!graph.busy) graph.labyrinthController.checkStatus() else graph.labyrinthController.reportMessage("请先停止当前任务") },
                        onStart = { guild -> if (!graph.busy) graph.labyrinthController.start(retreatConfirmed = true, guildIdOverride = guild) else graph.labyrinthController.reportMessage("请先停止当前任务") },
                        onStop = graph.labyrinthController::stop, onDismissMessage = graph.labyrinthController::dismissMessage,
                        onStartEntryRecognition = { graph.startRecognition(true) }, onStartEntryAutomation = { graph.startRecognition(false) },
                        onStopEntryRecognition = { actions.run { graph.stopAll("用户停止画面任务").join() } },
                        onStartAutoRun = { goals -> if (!graph.startLabyrinthAutoRun(state.selectedAccount?.id, goals)) graph.labyrinthController.reportMessage("自动执行未启动，请确认账号、设备连接、触控和 OCR 状态，并停止其他任务后重试") },
                        onStopAutoRun = graph::stopLabyrinthAutoRun,
                        onCaptchaSolved = graph.labyrinthController::submitCaptcha,
                        onCaptchaError = graph.labyrinthController::reportCaptchaError,
                        onCancelCaptcha = graph.labyrinthController::cancelCaptcha,
                        onOpenStrategies = { showWorkflow = false; openStrategies() })
                }
            }
        }
    } else state.captcha?.let { captcha ->
        GeetestCaptchaDialog(captcha, state.isWorking, graph.labyrinthController::submitCaptcha,
            graph.labyrinthController::reportCaptchaError, graph.labyrinthController::cancelCaptcha)
    }
}

@Composable internal fun StrategiesPage(graph: DesktopAppGraph) {
    val saved by graph.labyrinthStrategySettings.state.collectAsState()
    var edit by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    PageBody("策略与编组", "全局配置 · 保存后从下一次识别或自动执行生效。") {
        Panel("当前策略") {
            Detail("玩家评分", "${saved.playerWeight}%")
            Detail("个人评分", "已修改 ${saved.personalRoleScores.size} 个角色")
            Detail("Boss 编组", if (saved.bossTeamMode == LabyrinthBossTeamMode.MULTI_TEAM) "多队编组" else "单队编组")
            Detail("部署权重", "第一队 100% · 第二队 ${saved.secondTeamWeight}% · 第三队 ${saved.thirdTeamWeight}%")
            Button(onClick = { edit = true }) { Text("编辑完整策略") }
        }
        Panel("配置包含") {
            Text("评分与角色个人评分、选人部署、Boss 编组、战斗失败处理、开局阵容、遗物、专武、商店、事件、响应间隔。")
            Text("角色评分支持头像、名称/版本/ID 搜索、0–100 分和恢复原评分。开局编组与评分都保留独立保存/取消与校验。", color = Muted)
        }
    }
    if (edit) LabyrinthStrategySettingsScreen(saved = saved, saving = saving, message = message,
        loadRoleRatings = graph::loadLabyrinthRoleRatings, onClose = { edit = false; message = null },
        onSave = { value -> if (!saving) {
            saving = true; message = null
            scope.launch {
                try { graph.labyrinthStrategySettings.save(value); graph.log.add("策略配置已保存，下次启动生效") }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { message = failure.message ?: "保存失败，请重试" }
                finally { saving = false }
            }
        } })
}

@Composable internal fun AccountsPage(graph: DesktopAppGraph) {
    val busy = LocalTaskBusy.current
    val vm = graph.accounts
    val state by vm.uiState.collectAsState()
    val connection by graph.device.state.collectAsState()
    var focusId by remember { mutableStateOf<Long?>(null) }
    val focus = state.accounts.firstOrNull { it.id == focusId } ?: state.accounts.firstOrNull { it.isSelected } ?: state.accounts.firstOrNull()
    val snapshot = graph.identityStore.current()
    PageBody("账号库", "在当前 Windows 用户下加密保存凭据。选择账号后，任务按需登录。") {
        Message(snapshot?.let { "设备画像：${it.brand} ${it.model} · ${DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(it.capturedAtMillis))}${if (connection is DeviceConnectionState.Ready) "（当前设备）" else "（上次连接设备）"}" }
            ?: "尚未读取模拟器设备信息，请先在“设备”页连接模拟器。账号可以先添加，登录需要设备画像。")
        if (state.editor == null) Message(state.message, vm::dismissMessage)
        Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            Column(Modifier.width(230.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Button(onClick = vm::openNewAccount, enabled = !state.isWorking && state.editor == null, modifier = Modifier.fillMaxWidth()) { Text("+ 新增账号") }
                if (state.accounts.isEmpty()) Text("还没有账号", Modifier.padding(12.dp), color = Muted)
                state.accounts.forEach { account ->
                    Surface(onClick = { focusId = account.id }, enabled = state.editor == null && !state.isWorking,
                        color = if (focus?.id == account.id) MaterialTheme.colorScheme.primaryContainer else Color.White,
                        shape = RoundedCornerShape(10.dp), border = BorderStroke(1.dp, if (focus?.id == account.id) Accent else Line)) {
                        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(account.alias, fontWeight = FontWeight.SemiBold)
                            Text(account.serverName, color = Muted, fontSize = 12.sp)
                            if (account.isSelected) Text("当前账号", color = Accent, fontSize = 11.sp)
                        }
                    }
                }
            }
            Column(Modifier.weight(1f)) {
                val editor = state.editor
                if (editor != null) AccountEditor(editor, state, vm)
                else if (focus != null) Panel(focus.alias) {
                    Detail("服务器", focus.serverName)
                    Detail("游戏 UID", focus.gameUid ?: "未填写")
                    Detail("状态", if (focus.isSelected) "当前任务账号" else "未选中")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (!focus.isSelected) Button(onClick = { vm.selectAccount(focus.id) }, enabled = !state.isWorking && !busy) { Text("设为当前") }
                        OutlinedButton(onClick = { vm.openEditAccount(focus.id) }, enabled = !state.isWorking && !busy) { Text("编辑") }
                        TextButton(onClick = { vm.requestDelete(focus) }, enabled = !state.isWorking && !busy) { Text("删除", color = MaterialTheme.colorScheme.error) }
                    }
                    Text("“设为当前”只选择助手使用的凭据，不会切换游戏客户端中的账号。", color = Muted, fontSize = 12.sp)
                } else Panel("添加你的第一个账号") { Text("选择服务器并填写账号资料。渠道服使用 uid 与 access_key，B 服使用登录账号与密码。", color = Muted) }
            }
        }
    }
    state.deleteCandidate?.let { account -> AlertDialog(onDismissRequest = vm::dismissDelete,
        title = { Text("删除账号") }, text = { Column { Text("确定删除“${account.alias}”吗？账号元数据和本地加密凭据都会删除，此操作不可撤销。"); state.message?.let { Text(it, color = MaterialTheme.colorScheme.error) } } },
        confirmButton = { TextButton(onClick = vm::confirmDelete, enabled = !state.isWorking) { Text("删除") } },
        dismissButton = { TextButton(onClick = vm::dismissDelete, enabled = !state.isWorking) { Text("取消") } }) }
}

@Composable private fun AccountEditor(editor: AccountEditorState, state: AccountUiState, vm: AccountViewModel) {
    var servers by remember { mutableStateOf(false) }
    Panel(if (editor.isEditing) "编辑账号" else "新增账号") {
        Box {
            OutlinedButton(onClick = { servers = true }, enabled = !state.isWorking) { Text("服务器：${editor.server?.displayName ?: "请选择"}") }
            DropdownMenu(expanded = servers, onDismissRequest = { servers = false }) {
                GameServer.entries.forEach { server -> DropdownMenuItem(text = { Text(server.displayName) }, onClick = { servers = false; vm.updateEditor { it.copy(server = server) } }) }
            }
        }
        if (editor.serverChanged) Text("更换服务器后需重新填写密码，该账号已有的登录会话会失效", color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
        else if (editor.server?.isChannelServer == true) Text("渠道服登录账号填 uid，密码填 access_key。", color = Muted, fontSize = 12.sp)
        OutlinedTextField(editor.alias, { value -> vm.updateEditor { it.copy(alias = value) } }, label = { Text("账号名称") }, singleLine = true, enabled = !state.isWorking, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(editor.loginId, { value -> vm.updateEditor { it.copy(loginId = value) } }, label = { Text(if (editor.server?.isChannelServer == true) "登录账号（uid）" else "登录账号") }, singleLine = true, enabled = !state.isWorking, modifier = Modifier.fillMaxWidth())
        val passwordName = if (editor.server?.isChannelServer == true) "密码（access_key）" else "密码"
        OutlinedTextField(editor.password, { value -> vm.updateEditor { it.copy(password = value) } }, label = { Text(if (editor.serverChanged) "新$passwordName（更换服务器必填）" else if (editor.isEditing) "新$passwordName（留空不修改）" else passwordName) },
            visualTransformation = PasswordVisualTransformation(), singleLine = true, enabled = !state.isWorking, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(editor.gameUid, { value -> vm.updateEditor { it.copy(gameUid = value) } }, label = { Text("游戏 UID（可选）") }, singleLine = true, enabled = !state.isWorking, modifier = Modifier.fillMaxWidth())
        state.message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (state.isWorking) LinearProgressIndicator(Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = vm::saveEditor, enabled = !state.isWorking) { Text("保存账号") }
            OutlinedButton(onClick = vm::dismissEditor, enabled = !state.isWorking) { Text("取消") }
        }
    }
}
