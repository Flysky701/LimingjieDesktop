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
import com.landosol.toolbox.desktop.app.control.*
import com.landosol.toolbox.desktop.app.adapters.storage.DesktopRunRecord
import com.landosol.toolbox.desktop.app.ui.theme.LocalDesktopColors
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

@Composable internal fun RecordsPage(graph:ControlSessionUi) {
    var records by remember(graph) { mutableStateOf(graph.ux.history.records.value) }
    val revision by graph.ux.history.revision.collectAsState()
    val connection by graph.port.connection.collectAsState()
    val visible = LocalControlVisible.current
    val actions = rememberActions(graph)
    LaunchedEffect(graph, revision, connection, visible) {
        if (visible && connection == WorkerConnectionStatus.CONNECTED) {
            try { records = graph.ux.history.load() }
            catch (cancelled:kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (failure:Exception) { actions.message = failure.message ?: "读取记录失败" }
        }
    }
    var diagnostics by remember {mutableStateOf(false)}
    var deviceFilter by remember{mutableStateOf<String?>(null)}
    var accountFilter by remember{mutableStateOf<String?>(null)}
    var taskFilter by remember{mutableStateOf<String?>(null)}
    var strategyFilter by remember{mutableStateOf<String?>(null)}
    var strategyRevisionFilter by remember{mutableStateOf<String?>(null)}
    var scoreChart by remember{mutableStateOf(true)}
    fun strategyKey(record:DesktopRunRecord)=record.strategy?.id ?: "unknown"
    val strategyOptions=records.filter{it.singleRun}.asReversed().map{strategyKey(it) to (it.strategy?.name ?: "未记录策略")}.distinctBy{it.first}
    val revisionOptions=records.filter{strategyKey(it)==strategyFilter}.mapNotNull{it.strategy?.revision}.distinct().sortedDescending().map{it.toString() to "修订 $it"}
    val deviceOptions=records.mapNotNull{record->record.deviceId?.let{it to (record.deviceName ?: it)}}.distinctBy{it.first}
    fun accountKey(record:DesktopRunRecord)=record.accountId?.toString() ?: "alias:${record.alias}"
    val accountOptions=records.map{accountKey(it) to it.alias}.distinctBy{it.first}
    val filtered=records.filter{record->
        (deviceFilter==null||record.deviceId==deviceFilter)&&
        (accountFilter==null||accountKey(record)==accountFilter)&&
        (taskFilter==null||record.type==taskFilter)&&
        (strategyFilter==null||strategyKey(record)==strategyFilter)&&
        (strategyRevisionFilter==null||record.strategy?.revision?.toString()==strategyRevisionFilter)
    }
    Column(Modifier.fillMaxSize()) {
        Message(actions.message)
        Row(Modifier.padding(horizontal=24.dp,vertical=10.dp),horizontalArrangement=Arrangement.spacedBy(10.dp)) {
            FilterChip(!diagnostics,{diagnostics=false},label={Text("运行记录")})
            FilterChip(diagnostics,{diagnostics=true},label={Text("日志与诊断")})
        }
        if(diagnostics)Box(Modifier.weight(1f)){DiagnosticsPage(graph)}
        else Column(Modifier.weight(1f).padding(start=24.dp,end=24.dp,bottom=16.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){Text("运行记录",Modifier.weight(1f),style=MaterialTheme.typography.titleLarge);Text("最近 5000 条 / 180 天",color=Muted,style=MaterialTheme.typography.bodySmall)}
            Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(10.dp),verticalAlignment=Alignment.CenterVertically){
                RecordFilter("全部设备",deviceFilter,deviceOptions){deviceFilter=it}
                RecordFilter("全部账号",accountFilter,accountOptions){accountFilter=it}
                RecordFilter("全部任务",taskFilter,records.map{it.type}.distinct().map{it to it}){taskFilter=it}
                if(deviceFilter!=null||accountFilter!=null||taskFilter!=null||strategyFilter!=null)TextButton(onClick={deviceFilter=null;accountFilter=null;taskFilter=null;strategyFilter=null;strategyRevisionFilter=null}){Text("清除筛选")}
            }
            Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(10.dp),verticalAlignment=Alignment.CenterVertically) {
                RecordFilter("全部策略",strategyFilter,strategyOptions){strategyFilter=it;strategyRevisionFilter=null}
                if(strategyFilter!=null&&strategyFilter!="unknown") RecordFilter("全部修订",strategyRevisionFilter,revisionOptions){strategyRevisionFilter=it}
                FilterChip(scoreChart,{scoreChart=true},label={Text("每局分数")})
                FilterChip(!scoreChart,{scoreChart=false},label={Text("每日通关与耗时")})
            }
            Box(Modifier.heightIn(max=360.dp).verticalScroll(rememberScrollState())) {
                if(scoreChart)RunScoreChart(filtered) else HistoryChart(filtered)
            }
            if(records.isEmpty()) ConsoleCard("还没有运行记录") {Text("首次任务结束后，这里会显示结果、耗时和停止原因。",color=Muted);TextButton(onClick={diagnostics=true}){Text("查看日志与诊断 →")}}
            else if(filtered.isEmpty())ConsoleCard("没有匹配的运行记录"){Text("调整设备、账号或任务筛选后查看。",color=Muted)}
            else LazyColumn(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                items(filtered.asReversed(),key={it.id}){record->var expanded by remember(record.id){mutableStateOf(false)}
                    Surface(onClick={expanded=!expanded},color=MaterialTheme.colorScheme.surface,shape=MaterialTheme.shapes.medium,border=BorderStroke(1.dp,Line)) {
                        Column(Modifier.fillMaxWidth().padding(14.dp),verticalArrangement=Arrangement.spacedBy(7.dp)) {
                            Row(verticalAlignment=Alignment.CenterVertically){Text("${record.type} · ${record.alias}",Modifier.weight(1f),style=MaterialTheme.typography.titleSmall);StatusBadge(record.result)}
                            Text("${formatTime(record.startedAt)} · ${record.durationMillis?.let(::formatDuration) ?: "运行中"} · ${record.goal}",fontFamily=FontFamily.Monospace,style=MaterialTheme.typography.bodySmall,color=Muted)
                            if(record.cleared>0 && record.singleRun) Text("分数：${record.score?.toString() ?: "未识别"}",style=MaterialTheme.typography.bodySmall,color=Muted)
                            if(record.singleRun)Text(record.strategyLabel(),style=MaterialTheme.typography.bodySmall,color=Muted)
                            record.deviceName?.let{Text(it,style=MaterialTheme.typography.bodySmall,color=Muted)}
                            if(expanded){SelectionContainer {Text(record.reason.ifBlank{"暂无停止原因"},style=MaterialTheme.typography.bodySmall)};TextButton(onClick={copyText("${record.type} · ${record.result}\n${record.reason}")}){Text("复制详情")}}
                        }
                    }
                }
            }
        }
    }
}

