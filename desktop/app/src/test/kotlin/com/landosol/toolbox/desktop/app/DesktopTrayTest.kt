package com.landosol.toolbox.desktop.app

import androidx.compose.runtime.*
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.LocalSystemTheme
import androidx.compose.ui.SystemTheme
import androidx.compose.ui.test.junit4.createComposeRule
import com.landosol.toolbox.desktop.app.adapters.storage.DesktopPreferences
import com.landosol.toolbox.desktop.app.adapters.storage.DesktopThemeMode
import com.landosol.toolbox.desktop.app.ui.theme.DesktopTheme
import java.awt.Color
import java.awt.Dimension
import java.awt.Point
import java.awt.Rectangle
import java.awt.image.BufferedImage
import javax.swing.JMenu
import javax.swing.JMenuItem
import javax.swing.SwingUtilities
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class DesktopTrayPositionTest {
    @Test fun windowsPhysicalCoordinatesAreConvertedRelativeToEachMonitorOrigin() {
        assertEquals(Point(2000, 1100), windowsTrayPoint(Point(2500, 1375), Rectangle(0, 0, 2048, 1152), 1.25, 1.25))
        assertEquals(Point(2040, 80), windowsTrayPoint(Point(2100, 120), Rectangle(1920, 0, 1707, 960), 1.5, 1.5))
        assertEquals(Point(-2460, -120), windowsTrayPoint(Point(-2410, -80), Rectangle(-2560, -200, 1707, 960), 1.5, 1.5))
        assertEquals(Point(810, 590), windowsTrayPoint(Point(810, 590), Rectangle(0, 0, 1920, 1080), 1.0, 1.0))
    }

    @Test fun nearRightEdgePopupFollowsClickRatherThanStickingToScreenEdge() {
        val size = Dimension(180, 220)
        val area = Rectangle(0, 0, 2048, 1112)
        assertEquals(Point(1820, 680), trayPopupLocation(Point(2000, 900), size, area))
        assertEquals(Point(1780, 680), trayPopupLocation(Point(1960, 900), size, area))
        assertEquals(Point(100, 100), trayPopupLocation(Point(100, 100), size, area))
    }

    @Test fun taskbarAndNegativeMonitorOriginsAreRespected() {
        val area = Rectangle(-1920, 30, 1920, 1010)
        assertEquals(Point(-200, 820), trayPopupLocation(Point(-20, 1060), Dimension(180, 220), area))
        assertEquals(Point(-1920, 30), trayPopupLocation(Point(-1930, 10), Dimension(180, 220), area))
        assertEquals(Point(-1920, 30), trayPopupLocation(Point(-20, 1060), Dimension(2200, 1400), area))
    }
}

@OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class)
class DesktopTrayThemeTest {
    @get:Rule val compose = createComposeRule()

    @Test fun existingSwingMenuRepaintsForExplicitAndSystemThemesAndKeepsActionsReactive() {
        var mode by mutableStateOf(DesktopThemeMode.DARK)
        var system by mutableStateOf(SystemTheme.Light)
        var largeText by mutableStateOf(false)
        var paused by mutableStateOf(false)
        var enabled by mutableStateOf(false)
        var clicked = 0
        lateinit var menu: JMenu
        compose.setContent {
            CompositionLocalProvider(LocalSystemTheme provides system) {
                DesktopTheme(DesktopPreferences(theme = mode, largeText = largeText)) {
                    menu = rememberTrayMenu(trayMenuAppearance()) {
                        Item(if (paused) "继续当前标签" else "暂停当前标签", enabled = enabled, onClick = { clicked++ })
                        Separator()
                        Item("显示主窗口", onClick = { clicked += 10 })
                    }
                }
            }
        }
        fun verify(background: Int, hover: Int, foreground: Int, disabled: Int) {
            compose.waitForIdle()
            SwingUtilities.invokeAndWait {
                val item = menu.getItem(0)
                assertEquals(Color(background), menu.popupMenu.background)
                assertEquals(Color(background), item.background)
                assertEquals(Color(foreground), item.foreground)
                assertEquals(if (paused) "继续当前标签" else "暂停当前标签", item.text)
                assertEquals(enabled, item.isEnabled)
                assertEquals(if (largeText) 14.56f else 13f, item.font.size2D, .01f)
                assertEquals(-1, item.font.canDisplayUpTo(TRAY_MENU_GLYPHS))
                item.setSize(item.preferredSize)
                fun paint(): BufferedImage = BufferedImage(item.width, item.height, BufferedImage.TYPE_INT_RGB).also { image ->
                    val graphics = image.createGraphics()
                    graphics.color = Color.MAGENTA
                    graphics.fillRect(0, 0, image.width, image.height)
                    item.paint(graphics)
                    graphics.dispose()
                }
                var image = paint()
                assertEquals(Color(background).rgb, image.getRGB(2, 2))
                // Check rendered text as well as component properties; disabled text must not disappear.
                val textColor = Color(if (enabled) foreground else disabled).rgb
                assertTrue((0 until image.width).any { x -> (0 until image.height).any { y -> image.getRGB(x, y) == textColor } })
                if (enabled) {
                    item.model.isArmed = true
                    image = paint()
                    assertEquals(Color(hover).rgb, image.getRGB(2, 2))
                    item.model.isArmed = false
                }
            }
        }
        verify(0x1f1f1f, 0x313131, 0xcccccc, 0x9d9d9d)
        compose.runOnIdle { mode = DesktopThemeMode.LIGHT; enabled = true; paused = true; largeText = true }
        verify(0xffffff, 0xf2f2f2, 0x3b3b3b, 0x616161)
        compose.runOnIdle { mode = DesktopThemeMode.SYSTEM; system = SystemTheme.Dark }
        verify(0x1f1f1f, 0x313131, 0xcccccc, 0x9d9d9d)
        compose.runOnIdle { system = SystemTheme.Light; largeText = false }
        verify(0xffffff, 0xf2f2f2, 0x3b3b3b, 0x616161)
        SwingUtilities.invokeAndWait { menu.getItem(0).doClick(0) }
        assertEquals(1, clicked)
        compose.runOnIdle { enabled = false }
        compose.waitForIdle()
        SwingUtilities.invokeAndWait { assertFalse(menu.getItem(0).isEnabled); menu.getItem(0).doClick(0) }
        assertEquals(1, clicked)
    }
}
