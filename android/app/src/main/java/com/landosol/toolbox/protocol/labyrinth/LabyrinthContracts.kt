package com.landosol.toolbox.protocol.labyrinth

data class LabyrinthTop(
    val enterId: Long?,
    val guildId: Int?,
    val difficulty: Int?,
    val clearedDifficulties: List<ClearedDifficulty>,
    val enhancedNodes: Set<Int> = emptySet(),
)

data class ClearedDifficulty(val guildId: Int, val difficulty: Int)

data class LabyrinthEnterRequest(val guildId: Int, val difficulty: Int)

data class LabyrinthEnter(
    val enterId: Long,
    val map: List<LabyrinthMapNode>,
    val relicIds: Set<Int> = emptySet(),
)

data class LabyrinthResume(
    val enterId: Long,
    val guildId: Int?,
    val currentBlockId: Long?,
    val map: List<LabyrinthMapNode>,
    val relicIds: Set<Int> = emptySet(),
)

data class LabyrinthMapNode(
    val area: Int,
    val column: Int,
    val row: Int,
    val blockId: Long,
    val blockType: Int,
    val questId: Int?,
    val nextBlockIds: List<Long>,
    val isAreaLastPoint: Boolean,
)

data class LabyrinthRoute(
    val enterId: Long,
    val nodes: List<LabyrinthMapNode>,
    val openingRelicIds: Set<Int> = emptySet(),
) {
    val blockIds: List<Long> get() = nodes.map(LabyrinthMapNode::blockId)
}

sealed interface LabyrinthOperationResult<out T> {
    data class Success<T>(val value: T) : LabyrinthOperationResult<T>
    data class Failure(
        val message: String,
        val kind: LabyrinthFailureKind = LabyrinthFailureKind.UNKNOWN,
    ) : LabyrinthOperationResult<Nothing>
}

enum class LabyrinthFailureKind {
    REJECTED,
    NETWORK,
    PROTOCOL,
    UNKNOWN,
}

interface LabyrinthApi {
    suspend fun top(): LabyrinthOperationResult<LabyrinthTop>
    suspend fun enter(request: LabyrinthEnterRequest): LabyrinthOperationResult<LabyrinthEnter>
    suspend fun resume(enterId: Long): LabyrinthOperationResult<LabyrinthResume>
    suspend fun retire(enterId: Long): LabyrinthOperationResult<Unit>
}
