package com.landosol.toolbox.labyrinth

import com.landosol.toolbox.labyrinth.vision.EntryPixelRect
import com.landosol.toolbox.labyrinth.vision.LabyrinthAnchorScores
import com.landosol.toolbox.labyrinth.vision.LabyrinthBattleCharacterMatch
import com.landosol.toolbox.labyrinth.vision.LabyrinthBattleElementFilter
import com.landosol.toolbox.labyrinth.vision.LabyrinthBattleScrollbarObservation
import com.landosol.toolbox.labyrinth.vision.LabyrinthBattleTeamObservation
import com.landosol.toolbox.labyrinth.vision.LabyrinthBattleTeamRecognitionState
import com.landosol.toolbox.labyrinth.vision.LabyrinthCharacterMatch
import com.landosol.toolbox.labyrinth.vision.LabyrinthEntryFrameResult
import com.landosol.toolbox.labyrinth.vision.LabyrinthEntryPageObservation
import com.landosol.toolbox.labyrinth.vision.LabyrinthEntryPageState
import com.landosol.toolbox.labyrinth.vision.LabyrinthEventOcrDiagnostics
import com.landosol.toolbox.labyrinth.vision.EntryAnchorId
import com.landosol.toolbox.labyrinth.vision.EntryAnchorMatch
import com.landosol.toolbox.labyrinth.node.LabyrinthNodeTypes
import com.landosol.toolbox.labyrinth.node.NodeSessionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Assert.assertFalse

class LabyrinthPageRuntimePolicyTest {
    @Test fun `shop joined popup owns reward flow without opening invitation or purchase memory`() {
        val frame = result(LabyrinthEntryPageState.CHARACTER_JOINED, anchorScores = mapOf(
            EntryAnchorId.SHOP_TITLE to 0.9, EntryAnchorId.SHOP_CLOSE to 0.8))
        assertTrue(labyrinthShopJoinedRewardOwnsRoute(frame, Long.MIN_VALUE, 50_000))
    }

    @Test fun `confirmed imprint purchase owns direct joined popup across purchase cleanup`() {
        val frame = result(LabyrinthEntryPageState.CHARACTER_JOINED)
        assertTrue(labyrinthShopJoinedRewardOwnsRoute(frame, 1_000, 10_000))
        assertFalse(labyrinthShopJoinedRewardOwnsRoute(frame, 1_000, 130_000))
        assertFalse(labyrinthShopJoinedRewardOwnsRoute(frame, Long.MIN_VALUE, 10_000))
        assertFalse(labyrinthShopJoinedRewardOwnsRoute(result(LabyrinthEntryPageState.INITIAL_CHARACTER_SELECTION), 1_000, 10_000))
    }

    @Test fun `one shop lookalike anchor cannot take over a fresh invitation`() {
        val frame = result(LabyrinthEntryPageState.CHARACTER_JOINED, anchorScores = mapOf(EntryAnchorId.SHOP_TITLE to 0.99))
        assertFalse(labyrinthShopJoinedRewardOwnsRoute(frame, Long.MIN_VALUE, 10_000))
    }
    @Test
    fun `challenge button waits for difficulty and accepts only explicit EX guide fallback`() {
        val normal = LabyrinthCombatContext(LabyrinthCombatKind.NORMAL)
        val ex = LabyrinthCombatContext(LabyrinthCombatKind.EX)
        val boss = LabyrinthCombatContext(LabyrinthCombatKind.BOSS)

        assertEquals(false, labyrinthBattleChallengeCanAutoStart(normal, false, false, false))
        assertTrue(labyrinthBattleChallengeCanAutoStart(normal, true, false, false))

        // EVENT -> EX race: even before combatContext is promoted, visible 极难 evidence blocks
        // the generic challenge path until the encounter guide has been resolved.
        assertEquals(false, labyrinthBattleChallengeCanAutoStart(null, true, true, false))
        assertTrue(labyrinthBattleChallengeCanAutoStart(ex, false, true, true))
        assertEquals(false, labyrinthBattleChallengeCanAutoStart(ex, true, true, false))
        // A guide-less EX may continue only after the automation-owned details probe has closed
        // the modal and recorded an explicit fallback approval. Generic unknown EX frames remain
        // blocked by the preceding assertion.
        assertTrue(
            labyrinthBattleChallengeCanAutoStart(
                ex,
                challengeDifficultyResolved = true,
                extremeChallenge = true,
                exEncounterResolved = false,
                exGuideFallbackApproved = true,
            ),
        )

        // Route-known Bosses keep their existing direct challenge behavior.
        assertTrue(labyrinthBattleChallengeCanAutoStart(boss, false, false, false))
    }

    @Test
    fun `unrecoverable orphan movement modal is dismissed within bounds instead of searched around`() {
        fun decide(
            stable: Int = 2, attempts: Int = 0, lastAt: Long = Long.MIN_VALUE, now: Long = 10_000,
            pending: Boolean = false, recoverable: Boolean = false,
            page: LabyrinthEntryPageState = LabyrinthEntryPageState.NODE_SELECTION,
        ) = labyrinthShouldDismissOrphanNodeMoveConfirmation(
            pageState = page, hasPendingNodeTransition = pending, canRecover = recoverable,
            stableFrames = stable, dismissAttempts = attempts, lastDismissAt = lastAt, now = now,
        )
        // This is the 2026-09-15 20:33 bundle: dialog open, three reachable successors, no pending tap.
        assertTrue(decide())
        assertTrue(decide(page = LabyrinthEntryPageState.UNKNOWN))
        // One frame is not enough: the detector is a colour test and a transition can fake it.
        assertFalse(decide(stable = 1))
        // Anything the pending/recovery paths own stays theirs.
        assertFalse(decide(pending = true))
        assertFalse(decide(recoverable = true))
        // Bounded: three taps, two seconds apart, never on a page the map does not own.
        assertFalse(decide(attempts = MAX_ORPHAN_MOVE_CONFIRMATION_DISMISS_ATTEMPTS))
        assertFalse(decide(attempts = 1, lastAt = 9_000))
        assertTrue(decide(attempts = 1, lastAt = 7_000))
        assertFalse(decide(page = LabyrinthEntryPageState.SHOP_EXIT_CONFIRMATION))
        // Shop exit dialog hides the shop title so the page reads UNKNOWN, but the shop chrome
        // around it is still visible. That is the shop handler's dialog, never an orphan.
        assertFalse(
            labyrinthShouldDismissOrphanNodeMoveConfirmation(
                pageState = LabyrinthEntryPageState.UNKNOWN, hasPendingNodeTransition = false,
                canRecover = false, stableFrames = 2, dismissAttempts = 0,
                lastDismissAt = Long.MIN_VALUE, now = 10_000, shopBackgroundVisible = true,
            ),
        )
    }

