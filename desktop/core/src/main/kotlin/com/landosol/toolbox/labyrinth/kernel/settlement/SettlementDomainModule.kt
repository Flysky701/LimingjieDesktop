package com.landosol.toolbox.labyrinth.kernel.settlement

import com.landosol.toolbox.automation.AutomationActionResult
import com.landosol.toolbox.automation.GAME_NOT_FOREGROUND_REASON
import com.landosol.toolbox.labyrinth.LabyrinthFallbackTap
import com.landosol.toolbox.labyrinth.LabyrinthPostBossStage
import com.landosol.toolbox.labyrinth.LabyrinthPostEntryActionKind as ActionKind
import com.landosol.toolbox.labyrinth.LabyrinthPostEntryTapPlan
import com.landosol.toolbox.labyrinth.kernel.ActionDispatchedPayload
import com.landosol.toolbox.labyrinth.kernel.ActionReceiptPayload
import com.landosol.toolbox.labyrinth.kernel.KernelActionPermit
import com.landosol.toolbox.labyrinth.kernel.KernelDecision
import com.landosol.toolbox.labyrinth.kernel.KernelDomain
import com.landosol.toolbox.labyrinth.kernel.KernelDomainModule
import com.landosol.toolbox.labyrinth.kernel.KernelError
import com.landosol.toolbox.labyrinth.kernel.KernelErrorCategory
import com.landosol.toolbox.labyrinth.kernel.KernelEvent
import com.landosol.toolbox.labyrinth.kernel.KernelEventKinds as K
import com.landosol.toolbox.labyrinth.kernel.KernelEventPayload
import com.landosol.toolbox.labyrinth.kernel.KernelRuntime
import com.landosol.toolbox.labyrinth.kernel.KernelModuleStatus
import com.landosol.toolbox.labyrinth.kernel.PermitTransitionPayload
import com.landosol.toolbox.labyrinth.kernel.StateReducedPayload
import com.landosol.toolbox.labyrinth.labyrinthChestResultFallbackTap
import com.landosol.toolbox.labyrinth.labyrinthGenericConfirmDialogRect
import com.landosol.toolbox.labyrinth.labyrinthHasBattleControls
import com.landosol.toolbox.labyrinth.labyrinthPostBossStageOnPage
import com.landosol.toolbox.labyrinth.vision.EntryAnchorId
import com.landosol.toolbox.labyrinth.vision.EntryPixelRect
import com.landosol.toolbox.labyrinth.vision.LabyrinthEntryFrameResult
import com.landosol.toolbox.labyrinth.vision.LabyrinthEntryPageState as Page

internal data class SettlementObservation(
    val stage: LabyrinthPostBossStage,
    val decision: KernelDecision,
    val event: KernelEvent? = null,
)

internal data class SettlementPlan(
    val decision: KernelDecision,
    val tapPlan: LabyrinthPostEntryTapPlan? = null,
    val event: KernelEvent? = null,
    val permit: KernelActionPermit.Token? = null,
)

internal class SettlementDomainModule(private val runtime: KernelRuntime) : KernelDomainModule {
    override val id: String = "settlement"
    override val domain: KernelDomain = KernelDomain.SETTLEMENT

    override fun status(): KernelModuleStatus = synchronized(runtime) {
        if (stage == LabyrinthPostBossStage.NONE) super.status()
        else KernelModuleStatus(id, domain, true, stage.name, lastPage?.name.orEmpty())
    }

    var stage: LabyrinthPostBossStage = LabyrinthPostBossStage.NONE
        private set
    var startedAt: Long = Long.MIN_VALUE
        private set
    var unknownAttempts: Int = 0
        private set
    var chestObserved: Boolean = false
        private set

    private data class SettlementActionKey(
        val page: Page,
        val stage: LabyrinthPostBossStage,
        val kind: ActionKind,
        val rect: EntryPixelRect,
    )

    private data class SettlementDispatch(
        val event: KernelEvent,
        val stage: LabyrinthPostBossStage,
        val kind: ActionKind,
        val timestampMillis: Long,
    )

