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
import com.landosol.toolbox.labyrinth.vision.NODE_SEARCH_MODE_DEFERRED
import com.landosol.toolbox.labyrinth.vision.LabyrinthEntryFrameResult
import com.landosol.toolbox.labyrinth.vision.LabyrinthEntryPageState
import com.landosol.toolbox.labyrinth.node.LabyrinthNodeSession
import com.landosol.toolbox.labyrinth.node.LabyrinthNodeActionPlanner
import com.landosol.toolbox.labyrinth.node.LabyrinthNodeViewportScanner
import com.landosol.toolbox.labyrinth.node.LabyrinthNodeTypes
import com.landosol.toolbox.labyrinth.node.NodeSearchHint
import com.landosol.toolbox.labyrinth.node.finalBossPlatformClickRect
import com.landosol.toolbox.labyrinth.node.NodeAction
import com.landosol.toolbox.labyrinth.node.NodeClassification
import com.landosol.toolbox.labyrinth.node.NodeTemplateSet
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import com.landosol.toolbox.labyrinth.LabyrinthExecutionLimits.MAX_NODE_ERROR_STREAK
import com.landosol.toolbox.labyrinth.LabyrinthExecutionLimits.MAX_NODE_TAP_ATTEMPTS
import com.landosol.toolbox.labyrinth.LabyrinthExecutionLimits.NODE_CLICK_INTERVAL_MILLIS
import com.landosol.toolbox.labyrinth.LabyrinthExecutionLimits.NODE_CLICK_STABLE_FRAMES
import com.landosol.toolbox.labyrinth.LabyrinthExecutionLimits.NODE_CONFLICT_NUDGE_STREAK
import com.landosol.toolbox.labyrinth.LabyrinthExecutionLimits.NODE_MAP_SETTLE_AFTER_POST_ACTION_MILLIS
import com.landosol.toolbox.labyrinth.LabyrinthExecutionLimits.NODE_SCROLL_CURRENT_VIEW_MIN_WAIT_MILLIS
import com.landosol.toolbox.labyrinth.LabyrinthExecutionLimits.NODE_SCROLL_CURRENT_VIEW_STABLE_FRAMES
import com.landosol.toolbox.labyrinth.LabyrinthExecutionLimits.NODE_STRONG_BINDING_MEMORY_MILLIS
import com.landosol.toolbox.labyrinth.LabyrinthExecutionLimits.NODE_TARGET_VISIBLE_MAX_WAIT_FRAMES
import com.landosol.toolbox.labyrinth.LabyrinthExecutionLimits.NODE_TARGET_VISIBLE_MAX_WAIT_MILLIS
import com.landosol.toolbox.labyrinth.LabyrinthExecutionLimits.NODE_WAIT_NUDGE_STREAK

internal interface LabyrinthMapNavigationPort {
    val _state: MutableStateFlow<LabyrinthEntryRecognitionSessionState>
    val actionExecutor: SessionBoundActionExecutor?
    val actionScope: CoroutineScope
    val activeRunAccountId: Long?
    val activeSessionId: AutomationSessionId?
    val characterAcquisitionActive: Boolean
    val clock: () -> Long
    var combatContext: LabyrinthCombatContext?
    var entryPhaseComplete: Boolean
    var lastNodeActionAt: Long
    var lastNodeScrollSourceSignature: String?
    var lastNodeSwipeAvoidRects: List<EntryPixelRect>
    val lastPostEntryActionAt: Long
    val nodeActionPlanner: LabyrinthNodeActionPlanner
    val nodeConflictRecovery: com.landosol.toolbox.labyrinth.node.LabyrinthNodeConflictRecovery
    var nodeInitFailedReason: String?
    val nodeInitStarted: AtomicBoolean
    var nodeScrollAttempts: Int
    var nodeSession: LabyrinthNodeSession?
    val nodeSessionFactory: () -> LabyrinthNodeSession
    var nodeTapAttempts: Int
    val nodeTemplateLoader: (() -> NodeTemplateSet)?
    val nodeViewportScanner: LabyrinthNodeViewportScanner
    var postBossStage: LabyrinthPostBossStage
    var postBossStartedAt: Long
    var postBossUnknownAttempts: Int
    val routeLoader: (suspend (Long?) -> LabyrinthRouteJson?)?
    var routeNodeCount: Int
    var validatedRoute: LabyrinthRouteJson?
    fun beginGuardedAction(): Boolean
    fun clearCharacterAcquisitionContext(): Unit
    fun describeNodeClassifications(classifications: List<NodeClassification>): String
    fun finishFromPlanner(sessionId: AutomationSessionId, reason: String): Unit
    fun launchGuardedAction(block: suspend () -> Unit): Unit
    fun nodeClickRectsAreStable(
            previous: EntryPixelRect?,
            current: EntryPixelRect?,
        ): Boolean
    fun nodeLog(message: String, warning: Boolean = false): Unit
    fun publishOverlay(sessionId: AutomationSessionId): Unit
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
    fun requestNodeMapScroll(
            sessionId: AutomationSessionId,
            label: String,
            frameWidth: Int,
            frameHeight: Int,
            timestampMillis: Long,
            viewportSignature: String,
            avoidRects: List<EntryPixelRect>,
        ): Unit
    fun resetNodeMoveConfirmationTracking(): Unit
    fun resetMapTransitionRuntimeState()
    fun onNodeTapExecuted(blockId: Long, blockType: Int, label: String, area: Int?)
    fun pendingTransitionHoldsNextMapFrame(timestampMillis: Long): Boolean
    fun handlePendingNodeTransitionOnMapFrame(
        sessionId: AutomationSessionId,
        pageState: LabyrinthEntryPageState,
        frameWidth: Int,
        frameHeight: Int,
        timestampMillis: Long,
        viewportSignature: String,
    ): Boolean
    fun resetNodeRecoveryNudgeTracking(): Unit
    suspend fun stopFromRunLogic(reason: String): Boolean
    fun traceAction(label: String): Unit
    fun traceReject(reason: String): Unit
    fun traceWorkflow(owner: String, phase: String): Unit
}