    @Test fun `shop background is two or more fixed shop anchors regardless of page state`() {
        val exitDialog = result(LabyrinthEntryPageState.UNKNOWN, anchorScores = mapOf(
            EntryAnchorId.SHOP_REFRESH_BUTTON to 0.9, EntryAnchorId.SHOP_CLOSE to 0.8))
        assertTrue(labyrinthShopBackgroundVisible(exitDialog))
        val map = result(LabyrinthEntryPageState.NODE_SELECTION, anchorScores = mapOf(EntryAnchorId.SHOP_CLOSE to 0.9))
        assertFalse(labyrinthShopBackgroundVisible(map))
    }

    @Test
    fun `orphan movement modal can recover only from strong unique route evidence`() {
        assertTrue(
            labyrinthCanRecoverOrphanNodeMoveConfirmation(
                pageState = LabyrinthEntryPageState.NODE_SELECTION,
                confirmationConfidence = 0.91,
                hasUniqueReachableRouteTarget = true,
            ),
        )
        assertEquals(
            false,
            labyrinthCanRecoverOrphanNodeMoveConfirmation(
                pageState = LabyrinthEntryPageState.NODE_SELECTION,
                confirmationConfidence = 0.84,
                hasUniqueReachableRouteTarget = true,
            ),
        )
        assertEquals(
            false,
            labyrinthCanRecoverOrphanNodeMoveConfirmation(
                pageState = LabyrinthEntryPageState.NODE_SELECTION,
                confirmationConfidence = 0.95,
                hasUniqueReachableRouteTarget = false,
            ),
        )
        assertEquals(
            false,
            labyrinthCanRecoverOrphanNodeMoveConfirmation(
                pageState = LabyrinthEntryPageState.SHOP_EXIT_CONFIRMATION,
                confirmationConfidence = 0.95,
                hasUniqueReachableRouteTarget = true,
            ),
        )
        // 2026-09-18 live: the shop exit dialog fading in classified UNKNOWN with the shop
        // chrome still visible and was adopted as a movement confirmation for Boss#30701.
        assertFalse(
            labyrinthCanRecoverOrphanNodeMoveConfirmation(
                pageState = LabyrinthEntryPageState.UNKNOWN,
                confirmationConfidence = 0.95,
                hasUniqueReachableRouteTarget = true,
                shopBackgroundVisible = true,
            ),
        )
        assertTrue(
            labyrinthCanRecoverOrphanNodeMoveConfirmation(
                pageState = LabyrinthEntryPageState.UNKNOWN,
                confirmationConfidence = 0.95,
                hasUniqueReachableRouteTarget = true,
                shopBackgroundVisible = false,
            ),
        )
    }

    @Test
    fun `single-choice event needs both trigger and select anchors`() {
        val rect = EntryPixelRect(820, 805, 295, 145)
        val trusted = singleChoiceEventResult(rect)
        assertEquals(rect, labyrinthSingleChoiceEventButtonRect(trusted))

        val weakTitle = trusted.copy(
            observation = trusted.observation.copy(
                anchorScores = LabyrinthAnchorScores(
                    mapOf(
                        EntryAnchorId.EVENT_SINGLE_CHOICE_TRIGGER_TITLE to 0.60,
                        EntryAnchorId.EVENT_SINGLE_CHOICE_SELECT_BUTTON to 0.96,
                    ),
                ),
            ),
        )
        assertNull(labyrinthSingleChoiceEventButtonRect(weakTitle))
        assertNull(labyrinthSingleChoiceEventButtonRect(trusted.copy(anchorMatches = emptyMap())))
    }

    @Test
    fun `single-choice templates cannot override complete two or three choice layouts`() {
        val frame = singleChoiceEventResult()
        for (count in listOf(2, 3)) {
            assertNull(labyrinthSingleChoiceEventButtonRect(frame.copy(
                eventOcrDiagnostics = LabyrinthEventOcrDiagnostics(
                    status = "NO_CATALOG_MATCH",
                    completeLayoutCount = count,
                ),
            ), eventCustomizationEnabled = true))
        }
    }

    @Test
    fun `single-choice templates never preempt title OCR fast path`() {
        val frame = singleChoiceEventResult()
        for (status in listOf(
            "TITLE_WAITING_OCR",
            "TITLE_CANDIDATE_MATCH",
            "TITLE_TRUSTED_MATCH",
            "TITLE_FALLBACK",
            "TITLE_FUTURE_STATE",
        )) {
            assertNull(labyrinthSingleChoiceEventButtonRect(frame.copy(
                eventOcrDiagnostics = LabyrinthEventOcrDiagnostics(
                    status = status,
                    completeLayoutCount = 1,
                ),
            ), eventCustomizationEnabled = true))
        }
    }

    @Test
    fun `single-choice templates wait for pending OCR even with a complete single layout`() {
        val frame = singleChoiceEventResult()
        for (count in listOf(null, 1)) {
            assertNull(labyrinthSingleChoiceEventButtonRect(frame.copy(
                eventOcrDiagnostics = LabyrinthEventOcrDiagnostics(
                    status = "WAITING_OCR",
                    completeLayoutCount = count,
                ),
            ), eventCustomizationEnabled = true))
        }
    }

    @Test
    fun `single-choice templates retain original behavior without OCR diagnostics`() {
        val rect = EntryPixelRect(820, 805, 295, 145)
        val frame = singleChoiceEventResult(rect)
        assertNull(frame.eventOcrDiagnostics)
        assertEquals(rect, labyrinthSingleChoiceEventButtonRect(frame))
    }

    @Test
    fun `single-choice templates may fall back after unmatched or ineligible OCR`() {
        val rect = EntryPixelRect(820, 805, 295, 145)
        val frame = singleChoiceEventResult(rect)
        for (status in listOf("NO_CATALOG_MATCH", "INELIGIBLE_PAGE_OR_MOVE_DIALOG")) {
            for (count in listOf(null, 1)) {
                assertEquals(rect, labyrinthSingleChoiceEventButtonRect(frame.copy(
                    eventOcrDiagnostics = LabyrinthEventOcrDiagnostics(
                        status = status,
                        completeLayoutCount = count,
                    ),
                )))
            }
        }
    }

