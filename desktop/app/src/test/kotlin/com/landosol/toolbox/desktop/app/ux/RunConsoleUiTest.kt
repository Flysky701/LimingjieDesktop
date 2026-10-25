package com.landosol.toolbox.desktop.app.ux

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.landosol.toolbox.desktop.app.adapters.storage.DesktopPreferences
import com.landosol.toolbox.desktop.app.ui.*
import com.landosol.toolbox.desktop.app.ui.theme.DesktopTheme
import com.landosol.toolbox.labyrinth.batch.LabyrinthBatchHaltReason
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class RunConsoleUiTest {
    @get:Rule val compose=createComposeRule()
    @Test fun missingPreparationDisablesStartAndStopCardExpandsOriginalReason(){
        var starts=0;var target=""
        val raw="已识别战斗失败页，但缺少本次战斗上下文；未自动点击结束或重新挑战"
        compose.setContent {DesktopTheme(DesktopPreferences()){Column{StartControl("模拟器未连接",false){starts++};StopReasonCard(explainStop(LabyrinthBatchHaltReason.FATAL_RUN,raw)){target=it}}}}
        compose.onNodeWithText("开始 F5").assertIsNotEnabled()
        assertEquals(0,starts)
        compose.onNodeWithText("详情").performClick();compose.onNodeWithText(raw).assertExists()
        compose.onNodeWithText("切换到只读识别").performClick();compose.runOnIdle{assertEquals("Run",target)}
    }
}
