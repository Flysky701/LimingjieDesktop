package com.landosol.toolbox.labyrinth

import com.landosol.toolbox.automation.AutomationSessionId
import com.landosol.toolbox.labyrinth.vision.EntryPixelRect
import com.landosol.toolbox.labyrinth.vision.LabyrinthBattleElementFilter
import com.landosol.toolbox.labyrinth.vision.LabyrinthBattleScrollbarObservation
import com.landosol.toolbox.labyrinth.vision.LabyrinthBattleTeamObservation
import com.landosol.toolbox.labyrinth.vision.LabyrinthBattleTeamRecognitionState
import org.junit.Assert.assertEquals
import org.junit.Test

class LabyrinthBattleRosterSearchTest {
    @Test fun `missing scrollbar permits probes and repeated unchanged portraits establish boundaries`() {
        val search = LabyrinthBattleRosterSearch()
        val frame = observation(0.5).let { it.copy(scrollbar = it.scrollbar.copy(visible = false, thumbRect = null)) }
        assertEquals(LabyrinthBattleRosterSearchDecision.TO_TOP, search.observe(session, frame, listOf("A")))
        fun swipe(direction: LabyrinthBattleRosterScrollDirection): LabyrinthBattleRosterSearchDecision {
            search.recordExecutedScroll(direction, 0.5)
            var decision = LabyrinthBattleRosterSearchDecision.WAIT_FOR_SETTLE
            repeat(12) { decision = search.observe(session, frame, listOf("A")) }
            return decision
        }
        assertEquals(LabyrinthBattleRosterSearchDecision.TO_TOP, swipe(LabyrinthBattleRosterScrollDirection.TO_TOP))
        assertEquals(LabyrinthBattleRosterSearchDecision.NEXT_PAGE, swipe(LabyrinthBattleRosterScrollDirection.TO_TOP))
        assertEquals(LabyrinthBattleRosterSearchDecision.NEXT_PAGE, swipe(LabyrinthBattleRosterScrollDirection.NEXT_PAGE))
        assertEquals(LabyrinthBattleRosterSearchDecision.EXHAUSTED, swipe(LabyrinthBattleRosterScrollDirection.NEXT_PAGE))
    }

    @Test fun `blank unstable frames never prove an end or exhaust a finite probe budget`() {
        val search = LabyrinthBattleRosterSearch()
        val blank = observation(0.5).copy(
            visibleCharacters = emptyList(),
            recognitionState = LabyrinthBattleTeamRecognitionState.WAITING_FOR_STABILITY,
        )
        repeat(30) {
            assertEquals(LabyrinthBattleRosterSearchDecision.TO_TOP, search.observe(session, blank, listOf("A")))
        }
        repeat(12) {
            search.recordExecutedScroll(LabyrinthBattleRosterScrollDirection.TO_TOP, 0.5)
            var decision = LabyrinthBattleRosterSearchDecision.WAIT_FOR_SETTLE
            repeat(12) { decision = search.observe(session, blank, listOf("A")) }
            assertEquals(LabyrinthBattleRosterSearchDecision.TO_TOP, decision)
        }
    }

