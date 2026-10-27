package com.landosol.toolbox.labyrinth.execution.map

import com.landosol.toolbox.labyrinth.*
import com.landosol.toolbox.automation.AutomationActionResult
import com.landosol.toolbox.automation.AutomationSessionId
import com.landosol.toolbox.automation.SessionBoundActionExecutor
import com.landosol.toolbox.automation.capture.CaptureStateRegistry
import com.landosol.toolbox.automation.AutomationAction
import com.landosol.toolbox.automation.ScreenPoint
import com.landosol.toolbox.automation.accessibility.GAME_NOT_FOREGROUND_REASON
import com.landosol.toolbox.labyrinth.vision.EntryPixelRect
import com.landosol.toolbox.labyrinth.vision.LabyrinthEntryFrameResult
import com.landosol.toolbox.labyrinth.vision.LabyrinthEntryPageState
import com.landosol.toolbox.labyrinth.node.LabyrinthNodeSession
import com.landosol.toolbox.labyrinth.node.LabyrinthNodeTypes
import com.landosol.toolbox.labyrinth.node.NodeSessionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.landosol.toolbox.labyrinth.LabyrinthExecutionLimits.MAX_NODE_MOVE_CONFIRMATION_ATTEMPTS
import com.landosol.toolbox.labyrinth.LabyrinthExecutionLimits.MAX_NODE_TAP_ATTEMPTS
import com.landosol.toolbox.labyrinth.LabyrinthExecutionLimits.NODE_ENTRY_CONFIRMATION_TIMEOUT_MILLIS
import com.landosol.toolbox.labyrinth.LabyrinthExecutionLimits.NODE_ENTRY_MISMATCH_STABLE_FRAMES
import com.landosol.toolbox.labyrinth.LabyrinthExecutionLimits.NODE_MOVE_CONFIRMATION_APPEAR_TIMEOUT_MILLIS
import com.landosol.toolbox.labyrinth.LabyrinthExecutionLimits.NODE_MOVE_CONFIRMATION_RETRY_INTERVAL_MILLIS
import com.landosol.toolbox.labyrinth.LabyrinthExecutionLimits.NODE_MOVE_CONFIRMATION_STABLE_FRAMES

internal interface LabyrinthMapTransitionPort {
    val _state: MutableStateFlow<LabyrinthEntryRecognitionSessionState>
    val actionExecutor: SessionBoundActionExecutor?
    val actionScope: CoroutineScope
    val activeNodeArea: Int?
    val activeNodeType: Int?
    val activeRunAccountId: Long?
    val activeSessionId: AutomationSessionId?
    val clock: () -> Long
    var combatContext: LabyrinthCombatContext?
    var finalBossLocalizationStartedAt: Long
    var lastNodeScrollSourceSignature: String?
    val lastNodeSwipeAvoidRects: List<EntryPixelRect>
    var nodeScrollAttempts: Int
    val nodeSession: LabyrinthNodeSession?
    var nodeTapAttempts: Int
    val persistenceMutex: Mutex
    var postBossStage: LabyrinthPostBossStage
    var postBossStartedAt: Long
    var postBossUnknownAttempts: Int
    val routeProgressSaver: (suspend (Long, Long, Long) -> Boolean)?
    var validatedRoute: LabyrinthRouteJson?
    fun beginGuardedAction(): Boolean
    fun finishFromPlanner(sessionId: AutomationSessionId, reason: String): Unit
    fun launchGuardedAction(block: suspend () -> Unit): Unit
    fun nodeLog(message: String, warning: Boolean = false): Unit
    fun prepareNodeTransitionContext(blockType: Int, area: Int?): Unit
    fun publishOverlay(sessionId: AutomationSessionId): Unit
    fun publishRouteProgress(
            sessionId: AutomationSessionId,
            session: LabyrinthNodeSession,
            nextLabel: String?,
            nextRect: EntryPixelRect?,
            complete: Boolean = false,
        ): Unit
    fun publishWaitingForGameForeground(sessionId: AutomationSessionId): Unit
    fun requestNodeMapNudge(
            sessionId: AutomationSessionId,
            label: String,
            targetBlockId: Long,
            frameWidth: Int,
            frameHeight: Int,
            timestampMillis: Long,
            viewportSignature: String,
            avoidRects: List<EntryPixelRect>,
            reason: String,
        ): Unit
    fun resetNodeRecoveryNudgeTracking(): Unit
    fun resetNodeScrollSearchGate(): Unit
    fun resetPendingNodeClickStability(): Unit
    fun resetStrongRouteTargetBinding(): Unit
    suspend fun stopFromRunLogic(reason: String): Boolean
    fun traceAction(label: String): Unit
    fun traceWorkflow(owner: String, phase: String): Unit
}

