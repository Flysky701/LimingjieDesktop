package com.landosol.toolbox.labyrinth.kernel.strategy

import com.landosol.toolbox.labyrinth.LabyrinthRoleDecisionContext
import com.landosol.toolbox.labyrinth.LabyrinthRoleProfile
import com.landosol.toolbox.labyrinth.LabyrinthTeamScorer
import kotlin.math.ln

/** Database unit_id is the catalog key. The existing scorer uses the supplied character profile. */
data class BattleRosterUnit(
    val unitId: Int,
    val profile: LabyrinthRoleProfile,
    val roleId: Int = 0,
    val talentId: Int = 0,
)

data class BattleValueContext(
    val questId: Int,
    val area: Int,
    val difficulty: Int,
    val ownedUnits: List<BattleRosterUnit>,
    val ruleContext: LabyrinthRoleDecisionContext,
    val relicIds: List<Int> = emptyList(),
    val markStacks: List<Int> = emptyList(),
    val alphaRupee: Int? = null,
    val reviveAvailable: Boolean = false,
    /** Optional observed wave, in catalog slot order. */
    val enemyIds: List<Int>? = null,
) {
    init {
        require(ownedUnits.all { it.unitId > 0 } && ownedUnits.distinctBy { it.unitId }.size == ownedUnits.size)
        require(ownedUnits.distinctBy { it.profile.characterId }.size == ownedUnits.size)
    }

    fun roleCounts() = (1..8).map { role -> ownedUnits.count { it.roleId == role } }
    fun talentCounts() = (1..5).map { talent -> ownedUnits.count { it.talentId == talent } }
}

data class BattleValue(
    val pWin: Double, val pTimeout: Double, val pWipe: Double,
    val damageMean: Double, val damageLogStd: Double, val winRemTime: Double,
)

fun interface LabyrinthBattleValueEstimator {
    /** One result per candidate in input order. A team has 1–5 distinct owned database unit IDs. */
    fun estimate(context: BattleValueContext, candidates: List<List<Int>>): List<BattleValue>
}

/** Heuristic approximation: map the nonnegative formation score s to s/(s+50), including scores
 * above 100. Use this ratio for win, damage and remaining time, split losses evenly and assume
 * a fixed damage standard deviation of 0.25. The planner keeps its original score and gates.
 */
class RuleBattleValueEstimator(private val scorer: LabyrinthTeamScorer) : LabyrinthBattleValueEstimator {
    override fun estimate(context: BattleValueContext, candidates: List<List<Int>>): List<BattleValue> {
        val profiles = context.ownedUnits.associate { it.unitId to it.profile }
        return candidates.map { ids ->
            val evaluation = scorer.evaluate(ids.map(profiles::getValue), context.ruleContext)
            val win = evaluation.score / (evaluation.score + 50.0)
            val loss = (1.0 - win) / 2
            BattleValue(win, loss, loss, win, ln(0.25), win)
        }
    }
}
