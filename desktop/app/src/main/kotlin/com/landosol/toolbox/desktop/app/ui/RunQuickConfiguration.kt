package com.landosol.toolbox.desktop.app.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.landosol.toolbox.desktop.app.adapters.storage.DesktopGoal
import com.landosol.toolbox.desktop.app.ui.theme.DesktopCorners
import com.landosol.toolbox.desktop.app.ui.theme.LocalDesktopColors
import com.landosol.toolbox.labyrinth.*

internal data class RunGoalInput(val guildId: Int, val count: String, val clears: Boolean)

/** UI draft only. Business settings are saved through the original account controller. */
internal class RunSetupDraft(state: LabyrinthUiState, goals: List<DesktopGoal>) {
    private var savedSettings = state.rerollSettings()
    private var savedGoals = goalInputs(state, goals)
    var settings by mutableStateOf(savedSettings)
    var goals by mutableStateOf(savedGoals)
    val dirty get() = settings != savedSettings || goals != savedGoals

    fun reset(state: LabyrinthUiState, goals: List<DesktopGoal>) {
        savedSettings = state.rerollSettings()
        savedGoals = goalInputs(state, goals)
        settings = savedSettings
        this.goals = savedGoals
    }

    fun sync(state: LabyrinthUiState, goals: List<DesktopGoal>) {
        // Account settings can finish loading after the user has begun entering batch targets.
        // Refresh each unedited part independently, without discarding those target inputs.
        val merged = settingsToSave(state)
        savedSettings = state.rerollSettings()
        settings = merged
        if (this.goals == savedGoals) {
            savedGoals = goalInputs(state, goals)
            this.goals = savedGoals
        }
    }

    fun savedGoals(): List<DesktopGoal> = goals.mapNotNull { input ->
        input.count.toIntOrNull()?.takeIf { it in 1..99 }?.let { DesktopGoal(input.guildId, it, input.clears) }
    }

    // Only shortcut fields the user changed may replace the current account settings.
    // In particular, a late account load or advanced form save must keep its Boss/route rules.
    fun settingsToSave(state: LabyrinthUiState): LabyrinthRerollSettings {
        val current = state.rerollSettings()
        return current.copy(
            difficulty=if(settings.difficulty!=savedSettings.difficulty)settings.difficulty else current.difficulty,
            valueAllowance=if(settings.valueAllowance!=savedSettings.valueAllowance)settings.valueAllowance else current.valueAllowance,
            perfectStart=if(settings.perfectStart!=savedSettings.perfectStart)settings.perfectStart else current.perfectStart,
            rerollUntilFound=if(settings.rerollUntilFound!=savedSettings.rerollUntilFound)settings.rerollUntilFound else current.rerollUntilFound,
            maxAttempts=if(settings.maxAttempts!=savedSettings.maxAttempts)settings.maxAttempts else current.maxAttempts,
        )
    }

    fun validationError(state: LabyrinthUiState): String? {
        if (goals.any { it.count.isNotEmpty() && it.count.toIntOrNull() !in 0..99 }) return "公会次数请输入 1–99，留空或 0 跳过"
        val candidate=settingsToSave(state)
        if (candidate != state.rerollSettings()) {
            if (state.selectedAccount == null || !state.settingsReady) return "请先选择账号，等待该账号配置加载"
            candidate.validationError()?.let { return it }
            if (candidate.difficulty !in state.availableDifficulties) return "所选难度尚未解锁"
        }
        return null
    }

    companion object {
        private fun goalInputs(state: LabyrinthUiState, goals: List<DesktopGoal>) = state.guildOptions.take(5).map { guild ->
            val saved = goals.firstOrNull { it.guildId == guild.guildId }
            RunGoalInput(guild.guildId, saved?.count?.toString().orEmpty(), saved?.clears ?: true)
        }
    }
}

internal fun runSetupBlock(dirty: Boolean, fullConfiguration: Boolean): String? = when {
    fullConfiguration -> "请先返回运行台"
    dirty -> "常用配置尚未保存"
    else -> null
}

