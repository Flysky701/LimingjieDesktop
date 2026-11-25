package com.landosol.toolbox.labyrinth.vision

import com.landosol.toolbox.vision.PixelImage
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A labyrinth team page can only show roles the run has already acquired.
 *
 * 2026-09-20 bundle 204642: 成员2 came back unidentified for 162 consecutive frames, blocking the
 * whole team plan with "下方当前成员头像未确认（成员2）", while the role it was showing
 * (祈梨（怪盗）) sat in the run's own 32-name joined list. With ~800 icons in the pack, an
 * impossible look-alike only has to score close to the true role to eat the rival margin.
 */
class LabyrinthRosterRestrictedIconMatchTest {
    @Test
    fun `an impossible look-alike no longer vetoes a role that is in the run roster`() {
        val random = Random(20260920)
        val truth = noisyIcon(random, base = 90)
        // A near-twin that is NOT in the roster: close enough to hold the margin down.
        val lookAlike = truth.perturbed(random, amplitude = 6)
        val templates = listOf(
            LabyrinthBattleCharacterTemplate("1216", "祈梨（怪盗）", "31", truth),
            LabyrinthBattleCharacterTemplate("9001", "别的角色", "31", lookAlike),
        ) + (0 until 20).map { index ->
            LabyrinthBattleCharacterTemplate("filler$index", "路人$index", "31", noisyIcon(random, base = 40 + index))
        }
        val matcher = LabyrinthCharacterIconMatcher(templates)
        val frame = framed(truth)
        val rect = EntryPixelRect(0, 0, truth.width, truth.height)

        // Today's behaviour: the impossible twin holds the margin down and the slot is unusable.
        val unrestricted = matcher.match(frame, rect)
        assertFalse("the look-alike must be what blocks this: $unrestricted", unrestricted.trusted)
        assertNull(unrestricted.characterId)

        // With the run's roster, the twin cannot win and cannot veto.
        val restricted = matcher.match(frame, rect, rosterCharacterIds = setOf("1216", "filler3"))
        assertTrue("roster-restricted match must resolve: $restricted", restricted.trusted)
        assertEquals("1216", restricted.characterId)
    }

    @Test
    fun `a face that is not in the roster stays unidentified rather than becoming a wrong role`() {
        // Narrowing the candidate set is only safe while the roster is complete. A stale or
        // partial joined list must not turn an honest "unidentified" into a confident wrong role,
        // because the planner acts on the answer: it would cancel and re-pick real team members.
        val random = Random(4242)
        val onScreen = noisyIcon(random, base = 150)
        val rosterMember = noisyIcon(random, base = 30)
        val templates = listOf(
            LabyrinthBattleCharacterTemplate("2001", "画面上的角色", "31", onScreen),
            LabyrinthBattleCharacterTemplate("3001", "名单里的角色", "31", rosterMember),
        )
        val matcher = LabyrinthCharacterIconMatcher(templates)
        val match = matcher.match(
            framed(onScreen),
            EntryPixelRect(0, 0, onScreen.width, onScreen.height),
            // The role actually on screen is missing from the roster.
            rosterCharacterIds = setOf("3001"),
        )
        assertTrue(
            "must not confidently report the roster member for a different face: $match",
            match.characterId != "3001",
        )
    }

    private fun framed(icon: PixelImage) = PixelImage(icon.width, icon.height, icon.pixels.copyOf())

    private fun noisyIcon(random: Random, base: Int): PixelImage {
        // Must exceed ICON_CROP_TOP + ICON_CROP_HEIGHT or the matcher declines to compare.
        val size = 128
        val pixels = IntArray(size * size) {
            val v = (base + random.nextInt(0, 120)).coerceIn(0, 255)
            (0xff shl 24) or (v shl 16) or (v shl 8) or v
        }
        return PixelImage(size, size, pixels)
    }

    private fun PixelImage.perturbed(random: Random, amplitude: Int): PixelImage {
        val copy = pixels.copyOf()
        for (i in copy.indices) {
            val v = ((copy[i] ushr 16 and 0xff) + random.nextInt(-amplitude, amplitude + 1)).coerceIn(0, 255)
            copy[i] = (0xff shl 24) or (v shl 16) or (v shl 8) or v
        }
        return PixelImage(width, height, copy)
    }
}
