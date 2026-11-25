package com.landosol.toolbox.labyrinth

import com.landosol.toolbox.automation.AutomationSessionId
import com.landosol.toolbox.labyrinth.vision.LabyrinthBattleElementFilter
import com.landosol.toolbox.labyrinth.vision.LabyrinthBattleTeamObservation
import com.landosol.toolbox.labyrinth.vision.LabyrinthBattleTeamRecognitionState

enum class LabyrinthBattleRosterScrollDirection { TO_TOP, NEXT_PAGE }

internal enum class LabyrinthBattleRosterSearchDecision {
    TO_TOP,
    NEXT_PAGE,
    WAIT_FOR_SETTLE,
    EXHAUSTED,
    UNAVAILABLE,
}

/** Search progress belongs to one session, team, recommendation and attribute filter. */
internal class LabyrinthBattleRosterSearch {
    private data class Context(
        val sessionId: AutomationSessionId,
        val team: Int?,
        val filter: LabyrinthBattleElementFilter,
        val recommendedIds: List<String>,
    )

    private var context: Context? = null
    private var topObserved = false
    private var pendingScroll: PendingScroll? = null
    private var lastVisibleSignature: String? = null
    private var unchangedProbeDirection: LabyrinthBattleRosterScrollDirection? = null
    private var unchangedProbes = 0
    private var probing = false
    private var blankProbes = 0

    private data class PendingScroll(
        val direction: LabyrinthBattleRosterScrollDirection,
        val originPosition: Double,
        val observations: Int = 0,
        val samePositionStableObservations: Int = 0,
        val originVisibleSignature: String? = null,
        val isProbe: Boolean = false,
    )

    fun reset() {
        context = null
        topObserved = false
        pendingScroll = null
        lastVisibleSignature = null
        unchangedProbeDirection = null
        unchangedProbes = 0
        probing = false
        blankProbes = 0
    }

    /**
     * A roster swipe can temporarily overscroll past the first/last row and expose a blank area.
     * Do not interpret those transient frames as another page. Wait until the list rebounds to a
     * stable viewport with cards, then compare the scrollbar against the pre-swipe position.
     */
    fun recordExecutedScroll(
        direction: LabyrinthBattleRosterScrollDirection,
        originPosition: Double,
    ) {
        pendingScroll = PendingScroll(direction, originPosition.takeIf { it.isFinite() && it in 0.0..1.0 } ?: 0.0,
            originVisibleSignature = lastVisibleSignature, isProbe = probing)
        probing = false
    }

