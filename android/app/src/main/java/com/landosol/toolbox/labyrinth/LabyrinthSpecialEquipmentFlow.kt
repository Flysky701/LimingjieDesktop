package com.landosol.toolbox.labyrinth

import com.landosol.toolbox.labyrinth.vision.EntryPixelRect
import com.landosol.toolbox.labyrinth.vision.LabyrinthBattleTeamRecognitionState
import com.landosol.toolbox.labyrinth.vision.LabyrinthEntryFrameResult
import com.landosol.toolbox.labyrinth.vision.LabyrinthEntryPageState

/** 启动快照与当前编组的只读投影；流程不读取 Session 或 UI。 */
internal data class LabyrinthSpecialEquipmentContext(
    val appliesToBattle: Boolean,
    val multiTeamBoss: Boolean,
    val normalBattle: Boolean,
    val teamReady: Boolean,
    val previousNormalTeamSignature: String?,
    val actionIntervalElapsed: Boolean,
)

/** 同步动作准入仍归会话所有；true 只表示已派发，不表示游戏已确认。 */
internal interface LabyrinthSpecialEquipmentEffects {
    fun tryDispatch(rect: EntryPixelRect, label: String): Boolean
    fun publishMessage(message: String)
    fun stop(reason: String)
}

internal enum class LabyrinthSpecialEquipmentFrameOutcome(val ownsFrame: Boolean) {
    RELEASED(false), WAITING(true), ACTION_DISPATCHED(true), STOP_REQUESTED(true),
}

/**
 * 特别装备一轮的编排逻辑。状态通过接口读取，以兼容原会话字段。
 * 不依赖协程、设备、数据库、UI 或会话；现有识别与纯决策函数保持不变。
 * 由会话按原有帧顺序调用，跨流程动作间隔与会话有效性在调用端校验。
 */
internal interface LabyrinthSpecialEquipmentState {
    var specialEquipmentFlowStage: LabyrinthSpecialEquipmentFlowStage
    var specialEquipmentStepAttempts: Int
    var specialEquipmentStepStartedAt: Long
    var specialEquipmentDialogObserved: Boolean
    var specialEquipmentModalCloseSettlePending: Boolean
    var specialEquipmentAwayFrames: Int
    var specialEquipmentPendingEscape: LabyrinthSpecialEquipmentTap?
    var specialEquipmentEscapeDeadline: Long
    var specialEquipmentDiagnostic: String?
    var specialEquipmentEquipEvidence: LabyrinthSpecialEquipmentEquipEvidenceStreak
    var specialEquipmentAbandoned: Boolean
    var specialEquipmentRoundBossTeamIndex: Int?
}

