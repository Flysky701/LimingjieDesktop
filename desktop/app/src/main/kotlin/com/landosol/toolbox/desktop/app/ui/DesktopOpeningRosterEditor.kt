package com.landosol.toolbox.desktop.app.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.landosol.toolbox.desktop.app.control.ControlSessionUi
import com.landosol.toolbox.desktop.app.ui.theme.DesktopCorners
import com.landosol.toolbox.labyrinth.LabyrinthOpeningRosterCatalog
import com.landosol.toolbox.labyrinth.LabyrinthRoleRatingItem
import com.landosol.toolbox.labyrinth.openingRosterOverrideError

@Composable
internal fun DesktopOpeningRosterEditor(
    graph: ControlSessionUi,
    rosters: Map<Int, List<List<String>>>,
    onChange: (Int, List<List<String>>?) -> Unit,
    onClose: () -> Unit,
) {
    val catalog by graph.ux.catalog.collectAsState()
    val catalogById = remember(catalog) { catalog.associateBy { it.id } }
    var selectedGuildId by remember { mutableStateOf(LabyrinthOpeningRosterCatalog.guilds.first().guildId) }
    var pickingSlot by remember { mutableStateOf<Int?>(null) }

    val selectedGuild = LabyrinthOpeningRosterCatalog.guilds.first { it.guildId == selectedGuildId }
    val customSlots = rosters[selectedGuildId]
    val slots = customSlots ?: selectedGuild.slots.map { slot -> slot.candidates.map { it.characterId } }

    Column(Modifier.fillMaxSize()) {
        Surface(color = MaterialTheme.colorScheme.surface) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text("开局角色配置", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "每个公会开局固定选 3 名角色，槽位内按顺序优先选用第一个可用候选。",
                        color = Muted,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                TextButton(onClick = onClose) {
                    Symbol("arrow_back", modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("返回策略编辑")
                }
            }
        }
        HorizontalDivider(color = Line)

        Row(Modifier.weight(1f).fillMaxWidth()) {
            Surface(Modifier.width(220.dp).fillMaxHeight(), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Column(Modifier.verticalScroll(rememberScrollState()).padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("选择公会", Modifier.padding(horizontal = 8.dp, vertical = 6.dp), style = MaterialTheme.typography.labelMedium, color = Muted)
                    LabyrinthOpeningRosterCatalog.guilds.forEach { guild ->
                        val active = guild.guildId == selectedGuildId
                        val customized = guild.guildId in rosters
                        Surface(
                            onClick = { selectedGuildId = guild.guildId },
                            shape = DesktopCorners.sm,
                            color = if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
                        ) {
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 9.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Text(
                                    guild.guildName,
                                    Modifier.weight(1f),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (active) Accent else LocalContentColor.current,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (customized) StatusBadge("自定义", tone = StatusTone.INFO)
                            }
                        }
                    }
                }
            }
            VerticalDivider(color = Line)

            Column(
                Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(selectedGuild.guildName, style = MaterialTheme.typography.titleMedium)
                            StatusBadge(if (customSlots == null) "内置方案" else "已自定义", tone = if (customSlots == null) StatusTone.NEUTRAL else StatusTone.INFO)
                        }
                        selectedGuild.grantedCharacters.takeIf { it.isNotEmpty() }?.let { granted ->
                            Text(
                                "公会附赠（不可编辑）：${granted.joinToString("、") { it.displayName }}",
                                style = MaterialTheme.typography.bodySmall,
                                color = Muted,
                            )
                        }
                    }
                    if (customSlots != null) {
                        OutlinedButton(onClick = { onChange(selectedGuildId, null) }, shape = MaterialTheme.shapes.small) {
                            Text("恢复内置")
                        }
                    }
                }

                openingRosterOverrideError(slots)?.let {
                    Message(it, tone = StatusTone.ERROR)
                }

                slots.forEachIndexed { slotIndex, candidates ->
                    Surface(
                        Modifier.fillMaxWidth(),
                        shape = DesktopCorners.md,
                        color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(1.dp, Line),
                    ) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text("第 ${slotIndex + 1} 槽位 · ${candidates.size} 个候选", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                                OutlinedButton(
                                    onClick = { pickingSlot = slotIndex },
                                    shape = MaterialTheme.shapes.small,
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                ) { Text("+ 添加候选") }
                            }
                            HorizontalDivider(color = Line)
                            if (candidates.isEmpty()) {
                                Text("尚未选择候选角色（至少保留 1 个候选）", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                            }
                            candidates.forEachIndexed { idx, id ->
                                val role = catalogById[id]
                                val name = role?.name ?: id
                                Row(
                                    Modifier.fillMaxWidth().padding(vertical = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Text("${idx + 1}.", Modifier.width(24.dp), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, color = Muted)
                                    Portrait(graph, role, name, Modifier.size(30.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                                        Text("ID $id" + (role?.detail?.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()), style = MaterialTheme.typography.labelSmall, color = Muted)
                                    }
                                    TextButton(
                                        onClick = {
                                            val updated = candidates.toMutableList().apply { add(idx - 1, removeAt(idx)) }
                                            onChange(selectedGuildId, slots.mapIndexed { s, list -> if (s == slotIndex) updated else list })
                                        },
                                        enabled = idx > 0,
                                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                                    ) { Text("上移", style = MaterialTheme.typography.labelSmall) }
                                    TextButton(
                                        onClick = {
                                            val updated = candidates.toMutableList().apply { add(idx + 1, removeAt(idx)) }
                                            onChange(selectedGuildId, slots.mapIndexed { s, list -> if (s == slotIndex) updated else list })
                                        },
                                        enabled = idx < candidates.lastIndex,
                                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                                    ) { Text("下移", style = MaterialTheme.typography.labelSmall) }
                                    TextButton(
                                        onClick = {
                                            onChange(selectedGuildId, slots.mapIndexed { s, list -> if (s == slotIndex) list - id else list })
                                        },
                                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                                    ) { Text("移除", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall) }
                                }
                                if (idx < candidates.lastIndex) HorizontalDivider(color = Line)
                            }
                        }
                    }
                }
            }
        }
    }

    pickingSlot?.let { slotIndex ->
        OpeningCandidatePickerDialog(
            graph = graph,
            catalog = catalog,
            taken = slots.flatten().toSet(),
            onPick = { id ->
                onChange(selectedGuildId, slots.mapIndexed { s, list -> if (s == slotIndex) list + id else list })
                pickingSlot = null
            },
            onClose = { pickingSlot = null },
        )
    }
}

