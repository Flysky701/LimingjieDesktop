package com.landosol.toolbox.labyrinth.kernel

import com.landosol.toolbox.labyrinth.LabyrinthPostEntryActionKind
import com.landosol.toolbox.labyrinth.LabyrinthPostEntryActionKind.*
import com.landosol.toolbox.labyrinth.LabyrinthRelicIdentityGate
import com.landosol.toolbox.labyrinth.LabyrinthRelicMark.ACCELERATION
import com.landosol.toolbox.labyrinth.LabyrinthShopPolicy
import com.landosol.toolbox.labyrinth.kernel.KernelEventKinds as K
import com.landosol.toolbox.labyrinth.kernel.shop.ShopDomainModule
import com.landosol.toolbox.labyrinth.kernel.shop.ShopPlanOutcome
import com.landosol.toolbox.labyrinth.vision.EntryAnchorId
import com.landosol.toolbox.labyrinth.vision.EntryAnchorMatch
import com.landosol.toolbox.labyrinth.vision.EntryPixelRect
import com.landosol.toolbox.labyrinth.vision.LabyrinthAnchorScores
import com.landosol.toolbox.labyrinth.vision.LabyrinthEntryFrameResult
import com.landosol.toolbox.labyrinth.vision.LabyrinthEntryPageObservation
import com.landosol.toolbox.labyrinth.vision.LabyrinthEntryPageState
import com.landosol.toolbox.labyrinth.vision.LabyrinthEntryPageState.*
import com.landosol.toolbox.labyrinth.vision.LabyrinthNodeMoveConfirmationObservation
import com.landosol.toolbox.labyrinth.vision.LabyrinthRelicMatch
import com.landosol.toolbox.labyrinth.vision.LabyrinthShopDialogState
import com.landosol.toolbox.labyrinth.vision.LabyrinthShopItemKind
import com.landosol.toolbox.labyrinth.vision.LabyrinthShopItemMatch
import com.landosol.toolbox.labyrinth.vision.LabyrinthShopItemStatus
import com.landosol.toolbox.labyrinth.vision.LabyrinthShopObservation
import org.junit.Assert.*
import org.junit.Test

class ShopDomainKernelTest {
    @Test fun relicConfirmationRequiresCurrentMatchingIdentityAndCompletionCountsOnce() {
        val f = Fixture()
        val relic = relic("relic-1")
        val stock = shop(relicItem(relic))
        val buy = f.planBuy(relic, 1_000L)
        f.perform(buy, 1_100L)

        assertWait(f.plan(confirmation(null), 1_200L), "尚未可靠识别")
        assertNull(f.module.capturePermit(requireNotNull(buy.actionKey), 1_200L))
        assertWait(f.plan(confirmation(relic), 1_300L), "正在复读")
        assertWait(f.plan(confirmation(relic), 1_300L), "正在复读")
        val different = relic.copy(relicId = "relic-2")
        assertWait(f.plan(confirmation(different), 1_400L), "正在复读")
        assertWait(f.plan(confirmation(different), 1_500L), "遗物relic-2与计划relic-1不一致")
        assertNull(f.module.pendingPurchaseRelic)

        assertWait(f.plan(confirmation(relic), 1_600L), "正在复读")
        assertWait(f.plan(confirmation(relic, anchor = null), 1_700L), "确认按钮未达到安全识别线")
        val firstConfirm = f.plan(confirmation(relic), 1_800L).expectAction(SHOP_CONFIRM_RELIC)
        val firstKey = requireNotNull(firstConfirm.actionKey)
        assertWait(f.plan(confirmation(null), 1_900L), "拒绝自动确认")
        assertFalse(requireNotNull(firstConfirm.permitToken).isCurrent(1_900L))
        assertNull(f.module.capturePermit(firstKey, 1_900L))
        assertWait(f.plan(confirmation(relic), 2_000L), "正在复读")
        f.perform(f.plan(confirmation(relic), 2_100L).expectAction(SHOP_CONFIRM_RELIC), 2_100L)
        assertEquals(relic, f.module.pendingPurchaseRelic)
        assertEquals(0, f.module.relicPurchasesThisCycle)

        assertWait(f.plan(stock, 2_200L), "等待购买结果稳定")
        assertWait(f.plan(stock, 2_500L), "等待购买结果稳定")
        assertEquals("relic-1", f.module.plannedPurchaseRelicId)
        val completed = f.plan(frame(SHOP_PURCHASE_COMPLETE), 2_600L).expectAction(SHOP_CLOSE_PURCHASE_COMPLETE)
        assertEquals(relic, completed.completedPurchaseRelic)
        assertEquals(1, f.module.relicPurchasesThisCycle)
        val repeated = f.plan(frame(SHOP_PURCHASE_COMPLETE), 2_700L).expectAction(SHOP_CLOSE_PURCHASE_COMPLETE)
        assertNull(repeated.completedPurchaseRelic)
        val dispatch = f.dispatch(repeated, 2_700L)
        f.execute(repeated, 2_750L, dispatch)
        assertNull(f.module.recordActionExecuted(SHOP_CLOSE_PURCHASE_COMPLETE, 2_760L, cause = dispatch))
        assertEquals(1, f.module.relicPurchasesThisCycle)
        assertNull(f.module.plannedPurchaseRelicId)
        assertNull(f.module.pendingPurchaseRelic)
        assertFalse(f.module.plannedPurchaseCounted)
        assertFalse("relic-1" in f.cancelled)
    }

