package com.landosol.toolbox.desktop.app.ui

import com.landosol.toolbox.desktop.app.DesktopAppGraph
import com.landosol.toolbox.desktop.app.UserLogKind
import com.landosol.toolbox.desktop.app.adapters.device.VisualResolutionStatus
import com.landosol.toolbox.desktop.app.adapters.storage.*
import com.landosol.toolbox.desktop.device.DeviceConnectionState
import com.landosol.toolbox.desktop.ocr.OcrEngineStatus
import com.landosol.toolbox.labyrinth.*
import com.landosol.toolbox.labyrinth.batch.*
import com.landosol.toolbox.automation.overlay.AutomationOverlayPresentation
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

enum class DesktopTask(val title:String,val summary:String,val mode:String) {
    BATCH("批量自动执行","每轮重新刷取开局，再按公会目标执行","会点击"), REROLL("刷开局","刷到满意开局为止","网络操作"),
    READ("读取当前开局","登录并读取开局信息","只读 · 会登录"), RECOGNIZE("只读识别","仅分析当前游戏画面","只读 · 不点击"), ENTRY("执行入口流程","根据已保存策略操作游戏","会点击")
}
data class RunConsoleState(val task:LabyrinthUiState=LabyrinthUiState(),val entry:LabyrinthEntryRecognitionSessionState=LabyrinthEntryRecognitionSessionState(),
    val batch:LabyrinthBatchCheckpoint?=null,val halt:LabyrinthBatchHaltReason?=null,val overlay:AutomationOverlayPresentation?=null,
    val device:DeviceConnectionState=DeviceConnectionState.Disconnected,val busy:Boolean=false,val starting:Boolean=false,val stopping:Boolean=false)
