package com.landosol.toolbox.automation.session

import com.landosol.toolbox.automation.AutomationMode
import com.landosol.toolbox.automation.AutomationActionResult
import com.landosol.toolbox.automation.AutomationSessionId
import com.landosol.toolbox.automation.AutomationSessionManager
import com.landosol.toolbox.automation.AutomationSessionStartResult
import com.landosol.toolbox.automation.SessionBoundActionExecutor
import com.landosol.toolbox.automation.capture.CaptureFrameBus
import com.landosol.toolbox.automation.capture.CaptureFrameRegistrationResult
import com.landosol.toolbox.automation.capture.CapturedFrame
import com.landosol.toolbox.automation.capture.CaptureStateRegistry
import com.landosol.toolbox.automation.overlay.AutomationOverlayCoordinator
import com.landosol.toolbox.automation.overlay.AutomationOverlayPresentation
import com.landosol.toolbox.automation.overlay.AutomationOverlaySessionHandler
import com.landosol.toolbox.labyrinth.LabyrinthEntryActionDecision
import com.landosol.toolbox.labyrinth.LabyrinthEntryActionPlanner
import com.landosol.toolbox.labyrinth.vision.LabyrinthEntryFrameResult
import com.landosol.toolbox.labyrinth.vision.LabyrinthEntryPageState
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import com.landosol.toolbox.core.logging.AppLogger

enum class GameSessionResetStatus { IDLE, RUNNING, STOPPED, ERROR }

data class GameSessionResetState(
    val status: GameSessionResetStatus = GameSessionResetStatus.IDLE,
    val sessionId: AutomationSessionId? = null,
    val stage: GameSessionResetStage = GameSessionResetStage.PENDING,
    val frameCount: Long = 0,
    val actionCount: Int = 0,
    val lastBlock: SessionBlockKind = SessionBlockKind.NONE,
    val paused: Boolean = false,
    val message: String? = null,
) {
    val running: Boolean get() = status == GameSessionResetStatus.RUNNING
}

sealed interface GameSessionResetStartResult {
    data class Started(val sessionId: AutomationSessionId) : GameSessionResetStartResult
    data class AlreadyRunning(val sessionId: AutomationSessionId) : GameSessionResetStartResult
    data class Blocked(val reason: String) : GameSessionResetStartResult
}

enum class GameSessionResetReadyDecision { HOLD_FOR_TERMINATION, READY, CONTINUE }

/**
 * Pages on which the client has not loaded any labyrinth state yet. Starting a batch from one of
 * them (title, loading, announcement, home, adventure) needs no "return to title": the entry
 * planner navigates forward and the labyrinth home it reaches already reflects the new server
 * entry. 2026-09-17 user: "从app助手内的开始批量执行处发起的操作视为初次执行，需要可以从黎明界
 * 之前的登录流程中进行操作，而不是自认为需要先触发返回标题界面". Every other page (labyrinth
 * home, map, battle failure, unknown) keeps the invalidation step.
 */
internal fun gameSessionResetSkipsTermination(page: LabyrinthEntryPageState): Boolean = page in setOf(
    LabyrinthEntryPageState.TITLE_WAITING_TAP,
    LabyrinthEntryPageState.PRE_HOME_DATA_LOADING,
    LabyrinthEntryPageState.HOME_ANNOUNCEMENT,
    LabyrinthEntryPageState.HOME,
    LabyrinthEntryPageState.ADVENTURE,
)

/**
 * Whether the reset may try to invalidate an old session.
 *
 * [clientObserved] is the part the page alone cannot express. The observation handler stops
 * before reading a page whenever the client is not in the foreground, so a client that is still
 * launching, backgrounded or absent leaves the peek at its initial UNKNOWN. UNKNOWN otherwise
 * means "a labyrinth page we failed to classify", which does need invalidating, so the two cases
 * used to be indistinguishable and the second one won.
 *
 * 2026-09-19 live: a batch round began while the game was still opening. Every captured frame was
 * the portrait launcher, the peek returned UNKNOWN, and the anchor terminator spent its whole
 * budget looking for a 黎明界 trigger on a screen the game was not even drawing, ending the batch
 * with CLIENT_INVALIDATION_FAILED. With no client on screen there is no session to invalidate, so
 * the reset should go straight to the entry navigation instead.
 */
internal fun gameSessionResetSkipsTermination(
    page: LabyrinthEntryPageState,
    clientObserved: Boolean,
): Boolean = !clientObserved || gameSessionResetSkipsTermination(page)

/**
 * Whether a recognized labyrinth-home/map frame may complete the reset.
 *
 * While the terminator is still driving the client the screen is by definition the *old*
 * labyrinth home (the batch starts the reset from there), so recognising it as READY would hand
 * stale local state to the next run and, worse, let the entry planner tap 出发 on it. Only frames
 * observed once the flow has moved past termination/relaunch may complete the reset.
 */
internal fun gameSessionResetReadyDecision(
    stage: GameSessionResetStage,
    pageState: LabyrinthEntryPageState,
): GameSessionResetReadyDecision {
    if (
        stage == GameSessionResetStage.PENDING ||
        stage == GameSessionResetStage.TERMINATING ||
        stage == GameSessionResetStage.RELAUNCHING
    ) {
        return GameSessionResetReadyDecision.HOLD_FOR_TERMINATION
    }
    return if (
        pageState == LabyrinthEntryPageState.DAWN_REALM_HOME_IDLE ||
        pageState == LabyrinthEntryPageState.DAWN_REALM_HOME_ACTIVE ||
        pageState == LabyrinthEntryPageState.NODE_SELECTION
    ) {
        GameSessionResetReadyDecision.READY
    } else {
        GameSessionResetReadyDecision.CONTINUE
    }
}

