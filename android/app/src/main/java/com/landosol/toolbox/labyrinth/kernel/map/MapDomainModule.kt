package com.landosol.toolbox.labyrinth.kernel.map

import com.landosol.toolbox.labyrinth.LabyrinthBalancedEvidence
import com.landosol.toolbox.labyrinth.MAX_ORPHAN_MOVE_CONFIRMATION_DISMISS_ATTEMPTS
import com.landosol.toolbox.labyrinth.labyrinthCanRecoverOrphanNodeMoveConfirmation
import com.landosol.toolbox.labyrinth.labyrinthNodeMoveConfirmationOwnsFrame
import com.landosol.toolbox.labyrinth.labyrinthOrphanMoveConfirmationBudgetRestored
import com.landosol.toolbox.labyrinth.labyrinthPreservesPendingNodeTransitionForMoveConfirmation
import com.landosol.toolbox.labyrinth.labyrinthShouldDismissOrphanNodeMoveConfirmation
import com.landosol.toolbox.labyrinth.kernel.CustomPayload
import com.landosol.toolbox.labyrinth.kernel.FrameObservedPayload
import com.landosol.toolbox.labyrinth.kernel.KernelActionPermit
import com.landosol.toolbox.labyrinth.kernel.KernelDecision
import com.landosol.toolbox.labyrinth.kernel.KernelDomain
import com.landosol.toolbox.labyrinth.kernel.KernelDomainModule
import com.landosol.toolbox.labyrinth.kernel.KernelEncounterKind
import com.landosol.toolbox.labyrinth.kernel.KernelEncounterStack
import com.landosol.toolbox.labyrinth.kernel.KernelError
import com.landosol.toolbox.labyrinth.kernel.KernelErrorCategory
import com.landosol.toolbox.labyrinth.kernel.KernelEvent
import com.landosol.toolbox.labyrinth.kernel.KernelEventKinds as K
import com.landosol.toolbox.labyrinth.kernel.KernelHookVerdict
import com.landosol.toolbox.labyrinth.kernel.KernelRuntime
import com.landosol.toolbox.labyrinth.kernel.PageStabilityRegistry
import com.landosol.toolbox.labyrinth.kernel.StateReducedPayload
import com.landosol.toolbox.labyrinth.node.LabyrinthMapScanDirection
import com.landosol.toolbox.labyrinth.node.LabyrinthMapScanDirection.BACKWARD
import com.landosol.toolbox.labyrinth.node.LabyrinthMapScanDirection.FORWARD
import com.landosol.toolbox.labyrinth.node.LabyrinthNodeTypes as Node
import com.landosol.toolbox.labyrinth.vision.EntryPixelRect
import com.landosol.toolbox.labyrinth.vision.LabyrinthEntryPageState
import com.landosol.toolbox.labyrinth.vision.LabyrinthEntryPageState.*
import com.landosol.toolbox.labyrinth.vision.LabyrinthNodeMoveConfirmationObservation
import kotlin.math.abs

enum class MapActionKind { NODE_CLICK, MOVE_CONFIRM, ORPHAN_DISMISS, MAP_SCROLL, MAP_NUDGE }

data class MapActionKey(
    val actionKind: MapActionKind,
    val nodeId: Long?,
    val capturedAtMillis: Long,
    val frameEventSeq: Long,
    val direction: LabyrinthMapScanDirection? = null,
)

data class MapNodeTarget(val nodeId: Long, val nodeType: Int)
data class MapNodeCandidate(val target: MapNodeTarget, val rect: EntryPixelRect)

data class MapTopologyEvidence(
    val expectedTargetNodeId: Long? = null,
    val plannedTarget: MapNodeTarget? = null,
    /** Null means reachability was not established. An empty set proves no successor. */
    val reachableSuccessorIds: Set<Long>? = null,
)

data class MapFrameObservation(
    val capturedAtMillis: Long,
    val width: Int,
    val height: Int,
    val page: LabyrinthEntryPageState,
    val currentFrameVerified: Boolean,
    /** Competing interpretations of the click target. Unrelated visible nodes belong to the scanner. */
    val candidates: List<MapNodeCandidate> = emptyList(),
    val topology: MapTopologyEvidence = MapTopologyEvidence(),
    val moveConfirmation: LabyrinthNodeMoveConfirmationObservation? = null,
    val hasMoveConfirmation: Boolean = moveConfirmation != null,
    val shopBackgroundVisible: Boolean = false,
    /** Stable viewport identity supplied by the scanner, independent of animated pixel hashes. */
    val viewportSignature: String = "",
    val frameSeq: Long? = null,
    val sceneEpoch: Long = 0L,
) {
    internal val dialogVisible: Boolean get() = hasMoveConfirmation || moveConfirmation != null
}

data class PendingNodeTransition(
    val targetNodeId: Long,
    val nodeType: Int,
    val clickedAtMillis: Long?,
    val startedAtMillis: Long,
    val confirmedAtMillis: Long? = null,
    val confirmationAttempts: Int = 0,
    val encounterId: String? = null,
)

data class MapNodeArrival(val nodeId: Long, val nodeType: Int, val page: LabyrinthEntryPageState)
data class MapFrameSize(val width: Int, val height: Int)

enum class MapStallReason(internal val requiredFrames: Int) {
    TOPOLOGY_CONFLICT(4), TARGET_MISSING(8), SCROLL_EXHAUSTED(1),
}

