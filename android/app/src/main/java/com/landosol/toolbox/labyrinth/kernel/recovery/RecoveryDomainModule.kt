package com.landosol.toolbox.labyrinth.kernel.recovery

import com.landosol.toolbox.automation.AutomationActionResult
import com.landosol.toolbox.automation.accessibility.GAME_NOT_FOREGROUND_REASON
import com.landosol.toolbox.automation.session.SessionBlockClassifier
import com.landosol.toolbox.automation.session.SessionBlockKind
import com.landosol.toolbox.labyrinth.kernel.ActionDispatchedPayload
import com.landosol.toolbox.labyrinth.kernel.ActionReceiptPayload
import com.landosol.toolbox.labyrinth.kernel.CustomPayload
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
import com.landosol.toolbox.labyrinth.kernel.PermitTransitionPayload
import com.landosol.toolbox.labyrinth.kernel.StateReducedPayload
import com.landosol.toolbox.labyrinth.labyrinthActionLatchStuck
import com.landosol.toolbox.labyrinth.labyrinthClearsPostEntryUnknownTimer
import com.landosol.toolbox.labyrinth.labyrinthSessionBlockObservation
import com.landosol.toolbox.labyrinth.labyrinthSessionReturnTitleStreak
import com.landosol.toolbox.labyrinth.labyrinthShopTransitionSuppressesSessionBlock
import com.landosol.toolbox.labyrinth.vision.EntryPixelRect
import com.landosol.toolbox.labyrinth.vision.LabyrinthEntryFrameResult
import com.landosol.toolbox.labyrinth.vision.LabyrinthEntryPageState

data class RecoveryEvaluation(
    val blocked: Boolean,
    val decision: KernelDecision,
    val tapRect: EntryPixelRect?,
    val actionLabel: String?,
    val kind: SessionBlockKind,
    val event: KernelEvent? = null,
    val permit: KernelActionPermit.Token? = null,
)

class RecoveryDomainModule(private val runtime: KernelRuntime) : KernelDomainModule {
    override val id: String = "recovery"
    override val domain: KernelDomain = KernelDomain.RECOVERY

    var lastBlockKind: SessionBlockKind = SessionBlockKind.NONE
        private set
    var blockStableFrames: Int = 0
        private set
    var returnTitleStableFrames: Int = 0
        private set
    var returnTitleAttempts: Int = 0
        private set
    var lastReturnTitleActionAt: Long = Long.MIN_VALUE
        private set

    private data class RecoveryActionKey(
        val kind: SessionBlockKind,
        val label: String,
        val rect: EntryPixelRect,
    )

    private data class ReturnTitleDispatch(val event: KernelEvent, var counted: Boolean = true)

    private val classifier = SessionBlockClassifier()
    private var lastEvent: KernelEvent? = null
    private var proposal: RecoveryEvaluation? = null
    private var pending: ReturnTitleDispatch? = null
    internal var postEntryUnknownSince = Long.MIN_VALUE
    private val actionPermit = KernelActionPermit<RecoveryActionKey> { issued, _, revision, reason ->
        emit(
            if (issued) K.PERMIT_ISSUED else K.PERMIT_REVOKED,
            PermitTransitionPayload(RETURN_TITLE, revision, reason),
            reason,
        )
    }

    override fun onKernelEvent(event: KernelEvent, ctx: KernelRuntime) = Unit

    override fun reset(traceId: String) = synchronized(runtime) {
        clearTracking("session_reset")
        pending = null
        postEntryUnknownSince = Long.MIN_VALUE
        lastEvent = null
    }

    internal fun restoreTracking(
        kind: SessionBlockKind, blockFrames: Int, titleFrames: Int, attempts: Int, lastActionAt: Long,
    ) = synchronized(runtime) {
        lastBlockKind = kind
        blockStableFrames = blockFrames
        returnTitleStableFrames = titleFrames
        returnTitleAttempts = attempts
        lastReturnTitleActionAt = lastActionAt
    }

    fun resetTracking(): Unit = synchronized(runtime) {
        val wasBlocked = lastBlockKind != SessionBlockKind.NONE
        clearTracking("session_block_reset")
        if (wasBlocked) {
            emit(K.STATE_REDUCED, blockState(), "session_block_reset")
        }
    }

