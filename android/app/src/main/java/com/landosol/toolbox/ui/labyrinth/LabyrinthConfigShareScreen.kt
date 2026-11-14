package com.landosol.toolbox.ui.labyrinth

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.landosol.toolbox.AppVersion
import com.landosol.toolbox.LandosolToolboxApplication
import com.landosol.toolbox.automation.TaskConfigurationGate
import com.landosol.toolbox.labyrinth.*
import com.landosol.toolbox.ui.CompactTopBar
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

/** Share codes are generated from saved settings; decoded modules remain inert until confirmation. */
@Composable
fun LabyrinthConfigShareScreen(application: LandosolToolboxApplication, onClose: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val saved by application.labyrinthConfiguration.state.collectAsStateWithLifecycle()
    val accounts by remember(application) { application.accountRepository.observeAccounts() }
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val entry by application.labyrinthEntryRecognitionSession.state.collectAsStateWithLifecycle()
    val reset by application.gameSessionResetWorkflow.state.collectAsStateWithLifecycle()
    val clan by application.clanBattleRecognitionSession.state.collectAsStateWithLifecycle()
    val controller by application.labyrinthController.uiState.collectAsStateWithLifecycle()
    val batch by application.labyrinthBatchController.state.collectAsStateWithLifecycle()
    val halt by application.labyrinthBatchController.haltReason.collectAsStateWithLifecycle()
    val autoRun by application.labyrinthAutoRunWorkflow.progress.collectAsStateWithLifecycle()
    val gate by application.taskConfigurationGate.state.collectAsStateWithLifecycle()
    // These state reads invalidate the view whenever a task starts, stops or pauses.
    val busyReason = remember(entry, reset, clan, controller, batch, halt, autoRun, gate) {
        application.configurationTransferBlockedReason()
            ?: TaskConfigurationGate.BUSY_MESSAGE.takeIf { gate.admissions > 0 || gate.transferring }
    }
    var working by remember { mutableStateOf(false) }
    var undoPreview by remember { mutableStateOf<LabyrinthConfigurationValues?>(null) }
    var confirmUndoEmptyScores by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var exportModules by remember { mutableStateOf(setOf(LabyrinthConfigModule.STRATEGY, LabyrinthConfigModule.PERSONAL_SCORES)) }
    var output by remember { mutableStateOf("") }
    var input by remember { mutableStateOf("") }
    var decoded by remember { mutableStateOf<LabyrinthSharedConfiguration?>(null) }
    var importModules by remember { mutableStateOf(emptySet<LabyrinthConfigModule>()) }
    var targetAccountId by remember { mutableStateOf<Long?>(null) }
    var roleWarning by remember { mutableStateOf<String?>(null) }
    var roleNames by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var plan by remember { mutableStateOf<LabyrinthConfigImportPlan?>(null) }
    var confirmEmptyScores by remember { mutableStateOf(false) }
    val available = busyReason == null && !working
    val currentAccount = accounts.firstOrNull { it.isSelected }

    suspend fun loadRoleNames(): Map<String, String>? = try {
        application.loadLabyrinthRoleRatings().associate { it.id to it.name }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }

    fun launchOperation(action: suspend () -> Unit) {
        if (working) return
        working = true
        message = null
        scope.launch {
            try { application.withConfigurationTransfer { action() } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { message = failure.message ?: "配置操作失败，请重试" }
            finally { working = false }
        }
    }
    fun toggle(modules: Set<LabyrinthConfigModule>, module: LabyrinthConfigModule): Set<LabyrinthConfigModule> =
        if (module in modules) modules - module else modules + module

    BackHandler { if (!working) onClose() }
    Scaffold(
        topBar = { CompactTopBar(title = "配置导入与导出", onBack = { if (!working) onClose() }) },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("通过分享码分享全局策略、个人评分和刷开局设置")
            busyReason?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
            if (working) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (saved.undoImport != null) {
                OutlinedButton(enabled = available, onClick = {
                    launchOperation {
                        roleNames = loadRoleNames().orEmpty()
                        undoPreview = application.labyrinthConfiguration.state.value
                        confirmUndoEmptyScores = false
                    }
                }) { Text("撤销最近一次导入") }
                Text("手动保存其他配置后不可撤销", style = MaterialTheme.typography.bodySmall)
            }
            HorizontalDivider()
            Text("导出分享码", style = MaterialTheme.typography.titleMedium)
            LabyrinthConfigModule.entries.forEach { module ->
                ModuleCheck(module.label, module in exportModules, available && (module != LabyrinthConfigModule.REROLL || currentAccount != null)) {
                    exportModules = toggle(exportModules, module)
                    output = ""
                }
            }
            if (currentAccount == null) {
                Text("选择账号后可导出刷开局设置", style = MaterialTheme.typography.bodySmall)
            } else if (LabyrinthConfigModule.REROLL in exportModules) {
                Text("刷开局来源：${currentAccount.alias} · ${currentAccount.serverName}", style = MaterialTheme.typography.bodySmall)
            }
            Text("仅导出已保存配置", style = MaterialTheme.typography.bodySmall)
            Button(enabled = available && exportModules.isNotEmpty() && (LabyrinthConfigModule.REROLL !in exportModules || currentAccount != null), onClick = {
                output = ""
                launchOperation {
                    val source = currentAccount?.id
                    if (LabyrinthConfigModule.REROLL in exportModules) {
                        check(application.database.accountDao().getSelected()?.id == source &&
                            source == application.labyrinthController.configurationAccountId && application.labyrinthController.uiState.value.settingsReady) { "当前账号正在切换，请稍后重试" }
                    }
                    val values = application.labyrinthConfiguration.state.value
                    output = withContext(Dispatchers.Default) {
                        LabyrinthConfigShareCodec.encode(LabyrinthConfigShareCodec.create(values, exportModules, source, AppVersion.display, System.currentTimeMillis()))
                    }
                }
            }) { Text("生成分享码") }
            if (output.isNotEmpty()) {
                OutlinedTextField(value = output, onValueChange = {}, readOnly = true, enabled = available,
                    label = { Text("分享码") }, modifier = Modifier.fillMaxWidth(), maxLines = 4)
                OutlinedButton(enabled = available, onClick = {
                    launchOperation {
                        clipboard.setText(AnnotatedString(output))
                        scope.launch {
                            snackbarHostState.currentSnackbarData?.dismiss()
                            snackbarHostState.showSnackbar("已复制")
                        }
                    }
                }) { Text("复制分享码") }
            }
            HorizontalDivider()
            Text("导入分享码", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(value = input, onValueChange = { value ->
                if (value.length <= LabyrinthConfigShareCodec.MAX_INPUT_LENGTH) {
                    input = value
                    decoded = null
                    plan = null
                    roleWarning = null
                } else message = "分享码过长"
            }, enabled = available, label = { Text("输入或粘贴分享码") }, modifier = Modifier.fillMaxWidth(), minLines = 3, maxLines = 6)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(enabled = available, onClick = {
                    val pasted = clipboard.getText()?.text.orEmpty()
                    if (pasted.length <= LabyrinthConfigShareCodec.MAX_INPUT_LENGTH) {
                        input = pasted
                        decoded = null
                        plan = null
                        roleWarning = null
                    } else message = "剪贴板内容过长"
                }) { Text("粘贴") }
                Button(enabled = available && input.isNotBlank(), onClick = {
                    decoded = null
                    plan = null
                    launchOperation {
                        val configuration = withContext(Dispatchers.Default) { LabyrinthConfigShareCodec.decode(input) }
                        val names = loadRoleNames()
                        roleNames = names.orEmpty()
                        roleWarning = if (names == null) "角色目录不可用，暂无法核对角色引用。"
                            else configuration.unknownRoleIds(names.keys).takeIf { it.isNotEmpty() }?.let {
                                "暂缺 ${it.size} 个角色，引用将保留。编号：${it.take(12).joinToString()}"
                            }
                        decoded = configuration
                        importModules = configuration.defaultSelection()
                        targetAccountId = currentAccount?.id
                        confirmEmptyScores = false
                    }
                }) { Text("导入分享码") }
            }
            decoded?.let { configuration ->
                Text("选择导入内容", style = MaterialTheme.typography.titleSmall)
                Text("来源版本：${configuration.appVersion}\n导出时间：${DateFormat.getDateTimeInstance().format(Date(configuration.exportedAtMillis))}", style = MaterialTheme.typography.bodySmall)
                roleWarning?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                configuration.modules.forEach { module ->
                    ModuleCheck(module.label + if (module == LabyrinthConfigModule.PERSONAL_SCORES) "（${configuration.personalScores?.size ?: 0} 项）" else "",
                        module in importModules, available) {
                        importModules = toggle(importModules, module)
                        confirmEmptyScores = false
                        plan = null
                    }
                }
                if (configuration.personalScores?.isEmpty() == true) {
                    Text(if (LabyrinthConfigModule.PERSONAL_SCORES in importModules)
                        "导入将清除 ${saved.strategy.personalRoleScores.size} 项自定义评分，恢复原评分"
                        else "个人评分为空，默认不导入", style = MaterialTheme.typography.bodySmall)
                }
                if (LabyrinthConfigModule.REROLL in importModules) {
                    Text("刷开局目标账号：")
                    accounts.forEach { account ->
                        Row(Modifier.fillMaxWidth()) {
                            RadioButton(selected = account.id == targetAccountId, enabled = available, onClick = { targetAccountId = account.id; plan = null })
                            Text("${account.alias} · ${account.serverName}", modifier = Modifier.padding(top = 12.dp))
                        }
                    }
                    if (accounts.isEmpty()) Text("请先在账号库添加账号，或取消选择刷开局模块", color = MaterialTheme.colorScheme.error)
                }
                Button(enabled = available && importModules.isNotEmpty() && (LabyrinthConfigModule.REROLL !in importModules || accounts.any { it.id == targetAccountId }), onClick = {
                    launchOperation {
                        if (LabyrinthConfigModule.REROLL in importModules) {
                            application.labyrinthController.importedDifficultyError(targetAccountId!!, configuration.reroll!!.difficulty)?.let { error(it) }
                        }
                        plan = LabyrinthConfigImportPlan(configuration, importModules.toSet(), targetAccountId, application.labyrinthConfiguration.state.value)
                        confirmEmptyScores = false
                    }
                }) { Text("预览变更") }
            }
        }
    }
    undoPreview?.let { previous ->
        val undo = previous.undoImport
        val undoClearsScores = undo != null && LabyrinthConfigModule.PERSONAL_SCORES in undo.modules && undo.strategy.personalRoleScores.isEmpty()
        AlertDialog(onDismissRequest = { if (!working) undoPreview = null }, title = { Text("撤销最近一次导入？") }, text = {
            Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (undo != null) {
                    val restored = LabyrinthConfigShareCodec.create(LabyrinthConfigurationValues(undo.strategy, undo.rerolls),
                        undo.modules, undo.accountId, AppVersion.display, 0)
                    if (LabyrinthConfigModule.REROLL in undo.modules) Text("目标账号：${accounts.firstOrNull { it.id == undo.accountId }?.let { "${it.alias} · ${it.serverName}" } ?: "账号已不存在"}")
                    LabyrinthConfigImportPlan(restored, undo.modules, undo.accountId, previous).changeSummary(roleNames).forEach {
                        Text(it, style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (undoClearsScores) ModuleCheck("我确认清除 ${previous.strategy.personalRoleScores.size} 项自定义评分，恢复使用原评分",
                    confirmUndoEmptyScores, available) { confirmUndoEmptyScores = it }
                message?.let { Text(it) }
                busyReason?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        }, confirmButton = {
            TextButton(enabled = available && saved == previous && (!undoClearsScores || confirmUndoEmptyScores), onClick = {
                launchOperation {
                    val target = undo?.accountId
                    if (undo != null && LabyrinthConfigModule.REROLL in undo.modules) {
                        check(target != null && application.database.accountDao().getById(target) != null) { "恢复目标账号已不存在" }
                    }
                    withContext(NonCancellable) {
                        val restored = withContext(Dispatchers.IO) { application.labyrinthConfiguration.undoImport(previous) }
                        if (undo != null && LabyrinthConfigModule.REROLL in undo.modules && target != null) {
                            application.labyrinthController.onConfigurationImported(target, restored.rerolls[target] ?: LabyrinthRerollSettings())
                        }
                        undoPreview = null
                        plan = null
                        decoded = null
                        output = ""
                        message = "已恢复导入前的配置"
                    }
                }
            }) { Text("确认恢复") }
        }, dismissButton = { TextButton(enabled = !working, onClick = { undoPreview = null }) { Text("取消") } })
    }
    plan?.let { preview ->
        val changes = remember(preview, roleNames) { preview.changeSummary(roleNames) }
        AlertDialog(onDismissRequest = { if (!working) plan = null }, title = { Text("确认替换配置？") }, text = {
            Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (LabyrinthConfigModule.REROLL in preview.selected) Text("目标账号：${accounts.firstOrNull { it.id == preview.targetAccountId }?.let { "${it.alias} · ${it.serverName}" } ?: "账号已不存在"}")
                changes.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                roleWarning?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (preview.clearsPersonalScores) ModuleCheck("我确认清除上述自定义评分并恢复原评分", confirmEmptyScores, available) { confirmEmptyScores = it }
                if (saved != preview.expected) Text("配置已变化，请取消并重新预览", color = MaterialTheme.colorScheme.error)
                busyReason?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                message?.let { Text(it) }
            }
        }, confirmButton = {
            TextButton(enabled = available && saved == preview.expected && (!preview.clearsPersonalScores || confirmEmptyScores), onClick = {
                launchOperation {
                    val target = preview.targetAccountId
                    if (LabyrinthConfigModule.REROLL in preview.selected) {
                        check(target != null && application.database.accountDao().getById(target) != null) { "目标账号已不存在，请重新选择" }
                        application.labyrinthController.importedDifficultyError(target, preview.configuration.reroll!!.difficulty)?.let { error(it) }
                    }
                    withContext(NonCancellable) {
                        val committed = withContext(Dispatchers.IO) {
                            application.labyrinthConfiguration.importConfiguration(preview.configuration, preview.selected, target,
                                confirmEmptyScores, preview.expected)
                        }
                        if (LabyrinthConfigModule.REROLL in preview.selected) {
                            application.labyrinthController.onConfigurationImported(target!!, committed.rerolls.getValue(target))
                        }
                        plan = null
                        decoded = null
                        input = ""
                        output = ""
                        message = "导入成功，下次新任务生效"
                    }
                }
            }) { Text(if (working) "正在导入…" else "确认导入") }
        }, dismissButton = { TextButton(enabled = !working, onClick = { plan = null }) { Text("取消") } })
    }
}

@Composable
private fun ModuleCheck(label: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth()) {
        Checkbox(checked = checked, enabled = enabled, onCheckedChange = onChange)
        Text(label, Modifier.padding(top = 12.dp), style = MaterialTheme.typography.bodyMedium)
    }
}
