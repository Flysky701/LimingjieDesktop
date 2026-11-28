package com.landosol.toolbox.desktop.app.control

import com.landosol.toolbox.core.AppAssets
import com.landosol.toolbox.core.logging.AppLogger
import com.landosol.toolbox.account.*
import com.landosol.toolbox.automation.GameClientResolution
import com.landosol.toolbox.desktop.app.*
import com.landosol.toolbox.desktop.app.adapters.device.VisualResolutionStatus
import com.landosol.toolbox.desktop.app.adapters.storage.*
import com.landosol.toolbox.desktop.app.strategy.BattleModelStatus
import com.landosol.toolbox.desktop.device.*
import com.landosol.toolbox.desktop.ocr.OcrEngineStatus
import com.landosol.toolbox.desktop.ocr.OcrEngineTelemetry
import com.landosol.toolbox.desktop.platform.DesktopSettings
import com.landosol.toolbox.desktop.shared.*
import com.landosol.toolbox.desktop.app.ui.RunConsoleState
import com.landosol.toolbox.labyrinth.*
import com.landosol.toolbox.labyrinth.batch.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
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
    AppLogger.w("ControlUI", "Control preview decode failed")
    null
}

/** Each UI field owns its last decoded list; compare JSON before converting any rows. */
internal fun <T> StateFlow<ControlUiSnapshot>.decodedListField(
    scope: CoroutineScope,
    name: String,
    fallbackName: String? = null,
    read: (ControlUiSnapshot) -> List<T>,
): StateFlow<List<T>> {
    fun source(snapshot: ControlUiSnapshot): Any? {
        val raw = snapshot.document[name]
        return if (fallbackName != null && raw !is JsonArray) fallbackName to snapshot.document[fallbackName] else raw
    }

    val initial = value
    var lastSource = source(initial)
    var lastList = read(initial)
    return map { snapshot ->
        val nextSource = source(snapshot)
        if (nextSource != lastSource) {
            lastList = read(snapshot)
            lastSource = nextSource
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
    fun dismissError() { localError.value = null }
    private val models:StateFlow<ControlUiSnapshot> = port.snapshots.filter{it["console"] is JsonObject}.map(::ControlUiSnapshot).stateIn(scope,SharingStarted.Eagerly,ControlUiSnapshot(previous?.models?.value?.document ?: port.snapshots.value))
    private fun <T> field(name:String,read:(ControlUiSnapshot)->T):StateFlow<T> = models
        .map{it.document[name]}.distinctUntilChanged()
        .map{read(ControlUiSnapshot(JsonObject(mapOf(name to (it ?: JsonNull)))))}
        .stateIn(scope,SharingStarted.Eagerly,read(models.value))
    private inline fun <reified T> listField(name:String):StateFlow<List<T>> =
        models.decodedListField(scope,name){it.model(name,emptyList<T>())}
    // Decode the shared console once per change, preserving lastGood if a snapshot section fails to decode.
    private val consoleState: StateFlow<RunConsoleState> = run {
        var lastGood = models.value.model("console", RunConsoleState())
        models.map { it.document["console"] }.distinctUntilChanged().map { raw ->
            var failed = false
            val decoded = ControlUiSnapshot(JsonObject(mapOf("console" to (raw ?: JsonNull)))).model(
                name = "console",
                fallback = RunConsoleState(),
                onFailure = lastGood,
                onError = {
                    failed = true
                    localError.value = CONSOLE_DECODE_ERROR
                },
            )
            if (!failed) {
                lastGood = decoded
                if (localError.value == CONSOLE_DECODE_ERROR) localError.value = null
            }
            decoded
        }.stateIn(scope, SharingStarted.Eagerly, lastGood)
    }
    private fun <T> consoleField(read:(RunConsoleState)->T):StateFlow<T> = consoleState
        .map(read).distinctUntilChanged().stateIn(scope,SharingStarted.Eagerly,read(consoleState.value))
    val assets = AppAssets()
    val dataDirectory get()=Path.of(models.value.text("dataDirectory") ?: ".")
    val incidentCount: StateFlow<Int> = field("incidentCount") { it.document["incidentCount"]?.jsonPrimitive?.intOrNull ?: 0 }
    val notices: StateFlow<List<String>> = listField("notices")
    val gameClientResolution get()=models.value.model("gameClient",GameClientResolution.Unavailable as GameClientResolution)
    val gameClient=field("gameClient"){it.model("gameClient",GameClientResolution.Unavailable as GameClientResolution)}
    val busy get()=port.connection.value!=WorkerConnectionStatus.CONNECTED||consoleState.value.busy
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
    val battleModel: StateFlow<BattleModelStatus> = field("battleModel") { it.model("battleModel", BattleModelStatus()) }
    val automationOverlayCoordinator=OverlayView()
    val taskStopping=consoleField{it.stopping}
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
    suspend fun awaitSnapshot(
        timeoutMessage: String = "操作结果尚未确认，请稍后重试",
        predicate: (ControlUiSnapshot) -> Boolean,
    ): ControlUiSnapshot {
        return withTimeoutOrNull(8000) {
            ControlUiSnapshot(port.snapshots.first { predicate(ControlUiSnapshot(it)) })
        } ?: error(timeoutMessage)
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
    fun pauseTask(paused:Boolean?=null){fire(ControlOps.PAUSE,buildJsonObject{paused?.let{put("paused",it)}})}
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
    suspend fun dismissNotices(){request(ControlOps.NOTICES_DISMISS)}
    suspend fun replayStats(): Pair<Int, Int> {
        val reply = request(ControlOps.REPLAYS_STATS)
        return (reply["episodes"]?.jsonPrimitive?.intOrNull ?: 0) to (reply["battleSamples"]?.jsonPrimitive?.intOrNull ?: 0)
    }
    suspend fun exportReplays(path: String): String {
        val reply = request(ControlOps.REPLAYS_EXPORT, buildJsonObject { put("path", path) })
        return reply["message"]?.jsonPrimitive?.contentOrNull ?: "匿名回放已导出"
    }
    suspend fun messageRequest(op:String):String=request(op)["message"]?.jsonPrimitive?.contentOrNull ?: "操作已完成"
    override fun close(){scope.cancel()}

    inner class ResolutionView {val status=field("resolution"){it.model("resolution",VisualResolutionStatus(message=VisualResolutionGuardMessage))}}
    inner class OcrView {
        val status:StateFlow<OcrEngineStatus> = field("ocr"){it.model("ocr",OcrEngineStatus.NotLoaded as OcrEngineStatus)}
        val telemetry:StateFlow<OcrEngineTelemetry> = field("ocrTelemetry"){it.model("ocrTelemetry",OcrEngineTelemetry())}
    }
    inner class OverlayView {val state=consoleField{it.overlay}}
    inner class IdentityView {val state=field("identity"){it.model<DeviceIdentitySnapshot?>("identity",null)};fun current():DeviceIdentitySnapshot?=state.value}
    inner class DeviceView {
        val state=consoleField{it.device}
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
        val library=field("strategyLibrary"){it.model("strategyLibrary",StrategyLibrary())}
        suspend fun add(name:String,note:String="",settings:LabyrinthStrategySettings=LabyrinthStrategySettings()):String {
            val id=request(ControlOps.STRATEGY_ADD,buildJsonObject{put("name",name);put("note",note);put("value",ControlModelCodec.encode(settings))}).getValue("id").jsonPrimitive.content
            awaitSnapshot{it.model("strategyLibrary",StrategyLibrary()).profiles.any{p->p.id==id}}
            return id
        }
        suspend fun importCode(code:String):String {
            val id=request(ControlOps.STRATEGY_IMPORT,buildJsonObject{put("code",code)}).getValue("id").jsonPrimitive.content
            awaitSnapshot{it.model("strategyLibrary",StrategyLibrary()).profiles.any{p->p.id==id}}
            return id
        }
        suspend fun importConfiguration(code: String, modules: Set<com.landosol.toolbox.labyrinth.LabyrinthConfigModule>,
            profileId: String, accountId: Long?, confirmEmptyScores: Boolean, revision: Long) {
            request(ControlOps.CONFIG_IMPORT, buildJsonObject {
                put("code", code); put("modules", ControlModelCodec.encode(modules)); put("profileId", profileId)
                accountId?.let { put("accountId", it) }; put("confirmEmptyScores", confirmEmptyScores); put("revision", revision)
            })
            awaitSnapshot { it.model("strategyLibrary", StrategyLibrary()).revision > revision }
        }
        suspend fun undoImport(revision: Long) {
            request(ControlOps.CONFIG_UNDO, buildJsonObject { put("revision", revision) })
            awaitSnapshot { it.model("strategyLibrary", StrategyLibrary()).revision > revision }
        }
        suspend fun edit(profile:StrategyProfile,settings:LabyrinthStrategySettings=profile.settings):StrategyProfile {
            request(ControlOps.STRATEGY_EDIT,buildJsonObject{put("id",profile.id);put("revision",profile.revision);put("name",profile.name);put("note",profile.note);put("value",ControlModelCodec.encode(settings))})
            var updated=profile
            awaitSnapshot{snapshot->snapshot.model("strategyLibrary",StrategyLibrary()).profiles.firstOrNull{it.id==profile.id&&it.revision>profile.revision}?.also{updated=it}!=null}
            return updated
        }
        suspend fun activate(id:String){request(ControlOps.STRATEGY_ACTIVATE,buildJsonObject{put("id",id)});awaitSnapshot{it.model("strategyLibrary",StrategyLibrary()).activeId==id}}
        suspend fun delete(id:String){request(ControlOps.STRATEGY_DELETE,buildJsonObject{put("id",id)});awaitSnapshot{it.model("strategyLibrary",StrategyLibrary()).profiles.none{p->p.id==id}}}
        val state=field("strategy"){it.model("strategy",LabyrinthStrategySettings())}
        suspend fun save(value:LabyrinthStrategySettings){request(ControlOps.STRATEGY_SAVE,controlArgs("value" to ControlModelCodec.encode(value)));awaitSnapshot{it.model("strategy",LabyrinthStrategySettings())==value}}
    }
    inner class TaskView {
        val uiState=consoleField{it.task}
        fun saveSettings(value:LabyrinthRerollSettings){fire(ControlOps.SAVE_REROLL,controlArgs("value" to ControlModelCodec.encode(value)))}
        fun checkStatus(){fire(ControlOps.START,buildJsonObject{put("task","READ")})}
        fun start(retreatConfirmed:Boolean=false,guildIdOverride:Int?=null){fire(ControlOps.START,buildJsonObject{put("task","REROLL");put("retreatConfirmed",retreatConfirmed);guildIdOverride?.let{put("guildId",it)}})}
        fun stop(){stopAll()}
        fun dismissMessage(){fire(ControlOps.DISMISS_MESSAGE)}
        fun reportMessage(message:String){localError.value=message}
        fun cancelCaptcha(){captcha.value?.get("ticketId")?.let{fire(ControlOps.CAPTCHA_CANCEL,controlArgs("ticketId" to it))}}
    }
    inner class UxView {
        val console=consoleState
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
        val keyLines: StateFlow<List<UserLogLine>> =
            models.decodedListField(scope, "keyLogs", fallbackName = "logs") { snapshot ->
                if (snapshot.document["keyLogs"] is JsonArray) {
                    snapshot.model("keyLogs", emptyList())
                } else {
                    snapshot.model<List<UserLogLine>>("logs", emptyList()).filter { it.kind != UserLogKind.NORMAL }
                }
            }
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
    companion object {
        private const val VisualResolutionGuardMessage = "自动执行要求 1920 × 1080 横屏，请检查实际截图尺寸"
        private const val CONSOLE_DECODE_ERROR = "前后台状态同步解析失败，已保留上次有效状态"
    }
}

class ControlUiSnapshot(val document:JsonObject) {
    val console:RunConsoleState by lazy{model("console",RunConsoleState())}
    fun text(name:String)=document[name]?.jsonPrimitive?.contentOrNull
    inline fun <reified T> model(name:String,fallback:T):T =
        model(name = name, fallback = fallback, onFailure = fallback)

    inline fun <reified T> model(
        name: String,
        fallback: T,
        onFailure: T,
        noinline onError: ((Throwable) -> Unit)? = null,
    ): T = document[name]?.takeUnless{it is JsonNull}?.let{
        try { ControlModelCodec.decode<T>(it) }
        catch (cancelled:CancellationException) { throw cancelled }
        catch (failure:Exception) {
            reportDecodeFailure(name, failure)
            onError?.invoke(failure)
            onFailure
        }
    } ?: fallback

    companion object {
        private val failedSections=java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        fun reportDecodeFailure(name:String, failure:Throwable? = null) {
            if(failedSections.add(name)) {
                AppLogger.e("ControlUI", "Control snapshot decode failed for section: $name", failure)
            }
        }
    }
}
