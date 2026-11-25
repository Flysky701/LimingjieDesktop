package com.landosol.toolbox.labyrinth.kernel

import com.landosol.toolbox.labyrinth.LabyrinthFallbackTap
import com.landosol.toolbox.labyrinth.LabyrinthRelicMark
import com.landosol.toolbox.labyrinth.LabyrinthRoleChoicePolicy
import com.landosol.toolbox.labyrinth.LabyrinthRoleProfile
import com.landosol.toolbox.labyrinth.LabyrinthRoleRewardChoicePlanner
import com.landosol.toolbox.labyrinth.LabyrinthTeamOptimizer
import com.landosol.toolbox.labyrinth.LabyrinthTeamScorer
import com.landosol.toolbox.labyrinth.LabyrinthTeamScoringConfig
import com.landosol.toolbox.labyrinth.kernel.KernelEventKinds as K
import com.landosol.toolbox.labyrinth.kernel.reward.CharacterAcquisitionState
import com.landosol.toolbox.labyrinth.kernel.reward.EventFreeRoleFrameInput
import com.landosol.toolbox.labyrinth.kernel.reward.RelicChoiceInput
import com.landosol.toolbox.labyrinth.kernel.reward.RelicRewardHoldInput
import com.landosol.toolbox.labyrinth.kernel.reward.RelicRewardHoldOutcome
import com.landosol.toolbox.labyrinth.kernel.reward.RelicRewardHoldState
import com.landosol.toolbox.labyrinth.kernel.reward.RewardActionKind
import com.landosol.toolbox.labyrinth.kernel.reward.RewardStep
import com.landosol.toolbox.labyrinth.kernel.reward.RewardTailModule
import com.landosol.toolbox.labyrinth.kernel.reward.RoleRewardFrameInput
import com.landosol.toolbox.labyrinth.node.LabyrinthNodeTypes
import com.landosol.toolbox.labyrinth.vision.EntryAnchorId
import com.landosol.toolbox.labyrinth.vision.EntryAnchorMatch
import com.landosol.toolbox.labyrinth.vision.EntryPixelRect
import com.landosol.toolbox.labyrinth.vision.LabyrinthAnchorScores
import com.landosol.toolbox.labyrinth.vision.LabyrinthBattleCharacterMatch
import com.landosol.toolbox.labyrinth.vision.LabyrinthBattleElementFilter
import com.landosol.toolbox.labyrinth.vision.LabyrinthBattleScrollbarObservation
import com.landosol.toolbox.labyrinth.vision.LabyrinthBattleTeamObservation
import com.landosol.toolbox.labyrinth.vision.LabyrinthCharacterMatch
import com.landosol.toolbox.labyrinth.vision.LabyrinthEntryFrameResult
import com.landosol.toolbox.labyrinth.vision.LabyrinthEntryPageObservation
import com.landosol.toolbox.labyrinth.vision.LabyrinthEntryPageState as Page
import com.landosol.toolbox.labyrinth.vision.LabyrinthLinkChoiceMatch
import com.landosol.toolbox.labyrinth.vision.LabyrinthLinkChoiceObservation
import com.landosol.toolbox.labyrinth.vision.LabyrinthLinkChoicePriority
import com.landosol.toolbox.labyrinth.vision.LabyrinthLinkElement
import com.landosol.toolbox.labyrinth.vision.LabyrinthRelicChoiceObservation
import com.landosol.toolbox.labyrinth.vision.LabyrinthRelicMatch
import com.landosol.toolbox.labyrinth.vision.LabyrinthRelicRewardKind
import com.landosol.toolbox.labyrinth.vision.LabyrinthRelicRewardObservation
import org.junit.Assert.*
import org.junit.Test

class RewardTailDomainTest {
    @Test fun `relic modal retains missing rows and releases after stable identities are recorded`() {
        val fixture = Fixture()
        val rows = listOf(relic("a", "row-a"), relic("b", "row-b").copy(attribute = "特殊", attributeBonus = "0"))
        val recorded = setOf("a", "b")

        val first = fixture.hold(100, rows, recorded)
        assertTrue(first.holding)
        assertTrue(first.decision is KernelDecision.Wait)
        assertEquals(1, first.state.layoutConfirmations)
        assertEquals(1, fixture.hold(100, rows, recorded).state.layoutConfirmations)

        val missing = fixture.hold(200, rows.take(1), recorded)
        assertTrue(missing.decision is KernelDecision.Wait)
        assertEquals(setOf("row-a", "row-b"), missing.state.rowSlots)
        assertEquals(0, missing.state.layoutConfirmations)
        assertTrue(fixture.hold(300, rows, recorded).holding)
        val unrecorded = fixture.hold(400, rows, setOf("a"))
        assertTrue(unrecorded.decision is KernelDecision.Wait)
        assertEquals(2, unrecorded.state.layoutConfirmations)

        val ready = fixture.hold(500, rows, recorded)
        assertTrue(ready.decision is KernelDecision.Complete)
        assertTrue(ready.state.ready)
        assertFalse(ready.holding)
        action(fixture.module.evaluateItemReward(result(Page.ITEM_REWARD), false, WIDTH, HEIGHT),
            RewardActionKind.CLOSE_ITEM_REWARD)
        assertTrue(fixture.hold(600, rows, recorded, frameVersion = 0).holding)
        assertTrue(fixture.module.evaluateItemReward(result(Page.ITEM_REWARD), false, WIDTH, HEIGHT).decision is KernelDecision.Wait)

        fixture.module.invalidateRelicHold()
        assertEquals(RelicRewardHoldState(), fixture.module.relicRewardHoldState)
        val empty = fixture.module.evaluateRelicRewardHold(RelicRewardHoldInput(null, recorded, 1, 1, 0, 600))
        assertFalse(empty.holding)
        assertTrue(empty.decision is KernelDecision.Complete)
    }