/** 初始化路线、定位目标节点并提出节点点击。 */
internal class LabyrinthMapNavigationWorkflow(private val port: LabyrinthMapNavigationPort) : LabyrinthMapNavigationPort by port {
    @Volatile
    private var pendingNodeClickBlockId: Long = Long.MIN_VALUE

    @Volatile
    private var pendingNodeClickRect: EntryPixelRect? = null

    @Volatile
    private var pendingNodeClickStableFrames = 0

    @Volatile
    private var strongRouteTargetBindingBlockId: Long = Long.MIN_VALUE

    @Volatile
    private var strongRouteTargetBindingRect: EntryPixelRect? = null

    @Volatile
    private var strongRouteTargetBindingAtMillis: Long = Long.MIN_VALUE

    @Volatile
    private var pendingNodeScrollBlockId = Long.MIN_VALUE

    @Volatile
    private var pendingNodeScrollViewportSignature: String? = null

    @Volatile
    private var pendingNodeScrollStableFrames = 0

    @Volatile
    private var pendingNodeScrollStartedAt = Long.MIN_VALUE

    @Volatile
    private var finalBossLocalizationStartedAt = Long.MIN_VALUE

    @Volatile
    private var nodeErrorStreak = 0

    fun resetPendingNodeClickStability() {
        pendingNodeClickBlockId = Long.MIN_VALUE
        pendingNodeClickRect = null
        pendingNodeClickStableFrames = 0
    }

    /** Any map gesture or page change may move the camera; strong-column memory is then stale. */
    fun resetStrongRouteTargetBinding() {
        strongRouteTargetBindingBlockId = Long.MIN_VALUE
        strongRouteTargetBindingRect = null
        strongRouteTargetBindingAtMillis = Long.MIN_VALUE
    }

    fun resetNodeScrollSearchGate() {
        pendingNodeScrollBlockId = Long.MIN_VALUE
        pendingNodeScrollViewportSignature = null
        pendingNodeScrollStableFrames = 0
        pendingNodeScrollStartedAt = Long.MIN_VALUE
    }

    fun resetFinalBossLocalization() {
        finalBossLocalizationStartedAt = Long.MIN_VALUE
    }

    fun resetNodeErrorTracking() {
        nodeErrorStreak = 0
    }

    /**
     * Supplies the frame processor with route-aware search bounds without letting the vision layer
     * read mutable session internals directly. A missing viewport fit deliberately keeps the full
     * scan so the first reliable topology fit can be established instead of inventing coordinates.
     */
    /**
     * The map scan is only worth running once a route session can consume it. The first map frame
     * after entry only starts asynchronous route/template loading and returns; scanning it burns
     * the whole 16-anchor full sweep for nothing.
     */
    fun nodeScanRequested(): Boolean = nodeSession != null