    fun evaluateSessionBlock(
        result: LabyrinthEntryFrameResult,
        lastObservedPage: LabyrinthEntryPageState?,
        activeNodeType: Int?,
        lastPostEntryActionAt: Long,
        frameWidth: Int,
        frameHeight: Int,
        timestampMillis: Long,
        dryRun: Boolean,
        channelPromptActive: Boolean,
        cooldownMillis: Long = SESSION_RETURN_TITLE_ACTION_INTERVAL_MILLIS,
        actionInFlight: Boolean = false,
        parentEvent: KernelEvent? = null,
    ): RecoveryEvaluation = synchronized(runtime) {
        val page = result.observation.state
        val cause = parentEvent ?: lastEvent
        if (channelPromptActive) {
            val reason = "渠道弹窗接管当前画面，暂停普通点击"
            val event = emit(K.STATE_REDUCED, blockState(page), "channel_prompt", reason, cause)
            revoke("channel_prompt")
            return@synchronized RecoveryEvaluation(true, KernelDecision.Wait(reason), null, null, lastBlockKind, event)
        }
        val shopTransition = labyrinthShopTransitionSuppressesSessionBlock(
            pageState = page,
            previousPageState = lastObservedPage,
            activeNodeType = activeNodeType,
            lastActionAt = lastPostEntryActionAt,
            now = timestampMillis,
        )
        if (shopTransition) {
            return@synchronized unblocked(page, "shop_transition", "商店过渡中，忽略会话阻断误报", cause)
        }
        val block = labyrinthSessionBlockObservation(result, classifier)
        if (!block.blocksNormalActions) {
            return@synchronized unblocked(page, "session_block_cleared", "未检测到会话阻断", cause)
        }
        if (lastBlockKind != block.kind) {
            clearTracking("session_block_changed")
            lastBlockKind = block.kind
        }
        blockStableFrames++
        val rect = block.returnTitleRect
        returnTitleStableFrames = labyrinthSessionReturnTitleStreak(returnTitleStableFrames, rect != null)
        val label = block.actionLabel
        val decision: KernelDecision = when {
            dryRun -> KernelDecision.Wait("检测到账号会话失效；只读模式不会点击$label")
            block.kind == SessionBlockKind.UNKNOWN_PROMPT -> KernelDecision.Wait("检测到未知会话弹窗，已暂停普通点击")
            rect == null -> KernelDecision.Wait("检测到账号会话失效，等待识别“${label}”按钮")
            returnTitleStableFrames < SESSION_BLOCK_STABLE_FRAMES ->
                KernelDecision.Wait("检测到账号会话失效，正在确认“${label}”按钮")
            returnTitleAttempts >= MAX_SESSION_RETURN_TITLE_ATTEMPTS -> {
                val reason = "${label}多次无响应，请手动点击；自动会话仍保持运行"
                KernelDecision.NeedEvidence(KernelError("return_title_attempt_limit", KernelErrorCategory.EVIDENCE, id), reason)
            }
            rect.left.toLong() + rect.width > frameWidth || rect.top.toLong() + rect.height > frameHeight ->
                KernelDecision.Wait("${label}按钮坐标越界，等待重新识别")
            actionInFlight || pending != null -> KernelDecision.Wait("等待当前动作结束")
            lastReturnTitleActionAt != Long.MIN_VALUE && timestampMillis - lastReturnTitleActionAt < cooldownMillis ->
                KernelDecision.Wait("等待“${label}”点击冷却")
            timestampMillis < 0 -> KernelDecision.Wait("等待有效帧时间")
            else -> KernelDecision.Act(RETURN_TITLE, label, rect = rect, reason = "会话弹窗抢占普通点击")
        }
        val reasonCode: String
        val summary: String
        when (decision) {
            is KernelDecision.Wait -> {
                reasonCode = "session_block_wait"
                summary = decision.reason
            }
            is KernelDecision.NeedEvidence -> {
                reasonCode = decision.error.code
                summary = decision.reason
            }
            is KernelDecision.Act -> {
                reasonCode = "session_block_preempt"
                summary = decision.reason
            }
            else -> error("Unexpected recovery decision")
        }
        val event = emit(K.STATE_REDUCED, blockState(page), reasonCode, summary, cause)
        val target = (decision as? KernelDecision.Act)?.rect
        if (target == null) {
            revoke(reasonCode)
            return@synchronized RecoveryEvaluation(true, decision, null, label, block.kind, event)
        }
        val token = actionPermit.issue(RecoveryActionKey(block.kind, label, target), timestampMillis, reasonCode)
        RecoveryEvaluation(true, decision, target, label, block.kind, lastEvent, token).also { proposal = it }
    }

    /** Consume the current frame's authorization when the caller accepts the dispatch. */
    fun recordReturnTitleDispatched(
        evaluation: RecoveryEvaluation, timestampMillis: Long, keepPermitUntilReceipt: Boolean = false,
    ): KernelEvent? =
        synchronized(runtime) {
            if (evaluation !== proposal || pending != null) return@synchronized null
            val token = evaluation.permit ?: return@synchronized null
            if (!token.isCurrent(timestampMillis)) {
                revoke("return_title_permit_expired")
                return@synchronized null
            }
            returnTitleAttempts++
            lastReturnTitleActionAt = timestampMillis
            val event = emit(K.ACTION_DISPATCHED, ActionDispatchedPayload(RETURN_TITLE), RETURN_TITLE, cause = evaluation.event)
            pending = ReturnTitleDispatch(event)
            if (keepPermitUntilReceipt) proposal = null else revoke("return_title_dispatched")
            event
        }