    @Test fun `relic modal times out after eight seconds with an audit chain and still holds in dry run`() {
        val fixture = Fixture()
        fixture.hold(100, emptyList())
        assertTrue(fixture.hold(8_099, emptyList()).decision is KernelDecision.Wait)
        val timeout = fixture.hold(8_100, emptyList())
        assertTrue(timeout.holding)
        assertTrue(timeout.state.timedOut)
        assertEquals(8_000L, timeout.state.elapsedMillis)
        evidence(timeout.decision, "RELIC_REWARD_HOLD_TIMEOUT", KernelErrorCategory.TIMEOUT)
        val event = fixture.runtime.log.filter(reasonCode = "RELIC_REWARD_HOLD_TIMEOUT").last()
        assertEquals(K.FRAME_OBSERVED, fixture.runtime.log.causalChain(event.eventSeq).first().kind)
        assertTrue(fixture.hold(8_101, emptyList(), dryRun = true).decision is KernelDecision.Wait)
    }

    @Test fun `relic choice uses identity and calibration evidence and revokes replaced and departed targets`() {
        val fixture = Fixture()
        val a = relic("a", "left")
        val b = relic("b", "right")
        val unsafe = relic("special", "middle").copy(attribute = "特殊", attributeBonus = "0")
        assertTrue(fixture.relicChoice(100, a, b, unsafe).decision is KernelDecision.Wait)
        val first = fixture.relicChoice(200, a, b, unsafe)
        action(first, RewardActionKind.SELECT_RELIC)
        val firstKey = requireNotNull(first.key)
        val picked = firstKey.targetId
        assertTrue(fixture.module.hasPendingRelicAcquisition(picked))
        val token = requireNotNull(fixture.module.capturePermit(firstKey, 200))
        val remaining = if (picked == "a") b else a

        // A second evaluation can narrow candidates within the same observed frame.
        val changed = fixture.relicChoice(200, remaining, newFrame = false)
        assertEquals(remaining.relicId, action(changed, RewardActionKind.SELECT_RELIC).targetId)
        assertFalse(token.isCurrent(200))
        assertFalse(fixture.module.hasPendingRelicAcquisition(picked))
        val remainingId = requireNotNull(remaining.relicId)
        assertTrue(fixture.module.hasPendingRelicAcquisition(remainingId))
        val changedKey = requireNotNull(changed.key)
        val nextToken = requireNotNull(fixture.module.capturePermit(changedKey, 200))

        fixture.frame(Page.NODE_SELECTION, 300, epoch = 2)
        assertFalse(nextToken.isCurrent(300))
        assertNull(fixture.module.capturePermit(changedKey, 300))
        assertFalse(fixture.module.hasPendingRelicAcquisition(remainingId))
        assertEquals(listOf("target_changed", "reward_page_changed"),
            fixture.runtime.log.filter(kind = K.PERMIT_REVOKED).map { it.reasonCode })
    }