    fun observe(
        sessionId: AutomationSessionId,
        observation: LabyrinthBattleTeamObservation,
        recommendedIds: List<String>,
    ): LabyrinthBattleRosterSearchDecision {
        val stableContext = if (
            observation.recognitionState == LabyrinthBattleTeamRecognitionState.STABLE &&
            observation.currentFilter != LabyrinthBattleElementFilter.UNKNOWN
        ) {
            Context(sessionId, observation.bossTeamIndex, observation.currentFilter, recommendedIds.toList())
        } else {
            null
        }
        if (stableContext != null && context != stableContext) {
            context = stableContext
            topObserved = false
            pendingScroll = null
            unchangedProbes = 0
            unchangedProbeDirection = null
            probing = false
            blankProbes = 0
        }
        lastVisibleSignature = observation.takeIf {
            it.recognitionState == LabyrinthBattleTeamRecognitionState.STABLE && it.visibleCharacters.isNotEmpty() &&
                it.visibleCharacters.all { role -> role.trusted && role.characterId != null }
        }?.visibleCharacters?.joinToString("|") {
            "${it.characterId ?: it.suspectedCharacterId}:${it.screenRect.left / 4}:${it.screenRect.top / 4}:${it.screenRect.width / 4}"
        }

        pendingScroll?.let { pending ->
            val nextPending = pending.copy(observations = pending.observations + 1)
            pendingScroll = nextPending
            if (
                observation.recognitionState != LabyrinthBattleTeamRecognitionState.STABLE ||
                observation.currentFilter == LabyrinthBattleElementFilter.UNKNOWN ||
                observation.visibleCharacters.isEmpty()
            ) {
                if (nextPending.observations >= MAX_SCROLL_SETTLE_OBSERVATIONS) {
                    pendingScroll = null
                    return probe(observation, nextPending)
                }
                return LabyrinthBattleRosterSearchDecision.WAIT_FOR_SETTLE
            }
            val scrollbar = observation.scrollbar
            if (!scrollbar.isTrustedForBoundaryEvidence()) {
                // Without a trustworthy thumb, changed stable content is the only positive proof
                // that the gesture moved the list. It cannot be a stale pre-swipe capture, so do
                // not burn the whole settle budget before continuing in the same direction.
                val contentMoved = lastVisibleSignature != null &&
                    pending.originVisibleSignature != null &&
                    lastVisibleSignature != pending.originVisibleSignature
                if (contentMoved || nextPending.observations >= MAX_SCROLL_SETTLE_OBSERVATIONS) {
                    pendingScroll = null
                    return probe(observation, nextPending)
                }
                return LabyrinthBattleRosterSearchDecision.WAIT_FOR_SETTLE
            }

            val delta = scrollbar.position - pending.originPosition
            val movedInRequestedDirection = when (pending.direction) {
                LabyrinthBattleRosterScrollDirection.TO_TOP -> delta <= -MIN_CONFIRMED_SCROLL_DELTA
                LabyrinthBattleRosterScrollDirection.NEXT_PAGE -> delta >= MIN_CONFIRMED_SCROLL_DELTA
            }
            if (movedInRequestedDirection) {
                pendingScroll = null
                if (isAtVisualTopBoundary(scrollbar)) topObserved = true
                return normalDecision(scrollbar)
            }

            // The stable list returned to effectively the same position after the gesture. Near
            // an end this is the expected elastic overscroll -> rebound signal, so treat it as a
            // confirmed boundary instead of issuing another same-direction swipe.
            if (kotlin.math.abs(delta) <= SAME_POSITION_TOLERANCE) {
                // The first frame(s) after executor completion can still be the pre-swipe capture.
                // Never fail or confirm an elastic boundary from a single same-position frame.
                // Require repeated stable observations so the actual post-gesture/rebound frame
                // has time to arrive.
                val same = nextPending.samePositionStableObservations + 1
                pendingScroll = nextPending.copy(samePositionStableObservations = same)
                if (same >= MIN_REBOUND_STABLE_OBSERVATIONS) {
                    when (pending.direction) {
                        LabyrinthBattleRosterScrollDirection.TO_TOP -> {
                            if (isAtVisualTopBoundary(scrollbar, rebound = true)) {
                                pendingScroll = null
                                topObserved = true
                                return normalDecision(scrollbar)
                            }
                        }
                        LabyrinthBattleRosterScrollDirection.NEXT_PAGE -> {
                            if (isAtVisualBottomBoundary(scrollbar, rebound = true) ||
                                (topObserved && scrollbar.contentEndVisible)) {
                                pendingScroll = null
                                return LabyrinthBattleRosterSearchDecision.EXHAUSTED
                            }
                        }
                    }
                }
                if (nextPending.observations >= MAX_SCROLL_SETTLE_OBSERVATIONS) {
                    pendingScroll = null
                    return probe(observation, nextPending)
                }
                return LabyrinthBattleRosterSearchDecision.WAIT_FOR_SETTLE
            }

            if (nextPending.observations >= MAX_SCROLL_SETTLE_OBSERVATIONS) {
                pendingScroll = null
                return probe(observation, nextPending)
            }
            return LabyrinthBattleRosterSearchDecision.WAIT_FOR_SETTLE
        }

        if (observation.recognitionState != LabyrinthBattleTeamRecognitionState.STABLE ||
            observation.currentFilter == LabyrinthBattleElementFilter.UNKNOWN
        ) return probe(observation, null)
        val scrollbar = observation.scrollbar
        // A roster page without a trustworthy thumb is still scrollable. `canScroll == false` and
        // the 0.0 position that comes with it are an absent measurement, never a confirmed single
        // page, so they must fall through to a real swipe probe instead of ending the search.
        if (!scrollbar.isTrustedForBoundaryEvidence()) return probe(observation, null)

        // Only visual evidence can finish rewinding. A dispatched gesture is not confirmation.
        if (scrollbar.position <= TOP_POSITION_THRESHOLD) topObserved = true
        return normalDecision(scrollbar)
    }

