package com.landosol.toolbox.automation.session

import com.landosol.toolbox.labyrinth.vision.LabyrinthEntryPageState

/**
 * GameSessionReset 状态机核心。纯 Kotlin、无 Android 依赖，可 JVM 单测。
 *
 * 遵循项目统一的「识别 → 决策 → 点击 → 状态转换」约束：
 * - 识别：页面观察（[LabyrinthEntryPageState]）与弹窗阻塞观察
 * - 决策：[GameSessionResetPlanner].decide() 返回下一步
 * - 点击：由上层通过 actionExecutor 完成，本模块不持有执行器
 * - 状态转换：决策结果驱动 [GameSessionResetStage] 迁移
 */

enum class GameSessionResetStage {
    /** 自动刷流程已完成，保存本局结果后进入重置 */
    PENDING,

    /** 关闭/退出客户端，等待确认客户端不在场 */
    TERMINATING,

    /** 重新启动公主连结 */
    RELAUNCHING,

    /** 等待登录完成（标题页/加载/公告） */
    WAITING_LOGIN,

    /** 重回主页 */
    NAVIGATING_HOME,

    /** 进入冒险 */
    NAVIGATING_ADVENTURE,

    /** 进入黎明界 */
    NAVIGATING_DAWN_REALM,

    /** 会话重置完毕，可以开始下一轮刷初始 */
    READY_FOR_NEXT_ROUND,

    /** 检测到需要重新登录/重新连接弹窗，等待处理 */
    SESSION_BLOCKED,

    /** 重置失败 */
    FAILED,
}

enum class SessionBlockKind {
    /** 无阻塞 */
    NONE,

    /** 需要重新登录弹窗 */
    RELOGIN_REQUIRED,

    /** 重新连接弹窗 */
    RECONNECT_PROMPTED,

    /** 未知弹窗 */
    UNKNOWN_PROMPT,
}

data class GameSessionResetObservation(
    val pageState: LabyrinthEntryPageState,
    val block: SessionBlockKind,
    val clientForeground: Boolean,
    /** 会话失效终止方式下，以识别到「返回/重新登录」按钮为弹窗确认信号 */
    val sessionExpiryRequired: Boolean = false,
)

/** 决策输出：与现有 planner 风格一致的密封接口 */
sealed interface GameSessionResetDecision {
    /** 进入明确阶段，无需点击（例如完成关闭/重启命令） */
    data class Transition(val stage: GameSessionResetStage) : GameSessionResetDecision

    /** 等待画面稳定，不点击 */
    data class Wait(val reason: String) : GameSessionResetDecision

    /** 点击可配置坐标推进到下一阶段 */
    data class Click(val label: String) : GameSessionResetDecision

    /** 会话被弹窗阻塞，交由上层处理（重新登录或人工确认） */
    data class Blocked(val kind: SessionBlockKind, val reason: String) : GameSessionResetDecision

    /** 流程完成，进入下一轮 */
    data class Complete(val reason: String) : GameSessionResetDecision

    /** 流程失败 */
    data class Fail(val reason: String) : GameSessionResetDecision
}

/** 可配置的页面 → 阶段推进规则；不硬编码具体按钮坐标 */
data class SessionResetStageRule(
    val stage: GameSessionResetStage,
    /** 需要识别到的页面状态；null 表示不需要前置页面 */
    val expectedPage: LabyrinthEntryPageState? = null,
    /** 满足期望页面后的下一个阶段 */
    val nextStage: GameSessionResetStage,
)

/**
 * 无硬编码的会话重置规划器：阶段推进由 [rules] 注入（每个阶段一个规则），
 * 弹窗阻塞在任何阶段优先于页面推进。
 */
