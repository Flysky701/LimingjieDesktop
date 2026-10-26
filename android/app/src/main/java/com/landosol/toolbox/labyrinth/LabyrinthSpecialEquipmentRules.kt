package com.landosol.toolbox.labyrinth

import com.landosol.toolbox.labyrinth.vision.EntryPixelRect
import com.landosol.toolbox.labyrinth.vision.LabyrinthSpecialEquipmentObservation
import com.landosol.toolbox.labyrinth.vision.LabyrinthSpecialEquipmentStage

// ---------------------------------------------------------------------------------------------
// 「特别装备」自动穿戴：编组页执行状态机（纯决策，无 IO）
// ---------------------------------------------------------------------------------------------

/**
 * 本轮「特别装备」流程走到哪一步。
 *
 * 视觉层只回答「现在屏幕上是什么」（弹窗1 / 弹窗2 / 都不是），**无法区分弹窗1 是第几次到达**，
 * 所以「第几次」必须由这里的子状态记：[AWAIT_MAIN_DIALOG] 是「点过入口、等弹窗1 第一次出现」，
 * [AWAIT_SECOND_MAIN_DIALOG] 是「点过弹窗2 的确认、等弹窗1 第二次出现」。这两步之后要做的判断
 * 完全不同（前者要继续点「自动装备」，后者要按亮度判据决定点「装备确定」还是「取消」），
 * 任何靠 `stage` 值本身去猜第几次的写法都会做错。
 */
internal enum class LabyrinthSpecialEquipmentFlowStage {
    /** 本轮不执行：设置为「关闭」、本次战斗类型未勾选、尚未在编组页武装，或已经离开编组页。 */
    INACTIVE,

    /** 已武装：等编组页上的白色圆形「特别装备」入口按钮。 */
    AWAIT_ENTRY,

    /** 已点入口：等弹窗1「特别装备设定」出现。 */
    AWAIT_MAIN_DIALOG,

    /** 已点弹窗1 正文中部的白色「自动装备」：等弹窗2「自动特别装备设定」出现。 */
    AWAIT_AUTO_DIALOG,

    /** 已点弹窗2 的蓝色「确认」：等回到弹窗1（第二次到达），然后判「装备确定」的可点击性。 */
    AWAIT_SECOND_MAIN_DIALOG,

    /** 3.2：已点「装备确定」，等回到编组页。 */
    AWAIT_ACK_EQUIP,

    /** 3.1：已点左下白色「取消」（含判定为不可点击与状态未知两种情况），等回到编组页。 */
    AWAIT_ACK_CANCEL,

    /** 本轮结束（已穿戴 / 未变更 / 超限放弃）：放行开始战斗；离开编组页后回到 [INACTIVE]。 */
    DONE,
}

/** 本轮要派发的那一次点击落在哪个按钮上。 */
internal enum class LabyrinthSpecialEquipmentTap {
    ENTRY,
    AUTO_BUTTON,
    AUTO_CONFIRM,
    EQUIP_CONFIRM,
    CANCEL,
}

/**
 * 纯决策结果：下一步走到哪个 stage、这一帧要不要点、点哪个按钮、给用户的诊断消息，以及本轮
 * 是否已经结束（结束后才允许派发 START_BATTLE）。
 *
 * [tap] 只是一个**按钮身份**，不是坐标：坐标由 [labyrinthSpecialEquipmentTapRect] 从当前观测里取，
 * 那一层才是「不得点错位置」的结构性保证。
 *
 * [abandoned] 说明这次结束是**放弃**（超限、或入口缺失跳过）而不是走完 3.1/3.2：上层据此选择停机
 * 文案，并把 [message] 当作必须保留的诊断原文，不让后续帧的「本轮已结束」覆盖它。
 */
internal data class LabyrinthSpecialEquipmentDecision(
    val stage: LabyrinthSpecialEquipmentFlowStage,
    val tap: LabyrinthSpecialEquipmentTap?,
    val message: String,
    val finished: Boolean,
    val abandoned: Boolean = false,
)

