package com.landosol.toolbox.labyrinth

import com.landosol.toolbox.labyrinth.vision.LabyrinthShopObservation
import com.landosol.toolbox.labyrinth.vision.LabyrinthShopItemStatus
import com.landosol.toolbox.labyrinth.vision.LabyrinthShopItemKind
import com.landosol.toolbox.labyrinth.vision.LabyrinthShopItemVisibility
import com.landosol.toolbox.labyrinth.vision.LabyrinthShopCategoryState

/** A stable picture permits reading; only a completed visible read permits navigation. */
internal class LabyrinthShopScanController {
    enum class Phase { BROWSE, RETURN_TOP, COMPLETE }
    sealed interface Step {
        data class Evaluate(val complete: Boolean, val skipUnresolved: Boolean = false) : Step
        data class Swipe(val up: Boolean) : Step
        data class Reposition(val up: Boolean) : Step
        data class Reacquire(val reason: String) : Step
        data class Wait(val reason: String) : Step
        data class Stop(val reason: String) : Step
    }
    private data class ViewKey(val fingerprint: Long, val position: Int?)
    private data class Pending(val id: Long, val up: Boolean, val alignment: Boolean,
        val requestedAt: Long, val before: ViewKey, var completedAt: Long? = null)
    @Volatile var phase = Phase.BROWSE
        private set
    val complete: Boolean get() = phase == Phase.COMPLETE
    private var generation = 0L
    private var pending: Pending? = null
    private var requiredAfter = Long.MIN_VALUE
    private var lastCapturedAt = Long.MIN_VALUE
    private var lastFreshAt = Long.MIN_VALUE
    private var stable: ViewKey? = null
    private var stableFrames = 0
    private var stabilityStartedAt = Long.MIN_VALUE
    private var viewStartedAt = Long.MIN_VALUE
    private var lastReadView: ViewKey? = null
    private var lastReadSemantic: String? = null
    private var lastWasTop = false
    private var noMotionUp = 0
    private var noMotionDown = 0
    private var directionalSteps = 0
    private var alignmentAttempts = 0
    private var topCovered = false
    private var bottomCovered = false
    private val completedViews = linkedSetOf<ViewKey>()
    private var pendingSlots = listOf<String>()
    private var failure: String? = null

    @Synchronized fun reset(capturedAfter: Long = Long.MIN_VALUE) {
        clearCoverage()
        invalidateView(capturedAfter)
    }

