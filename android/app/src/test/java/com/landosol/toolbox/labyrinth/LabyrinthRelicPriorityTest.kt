package com.landosol.toolbox.labyrinth

import com.landosol.toolbox.labyrinth.vision.EntryPixelRect
import com.landosol.toolbox.labyrinth.vision.LabyrinthRelicMatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class LabyrinthRelicPriorityTest {
    @Test fun `a user order decides between otherwise comparable marks`() {
        val candidates = listOf(candidate("加速", 3), candidate("会心", 3), candidate("守备", 3))
        val neutral = LabyrinthRelicChoicePolicy().choose(emptyList(), candidates, currentArea = 4)
        assertNotNull(neutral)

        val prefersDefense = policy(LabyrinthRelicMark.DEFENSE, LabyrinthRelicMark.CRITICAL)
            .choose(emptyList(), candidates, currentArea = 4)
        assertEquals("守备", prefersDefense?.choice?.attribute)

        val prefersCritical = policy(LabyrinthRelicMark.CRITICAL, LabyrinthRelicMark.DEFENSE)
            .choose(emptyList(), candidates, currentArea = 4)
        assertEquals("会心", prefersCritical?.choice?.attribute)
    }

    @Test fun `reaching the fifteen tier still outranks the user order`() {
        // The tier is an actual payoff, not a preference, so taste must not override it.
        val acquired = listOf(observed("会心", 13))
        val candidates = listOf(candidate("会心", 3), candidate("守备", 5))
        val decision = policy(LabyrinthRelicMark.DEFENSE, LabyrinthRelicMark.CRITICAL)
            .choose(acquired, candidates, currentArea = 4)
        assertEquals("会心", decision?.choice?.attribute)
    }

    @Test fun `an empty order keeps the built-in behaviour`() {
        val candidates = listOf(candidate("加速", 3), candidate("会心", 4))
        assertEquals(
            LabyrinthRelicChoicePolicy().choose(emptyList(), candidates, currentArea = 4)?.choice?.attribute,
            policy().choose(emptyList(), candidates, currentArea = 4)?.choice?.attribute,
        )
    }

    @Test fun `settings reject a duplicated order and round-trip a valid one`() {
        assertNull(
            LabyrinthStrategySettings(
                relicMarkPriority = listOf(LabyrinthRelicMark.DEBUFF, LabyrinthRelicMark.CRITICAL),
            ).validationError(),
        )
        assertNotNull(
            LabyrinthStrategySettings(
                relicMarkPriority = listOf(LabyrinthRelicMark.DEBUFF, LabyrinthRelicMark.DEBUFF),
            ).validationError(),
        )
        val settings = LabyrinthStrategySettings(
            relicMarkPriority = listOf(LabyrinthRelicMark.DEFENSE, LabyrinthRelicMark.ACCELERATION),
        )
        assertEquals(
            settings.relicMarkPriority,
            LabyrinthStrategySettingsCodec.decode(LabyrinthStrategySettingsCodec.encode(settings)).relicMarkPriority,
        )
    }

    private fun policy(vararg priority: LabyrinthRelicMark) = LabyrinthRelicChoicePolicy(
        LabyrinthRelicChoicePolicyConfig(markPriority = priority.toList()),
    )

    private fun candidate(attribute: String, bonus: Int) = LabyrinthRelicMatch(
        slotId = "$attribute$bonus",
        relicId = "$attribute$bonus",
        displayName = "$attribute+$bonus",
        attribute = attribute,
        attributeBonus = bonus.toString(),
        effect = null,
        confidence = 1.0,
        screenRect = EntryPixelRect(0, 0, 10, 10),
        iconRect = EntryPixelRect(0, 0, 10, 10),
    )

    private fun observed(attribute: String, stacks: Int) = LabyrinthObservedRelic(
        relicId = attribute,
        displayName = attribute,
        attribute = attribute,
        attributeBonus = stacks.toString(),
        effect = "",
        confidence = 1.0,
        firstSeenAt = 0L,
        lastSeenAt = 0L,
    )
}
