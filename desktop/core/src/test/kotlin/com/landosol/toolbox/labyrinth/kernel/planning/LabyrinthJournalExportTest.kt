package com.landosol.toolbox.labyrinth.kernel.planning

import com.landosol.toolbox.labyrinth.LabyrinthCombatKind
import com.landosol.toolbox.labyrinth.LabyrinthNodeJson
import com.landosol.toolbox.labyrinth.LabyrinthRoleDecisionContext
import com.landosol.toolbox.labyrinth.LabyrinthRoleProfile
import com.landosol.toolbox.labyrinth.LabyrinthRouteJson
import com.landosol.toolbox.labyrinth.LabyrinthShadowBattleRecorder
import com.landosol.toolbox.labyrinth.kernel.KernelJournalSink
import com.landosol.toolbox.labyrinth.kernel.KernelJournalReader
import com.landosol.toolbox.labyrinth.kernel.KernelRuntime
import com.landosol.toolbox.labyrinth.kernel.strategy.BattleRosterUnit
import com.landosol.toolbox.labyrinth.kernel.strategy.BattleValueContext
import com.landosol.toolbox.labyrinth.node.LabyrinthNodeTypes as Node
import com.landosol.toolbox.protocol.labyrinth.LabyrinthOpeningRelicState
import java.nio.file.Files
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LabyrinthJournalExportTest {
    @Test fun exportsAnonymizedJsonlWithFullMapAndJournalBattleSamples() {
        val tempDir = Files.createTempDirectory("replay-export-test-").toFile()
        try {
            val runtime = KernelRuntime()
            val journal = KernelJournalSink(tempDir.resolve("journal"))
            runtime.addSink(journal)
            runtime.resetSession("session-secret-account-998877")

            val roster = (1001..1005).map { id ->
                BattleRosterUnit(id * 100 + 1, LabyrinthRoleProfile(id.toString(), "Role$id"))
            }
            val recorder = LabyrinthShadowBattleRecorder()
            val ctx = BattleValueContext(
                questId = 77030101,
                area = 3,
                difficulty = 5,
                ownedUnits = roster,
                ruleContext = LabyrinthRoleDecisionContext(0),
                relicIds = listOf(40003),
                markStacks = listOf(2, 1, 0, 0, 0),
                alphaRupee = 1800,
                reviveAvailable = false,
            )
            assertTrue(recorder.beginAttempt(ctx, roster.map { it.unitId }, LabyrinthCombatKind.BOSS, null, 7, 1_000L))
            recorder.completeAttempt(0, 20_000L, runtime)
            journal.close()

            val loaded = KernelJournalReader.verifyAndLoad(
                KernelJournalSink.sessionDirFor(tempDir.resolve("journal"), runtime.traceId),
            )
            val subsetNodes = listOf(
                LabyrinthNodeJson(1, 1, 0, 101L, Node.START, null, listOf(102L), false),
                LabyrinthNodeJson(3, 2, 0, 102L, Node.BOSS, 77030101, emptyList(), true),
            )
            val fullMap = subsetNodes + listOf(
                LabyrinthNodeJson(1, 2, 1, 103L, Node.SHOP, null, listOf(102L), false),
            )
            val route = LabyrinthRouteJson(
                enterId = 987654321L,
                guildId = 7,
                difficulty = 5,
                attempt = 1,
                rerollUntilFound = false,
                perfectStart = false,
                thirdBlockChoice = "EITHER",
                area3BossIds = listOf(1),
                area5BossIds = emptyList(),
                nodes = subsetNodes,
                allNodes = fullMap,
                openingRelics = LabyrinthOpeningRelicState(guildId = 7, enhanceNodeIds = setOf(21, 9)),
            )

            val episode = LabyrinthReplayExporter.toReplayEpisode(route, loaded)
            val outFile = tempDir.resolve("nested/replays.jsonl")
            assertEquals(1, LabyrinthReplayExporter.exportJsonl(listOf(episode), outFile))

            val lines = outFile.readLines().filter(String::isNotBlank)
            assertEquals(1, lines.size)
            val line = lines.single()
            assertFalse(line.contains("987654321"))
            assertFalse(line.contains("998877"))

            val obj = Json.parseToJsonElement(line).jsonObject
            assertTrue(obj.getValue("episode_id").jsonPrimitive.content.startsWith("ep-"))
            assertEquals(5, obj.getValue("difficulty").jsonPrimitive.int)
            assertEquals(3, obj.getValue("allNodes").jsonArray.size)
            assertTrue(obj.getValue("initial_state").jsonObject.getValue("has_enhance_node_21").jsonPrimitive.boolean)
            val samples = obj.getValue("battle_samples").jsonArray
            assertEquals(1, samples.size)
            assertEquals(77030101, samples.first().jsonObject.getValue("quest_id").jsonPrimitive.int)

            // Fallback to selected route nodes when allNodes is empty.
            val fallbackEpisode = LabyrinthReplayExporter.toReplayEpisode(route.copy(allNodes = emptyList()))
            assertEquals(2, fallbackEpisode.allNodes.size)
            assertEquals(episode.episodeId, LabyrinthReplayExporter.toReplayEpisode(route).episodeId)
        } finally {
            tempDir.deleteRecursively()
        }
    }
}
