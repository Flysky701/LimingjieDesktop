package com.landosol.toolbox.labyrinth

/** A temporary battle-only relaxation; it never changes acquisition or role ratings. */
enum class LabyrinthBattleFormationFallbackReason(val description: String) {
    INSUFFICIENT_NON_PROTECTORS("非掩护者不足五名，临时恢复稳定模式并使用完整可用候选池"),
    NO_UNFAILED_FORMATION("非常激进模式没有合法且未失败的阵容，临时恢复稳定模式并使用完整可用候选池"),
}

enum class LabyrinthBattleFormationFailure {
    OTHER,
    NO_LEGAL_FORMATION,
    EXHAUSTED_AFTER_STABLE,
}

data class LabyrinthBattleCandidateSelection(
    val characterIds: List<String>,
    val effectiveMode: LabyrinthFrontlineSurvivalMode,
    val fallbackReason: LabyrinthBattleFormationFallbackReason? = null,
)

internal fun selectLabyrinthBattleFormationCandidates(
    acquiredCharacterIds: Collection<String>,
    profiles: Map<String, LabyrinthRoleProfile>,
    mode: LabyrinthFrontlineSurvivalMode,
): LabyrinthBattleCandidateSelection {
    val completePool = acquiredCharacterIds.map(::canonicalLabyrinthRoleId).distinct()
    if (mode != LabyrinthFrontlineSurvivalMode.VERY_AGGRESSIVE) {
        return LabyrinthBattleCandidateSelection(completePool, mode)
    }
    // Only a known job-class fact excludes a protector. Unknown jobs, sturdy attackers and
    // healers remain candidates; do not remove profiles or alter their survival contributions.
    val nonProtectors = completePool.filterNot { profiles[it]?.roleClass == "掩护者" }
    return if (nonProtectors.size >= 5) {
        LabyrinthBattleCandidateSelection(nonProtectors, mode)
    } else {
        LabyrinthBattleCandidateSelection(
            completePool,
            LabyrinthFrontlineSurvivalMode.STABLE,
            LabyrinthBattleFormationFallbackReason.INSUFFICIENT_NON_PROTECTORS,
        )
    }
}