/**
 * Owns the complete pending-node lifecycle: accepted tap, confirmation receipt, destination
 * evidence and timeout. Navigation and recovery send events instead of changing these fields.
 * Confirmed receipts survive page/entry invalidation, and are cleared only at the original
 * destination-confirmed, new-run or full-map-reset boundaries.
 */
internal class LabyrinthMapTransitionWorkflow(private val port: LabyrinthMapTransitionPort) : LabyrinthMapTransitionPort by port {
    @Volatile
    private var pendingNodeTransition: PendingNodeTransition? = null

    // Semantic receipt survives page-local invalidation during title/loading re-entry.
    // Keep transition + source atomic so frame processing can never observe a mismatched pair.
    @Volatile
    private var confirmedNodeEntryReceipt: ConfirmedNodeEntryReceipt? = null

    @Volatile
    private var nodeMoveConfirmationStableFrames = 0

    @Volatile
    private var lastNodeMoveConfirmationRect: EntryPixelRect? = null

    @Volatile
    private var nodeEntryMismatchFrames = 0

    private var nodeEntryMismatchPage: LabyrinthEntryPageState? = null

    fun hasPendingNodeTransition(): Boolean = pendingNodeTransition != null

    /** Probe capture admission without consuming a frame or changing transition state. */
    fun pendingTransitionHoldsNextMapFrame(timestampMillis: Long): Boolean {
        val pending = pendingNodeTransition
        return pending != null && labyrinthKeepsPendingNodeTransition(
            elapsedMillis = timestampMillis - (pending.confirmationDispatchedAtMillis ?: pending.dispatchedAtMillis),
            timeoutMillis = if (pending.confirmationDispatchedAtMillis == null) {
                NODE_MOVE_CONFIRMATION_APPEAR_TIMEOUT_MILLIS
            } else {
                NODE_ENTRY_CONFIRMATION_TIMEOUT_MILLIS
            },
            nodeSelectionFramesSinceAction = pending.nodeSelectionFramesSinceAction + 1,
        )
    }

    /** Return true while this map frame belongs to transition waiting or bounded tap recovery. */
    fun handlePendingNodeTransitionOnMapFrame(
        sessionId: AutomationSessionId,
        pageState: LabyrinthEntryPageState,
        frameWidth: Int,
        frameHeight: Int,
        timestampMillis: Long,
        viewportSignature: String,
    ): Boolean {
        pendingNodeTransition?.let { pending ->
            val observedPending = pending.copy(
                nodeSelectionFramesSinceAction = pending.nodeSelectionFramesSinceAction + 1,
            )
            pendingNodeTransition = observedPending
            val waitStartedAt = pending.confirmationDispatchedAtMillis ?: pending.dispatchedAtMillis
            val timeout = if (pending.confirmationDispatchedAtMillis == null) {
                NODE_MOVE_CONFIRMATION_APPEAR_TIMEOUT_MILLIS
            } else {
                NODE_ENTRY_CONFIRMATION_TIMEOUT_MILLIS
            }
            val elapsedMillis = timestampMillis - waitStartedAt
            if (
                labyrinthKeepsPendingNodeTransition(
                    elapsedMillis = elapsedMillis,
                    timeoutMillis = timeout,
                    nodeSelectionFramesSinceAction = observedPending.nodeSelectionFramesSinceAction,
                )
            ) {
                val message = if (pending.confirmationDispatchedAtMillis == null) {
                    "已点击${pending.label}，等待移动确认弹窗"
                } else {
                    "已确认移动到${pending.label}，等待游戏进入节点"
                }
                if (_state.value.message != message) {
                    _state.value = _state.value.copy(message = message)
                }
                return true
            }
            pendingNodeTransition = null
            resetNodeMoveConfirmationTracking()
            resetPendingNodeClickStability()
            if (nodeTapAttempts >= MAX_NODE_TAP_ATTEMPTS) {
                nodeTapAttempts = 0
                requestNodeMapNudge(
                    sessionId = sessionId,
                    label = pending.label,
                    targetBlockId = pending.blockId,
                    frameWidth = frameWidth,
                    frameHeight = frameHeight,
                    timestampMillis = timestampMillis,
                    viewportSignature = viewportSignature,
                    avoidRects = lastNodeSwipeAvoidRects,
                    reason = "node-tap-not-confirmed",
                )
                if (activeSessionId == sessionId) {
                    _state.value = _state.value.copy(message = "节点多次点击未被确认；已切换为轻微移动地图后重新定位并重试：${pending.label}")
                }
                return true
            }
            nodeLog(
                "node-transition-timeout target=${pending.label} " +
                    "elapsed=${elapsedMillis}ms " +
                    "nodeFrames=${observedPending.nodeSelectionFramesSinceAction} " +
                    "confirmationSent=${pending.confirmationDispatchedAtMillis != null} " +
                    "confirmationAttempts=${pending.confirmationAttempts} " +
                    "tapAttempts=$nodeTapAttempts/$MAX_NODE_TAP_ATTEMPTS " +
                    "page=${pageState.name}",
                warning = true,
            )
            _state.value = _state.value.copy(message = "游戏未进入${pending.label}，准备重试同一节点")
            return true
        }
        return false
    }

