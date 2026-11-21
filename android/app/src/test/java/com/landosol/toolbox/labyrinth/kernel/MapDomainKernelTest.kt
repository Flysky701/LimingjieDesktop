package com.landosol.toolbox.labyrinth.kernel

import com.landosol.toolbox.labyrinth.kernel.KernelEventKinds as K
import com.landosol.toolbox.labyrinth.kernel.map.MapActionKey
import com.landosol.toolbox.labyrinth.kernel.map.MapActionKind
import com.landosol.toolbox.labyrinth.kernel.map.MapDomainModule
import com.landosol.toolbox.labyrinth.kernel.map.MapDomainSnapshot
import com.landosol.toolbox.labyrinth.kernel.map.MapFrameObservation
import com.landosol.toolbox.labyrinth.kernel.map.MapFrameSize
import com.landosol.toolbox.labyrinth.kernel.map.MapNodeArrival
import com.landosol.toolbox.labyrinth.kernel.map.MapNodeCandidate
import com.landosol.toolbox.labyrinth.kernel.map.MapNodeTarget
import com.landosol.toolbox.labyrinth.kernel.map.MapStallReason
import com.landosol.toolbox.labyrinth.kernel.map.MapTopologyEvidence
import com.landosol.toolbox.labyrinth.node.LabyrinthMapScanDirection.BACKWARD
import com.landosol.toolbox.labyrinth.node.LabyrinthMapScanDirection.FORWARD
import com.landosol.toolbox.labyrinth.node.LabyrinthNodeTypes as Node
import com.landosol.toolbox.labyrinth.vision.EntryPixelRect
import com.landosol.toolbox.labyrinth.vision.LabyrinthEntryPageState
import com.landosol.toolbox.labyrinth.vision.LabyrinthEntryPageState.*
import com.landosol.toolbox.labyrinth.vision.LabyrinthNodeMoveConfirmationObservation
import org.junit.Assert.*
import org.junit.Test

class MapDomainKernelTest {
    @Test fun everyNewEvidenceBoundaryRevokesThePreviousClickPermit() {
        val boundaries = listOf(
            "non_current_frame" to { f: MapFrameObservation -> f.copy(capturedAtMillis = f.capturedAtMillis - 301) },
            "frame_unverified" to { f: MapFrameObservation -> f.copy(currentFrameVerified = false) },
            "frame_size_changed" to { f: MapFrameObservation -> f.copy(width = 1920, height = 1080) },
            "move_confirmation_visible" to { f: MapFrameObservation -> f.copy(hasMoveConfirmation = true) },
            "left_node_selection" to { f: MapFrameObservation -> f.copy(page = NODE_MAP_VIEW) },
            "new_frame" to { f: MapFrameObservation -> f },
        )
        for ((reason, change) in boundaries) {
            val replay = Replay()
            val key = replay.armClick()
            val token = requireNotNull(replay.map.capturePermit(key.copy(), replay.now))
            assertTrue(token.isCurrent(replay.now))
            assertFalse(token.isCurrent(replay.now + 1_201))
            val issued = replay.last(K.PERMIT_ISSUED)
            val changed = change(replay.observation(replay.now + 300))
            replay.feed(changed)
            val revoke = replay.last(K.PERMIT_REVOKED)
            assertEquals(reason, revoke.reasonCode)
            assertFalse(reason, token.isCurrent(replay.now))
            assertNull(replay.map.capturePermit(key, replay.now))
            assertFalse(replay.map.validateCurrentEvidence(key, replay.now))
            assertNull(replay.map.dispatchAction(key, replay.now))
            val chain = replay.chain(revoke, K.FRAME_OBSERVED, K.PERMIT_ISSUED, K.FRAME_OBSERVED, K.PERMIT_REVOKED)
            assertTrue(issued in chain)
            assertEquals(replay.last(K.FRAME_OBSERVED).eventSeq, revoke.causeEventSeq)
            assertEquals(MapFrameSize(changed.width, changed.height), replay.map.snapshot().frameSize)
            if (reason == "non_current_frame") {
                assertEquals(key.capturedAtMillis, replay.map.snapshot().newestCapturedAtMillis)
                assertNull(replay.map.snapshot().verifiedCapturedAtMillis)
            }
            if (reason == "new_frame") {
                val freshKey = requireNotNull(replay.map.currentActionKey)
                assertNotEquals(key, freshKey)
                assertTrue(replay.map.validateCurrentEvidence(freshKey, replay.now))
            } else assertNull(replay.map.currentActionKey)
        }
    }

