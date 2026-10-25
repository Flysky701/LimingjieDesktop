package com.landosol.toolbox.desktop.app.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.landosol.toolbox.AppVersion
import com.landosol.toolbox.desktop.app.*
import com.landosol.toolbox.desktop.app.adapters.storage.DesktopRunRecord
import com.landosol.toolbox.desktop.app.ui.theme.*
import com.landosol.toolbox.desktop.device.DeviceConnectionState
import com.landosol.toolbox.desktop.ocr.OcrEngineStatus
import com.landosol.toolbox.labyrinth.*
import com.landosol.toolbox.labyrinth.batch.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.sample
import java.awt.image.BufferedImage
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable internal fun RunConsole(graph:DesktopAppGraph,s:RunConsoleState,selected:DesktopTask,select:(DesktopTask)->Unit,configure:()->Unit,navigate:(Page)->Unit,searchFocus:FocusRequester) {
    val database by graph.ux.database.collectAsState()
    val foreground by graph.ux.foreground.collectAsState()
    val ocr by graph.ocr.status.collectAsState()
    val preferences by graph.ux.preferences.state.collectAsState()
    val history by graph.ux.history.records.collectAsState()
    val settings by graph.settings.state.collectAsState()
    val strategy by graph.labyrinthStrategySettings.state.collectAsState()
    val credentials by graph.ux.credentials.collectAsState()
    val actions=rememberActions(graph)
    val ready=(s.device as? DeviceConnectionState.Ready)?.device
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val compact=maxWidth<1180.dp
        Row(Modifier.fillMaxSize().padding(if(compact)12.dp else 14.dp),horizontalArrangement=Arrangement.spacedBy(14.dp)) {
            Column(Modifier.width(if(compact)268.dp else 288.dp).fillMaxHeight().verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(14.dp)) {
                ConsoleCard("就绪检查",action={TextButton(onClick={actions.run { graph.ux.refreshForeground();graph.ux.refreshDatabase() }},enabled=ready!=null&&!actions.working){Text("重新检查",style=MaterialTheme.typography.bodySmall)}}) {
                    val items=listOf(
                        ReadinessItem("模拟器已连接",ready?.label ?: "去连接",ready!=null,"Devices"),
                        ReadinessItem("分辨率与触控",ready?.let { "${it.width}×${it.height} · ${if(it.touchReady) "触控就绪" else "只读"}" } ?: "等待连接",ready!=null,"Devices",ready!=null&&(ready.width!=1920||ready.height!=1080)),
                        ReadinessItem("游戏在前台",foreground?.let { if(com.landosol.toolbox.protocol.bilibili.GameServer.entries.any { s->s.packageName==it }) "上次检查通过" else "请打开游戏" } ?: "开始前检查",foreground!=null,"Devices",true),
                        ReadinessItem("账号凭据",if(credentials[s.task.selectedAccount?.id]==false) "缺凭据" else s.task.selectedAccount?.alias ?: "去添加",s.task.selectedAccount!=null&&credentials[s.task.selectedAccount?.id]!=false,"Accounts"),
                        ReadinessItem("国服数据库",database,database.startsWith("已就绪"),"Settings"),
                        ReadinessItem("识别模型",when(ocr){OcrEngineStatus.Ready->"已加载";OcrEngineStatus.Loading->"加载中";OcrEngineStatus.NotLoaded->"尚未加载";is OcrEngineStatus.Failed->"加载失败"},ocr is OcrEngineStatus.Ready,"Settings"))
                    Column { items.forEach { item -> Row(Modifier.fillMaxWidth().heightIn(min=27.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(7.dp)) {
                        Symbol(if(item.passed&&!item.warning) "check_circle" else "warning",tint=if(item.passed&&!item.warning)LocalDesktopColors.current.ok else LocalDesktopColors.current.warn,modifier=Modifier.size(16.dp))
                        Text(item.title,style=MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.weight(1f))
                        Text(item.detail,Modifier.widthIn(max=114.dp).clickable {navigate(Page.valueOf(item.page))},color=if(item.passed) Muted else Accent,style=MaterialTheme.typography.bodySmall,maxLines=1,overflow=TextOverflow.Ellipsis)
                    } } }
                    Row(verticalAlignment=Alignment.CenterVertically) {
                        Checkbox(settings.gameSettingsConfirmed && preferences.animationConfirmedVersion==AppVersion.name,{checked->actions.run { withContext(Dispatchers.IO){graph.settings.update{it.copy(gameSettingsConfirmed=checked)};graph.ux.preferences.update{it.copy(animationConfirmedVersion=if(checked)AppVersion.name else "")}} }},modifier=Modifier.size(28.dp))
                        Text("关闭“播放角色加入动画”\n请在游戏内确认",color=LocalDesktopColors.current.warn,style=MaterialTheme.typography.bodySmall)
                    }
                    Message(actions.message)
                }
                ConsoleCard("任务") {
                    DesktopTask.entries.chunked(2).forEach { pair -> Row(horizontalArrangement=Arrangement.spacedBy(7.dp)) {
                        pair.forEach { task -> Surface(onClick={select(task)},enabled=!s.busy,shape=DesktopCorners.sm,
                            color=if(selected==task)MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
                            border=BorderStroke(1.dp,if(selected==task)Accent else Line),modifier=Modifier.weight(1f)) {
                            Column(Modifier.padding(9.dp),verticalArrangement=Arrangement.spacedBy(3.dp)) { Text(task.title,style=MaterialTheme.typography.titleSmall,color=if(selected==task)MaterialTheme.colorScheme.onPrimaryContainer else LocalContentColor.current);Text(task.mode,style=MaterialTheme.typography.bodySmall,color=Muted) }
                        } }
                        if(pair.size==1) Spacer(Modifier.weight(1f))
                    } }
                    Text("难度 ${s.task.selectedDifficulty} · ${if(preferences.goals.isEmpty()) "尚未配置目标" else "${preferences.goals.size} 个公会 · 共 ${preferences.goals.sumOf{it.count}} 次"}",style=MaterialTheme.typography.bodySmall)
                    Text("策略共用 · 个人评分 ${strategy.personalRoleScores.size} 人",style=MaterialTheme.typography.bodySmall,color=Muted)
                    OutlinedButton(shape=MaterialTheme.shapes.small,onClick=configure,modifier=Modifier.fillMaxWidth()){Text("编辑完整配置")}
                }
            }
            Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(14.dp)) {
                if(!s.busy && s.batch==null && s.entry.message==null && s.task.message==null) {
                    ConsoleCard("开始今天的任务") { Symbol("play_arrow",modifier=Modifier.size(34.dp),tint=Accent);Text("选择左侧任务，完成就绪检查后，从底部开始。",color=Muted);TextButton(onClick={navigate(if(s.task.selectedAccount==null)Page.Accounts else Page.Devices)}){Text(if(s.task.selectedAccount==null)"添加账号 →" else "连接模拟器 →")} }
                    ConsoleCard("上次运行") {
                        val last=history.lastOrNull { !it.singleRun && it.endedAt!=null }
                        if(last==null) {Text("还没有运行记录",style=MaterialTheme.typography.titleSmall);Text("第一次任务结束后，这里会显示结果和停止原因。",color=Muted,style=MaterialTheme.typography.bodySmall)} else {Text("${last.type} · ${last.result}");Text(last.reason.ifBlank {"正常结束"},color=Muted,style=MaterialTheme.typography.bodySmall);Text("耗时 ${formatDuration(last.durationMillis ?: 0)}",fontFamily=FontFamily.Monospace)}
                        TextButton(onClick={navigate(Page.Records)}){Text("查看全部记录 →")}
                    }
                } else {
                    ConsoleCard(if(s.batch!=null) "批量执行" else if(s.entry.running) if(s.entry.dryRun) "只读识别" else "执行入口流程" else "当前任务") {
                        val cp=s.batch
                        if(cp!=null) { Text("目标 ${(cp.activeGoalIndex+1).coerceAtMost(cp.goals.size)} / ${cp.goals.size} · ${s.task.guildOptions.firstOrNull{it.guildId==cp.currentGuildId}?.name ?: "目标公会"}",style=MaterialTheme.typography.titleMedium)
                            BatchStages(cp.stage,s.overlay?.paused==true) }
                        else Text(s.task.progress ?: s.entry.message ?: "等待任务结果",color=Muted)
                        val record=history.lastOrNull { it.endedAt==null&&!it.singleRun }
                        if(record!=null) RunClock(record.startedAt)
                    }
                    val raw=s.batch?.message ?: s.entry.message ?: s.task.message
                    if(!s.busy && s.batch?.stage==LabyrinthBatchStage.COMPLETED)ConsoleCard("全部目标已完成"){Text("本次批量任务已结束，可在记录中查看各次结果。",color=Muted);TextButton(onClick={navigate(Page.Records)}){Text("查看运行记录")}}
                    else if(!s.busy && raw!=null && (s.halt!=null || s.entry.status!=LabyrinthEntryRecognitionStatus.IDLE || s.task.message!=null)) StopReasonCard(explainStop(s.halt,raw)){target->if(target=="Run")select(DesktopTask.RECOGNIZE) else navigate(Page.valueOf(target))}
                    s.entry.routeProgress?.let { route -> ConsoleCard("本区域进度") {
                        Text("区域 ${route.currentArea} · 节点 ${route.visitedCount}/${route.routeNodeCount}",fontFamily=FontFamily.Monospace,style=MaterialTheme.typography.bodySmall)
                        val accent=Accent;val line=Line
                        Canvas(Modifier.fillMaxWidth().height(25.dp)) { val count=route.routeNodeCount.coerceIn(1,80);val step=if(count>1)(size.width-12)/ (count-1) else 0f
                            repeat(count){i->val x=6+i*step;if(i>0)drawLine(if(i<route.visitedCount)accent else line,Offset(x-step,12f),Offset(x,12f),2f);drawCircle(if(i<route.visitedCount)accent else line,4f,Offset(x,12f))} }
                        route.nextNodeLabel?.let {Text("下一节点：$it",color=Muted,style=MaterialTheme.typography.bodySmall)}
                        s.entry.combatContext?.let {Text("${when(it.kind){LabyrinthCombatKind.NORMAL->"普通战斗";LabyrinthCombatKind.EX->"EX 战斗";LabyrinthCombatKind.BOSS->"Boss"}} · 第 ${it.teamIndex} 队",style=MaterialTheme.typography.bodySmall)}
                    } }
                    if(s.entry.joinedCharacters.isNotEmpty()||s.entry.observedRelics.isNotEmpty()) ConsoleCard("已加入角色与遗物") {
                        val catalog by graph.ux.catalog.collectAsState()
                        Row(horizontalArrangement=Arrangement.spacedBy(5.dp),verticalAlignment=Alignment.CenterVertically){s.entry.joinedCharacters.take(5).forEach { character -> Portrait(graph,catalog.firstOrNull{it.id==character.characterId},character.displayName) };if(s.entry.joinedCharacters.size>5)Text("+${s.entry.joinedCharacters.size-5}")}
                        Text(s.entry.observedRelics.take(4).joinToString(" · "){it.displayName}+(if(s.entry.observedRelics.size>4)" +${s.entry.observedRelics.size-4}" else ""),style=MaterialTheme.typography.bodySmall,color=Muted)
                    }
                    s.batch?.let { cp -> ConsoleCard("批量目标") { cp.goals.forEach { goal ->
                        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) { Text(s.task.guildOptions.firstOrNull{it.guildId==goal.guildId}?.name ?: "公会 ${goal.guildId}",Modifier.weight(1f),style=MaterialTheme.typography.bodySmall);Text("${goal.completedCount}/${goal.targetCount} ${if(goal.mode==LabyrinthBatchGoalMode.CLEARS)"通关" else "开局"}",fontFamily=FontFamily.Monospace,style=MaterialTheme.typography.bodySmall) }
                        LinearProgressIndicator(progress={ (goal.completedCount.toFloat()/goal.targetCount).coerceIn(0f,1f) },modifier=Modifier.fillMaxWidth().height(4.dp))
                    };Text("已开始 ${cp.runsStarted} · 异常 ${cp.abnormalRuns}",fontFamily=FontFamily.Monospace,style=MaterialTheme.typography.bodySmall)
                        val samples=history.filter{it.singleRun && it.durationMillis!=null && it.result=="通关"};if(samples.size>=2)Text("平均每次 ${formatDuration(samples.mapNotNull{it.durationMillis}.average().toLong())}",style=MaterialTheme.typography.bodySmall,color=Muted)
                    } }
                    val choice=s.entry.roleRewardChoiceDecision
                    val decision=when(choice){is LabyrinthRoleRewardChoiceDecision.Select->"选择 ${choice.displayName}：${choice.explanation.joinToString("；")}";is LabyrinthRoleRewardChoiceDecision.Wait->choice.reason;null->s.entry.battleTeamRecommendation?.reasons?.joinToString("；") ?: s.entry.battleTeamRecommendationUnavailableReason ?: eventDecisionText(s.entry.eventChoiceDecision)}
                    if(decision!=null)ConsoleCard("最近决策"){SelectionContainer{Text(decision,style=MaterialTheme.typography.bodySmall,maxLines=6,overflow=TextOverflow.Ellipsis)}}
                }
            }
            Column(Modifier.width(if(compact)280.dp else 340.dp).fillMaxHeight(),verticalArrangement=Arrangement.spacedBy(14.dp)) {
                if(compact) Row { listOf("preview" to "画面","logs" to "日志").forEach{(key,label)->FilterChip(selected=preferences.rightTab==key,onClick={actions.run{withContext(Dispatchers.IO){graph.ux.preferences.update{it.copy(rightTab=key)}}}},label={Text(label)},modifier=Modifier.weight(1f))} }
                if(!compact||preferences.rightTab=="preview")ConsoleCard("实时画面"){Preview(graph,s.busy);Text("帧 ${s.entry.frameCount} · 动作 ${s.entry.actionCount}",fontFamily=FontFamily.Monospace,style=MaterialTheme.typography.bodySmall);s.entry.lastActionLabel?.let{Text("上一步：$it",style=MaterialTheme.typography.bodySmall,color=Muted)}}
                if(!compact||preferences.rightTab!="preview") LogPanel(graph,Modifier.weight(1f),searchFocus)
            }
        }
    }
}
@Composable internal fun ConsoleCard(title:String,modifier:Modifier=Modifier,action:(@Composable ()->Unit)?=null,content:@Composable ColumnScope.()->Unit){Surface(modifier.fillMaxWidth(),color=MaterialTheme.colorScheme.surface,shape=DesktopCorners.lg,border=BorderStroke(1.dp,Line)){Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(6.dp)){Row(verticalAlignment=Alignment.CenterVertically){Text(title,Modifier.weight(1f),style=MaterialTheme.typography.titleSmall);action?.invoke()};content()}}}
@Composable internal fun BatchStages(stage:LabyrinthBatchStage,paused:Boolean){val names=listOf("刷开局","重置会话","执行中","记录结果");val current=when(stage){LabyrinthBatchStage.REROLLING->0;LabyrinthBatchStage.INVALIDATING_OLD_CLIENT_SESSION->1;LabyrinthBatchStage.RUNNING_LABYRINTH->2;LabyrinthBatchStage.RECORDING_RESULT->3;else->-1};Row(horizontalArrangement=Arrangement.spacedBy(4.dp)){names.forEachIndexed{i,name->Surface(Modifier.weight(1f),shape=DesktopCorners.sm,color=if(stage==LabyrinthBatchStage.FAILED)MaterialTheme.colorScheme.errorContainer else if(i==current)MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow){Text(if(paused&&i==current)"已暂停" else name,Modifier.padding(vertical=6.dp,horizontal=3.dp),style=MaterialTheme.typography.bodySmall,maxLines=1)}}};if(current<0)Text(when(stage){LabyrinthBatchStage.COMPLETED->"全部目标已完成";LabyrinthBatchStage.PAUSED->"批量任务已停止 / 暂停";LabyrinthBatchStage.FAILED->"批量任务已停止";else->"等待开始"},color=Muted,style=MaterialTheme.typography.bodySmall)}
@Composable internal fun StopReasonCard(info:StopExplanation,onNavigate:(String)->Unit){var details by remember(info){mutableStateOf(false)};ConsoleCard(info.title){Text(info.reason);Text(info.advice,color=Muted,style=MaterialTheme.typography.bodySmall);Row{TextButton(onClick={onNavigate(info.destination)}){Text(info.action)};TextButton(onClick={details=!details}){Text(if(details)"收起详情" else "详情")}};if(details) {SelectionContainer{Text(info.original,style=MaterialTheme.typography.bodySmall)};TextButton(onClick={copyText(info.original)}){Text("复制原文")}}}}
@Composable internal fun RunClock(start:Long){var now by remember(start){mutableStateOf(System.currentTimeMillis())};LaunchedEffect(start){while(isActive){now=System.currentTimeMillis();delay(1000)}};Text(formatDuration(now-start),fontFamily=FontFamily.Monospace,style=MaterialTheme.typography.titleMedium)}
internal fun formatDuration(ms:Long):String {val s=ms.coerceAtLeast(0)/1000;return "%02d:%02d:%02d".format(s/3600,s/60%60,s%60)}
private fun eventDecisionText(event:LabyrinthEventChoiceDecision?):String?=when(event){is LabyrinthEventChoiceDecision.Select->"${event.label}：${event.explanation}";is LabyrinthEventChoiceDecision.Wait->event.reason;null->null}
internal fun formatTime(ms:Long):String=DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(ms))
@Composable internal fun Portrait(graph:DesktopAppGraph,role:LabyrinthRoleRatingItem?,name:String,modifier:Modifier=Modifier.size(36.dp)){
    val bitmap by produceState<ImageBitmap?>(null,role?.iconAsset){value=withContext(Dispatchers.IO){runCatching{role?.iconAsset?.let{asset->graph.context.assets.open(asset).use{stream->BitmapFactory.decodeStream(stream)?.let{decoded->try{decoded.toBufferedImage().toComposeImageBitmap()}finally{decoded.recycle()}}}}}.getOrNull()}}
    Surface(modifier,shape=DesktopCorners.sm,color=MaterialTheme.colorScheme.surfaceContainerHigh){if(bitmap!=null)Image(bitmap!!,name,Modifier.fillMaxSize()) else Box(contentAlignment=Alignment.Center){Text(name.take(1),style=MaterialTheme.typography.bodySmall)}}
}
@Composable internal fun Preview(graph:DesktopAppGraph,running:Boolean=false){val pref by graph.ux.preferences.state.collectAsState();var expanded by remember{mutableStateOf(running||pref.previewExpanded)};LaunchedEffect(running){if(running)expanded=true};val frame by graph.preview.collectAsState();val overlay by graph.automationOverlayCoordinator.state.collectAsState();val actions=rememberActions(graph)
    TextButton(onClick={expanded=!expanded;actions.run{withContext(Dispatchers.IO){graph.ux.preferences.update{it.copy(previewExpanded=expanded)}};if(expanded&&!running)graph.startPreview()}},enabled=!actions.working){Text(if(expanded)"收起画面" else "展开画面")}
    if(expanded){val bitmap by produceState<ImageBitmap?>(null,frame){value=withContext(Dispatchers.Default){frame?.let{pixels->BufferedImage(pixels.width,pixels.height,BufferedImage.TYPE_INT_ARGB).apply{setRGB(0,0,pixels.width,pixels.height,pixels.pixels.copyOf(),0,pixels.width)}.toComposeImageBitmap()}}}
        if(bitmap==null)Text(actions.message ?: "尚无画面。连接模拟器后，点击展开预览。",color=Muted,style=MaterialTheme.typography.bodySmall)
        else Box(Modifier.fillMaxWidth().aspectRatio(bitmap!!.width.toFloat()/bitmap!!.height)){Image(bitmap!!,"模拟器最新画面",Modifier.fillMaxSize());val accent=Accent;val ok=LocalDesktopColors.current.ok;Canvas(Modifier.fillMaxSize()){overlay?.boxes?.forEach{box->drawRect(if(box.selected)accent else ok,Offset(box.left*size.width,box.top*size.height),Size(box.width*size.width,box.height*size.height),style=Stroke(1.5.dp.toPx()))}}}
    }else Text("空闲时折叠 · 预览上限 2 fps",style=MaterialTheme.typography.bodySmall,color=Muted)
}

