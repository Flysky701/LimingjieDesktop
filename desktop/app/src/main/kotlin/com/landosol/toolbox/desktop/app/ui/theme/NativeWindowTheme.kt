package com.landosol.toolbox.desktop.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import com.landosol.toolbox.desktop.platform.WindowsWindowTheme
import java.awt.Window
import java.awt.event.HierarchyEvent
import java.awt.event.HierarchyListener

@Composable
internal fun NativeWindowTheme(window: Window) {
    val colors = MaterialTheme.colorScheme
    val dark = colors.background.luminance() < .5f
    val captionArgb = colors.background.toArgb()
    val textArgb = colors.onBackground.toArgb()
    DisposableEffect(window, dark, captionArgb, textArgb) {
        fun applyTheme() = WindowsWindowTheme.apply(window, dark, captionArgb, textArgb)
        val listener = HierarchyListener { event ->
            if (event.changeFlags and (HierarchyEvent.DISPLAYABILITY_CHANGED or HierarchyEvent.SHOWING_CHANGED).toLong() != 0L) applyTheme()
        }
        window.addHierarchyListener(listener)
        applyTheme()
        onDispose { window.removeHierarchyListener(listener) }
    }
}
