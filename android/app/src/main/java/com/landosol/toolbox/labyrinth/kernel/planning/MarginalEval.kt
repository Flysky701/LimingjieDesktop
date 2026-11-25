package com.landosol.toolbox.labyrinth.kernel.planning

import com.landosol.toolbox.labyrinth.LabyrinthRelicMark
import com.landosol.toolbox.labyrinth.canonicalLabyrinthRoleId
import kotlin.math.abs

data class BattleExam(
    val questId: Int,
    val area: Int,
    val isBoss: Boolean,
    val weight: Double = 1.0,
    val maxTeams: Int = 3,
)

data class PlanningState(
    val difficulty: Int,
    val currentArea: Int,
    val ownedCharacterIds: Set<String>,
    val relicIds: Set<Int>,
    val markStacks: Map<LabyrinthRelicMark, Int>,
    val remainingShops: Int,
    val enhanceNode21: Boolean,
    val reviveAvailable: Boolean,
    val alphaRupee: Int? = null,
    val targetScore: Long = 0L,
    val confirmedPts: Long = 0L,
    val scoreRemainder: Int = 0,
) {
    internal val discount: ShopDiscountModel get() = ShopDiscountModel(enhanceNode21, 40003 in relicIds)
}

data class PassEstimate(
    val pPass: Double,
    val expectedTeams: Double,
    val teamWinProbs: List<Double> = emptyList(),
)

fun interface BattlePassEstimator {
    fun estimate(state: PlanningState, exam: BattleExam): PassEstimate?
}

data class BattlePassProfile(
    val conditionalPassProbs: List<Double>,
    val pPass: Double,
    val expectedTeams: Double,
)

/** The revive charge survives a win and can retry one non-boss exam across the whole sequence. */
fun passProfile(
    state: PlanningState,
    exams: List<BattleExam>,
    estimator: BattlePassEstimator?,
): BattlePassProfile? {
    var withCharge = if (state.reviveAvailable) 1.0 else 0.0
    var withoutCharge = 1.0 - withCharge
    var expectedTeams = 0.0
    val conditional = exams.map { exam ->
        val estimate = estimator?.estimate(state, exam) ?: return null
        val p = estimate.pPass
        require(p in 0.0..1.0)
        val alive = withCharge + withoutCharge
        val retry = if (exam.isBoss) 0.0 else withCharge * (1.0 - p)
        expectedTeams += (alive + retry) * estimate.expectedTeams
        withCharge *= p
        withoutCharge = (withoutCharge + retry) * p
        if (alive > 0.0) (withCharge + withoutCharge) / alive else 0.0
    }
    return BattlePassProfile(conditional, withCharge + withoutCharge, expectedTeams)
}

fun battleValue(
    state: PlanningState,
    exams: List<BattleExam>,
    estimator: BattlePassEstimator?,
    settings: ScoreSettings = ScoreSettings.currentOfficial(),
): Double = passProfile(state, exams, estimator)?.let { battlePoints(exams, it, settings, state.difficulty) } ?: 0.0

private fun battlePoints(
    exams: List<BattleExam>,
    profile: BattlePassProfile,
    settings: ScoreSettings,
    difficulty: Int,
): Double = exams.indices.sumOf { index ->
    val exam = exams[index]
    val pts = if (exam.isBoss) bossWinDeltaPts(settings, difficulty) else normalWinDeltaPts(settings, difficulty)
    exam.weight * pts * profile.conditionalPassProbs[index]
}

data class RelicMarkAward(val mark: LabyrinthRelicMark, val count: Int)

