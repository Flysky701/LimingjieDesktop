package com.landosol.toolbox.labyrinth

import com.landosol.toolbox.labyrinth.vision.EntryPixelRect
import com.landosol.toolbox.labyrinth.vision.LabyrinthBattleCharacterMatch
import com.landosol.toolbox.labyrinth.vision.LabyrinthCharacterMatch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LabyrinthRoleDecisionDataTest {
    @Test
    fun `generated production data preserves ratings and latest database facts`() {
        val asset = listOf(
            File("app/src/main/assets/resource-packs/cn-bilibili/labyrinth-role-decision.json"),
            File("src/main/assets/resource-packs/cn-bilibili/labyrinth-role-decision.json"),
        ).first { it.isFile }

        val parsed = when (val result = LabyrinthRoleDecisionDataParser.parse(asset.readText())) {
            is LabyrinthRoleDecisionDataResult.Ready -> result
            is LabyrinthRoleDecisionDataResult.Unavailable -> error(result.reason)
        }
        val violet = parsed.runtime.profiles.getValue("1331")
        val nephira = parsed.runtime.profiles.getValue("1297")
        val summerSheffy = parsed.runtime.profiles.getValue("1351")
        val christmasSaren = parsed.runtime.profiles.getValue("1145")
        val grace = parsed.runtime.profiles.getValue("1330")
        val halloweenMisaki = parsed.runtime.profiles.getValue("1083")
        val medusa = parsed.runtime.profiles.getValue("1336")
        val salarariaHiyori = parsed.runtime.profiles.getValue("1357")

        assertEquals(2, parsed.runtime.document.schemaVersion)
        assertEquals(100.0, violet.userScore ?: error("missing Violet score"), 0.0001)
        assertEquals(5, violet.userRatingCount)
        assertEquals(93.3, nephira.userScore ?: error("missing Nephira score"), 0.0001)
        assertEquals(80.0, summerSheffy.userScore ?: error("missing Summer Sheffy score"), 0.0001)
        assertEquals(143, summerSheffy.position)
        assertEquals(LabyrinthRoleDamageType.PHYSICAL, summerSheffy.damageType)
        assertTrue(violet.isStrictDecisionReady)
        assertEquals(LabyrinthCharacterAttribute.DARK, violet.attribute)
        assertEquals("掩护者", violet.roleClass)
        assertEquals(LabyrinthRoleDamageType.PHYSICAL, violet.damageType)
        assertEquals(154, violet.position)
        assertTrue((christmasSaren.vanguardProfile?.physicalDurability ?: 0.0) > 60.0)
        assertTrue((christmasSaren.vanguardProfile?.panelHp ?: 0) > 100_000)
        assertEquals(98.0, grace.vanguardProfile?.untargetable ?: 0.0, 0.0001)
        assertFalse(grace.isEligibleBattleVanguard(legacyFrontlineContext(defenseMarkStacks = 0)))
        assertTrue((halloweenMisaki.functions.dot ?: 0.0) > 0.0)
        assertTrue((medusa.functions.dot ?: 0.0) > 0.0)
        assertEquals(0.0, salarariaHiyori.functions.dot ?: 0.0, 0.0001)
        listOf("1183", "1184", "1204", "1205", "1206", "1217", "1218", "1243", "1244", "1281", "1282")
            .forEach { memberId -> assertFalse(parsed.runtime.profiles.containsKey(memberId)) }
        listOf("1807", "1808", "1809", "1810", "1811")
            .forEach { canonicalId -> assertTrue(parsed.runtime.profiles.containsKey(canonicalId)) }
    }

    @Test
    fun `production single boss fallback relaxes the vanguard gate into a real multi team plan`() {
        val asset = listOf(
            File("app/src/main/assets/resource-packs/cn-bilibili/labyrinth-role-decision.json"),
            File("src/main/assets/resource-packs/cn-bilibili/labyrinth-role-decision.json"),
        ).first { it.isFile }
        val parsed = when (val result = LabyrinthRoleDecisionDataParser.parse(asset.readText())) {
            is LabyrinthRoleDecisionDataResult.Ready -> result
            is LabyrinthRoleDecisionDataResult.Unavailable -> error(result.reason)
        }
        // 2026-09-14 debug bundle: 19 owned roles, 冰霜魔狼, only 圣诞咲恋 passes the strict gate.
        // The single team wiped three times and the temporary multi-team mode still planned 1 team.
        val acquired = listOf(
            "1213", "1077", "1145", "1344", "1266", "1272", "1295", "1285", "1277", "1065",
            "1008", "1258", "1140", "1178", "1175", "1147", "1061", "1346", "1075",
        )
        val planner = parsed.runtime.battleTeamRecommendationPlanner
        val strict = legacyFrontlineContext(
            defenseMarkStacks = 4,
            targetCount = 3,
            optimizeBossVanguardSynergy = true,
            preferSingleDamageSystem = true,
            encounterStrategy = LabyrinthBossEncounterCatalog.forUnitId(319604),
        )
        val strictPlan = planner.initialRecommendation(acquired, strict, requestedBossTeamCount = 3)
            as LabyrinthBattleTeamRecommendationResult.Ready
        // One tank-led team; the other two slots are filled with damage supplements (2026-09-17).
        assertEquals(1, strictPlan.recommendation.safeBossTeamCount)
        assertEquals(3, strictPlan.recommendation.plannedBossTeamCount)
        assertEquals("1145", strictPlan.recommendation.vanguard.characterId)

        val relaxed = strict.copy(relaxedVanguardGate = true)
        val failed = labyrinthBattleTeamSignature(strictPlan.recommendation.members.map { it.characterId })
        val fallback = planner.retryRecommendation(
            acquiredCharacterIds = acquired,
            context = relaxed,
            failedTeamSignatures = setOf(failed),
            retryNumber = 1,
            lastFailedTeamSignature = failed,
            requestedBossTeamCount = 3,
        ) as LabyrinthBattleTeamRecommendationResult.Ready
        assertTrue(
            "planned=${fallback.recommendation.plannedBossTeamCount} reasons=${fallback.recommendation.reasons}",
            fallback.recommendation.plannedBossTeamCount >= 2,
        )
        assertTrue(fallback.recommendation.reasons.any { it.contains("放宽一号位生存线") })
        val first = fallback.recommendation.members.map { it.characterId }
        assertEquals(5, first.size)
        assertFalse(labyrinthBattleTeamSignature(first) == failed)
        // The follow-up team for the second tab must also be plannable from the remaining roster.
        val second = planner.initialRecommendation(
            acquiredCharacterIds = acquired.filterNot(first::contains),
            context = relaxed.copy(optimizeBossVanguardSynergy = false),
            requestedBossTeamCount = 2,
        ) as LabyrinthBattleTeamRecommendationResult.Ready
        assertEquals(5, second.recommendation.members.size)
        assertTrue(second.recommendation.members.none { it.characterId in first })
    }

    @Test
    fun `production 24 role frost wolf roster plans at least two boss teams`() {
        val asset = listOf(
            File("app/src/main/assets/resource-packs/cn-bilibili/labyrinth-role-decision.json"),
            File("src/main/assets/resource-packs/cn-bilibili/labyrinth-role-decision.json"),
        ).first { it.isFile }
        val parsed = when (val result = LabyrinthRoleDecisionDataParser.parse(asset.readText())) {
            is LabyrinthRoleDecisionDataResult.Ready -> result
            is LabyrinthRoleDecisionDataResult.Unavailable -> error(result.reason)
        }
        // 2026-09-15 17:59 debug bundle: 24 owned roles against 冰霜魔狼 with multi-team mode on.
        // Six roles pass the strict vanguard gate, yet the session planned only one team because
        // every formation in the ranked beam reused the same AOE core.
        val acquired = listOf(
            "1351", "1059", "1075", "1225", "1811", "1280", "1332", "1033", "1119", "1137",
            "1058", "1247", "1261", "1018", "1302", "1331", "1028", "1239", "1278", "1264",
            "1108", "1215", "1093", "1068",
        )
        val planner = parsed.runtime.battleTeamRecommendationPlanner
        val context = legacyFrontlineContext(
            defenseMarkStacks = 4,
            targetCount = 3,
            optimizeBossVanguardSynergy = true,
            preferSingleDamageSystem = true,
            encounterStrategy = LabyrinthBossEncounterCatalog.forUnitId(319604),
        )
        val first = planner.initialRecommendation(acquired, context, requestedBossTeamCount = 3)
            as LabyrinthBattleTeamRecommendationResult.Ready
        assertTrue(
            "planned=${first.recommendation.plannedBossTeamCount} reasons=${first.recommendation.reasons}",
            first.recommendation.plannedBossTeamCount >= 2,
        )
        val firstIds = first.recommendation.members.map { it.characterId }
        assertEquals(5, firstIds.size)

        // The follow-up tabs are planned from the leftover roster exactly as the session does it.
        val second = planner.initialRecommendation(
            acquiredCharacterIds = acquired.filterNot(firstIds::contains),
            context = context.copy(optimizeBossVanguardSynergy = false),
            requestedBossTeamCount = first.recommendation.plannedBossTeamCount - 1,
        ) as LabyrinthBattleTeamRecommendationResult.Ready
        assertEquals(5, second.recommendation.members.size)
        assertTrue(second.recommendation.members.none { it.characterId in firstIds })

        // The planner's own multi-team answer must be a set of fully disjoint teams.
        val plan = parsed.runtime.teamPlanSearcher.bossMultiTeamSearch(
            roster = acquired.map(parsed.runtime.profiles::getValue),
            context = context,
            requestedTeams = 3,
        )
        assertTrue("kind=${plan.kind} reason=${plan.reason}", plan.teams.size >= 2)
        val allMembers = plan.teams.flatMap { team -> team.members.map { it.characterId } }
        assertEquals(allMembers.size, allMembers.distinct().size)
        plan.teams.forEach { team -> assertEquals(5, team.members.size) }
    }

    @Test
    fun `production ex first attempt fields the confirmed effective dot dealer against 好朋友X`() {
        val asset = listOf(
            File("app/src/main/assets/resource-packs/cn-bilibili/labyrinth-role-decision.json"),
            File("src/main/assets/resource-packs/cn-bilibili/labyrinth-role-decision.json"),
        ).first { it.isFile }
        val parsed = when (val result = LabyrinthRoleDecisionDataParser.parse(asset.readText())) {
            is LabyrinthRoleDecisionDataResult.Ready -> result
            is LabyrinthRoleDecisionDataResult.Unavailable -> error(result.reason)
        }
        // 2026-09-16 00:26 bundle: 31 owned roles, EX 好朋友X (guide: at least two DOT dealers).
        // 碧 (1040) is the only DOT dealer owned and the in-game effective-effect tab confirmed
        // her, yet the automatic team was five generic attackers three times in a row.
        val roster = listOf(
            "1351", "1059", "1075", "1257", "1801", "1297", "1094", "1181", "1290", "1093", "1261",
            "1810", "1005", "1328", "1242", "1037", "1168", "1245", "1010", "1040", "1030", "1278",
            "1113", "1115", "1173", "1054", "1260", "1009", "1806", "1343", "1808",
        )
        val strategy = requireNotNull(LabyrinthExEncounterCatalog.matchObservedName("好朋友X"))
        val planner = parsed.runtime.battleTeamRecommendationPlanner
        for (effective in listOf(emptySet(), setOf("1040"))) {
            val context = legacyFrontlineContext(
                defenseMarkStacks = 4,
                targetCount = 1,
                preferSingleDamageSystem = true,
                encounterStrategy = strategy,
                effectiveCharacterIds = effective,
            )
            val first = planner.initialRecommendation(roster, context)
                as LabyrinthBattleTeamRecommendationResult.Ready
            assertTrue(
                "effective=$effective members=${first.recommendation.members.map { it.displayName }}",
                first.recommendation.members.any { it.characterId == "1040" },
            )
            assertEquals("1351", first.recommendation.vanguard.characterId)
            assertTrue(first.recommendation.reasons.any { it.contains("已按攻略锁定核心角色") })
        }
    }

    @Test
    fun `production boss multi team degrades to two safe teams when only two vanguards are owned`() {
        val asset = listOf(
            File("app/src/main/assets/resource-packs/cn-bilibili/labyrinth-role-decision.json"),
            File("src/main/assets/resource-packs/cn-bilibili/labyrinth-role-decision.json"),
        ).first { it.isFile }
        val parsed = when (val result = LabyrinthRoleDecisionDataParser.parse(asset.readText())) {
            is LabyrinthRoleDecisionDataResult.Ready -> result
            is LabyrinthRoleDecisionDataResult.Unavailable -> error(result.reason)
        }
        // User-reported Boss roster: only 空花 and 圣诞咲恋 pass the vanguard gate; Grace does not.
        val acquired = listOf(
            "1213", "1077", "1145", "1257", "1071", "1323", "1017", "1324", "1330",
            "1141", "1045", "1167", "1111", "1168", "1302", "1165", "1272", "1066",
            "1122", "1328", "1278", "1112", "1237",
        )
        val planner = parsed.runtime.battleTeamRecommendationPlanner
        val baseContext = legacyFrontlineContext(defenseMarkStacks = 0, targetCount = 1)

        val first = planner.initialRecommendation(
            acquiredCharacterIds = acquired,
            context = baseContext.copy(optimizeBossVanguardSynergy = true),
            requestedBossTeamCount = 3,
        ) as LabyrinthBattleTeamRecommendationResult.Ready
        val firstIds = first.recommendation.members.map { it.characterId }.toSet()
        assertEquals(2, first.recommendation.safeBossTeamCount)
        assertEquals(3, first.recommendation.plannedBossTeamCount)
        assertTrue(first.recommendation.vanguard.characterId in setOf("1045", "1145"))
        assertTrue(first.recommendation.reasons.any { it.contains("安全容量2队") && it.contains("总评分") })

        val second = planner.initialRecommendation(
            acquiredCharacterIds = acquired.filterNot(firstIds::contains),
            context = baseContext,
            requestedBossTeamCount = 1,
        ) as LabyrinthBattleTeamRecommendationResult.Ready
        assertEquals(1, second.recommendation.plannedBossTeamCount)
        assertTrue(second.recommendation.vanguard.characterId in setOf("1045", "1145"))
        assertTrue(second.recommendation.vanguard.characterId != first.recommendation.vanguard.characterId)
        assertFalse(second.recommendation.vanguard.characterId == "1330")

        val frostWolf = planner.initialRecommendation(
            acquiredCharacterIds = acquired,
            context = baseContext.copy(
                targetCount = 3,
                optimizeBossVanguardSynergy = true,
            ),
            requestedBossTeamCount = 3,
        ) as LabyrinthBattleTeamRecommendationResult.Ready
        assertEquals(2, frostWolf.recommendation.safeBossTeamCount)
        assertEquals(3, frostWolf.recommendation.plannedBossTeamCount)
    }

    @Test
    fun `production tactical data distinguishes true wide aoe and summer misogi backline pull`() {
        val asset = listOf(
            File("app/src/main/assets/resource-packs/cn-bilibili/labyrinth-role-decision.json"),
            File("src/main/assets/resource-packs/cn-bilibili/labyrinth-role-decision.json"),
        ).first { it.isFile }
        val parsed = when (val result = LabyrinthRoleDecisionDataParser.parse(asset.readText())) {
            is LabyrinthRoleDecisionDataResult.Ready -> result
            is LabyrinthRoleDecisionDataResult.Unavailable -> error(result.reason)
        }

        val rino = parsed.runtime.profiles.getValue("1011")
        val summerMisogi = parsed.runtime.profiles.getValue("1228")

        assertTrue((rino.functions.wideAoeCoverage ?: 0.0) >= 90.0)
        assertTrue((summerMisogi.functions.backlineAoeCoverage ?: 0.0) >= 90.0)
        assertTrue((summerMisogi.functions.enemyPull ?: 0.0) >= 50.0)
        assertTrue((summerMisogi.functions.enemyPush ?: 0.0) <= 0.0)
        assertTrue(
            (rino.functions.wideAoeCoverage ?: 0.0) >
                (summerMisogi.functions.wideAoeCoverage ?: 0.0),
        )
    }

    @Test
    fun `versioned role decision data builds reward and team runtimes`() {
        val document = document(
            acquiredProfiles() + listOf(
                role("left", "高价值候选", damage = 95.0),
                role("center", "普通候选", damage = 50.0),
                role("right", "较弱候选", damage = 35.0),
            ),
        )

        val parsed = LabyrinthRoleDecisionDataParser.parse(Json.encodeToString(document))
            as LabyrinthRoleDecisionDataResult.Ready
        val choice = parsed.runtime.rewardChoicePlanner.decide(
            candidates = recognizedCandidates(),
            acquiredCharacterIds = acquiredProfiles().map(LabyrinthRoleProfile::characterId).toSet(),
            context = legacyFrontlineContext(defenseMarkStacks = 4),
            frameWidth = 1920,
            frameHeight = 1080,
        ) as LabyrinthRoleRewardChoiceDecision.Select

        assertEquals("left", choice.characterId)
        assertEquals(EntryPixelRect(260, 810, 290, 155), choice.buttonRect)
        assertTrue(choice.explanation.isNotEmpty())
        assertEquals(7, parsed.runtime.profiles.size)

        val recommendation = parsed.runtime.battleTeamRecommendationPlanner.initialRecommendation(
            acquiredCharacterIds = parsed.runtime.profiles.keys,
            context = legacyFrontlineContext(defenseMarkStacks = 4),
        ) as LabyrinthBattleTeamRecommendationResult.Ready
        assertEquals(5, recommendation.recommendation.members.size)
        assertEquals(4, recommendation.recommendation.defenseMarkStacks)
    }

    @Test
    fun `reward planner allows known visual identity without audited profile`() {
        val profiles = (acquiredProfiles() + listOf(
            role("left", "高价值候选", damage = 95.0),
            role("right", "较弱候选", damage = 35.0),
        )).associateBy(LabyrinthRoleProfile::characterId)
        val scorer = LabyrinthTeamScorer(scoring())
        val planner = LabyrinthRoleRewardChoicePlanner(
            profiles,
            LabyrinthRoleChoicePolicy(LabyrinthTeamOptimizer(scorer)),
        )

        val result = planner.decide(
            candidates = recognizedCandidates(),
            acquiredCharacterIds = acquiredProfiles().map(LabyrinthRoleProfile::characterId).toSet(),
            context = legacyFrontlineContext(defenseMarkStacks = 4),
            frameWidth = 1920,
            frameHeight = 1080,
        ) as LabyrinthRoleRewardChoiceDecision.Select

        assertTrue(result.actionSafe)
        assertTrue(result.safetyNote.orEmpty().contains("center"))
        assertEquals("left", result.characterId)
    }

    @Test
    fun `reward planner permits auto tap for incomplete profile`() {
        val profiles = (acquiredProfiles() + listOf(
            role("left", "高价值候选", damage = 95.0).copy(position = null),
            role("center", "普通候选", damage = 50.0),
            role("right", "较弱候选", damage = 35.0),
        )).associateBy(LabyrinthRoleProfile::characterId)
        val planner = LabyrinthRoleRewardChoicePlanner(
            profiles,
            LabyrinthRoleChoicePolicy(LabyrinthTeamOptimizer(LabyrinthTeamScorer(scoring()))),
        )

        val result = planner.decide(
            candidates = recognizedCandidates(),
            acquiredCharacterIds = acquiredProfiles().map(LabyrinthRoleProfile::characterId).toSet(),
            context = legacyFrontlineContext(defenseMarkStacks = 4),
            frameWidth = 1920,
            frameHeight = 1080,
        ) as LabyrinthRoleRewardChoiceDecision.Select

        assertEquals("left", result.characterId)
        assertTrue(result.actionSafe)
        assertTrue(result.safetyNote.orEmpty().contains("left"))
    }

    @Test fun `missing acquired and candidate profiles retain only known identities`() {
        val source = emptyMap<String, LabyrinthRoleProfile>()
        val known = source.withKnownRoleIdentities(listOf("left"), mapOf("left" to "可靠识别的角色"))
        assertEquals("可靠识别的角色", known.getValue("left").displayName)
        assertEquals(null, known.getValue("left").position)
        assertEquals(null, known.getValue("left").userScore)
        assertEquals(null, known.getValue("left").attribute)
        val planner = LabyrinthRoleRewardChoicePlanner(source,
            LabyrinthRoleChoicePolicy(LabyrinthTeamOptimizer(LabyrinthTeamScorer(scoring()))))
        val decision = planner.decide(recognizedCandidates(), setOf("old"),
            legacyFrontlineContext(defenseMarkStacks = 0), 1920, 1080) as LabyrinthRoleRewardChoiceDecision.Select
        assertTrue(decision.actionSafe)
        assertTrue(decision.characterId in listOf("left", "center", "right"))
        assertTrue(decision.safetyNote.orEmpty().contains("old"))
        val weak = recognizedCandidates().map { it.copy(trusted = false) }
        assertTrue(planner.decide(weak, setOf("old"), legacyFrontlineContext(defenseMarkStacks = 0),
            1920, 1080) is LabyrinthRoleRewardChoiceDecision.Wait)
    }

    @Test
    fun `reward planner canonicalizes combined card member ids`() {
        val profiles = (acquiredProfiles() + listOf(
            role("1809", "秋乃&咲恋", damage = 95.0),
            role("center", "普通候选", damage = 50.0),
            role("right", "较弱候选", damage = 35.0),
        )).associateBy(LabyrinthRoleProfile::characterId)
        val scorer = LabyrinthTeamScorer(scoring())
        val planner = LabyrinthRoleRewardChoicePlanner(
            profiles,
            LabyrinthRoleChoicePolicy(LabyrinthTeamOptimizer(scorer)),
        )

        val result = planner.decide(
            candidates = listOf(
                match("role_reward_left", "1217", "秋乃(秋乃&咲恋)", 0.91),
                match("role_reward_center", "center", "普通候选", 0.90),
                match("role_reward_right", "right", "较弱候选", 0.92),
            ),
            acquiredCharacterIds = acquiredProfiles().map(LabyrinthRoleProfile::characterId).toSet(),
            context = legacyFrontlineContext(defenseMarkStacks = 4),
            frameWidth = 1920,
            frameHeight = 1080,
        ) as LabyrinthRoleRewardChoiceDecision.Select

        assertEquals("1809", result.characterId)
        assertEquals("秋乃&咲恋", result.displayName)
        assertEquals(EntryPixelRect(260, 810, 290, 155), result.buttonRect)
    }

    @Test
    fun `reward planner blocks weak shared matcher result until name verification`() {
        val profiles = (acquiredProfiles() + listOf(
            role("left", "高价值候选", damage = 95.0),
            role("center", "普通候选", damage = 50.0),
            role("right", "较弱候选", damage = 35.0),
        )).associateBy(LabyrinthRoleProfile::characterId)
        val planner = LabyrinthRoleRewardChoicePlanner(
            profiles,
            LabyrinthRoleChoicePolicy(LabyrinthTeamOptimizer(LabyrinthTeamScorer(scoring()))),
        )

        val result = planner.decide(
            candidates = listOf(
                trustedMatch("role_reward_left", "left", "高价值候选", confidence = 0.39, margin = 0.15),
                trustedMatch("role_reward_center", "center", "普通候选", confidence = 0.41, margin = 0.10),
                trustedMatch("role_reward_right", "right", "较弱候选", confidence = 0.40, margin = 0.09),
            ),
            acquiredCharacterIds = acquiredProfiles().map(LabyrinthRoleProfile::characterId).toSet(),
            context = legacyFrontlineContext(defenseMarkStacks = 4),
            frameWidth = 1920,
            frameHeight = 1080,
        )

        assertTrue(result is LabyrinthRoleRewardChoiceDecision.Wait)
        assertTrue(
            labyrinthRoleRewardNeedsNameVerification(
                trustedMatch("role_reward_right", "right", "较弱候选", confidence = 0.45, margin = 0.07),
            ),
        )
    }

    @Test
    fun `reward planner accepts weak icon after trusted name verification`() {
        val profiles = (acquiredProfiles() + listOf(
            role("left", "高价值候选", damage = 95.0),
            role("center", "普通候选", damage = 50.0),
            role("right", "较弱候选", damage = 35.0),
        )).associateBy(LabyrinthRoleProfile::characterId)
        val planner = LabyrinthRoleRewardChoicePlanner(
            profiles,
            LabyrinthRoleChoicePolicy(LabyrinthTeamOptimizer(LabyrinthTeamScorer(scoring()))),
        )
        val nameVerifiedRight = trustedMatch(
            "role_reward_right",
            "right",
            "较弱候选",
            confidence = 0.45,
            margin = 0.07,
        ).copy(
            nameEvidenceText = "较弱候选",
            nameEvidenceScore = 1.0,
            nameEvidenceMargin = 0.40,
            nameAssisted = true,
        )

        val result = planner.decide(
            candidates = listOf(
                trustedMatch("role_reward_left", "left", "高价值候选", confidence = 0.62, margin = 0.18),
                trustedMatch("role_reward_center", "center", "普通候选", confidence = 0.60, margin = 0.16),
                nameVerifiedRight,
            ),
            acquiredCharacterIds = acquiredProfiles().map(LabyrinthRoleProfile::characterId).toSet(),
            context = legacyFrontlineContext(defenseMarkStacks = 4),
            frameWidth = 1920,
            frameHeight = 1080,
        )

        assertTrue(result is LabyrinthRoleRewardChoiceDecision.Select)
        assertFalse(labyrinthRoleRewardNeedsNameVerification(nameVerifiedRight))
    }

    @Test
    fun `reward planner ignores one unsafe card and chooses between the two reliable cards`() {
        val profiles = (acquiredProfiles() + listOf(
            role("left", "普通可靠候选", damage = 40.0),
            role("center", "更优可靠候选", damage = 95.0),
            role("right", "未可靠候选", damage = 100.0),
        )).associateBy(LabyrinthRoleProfile::characterId)
        val planner = LabyrinthRoleRewardChoicePlanner(
            profiles,
            LabyrinthRoleChoicePolicy(LabyrinthTeamOptimizer(LabyrinthTeamScorer(scoring()))),
        )

        val result = planner.decide(
            candidates = listOf(
                trustedMatch("role_reward_left", "left", "普通可靠候选", confidence = 0.62, margin = 0.18),
                trustedMatch("role_reward_center", "center", "更优可靠候选", confidence = 0.60, margin = 0.16),
                trustedMatch("role_reward_right", "right", "未可靠候选", confidence = 0.50, margin = 0.07),
            ),
            acquiredCharacterIds = acquiredProfiles().map(LabyrinthRoleProfile::characterId).toSet(),
            context = legacyFrontlineContext(defenseMarkStacks = 4),
            frameWidth = 1920,
            frameHeight = 1080,
        ) as LabyrinthRoleRewardChoiceDecision.Select

        assertEquals("center", result.characterId)
        assertEquals(EntryPixelRect(815, 810, 290, 155), result.buttonRect)
        assertTrue(result.safetyNote.orEmpty().contains("剩余2个可靠候选"))
    }

    @Test
    fun `all combined member ids canonicalize to one battle unit`() {
        val expected = mapOf(
            "1183" to "1807",
            "1184" to "1807",
            "1204" to "1808",
            "1205" to "1808",
            "1206" to "1808",
            "1217" to "1809",
            "1218" to "1809",
            "1243" to "1810",
            "1244" to "1810",
            "1281" to "1811",
            "1282" to "1811",
        )

        expected.forEach { (member, canonical) ->
            assertEquals(canonical, canonicalLabyrinthRoleId(member))
        }
        expected.values.toSet().forEach { canonical ->
            assertEquals(canonical, canonicalLabyrinthRoleId(canonical))
        }
    }

    @Test
    fun `verified final boss replacement favors Nephira over Rino without id special casing`() {
        val asset = listOf(
            File("app/src/main/assets/resource-packs/cn-bilibili/labyrinth-role-decision.json"),
            File("src/main/assets/resource-packs/cn-bilibili/labyrinth-role-decision.json"),
        ).first { it.isFile }
        val runtime = when (val result = LabyrinthRoleDecisionDataParser.parse(asset.readText())) {
            is LabyrinthRoleDecisionDataResult.Ready -> result.runtime
            is LabyrinthRoleDecisionDataResult.Unavailable -> error(result.reason)
        }
        val commonIds = listOf("1351", "1287", "1236", "1071")
        val common = commonIds.map(runtime.profiles::getValue)
        val scorer = LabyrinthTeamScorer(runtime.document.scoring)
        val context = legacyFrontlineContext(defenseMarkStacks = 4, targetCount = 1)

        val rinoTeam = scorer.evaluate(common + runtime.profiles.getValue("1011"), context)
        val nephiraTeam = scorer.evaluate(common + runtime.profiles.getValue("1297"), context)

        assertTrue(nephiraTeam.score > rinoTeam.score)
        assertTrue(runtime.profiles.getValue("1297").effectiveUserScore.orZeroForTest() >
            runtime.profiles.getValue("1011").effectiveUserScore.orZeroForTest())
    }

    @Test
    fun `role decision data rejects duplicate profile ids`() {
        val duplicate = role("same", "重复一")
        val raw = Json.encodeToString(document(listOf(duplicate, duplicate.copy(displayName = "重复二"))))

        val result = LabyrinthRoleDecisionDataParser.parse(raw) as LabyrinthRoleDecisionDataResult.Unavailable

        assertTrue(result.reason.contains("重复ID"))
    }

    @Test
    fun `schema one role choice weights remain readable with deployment semantics`() {
        val legacyDocument = document(
            acquiredProfiles() + listOf(
                role("left", "候选一"),
                role("center", "候选二"),
                role("right", "候选三"),
            ),
        ).copy(
            schemaVersion = 1,
            roleChoice = LabyrinthRoleChoiceConfig(
                forcedCandidateTeamWeight = 0.40,
                secondTeamPotentialWeight = 0.25,
            ),
        )

        val parsed = LabyrinthRoleDecisionDataParser.parse(Json.encodeToString(legacyDocument))
            as LabyrinthRoleDecisionDataResult.Ready

        assertEquals(listOf(1.0, 0.40, 0.25), parsed.runtime.document.roleChoice.effectiveCandidateTeamWeights())
    }

    private fun document(characters: List<LabyrinthRoleProfile>) = LabyrinthRoleDecisionDocument(
        source = LabyrinthRoleDecisionSource(
            ratingWorkbook = "公主连结角色黎明界强度表有图片.xlsx",
            ratingWorkbookHash = "test-rating-hash",
            skillDatabaseVersion = "test-cn-version",
            skillDatabaseHash = "test-db-hash",
            attributeBonusSource = "current-game-config",
        ),
        scoring = scoring(),
        roleChoice = LabyrinthRoleChoiceConfig(),
        teamPlan = LabyrinthTeamPlanSearchConfig(
            oneTeamKillScore = 90.0,
            mainTeamScore = 70.0,
            cleanupTeamScore = 40.0,
            mainPlusCleanupCombinedScore = 120.0,
            stableTeamScore = 60.0,
            fallbackTeamScore = 35.0,
            threeTeamCombinedScore = 120.0,
        ),
        characters = characters,
    )

    private fun scoring() = LabyrinthTeamScoringConfig(
        attributeDamageBonus = mapOf(1 to 0.0, 2 to 0.08, 3 to 0.16, 4 to 0.25, 5 to 0.75),
    )

    private fun acquiredProfiles() = listOf(
        role(
            "front",
            "一号位",
            position = 1,
            damage = 55.0,
            vanguard = 100.0,
            defenseDown = 80.0,
            roleClass = "掩护者",
        ),
        role("owned2", "已有二", damage = 65.0, aoe = 80.0),
        role("owned3", "已有三", damage = 65.0, single = 80.0),
        role("owned4", "已有四", damage = 60.0),
    )

    private fun recognizedCandidates() = listOf(
        match("role_reward_left", "left", "高价值候选", 0.91),
        match("role_reward_center", "center", "普通候选", 0.90),
        match("role_reward_right", "right", "较弱候选", 0.92),
    )

    private fun match(slot: String, id: String, name: String, confidence: Double) = LabyrinthCharacterMatch(
        slotId = slot,
        characterId = id,
        displayName = name,
        confidence = confidence,
        screenRect = EntryPixelRect(0, 0, 100, 100),
    )

    private fun trustedMatch(
        slot: String,
        id: String,
        name: String,
        confidence: Double,
        margin: Double,
    ) = LabyrinthCharacterMatch(
        slotId = slot,
        characterId = id,
        displayName = name,
        confidence = confidence,
        screenRect = EntryPixelRect(0, 0, 100, 100),
        trusted = true,
        rivalMargin = margin,
    )

    private fun role(
        id: String,
        name: String,
        position: Int = 5,
        damage: Double = 50.0,
        vanguard: Double = 0.0,
        defenseDown: Double = 0.0,
        aoe: Double = 0.0,
        single: Double = 0.0,
        roleClass: String = "fighter",
    ) = LabyrinthRoleProfile(
        characterId = id,
        displayName = name,
        roleClass = roleClass,
        attribute = LabyrinthCharacterAttribute.LIGHT,
        damageType = LabyrinthRoleDamageType.PHYSICAL,
        position = position,
        baseQuality = damage,
        physicalDamagePotential = damage,
        magicDamagePotential = 0.0,
        functions = LabyrinthRoleFunctions(
            reliableVanguard = vanguard,
            physicalDefenseDown = defenseDown,
            aoeDamage = aoe,
            singleTargetDamage = single,
        ),
    )

    private fun Double?.orZeroForTest(): Double = this ?: 0.0

    /**
     * The event free-role page shows a whole roster of roles this run has not got yet, so the
     * answer is simply the best-scoring card on screen. It must not re-run the team-placement
     * search the three-card reward page uses: that cost scales with the number of cards, and this
     * page has an order of magnitude more of them.
     */
    @Test
    fun `event free role picks the highest scoring recognizable card on the page`() {
        fun card(id: String, left: Int, top: Int, trusted: Boolean = true, selected: Boolean = false) =
            LabyrinthBattleCharacterMatch(
                slotId = "card-$id", characterId = id, displayName = "名称$id", iconVariant = "test",
                confidence = if (trusted) 0.9 else 0.2,
                screenRect = EntryPixelRect(left, top, 120, 150), selected = selected, trusted = trusted,
            )
        val profiles = mapOf(
            "low" to role("low", "低分", damage = 40.0).copy(userScore = 55.0),
            "best" to role("best", "最高", damage = 40.0).copy(userScore = 92.0),
            "mid" to role("mid", "中等", damage = 40.0).copy(userScore = 71.0),
            "owned" to role("owned", "已有", damage = 40.0).copy(userScore = 99.0),
            "unreadable" to role("unreadable", "认不出", damage = 40.0).copy(userScore = 98.0),
        )
        val planner = LabyrinthRoleRewardChoicePlanner(
            profiles,
            LabyrinthRoleChoicePolicy(LabyrinthTeamOptimizer(LabyrinthTeamScorer(scoring()))),
        )
        val page = listOf(
            card("low", 100, 100),
            card("owned", 300, 100),
            card("best", 500, 100),
            // An unrecognizable portrait must be skipped, not block the page.
            card("unreadable", 700, 100, trusted = false),
            card("mid", 900, 100),
        )
        val context = legacyFrontlineContext(0)

        val first = planner.decideFreeVisibleRole(page, setOf("owned"), context)
                as LabyrinthRoleRewardChoiceDecision.Select
        assertEquals("best", first.characterId)
        assertTrue(first.actionSafe)
        // The tap must land inside that card.
        assertTrue(first.buttonRect.left >= 500 && first.buttonRect.left < 620)

        // A second pick (multi-select events) walks down the same ranking: the previous choice
        // comes back marked selected/acquired and the next-best card wins.
        val second = planner.decideFreeVisibleRole(
            page.map { if (it.characterId == "best") it.copy(selected = true) else it },
            setOf("owned", "best"),
            context,
        ) as LabyrinthRoleRewardChoiceDecision.Select
        assertEquals("mid", second.characterId)

        val third = planner.decideFreeVisibleRole(
            page.map { if (it.characterId in setOf("best", "mid")) it.copy(selected = true) else it },
            setOf("owned", "best", "mid"),
            context,
        ) as LabyrinthRoleRewardChoiceDecision.Select
        assertEquals("low", third.characterId)

        // Nothing scorable left on the page is a wait, not a crash or a blind tap.
        val exhausted = planner.decideFreeVisibleRole(
            page.map { it.copy(selected = true) }, setOf("owned"), context,
        )
        assertTrue(exhausted is LabyrinthRoleRewardChoiceDecision.Wait)
    }
}
