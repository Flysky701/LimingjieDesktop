package com.landosol.toolbox.labyrinth

import com.landosol.toolbox.labyrinth.node.LabyrinthNodeTypes
import com.landosol.toolbox.labyrinth.vision.EntryAnchorId
import com.landosol.toolbox.labyrinth.vision.EntryPixelRect
import com.landosol.toolbox.labyrinth.vision.LabyrinthEntryFrameResult
import com.landosol.toolbox.labyrinth.vision.LabyrinthEntryPageState

/**
 * A one-choice event has no strategic ambiguity.  Two independent current-frame anchors are
 * required before exposing its sole button so this cannot turn an unrelated UNKNOWN/map frame
 * into a blind click.
 */
internal fun labyrinthSingleChoiceEventButtonRect(
    result: LabyrinthEntryFrameResult,
    minimumScore: Double = 0.72,
): EntryPixelRect? {
    if (result.observation.state != LabyrinthEntryPageState.EVENT_CHOICE) return null
    val scores = result.observation.anchorScores
    if (scores[EntryAnchorId.EVENT_SINGLE_CHOICE_TRIGGER_TITLE] < minimumScore) return null
    val match = result.anchorMatches[EntryAnchorId.EVENT_SINGLE_CHOICE_SELECT_BUTTON] ?: return null
    return match.rect.takeIf { match.score >= minimumScore }
}

/**
 * Event free-role pick: some events ask for one recruit, others for two (全列表 or 职阶列表,
 * 2026-09-17). The count is not announced anywhere the recogniser reads, but 去邀请 only lights
 * up once the required number is selected. So: confirm as soon as it is enabled; while it is
 * still visibly disabled after a pick has settled, pick another, bounded by the shell's three
 * slots.
 */
/**
 * Which visual state the 「去邀请」 button is in.
 *
 * The enabled and disabled templates share one ROI and differ only in button colour, so both
 * correlate highly on either state and neither score alone is a verdict. 2026-09-23: on a page
 * with nothing selected the enabled template still scored 0.925 while disabled scored 0.987, so a
 * plain threshold read the grey button as blue and the run confirmed an empty invitation for ever.
 * Only the margin between the two templates separates them reliably.
 */
internal enum class LabyrinthInviteButtonState { ENABLED, DISABLED, UNKNOWN }

internal fun labyrinthInviteButtonState(
    enabledScore: Double,
    disabledScore: Double,
    minimumScore: Double = LABYRINTH_INVITE_BUTTON_MIN_SCORE,
    margin: Double = LABYRINTH_INVITE_BUTTON_MARGIN,
): LabyrinthInviteButtonState = when {
    maxOf(enabledScore, disabledScore) < minimumScore -> LabyrinthInviteButtonState.UNKNOWN
    disabledScore - enabledScore >= margin -> LabyrinthInviteButtonState.DISABLED
    enabledScore - disabledScore >= margin -> LabyrinthInviteButtonState.ENABLED
    else -> LabyrinthInviteButtonState.UNKNOWN
}

internal const val LABYRINTH_INVITE_BUTTON_MIN_SCORE = 0.72

internal const val LABYRINTH_INVITE_BUTTON_MARGIN = 0.02

/**
 * Reconcile the remembered free-role picks against what the roster actually shows.
 *
 * Picks used to be recorded the moment a tap was dispatched and never revisited. A tap the game
 * ignored therefore left a phantom selection that pushed every later frame down the confirm
 * branch, and the run pressed a dead 「去邀请」 until the user killed it. The roster is the
 * authority: a remembered card that is on screen and unselected is dropped, a card that scrolled
 * out of view is kept, and anything the roster shows as selected is adopted.
 */
internal fun labyrinthEventFreeRoleReconcileSelection(
    rememberedIds: Collection<String>,
    visibleSelectedIds: Collection<String>,
    visibleIds: Collection<String>,
): List<String> {
    val visible = visibleIds.toSet()
    val selected = visibleSelectedIds.toSet()
    val kept = rememberedIds.filter { it in selected || it !in visible }
    return (kept + visibleSelectedIds).distinct()
}

internal fun labyrinthEventFreeRoleNeedsAnotherPick(
    selectedCount: Int,
    inviteEnabled: Boolean,
    inviteDisabled: Boolean,
    millisSinceLastSelect: Long,
    maxPicks: Int = 3,
    settleMillis: Long = 2_500L,
): Boolean = !inviteEnabled && inviteDisabled &&
    selectedCount in 1 until maxPicks &&
    millisSinceLastSelect >= settleMillis

