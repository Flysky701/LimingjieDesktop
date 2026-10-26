package com.landosol.toolbox.labyrinth

import com.landosol.toolbox.labyrinth.vision.LabyrinthEntryPageState

/**
 * A movement modal may already be open even though the session lost/never created its pending
 * tap record (for example a manual tap while automation is searching).  Recovery must not turn
 * the generic two-button detector into a broad auto-confirm rule, so require both a very strong
 * modal observation and a route graph with exactly one reachable saved-route successor.
 */
internal fun labyrinthCanRecoverOrphanNodeMoveConfirmation(
    pageState: LabyrinthEntryPageState,
    confirmationConfidence: Double,
    hasUniqueReachableRouteTarget: Boolean,
    shopBackgroundVisible: Boolean = false,
): Boolean {
    // The shop-exit confirmation uses the same blue-title/white-body/two-button chrome as a map
    // move confirmation. During the first transition frame it can classify as UNKNOWN, so page
    // state alone is not enough: visible shop chrome must keep ownership with the shop handler.
    if (shopBackgroundVisible) return false
    if (!hasUniqueReachableRouteTarget || confirmationConfidence < 0.85) return false
    // The shop exit dialog has the same blue-title/white-body/two-button chrome, and while it
    // fades in the page can classify UNKNOWN even though the shop is still on screen.
    // 2026-09-18 live: that frame was adopted as a movement confirmation for the route's only
    // remaining successor (Boss#30701) although no node had ever been tapped; the run then
    // waited for a Boss entry page, saw the shop, and stopped. Dismissal already refuses to act
    // while shop chrome is visible; adoption must refuse too.
    if (shopBackgroundVisible) return false
    return pageState in setOf(
        LabyrinthEntryPageState.NODE_SELECTION,
        LabyrinthEntryPageState.NODE_MAP_VIEW,
        LabyrinthEntryPageState.UNKNOWN,
    )
}

/**
 * An orphan movement modal that cannot be recovered (several reachable successors, or weak
 * evidence) still blocks every map interaction: swipes and node taps land on the dialog and the
 * search loop would otherwise nudge the map forever. The only safe automatic action is to press
 * the dialog's own cancel button, which returns to the untouched map. Dismissal stays bounded so
 * a misdetected dialog cannot turn into an endless tap loop.
 */
internal fun labyrinthShouldDismissOrphanNodeMoveConfirmation(
    pageState: LabyrinthEntryPageState,
    hasPendingNodeTransition: Boolean,
    canRecover: Boolean,
    stableFrames: Int,
    dismissAttempts: Int,
    lastDismissAt: Long,
    now: Long,
    shopBackgroundVisible: Boolean = false,
): Boolean {
    if (hasPendingNodeTransition || canRecover) return false
    // The shop exit dialog has the same blue-title/white-body/two-button chrome and hides the
    // shop title, so the page can classify as UNKNOWN. The shop chrome around it is still
    // visible; that dialog belongs to the shop handler, never to map recovery.
    if (shopBackgroundVisible) return false
    if (pageState !in setOf(
            LabyrinthEntryPageState.NODE_SELECTION,
            LabyrinthEntryPageState.NODE_MAP_VIEW,
            LabyrinthEntryPageState.UNKNOWN,
        )
    ) return false
    if (stableFrames < ORPHAN_MOVE_CONFIRMATION_DISMISS_STABLE_FRAMES) return false
    if (dismissAttempts >= MAX_ORPHAN_MOVE_CONFIRMATION_DISMISS_ATTEMPTS) return false
    return lastDismissAt == Long.MIN_VALUE || now - lastDismissAt >= ORPHAN_MOVE_CONFIRMATION_DISMISS_INTERVAL_MILLIS
}

internal const val ORPHAN_MOVE_CONFIRMATION_DISMISS_STABLE_FRAMES = 2

internal const val MAX_ORPHAN_MOVE_CONFIRMATION_DISMISS_ATTEMPTS = 3

internal const val ORPHAN_MOVE_CONFIRMATION_DISMISS_INTERVAL_MILLIS = 2_000L

/**
 * Whether the dismissal budget may be handed back after the dialog went away.
 *
 * [MAX_ORPHAN_MOVE_CONFIRMATION_DISMISS_ATTEMPTS] exists to stop pressing 取消 at a dialog that
 * refuses to close. It was also, accidentally, a per-run cap: the counter only ever reset when a
 * session error restarted entry navigation, so after three stray dialogs had been cancelled
 * successfully the fourth was never tapped at all and the run stopped with
 * "移动确认弹窗多次取消无效，请手动关闭后继续" without a single cancel tap (2026-09-19 report).
 *
 * A cleared screen is proof the previous cancel worked, so the budget is restored. Proof means
 * several consecutive dialog-free frames: one frame can be a fade-out, and a dialog that flickers
 * must not refill the budget forever.
 */
internal fun labyrinthOrphanMoveConfirmationBudgetRestored(
    clearFrames: Int,
    requiredClearFrames: Int = ORPHAN_MOVE_CONFIRMATION_CLEAR_FRAMES,
): Boolean = clearFrames >= requiredClearFrames

internal const val ORPHAN_MOVE_CONFIRMATION_CLEAR_FRAMES = 3

/**
 * The movement-confirmation detector intentionally looks for a generic two-button modal and can
 * also fire on unrelated dialogs such as SHOP_EXIT_CONFIRMATION. Page ownership must therefore
 * gate it before it is allowed to pre-empt the page-local action handler.
 *
 * A pending node transition may temporarily obscure the map enough for the page classifier to
 * return UNKNOWN, so keep that one bounded exception. Without a pending transition the generic
 * colour/shape detector has no semantic ownership at all: ordinary node-map controls can satisfy
 * its blue/light coverage test and would otherwise surface a false "manual confirmation" block.
 */
internal fun labyrinthNodeMoveConfirmationOwnsFrame(
    pageState: LabyrinthEntryPageState,
    hasPendingNodeTransition: Boolean,
): Boolean {
    if (!hasPendingNodeTransition) return false
    return when (pageState) {
    LabyrinthEntryPageState.NODE_SELECTION,
    LabyrinthEntryPageState.NODE_MAP_VIEW,
    LabyrinthEntryPageState.UNKNOWN,
    -> true
    else -> false
    }
}

/**
 * Page-local state invalidation normally clears a pending map-node transition as soon as the
 * classifier leaves NODE_SELECTION.  A real movement-confirmation modal can itself make the page
 * classify as NODE_MAP_VIEW or UNKNOWN, though, so preserve the pending semantic transition for
 * that one frame family.  The generic two-button detector is not sufficient on its own: ownership
 * still requires an already-dispatched programmatic node transition and a map-compatible page.
 */
internal fun labyrinthPreservesPendingNodeTransitionForMoveConfirmation(
    pageState: LabyrinthEntryPageState,
    hasPendingNodeTransition: Boolean,
    hasMoveConfirmation: Boolean,
): Boolean = hasMoveConfirmation && labyrinthNodeMoveConfirmationOwnsFrame(
    pageState = pageState,
    hasPendingNodeTransition = hasPendingNodeTransition,
)
