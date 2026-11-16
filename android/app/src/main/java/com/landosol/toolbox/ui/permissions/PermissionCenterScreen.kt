package com.landosol.toolbox.ui.permissions

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import com.landosol.toolbox.permissions.PermissionCenterState
import com.landosol.toolbox.ui.CompactTopBar
import com.landosol.toolbox.ui.ToolboxCard
import com.landosol.toolbox.ui.ToolboxIcons
import com.landosol.toolbox.ui.theme.AppThemeMode
import com.landosol.toolbox.AppVersion

private data class PermissionItem(
    val title: String,
    val enabled: Boolean,
    val statusText: String = if (enabled) "已授权" else "未授权",
    val description: String,
    val actionLabel: String? = null,
    val onAction: (() -> Unit)? = null,
)

@Composable
fun PermissionCenterScreen(
    state: PermissionCenterState,
    onBack: (() -> Unit)? = null,
    onOpenAccessibilitySettings: () -> Unit,
    onRequestNotifications: () -> Unit,
    onStopCapture: () -> Unit,
    onOpenConfigurationSharing: () -> Unit = {},
    configurationSharingEnabled: Boolean = true,
    configurationSharingReason: String? = null,
    themeMode: AppThemeMode = AppThemeMode.SYSTEM,
    onThemeChange: (AppThemeMode) -> Unit = {},
) {
    val configuration = LocalConfiguration.current
    val wideLandscape = configuration.screenWidthDp >= 900
    Scaffold(
        topBar = { CompactTopBar(title = "设置", onBack = onBack) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            AppearanceCard(themeMode, onThemeChange)
            Text("任务权限", style = MaterialTheme.typography.titleMedium)
            Text(
                "完成无障碍和任务通知设置后即可启动任务；启动时会请求截图授权，任务控制入口位于通知栏。",
                style = MaterialTheme.typography.bodySmall,
            )
            val permissionItems = listOf(
                PermissionItem(
                    title = "无障碍服务",
                    enabled = state.accessibilityEnabled && state.accessibilityConnected,
                    statusText = when {
                        !state.accessibilityEnabled -> "未授权"
                        state.accessibilityConnected -> "已授权且已连接"
                        else -> "已授权，服务未连接"
                    },
                    description = if (state.accessibilityEnabled && !state.accessibilityConnected) {
                        "系统可能保留了开关但没有重新绑定服务，请进入系统设置关闭后再开启。"
                    } else {
                        "用于执行用户启动的点击、滑动和返回动作。"
                    },
                    actionLabel = if (state.accessibilityEnabled && !state.accessibilityConnected) {
                        "前往重新连接"
                    } else {
                        "打开系统设置"
                    },
                    onAction = onOpenAccessibilitySettings,
                ),
                PermissionItem(
                    title = "通知",
                    enabled = state.notificationsEnabled,
                    description = "显示任务状态；展开通知可暂停、继续或停止。",
                    actionLabel = "开启或设置通知",
                    onAction = onRequestNotifications,
                ),
                PermissionItem(
                    title = "屏幕捕获",
                    enabled = state.captureSessionActive,
                    statusText = if (state.captureSessionActive) "运行中" else "未运行",
                    description = "启动视觉任务时由 Android 系统请求截图授权；停止后立即释放资源。",
                    actionLabel = if (state.captureSessionActive) "停止屏幕捕获" else null,
                    onAction = onStopCapture.takeIf { state.captureSessionActive },
                ),
            )
            if (wideLandscape) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    permissionItems.forEach { item ->
                        PermissionCard(
                            modifier = Modifier.weight(1f),
                            item = item,
                        )
                    }
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    permissionItems.forEach { item ->
                        PermissionCard(
                            modifier = Modifier.fillMaxWidth(),
                            item = item,
                        )
                    }
                }
            }
            Text(
                when {
                    state.canStartVisualAutomation -> "已就绪，可以启动视觉自动化。"
                    state.accessibilityEnabled && !state.accessibilityConnected ->
                        "暂不可用：无障碍开关已开启，但服务尚未连接。"
                    state.accessibilityEnabled && state.accessibilityConnected && state.notificationsEnabled ->
                        "权限已就绪，启动视觉任务时将请求截图授权。"
                    else -> "暂不可用：请先完成上面的无障碍和通知设置。"
                },
                style = MaterialTheme.typography.titleSmall,
                color = if (state.canStartVisualAutomation) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
            ToolboxCard {
                Text("配置导入与导出", style = MaterialTheme.typography.titleMedium)
                Text("分享全局策略、个人评分和刷开局设置。", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                configurationSharingReason?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                OutlinedButton(onClick = onOpenConfigurationSharing, enabled = configurationSharingEnabled) {
                    Text("管理配置分享码")
                }
            }
            Text("黎明界助手 · ${AppVersion.display}", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun AppearanceCard(selected: AppThemeMode, onSelect: (AppThemeMode) -> Unit) {
    ToolboxCard {
        Text("外观", style = MaterialTheme.typography.titleMedium)
        Text("选择适合当前环境的显示方式。", style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            AppThemeMode.entries.forEach { mode ->
                val active = selected == mode
                Surface(
                    modifier = Modifier.weight(1f),
                    shape = MaterialTheme.shapes.medium,
                    color = if (active) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
                ) {
                    Column(
                        Modifier.selectable(selected = active, role = Role.RadioButton, onClick = { onSelect(mode) })
                            .heightIn(min = 88.dp).padding(12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
                    ) {
                        Icon(when (mode) {
                            AppThemeMode.SYSTEM -> ToolboxIcons.System
                            AppThemeMode.LIGHT -> ToolboxIcons.Sun
                            AppThemeMode.DARK -> ToolboxIcons.Moon
                        }, contentDescription = null, modifier = Modifier.size(24.dp))
                        Text(mode.label, style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
        Text(
            when (selected) {
                AppThemeMode.SYSTEM -> "随 Android 系统自动切换浅色与深色。"
                AppThemeMode.LIGHT -> "始终使用浅色外观。"
                AppThemeMode.DARK -> "始终使用深色外观。"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PermissionCard(
    modifier: Modifier,
    item: PermissionItem,
) {
    Surface(modifier = modifier, shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(item.title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                Text(
                    item.statusText,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (item.enabled) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(item.description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            val onAction = item.onAction
            val actionLabel = item.actionLabel
            if (onAction != null && actionLabel != null) {
                OutlinedButton(
                    onClick = onAction,
                    modifier = Modifier.defaultMinSize(minHeight = 36.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Text(actionLabel, style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}
