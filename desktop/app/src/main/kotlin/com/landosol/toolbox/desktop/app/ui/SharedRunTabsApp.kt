package com.landosol.toolbox.desktop.app.ui

import android.content.res.Configuration
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.landosol.toolbox.AppVersion
import com.landosol.toolbox.desktop.app.adapters.storage.DesktopThemeMode
import com.landosol.toolbox.desktop.app.control.*
import com.landosol.toolbox.desktop.app.ui.theme.*
import kotlinx.serialization.json.JsonObject

private data class PendingSessionAction(val title:String,val message:String,val label:String,val action:()->Unit)

/** Shared pages have one lifetime and one data port; only the run viewport changes with selection. */
@Composable fun SharedRunTabsApp(
    globalSession:ControlSessionPort,
    sessions:List<DeviceRunTab>,
    selectedId:String?,
    callbacks:SharedRunShellCallbacks,
    devices:List<ManagedDeviceUi> = emptyList(),
    hostWindow:java.awt.Window?=null,
    error:String?=null,
) {
    val global=remember(globalSession){ControlSessionUi(globalSession)}
    DisposableEffect(global){onDispose{global.close()}}
    val preferences by global.ux.preferences.state.collectAsState()
    val settings by global.settings.state.collectAsState()
    val globalWire by globalSession.snapshots.collectAsState()
    val globalError by globalSession.error.collectAsState()
    val globalLocalError by global.error.collectAsState()
    val globalConnection by globalSession.connection.collectAsState()
    val accounts by global.accounts.uiState.collectAsState()
    val actions=rememberActions(global)
    val views=remember{mutableMapOf<String,Pair<ControlSessionPort,ControlSessionUi>>()}
    sessions.forEach{tab->if(views[tab.id]?.first!==tab.session){
        val previous=views.remove(tab.id)?.second
        val next=ControlSessionUi(tab.session,previous)
        previous?.close()
        views[tab.id]=tab.session to next
    }}
    DisposableEffect(Unit){onDispose{views.values.forEach{it.second.close()}}}
    val parts=remember{mutableMapOf<String,MutableSet<String>>()}
    val dirty=remember{mutableStateMapOf<String,Boolean>()}
    fun report(id:String,part:String,changed:Boolean){
        val state=parts.getOrPut(id){mutableSetOf()}
        if(changed)state+=part else state-=part
        dirty[id]=state.isNotEmpty()
        callbacks.onDirtyChanged(id,state.isNotEmpty())
    }
    LaunchedEffect(sessions.map{it.id}){(views.keys-sessions.map{it.id}.toSet()).forEach{
        views.remove(it)?.second?.close();parts.remove(it);dirty.remove(it);callbacks.onDirtyChanged(it,false)
    }}
    var page by remember{mutableStateOf(Page.Run)}
    val visited=remember{mutableStateListOf<Page>()}
    LaunchedEffect(page){if(page!=Page.Run&&page !in visited)visited+=page}
    var wizard by remember{mutableStateOf(false)}
    var pending by remember{mutableStateOf<PendingSessionAction?>(null)}
    val selected=sessions.firstOrNull{it.id==selectedId} ?: sessions.firstOrNull()
    val keys=remember{mutableMapOf<String,ControlKeyActions>()}
    val shellFocus=remember{FocusRequester()}
    LaunchedEffect(Unit){shellFocus.requestFocus()}
    fun openRun(id:String){callbacks.selectSession(id);page=Page.Run}
    fun confirmDraftChange(id:String,title:String,label:String,action:()->Unit){
        if(dirty[id]==true)pending=PendingSessionAction(title,"此设备会话有未保存的修改，继续会丢弃这些修改。已保存的账号和配置会保留。",label,action) else action()
    }
    fun closeRun(tab:DeviceRunTab){
        val busy=ControlUiSnapshot(tab.session.snapshots.value).console.busy
        val unsaved=dirty[tab.id]==true
        if(busy||unsaved){
            val label=when{busy&&unsaved->"丢弃、停止并关闭";busy->"停止并关闭";else->"丢弃并关闭"}
            pending=PendingSessionAction("$label ${tab.name}？",buildString{
                if(unsaved)append("此设备会话有未保存的修改，关闭时将丢弃。")
                if(busy)append("先停止此设备的任务，再关闭它的运行页。其他设备继续运行。")
                append("已保存的账号和配置会保留。")
            },label){callbacks.closeSession(tab.id)}
        } else callbacks.closeSession(tab.id)
    }
    val deviceCallbacks=callbacks.copy(
        bindAccount={id,account->confirmDraftChange(id,"丢弃修改并更换绑定？","丢弃并绑定"){callbacks.bindAccount(id,account)}},
        disconnect={id->confirmDraftChange(id,"丢弃修改并断开设备？","丢弃并断开"){callbacks.disconnect(id)}},
        reconnectSession={id->confirmDraftChange(id,"丢弃修改并重新连接？","丢弃并重新连接"){callbacks.reconnectSession(id)}},
    )
    val bindings=devices.mapNotNull{row->row.boundAccountId?.let{it to row.name}}.groupBy({it.first},{it.second})
    DesktopTheme(preferences){
        val dark=MaterialTheme.colorScheme.surface.luminance()<.5f
        Surface(Modifier.fillMaxSize().onPreviewKeyEvent{event->
            val target=if(page==Page.Run)keys[selected?.id] else null
            if(target==null||event.type!=KeyEventType.KeyDown)false else when{
                event.key==Key.F5->{target.start();true}
                event.key==Key.F6->{target.pause();true}
                event.key==Key.F7->{target.stop();true}
                event.isCtrlPressed&&event.key==Key.F->{target.search();true}
                else->false
            }
        }.focusRequester(shellFocus).focusable(),color=MaterialTheme.colorScheme.background){
            Row{
                Surface(Modifier.width(76.dp).fillMaxHeight(),color=LocalDesktopColors.current.rail){
                    Column(Modifier.padding(horizontal=6.dp,vertical=16.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(6.dp)){
                        Surface(shape=DesktopCorners.md,color=MaterialTheme.colorScheme.primary){Text("黎",Modifier.padding(10.dp),color=Color.White,fontWeight=FontWeight.Bold)}
                        Spacer(Modifier.height(8.dp))
                        Page.entries.forEach{destination->Surface(onClick={page=destination},shape=DesktopCorners.md,color=if(page==destination)MaterialTheme.colorScheme.primaryContainer else Color.Transparent,modifier=Modifier.fillMaxWidth()){
                            Column(Modifier.padding(vertical=9.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(4.dp)){
                                Symbol(destination.icon,tint=if(page==destination)Accent else Muted)
                                Text(destination.label,color=if(page==destination)Accent else Muted,style=MaterialTheme.typography.bodySmall)
                            }
                        }}
                        Spacer(Modifier.weight(1f));Text(AppVersion.name.replace("+","\n"),style=MaterialTheme.typography.labelSmall,color=Muted)
                    }
                }
                VerticalDivider(color=Line)
                Column(Modifier.weight(1f)){
                    Row(Modifier.fillMaxWidth().heightIn(min=52.dp).padding(horizontal=16.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)){
                        Text("黎明界助手",Modifier.weight(1f),style=MaterialTheme.typography.titleMedium)
                        Text("${devices.count{it.busy}} 台运行中",color=Muted,style=MaterialTheme.typography.bodySmall)
                        OutlinedButton(onClick=callbacks.stopAll,shape=MaterialTheme.shapes.small){Text("停止全部")}
                        IconButton(onClick={actions.run{global.ux.preferences.update{it.copy(theme=if(dark)DesktopThemeMode.LIGHT else DesktopThemeMode.DARK)}}}){Symbol(if(dark)"light_mode" else "dark_mode","切换主题")}
                    }
                    HorizontalDivider(color=Line)
                    (error ?: globalError ?: globalLocalError ?: actions.message)?.let{Message(it)}
                    BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()){
                        val configuration=Configuration(maxWidth.value.toInt(),maxHeight.value.toInt())
                        CompositionLocalProvider(LocalContext provides global.context,LocalDesktopWindow provides hostWindow,LocalConfiguration provides configuration){
                            // Keep every run composition alive when another device or a global page is visible.
                            Column(retainedViewport(page==Page.Run)){
                                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal=12.dp,vertical=8.dp),horizontalArrangement=Arrangement.spacedBy(6.dp),verticalAlignment=Alignment.CenterVertically){
                                    sessions.forEach{tab->
                                        val raw by tab.session.snapshots.collectAsState()
                                        val connection by tab.session.connection.collectAsState()
                                        val state=ControlUiSnapshot(raw).console
                                        val account=accounts.accounts.firstOrNull{it.id==tab.boundAccountId}?.alias ?: state.task.selectedAccount?.alias ?: "未绑定账号"
                                        val status=when{connection==WorkerConnectionStatus.STARTING->"连接中";connection==WorkerConnectionStatus.LOST->"控制失联";connection==WorkerConnectionStatus.CLOSED->"已关闭";raw["captcha"] is JsonObject->"需验证码";state.stopping->"停止中";state.overlay?.paused==true->"已暂停";state.busy->"运行中";else->"空闲"}
                                        Surface(onClick={callbacks.selectSession(tab.id)},shape=DesktopCorners.sm,color=if(selected?.id==tab.id)MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,border=BorderStroke(1.dp,if(selected?.id==tab.id)Accent else Line)){
                                            Row(Modifier.widthIn(min=170.dp,max=280.dp).padding(start=10.dp),verticalAlignment=Alignment.CenterVertically){
                                                Column(Modifier.weight(1f).padding(vertical=6.dp)){
                                                    Text("${tab.name} · $account"+(if(dirty[tab.id]==true)" •" else ""),maxLines=1,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.titleSmall)
                                                    Text(status,color=Muted,style=MaterialTheme.typography.bodySmall)
                                                }
                                                IconButton(onClick={closeRun(tab)},modifier=Modifier.size(30.dp)){Symbol("close","关闭 ${tab.name}",modifier=Modifier.size(15.dp))}
                                            }
                                        }
                                    }
                                    TextButton(onClick={page=Page.Devices}){Text("＋ 连接设备")}
                                }
                                Surface(Modifier.weight(1f).fillMaxWidth().padding(start=12.dp,end=12.dp,bottom=12.dp),shape=DesktopCorners.lg,border=BorderStroke(1.dp,Line)){
                                    if(sessions.isEmpty())Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center){Column(horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(10.dp)){
                                        Text("连接设备后打开运行页",style=MaterialTheme.typography.titleLarge)
                                        Text("账号和策略由所有设备共用，在设备页选择绑定账号。",color=Muted)
                                        Button(onClick={page=Page.Devices}){Text("前往设备管理")}
                                    }} else Box(Modifier.fillMaxSize()){
                                        sessions.forEach{tab->key(tab.id){
                                            val visible=page==Page.Run&&selected?.id==tab.id
                                            val view=checkNotNull(views[tab.id]).second
                                            val connection by tab.session.connection.collectAsState()
                                            LaunchedEffect(visible,tab.session){tab.session.setSelected(visible)}
                                            DisposableEffect(tab.id){onDispose{keys.remove(tab.id)}}
                                            CompositionLocalProvider(LocalControlVisible provides visible,LocalControlName provides tab.name,LocalControlDirty provides {part,changed->report(tab.id,part,changed)},LocalControlKeyActions provides {keys[tab.id]=it}){
                                                Column(retainedViewport(selected?.id==tab.id).testTag("run-${tab.id}")){
                                                    if(connection==WorkerConnectionStatus.LOST||connection==WorkerConnectionStatus.CLOSED)Row(Modifier.padding(horizontal=16.dp),verticalAlignment=Alignment.CenterVertically){
                                                        Text("此设备的控制进程已断开",Modifier.weight(1f),color=Muted)
                                                        TextButton(onClick={confirmDraftChange(tab.id,"丢弃修改并重新连接？","丢弃并重新连接"){callbacks.reconnectSession(tab.id)}}){Text("重新连接控制进程")}
                                                    }
                                                    Box(Modifier.weight(1f)){DesktopApp(view,hostWindow,runOnly=true,appearance=preferences,onNavigate={page=it},sharedSettings=global)}
                                                }
                                            }
                                        }}
                                    }
                                }
                            }
                            visited.forEach{target->key(target){
                                val visible=page==target
                                val navigation=remember{NavigationGuard()}
                                CompositionLocalProvider(LocalControlVisible provides visible,LocalControlName provides "全局管理",LocalTaskBusy provides (global.busy||actions.working),LocalNavigationGuard provides navigation,LocalControlDirty provides {part,changed->report(SHARED_UI_DIRTY_ID,part,changed)}){
                                    Box(retainedViewport(visible)){
                                        when(target){
                                            Page.Accounts->AccountsPage(global,bindings,shared=true)
                                            Page.Strategies->StrategiesPage(global)
                                            Page.Devices->SharedDeviceManager(global,devices,deviceCallbacks,::openRun,views.mapValues{it.value.second})
                                            Page.Records->RecordsPage(global)
                                            Page.Settings->SettingsPage(global,{wizard=true},{page=Page.Devices},shared=true)
                                            Page.Run->Unit
                                        }
                                    }
                                }
                            }}
                            if(globalWire["settings"] is JsonObject&&globalConnection==WorkerConnectionStatus.CONNECTED&&(!settings.wizardCompleted||wizard)){
                                CompositionLocalProvider(LocalControlName provides "使用向导",LocalControlDirty provides {part,changed->report(SHARED_UI_DIRTY_ID,"wizard-$part",changed)}){
                                    SetupWizard(global,shared=true,deviceContent={SharedDeviceManager(global,devices,deviceCallbacks,::openRun)}){wizard=false;page=Page.Run}
                                }
                            }
                        }
                    }
                }
            }
            pending?.let{next->AlertDialog(onDismissRequest={pending=null},title={Text(next.title)},text={Text(next.message)},confirmButton={TextButton(onClick={pending=null;next.action()}){Text(next.label)}},dismissButton={TextButton(onClick={pending=null}){Text("取消")}})}
        }
    }
}

private fun retainedViewport(visible:Boolean)=Modifier.fillMaxSize().offset(x=if(visible)0.dp else 20000.dp).alpha(if(visible)1f else 0f)