    /**
     * The blue thumb is boundary evidence only when it was actually measured. A missing thumb, a
     * full-height thumb (`canScroll == false`) or an out-of-range position carries no information
     * about where the list stands, and `position` is then reported as a placeholder 0.0.
     */
    private fun com.landosol.toolbox.labyrinth.vision.LabyrinthBattleScrollbarObservation.isTrustedForBoundaryEvidence(): Boolean =
        visible && thumbRect != null && canScroll && position.isFinite() && position in 0.0..1.0

    private fun probe(observation: LabyrinthBattleTeamObservation, pending: PendingScroll?): LabyrinthBattleRosterSearchDecision {
        if (observation.currentFilter == LabyrinthBattleElementFilter.UNKNOWN) return LabyrinthBattleRosterSearchDecision.WAIT_FOR_SETTLE
        val direction = pending?.direction ?: if (topObserved) LabyrinthBattleRosterScrollDirection.NEXT_PAGE
            else LabyrinthBattleRosterScrollDirection.TO_TOP

        // A probe concludes by comparing the portraits before and after a swipe, so a stable
        // viewport that resolves no portrait at all produces no signature and can never conclude.
        // 2026-09-19 live: the 有效效果 filter listed a single card the roster recogniser could not
        // resolve, its one tall thumb reported canScroll=false, and the scan rewound the list
        // every 17 seconds for the rest of the session. Count only swipes that actually completed
        // over a viewport the recogniser calls stable: an unstable or mid-scroll frame carries no
        // information, and repeated frames between two swipes must not burn the budget either.
        if (pending != null && observation.recognitionState == LabyrinthBattleTeamRecognitionState.STABLE) {
            if (observation.visibleCharacters.isEmpty()) {
                blankProbes++
                if (blankProbes >= MAX_BLANK_PROBES) {
                    blankProbes = 0
                    probing = false
                    return LabyrinthBattleRosterSearchDecision.EXHAUSTED
                }
            } else {
                blankProbes = 0
            }
        }

        if (pending?.isProbe == true && lastVisibleSignature != null && lastVisibleSignature == pending.originVisibleSignature) {
            unchangedProbes = if (unchangedProbeDirection == direction) unchangedProbes + 1 else 1
            unchangedProbeDirection = direction
            if (unchangedProbes >= 2) {
                unchangedProbes = 0
                if (direction == LabyrinthBattleRosterScrollDirection.TO_TOP) {
                    topObserved = true
                    probing = true
                    return LabyrinthBattleRosterSearchDecision.NEXT_PAGE
                }
                return LabyrinthBattleRosterSearchDecision.EXHAUSTED
            }
        } else if (pending != null) {
            unchangedProbes = 0
            unchangedProbeDirection = null
        }
        probing = true
        return if (direction == LabyrinthBattleRosterScrollDirection.TO_TOP) LabyrinthBattleRosterSearchDecision.TO_TOP
            else LabyrinthBattleRosterSearchDecision.NEXT_PAGE
    }

    private fun normalDecision(scrollbar: com.landosol.toolbox.labyrinth.vision.LabyrinthBattleScrollbarObservation): LabyrinthBattleRosterSearchDecision = when {
        !topObserved -> LabyrinthBattleRosterSearchDecision.TO_TOP
        scrollbar.contentEndVisible -> LabyrinthBattleRosterSearchDecision.EXHAUSTED
        scrollbar.position >= BOTTOM_POSITION_THRESHOLD -> LabyrinthBattleRosterSearchDecision.EXHAUSTED
        else -> LabyrinthBattleRosterSearchDecision.NEXT_PAGE
    }