    /** Called only after a node tap was reported executed, not when it was merely planned. */
    fun onNodeTapExecuted(blockId: Long, blockType: Int, label: String, area: Int?) {
        prepareNodeTransitionContext(blockType, area)
        pendingNodeTransition = PendingNodeTransition(
            blockId = blockId,
            blockType = blockType,
            label = label,
            // Dense-map recognition can consume most of the timeout before the tap.
            dispatchedAtMillis = clock(),
        )
        resetPendingNodeClickStability()
    }

    /** Entry recovery drops the pending tap but retains a confirmed receipt for re-entry. */
    fun clearPendingNodeTransition() {
        pendingNodeTransition = null
    }

    fun resetNodeEntryMismatchTracking() {
        nodeEntryMismatchFrames = 0
        nodeEntryMismatchPage = null
    }

    /** The new-run boundary clears the old receipt before the rest of run initialization. */
    fun resetConfirmedNodeEntryReceiptOnStart() {
        confirmedNodeEntryReceipt = null
    }

    /**
     * Full map reset also clears the semantic receipt; confirmation counters reset at their
     * existing later boundary, after the caller has cleared node tap attempts.
     */
    fun resetMapTransitionRuntimeState() {
        pendingNodeTransition = null
        nodeEntryMismatchFrames = 0
        nodeEntryMismatchPage = null
        confirmedNodeEntryReceipt = null
    }

    fun recoverPendingNodeTransitionFromMoveConfirmation(
        sessionId: AutomationSessionId,
        session: LabyrinthNodeSession,
        blockId: Long,
        blockType: Int,
        area: Int,
        timestampMillis: Long,
        confidence: Double,
    ) {
        val label = "${LabyrinthNodeTypes.labelOf(blockType)}#$blockId"
        prepareNodeTransitionContext(blockType, area)
        pendingNodeTransition = PendingNodeTransition(
            blockId = blockId,
            blockType = blockType,
            label = label,
            dispatchedAtMillis = timestampMillis,
        )
        nodeTapAttempts = 0
        resetPendingNodeClickStability()
        resetNodeScrollSearchGate()
        nodeScrollAttempts = 0
        resetNodeRecoveryNudgeTracking()
        lastNodeScrollSourceSignature = null
        nodeLog(
            "node-move-confirm-recover target=$label confidence=${"%.3f".format(confidence)} " +
                "reason=unique-reachable-route-target",
        )
        if (activeSessionId == sessionId) {
            _state.value = _state.value.copy(
                combatContext = combatContext,
                message = "检测到移动确认弹窗，已按唯一可达路线恢复：$label",
            )
            publishRouteProgress(sessionId, session, nextLabel = label, nextRect = null)
            publishOverlay(sessionId)
        }
    }

