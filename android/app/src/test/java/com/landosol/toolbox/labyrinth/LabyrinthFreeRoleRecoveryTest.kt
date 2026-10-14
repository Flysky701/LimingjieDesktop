package com.landosol.toolbox.labyrinth

import com.landosol.toolbox.automation.*
import com.landosol.toolbox.labyrinth.vision.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class LabyrinthFreeRoleRecoveryTest {
    @Test fun `session recovery permits selecting an unowned role on the first stable picker frame`() =
        exercise(selected = false, recover = true)

    @Test fun `session recovery permits confirming a visually selected role`() =
        exercise(selected = true, recover = true)

    @Test fun `free picker still waits for a previous gesture to land and settle`() =
        exercise(selected = false, recover = false)

    private fun exercise(selected: Boolean, recover: Boolean) = runBlocking {
        val manager = AutomationSessionManager()
        val taps = AtomicInteger()
        val session = LabyrinthEntryRecognitionSession(
            sessionManager = manager, captureActive = { true },
            processorFactory = { { error("this test supplies recognized frames directly") } },
            actionExecutor = SessionBoundActionExecutor(manager) {
                taps.incrementAndGet()
                AutomationBackendResult.Completed
            },
            roleRewardChoicePlanner = LabyrinthRoleRewardChoicePlanner(
                mapOf("1044" to LabyrinthRoleProfile("1044", "伊莉亚", userScore = 90.0)),
                LabyrinthRoleChoicePolicy(LabyrinthTeamOptimizer(LabyrinthTeamScorer(
                    LabyrinthTeamScoringConfig(attributeDamageBonus = (1..5).associateWith { 0.0 }),
                ))),
            ),
            clock = { 17_789_586L },
        )
        try {
            assertTrue(session.startAutomation() is LabyrinthEntryRecognitionStartResult.Started)
            field(session, "lastPostEntryActionAt").setLong(session, 17_787_586L)
            field(session, "lastPostEntryActionLandedAt").setLong(session, 17_789_486L)
            if (recover) {
                session.javaClass.getDeclaredMethod("restartEntryNavigationAfterSessionError")
                    .apply { isAccessible = true }.invoke(session)
                assertEquals(Long.MIN_VALUE, field(session, "lastPostEntryActionAt").getLong(session))
            }
            val inviteRect = EntryPixelRect(1430, 900, 400, 90)
            val frame = LabyrinthEntryFrameResult(
                observation = LabyrinthEntryPageObservation(
                    LabyrinthEntryPageState.INITIAL_CHARACTER_SELECTION, 0.994, emptyMap(),
                    LabyrinthAnchorScores(mapOf(EntryAnchorId.INVITE_ENABLED_STANDARD to 0.99)),
                ),
                anchorMatches = mapOf(EntryAnchorId.INVITE_ENABLED_STANDARD to EntryAnchorMatch(0.99, inviteRect)),
                matchedFeatures = emptyList(), elapsedMillis = 0,
                openingCharacterSelection = LabyrinthBattleTeamObservation(
                    currentFilter = LabyrinthBattleElementFilter.ALL, filters = emptyList(),
                    visibleCharacters = listOf(
                        LabyrinthBattleCharacterMatch("available_row_1_1", "1044", "伊莉亚", "31", 0.84,
                            EntryPixelRect(120, 280, 195, 195), selected = selected),
                        // A weak sibling must not prevent acting on the independently trusted card.
                        LabyrinthBattleCharacterMatch("available_row_1_2", null, null, null, 0.20,
                            EntryPixelRect(330, 280, 195, 195), selected = false),
                    ),
                    selectedCharacters = emptyList(),
                    scrollbar = LabyrinthBattleScrollbarObservation(
                        EntryPixelRect(1825, 280, 28, 580), null, false, false, 0.0,
                    ),
                ),
            )
            val handler = session.javaClass.declaredMethods.single {
                it.name.startsWith("handleEventFreeRoleSelection-")
            }.apply { isAccessible = true }
            handler.invoke(session, manager.current()!!.id.value, frame, 17_789_586L)
            if (!recover) {
                assertEquals("a recent landing must retain the cooldown", 0, taps.get())
                handler.invoke(session, manager.current()!!.id.value, frame, 17_791_586L)
            }
            val inFlight = field(session, "actionInFlight").get(session) as AtomicBoolean
            withTimeout(5_000) { while (inFlight.get()) delay(10) }
            assertEquals("a stable picker must dispatch its select/confirm action", 1, taps.get())
            assertTrue(session.state.value.lastActionLabel.orEmpty().contains(
                if (selected) "确认事件自由选角" else "事件自由选角：伊莉亚",
            ))
        } finally {
            session.stop()
        }
    }

    private fun field(session: LabyrinthEntryRecognitionSession, name: String) =
        session.javaClass.getDeclaredField(name).apply { isAccessible = true }
}