    @Test fun `two relic timeouts retry without keeping the old acquisition and the third exhausts the budget`() {
        val fixture = Fixture()
        val a = relic("a", "left")
        val b = relic("b", "center")
        val c = relic("c", "right")
        assertTrue(fixture.relicChoice(100, a, b, c).decision is KernelDecision.Wait)
        val first = fixture.relicChoice(200, a)
        fixture.execute(first, 200)
        assertTrue(fixture.relicChoice(6_199, b, c).decision is KernelDecision.Wait)
        assertTrue(fixture.module.hasPendingRelicAcquisition("a"))

        val second = fixture.relicChoice(6_200, b)
        assertEquals("b", action(second, RewardActionKind.SELECT_RELIC).targetId)
        assertFalse(fixture.module.hasPendingRelicAcquisition("a"))
        fixture.execute(second, 6_200)
        assertTrue(fixture.relicChoice(12_199, c).decision is KernelDecision.Wait)

        val third = fixture.relicChoice(12_200, c)
        assertEquals("c", action(third, RewardActionKind.SELECT_RELIC).targetId)
        assertFalse(fixture.module.hasPendingRelicAcquisition("b"))
        fixture.execute(third, 12_200)

        val exhausted = fixture.relicChoice(18_200, c)
        evidence(exhausted.decision, "RELIC_CHOICE_COMMIT_EXHAUSTED", KernelErrorCategory.TIMEOUT)
        assertFalse(fixture.module.hasPendingRelicAcquisition("c"))
        assertEquals(2, fixture.runtime.log.filter(kind = K.STATE_REDUCED, reasonCode = "relic_choice_commit_retry").size)
        assertEquals(3, fixture.runtime.log.filter(kind = K.ACTION_DISPATCHED).size)
    }

    @Test fun `link preference uses the observation priority and permits expire on a fresh frame or page change`() {
        val fixture = Fixture()
        val default = LabyrinthLinkChoicePriority.DEFAULT.orderedElements
        val priority = LabyrinthLinkChoicePriority(default.reversed())
        val first = link("left", default.first(), CARD)
        val preferred = link("right", default.last(), SECOND_BUTTON)
        val observation = LabyrinthLinkChoiceObservation(listOf(first, preferred), priority)
        fixture.frame(Page.LINK_CHOICE, 100)
        val step = fixture.module.evaluateLinkChoice(observation, WIDTH, HEIGHT, 100)
        assertEquals("right:${preferred.element.name}", action(step, RewardActionKind.SELECT_LINK_IMPRINT).targetId)
        val key = requireNotNull(step.key)
        val token = requireNotNull(fixture.module.capturePermit(key, 100))

        fixture.frame(Page.LINK_CHOICE, 200)
        assertFalse(token.isCurrent(200))
        assertNull(fixture.module.capturePermit(key, 200))
        val step2 = fixture.module.evaluateLinkChoice(observation, WIDTH, HEIGHT, 200)
        assertNull(fixture.module.dispatchAction(key, 200))
        val second = requireNotNull(fixture.module.capturePermit(requireNotNull(step2.key), 200))
        val invalid = preferred.copy(selectionButtonRect = EntryPixelRect(WIDTH - 1, 10, 10, 10))
        assertTrue(fixture.module.evaluateLinkChoice(observation.copy(choices = listOf(invalid)), WIDTH, HEIGHT, 200)
            .decision is KernelDecision.Wait)
        assertFalse(second.isCurrent(200))
        val restored = fixture.module.evaluateLinkChoice(observation, WIDTH, HEIGHT, 200)
        val last = requireNotNull(fixture.module.capturePermit(requireNotNull(restored.key), 200))
        fixture.frame(Page.NODE_SELECTION, 300)
        assertFalse(last.isCurrent(300))
        assertTrue(fixture.runtime.log.filter(kind = K.PERMIT_REVOKED).any { it.reasonCode == "reward_page_changed" })
    }