class GameSessionResetPlanner(
    private val rules: List<SessionResetStageRule> = defaultRules(),
) {
    private val rulesByStage = rules.associateBy(SessionResetStageRule::stage)

    fun decide(
        stage: GameSessionResetStage,
        observation: GameSessionResetObservation,
        stableFrames: Int,
        nowMillis: Long,
        lastStageChangeAt: Long,
        clickCooldownMillis: Long = 1_200L,
        stableFramesRequired: Int = 2,
    ): GameSessionResetDecision {
        when (observation.block) {
            SessionBlockKind.RELOGIN_REQUIRED ->
                return GameSessionResetDecision.Blocked(
                    SessionBlockKind.RELOGIN_REQUIRED,
                    "检测到「需要重新登录」，会话已被新登录流程挤出",
                )
            SessionBlockKind.RECONNECT_PROMPTED ->
                return GameSessionResetDecision.Blocked(
                    SessionBlockKind.RECONNECT_PROMPTED,
                    "检测到「重新连接」提示，客户端网络状态已失效",
                )
            SessionBlockKind.UNKNOWN_PROMPT ->
                return GameSessionResetDecision.Blocked(
                    SessionBlockKind.UNKNOWN_PROMPT,
                    "检测到未知弹窗，停止自动化等待处理",
                )
            SessionBlockKind.NONE -> Unit
        }

        when (stage) {
            GameSessionResetStage.PENDING ->
                return GameSessionResetDecision.Transition(GameSessionResetStage.TERMINATING)

            GameSessionResetStage.TERMINATING -> {
                // 会话失效触发：不杀进程，以识别到「错误提示/重新连接」弹窗或标题页为完成信号
                if (observation.sessionExpiryRequired) {
                    if (observation.pageState == LabyrinthEntryPageState.TITLE_WAITING_TAP) {
                        return GameSessionResetDecision.Transition(GameSessionResetStage.WAITING_LOGIN)
                    }
                    if (observation.block == SessionBlockKind.RECONNECT_PROMPTED ||
                        observation.block == SessionBlockKind.RELOGIN_REQUIRED
                    ) {
                        return GameSessionResetDecision.Wait("弹窗已出现，等待确认返回标题")
                    }
                    return GameSessionResetDecision.Wait("等待会话失效弹窗或标题页出现")
                }
                if (!observation.clientForeground) {
                    return GameSessionResetDecision.Transition(GameSessionResetStage.RELAUNCHING)
                }
                return GameSessionResetDecision.Wait("客户端仍在前台，等待关闭完成")
            }

            GameSessionResetStage.RELAUNCHING ->
                return GameSessionResetDecision.Transition(GameSessionResetStage.WAITING_LOGIN)

            GameSessionResetStage.SESSION_BLOCKED ->
                return GameSessionResetDecision.Wait("会话阻塞待处理")

            GameSessionResetStage.FAILED ->
                return GameSessionResetDecision.Fail("重置失败")

            GameSessionResetStage.READY_FOR_NEXT_ROUND ->
                return GameSessionResetDecision.Complete("会话重置完毕，可以开始下一轮刷初始")

            else -> {
                val rule = rulesByStage[stage]
                    ?: return GameSessionResetDecision.Fail("阶段 $stage 缺少推进规则")
                if (rule.expectedPage != null && observation.pageState != rule.expectedPage) {
                    return GameSessionResetDecision.Wait("等待识别到 ${rule.expectedPage}")
                }
                if (stableFrames < stableFramesRequired) {
                    return GameSessionResetDecision.Wait("等待画面稳定")
                }
                if (nowMillis - lastStageChangeAt < clickCooldownMillis) {
                    return GameSessionResetDecision.Wait("等待点击冷却")
                }
                if (!observation.clientForeground) {
                    return GameSessionResetDecision.Wait("客户端不在前台")
                }
                return GameSessionResetDecision.Transition(rule.nextStage)
            }
        }
    }

    companion object {
        /** 默认线性流程规则：与需求流程一致，只声明页面与阶段，不硬编码坐标 */
        fun defaultRules(): List<SessionResetStageRule> = listOf(
            SessionResetStageRule(
                stage = GameSessionResetStage.WAITING_LOGIN,
                expectedPage = null,
                nextStage = GameSessionResetStage.NAVIGATING_HOME,
            ),
            SessionResetStageRule(
                stage = GameSessionResetStage.NAVIGATING_HOME,
                expectedPage = LabyrinthEntryPageState.HOME,
                nextStage = GameSessionResetStage.NAVIGATING_ADVENTURE,
            ),
            SessionResetStageRule(
                stage = GameSessionResetStage.NAVIGATING_ADVENTURE,
                expectedPage = LabyrinthEntryPageState.ADVENTURE,
                nextStage = GameSessionResetStage.NAVIGATING_DAWN_REALM,
            ),
            SessionResetStageRule(
                stage = GameSessionResetStage.NAVIGATING_DAWN_REALM,
                expectedPage = LabyrinthEntryPageState.DAWN_REALM_HOME_IDLE,
                nextStage = GameSessionResetStage.READY_FOR_NEXT_ROUND,
            ),
        )
    }
}