    @Test fun bareFrameEventAlsoFencesClickWork() {
        val replay = Replay()
        val key = replay.armClick()
        val token = requireNotNull(replay.map.capturePermit(key, replay.now))
        val bare = replay.runtime.emit(
            KernelDomain.MAP, K.FRAME_OBSERVED, FrameObservedPayload(NODE_SELECTION.name, replay.now + 100),
        )
        assertFalse(token.isCurrent(replay.now))
        assertNull(replay.map.currentActionKey)
        assertNull(replay.map.snapshot().verifiedCapturedAtMillis)
        val revoke = replay.last(K.PERMIT_REVOKED)
        assertEquals("external_frame_observed", revoke.reasonCode)
        assertEquals(bare.eventSeq, revoke.causeEventSeq)
        replay.chain(revoke, K.FRAME_OBSERVED, K.PERMIT_REVOKED)
    }

    @Test fun topologyBindingNeedsConsecutiveFramesAndRejectsContradictions() {
        val replay = Replay()
        val spec = PageStabilityRegistry.specFor(NODE_SELECTION)
        assertEquals(3, spec.requiredFrames)
        val drift = requireNotNull(spec.maxCenterDriftPx)
        assertEquals(16, drift)
        fun at(offset: Int) = listOf(candidate.copy(rect = candidate.rect.copy(left = candidate.rect.left + offset)))

        assertTrue(replay.feed(candidates = at(0)) is KernelDecision.Wait)
        assertTrue(replay.feed(candidates = at(drift)) is KernelDecision.Wait)
        assertTrue(replay.events(K.PERMIT_ISSUED).isEmpty())
        assertTrue(replay.feed(candidates = at(2 * drift + 1)) is KernelDecision.Wait)
        assertEquals(1, replay.map.snapshot().stableFrames)
        assertTrue(replay.feed(candidates = at(2 * drift + 1)) is KernelDecision.Wait)
        assertTrue(replay.events(K.PERMIT_ISSUED).isEmpty())
        assertTrue(replay.feed(candidates = at(3 * drift + 1)) is KernelDecision.Act)
        val key = requireNotNull(replay.map.currentActionKey)
        assertEquals(target.nodeId, key.nodeId)
        replay.chain(replay.last(K.PERMIT_ISSUED), K.FRAME_OBSERVED, K.STATE_REDUCED, K.EVENT_DECISION_PROPOSED, K.PERMIT_ISSUED)
        val token = requireNotNull(replay.map.capturePermit(key, replay.now))

        val other = candidate.copy(target = target.copy(nodeId = target.nodeId + 1))
        assertEvidence("node_topology_conflict", replay.feed(candidates = listOf(other)))
        assertEquals(0, replay.map.snapshot().stableFrames)
        assertFalse(token.isCurrent(replay.now))
        assertNull(replay.map.currentActionKey)
        replay.chain(replay.last(), K.FRAME_OBSERVED, K.PERMIT_REVOKED, K.STATE_REDUCED, K.EVENT_DECISION_PROPOSED)
        assertEvidence("node_topology_conflict", replay.feed(candidates = listOf(candidate, other)))
        assertEvidence("node_topology_conflict", replay.feed(topology = topology.copy(reachableSuccessorIds = setOf(other.target.nodeId))))
        assertEvidence("node_topology_conflict", replay.feed(topology = topology.copy(expectedTargetNodeId = other.target.nodeId)))

        assertTrue(replay.feed(topology = MapTopologyEvidence()) is KernelDecision.Wait)
        assertTrue(replay.feed(topology = MapTopologyEvidence(), candidates = listOf(other)) is KernelDecision.Wait)
        assertEquals(1, replay.map.snapshot().stableFrames)
        assertTrue(replay.feed(topology = MapTopologyEvidence(), candidates = listOf(other)) is KernelDecision.Wait)
        assertTrue(replay.feed(topology = MapTopologyEvidence(), candidates = listOf(other)) is KernelDecision.Act)
        assertEquals(other.target.nodeId, replay.map.currentActionKey?.nodeId)
        replay.chain(replay.last(), K.FRAME_OBSERVED, K.EVENT_DECISION_PROPOSED, K.PERMIT_ISSUED)
    }

