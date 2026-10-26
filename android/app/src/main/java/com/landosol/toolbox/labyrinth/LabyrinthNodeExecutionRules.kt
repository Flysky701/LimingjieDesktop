package com.landosol.toolbox.labyrinth

import com.landosol.toolbox.automation.AutomationAction
import com.landosol.toolbox.automation.ScreenPoint
import com.landosol.toolbox.labyrinth.vision.EntryPixelRect
import com.landosol.toolbox.labyrinth.vision.LabyrinthEntryPageState
import com.landosol.toolbox.labyrinth.node.LabyrinthMapScanDirection
import com.landosol.toolbox.labyrinth.node.LabyrinthNodeScanPlan
import com.landosol.toolbox.labyrinth.node.labyrinthReferenceColumnPitch
import com.landosol.toolbox.labyrinth.node.LabyrinthNodeTypes
import com.landosol.toolbox.labyrinth.node.NodeClassification
import com.landosol.toolbox.labyrinth.node.NodePositionMapping
import com.landosol.toolbox.labyrinth.node.NodeTopologyBindingKind

private const val NODE_VIEWPORT_SIGNATURE_BUCKET_PX = 32

private const val MAP_SCROLL_DURATION_MILLIS = 450L

/** Drag one viewport-sized segment while staying clear of the persistent bottom controls. */
internal fun labyrinthMapSwipe(
    frameWidth: Int,
    frameHeight: Int,
    direction: LabyrinthMapScanDirection,
    avoidRects: List<EntryPixelRect> = emptyList(),
): AutomationAction.Swipe? {
    if (frameWidth <= 0 || frameHeight <= 0) return null
    // Travel is measured in node columns, not in screen widths. The old gesture dragged 40% of
    // the frame (768 px at 1920) while one column is ~540 px, so a single swipe jumped 1.4
    // columns and could carry a node straight through the viewport; the scanner then could not
    // attribute what had moved (geometryReliable=false, uncertainSwipes) and the search burned
    // its budget. Staying under one pitch makes every column pass in front of at least one scan.
    val pitch = labyrinthReferenceColumnPitch(frameHeight)
    val travel = (pitch * MAP_SCROLL_PITCH_RATIO)
        .coerceAtMost(frameWidth * MAP_SCROLL_MAX_FRAME_RATIO.toDouble())
        .toFloat()
    val centerPx = frameWidth * 0.56f
    val (startPx, endPx) = when (direction) {
        LabyrinthMapScanDirection.FORWARD -> (centerPx + travel / 2f) to (centerPx - travel / 2f)
        LabyrinthMapScanDirection.BACKWARD -> (centerPx - travel / 2f) to (centerPx + travel / 2f)
    }
    val yRatios = listOf(0.58f, 0.46f, 0.34f, 0.70f, 0.24f)
    val y = yRatios
        .map { ratio -> frameHeight * ratio }
        .minByOrNull { candidateY ->
            avoidRects.sumOf { rect ->
                fun pointPenalty(x: Float): Int {
                    val margin = maxOf(24, minOf(rect.width, rect.height) / 12)
                    val insideX = x >= rect.left - margin && x <= rect.left + rect.width + margin
                    val insideY = candidateY >= rect.top - margin && candidateY <= rect.top + rect.height + margin
                    return if (insideX && insideY) 1 else 0
                }
                pointPenalty(startPx) + pointPenalty(endPx)
            }
        }
        ?: frameHeight * 0.58f
    return AutomationAction.Swipe(
        start = ScreenPoint(startPx, y),
        end = ScreenPoint(endPx, y),
        durationMillis = MAP_SCROLL_DURATION_MILLIS,
        holdMillis = MAP_GESTURE_HOLD_MILLIS,
    )
}

/** One scan step stays below a single column pitch so no column can slip past unscanned. */
private const val MAP_SCROLL_PITCH_RATIO = 0.80
/** Never exceed this share of the frame on unusually tall/narrow captures. */
private const val MAP_SCROLL_MAX_FRAME_RATIO = 0.34f
/** Hold still before lifting so the game reads ~0 velocity and does not fling the map. */
internal const val MAP_GESTURE_HOLD_MILLIS = 220L