    fun handleNodeMoveConfirmationFrame(
        sessionId: AutomationSessionId,
        result: LabyrinthEntryFrameResult,
        timestampMillis: Long,
    ): Boolean {
        val confirmation = result.nodeMoveConfirmation ?: run {
            resetNodeMoveConfirmationTracking()
            return false
        }
        val pending = pendingNodeTransition ?: run {
            resetNodeMoveConfirmationTracking()
            publishNodeMoveConfirmationMessage(
                sessionId,
                "检测到移动确认弹窗，但没有待确认的自动节点，请手动处理",
            )
            return true.also { traceWorkflow("map", "move-confirmation") }
        }
        if (timestampMillis < pending.dispatchedAtMillis) {
            resetNodeMoveConfirmationTracking()
            publishNodeMoveConfirmationMessage(sessionId, "等待节点点击后的新画面")
            return true.also { traceWorkflow("map", "move-confirmation") }
        }
        val rect = confirmation.confirmButtonRect
        if (lastNodeMoveConfirmationRect == rect) {
            nodeMoveConfirmationStableFrames++
        } else {
            lastNodeMoveConfirmationRect = rect
            nodeMoveConfirmationStableFrames = 1
        }
        if (nodeMoveConfirmationStableFrames < NODE_MOVE_CONFIRMATION_STABLE_FRAMES) {
            publishNodeMoveConfirmationMessage(sessionId, "已识别移动确认弹窗，正在确认稳定性")
            return true.also { traceWorkflow("map", "move-confirmation") }
        }
        val lastConfirmationAt = pending.confirmationDispatchedAtMillis
        if (
            lastConfirmationAt != null &&
            timestampMillis - lastConfirmationAt < NODE_MOVE_CONFIRMATION_RETRY_INTERVAL_MILLIS
        ) {
            publishNodeMoveConfirmationMessage(sessionId, "已点击移动确认，等待游戏响应")
            return true.also { traceWorkflow("map", "move-confirmation") }
        }
        if (pending.confirmationAttempts >= MAX_NODE_MOVE_CONFIRMATION_ATTEMPTS) {
            finishFromPlanner(sessionId, "移动确认按钮多次无响应：${pending.label}")
            return true.also { traceWorkflow("map", "move-confirmation") }
        }
        if (!beginGuardedAction()) return true.also { traceWorkflow("map", "move-confirmation") }
        dispatchNodeMoveConfirmation(sessionId, pending, rect)
        return true.also { traceWorkflow("map", "move-confirmation") }
    }

    fun dispatchNodeMoveConfirmation(
        sessionId: AutomationSessionId,
        pending: PendingNodeTransition,
        rect: EntryPixelRect,
    ) {
        traceAction("确认移动 ${pending.label}")
        launchGuardedAction {
            val tap = AutomationAction.Tap(
                ScreenPoint(
                    x = rect.left + rect.width / 2f,
                    y = rect.top + rect.height / 2f,
                ),
            )
            nodeLog(
                "node-move-confirm-tap target=${pending.label} rect=$rect " +
                    "tap=${tap.point.x},${tap.point.y} " +
                    "attempt=${pending.confirmationAttempts + 1}/$MAX_NODE_MOVE_CONFIRMATION_ATTEMPTS " +
                    "display=${CaptureStateRegistry.gestureDisplayId()}",
            )
            val executor = actionExecutor
            val result = if (executor == null) {
                AutomationActionResult.Rejected(tap, "动作执行器不可用")
            } else {
                executor.execute(sessionId, tap)
            }
            when (result) {
                is AutomationActionResult.Executed -> {
                    val currentPending = pendingNodeTransition
                    if (currentPending?.blockId == pending.blockId) {
                        pendingNodeTransition = currentPending.copy(
                            confirmationDispatchedAtMillis = clock(),
                            confirmationAttempts = currentPending.confirmationAttempts + 1,
                            nodeSelectionFramesSinceAction = 0,
                        )
                        val confirmed = pendingNodeTransition
                        val sourceId = port.nodeSession?.getState()?.currentNodeId
                        if (confirmed != null && sourceId != null) {
                            confirmedNodeEntryReceipt = ConfirmedNodeEntryReceipt(confirmed, sourceId)
                        }
                    }
                    if (activeSessionId == sessionId) {
                        val current = _state.value
                        _state.value = current.copy(
                            actionCount = current.actionCount + 1,
                            lastActionLabel = "确认移动 ${pending.label}",
                            message = "已确认移动到${pending.label}，等待游戏进入节点",
                        )
                        publishOverlay(sessionId)
                    }
                }

                is AutomationActionResult.Rejected -> {
                    if (result.reason == GAME_NOT_FOREGROUND_REASON) {
                        publishWaitingForGameForeground(sessionId)
                    } else {
                        stopFromRunLogic("移动确认点击被拒绝：${result.reason}")
                    }
                }
                AutomationActionResult.StaleSession -> Unit
                AutomationActionResult.Paused -> Unit
                is AutomationActionResult.DryRun -> Unit
            }
        }
    }

