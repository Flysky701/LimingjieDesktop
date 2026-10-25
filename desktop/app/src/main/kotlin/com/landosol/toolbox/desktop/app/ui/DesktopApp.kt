package com.landosol.toolbox.desktop.app.ui

import com.landosol.toolbox.desktop.app.control.*

import android.content.res.Configuration
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.landosol.toolbox.AppVersion
import com.landosol.toolbox.desktop.app.*
import com.landosol.toolbox.desktop.app.adapters.storage.*
import com.landosol.toolbox.desktop.app.adapters.device.inProgress
import com.landosol.toolbox.desktop.app.ui.theme.*
import com.landosol.toolbox.desktop.device.DeviceConnectionState
import com.landosol.toolbox.desktop.ocr.OcrEngineStatus
import com.landosol.toolbox.labyrinth.batch.*
import com.landosol.toolbox.labyrinth.rerollSettings
import com.landosol.toolbox.labyrinth.LabyrinthRerollOptions
import com.landosol.toolbox.ui.labyrinth.LabyrinthScreen
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection

internal val Accent:Color @Composable get()=LocalDesktopColors.current.link
internal val Muted:Color @Composable get()=MaterialTheme.colorScheme.onSurfaceVariant
internal val Background:Color @Composable get()=MaterialTheme.colorScheme.background
internal val Line:Color @Composable get()=MaterialTheme.colorScheme.outlineVariant
internal val LocalTaskBusy=staticCompositionLocalOf { false }
internal val LocalDesktopWindow=staticCompositionLocalOf<java.awt.Window?> { null }
internal class NavigationGuard { var intercept:((()->Unit)->Unit)?=null;fun request(action:()->Unit){intercept?.invoke(action) ?: action()} }
internal val LocalNavigationGuard=staticCompositionLocalOf { NavigationGuard() }
internal enum class Page(val label:String,val icon:String) { Run("运行","play_arrow"),Accounts("账号","person"),Strategies("策略","tune"),Devices("设备","smartphone"),Records("记录","bar_chart"),Settings("设置","settings") }
@Composable internal fun Symbol(name:String,label:String?=null,tint:Color=LocalContentColor.current,modifier:Modifier=Modifier.size(20.dp)) { Icon(painterResource("desktop/icons/$name.svg"),label,modifier,tint=tint) }

