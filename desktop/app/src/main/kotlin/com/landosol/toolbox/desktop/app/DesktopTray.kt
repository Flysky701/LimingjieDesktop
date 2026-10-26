package com.landosol.toolbox.desktop.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.MenuScope
import androidx.compose.ui.window.Notification
import androidx.compose.ui.window.TrayState
import androidx.compose.ui.window.setContent
import java.awt.Font
import java.awt.GraphicsEnvironment
import java.awt.Color as AwtColor
import java.awt.Point
import java.awt.SystemTray
import java.awt.Toolkit
import java.awt.TrayIcon
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.KeyEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import javax.swing.BorderFactory
import javax.swing.JComponent
import javax.swing.JMenu
import javax.swing.JMenuItem
import javax.swing.JDialog
import javax.swing.JSeparator
import javax.swing.KeyStroke
import javax.swing.event.PopupMenuEvent
import javax.swing.event.PopupMenuListener
import javax.swing.plaf.basic.BasicMenuItemUI

internal const val TRAY_MENU_GLYPHS = "黎明界助手显示主窗口暂停继续当前标签停止全部退出"

private data class TrayMenuAppearance(
    val background: AwtColor, val foreground: AwtColor, val hover: AwtColor,
    val disabled: AwtColor, val outline: AwtColor, val font: Font,
)

private class TrayMenuItemUI(val appearance: TrayMenuAppearance) : BasicMenuItemUI() {
    override fun installDefaults() {
        super.installDefaults()
        selectionBackground = appearance.hover
        selectionForeground = appearance.foreground
        disabledForeground = appearance.disabled
    }
}

internal fun trayMenuFont(): Font {
    val systemFont = Toolkit.getDefaultToolkit().getDesktopProperty("win.menu.font") as? Font
        ?: Font(Font.DIALOG, Font.PLAIN, 12)
    val installed = GraphicsEnvironment.getLocalGraphicsEnvironment().availableFontFamilyNames.toSet()
    val logical = setOf(Font.DIALOG, Font.DIALOG_INPUT, Font.SANS_SERIF, Font.SERIF, Font.MONOSPACED)
    val candidates = listOf("Microsoft YaHei UI", "Microsoft YaHei", "SimSun", "NSimSun") +
        installed.filterNot { it in logical }.sorted()
    val family = candidates.firstOrNull { it in installed &&
        Font(it, systemFont.style, systemFont.size).canDisplayUpTo(TRAY_MENU_GLYPHS) == -1 }
    return Font(family ?: systemFont.name, systemFont.style, systemFont.size).deriveFont(systemFont.size2D)
}

/** A Swing tray menu uses Java2D's CJK rendering and keeps Compose menu actions reactive. */
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
    val menuFont = remember { trayMenuFont() }
    val colors = MaterialTheme.colorScheme
    val currentAppearance by rememberUpdatedState(TrayMenuAppearance(
        AwtColor(colors.surface.toArgb(), true), AwtColor(colors.onSurface.toArgb(), true),
        AwtColor(colors.surfaceVariant.toArgb(), true), AwtColor(colors.onSurfaceVariant.toArgb(), true),
        AwtColor(colors.outlineVariant.toArgb(), true),
        menuFont.deriveFont(MaterialTheme.typography.bodyMedium.fontSize.value),
    ))
    val swingMenu = remember { JMenu().apply { font = menuFont } }
    val popup = swingMenu.popupMenu
    val popupOwner = remember { JDialog().apply {
        isUndecorated = true
        isAlwaysOnTop = true
        focusableWindowState = true
        setSize(1, 1)
    } }
    val tray = remember { TrayIcon(image).apply {
        isImageAutoSize = true
        addActionListener { popup.isVisible = false; currentAction() }
        addMouseListener(object : MouseAdapter() {
            override fun mouseReleased(event: MouseEvent) {
                if (!event.isPopupTrigger && event.button != MouseEvent.BUTTON3) return
                popup.isVisible = false
                popupOwner.location = Point(event.x, event.y)
                popupOwner.isVisible = true
                popupOwner.toFront()
                popupOwner.requestFocus()
                // Swing fits the popup inside the monitor, including a tray at the screen edge.
                popup.show(popupOwner.contentPane, 0, 0)
            }
        })
    } }
    val context = rememberCompositionContext()
    DisposableEffect(tray) {
        val composition = swingMenu.setContent(context) {
            val appearance = currentAppearance
            currentMenu()
            SideEffect {
                popup.background = appearance.background
                popup.font = appearance.font
                popup.border = BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(appearance.outline), BorderFactory.createEmptyBorder(4, 4, 4, 4))
                popup.components.forEach { component -> when (component) {
                    is JMenuItem -> {
                        if ((component.ui as? TrayMenuItemUI)?.appearance != appearance) component.setUI(TrayMenuItemUI(appearance))
                        component.font = appearance.font
                        component.background = appearance.background
                        component.foreground = appearance.foreground
                        component.border = BorderFactory.createEmptyBorder(7, 12, 7, 12)
                    }
                    is JSeparator -> {
                        component.foreground = appearance.outline
                        component.background = appearance.background
                        component.border = BorderFactory.createEmptyBorder(4, 0, 4, 0)
                    }
                } }
            }
        }
        val focus = object : WindowAdapter() {
            override fun windowLostFocus(event: WindowEvent) {
                var next = event.oppositeWindow
                while (next != null) { if (next === popupOwner) return; next = next.owner }
                popup.isVisible = false
            }
        }
        popupOwner.addWindowFocusListener(focus)
        popupOwner.rootPane.registerKeyboardAction({ popup.isVisible = false },
            KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW)
        val dismiss = object : PopupMenuListener {
            override fun popupMenuWillBecomeVisible(event: PopupMenuEvent) = Unit
            override fun popupMenuWillBecomeInvisible(event: PopupMenuEvent) { popupOwner.isVisible = false }
            override fun popupMenuCanceled(event: PopupMenuEvent) { popupOwner.isVisible = false }
        }
        popup.addPopupMenuListener(dismiss)
        val systemTray = SystemTray.getSystemTray()
        try { systemTray.add(tray) } catch (failure: Exception) {
            popup.removePopupMenuListener(dismiss); popupOwner.removeWindowFocusListener(focus)
            popupOwner.dispose(); composition.dispose(); throw failure
        }
        onDispose {
            popup.isVisible = false
            popup.removePopupMenuListener(dismiss)
            popupOwner.removeWindowFocusListener(focus)
            popupOwner.dispose()
            systemTray.remove(tray)
            composition.dispose()
        }
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