    @Test fun `a stable roster that resolves no portrait ends instead of rewinding forever`() {
        // 2026-09-19 live: the 有效效果 filter listed a single card the roster recogniser could not
        // resolve, and its one tall thumb reported canScroll=false. A probe concludes by
        // comparing portraits before and after a swipe, so with no portrait at all there was
        // nothing to compare and the scan rewound the list every 17 seconds for the rest of the
        // session. Zero resolvable cards is an answer, not a reason to keep swiping.
        val search = LabyrinthBattleRosterSearch()
        val blank = observation(0.0).copy(
            currentFilter = LabyrinthBattleElementFilter.EFFECTIVE_EFFECT,
            visibleCharacters = emptyList(),
            scrollbar = LabyrinthBattleScrollbarObservation(
                trackRect = EntryPixelRect(1825, 235, 28, 500),
                thumbRect = EntryPixelRect(1825, 235, 28, 500),
                visible = true,
                canScroll = false,
                position = 0.0,
            ),
        )
        var swipes = 0
        repeat(200) {
            val decision = search.observe(session, blank, listOf("effective-scan"))
            if (decision == LabyrinthBattleRosterSearchDecision.EXHAUSTED) {
                // Several real swipes are still spent first, so a merely slow list is not cut off.
                assertEquals(4, swipes)
                return
            }
            if (decision != LabyrinthBattleRosterSearchDecision.WAIT_FOR_SETTLE) {
                swipes++
                search.recordExecutedScroll(
                    if (decision == LabyrinthBattleRosterSearchDecision.TO_TOP) {
                        LabyrinthBattleRosterScrollDirection.TO_TOP
                    } else {
                        LabyrinthBattleRosterScrollDirection.NEXT_PAGE
                    },
                    0.0,
                )
            }
        }
        throw AssertionError("search never finished; swipes=$swipes")
    }

    @Test fun `a roster that keeps resolving portraits is never cut short by the blank budget`() {
        val search = LabyrinthBattleRosterSearch()
        fun frame(id: String) = observation(0.5).let {
            it.copy(visibleCharacters = listOf(character().copy(characterId = id)),
                scrollbar = it.scrollbar.copy(visible = false, thumbRect = null, canScroll = false))
        }
        var decision = search.observe(session, frame("a"), listOf("A"))
        for (id in listOf("b", "c", "d", "e", "f", "g")) {
            search.recordExecutedScroll(LabyrinthBattleRosterScrollDirection.TO_TOP, 0.5)
            repeat(12) { decision = search.observe(session, frame(id), listOf("A")) }
            assertEquals(LabyrinthBattleRosterSearchDecision.TO_TOP, decision)
        }
    }

    @Test fun `changed portraits after probes do not falsely confirm a boundary`() {
        val search = LabyrinthBattleRosterSearch()
        fun frame(id: String) = observation(0.5).let {
            it.copy(visibleCharacters = listOf(character().copy(characterId = id)),
                scrollbar = it.scrollbar.copy(visible = false, thumbRect = null))
        }
        search.observe(session, frame("a"), listOf("A"))
        for (id in listOf("b", "c", "d")) {
            search.recordExecutedScroll(LabyrinthBattleRosterScrollDirection.TO_TOP, 0.5)
            var decision = LabyrinthBattleRosterSearchDecision.WAIT_FOR_SETTLE
            repeat(12) { decision = search.observe(session, frame(id), listOf("A")) }
            assertEquals(LabyrinthBattleRosterSearchDecision.TO_TOP, decision)
        }
    }

    private val session = AutomationSessionId(1L)

    @Test fun `visible end of short roster finishes only after top was observed`() {
        val search = LabyrinthBattleRosterSearch()
        fun shortRoster(position: Double) = observation(position).let {
            it.copy(scrollbar = it.scrollbar.copy(contentEndVisible = true))
        }
        assertEquals(LabyrinthBattleRosterSearchDecision.TO_TOP,
            search.observe(session, shortRoster(0.5), listOf("A")))
        assertEquals(LabyrinthBattleRosterSearchDecision.EXHAUSTED,
            search.observe(session, shortRoster(0.0), listOf("A")))
        val changed = shortRoster(0.5).copy(currentFilter = LabyrinthBattleElementFilter.EFFECTIVE_EFFECT)
        assertEquals(LabyrinthBattleRosterSearchDecision.TO_TOP,
            search.observe(session, changed, listOf("A")))
    }

