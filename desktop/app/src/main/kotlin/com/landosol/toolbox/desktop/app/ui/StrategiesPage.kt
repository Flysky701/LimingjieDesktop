package com.landosol.toolbox.desktop.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.landosol.toolbox.desktop.app.adapters.storage.*
import com.landosol.toolbox.desktop.app.control.ControlOps
import com.landosol.toolbox.desktop.app.control.ControlSessionUi
import com.landosol.toolbox.desktop.app.strategy.BattleModelHealth
import com.landosol.toolbox.desktop.app.ui.theme.DesktopCorners
import com.landosol.toolbox.desktop.app.ui.theme.LocalDesktopColors
import com.landosol.toolbox.labyrinth.*
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor

private enum class StrategySection(val label: String) {
    NN("NN 专属策略"),
    REGULAR("常规策略方案"),
}

@Composable
internal fun StrategiesPage(graph: ControlSessionUi) {
    val library by graph.labyrinthStrategySettings.library.collectAsState()
    val desktopSettings by graph.settings.state.collectAsState()
    val actions = rememberActions(graph)
    var section by remember {
        mutableStateOf(
            if (desktopSettings.battleOnnxEnabled || desktopSettings.pathBPlanningEnabled) {
                StrategySection.NN
            } else {
                StrategySection.REGULAR
            },
        )
    }
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
        DesktopStrategyEditor(
            graph = graph,
            profile = profile,
            saving = actions.working,
            message = actions.message,
            onDirtyChange = { editorDirty = it },
            onSave = { value ->
                actions.run {
                    editing = graph.labyrinthStrategySettings.edit(profile, value)
                    actions.message = "已保存，下次任务使用这套配置。"
                }
            },
            onOpenRatings = {
                editing = null
                ratings = profile
            },
            onClose = { editing = null },
        )
        return
    }

    PageBody("策略", "所有设备共用 · 保存及切换后，下次任务生效") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            StrategySection.entries.forEach { tab ->
                FilterChip(
                    selected = section == tab,
                    onClick = { section = tab },
                    label = { Text(tab.label) },
                )
            }
        }
        if (section == StrategySection.NN) {
            BattleValueModelPanel(graph)
            BattleModelStylePanel(graph)
        } else {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("常规策略方案", style = MaterialTheme.typography.titleMedium)
                Text("${library.profiles.size} 套", color = Muted, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.weight(1f))
                OutlinedButton(
                    onClick = {
                        actions.run {
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
                        }
                    },
                    shape = MaterialTheme.shapes.small,
                    enabled = !actions.working,
                ) { Text("导入配置") }
                Button(onClick = { creating = true }, shape = MaterialTheme.shapes.small, enabled = !actions.working) { Text("新建策略") }
            }
            Message(actions.message, onDismiss = { actions.message = null })
            if (library.undoImport != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("可撤销最近一次配置导入", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = Muted)
                    TextButton(
                        onClick = {
                            actions.run {
                                graph.labyrinthStrategySettings.undoImport(library.revision)
                                actions.message = "已恢复导入前的配置。"
                            }
                        },
                        enabled = !actions.working,
                    ) { Text("撤销导入") }
                }
            }
            library.profiles.sortedByDescending { it.id == library.activeId }.forEach { profile ->
                key(profile.id) {
                    val active = profile.id == library.activeId
                    var menu by remember { mutableStateOf(false) }
                    Surface(
                        shape = MaterialTheme.shapes.medium,
                        border = BorderStroke(1.dp, if (active) Accent.copy(alpha = .5f) else Line),
                        color = MaterialTheme.colorScheme.surface,
                    ) {
                        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text(profile.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                if (active) StatusBadge("当前启用", tone = StatusTone.OK, showDot = true)
                                Text("修订 ${profile.revision}", style = MaterialTheme.typography.bodySmall, color = Muted)
                                Box {
                                    TextButton(onClick = { menu = true }, enabled = !actions.working) { Text("更多 ⋯") }
                                    DropdownMenu(menu, onDismissRequest = { menu = false }) {
                                        DropdownMenuItem(text = { Text("名称与备注") }, onClick = { menu = false; naming = profile })
                                        DropdownMenuItem(
                                            text = { Text("创建副本") },
                                            onClick = {
                                                menu = false
                                                actions.run {
                                                    graph.labyrinthStrategySettings.add("${profile.name.take(55)} 副本", profile.note, profile.settings)
                                                    actions.message = "副本已创建，可编辑后启用。"
                                                }
                                            },
                                        )
                                        DropdownMenuItem(text = { Text("分享配置") }, onClick = { menu = false; sharing = profile })
                                        HorizontalDivider()
                                        DropdownMenuItem(
                                            text = { Text("删除", color = if (active) Muted else MaterialTheme.colorScheme.error) },
                                            enabled = !active,
                                            onClick = { menu = false; deleting = profile },
                                        )
                                    }
                                }
                            }
                            Text(strategySummary(profile.settings), color = Muted, style = MaterialTheme.typography.bodySmall)
                            if (profile.note.isNotBlank()) {
                                Text(profile.note, style = MaterialTheme.typography.bodySmall, color = Muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Button(onClick = { actions.message = null; editing = profile }, enabled = !actions.working, shape = MaterialTheme.shapes.small) {
                                    Text("编辑策略")
                                }
                                OutlinedButton(onClick = { ratings = profile }, enabled = !actions.working, shape = MaterialTheme.shapes.small) {
                                    Text("个人评分 · ${profile.settings.personalRoleScores.size}")
                                }
                                Spacer(Modifier.weight(1f))
                                if (!active) {
                                    OutlinedButton(
                                        onClick = {
                                            actions.run {
                                                graph.labyrinthStrategySettings.activate(profile.id)
                                                actions.message = "已启用“${profile.name}”。"
                                            }
                                        },
                                        enabled = !actions.working,
                                        shape = MaterialTheme.shapes.small,
                                    ) { Text("启用此策略") }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    if (creating || naming != null) {
        val profile = naming
        var name by remember(profile?.id) { mutableStateOf(profile?.name.orEmpty()) }
        var note by remember(profile?.id) { mutableStateOf(profile?.note.orEmpty()) }
        if (LocalControlVisible.current) {
            AlertDialog(
                onDismissRequest = { if (!actions.working) { creating = false; naming = null } },
                title = { Text(if (profile == null) "新建策略" else "名称与备注") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedTextField(name, { if (it.length <= 60) name = it }, label = { Text("名称") }, singleLine = true)
                        OutlinedTextField(note, { if (it.length <= 300) note = it }, label = { Text("备注（可选）") }, maxLines = 3)
                        Message(actions.message)
                    }
                },
                confirmButton = {
                    TextButton(
                        enabled = name.isNotBlank() && !actions.working,
                        onClick = {
                            actions.run {
                                if (profile == null) {
                                    val id = graph.labyrinthStrategySettings.add(name, note)
                                    editing = graph.labyrinthStrategySettings.library.value.profiles.first { it.id == id }
                                } else {
                                    graph.labyrinthStrategySettings.edit(profile.copy(name = name, note = note))
                                }
                                creating = false
                                naming = null
                                actions.message = null
                            }
                        },
                    ) { Text(if (profile == null) "创建并编辑" else "保存") }
                },
                dismissButton = {
                    TextButton(enabled = !actions.working, onClick = { creating = false; naming = null }) { Text("取消") }
                },
            )
        }
    }
    deleting?.let { profile ->
        if (LocalControlVisible.current) {
            AlertDialog(
                onDismissRequest = { if (!actions.working) deleting = null },
                title = { Text("删除“${profile.name}”？") },
                text = {
                    Column {
                        Text("运行历史保留策略名称。删除前的策略库会保存在本地备份文件中。")
                        Message(actions.message)
                    }
                },
                confirmButton = {
                    TextButton(
                        enabled = !actions.working,
                        onClick = {
                            actions.run {
                                graph.labyrinthStrategySettings.delete(profile.id)
                                deleting = null
                                actions.message = "策略已删除。"
                            }
                        },
                    ) { Text("删除") }
                },
                dismissButton = {
                    TextButton(enabled = !actions.working, onClick = { deleting = null }) { Text("取消") }
                },
            )
        }
    }
    if (LocalControlVisible.current && (sharing != null || importCode != null)) {
        ConfigurationShareDialog(
            graph, library, sharing ?: library.active, importCode,
            onClose = { sharing = null; importCode = null },
        )
    }
}

private fun strategySummary(settings: LabyrinthStrategySettings): String {
    val shop = when {
        !settings.buyRelics -> "商店跳过"
        settings.refreshShop -> "商店${settings.refreshFromArea}区起刷新"
        else -> "商店不刷新"
    }
    val equip = if (settings.specialEquipmentMode == LabyrinthSpecialEquipmentMode.AUTO_EQUIP) "特装自动" else "特装关"
    return listOf(
        "玩家评分 ${settings.playerWeight}%",
        "前排：${settings.effectiveFrontlineSurvivalMode.label}",
        if (settings.bossTeamMode == LabyrinthBossTeamMode.MULTI_TEAM) "Boss 多队" else "Boss 单队",
        shop,
        equip,
        "${settings.responseIntervalMillis}ms",
        "开局 ${settings.openingRosters.size} 个自定义",
        if (settings.eventCustomizationEnabled) "事件自定义开" else "事件自动",
    ).joinToString(" · ")
}

@Composable
private fun BattleModelStylePanel(graph: ControlSessionUi) {
    val saved by graph.settings.state.collectAsState()
    val actions = rememberActions(graph)
    val selectedStyle = LabyrinthBattleModelStyle.fromStoredName(saved.battleModelStyle)

    Surface(
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, Line),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("NN 战斗策略风格（三档）", style = MaterialTheme.typography.titleMedium)
                StatusBadge(selectedStyle.label, tone = if (saved.battleOnnxEnabled) StatusTone.INFO else StatusTone.NEUTRAL)
            }
            if (!saved.battleOnnxEnabled) {
                Text("当前未开启 ONNX 选队模型；下方档位已保存，开启后生效。", color = Muted, style = MaterialTheme.typography.bodySmall)
            }
            LabyrinthBattleModelStyle.entries.forEach { style ->
                StrategyRadioCard(
                    label = style.label,
                    summary = style.summary,
                    detail = style.description,
                    selected = selectedStyle == style,
                    enabled = !actions.working,
                ) {
                    actions.run {
                        graph.settings.update { it.copy(battleModelStyle = style.name) }
                        actions.message = "已切换 NN 策略风格：${style.label}（下局生效）。"
                    }
                }
            }
            HorizontalDivider(color = Line)
            Text(
                "分工说明：EX / Boss 战由 ONNX 模型决策，角色 / 遗物 / 商店 / 事件由 Path B 估值；普通战、开局选人、商店刷新开关、事件自定义、特装及模型关闭或回退时沿用「常规策略方案」。",
                style = MaterialTheme.typography.bodySmall,
                color = Muted,
            )
            Message(actions.message, onDismiss = { actions.message = null })
        }
    }
}

@Composable
private fun BattleValueModelPanel(graph: ControlSessionUi) {
    val saved by graph.settings.state.collectAsState()
    val battleModel by graph.battleModel.collectAsState()
    val modelActions = rememberActions(graph)
    val hostWindow = LocalDesktopWindow.current
    var bundlePath by remember(saved.battleOnnxBundlePath) { mutableStateOf(saved.battleOnnxBundlePath) }
    var directoryExpanded by remember {
        mutableStateOf(saved.battleOnnxBundlePath.isNotBlank() || battleModel.health == BattleModelHealth.FAILED)
    }
    LaunchedEffect(saved.battleOnnxBundlePath, battleModel.health) {
        if (saved.battleOnnxBundlePath.isNotBlank() || battleModel.health == BattleModelHealth.FAILED) {
            directoryExpanded = true
        }
    }
    fun saveBundlePath(path: String) {
        bundlePath = path
        modelActions.run { graph.settings.update { it.copy(battleOnnxBundlePath = path.trim()) } }
    }

    val badgeText = when (battleModel.health) {
        BattleModelHealth.DISABLED -> battleModel.health.label
        BattleModelHealth.READY, BattleModelHealth.LOADED ->
            listOfNotNull(battleModel.health.label, battleModel.bundleId?.takeIf(String::isNotBlank)).joinToString(" · ")
        BattleModelHealth.FAILED, BattleModelHealth.FALLBACK -> battleModel.health.label
    }
    val badgeTone = when (battleModel.health) {
        BattleModelHealth.READY, BattleModelHealth.LOADED -> StatusTone.OK
        BattleModelHealth.FALLBACK -> StatusTone.WARN
        BattleModelHealth.FAILED -> StatusTone.ERROR
        BattleModelHealth.DISABLED -> StatusTone.NEUTRAL
    }

    Surface(
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, Line),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("迷宫战斗价值估计", style = MaterialTheme.typography.titleMedium)
                        StatusBadge(
                            badgeText,
                            tone = badgeTone,
                            showDot = battleModel.health == BattleModelHealth.READY || battleModel.health == BattleModelHealth.LOADED,
                        )
                    }
                    Text("全局决策引擎 · 更改开关或模型目录后下局生效", style = MaterialTheme.typography.bodySmall, color = Muted)
                }
                OutlinedButton(
                    onClick = { modelActions.run { modelActions.message = graph.messageRequest(ControlOps.BATTLE_MODEL_CHECK) } },
                    enabled = !modelActions.working,
                    shape = MaterialTheme.shapes.small,
                ) { Text("校验并重载") }
            }

            if (battleModel.health in setOf(BattleModelHealth.FAILED, BattleModelHealth.FALLBACK) && !battleModel.detail.isNullOrBlank()) {
                Message(
                    battleModel.detail,
                    tone = if (battleModel.health == BattleModelHealth.FAILED) StatusTone.ERROR else StatusTone.WARN,
                )
            } else if (battleModel.health == BattleModelHealth.DISABLED && !battleModel.detail.isNullOrBlank()) {
                Text(battleModel.detail!!, color = Muted, style = MaterialTheme.typography.bodySmall)
            }

            Surface(
                shape = DesktopCorners.sm,
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                border = BorderStroke(1.dp, Line),
            ) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp)) {
                    BattleEngineSwitchRow(
                        label = "使用本地 ONNX 战斗选队模型",
                        detail = "EX / Boss 战斗生效；关闭时使用规则编组",
                        checked = saved.battleOnnxEnabled,
                        enabled = !modelActions.working,
                    ) { enabled ->
                        modelActions.run { graph.settings.update { it.copy(battleOnnxEnabled = enabled) } }
                    }
                    HorizontalDivider(color = Line)
                    BattleEngineSwitchRow(
                        label = "启用 Path B 全局收益规划",
                        detail = "角色 / 遗物 / 商店 / 事件生效；搭配 ONNX 战斗模型按官方分与 Boss 通率估值，模型关闭时使用官方分与规则兜底",
                        checked = saved.pathBPlanningEnabled,
                        enabled = !modelActions.working,
                    ) { enabled ->
                        modelActions.run { graph.settings.update { it.copy(pathBPlanningEnabled = enabled) } }
                    }
                }
            }

            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("生效目录 · ${battleModel.source.label}", style = MaterialTheme.typography.labelSmall, color = Muted)
                    SelectionContainer {
                        Text(
                            battleModel.resolvedPath.ifBlank { "未解析" },
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                TextButton(
                    onClick = { directoryExpanded = !directoryExpanded },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                ) { Text(if (directoryExpanded) "收起目录设置" else "自定义目录", style = MaterialTheme.typography.bodySmall) }
            }

            if (battleModel.overriddenByEnv) {
                Text(
                    "当前路径由环境变量或 JVM 参数覆盖，下方自定义目录暂不生效。",
                    color = LocalDesktopColors.current.warn,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            if (directoryExpanded) {
                OutlinedTextField(
                    value = bundlePath,
                    onValueChange = { bundlePath = it },
                    label = { Text("本地模型目录") },
                    placeholder = { Text("留空则使用默认数据目录") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    enabled = !modelActions.working,
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedButton(
                        onClick = {
                            modelActions.run {
                                val selectedDir = chooseDirectory("选择本地模型目录", bundlePath, hostWindow) ?: return@run
                                bundlePath = selectedDir.absolutePath
                                graph.settings.update { it.copy(battleOnnxBundlePath = selectedDir.absolutePath.trim()) }
                            }
                        },
                        enabled = !modelActions.working,
                        shape = MaterialTheme.shapes.small,
                    ) { Text("选择目录") }
                    Button(
                        onClick = { saveBundlePath(bundlePath.trim()) },
                        enabled = !modelActions.working && bundlePath.trim() != saved.battleOnnxBundlePath,
                        shape = MaterialTheme.shapes.small,
                    ) { Text("保存目录") }
                    TextButton(
                        onClick = { saveBundlePath("") },
                        enabled = !modelActions.working && (bundlePath.isNotEmpty() || saved.battleOnnxBundlePath.isNotEmpty()),
                        shape = MaterialTheme.shapes.small,
                    ) { Text("恢复默认") }
                }
            }

            Message(modelActions.message, onDismiss = { modelActions.message = null })
        }
    }
}

@Composable
private fun BattleEngineSwitchRow(
    label: String,
    detail: String,
    checked: Boolean,
    enabled: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth()
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = if (enabled) LocalContentColor.current else Muted)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = Muted)
        }
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}