    private fun singleChoiceEventResult(
        rect: EntryPixelRect = EntryPixelRect(820, 805, 295, 145),
    ) = result(
        LabyrinthEntryPageState.EVENT_CHOICE,
        anchorScores = mapOf(
            EntryAnchorId.EVENT_SINGLE_CHOICE_TRIGGER_TITLE to 0.94,
            EntryAnchorId.EVENT_SINGLE_CHOICE_SELECT_BUTTON to 0.96,
        ),
    ).copy(
        anchorMatches = mapOf(
            EntryAnchorId.EVENT_SINGLE_CHOICE_SELECT_BUTTON to EntryAnchorMatch(0.96, rect),
        ),
    )

    @Test
    fun `EX identity probe restarts on every fresh visit to the challenge page`() {
        val challenge = LabyrinthEntryPageState.BATTLE_CHALLENGE
        // Back from a lost battle / the map: fresh budget.
        assertTrue(labyrinthExIdentityProbeRestarts(LabyrinthEntryPageState.BATTLE_FAILED, challenge, false))
        assertTrue(labyrinthExIdentityProbeRestarts(LabyrinthEntryPageState.NODE_SELECTION, challenge, false))
        // Still on the page (or its 详情 modal / animation frames): keep the running clock.
        assertEquals(false, labyrinthExIdentityProbeRestarts(challenge, challenge, false))
        assertEquals(false, labyrinthExIdentityProbeRestarts(null, challenge, false))
        // Already identified: nothing to restart.
        assertEquals(false, labyrinthExIdentityProbeRestarts(LabyrinthEntryPageState.BATTLE_FAILED, challenge, true))
        // Not a challenge page at all.
        assertEquals(false, labyrinthExIdentityProbeRestarts(challenge, LabyrinthEntryPageState.BATTLE_FAILED, false))
    }

    @Test
    fun `single EX detail fallback waits for OCR grace and excludes structured encounters`() {
        assertNull(
            labyrinthSingleExDetailProbeRect(
                pageState = LabyrinthEntryPageState.BATTLE_CHALLENGE,
                isEx = true,
                encounterResolved = false,
                multiMonsterLikely = false,
                specialDualLikely = false,
                elapsedMillis = 2_999L,
                stableFrames = 3,
                attempts = 0,
                frameWidth = 1920,
                frameHeight = 1080,
            ),
        )
        val rect = requireNotNull(
            labyrinthSingleExDetailProbeRect(
                pageState = LabyrinthEntryPageState.BATTLE_CHALLENGE,
                isEx = true,
                encounterResolved = false,
                multiMonsterLikely = false,
                specialDualLikely = false,
                elapsedMillis = 3_000L,
                stableFrames = 3,
                attempts = 0,
                frameWidth = 1920,
                frameHeight = 1080,
            ),
        )
        assertEquals(1395, rect.left)
        assertEquals(590, rect.top)
        assertEquals(155, rect.width)
        assertEquals(88, rect.height)

        assertNull(
            labyrinthSingleExDetailProbeRect(
                pageState = LabyrinthEntryPageState.BATTLE_CHALLENGE,
                isEx = true,
                encounterResolved = false,
                multiMonsterLikely = true,
                specialDualLikely = false,
                elapsedMillis = 5_000L,
                stableFrames = 3,
                attempts = 0,
                frameWidth = 1920,
                frameHeight = 1080,
            ),
        )
        assertNull(
            labyrinthSingleExDetailProbeRect(
                pageState = LabyrinthEntryPageState.BATTLE_CHALLENGE,
                isEx = true,
                encounterResolved = true,
                multiMonsterLikely = false,
                specialDualLikely = false,
                elapsedMillis = 5_000L,
                stableFrames = 3,
                attempts = 0,
                frameWidth = 1920,
                frameHeight = 1080,
            ),
        )
    }

    @Test
    fun `recent shop animation unknown frame cannot masquerade as session failure`() {
        assertTrue(
            labyrinthShopTransitionSuppressesSessionBlock(
                pageState = LabyrinthEntryPageState.UNKNOWN,
                previousPageState = LabyrinthEntryPageState.SHOP_PURCHASE_COMPLETE,
                activeNodeType = LabyrinthNodeTypes.SHOP,
                lastActionAt = 1_000L,
                now = 2_500L,
            ),
        )
        assertEquals(
            false,
            labyrinthShopTransitionSuppressesSessionBlock(
                pageState = LabyrinthEntryPageState.UNKNOWN,
                previousPageState = LabyrinthEntryPageState.SHOP,
                activeNodeType = LabyrinthNodeTypes.SHOP,
                lastActionAt = 1_000L,
                now = 4_500L,
            ),
        )
        assertEquals(
            false,
            labyrinthShopTransitionSuppressesSessionBlock(
                pageState = LabyrinthEntryPageState.UNKNOWN,
                previousPageState = LabyrinthEntryPageState.SHOP,
                activeNodeType = LabyrinthNodeTypes.EVENT,
                lastActionAt = 1_000L,
                now = 2_000L,
            ),
        )
    }

    @Test
    fun `resumed run may hand pending role rewards directly to route execution`() {
        val joined = result(LabyrinthEntryPageState.CHARACTER_JOINED)
        val roleChoice = result(
            LabyrinthEntryPageState.INITIAL_CHARACTER_SELECTION,
            anchorScores = mapOf(
                EntryAnchorId.ROLE_REWARD_TITLE to 0.95,
                EntryAnchorId.ROLE_REWARD_INSTRUCTION to 0.94,
            ),
        )
        val freshOpening = result(
            LabyrinthEntryPageState.INITIAL_CHARACTER_SELECTION,
            anchorScores = mapOf(
                EntryAnchorId.SELECTION_HEADER_STANDARD to 0.99,
                EntryAnchorId.INVITE_DISABLED_STANDARD to 0.95,
                // Deliberate false positives: the opening shell must still own the frame.
                EntryAnchorId.ROLE_REWARD_TITLE to 0.90,
                EntryAnchorId.ROLE_REWARD_INSTRUCTION to 0.89,
            ),
        )

        assertTrue(labyrinthResumedRunRewardPageOwnsRoute(joined))
        assertTrue(labyrinthResumedRunRewardPageOwnsRoute(roleChoice))
        assertEquals(false, labyrinthResumedRunRewardPageOwnsRoute(freshOpening))
        assertEquals(
            false,
            labyrinthResumedRunRewardPageOwnsRoute(result(LabyrinthEntryPageState.NODE_SELECTION)),
        )
    }

