package com.landosol.toolbox.desktop.app.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.landosol.toolbox.desktop.app.control.ControlModelCodec
import com.landosol.toolbox.desktop.app.control.ControlOps
import com.landosol.toolbox.desktop.app.control.ControlSessionUi
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import com.landosol.toolbox.desktop.app.ui.theme.DesktopCorners
import com.landosol.toolbox.desktop.app.ui.theme.LocalDesktopColors
import com.landosol.toolbox.labyrinth.*
import com.landosol.toolbox.labyrinth.node.LabyrinthNodeTypes

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DesktopRerollSettingsDialog(
    graph: ControlSessionUi,
    state: LabyrinthUiState,
    busy: Boolean,
    onClose: () -> Unit,
) {
    var lastSaved by remember(state.selectedAccount?.id) {
        mutableStateOf(state.rerollSettings())
    }
    var draft by remember(state.selectedAccount?.id) {
        mutableStateOf(lastSaved)
    }
    LaunchedEffect(state.rerollSettings()) {
        val incoming = state.rerollSettings()
        if (incoming != lastSaved) {
            if (draft == lastSaved) draft = incoming
            lastSaved = incoming
        }
    }
    val actions = rememberActions(graph)
    var localError by remember { mutableStateOf<String?>(null) }
    val editable = !busy && !state.isWorking && !actions.working && state.settingsReady && state.selectedAccount != null

    Surface(Modifier.fillMaxSize(), color = Background) {
        Column(Modifier.fillMaxSize()) {
            Surface(color = MaterialTheme.colorScheme.surface) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("开局与路线设置", style = MaterialTheme.typography.titleMedium)
                        Text(
                            state.selectedAccount?.let { "当前账号：${it.alias} · 核验开局，并设置难度、路线、第三格、Boss 与撤退规则" }
                                ?: "请先选择账号后再保存开局与路线设置",
                            color = Muted,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    TextButton(
                        onClick = {
                            draft = LabyrinthRerollSettings(
                                guildId = state.selectedGuildId,
                                difficulty = LabyrinthRerollOptions.DEFAULT_DIFFICULTY.coerceAtMost(
                                    state.availableDifficulties.lastOrNull() ?: LabyrinthRerollOptions.DEFAULT_DIFFICULTY,
                                ),
                            )
                            localError = null
                        },
                        enabled = editable,
                    ) { Text("恢复默认") }
                    TextButton(onClick = onClose) {
                        Symbol("arrow_back", modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("返回运行台 · Esc")
                    }
                }
            }
            HorizontalDivider(color = Line)

            Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Message(actions.message ?: localError, tone = StatusTone.ERROR)

                Panel("当前账号开局核验") {
                    OpeningReadCard(graph, state, busy, actions)
                }

                Panel("难度与路线判定") {
                    Text("迷宫难度", style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        state.availableDifficulties.forEach { diff ->
                            FilterChip(
                                selected = draft.difficulty == diff,
                                onClick = { draft = draft.copy(difficulty = diff) },
                                label = { Text("难度 $diff") },
                                enabled = editable,
                            )
                        }
                    }

                    HorizontalDivider(color = Line)
                    val activeMode = DesktopRouteMode.from(draft.routeEvaluationMode)
                    Text("路线判定模式", style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        DesktopRouteMode.entries.forEach { mode ->
                            DesktopTooltip(mode.summary, delayMillis = 150) {
                                FilterChip(
                                    selected = activeMode == mode,
                                    onClick = { draft = draft.selectDesktopRouteMode(mode) },
                                    label = { Text(mode.label) },
                                    enabled = editable,
                                )
                            }
                        }
                    }

                    when (activeMode) {
                        DesktopRouteMode.VALUE -> {
                            Text("每个区域允许少拿贵重格数", style = MaterialTheme.typography.bodySmall, color = Muted)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                (0..LabyrinthRerollOptions.MAX_VALUE_ALLOWANCE).forEach { allowance ->
                                    FilterChip(
                                        selected = draft.valueAllowance == allowance,
                                        onClick = { draft = draft.copy(valueAllowance = allowance) },
                                        label = { Text("$allowance 格") },
                                        enabled = editable,
                                    )
                                }
                            }
                            Text(
                                "0 表示必须拿到当前地图可达的全部贵重格；Boss 始终为硬条件，不用少拿额度交换。",
                                style = MaterialTheme.typography.bodySmall,
                                color = Muted,
                            )
                        }
                        DesktopRouteMode.OFFICIAL_SCORE -> {
                            Text(
                                "按官方结算分规则规划最高分路线；下方 Boss 名单与 3/5 区第 3 格偏好仍作为硬筛选条件。",
                                style = MaterialTheme.typography.bodySmall,
                                color = Muted,
                            )
                        }
                        DesktopRouteMode.TEMPLATE -> {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(
                                    checked = draft.isTemplateStrict(),
                                    onCheckedChange = { draft = draft.withTemplateStrict(it, preserveLegacyThirdBlock = true) },
                                    enabled = editable,
                                )
                                Column(Modifier.weight(1f)) {
                                    Text("严格匹配列位模板", style = MaterialTheme.typography.bodyMedium)
                                    Text(
                                        "勾选后按下方各列节点逐列严格匹配连通路线；取消勾选则不限制格子与第 3 格，仅筛选下方 Boss 名单。",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Muted,
                                    )
                                }
                            }
                            if (draft.isTemplateStrict()) {
                                CustomNodePlanGrid(
                                    plan = draft.customNodePlan,
                                    difficulty = draft.difficulty,
                                    enabled = editable,
                                    onChange = { plan ->
                                        val nextPlan = if (draft.routeEvaluationMode == LabyrinthRouteEvaluationMode.LEGACY_TEMPLATE) {
                                            plan.copy(thirdBlockChoice = draft.thirdBlockChoice)
                                        } else {
                                            plan
                                        }
                                        draft = draft.copy(
                                            routeEvaluationMode = LabyrinthRouteEvaluationMode.CUSTOM_NODES,
                                            perfectStart = true,
                                            customNodePlan = nextPlan,
                                        )
                                    },
                                )
                            }
                        }
                    }

                    HorizontalDivider(color = Line)
                    val thirdBlockActive = !(activeMode == DesktopRouteMode.TEMPLATE && !draft.isTemplateStrict())
                    val useCustomThird = draft.routeEvaluationMode == LabyrinthRouteEvaluationMode.CUSTOM_NODES
                    val currentThird = if (useCustomThird) draft.customNodePlan.thirdBlockChoice else draft.thirdBlockChoice
                    Text("区域 3 / 5 第三格偏好（遗物 vs 事件）", style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        LabyrinthThirdBlockChoice.entries.forEach { choice ->
                            FilterChip(
                                selected = currentThird == choice,
                                onClick = {
                                    draft = if (useCustomThird) {
                                        draft.copy(customNodePlan = draft.customNodePlan.copy(thirdBlockChoice = choice))
                                    } else {
                                        draft.copy(thirdBlockChoice = choice)
                                    }
                                },
                                label = { Text(choice.label) },
                                enabled = editable && thirdBlockActive,
                            )
                        }
                    }
                    Text(
                        when (activeMode) {
                            DesktopRouteMode.VALUE -> "高价值路线中不绑定列号；选择「两者都行」时同分路线优先遗物。"
                            DesktopRouteMode.OFFICIAL_SCORE -> "作为区域 3 / 5 第 3 格筛选的硬偏好参与官方高分规划。"
                            DesktopRouteMode.TEMPLATE -> {
                                if (draft.isTemplateStrict()) "作用于区域 3 / 5 第 3 列，作为模板第 3 列节点类型要求。"
                                else "已取消「严格匹配列位模板」，当前仅筛选 Boss 名单，第 3 格暂不限制。"
                            }
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = Muted,
                    )

                    HorizontalDivider(color = Line)
                    var targetScoreInput by remember(state.selectedAccount?.id) {
                        mutableStateOf(if (draft.targetScore > 0L) draft.targetScore.toString() else "")
                    }
                    LaunchedEffect(draft.targetScore) {
                        val parsed = targetScoreInput.toLongOrNull() ?: 0L
                        if (parsed != draft.targetScore) {
                            targetScoreInput = if (draft.targetScore > 0L) draft.targetScore.toString() else ""
                        }
                    }
                    Text("刷分目标（DP 自动撤退门禁）", style = MaterialTheme.typography.labelLarge)
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        OutlinedTextField(
                            value = targetScoreInput,
                            onValueChange = { value ->
                                val filtered = value.filter(Char::isDigit).take(6)
                                targetScoreInput = filtered
                                draft = draft.copy(targetScore = filtered.toLongOrNull() ?: 0L)
                            },
                            enabled = editable,
                            singleLine = true,
                            placeholder = { Text("0（关闭），如 50000") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.width(180.dp),
                        )
                        Text(
                            "按游戏最终结算总分设置目标（0–${LabyrinthRerollOptions.MAX_TARGET_SCORE}，0 为关闭）。开局筛选与局内实时 DP 判定无法达标时自动撤退并重刷下一局。",
                            Modifier.weight(1f),
                            style = MaterialTheme.typography.bodySmall,
                            color = Muted,
                        )
                    }
                }

                Panel("Boss 名单过滤（不勾选即全部接受）") {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        BossChecklistColumn(
                            title = "区域 3 Boss",
                            options = state.area3BossOptions,
                            selectedIds = draft.area3BossIds,
                            enabled = editable,
                            modifier = Modifier.weight(1f),
                            onToggle = { id ->
                                draft = draft.copy(
                                    area3BossIds = if (id in draft.area3BossIds) draft.area3BossIds - id else draft.area3BossIds + id,
                                )
                            },
                            onClear = { draft = draft.copy(area3BossIds = emptySet()) },
                        )
                        BossChecklistColumn(
                            title = "区域 5 Boss",
                            options = state.area5BossOptions,
                            selectedIds = draft.area5BossIds,
                            enabled = editable,
                            modifier = Modifier.weight(1f),
                            onToggle = { id ->
                                draft = draft.copy(
                                    area5BossIds = if (id in draft.area5BossIds) draft.area5BossIds - id else draft.area5BossIds + id,
                                )
                            },
                            onClear = { draft = draft.copy(area5BossIds = emptySet()) },
                        )
                    }
                }

                Panel("刷取上限与撤退规则") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = draft.rerollUntilFound,
                            onCheckedChange = { draft = draft.copy(rerollUntilFound = it) },
                            enabled = editable,
                        )
                        Column(Modifier.weight(1f)) {
                            Text("刷到目标为止", style = MaterialTheme.typography.bodyMedium)
                            Text("持续刷新直到满足路线与 Boss 条件；遇到状态异常或连续撤退失败会安全停止。", style = MaterialTheme.typography.bodySmall, color = Muted)
                        }
                    }
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text("最大尝试次数", style = MaterialTheme.typography.bodyMedium)
                        OutlinedTextField(
                            value = draft.maxAttempts,
                            onValueChange = { value ->
                                if (value.length <= 3 && value.all(Char::isDigit)) {
                                    draft = draft.copy(maxAttempts = value)
                                }
                            },
                            enabled = editable && !draft.rerollUntilFound,
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.width(120.dp),
                        )
                        Text("1–${LabyrinthRerollOptions.MAX_ATTEMPTS} 次（未开启无限刷取时生效）", style = MaterialTheme.typography.bodySmall, color = Muted)
                    }
                    HorizontalDivider(color = Line)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = draft.retireExisting,
                            onCheckedChange = { draft = draft.copy(retireExisting = it) },
                            enabled = editable,
                        )
                        Column(Modifier.weight(1f)) {
                            Text("允许彻底撤退当前开局", style = MaterialTheme.typography.bodyMedium)
                            Text("只撤退已确认不符合目标的开局；请求发出后若断线会保留检查点并在重连后核对。", style = MaterialTheme.typography.bodySmall, color = Muted)
                        }
                    }
                }
            }

            HorizontalDivider(color = Line)
            val validation = draft.validationError() ?: if (draft.difficulty !in state.availableDifficulties) "所选难度尚未解锁" else null
            val changed = draft != state.rerollSettings()
            Surface(color = MaterialTheme.colorScheme.surface) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        validation ?: when {
                            state.selectedAccount == null -> "请先选择账号"
                            !state.settingsReady -> "正在读取账号配置…"
                            changed -> "有未保存的更改"
                            else -> "已与当前账号配置同步"
                        },
                        Modifier.weight(1f),
                        color = if (validation != null) MaterialTheme.colorScheme.error else if (changed) LocalDesktopColors.current.warn else Muted,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedButton(onClick = onClose, shape = MaterialTheme.shapes.small) { Text("取消") }
                    Button(
                        onClick = {
                            if (validation != null) {
                                localError = validation
                                return@Button
                            }
                            localError = null
                            val toSave = draft
                            val accountId = state.selectedAccount?.id
                            actions.run {
                                graph.request(
                                    ControlOps.SAVE_REROLL,
                                    buildJsonObject {
                                        put("value", ControlModelCodec.encode(toSave))
                                        accountId?.let { put("accountId", it) }
                                    },
                                )
                                graph.awaitSnapshot("保存结果尚未确认，请稍后重试") { snap ->
                                    snap.console.task.selectedAccount?.id == accountId && snap.console.task.rerollSettings() == toSave
                                }
                                onClose()
                            }
                        },
                        enabled = editable && validation == null && changed,
                        shape = MaterialTheme.shapes.small,
                    ) {
                        Text(if (actions.working) "保存中…" else "保存")
                    }
                }
            }
        }
    }
}

