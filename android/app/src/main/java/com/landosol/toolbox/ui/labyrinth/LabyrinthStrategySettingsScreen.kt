package com.landosol.toolbox.ui.labyrinth

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.landosol.toolbox.labyrinth.LabyrinthBossTeamMode
import com.landosol.toolbox.labyrinth.LabyrinthCombatKind
import com.landosol.toolbox.labyrinth.LabyrinthCustomEventCatalog
import com.landosol.toolbox.labyrinth.LabyrinthRelicMark
import com.landosol.toolbox.labyrinth.LabyrinthSpecialEquipmentMode
import com.landosol.toolbox.labyrinth.RESPONSE_INTERVAL_FAST
import com.landosol.toolbox.labyrinth.RESPONSE_INTERVAL_STANDARD
import com.landosol.toolbox.labyrinth.LabyrinthStrategySettings
import com.landosol.toolbox.labyrinth.LabyrinthStrategySettingsCodec
import com.landosol.toolbox.labyrinth.LabyrinthRoleRatingItem
import com.landosol.toolbox.ui.CompactTopBar
import kotlin.math.roundToInt

private val StrategySaver = Saver<LabyrinthStrategySettings, String>(
    save = { LabyrinthStrategySettingsCodec.encodeDraft(it) },
    restore = LabyrinthStrategySettingsCodec::decodeDraft,
)

private val strategySections = listOf(
    "评分", "选人", "开局", "战斗设置", "Boss编组", "战斗失败", "遗物", "遗物优先级",
    "商店", "事件", "事件自定义", "特别装备", "响应速度",
)

internal enum class StrategyLeaveDecision { STAY, CONFIRM_DISCARD, LEAVE }

internal fun strategyLeaveDecision(saving: Boolean, hasChanges: Boolean): StrategyLeaveDecision =
    when {
        saving -> StrategyLeaveDecision.STAY
        hasChanges -> StrategyLeaveDecision.CONFIRM_DISCARD
        else -> StrategyLeaveDecision.LEAVE
    }

