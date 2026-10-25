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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.landosol.toolbox.desktop.app.DesktopAppGraph
import com.landosol.toolbox.desktop.app.adapters.storage.DesktopRunRecord
import com.landosol.toolbox.desktop.app.ui.theme.LocalDesktopColors
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

@Composable internal fun RecordsPage(graph:DesktopAppGraph) {
    val records by graph.ux.history.records.collectAsState()
    var diagnostics by remember {mutableStateOf(false)}
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(horizontal=24.dp,vertical=10.dp),horizontalArrangement=Arrangement.spacedBy(10.dp)) {
            FilterChip(!diagnostics,{diagnostics=false},label={Text("运行记录")})
            FilterChip(diagnostics,{diagnostics=true},label={Text("日志与诊断")})
        }
        if(diagnostics)Box(Modifier.weight(1f)){DiagnosticsPage(graph)}
        else Column(Modifier.weight(1f).padding(start=24.dp,end=24.dp,bottom=16.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){Text("运行记录",Modifier.weight(1f),style=MaterialTheme.typography.titleLarge);Text("最近 5000 条 / 180 天",color=Muted,style=MaterialTheme.typography.bodySmall)}
            HistoryChart(records)
            if(records.isEmpty()) ConsoleCard("还没有运行记录") {Text("首次任务结束后，这里会显示结果、耗时和停止原因。",color=Muted);TextButton(onClick={diagnostics=true}){Text("查看日志与诊断 →")}}
            else LazyColumn(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                items(records.asReversed(),key={it.id}){record->var expanded by remember(record.id){mutableStateOf(false)}
                    Surface(onClick={expanded=!expanded},color=MaterialTheme.colorScheme.surface,shape=MaterialTheme.shapes.medium,border=BorderStroke(1.dp,Line)) {
                        Column(Modifier.fillMaxWidth().padding(14.dp),verticalArrangement=Arrangement.spacedBy(7.dp)) {
                            Row(verticalAlignment=Alignment.CenterVertically){Text("${record.type} · ${record.alias}",Modifier.weight(1f),style=MaterialTheme.typography.titleSmall);StatusBadge(record.result)}
                            Text("${formatTime(record.startedAt)} · ${record.durationMillis?.let(::formatDuration) ?: "运行中"} · ${record.goal}",fontFamily=FontFamily.Monospace,style=MaterialTheme.typography.bodySmall,color=Muted)
                            if(expanded){SelectionContainer {Text(record.reason.ifBlank{"暂无停止原因"},style=MaterialTheme.typography.bodySmall)};TextButton(onClick={copyText("${record.type} · ${record.result}\n${record.reason}")}){Text("复制详情")}}
                        }
                    }
                }
            }
        }
    }
}

@Composable private fun HistoryChart(records:List<DesktopRunRecord>) {
    val days=remember{(13 downTo 0).map{LocalDate.now().minusDays(it.toLong())}}
    val groups=remember(records){records.filter{it.singleRun&&it.endedAt!=null&&it.cleared>0}.groupBy{Instant.ofEpochMilli(it.endedAt!!).atZone(ZoneId.systemDefault()).toLocalDate()}}
    val counts=days.map{groups[it].orEmpty().sumOf{r->r.cleared}}
    val times=days.map{groups[it].orEmpty().mapNotNull{r->r.durationMillis}.takeIf{it.isNotEmpty()}?.average()}
    ConsoleCard("近 14 天") {
        Row {Text("每日通关次数",color=Accent,style=MaterialTheme.typography.bodySmall);Spacer(Modifier.width(20.dp));Text("平均用时（折线）",color=LocalDesktopColors.current.ok,style=MaterialTheme.typography.bodySmall)}
        if(counts.all{it==0})Text("暂无已通关的单次运行样本。完成批量运行后展示统计。",color=Muted,style=MaterialTheme.typography.bodySmall)
        else {val accent=Accent;val ok=LocalDesktopColors.current.ok;val line=Line
            Canvas(Modifier.fillMaxWidth().height(105.dp)) {
                val step=size.width/14;val maxCount=counts.maxOrNull()!!.coerceAtLeast(1);val maxTime=times.filterNotNull().maxOrNull()?.coerceAtLeast(1.0)?:1.0
                drawLine(line,Offset(0f,size.height),Offset(size.width,size.height))
                counts.forEachIndexed{i,n->val h=n.toFloat()/maxCount*(size.height-8);drawRect(accent,Offset(i*step+step*.2f,size.height-h),Size(step*.6f,h))}
                times.forEachIndexed{i,t->if(t!=null){val p=Offset((i+.5f)*step,size.height-(t/maxTime*(size.height-8)).toFloat());drawCircle(ok,3f,p);if(i>0&&times[i-1]!=null)drawLine(ok,Offset((i-.5f)*step,size.height-(times[i-1]!!/maxTime*(size.height-8)).toFloat()),p,2f)}}
            }
            Row(Modifier.fillMaxWidth()){days.forEachIndexed{i,d->Text(if(i%2==0||i==13)"${d.monthValue}/${d.dayOfMonth}" else "",Modifier.weight(1f),style=MaterialTheme.typography.labelSmall,color=Muted)}}
            Text("合计 ${counts.sum()} 次通关 · 总平均 ${formatDuration(records.filter{it.singleRun&&it.cleared>0}.mapNotNull{it.durationMillis}.takeIf{it.isNotEmpty()}?.average()?.toLong() ?: 0)}",style=MaterialTheme.typography.bodySmall)
        }
    }
}
