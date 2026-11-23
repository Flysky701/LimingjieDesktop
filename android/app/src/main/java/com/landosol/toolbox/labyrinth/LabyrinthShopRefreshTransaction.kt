package com.landosol.toolbox.labyrinth

import com.landosol.toolbox.labyrinth.vision.LabyrinthShopDialogState
import com.landosol.toolbox.labyrinth.vision.LabyrinthShopObservation

/** An event, not a counter: COMMITTED is emitted once, never by begin/confirmed. */
enum class LabyrinthShopRefreshOutcome { WAIT, COMMITTED, CANCELLED, UNRESOLVED }
enum class LabyrinthShopRefreshPhase { REQUESTED, CONFIRMED, UNRESOLVED }

data class LabyrinthShopRefreshPending(
    val requestedAt: Long,
    val coinsBefore: Int,
    val visualBefore: Long,
    val expectedFee: Int,
    val confirmedAt: Long? = null,
    val phase: LabyrinthShopRefreshPhase = LabyrinthShopRefreshPhase.REQUESTED,
    val lastCapturedAt: Long? = null,
    val observedCoins: Int? = null,
    val observedVisual: Long? = null,
    val stableFrames: Int = 0,
    val reason: String = "等待刷新确认弹窗；尚未扣费",
)

/**
 * Pure, single-flight refresh receipt. All times use the capture/session monotonic clock.
 *
 * The caller supplies a BEFORE balance already verified by two independent OCR reads. A
 * generic blue confirmation button is not refresh authorization: the caller must first check
 * shopRefreshDialog.kind == REFRESH, confirmed, and fee == pending.expectedFee.
 *
 * After dispatching the confirm tap, confirmed() records only intent. Payment requires the
 * exact fee delta in a trusted balance, followed by two distinct, stable new capture frames.
 * This also reconciles a real payment made without an automation confirmation receipt; it
 * does not pretend to know who touched the native screen. A moved/scrolled goods list alone
 * proves nothing, even after confirmation. No purchase or new refresh may run while pending.
 *
 * UNRESOLVED retains the receipt for Stop/reconcile. Only an actual settlement releases it;
 * repeated observations after COMMITTED/CANCELLED cannot emit the event a second time.
 */
class LabyrinthShopRefreshTransaction(private val timeoutMillis: Long = 8_000L) {
    init { require(timeoutMillis > 0) }

    var pending: LabyrinthShopRefreshPending? = null
        private set
    var lastSettled: LabyrinthShopRefreshPending? = null
        private set

    fun begin(requestedAt: Long, coinsBefore: Int, visualBefore: Long, fee: Int): Boolean {
        if (pending != null || requestedAt < 0 || fee !in setOf(300, 600) || coinsBefore < fee) return false
        pending = LabyrinthShopRefreshPending(requestedAt, coinsBefore, visualBefore, fee)
        return true
    }

    /** Call only for an executed semantic refresh confirmation, not for the refresh-button tap. */
    fun confirmed(now: Long): Boolean {
        val receipt = pending ?: return false
        if (now < receipt.requestedAt || receipt.phase == LabyrinthShopRefreshPhase.UNRESOLVED) return false
        if (receipt.confirmedAt != null) return true
        pending = receipt.copy(
            confirmedAt = now,
            phase = LabyrinthShopRefreshPhase.CONFIRMED,
            stableFrames = 0,
            reason = "刷新确认已执行，等待可信扣费回执",
        )
        return true
    }

    fun observe(shop: LabyrinthShopObservation?, now: Long, capturedAt: Long): LabyrinthShopRefreshOutcome {
        var receipt = pending ?: return LabyrinthShopRefreshOutcome.WAIT
        val timedOut = now - (receipt.confirmedAt ?: receipt.requestedAt) >= timeoutMillis
        val newFrame = capturedAt > (receipt.lastCapturedAt ?: receipt.requestedAt) && capturedAt <= now
        val afterAction = capturedAt > (receipt.confirmedAt ?: receipt.requestedAt)
        if (!newFrame || !afterAction) {
            return if (timedOut) unresolved(receipt, "刷新超时；没有可结算的新截图")
            else LabyrinthShopRefreshOutcome.WAIT
        }

        if (shop == null || shop.dialogState != LabyrinthShopDialogState.NONE ||
            !shop.coinBalanceConfirmed || shop.coinBalance == null
        ) {
            receipt = receipt.copy(lastCapturedAt = capturedAt, stableFrames = 0)
            pending = receipt
            return if (timedOut) unresolved(receipt, "刷新超时；弹窗仍在或金币未获两次OCR一致确认")
            else LabyrinthShopRefreshOutcome.WAIT
        }

        val sameEvidence = receipt.observedCoins == shop.coinBalance &&
            receipt.observedVisual == shop.visualFingerprint
        receipt = receipt.copy(
            lastCapturedAt = capturedAt,
            observedCoins = shop.coinBalance,
            observedVisual = shop.visualFingerprint,
            stableFrames = if (sameEvidence) receipt.stableFrames + 1 else 1,
        )
        pending = receipt
        val paid = shop.coinBalance == receipt.coinsBefore - receipt.expectedFee
        if (paid && receipt.stableFrames >= 2) {
            lastSettled = receipt.copy(reason = "可信余额扣除预期费用，两个新截图稳定，刷新提交一次")
            pending = null
            return LabyrinthShopRefreshOutcome.COMMITTED
        }
        val unchanged = shop.coinBalance == receipt.coinsBefore && receipt.visualBefore != 0L &&
            shop.visualFingerprint == receipt.visualBefore
        if (timedOut && unchanged && receipt.stableFrames >= 2) {
            lastSettled = receipt.copy(reason = "超时后余额和库存原始画面稳定未变，刷新取消、不计次数")
            pending = null
            return LabyrinthShopRefreshOutcome.CANCELLED
        }
        if (timedOut) return unresolved(receipt, when {
            paid -> "预期费用已扣除，但尚无两个稳定新截图；停止并核对"
            shop.coinBalance == receipt.coinsBefore -> "余额未扣费，但库存视觉变化或未稳定；不能把滚动当刷新"
            else -> "余额变化与预期刷新费用不符；停止并核对"
        })
        pending = receipt.copy(reason = if (paid) "预期费用已扣除，等待第二个稳定新截图" else "等待刷新付款或取消回执")
        return LabyrinthShopRefreshOutcome.WAIT
    }

    private fun unresolved(receipt: LabyrinthShopRefreshPending, reason: String): LabyrinthShopRefreshOutcome {
        pending = receipt.copy(phase = LabyrinthShopRefreshPhase.UNRESOLVED, reason = reason)
        return LabyrinthShopRefreshOutcome.UNRESOLVED
    }
}
