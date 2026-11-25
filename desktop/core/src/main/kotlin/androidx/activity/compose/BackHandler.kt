package androidx.activity.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalWindowInfo
import java.awt.KeyEventDispatcher
import java.awt.KeyboardFocusManager
import java.awt.event.KeyEvent

/** Hidden control tabs keep their drafts but must not consume another tab's Escape. */
val LocalDesktopBackHandlerActive = staticCompositionLocalOf { true }

@Composable
fun BackHandler(enabled: Boolean = true, onBack: () -> Unit) {
    val active = LocalDesktopBackHandlerActive.current
    val focused = rememberUpdatedState(LocalWindowInfo.current.isWindowFocused)
    val callback = rememberUpdatedState(onBack)
    DisposableEffect(enabled, active) {
        val manager = KeyboardFocusManager.getCurrentKeyboardFocusManager()
        val handler = KeyEventDispatcher { event ->
            if (enabled && active && focused.value && event.id == KeyEvent.KEY_PRESSED &&
                event.keyCode == KeyEvent.VK_ESCAPE && !event.isConsumed
            ) {
                callback.value()
                true
            } else false
        }
        if (enabled && active) manager.addKeyEventDispatcher(handler)
        onDispose { manager.removeKeyEventDispatcher(handler) }
    }
}
