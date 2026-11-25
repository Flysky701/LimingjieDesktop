package com.landosol.toolbox.labyrinth.kernel.event

import com.landosol.toolbox.gamedata.EventResource
import com.landosol.toolbox.labyrinth.LabyrinthEventActionKey
import com.landosol.toolbox.labyrinth.LabyrinthEventChoiceDecision
import com.landosol.toolbox.labyrinth.LabyrinthEventChoicePlanner
import com.landosol.toolbox.labyrinth.kernel.*
import com.landosol.toolbox.labyrinth.kernel.KernelEventKinds as K
import com.landosol.toolbox.labyrinth.vision.LabyrinthEventChoiceObservation

/** Session control owns observations and permits. Recognition only reads published catalog snapshots. */
class EventDomainModule(private val runtime: KernelRuntime) : KernelDomainModule {
    override val id = "game-event"
    override val domain = KernelDomain.EVENT

    override fun status(): KernelModuleStatus = synchronized(runtime) {
        val phase = when {
            awaitingUnknownEvidence -> "awaiting_evidence"
            observed != null -> "observed"
            lastPage == "EVENT_CHOICE" || lastPage == "EVENT_ANIMATION" -> lastPage
            else -> null
        }
        if (phase == null) super.status() else KernelModuleStatus(id, domain, true, phase)
    }
    @Volatile var extensionPack = KernelEventExtensionPack()
        private set
    private var ruleProvenance: Map<String, String> = emptyMap()
    private var base: List<EventResource>? = null
    @Volatile private var catalog: GameEventCatalogRegistry? = null
    internal fun isCurrent(source: GameEventCatalogRegistry?) = source == null || source === catalog

    @Synchronized fun catalogFor(events: List<EventResource>): GameEventCatalogRegistry {
        if (base !== events) {
            catalog = GameEventCatalogRegistry(events).apply { addOverlay(extensionPack.overlay) }
            base = events
        }
        return requireNotNull(catalog)
    }
    @Synchronized internal fun install(pack: KernelEventExtensionPack, provenance: Map<String, String> = emptyMap()) {
        val replacement = base?.let { GameEventCatalogRegistry(it).apply { addOverlay(pack.overlay) } }
        catalog = replacement
        extensionPack = pack
        ruleProvenance = provenance.toMap()
    }

    internal fun install(compiled: CompiledEventPackSet) = install(compiled.extensionPack, compiled.ruleProvenance)

    private var sessionRoot: KernelEvent? = null
    private var frame: KernelEvent? = null
    private var observed: KernelEvent? = null
    private var parent: KernelEvent? = null
    private var lastDispatch: KernelEvent? = null
    private var lastReceipt: KernelEvent? = null
    private var replanCause: KernelEvent? = null
    private var lastPage: String? = null
    internal var awaitingUnknownEvidence = false
        private set
    private var frameSize: Pair<Int, Int>? = null
    private val permit = KernelActionPermit<LabyrinthEventActionKey> { issued, key, revision, reason ->
        parent = runtime.emit(domain, if (issued) K.PERMIT_ISSUED else K.PERMIT_REVOKED,
            PermitTransitionPayload(key?.choiceId, revision, reason), parent, reason)
    }

    override fun reset(traceId: String) {
        permit.revoke("session_reset")
        frame = null
        sessionRoot = null
        observed = null
        parent = null
        lastDispatch = null
        lastReceipt = null
        replanCause = null
        lastPage = null
        awaitingUnknownEvidence = false
        frameSize = null
    }

    override fun onKernelEvent(event: KernelEvent, ctx: KernelRuntime) {
        when (event.kind) {
            K.EVENT_CATALOG_MATCHED, K.EVENT_UNKNOWN_OBSERVED -> observed = event
            K.ACTION_DISPATCHED -> { lastDispatch = event; lastReceipt = null }
            K.ACTION_RECEIPT_OBSERVED -> if (event.causeEventSeq == lastDispatch?.eventSeq) lastReceipt = event
        }
    }