    @Test fun pendingClickSurvivesModalPageChangesAndCommitsExactlyOnce() {
        val replay = Replay()
        val parent = replay.stack.push(KernelEncounterFrame("entry", KernelEncounterKind.ENTRY))
        val encounter = replay.stack.push(KernelEncounterFrame("map-for-node", KernelEncounterKind.MAP))
        val click = replay.click()
        val pending = requireNotNull(replay.map.pendingNodeTransition)
        assertEquals(target.nodeId, pending.targetNodeId)
        assertEquals(replay.now, pending.clickedAtMillis)
        assertNull(pending.confirmedAtMillis)
        assertNull(replay.map.currentActionKey)
        assertTrue(replay.dialog(UNKNOWN, shop = true, topology = topology) is KernelDecision.Wait)
        assertEquals(pending, replay.map.pendingNodeTransition)
        assertNull(replay.map.currentActionKey)
        assertTrue(replay.dialog(NODE_MAP_VIEW) is KernelDecision.Wait)
        assertEquals(pending, replay.map.pendingNodeTransition)
        assertAct(MapActionKind.MOVE_CONFIRM, replay.dialog(UNKNOWN))
        assertEquals(pending, replay.map.pendingNodeTransition)
        val confirm = replay.dispatch(MapActionKind.MOVE_CONFIRM)
        assertEquals(replay.now, replay.map.pendingNodeTransition?.confirmedAtMillis)
        replay.receipt(confirm)
        val completed = replay.feed(page = EVENT_CHOICE) as KernelDecision.Complete
        assertEquals(encounter.id, completed.encounterId)
        assertEquals(parent, replay.stack.peek())
        assertNull(replay.map.pendingNodeTransition)
        assertEquals(MapNodeArrival(target.nodeId, target.nodeType, EVENT_CHOICE), replay.map.snapshot().committedNode)
        assertTrue(replay.feed(page = EVENT_CHOICE) is KernelDecision.Wait)
        assertNull(replay.map.observeActionReceipt(confirm, true, replay.now))
        val commit = replay.events(K.EVENT_DECISION_PROPOSED).single { it.reasonCode == "node_arrived" }
        val chain = replay.chain(commit, K.FRAME_OBSERVED, K.PERMIT_ISSUED, K.ACTION_DISPATCHED, K.ACTION_RECEIPT_OBSERVED,
            K.FRAME_OBSERVED, K.ACTION_DISPATCHED, K.ACTION_RECEIPT_OBSERVED, K.FRAME_OBSERVED, K.STATE_REDUCED, K.EVENT_DECISION_PROPOSED)
        assertTrue(click in chain)
        assertTrue(confirm in chain)
    }

    @Test fun absentModalAndMissingPostConfirmationEntryExpireTheirOwnWindows() {
        val absent = Replay()
        absent.click()
        assertTrue(absent.feed(at = absent.now + 6_000) is KernelDecision.Wait)
        assertNotNull(absent.map.pendingNodeTransition)
        assertEvidence("move_confirmation_timeout", absent.feed())
        assertNull(absent.map.pendingNodeTransition)
        assertNull(absent.map.snapshot().committedNode)
        absent.chain(absent.last(), K.FRAME_OBSERVED, K.ACTION_DISPATCHED, K.ACTION_RECEIPT_OBSERVED,
            K.FRAME_OBSERVED, K.STATE_REDUCED, K.EVENT_DECISION_PROPOSED)

        val missing = Replay()
        missing.clickAndConfirm()
        val confirmedAt = missing.now
        assertTrue(missing.feed(page = UNKNOWN, at = confirmedAt + 5_999) is KernelDecision.Wait)
        assertEvidence("node_entry_timeout", missing.feed(page = NODE_MAP_VIEW, at = confirmedAt + 6_000))
        assertNull(missing.map.pendingNodeTransition)
        assertNull(missing.map.snapshot().committedNode)
        missing.chain(missing.last(), K.FRAME_OBSERVED, K.ACTION_DISPATCHED,
            K.ACTION_RECEIPT_OBSERVED, K.FRAME_OBSERVED, K.STATE_REDUCED, K.EVENT_DECISION_PROPOSED)
    }