    private var lastPage: Page? = null
    private var observedAt = Long.MIN_VALUE
    private var stableFrames = 0
    private var lastEvent: KernelEvent? = null
    private var proposal: SettlementPlan? = null
    private var pending: SettlementDispatch? = null
    private val actionPermit = KernelActionPermit<SettlementActionKey> { issued, key, revision, reason ->
        emit(
            if (issued) K.PERMIT_ISSUED else K.PERMIT_REVOKED,
            PermitTransitionPayload(key?.kind?.name, revision, reason),
            reason,
        )
    }

    override fun onKernelEvent(event: KernelEvent, ctx: KernelRuntime) = Unit

    override fun reset(traceId: String) = synchronized(runtime) {
        revoke("session_reset")
        stage = LabyrinthPostBossStage.NONE
        startedAt = Long.MIN_VALUE
        unknownAttempts = 0
        chestObserved = false
        lastPage = null
        observedAt = Long.MIN_VALUE
        stableFrames = 0
        lastEvent = null
        pending = null
    }

    internal fun restoreProgress(
        stage: LabyrinthPostBossStage,
        startedAt: Long,
        unknownAttempts: Int,
        chestObserved: Boolean = false,
    ) = synchronized(runtime) {
        this.stage = stage
        this.startedAt = startedAt
        this.unknownAttempts = unknownAttempts
        this.chestObserved = chestObserved
    }

    /** Call when route completion is confirmed, even if the score page is still loading. */
    fun beginFinalSettlement(timestampMillis: Long, parentEvent: KernelEvent? = null): KernelEvent? =
        synchronized(runtime) {
            if (stage != LabyrinthPostBossStage.NONE) return@synchronized null
            startedAt = timestampMillis
            changeStage(LabyrinthPostBossStage.BEFORE_SCORE, lastPage, "final_settlement_started", parentEvent ?: lastEvent)
        }

    fun onPageObserved(
        page: Page,
        postEntryStableFrames: Int,
        timestampMillis: Long,
        parentEvent: KernelEvent? = null,
    ): SettlementObservation = synchronized(runtime) {
        if (parentEvent != null) lastEvent = parentEvent
        if (lastPage != page || postEntryStableFrames < POST_ENTRY_STABLE_FRAMES) {
            revoke("settlement_page_changed")
        }
        lastPage = page
        observedAt = timestampMillis
        stableFrames = postEntryStableFrames
        if ((stage == LabyrinthPostBossStage.SCORE_RESULT || stage == LabyrinthPostBossStage.CHEST_SEQUENCE) &&
            (page == Page.RUN_CLEAR_CHEST_ANIMATION || page == Page.RUN_CLEAR_CHEST_RESULT)
        ) {
            chestObserved = true
        }
        val candidate = labyrinthPostBossStageOnPage(stage, page, chestObserved)
        val next = if (stage == LabyrinthPostBossStage.CHEST_SEQUENCE &&
            candidate == LabyrinthPostBossStage.SCORE_RESULT && postEntryStableFrames < POST_ENTRY_STABLE_FRAMES
        ) stage else candidate
        var transition: KernelEvent? = null
        if (next != stage) {
            if (next == LabyrinthPostBossStage.SCORE_RESULT) {
                startedAt = timestampMillis
                unknownAttempts = 0
            }
            transition = changeStage(next, page, "settlement_page_observed")
        }
        if (stage == LabyrinthPostBossStage.WAITING_FOR_DAWN_HOME) {
            if (page == Page.DAWN_REALM_HOME_IDLE) {
                if (postEntryStableFrames < POST_ENTRY_STABLE_FRAMES) {
                    return@synchronized SettlementObservation(stage, KernelDecision.Wait("等待黎明界主页稳定"))
                }
                val reason = "已完成最终结算并返回黎明界主页"
                val event = emit(K.STATE_REDUCED, stageState(page), "run_cleared", reason)
                return@synchronized SettlementObservation(stage, KernelDecision.FinishRun(cleared = true, reason = reason), event)
            }
            if (startedAt != Long.MIN_VALUE && timestampMillis - startedAt > FINAL_HOME_RETURN_TIMEOUT_MILLIS) {
                val reason = "最终道具已关闭，但未在限定时间内返回黎明界主页"
                val decision = evidence("final_home_return_timeout", KernelErrorCategory.TIMEOUT, reason)
                val event = emit(K.STATE_REDUCED, stageState(page), decision.error.code, reason)
                return@synchronized SettlementObservation(stage, decision, event)
            }
        }
        SettlementObservation(stage, KernelDecision.Wait("等待结算画面推进"), transition)
    }

