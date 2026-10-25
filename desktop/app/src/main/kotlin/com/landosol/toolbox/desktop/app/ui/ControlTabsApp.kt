package com.landosol.toolbox.desktop.app.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.key.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow
import com.landosol.toolbox.desktop.app.control.*
import com.landosol.toolbox.desktop.app.adapters.storage.DesktopPreferences
import com.landosol.toolbox.desktop.app.ui.theme.*
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.*

data class ControlTab(val id:String,val name:String,val session:ControlSessionPort)
data class AvailableControlProfile(val id:String,val name:String)
data class ControlShellCallbacks(
    val select:(String)->Unit,
    val create:(String)->Unit,
    val open:(String)->Unit,
    val close:(String)->Unit,
    val rename:(String,String)->Unit,
    val stopAll:()->Unit,
    val onDirtyChanged:(String,Boolean)->Unit={_,_->},
)
internal val LocalControlVisible=staticCompositionLocalOf{true}
internal val LocalControlDirty=staticCompositionLocalOf<(String,Boolean)->Unit>{{_,_->}}
internal data class ControlKeyActions(val start:()->Unit,val pause:()->Unit,val stop:()->Unit,val search:()->Unit)
internal val LocalControlKeyActions=staticCompositionLocalOf<(ControlKeyActions)->Unit>{{}}

