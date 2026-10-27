package com.landosol.toolbox.labyrinth
import android.util.Log
import com.landosol.toolbox.automation.AutomationMode
import com.landosol.toolbox.automation.AutomationSessionId
import com.landosol.toolbox.automation.AutomationSessionManager
import com.landosol.toolbox.automation.AutomationSessionStartResult
import com.landosol.toolbox.automation.SessionBoundActionExecutor
import com.landosol.toolbox.automation.capture.CaptureFrameBus
import com.landosol.toolbox.automation.capture.CaptureFrameConsumerLease
import com.landosol.toolbox.automation.capture.CaptureFrameRegistrationResult
import com.landosol.toolbox.automation.capture.CaptureStateRegistry
import com.landosol.toolbox.automation.capture.CapturedFrame
import com.landosol.toolbox.automation.overlay.AutomationOverlayBox
import com.landosol.toolbox.automation.overlay.AutomationOverlayCoordinator
import com.landosol.toolbox.automation.overlay.AutomationOverlayPresentation
import com.landosol.toolbox.automation.overlay.AutomationOverlaySessionHandler
import com.landosol.toolbox.automation.AutomationAction
import com.landosol.toolbox.automation.session.SessionBlockClassifier
import com.landosol.toolbox.automation.session.SessionBlockKind
import com.landosol.toolbox.labyrinth.vision.EntryPixelRect
import com.landosol.toolbox.labyrinth.vision.LabyrinthBattleElementFilter
import com.landosol.toolbox.labyrinth.vision.LabyrinthEntryFrameResult
import com.landosol.toolbox.labyrinth.vision.LabyrinthCharacterMatch
import com.landosol.toolbox.labyrinth.vision.LabyrinthEntryPageState
import com.landosol.toolbox.labyrinth.vision.LabyrinthBattleTeamObservation
import com.landosol.toolbox.labyrinth.vision.LabyrinthRelicMatch
import com.landosol.toolbox.labyrinth.vision.LabyrinthShopDialogState
import com.landosol.toolbox.labyrinth.node.LabyrinthNodeSession
import com.landosol.toolbox.labyrinth.node.LabyrinthNodeActionPlanner
import com.landosol.toolbox.labyrinth.node.LabyrinthNodeViewportScanner
import com.landosol.toolbox.labyrinth.node.NodeSearchHint
import com.landosol.toolbox.labyrinth.node.NodeAction
import com.landosol.toolbox.labyrinth.batch.LabyrinthRunTerminalEvent
import com.landosol.toolbox.labyrinth.node.NodeClassification
import com.landosol.toolbox.labyrinth.node.NodeDebugLogger
import com.landosol.toolbox.labyrinth.node.NodeSessionState
import com.landosol.toolbox.labyrinth.node.NodeTemplateSet
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.landosol.toolbox.labyrinth.LabyrinthExecutionLimits.FIRST_FRAME_TIMEOUT_MILLIS
import com.landosol.toolbox.labyrinth.LabyrinthExecutionLimits.FRAME_INTERVAL_MILLIS
import com.landosol.toolbox.labyrinth.LabyrinthExecutionLimits.NODE_LOG_TAG
import com.landosol.toolbox.labyrinth.LabyrinthExecutionLimits.OWNER

