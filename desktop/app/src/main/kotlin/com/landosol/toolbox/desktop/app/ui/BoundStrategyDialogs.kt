package com.landosol.toolbox.desktop.app.ui

import androidx.compose.runtime.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.rememberDialogState
import java.awt.KeyboardFocusManager
import java.awt.event.KeyEvent

@Composable internal fun WorkflowDialog(onClose:()->Unit,content:@Composable ()->Unit) {
    val parent=LocalDesktopWindow.current
    val state=rememberDialogState(width=((parent?.width ?: 1200)-60).coerceIn(900,1200).dp,height=((parent?.height ?: 800)-70).coerceIn(500,850).dp)
    DialogWindow(onCloseRequest=onClose,state=state,title="完整任务配置 · ${LocalControlName.current}",visible=LocalControlVisible.current,resizable=true){content()}
}

/** Compose 1.8's common Dialog is a scene layer, so an AWT listener cannot resize it.
 * A modal desktop host bounds the unmodified upstream layer and its nested editors.
 * Native close requests become Escape, preserving upstream's dirty-draft confirmation. */
@Composable internal fun BoundStrategyDialog(content:@Composable ()->Unit) {
    val parent=LocalDesktopWindow.current
    val state=rememberDialogState(width=880.dp,height=((parent?.height ?: 800)-80).coerceIn(500,900).dp)
    DialogWindow(onCloseRequest={
        val target=KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner
        if(target!=null) {
            val time=System.currentTimeMillis()
            target.dispatchEvent(KeyEvent(target,KeyEvent.KEY_PRESSED,time,0,KeyEvent.VK_ESCAPE,KeyEvent.CHAR_UNDEFINED))
            target.dispatchEvent(KeyEvent(target,KeyEvent.KEY_RELEASED,time,0,KeyEvent.VK_ESCAPE,KeyEvent.CHAR_UNDEFINED))
        }
    },state=state,title="策略与编组",resizable=false,visible=LocalControlVisible.current) { content() }
}