data class ReadinessItem(val title:String,val detail:String,val passed:Boolean,val page:String,val warning:Boolean=false)
data class StopExplanation(val title:String,val reason:String,val advice:String,val destination:String,val action:String,val original:String)
fun explainStop(halt:LabyrinthBatchHaltReason?,raw:String):StopExplanation {
    if(halt==null) {
        val rules=listOf(
            listOf("1920 × 1080","画面尺寸不符合要求","请在模拟器中设置 1920 × 1080 横屏，再到设备页重新检查。","Devices","检查画面尺寸"),
            listOf("请先在设备页连接","模拟器未连接","连接模拟器后，再开始画面任务。","Devices","连接设备"),
            listOf("休眠","模拟器屏幕正在休眠","请先手动唤醒模拟器，再重新检查。","Devices","检查设备"),
            listOf("无法确定游戏客户端","游戏客户端未就绪","核对账号服务器，并确认模拟器中安装了对应游戏。","Devices","检查客户端"),
            listOf("OCR 尚未就绪","识别模型尚未就绪","在设置中查看模型加载状态与失败原因。","Settings","查看模型"),
            listOf("识别模板","识别资源未就绪","查看诊断记录，确认发行包中的资源完整。","Records","查看诊断"),
            listOf("触控服务未就绪","触控未就绪","在设备页重新连接，必要时选择兼容模式。","Devices","检查触控"),
            listOf("无障碍服务未连接","触控未就绪","桌面版请在设备页重新连接触控服务。","Devices","检查触控"),
            listOf("屏幕捕获","尚未获取游戏画面","连接模拟器并重新开始画面预览。","Devices","检查设备"),
            listOf("策略配置加载失败","策略无法加载","检查策略保存状态和诊断详情。","Strategies","检查策略"),
            listOf("队伍状态失败","本局状态无法读取","查看本地存储与诊断信息，核对游戏画面。","Records","查看诊断"),
            listOf("联网校验","执行前校验未通过","核对当前账号、网络和开局，再重新读取。","Run","返回运行台"),
            listOf("未选择账号","尚未选择账号","在账号页添加并选择任务账号。","Accounts","选择账号"),
            listOf("凭据","账号凭据不可用","编辑账号并重新填写凭据，不会切换模拟器账号。","Accounts","编辑账号"),
            listOf("任务运行","已有任务运行","先暂停或停止已有任务，再启动其他任务。","Run","查看任务"),
            listOf("截图帧正在由","画面已被其他任务占用","先停止已有的画面任务。","Run","查看任务"),
            listOf("超过5分钟","等待战斗结算超时","查看模拟器当前画面，必要时人工接管。","Run","检查画面"),
            listOf("详情探测达到上限","未识别具体 EX","核对游戏画面，先使用只读识别。","Run","切换到只读识别"),
            listOf("事件自由选角无法继续","选角暂时无法继续","检查原始原因和游戏角色列表，必要时人工接管。","Run","检查画面"),
            listOf("用户停止","任务已手动停止","核对配置和游戏页面后，可再次开始。","Run","返回运行台")
        )
        rules.firstOrNull{raw.contains(it[0])}?.let{return StopExplanation(it[1],it[1],it[2],it[3],it[4],raw)}
    }
    val info=when(halt) {
        LabyrinthBatchHaltReason.CAPTURE_LOST -> listOf("画面连接中断","无法继续获取模拟器画面。","保持模拟器运行，重新连接后再检查。","Devices","检查设备")
        LabyrinthBatchHaltReason.ACCESSIBILITY_LOST -> listOf("触控或前台条件不满足","当前无法安全操作游戏。","请将游戏切回前台，并在设备页确认触控状态。","Devices","检查设备")
        LabyrinthBatchHaltReason.REROLL_FAILED -> listOf("刷开局未完成","网络刷取步骤停止。","检查账号、验证码和网络，再核对完整配置。","Accounts","检查账号")
        LabyrinthBatchHaltReason.REUSABLE_OPENING_VALIDATION_FAILED -> listOf("首轮开局需要重新读取","此前读取的开局未通过再次验证。","重新读取当前开局并核对目标。","Run","返回运行台")
        LabyrinthBatchHaltReason.CLIENT_INVALIDATION_FAILED -> listOf("重置游戏会话未完成","客户端尚未回到可继续执行的状态。","检查模拟器游戏页面，准备好后再开始。","Devices","检查设备")
        LabyrinthBatchHaltReason.RUN_START_FAILED -> listOf("本次执行未能启动","画面执行的前置条件未满足。","检查设备、触控、模型和游戏前台状态。","Settings","查看环境")
        LabyrinthBatchHaltReason.FATAL_RUN -> listOf("本次执行已停止","识别或执行步骤无法继续。","核对当前游戏画面，先使用只读识别检查。","Run","切换到只读识别")
        LabyrinthBatchHaltReason.USER_STOPPED -> listOf("任务已手动停止","已按你的要求结束任务。","核对目标和游戏画面后，可重新开始。","Run","返回运行台")
        null -> listOf("任务反馈","请查看本次任务的反馈信息。","核对当前游戏画面与任务配置，再决定下一步操作。","Run","返回运行台")
    }
    val battle=raw.contains("缺少本次战斗上下文")
    return StopExplanation(info[0],if(battle) "识别到了战斗失败页，但没有当前战斗信息。" else info[1],
        if(battle) "可能停在了标题画面，请先进入游戏主界面，再用只读识别检查。" else info[2],info[3],info[4],raw)
}
fun startMissing(task:DesktopTask,state:RunConsoleState,ocrReady:Boolean,hasGoals:Boolean,credentialsReady:Boolean?=true,resolution:VisualResolutionStatus?=null):String? {
    if(state.busy) return if(state.stopping) "正在停止任务" else "当前任务正在运行"
    if(task in listOf(DesktopTask.BATCH,DesktopTask.REROLL,DesktopTask.READ) && state.task.selectedAccount==null) return "尚未选择账号"
    if(task in listOf(DesktopTask.BATCH,DesktopTask.REROLL,DesktopTask.READ)) {
        if(credentialsReady==false)return "账号凭据缺失"
        if(credentialsReady==null)return "正在检查账号凭据"
    }
    if(task in listOf(DesktopTask.BATCH,DesktopTask.REROLL) && !state.task.settingsReady) return "刷开局配置尚未就绪"
    if(task in listOf(DesktopTask.BATCH,DesktopTask.RECOGNIZE,DesktopTask.ENTRY)) {
        val device=(state.device as? DeviceConnectionState.Ready)?.device ?: return "模拟器未连接"
        if(!device.awake) return "模拟器屏幕正在休眠"
        if(task!=DesktopTask.RECOGNIZE && !device.touchReady) return "触控未就绪"
        if(task!=DesktopTask.RECOGNIZE && resolution!=null && !resolution.supported) return resolution.message
        if(!ocrReady) return "识别模型尚未就绪"
    }
    if(task==DesktopTask.BATCH && !hasGoals) return "尚未配置公会目标"
    return null
}

