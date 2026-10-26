package com.landosol.toolbox.labyrinth.execution.shop

import com.landosol.toolbox.labyrinth.*
import com.landosol.toolbox.automation.AutomationSessionId
import com.landosol.toolbox.labyrinth.vision.EntryAnchorId
import com.landosol.toolbox.labyrinth.vision.EntryPixelRect
import com.landosol.toolbox.labyrinth.vision.LabyrinthEntryFrameResult
import com.landosol.toolbox.labyrinth.vision.LabyrinthEntryPageState
import com.landosol.toolbox.labyrinth.vision.LabyrinthRelicMatch
import com.landosol.toolbox.labyrinth.vision.LabyrinthShopDialogState
import com.landosol.toolbox.labyrinth.node.LabyrinthNodeTypes
import kotlinx.coroutines.flow.MutableStateFlow
import com.landosol.toolbox.labyrinth.LabyrinthExecutionLimits.POST_ENTRY_ACTION_INTERVAL_MILLIS
import com.landosol.toolbox.labyrinth.LabyrinthExecutionLimits.POST_ENTRY_ANCHOR_MIN_SCORE
import com.landosol.toolbox.labyrinth.LabyrinthExecutionLimits.SHOP_PURCHASE_TRANSITION_TIMEOUT_MILLIS
import com.landosol.toolbox.labyrinth.LabyrinthExecutionLimits.SHOP_REFRESH_SETTLE_MILLIS
import com.landosol.toolbox.labyrinth.LabyrinthExecutionLimits.SHOP_REFRESH_TRANSITION_TIMEOUT_MILLIS

internal interface LabyrinthShopPort {
    val _state: MutableStateFlow<LabyrinthEntryRecognitionSessionState>
    val activeNodeArea: Int?
    var activeNodeType: Int?
    val activeSessionId: AutomationSessionId?
    val clock: () -> Long
    val lastPostEntryActionAt: Long
    var lastShopDialogState: LabyrinthShopDialogState
    var pendingShopPurchase: LabyrinthRelicMatch?
    var plannedShopPurchaseCounted: Boolean
    var plannedShopPurchaseRelicId: String?
    var plannedShopPurchaseStartedAt: Long
    var plannedShopRefreshConfirmedAt: Long
    var plannedShopRefreshStartedAt: Long
    var plannedShopRoleImprintLabel: String?
    var postEntryAttempts: Int
    var postEntryStableFrames: Int
    var relicFocusMark: LabyrinthRelicMark?
    val relicMarkStackCalibration: Map<LabyrinthRelicMark, Int>
    var shopChoiceImprintRolePending: Boolean
    var shopImprintRewardConfirmedAt: Long
    val shopPolicy: LabyrinthShopPolicy
    var shopRelicPurchasesThisCycle: Int
    fun anchorRect(result: LabyrinthEntryFrameResult, anchorId: String): EntryPixelRect?
    fun dispatchPostEntryTap(
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
        ): Unit
    fun publishOverlay(sessionId: AutomationSessionId): Unit
    fun traceWorkflow(owner: String, phase: String): Unit
}

/** 管理购买计划、商品核对、购买回执和刷新轮次。 */
internal class LabyrinthShopWorkflow(private val port: LabyrinthShopPort) : LabyrinthShopPort by port {
    fun clearPlannedShopPurchase() {
        plannedShopPurchaseRelicId = null
        plannedShopRoleImprintLabel = null
        plannedShopPurchaseStartedAt = Long.MIN_VALUE
        plannedShopPurchaseCounted = false
    }

    fun clearPlannedShopRefresh() {
        plannedShopRefreshStartedAt = Long.MIN_VALUE
        plannedShopRefreshConfirmedAt = Long.MIN_VALUE
    }

    fun currentShopDecision(result: LabyrinthEntryFrameResult): LabyrinthShopDecision? {
        val shop = result.shopObservation ?: return null
        if (shop.dialogState != LabyrinthShopDialogState.NONE) return null
        val refreshAnchorReady = result.anchorMatches[EntryAnchorId.SHOP_REFRESH_BUTTON]
            ?.score
            ?.let { it >= POST_ENTRY_ANCHOR_MIN_SCORE }
            ?: false
        return shopPolicy.decide(
            // Prefer the area of the node we actually tapped. routeProgress.currentArea can lag
            // behind while the post-entry shop page is already visible, which previously caused
            // an area-4 shop to fall through into relic recognition and stop on missing metadata.
            currentArea = activeNodeArea ?: _state.value.routeProgress?.currentArea,
            items = shop.items,
            acquired = _state.value.observedRelics,
            calibratedStacks = relicMarkStackCalibration,
            refreshAvailable = refreshAnchorReady && shop.refreshButtonEnabled,
            lockedFocus = relicFocusMark,
            relicPurchasesInCycle = shopRelicPurchasesThisCycle,
            refreshAvailabilityKnown = refreshAnchorReady,
        )
    }