internal val LabyrinthSpecialEquipmentFlowStage.label: String
    get() = when (this) {
        LabyrinthSpecialEquipmentFlowStage.INACTIVE -> "未执行"
        LabyrinthSpecialEquipmentFlowStage.AWAIT_ENTRY -> "点入口「特别装备」"
        LabyrinthSpecialEquipmentFlowStage.AWAIT_MAIN_DIALOG -> "等弹窗1「特别装备设定」"
        LabyrinthSpecialEquipmentFlowStage.AWAIT_AUTO_DIALOG -> "点「自动装备」后等弹窗2"
        LabyrinthSpecialEquipmentFlowStage.AWAIT_SECOND_MAIN_DIALOG -> "点「确认」后等回弹窗1"
        LabyrinthSpecialEquipmentFlowStage.AWAIT_ACK_EQUIP -> "点了「装备确定」后等回编组页"
        LabyrinthSpecialEquipmentFlowStage.AWAIT_ACK_CANCEL -> "点了「取消」后等回编组页"
        LabyrinthSpecialEquipmentFlowStage.DONE -> "本轮已结束"
    }

/** 每一步的派发次数上限（沿用 `MAX_BATTLE_TEAM_STEP_ATTEMPTS = 3` 的量级）。 */
internal const val MAX_SPECIAL_EQUIPMENT_STEP_ATTEMPTS = 3

/** 每一步的等待上限：次数与时间两个上限都必须在，任何一步都不得无期限等待。 */
internal const val SPECIAL_EQUIPMENT_STEP_TIMEOUT_MILLIS = 8_000L

/**
 * 「装备确定」的两态判定要求**连续这么多帧同类证据**。
 *
 * 单帧判态会判错：「弹窗2 刚关、弹窗1 正在显现」的过渡帧上，内芯两个信号都可能落在
 * 不可点击一侧（实测内芯 lum=112.4 / B−R=81.8，而真正可用时是 lum=162.5 / B−R=110.4，
 * 可用基准 162.4/110.4、不可用基准 100.0/88.5），于是会误点「取消」；下一帧就已经是
 * 可用状态。过渡帧与不可用帧在 B−R 上只差 2.1（81.8 对 88.5）——**调阈值解决不了**，
 * 只能要求跨帧稳定。
 */
internal const val SPECIAL_EQUIPMENT_EQUIP_EVIDENCE_STABLE_FRAMES = 2

/**
 * 「装备确定」两态判定的**最小时长**：自本轮当前步开始算起必须已经观察这么久。
 *
 * 帧数不等于固定时长：同样两帧，600 ms/帧时约 1.2 s，300 ms/帧时只有 0.6 s，低帧率下过渡帧
 * 还能跨两帧；反过来只按时间门，在高帧率下又会退化成单帧放行。所以两者必须同时成立。
 */
internal const val SPECIAL_EQUIPMENT_EQUIP_EVIDENCE_STABLE_MILLIS = 1_000L

/** 一帧上「装备确定」可点击性的原始证据分类（`null` 观测＝证据不足或两信号矛盾）。 */
internal enum class LabyrinthSpecialEquipmentEquipEvidence {
    ENABLED,
    DISABLED,
    UNKNOWN,
}

/**
 * 连续同类证据的追踪状态。会话每帧调用一次 [labyrinthSpecialEquipmentEquipEvidenceStreak] 更新它。
 *
 * [confirmedEnabled] 是**唯一**可以拿去判态的结论：连续 [SPECIAL_EQUIPMENT_EQUIP_EVIDENCE_STABLE_FRAMES]
 * 帧同类证据**且**已观察满 [SPECIAL_EQUIPMENT_EQUIP_EVIDENCE_STABLE_MILLIS] 之后才给 `true`/`false`，
 * 否则为 `null`（＝这一帧不下结论，见 [labyrinthSpecialEquipmentDecision] 的 `equipConfirmEnabled`
 * 参数）。「两信号矛盾」连续两帧按**不可点击**处理（保守分支）。
 */
internal data class LabyrinthSpecialEquipmentEquipEvidenceStreak(
    val evidence: LabyrinthSpecialEquipmentEquipEvidence = LabyrinthSpecialEquipmentEquipEvidence.UNKNOWN,
    val frames: Int = 0,
    val confirmedEnabled: Boolean? = null,
)