/** Observes desktop and upstream state only. Never controls recognition, polling or game I/O. */
@OptIn(FlowPreview::class)
class DesktopUxController(private val graph:DesktopAppGraph) {
    var confirmNavigation:((()->Unit)->Unit)?=null
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default+CoroutineExceptionHandler { _,e -> graph.log.add("体验面板：${e.message}",true) })
    val preferences=DesktopPreferencesStore(graph.dataDirectory,graph::addNotice)
    val history=graph.runHistory
    private val foregroundMutable=MutableStateFlow<String?>(null)
    val foreground=foregroundMutable.asStateFlow()
    private val databaseMutable=MutableStateFlow("正在读取")
    val database=databaseMutable.asStateFlow()
    private val catalogMutable=MutableStateFlow<List<LabyrinthRoleRatingItem>>(emptyList())
    val catalog=catalogMutable.asStateFlow()
    private val credentialsMutable=MutableStateFlow<Map<Long,Boolean>>(emptyMap())
    val credentials=credentialsMutable.asStateFlow()
    private val core=combine(graph.labyrinthController.uiState,graph.labyrinthEntryRecognitionSession.state,graph.labyrinthBatchController.state,
        graph.labyrinthBatchController.haltReason,graph.automationOverlayCoordinator.state) { ui,entry,batch,halt,overlay -> RunConsoleState(ui,entry,batch,halt,overlay) }
    private val full=combine(core,graph.device.state,graph.taskStarting,graph.taskStopping,graph.gameSessionResetWorkflow.state) { state,device,starting,stopping,reset ->
        state.copy(device=device,starting=starting,stopping=stopping,busy=starting||stopping||state.task.isWorking||state.task.captcha!=null||state.entry.running||reset.running||state.batch?.stage in activeStages)
    }
    val console=full.sample(250).stateIn(scope,SharingStarted.Eagerly,RunConsoleState())
    @Volatile private var expectedType="网络任务"
    private var networkId:String?=null
    private var recognitionId:String?=null
    fun expect(task:DesktopTask) { expectedType=task.title }
    fun start() {
        scope.launch(Dispatchers.IO) {
            history.recoverInterrupted()
            supervisorScope {
                launch { full.distinctUntilChangedBy { listOf(it.batch?.batchId,it.batch?.currentRunId,it.batch?.lastRecordedRunId,it.batch?.stage,it.entry.running,it.task.isWorking,it.task.captcha,it.halt) }.collect(::record) }
                launch { graph.accountRepository.observeAccounts().collect { rows -> credentialsMutable.value=rows.associate { account->account.id to runCatching{graph.accountRepository.loadEditor(account.id)}.isSuccess } } }
                launch {refreshDatabase();catalogMutable.value=graph.loadLabyrinthRoleRatings()}
            }
        }
    }
    suspend fun refreshDatabase()=withContext(Dispatchers.IO) { databaseMutable.value=graph.labyrinthCnDatabaseRepository.currentMetadata()?.let { "已就绪 · ${it.version}" } ?: "尚无国服数据库，请在设置中更新" }
    suspend fun refreshForeground()=withContext(Dispatchers.IO) { foregroundMutable.value=graph.device.queryForeground() }
    private fun begin(id:String,type:String,goal:String,s:RunConsoleState,single:Boolean=false) {
        if(history.records.value.none { it.id==id }) { history.put(DesktopRunRecord(id,System.currentTimeMillis(),s.task.selectedAccount?.alias ?: "未选择",type,goal,singleRun=single)); graph.log.add("开始：$type",kind=UserLogKind.KEY) }
    }
    private fun record(s:RunConsoleState) {
        val batch=s.batch
        if(batch!=null) {
            val id="batch:${batch.batchId}"
            if(batch.stage in activeStages) begin(id,"批量自动执行",batch.goals.joinToString { "${it.guildId}: ${it.targetCount} ${if(it.mode==LabyrinthBatchGoalMode.CLEARS) "次通关" else "次执行"}" },s)
            batch.currentRunId?.let { begin("run:$it","批量执行 · 单次","公会 ${batch.currentGuildId ?: batch.activeGoal?.guildId ?: "—"}",s,true) }
            batch.lastRecordedRunId?.let { runId -> (batch.lastRecordedOutcome ?: batch.currentRunOutcome)?.let { outcome ->
                val summary = batch.lastRunSummary
                history.finish("run:$runId",when(outcome){LabyrinthRunOutcome.CLEARED->"通关";LabyrinthRunOutcome.USER_STOPPED->"手动停止";LabyrinthRunOutcome.ABORTED_UNKNOWN->"结果未确认";else->"未通关"},summary?.description() ?: outcome.name,if(outcome==LabyrinthRunOutcome.CLEARED)1 else 0,summary?.score,summary?.endedAt)
            } }
            if(batch.stage in setOf(LabyrinthBatchStage.COMPLETED,LabyrinthBatchStage.FAILED,LabyrinthBatchStage.PAUSED)) {
                val result=if(batch.stage==LabyrinthBatchStage.COMPLETED) "已完成" else if(s.halt==LabyrinthBatchHaltReason.USER_STOPPED) "手动停止" else "已停止"
                history.finish(id,result,listOfNotNull(s.halt?.name,batch.message).joinToString(" · "))
                history.records.value.filter { it.singleRun && it.endedAt==null }.forEach { history.finish(it.id,"已停止",listOfNotNull(s.halt?.name,batch.message).joinToString(" · ")) }
            }
        }
        val inBatch=batch?.stage in activeStages
        if(s.task.isWorking && !inBatch && networkId==null) { networkId="network:${s.task.startedAtMillis ?: System.currentTimeMillis()}";begin(networkId!!,expectedType,s.task.guildOptions.firstOrNull { it.guildId==s.task.selectedGuildId }?.name ?: "当前账号",s) }
        if(!s.task.isWorking && s.task.captcha==null) networkId?.let { history.finish(it,"已结束",s.task.message ?: s.task.progress ?: "网络操作已结束");networkId=null }
        if(s.entry.running && !inBatch && recognitionId==null) { recognitionId="recognition:${s.entry.sessionId}";begin(recognitionId!!,if(s.entry.dryRun) "只读识别" else "执行入口流程","当前游戏画面",s,single=!s.entry.dryRun) }
        if(!s.entry.running) recognitionId?.let { val summary=s.entry.runSummary;history.finish(it,if(summary!=null) "通关" else if(s.entry.status==LabyrinthEntryRecognitionStatus.ERROR) "异常停止" else "已停止",summary?.description() ?: s.entry.message.orEmpty(),if(summary!=null)1 else 0,summary?.score,summary?.endedAt);recognitionId=null }
    }
    suspend fun closeAndJoin() { scope.coroutineContext[Job]?.cancelAndJoin();withContext(Dispatchers.IO){ history.records.value.filter { it.endedAt==null }.forEach { history.finish(it.id,"应用退出","应用退出，当前任务已停止") } } }
    companion object { val activeStages=setOf(LabyrinthBatchStage.REROLLING,LabyrinthBatchStage.INVALIDATING_OLD_CLIENT_SESSION,LabyrinthBatchStage.RUNNING_LABYRINTH,LabyrinthBatchStage.RECORDING_RESULT) }
}