internal class LabyrinthSpecialEquipmentFlow(
    state: LabyrinthSpecialEquipmentState,
) : LabyrinthSpecialEquipmentState by state {
    val active: Boolean get() = specialEquipmentFlowStage != LabyrinthSpecialEquipmentFlowStage.INACTIVE

    fun progress(): LabyrinthSpecialEquipmentProgress = LabyrinthSpecialEquipmentProgress(
        stage = specialEquipmentFlowStage,
        attempts = specialEquipmentStepAttempts,
        pendingEscape = specialEquipmentPendingEscape,
        closeAnimationPending = specialEquipmentModalCloseSettlePending,
        bossTeamIndex = specialEquipmentRoundBossTeamIndex,
    )

    fun consumeModalCloseSettle(): Boolean {
        if (!specialEquipmentModalCloseSettlePending) return false
        specialEquipmentModalCloseSettlePending = false
        return true
    }

    /** 清掉本轮标记（离开编组页、页签更换、或本轮不适用时调用）。 */
    fun reset() {
        specialEquipmentFlowStage = LabyrinthSpecialEquipmentFlowStage.INACTIVE
        specialEquipmentStepAttempts = 0
        specialEquipmentStepStartedAt = Long.MIN_VALUE
        specialEquipmentAwayFrames = 0
        specialEquipmentPendingEscape = null
        specialEquipmentEscapeDeadline = Long.MIN_VALUE
        specialEquipmentDiagnostic = null
        specialEquipmentAbandoned = false
        specialEquipmentDialogObserved = false
        specialEquipmentModalCloseSettlePending = false
        specialEquipmentEquipEvidence = LabyrinthSpecialEquipmentEquipEvidenceStreak()
        specialEquipmentRoundBossTeamIndex = null
    }

    private fun specialEquipmentTapLabel(tap: LabyrinthSpecialEquipmentTap): String = when (tap) {
        LabyrinthSpecialEquipmentTap.ENTRY -> "特别装备：点击入口「特别装备」"
        LabyrinthSpecialEquipmentTap.AUTO_BUTTON -> "特别装备：点击「自动装备」"
        LabyrinthSpecialEquipmentTap.AUTO_CONFIRM -> "特别装备：点击弹窗2「确认」"
        LabyrinthSpecialEquipmentTap.EQUIP_CONFIRM -> "特别装备：点击「装备确定」"
        LabyrinthSpecialEquipmentTap.CANCEL -> "特别装备：点击「取消」"
    }

    /** 决策里的一次点击按按钮称呼（不含动作前缀，用来拼「等待后补发…」这类诊断消息）。 */
    private fun specialEquipmentTapName(tap: LabyrinthSpecialEquipmentTap): String = when (tap) {
        LabyrinthSpecialEquipmentTap.ENTRY -> "入口「特别装备」"
        LabyrinthSpecialEquipmentTap.AUTO_BUTTON -> "「自动装备」"
        LabyrinthSpecialEquipmentTap.AUTO_CONFIRM -> "弹窗2「确认」"
        LabyrinthSpecialEquipmentTap.EQUIP_CONFIRM -> "「装备确定」"
        LabyrinthSpecialEquipmentTap.CANCEL -> "「取消」"
    }

    /**
     * 「特别装备」本轮的执行钩子。ownsFrame 表示这一帧由本流程接管（已派发点击或正在等待），调用处
     * 必须立刻 return，**不得**再产出任何编组执行步——三条开始战斗路径都由这一点统一切断。
     *
     * RELEASED 的三种情况都保持既有行为不变：
     * 1. 本轮不适用（设置为「关闭」/ 本次战斗类型未勾选 / 不知道类型）；
     * 2. 已经稳定地离开编组页（顺带清掉本轮标记，下次进入编组页是新的一轮）；
     * 3. 本轮已经结束，而且编组页没有被弹窗挡住（放行开战）。
     */
    fun onFrame(
        result: LabyrinthEntryFrameResult,
        timestampMillis: Long,
        context: LabyrinthSpecialEquipmentContext,
        effects: LabyrinthSpecialEquipmentEffects,
    ): LabyrinthSpecialEquipmentFrameOutcome {
        val observation = result.specialEquipment
        val pageState = result.observation.state
        val dialogOpen = observation?.stage != null
        val onTeamPage = pageState == LabyrinthEntryPageState.BATTLE_TEAM_SELECTION

        // 弹窗1/弹窗2 与 UNKNOWN 都不算离开编组页；只有**连续**若干帧落在别的页面才算「稳定
        // 地离开」。单帧即清标记是错的：弹窗关合动画、Boss 切队、结算过渡都会插进来一两帧误分类，
        // 那样会重新武装并在同一轮里再跑一遍。
        //
        // 这一支一律不吃帧（返回 false），UNKNOWN 也不例外。
        // 它位于下面决策调用的前面，吃帧就等于把这一帧从两个上限体系里摘出去——本流程自己的 8s/3 次
        // 上限根本走不到，而 2403-2408 的早返回又让 handlePostEntryPage（120s 停滞看门狗唯一推进的
        // 地方）看不到它 ⇒ 又变回无界等待。交还帧就够：**标记保留**本身已经满足
        // （UNKNOWN 不算离开、单帧不算离开），下一次看到真实页面再决定是否继续本轮。
        if (!onTeamPage && !dialogOpen) {
            specialEquipmentAwayFrames =
                if (pageState == LabyrinthEntryPageState.UNKNOWN) 0 else specialEquipmentAwayFrames + 1
            if (
                specialEquipmentAwayFrames >= SPECIAL_EQUIPMENT_LEAVE_STABLE_FRAMES &&
                specialEquipmentFlowStage != LabyrinthSpecialEquipmentFlowStage.INACTIVE
            ) {
                reset()
            }
            return LabyrinthSpecialEquipmentFrameOutcome.RELEASED
        }
        specialEquipmentAwayFrames = 0

        // 设置关闭、或本次战斗类型未勾选时完全不介入——零点击、不新增等待、不新增中间态。
        if (!context.appliesToBattle) {
            if (specialEquipmentFlowStage != LabyrinthSpecialEquipmentFlowStage.INACTIVE) {
                reset()
            }
            return LabyrinthSpecialEquipmentFrameOutcome.RELEASED
        }

        // Boss 多队编组页的队伍标签切换**不是**同一轮：每个页签是一支独立的队伍，页签一变上一轮就
        // 作废——让当前页签走完自己的一整轮（入口 → 自动装备 → 弹窗2 确认 → 装备确定/取消），上一个
        // 页签的结束标记不再替它挡住穿戴。单队模式整段只跑一轮，页签不参与身份，行为不变。
        //
        // 只在上一轮**已经结束**（DONE）时才按页签重开：本轮还在走的时候被一帧误分类的页签打断，
        // 就会回到「重新武装并在同一轮里再跑一遍」的老问题；在走的轮次本来也有 8s/3 次上限兜底。
        val bossMultiTeamTab = context.multiTeamBoss && result.battleTeamSelection?.bossTeamIndex != null
        val bossTeamIndex = result.battleTeamSelection?.bossTeamIndex
        if (
            bossMultiTeamTab &&
            specialEquipmentFlowStage == LabyrinthSpecialEquipmentFlowStage.DONE &&
            specialEquipmentRoundBossTeamIndex != null &&
            bossTeamIndex != specialEquipmentRoundBossTeamIndex
        ) {
            reset()
        }

        // 武装：只在编组页且队伍页已稳定识别时武装一次。
        if (specialEquipmentFlowStage == LabyrinthSpecialEquipmentFlowStage.INACTIVE) {
            if (!onTeamPage) {
                // 没有在编组页武装过就不启动（例如用户手动打开了这个弹窗）：只留一条诊断消息。
                effects.publishMessage("特别装备：识别到弹窗，但本轮尚未在编组页武装，不自动点击")
                return LabyrinthSpecialEquipmentFrameOutcome.RELEASED
            }
            if (
                result.battleTeamSelection?.recognitionState !=
                LabyrinthBattleTeamRecognitionState.STABLE
            ) {
                return LabyrinthSpecialEquipmentFrameOutcome.RELEASED
            }
// **推荐阵容就位之前不武装、更不得点入口**：成员槽还空着、总战力 0、画面中央是
// 「请选择角色。」时点入口，游戏会弹「请选择角色。」，特别装备弹窗不会出现。
//
// 判据是编组计划的 teamReady（推荐阵容已全部就位），用法与 buildOverlayPresentation 一致，
// 刻意**不**要求 5 人齐全：「保持当前队伍」的场景在 teamReady 变成 true 之后照常进入。
// 未就绪就返回 false 把这一帧交还既有编组路径——它才负责把角色选上、把计划推到 ready。
//
// 入口 ROI 在实机帧上命中正确：圆形按钮位置与标定一致，还带点击反馈圈。
//
            if (!context.teamReady) return LabyrinthSpecialEquipmentFrameOutcome.RELEASED
            // Boss 多队模式下没有成员的页签没有可穿戴的对象：不武装、零点击，把帧交还给既有
            // 「其余留空，开始挑战」路径。
            if (bossMultiTeamTab && labyrinthSpecialEquipmentTeamIsEmpty(result.battleTeamSelection?.selectedCharacters.orEmpty().map { it.characterId })) {
                return LabyrinthSpecialEquipmentFrameOutcome.RELEASED
            }
            // 普通关卡与上一场普通战斗的编组完全相同 ⇒ 装备没变，跳过本轮：不武装、不点入口，
            // 把帧交还给既有编组/开战路径，只留一条可诊断消息。
            if (context.normalBattle && labyrinthSpecialEquipmentTeamIsUnchanged(
                    result.battleTeamSelection?.selectedCharacters.orEmpty().map { it.characterId },
                    context.previousNormalTeamSignature,
                )) {
                effects.publishMessage("特别装备：本场编组与上一场普通战斗完全相同，跳过本次穿戴")
                return LabyrinthSpecialEquipmentFrameOutcome.RELEASED
            }
            specialEquipmentFlowStage = LabyrinthSpecialEquipmentFlowStage.AWAIT_ENTRY
            specialEquipmentStepAttempts = 0
            specialEquipmentStepStartedAt = timestampMillis
            specialEquipmentRoundBossTeamIndex = bossTeamIndex.takeIf { bossMultiTeamTab }
        }

// 两态判定必须先**跨帧稳定**：这一帧的原始证据只用来推进连续
        // 计数：只有连续 SPECIAL_EQUIPMENT_EQUIP_EVIDENCE_STABLE_FRAMES 帧同类证据、且自本步开始已观察满
        // SPECIAL_EQUIPMENT_EQUIP_EVIDENCE_STABLE_MILLIS 才把结论交给决策（帧数挡过渡帧，时长把帧率变化隔离掉），
        // 首帧/过渡帧拿到的是 null ⇒ 决策只等一帧，既不点「装备确定」也不点「取消」。
        val stepElapsedMillis = if (specialEquipmentStepStartedAt == Long.MIN_VALUE) {
            0L
        } else {
            (timestampMillis - specialEquipmentStepStartedAt).coerceAtLeast(0L)
        }
        val equipEvidence = labyrinthSpecialEquipmentEquipEvidenceStreak(
            previous = specialEquipmentEquipEvidence,
            observation = observation,
            elapsedMillis = stepElapsedMillis,
        )
        specialEquipmentEquipEvidence = equipEvidence

        val decision = labyrinthSpecialEquipmentDecision(
            stage = specialEquipmentFlowStage,
            observation = observation,
            stepAttempts = specialEquipmentStepAttempts,
            stepElapsedMillis = stepElapsedMillis,
            equipConfirmEnabled = equipEvidence.confirmedEnabled,
        )
        // 只在本轮真正（武装后）看到过弹窗的帧上记账：用户手动打开的弹窗不引入任何新等待。
        if (dialogOpen) specialEquipmentDialogObserved = true

        // 结束但**带点击**的决策只有一种：超限逃生（点弹窗1 左下白色「取消」退回编组页）。
        // 结束判定必须先落下（stage=DONE），否则下一帧的 DONE 决策不会再带上这次点击；点击本身记成
        // 待办，等动作间隔到期补发——这是「记成待办再补发」的那一支，与下面「派发成功再提交」互补。
        if (decision.finished) {
            if (decision.abandoned) {
                // 超限/放弃的**诊断原文**：后续帧不得用「本轮已结束，继续编组与开战」把它覆盖，
                // 它同时决定停机文案该说「已放弃」还是「已正常结束」。
                if (specialEquipmentDiagnostic == null) specialEquipmentDiagnostic = decision.message
                specialEquipmentAbandoned = true
            }
            if (decision.tap != null) {
                specialEquipmentPendingEscape = decision.tap
                // 待办本身也有窗口：窗口内始终派发不出去（例如单动作锁一直被占）就放弃这次点击，
                // 交给下面「本轮已结束但弹窗仍开着」的分支停机并保留诊断，不引入无界等待。
                specialEquipmentEscapeDeadline =
                    timestampMillis + SPECIAL_EQUIPMENT_ESCAPE_TIMEOUT_MILLIS
            }
        }
        if (
            specialEquipmentPendingEscape != null &&
            specialEquipmentEscapeDeadline != Long.MIN_VALUE &&
            timestampMillis >= specialEquipmentEscapeDeadline
        ) {
            specialEquipmentPendingEscape = null
        }

        val tap = decision.tap ?: specialEquipmentPendingEscape
        val rect = tap?.let { current -> observation?.let { labyrinthSpecialEquipmentTapRect(current, it) } }
        val tapReady = rect != null && context.actionIntervalElapsed
        // **「决定要点击」不等于「点击已经派发」**：会话动作间隔（`BATTLE_TEAM_ACTION_INTERVAL_MILLIS`）
        // 与单动作锁都可能把这一下挡在派发之前。此时若把 stage 推进到下一状态，就等于宣布「已经点过
        // 了」——下一帧的决策会从下一状态继续等一个永远不会出现的画面，入口与「装备确定」都是这样丢的。
        // 所以 stage 只在「本帧没有待派发的点击」或「待派发的点击确实派发成功」之后才提交；被挡下的
        // 这一帧保持原 stage、步进次数与步进起点不变，间隔到期后同一决策会重新算出来补发**一次**。
        // 补发同样受 [MAX_SPECIAL_EQUIPMENT_STEP_ATTEMPTS] 与 [SPECIAL_EQUIPMENT_STEP_TIMEOUT_MILLIS]
        // 约束（次数只在真的派发时增加，超时按原来的步进起点计时），因此不会变成新的无界等待。
        if (tap == null || decision.finished) {
            commitSpecialEquipmentStage(decision, timestampMillis)
        }
        // 1) 有待派发的点击，但这一帧派不出去（动作间隔未到 / 矩形不可信）⇒ 只等，stage 不动
        //    （结束决策的 DONE 与逃生待办已经落下了）。间隔到期后同一决策会再算出来补发。
        if (tap != null && !tapReady) {
            effects.publishMessage(
                when {
                    // 矩形可信 ⇒ 唯一的拦路者就是会话动作间隔。
                    rect != null -> "特别装备：动作间隔未到，等待后补发点击${specialEquipmentTapName(tap)}"
                    // 结束决策的待办在等矩形：诊断原文优先，别用「本轮已结束，继续编组与开战」盖掉超限原因。
                    decision.finished -> specialEquipmentDiagnostic ?: decision.message
                    else -> decision.message
                },
            )
            return LabyrinthSpecialEquipmentFrameOutcome.WAITING
        }

        // 2) 没有待派发的点击。
        if (tap == null) {
            if (!decision.finished) {
                // 3) 本轮还没结束：没有可信矩形就只等（不点候选位置）；
                //    次数与时间上限都在决策函数里兜底。
                effects.publishMessage(decision.message)
                return LabyrinthSpecialEquipmentFrameOutcome.WAITING
            }
            if (!dialogOpen) {
                // 编组页干净：把这一帧交还既有编组/开战路径（放行开战）。
                // 交还帧时把逃生窗口一并收掉，否则 pendingEscape/escapeDeadline 会跨轮残留，
                // 8s 窗口内若再出现弹窗就会多打一次「取消」。
                specialEquipmentPendingEscape = null
                specialEquipmentEscapeDeadline = Long.MIN_VALUE
                effects.publishMessage(decision.message)
                if (specialEquipmentDialogObserved) {
                    // 本轮刚关掉过弹窗：把「关闭后的第一帧」让给关闭动画——这一刻点在「战斗开始」上
                    // 会被动画吃掉。只让一帧，由 handleBattleTeamSelectionExecution 消费。
                    specialEquipmentDialogObserved = false
                    specialEquipmentModalCloseSettlePending = true
                }
                return LabyrinthSpecialEquipmentFrameOutcome.RELEASED
            }
            // 本轮已结束、弹窗却仍开着，而且没有可以派发的逃生点击（待办已清或矩形不可信）。再吃帧就
            // 变成「安静地永久停在编组页」：既逃不出弹窗也不停机、也没有「卡在哪一步」的诊断。
            // 停机时必须保留超限诊断原文，不得用「本轮已结束，继续编组与开战」把它盖掉。
            val diagnostic = specialEquipmentDiagnostic ?: decision.message
            effects.publishMessage(diagnostic)
            if (
                specialEquipmentEscapeDeadline != Long.MIN_VALUE &&
                timestampMillis < specialEquipmentEscapeDeadline
            ) {
                // 逃生点击已经派发出去，给它一个等弹窗关闭的窗口；窗口内不重复点击、也不停机。
                return LabyrinthSpecialEquipmentFrameOutcome.WAITING
            }
            effects.stop(
                if (specialEquipmentAbandoned) {
                    "特别装备：已放弃本轮且弹窗仍未关闭（无可用逃生矩形）；已停止并保留诊断状态；$diagnostic"
                } else {
                    // 本轮是**正常结束**（没有放弃诊断），不得把它误报成「超限放弃」。
                    "特别装备：本轮已结束但弹窗仍开着且没有可用逃生矩形；已停止并保留诊断状态；$diagnostic"
                },
            )
            return LabyrinthSpecialEquipmentFrameOutcome.STOP_REQUESTED
        }

        // 4) 有待派发的点击，而且这一帧满足派发条件 ⇒ **真的派发出去之后再提交 stage**。
        effects.publishMessage(
            if (decision.finished) specialEquipmentDiagnostic ?: decision.message else decision.message,
        )
        if (
            !effects.tryDispatch(
                rect = requireNotNull(rect),
                label = specialEquipmentTapLabel(tap),
            )
        ) {
            // 单动作锁被占：这一下没有落到画面上，与「动作间隔未到」同等处理——stage 不前进（结束决策
            // 除外，它的 DONE 与逃生待办在前面的结束判定里已经落下），待办保留，下一次间隔到期后补发。
            return LabyrinthSpecialEquipmentFrameOutcome.WAITING
        }
        if (!decision.finished) {
            commitSpecialEquipmentStage(decision, timestampMillis)
        }
        if (decision.tap != null) {
            specialEquipmentStepAttempts++
        }
        if (decision.finished) {
            // 逃生点击**只点一次**——派发成功后立刻清待办，否则下一个动作间隔（≥1.2s）到来时残留的
            // pendingEscape 会再点一次「取消」，与本段的语义和注释都不符。
            specialEquipmentPendingEscape = null
            specialEquipmentEscapeDeadline =
                timestampMillis + SPECIAL_EQUIPMENT_ESCAPE_TIMEOUT_MILLIS
        }
        return LabyrinthSpecialEquipmentFrameOutcome.ACTION_DISPATCHED
    }

    /**
     * 提交决策给出的 stage（stage 变化时同时把步进次数与步进起点重置到本帧）。
     *
     * 只允许在「本帧没有待派发的点击」或「待派发的点击确实派发成功」之后调用：stage 的推进本身就
     * 声明了「这一步的动作已经派发过」，在点击被会话节流或单动作锁挡下时提交它，会让下一帧的决策
     * 从一个没人执行过的状态继续等下去。
     */
    private fun commitSpecialEquipmentStage(
        decision: LabyrinthSpecialEquipmentDecision,
        timestampMillis: Long,
    ) {
        if (decision.stage != specialEquipmentFlowStage) {
            specialEquipmentStepAttempts = 0
            specialEquipmentStepStartedAt = timestampMillis
        }
        specialEquipmentFlowStage = decision.stage
    }

}

/**
 * 连续多少帧落在非编组页才算「稳定地离开编组页」。
 *
 * 取 2 与 `POST_ENTRY_STABLE_FRAMES` / `SESSION_BLOCK_STABLE_FRAMES` 同量级：弹窗关合动画、Boss 切队、
 * 战斗结算过渡都会插进来一两帧误分类，而 UNKNOWN 根本不累计。
 */
internal const val SPECIAL_EQUIPMENT_LEAVE_STABLE_FRAMES = 2

/** 超限逃生点击（点弹窗1「取消」）之后等弹窗关闭的上限；超期仍未关就停机并保留诊断。 */
internal const val SPECIAL_EQUIPMENT_ESCAPE_TIMEOUT_MILLIS = 8_000L
