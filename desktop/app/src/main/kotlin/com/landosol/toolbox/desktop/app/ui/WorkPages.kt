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

@Composable internal fun StrategiesPage(graph: DesktopAppGraph) {
    val saved by graph.labyrinthStrategySettings.state.collectAsState()
    var edit by remember { mutableStateOf(false) }
    var ratings by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    if(ratings) { RoleRatingsPage(graph) { ratings=false };return }
    PageBody("策略", "全局配置 · 保存后从下一次识别或自动执行生效。") {
        Panel("当前策略") {
            Detail("玩家评分", "${saved.playerWeight}%")
            Detail("个人评分", "已修改 ${saved.personalRoleScores.size} 个角色")
            Detail("Boss 编组", if (saved.bossTeamMode == LabyrinthBossTeamMode.MULTI_TEAM) "多队编组" else "单队编组")
            Detail("部署权重", "第一队 100% · 第二队 ${saved.secondTeamWeight}% · 第三队 ${saved.thirdTeamWeight}%")
            Row(horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                Button(shape=MaterialTheme.shapes.small,onClick = { edit = true }) { Text("编辑完整策略与编组") }
                OutlinedButton(shape=MaterialTheme.shapes.small,onClick = { ratings=true }) { Text("角色个人评分") }
            }
        }
        Panel("配置包含") {
            Text("评分与角色个人评分、选人部署、Boss 编组、战斗失败处理、开局阵容、遗物、专武、商店、事件、响应间隔。")
            Text("角色评分支持头像、名称/版本/ID 搜索、0–100 分和恢复原评分。开局编组与评分都保留独立保存/取消与校验。", color = Muted)
        }
    }
    if (edit) BoundStrategyDialog { LabyrinthStrategySettingsScreen(saved = saved, saving = saving, message = message,
        loadRoleRatings = graph::loadLabyrinthRoleRatings, onClose = { edit = false; message = null },
        onSave = { value -> if (!saving) {
            saving = true; message = null
            scope.launch {
                try { graph.labyrinthStrategySettings.save(value); graph.log.add("策略配置已保存，下次启动生效") }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { message = failure.message ?: "保存失败，请重试" }
                finally { saving = false }
            }
        } }) }
}

