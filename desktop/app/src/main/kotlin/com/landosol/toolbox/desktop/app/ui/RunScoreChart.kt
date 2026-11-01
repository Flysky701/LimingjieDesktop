package com.landosol.toolbox.desktop.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.landosol.toolbox.desktop.app.adapters.storage.DesktopRunRecord
import com.landosol.toolbox.desktop.app.ui.theme.LocalDesktopColors

internal fun DesktopRunRecord.strategyLabel(): String = strategy?.let { "${it.name} · 修订 ${it.revision}" } ?: "未记录策略"

internal fun scoredClears(records: List<DesktopRunRecord>, limit: Int): List<DesktopRunRecord> =
    records.associateBy { it.id }.values
        .filter { it.singleRun && it.endedAt != null && it.cleared > 0 && (it.score ?: 0) > 0 }
        .sortedWith(compareBy<DesktopRunRecord> { it.endedAt }.thenBy { it.id })
        .takeLast(limit)

private val chartLimits = mapOf("最近 50 局" to 50, "最近 100 局" to 100, "最近 200 局" to 200, "全部保留记录" to Int.MAX_VALUE)

@Composable
internal fun RunScoreChart(
    records: List<DesktopRunRecord>,
    range: String,
    selectRange: (String) -> Unit,
    expanded: Boolean,
    toggleExpanded: () -> Unit,
) {
    ConsoleCard("单局分数与耗时", action = {
        if (expanded) ChoiceMenu("范围", range, chartLimits.keys.toList(), clear = false, select = selectRange)
        TextButton(onClick = toggleExpanded) {
            Text(if (expanded) "收起图表" else "展开图表")
            Symbol("expand_more", modifier = Modifier.size(18.dp).rotate(if (expanded) 180f else 0f))
        }
    }) {
        if (expanded) {
            val samples = remember(records, range) { scoredClears(records, chartLimits.getValue(range)) }
            if (samples.isEmpty()) Text("暂无已通关且有分数的单局记录", color = Muted)
            else RunTrend(samples)
        }
    }
}

@Composable
private fun RunTrend(samples: List<DesktopRunRecord>) {
    val scores = remember(samples) { samples.map { it.score!! } }
    val durations = remember(samples) { samples.map { it.durationMillis!! } }
    val maxScore = remember(scores) { scores.max() }
    val maxDuration = remember(durations) { durations.max() }
    val summary = remember(samples) {
        "平均分 ${scores.average().toLong()} · 最高 $maxScore · 最低 ${scores.min()} · 平均耗时 ${formatDuration(durations.average().toLong())}"
    }
    val scoreColor = Accent
    val durationColor = LocalDesktopColors.current.ok
    val grid = Line
    val cursor = Muted
    var hovered by remember(samples) { mutableStateOf<Int?>(null) }

    Text(summary, style = MaterialTheme.typography.bodySmall)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("分数 · 折线", color = scoreColor, style = MaterialTheme.typography.labelSmall)
        Text("耗时 · 柱形", color = durationColor, style = MaterialTheme.typography.labelSmall)
    }
    Row(Modifier.fillMaxWidth().height(120.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        ChartAxis(maxScore, scoreColor, Alignment.End, Long::toString)
        Canvas(Modifier.weight(1f).fillMaxHeight()
            .semantics { contentDescription = "按通关顺序排列的单局分数折线和耗时柱形图" }
            .pointerInput(samples) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        hovered = if (event.type == PointerEventType.Exit) null else event.changes.firstOrNull()?.let {
                            (it.position.x / size.width.coerceAtLeast(1) * samples.size).toInt().coerceIn(samples.indices)
                        }
                    }
                }
            }) {
            val inset = 8.dp.toPx()
            val bottom = size.height - inset
            val height = bottom - inset
            val step = size.width / samples.size
            val barWidth = (step * 0.68f).coerceAtMost(18.dp.toPx())
            fun x(index: Int) = step * (index + 0.5f)
            fun scoreY(index: Int) = bottom - height * (scores[index].toDouble() / maxScore).toFloat()

            for (fraction in listOf(0f, 0.5f, 1f)) {
                val y = bottom - fraction * height
                drawLine(grid, Offset(0f, y), Offset(size.width, y))
            }
            val path = Path()
            samples.indices.forEach { index ->
                val barHeight = height * (durations[index].toDouble() / maxDuration.coerceAtLeast(1)).toFloat()
                val center = x(index)
                drawRect(durationColor.copy(alpha = 0.45f), Offset(center - barWidth / 2, bottom - barHeight), Size(barWidth, barHeight))
                val y = scoreY(index)
                if (index == 0) path.moveTo(center, y) else path.lineTo(center, y)
                if (samples.size == 1 || step >= 6.dp.toPx()) drawCircle(scoreColor, 2.dp.toPx(), Offset(center, y))
            }
            drawPath(path, scoreColor, style = Stroke(width = 2.dp.toPx()))
            hovered?.let { index ->
                val center = x(index)
                drawLine(cursor, Offset(center, inset), Offset(center, bottom))
                drawCircle(scoreColor, 4.dp.toPx(), Offset(center, scoreY(index)))
            }
        }
        ChartAxis(maxDuration, durationColor, Alignment.Start, ::formatDuration)
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(formatTime(samples.first().endedAt!!), style = MaterialTheme.typography.labelSmall, color = Muted)
        if (samples.size > 1) Text(formatTime(samples.last().endedAt!!), style = MaterialTheme.typography.labelSmall, color = Muted)
    }
    val index = hovered ?: samples.lastIndex
    val selected = samples[index]
    Text("${formatTime(selected.endedAt!!)} · ${scores[index]} 分 · 耗时 ${formatDuration(durations[index])}\n" +
        "${selected.alias} · ${selected.deviceName ?: "未记录设备"} · ${selected.strategyLabel()}",
        style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun ChartAxis(maximum: Long, color: Color, alignment: Alignment.Horizontal, label: (Long) -> String) {
    Column(Modifier.fillMaxHeight(), verticalArrangement = Arrangement.SpaceBetween, horizontalAlignment = alignment) {
        for (tick in listOf(maximum, maximum / 2, 0L)) {
            Text(label(tick), color = color, style = MaterialTheme.typography.labelSmall)
        }
    }
}