    /** Call after [onPageObserved] for the same frame, once the caller's action cooldown has passed. */
    fun planTap(
        result: LabyrinthEntryFrameResult,
        page: Page,
        frameWidth: Int,
        frameHeight: Int,
        postEntryAttempts: Int,
        maxAttempts: Int,
        bossSettlementNextRect: EntryPixelRect?,
    ): SettlementPlan = synchronized(runtime) {
        if (page != lastPage || page != result.observation.state || observedAt < 0 ||
            stableFrames < POST_ENTRY_STABLE_FRAMES || pending != null
        ) {
            return@synchronized waitForTap("等待当前结算页面及动作状态确认")
        }
        val ownsAnimation = stage == LabyrinthPostBossStage.BEFORE_SCORE ||
            stage == LabyrinthPostBossStage.CHEST_SEQUENCE
        val target = when (page) {
            Page.RUN_CLEAR_RESULT -> ActionKind.CLOSE_FINAL_SCORE.takeIf { stage == LabyrinthPostBossStage.SCORE_RESULT }
            Page.ITEM_REWARD -> ActionKind.CLOSE_FINAL_ITEM.takeIf { stage == LabyrinthPostBossStage.FINAL_ITEM_REWARD }
            Page.UNKNOWN -> when {
                stage == LabyrinthPostBossStage.WAITING_FOR_DAWN_HOME || labyrinthHasBattleControls(result) ||
                    labyrinthGenericConfirmDialogRect(result) != null || result.nodeMoveConfirmation != null ||
                    result.battleEndConfirmation != null || result.specialEquipment?.stage != null -> null
                bossSettlementNextRect != null -> ActionKind.BOSS_SETTLEMENT_NEXT
                ownsAnimation -> ActionKind.ADVANCE_FINAL_SETTLEMENT
                else -> null
            }
            in ANIMATION_PAGES -> if (ownsAnimation) ActionKind.ADVANCE_FINAL_SETTLEMENT else null
            else -> null
        } ?: return@synchronized waitForTap("当前页面没有可执行的结算点击")
        if (postEntryAttempts >= maxAttempts) {
            val reason = if (target == ActionKind.ADVANCE_FINAL_SETTLEMENT) {
                "最终结算动画推进达到上限，未找到下一结算页面"
            } else {
                "${page.name} 页面重复点击达到上限，已暂停等待人工检查"
            }
            val decision = evidence("settlement_attempt_limit", KernelErrorCategory.EVIDENCE, reason)
            val event = emit(K.STATE_REDUCED, stageState(page), decision.error.code, reason)
            revoke(decision.error.code)
            return@synchronized SettlementPlan(decision, event = event)
        }
        if (frameWidth < 3 || frameHeight < 3) return@synchronized waitForTap("结算画面尺寸无效")
        val tap = when (target) {
            ActionKind.CLOSE_FINAL_SCORE -> LabyrinthPostEntryTapPlan(
                target, "关闭最终分数结果",
                anchorOrFallback(
                    result, EntryAnchorId.RUN_RESULT_CLOSE_BUTTON, LabyrinthFallbackTap.BOTTOM_CENTER, frameWidth, frameHeight,
                ),
            )
            ActionKind.CLOSE_FINAL_ITEM -> LabyrinthPostEntryTapPlan(
                target, "关闭最终获得道具",
                anchorOrFallback(
                    result, EntryAnchorId.ITEM_REWARD_CLOSE, LabyrinthFallbackTap.MODAL_CLOSE, frameWidth, frameHeight,
                ),
            )
            ActionKind.BOSS_SETTLEMENT_NEXT -> LabyrinthPostEntryTapPlan(
                target, "Boss结算：下一步", requireNotNull(bossSettlementNextRect),
            )
            ActionKind.ADVANCE_FINAL_SETTLEMENT -> LabyrinthPostEntryTapPlan(
                target, "推进最终结算动画", animationRect(result, page, frameWidth, frameHeight),
            )
            else -> error("Unexpected settlement action: $target")
        }
        val rect = tap.rect
        if (rect.left.toLong() + rect.width > frameWidth || rect.top.toLong() + rect.height > frameHeight) {
            return@synchronized waitForTap("结算按钮坐标越界，等待重新识别")
        }
        val decision = KernelDecision.Act(tap.kind.name, tap.label, rect = rect)
        emit(K.STATE_REDUCED, stageState(page), "settlement_tap_planned", tap.label)
        val token = actionPermit.issue(SettlementActionKey(page, stage, tap.kind, rect), observedAt, "settlement_tap_planned")
        SettlementPlan(decision, tap, lastEvent, token).also { proposal = it }
    }

