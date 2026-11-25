package com.landosol.toolbox.labyrinth

import com.landosol.toolbox.automation.AutomationAction
import com.landosol.toolbox.automation.session.SessionBlockKind
import com.landosol.toolbox.labyrinth.node.NodeClassification
import com.landosol.toolbox.labyrinth.node.LabyrinthMapScanDirection
import com.landosol.toolbox.labyrinth.node.LabyrinthNodeTypes
import com.landosol.toolbox.labyrinth.node.labyrinthReferenceColumnPitch
import com.landosol.toolbox.labyrinth.node.NodePositionMapping
import com.landosol.toolbox.labyrinth.node.NodeTopologyBindingKind
import com.landosol.toolbox.labyrinth.vision.EntryAnchorId
import com.landosol.toolbox.labyrinth.vision.EntryAnchorMatch
import com.landosol.toolbox.labyrinth.vision.EntryPixelRect
import com.landosol.toolbox.labyrinth.vision.LabyrinthAnchorScores
import com.landosol.toolbox.labyrinth.vision.LabyrinthEntryFrameResult
import com.landosol.toolbox.labyrinth.vision.LabyrinthEntryPageObservation
import com.landosol.toolbox.labyrinth.vision.LabyrinthEntryPageState
import com.landosol.toolbox.labyrinth.vision.LabyrinthNodeMoveConfirmationObservation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LabyrinthEntryRecognitionPolicyTest {
    @Test
    fun `role reward stays manual while battle team and relic pages are automated`() {
        assertEquals(
            "等待人工选择角色并确认",
            labyrinthManualInputMessage(LabyrinthEntryPageState.INITIAL_CHARACTER_SELECTION),
        )
        assertNull(labyrinthManualInputMessage(LabyrinthEntryPageState.RELIC_CHOICE))
        assertNull(labyrinthManualInputMessage(LabyrinthEntryPageState.BATTLE_TEAM_SELECTION))
    }

    @Test
    fun `battle team recommendation message announces automatic preparation`() {
        val recommendation = LabyrinthBattleTeamRecommendation(
            members = (1..5).map { index ->
                LabyrinthRecommendedTeamMember("$index", "角色$index")
            },
            damageType = LabyrinthTeamDamageType.PHYSICAL,
            score = 88.5,
            vanguard = LabyrinthRecommendedTeamMember("1", "角色1"),
            survivalAnchor = LabyrinthRecommendedTeamMember("1", "角色1"),
            reasons = listOf("当前角色池最高分五人"),
            defenseMarkStacks = 4,
            targetCount = 1,
        )

        val message = labyrinthBattleTeamSelectionMessage(
            combatContext = LabyrinthCombatContext(LabyrinthCombatKind.BOSS, teamIndex = 1),
            recommendation = recommendation,
            unavailableReason = null,
        )

        assertTrue(message.contains("准备 Boss 第1/3 队编组"))
        assertTrue(message.contains("推荐第一队"))
        assertTrue(!message.contains("不执行编组点击"))
    }

    @Test
    fun `automated post entry pages are not blocked by the manual input gate`() {
        listOf(
            LabyrinthEntryPageState.LINK_CHOICE,
            LabyrinthEntryPageState.RELIC_CHOICE,
            LabyrinthEntryPageState.ITEM_REWARD,
            LabyrinthEntryPageState.CHARACTER_JOINED,
            LabyrinthEntryPageState.SHOP,
            LabyrinthEntryPageState.BATTLE_CHALLENGE,
            LabyrinthEntryPageState.BATTLE_RESULT,
            LabyrinthEntryPageState.RUN_CLEAR_RESULT,
        ).forEach { state ->
            assertNull(state.name, labyrinthManualInputMessage(state))
        }
    }

    @Test
    fun `character acquisition fallback only arms after a known reward transition`() {
        listOf(
            LabyrinthEntryPageState.INITIAL_CHARACTER_SELECTION,
            LabyrinthEntryPageState.LINK_CHOICE,
            LabyrinthEntryPageState.EVENT_CHOICE,
            LabyrinthEntryPageState.EVENT_ANIMATION,
            LabyrinthEntryPageState.ITEM_REWARD,
            LabyrinthEntryPageState.BATTLE_RESULT,
        ).forEach { previousPage ->
            assertTrue(
                previousPage.name,
                shouldArmCharacterAcquisitionFallback(
                    previousPage = previousPage,
                    currentPage = LabyrinthEntryPageState.UNKNOWN,
                    activeNodeType = LabyrinthNodeTypes.NORMAL_BATTLE,
                ),
            )
        }
        assertTrue(
            shouldArmCharacterAcquisitionFallback(
                previousPage = LabyrinthEntryPageState.ITEM_REWARD,
                currentPage = LabyrinthEntryPageState.UNKNOWN,
                activeNodeType = LabyrinthNodeTypes.NORMAL_BATTLE,
            ),
        )
        assertTrue(
            shouldArmCharacterAcquisitionFallback(
                previousPage = LabyrinthEntryPageState.ITEM_REWARD,
                currentPage = LabyrinthEntryPageState.UNKNOWN,
                activeNodeType = LabyrinthNodeTypes.LINK,
            ),
        )
        assertTrue(
            !shouldArmCharacterAcquisitionFallback(
                previousPage = LabyrinthEntryPageState.ITEM_REWARD,
                currentPage = LabyrinthEntryPageState.UNKNOWN,
                activeNodeType = LabyrinthNodeTypes.RELIC,
            ),
        )
        assertTrue(
            !shouldArmCharacterAcquisitionFallback(
                previousPage = LabyrinthEntryPageState.NODE_SELECTION,
                currentPage = LabyrinthEntryPageState.UNKNOWN,
            ),
        )
        assertTrue(
            !shouldArmCharacterAcquisitionFallback(
                previousPage = LabyrinthEntryPageState.EVENT_ANIMATION,
                currentPage = LabyrinthEntryPageState.EVENT_ANIMATION,
            ),
        )
    }

    @Test
    fun `final settlement pages wait instead of stopping when no route is configured`() {
        listOf(
            LabyrinthEntryPageState.RUN_CLEAR_RESULT,
            LabyrinthEntryPageState.RUN_CLEAR_CONGRATULATIONS,
            LabyrinthEntryPageState.RUN_CLEAR_CHARACTER_SUMMARY,
            LabyrinthEntryPageState.RUN_CLEAR_REWARD_ANIMATION,
            LabyrinthEntryPageState.RUN_CLEAR_CHEST_ANIMATION,
            LabyrinthEntryPageState.RUN_CLEAR_CHEST_RESULT,
        ).forEach { assertTrue(it.name, labyrinthCompletionWaitsWithoutRoute(it)) }
        listOf(
            LabyrinthEntryPageState.NODE_SELECTION,
            LabyrinthEntryPageState.BATTLE_RESULT,
            LabyrinthEntryPageState.DAWN_REALM_HOME_IDLE,
            LabyrinthEntryPageState.UNKNOWN,
        ).forEach { assertTrue(it.name, !labyrinthCompletionWaitsWithoutRoute(it)) }
    }

    @Test
    fun `event free role picks another only while 去邀请 stays disabled after a settled pick`() {
        // One-pick event: the button lights up right after the first pick → never a second pick.
        assertTrue(!labyrinthEventFreeRoleNeedsAnotherPick(1, inviteEnabled = true, inviteDisabled = false, millisSinceLastSelect = 5_000L))
        // Two-pick event (2026-09-17): still disabled after the pick settled → pick again.
        assertTrue(labyrinthEventFreeRoleNeedsAnotherPick(1, inviteEnabled = false, inviteDisabled = true, millisSinceLastSelect = 2_500L))
        // Not settled yet, or the disabled button is not even recognised: wait.
        assertTrue(!labyrinthEventFreeRoleNeedsAnotherPick(1, inviteEnabled = false, inviteDisabled = true, millisSinceLastSelect = 800L))
        assertTrue(!labyrinthEventFreeRoleNeedsAnotherPick(1, inviteEnabled = false, inviteDisabled = false, millisSinceLastSelect = 5_000L))
        // The shell has three slots; never pick a fourth, and nothing to settle before the first.
        assertTrue(!labyrinthEventFreeRoleNeedsAnotherPick(3, inviteEnabled = false, inviteDisabled = true, millisSinceLastSelect = 5_000L))
        assertTrue(!labyrinthEventFreeRoleNeedsAnotherPick(0, inviteEnabled = false, inviteDisabled = true, millisSinceLastSelect = 5_000L))
    }

    @Test
    fun `去邀请 grey button is told from the blue one by the margin between both templates`() {
        // Real scores from limingjie-debug-20260923-013929 frame 598: nothing was selected, yet
        // the enabled template still scored 0.925. Only the margin against 0.987 exposes the grey
        // button, and reading it as blue confirmed an empty invitation until the user killed it.
        assertEquals(
            LabyrinthInviteButtonState.DISABLED,
            labyrinthInviteButtonState(enabledScore = 0.925, disabledScore = 0.987),
        )
        assertEquals(
            LabyrinthInviteButtonState.ENABLED,
            labyrinthInviteButtonState(enabledScore = 0.981, disabledScore = 0.872),
        )
        // A tie decides nothing, and neither does a frame where no template matched at all.
        assertEquals(
            LabyrinthInviteButtonState.UNKNOWN,
            labyrinthInviteButtonState(enabledScore = 0.930, disabledScore = 0.925),
        )
        assertEquals(
            LabyrinthInviteButtonState.UNKNOWN,
            labyrinthInviteButtonState(enabledScore = 0.310, disabledScore = 0.402),
        )
    }

    @Test
    fun `free role picks are reconciled against the roster instead of the dispatch log`() {
        // The 2026-09-23 deadlock: the tap on 埃拉 never registered, the card sits on screen
        // unselected, and the remembered pick has to be dropped so the run picks again.
        assertEquals(
            emptyList<String>(),
            labyrinthEventFreeRoleReconcileSelection(
                rememberedIds = listOf("ella"),
                visibleSelectedIds = emptyList(),
                visibleIds = listOf("ella", "kyle", "yuni"),
            ),
        )
        // A pick that scrolled out of view is still ours; the roster cannot contradict it.
        assertEquals(
            listOf("ella"),
            labyrinthEventFreeRoleReconcileSelection(
                rememberedIds = listOf("ella"),
                visibleSelectedIds = emptyList(),
                visibleIds = listOf("kyle", "yuni"),
            ),
        )
        // Anything the roster shows as selected is adopted, whoever tapped it, without duplicates.
        assertEquals(
            listOf("ella", "kyle"),
            labyrinthEventFreeRoleReconcileSelection(
                rememberedIds = listOf("ella"),
                visibleSelectedIds = listOf("ella", "kyle"),
                visibleIds = listOf("ella", "kyle", "yuni"),
            ),
        )
    }

    @Test
    fun `event full roster one-role selector owns initial-selection-looking frame`() {
        assertTrue(
            labyrinthEventFreeRoleSelectionOwnsFrame(
                pageState = LabyrinthEntryPageState.INITIAL_CHARACTER_SELECTION,
                activeNodeType = LabyrinthNodeTypes.EVENT,
                roleRewardPage = false,
                hasOpeningViewport = true,
            ),
        )
        assertTrue(
            !labyrinthEventFreeRoleSelectionOwnsFrame(
                pageState = LabyrinthEntryPageState.INITIAL_CHARACTER_SELECTION,
                activeNodeType = null,
                roleRewardPage = false,
                hasOpeningViewport = true,
            ),
        )
        assertTrue(
            !labyrinthEventFreeRoleSelectionOwnsFrame(
                pageState = LabyrinthEntryPageState.INITIAL_CHARACTER_SELECTION,
                activeNodeType = LabyrinthNodeTypes.EVENT,
                roleRewardPage = true,
                hasOpeningViewport = true,
            ),
        )
    }

    @Test
    fun `a shop 选择印记 opens the same picker and must not reach the opening roster`() {
        // 2026-09-20 bundle 155513: the shop sells 选择印记 as well as 随机印记, and buying one
        // opens the full-roster 角色选择 page. The selector was gated on the node being an EVENT,
        // so the frame fell through to the opening-roster handler, which went looking for this
        // guild's three fixed opening characters: "已到初始角色列表底部，复核剩余目标 1/3".
        assertTrue(
            labyrinthEventFreeRoleSelectionOwnsFrame(
                pageState = LabyrinthEntryPageState.INITIAL_CHARACTER_SELECTION,
                activeNodeType = LabyrinthNodeTypes.SHOP,
                roleRewardPage = false,
                hasOpeningViewport = true,
                shopChoiceImprintPending = true,
            ),
        )
        // A 随机印记 grants its role outright, so nothing in the shop may claim this page.
        assertTrue(
            !labyrinthEventFreeRoleSelectionOwnsFrame(
                pageState = LabyrinthEntryPageState.INITIAL_CHARACTER_SELECTION,
                activeNodeType = LabyrinthNodeTypes.SHOP,
                roleRewardPage = false,
                hasOpeningViewport = true,
                shopChoiceImprintPending = false,
            ),
        )
        // The opening roster itself still runs before any node is active.
        assertTrue(
            !labyrinthEventFreeRoleSelectionOwnsFrame(
                pageState = LabyrinthEntryPageState.INITIAL_CHARACTER_SELECTION,
                activeNodeType = null,
                roleRewardPage = false,
                hasOpeningViewport = true,
                shopChoiceImprintPending = true,
            ),
        )
    }

    @Test
    fun `confirmed node transition survives transient unresolved destination frames`() {
        val confirmedAt = 10_000L
        listOf(
            LabyrinthEntryPageState.UNKNOWN,
            LabyrinthEntryPageState.NODE_MAP_VIEW,
            LabyrinthEntryPageState.GAME_LOADING_PROGRESS,
            LabyrinthEntryPageState.PRE_HOME_DATA_LOADING,
        ).forEach { page ->
            assertTrue(
                page.name,
                labyrinthPreservesPendingNodeTransitionAcrossPage(
                    pageState = page,
                    confirmationDispatchedAtMillis = confirmedAt,
                    nowMillis = confirmedAt + 5_999L,
                    timeoutMillis = 6_000L,
                ),
            )
        }
    }

    @Test
    fun `confirmed node transition expires and never survives a semantic destination page`() {
        val confirmedAt = 10_000L
        assertTrue(
            !labyrinthPreservesPendingNodeTransitionAcrossPage(
                pageState = LabyrinthEntryPageState.UNKNOWN,
                confirmationDispatchedAtMillis = confirmedAt,
                nowMillis = confirmedAt + 6_000L,
                timeoutMillis = 6_000L,
            ),
        )
        assertTrue(
            !labyrinthPreservesPendingNodeTransitionAcrossPage(
                pageState = LabyrinthEntryPageState.EVENT_CHOICE,
                confirmationDispatchedAtMillis = confirmedAt,
                nowMillis = confirmedAt + 500L,
                timeoutMillis = 6_000L,
            ),
        )
        assertTrue(
            !labyrinthPreservesPendingNodeTransitionAcrossPage(
                pageState = LabyrinthEntryPageState.UNKNOWN,
                confirmationDispatchedAtMillis = null,
                nowMillis = confirmedAt + 500L,
                timeoutMillis = 6_000L,
            ),
        )
    }

    @Test
    fun `tolerated entry mismatch keeps the pending transition across the page change`() {
        val confirmedAt = 10_000L
        // First mismatched frame: tolerance active, transition must survive to see the next frame.
        assertTrue(
            labyrinthPreservesPendingNodeTransitionForEntryMismatch(
                mismatchFrames = 1,
                stableFrames = 3,
                confirmationDispatchedAtMillis = confirmedAt,
                nowMillis = confirmedAt + 1_500L,
                timeoutMillis = 6_000L,
            ),
        )
        // No mismatch counted: nothing to protect.
        assertTrue(
            !labyrinthPreservesPendingNodeTransitionForEntryMismatch(
                mismatchFrames = 0,
                stableFrames = 3,
                confirmationDispatchedAtMillis = confirmedAt,
                nowMillis = confirmedAt + 1_500L,
                timeoutMillis = 6_000L,
            ),
        )
        // Tolerance exhausted: the mismatch branch stops the run itself.
        assertTrue(
            !labyrinthPreservesPendingNodeTransitionForEntryMismatch(
                mismatchFrames = 3,
                stableFrames = 3,
                confirmationDispatchedAtMillis = confirmedAt,
                nowMillis = confirmedAt + 1_500L,
                timeoutMillis = 6_000L,
            ),
        )
        // Entry timeout still bounds how long a tolerated mismatch may hold the transition.
        assertTrue(
            !labyrinthPreservesPendingNodeTransitionForEntryMismatch(
                mismatchFrames = 1,
                stableFrames = 3,
                confirmationDispatchedAtMillis = confirmedAt,
                nowMillis = confirmedAt + 6_000L,
                timeoutMillis = 6_000L,
            ),
        )
        // Falls back to the tap timestamp when the move dialog was never confirmed by us.
        assertTrue(
            labyrinthPreservesPendingNodeTransitionForEntryMismatch(
                mismatchFrames = 1,
                stableFrames = 3,
                confirmationDispatchedAtMillis = null,
                nowMillis = confirmedAt + 1_500L,
                timeoutMillis = 6_000L,
                dispatchedAtMillis = confirmedAt,
            ),
        )
    }

    @Test
    fun `lone semantic repair cannot move a target just bound by a strong full column`() {
        // 2026-09-16 19:17 bundle: link#30402 seen at (802,350) inside a full three-node column,
        // then a single link crop at (820,220) straddling rows 1/2 was adopted by semantic repair.
        val strongRect = EntryPixelRect(802, 350, 280, 350)
        val driftedRect = EntryPixelRect(820, 220, 280, 350)
        val boundAt = 100_000L
        assertTrue(
            labyrinthNodeClickContradictsRecentStrongBinding(
                rememberedRect = strongRect,
                rememberedAtMillis = boundAt,
                nowMillis = boundAt + 1_871L,
                memoryMillis = 8_000L,
                candidateRect = driftedRect,
                candidateHasStrongOrderedTopology = false,
            ),
        )
        // The same physical slot re-observed a few px off is not a contradiction.
        assertTrue(
            !labyrinthNodeClickContradictsRecentStrongBinding(
                rememberedRect = strongRect,
                rememberedAtMillis = boundAt,
                nowMillis = boundAt + 1_871L,
                memoryMillis = 8_000L,
                candidateRect = EntryPixelRect(820, 350, 280, 350),
                candidateHasStrongOrderedTopology = false,
            ),
        )
        // A new strong full-column binding may legitimately relocate the target.
        assertTrue(
            !labyrinthNodeClickContradictsRecentStrongBinding(
                rememberedRect = strongRect,
                rememberedAtMillis = boundAt,
                nowMillis = boundAt + 1_871L,
                memoryMillis = 8_000L,
                candidateRect = driftedRect,
                candidateHasStrongOrderedTopology = true,
            ),
        )
        // Memory expires; afterwards the ordinary stability gate is the only guard again.
        assertTrue(
            !labyrinthNodeClickContradictsRecentStrongBinding(
                rememberedRect = strongRect,
                rememberedAtMillis = boundAt,
                nowMillis = boundAt + 8_000L,
                memoryMillis = 8_000L,
                candidateRect = driftedRect,
                candidateHasStrongOrderedTopology = false,
            ),
        )
        // Nothing remembered: nothing to contradict.
        assertTrue(
            !labyrinthNodeClickContradictsRecentStrongBinding(
                rememberedRect = null,
                rememberedAtMillis = Long.MIN_VALUE,
                nowMillis = boundAt,
                memoryMillis = 8_000L,
                candidateRect = driftedRect,
                candidateHasStrongOrderedTopology = false,
            ),
        )
    }

    @Test
    fun `only a confident full column binding counts as strong ordered topology`() {
        fun mapping(
            kind: NodeTopologyBindingKind?,
            confidence: Double?,
            repair: Boolean = false,
        ) = NodePositionMapping(
            blockId = 30402L,
            blockType = LabyrinthNodeTypes.LINK,
            column = 4,
            row = 2,
            confidence = 0.66,
            screenRect = EntryPixelRect(802, 350, 280, 350),
            isClickable = true,
            topologyConfidence = confidence,
            topologyBindingKind = kind,
            routeSemanticRepair = repair,
        )
        assertTrue(labyrinthNodeMappingHasStrongOrderedTopology(mapping(NodeTopologyBindingKind.FULL_COLUMN, 0.99)))
        assertTrue(!labyrinthNodeMappingHasStrongOrderedTopology(mapping(NodeTopologyBindingKind.FULL_COLUMN, 0.89)))
        assertTrue(!labyrinthNodeMappingHasStrongOrderedTopology(mapping(NodeTopologyBindingKind.PARTIAL_COLUMN, 0.99)))
        assertTrue(!labyrinthNodeMappingHasStrongOrderedTopology(mapping(NodeTopologyBindingKind.SINGLE_TARGET, 0.99)))
        assertTrue(!labyrinthNodeMappingHasStrongOrderedTopology(mapping(null, null, repair = true)))
        assertTrue(!labyrinthNodeMappingHasStrongOrderedTopology(mapping(NodeTopologyBindingKind.FULL_COLUMN, 0.99, repair = true)))
    }

    @Test
    fun `forward map swipe stays inside the map and moves toward later columns`() {
        val swipe = labyrinthForwardMapSwipe(1920, 1080) as AutomationAction.Swipe

        // 0.8 of one 540 px column: centred on 0.56W, so 1075.2 +/- 216.
        assertEquals(1291.2f, swipe.start.x, 0.01f)
        assertEquals(626.4f, swipe.start.y, 0.01f)
        assertEquals(859.2f, swipe.end.x, 0.01f)
        assertEquals(626.4f, swipe.end.y, 0.01f)
        assertEquals(450L, swipe.durationMillis)
        assertEquals(220L, swipe.holdMillis)
    }

    @Test
    fun `one scan step never travels a whole node column`() {
        // 2026-09-18 bundle: the old 40%-of-frame drag moved 768 px against a ~513 px
        // measured pitch, so a column could cross the viewport between two scans and the
        // scanner reported geometryReliable=false. Every step must stay under one pitch.
        for ((w, h) in listOf(1920 to 1080, 2340 to 1080, 1280 to 720)) {
            val pitch = labyrinthReferenceColumnPitch(h)
            for (direction in LabyrinthMapScanDirection.values()) {
                val swipe = requireNotNull(labyrinthMapSwipe(w, h, direction)) as AutomationAction.Swipe
                val travel = kotlin.math.abs(swipe.start.x - swipe.end.x)
                assertTrue("${'$'}w x ${'$'}h travel=${'$'}travel pitch=${'$'}pitch", travel < pitch)
                assertTrue("${'$'}w x ${'$'}h start", swipe.start.x in 0f..w.toFloat())
                assertTrue("${'$'}w x ${'$'}h end", swipe.end.x in 0f..w.toFloat())
                // A drag that lifts at speed is turned into a fling by the game.
                assertTrue("${'$'}w x ${'$'}h hold", swipe.holdMillis > 0)
            }
        }
    }

    @Test
    fun `local nudging gives up after whole cycles instead of oscillating forever`() {
        // The cycle is BACKWARD, FORWARD, FORWARD, BACKWARD: it returns the camera to the
        // start, so repeating it cannot reveal anything new. Live bundles reached attempt
        // 90 and 170 on a single node.
        assertTrue(!labyrinthNodeRecoveryNudgeExhausted(0))
        assertTrue(!labyrinthNodeRecoveryNudgeExhausted(MAX_NODE_RECOVERY_NUDGES - 1))
        assertTrue(labyrinthNodeRecoveryNudgeExhausted(MAX_NODE_RECOVERY_NUDGES))
        assertTrue(labyrinthNodeRecoveryNudgeExhausted(90))
        // Whole cycles only, so the camera ends a round where it began.
        assertEquals(0, MAX_NODE_RECOVERY_NUDGES % 4)
    }

    @Test
    fun `map swipe moves its lane when the default release point overlaps a node`() {
        // Live regression: while searching for relic #30302, a BACKWARD swipe ended at
        // (1459,626), inside the visible event hitbox below, and the game opened that event.
        val eventRect = EntryPixelRect(left = 1335, top = 612, width = 280, height = 350)
        val swipe = labyrinthMapSwipe(
            frameWidth = 1920,
            frameHeight = 1080,
            direction = LabyrinthMapScanDirection.BACKWARD,
            avoidRects = listOf(eventRect),
        ) as AutomationAction.Swipe

        assertEquals(859.2f, swipe.start.x, 0.01f)
        assertEquals(1291.2f, swipe.end.x, 0.01f)
        // The shortened step no longer reaches this node's column at all.
        assertTrue(!eventRect.containsPoint(swipe.start.x, swipe.start.y))
        assertTrue(!eventRect.containsPoint(swipe.end.x, swipe.end.y))
        assertEquals(450L, swipe.durationMillis)

        // A node sitting on the new, shorter lane must still push the gesture to another row.
        val onLane = EntryPixelRect(left = 800, top = 560, width = 560, height = 340)
        val moved = labyrinthMapSwipe(
            frameWidth = 1920,
            frameHeight = 1080,
            direction = LabyrinthMapScanDirection.BACKWARD,
            avoidRects = listOf(onLane),
        ) as AutomationAction.Swipe
        assertTrue(!onLane.containsPoint(moved.start.x, moved.start.y))
        assertTrue(!onLane.containsPoint(moved.end.x, moved.end.y))
    }

    private fun EntryPixelRect.containsPoint(x: Float, y: Float): Boolean =
        x >= left && x <= left + width && y >= top && y <= top + height

    @Test
    fun `map recovery nudge is short alternating and stays clear of a visible node lane`() {
        val nodeRect = EntryPixelRect(left = 1320, top = 560, width = 300, height = 330)
        val forward = requireNotNull(
            labyrinthMapNudge(
                frameWidth = 1920,
                frameHeight = 1080,
                direction = LabyrinthMapScanDirection.FORWARD,
                avoidRects = listOf(nodeRect),
            ),
        ) as AutomationAction.Swipe
        val backward = requireNotNull(
            labyrinthMapNudge(
                frameWidth = 1920,
                frameHeight = 1080,
                direction = LabyrinthMapScanDirection.BACKWARD,
                avoidRects = listOf(nodeRect),
            ),
        ) as AutomationAction.Swipe

        assertTrue(forward.start.x > forward.end.x)
        assertTrue(backward.start.x < backward.end.x)
        assertTrue(kotlin.math.abs(forward.start.x - forward.end.x) < 1920 * 0.20f)
        assertTrue(kotlin.math.abs(backward.start.x - backward.end.x) < 1920 * 0.20f)
        assertTrue(forward.start.y < nodeRect.top || forward.start.y > nodeRect.top + nodeRect.height)
        assertEquals(220L, forward.durationMillis)
        assertEquals(220L, forward.holdMillis)
        assertEquals(
            listOf(
                LabyrinthMapScanDirection.BACKWARD,
                LabyrinthMapScanDirection.FORWARD,
                LabyrinthMapScanDirection.FORWARD,
                LabyrinthMapScanDirection.BACKWARD,
                LabyrinthMapScanDirection.BACKWARD,
            ),
            (0..4).map(::labyrinthNodeRecoveryNudgeDirection),
        )
    }

    @Test
    fun `backward map swipe reverses the segment direction without entering bottom controls`() {
        val swipe = labyrinthMapSwipe(
            frameWidth = 1920,
            frameHeight = 1080,
            direction = LabyrinthMapScanDirection.BACKWARD,
        ) as AutomationAction.Swipe

        assertEquals(859.2f, swipe.start.x, 0.01f)
        assertEquals(626.4f, swipe.start.y, 0.01f)
        assertEquals(1291.2f, swipe.end.x, 0.01f)
        assertEquals(626.4f, swipe.end.y, 0.01f)
        assertEquals(450L, swipe.durationMillis)
    }

    @Test
    fun `empty node classifications still produce a bounded scan toward the route target`() {
        val towardLaterColumn = labyrinthBlindMapScanPlan(
            currentNodeId = 20601,
            targetLogicalColumn = 7,
            completedAttempts = 0,
            reverseAfterAttempts = 5,
        )
        val reverseAfterHalfBudget = labyrinthBlindMapScanPlan(
            currentNodeId = 20601,
            targetLogicalColumn = 7,
            completedAttempts = 5,
            reverseAfterAttempts = 5,
        )

        assertEquals(LabyrinthMapScanDirection.FORWARD, towardLaterColumn.direction)
        assertEquals(LabyrinthMapScanDirection.BACKWARD, reverseAfterHalfBudget.direction)
        assertTrue(towardLaterColumn.reason.contains("blind-empty-classifications"))
    }

    @Test
    fun `6002 popup blocks normal actions and exposes the recognized return-title button`() {
        val returnRect = EntryPixelRect(left = 756, top = 692, width = 410, height = 94)
        val block = labyrinthSessionBlockObservation(
            frameResult(
                errorScore = 0.91,
                returnScore = 0.88,
                returnRect = returnRect,
            ),
        )

        assertEquals(SessionBlockKind.RECONNECT_PROMPTED, block.kind)
        assertTrue(block.blocksNormalActions)
        assertEquals(returnRect, block.returnTitleRect)
    }

    @Test
    fun `generic confirm dialog is never a session block even with a scoring title bar`() {
        // 2026-09-18 live scores: error title 0.64, 确认 button 0.96, no 返回标题, page NODE_SELECTION.
        val confirmRect = EntryPixelRect(left = 750, top = 690, width = 415, height = 102)
        val scores = LabyrinthAnchorScores(
            mapOf(
                EntryAnchorId.SESSION_ERROR_TITLE to 0.64,
                EntryAnchorId.SESSION_RETURN_TITLE to 0.0,
                EntryAnchorId.SESSION_DATE_CHANGE_TITLE to 0.47,
                EntryAnchorId.SESSION_DATE_CHANGE_CONFIRM to 0.96,
            ),
        )
        val result = LabyrinthEntryFrameResult(
            observation = LabyrinthEntryPageObservation(
                state = LabyrinthEntryPageState.NODE_SELECTION,
                confidence = 0.90,
                stateScores = mapOf(LabyrinthEntryPageState.NODE_SELECTION to 0.90),
                anchorScores = scores,
            ),
            matchedFeatures = emptyList(),
            elapsedMillis = 1,
            anchorMatches = mapOf(EntryAnchorId.SESSION_DATE_CHANGE_CONFIRM to EntryAnchorMatch(0.96, confirmRect)),
            frameWidth = 1920,
            frameHeight = 1080,
        )
        assertEquals(confirmRect, labyrinthGenericConfirmDialogRect(result))
        assertEquals(SessionBlockKind.NONE, labyrinthSessionBlockObservation(result).kind)

        // The real expiry popup keeps blocking: blue 返回标题 present, pale 确认 absent.
        val expiry = frameResult(errorScore = 0.91, returnScore = 0.88, returnRect = EntryPixelRect(756, 692, 410, 94))
        assertNull(labyrinthGenericConfirmDialogRect(expiry))
        assertEquals(SessionBlockKind.RECONNECT_PROMPTED, labyrinthSessionBlockObservation(expiry).kind)
    }

    @Test
    fun `shop transition frames are never a session block without an actionable return-title`() {
        // 2026-09-18 live: every purchase produced "检测到账号会话失效" because the shop dialog's
        // blue title bar scores on the broad reconnect template.
        fun shopFrame(returnScore: Double) = LabyrinthEntryFrameResult(
            observation = LabyrinthEntryPageObservation(
                state = LabyrinthEntryPageState.UNKNOWN,
                confidence = 0.0,
                stateScores = emptyMap(),
                anchorScores = LabyrinthAnchorScores(
                    mapOf(
                        EntryAnchorId.SHOP_TITLE to 0.99,
                        EntryAnchorId.SHOP_CLOSE to 1.0,
                        EntryAnchorId.SESSION_ERROR_TITLE to 0.55,
                        EntryAnchorId.SESSION_RETURN_TITLE to returnScore,
                    ),
                ),
            ),
            matchedFeatures = emptyList(),
            elapsedMillis = 1,
            anchorMatches = mapOf(
                EntryAnchorId.SESSION_RETURN_TITLE to EntryAnchorMatch(returnScore, EntryPixelRect(756, 692, 410, 94)),
            ),
            frameWidth = 1920,
            frameHeight = 1080,
        )

        assertEquals(SessionBlockKind.NONE, labyrinthSessionBlockObservation(shopFrame(0.10)).kind)
        // A real expiry over the shop still shows 返回标题 and stays actionable.
        assertEquals(SessionBlockKind.RECONNECT_PROMPTED, labyrinthSessionBlockObservation(shopFrame(0.88)).kind)
    }

    @Test
    fun `return-title button alone cannot trigger session recovery`() {
        val block = labyrinthSessionBlockObservation(
            frameResult(
                errorScore = 0.40,
                returnScore = 0.93,
                returnRect = EntryPixelRect(left = 756, top = 692, width = 410, height = 94),
            ),
        )

        assertEquals(SessionBlockKind.NONE, block.kind)
        assertNull(block.returnTitleRect)
    }

    @Test
    fun `date change popup uses confirm button and enters relogin recovery`() {
        val confirmRect = EntryPixelRect(left = 750, top = 690, width = 415, height = 102)
        val base = frameResult(
            errorScore = 0.0,
            returnScore = 0.0,
            returnRect = confirmRect,
        )
        val result = base.copy(
            observation = base.observation.copy(
                anchorScores = LabyrinthAnchorScores(
                    mapOf(
                        EntryAnchorId.SESSION_DATE_CHANGE_TITLE to 0.91,
                        EntryAnchorId.SESSION_DATE_CHANGE_CONFIRM to 0.88,
                    ),
                ),
            ),
            anchorMatches = mapOf(
                EntryAnchorId.SESSION_DATE_CHANGE_CONFIRM to EntryAnchorMatch(0.88, confirmRect),
            ),
        )

        val block = labyrinthSessionBlockObservation(result)

        assertEquals(SessionBlockKind.RELOGIN_REQUIRED, block.kind)
        assertEquals(confirmRect, block.returnTitleRect)
        assertEquals("确认", block.actionLabel)
    }

    @Test
    fun `specific movement confirmation outranks the generic reconnect title`() {
        val confirmRect = EntryPixelRect(left = 975, top = 690, width = 415, height = 105)
        val result = frameResult(
            errorScore = 0.634,
            returnScore = 0.156,
            returnRect = EntryPixelRect(left = 756, top = 692, width = 410, height = 94),
        ).copy(
            nodeMoveConfirmation = LabyrinthNodeMoveConfirmationObservation(
                confidence = 0.895,
                confirmButtonRect = confirmRect,
                cancelButtonRect = EntryPixelRect(left = 545, top = 690, width = 415, height = 105),
            ),
        )

        val block = labyrinthSessionBlockObservation(result)

        assertEquals(SessionBlockKind.NONE, block.kind)
        assertTrue(!block.blocksNormalActions)
        assertNull(block.returnTitleRect)
    }

    @Test
    fun `node route advances only after a recognized destination page`() {
        assertTrue(labyrinthConfirmsNodeEntry(LabyrinthEntryPageState.BATTLE_CHALLENGE))
        assertTrue(labyrinthConfirmsNodeEntry(LabyrinthEntryPageState.LINK_CHOICE))
        assertTrue(labyrinthConfirmsNodeEntry(LabyrinthEntryPageState.RELIC_CHOICE))
        assertTrue(!labyrinthConfirmsNodeEntry(LabyrinthEntryPageState.NODE_SELECTION))
        assertTrue(!labyrinthConfirmsNodeEntry(LabyrinthEntryPageState.UNKNOWN))
    }

    @Test
    fun `one slow map frame cannot expire a pending movement confirmation`() {
        assertTrue(
            labyrinthKeepsPendingNodeTransition(
                elapsedMillis = 6_004L,
                timeoutMillis = 6_000L,
                nodeSelectionFramesSinceAction = 1,
            ),
        )
        assertTrue(
            labyrinthKeepsPendingNodeTransition(
                elapsedMillis = 5_999L,
                timeoutMillis = 6_000L,
                nodeSelectionFramesSinceAction = 2,
            ),
        )
        assertTrue(
            !labyrinthKeepsPendingNodeTransition(
                elapsedMillis = 6_004L,
                timeoutMillis = 6_000L,
                nodeSelectionFramesSinceAction = 2,
            ),
        )
    }

    @Test
    fun `recognized destination must match the clicked node type`() {
        assertTrue(
            labyrinthNodeEntryMatchesExpectedType(
                LabyrinthNodeTypes.NORMAL_BATTLE,
                LabyrinthEntryPageState.BATTLE_CHALLENGE,
            ),
        )
        assertTrue(
            !labyrinthNodeEntryMatchesExpectedType(
                LabyrinthNodeTypes.NORMAL_BATTLE,
                LabyrinthEntryPageState.RELIC_CHOICE,
            ),
        )
        assertTrue(
            labyrinthNodeEntryMatchesExpectedType(
                LabyrinthNodeTypes.EVENT,
                LabyrinthEntryPageState.ITEM_REWARD,
            ),
        )
    }

    @Test
    fun `stable unexpected shop has an actionable actual destination type`() {
        assertTrue(!labyrinthNodeEntryMatchesExpectedType(
            LabyrinthNodeTypes.EVENT, LabyrinthEntryPageState.SHOP,
        ))
        assertEquals(LabyrinthNodeTypes.SHOP,
            labyrinthObservedDestinationType(LabyrinthEntryPageState.SHOP))
        assertEquals(null,
            labyrinthObservedDestinationType(LabyrinthEntryPageState.ITEM_REWARD))
        assertEquals(null,
            labyrinthObservedDestinationType(LabyrinthEntryPageState.UNKNOWN))
        assertEquals(
            LabyrinthNodeTypes.BOSS,
            labyrinthObservedDestinationType(
                LabyrinthEntryPageState.BATTLE_IN_PROGRESS,
                LabyrinthNodeTypes.BOSS,
            ),
        )
        assertEquals(
            LabyrinthNodeTypes.EX_BATTLE,
            labyrinthObservedDestinationType(
                LabyrinthEntryPageState.BATTLE_TEAM_SELECTION,
                LabyrinthNodeTypes.EX_BATTLE,
            ),
        )
    }

    @Test
    fun `link node entry is confirmed anywhere along its reward chain`() {
        // LINK_CHOICE can be gone before the next sampled frame; the three-character
        // ITEM_REWARD and the role reward pages that follow still prove the link node was entered.
        for (page in listOf(
            LabyrinthEntryPageState.LINK_CHOICE,
            LabyrinthEntryPageState.ITEM_REWARD,
            LabyrinthEntryPageState.INITIAL_CHARACTER_SELECTION,
            LabyrinthEntryPageState.CHARACTER_JOINED,
        )) {
            assertTrue(page.name, labyrinthNodeEntryMatchesExpectedType(LabyrinthNodeTypes.LINK, page))
        }
        for (page in listOf(
            LabyrinthEntryPageState.RELIC_CHOICE,
            LabyrinthEntryPageState.EVENT_CHOICE,
            LabyrinthEntryPageState.NODE_SELECTION,
            LabyrinthEntryPageState.UNKNOWN,
        )) {
            assertTrue(page.name, !labyrinthNodeEntryMatchesExpectedType(LabyrinthNodeTypes.LINK, page))
        }
        // Relic stays strict: its item reward is indistinguishable from a map reward popup.
        assertTrue(
            !labyrinthNodeEntryMatchesExpectedType(
                LabyrinthNodeTypes.RELIC,
                LabyrinthEntryPageState.ITEM_REWARD,
            ),
        )
    }

    @Test
    fun `node viewport signature ignores tiny matcher jitter but detects map movement`() {
        fun node(left: Int) = NodeClassification(
            column = 2,
            row = 1,
            blockType = 2,
            confidence = 0.9,
            isClickable = true,
            screenRect = EntryPixelRect(left, 200, 160, 180),
        )

        assertEquals(
            labyrinthNodeViewportSignature(listOf(node(500))),
            labyrinthNodeViewportSignature(listOf(node(508))),
        )
        assertTrue(
            labyrinthNodeViewportSignature(listOf(node(500))) !=
                labyrinthNodeViewportSignature(listOf(node(420))),
        )
    }

    @Test
    fun `node target box is hidden while clicks are enabled`() {
        assertTrue(labyrinthShowsNextNodeOverlayBox(dryRun = true))
        assertTrue(!labyrinthShowsNextNodeOverlayBox(dryRun = false))
    }

    @Test
    fun `node diagnostics are visible only while the session is paused`() {
        assertTrue(labyrinthShowsPausedNodeDiagnostics(paused = true))
        assertTrue(!labyrinthShowsPausedNodeDiagnostics(paused = false))
    }

    private fun frameResult(
        errorScore: Double,
        returnScore: Double,
        returnRect: EntryPixelRect,
    ): LabyrinthEntryFrameResult {
        val scores = LabyrinthAnchorScores(
            mapOf(
                EntryAnchorId.SESSION_ERROR_TITLE to errorScore,
                EntryAnchorId.SESSION_RETURN_TITLE to returnScore,
            ),
        )
        return LabyrinthEntryFrameResult(
            observation = LabyrinthEntryPageObservation(
                state = LabyrinthEntryPageState.HOME,
                confidence = 0.90,
                stateScores = mapOf(LabyrinthEntryPageState.HOME to 0.90),
                anchorScores = scores,
            ),
            matchedFeatures = emptyList(),
            elapsedMillis = 1,
            anchorMatches = mapOf(
                EntryAnchorId.SESSION_RETURN_TITLE to EntryAnchorMatch(returnScore, returnRect),
            ),
            frameWidth = 1920,
            frameHeight = 1080,
        )
    }

    @Test
    fun `effective sweep survives only the EX to Boss hand-off of one encounter`() {
        assertTrue(labyrinthKeepsEffectiveScanAcrossNodes(LabyrinthNodeTypes.EX_BATTLE, LabyrinthNodeTypes.BOSS))
        assertTrue(!labyrinthKeepsEffectiveScanAcrossNodes(LabyrinthNodeTypes.BOSS, LabyrinthNodeTypes.EX_BATTLE))
        assertTrue(!labyrinthKeepsEffectiveScanAcrossNodes(LabyrinthNodeTypes.EX_BATTLE, LabyrinthNodeTypes.EX_BATTLE))
        assertTrue(!labyrinthKeepsEffectiveScanAcrossNodes(LabyrinthNodeTypes.NORMAL_BATTLE, LabyrinthNodeTypes.BOSS))
        assertTrue(!labyrinthKeepsEffectiveScanAcrossNodes(null, LabyrinthNodeTypes.BOSS))

        // Order-independent, and any roster change invalidates the saved sweep.
        assertEquals(
            labyrinthEffectiveScanRosterStamp(listOf("1001", "1002", "1003")),
            labyrinthEffectiveScanRosterStamp(listOf("1003", "1001", "1002")),
        )
        assertTrue(
            labyrinthEffectiveScanRosterStamp(listOf("1001", "1002")) !=
                labyrinthEffectiveScanRosterStamp(listOf("1001", "1002", "1003")),
        )
        assertTrue(labyrinthEffectiveScanRosterStamp(emptyList()) != labyrinthEffectiveScanRosterStamp(listOf("1001")))
    }

    @Test
    fun `every route page that shows the same status with no action eventually stops`() {
        // Page-level retry budgets only ever counted *taps*. A page that decides "wait" dispatches
        // nothing, so it incremented nothing and no deadline applied: before this watchdog only
        // UNKNOWN had one, and every other wait branch could hold a run open for ever.
        assertFalse(labyrinthPageStallExpired(POST_ENTRY_STALL_TIMEOUT_MILLIS - 1, battlePage = false))
        assertTrue(labyrinthPageStallExpired(POST_ENTRY_STALL_TIMEOUT_MILLIS, battlePage = false))
        // Battle is the one page whose legitimate silence is longer; it runs against the separate
        // 5-minute battle-wait deadline instead.
        assertFalse(labyrinthPageStallExpired(POST_ENTRY_STALL_TIMEOUT_MILLIS * 10, battlePage = true))
        // There must be no second exemption here. A "waiting for manual character selection"
        // carve-out used to swallow exactly the roster-page hang this watchdog exists for, because
        // INITIAL_CHARACTER_SELECTION always falls back to that message even though this build
        // never enables manual selection.
        assertTrue(
            "stall budget must exceed the shop OCR backstop by a wide margin",
            POST_ENTRY_STALL_TIMEOUT_MILLIS >= 90_000L,
        )
    }

    @Test
    fun `an action that never returns releases the latch instead of freezing the session`() {
        // Every frame handler bails out while the single-action latch is held, so a latch that is
        // claimed and never released is a silent, total freeze: no clicks, no messages, and no
        // other timeout can fire because no other timeout is ever evaluated.
        assertFalse(labyrinthActionLatchStuck(Long.MIN_VALUE, nowMillis = Long.MAX_VALUE / 2))
        assertFalse(labyrinthActionLatchStuck(heldSinceMillis = 0, nowMillis = ACTION_LATCH_STUCK_TIMEOUT_MILLIS - 1))
        assertTrue(labyrinthActionLatchStuck(heldSinceMillis = 0, nowMillis = ACTION_LATCH_STUCK_TIMEOUT_MILLIS))
        // A real gesture returns in well under a second, so the budget is orders of magnitude
        // above normal and only ever trips on a genuinely dead executor.
        assertTrue(ACTION_LATCH_STUCK_TIMEOUT_MILLIS >= 30_000L)
    }

    @Test
    fun `a mid-route roster picker is answered instead of reclaiming the entry phase`() {
        // The opening selector, a shop 选择印记 pick and an event free pick are the same page with
        // the same header. Only the first belongs to the entry planner, and confusing them parked
        // a run for 408 s: the picker reset the session to the entry phase, and the entry planner
        // has no rule for a picker, so nothing owned the frame at all.
        val picker = LabyrinthEntryFrameResult(
            observation = LabyrinthEntryPageObservation(
                state = LabyrinthEntryPageState.INITIAL_CHARACTER_SELECTION,
                confidence = 0.99,
                stateScores = mapOf(LabyrinthEntryPageState.INITIAL_CHARACTER_SELECTION to 0.99),
                anchorScores = LabyrinthAnchorScores(
                    mapOf(EntryAnchorId.SELECTION_HEADER_STANDARD to 0.99),
                ),
            ),
            matchedFeatures = emptyList(),
            elapsedMillis = 1,
        )
        assertTrue(labyrinthShouldResumeOpeningSelection(true, picker, routeActive = false))
        assertFalse(
            "a picker opened mid-route is never the opening selector",
            labyrinthShouldResumeOpeningSelection(true, picker, routeActive = true),
        )

        // Ownership must not depend on remembering which node opened it: that memory is exactly
        // what the page churn of a purchase destroys.
        fun owns(nodeType: Int?, imprintPending: Boolean, routeActive: Boolean) =
            labyrinthEventFreeRoleSelectionOwnsFrame(
                pageState = LabyrinthEntryPageState.INITIAL_CHARACTER_SELECTION,
                activeNodeType = nodeType,
                roleRewardPage = false,
                hasOpeningViewport = true,
                shopChoiceImprintPending = imprintPending,
                routeActive = routeActive,
            )
        assertTrue(owns(null, imprintPending = false, routeActive = true))
        assertTrue(owns(LabyrinthNodeTypes.EVENT, imprintPending = false, routeActive = false))
        assertTrue(owns(LabyrinthNodeTypes.SHOP, imprintPending = true, routeActive = false))
        // Before the route starts the page really is the opening selector; leave it alone.
        assertFalse(owns(null, imprintPending = false, routeActive = false))
        // A three-card reward page is never this picker.
        assertFalse(
            labyrinthEventFreeRoleSelectionOwnsFrame(
                pageState = LabyrinthEntryPageState.INITIAL_CHARACTER_SELECTION,
                activeNodeType = null,
                roleRewardPage = true,
                hasOpeningViewport = true,
                routeActive = true,
            ),
        )
    }

    @Test
    fun `a stalled event free-role page stops instead of hanging forever`() {
        // The page is answered from whatever is on screen, so a stall is only ever "still
        // settling" or "genuinely stuck"; there is no roster walk to attempt in between.
        assertEquals(
            LabyrinthEventFreeRoleBlockedAction.WAIT,
            labyrinthEventFreeRoleBlockedAction(0),
        )
        assertEquals(
            LabyrinthEventFreeRoleBlockedAction.WAIT,
            labyrinthEventFreeRoleBlockedAction(EVENT_FREE_ROLE_SETTLE_MILLIS),
        )
        assertEquals(
            LabyrinthEventFreeRoleBlockedAction.WAIT,
            labyrinthEventFreeRoleBlockedAction(EVENT_FREE_ROLE_GIVE_UP_MILLIS - 1),
        )
        // Past the give-up window it stops with a reason: this page has no other timeout, and an
        // unreadable portrait used to park the run here silently and indefinitely.
        assertEquals(
            LabyrinthEventFreeRoleBlockedAction.STOP,
            labyrinthEventFreeRoleBlockedAction(EVENT_FREE_ROLE_GIVE_UP_MILLIS),
        )
    }

    @Test
    fun `node scan is deferred while the map settles or a node tap is still pending`() {
        assertTrue(labyrinthNodeScanConsumable(settleRemainingMillis = 0L, pendingTransitionHeld = false))
        assertTrue(labyrinthNodeScanConsumable(settleRemainingMillis = -1L, pendingTransitionHeld = false))
        assertTrue(!labyrinthNodeScanConsumable(settleRemainingMillis = 1L, pendingTransitionHeld = false))
        assertTrue(!labyrinthNodeScanConsumable(settleRemainingMillis = 0L, pendingTransitionHeld = true))
    }

    @Test
    fun `post entry cooldown counts from the moment the tap landed`() {
        // Bundle 225220 row 249/251: the deciding frame was captured at t=0, the close gesture
        // landed 750 ms later, and the next popup frame was captured 292 ms after the gesture.
        assertTrue(
            labyrinthPostEntryCooldownRemaining(
                frameTimestampMillis = 1_042L,
                dispatchedAtMillis = 0L,
                landedAtMillis = 750L,
                intervalMillis = 650L,
            ) > 0L,
        )
        // 650 ms after the landing the popup has had its whole cooldown.
        assertEquals(
            0L,
            labyrinthPostEntryCooldownRemaining(
                frameTimestampMillis = 1_400L,
                dispatchedAtMillis = 0L,
                landedAtMillis = 750L,
                intervalMillis = 650L,
            ),
        )
        // A rejected or still-in-flight tap has no landing time: the old frame-stamp rule holds.
        assertEquals(
            150L,
            labyrinthPostEntryCooldownRemaining(
                frameTimestampMillis = 500L,
                dispatchedAtMillis = 0L,
                landedAtMillis = Long.MIN_VALUE,
                intervalMillis = 650L,
            ),
        )
        assertEquals(
            0L,
            labyrinthPostEntryCooldownRemaining(
                frameTimestampMillis = 500L,
                dispatchedAtMillis = Long.MIN_VALUE,
                landedAtMillis = Long.MIN_VALUE,
                intervalMillis = 650L,
            ),
        )
    }
}