    @Synchronized fun observe(shop: LabyrinthShopObservation, now: Long, capturedAt: Long, currentArea: Int? = null): Step {
        failure?.let { return Step.Stop(it) }
        val action = pending
        if (action != null && action.completedAt == null) {
            if (now - action.requestedAt >= ACTION_TIMEOUT) return fail("商店动作执行超时，保留诊断")
            return Step.Wait("等待商店动作完成")
        }
        if (capturedAt <= requiredAfter || capturedAt <= lastCapturedAt) {
            if (lastFreshAt != Long.MIN_VALUE && now - lastFreshAt >= ITEM_READ_TIMEOUT)
                return fail("商店未收到动作后的新采集帧，保留诊断")
            return Step.Wait("等待动作后的独立采集帧")
        }
        lastCapturedAt = capturedAt; lastFreshAt = now
        if (readTimedOut(now) && unreadSlots(shop).isNotEmpty())
            return fail("本页商品读取预算已耗尽，未完成覆盖，不回顶、不刷新")
        if (stabilityStartedAt == Long.MIN_VALUE) stabilityStartedAt = now
        val key = ViewKey(shop.visualFingerprint, shop.viewport?.position)
        if (stable == key) stableFrames++ else { stable = key; stableFrames = 1 }
        if (stableFrames < STABLE_FRAMES) {
            if (now - stabilityStartedAt >= ITEM_READ_TIMEOUT) return fail("商店画面持续不稳定，保留诊断")
            return Step.Wait("等待商店列表位置和画面稳定")
        }
        stabilityStartedAt = now
        if (action != null) {
            if (!action.alignment) {
                if (action.before != key) { noMotionUp = 0; noMotionDown = 0 }
                else if (key.fingerprint != 0L) {
                    if (action.up) { noMotionUp++; noMotionDown = 0 }
                    else { noMotionDown++; noMotionUp = 0 }
                }
            }
            pending = null
            lastReadView = null; lastReadSemantic = null
            viewStartedAt = now
        }
        if (viewStartedAt == Long.MIN_VALUE) viewStartedAt = now
        val missing = unreadSlots(shop)
        pendingSlots = missing
        val semantic = if (missing.isEmpty()) semanticKey(shop) else null
        // Completed evidence cannot survive unowned scrolling, stock changes or reinterpretation.
        if (action == null && lastReadView != null &&
            ((lastReadView?.position != key.position) ||
                (semantic != null && lastReadSemantic != null && semantic != lastReadSemantic))) {
            clearCoverage()
            invalidateView(capturedAt)
            return Step.Reacquire("商店出现非预期位置或库存变化，撤销旧扫描完成证据")
        }
        // Returning passes already covered views; it still must read the recovered top before ending.
        if (phase == Phase.RETURN_TOP && !atTop(shop)) return swipe(false)
        if (missing.isNotEmpty()) {
            // Area 4 has three stock rows. A verified forward search can stop between rows;
            // partially exposed cards need another normal forward swipe, not repeated tiny nudges.
            // This is recovery navigation only: it does not mark the unread view as covered.
            val partialOnly = shop.items.none { it.visibility == LabyrinthShopItemVisibility.FULL } &&
                shop.items.any { it.visibility == LabyrinthShopItemVisibility.PARTIAL }
            if (currentArea == 4 && phase == Phase.BROWSE && topCovered && completedViews.isNotEmpty() &&
                shop.viewport?.atBottom == false && !atBottom(shop) && partialOnly && key.fingerprint != 0L)
                return swipe(true)
            val needsAlignment = !shop.layoutReady || shop.items.none { it.visibility == LabyrinthShopItemVisibility.FULL } ||
                shop.items.any { it.visibility == LabyrinthShopItemVisibility.FULL && it.status == LabyrinthShopItemStatus.UNKNOWN }
            if (needsAlignment && now - viewStartedAt >= ALIGN_WAIT && alignmentAttempts < MAX_ALIGNMENT_ATTEMPTS)
                return Step.Reposition(up = if (currentArea == 4) !atBottom(shop) else shop.viewport?.atBottom != true)
            if (readTimedOut(now)) return fail("商店可见商品未完成识别：" + missing.joinToString("、"))
            return Step.Wait("等待本页可见商品识别完成：" + missing.joinToString("、"))
        }
        lastReadView = key; lastReadSemantic = semantic
        lastWasTop = atTop(shop)
        if (phase == Phase.RETURN_TOP) {
            phase = if (topCovered && bottomCovered) Phase.COMPLETE else Phase.BROWSE
            directionalSteps = 0; viewStartedAt = now
        }
        return Step.Evaluate(
            complete,
            skipUnresolved = shop.items.any {
                it.visibility == LabyrinthShopItemVisibility.FULL && it.status == LabyrinthShopItemStatus.AVAILABLE &&
                    it.categoryState == LabyrinthShopCategoryState.EXHAUSTED
            },
        )
    }

    @Synchronized fun readTimedOut(now: Long) = viewStartedAt != Long.MIN_VALUE && now - viewStartedAt >= ITEM_READ_TIMEOUT

    /** No Buy is insufficient: the exact current visible read must have finished as well. */
    @Synchronized fun advance(shop: LabyrinthShopObservation, now: Long): Step {
        failure?.let { return Step.Stop(it) }
        val key = ViewKey(shop.visualFingerprint, shop.viewport?.position)
        if (unreadSlots(shop).isNotEmpty()) {
            if (readTimedOut(now)) return fail("本页商品识别超时，未完成覆盖，不回顶、不刷新")
            return Step.Wait("本页识别尚未完成，不回顶、不刷新")
        }
        if (lastReadView != key || lastReadSemantic != semanticKey(shop))
            return Step.Wait("本页读取证据与当前画面不一致")
        return when (phase) {
            Phase.BROWSE -> {
                completedViews += key
                if (atTop(shop)) topCovered = true
                if (atBottom(shop)) {
                    bottomCovered = true
                    phase = if (atTop(shop) && topCovered) Phase.COMPLETE else Phase.RETURN_TOP
                    directionalSteps = 0; viewStartedAt = now
                    Step.Wait("底部可见商品已读取且无合规购买项，结束本轮搜索并复核")
                } else swipe(true)
            }
            Phase.RETURN_TOP -> Step.Wait("等待回顶部完成识别复核")
            Phase.COMPLETE -> Step.Evaluate(true)
        }
    }

    @Synchronized fun submitSwipe(up: Boolean, shop: LabyrinthShopObservation, now: Long, alignment: Boolean = false): Long {
        val id = ++generation
        pending = Pending(id, up, alignment, now, ViewKey(shop.visualFingerprint, shop.viewport?.position))
        return id
    }
    @Synchronized fun swipeExecuted(id: Long, completedAt: Long): Boolean {
        val action = pending?.takeIf { it.id == id && it.completedAt == null } ?: return false
        action.completedAt = completedAt
        if (action.alignment) alignmentAttempts++ else directionalSteps++
        requiredAfter = completedAt + SETTLE_MILLIS
        clearStability()
        return true
    }
    @Synchronized fun swipeNotExecuted(id: Long, reason: String? = null) {
        if (pending?.id != id) return
        pending = null
        if (reason != null) failure = "商店手势被拒绝：" + reason
        clearStability()
    }

