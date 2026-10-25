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
        Text("配色使用 VS Code 默认 Light Modern / Dark Modern。",color=Muted,style=MaterialTheme.typography.bodySmall)
        DesktopThemeMode.entries.forEach { mode->Row(verticalAlignment=Alignment.CenterVertically){RadioButton(p.theme==mode,{actions.run{withContext(Dispatchers.IO){graph.ux.preferences.update{it.copy(theme=mode)}}}});Text(mode.label)} }
        Row(verticalAlignment=Alignment.CenterVertically){Checkbox(p.largeText,{v->actions.run{withContext(Dispatchers.IO){graph.ux.preferences.update{it.copy(largeText=v)}}}});Text("大字号（默认标准）")}
        Row(verticalAlignment=Alignment.CenterVertically){Checkbox(p.notifications,{v->actions.run{withContext(Dispatchers.IO){graph.ux.preferences.update{it.copy(notifications=v)}}}});Text("运行完成、异常停止与人工验证码时通知")}
        Row(verticalAlignment=Alignment.CenterVertically){Checkbox(p.sound,{v->actions.run{withContext(Dispatchers.IO){graph.ux.preferences.update{it.copy(sound=v)}}}});Text("通知提示音")}
        Row(verticalAlignment=Alignment.CenterVertically){Checkbox(p.originalRatings,{v->actions.run{withContext(Dispatchers.IO){graph.ux.preferences.update{it.copy(originalRatings=v)}}}});Text("角色评分优先使用原版表单")}
        Message(actions.message)
    }
}
