package com.landosol.toolbox.labyrinth.kernel.planning

import com.landosol.toolbox.labyrinth.LabyrinthRelicMark
import com.landosol.toolbox.labyrinth.LabyrinthRouteJson
import com.landosol.toolbox.labyrinth.node.LabyrinthNodeTypes as Node
import com.landosol.toolbox.protocol.labyrinth.LabyrinthMapNode

data class LabyrinthRunPlanningContext(
    val enabled: Boolean,
    val difficulty: Int,
    val currentArea: Int,
    val upcomingExams: List<BattleExam>,
    val remainingShops: Int,
    val enhanceNode21: Boolean,
    val reviveAvailable: Boolean,
    val observedRelicIds: Set<Int>,
    val markStacks: Map<LabyrinthRelicMark, Int>,
    val alphaRupee: Int?,
    val scoreSettings: ScoreSettings = ScoreSettings.currentOfficial(),
    val passEstimator: BattlePassEstimator?,
    val cacheFingerprint: String,
    val targetScore: Long = 0L,
) {
    companion object {
        fun fromRoute(
            enabled: Boolean,
            route: LabyrinthRouteJson?,
            allNodes: List<LabyrinthMapNode> = route?.toFullMap().orEmpty(),
            currentArea: Int?,
            observedRelicIds: Set<Int>,
            markStacks: Map<LabyrinthRelicMark, Int>,
            reviveAvailable: Boolean,
            alphaRupee: Int?,
            passEstimator: BattlePassEstimator?,
            estimatorTag: String = "",
            targetScore: Long = route?.targetScore ?: 0L,
        ): LabyrinthRunPlanningContext {
            val nodes = allNodes.ifEmpty { route?.toLabyrinthRoute()?.nodes.orEmpty() }
            val cursor = route?.currentBlockId
            val cursorNode = cursor?.let { id -> nodes.firstOrNull { it.blockId == id } }
            val effectiveArea = currentArea ?: cursorNode?.area
                ?: route?.nodes?.firstOrNull { it.blockId == cursor }?.area ?: 1
            val cursorColumnInArea = cursorNode?.takeIf { it.area == effectiveArea }?.column
            val upcoming = nodes.filter {
                it.area > effectiveArea || (it.area == effectiveArea && (cursorColumnInArea == null || it.column >= cursorColumnInArea))
            }
            val exams = upcoming.asSequence()
                .filter { it.blockType == Node.BOSS && (it.area == 3 || it.area == 5) }
                .mapNotNull { node -> node.questId?.let { BattleExam(it, node.area, isBoss = true) } }
                .distinct()
                .sortedWith(compareBy<BattleExam> { it.area }.thenBy { it.questId })
                .toList()
            val remainingShops = upcoming.count { it.blockType == Node.SHOP }
            val enhanceNode21 = 21 in route?.openingRelics?.enhanceNodeIds.orEmpty()
            val difficulty = route?.difficulty ?: 5
            val relics = observedRelicIds.toSortedSet()
            val marks = markStacks.toSortedMap()
            val fingerprint = buildString {
                append(enabled).append('|').append(route?.enterId).append('|').append(route?.guildId).append('|')
                append(difficulty).append('|').append(effectiveArea).append('|')
                append(exams.joinToString(",") { "${it.area}:${it.questId}" }).append('|')
                append(remainingShops).append('|').append(enhanceNode21).append('|').append(reviveAvailable).append('|')
                append(relics.joinToString(",")).append('|')
                append(marks.entries.joinToString(",") { "${it.key.name}:${it.value}" }).append('|')
                append(alphaRupee).append('|').append(passEstimator != null).append('|').append(estimatorTag).append('|')
                append(targetScore)
            }
            return LabyrinthRunPlanningContext(
                enabled, difficulty, effectiveArea, exams, remainingShops, enhanceNode21, reviveAvailable,
                relics, marks, alphaRupee, passEstimator = passEstimator, cacheFingerprint = fingerprint,
                targetScore = targetScore,
            )
        }
    }
}
