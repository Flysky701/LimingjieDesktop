package com.landosol.toolbox.clanbattle

import com.landosol.toolbox.automation.AutomationMode
import com.landosol.toolbox.automation.AutomationSessionId
import com.landosol.toolbox.automation.AutomationSessionManager
import com.landosol.toolbox.automation.AutomationSessionStartResult
import com.landosol.toolbox.automation.capture.CaptureFrameBus
import com.landosol.toolbox.automation.capture.CaptureFrameConsumerLease
import com.landosol.toolbox.automation.capture.CaptureFrameRegistrationResult
import com.landosol.toolbox.automation.capture.CaptureStateRegistry
import com.landosol.toolbox.automation.capture.CapturedFrame
import com.landosol.toolbox.automation.overlay.AutomationOverlayCoordinator
import com.landosol.toolbox.automation.overlay.AutomationOverlayPresentation
import com.landosol.toolbox.automation.overlay.AutomationOverlaySessionHandler
import com.landosol.toolbox.clanbattle.axis.AxisDocument
import com.landosol.toolbox.clanbattle.axis.AxisType
import com.landosol.toolbox.clanbattle.axis.BattleSlot
import com.landosol.toolbox.clanbattle.recognition.ClanBattleObservation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class ClanBattleRecognitionStatus { IDLE, RUNNING, STOPPED, ERROR }

data class ClanBattleRecognitionSessionState(
    val status: ClanBattleRecognitionStatus = ClanBattleRecognitionStatus.IDLE,
    val sessionId: AutomationSessionId? = null,
    val paused: Boolean = false,
    val frameCount: Long = 0,
    val lastObservation: ClanBattleObservation? = null,
    val axisName: String? = null,
    val axisType: AxisType? = null,
    val switchRuntime: SwitchRuntimeSnapshot? = null,
    val switchPauseFrameRole: BattleSlot? = null,
    val plannedIntents: List<String> = emptyList(),
    val switchReason: String? = null,
    val latestBossUbEvent: SwitchBossUbEvent? = null,
    val message: String? = null,
) {
    val running: Boolean get() = status == ClanBattleRecognitionStatus.RUNNING
}

sealed interface ClanBattleRecognitionStartResult {
    data class Started(val sessionId: AutomationSessionId) : ClanBattleRecognitionStartResult
    data class AlreadyRunning(val sessionId: AutomationSessionId) : ClanBattleRecognitionStartResult
    data class Blocked(val reason: String) : ClanBattleRecognitionStartResult
}