    @Test fun `role dispatch locks scoring and a non-executed receipt rebinds the selected role to its current slot`() {
        val fixture = Fixture()
        val first = fixture.role(100, "1010", "1011", "1012")
        val initial = action(first, RewardActionKind.SELECT_ROLE_REWARD)
        val selected = requireNotNull(initial.targetId)
        val firstKey = requireNotNull(first.key)
        val token = requireNotNull(fixture.module.dispatchRoleRewardSelection(firstKey, 100))
        assertEquals(selected, fixture.module.lockedRoleRewardDecision?.characterId)
        assertEquals("DISPATCHED", fixture.module.roleRoundSnapshot().phase)
        assertTrue(fixture.module.roleRewardChoiceCommitted)
        assertNull(fixture.module.dispatchRoleRewardSelection(firstKey, 100))

        val newSlot = if (firstKey.slotId == LabyrinthRoleRewardChoicePlanner.SLOT_IDS.last()) 0 else 2
        val reboundIds = listOf("1013", "1014", "1015").toMutableList().apply { set(newSlot, selected) }
        val locked = fixture.role(200, *reboundIds.toTypedArray())
        assertEquals("本轮角色选择已派发，等待确认；不重新评分", (locked.decision as KernelDecision.Wait).reason)
        val receipt = fixture.module.recordRoleRewardReceipt(token, executed = false, atMillis = 210, reason = "suppressed")
        assertNotNull(receipt)
        assertEquals("RETRY_READY", fixture.module.roleRoundSnapshot().phase)
        assertFalse(fixture.module.roleRewardChoiceCommitted)
        assertFalse(selected in fixture.module.pendingAcquiredCharacterIds)

        val retry = fixture.role(300, *reboundIds.toTypedArray(), width = 1280, height = 720)
        val rebound = action(retry, RewardActionKind.SELECT_ROLE_REWARD)
        val expected = LabyrinthRoleRewardChoicePlanner.selectionButtonRect(
            LabyrinthRoleRewardChoicePlanner.SLOT_IDS[newSlot], 1280, 720,
        )
        assertEquals(selected, rebound.targetId)
        assertEquals(expected, rebound.rect)
        assertNotEquals(initial.rect, rebound.rect)
        assertEquals(expected, fixture.module.lockedRoleRewardDecision?.buttonRect)
        val retried = requireNotNull(fixture.module.dispatchRoleRewardSelection(requireNotNull(retry.key), 300))
        assertNull(fixture.module.recordRoleRewardReceipt(token, executed = true, atMillis = 301))
        assertNotNull(fixture.module.recordRoleRewardReceipt(retried, executed = true, atMillis = 310))

        assertTrue(fixture.role(400, "1016", "1017", "1018").decision is KernelDecision.Wait)
        assertTrue(fixture.role(699, "1016", "1017", "1018").decision is KernelDecision.Wait)
        val next = fixture.role(700, "1016", "1017", "1018")
        assertNotEquals(selected, action(next, RewardActionKind.SELECT_ROLE_REWARD).targetId)
        assertNull(fixture.module.lockedRoleRewardDecision)
        assertEquals("OPEN", fixture.module.roleRoundSnapshot().phase)
        assertTrue(fixture.runtime.log.filter(kind = K.STATE_REDUCED).any { it.reasonCode == "role_reward_new_round" })

        val nextKey = requireNotNull(next.key)
        val nextPermit = requireNotNull(fixture.module.capturePermit(nextKey, 700))
        fixture.module.clearRoleRewardBatch()
        assertFalse(fixture.module.roleRewardBatchActive)
        assertFalse(fixture.module.roleRewardChoiceCommitted)
        assertNull(fixture.module.committedRoleRewardSelectionSignature)
        assertNull(fixture.module.lockedRoleRewardDecision)
        assertNull(fixture.module.roleRoundSnapshot().selectedId)
        assertFalse(nextPermit.isCurrent(700))
    }

    @Test fun `clearing a batch before a real receipt preserves the dispatch and releases failed pending roles`() {
        val fixture = Fixture()
        val step = fixture.role(100, "1010", "1011", "1012")
        val key = requireNotNull(step.key)
        val token = requireNotNull(fixture.module.dispatchRoleRewardSelection(key, 100))
        fixture.pageContext(Page.INITIAL_CHARACTER_SELECTION, Page.NODE_SELECTION, 200)
        assertFalse(fixture.module.roleRewardBatchActive)
        assertEquals(setOf(key.targetId), fixture.module.pendingAcquiredCharacterIds)
        assertTrue(fixture.runtime.log.filter(kind = K.ACTION_RECEIPT_OBSERVED).isEmpty())

        assertNotNull(fixture.module.recordRoleRewardReceipt(token, executed = false, atMillis = 210))
        assertTrue(fixture.module.pendingAcquiredCharacterIds.isEmpty())
        assertEquals("OPEN", fixture.module.roleRoundSnapshot().phase)
    }