    fun publishNodeMoveConfirmationMessage(sessionId: AutomationSessionId, message: String) {
        if (activeSessionId != sessionId || _state.value.message == message) return
        _state.value = _state.value.copy(message = message)
        publishOverlay(sessionId)
    }

    fun resetNodeMoveConfirmationTracking() {
        nodeMoveConfirmationStableFrames = 0
        lastNodeMoveConfirmationRect = null
    }

    fun confirmPendingNodeTransition(
        sessionId: AutomationSessionId,
        pageState: LabyrinthEntryPageState,
    ) {
        if (!labyrinthConfirmsNodeEntry(pageState)) return
        val session = port.nodeSession ?: return
        val recoveredReceipt = confirmedNodeEntryReceipt?.takeIf { receipt ->
            labyrinthCanRecoverConfirmedNodeEntry(
                receipt.transition.blockType,
                pageState,
                receipt.sourceId,
                session.getState().currentNodeId,
                session.uniqueReachableRouteTarget()?.blockId == receipt.transition.blockId,
                receipt.transition.confirmationDispatchedAtMillis,
                clock(),
            )
        }
        val pending = pendingNodeTransition ?: recoveredReceipt?.transition ?: return
        if (pendingNodeTransition == null) {
            nodeLog(
                "node-entry-recovered target=${pending.label} page=${pageState.name} " +
                    "source=${recoveredReceipt?.sourceId}",
            )
        }
        if (!labyrinthNodeEntryMatchesExpectedType(pending.blockType, pageState)) {
            // Link and relic pages share their title and remaining-count anchors and differ by a
            // few glyphs in the instruction line; the first frame after entry can classify as the
            // wrong one of the pair (2026-09-15 23:55 bundle: link#20602 read as RELIC_CHOICE for
            // one frame, then LINK_CHOICE). Stopping on that frame left the route cursor behind
            // and the restarted session hunted the already-cleared node. Require agreement
            // across frames before treating a mismatch as real.
            nodeEntryMismatchFrames = if (nodeEntryMismatchPage == pageState) {
                nodeEntryMismatchFrames + 1
            } else {
                1
            }
            nodeEntryMismatchPage = pageState
            nodeLog(
                "node-entry-mismatch target=${pending.label} expected=" +
                    "${LabyrinthNodeTypes.labelOf(pending.blockType)} actual=${pageState.name} " +
                    "frames=$nodeEntryMismatchFrames/$NODE_ENTRY_MISMATCH_STABLE_FRAMES",
                warning = true,
            )
            if (nodeEntryMismatchFrames < NODE_ENTRY_MISMATCH_STABLE_FRAMES) {
                if (activeSessionId == sessionId) {
                    _state.value = _state.value.copy(
                        message = "节点${pending.label}进入页面识别为${pageState.name}，等待后续帧确认",
                    )
                }
                return
            }
            val observedType = labyrinthObservedDestinationType(pageState, pending.blockType)
            if (observedType == null) {
                // A generic reward or transition may belong to the previous node. Keep
                // observing instead of either advancing an unproven cursor or ending the run.
                _state.value = _state.value.copy(
                    message = "节点${pending.label}与${pageState.name}不符；等待可确认的实际页面",
                )
                return
            }
            nodeLog(
                "node-entry-reconciled target=${pending.label} expected=" +
                    "${LabyrinthNodeTypes.labelOf(pending.blockType)} actual=${pageState.name} " +
                    "observedType=${LabyrinthNodeTypes.labelOf(observedType)}",
                warning = true,
            )
            // The accepted tap proves progression, while the visible page owns the subsequent
            // shop/event/battle handler. Do not keep using the predicted type as runtime state.
            prepareNodeTransitionContext(observedType, activeNodeArea)
            _state.value = _state.value.copy(
                combatContext = combatContext,
                message = "路线预期${pending.label}，实际进入${pageState.name}；已记录差异并按实际页面继续",
            )
        }
        nodeEntryMismatchFrames = 0
        nodeEntryMismatchPage = null
        nodeLog(
            "node-entry-confirmed target=${pending.label} page=${pageState.name} " +
                "confirmationAttempts=${pending.confirmationAttempts} tapAttempts=$nodeTapAttempts",
        )
        session.afterClick(pending.blockId)
        confirmedNodeEntryReceipt = null
        persistNodeRouteProgress(sessionId, session.getState())
        if (!_state.value.dryRun && session.getState().isComplete &&
            activeNodeType in setOf(LabyrinthNodeTypes.BOSS, LabyrinthNodeTypes.EX_BATTLE) &&
            postBossStage == LabyrinthPostBossStage.NONE
        ) {
            // The final node is normally a Boss/EX page, so completion is observed here when
            // the destination page is first recognized—not later when the node matcher is
            // polled again. Keep the live run open for the complete settlement chain.
            postBossStage = LabyrinthPostBossStage.BEFORE_SCORE
            postBossStartedAt = clock()
            postBossUnknownAttempts = 0
        }
        pendingNodeTransition = null
        resetPendingNodeClickStability()
        nodeTapAttempts = 0
        resetNodeMoveConfirmationTracking()
        nodeScrollAttempts = 0
        resetNodeRecoveryNudgeTracking()
        resetNodeScrollSearchGate()
        lastNodeScrollSourceSignature = null
        if (activeSessionId == sessionId) {
            val combatMessage = combatContext?.let { context ->
                when (context.kind) {
                    LabyrinthCombatKind.NORMAL -> "普通战"
                    LabyrinthCombatKind.EX -> "EX战"
                    LabyrinthCombatKind.BOSS -> "Boss第${context.teamIndex}队"
                }
            }
            _state.value = _state.value.copy(
                combatContext = combatContext,
                message = "已确认进入节点：${pending.label}" +
                    (combatMessage?.let { "（$it）" } ?: ""),
            )
            publishRouteProgress(sessionId, session, nextLabel = null, nextRect = null)
            publishOverlay(sessionId)
        }
    }

