package com.landosol.toolbox.labyrinth.kernel

import java.util.UUID
import java.util.concurrent.CancellationException

interface KernelDomainModule {
    val id: String
    val domain: KernelDomain
    fun onKernelEvent(event: KernelEvent, ctx: KernelRuntime)
    fun reset(traceId: String)
}

interface KernelEventHook {
    val id: String
    val priority: Int get() = 0
    val domains: Set<KernelDomain> get() = emptySet()
    val kinds: Set<String> get() = emptySet()
    fun handle(event: KernelEvent, ctx: KernelRuntime): KernelHookVerdict
}

sealed interface KernelHookVerdict {
    data object Continue : KernelHookVerdict
    data class Emit(val kind: String, val payload: KernelEventPayload, val domain: KernelDomain = KernelDomain.CUSTOM_HOOK) : KernelHookVerdict
    data class Veto(val reason: String) : KernelHookVerdict
    data class OverrideEventChoice(val choiceId: String) : KernelHookVerdict
}

data class KernelIntervention(val eventSeq: Long, val cause: KernelEvent, val verdict: KernelHookVerdict)

class KernelRuntime(private val clock: () -> Long = System::currentTimeMillis, maxLogCapacity: Int = 2_000) {
    companion object { const val MAX_REENTRANCY_DEPTH = 8 }
    val log = KernelEventLog(maxLogCapacity)
    fun addSink(sink: KernelEventSink) = log.addSink(sink)
    fun removeSink(sink: KernelEventSink) = log.removeSink(sink)
    @Volatile var traceId: String = UUID.randomUUID().toString()
        private set
    private val modules = linkedMapOf<String, KernelDomainModule>()
    private val hooks = linkedMapOf<String, KernelEventHook>()
    private var sequence = 0L
    private val stack = ArrayDeque<KernelEvent>()
    @Volatile var latestIntervention: KernelIntervention? = null
        private set

    @Synchronized fun registerModule(module: KernelDomainModule) { modules[module.id] = module }
    @Synchronized fun unregisterModule(id: String) { modules.remove(id) }
    @Synchronized fun registerHook(hook: KernelEventHook) { hooks[hook.id] = hook }
    @Synchronized fun unregisterHook(id: String) { hooks.remove(id) }

    @Synchronized fun resetSession(sessionId: String = UUID.randomUUID().toString()): KernelEvent {
        check(stack.isEmpty())
        traceId = sessionId
        latestIntervention = null
        modules.values.toList().forEach { it.reset(sessionId) }
        return emit(KernelDomain.RUNTIME, KernelEventKinds.SESSION_LIFECYCLE, SessionLifecyclePayload(sessionId, "start"))
    }

    @Synchronized fun emit(domain: KernelDomain, kind: String, payload: KernelEventPayload,
                           cause: KernelEvent? = null, reasonCode: String = kind,
                           summary: String = reasonCode, frameSeq: Long? = cause?.frameSeq,
                           sceneEpoch: Long = cause?.sceneEpoch ?: 0): KernelEvent {
        fun record(d: KernelDomain, k: String, p: KernelEventPayload, parent: KernelEvent?, reason: String, note: String) =
            KernelEventEnvelope(++sequence, parent?.traceId ?: traceId, parent?.eventSeq, clock(), frameSeq ?: parent?.frameSeq,
                if (cause == null && parent != null) parent.sceneEpoch else sceneEpoch, d, k, reason, note, p).also(log::append)
        val parent = cause ?: stack.lastOrNull()
        if (stack.size >= MAX_REENTRANCY_DEPTH) return record(KernelDomain.CUSTOM_HOOK, KernelEventKinds.HOOK_INVOKED,
            HookInvokedPayload("runtime", "Veto", "DEPTH_LIMIT"), parent, "DEPTH_LIMIT", "事件重入达到上限")
        val event = record(domain, kind, payload, parent, reasonCode, summary)
        stack.addLast(event)
        try {
            modules.values.filter { it.domain == domain }.forEach { it.onKernelEvent(event, this) }
            var intervention: KernelIntervention? = null
            val interested = hooks.values.filter { (it.domains.isEmpty() || domain in it.domains) &&
                (it.kinds.isEmpty() || kind in it.kinds) }.sortedWith(compareByDescending<KernelEventHook> { it.priority }.thenBy { it.id })
            for (hook in interested) {
                val verdict = try { hook.handle(event, this) }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { KernelHookVerdict.Veto("HOOK_FAILED: " + e.javaClass.simpleName) }
                if (verdict == KernelHookVerdict.Continue) continue
                val reason = when (verdict) {
                    is KernelHookVerdict.Veto -> verdict.reason
                    is KernelHookVerdict.OverrideEventChoice -> verdict.choiceId
                    is KernelHookVerdict.Emit -> verdict.kind
                    else -> ""
                }
                val audit = record(KernelDomain.CUSTOM_HOOK, KernelEventKinds.HOOK_INVOKED,
                    HookInvokedPayload(hook.id, verdict.javaClass.simpleName, reason), event, "HOOK_INTERVENTION", reason)
                if (verdict is KernelHookVerdict.Emit) emit(verdict.domain, verdict.kind, verdict.payload, audit)
                else {
                    intervention = KernelIntervention(event.eventSeq, audit, verdict)
                    break
                }
                intervention = KernelIntervention(event.eventSeq, audit, verdict)
            }
            if (kind == KernelEventKinds.EVENT_DECISION_PROPOSED) latestIntervention = intervention
        } finally { stack.removeLast() }
        return event
    }
}
