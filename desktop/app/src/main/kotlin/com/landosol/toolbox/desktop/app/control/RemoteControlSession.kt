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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.jetbrains.skia.Image

/** Transport stays in WorkerLink; this adapter never owns a local Graph or device. */
class RemoteControlSession(private val link:WorkerLink):ControlSessionPort {
    override val instanceId get()=link.instanceId
    override val workerRunId get()=link.workerRunId
    override val snapshots get()=link.snapshots
    override val connection get()=link.connection
    override val error get()=link.error
    override suspend fun request(op:String,args:kotlinx.serialization.json.JsonObject)=link.request(op,args)
    override fun setSelected(selected:Boolean)=link.setSelected(selected)
    override fun setPreviewVisible(visible:Boolean)=link.setPreviewVisible(visible)
    override suspend fun shutdown()=link.shutdown()
}

/** Each port is one worker incarnation; never share decoded frames across ports. */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun <T> ControlSessionPort.previewFrames(
    decode: suspend (JsonObject) -> T?,
): Flow<T?> = combine(snapshots, connection) { raw, status ->
    if (status == WorkerConnectionStatus.CONNECTED) raw["preview"] as? JsonObject else null
}.distinctUntilChanged().mapLatest { image ->
    // Compare the whole preview (pixels AND timestamp), not unrelated console/log updates.
    // A clear/disconnect also cancels a pending decode before it can publish an old frame.
    image?.let { decode(it) }
}.buffer(0)

private suspend fun decodeControlPreview(image: JsonObject): ImageBitmap? = try {
    withContext(Dispatchers.Default) {
        val payload = (image["jpeg"] ?: image["png"])?.jsonPrimitive?.contentOrNull ?: return@withContext null
        Image.makeFromEncoded(Base64.getDecoder().decode(payload)).toComposeImageBitmap()
    }
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: Exception) {
    System.err.println("Control preview decode failed")
    null
}

/** Each UI field owns its last decoded list; compare JSON before converting any rows. */
internal fun <T> StateFlow<ControlUiSnapshot>.decodedListField(
    scope: CoroutineScope,
    name: String,
    read: (ControlUiSnapshot) -> List<T>,
): StateFlow<List<T>> {
    val initial = value
    var lastSource = initial.document[name]
    var lastList = read(initial)
    return map { snapshot ->
        val source = snapshot.document[name]
        if (source != lastSource) {
            lastList = read(snapshot)
            lastSource = source
        }
        lastList
    }.stateIn(scope, SharingStarted.Eagerly, lastList)
}

/** Local UI projection only. It contains no Graph, business controller, credential store or device I/O.
 * All commands below resolve to the closed ControlOps contract on the captured worker port.
 */
