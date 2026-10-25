package com.landosol.toolbox.desktop.app.control

import androidx.compose.runtime.*
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.landosol.toolbox.account.AccountListItem
import com.landosol.toolbox.account.AccountUiState
import com.landosol.toolbox.desktop.app.adapters.storage.DesktopPreferences
import com.landosol.toolbox.desktop.app.ui.*
import com.landosol.toolbox.desktop.platform.DesktopSettings
import com.landosol.toolbox.labyrinth.LabyrinthStrategySettings
import com.landosol.toolbox.labyrinth.LabyrinthUiState
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import javax.imageio.ImageIO
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Offscreen UI only; ports contain synthetic snapshots and never construct a Graph or device. */
@OptIn(ExperimentalTestApi::class)
class SharedRunTabsUiTest {
    @get:Rule val compose=createComposeRule()
    private val alice=AccountListItem(1,"共享甲","测试服",null,false)
    private val bob=AccountListItem(2,"共享乙","测试服",null,false)

    @Test fun singleGlobalPagesRemainUsableWithoutAConnectedDevice(){
        val global=FakePort("global",accounts=listOf(alice,bob),weight=37)
        compose.setContent{SharedRunTabsApp(global,emptyList(),null,callbacks())}
        compose.onNodeWithText("连接设备后打开运行页").assertIsDisplayed()
        compose.onNodeWithText("账号").performClick()
        compose.onAllNodesWithText("共享甲").onFirst().assertIsDisplayed()
        compose.onNodeWithText("设为当前").assertDoesNotExist()
        compose.onNodeWithText("策略").performClick()
        compose.onNodeWithText("37%").assertIsDisplayed()
        compose.onNodeWithText("设备").performClick()
        compose.onNodeWithText("尚未发现设备").assertIsDisplayed()
        compose.runOnIdle{assertTrue(global.commands.isEmpty())}
    }

    @Test fun selectingRunTabsChangesOnlyTheViewportAndHotkeysTargetTheSelectedSession(){
        val global=FakePort("global",accounts=listOf(alice,bob),weight=37)
        val a=FakePort("a",alice,busy=true,weight=81)
        val b=FakePort("b",bob,busy=true,weight=82)
        var selected by mutableStateOf("a")
        var stopAll=0
        val tabs=listOf(tab(a,alice),tab(b,bob))
        val devices=tabs.map{ManagedDeviceUi(it.deviceId,it.serial,it.name,it.id,it.boundAccountId,connected=true,busy=true,status="运行中")}
        compose.setContent{SharedRunTabsApp(global,tabs,selected,callbacks(select={selected=it},stopAll={stopAll++}),devices=devices)}
        compose.onRoot().performKeyInput{pressKey(Key.F7)}
        compose.waitUntil(5_000){ControlOps.STOP in a.commands}
        assertFalse(ControlOps.STOP in b.commands)
        compose.onNodeWithText("设备 b · 共享乙").performClick()
        compose.onRoot().performKeyInput{pressKey(Key.F7)}
        compose.waitUntil(5_000){ControlOps.STOP in b.commands}
        compose.runOnIdle{assertFalse(a.previewSelected);assertTrue(b.previewSelected)}
        val pixels=compose.onRoot().captureToImage().toPixelMap()
        val image=BufferedImage(pixels.width,pixels.height,BufferedImage.TYPE_INT_ARGB)
        for(y in 0 until pixels.height)for(x in 0 until pixels.width)image.setRGB(x,y,pixels[x,y].toArgb())
        val screenshot=Path.of("build/reports/shared-run-tabs.png")
        Files.createDirectories(screenshot.parent)
        ImageIO.write(image,"png",screenshot.toFile())
        compose.onNodeWithText("账号").performClick()
        compose.onAllNodesWithText("共享甲").onFirst().assertIsDisplayed()
        compose.onAllNodesWithText("共享乙").onFirst().assertIsDisplayed()
        compose.onRoot().performKeyInput{pressKey(Key.F7)}
        compose.onNodeWithText("策略").performClick()
        compose.onNodeWithText("37%").assertIsDisplayed()
        compose.onNodeWithText("停止全部").performClick()
        compose.runOnIdle{
            assertEquals(1,stopAll)
            assertTrue(global.commands.isEmpty())
            assertEquals(listOf(ControlOps.STOP),a.commands.toList())
            assertEquals(listOf(ControlOps.STOP),b.commands.toList())
        }
    }

    @Test fun eachDeviceKeepsItsQuickSetupDraftAcrossTabAndGlobalPageChanges(){
        val global=FakePort("global",accounts=listOf(alice,bob))
        val a=FakePort("a",alice)
        val b=FakePort("b",bob)
        var selected by mutableStateOf("a")
        compose.setContent{SharedRunTabsApp(global,listOf(tab(a,alice),tab(b,bob)),selected,callbacks(select={selected=it}))}
        val aGoal=hasContentDescription("美食殿堂 次数") and hasAnyAncestor(hasTestTag("run-a"))
        val bGoal=hasContentDescription("美食殿堂 次数") and hasAnyAncestor(hasTestTag("run-b"))
        compose.onNode(aGoal).performTextInput("3")
        compose.onNodeWithText("设备 b · 共享乙").performClick()
        compose.onNode(bGoal).performTextInput("5")
        compose.onNodeWithText("账号").performClick()
        compose.onAllNodesWithText("共享甲").onFirst().assertIsDisplayed()
        compose.onNodeWithText("运行").performClick()
        compose.onNode(bGoal).assertTextEquals("5")
        compose.onNodeWithText("设备 a · 共享甲 •").performClick()
        compose.onNode(aGoal).assertTextEquals("3")
        compose.runOnIdle{assertTrue(a.commands.isEmpty());assertTrue(b.commands.isEmpty());assertTrue(global.commands.isEmpty())}
    }

    private fun tab(port:FakePort,account:AccountListItem)=DeviceRunTab(port.instanceId,"设备 ${port.instanceId}",port,"device-${port.instanceId}","serial-${port.instanceId}",account.id)
    private fun callbacks(select:(String)->Unit={},stopAll:()->Unit={})=SharedRunShellCallbacks(
        selectSession=select,discover={},connect={},bindAccount={_,_->},disconnect={},closeSession={},reconnectSession={},stopAll=stopAll,
    )

    private class FakePort(
        override val instanceId:String,account:AccountListItem?=null,busy:Boolean=false,
        accounts:List<AccountListItem> = emptyList(),weight:Int=70,
    ):ControlSessionPort{
        override val workerRunId="worker-$instanceId"
        override val snapshots=MutableStateFlow(buildJsonObject{
            put("console",ControlModelCodec.encode(RunConsoleState(task=LabyrinthUiState(selectedAccount=account,settingsReady=true),busy=busy)))
            put("settings",ControlModelCodec.encode(DesktopSettings(wizardCompleted=true)))
            put("preferences",ControlModelCodec.encode(DesktopPreferences()))
            put("accounts",ControlModelCodec.encode(AccountUiState(accounts=accounts)))
            put("strategy",ControlModelCodec.encode(LabyrinthStrategySettings(playerWeight=weight)))
        })
        override val connection=MutableStateFlow(WorkerConnectionStatus.CONNECTED)
        override val error=MutableStateFlow<String?>(null)
        val commands=CopyOnWriteArrayList<String>()
        @Volatile var previewSelected=false
        override suspend fun request(op:String,args:JsonObject):JsonObject{commands+=op;return JsonObject(emptyMap())}
        override fun setSelected(selected:Boolean){previewSelected=selected}
        override suspend fun shutdown(){}
    }
}
