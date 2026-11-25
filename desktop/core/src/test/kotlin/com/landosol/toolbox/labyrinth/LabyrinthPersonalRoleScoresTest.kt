package com.landosol.toolbox.labyrinth

import com.landosol.toolbox.gamedata.GameIconAsset
import com.landosol.toolbox.gamedata.GameIconPackDocument
import java.io.File
import org.junit.Assert.*
import org.junit.Test

class LabyrinthPersonalRoleScoresTest {
    private val original by lazy {
        val asset = File("src/main/resources/assets/resource-packs/cn-bilibili/labyrinth-role-decision.json")
        (LabyrinthRoleDecisionDataParser.parse(asset.readText()) as LabyrinthRoleDecisionDataResult.Ready).runtime.document
    }

    @Test fun `personal score overrides source without changing source facts`() {
        val before = original.characters.first { it.isStrictDecisionReady && it.userScore != null }
        val settings = LabyrinthStrategySettings(personalRoleScores = mapOf(before.characterId to 0))
        val after = settings.applyTo(original).characters.first { it.characterId == before.characterId }
        assertEquals(0.0, after.effectiveUserScore!!, 0.0)
        assertEquals(before.userScore, after.userScore)
        assertEquals(before.userRatingCount, after.userRatingCount)
        assertEquals(before, after.copy(personalScore = null))
        assertNull(before.personalScore)
    }

    @Test fun `overrides flow into actual team scoring`() {
        val team = original.characters.filter { it.isStrictDecisionReady && (it.userScore ?: 0.0) > 0 }.take(5)
        val configured = LabyrinthStrategySettings(personalRoleScores = team.associate { it.characterId to 12 }).applyTo(original)
        val runtime = LabyrinthRoleDecisionDataParser.createRuntime(configured)
        val evaluation = LabyrinthTeamScorer(configured.scoring).evaluate(team.map { runtime.profiles.getValue(it.characterId) }, LabyrinthRoleDecisionContext(4))
        assertEquals(12.0, evaluation.playerScore!!, 0.0)
    }

    @Test fun `personal score cannot complete missing character facts`() {
        val missing = original.characters.first { !it.isStrictDecisionReady }
        val updated = LabyrinthStrategySettings(personalRoleScores = mapOf(missing.characterId to 100)).applyTo(original)
            .characters.first { it.characterId == missing.characterId }
        assertFalse(updated.isStrictDecisionReady)
    }

    @Test fun `reset restores original and other characters remain unchanged`() {
        val role = original.characters.first { it.characterId != "1000" }
        val settings = LabyrinthStrategySettings(personalRoleScores = mapOf(role.characterId to 5))
        val updated = settings.applyTo(original)
        assertEquals(original.characters.filterNot { it.characterId == role.characterId }, updated.characters.filterNot { it.characterId == role.characterId })
        assertEquals(original, settings.copy(personalRoleScores = emptyMap()).applyTo(original))
    }

    @Test fun `personal scores persist and reject invalid ranges or ids`() {
        val settings = LabyrinthStrategySettings(personalRoleScores = mapOf("1331" to 0, "1351" to 100))
        assertEquals(settings, LabyrinthStrategySettingsCodec.decode(LabyrinthStrategySettingsCodec.encode(settings)))
        for (scores in listOf(mapOf("1331" to 101), mapOf("1331" to -1), mapOf("祈梨" to 80))) {
            assertNotNull(LabyrinthStrategySettings(personalRoleScores = scores).validationError())
        }
        assertTrue(LabyrinthStrategySettingsCodec.decode("{\"playerWeight\":50}").personalRoleScores.isEmpty())
    }

    @Test fun `avatars use exact character id not shared name`() {
        val base = original.characters.first { it.isStrictDecisionReady }
        val document = original.copy(characters = listOf(base.copy(characterId = "1401", displayName = "祈梨"),
            base.copy(characterId = "1402", displayName = "祈梨（时间旅行）")))
        val icons = GameIconPackDocument(characterIcons = listOf(
            GameIconAsset("1401", "11", "icons/characters/a11.png"),
            GameIconAsset("1401", "31", "icons/characters/a31.png"),
            GameIconAsset("1402", "11", "icons/characters/b11.png")))
        val rows = labyrinthRoleRatingCatalog(document, icons).associateBy { it.id }
        assertTrue(rows.getValue("1401").iconAsset!!.endsWith("a31.png"))
        assertTrue(rows.getValue("1402").iconAsset!!.endsWith("b11.png"))
        assertTrue(labyrinthRoleRatingCatalog(document, null).all { it.iconAsset == null })
    }

    @Test fun `catalog does not display personal score as original source score`() {
        val base = original.characters.first { it.isStrictDecisionReady }
        val document = original.copy(characters = listOf(base.copy(personalScore = 7.0)))
        assertEquals(base.userScore ?: base.baseQuality, labyrinthRoleRatingCatalog(document, null).single().originalScore)
    }
}
