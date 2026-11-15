package com.landosol.toolbox.ui

import com.landosol.toolbox.labyrinth.batch.LabyrinthBatchGoalMode
import com.landosol.toolbox.ui.labyrinth.decodeBatchTargetCounts
import com.landosol.toolbox.ui.labyrinth.decodeBatchTargetModes
import com.landosol.toolbox.ui.labyrinth.encodeBatchTargetCounts
import com.landosol.toolbox.ui.labyrinth.encodeBatchTargetModes
import com.landosol.toolbox.ui.labyrinth.StrategyLeaveDecision
import com.landosol.toolbox.ui.labyrinth.strategyLeaveDecision
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResponsiveLayoutTest {
    @Test
    fun `short landscape uses compact typography and side navigation`() {
        assertTrue(isCompactLandscape(widthDp = 960, heightDp = 540))
        assertTrue(shouldUseNavigationRail(widthDp = 960, heightDp = 540))
    }

    @Test
    fun `portrait keeps standard typography and bottom navigation`() {
        assertFalse(isCompactLandscape(widthDp = 411, heightDp = 891))
        assertFalse(shouldUseNavigationRail(widthDp = 411, heightDp = 891))
    }

    @Test
    fun `tall landscape keeps standard typography but uses side navigation`() {
        assertFalse(isCompactLandscape(widthDp = 1280, heightDp = 800))
        assertTrue(shouldUseNavigationRail(widthDp = 1280, heightDp = 800))
    }

    @Test
    fun `strategy departure stays during saving and confirms unsaved drafts`() {
        assertEquals(StrategyLeaveDecision.STAY, strategyLeaveDecision(saving = true, hasChanges = true))
        assertEquals(StrategyLeaveDecision.STAY, strategyLeaveDecision(saving = true, hasChanges = false))
        assertEquals(StrategyLeaveDecision.CONFIRM_DISCARD, strategyLeaveDecision(saving = false, hasChanges = true))
        assertEquals(StrategyLeaveDecision.LEAVE, strategyLeaveDecision(saving = false, hasChanges = false))
    }

    @Test
    fun `strategy is second destination and both navigation layouts use the same leave guard`() {
        val app = mainSource("LandosolToolboxApp.kt")
        val destinations = Regex("""TopLevelDestination\(AppScreen\.(\w+),""")
            .findAll(app).map { it.groupValues[1] }.toList()
        assertEquals(listOf("Labyrinth", "Strategies", "Accounts", "Permissions"), destinations)
        assertEquals(2, Regex("""onClick = \{ requestNavigation\(destination.screen\) \},""").findAll(app).count())
        assertFalse(app.contains("screenName = destination.screen.name"))
        assertTrue(app.contains("strategyLeaveHandler?.invoke(navigate)"))
        assertTrue(app.contains("if (target != screen)"))
        assertTrue(app.contains("registerLeaveHandler = registerStrategyLeaveHandler"))
    }

    @Test
    fun `strategy is embedded but preserves child editors explicit save and system back protection`() {
        val screen = mainSource("labyrinth/LabyrinthStrategySettingsScreen.kt")
        assertFalse(screen.contains("Dialog(onDismissRequest = close"))
        assertTrue(screen.contains("BackHandler(enabled = !showRoleRatings"))
        assertTrue(screen.contains("rememberUpdatedState(requestLeave)"))
        assertTrue(screen.contains("onDispose { registerLeaveHandler(null) }"))
        assertTrue(screen.contains("currentRequestLeave(leave)"))
        assertTrue(screen.contains("pendingLeave = leave"))
        assertTrue(screen.contains("leave?.invoke()"))
        assertTrue(screen.contains("pendingLeave = null"))
        assertTrue(screen.contains("LabyrinthRoleRatingsScreen("))
        assertTrue(screen.contains("LabyrinthOpeningRosterScreen("))
        assertTrue(screen.contains("StrategySaver"))
        assertTrue(screen.contains("draft.canSaveDraft(saved, saving)"))
        assertTrue(screen.contains("EventCustomizationEditor("))
        assertTrue(screen.contains("draft = LabyrinthStrategySettings()"))
        assertTrue(screen.contains("所有账号共用 · 下次启动生效"))
        assertTrue(Regex("""CompactTopBar\(\s*title\s*=\s*"策略设置",[^)]*\bactions\s*=""")
            .containsMatchIn(screen))
    }

    @Test
    fun `battle settings is a peer category and switches appear only in its selected content`() {
        val screen = mainSource("labyrinth/LabyrinthStrategySettingsScreen.kt")
        val categoryList = screen.substringAfter("private val strategySections = listOf(").substringBefore(")")
        val categories = Regex("\"([^\"]+)\"").findAll(categoryList).map { it.groupValues[1] }.toList()
        assertTrue(categories.containsAll(listOf("评分", "选人", "开局", "战斗设置")))
        assertEquals(1, categories.count { it == "战斗设置" })
        assertEquals(2, Regex("strategySections.forEach").findAll(screen).count())
        val beforeContent = screen.substringBefore("when (section) {")
        assertFalse(beforeContent.contains("draft.frontlineSurvivalGateEnabled"))
        assertFalse(beforeContent.contains("draft.normalBattleSkipTeamSelectionEnabled"))
        val battleContent = screen.substringAfter("\"战斗设置\" -> StrategyPanel")
            .substringBefore("\"Boss编组\" -> StrategyPanel")
        assertTrue(battleContent.contains("前排生存门槛"))
        assertTrue(battleContent.contains("普战跳编队"))
        assertTrue(battleContent.contains("draft = draft.copy(frontlineSurvivalGateEnabled = it)"))
        assertTrue(battleContent.contains("draft = draft.copy(normalBattleSkipTeamSelectionEnabled = it)"))
        assertTrue(battleContent.contains("默认关闭 · 保存后下次启动生效"))
    }

    @Test
    fun `home removes only the strategy action and retains reroll settings`() {
        val home = mainSource("labyrinth/LabyrinthScreen.kt")
        assertFalse(home.contains("onOpenStrategies"))
        assertTrue(home.contains("onClick = { showRerollSettings = true }"))
        assertTrue(home.contains("刷开局设置"))
    }

    @Test
    fun `home batch targets round trip without clearing numbers or attempt modes`() {
        val counts = mapOf(1 to "12", 2 to "", 3 to "07")
        val modes = mapOf(1 to LabyrinthBatchGoalMode.ATTEMPTS, 2 to LabyrinthBatchGoalMode.CLEARS)
        assertEquals(counts, decodeBatchTargetCounts(encodeBatchTargetCounts(counts)))
        assertEquals(modes, decodeBatchTargetModes(encodeBatchTargetModes(modes)))
        val app = mainSource("LandosolToolboxApp.kt")
        val home = mainSource("labyrinth/LabyrinthScreen.kt")
        assertTrue(app.contains("homeStateHolder.SaveableStateProvider(AppScreen.Labyrinth.name)"))
        assertTrue(home.contains("rememberSaveable(guildOptions, stateSaver = BatchTargetCountsSaver)"))
        assertTrue(home.contains("rememberSaveable(guildOptions, stateSaver = BatchTargetModesSaver)"))
    }

    private fun mainSource(relative: String): String {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it, "android/app/src/main/java").isDirectory || File(it, "app/src/main/java").isDirectory }
        val base = if (File(root, "android").isDirectory) File(root, "android") else root
        return File(base, "app/src/main/java/com/landosol/toolbox/ui/$relative").readText()
    }
}