    @Test fun closeReceiptCanCommitWithoutAVisualCount() {
        val f = Fixture()
        val relic = relic("receipt-relic")
        f.perform(f.planBuy(relic, 1_000L), 1_100L)
        assertEquals(0, f.module.relicPurchasesThisCycle)
        assertNotNull(f.module.recordActionExecuted(SHOP_CLOSE_PURCHASE_COMPLETE, 1_500L, { f.cancelled.add(it) }))
        assertEquals(1, f.module.relicPurchasesThisCycle)
        f.module.recordActionExecuted(SHOP_CLOSE_PURCHASE_COMPLETE, 1_600L, { f.cancelled.add(it) })
        assertEquals(1, f.module.relicPurchasesThisCycle)
        assertNull(f.module.plannedPurchaseRelicId)
        assertFalse("receipt-relic" in f.cancelled)
        assertWait(f.plan(frame(SHOP_PURCHASE_COMPLETE), 1_700L), "人工购买完成")
    }

    @Test fun choiceImprintConfirmationUsesItsAnchorAndKeepsThePickerPending() {
        val f = Fixture()
        val stock = shop(imprintItem())
        val buy = f.plan(stock, 1_000L).expectAction(SHOP_BUY_IMPRINT)
        assertTrue(f.module.choiceImprintRolePending)
        assertEquals("全体选择印记", f.module.plannedRoleImprintLabel)
        f.perform(buy, 1_000L)
        assertWait(f.plan(confirmation(null, anchor = null), 1_100L), "确认按钮未达到安全识别线")
        val outside = EntryAnchorMatch(0.9, EntryPixelRect(WIDTH - 10, 20, 11, 10))
        assertWait(f.plan(confirmation(null, outside), 1_150L), "与当前捕获尺寸不符")
        f.perform(f.plan(confirmation(null), 1_200L).expectAction(SHOP_CONFIRM_IMPRINT), 1_200L)
        assertEquals(1_200L, f.module.imprintRewardConfirmedAt)
        assertNull(f.module.pendingPurchaseRelic)
        f.perform(f.plan(frame(SHOP_PURCHASE_COMPLETE), 1_300L).expectAction(SHOP_CLOSE_PURCHASE_COMPLETE), 1_300L)
        assertEquals(0, f.module.relicPurchasesThisCycle)
        assertTrue(f.module.choiceImprintRolePending)
        f.module.clearChoiceImprintRolePending()
        assertFalse(f.module.choiceImprintRolePending)

        f.perform(f.plan(stock, 2_000L).expectAction(SHOP_BUY_IMPRINT), 2_000L)
        f.perform(f.plan(confirmation(null), 2_100L).expectAction(SHOP_CONFIRM_IMPRINT), 2_100L)
        assertTrue(f.module.choiceImprintRolePending)
        val exit = f.plan(frame(SHOP_EXIT_CONFIRMATION), 2_200L).expectAction(SHOP_CONFIRM_EXIT)
        f.perform(exit, 2_200L)
        assertFalse(f.module.choiceImprintRolePending)
        assertNull(f.module.plannedRoleImprintLabel)
    }

