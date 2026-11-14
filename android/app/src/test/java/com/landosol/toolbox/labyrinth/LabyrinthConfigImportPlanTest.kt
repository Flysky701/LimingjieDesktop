package com.landosol.toolbox.labyrinth

import org.junit.Assert.*
import org.junit.Test

class LabyrinthConfigImportPlanTest {
    private fun strategySummary(
        before: LabyrinthStrategySettings = LabyrinthStrategySettings(),
        after: LabyrinthStrategySettings,
        roleNames: Map<String, String> = emptyMap(),
    ): List<String> {
        val configuration = LabyrinthSharedConfiguration("test", 0, strategy = after)
        return LabyrinthConfigImportPlan(configuration, configuration.modules, null,
            LabyrinthConfigurationValues(before)).changeSummary(roleNames)
    }

    private fun rerollSummary(before: LabyrinthRerollSettings = LabyrinthRerollSettings(), after: LabyrinthRerollSettings): List<String> {
        val configuration = LabyrinthSharedConfiguration("test", 0, reroll = after)
        return LabyrinthConfigImportPlan(configuration, configuration.modules, 7,
            LabyrinthConfigurationValues(rerolls = mapOf(7L to before))).changeSummary()
    }

    @Test fun `equipment and boss modes use readable labels in both directions`() {
        val defaults = LabyrinthStrategySettings()
        val custom = defaults.copy(specialEquipmentMode = LabyrinthSpecialEquipmentMode.AUTO_EQUIP, bossTeamMode = LabyrinthBossTeamMode.SINGLE_TEAM)
        val forward = strategySummary(defaults, custom)
        assertTrue(forward.contains("特别装备模式：关闭 → 自动穿戴"))
        assertTrue(forward.contains("Boss 编组模式：多队编组 → 单队编组"))
        val backward = strategySummary(custom, defaults)
        assertTrue(backward.contains("特别装备模式：自动穿戴 → 关闭"))
        assertTrue(backward.contains("Boss 编组模式：单队编组 → 多队编组"))
        listOf("OFF", "AUTO_EQUIP", "MULTI_TEAM", "SINGLE_TEAM").forEach { token ->
            assertFalse(token, (forward + backward).any { token in it })
        }
    }

    @Test fun `combat kinds and relic priority use names and preserve priority order`() {
        val before = LabyrinthStrategySettings(specialEquipmentKinds = setOf(LabyrinthCombatKind.NORMAL))
        val after = before.copy(specialEquipmentKinds = setOf(LabyrinthCombatKind.BOSS, LabyrinthCombatKind.EX),
            relicMarkPriority = listOf(LabyrinthRelicMark.DEFENSE, LabyrinthRelicMark.ACCELERATION, LabyrinthRelicMark.CRITICAL,
                LabyrinthRelicMark.ENHANCEMENT, LabyrinthRelicMark.DEBUFF))
        val changes = strategySummary(before, after)
        assertTrue(changes.contains("特别装备关卡类型：普通 → EX、BOSS"))
        assertTrue(changes.contains("遗物印记优先级：使用内置顺序 → 守备 > 加速 > 会心 > 强化 > 弱体"))
        assertTrue(strategySummary(after, before).contains("遗物印记优先级：守备 > 加速 > 会心 > 强化 > 弱体 → 使用内置顺序"))
    }

    @Test fun `empty combat kinds remain visibly unselected`() {
        val changes = strategySummary(after = LabyrinthStrategySettings(specialEquipmentKinds = emptySet()))
        assertTrue(changes.contains("特别装备关卡类型：普通、EX、BOSS → 未选择关卡类型"))
    }