/** One Compose shell; worker ports contain no local Graph. Hidden tab compositions retain drafts. */
@Composable fun ControlTabsApp(
    tabs:List<ControlTab>,selectedId:String?,callbacks:ControlShellCallbacks,
    hostWindow:java.awt.Window?=null,availableProfiles:List<AvailableControlProfile> = emptyList(),error:String?=null,
) {
    val views=remember{mutableMapOf<String,Pair<ControlSessionPort,ControlSessionUi>>()}
    tabs.forEach{tab->if(views[tab.id]?.first!==tab.session){val previous=views.remove(tab.id)?.second;val next=ControlSessionUi(tab.session,previous);previous?.close();views[tab.id]=tab.session to next}}
    DisposableEffect(Unit){onDispose{views.values.forEach{it.second.close()}}}
    val selected=tabs.firstOrNull{it.id==selectedId} ?: tabs.firstOrNull()
    val active=selected?.let{views[it.id]?.second}
    val preferences=active?.ux?.preferences?.state?.collectAsState()?.value ?: DesktopPreferences()
    var add by remember{mutableStateOf(false)}
    var create by remember{mutableStateOf(false)}
    var name by remember{mutableStateOf("")}
    var rename by remember{mutableStateOf<ControlTab?>(null)}
    var pendingClose by remember{mutableStateOf<ControlTab?>(null)}
    var pendingReconnect by remember{mutableStateOf<ControlTab?>(null)}
    val guards=remember{mutableMapOf<String,NavigationGuard>()}
    val keys=remember{mutableMapOf<String,ControlKeyActions>()}
    val dirty=remember{mutableStateMapOf<String,Boolean>()}
    LaunchedEffect(tabs.map{it.id}){(views.keys-tabs.map{it.id}.toSet()).forEach{views.remove(it)?.second?.close();TabDrafts.clear(it);dirty.remove(it);callbacks.onDirtyChanged(it,false)}}
    fun select(id:String){callbacks.select(id)}
    fun closeTab(tab:ControlTab){
        if(tab.id!=selected?.id)callbacks.select(tab.id)
        if(dirty[tab.id]==true||views[tab.id]?.second?.busy==true)pendingClose=tab else callbacks.close(tab.id)
    }
    fun reconnectTab(tab:ControlTab){
        if(dirty[tab.id]==true)pendingReconnect=tab else callbacks.open(tab.id)
    }
    DesktopTheme(preferences) {
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).onPreviewKeyEvent{event->
            val action=keys[selected?.id]
            if(event.type!=KeyEventType.KeyDown||action==null)false else when {
                event.key==Key.F5->{action.start();true}
                event.key==Key.F6->{action.pause();true}
                event.key==Key.F7->{action.stop();true}
                event.isCtrlPressed&&event.key==Key.F->{action.search();true}
                else->false
            }
        }) {
            Surface(color=MaterialTheme.colorScheme.surface) {
                Row(Modifier.fillMaxWidth().heightIn(min=50.dp).padding(horizontal=10.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                    Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                        tabs.forEach{tab->val snapshot by tab.session.snapshots.collectAsState();val connection by tab.session.connection.collectAsState()
                            val state=ControlUiSnapshot(snapshot).console
                            val captcha=snapshot["captcha"] as? JsonObject
                            val label=tab.name+(state.task.selectedAccount?.alias?.let{" · $it"} ?: "")
                            val status=when{connection==WorkerConnectionStatus.STARTING->"连接中";connection==WorkerConnectionStatus.LOST->"控制失联";connection==WorkerConnectionStatus.CLOSED->"已关闭";captcha!=null->"需验证码";state.stopping->"停止中";state.overlay?.paused==true->"已暂停";state.busy->"运行中";state.device is com.landosol.toolbox.desktop.device.DeviceConnectionState.Lost->"模拟器失联";else->"空闲"}
                            Surface(onClick={select(tab.id)},shape=DesktopCorners.sm,color=if(selected?.id==tab.id)MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
                                border=BorderStroke(1.dp,if(selected?.id==tab.id)Accent else Line)) {
                                Row(Modifier.widthIn(min=150.dp,max=260.dp).padding(start=10.dp),verticalAlignment=Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f).padding(vertical=6.dp)) {
                                        Text(label+(if(dirty[tab.id]==true)" •" else ""),style=MaterialTheme.typography.titleSmall,maxLines=1,overflow=TextOverflow.Ellipsis)
                                        Text(deviceLabel(state.device)+" · "+status,style=MaterialTheme.typography.bodySmall,color=if(captcha!=null||connection==WorkerConnectionStatus.LOST)LocalDesktopColors.current.warn else Muted,maxLines=1,overflow=TextOverflow.Ellipsis)
                                    }
                                    IconButton(onClick={closeTab(tab)},modifier=Modifier.size(30.dp)){Symbol("close","关闭 ${tab.name}",modifier=Modifier.size(15.dp))}
                                }
                            }
                        }
                    }
                    Box {
                        TextButton(onClick={add=true}){Text("＋")}
                        DropdownMenu(add,{add=false}) {
                            DropdownMenuItem(text={Text("新建控制标签")},onClick={add=false;name="";create=true})
                            availableProfiles.filter{profile->tabs.none{it.id==profile.id}}.forEach{profile->DropdownMenuItem(text={Text("打开 ${profile.name}")},onClick={add=false;callbacks.open(profile.id)})}
                            selected?.let{tab->DropdownMenuItem(text={Text("重命名当前标签")},onClick={add=false;name=tab.name;rename=tab})
                                DropdownMenuItem(text={Text("重新连接控制进程")},onClick={add=false;reconnectTab(tab)},enabled=tab.session.connection.value!=WorkerConnectionStatus.CONNECTED)}
                        }
                    }
                    OutlinedButton(onClick=callbacks.stopAll,shape=MaterialTheme.shapes.small){Text("停止全部")}
                }
            }
            HorizontalDivider(color=Line)
            error?.let{Message(it)}
            if(tabs.isEmpty()) Box(Modifier.weight(1f).fillMaxWidth(),contentAlignment=Alignment.Center){Column(horizontalAlignment=Alignment.CenterHorizontally){Text("打开一个控制标签",style=MaterialTheme.typography.titleLarge);Text("每个标签使用独立模拟器、账号和数据",color=Muted);Button(onClick={create=true}){Text("新建控制标签")}}}
            else Box(Modifier.weight(1f)) {
                tabs.forEach{tab->key(tab.id){
                    val visible=selected?.id==tab.id
                    val view=checkNotNull(views[tab.id]).second
                    val guard=remember{NavigationGuard()}
                    DisposableEffect(guard){guards[tab.id]=guard;onDispose{guards.remove(tab.id);keys.remove(tab.id)}}
                    LaunchedEffect(visible){tab.session.setSelected(visible)}
                    CompositionLocalProvider(LocalControlVisible provides visible,LocalControlName provides tab.name,LocalControlKeyActions provides {keys[tab.id]=it},LocalControlDirty provides {part,changed->
                        TabDrafts.set(tab.id,part,changed);val value=TabDrafts.dirty(tab.id);dirty[tab.id]=value;callbacks.onDirtyChanged(tab.id,value)
                    }) {
                        Box(Modifier.fillMaxSize().offset(x=if(visible)0.dp else 20000.dp).alpha(if(visible)1f else 0f)) {
                            DesktopApp(view,hostWindow,guard)
                        }
                    }
                }}
            }
            if(create||rename!=null) AlertDialog(onDismissRequest={create=false;rename=null},title={Text(if(create)"新建控制标签" else "重命名标签")},text={OutlinedTextField(name,{name=it},label={Text("名称")},singleLine=true)},
                confirmButton={TextButton(enabled=name.isNotBlank(),onClick={val tab=rename;create=false;rename=null;if(tab==null)callbacks.create(name.trim()) else callbacks.rename(tab.id,name.trim())}){Text("确定")}},dismissButton={TextButton(onClick={create=false;rename=null}){Text("取消")}})
            pendingClose?.let{tab->
                val unsaved=dirty[tab.id]==true
                val busy=views[tab.id]?.second?.busy==true
                val action=when{unsaved&&busy->"丢弃、停止并关闭";unsaved->"丢弃并关闭";else->"停止并关闭"}
                AlertDialog(onDismissRequest={pendingClose=null},title={Text("$action ${tab.name}？")},text={Text(buildString{
                    if(unsaved)append("此标签有未保存的修改，关闭时将丢弃。\n")
                    if(busy)append("仅停止此标签的任务并关闭它的控制进程。") else append("关闭此标签的控制进程。")
                    append("已保存的账号和配置会保留。")
                })},confirmButton={TextButton(onClick={pendingClose=null;callbacks.close(tab.id)}){Text(action)}},dismissButton={TextButton(onClick={pendingClose=null}){Text("取消")}})
            }
            pendingReconnect?.let{tab->AlertDialog(onDismissRequest={pendingReconnect=null},title={Text("丢弃修改并重新连接 ${tab.name}？")},text={Text("此标签有未保存的修改，重新连接可能丢失这些修改。确认丢弃后继续；已保存的账号和配置会保留。")},confirmButton={TextButton(onClick={pendingReconnect=null;callbacks.open(tab.id)}){Text("丢弃并重新连接")}},dismissButton={TextButton(onClick={pendingReconnect=null}){Text("取消")}})}
        }
    }
}

private object TabDrafts {
    private val parts=mutableMapOf<String,MutableMap<String,Boolean>>()
    fun set(id:String,part:String,dirty:Boolean){if(dirty)parts.getOrPut(id){mutableMapOf()}[part]=true else {parts[id]?.remove(part);if(parts[id].isNullOrEmpty())parts.remove(id)}}
    fun clear(id:String){parts.remove(id)}
    fun dirty(id:String)=parts[id]?.values?.any{it}==true
}
