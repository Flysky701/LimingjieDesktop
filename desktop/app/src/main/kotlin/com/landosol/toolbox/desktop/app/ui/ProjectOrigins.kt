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
private const val NN_PROJECT_URL = "https://github.com/Flysky701/LimingjieNN"

private data class ProjectOrigin(val label: String, val description: String, val url: String)

private val projectOrigins = listOf(
    ProjectOrigin("本项目 · Windows 桌面版", "独立 Windows 原生桌面端，提供多设备并行管理、迷宫事件内核、本地识别与诊断", DESKTOP_PROJECT_URL),
    ProjectOrigin("配套项目 · 黎明界NN（LimingjieNN）", "离线战斗神经网络训练、双塔 ONNX 选队模型与 Path B 全局结算分规划参考", NN_PROJECT_URL),
    ProjectOrigin("历史参考 · SatenRuiko10032", "历史社区分支，曾参考合并商店重构、三档前排生存模式与执行流程修复", "https://github.com/SatenRuiko10032/limingjie-assistant"),
    ProjectOrigin("原始项目 · wbero", "Android 原作与最初核心业务基础", "https://github.com/wbero/limingjie-assistant"),
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
            Text("本应用现为独立的 Windows 原生桌面项目。最初源自 wbero 的 Android 原作，并曾参考合并 SatenRuiko10032 社区分支的功能与修复；现已独立演进，结合配套的 黎明界NN（LimingjieNN）双塔 ONNX 战斗模型与 Path B 全局结算分规划，提供多设备并行控制与本地诊断。",
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
            Text("如果助手对你有帮助，欢迎给本项目与黎明界NN点个 Star ⭐，也请支持原作者与历史贡献者。感谢每一位贡献者！",
                color = Muted, style = MaterialTheme.typography.bodySmall)
            Button(onClick = { BrowserLauncher.open(DESKTOP_PROJECT_URL) }, shape = MaterialTheme.shapes.small) {
                Text("去 GitHub 点个 Star ⭐")
            }
        }
    }
}
