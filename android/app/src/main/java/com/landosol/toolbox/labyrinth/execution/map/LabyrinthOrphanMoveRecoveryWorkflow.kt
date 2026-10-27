package com.landosol.toolbox.labyrinth.execution.map

import com.landosol.toolbox.automation.AutomationAction
import com.landosol.toolbox.automation.AutomationActionResult
import com.landosol.toolbox.automation.AutomationSessionId
import com.landosol.toolbox.automation.ScreenPoint
import com.landosol.toolbox.automation.SessionBoundActionExecutor
import com.landosol.toolbox.automation.accessibility.GAME_NOT_FOREGROUND_REASON
import com.landosol.toolbox.labyrinth.*
import com.landosol.toolbox.labyrinth.vision.EntryPixelRect
import com.landosol.toolbox.labyrinth.vision.LabyrinthEntryFrameResult
import com.landosol.toolbox.protocol.labyrinth.LabyrinthMapNode

/** Read-only route context and explicit handoffs; recovery counters never leave this workflow. */
internal interface LabyrinthOrphanMoveRecoveryPort {
    val actionExecutor: SessionBoundActionExecutor?
    val entryPhaseComplete: Boolean
    val hasNodeSession: Boolean
    val hasPendingNodeTransition: Boolean
    fun uniqueReachableRouteTarget(): LabyrinthMapNode?
    fun recoverNodeTransition(
        sessionId: AutomationSessionId,
        target: LabyrinthMapNode,
        timestampMillis: Long,
        confidence: Double,
    )
    fun beginGuardedAction(): Boolean
    fun launchGuardedAction(block: suspend () -> Unit)
    fun onDismissExecuted(sessionId: AutomationSessionId)
    fun publishNodeMoveConfirmationMessage(sessionId: AutomationSessionId, message: String)
    fun publishWaitingForGameForeground(sessionId: AutomationSessionId)
    fun finishFromPlanner(sessionId: AutomationSessionId, reason: String)
    suspend fun stopFromRunLogic(reason: String): Boolean
    fun nodeLog(message: String, warning: Boolean = false)
    fun traceAction(label: String)
    fun traceWorkflow(owner: String, phase: String)
}

/**
 * Owns unrequested movement-modal recovery for one recognition session: observation, dismissal
 * admission/receipt, budget restoration and the exhausted-budget grace period. A recoverable
 * route target is handed back to map transitions; visible shop chrome leaves the frame to shop.
 *
 * The instance survives start/stop like the original fields. Entry recovery resets dismissal
 * tracking; only a dialog-free observation clears the exhaustion timestamp. Keep these two
 * boundaries distinct rather than turning the extraction into a lifecycle behavior change.
 */
internal class LabyrinthOrphanMoveRecoveryWorkflow(private val port: LabyrinthOrphanMoveRecoveryPort) {
    @Volatile
    private var orphanMoveConfirmationStableFrames = 0

    @Volatile
    private var orphanMoveConfirmationDismissAttempts = 0

    /** Consecutive frames with no movement dialog; proof a cancel worked, which refills the budget. */
    private var orphanMoveConfirmationClearFrames = 0

    @Volatile
    private var lastOrphanMoveConfirmationDismissAt = Long.MIN_VALUE

    /** First frame after the stray-dialog cancel budget ran out. */
    @Volatile
    private var orphanMoveConfirmationExhaustedSince = Long.MIN_VALUE

