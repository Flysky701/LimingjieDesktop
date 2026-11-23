package com.landosol.toolbox.labyrinth

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class LabyrinthFrontlineModeSettingsTest {
    @Test fun `defaults and missing mode resolve to stable without rewriting settings`() {
        val defaults = LabyrinthStrategySettings()
        assertNull(defaults.frontlineSurvivalMode)
        assertFalse(defaults.frontlineSurvivalGateEnabled)
        assertEquals(LabyrinthFrontlineSurvivalMode.STABLE, defaults.effectiveFrontlineSurvivalMode)
        listOf(null, "{}", "{\"playerWeight\":50,\"survivalWeight\":31}").forEach { raw ->
            val decoded = LabyrinthStrategySettingsCodec.decode(raw)
            assertEquals(LabyrinthFrontlineSurvivalMode.STABLE, decoded.effectiveFrontlineSurvivalMode)
            assertNull(decoded.frontlineSurvivalMode)
            if (raw?.contains("playerWeight") == true) {
                assertEquals(50, decoded.playerWeight)
                assertEquals(31, decoded.survivalWeight)
            }
        }
    }

    @Test fun `legacy booleans and explicit null mode preserve both old behaviors`() {
        listOf(false, true).forEach { gate ->
            val expected = if (gate) LabyrinthFrontlineSurvivalMode.CAUTIOUS else LabyrinthFrontlineSurvivalMode.STABLE
            listOf(
                "{\"frontlineSurvivalGateEnabled\":$gate,\"survivalWeight\":37}",
                "{\"frontlineSurvivalGateEnabled\":$gate,\"frontlineSurvivalMode\":null,\"survivalWeight\":37}",
            ).forEach { raw ->
                val decoded = LabyrinthStrategySettingsCodec.decode(raw)
                assertNull(decoded.frontlineSurvivalMode)
                assertEquals(gate, decoded.frontlineSurvivalGateEnabled)
                assertEquals(expected, decoded.effectiveFrontlineSurvivalMode)
                assertEquals(37, decoded.survivalWeight)
                assertEquals(decoded, LabyrinthStrategySettingsCodec.decode(LabyrinthStrategySettingsCodec.encode(decoded)))
            }
        }
    }

    @Test fun `old named and positional constructor and copy booleans stay source compatible`() {
        val named = LabyrinthStrategySettings(frontlineSurvivalGateEnabled = true)
        assertEquals(LabyrinthFrontlineSurvivalMode.CAUTIOUS, named.effectiveFrontlineSurvivalMode)
        assertEquals(LabyrinthFrontlineSurvivalMode.STABLE, named.copy(frontlineSurvivalGateEnabled = false).effectiveFrontlineSurvivalMode)
        val positional = LabyrinthStrategySettings(70, 50, 23, 13, 4, 10, true, 8, 15, 8, 35, 20, true)
        assertEquals(named, positional)
        val copied = positional.copy(70, 50, 23, 13, 4, 10, true, 8, 15, 8, 35, 20, false)
        assertEquals(LabyrinthStrategySettings(), copied)
        assertEquals(LabyrinthFrontlineSurvivalMode.STABLE, copied.effectiveFrontlineSurvivalMode)
    }

    @Test fun `explicit mode wins over either legacy boolean in source and JSON`() {
        LabyrinthFrontlineSurvivalMode.entries.forEach { mode ->
            listOf(false, true).forEach { gate ->
                val settings = LabyrinthStrategySettings(frontlineSurvivalGateEnabled = gate, frontlineSurvivalMode = mode)
                assertEquals(mode, settings.effectiveFrontlineSurvivalMode)
                val decoded = LabyrinthStrategySettingsCodec.decode(
                    "{\"frontlineSurvivalGateEnabled\":$gate,\"frontlineSurvivalMode\":\"${mode.name}\",\"damageWeight\":61,\"survivalWeight\":32}",
                )
                assertEquals(mode, decoded.frontlineSurvivalMode)
                assertEquals(mode, decoded.effectiveFrontlineSurvivalMode)
                assertEquals(gate, decoded.frontlineSurvivalGateEnabled)
                assertEquals(61, decoded.damageWeight)
                assertEquals(32, decoded.survivalWeight)
                assertEquals(settings, LabyrinthStrategySettingsCodec.decode(LabyrinthStrategySettingsCodec.encode(settings)))
            }
        }
    }

    @Test fun `new mode only JSON does not need the old boolean`() {
        LabyrinthFrontlineSurvivalMode.entries.forEach { mode ->
            val decoded = LabyrinthStrategySettingsCodec.decode(
                "{\"frontlineSurvivalMode\":\"${mode.name}\",\"survivalWeight\":29,\"buyRelics\":false}",
            )
            assertEquals(mode, decoded.frontlineSurvivalMode)
            assertEquals(mode, decoded.effectiveFrontlineSurvivalMode)
            assertFalse(decoded.frontlineSurvivalGateEnabled)
            assertEquals(29, decoded.survivalWeight)
            assertFalse(decoded.buyRelics)
        }
    }

    @Test fun `three choices have the sealed order labels gate and descriptions`() {
        val modes = LabyrinthFrontlineSurvivalMode.entries
        assertEquals(listOf(
            LabyrinthFrontlineSurvivalMode.VERY_AGGRESSIVE,
            LabyrinthFrontlineSurvivalMode.STABLE,
            LabyrinthFrontlineSurvivalMode.CAUTIOUS,
        ), modes.toList())
        assertEquals(listOf("非常激进", "稳定", "贪生怕死"), modes.map { it.label })
        assertEquals(listOf(false, false, true), modes.map { it.requiresFrontlineSurvivalGate })
        modes.forEach { assertTrue(it.summary.isNotBlank()); assertTrue(it.description.isNotBlank()) }
        assertEquals(
            "配队不选择任何掩护者（坦克），优先输出；非掩护者不足 5 人时，本轮自动按「稳定」编队",
            LabyrinthFrontlineSurvivalMode.VERY_AGGRESSIVE.description,
        )
        val stable = LabyrinthFrontlineSurvivalMode.STABLE.description
        listOf("生存评分", "开战安全检查", "不强制前排").forEach { assertTrue(stable.contains(it)) }
        val cautious = LabyrinthFrontlineSurvivalMode.CAUTIOUS.description
        listOf("55", "40", "无T兜底", "补刀例外", "不保证必胜").forEach { assertTrue(cautious.contains(it)) }
    }

    @Test fun `helper synchronizes both fields and both persistence paths`() {
        val original = LabyrinthStrategySettings(frontlineSurvivalGateEnabled = true, survivalWeight = 41, buyRelics = false)
        LabyrinthFrontlineSurvivalMode.entries.forEach { mode ->
            val selected = original.withFrontlineSurvivalMode(mode)
            assertEquals(original.copy(frontlineSurvivalMode = mode, frontlineSurvivalGateEnabled = mode.requiresFrontlineSurvivalGate), selected)
            assertEquals(mode, selected.effectiveFrontlineSurvivalMode)
            assertEquals(mode.requiresFrontlineSurvivalGate, selected.frontlineSurvivalGateEnabled)
            val encoded = LabyrinthStrategySettingsCodec.encode(selected)
            val payload = Json.parseToJsonElement(encoded).jsonObject
            assertEquals(mode.name, payload.getValue("frontlineSurvivalMode").jsonPrimitive.content)
            assertEquals(mode.requiresFrontlineSurvivalGate.toString(), payload.getValue("frontlineSurvivalGateEnabled").jsonPrimitive.content)
            assertEquals(selected, LabyrinthStrategySettingsCodec.decode(encoded))
            assertEquals(selected, LabyrinthStrategySettingsCodec.decodeDraft(LabyrinthStrategySettingsCodec.encodeDraft(selected)))
            assertTrue(selected.canSaveDraft(original, saving = false))
            assertFalse(selected.canSaveDraft(original, saving = true))
        }
        assertNull(original.frontlineSurvivalMode)
        assertTrue(original.frontlineSurvivalGateEnabled)
    }

    @Test fun `changing mode does not change weights opening shop or scoring safety`() {
        val original = productionDocument()
        val settings = LabyrinthStrategySettings(
            playerWeight = 44, damageWeight = 62, survivalWeight = 31, functionWeight = 11,
            formationWeight = 5, cohesionWeight = 9, baselineLastArea = 2, baselineTarget = 3,
            buyRelics = false, refreshShop = false, refreshFromArea = 4,
            normalBattleSkipTeamSelectionEnabled = true, bossTeamMode = LabyrinthBossTeamMode.SINGLE_TEAM,
        )
        val applied = settings.applyTo(original)
        LabyrinthFrontlineSurvivalMode.entries.forEach { mode ->
            val selected = settings.withFrontlineSurvivalMode(mode)
            assertEquals(settings, selected.copy(frontlineSurvivalMode = null, frontlineSurvivalGateEnabled = false))
            assertEquals(31, selected.survivalWeight)
            assertEquals(applied, selected.applyTo(original))
            assertEquals(original.scoring.minimumReliableVanguard, selected.applyTo(original).scoring.minimumReliableVanguard, 0.0)
        }
    }

    @Test fun `UI uses shared effective mode order text and synchronized selection with collapse`() {
        val screen = mainSource("ui/labyrinth/LabyrinthStrategySettingsScreen.kt")
        val editor = screen.substringAfter("private fun FrontlineSurvivalModeEditor(").substringBefore("private fun EventCustomizationEditor(")
        assertTrue(editor.contains("var expanded by rememberSaveable { mutableStateOf(false) }"))
        assertTrue(editor.contains("settings.effectiveFrontlineSurvivalMode"))
        assertTrue(editor.contains("${"$"}{mode.label}"))
        assertTrue(editor.contains("summary = mode.summary"))
        assertTrue(editor.contains("LabyrinthFrontlineSurvivalMode.entries.forEach"))
        assertTrue(editor.contains("StrategyRadioChoice("))
        assertTrue(editor.contains("label = choice.label"))
        assertTrue(editor.contains("detail = choice.description"))
        assertTrue(editor.contains("selected = mode == choice"))
        assertTrue(editor.contains("enabled = !saving"))
        assertTrue(editor.contains("onChange(settings.withFrontlineSurvivalMode(choice))\n                    expanded = false"))
        assertFalse(screen.contains("draft.frontlineSurvivalGateEnabled"))
        assertTrue(screen.contains("clickable(enabled = enabled)"))
        assertTrue(screen.contains(".selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = select)"))
        assertTrue(screen.contains("默认稳定 · 保存后下次任务生效"))
        assertTrue(screen.contains("已开启普战跳编队：沿用的现队即使含掩护者，也不会重新筛选。"))
        assertTrue(screen.contains("仅普通战首次尝试沿用已确认的满5人队伍，跳过重新选人；关闭按推荐阵容编组，不是跳过战斗。"))
    }

    private fun androidRoot(): File {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it, "android/app/src/main/java").isDirectory || File(it, "app/src/main/java").isDirectory }
        return if (File(root, "android").isDirectory) File(root, "android") else root
    }

    private fun mainSource(relative: String): String =
        File(androidRoot(), "app/src/main/java/com/landosol/toolbox/$relative").readText()

    private fun productionDocument(): LabyrinthRoleDecisionDocument {
        val raw = File(androidRoot(), "app/src/main/assets/resource-packs/cn-bilibili/labyrinth-role-decision.json").readText()
        return (LabyrinthRoleDecisionDataParser.parse(raw) as LabyrinthRoleDecisionDataResult.Ready).runtime.document
    }
}
