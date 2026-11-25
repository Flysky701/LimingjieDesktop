package com.landosol.toolbox.labyrinth.vision

import com.landosol.toolbox.vision.PixelImage
import com.landosol.toolbox.labyrinth.LabyrinthCharacterAttribute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LabyrinthCharacterRecognitionTest {
    @Test
    fun `matches a known slot and leaves missing slots unknown`() {
        val template = patternedImage(270, 110)
        val framePixels = IntArray(1920 * 1080) { 0xff202020.toInt() }
        template.pixels.forEachIndexed { index, color ->
            val x = index % template.width
            val y = index / template.width
            framePixels[(650 + y) * 1920 + 260 + x] = color
        }

        val matches = LabyrinthCharacterRecognizer(
            LabyrinthCharacterTemplateSet(
                listOf(
                    LabyrinthCharacterTemplate(
                        slotId = "role_reward_left",
                        characterId = "1335",
                        displayName = "咲恋(新年)",
                        image = template,
                    ),
                ),
            ),
        ).recognizeRoleRewardChoices(PixelImage(1920, 1080, framePixels))

        assertEquals(3, matches.size)
        assertEquals("1335", matches[0].characterId)
        assertEquals("咲恋(新年)", matches[0].displayName)
        assertTrue(matches[0].confidence >= 0.55)
        assertEquals(null, matches[1].characterId)
        assertEquals(null, matches[2].characterId)
    }

    @Test
    fun `role reward recognizes a non legacy candidate through the full icon matcher`() {
        val icon = patternedImage(240, 240)
        val framePixels = IntArray(1920 * 1080) { 0xff202020.toInt() }
        paste(icon, framePixels, left = 286, top = 365)
        val recognizer = LabyrinthCharacterRecognizer(
            LabyrinthCharacterIconMatcher(
                listOf(
                    LabyrinthBattleCharacterTemplate(
                        characterId = "1310",
                        displayName = "真步(梦想乐园)",
                        iconVariant = "31",
                        image = icon,
                    ),
                ),
            ),
        )

        val matches = recognizer.recognizeRoleRewardChoices(PixelImage(1920, 1080, framePixels))

        assertEquals("1310", matches.first().characterId)
        assertEquals("真步(梦想乐园)", matches.first().displayName)
        assertTrue(matches.first().trusted)
        val deferred = recognizer.roleRewardSlots(PixelImage(1920, 1080, framePixels))
        assertEquals(3, deferred.size)
        assertTrue(deferred.all { it.characterId == null && !it.trusted })
        assertEquals(matches.first().screenRect, deferred.first().screenRect)
        assertEquals("1310", recognizer.recognizeRoleRewardSlot(
            PixelImage(1920, 1080, framePixels), "role_reward_left",
        ).characterId)
    }

    @Test
    fun `role reward element badge removes a known mismatched attribute rival`() {
        val icon = patternedImage(240, 240)
        val framePixels = IntArray(1920 * 1080) { 0xff202020.toInt() }
        paste(icon, framePixels, left = 286, top = 365)
        // Simulate the green WIND badge in the bottom-right of the reward portrait. The two icon
        // templates are deliberately identical, so identity is impossible without the visible
        // element evidence.
        for (y in 548 until 595) {
            for (x in 474 until 520) {
                framePixels[y * 1920 + x] = 0xff35b85a.toInt()
            }
        }
        val recognizer = LabyrinthCharacterRecognizer(
            LabyrinthCharacterIconMatcher(
                listOf(
                    LabyrinthBattleCharacterTemplate(
                        characterId = "fire-rival",
                        displayName = "错误火属性候选",
                        iconVariant = "31",
                        image = icon,
                        attribute = LabyrinthCharacterAttribute.FIRE,
                    ),
                    LabyrinthBattleCharacterTemplate(
                        characterId = "wind-target",
                        displayName = "正确风属性候选",
                        iconVariant = "31",
                        image = icon,
                        attribute = LabyrinthCharacterAttribute.WIND,
                    ),
                ),
            ),
        )

        val match = recognizer
            .recognizeRoleRewardChoices(PixelImage(1920, 1080, framePixels))
            .first()

        assertEquals("wind-target", match.characterId)
        assertEquals("正确风属性候选", match.displayName)
        assertTrue(match.trusted)
    }

    @Test
    fun `character joined recognizes a non legacy role through the full icon matcher`() {
        val icon = patternedImage(120, 120)
        val rendered = resizeNearest(icon, 90, 90)
        val framePixels = IntArray(1920 * 1080) { 0xff202020.toInt() }
        paste(rendered, framePixels, left = 530, top = 350)
        val recognizer = LabyrinthCharacterRecognizer(
            LabyrinthCharacterIconMatcher(
                listOf(
                    LabyrinthBattleCharacterTemplate(
                        characterId = "1351",
                        displayName = "雪菲(夏日)",
                        iconVariant = "31",
                        image = icon,
                    ),
                ),
            ),
        )

        val matches = recognizer.recognizeJoinedCharacters(PixelImage(1920, 1080, framePixels))

        assertEquals("1351", matches.first().characterId)
        assertEquals("雪菲(夏日)", matches.first().displayName)
        assertTrue(matches.first().trusted)
    }

    private fun paste(image: PixelImage, destination: IntArray, left: Int, top: Int) {
        image.pixels.forEachIndexed { index, color ->
            val x = index % image.width
            val y = index / image.width
            destination[(top + y) * 1920 + left + x] = color
        }
    }

    private fun resizeNearest(image: PixelImage, width: Int, height: Int): PixelImage = PixelImage(
        width = width,
        height = height,
        pixels = IntArray(width * height) { index ->
            val x = index % width
            val y = index / width
            val sourceX = (x.toLong() * image.width / width).toInt().coerceAtMost(image.width - 1)
            val sourceY = (y.toLong() * image.height / height).toInt().coerceAtMost(image.height - 1)
            image.pixels[sourceY * image.width + sourceX]
        },
    )

    private fun patternedImage(width: Int, height: Int): PixelImage = PixelImage(
        width = width,
        height = height,
        pixels = IntArray(width * height) { index ->
            val x = index % width
            val y = index / width
            val red = (x * 7 + y * 3) and 0xff
            val green = (x * 5 + y * 11) and 0xff
            val blue = (x * 13 + y * 17) and 0xff
            (0xff shl 24) or (red shl 16) or (green shl 8) or blue
        },
    )
}
