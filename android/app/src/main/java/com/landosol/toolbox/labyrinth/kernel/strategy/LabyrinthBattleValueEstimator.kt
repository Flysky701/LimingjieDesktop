package com.landosol.toolbox.labyrinth.kernel.strategy

import com.landosol.toolbox.labyrinth.LabyrinthCharacterAttribute
import com.landosol.toolbox.labyrinth.LabyrinthRelicMark
import com.landosol.toolbox.labyrinth.LabyrinthRoleDecisionContext
import com.landosol.toolbox.labyrinth.LabyrinthRoleProfile
import com.landosol.toolbox.labyrinth.LabyrinthTeamScorer
import com.landosol.toolbox.labyrinth.canonicalLabyrinthRoleId
import com.landosol.toolbox.labyrinth.withKnownRoleIdentities
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

enum class BattleValueSource { RULE, MODEL }

enum class BattleConfidenceTier(val label: String) {
    SAFE("稳妥推荐"),
    MARGINAL("边缘尝试"),
    UNSAFE("无稳过队"),
    ;

    companion object {
        const val SAFE_THRESHOLD = 0.70
        const val MARGINAL_THRESHOLD = 0.50

        fun classify(pWin: Double): BattleConfidenceTier = when {
            pWin >= SAFE_THRESHOLD -> SAFE
            pWin >= MARGINAL_THRESHOLD -> MARGINAL
            else -> UNSAFE
        }
    }
}

enum class BattleModelDecisionStatus(val label: String) {
    ADOPTED("NN选队"),
    LOW_CONFIDENCE_FALLBACK("NN低胜率回退"),
    NO_QUEST_ID("缺关卡ID"),
    CONTEXT_UNAVAILABLE("上下文不完整"),
    MODEL_DISABLED("模型未启用"),
    LOAD_OR_INFERENCE_FAILED("模型异常回退"),
    NO_ELIGIBLE_CANDIDATE("无合法候选"),
}

data class BattleModelTrace(
    val status: BattleModelDecisionStatus,
    val questId: Int? = null,
    val detail: String? = null,
)

data class BattleModelAssessment(
    val tier: BattleConfidenceTier,
    val pWin: Double,
    val winRemTime: Double,
    val damageMean: Double,
    val evaluatedCandidates: Int,
    val adopted: Boolean,
    val jointWinProbability: Double? = null,
    val plannedTeams: List<List<String>> = emptyList(),
    val teamWinProbabilities: List<Double> = emptyList(),
)

data class BattleValueEstimate(
    val values: List<BattleValue>,
    val source: BattleValueSource,
    val fallbackReason: String? = null,
)

interface ContextualBattleValueEstimator : LabyrinthBattleValueEstimator {
    fun estimateWithSource(context: BattleValueContext, candidates: List<List<Int>>): BattleValueEstimate
    fun unitAffinities(context: BattleValueContext): Map<Int, Double>? = null
    override fun estimate(context: BattleValueContext, candidates: List<List<Int>>): List<BattleValue> =
        estimateWithSource(context, candidates).values
}

internal fun labyrinthCharacterIdToUnitId(characterId: String): Int? =
    canonicalLabyrinthRoleId(characterId).toIntOrNull()?.takeIf { it > 0 }?.let { it * 100 + 1 }

internal fun labyrinthRoleClassToId(roleClass: String?): Int = when (roleClass) {
    "攻击者" -> 1
    "破防者" -> 2
    "增益者" -> 3
    "减益者" -> 4
    "增幅者" -> 5
    "治疗者" -> 6
    "掩护者" -> 7
    "妨碍者" -> 8
    else -> 0
}

internal fun labyrinthAttributeToTalentId(attribute: LabyrinthCharacterAttribute?): Int = when (attribute) {
    LabyrinthCharacterAttribute.FIRE -> 1
    LabyrinthCharacterAttribute.WATER -> 2
    LabyrinthCharacterAttribute.WIND -> 3
    LabyrinthCharacterAttribute.LIGHT -> 4
    LabyrinthCharacterAttribute.DARK -> 5
    else -> 0
}

internal fun labyrinthMarkStacksForModel(markStacks: Map<LabyrinthRelicMark, Int>): List<Int> = listOf(
    LabyrinthRelicMark.ACCELERATION,
    LabyrinthRelicMark.DEBUFF,
    LabyrinthRelicMark.CRITICAL,
    LabyrinthRelicMark.ENHANCEMENT,
    LabyrinthRelicMark.DEFENSE,
).map { markStacks[it] ?: 0 }

internal fun buildBattleValueContext(
    acquiredCharacterIds: Collection<String>,
    profiles: Map<String, LabyrinthRoleProfile>,
    questId: Int,
    area: Int,
    difficulty: Int,
    ruleContext: LabyrinthRoleDecisionContext,
    relicIds: List<Int>,
    markStacksByMark: Map<LabyrinthRelicMark, Int>,
    alphaRupee: Int?,
    reviveAvailable: Boolean,
): BattleValueContext? {
    val canonicalIds = acquiredCharacterIds.map(::canonicalLabyrinthRoleId).distinct()
    val knownProfiles = profiles.withKnownRoleIdentities(canonicalIds)
    val seenUnitIds = hashSetOf<Int>()
    val ownedUnits = canonicalIds.map { id ->
        val unitId = labyrinthCharacterIdToUnitId(id)?.takeIf { it > 0 } ?: return null
        if (unitId / 100 != id.toIntOrNull() || !seenUnitIds.add(unitId)) return null
        val profile = knownProfiles.getValue(id)
        if (canonicalLabyrinthRoleId(profile.characterId) != id) return null
        BattleRosterUnit(
            unitId,
            if (profile.characterId == id) profile else profile.copy(characterId = id),
            labyrinthRoleClassToId(profile.roleClass),
            labyrinthAttributeToTalentId(profile.attribute),
        )
    }
    return BattleValueContext(
        questId = questId, area = area, difficulty = difficulty, ownedUnits = ownedUnits, ruleContext = ruleContext,
        relicIds = relicIds, markStacks = labyrinthMarkStacksForModel(markStacksByMark),
        alphaRupee = alphaRupee, reviveAvailable = reviveAvailable,
    )
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
