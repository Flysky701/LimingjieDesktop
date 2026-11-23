package com.landosol.toolbox.labyrinth

import com.landosol.toolbox.labyrinth.vision.EntryPixelRect
import com.landosol.toolbox.labyrinth.vision.LabyrinthRelicMatch
import com.landosol.toolbox.labyrinth.vision.LabyrinthShopItemKind
import com.landosol.toolbox.labyrinth.vision.LabyrinthShopItemVisibility
import com.landosol.toolbox.labyrinth.vision.LabyrinthShopCategoryState
import com.landosol.toolbox.labyrinth.vision.LabyrinthShopItemMatch
import com.landosol.toolbox.labyrinth.vision.LabyrinthShopItemStatus
import com.landosol.toolbox.labyrinth.vision.LabyrinthShopObservation
import com.landosol.toolbox.labyrinth.vision.LabyrinthShopViewport
import org.junit.Assert.*
import org.junit.Test

class LabyrinthShopScanControllerTest {
    @Test fun replenishedGoodsStayInPlaceAndOnlyExhaustionStartsSearch() {
        val driver = Driver()
        // More than three purchases: each new item is recognized in the same top viewport.
        repeat(4) { index ->
            val current = shop(top = true, position = 0, fingerprint = 10L + index)
                .copy(items = listOf(oneStar("refill-" + index)))
            val step = driver.stable(current) as LabyrinthShopScanController.Step.Evaluate
            assertFalse(step.complete)
            assertTrue(decision(current, step) is LabyrinthShopDecision.BuyRelic)
            driver.controller.purchased(driver.now)
            assertEquals(LabyrinthShopScanController.Phase.BROWSE, driver.controller.phase)
            driver.now += LabyrinthShopScanController.SETTLE_MILLIS + 1
        }
        val imprints = shop(top = true, position = 0, fingerprint = 20)
            .copy(items = listOf(item().copy(kind = LabyrinthShopItemKind.ROLE_IMPRINT,
                roleImprintLabel = "攻击型职能印记")))
        val localStep = driver.stable(imprints) as LabyrinthShopScanController.Step.Evaluate
        assertTrue("original local policy must not defer a permitted imprint", decision(imprints, localStep) is LabyrinthShopDecision.BuyRoleImprint)
        val gated = LabyrinthShopPolicy().decide(3, imprints.items, emptyList(), refreshAvailable = true,
            coinBalance = 5_575, stockScanComplete = false)
        assertEquals(LabyrinthShopWaitKind.SCAN, (gated as LabyrinthShopDecision.Wait).kind)

        // The following sold/disabled frame follows a real local purchase receipt, not an external change.
        driver.controller.purchased(driver.now)
        driver.now += LabyrinthShopScanController.SETTLE_MILLIS + 1
        val top = shop(top = true, position = 0, fingerprint = 30).copy(items = listOf(disabled()))
        val bottom = shop(bottom = true, position = 1000, fingerprint = 31).copy(items = listOf(disabled()))
        val exhausted = driver.stable(top) as LabyrinthShopScanController.Step.Evaluate
        assertEquals(LabyrinthShopWaitKind.SCAN, (decision(top, exhausted) as LabyrinthShopDecision.Wait).kind)
        assertSwipe(driver.controller.advance(top, driver.now), true)
        assertFalse((driver.swipe(true, top, bottom) as LabyrinthShopScanController.Step.Evaluate).complete)
        assertTrue(driver.controller.advance(bottom, driver.now) is LabyrinthShopScanController.Step.Wait)
        assertEquals(LabyrinthShopScanController.Phase.RETURN_TOP, driver.controller.phase)
        assertSwipe(driver.next(bottom), false)
        val verified = driver.swipe(false, bottom, top) as LabyrinthShopScanController.Step.Evaluate
        assertTrue(verified.complete)
        assertTrue(decision(top, verified) is LabyrinthShopDecision.Refresh)
    }

