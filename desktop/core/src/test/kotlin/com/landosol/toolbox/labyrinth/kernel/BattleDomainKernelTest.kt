package com.landosol.toolbox.labyrinth.kernel

import com.landosol.toolbox.automation.AutomationAction
import com.landosol.toolbox.automation.AutomationActionResult
import com.landosol.toolbox.automation.ScreenPoint
import com.landosol.toolbox.labyrinth.*
import com.landosol.toolbox.labyrinth.kernel.battle.*
import com.landosol.toolbox.labyrinth.node.LabyrinthNodeTypes
import com.landosol.toolbox.labyrinth.vision.*
import com.landosol.toolbox.labyrinth.vision.LabyrinthEntryPageState.*
import org.junit.Assert.*
import org.junit.Test

class BattleDomainKernelTest {
    @Test
    fun exProbeGetsFreshBudgetWhenReturningToChallenge() {
        val rig = Rig()
        val challenge = frame(BATTLE_CHALLENGE, ex = LabyrinthExEncounterObservation(
            challengeDifficultyResolved = true, extremeChallenge = true,
            multiMonsterLikely = true, slot3InfoButtonRect = probeRect,
        ))
        assertWait(rig.observe(challenge, 100))
        val first = rig.observe(challenge, 400)
        assertAction("EX_PROBE_DETAIL", probeRect, first)
        assertEquals(0, rig.module.snapshot().exSlot3ProbeAttempts)
        rig.execute(first, 400)
        assertWait(rig.observe(challenge, 5_400))
        val second = rig.observe(challenge, 5_500)
        assertAction("EX_PROBE_DETAIL", probeRect, second)
        rig.execute(second, 5_500)
        assertEquals(2, rig.module.snapshot().exSlot3ProbeAttempts)
        assertError("EX_DETAIL_OPEN_TIMEOUT", KernelErrorCategory.TIMEOUT, rig.observe(challenge, 10_500))

        assertFalse(rig.observe(frame(NODE_SELECTION), 15_000).consumed)
        val returning = rig.observe(challenge, 40_000)
        assertWait(returning)
        with(rig.module.snapshot()) {
            assertEquals(LabyrinthCombatKind.EX, combatContext?.kind)
            assertNull(exEncounterStrategy)
            assertEquals(0, exSlot3ProbeAttempts)
            assertFalse(exSlot3ProbePending)
            assertEquals(40_000L, exEncounterProbeStartedAt)
        }
        val freshProbe = rig.observe(challenge, 40_300)
        assertAction("EX_PROBE_DETAIL", probeRect, freshProbe)
        rig.execute(freshProbe, 40_300)
        assertEquals(1, rig.module.snapshot().exSlot3ProbeAttempts)
        assertTrue(rig.runtime.log.causalChain(requireNotNull(freshProbe.causeEvent).eventSeq)
            .any { it.reasonCode == "EX_IDENTITY_PROBE_RESTARTED" })
    }

    @Test
    fun normalTitleMustResolveButPersistedBossRouteCanRestoreContext() {
        val rig = Rig()
        val challenge = frame(BATTLE_CHALLENGE, anchors = anchors(EntryAnchorId.BATTLE_CHALLENGE_BUTTON to challengeRect))
        val event = BattleFrameContext(activeNodeType = LabyrinthNodeTypes.EVENT)
        assertWait(rig.observe(challenge, 0, event))
        assertWait(rig.observe(challenge, 9_999, event))
        assertError("BATTLE_CHALLENGE_TITLE_TIMEOUT", KernelErrorCategory.TIMEOUT, rig.observe(challenge, 10_000, event))
        assertNull(rig.module.snapshot().combatContext)
        assertFalse(rig.observe(frame(NODE_MAP_VIEW), 10_300, event).consumed)
        assertWait(rig.observe(challenge, 20_000, event))
        val resolved = challenge.copy(exEncounter = LabyrinthExEncounterObservation(challengeDifficultyResolved = true))
        assertAction("BATTLE_START_CHALLENGE", challengeRect, rig.observe(resolved, 20_300, event))
        assertEquals(LabyrinthCombatContext(LabyrinthCombatKind.NORMAL), rig.module.snapshot().combatContext)

        val boss = Rig()
        val bossNode = LabyrinthNodeJson(3, 2, 1, 70L, LabyrinthNodeTypes.BOSS, null, listOf(71L), true)
        val nextAreaStart = LabyrinthNodeJson(4, 0, 1, 71L, LabyrinthNodeTypes.START, null, emptyList(), false)
        val route = LabyrinthRouteJson(1L, 1, 1, 1, false, false, "", emptyList(), emptyList(),
            listOf(bossNode, nextAreaStart), currentBlockId = nextAreaStart.blockId)
        val persisted = BattleFrameContext(retry = BattleRetryPolicyContext(route = route))
        assertWait(boss.observe(challenge, 0, persisted))
        assertAction("BATTLE_START_CHALLENGE", challengeRect, boss.observe(challenge, 300, persisted))
        assertEquals(LabyrinthCombatContext(LabyrinthCombatKind.BOSS), boss.module.snapshot().combatContext)
        assertEquals(3, boss.module.snapshot().activeArea)
    }

