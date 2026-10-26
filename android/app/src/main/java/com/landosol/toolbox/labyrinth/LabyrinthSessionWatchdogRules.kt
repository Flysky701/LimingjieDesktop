package com.landosol.toolbox.labyrinth

import com.landosol.toolbox.labyrinth.vision.LabyrinthEntryPageState

/** A non-null message is also the safety gate that forbids post-entry auto clicks on this page. */
internal fun labyrinthManualInputMessage(state: LabyrinthEntryPageState): String? = when (state) {
    LabyrinthEntryPageState.INITIAL_CHARACTER_SELECTION -> "等待人工选择角色并确认"
    else -> null
}

/**
 * Whether a route page that has produced no action should be given up on.
 *
 * Almost every page state has some bounded retry of its own, but those bounds only count *taps*.
 * A page that decides "wait" never dispatches anything, so it increments nothing and no timeout
 * applies: before this, only UNKNOWN had a deadline. Every other wait branch -- shop titles that
 * never resolve, an EX challenge page whose encounter is never recognized, an event whose OCR
 * never reaches the trusted threshold, a failed battle whose retry conditions never come true --
 * could hold a run open indefinitely with a fixed status line and nothing happening.
 *
 * "Progress" is deliberately generous: a dispatched action, a page change, or merely a different
 * status message all count, because a changing message means some sub-state advanced (a shop
 * round, an OCR confirmation, a scan stage). Only a page that is truly frozen -- same page, same
 * message, no action at all for [limitMillis] -- gives up, and then it stops with that message as
 * the diagnosis rather than hanging.
 */
internal fun labyrinthPageStallExpired(
    stalledMillis: Long,
    battlePage: Boolean,
    limitMillis: Long = POST_ENTRY_STALL_TIMEOUT_MILLIS,
): Boolean = when {
    // Battle has its own fixed 5-minute deadline in LabyrinthBattleWaitPolicy, and it is the only
    // page whose legitimate silence exceeds this budget.
    //
    // There used to be a second exemption here for "waiting for the operator to pick characters".
    // It was a hole, not a safety valve: manual selection is not a mode this build offers
    // (`manualCharacterSelection = false`), yet INITIAL_CHARACTER_SELECTION still falls back to
    // that message, so any hang on a roster page exempted itself from the watchdog -- which is
    // precisely the page that hung for 408 s in 2026-09-22 bundle 173753.
    battlePage -> false
    else -> stalledMillis >= limitMillis
}

/**
 * How long a hand-opened relic-detail popup may hold the route before the run stops.
 *
 * Generous on purpose: it exists only so an unattended run cannot be parked for ever, not to rush
 * anyone reading the list.
 */
internal const val RELIC_DETAIL_HOLD_TIMEOUT_MILLIS = 300_000L

/**
 * Whether the single-action latch has been held long enough to be a fault rather than a wait.
 *
 * A dispatched gesture completes in well under a second; the executor is the only thing between
 * claiming the latch and releasing it. Holding it for a minute means the gesture call never came
 * back, and because every frame handler bails out while the latch is held, that state is
 * indistinguishable from a dead session: no clicks, no messages, no other timeout can fire.
 */
internal fun labyrinthActionLatchStuck(
    heldSinceMillis: Long,
    nowMillis: Long,
    limitMillis: Long = ACTION_LATCH_STUCK_TIMEOUT_MILLIS,
): Boolean = heldSinceMillis != Long.MIN_VALUE && nowMillis - heldSinceMillis >= limitMillis

/** A dispatched gesture that has not returned in this long is treated as never returning. */
internal const val ACTION_LATCH_STUCK_TIMEOUT_MILLIS = 60_000L

/**
 * How long a route page may show the same status with no action before the run stops.
 *
 * Long enough to clear the slowest legitimate silence: the shop's per-title OCR backstop is 20 s,
 * and a serial OCR queue can leave one shop slot waiting ~16 s for its turn, so a single
 * unchanged round can legitimately last around half a minute on a loaded device. 120 s leaves
 * several times that margin while still guaranteeing the run terminates.
 */
internal const val POST_ENTRY_STALL_TIMEOUT_MILLIS = 120_000L

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
