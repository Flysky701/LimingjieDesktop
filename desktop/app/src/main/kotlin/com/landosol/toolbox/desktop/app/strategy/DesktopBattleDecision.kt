package com.landosol.toolbox.desktop.app.strategy

import com.landosol.toolbox.labyrinth.LabyrinthBattleTeamRecommendation
import com.landosol.toolbox.labyrinth.LabyrinthCombatContext
import com.landosol.toolbox.labyrinth.LabyrinthCombatKind
import com.landosol.toolbox.labyrinth.kernel.strategy.BattleModelDecisionStatus
import com.landosol.toolbox.labyrinth.summary

internal fun battleDecisionPrefix(combat: LabyrinthCombatContext?, questId: Int? = null): String {
    val battle = when (combat?.kind) {
        LabyrinthCombatKind.NORMAL -> "普通战"
        LabyrinthCombatKind.EX -> "EX战"
        LabyrinthCombatKind.BOSS -> "Boss第${combat.teamIndex}队"
        null -> "战斗"
    }
    return "[${battle}${questId?.let { " · 关卡$it" }.orEmpty()}]"
}

internal fun LabyrinthBattleTeamRecommendation.desktopDecisionText(combat: LabyrinthCombatContext?): String {
    val trace = modelTrace
    val source = when (trace?.status) {
        BattleModelDecisionStatus.ADOPTED -> "NN选队"
        BattleModelDecisionStatus.LOW_CONFIDENCE_FALLBACK -> "NN低胜率回退规则"
        BattleModelDecisionStatus.LOAD_OR_INFERENCE_FAILED -> "NN异常回退规则"
        else -> "规则编组"
    }
    val assessment = modelAssessment?.summary(plannedBossTeamCount)
    val explanation = listOfNotNull(
        assessment,
        when {
            trace == null -> null
            trace.status == BattleModelDecisionStatus.ADOPTED -> trace.detail?.takeIf { it != assessment && it.isNotBlank() }
            trace.status == BattleModelDecisionStatus.NO_QUEST_ID && combat?.kind == LabyrinthCombatKind.NORMAL ->
                "普通战无questId，按规则编组"
            else -> trace.detail ?: trace.status.label
        },
    ).joinToString(" · ")
    val team = members.joinToString("、") { it.displayName }
    return "${battleDecisionPrefix(combat, trace?.questId)} $source" +
        explanation.takeIf(String::isNotBlank)?.let { "（$it）" }.orEmpty() + "：$team"
}