    @Test
    fun manualExDetailIsReadOnlyAndUnknownOwnedDetailClosesBeforeFallback() {
        val rig = Rig()
        val ex = LabyrinthExEncounterObservation(challengeDifficultyResolved = true, extremeChallenge = true,
            specialDualLikely = true, specialDualIdentityInfoButtonRect = probeRect)
        val challenge = frame(BATTLE_CHALLENGE, ex = ex,
            anchors = anchors(EntryAnchorId.BATTLE_CHALLENGE_BUTTON to challengeRect))
        val modal = frame(UNKNOWN, ex = ex.copy(monsterDetailOpen = true, closeButtonRect = closeRect))
        assertWait(rig.observe(modal, 0))
        assertWait(rig.observe(challenge, 300))
        val probe = rig.observe(challenge, 600)
        assertAction("EX_PROBE_DETAIL", probeRect, probe)
        rig.execute(probe, 600)
        assertWait(rig.observe(modal, 900))
        assertWait(rig.observe(modal, 10_899))
        val close = rig.observe(modal, 10_900)
        assertAction("EX_CLOSE_DETAIL", closeRect, close)
        assertFalse(rig.module.snapshot().exEncounterFallbackApproved)
        rig.execute(close, 10_900)
        assertTrue(rig.module.snapshot().exEncounterFallbackApproved)
        assertFalse(rig.module.snapshot().exSlot3ProbePending)
        assertWait(rig.observe(challenge, 12_400))
        assertAction("BATTLE_START_CHALLENGE", challengeRect, rig.observe(challenge, 12_700))
    }

    @Test
    fun specialEquipmentCommitsTapsOnlyAfterExecutionAndSkipsUnchangedNormalTeam() {
        val rig = Rig()
        val input = teamInput(teamIds, equip = true)
        rig.begin()
        val entry = editor(teamIds, equipment = equipmentEntry())
        val first = rig.observe(entry, 100, input)
        assertAction("SPECIAL_EQUIPMENT_ENTRY", equipmentEntryRect, first)
        assertEquals(BattleSpecialEquipmentPhase.AWAIT_ENTRY, rig.module.snapshot().specialEquipmentPhase)
        rig.report(first, 100, AutomationActionResult.Rejected(requireNotNull(first.action), "test rejection"))
        assertEquals(BattleSpecialEquipmentPhase.AWAIT_ENTRY, rig.module.snapshot().specialEquipmentPhase)
        rig.execute(rig.observe(entry, 200, input), 200)
        assertEquals(BattleSpecialEquipmentPhase.AWAIT_MAIN_DIALOG, rig.module.snapshot().specialEquipmentPhase)

        val main = frame(UNKNOWN, equipment = mainEquipment())
        assertWait(rig.observe(main, 799, input))
        assertEquals(BattleSpecialEquipmentPhase.AWAIT_MAIN_DIALOG, rig.module.snapshot().specialEquipmentPhase)
        val auto = rig.observe(main, 800, input)
        assertAction("SPECIAL_EQUIPMENT_AUTO_BUTTON", equipmentAutoRect, auto)
        rig.execute(auto, 800)
        assertEquals(BattleSpecialEquipmentPhase.AWAIT_AUTO_DIALOG, rig.module.snapshot().specialEquipmentPhase)

        val confirm = rig.observe(frame(UNKNOWN, equipment = autoEquipment()), 1_400, input)
        assertAction("SPECIAL_EQUIPMENT_AUTO_CONFIRM", equipmentConfirmRect, confirm)
        rig.execute(confirm, 1_400)
        assertEquals(BattleSpecialEquipmentPhase.AWAIT_SECOND_MAIN_DIALOG, rig.module.snapshot().specialEquipmentPhase)
        val enabled = frame(UNKNOWN, equipment = mainEquipment(enabled = true))
        assertWait(rig.observe(enabled, 1_600, input))
        assertWait(rig.observe(enabled, 2_200, input))
        assertEquals(BattleSpecialEquipmentPhase.AWAIT_SECOND_MAIN_DIALOG, rig.module.snapshot().specialEquipmentPhase)
        val equip = rig.observe(enabled, 2_400, input)
        assertAction("SPECIAL_EQUIPMENT_EQUIP_CONFIRM", equipmentConfirmRect, equip)
        rig.execute(equip, 2_400)
        assertEquals(BattleSpecialEquipmentPhase.AWAIT_ACK_EQUIP, rig.module.snapshot().specialEquipmentPhase)
        assertWait(rig.observe(entry, 3_000, input))
        assertEquals(BattleSpecialEquipmentPhase.DONE, rig.module.snapshot().specialEquipmentPhase)
        val start = rig.observe(entry, 3_001, input)
        assertAction("START_BATTLE", startRect, start)
        rig.execute(start, 3_001)

        rig.observe(frame(BATTLE_IN_PROGRESS), 3_400, input)
        rig.observe(frame(UNKNOWN), 3_700, input)
        rig.observe(frame(BATTLE_RESULT), 4_000, input)
        assertEquals(BattleSpecialEquipmentPhase.DONE, rig.module.snapshot().specialEquipmentPhase)
        rig.observe(frame(BATTLE_RESULT), 4_300, input)
        assertEquals(BattleSpecialEquipmentPhase.INACTIVE, rig.module.snapshot().specialEquipmentPhase)
        rig.begin()
        val unchanged = rig.observe(entry, 5_000, input)
        assertAction("START_BATTLE", startRect, unchanged)
        assertEquals(BattleSpecialEquipmentPhase.INACTIVE, rig.module.snapshot().specialEquipmentPhase)
        assertEquals(labyrinthBattleTeamSignature(teamIds), rig.module.snapshot().lastNormalBattleTeamSignature)
    }