    @Test fun stableMismatchReconcilesConcreteDestinationAndClearsUnrelatedPages() {
        val replay = Replay()
        replay.clickAndConfirm()
        assertTrue(replay.feed(page = RELIC_CHOICE) is KernelDecision.Wait)
        assertTrue(replay.feed(page = UNKNOWN) is KernelDecision.Wait)
        repeat(2) {
            assertTrue(replay.feed(page = RELIC_CHOICE) is KernelDecision.Wait)
            assertNotNull(replay.map.pendingNodeTransition)
        }
        assertTrue(replay.feed(page = RELIC_CHOICE) is KernelDecision.Complete)
        assertEquals(MapNodeArrival(target.nodeId, Node.RELIC, RELIC_CHOICE), replay.map.snapshot().committedNode)
        assertNull(replay.map.pendingNodeTransition)
        assertEquals("node_entry_reconciled", replay.last().reasonCode)
        replay.chain(replay.last(), K.FRAME_OBSERVED, K.ACTION_DISPATCHED, K.ACTION_RECEIPT_OBSERVED,
            K.FRAME_OBSERVED, K.STATE_REDUCED, K.FRAME_OBSERVED, K.STATE_REDUCED, K.EVENT_DECISION_PROPOSED)

        val unrelated = Replay()
        unrelated.clickAndConfirm()
        repeat(2) { assertTrue(unrelated.feed(page = HOME) is KernelDecision.Wait) }
        assertEvidence("node_entry_mismatch", unrelated.feed(page = HOME))
        assertNull(unrelated.map.pendingNodeTransition)
        assertNull(unrelated.map.snapshot().committedNode)
        unrelated.chain(unrelated.last(), K.FRAME_OBSERVED, K.ACTION_DISPATCHED, K.FRAME_OBSERVED,
            K.STATE_REDUCED, K.EVENT_DECISION_PROPOSED)
    }

    @Test fun rejectedDispatchRollsBackAndExplicitArrivalChecksTheTarget() {
        val replay = Replay()
        val staleKey = replay.armClick()
        val token = requireNotNull(replay.map.capturePermit(staleKey, replay.now))
        assertNull(replay.map.dispatchAction(staleKey, replay.now + 1_201))
        val failed = replay.dispatch(MapActionKind.NODE_CLICK)
        assertNotNull(replay.map.pendingNodeTransition)
        assertNull(replay.map.dispatchAction(staleKey, replay.now))
        assertTrue(token.isCurrent(replay.now))
        replay.receipt(failed, false)
        assertFalse(token.isCurrent(replay.now))
        assertNull(replay.map.pendingNodeTransition)
        replay.chain(replay.last(), K.FRAME_OBSERVED, K.PERMIT_ISSUED, K.ACTION_DISPATCHED, K.ACTION_RECEIPT_OBSERVED)

        replay.click()
        assertTrue(replay.dialog() is KernelDecision.Wait)
        assertAct(MapActionKind.MOVE_CONFIRM, replay.dialog())
        val confirm = replay.dispatch(MapActionKind.MOVE_CONFIRM)
        replay.receipt(confirm, false)
        assertNull(replay.map.pendingNodeTransition?.confirmedAtMillis)
        assertEquals(0, replay.map.pendingNodeTransition?.confirmationAttempts)
        replay.feed(page = UNKNOWN)
        assertEvidence("arrival_target_conflict", replay.map.confirmArrival(target.nodeId + 1))
        assertTrue(replay.map.confirmArrival(target.nodeId) is KernelDecision.Complete)
        replay.chain(replay.last(), K.FRAME_OBSERVED, K.ACTION_DISPATCHED, K.ACTION_RECEIPT_OBSERVED,
            K.FRAME_OBSERVED, K.STATE_REDUCED, K.EVENT_DECISION_PROPOSED)
    }

