package com.landosol.toolbox.desktop.app.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.TooltipPlacement
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.landosol.toolbox.desktop.app.control.ControlSessionUi
import com.landosol.toolbox.desktop.app.ui.theme.DesktopCorners
import com.landosol.toolbox.desktop.app.ui.theme.LocalDesktopColors
import com.landosol.toolbox.labyrinth.LabyrinthRoleRatingItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.awt.Dialog
import java.awt.FileDialog
import java.awt.Frame
import java.awt.GraphicsEnvironment
import java.io.File
import javax.swing.JFileChooser
import javax.swing.SwingUtilities
import javax.swing.UIManager

internal enum class StatusTone { NEUTRAL, INFO, OK, WARN, ERROR }

internal enum class DesktopTooltipPlacement { BOTTOM, END }

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun DesktopTooltip(
    text: String?,
    modifier: Modifier = Modifier,
    delayMillis: Int = 250,
    placement: DesktopTooltipPlacement = DesktopTooltipPlacement.BOTTOM,
    content: @Composable () -> Unit,
) {
    if (text.isNullOrBlank()) {
        content()
        return
    }
    val tooltipPlacement = when (placement) {
        DesktopTooltipPlacement.BOTTOM -> TooltipPlacement.ComponentRect(
            anchor = Alignment.BottomCenter,
            alignment = Alignment.BottomCenter,
            offset = DpOffset(0.dp, 6.dp),
        )
        DesktopTooltipPlacement.END -> TooltipPlacement.ComponentRect(
            anchor = Alignment.CenterEnd,
            alignment = Alignment.CenterEnd,
            offset = DpOffset(8.dp, 0.dp),
        )
    }
    val isDark = MaterialTheme.colorScheme.surface.luminance() < .5f
    TooltipArea(
        tooltip = {
            Surface(
                color = if (isDark) MaterialTheme.colorScheme.surfaceContainerHighest else MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onSurface,
                shape = DesktopCorners.sm,
                shadowElevation = 6.dp,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)),
            ) {
                Text(
                    text,
                    Modifier.widthIn(max = 280.dp).padding(horizontal = 10.dp, vertical = 7.dp),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        modifier = modifier,
        delayMillis = delayMillis,
        tooltipPlacement = tooltipPlacement,
        content = content,
    )
}

internal fun inferStatusTone(text: String): StatusTone = when {
    listOf("失联", "失败", "异常", "未通过", "未通关", "错误", "断线", "损坏", "不可用", "拒绝", "关闭").any {
        text.contains(it) && !text.contains("已关闭角色") && !text.contains("已关闭（规则）") && !text.contains("未关闭")
    } && !text.contains("已关闭（") -> when {
        text == "已关闭" || text.contains("控制失联") || text.contains("异常") || text.contains("失败") ||
            text.contains("未通关") || text.contains("离线") || text.contains("损坏") || text.contains("不可用") -> StatusTone.ERROR
        else -> StatusTone.WARN
    }
    text.contains("离线") -> StatusTone.ERROR
    listOf("暂停", "停止中", "验证码", "连接中", "未确认", "待确认", "回退", "偏慢", "注意", "未保存").any { text.contains(it) } -> StatusTone.WARN
    listOf("运行中", "通关", "已完成", "当前启用", "已就绪", "就绪", "已连接", "准备完成", "NN选队", "NN采纳", "已加载").any { text.contains(it) } -> StatusTone.OK
    listOf("空闲", "未连接", "未绑定", "已关闭（规则）", "已停止", "手动停止").any { text.contains(it) } -> StatusTone.NEUTRAL
    else -> StatusTone.INFO
}

internal fun inferMessageTone(text: String?): StatusTone {
    if (text.isNullOrBlank()) return StatusTone.INFO
    return when {
        listOf("失败", "异常", "错误", "损坏", "不可用", "失联", "无效", "拒绝", "未通过", "缺失").any { text.contains(it) } -> StatusTone.ERROR
        listOf("警告", "注意", "尚未", "暂停", "覆盖", "回退", "丢弃", "未保存").any { text.contains(it) } -> StatusTone.WARN
        listOf("通过", "完成", "已保存", "已导出", "已启用", "已更新", "已就绪", "已复制").any { text.contains(it) } -> StatusTone.OK
        else -> StatusTone.INFO
    }
}

@Composable
internal fun toneForegroundColor(tone: StatusTone): Color {
    val semantic = LocalDesktopColors.current
    return when (tone) {
        StatusTone.OK -> semantic.ok
        StatusTone.WARN -> semantic.warn
        StatusTone.ERROR -> semantic.error
        StatusTone.INFO -> semantic.info
        StatusTone.NEUTRAL -> Muted
    }
}

@Composable
internal fun toneContainerColor(tone: StatusTone): Color {
    val isDark = MaterialTheme.colorScheme.surface.luminance() < .5f
    val fg = toneForegroundColor(tone)
    return when (tone) {
        StatusTone.NEUTRAL -> MaterialTheme.colorScheme.surfaceContainerHigh
        StatusTone.INFO -> MaterialTheme.colorScheme.primaryContainer
        StatusTone.OK, StatusTone.WARN, StatusTone.ERROR -> fg.copy(alpha = if (isDark) 0.16f else 0.11f)
    }
}

@Composable
internal fun StatusBadge(
    text: String,
    tone: StatusTone = inferStatusTone(text),
    showDot: Boolean = false,
) {
    val fg = toneForegroundColor(tone)
    val bg = toneContainerColor(tone)
    Surface(
        color = bg,
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, fg.copy(alpha = 0.24f)),
    ) {
        Row(
            Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            if (showDot) {
                Surface(Modifier.size(6.dp), shape = CircleShape, color = fg) {}
            }
            Text(text, color = fg, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
internal fun Message(
    text: String?,
    onDismiss: (() -> Unit)? = null,
    tone: StatusTone = inferMessageTone(text),
) {
    if (text == null) return
    val fg = toneForegroundColor(tone)
    val bg = toneContainerColor(tone)
    val icon = when (tone) {
        StatusTone.OK -> "check_circle"
        StatusTone.WARN -> "warning"
        StatusTone.ERROR -> "error"
        StatusTone.INFO, StatusTone.NEUTRAL -> "warning"
    }
    Surface(
        color = bg,
        shape = DesktopCorners.sm,
        border = BorderStroke(1.dp, fg.copy(alpha = 0.28f)),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (tone != StatusTone.INFO && tone != StatusTone.NEUTRAL) {
                Symbol(icon, tint = fg, modifier = Modifier.size(16.dp))
            }
            Text(text, Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodySmall)
            if (onDismiss != null) {
                TextButton(onClick = onDismiss, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)) {
                    Text("关闭", color = fg, style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

@Composable
internal fun NoticesBanner(notices: List<String>, onDismiss: () -> Unit) {
    if (notices.isEmpty()) return
    val fg = LocalDesktopColors.current.warn
    val bg = toneContainerColor(StatusTone.WARN)
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        color = bg,
        shape = DesktopCorners.sm,
        border = BorderStroke(1.dp, fg.copy(alpha = 0.35f)),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Symbol("warning", tint = fg, modifier = Modifier.size(18.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                notices.distinct().take(3).forEach { notice ->
                    Text(notice, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
                }
            }
            TextButton(onClick = onDismiss, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)) {
                Text("已知晓", color = fg, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

internal object PortraitBitmapCache {
    private const val MAX_ENTRIES = 256
    private val map = object : LinkedHashMap<String, ImageBitmap>(128, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>?): Boolean = size > MAX_ENTRIES
    }

    @Synchronized
    fun get(key: String): ImageBitmap? = map[key]

    @Synchronized
    fun put(key: String, bitmap: ImageBitmap) {
        map[key] = bitmap
    }
}

@Composable
internal fun Portrait(
    graph: ControlSessionUi,
    role: LabyrinthRoleRatingItem?,
    name: String,
    modifier: Modifier = Modifier.size(36.dp),
) {
    val asset = role?.iconAsset
    val cacheKey = remember(role?.id, asset) { asset?.let { "${role.id}:$it" } }
    val cached = remember(cacheKey) { cacheKey?.let(PortraitBitmapCache::get) }
    val bitmap by produceState<ImageBitmap?>(cached, cacheKey) {
        if (cached != null || cacheKey == null || asset == null) {
            value = cached
            return@produceState
        }
        value = withContext(Dispatchers.IO) {
            runCatching {
                graph.context.assets.open(asset).use { stream ->
                    BitmapFactory.decodeStream(stream)?.let { decoded ->
                        try {
                            decoded.toBufferedImage().toComposeImageBitmap().also {
                                PortraitBitmapCache.put(cacheKey, it)
                            }
                        } finally {
                            decoded.recycle()
                        }
                    }
                }
            }.getOrNull()
        }
    }
    Surface(modifier.clip(DesktopCorners.sm), shape = DesktopCorners.sm, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        if (bitmap != null) {
            Image(bitmap!!, name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(name.take(1), style = MaterialTheme.typography.bodySmall, color = Muted)
            }
        }
    }
}

@Composable
internal fun AppBrandMark(modifier: Modifier = Modifier.size(38.dp)) {
    val primary = MaterialTheme.colorScheme.primary
    Canvas(modifier) {
        drawRoundRect(primary, cornerRadius = CornerRadius(size.width * .24f))
        drawCircle(Color.White, size.minDimension * .27f)
        drawCircle(primary, size.minDimension * .22f, center + Offset(size.width * .11f, -size.height * .07f))
        drawCircle(Color(0xFFFFDC92), size.minDimension * .07f, center + Offset(size.width * .22f, size.height * .2f))
    }
}

private var systemLookAndFeelAttempted = false

private fun ensureSystemLookAndFeel() {
    if (systemLookAndFeelAttempted) return
    systemLookAndFeelAttempted = true
    runCatching { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()) }
}

internal suspend fun <T> runDeferredOnEdt(
    schedule: (Runnable) -> Unit = SwingUtilities::invokeLater,
    onCancel: () -> Unit = {},
    block: () -> T,
): T = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { onCancel() }
    schedule(Runnable {
        if (!cont.isActive) return@Runnable
        val result = try {
            Result.success(block())
        } catch (failure: Throwable) {
            Result.failure(failure)
        }
        if (cont.isActive) cont.resumeWith(result)
    })
}

internal suspend fun chooseFile(
    save: Boolean,
    title: String,
    extension: String,
    suggested: String? = null,
    owner: java.awt.Window? = null,
): File? {
    if (GraphicsEnvironment.isHeadless()) return null
    val normalizedExt = extension.trimStart('.').lowercase()
    val activeDialog = java.util.concurrent.atomic.AtomicReference<FileDialog?>(null)
    return runDeferredOnEdt(
        onCancel = { SwingUtilities.invokeLater { activeDialog.get()?.dispose() } },
    ) {
        val mode = if (save) FileDialog.SAVE else FileDialog.LOAD
        val dialog = when (owner) {
            is Frame -> FileDialog(owner, title, mode)
            is Dialog -> FileDialog(owner, title, mode)
            else -> FileDialog(null as Frame?, title, mode)
        }.apply {
            suggested?.let { file = it }
            setFilenameFilter { _, name -> name.lowercase().endsWith(".$normalizedExt") }
        }
        activeDialog.set(dialog)
        try {
            dialog.isVisible = true
            val selectedName = dialog.file ?: return@runDeferredOnEdt null
            val dir = dialog.directory ?: return@runDeferredOnEdt null
            val finalName = if (save && !selectedName.lowercase().endsWith(".$normalizedExt")) {
                "$selectedName.$normalizedExt"
            } else {
                selectedName
            }
            File(dir, finalName)
        } finally {
            activeDialog.compareAndSet(dialog, null)
            dialog.dispose()
        }
    }
}

internal suspend fun chooseDirectory(
    title: String,
    initialPath: String? = null,
    owner: java.awt.Window? = null,
): File? {
    if (GraphicsEnvironment.isHeadless()) return null
    return runDeferredOnEdt {
        ensureSystemLookAndFeel()
        val chooser = JFileChooser().apply {
            dialogTitle = title
            fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
            initialPath?.trim()?.takeIf { it.isNotEmpty() }?.let { File(it).takeIf(File::exists)?.let { f -> selectedFile = f } }
        }
        if (chooser.showOpenDialog(owner) == JFileChooser.APPROVE_OPTION) chooser.selectedFile else null
    }
}