    /**
     * Whether the next node-page frame's classifications would be consumed. Mirrors the two early
     * returns at the top of [handleNodeSelectionFrame]; see [labyrinthNodeScanConsumable].
     *
     * The pending-transition check deliberately counts the frame being decided as one more
     * node-page frame, so the retry guard in [labyrinthKeepsPendingNodeTransition] still lets the
     * second post-timeout frame through to be scanned and to expire the transition.
     */
    fun nodeScanConsumable(): Boolean {
        val now = clock()
        val lastAction = lastPostEntryActionAt
        val settleRemaining = if (lastAction == Long.MIN_VALUE) {
            0L
        } else {
            NODE_MAP_SETTLE_AFTER_POST_ACTION_MILLIS - (now - lastAction)
        }
        val pendingHeld = pendingTransitionHoldsNextMapFrame(now)
        return labyrinthNodeScanConsumable(
            settleRemainingMillis = settleRemaining,
            pendingTransitionHeld = pendingHeld,
        )
    }

    fun currentNodeSearchHint(): NodeSearchHint? {
        val session = nodeSession ?: return null
        val target = session.currentRouteTarget() ?: return null
        val expectedCenterX = nodeViewportScanner.expectedScreenCenterX(target.column)?.toInt()
        return NodeSearchHint(
            targetBlockId = target.blockId,
            expectedCenterX = expectedCenterX,
            expectedTypes = session.searchTypesNearTarget(target),
            reachableColumns = (target.column - 1).coerceAtLeast(1)..(target.column + 1),
            matchedTargetBlockId = session.matchedRouteTargetId(),
        )
    }