    @Test
    fun `role reward signature distinguishes consecutive choice rounds without a page-state change`() {
        val anchors = mapOf(
            EntryAnchorId.ROLE_REWARD_TITLE to 0.95,
            EntryAnchorId.ROLE_REWARD_INSTRUCTION to 0.94,
        )
        val first = result(
            LabyrinthEntryPageState.INITIAL_CHARACTER_SELECTION,
            anchorScores = anchors,
            characterMatches = listOf(
                character("1001", "A", trusted = true),
                character("1002", "B", trusted = true),
                character("1003", "C", trusted = true),
            ),
        )
        val second = result(
            LabyrinthEntryPageState.INITIAL_CHARACTER_SELECTION,
            anchorScores = anchors,
            characterMatches = listOf(
                character("1004", "D", trusted = true),
                character("1005", "E", trusted = true),
                character("1006", "F", trusted = true),
            ),
        )

        val firstSignature = labyrinthRoleRewardSelectionSignature(first)
        assertEquals(firstSignature, labyrinthRoleRewardSelectionSignature(first))
        assertTrue(firstSignature != labyrinthRoleRewardSelectionSignature(second))
    }

    @Test
    fun `role reward batch survives all choices then consecutive joined popups until a terminal page`() {
        assertTrue(
            labyrinthKeepsRoleRewardBatchOnPage(
                LabyrinthEntryPageState.INITIAL_CHARACTER_SELECTION,
                isRoleRewardPage = true,
            ),
        )
        assertTrue(labyrinthKeepsRoleRewardBatchOnPage(LabyrinthEntryPageState.UNKNOWN, false))
        assertTrue(labyrinthKeepsRoleRewardBatchOnPage(LabyrinthEntryPageState.CHARACTER_JOINED, false))
        assertTrue(labyrinthKeepsRoleRewardBatchOnPage(LabyrinthEntryPageState.ITEM_REWARD, false))
        assertEquals(
            false,
            labyrinthKeepsRoleRewardBatchOnPage(
                LabyrinthEntryPageState.INITIAL_CHARACTER_SELECTION,
                isRoleRewardPage = false,
            ),
        )
        assertEquals(false, labyrinthKeepsRoleRewardBatchOnPage(LabyrinthEntryPageState.NODE_SELECTION, false))
        assertEquals(false, labyrinthKeepsRoleRewardBatchOnPage(LabyrinthEntryPageState.SHOP, false))
    }

    @Test
    fun `only a reliable boss unknown next button can enter the intermediate boss settlement chain`() {
        val rect = EntryPixelRect(1412, 966, 414, 92)
        val reliable = EntryAnchorMatch(0.72, rect)

        assertEquals(
            rect,
            labyrinthBossSettlementNextButtonRect(
                LabyrinthEntryPageState.UNKNOWN,
                LabyrinthCombatContext(LabyrinthCombatKind.BOSS),
                reliable,
            ),
        )
        assertNull(
            labyrinthBossSettlementNextButtonRect(
                LabyrinthEntryPageState.UNKNOWN,
                LabyrinthCombatContext(LabyrinthCombatKind.NORMAL),
                reliable,
            ),
        )
        assertNull(
            labyrinthBossSettlementNextButtonRect(
                LabyrinthEntryPageState.BATTLE_RESULT,
                LabyrinthCombatContext(LabyrinthCombatKind.BOSS),
                reliable,
            ),
        )
        assertNull(
            labyrinthBossSettlementNextButtonRect(
                LabyrinthEntryPageState.UNKNOWN,
                LabyrinthCombatContext(LabyrinthCombatKind.BOSS),
                EntryAnchorMatch(0.67, rect),
            ),
        )

        // Current three-team Boss summary has a different button skin. A dedicated strong match
        // must recover the settlement even when the legacy result-button template is unusable.
        assertEquals(
            rect,
            labyrinthBossSettlementNextButtonRect(
                pageState = LabyrinthEntryPageState.UNKNOWN,
                combatContext = LabyrinthCombatContext(LabyrinthCombatKind.BOSS),
                nextButtonMatch = EntryAnchorMatch(0.19, rect),
                bossSummaryNextButtonMatch = EntryAnchorMatch(0.99, rect),
            ),
        )
    }

    @Test
    fun `1088 recovery requires first start evidence and rejects route owned pickers`() {
        val start = LabyrinthNodeJson(1, 1, 1, 10101L, LabyrinthNodeTypes.START, null, emptyList(), false)
        val state = NodeSessionState(1, 10101L, listOf(10101L))
        fun allowed(
            route: NodeSessionState? = state,
            node: LabyrinthNodeJson? = start,
            transition: Boolean = false,
            activeType: Int? = null,
            imprintPending: Boolean = false,
        ) = labyrinthAllowsInitial1088GeometryRefinement(route, node, transition, activeType, imprintPending)
        assertTrue(allowed())
        assertTrue(allowed(route = null))
        assertTrue(allowed(node = null))
        assertFalse(allowed(route = null, node = null))
        assertTrue(labyrinthAllowsInitial1088GeometryRefinement(null, null, false, null, false, initialStartExecuted = true))
        assertFalse(labyrinthAllowsInitial1088GeometryRefinement(null, null, true, null, false, initialStartExecuted = true))
        assertFalse(labyrinthAllowsInitial1088GeometryRefinement(null, null, false, LabyrinthNodeTypes.EVENT, false, initialStartExecuted = true))
        assertFalse(allowed(transition = true))
        assertFalse(allowed(imprintPending = true))
        assertFalse(allowed(activeType = LabyrinthNodeTypes.EVENT))
        assertFalse(allowed(activeType = LabyrinthNodeTypes.SHOP))
        assertFalse(allowed(node = start.copy(blockType = LabyrinthNodeTypes.EVENT)))
        assertFalse(allowed(node = start.copy(blockType = LabyrinthNodeTypes.SHOP)))
        assertFalse(allowed(node = start.copy(area = 2)))
        assertFalse(allowed(route = state.copy(currentArea = 2)))
        assertFalse(allowed(route = state.copy(visitedNodes = listOf(10101L, 10102L))))
        assertFalse(allowed(route = state.copy(currentNodeId = 10102L)))
        assertFalse(allowed(route = state.copy(isComplete = true)))
    }