@Composable private fun RecordFilter(label:String,value:String?,options:List<Pair<String,String>>,select:(String?)->Unit){
    var open by remember{mutableStateOf(false)}
    Box{
        OutlinedButton(onClick={open=true},shape=MaterialTheme.shapes.small){Text(options.firstOrNull{it.first==value}?.second ?: label)}
        DropdownMenu(open&&LocalControlVisible.current,{open=false}){
            DropdownMenuItem(text={Text(label)},onClick={open=false;select(null)})
            options.forEach{(id,name)->DropdownMenuItem(text={Text(name)},onClick={open=false;select(id)})}
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
            Text("合计 ${counts.sum()} 次通关 · 总平均 ${formatDuration(groups.filterKeys{it in days}.values.flatten().mapNotNull{it.durationMillis}.takeIf{it.isNotEmpty()}?.average()?.toLong() ?: 0)}",style=MaterialTheme.typography.bodySmall)
            val scores=groups.filterKeys{it in days}.values.flatten().mapNotNull{it.score}
            Text(if(scores.isEmpty()) "暂无可用分数" else "已记录 ${scores.size} 局分数 · 平均 ${scores.average().toLong()} · 最高 ${scores.maxOrNull()}",style=MaterialTheme.typography.bodySmall)
        }
    }
}