    @Test fun `rewind rebound with empty trailing space completes a short roster`() {
        val search = LabyrinthBattleRosterSearch()
        val short = observation(0.05).let {
            it.copy(scrollbar = it.scrollbar.copy(contentEndVisible = true))
        }
        assertEquals(LabyrinthBattleRosterSearchDecision.TO_TOP, search.observe(session, short, listOf("A")))
        search.recordExecutedScroll(LabyrinthBattleRosterScrollDirection.TO_TOP, 0.05)
        assertEquals(LabyrinthBattleRosterSearchDecision.WAIT_FOR_SETTLE, search.observe(session, short, listOf("A")))
        assertEquals(LabyrinthBattleRosterSearchDecision.EXHAUSTED, search.observe(session, short, listOf("A")))
    }
    private fun observation(position: Double) = LabyrinthBattleTeamObservation(
        currentFilter = LabyrinthBattleElementFilter.FIRE,
        filters = emptyList(), visibleCharacters = listOf(character()), selectedCharacters = emptyList(),
        scrollbar = LabyrinthBattleScrollbarObservation(
            EntryPixelRect(1825, 377, 28, 349), EntryPixelRect(1825, 450, 28, 70), true, true, position,
        ),
    )

    private fun character() = com.landosol.toolbox.labyrinth.vision.LabyrinthBattleCharacterMatch(
        slotId = "available_1",
        characterId = "1001",
        displayName = "测试角色",
        iconVariant = null,
        confidence = 0.95,
        screenRect = EntryPixelRect(100, 300, 195, 195),
        selected = false,
        trusted = true,
    )

    @Test fun `middle and bottom starts rewind before searching to the end`() {
        for (start in listOf(0.5, 1.0)) {
            val search = LabyrinthBattleRosterSearch()
            for (position in listOf(start, start, 0.2)) {
                assertEquals(LabyrinthBattleRosterSearchDecision.TO_TOP, search.observe(session, observation(position), listOf("A")))
            }
            for (position in listOf(0.0, 0.3, 0.7)) {
                assertEquals(LabyrinthBattleRosterSearchDecision.NEXT_PAGE, search.observe(session, observation(position), listOf("A")))
            }
            repeat(3) {
                assertEquals(LabyrinthBattleRosterSearchDecision.EXHAUSTED, search.observe(session, observation(1.0), listOf("A")))
            }
        }
    }

    @Test fun `filter team recommendation and session changes each restart the search`() {
        for (change in 0..3) {
            val search = LabyrinthBattleRosterSearch()
            search.observe(session, observation(0.0), listOf("A"))
            val changed = when (change) {
                0 -> observation(0.5).copy(currentFilter = LabyrinthBattleElementFilter.WATER)
                1 -> observation(0.5).copy(bossTeamIndex = 2)
                else -> observation(0.5)
            }
            assertEquals(LabyrinthBattleRosterSearchDecision.TO_TOP, search.observe(
                if (change == 3) AutomationSessionId(2L) else session,
                changed, if (change == 2) listOf("B") else listOf("A"),
            ))
        }
    }

    @Test fun `unstable top and missing scrollbar do not confirm a rewind`() {
        val search = LabyrinthBattleRosterSearch()
        val unstable = observation(0.0).copy(recognitionState = LabyrinthBattleTeamRecognitionState.WAITING_FOR_STABILITY)
        assertEquals(LabyrinthBattleRosterSearchDecision.TO_TOP, search.observe(session, unstable, listOf("A")))
        val absent = observation(0.0).let { it.copy(scrollbar = it.scrollbar.copy(thumbRect = null)) }
        assertEquals(LabyrinthBattleRosterSearchDecision.TO_TOP, search.observe(session, absent, listOf("A")))
        assertEquals(LabyrinthBattleRosterSearchDecision.TO_TOP, search.observe(session, observation(0.6), listOf("A")))
        search.observe(session, observation(0.0), listOf("A"))
        search.reset()
        assertEquals(LabyrinthBattleRosterSearchDecision.TO_TOP, search.observe(session, observation(0.6), listOf("A")))
    }