    @Test fun timedOutRefreshKeepsPurchasesAndAConfirmedRefreshResetsOnlyTheCycle() {
        val f = Fixture(area = 5)
        repeat(3) { f.completePurchase(relic("relic-$it"), 1_000L + it * 2_000L) }
        assertEquals(3, f.module.relicPurchasesThisCycle)
        val stock = shop(refreshScore = 0.45)
        assertWait(f.plan(shop(refreshScore = 0.44), 6_900L), "等待刷新按钮稳定识别")

        val refresh = f.plan(stock, 7_000L).expectAction(SHOP_REFRESH)
        f.perform(refresh, 7_000L)
        assertEquals(1, f.module.refreshesUsedInVisit)
        assertEquals(7_000L, f.module.plannedRefreshStartedAt)
        assertWait(f.refresh(refreshDialog(), 8_499L), "安全点击间隔")
        val expired = f.refresh(refreshDialog(), 8_500L).expectAction(SHOP_CONFIRM_REFRESH)
        val timeout = f.refresh(refreshDialog(), 15_001L)
        assertWait(timeout, "未重置本轮遗物计数")
        assertFalse(timeout.handled)
        assertEquals(Long.MIN_VALUE, f.module.plannedRefreshStartedAt)
        assertEquals(Long.MIN_VALUE, f.module.plannedRefreshConfirmedAt)
        assertEquals(3, f.module.relicPurchasesThisCycle)
        assertEquals(1, f.module.refreshesUsedInVisit)
        assertNull(f.module.capturePermit(requireNotNull(expired.actionKey), 15_001L))
        assertEquals("refresh_confirmation_timeout", f.runtime.log.filter(kind = K.PERMIT_REVOKED).last().reasonCode)
        assertNull(f.module.handleRefreshConfirmation(stock, 15_002L))

        f.perform(f.plan(stock, 15_100L).expectAction(SHOP_REFRESH), 15_100L)
        assertEquals(2, f.module.refreshesUsedInVisit)
        val confirm = f.refresh(refreshDialog(), 16_600L).expectAction(SHOP_CONFIRM_REFRESH)
        f.perform(confirm, 16_600L)
        assertEquals(16_600L, f.module.plannedRefreshConfirmedAt)
        assertWait(f.refresh(stock, 17_099L), "等待新商品列表返回")
        assertWait(f.refresh(refreshDialog(), 17_100L), "等待新商品列表返回")
        assertEquals(3, f.module.relicPurchasesThisCycle)
        val committed = f.refresh(stock, 17_101L)
        assertWait(committed, "商店已刷新")
        assertTrue(committed.handled)
        assertEquals(0, f.module.relicPurchasesThisCycle)
        assertEquals(2, f.module.refreshesUsedInVisit)
        assertEquals(Long.MIN_VALUE, f.module.plannedRefreshStartedAt)
        assertEquals(Long.MIN_VALUE, f.module.plannedRefreshConfirmedAt)
    }

