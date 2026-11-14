package com.landosol.toolbox.labyrinth

import com.landosol.toolbox.automation.AutomationAction
import com.landosol.toolbox.automation.ScreenPoint
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Existing progress regression, updated to fresh two-frame ACK rather than a single signature change. */
class LabyrinthBossTeamSwitchProgressTest {
    @Test fun `a boss tab change clears the pending action and the consecutive failure count`() = runBlocking {
        val f = bossSession()
        try {
            dispatchNext(f)
            assertEquals(1, f.calls.get())
            assertEquals("BATTLE_TEAM_SELECTION:1", pendingSignature(f))
            f.set("battleTeamActionUnconfirmedAttempts", 2)
            f.invoke("handleUnconfirmedBattleTeamActionFrame", f.id, f.team(2, members = false), 1_300L)
            assertNotNull("one target frame is not an ACK", f.pending())
            assertEquals(2, f.get("battleTeamActionUnconfirmedAttempts"))
            f.invoke("handleUnconfirmedBattleTeamActionFrame", f.id, f.team(2, members = false), 1_600L)
            assertNull("a confirmed boss tab change is progress", f.pending())
            assertEquals(0, f.get("battleTeamActionUnconfirmedAttempts"))
            assertEquals("ACKED", (f.get("lastBossTabDiagnostic") as LabyrinthBossTabDiagnostic).phase)
        } finally { f.awaitActions(); f.close() }
    }

    @Test fun `a team page frame with an unchanged tab signature keeps the pending action and the count`() = runBlocking {
        val f = bossSession()
        try {
            dispatchNext(f)
            f.set("battleTeamActionUnconfirmedAttempts", 2)
            f.invoke("handleUnconfirmedBattleTeamActionFrame", f.id, f.team(1, members = false), 1_300L)
            f.invoke("handleUnconfirmedBattleTeamActionFrame", f.id, f.team(1, members = false), 1_600L)
            assertNotNull("an unchanged tab cannot count as progress", f.pending())
            assertEquals("BATTLE_TEAM_SELECTION:1", pendingSignature(f))
            assertEquals(2, f.get("battleTeamActionUnconfirmedAttempts"))
            assertEquals(1, f.calls.get())
        } finally { f.awaitActions(); f.close() }
    }

    private suspend fun bossSession() = Batch2SessionFixture().also {
        it.start()
        it.set("combatContext", LabyrinthCombatContext(LabyrinthCombatKind.BOSS))
        it.set("bossEditorTeamIndex", 1)
        it.set("bossEditorPreparationStage", LabyrinthBossEditorPreparationStage.COMPLETE)
    }

    private suspend fun dispatchNext(f: Batch2SessionFixture) {
        val step = LabyrinthBattleTeamExecutionStep(
            kind = LabyrinthBattleTeamExecutionKind.NEXT_BOSS_TEAM,
            key = "boss-next:1:${f.ids.joinToString(",")}",
            label = "Boss第1队编组完成，切换队伍2",
            action = AutomationAction.Tap(ScreenPoint(315f, 165f)), confirmedTeamIds = f.ids,
        )
        f.clock.set(1_000L)
        f.state { it.copy(lastResult = f.team(1, members = false)) }
        f.invoke("dispatchBattleTeamAction", f.id, step, 1_000L)
        f.awaitActions()
    }

    private fun pendingSignature(f: Batch2SessionFixture): String? = f.pending()?.let {
        it.javaClass.getDeclaredField("signature").apply { isAccessible = true }.get(it) as String?
    }
}