    @Test fun `overscroll blank frame waits for rebound then recognizes bottom boundary`() {
        val search = LabyrinthBattleRosterSearch()
        search.observe(session, observation(0.0), listOf("A"))
        assertEquals(LabyrinthBattleRosterSearchDecision.NEXT_PAGE,
            search.observe(session, observation(0.93), listOf("A")))
        search.recordExecutedScroll(LabyrinthBattleRosterScrollDirection.NEXT_PAGE, 0.93)

        val blank = observation(0.93).copy(
            visibleCharacters = emptyList(),
            recognitionState = LabyrinthBattleTeamRecognitionState.WAITING_FOR_STABILITY,
        )
        assertEquals(LabyrinthBattleRosterSearchDecision.WAIT_FOR_SETTLE,
            search.observe(session, blank, listOf("A")))

        // Elastic overscroll has bounced back to the same last-row stop. The wider threshold is
        // used only because repeated no-movement rebound frames were observed.
        assertEquals(LabyrinthBattleRosterSearchDecision.WAIT_FOR_SETTLE,
            search.observe(session, observation(0.93), listOf("A")))
        assertEquals(LabyrinthBattleRosterSearchDecision.EXHAUSTED,
            search.observe(session, observation(0.93), listOf("A")))
    }

    @Test fun `stale pre swipe frame does not abort before fresh moved frame arrives`() {
        val search = LabyrinthBattleRosterSearch()
        search.observe(session, observation(0.0), listOf("A"))
        assertEquals(LabyrinthBattleRosterSearchDecision.NEXT_PAGE,
            search.observe(session, observation(0.40), listOf("A")))
        search.recordExecutedScroll(LabyrinthBattleRosterScrollDirection.NEXT_PAGE, 0.40)

        // Capture can race the gesture executor and deliver the old 0.40 frame once more.
        assertEquals(LabyrinthBattleRosterSearchDecision.WAIT_FOR_SETTLE,
            search.observe(session, observation(0.40), listOf("A")))
        // The real post-swipe frame then arrives and must be accepted instead of having already
        // stopped the session as UNAVAILABLE.
        assertEquals(LabyrinthBattleRosterSearchDecision.NEXT_PAGE,
            search.observe(session, observation(0.72), listOf("A")))
    }

    @Test fun `real scroll movement clears rebound wait and continues search`() {
        val search = LabyrinthBattleRosterSearch()
        assertEquals(LabyrinthBattleRosterSearchDecision.TO_TOP,
            search.observe(session, observation(0.70), listOf("A")))
        search.recordExecutedScroll(LabyrinthBattleRosterScrollDirection.TO_TOP, 0.70)
        assertEquals(LabyrinthBattleRosterSearchDecision.WAIT_FOR_SETTLE,
            search.observe(session, observation(0.70).copy(visibleCharacters = emptyList()), listOf("A")))
        assertEquals(LabyrinthBattleRosterSearchDecision.TO_TOP,
            search.observe(session, observation(0.40), listOf("A")))
    }

    @Test fun `tall filtered thumb confirms top from physical edge after rebound`() {
        val search = LabyrinthBattleRosterSearch()
        val effectiveTop = observation(0.111).copy(
            currentFilter = LabyrinthBattleElementFilter.EFFECTIVE_EFFECT,
            scrollbar = LabyrinthBattleScrollbarObservation(
                trackRect = EntryPixelRect(1825, 235, 28, 500),
                thumbRect = EntryPixelRect(1825, 248, 28, 383),
                visible = true,
                canScroll = true,
                position = 0.111,
            ),
        )

        // The normalized value alone looks slightly away from the top, so first request a real
        // rewind gesture. If the list elastically rebounds to the same physical top stop twice,
        // the 13 px end-cap is accepted as boundary evidence instead of aborting the scan.
        assertEquals(
            LabyrinthBattleRosterSearchDecision.TO_TOP,
            search.observe(session, effectiveTop, listOf("effective-scan")),
        )
        search.recordExecutedScroll(LabyrinthBattleRosterScrollDirection.TO_TOP, 0.111)
        assertEquals(
            LabyrinthBattleRosterSearchDecision.WAIT_FOR_SETTLE,
            search.observe(session, effectiveTop, listOf("effective-scan")),
        )
        assertEquals(
            LabyrinthBattleRosterSearchDecision.NEXT_PAGE,
            search.observe(session, effectiveTop, listOf("effective-scan")),
        )
    }

