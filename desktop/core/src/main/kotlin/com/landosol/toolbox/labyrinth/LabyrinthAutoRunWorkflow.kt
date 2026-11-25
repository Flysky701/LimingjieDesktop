package com.landosol.toolbox.labyrinth

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 黎明界自动执行外层循环配置。
 *
 * 一次 run = 从进入黎明界到按已保存路线走完全部区域。达到 [targetRuns] 后停止；
 * 轮与轮之间通过会话重置回到可重新进入的状态。
 */
data class LabyrinthAutoRunConfig(
    val accountId: Long?,
    val targetRuns: Int,
) {
    init {
        require(targetRuns in 1..MAX_TARGET_RUNS) { "刷取次数必须在 1..$MAX_TARGET_RUNS" }
    }

    companion object {
        const val MAX_TARGET_RUNS = 99
    }
}

/** 单轮路线执行的结局。 */
sealed interface LabyrinthAutoRunRoundOutcome {
    /** 路线走完全部区域。 */
    data object Completed : LabyrinthAutoRunRoundOutcome

    /** 会话因错误、拒绝或用户停止而提前结束。 */
    data class Aborted(val reason: String) : LabyrinthAutoRunRoundOutcome
}

data class LabyrinthAutoRunProgress(
    val completedRuns: Int = 0,
    val targetRuns: Int = 0,
    val stage: String = "",
    val running: Boolean = false,
)

sealed interface LabyrinthAutoRunResult {
    data class Completed(val runs: Int) : LabyrinthAutoRunResult
    data class Aborted(val runs: Int, val reason: String) : LabyrinthAutoRunResult
    data class Failure(val runs: Int, val message: String) : LabyrinthAutoRunResult
}

/**
 * 刷取次数外层循环：进入并执行路线 → 计数 → 会话重置 → 重新进入。
 *
 * 具体能力通过挂钩注入：[startRun] 启动入口+路线执行会话，[awaitRunFinished]
 * 阻塞到本轮结束并给出结局，[resetSession] 在两轮之间重置游戏会话。
 */
class LabyrinthAutoRunWorkflow(
    private val startRun: suspend (accountId: Long?) -> Boolean,
    private val awaitRunFinished: suspend () -> LabyrinthAutoRunRoundOutcome,
    private val resetSession: suspend () -> Boolean,
    private val resetFailureReason: () -> String? = { null },
) {
    private val _progress = MutableStateFlow(LabyrinthAutoRunProgress())
    val progress: StateFlow<LabyrinthAutoRunProgress> = _progress.asStateFlow()

    suspend fun run(config: LabyrinthAutoRunConfig): LabyrinthAutoRunResult {
        var runs = 0
        publish(runs, config.targetRuns, "准备进入黎明界", running = true)
        try {
            while (runs < config.targetRuns) {
                if (!startRun(config.accountId)) {
                    return LabyrinthAutoRunResult.Failure(runs, "无法启动路线执行会话").also {
                        publish(runs, config.targetRuns, it.message, running = false)
                    }
                }
                publish(runs, config.targetRuns, "第 ${runs + 1}/${config.targetRuns} 轮执行中", running = true)
                when (val outcome = awaitRunFinished()) {
                    LabyrinthAutoRunRoundOutcome.Completed -> {
                        runs++
                        publish(runs, config.targetRuns, "第 $runs 轮已完成", running = true)
                    }

                    is LabyrinthAutoRunRoundOutcome.Aborted ->
                        return LabyrinthAutoRunResult.Aborted(runs, outcome.reason).also {
                            publish(runs, config.targetRuns, "已中止：${outcome.reason}", running = false)
                        }
                }
                if (runs < config.targetRuns) {
                    publish(runs, config.targetRuns, "重置游戏会话中", running = true)
                    if (!resetSession()) {
                        val reason = resetFailureReason()?.takeIf(String::isNotBlank) ?: "会话重置失败"
                        return LabyrinthAutoRunResult.Failure(runs, reason).also {
                            publish(runs, config.targetRuns, it.message, running = false)
                        }
                    }
                }
            }
            publish(runs, config.targetRuns, "全部 ${config.targetRuns} 轮完成", running = false)
            return LabyrinthAutoRunResult.Completed(runs)
        } finally {
            if (_progress.value.running) {
                publish(runs, config.targetRuns, "已停止", running = false)
            }
        }
    }

    private fun publish(runs: Int, target: Int, stage: String, running: Boolean) {
        _progress.value = LabyrinthAutoRunProgress(
            completedRuns = runs,
            targetRuns = target,
            stage = stage,
            running = running,
        )
    }
}
