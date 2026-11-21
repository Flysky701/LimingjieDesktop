package com.landosol.toolbox.labyrinth.kernel.shop

import com.landosol.toolbox.labyrinth.LabyrinthFallbackTap
import com.landosol.toolbox.labyrinth.LabyrinthObservedRelic
import com.landosol.toolbox.labyrinth.LabyrinthPageUiOwner
import com.landosol.toolbox.labyrinth.LabyrinthPostEntryActionKind
import com.landosol.toolbox.labyrinth.LabyrinthPostEntryActionKind.*
import com.landosol.toolbox.labyrinth.LabyrinthPostEntryTapPlan
import com.landosol.toolbox.labyrinth.LabyrinthRelicChoicePhase
import com.landosol.toolbox.labyrinth.LabyrinthRelicIdentityGate
import com.landosol.toolbox.labyrinth.LabyrinthRelicMark
import com.landosol.toolbox.labyrinth.LabyrinthShopDecision
import com.landosol.toolbox.labyrinth.LabyrinthShopPolicy
import com.landosol.toolbox.labyrinth.kernel.*
import com.landosol.toolbox.labyrinth.kernel.KernelEventKinds as K
import com.landosol.toolbox.labyrinth.labyrinthKeepsPlannedShopPurchaseAcrossPage
import com.landosol.toolbox.labyrinth.labyrinthPageUiOwner
import com.landosol.toolbox.labyrinth.labyrinthShopRefreshCanCommit
import com.landosol.toolbox.labyrinth.labyrinthShopRefreshConfirmationOwnsFrame
import com.landosol.toolbox.labyrinth.node.LabyrinthNodeTypes
import com.landosol.toolbox.labyrinth.vision.EntryAnchorId
import com.landosol.toolbox.labyrinth.vision.EntryPixelRect
import com.landosol.toolbox.labyrinth.vision.LabyrinthEntryFrameResult
import com.landosol.toolbox.labyrinth.vision.LabyrinthEntryPageState
import com.landosol.toolbox.labyrinth.vision.LabyrinthEntryPageState.*
import com.landosol.toolbox.labyrinth.vision.LabyrinthRelicMatch
import com.landosol.toolbox.labyrinth.vision.LabyrinthShopDialogState

internal data class ShopActionKey(
    val sessionId: String,
    val pageState: LabyrinthEntryPageState,
    val actionKind: LabyrinthPostEntryActionKind,
    val targetId: String?,
    val rect: EntryPixelRect,
) {
    internal fun traceTarget(): String = "${actionKind.name}:${targetId ?: pageState.name}"
}

internal data class ShopPlanOutcome(
    val tapPlan: LabyrinthPostEntryTapPlan?,
    val decision: KernelDecision,
    val waitReason: String?,
    val actionKey: ShopActionKey? = null,
    val permitToken: KernelActionPermit.Token? = null,
    val decisionEvent: KernelEvent? = null,
    /** False lets the caller continue routing this frame after the shop reports its decision. */
    val handled: Boolean = true,
    /** Matching confirmation evidence delivered once when the purchase completes visually. */
    val completedPurchaseRelic: LabyrinthRelicMatch? = null,
)

internal data class ShopVisitProgress(
    val purchases: Int, val refreshes: Int, val purchaseId: String?, val imprint: String?,
    val purchaseStartedAt: Long, val purchaseCounted: Boolean, val choiceImprintPending: Boolean,
    val refreshStartedAt: Long, val refreshConfirmedAt: Long, val focus: LabyrinthRelicMark?,
    val closedFrames: Int, val closedAt: Long,
)

class ShopDomainModule(private val runtime: KernelRuntime) : KernelDomainModule {
    override val id = "shop"
    override val domain = KernelDomain.SHOP

    internal var relicPurchasesThisCycle = 0
        private set
    internal var refreshesUsedInVisit = 0
        private set
    internal var plannedPurchaseRelicId: String? = null
        private set
    internal var plannedPurchaseStartedAt = Long.MIN_VALUE
        private set
    internal var plannedRoleImprintLabel: String? = null
        private set
    internal var choiceImprintRolePending = false
        private set
    internal var plannedRefreshStartedAt = Long.MIN_VALUE
        private set
    internal var plannedRefreshConfirmedAt = Long.MIN_VALUE
        private set
    internal var focusMark: LabyrinthRelicMark? = null
        private set

    internal var plannedPurchaseCounted = false
        private set
    internal var pendingPurchaseRelic: LabyrinthRelicMatch? = null
        private set
    internal var imprintRewardConfirmedAt = Long.MIN_VALUE
        private set
    internal var lastShopDialogState = LabyrinthShopDialogState.NONE
        private set
    internal var shopConfirmClosedFrames = 0
        private set
    internal var shopConfirmClosedAt = Long.MIN_VALUE
        private set
    internal var lastObservedPage: LabyrinthEntryPageState? = null
        private set