    @Test fun manualPurchaseDialogsRevokePreviousShopPermits() {
        val f = Fixture()
        val close = f.plan(shop(), 1_000L).expectAction(SHOP_CLOSE)
        assertWait(f.plan(confirmation(relic("manual")), 1_100L), "保持人工控制")
        assertFalse(requireNotNull(close.permitToken).isCurrent(1_100L))
        assertNull(f.module.capturePermit(requireNotNull(close.actionKey), 1_100L))

        val otherClose = f.plan(shop(), 1_200L).expectAction(SHOP_CLOSE)
        assertWait(f.plan(frame(SHOP_PURCHASE_COMPLETE), 1_300L), "人工购买完成")
        assertFalse(requireNotNull(otherClose.permitToken).isCurrent(1_300L))
        assertEquals(0, f.module.relicPurchasesThisCycle)
    }

    @Test fun purchaseSurvivesAnUnknownFrameAndCancelsOnlyAfterTheDialogGrace() {
        val f = Fixture()
        val relic = relic("cancelled")
        f.perform(f.planBuy(relic, 1_000L), 1_100L)
        val transition = f.plan(frame(UNKNOWN), 1_200L)
        assertFalse(transition.handled)
        assertEquals("cancelled", f.module.plannedPurchaseRelicId)
        assertWait(f.plan(confirmation(relic), 1_300L), "正在复读")
        assertWait(f.plan(shop(), 1_400L), "等待购买结果稳定")
        assertWait(f.plan(shop(), 3_399L), "等待购买结果稳定")
        assertEquals(2, f.module.shopConfirmClosedFrames)
        assertEquals(1_400L, f.module.shopConfirmClosedAt)
        f.plan(shop(), 3_400L).expectAction(SHOP_CLOSE)
        assertEquals(listOf("cancelled"), f.cancelled.filterNotNull())
        assertNull(f.module.plannedPurchaseRelicId)
        assertEquals(0, f.module.shopConfirmClosedFrames)

        val expired = relic("expired")
        f.perform(f.planBuy(expired, 4_000L), 4_100L)
        assertWait(f.plan(confirmation(expired), 12_101L), "购买确认已超时")
        assertNull(f.module.plannedPurchaseRelicId)
        assertEquals(listOf("cancelled", "expired"), f.cancelled.filterNotNull())
    }

    @Test fun permitTracksTheLatestDecisionAndExpiresOrRevokesWhenItsKeyChanges() {
        val f = Fixture()
        val initial = f.plan(shop(), 1_000L, coins = 4_200, scroll = 2).expectAction(SHOP_CLOSE)
        assertEquals(4_200, f.module.observedAlphaCoin)
        assertEquals(2, f.module.scrollStage)
        val latest = f.plan(shop(), 1_050L).expectAction(SHOP_CLOSE)
        assertEquals(initial.tapPlan, latest.tapPlan)
        assertNull(f.module.observedAlphaCoin)
        assertNull(f.module.scrollStage)
        val dispatch = f.dispatch(latest, 1_050L)
        val receipt = f.execute(latest, 1_060L, dispatch)
        val chain = f.runtime.log.causalChain(receipt.eventSeq)
        assertEquals(listOf(K.SESSION_LIFECYCLE, K.STATE_REDUCED, K.FRAME_OBSERVED, K.EVENT_RULE_EVALUATED,
            K.EVENT_DECISION_PROPOSED, K.PERMIT_ISSUED, K.ACTION_DISPATCHED, K.ACTION_RECEIPT_OBSERVED),
            chain.map { it.kind })
        assertEquals(latest.decisionEvent, chain[4])
        val frameEvent = chain[2]
        assertEquals(1_050L, (frameEvent.payload as FrameObservedPayload).capturedAtMillis)
        assertTrue(receipt in f.runtime.log.descendants(frameEvent.eventSeq))
        assertEquals(K.PERMIT_REVOKED, f.runtime.log.descendants(receipt.eventSeq).single().kind)

        val first = f.plan(shop(), 2_000L).expectAction(SHOP_CLOSE)
        val moved = shop().copy(anchorMatches = mapOf(
            EntryAnchorId.SHOP_CLOSE to EntryAnchorMatch(0.9, EntryPixelRect(100, 100, 30, 20)),
        ))
        val second = f.plan(moved, 2_100L).expectAction(SHOP_CLOSE)
        val firstKey = requireNotNull(first.actionKey)
        val secondKey = requireNotNull(second.actionKey)
        assertFalse(requireNotNull(first.permitToken).isCurrent(2_100L))
        assertNull(f.module.capturePermit(firstKey, 2_100L))
        assertNull(f.module.capturePermit(secondKey.copy(sessionId = "old-session"), 2_100L))
        assertNull(f.module.recordActionDispatched(SHOP_CLOSE, 2_100L, actionKey = firstKey))
        assertNotNull(f.module.capturePermit(secondKey, 3_300L))
        assertNull(f.module.capturePermit(secondKey, 3_301L))
        assertNull(f.module.recordActionExecuted(SHOP_CLOSE, 3_301L, actionKey = secondKey))
        assertEquals("permit_expired", f.runtime.log.filter(kind = K.PERMIT_REVOKED).last().reasonCode)
    }

