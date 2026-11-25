package com.landosol.toolbox.labyrinth.kernel

import com.landosol.toolbox.automation.AutomationAction
import com.landosol.toolbox.automation.AutomationBackendResult
import com.landosol.toolbox.automation.AutomationSessionManager
import com.landosol.toolbox.automation.SessionBoundActionExecutor
import com.landosol.toolbox.labyrinth.LabyrinthEntryRecognitionSession
import com.landosol.toolbox.labyrinth.LabyrinthEntryRecognitionStartResult
import com.landosol.toolbox.labyrinth.LabyrinthExecutionGateResult
import com.landosol.toolbox.labyrinth.LabyrinthNodeJson
import com.landosol.toolbox.labyrinth.LabyrinthRouteJson
import com.landosol.toolbox.labyrinth.LabyrinthPostEntryActionKind
import com.landosol.toolbox.labyrinth.LabyrinthPostEntryActionKind.*
import com.landosol.toolbox.labyrinth.LabyrinthRelicIdentityGate
import com.landosol.toolbox.labyrinth.LabyrinthRelicMark.ACCELERATION
import com.landosol.toolbox.labyrinth.LabyrinthShopDecision
import com.landosol.toolbox.labyrinth.LabyrinthShopPolicy
import com.landosol.toolbox.labyrinth.LabyrinthShopRefreshOutcome
import com.landosol.toolbox.labyrinth.LabyrinthShopRefreshPhase
import com.landosol.toolbox.labyrinth.LabyrinthShopScanController
import com.landosol.toolbox.labyrinth.kernel.KernelEventKinds as K
import com.landosol.toolbox.labyrinth.kernel.shop.ShopDomainModule
import com.landosol.toolbox.labyrinth.kernel.shop.ShopPlanOutcome
import com.landosol.toolbox.labyrinth.node.LabyrinthNodeTypes
import com.landosol.toolbox.labyrinth.node.NodeTemplateSet
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
import com.landosol.toolbox.labyrinth.vision.LabyrinthShopItemVisibility
import com.landosol.toolbox.labyrinth.vision.LabyrinthShopObservation
import com.landosol.toolbox.labyrinth.vision.LabyrinthShopRefreshDialog
import com.landosol.toolbox.labyrinth.vision.LabyrinthShopRefreshDialogKind
import com.landosol.toolbox.labyrinth.vision.LabyrinthShopViewport
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.InvocationTargetException
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

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
        val close = f.plan(frame(SHOP_PURCHASE_COMPLETE), 1_500L, purchaseAlreadyObserved = true)
            .expectAction(SHOP_CLOSE_PURCHASE_COMPLETE)
        assertEquals(0, f.module.relicPurchasesThisCycle)
        f.perform(close, 1_500L)
        assertEquals(1, f.module.relicPurchasesThisCycle)
        assertNull(f.module.recordActionExecuted(SHOP_CLOSE_PURCHASE_COMPLETE, 1_600L))
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

    @Test fun refreshCommitsOnceOnTwoDistinctPaidFramesAndKeepsTheWholeInputChain() {
        val f = Fixture(area = 5)
        repeat(3) { f.completePurchase(relic("relic-$it"), 1_000L + it * 2_000L) }
        val missingButton = shelf(refreshScore = 0.44)
        assertWait(f.plan(missingButton, 6_700L), "画面稳定")
        assertWait(f.plan(missingButton, 6_800L), "底部可见商品")
        assertWait(f.plan(missingButton, 6_900L), "等待刷新按钮稳定识别")
        val refresh = f.plan(shelf(refreshScore = 0.45), 7_000L).expectAction(SHOP_REFRESH)
        val dispatched = f.dispatch(refresh, 7_000L)
        assertNull(f.module.shopRefreshTransaction.pending)
        assertEquals(0, f.module.refreshesUsedInVisit)
        val requestReceipt = f.execute(refresh, 7_010L, dispatched)
        assertEquals(7_010L, f.module.plannedRefreshStartedAt)
        assertEquals(3, f.module.relicPurchasesThisCycle)
        assertEquals(0, f.module.refreshesUsedInVisit)

        assertWait(f.refresh(refreshDialog(8_499L), 8_499L), "安全点击间隔")
        val confirm = f.refresh(refreshDialog(8_500L), 8_500L).expectAction(SHOP_CONFIRM_REFRESH)
        val confirmReceipt = f.perform(confirm, 8_500L)
        assertTrue(requestReceipt in f.runtime.log.causalChain(confirmReceipt.eventSeq))
        assertEquals(8_500L, f.module.plannedRefreshConfirmedAt)
        val paid = shelf(coins = 19_700, visual = 87)
        assertWait(f.refresh(paid, 8_600L), "第二个稳定新截图")
        assertWait(f.refresh(paid, 8_600L), "第二个稳定新截图")
        assertEquals(1, f.module.shopRefreshTransaction.pending?.stableFrames)
        assertEquals(3, f.module.relicPurchasesThisCycle)
        assertEquals(0, f.module.refreshesUsedInVisit)
        val commit = f.refresh(paid, 8_701L)
        assertEquals(LabyrinthShopRefreshOutcome.COMMITTED, commit.refreshOutcome)
        assertWait(commit, "提交第1次刷新")
        assertEquals(0, f.module.relicPurchasesThisCycle)
        assertEquals(1, f.module.refreshesUsedInVisit)
        assertEquals(Long.MIN_VALUE, f.module.plannedRefreshStartedAt)
        assertEquals(Long.MIN_VALUE, f.module.plannedRefreshConfirmedAt)
        assertFalse(f.module.shopScan.complete)
        assertNull(f.module.handleRefreshConfirmation(paid, 9_000L))
        val reduced = f.runtime.log.filter(kind = K.STATE_REDUCED, reasonCode = "refresh_committed").single()
        assertTrue(confirmReceipt in f.runtime.log.causalChain(reduced.eventSeq))

        val next = f.readyToRefresh(10_000L, shelf(coins = 19_700, visual = 87))
        f.perform(next, 10_200L)
        assertEquals(600, f.module.shopRefreshTransaction.pending?.expectedFee)
        assertWait(f.refresh(refreshDialog(11_800L, fee = 300), 11_800L), "未结算")
        f.refresh(refreshDialog(11_900L, fee = 600), 11_900L).expectAction(SHOP_CONFIRM_REFRESH)
    }

    @Test fun genericStaleAndWrongFeeDialogsRevokeConfirmationAndExitStopsTheVisit() {
        val f = Fixture(area = 5)
        val refresh = f.readyToRefresh(1_000L)
        // The permit cannot transfer a financial baseline to a different frame.
        assertNull(f.module.recordActionDispatched(SHOP_REFRESH, 1_201L, actionKey = refresh.actionKey,
            permitToken = refresh.permitToken))
        f.perform(f.plan(shelf(), 1_300L).expectAction(SHOP_REFRESH), 1_300L)
        val valid = f.refresh(refreshDialog(2_900L), 2_900L).expectAction(SHOP_CONFIRM_REFRESH)
        assertNull(f.module.recordActionDispatched(SHOP_CONFIRM_REFRESH, 2_900L, actionKey = valid.actionKey,
            permitToken = valid.permitToken, dialog = null))
        assertFalse(requireNotNull(valid.permitToken).isCurrent(2_900L))
        for (dialog in listOf(null, LabyrinthShopRefreshDialog(),
                LabyrinthShopRefreshDialog(LabyrinthShopRefreshDialogKind.REFRESH, 300, false, capturedAtMillis = 3_000L),
                LabyrinthShopRefreshDialog(LabyrinthShopRefreshDialogKind.REFRESH, 300, true, capturedAtMillis = 1_300L),
                LabyrinthShopRefreshDialog(LabyrinthShopRefreshDialogKind.REFRESH, 600, true, capturedAtMillis = 3_000L))) {
            assertWait(f.refresh(refreshDialog(3_000L).copy(shopRefreshDialog = dialog), 3_000L), "未结算")
        }
        val exit = refreshDialog(3_100L, kind = LabyrinthShopRefreshDialogKind.EXIT)
        val stopped = f.plan(exit.copy(observation = frame(SHOP_EXIT_CONFIRMATION).observation), 3_100L)
        assertStopped(stopped, "检测到退出确认")
        assertEquals(0, f.module.refreshesUsedInVisit)
        assertNull(f.module.shopRefreshTransaction.pending?.confirmedAt)
        assertEquals(LabyrinthShopRefreshPhase.REQUESTED, f.module.shopRefreshTransaction.pending?.phase)
    }

    @Test fun unchangedStockCancelsButAnUnexplainedBalanceKeepsTheUnresolvedReceipt() {
        for (unchanged in listOf(true, false)) {
            val f = Fixture(area = 5)
            f.completePurchase(relic("before-refresh"), 1_000L)
            f.perform(f.readyToRefresh(3_000L), 3_200L)
            val confirm = f.refresh(refreshDialog(5_000L), 5_000L).expectAction(SHOP_CONFIRM_REFRESH)
            val stock = if (unchanged) shelf() else shelf(coins = 19_699)
            assertWait(f.refresh(stock, 5_100L), "未结算")
            assertFalse(requireNotNull(confirm.permitToken).isCurrent(5_100L))
            val stopped = f.refresh(stock, 11_201L)
            if (unchanged) {
                assertStopped(stopped, "刷新已取消或未扣费")
                assertEquals(LabyrinthShopRefreshOutcome.CANCELLED, stopped.refreshOutcome)
                assertNull(f.module.shopRefreshTransaction.pending)
                assertEquals(Long.MIN_VALUE, f.module.plannedRefreshStartedAt)
            } else {
                assertStopped(stopped, "刷新结果无法核实")
                assertEquals(LabyrinthShopRefreshOutcome.UNRESOLVED, stopped.refreshOutcome)
                assertEquals(3_200L, f.module.shopRefreshTransaction.pending?.requestedAt)
                assertEquals(19_699, f.module.shopRefreshTransaction.pending?.observedCoins)
                assertNull(f.module.recordActionExecuted(SHOP_REFRESH, 11_300L))
            }
            assertEquals(1, f.module.relicPurchasesThisCycle)
            assertEquals(0, f.module.refreshesUsedInVisit)
            assertTrue(f.runtime.log.filter(kind = K.STATE_REDUCED, reasonCode = "refresh_committed").isEmpty())
        }
    }

    @Test fun pauseRevokesPlansButRetainsAnExecutedRefreshForLateBalanceReconciliation() {
        val f = Fixture(area = 5)
        val refresh = f.readyToRefresh(1_000L)
        val dispatched = f.dispatch(refresh, 1_200L)
        f.module.resetShopScrollState(capturedAfter = 1_201L)
        assertFalse(requireNotNull(refresh.permitToken).isCurrent(1_201L))
        // The backend can return Executed after pause revoked its planning permit.
        assertNotNull(f.module.recordActionExecuted(SHOP_REFRESH, 1_210L, cause = dispatched))
        f.module.resetShopScrollState(capturedAfter = 10_000L)
        val paid = shelf(coins = 19_700)
        val first = f.refresh(paid, 10_100L)
        assertEquals(LabyrinthShopRefreshOutcome.UNRESOLVED, first.refreshOutcome)
        assertWait(first, "未结算")
        assertEquals(0, f.module.refreshesUsedInVisit)
        assertEquals(LabyrinthShopRefreshOutcome.COMMITTED, f.refresh(paid, 10_200L).refreshOutcome)
        assertEquals(1, f.module.refreshesUsedInVisit)
    }

    @Test fun oneExpiredPaidFrameCannotHoldAnUnresolvedVisitOpen() {
        val f = Fixture(area = 5)
        f.perform(f.readyToRefresh(1_000L), 1_200L)
        val paid = shelf(coins = 19_700)
        assertWait(f.refresh(paid, 10_000L), "未结算")
        val stopped = f.module.handleRefreshConfirmation(paid, 13_001L, nowMillis = 13_001L, capturedAt = 10_000L)
        assertStopped(requireNotNull(stopped), "刷新结果无法核实")
        assertEquals(0, f.module.refreshesUsedInVisit)
    }

    @Test fun onlyFullCardsCanContributeIdentityOrAnActionableTarget() {
        val f = Fixture()
        val item = relicItem(relic("cropped"))
        for (visibility in LabyrinthShopItemVisibility.entries.filter { it != LabyrinthShopItemVisibility.FULL }) {
            val clipped = shop(item.copy(visibility = visibility))
            f.plan(clipped, 1_000L).expectAction(SHOP_CLOSE)
            f.plan(clipped, 1_100L).expectAction(SHOP_CLOSE)
        }
        assertWait(f.plan(shop(item), 1_200L), "复核待购买遗物身份")
        val buy = f.plan(shop(item), 1_300L).expectAction(SHOP_BUY_RELIC)
        f.plan(shop(item.copy(visibility = LabyrinthShopItemVisibility.PARTIAL)), 1_400L).expectAction(SHOP_CLOSE)
        assertFalse(requireNotNull(buy.permitToken).isCurrent(1_400L))
    }

    @Test fun aWalletReadingMustBeConfirmedAndTheCoinDeadlinePrecedesTheScan() {
        for (area in 3..5) {
            val f = Fixture(area)
            val unconfirmed = shelf(coins = 20_000, confirmed = false)
            assertWait(f.plan(unconfirmed, 1_000L), "等待金币识别")
            assertNull(f.module.observedAlphaCoin)
            assertWait(f.plan(unconfirmed, 30_999L), "等待金币识别")
            assertStopped(f.plan(unconfirmed, 31_000L), "商店金币超过30秒")
        }
        val f = Fixture(area = 4)
        val unread = shelf(relicItem(relic("partial")).copy(visibility = LabyrinthShopItemVisibility.PARTIAL),
            coins = 8_000, confirmed = false)
        assertWait(f.plan(unread, 1_000L), "等待金币识别")
        f.plan(unread, 1_100L, coins = 8_000).expectAction(SHOP_CLOSE)
        assertEquals(8_000, f.module.observedAlphaCoin)
        // A previous confirmed balance is not evidence on the next capture.
        assertWait(f.plan(unread, 1_200L), "等待金币识别")
        val confirmed = unread.copy(shopObservation = requireNotNull(unread.shopObservation).copy(coinBalanceConfirmed = true))
        f.plan(confirmed, 1_300L).expectAction(SHOP_CLOSE)
    }

    @Test fun overlayAndPermitRequireTheSameRefreshBaselineEvenWhenEarlyRefreshIsEnabled() {
        val policy = LabyrinthShopPolicy(finalArea = 1)
        val f = Fixture(policy = policy)
        val goods = Array(3) { imprintItem().copy(slotId = "imprint-$it") }
        for (stock in listOf(shelf(*goods, confirmed = false), shelf(*goods, coins = 299), shelf(*goods, visual = 0L))) {
            val displayed = f.module.currentShopDecision(stock, policy, 1, emptyList(), emptyMap())
            assertTrue(displayed is LabyrinthShopDecision.Wait)
            assertWait(f.plan(stock, 1_000L), (displayed as LabyrinthShopDecision.Wait).reason)
        }
        val ready = shelf(*goods)
        assertTrue(f.module.currentShopDecision(ready, policy, 1, emptyList(), emptyMap()) is LabyrinthShopDecision.Refresh)
        f.plan(ready, 1_100L).expectAction(SHOP_REFRESH)
    }

    @Test fun unreadOrMovingStockRevokesATapAndCannotAuthorizeARefresh() {
        val f = Fixture(area = 5)
        val imprint = imprintItem().copy(choiceRoleImprint = false)
        val ready = shelf(imprint)
        assertWait(f.plan(ready, 1_000L), "画面稳定")
        val buy = f.plan(ready, 1_100L).expectAction(SHOP_BUY_IMPRINT)
        val moving = shelf(imprint, visual = 99)
        assertWait(f.plan(moving, 1_200L), "画面稳定")
        assertFalse(requireNotNull(buy.permitToken).isCurrent(1_200L))
        val unread = shelf(imprint.copy(status = LabyrinthShopItemStatus.UNKNOWN), visual = 99)
        assertWait(f.plan(unread, 1_300L), "等待本页可见商品")
        val align = f.plan(unread, 3_000L)
        assertTrue(align.scanStep is LabyrinthShopScanController.Step.Reposition)
        assertNull(align.permitToken)
        assertStopped(f.plan(unread, 24_000L), "未完成覆盖")
        assertFalse(f.module.shopScan.complete)
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
        val oldPermit = f.readyToRefresh(7_000L)
        f.perform(oldPermit, 7_200L)
        val oldConfirm = f.refresh(refreshDialog(8_700L), 8_700L).expectAction(SHOP_CONFIRM_REFRESH)
        assertNotNull(f.module.shopRefreshTransaction.pending)
        assertTrue(f.module.shopScan.complete)
        f.module.onEnterShopNode(area = 5, timestampMillis = 8_750L)
        assertEquals(ACCELERATION, f.module.focusMark)
        assertEquals(0, f.module.relicPurchasesThisCycle)
        assertFalse(requireNotNull(oldConfirm.permitToken).isCurrent(8_750L))
        assertNull(f.module.shopRefreshTransaction.pending)
        assertFalse(f.module.shopScan.complete)
        assertNull(f.module.scrollStage)
        assertNull(f.module.observedAlphaCoin)

        assertNull(f.module.recordActionExecuted(SHOP_REFRESH, 9_000L))
        f.perform(f.readyToRefresh(9_000L), 9_200L)
        assertEquals(0, f.module.refreshesUsedInVisit)
        val confirm = f.refresh(refreshDialog(10_700L), 10_700L).expectAction(SHOP_CONFIRM_REFRESH)
        f.perform(confirm, 10_700L)
        val paid = shelf(coins = 19_700)
        f.refresh(paid, 11_400L)
        f.refresh(paid, 11_500L)
        assertEquals(1, f.module.refreshesUsedInVisit)
        f.completePurchase(relic("after-refresh"), 11_600L)
        f.area = 1
        val pending = f.plan(shop(imprintItem()), 12_100L, coins = 1_820, scroll = 1).expectAction(SHOP_BUY_IMPRINT)
        f.perform(pending, 12_100L)
        f.perform(f.plan(confirmation(null), 12_200L).expectAction(SHOP_CONFIRM_IMPRINT), 12_200L)
        val outstanding = f.plan(frame(SHOP_PURCHASE_COMPLETE), 12_300L).expectAction(SHOP_CLOSE_PURCHASE_COMPLETE)
        assertTrue(f.module.choiceImprintRolePending)
        assertEquals(1, f.module.relicPurchasesThisCycle)
        assertEquals(1, f.module.refreshesUsedInVisit)
        assertEquals(ACCELERATION, f.module.focusMark)
        f.runtime.resetSession("next-shop-run")
        assertFalse(requireNotNull(outstanding.permitToken).isCurrent(12_300L))
        assertNull(f.module.capturePermit(requireNotNull(outstanding.actionKey), 12_300L))
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

    @Test fun resetSessionClearsPendingRefreshAndPreparedNavigation() {
        val f = Fixture(area = 5)
        f.perform(f.readyToRefresh(1_000L), 1_200L)
        val confirm = f.refresh(refreshDialog(3_000L), 3_000L).expectAction(SHOP_CONFIRM_REFRESH)
        val visit = f.module.visitProgress()
        assertEquals(1_200L, visit.refreshStartedAt)
        f.runtime.resetSession("next")
        assertNull(f.module.shopRefreshTransaction.pending)
        assertFalse(f.module.shopScan.complete)
        assertNull(f.module.scrollStage)
        assertFalse(requireNotNull(confirm.permitToken).isCurrent(3_000L))
        assertNull(f.module.handleRefreshConfirmation(refreshDialog(3_100L), 3_100L))
        f.module.restoreVisit(visit)
        assertEquals(20_000, f.module.shopRefreshTransaction.pending?.coinsBefore)
        assertEquals(0, f.module.refreshesUsedInVisit)
        assertFalse(requireNotNull(confirm.permitToken).isCurrent(3_100L))
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun resumedShopSessionScansBothEndsAndReconcilesPaymentAcrossPause() = runTest {
        val node = LabyrinthNodeJson(5, 1, 1, 50101L, LabyrinthNodeTypes.SHOP, null, emptyList(), false)
        val route = LabyrinthRouteJson(1, 1, 1, 1, false, false, "fixture", emptyList(), emptyList(),
            listOf(node), allNodes = listOf(node), currentBlockId = node.blockId)
        val manager = AutomationSessionManager()
        var now = 1_000L
        val inputs = mutableListOf<AutomationAction>()
        val session = LabyrinthEntryRecognitionSession(manager, captureActive = { true },
            processorFactory = { { error("replay supplies shop observations") } }, actionScope = this, clock = { now },
            routeLoader = { route }, routeExecutionGate = { LabyrinthExecutionGateResult.Allowed(route, "offline") },
            nodeTemplateLoader = { NodeTemplateSet(emptyMap()) },
            actionExecutor = SessionBoundActionExecutor(manager) { inputs += it; AutomationBackendResult.Completed })
        val process = session.javaClass.declaredMethods.single {
            it.name.startsWith("processFrameResult-") && it.parameterCount == 6
        }.apply { isAccessible = true }
        suspend fun feed(result: LabyrinthEntryFrameResult, advance: Long = 2_000L) {
            now += advance
            suspendCoroutineUninterceptedOrReturn<Unit> { continuation ->
                try {
                    process.invoke(session, requireNotNull(session.state.value.sessionId).value, result,
                        WIDTH, HEIGHT, now, continuation)
                } catch (failure: InvocationTargetException) { throw failure.targetException }
            }
            runCurrent()
        }
        val stock = shelf()
        val top = stock.copy(shopObservation = requireNotNull(stock.shopObservation).copy(viewport = LabyrinthShopViewport(true, false, 0)))
        val bottom = stock.copy(shopObservation = requireNotNull(stock.shopObservation).copy(viewport = LabyrinthShopViewport(false, true, 1000),
            visualFingerprint = 82L))
        val runtime = session.kernelRuntime
        try {
            assertTrue(session.startAutomation(1L) is LabyrinthEntryRecognitionStartResult.Started)
            session.javaClass.getDeclaredField("entryPhaseComplete").apply { isAccessible = true }.setBoolean(session, true)
            runCurrent()
            val startVersion = session.shopEvidenceVersion
            repeat(3) { feed(top) }
            val up = inputs.single() as AutomationAction.Swipe
            assertTrue(up.start.y > up.end.y)
            assertEquals(startVersion + 1, session.shopEvidenceVersion)
            val frames = session.state.value.frameCount
            feed(top, LabyrinthShopScanController.SETTLE_MILLIS)
            assertEquals(frames, session.state.value.frameCount)
            repeat(3) { feed(bottom) }
            val down = inputs.last() as AutomationAction.Swipe
            assertTrue(down.start.y < down.end.y)
            assertEquals(2, inputs.size)
            repeat(2) { feed(top) }
            assertEquals(3, inputs.size)
            assertEquals(SHOP_REFRESH.name, (runtime.log.filter(KernelDomain.SHOP, K.ACTION_RECEIPT_OBSERVED).last().payload as ActionReceiptPayload).choiceId)
            feed(refreshDialog(now + 2_000L).copy(shopRefreshDialog = null))
            assertEquals(3, inputs.size)
            feed(refreshDialog(now + 2_000L))
            val confirm = runtime.log.filter(KernelDomain.SHOP, K.ACTION_RECEIPT_OBSERVED).last()
            assertEquals(SHOP_CONFIRM_REFRESH.name, (confirm.payload as ActionReceiptPayload).choiceId)
            assertTrue(session.setPaused(true))
            now += 9_000L
            assertTrue(session.setPaused(false))
            val paid = shelf(coins = 19_700, visual = 90)
            feed(paid, 100L)
            assertTrue(runtime.log.filter(reasonCode = "refresh_committed").isEmpty())
            feed(paid, 100L)
            val commit = runtime.log.filter(KernelDomain.SHOP, K.STATE_REDUCED, reasonCode = "refresh_committed").single()
            assertTrue(confirm in runtime.log.causalChain(commit.eventSeq))
            assertEquals("1", (commit.payload as StateReducedPayload).detail["refreshesUsedInVisit"])
            assertEquals(4, inputs.size)
            assertEquals(4, session.state.value.actionCount)
        } finally { session.stop() }
    }

    @Test fun sessionResetClearsIdentityEvidenceWhenTheNextSessionStartsOnTheSamePage() {
        val f = Fixture()
        val stock = shop(relicItem(relic("same-visible-relic")))
        assertWait(f.plan(stock, 1_000L), "复核待购买遗物身份")
        f.runtime.resetSession("new-shop-run")
        assertWait(f.plan(stock, 1_100L), "复核待购买遗物身份")
        f.plan(stock, 1_200L).expectAction(SHOP_BUY_RELIC)
    }

    private class Fixture(var area: Int = 1, private val policy: LabyrinthShopPolicy = LabyrinthShopPolicy()) {
        private var now = 0L
        val runtime = KernelRuntime(clock = { now })
        val module = ShopDomainModule(runtime)
        val cancelled = mutableListOf<String?>()
        private val identity = LabyrinthRelicIdentityGate()

        init {
            runtime.registerModule(module)
            module.onEnterShopNode(area = area, cause = runtime.resetSession("shop-run"))
        }

        fun plan(result: LabyrinthEntryFrameResult, at: Long, coins: Int? = null, scroll: Int? = null,
                 purchaseAlreadyObserved: Boolean = false): ShopPlanOutcome {
            now = at
            return module.planShopPage(result, result.observation.state, policy, area, emptyList(),
                mapOf(ACCELERATION to 6), identity, { true }, { cancelled.add(it) },
                WIDTH, HEIGHT, at, observedAlphaCoin = coins, scrollStage = scroll,
                purchaseAlreadyObserved = purchaseAlreadyObserved)
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

        fun perform(outcome: ShopPlanOutcome, at: Long): KernelEvent {
            val receipt = execute(outcome, at, dispatch(outcome, at))
            val chain = runtime.log.causalChain(receipt.eventSeq)
            assertEquals(listOf(K.EVENT_RULE_EVALUATED, K.EVENT_DECISION_PROPOSED, K.PERMIT_ISSUED,
                K.ACTION_DISPATCHED, K.ACTION_RECEIPT_OBSERVED), chain.takeLast(5).map { it.kind })
            assertTrue(chain.dropLast(5).any { it.domain == KernelDomain.SHOP && it.kind == K.FRAME_OBSERVED })
            return receipt
        }

        fun readyToRefresh(at: Long, stock: LabyrinthEntryFrameResult = shelf()): ShopPlanOutcome {
            assertWait(plan(stock, at), "画面稳定")
            assertWait(plan(stock, at + 100L), "底部可见商品")
            return plan(stock, at + 200L).expectAction(SHOP_REFRESH)
        }

        fun planBuy(relic: LabyrinthRelicMatch, at: Long): ShopPlanOutcome {
            val stock = if (area == 5) shelf(relicItem(relic)) else shop(relicItem(relic))
            assertWait(plan(stock, at), if (area == 5) "画面稳定" else "复核待购买遗物身份")
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

        fun shelf(vararg items: LabyrinthShopItemMatch, coins: Int? = 20_000, confirmed: Boolean = coins != null,
                  visual: Long = 71L, refreshScore: Double = 0.9): LabyrinthEntryFrameResult {
            val goods = items.toList().ifEmpty { listOf(imprintItem().copy(status = LabyrinthShopItemStatus.DISABLED)) }
            return shop(refreshScore = refreshScore).copy(shopObservation = LabyrinthShopObservation(goods,
                refreshButtonEnabledEvidence = 0.9, coinBalance = coins, coinBalanceConfirmed = confirmed,
                viewport = LabyrinthShopViewport(atTop = true, atBottom = true, position = 0), visualFingerprint = visual))
        }

        fun refreshDialog(at: Long, fee: Int = 300, kind: LabyrinthShopRefreshDialogKind = LabyrinthShopRefreshDialogKind.REFRESH) =
            frame(UNKNOWN).copy(nodeMoveConfirmation = LabyrinthNodeMoveConfirmationObservation(
                0.9, BUTTON, EntryPixelRect(500, 600, 100, 50)),
                shopRefreshDialog = LabyrinthShopRefreshDialog(kind, fee, confirmed = true, capturedAtMillis = at))

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

        fun assertStopped(outcome: ShopPlanOutcome, reason: String) {
            assertTrue(outcome.handled)
            assertNull(outcome.tapPlan)
            assertNull(outcome.permitToken)
            val stop = outcome.decision as KernelDecision.FinishRun
            assertFalse(stop.cleared)
            assertTrue(stop.reason, stop.reason.contains(reason))
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