    @Test fun orphanCancelHonorsShopGuardStabilityCooldownAndClearBudget() {
        val replay = Replay()
        repeat(2) {
            assertTrue(replay.dialog(UNKNOWN, shop = true, topology = topology) is KernelDecision.Wait)
            assertNull(replay.map.pendingNodeTransition)
            assertNull(replay.map.currentActionKey)
        }
        assertTrue(replay.dialog(UNKNOWN) is KernelDecision.Wait)
        assertAct(MapActionKind.ORPHAN_DISMISS, replay.dialog(UNKNOWN))
        val first = replay.execute(MapActionKind.ORPHAN_DISMISS)
        replay.chain(replay.last(), K.FRAME_OBSERVED, K.FRAME_OBSERVED,
            K.EVENT_DECISION_PROPOSED, K.ACTION_DISPATCHED, K.ACTION_RECEIPT_OBSERVED)
        assertEquals(1, replay.map.snapshot().orphanDismissAttempts)
        assertTrue(replay.dialog(UNKNOWN) is KernelDecision.Wait)
        assertTrue(replay.dialog(UNKNOWN) is KernelDecision.Wait)
        assertNull(replay.map.currentActionKey)
        assertAct(MapActionKind.ORPHAN_DISMISS, replay.dialog(UNKNOWN, at = first.timestampMillis + 2_000))
        replay.execute(MapActionKind.ORPHAN_DISMISS)
        assertTrue(replay.dialog(UNKNOWN) is KernelDecision.Wait)
        assertAct(MapActionKind.ORPHAN_DISMISS, replay.dialog(UNKNOWN, at = replay.now + 2_000))
        replay.execute(MapActionKind.ORPHAN_DISMISS)
        assertEvidence("orphan_dismiss_exhausted", replay.dialog(UNKNOWN))
        assertEquals(3, replay.map.snapshot().orphanDismissAttempts)
        assertNull(replay.map.currentActionKey)
        replay.chain(replay.last(), K.FRAME_OBSERVED, K.STATE_REDUCED, K.EVENT_DECISION_PROPOSED)
        assertTrue(replay.dialog(UNKNOWN, shop = true) is KernelDecision.Wait)
        assertNull(replay.map.currentActionKey)

        repeat(2) { replay.feed(candidates = emptyList()) }
        assertEquals(3, replay.map.snapshot().orphanDismissAttempts)
        assertEquals(2, replay.map.snapshot().orphanClearFrames)
        replay.feed(page = UNKNOWN)
        assertEquals(0, replay.map.snapshot().orphanClearFrames)
        repeat(3) { replay.feed(candidates = emptyList()) }
        assertEquals(0, replay.map.snapshot().orphanDismissAttempts)
        val restored = replay.events(K.STATE_REDUCED).single { it.reasonCode == "move_confirmation_budget_restored" }
        replay.chain(restored, K.FRAME_OBSERVED, K.STATE_REDUCED)
        assertTrue(replay.dialog() is KernelDecision.Wait)
        assertAct(MapActionKind.ORPHAN_DISMISS, replay.dialog())
    }

    @Test fun onlyStrongUniqueReachableRouteRecoversAnOrphan() {
        val replay = Replay()
        val ambiguous = topology.copy(reachableSuccessorIds = setOf(target.nodeId, target.nodeId + 1))
        assertTrue(replay.dialog(topology = ambiguous) is KernelDecision.Wait)
        assertNull(replay.map.pendingNodeTransition)
        assertAct(MapActionKind.ORPHAN_DISMISS, replay.dialog(topology = topology, confidence = 0.84))
        assertNull(replay.map.pendingNodeTransition)
        assertTrue(replay.dialog(topology = topology, confidence = 0.85) is KernelDecision.Wait)
        assertNull(replay.map.pendingNodeTransition?.clickedAtMillis)
        assertAct(MapActionKind.MOVE_CONFIRM, replay.dialog(topology = topology))
        replay.execute(MapActionKind.MOVE_CONFIRM)
        assertTrue(replay.feed(page = EVENT_CHOICE) is KernelDecision.Complete)
        val chain = replay.chain(replay.last(), K.FRAME_OBSERVED, K.STATE_REDUCED, K.ACTION_DISPATCHED, K.ACTION_RECEIPT_OBSERVED,
            K.FRAME_OBSERVED, K.STATE_REDUCED, K.EVENT_DECISION_PROPOSED)
        assertTrue(chain.any { it.reasonCode == "orphan_move_recovered" })
    }

