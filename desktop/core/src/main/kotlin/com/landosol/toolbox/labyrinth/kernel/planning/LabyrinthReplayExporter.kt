package com.landosol.toolbox.labyrinth.kernel.planning

import com.landosol.toolbox.labyrinth.LabyrinthNodeJson
import com.landosol.toolbox.labyrinth.LabyrinthRouteJson
import com.landosol.toolbox.labyrinth.kernel.BattleSamplePayload
import com.landosol.toolbox.labyrinth.kernel.LoadedKernelJournal
import com.landosol.toolbox.labyrinth.kernel.strategy.ShadowBattleSample
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class LabyrinthReplayNodeJson(
    @SerialName("block_id") val blockId: Long,
    val area: Int,
    val column: Int,
    val row: Int,
    @SerialName("block_type") val blockType: Int,
    @SerialName("quest_id") val questId: Int? = null,
    @SerialName("next_block_ids") val nextBlockIds: List<Long> = emptyList(),
    @SerialName("is_area_last_point") val isAreaLastPoint: Boolean = false,
)

@Serializable
data class LabyrinthReplayInitialStateJson(
    val difficulty: Int,
    @SerialName("has_enhance_node_21") val hasEnhanceNode21: Boolean = false,
)

@Serializable
data class LabyrinthReplayEpisodeJson(
    @SerialName("episode_id") val episodeId: String,
    val difficulty: Int,
    val allNodes: List<LabyrinthReplayNodeJson>,
    @SerialName("initial_state") val initialState: LabyrinthReplayInitialStateJson,
    @SerialName("battle_samples") val battleSamples: List<ShadowBattleSample> = emptyList(),
)

object LabyrinthReplayExporter {
    private val replayJson = Json { encodeDefaults = true }

    fun extractBattleSamples(journal: LoadedKernelJournal): List<ShadowBattleSample> =
        journal.events.mapNotNull { (it.payload as? BattleSamplePayload)?.sample }

    fun toReplayEpisode(
        route: LabyrinthRouteJson,
        battleSamples: List<ShadowBattleSample> = emptyList(),
    ): LabyrinthReplayEpisodeJson {
        val rawNodes = route.allNodes.ifEmpty { route.nodes }
        val nodes = rawNodes.map { node ->
            LabyrinthReplayNodeJson(
                blockId = node.blockId,
                area = node.area,
                column = node.column,
                row = node.row,
                blockType = node.blockType,
                questId = node.questId,
                nextBlockIds = node.nextBlockIds,
                isAreaLastPoint = node.isAreaLastPoint,
            )
        }
        val enhanceNode21 = 21 in route.openingRelics?.enhanceNodeIds.orEmpty()
        return LabyrinthReplayEpisodeJson(
            episodeId = anonymizedEpisodeId(route, rawNodes),
            difficulty = route.difficulty,
            allNodes = nodes,
            initialState = LabyrinthReplayInitialStateJson(
                difficulty = route.difficulty,
                hasEnhanceNode21 = enhanceNode21,
            ),
            battleSamples = battleSamples.toList(),
        )
    }

    fun toReplayEpisode(route: LabyrinthRouteJson, journal: LoadedKernelJournal): LabyrinthReplayEpisodeJson =
        toReplayEpisode(route, extractBattleSamples(journal))

    fun encodeJsonl(episodes: Iterable<LabyrinthReplayEpisodeJson>): String {
        val list = episodes.toList()
        if (list.isEmpty()) return ""
        return list.joinToString(separator = "\n", postfix = "\n") { replayJson.encodeToString(it) }
    }

    fun exportJsonl(episodes: Iterable<LabyrinthReplayEpisodeJson>, targetFile: File): Int {
        val list = episodes.toList()
        val content = encodeJsonl(list).toByteArray(Charsets.UTF_8)
        val targetPath = targetFile.toPath()
        targetPath.parent?.let { Files.createDirectories(it) }
        val tmp = Files.createTempFile(targetPath.parent ?: File(".").toPath(), "replays-", ".tmp")
        try {
            Files.write(tmp, content)
            try {
                Files.move(tmp, targetPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(tmp, targetPath, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(tmp)
        }
        return list.size
    }

    private fun anonymizedEpisodeId(route: LabyrinthRouteJson, nodes: List<LabyrinthNodeJson>): String {
        val seed = "${route.enterId}|${route.difficulty}|${nodes.size}|${nodes.firstOrNull()?.blockId}|${nodes.lastOrNull()?.blockId}"
        val hex = MessageDigest.getInstance("SHA-256")
            .digest(seed.toByteArray(Charsets.UTF_8))
            .take(8)
            .joinToString("") { "%02x".format(it) }
        return "ep-$hex"
    }
}
