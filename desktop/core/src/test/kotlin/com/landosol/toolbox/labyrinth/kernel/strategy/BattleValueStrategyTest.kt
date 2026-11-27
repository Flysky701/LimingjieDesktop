package com.landosol.toolbox.labyrinth.kernel.strategy

import com.landosol.toolbox.labyrinth.*
import com.landosol.toolbox.labyrinth.concurrent.LabyrinthFourThreadRuntime
import com.landosol.toolbox.labyrinth.kernel.*
import com.landosol.toolbox.labyrinth.kernel.planning.BattleExam
import com.landosol.toolbox.labyrinth.kernel.planning.PlanningState
import com.landosol.toolbox.labyrinth.node.LabyrinthNodeTypes
import java.util.UUID
import kotlinx.coroutines.runBlocking
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
        val ruleUnitIds = rule.members.map { requireNotNull(labyrinthCharacterIdToUnitId(it.characterId)) }.sorted()

        for ((probability, tier) in listOf(
            0.70 to BattleConfidenceTier.SAFE,
            0.50 to BattleConfidenceTier.MARGINAL,
            0.49 to BattleConfidenceTier.UNSAFE,
        )) {
            val estimator = ModelEstimator {
                when (it) {
                    modelUnitIds -> probability
                    ruleUnitIds -> 0.40
                    else -> 0.10
                }
            }
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
                assertEquals(rule.copy(
                    reasons = listOf(reason) + rule.reasons, modelAssessment = assessment,
                    modelTrace = BattleModelTrace(BattleModelDecisionStatus.LOW_CONFIDENCE_FALLBACK, 42, reason),
                ), recommendation)
            } else {
                val summary = "NN胜率 ${(probability * 100).toInt()}.0% · ${tier.label}"
                assertTrue(assessment.adopted)
                assertEquals(modelIds.toSet(), recommendation.members.map { it.characterId }.toSet())
                assertEquals("按NN模型选队（$summary）", recommendation.reasons.first())
                assertEquals(BattleModelTrace(BattleModelDecisionStatus.ADOPTED, 42, summary), recommendation.modelTrace)
                assertEquals(scorer.evaluate(roster.filter { it.characterId in modelIds }, valueContext.ruleContext).score,
                    recommendation.score, 1e-12)
            }
        }
    }

    @Test fun modelStyleTradesSmallHighWinMarginsForSpeedAndBoostsOnlyNonBossRevives() {
        val roster = modelRoster(6)
        val baseContext = modelContext(roster)
        val unitIds = baseContext.ownedUnits.map { it.unitId }
        val slow = unitIds.take(5)
        val fast = unitIds.takeLast(5)
        val slowIds = roster.take(5).map { it.characterId }
        val fastIds = roster.takeLast(5).map { it.characterId }

        fun pick(
            style: LabyrinthBattleModelStyle, slowWin: Double, fastWin: Double,
            revive: Boolean = false, boss: Boolean = false,
        ): List<String> {
            val estimator = ModelEstimator(winRemTime = { if (it == fast) 0.90 else 0.10 }) {
                when (it) {
                    slow -> slowWin
                    fast -> fastWin
                    else -> 0.01
                }
            }
            val result = requireNotNull(ModelGuidedBattleTeamSearcher(estimator, optimizer = null).search(
                baseContext.copy(strategyStyle = style, reviveAvailable = revive, isBossBattle = boss),
                roster, emptyList(), emptySet(), 1, supplementLead = false,
            ))
            return result.assessment.plannedTeams.single()
        }

        assertEquals(slowIds, pick(LabyrinthBattleModelStyle.CONSERVATIVE, 0.95, 0.91))
        assertEquals(fastIds, pick(LabyrinthBattleModelStyle.BALANCED, 0.95, 0.91))
        assertEquals(fastIds, pick(LabyrinthBattleModelStyle.AGGRESSIVE, 0.95, 0.91))
        assertEquals(slowIds, pick(LabyrinthBattleModelStyle.AGGRESSIVE, 0.45, 0.40))
        assertEquals(slowIds, pick(LabyrinthBattleModelStyle.BALANCED, 0.95, 0.89))
        assertEquals(fastIds, pick(LabyrinthBattleModelStyle.BALANCED, 0.95, 0.89, revive = true))
        assertEquals(slowIds, pick(LabyrinthBattleModelStyle.BALANCED, 0.95, 0.89, revive = true, boss = true))
    }

    @Test fun lowSingleBossWinAutomaticallyPlansThreeTeams() {
        val roster = modelRoster(15)
        val valueContext = modelContext(roster).copy(isBossBattle = true)
        val chunks = valueContext.ownedUnits.map { it.unitId }.chunked(5)
        val estimator = ModelEstimator { if (it in chunks) 0.65 else 0.02 }
        val result = labyrinthBalancedBattleRecommendation(
            planner = planner(roster).withBattleValueEstimator(estimator),
            unavailableReason = null, eligibleIds = roster.map { it.characterId }, context = valueContext.ruleContext,
            failedSignatures = emptySet(), lastFailedSignature = null, retryNumber = 0,
            requestedBossTeamCount = 1, allowSupplementLead = false, teamIndex = 1,
            battleValueContext = valueContext, autoDowngradeBossMaxTeams = 3,
        ).result as LabyrinthBattleTeamRecommendationResult.Ready

        val recommendation = result.recommendation
        val assessment = requireNotNull(recommendation.modelAssessment)
        assertTrue(assessment.autoDowngradedToMultiTeam)
        assertTrue(assessment.adopted)
        assertEquals(3, recommendation.plannedBossTeamCount)
        assertEquals(chunks.toSet(), assessment.plannedTeams.map { team ->
            team.map { requireNotNull(labyrinthCharacterIdToUnitId(it)) }.sorted()
        }.toSet())
        assertTrue(recommendation.reasons.first().startsWith("按NN模型多队编组"))
        assertTrue("单队NN胜率不足（65.0%），已自动降级为3队编组" in recommendation.reasons)
    }

    @Test fun modelAdoptsUnsafeTeamWhenItClearlyBeatsEvaluatedRuleTeam() {
        val roster = modelRoster(6)
        val ids = roster.map { it.characterId }
        val valueContext = modelContext(roster)
        val planner = planner(roster)
        val rule = (planner.initialRecommendation(ids, valueContext.ruleContext)
            as LabyrinthBattleTeamRecommendationResult.Ready).recommendation
        val modelIds = ids.filterNot { it == rule.members.first().characterId }
        val modelUnitIds = modelIds.map { requireNotNull(labyrinthCharacterIdToUnitId(it)) }.sorted()
        val estimator = ModelEstimator { if (it == modelUnitIds) 0.30 else 0.01 }

        val result = planner.withBattleValueEstimator(estimator)
            .initialRecommendation(ids, valueContext.ruleContext, battleValueContext = valueContext)
            as LabyrinthBattleTeamRecommendationResult.Ready
        val recommendation = result.recommendation
        val assessment = requireNotNull(recommendation.modelAssessment)
        val note = "NN低胜率优选（30.0% 优于规则队 1.0%）"
        assertEquals(BattleConfidenceTier.UNSAFE, assessment.tier)
        assertTrue(assessment.adopted)
        assertEquals(modelIds.toSet(), recommendation.members.map { it.characterId }.toSet())
        assertEquals(note, assessment.adoptionNote)
        assertTrue(note in recommendation.reasons)
        assertEquals(BattleModelTrace(BattleModelDecisionStatus.ADOPTED, 42, "NN胜率 30.0% · 无稳过队 · $note"),
            recommendation.modelTrace)
    }

    @Test fun ruleBatchDiscardsModelResultsAndPreservesFailureReason() {
        val roster = modelRoster(10)
        val ids = roster.map { it.characterId }
        val valueContext = modelContext(roster)
        val planner = planner(roster)
        val expected = (planner.initialRecommendation(ids, valueContext.ruleContext)
            as LabyrinthBattleTeamRecommendationResult.Ready).recommendation
        val reason = "模型推理无法读取关卡"
        val estimator = ModelEstimator(
            sourceForBatch = { if (it == 1) BattleValueSource.MODEL else BattleValueSource.RULE },
            fallbackReason = reason,
        ) { 0.99 }

        val actual = planner.withBattleValueEstimator(estimator)
            .initialRecommendation(ids, valueContext.ruleContext, battleValueContext = valueContext)
            as LabyrinthBattleTeamRecommendationResult.Ready

        assertEquals(2, estimator.batches.size)
        assertEquals(expected.copy(
            modelTrace = BattleModelTrace(BattleModelDecisionStatus.LOAD_OR_INFERENCE_FAILED, 42, reason),
        ), actual.recommendation)
        assertTrue(actual.recommendation.logText().endsWith(" | 决策来源=模型异常回退 | questId=42 | 模型说明=$reason"))
    }

    @Test fun retryCandidatesExcludeFailedAndCommittedRolesAndCheckActualFront() {
        val roster = modelRoster(9).mapIndexed { index, profile ->
            when (index) {
                0 -> profile.copy(roleClass = "掩护者", functions = LabyrinthRoleFunctions(selfSustain = 80.0))
                1 -> profile.copy(position = 100)
                2 -> profile.copy(position = null)
                else -> profile
            }
        }
        val eligible = roster.dropLast(1)
        val ruleContext = LabyrinthRoleDecisionContext(0, currentArea = 3, frontlineSurvivalGateEnabled = true)
        val valueContext = modelContext(roster, ruleContext)
        val legal = eligible.filter { it.characterId !in setOf("1002", "1003") }
        val failed = labyrinthBattleTeamSignature(legal.take(5).map { it.characterId })
        val byUnit = valueContext.ownedUnits.associateBy { it.unitId }
        val estimator = ModelEstimator { 0.75 }
        val result = planner(roster).withBattleValueEstimator(estimator).retryRecommendation(
            acquiredCharacterIds = eligible.map { it.characterId }, context = ruleContext,
            failedTeamSignatures = setOf(failed), battleValueContext = valueContext,
        ) as LabyrinthBattleTeamRecommendationResult.Ready

        assertEquals(9, valueContext.ownedUnits.size)
        val candidates = estimator.batches.flatten()
        assertTrue(candidates.isNotEmpty())
        for (candidate in candidates) {
            val characters = candidate.map { byUnit.getValue(it).profile.characterId }
            assertEquals(5, characters.size)
            assertTrue("1001" in characters)
            assertFalse("1002" in characters)
            assertFalse("1003" in characters)
            assertFalse("1009" in characters)
            assertNotEquals(failed, labyrinthBattleTeamSignature(characters))
        }
        assertEquals("1001", result.recommendation.vanguard.characterId)
        assertEquals(true, result.recommendation.modelAssessment?.adopted)
    }

    @Test fun modelUsesConfiguredVanguardGateAndReportsWhenNoCandidatesPass() {
        val roster = modelRoster(6).let {
            listOf(it.first().copy(roleClass = "掩护者", functions = LabyrinthRoleFunctions(selfSustain = 80.0))) + it.drop(1)
        }
        val context = modelContext(roster, LabyrinthRoleDecisionContext(0, currentArea = 3, frontlineSurvivalGateEnabled = true))
        val strictScorer = LabyrinthTeamScorer(scorer.config.copy(minimumReliableVanguard = 70.0))
        val estimator = ModelEstimator { 0.95 }
        val searcher = ModelGuidedBattleTeamSearcher(estimator, LabyrinthTeamOptimizer(strictScorer, searchThreads = 1))
        assertTrue(roster.first().isEligibleBattleVanguard(context.ruleContext))
        assertEquals(
            ModelGuidedBattleTeamSearcher.Outcome.Fallback(
                BattleModelDecisionStatus.NO_ELIGIBLE_CANDIDATE, "无满足前排与排除约束的合法候选队",
            ),
            searcher.searchOutcome(
                context, roster, listOf(roster.take(5).map { it.characterId }), emptySet(), 1, supplementLead = false,
            ),
        )
        assertTrue(estimator.batches.isEmpty())
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
            assertEquals(result.teams.map { team -> team.map { it.characterId } }, result.assessment.plannedTeams)
            assertEquals(listOf(0.40, 0.40, 0.25).take(teamCount), result.assessment.teamWinProbabilities)
            assertEquals(if (teamCount == 2) 0.64 else 0.73, requireNotNull(result.assessment.jointWinProbability), 1e-12)
            assertEquals(if (teamCount == 2) BattleConfidenceTier.MARGINAL else BattleConfidenceTier.SAFE, result.assessment.tier)
            assertTrue(result.assessment.adopted)
            val candidates = estimator.batches.flatten()
            assertTrue(overlapping in candidates)
            assertEquals(candidates.distinct().size, result.assessment.evaluatedCandidates)
            assertTrue(candidates.size <= 384 && estimator.batches.all { it.size <= 64 })
        }
    }

    @Test fun bossPackingFindsCumulativeDamageTeamsOutsideTheTopWinCandidates() {
        val roster = modelRoster(15)
        val valueContext = modelContext(roster)
        val (first, second, third) = valueContext.ownedUnits.map { it.unitId }.chunked(5)
        val wins = mapOf(first to 0.10, second to 0.12, third to 0.08)
        val damage = mapOf(first to 0.55, second to 0.50, third to 0.45)
        val values = wins.map { (ids, win) -> BattleValue(win, 0.0, 1.0 - win, damage.getValue(ids), 0.0, win) }
        val independent = 1.0 - wins.values.fold(1.0) { miss, win -> miss * (1.0 - win) }
        val combined = combinedMultiTeamPassProb(values)
        assertEquals(0.7181486, combined, 1e-6)
        assertTrue(combined > independent + 0.40)

        val estimator = ModelEstimator(damageMean = { damage[it] ?: 0.02 }) { wins[it] ?: 0.25 }
        val result = requireNotNull(ModelGuidedBattleTeamSearcher(estimator, optimizer = null).search(
            valueContext, roster, emptyList(), emptySet(), 3, supplementLead = false,
        ))
        val packed = result.teams.map { team ->
            team.map { requireNotNull(labyrinthCharacterIdToUnitId(it.characterId)) }.sorted()
        }
        assertTrue(estimator.batches.flatten().count { it !in wins } > 48)
        assertEquals(listOf(second, first, third), packed)
        assertEquals(combined, requireNotNull(result.assessment.jointWinProbability), 1e-12)
        assertTrue(result.assessment.adopted)
    }

    @Test fun bossRetainsSupplementAndReusesItsOwnWinProbabilityOnTheNextPage() {
        val roster = modelRoster(10).let {
            listOf(it.first().copy(roleClass = "掩护者", functions = LabyrinthRoleFunctions(selfSustain = 80.0))) + it.drop(1)
        }
        val context = modelContext(roster, LabyrinthRoleDecisionContext(
            0, currentArea = 3, frontlineSurvivalGateEnabled = true, optimizeBossVanguardSynergy = true,
        ))
        val ids = roster.map { it.characterId }
        val tankUnit = context.ownedUnits.first().unitId
        val probability: (List<Int>) -> Double = { if (tankUnit in it) 0.60 else 0.75 }
        val estimator = ModelEstimator(probability = probability)
        val planner = planner(roster).withBattleValueEstimator(estimator)
        val first = (planner.initialRecommendation(ids, context.ruleContext, requestedBossTeamCount = 2, battleValueContext = context)
            as LabyrinthBattleTeamRecommendationResult.Ready).recommendation
        val assessment = requireNotNull(first.modelAssessment)
        val plannedTeams = assessment.plannedTeams
        assertEquals(2, first.plannedBossTeamCount)
        assertEquals(1, first.safeBossTeamCount)
        assertEquals(2, plannedTeams.size)
        assertEquals(ids.toSet(), plannedTeams.flatten().toSet())
        assertEquals(first.members.map { it.characterId }, plannedTeams.first())
        assertEquals(ids.first(), plannedTeams.first().first())
        assertEquals(listOf(0.60, 0.75), assessment.teamWinProbabilities)
        assertEquals(0.90, requireNotNull(assessment.jointWinProbability), 1e-12)
        assertEquals("按NN模型多队编组（本队NN胜率 60.0% · 2队估算联合胜率 90.0% · 稳妥推荐）", first.reasons.first())

        val supplementIds = plannedTeams[1]
        val pageContext = context.ruleContext.copy(optimizeBossVanguardSynergy = false)
        val batchCount = estimator.batches.size
        fun reuse(eligible: List<String>, failed: Set<String> = emptySet()) =
            planner.plannedBossRecommendation(plannedTeams, assessment, 2, context.questId, eligible, failed, pageContext)
        val second = requireNotNull(reuse(supplementIds)).recommendation
        assertEquals(supplementIds, second.members.map { it.characterId })
        assertEquals(assessment.copy(pWin = 0.75), second.modelAssessment)
        assertEquals(0, second.safeBossTeamCount)
        assertEquals(scorer.evaluate(supplementIds.map(roster.associateBy { it.characterId }::getValue), pageContext).score,
            second.score, 1e-12)
        assertEquals(BattleModelTrace(BattleModelDecisionStatus.ADOPTED, 42, "沿用多队规划第2队"), second.modelTrace)
        assertNull(reuse(supplementIds.drop(1)))
        assertNull(reuse(supplementIds, setOf(labyrinthBattleTeamSignature(supplementIds))))
        assertEquals(batchCount, estimator.batches.size)

        val supplementBatch = estimator.batches.indexOfFirst { tankUnit !in it.first() } + 1
        assertTrue(supplementBatch > 1)
        val failing = ModelEstimator(
            sourceForBatch = { if (it < supplementBatch) BattleValueSource.MODEL else BattleValueSource.RULE },
            fallbackReason = "补刀队推理失败",
            probability = probability,
        )
        val fallback = (planner.withBattleValueEstimator(failing)
            .initialRecommendation(ids, context.ruleContext, requestedBossTeamCount = 2, battleValueContext = context)
            as LabyrinthBattleTeamRecommendationResult.Ready).recommendation
        assertEquals(supplementBatch, failing.batches.size)
        assertNull(fallback.modelAssessment)
        assertEquals(BattleModelTrace(BattleModelDecisionStatus.LOAD_OR_INFERENCE_FAILED, 42, "补刀队推理失败"),
            fallback.modelTrace)
    }

    @Test fun activeBattleQuestUsesCurrentNodeOrPrecedingBossAfterAreaAdvance() {
        val normal = LabyrinthNodeJson(2, 1, 1, 10, LabyrinthNodeTypes.NORMAL_BATTLE, 20, listOf(11), false)
        val ex = LabyrinthNodeJson(2, 2, 1, 11, LabyrinthNodeTypes.EX_BATTLE, 21, listOf(12), false)
        val boss = LabyrinthNodeJson(2, 3, 1, 12, LabyrinthNodeTypes.BOSS, 22, listOf(13), true)
        val start = LabyrinthNodeJson(3, 0, 1, 13, LabyrinthNodeTypes.START, null, emptyList(), false)
        val route = LabyrinthRouteJson(1L, 1, 5, 1, false, false, "", emptyList(), emptyList(),
            listOf(normal, ex, boss, start), currentBlockId = start.blockId)

        assertEquals(22, labyrinthActiveBattleQuestId(LabyrinthCombatKind.BOSS, route))
        assertEquals(2, labyrinthBattleTeamArea(null, LabyrinthCombatKind.BOSS, route, progressArea = 3))
        assertEquals(22, labyrinthActiveBattleQuestId(LabyrinthCombatKind.BOSS, route.copy(currentBlockId = boss.blockId)))
        assertEquals(21, labyrinthActiveBattleQuestId(LabyrinthCombatKind.EX, route.copy(currentBlockId = ex.blockId)))
        assertEquals(20, labyrinthActiveBattleQuestId(LabyrinthCombatKind.NORMAL, route.copy(currentBlockId = normal.blockId)))
        assertNull(labyrinthActiveBattleQuestId(LabyrinthCombatKind.EX, route))
        assertNull(labyrinthActiveBattleQuestId(LabyrinthCombatKind.BOSS, null))
    }

    @Test fun exCatalogMapsAll64QuestsByStableStrategyIdAndArea() {
        val expectedIds = listOf(
            "ghost_lord", "good_friend_x", "island_whale", "antimatter_beast", "giant_tuna", "lava_dragon",
            "multi_explosion_relic", "multi_destruction_relic", "multi_kurumi_shadow", "multi_ny_karyl_shadow",
            "multi_mifuyu_work_shadow", "multi_ruka_shadow", "queen_bee", "misora",
        )
        val strategies = LabyrinthExEncounterCatalog.all.associateBy { it.id }
        assertEquals(expectedIds.toSet(), strategies.keys)
        val matched = mutableSetOf<Int>()
        for (area in 0..6) {
            for ((index, id) in expectedIds.withIndex()) {
                val questId = LabyrinthExEncounterCatalog.questIdFor(area, strategies.getValue(id))
                if (area !in 1..5 || (index >= 12 && area < 4)) {
                    assertNull("$area/$id", questId)
                } else {
                    val expected = "770${area}2${(index + 1).toString().padStart(2, '0')}01".toInt()
                    assertEquals("$area/$id", expected, questId)
                    assertTrue(matched.add(requireNotNull(questId)))
                }
            }
        }
        assertEquals(64, matched.size)
        assertNull(LabyrinthExEncounterCatalog.questIdFor(null, strategies.getValue("ghost_lord")))
        assertNull(LabyrinthExEncounterCatalog.questIdFor(5, null))
        assertNull(LabyrinthExEncounterCatalog.questIdFor(5, strategies.getValue("ghost_lord").copy(id = "unknown")))
    }

    @Test fun exNodeWithoutPersistedQuestUsesRecognizedEncounterForModelRecommendation() {
        val ex = LabyrinthNodeJson(4, 2, 1, 40201, blockType = 3, questId = null, listOf(40301), false)
        val route = LabyrinthRouteJson(1L, 1, 5, 1, false, false, "", emptyList(), emptyList(),
            listOf(ex), currentBlockId = ex.blockId)
        val strategy = LabyrinthExEncounterCatalog.all.single { it.id == "multi_destruction_relic" }
        val area = labyrinthBattleTeamArea(null, LabyrinthCombatKind.EX, route, progressArea = 5)
        val questId = labyrinthActiveBattleQuestId(LabyrinthCombatKind.EX, route, exEncounterStrategy = strategy)
        assertEquals(770420801, questId)
        assertEquals(770520801, labyrinthActiveBattleQuestId(LabyrinthCombatKind.EX, route, 5, strategy))
        assertEquals(99, labyrinthActiveBattleQuestId(
            LabyrinthCombatKind.EX, route.copy(nodes = listOf(ex.copy(questId = 99))), 5, strategy,
        ))
        assertNull(labyrinthActiveBattleQuestId(LabyrinthCombatKind.EX, route, area))
        assertNull(labyrinthActiveBattleQuestId(LabyrinthCombatKind.NORMAL, route, area, strategy))

        val estimator = ModelEstimator { 0.80 }
        val inputs = BattleModelInputs(questId, route.difficulty, emptyList(), emptyMap(), false, estimator, strategy.id)
        val roster = modelRoster(6)
        val ids = roster.map { it.characterId }
        val profiles = roster.associateBy { it.characterId }
        val context = LabyrinthRoleDecisionContext(0, currentArea = area, encounterStrategy = strategy)
        val valueContext = requireNotNull(inputs.valueContext(ids, profiles, context))
        assertEquals(4, valueContext.area)
        assertEquals(5, valueContext.difficulty)
        assertNull(inputs.skipTrace(LabyrinthCombatKind.EX, area, ids, profiles, context))
        val result = labyrinthBalancedBattleRecommendation(
            planner(roster).withBattleValueEstimator(estimator), null, ids, context, emptySet(), null, 0, 1, false, 1,
            battleValueContext = valueContext,
        ).result as LabyrinthBattleTeamRecommendationResult.Ready
        assertEquals(setOf(770420801), estimator.questIds.toSet())
        assertEquals(BattleModelTrace(BattleModelDecisionStatus.ADOPTED, 770420801, "NN胜率 80.0% · 稳妥推荐"),
            result.recommendation.modelTrace)
    }

    @Test fun missingModelInputsExplainWhyNormalBossAndExUseRules() {
        val estimator = ModelEstimator { 0.80 }
        val inputs = BattleModelInputs(null, 5, emptyList(), emptyMap(), false, estimator)
        val roster = modelRoster(6)
        val ids = roster.map { it.characterId }
        val profiles = roster.associateBy { it.characterId }
        val context = LabyrinthRoleDecisionContext(0, currentArea = 3)
        val planner = planner(roster).withBattleValueEstimator(estimator)
        val normalTrace = BattleModelTrace(BattleModelDecisionStatus.NO_QUEST_ID, detail = "普通战节点缺少 questId")
        fun trace(kind: LabyrinthCombatKind, modelInputs: BattleModelInputs = inputs) =
            modelInputs.skipTrace(kind, 3, ids, profiles, context)
        assertNull(inputs.valueContext(ids, profiles, context))
        assertEquals(normalTrace, trace(LabyrinthCombatKind.NORMAL))
        assertEquals(BattleModelTrace(BattleModelDecisionStatus.NO_QUEST_ID, detail = "Boss 节点缺少 questId"),
            trace(LabyrinthCombatKind.BOSS))
        assertEquals(BattleModelTrace(BattleModelDecisionStatus.NO_QUEST_ID, detail = "EX 遭遇未识别，无法确定 questId"),
            trace(LabyrinthCombatKind.EX))
        assertEquals(BattleModelTrace(BattleModelDecisionStatus.NO_QUEST_ID, detail = "EX 遭遇 queen_bee 在第3层没有 questId 映射"),
            trace(LabyrinthCombatKind.EX, inputs.copy(exEncounterId = "queen_bee")))
        assertEquals(BattleModelTrace(BattleModelDecisionStatus.MODEL_DISABLED, 42),
            trace(LabyrinthCombatKind.NORMAL, inputs.copy(questId = 42, estimator = RuleBattleValueEstimator(scorer))))
        assertEquals(BattleModelTrace(BattleModelDecisionStatus.CONTEXT_UNAVAILABLE, 42, "缺少迷宫难度"),
            trace(LabyrinthCombatKind.NORMAL, inputs.copy(questId = 42, difficulty = null)))
        for (retry in 0..1) {
            val result = labyrinthBalancedBattleRecommendation(
                planner, null, ids, context, emptySet(), null, retry, 1, false, 1, modelFallbackTrace = normalTrace,
            ).result as LabyrinthBattleTeamRecommendationResult.Ready
            assertEquals(normalTrace, result.recommendation.modelTrace)
        }

        val healer = roster.last().copy(roleClass = "治疗者")
        val exRoster = roster.dropLast(1) + healer
        val exStrategy = LabyrinthExEncounterCatalog.all.first { it.id == "island_whale" }
        val failed = labyrinthBattleTeamSignature(ids.dropLast(1))
        val exTrace = trace(LabyrinthCombatKind.EX, inputs.copy(exEncounterId = exStrategy.id))
        val exResult = labyrinthBalancedBattleRecommendation(
            planner(exRoster).withBattleValueEstimator(estimator), null, ids, context.copy(encounterStrategy = exStrategy),
            setOf(failed), failed, 2, 1, false, 1, modelFallbackTrace = exTrace,
        ).result as LabyrinthBattleTeamRecommendationResult.Ready
        assertTrue(exResult.recommendation.reasons.any { "EX第二次失败生存兜底" in it })
        assertEquals(requireNotNull(exTrace), exResult.recommendation.modelTrace)
        assertTrue(estimator.batches.isEmpty())
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
        private val fallbackReason: String? = null,
        private val damageMean: ((List<Int>) -> Double)? = null,
        private val winRemTime: ((List<Int>) -> Double)? = null,
        private val probability: (List<Int>) -> Double,
    ) : ContextualBattleValueEstimator {
        val batches = mutableListOf<List<List<Int>>>()
        val questIds = mutableListOf<Int>()

        override fun unitAffinities(context: BattleValueContext): Map<Int, Double> =
            context.ownedUnits.associate { it.unitId to -it.unitId.toDouble() }

        override fun estimateWithSource(context: BattleValueContext, candidates: List<List<Int>>): BattleValueEstimate {
            batches.add(candidates)
            questIds.add(context.questId)
            val values = candidates.map { team ->
                val win = probability(team)
                BattleValue(win, 0.0, 1.0 - win, damageMean?.invoke(team) ?: win, 0.0, winRemTime?.invoke(team) ?: win)
            }
            val source = sourceForBatch(batches.size)
            return BattleValueEstimate(values, source, fallbackReason.takeIf { source == BattleValueSource.RULE })
        }
    }

    @Test fun planningPassEstimatorRunsSafelyOnControlThreadWithTwelvePlusUnits() = runBlocking(LabyrinthFourThreadRuntime.controlDispatcher) {
        assertTrue(LabyrinthFourThreadRuntime.isControlThread())
        val roster = modelRoster(12).mapIndexed { index, profile ->
            if (index == 0) profile.copy(roleClass = "掩护者", functions = LabyrinthRoleFunctions(selfSustain = 80.0))
            else profile
        }
        val profiles = roster.associateBy { it.characterId }
        val estimator = ModelEstimator { 0.80 }
        val passEstimator = requireNotNull(
            createPlanningBattlePassEstimator(
                estimator = estimator,
                profiles = profiles,
                scoring = scorer.config,
                requireLeadVanguard = true,
            ),
        )
        val state = PlanningState(
            difficulty = 5,
            currentArea = 3,
            ownedCharacterIds = profiles.keys,
            relicIds = emptySet(),
            markStacks = emptyMap(),
            remainingShops = 1,
            enhanceNode21 = false,
            reviveAvailable = false,
        )
        val estimate = requireNotNull(passEstimator.estimate(state, BattleExam(questId = 770320101, area = 3, isBoss = false, maxTeams = 1, isEx = true)))
        assertEquals(0.80, estimate.pPass, 1e-12)
        assertTrue(estimator.batches.isNotEmpty())
    }

    @Test fun retryPrefersDiverseCoreOverFourOfFiveRepeatedFailure() {
        val roster = modelRoster(7)
        val valueContext = modelContext(roster)
        val ids = roster.map { it.characterId }
        val unitIds = valueContext.ownedUnits.map { it.unitId }
        val failed1 = ids.subList(0, 5)
        val failed2 = ids.subList(0, 4) + ids[5]
        val fourOverlap = (unitIds.subList(0, 4) + unitIds[6]).sorted()
        val diverseThreeOverlap = (listOf(unitIds[0], unitIds[1], unitIds[4], unitIds[5], unitIds[6])).sorted()
        val estimator = ModelEstimator { team ->
            when (team) {
                fourOverlap -> 0.72
                diverseThreeOverlap -> 0.65
                else -> 0.20
            }
        }
        val planner = planner(roster).withBattleValueEstimator(estimator)
        val failedSignatures = setOf(
            labyrinthBattleTeamSignature(failed1),
            labyrinthBattleTeamSignature(failed2),
        )
        val result = (planner.retryRecommendation(
            acquiredCharacterIds = ids,
            context = valueContext.ruleContext,
            failedTeamSignatures = failedSignatures,
            retryNumber = 2,
            lastFailedTeamSignature = labyrinthBattleTeamSignature(failed2),
            battleValueContext = valueContext,
        ) as LabyrinthBattleTeamRecommendationResult.Ready).recommendation
        val chosenUnits = result.members.map { requireNotNull(labyrinthCharacterIdToUnitId(it.characterId)) }.sorted()
        assertEquals(diverseThreeOverlap, chosenUnits)
        assertTrue(result.modelAssessment?.adopted == true)
    }

    @Test fun bossSearchesDisjointSecondTeamWithoutVanguardGateAndCapsLowTwoTeamDamageEstimate() {
        val roster = modelRoster(10)
        val ruleContext = LabyrinthRoleDecisionContext(0, currentArea = 3, frontlineSurvivalGateEnabled = false, optimizeBossVanguardSynergy = true)
        val valueContext = modelContext(roster, ruleContext)
        val firstTeam = valueContext.ownedUnits.take(5).map { it.unitId }.sorted()
        val secondTeam = valueContext.ownedUnits.drop(5).map { it.unitId }.sorted()
        val estimator = ModelEstimator(damageMean = { 0.30 }) { team ->
            when (team) {
                firstTeam -> 0.60
                secondTeam -> 0.70
                else -> 0.10
            }
        }
        val result = requireNotNull(ModelGuidedBattleTeamSearcher(estimator, optimizer = null).search(
            valueContext, roster, emptyList(), emptySet(), requestedBossTeamCount = 2, supplementLead = false,
        ))
        assertEquals(2, result.teams.size)
        val packed = result.teams.map { t -> t.map { requireNotNull(labyrinthCharacterIdToUnitId(it.characterId)) }.sorted() }
        assertEquals(setOf(firstTeam, secondTeam), packed.toSet())
        assertEquals(listOf(0.70, 0.60), result.assessment.teamWinProbabilities)

        // Two <20% teams must stay conservative (capped <= 0.68, below SAFE 0.70) even with high mean damage.
        val lowTwoTeamProb = combinedMultiTeamPassProb(listOf(
            BattleValue(0.08, 0.0, 0.92, 0.85, 0.0, 0.08),
            BattleValue(0.06, 0.0, 0.94, 0.85, 0.0, 0.06),
        ))
        assertEquals(0.68, lowTwoTeamProb, 1e-12)
        assertTrue(lowTwoTeamProb < BattleConfidenceTier.SAFE_THRESHOLD)
    }

    @Test fun lowConfidenceFallbackLogsRuleLeadNnWinProbability() {
        val roster = modelRoster(6)
        val ids = roster.map { it.characterId }
        val valueContext = modelContext(roster)
        val planner = planner(roster)
        val rule = (planner.initialRecommendation(ids, valueContext.ruleContext)
            as LabyrinthBattleTeamRecommendationResult.Ready).recommendation
        val ruleUnitIds = rule.members.map { requireNotNull(labyrinthCharacterIdToUnitId(it.characterId)) }.sorted()
        val modelIds = ids.filterNot { it == rule.members.first().characterId }
        val modelUnitIds = modelIds.map { requireNotNull(labyrinthCharacterIdToUnitId(it)) }.sorted()
        val estimator = ModelEstimator { team ->
            when (team) {
                modelUnitIds -> 0.49
                ruleUnitIds -> 0.40
                else -> 0.10
            }
        }
        val rec = (planner.withBattleValueEstimator(estimator)
            .initialRecommendation(ids, valueContext.ruleContext, battleValueContext = valueContext)
            as LabyrinthBattleTeamRecommendationResult.Ready).recommendation
        assertEquals(0.40, requireNotNull(rec.modelAssessment?.ruleLeadWinProbability), 1e-12)
        assertTrue(rec.logText().contains("规则队NN胜率=40.0%"))
    }
}