    /** Consume the current frame's authorization when the caller accepts the dispatch. */
    fun recordTapDispatched(
        plan: SettlementPlan, timestampMillis: Long, keepPermitUntilReceipt: Boolean = false,
    ): KernelEvent? = synchronized(runtime) {
        if (plan !== proposal || pending != null) return@synchronized null
        val tap = plan.tapPlan ?: return@synchronized null
        val token = plan.permit ?: return@synchronized null
        if (!token.isCurrent(timestampMillis)) {
            revoke("settlement_permit_expired")
            return@synchronized null
        }
        if (tap.kind == ActionKind.ADVANCE_FINAL_SETTLEMENT) unknownAttempts++
        val event = emit(K.ACTION_DISPATCHED, ActionDispatchedPayload(tap.kind.name), tap.kind.name, cause = plan.event)
        pending = SettlementDispatch(event, stage, tap.kind, timestampMillis)
        if (keepPermitUntilReceipt) proposal = null else revoke("settlement_tap_dispatched")
        event
    }

    fun recordTapReceipt(
        dispatch: KernelEvent,
        result: AutomationActionResult,
        suppressed: Boolean = false,
    ): KernelEvent? = synchronized(runtime) {
        val action = pending?.takeIf { it.event == dispatch && dispatch.traceId == runtime.traceId }
            ?: return@synchronized null
        pending = null
        revoke("settlement_tap_receipt")
        val verdict = if (suppressed) "SUPPRESSED" else result.javaClass.simpleName
        val event = emit(K.ACTION_RECEIPT_OBSERVED, ActionReceiptPayload(action.kind.name, verdict), verdict, cause = dispatch)
        if (stage != action.stage) return@synchronized event
        val rejected = result as? AutomationActionResult.Rejected
        when {
            suppressed || rejected?.reason == GAME_NOT_FOREGROUND_REASON -> {
                if (action.kind == ActionKind.ADVANCE_FINAL_SETTLEMENT) unknownAttempts--
            }
            result is AutomationActionResult.Executed -> when (action.kind) {
                ActionKind.CLOSE_FINAL_SCORE -> {
                    unknownAttempts = 0
                    changeStage(LabyrinthPostBossStage.CHEST_SEQUENCE, lastPage, "final_score_closed")
                }
                ActionKind.CLOSE_FINAL_ITEM -> {
                    startedAt = action.timestampMillis
                    changeStage(LabyrinthPostBossStage.WAITING_FOR_DAWN_HOME, lastPage, "final_item_closed")
                }
                else -> Unit
            }
        }
        event
    }