    @Test fun nudgeRequiresFreshStallFramesAndStopsAfterTwoDirectionCycles() {
        val replay = Replay()
        replay.feed(candidates = emptyList())
        repeat(2) { assertTrue(replay.nudge() is KernelDecision.Wait) }
        assertEquals(1, replay.map.snapshot().nudgeStallFrames)
        val expected = listOf(BACKWARD, FORWARD, FORWARD, BACKWARD, BACKWARD, FORWARD, FORWARD, BACKWARD)
        for ((index, direction) in expected.withIndex()) {
            val interval = if (index == 1) 300L else 500L
            repeat(if (index == 0) 2 else 3) {
                replay.feed(candidates = emptyList(), at = replay.now + interval)
                assertTrue(replay.nudge() is KernelDecision.Wait)
            }
            replay.feed(candidates = emptyList(), at = replay.now + interval)
            var decision = replay.nudge()
            if (index == 1) {
                assertEquals("nudge_cooldown", (decision as KernelDecision.Wait).reason)
                replay.feed(candidates = emptyList(), at = replay.now + interval)
                decision = replay.nudge()
            }
            assertAct(MapActionKind.MAP_NUDGE, decision)
            assertEquals(direction, replay.map.currentActionKey?.direction)
            replay.execute(MapActionKind.MAP_NUDGE)
            assertEquals(index + 1, replay.map.snapshot().nudgeAttempts)
            replay.chain(replay.last(), K.FRAME_OBSERVED, K.FRAME_OBSERVED, K.FRAME_OBSERVED, K.FRAME_OBSERVED,
                K.EVENT_DECISION_PROPOSED, K.ACTION_DISPATCHED, K.ACTION_RECEIPT_OBSERVED)
        }
        replay.feed(candidates = emptyList())
        assertEvidence("nudge_exhausted", replay.nudge())
        assertNull(replay.map.currentActionKey)
        replay.chain(replay.last(), K.FRAME_OBSERVED, K.STATE_REDUCED, K.EVENT_DECISION_PROPOSED)
    }

    @Test fun scrollWaitsForViewportAndCooldownAndBoundsBlindSearch() {
        for (viewport in listOf("map-column", "")) {
            val replay = Replay()
            replay.feed(candidates = emptyList(), viewport = viewport)
            assertTrue(replay.map.requestNodeMapScroll(target.nodeId, FORWARD, awaitingSwipeOutcome = true) is KernelDecision.Wait)
            assertEquals(0, replay.map.snapshot().scrollStableFrames)
            var result: KernelDecision
            for (step in 0 until 6) {
                repeat(4) {
                    replay.feed(candidates = emptyList(), viewport = viewport, at = replay.now + 375)
                    assertTrue(replay.scroll() is KernelDecision.Wait)
                }
                replay.feed(candidates = emptyList(), viewport = viewport, at = replay.now + 375)
                result = replay.scroll()
                if (step > 0) {
                    assertEquals("scroll_cooldown", (result as KernelDecision.Wait).reason)
                    replay.feed(candidates = emptyList(), viewport = viewport, at = replay.now + 625)
                    result = replay.scroll()
                }
                assertAct(MapActionKind.MAP_SCROLL, result)
                assertEquals(if (viewport.isEmpty() && step >= 3) BACKWARD else FORWARD, replay.map.currentActionKey?.direction)
                replay.execute(MapActionKind.MAP_SCROLL)
                replay.chain(replay.last(), K.FRAME_OBSERVED, K.FRAME_OBSERVED, K.FRAME_OBSERVED, K.FRAME_OBSERVED,
                    K.FRAME_OBSERVED, K.EVENT_DECISION_PROPOSED, K.ACTION_DISPATCHED, K.ACTION_RECEIPT_OBSERVED)
            }
            replay.feed(candidates = emptyList(), viewport = viewport)
            val code = if (viewport.isEmpty()) "blind_scroll_exhausted" else "scroll_exhausted"
            assertEvidence(code, replay.scroll())
            assertNull(replay.map.currentActionKey)
            assertEquals(6, replay.map.snapshot().scrollAttempts)
            assertEquals(if (viewport.isEmpty()) 6 else 0, replay.map.snapshot().blindScrollAttempts)
            replay.chain(replay.last(), K.FRAME_OBSERVED, K.STATE_REDUCED, K.EVENT_DECISION_PROPOSED)
        }
    }