    /**
     * Keep the saved route cursor aligned with the last destination page that the game actually
     * accepted. The Room store repeats the enterId and monotonic-order checks in its transaction,
     * so delayed writes cannot move a newer session backwards.
     */
    fun persistNodeRouteProgress(
        sessionId: AutomationSessionId,
        nodeState: NodeSessionState,
    ) {
        if (_state.value.dryRun) return
        val currentBlockId = nodeState.currentNodeId ?: return
        val accountId = activeRunAccountId ?: return
        val route = validatedRoute ?: return
        val saver = routeProgressSaver ?: return
        val advancedRoute = route.withAdvancedCurrentBlock(currentBlockId)
        if (advancedRoute == null) {
            nodeLog(
                "route-progress-rejected-memory enterId=${route.enterId} " +
                    "saved=${route.currentBlockId} requested=$currentBlockId area=${nodeState.currentArea}",
                warning = true,
            )
            return
        }
        validatedRoute = advancedRoute
        actionScope.launch {
            val result = runCatching {
                persistenceMutex.withLock {
                    saver(accountId, advancedRoute.enterId, currentBlockId)
                }
            }
            if (activeSessionId != sessionId) return@launch
            result.fold(
                onSuccess = { persisted ->
                    nodeLog(
                        if (persisted) {
                            "route-progress-persisted account=$accountId enterId=${advancedRoute.enterId} " +
                                "current=$currentBlockId area=${nodeState.currentArea}"
                        } else {
                            "route-progress-persist-rejected account=$accountId " +
                                "enterId=${advancedRoute.enterId} current=$currentBlockId"
                        },
                        warning = !persisted,
                    )
                },
                onFailure = { error ->
                    nodeLog(
                        "route-progress-persist-failed account=$accountId " +
                            "enterId=${advancedRoute.enterId} current=$currentBlockId " +
                            "error=${error.message ?: error::class.java.simpleName}",
                        warning = true,
                    )
                },
            )
        }
    }