fun awardCollections(
    state: PlanningState,
    settings: ScoreSettings = ScoreSettings.currentOfficial(),
    characterIds: Iterable<String> = emptyList(),
    relicIds: Iterable<Int> = emptyList(),
    newUnits: Int = 0,
    newRelics: Int = 0,
    relicMarks: Map<Int, RelicMarkAward> = emptyMap(),
): PlanningState {
    require(newUnits >= 0 && newRelics >= 0 && state.scoreRemainder in 0..99)
    val owned = state.ownedCharacterIds.mapTo(hashSetOf(), ::canonicalLabyrinthRoleId)
    val addedUnits = characterIds.map(::canonicalLabyrinthRoleId).toSet() - owned
    val addedRelics = relicIds.toSet() - state.relicIds
    val marks = state.markStacks.toMutableMap()
    for (relicId in addedRelics) {
        val (mark, count) = relicMarks[relicId] ?: continue
        if (count > 0) marks[mark] = marks.getOrDefault(mark, 0) + count
    }
    val base = (addedUnits.size.toLong() + newUnits) * settings.unitPts +
        (addedRelics.size.toLong() + newRelics) * settings.relicPts
    val ticks = state.scoreRemainder + base * settings.scoreRates.getValue(state.difficulty)
    return state.copy(
        ownedCharacterIds = state.ownedCharacterIds + addedUnits,
        relicIds = state.relicIds + addedRelics,
        markStacks = marks,
        reviveAvailable = state.reviveAvailable || REVIVE_RELIC_ID in addedRelics,
        confirmedPts = state.confirmedPts + Math.floorDiv(ticks, 100L),
        scoreRemainder = Math.floorMod(ticks, 100L).toInt(),
    )
}

data class RoleChoiceEval(
    val characterId: String,
    val totalScore: Double,
    val battleDeltaPts: Double,
    val collectionDeltaPts: Double,
    val beforePassProb: Double,
    val afterPassProb: Double,
    val usedModel: Boolean,
)

data class RelicChoiceEval(
    val relicId: Int,
    val totalScore: Double,
    val battleDeltaPts: Double,
    val collectionDeltaPts: Double,
    val shopDiscountDeltaPts: Double,
    val markSynergyPts: Double,
    val beforePassProb: Double,
    val afterPassProb: Double,
    val usedModel: Boolean,
)

private fun shopPayouts(state: PlanningState, settings: ScoreSettings, shelfModel: ShopShelfModel): Map<Long, Double> {
    val gold = state.alphaRupee ?: return mapOf(0L to 1.0)
    return shopScoreDistribution(
        gold, state.remainingShops, settings, state.difficulty, state.discount, shelfModel,
        ownedCharacterIds = state.ownedCharacterIds.mapTo(hashSetOf(), ::canonicalLabyrinthRoleId),
        scoreRemainder = state.scoreRemainder,
    )
}

private fun Map<Long, Double>.expectedPts(): Double = entries.sumOf { (pts, p) -> pts * p }

private fun walletValue(
    state: PlanningState,
    settings: ScoreSettings,
    shelfModel: ShopShelfModel,
    shopCurve: ((PlanningState) -> Double)?,
): Double = shopCurve?.invoke(state) ?: shopPayouts(state, settings, shelfModel).expectedPts()

fun evaluateRoleChoices(
    state: PlanningState,
    candidates: Iterable<String>,
    exams: List<BattleExam> = emptyList(),
    estimator: BattlePassEstimator? = null,
    settings: ScoreSettings = ScoreSettings.currentOfficial(),
    shelfModel: ShopShelfModel = ShopShelfModel(),
    shopCurve: ((PlanningState) -> Double)? = null,
): List<RoleChoiceEval> {
    val before = passProfile(state, exams, estimator)
    val beforeShop = walletValue(state, settings, shelfModel, shopCurve)
    return candidates.map { id ->
        val after = awardCollections(state, settings, characterIds = listOf(id))
        val profile = if (after == state) before else passProfile(after, exams, estimator)
        val battle = if (before != null && profile != null) {
            battlePoints(exams, profile, settings, state.difficulty) - battlePoints(exams, before, settings, state.difficulty)
        } else 0.0
        val collection = (after.confirmedPts - state.confirmedPts).toDouble()
        val shop = walletValue(after, settings, shelfModel, shopCurve) - beforeShop
        RoleChoiceEval(id, collection + shop + battle, battle, collection, before?.pPass ?: 0.0,
            profile?.pPass ?: 0.0, exams.isNotEmpty() && before != null && profile != null)
    }.sortedWith(compareByDescending<RoleChoiceEval> { it.totalScore }.thenBy { it.characterId })
}