    /** Observed balance for later area-specific rules. A missing reading is unknown. */
    internal var observedAlphaCoin: Int? = null
        private set
    /** Optional list position for future scrolling. Null leaves planning on the first screen. */
    internal var scrollStage: Int? = null
        private set

    private var nodeEntry: KernelEvent? = null
    private var lastDispatch: KernelEvent? = null
    private var lastReceipt: KernelEvent? = null
    private var lastActionDispatchedAt = Long.MIN_VALUE
    private var observedIdentityGate: LabyrinthRelicIdentityGate? = null
    private var permitParent: KernelEvent? = null
    private var permitKey: ShopActionKey? = null
    private var permitIssued: KernelEvent? = null
    private val permit = KernelActionPermit<ShopActionKey> { issued, key, revision, reason ->
        permitKey = key.takeIf { issued }
        val transition = runtime.emit(
            domain, if (issued) K.PERMIT_ISSUED else K.PERMIT_REVOKED,
            PermitTransitionPayload(key?.traceTarget(), revision, reason), permitParent, reason,
        )
        permitIssued = transition.takeIf { issued }
    }

    override fun reset(traceId: String) {
        synchronized(runtime) {
            resetVisit("session_reset")
            focusMark = null
        }
    }

    override fun onKernelEvent(event: KernelEvent, ctx: KernelRuntime) {
        if (ctx !== runtime || event.domain != domain || event.traceId != runtime.traceId) return
        when (event.kind) {
            K.ACTION_DISPATCHED -> {
                lastDispatch = event
                lastReceipt = null
            }
            K.ACTION_RECEIPT_OBSERVED -> if (event.causeEventSeq == lastDispatch?.eventSeq) {
                lastReceipt = event
            }
        }
    }

    internal fun onEnterShopNode(
        sessionId: String = runtime.traceId,
        area: Int? = null,
        timestampMillis: Long = 0L,
        cause: KernelEvent? = null,
        cancelRelicLedger: ((String?) -> Unit)? = null,
    ): KernelEvent = synchronized(runtime) {
        resetVisit("shop_node_entered", cause, cancelRelicLedger)
        val detail = buildMap {
            put("sessionId", sessionId)
            put("enteredAtMillis", timestampMillis.toString())
            if (area != null) put("area", area.toString())
        }
        runtime.emit(domain, K.STATE_REDUCED, StateReducedPayload(SHOP.name, detail = detail),
            cause, "shop_node_entered").also { nodeEntry = it }
    }

    private fun resetVisit(reason: String, cause: KernelEvent? = null, cancelRelicLedger: ((String?) -> Unit)? = null) {
        revoke(reason, cause)
        relicPurchasesThisCycle = 0
        refreshesUsedInVisit = 0
        clearPlannedPurchase(cancelRelicLedger)
        clearPlannedRefresh()
        clearChoiceImprintRolePending()
        imprintRewardConfirmedAt = Long.MIN_VALUE
        lastShopDialogState = LabyrinthShopDialogState.NONE
        lastObservedPage = null
        observedAlphaCoin = null
        scrollStage = null
        nodeEntry = null
        lastDispatch = null
        lastReceipt = null
        lastActionDispatchedAt = Long.MIN_VALUE
        observedIdentityGate?.clear()
        observedIdentityGate = null
    }

    internal fun restoreVisit(progress: ShopVisitProgress) = synchronized(runtime) {
        relicPurchasesThisCycle = progress.purchases
        refreshesUsedInVisit = progress.refreshes
        plannedPurchaseRelicId = progress.purchaseId
        plannedRoleImprintLabel = progress.imprint
        plannedPurchaseStartedAt = progress.purchaseStartedAt
        plannedPurchaseCounted = progress.purchaseCounted
        choiceImprintRolePending = progress.choiceImprintPending
        plannedRefreshStartedAt = progress.refreshStartedAt
        plannedRefreshConfirmedAt = progress.refreshConfirmedAt
        focusMark = progress.focus
        shopConfirmClosedFrames = progress.closedFrames
        shopConfirmClosedAt = progress.closedAt
    }

    internal fun visitProgress() = synchronized(runtime) {
        ShopVisitProgress(relicPurchasesThisCycle, refreshesUsedInVisit, plannedPurchaseRelicId,
            plannedRoleImprintLabel, plannedPurchaseStartedAt, plannedPurchaseCounted, choiceImprintRolePending,
            plannedRefreshStartedAt, plannedRefreshConfirmedAt, focusMark, shopConfirmClosedFrames, shopConfirmClosedAt)
    }