data class MapDomainSnapshot(
    val newestCapturedAtMillis: Long?,
    val verifiedCapturedAtMillis: Long?,
    val frameSize: MapFrameSize?,
    val stableFrames: Int,
    val pendingNodeTransition: PendingNodeTransition?,
    val committedNode: MapNodeArrival?,
    val confirmationStableFrames: Int,
    val orphanDismissAttempts: Int,
    val orphanClearFrames: Int,
    val scrollStableFrames: Int,
    val scrollAttempts: Int,
    val blindScrollAttempts: Int,
    val nudgeStallFrames: Int,
    val nudgeAttempts: Int,
)

/** Feed each verified detail capture once, then claim the suggested action and report its executor receipt. */
class MapDomainModule(
    private val runtime: KernelRuntime,
    private val encounterStack: KernelEncounterStack? = null,
) : KernelDomainModule {
    override val id: String = "map"
    override val domain: KernelDomain = KernelDomain.MAP

    private data class Frame(val observation: MapFrameObservation, val number: Long, val event: KernelEvent)
    private data class Proposal(val key: MapActionKey, val target: MapNodeTarget?, val event: KernelEvent)
    private data class Dispatch(
        val proposal: Proposal,
        val event: KernelEvent,
        val at: Long,
        val before: PendingNodeTransition?,
        val after: PendingNodeTransition?,
        val blind: Boolean,
    )

    private data class Streak<K>(
        val key: K, val frame: Long, val frames: Int, val startedAt: Long,
    )

    private val stabilitySpec = PageStabilityRegistry.specFor(NODE_SELECTION)
    private val permit = KernelActionPermit<MapActionKey>(LabyrinthBalancedEvidence.MAX_ACTION_EVIDENCE_AGE_MILLIS)
    private var issuedKey: MapActionKey? = null
    private var newestCapturedAtMillis: Long? = null
    private var verifiedCapturedAtMillis: Long? = null
    private var frameSize: MapFrameSize? = null
    private var frameNumber = 0L
    private var frame: Frame? = null
    private var frameTail: KernelEvent? = null
    private var proposal: Proposal? = null
    private var dispatching = false
    private var inFlight: Dispatch? = null
    private var lastMapActionAt: Long? = null

    private var lastCandidate: MapNodeCandidate? = null
    private var stableFrames = 0
    private var pending: PendingNodeTransition? = null
    private var transitionCause: KernelEvent? = null
    private var nodeFramesSinceClick = 0
    private var mismatch: Streak<LabyrinthEntryPageState>? = null
    private var committedNode: MapNodeArrival? = null

    private var confirmation: Streak<EntryPixelRect>? = null
    private var orphan: Streak<EntryPixelRect>? = null
    private var orphanDismissAttempts = 0
    private var orphanClearFrames = 0
    private var lastOrphanDismissAt: Long? = null
    private var confirmationBlocked: String? = null

    private var scrollTargetId: Long? = null
    private var scrollView: Streak<String>? = null
    private var scrollAttempts = 0
    private var blindScrollAttempts = 0
    private var nudgeTargetId: Long? = null
    private var nudgeStall: Streak<MapStallReason>? = null
    private var nudgeAttempts = 0

    val currentActionKey: MapActionKey? get() = synchronized(runtime) { proposal?.key }
    val pendingNodeTransition: PendingNodeTransition? get() = synchronized(runtime) { pending }

    fun snapshot(): MapDomainSnapshot = synchronized(runtime) {
        MapDomainSnapshot(
            newestCapturedAtMillis, verifiedCapturedAtMillis, frameSize, stableFrames, pending, committedNode,
            confirmation?.frames ?: 0, orphanDismissAttempts, orphanClearFrames, scrollView?.frames ?: 0,
            scrollAttempts, blindScrollAttempts, nudgeStall?.frames ?: 0, nudgeAttempts,
        )
    }

    override fun reset(traceId: String): Unit = synchronized(runtime) {
        val previous = frameTail
        val revoked = takePermit()
        clearNodeStability()
        resetMoveConfirmation()
        resetScrollGate()
        resetRecoveryNudge()
        clearTransition()
        newestCapturedAtMillis = null
        verifiedCapturedAtMillis = null
        frameSize = null
        frameNumber = 0
        frame = null
        frameTail = null
        proposal = null
        dispatching = false
        inFlight = null
        lastMapActionAt = null
        committedNode = null
        if (revoked != null) logRevoked(revoked, "session_reset", previous)
    }

    fun resetStability(): Unit = synchronized(runtime) {
        clearNodeStability()
        dropProposal(MapActionKind.NODE_CLICK)
        takePermit()?.let { frameTail = logRevoked(it, "stability_reset", frameTail) }
    }

    fun resetMoveConfirmation(): Unit = synchronized(runtime) {
        confirmation = null
        orphan = null
        orphanDismissAttempts = 0
        orphanClearFrames = 0
        lastOrphanDismissAt = null
        confirmationBlocked = null
        dropProposal(MapActionKind.MOVE_CONFIRM)
        dropProposal(MapActionKind.ORPHAN_DISMISS)
    }

    fun resetScrollGate(): Unit = synchronized(runtime) {
        scrollTargetId = null
        scrollView = null
        scrollAttempts = 0
        blindScrollAttempts = 0
        dropProposal(MapActionKind.MAP_SCROLL)
    }

    fun resetRecoveryNudge(): Unit = synchronized(runtime) {
        nudgeTargetId = null
        nudgeStall = null
        nudgeAttempts = 0
        dropProposal(MapActionKind.MAP_NUDGE)
    }

    override fun onKernelEvent(event: KernelEvent, ctx: KernelRuntime): Unit = synchronized(runtime) {
        if (ctx !== runtime || event.domain != domain || event.traceId != runtime.traceId ||
            event.kind != K.FRAME_OBSERVED || event.reasonCode == OBSERVED_FRAME
        ) return@synchronized
        val observed = event.payload as? FrameObservedPayload ?: return@synchronized
        // A bare frame event can fence work, but lacks the geometry and verification to authorize it.
        frameNumber++
        frame = null
        proposal = null
        verifiedCapturedAtMillis = null
        clearNodeStability()
        confirmation = null
        orphan = null
        orphanClearFrames = 0
        mismatch = null
        if (observed.capturedAtMillis >= 0) {
            newestCapturedAtMillis = maxOf(newestCapturedAtMillis ?: -1L, observed.capturedAtMillis)
        }
        val revoked = takePermit()
        frameTail = if (revoked == null) event else logRevoked(revoked, "external_frame_observed", event)
    }

    fun observeFrame(observation: MapFrameObservation): KernelDecision = synchronized(runtime) {
        val number = ++frameNumber
        val previous = frame
        val continuesStreak = previous != null && when {
            observation.dialogVisible -> confirmation?.frame == previous.number || orphan?.frame == previous.number
            observation.page == NODE_SELECTION -> scrollView?.frame == previous.number || nudgeStall?.frame == previous.number
            else -> false
        }
        val priorCause = frameTail?.takeIf {
            issuedKey != null || stableFrames in 1 until stabilitySpec.requiredFrames || continuesStreak
        } ?: transitionCause
        frame = null
        proposal = null
        verifiedCapturedAtMillis = null
        val revoked = takePermit()
        val capturedAt = observation.capturedAtMillis
        val advances = capturedAt >= 0 && capturedAt > (newestCapturedAtMillis ?: -1L)
        val size = MapFrameSize(observation.width, observation.height)
        val sizeChanged = frameSize != null && frameSize != size
        val sceneChanged = previous != null && previous.observation.sceneEpoch != observation.sceneEpoch
        val verified = advances && observation.currentFrameVerified && size.width > 0 && size.height > 0
        if (advances) {
            newestCapturedAtMillis = capturedAt
            frameSize = size
        }
        val event = runtime.emit(
            domain, K.FRAME_OBSERVED, FrameObservedPayload(observation.page.name, capturedAt),
            priorCause, OBSERVED_FRAME, frameSeq = observation.frameSeq ?: number, sceneEpoch = observation.sceneEpoch,
        )
        val fence = when {
            !advances -> "non_current_frame"
            sizeChanged -> "frame_size_changed"
            observation.dialogVisible -> "move_confirmation_visible"
            observation.page != NODE_SELECTION -> "left_node_selection"
            !verified -> "frame_unverified"
            observation.shopBackgroundVisible -> "shop_background_visible"
            sceneChanged -> "scene_changed"
            else -> "new_frame"
        }
        val tail = if (revoked == null) event else logRevoked(revoked, fence, event)
        if (number != frameNumber) return@synchronized KernelDecision.Wait("frame_superseded")
        frameTail = tail
        if (!verified || sizeChanged || sceneChanged) {
            clearNodeStability()
            confirmation = null
            orphan = null
            orphanClearFrames = 0
            mismatch = null
            scrollView = null
            nudgeStall = null
        }
        if (!verified) return@synchronized waitFor(fence)
        val current = Frame(observation, number, event)
        frame = current
        val nodePage = observation.page == NODE_SELECTION && !observation.dialogVisible &&
            !observation.shopBackgroundVisible
        if (nodePage) verifiedCapturedAtMillis = capturedAt else clearNodeStability()
        observeDialogClear(current)
        reduceTransition(current)?.let { return@synchronized it }
        if (dispatching || inFlight != null) return@synchronized waitFor("awaiting_action_receipt")
        if (observation.dialogVisible) return@synchronized reduceMoveConfirmation(current)
        if (pending != null) return@synchronized waitFor("awaiting_node_transition")
        if (!nodePage) return@synchronized waitFor("not_node_selection")
        selectNode(current)
    }

    /** Use this entry point for a capture with a visible movement modal. */
    fun handleMoveConfirmation(observation: MapFrameObservation): KernelDecision {
        require(observation.dialogVisible)
        return observeFrame(observation)
    }

    fun capturePermit(key: MapActionKey, nowMillis: Long): KernelActionPermit.Token? = synchronized(runtime) {
        if (key != issuedKey || key.capturedAtMillis != verifiedCapturedAtMillis) return@synchronized null
        permit.capture(key, nowMillis)
    }

    fun validateCurrentEvidence(key: MapActionKey, nowMillis: Long): Boolean = capturePermit(key, nowMillis) != null

    /**
     * Claim only the latest proposal. A captured click token remains usable for the executor's
     * final evidence check until a new observation fences it or the executor reports the receipt.
     */
    fun dispatchAction(key: MapActionKey, nowMillis: Long): KernelEvent? = synchronized(runtime) {
        val selected = proposal ?: return@synchronized null
        val current = frame ?: return@synchronized null
        if (dispatching || inFlight != null || selected.key != key ||
            nowMillis < key.capturedAtMillis ||
            nowMillis - key.capturedAtMillis > LabyrinthBalancedEvidence.MAX_ACTION_EVIDENCE_AGE_MILLIS
        ) return@synchronized null
        if (key.actionKind == MapActionKind.NODE_CLICK && !validateCurrentEvidence(key, nowMillis)) {
            return@synchronized null
        }

        val before = pending
        when (key.actionKind) {
            MapActionKind.NODE_CLICK -> {
                startTransition(checkNotNull(selected.target), nowMillis, nowMillis)
                clearNodeStability()
            }
            MapActionKind.MOVE_CONFIRM -> {
                val transition = before ?: return@synchronized null
                pending = transition.copy(
                    confirmedAtMillis = nowMillis,
                    confirmationAttempts = transition.confirmationAttempts + 1,
                )
                mismatch = null
            }
            else -> Unit
        }
        val after = pending
        if (after !== before) nodeFramesSinceClick = 0
        proposal = null
        val detail = if (after != null && after !== before) after.details() else emptyMap()
        val payload = CustomPayload(key.attributes() + detail + ("dispatchedAtMillis" to nowMillis.toString()))
        dispatching = true
        val dispatched = try {
            runtime.emit(domain, K.ACTION_DISPATCHED, payload, selected.event)
        } finally {
            dispatching = false
        }
        if (after !== before && pending === after) transitionCause = dispatched
        if (frame === current) frameTail = dispatched
        inFlight = Dispatch(selected, dispatched, nowMillis, before, after, current.observation.viewportSignature.isBlank())
        dispatched
    }

    fun observeActionReceipt(dispatch: KernelEvent, executed: Boolean, observedAtMillis: Long): KernelEvent? =
        synchronized(runtime) {
            val active = inFlight ?: return@synchronized null
            if (active.event !== dispatch || observedAtMillis < active.at) return@synchronized null
            val key = active.proposal.key
            inFlight = null
            val revoked = if (issuedKey == key) takePermit() else null
            if (executed) {
                when (key.actionKind) {
                    MapActionKind.NODE_CLICK -> lastMapActionAt = active.at
                    MapActionKind.MOVE_CONFIRM -> Unit
                    MapActionKind.ORPHAN_DISMISS -> {
                        orphanDismissAttempts++
                        lastOrphanDismissAt = active.at
                        orphan = null
                        scrollView = null
                        resetRecoveryNudge()
                    }
                    MapActionKind.MAP_SCROLL -> {
                        scrollAttempts++
                        if (active.blind) blindScrollAttempts++
                        scrollView = null
                        lastMapActionAt = active.at
                    }
                    MapActionKind.MAP_NUDGE -> {
                        nudgeAttempts++
                        nudgeStall = null
                        scrollView = null
                        lastMapActionAt = active.at
                    }
                }
            } else if (active.after !== active.before && pending === active.after) {
                pending = active.before
                nodeFramesSinceClick = 0
                mismatch = null
                if (pending == null) transitionCause = null
            }
            if (revoked != null) logRevoked(revoked, "action_receipt_observed", dispatch)
            val receipt = runtime.emit(
                domain, K.ACTION_RECEIPT_OBSERVED,
                CustomPayload(key.attributes() + mapOf("executed" to executed.toString(), "observedAtMillis" to observedAtMillis.toString())),
                dispatch, if (executed) "action_executed" else "action_rejected",
            )
            if (pending != null && (pending === active.after || pending === active.before)) transitionCause = receipt
            if (frame?.event?.eventSeq == key.frameEventSeq) frameTail = receipt
            receipt
        }

    /** An upper-layer observation may prove the target identity directly. */
    fun confirmArrival(targetNodeId: Long): KernelDecision = synchronized(runtime) {
        val current = frame ?: return@synchronized waitFor("no_current_frame")
        val transition = pending ?: return@synchronized waitFor("no_pending_transition")
        val observed = current.observation
        if (observed.dialogVisible || observed.capturedAtMillis <= (transition.confirmedAtMillis ?: transition.startedAtMillis)) {
            return@synchronized waitFor("awaiting_arrival_frame")
        }
        if (transition.targetNodeId != targetNodeId) return@synchronized needEvidence("arrival_target_conflict")
        commitArrival(current, transition, destinationType(observed.page, transition.nodeType) ?: transition.nodeType, "node_arrived")
    }

    /**
     * The viewport scanner supplies its preferred direction and whether a previous swipe is still
     * being measured. An empty viewport signature uses the bounded blind search.
     */
    fun requestNodeMapScroll(
        targetNodeId: Long,
        direction: LabyrinthMapScanDirection,
        awaitingSwipeOutcome: Boolean = false,
    ): KernelDecision = synchronized(runtime) {
        val current = gestureFrame() ?: return@synchronized waitFor("map_gesture_blocked")
        proposal = null
        if (scrollTargetId != targetNodeId) {
            resetScrollGate()
            scrollTargetId = targetNodeId
        }
        val observed = current.observation
        val blind = observed.viewportSignature.isBlank()
        if (blind && blindScrollAttempts >= MAX_BLIND_SCROLL_STEPS) {
            return@synchronized needEvidence("blind_scroll_exhausted", detail = scrollDetail(targetNodeId))
        }
        if (scrollAttempts >= MAX_SCROLL_STEPS) {
            return@synchronized needEvidence("scroll_exhausted", detail = scrollDetail(targetNodeId))
        }
        if (awaitingSwipeOutcome) {
            scrollView = null
            return@synchronized waitFor("awaiting_scroll_outcome")
        }
        val streak = advance(scrollView, observed.viewportSignature, current)
        scrollView = streak
        if (streak.frames < SCROLL_STABLE_FRAMES || observed.capturedAtMillis - streak.startedAt < SCROLL_SETTLE_MILLIS) {
            return@synchronized waitFor("viewport_settling", scrollDetail(targetNodeId))
        }
        if (coolingDown(lastMapActionAt, observed.capturedAtMillis, MAP_ACTION_INTERVAL_MILLIS)) {
            return@synchronized waitFor("scroll_cooldown")
        }
        val nextDirection = if (blind && blindScrollAttempts >= MAX_BLIND_SCROLL_STEPS / 2) {
            if (direction == FORWARD) BACKWARD else FORWARD
        } else direction
        offer(current, MapActionKind.MAP_SCROLL, "滚动地图", targetNodeId, direction = nextDirection,
            detail = scrollDetail(targetNodeId))
    }

    fun requestNodeMapNudge(targetNodeId: Long, reason: MapStallReason): KernelDecision = synchronized(runtime) {
        val current = gestureFrame() ?: return@synchronized waitFor("map_gesture_blocked")
        proposal = null
        if (nudgeTargetId != targetNodeId) {
            resetRecoveryNudge()
            nudgeTargetId = targetNodeId
        }
        val streak = advance(nudgeStall, reason, current)
        nudgeStall = streak
        val detail = mapOf("targetNodeId" to targetNodeId.toString(), "stallReason" to reason.name,
            "stallFrames" to streak.frames.toString(), "nudgeAttempts" to nudgeAttempts.toString())
        if (nudgeAttempts >= MAX_NUDGES) return@synchronized needEvidence("nudge_exhausted", detail = detail)
        if (streak.frames < reason.requiredFrames) return@synchronized waitFor("map_stall_unconfirmed", detail)
        if (coolingDown(lastMapActionAt, current.observation.capturedAtMillis, NUDGE_INTERVAL_MILLIS)) {
            return@synchronized waitFor("nudge_cooldown", detail)
        }
        val direction = when (nudgeAttempts % 4) {
            0, 3 -> BACKWARD
            else -> FORWARD
        }
        offer(current, MapActionKind.MAP_NUDGE, "微调地图", targetNodeId, direction = direction, detail = detail)
    }

    private fun selectNode(current: Frame): KernelDecision {
        val observed = current.observation
        val topology = observed.topology
        val candidates = observed.candidates.distinct()
        val candidate = candidates.singleOrNull()
        val planned = topology.plannedTarget
        val expected = topology.expectedTargetNodeId
        val reachable = topology.reachableSuccessorIds
        val binding = expected ?: planned?.nodeId
        val conflicting = candidates.size > 1 ||
            (expected != null && planned != null && expected != planned.nodeId) ||
            (binding != null && reachable != null && binding !in reachable) ||
            (candidate != null && (
                (binding != null && candidate.target.nodeId != binding) ||
                    (reachable != null && candidate.target.nodeId !in reachable) ||
                    (planned != null && candidate.target.nodeType != planned.nodeType)
                ))
        if (conflicting) {
            clearNodeStability()
            return needEvidence("node_topology_conflict", detail = mapOf(
                "expectedTargetNodeId" to expected.orEmptyText(), "plannedTargetNodeId" to planned?.nodeId.orEmptyText(),
                "plannedNodeType" to planned?.nodeType?.toString().orEmpty(),
                "reachableSuccessorIds" to (reachable?.sorted()?.joinToString(",") ?: "unknown"),
                "candidateNodeIds" to candidates.joinToString(",") { it.target.nodeId.toString() },
                "candidateNodeTypes" to candidates.joinToString(",") { it.target.nodeType.toString() },
            ))
        }
        if (candidate == null || !validTarget(candidate.target) || !contains(observed, candidate.rect)) {
            clearNodeStability()
            return waitFor("node_target_missing")
        }
        val previous = lastCandidate
        val drift = checkNotNull(stabilitySpec.maxCenterDriftPx)
        stableFrames = if (previous != null && previous.target == candidate.target &&
            abs(centerX(previous.rect) - centerX(candidate.rect)) <= drift &&
            abs(centerY(previous.rect) - centerY(candidate.rect)) <= drift
        ) (stableFrames + 1).coerceAtMost(stabilitySpec.requiredFrames) else 1
        lastCandidate = candidate
        val detail = mapOf("targetNodeId" to candidate.target.nodeId.toString(),
            "stableFrames" to stableFrames.toString(), "requiredFrames" to stabilitySpec.requiredFrames.toString())
        reduce("node_stability", detail)
        if (frame !== current) return KernelDecision.Wait("frame_superseded")
        if (stableFrames < stabilitySpec.requiredFrames) return waitFor("node_stabilizing")
        if (coolingDown(lastMapActionAt, observed.capturedAtMillis, MAP_ACTION_INTERVAL_MILLIS)) {
            return waitFor("node_click_cooldown")
        }
        return offer(current, MapActionKind.NODE_CLICK, Node.labelOf(candidate.target.nodeType),
            candidate.target.nodeId, candidate.rect, candidate.target)
    }

    private fun reduceMoveConfirmation(current: Frame): KernelDecision {
        val observed = current.observation
        if (observed.shopBackgroundVisible || !labyrinthNodeMoveConfirmationOwnsFrame(observed.page, true)) {
            confirmation = null
            orphan = null
            return waitFor("move_confirmation_unowned")
        }
        val blocked = confirmationBlocked
        if (blocked != null) return needEvidence(blocked, KernelErrorCategory.TIMEOUT)
        val dialog = observed.moveConfirmation
        if (dialog == null || dialog.confidence !in 0.0..1.0) {
            return waitFor("move_confirmation_buttons_missing")
        }
        val transition = pending ?: recoverOrphan(observed, dialog.confidence)
            ?: return dismissOrphan(current, dialog.cancelButtonRect)
        if (!contains(observed, dialog.confirmButtonRect)) {
            confirmation = null
            return waitFor("move_confirmation_button_invalid")
        }
        val streak = advance(confirmation, dialog.confirmButtonRect, current)
        confirmation = streak
        if (streak.frames < MOVE_CONFIRM_STABLE_FRAMES) return waitFor("move_confirmation_stabilizing")
        if (coolingDown(transition.confirmedAtMillis, observed.capturedAtMillis, MOVE_CONFIRM_RETRY_MILLIS)) {
            return waitFor("move_confirmation_cooldown")
        }
        if (transition.confirmationAttempts >= MAX_CONFIRM_ATTEMPTS) {
            confirmationBlocked = "move_confirmation_exhausted"
            return endTransition(transition, "move_confirmation_exhausted", KernelErrorCategory.TIMEOUT)
        }
        return offer(current, MapActionKind.MOVE_CONFIRM, "确认移动", transition.targetNodeId, dialog.confirmButtonRect)
    }

    private fun recoverOrphan(observed: MapFrameObservation, confidence: Double): PendingNodeTransition? {
        val topology = observed.topology
        val target = topology.plannedTarget ?: return null
        if (!validTarget(target) ||
            (topology.expectedTargetNodeId != null && topology.expectedTargetNodeId != target.nodeId) ||
            topology.reachableSuccessorIds?.singleOrNull() != target.nodeId ||
            !labyrinthCanRecoverOrphanNodeMoveConfirmation(observed.page, confidence, true, observed.shopBackgroundVisible)
        ) return null
        val transition = startTransition(target, observed.capturedAtMillis, null)
        orphan = null
        transitionCause = reduce("orphan_move_recovered", transition.details())
        return transition
    }

    private fun dismissOrphan(current: Frame, rect: EntryPixelRect): KernelDecision {
        val observed = current.observation
        val detail = mapOf("dismissAttempts" to orphanDismissAttempts.toString(),
            "maxAttempts" to MAX_ORPHAN_MOVE_CONFIRMATION_DISMISS_ATTEMPTS.toString())
        if (orphanDismissAttempts >= MAX_ORPHAN_MOVE_CONFIRMATION_DISMISS_ATTEMPTS) {
            return needEvidence("orphan_dismiss_exhausted", detail = detail)
        }
        if (!contains(observed, rect)) {
            orphan = null
            return waitFor("orphan_cancel_button_invalid")
        }
        val streak = advance(orphan, rect, current)
        orphan = streak
        if (!labyrinthShouldDismissOrphanNodeMoveConfirmation(
                observed.page, false, false, streak.frames, orphanDismissAttempts,
                lastOrphanDismissAt ?: Long.MIN_VALUE, observed.capturedAtMillis, observed.shopBackgroundVisible,
            )
        ) return waitFor("orphan_dismiss_wait", detail + ("stableFrames" to streak.frames.toString()))
        return offer(current, MapActionKind.ORPHAN_DISMISS, "取消移动", rect = rect, detail = detail)
    }

    private fun observeDialogClear(current: Frame) {
        val observed = current.observation
        if (!observed.dialogVisible) {
            confirmation = null
            orphan = null
        }
        val clear = !observed.dialogVisible && observed.page !in transitionalPages
        orphanClearFrames = if (clear) orphanClearFrames + 1 else 0
        if (labyrinthOrphanMoveConfirmationBudgetRestored(orphanClearFrames)) {
            val hadBudgetInUse = orphanDismissAttempts > 0 || confirmationBlocked != null
            resetMoveConfirmation()
            if (hadBudgetInUse) reduce("move_confirmation_budget_restored")
        }
    }

    private fun reduceTransition(current: Frame): KernelDecision? {
        val transition = pending ?: return null
        val observed = current.observation
        val at = observed.capturedAtMillis
        val waitStartedAt = transition.confirmedAtMillis ?: transition.startedAtMillis
        if (at <= waitStartedAt) return waitFor("awaiting_post_action_frame")
        val ownedModal = !observed.shopBackgroundVisible &&
            labyrinthPreservesPendingNodeTransitionForMoveConfirmation(observed.page, true, observed.dialogVisible)
        val actualType = destinationType(observed.page, transition.nodeType)
        if (!ownedModal && !observed.dialogVisible && matchesDestination(observed.page, transition.nodeType, actualType)) {
            return commitArrival(current, transition, transition.nodeType, "node_arrived")
        }
        if (transition.confirmedAtMillis == null) {
            if (observed.page == NODE_SELECTION && !observed.dialogVisible) nodeFramesSinceClick++
            if (!ownedModal && at - waitStartedAt >= MOVE_APPEAR_TIMEOUT_MILLIS &&
                (observed.page != NODE_SELECTION || nodeFramesSinceClick >= 2)
            ) return endTransition(transition, "move_confirmation_timeout", KernelErrorCategory.TIMEOUT)
        } else if (at - waitStartedAt >= ENTRY_TIMEOUT_MILLIS) {
            if (ownedModal) confirmationBlocked = "node_entry_timeout"
            return endTransition(transition, "node_entry_timeout", KernelErrorCategory.TIMEOUT)
        }
        if (observed.dialogVisible || observed.page == NODE_SELECTION || observed.page in transitionalPages) {
            mismatch = null
            return null
        }
        val streak = advance(mismatch, observed.page, current)
        mismatch = streak
        val detail = transition.details() + ("mismatchFrames" to streak.frames.toString())
        transitionCause = reduce("node_entry_mismatch", detail)
        if (streak.frames < ENTRY_MISMATCH_FRAMES) return waitFor("node_entry_mismatch_stabilizing", detail)
        if (actualType != null && transition.confirmedAtMillis != null) {
            return commitArrival(current, transition, actualType, "node_entry_reconciled")
        }
        if (observed.page in genericDestinationPages) return waitFor("node_entry_type_unresolved", detail)
        return endTransition(transition, "node_entry_mismatch", KernelErrorCategory.STATE)
    }

    private fun startTransition(target: MapNodeTarget, at: Long, clickedAt: Long?): PendingNodeTransition {
        val encounterId = encounterStack?.peek()?.takeIf { it.kind == KernelEncounterKind.MAP }?.id
        val transition = PendingNodeTransition(target.nodeId, target.nodeType, clickedAt, at, encounterId = encounterId)
        pending = transition
        confirmation = null
        mismatch = null
        nodeFramesSinceClick = 0
        return transition
    }

    private fun endTransition(transition: PendingNodeTransition, code: String, category: KernelErrorCategory): KernelDecision {
        clearTransition()
        clearNodeStability()
        return needEvidence(code, category, transition.details())
    }

    private fun commitArrival(current: Frame, transition: PendingNodeTransition, nodeType: Int, code: String): KernelDecision {
        committedNode = MapNodeArrival(transition.targetNodeId, nodeType, current.observation.page)
        clearTransition()
        clearNodeStability()
        resetScrollGate()
        resetRecoveryNudge()
        val popped = transition.encounterId?.let { encounterStack?.popExpected(it, code) }
        reduce(code, transition.details() + ("actualNodeType" to nodeType.toString()), popped?.id)
        val decision = KernelDecision.Complete(popped?.id, "已确认进入节点 ${transition.targetNodeId}")
        logDecision(code, decision, mapOf("targetNodeId" to transition.targetNodeId.toString()))
        return decision
    }

    private fun clearTransition() {
        pending = null
        transitionCause = null
        nodeFramesSinceClick = 0
        mismatch = null
        confirmation = null
        dropProposal(MapActionKind.MOVE_CONFIRM)
    }

    private fun gestureFrame(): Frame? {
        if (verifiedCapturedAtMillis == null || pending != null || dispatching || inFlight != null ||
            proposal?.key?.actionKind == MapActionKind.NODE_CLICK
        ) return null
        return frame
    }

    private fun offer(
        current: Frame, kind: MapActionKind, label: String, nodeId: Long? = null, rect: EntryPixelRect? = null,
        target: MapNodeTarget? = null, direction: LabyrinthMapScanDirection? = null,
        detail: Map<String, String> = emptyMap(),
    ): KernelDecision {
        val key = MapActionKey(kind, nodeId, current.observation.capturedAtMillis, current.event.eventSeq, direction)
        val code = kind.name.lowercase()
        val decision = KernelDecision.Act(code, label, nodeId?.toString(), rect, code)
        proposal = null
        val decisionEvent = logDecision(code, decision, key.attributes() + detail)
        if (frame !== current) return KernelDecision.Wait("frame_superseded")
        val intervention = runtime.latestIntervention?.takeIf { it.eventSeq == decisionEvent.eventSeq }
        if (intervention != null && intervention.verdict is KernelHookVerdict.Veto) {
            frameTail = intervention.cause
            return waitFor("map_action_vetoed", mapOf("reason" to intervention.verdict.reason))
        }
        val authorized = if (kind == MapActionKind.NODE_CLICK) {
            permit.issue(key, key.capturedAtMillis)
            issuedKey = key
            runtime.emit(domain, K.PERMIT_ISSUED,
                CustomPayload(key.attributes() + ("revision" to permit.currentRevision.toString())), decisionEvent, "node_click_current")
        } else decisionEvent
        if (frame !== current) return KernelDecision.Wait("frame_superseded")
        frameTail = authorized
        proposal = Proposal(key, target, authorized)
        return decision
    }

    private fun waitFor(code: String, detail: Map<String, String> = emptyMap()): KernelDecision.Wait {
        val decision = KernelDecision.Wait(code)
        logDecision(code, decision, detail)
        return decision
    }

    private fun needEvidence(
        code: String,
        category: KernelErrorCategory = KernelErrorCategory.EVIDENCE,
        detail: Map<String, String> = emptyMap(),
    ): KernelDecision.NeedEvidence {
        reduce(code, detail)
        val decision = KernelDecision.NeedEvidence(KernelError(code, category, id), code)
        logDecision(code, decision, detail)
        return decision
    }

    private fun reduce(code: String, detail: Map<String, String> = emptyMap(), encounterId: String? = null): KernelEvent {
        val parent = frameTail
        val event = runtime.emit(domain, K.STATE_REDUCED,
            StateReducedPayload(frame?.observation?.page?.name ?: UNKNOWN.name, encounterId = encounterId, detail = detail),
            parent, code)
        if (frameTail === parent) frameTail = event
        return event
    }

    private fun logDecision(code: String, decision: KernelDecision, detail: Map<String, String>): KernelEvent {
        val parent = frameTail
        val event = runtime.emit(domain, K.EVENT_DECISION_PROPOSED,
            CustomPayload(detail + ("decision" to decision.javaClass.simpleName)), parent, code)
        if (frameTail === parent) frameTail = event
        return event
    }

    private fun takePermit(): MapActionKey? {
        val key = issuedKey ?: return null
        issuedKey = null
        permit.revoke()
        return key
    }

    private fun logRevoked(key: MapActionKey, reason: String, cause: KernelEvent?): KernelEvent =
        runtime.emit(domain, K.PERMIT_REVOKED,
            CustomPayload(key.attributes() + ("revision" to permit.currentRevision.toString())), cause, reason)

    private fun dropProposal(kind: MapActionKind) {
        if (proposal?.key?.actionKind == kind) proposal = null
    }

    private fun clearNodeStability() {
        lastCandidate = null
        stableFrames = 0
    }

    private fun scrollDetail(targetNodeId: Long): Map<String, String> = mapOf(
        "targetNodeId" to targetNodeId.toString(),
        "stableFrames" to (scrollView?.frames ?: 0).toString(),
        "scrollAttempts" to scrollAttempts.toString(),
        "blindScrollAttempts" to blindScrollAttempts.toString(),
    )

    private fun <K> advance(previous: Streak<K>?, key: K, current: Frame): Streak<K> {
        if (previous != null && previous.key == key) {
            if (previous.frame == current.number) return previous
            if (previous.frame == current.number - 1) return previous.copy(frame = current.number, frames = previous.frames + 1)
        }
        return Streak(key, current.number, 1, current.observation.capturedAtMillis)
    }

    private fun MapActionKey.attributes(): Map<String, String> = mapOf(
        "actionKind" to actionKind.name, "nodeId" to nodeId.orEmptyText(), "capturedAtMillis" to capturedAtMillis.toString(),
        "direction" to direction?.name.orEmpty(),
    )

    private fun PendingNodeTransition.details(): Map<String, String> = mapOf(
        "targetNodeId" to targetNodeId.toString(), "nodeType" to nodeType.toString(),
        "startedAtMillis" to startedAtMillis.toString(), "clickedAtMillis" to clickedAtMillis.orEmptyText(),
        "confirmedAtMillis" to confirmedAtMillis.orEmptyText(), "confirmationAttempts" to confirmationAttempts.toString(),
    )

    private fun Long?.orEmptyText() = this?.toString().orEmpty()

    private fun matchesDestination(page: LabyrinthEntryPageState, expectedType: Int, actualType: Int?): Boolean =
        actualType == expectedType || when (page) {
            BATTLE_RESULT -> expectedType in battleTypes
            INITIAL_CHARACTER_SELECTION, CHARACTER_JOINED, ITEM_REWARD -> expectedType == Node.LINK || expectedType == Node.EVENT
            else -> false
        }

    private fun destinationType(page: LabyrinthEntryPageState, expectedType: Int): Int? = when (page) {
        SHOP, SHOP_PURCHASE_CONFIRMATION, SHOP_PURCHASE_COMPLETE, SHOP_EXIT_CONFIRMATION -> Node.SHOP
        EVENT_CHOICE, EVENT_ANIMATION -> Node.EVENT
        LINK_CHOICE -> Node.LINK
        RELIC_CHOICE -> Node.RELIC
        BATTLE_CHALLENGE, BATTLE_TEAM_SELECTION, BATTLE_IN_PROGRESS ->
            expectedType.takeIf { it in battleTypes } ?: Node.NORMAL_BATTLE
        else -> null
    }

    private fun validTarget(target: MapNodeTarget) = target.nodeId > 0 && Node.isClickable(target.nodeType)

    private fun contains(frame: MapFrameObservation, rect: EntryPixelRect): Boolean =
        rect.left.toLong() + rect.width <= frame.width && rect.top.toLong() + rect.height <= frame.height

    private fun centerX(rect: EntryPixelRect) = rect.left + rect.width / 2.0
    private fun centerY(rect: EntryPixelRect) = rect.top + rect.height / 2.0
    private fun coolingDown(last: Long?, now: Long, interval: Long) = last != null && now - last < interval

    private companion object {
        const val OBSERVED_FRAME = "map_frame_observed"
        const val MOVE_APPEAR_TIMEOUT_MILLIS = 6_000L
        const val ENTRY_TIMEOUT_MILLIS = 6_000L
        const val MOVE_CONFIRM_STABLE_FRAMES = 2
        const val MOVE_CONFIRM_RETRY_MILLIS = 2_000L
        const val MAX_CONFIRM_ATTEMPTS = 3
        const val ENTRY_MISMATCH_FRAMES = 3
        const val MAP_ACTION_INTERVAL_MILLIS = 2_500L
        const val NUDGE_INTERVAL_MILLIS = 1_500L
        const val MAX_NUDGES = 8
        const val MAX_SCROLL_STEPS = 6
        const val MAX_BLIND_SCROLL_STEPS = MAX_SCROLL_STEPS
        const val SCROLL_STABLE_FRAMES = 5
        const val SCROLL_SETTLE_MILLIS = 1_500L
        val transitionalPages = setOf(UNKNOWN, NODE_MAP_VIEW, GAME_LOADING_PROGRESS, PRE_HOME_DATA_LOADING)
        val genericDestinationPages = setOf(INITIAL_CHARACTER_SELECTION, CHARACTER_JOINED, ITEM_REWARD,
            BATTLE_RESULT, RUN_CLEAR_RESULT, RUN_CLEAR_CONGRATULATIONS, RUN_CLEAR_CHARACTER_SUMMARY,
            RUN_CLEAR_REWARD_ANIMATION, RUN_CLEAR_CHEST_ANIMATION, RUN_CLEAR_CHEST_RESULT)
        val battleTypes = setOf(Node.NORMAL_BATTLE, Node.EX_BATTLE, Node.BOSS)
    }
}
