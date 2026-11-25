package com.landosol.toolbox.desktop.app.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.landosol.toolbox.desktop.app.adapters.storage.DesktopRunRecord
import com.landosol.toolbox.desktop.app.control.*
import com.landosol.toolbox.desktop.app.ui.theme.DesktopCorners

@Composable
internal fun RecordsPage(
    graph: ControlSessionUi,
    sessions: List<DeviceRunTab> = emptyList(),
    runtimes: Map<String, ControlSessionUi> = emptyMap(),
    selectedSessionId: String? = null,
) {
    var records by remember(graph) { mutableStateOf(graph.ux.history.records.value) }
    val revision by graph.ux.history.revision.collectAsState()
    val connection by graph.port.connection.collectAsState()
    val visible = LocalControlVisible.current
    val actions = rememberActions(graph)
    LaunchedEffect(graph, revision, connection, visible) {
        if (visible && connection == WorkerConnectionStatus.CONNECTED) {
            try { records = graph.ux.history.load() }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (failure: Exception) { actions.message = failure.message ?: "读取记录失败" }
        }
    }
    var diagnostics by remember { mutableStateOf(false) }
    var diagTargetId by remember(selectedSessionId, sessions.map { it.id }) {
        mutableStateOf(selectedSessionId ?: sessions.firstOrNull()?.id ?: "global")
    }
    var accountFilter by remember { mutableStateOf<String?>(null) }
    var strategyFilter by remember { mutableStateOf<String?>(null) }
    var strategyRevisionFilter by remember { mutableStateOf<String?>(null) }
    var resultFilter by remember { mutableStateOf<String?>(null) }
    var deviceFilter by remember { mutableStateOf<String?>(null) }
    var chartExpanded by remember { mutableStateOf(true) }
    var scoreRange by remember { mutableStateOf("最近 50 局") }

    fun strategyKey(record: DesktopRunRecord) = record.strategy?.id ?: "unknown"
    val strategyOptions = records.filter { it.singleRun }.asReversed().map { strategyKey(it) to (it.strategy?.name ?: "未记录策略") }.distinctBy { it.first }
    val revisionOptions = records.filter { strategyKey(it) == strategyFilter }.mapNotNull { it.strategy?.revision }.distinct().sortedDescending().map { it.toString() to "修订 $it" }
    fun accountKey(record: DesktopRunRecord) = record.accountId?.toString() ?: "alias:${record.alias}"
    val accountOptions = records.map { accountKey(it) to it.alias }.distinctBy { it.first }
    val deviceOptions = records.mapNotNull { it.deviceName?.takeIf(String::isNotBlank) }.distinct().map { it to it }
    val resultOptions = listOf("cleared" to "仅通关", "other" to "未通关与异常")

    val filtered = records.filter { record ->
        (accountFilter == null || accountKey(record) == accountFilter) &&
            (strategyFilter == null || strategyKey(record) == strategyFilter) &&
            (strategyRevisionFilter == null || record.strategy?.revision?.toString() == strategyRevisionFilter) &&
            (deviceFilter == null || record.deviceName == deviceFilter) &&
            when (resultFilter) {
                "cleared" -> record.cleared > 0 || record.result == "通关" || record.result == "已完成"
                "other" -> record.cleared == 0 && record.result != "通关" && record.result != "已完成"
                else -> true
            }
    }

    Column(Modifier.fillMaxSize()) {
        Message(actions.message, onDismiss = { actions.message = null })
        Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            FilterChip(!diagnostics, { diagnostics = false }, label = { Text("运行记录") })
            FilterChip(diagnostics, { diagnostics = true }, label = { Text("日志与诊断") })
            if (diagnostics && sessions.isNotEmpty()) {
                Spacer(Modifier.width(12.dp))
                Text("诊断目标：", style = MaterialTheme.typography.bodySmall, color = Muted)
                FilterChip(
                    selected = diagTargetId == "global",
                    onClick = { diagTargetId = "global" },
                    label = { Text("全局服务") },
                )
                sessions.forEach { tab ->
                    FilterChip(
                        selected = diagTargetId == tab.id,
                        onClick = { diagTargetId = tab.id },
                        label = { Text(tab.name) },
                    )
                }
            }
        }
        if (diagnostics) {
            val targetGraph = if (diagTargetId == "global") graph else (runtimes[diagTargetId] ?: graph)
            val targetLabel = if (diagTargetId == "global") "全局数据服务" else sessions.firstOrNull { it.id == diagTargetId }?.name
            Box(Modifier.weight(1f)) { DiagnosticsPage(targetGraph, targetLabel) }
        } else {
            Column(Modifier.weight(1f).padding(start = 24.dp, end = 24.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("运行记录", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                    Text("共 ${filtered.size} 条（保留最近 5000 条 / 180 天）", color = Muted, style = MaterialTheme.typography.bodySmall)
                }
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    RecordFilter("全部账号", accountFilter, accountOptions) { accountFilter = it }
                    RecordFilter("全部策略", strategyFilter, strategyOptions) { strategyFilter = it; strategyRevisionFilter = null }
                    if (strategyFilter != null && strategyFilter != "unknown") {
                        RecordFilter("全部修订", strategyRevisionFilter, revisionOptions) { strategyRevisionFilter = it }
                    }
                    if (deviceOptions.isNotEmpty()) RecordFilter("全部设备", deviceFilter, deviceOptions) { deviceFilter = it }
                    RecordFilter("全部结果", resultFilter, resultOptions) { resultFilter = it }
                    if (accountFilter != null || strategyFilter != null || deviceFilter != null || resultFilter != null) {
                        TextButton(onClick = {
                            accountFilter = null; strategyFilter = null; strategyRevisionFilter = null
                            deviceFilter = null; resultFilter = null
                        }) { Text("清除筛选") }
                    }
                }
                RecordsKpiStrip(filtered)
                Box(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                    RunScoreChart(filtered, scoreRange, { scoreRange = it }, chartExpanded, { chartExpanded = !chartExpanded })
                }
                if (records.isEmpty()) {
                    ConsoleCard("暂无运行记录") { TextButton(onClick = { diagnostics = true }) { Text("查看日志") } }
                } else if (filtered.isEmpty()) {
                    ConsoleCard("无匹配记录") {}
                } else {
                    LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(filtered.asReversed(), key = { it.id }) { record ->
                            var expanded by remember(record.id) { mutableStateOf(false) }
                            Surface(
                                onClick = { expanded = !expanded },
                                color = MaterialTheme.colorScheme.surface,
                                shape = MaterialTheme.shapes.medium,
                                border = BorderStroke(1.dp, Line),
                            ) {
                                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text("${record.type} · ${record.alias}", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                                        StatusBadge(record.result)
                                    }
                                    Text(
                                        "${formatTime(record.startedAt)} · ${record.durationMillis?.let(::formatDuration) ?: "运行中"} · ${record.goal}",
                                        fontFamily = FontFamily.Monospace,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Muted,
                                    )
                                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                        if (record.cleared > 0 && record.singleRun) {
                                            Text("分数：${record.score?.toString() ?: "未识别"}", style = MaterialTheme.typography.bodySmall, color = Muted)
                                        }
                                        if (record.singleRun) {
                                            Text(record.strategyLabel(), style = MaterialTheme.typography.bodySmall, color = Muted)
                                        }
                                        record.deviceName?.let {
                                            Text(it, style = MaterialTheme.typography.bodySmall, color = Muted)
                                        }
                                    }
                                    if (expanded) {
                                        SelectionContainer { Text(record.reason.ifBlank { "暂无停止原因" }, style = MaterialTheme.typography.bodySmall) }
                                        TextButton(onClick = { copyText("${record.type} · ${record.result}\n${record.reason}") }) { Text("复制详情") }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RecordsKpiStrip(records: List<DesktopRunRecord>) {
    val singles = remember(records) { records.filter { it.singleRun && it.endedAt != null } }
    val cleared = remember(singles) { singles.filter { it.cleared > 0 } }
    val scored = remember(cleared) { cleared.mapNotNull { it.score?.takeIf { s -> s > 0 } } }
    val durations = remember(singles) { singles.mapNotNull { it.durationMillis } }
    val passRate = if (singles.isEmpty()) "—" else "${cleared.size * 100 / singles.size}%"
    val avgScore = if (scored.isEmpty()) "—" else "${scored.average().toLong()}（最高 ${scored.max()}）"
    val avgDuration = if (durations.isEmpty()) "—" else formatDuration(durations.average().toLong())
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        KpiTile("单局总计", "${singles.size} 局", Modifier.weight(1f))
        KpiTile("通关率", passRate, Modifier.weight(1f))
        KpiTile("平均 / 最高分", avgScore, Modifier.weight(1f))
        KpiTile("平均耗时", avgDuration, Modifier.weight(1f))
    }
}

@Composable
private fun KpiTile(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(modifier, shape = DesktopCorners.sm, color = MaterialTheme.colorScheme.surface, border = BorderStroke(1.dp, Line)) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = Muted)
            Text(value, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun RecordFilter(label: String, value: String?, options: List<Pair<String, String>>, select: (String?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }, shape = MaterialTheme.shapes.small, modifier = Modifier.widthIn(max = 180.dp)) {
            Text(options.firstOrNull { it.first == value }?.second ?: label, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        DropdownMenu(open && LocalControlVisible.current, { open = false }) {
            DropdownMenuItem(text = { Text(label) }, onClick = { open = false; select(null) })
            options.forEach { (id, name) -> DropdownMenuItem(text = { Text(name) }, onClick = { open = false; select(id) }) }
        }
    }
}
