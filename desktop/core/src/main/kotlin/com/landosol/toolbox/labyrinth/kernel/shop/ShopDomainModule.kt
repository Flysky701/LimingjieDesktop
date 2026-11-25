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
import com.landosol.toolbox.labyrinth.LabyrinthShopRefreshOutcome
import com.landosol.toolbox.labyrinth.LabyrinthShopRefreshPhase
import com.landosol.toolbox.labyrinth.LabyrinthShopRefreshTransaction
import com.landosol.toolbox.labyrinth.LabyrinthShopScanController
import com.landosol.toolbox.labyrinth.LabyrinthShopScanController.Step
import com.landosol.toolbox.labyrinth.LabyrinthShopWaitKind
import com.landosol.toolbox.labyrinth.kernel.*
import com.landosol.toolbox.labyrinth.kernel.KernelEventKinds as K
import com.landosol.toolbox.labyrinth.labyrinthKeepsPlannedShopPurchaseAcrossPage
import com.landosol.toolbox.labyrinth.labyrinthPageUiOwner
import com.landosol.toolbox.labyrinth.vision.EntryAnchorId
import com.landosol.toolbox.labyrinth.vision.EntryPixelRect
import com.landosol.toolbox.labyrinth.vision.LabyrinthEntryFrameResult
import com.landosol.toolbox.labyrinth.vision.LabyrinthEntryPageState
import com.landosol.toolbox.labyrinth.vision.LabyrinthEntryPageState.*
import com.landosol.toolbox.labyrinth.vision.LabyrinthRelicMatch
import com.landosol.toolbox.labyrinth.vision.LabyrinthShopDialogState
import com.landosol.toolbox.labyrinth.vision.LabyrinthShopItemVisibility
import com.landosol.toolbox.labyrinth.vision.LabyrinthShopRefreshDialog
import com.landosol.toolbox.labyrinth.vision.LabyrinthShopRefreshDialogKind

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
    val scanStep: Step? = null,
    val refreshOutcome: LabyrinthShopRefreshOutcome? = null,
)

internal data class ShopVisitProgress(
    val purchases: Int, val refreshes: Int, val purchaseId: String?, val imprint: String?,
    val purchaseStartedAt: Long, val purchaseCounted: Boolean, val choiceImprintPending: Boolean,
    val refreshStartedAt: Long, val refreshConfirmedAt: Long, val focus: LabyrinthRelicMark?,
    val closedFrames: Int, val closedAt: Long,
    val refreshTransaction: LabyrinthShopRefreshTransaction? = null,
)

/** The run ledger and the planner share the very same purchase target during a suspended write. */
internal open class ShopPurchaseTarget {
    open var relicId: String? = null
    open var startedAt: Long = Long.MIN_VALUE
    open var confirmedRelic: LabyrinthRelicMatch? = null
}

