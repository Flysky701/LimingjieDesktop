package com.landosol.toolbox.labyrinth.kernel

import com.landosol.toolbox.labyrinth.kernel.KernelEventKinds as K

/** Authorizes a domain decision made with frame evidence owned by the host. */
internal class KernelFrameAction(private val runtime: KernelRuntime, private val domain: KernelDomain) {
    private data class Key(val frameEventSeq: Long, val action: KernelDecision.Act)
    private val permits = KernelActionPermit<Key>()
    private var permitEvent: KernelEvent? = null

    companion object {
        const val HOST_FRAME = "host_frame_observed"
    }

    class Authorization internal constructor(
        private val runtime: KernelRuntime,
        private val domain: KernelDomain,
        private val action: String,
        val permit: KernelActionPermit.Token,
        private val cause: KernelEvent,
        private val release: (KernelEvent) -> Unit,
    ) {
        private var dispatch: KernelEvent? = null
        private var completed = false

        fun dispatch(): Unit = synchronized(runtime) {
            if (completed || dispatch != null || cause.traceId != runtime.traceId) return
            dispatch = runtime.emit(domain, K.ACTION_DISPATCHED, ActionDispatchedPayload(action), cause)
        }

        fun receipt(verdict: String): KernelEvent? = synchronized(runtime) {
            if (completed || cause.traceId != runtime.traceId) return null
            completed = true
            runtime.emit(domain, K.ACTION_RECEIPT_OBSERVED, ActionReceiptPayload(action, verdict),
                dispatch ?: cause, verdict).also(release)
        }
    }

    fun authorize(
        page: String,
        capturedAt: Long,
        checkedAt: Long,
        parent: KernelEvent?,
        action: KernelDecision.Act,
        state: Map<String, String>,
    ): Authorization? = synchronized(runtime) {
        revoke("action_replaced")
        val frame = runtime.emit(domain, K.FRAME_OBSERVED, FrameObservedPayload(page, capturedAt), parent, HOST_FRAME)
        val reduced = runtime.emit(domain, K.STATE_REDUCED, StateReducedPayload(page, detail = state), frame)
        val decision = runtime.emit(domain, K.EVENT_DECISION_PROPOSED,
            DecisionProposedPayload(domain.name, action.targetId ?: action.actionKind, true, action.reason),
            reduced, action.actionKind, action.label)
        val intervention = runtime.latestIntervention?.takeIf { it.eventSeq == decision.eventSeq }
        if (intervention?.verdict is KernelHookVerdict.Veto) return null
        val token = permits.issue(Key(frame.eventSeq, action), checkedAt, action.actionKind)
        val issued = runtime.emit(domain, K.PERMIT_ISSUED,
            PermitTransitionPayload(action.targetId ?: action.actionKind, permits.currentRevision, action.actionKind),
            intervention?.cause ?: decision)
        permitEvent = issued
        Authorization(runtime, domain, action.actionKind, token, issued) {
            if (permitEvent === issued) revoke("action_receipt")
        }
    }

    fun revoke(reason: String): Unit = synchronized(runtime) {
        val parent = permitEvent ?: return
        val target = (parent.payload as PermitTransitionPayload).choiceId
        permitEvent = null
        permits.revoke(reason)
        runtime.emit(domain, K.PERMIT_REVOKED, PermitTransitionPayload(target, permits.currentRevision, reason),
            parent, reason)
    }
}
