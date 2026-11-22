package com.landosol.toolbox.labyrinth.kernel.strategy

import com.landosol.toolbox.labyrinth.*
import com.landosol.toolbox.labyrinth.kernel.*
import java.util.UUID
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BattleValueStrategyTest {
    @get:Rule val temp = TemporaryFolder()
    private val scorer = LabyrinthTeamScorer(LabyrinthTeamScoringConfig(mapOf(1 to 0.0, 2 to 0.08, 3 to 0.16, 4 to 0.25, 5 to 0.75)))
    private val units = (1..6).map { n ->
        BattleRosterUnit(n, LabyrinthRoleProfile(n.toString(), "viewer_id=private;token=canary;UID=private",
            userScore = n * 15.0, physicalDamagePotential = n * 10.0), roleId = n, talentId = 1)
    }
    private val context = BattleValueContext(42, 3, 5, units, LabyrinthRoleDecisionContext(4),
        relicIds = listOf(12), markStacks = listOf(4), reviveAvailable = true)
    private val teams = listOf((1..5).toList(), (2..6).toList())

    @Test fun estimatesFollowExistingScoreAndBatchOrder() {
        val estimator = RuleBattleValueEstimator(scorer)
        val values = estimator.estimate(context, teams)
        assertEquals(teams.size, values.size)
        assertEquals(values, estimator.estimate(context, teams))
        assertEquals(values.reversed(), estimator.estimate(context, teams.reversed()))
        assertTrue(values[1].pWin > values[0].pWin)
        for ((ids, v) in teams.zip(values)) {
            val score = scorer.evaluate(ids.map { units[it - 1].profile }, context.ruleContext).score
            assertEquals(score / (score + 50.0), v.pWin, 0.0)
            assertTrue(listOf(v.pWin, v.pTimeout, v.pWipe, v.damageMean, v.winRemTime).all { it in 0.0..1.0 })
            assertTrue(v.damageLogStd.isFinite())
            assertEquals(1.0, v.pWin + v.pTimeout + v.pWipe, 1e-12)
        }
    }

    @Test fun journalContainsOnlyPythonSampleFieldsAndObservedLabels() {
        val runtime = KernelRuntime()
        val round = UUID.randomUUID()
        KernelJournalSink(temp.root).use { sink ->
            runtime.addSink(sink)
            // Defeat within two frames of the limit has Python's timeout label.
            for ((result, frames) in listOf(0 to 1800, 1 to 5398, 2 to 5000, 1 to 1800)) {
                runtime.recordBattleSample(shadowBattleSample(context, teams.first(),
                    ObservedBattleResult(result, frames, 90, 0.75, 1500, listOf(500)),
                    UUID.randomUUID(), round, "a".repeat(64), "robot_ex"))
            }
        }
        val dir = KernelJournalSink.sessionDirFor(temp.root, runtime.traceId)
        assertEquals(4, KernelJournalReader.verifyAndLoad(dir).events.size)
        val lines = dir.resolve("events.ndjson").readLines()
        assertFalse(lines.any { "private" in it || "canary" in it || "viewer_id" in it || "UID" in it || "token" in it })
        val fields = "sample_id round_id source db_version quest_id canonical_wave_sig bucket area difficulty guild_id " +
            "team_unit_ids owned_unit_ids owned_unit_count owned_role_counts owned_talent_counts relic_ids mark_stacks " +
            "alpha_rupee revive_available runs outcome_counts mean_damage_ratio mean_win_rem_time"
        for ((i, line) in lines.withIndex()) {
            val event = Json.parseToJsonElement(line).jsonObject
            assertEquals(KernelEventKinds.BATTLE_SAMPLE_OBSERVED, event.getValue("kind").jsonPrimitive.content)
            val sample = event.getValue("payload").jsonObject.getValue("sample").jsonObject
            assertEquals(fields.split(' ').toSet(), sample.keys)
            val outcome = listOf(0, 1, 1, 2)[i]
            assertEquals(List(3) { if (it == outcome) 1 else 0 }, sample.getValue("outcome_counts").jsonArray.map { it.jsonPrimitive.int })
            assertEquals(JsonNull, sample["alpha_rupee"])
            assertEquals(teams.first(), sample.getValue("team_unit_ids").jsonArray.map { it.jsonPrimitive.int })
            if (outcome == 0) assertEquals(2.0 / 3, sample.getValue("mean_win_rem_time").jsonPrimitive.double, 1e-12)
            else assertEquals(JsonNull, sample["mean_win_rem_time"])
            val run = sample.getValue("runs").jsonArray.single().jsonObject
            assertEquals("seed result outcome_class battle_frames limit_time elapsed_sec rem_time_ratio total_damage damage_ratio enemy_final_hps"
                .split(' ').toSet(), run.keys)
            assertEquals(outcome, run.getValue("outcome_class").jsonPrimitive.int)
            assertEquals(0.75, run.getValue("damage_ratio").jsonPrimitive.double, 0.0)
        }
    }
}