private const val MAP_NUDGE_DURATION_MILLIS = 220L

/**
 * Small side-lane camera perturbation used when the route target should already be nearby but the
 * current background makes classification unstable. Unlike the segmented map scan this gesture
 * deliberately stays near one side and moves only a small fraction of the viewport.
 */
internal fun labyrinthMapNudge(
    frameWidth: Int,
    frameHeight: Int,
    direction: LabyrinthMapScanDirection,
    avoidRects: List<EntryPixelRect> = emptyList(),
): AutomationAction.Swipe? {
    if (frameWidth <= 0 || frameHeight <= 0) return null
    val (startX, endX) = when (direction) {
        LabyrinthMapScanDirection.FORWARD -> 0.82f to 0.70f
        LabyrinthMapScanDirection.BACKWARD -> 0.18f to 0.30f
    }
    val startPx = frameWidth * startX
    val endPx = frameWidth * endX
    val minX = minOf(startPx, endPx)
    val maxX = maxOf(startPx, endPx)
    val y = listOf(0.58f, 0.46f, 0.34f, 0.70f, 0.24f)
        .map { frameHeight * it }
        .minByOrNull { candidateY ->
            avoidRects.count { rect ->
                val margin = maxOf(24, minOf(rect.width, rect.height) / 12)
                val crossesX = maxX >= rect.left - margin && minX <= rect.left + rect.width + margin
                val crossesY = candidateY >= rect.top - margin && candidateY <= rect.top + rect.height + margin
                crossesX && crossesY
            }
        } ?: frameHeight * 0.58f
    return AutomationAction.Swipe(
        start = ScreenPoint(startPx, y),
        end = ScreenPoint(endPx, y),
        durationMillis = MAP_NUDGE_DURATION_MILLIS,
        holdMillis = MAP_GESTURE_HOLD_MILLIS,
    )
}

/** center -> left -> center -> right -> center, then repeat. */
/**
 * The nudge cycle is BACKWARD, FORWARD, FORWARD, BACKWARD: it returns the camera to where it
 * started, so a further cycle cannot expose anything the previous one failed to classify.
 * 2026-09-15/18 bundles show it repeating 90 and 170 times on one node. Stop after a whole
 * number of cycles and re-arm the segmented scan, which does move the camera somewhere new.
 */
internal fun labyrinthNodeRecoveryNudgeExhausted(
    completedNudges: Int,
    maximumNudges: Int = MAX_NODE_RECOVERY_NUDGES,
): Boolean = completedNudges >= maximumNudges

/** Whole nudge cycles (multiple of 4) before the search falls back to scrolling again. */
internal const val MAX_NODE_RECOVERY_NUDGES = 8

internal fun labyrinthNodeRecoveryNudgeDirection(completedNudges: Int): LabyrinthMapScanDirection {
    require(completedNudges >= 0)
    return when (completedNudges % 4) {
        0, 3 -> LabyrinthMapScanDirection.BACKWARD
        else -> LabyrinthMapScanDirection.FORWARD
    }
}

/** Compatibility helper retained for policy tests and callers that explicitly need later columns. */
internal fun labyrinthForwardMapSwipe(frameWidth: Int, frameHeight: Int): AutomationAction.Swipe? =
    labyrinthMapSwipe(frameWidth, frameHeight, LabyrinthMapScanDirection.FORWARD)

/**
 * Bounded fallback when no visual node survives classification and therefore no viewport
 * signature exists. Follow the protocol column first, then reverse for the second half of the
 * retry budget so an unknown camera position cannot leave execution waiting forever.
 */
internal fun labyrinthBlindMapScanPlan(
    currentNodeId: Long,
    targetLogicalColumn: Int,
    completedAttempts: Int,
    reverseAfterAttempts: Int,
): LabyrinthNodeScanPlan {
    require(completedAttempts >= 0)
    require(reverseAfterAttempts > 0)
    val currentLogicalColumn = (currentNodeId / 100L % 100L).toInt()
    val preferred = when {
        targetLogicalColumn > currentLogicalColumn -> LabyrinthMapScanDirection.FORWARD
        targetLogicalColumn < currentLogicalColumn -> LabyrinthMapScanDirection.BACKWARD
        else -> LabyrinthMapScanDirection.FORWARD
    }
    val direction = if (completedAttempts < reverseAfterAttempts) {
        preferred
    } else {
        when (preferred) {
            LabyrinthMapScanDirection.FORWARD -> LabyrinthMapScanDirection.BACKWARD
            LabyrinthMapScanDirection.BACKWARD -> LabyrinthMapScanDirection.FORWARD
        }
    }

    return LabyrinthNodeScanPlan(
        direction = direction,
        reason = "blind-empty-classifications currentColumn=$currentLogicalColumn " +
            "targetColumn=$targetLogicalColumn completed=$completedAttempts",
    )
}

