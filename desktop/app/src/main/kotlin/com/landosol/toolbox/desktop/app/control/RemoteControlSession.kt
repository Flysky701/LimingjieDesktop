package com.landosol.toolbox.desktop.app.control

import android.content.Context
import android.content.SharedPreferences
import android.content.res.AssetManager
import com.landosol.toolbox.account.*
import com.landosol.toolbox.automation.GameClientResolution
import com.landosol.toolbox.desktop.app.*
import com.landosol.toolbox.desktop.app.adapters.device.VisualResolutionStatus
import com.landosol.toolbox.desktop.app.adapters.storage.*
import com.landosol.toolbox.desktop.device.*
import com.landosol.toolbox.desktop.ocr.OcrEngineStatus
import com.landosol.toolbox.desktop.platform.DesktopSettings
import com.landosol.toolbox.desktop.shared.*
import com.landosol.toolbox.desktop.app.ui.RunConsoleState
import com.landosol.toolbox.labyrinth.*
import com.landosol.toolbox.labyrinth.batch.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import java.io.File
import java.nio.file.Path
import java.util.Base64
import javax.imageio.ImageIO

/** Transport stays in WorkerLink; this adapter never owns a local Graph or device. */
class RemoteControlSession(private val link:WorkerLink):ControlSessionPort {
    override val instanceId get()=link.instanceId
    override val workerRunId get()=link.workerRunId
    override val snapshots get()=link.snapshots
    override val connection get()=link.connection
    override val error get()=link.error
    override suspend fun request(op:String,args:kotlinx.serialization.json.JsonObject)=link.request(op,args)
    override fun setSelected(selected:Boolean)=link.setSelected(selected)
    override suspend fun shutdown()=link.shutdown()
}

/** Local UI projection only. It contains no Graph, business controller, credential store or device I/O.
 * All commands below resolve to the closed ControlOps contract on the captured worker port.
 */
