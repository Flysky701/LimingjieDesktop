package com.landosol.toolbox.labyrinth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LabyrinthBattleFormationModeTest {
    private val aggressive = LabyrinthFrontlineSurvivalMode.VERY_AGGRESSIVE
    private val stable = LabyrinthFrontlineSurvivalMode.STABLE
    private val cautious = LabyrinthFrontlineSurvivalMode.CAUTIOUS
    private val scoring = LabyrinthTeamScoringConfig(
        attributeDamageBonus = mapOf(1 to 0.0, 2 to 0.08, 3 to 0.16, 4 to 0.25, 5 to 0.75),
    )

    @Test fun `aggressive candidates exclude only known protectors and preserve unknown jobs`() {
        val profiles = listOf(
            role("tank", 1, "掩护者", survival = 100.0),
            role("sturdy", 2, survival = 100.0),
            role("healer", 3, "治疗者", survival = 100.0, healing = 100.0),
            role("unknown", 4, job = null),
            role("1807", 5),
        ).associateBy { it.characterId }
        val selection = planner(profiles).formationCandidateSelection(
            listOf("tank", "sturdy", "healer", "unknown", "missing", "1183", "1184", "1807", "sturdy"), aggressive,
        )
        assertEquals(listOf("sturdy", "healer", "unknown", "missing", "1807"), selection.characterIds)
        assertEquals(aggressive, selection.effectiveMode)
        assertNull(selection.fallbackReason)
        assertEquals(100.0, profiles.getValue("sturdy").functions.reliableVanguard!!, 0.0)
        assertEquals("掩护者", profiles.getValue("tank").roleClass)
    }

    @Test fun `unknown job participates in aggressive recommendations without becoming a protector`() {
        val profiles = (1..5).map { role("a$it", it, job = if (it == 1) null else "攻击者") }
            .plus(role("tank", 6, "掩护者", survival = 100.0)).associateBy { it.characterId }
        val result = ready(planner(profiles).initialRecommendation(profiles.keys, context(aggressive)))
        assertEquals((1..5).map { "a$it" }.toSet(), result.members.map { it.characterId }.toSet())
        assertEquals(aggressive, result.formationMode)
        assertNull(profiles.getValue("a1").roleClass)
        assertFalse(result.members.any { it.characterId == "tank" })
    }

    @Test fun `stable and cautious keep the complete canonical candidate pool`() {
        val profiles = listOf(role("tank", 1, "掩护者"), role("1807", 2)).associateBy { it.characterId }
        for (mode in listOf(stable, cautious)) {
            val selected = planner(profiles).formationCandidateSelection(listOf("tank", "1183", "1184", "unknown"), mode)
            assertEquals(listOf("tank", "1807", "unknown"), selected.characterIds)
            assertEquals(mode, selected.effectiveMode)
            assertNull(selected.fallbackReason)
        }
    }

    @Test fun `fewer than five non protectors restores full given pool not global profiles`() {
        val profiles = roster().plus("used" to role("used", 1, "掩护者", survival = 100.0))
        val given = listOf("tank", "a1", "a2", "a3", "a4", "a4")
        val selected = planner(profiles).formationCandidateSelection(given, aggressive)
        assertEquals(given.distinct(), selected.characterIds)
        assertEquals(stable, selected.effectiveMode)
        assertEquals(LabyrinthBattleFormationFallbackReason.INSUFFICIENT_NON_PROTECTORS, selected.fallbackReason)
        assertFalse("used" in selected.characterIds)
        val recommendation = ready(planner(profiles).initialRecommendation(given, context(aggressive)))
        assertEquals(given.toSet(), recommendation.members.map { it.characterId }.toSet())
        assertEquals(stable, recommendation.formationMode)
        assertEquals(selected.fallbackReason, recommendation.formationFallbackReason)
        assertTrue(recommendation.reasons.first().contains(selected.fallbackReason!!.description))
    }

    @Test fun `exactly five non protectors fail then stable creates a new team without clearing failures`() {
        val profiles = roster()
        val planner = planner(profiles)
        val first = ready(planner.initialRecommendation(profiles.keys, context(aggressive)))
        assertEquals((1..5).map { "a$it" }.toSet(), first.members.map { it.characterId }.toSet())
        assertEquals(aggressive, first.formationMode)
        val failed = labyrinthBattleTeamSignature(first.members.map { it.characterId })
        val failures = setOf(failed)
        val retry = ready(planner.retryRecommendation(profiles.keys, context(aggressive), failures))
        assertTrue(retry.members.any { it.characterId == "tank" })
        assertFalse(labyrinthBattleTeamSignature(retry.members.map { it.characterId }) in failures)
        assertEquals(stable, retry.formationMode)
        assertEquals(LabyrinthBattleFormationFallbackReason.NO_UNFAILED_FORMATION, retry.formationFallbackReason)
        assertEquals(setOf(failed), failures)
        assertTrue(retry.reasons.first().contains(stable.label))
        assertTrue(retry.logText().contains("Boss安全队数="))
    }

    @Test fun `stable exhaustion after aggressive fallback is typed and preserves metadata`() {
        val profiles = roster()
        val failures = allFiveSignatures(profiles.keys.toList())
        val result = unavailable(planner(profiles).retryRecommendation(profiles.keys, context(aggressive), failures))
        assertEquals(LabyrinthBattleFormationFailure.EXHAUSTED_AFTER_STABLE, result.failureKind)
        assertEquals(stable, result.formationMode)
        assertEquals(LabyrinthBattleFormationFallbackReason.NO_UNFAILED_FORMATION, result.formationFallbackReason)
        assertTrue(result.reason.contains(stable.label))
        assertTrue(result.reason.contains(result.formationFallbackReason!!.description))
    }

    @Test fun `explicit stable or cautious exhaustion never pretends an aggressive fallback happened`() {
        val profiles = roster()
        for (mode in listOf(stable, cautious)) {
            val result = unavailable(planner(profiles).retryRecommendation(
                profiles.keys, context(mode), allFiveSignatures(profiles.keys.toList()),
            ))
            assertEquals(LabyrinthBattleFormationFailure.NO_LEGAL_FORMATION, result.failureKind)
            assertEquals(mode, result.formationMode)
            assertNull(result.formationFallbackReason)
        }
    }

    @Test fun `stable fallback cannot borrow a protector committed to a previous Boss team`() {
        val profiles = roster().plus("usedTank" to role("usedTank", 1, "掩护者", survival = 100.0, score = 100.0))
        val remaining = profiles.keys.filterNot { it == "usedTank" }
        val failed = labyrinthBattleTeamSignature((1..5).map { "a$it" })
        val result = ready(planner(profiles).retryRecommendation(
            remaining, context(aggressive), setOf(failed), requestedBossTeamCount = 2, allowSupplementLead = true,
        ))
        assertTrue(result.members.all { it.characterId in remaining })
        assertFalse(result.members.any { it.characterId == "usedTank" })
        assertTrue(result.members.any { it.characterId == "tank" })
        assertEquals(stable, result.formationMode)
    }

    @Test fun `ordinary initial battles still support short teams in every mode`() {
        val profiles = listOf(role("a", 1), role("b", 2)).associateBy { it.characterId }
        for (mode in listOf(aggressive, stable, cautious)) {
            val result = ready(planner(profiles).initialRecommendation(profiles.keys, context(mode)))
            assertEquals(2, result.members.size)
            assertEquals(if (mode == aggressive) stable else mode, result.formationMode)
        }
    }

    @Test fun `Boss multi team requires five members and short team retry exhausts after its only lineup fails`() {
        val profiles = listOf(role("a", 1), role("b", 2)).associateBy { it.characterId }
        val planner = planner(profiles)
        val boss = unavailable(planner.initialRecommendation(profiles.keys, context(aggressive), requestedBossTeamCount = 2))
        assertEquals(LabyrinthBattleFormationFailure.EXHAUSTED_AFTER_STABLE, boss.failureKind)
        assertEquals(stable, boss.formationMode)
        assertEquals(LabyrinthBattleFormationFallbackReason.INSUFFICIENT_NON_PROTECTORS, boss.formationFallbackReason)

        val retry = ready(planner.retryRecommendation(profiles.keys, context(aggressive), emptySet()))
        assertEquals(2, retry.members.size)
        assertEquals(setOf("a", "b"), retry.members.map { it.characterId }.toSet())
        assertEquals(stable, retry.formationMode)
        assertEquals(LabyrinthBattleFormationFallbackReason.INSUFFICIENT_NON_PROTECTORS, retry.formationFallbackReason)

        val failedSignatures = setOf(labyrinthBattleTeamSignature(listOf("a", "b")))
        val exhausted = unavailable(planner.retryRecommendation(profiles.keys, context(aggressive), failedSignatures))
        assertEquals(LabyrinthBattleFormationFailure.EXHAUSTED_AFTER_STABLE, exhausted.failureKind)
        assertEquals(stable, exhausted.formationMode)
        assertEquals(LabyrinthBattleFormationFallbackReason.INSUFFICIENT_NON_PROTECTORS, exhausted.formationFallbackReason)
    }

    @Test fun `aggressive scoring keeps non protector frontline and healing contributions`() {
        val profiles = (1..5).map { index ->
            role("a$index", index, if (index == 5) "治疗者" else "攻击者",
                survival = if (index == 1) 100.0 else 0.0, healing = if (index == 5) 100.0 else 0.0)
        }.associateBy { it.characterId }
        val context = context(aggressive)
        val result = ready(planner(profiles).initialRecommendation(profiles.keys, context))
        val evaluation = LabyrinthTeamScorer(scoring).evaluate(profiles.values.toList(), context)
        assertTrue(evaluation.frontlineScore > 0.0)
        assertTrue(evaluation.sustainScore > 0.0)
        assertEquals(evaluation.score, result.score, 0.000001)
        assertEquals(100.0, profiles.getValue("a1").functions.reliableVanguard!!, 0.0)
        assertEquals(100.0, profiles.getValue("a5").functions.healing!!, 0.0)
        assertEquals(aggressive, result.formationMode)
    }

    @Test fun `legacy gate off retains protectors and legacy gate on retains the old refusal`() {
        val profiles = (1..5).map { role("a$it", it) }.plus(
            role("tank", 100, "掩护者", survival = 100.0, score = 100.0),
        ).associateBy { it.characterId }
        val planner = planner(profiles)
        val off = LabyrinthRoleDecisionContext(defenseMarkStacks = 4, frontlineSurvivalGateEnabled = false)
        val on = off.copy(frontlineSurvivalGateEnabled = true)
        assertNull(off.frontlineSurvivalMode)
        val offResult = ready(planner.initialRecommendation(profiles.keys, off))
        assertTrue(offResult.members.any { it.characterId == "tank" })
        assertNull(offResult.formationMode)
        assertNull(offResult.formationFallbackReason)
        val onResult = unavailable(planner.initialRecommendation(profiles.keys, on))
        assertNull(onResult.formationMode)
        assertNull(onResult.formationFallbackReason)
        assertEquals(LabyrinthBattleFormationFailure.OTHER, onResult.failureKind)
        assertEquals(aggressive, ready(planner.initialRecommendation(profiles.keys, context(aggressive))).formationMode)
    }

    @Test fun `unloaded profiles cannot trigger stable exhaustion or a restart permission`() {
        val ids = (1..6).map { "unloaded$it" }
        val result = unavailable(planner(emptyMap()).retryRecommendation(ids, context(aggressive), allFiveSignatures(ids)))
        assertEquals(LabyrinthBattleFormationFailure.OTHER, result.failureKind)
        assertEquals(stable, result.formationMode)
        assertEquals(LabyrinthBattleFormationFallbackReason.NO_UNFAILED_FORMATION, result.formationFallbackReason)
    }

    @Test fun `EX healer recovery uses the same aggressive filtered roster and context`() {
        val profiles = roster().plus("heal" to role("heal", 8, "治疗者", healing = 100.0))
        val guide = LabyrinthExEncounterStrategy(id = "healing_test", identityName = "好朋友X", targetCount = 1)
        val failed = labyrinthBattleTeamSignature((1..5).map { "a$it" })
        val result = ready(planner(profiles).retryRecommendation(
            profiles.keys, context(aggressive).copy(encounterStrategy = guide), setOf(failed),
            retryNumber = 2, lastFailedTeamSignature = failed,
        ))
        assertTrue(result.members.any { it.characterId == "heal" })
        assertFalse(result.members.any { it.characterId == "tank" })
        assertEquals(aggressive, result.formationMode)
        assertNull(result.formationFallbackReason)
        assertTrue(result.reasons.any { it.contains("EX第二次失败") })
    }

    @Test fun `Boss multi team search does not reintroduce filtered protectors`() {
        val profiles = roster().plus((6..10).associate { "a$it" to role("a$it", it + 1) })
        val result = ready(planner(profiles).initialRecommendation(profiles.keys, context(aggressive), requestedBossTeamCount = 2))
        assertEquals(2, result.plannedBossTeamCount)
        assertFalse(result.members.any { it.characterId == "tank" })
        assertEquals(aggressive, result.formationMode)
    }

    @Test fun incompletePositionOrMissingProfileStillTriesStableOnce() {
        for (missingProfile in listOf(false, true)) {
            val profiles = roster().toMutableMap()
            val ids = profiles.keys.toList()
            if (missingProfile) profiles.remove("a1")
            else profiles["a1"] = profiles.getValue("a1").copy(position = null)
            val failed = labyrinthBattleTeamSignature((1..5).map { "a$it" })
            val result = ready(planner(profiles).retryRecommendation(
                ids, context(aggressive), setOf(failed),
            ))
            assertEquals(stable, result.formationMode)
            assertEquals(LabyrinthBattleFormationFallbackReason.NO_UNFAILED_FORMATION, result.formationFallbackReason)
            assertTrue(result.members.any { it.characterId == "tank" })
            assertNotEquals(failed, labyrinthBattleTeamSignature(result.members.map { it.characterId }))
        }
    }

    private fun context(mode: LabyrinthFrontlineSurvivalMode) =
        LabyrinthRoleDecisionContext(defenseMarkStacks = 4).withFrontlineSurvivalMode(mode)

    private fun roster(): Map<String, LabyrinthRoleProfile> =
        listOf(role("tank", 1, "掩护者", survival = 100.0))
            .plus((1..5).map { role("a$it", it + 1) }).associateBy { it.characterId }

    private fun role(
        id: String,
        position: Int,
        job: String? = "攻击者",
        survival: Double = 0.0,
        healing: Double = 0.0,
        score: Double = 80.0,
    ) = LabyrinthRoleProfile(
        characterId = id,
        displayName = id,
        roleClass = job,
        attribute = LabyrinthCharacterAttribute.LIGHT,
        damageType = LabyrinthRoleDamageType.PHYSICAL,
        position = position,
        userScore = score,
        physicalDamagePotential = 70.0,
        magicDamagePotential = 0.0,
        functions = LabyrinthRoleFunctions(reliableVanguard = survival, healing = healing),
    )

    private fun planner(profiles: Map<String, LabyrinthRoleProfile>): LabyrinthBattleTeamRecommendationPlanner =
        LabyrinthBattleTeamRecommendationPlanner(
            profiles,
            LabyrinthTeamPlanSearcher(
                LabyrinthTeamOptimizer(LabyrinthTeamScorer(scoring)),
                LabyrinthTeamPlanSearchConfig(
                    oneTeamKillScore = 0.0, mainTeamScore = 0.0, cleanupTeamScore = 0.0,
                    mainPlusCleanupCombinedScore = 0.0, stableTeamScore = 0.0,
                    fallbackTeamScore = 0.0, threeTeamCombinedScore = 0.0,
                ),
            ),
        )

    private fun ready(result: LabyrinthBattleTeamRecommendationResult): LabyrinthBattleTeamRecommendation {
        assertTrue("Expected ready, got $result", result is LabyrinthBattleTeamRecommendationResult.Ready)
        return (result as LabyrinthBattleTeamRecommendationResult.Ready).recommendation
    }

    private fun unavailable(result: LabyrinthBattleTeamRecommendationResult): LabyrinthBattleTeamRecommendationResult.Unavailable {
        assertTrue("Expected unavailable, got $result", result is LabyrinthBattleTeamRecommendationResult.Unavailable)
        return result as LabyrinthBattleTeamRecommendationResult.Unavailable
    }

    private fun allFiveSignatures(ids: List<String>): Set<String> {
        val signatures = mutableSetOf<String>()
        for (a in 0 until ids.size - 4) for (b in a + 1 until ids.size - 3)
            for (c in b + 1 until ids.size - 2) for (d in c + 1 until ids.size - 1)
                for (e in d + 1 until ids.size) signatures += labyrinthBattleTeamSignature(
                    listOf(ids[a], ids[b], ids[c], ids[d], ids[e]),
                )
        return signatures
    }
}