/**
 * Whether a committed event-choice tap has waited long enough to be considered lost.
 *
 * A real selection leaves the event page within a second or two, so a page that is still the same
 * event after this long means the tap never landed. Retrying is safe because the event page only
 * accepts one selection: if the tap did land and the page simply lagged, the page has changed by
 * the time the retry is planned.
 */
internal fun labyrinthEventChoiceCommitExpired(
    committedAtMillis: Long,
    nowMillis: Long,
    retryAfterMillis: Long = EVENT_CHOICE_COMMIT_RETRY_MILLIS,
): Boolean = committedAtMillis != Long.MIN_VALUE &&
    (nowMillis - committedAtMillis).coerceAtLeast(0L) >= retryAfterMillis

internal const val EVENT_CHOICE_COMMIT_RETRY_MILLIS = 6_000L

/**
 * Some event nodes open the same full-roster shell used by the initial 3-character selector, but
 * ask for exactly one free recruit. Route context owns this ambiguity: once the node we actually
 * tapped is EVENT, an INITIAL_CHARACTER_SELECTION frame with an opening-style viewport must never
 * be delegated to the opening-roster planner.
 */
internal fun labyrinthEventFreeRoleSelectionOwnsFrame(
    pageState: LabyrinthEntryPageState,
    activeNodeType: Int?,
    roleRewardPage: Boolean,
    hasOpeningViewport: Boolean,
    shopChoiceImprintPending: Boolean = false,
    /**
     * Whether a route has passed the opening start. Once it has, a full-roster picker can only have been
     * opened by this run (an event's free pick or a shop 选择印记); the opening selector is over.
     *
     * The node-type and imprint-pending flags are better evidence when they survive, but they are
     * exactly what gets lost across the page churn a purchase causes, and losing them used to
     * leave the page with no owner at all rather than with a worse owner.
     */
    routeActive: Boolean = false,
): Boolean =
    pageState == LabyrinthEntryPageState.INITIAL_CHARACTER_SELECTION &&
        !roleRewardPage &&
        hasOpeningViewport &&
        (
            routeActive ||
                activeNodeType == LabyrinthNodeTypes.EVENT ||
                // A 选择印记 bought in the shop opens the same full-roster picker as an event's
                // free pick. Without this the frame fell through to the opening-roster handler,
                // which hunts for this guild's three fixed opening characters and reported
                // "已到初始角色列表底部，复核剩余目标 1/3" while the shop's picker waited
                // (2026-09-20 bundle 155513, 攻击型职能的选择印记).
                (activeNodeType == LabyrinthNodeTypes.SHOP && shopChoiceImprintPending)
            )

internal enum class LabyrinthEventFreeRoleBlockedAction { WAIT, STOP }

/**
 * What a stalled event free-role page should do next.
 *
 * The page is answered from whatever is on screen: the roster only offers roles this run has not
 * got yet, so the top-scoring visible card is the answer and there is nothing to be gained by
 * walking the (very long) list. Short stalls are still ordinary -- the list animates and portraits
 * resolve a frame late -- so they wait. What must not happen is the unbounded wait this page used
 * to allow: an unreadable portrait or a recommendation that never cleared the safety bar parked
 * the run here with no timeout at all, so past [giveUpMillis] it stops with the reason instead.
 */
internal fun labyrinthEventFreeRoleBlockedAction(
    blockedMillis: Long,
    settleMillis: Long = EVENT_FREE_ROLE_SETTLE_MILLIS,
    giveUpMillis: Long = EVENT_FREE_ROLE_GIVE_UP_MILLIS,
): LabyrinthEventFreeRoleBlockedAction =
    if (blockedMillis >= giveUpMillis) {
        LabyrinthEventFreeRoleBlockedAction.STOP
    } else {
        LabyrinthEventFreeRoleBlockedAction.WAIT
    }

/** A stalled free-role viewport is still considered to be settling for this long. */
internal const val EVENT_FREE_ROLE_SETTLE_MILLIS = 3_000L

/** A free-role page that still cannot act after this long stops rather than hanging. */
internal const val EVENT_FREE_ROLE_GIVE_UP_MILLIS = 45_000L
