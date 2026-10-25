package com.landosol.toolbox.desktop.app.ux

import androidx.compose.runtime.*
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.landosol.toolbox.account.NormalizedAccountInput
import com.landosol.toolbox.desktop.app.DesktopAppGraph
import com.landosol.toolbox.desktop.app.control.*
import com.landosol.toolbox.desktop.app.adapters.storage.DesktopGoal
import com.landosol.toolbox.desktop.app.ui.DesktopApp
import com.landosol.toolbox.labyrinth.rerollSettings
import com.landosol.toolbox.protocol.bilibili.GameServer
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalTestApi::class)
class RunHomeUiTest {
    @get:Rule val compose=createComposeRule()
    @get:Rule val directory=TemporaryFolder()

    @Test fun inlineSaveCancelAdvancedReturnF5GuardAndRestartUseActualStores()=runBlocking {
        val data=directory.root.toPath()
        val device=UiTestNoIoDevice()
        val graph=DesktopAppGraph(data,suppliedDevice=device,startupUpdates=false)
        var control:LocalGraphControlPort?=null
        var view:ControlSessionUi?=null
        var shown by mutableStateOf(true)
        try {
            graph.settings.update{it.copy(wizardCompleted=true)}
            val accountId=graph.accountRepository.create(NormalizedAccountInput("离线示例","unused-offline-login","unused-offline-password",null,GameServer.CN_BILIBILI))
            withTimeout(10000){graph.labyrinthController.uiState.first{it.settingsReady&&it.selectedAccount!=null}}
            withTimeout(10000){graph.ux.credentials.first{it[accountId]==true}}
            val port=LocalGraphControlPort.create(graph,verifiedForUi=true);control=port
            val ui=ControlSessionUi(port);view=ui
            compose.setContent{if(shown)DesktopApp(ui)}
            compose.onNodeWithContentDescription("美食殿堂 次数").performTextInput("3")
            compose.onNodeWithText("开始 F5").assertIsNotEnabled()
            compose.onNodeWithText("保存更改").assertIsDisplayed()
            compose.onRoot().performKeyInput{pressKey(Key.F5)}
            compose.runOnIdle{assertFalse(graph.busy);assertTrue(graph.ux.preferences.state.value.goals.isEmpty());assertFalse(ControlOps.START in port.requests)}
            compose.onNodeWithText("高级配置").performClick()
            compose.onNodeWithText("放弃未保存的常用配置？").assertExists()
            compose.onNodeWithText("留在此页").performClick()
            compose.onNodeWithText("保存更改").performClick()
            compose.waitUntil(5000){graph.ux.preferences.state.value.goals==listOf(DesktopGoal(1,3))}
            compose.onNodeWithContentDescription("美食殿堂 次数").performTextReplacement("9")
            compose.onNodeWithText("取消更改").performClick()
            compose.onNodeWithContentDescription("美食殿堂 次数").assertTextEquals("3")

            // Use the original form's actual Save action, then return to the home shortcut.
            compose.onNodeWithText("高级配置").performClick()
            compose.onNodeWithText("开始 F5").assertIsNotEnabled()
            compose.onNodeWithText("刷开局设置").performClick()
            compose.onNodeWithText("刷开局设置 · 当前账号",useUnmergedTree=true).assertExists()
            compose.onAllNodes(hasText("2") and hasAnyAncestor(isDialog())).onFirst().performClick()
            compose.onNodeWithText("保存").performClick()
            compose.waitUntil(5000){graph.labyrinthController.uiState.value.selectedDifficulty==2}
            compose.onNodeWithText("返回运行台 · Esc").performClick()
            compose.runOnIdle{assertFalse(ControlOps.START in port.requests);assertNull(port.error.value)}
            compose.onNodeWithText("2").assertExists()
            compose.onNodeWithContentDescription("美食殿堂 次数").assertTextEquals("3")
            compose.onNodeWithText("刷开局").performClick()
            compose.waitUntil(5000){graph.ux.preferences.state.value.selectedTask=="REROLL"}
            compose.onNodeWithText("开始 F5").assertIsEnabled()
            compose.onNodeWithText("2").performClick()
            compose.onNodeWithText("3").performClick()
            compose.onNodeWithText("开始 F5").assertIsNotEnabled()
            compose.onRoot().performKeyInput{pressKey(Key.F5)}
            compose.onNodeWithText("确认彻底撤退").assertDoesNotExist()
            compose.onNodeWithText("取消更改").performClick()
            compose.onNodeWithText("高级配置").performClick()
            compose.onRoot().performKeyInput{pressKey(Key.F5)}
            compose.onNodeWithText("确认彻底撤退").assertDoesNotExist()
            compose.onNodeWithText("返回运行台 · Esc").performClick()
            compose.runOnIdle{assertFalse(ControlOps.START in port.requests);assertNull(port.error.value);shown=false}
            compose.waitForIdle()
        } finally {view?.close();control?.shutdown() ?: graph.shutdown()}
        assertEquals(0,device.attemptedIo.get())

        val reopenedDevice=UiTestNoIoDevice()
        val reopened=DesktopAppGraph(data,suppliedDevice=reopenedDevice,startupUpdates=false)
        try {
            val state=withTimeout(10000){reopened.labyrinthController.uiState.first{it.settingsReady&&it.selectedAccount!=null}}
            assertEquals(2,state.rerollSettings().difficulty)
            assertEquals(listOf(DesktopGoal(1,3)),reopened.ux.preferences.state.value.goals)
            assertEquals("REROLL",reopened.ux.preferences.state.value.selectedTask)
            assertFalse(reopened.busy)
        } finally {reopened.shutdown()}
        assertEquals(0,reopenedDevice.attemptedIo.get())
    }
}