/** Only a recognized destination page proves that the game accepted a map-node tap. */
internal fun labyrinthConfirmsNodeEntry(state: LabyrinthEntryPageState): Boolean = when (state) {
    LabyrinthEntryPageState.CHARACTER_JOINED,
    LabyrinthEntryPageState.INITIAL_CHARACTER_SELECTION,
    LabyrinthEntryPageState.LINK_CHOICE,
    LabyrinthEntryPageState.RELIC_CHOICE,
    LabyrinthEntryPageState.EVENT_CHOICE,
    LabyrinthEntryPageState.EVENT_ANIMATION,
    LabyrinthEntryPageState.ITEM_REWARD,
    LabyrinthEntryPageState.SHOP,
    LabyrinthEntryPageState.SHOP_PURCHASE_CONFIRMATION,
    LabyrinthEntryPageState.SHOP_PURCHASE_COMPLETE,
    LabyrinthEntryPageState.SHOP_EXIT_CONFIRMATION,
    LabyrinthEntryPageState.BATTLE_CHALLENGE,
    LabyrinthEntryPageState.BATTLE_TEAM_SELECTION,
    LabyrinthEntryPageState.BATTLE_IN_PROGRESS,
    LabyrinthEntryPageState.BATTLE_RESULT,
    LabyrinthEntryPageState.RUN_CLEAR_RESULT,
    LabyrinthEntryPageState.RUN_CLEAR_CONGRATULATIONS,
    LabyrinthEntryPageState.RUN_CLEAR_CHARACTER_SUMMARY,
    LabyrinthEntryPageState.RUN_CLEAR_REWARD_ANIMATION,
    LabyrinthEntryPageState.RUN_CLEAR_CHEST_ANIMATION,
    LabyrinthEntryPageState.RUN_CLEAR_CHEST_RESULT,
    -> true

    else -> false
}

/**
 * A generic recognized page is not enough to advance the route: the destination must also be
 * compatible with the node that was clicked. This protects the route cursor from stale popups
 * and from a map false positive that happens to be followed by another page.
 */
internal fun labyrinthNodeEntryMatchesExpectedType(
    blockType: Int,
    pageState: LabyrinthEntryPageState,
): Boolean = when (blockType) {
    LabyrinthNodeTypes.NORMAL_BATTLE,
    LabyrinthNodeTypes.EX_BATTLE,
    LabyrinthNodeTypes.BOSS,
    -> pageState in setOf(
        LabyrinthEntryPageState.BATTLE_CHALLENGE,
        LabyrinthEntryPageState.BATTLE_TEAM_SELECTION,
        LabyrinthEntryPageState.BATTLE_IN_PROGRESS,
        LabyrinthEntryPageState.BATTLE_FAILED,
        LabyrinthEntryPageState.BATTLE_RESULT,
    )

    // A link node opens LINK_CHOICE, but that page can be gone before the next sampled frame:
    // the imprint pick is followed by the three-character ITEM_REWARD popup and then the role
    // reward selection. Those pages are already accepted by labyrinthConfirmsNodeEntry; refusing
    // them here left the route cursor one node behind while the reward chain kept running
    // (2026-09-16 11:29 bundle: link#20601 cleared, role picked, cursor still at 20501).
    LabyrinthNodeTypes.LINK -> pageState in setOf(
        LabyrinthEntryPageState.LINK_CHOICE,
        LabyrinthEntryPageState.ITEM_REWARD,
        LabyrinthEntryPageState.INITIAL_CHARACTER_SELECTION,
        LabyrinthEntryPageState.CHARACTER_JOINED,
    )
    LabyrinthNodeTypes.RELIC -> pageState == LabyrinthEntryPageState.RELIC_CHOICE
    LabyrinthNodeTypes.EVENT -> pageState in setOf(
        LabyrinthEntryPageState.EVENT_CHOICE,
        LabyrinthEntryPageState.EVENT_ANIMATION,
        LabyrinthEntryPageState.INITIAL_CHARACTER_SELECTION,
        LabyrinthEntryPageState.CHARACTER_JOINED,
        LabyrinthEntryPageState.ITEM_REWARD,
    )
    LabyrinthNodeTypes.SHOP -> pageState in setOf(
        LabyrinthEntryPageState.SHOP,
        LabyrinthEntryPageState.SHOP_PURCHASE_CONFIRMATION,
        LabyrinthEntryPageState.SHOP_PURCHASE_COMPLETE,
        LabyrinthEntryPageState.SHOP_EXIT_CONFIRMATION,
    )
    else -> false
}