    @Test
    fun specialEquipmentCancelsWhenConfirmIsDisabledAndBoundsEachEscapeLayer() {
        val rig = Rig()
        val input = teamInput(teamIds, equip = true)
        reachSecondEquipmentDialog(rig, input)
        val disabled = frame(UNKNOWN, equipment = mainEquipment(enabled = false))
        assertWait(rig.observe(disabled, 2_200, input))
        val cancel = rig.observe(disabled, 2_500, input)
        assertAction("SPECIAL_EQUIPMENT_CANCEL", equipmentCancelRect, cancel)
        rig.execute(cancel, 2_500)
        assertEquals(BattleSpecialEquipmentPhase.AWAIT_ACK_CANCEL, rig.module.snapshot().specialEquipmentPhase)
        val editor = editor(teamIds, equipment = equipmentEntry())
        assertWait(rig.observe(editor, 2_600, input))
        assertEquals(BattleSpecialEquipmentPhase.DONE, rig.module.snapshot().specialEquipmentPhase)
        assertWait(rig.observe(editor, 3_099, input))
        assertAction("START_BATTLE", startRect, rig.observe(editor, 3_100, input))

        val escape = Rig()
        val entry = editor(teamIds, equipment = equipmentEntry())
        escape.begin()
        escape.execute(escape.observe(entry, 100, input), 100)
        val upper = frame(UNKNOWN, equipment = autoEquipment())
        val upperCancel = escape.observe(upper, 8_100, input)
        assertAction("SPECIAL_EQUIPMENT_CANCEL", upperCancelRect, upperCancel)
        escape.execute(upperCancel, 8_100)
        assertWait(escape.observe(upper, 8_700, input))
        val lower = frame(UNKNOWN, equipment = mainEquipment())
        val lowerCancel = escape.observe(lower, 9_000, input)
        assertAction("SPECIAL_EQUIPMENT_CANCEL", equipmentCancelRect, lowerCancel)
        escape.execute(lowerCancel, 9_000)
        assertWait(escape.observe(lower, 16_999, input))
        assertError("SPECIAL_EQUIPMENT_DIALOG_BLOCKED", KernelErrorCategory.EVIDENCE, escape.observe(lower, 17_000, input))

        val noEscape = Rig()
        noEscape.begin()
        noEscape.execute(noEscape.observe(entry, 100, input), 100)
        val missingCancel = frame(UNKNOWN, equipment = mainEquipment().copy(cancelButtonRect = null))
        assertError("SPECIAL_EQUIPMENT_DIALOG_BLOCKED", KernelErrorCategory.EVIDENCE,
            noEscape.observe(missingCancel, 8_100, input))
    }

    @Test
    fun threeBossTeamsUseFiveMinuteBaseAndFourteenMinuteCeiling() {
        val still = Rig()
        still.begin(LabyrinthCombatKind.BOSS, plannedTeams = 3)
        assertWait(still.observe(frame(BATTLE_IN_PROGRESS, hud = 1), 0))
        assertWait(still.observe(frame(BATTLE_IN_PROGRESS, hud = 1), 299_999))
        assertError("BATTLE_WAIT_TIMEOUT", KernelErrorCategory.TIMEOUT,
            still.observe(frame(BATTLE_IN_PROGRESS, hud = 1), 300_000))

        val advances = Rig()
        advances.begin(LabyrinthCombatKind.BOSS, plannedTeams = 3)
        assertWait(advances.observe(frame(BATTLE_IN_PROGRESS, hud = 1), 0))
        assertWait(advances.observe(frame(BATTLE_IN_PROGRESS, hud = 2), 290_000))
        assertWait(advances.observe(frame(UNKNOWN), 349_999))
        assertError("BATTLE_WAIT_TIMEOUT", KernelErrorCategory.TIMEOUT,
            advances.observe(frame(BATTLE_IN_PROGRESS, hud = 2), 350_000))

        val capped = Rig()
        capped.begin(LabyrinthCombatKind.BOSS, plannedTeams = 3)
        assertWait(capped.observe(frame(BATTLE_IN_PROGRESS, hud = 1), 0))
        for (at in 290_000L..790_000L step 50_000L) {
            assertWait(capped.observe(frame(BATTLE_IN_PROGRESS, hud = at), at))
        }
        assertWait(capped.observe(frame(BATTLE_IN_PROGRESS, hud = 839_999L), 839_999))
        assertError("BATTLE_WAIT_TIMEOUT", KernelErrorCategory.TIMEOUT,
            capped.observe(frame(BATTLE_IN_PROGRESS, hud = 840_000L), 840_000))
    }