    internal fun authorize(
        key: ShopActionKey,
        capturedAtMillis: Long,
        cause: KernelEvent? = null,
    ): KernelActionPermit.Token = synchronized(runtime) {
        if (permitKey == key && permit.capture(key, capturedAtMillis) == null) revoke("permit_expired", cause)
        permitParent = cause
        permit.issue(key, capturedAtMillis)
    }

    internal fun revoke(reason: String, cause: KernelEvent? = null) {
        synchronized(runtime) {
            permitParent = cause ?: permitIssued ?: permitParent
            permit.revoke(reason)
            permitParent = null
        }
    }

    /** Recheck the key and the originally dispatched token immediately before executing a tap. */
    internal fun capturePermit(expected: ShopActionKey, nowMillis: Long): KernelActionPermit.Token? =
        synchronized(runtime) {
            val token = permit.capture(expected, nowMillis)
            if (token == null && permitKey == expected) revoke("permit_expired")
            token
        }

    internal fun planShopPage(
        result: LabyrinthEntryFrameResult,
        page: LabyrinthEntryPageState,
        shopPolicy: LabyrinthShopPolicy,
        currentArea: Int?,
        observedRelics: List<LabyrinthObservedRelic>,
        calibratedStacks: Map<LabyrinthRelicMark, Int>,
        relicIdentityGate: LabyrinthRelicIdentityGate,
        canPrepareRelic: (LabyrinthRelicMatch) -> Boolean,
        cancelRelicLedger: (String?) -> Unit,
        frameWidth: Int,
        frameHeight: Int,
        timestampMillis: Long,
        sessionId: String = runtime.traceId,
        observedAlphaCoin: Int? = null,
        scrollStage: Int? = null,
        cause: KernelEvent? = null,
        purchaseAlreadyObserved: Boolean = false,
    ): ShopPlanOutcome = synchronized(runtime) {
        val previous = lastObservedPage
        val observed = observeFrame(page, timestampMillis, cause)
        if (page == SHOP) {
            this.observedAlphaCoin = observedAlphaCoin
            this.scrollStage = scrollStage
        }
        val shop = result.shopObservation
        val candidate = shop?.purchaseCandidate
        observedIdentityGate = relicIdentityGate
        if (!purchaseAlreadyObserved) relicIdentityGate.observe(page, timestampMillis, when (page) {
            SHOP -> shop?.items.orEmpty().mapNotNull { it.relicMatch }
            SHOP_PURCHASE_CONFIRMATION -> listOfNotNull(candidate)
            else -> emptyList()
        })
        val wasCounted = plannedPurchaseCounted
        val completedRelic = if (purchaseAlreadyObserved) {
            lastObservedPage = page
            null
        } else observePurchase(page, shop?.dialogState, candidate, relicIdentityGate, timestampMillis, cancelRelicLedger)
        val purchaseCommitted = !wasCounted && plannedPurchaseCounted
        val parent = if (previous != page || purchaseCommitted) {
            runtime.emit(domain, K.STATE_REDUCED, StateReducedPayload(page.name,
                detail = mapOf("relicPurchasesThisCycle" to relicPurchasesThisCycle.toString())),
                observed, if (purchaseCommitted) "purchase_committed" else K.STATE_REDUCED)
        } else observed
        val frame = FramePlan(page, sessionId, timestampMillis, frameWidth, frameHeight, parent, completedRelic)

        when (page) {
            SHOP -> {
                when {
                    plannedRefreshStartedAt != Long.MIN_VALUE ->
                        frame.wait("等待商店刷新事务结束", "refresh_pending")
                    shopConfirmClosedFrames > 0 ->
                        frame.wait("购买确认弹窗已关闭，等待购买结果稳定", "purchase_close_grace")
                    else -> frame.planStock(result, shopPolicy, currentArea, observedRelics, calibratedStacks,
                        relicIdentityGate, canPrepareRelic, cancelRelicLedger)
                }
            }
            SHOP_PURCHASE_CONFIRMATION ->
                frame.planPurchaseConfirmation(result, candidate, relicIdentityGate, cancelRelicLedger)
            SHOP_PURCHASE_COMPLETE -> {
                val target = plannedPurchaseRelicId ?: plannedRoleImprintLabel
                if (target == null) {
                    frame.wait("人工购买完成弹窗保持人工控制", "manual_purchase_complete")
                } else {
                    frame.act(SHOP_CLOSE_PURCHASE_COMPLETE, "关闭购买完成",
                        frame.fallback(LabyrinthFallbackTap.SHOP_PURCHASE_COMPLETE_CLOSE), target)
                }
            }
            SHOP_EXIT_CONFIRMATION -> frame.act(SHOP_CONFIRM_EXIT, "确认退出商店",
                anchorRect(result, EntryAnchorId.SHOP_EXIT_CONFIRM_BUTTON)
                    ?: frame.fallback(LabyrinthFallbackTap.SHOP_EXIT_CONFIRM))
            else -> frame.wait("当前页面由其他流程处理", "outside_shop", handled = false)
        }
    }

