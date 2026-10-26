package com.landosol.toolbox.labyrinth

import com.landosol.toolbox.labyrinth.node.LabyrinthNodeTypes
import com.landosol.toolbox.labyrinth.vision.EntryAnchorId
import com.landosol.toolbox.labyrinth.vision.LabyrinthEntryFrameResult
import com.landosol.toolbox.labyrinth.vision.LabyrinthEntryPageState

/**
 * A programmatic shop purchase may render one or two UNKNOWN transition frames before the
 * confirmation modal becomes recognizable. Keep only the semantic relic-id transaction across
 * that bounded transition; page-local coordinates still remain invalidated normally.
 */
internal fun labyrinthKeepsPlannedShopPurchaseAcrossPage(
    pageState: LabyrinthEntryPageState,
    plannedRelicId: String?,
    startedAt: Long,
    now: Long,
    timeoutMillis: Long,
): Boolean {
    if (pageState != LabyrinthEntryPageState.UNKNOWN) return false
    if (plannedRelicId.isNullOrBlank() || startedAt == Long.MIN_VALUE) return false
    return (now - startedAt).coerceAtLeast(0L) <= timeoutMillis
}

internal fun labyrinthShopRefreshConfirmationOwnsFrame(
    activeNodeType: Int?,
    refreshStartedAt: Long,
    refreshConfirmedAt: Long,
    now: Long,
    hasGenericConfirmation: Boolean,
    timeoutMillis: Long,
): Boolean {
    if (activeNodeType != LabyrinthNodeTypes.SHOP) return false
    if (!hasGenericConfirmation || refreshStartedAt == Long.MIN_VALUE) return false
    if (refreshConfirmedAt != Long.MIN_VALUE) return false
    return (now - refreshStartedAt).coerceAtLeast(0L) <= timeoutMillis
}

internal fun labyrinthShopRefreshCanCommit(
    pageState: LabyrinthEntryPageState,
    refreshStartedAt: Long,
    refreshConfirmedAt: Long,
    now: Long,
    settleMillis: Long,
    timeoutMillis: Long,
): Boolean {
    if (pageState != LabyrinthEntryPageState.SHOP) return false
    if (refreshStartedAt == Long.MIN_VALUE || refreshConfirmedAt == Long.MIN_VALUE) return false
    val sinceStart = (now - refreshStartedAt).coerceAtLeast(0L)
    val sinceConfirm = (now - refreshConfirmedAt).coerceAtLeast(0L)
    return sinceStart <= timeoutMillis && sinceConfirm >= settleMillis
}

/**
 * Purchase/refresh animation frames can resemble the broad reconnect-title template while the
 * shop itself is temporarily classified UNKNOWN. Suppress only that short, semantically owned
 * transition; a genuine session error remains actionable again as soon as the guard expires.
 */
internal fun labyrinthShopTransitionSuppressesSessionBlock(
    pageState: LabyrinthEntryPageState,
    previousPageState: LabyrinthEntryPageState?,
    activeNodeType: Int?,
    lastActionAt: Long,
    now: Long,
    guardMillis: Long = 3_000L,
): Boolean {
    if (pageState != LabyrinthEntryPageState.UNKNOWN) return false
    if (activeNodeType != LabyrinthNodeTypes.SHOP) return false
    if (labyrinthPageUiOwner(previousPageState ?: LabyrinthEntryPageState.UNKNOWN) != LabyrinthPageUiOwner.SHOP) {
        return false
    }
    if (lastActionAt == Long.MIN_VALUE) return false
    return (now - lastActionAt).coerceAtLeast(0L) <= guardMillis
}

/**
 * After resuming an already-active Dawn Realm run, the game may reopen directly on a pending
 * role-reward page instead of the node map. Those pages belong to route execution rather than
 * the opening invitation flow. Keep this deliberately narrow so a fresh run still retains the
 * original CHARACTER_JOINED opening-safety check.
 */
/** Two or more fixed shop chrome anchors still visible: a dialog is open on top of the shop. */
internal fun labyrinthShopBackgroundVisible(result: LabyrinthEntryFrameResult): Boolean =
    listOf(EntryAnchorId.SHOP_TITLE, EntryAnchorId.SHOP_INSTRUCTION,
        EntryAnchorId.SHOP_REFRESH_BUTTON, EntryAnchorId.SHOP_CLOSE)
        .count { result.observation.anchorScores[it] >= 0.65 } >= 2

internal fun labyrinthShopJoinedRewardOwnsRoute(
    result: LabyrinthEntryFrameResult,
    purchaseConfirmedAt: Long,
    now: Long,
): Boolean {
    if (result.observation.state != LabyrinthEntryPageState.CHARACTER_JOINED) return false
    val recentPurchase = purchaseConfirmedAt != Long.MIN_VALUE && now - purchaseConfirmedAt in 0..120_000
    return recentPurchase || labyrinthShopBackgroundVisible(result)
}