/** Only a stable, actionable destination may override stale route node metadata. */
internal fun labyrinthObservedDestinationType(
    state: LabyrinthEntryPageState,
    expectedType: Int? = null,
): Int? = when (state) {
    LabyrinthEntryPageState.SHOP,
    LabyrinthEntryPageState.SHOP_PURCHASE_CONFIRMATION,
    LabyrinthEntryPageState.SHOP_PURCHASE_COMPLETE,
    LabyrinthEntryPageState.SHOP_EXIT_CONFIRMATION -> LabyrinthNodeTypes.SHOP
    LabyrinthEntryPageState.EVENT_CHOICE,
    LabyrinthEntryPageState.EVENT_ANIMATION -> LabyrinthNodeTypes.EVENT
    LabyrinthEntryPageState.LINK_CHOICE -> LabyrinthNodeTypes.LINK
    LabyrinthEntryPageState.RELIC_CHOICE -> LabyrinthNodeTypes.RELIC
    LabyrinthEntryPageState.BATTLE_CHALLENGE,
    LabyrinthEntryPageState.BATTLE_TEAM_SELECTION,
    LabyrinthEntryPageState.BATTLE_IN_PROGRESS ->
        expectedType.takeIf { it in setOf(LabyrinthNodeTypes.BOSS, LabyrinthNodeTypes.EX_BATTLE) }
            ?: LabyrinthNodeTypes.NORMAL_BATTLE
    // Generic reward/transition screens do not identify the node that produced them.
    else -> null
}

/** Coarse signature used to detect that a map swipe produced no viewport movement. */
internal fun labyrinthNodeViewportSignature(classifications: List<NodeClassification>): String =
    classifications
        .mapNotNull { node ->
            node.screenRect?.let { rect ->
                listOf(
                    node.blockType,
                    if (node.isClickable) 1 else 0,
                    (rect.left + rect.width / 2) / NODE_VIEWPORT_SIGNATURE_BUCKET_PX,
                    (rect.top + rect.height / 2) / NODE_VIEWPORT_SIGNATURE_BUCKET_PX,
                ).joinToString(":")
            }
        }
        .sorted()
        .joinToString("|")

/** Only read-only diagnostics may draw the normal target box into frames captured by MediaProjection. */
internal fun labyrinthShowsNextNodeOverlayBox(dryRun: Boolean): Boolean = dryRun

/**
 * Node diagnostics are drawn only while the session is paused. While running, MediaProjection
 * can capture the annotation layer and feed its colored boxes back into node recognition.
 */
internal fun labyrinthShowsPausedNodeDiagnostics(paused: Boolean): Boolean = paused

private const val MIN_NODE_SELECTION_FRAMES_BEFORE_TRANSITION_RETRY = 2

/**
 * Node recognition can take roughly as long as the transition timeout on a dense map. One stale
 * map frame captured around the tap must not erase the pending transition before the next frame
 * can observe the movement dialog.
 */
internal fun labyrinthKeepsPendingNodeTransition(
    elapsedMillis: Long,
    timeoutMillis: Long,
    nodeSelectionFramesSinceAction: Int,
): Boolean =
    elapsedMillis < timeoutMillis ||
        nodeSelectionFramesSinceAction < MIN_NODE_SELECTION_FRAMES_BEFORE_TRANSITION_RETRY