    @Test fun enteringAnotherShopKeepsFocusAndSessionResetClearsAllShopState() {
        val f = Fixture(area = 5)
        repeat(3) { f.completePurchase(relic("before-reset-$it"), 1_000L + it * 2_000L) }
        assertEquals(ACCELERATION, f.module.focusMark)
        assertEquals(3, f.module.relicPurchasesThisCycle)
        val oldPermit = f.plan(shop(refreshScore = 0.8), 7_000L).expectAction(SHOP_REFRESH)
        f.module.onEnterShopNode(area = 5, timestampMillis = 7_100L)
        assertEquals(ACCELERATION, f.module.focusMark)
        assertEquals(0, f.module.relicPurchasesThisCycle)
        assertFalse(requireNotNull(oldPermit.permitToken).isCurrent(7_100L))

        assertNotNull(f.module.recordActionExecuted(SHOP_REFRESH, 7_200L))
        assertEquals(1, f.module.refreshesUsedInVisit)
        val confirm = f.refresh(refreshDialog(), 8_700L).expectAction(SHOP_CONFIRM_REFRESH)
        f.perform(confirm, 8_700L)
        f.refresh(shop(), 9_200L)
        f.completePurchase(relic("after-refresh"), 9_300L)
        f.area = 1
        val pending = f.plan(shop(imprintItem()), 9_800L, coins = 1_820, scroll = 1).expectAction(SHOP_BUY_IMPRINT)
        f.perform(pending, 9_800L)
        f.perform(f.plan(confirmation(null), 9_900L).expectAction(SHOP_CONFIRM_IMPRINT), 9_900L)
        val outstanding = f.plan(frame(SHOP_PURCHASE_COMPLETE), 10_000L).expectAction(SHOP_CLOSE_PURCHASE_COMPLETE)
        assertTrue(f.module.choiceImprintRolePending)
        assertEquals(1, f.module.relicPurchasesThisCycle)
        assertEquals(1, f.module.refreshesUsedInVisit)
        assertEquals(ACCELERATION, f.module.focusMark)
        f.runtime.resetSession("next-shop-run")
        assertFalse(requireNotNull(outstanding.permitToken).isCurrent(10_000L))
        assertNull(f.module.capturePermit(requireNotNull(outstanding.actionKey), 10_000L))
        assertEquals("session_reset", f.runtime.log.filter(kind = K.PERMIT_REVOKED).last().reasonCode)
        assertEquals(0, f.module.relicPurchasesThisCycle)
        assertEquals(0, f.module.refreshesUsedInVisit)
        assertNull(f.module.plannedPurchaseRelicId)
        assertNull(f.module.plannedRoleImprintLabel)
        assertNull(f.module.pendingPurchaseRelic)
        assertFalse(f.module.plannedPurchaseCounted)
        assertFalse(f.module.choiceImprintRolePending)
        assertEquals(Long.MIN_VALUE, f.module.plannedPurchaseStartedAt)
        assertEquals(Long.MIN_VALUE, f.module.plannedRefreshStartedAt)
        assertEquals(Long.MIN_VALUE, f.module.plannedRefreshConfirmedAt)
        assertEquals(Long.MIN_VALUE, f.module.imprintRewardConfirmedAt)
        assertEquals(LabyrinthShopDialogState.NONE, f.module.lastShopDialogState)
        assertEquals(0, f.module.shopConfirmClosedFrames)
        assertEquals(Long.MIN_VALUE, f.module.shopConfirmClosedAt)
        assertNull(f.module.lastObservedPage)
        assertNull(f.module.focusMark)
        assertNull(f.module.observedAlphaCoin)
        assertNull(f.module.scrollStage)
        assertEquals(listOf(K.SESSION_LIFECYCLE), f.runtime.log.byTraceId("next-shop-run").map { it.kind })
    }

