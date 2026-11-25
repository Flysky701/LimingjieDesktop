package com.landosol.toolbox.labyrinth

import com.landosol.toolbox.labyrinth.kernel.strategy.LabyrinthBattleValueEstimator
import com.landosol.toolbox.labyrinth.kernel.strategy.RuleBattleValueEstimator

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class LabyrinthRoleDecisionSource(
    val ratingWorkbook: String,
    val ratingWorkbookHash: String,
    val skillDatabaseVersion: String,
    val skillDatabaseHash: String,
    val attributeBonusSource: String,
) {
    init {
        require(ratingWorkbook.isNotBlank())
        require(ratingWorkbookHash.isNotBlank())
        require(skillDatabaseVersion.isNotBlank())
        require(skillDatabaseHash.isNotBlank())
        require(attributeBonusSource.isNotBlank())
    }
}

/** Versioned, auditable input consumed by both role choice and team planning. */
@Serializable
data class LabyrinthRoleDecisionDocument(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val source: LabyrinthRoleDecisionSource,
    val scoring: LabyrinthTeamScoringConfig,
    val roleChoice: LabyrinthRoleChoiceConfig,
    val teamPlan: LabyrinthTeamPlanSearchConfig,
    val characters: List<LabyrinthRoleProfile>,
) {
    companion object {
        const val CURRENT_SCHEMA_VERSION = 2
        val SUPPORTED_SCHEMA_VERSIONS = setOf(1, CURRENT_SCHEMA_VERSION)
    }
}

data class LabyrinthRoleDecisionRuntime(
    val document: LabyrinthRoleDecisionDocument,
    val profiles: Map<String, LabyrinthRoleProfile>,
    val rewardChoicePlanner: LabyrinthRoleRewardChoicePlanner,
    val eventChoicePlanner: LabyrinthEventChoicePlanner,
    val teamPlanSearcher: LabyrinthTeamPlanSearcher,
    val battleTeamRecommendationPlanner: LabyrinthBattleTeamRecommendationPlanner,
    val battleTeamSelectionPlanner: LabyrinthBattleTeamSelectionPlanner,
    val battleValueEstimator: LabyrinthBattleValueEstimator,
) {
    fun withBattleValueEstimator(estimator: LabyrinthBattleValueEstimator): LabyrinthRoleDecisionRuntime = copy(
        battleValueEstimator = estimator,
        battleTeamRecommendationPlanner = battleTeamRecommendationPlanner.withBattleValueEstimator(estimator),
    )
}

sealed interface LabyrinthRoleDecisionDataResult {
    data class Ready(val runtime: LabyrinthRoleDecisionRuntime) : LabyrinthRoleDecisionDataResult
    data class Unavailable(val reason: String) : LabyrinthRoleDecisionDataResult
}

object LabyrinthRoleDecisionDataParser {
    private val json = Json {
        ignoreUnknownKeys = false
        isLenient = false
    }

    fun parse(raw: String): LabyrinthRoleDecisionDataResult = runCatching {
        val document = json.decodeFromString<LabyrinthRoleDecisionDocument>(raw)
        require(document.schemaVersion in LabyrinthRoleDecisionDocument.SUPPORTED_SCHEMA_VERSIONS) {
            "不支持的角色决策资料版本：${document.schemaVersion}"
        }
        require(document.characters.isNotEmpty()) { "角色决策资料没有角色" }
        val duplicateIds = document.characters.groupingBy(LabyrinthRoleProfile::characterId)
            .eachCount()
            .filterValues { it > 1 }
            .keys
        require(duplicateIds.isEmpty()) { "角色决策资料存在重复ID：${duplicateIds.sorted().joinToString()}" }
        createRuntime(document)
    }.fold(
        onSuccess = { LabyrinthRoleDecisionDataResult.Ready(it) },
        onFailure = { failure ->
            LabyrinthRoleDecisionDataResult.Unavailable(
                "角色决策资料不可用：${failure.message ?: failure::class.simpleName.orEmpty()}",
            )
        },
    )

    fun createRuntime(
        document: LabyrinthRoleDecisionDocument,
        strategy: LabyrinthStrategySettings = LabyrinthStrategySettings(),
    ): LabyrinthRoleDecisionRuntime {
        val profiles = document.characters.associateBy(LabyrinthRoleProfile::characterId)
        val scorer = LabyrinthTeamScorer(document.scoring)
        val optimizer = LabyrinthTeamOptimizer(scorer)
        val policy = LabyrinthRoleChoicePolicy(optimizer, document.roleChoice)
        val teamPlanSearcher = LabyrinthTeamPlanSearcher(optimizer, document.teamPlan)
        val battleValueEstimator = RuleBattleValueEstimator(scorer)
        return LabyrinthRoleDecisionRuntime(
            document = document,
            profiles = profiles,
            rewardChoicePlanner = LabyrinthRoleRewardChoicePlanner(profiles, policy),
            eventChoicePlanner = LabyrinthEventChoicePlanner(profiles, optimizer, strategy),
            teamPlanSearcher = teamPlanSearcher,
            battleTeamRecommendationPlanner = LabyrinthBattleTeamRecommendationPlanner(
                profiles = profiles,
                teamPlanSearcher = teamPlanSearcher,
                scorer = scorer,
                battleValueEstimator = battleValueEstimator,
            ),
            battleTeamSelectionPlanner = LabyrinthBattleTeamSelectionPlanner(profiles),
            battleValueEstimator = battleValueEstimator,
        )
    }
}