/** 独立策略页面：保留原草稿、分组、子编辑器与显式保存。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LabyrinthStrategySettingsScreen(
    saved: LabyrinthStrategySettings,
    saving: Boolean,
    message: String?,
    onSave: (LabyrinthStrategySettings) -> Unit,
    onClose: () -> Unit,
    loadRoleRatings: suspend () -> List<LabyrinthRoleRatingItem> = { emptyList() },
    registerLeaveHandler: (((() -> Unit) -> Unit)?) -> Unit = {},
) {
    var draft by rememberSaveable(stateSaver = StrategySaver) { mutableStateOf(saved) }
    var section by rememberSaveable { mutableStateOf("评分") }
    val sectionStateHolder = rememberSaveableStateHolder()
    var pendingLeave by remember { mutableStateOf<(() -> Unit)?>(null) }
    var confirmReset by remember { mutableStateOf(false) }
    var showRoleRatings by rememberSaveable { mutableStateOf(false) }
    var showOpeningRoster by rememberSaveable { mutableStateOf(false) }
    val requestLeave: (() -> Unit) -> Unit = { leave ->
        if (!showRoleRatings && !showOpeningRoster && !confirmReset && pendingLeave == null) {
            when (strategyLeaveDecision(saving, draft != saved)) {
                StrategyLeaveDecision.STAY -> Unit
                StrategyLeaveDecision.CONFIRM_DISCARD -> pendingLeave = leave
                StrategyLeaveDecision.LEAVE -> leave()
            }
        }
    }
    val currentRequestLeave by rememberUpdatedState(requestLeave)
    DisposableEffect(registerLeaveHandler) {
        registerLeaveHandler { leave -> currentRequestLeave(leave) }
        onDispose { registerLeaveHandler(null) }
    }
    val close = { requestLeave(onClose) }
    BackHandler(enabled = !showRoleRatings && !showOpeningRoster && !confirmReset && pendingLeave == null) {
        close()
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wideLayout = maxWidth >= 720.dp
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            topBar = {
                CompactTopBar(title = "策略设置", onBack = close, backEnabled = !saving, actions = {
                    TextButton(onClick = { confirmReset = true }, enabled = !saving) {
                        Text("恢复默认", style = MaterialTheme.typography.labelLarge)
                    }
                })
            },
            bottomBar = {
                Surface(tonalElevation = 3.dp) {
                    Row(
                        Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val error = draft.validationError()
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(error ?: if (saving) "正在保存…" else if (draft == saved) "配置已保存" else "有未保存的修改",
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                            Text("所有账号共用 · 下次启动生效", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                        }
                        Button(onClick = { onSave(draft) }, enabled = draft.canSaveDraft(saved, saving),
                            modifier = Modifier.heightIn(min = 48.dp)) {
                            Text(if (saving) "保存中…" else "保存配置")
                        }
                    }
                }
            },
        ) { padding ->
            Row(Modifier.fillMaxSize().padding(padding)) {
                if (wideLayout) {
                    Surface(Modifier.width(180.dp).fillMaxHeight(), color = MaterialTheme.colorScheme.surfaceVariant) {
                        Column(
                            Modifier.verticalScroll(rememberScrollState()).selectableGroup().padding(8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text("配置分类", Modifier.padding(12.dp), style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            strategySections.forEach { name ->
                                Surface(
                                    shape = MaterialTheme.shapes.medium,
                                    color = if (section == name) MaterialTheme.colorScheme.secondaryContainer
                                        else MaterialTheme.colorScheme.surfaceVariant,
                                ) {
                                    Text(name, Modifier.fillMaxWidth().selectable(
                                        selected = section == name,
                                        role = Role.Tab,
                                        onClick = { section = name },
                                    ).heightIn(min = 48.dp).padding(12.dp),
                                        style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                        }
                    }
                }
                sectionStateHolder.SaveableStateProvider(section) {
                    Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState())) {
                        if (!wideLayout) {
                            StrategyCategoryPicker(section = section, onSelect = { section = it })
                        }
                        Column(
                            Modifier.fillMaxWidth().padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            when (section) {
                                "评分" -> StrategyPanel("评分", "玩家评分 ${draft.playerWeight}% · 系统权重自动归一化") {
                                    OutlinedButton(onClick = { showRoleRatings = true }, enabled = !saving,
                                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                                        Text("角色头像与个人评分 · 已修改 ${draft.personalRoleScores.size} 人")
                                    }
                                    Text("玩家评分缺失时仍按原规则降低有效权重。以下系统项目是相对权重，无需手动凑到 100。")
                                    StrategyNumber("玩家评分占比", draft.playerWeight, 0..100, "%", !saving) { draft = draft.copy(playerWeight = it) }
                                    StrategyNumber("系统：伤害", draft.damageWeight, 0..100, "", !saving) { draft = draft.copy(damageWeight = it) }
                                    StrategyNumber("系统：生存", draft.survivalWeight, 0..100, "", !saving) { draft = draft.copy(survivalWeight = it) }
                                    StrategyNumber("系统：功能", draft.functionWeight, 0..100, "", !saving) { draft = draft.copy(functionWeight = it) }
                                    StrategyNumber("系统：阵型", draft.formationWeight, 0..100, "", !saving) { draft = draft.copy(formationWeight = it) }
                                    StrategyNumber("系统：协同", draft.cohesionWeight, 0..100, "", !saving) { draft = draft.copy(cohesionWeight = it) }
                                    StrategySwitch(
                                        "属性配队增伤",
                                        "关闭后角色属性仍会识别，并继续用于筛选/显示；只是评分时不再计算同属性 2/3/4/5 人的 8/16/25/75% 伤害加成。",
                                        draft.attributePairingBonusEnabled,
                                        !saving,
                                    ) { draft = draft.copy(attributePairingBonusEnabled = it) }
                                    StrategyNumber("EX/Boss物法混编扣分", draft.mixedDamagePenalty, 0..30, "分", !saving) {
                                        draft = draft.copy(mixedDamagePenalty = it)
                                    }
                                    StrategyNumber("有效效果角色系统加分", draft.effectiveCharacterSystemBonus, 0..30, "分", !saving) {
                                        draft = draft.copy(effectiveCharacterSystemBonus = it)
                                    }
                                    Text("有效效果加分只进入该角色的系统侧评价，再按玩家/系统权重融合；不会修改玩家评分，也不会在最终队伍总分外追加奖励。")
                                }
                                "选人" -> StrategyPanel("选人", "第二队 ${draft.secondTeamWeight}% · 第三队 ${draft.thirdTeamWeight}%") {
                                    Text("角色奖励按能改善哪一队来评估，第一队权重固定为 100%。重复纯掩护/纯治疗按扣分处理，不是人数硬限制。")
                                    StrategyNumber("第二队部署权重", draft.secondTeamWeight, 0..100, "%", !saving) { draft = draft.copy(secondTeamWeight = it) }
                                    StrategyNumber("第三队部署权重", draft.thirdTeamWeight, 0..100, "%", !saving) { draft = draft.copy(thirdTeamWeight = it) }
                                    StrategyNumber("重复纯职能基础扣分", draft.duplicateRolePenalty, 0..30, "分", !saving) { draft = draft.copy(duplicateRolePenalty = it) }
                                }
                                "战斗设置" -> StrategyPanel("战斗设置", "默认关闭 · 保存后下次启动生效") {
                                    StrategySwitch(
                                        "前排生存门槛",
                                        "开启后限制新编组的前排生存资格，保留原有无T兜底和补刀例外；关闭不取消生存评分与开战安全检查。",
                                        draft.frontlineSurvivalGateEnabled,
                                        !saving,
                                    ) { draft = draft.copy(frontlineSurvivalGateEnabled = it) }
                                    StrategySwitch(
                                        "普战跳编队",
                                        "仅普通战首次尝试沿用已确认的满5人队伍，跳过重新选人；关闭按推荐阵容编组，不是跳过战斗。",
                                        draft.normalBattleSkipTeamSelectionEnabled,
                                        !saving,
                                    ) { draft = draft.copy(normalBattleSkipTeamSelectionEnabled = it) }
                                    if (draft.normalBattleSkipTeamSelectionEnabled && draft.frontlineSurvivalGateEnabled) {
                                        Text("推荐与识别流程正常时，沿用的现队不会按前排门槛重新换人。", style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                                "Boss编组" -> StrategyPanel(
                                    "Boss编组",
                                    (if (draft.bossTeamMode == LabyrinthBossTeamMode.MULTI_TEAM) "多队编组（默认）" else "单队编组") +
                                        if (draft.preferPureBossDamageSystem) " · 优先纯物/法" else " · 允许自由混编",
                                ) {
                                    Text("多队模式会按当前角色池自动使用可安全组成的3/2/1队，并优化多队总评分；单队模式只编第一队。前排资格限制由“战斗设置”分类中的“前排生存门槛”开关控制。物法偏好是软限制，不会为了纯体系牺牲明显更强或更能生存的阵容。")
                                    StrategyRadioChoice(
                                        label = "多队编组",
                                        detail = "默认。依次生成并自动配置三支互不重复的 Boss 队伍。",
                                        selected = draft.bossTeamMode == LabyrinthBossTeamMode.MULTI_TEAM,
                                        enabled = !saving,
                                    ) { draft = draft.copy(bossTeamMode = LabyrinthBossTeamMode.MULTI_TEAM) }
                                    StrategyRadioChoice(
                                        label = "单队编组",
                                        detail = "只配置第一队；确认第二、三队为空后开始战斗。",
                                        selected = draft.bossTeamMode == LabyrinthBossTeamMode.SINGLE_TEAM,
                                        enabled = !saving,
                                    ) { draft = draft.copy(bossTeamMode = LabyrinthBossTeamMode.SINGLE_TEAM) }
                                    StrategySwitch(
                                        "优先纯物理/纯法术编组",
                                        "默认开启。Boss 使用与 EX 相同的物法混编扣分；关闭后 Boss 可自由混编，EX 仍保持单一物/法体系偏好。",
                                        draft.preferPureBossDamageSystem,
                                        !saving,
                                    ) { draft = draft.copy(preferPureBossDamageSystem = it) }
                                    StrategySwitch(
                                        "单队Boss失败后切换多队",
                                        "仅单队编组生效。达到下面设置的重新挑战次数后仍失败，不直接重刷开局，而是返回编组并临时切换为多队模式；若开启前排生存门槛，则把一号位生存线从55分放宽到40分；按当前角色池尽量组成3/2/1队。不会永久修改你的Boss编组设置。",
                                        draft.singleBossFallbackToMultiAfterThreeFailures,
                                        !saving,
                                    ) { draft = draft.copy(singleBossFallbackToMultiAfterThreeFailures = it) }
                                    StrategyNumber(
                                        "单队Boss重新挑战次数",
                                        draft.singleBossRetryCountBeforeMulti,
                                        0..10,
                                        "次",
                                        !saving && draft.singleBossFallbackToMultiAfterThreeFailures,
                                    ) { draft = draft.copy(singleBossRetryCountBeforeMulti = it) }
                                    Text(
                                        "当前设置：首次挑战失败后最多重新挑战 ${draft.singleBossRetryCountBeforeMulti} 次；" +
                                            "第 ${draft.singleBossRetryCountBeforeMulti + 1} 次挑战仍失败时切换多队。0 表示首次失败就切多队。",
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                                "战斗失败" -> StrategyPanel(
                                    "战斗失败",
                                    when {
                                        draft.bossTeamMode == LabyrinthBossTeamMode.SINGLE_TEAM && draft.singleBossFallbackToMultiAfterThreeFailures ->
                                            "单队${draft.singleBossRetryCountBeforeMulti + 1}次挑战失败后切多队"
                                        draft.rerollAfterThreeBattleFailures -> "3次失败后自动重刷"
                                        else -> "达到原重试上限后停止"
                                    },
                                ) {
                                    StrategySwitch(
                                        "3次失败后自动重刷开局",
                                        "首次挑战 + 2次重新挑战均失败后，不点击结束/结算；停止视觉执行并直接调用刷开局流程撤退当前开局，再按已保存的刷开局条件重刷。开启时 Boss 也允许总共挑战3次。",
                                        draft.rerollAfterThreeBattleFailures,
                                        !saving,
                                    ) { draft = draft.copy(rerollAfterThreeBattleFailures = it) }
                                }
                                "遗物" -> StrategyPanel("遗物", "基础 ${draft.baselineTarget} 层 · 截止区域 ${draft.baselineLastArea}") {
                                    Text("加速、暴击、守备的基础层数仅作加分，不强制凑齐。截止区域为 0 表示不使用前期加分；15 层档位优先保留。")
                                    StrategyNumber("基础目标", draft.baselineTarget, 1..4, "层", !saving) { draft = draft.copy(baselineTarget = it) }
                                    StrategyNumber("前期加分截止区域", draft.baselineLastArea, 0..5, "", !saving) { draft = draft.copy(baselineLastArea = it) }
                                    StrategyNumber("转追削弱的最低层数", draft.debuffPivotMinimum, 5..15, "层", !saving) { draft = draft.copy(debuffPivotMinimum = it) }
                                    StrategyNumber("削弱落后主印记的容忍量", draft.debuffPivotTolerance, 0..5, "层", !saving) { draft = draft.copy(debuffPivotTolerance = it) }
                                }
                                "遗物优先级" -> StrategyPanel("遗物优先级", relicPrioritySummary(draft.relicMarkPriority)) {
                                    Text(
                                        "按你的顺序主追印记。留空时沿用内置顺序（以当前层数最高的未满印记为主追）。" +
                                            "能一次跨到 15 层的选项仍然优先，这条不受顺序影响。",
                                    )
                                    RelicPriorityEditor(
                                        priority = draft.relicMarkPriority,
                                        enabled = !saving,
                                        onChange = { draft = draft.copy(relicMarkPriority = it) },
                                    )
                                }
                                "商店" -> StrategyPanel("商店", if (draft.buyRelics) "购买遗物 · ${if (draft.refreshShop) "区域 ${draft.refreshFromArea} 起刷新" else "不刷新"}" else "不购买") {
                                    Text("遗物始终优先；达到允许刷新起始区域后，本轮遗物买完或当前商品行已无可购买遗物且刷新可用时，继续花300金币刷新。只有遗物/刷新都无法继续时，才用剩余金币购买已确认的职能印记。无法确认的商品不会盲买。")
                                    StrategySwitch("购买遗物", "关闭后退出商店，也不会刷新。", draft.buyRelics, !saving) { draft = draft.copy(buyRelics = it) }
                                    StrategySwitch("允许商店刷新", "买完当前可确认遗物后，才考虑花费 300 金币刷新。", draft.refreshShop, !saving && draft.buyRelics) { draft = draft.copy(refreshShop = it) }
                                    if (draft.buyRelics && draft.refreshShop) {
                                        StrategyNumber("允许刷新起始区域", draft.refreshFromArea, 1..5, "", !saving) { draft = draft.copy(refreshFromArea = it) }
                                        if (draft.refreshFromArea < 5) Text("提前刷新会消耗后续商店的金币，请谨慎调整。", color = MaterialTheme.colorScheme.error)
                                    }
                                }
                                "开局" -> StrategyPanel("开局", openingRosterSummary(draft.openingRosters)) {
                                    Text(
                                        "每个公会开局固定选 3 名角色。槽位内可以排多个候选，按顺序取第一个能在列表里找到的；" +
                                            "公会附赠的角色由游戏决定，不可编辑。",
                                    )
                                    OutlinedButton(
                                        onClick = { showOpeningRoster = true },
                                        enabled = !saving,
                                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                                    ) {
                                        Text("编辑开局角色 · ${openingRosterSummary(draft.openingRosters)}")
                                    }
                                    draft.openingRosterError()?.let {
                                        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                                "事件" -> StrategyPanel("事件", "普通 ${draft.normalWinRate}% · 极难 ${draft.extremeWinRate}%") {
                                    Text("仅用于比较事件战斗奖励的期望值，不代表实测胜率或胜利保证。")
                                    StrategyNumber("普通战估计胜率", draft.normalWinRate, 0..100, "%", !saving) { draft = draft.copy(normalWinRate = it) }
                                    StrategyNumber("极难战估计胜率", draft.extremeWinRate, 0..100, "%", !saving) { draft = draft.copy(extremeWinRate = it) }
                                }
                                "事件自定义" -> EventCustomizationEditor(
                                    settings = draft,
                                    saving = saving,
                                    onChange = { draft = it },
                                )
                                "响应速度" -> StrategyPanel("响应速度", responseIntervalSummary(draft)) {
                                    Text("延迟越低响应越快，但CPU需求会更高")
                                    StrategyChoice(
                                        label = "识别节拍",
                                        value = draft.responseIntervalMillis,
                                        choices = listOf(RESPONSE_INTERVAL_FAST, RESPONSE_INTERVAL_STANDARD),
                                        enabled = !saving,
                                    ) { draft = draft.copy(responseIntervalMillis = it) }
                                }
                                "特别装备" -> StrategyPanel("特别装备", specialEquipmentSummary(draft)) {
                                    Text("自动穿戴会在队伍编组页点开「特别装备」并尝试自动穿戴，只对下面勾选的关卡类型生效", style = MaterialTheme.typography.bodySmall)
                                    Text("是否执行按本次启动读取的设置快照判定，运行中保存只影响下一次启动", style = MaterialTheme.typography.bodySmall)
                                    StrategyRadioChoice(
                                        label = "关闭",
                                        detail = "默认，本次编组页不会为特别装备产生任何额外点击",
                                        selected = draft.specialEquipmentMode == LabyrinthSpecialEquipmentMode.OFF,
                                        enabled = !saving,
                                    ) { draft = draft.copy(specialEquipmentMode = LabyrinthSpecialEquipmentMode.OFF) }
                                    StrategyRadioChoice(
                                        label = "自动穿戴",
                                        detail = "只在队伍编组页执行，且仅限下面勾选的关卡类型",
                                        selected = draft.specialEquipmentMode == LabyrinthSpecialEquipmentMode.AUTO_EQUIP,
                                        enabled = !saving,
                                    ) { draft = draft.copy(specialEquipmentMode = LabyrinthSpecialEquipmentMode.AUTO_EQUIP) }
                                    if (draft.specialEquipmentMode == LabyrinthSpecialEquipmentMode.AUTO_EQUIP) {
                                        Text("需要自动穿戴的关卡类型（可同时勾选，至少勾选一个）：")
                                        LabyrinthCombatKind.entries.forEach { kind ->
                                            StrategyCheckChoice(
                                                label = kind.specialEquipmentLabel(),
                                                checked = draft.autoEquipsSpecialEquipment(kind),
                                                enabled = !saving,
                                            ) { checked ->
                                                draft = draft.copy(
                                                    specialEquipmentKinds = if (checked) {
                                                        draft.specialEquipmentKinds + kind
                                                    } else {
                                                        draft.specialEquipmentKinds - kind
                                                    },
                                                )
                                            }
                                        }
                                        if (draft.specialEquipmentKinds.isEmpty()) {
                                            Text(
                                                LabyrinthStrategySettings.SPECIAL_EQUIPMENT_EMPTY_KINDS_ERROR,
                                                color = MaterialTheme.colorScheme.error,
                                                style = MaterialTheme.typography.bodySmall,
                                            )
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
    if (showRoleRatings) LabyrinthRoleRatingsScreen(
        scores = draft.personalRoleScores,
        loadCatalog = loadRoleRatings,
        onScoreChange = { id, value -> draft = draft.copy(personalRoleScores =
            if (value == null) draft.personalRoleScores - id else draft.personalRoleScores + (id to value)) },
        onClose = { showRoleRatings = false },
    )
    if (showOpeningRoster) LabyrinthOpeningRosterScreen(
        rosters = draft.openingRosters,
        loadCatalog = loadRoleRatings,
        onChange = { guildId, slots ->
            draft = draft.copy(
                openingRosters = if (slots == null) {
                    draft.openingRosters - guildId
                } else {
                    draft.openingRosters + (guildId to slots)
                },
            )
        },
        onClose = { showOpeningRoster = false },
    )
    if (pendingLeave != null) AlertDialog(onDismissRequest = { pendingLeave = null },
        title = { Text("放弃未保存的修改？") }, text = { Text("已保存的配置不会改变。") },
        confirmButton = { TextButton(onClick = {
            if (!saving) {
                val leave = pendingLeave
                pendingLeave = null
                leave?.invoke()
            }
        }, enabled = !saving) { Text("放弃修改") } },
        dismissButton = { TextButton(onClick = { pendingLeave = null }) { Text("继续编辑") } })
    if (confirmReset) AlertDialog(onDismissRequest = { confirmReset = false },
        title = { Text("恢复全部默认策略？") }, text = { Text("恢复后仍需点击保存，才会替换已保存的配置。") },
        confirmButton = { TextButton(onClick = { draft = LabyrinthStrategySettings(); confirmReset = false }) { Text("恢复默认") } },
        dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("取消") } })
}

@Composable
private fun EventCustomizationEditor(
    settings: LabyrinthStrategySettings,
    saving: Boolean,
    onChange: (LabyrinthStrategySettings) -> Unit,
) {
    var expandedCardIds by rememberSaveable { mutableStateOf(emptyList<String>()) }
    val configured = settings.configuredEventChoiceOverrides()
    StrategyPanel(
        title = "事件自定义",
        summary = if (settings.eventCustomizationEnabled) "已开启 · ${LabyrinthCustomEventCatalog.cards.size} 个可自定义事件" else "关闭（默认） · 已配置分支保留",
    ) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            StrategySwitch(
                label = "启用事件自定义",
                detail = "默认关闭，开启后使用自定义配置。",
                checked = settings.eventCustomizationEnabled,
                enabled = !saving,
            ) { onChange(settings.copy(eventCustomizationEnabled = it)) }
            Text("总开关和分支配置保存后，从下次自动任务启动生效；当前任务不变。", style = MaterialTheme.typography.bodySmall)
            Text("启用后优先选择指定分支；按钮不可用时回退到既有事件选择策略，不硬点。", style = MaterialTheme.typography.bodySmall)
        }
        LabyrinthCustomEventCatalog.cards.forEach { card ->
            val selectedChoice = card.choices.firstOrNull { it.runtimeName == configured[card.runtimeName] }
            StrategySection(
                title = card.name,
                summary = if (card.id == "arena") {
                    "四区共用 · ${selectedChoice?.name ?: "已保存分支不在目录中"}"
                } else selectedChoice?.name ?: "已保存分支不在目录中",
                active = if (card.id in expandedCardIds) card.name else "",
                select = { title ->
                    expandedCardIds = if (title.isEmpty()) expandedCardIds - card.id else expandedCardIds + card.id
                },
            ) {
                val enabled = !saving && settings.eventCustomizationEnabled
                Column(Modifier.fillMaxWidth().alpha(if (enabled) 1f else 0.38f)) {
                    card.choices.sortedBy { it.slot }.forEach { choice ->
                        StrategyRadioChoice(
                            label = choice.name,
                            detail = choice.description,
                            selected = selectedChoice == choice,
                            enabled = enabled,
                        ) {
                            onChange(settings.copy(
                                eventChoiceOverrides = settings.eventChoiceOverrides + (card.runtimeName to choice.runtimeName),
                            ))
                        }
                    }
                }
            }
        }
    }
}

private fun relicPrioritySummary(priority: List<LabyrinthRelicMark>): String =
    if (priority.isEmpty()) "使用内置顺序" else priority.joinToString(" > ") { it.label }

/** 只把关卡类型翻成界面文案；取值仍是 [LabyrinthCombatKind] 本身，不另造枚举或映射表。 */
private fun LabyrinthCombatKind.specialEquipmentLabel(): String = when (this) {
    LabyrinthCombatKind.NORMAL -> "普通"
    LabyrinthCombatKind.EX -> "EX"
    LabyrinthCombatKind.BOSS -> "BOSS"
}