fun evaluateRelicChoices(
    state: PlanningState,
    candidates: Iterable<Int>,
    exams: List<BattleExam> = emptyList(),
    estimator: BattlePassEstimator? = null,
    settings: ScoreSettings = ScoreSettings.currentOfficial(),
    shelfModel: ShopShelfModel = ShopShelfModel(),
    relicMarks: Map<Int, RelicMarkAward> = emptyMap(),
    shopCurve: ((PlanningState) -> Double)? = null,
): List<RelicChoiceEval> {
    val before = passProfile(state, exams, estimator)
    val beforeShop = walletValue(state, settings, shelfModel, shopCurve)
    return candidates.map { id ->
        val after = awardCollections(state, settings, relicIds = listOf(id), relicMarks = relicMarks)
        val profile = if (after == state) before else passProfile(after, exams, estimator)
        var battle = 0.0
        var markSynergy = 0.0
        if (before != null && profile != null) {
            val afterPts = battlePoints(exams, profile, settings, state.difficulty)
            battle = afterPts - battlePoints(exams, before, settings, state.difficulty)
            if (after.markStacks != state.markStacks) {
                val withoutMark = passProfile(after.copy(markStacks = state.markStacks), exams, estimator)
                if (withoutMark != null) {
                    markSynergy = afterPts - battlePoints(exams, withoutMark, settings, state.difficulty)
                    battle -= markSynergy
                }
            }
        }
        val collection = (after.confirmedPts - state.confirmedPts).toDouble()
        val afterShop = walletValue(after, settings, shelfModel, shopCurve)
        val discountDelta = if (after.discount != state.discount) {
            afterShop - walletValue(after.copy(relicIds = after.relicIds - 40003), settings, shelfModel, shopCurve)
        } else 0.0
        RelicChoiceEval(id, collection + (afterShop - beforeShop) + battle + markSynergy, battle, collection,
            discountDelta, markSynergy, before?.pPass ?: 0.0, profile?.pPass ?: 0.0,
            exams.isNotEmpty() && before != null && profile != null)
    }.sortedWith(compareByDescending<RelicChoiceEval> { it.totalScore }.thenBy { it.relicId })
}

data class EventBranchSpec(
    val probability: Double = 1.0,
    val goldDelta: Int = 0,
    val nextEventId: Int? = null,
    val characterIds: List<String> = emptyList(),
    val relicIds: List<Int> = emptyList(),
    val newUnits: Int = 0,
    val newRelics: Int = 0,
    val elapsedSec: Double = 0.0,
)

data class EventOptionSpec(
    val choiceId: Int,
    val branches: List<EventBranchSpec> = listOf(EventBranchSpec()),
    val conditionValue: Int = 0,
    val timeSec: Double = 0.0,
) {
    init {
        checkProbabilityMass(branches.map(EventBranchSpec::probability))
        require(conditionValue >= 0 && timeSec.isFinite() && timeSec >= 0.0)
        require(branches.all { it.newUnits >= 0 && it.newRelics >= 0 && it.elapsedSec.isFinite() && it.elapsedSec >= 0.0 })
    }
}

data class EventChoiceOutcome(
    val probability: Double,
    val state: PlanningState,
    val elapsedSec: Double,
    val choices: List<Int>,
    val branchIndices: List<Int>,
)

data class EventChoiceEval(
    val choiceId: Int,
    val totalScore: Double,
    val collectionDeltaPts: Double,
    val shopDeltaPts: Double,
    val battleDeltaPts: Double,
    val probReachTarget: Double,
    val expectedTimeSec: Double,
    val outcomes: List<EventChoiceOutcome>,
    val usedModel: Boolean,
) {
    val nextState: PlanningState? get() = outcomes.singleOrNull()?.state
}