    @Test fun targetedResetsIsolateStateAndSessionResetClearsEverything() {
        val replay = Replay()
        replay.feed(candidates = emptyList())
        assertAct(MapActionKind.MAP_NUDGE, replay.map.requestNodeMapNudge(target.nodeId, MapStallReason.SCROLL_EXHAUSTED))
        replay.execute(MapActionKind.MAP_NUDGE)
        repeat(5) {
            replay.feed(candidates = emptyList(), at = replay.now + 500)
            replay.scroll()
        }
        replay.execute(MapActionKind.MAP_SCROLL)
        assertEquals(1, replay.map.snapshot().blindScrollAttempts)
        assertEquals(1, replay.map.snapshot().nudgeAttempts)
        replay.map.resetScrollGate()
        assertEquals(0, replay.map.snapshot().scrollAttempts)
        assertEquals(0, replay.map.snapshot().blindScrollAttempts)
        assertEquals(1, replay.map.snapshot().nudgeAttempts)
        replay.feed(candidates = emptyList(), at = replay.now + 2_500)
        replay.scroll()
        replay.map.resetRecoveryNudge()
        assertEquals(0, replay.map.snapshot().nudgeAttempts)
        assertEquals(1, replay.map.snapshot().scrollStableFrames)

        val key = replay.armClick()
        val token = requireNotNull(replay.map.capturePermit(key, replay.now))
        replay.map.resetStability()
        assertEquals(0, replay.map.snapshot().stableFrames)
        assertFalse(token.isCurrent(replay.now))
        assertNull(replay.map.currentActionKey)
        assertEquals(1, replay.map.snapshot().scrollStableFrames)
        replay.chain(replay.last(), K.FRAME_OBSERVED, K.PERMIT_ISSUED, K.PERMIT_REVOKED)

        replay.click()
        assertTrue(replay.dialog() is KernelDecision.Wait)
        assertEquals(1, replay.map.snapshot().confirmationStableFrames)
        val pending = replay.map.pendingNodeTransition
        replay.map.resetMoveConfirmation()
        assertEquals(0, replay.map.snapshot().confirmationStableFrames)
        assertEquals(pending, replay.map.pendingNodeTransition)
        assertTrue(replay.dialog() is KernelDecision.Wait)
        replay.runtime.resetSession("next-map")
        assertEquals(emptySnapshot(), replay.map.snapshot())
        assertNull(replay.map.currentActionKey)

        val live = replay.armClick()
        val liveToken = requireNotNull(replay.map.capturePermit(live, replay.now))
        replay.runtime.resetSession("fresh-map")
        assertFalse(liveToken.isCurrent(replay.now))
        assertEquals(emptySnapshot(), replay.map.snapshot())
        val resetRevoke = replay.last(K.PERMIT_REVOKED)
        assertEquals("next-map", resetRevoke.traceId)
        replay.chain(resetRevoke, K.FRAME_OBSERVED, K.PERMIT_ISSUED, K.PERMIT_REVOKED)
        val replacement = replay.armClick(firstAt = live.capturedAtMillis - 600)
        assertEquals(live.capturedAtMillis, replacement.capturedAtMillis)
        assertNotEquals(live, replacement)
        assertNull(replay.map.dispatchAction(live, replacement.capturedAtMillis))
        assertEquals("fresh-map", replay.chain(replay.last(), K.FRAME_OBSERVED, K.PERMIT_ISSUED).first().traceId)
    }

    private class Replay {
        var now = 0L
            private set
        val runtime = KernelRuntime(clock = { now })
        val stack = KernelEncounterStack(runtime)
        val map = MapDomainModule(runtime, stack)

        init {
            runtime.registerModule(map)
            runtime.resetSession("map-replay")
        }

        fun observation(
            at: Long = now + 300,
            page: LabyrinthEntryPageState = NODE_SELECTION,
            candidates: List<MapNodeCandidate> = listOf(candidate),
            topology: MapTopologyEvidence = MapDomainKernelTest.topology,
            viewport: String = "",
        ) = MapFrameObservation(at, 1280, 720, page, true, candidates, topology, viewportSignature = viewport)

        fun feed(observation: MapFrameObservation): KernelDecision {
            now = maxOf(now, observation.capturedAtMillis)
            return map.observeFrame(observation)
        }

        fun feed(
            page: LabyrinthEntryPageState = NODE_SELECTION,
            candidates: List<MapNodeCandidate> = listOf(candidate),
            topology: MapTopologyEvidence = MapDomainKernelTest.topology,
            viewport: String = "",
            at: Long = now + 300,
        ): KernelDecision = feed(observation(at, page, candidates, topology, viewport))