    fun prepareMapTransitionFrame(sessionId: AutomationSessionId, pageState: LabyrinthEntryPageState, result: LabyrinthEntryFrameResult, timestampMillis: Long, dryRun: Boolean): Boolean? {
        val preservePendingNodeMoveConfirmation = !dryRun &&
            labyrinthPreservesPendingNodeTransitionForMoveConfirmation(
                pageState = pageState,
                hasPendingNodeTransition = pendingNodeTransition != null,
                hasMoveConfirmation = result.nodeMoveConfirmation != null,
            )
        val preservePendingNodeTransitionAcrossPage = !dryRun &&
            pendingNodeTransition?.let { pending ->
                labyrinthPreservesPendingNodeTransitionAcrossPage(
                    pageState = pageState,
                    confirmationDispatchedAtMillis = pending.confirmationDispatchedAtMillis,
                    nowMillis = timestampMillis,
                    timeoutMillis = NODE_ENTRY_CONFIRMATION_TIMEOUT_MILLIS,
                    dispatchedAtMillis = pending.dispatchedAtMillis,
                )
            } == true
        val preservePendingNodeTransitionForMismatch = !dryRun &&
            pendingNodeTransition?.let { pending ->
                labyrinthPreservesPendingNodeTransitionForEntryMismatch(
                    mismatchFrames = nodeEntryMismatchFrames,
                    stableFrames = NODE_ENTRY_MISMATCH_STABLE_FRAMES,
                    confirmationDispatchedAtMillis = pending.confirmationDispatchedAtMillis,
                    nowMillis = timestampMillis,
                    timeoutMillis = NODE_ENTRY_CONFIRMATION_TIMEOUT_MILLIS,
                    dispatchedAtMillis = pending.dispatchedAtMillis,
                )
            } == true
        val confirmedTransitionTimedOut = !dryRun &&
            pendingNodeTransition?.confirmationDispatchedAtMillis?.let { confirmedAt ->
                !labyrinthConfirmsNodeEntry(pageState) &&
                    pageState in setOf(
                        LabyrinthEntryPageState.UNKNOWN,
                        LabyrinthEntryPageState.NODE_MAP_VIEW,
                        LabyrinthEntryPageState.GAME_LOADING_PROGRESS,
                        LabyrinthEntryPageState.PRE_HOME_DATA_LOADING,
                    ) &&
                    (timestampMillis - confirmedAt).coerceAtLeast(0L) >= NODE_ENTRY_CONFIRMATION_TIMEOUT_MILLIS
            } == true
        if (confirmedTransitionTimedOut) {
            val pending = pendingNodeTransition
            if (pending != null) {
                nodeLog(
                    "node-entry-timeout target=${pending.label} after-confirmation page=${pageState.name} " +
                        "elapsed=${(timestampMillis - requireNotNull(pending.confirmationDispatchedAtMillis)).coerceAtLeast(0L)}ms",
                    warning = true,
                )
                pendingNodeTransition = null
                resetNodeMoveConfirmationTracking()
                resetPendingNodeClickStability()
                finishFromPlanner(
                    sessionId,
                    "已确认移动到${pending.label}，但在限定时间内未识别到对应节点页面；已停止并保留诊断状态",
                )
                return null
            }
        }
        return preservePendingNodeMoveConfirmation || preservePendingNodeTransitionAcrossPage || preservePendingNodeTransitionForMismatch
    }

    fun onMapPageChanged(pageState: LabyrinthEntryPageState, preservePendingNodeMoveConfirmation: Boolean) {
        if (pageState != LabyrinthEntryPageState.NODE_SELECTION) {
            finalBossLocalizationStartedAt = Long.MIN_VALUE
            resetPendingNodeClickStability()
            resetStrongRouteTargetBinding()
            resetNodeScrollSearchGate()
            if (!preservePendingNodeMoveConfirmation) {
                pendingNodeTransition = null
                nodeMoveConfirmationStableFrames = 0
                lastNodeMoveConfirmationRect = null
            }
            _state.value.routeProgress?.let { progress ->
                if (progress.nextNodeLabel != null || progress.nextNodeRect != null) {
                    _state.value = _state.value.copy(
                        routeProgress = progress.copy(nextNodeLabel = null, nextNodeRect = null),
                    )
                }
            }
        }
    }

    fun prepareMapEntry() {
        resetNodeMoveConfirmationTracking()
        nodeEntryMismatchFrames = 0
        nodeEntryMismatchPage = null
    }
}
