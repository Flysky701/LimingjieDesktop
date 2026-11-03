package com.landosol.toolbox.labyrinth

import com.landosol.toolbox.labyrinth.vision.LabyrinthEntryPageState as Page
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 2026-09-17 live: the final Boss completed the route (BEFORE_SCORE), then the RESULT page
 * appeared. The old NONE-only transition never entered SCORE_RESULT, so RESULT had no plan and
 * the run burned 402 blind animation taps ("RUN_CLEAR_RESULT：暂不自动处理").
 */
class LabyrinthPostBossStageTest {
    @Test
    fun `result page enters score stage from either pre-score stage`() {
        assertEquals(
            LabyrinthPostBossStage.SCORE_RESULT,
            labyrinthPostBossStageOnPage(LabyrinthPostBossStage.NONE, Page.RUN_CLEAR_RESULT),
        )
        assertEquals(
            LabyrinthPostBossStage.SCORE_RESULT,
            labyrinthPostBossStageOnPage(LabyrinthPostBossStage.BEFORE_SCORE, Page.RUN_CLEAR_RESULT),
        )
    }

    @Test
    fun `result page still visible after the first close tap returns to the score stage`() {
        assertEquals(
            LabyrinthPostBossStage.SCORE_RESULT,
            labyrinthPostBossStageOnPage(LabyrinthPostBossStage.CHEST_SEQUENCE, Page.RUN_CLEAR_RESULT),
        )
    }

    @Test
    fun `result page never regresses a stage past the chest result`() {
        for (stage in listOf(
            LabyrinthPostBossStage.SCORE_RESULT,
            LabyrinthPostBossStage.FINAL_ITEM_REWARD,
            LabyrinthPostBossStage.WAITING_FOR_DAWN_HOME,
        )) {
            assertEquals(stage.name, stage, labyrinthPostBossStageOnPage(stage, Page.RUN_CLEAR_RESULT))
        }
    }

    @Test
    fun `recognized chest pages recover from a lingering score frame`() {
        for (page in listOf(Page.RUN_CLEAR_CHEST_ANIMATION, Page.RUN_CLEAR_CHEST_RESULT)) {
            assertEquals(page.name, LabyrinthPostBossStage.CHEST_SEQUENCE,
                labyrinthPostBossStageOnPage(LabyrinthPostBossStage.SCORE_RESULT, page))
        }
    }

    @Test
    fun `final item advances after score even when chest frames were not observed`() {
        for (stage in listOf(LabyrinthPostBossStage.SCORE_RESULT, LabyrinthPostBossStage.CHEST_SEQUENCE)) {
            assertEquals(stage.name, LabyrinthPostBossStage.FINAL_ITEM_REWARD,
                labyrinthPostBossStageOnPage(stage, Page.ITEM_REWARD))
        }
    }

    @Test
    fun `transitions and ordinary rewards do not infer completion or regress final progress`() {
        for (page in listOf(Page.UNKNOWN, Page.NODE_SELECTION, Page.DAWN_REALM_HOME_IDLE, Page.BATTLE_RESULT)) {
            assertEquals(page.name, LabyrinthPostBossStage.SCORE_RESULT,
                labyrinthPostBossStageOnPage(LabyrinthPostBossStage.SCORE_RESULT, page))
        }
        for (stage in listOf(LabyrinthPostBossStage.NONE, LabyrinthPostBossStage.BEFORE_SCORE,
            LabyrinthPostBossStage.FINAL_ITEM_REWARD, LabyrinthPostBossStage.WAITING_FOR_DAWN_HOME)) {
            for (page in listOf(Page.RUN_CLEAR_CHEST_ANIMATION, Page.RUN_CLEAR_CHEST_RESULT, Page.ITEM_REWARD)) {
                assertEquals("$stage on $page", stage, labyrinthPostBossStageOnPage(stage, page))
            }
        }
    }

    @Test
    fun `chest result dialog falls back to the bottom-centre confirm button`() {
        assertEquals(LabyrinthFallbackTap.BOTTOM_CENTER, labyrinthChestResultFallbackTap())
        // 1178x663 screenshot: 确认 centre (588,588) → 1080p (958,958); the tap must sit on it.
        val rect = labyrinthChestResultFallbackTap().rect(1920, 1080)
        val cx = rect.left + rect.width / 2
        val cy = rect.top + rect.height / 2
        assert(cx in 900..1020 && cy in 913..1003) { "tap ($cx,$cy) misses the 确认 button" }
    }
}
