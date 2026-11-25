package com.landosol.toolbox.desktop.app.ui

import androidx.compose.runtime.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.rememberDialogState
import com.landosol.toolbox.desktop.app.ui.theme.NativeWindowTheme

@Composable
internal fun WorkflowDialog(onClose: () -> Unit, content: @Composable () -> Unit) {
    val parent = LocalDesktopWindow.current
    // Offscreen test scenes have no native host and must not create a desktop window.
    if (parent == null) {
        content()
        return
    }
    val state = rememberDialogState(
        width = (parent.width - 60).coerceIn(900, 1200).dp,
        height = (parent.height - 70).coerceIn(500, 850).dp,
    )
    DialogWindow(
        onCloseRequest = onClose,
        state = state,
        title = "开局与路线设置 · ${LocalControlName.current}",
        visible = LocalControlVisible.current,
        resizable = true,
    ) {
        NativeWindowTheme(window)
        content()
    }
}