    private fun animationRect(result: LabyrinthEntryFrameResult, page: Page, width: Int, height: Int): EntryPixelRect =
        when (page) {
            Page.RUN_CLEAR_CHARACTER_SUMMARY -> anchorOrFallback(
                result, EntryAnchorId.RUN_CLEAR_NEXT_BUTTON, LabyrinthFallbackTap.BOTTOM_RIGHT, width, height,
            )
            Page.RUN_CLEAR_CHEST_RESULT -> anchorOrFallback(
                result, EntryAnchorId.RUN_CLEAR_CHEST_CONFIRM_BUTTON, labyrinthChestResultFallbackTap(), width, height,
            )
            Page.RUN_CLEAR_REWARD_ANIMATION -> LabyrinthFallbackTap.BOTTOM_CENTER.rect(width, height)
            Page.UNKNOWN -> {
                val bottomRight = if (stage == LabyrinthPostBossStage.CHEST_SEQUENCE) {
                    unknownAttempts >= 3
                } else {
                    unknownAttempts % 2 == 1
                }
                val fallback = if (bottomRight) LabyrinthFallbackTap.BOTTOM_RIGHT else LabyrinthFallbackTap.CENTER
                fallback.rect(width, height)
            }
            else -> LabyrinthFallbackTap.CENTER.rect(width, height)
        }

    private fun anchorOrFallback(
        result: LabyrinthEntryFrameResult,
        anchorId: String,
        fallback: LabyrinthFallbackTap,
        width: Int,
        height: Int,
    ): EntryPixelRect = result.anchorMatches[anchorId]?.takeIf { it.score >= POST_ENTRY_ANCHOR_MIN_SCORE }?.rect
        ?: fallback.rect(width, height)

    private fun changeStage(
        next: LabyrinthPostBossStage,
        page: Page?,
        code: String,
        cause: KernelEvent? = lastEvent,
    ): KernelEvent {
        val previous = stage
        stage = next
        val event = emit(K.STATE_REDUCED, stageState(page, previous), code, cause = cause)
        revoke(code)
        return event
    }

    private fun stageState(page: Page?, previous: LabyrinthPostBossStage = stage) = StateReducedPayload(
        pageState = page?.name ?: Page.UNKNOWN.name,
        detail = mapOf("fromStage" to previous.name, "stage" to stage.name),
    )

    private fun evidence(code: String, category: KernelErrorCategory, reason: String) =
        KernelDecision.NeedEvidence(KernelError(code, category, id), reason)

    private fun waitForTap(reason: String): SettlementPlan {
        revoke("settlement_tap_wait")
        return SettlementPlan(KernelDecision.Wait(reason))
    }

    internal fun invalidateTap(reason: String) = synchronized(runtime) { revoke(reason) }

    private fun revoke(reason: String) {
        proposal = null
        actionPermit.revoke(reason)
    }

    private fun emit(
        kind: String,
        payload: KernelEventPayload,
        code: String,
        summary: String = code,
        cause: KernelEvent? = lastEvent,
    ): KernelEvent {
        val parent = cause ?: runtime.log.filter(
            domain = KernelDomain.RUNTIME, kind = K.SESSION_LIFECYCLE, traceId = runtime.traceId,
        ).lastOrNull()
        return runtime.emit(domain, kind, payload, parent, code, summary).also { lastEvent = it }
    }

    private companion object {
        const val FINAL_HOME_RETURN_TIMEOUT_MILLIS = 20_000L
        const val POST_ENTRY_STABLE_FRAMES = 2
        const val POST_ENTRY_ANCHOR_MIN_SCORE = 0.45
        val ANIMATION_PAGES = setOf(
            Page.RUN_CLEAR_CONGRATULATIONS,
            Page.RUN_CLEAR_CHARACTER_SUMMARY,
            Page.RUN_CLEAR_REWARD_ANIMATION,
            Page.RUN_CLEAR_CHEST_ANIMATION,
            Page.RUN_CLEAR_CHEST_RESULT,
        )
    }
}