/**
 * 用本帧观测推进「连续同类证据」计数。纯函数。
 *
 * 只看弹窗1（[LabyrinthSpecialEquipmentStage.MAIN_DIALOG]）：弹窗2 打开或无弹窗时证据清零——要求
 * 点「确认」回到弹窗1 之后**重新判定**，所以回到弹窗1 的第一帧一定是 `frames = 1`、`confirmedEnabled = null`。
 *
 * [elapsedMillis] 是「自本轮当前步开始已经过了多久」，由调用方用同一时间戳算好传进来；两态结论要求
 * 它不短于 [requiredMillis]。帧数与时长必须同时满足：帧数挡过渡帧，时长把帧率变化隔离掉。
 */
internal fun labyrinthSpecialEquipmentEquipEvidenceStreak(
    previous: LabyrinthSpecialEquipmentEquipEvidenceStreak =
        LabyrinthSpecialEquipmentEquipEvidenceStreak(),
    observation: LabyrinthSpecialEquipmentObservation?,
    elapsedMillis: Long,
    requiredFrames: Int = SPECIAL_EQUIPMENT_EQUIP_EVIDENCE_STABLE_FRAMES,
    requiredMillis: Long = SPECIAL_EQUIPMENT_EQUIP_EVIDENCE_STABLE_MILLIS,
): LabyrinthSpecialEquipmentEquipEvidenceStreak {
    if (observation?.stage != LabyrinthSpecialEquipmentStage.MAIN_DIALOG) {
        return LabyrinthSpecialEquipmentEquipEvidenceStreak()
    }
    val evidence = when (observation.equipConfirmEnabled) {
        true -> LabyrinthSpecialEquipmentEquipEvidence.ENABLED
        false -> LabyrinthSpecialEquipmentEquipEvidence.DISABLED
        null -> LabyrinthSpecialEquipmentEquipEvidence.UNKNOWN
    }
    val frames = if (evidence == previous.evidence) previous.frames + 1 else 1
    return LabyrinthSpecialEquipmentEquipEvidenceStreak(
        evidence = evidence,
        frames = frames,
        // 只有连续同类证据**且**观察时间够长才下结论；矛盾的连续两帧按不可点击。
        confirmedEnabled = if (frames >= requiredFrames && elapsedMillis >= requiredMillis) {
            evidence == LabyrinthSpecialEquipmentEquipEvidence.ENABLED
        } else {
            null
        },
    )
}

/**
 * 闸门：本轮特别装备流程**没有**结束时，不得派发 START_BATTLE。
 *
 * 只有 [LabyrinthSpecialEquipmentFlowStage.INACTIVE]（本轮不执行）与
 * [LabyrinthSpecialEquipmentFlowStage.DONE]（已穿戴 / 未变更 / 超限放弃）放行——要求
 * 「宁可这一次没穿上，也不能卡死在编组页」。
 */
internal fun labyrinthSpecialEquipmentBlocksBattleStart(
    stage: LabyrinthSpecialEquipmentFlowStage,
): Boolean = stage != LabyrinthSpecialEquipmentFlowStage.INACTIVE &&
    stage != LabyrinthSpecialEquipmentFlowStage.DONE

/**
 * 这一队是否一个可用成员都没有：整个队伍没有识别出任何 `characterId`（未识别的槽位是 null）。
 *
 * Boss 多队模式下留空的页签就是这种队伍——没有可穿戴的对象，所以本轮不武装，把帧交还给既有
 * 「其余留空，开始挑战」路径。
 */
internal fun labyrinthSpecialEquipmentTeamIsEmpty(characterIds: List<String?>): Boolean =
    characterIds.none { characterId -> !characterId.isNullOrBlank() }

/**
 * 本场编组是否与上一场**普通**战斗完全相同：完全相同 ⇒ 特别装备没变，跳过本轮。
 *
 * 三个条件同时成立才算「完全相同」：
 * 1. [previousSignature] 非空（首场没有可比对象）；
 * 2. 数量相同，且逐槽都识别出了角色——任一槽未识别就无法证明是同一支队伍，宁可再穿一次；
 * 3. 角色集合相同（`characterId` 经 [labyrinthBattleTeamSignature] 规范化后逐字相同）。
 *
 * EX / Boss 与「本次战斗类型未勾选」由调用方在进入本函数之前排除。
 */
