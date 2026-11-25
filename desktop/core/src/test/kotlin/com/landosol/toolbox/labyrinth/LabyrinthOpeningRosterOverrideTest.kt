package com.landosol.toolbox.labyrinth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class LabyrinthOpeningRosterOverrideTest {
    @Test fun `saren orphanage opens with the requested three`() {
        // 女仆 / 花女仆 / 圣诞咲恋.
        val config = requireNotNull(LabyrinthOpeningRosterCatalog.configFor(SAREN))
        assertEquals(
            listOf(listOf("1025"), listOf("1308"), listOf("1145")),
            config.slots.map { slot -> slot.candidates.map { it.characterId } },
        )
        val decision = LabyrinthOpeningRosterPolicy(config).choose(setOf("1025", "1308", "1103", "1145"))
        assertEquals(
            listOf("1025", "1308", "1145"),
            (decision as LabyrinthOpeningRosterDecision.Ready).characters.map { it.characterId },
        )
    }

    @Test fun `an override replaces the picks and keeps the guild's free character`() {
        val overrides = mapOf(LABYRINTHISTA to listOf(listOf("1025"), listOf("1103"), listOf("1308", "1077")))
        val config = requireNotNull(
            LabyrinthOpeningRosterCatalog.configFor(LABYRINTHISTA, overrides) { id -> "角色$id" },
        )
        assertEquals("拉比林斯", config.guildName)
        assertEquals(listOf("1068"), config.grantedCharacters.map { it.characterId })
        assertEquals(
            listOf(listOf("1025"), listOf("1103"), listOf("1308", "1077")),
            config.slots.map { slot -> slot.candidates.map { it.characterId } },
        )
        // A name resolver supplied by the caller keeps the planner's messages readable.
        assertEquals("角色1025", config.slots.first().candidates.single().displayName)

        // The second candidate is a fallback: it wins only when the first is not on screen.
        val policy = LabyrinthOpeningRosterPolicy(config)
        val ready = policy.choose(setOf("1025", "1103", "1077")) as LabyrinthOpeningRosterDecision.Ready
        assertEquals(listOf("1025", "1103", "1077"), ready.characters.map { it.characterId })
    }

    @Test fun `guilds without an override keep their shipped plan`() {
        val overrides = mapOf(LABYRINTHISTA to listOf(listOf("1025"), listOf("1103"), listOf("1308")))
        val untouched = requireNotNull(LabyrinthOpeningRosterCatalog.configFor(SAREN, overrides))
        assertEquals(LabyrinthOpeningRosterCatalog.configs.getValue(SAREN).slots, untouched.slots)
    }

    @Test fun `a malformed override is refused rather than half applied`() {
        assertNull(openingRosterOverrideError(listOf(listOf("1025"), listOf("1308"), listOf("1103"))))
        assertNotNull("wrong slot count", openingRosterOverrideError(listOf(listOf("1025"), listOf("1308"))))
        assertNotNull("empty slot", openingRosterOverrideError(listOf(listOf("1025"), emptyList(), listOf("1103"))))
        assertNotNull("bad id", openingRosterOverrideError(listOf(listOf("abc"), listOf("1308"), listOf("1103"))))
        // The planner fills slots independently, so one character in two slots could be taken twice.
        assertNotNull(
            "duplicate across slots",
            openingRosterOverrideError(listOf(listOf("1025"), listOf("1025"), listOf("1103"))),
        )

        // An override that cannot be honoured falls back to the shipped plan instead of breaking.
        val broken = mapOf(SAREN to listOf(listOf("1025"), listOf("1025"), listOf("1103")))
        assertEquals(
            LabyrinthOpeningRosterCatalog.configs.getValue(SAREN).slots,
            requireNotNull(LabyrinthOpeningRosterCatalog.configFor(SAREN, broken)).slots,
        )
    }

    @Test fun `settings reject an override the catalog could not use`() {
        assertNull(LabyrinthStrategySettings().validationError())
        assertNull(
            LabyrinthStrategySettings(
                openingRosters = mapOf(SAREN to listOf(listOf("1025"), listOf("1308"), listOf("1103"))),
            ).validationError(),
        )
        assertNotNull(
            LabyrinthStrategySettings(openingRosters = mapOf(SAREN to listOf(listOf("1025")))).validationError(),
        )
        assertNotNull(
            LabyrinthStrategySettings(
                openingRosters = mapOf(999 to listOf(listOf("1025"), listOf("1308"), listOf("1103"))),
            ).validationError(),
        )
    }

    @Test fun `overrides survive a save and load round trip`() {
        val settings = LabyrinthStrategySettings(
            openingRosters = mapOf(SAREN to listOf(listOf("1025"), listOf("1308", "1077"), listOf("1103"))),
        )
        val restored = LabyrinthStrategySettingsCodec.decode(LabyrinthStrategySettingsCodec.encode(settings))
        assertEquals(settings.openingRosters, restored.openingRosters)
    }

    @Test fun `new guilds keep their configured three picks and do not grant extra characters`() {
        val expected = mapOf(
            6 to listOf(listOf("1348"), listOf("1344"), listOf("1054", "1116")),
            7 to listOf(listOf("1082"), listOf("1117"), listOf("1138")),
            8 to listOf(listOf("1033"), listOf("1026"), listOf("1038")),
            9 to listOf(listOf("1079"), listOf("1168"), listOf("1251")),
            10 to listOf(listOf("1056"), listOf("1299"), listOf("1051")),
        )
        for ((guild, slots) in expected) {
            val config = requireNotNull(LabyrinthOpeningRosterCatalog.configFor(guild))
            assertEquals("guild $guild", slots, config.slots.map { slot -> slot.candidates.map { it.characterId } })
            assertEquals(emptyList<LabyrinthOpeningCharacter>(), config.grantedCharacters)
            val decision = LabyrinthOpeningRosterPolicy(config).choose(slots.flatten().toSet())
            assertEquals(slots.map { it.first() },
                (decision as LabyrinthOpeningRosterDecision.Ready).characters.map { it.characterId })
        }
    }

    @Test fun `carmina uses official names and only its configured fallback`() {
        val config = requireNotNull(LabyrinthOpeningRosterCatalog.configFor(6))
        assertEquals(listOf("纺希（吉奥·矶汉拿）", "千歌（礼服）"),
            config.slots.take(2).map { it.candidates.single().displayName })
        val policy = LabyrinthOpeningRosterPolicy(config)
        val fallback = policy.choose(setOf("1348", "1344", "1116", "9999")) as LabyrinthOpeningRosterDecision.Ready
        assertEquals(listOf("1348", "1344", "1116"), fallback.characters.map { it.characterId })
        val missing = policy.choose(setOf("1348", "1344", "9999")) as LabyrinthOpeningRosterDecision.Missing
        assertEquals(listOf(3), missing.missingSlotNumbers)
    }

    private companion object {
        const val SAREN = 3
        const val LABYRINTHISTA = 5
    }
}