    @Test fun noMotionRequiresSeparateExecutedInputsEvenWithAnInconclusiveScrollbar() {
        for (knownViewport in listOf(false, true)) {
            val driver = Driver()
            val top = shop(top = true, position = if (knownViewport) 0 else null, fingerprint = 41)
            val unchanged = shop(position = if (knownViewport) 838 else null, fingerprint = 42)
            driver.stable(top)
            driver.swipe(true, top, unchanged)
            val first = driver.controller.submitSwipe(true, unchanged, driver.now)
            val oldCapture = driver.now
            val completedAt = driver.now + 100
            assertTrue(driver.controller.swipeExecuted(first, completedAt))
            assertFalse(driver.controller.swipeExecuted(first, completedAt))
            repeat(3) {
                assertTrue(driver.controller.observe(unchanged, completedAt + 900, oldCapture) is LabyrinthShopScanController.Step.Wait)
            }
            driver.now = completedAt + LabyrinthShopScanController.SETTLE_MILLIS + 1
            assertTrue(driver.next(unchanged) is LabyrinthShopScanController.Step.Wait)
            repeat(3) {
                assertTrue(driver.controller.observe(unchanged, driver.now, driver.now) is LabyrinthShopScanController.Step.Wait)
            }
            driver.next(unchanged)
            assertSwipe(driver.controller.advance(unchanged, driver.now), true)
            val suppressed = driver.controller.submitSwipe(true, unchanged, driver.now)
            driver.controller.swipeNotExecuted(suppressed)
            assertFalse(driver.controller.swipeExecuted(suppressed, driver.now + 100))
            driver.stable(unchanged)
            assertSwipe(driver.controller.advance(unchanged, driver.now), true)
            driver.swipe(true, unchanged, unchanged)
            assertTrue(driver.controller.advance(unchanged, driver.now) is LabyrinthShopScanController.Step.Wait)
            assertEquals(LabyrinthShopScanController.Phase.RETURN_TOP, driver.controller.phase)
            assertFalse(driver.controller.complete)
        }
    }

    @Test fun receiptsRevokeOldInputsWithoutForcingReturnTop() {
        for (purchase in listOf(false, true)) {
            val driver = Driver()
            val bottom = shop(bottom = true, position = 1000, fingerprint = 9)
            driver.stable(bottom)
            val oldId = driver.controller.submitSwipe(true, bottom, driver.now)
            val barrier = driver.now + 1_000
            if (purchase) driver.controller.purchased(barrier - LabyrinthShopScanController.SETTLE_MILLIS)
            else driver.controller.reset(capturedAfter = barrier)
            assertFalse(driver.controller.swipeExecuted(oldId, barrier + 10))
            driver.controller.swipeNotExecuted(oldId, "stale rejection")
            assertTrue(driver.controller.observe(bottom, barrier + 1, barrier) is LabyrinthShopScanController.Step.Wait)
            driver.now = barrier + 1
            assertTrue(driver.stable(bottom) is LabyrinthShopScanController.Step.Evaluate)
            assertEquals("receipt must not request a return swipe", LabyrinthShopScanController.Phase.BROWSE, driver.controller.phase)
            assertFalse(driver.controller.complete)
        }
    }

    @Test fun readBudgetAndReplayedFramesAreBounded() {
        val driver = Driver()
        val reading = shop(top = true, position = 0, fingerprint = 1)
            .copy(items = listOf(item().copy(categoryState = LabyrinthShopCategoryState.READING)))
        assertTrue(driver.stable(reading) is LabyrinthShopScanController.Step.Wait)
        for (i in 1..3) {
            driver.now += 5_000
            assertTrue(driver.stable(reading.copy(visualFingerprint = 1L + i)) is LabyrinthShopScanController.Step.Wait)
            assertTrue(driver.controller.advance(reading, driver.now) is LabyrinthShopScanController.Step.Wait)
        }
        driver.now += 5_000
        assertTrue(driver.next(reading) is LabyrinthShopScanController.Step.Stop)
        assertFalse(driver.controller.complete)
        val search = Driver()
        val upper = shop(top = true, position = 0, fingerprint = 7).copy(items = listOf(disabled()))
        search.stable(upper)
        search.controller.advance(upper, search.now)
        val unknownBottom = shop(bottom = true, position = 1000, fingerprint = 8)
            .copy(items = listOf(item().copy(status = LabyrinthShopItemStatus.UNKNOWN,
                categoryState = LabyrinthShopCategoryState.EXHAUSTED, categoryAttempt = 0)))
        assertTrue(search.swipe(true, upper, unknownBottom) is LabyrinthShopScanController.Step.Wait)
        assertTrue(search.controller.advance(unknownBottom, search.now) is LabyrinthShopScanController.Step.Wait)
        assertEquals(LabyrinthShopScanController.Phase.BROWSE, search.controller.phase)
        val pending = Driver()
        val bottom = shop(bottom = true, position = 1000, fingerprint = 8)
            .copy(items = listOf(item().copy(roleImprintLabel = null, imprintLabelPending = true)))
        assertTrue(pending.stable(bottom) is LabyrinthShopScanController.Step.Wait)
        assertTrue(pending.controller.advance(bottom, pending.now) is LabyrinthShopScanController.Step.Wait)
        assertEquals(LabyrinthShopScanController.Phase.BROWSE, pending.controller.phase)
        val ready = bottom.copy(items = listOf(item()))
        val step = pending.stable(ready) as LabyrinthShopScanController.Step.Evaluate
        assertTrue(LabyrinthShopPolicy().decide(4, ready.items, emptyList(), refreshAvailable = true,
            coinBalance = 9_400, stockScanComplete = step.complete) is LabyrinthShopDecision.BuyRoleImprint)
        val stalled = Driver()
        stalled.stable(shop(top = true, position = 0, fingerprint = 1))
        val frozen = stalled.now
        assertTrue(stalled.controller.observe(ready, frozen + LabyrinthShopScanController.ITEM_READ_TIMEOUT + 1,
            frozen) is LabyrinthShopScanController.Step.Stop)
    }