    @Test fun sessionResetClearsIdentityEvidenceWhenTheNextSessionStartsOnTheSamePage() {
        val f = Fixture()
        val stock = shop(relicItem(relic("same-visible-relic")))
        assertWait(f.plan(stock, 1_000L), "复核待购买遗物身份")
        f.runtime.resetSession("new-shop-run")
        assertWait(f.plan(stock, 1_100L), "复核待购买遗物身份")
        f.plan(stock, 1_200L).expectAction(SHOP_BUY_RELIC)
    }

    private class Fixture(var area: Int = 1) {
        private var now = 0L
        val runtime = KernelRuntime(clock = { now })
        val module = ShopDomainModule(runtime)
        val cancelled = mutableListOf<String?>()
        private val policy = LabyrinthShopPolicy()
        private val identity = LabyrinthRelicIdentityGate()

        init {
            runtime.registerModule(module)
            module.onEnterShopNode(area = area, cause = runtime.resetSession("shop-run"))
        }

        fun plan(result: LabyrinthEntryFrameResult, at: Long, coins: Int? = null, scroll: Int? = null): ShopPlanOutcome {
            now = at
            return module.planShopPage(result, result.observation.state, policy, area, emptyList(),
                mapOf(ACCELERATION to 6), identity, { true }, { cancelled.add(it) },
                WIDTH, HEIGHT, at, observedAlphaCoin = coins, scrollStage = scroll)
        }

        fun refresh(result: LabyrinthEntryFrameResult, at: Long): ShopPlanOutcome {
            now = at
            return requireNotNull(module.handleRefreshConfirmation(result, at))
        }

        fun dispatch(outcome: ShopPlanOutcome, at: Long): KernelEvent {
            now = at
            val key = requireNotNull(outcome.actionKey)
            return requireNotNull(module.recordActionDispatched(key.actionKind, at, actionKey = key,
                cause = outcome.decisionEvent, permitToken = outcome.permitToken))
        }

        fun execute(outcome: ShopPlanOutcome, at: Long, dispatch: KernelEvent): KernelEvent {
            now = at
            val key = requireNotNull(outcome.actionKey)
            return requireNotNull(module.recordActionExecuted(key.actionKind, at, { cancelled.add(it) },
                dispatch, key, outcome.permitToken))
        }

        fun perform(outcome: ShopPlanOutcome, at: Long) = execute(outcome, at, dispatch(outcome, at))

        fun planBuy(relic: LabyrinthRelicMatch, at: Long): ShopPlanOutcome {
            val stock = shop(relicItem(relic))
            assertWait(plan(stock, at), "复核待购买遗物身份")
            return plan(stock, at + 100L).expectAction(SHOP_BUY_RELIC)
        }

        fun completePurchase(relic: LabyrinthRelicMatch, at: Long) {
            perform(planBuy(relic, at), at + 100L)
            assertWait(plan(confirmation(relic), at + 200L), "正在复读")
            perform(plan(confirmation(relic), at + 300L).expectAction(SHOP_CONFIRM_RELIC), at + 300L)
            perform(plan(frame(SHOP_PURCHASE_COMPLETE), at + 400L).expectAction(SHOP_CLOSE_PURCHASE_COMPLETE), at + 400L)
        }
    }