    @Test fun `joined and item modals honor close priorities and final settlement retains ownership`() {
        val fixture = Fixture()
        fixture.arm()
        fixture.pageContext(Page.UNKNOWN, Page.CHARACTER_JOINED, 200)
        val joinedAnchors = mapOf(EntryAnchorId.JOINED_CLOSE to FIRST_BUTTON, EntryAnchorId.JOINED_CLOSE_STANDARD to SECOND_BUTTON)
        val both = fixture.module.evaluateCharacterJoined(result(Page.CHARACTER_JOINED, joinedAnchors), WIDTH, HEIGHT)
        assertEquals(FIRST_BUTTON, action(both, RewardActionKind.CLOSE_CHARACTER_JOINED).rect)
        val standard = fixture.module.evaluateCharacterJoined(
            result(Page.CHARACTER_JOINED, joinedAnchors - EntryAnchorId.JOINED_CLOSE), WIDTH, HEIGHT,
        )
        assertEquals(SECOND_BUTTON, action(standard, RewardActionKind.CLOSE_CHARACTER_JOINED).rect)
        val fallback = fixture.module.evaluateCharacterJoined(result(Page.CHARACTER_JOINED), WIDTH, HEIGHT)
        val modalClose = LabyrinthFallbackTap.MODAL_CLOSE.rect(WIDTH, HEIGHT)
        assertEquals(modalClose, action(fallback, RewardActionKind.CLOSE_CHARACTER_JOINED).rect)
        fixture.execute(fallback, 200)
        assertEquals(CharacterAcquisitionState(), fixture.module.characterAcquisitionState)

        fixture.role(300, "1010", "1011", "1012")
        fixture.pageContext(Page.INITIAL_CHARACTER_SELECTION, Page.UNKNOWN, 350)
        assertFalse(fixture.module.characterAcquisitionState.active)
        fixture.pageContext(Page.UNKNOWN, Page.CHARACTER_JOINED, 400)
        assertTrue(fixture.module.roleRewardJoinedSequenceStarted)
        assertTrue(fixture.module.characterAcquisitionState.active)
        val batchClose = fixture.module.evaluateCharacterJoined(result(Page.CHARACTER_JOINED), WIDTH, HEIGHT)
        fixture.execute(batchClose, 420)
        assertTrue(fixture.module.characterAcquisitionState.active)
        assertEquals(420L, fixture.module.characterAcquisitionState.startedAtMillis)

        fixture.pageContext(Page.CHARACTER_JOINED, Page.ITEM_REWARD, 500)
        val itemAnchors = mapOf(EntryAnchorId.RELIC_EFFECT_RESULT_CLOSE to FIRST_BUTTON, EntryAnchorId.ITEM_REWARD_CLOSE to SECOND_BUTTON)
        val bothItems = fixture.module.evaluateItemReward(result(Page.ITEM_REWARD, itemAnchors), false, WIDTH, HEIGHT)
        assertEquals(FIRST_BUTTON, action(bothItems, RewardActionKind.CLOSE_ITEM_REWARD).rect)
        val onlyItem = fixture.module.evaluateItemReward(
            result(Page.ITEM_REWARD, itemAnchors - EntryAnchorId.RELIC_EFFECT_RESULT_CLOSE), false, WIDTH, HEIGHT,
        )
        assertEquals(SECOND_BUTTON, action(onlyItem, RewardActionKind.CLOSE_ITEM_REWARD).rect)
        val itemFallback = fixture.module.evaluateItemReward(result(Page.ITEM_REWARD), false, WIDTH, HEIGHT)
        assertEquals(modalClose, action(itemFallback, RewardActionKind.CLOSE_ITEM_REWARD).rect)
        assertTrue(fixture.module.evaluateItemReward(result(Page.ITEM_REWARD), true, WIDTH, HEIGHT).decision is KernelDecision.Complete)
        assertNull(fixture.module.capturePermit(requireNotNull(itemFallback.key), 500))
        fixture.execute(fixture.module.evaluateItemReward(result(Page.ITEM_REWARD), false, WIDTH, HEIGHT), 500)
        assertTrue(fixture.module.characterAcquisitionState.active)
        fixture.pageContext(Page.ITEM_REWARD, Page.NODE_SELECTION, 600)
        assertFalse(fixture.module.roleRewardBatchActive)
        assertFalse(fixture.module.characterAcquisitionState.active)

        fixture.arm(700)
        fixture.pageContext(Page.UNKNOWN, Page.ITEM_REWARD, 800)
        val outsideBatch = fixture.module.evaluateItemReward(result(Page.ITEM_REWARD), false, WIDTH, HEIGHT)
        fixture.execute(outsideBatch, 800)
        assertEquals(CharacterAcquisitionState(), fixture.module.characterAcquisitionState)
    }

    @Test fun `animation advances only on the allowed page and checks rollback interval limit and timeout`() {
        val fixture = Fixture()
        fixture.pageContext(Page.ITEM_REWARD, Page.UNKNOWN, 90, node = LabyrinthNodeTypes.EVENT)
        assertFalse(fixture.module.characterAcquisitionState.active)
        fixture.arm(100)
        assertTrue(fixture.module.characterAcquisitionState.active)
        assertTrue(fixture.module.evaluateCharacterAcquisitionAdvance(false, WIDTH, HEIGHT, 100).decision is KernelDecision.Wait)
        assertTrue(fixture.animation(200, hasBattleControls = true).decision is KernelDecision.Wait)
        val first = fixture.animation(300)
        assertEquals(LabyrinthFallbackTap.CENTER.rect(WIDTH, HEIGHT),
            action(first, RewardActionKind.ADVANCE_CHARACTER_ACQUISITION).rect)
        val rolledBack = requireNotNull(fixture.module.recordCharacterAcquisitionDispatched(requireNotNull(first.key), 300))
        assertEquals(1, fixture.module.characterAcquisitionState.clicks)
        assertNotNull(fixture.module.rollbackCharacterAcquisitionClick(rolledBack, 301, "not_foreground"))
        assertEquals(0, fixture.module.characterAcquisitionState.clicks)
        assertEquals(Long.MIN_VALUE, fixture.module.characterAcquisitionState.lastClickAtMillis)

        fixture.execute(fixture.animation(400), 400)
        assertTrue(fixture.animation(1_049).decision is KernelDecision.Wait)
        for (index in 1 until 40) {
            val at = 400L + index * 650L
            fixture.execute(fixture.animation(at), at)
        }
        assertEquals(40, fixture.module.characterAcquisitionState.clicks)
        evidence(fixture.animation(26_400).decision, "CHARACTER_ACQUISITION_CLICK_LIMIT", KernelErrorCategory.EXECUTION)
        fixture.module.clearCharacterAcquisition()
        assertEquals(CharacterAcquisitionState(), fixture.module.characterAcquisitionState)

        fixture.arm(30_000)
        evidence(fixture.animation(60_001).decision, "CHARACTER_ACQUISITION_TIMEOUT", KernelErrorCategory.TIMEOUT)
    }

