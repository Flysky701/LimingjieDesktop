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
import com.landosol.toolbox.desktop.shared.DesktopLog
import java.awt.Dimension
import java.awt.BorderLayout
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.GraphicsConfiguration
import java.awt.GraphicsDevice.WindowTranslucency
import java.awt.GraphicsEnvironment
import java.awt.Color as AwtColor
import java.awt.Point
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.SystemTray
import java.awt.Toolkit
import java.awt.TrayIcon
import java.awt.Window
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
import javax.swing.JPopupMenu
import javax.swing.JSeparator
import javax.swing.JWindow
import javax.swing.KeyStroke
import javax.swing.Popup
import javax.swing.SwingConstants
import javax.swing.SwingUtilities
import javax.swing.event.PopupMenuEvent
import javax.swing.event.PopupMenuListener
import javax.swing.plaf.basic.BasicMenuItemUI
import javax.swing.plaf.basic.BasicGraphicsUtils
import javax.swing.plaf.basic.BasicPopupMenuUI
import javax.swing.plaf.basic.BasicSeparatorUI
import kotlin.math.roundToInt

internal const val TRAY_MENU_GLYPHS = "黎明界助手显示主窗口暂停继续当前标签停止全部退出"

internal data class TrayMenuAppearance(
    val background: AwtColor, val foreground: AwtColor, val hover: AwtColor,
    val disabled: AwtColor, val outline: AwtColor, val font: Font,
)

private const val TRAY_GLASS_AVAILABLE = "limingjie.tray.glassAvailable"

// A dedicated transparent host avoids changing Swing's cached popup windows for other menus.
private class TrayGlassPopup(popup: JPopupMenu, owner: Window, x: Int, y: Int) : Popup() {
    private val window = JWindow(owner, owner.graphicsConfiguration).apply {
        type = Window.Type.POPUP
        focusableWindowState = false
        isAlwaysOnTop = true
        background = AwtColor(0, 0, 0, 0)
        rootPane.isOpaque = false
        (contentPane as JComponent).isOpaque = false
        contentPane.add(popup, BorderLayout.CENTER)
        setLocation(x, y)
    }

    override fun show() {
        // JPopupMenu becomes visible only after getPopup returns. Pack now, as Swing's Popup does.
        window.pack()
        window.isVisible = true
    }
    override fun hide() {
        window.isVisible = false
        window.contentPane.removeAll()
        window.dispose()
    }
}

private class TrayPopupMenuUI : BasicPopupMenuUI() {
    override fun getPopup(popup: JPopupMenu, x: Int, y: Int): Popup {
        val owner = SwingUtilities.getWindowAncestor(popup.invoker)
        val glass = owner != null && owner.graphicsConfiguration.device
            .isWindowTranslucencySupported(WindowTranslucency.PERPIXEL_TRANSLUCENT)
        popup.putClientProperty(TRAY_GLASS_AVAILABLE, glass)
        return if (glass) TrayGlassPopup(popup, checkNotNull(owner), x, y) else super.getPopup(popup, x, y)
    }

    override fun paint(graphics: Graphics, component: JComponent) {
        val g = graphics.create() as Graphics2D
        try {
            if (component.getClientProperty(TRAY_GLASS_AVAILABLE) == false) {
                g.color = component.background
                g.fillRect(0, 0, component.width, component.height)
                return
            }
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            for (spread in 4 downTo 1) {
                val inset = 4 - spread
                g.color = AwtColor(0, 0, 0, 10)
                g.fillRoundRect(inset, inset + 1, component.width - inset * 2,
                    component.height - inset * 2 - 1, 18 + spread * 2, 18 + spread * 2)
            }
            val color = component.background
            g.color = AwtColor(color.red, color.green, color.blue, 232)
            g.fillRoundRect(4, 4, component.width - 8, component.height - 8, 18, 18)
            val outline = component.foreground
            g.color = AwtColor(outline.red, outline.green, outline.blue, 170)
            g.drawRoundRect(4, 4, component.width - 9, component.height - 9, 18, 18)
        } finally { g.dispose() }
    }
}

private class TraySeparatorUI : BasicSeparatorUI() {
    override fun getPreferredSize(component: JComponent) = Dimension(0, 9)
    override fun paint(graphics: Graphics, component: JComponent) {
        graphics.color = component.foreground
        graphics.drawLine(12, component.height / 2, component.width - 13, component.height / 2)
    }
}