    private companion object {
        const val WIDTH = 1_920
        const val HEIGHT = 1_080
        val BUTTON = EntryPixelRect(800, 600, 100, 50)
        val ANCHOR = EntryAnchorMatch(0.9, BUTTON)

        fun relic(id: String) = LabyrinthRelicMatch(
            slotId = "first-slot", relicId = id, displayName = id, attribute = ACCELERATION.label,
            attributeBonus = "2", effect = null, confidence = 0.95,
            screenRect = BUTTON, iconRect = BUTTON, rarity = 2,
        )

        fun relicItem(relic: LabyrinthRelicMatch) = LabyrinthShopItemMatch(
            relic.slotId, BUTTON, BUTTON, 0.9, status = LabyrinthShopItemStatus.AVAILABLE,
            relicMatch = relic, kind = LabyrinthShopItemKind.RELIC,
        )

        fun imprintItem() = LabyrinthShopItemMatch(
            "imprint-slot", BUTTON, BUTTON, 0.9, status = LabyrinthShopItemStatus.AVAILABLE,
            kind = LabyrinthShopItemKind.ROLE_IMPRINT, roleImprintLabel = "全体选择印记", choiceRoleImprint = true,
        )

        fun shop(vararg items: LabyrinthShopItemMatch, refreshScore: Double? = null): LabyrinthEntryFrameResult {
            val matches = buildMap {
                put(EntryAnchorId.SHOP_CLOSE, ANCHOR)
                if (refreshScore != null) put(EntryAnchorId.SHOP_REFRESH_BUTTON, EntryAnchorMatch(refreshScore, BUTTON))
            }
            return frame(SHOP, LabyrinthShopObservation(items.toList(),
                refreshButtonEnabledEvidence = if (refreshScore == null) 0.0 else 0.9), matches)
        }

        fun confirmation(relic: LabyrinthRelicMatch?, anchor: EntryAnchorMatch? = ANCHOR) =
            frame(SHOP_PURCHASE_CONFIRMATION,
                LabyrinthShopObservation(emptyList(), LabyrinthShopDialogState.PURCHASE_CONFIRMATION, purchaseCandidate = relic),
                if (anchor == null) emptyMap() else mapOf(EntryAnchorId.SHOP_EXIT_CONFIRM_BUTTON to anchor))

        fun refreshDialog() = frame(UNKNOWN).copy(nodeMoveConfirmation = LabyrinthNodeMoveConfirmationObservation(
            0.9, BUTTON, EntryPixelRect(500, 600, 100, 50),
        ))

        fun frame(
            page: LabyrinthEntryPageState,
            shop: LabyrinthShopObservation? = null,
            anchors: Map<String, EntryAnchorMatch> = emptyMap(),
        ) = LabyrinthEntryFrameResult(
            LabyrinthEntryPageObservation(page, 1.0, mapOf(page to 1.0), LabyrinthAnchorScores(emptyMap())),
            emptyList(), 0L, anchorMatches = anchors, shopObservation = shop, frameWidth = WIDTH, frameHeight = HEIGHT,
        )

        fun assertWait(outcome: ShopPlanOutcome, reason: String) {
            assertNull(outcome.tapPlan)
            assertNull(outcome.actionKey)
            assertNull(outcome.permitToken)
            assertTrue(outcome.decision is KernelDecision.Wait)
            assertTrue(outcome.waitReason.orEmpty(), outcome.waitReason.orEmpty().contains(reason))
            assertEquals(outcome.waitReason, (outcome.decision as KernelDecision.Wait).reason)
        }

        fun ShopPlanOutcome.expectAction(kind: LabyrinthPostEntryActionKind): ShopPlanOutcome {
            assertEquals(waitReason, kind, tapPlan?.kind)
            assertEquals(kind, actionKey?.actionKind)
            assertEquals(kind.name, (decision as? KernelDecision.Act)?.actionKind)
            assertNotNull(permitToken)
            return this
        }
    }
}