    /** 把 1920x1080 参考坐标按当前帧尺寸换算成一个小点击框。 */
    /** The picker has been answered, or the run left the shop without one appearing. */
    fun clearShopChoiceImprintRolePending() {
        shopChoiceImprintRolePending = false
    }

    fun handleShopRefreshConfirmationFrame(
        sessionId: AutomationSessionId,
        result: LabyrinthEntryFrameResult,
        timestampMillis: Long,
    ): Boolean {
        val startedAt = plannedShopRefreshStartedAt
        if (startedAt == Long.MIN_VALUE) return false

        val elapsed = (timestampMillis - startedAt).coerceAtLeast(0L)
        if (elapsed > SHOP_REFRESH_TRANSITION_TIMEOUT_MILLIS) {
            clearPlannedShopRefresh()
            if (activeSessionId == sessionId) {
                _state.value = _state.value.copy(
                    message = "商店刷新确认超时；未重置本轮遗物计数，等待重新识别商店",
                )
                publishOverlay(sessionId)
            }
            return false
        }

        val confirmation = result.nodeMoveConfirmation
        if (
            labyrinthShopRefreshConfirmationOwnsFrame(
                activeNodeType = activeNodeType,
                refreshStartedAt = startedAt,
                refreshConfirmedAt = plannedShopRefreshConfirmedAt,
                now = timestampMillis,
                hasGenericConfirmation = confirmation != null,
                timeoutMillis = SHOP_REFRESH_TRANSITION_TIMEOUT_MILLIS,
            ) && confirmation != null
        ) {
            if (
                lastPostEntryActionAt == Long.MIN_VALUE ||
                timestampMillis - lastPostEntryActionAt >= POST_ENTRY_ACTION_INTERVAL_MILLIS
            ) {
                dispatchPostEntryTap(
                    sessionId = sessionId,
                    kind = LabyrinthPostEntryActionKind.SHOP_CONFIRM_REFRESH,
                    label = "确认刷新商店",
                    rect = confirmation.confirmButtonRect,
                    timestampMillis = timestampMillis,
                )
            } else if (activeSessionId == sessionId) {
                _state.value = _state.value.copy(message = "已识别更新确认，等待安全点击间隔")
                publishOverlay(sessionId)
            }
            return true.also { traceWorkflow("shop", "refresh-confirmation") }
        }

        if (plannedShopRefreshConfirmedAt != Long.MIN_VALUE) {
            if (
                confirmation == null &&
                labyrinthShopRefreshCanCommit(
                    pageState = result.observation.state,
                    refreshStartedAt = startedAt,
                    refreshConfirmedAt = plannedShopRefreshConfirmedAt,
                    now = timestampMillis,
                    settleMillis = SHOP_REFRESH_SETTLE_MILLIS,
                    timeoutMillis = SHOP_REFRESH_TRANSITION_TIMEOUT_MILLIS,
                )
            ) {
                shopRelicPurchasesThisCycle = 0
                clearPlannedShopRefresh()
                postEntryStableFrames = 0
                postEntryAttempts = 0
                if (activeSessionId == sessionId) {
                    _state.value = _state.value.copy(message = "商店已刷新，等待新商品列表稳定")
                    publishOverlay(sessionId)
                }
                return true.also { traceWorkflow("shop", "refresh-confirmation") }
            }
            if (activeSessionId == sessionId) {
                _state.value = _state.value.copy(message = "已确认刷新商店，等待新商品列表返回")
                publishOverlay(sessionId)
            }
            return true.also { traceWorkflow("shop", "refresh-confirmation") }
        }

        if (activeSessionId == sessionId) {
            _state.value = _state.value.copy(message = "已点击用300更新，等待更新确认弹窗")
            publishOverlay(sessionId)
        }
        return true.also { traceWorkflow("shop", "refresh-confirmation") }
    }