    fun recordReturnTitleReceipt(
        dispatch: KernelEvent,
        result: AutomationActionResult,
        suppressed: Boolean = false,
    ): KernelEvent? = synchronized(runtime) {
        val action = pending?.takeIf { it.event == dispatch && dispatch.traceId == runtime.traceId }
            ?: return@synchronized null
        val rejected = result as? AutomationActionResult.Rejected
        if (action.counted && (suppressed || rejected?.reason == GAME_NOT_FOREGROUND_REASON)) {
            returnTitleAttempts--
            lastReturnTitleActionAt = Long.MIN_VALUE
        }
        pending = null
        revoke("return_title_receipt")
        val verdict = if (suppressed) "SUPPRESSED" else result.javaClass.simpleName
        emit(K.ACTION_RECEIPT_OBSERVED, ActionReceiptPayload(RETURN_TITLE, verdict), verdict, cause = dispatch)
    }

    fun evaluateActionLatch(
        actionInFlight: Boolean,
        actionInFlightSince: Long,
        timestampMillis: Long,
        parentEvent: KernelEvent? = null,
    ): KernelDecision.NeedEvidence? = synchronized(runtime) {
        if (!actionInFlight || !labyrinthActionLatchStuck(actionInFlightSince, timestampMillis)) {
            return@synchronized null
        }
        val heldFor = timestampMillis - actionInFlightSince
        val reason = "动作已等待${heldFor / 1000}秒仍未返回（手势可能未落地），需要释放动作锁并保留诊断状态"
        timeout("action_latch_stuck", reason, heldFor, parentEvent)
    }

    fun evaluatePostEntryUnknownTimeout(
        result: LabyrinthEntryFrameResult,
        timestampMillis: Long,
        characterAcquisitionActive: Boolean,
        postBossActive: Boolean,
        parentEvent: KernelEvent? = null,
    ): KernelDecision.NeedEvidence? = synchronized(runtime) {
        if (labyrinthClearsPostEntryUnknownTimer(result)) {
            resetPostEntryUnknownTimer()
            return@synchronized null
        }
        if (postEntryUnknownSince == Long.MIN_VALUE) postEntryUnknownSince = timestampMillis
        val elapsed = timestampMillis - postEntryUnknownSince
        if (characterAcquisitionActive || postBossActive || elapsed <= UNKNOWN_POST_ENTRY_TIMEOUT_MILLIS) {
            return@synchronized null
        }
        timeout("post_entry_unknown_timeout", "路线阶段未知页面超过限定时间，已停止并保留诊断状态", elapsed, parentEvent)
    }

    fun resetPostEntryUnknownTimer() = synchronized(runtime) { postEntryUnknownSince = Long.MIN_VALUE }

    private fun unblocked(
        page: LabyrinthEntryPageState,
        code: String,
        reason: String,
        cause: KernelEvent?,
    ): RecoveryEvaluation {
        val wasBlocked = lastBlockKind != SessionBlockKind.NONE
        clearTracking(code)
        val event = if (wasBlocked || code == "shop_transition") {
            emit(K.STATE_REDUCED, blockState(page), code, reason, cause)
        } else null
        return RecoveryEvaluation(false, KernelDecision.Wait(reason), null, null, SessionBlockKind.NONE, event)
    }

    private fun clearTracking(reason: String) {
        revoke(reason)
        pending?.counted = false
        lastBlockKind = SessionBlockKind.NONE
        blockStableFrames = 0
        returnTitleStableFrames = 0
        returnTitleAttempts = 0
        lastReturnTitleActionAt = Long.MIN_VALUE
    }

    internal fun invalidateReturnTitle(reason: String) = synchronized(runtime) { revoke(reason) }

    private fun revoke(reason: String) {
        proposal = null
        actionPermit.revoke(reason)
    }

    private fun blockState(page: LabyrinthEntryPageState? = null) = StateReducedPayload(
        pageState = page?.name ?: lastBlockKind.name,
        detail = mapOf(
            "blockKind" to lastBlockKind.name,
            "blockStableFrames" to blockStableFrames.toString(),
            "returnTitleStableFrames" to returnTitleStableFrames.toString(),
            "returnTitleAttempts" to returnTitleAttempts.toString(),
        ),
    )

    private fun timeout(
        code: String,
        reason: String,
        elapsed: Long,
        parentEvent: KernelEvent?,
    ): KernelDecision.NeedEvidence {
        val decision = KernelDecision.NeedEvidence(KernelError(code, KernelErrorCategory.TIMEOUT, id), reason)
        emit(K.STATE_REDUCED, CustomPayload(mapOf("elapsedMillis" to elapsed.toString())), code, reason, parentEvent ?: lastEvent)
        revoke(code)
        return decision
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
        const val RETURN_TITLE = "RETURN_TITLE"
        const val SESSION_BLOCK_STABLE_FRAMES = 2
        const val SESSION_RETURN_TITLE_ACTION_INTERVAL_MILLIS = 1_500L
        const val MAX_SESSION_RETURN_TITLE_ATTEMPTS = 6
        const val UNKNOWN_POST_ENTRY_TIMEOUT_MILLIS = 30_000L
    }
}
