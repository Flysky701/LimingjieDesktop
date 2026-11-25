package com.landosol.toolbox.desktop.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.landosol.toolbox.desktop.app.adapters.storage.DesktopThemeMode
import com.landosol.toolbox.desktop.app.control.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun AppearanceSettings(graph: ControlSessionUi) {
    val p by graph.ux.preferences.state.collectAsState()
    val actions = rememberActions(graph)
    Panel("外观与通知") {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("界面主题", style = MaterialTheme.typography.labelLarge)
            DesktopThemeMode.entries.forEach { mode ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(
                        selected = p.theme == mode,
                        onClick = { actions.run { withContext(Dispatchers.IO) { graph.ux.preferences.update { it.copy(theme = mode) } } } },
                    )
                    Text(mode.label, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        HorizontalDivider(color = Line)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(p.largeText, { v -> actions.run { withContext(Dispatchers.IO) { graph.ux.preferences.update { it.copy(largeText = v) } } } })
            Text("大字号显示", style = MaterialTheme.typography.bodyMedium)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(p.notifications, { v -> actions.run { withContext(Dispatchers.IO) { graph.ux.preferences.update { it.copy(notifications = v) } } } })
            Text("任务与验证码桌面通知", style = MaterialTheme.typography.bodyMedium)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(p.sound, { v -> actions.run { withContext(Dispatchers.IO) { graph.ux.preferences.update { it.copy(sound = v) } } } })
            Text("通知提示音", style = MaterialTheme.typography.bodyMedium)
        }
        Message(actions.message, onDismiss = { actions.message = null })
    }
}
