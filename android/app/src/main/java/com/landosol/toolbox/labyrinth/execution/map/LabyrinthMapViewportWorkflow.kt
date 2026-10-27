package com.landosol.toolbox.labyrinth.execution.map

import com.landosol.toolbox.labyrinth.*
import com.landosol.toolbox.automation.AutomationActionResult
import com.landosol.toolbox.automation.AutomationSessionId
import com.landosol.toolbox.automation.SessionBoundActionExecutor
import com.landosol.toolbox.automation.accessibility.GAME_NOT_FOREGROUND_REASON
import com.landosol.toolbox.labyrinth.vision.EntryPixelRect
import com.landosol.toolbox.labyrinth.node.LabyrinthNodeSession
import com.landosol.toolbox.labyrinth.node.LabyrinthMapScanDirection
import com.landosol.toolbox.labyrinth.node.LabyrinthNodeViewportScanner
import com.landosol.toolbox.labyrinth.node.LabyrinthNodeTypes
import com.landosol.toolbox.labyrinth.node.NodeClassification
import kotlinx.coroutines.flow.MutableStateFlow
import com.landosol.toolbox.labyrinth.LabyrinthExecutionLimits.MAX_NODE_SCROLL_ATTEMPTS
import com.landosol.toolbox.labyrinth.LabyrinthExecutionLimits.MAX_NODE_SEARCH_REARMS
import com.landosol.toolbox.labyrinth.LabyrinthExecutionLimits.NODE_CLICK_INTERVAL_MILLIS
import com.landosol.toolbox.labyrinth.LabyrinthExecutionLimits.NODE_RECOVERY_NUDGE_INTERVAL_MILLIS

internal interface LabyrinthMapViewportPort {
    val _state: MutableStateFlow<LabyrinthEntryRecognitionSessionState>
    val actionExecutor: SessionBoundActionExecutor?
    val activeSessionId: AutomationSessionId?
    var lastNodeActionAt: Long
    var lastNodeScrollSourceSignature: String?
    val nodeConflictRecovery: com.landosol.toolbox.labyrinth.node.LabyrinthNodeConflictRecovery
    var nodeRecoveryNudgeBlockId: Long
    var nodeRecoveryNudgeCount: Int
    var nodeScrollAttempts: Int
    var nodeSearchRearmCount: Int
    val nodeSession: LabyrinthNodeSession?
    val nodeViewportScanner: LabyrinthNodeViewportScanner
    fun resetPendingNodeClickStability()
    fun resetStrongRouteTargetBinding()
    fun resetNodeScrollSearchGate()
    fun resetNodeErrorTracking()
    fun beginGuardedAction(): Boolean
    fun finishFromPlanner(sessionId: AutomationSessionId, reason: String): Unit
    fun launchGuardedAction(block: suspend () -> Unit): Unit
    fun nodeLog(message: String, warning: Boolean = false): Unit
    fun publishOverlay(sessionId: AutomationSessionId): Unit
    fun publishWaitingForGameForeground(sessionId: AutomationSessionId): Unit
    suspend fun stopFromRunLogic(reason: String): Boolean
}

/** 管理地图视口、滚动扫描、微调及目标位置稳定性。 */
internal class LabyrinthMapViewportWorkflow(private val port: LabyrinthMapViewportPort) : LabyrinthMapViewportPort by port {
    fun resetNodeRecoveryNudgeTracking() {
        nodeRecoveryNudgeBlockId = Long.MIN_VALUE
        nodeRecoveryNudgeCount = 0
        nodeConflictRecovery.reset()
    }

