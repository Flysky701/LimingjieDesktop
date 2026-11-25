package com.landosol.toolbox.desktop.app.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.landosol.toolbox.desktop.app.adapters.storage.DesktopGoal
import com.landosol.toolbox.desktop.app.ui.theme.DesktopCorners
import com.landosol.toolbox.desktop.app.ui.theme.LocalDesktopColors
import com.landosol.toolbox.labyrinth.*
import com.landosol.toolbox.labyrinth.node.LabyrinthNodeTypes

internal enum class DesktopRouteMode(val label: String, val summary: String) {
    VALUE(
        label = "高价值路线",
        summary = "不绑定列号，优先拿满 EX、角色、遗物、商店等高价值格，可设每区允许少拿格数（默认推荐）",
    ),
    OFFICIAL_SCORE(
        label = "官方高分路线",
        summary = "按官方结算分规则规划总分最高的连通路线，仅卡 Boss 名单与 3/5 区第 3 格偏好",
    ),
    TEMPLATE(
        label = "节点模板路线",
        summary = "按标准或自定义列位模板逐列严格匹配；取消「严格匹配列位」时仅筛选 Boss 名单",
    ),
    ;

    companion object {
        fun from(mode: LabyrinthRouteEvaluationMode): DesktopRouteMode = when (mode) {
            LabyrinthRouteEvaluationMode.VALUE_ROUTE -> VALUE
            LabyrinthRouteEvaluationMode.OFFICIAL_SCORE_DP -> OFFICIAL_SCORE
            LabyrinthRouteEvaluationMode.LEGACY_TEMPLATE,
            LabyrinthRouteEvaluationMode.CUSTOM_NODES -> TEMPLATE
        }
    }
}

internal fun LabyrinthCustomNodePlan.hasCustomSlots(): Boolean =
    area1Column3 != LabyrinthNodeTypes.LINK ||
        area1Column5 != LabyrinthNodeTypes.LINK ||
        area2Columns2And6 != LabyrinthNodeTypes.LINK ||
        area3Column4 != LabyrinthNodeTypes.LINK ||
        area4Column2 != LabyrinthNodeTypes.LINK ||
        area4Column7 != LabyrinthNodeTypes.LINK

internal fun LabyrinthRerollSettings.isTemplateStrict(): Boolean = when (routeEvaluationMode) {
    LabyrinthRouteEvaluationMode.CUSTOM_NODES -> true
    LabyrinthRouteEvaluationMode.LEGACY_TEMPLATE -> perfectStart
    else -> true
}

internal fun LabyrinthRerollSettings.selectDesktopRouteMode(mode: DesktopRouteMode): LabyrinthRerollSettings = when (mode) {
    DesktopRouteMode.VALUE -> copy(routeEvaluationMode = LabyrinthRouteEvaluationMode.VALUE_ROUTE)
    DesktopRouteMode.OFFICIAL_SCORE -> copy(routeEvaluationMode = LabyrinthRouteEvaluationMode.OFFICIAL_SCORE_DP)
    DesktopRouteMode.TEMPLATE -> {
        if (routeEvaluationMode == LabyrinthRouteEvaluationMode.LEGACY_TEMPLATE ||
            routeEvaluationMode == LabyrinthRouteEvaluationMode.CUSTOM_NODES
        ) {
            this
        } else {
            copy(routeEvaluationMode = LabyrinthRouteEvaluationMode.CUSTOM_NODES, perfectStart = true)
        }
    }
}

internal fun LabyrinthRerollSettings.withTemplateStrict(
    strict: Boolean,
    preserveLegacyThirdBlock: Boolean = false,
): LabyrinthRerollSettings {
    if (!strict) {
        return copy(routeEvaluationMode = LabyrinthRouteEvaluationMode.LEGACY_TEMPLATE, perfectStart = false)
    }
    val nextPlan = if (preserveLegacyThirdBlock &&
        routeEvaluationMode == LabyrinthRouteEvaluationMode.LEGACY_TEMPLATE &&
        !customNodePlan.hasCustomSlots()
    ) {
        customNodePlan.copy(thirdBlockChoice = thirdBlockChoice)
    } else {
        customNodePlan
    }
    return copy(
        routeEvaluationMode = LabyrinthRouteEvaluationMode.CUSTOM_NODES,
        perfectStart = true,
        customNodePlan = nextPlan,
    )
}

