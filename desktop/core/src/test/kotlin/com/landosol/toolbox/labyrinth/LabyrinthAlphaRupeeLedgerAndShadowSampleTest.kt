package com.landosol.toolbox.labyrinth

import com.landosol.toolbox.labyrinth.kernel.KernelDomain
import com.landosol.toolbox.labyrinth.kernel.KernelEventKinds
import com.landosol.toolbox.labyrinth.kernel.KernelRuntime
import com.landosol.toolbox.labyrinth.kernel.BattleSamplePayload
import com.landosol.toolbox.labyrinth.kernel.strategy.BattleQuestCatalogMeta
import com.landosol.toolbox.labyrinth.kernel.strategy.BattleRosterUnit
import com.landosol.toolbox.labyrinth.kernel.strategy.BattleValueContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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

        // A start with no visual acknowledgement must not emit a sample or close the round.
        assertTrue(recorder.beginAttempt(context, team, LabyrinthCombatKind.BOSS, null, labyrinthGuildId = 1, startedAtMillis = 27_000L))
        recorder.cancelPendingAttempt()
        assertFalse(recorder.hasPendingAttempt())
        assertNull(recorder.completeAttempt(0, observedAtMillis = 28_000L, runtime = runtime))

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

    @Test fun shadowBattleSamplesExcludeMultiTeamBossAttempts() {
        fun shouldRecord(
            kind: LabyrinthCombatKind? = LabyrinthCombatKind.BOSS,
            mode: LabyrinthBossTeamMode = LabyrinthBossTeamMode.SINGLE_TEAM,
            plannedTeams: Int = 1,
            committedTeamCount: Int = 1,
            fallbackActive: Boolean = false,
            editorTab: Int? = null,
            combatTeamIndex: Int = 1,
        ) = labyrinthShouldRecordShadowBattleAttempt(
            kind, mode, plannedTeams, committedTeamCount, fallbackActive, editorTab, combatTeamIndex,
        )

        assertFalse(shouldRecord(kind = null))
        for (kind in listOf(LabyrinthCombatKind.NORMAL, LabyrinthCombatKind.EX)) {
            assertTrue(kind.name, shouldRecord(kind = kind, mode = LabyrinthBossTeamMode.MULTI_TEAM))
        }
        assertTrue(shouldRecord())
        assertTrue(shouldRecord(editorTab = 3, combatTeamIndex = 3))
        assertFalse(shouldRecord(mode = LabyrinthBossTeamMode.MULTI_TEAM))
        assertFalse(shouldRecord(fallbackActive = true))
        assertFalse(shouldRecord(combatTeamIndex = 2))
        assertFalse(shouldRecord(plannedTeams = 2))
        assertFalse(shouldRecord(committedTeamCount = 2))
    }

    @Test fun liveRecorderPreventsSlowWipeTimeoutFlipPreservesReviveAndUsesCatalogMeta() {
        val runtime = KernelRuntime()
        val recorder = LabyrinthShadowBattleRecorder()
        val roster = (1001..1005).map { id ->
            BattleRosterUnit(id * 100 + 1, LabyrinthRoleProfile(id.toString(), "Role$id"))
        }
        val team = roster.map { it.unitId }
        val context = BattleValueContext(
            questId = 770420801,
            area = 4,
            difficulty = 5,
            ownedUnits = roster,
            ruleContext = LabyrinthRoleDecisionContext(0),
            relicIds = listOf(50002),
            markStacks = List(5) { 0 },
            alphaRupee = 1200,
            reviveAvailable = true,
        )
        val sig = "b".repeat(64)
        val meta = BattleQuestCatalogMeta(bucket = "special_dual_ex", canonicalWaveSig = sig, dbVersion = "202611131613")

        // 1) Slow loading live BATTLE_FAILED (wall-clock >= 90s) must stay outcome=2 (wipe), NOT outcome=1 (timeout).
        assertTrue(recorder.beginAttempt(context, team, LabyrinthCombatKind.EX, null, labyrinthGuildId = 2, startedAtMillis = 1_000L, catalogMeta = meta))
        val slowWipe = requireNotNull(recorder.completeAttempt(resultCode = 1, observedAtMillis = 96_000L, runtime = runtime))
        assertEquals(listOf(0, 0, 1), slowWipe.outcomeCounts)
        assertEquals("special_dual_ex", slowWipe.bucket)
        assertEquals(sig, slowWipe.canonicalWaveSig)
        assertEquals("202611131613", slowWipe.dbVersion)

        // 2) Relic revive TWO_BUTTON failure preserves pending attempt and tags reviveTriggered=true on final completion.
        assertTrue(recorder.beginAttempt(context, team, LabyrinthCombatKind.EX, null, labyrinthGuildId = 2, startedAtMillis = 100_000L, catalogMeta = meta))
        recorder.markReviveTriggered()
        assertTrue(recorder.hasPendingAttempt())
        val revivedWin = requireNotNull(recorder.completeAttempt(resultCode = 0, observedAtMillis = 135_000L, runtime = runtime))
        assertEquals(listOf(1, 0, 0), revivedWin.outcomeCounts)

        val events = runtime.log.filter(domain = KernelDomain.BATTLE, kind = KernelEventKinds.BATTLE_SAMPLE_OBSERVED)
        assertEquals(2, events.size)
        val firstPayload = events[0].payload as BattleSamplePayload
        val secondPayload = events[1].payload as BattleSamplePayload
        assertFalse(firstPayload.damageObserved)
        assertEquals("wall_clock", firstPayload.timeSource)
        assertFalse(firstPayload.reviveTriggered)
        assertFalse(secondPayload.damageObserved)
        assertEquals("wall_clock", secondPayload.timeSource)
        assertTrue(secondPayload.reviveTriggered)

        // Strict 23-key contract on ShadowBattleSample itself remains intact.
        val sampleKeys = Json.parseToJsonElement(Json.encodeToString(slowWipe)).jsonObject.keys
        assertEquals(
            setOf(
                "sample_id", "round_id", "source", "db_version", "quest_id", "canonical_wave_sig", "bucket",
                "area", "difficulty", "guild_id", "team_unit_ids", "owned_unit_ids", "owned_unit_count",
                "owned_role_counts", "owned_talent_counts", "relic_ids", "mark_stacks", "alpha_rupee",
                "revive_available", "runs", "outcome_counts", "mean_damage_ratio", "mean_win_rem_time",
            ),
            sampleKeys,
        )
    }
}