    fun handleNodeSelectionFrame(
        sessionId: AutomationSessionId,
        result: LabyrinthEntryFrameResult,
        frameWidth: Int,
        frameHeight: Int,
        timestampMillis: Long,
    ) {
        traceWorkflow("map", "locate-node")
        val visibleNodeRects = result.nodeClassifications.mapNotNull { it.screenRect }
        if (visibleNodeRects.isNotEmpty()) {
            lastNodeSwipeAvoidRects = visibleNodeRects
        }
        val session = nodeSession
        if (session == null) {
            maybeStartNodeInit(sessionId)
            return
        }
        // A reward animation may end on a map frame without a separately recognized close
        // page. Once the node page is back, the bounded portrait fallback must not leak into
        // the next node or keep tapping the map.
        if (characterAcquisitionActive) {
            clearCharacterAcquisitionContext()
        }
        // 关闭角色加入/奖励等弹窗后，地图会经历短暂的过渡动画。此时节点模板可能与
        // 弹窗残影同时命中，立即点击或滚图会把脏帧当成稳定地图。
        if (lastPostEntryActionAt != Long.MIN_VALUE &&
            timestampMillis - lastPostEntryActionAt < NODE_MAP_SETTLE_AFTER_POST_ACTION_MILLIS
        ) {
            val message = "弹窗关闭后等待节点地图稳定"
            if (_state.value.message != message) {
                _state.value = _state.value.copy(message = message)
                publishOverlay(sessionId)
            }
            return
        }
        val nodeState = session.getState()
        // Two identities on purpose. The classification-bucketed signature is stable across the
        // map's glow animation and gates "hold the camera N frames before scrolling"; the pixel
        // hash keys the viewport tracker and stays available when no node can be classified.
        val viewportSignature = labyrinthNodeViewportSignature(result.nodeClassifications)
        val trackerSignature = result.nodeViewportSignature.ifBlank { viewportSignature }
        nodeLog(
            "node-frame size=${frameWidth}x${frameHeight} " +
                "area=${nodeState.currentArea} current=${nodeState.currentNodeId} " +
                "visited=${nodeState.visitedNodes.size} " +
                "classes=${describeNodeClassifications(result.nodeClassifications)}",
        )
        if (handlePendingNodeTransitionOnMapFrame(
                sessionId, result.observation.state, frameWidth, frameHeight, timestampMillis, viewportSignature,
            )
        ) return
        // The processor skipped this frame's map scan because, when it looked, one of the two
        // gates above was still closed (see nodeScanConsumable). Both gates only open with time,
        // so this frame is normally caught above; the exception is a pending transition cleared
        // by an action coroutine in between. An unscanned frame carries no node evidence and
        // must not be read as "no nodes visible".
        if (result.nodeSearchMode == NODE_SEARCH_MODE_DEFERRED) return
        // Bind any current-frame ordinary-node evidence. The final-Boss-only fast path supplies
        // none: observing that empty mapping also invalidates old camera coordinates after a pan.
        val rawNodeAction = session.processClassifications(result.nodeClassifications)
        val conflictTargetId = (rawNodeAction as? NodeAction.TypeConflict)?.blockId
        val visuallyPlannedAction = nodeConflictRecovery.resolve(
            session = sessionId.toString(),
            action = rawNodeAction,
            visible = result.nodeClassifications,
            uniqueReachableRouteTarget = conflictTargetId != null &&
                session.uniqueReachableRouteTarget()?.blockId == conflictTargetId,
        )
        if (rawNodeAction is NodeAction.TypeConflict && visuallyPlannedAction is NodeAction.ClickNode) {
            nodeLog("node-conflict-recovery route=${visuallyPlannedAction.blockId} generic-route-bound stable=3+ rect=${visuallyPlannedAction.screenRect}")
        }
        session.getLastMatchResult()?.let { match ->
            val snapshot = nodeViewportScanner.observe(
                area = nodeState.currentArea,
                viewportSignature = trackerSignature,
                frameWidth = frameWidth,
                frameHeight = frameHeight,
                matchResult = match,
                viewportPixels = result.nodeViewportPixels,
            )
            val target = match.nextNode
            val current = _state.value
            _state.value = current.copy(
                frameTrace = current.frameTrace.copy(
                    nodeTargetBlockId = target?.blockId,
                    nodeTargetColumn = target?.column,
                    nodeTrackerReliable = snapshot?.geometryReliable,
                    nodeTrackerWorldLeft = snapshot?.viewportWorldLeft,
                    nodeTargetExpectedX = target?.let { nodeViewportScanner.expectedScreenCenterX(it.column) },
                ),
            )
        }

        val directFinalBoss = session.directFinalBossTarget()
        val action: NodeAction
        if (directFinalBoss != null) {
            val predictedX = nodeViewportScanner.expectedScreenCenterX(directFinalBoss.column)
            val rect = finalBossPlatformClickRect(
                matches = result.finalBossPlatforms,
                frameWidth = frameWidth,
                frameHeight = frameHeight,
                predictedX = predictedX,
            )
            nodeLog(
                "node-direct-final-boss target=Boss#${directFinalBoss.blockId} " +
                    "area=${directFinalBoss.area} column=${directFinalBoss.column} " +
                    "predictedX=${predictedX?.let { "%.1f".format(it) } ?: "n/a"} " +
                    "platforms=${result.finalBossPlatforms} candidates=${result.finalBossPlatformCandidates} rect=$rect",
            )
            if (rect == null) {
                resetPendingNodeClickStability()
                publishRouteProgress(sessionId, session, nextLabel = "Boss#${directFinalBoss.blockId}", nextRect = null)
                if (finalBossLocalizationStartedAt == Long.MIN_VALUE) finalBossLocalizationStartedAt = timestampMillis
                if (!_state.value.dryRun &&
                    timestampMillis - finalBossLocalizationStartedAt >= NODE_TARGET_VISIBLE_MAX_WAIT_MILLIS
                ) {
                    requestNodeMapNudge(
                        sessionId = sessionId,
                        label = "Boss#${directFinalBoss.blockId}",
                        targetBlockId = directFinalBoss.blockId,
                        frameWidth = frameWidth,
                        frameHeight = frameHeight,
                        timestampMillis = timestampMillis,
                        viewportSignature = viewportSignature,
                        avoidRects = lastNodeSwipeAvoidRects,
                        reason = "final-boss-platform-not-localized",
                    )
                    finalBossLocalizationStartedAt = timestampMillis
                    return
                }
                _state.value = _state.value.copy(message = "最终Boss底座暂未可靠定位；保持节点页，稍后将轻微左右移动地图重新识别")
                return
            }
            finalBossLocalizationStartedAt = Long.MIN_VALUE
            action = NodeAction.ClickNode(
                blockId = directFinalBoss.blockId,
                blockType = directFinalBoss.blockType,
                screenRect = rect,
            )
        } else {
            finalBossLocalizationStartedAt = Long.MIN_VALUE
            action = visuallyPlannedAction
        }
        if (action !is NodeAction.ClickNode) {
            // "Stable frames" must be consecutive. A conflict/wait frame, or a target crop that
            // disappears, invalidates any click evidence accumulated before it.
            resetPendingNodeClickStability()
        }
        when (action) {
            is NodeAction.ClickNode -> {
                nodeLog(
                    "node-decision target=${LabyrinthNodeTypes.labelOf(action.blockType)}#${action.blockId} " +
                        "rect=${action.screenRect}",
                )
                nodeErrorStreak = 0
                onNodeClickPlanned(
                    sessionId = sessionId,
                    session = session,
                    action = action,
                    frameWidth = frameWidth,
                    frameHeight = frameHeight,
                    timestampMillis = timestampMillis,
                    viewportSignature = viewportSignature,
                )
            }

            is NodeAction.TypeConflict -> {
                val expected = LabyrinthNodeTypes.labelOf(action.expectedBlockType)
                val detected = LabyrinthNodeTypes.labelOf(action.detectedBlockType)
                val label = "$expected#${action.blockId}"
                nodeLog(
                    "node-decision TYPE_CONFLICT target=$label detected=$detected " +
                        "confidence=${"%.3f".format(action.confidence)} " +
                        "topology=${action.topologyBindingKind ?: "none"}/" +
                        "${action.topologyConfidence?.let { "%.3f".format(it) } ?: "n/a"} " +
                        "rect=${action.screenRect}",
                    warning = true,
                )
                // Every node type can be recovered when route-bound visual evidence is stable.
                // If the conflict remains ambiguous, actively perturb the background instead of
                // watching the same bad crop forever.
                nodeErrorStreak++
                publishRouteProgress(sessionId, session, nextLabel = label, nextRect = action.screenRect)
                val message = "节点类型待复核：视觉$detected / 路线$label，正在结合路线与当前画面自恢复"
                if (activeSessionId == sessionId) {
                    _state.value = _state.value.copy(message = message)
                    publishOverlay(sessionId)
                }
                if (!_state.value.dryRun && nodeErrorStreak >= NODE_CONFLICT_NUDGE_STREAK) {
                    requestNodeMapNudge(
                        sessionId = sessionId,
                        label = label,
                        targetBlockId = action.blockId,
                        frameWidth = frameWidth,
                        frameHeight = frameHeight,
                        timestampMillis = timestampMillis,
                        viewportSignature = viewportSignature,
                        avoidRects = lastNodeSwipeAvoidRects,
                        reason = "persistent-type-conflict",
                    )
                }
            }

            NodeAction.WaitForLoad -> {
                nodeLog("node-decision wait-for-load")
                val waitTarget = session.getLastMatchResult()?.nextNode
                val waitLabel = waitTarget?.let { "${LabyrinthNodeTypes.labelOf(it.blockType)}#${it.blockId}" }
                publishRouteProgress(sessionId, session, nextLabel = waitLabel, nextRect = null)
                nodeErrorStreak++
                if (!_state.value.dryRun && waitTarget != null && nodeErrorStreak >= NODE_WAIT_NUDGE_STREAK) {
                    requestNodeMapNudge(
                        sessionId = sessionId,
                        label = waitLabel ?: "节点#${waitTarget.blockId}",
                        targetBlockId = waitTarget.blockId,
                        frameWidth = frameWidth,
                        frameHeight = frameHeight,
                        timestampMillis = timestampMillis,
                        viewportSignature = viewportSignature,
                        avoidRects = lastNodeSwipeAvoidRects,
                        reason = "route-target-visible-but-not-active",
                    )
                } else if (activeSessionId == sessionId && waitTarget != null) {
                    _state.value = _state.value.copy(message = "已定位路线目标但节点尚未稳定激活，继续识别；持续无变化时将轻微移动地图")
                }
            }

            NodeAction.Complete -> {
                publishRouteProgress(sessionId, session, nextLabel = null, nextRect = null, complete = false)
                if (_state.value.dryRun) {
                    _state.value = _state.value.copy(message = "路线节点回放完成")
                } else {
                    // The node graph can finish as soon as the final Boss node is accepted. The
                    // run is not complete yet: Congratulations, score, chest and final item
                    // pages still have to be consumed and the game must return to its idle home.
                    if (postBossStage == LabyrinthPostBossStage.NONE) {
                        postBossStage = LabyrinthPostBossStage.BEFORE_SCORE
                        postBossStartedAt = timestampMillis
                        postBossUnknownAttempts = 0
                    }
                    _state.value = _state.value.copy(message = "路线节点已完成，等待最终结算链")
                }
            }

            is NodeAction.Error -> {
                nodeLog("node-decision error=${action.message}", warning = true)
                nodeErrorStreak++
                if (!_state.value.dryRun && nodeErrorStreak >= MAX_NODE_ERROR_STREAK) {
                    finishFromPlanner(sessionId, "路线执行连续失败：${action.message}")
                } else if (activeSessionId == sessionId) {
                    _state.value = _state.value.copy(message = "路线匹配失败：${action.message}")
                }
            }
        }
    }

