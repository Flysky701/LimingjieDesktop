package com.landosol.toolbox.desktop.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.landosol.toolbox.AppVersion
import com.landosol.toolbox.account.AccountListItem
import com.landosol.toolbox.desktop.app.adapters.storage.*
import com.landosol.toolbox.desktop.app.control.ControlSessionUi
import com.landosol.toolbox.labyrinth.*

@Composable internal fun ConfigurationShareDialog(
    graph: ControlSessionUi, library: StrategyLibrary, profile: StrategyProfile, code: String?, onClose: () -> Unit,
) {
    val baseline = remember { library }
    val configuration = remember(code) { code?.let(LabyrinthConfigShareCodec::decode) }
    val accounts by graph.accounts.uiState.collectAsState()
    val catalog by graph.ux.catalog.collectAsState()
    val actions = rememberActions(graph)
    var targetProfile by remember { mutableStateOf(profile.id) }
    var accountId by remember { mutableStateOf(accounts.accounts.firstOrNull { it.isSelected }?.id) }
    var modules by remember { mutableStateOf(configuration?.defaultSelection() ?: setOf(LabyrinthConfigModule.STRATEGY, LabyrinthConfigModule.PERSONAL_SCORES)) }
    var confirmEmpty by remember { mutableStateOf(false) }
    val selectedProfile = baseline.profiles.first { it.id == targetProfile }
    val validAccount = accountId?.takeIf { id -> accounts.accounts.any { it.id == id } }
    val needsAccount = LabyrinthConfigModule.REROLL in modules
    val ready = modules.isNotEmpty() && (!needsAccount || validAccount != null)
    val plan = if (configuration != null && ready) LabyrinthConfigImportPlan(configuration, modules, validAccount,
        LabyrinthConfigurationValues(selectedProfile.settings, baseline.rerolls)) else null
    val stale = library.revision != baseline.revision
    AlertDialog(onDismissRequest = { if (!actions.working) onClose() }, modifier = Modifier.widthIn(min = 560.dp, max = 720.dp),
        title = { Text(if (code == null) "分享配置" else "导入预览") },
        text = { Column(Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (configuration == null) Text("选择要分享的内容。分享码可在桌面端与 Android 端互通。")
            else Text("确认目标和变更后，替换所选模块。当前运行中的任务继续使用启动时的配置。")
            if (code == null) Text("策略：${profile.name}", style = MaterialTheme.typography.titleSmall)
            else {
                var choosing by remember { mutableStateOf(false) }
                Box {
                    OutlinedButton(onClick = { choosing = true }, shape = MaterialTheme.shapes.small) { Text("目标策略：${selectedProfile.name}") }
                    DropdownMenu(choosing, { choosing = false }) {
                        baseline.profiles.forEach { value -> DropdownMenuItem(text = { Text(value.name) },
                            onClick = { targetProfile = value.id; choosing = false; confirmEmpty = false }) }
                    }
                }
                if (selectedProfile.id != baseline.activeId) Text("导入后仍保留当前启用的策略。", color = Muted, style = MaterialTheme.typography.bodySmall)
            }
            (configuration?.modules ?: LabyrinthConfigModule.entries.toSet()).forEach { module ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = module in modules, onCheckedChange = { checked ->
                        modules = if (checked) modules + module else modules - module; confirmEmpty = false
                    }, enabled = !actions.working)
                    Text(if (module == LabyrinthConfigModule.STRATEGY) "策略与开局编组" else module.label)
                }
            }
            if (needsAccount) {
                ShareAccountPicker(accounts.accounts, validAccount) { accountId = it; confirmEmpty = false }
                if (validAccount == null) Text("请选择刷开局设置所属的本机账号", color = MaterialTheme.colorScheme.error)
            }
            if (configuration == null) {
                Text("只包含策略偏好、角色评分和所选账号的刷取条件。", style = MaterialTheme.typography.bodySmall, color = Muted)
            } else if (plan != null) {
                HorizontalDivider()
                Text("将发生的变化", style = MaterialTheme.typography.titleSmall)
                plan.changeSummary(catalog.associate { it.id to it.name }).forEach { line ->
                    Text(when (line) {
                        "全局策略（所有账号）" -> "策略：${selectedProfile.name}"
                        "个人评分（所有账号）" -> "所选策略的个人评分"
                        else -> line
                    }, style = MaterialTheme.typography.bodySmall)
                }
                if (catalog.isNotEmpty()) {
                    val unknown = configuration.unknownRoleIds(catalog.map { it.id }.toSet())
                    if (unknown.isNotEmpty()) Text("包含 ${unknown.size} 个本机资料中未找到的角色编号，将保留其配置。", style = MaterialTheme.typography.bodySmall)
                }
                if (plan.clearsPersonalScores) Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(confirmEmpty, { confirmEmpty = it }, enabled = !actions.working)
                    Text(plan.clearScoresWarning, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
            if (stale) Text("本机配置已变化，请关闭后重新预览。", color = MaterialTheme.colorScheme.error)
            Message(actions.message)
        } },
        confirmButton = { Button(enabled = ready && !stale && !actions.working && (plan?.clearsPersonalScores != true || confirmEmpty),
            shape = MaterialTheme.shapes.small, onClick = { actions.run {
                if (code == null) {
                    val values = LabyrinthConfigurationValues(selectedProfile.settings, baseline.rerolls)
                    copyText(LabyrinthConfigShareCodec.encode(LabyrinthConfigShareCodec.create(values, modules, validAccount, AppVersion.display, System.currentTimeMillis())))
                    actions.message = "分享码已复制。"
                } else {
                    graph.labyrinthStrategySettings.importConfiguration(code, modules, targetProfile, validAccount, confirmEmpty, baseline.revision)
                    onClose()
                }
            } }) { Text(if (code == null) "复制分享码" else "确认导入") } },
        dismissButton = { TextButton(onClick = onClose, enabled = !actions.working) { Text("关闭") } })
}

@Composable private fun ShareAccountPicker(accounts: List<AccountListItem>, selected: Long?, change: (Long) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }, shape = MaterialTheme.shapes.small) {
            Text("刷开局账号：${accounts.firstOrNull { it.id == selected }?.alias ?: "请选择"}")
        }
        DropdownMenu(open, { open = false }) {
            accounts.forEach { account -> DropdownMenuItem(text = { Text("${account.alias} · ${account.serverName}") },
                onClick = { change(account.id); open = false }) }
        }
    }
}
