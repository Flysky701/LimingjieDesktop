package com.landosol.toolbox.labyrinth

import com.landosol.toolbox.protocol.labyrinth.LabyrinthTop

/**
 * Result of the safety check performed before live route execution.
 *
 * The saved checkpoint's Enter ID is the persisted identity of the server opening. A TARGET
 * checkpoint also proves that the route matches the current filter; cached execution may reuse
 * a non-target checkpoint when its Enter ID still matches the saved route. The optional [top]
 * argument is used only for an explicit fresh online comparison.
 */
sealed interface LabyrinthExecutionGateResult {
    data class Allowed(
        val route: LabyrinthRouteJson,
        val message: String,
    ) : LabyrinthExecutionGateResult

    data class Blocked(val message: String) : LabyrinthExecutionGateResult
}

fun validateLabyrinthExecution(
    route: LabyrinthRouteJson?,
    checkpoint: LabyrinthRerollCheckpoint?,
    top: LabyrinthTop?,
): LabyrinthExecutionGateResult {
    if (route == null) return LabyrinthExecutionGateResult.Blocked("未找到已保存路线，请先完成刷开局")
    if (route.enterId <= 0L) {
        return LabyrinthExecutionGateResult.Blocked("已保存路线缺少有效 Enter ID")
    }
    if (route.nodes.isEmpty() || route.allNodes.isEmpty()) {
        return LabyrinthExecutionGateResult.Blocked("已保存路线缺少路线节点或完整地图数据")
    }
    if (checkpoint == null) {
        return LabyrinthExecutionGateResult.Blocked("未找到路线检查点，请先联网读取并确认当前开局")
    }
    if (checkpoint.enterId != route.enterId) {
        return LabyrinthExecutionGateResult.Blocked(
            "路线与已保存开局检查点的 Enter ID 不一致：路线=${route.enterId}，检查点=${checkpoint.enterId}",
        )
    }
    if (top == null) {
        return when (checkpoint.verdict) {
            LabyrinthRouteVerdict.TARGET -> {
                if (checkpoint.guildId != route.guildId) {
                    return LabyrinthExecutionGateResult.Blocked(
                        "路线与 TARGET 检查点的公会不一致：路线=${route.guildId}，检查点=${checkpoint.guildId}",
                    )
                }
                if (checkpoint.difficulty != route.difficulty) {
                    return LabyrinthExecutionGateResult.Blocked(
                        "路线与 TARGET 检查点的难度不一致：路线=${route.difficulty}，检查点=${checkpoint.difficulty}",
                    )
                }
                LabyrinthExecutionGateResult.Allowed(
                    route = route,
                    message = "已复用上次确认的服务端开局（Enter ID=${route.enterId}），无需重新登录或重新读取",
                )
            }

            LabyrinthRouteVerdict.NOT_TARGET -> LabyrinthExecutionGateResult.Allowed(
                route = route,
                message = "已复用相同 Enter ID 的已保存开局（${route.enterId}）；当前筛选条件不是该路线目标，仅按 Enter ID 继续执行",
            )

            LabyrinthRouteVerdict.PENDING_VERIFICATION ->
                LabyrinthExecutionGateResult.Blocked("路线检查点仍待联网验证，无法安全复用当前开局")
        }
    }
    if (checkpoint.verdict != LabyrinthRouteVerdict.TARGET) {
        return LabyrinthExecutionGateResult.Blocked("路线检查点不是 TARGET，请先联网读取并确认当前开局")
    }
    if (checkpoint.guildId != route.guildId) {
        return LabyrinthExecutionGateResult.Blocked(
            "路线与 TARGET 检查点的公会不一致：路线=${route.guildId}，检查点=${checkpoint.guildId}",
        )
    }
    if (checkpoint.difficulty != route.difficulty) {
        return LabyrinthExecutionGateResult.Blocked(
            "路线与 TARGET 检查点的难度不一致：路线=${route.difficulty}，检查点=${checkpoint.difficulty}",
        )
    }
    val currentEnterId = top.enterId
        ?: return LabyrinthExecutionGateResult.Blocked("服务端当前没有黎明界开局，已禁止执行保存路线")
    if (currentEnterId != route.enterId) {
        return LabyrinthExecutionGateResult.Blocked(
            "当前开局与保存路线不一致：当前=$currentEnterId，路线=${route.enterId}",
        )
    }
    if (top.guildId != null && top.guildId != route.guildId) {
        return LabyrinthExecutionGateResult.Blocked(
            "当前开局公会与保存路线不一致：当前=${top.guildId}，路线=${route.guildId}",
        )
    }
    if (top.difficulty != null && top.difficulty != route.difficulty) {
        return LabyrinthExecutionGateResult.Blocked(
            "当前开局难度与保存路线不一致：当前=${top.difficulty}，路线=${route.difficulty}",
        )
    }
    return LabyrinthExecutionGateResult.Allowed(
        route = route,
        message = "已验证 TARGET 与当前 Enter ID 一致（${route.enterId}），允许执行路线",
    )
}