    @Test
    fun bossSettlementAnchorTakesPriorityOnUnknownPage() {
        val rig = Rig()
        rig.begin(LabyrinthCombatKind.BOSS)
        assertWait(rig.observe(frame(BATTLE_IN_PROGRESS, hud = 1), 0))
        val result = frame(UNKNOWN, anchors = anchors(EntryAnchorId.BATTLE_RESULT_BOSS_SUMMARY_NEXT_BUTTON to resultRect))
        assertAction("BOSS_SETTLEMENT_NEXT", resultRect, rig.observe(result, 300_000))
    }

    @Test
    fun bossPreparationClearsTabsWaitsForTwoFreshFramesAndRecoversManualSwitch() {
        val rig = Rig()
        rig.begin(LabyrinthCombatKind.BOSS)
        val populated = editor(teamIds.take(1), bossIndex = 1)
        val clearMember = rig.observe(populated, 100)
        assertAction("RESET_BOSS_DESELECT_CHARACTER",
            requireNotNull(populated.battleTeamSelection).selectedCharacters.single().screenRect, clearMember)
        rig.execute(clearMember, 100)
        assertWait(rig.observe(populated, 700))

        val team1 = editor(emptyList(), bossIndex = 1)
        val team2 = editor(emptyList(), bossIndex = 2)
        val team3 = editor(emptyList(), bossIndex = 3)
        val to2 = rig.observe(team1, 1_000)
        assertAction("RESET_BOSS_SWITCH_TEAM", bossTabs[1], to2)
        rig.execute(to2, 1_000)
        assertEquals(LabyrinthBossEditorPreparationStage.TEAM_2, rig.module.snapshot().bossEditorPreparationStage)
        assertWait(rig.observe(team2, 1_300))
        assertEquals(1, requireNotNull(rig.module.snapshot().bossTabDiagnostic).ackFrames)
        val to3 = rig.observe(team2, 1_600)
        assertAction("RESET_BOSS_SWITCH_TEAM", bossTabs[2], to3)
        assertEquals(2, rig.module.snapshot().combatContext?.teamIndex)
        rig.execute(to3, 1_600)
        assertWait(rig.observe(team3, 1_900))
        val to1 = rig.observe(team3, 2_200)
        assertAction("RESET_BOSS_SWITCH_TEAM", bossTabs[0], to1)
        rig.execute(to1, 2_200)
        assertWait(rig.observe(team1, 2_500))
        assertWait(rig.observe(team1, 2_800))
        assertEquals(LabyrinthBossEditorPreparationStage.COMPLETE, rig.module.snapshot().bossEditorPreparationStage)
        assertEquals(1, rig.module.snapshot().combatContext?.teamIndex)
        assertTrue(rig.module.snapshot().committedCharacterIds.isEmpty())

        assertWait(rig.observe(team2, 3_100))
        assertEquals(LabyrinthBossEditorPreparationStage.TEAM_1, rig.module.snapshot().bossEditorPreparationStage)
        assertAction("RESET_BOSS_SWITCH_TEAM", bossTabs[0], rig.observe(team2, 3_400))
    }

    @Test
    fun unconfirmedStartRestoresPreviousTeamAndRetryStateAndEventuallyStops() {
        val rig = Rig(BattleDomainConfig(stableFrames = 1, maxUnconfirmedActions = 1))
        rig.begin()
        rig.start(teamIds, 100)
        val failed = failure(LabyrinthBattleFailureLayout.THREE_BUTTON)
        rig.execute(rig.failure(failed, 1_600, BattleRetryPolicyContext()), 1_600)
        val replacement = teamIds.dropLast(1) + "r6"
        val editor = editor(replacement)
        val input = teamInput(replacement)
        rig.start(replacement, 3_100, input)
        assertFalse(rig.module.snapshot().awaitingRetryBattleAfterFailure)
        assertWait(rig.observe(editor, 7_099, input))
        assertWait(rig.observe(editor, 7_100, input))
        with(rig.module.snapshot()) {
            assertTrue(awaitingRetryBattleAfterFailure)
            assertEquals(labyrinthBattleTeamSignature(teamIds), lastNormalBattleTeamSignature)
            assertTrue(currentTeamSignatures.isEmpty())
            assertTrue(committedCharacterIds.isEmpty())
            assertEquals(1, unconfirmedTeamActions)
        }
        rig.start(replacement, 7_400, input)
        assertError("BATTLE_TEAM_ACTION_UNCONFIRMED", KernelErrorCategory.EXECUTION, rig.observe(editor, 11_400, input))
        assertTrue(rig.module.snapshot().committedCharacterIds.isEmpty())
        assertTrue(rig.module.snapshot().awaitingRetryBattleAfterFailure)
        assertEquals(2, rig.module.snapshot().unconfirmedTeamActions)
    }