    fun handleOrphanMoveConfirmation(sessionId: AutomationSessionId, result: LabyrinthEntryFrameResult, timestampMillis: Long, dryRun: Boolean): Boolean {
        val pageState = result.observation.state
        if (!dryRun && result.nodeMoveConfirmation != null && !port.hasPendingNodeTransition) {
            // Read persistent chrome before considering the generic modal recovery. A shop exit
            // confirmation can briefly classify as UNKNOWN but still has the shop behind it; it
            // must never manufacture a pending route transition to the next Boss/node.
            val shopBackground = labyrinthShopBackgroundVisible(result)
            val recoverableTarget = port.uniqueReachableRouteTarget()
            val canRecover = recoverableTarget != null &&
                labyrinthCanRecoverOrphanNodeMoveConfirmation(
                    pageState = pageState,
                    confirmationConfidence = result.nodeMoveConfirmation.confidence,
                    hasUniqueReachableRouteTarget = true,
                    shopBackgroundVisible = shopBackground,
                )
            if (canRecover && recoverableTarget != null) {
                port.recoverNodeTransition(
                    sessionId = sessionId,
                    target = recoverableTarget,
                    timestampMillis = timestampMillis,
                    confidence = result.nodeMoveConfirmation.confidence,
                )
            } else if (port.hasNodeSession && port.entryPhaseComplete) {
                // A modal nobody asked for and nobody can adopt (a stray tap while the route has
                // several reachable successors). Every map gesture would land on the dialog, so
                // the node search below must not run; press the dialog's cancel button instead.
                // The shop exit dialog shares this chrome; while shop controls are visible the
                // dialog is the shop handler's and must be left alone.
                val shopBackground = labyrinthShopBackgroundVisible(result)
                orphanMoveConfirmationClearFrames = 0
                if (shopBackground) {
                    orphanMoveConfirmationStableFrames = 0
                } else {
                    orphanMoveConfirmationStableFrames++
                }
                if (!shopBackground && labyrinthShouldDismissOrphanNodeMoveConfirmation(
                        pageState = pageState,
                        hasPendingNodeTransition = false,
                        canRecover = false,
                        stableFrames = orphanMoveConfirmationStableFrames,
                        dismissAttempts = orphanMoveConfirmationDismissAttempts,
                        lastDismissAt = lastOrphanMoveConfirmationDismissAt,
                        now = timestampMillis,
                        shopBackgroundVisible = false,
                    )
                ) {
                    dismissOrphanNodeMoveConfirmation(
                        sessionId = sessionId,
                        cancelRect = result.nodeMoveConfirmation.cancelButtonRect,
                        timestampMillis = timestampMillis,
                    )
                } else if (shopBackground) {
                    // Fall through: the shop / shop-exit handlers own this frame.
                } else if (orphanMoveConfirmationDismissAttempts >= MAX_ORPHAN_MOVE_CONFIRMATION_DISMISS_ATTEMPTS) {
                    // The cancel button was pressed its full budget and the dialog is still up.
                    // Asking for a human and then waiting for one indefinitely is the one thing
                    // an unattended run must not do: every map gesture lands on this dialog, so
                    // nothing else can make progress either.
                    if (orphanMoveConfirmationExhaustedSince == Long.MIN_VALUE) {
                        orphanMoveConfirmationExhaustedSince = timestampMillis
                    }
                    val heldFor = timestampMillis - orphanMoveConfirmationExhaustedSince
                    if (heldFor >= ORPHAN_MOVE_CONFIRMATION_MANUAL_GRACE_MILLIS) {
                        port.finishFromPlanner(
                            sessionId,
                            "移动确认弹窗取消${orphanMoveConfirmationDismissAttempts}次无效，" +
                                "等待手动关闭${heldFor / 1000}秒后仍未消失；已停止并保留诊断状态",
                        )
                        return true.also { port.traceWorkflow("map", "orphan-confirmation") }
                    }
                    port.publishNodeMoveConfirmationMessage(sessionId, "移动确认弹窗多次取消无效，请手动关闭后继续")
                    return true.also { port.traceWorkflow("map", "orphan-confirmation") }
                } else {
                    port.publishNodeMoveConfirmationMessage(sessionId, "检测到非自动触发的移动确认弹窗，准备点击取消")
                    return true.also { port.traceWorkflow("map", "orphan-confirmation") }
                }
                if (!shopBackground) return true.also { port.traceWorkflow("map", "orphan-confirmation") }
            }
        } else if (result.nodeMoveConfirmation == null) {
            orphanMoveConfirmationStableFrames = 0
            orphanMoveConfirmationExhaustedSince = Long.MIN_VALUE
            // The screen cleared, so the previous cancel worked. Give the budget back: it is there
            // to stop tapping at a dialog that will not close, not to cap how many separate stray
            // dialogs one run may recover from.
            orphanMoveConfirmationClearFrames++
            if (labyrinthOrphanMoveConfirmationBudgetRestored(orphanMoveConfirmationClearFrames)) {
                resetOrphanMoveConfirmationTracking()
            }
        }
        return false
    }

    fun dismissOrphanNodeMoveConfirmation(
        sessionId: AutomationSessionId,
        cancelRect: EntryPixelRect,
        timestampMillis: Long,
    ) {
        if (!port.beginGuardedAction()) return
        val attempt = orphanMoveConfirmationDismissAttempts + 1
        lastOrphanMoveConfirmationDismissAt = timestampMillis
        port.traceAction("取消误触的移动确认")
        port.nodeLog(
            "node-move-confirm-dismiss rect=$cancelRect attempt=$attempt/$MAX_ORPHAN_MOVE_CONFIRMATION_DISMISS_ATTEMPTS",
            warning = true,
        )
        port.launchGuardedAction {
            val tap = AutomationAction.Tap(
                ScreenPoint(
                    x = cancelRect.left + cancelRect.width / 2f,
                    y = cancelRect.top + cancelRect.height / 2f,
                ),
            )
            val executor = port.actionExecutor
            val result = if (executor == null) {
                AutomationActionResult.Rejected(tap, "动作执行器不可用")
            } else {
                executor.execute(sessionId, tap)
            }
            when (result) {
                is AutomationActionResult.Executed -> {
                    orphanMoveConfirmationDismissAttempts = attempt
                    orphanMoveConfirmationStableFrames = 0
                    port.onDismissExecuted(sessionId)
                }
                is AutomationActionResult.Rejected -> {
                    if (result.reason == GAME_NOT_FOREGROUND_REASON) {
                        port.publishWaitingForGameForeground(sessionId)
                    } else {
                        port.stopFromRunLogic("取消移动确认被拒绝：${result.reason}")
                    }
                }
                AutomationActionResult.StaleSession -> Unit
                AutomationActionResult.Paused -> Unit
                is AutomationActionResult.DryRun -> Unit
            }
        }
    }

    /** Restore dismissal tracking at the existing entry-recovery / clear-frame boundaries. */
    fun resetOrphanMoveConfirmationTracking() {
        orphanMoveConfirmationStableFrames = 0
        orphanMoveConfirmationDismissAttempts = 0
        orphanMoveConfirmationClearFrames = 0
        lastOrphanMoveConfirmationDismissAt = Long.MIN_VALUE
    }
}