    @Test
    fun `opening roster reclaims premature route handoff but reward choices do not`() {
        val opening = result(
            LabyrinthEntryPageState.INITIAL_CHARACTER_SELECTION,
            anchorScores = mapOf(EntryAnchorId.SELECTION_HEADER_STANDARD to 0.99998),
        )
        assertTrue(labyrinthShouldResumeOpeningSelection(true, opening))
        assertEquals(false, labyrinthShouldResumeOpeningSelection(false, opening))
        val reward = result(
            LabyrinthEntryPageState.INITIAL_CHARACTER_SELECTION,
            anchorScores = mapOf(
                EntryAnchorId.SELECTION_HEADER_STANDARD to 0.99,
                EntryAnchorId.ROLE_REWARD_TITLE to 0.95,
                EntryAnchorId.ROLE_REWARD_INSTRUCTION to 0.95,
            ),
        )
        assertEquals(false, labyrinthShouldResumeOpeningSelection(true, reward))
        assertEquals(false, labyrinthShouldResumeOpeningSelection(true, result(LabyrinthEntryPageState.NODE_SELECTION)))
        assertEquals(false, labyrinthShouldResumeOpeningSelection(true, result(LabyrinthEntryPageState.INITIAL_CHARACTER_SELECTION)))
    }

    @Test
    fun `first map glimpse cannot send the delayed opening selector to event free picks`() {
        // 2026-09-26 bundle 190009: an active home led to two map frames then the 0-of-3
        // opening selector. A node session existed, but only the start had been visited.
        val picker = result(
            LabyrinthEntryPageState.INITIAL_CHARACTER_SELECTION,
            anchorScores = mapOf(EntryAnchorId.SELECTION_HEADER_STANDARD to 0.995),
        )
        val opening = NodeSessionState(currentArea = 1, currentNodeId = 10101L, visitedNodes = listOf(10101L))
        val routeActive = labyrinthRouteHasPassedOpening(opening)
        assertFalse(routeActive)
        assertTrue(labyrinthShouldResumeOpeningSelection(true, picker, routeActive))
        assertFalse(labyrinthEventFreeRoleSelectionOwnsFrame(
            pageState = picker.observation.state,
            activeNodeType = LabyrinthNodeTypes.START,
            roleRewardPage = false,
            hasOpeningViewport = true,
            routeActive = routeActive,
        ))

        // A picker inside a real run stays owned by the route, including after node-type
        // memory is lost and at a later area's start following a Boss.
        for (progressed in listOf(
            opening.copy(currentNodeId = 10102L, visitedNodes = listOf(10101L, 10102L)),
            NodeSessionState(currentArea = 2, currentNodeId = 20101L, visitedNodes = listOf(20101L)),
        )) {
            val progressedRoute = labyrinthRouteHasPassedOpening(progressed)
            assertTrue(progressedRoute)
            assertFalse(labyrinthShouldResumeOpeningSelection(true, picker, progressedRoute))
            assertTrue(labyrinthEventFreeRoleSelectionOwnsFrame(
                pageState = picker.observation.state,
                activeNodeType = null,
                roleRewardPage = false,
                hasOpeningViewport = true,
                routeActive = progressedRoute,
            ))
        }
        assertFalse(labyrinthRouteHasPassedOpening(null))
        // The first event may open a picker before its destination frame updates visitedNodes.
        val firstEventPending = labyrinthRouteHasPassedOpening(opening, hasNodeTransition = true)
        assertTrue(firstEventPending)
        assertFalse(labyrinthShouldResumeOpeningSelection(true, picker, firstEventPending))
        assertTrue(labyrinthEventFreeRoleSelectionOwnsFrame(
            pageState = picker.observation.state,
            activeNodeType = LabyrinthNodeTypes.EVENT,
            roleRewardPage = false,
            hasOpeningViewport = true,
            routeActive = firstEventPending,
        ))
    }

    @Test
    fun `page state is the hard UI ownership boundary`() {
        assertEquals(LabyrinthPageUiOwner.NODE_SELECTION, labyrinthPageUiOwner(LabyrinthEntryPageState.NODE_SELECTION))
        assertEquals(LabyrinthPageUiOwner.NODE_MAP_VIEW, labyrinthPageUiOwner(LabyrinthEntryPageState.NODE_MAP_VIEW))
        assertEquals(
            LabyrinthPageUiOwner.CHARACTER_SELECTION,
            labyrinthPageUiOwner(LabyrinthEntryPageState.INITIAL_CHARACTER_SELECTION),
        )
        assertEquals(
            LabyrinthPageUiOwner.BATTLE_TEAM_SELECTION,
            labyrinthPageUiOwner(LabyrinthEntryPageState.BATTLE_TEAM_SELECTION),
        )
        assertEquals(LabyrinthPageUiOwner.NONE, labyrinthPageUiOwner(LabyrinthEntryPageState.UNKNOWN))
    }

    @Test
    fun `node move confirmation cannot preempt a recognized shop dialog`() {
        assertEquals(
            false,
            labyrinthNodeMoveConfirmationOwnsFrame(
                LabyrinthEntryPageState.SHOP_EXIT_CONFIRMATION,
                hasPendingNodeTransition = false,
            ),
        )
        assertEquals(
            false,
            labyrinthNodeMoveConfirmationOwnsFrame(
                LabyrinthEntryPageState.SHOP_EXIT_CONFIRMATION,
                hasPendingNodeTransition = true,
            ),
        )
        assertEquals(
            false,
            labyrinthNodeMoveConfirmationOwnsFrame(
                LabyrinthEntryPageState.SHOP_PURCHASE_CONFIRMATION,
                hasPendingNodeTransition = true,
            ),
        )
    }