        fun dialog(
            page: LabyrinthEntryPageState = NODE_SELECTION,
            shop: Boolean = false,
            topology: MapTopologyEvidence = MapTopologyEvidence(),
            confidence: Double = 0.95,
            at: Long = now + 300,
        ): KernelDecision {
            val observation = observation(at, page, emptyList(), topology).copy(
                moveConfirmation = LabyrinthNodeMoveConfirmationObservation(confidence, confirmRect, cancelRect),
                shopBackgroundVisible = shop,
            )
            now = maxOf(now, at)
            return map.handleMoveConfirmation(observation)
        }

        fun armClick(firstAt: Long = now + 300): MapActionKey {
            assertTrue(feed(at = firstAt) is KernelDecision.Wait)
            assertTrue(feed(at = firstAt + 300) is KernelDecision.Wait)
            assertAct(MapActionKind.NODE_CLICK, feed(at = firstAt + 600))
            return requireNotNull(map.currentActionKey)
        }

        fun click(): KernelEvent {
            armClick()
            return execute(MapActionKind.NODE_CLICK)
        }

        fun clickAndConfirm() {
            click()
            assertTrue(dialog() is KernelDecision.Wait)
            assertAct(MapActionKind.MOVE_CONFIRM, dialog())
            execute(MapActionKind.MOVE_CONFIRM)
        }

        fun dispatch(kind: MapActionKind): KernelEvent {
            val key = requireNotNull(map.currentActionKey)
            assertEquals(kind, key.actionKind)
            return requireNotNull(map.dispatchAction(key, key.capturedAtMillis))
        }

        fun execute(kind: MapActionKind): KernelEvent {
            val dispatch = dispatch(kind)
            receipt(dispatch)
            return dispatch
        }

        fun receipt(dispatch: KernelEvent, executed: Boolean = true) =
            requireNotNull(map.observeActionReceipt(dispatch, executed, now))

        fun nudge() = map.requestNodeMapNudge(target.nodeId, MapStallReason.TOPOLOGY_CONFLICT)
        fun scroll() = map.requestNodeMapScroll(target.nodeId, FORWARD)
        fun events(kind: String? = null) = runtime.log.filter(domain = KernelDomain.MAP, kind = kind)
        fun last(kind: String? = null) = events(kind).last()

        fun chain(event: KernelEvent, vararg expectedKinds: String): List<KernelEvent> {
            val chain = runtime.log.causalChain(event.eventSeq)
            assertEquals(event, chain.last())
            assertEquals(K.FRAME_OBSERVED, chain.first().kind)
            assertNull(chain.first().causeEventSeq)
            assertTrue(chain.all { it.traceId == event.traceId })
            chain.zipWithNext().forEach { (parent, child) ->
                assertEquals(parent.eventSeq, child.causeEventSeq)
                assertTrue(parent.eventSeq < child.eventSeq)
            }
            var matched = 0
            for (link in chain) {
                if (matched < expectedKinds.size && link.kind == expectedKinds[matched]) matched++
            }
            assertEquals(chain.map { it.kind }.toString(), expectedKinds.size, matched)
            return chain
        }
    }

    private companion object {
        val target = MapNodeTarget(10201L, Node.EVENT)
        val candidate = MapNodeCandidate(target, EntryPixelRect(210, 180, 50, 60))
        val topology = MapTopologyEvidence(target.nodeId, target, setOf(target.nodeId))
        val confirmRect = EntryPixelRect(660, 460, 240, 70)
        val cancelRect = EntryPixelRect(360, 460, 240, 70)

        fun assertAct(kind: MapActionKind, decision: KernelDecision) {
            assertTrue(decision.toString(), decision is KernelDecision.Act)
            assertEquals(kind.name.lowercase(), (decision as KernelDecision.Act).actionKind)
        }

        fun assertEvidence(code: String, decision: KernelDecision) {
            assertTrue(decision.toString(), decision is KernelDecision.NeedEvidence)
            assertEquals(code, (decision as KernelDecision.NeedEvidence).error.code)
        }

        fun emptySnapshot() = MapDomainSnapshot(null, null, null, 0, null, null, 0, 0, 0, 0, 0, 0, 0, 0)
    }
}