@Composable internal fun AccountsPage(graph: DesktopAppGraph) {
    val busy = LocalTaskBusy.current
    val vm = graph.accounts
    val state by vm.uiState.collectAsState()
    val credentials by graph.ux.credentials.collectAsState()
    val connection by graph.device.state.collectAsState()
    var focusId by remember { mutableStateOf<Long?>(null) }
    val focus = state.accounts.firstOrNull { it.id == focusId } ?: state.accounts.firstOrNull { it.isSelected } ?: state.accounts.firstOrNull()
    val snapshot = graph.identityStore.current()
    PageBody("账号库", "在当前 Windows 用户下加密保存凭据。选择账号后，任务按需登录。") {
        if(busy)Message("任务运行中，停止后可新增、切换或修改账号；编辑草稿仍可取消。")
        Message(snapshot?.let { "设备画像：${it.brand} ${it.model} · ${DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(it.capturedAtMillis))}${if (connection is DeviceConnectionState.Ready) "（当前设备）" else "（上次连接设备）"}" }
            ?: "尚未读取模拟器设备信息，请先在“设备”页连接模拟器。账号可以先添加，登录需要设备画像。")
        if (state.editor == null) Message(state.message, vm::dismissMessage)
        Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            Column(Modifier.width(230.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Button(shape=MaterialTheme.shapes.small,onClick = { if(!graph.busy)vm.openNewAccount() }, enabled = !state.isWorking && !busy && state.editor == null, modifier = Modifier.fillMaxWidth()) { Text("+ 新增账号") }
                if (state.accounts.isEmpty()) Text("还没有账号", Modifier.padding(12.dp), color = Muted)
                state.accounts.forEach { account ->
                    Surface(onClick = { focusId = account.id }, enabled = state.editor == null && !state.isWorking,
                        color = if (focus?.id == account.id) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                        shape = RoundedCornerShape(10.dp), border = BorderStroke(1.dp, if (focus?.id == account.id) Accent else Line)) {
                        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(account.alias, fontWeight = FontWeight.SemiBold)
                            Text(account.serverName, color = Muted, fontSize = 12.5.sp)
                            if (account.isSelected) Text("当前账号", color = Accent, fontSize = 12.5.sp)
                            if(account.gameUid.isNullOrBlank())Text("UID 未填",color=Muted,style=MaterialTheme.typography.bodySmall)
                            if(credentials[account.id]==false)Text("缺凭据 · 请编辑重新填写",color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            Column(Modifier.weight(1f)) {
                val editor = state.editor
                if (editor != null) AccountEditor(editor, state, vm,graph)
                else if (focus != null) Panel(focus.alias) {
                    Detail("服务器", focus.serverName)
                    Detail("游戏 UID", focus.gameUid ?: "未填写")
                    Detail("状态", if (focus.isSelected) "当前任务账号" else "未选中")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (!focus.isSelected) Button(shape=MaterialTheme.shapes.small,onClick = { if(!graph.busy)vm.selectAccount(focus.id) }, enabled = !state.isWorking && !busy) { Text("设为当前") }
                        OutlinedButton(shape=MaterialTheme.shapes.small,onClick = { if(!graph.busy)vm.openEditAccount(focus.id) }, enabled = !state.isWorking && !busy) { Text("编辑") }
                        TextButton(onClick = { if(!graph.busy)vm.requestDelete(focus) }, enabled = !state.isWorking && !busy) { Text("删除", color = MaterialTheme.colorScheme.error) }
                    }
                    Text("“设为当前”只选择助手使用的凭据，不会切换游戏客户端中的账号。", color = Muted, fontSize = 12.5.sp)
                } else Panel("添加你的第一个账号") { Text("选择服务器并填写账号资料。渠道服使用 uid 与 access_key，B 服使用登录账号与密码。", color = Muted) }
            }
        }
    }
    state.deleteCandidate?.let { account -> AlertDialog(onDismissRequest = vm::dismissDelete,
        title = { Text("删除账号") }, text = { Column { Text("确定删除“${account.alias}”吗？账号元数据和本地加密凭据都会删除，此操作不可撤销。"); state.message?.let { Text(it, color = MaterialTheme.colorScheme.error) } } },
        confirmButton = { TextButton(onClick = {if(!graph.busy)vm.confirmDelete()}, enabled = !state.isWorking && !busy) { Text("删除") } },
        dismissButton = { TextButton(onClick = vm::dismissDelete, enabled = !state.isWorking) { Text("取消") } }) }
}

@Composable private fun AccountEditor(editor: AccountEditorState, state: AccountUiState, vm: AccountViewModel,graph:DesktopAppGraph) {
    val editable = !state.isWorking && !LocalTaskBusy.current
    var servers by remember { mutableStateOf(false) }
    Panel(if (editor.isEditing) "编辑账号" else "新增账号") {
        Box {
            OutlinedButton(shape=MaterialTheme.shapes.small,onClick = { servers = true }, enabled = editable) { Text("服务器：${editor.server?.displayName ?: "请选择"}") }
            DropdownMenu(expanded = servers, onDismissRequest = { servers = false }) {
                GameServer.entries.forEach { server -> DropdownMenuItem(text = { Text(server.displayName) }, enabled = editable, onClick = { servers = false; vm.updateEditor { it.copy(server = server) } }) }
            }
        }
        if (editor.serverChanged) Text("更换服务器后需重新填写密码，该账号已有的登录会话会失效", color = MaterialTheme.colorScheme.error, fontSize = 12.5.sp)
        else if (editor.server?.isChannelServer == true) Text("渠道服登录账号填 uid，密码填 access_key。", color = Muted, fontSize = 12.5.sp)
        OutlinedTextField(editor.alias, { value -> if(!graph.busy) vm.updateEditor { it.copy(alias = value) } }, label = { Text("账号名称") }, singleLine = true, enabled = editable, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(editor.loginId, { value -> if(!graph.busy) vm.updateEditor { it.copy(loginId = value) } }, label = { Text(if (editor.server?.isChannelServer == true) "登录账号（uid）" else "登录账号") }, singleLine = true, enabled = editable, modifier = Modifier.fillMaxWidth())
        val passwordName = if (editor.server?.isChannelServer == true) "密码（access_key）" else "密码"
        OutlinedTextField(editor.password, { value -> if(!graph.busy) vm.updateEditor { it.copy(password = value) } }, label = { Text(if (editor.serverChanged) "新$passwordName（更换服务器必填）" else if (editor.isEditing) "新$passwordName（留空不修改）" else passwordName) },
            visualTransformation = PasswordVisualTransformation(), singleLine = true, enabled = editable, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(editor.gameUid, { value -> if(!graph.busy) vm.updateEditor { it.copy(gameUid = value) } }, label = { Text("游戏 UID（可选）") }, singleLine = true, enabled = editable, modifier = Modifier.fillMaxWidth())
        state.message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (state.isWorking) LinearProgressIndicator(Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(shape=MaterialTheme.shapes.small,onClick = {if(!graph.busy)vm.saveEditor()}, enabled = editable) { Text("保存账号") }
            OutlinedButton(shape=MaterialTheme.shapes.small,onClick = vm::dismissEditor, enabled = !state.isWorking) { Text("取消") }
        }
    }
}