    fun planShopPage(result: LabyrinthEntryFrameResult, frameWidth: Int, frameHeight: Int, timestampMillis: Long): LabyrinthPageActionProposal {
        traceWorkflow("shop", "plan-page")
        var shopWaitReason: String? = null
        val plan = when (result.observation.state) {
            LabyrinthEntryPageState.SHOP -> when (val decision = currentShopDecision(result)) {
                is LabyrinthShopDecision.BuyRelic -> {
                    val relic = decision.relicDecision.choice
                    if (decision.relicDecision.phase != LabyrinthRelicChoicePhase.BUILD_BASELINE) {
                        relicFocusMark = decision.relicDecision.focusMark
                    }
                    plannedShopRoleImprintLabel = null
                    plannedShopPurchaseRelicId = relic.relicId
                    LabyrinthPostEntryTapPlan(
                        LabyrinthPostEntryActionKind.SHOP_BUY_RELIC,
                        "购买遗物：${relic.displayName ?: relic.relicId}；${decision.relicDecision.reason}",
                        decision.item.buyButtonRect,
                    )
                }

                is LabyrinthShopDecision.BuyRoleImprint -> {
                    plannedShopPurchaseRelicId = null
                    plannedShopRoleImprintLabel = decision.label
                    // A 选择印记 opens the roster picker once the purchase completes; a 随机印记
                    // does not. Remember which was bought so the picker is answered by the
                    // free-role selector rather than the opening-roster handler.
                    shopChoiceImprintRolePending = decision.opensRolePicker
                    LabyrinthPostEntryTapPlan(
                        LabyrinthPostEntryActionKind.SHOP_BUY_IMPRINT,
                        "购买印记：${decision.label}（${if (decision.opensRolePicker) "选择" else "随机"}）；${decision.reason}",
                        decision.item.buyButtonRect,
                    )
                }

                is LabyrinthShopDecision.Refresh -> {
                    clearPlannedShopPurchase()
                    val rect = anchorRect(result, EntryAnchorId.SHOP_REFRESH_BUTTON)
                    if (rect != null) {
                        LabyrinthPostEntryTapPlan(LabyrinthPostEntryActionKind.SHOP_REFRESH, "刷新商店：${decision.reason}", rect)
                    } else {
                        shopWaitReason = "策略允许刷新，但刷新按钮没有达到安全识别线"
                        null
                    }
                }

                is LabyrinthShopDecision.Close -> {
                    clearPlannedShopPurchase()
                    val rect = anchorRect(result, EntryAnchorId.SHOP_CLOSE)
                    if (rect != null) {
                        LabyrinthPostEntryTapPlan(LabyrinthPostEntryActionKind.SHOP_CLOSE, "关闭商店：${decision.reason}", rect)
                    } else {
                        shopWaitReason = "商店已无计划购买项，但关闭按钮未稳定识别"
                        null
                    }
                }

                is LabyrinthShopDecision.Wait -> {
                    shopWaitReason = decision.reason
                    null
                }

                null -> {
                    shopWaitReason = "商店商品识别尚未生成"
                    null
                }
            }

            LabyrinthEntryPageState.SHOP_PURCHASE_CONFIRMATION -> {
                val plannedId = plannedShopPurchaseRelicId
                val plannedImprint = plannedShopRoleImprintLabel
                val candidate = result.shopObservation?.purchaseCandidate
                when {
                    plannedId == null && plannedImprint == null -> {
                        shopWaitReason = "未检测到程序发起的购买，本次确认弹窗保持人工控制"
                        null
                    }
                    plannedShopPurchaseStartedAt == Long.MIN_VALUE ||
                        timestampMillis - plannedShopPurchaseStartedAt > SHOP_PURCHASE_TRANSITION_TIMEOUT_MILLIS -> {
                        clearPlannedShopPurchase()
                        shopWaitReason = "程序发起的购买确认已超时，本次弹窗保持人工控制"
                        null
                    }
                    plannedImprint != null -> {
                        // The originating shop-card title was already OCR-classified as a role
                        // imprint. The confirmation modal is transaction-bound and short-lived;
                        // require the normal confirmation button, but do not run relic icon
                        // identity against an item that is explicitly not a relic.
                        val confirmRect = anchorRect(result, EntryAnchorId.SHOP_EXIT_CONFIRM_BUTTON)
                        if (confirmRect != null) {
                            LabyrinthPostEntryTapPlan(
                                LabyrinthPostEntryActionKind.SHOP_CONFIRM_IMPRINT,
                                "确认购买印记：$plannedImprint",
                                confirmRect,
                            )
                        } else {
                            shopWaitReason = "印记购买确认已建立，但确认按钮未达到安全识别线"
                            null
                        }
                    }
                    candidate?.relicId == null -> {
                        shopWaitReason = "购买确认弹窗中的遗物尚未可靠识别，拒绝自动确认"
                        null
                    }
                    candidate.relicId != plannedId -> {
                        shopWaitReason = "购买确认遗物${candidate.relicId}与计划${plannedId}不一致，拒绝自动确认"
                        null
                    }
                    else -> {
                        val confirmRect = anchorRect(result, EntryAnchorId.SHOP_EXIT_CONFIRM_BUTTON)
                        if (confirmRect != null) {
                            LabyrinthPostEntryTapPlan(
                                LabyrinthPostEntryActionKind.SHOP_CONFIRM_RELIC,
                                "确认购买遗物：${candidate.displayName ?: candidate.relicId}",
                                confirmRect,
                            )
                        } else {
                            // Never fall back to the old hard-coded point: on the current modal
                            // it lands on the currency-change row above the confirm button.
                            shopWaitReason = "购买确认遗物已核对，但确认按钮未达到安全识别线"
                            null
                        }
                    }
                }
            }

            LabyrinthEntryPageState.SHOP_PURCHASE_COMPLETE ->
                if (plannedShopPurchaseRelicId != null || plannedShopRoleImprintLabel != null) {
                    // Purchase-complete is a single-button standard modal; dismissing it returns
                    // to SHOP, where all visible slots are recognized again. This is what makes
                    // the game-side "后面的商品补位" naturally enter the next decision cycle.
                    LabyrinthPostEntryTapPlan(
                        LabyrinthPostEntryActionKind.SHOP_CLOSE_PURCHASE_COMPLETE,
                        "关闭购买完成",
                        LabyrinthFallbackTap.SHOP_PURCHASE_COMPLETE_CLOSE.rect(frameWidth, frameHeight),
                    )
                } else {
                    shopWaitReason = "人工购买完成弹窗保持人工控制"
                    null
                }

            LabyrinthEntryPageState.SHOP_EXIT_CONFIRMATION ->
                LabyrinthPostEntryTapPlan(
                    LabyrinthPostEntryActionKind.SHOP_CONFIRM_EXIT,
                    "确认退出商店",
                    anchorRect(result, EntryAnchorId.SHOP_EXIT_CONFIRM_BUTTON)
                        ?: LabyrinthFallbackTap.SHOP_EXIT_CONFIRM.rect(frameWidth, frameHeight),
                )

            else -> null
        }
        return LabyrinthPageActionProposal(plan, shopWaitReason)
    }