class ShopDomainModule internal constructor(
    private val runtime: KernelRuntime,
    purchase: ShopPurchaseTarget,
) : KernelDomainModule {
    constructor(runtime: KernelRuntime) : this(runtime, ShopPurchaseTarget())

    override val id = "shop"
    override val domain = KernelDomain.SHOP

    override fun status(): KernelModuleStatus = synchronized(runtime) {
        val page = lastObservedPage
        val phase = when {
            shopRefreshTransaction.pending != null -> "refresh"
            plannedPurchaseRelicId != null || plannedRoleImprintLabel != null -> "purchase"
            page != null && labyrinthPageUiOwner(page) == LabyrinthPageUiOwner.SHOP -> page.name
            nodeEntry != null && page == null -> "entered"
            else -> return@synchronized super.status()
        }
        KernelModuleStatus(id, domain, true, phase)
    }

    internal var relicPurchasesThisCycle = 0
        private set
    internal var refreshesUsedInVisit = 0
        private set
    private val refreshFee get() = if (refreshesUsedInVisit == 0) 300 else 600
    internal var plannedPurchaseRelicId by purchase::relicId
        private set
    internal var plannedPurchaseStartedAt by purchase::startedAt
        private set
    internal var plannedRoleImprintLabel: String? = null
        private set
    internal var choiceImprintRolePending = false
        private set
    internal val plannedRefreshStartedAt get() = shopRefreshTransaction.pending?.requestedAt ?: Long.MIN_VALUE
    internal val plannedRefreshConfirmedAt get() = shopRefreshTransaction.pending?.confirmedAt ?: Long.MIN_VALUE
    @Volatile internal var focusMark: LabyrinthRelicMark? = null

    internal var plannedPurchaseCounted = false
        private set
    internal var pendingPurchaseRelic by purchase::confirmedRelic
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

    /** Confirmed balance on the current stock frame. Never reuse it for a later unread wallet. */
    internal var observedAlphaCoin: Int? = null
        private set
    /** Position supplied by the caller or measured from the current scroll thumb. */
    internal var scrollStage: Int? = null
        private set

    internal val shopScan = LabyrinthShopScanController()
    private var shopCoinUnconfirmedSince = Long.MIN_VALUE
    internal var shopRefreshTransaction = LabyrinthShopRefreshTransaction()
        private set
    private data class RefreshBaseline(val capturedAt: Long, val coins: Int, val visual: Long, val fee: Int)
    private var preparedShopRefresh: RefreshBaseline? = null
    // Keep the dispatched baseline until its executor receipt, even if pause revokes planning evidence.
    private var dispatchedRefresh: RefreshBaseline? = null
    private var confirmationDialog: LabyrinthShopRefreshDialog? = null

    private var nodeEntry: KernelEvent? = null
    private var lastDispatch: KernelEvent? = null
    private var lastReceipt: KernelEvent? = null
    private var lastActionDispatchedAt = Long.MIN_VALUE
    private var purchaseStartedBeforeDispatch = Long.MIN_VALUE
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
        shopRefreshTransaction = LabyrinthShopRefreshTransaction()
        dispatchedRefresh = null
        resetShopScrollState()
        clearChoiceImprintRolePending()
        imprintRewardConfirmedAt = Long.MIN_VALUE
        lastShopDialogState = LabyrinthShopDialogState.NONE
        lastObservedPage = null
        nodeEntry = null
        lastDispatch = null
        lastReceipt = null
        lastActionDispatchedAt = Long.MIN_VALUE
        observedIdentityGate?.clear()
        observedIdentityGate = null
    }

    internal fun restoreVisit(progress: ShopVisitProgress) = synchronized(runtime) {
        // Two timestamps alone cannot restore authority to confirm a payment.
        check(dispatchedRefresh == null && (shopRefreshTransaction.pending == null ||
            progress.refreshTransaction === shopRefreshTransaction)) { "待结算刷新不能被旧进度覆盖" }
        require(progress.refreshStartedAt == (progress.refreshTransaction?.pending?.requestedAt ?: Long.MIN_VALUE) &&
            progress.refreshConfirmedAt == (progress.refreshTransaction?.pending?.confirmedAt ?: Long.MIN_VALUE))
        revoke("visit_restored")
        shopRefreshTransaction = progress.refreshTransaction ?: LabyrinthShopRefreshTransaction()
        dispatchedRefresh = null
        resetShopScrollState()
        relicPurchasesThisCycle = progress.purchases
        refreshesUsedInVisit = progress.refreshes
        plannedPurchaseRelicId = progress.purchaseId
        plannedRoleImprintLabel = progress.imprint
        plannedPurchaseStartedAt = progress.purchaseStartedAt
        plannedPurchaseCounted = progress.purchaseCounted
        choiceImprintRolePending = progress.choiceImprintPending
        focusMark = progress.focus
        shopConfirmClosedFrames = progress.closedFrames
        shopConfirmClosedAt = progress.closedAt
    }

    internal fun visitProgress() = synchronized(runtime) {
        ShopVisitProgress(relicPurchasesThisCycle, refreshesUsedInVisit, plannedPurchaseRelicId,
            plannedRoleImprintLabel, plannedPurchaseStartedAt, plannedPurchaseCounted, choiceImprintRolePending,
            plannedRefreshStartedAt, plannedRefreshConfirmedAt, focusMark, shopConfirmClosedFrames, shopConfirmClosedAt,
            shopRefreshTransaction.takeIf { it.pending != null })
    }

    internal fun resetShopScrollState(capturedAfter: Long = Long.MIN_VALUE) = synchronized(runtime) {
        revoke("shop_scan_reset")
        shopScan.reset(capturedAfter)
        shopCoinUnconfirmedSince = Long.MIN_VALUE
        observedAlphaCoin = null
        scrollStage = null
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
            preparedShopRefresh = null
            confirmationDialog = null
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
        nowMillis: Long = timestampMillis,
        capturedAt: Long = timestampMillis,
    ): ShopPlanOutcome = synchronized(runtime) {
        handleRefreshConfirmation(result, timestampMillis, frameWidth, frameHeight, sessionId,
            cause = cause, nowMillis = nowMillis, capturedAt = capturedAt)?.let { return@synchronized it }
        val previous = lastObservedPage
        val observed = observeFrame(page, timestampMillis, cause)
        val shop = result.shopObservation
        if (page == SHOP) {
            this.observedAlphaCoin = shop?.coinBalance?.takeIf { shop.coinBalanceConfirmed } ?: observedAlphaCoin
            this.scrollStage = scrollStage ?: shop?.viewport?.position
            if (this.observedAlphaCoin != null) shopCoinUnconfirmedSince = Long.MIN_VALUE
        }
        val candidate = shop?.purchaseCandidate
        observedIdentityGate = relicIdentityGate
        if (!purchaseAlreadyObserved) relicIdentityGate.observe(page, timestampMillis, when (page) {
            SHOP -> shop?.items.orEmpty().filter { it.visibility == LabyrinthShopItemVisibility.FULL }
                .mapNotNull { it.relicMatch }
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
                    shopConfirmClosedFrames > 0 ->
                        frame.wait("购买确认弹窗已关闭，等待购买结果稳定", "purchase_close_grace")
                    else -> frame.planStock(result, shopPolicy, currentArea, observedRelics, calibratedStacks,
                        relicIdentityGate, canPrepareRelic, cancelRelicLedger, nowMillis, capturedAt)
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

    /** Consumed by the run ledger before planning, including on frames held by another page handler. */
    internal fun observePurchaseFrame(
        result: LabyrinthEntryFrameResult,
        gate: LabyrinthRelicIdentityGate,
        timestampMillis: Long,
        cancelRelicLedger: (String?) -> Unit,
        cause: KernelEvent?,
    ): LabyrinthRelicMatch? = synchronized(runtime) {
        val counted = plannedPurchaseCounted
        val page = result.observation.state
        val shop = result.shopObservation
        val completed = observePurchase(page, shop?.dialogState, shop?.purchaseCandidate, gate,
            timestampMillis, cancelRelicLedger)
        if (!counted && plannedPurchaseCounted) {
            runtime.emit(domain, K.STATE_REDUCED, StateReducedPayload(page.name,
                detail = mapOf("relicPurchasesThisCycle" to relicPurchasesThisCycle.toString())),
                cause ?: lastReceipt ?: nodeEntry, "purchase_committed")
        }
        completed
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

    internal fun currentShopDecision(
        result: LabyrinthEntryFrameResult,
        policy: LabyrinthShopPolicy,
        area: Int?,
        acquired: List<LabyrinthObservedRelic>,
        stacks: Map<LabyrinthRelicMark, Int>,
        observedAlphaCoin: Int? = null,
        scanComplete: Boolean = shopScan.complete,
        notifyObserver: Boolean = true,
    ): LabyrinthShopDecision? = synchronized(runtime) {
        val shop = result.shopObservation ?: return@synchronized null
        if (shop.dialogState != LabyrinthShopDialogState.NONE) return@synchronized null
        shopRefreshTransaction.pending?.let { return@synchronized LabyrinthShopDecision.Wait(refreshWaitMessage(it.reason)) }
        val scannedArea = area == 3 || area == 4 || area == 5
        val coins = shop.coinBalance?.takeIf { shop.coinBalanceConfirmed } ?: observedAlphaCoin
        val refreshReady = anchorRect(result, EntryAnchorId.SHOP_REFRESH_BUTTON) != null
        val decision = policy.decide(
            currentArea = area,
            items = shop.items.filter { it.visibility == LabyrinthShopItemVisibility.FULL },
            acquired = acquired, calibratedStacks = stacks, lockedFocus = focusMark,
            refreshAvailable = refreshReady && shop.refreshButtonEnabled, refreshAvailabilityKnown = refreshReady,
            relicPurchasesInCycle = relicPurchasesThisCycle, refreshesUsedInVisit = refreshesUsedInVisit,
            coinBalance = coins,
            stockScanComplete = !scannedArea || scanComplete,
            allowSecondaryPurchases = true, allowClearancePurchases = true,
            notifyObserver = notifyObserver,
        )
        if (decision is LabyrinthShopDecision.Close && decision.immediate ||
            decision is LabyrinthShopDecision.Wait && decision.kind == LabyrinthShopWaitKind.COINS) return@synchronized decision
        if (scannedArea) shopScan.readingReason(shop)?.let {
            return@synchronized LabyrinthShopDecision.Wait("本页读取未完成：$it", LabyrinthShopWaitKind.ITEMS)
        }
        if (decision is LabyrinthShopDecision.Refresh) {
            if (coins == null) return@synchronized LabyrinthShopDecision.Wait("等待刷新前金币确认")
            if (coins < refreshFee || shop.visualFingerprint == 0L)
                return@synchronized LabyrinthShopDecision.Wait("等待可信的刷新前余额和库存画面")
        }
        decision
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
        now: Long,
        capturedAt: Long,
    ): ShopPlanOutcome {
        val shop = result.shopObservation
        if (shop == null || shop.dialogState != LabyrinthShopDialogState.NONE) {
            return wait("商店商品识别尚未生成", "shop_stock_unavailable")
        }
        var decision = currentShopDecision(result, policy, area, acquired, stacks, observedAlphaCoin, notifyObserver = false)
            ?: return wait("商店商品识别尚未生成", "shop_stock_unavailable")
        if (decision is LabyrinthShopDecision.Wait && decision.kind == LabyrinthShopWaitKind.COINS) {
            if (shopCoinUnconfirmedSince == Long.MIN_VALUE) shopCoinUnconfirmedSince = now
            if (now - shopCoinUnconfirmedSince >= 30_000L)
                return stop("商店金币超过30秒未能识别确定，已停止并保留诊断状态", "shop_coins_timeout")
            return wait(decision.reason, "shop_coins_unconfirmed")
        }
        if ((area == 3 || area == 4 || area == 5) && !(decision is LabyrinthShopDecision.Close && decision.immediate)) {
            val wasComplete = shopScan.complete
            val step = shopScan.observe(shop, now, capturedAt, currentArea = area)
            if (step !is Step.Evaluate) return scan(step)
            if (step.complete != wasComplete) {
                decision = requireNotNull(currentShopDecision(
                    result, policy, area, acquired, stacks, observedAlphaCoin, step.complete, notifyObserver = false,
                ))
            }
            if (decision is LabyrinthShopDecision.Wait) {
                if (decision.kind == LabyrinthShopWaitKind.SCAN) return scan(shopScan.advance(shop, now))
                if (decision.kind == LabyrinthShopWaitKind.OTHER && shopScan.readTimedOut(now))
                    return stop("商店安全复核超时：${decision.reason}", "shop_read_timeout")
            }
        }
        return when (decision) {
            is LabyrinthShopDecision.BuyRelic -> {
                val relicDecision = decision.relicDecision
                val relic = relicDecision.choice
                val relicId = relic.relicId ?: return waitForRelicIdentity(now)
                if (plannedPurchaseRelicId != relicId) {
                    if (!plannedPurchaseCounted) cancelRelicLedger(plannedPurchaseRelicId)
                    clearPlannedPurchase()
                }
                if (!gate.confirmed(relic) || !canPrepareRelic(relic)) {
                    return waitForRelicIdentity(now)
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
                val baseline = RefreshBaseline(capturedAt, requireNotNull(observedAlphaCoin), shop.visualFingerprint, refreshFee)
                act(SHOP_REFRESH, "刷新商店：${decision.reason}", anchorRect(result, EntryAnchorId.SHOP_REFRESH_BUTTON),
                    missingRectReason = "策略允许刷新，但刷新按钮没有达到安全识别线").also {
                    if (it.permitToken != null) preparedShopRefresh = baseline
                }
            }
            is LabyrinthShopDecision.Close -> {
                clearPlannedPurchase(cancelRelicLedger)
                act(SHOP_CLOSE, "关闭商店：${decision.reason}", anchorRect(result, EntryAnchorId.SHOP_CLOSE),
                    missingRectReason = "商店已无计划购买项，但关闭按钮未稳定识别")
            }
            is LabyrinthShopDecision.Wait -> wait(decision.reason, "shop_policy_wait")
        }.also { outcome ->
            if (outcome.tapPlan != null) policy.notifyDecision(decision)
        }
    }

    private fun FramePlan.waitForRelicIdentity(now: Long): ShopPlanOutcome =
        if (shopScan.readTimedOut(now)) stop("商店遗物身份复核超时，拒绝盲买", "shop_relic_identity_timeout")
        else wait("正在复核待购买遗物身份", "relic_identity_unconfirmed")

    private fun FramePlan.scan(step: Step): ShopPlanOutcome {
        val reason = when (step) {
            is Step.Stop -> return stop(step.reason, "shop_scan_stopped")
            is Step.Wait -> step.reason
            is Step.Reacquire -> step.reason
            is Step.Reposition, is Step.Swipe -> "商店扫描翻页，等待动作完成及新画面"
            is Step.Evaluate -> "整店扫描已完成，等待复核"
        }
        return wait(reason, "shop_scan").copy(scanStep = step)
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

    private fun refreshWaitMessage(reason: String) = "刷新事务未结算（已用${refreshesUsedInVisit}次）：$reason"

    /** Session teardown clears live authority. Keep the outstanding financial evidence in its causal trace. */
    internal fun recordPendingRefreshOnStop(cause: KernelEvent?): KernelEvent? = synchronized(runtime) {
        val pending = shopRefreshTransaction.pending ?: return@synchronized null
        runtime.emit(domain, K.STATE_REDUCED, StateReducedPayload(SHOP.name, detail = mapOf(
            "phase" to pending.phase.name, "requestedAt" to pending.requestedAt.toString(),
            "confirmedAt" to pending.confirmedAt.toString(), "expectedFee" to pending.expectedFee.toString(),
            "coinsBefore" to pending.coinsBefore.toString(), "observedCoins" to pending.observedCoins.toString(),
            "visualBefore" to pending.visualBefore.toString(), "observedVisual" to pending.observedVisual.toString(),
            "lastCapturedAt" to pending.lastCapturedAt.toString(), "stableFrames" to pending.stableFrames.toString(),
            "refreshesUsedInVisit" to refreshesUsedInVisit.toString(),
        )), cause ?: lastReceipt, "refresh_pending_at_stop", pending.reason)
    }

    private fun authorizesRefresh(dialog: LabyrinthShopRefreshDialog?): Boolean {
        val pending = shopRefreshTransaction.pending ?: return false
        return pending.phase == LabyrinthShopRefreshPhase.REQUESTED && pending.confirmedAt == null &&
            dialog?.authorizesRefresh(pending.expectedFee) == true && dialog.capturedAtMillis > pending.requestedAt
    }

    /** While payment is outstanding the transaction owns even UNKNOWN and visually similar exit dialogs. */
    internal fun handleRefreshConfirmation(
        result: LabyrinthEntryFrameResult,
        timestampMillis: Long,
        frameWidth: Int = result.frameWidth,
        frameHeight: Int = result.frameHeight,
        sessionId: String = runtime.traceId,
        lastPostEntryActionAt: Long = lastActionDispatchedAt,
        cause: KernelEvent? = null,
        nowMillis: Long = timestampMillis,
        capturedAt: Long = timestampMillis,
    ): ShopPlanOutcome? = synchronized(runtime) {
        val before = shopRefreshTransaction.pending ?: return@synchronized null
        val page = result.observation.state
        val frame = FramePlan(page, sessionId, timestampMillis, frameWidth, frameHeight,
            observeFrame(page, capturedAt, cause))
        val shop = result.shopObservation?.takeIf { page == SHOP }
        val outcome = shopRefreshTransaction.observe(shop, nowMillis, capturedAt)
        val plan = when (outcome) {
            LabyrinthShopRefreshOutcome.COMMITTED -> {
                refreshesUsedInVisit++
                relicPurchasesThisCycle = 0
                resetShopScrollState()
                val reduced = runtime.emit(domain, K.STATE_REDUCED, StateReducedPayload(page.name,
                    detail = mapOf("refreshesUsedInVisit" to refreshesUsedInVisit.toString(), "relicPurchasesThisCycle" to "0",
                        "coinsBefore" to before.coinsBefore.toString(), "fee" to before.expectedFee.toString(),
                        "coinsAfter" to shop?.coinBalance.toString())), frame.parent, "refresh_committed")
                FramePlan(page, sessionId, timestampMillis, frameWidth, frameHeight, reduced)
                    .wait("刷新扣费已核验，提交第${refreshesUsedInVisit}次刷新，重读新库存", "refresh_committed")
            }
            LabyrinthShopRefreshOutcome.CANCELLED ->
                frame.stop("刷新已取消或未扣费，次数不增加；已停住，避免重复付费", "refresh_cancelled")
            LabyrinthShopRefreshOutcome.UNRESOLVED -> {
                val pending = requireNotNull(shopRefreshTransaction.pending)
                val awaitingSecondPaidFrame = pending.observedCoins == pending.coinsBefore - pending.expectedFee &&
                    pending.stableFrames == 1 && nowMillis - (pending.lastCapturedAt ?: 0L) in 0..3_000L
                if (awaitingSecondPaidFrame) frame.wait(refreshWaitMessage(pending.reason), "refresh_awaiting_second_frame")
                else frame.stop("刷新结果无法核实，次数未提交：" + pending.reason, "refresh_unresolved")
            }
            LabyrinthShopRefreshOutcome.WAIT -> {
                val confirmation = result.nodeMoveConfirmation
                val dialog = result.shopRefreshDialog
                when {
                    confirmation != null && authorizesRefresh(dialog) -> {
                        if (lastPostEntryActionAt != Long.MIN_VALUE && timestampMillis - lastPostEntryActionAt < ACTION_INTERVAL_MILLIS)
                            frame.wait("已识别更新确认，等待安全点击间隔", "refresh_action_interval")
                        else frame.act(SHOP_CONFIRM_REFRESH, "确认刷新商店（${before.expectedFee}金币，弹窗语义已核对）",
                            confirmation.confirmButtonRect).also { if (it.permitToken != null) confirmationDialog = dialog }
                    }
                    dialog?.kind == LabyrinthShopRefreshDialogKind.EXIT && dialog.confirmed ->
                        frame.stop("检测到退出确认而非更新确认，停止并保留刷新事务，未增加次数", "refresh_exit_dialog")
                    else -> frame.wait(refreshWaitMessage(requireNotNull(shopRefreshTransaction.pending).reason), "refresh_pending")
                }
            }
        }
        plan.copy(refreshOutcome = outcome)
    }

    internal fun recordActionDispatched(
        actionKind: LabyrinthPostEntryActionKind,
        timestampMillis: Long,
        shopPurchaseRelicId: String? = null,
        shopPurchaseRoleImprintLabel: String? = null,
        actionKey: ShopActionKey? = null,
        cause: KernelEvent? = null,
        permitToken: KernelActionPermit.Token? = null,
        dialog: LabyrinthShopRefreshDialog? = confirmationDialog,
    ): KernelEvent? = synchronized(runtime) {
        val key = actionKey ?: return@synchronized null
        if (actionKind !in SHOP_ACTIONS || !actionAllowed(actionKind, key, permitToken, timestampMillis) ||
            lastDispatch != null && lastReceipt == null) return@synchronized null
        if (actionKind == SHOP_REFRESH && (preparedShopRefresh?.capturedAt != timestampMillis ||
                shopRefreshTransaction.pending != null) ||
            actionKind == SHOP_CONFIRM_REFRESH && !authorizesRefresh(dialog)) {
            revoke("refresh_evidence_invalid", cause)
            return@synchronized null
        }
        val relicId = if (actionKind == SHOP_BUY_RELIC || actionKind == SHOP_CONFIRM_RELIC) {
            shopPurchaseRelicId ?: key.targetId ?: plannedPurchaseRelicId
        } else null
        val imprint = if (actionKind == SHOP_BUY_IMPRINT || actionKind == SHOP_CONFIRM_IMPRINT) {
            shopPurchaseRoleImprintLabel ?: key.targetId ?: plannedRoleImprintLabel
        } else null
        if ((relicId ?: imprint)?.let { it != key.targetId } == true) {
            return@synchronized null
        }
        purchaseStartedBeforeDispatch = plannedPurchaseStartedAt
        if (relicId != null || imprint != null) {
            if (relicId != plannedPurchaseRelicId || imprint != plannedRoleImprintLabel) clearPlannedPurchase()
            plannedPurchaseRelicId = relicId
            plannedRoleImprintLabel = imprint
            plannedPurchaseStartedAt = timestampMillis
        }
        val authorization = permitIssued?.takeIf { it.causeEventSeq == permitParent?.eventSeq }
            ?: runtime.emit(domain, K.PERMIT_ISSUED,
                PermitTransitionPayload(key.traceTarget(), permit.currentRevision, "dispatch_capture"),
                permitParent, "dispatch_capture")
        if (actionKind == SHOP_REFRESH) dispatchedRefresh = preparedShopRefresh
        lastActionDispatchedAt = timestampMillis
        runtime.emit(domain, K.ACTION_DISPATCHED, ActionDispatchedPayload(actionKind.name), authorization).also {
            lastDispatch = it
            lastReceipt = null
        }
    }

    internal fun recordActionNotExecuted(dispatch: KernelEvent, verdict: String): KernelEvent? =
        synchronized(runtime) {
            if (dispatch != lastDispatch || dispatch.traceId != runtime.traceId || lastReceipt != null) {
                return@synchronized null
            }
            val action = (dispatch.payload as? ActionDispatchedPayload)?.choiceId ?: return@synchronized null
            if (action == SHOP_REFRESH.name) dispatchedRefresh = null
            if (action in setOf(SHOP_BUY_RELIC.name, SHOP_BUY_IMPRINT.name, SHOP_CONFIRM_RELIC.name, SHOP_CONFIRM_IMPRINT.name))
                plannedPurchaseStartedAt = purchaseStartedBeforeDispatch
            runtime.emit(domain, K.ACTION_RECEIPT_OBSERVED, ActionReceiptPayload(action, verdict), dispatch, verdict)
                .also { revoke(verdict, it) }
        }

    /** Native completion belongs to its dispatch even when pause has since revoked the permit. */
    internal fun recordActionExecuted(
        actionKind: LabyrinthPostEntryActionKind,
        timestampMillis: Long,
        cancelRelicLedger: ((String?) -> Unit)? = null,
        cause: KernelEvent? = null,
        actionKey: ShopActionKey? = null,
        permitToken: KernelActionPermit.Token? = null,
    ): KernelEvent? = synchronized(runtime) {
        val dispatch = lastDispatch?.takeIf { (it.payload as? ActionDispatchedPayload)?.choiceId == actionKind.name }
            ?: return@synchronized null
        if (cause != null && cause != dispatch || lastReceipt != null) return@synchronized null
        if (cause == null && !actionAllowed(actionKind, actionKey, permitToken, timestampMillis)) return@synchronized null
        var reason = K.ACTION_RECEIPT_OBSERVED
        when (actionKind) {
            SHOP_CLOSE_PURCHASE_COMPLETE -> {
                countRelicPurchase()
                shopScan.purchased(timestampMillis)
                clearPlannedPurchase()
            }
            SHOP_REFRESH -> {
                val baseline = dispatchedRefresh
                dispatchedRefresh = null
                preparedShopRefresh = null
                if (baseline == null || !shopRefreshTransaction.begin(timestampMillis, baseline.coins, baseline.visual, baseline.fee))
                    reason = "refresh_baseline_invalid"
                clearPlannedPurchase(cancelRelicLedger)
            }
            SHOP_CONFIRM_REFRESH -> shopRefreshTransaction.confirmed(timestampMillis)
            SHOP_CONFIRM_IMPRINT -> imprintRewardConfirmedAt = timestampMillis
            SHOP_CLOSE, SHOP_CONFIRM_EXIT -> {
                clearPlannedPurchase(cancelRelicLedger)
                clearChoiceImprintRolePending()
            }
            else -> Unit
        }
        val receipt = runtime.emit(domain, K.ACTION_RECEIPT_OBSERVED, ActionReceiptPayload(actionKind.name, "Executed"),
            dispatch, reason)
        lastReceipt = receipt
        revoke("action_executed", receipt)
        receipt
    }

    internal fun clearChoiceImprintRolePending() {
        synchronized(runtime) { choiceImprintRolePending = false }
    }

    internal fun ownsAction(kind: LabyrinthPostEntryActionKind) = kind in SHOP_ACTIONS

    /** Compatibility for a caller delivering a relic tap already staged in the acquisition ledger. */
    internal fun planPreparedRelicTap(
        kind: LabyrinthPostEntryActionKind, relicId: String, label: String, rect: EntryPixelRect,
        timestamp: Long, width: Int, height: Int, cause: KernelEvent?,
    ): ShopPlanOutcome? = synchronized(runtime) {
        if (relicId != plannedPurchaseRelicId || plannedPurchaseStartedAt == Long.MIN_VALUE ||
            timestamp - plannedPurchaseStartedAt !in 0..TRANSITION_TIMEOUT_MILLIS || shopRefreshTransaction.pending != null)
            return@synchronized null
        val page = when (kind) {
            SHOP_BUY_RELIC -> SHOP
            SHOP_CONFIRM_RELIC -> if (pendingPurchaseRelic?.relicId == relicId) SHOP_PURCHASE_CONFIRMATION else return@synchronized null
            else -> return@synchronized null
        }
        FramePlan(page, runtime.traceId, timestamp, width, height, observeFrame(page, timestamp, cause))
            .act(kind, label, rect, relicId)
    }

    private fun actionAllowed(
        kind: LabyrinthPostEntryActionKind,
        key: ShopActionKey?,
        token: KernelActionPermit.Token?,
        now: Long,
    ): Boolean = (key == null || (key.actionKind == kind && capturePermit(key, now) != null)) &&
        (token == null || token.isCurrent(now))

    internal fun clearPlannedPurchase(cancelRelicLedger: ((String?) -> Unit)? = null) = synchronized(runtime) {
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

        fun stop(reason: String, code: String): ShopPlanOutcome {
            val proposal = propose("FinishRun", null, reason, code)
            revoke(code, proposal)
            return ShopPlanOutcome(null, KernelDecision.FinishRun(false, reason), reason, decisionEvent = proposal)
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
        const val TRANSITION_TIMEOUT_MILLIS = 8_000L
        val SHOP_ACTIONS = setOf(SHOP_BUY_RELIC, SHOP_BUY_IMPRINT, SHOP_CONFIRM_RELIC, SHOP_CONFIRM_IMPRINT,
            SHOP_CLOSE_PURCHASE_COMPLETE, SHOP_REFRESH, SHOP_CONFIRM_REFRESH, SHOP_CLOSE, SHOP_CONFIRM_EXIT)
    }
}
