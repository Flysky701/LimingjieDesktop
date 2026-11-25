package com.landosol.toolbox.automation.session

import com.landosol.toolbox.labyrinth.vision.LabyrinthAnchorScores

/**
 * 会话阻塞弹窗识别。复用现有锚点评分模型（[LabyrinthAnchorScores]），
 * 用一组独立锚点判断「需要重新登录」「重新连接/错误提示」等阻塞弹窗，优先于页面分类。
 *
 * 锚点坐标由视觉资源包/校准数据提供，不在模块内硬编码具体按钮位置。
 * 该分类器只输出阻塞类型，不产生点击动作。
 */
data class SessionBlockAnchorScores(
    val relogin: Double,
    val reconnect: Double,
    val prompt: Double,
) {
    init {
        require(listOf(relogin, reconnect, prompt).all { it in 0.0..1.0 })
    }
}

class SessionBlockClassifier(
    private val minScore: Double = 0.55,
    private val minMargin: Double = 0.08,
) {
    init {
        require(minScore in 0.0..1.0)
        require(minMargin in 0.0..1.0)
    }

    fun classify(scores: SessionBlockAnchorScores, pageTitleScore: Double): SessionBlockKind {
        val reloginV = scores.relogin
        val reconnectV = scores.reconnect
        val promptV = scores.prompt

        if (reloginV < minScore && reconnectV < minScore && promptV < minScore) {
            return SessionBlockKind.NONE
        }
        val best = maxOf(reloginV, reconnectV, promptV)
        val second = listOf(reloginV, reconnectV, promptV).sortedDescending()[1]
        if (best - second < minMargin) return SessionBlockKind.UNKNOWN_PROMPT
        // 标题或强主流程页面得分更高时，视为正常流程，避免误报阻塞
        if (pageTitleScore > best) return SessionBlockKind.NONE
        return when {
            reloginV == best -> SessionBlockKind.RELOGIN_REQUIRED
            reconnectV == best -> SessionBlockKind.RECONNECT_PROMPTED
            else -> SessionBlockKind.UNKNOWN_PROMPT
        }
    }
}

/** 从通用锚点评分映射出阻塞相关子分（模板缺失时为 0，不阻塞流程） */
fun LabyrinthAnchorScores.toSessionBlockScores(): SessionBlockAnchorScores = SessionBlockAnchorScores(
    relogin = values["session.relogin"] ?: 0.0,
    reconnect = values["session.error.title"] ?: 0.0,
    prompt = values["session.prompt"] ?: 0.0,
)