    @Test fun `free roster selects a scored safe card waits for settle then selects again and confirms`() {
        val fixture = Fixture()
        val a = roster("1010", 0)
        val b = roster("1011", 1)
        val weak = roster("1019", 2).copy(trusted = false)
        val opening = fixture.freeRole(90, listOf(a, b, weak), node = null)
        assertTrue(opening.decision is KernelDecision.Complete)
        val first = fixture.freeRole(100, listOf(a, b, weak))
        assertEquals("1010", action(first, RewardActionKind.EVENT_FREE_ROLE_SELECT).targetId)
        fixture.execute(first, 100)
        assertEquals(listOf("1010"), fixture.module.eventFreeRoleSelectedCharacterIds)

        val selected = a.copy(selected = true)
        val settling = fixture.freeRole(2_599, listOf(selected, b, weak), disabled = true)
        assertTrue(settling.decision is KernelDecision.Wait)
        val second = fixture.freeRole(2_600, listOf(selected, b, weak), disabled = true)
        assertEquals("1011", action(second, RewardActionKind.EVENT_FREE_ROLE_SELECT).targetId)
        fixture.execute(second, 2_600)

        val confirm = fixture.freeRole(2_900, listOf(selected, b.copy(selected = true)), enabled = true)
        assertEquals("1010,1011", action(confirm, RewardActionKind.EVENT_FREE_ROLE_CONFIRM).targetId)
        fixture.execute(confirm, 2_900)
        assertEquals(setOf("1010", "1011"), fixture.module.pendingAcquiredCharacterIds)
        assertTrue(fixture.module.eventFreeRoleSelectedCharacterIds.isEmpty())
    }

    @Test fun `free roster uses visible selection and stops waiting or confirming after 45 seconds`() {
        val fixture = Fixture()
        val selected = roster("1010", 0).copy(selected = true)
        assertTrue(fixture.freeRole(100, listOf(selected)).decision is KernelDecision.Wait)
        assertTrue(fixture.freeRole(45_099, listOf(selected)).decision is KernelDecision.Wait)
        evidence(fixture.freeRole(45_100, listOf(selected), enabled = true).decision,
            "EVENT_FREE_ROLE_TIMEOUT", KernelErrorCategory.TIMEOUT)
        fixture.module.clearEventFreeRole()
        val noSafeCards = listOf(selected.copy(selected = false, trusted = false))
        assertTrue(fixture.freeRole(46_000, noSafeCards).decision is KernelDecision.Wait)
        evidence(fixture.freeRole(91_000, noSafeCards).decision, "EVENT_FREE_ROLE_TIMEOUT", KernelErrorCategory.TIMEOUT)
    }