@Composable
private fun OpeningReadCard(graph: ControlSessionUi, state: LabyrinthUiState, busy: Boolean, actions: UiActions) {
    val readStatus = state.currentOpeningReadStatus
    val statusText = when {
        state.selectedAccount == null -> "尚未选择账号"
        readStatus == LabyrinthCurrentOpeningReadStatus.NOT_READ -> "尚未读取当前开局"
        readStatus == LabyrinthCurrentOpeningReadStatus.READING -> "正在登录并读取当前开局"
        readStatus == LabyrinthCurrentOpeningReadStatus.LOGIN_VERIFICATION_REQUIRED -> "登录需要人工验证"
        readStatus == LabyrinthCurrentOpeningReadStatus.NO_ACTIVE_OPENING -> "当前无进行中的黎明界"
        readStatus == LabyrinthCurrentOpeningReadStatus.TARGET -> "当前开局符合目标条件"
        readStatus == LabyrinthCurrentOpeningReadStatus.NOT_TARGET -> "当前开局不符合已保存路线或难度"
        readStatus == LabyrinthCurrentOpeningReadStatus.PENDING_VERIFICATION -> "读取未完成，需要重新验证"
        readStatus == LabyrinthCurrentOpeningReadStatus.FAILED -> "读取失败"
        else -> "读取已取消"
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(state.selectedAccount?.alias ?: "未选择账号", style = MaterialTheme.typography.titleSmall)
                StatusBadge(
                    statusText,
                    tone = when (readStatus) {
                        LabyrinthCurrentOpeningReadStatus.TARGET -> StatusTone.OK
                        LabyrinthCurrentOpeningReadStatus.FAILED, LabyrinthCurrentOpeningReadStatus.NOT_TARGET -> StatusTone.ERROR
                        LabyrinthCurrentOpeningReadStatus.READING, LabyrinthCurrentOpeningReadStatus.PENDING_VERIFICATION,
                        LabyrinthCurrentOpeningReadStatus.LOGIN_VERIFICATION_REQUIRED -> StatusTone.WARN
                        else -> StatusTone.NEUTRAL
                    },
                )
            }
            state.currentOpeningReadMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Muted) }
            if (state.routeVerdict != null || state.currentGuildId != null) {
                val guildName = state.currentGuildId?.let { id -> state.guildOptions.firstOrNull { it.guildId == id }?.name ?: "ID $id" }
                Text(
                    "判定：${state.routeVerdict?.label ?: "未知"} · ${guildName ?: "未读取公会"} · 难度 ${state.currentDifficulty ?: "-"}" +
                        (state.checkpointEnterId?.let { " · EnterID 尾号 ${it % 10_000}" } ?: "") +
                        (state.routeBlockIds.takeIf { it.isNotEmpty() }?.let { " · 已保存 ${it.size} 节点" }.orEmpty()),
                    style = MaterialTheme.typography.bodySmall,
                    color = Muted,
                )
            }
            state.verdictMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Muted) }
        }
        OutlinedButton(
            onClick = { actions.run { graph.labyrinthController.checkStatus() } },
            enabled = !busy && !state.isWorking && !actions.working && state.selectedAccount != null,
            shape = MaterialTheme.shapes.small,
        ) {
            Text(
                when (readStatus) {
                    LabyrinthCurrentOpeningReadStatus.NOT_READ -> "登录并读取"
                    LabyrinthCurrentOpeningReadStatus.PENDING_VERIFICATION,
                    LabyrinthCurrentOpeningReadStatus.FAILED,
                    LabyrinthCurrentOpeningReadStatus.CANCELLED -> "重新验证"
                    else -> "重新读取"
                },
            )
        }
    }
}

