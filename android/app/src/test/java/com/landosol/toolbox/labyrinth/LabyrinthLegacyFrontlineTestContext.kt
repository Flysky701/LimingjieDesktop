package com.landosol.toolbox.labyrinth

/** Legacy gate regressions explicitly enable the former default; new switch tests cover default-off. */
internal fun legacyFrontlineContext(
    defenseMarkStacks: Int,
    enemyDamageType: LabyrinthEnemyDamageType = LabyrinthEnemyDamageType.UNKNOWN,
    targetCount: Int = 1,
    survivalRecovery: Boolean = false,
    optimizeBossVanguardSynergy: Boolean = false,
    preferSingleDamageSystem: Boolean = false,
    encounterStrategy: LabyrinthExEncounterStrategy? = null,
    effectiveCharacterIds: Set<String> = emptySet(),
    relaxedVanguardGate: Boolean = false,
    currentArea: Int? = null,
) = LabyrinthRoleDecisionContext(
    defenseMarkStacks = defenseMarkStacks, enemyDamageType = enemyDamageType, targetCount = targetCount,
    survivalRecovery = survivalRecovery, optimizeBossVanguardSynergy = optimizeBossVanguardSynergy,
    preferSingleDamageSystem = preferSingleDamageSystem, encounterStrategy = encounterStrategy,
    effectiveCharacterIds = effectiveCharacterIds, relaxedVanguardGate = relaxedVanguardGate,
    currentArea = currentArea, frontlineSurvivalGateEnabled = true,
)