class ControlSessionUi(val port:ControlSessionPort,previous:ControlSessionUi?=null) : AutoCloseable {
    val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main)
    private val localError=MutableStateFlow<String?>(null)
    val error=localError.asStateFlow()
    private val models:StateFlow<ControlUiSnapshot> = port.snapshots.filter{it["console"] is JsonObject}.map(::ControlUiSnapshot).stateIn(scope,SharingStarted.Eagerly,ControlUiSnapshot(previous?.models?.value?.document ?: port.snapshots.value))
    private fun <T> field(read:(ControlUiSnapshot)->T):StateFlow<T> = models.map(read).stateIn(scope,SharingStarted.Eagerly,read(models.value))
    val context=object:Context() {
        override val assets=AssetManager()
        override val applicationContext:Context get()=this
        override val filesDir:File get()=error("UI does not own worker files")
        override val cacheDir:File get()=error("UI does not own worker cache")
        override fun getSharedPreferences(name:String,mode:Int):SharedPreferences=error("UI preferences must use the control port")
    }
    val dataDirectory get()=Path.of(models.value.text("dataDirectory") ?: ".")
    val debugUrl get()=models.value.text("debugUrl")
    val crashes get()=models.value.document["crashes"]?.jsonArray?.map{it.jsonPrimitive.content}.orEmpty()
    val gameClientResolution get()=models.value.model("gameClient",GameClientResolution.Unavailable as GameClientResolution)
    val gameClient=field{it.model("gameClient",GameClientResolution.Unavailable as GameClientResolution)}
    val busy get()=port.connection.value!=WorkerConnectionStatus.CONNECTED||models.value.console.busy
    val resolutionGuard=ResolutionView()
    val deviceConnection=ConnectionView()
    val device=DeviceView()
    val identityStore=IdentityView()
    val ux=UxView()
    val settings=SettingsView()
    val labyrinthController=TaskView()
    val labyrinthStrategySettings=StrategyView()
    val accounts=AccountsView()
    init{previous?.let{accounts.copyDraftFrom(it.accounts)}}
    val log=LogView()
    val ocr=OcrView()
    val automationOverlayCoordinator=OverlayView()
    val taskStopping=field{it.console.stopping}
    val preview:StateFlow<CapturePixels?> = port.snapshots.map { raw ->
        (raw["preview"] as? JsonObject)?.let{image->
            val png=image["png"]?.jsonPrimitive?.contentOrNull ?: return@let null
            runCatching{withContext(Dispatchers.Default){
                val bitmap=ImageIO.read(Base64.getDecoder().decode(png).inputStream()) ?: return@withContext null
                CapturePixels(bitmap.width,bitmap.height,bitmap.getRGB(0,0,bitmap.width,bitmap.height,null,0,bitmap.width),image["timestampMillis"]?.jsonPrimitive?.longOrNull ?: 0L)
            }}.getOrNull()
        }
    }.stateIn(scope,SharingStarted.Eagerly,null)
    val captcha=field{it.document["captcha"] as? JsonObject}
    val accountVerified=field{it.document["accountVerified"]?.jsonPrimitive?.booleanOrNull ?: true}
    val accountReminder get()=models.value.text("accountReminder") ?: "助手账号不会切换模拟器中的游戏账号，请人工核对同一账号。"
    suspend fun request(op:String,args:JsonObject=JsonObject(emptyMap())):JsonObject {
        check(port.connection.value==WorkerConnectionStatus.CONNECTED){"控制连接未就绪，请重新打开此标签"}
        localError.value=null
        return port.request(op,args)
    }
    fun fire(op:String,args:JsonObject=JsonObject(emptyMap())):Job=scope.launch {
        try{request(op,args)}catch(cancelled:CancellationException){throw cancelled}catch(failure:Exception){localError.value=failure.message ?: "操作失败"}
    }
    suspend fun awaitSnapshot(predicate:(ControlUiSnapshot)->Boolean) {
        withTimeout(8000){port.snapshots.first{predicate(ControlUiSnapshot(it))}}
    }
    suspend fun loadLabyrinthRoleRatings():List<LabyrinthRoleRatingItem> {
        val reply=request(ControlOps.CATALOG)
        return ControlModelCodec.decode(reply["items"] ?: JsonArray(emptyList()))
    }
    fun startRecognition(dryRun:Boolean)=fire(ControlOps.START,buildJsonObject{put("task",if(dryRun)"RECOGNIZE" else "ENTRY")})
    suspend fun startLabyrinthAutoRun(accountId:Long?,goals:List<LabyrinthBatchGoal>):Boolean {
        val reply=request(ControlOps.START,buildJsonObject{put("task","BATCH");put("goals",ControlModelCodec.encode(goals));accountId?.let{put("accountId",it)}})
        return reply["accepted"]?.jsonPrimitive?.booleanOrNull ?: true
    }
    fun stopAll(reason:String="用户停止任务")=fire(ControlOps.STOP,buildJsonObject{put("reason",reason)})
    fun stopLabyrinthAutoRun(){stopAll()}
    fun pauseTask(){fire(ControlOps.PAUSE)}
    suspend fun connectDevice(serial:String,reconnect:Boolean=false){request(ControlOps.DEVICE_CONNECT,buildJsonObject{put("serial",serial);put("reconnect",reconnect)})}
    suspend fun disconnectDevice(){request(ControlOps.DEVICE_DISCONNECT)}
    suspend fun recheckResolution(){request(ControlOps.REFRESH,buildJsonObject{put("resolution",true)})}
    suspend fun startPreview(){request(ControlOps.PREVIEW)}
    suspend fun checkAdb():AdbLocation=configureAdb(settings.state.value.adbPath)
    suspend fun configureAdb(path:String):AdbLocation {
        val reply=request(ControlOps.ADB_CONFIGURE,buildJsonObject{put("path",path)})
        return ControlModelCodec.decode(reply["location"] ?: reply)
    }
    suspend fun downloadAdb():AdbLocation {
        val reply=request(ControlOps.ADB_DOWNLOAD)
        return ControlModelCodec.decode(reply["location"] ?: reply)
    }
    suspend fun stopCapture(){request(ControlOps.CAPTURE_STOP)}
    suspend fun messageRequest(op:String):String=request(op)["message"]?.jsonPrimitive?.contentOrNull ?: "操作已完成"
    override fun close(){scope.cancel()}

    inner class ResolutionView {val status=field{it.model("resolution",VisualResolutionStatus(message=VisualResolutionGuardMessage))}}
    inner class OcrView {val status:StateFlow<OcrEngineStatus> = field{it.model("ocr",OcrEngineStatus.NotLoaded as OcrEngineStatus)}}
    inner class OverlayView {val state=field{it.console.overlay}}
    inner class IdentityView {val state=field{it.model<DeviceIdentitySnapshot?>("identity",null)};fun current():DeviceIdentitySnapshot?=state.value}
    inner class DeviceView {
        val state=field{it.console.device}
        val foreground=field{it.text("foreground")}
        val foregroundPackage get()=models.value.text("foreground")
        suspend fun queryForeground():String?=request(ControlOps.FOREGROUND)["value"]?.jsonPrimitive?.contentOrNull
        suspend fun tap(x:Float,y:Float,expectedPackage:String):TouchResult {request(ControlOps.DEVICE_TEST);return TouchResult.Completed}
    }
    inner class ConnectionView {
        val activity=field{it.text("connectionActivity")}
        val message=field{it.text("connectionMessage")}
        val devices=field{it.model<List<DeviceDescriptor>>("devices",emptyList())}
        suspend fun discover(force:Boolean=true){request(ControlOps.DEVICE_DISCOVER,buildJsonObject{put("force",force)})}
        suspend fun cancel(){request(ControlOps.DEVICE_CANCEL)}
        suspend fun configure(block:suspend()->Unit){check(!busy){"请先停止任务"};block()}
    }
    inner class SettingsView {
        val state=field{it.model("settings",DesktopSettings())}
        suspend fun update(change:(DesktopSettings)->DesktopSettings){patch(ControlOps.SETTINGS_PATCH,state.value,change(state.value))}
    }
    inner class StrategyView {
        val state=field{it.model("strategy",LabyrinthStrategySettings())}
        suspend fun save(value:LabyrinthStrategySettings){request(ControlOps.STRATEGY_SAVE,controlArgs("value" to ControlModelCodec.encode(value)));awaitSnapshot{it.model("strategy",LabyrinthStrategySettings())==value}}
    }
    inner class TaskView {
        val uiState=field{it.console.task}
        fun saveSettings(value:LabyrinthRerollSettings){fire(ControlOps.SAVE_REROLL,controlArgs("value" to ControlModelCodec.encode(value)))}
        fun checkStatus(){fire(ControlOps.START,buildJsonObject{put("task","READ")})}
        fun start(retreatConfirmed:Boolean=false,guildIdOverride:Int?=null){fire(ControlOps.START,buildJsonObject{put("task","REROLL");put("retreatConfirmed",retreatConfirmed);guildIdOverride?.let{put("guildId",it)}})}
        fun stop(){stopAll()}
        fun dismissMessage(){fire(ControlOps.DISMISS_MESSAGE)}
        fun reportMessage(message:String){localError.value=message}
        fun cancelCaptcha(){captcha.value?.get("ticketId")?.let{fire(ControlOps.CAPTCHA_CANCEL,controlArgs("ticketId" to it))}}
    }
    inner class UxView {
        val console=field{it.console}
        val credentials=field{it.model<Map<Long,Boolean>>("credentials",emptyMap())}
        val database=field{it.text("database") ?: "正在读取"}
        val foreground=field{it.text("foreground")}
        val catalog=field{it.model<List<LabyrinthRoleRatingItem>>("catalog",emptyList())}
        val history=HistoryView()
        val preferences=PreferencesView()
        var confirmNavigation:((()->Unit)->Unit)?=null
        fun expect(task:com.landosol.toolbox.desktop.app.ui.DesktopTask){}
        suspend fun refreshDatabase(){request(ControlOps.REFRESH,buildJsonObject{put("database",true)})}
        suspend fun refreshForeground(){request(ControlOps.REFRESH,buildJsonObject{put("foreground",true)})}
    }
    inner class HistoryView {val records=field{it.model<List<DesktopRunRecord>>("history",emptyList())}}
    inner class PreferencesView {
        val state=field{it.model("preferences",DesktopPreferences())}
        suspend fun update(change:(DesktopPreferences)->DesktopPreferences){patch(ControlOps.PREFS_PATCH,state.value,change(state.value))}
    }
    inner class LogView {
        val lines=field{it.model<List<UserLogLine>>("logs",emptyList())}
        fun text()=lines.value.joinToString("\n"){"${it.time} [${it.kind.label}] ${it.text}${if(it.repeats>1)" ×${it.repeats}"else ""}"}
        fun add(text:String,error:Boolean=false,kind:UserLogKind=if(error)UserLogKind.ERROR else UserLogKind.NORMAL){fire(ControlOps.LOG_ADD,buildJsonObject{put("text",text);put("kind",kind.name)})}
        fun clear(){fire(ControlOps.LOG_CLEAR)}
    }
    inner class AccountsView {
        private val editor=MutableStateFlow<AccountEditorState?>(null)
        private val deletion=MutableStateFlow<AccountListItem?>(null)
        private val working=MutableStateFlow(false)
        private val message=MutableStateFlow<String?>(null)
        private var baseline:AccountEditorState?=null
        val dirty get()=editor.value!=null&&editor.value!=baseline
        fun copyDraftFrom(other:AccountsView){baseline=other.baseline;editor.value=other.editor.value;deletion.value=other.deletion.value;message.value=other.message.value}
        private val backend=field{it.model("accounts",AccountUiState())}
        val uiState=combine(backend,editor,deletion,working,message){s,e,d,w,m->s.copy(editor=e,deleteCandidate=d,isWorking=s.isWorking||w,message=m ?: s.message)}.stateIn(scope,SharingStarted.Eagerly,AccountUiState())
        fun openNewAccount(){if(!busy){baseline=AccountEditorState();editor.value=baseline;message.value=null}}
        fun openEditAccount(id:Long){scope.launch{working.value=true;try{val reply=request(ControlOps.ACCOUNT_EDIT,buildJsonObject{put("id",id)});baseline=ControlModelCodec.decode(reply["editor"] ?: reply);editor.value=baseline;message.value=null}catch(cancelled:CancellationException){throw cancelled}catch(e:Exception){message.value=e.message}finally{working.value=false}}}
        fun updateEditor(change:(AccountEditorState)->AccountEditorState){if(!busy)editor.value=editor.value?.let(change)}
        fun dismissEditor(){editor.value=null;message.value=null}
        fun dismissMessage(){message.value=null}
        fun selectAccount(id:Long){fire(ControlOps.ACCOUNT_SELECT,buildJsonObject{put("id",id)})}
        fun requestDelete(account:AccountListItem){if(!busy)deletion.value=account}
        fun dismissDelete(){deletion.value=null}
        fun saveEditor(){val value=editor.value ?: return;scope.launch{working.value=true;try{request(ControlOps.ACCOUNT_SAVE,controlArgs("editor" to ControlModelCodec.encode(value)));editor.value=null;message.value=null}catch(cancelled:CancellationException){throw cancelled}catch(e:Exception){message.value=e.message}finally{working.value=false}}}
        fun confirmDelete(){val id=deletion.value?.id ?: return;scope.launch{working.value=true;try{request(ControlOps.ACCOUNT_DELETE,buildJsonObject{put("id",id)});deletion.value=null;message.value=null}catch(cancelled:CancellationException){throw cancelled}catch(e:Exception){message.value=e.message}finally{working.value=false}}}
    }
    private suspend fun patch(op:String,before:Any,after:Any){
        val old=ControlModelCodec.encode(before).jsonObject
        val next=ControlModelCodec.encode(after).jsonObject
        request(op,JsonObject(next.filter{(key,value)->key!="_type"&&old[key]!=value}))
    }
    companion object {private const val VisualResolutionGuardMessage="自动执行要求 1920 × 1080 横屏，请检查实际截图尺寸"}
}

class ControlUiSnapshot(val document:JsonObject) {
    val console:RunConsoleState by lazy{model("console",RunConsoleState())}
    fun text(name:String)=document[name]?.jsonPrimitive?.contentOrNull
    inline fun <reified T> model(name:String,fallback:T):T = document[name]?.takeUnless{it is JsonNull}?.let{runCatching{ControlModelCodec.decode<T>(it)}.getOrElse{fallback}} ?: fallback
}