    @Test
    fun `node move confirmation requires a programmatic pending node transition`() {
        assertEquals(
            false,
            labyrinthNodeMoveConfirmationOwnsFrame(
                LabyrinthEntryPageState.NODE_SELECTION,
                hasPendingNodeTransition = false,
            ),
        )
        assertEquals(
            false,
            labyrinthNodeMoveConfirmationOwnsFrame(
                LabyrinthEntryPageState.NODE_MAP_VIEW,
                hasPendingNodeTransition = false,
            ),
        )
        assertEquals(
            false,
            labyrinthNodeMoveConfirmationOwnsFrame(
                LabyrinthEntryPageState.UNKNOWN,
                hasPendingNodeTransition = false,
            ),
        )
        assertTrue(
            labyrinthNodeMoveConfirmationOwnsFrame(
                LabyrinthEntryPageState.NODE_SELECTION,
                hasPendingNodeTransition = true,
            ),
        )
        assertTrue(
            labyrinthNodeMoveConfirmationOwnsFrame(
                LabyrinthEntryPageState.NODE_MAP_VIEW,
                hasPendingNodeTransition = true,
            ),
        )
        assertTrue(
            labyrinthNodeMoveConfirmationOwnsFrame(
                LabyrinthEntryPageState.UNKNOWN,
                hasPendingNodeTransition = true,
            ),
        )
        assertEquals(
            false,
            labyrinthNodeMoveConfirmationOwnsFrame(
                LabyrinthEntryPageState.BATTLE_CHALLENGE,
                hasPendingNodeTransition = true,
            ),
        )
    }

    @Test
    fun `recognized movement modal preserves pending transition across unknown map classification`() {
        assertTrue(
            labyrinthPreservesPendingNodeTransitionForMoveConfirmation(
                pageState = LabyrinthEntryPageState.UNKNOWN,
                hasPendingNodeTransition = true,
                hasMoveConfirmation = true,
            ),
        )
        assertTrue(
            labyrinthPreservesPendingNodeTransitionForMoveConfirmation(
                pageState = LabyrinthEntryPageState.NODE_MAP_VIEW,
                hasPendingNodeTransition = true,
                hasMoveConfirmation = true,
            ),
        )
        assertEquals(
            false,
            labyrinthPreservesPendingNodeTransitionForMoveConfirmation(
                pageState = LabyrinthEntryPageState.UNKNOWN,
                hasPendingNodeTransition = false,
                hasMoveConfirmation = true,
            ),
        )
        assertEquals(
            false,
            labyrinthPreservesPendingNodeTransitionForMoveConfirmation(
                pageState = LabyrinthEntryPageState.SHOP_EXIT_CONFIRMATION,
                hasPendingNodeTransition = true,
                hasMoveConfirmation = true,
            ),
        )
        assertEquals(
            false,
            labyrinthPreservesPendingNodeTransitionForMoveConfirmation(
                pageState = LabyrinthEntryPageState.UNKNOWN,
                hasPendingNodeTransition = true,
                hasMoveConfirmation = false,
            ),
        )
    }

    @Test
    fun `auto shop purchase survives only a bounded unknown transition`() {
        assertTrue(
            labyrinthKeepsPlannedShopPurchaseAcrossPage(
                pageState = LabyrinthEntryPageState.UNKNOWN,
                plannedRelicId = "25004",
                startedAt = 1_000L,
                now = 4_000L,
                timeoutMillis = 8_000L,
            ),
        )
        assertEquals(
            false,
            labyrinthKeepsPlannedShopPurchaseAcrossPage(
                pageState = LabyrinthEntryPageState.UNKNOWN,
                plannedRelicId = "25004",
                startedAt = 1_000L,
                now = 10_001L,
                timeoutMillis = 8_000L,
            ),
        )
        assertEquals(
            false,
            labyrinthKeepsPlannedShopPurchaseAcrossPage(
                pageState = LabyrinthEntryPageState.NODE_SELECTION,
                plannedRelicId = "25004",
                startedAt = 1_000L,
                now = 2_000L,
                timeoutMillis = 8_000L,
            ),
        )
        assertEquals(
            false,
            labyrinthKeepsPlannedShopPurchaseAcrossPage(
                pageState = LabyrinthEntryPageState.UNKNOWN,
                plannedRelicId = null,
                startedAt = 1_000L,
                now = 2_000L,
                timeoutMillis = 8_000L,
            ),
        )
    }

    @Test
    fun `shop refresh owns generic confirmation only while its transaction is pending`() {
        assertTrue(
            labyrinthShopRefreshConfirmationOwnsFrame(
                activeNodeType = LabyrinthNodeTypes.SHOP,
                refreshStartedAt = 1_000L,
                refreshConfirmedAt = Long.MIN_VALUE,
                now = 2_000L,
                hasGenericConfirmation = true,
                timeoutMillis = 8_000L,
            ),
        )
        assertEquals(
            false,
            labyrinthShopRefreshConfirmationOwnsFrame(
                activeNodeType = LabyrinthNodeTypes.SHOP,
                refreshStartedAt = 1_000L,
                refreshConfirmedAt = 1_500L,
                now = 2_000L,
                hasGenericConfirmation = true,
                timeoutMillis = 8_000L,
            ),
        )
        assertEquals(
            false,
            labyrinthShopRefreshConfirmationOwnsFrame(
                activeNodeType = LabyrinthNodeTypes.EX_BATTLE,
                refreshStartedAt = 1_000L,
                refreshConfirmedAt = Long.MIN_VALUE,
                now = 2_000L,
                hasGenericConfirmation = true,
                timeoutMillis = 8_000L,
            ),
        )
    }

    @Test
    fun `shop refresh commits only after confirm disappears and shop settles`() {
        assertTrue(
            labyrinthShopRefreshCanCommit(
                pageState = LabyrinthEntryPageState.SHOP,
                refreshStartedAt = 1_000L,
                refreshConfirmedAt = 2_000L,
                now = 2_600L,
                settleMillis = 500L,
                timeoutMillis = 8_000L,
            ),
        )
        assertEquals(
            false,
            labyrinthShopRefreshCanCommit(
                pageState = LabyrinthEntryPageState.SHOP,
                refreshStartedAt = 1_000L,
                refreshConfirmedAt = 2_000L,
                now = 2_200L,
                settleMillis = 500L,
                timeoutMillis = 8_000L,
            ),
        )
        assertEquals(
            false,
            labyrinthShopRefreshCanCommit(
                pageState = LabyrinthEntryPageState.UNKNOWN,
                refreshStartedAt = 1_000L,
                refreshConfirmedAt = 2_000L,
                now = 2_600L,
                settleMillis = 500L,
                timeoutMillis = 8_000L,
            ),
        )
    }