    fun observeShopPurchase(result: LabyrinthEntryFrameResult): LabyrinthRelicMatch? {
        val shopDialogState = result.shopObservation?.dialogState
        val purchasedRelic = when (shopDialogState) {
            LabyrinthShopDialogState.PURCHASE_CONFIRMATION -> {
                // Only a transaction explicitly planned as a relic is eligible for relic
                // persistence. Role-imprint icons can resemble relic templates and must never be
                // written into the relic ledger after their purchase-complete modal.
                pendingShopPurchase = plannedShopPurchaseRelicId?.let { plannedId ->
                    result.shopObservation.purchaseCandidate?.takeIf { it.relicId == plannedId }
                }
                null
            }

            LabyrinthShopDialogState.PURCHASE_COMPLETE -> {
                pendingShopPurchase.also { pendingShopPurchase = null }
            }

            LabyrinthShopDialogState.NONE -> {
                if (lastShopDialogState == LabyrinthShopDialogState.PURCHASE_CONFIRMATION) {
                    // Returning directly to the shop means the confirmation was cancelled.
                    pendingShopPurchase = null
                    clearPlannedShopPurchase()
                }
                null
            }

            LabyrinthShopDialogState.EXIT_CONFIRMATION -> {
                pendingShopPurchase = null
                null
            }
            null -> {
                pendingShopPurchase = null
                null
            }
        }
        lastShopDialogState = shopDialogState ?: LabyrinthShopDialogState.NONE
        return purchasedRelic
    }