    private fun observePurchase(
        page: LabyrinthEntryPageState,
        dialog: LabyrinthShopDialogState?,
        candidate: LabyrinthRelicMatch?,
        gate: LabyrinthRelicIdentityGate,
        now: Long,
        cancelRelicLedger: (String?) -> Unit,
    ): LabyrinthRelicMatch? {
        lastObservedPage = page
        if (labyrinthPageUiOwner(page) != LabyrinthPageUiOwner.SHOP) {
            if (!labyrinthKeepsPlannedShopPurchaseAcrossPage(page, plannedPurchaseRelicId ?: plannedRoleImprintLabel,
                    plannedPurchaseStartedAt, now, TRANSITION_TIMEOUT_MILLIS)) {
                clearPlannedPurchase(cancelRelicLedger)
                lastShopDialogState = LabyrinthShopDialogState.NONE
            }
            return null
        }
        val state = when (page) {
            SHOP_PURCHASE_CONFIRMATION -> LabyrinthShopDialogState.PURCHASE_CONFIRMATION
            SHOP_PURCHASE_COMPLETE -> LabyrinthShopDialogState.PURCHASE_COMPLETE
            SHOP_EXIT_CONFIRMATION -> LabyrinthShopDialogState.EXIT_CONFIRMATION
            else -> dialog ?: return null
        }
        val previous = lastShopDialogState
        lastShopDialogState = state
        when (state) {
            LabyrinthShopDialogState.PURCHASE_CONFIRMATION -> {
                resetConfirmCloseGrace()
                val plannedId = plannedPurchaseRelicId
                if (plannedId != null && candidate != null && candidate.relicId == plannedId && gate.confirmed(candidate)) {
                    pendingPurchaseRelic = candidate
                }
            }
            LabyrinthShopDialogState.PURCHASE_COMPLETE -> {
                resetConfirmCloseGrace()
                if (page == SHOP_PURCHASE_COMPLETE && countRelicPurchase()) {
                    return pendingPurchaseRelic.also { pendingPurchaseRelic = null }
                }
            }
            LabyrinthShopDialogState.NONE -> {
                if (plannedPurchaseRelicId == null && plannedRoleImprintLabel == null) return null
                if (previous == LabyrinthShopDialogState.PURCHASE_COMPLETE && plannedPurchaseCounted) {
                    clearPlannedPurchase()
                } else if (previous == LabyrinthShopDialogState.PURCHASE_CONFIRMATION) {
                    shopConfirmClosedFrames = 1
                    shopConfirmClosedAt = now
                } else if (shopConfirmClosedFrames > 0) {
                    shopConfirmClosedFrames++
                    if (shopConfirmClosedFrames >= 2 && now - shopConfirmClosedAt >= CONFIRM_CLOSE_GRACE_MILLIS) {
                        clearPlannedPurchase(cancelRelicLedger)
                    }
                }
            }
            LabyrinthShopDialogState.EXIT_CONFIRMATION -> clearPlannedPurchase(cancelRelicLedger)
        }
        return null
    }

