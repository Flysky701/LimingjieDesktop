package com.landosol.toolbox.desktop.app.ux

import com.landosol.toolbox.account.AccountListItem
import com.landosol.toolbox.desktop.app.adapters.storage.*
import com.landosol.toolbox.desktop.app.ui.*
import com.landosol.toolbox.labyrinth.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RunSetupDraftTest {
    @get:Rule val directory=TemporaryFolder()
    private val ready=LabyrinthUiState(selectedAccount=AccountListItem(7,"示例账号","示例服务器",null,true),settingsReady=true)

    @Test fun quickEditsKeepAdvancedRulesAndUseUpstreamLimits() {
        val source=ready.copy(selectedArea3BossIds=setOf(319604),selectedArea5BossIds=emptySet(),retireExisting=false,thirdBlockChoice=LabyrinthThirdBlockChoice.EITHER)
        val draft=RunSetupDraft(source,listOf(DesktopGoal(1,3,false)))
        assertEquals("账号凭据缺失",startMissing(DesktopTask.REROLL,RunConsoleState(task=source),true,true,false))
        assertEquals("正在检查账号凭据",startMissing(DesktopTask.READ,RunConsoleState(task=source),true,true,null))
        draft.settings=draft.settings.copy(difficulty=2,valueAllowance=0,rerollUntilFound=false,maxAttempts="800")
        assertNull(draft.validationError(source))
        assertEquals(setOf(319604),draft.settings.area3BossIds)
        assertTrue(draft.settings.area5BossIds.isEmpty())
        assertFalse(draft.settings.retireExisting)
        assertEquals(LabyrinthThirdBlockChoice.EITHER,draft.settings.thirdBlockChoice)
        draft.settings=draft.settings.copy(maxAttempts="801")
        assertEquals("最大尝试次数无效",draft.validationError(source))
        draft.settings=draft.settings.copy(maxAttempts="1",difficulty=5)
        assertEquals("所选难度尚未解锁",draft.validationError(source.copy(availableDifficulties=listOf(1,2,3))))
    }

    @Test fun lateAccountLoadAndAdvancedReturnPreserveOnlyUneditedParts() {
        val loading=ready.copy(settingsReady=false)
        val draft=RunSetupDraft(loading,emptyList())
        draft.goals=draft.goals.map{if(it.guildId==1)it.copy(count="4")else it}
        val loaded=ready.copy(selectedDifficulty=2,valueAllowance=0,selectedArea3BossIds=emptySet(),retireExisting=false)
        assertEquals(loaded.rerollSettings(),draft.settingsToSave(loaded))
        draft.sync(loaded,emptyList())
        assertEquals(2,draft.settings.difficulty)
        assertEquals("4",draft.goals.first().count)
        assertFalse(draft.settings.retireExisting)
        assertTrue(draft.settings.area3BossIds.isEmpty())
        assertTrue(draft.dirty)
        assertEquals("常用配置尚未保存",runSetupBlock(draft.dirty,false))
        draft.reset(loaded,listOf(DesktopGoal(1,4)))
        assertFalse(draft.dirty)
        val advanced=loaded.copy(selectedDifficulty=3,selectedArea3BossIds=emptySet(),retireExisting=false)
        draft.sync(advanced,listOf(DesktopGoal(1,2,false)))
        assertEquals(advanced.rerollSettings(),draft.settings)
        assertEquals(listOf(DesktopGoal(1,2,false)),draft.savedGoals())
        assertNull(runSetupBlock(draft.dirty,false))
        assertEquals("请先返回运行台",runSetupBlock(false,true))
        draft.goals=draft.goals.map{it.copy(count="0")}
        assertTrue(draft.savedGoals().isEmpty())
        draft.reset(advanced,listOf(DesktopGoal(1,2,false)))
        assertFalse(draft.dirty)
    }

    @Test fun preferencesReopenLastTaskAndTargetsWithoutChangingOtherPreferences() {
        val root=directory.root.toPath()
        DesktopPreferencesStore(root){fail(it)}.apply {
            update{it.copy(theme=DesktopThemeMode.DARK,selectedTask=DesktopTask.REROLL.name,goals=listOf(DesktopGoal(1,99,false),DesktopGoal(5,2)))}
        }
        val loaded=DesktopPreferencesStore(root){fail(it)}.state.value
        assertEquals(DesktopTask.REROLL.name,loaded.selectedTask)
        assertEquals(listOf(DesktopGoal(1,99,false),DesktopGoal(5,2)),loaded.goals)
        assertEquals(DesktopThemeMode.DARK,loaded.theme)
        val draft=RunSetupDraft(ready,loaded.goals)
        assertEquals(loaded.goals,draft.savedGoals())
        assertFalse(draft.dirty)
    }
}