    @Test fun `full height thumb is not a single page and still probes with real swipes`() {
        // Reported 2026-09-14: the whole grey-blue track passed the loose blue rule, so the
        // recognizer reported a full-height thumb (canScroll=false, position=0.0). The search
        // then returned EXHAUSTED without a single swipe. An unmeasured thumb must probe instead.
        val search = LabyrinthBattleRosterSearch()
        val fullThumb = observation(0.0).let {
            it.copy(scrollbar = it.scrollbar.copy(
                thumbRect = EntryPixelRect(1825, 377, 28, 349), canScroll = false, position = 0.0,
            ))
        }
        assertEquals(LabyrinthBattleRosterSearchDecision.TO_TOP, search.observe(session, fullThumb, listOf("A")))
        search.recordExecutedScroll(LabyrinthBattleRosterScrollDirection.TO_TOP, 0.0)
        var decision = LabyrinthBattleRosterSearchDecision.WAIT_FOR_SETTLE
        repeat(12) { decision = search.observe(session, fullThumb, listOf("A")) }
        assertEquals(LabyrinthBattleRosterSearchDecision.TO_TOP, decision)
        search.recordExecutedScroll(LabyrinthBattleRosterScrollDirection.TO_TOP, 0.0)
        repeat(12) { decision = search.observe(session, fullThumb, listOf("A")) }
        // Two unchanged rewind probes prove the top; the search then moves downward.
        assertEquals(LabyrinthBattleRosterSearchDecision.NEXT_PAGE, decision)
    }

    @Test fun `changed portraits end an unmeasured scrollbar wait before the settle budget`() {
        val search = LabyrinthBattleRosterSearch()
        fun frame(id: String) = observation(0.0).let {
            it.copy(visibleCharacters = listOf(character().copy(characterId = id)),
                scrollbar = it.scrollbar.copy(visible = false, thumbRect = null, canScroll = false))
        }
        assertEquals(LabyrinthBattleRosterSearchDecision.TO_TOP, search.observe(session, frame("a"), listOf("A")))
        search.recordExecutedScroll(LabyrinthBattleRosterScrollDirection.TO_TOP, 0.0)
        // Stale pre-swipe capture keeps waiting; the first genuinely different stable page
        // already proves the gesture moved the list and continues the search immediately.
        assertEquals(LabyrinthBattleRosterSearchDecision.WAIT_FOR_SETTLE, search.observe(session, frame("a"), listOf("A")))
        assertEquals(LabyrinthBattleRosterSearchDecision.TO_TOP, search.observe(session, frame("b"), listOf("A")))
    }

    @Test fun `tall filtered thumb confirms bottom from physical edge after rebound`() {
        val search = LabyrinthBattleRosterSearch()
        // Establish a visually confirmed top before moving to the other end.
        search.observe(session, observation(0.0), listOf("effective-scan"))
        val effectiveBottom = observation(0.889).copy(
            currentFilter = LabyrinthBattleElementFilter.FIRE,
            scrollbar = LabyrinthBattleScrollbarObservation(
                trackRect = EntryPixelRect(1825, 235, 28, 500),
                thumbRect = EntryPixelRect(1825, 339, 28, 383),
                visible = true,
                canScroll = true,
                position = 0.889,
            ),
        )
        assertEquals(
            LabyrinthBattleRosterSearchDecision.NEXT_PAGE,
            search.observe(session, effectiveBottom, listOf("effective-scan")),
        )
        search.recordExecutedScroll(LabyrinthBattleRosterScrollDirection.NEXT_PAGE, 0.889)
        assertEquals(
            LabyrinthBattleRosterSearchDecision.WAIT_FOR_SETTLE,
            search.observe(session, effectiveBottom, listOf("effective-scan")),
        )
        assertEquals(
            LabyrinthBattleRosterSearchDecision.EXHAUSTED,
            search.observe(session, effectiveBottom, listOf("effective-scan")),
        )
    }
}
