package com.landosol.toolbox.desktop.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.landosol.toolbox.desktop.app.adapters.storage.*
import com.landosol.toolbox.desktop.app.control.ControlSessionUi
import com.landosol.toolbox.ui.labyrinth.LabyrinthStrategySettingsScreen
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor

@Composable internal fun StrategiesPage(graph: ControlSessionUi) {
    val library by graph.labyrinthStrategySettings.library.collectAsState()
    val actions = rememberActions(graph)
    var editing by remember { mutableStateOf<StrategyProfile?>(null) }
    var ratings by remember { mutableStateOf<StrategyProfile?>(null) }
    var naming by remember { mutableStateOf<StrategyProfile?>(null) }
    var creating by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<StrategyProfile?>(null) }
    ReportControlDirty("strategy", editing != null || naming != null || creating)
    ratings?.let { profile -> RoleRatingsPage(graph, profile) { ratings = null }; return }

    PageBody("策略", "全局策略库 · 所有设备共用 · 启用后从下一次任务开始生效，运行中的整批任务保持原策略。") {
        Message(actions.message)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = { creating = true }, enabled = !actions.working) { Text("新建策略") }
            OutlinedButton(onClick = { actions.run {
                val clipboard = Toolkit.getDefaultToolkit().systemClipboard
                check(clipboard.isDataFlavorAvailable(DataFlavor.stringFlavor)) { "剪贴板没有文字，请先复制完整的策略分享码" }
                val code = clipboard.getData(DataFlavor.stringFlavor) as String
                graph.labyrinthStrategySettings.importCode(code)
                actions.message = "策略已导入到列表，尚未启用；同名策略会自动加后缀。"
            } }, enabled = !actions.working) { Text("一键导入") }
        }
        Text("分享会复制完整策略码。导入读取剪贴板并新增方案；需要使用时再点击“启用”。", color = Muted, style = MaterialTheme.typography.bodySmall)
        library.profiles.sortedByDescending { it.id == library.activeId }.forEach { profile ->
            key(profile.id) {
                Panel(profile.name) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (profile.id == library.activeId) StatusBadge("全局已启用")
                        Text("第 ${profile.revision} 次修订 · 个人评分 ${profile.settings.personalRoleScores.size} 人", color = Muted, style = MaterialTheme.typography.bodySmall)
                    }
                    Detail("玩家评分", "${profile.settings.playerWeight}%")
                    if (profile.note.isNotBlank()) Text(profile.note, style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { actions.run { graph.labyrinthStrategySettings.activate(profile.id); actions.message = "已启用“${profile.name}”，下一次任务开始生效。" } },
                            enabled = profile.id != library.activeId && !actions.working) { Text(if (profile.id == library.activeId) "已启用" else "启用") }
                        OutlinedButton(onClick = { editing = profile }, enabled = !actions.working) { Text("编辑策略与编组") }
                        OutlinedButton(onClick = { ratings = profile }, enabled = !actions.working) { Text("角色个人评分") }
                        OutlinedButton(onClick = { actions.run { copyText(StrategyShareCodec.encode(profile)); actions.message = "“${profile.name}”分享码已复制，可粘贴发送。" } }, enabled = !actions.working) { Text("一键分享") }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { naming = profile }, enabled = !actions.working) { Text("名称与备注") }
                        TextButton(onClick = { actions.run { graph.labyrinthStrategySettings.add("${profile.name.take(55)} 副本", profile.note, profile.settings); actions.message = "策略副本已创建，尚未启用。" } }, enabled = !actions.working) { Text("复制") }
                        TextButton(onClick = { deleting = profile }, enabled = !actions.working && profile.id != library.activeId) { Text("删除") }
                    }
                }
            }
        }
    }
    editing?.let { profile ->
        BoundStrategyDialog {
            LabyrinthStrategySettingsScreen(saved = profile.settings, saving = actions.working, message = actions.message,
                loadRoleRatings = graph::loadLabyrinthRoleRatings, onClose = { editing = null },
                onSave = { value -> actions.run {
                    editing = graph.labyrinthStrategySettings.edit(profile, value)
                    actions.message = "策略已保存；已启动的任务保持原策略。"
                } })
        }
    }
    if (creating || naming != null) {
        val profile = naming
        var name by remember(profile?.id) { mutableStateOf(profile?.name.orEmpty()) }
        var note by remember(profile?.id) { mutableStateOf(profile?.note.orEmpty()) }
        if (LocalControlVisible.current) AlertDialog(onDismissRequest = { if (!actions.working) { creating = false; naming = null } },
            title = { Text(if (profile == null) "新建策略" else "名称与备注") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(name, { if (it.length <= 60) name = it }, label = { Text("名称") }, singleLine = true)
                OutlinedTextField(note, { if (it.length <= 300) note = it }, label = { Text("备注（可选）") }, maxLines = 4)
                Message(actions.message)
            } }, confirmButton = { TextButton(enabled = name.isNotBlank() && !actions.working, onClick = { actions.run {
                if (profile == null) graph.labyrinthStrategySettings.add(name, note)
                else graph.labyrinthStrategySettings.edit(profile.copy(name = name, note = note))
                creating = false; naming = null; actions.message = "策略已保存。"
            } }) { Text("保存") } }, dismissButton = { TextButton(enabled = !actions.working, onClick = { creating = false; naming = null }) { Text("取消") } })
    }
    deleting?.let { profile -> if (LocalControlVisible.current) AlertDialog(onDismissRequest = { if (!actions.working) deleting = null },
        title = { Text("删除“${profile.name}”？") }, text = { Column {
            Text("运行历史保留策略名称。删除前的策略库会保存在本地备份文件中。")
            Message(actions.message)
        } }, confirmButton = { TextButton(enabled = !actions.working, onClick = { actions.run {
            graph.labyrinthStrategySettings.delete(profile.id); deleting = null; actions.message = "策略已删除。"
        } }) { Text("删除") } }, dismissButton = { TextButton(enabled = !actions.working, onClick = { deleting = null }) { Text("取消") } }) }
}