/**
 * Whether the completed "有效效果" roster sweep of the node being left is still the answer for the
 * node being entered. The sweep is a property of the encounter, which an EX fight and its Boss
 * share; any other transition is a different encounter or no encounter at all.
 */
internal fun labyrinthKeepsEffectiveScanAcrossNodes(previousNodeType: Int?, nextNodeType: Int): Boolean =
    previousNodeType == LabyrinthNodeTypes.EX_BATTLE && nextNodeType == LabyrinthNodeTypes.BOSS

/**
 * Order-independent fingerprint of the owned roster. The filtered list only shows owned roles, so
 * a sweep taken before a role joined can be stale even though the encounter is unchanged.
 */
internal fun labyrinthEffectiveScanRosterStamp(characterIds: Collection<String>): Long =
    characterIds.map(::canonicalLabyrinthRoleId).sorted().fold(characterIds.size.toLong()) { acc, id ->
        acc * 1_000_003L + id.hashCode()
    }

/**
 * Whether a node-page frame captured now would actually be read by [handleNodeSelectionFrame].
 *
 * The frame is thrown away in two situations: the map is still settling after a popup was closed
 * (`settleRemainingMillis > 0`), and the session is still waiting for the game to react to a node
 * tap (`pendingTransitionHeld`). The typed/full node scan costs 3-8 s per frame on a dense map;
 * 2026-09-20 bundle 225220 spent 75 s of its 130 s node-stage budget scanning 42 frames that ended
 * in one of those two early returns. Skipping the scan there changes no decision: the frame that
 * would have been read next is the first one that passes both gates, and the tracker still sees it.
 */
internal fun labyrinthNodeScanConsumable(
    settleRemainingMillis: Long,
    pendingTransitionHeld: Boolean,
): Boolean = settleRemainingMillis <= 0L && !pendingTransitionHeld

/**
 * Milliseconds a post-entry tap's cooldown still has to run when a frame captured at
 * [frameTimestampMillis] is being decided; `<= 0` means the frame may act.
 *
 * The cooldown used to count from the capture time of the frame that *decided* the previous tap.
 * The tap itself lands asynchronously, 0.3-1.1 s later on the 2026-09-20 bundle 225220 device,
 * and a joined-character popup needs a further ~0.3-0.8 s to fade. So a frame captured before
 * the tap had even reached the screen — or 292 ms after it — still showed the popup, passed a
 * 650 ms cooldown measured from the older stamp, and closed it a second time. Three of the eight
 * popups in that run were double-closed; one second tap went through the fading popup onto the
 * map node behind it and raised a movement dialog that then had to be cancelled.
 *
 * Counting from the moment the gesture actually landed ([landedAtMillis]) when that is later
 * than the deciding frame makes the cooldown mean what it says: time the game has had to react.
 */
internal fun labyrinthPostEntryCooldownRemaining(
    frameTimestampMillis: Long,
    dispatchedAtMillis: Long,
    landedAtMillis: Long,
    intervalMillis: Long,
): Long {
    if (dispatchedAtMillis == Long.MIN_VALUE) return 0L
    val reference = maxOf(dispatchedAtMillis, landedAtMillis)
    return intervalMillis - (frameTimestampMillis - reference)
}

/**
 * After a node or its movement-confirmation button was tapped, the game can render one or more
 * transitional frames before its semantic page becomes recognizable. Those frames must not erase
 * the pending route transition; otherwise the game can enter/finish a node while the local route
 * cursor remains one step behind.
 *
 * This does not authorize any click. It only keeps the already-dispatched semantic transition
 * alive until a real destination page confirms it or the bounded entry timeout expires.
 */
internal fun labyrinthPreservesPendingNodeTransitionAcrossPage(
    pageState: LabyrinthEntryPageState,
    confirmationDispatchedAtMillis: Long?,
    nowMillis: Long,
    timeoutMillis: Long,
    dispatchedAtMillis: Long? = null,
): Boolean {
    val startedAt = confirmationDispatchedAtMillis ?: dispatchedAtMillis ?: return false
    val elapsed = (nowMillis - startedAt).coerceAtLeast(0L)
    if (elapsed >= timeoutMillis) return false
    return pageState in setOf(
        LabyrinthEntryPageState.UNKNOWN,
        LabyrinthEntryPageState.NODE_MAP_VIEW,
        LabyrinthEntryPageState.GAME_LOADING_PROGRESS,
        LabyrinthEntryPageState.PRE_HOME_DATA_LOADING,
    )
}