    @Test fun `receipt traces back to its dispatch frame and reset drops state and old permits`() {
        val fixture = Fixture()
        val planned = fixture.role(100, "1010", "1011", "1012")
        val roleKey = requireNotNull(planned.key)
        action(planned, RewardActionKind.SELECT_ROLE_REWARD)
        val dispatchPermit = requireNotNull(fixture.module.capturePermit(roleKey, 100))
        val dispatched = requireNotNull(fixture.module.dispatchAction(roleKey, 100))
        assertTrue(dispatchPermit.isCurrent(100))
        assertNull(fixture.module.capturePermit(roleKey, 100))
        fixture.pageContext(Page.INITIAL_CHARACTER_SELECTION, Page.CHARACTER_JOINED, 200)
        assertFalse(dispatchPermit.isCurrent(200))
        val receipt = requireNotNull(fixture.module.recordActionReceipt(dispatched, true, 210))
        val chain = fixture.runtime.log.causalChain(receipt.eventSeq)
        assertEquals(listOf(K.FRAME_OBSERVED, K.STATE_REDUCED, K.STATE_REDUCED, K.EVENT_RULE_EVALUATED,
            K.EVENT_DECISION_PROPOSED, K.PERMIT_ISSUED, K.ACTION_DISPATCHED, K.ACTION_RECEIPT_OBSERVED), chain.map { it.kind })
        assertTrue(chain.all { it.traceId == "reward-trace" && it.frameSeq == 1L })
        assertTrue(chain.zipWithNext().all { (parent, child) -> child.causeEventSeq == parent.eventSeq })
        assertNull(fixture.module.recordActionReceipt(dispatched, true, 211))

        assertTrue(fixture.module.markRelicAcquiredFromReceipt("owned", LabyrinthRelicMark.DEFENSE, 3, 215, 3))
        assertTrue(fixture.module.peekWaitingForMap(220))
        assertTrue(fixture.module.waitingForMap(220))
        assertTrue(fixture.module.prepareRelicAcquisition(relic("pending", "left"), emptyList(), true))
        assertTrue(fixture.hold(300, listOf(relic("owned", "row-1"))).holding)
        fixture.frame(Page.INITIAL_CHARACTER_SELECTION, 400)
        val roster = fixture.freeRole(400, listOf(roster("1019", 0).copy(selected = true)), enabled = true, newFrame = false)
        val key = requireNotNull(roster.key)
        val permit = requireNotNull(fixture.module.capturePermit(key, 400))
        assertTrue(fixture.module.pendingAcquiredCharacterIds.isNotEmpty())
        assertTrue(fixture.module.eventFreeRoleSelectedCharacterIds.isNotEmpty())
        assertTrue(fixture.module.roleRewardBatchActive)

        fixture.module.reset("new-trace")
        assertFalse(permit.isCurrent(400))
        assertNull(fixture.module.capturePermit(key, 400))
        assertEquals(RelicRewardHoldState(), fixture.module.relicRewardHoldState)
        assertEquals(CharacterAcquisitionState(), fixture.module.characterAcquisitionState)
        assertFalse(fixture.module.roleRewardBatchActive)
        assertFalse(fixture.module.roleRewardJoinedSequenceStarted)
        assertFalse(fixture.module.waitingForManualRoleSelection)
        assertNull(fixture.module.lockedRoleRewardDecision)
        assertEquals("OPEN", fixture.module.roleRoundSnapshot().phase)
        assertTrue(fixture.module.relicStacksSnapshot().isEmpty())
        assertFalse(fixture.module.peekWaitingForMap(401))
        assertFalse(fixture.module.hasPendingRelicAcquisition("pending"))
        assertTrue(fixture.module.pendingAcquiredCharacterIds.isEmpty())
        assertTrue(fixture.module.eventFreeRoleSelectedCharacterIds.isEmpty())

        val root = fixture.runtime.resetSession("new-trace")
        val newFrame = fixture.module.beginFrame(1, 500, 1, Page.NODE_SELECTION, root)
        assertEquals("new-trace", newFrame.traceId)
        assertEquals(listOf(root, newFrame), fixture.runtime.log.causalChain(newFrame.eventSeq))
    }

    private class Fixture {
        var now = 0L
        val runtime = KernelRuntime(clock = { now })
        val module = RewardTailModule(runtime, rolePlanner = planner())
        private var seq = 0L

        init {
            runtime.registerModule(module)
            runtime.resetSession("reward-trace")
        }

        fun frame(page: Page, at: Long, epoch: Long = 1) {
            now = at
            module.beginFrame(++seq, at, epoch, page)
        }

        fun pageContext(previous: Page, page: Page, at: Long, node: Int? = null) {
            frame(page, at)
            module.observePageContext(previous, page, node, false, at)
        }

        fun hold(
            at: Long, rows: List<LabyrinthRelicMatch>, ids: Set<String> = emptySet(),
            dryRun: Boolean = false, frameVersion: Long = 1,
        ): RelicRewardHoldOutcome {
            frame(Page.ITEM_REWARD, at)
            val reward = LabyrinthRelicRewardObservation(
                LabyrinthRelicRewardKind.RELIC_EFFECT, true, rows, at, true, rows.map { it.slotId }.toSet(),
            )
            return module.evaluateRelicRewardHold(RelicRewardHoldInput(reward, ids, 1, frameVersion, 0, at, dryRun))
        }

        fun relicChoice(at: Long, vararg choices: LabyrinthRelicMatch, newFrame: Boolean = true): RewardStep {
            if (newFrame) frame(Page.RELIC_CHOICE, at)
            return module.evaluateRelicChoice(RelicChoiceInput(
                LabyrinthRelicChoiceObservation(choices.toList()), emptyList(), true,
                mapOf(LabyrinthRelicMark.DEFENSE to 0), 1, 0, at, WIDTH, HEIGHT,
            ))
        }

        fun role(at: Long, vararg ids: String, width: Int = WIDTH, height: Int = HEIGHT): RewardStep {
            frame(Page.INITIAL_CHARACTER_SELECTION, at)
            val matches = ids.mapIndexed { index, id ->
                LabyrinthCharacterMatch(LabyrinthRoleRewardChoicePlanner.SLOT_IDS[index], id, "角色$id", 0.99, CARD)
            }
            return module.evaluateRoleRewardChoice(RoleRewardFrameInput(true, matches, width, height, 0, emptySet(), at))
        }