@OptIn(FlowPreview::class)
@Composable internal fun LogPanel(graph:DesktopAppGraph,modifier:Modifier=Modifier,searchFocus:FocusRequester=remember{FocusRequester()}){
    val lines by remember(graph){graph.log.lines.sample(250)}.collectAsState(graph.log.lines.value)
    var filter by remember{mutableStateOf("关键")};var query by remember{mutableStateOf("")};val listState=rememberLazyListState();val scope=rememberCoroutineScope()
    val shown=remember(lines,filter,query){lines.filter{(filter=="全部"||filter=="关键"&&it.kind!=UserLogKind.NORMAL||filter=="警告与错误"&&it.kind in listOf(UserLogKind.WARNING,UserLogKind.ERROR))&&(query.isBlank()||it.text.contains(query,true))}}
    var follow by remember{mutableStateOf(true)}
    LaunchedEffect(listState.isScrollInProgress){if(listState.isScrollInProgress)follow=!listState.canScrollForward}
    LaunchedEffect(shown.size,shown.lastOrNull()?.repeats){if(follow&&shown.isNotEmpty())listState.scrollToItem(shown.lastIndex)}
    Surface(modifier.fillMaxWidth(),color=MaterialTheme.colorScheme.surface,shape=DesktopCorners.lg,border=BorderStroke(1.dp,Line)){Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(7.dp)){
        Row(verticalAlignment=Alignment.CenterVertically){Text("日志",Modifier.weight(1f),style=MaterialTheme.typography.titleSmall);TextButton(onClick={copyText(graph.log.text())}){Text("复制全部",style=MaterialTheme.typography.bodySmall)}}
        Row(horizontalArrangement=Arrangement.spacedBy(4.dp)){listOf("关键","全部","警告与错误").forEach{label->FilterChip(selected=filter==label,onClick={filter=label},label={Text(label,style=MaterialTheme.typography.bodySmall)})}}
        OutlinedTextField(query,{query=it},placeholder={Text("搜索日志 · Ctrl+F",style=MaterialTheme.typography.bodySmall)},singleLine=true,modifier=Modifier.fillMaxWidth().focusRequester(searchFocus))
        if(shown.isEmpty())Column(Modifier.weight(1f).fillMaxWidth().padding(12.dp),verticalArrangement=Arrangement.Center,horizontalAlignment=Alignment.CenterHorizontally){Symbol("bar_chart",tint=Muted);Text(if(lines.isEmpty())"等待第一条任务日志" else "没有匹配的日志",style=MaterialTheme.typography.bodySmall,color=Muted);TextButton(onClick={filter="全部";query=""}){Text("显示全部")}}
        else SelectionContainer(Modifier.weight(1f)){LazyColumn(state=listState,verticalArrangement=Arrangement.spacedBy(9.dp)){itemsIndexed(shown){_,line->Column {Text("${line.time} · ${line.kind.label}",fontFamily=FontFamily.Monospace,style=MaterialTheme.typography.labelSmall,color=Muted);Text(line.text+if(line.repeats>1)" ×${line.repeats}" else "",style=MaterialTheme.typography.bodySmall,color=when(line.kind){UserLogKind.ERROR->LocalDesktopColors.current.error;UserLogKind.WARNING->LocalDesktopColors.current.warn;UserLogKind.KEY->Accent;else->LocalContentColor.current});HorizontalDivider(color=Line)}}}}
        if(!follow)TextButton(onClick={follow=true;scope.launch{if(shown.isNotEmpty())listState.scrollToItem(shown.lastIndex)}}){Text("回到最新")}
        Text("可选中文字后 Ctrl+C 复制",style=MaterialTheme.typography.bodySmall,color=Muted)
    }}
}