internal fun labyrinthSpecialEquipmentTeamIsUnchanged(
    characterIds: List<String?>,
    previousSignature: String?,
): Boolean {
    val previous = previousSignature?.takeIf { signature -> signature.isNotBlank() } ?: return false
    if (characterIds.isEmpty()) return false
    if (characterIds.any { characterId -> characterId.isNullOrBlank() }) return false
    val ids = characterIds.filterNotNull()
    if (ids.size != previous.split(",").count { id -> id.isNotBlank() }) return false
    return labyrinthBattleTeamSignature(ids) == previous
}

/**
 * 「特别装备」本轮的下一步决策。纯函数：只读观测与计数，不碰会话状态、不派发动作。
 *
 * @param stepAttempts 当前这一步已经派发过几次点击。
 * @param stepElapsedMillis 当前这一步已经等了多久（毫秒）。
 * @param equipConfirmEnabled **已经过跨帧稳定**的「装备确定」可点击性，取自
 *   [labyrinthSpecialEquipmentEquipEvidenceStreak] 的 `confirmedEnabled`：连续
 *   [SPECIAL_EQUIPMENT_EQUIP_EVIDENCE_STABLE_FRAMES] 帧同类证据才给 `true`/`false`，否则 `null`。
 *   `null` ＝「这一帧不下结论」：既**不点**「装备确定」，也**不点**「取消」（过渡帧上的
 *   两态证据不可信），只等下一帧；判定推迟不会破坏 3.1 分支——稳定不可用仍然会走
 *   「取消」这条路，只是晚一帧。这里刻意**不给默认值**：调用方必须显式传入稳定后的结论，避免有人
 *   拿单帧原始证据直接判态。
 *
 * 终止条件只有三个，且都在这一个函数里：
 * 1. 走完 3.2（点「装备确定」并回到编组页）→ [LabyrinthSpecialEquipmentFlowStage.DONE]；
 * 2. 走完 3.1（点「取消」并回到编组页）→ DONE，消息说明「未变更」是预期结果；
 * 3. 入口找不到、或某一步次数/时间超限 → DONE 并给出卡在哪一步、当前 stage 与观测情况。
 */