    @Test fun failedInputsUnknownViewsAndMovingSearchNeverAuthorizeRefresh() {
        val rejected = Driver()
        val top = shop(top = true, position = 0, fingerprint = 1)
        rejected.stable(top)
        val id = rejected.controller.submitSwipe(true, top, rejected.now)
        rejected.controller.swipeNotExecuted(id, "gesture rejected")
        assertFalse(rejected.controller.swipeExecuted(id, rejected.now + 100))
        assertTrue(rejected.next(top) is LabyrinthShopScanController.Step.Stop)

        val moving = Driver()
        var before = top
        moving.stable(before)
        repeat(6) { index ->
            assertSwipe(moving.controller.advance(before, moving.now), true)
            val after = shop(position = (index + 1) * 100, fingerprint = index + 2L)
            moving.swipe(true, before, after)
            before = after
        }
        assertTrue(moving.controller.advance(before, moving.now) is LabyrinthShopScanController.Step.Stop)
        assertFalse(moving.controller.complete)

        val unknown = shop(top = true, bottom = true, position = 0, fingerprint = 9)
            .copy(items = listOf(item().copy(status = LabyrinthShopItemStatus.UNKNOWN,
                categoryState = LabyrinthShopCategoryState.EXHAUSTED)))
        val noButtons = Driver()
        noButtons.stable(unknown)
        assertTrue(noButtons.controller.advance(unknown, noButtons.now) is LabyrinthShopScanController.Step.Wait)
        assertFalse(noButtons.controller.complete)
        assertTrue(noButtons.controller.advance(unknown, noButtons.now + LabyrinthShopScanController.ITEM_READ_TIMEOUT + 1)
            is LabyrinthShopScanController.Step.Stop)

        val completed = Driver()
        val known = unknown.copy(items = listOf(disabled()), visualFingerprint = 10)
        completed.stable(known)
        completed.controller.advance(known, completed.now)
        assertTrue(completed.controller.complete)
        assertTrue(completed.stable(unknown) is LabyrinthShopScanController.Step.Wait)
        repeat(2) {
            val adjustment = completed.controller.submitSwipe(false, unknown, completed.now, alignment = true)
            assertTrue(completed.controller.swipeExecuted(adjustment, completed.now + 100))
            completed.now += LabyrinthShopScanController.SETTLE_MILLIS + 101
            completed.stable(unknown)
        }
        completed.now += LabyrinthShopScanController.ITEM_READ_TIMEOUT + 1
        assertTrue(completed.next(unknown) is LabyrinthShopScanController.Step.Stop)
        val external = Driver()
        val allFit = shop(top = true, bottom = true, position = 0, fingerprint = 14)
        external.stable(allFit)
        external.controller.advance(allFit, external.now)
        assertTrue(external.controller.complete)
        val changed = allFit.copy(items = listOf(item().copy(roleImprintLabel = "治疗型职能印记")), visualFingerprint = 15)
        assertTrue(external.stable(changed) is LabyrinthShopScanController.Step.Reacquire)
        assertFalse(external.controller.complete)
        external.controller.reset(capturedAfter = external.now)
        assertTrue(external.controller.observe(changed, external.now + 1, external.now) is LabyrinthShopScanController.Step.Wait)
    }