/**
 * While a destination-type mismatch is still being tolerated (see NODE_ENTRY_MISMATCH_STABLE_FRAMES),
 * the pending transition must survive the page change that carried the mismatched frame.
 * Otherwise the tolerance never gets a second frame to look at: the semantic page invalidates the
 * proposal immediately, and receipt recovery is refused whenever the source node has more than one
 * reachable successor (2026-09-16 17:40 bundle: link#10503 read as RELIC_CHOICE for one frame,
 * then LINK_CHOICE with no pending transition; reachable=[10501,10502,10503]).
 */
internal fun labyrinthPreservesPendingNodeTransitionForEntryMismatch(
    mismatchFrames: Int,
    stableFrames: Int,
    confirmationDispatchedAtMillis: Long?,
    nowMillis: Long,
    timeoutMillis: Long,
    dispatchedAtMillis: Long? = null,
): Boolean {
    if (mismatchFrames <= 0 || mismatchFrames >= stableFrames) return false
    val startedAt = confirmationDispatchedAtMillis ?: dispatchedAtMillis ?: return false
    return (nowMillis - startedAt).coerceAtLeast(0L) < timeoutMillis
}

/** Same physical node across frames: centre drift within 18% of the crop (at least 16 px). */
internal fun labyrinthNodeClickRectsAreStable(
    previous: EntryPixelRect?,
    current: EntryPixelRect?,
): Boolean {
    if (previous == null || current == null) return previous == current
    val previousCenterX = previous.left + previous.width / 2
    val previousCenterY = previous.top + previous.height / 2
    val currentCenterX = current.left + current.width / 2
    val currentCenterY = current.top + current.height / 2
    val xTolerance = maxOf(16, minOf(previous.width, current.width) * 18 / 100)
    val yTolerance = maxOf(16, minOf(previous.height, current.height) * 18 / 100)
    return kotlin.math.abs(previousCenterX - currentCenterX) <= xTolerance &&
        kotlin.math.abs(previousCenterY - currentCenterY) <= yTolerance
}

/** A complete, ordered column binding is the strongest slot evidence the mapper produces. */
internal fun labyrinthNodeMappingHasStrongOrderedTopology(
    mapping: NodePositionMapping,
    minimumConfidence: Double = 0.90,
): Boolean = !mapping.routeSemanticRepair &&
    mapping.topologyBindingKind == NodeTopologyBindingKind.FULL_COLUMN &&
    (mapping.topologyConfidence ?: 0.0) >= minimumConfidence

/**
 * A route target located by a strong FULL_COLUMN binding moments ago must not be re-bound to a
 * different screen rect by weaker evidence while the camera has not moved.
 *
 * 2026-09-16 19:17 bundle: link#30402 was bound at (802,350) inside a fully detected three-node
 * column (topology 0.99). Two seconds later the map's glow animation left a single "link" crop
 * straddling rows 1 and 2 at (820,220); route-semantic-repair adopted it as 30402, three such
 * frames satisfied the stability gate, and the tap at y=332 landed on the event node above.
 * Without a map gesture in between, one lone crop carries no row-order evidence and cannot
 * outrank the complete column that was just seen.
 */
internal fun labyrinthNodeClickContradictsRecentStrongBinding(
    rememberedRect: EntryPixelRect?,
    rememberedAtMillis: Long,
    nowMillis: Long,
    memoryMillis: Long,
    candidateRect: EntryPixelRect?,
    candidateHasStrongOrderedTopology: Boolean,
): Boolean {
    if (rememberedRect == null || candidateRect == null) return false
    if (candidateHasStrongOrderedTopology) return false
    if (rememberedAtMillis == Long.MIN_VALUE) return false
    if ((nowMillis - rememberedAtMillis).coerceAtLeast(0L) >= memoryMillis) return false
    return !labyrinthNodeClickRectsAreStable(rememberedRect, candidateRect)
}