/** Recognition-only live session. It owns one capture consumer lease and never dispatches actions. */
class ClanBattleRecognitionSession(
    private val sessionManager: AutomationSessionManager,
    private val captureActive: () -> Boolean = CaptureStateRegistry::isActive,
    private val processorFactory: () -> (CapturedFrame) -> ClanBattleObservation = {
        AndroidClanBattleFrameProcessor.createDefault()::process
    },
    private val overlayCoordinator: AutomationOverlayCoordinator = AutomationOverlayCoordinator(),
    private val configurationGate: com.landosol.toolbox.automation.TaskConfigurationGate = com.landosol.toolbox.automation.TaskConfigurationGate(),
) {
    private val mutex = Mutex()
    private val _state = MutableStateFlow(ClanBattleRecognitionSessionState())
    val state: StateFlow<ClanBattleRecognitionSessionState> = _state.asStateFlow()
    private val processingLock = Any()

    @Volatile
    private var activeSessionId: AutomationSessionId? = null
    private var lease: CaptureFrameConsumerLease? = null
    private var lastProcessedAt = Long.MIN_VALUE
    private var configuredAxis: AxisDocument? = null
    private var activeSwitchCoordinator: SwitchAxisCoordinator? = null
    private val bossUbDetector = BossUbDetector()

    suspend fun configureAxis(axis: AxisDocument?): Boolean = mutex.withLock {
        if (activeSessionId != null) return false
        configuredAxis = axis
        _state.value = _state.value.copy(
            axisName = axis?.header?.get("轴名称")?.ifBlank { "未命名轴" },
            axisType = axis?.type,
            switchRuntime = null,
            switchPauseFrameRole = null,
            plannedIntents = emptyList(),
            switchReason = axis?.let {
                if (it.type == AxisType.SWITCH) "开关轴已配置，运行时固定为 Dry Run" else "顺序轴已配置，仅识别预览"
            },
        )
        true
    }

    suspend fun confirmPauseFrame(): Boolean = mutex.withLock {
        val nodeId = _state.value.switchRuntime?.nodeId ?: return false
        val confirmed = synchronized(processingLock) {
            activeSwitchCoordinator?.confirmPauseFrame(nodeId) == true
        }
        if (confirmed) {
            _state.value = _state.value.copy(
                switchReason = "卡帧已确认，等待下一帧识别",
                message = "卡帧确认仅用于 Dry Run，不会点击游戏",
            )
            activeSessionId?.let { overlayCoordinator.update(it, buildOverlayPresentation(it)) }
        }
        confirmed
    }

    suspend fun start(): ClanBattleRecognitionStartResult {
        val admission = configurationGate.tryBeginTask()
            ?: return ClanBattleRecognitionStartResult.Blocked(com.landosol.toolbox.automation.TaskConfigurationGate.TRANSFER_MESSAGE)
        return try { startAdmitted() } finally { admission.close() }
    }

    private suspend fun startAdmitted(): ClanBattleRecognitionStartResult = mutex.withLock {
        activeSessionId?.let { return ClanBattleRecognitionStartResult.AlreadyRunning(it) }
        if (!captureActive()) {
            val reason = "请先在权限中心开启屏幕捕获"
            _state.value = _state.value.copy(status = ClanBattleRecognitionStatus.ERROR, message = reason)
            return ClanBattleRecognitionStartResult.Blocked(reason)
        }
        val session = when (val started = sessionManager.start(AutomationMode.CLAN_BATTLE, dryRun = true)) {
            is AutomationSessionStartResult.AlreadyRunning -> {
                val reason = "已有${started.session.mode.name}任务运行"
                _state.value = _state.value.copy(status = ClanBattleRecognitionStatus.ERROR, message = reason)
                return ClanBattleRecognitionStartResult.Blocked(reason)
            }
            is AutomationSessionStartResult.Started -> started.session
        }
        val processor = runCatching(processorFactory).getOrElse { error ->
            sessionManager.stop(session.id)
            val reason = "加载会战识别模板失败：${error.message ?: "未知错误"}"
            _state.value = _state.value.copy(status = ClanBattleRecognitionStatus.ERROR, message = reason)
            return ClanBattleRecognitionStartResult.Blocked(reason)
        }
        val axis = configuredAxis
        synchronized(processingLock) {
            bossUbDetector.reset()
            activeSwitchCoordinator = axis
                ?.takeIf { it.type == AxisType.SWITCH }
                ?.let { SwitchAxisCoordinator(it.switchOpenings.singleOrNull(), it.switchNodes) }
        }
        val registration = CaptureFrameBus.register(OWNER) { frame ->
            onFrame(session.id, processor, frame)
        }
        if (registration is CaptureFrameRegistrationResult.Busy) {
            synchronized(processingLock) {
                activeSwitchCoordinator = null
                bossUbDetector.reset()
            }
            sessionManager.stop(session.id)
            val reason = "截图帧正在由${registration.owner}使用"
            _state.value = _state.value.copy(status = ClanBattleRecognitionStatus.ERROR, message = reason)
            return ClanBattleRecognitionStartResult.Blocked(reason)
        }
        lease = (registration as CaptureFrameRegistrationResult.Registered).lease
        activeSessionId = session.id
        lastProcessedAt = Long.MIN_VALUE
        _state.value = ClanBattleRecognitionSessionState(
            status = ClanBattleRecognitionStatus.RUNNING,
            sessionId = session.id,
            paused = false,
            axisName = axis?.header?.get("轴名称")?.ifBlank { "未命名轴" },
            axisType = axis?.type,
            switchReason = axis?.let {
                if (it.type == AxisType.SWITCH) "开关轴 Dry Run 已启动，不会执行动作" else "顺序轴仅识别预览已启动"
            },
            message = "仅识别会话已启动，不会执行点击",
        )
        val attached = overlayCoordinator.attach(
            presentation = buildOverlayPresentation(session.id),
            sessionHandler = object : AutomationOverlaySessionHandler {
                override suspend fun setPaused(paused: Boolean): Boolean = this@ClanBattleRecognitionSession.setPaused(paused)

                override suspend fun stop(): Boolean = this@ClanBattleRecognitionSession.stop("通知栏停止")
            },
        )
        if (!attached) {
            lease?.let(CaptureFrameBus::unregister)
            lease = null
            activeSessionId = null
            synchronized(processingLock) {
                activeSwitchCoordinator = null
                bossUbDetector.reset()
            }
            sessionManager.stop(session.id)
            val reason = "任务控制通知不可用或已有任务占用，请检查通知权限与频道"
            _state.value = _state.value.copy(
                status = ClanBattleRecognitionStatus.ERROR,
                sessionId = null,
                paused = false,
                message = reason,
            )
            return ClanBattleRecognitionStartResult.Blocked(reason)
        }
        ClanBattleRecognitionStartResult.Started(session.id)
    }

    suspend fun setPaused(paused: Boolean): Boolean = mutex.withLock {
        val sessionId = activeSessionId ?: return false
        val changed = sessionManager.setPaused(sessionId, paused)
        if (!changed) return false
        if (paused) synchronized(processingLock) { bossUbDetector.suspend() }
        _state.value = _state.value.copy(
            paused = paused,
            message = if (paused) "会话已暂停，Dry Run 不再处理新帧" else "会话已继续，仅识别 Dry Run",
        )
        overlayCoordinator.update(sessionId, buildOverlayPresentation(sessionId))
        true
    }

    suspend fun stop(reason: String = "用户停止仅识别会话"): Boolean = mutex.withLock {
        val sessionId = activeSessionId ?: return false
        activeSessionId = null
        synchronized(processingLock) {
            activeSwitchCoordinator = null
            bossUbDetector.reset()
        }
        lease?.let(CaptureFrameBus::unregister)
        lease = null
        val stopped = sessionManager.stop(sessionId)
        overlayCoordinator.detach(sessionId)
        _state.value = _state.value.copy(
            status = ClanBattleRecognitionStatus.STOPPED,
            sessionId = null,
            paused = false,
            switchRuntime = null,
            switchPauseFrameRole = null,
            plannedIntents = emptyList(),
            latestBossUbEvent = null,
            message = reason,
        )
        stopped
    }

    private fun onFrame(
        sessionId: AutomationSessionId,
        processor: (CapturedFrame) -> ClanBattleObservation,
        frame: CapturedFrame,
    ) {
        if (activeSessionId != sessionId) {
            if (!frame.bitmap.isRecycled) frame.bitmap.recycle()
            return
        }
        if (_state.value.paused) {
            if (!frame.bitmap.isRecycled) frame.bitmap.recycle()
            return
        }
        if (lastProcessedAt != Long.MIN_VALUE && frame.timestampMillis - lastProcessedAt < PREVIEW_FRAME_INTERVAL_MILLIS) {
            if (!frame.bitmap.isRecycled) frame.bitmap.recycle()
            return
        }
        lastProcessedAt = frame.timestampMillis
        runCatching { processor(frame) }
            .onSuccess { observation ->
                val updated = synchronized(processingLock) {
                    if (activeSessionId != sessionId) return@synchronized false
                    val bossUbEvent = bossUbDetector.updateFromObservation(observation, frame.timestampMillis)
                    val switchResult = activeSwitchCoordinator?.update(
                        observation,
                        frame.timestampMillis,
                        bossUbEvent,
                    )
                    val current = _state.value
                    _state.value = current.copy(
                        frameCount = current.frameCount + 1,
                        lastObservation = observation,
                        switchRuntime = switchResult?.runtime,
                        switchPauseFrameRole = switchResult?.pauseFrameRole,
                        plannedIntents = switchResult?.intents.orEmpty().map(::describeIntent),
                        switchReason = switchResult?.reason,
                        latestBossUbEvent = bossUbDetector.latestEvent(frame.timestampMillis),
                        message = observation.diagnostics.firstOrNull() ?: "识别帧已更新",
                    )
                    true
                }
                if (updated) {
                    overlayCoordinator.update(sessionId, buildOverlayPresentation(sessionId))
                }
            }
            .onFailure { error ->
                if (!frame.bitmap.isRecycled) frame.bitmap.recycle()
                synchronized(processingLock) {
                    if (activeSessionId == sessionId) {
                        _state.value = _state.value.copy(message = "识别失败：${error.message ?: "未知错误"}")
                    }
                }
            }
            .also {
                if (!frame.bitmap.isRecycled) frame.bitmap.recycle()
            }
    }

    private fun describeIntent(intent: ClanBattleActionIntent): String = when (intent) {
        is ClanBattleActionIntent.TapRole -> "点击${intent.role.name}: ${intent.purpose}"
        is ClanBattleActionIntent.TapAuto -> "点击 AUTO: ${intent.purpose}"
        is ClanBattleActionIntent.Notify -> "提示：${intent.message}"
        is ClanBattleActionIntent.Pause -> "安全暂停：${intent.reason}"
    }

    private fun buildOverlayPresentation(sessionId: AutomationSessionId): AutomationOverlayPresentation {
        val current = _state.value
        val detail = buildList {
            add("轴：${current.axisName ?: "未配置"}")
            add("识别帧：${current.frameCount}")
            current.lastObservation?.let { observation ->
                val clock = observation.filteredClock?.rawText ?: observation.clock?.rawText ?: "--:--"
                add("画面：${observation.screenKind.name} · $clock")
                observation.menuButton?.let { menu ->
                    add("菜单锚点：${if (menu.trustworthy) "可信" else "遮挡"} · ${"%.3f".format(menu.score)}")
                }
            }
            current.switchRuntime?.nodeId?.let { add("节点：$it") }
            current.latestBossUbEvent?.let { event ->
                val phase = if (event.early) "提前确认" else "完整确认"
                val duration = event.holdDurationMillis?.let { " · ${it / 1_000.0}s" }.orEmpty()
                add("BOSS UB：${event.heldClockSeconds}s · $phase$duration")
            }
            current.plannedIntents.firstOrNull()?.let { add("计划：$it") }
        }.joinToString("\n")
        return AutomationOverlayPresentation(
            sessionId = sessionId,
            title = "会战仅识别",
            status = if (current.paused) "已暂停" else "运行中",
            detail = detail,
            dryRun = true,
            paused = current.paused,
        )
    }

    private companion object {
        const val OWNER = "clan-battle-recognition-preview"
        const val PREVIEW_FRAME_INTERVAL_MILLIS = 200L
    }
}