internal data class RunGoalInput(val guildId: Int, val count: String, val clears: Boolean)

/** UI draft only. Business settings are saved through the original account controller. */
internal class RunSetupDraft(state: LabyrinthUiState, goals: List<DesktopGoal>) {
    private var savedSettings = state.rerollSettings()
    private var savedGoals = goalInputs(state, goals)
    var settings by mutableStateOf(savedSettings)
    var goals by mutableStateOf(savedGoals)
    val dirty get() = settings != savedSettings || goals != savedGoals

    fun reset(state: LabyrinthUiState, goals: List<DesktopGoal>) {
        savedSettings = state.rerollSettings()
        savedGoals = goalInputs(state, goals)
        settings = savedSettings
        this.goals = savedGoals
    }

    fun sync(state: LabyrinthUiState, goals: List<DesktopGoal>) {
        // Account settings can finish loading after the user has begun entering batch targets.
        // Refresh each unedited part independently, without discarding those target inputs.
        val merged = settingsToSave(state)
        savedSettings = state.rerollSettings()
        settings = merged
        if (this.goals == savedGoals) {
            savedGoals = goalInputs(state, goals)
            this.goals = savedGoals
        }
    }

    fun savedGoals(): List<DesktopGoal> = goals.mapNotNull { input ->
        input.count.toIntOrNull()?.takeIf { it in 1..99 }?.let { DesktopGoal(input.guildId, it, input.clears) }
    }

    // Only shortcut fields the user changed may replace the current account settings.
    // In particular, a late account load or advanced form save must keep its Boss/custom-node rules.
    fun settingsToSave(state: LabyrinthUiState): LabyrinthRerollSettings {
        val current = state.rerollSettings()
        return current.copy(
            difficulty = if (settings.difficulty != savedSettings.difficulty) settings.difficulty else current.difficulty,
            routeEvaluationMode = if (settings.routeEvaluationMode != savedSettings.routeEvaluationMode) {
                settings.routeEvaluationMode
            } else {
                current.routeEvaluationMode
            },
            valueAllowance = if (settings.valueAllowance != savedSettings.valueAllowance) settings.valueAllowance else current.valueAllowance,
            perfectStart = if (settings.perfectStart != savedSettings.perfectStart) settings.perfectStart else current.perfectStart,
            rerollUntilFound = if (settings.rerollUntilFound != savedSettings.rerollUntilFound) settings.rerollUntilFound else current.rerollUntilFound,
            maxAttempts = if (settings.maxAttempts != savedSettings.maxAttempts) settings.maxAttempts else current.maxAttempts,
            targetScore = if (settings.targetScore != savedSettings.targetScore) settings.targetScore else current.targetScore,
        )
    }

    fun validationError(state: LabyrinthUiState): String? {
        if (goals.any { it.count.isNotEmpty() && it.count.toIntOrNull() !in 0..99 }) return "公会次数请输入 1–99，留空或 0 跳过"
        val candidate = settingsToSave(state)
        if (candidate != state.rerollSettings()) {
            if (state.selectedAccount == null || !state.settingsReady) return "请先选择账号，等待该账号配置加载"
            candidate.validationError()?.let { return it }
            if (candidate.difficulty !in state.availableDifficulties) return "所选难度尚未解锁"
        }
        return null
    }

    companion object {
        private fun goalInputs(state: LabyrinthUiState, goals: List<DesktopGoal>) = state.guildOptions.map { guild ->
            val saved = goals.firstOrNull { it.guildId == guild.guildId }
            RunGoalInput(guild.guildId, saved?.count?.toString().orEmpty(), saved?.clears ?: true)
        }
    }
}

internal fun runSetupBlock(dirty: Boolean, fullConfiguration: Boolean): String? = when {
    fullConfiguration -> "请先返回运行台"
    dirty -> "常用配置尚未保存"
    else -> null
}

