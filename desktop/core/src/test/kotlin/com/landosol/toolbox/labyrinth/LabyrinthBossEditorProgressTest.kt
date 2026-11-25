package com.landosol.toolbox.labyrinth

import com.landosol.toolbox.automation.AutomationSessionManager
import com.landosol.toolbox.labyrinth.vision.*
import org.junit.Assert.*
import org.junit.Test

class LabyrinthBossEditorProgressTest {
    private fun session() = LabyrinthEntryRecognitionSession(
        sessionManager = AutomationSessionManager(), processorFactory = { { error("no capture") } },
    )

    private fun field(session: LabyrinthEntryRecognitionSession, name: String) =
        session.javaClass.getDeclaredField(name).apply { isAccessible = true }

    private fun observe(session: LabyrinthEntryRecognitionSession, index: Int, stable: Boolean = true) {
        val result = LabyrinthEntryFrameResult(
            observation = LabyrinthEntryPageObservation(LabyrinthEntryPageState.BATTLE_TEAM_SELECTION, 1.0,
                emptyMap(), LabyrinthAnchorScores(emptyMap())), matchedFeatures = emptyList(), elapsedMillis = 0,
            battleTeamSelection = LabyrinthBattleTeamObservation(
                currentFilter = LabyrinthBattleElementFilter.ALL, filters = emptyList(),
                visibleCharacters = emptyList(), selectedCharacters = emptyList(),
                scrollbar = LabyrinthBattleScrollbarObservation(EntryPixelRect(1825,377,28,349), null, false, false, 0.0),
                recognitionState = if (stable) LabyrinthBattleTeamRecognitionState.STABLE else
                    LabyrinthBattleTeamRecognitionState.WAITING_FOR_STABILITY,
                bossTeamIndex = index,
            ),
        )
        session.javaClass.getDeclaredMethod("synchronizeBossEditor", LabyrinthEntryFrameResult::class.java)
            .apply { isAccessible = true }.invoke(session, result)
    }

    @Test fun `only visually confirmed next tab records first team without marking it battle used`() {
        val session = session()
        field(session, "bossTeamMode").set(session, LabyrinthBossTeamMode.SINGLE_TEAM)
        observe(session, 1)
        field(session, "pendingBossTeamAdvance").set(session, 2 to listOf("1044", "1078"))
        observe(session, 1)
        assertTrue((field(session, "committedBattleCharacterIds").get(session) as Set<*>).isEmpty())
        observe(session, 2, stable = false)
        assertEquals(1, session.state.value.combatContext?.teamIndex)
        observe(session, 2)
        assertEquals(listOf("1044", "1078"), field(session, "preparedBossFirstTeamIds").get(session))
        assertTrue((field(session, "committedBattleCharacterIds").get(session) as Set<*>).isEmpty())
        assertEquals(2, session.state.value.combatContext?.teamIndex)
        assertEquals(false, field(session, "bossEditorBlocked").get(session))
        assertNull(field(session, "pendingBossTeamAdvance").get(session))
    }

    @Test fun `untracked resume and manual tab jumps cannot silently reuse previous team`() {
        val resumed = session()
        observe(resumed, 2)
        assertEquals(true, field(resumed, "bossEditorBlocked").get(resumed))
        val manual = session()
        observe(manual, 1)
        observe(manual, 3)
        assertEquals(true, field(manual, "bossEditorBlocked").get(manual))
        assertTrue((field(manual, "committedBattleCharacterIds").get(manual) as Set<*>).isEmpty())
    }

    @Test fun `blocked boss editor state is reserved for safe automatic resync rather than team reuse`() {
        val session = session()
        observe(session, 1)
        observe(session, 3)
        assertEquals(true, field(session, "bossEditorBlocked").get(session))
        assertNull(field(session, "pendingBossTeamAdvance").get(session))
        assertTrue((field(session, "committedBattleCharacterIds").get(session) as Set<*>).isEmpty())
        assertEquals(3, session.state.value.combatContext?.teamIndex)
    }
}