/**
 * 分组摘要必须与实际执行读同一份字段和同一个判据：勾选状态来自
 * [LabyrinthStrategySettings.autoEquipsSpecialEquipment]，不在界面里另写一套规则。
 */
private fun specialEquipmentSummary(settings: LabyrinthStrategySettings): String =
    if (settings.specialEquipmentMode == LabyrinthSpecialEquipmentMode.OFF) {
        "关闭（默认）"
    } else {
        val kinds = LabyrinthCombatKind.entries.filter { settings.autoEquipsSpecialEquipment(it) }
        "自动穿戴 · " + if (kinds.isEmpty()) "未选择关卡类型" else kinds.joinToString("、") { it.specialEquipmentLabel() }
    }

@Composable
private fun RelicPriorityEditor(
    priority: List<LabyrinthRelicMark>,
    enabled: Boolean,
    onChange: (List<LabyrinthRelicMark>) -> Unit,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        priority.forEachIndexed { index, mark ->
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("${index + 1}. ${mark.label}", Modifier.weight(1f))
                TextButton(
                    onClick = { onChange(priority.toMutableList().also { it.add(index - 1, it.removeAt(index)) }) },
                    enabled = enabled && index > 0,
                ) { Text("上移") }
                TextButton(onClick = { onChange(priority - mark) }, enabled = enabled) { Text("移除") }
            }
        }
        val remaining = LabyrinthRelicMark.entries.filterNot { it in priority }
        if (remaining.isNotEmpty()) {
            Text("添加：", style = MaterialTheme.typography.bodySmall)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                remaining.forEach { mark ->
                    OutlinedButton(onClick = { onChange(priority + mark) }, enabled = enabled) {
                        Text(mark.label)
                    }
                }
            }
        }
        if (priority.isNotEmpty()) {
            TextButton(onClick = { onChange(emptyList()) }, enabled = enabled) { Text("恢复内置顺序") }
        }
    }
}

