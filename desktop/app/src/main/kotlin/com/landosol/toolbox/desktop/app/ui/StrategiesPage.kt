package com.landosol.toolbox.desktop.app.ui

import androidx.activity.compose.LocalDesktopBackHandlerActive
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.landosol.toolbox.desktop.app.adapters.storage.*
import com.landosol.toolbox.desktop.app.control.ControlSessionUi
import com.landosol.toolbox.labyrinth.*
import com.landosol.toolbox.ui.labyrinth.LabyrinthStrategySettingsScreen
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor

@Composable internal fun StrategiesPage(graph: ControlSessionUi) {
    val library by graph.labyrinthStrategySettings.library.collectAsState()
    val actions = rememberActions(graph)
    var editing by remember { mutableStateOf<StrategyProfile?>(null) }
    var editorDirty by remember { mutableStateOf(false) }
    var ratings by remember { mutableStateOf<StrategyProfile?>(null) }
    var naming by remember { mutableStateOf<StrategyProfile?>(null) }
    var creating by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<StrategyProfile?>(null) }
    var sharing by remember { mutableStateOf<StrategyProfile?>(null) }
    var importCode by remember { mutableStateOf<String?>(null) }
    ReportControlDirty("strategy", editorDirty || naming != null || creating)
    ratings?.let { profile -> RoleRatingsPage(graph, profile) { ratings = null }; return }
    editing?.let { profile ->
        val navigation = LocalNavigationGuard.current
        val register: (((() -> Unit) -> Unit)?) -> Unit = remember(navigation) { { handler -> navigation.intercept = handler } }
        CompositionLocalProvider(LocalDesktopBackHandlerActive provides LocalControlVisible.current) {
            key(profile.id) {
                LabyrinthStrategySettingsScreen(saved = profile.settings, saving = actions.working, message = actions.message,
                    title = profile.name, wideLayout = true, dialogsVisible = LocalControlVisible.current,
                    registerLeaveHandler = register, onDirtyChange = { editorDirty = it },
                    loadRoleRatings = graph::loadLabyrinthRoleRatings, onClose = { editing = null },
                    onSave = { value -> actions.run {
                        editing = graph.labyrinthStrategySettings.edit(profile, value)
                        actions.message = "已保存，下次任务使用这套配置。"
                    } })
            }
        }
        return
    }

    PageBody("策略", "所有设备共用 · 保存及切换后，下次任务生效") {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("${library.profiles.size} 套策略", Modifier.weight(1f), color = Muted, style = MaterialTheme.typography.bodyMedium)
            OutlinedButton(onClick = { actions.run {
                val clipboard = Toolkit.getDefaultToolkit().systemClipboard
                check(clipboard.isDataFlavorAvailable(DataFlavor.stringFlavor)) { "请先复制完整的配置分享码" }
                val code = clipboard.getData(DataFlavor.stringFlavor) as String
                if (code.trim().startsWith("LMJ1:")) {
                    graph.labyrinthStrategySettings.importCode(code)
                    actions.message = "已导入为新策略，可编辑后启用。"
                } else {
                    LabyrinthConfigShareCodec.decode(code)
                    importCode = code
                }
            } }, shape = MaterialTheme.shapes.small, enabled = !actions.working) { Text("导入配置") }
            Button(onClick = { creating = true }, shape = MaterialTheme.shapes.small, enabled = !actions.working) { Text("新建策略") }
        }
        Message(actions.message)
        if (library.undoImport != null) Row(verticalAlignment = Alignment.CenterVertically) {
            Text("可撤销最近一次配置导入", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = Muted)
            TextButton(onClick = { actions.run {
                graph.labyrinthStrategySettings.undoImport(library.revision); actions.message = "已恢复导入前的配置。"
            } }, enabled = !actions.working) { Text("撤销导入") }
        }
        library.profiles.sortedByDescending { it.id == library.activeId }.forEach { profile -> key(profile.id) {
            val active = profile.id == library.activeId
            var menu by remember { mutableStateOf(false) }
            Surface(shape = MaterialTheme.shapes.medium, border = BorderStroke(1.dp, if (active) Accent.copy(alpha = .5f) else Line),
                color = MaterialTheme.colorScheme.surface) {
                Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(profile.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        if (active) StatusBadge("当前启用")
                        Text("修订 ${profile.revision}", style = MaterialTheme.typography.bodySmall, color = Muted)
                        Box {
                            TextButton(onClick = { menu = true }, enabled = !actions.working) { Text("更多 ⋯") }
                            DropdownMenu(menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(text = { Text("名称与备注") }, onClick = { menu = false; naming = profile })
                                DropdownMenuItem(text = { Text("创建副本") }, onClick = { menu = false; actions.run {
                                    graph.labyrinthStrategySettings.add("${profile.name.take(55)} 副本", profile.note, profile.settings)
                                    actions.message = "副本已创建，可编辑后启用。"
                                } })
                                DropdownMenuItem(text = { Text("分享配置") }, onClick = { menu = false; sharing = profile })
                                HorizontalDivider()
                                DropdownMenuItem(text = { Text("删除", color = if (active) Muted else MaterialTheme.colorScheme.error) },
                                    enabled = !active, onClick = { menu = false; deleting = profile })
                            }
                        }
                    }
                    Text(strategySummary(profile.settings), color = Muted, style = MaterialTheme.typography.bodyMedium)
                    if (profile.note.isNotBlank()) Text(profile.note, style = MaterialTheme.typography.bodySmall, color = Muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(onClick = { actions.message = null; editing = profile }, enabled = !actions.working, shape = MaterialTheme.shapes.small) { Text("编辑策略") }
                        OutlinedButton(onClick = { ratings = profile }, enabled = !actions.working, shape = MaterialTheme.shapes.small) { Text("个人评分 · ${profile.settings.personalRoleScores.size}") }
                        Spacer(Modifier.weight(1f))
                        if (!active) OutlinedButton(onClick = { actions.run {
                            graph.labyrinthStrategySettings.activate(profile.id); actions.message = "已启用“${profile.name}”。"
                        } }, enabled = !actions.working, shape = MaterialTheme.shapes.small) { Text("启用此策略") }
                    }
                }
            }
        } }
    }
    if (creating || naming != null) {
        val profile = naming
        var name by remember(profile?.id) { mutableStateOf(profile?.name.orEmpty()) }
        var note by remember(profile?.id) { mutableStateOf(profile?.note.orEmpty()) }
        if (LocalControlVisible.current) AlertDialog(onDismissRequest = { if (!actions.working) { creating = false; naming = null } },
            title = { Text(if (profile == null) "新建策略" else "名称与备注") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(name, { if (it.length <= 60) name = it }, label = { Text("名称") }, singleLine = true)
                OutlinedTextField(note, { if (it.length <= 300) note = it }, label = { Text("备注（可选）") }, maxLines = 3)
                Message(actions.message)
            } }, confirmButton = { TextButton(enabled = name.isNotBlank() && !actions.working, onClick = { actions.run {
                if (profile == null) {
                    val id = graph.labyrinthStrategySettings.add(name, note)
                    editing = graph.labyrinthStrategySettings.library.value.profiles.first { it.id == id }
                } else graph.labyrinthStrategySettings.edit(profile.copy(name = name, note = note))
                creating = false; naming = null; actions.message = null
            } }) { Text(if (profile == null) "创建并编辑" else "保存") } },
            dismissButton = { TextButton(enabled = !actions.working, onClick = { creating = false; naming = null }) { Text("取消") } })
    }
    deleting?.let { profile -> if (LocalControlVisible.current) AlertDialog(onDismissRequest = { if (!actions.working) deleting = null },
        title = { Text("删除“${profile.name}”？") }, text = { Column {
            Text("运行历史保留策略名称。删除前的策略库会保存在本地备份文件中。")
            Message(actions.message)
        } }, confirmButton = { TextButton(enabled = !actions.working, onClick = { actions.run {
            graph.labyrinthStrategySettings.delete(profile.id); deleting = null; actions.message = "策略已删除。"
        } }) { Text("删除") } }, dismissButton = { TextButton(enabled = !actions.working, onClick = { deleting = null }) { Text("取消") } }) }
    if (LocalControlVisible.current && (sharing != null || importCode != null)) ConfigurationShareDialog(
        graph, library, sharing ?: library.active, importCode, onClose = { sharing = null; importCode = null },
    )
}

private fun strategySummary(settings: LabyrinthStrategySettings): String = listOf(
    "玩家评分 ${settings.playerWeight}%",
    if (settings.bossTeamMode == LabyrinthBossTeamMode.MULTI_TEAM) "Boss 多队" else "Boss 单队",
    "开局编组 ${settings.openingRosters.size} 个公会已自定义",
    if (settings.eventCustomizationEnabled) "指定事件选项已开启" else "事件自动选择",
).joinToString("  ·  ")
