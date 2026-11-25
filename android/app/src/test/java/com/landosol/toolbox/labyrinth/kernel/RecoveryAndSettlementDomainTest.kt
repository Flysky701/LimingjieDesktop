package com.landosol.toolbox.labyrinth.kernel

import com.landosol.toolbox.automation.AutomationAction
import com.landosol.toolbox.automation.AutomationActionResult
import com.landosol.toolbox.automation.ScreenPoint
import com.landosol.toolbox.automation.accessibility.GAME_NOT_FOREGROUND_REASON
import com.landosol.toolbox.automation.session.SessionBlockKind
import com.landosol.toolbox.labyrinth.LabyrinthFallbackTap
import com.landosol.toolbox.labyrinth.LabyrinthPostBossStage as Stage
import com.landosol.toolbox.labyrinth.LabyrinthPostEntryActionKind as ActionKind
import com.landosol.toolbox.labyrinth.kernel.KernelEventKinds as K
import com.landosol.toolbox.labyrinth.kernel.recovery.RecoveryDomainModule
import com.landosol.toolbox.labyrinth.kernel.settlement.SettlementDomainModule
import com.landosol.toolbox.labyrinth.kernel.settlement.SettlementPlan
import com.landosol.toolbox.labyrinth.node.LabyrinthNodeTypes
import com.landosol.toolbox.labyrinth.vision.EntryAnchorId
import com.landosol.toolbox.labyrinth.vision.EntryAnchorMatch
import com.landosol.toolbox.labyrinth.vision.EntryPixelRect
import com.landosol.toolbox.labyrinth.vision.LabyrinthAnchorScores
import com.landosol.toolbox.labyrinth.vision.LabyrinthEntryFrameResult
import com.landosol.toolbox.labyrinth.vision.LabyrinthEntryPageObservation
import com.landosol.toolbox.labyrinth.vision.LabyrinthEntryPageState as Page
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecoveryAndSettlementDomainTest {
    private val runtime = KernelRuntime(clock = { 0L })
    private val recovery = RecoveryDomainModule(runtime)
    private val settlement = SettlementDomainModule(runtime)
    private val root = runtime.run {
        registerModule(recovery)
        registerModule(settlement)
        resetSession("first")
    }
    private val returnRect = EntryPixelRect(756, 692, 410, 94)
    private val executed = AutomationActionResult.Executed(AutomationAction.Tap(ScreenPoint(960f, 780f)))

    @Test
    fun recoveryRequiresConsecutiveButtonFramesAndStopsAfterSixDispatches() {
        val missing = reconnect(withButton = false)
        val reconnect = reconnect()
        val firstFrame = runtime.emit(KernelDomain.ENTRY, K.FRAME_OBSERVED, FrameObservedPayload(Page.UNKNOWN.name, 100), root)
        val blocked = evaluateBlock(missing, 100, parentEvent = firstFrame)
        assertTrue(blocked.blocked)
        assertTrue(blocked.decision is KernelDecision.Wait)
        assertEquals(0, recovery.returnTitleStableFrames)
        assertEquals(firstFrame.eventSeq, blocked.event?.causeEventSeq)

        assertTrue(evaluateBlock(reconnect, 200).decision is KernelDecision.Wait)
        evaluateBlock(missing, 300)
        assertEquals(3, recovery.blockStableFrames)
        assertEquals(0, recovery.returnTitleStableFrames)
        assertTrue(evaluateBlock(reconnect, 400).decision is KernelDecision.Wait)

        repeat(6) { index ->
            val at = 500L + index * 1_500
            val proposal = evaluateBlock(reconnect, at)
            assertTrue(proposal.blocked)
            assertTrue(proposal.decision is KernelDecision.Act)
            assertEquals(SessionBlockKind.RECONNECT_PROMPTED, proposal.kind)
            assertEquals(returnRect, proposal.tapRect)
            assertEquals("返回标题", proposal.actionLabel)
            val permit = requireNotNull(proposal.permit)
            assertTrue(permit.isCurrent(at))
            val dispatch = requireNotNull(recovery.recordReturnTitleDispatched(proposal, at))
            assertFalse(permit.isCurrent(at))
            assertNull(recovery.recordReturnTitleDispatched(proposal, at))
            assertTrue(evaluateBlock(reconnect, at + 100).decision.let {
                it is KernelDecision.Wait || it is KernelDecision.NeedEvidence
            })
            val receipt = requireNotNull(recovery.recordReturnTitleReceipt(dispatch, executed))
            assertEquals(dispatch.eventSeq, receipt.causeEventSeq)
            val chain = assertCausalChain(receipt)
            assertEquals(listOf(K.PERMIT_ISSUED, K.ACTION_DISPATCHED, K.ACTION_RECEIPT_OBSERVED), chain.takeLast(3).map { it.kind })
            assertEquals(ActionReceiptPayload("RETURN_TITLE", "Executed"), receipt.payload)
            assertEquals(index + 1, recovery.returnTitleAttempts)
            assertEquals(at, recovery.lastReturnTitleActionAt)
            if (index < 5) {
                val cooling = evaluateBlock(reconnect, at + 1_499)
                assertTrue(cooling.decision is KernelDecision.Wait)
                assertNull(cooling.permit)
            }
        }
        val exhausted = evaluateBlock(reconnect, 12_000)
        assertTrue(exhausted.blocked)
        assertEquals("return_title_attempt_limit", (exhausted.decision as KernelDecision.NeedEvidence).error.code)
        assertCausalChain(requireNotNull(exhausted.event))

        assertFalse(evaluateBlock(frame(Page.HOME), 12_100).blocked)
        assertEquals(SessionBlockKind.NONE, recovery.lastBlockKind)
        assertEquals(0, recovery.blockStableFrames)
        assertEquals(0, recovery.returnTitleStableFrames)
        assertEquals(0, recovery.returnTitleAttempts)
        assertEquals(Long.MIN_VALUE, recovery.lastReturnTitleActionAt)
    }

    @Test
    fun shopTransitionClearsRecoveryAndSuppressesOnlyWithinItsGuard() {
        val result = reconnect()
        evaluateBlock(result, 100)
        val proposal = evaluateBlock(result, 200)
        val suppressed = evaluateBlock(result, 4_000, Page.SHOP_PURCHASE_COMPLETE, LabyrinthNodeTypes.SHOP, 1_000)
        assertFalse(suppressed.blocked)
        assertEquals(SessionBlockKind.NONE, recovery.lastBlockKind)
        assertEquals(0, recovery.blockStableFrames)
        assertFalse(requireNotNull(proposal.permit).isCurrent(200))
        assertEquals("shop_transition", requireNotNull(suppressed.event).reasonCode)

        val expired = evaluateBlock(result, 4_001, Page.SHOP_PURCHASE_COMPLETE, LabyrinthNodeTypes.SHOP, 1_000)
        assertTrue(expired.blocked)
        assertTrue(expired.decision is KernelDecision.Wait)
        assertEquals(1, recovery.returnTitleStableFrames)
    }

    @Test
    fun recoveryHonorsModalOwnershipDryRunBoundsAndForegroundRejection() {
        val ambiguous = frame(Page.UNKNOWN, scores = mapOf("session.prompt" to 0.90))
        val unknown = evaluateBlock(ambiguous, 100)
        assertTrue(unknown.blocked)
        assertEquals(SessionBlockKind.UNKNOWN_PROMPT, unknown.kind)
        assertTrue(unknown.decision is KernelDecision.Wait)

        val outOfBounds = reconnect(rect = EntryPixelRect(WIDTH - 1, 700, 400, 90))
        evaluateBlock(outOfBounds, 200)
        assertTrue(evaluateBlock(outOfBounds, 300).decision is KernelDecision.Wait)
        assertTrue(evaluateBlock(reconnect(), 400, dryRun = true).decision is KernelDecision.Wait)
        val proposal = evaluateBlock(reconnect(), 500)
        val channel = evaluateBlock(reconnect(), 600, channelPromptActive = true)
        assertTrue(channel.blocked)
        assertTrue(channel.decision is KernelDecision.Wait)
        assertNull(recovery.recordReturnTitleDispatched(proposal, 600))

        val dispatch = requireNotNull(recovery.recordReturnTitleDispatched(evaluateBlock(reconnect(), 700), 700))
        val rejected = AutomationActionResult.Rejected(executed.action, GAME_NOT_FOREGROUND_REASON)
        assertCausalChain(requireNotNull(recovery.recordReturnTitleReceipt(dispatch, rejected)))
        assertEquals(0, recovery.returnTitleAttempts)
        assertEquals(Long.MIN_VALUE, recovery.lastReturnTitleActionAt)
        assertTrue(evaluateBlock(reconnect(), 701).decision is KernelDecision.Act)
    }

    @Test
    fun watchdogsUseOriginalTimeoutBoundariesAndFrameCauses() {
        assertNull(recovery.evaluateActionLatch(false, 0, 60_000))
        assertNull(recovery.evaluateActionLatch(true, Long.MIN_VALUE, 60_000))
        assertNull(recovery.evaluateActionLatch(true, 0, 59_999))
        val frameEvent = runtime.emit(KernelDomain.ENTRY, K.FRAME_OBSERVED, FrameObservedPayload(Page.UNKNOWN.name, 60_000), root)
        val stuck = requireNotNull(recovery.evaluateActionLatch(true, 0, 60_000, parentEvent = frameEvent))
        val event = runtime.log.filter(reasonCode = stuck.error.code).single()
        assertEquals(KernelErrorCategory.TIMEOUT, stuck.error.category)
        assertEquals(frameEvent.eventSeq, event.causeEventSeq)
        assertCausalChain(event)

        val unknown = frame(Page.UNKNOWN)
        assertNull(recovery.evaluatePostEntryUnknownTimeout(unknown, 1_000, false, false))
        assertNull(recovery.evaluatePostEntryUnknownTimeout(unknown, 31_000, false, false))
        assertNull(recovery.evaluatePostEntryUnknownTimeout(unknown, 31_001, true, false))
        assertNull(recovery.evaluatePostEntryUnknownTimeout(unknown, 31_002, false, true))
        assertNull(recovery.evaluatePostEntryUnknownTimeout(frame(Page.NODE_MAP_VIEW), 31_003, false, false))
        assertNull(recovery.evaluatePostEntryUnknownTimeout(unknown, 40_000, false, false))
        val timeout = requireNotNull(recovery.evaluatePostEntryUnknownTimeout(unknown, 70_001, false, false))
        assertEquals("post_entry_unknown_timeout", timeout.error.code)
        assertCausalChain(runtime.log.filter(reasonCode = timeout.error.code).single())
    }

    @Test
    fun settlementFinishesOnlyAfterScoreChestItemAndStableIdleHome() {
        val page = Page.RUN_CLEAR_RESULT
        val frameEvent = runtime.emit(KernelDomain.ENTRY, K.FRAME_OBSERVED, FrameObservedPayload(page.name, 1_000), root)
        val observed = settlement.onPageObserved(page, 2, 1_000, parentEvent = frameEvent)
        assertEquals(frameEvent.eventSeq, observed.event?.causeEventSeq)
        val score = settlement.planTap(frame(page), page, WIDTH, HEIGHT, 0, 6, null)
        assertEquals(Stage.SCORE_RESULT, settlement.stage)
        assertEquals(ActionKind.CLOSE_FINAL_SCORE, score.tapPlan?.kind)
        val scoreReceipt = commit(score, 1_000)
        assertEquals(Stage.CHEST_SEQUENCE, settlement.stage)

        settlement.onPageObserved(page, 1, 1_050)
        assertEquals(Stage.CHEST_SEQUENCE, settlement.stage)
        assertFalse(settlement.chestObserved)
        assertNull(settlement.planTap(frame(page), page, WIDTH, HEIGHT, 0, 6, null).tapPlan)

        val stillScore = observeAndPlan(Page.RUN_CLEAR_RESULT, 1_100)
        assertEquals(Stage.SCORE_RESULT, settlement.stage)
        assertEquals(ActionKind.CLOSE_FINAL_SCORE, stillScore.tapPlan?.kind)
        val chest = observeAndPlan(Page.RUN_CLEAR_CHEST_ANIMATION, 1_200)
        assertFalse(requireNotNull(stillScore.permit).isCurrent(1_200))
        assertEquals(Stage.CHEST_SEQUENCE, settlement.stage)
        assertTrue(settlement.chestObserved)
        assertEquals(ActionKind.ADVANCE_FINAL_SETTLEMENT, chest.tapPlan?.kind)
        commit(chest, 1_200)

        assertNull(observeAndPlan(Page.RUN_CLEAR_RESULT, 1_250).tapPlan)
        assertEquals(Stage.CHEST_SEQUENCE, settlement.stage)

        val chestResult = observeAndPlan(Page.RUN_CLEAR_CHEST_RESULT, 1_300)
        assertEquals(LabyrinthFallbackTap.BOTTOM_CENTER.rect(WIDTH, HEIGHT), chestResult.tapPlan?.rect)
        commit(chestResult, 1_300)
        val item = observeAndPlan(Page.ITEM_REWARD, 1_400)
        assertEquals(Stage.FINAL_ITEM_REWARD, settlement.stage)
        assertEquals(ActionKind.CLOSE_FINAL_ITEM, item.tapPlan?.kind)
        val itemReceipt = commit(item, 1_401)
        assertEquals(Stage.WAITING_FOR_DAWN_HOME, settlement.stage)
        assertEquals(1_401L, settlement.startedAt)
        assertTrue(settlement.onPageObserved(Page.DAWN_REALM_HOME_ACTIVE, 2, 1_500).decision is KernelDecision.Wait)
        assertTrue(settlement.onPageObserved(Page.DAWN_REALM_HOME_IDLE, 1, 1_600).decision is KernelDecision.Wait)
        val finish = settlement.onPageObserved(Page.DAWN_REALM_HOME_IDLE, 2, 1_700)
        assertTrue((finish.decision as KernelDecision.FinishRun).cleared)
        val chain = assertCausalChain(requireNotNull(finish.event))
        assertTrue(frameEvent in chain)
        assertTrue(scoreReceipt in chain)
        assertTrue(itemReceipt in chain)
        assertEquals("final_item_closed", chain[chain.lastIndex - 1].reasonCode)
        assertTrue(runtime.log.filter(domain = KernelDomain.SETTLEMENT, kind = K.PERMIT_REVOKED).isNotEmpty())
    }

    @Test
    fun settlementSkipsUnobservedChestPagesButDoesNotOwnOrdinaryItemRewards() {
        assertTrue(observeAndPlan(Page.ITEM_REWARD, 100).decision is KernelDecision.Wait)
        assertEquals(Stage.NONE, settlement.stage)
        val oldScore = observeAndPlan(Page.RUN_CLEAR_RESULT, 200)
        val item = observeAndPlan(Page.ITEM_REWARD, 300)
        assertEquals(Stage.FINAL_ITEM_REWARD, settlement.stage)
        assertNull(settlement.recordTapDispatched(oldScore, 300))
        assertEquals(ActionKind.CLOSE_FINAL_ITEM, item.tapPlan?.kind)
        val firstDispatch = requireNotNull(settlement.recordTapDispatched(item, 300))
        val rejected = AutomationActionResult.Rejected(executed.action, GAME_NOT_FOREGROUND_REASON)
        val rejectedReceipt = requireNotNull(settlement.recordTapReceipt(firstDispatch, rejected))
        assertEquals(firstDispatch.eventSeq, rejectedReceipt.causeEventSeq)
        assertEquals(Stage.FINAL_ITEM_REWARD, settlement.stage)
        assertTrue(settlement.onPageObserved(Page.DAWN_REALM_HOME_IDLE, 2, 400).decision is KernelDecision.Wait)
        val receipt = commit(observeAndPlan(Page.ITEM_REWARD, 500), 500)
        val finish = settlement.onPageObserved(Page.DAWN_REALM_HOME_IDLE, 2, 600)
        assertTrue((finish.decision as KernelDecision.FinishRun).cleared)
        assertTrue(receipt in assertCausalChain(requireNotNull(finish.event)))
    }

    @Test
    fun settlementReturnHomeTimeoutUsesTheExistingTwentySecondLimit() {
        observeAndPlan(Page.RUN_CLEAR_RESULT, 100)
        val receipt = commit(observeAndPlan(Page.ITEM_REWARD, 200), 200)
        assertTrue(settlement.onPageObserved(Page.UNKNOWN, 2, 20_200).decision is KernelDecision.Wait)
        val timeout = settlement.onPageObserved(Page.UNKNOWN, 2, 20_201)
        assertEquals("final_home_return_timeout", (timeout.decision as KernelDecision.NeedEvidence).error.code)
        assertTrue(receipt in assertCausalChain(requireNotNull(timeout.event)))
        assertTrue(settlement.onPageObserved(Page.UNKNOWN, 2, 25_201).decision is KernelDecision.NeedEvidence)
    }

    @Test
    fun settlementPlansAnimationsAndBoundsUnknownProbes() {
        settlement.beginFinalSettlement(100)
        val animationFallbacks = mapOf(
            Page.RUN_CLEAR_CONGRATULATIONS to LabyrinthFallbackTap.CENTER,
            Page.RUN_CLEAR_CHARACTER_SUMMARY to LabyrinthFallbackTap.BOTTOM_RIGHT,
            Page.RUN_CLEAR_REWARD_ANIMATION to LabyrinthFallbackTap.BOTTOM_CENTER,
        )
        for ((page, fallback) in animationFallbacks) {
            val plan = observeAndPlan(page, 200)
            assertEquals(ActionKind.ADVANCE_FINAL_SETTLEMENT, plan.tapPlan?.kind)
            assertEquals(fallback.rect(WIDTH, HEIGHT), plan.tapPlan?.rect)
        }
        val firstProbe = observeAndPlan(Page.UNKNOWN, 300)
        assertEquals(LabyrinthFallbackTap.CENTER.rect(WIDTH, HEIGHT), firstProbe.tapPlan?.rect)
        commit(firstProbe, 300)
        assertEquals(1, settlement.unknownAttempts)
        val secondProbe = observeAndPlan(Page.UNKNOWN, 400, attempts = 1, maxAttempts = 2)
        assertEquals(LabyrinthFallbackTap.BOTTOM_RIGHT.rect(WIDTH, HEIGHT), secondProbe.tapPlan?.rect)
        commit(secondProbe, 400)
        assertEquals(2, settlement.unknownAttempts)
        val exhausted = observeAndPlan(Page.UNKNOWN, 500, attempts = 2, maxAttempts = 2)
        assertTrue(exhausted.decision is KernelDecision.NeedEvidence)
        assertCausalChain(requireNotNull(exhausted.event))

        val bossNext = EntryPixelRect(1_500, 900, 350, 100)
        val bossPlan = observeAndPlan(Page.UNKNOWN, 600, bossNext = bossNext)
        assertEquals(ActionKind.BOSS_SETTLEMENT_NEXT, bossPlan.tapPlan?.kind)
        assertEquals(bossNext, bossPlan.tapPlan?.rect)
        commit(bossPlan, 600)
        assertEquals(2, settlement.unknownAttempts)

        val battle = frame(Page.UNKNOWN, scores = mapOf(EntryAnchorId.BATTLE_IN_PROGRESS_MENU_BUTTON to 0.9))
        settlement.onPageObserved(Page.UNKNOWN, 2, 700)
        assertTrue(settlement.planTap(battle, Page.UNKNOWN, WIDTH, HEIGHT, 0, 40, bossNext).decision is KernelDecision.Wait)
        val next = observeAndPlan(Page.UNKNOWN, 800)
        val permit = requireNotNull(next.permit)
        assertTrue(permit.isCurrent(800))
        runtime.resetSession("second")
        assertFalse(permit.isCurrent(800))
        assertNull(settlement.recordTapDispatched(next, 800))
        assertEquals(Stage.NONE, settlement.stage)
        assertEquals(0, settlement.unknownAttempts)
        assertEquals(Long.MIN_VALUE, settlement.startedAt)
    }

    private fun assertCausalChain(leaf: KernelEvent): List<KernelEvent> {
        val chain = runtime.log.causalChain(leaf.eventSeq)
        assertEquals(root, chain.first())
        assertEquals(leaf, chain.last())
        assertTrue(chain.size > 1)
        chain.zipWithNext().forEach { (parent, child) -> assertEquals(parent.eventSeq, child.causeEventSeq) }
        return chain
    }

    private fun commit(plan: SettlementPlan, at: Long): KernelEvent {
        assertTrue(plan.decision is KernelDecision.Act)
        val permit = requireNotNull(plan.permit)
        assertTrue(permit.isCurrent(at))
        val dispatch = requireNotNull(settlement.recordTapDispatched(plan, at))
        assertFalse(permit.isCurrent(at))
        val receipt = requireNotNull(settlement.recordTapReceipt(dispatch, executed))
        assertEquals(dispatch.eventSeq, receipt.causeEventSeq)
        assertEquals(listOf(K.PERMIT_ISSUED, K.ACTION_DISPATCHED, K.ACTION_RECEIPT_OBSERVED),
            assertCausalChain(receipt).takeLast(3).map { it.kind })
        return receipt
    }

    private fun observeAndPlan(
        page: Page,
        at: Long,
        attempts: Int = 0,
        maxAttempts: Int = 6,
        bossNext: EntryPixelRect? = null,
    ): SettlementPlan {
        settlement.onPageObserved(page, 2, at)
        return settlement.planTap(frame(page), page, WIDTH, HEIGHT, attempts, maxAttempts, bossNext)
    }

    private fun evaluateBlock(
        result: LabyrinthEntryFrameResult,
        at: Long,
        previous: Page? = null,
        node: Int? = null,
        lastAction: Long = Long.MIN_VALUE,
        dryRun: Boolean = false,
        channelPromptActive: Boolean = false,
        parentEvent: KernelEvent? = null,
    ) = recovery.evaluateSessionBlock(
        result, previous, node, lastAction, WIDTH, HEIGHT, at, dryRun, channelPromptActive, parentEvent = parentEvent,
    )

    private fun reconnect(withButton: Boolean = true, rect: EntryPixelRect = returnRect): LabyrinthEntryFrameResult {
        val matches = if (withButton) mapOf(EntryAnchorId.SESSION_RETURN_TITLE to EntryAnchorMatch(0.88, rect)) else emptyMap()
        return frame(Page.UNKNOWN, matches, mapOf(EntryAnchorId.SESSION_ERROR_TITLE to 0.91))
    }

    private fun frame(
        page: Page,
        matches: Map<String, EntryAnchorMatch> = emptyMap(),
        scores: Map<String, Double> = emptyMap(),
    ) = LabyrinthEntryFrameResult(
        observation = LabyrinthEntryPageObservation(
            state = page,
            confidence = 0.9,
            stateScores = mapOf(page to 0.9),
            anchorScores = LabyrinthAnchorScores(matches.mapValues { it.value.score } + scores),
        ),
        matchedFeatures = emptyList(),
        elapsedMillis = 0,
        anchorMatches = matches,
        frameWidth = WIDTH,
        frameHeight = HEIGHT,
    )

    private companion object {
        const val WIDTH = 1_920
        const val HEIGHT = 1_080
    }
}
