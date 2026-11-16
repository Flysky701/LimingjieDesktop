package com.landosol.toolbox.ui.account

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.landosol.toolbox.account.AccountEditorState
import com.landosol.toolbox.account.AccountListItem
import com.landosol.toolbox.account.AccountUiState
import com.landosol.toolbox.protocol.bilibili.GameServer
import com.landosol.toolbox.ui.CompactTopBar

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun AccountScreen(
    state: AccountUiState,
    onBack: (() -> Unit)? = null,
    onAdd: () -> Unit,
    onEdit: (Long) -> Unit,
    onSelect: (Long) -> Unit,
    onRequestDelete: (AccountListItem) -> Unit,
    onDismissDelete: () -> Unit,
    onConfirmDelete: () -> Unit,
    onEditorChange: ((AccountEditorState) -> AccountEditorState) -> Unit,
    onDismissEditor: () -> Unit,
    onSaveEditor: () -> Unit,
    onDismissMessage: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val searchTerm = query.trim()
    val visibleAccounts = remember(state.accounts, searchTerm) {
        state.accounts.filter { account ->
            account.alias.contains(searchTerm, ignoreCase = true) ||
                account.serverName.contains(searchTerm, ignoreCase = true) ||
                account.gameUid?.contains(searchTerm) == true
        }
    }
    Scaffold(
        modifier = Modifier.imePadding(),
        topBar = {
            CompactTopBar(
                title = "账号库",
                onBack = onBack,
                actions = {
                    TextButton(onClick = onAdd, enabled = !state.isWorking) {
                        Text("新增", style = MaterialTheme.typography.labelLarge)
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 300.dp),
                modifier = Modifier.widthIn(max = 1080.dp).fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("共 ${state.accounts.size} 个账号", style = MaterialTheme.typography.titleMedium)
                        Text(
                            text = "选择当前账号后，黎明界会在需要时自动登录。登录凭据加密保存在本机。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (state.accounts.isNotEmpty() || query.isNotEmpty()) {
                            OutlinedTextField(
                                value = query,
                                onValueChange = { query = it },
                                label = { Text("查找账号") },
                                placeholder = { Text("名称、服务器或游戏 UID") },
                                singleLine = true,
                                trailingIcon = {
                                    if (query.isNotEmpty()) {
                                        TextButton(onClick = { query = "" }) { Text("清除") }
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        if (searchTerm.isNotEmpty()) {
                            Text("找到 ${visibleAccounts.size} 个账号", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                state.message?.takeIf {
                    state.editor == null && state.deleteCandidate == null
                }?.let { message ->
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        MessageCard(message = message, onDismiss = onDismissMessage)
                    }
                }
                if (state.accounts.isEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Text("还没有账号", style = MaterialTheme.typography.titleMedium)
                            Text("添加账号后即可配置黎明界任务。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Button(onClick = onAdd, enabled = !state.isWorking) { Text("新增账号") }
                        }
                    }
                } else if (visibleAccounts.isEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Column(
                            Modifier.fillMaxWidth().padding(vertical = 32.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text("没有找到匹配的账号", style = MaterialTheme.typography.titleMedium)
                            TextButton(onClick = { query = "" }) { Text("显示全部账号") }
                        }
                    }
                } else {
                    items(visibleAccounts, key = { it.id }) { account ->
                        AccountCard(
                            account = account,
                            enabled = !state.isWorking,
                            onEdit = { onEdit(account.id) },
                            onSelect = { onSelect(account.id) },
                            onDelete = { onRequestDelete(account) },
                        )
                    }
                }
                if (state.isWorking && state.editor == null && state.deleteCandidate == null) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                            CircularProgressIndicator()
                        }
                    }
                }
            }
        }
    }

    state.editor?.let { editor ->
        AccountEditorDialog(
            editor = editor,
            isWorking = state.isWorking,
            message = state.message,
            onChange = onEditorChange,
            onDismiss = onDismissEditor,
            onSave = onSaveEditor,
        )
    }

    state.deleteCandidate?.let { account ->
        AlertDialog(
            onDismissRequest = onDismissDelete,
            title = { Text("删除账号") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("确定删除“${account.alias}”吗？")
                    Text("账号元数据和本地加密凭据都会删除，此操作不可撤销。")
                    state.message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                TextButton(onClick = onConfirmDelete, enabled = !state.isWorking) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = onDismissDelete, enabled = !state.isWorking) { Text("取消") }
            },
        )
    }

}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun AccountCard(
    account: AccountListItem,
    enabled: Boolean,
    onEdit: () -> Unit,
    onSelect: () -> Unit,
    onDelete: () -> Unit,
) {
    var showActions by remember { mutableStateOf(false) }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = if (account.isSelected) {
            MaterialTheme.colorScheme.primaryContainer
        } else MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, if (account.isSelected) {
            MaterialTheme.colorScheme.primary
        } else MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (account.isSelected) {
                Surface(color = MaterialTheme.colorScheme.primary, shape = MaterialTheme.shapes.small) {
                    Text("当前账号", Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelMedium)
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(account.alias, style = MaterialTheme.typography.titleMedium,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(account.serverName, style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                account.gameUid?.let {
                    Text("游戏 UID：$it", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!account.isSelected) {
                    OutlinedButton(onClick = onSelect, enabled = enabled) { Text("设为当前") }
                }
                TextButton(onClick = onEdit, enabled = enabled) { Text("编辑") }
                Box {
                    TextButton(onClick = { showActions = true }, enabled = enabled) { Text("更多") }
                    DropdownMenu(expanded = showActions, onDismissRequest = { showActions = false }) {
                        DropdownMenuItem(
                            text = { Text("删除账号", color = MaterialTheme.colorScheme.error) },
                            enabled = enabled,
                            onClick = { showActions = false; onDelete() },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AccountEditorDialog(
    editor: AccountEditorState,
    isWorking: Boolean,
    message: String?,
    onChange: ((AccountEditorState) -> AccountEditorState) -> Unit,
    onDismiss: () -> Unit,
    onSave: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (editor.isEditing) "编辑账号" else "新增账号") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                ServerField(
                    server = editor.server,
                    serverChanged = editor.serverChanged,
                    changeable = !isWorking,
                    onSelect = { server -> onChange { it.copy(server = server) } },
                )
                OutlinedTextField(
                    value = editor.alias,
                    onValueChange = { value -> onChange { it.copy(alias = value) } },
                    label = { Text("账号名称") },
                    enabled = !isWorking,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = editor.loginId,
                    onValueChange = { value -> onChange { it.copy(loginId = value) } },
                    label = { Text(if (editor.server?.isChannelServer == true) "登录账号（uid）" else "登录账号") },
                    enabled = !isWorking,
                    keyboardOptions = KeyboardOptions(autoCorrect = false, imeAction = ImeAction.Next),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = editor.password,
                    onValueChange = { value -> onChange { it.copy(password = value) } },
                    label = {
                        val name = if (editor.server?.isChannelServer == true) "密码（access_key）" else "密码"
                        Text(
                            when {
                                editor.serverChanged -> "新$name（更换服务器必填）"
                                editor.isEditing -> "新$name（留空则不修改）"
                                else -> name
                            },
                        )
                    },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    enabled = !isWorking,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = editor.gameUid,
                    onValueChange = { value -> onChange { it.copy(gameUid = value) } },
                    label = { Text("游戏 UID（可选）") },
                    enabled = !isWorking,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (isWorking) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                        CircularProgressIndicator()
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onSave, enabled = !isWorking) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isWorking) { Text("取消") }
        },
    )
}

@Composable
private fun ServerField(
    server: GameServer?,
    serverChanged: Boolean,
    changeable: Boolean,
    onSelect: (GameServer) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = server?.displayName ?: "请选择",
            onValueChange = {},
            readOnly = true,
            label = { Text("服务器") },
            trailingIcon = if (changeable) {
                { TextButton(onClick = { expanded = true }) { Text("选择") } }
            } else {
                null
            },
            supportingText = when {
                serverChanged -> {
                    { Text("更换服务器后需重新填写密码，该账号已有的登录会话会失效") }
                }
                server == null -> {
                    { Text("选择账号所属的服务器：国服 B 服，或所用客户端对应的渠道服") }
                }
                server.isChannelServer -> {
                    { Text("渠道服不走 B 站账号登录：登录账号填 uid，密码填 access_key") }
                }
                else -> null
            },
            modifier = Modifier.fillMaxWidth(),
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            GameServer.entries.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.displayName) },
                    onClick = {
                        expanded = false
                        onSelect(option)
                    },
                )
            }
        }
    }
}

@Composable
private fun MessageCard(message: String, onDismiss: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(message, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
            TextButton(onClick = onDismiss) { Text("关闭") }
        }
    }
}