    /**
     * The blue scrollbar thumb does not always touch the calibrated track rectangle. In a short
     * filtered roster the thumb can be very tall, so a fixed ~13 px visual end-cap becomes a large
     * normalized position error (the current EFFECTIVE_EFFECT top stop measured as 0.111). Keep
     * the strict normalized thresholds for ordinary decisions, but after a real swipe/rebound also
     * accept a thumb physically hugging the corresponding track edge.
     */
    private fun isAtVisualTopBoundary(
        scrollbar: com.landosol.toolbox.labyrinth.vision.LabyrinthBattleScrollbarObservation,
        rebound: Boolean = false,
    ): Boolean {
        val normalizedThreshold = if (rebound) TOP_REBOUND_BOUNDARY_THRESHOLD else TOP_POSITION_THRESHOLD
        if (scrollbar.position <= normalizedThreshold) return true
        val thumb = scrollbar.thumbRect ?: return false
        val gap = (thumb.top - scrollbar.trackRect.top).coerceAtLeast(0)
        return gap <= visualEdgeGapLimit(scrollbar.trackRect.height)
    }

    private fun isAtVisualBottomBoundary(
        scrollbar: com.landosol.toolbox.labyrinth.vision.LabyrinthBattleScrollbarObservation,
        rebound: Boolean = false,
    ): Boolean {
        val normalizedThreshold = if (rebound) BOTTOM_REBOUND_BOUNDARY_THRESHOLD else BOTTOM_POSITION_THRESHOLD
        if (scrollbar.position >= normalizedThreshold) return true
        val thumb = scrollbar.thumbRect ?: return false
        val trackBottom = scrollbar.trackRect.top + scrollbar.trackRect.height
        val thumbBottom = thumb.top + thumb.height
        val gap = (trackBottom - thumbBottom).coerceAtLeast(0)
        return gap <= visualEdgeGapLimit(scrollbar.trackRect.height)
    }

    private fun visualEdgeGapLimit(trackHeight: Int): Int =
        maxOf(MIN_VISUAL_EDGE_GAP_PX, (trackHeight * VISUAL_EDGE_GAP_RATIO).toInt())

    private companion object {
        const val TOP_POSITION_THRESHOLD = 0.02
        const val BOTTOM_POSITION_THRESHOLD = 0.98
        // Wider only for a confirmed no-movement rebound; normal position decisions stay strict.
        const val TOP_REBOUND_BOUNDARY_THRESHOLD = 0.08
        const val BOTTOM_REBOUND_BOUNDARY_THRESHOLD = 0.92
        const val MIN_CONFIRMED_SCROLL_DELTA = 0.035
        const val SAME_POSITION_TOLERANCE = 0.025
        const val MIN_REBOUND_STABLE_OBSERVATIONS = 2
        const val MAX_SCROLL_SETTLE_OBSERVATIONS = 12
        const val MIN_VISUAL_EDGE_GAP_PX = 14
        const val VISUAL_EDGE_GAP_RATIO = 0.04

        /**
         * Swipes spent on a stable viewport that shows no resolvable portrait.
         *
         * Enough that a genuinely slow list still gets several chances to render, few enough that
         * a roster which simply has nothing to resolve ends the scan instead of rewinding forever.
         */
        const val MAX_BLANK_PROBES = 4
    }
}

/**
 * Roster left after every attribute tab was scanned end to end without finding [unfindableIds].
 *
 * The run roster can list characters this editor cannot show: a stale roster restored from a
 * previous run, or a recognition mistake at join time. Once the whole editor has been swept they
 * are provably absent, so planning must stop asking for them. Returns null when nothing would
 * change, which keeps the caller from looping on an exclusion that cannot make progress.
 */
internal fun labyrinthRosterAfterUnfindableExclusion(
    roster: List<LabyrinthJoinedCharacter>,
    unfindableIds: Set<String>,
): List<LabyrinthJoinedCharacter>? {
    if (unfindableIds.isEmpty()) return null
    val canonical = unfindableIds.map(::canonicalLabyrinthRoleId).toSet()
    val remaining = roster.filterNot { canonicalLabyrinthRoleId(it.characterId) in canonical }
    return remaining.takeIf { it.size != roster.size }
}
