package com.landosol.toolbox.desktop.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.landosol.toolbox.desktop.app.control.*
import com.landosol.toolbox.desktop.app.adapters.storage.DesktopThemeMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable internal fun AppearanceSettings(graph:ControlSessionUi) {
    val p by graph.ux.preferences.state.collectAsState()
    val actions=rememberActions(graph)
    Panel("外观与通知") {
        DesktopThemeMode.entries.forEach { mode->Row(verticalAlignment=Alignment.CenterVertically){RadioButton(p.theme==mode,{actions.run{withContext(Dispatchers.IO){graph.ux.preferences.update{it.copy(theme=mode)}}}});Text(mode.label)} }
        Row(verticalAlignment=Alignment.CenterVertically){Checkbox(p.largeText,{v->actions.run{withContext(Dispatchers.IO){graph.ux.preferences.update{it.copy(largeText=v)}}}});Text("大字号")}
        Row(verticalAlignment=Alignment.CenterVertically){Checkbox(p.notifications,{v->actions.run{withContext(Dispatchers.IO){graph.ux.preferences.update{it.copy(notifications=v)}}}});Text("任务与验证码通知")}
        Row(verticalAlignment=Alignment.CenterVertically){Checkbox(p.sound,{v->actions.run{withContext(Dispatchers.IO){graph.ux.preferences.update{it.copy(sound=v)}}}});Text("通知提示音")}
        Row(verticalAlignment=Alignment.CenterVertically){Checkbox(p.originalRatings,{v->actions.run{withContext(Dispatchers.IO){graph.ux.preferences.update{it.copy(originalRatings=v)}}}});Text("角色评分优先使用原版表单")}
        Message(actions.message)
    }
}