class ControlSessionUi(val port:ControlSessionPort,previous:ControlSessionUi?=null) : AutoCloseable {
    val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main)
    private val localError=MutableStateFlow<String?>(null)
    val error=localError.asStateFlow()
    private val models:StateFlow<ControlUiSnapshot> = port.snapshots.filter{it["console"] is JsonObject}.map(::ControlUiSnapshot).stateIn(scope,SharingStarted.Eagerly,ControlUiSnapshot(previous?.models?.value?.document ?: port.snapshots.value))
    private fun <T> field(name:String,read:(ControlUiSnapshot)->T):StateFlow<T> = models
        .map{it.document[name]}.distinctUntilChanged()
        .map{read(ControlUiSnapshot(JsonObject(mapOf(name to (it ?: JsonNull)))))}
        .stateIn(scope,SharingStarted.Eagerly,read(models.value))
    private inline fun <reified T> listField(name:String):StateFlow<List<T>> =
        models.decodedListField(scope,name){it.model(name,emptyList<T>())}
    val context=object:Context() {
        override val assets=AssetManager()
        override val applicationContext:Context get()=this
        override val filesDir:File get()=error("UI does not own worker files")
        override val cacheDir:File get()=error("UI does not own worker cache")
        override fun getSharedPreferences(name:String,mode:Int):SharedPreferences=error("UI preferences must use the control port")
    }
    val dataDirectory get()=Path.of(models.value.text("dataDirectory") ?: ".")
    val debugUrl get()=models.value.text("debugUrl")
    val gameClientResolution get()=models.value.model("gameClient",GameClientResolution.Unavailable as GameClientResolution)
    val gameClient=field("gameClient"){it.model("gameClient",GameClientResolution.Unavailable as GameClientResolution)}
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
    val taskStopping=field("console"){it.console.stopping}
    val preview:StateFlow<ImageBitmap?> = port.previewFrames(::decodeControlPreview).stateIn(scope,SharingStarted.Eagerly,null)
    val captcha=field("captcha"){it.document["captcha"] as? JsonObject}
    val accountVerified=field("accountVerified"){it.document["accountVerified"]?.jsonPrimitive?.booleanOrNull ?: true}
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

    inner class ResolutionView {val status=field("resolution"){it.model("resolution",VisualResolutionStatus(message=VisualResolutionGuardMessage))}}
    inner class OcrView {val status:StateFlow<OcrEngineStatus> = field("ocr"){it.model("ocr",OcrEngineStatus.NotLoaded as OcrEngineStatus)}}
    inner class OverlayView {val state=field("console"){it.console.overlay}}
    inner class IdentityView {val state=field("identity"){it.model<DeviceIdentitySnapshot?>("identity",null)};fun current():DeviceIdentitySnapshot?=state.value}
    inner class DeviceView {
        val state=field("console"){it.console.device}
        val foreground=field("foreground"){it.text("foreground")}
        val foregroundPackage get()=models.value.text("foreground")
        suspend fun queryForeground():String?=request(ControlOps.FOREGROUND)["value"]?.jsonPrimitive?.contentOrNull
        suspend fun tap(x:Float,y:Float,expectedPackage:String):TouchResult {request(ControlOps.DEVICE_TEST);return TouchResult.Completed}
    }
    inner class ConnectionView {
        val activity=field("connectionActivity"){it.text("connectionActivity")}
        val message=field("connectionMessage"){it.text("connectionMessage")}
        val devices=field("devices"){it.model<List<DeviceDescriptor>>("devices",emptyList())}
        suspend fun discover(force:Boolean=true){request(ControlOps.DEVICE_DISCOVER,buildJsonObject{put("force",force)})}
        suspend fun cancel(){request(ControlOps.DEVICE_CANCEL)}
        suspend fun configure(block:suspend()->Unit){check(!busy){"请先停止任务"};block()}
    }
    inner class SettingsView {
        val state=field("settings"){it.model("settings",DesktopSettings())}
        suspend fun update(change:(DesktopSettings)->DesktopSettings){patch(ControlOps.SETTINGS_PATCH,state.value,change(state.value))}
    }
    inner class StrategyView {
        val state=field("strategy"){it.model("strategy",LabyrinthStrategySettings())}
        suspend fun save(value:LabyrinthStrategySettings){request(ControlOps.STRATEGY_SAVE,controlArgs("value" to ControlModelCodec.encode(value)));awaitSnapshot{it.model("strategy",LabyrinthStrategySettings())==value}}
    }
    inner class TaskView {
        val uiState=field("console"){it.console.task}
        fun saveSettings(value:LabyrinthRerollSettings){fire(ControlOps.SAVE_REROLL,controlArgs("value" to ControlModelCodec.encode(value)))}
        fun checkStatus(){fire(ControlOps.START,buildJsonObject{put("task","READ")})}
        fun start(retreatConfirmed:Boolean=false,guildIdOverride:Int?=null){fire(ControlOps.START,buildJsonObject{put("task","REROLL");put("retreatConfirmed",retreatConfirmed);guildIdOverride?.let{put("guildId",it)}})}
        fun stop(){stopAll()}
        fun dismissMessage(){fire(ControlOps.DISMISS_MESSAGE)}
        fun reportMessage(message:String){localError.value=message}
        fun cancelCaptcha(){captcha.value?.get("ticketId")?.let{fire(ControlOps.CAPTCHA_CANCEL,controlArgs("ticketId" to it))}}
    }
    inner class UxView {
        val console=field("console"){it.console}
        val credentials=field("credentials"){it.model<Map<Long,Boolean>>("credentials",emptyMap())}
        val database=field("database"){it.text("database") ?: "正在读取"}
        val foreground=field("foreground"){it.text("foreground")}
        val catalog=listField<LabyrinthRoleRatingItem>("catalog")
        val history=HistoryView()
        val preferences=PreferencesView()
        var confirmNavigation:((()->Unit)->Unit)?=null
        fun expect(task:com.landosol.toolbox.desktop.app.ui.DesktopTask){}
        suspend fun refreshDatabase(){request(ControlOps.REFRESH,buildJsonObject{put("database",true)})}
        suspend fun refreshForeground(){request(ControlOps.REFRESH,buildJsonObject{put("foreground",true)})}
    }
    inner class HistoryView {
        val records=listField<DesktopRunRecord>("history")
        val revision=field("historyRevision"){it.document["historyRevision"]?.jsonPrimitive?.longOrNull ?: 0L}
        suspend fun load():List<DesktopRunRecord> = ControlModelCodec.decode(request(ControlOps.HISTORY_LOAD).getValue("items"))
    }
    inner class PreferencesView {
        val state=field("preferences"){it.model("preferences",DesktopPreferences())}
        suspend fun update(change:(DesktopPreferences)->DesktopPreferences){patch(ControlOps.PREFS_PATCH,state.value,change(state.value))}
    }
    inner class LogView {
        val lines=listField<UserLogLine>("logs")
        val revision=field("logRevision"){it.document["logRevision"]?.jsonPrimitive?.longOrNull ?: 0L}
        suspend fun load():List<UserLogLine> = ControlModelCodec.decode(request(ControlOps.LOG_LOAD).getValue("items"))
        fun text(lines:List<UserLogLine> = this.lines.value)=lines.joinToString("\n"){"${it.time} [${it.kind.label}] ${it.text}${if(it.repeats>1)" ×${it.repeats}"else ""}"}
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
        fun copyDraftFrom(other:AccountsView){baseline=other.baseline;editor.value=other.editor.value;deletion.value=other.deletion.value;message.value=other.message.value;publish()}
        private val backend=field("accounts"){it.model("accounts",AccountUiState())}
        private fun snapshot():AccountUiState=backend.value.let{s->s.copy(editor=editor.value,deleteCandidate=deletion.value,isWorking=s.isWorking||working.value,message=message.value ?: s.message)}
        private val mutableUiState=MutableStateFlow(snapshot())
        val uiState=mutableUiState.asStateFlow()
        private fun publish(){mutableUiState.value=snapshot()}
        init{scope.launch{combine(backend,editor,deletion,working,message){_,_,_,_,_->Unit}.collect{publish()}}}
        fun openNewAccount(){if(!busy){baseline=AccountEditorState();editor.value=baseline;message.value=null;publish()}}
        fun openEditAccount(id:Long){scope.launch{working.value=true;try{val reply=request(ControlOps.ACCOUNT_EDIT,buildJsonObject{put("id",id)});baseline=ControlModelCodec.decode(reply["editor"] ?: reply);editor.value=baseline;message.value=null}catch(cancelled:CancellationException){throw cancelled}catch(e:Exception){message.value=e.message}finally{working.value=false}}}
        // TextField/IME callbacks must receive their new text in the same UI turn.
        // Async combine/stateIn feedback can reapply an old composing buffer and duplicate input.
        fun updateEditor(change:(AccountEditorState)->AccountEditorState){if(!busy){editor.value=editor.value?.let(change);publish()}}
        fun dismissEditor(){editor.value=null;message.value=null;publish()}
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
    inline fun <reified T> model(name:String,fallback:T):T = document[name]?.takeUnless{it is JsonNull}?.let{
        try { ControlModelCodec.decode<T>(it) }
        catch (cancelled:CancellationException) { throw cancelled }
        catch (_:Exception) { reportDecodeFailure(name); fallback }
    } ?: fallback
    companion object {
        private val failedSections=java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        fun reportDecodeFailure(name:String) {
            if(failedSections.add(name)) System.err.println("Control snapshot decode failed for section: $name")
        }
    }
}