@Composable internal fun DesktopApp(
    graph:ControlSessionUi,hostWindow:java.awt.Window?=null,navigationGuard:NavigationGuard?=null,
    runOnly:Boolean=false,appearance:DesktopPreferences?=null,onNavigate:((Page)->Unit)?=null,
) {
    val preferences by graph.ux.preferences.state.collectAsState()
    DesktopTheme(appearance ?: preferences) {
        val state by graph.ux.console.collectAsState()
        val resolution by graph.resolutionGuard.status.collectAsState()
        val connectionActivity by graph.deviceConnection.activity.collectAsState()
        val connectionMessage by graph.deviceConnection.message.collectAsState()
        val connectionPending = connectionActivity != null || state.device.inProgress
        val connectionLabel = if (state.device.inProgress) deviceLabel(state.device, resolution.dimensionsLabel) else connectionActivity
            ?: connectionMessage?.takeIf { state.device !is DeviceConnectionState.Ready }?.let { "连接提示 · $it" }
            ?: deviceLabel(state.device, resolution.dimensionsLabel)
        val configuration by graph.labyrinthController.uiState.collectAsState()
        val credentials by graph.ux.credentials.collectAsState()
        val settings by graph.settings.state.collectAsState()
        val accounts by graph.accounts.uiState.collectAsState()
        val ocr by graph.ocr.status.collectAsState()
        val isDark=MaterialTheme.colorScheme.surface.luminance()<.5f
        val actions=rememberActions(graph)
        val cancelConnection=rememberActions(graph)
        val navigation=navigationGuard ?: remember{NavigationGuard()}
        val connection by graph.port.connection.collectAsState()
        val portError by graph.error.collectAsState()
        val transportError by graph.port.error.collectAsState()
        val rawSnapshot by graph.port.snapshots.collectAsState()
        val hasSnapshot=rawSnapshot["console"] is JsonObject
        val visible=LocalControlVisible.current
        DisposableEffect(graph,navigation){graph.ux.confirmNavigation=navigation::request;onDispose{graph.ux.confirmNavigation=null}}
        var searchRequested by remember{mutableStateOf(false)}
        var page by remember { mutableStateOf(Page.entries.firstOrNull { it.name==preferences.page } ?: Page.Run) }
        var selected by remember { mutableStateOf(DesktopTask.entries.firstOrNull{it.name==preferences.selectedTask} ?: DesktopTask.BATCH) }
        var fullConfiguration by remember { mutableStateOf(false) }
        var wizard by remember { mutableStateOf(false) }
        var confirmRetreat by remember { mutableStateOf(false) }
        var deviceMenu by remember { mutableStateOf(false) }
        var accountMenu by remember { mutableStateOf(false) }
        var hydrated by remember{mutableStateOf(false)}
        LaunchedEffect(hasSnapshot){if(hasSnapshot&&!hydrated){
            val p=ControlUiSnapshot(rawSnapshot).model("preferences",DesktopPreferences())
            page=if(runOnly)Page.Run else Page.entries.firstOrNull{it.name==p.page} ?: Page.Run
            selected=DesktopTask.entries.firstOrNull{it.name==p.selectedTask} ?: DesktopTask.BATCH
            hydrated=true
        }}
        val setup=remember(configuration.selectedAccount?.id){RunSetupDraft(configuration,preferences.goals)}
        ReportControlDirty("setup",setup.dirty)
        ReportControlDirty("workflow",fullConfiguration)
        var standaloneGuild by remember(configuration.selectedAccount?.id){mutableStateOf(LabyrinthRerollOptions.DEFAULT_GUILD_ID)}
        var setupMessage by remember{mutableStateOf<String?>(null)}
        var pendingSetupExit by remember{mutableStateOf<(() -> Unit)?>(null)}
        LaunchedEffect(configuration.rerollSettings(),preferences.goals){setup.sync(configuration,preferences.goals)}
        fun cancelSetup(){setup.reset(graph.labyrinthController.uiState.value,graph.ux.preferences.state.value.goals);setupMessage=null}
        fun saveSetup(){
            if(graph.busy||actions.working)return
            setupMessage=null
            val current=graph.labyrinthController.uiState.value
            setup.validationError(current)?.let{setupMessage=it;return}
            val settingsToSave=setup.settingsToSave(current)
            val goalsToSave=setup.savedGoals()
            val accountId=current.selectedAccount?.id
            actions.run {
                if(graph.busy||graph.labyrinthController.uiState.value.selectedAccount?.id!=accountId)return@run
                graph.request(ControlOps.SAVE_SETUP,controlArgs("settings" to ControlModelCodec.encode(settingsToSave),"goals" to ControlModelCodec.encode(goalsToSave)))
                graph.awaitSnapshot{snapshot->snapshot.console.task.selectedAccount?.id==accountId&&snapshot.console.task.rerollSettings()==settingsToSave&&snapshot.model("preferences",DesktopPreferences()).goals==goalsToSave}
                setup.reset(graph.labyrinthController.uiState.value,graph.ux.preferences.state.value.goals)
                setupMessage=null
            }
        }
        if(page==Page.Run&&!fullConfiguration) {
            SideEffect{navigation.intercept={next->if(setup.dirty)pendingSetupExit=next else next()}}
            DisposableEffect(navigation){onDispose{navigation.intercept=null}}
        }
        fun selectTask(task:DesktopTask){
            if(task==selected||graph.busy||actions.working)return
            navigation.request{selected=task;setupMessage=null;actions.run{withContext(Dispatchers.IO){graph.ux.preferences.update{it.copy(selectedTask=task.name)}}}}
        }
        fun configure(){navigation.request{fullConfiguration=true}}
        val searchFocus=remember { FocusRequester() }
        fun navigate(target:Page) {
            if(runOnly){onNavigate?.invoke(target);return}
            if(target!=page)navigation.request {page=target;actions.run { withContext(Dispatchers.IO){graph.ux.preferences.update { it.copy(page=target.name) }} }}
        }
        LaunchedEffect(page,searchRequested){if(page==Page.Run&&searchRequested){withContext(Dispatchers.IO){graph.ux.preferences.update{it.copy(rightTab="logs")}};delay(50);runCatching{searchFocus.requestFocus()};searchRequested=false}}
        val goals=preferences.goals.map { LabyrinthBatchGoal(it.guildId,it.count,if(it.clears) LabyrinthBatchGoalMode.CLEARS else LabyrinthBatchGoalMode.ATTEMPTS) }
        val missing=if(connection!=WorkerConnectionStatus.CONNECTED||!hasSnapshot)"控制连接未就绪" else runSetupBlock(setup.dirty,fullConfiguration) ?: startMissing(selected,state,ocr is OcrEngineStatus.Ready,goals.isNotEmpty(),credentials[configuration.selectedAccount?.id],resolution)
        fun launchSelected() {
            if(setup.dirty || fullConfiguration || actions.working || graph.busy) return
            val current=graph.labyrinthController.uiState.value
            val currentGoals=graph.ux.preferences.state.value.goals.map{LabyrinthBatchGoal(it.guildId,it.count,if(it.clears)LabyrinthBatchGoalMode.CLEARS else LabyrinthBatchGoalMode.ATTEMPTS)}
            startMissing(selected,state.copy(task=current),graph.ocr.status.value is OcrEngineStatus.Ready,currentGoals.isNotEmpty(),graph.ux.credentials.value[current.selectedAccount?.id],graph.resolutionGuard.status.value)?.let {
                graph.labyrinthController.reportMessage(it)
                return
            }
            graph.ux.expect(selected)
            actions.run {
                if(selected in listOf(DesktopTask.BATCH,DesktopTask.RECOGNIZE,DesktopTask.ENTRY)) graph.ux.refreshForeground()
                when(selected) {
                    DesktopTask.BATCH -> if(!graph.startLabyrinthAutoRun(current.selectedAccount?.id,currentGoals)) graph.log.add("启动检查未通过，请核对账号、设备、模型和当前任务。",true)
                    DesktopTask.REROLL -> graph.labyrinthController.start(retreatConfirmed=true,guildIdOverride=standaloneGuild)
                    DesktopTask.READ -> graph.labyrinthController.checkStatus()
                    DesktopTask.RECOGNIZE -> graph.startRecognition(true)
                    DesktopTask.ENTRY -> graph.startRecognition(false)
                }
            }
        }
        fun start() { if(setup.dirty||fullConfiguration||actions.working||graph.busy)return;if(selected==DesktopTask.REROLL && graph.labyrinthController.uiState.value.retireExisting && missing==null) confirmRetreat=true else launchSelected() }
        fun stop() { if(graph.busy && !state.stopping) actions.run { graph.stopAll().join() } }
        fun pause() { if(state.overlay!=null && !state.stopping) graph.pauseTask() }
        val registerKeys=LocalControlKeyActions.current
        SideEffect{registerKeys(ControlKeyActions(::start,::pause,::stop){navigation.request{page=Page.Run;searchRequested=true}})}
        Surface(Modifier.fillMaxSize().onPreviewKeyEvent { event ->
            if(!visible||event.type!=KeyEventType.KeyDown) false else when {
                event.key==Key.F5 -> { start();true };event.key==Key.F6 -> { pause();true };event.key==Key.F7 -> { stop();true }
                event.key==Key.Escape && fullConfiguration -> { fullConfiguration=false;true }
                event.isCtrlPressed && event.key==Key.F -> { navigation.request{page=Page.Run;searchRequested=true};true };else->false
            }
        },color=Background) {
            Row {
                if(!runOnly)Surface(Modifier.width(76.dp).fillMaxHeight(),color=LocalDesktopColors.current.rail) {
                    Column(Modifier.padding(horizontal=6.dp,vertical=16.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(6.dp)) {
                        Surface(shape=DesktopCorners.md,color=MaterialTheme.colorScheme.primary) { Text("黎",Modifier.padding(10.dp),color=Color.White,fontWeight=FontWeight.Bold) }
                        Spacer(Modifier.height(8.dp))
                        Page.entries.forEach { destination -> Surface(onClick={navigate(destination)},shape=DesktopCorners.md,
                            color=if(page==destination) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,modifier=Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(vertical=9.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(4.dp)) {
                                Symbol(destination.icon,tint=if(page==destination) Accent else Muted)
                                Text(destination.label,color=if(page==destination) Accent else Muted,style=MaterialTheme.typography.bodySmall)
                            }
                        } }
                        Spacer(Modifier.weight(1f));Text("${AppVersion.name.replace("+","\n")}\n独立实例",style=MaterialTheme.typography.labelSmall,color=Muted)
                    }
                }
                if(!runOnly)VerticalDivider(color=Line)
                Column(Modifier.weight(1f)) {
                    Surface(color=MaterialTheme.colorScheme.surface) {
                        Row(Modifier.fillMaxWidth().heightIn(min=56.dp).padding(horizontal=16.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                            if(runOnly) {
                                Text(connectionLabel,Modifier.weight(1f),maxLines=1,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.bodySmall)
                                Text("绑定账号：${state.task.selectedAccount?.alias ?: "未绑定"}",style=MaterialTheme.typography.bodySmall)
                                TextButton(onClick={navigate(Page.Devices)}){Text("设备管理")}
                                StatusBadge(if(state.stopping)"停止中" else if(state.overlay?.paused==true)"已暂停" else if(state.busy)"运行中" else "空闲")
                            } else {
                            Box { OutlinedButton(shape=MaterialTheme.shapes.small,onClick={deviceMenu=true},contentPadding=PaddingValues(horizontal=10.dp,vertical=6.dp)) {
                                Symbol("smartphone",tint=if(state.device is DeviceConnectionState.Ready) LocalDesktopColors.current.ok else Muted)
                                Spacer(Modifier.width(7.dp));Text(connectionLabel,maxLines=1,overflow=TextOverflow.Ellipsis,modifier=Modifier.widthIn(max=360.dp),style=MaterialTheme.typography.bodySmall)
                            };DropdownMenu(deviceMenu&&visible,{deviceMenu=false}) {
                                DropdownMenuItem(text={Text("设备详情")},onClick={deviceMenu=false;navigate(Page.Devices)})
                                connectionMessage?.let { message -> DropdownMenuItem(text={Text(message)},onClick={deviceMenu=false;navigate(Page.Devices)}) }
                                DropdownMenuItem(text={Text(if(state.busy) "任务运行中，停止后可重连" else "重新连接")},enabled=!state.busy && settings.lastDevice.isNotBlank() && !connectionPending,onClick={deviceMenu=false;actions.run { graph.connectDevice(settings.lastDevice, reconnect = true) }})
                                DropdownMenuItem(text={Text(if(connectionPending) "取消连接操作" else "断开")},enabled=!state.busy && !cancelConnection.working && (connectionPending || state.device is DeviceConnectionState.Ready),onClick={deviceMenu=false;cancelConnection.run{if(connectionPending) graph.deviceConnection.cancel() else graph.disconnectDevice()}})
                            } }
                            Box { OutlinedButton(shape=MaterialTheme.shapes.small,onClick={accountMenu=true},enabled=!state.busy,contentPadding=PaddingValues(horizontal=10.dp,vertical=6.dp)) {
                                Symbol("person");Spacer(Modifier.width(6.dp));Text(state.task.selectedAccount?.alias ?: "选择账号",style=MaterialTheme.typography.bodySmall)
                            };DropdownMenu(accountMenu&&visible,{accountMenu=false}) {
                                accounts.accounts.forEach { account -> DropdownMenuItem(text={Text("${if(account.isSelected) "✓ " else ""}${account.alias} · ${account.serverName}")},onClick={accountMenu=false;if(!graph.busy)navigation.request{cancelSetup();graph.accounts.selectAccount(account.id)}}) }
                                DropdownMenuItem(text={Text("管理账号")},onClick={accountMenu=false;navigate(Page.Accounts)})
                            } }
                            if(state.busy) Text("运行中不可切换账号",color=Muted,style=MaterialTheme.typography.bodySmall)
                            Spacer(Modifier.weight(1f));StatusBadge(if(state.stopping) "停止中" else if(state.task.captcha!=null) "等待验证码" else if(state.overlay?.paused==true) "已暂停" else if(state.busy) "运行中" else "空闲")
                            IconButton(onClick={actions.run { withContext(Dispatchers.IO){graph.ux.preferences.update { it.copy(theme=if(isDark) DesktopThemeMode.LIGHT else DesktopThemeMode.DARK) }} }}) { Symbol(if(isDark) "light_mode" else "dark_mode","切换主题") }
                            }
                        }
                    }
                    HorizontalDivider(color=Line)
                    if(transportError!=null)Message(transportError)
                    BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                        CompositionLocalProvider(LocalContext provides graph.context,LocalTaskBusy provides (graph.busy||actions.working),LocalDesktopWindow provides hostWindow,LocalNavigationGuard provides navigation,
                            LocalConfiguration provides Configuration(maxWidth.value.toInt(),maxHeight.value.toInt())) {
                            when(if(runOnly)Page.Run else page) {
                                Page.Run -> RunConsole(graph,state,selected,::selectTask,::configure,{navigate(it)},searchFocus,
                                    setup,configuration,standaloneGuild,{if(!graph.busy)standaloneGuild=it},setupMessage ?: actions.message)
                                Page.Accounts -> AccountsPage(graph)
                                Page.Strategies -> StrategiesPage(graph)
                                Page.Devices -> DevicesPage(graph)
                                Page.Records -> RecordsPage(graph)
                                Page.Settings -> SettingsPage(graph,{wizard=true},{navigate(Page.Devices)})
                            }
                            if(fullConfiguration) WorkflowDialog({fullConfiguration=false}) {Surface(Modifier.fillMaxSize(),color=MaterialTheme.colorScheme.surface) {
                                Column { Row(Modifier.fillMaxWidth().padding(horizontal=16.dp),verticalAlignment=Alignment.CenterVertically) {
                                    Text("完整任务配置",Modifier.weight(1f),style=MaterialTheme.typography.titleMedium)
                                    TextButton(onClick={fullConfiguration=false}) { Symbol("arrow_back");Text("返回运行台 · Esc") }
                                };Box(Modifier.weight(1f)){FullWorkflow(graph,state,{fullConfiguration=false;navigate(Page.Strategies)},{fullConfiguration=false})} }
                            }
                            }
                            if(!runOnly&&hasSnapshot&&connection==WorkerConnectionStatus.CONNECTED&&(!settings.wizardCompleted || wizard)) SetupWizard(graph){wizard=false;navigate(Page.Run)}
                            if(visible) RemoteCaptchaDialog(graph)
                            if(confirmRetreat&&visible) AlertDialog(onDismissRequest={confirmRetreat=false},title={Text("确认彻底撤退")},text={Text("允许彻底撤退会放弃当前开局。请确认没有需要保留的进度。")},
                                confirmButton={TextButton(onClick={confirmRetreat=false;launchSelected()}){Text("确认并开始")}},dismissButton={TextButton(onClick={confirmRetreat=false}){Text("取消")}})
                            if(pendingSetupExit!=null&&visible) AlertDialog(onDismissRequest={pendingSetupExit=null},title={Text("放弃未保存的常用配置？")},
                                text={Text("更改尚未保存。你可以留在运行台保存，或放弃更改后继续。")},
                                confirmButton={TextButton(onClick={val next=pendingSetupExit;pendingSetupExit=null;cancelSetup();next?.invoke()}){Text("放弃更改并继续")}},
                                dismissButton={TextButton(onClick={pendingSetupExit=null}){Text("留在此页")}})
                        }
                    }
                    HorizontalDivider(color=Line)
                    Surface(color=MaterialTheme.colorScheme.surface) { Row(Modifier.fillMaxWidth().heightIn(min=68.dp).padding(horizontal=16.dp,vertical=8.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        Column(Modifier.weight(1f)) { Text(if(state.busy) state.overlay?.status ?: state.task.progress ?: "任务进行中" else selected.title,style=MaterialTheme.typography.titleSmall,maxLines=1,overflow=TextOverflow.Ellipsis)
                            Text(actions.message ?: transportError ?: portError ?: setupMessage ?: if(setup.dirty)"请先保存或取消更改" else if(state.busy) state.entry.message ?: state.task.message ?: "任务正在进行" else selected.mode+" · 使用已保存配置",color=Muted,style=MaterialTheme.typography.bodySmall,maxLines=1,overflow=TextOverflow.Ellipsis) }
                        if(missing!=null && !state.busy && !setup.dirty) TextButton(onClick={when {missing.contains("读取")->selectTask(DesktopTask.READ);fullConfiguration||missing.contains("目标")||missing.contains("配置")->{page=Page.Run;fullConfiguration=false};missing.contains("账号")->navigate(Page.Accounts);missing.contains("模型")->navigate(Page.Settings);else->navigate(Page.Devices)}}) { Text(missing,style=MaterialTheme.typography.bodySmall) }
                        if(setup.dirty&&!fullConfiguration) {
                            TextButton(onClick=::cancelSetup,enabled=!state.busy&&!actions.working){Text("取消更改")}
                            Button(onClick=::saveSetup,enabled=!state.busy&&!actions.working,shape=MaterialTheme.shapes.small){Text(if(actions.working)"保存中…" else "保存更改")}
                        }
                        if(state.overlay!=null) OutlinedButton(shape=MaterialTheme.shapes.small,onClick=::pause,enabled=!state.stopping&&!actions.working) { Text(if(state.overlay?.paused==true) "继续 F6" else "暂停 F6") }
                        OutlinedButton(shape=MaterialTheme.shapes.small,onClick=::stop,enabled=state.busy&&!state.stopping&&!actions.working) { Text(if(state.stopping) "停止中…" else "停止 F7") }
                        StartControl(missing,actions.working,::start)
                    } }
                }
            }
        }
    }
}

@Composable private fun FullWorkflow(graph:ControlSessionUi,s:RunConsoleState,strategies:()->Unit,close:()->Unit) {
    val actions=rememberActions(graph)
    LabyrinthScreen(state=s.task.copy(captcha=null,isWorking=s.task.isWorking||s.busy),entryRecognitionState=s.entry,batchCheckpoint=s.batch,batchHaltReason=s.halt,
        onSaveSettings=graph.labyrinthController::saveSettings,
        onCheckStatus={if(!graph.busy){graph.ux.expect(DesktopTask.READ);graph.labyrinthController.checkStatus()}},
        onStart={guild->if(!graph.busy){graph.ux.expect(DesktopTask.REROLL);graph.labyrinthController.start(retreatConfirmed=true,guildIdOverride=guild);close()}},
        onStop=graph.labyrinthController::stop,onDismissMessage=graph.labyrinthController::dismissMessage,
        onStartEntryRecognition={graph.ux.expect(DesktopTask.RECOGNIZE);graph.startRecognition(true)},onStartEntryAutomation={graph.ux.expect(DesktopTask.ENTRY);graph.startRecognition(false)},
        onStopEntryRecognition={actions.run{graph.stopAll().join()}},
        onStartAutoRun={goals-> actions.run { withContext(Dispatchers.IO){graph.ux.preferences.update { it.copy(goals=goals.map { g->DesktopGoal(g.guildId,g.targetCount,g.mode==LabyrinthBatchGoalMode.CLEARS) }) }}
            if(graph.startLabyrinthAutoRun(s.task.selectedAccount?.id,goals)) close() else graph.labyrinthController.reportMessage("自动执行未启动，请检查账号、设备、触控和 OCR，并停止其他任务后重试") }},
        onStopAutoRun=graph::stopLabyrinthAutoRun,onCaptchaSolved={graph.labyrinthController.reportMessage("请使用此标签的验证入口")},onCaptchaError=graph.labyrinthController::reportMessage,onCancelCaptcha=graph.labyrinthController::cancelCaptcha,onOpenStrategies=strategies)
}

internal class UiActions(private val scope:CoroutineScope,private val graph:ControlSessionUi) {
    var working by mutableStateOf(false);private set
    var message by mutableStateOf<String?>(null)
    fun run(block:suspend()->Unit) { if(working)return;working=true;scope.launch { message=null;try{block()}catch(e:CancellationException){throw e}catch(e:Exception){message=e.message?:"操作失败，请重试";graph.log.add(message!!,true)}finally{working=false} } }
}
@Composable internal fun rememberActions(graph:ControlSessionUi):UiActions {val scope=rememberCoroutineScope();return remember(graph,scope){UiActions(scope,graph)}}
@Composable internal fun PageBody(title:String,subtitle:String,content:@Composable ColumnScope.()->Unit) {Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),verticalArrangement=Arrangement.spacedBy(16.dp)){Column{Text(title,style=MaterialTheme.typography.titleLarge);Text(subtitle,color=Muted,style=MaterialTheme.typography.bodySmall)};content()}}
@Composable internal fun Panel(title:String,modifier:Modifier=Modifier,content:@Composable ColumnScope.()->Unit) {Surface(modifier.fillMaxWidth(),color=MaterialTheme.colorScheme.surface,shape=DesktopCorners.lg,border=BorderStroke(1.dp,Line)){Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){Text(title,style=MaterialTheme.typography.titleMedium);content()}}}
@Composable internal fun Detail(label:String,value:String) {Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(12.dp)){Text(label,Modifier.width(125.dp),color=Muted,style=MaterialTheme.typography.bodySmall);Text(value,Modifier.weight(1f),style=MaterialTheme.typography.bodySmall)}}
@Composable internal fun Message(text:String?,onDismiss:(()->Unit)?=null){if(text!=null)Surface(color=MaterialTheme.colorScheme.primaryContainer,shape=DesktopCorners.sm){Row(Modifier.fillMaxWidth().padding(12.dp),verticalAlignment=Alignment.CenterVertically){Text(text,Modifier.weight(1f),color=MaterialTheme.colorScheme.onPrimaryContainer,style=MaterialTheme.typography.bodySmall);if(onDismiss!=null)TextButton(onClick=onDismiss){Text("关闭")}}}}
@Composable internal fun StatusBadge(text:String){Surface(color=MaterialTheme.colorScheme.primaryContainer,shape=RoundedCornerShape(20.dp)){Text(text,Modifier.padding(horizontal=10.dp,vertical=4.dp),color=MaterialTheme.colorScheme.onPrimaryContainer,style=MaterialTheme.typography.bodySmall)}}
internal fun deviceLabel(state:DeviceConnectionState, dimensions:String="画面尺寸待检查"):String=when(state){DeviceConnectionState.Disconnected->"未连接模拟器";is DeviceConnectionState.Ready->"${state.device.label} · ${state.device.serial} · $dimensions";is DeviceConnectionState.Connecting->"正在连接 ${state.serial}";is DeviceConnectionState.Verifying->"正在检查 · ${state.detail}";is DeviceConnectionState.Lost->"连接中断 · ${state.reason}";is DeviceConnectionState.Failed->"连接失败 · ${state.reason}";is DeviceConnectionState.Reconnecting->"重新连接（${state.attempt}）"}
internal fun copyText(text:String)=Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text),null)
@Composable internal fun StartControl(missing:String?,working:Boolean,onClick:()->Unit){Button(shape=MaterialTheme.shapes.small,onClick=onClick,enabled=missing==null&&!working){Text("开始 F5")}}