@Composable
private fun OpeningCandidatePickerDialog(
    graph: ControlSessionUi,
    catalog: List<LabyrinthRoleRatingItem>,
    taken: Set<String>,
    onPick: (String) -> Unit,
    onClose: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val rows = remember(catalog, query, taken) {
        val keyword = query.trim()
        catalog.filter {
            it.id !in taken && (keyword.isEmpty() || it.name.contains(keyword, ignoreCase = true) || it.id.contains(keyword))
        }
    }
    if (LocalControlVisible.current) {
        Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(Modifier.width(560.dp).heightIn(max = 600.dp), shape = DesktopCorners.lg, border = BorderStroke(1.dp, Line)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("选择开局候选角色", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                        TextButton(onClick = onClose) { Text("关闭") }
                    }
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        placeholder = { Text("按角色名称或 ID 搜索") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (rows.isEmpty()) {
                        Box(Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) {
                            Text(if (catalog.isEmpty()) "角色资料尚未加载" else "没有匹配的角色", color = Muted)
                        }
                    } else {
                        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            items(rows, key = LabyrinthRoleRatingItem::id) { row ->
                                Surface(
                                    onClick = { onPick(row.id) },
                                    shape = DesktopCorners.sm,
                                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                                ) {
                                    Row(
                                        Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    ) {
                                        Portrait(graph, row, row.name, Modifier.size(32.dp))
                                        Column(Modifier.weight(1f)) {
                                            Text(row.name, style = MaterialTheme.typography.bodyMedium)
                                            Text("${row.id}${row.detail.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()}", style = MaterialTheme.typography.labelSmall, color = Muted)
                                        }
                                        Text("选择 →", style = MaterialTheme.typography.labelSmall, color = Accent)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

internal fun desktopOpeningRosterSummary(rosters: Map<Int, List<List<String>>>): String {
    val customised = LabyrinthOpeningRosterCatalog.guilds.count { rosters.containsKey(it.guildId) }
    return if (customised == 0) "全部使用内置方案" else "已自定义 $customised 个公会"
}