    @Test
    fun `character joined contributes any trusted full-icon identity`() {
        val matches = labyrinthRosterReconciliationMatches(
            result(
                LabyrinthEntryPageState.CHARACTER_JOINED,
                characterMatches = listOf(character("1310", "真步(梦想乐园)", trusted = true)),
            ),
        )

        assertEquals(listOf("1310"), matches.mapNotNull(LabyrinthCharacterMatch::characterId))
    }

    @Test
    fun `character joined never reconciles an untrusted guess`() {
        val matches = labyrinthRosterReconciliationMatches(
            result(
                LabyrinthEntryPageState.CHARACTER_JOINED,
                characterMatches = listOf(character("1310", "真步(梦想乐园)", trusted = false)),
            ),
        )

        assertTrue(matches.isEmpty())
    }

    @Test
    fun `battle team candidates are never treated as newly joined roles`() {
        val observation = battleTeamObservation(
            state = LabyrinthBattleTeamRecognitionState.STABLE,
            visible = listOf(
                battleCharacter("1059", "可可萝"),
                battleCharacter("1075", "贪吃佩可(夏日)"),
                battleCharacter("1351", "雪菲(夏日)"),
                battleCharacter("1310", "真步(梦想乐园)"),
            ),
            selected = listOf(
                battleCharacter("1091", "静流(情人节)", selected = true),
                battleCharacter("1168", "珠希(工作服)", selected = true),
            ),
        )

        val matches = labyrinthRosterReconciliationMatches(
            result(LabyrinthEntryPageState.BATTLE_TEAM_SELECTION, battleTeam = observation),
        )

        assertTrue(matches.isEmpty())
    }

    @Test
    fun `moving battle team viewport is not roster evidence`() {
        val observation = battleTeamObservation(
            state = LabyrinthBattleTeamRecognitionState.WAITING_FOR_STABILITY,
            visible = listOf(battleCharacter("1059", "可可萝")),
        )

        assertTrue(
            labyrinthRosterReconciliationMatches(
                result(LabyrinthEntryPageState.BATTLE_TEAM_SELECTION, battleTeam = observation),
            ).isEmpty(),
        )
    }

    @Test
    fun `empty effective filter completes the scan only with the notice and no cards`() {
        val empty = battleTeamObservation(
            state = LabyrinthBattleTeamRecognitionState.STABLE,
            visible = emptyList(),
        ).copy(currentFilter = LabyrinthBattleElementFilter.EFFECTIVE_EFFECT)
        assertTrue(labyrinthEffectiveFilterIsEmpty(empty, emptyNoticeScore = 0.95))
        // Notice alone (cards still loading in) or cards alone never count as empty.
        assertTrue(!labyrinthEffectiveFilterIsEmpty(empty, emptyNoticeScore = 0.40))
        assertTrue(!labyrinthEffectiveFilterIsEmpty(empty.copy(visibleCharacters = listOf(battleCharacter("1059", "可可萝"))), emptyNoticeScore = 0.95))
        assertTrue(!labyrinthEffectiveFilterIsEmpty(empty.copy(recognitionState = LabyrinthBattleTeamRecognitionState.WAITING_FOR_STABILITY), emptyNoticeScore = 0.95))
        assertTrue(!labyrinthEffectiveFilterIsEmpty(empty.copy(currentFilter = LabyrinthBattleElementFilter.ALL), emptyNoticeScore = 0.95))
    }

    @Test
    fun `拉比林斯 opening grant rides along with the confirmed picks`() {
        val grant = labyrinthGuildGrantedRosterMatches(5, EntryPixelRect(0, 0, 1920, 1080))
        assertEquals(listOf("1068"), grant.map { it.characterId })
        assertEquals(listOf("菈比莉斯塔"), grant.map { it.displayName })
        assertTrue(grant.single().trusted)
        assertTrue(labyrinthGuildGrantedRosterMatches(3, EntryPixelRect(0, 0, 1920, 1080)).isEmpty())
        assertTrue(labyrinthGuildGrantedRosterMatches(null, EntryPixelRect(0, 0, 1920, 1080)).isEmpty())
    }

    @Test
    fun `confirmed opening selection contributes exactly its three trusted selected roles`() {
        val anchors = mapOf(
            EntryAnchorId.SELECTION_COUNT_COMPLETE to 1.0,
            EntryAnchorId.INVITE_ENABLED to 1.0,
        )
        val opening = listOf(
            battleCharacter("1059", "可可萝", selected = true),
            battleCharacter("1075", "贪吃佩可(夏日)", selected = true),
            battleCharacter("1351", "雪菲(夏日)", selected = true),
            battleCharacter("1310", "真步(梦想乐园)", selected = false),
        )

        val matches = labyrinthRosterReconciliationMatches(
            result(
                LabyrinthEntryPageState.INITIAL_CHARACTER_SELECTION,
                anchorScores = anchors,
                openingCharacters = opening,
            ),
        )

        assertEquals(listOf("1059", "1075", "1351"), matches.mapNotNull(LabyrinthCharacterMatch::characterId))
        assertTrue(
            labyrinthRosterReplacesExisting(
                result(
                    LabyrinthEntryPageState.INITIAL_CHARACTER_SELECTION,
                    anchorScores = anchors,
                    openingCharacters = opening,
                ),
                matches,
            ),
        )
    }

    @Test
    fun `role reward candidates are not treated as already-owned roster`() {
        val anchors = mapOf(
            EntryAnchorId.ROLE_REWARD_TITLE to 1.0,
            EntryAnchorId.ROLE_REWARD_INSTRUCTION to 1.0,
            EntryAnchorId.SELECTION_COUNT_COMPLETE to 1.0,
            EntryAnchorId.INVITE_ENABLED to 1.0,
        )

        val matches = labyrinthRosterReconciliationMatches(
            result(
                LabyrinthEntryPageState.INITIAL_CHARACTER_SELECTION,
                anchorScores = anchors,
                openingCharacters = listOf(
                    battleCharacter("1310", "真步(梦想乐园)", selected = true),
                    battleCharacter("1022", "绫音", selected = true),
                    battleCharacter("1063", "亚里莎", selected = true),
                ),
            ),
        )

        assertTrue(matches.isEmpty())
    }