@Composable
private fun CustomNodePlanGrid(
    plan: LabyrinthCustomNodePlan,
    difficulty: Int,
    enabled: Boolean,
    onChange: (LabyrinthCustomNodePlan) -> Unit,
) {
    val charOrEvent = listOf(LabyrinthNodeTypes.LINK, LabyrinthNodeTypes.EVENT)
    val charOrRelic = listOf(LabyrinthNodeTypes.LINK, LabyrinthNodeTypes.RELIC)
    val area4Enabled = enabled && difficulty != 1
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("模板关键列位（默认全部为角色）", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
            if (plan.hasCustomSlots()) {
                TextButton(
                    onClick = { onChange(LabyrinthCustomNodePlan(thirdBlockChoice = plan.thirdBlockChoice)) },
                    enabled = enabled,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                ) { Text("恢复标准模板", style = MaterialTheme.typography.labelSmall) }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CustomNodeSlot("区域 1 · 第 3 列", plan.area1Column3, charOrEvent, enabled, Modifier.weight(1f)) {
                onChange(plan.copy(area1Column3 = it))
            }
            CustomNodeSlot("区域 1 · 第 5 列", plan.area1Column5, charOrEvent, enabled, Modifier.weight(1f)) {
                onChange(plan.copy(area1Column5 = it))
            }
            CustomNodeSlot("区域 2 · 第 2、6 列", plan.area2Columns2And6, charOrEvent, enabled, Modifier.weight(1f)) {
                onChange(plan.copy(area2Columns2And6 = it))
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CustomNodeSlot("区域 3 · 第 4 列", plan.area3Column4, charOrEvent, enabled, Modifier.weight(1f)) {
                onChange(plan.copy(area3Column4 = it))
            }
            CustomNodeSlot("区域 4 · 第 2 列", plan.area4Column2, charOrRelic, area4Enabled, Modifier.weight(1f)) {
                onChange(plan.copy(area4Column2 = it))
            }
            CustomNodeSlot("区域 4 · 第 7 列", plan.area4Column7, charOrRelic, area4Enabled, Modifier.weight(1f)) {
                onChange(plan.copy(area4Column7 = it))
            }
        }
        Text(
            if (difficulty == 1) "难度 1 仅检查区域 1–3；区域 4 的设置已保留但暂不参与。"
            else "区域 4 固定必有一事件；未勾选自定义的部分沿用标准连通要求。",
            style = MaterialTheme.typography.bodySmall,
            color = Muted,
        )
    }
}

@Composable
private fun CustomNodeSlot(
    title: String,
    selected: Int,
    options: List<Int>,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onSelect: (Int) -> Unit,
) {
    Surface(modifier, shape = DesktopCorners.sm, color = MaterialTheme.colorScheme.surfaceContainerLow, border = BorderStroke(1.dp, Line)) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.labelMedium, color = Muted)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                options.forEach { type ->
                    FilterChip(
                        selected = type == selected,
                        onClick = { onSelect(type) },
                        label = { Text(LabyrinthCustomNodePlan.labelOf(type), style = MaterialTheme.typography.bodySmall) },
                        enabled = enabled,
                    )
                }
            }
        }
    }
}

@Composable
private fun BossChecklistColumn(
    title: String,
    options: List<LabyrinthBossOption>,
    selectedIds: Set<Int>,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onToggle: (Int) -> Unit,
    onClear: () -> Unit,
) {
    Surface(modifier, shape = DesktopCorners.sm, color = MaterialTheme.colorScheme.surfaceContainerLow, border = BorderStroke(1.dp, Line)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                if (selectedIds.isNotEmpty()) {
                    TextButton(onClick = onClear, enabled = enabled, contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)) {
                        Text("不限", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            options.forEach { boss ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Checkbox(
                        checked = boss.unitId in selectedIds,
                        onCheckedChange = { onToggle(boss.unitId) },
                        enabled = enabled,
                        modifier = Modifier.size(28.dp),
                    )
                    Text("【${boss.difficulty.label}】${boss.name}", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
