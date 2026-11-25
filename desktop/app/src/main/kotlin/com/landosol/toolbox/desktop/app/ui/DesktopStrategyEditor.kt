package com.landosol.toolbox.desktop.app.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.landosol.toolbox.desktop.app.adapters.storage.StrategyProfile
import com.landosol.toolbox.desktop.app.control.ControlSessionUi
import com.landosol.toolbox.desktop.app.ui.theme.DesktopCorners
import com.landosol.toolbox.desktop.app.ui.theme.LocalDesktopColors
import com.landosol.toolbox.labyrinth.*
import kotlin.math.roundToInt

private enum class StrategyGroup(val label: String, val icon: String) {
    SCORING("评分与角色", "tune"),
    ROSTER("选人与开局", "person"),
    COMBAT("战斗与前排", "play_arrow"),
    RELIC_SHOP("遗物与商店", "bar_chart"),
    EVENTS("事件与自定义", "search"),
    EQUIPMENT_PERF("特装与性能", "settings"),
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DesktopStrategyEditor(
    graph: ControlSessionUi,
    profile: StrategyProfile,
    saving: Boolean,
    message: String?,
    onDirtyChange: (Boolean) -> Unit,
    onSave: (LabyrinthStrategySettings) -> Unit,
    onOpenRatings: () -> Unit,
    onClose: () -> Unit,
) {
    val saved = profile.settings
    val desktopSettings by graph.settings.state.collectAsState()
    var draft by remember(profile.id, profile.revision) { mutableStateOf(saved) }
    var group by remember(profile.id) { mutableStateOf(StrategyGroup.SCORING) }
    var editingOpeningRoster by remember(profile.id) { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }
    var pendingExit by remember { mutableStateOf<(() -> Unit)?>(null) }

    val dirty = draft != saved
    SideEffect { onDirtyChange(dirty) }
    DisposableEffect(Unit) { onDispose { onDirtyChange(false) } }

    val navigation = LocalNavigationGuard.current
    val requestExit: (() -> Unit) -> Unit = { action ->
        if (saving) Unit
        else if (dirty) pendingExit = action
        else action()
    }
    SideEffect { navigation.intercept = { next -> requestExit(next) } }
    DisposableEffect(navigation) { onDispose { navigation.intercept = null } }

    if (editingOpeningRoster) {
        DesktopOpeningRosterEditor(
            graph = graph,
            rosters = draft.openingRosters,
            onChange = { guildId, slots ->
                draft = draft.copy(
                    openingRosters = if (slots == null) draft.openingRosters - guildId else draft.openingRosters + (guildId to slots),
                )
            },
            onClose = { editingOpeningRoster = false },
        )
        return
    }

    Column(Modifier.fillMaxSize()) {
        Surface(color = MaterialTheme.colorScheme.surface) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(profile.name, style = MaterialTheme.typography.titleMedium)
                        StatusBadge("修订 ${profile.revision}", tone = StatusTone.NEUTRAL)
                        if (dirty) StatusBadge("未保存", tone = StatusTone.WARN)
                    }
                    Text("桌面原生策略编辑器 · 保存后从下次任务生效", style = MaterialTheme.typography.bodySmall, color = Muted)
                }
                TextButton(onClick = { confirmReset = true }, enabled = !saving) { Text("恢复默认") }
                TextButton(onClick = { requestExit(onClose) }, enabled = !saving) {
                    Symbol("arrow_back", modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("返回策略列表")
                }
            }
        }
        HorizontalDivider(color = Line)

        Row(Modifier.weight(1f).fillMaxWidth()) {
            Surface(Modifier.width(176.dp).fillMaxHeight(), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Column(Modifier.verticalScroll(rememberScrollState()).padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("策略分类", Modifier.padding(horizontal = 8.dp, vertical = 6.dp), style = MaterialTheme.typography.labelMedium, color = Muted)
                    StrategyGroup.entries.forEach { item ->
                        val active = group == item
                        Surface(
                            onClick = { group = item },
                            shape = DesktopCorners.sm,
                            color = if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
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

            Column(
                Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Message(message)
                when (group) {
                    StrategyGroup.SCORING -> {
                        if (desktopSettings.battleOnnxEnabled) {
                            Message("当前已开启 ONNX 选队模型：EX / Boss 优先由 NN 专属策略决策，本页评分权重仅用于普通战与模型回退兜底。", tone = StatusTone.INFO)
                        }
                        Panel("个人评分与权重融合") {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Column(Modifier.weight(1f)) {
                                    Text("角色个人评分（当前已修改 ${draft.personalRoleScores.size} 人）", style = MaterialTheme.typography.bodyMedium)
                                    Text("个人评分按下方「玩家评分占比」参与选人和战斗编组。", style = MaterialTheme.typography.bodySmall, color = Muted)
                                }
                                OutlinedButton(onClick = { requestExit(onOpenRatings) }, enabled = !saving, shape = MaterialTheme.shapes.small) {
                                    Text("管理个人评分 →")
                                }
                            }
                            HorizontalDivider(color = Line)
                            StrategySliderRow("玩家评分占比", draft.playerWeight, 0..100, "%", !saving) { draft = draft.copy(playerWeight = it) }
                        }
                        Panel("系统评分相对权重（自动归一化）") {
                            val sum = (draft.damageWeight + draft.survivalWeight + draft.functionWeight + draft.formationWeight + draft.cohesionWeight).coerceAtLeast(1)
                            Text(
                                "当前归一化占比：伤害 ${draft.damageWeight * 100 / sum}% · 生存 ${draft.survivalWeight * 100 / sum}% · 功能 ${draft.functionWeight * 100 / sum}% · 阵型 ${draft.formationWeight * 100 / sum}% · 协同 ${draft.cohesionWeight * 100 / sum}%",
                                style = MaterialTheme.typography.bodySmall,
                                color = Accent,
                            )
                            StrategySliderRow("系统：伤害", draft.damageWeight, 0..100, "", !saving) { draft = draft.copy(damageWeight = it) }
                            StrategySliderRow("系统：生存", draft.survivalWeight, 0..100, "", !saving) { draft = draft.copy(survivalWeight = it) }
                            StrategySliderRow("系统：功能", draft.functionWeight, 0..100, "", !saving) { draft = draft.copy(functionWeight = it) }
                            StrategySliderRow("系统：阵型", draft.formationWeight, 0..100, "", !saving) { draft = draft.copy(formationWeight = it) }
                            StrategySliderRow("系统：协同", draft.cohesionWeight, 0..100, "", !saving) { draft = draft.copy(cohesionWeight = it) }
                        }
                        Panel("配队修正与有效效果") {
                            StrategySwitchRow(
                                label = "属性配队增伤",
                                detail = "关闭后仍识别角色属性，但评分不再计入同属性 2/3/4/5 人 8/16/25/75% 增伤。",
                                checked = draft.attributePairingBonusEnabled,
                                enabled = !saving,
                            ) { draft = draft.copy(attributePairingBonusEnabled = it) }
                            HorizontalDivider(color = Line)
                            StrategySliderRow("EX / Boss 物法混编扣分", draft.mixedDamagePenalty, 0..30, "分", !saving) {
                                draft = draft.copy(mixedDamagePenalty = it)
                            }
                            StrategySliderRow("有效效果角色系统加分", draft.effectiveCharacterSystemBonus, 0..30, "分", !saving) {
                                draft = draft.copy(effectiveCharacterSystemBonus = it)
                            }
                        }
                    }

                    StrategyGroup.ROSTER -> {
                        Panel("多队与角色奖励权重") {
                            Text("第一队部署权重固定为 100%；下方调整第二、三队对角色奖励价值的贡献权重。", style = MaterialTheme.typography.bodySmall, color = Muted)
                            StrategySliderRow("第二队部署权重", draft.secondTeamWeight, 0..100, "%", !saving) { draft = draft.copy(secondTeamWeight = it) }
                            StrategySliderRow("第三队部署权重", draft.thirdTeamWeight, 0..100, "%", !saving) { draft = draft.copy(thirdTeamWeight = it) }
                            StrategySliderRow("重复纯职能基础扣分", draft.duplicateRolePenalty, 0..30, "分", !saving) { draft = draft.copy(duplicateRolePenalty = it) }
                        }
                        Panel("开局角色优先级") {
                            Text(desktopOpeningRosterSummary(draft.openingRosters), style = MaterialTheme.typography.bodyMedium)
                            Text("可为每个公会自定义 3 个槽位的候选角色顺序；公会附赠角色由游戏决定。", style = MaterialTheme.typography.bodySmall, color = Muted)
                            draft.openingRosterError()?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                            OutlinedButton(onClick = { editingOpeningRoster = true }, enabled = !saving, shape = MaterialTheme.shapes.small) {
                                Text("编辑开局角色")
                            }
                        }
                    }

                    StrategyGroup.COMBAT -> {
                        Panel("前排生存门槛（三档）") {
                            val currentMode = draft.effectiveFrontlineSurvivalMode
                            LabyrinthFrontlineSurvivalMode.entries.forEach { mode ->
                                StrategyRadioCard(
                                    label = mode.label,
                                    summary = mode.summary,
                                    detail = mode.description,
                                    selected = currentMode == mode,
                                    enabled = !saving,
                                ) { draft = draft.withFrontlineSurvivalMode(mode) }
                            }
                            HorizontalDivider(color = Line)
                            StrategySwitchRow(
                                label = "普战跳编队",
                                detail = "仅普通战首次尝试沿用已确认的满 5 人队伍，跳过重新选人；关闭按推荐阵容编组。",
                                checked = draft.normalBattleSkipTeamSelectionEnabled,
                                enabled = !saving,
                            ) { draft = draft.copy(normalBattleSkipTeamSelectionEnabled = it) }
                        }
                        Panel("Boss 编组与失败重试") {
                            StrategyRadioCard(
                                label = "Boss 多队编组（默认）",
                                summary = "最多自动配置三支互不重复队伍",
                                detail = "按当前角色池自动组成 3/2/1 队并优化总评分。",
                                selected = draft.bossTeamMode == LabyrinthBossTeamMode.MULTI_TEAM,
                                enabled = !saving,
                            ) { draft = draft.copy(bossTeamMode = LabyrinthBossTeamMode.MULTI_TEAM) }
                            StrategyRadioCard(
                                label = "Boss 单队编组",
                                summary = "只配置第一队",
                                detail = "确认第二、三队为空后开战。",
                                selected = draft.bossTeamMode == LabyrinthBossTeamMode.SINGLE_TEAM,
                                enabled = !saving,
                            ) { draft = draft.copy(bossTeamMode = LabyrinthBossTeamMode.SINGLE_TEAM) }
                            HorizontalDivider(color = Line)
                            StrategySwitchRow(
                                label = "优先纯物理 / 纯法术编组",
                                detail = "Boss 启用与 EX 相同的物法混编扣分；关闭后 Boss 可自由混编。",
                                checked = draft.preferPureBossDamageSystem,
                                enabled = !saving,
                            ) { draft = draft.copy(preferPureBossDamageSystem = it) }
                            StrategySwitchRow(
                                label = "单队 Boss 失败后切换多队",
                                detail = "仅单队模式生效。达到重试次数仍失败时切多队（开启生存门槛时 1 号位放宽到 40 分）；开启 NN 时按 NN 策略风格阈值提前切换。",
                                checked = draft.singleBossFallbackToMultiAfterThreeFailures,
                                enabled = !saving,
                            ) { draft = draft.copy(singleBossFallbackToMultiAfterThreeFailures = it) }
                            StrategySliderRow(
                                label = "单队 Boss 重新挑战次数",
                                value = draft.singleBossRetryCountBeforeMulti,
                                range = 0..10,
                                suffix = "次",
                                enabled = !saving && draft.singleBossFallbackToMultiAfterThreeFailures,
                            ) { draft = draft.copy(singleBossRetryCountBeforeMulti = it) }
                            HorizontalDivider(color = Line)
                            StrategySwitchRow(
                                label = "3 次失败后自动重刷开局",
                                detail = "首次挑战 + 2 次重挑战均失败后停止视觉执行，调用刷开局撤退并重刷。",
                                checked = draft.rerollAfterThreeBattleFailures,
                                enabled = !saving,
                            ) { draft = draft.copy(rerollAfterThreeBattleFailures = it) }
                        }
                    }

                    StrategyGroup.RELIC_SHOP -> {
                        if (desktopSettings.pathBPlanningEnabled) {
                            Message("当前已开启 Path B 全局规划：遗物与商店购买决策优先由 Path B 估算，下方遗物层数策略仅用于兜底；商店开关与刷新起始区照常生效。", tone = StatusTone.INFO)
                        }
                        Panel("遗物层数策略") {
                            StrategySliderRow("基础目标层数", draft.baselineTarget, 1..4, "层", !saving) { draft = draft.copy(baselineTarget = it) }
                            StrategySliderRow("前期加分截止区域", draft.baselineLastArea, 0..5, "区", !saving) { draft = draft.copy(baselineLastArea = it) }
                            StrategySliderRow("转追削弱最低层数", draft.debuffPivotMinimum, 5..15, "层", !saving) { draft = draft.copy(debuffPivotMinimum = it) }
                            StrategySliderRow("削弱落后容忍量", draft.debuffPivotTolerance, 0..5, "层", !saving) { draft = draft.copy(debuffPivotTolerance = it) }
                        }
                        Panel("遗物印记优先级") {
                            Text("留空使用内置顺序；能一次跨到 15 层的选项始终优先。", style = MaterialTheme.typography.bodySmall, color = Muted)
                            DesktopRelicPriorityEditor(draft.relicMarkPriority, !saving) { draft = draft.copy(relicMarkPriority = it) }
                        }
                        Panel("商店购买与刷新") {
                            StrategySwitchRow("购买遗物", "关闭后进入商店直接退出，不购买也不刷新。", draft.buyRelics, !saving) { draft = draft.copy(buyRelics = it) }
                            StrategySwitchRow("允许商店刷新", "买完当前可确认遗物后，才花费金币刷新商店。", draft.refreshShop, !saving && draft.buyRelics) { draft = draft.copy(refreshShop = it) }
                            if (draft.buyRelics && draft.refreshShop) {
                                StrategySliderRow("允许刷新起始区域", draft.refreshFromArea, 1..5, "区", !saving) { draft = draft.copy(refreshFromArea = it) }
                                if (draft.refreshFromArea < 5) {
                                    Text("注意：提前刷新会消耗后续商店金币。", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }

                    StrategyGroup.EVENTS -> {
                        Panel("事件战斗胜率估值") {
                            Text("仅用于估算事件战斗奖励期望值，不代表实际胜率保证。", style = MaterialTheme.typography.bodySmall, color = Muted)
                            StrategySliderRow("普通战估计胜率", draft.normalWinRate, 0..100, "%", !saving) { draft = draft.copy(normalWinRate = it) }
                            StrategySliderRow("极难战估计胜率", draft.extremeWinRate, 0..100, "%", !saving) { draft = draft.copy(extremeWinRate = it) }
                        }
                        Panel("事件自定义分支") {
                            StrategySwitchRow(
                                label = "启用事件自定义",
                                detail = "开启后优先点击指定分支；按钮不可用时自动回退默认决策，不硬点。",
                                checked = draft.eventCustomizationEnabled,
                                enabled = !saving,
                            ) { draft = draft.copy(eventCustomizationEnabled = it) }
                            val configured = draft.configuredEventChoiceOverrides()
                            LabyrinthCustomEventCatalog.cards.forEach { card ->
                                val selectedChoice = card.choices.firstOrNull { it.runtimeName == configured[card.runtimeName] }
                                Surface(
                                    Modifier.fillMaxWidth(),
                                    shape = DesktopCorners.sm,
                                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                                    border = BorderStroke(1.dp, Line),
                                ) {
                                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(card.name, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                                            if (card.id == "arena") StatusBadge("四区共用", tone = StatusTone.INFO)
                                        }
                                        card.choices.sortedBy { it.slot }.forEach { choice ->
                                            val chosen = selectedChoice == choice
                                            val activeEnabled = !saving && draft.eventCustomizationEnabled
                                            Row(
                                                Modifier.fillMaxWidth()
                                                    .selectable(selected = chosen, enabled = activeEnabled, role = Role.RadioButton) {
                                                        draft = draft.copy(
                                                            eventChoiceOverrides = draft.eventChoiceOverrides + (card.runtimeName to choice.runtimeName),
                                                        )
                                                    }
                                                    .padding(vertical = 3.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                            ) {
                                                RadioButton(selected = chosen, onClick = null, enabled = activeEnabled, modifier = Modifier.size(22.dp))
                                                Column(Modifier.weight(1f)) {
                                                    Text(choice.name, style = MaterialTheme.typography.bodySmall, color = if (activeEnabled) LocalContentColor.current else Muted)
                                                    Text(choice.description, style = MaterialTheme.typography.labelSmall, color = Muted)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    StrategyGroup.EQUIPMENT_PERF -> {
                        Panel("特别装备自动穿戴") {
                            Text("仅在队伍编组页尝试点击「特别装备设定 → 自动穿戴」，并按勾选关卡类型生效。", style = MaterialTheme.typography.bodySmall, color = Muted)
                            StrategyRadioCard(
                                label = "关闭（默认）",
                                summary = "0 次额外点击",
                                detail = "编组页不触碰特别装备。",
                                selected = draft.specialEquipmentMode == LabyrinthSpecialEquipmentMode.OFF,
                                enabled = !saving,
                            ) { draft = draft.copy(specialEquipmentMode = LabyrinthSpecialEquipmentMode.OFF) }
                            StrategyRadioCard(
                                label = "自动穿戴",
                                summary = "按关卡类型触发",
                                detail = "进入所选关卡类型的编组页时自动穿戴。",
                                selected = draft.specialEquipmentMode == LabyrinthSpecialEquipmentMode.AUTO_EQUIP,
                                enabled = !saving,
                            ) { draft = draft.copy(specialEquipmentMode = LabyrinthSpecialEquipmentMode.AUTO_EQUIP) }
                            if (draft.specialEquipmentMode == LabyrinthSpecialEquipmentMode.AUTO_EQUIP) {
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    LabyrinthCombatKind.entries.forEach { kind ->
                                        val label = when (kind) {
                                            LabyrinthCombatKind.NORMAL -> "普通战"
                                            LabyrinthCombatKind.EX -> "EX 战"
                                            LabyrinthCombatKind.BOSS -> "BOSS 战"
                                        }
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Checkbox(
                                                checked = draft.autoEquipsSpecialEquipment(kind),
                                                onCheckedChange = { checked ->
                                                    draft = draft.copy(
                                                        specialEquipmentKinds = if (checked) draft.specialEquipmentKinds + kind else draft.specialEquipmentKinds - kind,
                                                    )
                                                },
                                                enabled = !saving,
                                            )
                                            Text(label, style = MaterialTheme.typography.bodySmall)
                                        }
                                    }
                                }
                                if (draft.specialEquipmentKinds.isEmpty()) {
                                    Text(LabyrinthStrategySettings.SPECIAL_EQUIPMENT_EMPTY_KINDS_ERROR, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                        Panel("识别响应速度") {
                            Text("采集每 300 ms 产出一帧；300 ms 为每帧处理，500 ms 为每两帧处理一帧（更省 CPU）。", style = MaterialTheme.typography.bodySmall, color = Muted)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                FilterChip(
                                    selected = draft.responseIntervalMillis == RESPONSE_INTERVAL_FAST,
                                    onClick = { draft = draft.copy(responseIntervalMillis = RESPONSE_INTERVAL_FAST) },
                                    label = { Text("${RESPONSE_INTERVAL_FAST} ms · 快速（默认）") },
                                    enabled = !saving,
                                )
                                FilterChip(
                                    selected = draft.responseIntervalMillis == RESPONSE_INTERVAL_STANDARD,
                                    onClick = { draft = draft.copy(responseIntervalMillis = RESPONSE_INTERVAL_STANDARD) },
                                    label = { Text("${RESPONSE_INTERVAL_STANDARD} ms · 标准省电") },
                                    enabled = !saving,
                                )
                            }
                        }
                    }
                }
            }
        }

        HorizontalDivider(color = Line)
        val validationError = draft.validationError()
        Surface(color = MaterialTheme.colorScheme.surface) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    validationError ?: if (saving) "正在保存…" else if (dirty) "有未保存的修改" else "配置已保存 · 下次任务生效",
                    Modifier.weight(1f),
                    color = if (validationError != null) MaterialTheme.colorScheme.error else if (dirty) LocalDesktopColors.current.warn else Muted,
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedButton(onClick = { requestExit(onClose) }, enabled = !saving, shape = MaterialTheme.shapes.small) { Text("取消") }
                Button(
                    onClick = { onSave(draft) },
                    enabled = !saving && validationError == null && draft != saved,
                    shape = MaterialTheme.shapes.small,
                ) { Text(if (saving) "保存中…" else "保存配置") }
            }
        }
    }

    if (confirmReset && LocalControlVisible.current) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("恢复全部默认策略？") },
            text = { Text("将策略字段恢复为默认值；需点击「保存配置」才会覆盖当前策略。") },
            confirmButton = { TextButton(onClick = { draft = LabyrinthStrategySettings(); confirmReset = false }) { Text("恢复默认") } },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("取消") } },
        )
    }

    pendingExit?.takeIf { LocalControlVisible.current }?.let { next ->
        AlertDialog(
            onDismissRequest = { pendingExit = null },
            title = { Text("放弃未保存的策略修改？") },
            text = { Text("策略草稿尚未保存，离开将丢弃这些修改。") },
            confirmButton = { TextButton(onClick = { pendingExit = null; next() }) { Text("放弃修改") } },
            dismissButton = { TextButton(onClick = { pendingExit = null }) { Text("继续编辑") } },
        )
    }
}

@Composable
private fun StrategySliderRow(
    label: String,
    value: Int,
    range: IntRange,
    suffix: String,
    enabled: Boolean,
    onChange: (Int) -> Unit,
) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = if (enabled) LocalContentColor.current else Muted)
            TextButton(
                onClick = { onChange((value - 1).coerceAtLeast(range.first)) },
                enabled = enabled && value > range.first,
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                modifier = Modifier.sizeIn(minWidth = 28.dp, minHeight = 28.dp),
            ) { Text("−") }
            BasicTextField(
                value = text,
                onValueChange = { raw ->
                    val digits = raw.filter(Char::isDigit).take(4)
                    text = digits
                    digits.toIntOrNull()?.takeIf { it in range }?.let(onChange)
                },
                enabled = enabled,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, color = if (enabled) LocalContentColor.current else Muted),
                cursorBrush = SolidColor(Accent),
                modifier = Modifier.width(52.dp).background(MaterialTheme.colorScheme.surfaceContainerLow, DesktopCorners.sm)
                    .border(1.dp, if (text.toIntOrNull() in range) Line else MaterialTheme.colorScheme.error, DesktopCorners.sm)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
            if (suffix.isNotEmpty()) Text(suffix, style = MaterialTheme.typography.bodySmall, color = Muted)
            TextButton(
                onClick = { onChange((value + 1).coerceAtMost(range.last)) },
                enabled = enabled && value < range.last,
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                modifier = Modifier.sizeIn(minWidth = 28.dp, minHeight = 28.dp),
            ) { Text("+") }
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { onChange(it.roundToInt().coerceIn(range)) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            enabled = enabled,
            modifier = Modifier.fillMaxWidth().height(24.dp).semantics { contentDescription = label },
        )
    }
}

@Composable
private fun StrategySwitchRow(
    label: String,
    detail: String,
    checked: Boolean,
    enabled: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth()
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)
            .padding(vertical = 4.dp),
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

@Composable
internal fun StrategyRadioCard(
    label: String,
    summary: String,
    detail: String,
    selected: Boolean,
    enabled: Boolean,
    onSelect: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onSelect),
        shape = DesktopCorners.sm,
        color = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f) else MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, if (selected) Accent else Line),
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            RadioButton(selected = selected, onClick = null, enabled = enabled, modifier = Modifier.size(22.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                    Text(summary, style = MaterialTheme.typography.labelSmall, color = if (selected) Accent else Muted)
                }
                Text(detail, style = MaterialTheme.typography.bodySmall, color = Muted)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DesktopRelicPriorityEditor(
    priority: List<LabyrinthRelicMark>,
    enabled: Boolean,
    onChange: (List<LabyrinthRelicMark>) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        priority.forEachIndexed { index, mark ->
            Surface(shape = DesktopCorners.sm, color = MaterialTheme.colorScheme.surfaceContainerLow, border = BorderStroke(1.dp, Line)) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("${index + 1}. ${mark.label}", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    TextButton(
                        onClick = { onChange(priority.toMutableList().also { it.add(index - 1, it.removeAt(index)) }) },
                        enabled = enabled && index > 0,
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                    ) { Text("上移", style = MaterialTheme.typography.labelSmall) }
                    TextButton(
                        onClick = { onChange(priority - mark) },
                        enabled = enabled,
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                    ) { Text("移除", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall) }
                }
            }
        }
        val remaining = LabyrinthRelicMark.entries.filterNot { it in priority }
        if (remaining.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("添加印记：", Modifier.align(Alignment.CenterVertically), style = MaterialTheme.typography.bodySmall, color = Muted)
                remaining.forEach { mark ->
                    OutlinedButton(onClick = { onChange(priority + mark) }, enabled = enabled, shape = MaterialTheme.shapes.small) {
                        Text("+ ${mark.label}")
                    }
                }
            }
        }
        if (priority.isNotEmpty()) {
            TextButton(onClick = { onChange(emptyList()) }, enabled = enabled) { Text("恢复内置顺序") }
        }
    }
}