    @Test
    fun bossSingleTeamSwitchesToMultiOnlyAfterRetryExecutes() {
        val rig = Rig(BattleDomainConfig(stableFrames = 1))
        rig.module.beginBattle(LabyrinthCombatContext(LabyrinthCombatKind.BOSS), bossMode = LabyrinthBossTeamMode.SINGLE_TEAM)
        val policy = BattleRetryPolicyContext(singleBossRetryCountBeforeMulti = 1)
        val failed = failure(LabyrinthBattleFailureLayout.THREE_BUTTON)
        val firstRetry = rig.failure(failed, 100, policy)
        assertAction("BATTLE_RETRY", retryRect, firstRetry)
        rig.execute(firstRetry, 100)
        assertWait(rig.failure(failed, 1_600, policy))
        assertWait(rig.observe(frame(BATTLE_IN_PROGRESS, hud = 1), 1_900))
        val switch = rig.failure(failed, 2_200, policy)
        assertAction("BATTLE_RETRY_SWITCH_MULTI", retryRect, switch)
        assertEquals(LabyrinthBossTeamMode.SINGLE_TEAM, rig.module.snapshot().bossTeamMode)
        rig.execute(switch, 2_200)
        assertEquals(LabyrinthBossTeamMode.MULTI_TEAM, rig.module.snapshot().bossTeamMode)
        assertEquals(0, rig.module.snapshot().battleRetryCount)
        assertTrue(rig.module.snapshot().awaitingRetryBattleAfterFailure)
    }

    @Test
    fun relicRevivalPreservesRetryCountAndBothSetsOfTeamSignaturesAtTheLimit() {
        val rig = Rig(BattleDomainConfig(stableFrames = 1))
        rig.begin()
        val policy = BattleRetryPolicyContext(batchOwnedRun = true, rerollAfterThreeFailures = true)
        val retry = failure(LabyrinthBattleFailureLayout.THREE_BUTTON)
        val revive = failure(LabyrinthBattleFailureLayout.TWO_BUTTON)
        val secondTeam = teamIds.dropLast(1) + "r6"
        val thirdTeam = teamIds.dropLast(1) + "r7"
        rig.start(teamIds, 100)
        val firstRetry = rig.failure(retry, 1_600, policy)
        assertAction("BATTLE_RETRY", retryRect, firstRetry)
        assertEquals(0, rig.module.snapshot().battleRetryCount)
        rig.capture(firstRetry, 1_600)
        assertNotNull(rig.module.onBattleRetryExecuted())
        with(rig.module.snapshot()) {
            assertEquals(1, battleRetryCount)
            assertTrue(awaitingRetryBattleAfterFailure)
            assertEquals(setOf(labyrinthBattleTeamSignature(teamIds)), failedTeamSignatures)
            assertTrue(currentTeamSignatures.isEmpty())
            assertTrue(committedCharacterIds.isEmpty())
        }
        assertWait(rig.failure(retry, 1_900, policy))
        assertEquals(1, rig.module.snapshot().battleRetryCount)
        rig.start(secondTeam, 3_100)
        assertFalse(rig.module.snapshot().awaitingRetryBattleAfterFailure)
        rig.execute(rig.failure(retry, 4_600, policy), 4_600)
        rig.start(thirdTeam, 6_100)
        val atLimit = rig.module.snapshot()
        assertEquals(2, atLimit.battleRetryCount)
        assertEquals(setOf(labyrinthBattleTeamSignature(teamIds), labyrinthBattleTeamSignature(secondTeam)), atLimit.failedTeamSignatures)
        assertEquals(setOf(labyrinthBattleTeamSignature(thirdTeam)), atLimit.currentTeamSignatures)

        val next = rig.failure(revive, 7_600, policy)
        assertAction("BATTLE_FAILURE_NEXT", retryRect, next)
        rig.capture(next, 7_600)
        assertNotNull(rig.module.onBattleFailureNextExecuted())
        assertEquals(atLimit, rig.module.snapshot())
        val terminal = rig.failure(retry, 9_100, policy)
        assertFalse((terminal.decision as KernelDecision.FinishRun).cleared)
        assertTrue(terminal.shouldReroll)
        assertNull(terminal.permitToken)
    }

    @Test
    fun noReplacementForTheFailedFivePersonTeamStopsOnFirstStableFailureFrame() {
        val profiles = teamIds.mapIndexed { index, id ->
            LabyrinthRoleProfile(id, id,
                roleClass = if (index == 0) "掩护者" else "攻击者",
                attribute = LabyrinthCharacterAttribute.LIGHT, damageType = LabyrinthRoleDamageType.PHYSICAL,
                position = 100 + index, userScore = 80.0, physicalDamagePotential = 70.0, magicDamagePotential = 0.0,
                functions = LabyrinthRoleFunctions(reliableVanguard = if (index == 0) 100.0 else 0.0),
            )
        }.associateBy { it.characterId }
        val scoring = LabyrinthTeamScoringConfig(attributeDamageBonus = mapOf(1 to 0.0, 2 to 0.08, 3 to 0.16, 4 to 0.25, 5 to 0.75))
        val search = LabyrinthTeamPlanSearcher(
            LabyrinthTeamOptimizer(LabyrinthTeamScorer(scoring), searchThreads = 1),
            LabyrinthTeamPlanSearchConfig(
                oneTeamKillScore = 0.0, mainTeamScore = 0.0, cleanupTeamScore = 0.0,
                mainPlusCleanupCombinedScore = 0.0, stableTeamScore = 0.0, fallbackTeamScore = 0.0,
                threeTeamCombinedScore = 0.0,
            ),
        )
        val planner = LabyrinthBattleTeamRecommendationPlanner(profiles, search)
        val available = planner.initialRecommendation(teamIds, LabyrinthRoleDecisionContext(defenseMarkStacks = 4))
            as LabyrinthBattleTeamRecommendationResult.Ready
        assertEquals(teamIds.toSet(), available.recommendation.members.map { it.characterId }.toSet())
        assertTrue(planner.retryRecommendation(teamIds, LabyrinthRoleDecisionContext(defenseMarkStacks = 4),
            setOf(labyrinthBattleTeamSignature(teamIds))) is LabyrinthBattleTeamRecommendationResult.Unavailable)

        val retry = failure(LabyrinthBattleFailureLayout.THREE_BUTTON)
        for (batch in listOf(false, true)) {
            val rig = Rig()
            rig.begin()
            rig.start(teamIds, 100, BattleFrameContext(recommendation = available))
            val policy = BattleRetryPolicyContext(batchOwnedRun = batch, rerollAfterThreeFailures = false,
                acquiredCharacterIds = teamIds, defenseMarkStacks = 4)
            assertWait(rig.failure(retry, 500, policy, planner))
            val failed = rig.failure(retry, 800, policy, planner)
            if (batch) {
                assertFalse((failed.decision as KernelDecision.FinishRun).cleared)
                assertTrue(failed.shouldReroll)
                assertTrue(failed.message.contains("无可替换"))
            } else {
                assertError("NO_RECOVERABLE_RETRY_TEAM", KernelErrorCategory.POLICY, failed)
                assertFalse(failed.shouldReroll)
            }
            assertNull(failed.action)
            assertNull(failed.permitToken)
            assertEquals(0, rig.module.snapshot().battleRetryCount)
            assertEquals(setOf(labyrinthBattleTeamSignature(teamIds)), rig.module.snapshot().currentTeamSignatures)
            assertTrue(rig.module.snapshot().failedTeamSignatures.isEmpty())
        }
    }

