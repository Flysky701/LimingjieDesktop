package com.landosol.toolbox.labyrinth.kernel.strategy

import com.landosol.toolbox.labyrinth.*
import com.landosol.toolbox.labyrinth.kernel.*
import com.landosol.toolbox.labyrinth.node.LabyrinthNodeTypes
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

    @Test fun modelFeaturesUseCatalogIdsAndMarkOrder() {
        assertEquals(123401, labyrinthCharacterIdToUnitId("1234"))
        assertEquals(180701, labyrinthCharacterIdToUnitId("1183"))
        listOf("", "abc", "0", "-1").forEach { assertNull(labyrinthCharacterIdToUnitId(it)) }

        val roles = listOf("攻击者", "破防者", "增益者", "减益者", "增幅者", "治疗者", "掩护者", "妨碍者")
        assertEquals((1..8).toList(), roles.map(::labyrinthRoleClassToId))
        assertEquals(0, labyrinthRoleClassToId(null))
        assertEquals(0, labyrinthRoleClassToId("未知"))
        val attributes = listOf(
            LabyrinthCharacterAttribute.FIRE, LabyrinthCharacterAttribute.WATER, LabyrinthCharacterAttribute.WIND,
            LabyrinthCharacterAttribute.LIGHT, LabyrinthCharacterAttribute.DARK,
        )
        assertEquals((1..5).toList(), attributes.map(::labyrinthAttributeToTalentId))
        assertEquals(0, labyrinthAttributeToTalentId(null))
        assertEquals(0, labyrinthAttributeToTalentId(LabyrinthCharacterAttribute.UNKNOWN))

        val marks = mapOf(
            LabyrinthRelicMark.ACCELERATION to 11, LabyrinthRelicMark.CRITICAL to 13,
            LabyrinthRelicMark.DEFENSE to 15, LabyrinthRelicMark.ENHANCEMENT to 14, LabyrinthRelicMark.DEBUFF to 12,
        )
        assertEquals(listOf(11, 12, 13, 14, 15), labyrinthMarkStacksForModel(marks))
        assertEquals(listOf(0, 0, 0, 0, 15), labyrinthMarkStacksForModel(mapOf(LabyrinthRelicMark.DEFENSE to 15)))
    }

    @Test fun modelContextKeepsUniqueOwnedUnitsAndRejectsInvalidIdentities() {
        val profiles = mapOf(
            "1807" to LabyrinthRoleProfile("1183", "组合", roleClass = "攻击者", attribute = LabyrinthCharacterAttribute.FIRE),
            "1001" to LabyrinthRoleProfile("1001", "治疗", roleClass = "治疗者", attribute = LabyrinthCharacterAttribute.WATER),
        )
        fun build(ids: List<String>, roster: Map<String, LabyrinthRoleProfile> = profiles) = buildBattleValueContext(
            acquiredCharacterIds = ids, profiles = roster, questId = 42, area = 3, difficulty = 5,
            ruleContext = context.ruleContext, relicIds = listOf(50002),
            markStacksByMark = mapOf(LabyrinthRelicMark.DEBUFF to 4), alphaRupee = null, reviveAvailable = true,
        )

        val ids = listOf("1183", "1184", "1807", "1001", "9999")
        val valueContext = requireNotNull(build(ids))
        assertEquals(listOf(180701, 100101, 999901), valueContext.ownedUnits.map { it.unitId })
        assertEquals(listOf("1807", "1001", "9999"), valueContext.ownedUnits.map { it.profile.characterId })
        assertEquals(listOf(1, 0, 0, 0, 0, 1, 0, 0), valueContext.roleCounts())
        assertEquals(listOf(1, 1, 0, 0, 0), valueContext.talentCounts())
        assertEquals(listOf(0, 4, 0, 0, 0), valueContext.markStacks)
        assertEquals(listOf(50002), valueContext.relicIds)
        assertTrue(valueContext.reviveAvailable)
        assertNull(build(ids + "invalid"))
        assertNull(build(ids + "01001"))
        assertNull(build(ids + "50000000"))
        assertNull(build(ids, profiles + ("1001" to profiles.getValue("1807"))))
    }

    @Test fun modelAdoptsSafeAndMarginalTeamsAndExplainsUnsafeRuleFallback() {
        val roster = modelRoster(6)
        val ids = roster.map { it.characterId }
        val valueContext = modelContext(roster)
        val planner = planner(roster)
        val rule = (planner.initialRecommendation(ids, valueContext.ruleContext)
            as LabyrinthBattleTeamRecommendationResult.Ready).recommendation
        val modelIds = ids.filterNot { it == rule.members.first().characterId }
        val modelUnitIds = modelIds.map { requireNotNull(labyrinthCharacterIdToUnitId(it)) }.sorted()

        for ((probability, tier) in listOf(
            0.70 to BattleConfidenceTier.SAFE,
            0.50 to BattleConfidenceTier.MARGINAL,
            0.49 to BattleConfidenceTier.UNSAFE,
        )) {
            val estimator = ModelEstimator { if (it == modelUnitIds) probability else 0.10 }
            val result = planner.withBattleValueEstimator(estimator)
                .initialRecommendation(ids, valueContext.ruleContext, battleValueContext = valueContext)
                as LabyrinthBattleTeamRecommendationResult.Ready
            val recommendation = result.recommendation
            val assessment = requireNotNull(recommendation.modelAssessment)
            assertEquals(tier, assessment.tier)
            assertEquals(probability, assessment.pWin, 0.0)
            assertEquals(estimator.batches.sumOf { it.size }, assessment.evaluatedCandidates)
            if (tier == BattleConfidenceTier.UNSAFE) {
                val reason = "NN模型未找到稳过队（最高预测胜率 49.0% < 50%），已回退规则推荐"
                assertFalse(assessment.adopted)
                assertEquals(rule.copy(reasons = listOf(reason) + rule.reasons, modelAssessment = assessment), recommendation)
            } else {
                assertTrue(assessment.adopted)
                assertEquals(modelIds.toSet(), recommendation.members.map { it.characterId }.toSet())
                assertEquals(scorer.evaluate(roster.filter { it.characterId in modelIds }, valueContext.ruleContext).score,
                    recommendation.score, 1e-12)
            }
        }
    }

    @Test fun anyRuleBatchSilentlyDiscardsModelResults() {
        val roster = modelRoster(10)
        val ids = roster.map { it.characterId }
        val valueContext = modelContext(roster)
        val planner = planner(roster)
        val expected = planner.initialRecommendation(ids, valueContext.ruleContext)
        val estimator = ModelEstimator(sourceForBatch = { if (it == 1) BattleValueSource.MODEL else BattleValueSource.RULE }) { 0.99 }

        val actual = planner.withBattleValueEstimator(estimator)
            .initialRecommendation(ids, valueContext.ruleContext, battleValueContext = valueContext)

        assertEquals(2, estimator.batches.size)
        assertEquals(expected, actual)
    }

    @Test fun retryCandidatesExcludeFailedAndCommittedRolesAndCheckActualFront() {
        val roster = modelRoster(8).mapIndexed { index, profile ->
            when (index) {
                0 -> profile.copy(position = 100)
                1 -> profile.copy(position = 100, roleClass = "掩护者", functions = LabyrinthRoleFunctions(selfSustain = 80.0))
                else -> profile
            }
        }
        val eligible = roster.dropLast(1)
        val ruleContext = LabyrinthRoleDecisionContext(0, currentArea = 3, frontlineSurvivalGateEnabled = true)
        val valueContext = modelContext(roster, ruleContext)
        val failed = labyrinthBattleTeamSignature(eligible.drop(1).take(5).map { it.characterId })
        val byUnit = valueContext.ownedUnits.associateBy { it.unitId }
        val estimator = ModelEstimator { 0.75 }
        val result = planner(roster).withBattleValueEstimator(estimator).retryRecommendation(
            acquiredCharacterIds = eligible.map { it.characterId }, context = ruleContext,
            failedTeamSignatures = setOf(failed), battleValueContext = valueContext,
        ) as LabyrinthBattleTeamRecommendationResult.Ready

        assertEquals(8, valueContext.ownedUnits.size)
        val candidates = estimator.batches.flatten()
        assertTrue(candidates.isNotEmpty())
        for (candidate in candidates) {
            val characters = candidate.map { byUnit.getValue(it).profile.characterId }
            assertEquals(5, characters.size)
            assertTrue("1002" in characters)
            assertFalse("1001" in characters)
            assertFalse("1008" in characters)
            assertNotEquals(failed, labyrinthBattleTeamSignature(characters))
        }
        assertEquals("1002", result.recommendation.vanguard.characterId)
        assertEquals(true, result.recommendation.modelAssessment?.adopted)
    }

    @Test fun bossPackingMaximizesJointWinAcrossDisjointTeams() {
        val roster = modelRoster(15)
        val valueContext = modelContext(roster)
        val (first, second, third) = valueContext.ownedUnits.map { it.unitId }.chunked(5)
        val overlapping = listOf(first[0], first[1], second[0], second[1], third[0])
        val byCharacter = valueContext.ownedUnits.associate { it.profile.characterId to it.unitId }
        val ruleTeams = listOf(overlapping.map { unitId -> valueContext.ownedUnits.single { it.unitId == unitId }.profile.characterId })
        val estimator = ModelEstimator {
            when (it) {
                overlapping -> 0.55
                first, second -> 0.40
                third -> 0.25
                else -> 0.02
            }
        }
        val searcher = ModelGuidedBattleTeamSearcher(estimator, optimizer = null)

        for (teamCount in 2..3) {
            estimator.batches.clear()
            val result = requireNotNull(searcher.search(
                valueContext, roster, ruleTeams, emptySet(), teamCount, supplementLead = false,
            ))
            val packed = result.teams.map { team -> team.map { byCharacter.getValue(it.characterId) }.sorted() }
            assertEquals(listOf(first, second, third).take(teamCount).toSet(), packed.toSet())
            assertEquals(teamCount * 5, packed.flatten().distinct().size)
            assertEquals(if (teamCount == 2) 0.64 else 0.73, requireNotNull(result.assessment.jointWinProbability), 1e-12)
            assertEquals(if (teamCount == 2) BattleConfidenceTier.MARGINAL else BattleConfidenceTier.SAFE, result.assessment.tier)
            assertTrue(result.assessment.adopted)
            val candidates = estimator.batches.flatten()
            assertTrue(overlapping in candidates)
            assertEquals(candidates.distinct().size, result.assessment.evaluatedCandidates)
            assertTrue(candidates.size <= 256 && estimator.batches.all { it.size <= 64 })
        }
    }

    @Test fun activeBattleQuestUsesCurrentNodeOrPrecedingBossAfterAreaAdvance() {
        val normal = LabyrinthNodeJson(2, 1, 1, 10, LabyrinthNodeTypes.NORMAL_BATTLE, 20, listOf(11), false)
        val ex = LabyrinthNodeJson(2, 2, 1, 11, LabyrinthNodeTypes.EX_BATTLE, 21, listOf(12), false)
        val boss = LabyrinthNodeJson(2, 3, 1, 12, LabyrinthNodeTypes.BOSS, 22, listOf(13), true)
        val start = LabyrinthNodeJson(3, 0, 1, 13, LabyrinthNodeTypes.START, null, emptyList(), false)
        val route = LabyrinthRouteJson(1L, 1, 5, 1, false, false, "", emptyList(), emptyList(),
            listOf(normal, ex, boss, start), currentBlockId = start.blockId)

        assertEquals(22, labyrinthActiveBattleQuestId(LabyrinthCombatKind.BOSS, route))
        assertEquals(22, labyrinthActiveBattleQuestId(LabyrinthCombatKind.BOSS, route.copy(currentBlockId = boss.blockId)))
        assertEquals(21, labyrinthActiveBattleQuestId(LabyrinthCombatKind.EX, route.copy(currentBlockId = ex.blockId)))
        assertEquals(20, labyrinthActiveBattleQuestId(LabyrinthCombatKind.NORMAL, route.copy(currentBlockId = normal.blockId)))
        assertNull(labyrinthActiveBattleQuestId(LabyrinthCombatKind.EX, route))
        assertNull(labyrinthActiveBattleQuestId(LabyrinthCombatKind.BOSS, null))
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

    private fun modelRoster(size: Int): List<LabyrinthRoleProfile> = (1..size).map { index ->
        val id = (1000 + index).toString()
        LabyrinthRoleProfile(id, id, position = index * 100, userScore = index * 5.0, physicalDamagePotential = index * 5.0)
    }

    private fun modelContext(
        roster: List<LabyrinthRoleProfile>,
        ruleContext: LabyrinthRoleDecisionContext = LabyrinthRoleDecisionContext(0, currentArea = 3),
    ): BattleValueContext = requireNotNull(buildBattleValueContext(
        roster.map { it.characterId }, roster.associateBy { it.characterId }, 42, 3, 5, ruleContext,
        emptyList(), emptyMap(), null, false,
    ))

    private fun planner(roster: List<LabyrinthRoleProfile>) = LabyrinthBattleTeamRecommendationPlanner(
        profiles = roster.associateBy { it.characterId },
        teamPlanSearcher = LabyrinthTeamPlanSearcher(
            LabyrinthTeamOptimizer(scorer, searchThreads = 1),
            LabyrinthTeamPlanSearchConfig(70.0, 60.0, 40.0, 100.0, 50.0, 30.0, 110.0),
        ),
        scorer = scorer,
    )

    private class ModelEstimator(
        private val sourceForBatch: (Int) -> BattleValueSource = { BattleValueSource.MODEL },
        private val probability: (List<Int>) -> Double,
    ) : ContextualBattleValueEstimator {
        val batches = mutableListOf<List<List<Int>>>()

        override fun unitAffinities(context: BattleValueContext): Map<Int, Double> =
            context.ownedUnits.associate { it.unitId to -it.unitId.toDouble() }

        override fun estimateWithSource(context: BattleValueContext, candidates: List<List<Int>>): BattleValueEstimate {
            batches.add(candidates)
            val values = candidates.map { team ->
                val win = probability(team)
                BattleValue(win, 0.0, 1.0 - win, win, 0.0, win)
            }
            return BattleValueEstimate(values, sourceForBatch(batches.size))
        }
    }
}