    internal val currentFrame get() = frame

    internal fun started(root: KernelEvent) { sessionRoot = root }
    internal fun stopped(reason: String, cause: KernelEvent? = frame ?: sessionRoot) {
        revoke("session_stopped")
        runtime.emit(KernelDomain.RUNTIME, K.SESSION_LIFECYCLE, SessionLifecyclePayload(runtime.traceId, "stop"), cause, summary = reason)
    }
    internal fun beginFrame(seq: Long, capturedAt: Long, epoch: Long, page: String,
                            observation: LabyrinthEventChoiceObservation?, unknownText: String?, width: Int, height: Int, cause: KernelEvent? = null) {
        frameSize = width to height
        frame = runtime.emit(KernelDomain.ENTRY, K.FRAME_OBSERVED, FrameObservedPayload(page, capturedAt),
            replanCause ?: cause ?: sessionRoot, frameSeq = seq, sceneEpoch = epoch)
        replanCause = null
        if (page != lastPage) {
            runtime.emit(KernelDomain.ENTRY, K.STATE_REDUCED, StateReducedPayload(page), frame)
            lastPage = page
        }
        when {
            observation != null -> awaitingUnknownEvidence = !observation.catalogMatched
            unknownText != null -> awaitingUnknownEvidence = true
            page != "EVENT_CHOICE" && page != "UNKNOWN" -> awaitingUnknownEvidence = false
        }
        observed = null
        parent = frame
        observe(observation, unknownText)
    }

    private fun observe(observation: LabyrinthEventChoiceObservation?, unknownText: String?) {
        if (observation?.catalogMatched == true) {
            parent = runtime.emit(domain, K.EVENT_CATALOG_MATCHED, GameEventObservedPayload(observation.event.id,
                observation.stableFrames, observation.trusted), frame)
        } else {
            val text = observation?.rawText ?: unknownText ?: return
            parent = runtime.emit(domain, K.EVENT_UNKNOWN_OBSERVED, UnknownGameEventObservedPayload(text.take(300),
                GameEventEffectParser.parse(text).primitives.map { it.toString().take(120) },
                observation?.choices.orEmpty().associate { it.choice.id to it.choice.description.take(300) }), frame)
        }
    }

    internal fun propose(planner: LabyrinthEventChoicePlanner?, observation: LabyrinthEventChoiceObservation,
                         rules: GameEventRuleEvaluation?, planned: LabyrinthEventChoiceDecision,
                         acquired: Set<String>, rejected: Set<String>): LabyrinthEventChoiceDecision {
        val original = checkGeometry(planned)
        if (observed == null) observe(observation, null)
        val ruleId = if (observation.catalogMatched) rules?.rule?.id else extensionPack.unknownEventPolicy.name
        val evaluation = runtime.emit(domain, K.EVENT_RULE_EVALUATED, PolicyRuleEvaluatedPayload(ruleId,
            if (observation.catalogMatched) rules?.action?.javaClass?.simpleName ?: "Wait" else extensionPack.unknownEventPolicy.name,
            rules?.examinedRuleIds.orEmpty(), if (observation.catalogMatched)
                ruleProvenance[ruleId] ?: planner?.provenanceFor(ruleId) else null), parent ?: observed)
        val selected = original as? LabyrinthEventChoiceDecision.Select
        val description = when (original) {
            is LabyrinthEventChoiceDecision.Select -> original.safetyNote ?: original.explanation
            is LabyrinthEventChoiceDecision.Wait -> original.reason
        }
        val proposal: KernelEvent
        val intervention: KernelIntervention?
        synchronized(runtime) {
            proposal = runtime.emit(domain, K.EVENT_DECISION_PROPOSED, DecisionProposedPayload(observation.event.id,
                selected?.choiceId, selected?.actionSafe == true, description), evaluation, summary = description)
            intervention = runtime.latestIntervention?.takeIf { it.eventSeq == proposal.eventSeq }
        }
        parent = intervention?.cause ?: proposal
        val decision = when (val verdict = intervention?.verdict) {
            is KernelHookVerdict.Veto -> LabyrinthEventChoiceDecision.Wait(verdict.reason)
            is KernelHookVerdict.OverrideEventChoice -> {
                // Waiting for T1 completion or a fresh detail frame cannot be overridden.
                if (selected?.actionSafe != true || planner == null) LabyrinthEventChoiceDecision.Wait("Hook 等待安全原始推荐：$description")
                else {
                    val target = observation.event.choices.singleOrNull { it.id == verdict.choiceId }
                    if (!observation.catalogMatched && target != null && !extensionPack.unknownEventPolicy.allows(target))
                        LabyrinthEventChoiceDecision.Wait("Hook 目标未通过未知事件策略")
                    else checkGeometry(planner.policyChoice("hook/" + verdict.choiceId, target, observation, acquired, rejected))
                }
            }
            else -> return original
        }
        val final = decision as? LabyrinthEventChoiceDecision.Select
        val reason = final?.safetyNote ?: final?.explanation ?: (decision as LabyrinthEventChoiceDecision.Wait).reason
        parent = runtime.emit(domain, K.STATE_REDUCED, DecisionProposedPayload(observation.event.id, final?.choiceId,
            final?.actionSafe == true, reason), parent, "HOOK_DECISION_VALIDATED", reason)
        return decision
    }