    @Test
    fun nestedEventBattleTracesVetoPermitExecutionSettlementAndExit() {
        val rig = Rig()
        val root = rig.runtime.resetSession("arena-replay")
        val parent = rig.stack.push(KernelEncounterFrame("arena", KernelEncounterKind.EVENT, label = "斗技场"))
        val parentEvent = rig.runtime.emit(KernelDomain.EVENT, KernelEventKinds.STATE_REDUCED,
            StateReducedPayload("EVENT_CHOICE", parent.id), root, "ARENA_EVENT_ENTERED")
        assertEquals(1, rig.stack.depth())
        val battle = rig.module.enterNestedBattle(parent.id, LabyrinthCombatKind.NORMAL, 2)
        assertEquals(2, rig.stack.depth())
        assertEquals(battle, rig.stack.peek())
        assertEquals(parent.id, battle.parentId)
        assertEquals(KernelEncounterKind.BATTLE, battle.kind)
        assertEquals(2, rig.module.snapshot().activeArea)
        assertEquals(battle.id, rig.module.snapshot().activeNestedEncounterId)

        rig.runtime.registerHook(object : KernelEventHook {
            override val id = "block-challenge-once"
            override val domains = setOf(KernelDomain.BATTLE)
            override val kinds = setOf(KernelEventKinds.EVENT_DECISION_PROPOSED)
            override fun handle(event: KernelEvent, ctx: KernelRuntime): KernelHookVerdict =
                if (event.reasonCode == "BATTLE_START_CHALLENGE") KernelHookVerdict.Veto("人工暂缓")
                else KernelHookVerdict.Continue
        })
        val challenge = frame(BATTLE_CHALLENGE, ex = LabyrinthExEncounterObservation(challengeDifficultyResolved = true),
            anchors = anchors(EntryAnchorId.BATTLE_CHALLENGE_BUTTON to challengeRect))
        assertWait(rig.observe(challenge, 100))
        val veto = rig.observe(challenge, 400)
        assertEquals(KernelDecision.Wait("人工暂缓"), veto.decision)
        assertNull(veto.permitToken)
        rig.runtime.unregisterHook("block-challenge-once")
        val stale = rig.observe(challenge, 700)
        val authorized = rig.observe(challenge, 1_000)
        assertFalse(requireNotNull(stale.permitToken).isCurrent(1_000))
        assertNull(rig.module.onActionDispatched(stale, 1_000))
        assertAction("BATTLE_START_CHALLENGE", challengeRect, authorized)
        rig.execute(authorized, 1_000)
        rig.start(teamIds, 1_600)
        assertWait(rig.observe(frame(BATTLE_IN_PROGRESS, hud = 1), 1_900))
        val result = frame(BATTLE_RESULT, anchors = anchors(EntryAnchorId.BATTLE_RESULT_NEXT_BUTTON to resultRect))
        assertWait(rig.observe(result, 2_200))
        val next = rig.observe(result, 2_500)
        assertAction("BATTLE_RESULT_NEXT", resultRect, next)
        val receipt = rig.execute(next, 2_500)
        assertFalse(requireNotNull(next.permitToken).isCurrent(2_500))

        assertEquals(battle, rig.module.exitNestedBattleIfActive("cleared"))
        assertEquals(1, rig.stack.depth())
        assertEquals(parent, rig.stack.peek())
        assertNull(rig.module.snapshot().activeNestedEncounterId)
        assertNull(rig.module.exitNestedBattleIfActive("duplicate"))
        val exit = rig.runtime.log.filter(domain = KernelDomain.BATTLE, reasonCode = "BATTLE_EXITED").single()
        assertEquals("cleared", (exit.payload as StateReducedPayload).detail["outcome"])
        val chain = rig.runtime.log.causalChain(exit.eventSeq)
        assertEquals(root, chain.first())
        assertTrue(parentEvent in chain)
        assertTrue(receipt in chain)
        assertTrue(chain.zipWithNext().all { (a, b) -> b.causeEventSeq == a.eventSeq })
        assertTrue(chain.map { it.kind }.containsAll(listOf(
            KernelEventKinds.FRAME_OBSERVED, KernelEventKinds.STATE_REDUCED, KernelEventKinds.EVENT_RULE_EVALUATED,
            KernelEventKinds.EVENT_DECISION_PROPOSED, KernelEventKinds.PERMIT_ISSUED, KernelEventKinds.PERMIT_REVOKED,
            KernelEventKinds.ACTION_DISPATCHED, KernelEventKinds.ACTION_RECEIPT_OBSERVED, KernelEventKinds.HOOK_INVOKED,
        )))
        assertEquals(listOf("BATTLE_START_CHALLENGE", "START_BATTLE", "BATTLE_RESULT_NEXT"),
            chain.filter { it.kind == KernelEventKinds.ACTION_DISPATCHED }.map { it.reasonCode })
        assertEquals(battle.id, (chain.single { it.reasonCode == "BATTLE_ENTERED" }.payload as StateReducedPayload).encounterId)
    }