private class TrayMenuItemUI(val appearance: TrayMenuAppearance) : BasicMenuItemUI() {
    override fun installDefaults() {
        super.installDefaults()
        selectionBackground = appearance.hover
        selectionForeground = appearance.foreground
        disabledForeground = appearance.disabled
        checkIcon = null
        arrowIcon = null
    }

    override fun getPreferredSize(component: JComponent): Dimension = super.getPreferredSize(component).apply {
        width = width.coerceAtLeast(176)
        height = height.coerceAtLeast(32)
    }

    override fun paintBackground(graphics: Graphics, item: JMenuItem, background: AwtColor) {
        if (!item.isEnabled || !item.model.isArmed) return
        val g = graphics.create() as Graphics2D
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.color = appearance.hover
            g.fillRoundRect(0, 0, item.width, item.height, 10, 10)
        } finally { g.dispose() }
    }

    override fun paintText(graphics: Graphics, item: JMenuItem, rect: Rectangle, text: String) {
        // BasicMenuItemUI ignores disabledForeground for labels and reads the global
        // light LookAndFeel color instead. Paint with this menu's resolved palette.
        val g = graphics.create() as Graphics2D
        try {
            g.color = if (item.isEnabled) appearance.foreground else appearance.disabled
            // These tray entries are plain actions; don't reserve Windows' unused check/icon column.
            BasicGraphicsUtils.drawStringUnderlineCharAt(item, g, text, item.displayedMnemonicIndex,
                item.insets.left.toFloat(), (rect.y + g.fontMetrics.ascent).toFloat())
        } finally { g.dispose() }
    }
}

@Composable
internal fun trayMenuAppearance(): TrayMenuAppearance {
    val menuFont = remember { trayMenuFont() }
    val colors = MaterialTheme.colorScheme
    return TrayMenuAppearance(
        AwtColor(colors.surface.toArgb(), true), AwtColor(colors.onSurface.toArgb(), true),
        AwtColor(colors.surfaceVariant.toArgb(), true), AwtColor(colors.onSurfaceVariant.toArgb(), true),
        AwtColor(colors.outlineVariant.toArgb(), true),
        menuFont.deriveFont(MaterialTheme.typography.bodyMedium.fontSize.value),
    )
}

internal fun applyTrayMenuAppearance(popup: JPopupMenu, appearance: TrayMenuAppearance) {
    if (popup.ui !is TrayPopupMenuUI) popup.setUI(TrayPopupMenuUI())
    popup.isOpaque = false
    popup.background = appearance.background
    popup.foreground = appearance.outline
    popup.font = appearance.font
    popup.border = BorderFactory.createEmptyBorder(10, 10, 10, 10)
    popup.components.forEach { component -> when (component) {
        is JMenuItem -> {
            if ((component.ui as? TrayMenuItemUI)?.appearance != appearance) component.setUI(TrayMenuItemUI(appearance))
            component.isOpaque = false
            component.horizontalAlignment = SwingConstants.LEFT
            component.horizontalTextPosition = SwingConstants.RIGHT
            component.font = appearance.font
            component.background = appearance.background
            component.foreground = appearance.foreground
            component.border = BorderFactory.createEmptyBorder(7, 12, 7, 12)
        }
        is JSeparator -> {
            if (component.ui !is TraySeparatorUI) component.setUI(TraySeparatorUI())
            component.isOpaque = false
            component.foreground = appearance.outline
            component.background = appearance.background
            component.border = null
        }
    } }
    popup.revalidate()
    popup.repaint()
}

@Composable
internal fun rememberTrayMenu(appearance: TrayMenuAppearance, menu: @Composable MenuScope.() -> Unit): JMenu {
    val currentMenu by rememberUpdatedState(menu)
    val currentAppearance by rememberUpdatedState(appearance)
    val swingMenu = remember { JMenu() }
    val context = rememberCompositionContext()
    DisposableEffect(swingMenu) {
        val composition = swingMenu.setContent(context) {
            val value = currentAppearance
            currentMenu()
            SideEffect { applyTrayMenuAppearance(swingMenu.popupMenu, value) }
        }
        onDispose { composition.dispose() }
    }
    return swingMenu
}

