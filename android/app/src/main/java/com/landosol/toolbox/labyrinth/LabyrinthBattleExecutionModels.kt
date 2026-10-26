package com.landosol.toolbox.labyrinth



enum class LabyrinthCombatKind { NORMAL, EX, BOSS }

/** Context carried across challenge, team editor, battle and result pages. */
data class LabyrinthCombatContext(
    val kind: LabyrinthCombatKind,
    val teamIndex: Int = 1,
    /** Values greater than three share the 3+ target combat model. */
    val targetCount: Int = 1,
) {
    init {
        require(teamIndex >= 1)
        require(targetCount >= 1)
    }
}

internal fun labyrinthBattleKindLabel(kind: LabyrinthCombatKind): String = when (kind) {
    LabyrinthCombatKind.NORMAL -> "普通战"
    LabyrinthCombatKind.EX -> "EX战"
    LabyrinthCombatKind.BOSS -> "Boss战"
}

/**
 * 编组页两次动作之间的点击防抖间隔，本会话内**任意**两次点击（特别装备流程与编组/开战共用）都受它约束。
 *
 * 取值 600 ms：识别节拍是 300 ms，600 ms 正好是一帧，不必再空等两帧；同时仍能把「弹窗关闭动画还没收完
 * 就点下一处」这类插队挡在间隔之外。真正的兜底是动作被吞之后的回收重试，间隔只负责降低吞点击的概率。
 */
internal const val BATTLE_TEAM_ACTION_INTERVAL_MILLIS = 600L

/**
 * 「编组页动作已经派发、画面却还没有推进」这一件事的全部状态：START_BATTLE 与 NEXT_BOSS_TEAM
 * 共用同一处记账（不回滚在各处各打一块补丁）。
 *
 * 只有「从派发那一刻起每一帧都还是同一个编组页签名」才算「画面没动」——游戏只要收下了这一下，
 * 画面就会换页或换 Boss 页签。超过 [BATTLE_TEAM_ACTION_ACK_TIMEOUT_MILLIS] 即认定这一下没被接受，
 * 回滚本次提交并重新规划；重复失败落在 [MAX_BATTLE_TEAM_ACTION_UNCONFIRMED] 次以内。
 */
internal data class PendingBattleTeamAction(
    val kind: LabyrinthBattleTeamExecutionKind,
    val label: String,
    val confirmedTeamIds: List<String>,
    val signature: String,
    val dispatchedAt: Long,
    /**
     * 派发这一刻之前 [lastNormalBattleTeamSignature] 的值（只有 START_BATTLE 会带）。
     *
     * 「派发后画面没推进」说明这一下没被游戏收下：回收把记忆还原成这个当次捕获的原值，也就是
     * **最近一次派发之前**的值。重试链上的每一次派发都按同一规则重新捕获，因此它不等同于
     * 「最近一场真正开起来的战斗」的编组。
     */
    val previousNormalTeamSignature: String? = null,
)