    fun nodeClickRectsAreStable(
        previous: EntryPixelRect?,
        current: EntryPixelRect?,
    ): Boolean {
        return labyrinthNodeClickRectsAreStable(previous, current)
    }

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
    ) {
        if (nodeRecoveryNudgeBlockId != targetBlockId) {
            nodeRecoveryNudgeBlockId = targetBlockId
            nodeRecoveryNudgeCount = 0
            nodeSearchRearmCount = 0
            nodeConflictRecovery.reset()
        }
        if (labyrinthNodeRecoveryNudgeExhausted(nodeRecoveryNudgeCount)) {
            // Oscillating in place has provably stopped helping. Give the segmented scan a fresh
            // budget so the camera actually travels; bound the alternation so a genuinely
            // unclassifiable node still reaches a diagnosable stop instead of looping forever.
            nodeRecoveryNudgeCount = 0
            nodeSearchRearmCount++
            nodeLog(
                "node-nudge-exhausted target=$label rearm=$nodeSearchRearmCount/$MAX_NODE_SEARCH_REARMS " +
                    "reason=$reason viewport=$viewportSignature",
                warning = true,
            )
            if (nodeSearchRearmCount > MAX_NODE_SEARCH_REARMS) {
                finishFromPlanner(
                    sessionId,
                    "${label}在完整扫描与局部微调各${MAX_NODE_SEARCH_REARMS}轮后仍无法识别；已停止并保留诊断状态",
                )
                return
            }
            nodeScrollAttempts = 0
            nodeViewportScanner.reset()
            if (activeSessionId == sessionId) {
                _state.value = _state.value.copy(
                    message = "${label}局部微调无效，重新开始整段地图扫描（第${nodeSearchRearmCount}轮）",
                )
            }
            return
        }
        if (lastNodeActionAt != Long.MIN_VALUE &&
            timestampMillis - lastNodeActionAt < NODE_RECOVERY_NUDGE_INTERVAL_MILLIS
        ) return
        val direction = labyrinthNodeRecoveryNudgeDirection(nodeRecoveryNudgeCount)
        val swipe = labyrinthMapNudge(frameWidth, frameHeight, direction, avoidRects) ?: return
        if (!beginGuardedAction()) return
        val attempt = nodeRecoveryNudgeCount + 1
        lastNodeActionAt = timestampMillis
        resetNodeScrollSearchGate()
        resetPendingNodeClickStability()
        resetStrongRouteTargetBinding()
        nodeConflictRecovery.reset()
        nodeLog(
            "node-nudge-dispatch target=$label attempt=$attempt direction=${direction.name} " +
                "reason=$reason viewport=$viewportSignature swipe=" +
                "${swipe.start.x.toInt()},${swipe.start.y.toInt()}->${swipe.end.x.toInt()},${swipe.end.y.toInt()}",
            warning = true,
        )
        launchGuardedAction {
            val executor = actionExecutor
            val result = if (executor == null) {
                AutomationActionResult.Rejected(swipe, "动作执行器不可用")
            } else {
                executor.execute(sessionId, swipe)
            }
            when (result) {
                is AutomationActionResult.Executed -> {
                    nodeRecoveryNudgeCount = attempt
                    resetNodeErrorTracking()
                    if (activeSessionId == sessionId) {
                        val directionLabel = when (direction) {
                            LabyrinthMapScanDirection.FORWARD -> "向右轻移"
                            LabyrinthMapScanDirection.BACKWARD -> "向左轻移"
                        }
                        val current = _state.value
                        _state.value = current.copy(
                            actionCount = current.actionCount + 1,
                            lastActionLabel = "微调地图重新识别 $label",
                            message = "已${directionLabel}地图制造新背景证据，重新识别$label（第${attempt}次微调）",
                        )
                        publishOverlay(sessionId)
                    }
                }
                is AutomationActionResult.Rejected -> {
                    lastNodeActionAt = Long.MIN_VALUE
                    if (result.reason == GAME_NOT_FOREGROUND_REASON) {
                        publishWaitingForGameForeground(sessionId)
                    } else if (activeSessionId == sessionId) {
                        _state.value = _state.value.copy(message = "地图微调未执行：${result.reason}；保持节点页并等待重试")
                        publishOverlay(sessionId)
                    }
                }
                AutomationActionResult.StaleSession -> Unit
                AutomationActionResult.Paused -> Unit
                is AutomationActionResult.DryRun -> Unit
            }
        }
    }

    fun requestNodeMapScroll(
        sessionId: AutomationSessionId,
        label: String,
        frameWidth: Int,
        frameHeight: Int,
        timestampMillis: Long,
        viewportSignature: String,
        avoidRects: List<EntryPixelRect>,
    ) {
        nodeLog(
            "node-scroll-request target=$label attempt=${nodeScrollAttempts + 1}/$MAX_NODE_SCROLL_ATTEMPTS " +
                "frame=${frameWidth}x${frameHeight} viewport=$viewportSignature " +
                nodeViewportScanner.diagnostics(port.nodeSession?.getLastMatchResult()?.nextNode?.blockId),
            warning = true,
        )
        val routeTarget = port.nodeSession?.getLastMatchResult()?.nextNode
        if (routeTarget == null) {
            logNodeScrollTerminalDiagnostics(
                label = label,
                reason = "MISSING_ROUTE_TARGET",
                frameWidth = frameWidth,
                frameHeight = frameHeight,
                viewportSignature = viewportSignature,
            )
            finishFromPlanner(sessionId, "路线缺少待定位节点，无法继续扫描：$label")
            return
        }
        if (nodeScrollAttempts >= MAX_NODE_SCROLL_ATTEMPTS) {
            logNodeScrollTerminalDiagnostics(
                label = label,
                reason = "MAX_ATTEMPTS_SWITCH_TO_LOCAL_RECOVERY",
                frameWidth = frameWidth,
                frameHeight = frameHeight,
                viewportSignature = viewportSignature,
            )
            requestNodeMapNudge(
                sessionId = sessionId,
                label = label,
                targetBlockId = routeTarget.blockId,
                frameWidth = frameWidth,
                frameHeight = frameHeight,
                timestampMillis = timestampMillis,
                viewportSignature = viewportSignature,
                avoidRects = avoidRects,
                reason = "segmented-scan-budget-exhausted",
            )
            return
        }
        val hasVisualViewport = viewportSignature.isNotBlank()
        if (hasVisualViewport && nodeViewportScanner.awaitingSwipeOutcome()) {
            if (activeSessionId == sessionId) {
                _state.value = _state.value.copy(message = "已滚动地图，等待视口稳定并判断是否到达边界；暂不连续滑动")
            }
            return
        }
        val scanPlan = if (hasVisualViewport) {
            nodeViewportScanner.plan(
                targetBlockId = routeTarget.blockId,
                targetLogicalColumn = routeTarget.column,
            )
        } else {
            labyrinthBlindMapScanPlan(
                currentNodeId = port.nodeSession?.getState()?.currentNodeId ?: 0L,
                targetLogicalColumn = routeTarget.column,
                completedAttempts = nodeScrollAttempts,
                reverseAfterAttempts = MAX_NODE_SCROLL_ATTEMPTS / 2,
            )
        }
        if (scanPlan == null) {
            logNodeScrollTerminalDiagnostics(
                label = label,
                reason = "${nodeViewportScanner.exhaustionReason()}:switch-to-local-recovery",
                frameWidth = frameWidth,
                frameHeight = frameHeight,
                viewportSignature = viewportSignature,
            )
            requestNodeMapNudge(
                sessionId = sessionId,
                label = label,
                targetBlockId = routeTarget.blockId,
                frameWidth = frameWidth,
                frameHeight = frameHeight,
                timestampMillis = timestampMillis,
                viewportSignature = viewportSignature,
                avoidRects = avoidRects,
                reason = nodeViewportScanner.exhaustionReason(),
            )
            return
        }
        if (lastNodeActionAt != Long.MIN_VALUE &&
            timestampMillis - lastNodeActionAt < NODE_CLICK_INTERVAL_MILLIS
        ) {
            return
        }
        val swipe = labyrinthMapSwipe(frameWidth, frameHeight, scanPlan.direction, avoidRects) ?: run {
            finishFromPlanner(sessionId, "地图尺寸无效，无法滚动到下一节点：$label")
            return
        }
        if (!beginGuardedAction()) return
        nodeScrollAttempts++
        resetNodeScrollSearchGate()
        resetStrongRouteTargetBinding()
        lastNodeScrollSourceSignature = viewportSignature
        lastNodeActionAt = timestampMillis
        nodeLog(
            "node-scroll-dispatch target=$label direction=${scanPlan.direction.name} " +
                "reason=${scanPlan.reason} viewport=$viewportSignature " +
                "swipe=${swipe.start.x.toInt()},${swipe.start.y.toInt()}->" +
                "${swipe.end.x.toInt()},${swipe.end.y.toInt()} avoidRects=${avoidRects.size}",
        )
        launchGuardedAction {
            val executor = actionExecutor
            val result = if (executor == null) {
                AutomationActionResult.Rejected(swipe, "动作执行器不可用")
            } else {
                executor.execute(sessionId, swipe)
            }
            when (result) {
                is AutomationActionResult.Executed -> {
                    if (hasVisualViewport) {
                        nodeViewportScanner.recordSwipe(scanPlan.direction, nodeViewportScanner.currentSignature())
                    }
                    if (activeSessionId == sessionId) {
                        val current = _state.value
                        val directionLabel = when (scanPlan.direction) {
                            LabyrinthMapScanDirection.FORWARD -> "向右"
                            LabyrinthMapScanDirection.BACKWARD -> "向左"
                        }
                        _state.value = current.copy(
                            actionCount = current.actionCount + 1,
                            lastActionLabel = "滚动地图寻找 $label",
                            message = "已${directionLabel}搜索地图，等待画面稳定后重新识别下一节点（" +
                                "$nodeScrollAttempts/$MAX_NODE_SCROLL_ATTEMPTS）",
                        )
                        publishOverlay(sessionId)
                    }
                }

                is AutomationActionResult.Rejected -> {
                    if (result.reason == GAME_NOT_FOREGROUND_REASON) {
                        nodeScrollAttempts = (nodeScrollAttempts - 1).coerceAtLeast(0)
                        publishWaitingForGameForeground(sessionId)
                    } else {
                        stopFromRunLogic("地图滚动被拒绝：${result.reason}")
                    }
                }
                AutomationActionResult.StaleSession -> Unit
                AutomationActionResult.Paused -> Unit
                is AutomationActionResult.DryRun -> Unit
            }
        }
    }

    fun logNodeScrollTerminalDiagnostics(
        label: String,
        reason: String,
        frameWidth: Int,
        frameHeight: Int,
        viewportSignature: String,
    ) {
        val session = port.nodeSession
        val rawCandidates = session?.getLastClassifications().orEmpty().joinToString(";") { candidate ->
            "${candidate.column}/${candidate.row}:${LabyrinthNodeTypes.labelOf(candidate.blockType)}" +
                "@${"%.2f".format(candidate.confidence)}:" +
                "click=${candidate.isClickable}:" +
                "cyan=${"%.2f".format(candidate.cyanGlowScore)}:" +
                "cyanRows=${"%.2f".format(candidate.cyanGlowRowCoverage)}:" +
                "purple=${"%.2f".format(candidate.purpleGlowScore)}:" +
                "purpleRows=${"%.2f".format(candidate.purpleGlowRowCoverage)}:" +
                "purpleSide=${"%.2f".format(candidate.purpleGlowSideScore)}:" +
                "purpleLower=${"%.2f".format(candidate.purpleGlowLowerScore)}:" +
                "color=${"%.2f".format(candidate.colorRoiScore)}:" +
                "rect=${candidate.screenRect}:template=${candidate.templateId}"
        }.ifBlank { "none" }
        val match = session?.getLastMatchResult()
        val mappings = match?.visibleNodes.orEmpty().joinToString(";") { mapping ->
            "${mapping.blockId}:${LabyrinthNodeTypes.labelOf(mapping.blockType)}:" +
                "rect=${mapping.screenRect}"
        }.ifBlank { "none" }
        val conflicts = match?.conflicts.orEmpty().joinToString(";") { conflict ->
            "${conflict.reason}:${conflict.blockId}:" +
                "expected=${LabyrinthNodeTypes.labelOf(conflict.expectedBlockType)}:" +
                "detected=${LabyrinthNodeTypes.labelOf(conflict.detectedBlockType)}:" +
                "rect=${conflict.screenRect}"
        }.ifBlank { "none" }
        val topology = match?.topologyMappings.orEmpty().joinToString(";") { mapping ->
            "${mapping.blockId}:${LabyrinthNodeTypes.labelOf(mapping.expectedBlockType)}:" +
                "visual=${mapping.visualColumn}/${mapping.visualRow}:" +
                "detected=${LabyrinthNodeTypes.labelOf(mapping.detectedBlockType)}:" +
                "topo=${"%.2f".format(mapping.topologyConfidence)}:" +
                "rect=${mapping.screenRect}"
        }.ifBlank { "none" }
        val topologyColumns = match?.topologyObservedColumns.orEmpty().joinToString(";") { column ->
            "v${column.visualColumn}@${column.centerX}[${column.nodeCount}]->" +
                "c${column.mappedLogicalColumn ?: "?"}"
        }.ifBlank { "none" }
        val routeTarget = match?.nextNode?.let { node ->
            "${node.blockId}:${LabyrinthNodeTypes.labelOf(node.blockType)}"
        } ?: label
        nodeLog(
            "node-scroll-terminal reason=$reason target=$routeTarget " +
                "frame=${frameWidth}x${frameHeight} viewport=$viewportSignature " +
                "columnOffset=${match?.logicalColumnOffset ?: "unknown"} " +
                "topologyScore=${match?.topologyAlignmentScore ?: 0.0} " +
                "scan=${nodeViewportScanner.diagnostics(match?.nextNode?.blockId)} " +
                "topologyColumns=[$topologyColumns] topology=[$topology] " +
                "raw=[$rawCandidates] mapped=[$mappings] conflicts=[$conflicts]",
            warning = true,
        )
    }

    fun describeNodeClassifications(classifications: List<NodeClassification>): String =
        classifications.joinToString(separator = ";") { classification ->
            val rect = classification.screenRect
            "${classification.column}/${classification.row}:" +
                "${LabyrinthNodeTypes.labelOf(classification.blockType)}" +
                "@${"%.2f".format(classification.confidence)}" +
                ":click=${classification.isClickable}" +
                ":glow=cyan${"%.2f".format(classification.cyanGlowScore)}," +
                "rows${"%.2f".format(classification.cyanGlowRowCoverage)}," +
                "purple${"%.2f".format(classification.purpleGlowScore)}," +
                "purpleRows${"%.2f".format(classification.purpleGlowRowCoverage)}," +
                "purpleSide${"%.2f".format(classification.purpleGlowSideScore)}," +
                "purpleLower${"%.2f".format(classification.purpleGlowLowerScore)}" +
                ":color=${"%.2f".format(classification.colorRoiScore)}" +
                ":rect=${rect?.left},${rect?.top},${rect?.width},${rect?.height}" +
                ":template=${classification.templateId}"
        }.ifBlank { "none" }
}
