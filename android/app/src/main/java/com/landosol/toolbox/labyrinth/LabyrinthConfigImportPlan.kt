package com.landosol.toolbox.labyrinth

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** A confirmation binds the selected modules and target to the exact saved values being previewed. */
data class LabyrinthConfigImportPlan(
    val configuration: LabyrinthSharedConfiguration,
    val selected: Set<LabyrinthConfigModule>,
    val targetAccountId: Long?,
    val expected: LabyrinthConfigurationValues,
) {
    val clearsPersonalScores: Boolean get() = LabyrinthConfigModule.PERSONAL_SCORES in selected && configuration.personalScores.isNullOrEmpty()
    val clearScoresWarning: String get() = "将清除 ${expected.strategy.personalRoleScores.size} 项自定义评分，恢复使用原评分"

    fun changeSummary(roleNames: Map<String, String> = emptyMap()): List<String> = buildList {
        val next = configuration.replacement(expected, selected, targetAccountId, confirmEmptyScores = true)
        if (LabyrinthConfigModule.STRATEGY in selected) {
            add("全局策略（所有账号）")
            addChanges(strategyFields(expected.strategy), strategyFields(next.strategy), roleNames)
        }
        if (LabyrinthConfigModule.PERSONAL_SCORES in selected) {
            val before = expected.strategy.personalRoleScores
            val after = next.strategy.personalRoleScores
            val added = after.keys.count { it !in before }
            val changed = after.keys.count { it in before && after[it] != before[it] }
            val removed = before.keys.count { it !in after }
            add("个人评分（所有账号）")
            if (clearsPersonalScores) add(clearScoresWarning)
            else add("新增 $added 项、修改 $changed 项、移除 $removed 项" + if (removed > 0) "、移除项恢复原评分" else "")
        }
        if (LabyrinthConfigModule.REROLL in selected) {
            add("刷开局设置（所选账号）")
            addChanges(rerollFields(expected.rerolls[targetAccountId] ?: LabyrinthRerollSettings()), rerollFields(next.rerolls.getValue(targetAccountId!!)), roleNames)
        }
        add("仅替换所选模块")
    }

    private fun MutableList<String>.addChanges(before: JsonObject, after: JsonObject, roleNames: Map<String, String>) {
        val changes = before.keys.filter { key ->
            if (key in SET_FIELDS) before.getValue(key).jsonArray.toSet() != after.getValue(key).jsonArray.toSet()
            else before[key] != after[key]
        }
        if (changes.isEmpty()) add("与当前配置相同")
        changes.forEach { key ->
            val old = before.getValue(key)
            val new = after.getValue(key)
            when (key) {
                "eventChoiceOverrides" -> addEventChanges(old.jsonObject, new.jsonObject)
                "openingRosters" -> addRosterChanges(old.jsonObject, new.jsonObject, roleNames)
                "customNodePlan" -> addCustomNodeChanges(old.jsonObject, new.jsonObject)
                else -> add("${FIELD_LABELS.getValue(key)}：${display(key, old)} → ${display(key, new)}")
            }
        }
    }

    private fun MutableList<String>.addCustomNodeChanges(before: JsonObject, after: JsonObject) {
        val labels = linkedMapOf(
            "area1Column3" to "区域1第3列", "area1Column5" to "区域1第5列",
            "area2Columns2And6" to "区域2第2、6列", "area3Column4" to "区域3第4列",
            "area4Column2" to "区域4第2列", "area4Column7" to "区域4第7列",
            "thirdBlockChoice" to "区域3/5第3列",
        )
        labels.forEach { (key, label) ->
            if (before[key] != after[key]) {
                fun name(value: JsonElement): String = if (key == "thirdBlockChoice") {
                    LabyrinthThirdBlockChoice.valueOf(value.jsonPrimitive.content).label
                } else LabyrinthCustomNodePlan.labelOf(value.jsonPrimitive.int)
                add("自定义 · $label：${name(before.getValue(key))} → ${name(after.getValue(key))}")
            }
        }
    }

    private fun MutableList<String>.addEventChanges(before: JsonObject, after: JsonObject) {
        (before.keys + after.keys).filter { before[it] != after[it] }.forEach { event ->
            val card = LabyrinthCustomEventCatalog.cards.firstOrNull { it.runtimeName == event }
            fun choice(value: JsonElement?): String {
                if (value == null) {
                    val default = card?.let { event -> event.choices.firstOrNull { it.slot == event.defaultSlot }?.name }
                    return default?.let { "默认（$it）" } ?: "默认选择"
                }
                val runtimeName = value.jsonPrimitive.content
                return card?.choices?.firstOrNull { it.runtimeName == runtimeName }?.name ?: runtimeName
            }
            add("事件 · ${card?.name ?: event}：${choice(before[event])} → ${choice(after[event])}")
        }
    }

    private fun MutableList<String>.addRosterChanges(before: JsonObject, after: JsonObject, roleNames: Map<String, String>) {
        (before.keys + after.keys).sortedBy(String::toInt).filter { before[it] != after[it] }.forEach { guild ->
            val guildId = guild.toInt()
            val old = before[guild]
            val new = after[guild]
            val label = "开局角色 · ${guildName(guildId)}"
            if (old == null || new == null) {
                add("$label：${if (old == null) "内置方案" else "自定义方案"} → ${if (new == null) "内置方案" else "自定义方案"}")
            }
            fun slots(value: JsonElement?): List<List<String>> = value?.jsonArray?.map { slot ->
                slot.jsonArray.map { it.jsonPrimitive.content }
            } ?: LabyrinthOpeningRosterCatalog.configs.getValue(guildId).slots.map { slot -> slot.candidates.map { it.characterId } }
            fun names(candidates: List<String>): String = candidates.joinToString(" > ") { id ->
                roleNames[id] ?: DEFAULT_ROLE_NAMES[id] ?: "未知角色（编号 $id）"
            }
            val oldSlots = slots(old)
            val newSlots = slots(new)
            oldSlots.indices.filter { oldSlots[it] != newSlots[it] }.forEach { index ->
                add("$label · 第 ${index + 1} 槽：${names(oldSlots[index])} → ${names(newSlots[index])}")
            }
        }
    }

    private fun display(key: String, value: JsonElement): String = when (key) {
        "bossTeamMode" -> when (LabyrinthBossTeamMode.valueOf(value.jsonPrimitive.content)) {
            LabyrinthBossTeamMode.MULTI_TEAM -> "多队编组"
            LabyrinthBossTeamMode.SINGLE_TEAM -> "单队编组"
        }
        "specialEquipmentMode" -> when (LabyrinthSpecialEquipmentMode.valueOf(value.jsonPrimitive.content)) {
            LabyrinthSpecialEquipmentMode.OFF -> "关闭"
            LabyrinthSpecialEquipmentMode.AUTO_EQUIP -> "自动穿戴"
        }
        "specialEquipmentKinds" -> value.jsonArray.map { LabyrinthCombatKind.valueOf(it.jsonPrimitive.content) }
            .sortedBy { it.ordinal }.joinToString("、") {
                when (it) {
                    LabyrinthCombatKind.NORMAL -> "普通"
                    LabyrinthCombatKind.EX -> "EX"
                    LabyrinthCombatKind.BOSS -> "BOSS"
                }
            }.ifEmpty { "未选择关卡类型" }
        "relicMarkPriority" -> value.jsonArray.joinToString(" > ") { LabyrinthRelicMark.valueOf(it.jsonPrimitive.content).label }
            .ifEmpty { "使用内置顺序" }
        "routeEvaluationMode" -> LabyrinthRouteEvaluationMode.valueOf(value.jsonPrimitive.content).label
        "thirdBlockChoice" -> LabyrinthThirdBlockChoice.valueOf(value.jsonPrimitive.content).label
        "guildId" -> guildName(value.jsonPrimitive.int)
        "area3BossIds", "area5BossIds" -> {
            val ids = value.jsonArray.map { it.jsonPrimitive.int }.toSet()
            val options = if (key == "area3BossIds") LabyrinthRerollOptions.area3Bosses else LabyrinthRerollOptions.area5Bosses
            if (ids.isEmpty()) "不限制" else options.filter { it.unitId in ids }.joinToString("、") { "${it.name}（${it.difficulty.label}）" }
        }
        "responseIntervalMillis" -> when (value.jsonPrimitive.int) {
            RESPONSE_INTERVAL_FAST -> "$RESPONSE_INTERVAL_FAST 毫秒（最快）"
            RESPONSE_INTERVAL_STANDARD -> "$RESPONSE_INTERVAL_STANDARD 毫秒（最慢）"
            else -> "${value.jsonPrimitive.content} 毫秒"
        }
        "playerWeight", "secondTeamWeight", "thirdTeamWeight", "normalWinRate", "extremeWinRate" -> "${value.jsonPrimitive.content}%"
        "duplicateRolePenalty", "mixedDamagePenalty", "effectiveCharacterSystemBonus" -> "${value.jsonPrimitive.content} 分"
        "baselineTarget", "debuffPivotMinimum", "debuffPivotTolerance" -> "${value.jsonPrimitive.content} 层"
        "baselineLastArea" -> if (value.jsonPrimitive.int == 0) "不使用前期加分" else "区域 ${value.jsonPrimitive.content}"
        "refreshFromArea" -> "区域 ${value.jsonPrimitive.content}"
        "singleBossRetryCountBeforeMulti", "maxAttempts" -> "${value.jsonPrimitive.content} 次"
        "difficulty" -> "难度 ${value.jsonPrimitive.content}"
        "valueAllowance" -> "${value.jsonPrimitive.content} 格"
        else -> when (value.jsonPrimitive.booleanOrNull) {
            true -> "开启"
            false -> "关闭"
            null -> value.jsonPrimitive.content
        }
    }

    companion object {
        private val json = Json { encodeDefaults = true }
        private fun strategyFields(value: LabyrinthStrategySettings) = JsonObject(json.encodeToJsonElement(value).jsonObject - "personalRoleScores")
        private fun rerollFields(value: LabyrinthRerollSettings) = json.encodeToJsonElement(value).jsonObject
        private fun guildName(id: Int) = LabyrinthRerollOptions.guilds.first { it.guildId == id }.name
        private val SET_FIELDS = setOf("specialEquipmentKinds", "area3BossIds", "area5BossIds")
        private val DEFAULT_ROLE_NAMES = LabyrinthOpeningRosterCatalog.configs.values.flatMap { config ->
            config.slots.flatMap { it.candidates } + config.grantedCharacters
        }.associate { it.characterId to it.displayName }
        private val FIELD_LABELS = mapOf(
            "guildId" to "刷开局默认公会", "playerWeight" to "玩家评分占比", "damageWeight" to "伤害权重", "survivalWeight" to "生存权重",
            "functionWeight" to "功能权重", "formationWeight" to "阵型权重", "cohesionWeight" to "协同权重",
            "attributePairingBonusEnabled" to "属性配队增伤", "duplicateRolePenalty" to "重复纯职能扣分",
            "mixedDamagePenalty" to "物法混编扣分", "effectiveCharacterSystemBonus" to "有效效果角色加分",
            "secondTeamWeight" to "第二队权重", "thirdTeamWeight" to "第三队权重", "bossTeamMode" to "Boss 编组模式",
            "preferPureBossDamageSystem" to "Boss 纯物理/法术偏好", "singleBossFallbackToMultiAfterThreeFailures" to "单队失败后切换多队",
            "singleBossRetryCountBeforeMulti" to "单队重新挑战次数", "rerollAfterThreeBattleFailures" to "三次战败后自动重刷",
            "baselineLastArea" to "基础加分截止区域", "baselineTarget" to "基础目标层数", "debuffPivotMinimum" to "转追削弱最低层数",
            "debuffPivotTolerance" to "削弱落后容忍量", "buyRelics" to "商店购买遗物", "refreshShop" to "商店刷新",
            "refreshFromArea" to "允许刷新起始区域", "normalWinRate" to "普通战估计胜率", "extremeWinRate" to "极难战估计胜率",
            "eventCustomizationEnabled" to "事件自定义开关", "eventChoiceOverrides" to "事件分支", "openingRosters" to "开局三槽候选顺序",
            "relicMarkPriority" to "遗物印记优先级", "specialEquipmentMode" to "特别装备模式", "specialEquipmentKinds" to "特别装备关卡类型",
            "responseIntervalMillis" to "响应速度", "difficulty" to "难度", "perfectStart" to "完美开局",
            "routeEvaluationMode" to "路线评价模式", "valueAllowance" to "每区允许少拿格数", "thirdBlockChoice" to "第三格偏好",
            "area3BossIds" to "区域 3 Boss", "area5BossIds" to "区域 5 Boss", "maxAttempts" to "最大尝试次数",
            "rerollUntilFound" to "刷到出", "retireExisting" to "允许彻底撤退当前开局",
        )
    }
}