internal fun labyrinthSpecialEquipmentDecision(
    stage: LabyrinthSpecialEquipmentFlowStage,
    observation: LabyrinthSpecialEquipmentObservation?,
    stepAttempts: Int,
    stepElapsedMillis: Long,
    equipConfirmEnabled: Boolean?,
    maxStepAttempts: Int = MAX_SPECIAL_EQUIPMENT_STEP_ATTEMPTS,
    stepTimeoutMillis: Long = SPECIAL_EQUIPMENT_STEP_TIMEOUT_MILLIS,
): LabyrinthSpecialEquipmentDecision {
    if (stage == LabyrinthSpecialEquipmentFlowStage.INACTIVE) {
        return LabyrinthSpecialEquipmentDecision(stage, null, "特别装备：本轮不执行", finished = false)
    }
    if (stage == LabyrinthSpecialEquipmentFlowStage.DONE) {
        return LabyrinthSpecialEquipmentDecision(
            stage = stage,
            tap = null,
            message = "特别装备：本轮已结束，继续编组与开战",
            finished = true,
        )
    }
    val dialog = observation?.stage
    val observed = dialog?.let { "当前弹窗=${it.name}" } ?: "当前无弹窗"
    if (stepAttempts >= maxStepAttempts || stepElapsedMillis >= stepTimeoutMillis) {
        // 只要弹窗1 还开着且「取消」矩形可信，就点它退回编组页；否则只给可诊断消息
        // ，并且本轮照样结束、照样放行开战。
        val escape = if (
            dialog == LabyrinthSpecialEquipmentStage.MAIN_DIALOG &&
            observation.cancelButtonRect != null
        ) {
            LabyrinthSpecialEquipmentTap.CANCEL
        } else {
            null
        }
        val tail = if (escape != null) {
            "；点击左下白色「取消」退回编组页后照常开始战斗"
        } else {
            "；放弃本轮并照常开始战斗"
        }
        return LabyrinthSpecialEquipmentDecision(
            stage = LabyrinthSpecialEquipmentFlowStage.DONE,
            tap = escape,
            message = "特别装备：${stage.label}超限（尝试 $stepAttempts/$maxStepAttempts，" +
                "已等待 ${stepElapsedMillis}ms/$stepTimeoutMillis ms；$observed）$tail",
            finished = true,
            abandoned = true,
        )
    }
    return when (stage) {
        LabyrinthSpecialEquipmentFlowStage.AWAIT_ENTRY -> when (dialog) {
            // 入口点击已经生效，弹窗1 直接出现了。
            LabyrinthSpecialEquipmentStage.MAIN_DIALOG -> autoButtonDecision(observation)
            LabyrinthSpecialEquipmentStage.AUTO_DIALOG -> autoConfirmDecision(observation)
            null -> if (observation?.entryButtonRect != null) {
                LabyrinthSpecialEquipmentDecision(
                    stage = LabyrinthSpecialEquipmentFlowStage.AWAIT_MAIN_DIALOG,
                    tap = LabyrinthSpecialEquipmentTap.ENTRY,
                    message = "特别装备：点击编组页白色圆形入口「特别装备」",
                    finished = false,
                )
            } else {
                // 入口不可信就跳过本轮，**零点击**，并照常允许开始战斗（不能卡死整局）。
                LabyrinthSpecialEquipmentDecision(
                    stage = LabyrinthSpecialEquipmentFlowStage.DONE,
                    tap = null,
                    message = "特别装备：本次编组未找到可信的入口按钮" +
                        "（原因码 ${observation?.entryButtonMissReason ?: "未提供"}）；" +
                        "跳过本次穿戴并照常开始战斗",
                    finished = true,
                    abandoned = true,
                )
            }
        }

        LabyrinthSpecialEquipmentFlowStage.AWAIT_MAIN_DIALOG -> when (dialog) {
            LabyrinthSpecialEquipmentStage.MAIN_DIALOG -> autoButtonDecision(observation)
            LabyrinthSpecialEquipmentStage.AUTO_DIALOG -> autoConfirmDecision(observation)
            null -> waitFor(stage, "特别装备：等待「特别装备设定」弹窗出现（$observed）")
        }

        LabyrinthSpecialEquipmentFlowStage.AWAIT_AUTO_DIALOG -> when (dialog) {
            LabyrinthSpecialEquipmentStage.AUTO_DIALOG -> autoConfirmDecision(observation)
            // 还停在弹窗1：重新点「自动装备」（有次数上限兜底）。
            LabyrinthSpecialEquipmentStage.MAIN_DIALOG -> autoButtonDecision(observation)
            null -> waitFor(stage, "特别装备：等待「自动特别装备设定」弹窗出现")
        }

        LabyrinthSpecialEquipmentFlowStage.AWAIT_SECOND_MAIN_DIALOG -> when (dialog) {
            LabyrinthSpecialEquipmentStage.MAIN_DIALOG -> equipDecision(observation, equipConfirmEnabled)
            LabyrinthSpecialEquipmentStage.AUTO_DIALOG -> autoConfirmDecision(observation)
            null -> LabyrinthSpecialEquipmentDecision(
                stage = LabyrinthSpecialEquipmentFlowStage.DONE,
                tap = null,
                message = "特别装备：没有回到弹窗1 就离开了弹窗；本轮结束并继续开始战斗",
                finished = true,
            )
        }

        LabyrinthSpecialEquipmentFlowStage.AWAIT_ACK_EQUIP -> when (dialog) {
            // 「装备确定」点过之后**只等结果，不重复派发**——不回退、不重试、不额外点击。
            // 弹窗迟迟不关由本步的次数/时间上限收尾，然后走超限逃生（那一支只允许点「取消」）。
            LabyrinthSpecialEquipmentStage.MAIN_DIALOG ->
                waitFor(stage, "特别装备：已点击「装备确定」，等待返回编组页（不重复派发）")
            LabyrinthSpecialEquipmentStage.AUTO_DIALOG ->
                waitFor(stage, "特别装备：等待回到编组页（弹窗2 仍开着）")
            null -> LabyrinthSpecialEquipmentDecision(
                stage = LabyrinthSpecialEquipmentFlowStage.DONE,
                tap = null,
                message = "特别装备：已点击「装备确定」并回到编组页，继续开始战斗",
                finished = true,
            )
        }

        LabyrinthSpecialEquipmentFlowStage.AWAIT_ACK_CANCEL -> when (dialog) {
            // 3.1 已经决定「不点装备确定」，这一支**只允许**重复点「取消」。
            LabyrinthSpecialEquipmentStage.MAIN_DIALOG -> if (observation.cancelButtonRect != null) {
                LabyrinthSpecialEquipmentDecision(
                    stage = stage,
                    tap = LabyrinthSpecialEquipmentTap.CANCEL,
                    message = "特别装备：弹窗1 仍在，重新点击左下白色「取消」",
                    finished = false,
                )
            } else {
                waitFor(stage, "特别装备：等待返回编组页（未识别到「取消」矩形，不点击任何候选位置）")
            }
            LabyrinthSpecialEquipmentStage.AUTO_DIALOG ->
                waitFor(stage, "特别装备：等待回到编组页（弹窗2 仍开着）")
            null -> LabyrinthSpecialEquipmentDecision(
                stage = LabyrinthSpecialEquipmentFlowStage.DONE,
                tap = null,
                message = "特别装备：本次未变更（按 3.1 点了左下白色「取消」；" +
                    "游戏提示「特别装备未变更。」是预期结果，不是失败）；继续开始战斗",
                finished = true,
            )
        }

        LabyrinthSpecialEquipmentFlowStage.INACTIVE,
        LabyrinthSpecialEquipmentFlowStage.DONE,
        -> error("unreachable: $stage 已在上方提前返回")
    }
}