/**
 * 会话重置编排：保存本局结果 → 关闭客户端 → 重启 → 等登录 → 主页 → 冒险 →
 * 黎明界 → 就绪下一轮。复用共享自动化会话仲裁、截图帧总线与悬浮窗。
 */
class GameSessionResetWorkflow(
    private val sessionManager: AutomationSessionManager,
    private val backend: GameSessionResetBackend,
    private val captureActive: () -> Boolean = CaptureStateRegistry::isActive,
    private val processorFactory: () -> (CapturedFrame) -> LabyrinthEntryFrameResult,
    private val blockClassifier: (LabyrinthEntryFrameResult) -> SessionBlockKind,
    private val overlayCoordinator: AutomationOverlayCoordinator = AutomationOverlayCoordinator(),
    private val onSaveRoundResult: suspend () -> Unit = {},
    private val onNextRoundReady: suspend (AutomationSessionId) -> Unit = {},
    private val onBlocked: suspend (AutomationSessionId, SessionBlockKind) -> Unit = { _, _ -> },
    private val presence: GameClientPresenceObserver,
    private val clock: () -> Long = System::currentTimeMillis,
    private val actionExecutor: SessionBoundActionExecutor? = null,
    private val entryActionPlannerFactory: () -> LabyrinthEntryActionPlanner = { LabyrinthEntryActionPlanner() },
    private val totalTimeoutMillis: Long = DEFAULT_TOTAL_TIMEOUT_MILLIS,
    /** Publishes recognized reset frames to the diagnostics recorder. */
    private val debugFramePublisher: ((CapturedFrame, GameSessionResetState, LabyrinthEntryFrameResult, AutomationOverlayPresentation) -> Unit)? = null,
    /** Channel SDK login dialogs (九游) recognised through accessibility, not frames. */
    private val channelPrompts: ChannelLoginPromptAssistant? = null,
    private val actionScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val configurationGate: com.landosol.toolbox.automation.TaskConfigurationGate = com.landosol.toolbox.automation.TaskConfigurationGate(),
    private val debugLog: (String) -> Unit = { AppLogger.d(LOG_TAG, it) },
) {
    private val mutex = Mutex()
    private val frameProcessorMutex = Mutex()
    private val inFlight = AtomicBoolean(false)
    private val _state = MutableStateFlow(GameSessionResetState())
    val state: StateFlow<GameSessionResetState> = _state.asStateFlow()

    @Volatile
    private var activeSessionId: AutomationSessionId? = null
    private var resetFlowJob: Job? = null
    @Volatile
    private var pauseStartedAt: Long = Long.MIN_VALUE
    @Volatile
    private var pausedDurationMillis: Long = 0L
    private var lease: com.landosol.toolbox.automation.capture.CaptureFrameConsumerLease? = null
    @Volatile
    private var stage: GameSessionResetStage = GameSessionResetStage.PENDING
    private var stableStageFrames = 0
    private var lastStageChangeAt = Long.MIN_VALUE

    private fun activeClock(): Long {
        val now = clock()
        val pausedAt = pauseStartedAt
        val currentPause = if (pausedAt != Long.MIN_VALUE) (now - pausedAt).coerceAtLeast(0L) else 0L
        return now - pausedDurationMillis - currentPause
    }

    /** Most recent recognised page; read by the PENDING peek before termination is decided. */
    @Volatile
    private var lastObservedPage: LabyrinthEntryPageState = LabyrinthEntryPageState.UNKNOWN

    /**
     * Set once a frame has been recognised with the client in the foreground.
     *
     * Without this the peek cannot tell an unclassifiable game page from no game at all, because
     * both leave [lastObservedPage] on UNKNOWN.
     */
    @Volatile
    private var clientObserved = false

    /**
     * Frames observed before this time belong to a channel SDK login dialog that the accessibility
     * poll has just seen. The entry planner must not tap through it: 2026-10-05 九游 debug bundle,
     * the planner read the 登录历史 dialog as an UNKNOWN pre-announcement frame and kept tapping
     * (950,930) outside it until the reset gave up.
     */
    @Volatile
    private var channelPromptVisibleUntil = Long.MIN_VALUE

    @Volatile
    private var lastChannelPromptPollAt = Long.MIN_VALUE

    /**
     * Set when this run cold-starts a client that was hard-closed before the reroll. Until the
     * title page is seen, no frame may complete the reset or drive the entry planner: a page from
     * a process that survived the kill would be taken over with a stale session.
     */
    @Volatile
    private var coldStartGate: ColdStartTitleGate? = null

    @Volatile
    private var reconnectRecoveryRequested = false
    private var expiryRecoveryAttempts = 0

    private fun isExpiryReconnect(block: SessionBlockKind): Boolean =
        backend.kind == ClientTerminationKind.SESSION_EXPIRY &&
            coldStartGate == null &&
            block == SessionBlockKind.RECONNECT_PROMPTED

    private val GameSessionResetStage.isFailure: Boolean
        get() = this == GameSessionResetStage.SESSION_BLOCKED || this == GameSessionResetStage.FAILED

    init {
        require(totalTimeoutMillis > 0)
    }

    /**
     * @param clientHardClosed the caller has just hard-closed the client (大退) before a reroll, so
     *   there is no session to invalidate and no point waiting for the client to show up: cold-start
     *   it and let the entry planner log in from the title.
     */
    suspend fun start(clientHardClosed: Boolean = false): GameSessionResetStartResult = mutex.withLock {
        startLocked(onSaveRoundResult, clientHardClosed)
    }

    private suspend fun startLocked(
        saveRoundResult: suspend () -> Unit,
        clientHardClosed: Boolean = false,
    ): GameSessionResetStartResult {
        val admission = configurationGate.tryBeginTask()
            ?: return GameSessionResetStartResult.Blocked(com.landosol.toolbox.automation.TaskConfigurationGate.TRANSFER_MESSAGE)
        return try {
            activeSessionId?.let { return GameSessionResetStartResult.AlreadyRunning(it) }
            // Let an old capture callback finish before the new processor resets shared frame evidence.
            frameProcessorMutex.withLock { startAdmitted(saveRoundResult, clientHardClosed) }
        } finally {
            admission.close()
        }
    }

    private suspend fun startAdmitted(
        saveRoundResult: suspend () -> Unit,
        clientHardClosed: Boolean,
    ): GameSessionResetStartResult {
        if (!captureActive()) {
            return blockedStart("请先在权限中心开启屏幕捕获")
        }
        if (!backend.isSupported()) {
            return blockedStart("当前设备没有可用的客户端终止策略")
        }
        val session = when (val started = sessionManager.start(AutomationMode.SESSION_RESET, dryRun = false)) {
            is AutomationSessionStartResult.AlreadyRunning -> {
                return blockedStart("已有${started.session.mode.name}任务运行")
            }
            is AutomationSessionStartResult.Started -> started.session
        }
        val processor = runCatching(processorFactory).getOrElse { error ->
            sessionManager.stop(session.id)
            if (error is CancellationException) throw error
            return blockedStart("加载入口识别模板失败：${error.message ?: "未知错误"}")
        }
        val entryPlanner = runCatching(entryActionPlannerFactory).getOrElse { error ->
            sessionManager.stop(session.id)
            if (error is CancellationException) throw error
            return blockedStart("初始化入口动作规划器失败：${error.message ?: "未知错误"}")
        }.also { planner ->
            pauseStartedAt = Long.MIN_VALUE
            pausedDurationMillis = 0L
            planner.start(activeClock())
        }
        val registration = CaptureFrameBus.register(OWNER) { frame ->
            onFrame(session.id, processor, entryPlanner, frame)
        }
        if (registration is CaptureFrameRegistrationResult.Busy) {
            sessionManager.stop(session.id)
            return blockedStart("截图帧正在由${registration.owner}使用")
        }
        lease = (registration as CaptureFrameRegistrationResult.Registered).lease
        activeSessionId = session.id
        pauseStartedAt = Long.MIN_VALUE
        pausedDurationMillis = 0L
        inFlight.set(false)
        stage = GameSessionResetStage.PENDING
        stableStageFrames = 0
        lastStageChangeAt = activeClock()
        lastObservedPage = LabyrinthEntryPageState.UNKNOWN
        clientObserved = false
        channelPromptVisibleUntil = Long.MIN_VALUE
        lastChannelPromptPollAt = Long.MIN_VALUE
        channelPrompts?.reset()
        coldStartGate = if (clientHardClosed) ColdStartTitleGate() else null
        reconnectRecoveryRequested = false
        expiryRecoveryAttempts = 0
        _state.value = GameSessionResetState(
            status = GameSessionResetStatus.RUNNING,
            sessionId = session.id,
            stage = stage,
            message = "会话重置已启动",
        )
        val attached = overlayCoordinator.attach(
            presentation = buildOverlayPresentation(session.id),
            sessionHandler = object : AutomationOverlaySessionHandler {
                override suspend fun setPaused(paused: Boolean): Boolean =
                    this@GameSessionResetWorkflow.setPaused(paused)

                override suspend fun stop(): Boolean =
                    this@GameSessionResetWorkflow.stop("通知栏停止")
            },
        )
        if (!attached) {
            lease?.let(CaptureFrameBus::unregister)
            lease = null
            activeSessionId = null
            sessionManager.stop(session.id)
            return blockedStart("任务控制通知不可用或已有任务占用，请检查通知权限与频道")
        }
        resetFlowJob?.cancel()
        resetFlowJob = actionScope.launch {
            runResetFlow(
                sessionId = session.id,
                saveRoundResult = saveRoundResult,
                clientHardClosed = clientHardClosed,
                entryPlanner = entryPlanner,
            )
        }
        return GameSessionResetStartResult.Started(session.id)
    }

    private fun blockedStart(reason: String): GameSessionResetStartResult.Blocked {
        _state.update { it.copy(status = GameSessionResetStatus.ERROR, sessionId = null, message = reason) }
        return GameSessionResetStartResult.Blocked(reason)
    }

    suspend fun setPaused(paused: Boolean): Boolean = mutex.withLock {
        val sessionId = activeSessionId ?: return false
        if (!sessionManager.setPaused(sessionId, paused)) return false
        if (_state.value.paused == paused) {
            overlayCoordinator.update(sessionId, buildOverlayPresentation(sessionId))
            return true
        }
        val now = clock()
        if (paused) {
            pauseStartedAt = now
        } else {
            val startedAt = pauseStartedAt
            if (startedAt != Long.MIN_VALUE) {
                pausedDurationMillis += (now - startedAt).coerceAtLeast(0L)
                pauseStartedAt = Long.MIN_VALUE
            }
        }
        _state.update {
            it.copy(
                paused = paused,
                message = if (paused) "会话重置已暂停" else "会话重置已继续",
            )
        }
        overlayCoordinator.update(sessionId, buildOverlayPresentation(sessionId))
        true
    }

    suspend fun stop(reason: String = "用户停止会话重置"): Boolean = mutex.withLock {
        val sessionId = activeSessionId ?: return false
        resetFlowJob?.cancel()
        finishFlowLocked(sessionId, GameSessionResetStatus.STOPPED, stage, reason)
    }

    /** 供外部在自动刷完成后触发下一轮前的重置 */
    suspend fun runOnceAfterRound(
        saveResult: suspend () -> Unit = onSaveRoundResult,
    ): Boolean = mutex.withLock {
        when (startLocked(saveResult)) {
            is GameSessionResetStartResult.Started -> true
            is GameSessionResetStartResult.AlreadyRunning -> false
            is GameSessionResetStartResult.Blocked -> false
        }
    }

    private suspend fun runResetFlow(
        sessionId: AutomationSessionId,
        saveRoundResult: suspend () -> Unit,
        clientHardClosed: Boolean,
        entryPlanner: LabyrinthEntryActionPlanner,
    ) {
        val activeDeadline = activeClock() + totalTimeoutMillis
        try {
            awaitUnpaused(sessionId, activeDeadline)
            // 1. 保存本局结果。runOnceAfterRound 只把回调交给本流程，不在外层重复调用。
            setMessage("保存本局结果")
            saveRoundResult()
            awaitUnpaused(sessionId, activeDeadline)

            // 2. 先看清客户端此刻在哪一页。批次首轮可能从标题/主页/冒险页发起，那些页面
            //    尚未载入黎明界状态，没有旧会话可失效，直接走入口导航即可。
            setMessage("识别客户端当前页面")
            // A round can begin while the client is still opening. Give it time to reach the
            // foreground before judging which page it is on; otherwise every frame belongs to
            // the launcher and the peek below can only report UNKNOWN.
            // A hard-closed client cannot appear on its own; waiting for it only costs time.
            if (!clientHardClosed) awaitClientObserved(sessionId, CLIENT_PRESENCE_TIMEOUT_MILLIS, activeDeadline)
            val observedPage = if (clientHardClosed) {
                LabyrinthEntryPageState.UNKNOWN
            } else {
                awaitObservedPage(sessionId, PAGE_PEEK_TIMEOUT_MILLIS, activeDeadline)
            }
            awaitUnpaused(sessionId, activeDeadline)
            // A round can begin with a channel login dialog already over the title (2026-10-12
            // diag: the page read UNKNOWN, the flow hunted a 黎明界 trigger to invalidate a
            // session, timed out, and the reset failed before WAITING_LOGIN, the only stage
            // that handles the dialog). A channel login prompt on screen means the client is
            // still logging in: there is no session to invalidate.
            val channelPrompt = if (clientHardClosed) null else channelPrompts?.peek()
            awaitUnpaused(sessionId, activeDeadline)
            val pendingExpiryPopup = isExpiryReconnect(_state.value.lastBlock)
            val skipTermination = clientHardClosed || channelPrompt != null ||
                (!pendingExpiryPopup && gameSessionResetSkipsTermination(observedPage, clientObserved))
            debugLog(
                "peek page=$observedPage clientObserved=$clientObserved hardClosed=$clientHardClosed " +
                    "channelPrompt=${channelPrompt?.rule?.id} skipTermination=$skipTermination",
            )
            if (skipTermination) {
                setMessage(
                    when {
                        clientHardClosed -> "游戏已在刷开局前大退，冷启动公主连结"
                        channelPrompt != null -> "客户端停在渠道登录界面（${channelPrompt.rule.label}），无需触发会话失效"
                        !clientObserved -> "未看到公主连结画面，没有可失效的旧会话；直接进入登录导航"
                        else -> "客户端在 ${observedPage.name}，尚未载入黎明界，无需触发会话失效"
                    },
                )
                // Nothing was terminated, so the client may simply not be running. Ask the
                // backend to bring it up; the entry planner drives everything after that.
                if (clientHardClosed || !clientObserved) {
                    awaitUnpaused(sessionId, activeDeadline)
                    transitionTo(sessionId, GameSessionResetStage.RELAUNCHING, "启动公主连结")
                    val relaunch = backend.relaunchClient()
                    if (relaunch != GameClientRelaunchResult.LAUNCH_REQUESTED) {
                        error("启动客户端失败：$relaunch")
                    }
                }
            } else {
                // 3. 关闭/退出客户端（ADB force-stop 或会话失效触发弹窗返回标题）
                terminateClient(sessionId, activeDeadline)

                // 会话失效触发已回到标题页，无需重启；其余策略重新启动客户端
                if (backend.kind != ClientTerminationKind.SESSION_EXPIRY) {
                    awaitUnpaused(sessionId, activeDeadline)
                    transitionTo(sessionId, GameSessionResetStage.RELAUNCHING, "重新启动公主连结")
                    val relaunch = backend.relaunchClient()
                    if (relaunch != GameClientRelaunchResult.LAUNCH_REQUESTED) {
                        error("重新启动客户端失败：$relaunch")
                    }
                }
            }

            // 4-7. 入口规划器根据识别帧实际点击：标题 → 公告 → 主页 → 冒险 → 黎明界。
            awaitUnpaused(sessionId, activeDeadline)
            transitionTo(sessionId, GameSessionResetStage.WAITING_LOGIN, "等待登录完成")
            awaitStageReached(sessionId, GameSessionResetStage.READY_FOR_NEXT_ROUND, activeDeadline, entryPlanner)
            awaitUnpaused(sessionId, activeDeadline)
            if (activeSessionId != sessionId) return
            runCatching { onNextRoundReady(sessionId) }
                .onFailure { callbackError ->
                    if (callbackError is CancellationException) throw callbackError
                    finishFlow(
                        sessionId = sessionId,
                        status = GameSessionResetStatus.ERROR,
                        terminalStage = GameSessionResetStage.FAILED,
                        message = "下一轮回调失败：${callbackError.message ?: "未知错误"}",
                    )
                    return
                }
            finishFlow(
                sessionId = sessionId,
                status = GameSessionResetStatus.STOPPED,
                terminalStage = GameSessionResetStage.READY_FOR_NEXT_ROUND,
                message = "会话重置完成，可以开始下一轮",
            )
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                finishFlow(sessionId, GameSessionResetStatus.STOPPED, stage, "会话重置已被停止")
            }
            throw cancelled
        } catch (error: Exception) {
            val detail = error.message?.takeIf(String::isNotBlank) ?: "未知错误"
            finishFlow(
                sessionId = sessionId,
                status = GameSessionResetStatus.ERROR,
                terminalStage = GameSessionResetStage.FAILED,
                message = if (detail.startsWith("会话重置")) detail else "会话重置失败：$detail",
            )
        }
    }

    private suspend fun terminateClient(sessionId: AutomationSessionId, activeDeadline: Long) {
        val label = when (backend.kind) {
            ClientTerminationKind.SESSION_EXPIRY -> "触发会话失效并返回标题页"
            else -> "关闭/退出公主连结客户端"
        }
        while (true) {
            awaitUnpaused(sessionId, activeDeadline)
            if (inFlight.compareAndSet(false, true)) break
            kotlinx.coroutines.delay(FRAME_POLL_MILLIS)
        }
        try {
            throwIfResetFailed()
            if (reconnectRecoveryRequested) {
                reconnectRecoveryRequested = false
                expiryRecoveryAttempts++
            }
            transitionTo(sessionId, GameSessionResetStage.TERMINATING, label)
        } finally {
            // The terminator needs fresh frames for popup and title recognition.
            inFlight.set(false)
        }

        val termination = backend.terminateClient()
        val terminationFailed = termination != ClientTerminationResult.TERMINATED &&
            termination != ClientTerminationResult.ALREADY_GONE
        val stepSuffix = if (terminationFailed) {
            backend.lastDiagnosticStep?.let { "（step=$it）" }.orEmpty()
        } else {
            ""
        }
        debugLog("terminate kind=${backend.kind} result=$termination$stepSuffix")
        if (terminationFailed) {
            error("终止客户端未确认完成：$termination$stepSuffix")
        }
    }

    private fun throwIfResetFailed() {
        val current = _state.value
        when (current.stage) {
            GameSessionResetStage.SESSION_BLOCKED -> error("会话被阻塞：${current.lastBlock}")
            GameSessionResetStage.FAILED -> error(current.message?.takeIf(String::isNotBlank) ?: "会话重置失败")
            else -> Unit
        }
    }

    private suspend fun awaitUnpaused(sessionId: AutomationSessionId, activeDeadline: Long) {
        while (activeSessionId == sessionId && _state.value.paused) {
            kotlinx.coroutines.delay(FRAME_POLL_MILLIS)
        }
        if (activeSessionId != sessionId) error("会话重置已被停止")
        throwIfResetFailed()
        if (activeClock() >= activeDeadline) error("会话重置超时")
    }

    /** Waits for one frame recognised with the client in the foreground. */
    private suspend fun awaitClientObserved(
        sessionId: AutomationSessionId,
        timeoutMillis: Long,
        activeDeadline: Long,
    ): Boolean {
        val deadline = minOf(activeClock() + timeoutMillis, activeDeadline)
        while (activeSessionId == sessionId) {
            val current = _state.value
            if (current.stage.isFailure) return clientObserved
            if (current.paused) {
                kotlinx.coroutines.delay(FRAME_POLL_MILLIS)
                continue
            }
            // A captured expiry popup is evidence enough while the foreground cache catches up.
            if (clientObserved || isExpiryReconnect(current.lastBlock)) return clientObserved
            if (activeClock() >= deadline) break
            kotlinx.coroutines.delay(FRAME_POLL_MILLIS)
        }
        if (activeSessionId != sessionId) error("会话重置已被停止")
        if (!clientObserved && activeClock() >= activeDeadline) error("会话重置超时")
        return clientObserved
    }

    private suspend fun awaitObservedPage(
        sessionId: AutomationSessionId,
        timeoutMillis: Long,
        activeDeadline: Long,
    ): LabyrinthEntryPageState {
        val deadline = minOf(activeClock() + timeoutMillis, activeDeadline)
        while (activeSessionId == sessionId) {
            val current = _state.value
            if (current.stage.isFailure) return lastObservedPage
            if (current.paused) {
                kotlinx.coroutines.delay(FRAME_POLL_MILLIS)
                continue
            }
            val page = lastObservedPage
            if (page != LabyrinthEntryPageState.UNKNOWN || isExpiryReconnect(current.lastBlock)) return page
            if (activeClock() >= deadline) break
            kotlinx.coroutines.delay(FRAME_POLL_MILLIS)
        }
        if (activeSessionId != sessionId) error("会话重置已被停止")
        if (lastObservedPage == LabyrinthEntryPageState.UNKNOWN && activeClock() >= activeDeadline) error("会话重置超时")
        return lastObservedPage
    }

    private suspend fun awaitStageReached(
        sessionId: AutomationSessionId,
        target: GameSessionResetStage,
        activeDeadline: Long,
        entryPlanner: LabyrinthEntryActionPlanner,
    ) {
        // 识别帧回调驱动推进；此处等待目标阶段被 onFrame 转换到达
        while (activeSessionId == sessionId && stage != target) {
            throwIfResetFailed()
            if (_state.value.paused) {
                kotlinx.coroutines.delay(FRAME_POLL_MILLIS)
                continue
            }
            if (activeClock() >= activeDeadline) {
                error("会话重置超时")
            }
            if (!captureActive()) {
                // Without frames nothing can reach the target; report why instead of idling until
                // the total timeout (for example a rotation that outlasted the capture grace).
                error(CaptureStateRegistry.lastStopReason() ?: "屏幕捕获已停止，请重新授权")
            }
            if (reconnectRecoveryRequested) {
                terminateClient(sessionId, activeDeadline)
                awaitUnpaused(sessionId, activeDeadline)
                entryPlanner.start(activeClock())
                transitionTo(sessionId, GameSessionResetStage.WAITING_LOGIN, "已从失效弹窗返回标题，等待重新登录")
                continue
            }
            pollChannelPrompts(sessionId)
            kotlinx.coroutines.delay(FRAME_POLL_MILLIS)
        }
        if (activeSessionId != sessionId) error("会话重置已被停止")
        if (stage != target) error("会话重置未到达目标阶段：$target")
    }

    /**
     * Channel SDK login dialogs are Android views drawn over the game, read through accessibility
     * rather than frames (a portrait SDK page never yields a landscape frame). Polled from the flow
     * loop while the reset waits for login, sharing [inFlight] with frame actions so the two never
     * tap at once.
     */
    private suspend fun pollChannelPrompts(sessionId: AutomationSessionId) {
        val assistant = channelPrompts ?: return
        if (stage != GameSessionResetStage.WAITING_LOGIN || _state.value.paused) return
        val now = activeClock()
        if (lastChannelPromptPollAt != Long.MIN_VALUE && now - lastChannelPromptPollAt < CHANNEL_PROMPT_POLL_MILLIS) return
        if (!inFlight.compareAndSet(false, true)) return
        try {
            lastChannelPromptPollAt = now
            val actions = SessionBoundChannelPromptActions(actionExecutor, sessionId)
            when (val step = assistant.step(now, actions)) {
                ChannelPromptStep.None -> channelPromptVisibleUntil = Long.MIN_VALUE
                is ChannelPromptStep.Seen -> {
                    channelPromptVisibleUntil = now + CHANNEL_PROMPT_HOLD_MILLIS
                    setMessage("渠道登录界面：${step.label}，确认中")
                }
                is ChannelPromptStep.Activated -> {
                    channelPromptVisibleUntil = now + CHANNEL_PROMPT_HOLD_MILLIS
                    _state.update { current ->
                        current.copy(
                            actionCount = current.actionCount + 1,
                            message = "渠道登录界面：${step.label}，已点击",
                        )
                    }
                    overlayCoordinator.update(sessionId, buildOverlayPresentation(sessionId))
                }
                is ChannelPromptStep.Exhausted -> markFailed(sessionId, step.reason)
            }
        } finally {
            inFlight.set(false)
        }
    }

    private fun onFrame(
        sessionId: AutomationSessionId,
        processor: (CapturedFrame) -> LabyrinthEntryFrameResult,
        entryPlanner: LabyrinthEntryActionPlanner,
        frame: CapturedFrame,
    ) {
        if (activeSessionId != sessionId || _state.value.paused) {
            recycle(frame)
            return
        }
        if (inFlight.get() || !frameProcessorMutex.tryLock()) {
            recycle(frame)
            return
        }
        val processed = try {
            if (activeSessionId != sessionId || _state.value.paused) {
                recycle(frame)
                return
            }
            runCatching { processor(frame) }
        } finally {
            frameProcessorMutex.unlock()
        }
        processed
            .onSuccess { result ->
                if (activeSessionId != sessionId || _state.value.paused) return@onSuccess
                val block = blockClassifier(result)
                _state.update { current ->
                    if (current.stage.isFailure) current else current.copy(
                        frameCount = current.frameCount + 1,
                        lastBlock = block,
                    )
                }
                debugFramePublisher?.let { publish ->
                    runCatching { publish(frame, _state.value, result, buildOverlayPresentation(sessionId)) }
                }
                handleObservation(
                    sessionId = sessionId,
                    result = result,
                    block = block,
                    frameWidth = frame.bitmap.width,
                    frameHeight = frame.bitmap.height,
                    entryPlanner = entryPlanner,
                )
            }
            .onFailure { error ->
                if (activeSessionId == sessionId) {
                    _state.update { it.copy(message = "识别失败：${error.message ?: "未知错误"}") }
                }
            }
            .also { recycle(frame) }
    }

    private fun handleObservation(
        sessionId: AutomationSessionId,
        result: LabyrinthEntryFrameResult,
        block: SessionBlockKind,
        frameWidth: Int,
        frameHeight: Int,
        entryPlanner: LabyrinthEntryActionPlanner,
    ) {
        if (!inFlight.compareAndSet(false, true)) return
        actionScope.launch {
            try {
                if (activeSessionId != sessionId || _state.value.paused) return@launch
                if (stage.isFailure) return@launch
                if (activeClock() < channelPromptVisibleUntil) {
                    // A white SDK dialog can also score as a session prompt; neither the block
                    // classifier nor the entry planner may act on it while the poll owns it.
                    return@launch
                }
                val expiryReconnect = isExpiryReconnect(block)
                if (expiryReconnect && stage in EXPIRY_RECOVERY_STAGES &&
                    expiryRecoveryAttempts < MAX_EXPIRY_RECOVERY_ATTEMPTS
                ) {
                    setMessage("检测到会话失效弹窗，准备点击返回标题重新登录")
                    reconnectRecoveryRequested = true
                    return@launch
                }
                val expectedExpiryPopup =
                    (backend.kind == ClientTerminationKind.SESSION_EXPIRY && stage == GameSessionResetStage.TERMINATING) ||
                        (stage == GameSessionResetStage.PENDING && expiryReconnect)
                if (block != SessionBlockKind.NONE && !expectedExpiryPopup) {
                    if (activeSessionId != sessionId || _state.value.paused) return@launch
                    stage = GameSessionResetStage.SESSION_BLOCKED
                    // A session prompt before the title of a hard-closed client most likely means
                    // the old process is still there and just met 6002.
                    val beforeColdStartTitle = coldStartGate?.titleSeen == false
                    _state.update {
                        it.copy(
                            stage = GameSessionResetStage.SESSION_BLOCKED,
                            lastBlock = block,
                            message = if (beforeColdStartTitle) {
                                "会话被阻塞：$block（冷启动尚未出现标题页，可能大退未生效、游戏进程仍在）"
                            } else {
                                "会话被阻塞：$block"
                            },
                        )
                    }
                    onBlocked(sessionId, block)
                    return@launch
                }
                if (reconnectRecoveryRequested || stage == GameSessionResetStage.TERMINATING) return@launch
                if (!presence.isGameForeground()) {
                    _state.update { it.copy(message = "等待公主连结回到前台") }
                    return@launch
                }
                if (activeSessionId != sessionId || _state.value.paused) return@launch

                val pageState = result.observation.state
                lastObservedPage = pageState
                clientObserved = true
                if (stage == GameSessionResetStage.PENDING) {
                    // The flow is still deciding whether termination is needed; look, never act.
                    _state.update { it.copy(message = "识别客户端当前页面：${pageState.name}") }
                    return@launch
                }
                coldStartGate?.let { gate ->
                    when (gate.observe(pageState)) {
                        ColdStartVerdict.CONFIRMED -> Unit
                        ColdStartVerdict.PENDING -> if (pageState != LabyrinthEntryPageState.UNKNOWN &&
                            pageState != LabyrinthEntryPageState.GAME_LOADING_PROGRESS
                        ) {
                            _state.update { it.copy(message = "确认冷启动：等待标题页（当前 ${pageState.name}）") }
                            return@launch
                        }
                        ColdStartVerdict.CLIENT_SURVIVED -> {
                            markFailed(
                                sessionId,
                                "大退未生效：冷启动后先出现 ${pageState.name} 而不是标题页，游戏进程仍在；" +
                                    "已停止，避免接管旧会话导致渠道账号登出",
                            )
                            return@launch
                        }
                    }
                }
                if (activeSessionId != sessionId || _state.value.paused) return@launch
                // While the terminator is still driving the client (TERMINATING / RELAUNCHING) the
                // screen is by definition the *old* labyrinth home; recognising it as "ready" here
                // would let the entry planner tap 出发 on stale state. Only frames observed after
                // the flow has moved into the login/navigation phase may complete the reset.
                when (gameSessionResetReadyDecision(stage, pageState)) {
                    GameSessionResetReadyDecision.HOLD_FOR_TERMINATION -> {
                        _state.update { it.copy(message = "等待旧会话失效（当前页面 ${pageState.name}）") }
                        return@launch
                    }
                    GameSessionResetReadyDecision.READY -> {
                        markReady(sessionId, "已回到黎明界，可以开始下一轮")
                        return@launch
                    }
                    GameSessionResetReadyDecision.CONTINUE -> Unit
                }
                if (
                    pageState == LabyrinthEntryPageState.INITIAL_CHARACTER_SELECTION ||
                    pageState == LabyrinthEntryPageState.CHARACTER_JOINED
                ) {
                    markFailed(sessionId, "会话重置意外进入开局角色流程，已停止避免误操作")
                    return@launch
                }
                if (activeSessionId != sessionId || _state.value.paused) return@launch

                updateNavigationStage(pageState)
                when (
                    val decision = entryPlanner.decide(
                        state = pageState,
                        frameWidth = frameWidth,
                        frameHeight = frameHeight,
                        nowMillis = activeClock(),
                        anchorScores = result.observation.anchorScores,
                        anchorMatches = result.anchorMatches,
                    )
                ) {
                    is LabyrinthEntryActionDecision.Execute -> {
                        val executor = actionExecutor
                            ?: run {
                                markFailed(sessionId, "入口动作执行器未配置")
                                return@launch
                            }
                        when (val executed = executor.execute(sessionId, decision.action)) {
                            is AutomationActionResult.Executed,
                            is AutomationActionResult.DryRun,
                            -> {
                                _state.update { current ->
                                    current.copy(
                                        actionCount = current.actionCount + 1,
                                        message = decision.label,
                                    )
                                }
                                overlayCoordinator.update(sessionId, buildOverlayPresentation(sessionId))
                            }
                            is AutomationActionResult.Rejected ->
                                markFailed(sessionId, "动作被拒绝：${executed.reason}")
                            AutomationActionResult.StaleSession ->
                                markFailed(sessionId, "动作会话已失效")
                            AutomationActionResult.Paused ->
                                _state.update { it.copy(message = "会话重置已暂停") }
                        }
                    }
                    is LabyrinthEntryActionDecision.Wait -> {
                        stableStageFrames++
                        _state.update { it.copy(message = decision.reason) }
                    }
                    is LabyrinthEntryActionDecision.Complete ->
                        if (activeSessionId == sessionId && !_state.value.paused) {
                            markReady(sessionId, decision.reason)
                        }
                    is LabyrinthEntryActionDecision.Stop ->
                        if (activeSessionId == sessionId && !_state.value.paused) {
                            markFailed(sessionId, decision.reason)
                        }
                }
            } finally {
                inFlight.set(false)
            }
        }
    }

    private fun updateNavigationStage(pageState: LabyrinthEntryPageState) {
        val next = when (pageState) {
            LabyrinthEntryPageState.HOME -> GameSessionResetStage.NAVIGATING_HOME
            LabyrinthEntryPageState.ADVENTURE -> GameSessionResetStage.NAVIGATING_ADVENTURE
            LabyrinthEntryPageState.DAWN_REALM_HOME_IDLE,
            LabyrinthEntryPageState.DAWN_REALM_HOME_ACTIVE,
            -> GameSessionResetStage.NAVIGATING_DAWN_REALM
            else -> GameSessionResetStage.WAITING_LOGIN
        }
        if (stage != next) {
            stage = next
            stableStageFrames = 0
            lastStageChangeAt = activeClock()
            _state.update { it.copy(stage = next) }
        }
    }

    private fun markReady(sessionId: AutomationSessionId, message: String) {
        stage = GameSessionResetStage.READY_FOR_NEXT_ROUND
        _state.update {
            it.copy(
                stage = GameSessionResetStage.READY_FOR_NEXT_ROUND,
                message = message,
            )
        }
        overlayCoordinator.update(sessionId, buildOverlayPresentation(sessionId))
    }

    private fun markFailed(sessionId: AutomationSessionId, message: String) {
        stage = GameSessionResetStage.FAILED
        _state.update {
            it.copy(
                stage = GameSessionResetStage.FAILED,
                message = message,
            )
        }
        overlayCoordinator.update(sessionId, buildOverlayPresentation(sessionId))
    }

    private suspend fun transitionTo(
        sessionId: AutomationSessionId,
        stage: GameSessionResetStage,
        message: String,
    ) {
        this.stage = stage
        stableStageFrames = 0
        lastStageChangeAt = activeClock()
        _state.update { it.copy(stage = stage, message = message) }
        overlayCoordinator.update(sessionId, buildOverlayPresentation(sessionId))
    }

    private fun setMessage(message: String) {
        _state.update { it.copy(message = message) }
    }

    private suspend fun finishFlow(
        sessionId: AutomationSessionId,
        status: GameSessionResetStatus,
        terminalStage: GameSessionResetStage,
        message: String,
    ) = mutex.withLock {
        finishFlowLocked(sessionId, status, terminalStage, message)
    }

    private suspend fun finishFlowLocked(
        sessionId: AutomationSessionId,
        status: GameSessionResetStatus,
        terminalStage: GameSessionResetStage,
        message: String,
    ): Boolean {
        if (activeSessionId != sessionId) return false
        activeSessionId = null
        resetFlowJob = null
        pauseStartedAt = Long.MIN_VALUE
        pausedDurationMillis = 0L
        inFlight.set(false)
        lease?.let(CaptureFrameBus::unregister)
        lease = null
        val stopped = sessionManager.stop(sessionId)
        overlayCoordinator.detach(sessionId)
        stage = terminalStage
        _state.update {
            it.copy(
                status = status,
                sessionId = null,
                stage = terminalStage,
                paused = false,
                message = message,
            )
        }
        return stopped
    }

    private fun buildOverlayPresentation(sessionId: AutomationSessionId): AutomationOverlayPresentation {
        val current = _state.value
        return AutomationOverlayPresentation(
            sessionId = sessionId,
            title = "游戏会话重置",
            status = when (current.paused) {
                true -> "已暂停"
                else -> "运行中"
            },
            detail = buildList {
                add("阶段：${current.stage}")
                add("帧：${current.frameCount}")
                add("动作：${current.actionCount}")
                current.message?.let { add(it) }
            }.joinToString("\n"),
            dryRun = false,
            paused = current.paused,
        )
    }

    private fun recycle(frame: CapturedFrame) {
        if (!frame.bitmap.isRecycled) frame.bitmap.recycle()
    }

    private companion object {
        const val LOG_TAG = "LabyrinthReset"
        const val OWNER = "game-session-reset"
        const val FRAME_POLL_MILLIS = 250L
        const val CHANNEL_PROMPT_POLL_MILLIS = 500L
        /** How long one sighting keeps frame actions away from the dialog; > one poll interval. */
        const val CHANNEL_PROMPT_HOLD_MILLIS = 1_500L
        /** How long the PENDING peek waits for a recognised page before defaulting to termination. */
        const val PAGE_PEEK_TIMEOUT_MILLIS = 6_000L

/**
 * How long a round waits for the client to reach the foreground before deciding there is no
 * session to invalidate. Long enough to cover a cold start, well inside the flow's total budget.
 */
const val CLIENT_PRESENCE_TIMEOUT_MILLIS = 20_000L
        const val DEFAULT_TOTAL_TIMEOUT_MILLIS = 180_000L
        const val MAX_EXPIRY_RECOVERY_ATTEMPTS = 2
        val EXPIRY_RECOVERY_STAGES = setOf(
            GameSessionResetStage.RELAUNCHING,
            GameSessionResetStage.WAITING_LOGIN,
            GameSessionResetStage.NAVIGATING_HOME,
            GameSessionResetStage.NAVIGATING_ADVENTURE,
            GameSessionResetStage.NAVIGATING_DAWN_REALM,
        )
    }
}
