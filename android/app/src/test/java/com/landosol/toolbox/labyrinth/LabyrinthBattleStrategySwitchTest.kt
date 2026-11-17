package com.landosol.toolbox.labyrinth

import org.junit.Assert.*
import org.junit.Test

/** Small deterministic checks for the two user switches; no production-roster or timing benchmark. */
class LabyrinthBattleStrategySwitchTest {
    private val config = LabyrinthTeamScoringConfig(
        attributeDamageBonus = mapOf(1 to 0.0, 2 to 0.08, 3 to 0.16, 4 to 0.25, 5 to 0.75),
    )
    private val scorer = LabyrinthTeamScorer(config)
    private val optimizer = LabyrinthTeamOptimizer(scorer, searchThreads = 1)
    private val searcher = searcher(optimizer)
    private val off = LabyrinthRoleDecisionContext(defenseMarkStacks = 4, currentArea = 2)
    private val on = off.copy(frontlineSurvivalGateEnabled = true)

    @Test fun `new and legacy settings default both switches off`() {
        for (settings in listOf(LabyrinthStrategySettings(), LabyrinthStrategySettingsCodec.decode(null),
            LabyrinthStrategySettingsCodec.decode("{\"playerWeight\":42}"))) {
            assertFalse(settings.frontlineSurvivalGateEnabled)
            assertFalse(settings.normalBattleSkipTeamSelectionEnabled)
        }
        assertEquals(42, LabyrinthStrategySettingsCodec.decode("{\"playerWeight\":42}").playerWeight)
        assertFalse(off.frontlineSurvivalGateEnabled)
    }

    @Test fun `switches persist independently and do not mutate an earlier snapshot`() {
        val original = LabyrinthStrategySettings()
        for (front in listOf(false, true)) for (skip in listOf(false, true)) {
            val saved = original.copy(frontlineSurvivalGateEnabled = front,
                normalBattleSkipTeamSelectionEnabled = skip)
            assertNull(saved.validationError())
            assertEquals(saved, LabyrinthStrategySettingsCodec.decode(LabyrinthStrategySettingsCodec.encode(saved)))
            assertEquals(saved, LabyrinthStrategySettingsCodec.decodeDraft(LabyrinthStrategySettingsCodec.encodeDraft(saved)))
        }
        assertFalse(original.frontlineSurvivalGateEnabled)
        assertFalse(original.normalBattleSkipTeamSelectionEnabled)
    }

    @Test fun `frontline switch controls the actual front including tied positions`() {
        val roster = listOf(role("tank", 2, reliable = 100.0), role("front", 1)) +
            (3..5).map { role("r$it", it) }
        assertNotNull(optimizer.bestBattleFormation(roster, off))
        assertNull(optimizer.bestBattleFormation(roster, on))
        val tied = roster.map { if (it.characterId == "tank") it.copy(position = 1) else it }
        assertNull(optimizer.bestBattleFormation(tied, on))
        val safe = roster.map {
            if (it.characterId == "tank") it.copy(position = 1) else it.copy(position = requireNotNull(it.position) + 1)
        }
        assertNotNull(optimizer.bestBattleFormation(safe, on))
    }

    @Test fun `strict and relaxed scores apply only when the gate is enabled`() {
        val weakLead = role("lead", 1, reliable = 60.0) // non-tank legacy model: 60 * 0.75 = 45
        val roster = listOf(weakLead) + (2..5).map { role("r$it", it) }
        assertEquals(45.0, weakLead.labyrinthVanguardStrength(off), 0.000001)
        assertNull(optimizer.bestBattleFormation(roster, on))
        assertNotNull(optimizer.bestBattleFormation(roster, on.copy(relaxedVanguardGate = true)))
        assertNotNull(optimizer.bestBattleFormation(roster, off))
    }

    @Test fun `initial and retry recommendations do not retain a hidden gate when disabled`() {
        val roster = (1..6).map { role("r$it", it) } + role("rearTank", 20, reliable = 100.0)
        val planner = planner(roster)
        assertTrue(planner.initialRecommendation(roster.map { it.characterId }, on) is LabyrinthBattleTeamRecommendationResult.Unavailable)
        val first = planner.initialRecommendation(roster.map { it.characterId }, off) as LabyrinthBattleTeamRecommendationResult.Ready
        assertEquals(5, first.recommendation.members.size)
        val failed = labyrinthBattleTeamSignature(first.recommendation.members.map { it.characterId })
        val retry = planner.retryRecommendation(roster.map { it.characterId }, off, setOf(failed)) as LabyrinthBattleTeamRecommendationResult.Ready
        assertEquals(5, retry.recommendation.members.size)
        assertNotEquals(failed, labyrinthBattleTeamSignature(retry.recommendation.members.map { it.characterId }))
        assertTrue(planner.retryRecommendation(roster.map { it.characterId }, on, setOf(failed)) is LabyrinthBattleTeamRecommendationResult.Unavailable)
    }

    @Test fun `boss main team capacity is not capped by tank count when gate is off`() {
        val roster = (1..15).map { role("r$it", it) }
        val closed = searcher.bossMultiTeamSearch(roster, on, requestedTeams = 3, supplementTeams = false)
        assertTrue(closed.teams.isEmpty())
        val open = searcher.bossMultiTeamSearch(roster, off, requestedTeams = 3, supplementTeams = false)
        assertEquals(3, open.teams.size)
        assertEquals(3, open.safeTeamCount)
        assertEquals(15, open.teams.flatMap { it.members }.map { it.characterId }.distinct().size)
        val recommended = planner(roster).initialRecommendation(roster.map { it.characterId }, off,
            requestedBossTeamCount = 3) as LabyrinthBattleTeamRecommendationResult.Ready
        assertEquals(3, recommended.recommendation.plannedBossTeamCount)
    }