private fun waitFor(
    stage: LabyrinthSpecialEquipmentFlowStage,
    message: String,
): LabyrinthSpecialEquipmentDecision =
    LabyrinthSpecialEquipmentDecision(stage = stage, tap = null, message = message, finished = false)

/** 弹窗1：点正文中部的白色「自动装备」；矩形不可信时只等，不点候选位置。 */
private fun autoButtonDecision(
    observation: LabyrinthSpecialEquipmentObservation?,
): LabyrinthSpecialEquipmentDecision = if (observation?.autoButtonRect != null) {
    LabyrinthSpecialEquipmentDecision(
        stage = LabyrinthSpecialEquipmentFlowStage.AWAIT_AUTO_DIALOG,
        tap = LabyrinthSpecialEquipmentTap.AUTO_BUTTON,
        message = "特别装备：点击弹窗1 正文中部的白色「自动装备」",
        finished = false,
    )
} else {
    waitFor(
        LabyrinthSpecialEquipmentFlowStage.AWAIT_MAIN_DIALOG,
        "特别装备：等待识别弹窗1 的「自动装备」按钮（不点击候选位置）",
    )
}

/** 弹窗2：点蓝色「确认」；矩形不可信时只等。 */
private fun autoConfirmDecision(
    observation: LabyrinthSpecialEquipmentObservation?,
): LabyrinthSpecialEquipmentDecision = if (observation?.confirmButtonRect != null) {
    LabyrinthSpecialEquipmentDecision(
        stage = LabyrinthSpecialEquipmentFlowStage.AWAIT_SECOND_MAIN_DIALOG,
        tap = LabyrinthSpecialEquipmentTap.AUTO_CONFIRM,
        message = "特别装备：点击弹窗2 的蓝色「确认」",
        finished = false,
    )
} else {
    waitFor(
        LabyrinthSpecialEquipmentFlowStage.AWAIT_AUTO_DIALOG,
        "特别装备：等待识别弹窗2 的「确认」矩形（不点击候选位置）",
    )
}