    private fun FramePlan.planStock(
        result: LabyrinthEntryFrameResult,
        policy: LabyrinthShopPolicy,
        area: Int?,
        acquired: List<LabyrinthObservedRelic>,
        stacks: Map<LabyrinthRelicMark, Int>,
        gate: LabyrinthRelicIdentityGate,
        canPrepareRelic: (LabyrinthRelicMatch) -> Boolean,
        cancelRelicLedger: (String?) -> Unit,
    ): ShopPlanOutcome {
        val shop = result.shopObservation
        if (shop == null || shop.dialogState != LabyrinthShopDialogState.NONE) {
            return wait("商店商品识别尚未生成", "shop_stock_unavailable")
        }
        val refreshRect = anchorRect(result, EntryAnchorId.SHOP_REFRESH_BUTTON)
        return when (val decision = policy.decide(
            currentArea = area,
            items = shop.items,
            acquired = acquired,
            calibratedStacks = stacks,
            refreshAvailable = refreshRect != null && shop.refreshButtonEnabled,
            lockedFocus = focusMark,
            relicPurchasesInCycle = relicPurchasesThisCycle,
            refreshAvailabilityKnown = refreshRect != null,
            refreshesUsedInVisit = refreshesUsedInVisit,
        )) {
            is LabyrinthShopDecision.BuyRelic -> {
                val relicDecision = decision.relicDecision
                val relic = relicDecision.choice
                val relicId = relic.relicId ?: return wait("正在复核待购买遗物身份", "relic_identity_unconfirmed")
                if (plannedPurchaseRelicId != relicId) {
                    if (!plannedPurchaseCounted) cancelRelicLedger(plannedPurchaseRelicId)
                    clearPlannedPurchase()
                }
                if (!gate.confirmed(relic) || !canPrepareRelic(relic)) {
                    return wait("正在复核待购买遗物身份", "relic_identity_unconfirmed")
                }
                if (relicDecision.phase != LabyrinthRelicChoicePhase.BUILD_BASELINE) {
                    focusMark = relicDecision.focusMark
                }
                plannedPurchaseRelicId = relicId
                plannedRoleImprintLabel = null
                act(SHOP_BUY_RELIC, "购买遗物：${relic.displayName ?: relicId}；${relicDecision.reason}",
                    decision.item.buyButtonRect, relicId)
            }
            is LabyrinthShopDecision.BuyRoleImprint -> {
                if (!plannedPurchaseCounted) cancelRelicLedger(plannedPurchaseRelicId)
                if (plannedRoleImprintLabel != decision.label) clearPlannedPurchase()
                plannedRoleImprintLabel = decision.label
                choiceImprintRolePending = decision.opensRolePicker
                val form = if (decision.opensRolePicker) "选择" else "随机"
                act(SHOP_BUY_IMPRINT, "购买印记：${decision.label}（$form）；${decision.reason}",
                    decision.item.buyButtonRect, decision.label)
            }
            is LabyrinthShopDecision.Refresh -> {
                clearPlannedPurchase(cancelRelicLedger)
                act(SHOP_REFRESH, "刷新商店：${decision.reason}", refreshRect,
                    missingRectReason = "策略允许刷新，但刷新按钮没有达到安全识别线")
            }
            is LabyrinthShopDecision.Close -> {
                clearPlannedPurchase(cancelRelicLedger)
                act(SHOP_CLOSE, "关闭商店：${decision.reason}", anchorRect(result, EntryAnchorId.SHOP_CLOSE),
                    missingRectReason = "商店已无计划购买项，但关闭按钮未稳定识别")
            }
            is LabyrinthShopDecision.Wait -> wait(decision.reason, "shop_policy_wait")
        }
    }

    private fun FramePlan.planPurchaseConfirmation(
        result: LabyrinthEntryFrameResult,
        candidate: LabyrinthRelicMatch?,
        gate: LabyrinthRelicIdentityGate,
        cancelRelicLedger: (String?) -> Unit,
    ): ShopPlanOutcome {
        val plannedId = plannedPurchaseRelicId
        val imprint = plannedRoleImprintLabel
        if (plannedId == null && imprint == null) {
            return wait("未检测到程序发起的购买，本次确认弹窗保持人工控制", "manual_purchase_confirmation")
        }
        if (plannedPurchaseStartedAt == Long.MIN_VALUE || timestamp - plannedPurchaseStartedAt > TRANSITION_TIMEOUT_MILLIS) {
            clearPlannedPurchase(cancelRelicLedger)
            return wait("程序发起的购买确认已超时，本次弹窗保持人工控制", "purchase_confirmation_timeout")
        }
        val rect = anchorRect(result, EntryAnchorId.SHOP_EXIT_CONFIRM_BUTTON)
        if (imprint != null) {
            return act(SHOP_CONFIRM_IMPRINT, "确认购买印记：$imprint", rect, imprint,
                "印记购买确认已建立，但确认按钮未达到安全识别线")
        }
        if (candidate == null || candidate.relicId == null) {
            return wait("购买确认弹窗中的遗物尚未可靠识别，拒绝自动确认", "missing_confirmation_relic")
        }
        val candidateId = candidate.relicId
        if (!gate.confirmed(candidate)) {
            return wait("正在复读购买确认中的遗物身份", "confirmation_relic_unconfirmed")
        }
        if (candidateId != plannedId) {
            return wait("购买确认遗物${candidateId}与计划${plannedId}不一致，拒绝自动确认", "confirmation_relic_mismatch")
        }
        return act(SHOP_CONFIRM_RELIC, "确认购买遗物：${candidate.displayName ?: candidateId}", rect, candidateId,
            "购买确认遗物已核对，但确认按钮未达到安全识别线")
    }