@Composable
private fun StrategyRadioChoice(
    label: String,
    detail: String,
    selected: Boolean,
    enabled: Boolean,
    select: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = select)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled, modifier = Modifier.padding(end = 12.dp))
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun StrategyCheckChoice(
    label: String,
    checked: Boolean,
    enabled: Boolean,
    change: (Boolean) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .toggleable(value = checked, enabled = enabled, role = Role.Checkbox, onValueChange = change)
            .semantics { contentDescription = label },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = null, enabled = enabled)
        Text(label, Modifier.weight(1f))
    }
}

@Composable
private fun StrategyCategoryPicker(section: String, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(section)
                Text("切换分类 ▾")
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }, modifier = Modifier.heightIn(max = 360.dp)) {
            strategySections.forEach { name ->
                DropdownMenuItem(
                    text = { Text(name, color = if (section == name) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface) },
                    onClick = { expanded = false; onSelect(name) },
                )
            }
        }
    }
}

@Composable
private fun StrategyPanel(title: String, summary: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        HorizontalDivider()
        content()
    }
}

@Composable
private fun StrategySection(title: String, summary: String, active: String, select: (String) -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { select(if (active == title) "" else title) }) {
                Text("$title ${if (active == title) "▴" else "▾"}", style = MaterialTheme.typography.titleMedium)
                Text(summary, style = MaterialTheme.typography.bodySmall)
            }
            if (active == title) content()
        }
    }
}