    @Test fun `required guide core survives bounded search with gate disabled`() {
        val bounded = LabyrinthTeamOptimizer(LabyrinthTeamScorer(config.copy(exhaustiveRosterLimit = 5)), searchThreads = 1)
        val roster = (1..7).map { role("r$it", it) } + role("guide", 8, score = 1.0)
        val result = requireNotNull(bounded.bestBattleFormationIncluding(roster, off, setOf("guide")))
        assertTrue(result.members.any { it.characterId == "guide" })
        val guideContext = off.copy(encounterStrategy = LabyrinthExEncounterStrategy(
            id = "switch-test", identityName = "switch-test", targetCount = 1, preferEffectiveCharacters = true,
        ), effectiveCharacterIds = setOf("guide"))
        assertEquals(setOf("guide"), labyrinthEncounterGuideCore(roster, guideContext))
        assertTrue(labyrinthEncounterGuideCore(roster, guideContext.copy(frontlineSurvivalGateEnabled = true)).isEmpty())
    }

    @Test fun `bounded gate-off search keeps both retained and inserted guide roles`() {
        val bounded = LabyrinthTeamOptimizer(LabyrinthTeamScorer(config.copy(exhaustiveRosterLimit = 5)), searchThreads = 1)
        val roster = (1..4).map { role("high$it", it, score = 100.0 - it) } +
            listOf(role("retainedGuide", 5, score = 90.0), role("optional", 6, score = 70.0),
                role("insertedGuide", 7, score = 1.0))
        val required = setOf("retainedGuide", "insertedGuide")
        val result = requireNotNull(bounded.bestBattleFormationIncluding(roster, off, required))
        assertEquals(5, result.members.size)
        assertTrue(result.members.map { it.characterId }.containsAll(required))
    }

    @Test fun `disabling the gate keeps all soft team scoring intact`() {
        val roster = (1..5).map { role("r$it", it) }
        assertEquals(scorer.evaluate(roster, on), scorer.evaluate(roster, off))
    }

    @Test fun `disabled gate keeps boss multi-team requirement and aligns short-roster retry with initial`() {
        val roster = (1..4).map { role("r$it", it) }
        assertTrue(searcher.bossMultiTeamSearch(roster, off).teams.isEmpty())
        val retry = planner(roster).retryRecommendation(roster.map { it.characterId }, off, emptySet())
            as LabyrinthBattleTeamRecommendationResult.Ready
        assertEquals(4, retry.recommendation.members.size)
        val exhausted = labyrinthBattleTeamSignature(roster.map { it.characterId })
        assertTrue(planner(roster).retryRecommendation(roster.map { it.characterId }, off, setOf(exhausted))
            is LabyrinthBattleTeamRecommendationResult.Unavailable)
        assertTrue(planner(roster).retryRecommendation(emptyList(), off, emptySet())
            is LabyrinthBattleTeamRecommendationResult.Unavailable)
    }

    @Test fun `EX healer recovery also respects disabled frontline checking`() {
        val roster = (1..5).map { role("r$it", it) } + role("healer", 6).let {
            it.copy(roleClass = "治疗者", functions = it.functions.copy(healing = 100.0, pureHealer = 100.0))
        }
        val failed = labyrinthBattleTeamSignature((1..5).map { "r$it" })
        val context = off.copy(encounterStrategy = LabyrinthExEncounterStrategy(
            id = "recovery-test", identityName = "recovery-test", targetCount = 1,
        ))
        val result = planner(roster).retryRecommendation(roster.map { it.characterId }, context, setOf(failed),
            retryNumber = 2, lastFailedTeamSignature = failed) as LabyrinthBattleTeamRecommendationResult.Ready
        assertTrue(result.recommendation.members.any { it.characterId == "healer" })
        assertTrue(result.recommendation.reasons.any { it.contains("EX第二次失败生存兜底") })
        assertTrue(result.recommendation.reasons.any { it.contains("前排生存门槛已关闭") })
        assertFalse(result.recommendation.reasons.any { it.contains("保留一号位候选") })
    }

    private fun planner(roster: List<LabyrinthRoleProfile>) = LabyrinthBattleTeamRecommendationPlanner(
        roster.associateBy { it.characterId }, searcher,
    )

    private fun searcher(value: LabyrinthTeamOptimizer) = LabyrinthTeamPlanSearcher(value, LabyrinthTeamPlanSearchConfig(
        oneTeamKillScore = 0.0, mainTeamScore = 0.0, cleanupTeamScore = 0.0,
        mainPlusCleanupCombinedScore = 0.0, stableTeamScore = 0.0, fallbackTeamScore = 0.0, threeTeamCombinedScore = 0.0,
    ))

    private fun role(id: String, position: Int, reliable: Double = 0.0, score: Double = 80.0) = LabyrinthRoleProfile(
        characterId = id, displayName = id, roleClass = "攻击者",
        attribute = LabyrinthCharacterAttribute.LIGHT, damageType = LabyrinthRoleDamageType.PHYSICAL,
        position = position, userScore = score, userRatingCount = 5,
        physicalDamagePotential = score, magicDamagePotential = 0.0,
        functions = LabyrinthRoleFunctions(reliableVanguard = reliable),
    )
}