    fun onShopTapExecuted(kind: LabyrinthPostEntryActionKind) {
        when (kind) {
                        LabyrinthPostEntryActionKind.SHOP_CLOSE_PURCHASE_COMPLETE -> clearPlannedShopPurchase()
                        LabyrinthPostEntryActionKind.SHOP_REFRESH -> {
                            // The refresh button opens a second confirmation dialog.  Keep the
                            // completed-cycle count until that dialog is confirmed and fresh shop
                            // stock is visible again.
                            plannedShopRefreshStartedAt = clock()
                            plannedShopRefreshConfirmedAt = Long.MIN_VALUE
                            postEntryStableFrames = 0
                            postEntryAttempts = 0
                            clearPlannedShopPurchase()
                        }
                        LabyrinthPostEntryActionKind.SHOP_CONFIRM_REFRESH -> {
                            plannedShopRefreshConfirmedAt = clock()
                            postEntryStableFrames = 0
                            postEntryAttempts = 0
                        }
            else -> Unit
        }
    }

    fun onShopTapDispatched(shopPurchaseRelicId: String?, shopPurchaseRoleImprintLabel: String?, timestampMillis: Long): Unit {
        if (shopPurchaseRelicId != null || shopPurchaseRoleImprintLabel != null) {
            // This is semantic transaction context, not a page-local coordinate. It must survive
            // the brief UNKNOWN transition that can appear between the buy tap and confirmation.
            plannedShopPurchaseRelicId = shopPurchaseRelicId
            plannedShopRoleImprintLabel = shopPurchaseRoleImprintLabel
            plannedShopPurchaseStartedAt = timestampMillis
        }
    }

    fun onShopImprintTapExecuted(kind: LabyrinthPostEntryActionKind): Unit {
        if (kind == LabyrinthPostEntryActionKind.SHOP_CONFIRM_IMPRINT) {
            shopImprintRewardConfirmedAt = clock()
        }
    }

    fun onShopPurchasePageObserved(pageState: LabyrinthEntryPageState) {
        if (
            pageState == LabyrinthEntryPageState.SHOP_PURCHASE_COMPLETE &&
            plannedShopPurchaseRelicId != null &&
            !plannedShopPurchaseCounted
        ) {
            // The completed-purchase page is the visual commit point. Count it exactly once;
            // do not infer success merely because a tap was dispatched.
            shopRelicPurchasesThisCycle++
            plannedShopPurchaseCounted = true
        }

    }

    fun onShopPageChanged(pageState: LabyrinthEntryPageState) {
        if (labyrinthPageUiOwner(pageState) != LabyrinthPageUiOwner.SHOP) {
            pendingShopPurchase = null
            val keepPurchaseTransition = labyrinthKeepsPlannedShopPurchaseAcrossPage(
                pageState = pageState,
                plannedRelicId = plannedShopPurchaseRelicId ?: plannedShopRoleImprintLabel,
                startedAt = plannedShopPurchaseStartedAt,
                now = clock(),
                timeoutMillis = SHOP_PURCHASE_TRANSITION_TIMEOUT_MILLIS,
            )
            if (!keepPurchaseTransition) {
                clearPlannedShopPurchase()
                lastShopDialogState = LabyrinthShopDialogState.NONE
            }
        }    }

    fun prepareShopEntry(blockType: Int) {
        if (blockType == LabyrinthNodeTypes.SHOP) {
            // Reset only when entering a new shop node; purchase-animation page churn must not
            // erase this counter, but a recovered movement dialog represents a real new node.
            shopRelicPurchasesThisCycle = 0
            clearPlannedShopRefresh()
        }
    }

    fun resetShopOnStart() {
        pendingShopPurchase = null
        plannedShopPurchaseRelicId = null
        plannedShopRoleImprintLabel = null
        plannedShopPurchaseStartedAt = Long.MIN_VALUE
        plannedShopPurchaseCounted = false
        plannedShopRefreshStartedAt = Long.MIN_VALUE
        plannedShopRefreshConfirmedAt = Long.MIN_VALUE
        shopRelicPurchasesThisCycle = 0
        plannedShopPurchaseCounted = false
        shopRelicPurchasesThisCycle = 0
    }

    fun resetShopOnStop() {
        pendingShopPurchase = null
        plannedShopPurchaseRelicId = null
        plannedShopRoleImprintLabel = null
        plannedShopPurchaseStartedAt = Long.MIN_VALUE
        plannedShopRefreshStartedAt = Long.MIN_VALUE
        plannedShopRefreshConfirmedAt = Long.MIN_VALUE
        lastShopDialogState = LabyrinthShopDialogState.NONE
    }
}
