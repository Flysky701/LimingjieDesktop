package com.landosol.toolbox.labyrinth

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class LabyrinthRerollSettings(
    val guildId: Int = LabyrinthRerollOptions.DEFAULT_GUILD_ID,
    val difficulty: Int = LabyrinthRerollOptions.DEFAULT_DIFFICULTY,
    val perfectStart: Boolean = LabyrinthRerollOptions.DEFAULT_PERFECT_START,
    val routeEvaluationMode: LabyrinthRouteEvaluationMode = LabyrinthRerollOptions.DEFAULT_ROUTE_EVALUATION_MODE,
    val valueAllowance: Int = LabyrinthRerollOptions.DEFAULT_VALUE_ALLOWANCE,
    val thirdBlockChoice: LabyrinthThirdBlockChoice = LabyrinthRerollOptions.defaultThirdBlockChoice,
    val area3BossIds: Set<Int> = LabyrinthRerollOptions.defaultArea3BossIds,
    val area5BossIds: Set<Int> = LabyrinthRerollOptions.defaultArea5BossIds,
    val maxAttempts: String = "100",
    val rerollUntilFound: Boolean = true,
    val retireExisting: Boolean = true,
    val customNodePlan: LabyrinthCustomNodePlan = LabyrinthCustomNodePlan(),
) {
    fun toConfig(accountId: Long): LabyrinthRerollConfig {
        require(validationError() == null)
        return LabyrinthRerollConfig(accountId = accountId, guildId = guildId, difficulty = difficulty,
            maxAttempts = maxAttempts.toInt(), retireExisting = retireExisting,
            rerollUntilFound = rerollUntilFound,
            routePolicy = LabyrinthRoutePolicy(perfectStart = perfectStart,
                evaluationMode = routeEvaluationMode, valueAllowance = valueAllowance,
                thirdBlockChoice = thirdBlockChoice,
                area3BossIds = area3BossIds.toSet(), area5BossIds = area5BossIds.toSet(),
                customNodePlan = customNodePlan))
    }

    fun validationError(): String? = when {
        LabyrinthRerollOptions.guilds.none { it.guildId == guildId } -> "请选择有效公会"
        difficulty !in 1..LabyrinthRerollOptions.MAX_DIFFICULTY -> "难度无效"
        valueAllowance !in 0..LabyrinthRerollOptions.MAX_VALUE_ALLOWANCE -> "每区允许少拿格数无效"
        maxAttempts.toIntOrNull() !in 1..LabyrinthRerollOptions.MAX_ATTEMPTS -> "最大尝试次数无效"
        area3BossIds.any { id -> LabyrinthRerollOptions.area3Bosses.none { it.unitId == id } } -> "区域3 Boss无效"
        area5BossIds.any { id -> LabyrinthRerollOptions.area5Bosses.none { it.unitId == id } } -> "区域5 Boss无效"
        else -> customNodePlan.validationError()
    }
}

fun LabyrinthUiState.rerollSettings() = LabyrinthRerollSettings(
    guildId = selectedGuildId,
    difficulty = selectedDifficulty,
    perfectStart = perfectStart,
    routeEvaluationMode = routeEvaluationMode,
    valueAllowance = valueAllowance,
    thirdBlockChoice = thirdBlockChoice,
    customNodePlan = customNodePlan,
    area3BossIds = selectedArea3BossIds,
    area5BossIds = selectedArea5BossIds,
    maxAttempts = maxAttempts,
    rerollUntilFound = rerollUntilFound,
    retireExisting = retireExisting,
)

interface LabyrinthRerollSettingsStore {
    fun load(accountId: Long): LabyrinthRerollSettings
    fun save(accountId: Long, settings: LabyrinthRerollSettings)
}

internal class JsonLabyrinthRerollSettingsStore(
    private val read: (String) -> String?,
    private val write: (String, String) -> Unit,
) : LabyrinthRerollSettingsStore {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    override fun load(accountId: Long): LabyrinthRerollSettings = runCatching {
        read("account.$accountId")?.let {
            json.decodeFromString<LabyrinthRerollSettings>(it).takeIf { settings -> settings.validationError() == null }
        }
    }.getOrNull() ?: LabyrinthRerollSettings()
    override fun save(accountId: Long, settings: LabyrinthRerollSettings) {
        require(settings.validationError() == null)
        write("account.$accountId", json.encodeToString(LabyrinthRerollSettings.serializer(), settings))
    }
}