@Composable
private fun StrategyNumber(label: String, value: Int, range: IntRange, suffix: String, enabled: Boolean, change: (Int) -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.small) {
                Text("$value$suffix", Modifier.padding(horizontal = 8.dp, vertical = 4.dp), style = MaterialTheme.typography.labelLarge)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { change(value - 1) }, enabled = enabled && value > range.first,
                modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).semantics { contentDescription = "$label 减少" }) { Text("−") }
            Slider(value = value.toFloat(), onValueChange = { change(it.roundToInt()) },
                valueRange = range.first.toFloat()..range.last.toFloat(), steps = (range.last - range.first - 1).coerceAtLeast(0),
                enabled = enabled, modifier = Modifier.weight(1f).semantics { contentDescription = label })
            TextButton(onClick = { change(value + 1) }, enabled = enabled && value < range.last,
                modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).semantics { contentDescription = "$label 增加" }) { Text("+") }
        }
    }
}

@Composable
private fun StrategySwitch(label: String, detail: String, checked: Boolean, enabled: Boolean, change: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = change)
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

/**
 * 档位开关：Slider 的取值是 [choices] 的下标，所以拖动只会停在这几个档位上，不会滑出中间值。
 * [choices] 至少两项；两档时 `steps = 0`，滑块只有两个端点。
 */