// On Windows JDK 21 the tray event is in physical pixels. AWT screen origins remain
// physical, but sizes and offsets from each monitor's origin are DPI-scaled.
internal fun windowsTrayPoint(point: Point, bounds: Rectangle, scaleX: Double, scaleY: Double) = Point(
    bounds.x + ((point.x - bounds.x) / scaleX).roundToInt(),
    bounds.y + ((point.y - bounds.y) / scaleY).roundToInt(),
)

private fun trayScreen(point: Point): GraphicsConfiguration {
    val environment = GraphicsEnvironment.getLocalGraphicsEnvironment()
    return environment.screenDevices.map { it.defaultConfiguration }.firstOrNull { screen ->
        val bounds = screen.bounds
        val scale = screen.defaultTransform
        Rectangle(bounds.x, bounds.y, (bounds.width * scale.scaleX).roundToInt(),
            (bounds.height * scale.scaleY).roundToInt()).contains(point)
    } ?: environment.defaultScreenDevice.defaultConfiguration
}

/** Flip at the clicked point before clamping, so near-edge clicks keep a menu corner anchored. */
internal fun trayPopupLocation(anchor: Point, size: Dimension, area: Rectangle): Point {
    val x = if (anchor.x + size.width > area.x + area.width) anchor.x - size.width else anchor.x
    val y = if (anchor.y + size.height > area.y + area.height) anchor.y - size.height else anchor.y
    return Point(x.coerceIn(area.x, (area.x + area.width - size.width).coerceAtLeast(area.x)),
        y.coerceIn(area.y, (area.y + area.height - size.height).coerceAtLeast(area.y)))
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
    val currentAppearance by rememberUpdatedState(trayMenuAppearance())
    val swingMenu = rememberTrayMenu(currentAppearance, menu)
    val popup = swingMenu.popupMenu
    val popupOwner = remember { JDialog().apply {
        isUndecorated = true
        isAlwaysOnTop = true
        focusableWindowState = true
        if (graphicsConfiguration.device.isWindowTranslucencySupported(WindowTranslucency.PERPIXEL_TRANSLUCENT)) {
            background = AwtColor(0, 0, 0, 0)
            rootPane.isOpaque = false
            (contentPane as JComponent).isOpaque = false
        }
        setSize(1, 1)
    } }
    val tray = remember { TrayIcon(image).apply {
        isImageAutoSize = true
        addActionListener { popup.isVisible = false; currentAction() }
        addMouseListener(object : MouseAdapter() {
            override fun mouseReleased(event: MouseEvent) {
                if (!event.isPopupTrigger && event.button != MouseEvent.BUTTON3) return
                popup.isVisible = false
                applyTrayMenuAppearance(popup, currentAppearance)
                val physical = Point(event.x, event.y)
                val screen = trayScreen(physical)
                val scale = screen.defaultTransform
                val anchor = if (System.getProperty("os.name").startsWith("Windows"))
                    windowsTrayPoint(physical, screen.bounds, scale.scaleX, scale.scaleY) else physical
                val insets = Toolkit.getDefaultToolkit().getScreenInsets(screen)
                val area = Rectangle(screen.bounds).apply {
                    x += insets.left; y += insets.top
                    width -= insets.left + insets.right; height -= insets.top + insets.bottom
                }
                val location = trayPopupLocation(anchor, popup.preferredSize, area)
                popupOwner.location = location
                popupOwner.isVisible = true
                popupOwner.toFront()
                popupOwner.requestFocus()
                // Windows may adjust the tiny owner's position; use its actual origin.
                val ownerOrigin = popupOwner.contentPane.locationOnScreen
                popup.show(popupOwner.contentPane, location.x - ownerOrigin.x, location.y - ownerOrigin.y)
                DesktopLog.write('I', "DesktopTray", "event=$physical scale=${scale.scaleX},${scale.scaleY} " +
                    "anchor=$anchor requested=$location actual=${popup.locationOnScreen} size=${popup.size} " +
                    "background=${Integer.toHexString(popup.background.rgb)} foreground=${Integer.toHexString(currentAppearance.foreground.rgb)} " +
                    "glass=${popup.getClientProperty(TRAY_GLASS_AVAILABLE)}")
            }
        })
    } }
    DisposableEffect(tray) {
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
            popupOwner.dispose(); throw failure
        }
        onDispose {
            popup.isVisible = false
            popup.removePopupMenuListener(dismiss)
            popupOwner.removeWindowFocusListener(focus)
            popupOwner.dispose()
            systemTray.remove(tray)
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