private fun checkProbabilityMass(probabilities: Iterable<Double>) {
    require(probabilities.all { it in 0.0..1.0 } && abs(probabilities.sum() - 1.0) <= 1e-12)
}

private val eventRanking = compareByDescending<EventChoiceEval> { it.totalScore }
    .thenBy { it.expectedTimeSec }.thenBy { it.choiceId }

/** Resolves each continuation after observing its parent branch and checking the new wallet. */
fun evaluateEventOptions(
    state: PlanningState,
    options: List<EventOptionSpec>,
    exams: List<BattleExam> = emptyList(),
    estimator: BattlePassEstimator? = null,
    deductConditionPrincipal: Boolean = true,
    eventTree: Map<Int, List<EventOptionSpec>> = emptyMap(),
    settings: ScoreSettings = ScoreSettings.currentOfficial(),
    relicMarks: Map<Int, RelicMarkAward> = emptyMap(),
    shelfModel: ShopShelfModel = ShopShelfModel(),
    shopCurve: ((PlanningState) -> Double)? = null,
    shopDistributionFn: ((PlanningState) -> Map<Long, Double>)? = null,
    futureScoreDistribution: Map<Long, Double> = mapOf(0L to 1.0),
    targetScore: Long = state.targetScore,
    lambdaRate: Double = 0.0,
    firstContinuation: Boolean = false,
): List<EventChoiceEval> {
    require(lambdaRate.isFinite() && lambdaRate >= 0.0)
    checkProbabilityMass(futureScoreDistribution.values)

    fun payouts(s: PlanningState): Map<Long, Double>? =
        shopDistributionFn?.invoke(s)?.also { checkProbabilityMass(it.values) }
            ?: if (shopCurve == null) shopPayouts(s, settings, shelfModel) else null

    val initialShop = payouts(state)?.expectedPts() ?: shopCurve?.invoke(state) ?: 0.0
    val initialProfile = passProfile(state, exams, estimator)
    fun points(profile: BattlePassProfile) = battlePoints(exams, profile, settings, state.difficulty)
    val initialBattle = initialProfile?.let(::points) ?: 0.0

    fun evaluate(choiceId: Int, outcomes: List<EventChoiceOutcome>): EventChoiceEval {
        var direct = 0.0
        var shop = 0.0
        var battle = 0.0
        var hit = 0.0
        var time = 0.0
        var usedModel = exams.isNotEmpty() && initialProfile != null
        for (outcome in outcomes) {
            val (prob, after, elapsed) = outcome
            val distribution = payouts(after)
            val balance = distribution?.expectedPts() ?: shopCurve?.invoke(after) ?: 0.0
            val profile = if (after == state) initialProfile else passProfile(after, exams, estimator)
            direct += prob * (after.confirmedPts - state.confirmedPts)
            shop += prob * (balance - initialShop)
            time += prob * elapsed
            if (initialProfile != null && profile != null) battle += prob * (points(profile) - initialBattle)
            usedModel = usedModel && profile != null
            if (targetScore > 0 && profile != null) {
                val shopHit = { pts: Double ->
                    futureScoreDistribution.entries.sumOf { (extra, p) ->
                        if (after.confirmedPts + pts + extra >= targetScore) p else 0.0
                    }
                }
                val hitGivenPass = distribution?.entries?.sumOf { (pts, p) -> p * shopHit(pts.toDouble()) }
                    ?: shopHit(balance)
                hit += prob * profile.pPass * hitGivenPass
            }
        }
        val total = if (targetScore > 0) hit - lambdaRate * time else direct + shop + battle
        return EventChoiceEval(choiceId, total, direct, shop, battle, hit, time, outcomes, usedModel)
    }

    fun expand(
        start: EventChoiceOutcome,
        choices: List<EventOptionSpec>,
        visiting: Set<Int>,
    ): List<EventChoiceEval> = choices.mapNotNull { option ->
        val wallet = start.state.alphaRupee
        if ((wallet ?: 0) < option.conditionValue) return@mapNotNull null
        val principal = if (deductConditionPrincipal) option.conditionValue else 0
        val outcomes = option.branches.flatMapIndexed { index, branch ->
            if (branch.probability == 0.0) return@flatMapIndexed emptyList()
            val gold = wallet?.let { Math.addExact(it - principal, branch.goldDelta) }
            require(gold == null || gold >= 0) { "Event branch spends more gold than available" }
            val after = awardCollections(start.state.copy(alphaRupee = gold), settings, branch.characterIds,
                branch.relicIds, branch.newUnits, branch.newRelics, relicMarks)
            val leaf = EventChoiceOutcome(branch.probability, after, start.elapsedSec + option.timeSec + branch.elapsedSec,
                start.choices + option.choiceId, start.branchIndices + index)
            val next = branch.nextEventId ?: return@flatMapIndexed listOf(leaf)
            require(next !in visiting) { "Cycle in event chain at $next" }
            val nextOptions = requireNotNull(eventTree[next]) { "Missing event options for $next" }
            val continuations = expand(leaf, nextOptions, visiting + next)
            val chosen = if (firstContinuation) continuations.firstOrNull() else continuations.minWithOrNull(eventRanking)
            chosen?.outcomes?.map { it.copy(probability = branch.probability * it.probability) } ?: listOf(leaf)
        }
        evaluate(option.choiceId, outcomes)
    }

    return expand(EventChoiceOutcome(1.0, state, 0.0, emptyList(), emptyList()), options, emptySet())
        .sortedWith(eventRanking)
}