    /** Route generic confirmations here before ordinary page planning while a refresh is active. */
    internal fun handleRefreshConfirmation(
        result: LabyrinthEntryFrameResult,
        timestampMillis: Long,
        frameWidth: Int = result.frameWidth,
        frameHeight: Int = result.frameHeight,
        sessionId: String = runtime.traceId,
        activeNodeType: Int? = LabyrinthNodeTypes.SHOP,
        lastPostEntryActionAt: Long? = null,
        cause: KernelEvent? = null,
    ): ShopPlanOutcome? = synchronized(runtime) {
        val startedAt = plannedRefreshStartedAt
        if (startedAt == Long.MIN_VALUE) return@synchronized null
        val page = result.observation.state
        val frame = FramePlan(page, sessionId, timestampMillis, frameWidth, frameHeight,
            observeFrame(page, timestampMillis, cause))

        if (timestampMillis - startedAt > TRANSITION_TIMEOUT_MILLIS) {
            clearPlannedRefresh()
            return@synchronized frame.wait("商店刷新确认超时；未重置本轮遗物计数，等待重新识别商店",
                "refresh_confirmation_timeout", handled = false)
        }
        val confirmation = result.nodeMoveConfirmation
        if (confirmation != null && labyrinthShopRefreshConfirmationOwnsFrame(
                activeNodeType, startedAt, plannedRefreshConfirmedAt, timestampMillis, true, TRANSITION_TIMEOUT_MILLIS,
            )) {
            val lastAction = lastPostEntryActionAt ?: lastActionDispatchedAt
            return@synchronized if (lastAction == Long.MIN_VALUE || timestampMillis - lastAction >= ACTION_INTERVAL_MILLIS) {
                frame.act(SHOP_CONFIRM_REFRESH, "确认刷新商店", confirmation.confirmButtonRect)
            } else {
                frame.wait("已识别更新确认，等待安全点击间隔", "refresh_action_interval")
            }
        }
        if (plannedRefreshConfirmedAt != Long.MIN_VALUE) {
            if (confirmation == null && labyrinthShopRefreshCanCommit(
                    page, startedAt, plannedRefreshConfirmedAt, timestampMillis, REFRESH_SETTLE_MILLIS,
                    TRANSITION_TIMEOUT_MILLIS,
                )) {
                relicPurchasesThisCycle = 0
                clearPlannedRefresh()
                val reduced = runtime.emit(domain, K.STATE_REDUCED, StateReducedPayload(page.name,
                    detail = mapOf("relicPurchasesThisCycle" to "0")), frame.parent, "refresh_committed")
                return@synchronized FramePlan(page, sessionId, timestampMillis, frameWidth, frameHeight, reduced)
                    .wait("商店已刷新，等待新商品列表稳定", "refresh_committed")
            }
            return@synchronized frame.wait("已确认刷新商店，等待新商品列表返回", "refresh_settling")
        }
        frame.wait("已点击用300更新，等待更新确认弹窗", "refresh_waiting_confirmation")
    }

    internal fun recordActionDispatched(
        actionKind: LabyrinthPostEntryActionKind,
        timestampMillis: Long,
        shopPurchaseRelicId: String? = null,
        shopPurchaseRoleImprintLabel: String? = null,
        actionKey: ShopActionKey? = null,
        cause: KernelEvent? = null,
        permitToken: KernelActionPermit.Token? = null,
    ): KernelEvent? = synchronized(runtime) {
        if (actionKind !in SHOP_ACTIONS || !actionAllowed(actionKind, actionKey, permitToken, timestampMillis)) {
            return@synchronized null
        }
        val relicId = if (actionKind == SHOP_BUY_RELIC || actionKind == SHOP_CONFIRM_RELIC) {
            shopPurchaseRelicId ?: actionKey?.targetId ?: plannedPurchaseRelicId
        } else null
        val imprint = if (actionKind == SHOP_BUY_IMPRINT || actionKind == SHOP_CONFIRM_IMPRINT) {
            shopPurchaseRoleImprintLabel ?: actionKey?.targetId ?: plannedRoleImprintLabel
        } else null
        if (actionKey != null && (relicId ?: imprint)?.let { it != actionKey.targetId } == true) {
            return@synchronized null
        }
        if (relicId != null || imprint != null) {
            if (relicId != plannedPurchaseRelicId || imprint != plannedRoleImprintLabel) clearPlannedPurchase()
            plannedPurchaseRelicId = relicId
            plannedRoleImprintLabel = imprint
            plannedPurchaseStartedAt = timestampMillis
        }
        val authorization = if (actionKey == null) null else {
            permitIssued?.takeIf { it.causeEventSeq == permitParent?.eventSeq }
                ?: runtime.emit(domain, K.PERMIT_ISSUED,
                    PermitTransitionPayload(actionKey.traceTarget(), permit.currentRevision, "dispatch_capture"),
                    permitParent, "dispatch_capture")
        }
        lastActionDispatchedAt = timestampMillis
        runtime.emit(domain, K.ACTION_DISPATCHED, ActionDispatchedPayload(actionKind.name),
            authorization ?: cause ?: permitIssued ?: lastReceipt ?: nodeEntry).also {
            lastDispatch = it
            lastReceipt = null
        }
    }