    @Test fun `reroll fields resolve guild bosses modes and units`() {
        val settings = LabyrinthRerollSettings(guildId = 3, difficulty = 2, perfectStart = false,
            routeEvaluationMode = LabyrinthRouteEvaluationMode.LEGACY_TEMPLATE, valueAllowance = 2,
            thirdBlockChoice = LabyrinthThirdBlockChoice.EITHER, area3BossIds = emptySet(), area5BossIds = setOf(301701),
            maxAttempts = "25", rerollUntilFound = false, retireExisting = false)
        val changes = rerollSummary(after = settings)
        assertTrue(changes.contains("刷开局默认公会：美食殿堂 → 咲恋救济院"))
        assertTrue(changes.contains("难度：难度 5 → 难度 2"))
        assertTrue(changes.contains("路线评价模式：价值路线 v2 → 旧版固定模板"))
        assertTrue(changes.contains("第三格偏好：必须遗物 → 两者都行"))
        assertTrue(changes.contains("每区允许少拿格数：1 格 → 2 格"))
        assertTrue(changes.contains("区域 3 Boss：厄勒克特拉夫人（简单）、冰霜魔狼（简单） → 不限制"))
        assertTrue(changes.contains("区域 5 Boss：愤怒巨龙（简单） → 炸脖龙（普通）"))
        assertTrue(changes.contains("最大尝试次数：100 次 → 25 次"))
        assertTrue(changes.contains("允许彻底撤退当前开局：开启 → 关闭"))
        LabyrinthThirdBlockChoice.entries.forEach { choice ->
            val before = settings.copy(thirdBlockChoice = LabyrinthThirdBlockChoice.entries.first { it != choice })
            assertTrue(rerollSummary(before, settings.copy(thirdBlockChoice = choice))
                .contains("第三格偏好：${before.thirdBlockChoice.label} → ${choice.label}"))
        }
        assertTrue(rerollSummary(settings, LabyrinthRerollSettings()).contains("路线评价模式：旧版固定模板 → 价值路线 v2"))
    }

    @Test fun `strategy numbers show units and zero baseline meaning`() {
        val changes = strategySummary(after = LabyrinthStrategySettings(playerWeight = 45, secondTeamWeight = 30,
            thirdTeamWeight = 10, damageWeight = 55, survivalWeight = 20, functionWeight = 10, formationWeight = 5,
            cohesionWeight = 9, attributePairingBonusEnabled = false, duplicateRolePenalty = 4, mixedDamagePenalty = 12,
            effectiveCharacterSystemBonus = 5, preferPureBossDamageSystem = false,
            singleBossFallbackToMultiAfterThreeFailures = false, singleBossRetryCountBeforeMulti = 3,
            rerollAfterThreeBattleFailures = false, baselineLastArea = 0, baselineTarget = 3,
            debuffPivotMinimum = 10, debuffPivotTolerance = 1, buyRelics = false, refreshShop = false,
            refreshFromArea = 4, normalWinRate = 80, extremeWinRate = 60, eventCustomizationEnabled = true,
            responseIntervalMillis = RESPONSE_INTERVAL_FAST))
        assertTrue(changes.contains("玩家评分占比：70% → 45%"))
        assertTrue(changes.contains("属性配队增伤：开启 → 关闭"))
        assertTrue(changes.contains("重复纯职能扣分：8 分 → 4 分"))
        assertTrue(changes.contains("基础目标层数：4 层 → 3 层"))
        assertTrue(changes.contains("基础加分截止区域：区域 3 → 不使用前期加分"))
        assertTrue(changes.contains("允许刷新起始区域：区域 5 → 区域 4"))
        assertTrue(changes.contains("普通战估计胜率：95% → 80%"))
        assertTrue(changes.contains("单队重新挑战次数：2 次 → 3 次"))
        assertTrue(changes.contains("响应速度：500 毫秒（最慢） → 300 毫秒（最快）"))
        assertFalse(changes.any { "true" in it || "false" in it || "属性配对增伤" in it })
    }

    @Test fun `event branches use display names even when customization is disabled`() {
        LabyrinthCustomEventCatalog.cards.forEach { card ->
            val first = card.choices.first()
            val last = card.choices.last()
            val before = LabyrinthStrategySettings(eventChoiceOverrides = mapOf(card.runtimeName to first.runtimeName))
            val after = before.copy(eventChoiceOverrides = mapOf(card.runtimeName to last.runtimeName))
            assertTrue(strategySummary(before, after).contains("事件 · ${card.name}：${first.name} → ${last.name}"))
            val default = card.choices.first { it.slot == card.defaultSlot }.name
            assertTrue(strategySummary(after, after.copy(eventChoiceOverrides = emptyMap()))
                .contains("事件 · ${card.name}：${last.name} → 默认（$default）"))
        }
    }

