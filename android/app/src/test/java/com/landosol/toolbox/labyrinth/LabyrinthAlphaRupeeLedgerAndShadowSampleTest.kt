package com.landosol.toolbox.labyrinth

import com.landosol.toolbox.labyrinth.kernel.KernelDomain
import com.landosol.toolbox.labyrinth.kernel.KernelEventKinds
import com.landosol.toolbox.labyrinth.kernel.KernelRuntime
import com.landosol.toolbox.labyrinth.kernel.BattleSamplePayload
import com.landosol.toolbox.labyrinth.kernel.strategy.BattleRosterUnit
import com.landosol.toolbox.labyrinth.kernel.strategy.BattleValueContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LabyrinthAlphaRupeeLedgerAndShadowSampleTest {
    @Test fun ledgerAnchorsUpdatesAndClearsUnknowns() {
        val ledger = LabyrinthAlphaRupeeLedger()
        assertNull(ledger.currentBalance)

        // Unconfirmed shop coins do not anchor.
        ledger.onShopCoinsObserved(5000, confirmed = false, timestampMillis = 100L)
        assertNull(ledger.currentBalance)

        // Confirmed coins anchor the balance.
        ledger.onShopCoinsObserved(5000, confirmed = true, timestampMillis = 200L)
        assertEquals(5000, ledger.currentBalance)

        // Deterministic 1-star relic with 25% node21 + 10% relic 40003 = 1800 * 65 / 100 = 1170.
        val price = LabyrinthAlphaRupeeLedger.estimatedShopItemPrice(
            rarity = 1, isRoleImprint = false, isChoiceImprint = false, enhanceNode21 = true, hasRelic40003 = true,
        )
        assertEquals(1170, price)
        ledger.onShopPurchaseCommitted(price, timestampMillis = 300L)
        assertEquals(3830, ledger.currentBalance)

        // Refresh decrements when observedCoinsAfter is null, or re-anchors when confirmed.
        ledger.onShopRefreshCommitted(fee = 300, observedCoinsAfter = null, timestampMillis = 400L)
        assertEquals(3530, ledger.currentBalance)
        ledger.onShopRefreshCommitted(fee = 600, observedCoinsAfter = 2900, timestampMillis = 500L)
        assertEquals(2900, ledger.currentBalance)

        // Deterministic event choices update the balance.
        ledger.onEventChoiceCommitted("11011", timestampMillis = 600L)
        assertEquals(3100, ledger.currentBalance)
        ledger.onEventChoiceCommitted("11071", timestampMillis = 700L)
        assertEquals(1100, ledger.currentBalance)

        // Variable / random gold events clear the balance instead of keeping stale figures.
        ledger.onEventChoiceCommitted("23161", timestampMillis = 800L)
        assertNull(ledger.currentBalance)

        // Reset restores null.
        ledger.onShopCoinsObserved(4200, confirmed = true, timestampMillis = 900L)
        assertEquals(4200, ledger.currentBalance)
        ledger.reset()
        assertNull(ledger.currentBalance)
    }

    @Test fun recorderEmitsOncePerAttemptPreservesRoundAcrossRetriesAndResetsOnNodeTransition() {
        val runtime = KernelRuntime()
        val recorder = LabyrinthShadowBattleRecorder()
        val roster = (1001..1006).map { id ->
            val unitId = id * 100 + 1
            BattleRosterUnit(unitId, LabyrinthRoleProfile(id.toString(), "Role$id"))
        }
        val team = roster.take(5).map { it.unitId }
        val context = BattleValueContext(
            questId = 770530301,
            area = 5,
            difficulty = 5,
            ownedUnits = roster,
            ruleContext = LabyrinthRoleDecisionContext(0),
            relicIds = listOf(40003, 50002),
            markStacks = listOf(4, 4, 4, 0, 2),
            alphaRupee = 2900,
            reviveAvailable = true,
        )

        assertTrue(recorder.beginAttempt(context, team, LabyrinthCombatKind.BOSS, null, labyrinthGuildId = 1, startedAtMillis = 1_000L))
        val wipe = requireNotNull(recorder.completeAttempt(1, observedAtMillis = 25_000L, runtime = runtime))
        assertEquals(2, wipe.outcomeCounts.indexOf(1))
        assertEquals(0.0, wipe.meanDamageRatio, 0.0)
        assertEquals(2900, wipe.alphaRupee)
        assertEquals("boss_5f_d5", wipe.bucket)
        assertTrue(wipe.canonicalWaveSig.matches(Regex("[a-f0-9]{64}")))
        // Duplicate completion call without new START_BATTLE is a no-op.
        assertNull(recorder.completeAttempt(0, observedAtMillis = 26_000L, runtime = runtime))

        // Retry on the same node reuses round_id with a new sample_id.
        assertTrue(recorder.beginAttempt(context, team, LabyrinthCombatKind.BOSS, null, labyrinthGuildId = 1, startedAtMillis = 30_000L))
        val win = requireNotNull(recorder.completeAttempt(0, observedAtMillis = 60_000L, runtime = runtime))
        assertEquals(wipe.roundId, win.roundId)
        assertNotEquals(wipe.sampleId, win.sampleId)
        assertEquals(0, win.outcomeCounts.indexOf(1))
        assertEquals(1.0, win.meanDamageRatio, 0.0)
        assertNotNull(win.meanWinRemTime)

        // Timeout maps to outcome_class=1 on node transition with a fresh round_id.
        recorder.onNodeTransition()
        assertTrue(recorder.beginAttempt(context.copy(area = 2, questId = 770220101), team, LabyrinthCombatKind.EX, "ex_robot_guard", labyrinthGuildId = 1, startedAtMillis = 70_000L))
        val timeout = requireNotNull(recorder.completeAttempt(2, observedAtMillis = 160_000L, runtime = runtime))
        assertNotEquals(win.roundId, timeout.roundId)
        assertEquals("robot_ex", timeout.bucket)
        assertEquals(1, timeout.outcomeCounts.indexOf(1))

        val events = runtime.log.filter(domain = KernelDomain.BATTLE, kind = KernelEventKinds.BATTLE_SAMPLE_OBSERVED)
        assertEquals(3, events.size)
        val payload = events.last().payload as BattleSamplePayload
        assertEquals(timeout.sampleId, payload.sample.sampleId)
        assertFalse(recorder.hasPendingAttempt())
    }
}