    private fun reachSecondEquipmentDialog(rig: Rig, input: BattleFrameContext) {
        rig.begin()
        rig.execute(rig.observe(editor(teamIds, equipment = equipmentEntry()), 100, input), 100)
        rig.execute(rig.observe(frame(UNKNOWN, equipment = mainEquipment()), 700, input), 700)
        rig.execute(rig.observe(frame(UNKNOWN, equipment = autoEquipment()), 1_300, input), 1_300)
        assertEquals(BattleSpecialEquipmentPhase.AWAIT_SECOND_MAIN_DIALOG, rig.module.snapshot().specialEquipmentPhase)
    }

    private inner class Rig(config: BattleDomainConfig = BattleDomainConfig()) {
        private var now = 0L
        private var frameSeq = 0L
        val runtime = KernelRuntime(clock = { now })
        val stack = KernelEncounterStack(runtime)
        val module = BattleDomainModule(runtime, stack, config).also(runtime::registerModule)

        fun begin(kind: LabyrinthCombatKind = LabyrinthCombatKind.NORMAL, plannedTeams: Int? = null) {
            module.beginBattle(LabyrinthCombatContext(kind), plannedBossTeams = plannedTeams)
        }

        fun observe(frame: LabyrinthEntryFrameResult, at: Long, input: BattleFrameContext = BattleFrameContext()): BattleStepOutcome {
            now = at
            return module.evaluateFrame(frame, at, input.copy(frameSeq = ++frameSeq))
        }

        fun failure(frame: LabyrinthEntryFrameResult, at: Long, policy: BattleRetryPolicyContext,
                    planner: LabyrinthBattleTeamRecommendationPlanner? = null): BattleStepOutcome {
            now = at
            return module.evaluateBattleFailureFrame(frame, at, policy, planner)
        }

        fun start(ids: List<String>, at: Long, input: BattleFrameContext = teamInput(ids)): KernelEvent {
            val outcome = observe(editor(ids), at, input)
            assertAction("START_BATTLE", startRect, outcome)
            return execute(outcome, at)
        }

        fun capture(outcome: BattleStepOutcome, at: Long) {
            now = at
            assertTrue(outcome.message, requireNotNull(module.onActionDispatched(outcome, at)).isCurrent(at))
        }

        fun execute(outcome: BattleStepOutcome, at: Long): KernelEvent =
            report(outcome, at, AutomationActionResult.Executed(requireNotNull(outcome.action) { outcome.message }))

        fun report(outcome: BattleStepOutcome, at: Long, result: AutomationActionResult): KernelEvent {
            capture(outcome, at)
            return requireNotNull(module.onActionReceipt(outcome, result, at))
        }
    }

    private fun assertWait(outcome: BattleStepOutcome) {
        assertTrue(outcome.message, outcome.decision is KernelDecision.Wait)
        assertNull(outcome.action)
        assertNull(outcome.permitToken)
    }

    private fun assertError(code: String, category: KernelErrorCategory, outcome: BattleStepOutcome) {
        assertTrue(outcome.message, outcome.decision is KernelDecision.NeedEvidence)
        val error = (outcome.decision as KernelDecision.NeedEvidence).error
        assertEquals(code, error.code)
        assertEquals(category, error.category)
        assertNull(outcome.permitToken)
        assertNull(outcome.action)
    }

    private fun assertAction(kind: String, rect: EntryPixelRect, outcome: BattleStepOutcome) {
        assertTrue(outcome.message, outcome.decision is KernelDecision.Act)
        val act = outcome.decision as KernelDecision.Act
        assertEquals("tap", act.actionKind)
        assertEquals(kind, outcome.actionKind)
        assertEquals(kind, act.reason)
        assertEquals(outcome.permitKey?.targetId ?: kind, act.targetId)
        assertEquals(rect, act.rect)
        assertEquals(rect, outcome.permitKey?.rect)
        assertEquals(AutomationAction.Tap(ScreenPoint(rect.left + rect.width / 2f, rect.top + rect.height / 2f)), outcome.action)
        assertNotNull(outcome.permitToken)
    }