    @Test fun `opening candidates use names without rewriting literal true or false text`() {
        val before = LabyrinthStrategySettings(openingRosters = mapOf(1 to listOf(listOf("100101", "100201"), listOf("100301"), listOf("999999"))))
        val after = before.copy(openingRosters = mapOf(1 to listOf(listOf("100201", "100101"), listOf("100301"), listOf("100401"))))
        val names = mapOf("100101" to "角色true", "100201" to "角色false", "100301" to "角色丙", "100401" to "角色丁")
        val changes = strategySummary(before, after, names)
        assertTrue(changes.contains("开局角色 · 美食殿堂 · 第 1 槽：角色true > 角色false → 角色false > 角色true"))
        assertTrue(changes.contains("开局角色 · 美食殿堂 · 第 3 槽：未知角色（编号 999999） → 角色丁"))
        assertFalse(changes.any { "第 2 槽" in it })
        assertFalse(changes.any { "100101" in it || "100201" in it || "[" in it || "{" in it })
    }

    @Test fun `removing an opening override shows the restored built in choices`() {
        val before = LabyrinthStrategySettings(openingRosters = mapOf(1 to listOf(listOf("100101"), listOf("100201"), listOf("100301"))))
        val changes = strategySummary(before, before.copy(openingRosters = emptyMap()),
            mapOf("100101" to "角色甲", "100201" to "角色乙", "100301" to "角色丙"))
        assertTrue(changes.contains("开局角色 · 美食殿堂：自定义方案 → 内置方案"))
        LabyrinthOpeningRosterCatalog.configs.getValue(1).slots.forEach { slot ->
            slot.candidates.forEach { candidate -> assertTrue(changes.any { candidate.displayName in it }) }
        }
    }

    @Test fun `late candidate changes are not hidden by character truncation`() {
        val ids = (200001..200025).map(Int::toString)
        val names = (ids + "200026").associateWith { "很长的角色显示名称$it" }
        val before = LabyrinthStrategySettings(openingRosters = mapOf(1 to listOf(ids, listOf("100201"), listOf("100301"))))
        val after = before.copy(openingRosters = mapOf(1 to listOf(ids.dropLast(1) + "200026", listOf("100201"), listOf("100301"))))
        val change = strategySummary(before, after, names).first { "第 1 槽" in it }
        assertTrue(change.contains(names.getValue("200025")))
        assertTrue(change.contains(names.getValue("200026")))
        assertFalse(change.contains("省略"))
    }

    @Test fun `set order alone does not appear as a configuration change`() {
        val before = LabyrinthStrategySettings()
        val after = before.copy(specialEquipmentKinds = before.specialEquipmentKinds.reversed().toSet())
        assertFalse(strategySummary(before, after).any { "特别装备关卡类型" in it })
        val reroll = LabyrinthRerollSettings(area3BossIds = LabyrinthRerollOptions.area3Bosses.map { it.unitId }.toSet())
        assertFalse(rerollSummary(reroll, reroll.copy(area3BossIds = reroll.area3BossIds.reversed().toSet()))
            .any { "区域 3 Boss" in it })
    }

    @Test fun `empty score preview explains the clearing count and restored ratings`() {
        val scores = mapOf("100101" to 0, "100201" to 80)
        val values = LabyrinthConfigurationValues(strategy = LabyrinthStrategySettings(personalRoleScores = scores))
        val configuration = LabyrinthSharedConfiguration("test", 0, personalScores = emptyMap())
        val plan = LabyrinthConfigImportPlan(configuration, configuration.modules, null, values)
        assertTrue(plan.clearsPersonalScores)
        val warning = plan.changeSummary().single { "清除" in it }
        // Preserve the destructive-operation meaning, not incidental punctuation.
        assertTrue(warning, warning.contains("2 项自定义评分"))
        assertTrue(warning, warning.contains("恢复") && warning.contains("原评分"))
        assertEquals(mapOf("100101" to 0, "100201" to 80), values.strategy.personalRoleScores)
    }
}