@Composable internal fun RunTaskSetup(
    state: LabyrinthUiState,
    selected: DesktopTask,
    select: (DesktopTask) -> Unit,
    draft: RunSetupDraft,
    standaloneGuild: Int,
    selectGuild: (Int) -> Unit,
    enabled: Boolean,
    configure: () -> Unit,
    strategies: () -> Unit,
    message: String?,
) {
    Surface(color=MaterialTheme.colorScheme.surface,shape=DesktopCorners.lg,border=BorderStroke(1.dp,Line)) {
        Column(Modifier.fillMaxWidth().padding(16.dp),verticalArrangement=Arrangement.spacedBy(9.dp)) {
            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(selected.title,style=MaterialTheme.typography.titleLarge)
                    Text(selected.summary,style=MaterialTheme.typography.bodySmall,color=Muted)
                }
                TextButton(onClick=configure,enabled=enabled) { Text("高级配置");Symbol("expand_more",modifier=Modifier.size(16.dp)) }
            }
            TaskPicker(selected,select,enabled)
            HorizontalDivider(color=Line)
            if (selected == DesktopTask.BATCH || selected == DesktopTask.REROLL) {
                if (selected == DesktopTask.BATCH) {
                    Row(verticalAlignment=Alignment.CenterVertically) {
                        Text("公会目标",Modifier.weight(1f),style=MaterialTheme.typography.titleSmall)
                        Text("留空跳过 · 每项 1–99 次",style=MaterialTheme.typography.bodySmall,color=Muted)
                    }
                    BatchGoalInputs(state.guildOptions,draft.goals,enabled) { draft.goals=it }
                } else {
                    RunChoice("本次公会",state.guildOptions.firstOrNull{it.guildId==standaloneGuild}?.name ?: "请选择",state.guildOptions,
                        {it.name},enabled,Modifier.fillMaxWidth()) {selectGuild(it.guildId)}
                    Text("公会仅用于本次刷取，不改变批量目标。",style=MaterialTheme.typography.bodySmall,color=Muted)
                }
                Row(horizontalArrangement=Arrangement.spacedBy(12.dp),verticalAlignment=Alignment.Top) {
                    RunChoice("难度",draft.settings.difficulty.toString(),state.availableDifficulties,{it.toString()},
                        enabled&&state.settingsReady&&state.selectedAccount!=null,Modifier.weight(1f)) {draft.settings=draft.settings.copy(difficulty=it)}
                    if (draft.settings.routeEvaluationMode == LabyrinthRouteEvaluationMode.VALUE_ROUTE) {
                        RunChoice("每区允许少拿", "${draft.settings.valueAllowance} 格",(0..LabyrinthRerollOptions.MAX_VALUE_ALLOWANCE).toList(),{"$it 格"},
                            enabled&&state.settingsReady&&state.selectedAccount!=null,Modifier.weight(1f)) {draft.settings=draft.settings.copy(valueAllowance=it)}
                    } else {
                        Column(Modifier.weight(1f)) {
                            Text("旧版路线要求",style=MaterialTheme.typography.bodySmall,color=Muted)
                            Row(verticalAlignment=Alignment.CenterVertically) {
                                Checkbox(draft.settings.perfectStart,{draft.settings=draft.settings.copy(perfectStart=it)},enabled=enabled&&state.settingsReady,modifier=Modifier.size(32.dp))
                                Text("完美开局",style=MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                    RunChoice("刷取上限",if(draft.settings.rerollUntilFound) "刷到目标为止" else "最多 ${draft.settings.maxAttempts} 次",
                        listOf(true,false),{if(it) "刷到目标为止" else "限制尝试次数"},enabled&&state.settingsReady&&state.selectedAccount!=null,Modifier.weight(1f)) {
                        draft.settings=draft.settings.copy(rerollUntilFound=it)
                    }
                }
                if(!draft.settings.rerollUntilFound) Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                    Text("最大尝试次数",style=MaterialTheme.typography.bodySmall)
                    RunNumberInput(draft.settings.maxAttempts,{value->if(value.length<=3&&value.all{it in '0'..'9'})draft.settings=draft.settings.copy(maxAttempts=value)},
                        "最大尝试次数",enabled&&state.settingsReady,Modifier.width(84.dp))
                    Text("1–${LabyrinthRerollOptions.MAX_ATTEMPTS}",style=MaterialTheme.typography.bodySmall,color=Muted)
                }
                if (state.selectedAccount == null || !state.settingsReady) {
                    Text("公会目标可先保存；选择账号后自动恢复该账号的难度和路线。",style=MaterialTheme.typography.bodySmall,color=Muted)
                } else Text("${draft.settings.routeEvaluationMode.label} · ${draft.settings.thirdBlockChoice.label} · Boss 沿用高级配置",style=MaterialTheme.typography.bodySmall,color=Muted)
                if(selected==DesktopTask.REROLL) Text(if(draft.settings.retireExisting)"允许彻底撤退当前开局，开始前会再次确认。" else "不允许彻底撤退当前开局。",style=MaterialTheme.typography.bodySmall,color=Muted)
            } else {
                Text(when(selected) {
                    DesktopTask.READ -> "读取当前账号在服务端的开局，不会替换模拟器里的游戏账号。"
                    DesktopTask.RECOGNIZE -> "分析模拟器当前画面，不点击、不登录。可用于检查当前页面。"
                    else -> "按已保存策略操作当前游戏画面，请先进入游戏并确认设备已就绪。"
                },style=MaterialTheme.typography.bodyMedium)
                TextButton(onClick=strategies,enabled=enabled){Text("查看当前策略 →")}
            }
            if (draft.dirty) {
                Text("有未保存更改 · 请在底部保存或取消",color=LocalDesktopColors.current.warn,style=MaterialTheme.typography.bodySmall)
            } else Row(verticalAlignment=Alignment.CenterVertically) {
                Symbol("check_circle",tint=LocalDesktopColors.current.ok,modifier=Modifier.size(15.dp))
                Spacer(Modifier.width(6.dp))
                Text(message ?: if(selected==DesktopTask.BATCH&&draft.savedGoals().isEmpty())"填写并保存公会目标后开始" else if(selected==DesktopTask.BATCH) "已恢复上次保存的目标与配置" else "使用已保存配置",style=MaterialTheme.typography.bodySmall,color=Muted)
                Spacer(Modifier.weight(1f))
                if(selected==DesktopTask.BATCH) Text("${draft.savedGoals().size} 个公会 · 共 ${draft.savedGoals().sumOf{it.count}} 次",style=MaterialTheme.typography.bodySmall,color=Muted)
            }
            if(draft.dirty) message?.let{Text(it,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)}
        }
    }
}

@Composable private fun TaskPicker(selected: DesktopTask,select:(DesktopTask)->Unit,enabled:Boolean) {
    var tools by remember {mutableStateOf(false)}
    Row(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalAlignment=Alignment.CenterVertically) {
        listOf(DesktopTask.BATCH,DesktopTask.REROLL).forEach { task ->
            FilterChip(selected==task,{select(task)},label={Text(task.title)},enabled=enabled)
        }
        Box {
            FilterChip(selected !in listOf(DesktopTask.BATCH,DesktopTask.REROLL),{tools=true},enabled=enabled,
                label={Text(if(selected in listOf(DesktopTask.BATCH,DesktopTask.REROLL))"辅助任务" else selected.title)},trailingIcon={Symbol("expand_more",modifier=Modifier.size(16.dp))})
            DropdownMenu(tools&&LocalControlVisible.current,{tools=false}) {
                listOf(DesktopTask.READ,DesktopTask.RECOGNIZE,DesktopTask.ENTRY).forEach {task->
                    DropdownMenuItem(text={Column{Text(task.title);Text(task.mode,style=MaterialTheme.typography.bodySmall,color=Muted)}},onClick={tools=false;select(task)})
                }
            }
        }
    }
}

@Composable internal fun BatchGoalInputs(guilds:List<LabyrinthGuildOption>,goals:List<RunGoalInput>,enabled:Boolean,change:(List<RunGoalInput>)->Unit) {
    Column {
        Row(Modifier.fillMaxWidth().padding(horizontal=10.dp,vertical=4.dp)) {
            Text("公会",Modifier.weight(1f),color=Muted,style=MaterialTheme.typography.bodySmall)
            Text("次数",Modifier.width(84.dp),color=Muted,style=MaterialTheme.typography.bodySmall)
            Text("计数方式",Modifier.width(118.dp),color=Muted,style=MaterialTheme.typography.bodySmall)
        }
        goals.forEach { input ->
            val name=guilds.firstOrNull{it.guildId==input.guildId}?.name ?: "公会 ${input.guildId}"
            Row(Modifier.fillMaxWidth().heightIn(min=39.dp).padding(horizontal=10.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                Text(name,Modifier.weight(1f),style=MaterialTheme.typography.bodyMedium,maxLines=1,overflow=TextOverflow.Ellipsis)
                RunNumberInput(input.count,{value->if(value.length<=2&&value.all{it in '0'..'9'})change(goals.map{if(it.guildId==input.guildId)it.copy(count=value)else it})},
                    "$name 次数",enabled,Modifier.width(74.dp),"跳过")
                Box(Modifier.width(108.dp)) {
                    var modes by remember{mutableStateOf(false)}
                    TextButton(onClick={modes=true},enabled=enabled,contentPadding=PaddingValues(horizontal=4.dp,vertical=2.dp)) {
                        Text(if(input.clears)"按通关计" else "按开局计",style=MaterialTheme.typography.bodySmall);Symbol("expand_more",modifier=Modifier.size(14.dp))
                    }
                    DropdownMenu(modes&&LocalControlVisible.current,{modes=false}) {
                        listOf(true,false).forEach{clears->DropdownMenuItem(text={Text(if(clears)"按通关计" else "按开局计")},onClick={modes=false;change(goals.map{if(it.guildId==input.guildId)it.copy(clears=clears)else it})})}
                    }
                }
            }
            HorizontalDivider(Modifier.padding(horizontal=10.dp),color=Line)
        }
    }
}

@Composable private fun <T> RunChoice(label:String,value:String,options:List<T>,optionLabel:(T)->String,enabled:Boolean,modifier:Modifier=Modifier,change:(T)->Unit) {
    var expanded by remember{mutableStateOf(false)}
    Column(modifier,verticalArrangement=Arrangement.spacedBy(3.dp)) {
        Text(label,style=MaterialTheme.typography.bodySmall,color=Muted)
        Box {
            OutlinedButton(onClick={expanded=true},enabled=enabled,shape=MaterialTheme.shapes.small,contentPadding=PaddingValues(horizontal=10.dp,vertical=7.dp),modifier=Modifier.fillMaxWidth().heightIn(min=34.dp)) {
                Text(value,Modifier.weight(1f),maxLines=1,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.bodySmall)
                Symbol("expand_more",modifier=Modifier.size(16.dp))
            }
            DropdownMenu(expanded&&LocalControlVisible.current,{expanded=false}){options.forEach{option->DropdownMenuItem(text={Text(optionLabel(option))},onClick={expanded=false;change(option)})}}
        }
    }
}

@Composable private fun RunNumberInput(value:String,change:(String)->Unit,label:String,enabled:Boolean,modifier:Modifier=Modifier,placeholder:String="") {
    BasicTextField(value,change,enabled=enabled,singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number),
        textStyle=MaterialTheme.typography.bodyMedium.copy(color=if(enabled)LocalContentColor.current else Muted),cursorBrush=SolidColor(Accent),
        modifier=modifier.semantics{contentDescription=label}.border(1.dp,Line,DesktopCorners.sm).padding(horizontal=9.dp,vertical=6.dp),
        decorationBox={inner->Box{if(value.isBlank())Text(placeholder,style=MaterialTheme.typography.bodySmall,color=Muted);inner()}})
}