    private fun frame(
        page: LabyrinthEntryPageState,
        anchors: Map<String, EntryAnchorMatch> = emptyMap(),
        ex: LabyrinthExEncounterObservation? = null,
        team: LabyrinthBattleTeamObservation? = null,
        equipment: LabyrinthSpecialEquipmentObservation? = null,
        failure: LabyrinthBattleFailureObservation? = null,
        hud: Long? = null,
    ) = LabyrinthEntryFrameResult(
        LabyrinthEntryPageObservation(page, 1.0, mapOf(page to 1.0), LabyrinthAnchorScores(emptyMap())),
        emptyList(), 0, anchorMatches = anchors, battleTeamSelection = team, exEncounter = ex,
        specialEquipment = equipment, battleFailure = failure, frameWidth = 1920, frameHeight = 1080, battleHudDigest = hud,
    )

    private fun anchors(vararg items: Pair<String, EntryPixelRect>): Map<String, EntryAnchorMatch> =
        items.associate { it.first to EntryAnchorMatch(0.95, it.second) }

    private fun editor(
        ids: List<String>,
        equipment: LabyrinthSpecialEquipmentObservation? = null,
        bossIndex: Int? = null,
    ): LabyrinthEntryFrameResult {
        val selected = ids.mapIndexed { index, id ->
            LabyrinthBattleCharacterMatch("member-$index", id, id, "test", 0.99,
                EntryPixelRect(440 + index * 100, 800, 70, 70), selected = true)
        }
        val roster = LabyrinthBattleTeamObservation(LabyrinthBattleElementFilter.ALL, emptyList(), selected, selected,
            LabyrinthBattleScrollbarObservation(EntryPixelRect(1850, 250, 20, 420), null, false, false, 0.0),
            bossTeamIndex = bossIndex, bossTeamTabs = if (bossIndex != null) bossTabs else emptyList(),
        )
        return frame(BATTLE_TEAM_SELECTION, anchors(EntryAnchorId.BATTLE_TEAM_START_BUTTON to startRect),
            team = roster, equipment = equipment)
    }

    private fun teamInput(ids: List<String>, equip: Boolean = false): BattleFrameContext {
        val members = ids.map { LabyrinthRecommendedTeamMember(it, it) }
        val recommendation = LabyrinthBattleTeamRecommendation(members, LabyrinthTeamDamageType.PHYSICAL, 80.0,
            members.first(), null, emptyList(), 0, 1)
        return BattleFrameContext(recommendation = LabyrinthBattleTeamRecommendationResult.Ready(recommendation),
            settings = LabyrinthStrategySettings(
                specialEquipmentMode = if (equip) LabyrinthSpecialEquipmentMode.AUTO_EQUIP else LabyrinthSpecialEquipmentMode.OFF,
                specialEquipmentKinds = setOf(LabyrinthCombatKind.NORMAL),
            ),
        )
    }

    private fun failure(layout: LabyrinthBattleFailureLayout): LabyrinthEntryFrameResult =
        frame(BATTLE_FAILED, failure = LabyrinthBattleFailureObservation(0.95,
            endButtonRect = EntryPixelRect(250, 900, 150, 70).takeIf { layout == LabyrinthBattleFailureLayout.THREE_BUTTON },
            retryButtonRect = retryRect, layout = layout))

    private fun equipmentEntry() = LabyrinthSpecialEquipmentObservation(null, 0.95, entryButtonRect = equipmentEntryRect)

    private fun mainEquipment(enabled: Boolean? = null) = LabyrinthSpecialEquipmentObservation(
        LabyrinthSpecialEquipmentStage.MAIN_DIALOG, 0.95, autoButtonRect = equipmentAutoRect,
        confirmButtonRect = equipmentConfirmRect, cancelButtonRect = equipmentCancelRect, equipConfirmEnabled = enabled,
    )

    private fun autoEquipment() = LabyrinthSpecialEquipmentObservation(
        LabyrinthSpecialEquipmentStage.AUTO_DIALOG, 0.95,
        confirmButtonRect = equipmentConfirmRect, cancelButtonRect = upperCancelRect,
    )

    private companion object {
        val teamIds = (1..5).map { "r$it" }
        val challengeRect = EntryPixelRect(1500, 860, 160, 70)
        val startRect = EntryPixelRect(1500, 910, 160, 70)
        val retryRect = EntryPixelRect(1510, 930, 160, 70)
        val resultRect = EntryPixelRect(1580, 930, 160, 70)
        val probeRect = EntryPixelRect(1400, 550, 100, 60)
        val closeRect = EntryPixelRect(1680, 130, 60, 60)
        val equipmentEntryRect = EntryPixelRect(1160, 900, 70, 70)
        val equipmentAutoRect = EntryPixelRect(790, 900, 160, 70)
        val equipmentConfirmRect = EntryPixelRect(1500, 930, 160, 70)
        val equipmentCancelRect = EntryPixelRect(250, 925, 160, 70)
        val upperCancelRect = EntryPixelRect(590, 925, 160, 70)
        val bossTabs = (0..2).map { EntryPixelRect(510 + it * 175, 260, 150, 60) }
    }
}
