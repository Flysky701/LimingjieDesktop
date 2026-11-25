package com.landosol.toolbox.desktop.app.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.rememberDialogState
import com.landosol.toolbox.AppVersion
import com.landosol.toolbox.automation.overlay.AutomationOverlayPresentation
import com.landosol.toolbox.desktop.app.*
import com.landosol.toolbox.desktop.app.control.*
import com.landosol.toolbox.desktop.app.strategy.BattleModelHealth
import com.landosol.toolbox.desktop.app.strategy.BattleModelStatus
import com.landosol.toolbox.desktop.app.strategy.battleDecisionPrefix
import com.landosol.toolbox.desktop.app.ui.theme.*
import com.landosol.toolbox.desktop.device.DeviceConnectionState
import com.landosol.toolbox.desktop.ocr.OcrEngineStatus
import com.landosol.toolbox.desktop.platform.BrowserLauncher
import com.landosol.toolbox.labyrinth.*
import com.landosol.toolbox.labyrinth.batch.*
import com.landosol.toolbox.labyrinth.kernel.strategy.BattleModelDecisionStatus
import kotlinx.coroutines.*
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun RunConsole(
    graph: ControlSessionUi,
    s: RunConsoleState,
    selected: DesktopTask,
    select: (DesktopTask) -> Unit,
    configure: () -> Unit,
    navigate: (Page) -> Unit,
    searchFocus: FocusRequester,
    setup: RunSetupDraft,
    configuration: LabyrinthUiState,
    standaloneGuild: Int,
    selectGuild: (Int) -> Unit,
    setupMessage: String?,
    settingsOwner: ControlSessionUi = graph,
    activeStrategyName: String = "默认策略",
) {
    val preferences by graph.ux.preferences.state.collectAsState()
    val history by graph.ux.history.records.collectAsState()
    val catalog by graph.ux.catalog.collectAsState()
    val incidentCount by graph.incidentCount.collectAsState()
    val actions = rememberActions(graph)
    val hostWindow = LocalDesktopWindow.current
    val ready = (s.device as? DeviceConnectionState.Ready)?.device
    val enabled = !s.busy && !LocalTaskBusy.current
    var rosterExpanded by remember(s.entry.runId, s.entry.sessionId) { mutableStateOf(false) }

    fun exportDiagnostics() {
        actions.run {
            val file = chooseFile(true, "保存诊断包（含事故现场）", "zip", "limingjie-debug.zip", hostWindow)
                ?: return@run
            graph.request(ControlOps.DIAGNOSTICS_EXPORT, buildJsonObject { put("path", file.absolutePath) })
            actions.message = "诊断包已导出：${file.name}"
        }
    }

    var lastBattleRec by remember(s.entry.runId, s.batch?.batchId, s.entry.sessionId) {
        mutableStateOf<Pair<LabyrinthBattleTeamRecommendation, LabyrinthCombatContext?>?>(null)
    }
    SideEffect {
        val currentRec = s.entry.battleTeamRecommendation
        if (currentRec != null) lastBattleRec = currentRec to s.entry.combatContext
        else if (!s.entry.running && s.entry.runId == null) lastBattleRec = null
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val compact = maxWidth < 1120.dp
        Row(Modifier.fillMaxSize().padding(if (compact) 12.dp else 14.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (!s.busy) {
                    RunTaskSetup(
                        configuration, selected, select, setup, standaloneGuild, selectGuild,
                        enabled, configure, { navigate(Page.Strategies) }, setupMessage, activeStrategyName,
                    )
                } else {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(selected.title, style = MaterialTheme.typography.titleLarge)
                            Text("局内策略：$activeStrategyName", style = MaterialTheme.typography.bodySmall, color = Muted)
                        }
                        StatusBadge(
                            if (s.stopping) "停止中" else if (s.overlay?.paused == true) "已暂停" else "运行中",
                            showDot = true,
                        )
                    }
                }

                if (s.busy || s.batch != null || s.entry.status != LabyrinthEntryRecognitionStatus.IDLE ||
                    s.task.startedAtMillis != null || s.task.currentOpeningReadStatus != LabyrinthCurrentOpeningReadStatus.NOT_READ
                ) {
                    ConsoleCard(
                        if (s.batch != null) "批量执行" else if (s.entry.running) if (s.entry.dryRun) "只读识别" else "执行入口流程" else "当前任务",
                    ) {
                        val cp = s.batch
                        if (cp != null) {
                            Text(
                                "目标 ${(cp.activeGoalIndex + 1).coerceAtMost(cp.goals.size)} / ${cp.goals.size} · ${
                                    s.task.guildOptions.firstOrNull { it.guildId == cp.currentGuildId }?.name ?: "目标公会"
                                }",
                                style = MaterialTheme.typography.titleMedium,
                            )
                            BatchStages(cp.stage, s.overlay?.paused == true)
                        } else {
                            Text(s.task.progress ?: s.entry.message ?: "等待任务结果", color = Muted, style = MaterialTheme.typography.bodySmall)
                        }
                        val record = history.lastOrNull { it.endedAt == null && !it.singleRun }
                        if (record != null) RunClock(record.startedAt)
                    }

                    val raw = s.batch?.message ?: s.entry.message ?: s.task.message
                    if (!s.busy && s.batch?.stage == LabyrinthBatchStage.COMPLETED) {
                        ConsoleCard("全部目标已完成") {
                            TextButton(onClick = { navigate(Page.Records) }) { Text("查看运行记录") }
                        }
                    } else if (!s.busy && raw != null && (s.halt != null || s.entry.status != LabyrinthEntryRecognitionStatus.IDLE || s.task.message != null)) {
                        StopReasonCard(
                            info = explainStop(s.halt, raw),
                            onNavigate = { target ->
                                if (target == "Run") select(DesktopTask.RECOGNIZE) else navigate(Page.valueOf(target))
                            },
                            onExportDiagnostics = if (graph.debugUrl != null) ::exportDiagnostics else null,
                        )
                    }

                    if (s.entry.routeProgress != null || s.entry.alphaRupeeBalance != null || s.entry.combatContext != null || s.entry.scoreTargetStatus != null || incidentCount > 0) {
                        ConsoleCard("本区域进度与局内状态") {
                            s.entry.routeProgress?.let { route ->
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        "区域 ${route.currentArea} · 节点 ${route.visitedCount}/${route.routeNodeCount}",
                                        Modifier.weight(1f),
                                        fontFamily = FontFamily.Monospace,
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                    route.nextNodeLabel?.let {
                                        Text("下一节点：$it", color = Muted, style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                                val accent = Accent
                                val line = Line
                                Canvas(Modifier.fillMaxWidth().height(22.dp)) {
                                    val count = route.routeNodeCount.coerceIn(1, 80)
                                    val step = if (count > 1) (size.width - 12) / (count - 1) else 0f
                                    repeat(count) { i ->
                                        val x = 6 + i * step
                                        if (i > 0) drawLine(if (i < route.visitedCount) accent else line, Offset(x - step, 11f), Offset(x, 11f), 2f)
                                        drawCircle(if (i < route.visitedCount) accent else line, 4f, Offset(x, 11f))
                                    }
                                }
                            }
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                val alphaText = s.entry.alphaRupeeBalance?.let { "$it α币" } ?: "α币 待观测"
                                StatusBadge(
                                    alphaText,
                                    tone = if (s.entry.alphaRupeeBalance != null) StatusTone.INFO else StatusTone.NEUTRAL,
                                )
                                s.entry.combatContext?.let { ctx ->
                                    StatusBadge(
                                        "${when (ctx.kind) {
                                            LabyrinthCombatKind.NORMAL -> "普通战"
                                            LabyrinthCombatKind.EX -> "EX 战"
                                            LabyrinthCombatKind.BOSS -> "Boss"
                                        }} · 第 ${ctx.teamIndex} 队",
                                        tone = StatusTone.INFO,
                                    )
                                }
                                if (incidentCount > 0) {
                                    Surface(
                                        onClick = ::exportDiagnostics,
                                        shape = MaterialTheme.shapes.small,
                                        color = toneContainerColor(StatusTone.WARN),
                                        border = BorderStroke(1.dp, LocalDesktopColors.current.warn.copy(alpha = 0.35f)),
                                    ) {
                                        Text(
                                            "事故快照 $incidentCount 条 · 点击导出",
                                            Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                            color = LocalDesktopColors.current.warn,
                                            style = MaterialTheme.typography.labelSmall,
                                        )
                                    }
                                }
                            }
                            s.entry.scoreTargetStatus?.let { score ->
                                val pct = kotlin.math.round(score.probReachTarget * 100.0).toInt()
                                val badgeTone = if (score.willRetire) StatusTone.WARN else StatusTone.OK
                                val suffix = if (score.willRetire) " · 准备撤退" else ""
                                DesktopTooltip(score.reason) {
                                    StatusBadge(
                                        "目标 ${score.targetScore} · 达标率 $pct% · 期望 ${score.expectedScore} · 上界 ${score.hardUpperBound}$suffix",
                                        tone = badgeTone,
                                    )
                                }
                            }
                        }
                    }

                    if (s.entry.joinedCharacters.isNotEmpty() || s.entry.observedRelics.isNotEmpty()) {
                        ConsoleCard(
                            title = "已加入角色（${s.entry.joinedCharacters.size}）与遗物（${s.entry.observedRelics.size}）",
                            action = {
                                if (s.entry.joinedCharacters.size > 5 || s.entry.observedRelics.size > 4) {
                                    TextButton(
                                        onClick = { rosterExpanded = !rosterExpanded },
                                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                                    ) { Text(if (rosterExpanded) "收起" else "展开全部", style = MaterialTheme.typography.labelSmall) }
                                }
                            },
                        ) {
                            if (s.entry.joinedCharacters.isNotEmpty()) {
                                if (!rosterExpanded) {
                                    Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
                                        s.entry.joinedCharacters.take(5).forEach { character ->
                                            Portrait(graph, catalog.firstOrNull { it.id == character.characterId }, character.displayName)
                                        }
                                        if (s.entry.joinedCharacters.size > 5) {
                                            Text("+${s.entry.joinedCharacters.size - 5}", style = MaterialTheme.typography.bodySmall, color = Muted)
                                        }
                                    }
                                } else {
                                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        s.entry.joinedCharacters.forEach { character ->
                                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                                                Portrait(graph, catalog.firstOrNull { it.id == character.characterId }, character.displayName, Modifier.size(28.dp))
                                                Text(character.displayName, style = MaterialTheme.typography.bodySmall)
                                            }
                                        }
                                    }
                                }
                            }
                            if (s.entry.observedRelics.isNotEmpty()) {
                                if (!rosterExpanded) {
                                    Text(
                                        s.entry.observedRelics.take(4).joinToString(" · ") { it.displayName } +
                                            if (s.entry.observedRelics.size > 4) " +${s.entry.observedRelics.size - 4}" else "",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Muted,
                                    )
                                } else {
                                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        s.entry.observedRelics.forEach { relic ->
                                            Surface(shape = DesktopCorners.sm, color = MaterialTheme.colorScheme.surfaceContainerLow, border = BorderStroke(1.dp, Line)) {
                                                Text(relic.displayName, Modifier.padding(horizontal = 7.dp, vertical = 3.dp), style = MaterialTheme.typography.labelSmall)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    s.batch?.let { cp ->
                        ConsoleCard("批量目标") {
                            cp.goals.forEach { goal ->
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Text(s.task.guildOptions.firstOrNull { it.guildId == goal.guildId }?.name ?: "公会 ${goal.guildId}", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                                    Text("${goal.completedCount}/${goal.targetCount} ${if (goal.mode == LabyrinthBatchGoalMode.CLEARS) "通关" else "开局"}", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                                }
                                LinearProgressIndicator(progress = { (goal.completedCount.toFloat() / goal.targetCount).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().height(4.dp))
                            }
                            Text(
                                buildString {
                                    append("已开始 ${cp.runsStarted}")
                                    if (cp.scoreRetiredRuns > 0) append(" · 未达标撤退 ${cp.scoreRetiredRuns}")
                                    append(" · 异常 ${cp.abnormalRuns}")
                                },
                                fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.bodySmall,
                            )
                            val samples = history.filter { it.singleRun && it.durationMillis != null && it.result == "通关" }
                            if (samples.size >= 2) {
                                Text("平均每次 ${formatDuration(samples.mapNotNull { it.durationMillis }.average().toLong())}", style = MaterialTheme.typography.bodySmall, color = Muted)
                            }
                        }
                    }

                    RecentDecisionCard(
                        graph = graph,
                        catalog = catalog,
                        state = s.entry,
                        battlePair = s.entry.battleTeamRecommendation?.let { it to s.entry.combatContext } ?: lastBattleRec,
                    )
                }

                if (!s.busy) history.lastOrNull { !it.singleRun && it.endedAt != null }?.let { last ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            "上次 ${last.type} · ${last.result} · ${formatDuration(last.durationMillis ?: 0)}",
                            Modifier.weight(1f),
                            color = Muted,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        TextButton(onClick = { navigate(Page.Records) }) { Text("查看记录 →", style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }

            Column(Modifier.width(if (compact) 288.dp else 320.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                RunReadiness(graph, s, selected, preferences.goals.isNotEmpty(), navigate, settingsOwner)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("logs" to "日志", "preview" to "画面").forEach { (key, label) ->
                        FilterChip(
                            selected = preferences.rightTab == key,
                            onClick = { actions.run { withContext(Dispatchers.IO) { graph.ux.preferences.update { it.copy(rightTab = key) } } } },
                            label = { Text(label) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                if (preferences.rightTab == "preview") {
                    ConsoleCard("实时画面") {
                        if (ready == null && !s.busy) {
                            Text("连接模拟器后可查看画面", style = MaterialTheme.typography.bodySmall, color = Muted)
                            TextButton(onClick = { navigate(Page.Devices) }) { Text("连接设备 →") }
                        } else {
                            Preview(graph, s.busy, s.entry)
                            Text("帧 ${s.entry.frameCount} · 动作 ${s.entry.actionCount}", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                            s.entry.lastActionLabel?.let { Text("上一步：$it", style = MaterialTheme.typography.bodySmall, color = Muted) }
                        }
                    }
                } else {
                    LogPanel(
                        graph = graph,
                        modifier = Modifier.weight(1f),
                        searchFocus = searchFocus,
                        onExportDiagnostics = if (graph.debugUrl != null) ::exportDiagnostics else null,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RecentDecisionCard(
    graph: ControlSessionUi,
    catalog: List<LabyrinthRoleRatingItem>,
    state: LabyrinthEntryRecognitionSessionState,
    battlePair: Pair<LabyrinthBattleTeamRecommendation, LabyrinthCombatContext?>?,
) {
    val pathBSummary = state.pathBDecisionSummary
    val roleChoice = state.roleRewardChoiceDecision
    val eventChoice = state.eventChoiceDecision
    val unavailable = state.battleTeamRecommendationUnavailableReason
    if (battlePair == null && pathBSummary == null && roleChoice == null && eventChoice == null && unavailable == null) return

    ConsoleCard("最近决策") {
        SelectionContainer {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                battlePair?.let { (rec, combat) ->
                    val trace = rec.modelTrace
                    val (sourceLabel, sourceTone) = when (trace?.status) {
                        BattleModelDecisionStatus.ADOPTED -> "NN 采纳" to StatusTone.OK
                        BattleModelDecisionStatus.LOW_CONFIDENCE_FALLBACK -> "NN 低胜率回退" to StatusTone.WARN
                        BattleModelDecisionStatus.LOAD_OR_INFERENCE_FAILED -> "NN 异常回退" to StatusTone.ERROR
                        else -> "规则编组" to StatusTone.NEUTRAL
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(battleDecisionPrefix(combat, trace?.questId), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                        StatusBadge(sourceLabel, tone = sourceTone)
                        rec.modelAssessment?.summary(rec.plannedBossTeamCount)?.let {
                            Text(it, style = MaterialTheme.typography.labelSmall, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        rec.members.forEach { member ->
                            val item = catalog.firstOrNull { it.id == member.characterId }
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                Portrait(graph, item, member.displayName, Modifier.size(26.dp))
                                Text(member.displayName, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                    if (rec.reasons.isNotEmpty()) {
                        Text(rec.reasons.take(3).joinToString("；"), style = MaterialTheme.typography.bodySmall, color = Muted)
                    }
                }

                if (battlePair == null && unavailable != null) {
                    Text(unavailable, style = MaterialTheme.typography.bodySmall, color = LocalDesktopColors.current.warn)
                }

                if (pathBSummary != null) {
                    if (battlePair != null) HorizontalDivider(color = Line)
                    val isNn = pathBSummary.source == "PathB-NN"
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        StatusBadge(if (isNn) "PathB(NN)" else "PathB(官方分)", tone = if (isNn) StatusTone.OK else StatusTone.INFO)
                        Text("${pathBSummary.title}：${pathBSummary.target}", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
                    }
                    if (pathBSummary.detail.isNotBlank()) {
                        Text(pathBSummary.detail, style = MaterialTheme.typography.bodySmall, color = Muted)
                    }
                } else {
                    val fallbackText = when (roleChoice) {
                        is LabyrinthRoleRewardChoiceDecision.Select -> "角色三选一：选择 ${roleChoice.displayName}（${roleChoice.explanation.joinToString("；")}）"
                        is LabyrinthRoleRewardChoiceDecision.Wait -> roleChoice.reason
                        null -> eventDecisionText(eventChoice)
                    }
                    if (fallbackText != null) {
                        if (battlePair != null) HorizontalDivider(color = Line)
                        Text(fallbackText, style = MaterialTheme.typography.bodySmall, color = Muted)
                    }
                }
            }
        }
    }
}

@Composable
internal fun RunReadiness(
    graph: ControlSessionUi,
    s: RunConsoleState,
    selected: DesktopTask,
    hasGoals: Boolean,
    navigate: (Page) -> Unit,
    settingsOwner: ControlSessionUi = graph,
) {
    val resolution by graph.resolutionGuard.status.collectAsState()
    val database by settingsOwner.ux.database.collectAsState()
    val foreground by graph.ux.foreground.collectAsState()
    val ocr by graph.ocr.status.collectAsState()
    val ocrTelemetry by graph.ocr.telemetry.collectAsState()
    val incidentCount by graph.incidentCount.collectAsState()
    val preferences by graph.ux.preferences.state.collectAsState()
    val settings by settingsOwner.settings.state.collectAsState()
    val globalBattleModel by settingsOwner.battleModel.collectAsState()
    val workerBattleModel by graph.battleModel.collectAsState()
    val credentials by graph.ux.credentials.collectAsState()
    val ready = (s.device as? DeviceConnectionState.Ready)?.device
    val actions = rememberActions(graph)
    var expanded by remember { mutableStateOf(false) }
    val missing = startMissing(selected, s.copy(busy = false, stopping = false), ocr is OcrEngineStatus.Ready, hasGoals, credentials[s.task.selectedAccount?.id], resolution)
    val needsConfirmation = selected in listOf(DesktopTask.BATCH, DesktopTask.ENTRY)
    val confirmed = settings.gameSettingsConfirmed && preferences.animationConfirmedVersion == AppVersion.name
    val pendingConfirmation = needsConfirmation && !confirmed
    // Keep confirmation row visible after checking when it started unconfirmed in this session;
    // fold into expanded list when already confirmed beforehand.
    var showConfirmationInMainCard by remember(s.task.selectedAccount?.id, settingsOwner) { mutableStateOf(!confirmed) }
    LaunchedEffect(confirmed) { if (!confirmed) showConfirmationInMainCard = true }

    val tone = if (s.busy) Accent else if (missing != null || pendingConfirmation) LocalDesktopColors.current.warn else LocalDesktopColors.current.ok
    ConsoleCard(
        title = "运行检查",
        action = {
            TextButton(onClick = { expanded = !expanded }, contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)) {
                Text(if (expanded) "收起" else "展开检查项", style = MaterialTheme.typography.bodySmall)
            }
        },
    ) {
        val reason = missing
        Surface(Modifier.fillMaxWidth(), shape = DesktopCorners.sm, color = tone.copy(alpha = .09f)) {
            Row(Modifier.padding(9.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Symbol(if (!s.busy && (reason != null || pendingConfirmation)) "warning" else "check_circle", tint = tone, modifier = Modifier.size(18.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        when {
                            s.busy -> "任务进行中"
                            reason != null -> "尚有检查项未通过"
                            pendingConfirmation -> "待确认游戏设置"
                            else -> "准备完成"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = tone,
                    )
                    Text(
                        when {
                            s.busy -> "运行中使用开始时的配置"
                            reason != null -> reason
                            pendingConfirmation -> "基础检查通过，请完成下方确认"
                            else -> "当前任务的基础检查已通过"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = Muted,
                    )
                }
            }
        }
        if (reason != null && !s.busy) {
            TextButton(
                onClick = {
                    navigate(
                        when {
                            reason.contains("账号") -> Page.Accounts
                            reason.contains("目标") || reason.contains("配置") -> Page.Run
                            reason.contains("模型") -> Page.Settings
                            else -> Page.Devices
                        },
                    )
                },
                contentPadding = PaddingValues(horizontal = 0.dp, vertical = 2.dp),
            ) {
                Text(
                    when {
                        reason.contains("账号") -> "选择或编辑账号 →"
                        reason.contains("目标") || reason.contains("配置") -> "填写任务配置"
                        reason.contains("模型") -> "查看识别模型 →"
                        else -> "检查设备 →"
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        val showConfirmationRow = needsConfirmation && (showConfirmationInMainCard || expanded)
        if (expanded) {
            val inForeground = com.landosol.toolbox.protocol.bilibili.GameServer.entries.any { it.packageName == foreground }
            val ocrQueueDetail = buildString {
                append(
                    when (ocr) {
                        OcrEngineStatus.Ready -> "就绪"
                        OcrEngineStatus.Loading -> "加载中"
                        OcrEngineStatus.NotLoaded -> "尚未加载"
                        is OcrEngineStatus.Failed -> "加载失败"
                    },
                )
                if (ocrTelemetry.submitted > 0 || ocrTelemetry.queueDrops > 0 || ocrTelemetry.consecutiveFailures > 0) {
                    append(" · ${ocrTelemetry.submitted}次")
                    if (ocrTelemetry.queueDrops > 0) append(" · 丢${ocrTelemetry.queueDrops}")
                    if (ocrTelemetry.consecutiveFailures > 0) append(" · 异常${ocrTelemetry.consecutiveFailures}")
                }
            }
            val items = listOf(
                ReadinessItem("模拟器", ready?.label ?: "未连接", ready != null, "Devices"),
                ReadinessItem("画面与触控", ready?.let { "${resolution.dimensionsLabel} · ${if (it.touchReady) "就绪" else "只读"}" } ?: "等待连接", ready != null && resolution.supported, "Devices", ready != null && !resolution.supported),
                ReadinessItem("游戏前台", if (foreground == null) "开始前检查" else if (inForeground) "上次检查通过" else "请打开游戏", inForeground, "Devices", true),
                ReadinessItem("账号", if (credentials[s.task.selectedAccount?.id] == false) "缺凭据" else s.task.selectedAccount?.alias ?: "未选择", s.task.selectedAccount != null && credentials[s.task.selectedAccount?.id] != false, "Accounts"),
                ReadinessItem("国服数据库", database, database.startsWith("已就绪") || database.startsWith("正在"), "Settings"),
                ReadinessItem("OCR 识别模型", ocrQueueDetail, ocr is OcrEngineStatus.Ready && ocrTelemetry.consecutiveFailures == 0, "Settings", ocrTelemetry.queueDrops > 0 || ocrTelemetry.consecutiveFailures > 0),
                battleModelReadiness(settings.battleOnnxEnabled, s.entry.running, globalBattleModel, workerBattleModel),
                ReadinessItem("Path B 全局规划", if (settings.pathBPlanningEnabled) "已开启" else "已关闭", true, "Strategies"),
                ReadinessItem("事故现场快照", if (incidentCount > 0) "已留存 $incidentCount 条" else "无异常快照", incidentCount == 0, "Records", incidentCount > 0),
            )
            items.forEach { item ->
                Row(Modifier.fillMaxWidth().heightIn(min = 24.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(item.title, style = MaterialTheme.typography.bodySmall, color = Muted)
                    Spacer(Modifier.weight(1f))
                    Text(
                        item.detail,
                        Modifier.widthIn(max = 155.dp).clickable { navigate(Page.valueOf(item.page)) },
                        color = if (item.passed && !item.warning) Muted else Accent,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { actions.run { graph.recheckResolution(); graph.ux.refreshForeground(); settingsOwner.ux.refreshDatabase() } }, enabled = ready != null && !s.busy && !actions.working) {
                    Text("重新检查", style = MaterialTheme.typography.bodySmall)
                }
                if (settingsOwner !== graph) TextButton(onClick = { navigate(Page.Settings) }) {
                    Text("打开设置", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        if (showConfirmationRow) {
            if (expanded) HorizontalDivider(color = Line, modifier = Modifier.padding(vertical = 2.dp))
            Row(
                Modifier.fillMaxWidth().heightIn(min = 44.dp).semantics { contentDescription = "确认已关闭角色加入动画" }.toggleable(
                    value = confirmed,
                    enabled = !s.busy && !actions.working && !LocalTaskBusy.current,
                    role = Role.Checkbox,
                    onValueChange = { checked ->
                        actions.run {
                            settingsOwner.settings.update { it.copy(gameSettingsConfirmed = checked) }
                            graph.ux.preferences.update { it.copy(animationConfirmedVersion = if (checked) AppVersion.name else "") }
                        }
                    },
                ),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                Checkbox(confirmed, null, enabled = !s.busy && !actions.working, modifier = Modifier.size(28.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("已关闭角色加入动画", style = MaterialTheme.typography.bodySmall)
                    Text("游戏内「黎明界设置」", style = MaterialTheme.typography.labelSmall, color = Muted)
                }
            }
        }
        actions.message?.let { message ->
            Row(Modifier.fillMaxWidth().padding(top = 3.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Symbol("error", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
                Text("操作未完成：$message", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

private fun battleModelReadiness(
    enabled: Boolean,
    running: Boolean,
    global: BattleModelStatus,
    worker: BattleModelStatus,
): ReadinessItem {
    val applied = if (running) worker else global
    val detail = when {
        !enabled -> if (running && worker.health in setOf(BattleModelHealth.LOADED, BattleModelHealth.FALLBACK)) {
            "下局关闭 · 当前用模型"
        } else "已关闭（规则）"
        running -> when (worker.health) {
            BattleModelHealth.LOADED -> "已加载 · NN"
            BattleModelHealth.FAILED, BattleModelHealth.FALLBACK -> worker.health.label
            BattleModelHealth.READY, BattleModelHealth.DISABLED -> "下局生效 · 规则中"
        }
        else -> when (global.health) {
            BattleModelHealth.READY, BattleModelHealth.LOADED -> listOfNotNull("已就绪", global.bundleId).joinToString(" · ")
            BattleModelHealth.FAILED ->
                if (global.detail.orEmpty().contains("未找到模型包目录") || global.detail.orEmpty().contains("缺少 manifest.json")) {
                    "未找到模型包"
                } else global.health.label
            BattleModelHealth.FALLBACK -> global.health.label
            BattleModelHealth.DISABLED -> "正在检查模型包"
        }
    }
    val warning = enabled && applied.health in setOf(BattleModelHealth.FAILED, BattleModelHealth.FALLBACK)
    return ReadinessItem("战斗选队模型", detail, !warning, "Strategies", warning)
}

@Composable
internal fun ConsoleCard(title: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surface, shape = DesktopCorners.lg, border = BorderStroke(1.dp, Line)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                action?.invoke()
            }
            content()
        }
    }
}

@Composable
internal fun BatchStages(stage: LabyrinthBatchStage, paused: Boolean) {
    val names = listOf("刷开局", "重置会话", "执行中", "记录结果")
    val current = when (stage) {
        LabyrinthBatchStage.REROLLING -> 0
        LabyrinthBatchStage.INVALIDATING_OLD_CLIENT_SESSION -> 1
        LabyrinthBatchStage.RUNNING_LABYRINTH -> 2
        LabyrinthBatchStage.RECORDING_RESULT -> 3
        else -> -1
    }
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        names.forEachIndexed { i, name ->
            Surface(
                Modifier.weight(1f),
                shape = DesktopCorners.sm,
                color = if (stage == LabyrinthBatchStage.FAILED) MaterialTheme.colorScheme.errorContainer
                else if (i == current) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceContainerLow,
            ) {
                Text(
                    if (paused && i == current) "已暂停" else name,
                    Modifier.padding(vertical = 6.dp, horizontal = 4.dp),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                )
            }
        }
    }
    if (current < 0) {
        Text(
            when (stage) {
                LabyrinthBatchStage.COMPLETED -> "全部目标已完成"
                LabyrinthBatchStage.PAUSED -> "批量任务已停止 / 暂停"
                LabyrinthBatchStage.FAILED -> "批量任务已停止"
                else -> "等待开始"
            },
            color = Muted,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
internal fun StopReasonCard(
    info: StopExplanation,
    onNavigate: (String) -> Unit,
    onExportDiagnostics: (() -> Unit)? = null,
) {
    var details by remember(info) { mutableStateOf(false) }
    val tone = if (info.title.contains("手动停止")) StatusTone.NEUTRAL else StatusTone.ERROR
    val fg = toneForegroundColor(tone)
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        shape = DesktopCorners.lg,
        border = BorderStroke(1.dp, fg.copy(alpha = 0.35f)),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(info.title, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, color = if (tone == StatusTone.ERROR) fg else LocalContentColor.current)
                StatusBadge(if (tone == StatusTone.ERROR) "异常停止" else "已停止", tone = tone)
            }
            Text(info.reason, style = MaterialTheme.typography.bodyMedium)
            Text(info.advice, color = Muted, style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TextButton(onClick = { onNavigate(info.destination) }) { Text(info.action) }
                TextButton(onClick = { details = !details }) { Text(if (details) "收起详情" else "详情") }
                if (onExportDiagnostics != null) {
                    TextButton(onClick = onExportDiagnostics) { Text("导出诊断包") }
                }
            }
            if (details) {
                SelectionContainer { Text(info.original, style = MaterialTheme.typography.bodySmall) }
                TextButton(onClick = { copyText(info.original) }) { Text("复制原文") }
            }
        }
    }
}

@Composable
internal fun RunClock(start: Long) {
    var now by remember(start) { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(start) { while (isActive) { now = System.currentTimeMillis(); delay(1000) } }
    Text(formatDuration(now - start), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.titleMedium)
}

internal fun formatDuration(ms: Long): String {
    val s = ms.coerceAtLeast(0) / 1000
    return "%02d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60)
}

private fun eventDecisionText(event: LabyrinthEventChoiceDecision?): String? = when (event) {
    is LabyrinthEventChoiceDecision.Select -> "${event.label}：${event.explanation}"
    is LabyrinthEventChoiceDecision.Wait -> event.reason
    null -> null
}

internal fun formatTime(ms: Long): String =
    DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(ms))

@Composable
internal fun Preview(
    graph: ControlSessionUi,
    running: Boolean = false,
    entryState: LabyrinthEntryRecognitionSessionState = LabyrinthEntryRecognitionSessionState(),
) {
    val pref by graph.ux.preferences.state.collectAsState()
    var expanded by remember { mutableStateOf(running || pref.previewExpanded) }
    var zoomed by remember { mutableStateOf(false) }
    LaunchedEffect(running) { if (running) expanded = true }
    val frame by graph.preview.collectAsState()
    val overlay by graph.automationOverlayCoordinator.state.collectAsState()
    val actions = rememberActions(graph)
    val visible = LocalControlVisible.current && (expanded || zoomed)
    DisposableEffect(graph.port, visible) {
        graph.port.setPreviewVisible(visible)
        onDispose { graph.port.setPreviewVisible(false) }
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        TextButton(
            onClick = {
                expanded = !expanded
                actions.run {
                    withContext(Dispatchers.IO) { graph.ux.preferences.update { it.copy(previewExpanded = expanded) } }
                    if (expanded && !running) graph.startPreview()
                }
            },
            enabled = !actions.working,
            modifier = Modifier.weight(1f),
        ) { Text(if (expanded) "收起画面" else "展开画面") }
        if (expanded && frame != null) {
            TextButton(onClick = { zoomed = true }) { Text("放大查看") }
        }
    }
    if (expanded) {
        val bitmap = frame
        if (bitmap == null) {
            Text(actions.message ?: "尚无画面。连接模拟器后，点击展开预览。", color = Muted, style = MaterialTheme.typography.bodySmall)
        } else {
            PreviewCanvas(bitmap, overlay)
        }
    } else {
        Text("空闲时折叠 · 预览上限 2 fps", style = MaterialTheme.typography.bodySmall, color = Muted)
    }

    if (zoomed) {
        val closeZoom = { zoomed = false }
        val zoomContent: @Composable () -> Unit = {
            Surface(Modifier.fillMaxSize(), color = Background) {
                Column(Modifier.fillMaxSize().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val result = entryState.lastResult
                        Text("实时画面 · ${LocalControlName.current}", style = MaterialTheme.typography.titleMedium)
                        result?.let {
                            StatusBadge(it.observation.state.name, tone = StatusTone.INFO)
                            Text(
                                "置信度 ${"%.3f".format(it.observation.confidence)} · ${it.elapsedMillis} ms · 帧 ${entryState.frameCount} · 动作 ${entryState.actionCount}",
                                style = MaterialTheme.typography.bodySmall,
                                color = Muted,
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = closeZoom) { Text("关闭") }
                    }
                    HorizontalDivider(color = Line)
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        val bitmap = frame
                        if (bitmap == null) Text("等待实时画面…", color = Muted)
                        else PreviewCanvas(bitmap, overlay, Modifier.fillMaxSize())
                    }
                    entryState.lastActionLabel?.let {
                        Text("上一步动作：$it", style = MaterialTheme.typography.bodySmall, color = Muted)
                    }
                }
            }
        }
        val parent = LocalDesktopWindow.current
        if (parent == null) {
            Dialog(onDismissRequest = closeZoom, properties = DialogProperties(usePlatformDefaultWidth = false)) {
                Box(Modifier.size(900.dp, 580.dp)) { zoomContent() }
            }
        } else {
            val state = rememberDialogState(width = 960.dp, height = 620.dp)
            DialogWindow(onCloseRequest = closeZoom, state = state, title = "实时画面 · ${LocalControlName.current}", resizable = true) {
                NativeWindowTheme(window)
                zoomContent()
            }
        }
    }
}

@Composable
private fun PreviewCanvas(
    bitmap: ImageBitmap,
    overlay: AutomationOverlayPresentation?,
    modifier: Modifier = Modifier.fillMaxWidth(),
) {
    Box(modifier.aspectRatio(bitmap.width.toFloat() / bitmap.height)) {
        Image(bitmap, "模拟器最新画面", Modifier.fillMaxSize())
        val accent = Accent
        val ok = LocalDesktopColors.current.ok
        Canvas(Modifier.fillMaxSize()) {
            overlay?.boxes?.forEach { box ->
                drawRect(
                    color = if (box.selected) accent else ok,
                    topLeft = Offset(box.left * size.width, box.top * size.height),
                    size = Size(box.width * size.width, box.height * size.height),
                    style = Stroke(1.5.dp.toPx()),
                )
            }
        }
    }
}

@OptIn(FlowPreview::class)
@Composable
internal fun LogPanel(
    graph: ControlSessionUi,
    modifier: Modifier = Modifier,
    searchFocus: FocusRequester = remember { FocusRequester() },
    onExportDiagnostics: (() -> Unit)? = null,
) {
    val actions = rememberActions(graph)
    val lines by graph.log.lines.collectAsState()
    val keyLines by graph.log.keyLines.collectAsState()
    val revision by graph.log.revision.collectAsState()
    val connection by graph.port.connection.collectAsState()
    var filter by remember { mutableStateOf("关键") }
    var query by remember { mutableStateOf("") }
    var fullLines by remember(filter, graph, connection) { mutableStateOf<List<UserLogLine>?>(null) }
    LaunchedEffect(filter, graph, revision, connection) {
        if (filter != "全部" || connection != WorkerConnectionStatus.CONNECTED) return@LaunchedEffect
        delay(150)
        try {
            fullLines = graph.log.load()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            fullLines = null
        }
    }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val filtered = when (filter) {
        "全部" -> fullLines ?: lines
        "警告与错误" -> remember(keyLines) { keyLines.filter { it.kind == UserLogKind.WARNING || it.kind == UserLogKind.ERROR } }
        else -> keyLines
    }
    val shown = remember(filtered, query) {
        if (query.isBlank()) filtered else filtered.filter { it.text.contains(query, ignoreCase = true) }
    }
    var follow by remember { mutableStateOf(true) }
    LaunchedEffect(listState.isScrollInProgress) { if (listState.isScrollInProgress) follow = !listState.canScrollForward }
    LaunchedEffect(shown.size, shown.lastOrNull()?.repeats) { if (follow && shown.isNotEmpty()) listState.scrollToItem(shown.lastIndex) }

    Surface(modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surface, shape = DesktopCorners.lg, border = BorderStroke(1.dp, Line)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("日志", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                if (onExportDiagnostics != null) {
                    TextButton(onClick = onExportDiagnostics, contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)) {
                        Text("导出ZIP", style = MaterialTheme.typography.labelSmall)
                    }
                }
                if (graph.debugUrl != null) {
                    TextButton(onClick = { graph.debugUrl?.let(BrowserLauncher::open) }, contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)) {
                        Text("诊断面板", style = MaterialTheme.typography.labelSmall)
                    }
                }
                TextButton(
                    onClick = { actions.run { copyText(graph.log.text(graph.log.load())) } },
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                ) { Text("复制全部", style = MaterialTheme.typography.labelSmall) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf("关键", "全部", "警告与错误").forEach { label ->
                    FilterChip(selected = filter == label, onClick = { filter = label }, label = { Text(label, style = MaterialTheme.typography.bodySmall) })
                }
            }
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("搜索日志 · Ctrl+F", style = MaterialTheme.typography.bodySmall) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().focusRequester(searchFocus),
            )
            if (shown.isEmpty()) {
                Column(Modifier.weight(1f).fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    Symbol("bar_chart", tint = Muted)
                    Text(if (lines.isEmpty() && keyLines.isEmpty()) "等待第一条任务日志" else "没有匹配的日志", style = MaterialTheme.typography.bodySmall, color = Muted)
                    TextButton(onClick = { filter = "全部"; query = "" }) { Text("显示全部") }
                }
            } else {
                SelectionContainer(Modifier.weight(1f)) {
                    LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        itemsIndexed(shown) { _, line ->
                            Column {
                                Text("${line.time} · ${line.kind.label}", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelSmall, color = Muted)
                                Text(
                                    line.text + if (line.repeats > 1) " ×${line.repeats}" else "",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = when (line.kind) {
                                        UserLogKind.ERROR -> LocalDesktopColors.current.error
                                        UserLogKind.WARNING -> LocalDesktopColors.current.warn
                                        UserLogKind.KEY -> Accent
                                        else -> LocalContentColor.current
                                    },
                                )
                                HorizontalDivider(color = Line, modifier = Modifier.padding(top = 4.dp))
                            }
                        }
                    }
                }
            }
            if (!follow) {
                TextButton(onClick = { follow = true; scope.launch { if (shown.isNotEmpty()) listState.scrollToItem(shown.lastIndex) } }) {
                    Text("回到最新")
                }
            }
        }
    }
}