/**
 * 回到弹窗1 之后才叫的这一步：按「装备确定」**跨帧稳定后**的可点击性分流。
 *
 * **的判定只有这一处**：`confirmedEnabled != true` 时下一个动作要么是「取消」、要么什么都不做，
 * 不存在任何把动作指向「装备确定」的路径。
 *
 * [confirmedEnabled] 的三态语义（见 [labyrinthSpecialEquipmentEquipEvidenceStreak]）：
 * - `true`：连续两帧可用 → 点蓝色「装备确定」；
 * - `false`：连续两帧不可用（含连续两帧两信号矛盾保守分支）→ 走 3.1，点左下白色「取消」；
 * - `null`：**证据还没跨帧稳定**（弹窗刚打开、或刚从弹窗2 回来）→ 这一帧只等，既不点确定也不点取消。
 *   单帧判态会在这种过渡帧上误点「取消」。
 */
private fun equipDecision(
    observation: LabyrinthSpecialEquipmentObservation?,
    confirmedEnabled: Boolean?,
): LabyrinthSpecialEquipmentDecision = when (confirmedEnabled) {
    true -> {
        if (observation?.confirmButtonRect != null) {
            LabyrinthSpecialEquipmentDecision(
                stage = LabyrinthSpecialEquipmentFlowStage.AWAIT_ACK_EQUIP,
                tap = LabyrinthSpecialEquipmentTap.EQUIP_CONFIRM,
                message = "特别装备：「装备确定」连续两帧判定为可点击，点击蓝色「装备确定」确认穿戴",
                finished = false,
            )
        } else {
            waitFor(
                LabyrinthSpecialEquipmentFlowStage.AWAIT_SECOND_MAIN_DIALOG,
                "特别装备：等待识别「装备确定」矩形（不点击候选位置）",
            )
        }
    }

    false -> {
        if (observation?.cancelButtonRect != null) {
            LabyrinthSpecialEquipmentDecision(
                stage = LabyrinthSpecialEquipmentFlowStage.AWAIT_ACK_CANCEL,
                tap = LabyrinthSpecialEquipmentTap.CANCEL,
                message = "特别装备：「装备确定」连续两帧判定为不可点击；点击左下白色「取消」返回编组页" +
                    "（预期提示「特别装备未变更。」）",
                finished = false,
            )
        } else {
            waitFor(
                LabyrinthSpecialEquipmentFlowStage.AWAIT_SECOND_MAIN_DIALOG,
                "特别装备：「装备确定」连续两帧判定为不可点击 且未识别到「取消」矩形；" +
                    "等待下一帧（绝不点击「装备确定」）",
            )
        }
    }

    null -> waitFor(
        LabyrinthSpecialEquipmentFlowStage.AWAIT_SECOND_MAIN_DIALOG,
        "特别装备：「装备确定」的两态证据尚未跨帧稳定（首帧或过渡帧一律先等一帧，按不可点击保守处理）",
    )
}

/**
 * 决策里的按钮身份 → 本帧真正可点的矩形。
 *
 * **这是的结构性保证（第二道锁）**：即便某个决策错误地返回了 [LabyrinthSpecialEquipmentTap.EQUIP_CONFIRM]，
 * 只要 [`equipConfirmEnabled`] 不是 `true`（含 `null`），这里就返回 `null` ⇒ 上层不会构造任何点击；
 * 同理，[LabyrinthSpecialEquipmentTap.AUTO_CONFIRM] 只在弹窗2 的观测上成立，弹窗1 的
 * `confirmButtonRect`（＝「装备确定」）不会被它当成「确认」用掉。
 */
internal fun labyrinthSpecialEquipmentTapRect(
    tap: LabyrinthSpecialEquipmentTap,
    observation: LabyrinthSpecialEquipmentObservation,
): EntryPixelRect? = when (tap) {
    LabyrinthSpecialEquipmentTap.ENTRY -> observation.entryButtonRect
    LabyrinthSpecialEquipmentTap.AUTO_BUTTON -> observation.autoButtonRect
    LabyrinthSpecialEquipmentTap.AUTO_CONFIRM ->
        observation.confirmButtonRect?.takeIf { observation.stage == LabyrinthSpecialEquipmentStage.AUTO_DIALOG }
    LabyrinthSpecialEquipmentTap.EQUIP_CONFIRM -> observation.confirmButtonRect?.takeIf {
        observation.stage == LabyrinthSpecialEquipmentStage.MAIN_DIALOG &&
            observation.equipConfirmEnabled == true
    }
    LabyrinthSpecialEquipmentTap.CANCEL -> observation.cancelButtonRect
}