@Composable
private fun StrategyChoice(
    label: String,
    value: Int,
    choices: List<Int>,
    enabled: Boolean,
    change: (Int) -> Unit,
) {
    val index = choices.indexOf(value).coerceIn(0, choices.lastIndex)
    Column {
        Text("$label：${choices[index]} ms", style = MaterialTheme.typography.bodyMedium)
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { change(choices[(index - 1).coerceAtLeast(0)]) }, enabled = enabled && index > 0,
                modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).semantics { contentDescription = "$label 变快" }) { Text("−") }
            Slider(
                value = index.toFloat(),
                onValueChange = { change(choices[it.roundToInt().coerceIn(0, choices.lastIndex)]) },
                valueRange = 0f..choices.lastIndex.toFloat(),
                steps = (choices.size - 2).coerceAtLeast(0),
                enabled = enabled,
                modifier = Modifier.weight(1f).semantics { contentDescription = label },
            )
            TextButton(onClick = { change(choices[(index + 1).coerceAtMost(choices.lastIndex)]) }, enabled = enabled && index < choices.lastIndex,
                modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).semantics { contentDescription = "$label 变慢" }) { Text("+") }
        }
    }
}

/** 折叠状态下的摘要：只显示当前档位的毫秒数与快慢标注。 */
private fun responseIntervalSummary(draft: LabyrinthStrategySettings): String =
    if (draft.responseIntervalMillis == RESPONSE_INTERVAL_FAST) {
        "${RESPONSE_INTERVAL_FAST} ms（最快）"
    } else {
        "${RESPONSE_INTERVAL_STANDARD} ms（最慢）"
    }