        fun arm(at: Long = 100) = pageContext(Page.LINK_CHOICE, Page.UNKNOWN, at)

        fun animation(at: Long, hasBattleControls: Boolean = false): RewardStep {
            frame(Page.EVENT_ANIMATION, at)
            return module.evaluateCharacterAcquisitionAdvance(hasBattleControls, WIDTH, HEIGHT, at)
        }

        fun freeRole(
            at: Long, candidates: List<LabyrinthBattleCharacterMatch>, enabled: Boolean = false, disabled: Boolean = false,
            node: Int? = LabyrinthNodeTypes.EVENT, newFrame: Boolean = true,
        ): RewardStep {
            if (newFrame) frame(Page.INITIAL_CHARACTER_SELECTION, at)
            val anchors = if (enabled) mapOf(EntryAnchorId.INVITE_ENABLED to FIRST_BUTTON) else emptyMap()
            val scores = if (disabled) mapOf(EntryAnchorId.INVITE_DISABLED to 0.99) else emptyMap()
            val viewport = LabyrinthBattleTeamObservation(
                LabyrinthBattleElementFilter.ALL, emptyList(), candidates, emptyList(),
                LabyrinthBattleScrollbarObservation(CARD, null, false, false, 0.0),
            )
            val result = result(Page.INITIAL_CHARACTER_SELECTION, anchors, scores).copy(openingCharacterSelection = viewport)
            return module.evaluateEventFreeRole(EventFreeRoleFrameInput(result, node, false, WIDTH, HEIGHT, 0, emptySet(), at))
        }

        fun execute(step: RewardStep, at: Long) {
            now = at
            val dispatch = requireNotNull(module.dispatchAction(requireNotNull(step.key), at))
            assertNotNull(module.recordActionReceipt(dispatch, true, at))
        }
    }

    private companion object {
        const val WIDTH = 1920
        const val HEIGHT = 1080
        val CARD = EntryPixelRect(100, 200, 100, 100)
        val FIRST_BUTTON = EntryPixelRect(200, 700, 150, 75)
        val SECOND_BUTTON = EntryPixelRect(800, 720, 150, 75)

        fun relic(id: String, slot: String) = LabyrinthRelicMatch(
            slotId = slot, relicId = id, displayName = "遗物$id", attribute = LabyrinthRelicMark.DEFENSE.label,
            attributeBonus = "3", effect = null, confidence = 0.99, screenRect = CARD, iconRect = CARD,
            currentMarkStacks = 0, currentMarkStacksConfirmations = 2, currentMarkStacksCapturedAtMillis = 50,
        )

        fun link(slot: String, element: LabyrinthLinkElement, button: EntryPixelRect) =
            LabyrinthLinkChoiceMatch(slot, element, 0.9, CARD, button, CARD, 1)

        fun roster(id: String, index: Int) = LabyrinthBattleCharacterMatch(
            "roster_$index", id, "角色$id", null, 0.99, CARD.copy(left = CARD.left + index * 120), false,
        )

        fun planner(): LabyrinthRoleRewardChoicePlanner {
            val profiles = (1010..1019).associate { number ->
                val id = number.toString()
                id to LabyrinthRoleProfile(id, "角色$id", userScore = (1109 - number).toDouble())
            }
            val config = LabyrinthTeamScoringConfig(attributeDamageBonus = (1..5).associateWith { 0.0 })
            return LabyrinthRoleRewardChoicePlanner(profiles, LabyrinthRoleChoicePolicy(LabyrinthTeamOptimizer(LabyrinthTeamScorer(config))))
        }

        fun result(page: Page, anchors: Map<String, EntryPixelRect> = emptyMap(), scores: Map<String, Double> = emptyMap()) =
            LabyrinthEntryFrameResult(
                LabyrinthEntryPageObservation(page, 1.0, emptyMap(), LabyrinthAnchorScores(anchors.mapValues { 0.99 } + scores)),
                emptyList(), 0L, anchorMatches = anchors.mapValues { EntryAnchorMatch(0.99, it.value) },
            )

        fun action(step: RewardStep, kind: RewardActionKind): KernelDecision.Act {
            assertTrue("Expected $kind but found ${step.decision}", step.decision is KernelDecision.Act)
            val act = step.decision as KernelDecision.Act
            assertEquals(kind.name, act.actionKind)
            assertEquals(kind, requireNotNull(step.key).kind)
            return act
        }

        fun evidence(decision: KernelDecision, code: String, category: KernelErrorCategory) {
            assertTrue("Expected $code but found $decision", decision is KernelDecision.NeedEvidence)
            val error = (decision as KernelDecision.NeedEvidence).error
            assertEquals(code, error.code)
            assertEquals(category, error.category)
        }
    }
}
