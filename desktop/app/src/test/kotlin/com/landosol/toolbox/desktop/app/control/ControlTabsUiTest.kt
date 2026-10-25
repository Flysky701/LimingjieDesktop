package com.landosol.toolbox.desktop.app.control

import androidx.compose.runtime.*
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.landosol.toolbox.desktop.app.adapters.storage.DesktopPreferences
import com.landosol.toolbox.desktop.app.ui.*
import com.landosol.toolbox.desktop.platform.DesktopSettings
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

/** Compose's offscreen test scene; no Graph, real account, native app window, or device. */
class ControlTabsUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun switchingTabsRoutesStopToTheirOriginalPortsAndKeepsOtherInstancesOpen() {
        val a = FakePort("a")
        val b = FakePort("b")
        var selected by mutableStateOf("a")
        var stopAll = 0
        val tabs = listOf(ControlTab("a", "模拟器 1", a), ControlTab("b", "模拟器 2", b))
        compose.setContent { ControlTabsApp(tabs, selected, ControlShellCallbacks(
            select = { selected = it }, create = {}, open = {}, close = {}, rename = { _, _ -> }, stopAll = { stopAll++ },
        )) }
        compose.onNodeWithText("模拟器 1").assertIsDisplayed()
        compose.onNodeWithText("模拟器 2").assertIsDisplayed()
        compose.onAllNodesWithText("停止 F7")[0].performClick()
        compose.waitUntil(5_000) { a.commands.contains(ControlOps.STOP) }
        assertFalse(b.commands.contains(ControlOps.STOP))
        compose.onNodeWithText("模拟器 2").performClick()
        compose.onAllNodesWithText("停止 F7")[1].performClick()
        compose.waitUntil(5_000) { b.commands.contains(ControlOps.STOP) }
        compose.runOnIdle { assertFalse(a.previewSelected); assertTrue(b.previewSelected) }
        compose.onNodeWithText("停止全部").performClick()
        compose.runOnIdle { assertEquals(1, stopAll); assertEquals(1, a.commands.count { it == ControlOps.STOP }); assertEquals(1, b.commands.count { it == ControlOps.STOP }) }
        val pixels = compose.onRoot().captureToImage().toPixelMap()
        val bitmap = BufferedImage(pixels.width, pixels.height, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until pixels.height) for (x in 0 until pixels.width) bitmap.setRGB(x, y, pixels[x, y].toArgb())
        val path = Path.of("build/reports/multi-instance-tabs.png")
        Files.createDirectories(path.parent)
        ImageIO.write(bitmap, "png", path.toFile())
    }

    private class FakePort(override val instanceId: String) : ControlSessionPort {
        override val workerRunId = "run-$instanceId"
        override val snapshots = MutableStateFlow(buildJsonObject {
            put("console", ControlModelCodec.encode(RunConsoleState(busy = true)))
            put("settings", ControlModelCodec.encode(DesktopSettings(wizardCompleted = true)))
            put("preferences", ControlModelCodec.encode(DesktopPreferences()))
        })
        override val connection = MutableStateFlow(WorkerConnectionStatus.CONNECTED)
        override val error = MutableStateFlow<String?>(null)
        val commands = CopyOnWriteArrayList<String>()
        @Volatile var previewSelected = false
        override suspend fun request(op: String, args: JsonObject): JsonObject { commands += op; return JsonObject(emptyMap()) }
        override fun setSelected(selected: Boolean) { previewSelected = selected }
        override suspend fun shutdown() {}
    }
}