    internal fun recordActionNotExecuted(dispatch: KernelEvent, verdict: String): KernelEvent? =
        synchronized(runtime) {
            if (dispatch.traceId != runtime.traceId || lastReceipt?.causeEventSeq == dispatch.eventSeq) {
                return@synchronized null
            }
            val action = (dispatch.payload as? ActionDispatchedPayload)?.choiceId ?: return@synchronized null
            runtime.emit(domain, K.ACTION_RECEIPT_OBSERVED, ActionReceiptPayload(action, verdict), dispatch, verdict)
                .also { revoke(verdict, it) }
        }

    /** An executed receipt without a key may come from a caller that already checked the permit. */
    internal fun recordActionExecuted(
        actionKind: LabyrinthPostEntryActionKind,
        timestampMillis: Long,
        cancelRelicLedger: ((String?) -> Unit)? = null,
        cause: KernelEvent? = null,
        actionKey: ShopActionKey? = null,
        permitToken: KernelActionPermit.Token? = null,
    ): KernelEvent? = synchronized(runtime) {
        if (actionKind !in SHOP_ACTIONS || !actionAllowed(actionKind, actionKey, permitToken, timestampMillis)) {
            return@synchronized null
        }
        val dispatch = lastDispatch?.takeIf { (it.payload as? ActionDispatchedPayload)?.choiceId == actionKind.name }
        if (cause?.kind == K.ACTION_DISPATCHED && cause != dispatch) return@synchronized null
        if (dispatch != null && lastReceipt?.causeEventSeq == dispatch.eventSeq) return@synchronized null
        if (actionKind == SHOP_REFRESH && plannedRefreshStartedAt != Long.MIN_VALUE) return@synchronized null
        if (actionKind == SHOP_CONFIRM_REFRESH &&
            (plannedRefreshStartedAt == Long.MIN_VALUE || plannedRefreshConfirmedAt != Long.MIN_VALUE)) {
            return@synchronized null
        }
        val receipt = runtime.emit(domain, K.ACTION_RECEIPT_OBSERVED, ActionReceiptPayload(actionKind.name, "Executed"),
            dispatch ?: cause ?: permitIssued ?: lastReceipt ?: nodeEntry)
        lastReceipt = receipt
        when (actionKind) {
            SHOP_CLOSE_PURCHASE_COMPLETE -> {
                countRelicPurchase()
                clearPlannedPurchase()
            }
            SHOP_REFRESH -> {
                refreshesUsedInVisit++
                plannedRefreshStartedAt = timestampMillis
                plannedRefreshConfirmedAt = Long.MIN_VALUE
                clearPlannedPurchase(cancelRelicLedger)
            }
            SHOP_CONFIRM_REFRESH -> plannedRefreshConfirmedAt = timestampMillis
            SHOP_CONFIRM_IMPRINT -> imprintRewardConfirmedAt = timestampMillis
            SHOP_CLOSE, SHOP_CONFIRM_EXIT -> {
                clearPlannedPurchase(cancelRelicLedger)
                clearPlannedRefresh()
                clearChoiceImprintRolePending()
            }
            else -> Unit
        }
        revoke("action_executed", receipt)
        receipt
    }

    internal fun clearChoiceImprintRolePending() {
        synchronized(runtime) { choiceImprintRolePending = false }
    }

    private fun actionAllowed(
        kind: LabyrinthPostEntryActionKind,
        key: ShopActionKey?,
        token: KernelActionPermit.Token?,
        now: Long,
    ): Boolean = (key == null || (key.actionKind == kind && capturePermit(key, now) != null)) &&
        (token == null || token.isCurrent(now))

    private fun clearPlannedPurchase(cancelRelicLedger: ((String?) -> Unit)? = null) {
        if (!plannedPurchaseCounted) plannedPurchaseRelicId?.let { cancelRelicLedger?.invoke(it) }
        plannedPurchaseRelicId = null
        plannedRoleImprintLabel = null
        plannedPurchaseStartedAt = Long.MIN_VALUE
        plannedPurchaseCounted = false
        pendingPurchaseRelic = null
        resetConfirmCloseGrace()
    }

    private fun resetConfirmCloseGrace() {
        shopConfirmClosedFrames = 0
        shopConfirmClosedAt = Long.MIN_VALUE
    }

    private fun clearPlannedRefresh() {
        plannedRefreshStartedAt = Long.MIN_VALUE
        plannedRefreshConfirmedAt = Long.MIN_VALUE
    }