    fun maybeStartNodeInit(sessionId: AutomationSessionId) {
        if (!nodeInitStarted.compareAndSet(false, true)) return
        val loadRoute = routeLoader
        val loadTemplates = nodeTemplateLoader
        if (loadRoute == null || loadTemplates == null) {
            nodeInitFailedReason = "节点执行未配置"
            finishFromPlanner(sessionId, nodeInitFailedReason!!)
            return
        }
        val accountId = activeRunAccountId
        actionScope.launch {
            val failure: String? = run {
                val route = validatedRoute ?: runCatching { loadRoute(accountId) }.getOrElse { error ->
                    return@run "读取已保存路线失败：${error.message ?: "未知错误"}"
                } ?: return@run "未找到已保存路线，请先完成刷开局"
                validatedRoute = route
                if (route.allNodes.isEmpty()) {
                    return@run "已保存路线缺少完整地图数据，请重新刷取一次"
                }
                val templates = runCatching(loadTemplates).getOrElse { error ->
                    return@run "加载节点模板失败：${error.message ?: "未知错误"}"
                }
                val session = nodeSessionFactory()
                val routeNodes = route.toLabyrinthRoute().nodes
                val initializationFailure = runCatching {
                    session.initialize(
                        allNodes = route.toFullMap(),
                        route = routeNodes,
                        nodeTemplates = templates,
                        currentNodeId = route.currentBlockId,
                    )
                }.exceptionOrNull()
                if (initializationFailure != null) {
                    return@run "初始化节点会话失败：${initializationFailure.message ?: "未知错误"}"
                }
                routeNodeCount = routeNodes.size
                nodeSession = session
                null
            }
            if (activeSessionId != sessionId) return@launch
            if (failure != null) {
                nodeInitFailedReason = failure
                finishFromPlanner(sessionId, failure)
                return@launch
            } else {
                nodeSession?.let { initialized ->
                    val state = initialized.getState()
                    nodeLog(
                        "route-init area=${state.currentArea} current=${state.currentNodeId} " +
                            "visited=${state.visitedNodes.size} routeNodes=$routeNodeCount",
                    )
                }
                _state.value = _state.value.copy(
                    message = "路线已加载，开始节点${if (_state.value.dryRun) "回放" else "执行"}",
                )
                nodeSession?.let { publishRouteProgress(sessionId, it, nextLabel = null, nextRect = null) }
            }
            publishOverlay(sessionId)
        }
    }

