package com.landosol.toolbox.labyrinth.vision

import com.landosol.toolbox.vision.PixelImage
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LabyrinthExChallengeDetectorTest {
    @Test
    fun `challenge difficulty requires a strong suffix winner`() {
        assertTrue(
            LabyrinthChallengeDifficultyResolver.resolve(0.63, 0.96) ==
                LabyrinthChallengeDifficulty.EXTREME,
        )
        assertTrue(
            LabyrinthChallengeDifficultyResolver.resolve(0.95, 0.61) ==
                LabyrinthChallengeDifficulty.NORMAL,
        )
        assertTrue(
            LabyrinthChallengeDifficultyResolver.resolve(0.74, 0.71) ==
                LabyrinthChallengeDifficulty.UNKNOWN,
        )
        assertTrue(
            LabyrinthChallengeDifficultyResolver.resolve(0.86, 0.82) ==
                LabyrinthChallengeDifficulty.UNKNOWN,
        )
    }

    @Test
    fun `alternative extreme template resolves the green banner without lowering the floor`() {
        // Reproduces the 2026-11-19 report: original extreme template only reaches 0.702.
        assertTrue(
            LabyrinthChallengeDifficultyResolver.resolve(0.332, 0.702) ==
                LabyrinthChallengeDifficulty.UNKNOWN,
        )
        assertTrue(
            LabyrinthChallengeDifficultyResolver.resolve(0.332, 0.702, 0.95) ==
                LabyrinthChallengeDifficulty.EXTREME,
        )
        assertTrue(
            LabyrinthChallengeDifficultyResolver.resolve(0.33, 0.10, 0.90) ==
                LabyrinthChallengeDifficulty.EXTREME,
        )
        // A strong normal title still wins when both extreme templates are weak.
        assertTrue(
            LabyrinthChallengeDifficultyResolver.resolve(0.92, 0.30, 0.60) ==
                LabyrinthChallengeDifficulty.NORMAL,
        )
    }

    @Test
    fun `challenge title must be positively recognized before difficulty is considered resolved`() {
        assertTrue(LabyrinthExEncounterText.isChallengeTitle("战斗格子（极难）"))
        assertTrue(LabyrinthExEncounterText.isChallengeTitle("戰鬥格子（極難）"))
        assertTrue(LabyrinthExEncounterText.isExtremeChallenge("战斗格子（极难）"))
        assertFalse(LabyrinthExEncounterText.isChallengeTitle("极难"))
        assertFalse(LabyrinthExEncounterText.isChallengeTitle(null))
    }

    @Test
    fun `two central info buttons identify special dual target EX but not five monster EX`() {
        val pixels = IntArray(1920 * 1080) { rgb(35, 40, 60) }
        // Screenshot-calibrated special pair: centres approximately (884,599) and (1166,599).
        fill(pixels, EntryPixelRect(856, 570, 56, 56), rgb(190, 205, 235))
        fill(pixels, EntryPixelRect(1138, 570, 56, 56), rgb(190, 205, 235))

        val result = requireNotNull(
            LabyrinthExChallengeDetector().detect(
                PixelImage(1920, 1080, pixels),
                LabyrinthEntryPageState.BATTLE_CHALLENGE,
            ),
        )

        assertTrue(result.specialDualLikely)
        assertFalse(result.multiMonsterLikely)
        assertNotNull(result.specialDualIdentityInfoButtonRect)
    }

    @Test
    fun `lower live dual layout for misora and queen bee is recognized before single target fallback`() {
        val pixels = IntArray(1920 * 1080) { rgb(35, 40, 60) }
        // Live 2026-09 layout from the 美空/黄蜂女王 EX page.
        fill(pixels, EntryPixelRect(852, 604, 56, 56), rgb(190, 205, 235))
        fill(pixels, EntryPixelRect(1152, 604, 56, 56), rgb(190, 205, 235))

        val result = requireNotNull(
            LabyrinthExChallengeDetector().detect(
                PixelImage(1920, 1080, pixels),
                LabyrinthEntryPageState.BATTLE_CHALLENGE,
            ),
        )

        assertTrue(result.specialDualLikely)
        assertFalse(result.multiMonsterLikely)
        val identity = requireNotNull(result.specialDualIdentityInfoButtonRect)
        assertTrue(identity.left >= 1140)
        assertTrue(identity.top >= 595)
    }

    @Test
    fun `five monster layout wins over accidental special evidence`() {
        val pixels = IntArray(1920 * 1080) { rgb(35, 40, 60) }
        listOf(395, 697, 995, 1295).forEach { x ->
            fill(pixels, EntryPixelRect(x, 599, 70, 70), rgb(190, 205, 235))
        }
        // Even if animated artwork happens to make the two special ROIs look bright/blue,
        // a confirmed five-slot structure must never be downgraded to the special layout.
        fill(pixels, EntryPixelRect(856, 570, 56, 56), rgb(190, 205, 235))
        fill(pixels, EntryPixelRect(1138, 570, 56, 56), rgb(190, 205, 235))

        val result = requireNotNull(
            LabyrinthExChallengeDetector().detect(
                PixelImage(1920, 1080, pixels),
                LabyrinthEntryPageState.BATTLE_CHALLENGE,
            ),
        )

        assertTrue(result.multiMonsterLikely)
        assertFalse(result.specialDualLikely)
    }

    private fun fill(pixels: IntArray, rect: EntryPixelRect, color: Int) {
        for (y in rect.top until rect.top + rect.height) {
            for (x in rect.left until rect.left + rect.width) {
                pixels[y * 1920 + x] = color
            }
        }
    }

    private fun rgb(red: Int, green: Int, blue: Int): Int =
        0xff000000.toInt() or (red shl 16) or (green shl 8) or blue
}