/** Reference outcomes for the recorded multistage events, exchanges and coin battles. */
fun defaultEventTree(normalWinProb: Double = 1.0, exWinProb: Double = 1.0): Map<Int, List<EventOptionSpec>> = buildMap {
    val fishProbabilities = listOf(0.5, 0.25, 0.10)
    val slotPrizes = listOf(2000, 4000, 8000)
    for (family in 14..16) {
        for (stage in 1..3) {
            val eventId = stage * 1000 + 300 + family
            val choiceId = eventId * 10 + 1
            val success = if (family == 14) fishProbabilities[stage - 1] else 0.5
            val prize = if (family == 16) slotPrizes[stage - 1] else 0
            val principal = if (family == 16 && stage > 1) slotPrizes[stage - 2] else 0
            val win = EventBranchSpec(
                success, prize, nextEventId = (eventId + 1000).takeIf { stage < 3 },
                newUnits = if (family == 15 && stage == 3) 2 else 0,
                newRelics = if (family == 14) 1 else 0,
            )
            val play = EventOptionSpec(choiceId, listOf(win, EventBranchSpec(1.0 - success, 500)), principal, 1.0)
            put(eventId, if (family != 14 && stage > 1) {
                listOf(play, EventOptionSpec(choiceId + 1, listOf(EventBranchSpec(newUnits = if (family == 15) 2 else 0))))
            } else listOf(play))
        }
    }
    put(1107, listOf(
        EventOptionSpec(11071, listOf(EventBranchSpec(newRelics = 1)), conditionValue = 2000),
        EventOptionSpec(11072, listOf(EventBranchSpec(newUnits = 1)), conditionValue = 2000),
        EventOptionSpec(11073, listOf(EventBranchSpec(.5, 1500), EventBranchSpec(.5, 2000))),
    ))
    for ((eventId, coins) in mapOf(1105 to 200, 1109 to 300, 1113 to 400, 1117 to 500)) {
        put(eventId, listOf(normalWinProb to coins, exWinProb to coins * 3 / 2).mapIndexed { index, (win, prize) ->
            EventOptionSpec(eventId * 10 + index + 1, listOf(EventBranchSpec(win, prize), EventBranchSpec(1.0 - win, 500)),
                timeSec = 1.0)
        })
    }
}