    fun onNodeClickPlanned(
        sessionId: AutomationSessionId,
        session: LabyrinthNodeSession,
        action: NodeAction.ClickNode,
        frameWidth: Int,
        frameHeight: Int,
        timestampMillis: Long,
        viewportSignature: String,
    ) {
        val label = "${LabyrinthNodeTypes.labelOf(action.blockType)}#${action.blockId}"
        publishRouteProgress(sessionId, session, nextLabel = label, nextRect = action.screenRect)
        if (_state.value.dryRun) return

        val rect = action.screenRect
        val targetMapping = session.getLastMatchResult()?.visibleNodes
            ?.filter { it.blockId == action.blockId && it.screenRect == rect }
            ?.maxByOrNull { it.topologyConfidence ?: 0.0 }
        val strongOrdered = targetMapping?.let(::labyrinthNodeMappingHasStrongOrderedTopology) == true
        if (strongRouteTargetBindingBlockId != action.blockId) {
            resetStrongRouteTargetBinding()
        }
        if (
            labyrinthNodeClickContradictsRecentStrongBinding(
                rememberedRect = strongRouteTargetBindingRect,
                rememberedAtMillis = strongRouteTargetBindingAtMillis,
                nowMillis = timestampMillis,
                memoryMillis = NODE_STRONG_BINDING_MEMORY_MILLIS,
                candidateRect = rect,
                candidateHasStrongOrderedTopology = strongOrdered,
            )
        ) {
            resetPendingNodeClickStability()
            val source = when {
                targetMapping?.routeSemanticRepair == true -> "semantic-repair"
                else -> targetMapping?.topologyBindingKind?.name ?: "none"
            }
            nodeLog(
                "node-click-held target=$label reason=weak-rebinding-contradicts-strong-column " +
                    "strongRect=$strongRouteTargetBindingRect candidateRect=$rect source=$source " +
                    "age=${timestampMillis - strongRouteTargetBindingAtMillis}ms",
                warning = true,
            )
            traceReject("node-weak-rebinding")
            if (activeSessionId == sessionId) {
                _state.value = _state.value.copy(
                    message = "$label 刚以完整列定位，当前帧仅剩单个候选且位置不同；不采信，等待完整列重新出现",
                )
            }
            return
        }
        if (strongOrdered && rect != null) {
            strongRouteTargetBindingBlockId = action.blockId
            strongRouteTargetBindingRect = rect
            strongRouteTargetBindingAtMillis = timestampMillis
        }
        // 同一目标和同一物理位置需连续多帧稳定才允许点击。模板窗口明显跳动时重新计数，
        // 避免把前一帧的正确底座与后一帧落在底栏附近的噪声框累计在一起。
        if (
            pendingNodeClickBlockId == action.blockId &&
            nodeClickRectsAreStable(pendingNodeClickRect, rect)
        ) {
            pendingNodeClickStableFrames++
        } else {
            pendingNodeClickBlockId = action.blockId
            pendingNodeClickRect = rect
            pendingNodeClickStableFrames = 1
        }
        if (pendingNodeClickStableFrames < NODE_CLICK_STABLE_FRAMES) {
            traceReject("node-stable-frames:$pendingNodeClickStableFrames/$NODE_CLICK_STABLE_FRAMES")
            return
        }
        if (lastNodeActionAt != Long.MIN_VALUE &&
            timestampMillis - lastNodeActionAt < NODE_CLICK_INTERVAL_MILLIS
        ) {
            traceReject("node-cooldown:${timestampMillis - lastNodeActionAt}/$NODE_CLICK_INTERVAL_MILLIS")
            return
        }
        val invalidRectReason = when {
            rect == null -> "null"
            rect.left < 0 || rect.top < 0 -> "negative"
            rect.left + rect.width > frameWidth || rect.top + rect.height > frameHeight ->
                "out-of-bounds"
            else -> null
        }
        if (invalidRectReason != null) {
            nodeLog(
                "node-target-invalid target=$label reason=$invalidRectReason rect=$rect " +
                    "frame=${frameWidth}x${frameHeight} viewport=$viewportSignature",
                warning = true,
            )

            if (
                pendingNodeScrollBlockId == action.blockId &&
                pendingNodeScrollViewportSignature == viewportSignature
            ) {
                pendingNodeScrollStableFrames++
            } else {
                pendingNodeScrollBlockId = action.blockId
                pendingNodeScrollViewportSignature = viewportSignature
                pendingNodeScrollStableFrames = 1
                pendingNodeScrollStartedAt = timestampMillis
            }

            val routeTarget = session.getLastMatchResult()?.nextNode
            if (routeTarget != null && nodeViewportScanner.targetLikelyVisible(routeTarget.column)) {
                val elapsed = if (pendingNodeScrollStartedAt == Long.MIN_VALUE) 0L
                    else timestampMillis - pendingNodeScrollStartedAt
                if (
                    pendingNodeScrollStableFrames >= NODE_TARGET_VISIBLE_MAX_WAIT_FRAMES &&
                    elapsed >= NODE_TARGET_VISIBLE_MAX_WAIT_MILLIS
                ) {
                    requestNodeMapNudge(
                        sessionId = sessionId,
                        label = label,
                        targetBlockId = action.blockId,
                        frameWidth = frameWidth,
                        frameHeight = frameHeight,
                        timestampMillis = timestampMillis,
                        viewportSignature = viewportSignature,
                        avoidRects = lastNodeSwipeAvoidRects,
                        reason = "target-geometry-visible-but-classification-missing",
                    )
                } else if (activeSessionId == sessionId) {
                    _state.value = _state.value.copy(
                        message = "${label}按地图几何应在当前画面，先保持镜头并复识别；持续失败将左右轻微移动制造新背景（" +
                            "$pendingNodeScrollStableFrames/$NODE_TARGET_VISIBLE_MAX_WAIT_FRAMES）",
                    )
                }
                return
            }

            val elapsed = if (pendingNodeScrollStartedAt == Long.MIN_VALUE) 0L
                else timestampMillis - pendingNodeScrollStartedAt
            if (
                pendingNodeScrollStableFrames < NODE_SCROLL_CURRENT_VIEW_STABLE_FRAMES ||
                elapsed < NODE_SCROLL_CURRENT_VIEW_MIN_WAIT_MILLIS
            ) {
                if (activeSessionId == sessionId) {
                    _state.value = _state.value.copy(
                        message = "暂未找到可点击的$label，优先保持当前地图并继续识别（" +
                            "$pendingNodeScrollStableFrames/$NODE_SCROLL_CURRENT_VIEW_STABLE_FRAMES）",
                    )
                }
                return
            }
            requestNodeMapScroll(
                sessionId,
                label,
                frameWidth,
                frameHeight,
                timestampMillis,
                viewportSignature,
                lastNodeSwipeAvoidRects,
            )
            return
        }
        resetNodeScrollSearchGate()
        val clickRect = rect ?: return
        if (!beginGuardedAction()) return
        traceAction("点击节点 $label")
        lastNodeActionAt = timestampMillis
        launchGuardedAction {
            val executor = actionExecutor
            // Each rejected attempt steps further down the node's own body: repeating the exact
            // pixel the game already refused can only be refused again (2026-09-20 bundle 163307:
            // three taps at 988,350, all rejected, 29 s).
            val (clickX, clickY) = nodeActionPlanner.getBaseClickPosition(
                rect = clickRect,
                retry = nodeTapAttempts,
            )
            nodeLog(
                "node-tap target=$label rect=$clickRect tap=$clickX,$clickY " +
                    "frame=${frameWidth}x${frameHeight} " +
                    "display=${CaptureStateRegistry.gestureDisplayId()} " +
                    "attempt=${nodeTapAttempts + 1}/$MAX_NODE_TAP_ATTEMPTS " +
                    "viewport=$viewportSignature",
            )
            val tap = AutomationAction.Tap(
                ScreenPoint(
                    x = clickX.toFloat(),
                    y = clickY.toFloat(),
                ),
            )
            val result = if (executor == null) {
                AutomationActionResult.Rejected(tap, "动作执行器不可用")
            } else {
                executor.execute(sessionId, tap)
            }
            when (result) {
                is AutomationActionResult.Executed -> {
                    nodeTapAttempts++
                    val targetArea = session.getLastMatchResult()
                        ?.nextNode
                        ?.takeIf { it.blockId == action.blockId }
                        ?.area
                        ?: _state.value.routeProgress?.currentArea
                    onNodeTapExecuted(action.blockId, action.blockType, label, targetArea)
                    if (activeSessionId == sessionId) {
                        val current = _state.value
                        _state.value = current.copy(
                            actionCount = current.actionCount + 1,
                            lastActionLabel = "点击节点 $label",
                            combatContext = combatContext,
                            message = "已点击$label，等待移动确认弹窗",
                        )
                        publishRouteProgress(sessionId, session, nextLabel = label, nextRect = rect)
                        publishOverlay(sessionId)
                    }
                }

                is AutomationActionResult.Rejected -> {
                    if (result.reason == GAME_NOT_FOREGROUND_REASON) {
                        publishWaitingForGameForeground(sessionId)
                    } else {
                        stopFromRunLogic("节点点击被拒绝：${result.reason}")
                    }
                }
                AutomationActionResult.StaleSession -> Unit
                AutomationActionResult.Paused -> Unit
                is AutomationActionResult.DryRun -> Unit
            }
        }
    }

