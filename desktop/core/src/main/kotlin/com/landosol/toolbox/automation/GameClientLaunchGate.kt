package com.landosol.toolbox.automation

enum class GameLaunchDecision { ALREADY_FOREGROUND, LAUNCH_GAME, BLOCKED }

/**
 * 启动闸门：目标游戏包已在前台时不得重发启动 Intent。
 * 重发会拉起 SplashActivity，PCR 随即弹出 PermissionActivity，
 * MainActivity 被 stop、Surface 消失 —— 即历史上的小米黑屏。
 * 无障碍 Activity 名可能停留在已经关闭的 SDK 公告页，不能用它判断游戏能否接管。
 * 接管只启动画面识别；是否点击仍由页面规划器和动作后端的前台包校验决定。
 */
object GameClientLaunchGate {
    fun decide(targetPackage: String?, foregroundPackage: String?): GameLaunchDecision {
        if (targetPackage == null) return GameLaunchDecision.BLOCKED
        if (foregroundPackage == targetPackage) {
            return GameLaunchDecision.ALREADY_FOREGROUND
        }
        return GameLaunchDecision.LAUNCH_GAME
    }

    fun execute(decision: GameLaunchDecision, launch: () -> Boolean): Boolean = when (decision) {
        GameLaunchDecision.ALREADY_FOREGROUND -> true
        GameLaunchDecision.LAUNCH_GAME -> launch()
        GameLaunchDecision.BLOCKED -> false
    }
}