class LabyrinthEntryRecognitionSession(
    private val sessionManager: AutomationSessionManager,
    internal val captureActive: () -> Boolean = CaptureStateRegistry::isActive,
    private val captureStop: () -> Unit = {},
    private val processorFactory: (finalBossOnly: () -> Boolean) -> (CapturedFrame) -> LabyrinthEntryFrameResult,
    private val overlayCoordinator: AutomationOverlayCoordinator = AutomationOverlayCoordinator(),
    internal val actionExecutor: SessionBoundActionExecutor? = null,
    private val actionsAvailable: () -> Boolean = { actionExecutor != null },
    private val actionTargetReady: () -> Boolean = { true },
    private val gameLauncher: () -> Boolean = { true },
    internal val actionPlannerFactory: () -> LabyrinthEntryActionPlanner = { LabyrinthEntryActionPlanner() },
    internal var relicChoicePolicy: LabyrinthRelicChoicePolicy = LabyrinthRelicChoicePolicy(),
    internal var shopPolicy: LabyrinthShopPolicy = LabyrinthShopPolicy(relicChoicePolicy),
    internal var roleRewardChoicePlanner: LabyrinthRoleRewardChoicePlanner? = null,
    internal val roleRewardChoiceUnavailableReason: String? = null,
    internal var eventChoicePlanner: LabyrinthEventChoicePlanner? = null,
    internal var battleTeamRecommendationPlanner: LabyrinthBattleTeamRecommendationPlanner? = null,
    internal val battleTeamRecommendationUnavailableReason: String? = null,
    internal var battleTeamSelectionPlanner: LabyrinthBattleTeamSelectionPlanner? = null,
    internal val runStateStore: LabyrinthRunStateStore? = null,
    internal val routeLoader: (suspend (Long?) -> LabyrinthRouteJson?)? = null,
    internal val routeProgressSaver: (suspend (Long, Long, Long) -> Boolean)? = null,
    private val routeExecutionGate: (suspend (Long?) -> LabyrinthExecutionGateResult)? = null,
    internal val nodeTemplateLoader: (() -> NodeTemplateSet)? = null,
    internal val nodeSessionFactory: () -> LabyrinthNodeSession = {
        LabyrinthNodeSession(
            debugLogger = NodeDebugLogger { message ->
                Log.d(NODE_LOG_TAG, "node-mapping $message")
            },
        )
    },
    private val debugFramePublisher: ((CapturedFrame, LabyrinthEntryRecognitionSessionState, LabyrinthEntryFrameResult, AutomationOverlayPresentation) -> Unit)? = null,
    internal val clock: () -> Long = System::currentTimeMillis,
    private val strategyProvider: (() -> LabyrinthStrategySnapshot)? = null,
    internal val rerollRequester: (suspend (Long) -> Boolean)? = null,
    /**
     * Formal run outcome for an outer batch orchestrator (labyrinth-full-automation.md §11).
     * Fired at most once per run id, after the run has fully stopped. Absent runId = no batch.
     */
    internal val runTerminalListener: (suspend (LabyrinthRunTerminalEvent) -> Unit)? = null,
    /** Channel SDK login dialogs (九游) on the way from the title, read via accessibility. */
    internal val channelPrompts: com.landosol.toolbox.automation.session.ChannelLoginPromptAssistant? = null,
    internal val actionScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {


    private data class QueuedFrame(
        val sessionId: AutomationSessionId,
        val processor: (CapturedFrame) -> LabyrinthEntryFrameResult,
        val frame: CapturedFrame,
    )

    private val mutex = Mutex()

    internal val persistenceMutex = Mutex()

    internal val actionInFlight = AtomicBoolean(false)

    private val stopRequested = AtomicBoolean(false)

    /**
     * Frames observed before this time belong to a channel SDK login dialog that the accessibility
     * poll owns. Without the hold the entry planner reads such a dialog as an UNKNOWN
     * pre-announcement frame and keeps tapping (950,930) outside it (2026-10-05 九游 debug bundle,
     * 2026-10-12 diag), and the session-block path's 返回标题 point (961,739) can land on it.
     */
    @Volatile
    internal var channelPromptVisibleUntil = Long.MIN_VALUE

    @Volatile
    internal var channelPromptMessage: String? = null

    private val queuedFrame = AtomicReference<QueuedFrame?>(null)

    private val frameWorkerRunning = AtomicBoolean(false)

    internal val actionPlannerLock = Any()

    internal val nodeActionPlanner = LabyrinthNodeActionPlanner()

    internal val nodeViewportScanner = LabyrinthNodeViewportScanner()

    internal val sessionBlockClassifier = SessionBlockClassifier()

    internal val _state = MutableStateFlow(LabyrinthEntryRecognitionSessionState())

    val state: StateFlow<LabyrinthEntryRecognitionSessionState> = _state.asStateFlow()

    fun nodeScanRequested(): Boolean =
        mapNavigationWorkflow.nodeScanRequested()

    fun nodeScanConsumable(): Boolean =
        mapNavigationWorkflow.nodeScanConsumable()

    /**
     * Roles this run has acquired, as canonical ids — the only roles a team page can show.
     *
     * Empty when the run has not established a roster yet, which the matcher reads as "no
     * restriction" rather than "no candidates".
     */
    fun rosterCharacterIds(): Set<String> = _state.value.joinedCharacters
        .map(LabyrinthJoinedCharacter::characterId)
        .toSet()

    fun currentNodeSearchHint(): NodeSearchHint? =
        mapNavigationWorkflow.currentNodeSearchHint()

    @Volatile
    internal var activeSessionId: AutomationSessionId? = null

    @Volatile
    internal var activeRunAccountId: Long? = null

    private var lease: CaptureFrameConsumerLease? = null

    internal var firstFrameWatchdog: Job? = null

    private var lastProcessedAt = Long.MIN_VALUE

    internal var actionPlanner: LabyrinthEntryActionPlanner? = null

    @Volatile
    internal var validatedRoute: LabyrinthRouteJson? = null

    internal val openingStateReset = AtomicBoolean(false)

    @Volatile
    internal var lastPersistedObservationSignature: String? = null

    // Compatibility backing field for existing reflection callers; recommendation owns mutations.
    @Volatile
    internal var lastBattleTeamRecommendationKey: String? = null

    // Compatibility backing field for existing reflection callers; team execution owns mutations.
    @Volatile
    internal var lastBattleTeamExecutionKey: String? = null

    internal val battleRosterSearch = LabyrinthBattleRosterSearch()

    /** Attribute tabs already scanned to the end for the current set of still-missing roles. */
    internal val exhaustedBattleRosterFilters = linkedSetOf<com.landosol.toolbox.labyrinth.vision.LabyrinthBattleElementFilter>()

    internal var exhaustedBattleRosterFiltersKey: String? = null

    /**
     * 会话级动作节流：本会话**任意**两次点击之间强制 [BATTLE_TEAM_ACTION_INTERVAL_MILLIS]。
     *
     * 「特别装备」流程与编组/开战路径各有自己的执行跟踪，但游戏只有一个输入口。两者各持一个时间戳时，
     * 弹窗「取消」刚点完 0.76s，编组路径只看到自己的旧时间戳就派发「战斗开始」，被关闭动画吃掉；
     * 所以这两个流程共用这一个时间戳：任一方派发点击都盖上它，任一方派发前都要等它过期。
     */
    @Volatile
    internal var lastSessionActionAt = Long.MIN_VALUE

    // Compatibility backing field for existing reflection callers; team execution owns mutations.
    @Volatile
    internal var pendingBattleTeamAction: PendingBattleTeamAction? = null

    /** 连续多少次「编组页动作派发后画面没动」；画面前进一次或整页重来即清零。 */
    // Compatibility backing field for existing reflection callers; team execution owns mutations.
    @Volatile
    internal var battleTeamActionUnconfirmedAttempts = 0

    internal val battleTeamDispatchSequence = AtomicLong()

    /**
     * 「特别装备」自动穿戴的本轮状态（纯状态机见 [labyrinthSpecialEquipmentDecision]）。
     *
     * **刻意不放进 [resetBattleTeamExecutionTracking]**：那个函数会在「页面状态变成编组页」时被调用
     * （`handlePostEntryPage` 里进入 BATTLE_TEAM_SELECTION 的那一支），而弹窗开合本身就会让页面
     * 状态抖动，放进去会让本轮标记被抖掉、流程半途重来。这里的标记只在「稳定地离开编组页」时清零。
     */
    @Volatile
    private var specialEquipmentFlowStage = LabyrinthSpecialEquipmentFlowStage.INACTIVE

    @Volatile
    private var specialEquipmentStepAttempts = 0

    @Volatile
    private var specialEquipmentStepStartedAt = Long.MIN_VALUE

    /** 本轮是否真的看到过弹窗（stage 非空）；决定「弹窗刚关」这一帧要不要让给关闭动画。 */
    @Volatile
    private var specialEquipmentDialogObserved = false

    /**
     * 「特别装备弹窗刚关闭」这一帧已经交给编组路径、但还没被让掉。
     *
     * 弹窗关闭动画期间点在「战斗开始」上会被游戏吞掉，所以关闭后的第一帧只让一帧：
     * 由 [handleBattleTeamSelectionExecution] 消费，消费的那一帧不派发任何编组/开战点击。
     */
    @Volatile
    private var specialEquipmentModalCloseSettlePending = false

    /** 连续多少帧落在非编组页（UNKNOWN 不累计）；达到 [SPECIAL_EQUIPMENT_LEAVE_STABLE_FRAMES] 才算离开。 */
    @Volatile
    private var specialEquipmentAwayFrames = 0

    /**
     * 超限逃生那次点击（点弹窗1 左下白色「取消」）已经决定、但还没派发出去时的待办。
     *
     * 决策只在「超限的那一帧」带 tap，而 stage 那时已经推进到 DONE，后续帧的 DONE 决策不再带 tap；
     * 没有这个待办字段，逃生点击会被动作间隔或帧序吞掉：表现为「只被决定、从未点击」。
     * 待办本身也有窗口（[specialEquipmentEscapeDeadline]）：窗口内始终派发不出去就放弃这次点击，
     * 由「本轮已结束但弹窗仍开着」的分支停机并保留诊断。
     */
    @Volatile
    private var specialEquipmentPendingEscape: LabyrinthSpecialEquipmentTap? = null

    /** 逃生点击派发后的等弹窗关闭上限；超期仍未关就停机并保留诊断。 */
    @Volatile
    private var specialEquipmentEscapeDeadline = Long.MIN_VALUE

    /** 超限/放弃时的诊断原文：后续帧不得用「本轮已结束，继续编组与开战」把它覆盖掉。 */
    @Volatile
    private var specialEquipmentDiagnostic: String? = null

    /**
     * 「装备确定」两态判定的连续同类证据计数（跨帧稳定）：单帧（尤其是弹窗切换的过渡帧）不下结论。
     * 由 [labyrinthSpecialEquipmentEquipEvidenceStreak] 每帧推进，`confirmedEnabled` 才交给决策。
     */
    @Volatile
    private var specialEquipmentEquipEvidence = LabyrinthSpecialEquipmentEquipEvidenceStreak()

    /**
     * 本轮是不是**放弃**收尾（超限、或入口缺失跳过）。
     *
     * 它只影响停机文案：既可能因为「超限放弃 + 弹窗没关」停机，也可能因为「本轮已正常结束 + 弹窗
     * 又被打开」停机，两者不能共用一句「已放弃本轮」。
     */
    @Volatile
    private var specialEquipmentAbandoned = false

    /**
     * 本轮武装时所在的队伍页签（非「Boss 多队编组页的页签」一律为 null）。
     *
     * Boss 多队编组页上的每个页签是一支独立的队伍，所以页签是「一轮」的身份之一：页签一变，上一个
     * 页签的结束标记不属于当前队伍，必须重新武装，否则队伍2 会沿用队伍1 的标记而永不穿戴。
     */
    @Volatile
    private var specialEquipmentRoundBossTeamIndex: Int? = null

    private val specialEquipmentState = object : LabyrinthSpecialEquipmentState {
        override var specialEquipmentFlowStage: LabyrinthSpecialEquipmentFlowStage
            get() = this@LabyrinthEntryRecognitionSession.specialEquipmentFlowStage
            set(value) { this@LabyrinthEntryRecognitionSession.specialEquipmentFlowStage = value }
        override var specialEquipmentStepAttempts: Int
            get() = this@LabyrinthEntryRecognitionSession.specialEquipmentStepAttempts
            set(value) { this@LabyrinthEntryRecognitionSession.specialEquipmentStepAttempts = value }
        override var specialEquipmentStepStartedAt: Long
            get() = this@LabyrinthEntryRecognitionSession.specialEquipmentStepStartedAt
            set(value) { this@LabyrinthEntryRecognitionSession.specialEquipmentStepStartedAt = value }
        override var specialEquipmentDialogObserved: Boolean
            get() = this@LabyrinthEntryRecognitionSession.specialEquipmentDialogObserved
            set(value) { this@LabyrinthEntryRecognitionSession.specialEquipmentDialogObserved = value }
        override var specialEquipmentModalCloseSettlePending: Boolean
            get() = this@LabyrinthEntryRecognitionSession.specialEquipmentModalCloseSettlePending
            set(value) { this@LabyrinthEntryRecognitionSession.specialEquipmentModalCloseSettlePending = value }
        override var specialEquipmentAwayFrames: Int
            get() = this@LabyrinthEntryRecognitionSession.specialEquipmentAwayFrames
            set(value) { this@LabyrinthEntryRecognitionSession.specialEquipmentAwayFrames = value }
        override var specialEquipmentPendingEscape: LabyrinthSpecialEquipmentTap?
            get() = this@LabyrinthEntryRecognitionSession.specialEquipmentPendingEscape
            set(value) { this@LabyrinthEntryRecognitionSession.specialEquipmentPendingEscape = value }
        override var specialEquipmentEscapeDeadline: Long
            get() = this@LabyrinthEntryRecognitionSession.specialEquipmentEscapeDeadline
            set(value) { this@LabyrinthEntryRecognitionSession.specialEquipmentEscapeDeadline = value }
        override var specialEquipmentDiagnostic: String?
            get() = this@LabyrinthEntryRecognitionSession.specialEquipmentDiagnostic
            set(value) { this@LabyrinthEntryRecognitionSession.specialEquipmentDiagnostic = value }
        override var specialEquipmentEquipEvidence: LabyrinthSpecialEquipmentEquipEvidenceStreak
            get() = this@LabyrinthEntryRecognitionSession.specialEquipmentEquipEvidence
            set(value) { this@LabyrinthEntryRecognitionSession.specialEquipmentEquipEvidence = value }
        override var specialEquipmentAbandoned: Boolean
            get() = this@LabyrinthEntryRecognitionSession.specialEquipmentAbandoned
            set(value) { this@LabyrinthEntryRecognitionSession.specialEquipmentAbandoned = value }
        override var specialEquipmentRoundBossTeamIndex: Int?
            get() = this@LabyrinthEntryRecognitionSession.specialEquipmentRoundBossTeamIndex
            set(value) { this@LabyrinthEntryRecognitionSession.specialEquipmentRoundBossTeamIndex = value }
    }

    internal val specialEquipmentFlow = LabyrinthSpecialEquipmentFlow(specialEquipmentState)

    internal val committedBattleCharacterIds = linkedSetOf<String>()

    /** Teams actually committed during the current combat attempt, grouped by team signature. */
    internal val currentBattleTeamSignatures = linkedSetOf<String>()

    /**
     * 上一场**普通**关卡的编组签名（[labyrinthBattleTeamSignature]）。
     *
     * 只有普通关卡的 START_BATTLE 会把它更新为本场编组；EX / Boss 的战斗不会改变它（回收时写回的只是
     * 当次捕获的原值）。刻意不放进 [resetBattleTeamExecutionTracking] 与单场重置：它是跨战斗的记忆，
     * 本轮普通关卡若与它完全相同就跳过特别装备流程。
     */
    @Volatile
    internal var lastNormalBattleTeamSignature: String? = null

    /** Exact team signatures observed in failed attempts for this node. Never submit them again. */
    internal val failedBattleTeamSignatures = linkedSetOf<String>()

    @Volatile
    internal var battleRetryCount = 0

    internal var bossEditorTeamIndex: Int? = null

    internal var pendingBossTeamAdvance: Pair<Int, List<String>>? = null

    internal var bossEditorBlocked = false

    internal var bossEditorPreparationStage = LabyrinthBossEditorPreparationStage.INACTIVE

    internal var preparedBossFirstTeamIds: List<String> = emptyList()

    /** Chosen safe team count for the current Boss multi-team attempt; 1/2 teams leave later tabs empty. */
    internal var bossMultiTeamTargetCount: Int? = null

    internal var configuredBossTeamMode: LabyrinthBossTeamMode = LabyrinthBossTeamMode.MULTI_TEAM

    /** User-configured opening picks per guild; empty means every guild keeps its shipped plan. */
    internal var configuredOpeningRosters: Map<Int, List<List<String>>> = emptyMap()

    /** Names for ids an override may reference, so opening progress messages stay readable. */
    internal var roleDisplayNames: Map<String, String> = emptyMap()

    internal var bossTeamMode: LabyrinthBossTeamMode = LabyrinthBossTeamMode.MULTI_TEAM

    internal var preferPureBossDamageSystem: Boolean = true

    /**
     * 启动时抓取的设置快照，只用来回答「本次战斗的关卡类型要不要自动穿戴特别装备」：
     * 运行中保存新配置不改变本轮，只影响下一次启动。
     */
    internal var specialEquipmentSettings: LabyrinthStrategySettings? = null

    /**
     * 本次启动的识别节拍，来自设置快照的 `responseIntervalMillis`；运行中保存只影响下一次启动。
     *
     * 与 [FRAME_INTERVAL_MILLIS] 的关系：后者是上游默认值，这里在 [start] 里用它做初值，
     * 快照存在时被覆盖。采集每 300 ms 一帧，所以只有 300（每帧）与 500/600（每 2 帧）
     * 两档真实生效。
     */
    internal var responseIntervalMillis: Long = FRAME_INTERVAL_MILLIS

    internal var singleBossFallbackToMultiAfterThreeFailures: Boolean = false

    internal var singleBossRetryCountBeforeMulti: Int = 2

    internal var pendingSingleBossFallbackToMulti: Boolean = false

    /**
     * True once the single-team Boss attempts were exhausted and the editor was switched to the
     * temporary multi-team mode. Every team recommendation for this Boss then uses the relaxed
     * vanguard gate; otherwise a roster with one real tank silently plans one team again.
     */
    internal var bossFallbackMultiTeamActive: Boolean = false

    internal var rerollAfterThreeBattleFailures: Boolean = false

    /** Batch-assigned identity of the current run; null when started interactively. */
    @Volatile
    internal var currentRunId: String? = null

    /** Set by the terminal paths before stop() so stop() can publish the right event. */
    @Volatile
    internal var pendingTerminalOutcome: LabyrinthRunTerminalOutcome? = null

    @Volatile
    internal var lastObservedPageState: LabyrinthEntryPageState? = null

    // Published to the frame processor; reward workflow owns updates through its port.
    @Volatile
    var skipRoleRewardJoinedRecognition: Boolean = false
        internal set

    @Volatile
    internal var pendingShopPurchase: LabyrinthRelicMatch? = null

    @Volatile
    internal var plannedShopPurchaseRelicId: String? = null

    @Volatile
    internal var plannedShopRoleImprintLabel: String? = null

    /** Set when a purchased 选择印记 still owes us a character pick. */
    @Volatile
    internal var shopChoiceImprintRolePending = false

    @Volatile
    internal var shopImprintRewardConfirmedAt = Long.MIN_VALUE

    @Volatile
    internal var plannedShopPurchaseStartedAt = Long.MIN_VALUE

    @Volatile
    internal var plannedShopPurchaseCounted = false

    @Volatile
    internal var plannedShopRefreshStartedAt = Long.MIN_VALUE

    @Volatile
    internal var plannedShopRefreshConfirmedAt = Long.MIN_VALUE

    @Volatile
    internal var shopRelicPurchasesThisCycle = 0

    @Volatile
    internal var eventChoiceCommitted = false

    internal var eventChoiceCommittedAt = Long.MIN_VALUE

    internal var eventChoiceCommitAttempts = 0

    /** The choice whose tap is currently being waited on, so a refusal can be attributed to it. */
    internal var eventChoiceCommittedChoiceId: String? = null

    /** Choices tapped on this event visit that left the page unchanged; see the planner's KDoc. */
    internal val eventChoiceRejectedIds = linkedSetOf<String>()

    @Volatile
    internal var relicFocusMark: LabyrinthRelicMark? = null

    /** Latest game-visible "当前：N" values. These override history-derived stacks when known. */
    internal val relicStackLedger = LabyrinthRelicStackLedger()

    internal val relicMarkStackCalibration get() = relicStackLedger.stacks

    /** A single OCR read is evidence, not truth; require repeated/same-frame corroboration. */
    @Volatile
    internal var lastShopDialogState: LabyrinthShopDialogState = LabyrinthShopDialogState.NONE

    internal val nodeConflictRecovery = com.landosol.toolbox.labyrinth.node.LabyrinthNodeConflictRecovery()

    @Volatile
    internal var nodeSession: LabyrinthNodeSession? = null

    internal val nodeInitStarted = AtomicBoolean(false)

    @Volatile
    internal var nodeInitFailedReason: String? = null

    @Volatile
    internal var entryPhaseComplete = false

    @Volatile
    internal var lastNodeSwipeAvoidRects: List<EntryPixelRect> = emptyList()

    @Volatile
    internal var lastNodeActionAt = Long.MIN_VALUE

    @Volatile
    internal var nodeTapAttempts = 0

    @Volatile
    internal var routeNodeCount = 0

    @Volatile
    internal var lastPostEntryState: LabyrinthEntryPageState? = null

    @Volatile
    internal var postEntryStableFrames = 0

    @Volatile
    internal var postEntryAttempts = 0

    @Volatile
    internal var lastPostEntryActionAt = Long.MIN_VALUE

    /** Wall-clock time the last post-entry tap was reported executed; see [labyrinthPostEntryCooldownRemaining]. */
    @Volatile
    internal var lastPostEntryActionLandedAt = Long.MIN_VALUE

    @Volatile
    internal var postEntryUnknownSince = Long.MIN_VALUE

    /** When the single-action latch was claimed; see [beginGuardedAction]. */
    @Volatile
    internal var actionInFlightSince = Long.MIN_VALUE

    /** Start of the current no-action, no-page-change, no-message-change stretch. */
    @Volatile
    internal var postEntryStallSince = Long.MIN_VALUE

    /** First frame of an uninterrupted relic-detail popup; that popup is a human's to close. */
    @Volatile
    internal var relicDetailHoldSince = Long.MIN_VALUE

    @Volatile
    internal var postEntryStallMessage: String? = null

    internal val battleWait = LabyrinthBattleWaitPolicy()

    @Volatile
    internal var existingRunResumeHandoffArmed = false

    @Volatile
    internal var postBossStage = LabyrinthPostBossStage.NONE

    @Volatile
    internal var postBossUnknownAttempts = 0

    @Volatile
    internal var postBossStartedAt = Long.MIN_VALUE

    @Volatile
    internal var combatContext: LabyrinthCombatContext? = null

    @Volatile
    internal var currentExEncounterStrategy: LabyrinthExEncounterStrategy? = null

    /**
     * An EX detail probe may reliably confirm that this is an EX encounter while its monster name
     * has no entry in the local guide catalog.  That must not turn a playable challenge into a
     * terminal batch failure: after closing the automation-owned detail modal, this flag permits
     * the normal EX team-selection and challenge flow without claiming that a dedicated guide
     * exists.  It is reset for every new route node/session.
     */
    @Volatile
    internal var exEncounterFallbackApproved = false

    @Volatile
    internal var exSlot3ProbePending = false

    @Volatile
    internal var exSlot3ProbeAttempts = 0

    @Volatile
    internal var exEncounterProbeStartedAt = Long.MIN_VALUE

    /** Last non-UNKNOWN page the EX handler saw; a challenge page after any other page is a fresh visit. */
    internal var exLastKnownPage: LabyrinthEntryPageState? = null

    @Volatile
    internal var exIdentityProbeDescription = "EX识别目标"

    /** Official “有效效果” roles for the current encounter; populated by the later filter scan. */
    internal val effectiveExCharacterIds = linkedSetOf<String>()

    internal val effectiveRosterSearch = LabyrinthBattleRosterSearch()

    @Volatile
    internal var effectiveCharacterScanStage = LabyrinthEffectiveCharacterScanStage.IDLE

    /**
     * Owned-roster fingerprint captured when the sweep reached COMPLETE.
     *
     * The sweep describes the encounter, so the EX fight and the Boss of one encounter share it,
     * but the filtered list only shows roles this account owns. A role acquired between the two
     * editors therefore invalidates the saved sweep instead of silently reusing a stale roster.
     */
    @Volatile
    internal var effectiveCharacterScanRosterStamp = Long.MIN_VALUE

    @Volatile
    internal var effectiveCharacterScanScrollActions = 0

    @Volatile
    internal var lastEffectiveCharacterScanActionAt = Long.MIN_VALUE

    @Volatile
    internal var effectiveCharacterUnsafeStartedAt = Long.MIN_VALUE

    @Volatile
    internal var effectiveCharacterScanSkippedUnsafe = false

    internal val eventFreeRoleSelectedCharacterIds = linkedSetOf<String>()

    @Volatile
    internal var eventFreeRoleLastSelectAt = Long.MIN_VALUE

    internal var eventFreeRoleObservedSelectedCount = 0

    /** When the free-role page last had nothing it could act on; bounds the wait. */
    internal var eventFreeRoleBlockedSince = Long.MIN_VALUE

    @Volatile
    internal var activeNodeType: Int? = null

    @Volatile
    internal var activeNodeArea: Int? = null

    @Volatile
    internal var eventActionAttempts = 0

    @Volatile
    internal var lastSessionBlockKind = SessionBlockKind.NONE

    @Volatile
    internal var sessionBlockStableFrames = 0

    /**
     * Consecutive frames that actually resolved a 返回标题 rect, not merely frames spent blocked.
     *
     * [sessionBlockStableFrames] counts every blocked frame, including the stretches where the
     * button is not recognised at all and the run sits on 等待识别“返回标题”按钮. That primes it past
     * the stability floor, so the first frame whose score happens to clear the trigger taps at once,
     * unconfirmed. See [labyrinthSessionReturnTitleStreak].
     */
    @Volatile
    internal var sessionReturnTitleStableFrames = 0

    @Volatile
    internal var sessionReturnTitleAttempts = 0

    @Volatile
    internal var lastSessionReturnTitleActionAt = Long.MIN_VALUE

    suspend fun start(
        dryRun: Boolean = true,
        accountId: Long? = null,
        runId: String? = null,
    ): LabyrinthEntryRecognitionStartResult = mutex.withLock {
        activeSessionId?.let { return LabyrinthEntryRecognitionStartResult.AlreadyRunning(it) }
        sessionManager.current()?.let { running ->
            val reason = "已有${running.mode.name}任务运行"
            _state.value = _state.value.copy(status = LabyrinthEntryRecognitionStatus.ERROR, message = reason)
            return LabyrinthEntryRecognitionStartResult.Blocked(reason)
        }
        if (!dryRun && (actionExecutor == null || !actionsAvailable())) {
            val reason = "无障碍服务未连接；若系统开关显示已开启，请关闭后重新开启"
            _state.value = _state.value.copy(status = LabyrinthEntryRecognitionStatus.ERROR, message = reason)
            return LabyrinthEntryRecognitionStartResult.Blocked(reason)
        }
        if (!captureActive()) {
            val reason = "请先在权限中心开启屏幕捕获"
            _state.value = _state.value.copy(status = LabyrinthEntryRecognitionStatus.ERROR, message = reason)
            return LabyrinthEntryRecognitionStartResult.Blocked(reason)
        }
        var executionGateMessage: String? = null
        val executionRoute = if (!dryRun && nodeExecutionConfigured()) {
            val gate = routeExecutionGate
            if (gate == null) {
                val reason = "未配置路线执行联网门禁，已禁止自动点节点"
                requestCaptureStop()
                _state.value = _state.value.copy(status = LabyrinthEntryRecognitionStatus.ERROR, message = reason)
                return LabyrinthEntryRecognitionStartResult.Blocked(reason)
            }
            when (val result = runCatching { gate(accountId) }.getOrElse { failure ->
                LabyrinthExecutionGateResult.Blocked(
                    "执行前联网校验失败：${failure.message ?: "未知错误"}",
                )
            }) {
                is LabyrinthExecutionGateResult.Allowed -> {
                    executionGateMessage = result.message
                    result.route
                }
                is LabyrinthExecutionGateResult.Blocked -> {
                    requestCaptureStop()
                    _state.value = _state.value.copy(
                        status = LabyrinthEntryRecognitionStatus.ERROR,
                        message = result.message,
                    )
                    return LabyrinthEntryRecognitionStartResult.Blocked(result.message)
                }
            }
        } else {
            null
        }
        val strategySnapshot = try {
            strategyProvider?.invoke()
        } catch (failure: Exception) {
            requestCaptureStop()
            val reason = "策略配置加载失败：${failure.message}"
            _state.value = _state.value.copy(status = LabyrinthEntryRecognitionStatus.ERROR, message = reason)
            return LabyrinthEntryRecognitionStartResult.Blocked(reason)
        }
        val session = when (val started = sessionManager.start(AutomationMode.LABYRINTH, dryRun = dryRun)) {
            is AutomationSessionStartResult.AlreadyRunning -> {
                val reason = "已有${started.session.mode.name}任务运行"
                _state.value = _state.value.copy(status = LabyrinthEntryRecognitionStatus.ERROR, message = reason)
                return LabyrinthEntryRecognitionStartResult.Blocked(reason)
            }
            is AutomationSessionStartResult.Started -> started.session
        }
        configureRunStrategy(strategySnapshot)
        val initialRunSnapshot = try {
            runStateStore?.begin(accountId, clock())
        } catch (failure: Throwable) {
            sessionManager.stop(session.id)
            requestCaptureStop()
            validatedRoute = null
            val reason = "加载本局队伍状态失败：${failure.message ?: "未知错误"}"
            _state.value = _state.value.copy(status = LabyrinthEntryRecognitionStatus.ERROR, message = reason)
            return LabyrinthEntryRecognitionStartResult.Blocked(reason)
        }
        val processor = runCatching {
            processorFactory { nodeSession?.directFinalBossTarget() != null }
        }.getOrElse { error ->
            sessionManager.stop(session.id)
            requestCaptureStop()
            validatedRoute = null
            val reason = "加载入口识别模板失败：${error.message ?: "未知错误"}"
            _state.value = _state.value.copy(status = LabyrinthEntryRecognitionStatus.ERROR, message = reason)
            return LabyrinthEntryRecognitionStartResult.Blocked(reason)
        }
        val registration = CaptureFrameBus.register(OWNER) { frame ->
            enqueueLatestFrame(session.id, processor, frame)
        }
        if (registration is CaptureFrameRegistrationResult.Busy) {
            sessionManager.stop(session.id)
            validatedRoute = null
            val reason = "截图帧正在由${registration.owner}使用"
            _state.value = _state.value.copy(status = LabyrinthEntryRecognitionStatus.ERROR, message = reason)
            return LabyrinthEntryRecognitionStartResult.Blocked(reason)
        }
        lease = (registration as CaptureFrameRegistrationResult.Registered).lease
        // Bind batch ownership before publishing the session id. A very fast first frame may
        // otherwise complete before the batch run id is attached.
        currentRunId = if (dryRun) null else runId
        pendingTerminalOutcome = null
        activeSessionId = session.id
        activeRunAccountId = accountId
        shopImprintRewardConfirmedAt = Long.MIN_VALUE
        mapTransitionWorkflow.resetConfirmedNodeEntryReceiptOnStart()
        validatedRoute = executionRoute
        lastProcessedAt = Long.MIN_VALUE
        endGuardedAction()
        channelPromptVisibleUntil = Long.MIN_VALUE
        channelPromptMessage = null
        channelPrompts?.reset()
        stopRequested.set(false)
        initializeRunBusinessState(dryRun, initialRunSnapshot)
        _state.value = LabyrinthEntryRecognitionSessionState(
            status = LabyrinthEntryRecognitionStatus.RUNNING,
            sessionId = session.id,
            dryRun = dryRun,
            joinedCharacters = initialRunSnapshot?.joinedCharacters.orEmpty(),
            observedRelics = initialRunSnapshot?.observedRelics.orEmpty(),
            message = if (dryRun) {
                "入口只读识别已启动"
            } else {
                listOfNotNull(executionGateMessage, "路线自动执行已启动，等待游戏切到前台").joinToString("；")
            },
        )
        val attached = overlayCoordinator.attach(
            presentation = buildOverlayPresentation(session.id),
            sessionHandler = object : AutomationOverlaySessionHandler {
                override suspend fun setPaused(paused: Boolean): Boolean =
                    this@LabyrinthEntryRecognitionSession.setPaused(paused)

                override suspend fun stop(): Boolean =
                    this@LabyrinthEntryRecognitionSession.stop("通知栏停止")
            },
        )
        if (!attached) {
            lease?.let(CaptureFrameBus::unregister)
            lease = null
            activeSessionId = null
            currentRunId = null
            pendingTerminalOutcome = null
            validatedRoute = null
            sessionManager.stop(session.id)
            requestCaptureStop()
            val reason = "任务控制通知不可用或已有任务占用，请检查通知权限与频道"
            _state.value = _state.value.copy(
                status = LabyrinthEntryRecognitionStatus.ERROR,
                sessionId = null,
                message = reason,
            )
            return LabyrinthEntryRecognitionStartResult.Blocked(reason)
        }
        armFirstFrameWatchdog(session.id)
        if (!dryRun) startChannelPromptPoll(session.id)
        if (!dryRun && !gameLauncher()) {
            firstFrameWatchdog?.cancel()
            firstFrameWatchdog = null
            lease?.let(CaptureFrameBus::unregister)
            lease = null
            activeSessionId = null
            currentRunId = null
            pendingTerminalOutcome = null
            validatedRoute = null
            actionPlanner = null
            sessionManager.stop(session.id)
            overlayCoordinator.detach(session.id)
            requestCaptureStop()
            val reason = "无法启动公主连结"
            _state.value = _state.value.copy(
                status = LabyrinthEntryRecognitionStatus.ERROR,
                sessionId = null,
                message = reason,
            )
            return LabyrinthEntryRecognitionStartResult.Blocked(reason)
        }
        LabyrinthEntryRecognitionStartResult.Started(session.id)
    }

    suspend fun startAutomation(
        accountId: Long? = null,
        runId: String? = null,
    ): LabyrinthEntryRecognitionStartResult {
        return start(
            dryRun = false,
            accountId = accountId,
            runId = runId,
        )
    }

    suspend fun setPaused(paused: Boolean): Boolean = mutex.withLock {
        val sessionId = activeSessionId ?: return false
        if (!sessionManager.setPaused(sessionId, paused)) return false
        mapNavigationWorkflow.resetFinalBossLocalization()
        resetPendingNodeClickStability()
        if (paused) clearQueuedFrame()
        _state.value = _state.value.copy(
            paused = paused,
            message = if (paused) "入口识别已暂停" else "入口仅识别已继续",
        )
        publishOverlay(sessionId)
        true
    }

    /**
     * Stops the current labyrinth run.
     *
     * [releaseCapture] separates the Run lifetime from the Capture lifetime. A user stop or a
     * startup failure ends both. A run that hands off to the network reroll must keep the
     * MediaProjection session alive: Android 14+ allows a single createVirtualDisplay() per
     * token, so ending capture here would force a fresh screen-capture consent before the next
     * run could start, which is incompatible with "one authorization per batch".
     */
    suspend fun stop(
        reason: String = "用户停止入口识别",
        releaseCapture: Boolean = true,
    ): Boolean = mutex.withLock {
        val sessionId = activeSessionId ?: run {
            // 启动阶段失败或异常处理可能已经释放了会话，但录屏服务仍在运行。
            if (releaseCapture) requestCaptureStop()
            return false
        }
        stopRequested.set(true)
        firstFrameWatchdog?.cancel()
        firstFrameWatchdog = null
        activeSessionId = null
        activeRunAccountId = null
        validatedRoute = null
        actionPlanner = null
        endGuardedAction()
        clearStoppedRunBusinessState()
        clearQueuedFrame()
        lease?.let(CaptureFrameBus::unregister)
        lease = null
        val stopped = sessionManager.stop(sessionId)
        overlayCoordinator.detach(sessionId)
        if (releaseCapture) {
            requestCaptureStop()
        } else {
            nodeLog("run-stopped-capture-retained reason=$reason")
        }
        _state.value = _state.value.copy(
            status = LabyrinthEntryRecognitionStatus.STOPPED,
            sessionId = null,
            paused = false,
            message = reason,
        )
        publishRunTerminal(reason, userInitiated = releaseCapture && pendingTerminalOutcome == null)
        stopped
    }

    private fun publishRunTerminal(reason: String, userInitiated: Boolean): Unit =
        settlementWorkflow.publishRunTerminal(reason = reason, userInitiated = userInitiated)

    /**
     * CaptureFrameBus dispatches synchronously on the capture callback thread. Heavy recognition
     * must therefore never run directly inside that callback: one slow OCR/template pass would
     * otherwise stop fresh frames from reaching the state machine and make the whole session
     * appear seconds behind the game.
     *
     * Keep at most one waiting frame while a single recognition worker is busy. Newer frames
     * replace older waiting frames (latest-frame-wins), so an occasional expensive frame can add
     * at most one recognition-duration of latency instead of creating an unbounded stale queue.
     */
    private fun enqueueLatestFrame(
        sessionId: AutomationSessionId,
        processor: (CapturedFrame) -> LabyrinthEntryFrameResult,
        frame: CapturedFrame,
    ) {
        if (activeSessionId != sessionId || _state.value.paused) {
            recycle(frame)
            return
        }
        if (lastProcessedAt != Long.MIN_VALUE && frame.timestampMillis - lastProcessedAt < responseIntervalMillis) {
            recycle(frame)
            return
        }
        lastProcessedAt = frame.timestampMillis

        val replaced = queuedFrame.getAndSet(QueuedFrame(sessionId, processor, frame))
        replaced?.let { recycle(it.frame) }
        ensureFrameWorker()
    }

    private fun ensureFrameWorker() {
        if (!frameWorkerRunning.compareAndSet(false, true)) return
        actionScope.launch {
            try {
                while (true) {
                    val next = queuedFrame.getAndSet(null) ?: break
                    onFrame(next.sessionId, next.processor, next.frame)
                }
            } finally {
                frameWorkerRunning.set(false)
                // Close the race where a frame arrives after the loop observes null but before
                // frameWorkerRunning becomes false.
                if (queuedFrame.get() != null) ensureFrameWorker()
            }
        }
    }

    private fun clearQueuedFrame() {
        queuedFrame.getAndSet(null)?.let { recycle(it.frame) }
    }

    private fun onFrame(
        sessionId: AutomationSessionId,
        processor: (CapturedFrame) -> LabyrinthEntryFrameResult,
        frame: CapturedFrame,
    ) {
        if (activeSessionId != sessionId || _state.value.paused) {
            recycle(frame)
            return
        }
        val sessionState = _state.value
        if (!sessionState.dryRun && !actionTargetReady()) {
            val message = "等待游戏切到前台，自动会话保持运行"
            if (sessionState.message != message) {
                _state.value = sessionState.copy(message = message)
                runCatching {
                    publishOverlay(sessionId)
                }.onFailure { error ->
                    handleFrameFailure(sessionId, error)
                }
            }
            recycle(frame)
            return
        }
        val frameWidth = frame.bitmap.width
        val frameHeight = frame.bitmap.height
        val beforeProcessing = _state.value
        _state.value = beforeProcessing.copy(
            receivedFrameCount = beforeProcessing.receivedFrameCount + 1,
        )
        runCatching {
            val result = processor(frame)
            // stop()/pause may run while an expensive frame is being recognized. Never let that
            // stale result mutate a stopped/paused session or publish an old dashboard frame.
            if (activeSessionId != sessionId || _state.value.paused) return@runCatching
            processFrameResult(
                sessionId = sessionId,
                result = result,
                frameWidth = frameWidth,
                frameHeight = frameHeight,
                timestampMillis = frame.timestampMillis,
            )
            debugFramePublisher?.invoke(
                frame,
                _state.value,
                result,
                buildOverlayPresentation(sessionId),
            )
        }.onFailure { error ->
            handleFrameFailure(sessionId, error)
        }
            .also { recycle(frame) }
    }

    private fun handleFrameFailure(sessionId: AutomationSessionId, error: Throwable) {
        if (activeSessionId != sessionId) return
        val reason = "入口识别失败：${error.message ?: "未知错误"}"
        _state.value = _state.value.copy(message = reason)
        requestStopAfterFailure(sessionId, reason)
    }

    private fun processFrameResult(
        sessionId: AutomationSessionId,
        result: LabyrinthEntryFrameResult,
        frameWidth: Int,
        frameHeight: Int,
        timestampMillis: Long,
    ): Unit =
        pageRouterWorkflow.processFrameResult(sessionId = sessionId, result = result, frameWidth = frameWidth, frameHeight = frameHeight, timestampMillis = timestampMillis)

    private fun armFirstFrameWatchdog(sessionId: AutomationSessionId) {
        firstFrameWatchdog?.cancel()
        firstFrameWatchdog = actionScope.launch {
            delay(FIRST_FRAME_TIMEOUT_MILLIS)
            if (activeSessionId != sessionId) return@launch
            val current = _state.value
            if (current.lastResult != null || current.frameCount > 0) return@launch
            val captureRunning = captureActive()
            val currentOwner = CaptureFrameBus.currentOwner()
            val reason = when {
                current.receivedFrameCount > 0 ->
                    "5秒未完成首帧识别：已收到${current.receivedFrameCount}个原始截图，首帧处理卡住或过慢"

                !captureRunning ->
                    buildString {
                        append("5秒未收到首帧：屏幕捕获已停止")
                        CaptureStateRegistry.lastStopReason()?.let { append("（$it）") }
                        append("，请停止Dry Run后重新开启屏幕捕获")
                    }

                currentOwner != OWNER ->
                    "5秒未收到首帧：截图消费者异常（${currentOwner ?: "无消费者"}）"

                else ->
                    "5秒未收到首帧：屏幕捕获仍运行但没有图像帧，请重新开启屏幕捕获"
            }
            _state.value = current.copy(message = reason)
            runCatching {
                publishOverlay(sessionId)
            }
        }
    }

    private fun requestStopAfterFailure(sessionId: AutomationSessionId, reason: String) {
        if (!stopRequested.compareAndSet(false, true)) return
        actionScope.launch {
            if (activeSessionId == sessionId) stopFromRunLogic(reason)
        }
    }

    private fun requestCaptureStop() {
        runCatching { captureStop() }
    }

    private fun handleSessionBlockFrame(
        sessionId: AutomationSessionId,
        result: LabyrinthEntryFrameResult,
        frameWidth: Int,
        frameHeight: Int,
        timestampMillis: Long,
        dryRun: Boolean,
    ): Boolean =
        recoveryWorkflow.handleSessionBlockFrame(sessionId = sessionId, result = result, frameWidth = frameWidth, frameHeight = frameHeight, timestampMillis = timestampMillis, dryRun = dryRun)

    private fun dispatchSessionReturnTitle(
        sessionId: AutomationSessionId,
        rect: EntryPixelRect,
        actionLabel: String,
        timestampMillis: Long,
    ): Unit =
        recoveryWorkflow.dispatchSessionReturnTitle(sessionId = sessionId, rect = rect, actionLabel = actionLabel, timestampMillis = timestampMillis)

    private fun clearPlannedShopPurchase(): Unit =
        shopWorkflow.clearPlannedShopPurchase()

    private fun clearPlannedShopRefresh(): Unit =
        shopWorkflow.clearPlannedShopRefresh()

    private fun restartEntryNavigationAfterSessionError(): Unit =
        recoveryWorkflow.restartEntryNavigationAfterSessionError()

    private fun createActionPlanner(): LabyrinthEntryActionPlanner =
        entryWorkflow.createActionPlanner()

    private fun publishSessionRecoveryMessage(sessionId: AutomationSessionId, message: String): Unit =
        recoveryWorkflow.publishSessionRecoveryMessage(sessionId = sessionId, message = message)

    private fun resetSessionRecoveryTracking(): Unit =
        recoveryWorkflow.resetSessionRecoveryTracking()

    private fun refreshRelicStackCalibration(result: LabyrinthEntryFrameResult): Unit =
        rewardWorkflow.refreshRelicStackCalibration(result = result)

    private fun advanceRelicStackCalibrationAfterAcquisition(relic: LabyrinthRelicMatch): Unit =
        rewardWorkflow.advanceRelicStackCalibrationAfterAcquisition(relic = relic)

    private fun refreshRoleRewardChoiceDecision(
        sessionId: AutomationSessionId,
        result: LabyrinthEntryFrameResult,
        frameWidth: Int,
        frameHeight: Int,
    ): Unit =
        rewardWorkflow.refreshRoleRewardChoiceDecision(sessionId = sessionId, result = result, frameWidth = frameWidth, frameHeight = frameHeight)

    private fun refreshEventChoiceDecision(
        sessionId: AutomationSessionId,
        result: LabyrinthEntryFrameResult,
    ): Unit =
        eventWorkflow.refreshEventChoiceDecision(sessionId = sessionId, result = result)

    private fun persistObservation(
        sessionId: AutomationSessionId,
        result: LabyrinthEntryFrameResult,
        timestampMillis: Long,
    ): Unit =
        persistenceWorkflow.persistObservation(sessionId = sessionId, result = result, timestampMillis = timestampMillis)

    private fun synchronizeBossEditor(result: LabyrinthEntryFrameResult): Unit =
        battleRecommendationWorkflow.synchronizeBossEditor(result = result)

    private fun invalidatePreviousPageState(
        pageState: LabyrinthEntryPageState,
        preservePendingNodeMoveConfirmation: Boolean = false,
    ): Unit =
        runBoundaryWorkflow.invalidatePreviousPageState(pageState = pageState, preservePendingNodeMoveConfirmation = preservePendingNodeMoveConfirmation)

    private fun refreshEffectiveCharacterScan(
        sessionId: AutomationSessionId,
        result: LabyrinthEntryFrameResult,
        frameWidth: Int,
        frameHeight: Int,
        timestampMillis: Long,
    ): Unit =
        battleRosterWorkflow.refreshEffectiveCharacterScan(sessionId = sessionId, result = result, frameWidth = frameWidth, frameHeight = frameHeight, timestampMillis = timestampMillis)

    private fun dispatchEffectiveCharacterScanAction(
        sessionId: AutomationSessionId,
        label: String,
        action: AutomationAction,
        timestampMillis: Long,
        scrollDirection: LabyrinthBattleRosterScrollDirection? = null,
        scrollOriginPosition: Double? = null,
    ): Unit =
        battleRosterWorkflow.dispatchEffectiveCharacterScanAction(sessionId = sessionId, label = label, action = action, timestampMillis = timestampMillis, scrollDirection = scrollDirection, scrollOriginPosition = scrollOriginPosition)

    private fun refreshBattleTeamRecommendation(
        sessionId: AutomationSessionId,
        pageState: LabyrinthEntryPageState,
    ): Unit =
        battleRecommendationWorkflow.refreshBattleTeamRecommendation(sessionId = sessionId, pageState = pageState)

    private fun refreshBattleTeamSelectionPlan(
        sessionId: AutomationSessionId,
        pageState: LabyrinthEntryPageState,
        observation: com.landosol.toolbox.labyrinth.vision.LabyrinthBattleTeamObservation?,
    ): Unit =
        battleRecommendationWorkflow.refreshBattleTeamSelectionPlan(sessionId = sessionId, pageState = pageState, observation = observation)

    private fun startChannelPromptPoll(sessionId: AutomationSessionId): Unit =
        recoveryWorkflow.startChannelPromptPoll(sessionId = sessionId)

    private suspend fun pollChannelPrompt(
        sessionId: AutomationSessionId,
        prompts: com.landosol.toolbox.automation.session.ChannelLoginPromptAssistant,
    ): Unit =
        recoveryWorkflow.pollChannelPrompt(sessionId = sessionId, prompts = prompts)

    private fun handleActionDecision(
        sessionId: AutomationSessionId,
        result: LabyrinthEntryFrameResult,
        frameWidth: Int,
        frameHeight: Int,
        timestampMillis: Long,
    ): Unit =
        entryWorkflow.handleActionDecision(sessionId = sessionId, result = result, frameWidth = frameWidth, frameHeight = frameHeight, timestampMillis = timestampMillis)

    private fun dispatchAction(
        sessionId: AutomationSessionId,
        decision: LabyrinthEntryActionDecision.Execute,
    ): Unit =
        entryWorkflow.dispatchAction(sessionId = sessionId, decision = decision)

    /**
     * Stop initiated by the run's own logic (a terminal page, a rejected tap, a planner stop).
     *
     * When a batch owns this run the MediaProjection session belongs to the batch, not to the
     * run: the next run reuses it, and Android 14+ would demand a fresh consent if it were
     * released here. 2026-09-17 live: the cleared run's stop released capture, so the following
     * client-invalidation step found no capture and the batch halted with
     * CLIENT_INVALIDATION_FAILED. Only a user-initiated [stop] releases capture during a batch.
     */
    internal suspend fun stopFromRunLogic(reason: String): Boolean =
        stop(reason, releaseCapture = currentRunId == null)

    private fun resetEventChoiceCommit(): Unit =
        eventWorkflow.resetEventChoiceCommit()

    private fun finishFromPlanner(sessionId: AutomationSessionId, reason: String) {
        if (!beginGuardedAction()) return
        launchGuardedAction {
            if (activeSessionId == sessionId) stopFromRunLogic(reason)
        }
    }

    private fun finishFromPlannerAndRequestReroll(
        sessionId: AutomationSessionId,
        reason: String,
    ): Unit =
        settlementWorkflow.finishFromPlannerAndRequestReroll(sessionId = sessionId, reason = reason)

    private fun nodeExecutionConfigured(): Boolean =
        routeLoader != null && nodeTemplateLoader != null

    private fun shouldHandOffToRouteExecution(state: LabyrinthEntryPageState): Boolean =
        entryWorkflow.shouldHandOffToRouteExecution(state = state)

    private fun currentShopDecision(result: LabyrinthEntryFrameResult): LabyrinthShopDecision? =
        shopWorkflow.currentShopDecision(result = result)

    private fun handleBattleWaitFrame(
        sessionId: AutomationSessionId,
        result: LabyrinthEntryFrameResult,
        timestampMillis: Long,
    ): Boolean =
        battleCombatWorkflow.handleBattleWaitFrame(sessionId = sessionId, result = result, timestampMillis = timestampMillis)

    private fun acknowledgeBattleTeamActionProgress(result: LabyrinthEntryFrameResult): Unit =
        battleTeamWorkflow.acknowledgeBattleTeamActionProgress(result = result)

    private fun handleUnconfirmedBattleTeamActionFrame(
        sessionId: AutomationSessionId,
        result: LabyrinthEntryFrameResult,
        timestampMillis: Long,
    ): Boolean =
        battleTeamWorkflow.handleUnconfirmedBattleTeamActionFrame(sessionId = sessionId, result = result, timestampMillis = timestampMillis)

    private fun recoverUnconfirmedBattleTeamAction(
        sessionId: AutomationSessionId,
        pending: PendingBattleTeamAction,
    ): Unit =
        battleTeamWorkflow.recoverUnconfirmedBattleTeamAction(sessionId = sessionId, pending = pending)

    private fun continueUnknownExAfterDetail(
        sessionId: AutomationSessionId,
        close: EntryPixelRect?,
        observedName: String?,
        timestampMillis: Long,
    ): Unit =
        encounterWorkflow.continueUnknownExAfterDetail(sessionId = sessionId, close = close, observedName = observedName, timestampMillis = timestampMillis)

    private fun handleExEncounterRecognitionFrame(
        sessionId: AutomationSessionId,
        result: LabyrinthEntryFrameResult,
        timestampMillis: Long,
    ): Boolean =
        encounterWorkflow.handleExEncounterRecognitionFrame(sessionId = sessionId, result = result, timestampMillis = timestampMillis)

    private fun handleEventFreeRoleSelection(
        sessionId: AutomationSessionId,
        result: LabyrinthEntryFrameResult,
        timestampMillis: Long,
    ): Unit =
        eventWorkflow.handleEventFreeRoleSelection(sessionId = sessionId, result = result, timestampMillis = timestampMillis)

    private fun resetEventFreeRoleProgress(): Unit =
        eventWorkflow.resetEventFreeRoleProgress()

    /**
     * Claim the single-action latch, remembering when it was claimed.
     *
     * Only one gesture may be in flight at a time, and every frame handler bails out while the
     * latch is held. That makes the latch the one piece of state that can freeze the entire
     * session on its own: if it is ever claimed and not released, no page handler runs again, no
     * message is published, and the run sits on the generic per-frame status for ever with a
     * frozen action count -- exactly the 408 s freeze in 2026-09-22 bundle 173753.
     *
     * Release therefore belongs in a `finally` ([launchGuardedAction]) rather than at the end of
     * a long coroutine body, and a latch held implausibly long is treated as a fault by
     * [labyrinthActionLatchStuck] instead of being waited on.
     */
    private fun beginGuardedAction(): Boolean {
        if (!actionInFlight.compareAndSet(false, true)) return false
        actionInFlightSince = clock()
        return true
    }

    private fun endGuardedAction() {
        actionInFlightSince = Long.MIN_VALUE
        actionInFlight.set(false)
    }

    /** Runs a dispatched action, releasing the latch however the body ends. */
    private fun launchGuardedAction(block: suspend () -> Unit) {
        actionScope.launch {
            try {
                block()
            } finally {
                endGuardedAction()
            }
        }
    }

    private fun resetPostEntryStall(): Unit =
        pageRouterWorkflow.resetPostEntryStall()

    private fun noteEventFreeRoleBlocked(
        sessionId: AutomationSessionId,
        timestampMillis: Long,
        reason: String,
    ): Unit =
        eventWorkflow.noteEventFreeRoleBlocked(sessionId = sessionId, timestampMillis = timestampMillis, reason = reason)

    private fun handlePostEntryPage(
        sessionId: AutomationSessionId,
        result: LabyrinthEntryFrameResult,
        frameWidth: Int,
        frameHeight: Int,
        timestampMillis: Long,
    ): Unit =
        pageRouterWorkflow.handlePostEntryPage(sessionId = sessionId, result = result, frameWidth = frameWidth, frameHeight = frameHeight, timestampMillis = timestampMillis)

    private fun handleBattleTeamSelectionExecution(
        sessionId: AutomationSessionId,
        result: LabyrinthEntryFrameResult,
        frameWidth: Int,
        frameHeight: Int,
        timestampMillis: Long,
    ): Unit =
        battleTeamWorkflow.handleBattleTeamSelectionExecution(sessionId = sessionId, result = result, frameWidth = frameWidth, frameHeight = frameHeight, timestampMillis = timestampMillis)

    private fun battleTeamProgress(): LabyrinthBattleTeamProgress =
        battleTeamWorkflow.battleTeamProgress()

    private fun planBattleTeamSelectionFrame(
        sessionId: AutomationSessionId,
        result: LabyrinthEntryFrameResult,
        frameWidth: Int,
        frameHeight: Int,
        timestampMillis: Long,
    ): Unit =
        battleTeamWorkflow.planBattleTeamSelectionFrame(sessionId = sessionId, result = result, frameWidth = frameWidth, frameHeight = frameHeight, timestampMillis = timestampMillis)

    private fun battleTeamEditorSignature(result: LabyrinthEntryFrameResult): String =
        battleTeamWorkflow.battleTeamEditorSignature(result = result)

    private fun resetSpecialEquipmentRound(): Unit =
        equipmentWorkflow.resetSpecialEquipmentRound()

    private fun handleSpecialEquipmentFlowFrame(
        sessionId: AutomationSessionId,
        result: LabyrinthEntryFrameResult,
        timestampMillis: Long,
    ): Boolean =
        equipmentWorkflow.handleSpecialEquipmentFlowFrame(sessionId = sessionId, result = result, timestampMillis = timestampMillis)

    /**
     * 会话级动作间隔：与编组执行步共用同一个节流时间戳，两个流程之间不得插队（见 [lastSessionActionAt]）。
     */
    private fun sessionActionIntervalElapsed(timestampMillis: Long): Boolean =
        lastSessionActionAt == Long.MIN_VALUE ||
            timestampMillis - lastSessionActionAt >= BATTLE_TEAM_ACTION_INTERVAL_MILLIS

    private fun dispatchSpecialEquipmentAction(
        sessionId: AutomationSessionId,
        rect: EntryPixelRect,
        label: String,
        timestampMillis: Long,
    ): Boolean =
        equipmentWorkflow.dispatchSpecialEquipmentAction(sessionId = sessionId, rect = rect, label = label, timestampMillis = timestampMillis)

    private fun dispatchBattleTeamAction(
        sessionId: AutomationSessionId,
        step: LabyrinthBattleTeamExecutionStep,
        timestampMillis: Long,
    ): Unit =
        battleTeamWorkflow.dispatchBattleTeamAction(sessionId = sessionId, step = step, timestampMillis = timestampMillis)

    /** Record why a plannable action was withheld this frame. Diagnostic only. */
    private fun traceReject(reason: String) {
        val current = _state.value
        if (current.frameTrace.actionRejectReason == reason) return
        _state.value = current.copy(frameTrace = current.frameTrace.copy(actionRejectReason = reason))
    }

    /** Record the label of the action dispatched on this frame. Diagnostic only. */
    private fun traceAction(label: String) {
        val current = _state.value
        _state.value = current.copy(frameTrace = current.frameTrace.copy(actionLabel = label))
    }

    private fun excludeUnfindableBattleCharacters(
        sessionId: AutomationSessionId,
        unfindable: Set<String>,
        missingLabel: String,
        scannedLabel: String,
    ): Unit =
        battleRosterWorkflow.excludeUnfindableBattleCharacters(sessionId = sessionId, unfindable = unfindable, missingLabel = missingLabel, scannedLabel = scannedLabel)

    private fun resetBattleTeamExecutionTracking(): Unit =
        battleTeamWorkflow.resetBattleTeamExecutionTracking()

    private fun resetExEncounterTracking(): Unit =
        encounterWorkflow.resetExEncounterTracking()

    private fun resetEffectiveCharacterScan(): Unit =
        battleRosterWorkflow.resetEffectiveCharacterScan()

    private fun currentEffectiveScanRosterStamp(): Long =
        battleRosterWorkflow.currentEffectiveScanRosterStamp()

    private fun resetBattleRetryTracking(): Unit =
        battleCombatWorkflow.resetBattleRetryTracking()

    private fun postEntryIsRoleRewardPage(result: LabyrinthEntryFrameResult): Boolean =
        rewardWorkflow.postEntryIsRoleRewardPage(result = result)

    private fun finalSettlementFallbackRect(
        result: LabyrinthEntryFrameResult,
        pageState: LabyrinthEntryPageState,
        frameWidth: Int,
        frameHeight: Int,
    ): EntryPixelRect =
        settlementWorkflow.finalSettlementFallbackRect(result = result, pageState = pageState, frameWidth = frameWidth, frameHeight = frameHeight)

    private fun dispatchPostEntryTap(
        sessionId: AutomationSessionId,
        kind: LabyrinthPostEntryActionKind,
        label: String,
        rect: EntryPixelRect,
        timestampMillis: Long,
        roleRewardSelectionSignature: String? = null,
        roleRewardSelectedCharacterId: String? = null,
        shopPurchaseRelicId: String? = null,
        shopPurchaseRoleImprintLabel: String? = null,
        eventFreeRoleCandidateId: String? = null,
        eventFreeRoleConfirm: Boolean = false,
        eventFreeRoleConfirmIds: List<String> = emptyList(),
    ): Unit =
        pageActionWorkflow.dispatchPostEntryTap(sessionId = sessionId, kind = kind, label = label, rect = rect, timestampMillis = timestampMillis, roleRewardSelectionSignature = roleRewardSelectionSignature, roleRewardSelectedCharacterId = roleRewardSelectedCharacterId, shopPurchaseRelicId = shopPurchaseRelicId, shopPurchaseRoleImprintLabel = shopPurchaseRoleImprintLabel, eventFreeRoleCandidateId = eventFreeRoleCandidateId, eventFreeRoleConfirm = eventFreeRoleConfirm, eventFreeRoleConfirmIds = eventFreeRoleConfirmIds)

    private fun markRunCleared(sessionId: AutomationSessionId): Unit =
        settlementWorkflow.markRunCleared(sessionId = sessionId)

    private fun anchorRect(result: LabyrinthEntryFrameResult, anchorId: String): EntryPixelRect? =
        pageActionWorkflow.anchorRect(result = result, anchorId = anchorId)

    private fun clearShopChoiceImprintRolePending(): Unit =
        shopWorkflow.clearShopChoiceImprintRolePending()

    private fun resetNodeExecutionState(): Unit =
        runBoundaryWorkflow.resetNodeExecutionState()

    private fun clearCharacterAcquisitionContext(): Unit =
        rewardWorkflow.clearCharacterAcquisitionContext()

    private fun clearRoleRewardBatchTracking(): Unit =
        rewardWorkflow.clearRoleRewardBatchTracking()

    private fun handleNodeSelectionFrame(
        sessionId: AutomationSessionId,
        result: LabyrinthEntryFrameResult,
        frameWidth: Int,
        frameHeight: Int,
        timestampMillis: Long,
    ): Unit =
        mapNavigationWorkflow.handleNodeSelectionFrame(sessionId = sessionId, result = result, frameWidth = frameWidth, frameHeight = frameHeight, timestampMillis = timestampMillis)

    private fun maybeStartNodeInit(sessionId: AutomationSessionId): Unit =
        mapNavigationWorkflow.maybeStartNodeInit(sessionId = sessionId)

    private fun onNodeClickPlanned(
        sessionId: AutomationSessionId,
        session: LabyrinthNodeSession,
        action: NodeAction.ClickNode,
        frameWidth: Int,
        frameHeight: Int,
        timestampMillis: Long,
        viewportSignature: String,
    ): Unit =
        mapNavigationWorkflow.onNodeClickPlanned(sessionId = sessionId, session = session, action = action, frameWidth = frameWidth, frameHeight = frameHeight, timestampMillis = timestampMillis, viewportSignature = viewportSignature)

    private fun recoverPendingNodeTransitionFromMoveConfirmation(
        sessionId: AutomationSessionId,
        session: LabyrinthNodeSession,
        blockId: Long,
        blockType: Int,
        area: Int,
        timestampMillis: Long,
        confidence: Double,
    ): Unit =
        mapTransitionWorkflow.recoverPendingNodeTransitionFromMoveConfirmation(sessionId = sessionId, session = session, blockId = blockId, blockType = blockType, area = area, timestampMillis = timestampMillis, confidence = confidence)

    private fun prepareNodeTransitionContext(blockType: Int, area: Int?): Unit =
        runBoundaryWorkflow.prepareNodeTransitionContext(blockType = blockType, area = area)

    private fun handleShopRefreshConfirmationFrame(
        sessionId: AutomationSessionId,
        result: LabyrinthEntryFrameResult,
        timestampMillis: Long,
    ): Boolean =
        shopWorkflow.handleShopRefreshConfirmationFrame(sessionId = sessionId, result = result, timestampMillis = timestampMillis)

    private fun handleNodeMoveConfirmationFrame(
        sessionId: AutomationSessionId,
        result: LabyrinthEntryFrameResult,
        timestampMillis: Long,
    ): Boolean =
        mapTransitionWorkflow.handleNodeMoveConfirmationFrame(sessionId = sessionId, result = result, timestampMillis = timestampMillis)

    private fun dispatchNodeMoveConfirmation(
        sessionId: AutomationSessionId,
        pending: PendingNodeTransition,
        rect: EntryPixelRect,
    ): Unit =
        mapTransitionWorkflow.dispatchNodeMoveConfirmation(sessionId = sessionId, pending = pending, rect = rect)

    private fun dismissOrphanNodeMoveConfirmation(
        sessionId: AutomationSessionId,
        cancelRect: EntryPixelRect,
        timestampMillis: Long,
    ): Unit =
        orphanMoveRecoveryWorkflow.dismissOrphanNodeMoveConfirmation(sessionId = sessionId, cancelRect = cancelRect, timestampMillis = timestampMillis)

    private fun publishNodeMoveConfirmationMessage(sessionId: AutomationSessionId, message: String): Unit =
        mapTransitionWorkflow.publishNodeMoveConfirmationMessage(sessionId = sessionId, message = message)

    private fun resetNodeMoveConfirmationTracking(): Unit =
        mapTransitionWorkflow.resetNodeMoveConfirmationTracking()

    private fun resetOrphanMoveConfirmationTracking(): Unit =
        orphanMoveRecoveryWorkflow.resetOrphanMoveConfirmationTracking()

    private fun publishWaitingForGameForeground(sessionId: AutomationSessionId) {
        if (activeSessionId != sessionId) return
        _state.value = _state.value.copy(message = "游戏不在前台，保持会话并等待返回")
        publishOverlay(sessionId)
    }

    private fun confirmPendingNodeTransition(
        sessionId: AutomationSessionId,
        pageState: LabyrinthEntryPageState,
    ): Unit =
        mapTransitionWorkflow.confirmPendingNodeTransition(sessionId = sessionId, pageState = pageState)

    private fun persistNodeRouteProgress(
        sessionId: AutomationSessionId,
        nodeState: NodeSessionState,
    ): Unit =
        mapTransitionWorkflow.persistNodeRouteProgress(sessionId = sessionId, nodeState = nodeState)

    private fun combatContextFor(blockType: Int): LabyrinthCombatContext? =
        battleCombatWorkflow.combatContextFor(blockType = blockType)

    private fun resetPendingNodeClickStability(): Unit =
        mapNavigationWorkflow.resetPendingNodeClickStability()

    private fun resetStrongRouteTargetBinding(): Unit =
        mapNavigationWorkflow.resetStrongRouteTargetBinding()

    private fun resetNodeScrollSearchGate(): Unit =
        mapNavigationWorkflow.resetNodeScrollSearchGate()

    private fun resetNodeRecoveryNudgeTracking(): Unit =
        mapViewportWorkflow.resetNodeRecoveryNudgeTracking()

    private fun nodeClickRectsAreStable(
        previous: EntryPixelRect?,
        current: EntryPixelRect?,
    ): Boolean =
        mapViewportWorkflow.nodeClickRectsAreStable(previous = previous, current = current)

    private fun requestNodeMapNudge(
        sessionId: AutomationSessionId,
        label: String,
        targetBlockId: Long,
        frameWidth: Int,
        frameHeight: Int,
        timestampMillis: Long,
        viewportSignature: String,
        avoidRects: List<EntryPixelRect>,
        reason: String,
    ): Unit =
        mapViewportWorkflow.requestNodeMapNudge(sessionId = sessionId, label = label, targetBlockId = targetBlockId, frameWidth = frameWidth, frameHeight = frameHeight, timestampMillis = timestampMillis, viewportSignature = viewportSignature, avoidRects = avoidRects, reason = reason)

    private fun requestNodeMapScroll(
        sessionId: AutomationSessionId,
        label: String,
        frameWidth: Int,
        frameHeight: Int,
        timestampMillis: Long,
        viewportSignature: String,
        avoidRects: List<EntryPixelRect>,
    ): Unit =
        mapViewportWorkflow.requestNodeMapScroll(sessionId = sessionId, label = label, frameWidth = frameWidth, frameHeight = frameHeight, timestampMillis = timestampMillis, viewportSignature = viewportSignature, avoidRects = avoidRects)

    private fun logNodeScrollTerminalDiagnostics(
        label: String,
        reason: String,
        frameWidth: Int,
        frameHeight: Int,
        viewportSignature: String,
    ): Unit =
        mapViewportWorkflow.logNodeScrollTerminalDiagnostics(label = label, reason = reason, frameWidth = frameWidth, frameHeight = frameHeight, viewportSignature = viewportSignature)

    private fun publishRouteProgress(
        sessionId: AutomationSessionId,
        session: LabyrinthNodeSession,
        nextLabel: String?,
        nextRect: EntryPixelRect?,
        complete: Boolean = false,
    ): Unit =
        mapNavigationWorkflow.publishRouteProgress(sessionId = sessionId, session = session, nextLabel = nextLabel, nextRect = nextRect, complete = complete)

    private fun buildOverlayPresentation(sessionId: AutomationSessionId): AutomationOverlayPresentation =
        presentationWorkflow.buildOverlayPresentation(sessionId = sessionId)

    private fun nodeRelicStackSummary(result: LabyrinthEntryFrameResult?): String? =
        presentationWorkflow.nodeRelicStackSummary(result = result)

    private fun buildPageOwnedOverlayBoxes(
        owner: LabyrinthPageUiOwner,
        current: LabyrinthEntryRecognitionSessionState,
        result: LabyrinthEntryFrameResult?,
    ): List<AutomationOverlayBox> =
        presentationWorkflow.buildPageOwnedOverlayBoxes(owner = owner, current = current, result = result)

    private fun buildEventChoiceOverlayBoxes(
        current: LabyrinthEntryRecognitionSessionState,
        result: LabyrinthEntryFrameResult?,
    ): List<AutomationOverlayBox> =
        presentationWorkflow.buildEventChoiceOverlayBoxes(current = current, result = result)

    private fun buildCharacterPageOverlayBoxes(
        current: LabyrinthEntryRecognitionSessionState,
        result: LabyrinthEntryFrameResult?,
    ): List<AutomationOverlayBox> =
        presentationWorkflow.buildCharacterPageOverlayBoxes(current = current, result = result)

    private fun buildPausedNodeDiagnosticsBoxes(
        current: LabyrinthEntryRecognitionSessionState,
        result: LabyrinthEntryFrameResult?,
    ): List<AutomationOverlayBox> =
        presentationWorkflow.buildPausedNodeDiagnosticsBoxes(current = current, result = result)

    private fun expandNodeDiagnosticsRect(
        rect: EntryPixelRect,
        frameWidth: Int,
        frameHeight: Int,
    ): EntryPixelRect? =
        presentationWorkflow.expandNodeDiagnosticsRect(rect = rect, frameWidth = frameWidth, frameHeight = frameHeight)

    private fun toNodeDiagnosticsOverlayBox(
        rect: EntryPixelRect,
        frameWidth: Int,
        frameHeight: Int,
        label: String,
        recognized: Boolean,
        selected: Boolean,
    ): AutomationOverlayBox? =
        presentationWorkflow.toNodeDiagnosticsOverlayBox(rect = rect, frameWidth = frameWidth, frameHeight = frameHeight, label = label, recognized = recognized, selected = selected)

    private fun buildNextNodeOverlayBoxes(
        current: LabyrinthEntryRecognitionSessionState,
        result: LabyrinthEntryFrameResult?,
    ): List<AutomationOverlayBox> =
        presentationWorkflow.buildNextNodeOverlayBoxes(current = current, result = result)

    private fun buildBattleTeamOverlayBoxes(
        current: LabyrinthEntryRecognitionSessionState,
        result: LabyrinthEntryFrameResult?,
    ): List<AutomationOverlayBox> =
        presentationWorkflow.buildBattleTeamOverlayBoxes(current = current, result = result)

    private fun buildLinkChoiceOverlayBoxes(
        result: LabyrinthEntryFrameResult?,
    ): List<AutomationOverlayBox> =
        presentationWorkflow.buildLinkChoiceOverlayBoxes(result = result)

    private fun buildRelicChoiceOverlayBoxes(
        result: LabyrinthEntryFrameResult?,
    ): List<AutomationOverlayBox> =
        presentationWorkflow.buildRelicChoiceOverlayBoxes(result = result)

    private fun buildShopOverlayBoxes(
        result: LabyrinthEntryFrameResult?,
    ): List<AutomationOverlayBox> =
        presentationWorkflow.buildShopOverlayBoxes(result = result)

    private fun recycle(frame: CapturedFrame) {
        if (!frame.bitmap.isRecycled) frame.bitmap.recycle()
    }

    private fun describeNodeClassifications(classifications: List<NodeClassification>): String =
        mapViewportWorkflow.describeNodeClassifications(classifications = classifications)

    private fun nodeLog(message: String, warning: Boolean = false) {
        runCatching {
            if (warning) Log.w(NODE_LOG_TAG, message) else Log.d(NODE_LOG_TAG, message)
        }
    }

    private fun handleBattleFailureFrame(sessionId: AutomationSessionId, result: LabyrinthEntryFrameResult): Boolean =
        battleCombatWorkflow.handleBattleFailureFrame(sessionId = sessionId, result = result)

    private fun planRelicChoice(sessionId: AutomationSessionId, result: LabyrinthEntryFrameResult, timestampMillis: Long): LabyrinthPostEntryTapPlan? =
        rewardWorkflow.planRelicChoice(sessionId = sessionId, result = result, timestampMillis = timestampMillis)

    private fun planEventChoice(sessionId: AutomationSessionId, result: LabyrinthEntryFrameResult, timestampMillis: Long): LabyrinthPostEntryTapPlan? =
        eventWorkflow.planEventChoice(sessionId = sessionId, result = result, timestampMillis = timestampMillis)

    private fun planShopPage(result: LabyrinthEntryFrameResult, frameWidth: Int, frameHeight: Int, timestampMillis: Long): LabyrinthPageActionProposal =
        shopWorkflow.planShopPage(result = result, frameWidth = frameWidth, frameHeight = frameHeight, timestampMillis = timestampMillis)

    private fun observeShopPurchase(result: LabyrinthEntryFrameResult): LabyrinthRelicMatch? =
        shopWorkflow.observeShopPurchase(result = result)

    private fun onBattleTapExecuted(kind: LabyrinthPostEntryActionKind): Unit =
        battleCombatWorkflow.onBattleTapExecuted(kind = kind)

    private fun onShopTapExecuted(kind: LabyrinthPostEntryActionKind): Unit =
        shopWorkflow.onShopTapExecuted(kind = kind)

    private fun onSettlementTapExecuted(kind: LabyrinthPostEntryActionKind, timestampMillis: Long): Unit =
        settlementWorkflow.onSettlementTapExecuted(kind = kind, timestampMillis = timestampMillis)

    private fun updateRewardPageProgress(sessionId: AutomationSessionId, result: LabyrinthEntryFrameResult, previousPostEntryState: LabyrinthEntryPageState?, timestampMillis: Long): Unit =
        rewardWorkflow.updateRewardPageProgress(sessionId = sessionId, result = result, previousPostEntryState = previousPostEntryState, timestampMillis = timestampMillis)

    private fun handleSettlementProgress(sessionId: AutomationSessionId, pageState: LabyrinthEntryPageState, timestampMillis: Long): Boolean =
        settlementWorkflow.handleSettlementProgress(sessionId = sessionId, pageState = pageState, timestampMillis = timestampMillis)

    private fun prepareMapTransitionFrame(sessionId: AutomationSessionId, pageState: LabyrinthEntryPageState, result: LabyrinthEntryFrameResult, timestampMillis: Long, dryRun: Boolean): Boolean? =
        mapTransitionWorkflow.prepareMapTransitionFrame(sessionId = sessionId, pageState = pageState, result = result, timestampMillis = timestampMillis, dryRun = dryRun)

    private fun handleOrphanMoveConfirmation(sessionId: AutomationSessionId, result: LabyrinthEntryFrameResult, timestampMillis: Long, dryRun: Boolean): Boolean =
        orphanMoveRecoveryWorkflow.handleOrphanMoveConfirmation(sessionId = sessionId, result = result, timestampMillis = timestampMillis, dryRun = dryRun)

    private fun handleEventFreeRoleFrame(sessionId: AutomationSessionId, result: LabyrinthEntryFrameResult, timestampMillis: Long, dryRun: Boolean, routeHasPassedOpening: Boolean): Boolean =
        eventWorkflow.handleEventFreeRoleFrame(sessionId = sessionId, result = result, timestampMillis = timestampMillis, dryRun = dryRun, routeHasPassedOpening = routeHasPassedOpening)

    private fun publishOverlay(sessionId: AutomationSessionId) {
        overlayCoordinator.update(sessionId, buildOverlayPresentation(sessionId))
    }

    private fun traceWorkflow(owner: String, phase: String) {
        _state.update { current ->
            current.copy(frameTrace = current.frameTrace.copy(workflowOwner = owner, workflowPhase = phase))
        }
    }

    private fun configureRunStrategy(strategySnapshot: LabyrinthStrategySnapshot?): Unit =
        runBoundaryWorkflow.configureRunStrategy(strategySnapshot = strategySnapshot)

    private fun initializeRunBusinessState(dryRun: Boolean, initialRunSnapshot: LabyrinthRunSnapshot?): Unit =
        runBoundaryWorkflow.initializeRunBusinessState(dryRun = dryRun, initialRunSnapshot = initialRunSnapshot)

    private fun clearStoppedRunBusinessState(): Unit =
        runBoundaryWorkflow.clearStoppedRunBusinessState()

    private fun captureRoleRewardTap(roleRewardSelectedCharacterId: String?): LabyrinthRoleRewardTapContext =
        rewardWorkflow.captureRoleRewardTap(roleRewardSelectedCharacterId = roleRewardSelectedCharacterId)

    private fun onEventTapExecuted(kind: LabyrinthPostEntryActionKind, eventFreeRoleCandidateId: String?, eventFreeRoleConfirm: Boolean, eventFreeRoleConfirmIds: List<String>): Unit =
        eventWorkflow.onEventTapExecuted(kind = kind, eventFreeRoleCandidateId = eventFreeRoleCandidateId, eventFreeRoleConfirm = eventFreeRoleConfirm, eventFreeRoleConfirmIds = eventFreeRoleConfirmIds)

    private suspend fun onRewardTapExecuted(sessionId: AutomationSessionId, kind: LabyrinthPostEntryActionKind, roleRewardSelectionSignature: String?, roleRewardSelectedCharacterId: String?, context: LabyrinthRoleRewardTapContext, rect: EntryPixelRect): Unit =
        rewardWorkflow.onRewardTapExecuted(sessionId = sessionId, kind = kind, roleRewardSelectionSignature = roleRewardSelectionSignature, roleRewardSelectedCharacterId = roleRewardSelectedCharacterId, context = context, rect = rect)

    private fun onShopTapDispatched(shopPurchaseRelicId: String?, shopPurchaseRoleImprintLabel: String?, timestampMillis: Long): Unit =
        shopWorkflow.onShopTapDispatched(shopPurchaseRelicId = shopPurchaseRelicId, shopPurchaseRoleImprintLabel = shopPurchaseRoleImprintLabel, timestampMillis = timestampMillis)

    private fun onEventTapDispatched(kind: LabyrinthPostEntryActionKind): Unit =
        eventWorkflow.onEventTapDispatched(kind = kind)

    private fun onRewardTapDispatched(kind: LabyrinthPostEntryActionKind, timestampMillis: Long): Unit =
        rewardWorkflow.onRewardTapDispatched(kind = kind, timestampMillis = timestampMillis)

    private fun onShopImprintTapExecuted(kind: LabyrinthPostEntryActionKind): Unit =
        shopWorkflow.onShopImprintTapExecuted(kind = kind)

    private fun onRewardPageClosed(kind: LabyrinthPostEntryActionKind, timestampMillis: Long): Unit =
        rewardWorkflow.onRewardPageClosed(kind = kind, timestampMillis = timestampMillis)

    private fun onEncounterTapExecuted(kind: LabyrinthPostEntryActionKind): Unit =
        encounterWorkflow.onEncounterTapExecuted(kind = kind)

    private fun onEncounterTapRejected(kind: LabyrinthPostEntryActionKind): Unit =
        encounterWorkflow.onEncounterTapRejected(kind = kind)

    private fun observeRelicAcquisition(result: LabyrinthEntryFrameResult): LabyrinthRelicMatch? =
        rewardWorkflow.observeRelicAcquisition(result = result)

    private fun onSettlementTapDispatched(kind: LabyrinthPostEntryActionKind): Unit =
        settlementWorkflow.onSettlementTapDispatched(kind = kind)

    private fun onRewardTapNotForeground(kind: LabyrinthPostEntryActionKind): Unit =
        rewardWorkflow.onRewardTapNotForeground(kind = kind)

    private fun onSettlementTapNotForeground(kind: LabyrinthPostEntryActionKind): Unit =
        settlementWorkflow.onSettlementTapNotForeground(kind = kind)

    private fun acknowledgeAcquiredCharacters(characters: List<LabyrinthCharacterMatch>): Unit =
        rewardWorkflow.acknowledgeAcquiredCharacters(characters = characters)

    private fun resetObservedOpeningRewards(): Unit =
        rewardWorkflow.resetObservedOpeningRewards()

    private fun resetEventRosterState(): Unit =
        eventWorkflow.resetEventRosterState()

    private fun resetMapRuntimeState(): Unit =
        mapNavigationWorkflow.resetMapRuntimeState()

    private fun resetPostEntryRuntimeState(): Unit =
        pageRouterWorkflow.resetPostEntryRuntimeState()

    private fun resetRewardRuntimeState(): Unit =
        rewardWorkflow.resetRewardRuntimeState()

    private fun resetSettlementRuntimeState(): Unit =
        settlementWorkflow.resetSettlementRuntimeState()

    private fun resetCombatRuntimeState(): Unit =
        battleCombatWorkflow.resetCombatRuntimeState()

    private fun onShopPurchasePageObserved(pageState: LabyrinthEntryPageState): Unit =
        shopWorkflow.onShopPurchasePageObserved(pageState = pageState)

    private fun onMapPageChanged(pageState: LabyrinthEntryPageState, preservePendingNodeMoveConfirmation: Boolean): Unit =
        mapTransitionWorkflow.onMapPageChanged(pageState = pageState, preservePendingNodeMoveConfirmation = preservePendingNodeMoveConfirmation)

    private fun onBattlePageChanged(pageState: LabyrinthEntryPageState): Unit =
        battleTeamWorkflow.onBattlePageChanged(pageState = pageState)

    private fun onRewardPageChanged(pageState: LabyrinthEntryPageState): Unit =
        rewardWorkflow.onRewardPageChanged(pageState = pageState)

    private fun onEventPageChanged(pageState: LabyrinthEntryPageState): Unit =
        eventWorkflow.onEventPageChanged(pageState = pageState)

    private fun onShopPageChanged(pageState: LabyrinthEntryPageState): Unit =
        shopWorkflow.onShopPageChanged(pageState = pageState)

    private fun prepareMapEntry(): Unit =
        mapTransitionWorkflow.prepareMapEntry()

    private fun prepareEventEntry(): Unit =
        eventWorkflow.prepareEventEntry()

    private fun prepareShopEntry(blockType: Int): Unit =
        shopWorkflow.prepareShopEntry(blockType = blockType)

    private fun prepareBattleEntry(blockType: Int): Unit =
        battleCombatWorkflow.prepareBattleEntry(blockType = blockType)

    private fun prepareBattleEditor(blockType: Int): Unit =
        battleCombatWorkflow.prepareBattleEditor(blockType = blockType)

    private fun resetRewardOnStart(): Unit =
        rewardWorkflow.resetRewardOnStart()

    private fun resetShopOnStart(): Unit =
        shopWorkflow.resetShopOnStart()

    private fun resetRewardOnStop(): Unit =
        rewardWorkflow.resetRewardOnStop()

    private fun resetShopOnStop(): Unit =
        shopWorkflow.resetShopOnStop()

    private fun resetRelicsOnStart(): Unit =
        rewardWorkflow.resetRelicsOnStart()

    private fun planRewardPage(result: LabyrinthEntryFrameResult, frameWidth: Int, frameHeight: Int): LabyrinthPostEntryTapPlan? =
        rewardWorkflow.planRewardPage(result = result, frameWidth = frameWidth, frameHeight = frameHeight)

    private fun planEventAnimationPage(result: LabyrinthEntryFrameResult, frameWidth: Int, frameHeight: Int, characterAcquisitionActive: Boolean): LabyrinthPostEntryTapPlan? =
        eventWorkflow.planEventAnimationPage(result = result, frameWidth = frameWidth, frameHeight = frameHeight, characterAcquisitionActive = characterAcquisitionActive)

    private fun planSettlementPage(result: LabyrinthEntryFrameResult, frameWidth: Int, frameHeight: Int, finalAnimationActive: Boolean, finalAnimationPage: Boolean): LabyrinthPostEntryTapPlan? =
        settlementWorkflow.planSettlementPage(result = result, frameWidth = frameWidth, frameHeight = frameHeight, finalAnimationActive = finalAnimationActive, finalAnimationPage = finalAnimationPage)

    private fun planBattlePage(result: LabyrinthEntryFrameResult, frameWidth: Int, frameHeight: Int): LabyrinthPostEntryTapPlan? =
        battleCombatWorkflow.planBattlePage(result = result, frameWidth = frameWidth, frameHeight = frameHeight)

    private fun planRoleRewardPage(result: LabyrinthEntryFrameResult): LabyrinthPageActionProposal =
        rewardWorkflow.planRoleRewardPage(result = result)

    private fun updateRewardAnimationProgress(sessionId: AutomationSessionId, result: LabyrinthEntryFrameResult, timestampMillis: Long): Unit =
        rewardWorkflow.updateRewardAnimationProgress(sessionId = sessionId, result = result, timestampMillis = timestampMillis)

    private fun advanceBossTeamOnResultPage(sessionId: AutomationSessionId, result: LabyrinthEntryFrameResult, previousPostEntryState: LabyrinthEntryPageState?): Unit =
        battleCombatWorkflow.advanceBossTeamOnResultPage(sessionId = sessionId, result = result, previousPostEntryState = previousPostEntryState)

    // Wiring is kept outside the lifecycle facade; the original private entry methods remain above.
    internal val battleRecommendationWorkflow by lazy { createBattleRecommendationWorkflow() }
    internal val battleRosterWorkflow by lazy { createBattleRosterWorkflow() }
    internal val battleTeamWorkflow by lazy { createBattleTeamWorkflow() }
    internal val battleCombatWorkflow by lazy { createBattleCombatWorkflow() }
    internal val encounterWorkflow by lazy { createEncounterWorkflow() }
    internal val equipmentWorkflow by lazy { createEquipmentWorkflow() }
    internal val rewardWorkflow by lazy { createRewardWorkflow() }
    internal val eventWorkflow by lazy { createEventWorkflow() }
    internal val shopWorkflow by lazy { createShopWorkflow() }
    internal val settlementWorkflow by lazy { createSettlementWorkflow() }
    internal val mapNavigationWorkflow by lazy { createMapNavigationWorkflow() }
    internal val mapTransitionWorkflow by lazy { createMapTransitionWorkflow() }
    internal val orphanMoveRecoveryWorkflow by lazy { createOrphanMoveRecoveryWorkflow() }
    internal val mapViewportWorkflow by lazy { createMapViewportWorkflow() }
    internal val runBoundaryWorkflow by lazy { createRunBoundaryWorkflow() }
    internal val entryWorkflow by lazy { createEntryWorkflow() }
    internal val recoveryWorkflow by lazy { createRecoveryWorkflow() }
    internal val persistenceWorkflow by lazy { createPersistenceWorkflow() }
    internal val pageRouterWorkflow by lazy { createPageRouterWorkflow() }
    internal val pageActionWorkflow by lazy { createPageActionWorkflow() }
    internal val presentationWorkflow by lazy { createPresentationWorkflow() }

    internal fun runtimeBeginGuardedAction(): Boolean =
        beginGuardedAction()

    internal fun runtimeEndGuardedAction(): Unit =
        endGuardedAction()

    internal fun runtimeFinishFromPlanner(sessionId: AutomationSessionId, reason: String): Unit =
        finishFromPlanner(sessionId = sessionId, reason = reason)

    internal fun runtimeLaunchGuardedAction(block: suspend () -> Unit): Unit =
        launchGuardedAction(block = block)

    internal fun runtimeNodeExecutionConfigured(): Boolean =
        nodeExecutionConfigured()

    internal fun runtimeNodeLog(message: String, warning: Boolean): Unit =
        nodeLog(message = message, warning = warning)

    internal fun runtimePublishOverlay(sessionId: AutomationSessionId): Unit =
        publishOverlay(sessionId = sessionId)

    internal fun runtimePublishWaitingForGameForeground(sessionId: AutomationSessionId): Unit =
        publishWaitingForGameForeground(sessionId = sessionId)

    internal fun runtimeSessionActionIntervalElapsed(timestampMillis: Long): Boolean =
        sessionActionIntervalElapsed(timestampMillis = timestampMillis)

    internal suspend fun runtimeStop(
        reason: String,
        releaseCapture: Boolean,
    ): Boolean =
        stop(reason = reason, releaseCapture = releaseCapture)

    internal suspend fun runtimeStopFromRunLogic(reason: String): Boolean =
        stopFromRunLogic(reason = reason)

    internal fun runtimeTraceAction(label: String): Unit =
        traceAction(label = label)

    internal fun runtimeTraceReject(reason: String): Unit =
        traceReject(reason = reason)

    internal fun runtimeTraceWorkflow(owner: String, phase: String): Unit =
        traceWorkflow(owner = owner, phase = phase)
}
