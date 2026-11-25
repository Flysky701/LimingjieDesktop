package com.landosol.toolbox.automation.session

/**
 * 统一的会话重置后端：组合「终止客户端」「重启客户端」「在场检测」。
 * 开发阶段使用 [AdbSessionResetBackend]（am force-stop + am start）；
 * 最终无 Root 版本使用降级链（游戏内登出 / 最近任务划卡 / 用户协助）。
 */
interface GameSessionResetBackend {
    /** 本后端的终止策略类型（决定重置流程是否还需要重启客户端） */
    val kind: ClientTerminationKind
    val lastDiagnosticStep: String? get() = null

    suspend fun terminateClient(): ClientTerminationResult
    suspend fun relaunchClient(): GameClientRelaunchResult
    fun isSupported(): Boolean
}

/** 客户端重启策略（fun interface，支持 lambda 注入） */
fun interface Relauncher {
    suspend fun relaunch(): GameClientRelaunchResult
}

/** 把终止策略与重启策略组合成统一后端 */
class CompositeSessionResetBackend(
    private val terminator: GameClientTerminator,
    private val relauncher: Relauncher,
) : GameSessionResetBackend {
    override val kind: ClientTerminationKind get() = terminator.kind
    override val lastDiagnosticStep: String? get() = terminator.lastDiagnosticStep
    override suspend fun terminateClient(): ClientTerminationResult = terminator.terminate()
    override suspend fun relaunchClient(): GameClientRelaunchResult = relauncher.relaunch()
    override fun isSupported(): Boolean = terminator.isSupported()
}
