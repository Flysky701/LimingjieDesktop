package com.landosol.toolbox.desktop.app

import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.MenuScope
import androidx.compose.ui.window.Notification
import androidx.compose.ui.window.TrayState
import androidx.compose.ui.window.setContent
import java.awt.Font
import java.awt.GraphicsEnvironment
import java.awt.PopupMenu
import java.awt.SystemTray
import java.awt.Toolkit
import java.awt.TrayIcon

internal const val TRAY_MENU_GLYPHS = "黎明界助手显示主窗口暂停继续当前标签停止全部退出"

internal fun nativeTrayMenuFont(): Font {
    val systemFont = Toolkit.getDefaultToolkit().getDesktopProperty("win.menu.font") as? Font
        ?: Font(Font.DIALOG, Font.PLAIN, 12)
    val installed = GraphicsEnvironment.getLocalGraphicsEnvironment().availableFontFamilyNames.toSet()
    val logical = setOf(Font.DIALOG, Font.DIALOG_INPUT, Font.SANS_SERIF, Font.SERIF, Font.MONOSPACED)
    val candidates = listOf("Microsoft YaHei UI", "Microsoft YaHei", "SimSun", "NSimSun") +
        installed.filterNot { it in logical }.sorted()
    // Native Windows menus need a physical CJK font; AWT's logical-font fallback is not used.
    val family = candidates.firstOrNull { it in installed &&
        Font(it, systemFont.style, systemFont.size).canDisplayUpTo(TRAY_MENU_GLYPHS) == -1 }
    return Font(family ?: systemFont.name, systemFont.style, systemFont.size).deriveFont(systemFont.size2D)
}

/** Keeps Compose's reactive menu and notifications, with an explicit font for native menu text. */
@Composable
internal fun DesktopTray(
    icon: Painter,
    state: TrayState,
    tooltip: String,
    onAction: () -> Unit,
    menu: @Composable MenuScope.() -> Unit,
) {
    val image = remember(icon) { icon.toAwtImage(Density(1f), LayoutDirection.Ltr, Size(16f, 16f)) }
    val currentAction by rememberUpdatedState(onAction)
    val currentMenu by rememberUpdatedState(menu)
    val tray = remember { TrayIcon(image).apply {
        isImageAutoSize = true
        addActionListener { currentAction() }
    } }
    val popup = remember { PopupMenu().apply { font = nativeTrayMenuFont() } }
    val context = rememberCompositionContext()
    DisposableEffect(tray) {
        val composition = popup.setContent(context) { currentMenu() }
        tray.popupMenu = popup
        val systemTray = SystemTray.getSystemTray()
        try { systemTray.add(tray) } catch (failure: Exception) { composition.dispose(); throw failure }
        onDispose { systemTray.remove(tray); composition.dispose() }
    }
    SideEffect {
        if (tray.image != image) tray.image = image
        if (tray.toolTip != tooltip) tray.toolTip = tooltip
    }
    LaunchedEffect(tray, state) {
        state.notificationFlow.collect { notification ->
            val type = when (notification.type) {
                Notification.Type.None -> TrayIcon.MessageType.NONE
                Notification.Type.Info -> TrayIcon.MessageType.INFO
                Notification.Type.Warning -> TrayIcon.MessageType.WARNING
                Notification.Type.Error -> TrayIcon.MessageType.ERROR
            }
            tray.displayMessage(notification.title, notification.message, type)
        }
    }
}