    internal fun recordCompletedPurchase(cause: KernelEvent?): Unit = synchronized(runtime) {
        if (countRelicPurchase()) runtime.emit(domain, K.STATE_REDUCED,
            StateReducedPayload(SHOP_PURCHASE_COMPLETE.name,
                detail = mapOf("relicPurchasesThisCycle" to relicPurchasesThisCycle.toString())),
            cause ?: lastReceipt ?: lastDispatch ?: nodeEntry, "purchase_committed")
    }

    private fun countRelicPurchase(): Boolean {
        if (plannedPurchaseRelicId == null || plannedPurchaseCounted) return false
        relicPurchasesThisCycle++
        plannedPurchaseCounted = true
        return true
    }

    private fun anchorRect(result: LabyrinthEntryFrameResult, id: String): EntryPixelRect? =
        result.anchorMatches[id]?.takeIf { it.score >= ANCHOR_MIN_SCORE }?.rect

    private fun observeFrame(page: LabyrinthEntryPageState, timestamp: Long, cause: KernelEvent? = null): KernelEvent =
        runtime.emit(domain, K.FRAME_OBSERVED, FrameObservedPayload(page.name, timestamp),
            cause ?: lastReceipt ?: lastDispatch ?: nodeEntry)

    private inner class FramePlan(
        val page: LabyrinthEntryPageState,
        val sessionId: String,
        val timestamp: Long,
        val width: Int,
        val height: Int,
        val parent: KernelEvent,
        val completedRelic: LabyrinthRelicMatch? = null,
    ) {
        fun fallback(tap: LabyrinthFallbackTap): EntryPixelRect? =
            if (width >= 3 && height >= 3) tap.rect(width, height) else null

        fun act(
            kind: LabyrinthPostEntryActionKind,
            label: String,
            rect: EntryPixelRect?,
            target: String? = null,
            missingRectReason: String = "未识别到安全点击区域",
        ): ShopPlanOutcome {
            if (rect == null) return wait(missingRectReason, "missing_shop_button")
            if (rect.left.toLong() + rect.width > width || rect.top.toLong() + rect.height > height) {
                return wait("按钮矩形与当前捕获尺寸不符", "shop_button_out_of_bounds")
            }
            val key = ShopActionKey(sessionId, page, kind, target, rect)
            val proposal = propose(kind.name, key.traceTarget(), label, "shop_action_proposed")
            val intervention = runtime.latestIntervention?.takeIf { it.eventSeq == proposal.eventSeq }
            val veto = intervention?.verdict as? KernelHookVerdict.Veto
            if (veto != null) {
                revoke("shop_action_vetoed", intervention.cause)
                return ShopPlanOutcome(null, KernelDecision.Wait(veto.reason), veto.reason,
                    decisionEvent = proposal, completedPurchaseRelic = completedRelic)
            }
            return ShopPlanOutcome(LabyrinthPostEntryTapPlan(kind, label, rect),
                KernelDecision.Act(kind.name, label, target, rect, label), null, key,
                authorize(key, timestamp, proposal), proposal, completedPurchaseRelic = completedRelic)
        }

        fun wait(reason: String, code: String, handled: Boolean = true): ShopPlanOutcome {
            val proposal = propose("Wait", null, reason, code)
            revoke(code, proposal)
            return ShopPlanOutcome(null, KernelDecision.Wait(reason), reason, decisionEvent = proposal,
                handled = handled, completedPurchaseRelic = completedRelic)
        }

        private fun propose(action: String, target: String?, reason: String, code: String): KernelEvent {
            val rule = runtime.emit(domain, K.EVENT_RULE_EVALUATED, PolicyRuleEvaluatedPayload(null, action),
                parent, code, reason)
            return runtime.emit(domain, K.EVENT_DECISION_PROPOSED, DecisionProposedPayload(id, target, target != null, reason),
                rule, code, reason)
        }
    }

    private companion object {
        const val ANCHOR_MIN_SCORE = 0.45
        const val ACTION_INTERVAL_MILLIS = 1_500L
        const val CONFIRM_CLOSE_GRACE_MILLIS = 2_000L
        const val REFRESH_SETTLE_MILLIS = 500L
        const val TRANSITION_TIMEOUT_MILLIS = 8_000L
        val SHOP_ACTIONS = setOf(SHOP_BUY_RELIC, SHOP_BUY_IMPRINT, SHOP_CONFIRM_RELIC, SHOP_CONFIRM_IMPRINT,
            SHOP_CLOSE_PURCHASE_COMPLETE, SHOP_REFRESH, SHOP_CONFIRM_REFRESH, SHOP_CLOSE, SHOP_CONFIRM_EXIT)
    }
}