    fun publishRouteProgress(
        sessionId: AutomationSessionId,
        session: LabyrinthNodeSession,
        nextLabel: String?,
        nextRect: EntryPixelRect?,
        complete: Boolean = false,
    ) {
        if (activeSessionId != sessionId) return
        val nodeState = session.getState()
        _state.value = _state.value.copy(
            routeProgress = LabyrinthRouteProgress(
                currentArea = nodeState.currentArea,
                visitedCount = nodeState.visitedNodes.size,
                routeNodeCount = routeNodeCount,
                nextNodeLabel = nextLabel,
                nextNodeRect = nextRect,
                complete = complete || nodeState.isComplete,
            ),
        )
    }

    fun resetMapRuntimeState() {
        nodeSession = null
        nodeInitStarted.set(false)
        nodeInitFailedReason = null
        finalBossLocalizationStartedAt = Long.MIN_VALUE
        entryPhaseComplete = false
        resetPendingNodeClickStability()
        resetStrongRouteTargetBinding()
        resetMapTransitionRuntimeState()
        nodeTapAttempts = 0
        resetNodeMoveConfirmationTracking()
        nodeScrollAttempts = 0
        resetNodeRecoveryNudgeTracking()
        resetNodeScrollSearchGate()
        lastNodeScrollSourceSignature = null
        nodeViewportScanner.reset()
        lastNodeActionAt = Long.MIN_VALUE
        nodeErrorStreak = 0
        routeNodeCount = 0
    }
}
