package com.landosol.toolbox.ui.labyrinth

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.landosol.toolbox.labyrinth.LabyrinthOpeningRosterCatalog
import com.landosol.toolbox.labyrinth.LabyrinthOpeningRosterConfig
import com.landosol.toolbox.labyrinth.LabyrinthRoleRatingItem
import com.landosol.toolbox.labyrinth.openingRosterOverrideError

/**
 * Editor for the three characters each guild opens with.
 *
 * Slots are ordered preference lists: the run takes the first candidate it can actually see, so a
 * second or third entry is a fallback for a character the account may not own yet. Only the picks
 * are editable; which guild grants which free character is a game fact and stays fixed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LabyrinthOpeningRosterScreen(
    rosters: Map<Int, List<List<String>>>,
    loadCatalog: suspend () -> List<LabyrinthRoleRatingItem>,
    onChange: (Int, List<List<String>>?) -> Unit,
    onClose: () -> Unit,
    wideLayout: Boolean = false,
) {
    var catalog by remember { mutableStateOf<List<LabyrinthRoleRatingItem>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        catalog = runCatching { loadCatalog() }.getOrDefault(emptyList())
        loading = false
    }
    val nameOf: (String) -> String = { id -> catalog.firstOrNull { it.id == id }?.name ?: id }
    var picking by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var selectedGuild by rememberSaveable { mutableStateOf(LabyrinthOpeningRosterCatalog.guilds.first().guildId) }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            topBar = {
                TopAppBar(
                    title = { Text("开局角色") },
                    navigationIcon = { TextButton(onClick = onClose) { Text("返回") } },
                )
            },
        ) { padding ->
            Row(Modifier.fillMaxSize().padding(padding)) {
                if (wideLayout) Surface(Modifier.width(236.dp).fillMaxHeight(), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Column(Modifier.verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("选择公会", Modifier.padding(10.dp), style = MaterialTheme.typography.labelMedium)
                        LabyrinthOpeningRosterCatalog.guilds.forEach { guild ->
                            Surface(onClick = { selectedGuild = guild.guildId }, shape = MaterialTheme.shapes.small,
                                color = if (guild.guildId == selectedGuild) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow) {
                                Column(Modifier.fillMaxWidth().padding(12.dp)) {
                                    Text(guild.guildName, style = MaterialTheme.typography.bodyMedium)
                                    if (guild.guildId in rosters) Text("已自定义", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }
                }
            val contentScroll = key(if (wideLayout) selectedGuild else 0) { rememberScrollState() }
            Column(
                Modifier.weight(1f).fillMaxHeight().verticalScroll(contentScroll).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    "每个公会固定选 3 名角色。同一槽位可以放多个候选，程序按顺序取第一个在列表里找到的；" +
                        "全部槽位都找不到候选时会停下来，不会随机补人。",
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (loading) {
                    Text("正在读取角色资料…", style = MaterialTheme.typography.bodySmall)
                }
                LabyrinthOpeningRosterCatalog.guilds.filter { !wideLayout || it.guildId == selectedGuild }.forEach { guild ->
                    val custom = rosters[guild.guildId]
                    val slots = custom ?: guild.slots.map { slot -> slot.candidates.map { it.characterId } }
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(guild.guildName, style = MaterialTheme.typography.titleMedium)
                                    Text(
                                        if (custom == null) "使用内置方案" else "已自定义",
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                                if (custom != null) {
                                    TextButton(onClick = { onChange(guild.guildId, null) }) { Text("恢复内置") }
                                }
                            }
                            guild.grantedCharacters.takeIf { it.isNotEmpty() }?.let { granted ->
                                Text(
                                    "公会附赠（不可编辑）：${granted.joinToString("、") { it.displayName }}",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            slots.forEachIndexed { slotIndex, candidates ->
                                OpeningSlotRow(
                                    slotNumber = slotIndex + 1,
                                    candidates = candidates,
                                    nameOf = nameOf,
                                    onAdd = { picking = guild.guildId to slotIndex },
                                    onRemove = { id ->
                                        onChange(
                                            guild.guildId,
                                            slots.mapIndexed { index, list ->
                                                if (index == slotIndex) list - id else list
                                            },
                                        )
                                    },
                                )
                            }
                            openingRosterOverrideError(slots)?.let {
                                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
            }
        }
    }

    picking?.let { (guildId, slotIndex) ->
        val guild = LabyrinthOpeningRosterCatalog.configs.getValue(guildId)
        val slots = rosters[guildId] ?: guild.slots.map { slot -> slot.candidates.map { it.characterId } }
        OpeningCharacterPicker(
            catalog = catalog,
            taken = slots.flatten().toSet(),
            onPick = { id ->
                onChange(guildId, slots.mapIndexed { index, list -> if (index == slotIndex) list + id else list })
                picking = null
            },
            onClose = { picking = null },
        )
    }
}

@Composable
private fun OpeningSlotRow(
    slotNumber: Int,
    candidates: List<String>,
    nameOf: (String) -> String,
    onAdd: () -> Unit,
    onRemove: (String) -> Unit,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("第 $slotNumber 槽", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = onAdd) { Text("添加候选") }
        }
        if (candidates.isEmpty()) {
            Text("尚未选择角色", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        candidates.forEachIndexed { index, id ->
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "${index + 1}. ${nameOf(id)}",
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                )
                TextButton(onClick = { onRemove(id) }) { Text("移除") }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OpeningCharacterPicker(
    catalog: List<LabyrinthRoleRatingItem>,
    taken: Set<String>,
    onPick: (String) -> Unit,
    onClose: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val rows = remember(catalog, query, taken) {
        val keyword = query.trim()
        catalog.filter { it.id !in taken && (keyword.isEmpty() || it.name.contains(keyword, ignoreCase = true) || it.id.contains(keyword)) }
    }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            topBar = {
                TopAppBar(
                    title = { Text("选择角色") },
                    navigationIcon = { TextButton(onClick = onClose) { Text("返回") } },
                )
            },
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("按名称或 ID 搜索") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions.Default,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                )
                if (rows.isEmpty()) {
                    Text("没有匹配的角色", style = MaterialTheme.typography.bodySmall)
                }
                LazyColumn(Modifier.fillMaxSize()) {
                    items(rows, key = LabyrinthRoleRatingItem::id) { row ->
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable { onPick(row.id) },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(row.name, style = MaterialTheme.typography.bodyMedium)
                                Text(row.id, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Summary line for the settings section header. */
internal fun openingRosterSummary(rosters: Map<Int, List<List<String>>>): String {
    val customised = LabyrinthOpeningRosterCatalog.guilds.count { rosters.containsKey(it.guildId) }
    return if (customised == 0) "全部使用内置方案" else "已自定义 $customised 个公会"
}

/** Slot ids for a guild, whether or not the user has overridden it. */
internal fun openingRosterSlots(
    guild: LabyrinthOpeningRosterConfig,
    rosters: Map<Int, List<List<String>>>,
): List<List<String>> =
    rosters[guild.guildId] ?: guild.slots.map { slot -> slot.candidates.map { it.characterId } }
