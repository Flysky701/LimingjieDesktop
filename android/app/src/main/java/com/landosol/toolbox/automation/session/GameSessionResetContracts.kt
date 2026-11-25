package com.landosol.toolbox.automation.session

/**
 * 客户端会话重置机制的契约定义。
 *
 * 普通 Android 应用没有权限调用 `am force-stop` 关闭其他应用（该命令要求 root、
 * shell 或 device owner 权限）。因此“关闭客户端”必须抽象为可插拔的终止策略：
 * 开发阶段使用 ADB 桥接，最终无 Root 版本使用「最近任务划卡」「游戏内登出」
 * 「用户协助」的降级链。本文件只定义接口与数据，不包含任何 Android 实现。
 */

enum class ClientTerminationKind {
    /** 强制停止：需要 root / shell / device owner（仅开发阶段 ADB 可用） */
    FORCE_STOP,

    /** 最近任务划卡杀死进程：通过无障碍服务打开最近任务并划掉游戏卡片，无 Root */
    RECENTS_SWIPE,

    /** 游戏内主动登出：通过视觉锚点驱动菜单 → 设置 → 退出登录，回到标题页 */
    GRACEFUL_LOGOUT,

    /** 会话失效触发：进入冒险→黎明界触发「会话失效」弹窗并确认返回标题页，无 Root */
    SESSION_EXPIRY,

    /** 仅重启 Activity：不杀进程，以全新任务栈重新启动客户端 */
    RELAUNCH_ONLY,

    /** 用户协助：暂停并提示用户手动关闭游戏，确认后继续 */
    USER_ASSIST,
}

enum class ClientTerminationResult {
    /** 客户端已确认停止（或已确认进程消失） */
    TERMINATED,

    /** 客户端不在前台或确认已停止，无需进一步操作 */
    ALREADY_GONE,

    /** 本策略不可用（例如缺少权限、模板未配置） */
    UNSUPPORTED,

    /** 达到超时仍未确认停止，需要降级到下一个策略 */
    TIMEOUT,

    /** 用户拒绝或取消了协助请求 */
    REJECTED,
}

enum class GameClientRelaunchResult {
    /** 已发出启动请求 */
    LAUNCH_REQUESTED,

    /** 游戏未安装或启动意图不可用 */
    LAUNCH_UNAVAILABLE,
}

/**
 * 客户端进程在场状态观察者。终止动作必须以“客户端确实不在场”为完成条件，
 * 不能只凭“发出了命令”就认为完成。
 */
interface GameClientPresenceObserver {
    /** 游戏客户端当前是否处于前台 */
    suspend fun isGameForeground(): Boolean
}

/** 由无障碍服务的前台包名观察实现的无 Root 在场检测 */
class AccessibilityForegroundPresenceObserver(
    private val gamePackageName: () -> String,
    private val foregroundPackage: () -> String?,
) : GameClientPresenceObserver {
    override suspend fun isGameForeground(): Boolean =
        foregroundPackage() == gamePackageName()
}

/** 测试用在场观察者 */
class FixedGameClientPresenceObserver(
    private val foreground: Boolean,
) : GameClientPresenceObserver {
    override suspend fun isGameForeground(): Boolean = foreground
}

/**
 * 客户端终止策略。每个策略只负责“让客户端进程消失”，确认逻辑统一由
 * [GameSessionResetWorkflow] 通过 [GameClientPresenceObserver] 完成。
 */
interface GameClientTerminator {
    val kind: ClientTerminationKind
    val lastDiagnosticStep: String? get() = null

    /** 触发终止动作，不做确认（确认交给 workflow 的在场观察） */
    suspend fun terminate(): ClientTerminationResult

    /** 该策略在当前设备上是否可用（例如无障碍服务已连接、模板已配置） */
    fun isSupported(): Boolean
}

/** 客户端重启策略 */
interface GameClientRelauncher {
    suspend fun relaunch(): GameClientRelaunchResult
}
