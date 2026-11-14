package com.landosol.toolbox.ui.labyrinth

import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.landosol.toolbox.labyrinth.LabyrinthRoleRatingItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

private data class RatingCatalogState(val rows: List<LabyrinthRoleRatingItem> = emptyList(), val loading: Boolean = true, val error: String? = null)

enum class LabyrinthPersonalScoreFilter(val label: String) {
    ALL("全部评分"), RATED("仅看已修改"), UNRATED("未设个人评分");

    fun includes(hasPersonalScore: Boolean): Boolean = when (this) {
        ALL -> true
        RATED -> hasPersonalScore
        UNRATED -> !hasPersonalScore
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LabyrinthRoleRatingsScreen(
    scores: Map<String, Int>,
    loadCatalog: suspend () -> List<LabyrinthRoleRatingItem>,
    onScoreChange: (String, Int?) -> Unit,
    onClose: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var scoreFilter by rememberSaveable { mutableStateOf(LabyrinthPersonalScoreFilter.ALL) }
    var choosingScoreFilter by remember { mutableStateOf(false) }
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }
    var retry by remember { mutableIntStateOf(0) }
    val catalog by produceState(RatingCatalogState(), retry) {
        value = RatingCatalogState()
        value = try { RatingCatalogState(loadCatalog(), loading = false) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { RatingCatalogState(loading = false, error = failure.message ?: "加载失败") }
    }
    val assets = LocalContext.current.applicationContext.assets
    val portraits = remember(assets) { RolePortraitLoader(assets) }
    val filtered = remember(catalog.rows, query, scoreFilter, scores) {
        val needle = query.trim().replace('（', '(').replace('）', ')')
        catalog.rows.filter { role ->
            scoreFilter.includes(role.id in scores) && (needle.isBlank() ||
                role.name.replace('（', '(').replace('）', ')').contains(needle, ignoreCase = true) || role.id.contains(needle))
        }
    }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Scaffold(modifier = Modifier.fillMaxSize().imePadding(), topBar = {
            TopAppBar(title = { Text("角色个人评分") }, navigationIcon = {
                TextButton(onClick = onClose) { Text("返回") }
            })
        }, bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Text("此页修改先加入草稿；返回策略设置后点击“保存配置”。",
                    Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp), style = MaterialTheme.typography.bodySmall)
            }
        }) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = query, onValueChange = { query = it }, label = { Text("搜索角色名、版本或 ID") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(), trailingIcon = {
                        if (query.isNotEmpty()) TextButton(onClick = { query = "" }) { Text("清除") }
                    })
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box {
                        OutlinedButton(onClick = { choosingScoreFilter = true }, shape = MaterialTheme.shapes.small) { Text(scoreFilter.label) }
                        DropdownMenu(choosingScoreFilter, onDismissRequest = { choosingScoreFilter = false }) {
                            LabyrinthPersonalScoreFilter.entries.forEach { filter ->
                                DropdownMenuItem(text = { Text(filter.label) }, onClick = { scoreFilter = filter; choosingScoreFilter = false })
                            }
                        }
                    }
                    Text("${filtered.size} 人", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                HorizontalDivider()
                when {
                    catalog.loading -> CircularProgressIndicator(Modifier.padding(16.dp))
                    catalog.error != null -> {
                        Text(catalog.error.orEmpty(), color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = { retry++ }) { Text("重试") }
                    }
                    filtered.isEmpty() -> Text("没有符合条件的角色")
                }
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 16.dp)) {
                    items(filtered, key = { it.id }) { role ->
                        val personal = scores[role.id]
                        Card(Modifier.fillMaxWidth().clickable { editingId = role.id },
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                            Row(Modifier.fillMaxWidth().padding(12.dp).heightIn(min = 64.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                RolePortrait(role, personal != null, portraits)
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(role.name, style = MaterialTheme.typography.titleSmall)
                                    Text("${role.detail} · ${role.id}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text("原评分：${role.originalScore?.toString() ?: "暂无"}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text(if (personal == null) "我的评分：未设置（沿用原评分）" else "我的评分：$personal",
                                        color = if (personal == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
                                        style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    catalog.rows.firstOrNull { it.id == editingId }?.let { role ->
        key(role.id) {
            RoleScoreEditor(role, scores[role.id], portraits,
                onApply = { onScoreChange(role.id, it); editingId = null }, onClose = { editingId = null })
        }
    }
}

@Composable
private fun RoleScoreEditor(role: LabyrinthRoleRatingItem, personal: Int?, portraits: RolePortraitLoader,
    onApply: (Int?) -> Unit, onClose: () -> Unit) {
    var input by rememberSaveable { mutableStateOf(personal?.toString() ?: role.originalScore?.roundToInt()?.toString().orEmpty()) }
    val score = input.toIntOrNull()?.takeIf { it in 0..100 }
    AlertDialog(onDismissRequest = onClose, title = { Text(role.name) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                RolePortrait(role, personal != null, portraits)
                Text("原评分：${role.originalScore?.toString() ?: "暂无"}\n个人评分优先用于选人和编组。")
            }
            OutlinedTextField(value = input, onValueChange = { value ->
                if (value.length <= 3 && value.all { it in '0'..'9' }) input = value
            }, label = { Text("我的评分（0–100）") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                isError = score == null, supportingText = { Text("不会补全缺失角色资料或覆盖数据库。") })
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = { input = ((score ?: 0) - 1).coerceAtLeast(0).toString() }, enabled = score != null && score > 0) { Text("− 1") }
                OutlinedButton(onClick = { input = ((score ?: 0) + 1).coerceAtMost(100).toString() }, enabled = score == null || score < 100) { Text("+ 1") }
            }
            TextButton(onClick = { onApply(null) }, enabled = personal != null) { Text("恢复此角色原评分") }
        }
    }, confirmButton = { TextButton(onClick = { onApply(score) }, enabled = score != null) { Text("应用到草稿") } },
        dismissButton = { TextButton(onClick = onClose) { Text("取消") } })
}

@Composable
private fun RolePortrait(role: LabyrinthRoleRatingItem, modified: Boolean, loader: RolePortraitLoader) {
    val bitmap by produceState<Bitmap?>(null, role.iconAsset, loader) { value = role.iconAsset?.let { loader.load(it) } }
    val shape = RoundedCornerShape(8.dp)
    Box(Modifier.size(56.dp).clip(shape).border(if (modified) 2.dp else 1.dp,
        if (modified) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, shape),
        contentAlignment = Alignment.Center) {
        bitmap?.let { Image(it.asImageBitmap(), contentDescription = "${role.name}头像", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().padding(3.dp)) }
            ?: Text("暂无\n头像", style = MaterialTheme.typography.labelSmall)
    }
}

/** Screen-owned 2 MiB cache; decode only visible rows, serially off the main thread. */
private class RolePortraitLoader(private val assets: AssetManager) {
    private val mutex = Mutex()
    private val cache = object : LruCache<String, Bitmap>(2 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }
    suspend fun load(path: String): Bitmap? = withContext(Dispatchers.IO) {
        mutex.withLock {
            cache.get(path) ?: try {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                assets.open(path).use { BitmapFactory.decodeStream(it, null, bounds) }
                var sample = 1
                while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 128) sample *= 2
                val options = BitmapFactory.Options().apply { inSampleSize = sample }
                assets.open(path).use { BitmapFactory.decodeStream(it, null, options) }?.also { cache.put(path, it) }
            } catch (_: Exception) { null }
        }
    }
}