    @Test fun areaFourContinuesPartialForwardSearchWithoutSkippingReadableGoods() {
        val top = shop(top = true, position = 0, fingerprint = 91).copy(items = listOf(disabled()))
        val middle = shop(position = 924, fingerprint = 92).copy(layoutReady = false,
            items = listOf(item().copy(visibility = LabyrinthShopItemVisibility.PARTIAL,
                status = LabyrinthShopItemStatus.UNKNOWN, kind = LabyrinthShopItemKind.UNKNOWN)))
        for (area in listOf(3, 4, 5)) {
            val driver = Driver()
            driver.stable(top, area)
            assertSwipe(driver.controller.advance(top, driver.now), true)
            val next = driver.swipe(true, top, middle, area)
            if (area == 4) {
                assertSwipe(next, true)
                assertFalse(driver.controller.complete)
                assertTrue(driver.controller.advance(middle, driver.now) is LabyrinthShopScanController.Step.Wait)
                val pendingBottom = shop(bottom = true, position = 1000, fingerprint = 93)
                    .copy(items = listOf(item().copy(roleImprintLabel = null, imprintLabelPending = true)))
                assertTrue(driver.swipe(true, middle, pendingBottom, area) is LabyrinthShopScanController.Step.Wait)
                driver.now += 2_000
                assertTrue(driver.next(pendingBottom, area) is LabyrinthShopScanController.Step.Wait)
                assertEquals(LabyrinthShopScanController.Phase.BROWSE, driver.controller.phase)
                val ready = pendingBottom.copy(items = listOf(item()))
                val evaluated = driver.stable(ready, area) as LabyrinthShopScanController.Step.Evaluate
                assertTrue(LabyrinthShopPolicy().decide(4, ready.items, emptyList(), refreshAvailable = true,
                    coinBalance = 8_500, stockScanComplete = evaluated.complete) is LabyrinthShopDecision.BuyRoleImprint)
            } else {
                assertTrue(next is LabyrinthShopScanController.Step.Wait)
                driver.now += 1_600
                assertTrue(driver.next(middle, area) is LabyrinthShopScanController.Step.Reposition)
            }
        }
        val stuck = Driver()
        stuck.stable(top, 4)
        stuck.controller.advance(top, stuck.now)
        stuck.swipe(true, top, middle, 4)
        assertSwipe(stuck.swipe(true, middle, middle, 4), true)
        assertTrue(stuck.swipe(true, middle, middle, 4) is LabyrinthShopScanController.Step.Wait)
        stuck.now += 1_600
        val adjustment = stuck.next(middle, 4) as LabyrinthShopScanController.Step.Reposition
        assertFalse("proved no movement must not keep searching down", adjustment.up)
        assertFalse(stuck.controller.complete)
    }

    private fun assertSwipe(step: LabyrinthShopScanController.Step, up: Boolean) {
        assertTrue("expected swipe up=" + up + ", actual=" + step, step is LabyrinthShopScanController.Step.Swipe)
        assertEquals(up, (step as LabyrinthShopScanController.Step.Swipe).up)
    }
    private class Driver {
        val controller = LabyrinthShopScanController()
        var now = 1_000L
        fun next(shop: LabyrinthShopObservation, area: Int? = null): LabyrinthShopScanController.Step {
            now++
            return controller.observe(shop, now, now, currentArea = area)
        }
        fun stable(shop: LabyrinthShopObservation, area: Int? = null): LabyrinthShopScanController.Step { next(shop, area); return next(shop, area) }
        fun swipe(up: Boolean, before: LabyrinthShopObservation, after: LabyrinthShopObservation, area: Int? = null): LabyrinthShopScanController.Step {
            val id = controller.submitSwipe(up, before, now)
            val completedAt = now + 100
            assertTrue(controller.swipeExecuted(id, completedAt))
            now = completedAt + LabyrinthShopScanController.SETTLE_MILLIS
            return stable(after, area)
        }
    }
    private fun shop(top: Boolean = false, bottom: Boolean = false, position: Int? = null, fingerprint: Long) =
        LabyrinthShopObservation(items = listOf(item()), visualFingerprint = fingerprint,
            viewport = position?.let { LabyrinthShopViewport(top, bottom, it) })
    private fun decision(shop: LabyrinthShopObservation, step: LabyrinthShopScanController.Step.Evaluate) =
        LabyrinthShopPolicy().decide(5, shop.items, emptyList(), refreshAvailable = true,
            coinBalance = 10_000, stockScanComplete = step.complete)
    private fun oneStar(id: String): LabyrinthShopItemMatch {
        val rect = EntryPixelRect(0, 0, 10, 10)
        return item().copy(kind = LabyrinthShopItemKind.RELIC,
            relicMatch = LabyrinthRelicMatch(slotId = "shop_item_1", relicId = id, displayName = id,
                attribute = "会心", attributeBonus = "1", effect = null, rarity = 1, confidence = 0.9,
                screenRect = rect, iconRect = rect, suspectedRelicId = id, suspectedDisplayName = id,
                suspectedAttribute = "会心", rivalMargin = 0.2))
    }
    private fun disabled() = item().copy(status = LabyrinthShopItemStatus.DISABLED)
    private fun item(): LabyrinthShopItemMatch {
        val rect = EntryPixelRect(0, 0, 10, 10)
        return LabyrinthShopItemMatch("shop_item_1", rect, rect, 0.9, status = LabyrinthShopItemStatus.AVAILABLE,
            kind = LabyrinthShopItemKind.ROLE_IMPRINT, roleImprintLabel = "攻击型职能印记", titleText = "攻击型职能的随机印记")
    }
}