    private fun checkGeometry(decision: LabyrinthEventChoiceDecision): LabyrinthEventChoiceDecision {
        val (width, height) = frameSize ?: return decision
        if (decision !is LabyrinthEventChoiceDecision.Select) return decision
        val rect = decision.buttonRect
        return if (rect.left < 0 || rect.top < 0 || rect.width <= 0 || rect.height <= 0 ||
            rect.left.toLong() + rect.width > width || rect.top.toLong() + rect.height > height)
            decision.copy(actionSafe = false, safetyNote = "按钮矩形与当前捕获尺寸不符")
        else decision
    }

    internal fun unmatchedWait(): LabyrinthEventChoiceDecision.Wait {
        val wait = LabyrinthEventChoiceDecision.Wait("未收录事件，等待可信的当前帧按钮")
        val rule = runtime.emit(domain, K.EVENT_RULE_EVALUATED, PolicyRuleEvaluatedPayload(null,
            extensionPack.unknownEventPolicy.name), observed ?: frame)
        parent = runtime.emit(domain, K.EVENT_DECISION_PROPOSED, DecisionProposedPayload("", null, false, wait.reason), rule)
        return wait
    }

    internal fun authorize(key: LabyrinthEventActionKey?, capturedAt: Long, reason: String) {
        if (key == null || capturedAt < 0) revoke(reason) else permit.issue(key, capturedAt, reason)
    }
    internal fun revoke(reason: String) { permit.revoke(reason) }
    internal fun captured(key: LabyrinthEventActionKey, at: Long): KernelEvent? {
        val token = permit.capture(key, at) ?: return null
        if (!token.isCurrent(at)) return null
        return runtime.emit(domain, K.PERMIT_ISSUED, PermitTransitionPayload(key.choiceId, permit.currentRevision, "dispatch_capture"), parent)
    }
    internal fun dispatched(choice: String, cause: KernelEvent) = runtime.emit(domain, K.ACTION_DISPATCHED, ActionDispatchedPayload(choice), cause)
    internal fun receipt(choice: String, verdict: String, cause: KernelEvent) =
        runtime.emit(domain, K.ACTION_RECEIPT_OBSERVED, ActionReceiptPayload(choice, verdict), cause, verdict)

    internal fun refused(choice: String) {
        parent = receipt(choice, "PAGE_UNCHANGED", lastReceipt ?: lastDispatch ?: parent ?: return)
        revoke("PAGE_UNCHANGED")
        replanCause = parent
    }
}