@Composable
internal fun RunTaskSetup(
    state: LabyrinthUiState,
    selected: DesktopTask,
    select: (DesktopTask) -> Unit,
    draft: RunSetupDraft,
    standaloneGuild: Int,
    selectGuild: (Int) -> Unit,
    enabled: Boolean,
    configure: () -> Unit,
    strategies: () -> Unit,
    message: String?,
    activeStrategyName: String = "默认策略",
) {
    Surface(color = MaterialTheme.colorScheme.surface, shape = DesktopCorners.lg, border = BorderStroke(1.dp, Line)) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(selected.title, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                DesktopTooltip("管理角色评分、选人、战斗、遗物、商店、事件与模型方案（所有设备共用）") {
                    TextButton(onClick = strategies, enabled = enabled, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
                        Symbol("tune", modifier = Modifier.size(15.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("局内策略：$activeStrategyName", style = MaterialTheme.typography.bodySmall)
                    }
                }
                DesktopTooltip("打开当前账号的开局核验、路线与自定义节点、3/5 区第三格偏好、Boss 名单及撤退规则") {
                    TextButton(onClick = configure, enabled = enabled, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
                        Symbol("settings", modifier = Modifier.size(15.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("开局与路线", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            TaskPicker(selected, select, enabled)
            HorizontalDivider(color = Line)
            if (selected == DesktopTask.BATCH || selected == DesktopTask.REROLL) {
                if (selected == DesktopTask.BATCH) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("公会目标", style = MaterialTheme.typography.titleSmall)
                        Text("留空跳过 · 每项 1–99 次", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = Muted)
                        if (draft.goals.any { it.count.isNotEmpty() }) {
                            TextButton(
                                onClick = { draft.goals = draft.goals.map { it.copy(count = "") } },
                                enabled = enabled,
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                            ) { Text("清空目标", style = MaterialTheme.typography.labelSmall) }
                        }
                    }
                    BatchGoalInputs(state.guildOptions, draft.goals, enabled) { draft.goals = it }
                } else {
                    RunChoice(
                        label = "本次公会",
                        value = state.guildOptions.firstOrNull { it.guildId == standaloneGuild }?.name ?: "请选择",
                        options = state.guildOptions,
                        optionLabel = { it.name },
                        enabled = enabled,
                        modifier = Modifier.fillMaxWidth(),
                    ) { selectGuild(it.guildId) }
                }

                val canEditSettings = enabled && state.settingsReady && state.selectedAccount != null
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
                    RunChoice(
                        label = "难度",
                        value = draft.settings.difficulty.toString(),
                        options = state.availableDifficulties,
                        optionLabel = { it.toString() },
                        enabled = canEditSettings,
                        modifier = Modifier.weight(0.75f),
                    ) { draft.settings = draft.settings.copy(difficulty = it) }

                    val activeRouteMode = DesktopRouteMode.from(draft.settings.routeEvaluationMode)
                    RunChoice(
                        label = "路线判定",
                        value = activeRouteMode.label,
                        options = DesktopRouteMode.entries,
                        optionLabel = { it.label },
                        enabled = canEditSettings,
                        modifier = Modifier.weight(1.25f),
                        tooltip = activeRouteMode.summary,
                        optionTooltip = { it.summary },
                    ) { draft.settings = draft.settings.selectDesktopRouteMode(it) }

                    when (activeRouteMode) {
                        DesktopRouteMode.VALUE -> {
                            RunChoice(
                                label = "每区允许少拿",
                                value = "${draft.settings.valueAllowance} 格",
                                options = (0..LabyrinthRerollOptions.MAX_VALUE_ALLOWANCE).toList(),
                                optionLabel = { "$it 格" },
                                enabled = canEditSettings,
                                modifier = Modifier.weight(1f),
                                tooltip = "0 格表示每区拿满全部高价值格；1–3 格表示允许少拿对应格数。Boss 始终为硬条件",
                            ) { draft.settings = draft.settings.copy(valueAllowance = it) }
                        }
                        DesktopRouteMode.OFFICIAL_SCORE -> {
                            Column(Modifier.weight(1f)) {
                                Text("模式说明", style = MaterialTheme.typography.bodySmall, color = Muted)
                                Text(
                                    "按官方结算分自动选最高分路径",
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        DesktopRouteMode.TEMPLATE -> {
                            Column(Modifier.weight(1f)) {
                                Text("模板规则", style = MaterialTheme.typography.bodySmall, color = Muted)
                                DesktopTooltip("勾选：逐列匹配标准或自定义节点模板（点「开局与路线」调整列位）；取消：不限格子，仅筛选 Boss 名单") {
                                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.heightIn(min = 34.dp)) {
                                        Checkbox(
                                            checked = draft.settings.isTemplateStrict(),
                                            onCheckedChange = { draft.settings = draft.settings.withTemplateStrict(it) },
                                            enabled = canEditSettings,
                                            modifier = Modifier.size(30.dp),
                                        )
                                        Text("严格匹配列位", style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            }
                        }
                    }

                    RunChoice(
                        label = "刷取上限",
                        value = if (draft.settings.rerollUntilFound) "刷到目标为止" else "最多 ${draft.settings.maxAttempts} 次",
                        options = listOf(true, false),
                        optionLabel = { if (it) "刷到目标为止" else "限制尝试次数" },
                        enabled = canEditSettings,
                        modifier = Modifier.weight(1.1f),
                    ) { draft.settings = draft.settings.copy(rerollUntilFound = it) }
                }

                if (!draft.settings.rerollUntilFound) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("最大尝试次数", style = MaterialTheme.typography.bodySmall)
                        RunNumberInput(
                            value = draft.settings.maxAttempts,
                            change = { value ->
                                if (value.length <= 3 && value.all { it in '0'..'9' }) {
                                    draft.settings = draft.settings.copy(maxAttempts = value)
                                }
                            },
                            label = "最大尝试次数",
                            enabled = canEditSettings,
                            modifier = Modifier.width(84.dp),
                        )
                        Text("1–${LabyrinthRerollOptions.MAX_ATTEMPTS}", style = MaterialTheme.typography.bodySmall, color = Muted)
                    }
                }

                var targetScoreInput by remember(state.selectedAccount?.id, draft.settings.targetScore) {
                    mutableStateOf(if (draft.settings.targetScore > 0L) draft.settings.targetScore.toString() else "")
                }
                DesktopTooltip("输入最终结算总分目标（如 50000），开局与局内 DP 判为无法达标时自动撤退并重刷下一局；留空或 0 表示关闭") {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("刷分目标", style = MaterialTheme.typography.bodySmall)
                        RunNumberInput(
                            value = targetScoreInput,
                            change = { value ->
                                val filtered = value.filter { it in '0'..'9' }.take(6)
                                targetScoreInput = filtered
                                draft.settings = draft.settings.copy(targetScore = filtered.toLongOrNull() ?: 0L)
                            },
                            label = "刷分目标",
                            enabled = canEditSettings,
                            modifier = Modifier.width(120.dp),
                            placeholder = "0（关闭）",
                        )
                        Text("最终结算总分 · 0 关闭", style = MaterialTheme.typography.bodySmall, color = Muted)
                    }
                }

                if (state.selectedAccount == null || !state.settingsReady) {
                    Text(
                        if (state.selectedAccount == null) "未选择账号" else "正在加载账号配置…",
                        style = MaterialTheme.typography.bodySmall,
                        color = Muted,
                    )
                } else {
                    val settings = draft.settings
                    val activeMode = DesktopRouteMode.from(settings.routeEvaluationMode)
                    val modeSummary = when (activeMode) {
                        DesktopRouteMode.VALUE -> "高价值路线"
                        DesktopRouteMode.OFFICIAL_SCORE -> "官方高分路线"
                        DesktopRouteMode.TEMPLATE -> when {
                            !settings.isTemplateStrict() -> "节点模板路线（仅限 Boss）"
                            settings.routeEvaluationMode == LabyrinthRouteEvaluationMode.CUSTOM_NODES &&
                                settings.customNodePlan.hasCustomSlots() -> "节点模板路线（已自定义列位）"
                            else -> "节点模板路线（标准列位）"
                        }
                    }
                    val thirdBlockSummary = if (activeMode == DesktopRouteMode.TEMPLATE && !settings.isTemplateStrict()) {
                        null
                    } else {
                        when (settings.routeEvaluationMode) {
                            LabyrinthRouteEvaluationMode.CUSTOM_NODES -> settings.customNodePlan.thirdBlockChoice.label
                            else -> settings.thirdBlockChoice.label
                        }
                    }
                    val bossSummary = buildString {
                        append(if (settings.area3BossIds.isEmpty()) "3区Boss不限" else "3区Boss限${settings.area3BossIds.size}个")
                        append(" · ")
                        append(if (settings.area5BossIds.isEmpty()) "5区Boss不限" else "5区Boss限${settings.area5BossIds.size}个")
                    }
                    val retireSummary = if (settings.retireExisting) "允许彻底撤退" else "不撤退现有开局"
                    val targetScoreSummary = settings.targetScore.takeIf { it > 0L }?.let { "目标分 $it" }
                    Text(
                        listOfNotNull(modeSummary, thirdBlockSummary, targetScoreSummary, bossSummary, retireSummary).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = Muted,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            } else {
                Text(
                    when (selected) {
                        DesktopTask.READ -> "读取服务端开局状态与路线存档"
                        DesktopTask.RECOGNIZE -> "只读识别当前画面与决策规划 · 不执行任何点击"
                        else -> "按当前启用策略执行入口自动化流程"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                TextButton(onClick = strategies, enabled = enabled, contentPadding = PaddingValues(horizontal = 0.dp, vertical = 2.dp)) {
                    Text("编辑当前局内策略（$activeStrategyName）→")
                }
            }

            if (draft.dirty) {
                Text("未保存", color = LocalDesktopColors.current.warn, style = MaterialTheme.typography.bodySmall)
            } else Row(verticalAlignment = Alignment.CenterVertically) {
                Symbol("check_circle", tint = LocalDesktopColors.current.ok, modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    message ?: if (selected == DesktopTask.BATCH && draft.savedGoals().isEmpty()) "未设置公会目标" else "已保存",
                    style = MaterialTheme.typography.bodySmall,
                    color = Muted,
                )
                Spacer(Modifier.weight(1f))
                if (selected == DesktopTask.BATCH) {
                    Text(
                        "${draft.savedGoals().size} 个公会 · 共 ${draft.savedGoals().sumOf { it.count }} 次",
                        style = MaterialTheme.typography.bodySmall,
                        color = Muted,
                    )
                }
            }
            if (draft.dirty) message?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
private fun TaskPicker(selected: DesktopTask, select: (DesktopTask) -> Unit, enabled: Boolean) {
    var tools by remember { mutableStateOf(false) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        listOf(DesktopTask.BATCH, DesktopTask.REROLL).forEach { task ->
            FilterChip(selected == task, { select(task) }, label = { Text(task.title) }, enabled = enabled)
        }
        Box {
            FilterChip(
                selected = selected !in listOf(DesktopTask.BATCH, DesktopTask.REROLL),
                onClick = { tools = true },
                enabled = enabled,
                label = { Text(if (selected in listOf(DesktopTask.BATCH, DesktopTask.REROLL)) "辅助任务" else selected.title) },
                trailingIcon = { Symbol("expand_more", modifier = Modifier.size(16.dp)) },
            )
            DropdownMenu(tools && LocalControlVisible.current, { tools = false }) {
                listOf(DesktopTask.READ, DesktopTask.RECOGNIZE, DesktopTask.ENTRY).forEach { task ->
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(task.title)
                                Text(task.mode, style = MaterialTheme.typography.bodySmall, color = Muted)
                            }
                        },
                        onClick = { tools = false; select(task) },
                    )
                }
            }
        }
    }
}

@Composable
internal fun BatchGoalInputs(
    guilds: List<LabyrinthGuildOption>,
    goals: List<RunGoalInput>,
    enabled: Boolean,
    change: (List<RunGoalInput>) -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val twoColumns = maxWidth >= 540.dp
        val rows = if (twoColumns) goals.chunked(2) else goals.map { listOf(it) }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            rows.forEach { pair ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    pair.forEach { input ->
                        GoalCell(
                            guildName = guilds.firstOrNull { it.guildId == input.guildId }?.name ?: "公会 ${input.guildId}",
                            input = input,
                            enabled = enabled,
                            modifier = Modifier.weight(1f),
                            onChange = { updated -> change(goals.map { if (it.guildId == input.guildId) updated else it }) },
                        )
                    }
                    if (twoColumns && pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun GoalCell(
    guildName: String,
    input: RunGoalInput,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onChange: (RunGoalInput) -> Unit,
) {
    val active = (input.count.toIntOrNull() ?: 0) >= 1
    var modes by remember { mutableStateOf(false) }
    Surface(
        modifier = modifier,
        shape = DesktopCorners.sm,
        color = if (active) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.38f) else MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, if (active) Accent.copy(alpha = 0.45f) else Line),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(guildName, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            RunNumberInput(
                value = input.count,
                change = { value -> if (value.length <= 2 && value.all { it in '0'..'9' }) onChange(input.copy(count = value)) },
                label = "$guildName 次数",
                enabled = enabled,
                modifier = Modifier.width(58.dp),
                placeholder = "跳过",
            )
            Box {
                TextButton(
                    onClick = { modes = true },
                    enabled = enabled,
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp),
                    modifier = Modifier.height(28.dp),
                ) {
                    Text(if (input.clears) "按通关计" else "按开局计", style = MaterialTheme.typography.labelSmall)
                    Symbol("expand_more", modifier = Modifier.size(13.dp))
                }
                DropdownMenu(modes && LocalControlVisible.current, { modes = false }) {
                    listOf(true, false).forEach { clears ->
                        DropdownMenuItem(
                            text = { Text(if (clears) "按通关计" else "按开局计") },
                            onClick = { modes = false; onChange(input.copy(clears = clears)) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun <T> RunChoice(
    label: String,
    value: String,
    options: List<T>,
    optionLabel: (T) -> String,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    tooltip: String? = null,
    optionTooltip: ((T) -> String?)? = null,
    change: (T) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Box {
            DesktopTooltip(tooltip, modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = { expanded = true },
                    enabled = enabled,
                    shape = MaterialTheme.shapes.small,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 7.dp),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 34.dp),
                ) {
                    Text(value, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                    Symbol("expand_more", modifier = Modifier.size(16.dp))
                }
            }
            DropdownMenu(expanded && LocalControlVisible.current, { expanded = false }) {
                options.forEach { option ->
                    DesktopTooltip(
                        text = optionTooltip?.invoke(option),
                        delayMillis = 150,
                        placement = DesktopTooltipPlacement.END,
                    ) {
                        DropdownMenuItem(text = { Text(optionLabel(option)) }, onClick = { expanded = false; change(option) })
                    }
                }
            }
        }
    }
}

@Composable
private fun RunNumberInput(
    value: String,
    change: (String) -> Unit,
    label: String,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    placeholder: String = "",
) {
    BasicTextField(
        value = value,
        onValueChange = change,
        enabled = enabled,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        textStyle = MaterialTheme.typography.bodySmall.copy(color = if (enabled) LocalContentColor.current else Muted),
        cursorBrush = SolidColor(Accent),
        modifier = modifier.semantics { contentDescription = label }
            .background(MaterialTheme.colorScheme.surface, DesktopCorners.sm)
            .border(1.dp, Line, DesktopCorners.sm)
            .padding(horizontal = 8.dp, vertical = 5.dp),
        decorationBox = { inner ->
            Box {
                if (value.isBlank()) Text(placeholder, style = MaterialTheme.typography.labelSmall, color = Muted)
                inner()
            }
        },
    )
}
