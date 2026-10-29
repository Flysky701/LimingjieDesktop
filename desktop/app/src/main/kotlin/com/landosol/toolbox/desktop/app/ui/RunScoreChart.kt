package com.landosol.toolbox.desktop.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.landosol.toolbox.desktop.app.adapters.storage.DesktopRunRecord
import kotlin.math.roundToInt

internal fun DesktopRunRecord.strategyLabel(): String = strategy?.let { "${it.name} · 修订 ${it.revision}" } ?: "未记录策略"

@Composable internal fun RunScoreChart(records: List<DesktopRunRecord>) {
    var range by remember { mutableStateOf("最近 50 局") }
    val limit = when (range) { "最近 100 局" -> 100; "最近 200 局" -> 200; "全部保留记录" -> Int.MAX_VALUE; else -> 50 }
    val samples = remember(records, limit) {
        records.filter { it.singleRun && it.endedAt != null }.sortedWith(compareBy<DesktopRunRecord> { it.endedAt }.thenBy { it.id }).takeLast(limit)
    }
    val scores = samples.map { it.score?.takeIf { score -> it.cleared > 0 && score > 0 } }
    val valid = scores.filterNotNull()
    var hovered by remember(samples) { mutableStateOf<Int?>(null) }
    ConsoleCard("每局分数") {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("按完成顺序 · 鼠标移到图上查看单局", color = Muted, style = MaterialTheme.typography.bodySmall)
            ChoiceMenu("范围", range, listOf("最近 50 局", "最近 100 局", "最近 200 局", "全部保留记录"), clear = false) { range = it }
        }
        if (samples.isEmpty()) Text("暂无已结束的单局记录。完成任务后将在这里展示。", color = Muted)
        else {
            Text("${samples.size} 局 · 有效分数 ${valid.size} 局 · 通关未识别 ${samples.count { it.cleared > 0 && (it.score == null || it.score <= 0) }} 局 · 未通关 ${samples.count { it.cleared == 0 }} 局",
                style = MaterialTheme.typography.bodySmall)
            Text(if (valid.isEmpty()) "暂无可用分数；缺失分数不会按零分统计。" else
                "平均 ${valid.average().toLong()} · 最高 ${valid.maxOrNull()} · 最低 ${valid.minOrNull()}", style = MaterialTheme.typography.bodySmall)
            val maximum = (valid.maxOrNull() ?: 1L).coerceAtLeast(1).toDouble()
            val accent = Accent; val line = Line; val muted = Muted
            Row(Modifier.fillMaxWidth().height(130.dp)) {
                Column(Modifier.width(66.dp).fillMaxHeight(), verticalArrangement = Arrangement.SpaceBetween) {
                    Text(if (valid.isEmpty()) "—" else maximum.toLong().toString(), style = MaterialTheme.typography.labelSmall, color = Muted)
                    Text(if (valid.isEmpty()) "—" else (maximum / 2).toLong().toString(), style = MaterialTheme.typography.labelSmall, color = Muted)
                    Text("0", style = MaterialTheme.typography.labelSmall, color = Muted)
                }
                Canvas(Modifier.weight(1f).fillMaxHeight().semantics { contentDescription = "单局分数趋势，${samples.size} 局，有效分数 ${valid.size} 局" }
                    .pointerInput(samples) {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent()
                                hovered = if (event.type == PointerEventType.Exit) null else event.changes.firstOrNull()?.position?.let {
                                    ((it.x - 8f) / (size.width - 16f).coerceAtLeast(1f) * (samples.size - 1)).roundToInt().coerceIn(0, samples.lastIndex)
                                }
                            }
                        }
                    }) {
                    val width = (size.width - 16f).coerceAtLeast(1f); val height = size.height - 12f
                    fun x(index: Int) = if (samples.size == 1) size.width / 2 else 8f + index.toFloat() / samples.lastIndex * width
                    fun point(index: Int, score: Long) = Offset(x(index), 6f + height * (1 - score / maximum).toFloat())
                    listOf(0f, .5f, 1f).forEach { drawLine(line, Offset(8f, 6f + height * it), Offset(8f + width, 6f + height * it)) }
                    scores.forEachIndexed { index, score ->
                        if (score == null) drawLine(muted, Offset(x(index), size.height - 4), Offset(x(index), size.height), 2f)
                        else {
                            if (index > 0) scores[index - 1]?.let { drawLine(accent, point(index - 1, it), point(index, score), 2f) }
                            drawCircle(accent, if (samples.size > 200) 1.5f else 3f, point(index, score))
                        }
                    }
                    hovered?.let { index ->
                        drawLine(muted, Offset(x(index), 0f), Offset(x(index), size.height))
                        scores[index]?.let { drawCircle(accent, 5f, point(index, it)) }
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("第 1 局 · ${formatTime(samples.first().endedAt!!)}", style = MaterialTheme.typography.labelSmall, color = Muted)
                Text("第 ${samples.size} 局 · ${formatTime(samples.last().endedAt!!)}", style = MaterialTheme.typography.labelSmall, color = Muted)
            }
            val index = hovered ?: samples.lastIndex
            val selected = samples[index]
            Text("第 ${index + 1} 局 · ${scores[index]?.toString() ?: if (selected.cleared > 0) "分数未识别" else selected.result} · ${formatTime(selected.endedAt!!)} · 用时 ${selected.durationMillis?.let(::formatDuration) ?: "—"}\n" +
                "${selected.alias} · ${selected.deviceName ?: "未记录设备"} · ${selected.strategyLabel()}", style = MaterialTheme.typography.bodySmall, maxLines = 2)
        }
    }
}
