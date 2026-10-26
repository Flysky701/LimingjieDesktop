package com.landosol.toolbox.desktop.app.ux

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.landosol.toolbox.AppVersion
import com.landosol.toolbox.account.AccountListItem
import com.landosol.toolbox.desktop.app.adapters.device.VisualResolutionStatus
import com.landosol.toolbox.desktop.app.adapters.storage.*
import com.landosol.toolbox.desktop.app.control.*
import com.landosol.toolbox.desktop.app.ui.*
import com.landosol.toolbox.desktop.app.ui.theme.DesktopTheme
import com.landosol.toolbox.desktop.device.*
import com.landosol.toolbox.desktop.ocr.OcrEngineStatus
import com.landosol.toolbox.desktop.platform.DesktopSettings
import com.landosol.toolbox.labyrinth.LabyrinthUiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import javax.imageio.ImageIO

class RunReadinessUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun sharedConfirmationWritesToItsOwnerAndDoesNotClaimReadyUntilConfirmed() {
        val worker = ReadinessPort(runtime = true)
        val host = ReadinessPort(runtime = false)
        val runtime = ControlSessionUi(worker)
        val global = ControlSessionUi(host)
        var busy by mutableStateOf(false)
        var destination: Page? = null
        try {
            compose.setContent {
                DesktopTheme(DesktopPreferences(theme = DesktopThemeMode.DARK)) {
                    Box(Modifier.width(316.dp).background(MaterialTheme.colorScheme.background).padding(8.dp).testTag("readiness-preview")) {
                        RunReadiness(runtime, worker.console.copy(busy = busy), DesktopTask.BATCH, true, { destination = it }, global)
                    }
                }
            }
            compose.onNodeWithText("待确认游戏设置").assertIsDisplayed()
            compose.onNodeWithText("准备完成").assertDoesNotExist()
            compose.onNodeWithContentDescription("确认已关闭角色加入动画").assertIsOff()
            val pixels = compose.onNodeWithTag("readiness-preview").captureToImage().toPixelMap()
            val image = BufferedImage(pixels.width, pixels.height, BufferedImage.TYPE_INT_ARGB)
            for (y in 0 until pixels.height) for (x in 0 until pixels.width) image.setRGB(x, y, pixels[x, y].toArgb())
            val preview = Path.of("build/reports/run-readiness-improved.png")
            Files.createDirectories(preview.parent)
            ImageIO.write(image, "png", preview.toFile())

            compose.onNodeWithContentDescription("确认已关闭角色加入动画").performClick()
            compose.waitUntil(5_000) {
                global.settings.state.value.gameSettingsConfirmed &&
                    runtime.ux.preferences.state.value.animationConfirmedVersion == AppVersion.name
            }
            compose.onNodeWithContentDescription("确认已关闭角色加入动画").assertIsOn()
            compose.onNodeWithText("准备完成").assertIsDisplayed()
            compose.onNodeWithText("操作未完成", substring = true).assertDoesNotExist()
            assertEquals(listOf(ControlOps.SETTINGS_PATCH), host.requests.toList())
            assertEquals(listOf(ControlOps.PREFS_PATCH), worker.requests.toList())
            assertFalse(runtime.settings.state.value.gameSettingsConfirmed) // A stale worker snapshot must not undo confirmation.
            compose.onNodeWithText("展开检查项").performClick()
            compose.onNodeWithText("打开设置").performClick()
            compose.runOnIdle { assertEquals(Page.Settings, destination); busy = true }
            compose.onNodeWithContentDescription("确认已关闭角色加入动画").assertIsNotEnabled()
        } finally { compose.runOnIdle { runtime.close(); global.close() } }
    }

    /** Synthetic snapshots only; a runtime rejects shared settings exactly as the real service does. */
    private class ReadinessPort(private val runtime: Boolean) : ControlSessionPort {
        override val instanceId = if (runtime) "runtime" else "global"
        override val workerRunId = instanceId
        val console = RunConsoleState(
            task = LabyrinthUiState(selectedAccount = AccountListItem(1, "示例账号", "测试服", null, true), settingsReady = true),
            device = DeviceConnectionState.Ready(DeviceReady("fixture", "示例模拟器", 1920, 1080, emptyList(), CaptureMode.Raw, true, true,
                DeviceIdentity("fixture", "fixture", "示例模拟器", "12", 31, emptyList(), "fixture", 1920, 1080, emptyMap(), 1))),
        )
        override val snapshots = MutableStateFlow(buildJsonObject {
            put("console", ControlModelCodec.encode(console))
            put("settings", ControlModelCodec.encode(DesktopSettings(wizardCompleted = true)))
            put("preferences", ControlModelCodec.encode(DesktopPreferences()))
            put("resolution", ControlModelCodec.encode(VisualResolutionStatus(1920, 1080, "画面尺寸正确")))
            put("ocr", ControlModelCodec.encode(OcrEngineStatus.Ready))
            put("credentials", ControlModelCodec.encode(mapOf(1L to true)))
            put("database", "已就绪")
            put("foreground", "com.bilibili.priconne")
        })
        override val connection = MutableStateFlow(WorkerConnectionStatus.CONNECTED)
        override val error = MutableStateFlow<String?>(null)
        val requests = CopyOnWriteArrayList<String>()
        override suspend fun request(op: String, args: JsonObject): JsonObject {
            requests += op
            when (op) {
                ControlOps.SETTINGS_PATCH -> {
                    check(!runtime) { "请在全局账号、策略或设置页修改共享数据" }
                    val previous = ControlModelCodec.decode<DesktopSettings>(snapshots.value.getValue("settings"))
                    snapshots.value = JsonObject(snapshots.value + ("settings" to ControlModelCodec.encode(previous.copy(gameSettingsConfirmed = args.getValue("gameSettingsConfirmed").jsonPrimitive.boolean))))
                }
                ControlOps.PREFS_PATCH -> {
                    val previous = ControlModelCodec.decode<DesktopPreferences>(snapshots.value.getValue("preferences"))
                    snapshots.value = JsonObject(snapshots.value + ("preferences" to ControlModelCodec.encode(previous.copy(animationConfirmedVersion = args.getValue("animationConfirmedVersion").jsonPrimitive.content))))
                }
            }
            return JsonObject(emptyMap())
        }
        override fun setSelected(selected: Boolean) = Unit
        override suspend fun shutdown() = Unit
    }
}