    /** A successful purchase removes stock; it never forces a return swipe. */
    @Synchronized fun purchased(completedAt: Long) {
        if (lastWasTop) topCovered = false
        bottomCovered = false
        completedViews.remove(lastReadView)
        invalidateView(completedAt + SETTLE_MILLIS)
    }

    @Synchronized fun diagnostics(): Map<String, String> = mapOf(
        "phase" to phase.name, "generation" to generation.toString(), "capturedAt" to lastCapturedAt.toString(),
        "stableFrames" to stableFrames.toString(), "position" to (stable?.position?.toString() ?: "unknown"),
        "topRead" to topCovered.toString(), "bottomRead" to bottomCovered.toString(),
        "coveredViews" to completedViews.size.toString(), "unread" to pendingSlots.joinToString("、"),
        "alignmentAttempts" to alignmentAttempts.toString(), "pendingAction" to (pending?.id?.toString() ?: "none"))

    @Synchronized fun readingReason(shop: LabyrinthShopObservation): String? =
        unreadSlots(shop).takeIf { it.isNotEmpty() }?.joinToString("、")

    private fun unreadSlots(shop: LabyrinthShopObservation): List<String> {
        if (!shop.layoutReady || shop.visualFingerprint == 0L) return listOf("可见卡片布局未对齐")
        val full = shop.items.filter { it.visibility == LabyrinthShopItemVisibility.FULL }
        if (full.isEmpty()) return listOf("没有已对齐的完整可见商品")
        return full.mapNotNull { item -> when {
            item.status == LabyrinthShopItemStatus.UNKNOWN -> item.slotId + "按钮待确认"
            item.status == LabyrinthShopItemStatus.DISABLED -> null
            item.categoryState == LabyrinthShopCategoryState.READING -> item.slotId + "类别待确认"
            item.categoryState == LabyrinthShopCategoryState.EXHAUSTED -> null
            item.kind == LabyrinthShopItemKind.UNKNOWN -> item.slotId + "类别待确认"
            item.kind == LabyrinthShopItemKind.ROLE_IMPRINT && item.imprintLabelPending -> item.slotId + "印记形式或职能名待确认"
            else -> null
        } }
    }
    private fun semanticKey(shop: LabyrinthShopObservation) = shop.items.filter { it.visibility == LabyrinthShopItemVisibility.FULL }
        .joinToString("|") { if (it.status == LabyrinthShopItemStatus.DISABLED) it.slotId + ":DISABLED"
            else it.slotId + ":" + it.status + ":" + it.categoryState + ":" + it.kind + ":" +
                (it.relicMatch?.relicId ?: "") + ":" +
                (it.roleImprintLabel ?: "") + ":" + it.choiceRoleImprint + ":" + (it.titleText ?: "") }
    private fun invalidateView(capturedAfter: Long) {
        generation++; phase = Phase.BROWSE; pending = null; requiredAfter = capturedAfter
        lastCapturedAt = Long.MIN_VALUE; lastFreshAt = Long.MIN_VALUE
        lastReadView = null; lastReadSemantic = null; lastWasTop = false
        noMotionUp = 0; noMotionDown = 0; directionalSteps = 0; alignmentAttempts = 0; failure = null
        clearStability()
    }
    private fun clearCoverage() { topCovered = false; bottomCovered = false; completedViews.clear() }
    private fun atTop(shop: LabyrinthShopObservation) = shop.viewport?.atTop == true || noMotionDown >= 2
    private fun atBottom(shop: LabyrinthShopObservation) = shop.viewport?.atBottom == true || noMotionUp >= 2
    private fun swipe(up: Boolean): Step = if (directionalSteps >= 6) fail("商店搜索达安全上限但未证实边界，保留诊断") else Step.Swipe(up)
    private fun fail(reason: String): Step.Stop { failure = reason; return Step.Stop(reason) }
    private fun clearStability() { stable = null; stableFrames = 0; stabilityStartedAt = Long.MIN_VALUE; viewStartedAt = Long.MIN_VALUE }
    companion object {
        const val SETTLE_MILLIS = 700L
        const val ITEM_READ_TIMEOUT = 20_000L
        private const val STABLE_FRAMES = 2
        private const val ALIGN_WAIT = 1_500L
        private const val MAX_ALIGNMENT_ATTEMPTS = 2
        private const val ACTION_TIMEOUT = 8_000L
    }
}
