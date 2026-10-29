package com.landosol.toolbox.desktop.app.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.dp
import com.landosol.toolbox.desktop.app.control.*
import com.landosol.toolbox.labyrinth.LabyrinthRoleRatingItem
import com.landosol.toolbox.ui.labyrinth.LabyrinthRoleRatingsScreen
import kotlinx.coroutines.*
import kotlin.math.roundToInt

internal object ScoreEdits {
    fun initial(personal:Int?,original:Double?)=personal?.toString() ?: original?.roundToInt()?.toString().orEmpty()
    fun input(value:String)=value.length<=3 && value.all { it in '0'..'9' }
    fun score(value:String)=value.toIntOrNull()?.takeIf{it in 0..100 && input(value)}
    fun apply(id:String,value:String,change:(String,Int?)->Unit):Boolean {val number=score(value)?:return false;change(id,number);return true}
    fun restore(id:String,personal:Int?,change:(String,Int?)->Unit){if(personal!=null)change(id,null)}
}

@Composable internal fun RoleRatingsPage(graph:ControlSessionUi,profile:com.landosol.toolbox.desktop.app.adapters.storage.StrategyProfile,onClose:()->Unit) {
    var baseline by remember(profile.id) { mutableStateOf(profile) }
    val saved = baseline.settings
    val preferences by graph.ux.preferences.state.collectAsState()
    val catalog by graph.ux.catalog.collectAsState()
    var draft by remember {mutableStateOf(saved.personalRoleScores)}
    ReportControlDirty("ratings",draft!=saved.personalRoleScores)
    var query by remember{mutableStateOf("")};var modified by remember{mutableStateOf(false)}
    var attribute by remember{mutableStateOf("")};var position by remember{mutableStateOf("")};var sort by remember{mutableStateOf("已修改优先")}
    var original by remember{mutableStateOf(preferences.originalRatings)}
    var confirmDiscard by remember{mutableStateOf(false)}
    var pendingExit by remember{mutableStateOf<(() -> Unit)?>(null)}
    val navigation=LocalNavigationGuard.current
    val actions=rememberActions(graph)
    val change:(String,Int?)->Unit={id,value->draft=if(value==null)draft-id else draft+(id to value)}
    SideEffect{navigation.intercept={next->if(draft!=saved.personalRoleScores){pendingExit=next;confirmDiscard=true}else next()}}
    DisposableEffect(navigation){onDispose{navigation.intercept=null}}
    fun close(){navigation.request(onClose)}
    fun attributeOf(role:LabyrinthRoleRatingItem)=role.detail.split(" · ").firstOrNull{it in setOf("火","水","风","光","暗")}.orEmpty()
    fun positionOf(role:LabyrinthRoleRatingItem)=role.detail.split(" · ").firstOrNull{it !in setOf("火","水","风","光","暗")}.orEmpty()
    val attributes=catalog.map(::attributeOf).filter{it.isNotBlank()}.distinct().sorted()
    val positions=catalog.map(::positionOf).filter{it.isNotBlank()}.distinct().sorted()
    val shown=remember(catalog,query,modified,attribute,position,sort,draft){catalog.filter{
        (query.isBlank()||it.name.contains(query,true)||it.id.contains(query))&&(!modified||it.id in draft)&&
            (attribute.isBlank()||attributeOf(it)==attribute)&&(position.isBlank()||positionOf(it)==position)
    }.let{rows->when(sort){"原评分降序"->rows.sortedByDescending{it.originalScore?:-1.0};"名称"->rows.sortedBy{it.name};"ID"->rows.sortedBy{it.id};else->rows.sortedByDescending{it.id in draft}}}}
    val focus=remember(shown.map{it.id}){shown.associate{it.id to FocusRequester()}}
    Column(Modifier.fillMaxSize().padding(24.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text("角色个人评分",style=MaterialTheme.typography.titleLarge);Text("个人评分优先用于选人和编组。不会补全缺失角色资料或覆盖数据库。",color=Muted,style=MaterialTheme.typography.bodySmall)};OutlinedButton(shape=MaterialTheme.shapes.small,onClick={original=true}){Text("原版表单")};TextButton(onClick=::close){Text("返回策略")}}
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalAlignment=Alignment.CenterVertically){OutlinedTextField(query,{query=it},placeholder={Text("搜索角色名、版本或 ID")},singleLine=true,modifier=Modifier.weight(1f));ChoiceMenu("全部属性",attribute,attributes){attribute=it};ChoiceMenu("全部定位",position,positions){position=it};FilterChip(modified,{modified=!modified},label={Text("仅看已修改")});ChoiceMenu("排序",sort,listOf("已修改优先","原评分降序","名称","ID"),false){sort=it}}
        Surface(Modifier.weight(1f).fillMaxWidth(),color=MaterialTheme.colorScheme.surface,shape=MaterialTheme.shapes.medium,border=BorderStroke(1.dp,Line)) {
            Column {
                Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerLow).padding(horizontal=12.dp,vertical=10.dp)){Text("角色",Modifier.weight(1f));Text("属性 / 定位",Modifier.width(138.dp));Text("ID",Modifier.width(62.dp));Text("原评分",Modifier.width(65.dp));Text("我的评分",Modifier.width(116.dp));Text("操作",Modifier.width(124.dp))}
                if(shown.isEmpty())Column(Modifier.fillMaxWidth().padding(28.dp),horizontalAlignment=Alignment.CenterHorizontally){Text(if(catalog.isEmpty())"角色资料尚未加载" else "没有符合条件的角色");TextButton(onClick={query="";modified=false;attribute="";position=""}){Text("清除筛选")}}
                LazyColumn {itemsIndexed(shown,key={_,r->r.id}){index,role->var input by remember(role.id,draft[role.id]){mutableStateOf(ScoreEdits.initial(draft[role.id],role.originalScore))}
                    Row(Modifier.fillMaxWidth().padding(horizontal=12.dp,vertical=5.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(0.dp)) {
                        Row(Modifier.weight(1f),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(9.dp)){Portrait(graph,role,role.name);Text(role.name,style=MaterialTheme.typography.bodySmall)}
                        Text(role.detail.ifBlank{"—"},Modifier.width(138.dp),style=MaterialTheme.typography.bodySmall,color=Muted)
                        Text(role.id,Modifier.width(62.dp),fontFamily=FontFamily.Monospace,style=MaterialTheme.typography.bodySmall)
                        Text(role.originalScore?.toString() ?: "暂无",Modifier.width(65.dp),fontFamily=FontFamily.Monospace,style=MaterialTheme.typography.bodySmall)
                        BasicTextField(input,{if(ScoreEdits.input(it))input=it},singleLine=true,cursorBrush=SolidColor(Accent),textStyle=MaterialTheme.typography.bodySmall.copy(fontFamily=FontFamily.Monospace,color=MaterialTheme.colorScheme.onSurface),modifier=Modifier.width(76.dp).height(34.dp).border(1.dp,if(ScoreEdits.score(input)==null)MaterialTheme.colorScheme.error else if(role.id in draft)Accent else Line,MaterialTheme.shapes.small).padding(7.dp).focusRequester(focus.getValue(role.id)).onPreviewKeyEvent{e->if(e.type!=KeyEventType.KeyDown)false else when(e.key){Key.Enter->{ScoreEdits.apply(role.id,input,change);true};Key.DirectionDown->{shown.getOrNull(index+1)?.let{focus[it.id]?.requestFocus()};true};Key.DirectionUp->{shown.getOrNull(index-1)?.let{focus[it.id]?.requestFocus()};true};else->false}})
                        TextButton(onClick={ScoreEdits.apply(role.id,input,change)},enabled=ScoreEdits.score(input)!=null,contentPadding=PaddingValues(4.dp),modifier=Modifier.width(40.dp)){Text("应用",style=MaterialTheme.typography.bodySmall)}
                        TextButton(onClick={ScoreEdits.restore(role.id,draft[role.id],change)},enabled=role.id in draft,modifier=Modifier.width(124.dp)){Text("恢复原评分",style=MaterialTheme.typography.bodySmall)}
                    };HorizontalDivider(color=Line)
                }}
            }
        }
        Message(actions.message)
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text("${shown.size} 人 · 0–100 整数 · Enter 应用到草稿 · ↑↓ 移动",style=MaterialTheme.typography.bodySmall,color=Muted);Text(if(draft==saved.personalRoleScores)"配置已保存；下次启动生效" else "有未保存的修改",style=MaterialTheme.typography.bodySmall)};OutlinedButton(shape=MaterialTheme.shapes.small,onClick=::close,enabled=!actions.working){Text("取消")};Spacer(Modifier.width(8.dp));Button(shape=MaterialTheme.shapes.small,onClick={actions.run{baseline=graph.labyrinthStrategySettings.edit(baseline,saved.copy(personalRoleScores=draft));graph.log.add("角色评分已保存，下次启动生效",kind=com.landosol.toolbox.desktop.app.UserLogKind.KEY)}},enabled=draft!=saved.personalRoleScores&&!actions.working){Text("保存配置")}}
    }
    if(original)BoundStrategyDialog{LabyrinthRoleRatingsScreen(draft,graph::loadLabyrinthRoleRatings,change,{original=false})}
    if(confirmDiscard&&LocalControlVisible.current)AlertDialog(onDismissRequest={confirmDiscard=false;pendingExit=null},title={Text("放弃未保存的修改？")},text={Text("个人评分修改尚未保存。")},confirmButton={TextButton(onClick={confirmDiscard=false;val next=pendingExit;pendingExit=null;next?.invoke() ?: onClose()}){Text("放弃修改")}},dismissButton={TextButton(onClick={confirmDiscard=false;pendingExit=null}){Text("继续编辑")}})
}
@Composable internal fun ChoiceMenu(label:String,value:String,options:List<String>,clear:Boolean=true,select:(String)->Unit){var open by remember{mutableStateOf(false)};Box{OutlinedButton(shape=MaterialTheme.shapes.small,onClick={open=true}){Text(value.ifBlank{label},style=MaterialTheme.typography.bodySmall)};DropdownMenu(open&&LocalControlVisible.current,{open=false}){if(clear)DropdownMenuItem(text={Text(label)},onClick={select("");open=false});options.forEach{option->DropdownMenuItem(text={Text(option)},onClick={select(option);open=false})}}}

}
