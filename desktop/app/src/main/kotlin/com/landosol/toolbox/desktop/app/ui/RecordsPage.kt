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
import com.landosol.toolbox.desktop.app.control.*
import com.landosol.toolbox.desktop.app.adapters.storage.DesktopRunRecord

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
    var accountFilter by remember{mutableStateOf<String?>(null)}
    var strategyFilter by remember{mutableStateOf<String?>(null)}
    var strategyRevisionFilter by remember{mutableStateOf<String?>(null)}
    var chartExpanded by remember{mutableStateOf(true)}
    var scoreRange by remember{mutableStateOf("最近 50 局")}
    fun strategyKey(record:DesktopRunRecord)=record.strategy?.id ?: "unknown"
    val strategyOptions=records.filter{it.singleRun}.asReversed().map{strategyKey(it) to (it.strategy?.name ?: "未记录策略")}.distinctBy{it.first}
    val revisionOptions=records.filter{strategyKey(it)==strategyFilter}.mapNotNull{it.strategy?.revision}.distinct().sortedDescending().map{it.toString() to "修订 $it"}
    fun accountKey(record:DesktopRunRecord)=record.accountId?.toString() ?: "alias:${record.alias}"
    val accountOptions=records.map{accountKey(it) to it.alias}.distinctBy{it.first}
    val filtered=records.filter{record->
        (accountFilter==null||accountKey(record)==accountFilter)&&
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
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp),verticalAlignment=Alignment.CenterVertically){
                RecordFilter("全部账号",accountFilter,accountOptions){accountFilter=it}
                RecordFilter("全部策略",strategyFilter,strategyOptions){strategyFilter=it;strategyRevisionFilter=null}
                if(strategyFilter!=null&&strategyFilter!="unknown") RecordFilter("全部修订",strategyRevisionFilter,revisionOptions){strategyRevisionFilter=it}
                if(accountFilter!=null||strategyFilter!=null)TextButton(onClick={accountFilter=null;strategyFilter=null;strategyRevisionFilter=null}){Text("清除筛选")}
            }
            Box(Modifier.heightIn(max=360.dp).verticalScroll(rememberScrollState())) {
                RunScoreChart(filtered, scoreRange, { scoreRange = it }, chartExpanded, { chartExpanded = !chartExpanded })
            }
            if(records.isEmpty()) ConsoleCard("暂无运行记录") {TextButton(onClick={diagnostics=true}){Text("查看日志")}}
            else if(filtered.isEmpty())ConsoleCard("无匹配记录"){}
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
        OutlinedButton(onClick={open=true},shape=MaterialTheme.shapes.small,modifier=Modifier.widthIn(max=180.dp)){
            Text(options.firstOrNull{it.first==value}?.second ?: label,maxLines=1,overflow=TextOverflow.Ellipsis)
        }
        DropdownMenu(open&&LocalControlVisible.current,{open=false}){
            DropdownMenuItem(text={Text(label)},onClick={open=false;select(null)})
            options.forEach{(id,name)->DropdownMenuItem(text={Text(name)},onClick={open=false;select(id)})}
        }
    }
}
