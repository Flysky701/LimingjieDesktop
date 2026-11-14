package com.landosol.toolbox.desktop.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.landosol.toolbox.desktop.platform.BrowserLauncher

private const val DESKTOP_PROJECT_URL = "https://github.com/Flysky701/LimingjieDesktop"

private data class ProjectOrigin(val label: String, val description: String, val url: String)

private val projectOrigins = listOf(
    ProjectOrigin("本项目 · Windows 桌面版", "桌面界面、模拟器连接与多设备管理", DESKTOP_PROJECT_URL),
    ProjectOrigin("当前上游 · SatenRuiko10032", "同步新版功能与执行流程修复", "https://github.com/SatenRuiko10032/limingjie-assistant"),
    ProjectOrigin("原始项目 · wbero", "Android 原作与核心业务基础", "https://github.com/wbero/limingjie-assistant"),
)

@Composable
internal fun ProjectOriginsPanel() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, Line),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("项目来源与致谢", style = MaterialTheme.typography.titleMedium)
            Text("桌面版最初移植自 wbero 的原始项目，并同步 SatenRuiko10032 上游的功能与修复，在此基础上提供 Windows 界面和多设备管理。",
                color = Muted, style = MaterialTheme.typography.bodySmall)
            projectOrigins.forEachIndexed { index, project ->
                if (index > 0) HorizontalDivider(color = Line)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(project.label, style = MaterialTheme.typography.titleSmall)
                        Text(project.description, color = Muted, style = MaterialTheme.typography.bodySmall)
                        SelectionContainer { Text(project.url, color = Accent, style = MaterialTheme.typography.bodySmall) }
                    }
                    OutlinedButton(onClick = { BrowserLauncher.open(project.url) }, shape = MaterialTheme.shapes.small) { Text("打开项目 ↗") }
                }
            }
            HorizontalDivider(color = Line)
            Text("如果助手对你有帮助，欢迎给本项目点个 Star ⭐，也请支持上游和原作者。感谢每一位贡献者！",
                color = Muted, style = MaterialTheme.typography.bodySmall)
            Button(onClick = { BrowserLauncher.open(DESKTOP_PROJECT_URL) }, shape = MaterialTheme.shapes.small) {
                Text("去 GitHub 点个 Star ⭐")
            }
        }
    }
}