    @Test
    fun `opening roster with duplicate identity guesses is not persisted`() {
        val anchors = mapOf(
            EntryAnchorId.SELECTION_COUNT_COMPLETE to 1.0,
            EntryAnchorId.INVITE_ENABLED to 1.0,
        )
        val result = result(
            LabyrinthEntryPageState.INITIAL_CHARACTER_SELECTION,
            anchorScores = anchors,
            openingCharacters = listOf(
                battleCharacter("1075", "贪吃佩可(夏日)", selected = true),
                battleCharacter("1108", "可可萝(夏日)", selected = true),
                battleCharacter("1108", "可可萝(夏日)", selected = true),
            ),
        )
        val matches = labyrinthRosterReconciliationMatches(result)

        assertTrue(matches.isEmpty())
        assertTrue(!labyrinthRosterReplacesExisting(result, matches))
    }

    @Test
    fun `roster reconciliation is append-only across partial later viewports`() {
        val existing = listOf(
            joined("1059", "可可萝", 0),
            joined("1075", "贪吃佩可(夏日)", 1),
            joined("1351", "雪菲(夏日)", 2),
        )

        val merged = LabyrinthRosterMerger.merge(
            existing = existing,
            matches = listOf(character("1310", "真步(梦想乐园)", trusted = true)),
            nowMillis = 20L,
        )

        assertEquals(setOf("1059", "1075", "1351", "1310"), merged.map { it.characterId }.toSet())
    }

    @Test
    fun `confirmed opening roster replaces transient false identities`() {
        val polluted = listOf(
            joined("1075", "贪吃佩可(夏日)", 0),
            joined("1155", "贪吃佩可(圣诞节)", 1),
            joined("1290", "雪菲(新年)", 2),
            joined("1124", "花凛", 3),
        )

        val replaced = LabyrinthRosterMerger.merge(
            existing = polluted,
            matches = listOf(
                character("1075", "贪吃佩可(夏日)", trusted = true),
                character("1108", "可可萝(夏日)", trusted = true),
                character("1351", "雪菲(夏日)", trusted = true),
            ),
            nowMillis = 20L,
            replaceExisting = true,
        )

        assertEquals(setOf("1075", "1108", "1351"), replaced.map { it.characterId }.toSet())
    }

    @Test
    fun `combined member evidence canonicalizes to one combined role`() {
        val merged = LabyrinthRosterMerger.merge(
            existing = emptyList(),
            matches = listOf(
                character("1183", "初音", trusted = true),
                character("1184", "栞", trusted = true),
            ),
            nowMillis = 20L,
        )

        assertEquals(listOf("1807"), merged.map { it.characterId })
    }

    private fun result(
        state: LabyrinthEntryPageState,
        anchorScores: Map<String, Double> = emptyMap(),
        characterMatches: List<LabyrinthCharacterMatch> = emptyList(),
        openingCharacters: List<LabyrinthBattleCharacterMatch> = emptyList(),
        battleTeam: LabyrinthBattleTeamObservation? = null,
    ) = LabyrinthEntryFrameResult(
        observation = LabyrinthEntryPageObservation(
            state = state,
            confidence = 1.0,
            stateScores = mapOf(state to 1.0),
            anchorScores = LabyrinthAnchorScores(anchorScores),
        ),
        matchedFeatures = emptyList(),
        elapsedMillis = 0L,
        characterMatches = characterMatches,
        openingCharacterMatches = openingCharacters,
        battleTeamSelection = battleTeam,
        frameWidth = 1920,
        frameHeight = 1080,
    )

    private fun character(
        id: String,
        name: String,
        trusted: Boolean,
    ) = LabyrinthCharacterMatch(
        slotId = "slot-$id",
        characterId = id,
        displayName = name,
        confidence = if (trusted) 0.40 else 0.30,
        screenRect = RECT,
        iconVariant = "31",
        trusted = trusted,
        rivalMargin = if (trusted) 0.15 else 0.01,
    )

    private fun battleCharacter(
        id: String,
        name: String,
        selected: Boolean = false,
    ) = LabyrinthBattleCharacterMatch(
        slotId = "slot-$id-${if (selected) "selected" else "visible"}",
        characterId = id,
        displayName = name,
        iconVariant = "31",
        confidence = 0.40,
        screenRect = RECT,
        selected = selected,
        trusted = true,
        rivalMargin = 0.15,
    )

    private fun battleTeamObservation(
        state: LabyrinthBattleTeamRecognitionState,
        visible: List<LabyrinthBattleCharacterMatch>,
        selected: List<LabyrinthBattleCharacterMatch> = emptyList(),
    ) = LabyrinthBattleTeamObservation(
        currentFilter = LabyrinthBattleElementFilter.ALL,
        filters = emptyList(),
        visibleCharacters = visible,
        selectedCharacters = selected,
        scrollbar = LabyrinthBattleScrollbarObservation(
            trackRect = RECT,
            thumbRect = RECT,
            visible = true,
            canScroll = true,
            position = 0.0,
        ),
        recognitionState = state,
        viewportRevision = 1L,
    )

    private fun joined(id: String, name: String, order: Int) = LabyrinthJoinedCharacter(
        characterId = id,
        displayName = name,
        orderIndex = order,
        confidence = 0.9,
        firstSeenAt = 1L,
        lastSeenAt = 1L,
    )

    @Test
    fun `waiting for the return-title button never counts as confirming it`() {
        // The defect this pins: the session gated its tap on how long it had been blocked, and the
        // 等待识别“返回标题”按钮 stretches count as blocked. 2026-09-23 live, the labyrinth 商店 page
        // spiked session.return.title to 0.646 on single frames while already classified as blocked;
        // with the old counter primed, one such frame would have tapped with zero confirmation.
        var streak = 0
        repeat(200) { streak = labyrinthSessionReturnTitleStreak(streak, returnTitleRectPresent = false) }
        assertEquals(0, streak)

        // One spiking frame is not enough on its own.
        streak = labyrinthSessionReturnTitleStreak(streak, returnTitleRectPresent = true)
        assertEquals(1, streak)
        assertTrue("one frame must not be actionable", streak < 2)

        // A genuine popup keeps the button on screen, so it confirms on the very next frame.
        streak = labyrinthSessionReturnTitleStreak(streak, returnTitleRectPresent = true)
        assertEquals(2, streak)

        // And a single frame without the button drops the streak back to zero.
        streak = labyrinthSessionReturnTitleStreak(streak, returnTitleRectPresent = false)
        assertEquals(0, streak)
    }

    private companion object {
        val RECT = EntryPixelRect(10, 10, 100, 100)
    }
}
