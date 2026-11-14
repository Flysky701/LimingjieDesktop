package com.landosol.toolbox.labyrinth

import com.landosol.toolbox.labyrinth.concurrent.LabyrinthFourThreadRuntime
import java.util.PriorityQueue
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import kotlin.math.max

// Frozen verbatim algorithm blocks from read-only batch5 / versionCode 10086.
// Only names change; shared models/runtime/index cache intentionally stay shared.
// See _round1/fixture-freeze-manifest.json and extract_round1.py.

/** Attribute table indexed by ordinal, for the scorer's allocation-free accumulation. */
private val B5R1_ATTRIBUTE_SLOTS = LabyrinthCharacterAttribute.values()
private val B5R1_ATTRIBUTE_SLOT_COUNT = B5R1_ATTRIBUTE_SLOTS.size

/** Default shard budget; explicitly requesting four shards still uses the same two workers. */
private const val B5R1_ROLE_SEARCH_THREADS = 2

/** Below this many combinations the thread hand-off costs more than it saves. */
private const val B5R1_PARALLEL_MIN_COMBINATIONS = 512

/** Alias, not a second pool. Helping joins permit this synchronous API on a CPU parent. */
private val B5R1_ROLE_SEARCH_EXECUTOR: ExecutorService = LabyrinthFourThreadRuntime.computeExecutor

/**
 * A role can join either physical or magic cores only when its combat contribution is genuinely
 * system-agnostic.  Damage dealers / breakers never become universal merely because their own
 * damage is small.  This keeps 厄莉丝-like cross-system healing/support legal while preventing a
 * magic breaker such as 帆稀（夏日） from slipping into a physical core.
 */
private fun LabyrinthRoleProfile.b5r1IsUniversalDamageSystemRole(): Boolean {
    val role = roleClass.orEmpty()
    val physicalUplift = physicalTeamUplift ?: 0.0
    val magicUplift = magicTeamUplift ?: 0.0
    val crossSystemOffense = minOf(physicalUplift, magicUplift) >= 0.08 ||
        support.universalOffense.b5r1OrZero() >= 35.0
    val universalSurvival = support.universalSurvival.b5r1OrZero() >= 35.0
    val healer = role == "治疗者" &&
        max(functions.healing.b5r1OrZero(), functions.regeneration.b5r1OrZero()) >= 25.0
    val vanguard = role == "掩护者" &&
        (functions.reliableVanguard.b5r1OrZero() >= 45.0 || universalSurvival)
    val universalBuffer = role == "增益者" && crossSystemOffense
    return healer || vanguard || universalBuffer
}

private fun b5r1StrictMemberDamageSystem(members: List<LabyrinthRoleProfile>): LabyrinthTeamDamageType {
    val typed = members
        .filterNot(LabyrinthRoleProfile::b5r1IsUniversalDamageSystemRole)
        .mapNotNull(LabyrinthRoleProfile::damageType)
        .filter { it in setOf(LabyrinthRoleDamageType.PHYSICAL, LabyrinthRoleDamageType.MAGIC) }

    if (typed.isEmpty()) return LabyrinthTeamDamageType.NONE
    val hasPhysical = LabyrinthRoleDamageType.PHYSICAL in typed
    val hasMagic = LabyrinthRoleDamageType.MAGIC in typed
    return when {
        hasPhysical && hasMagic -> LabyrinthTeamDamageType.MIXED
        hasPhysical -> LabyrinthTeamDamageType.PHYSICAL
        hasMagic -> LabyrinthTeamDamageType.MAGIC
        else -> LabyrinthTeamDamageType.NONE
    }
}


class Batch5Round1ScorerReference(
    val config: LabyrinthTeamScoringConfig,
) {
    fun evaluate(
        members: List<LabyrinthRoleProfile>,
        context: LabyrinthRoleDecisionContext,
    ): LabyrinthTeamEvaluation {
        // Argument validation stays on the public entry: the search hot path evaluates tens of
        // thousands of teams whose pool was already de-duplicated at rankedFormationsInternal.
        require(members.isNotEmpty() && members.size <= TEAM_SIZE)
        require(members.distinctBy(LabyrinthRoleProfile::characterId).size == members.size)
        return evaluateInternal(members, context, explain = true)
    }

    internal fun evaluateForSearch(
        members: List<LabyrinthRoleProfile>,
        context: LabyrinthRoleDecisionContext,
    ): LabyrinthTeamEvaluation = evaluateInternal(members, context, explain = false)

    private fun evaluateInternal(
        members: List<LabyrinthRoleProfile>,
        context: LabyrinthRoleDecisionContext,
        explain: Boolean,
    ): LabyrinthTeamEvaluation {
        val targetCount = context.targetCount.coerceAtMost(3)
        // One pass over the members into ordinal-indexed arrays. This is the same computation as
        // the former mapNotNull/groupingBy/eachCount + Triple + stable sortedByDescending + take
        // chain, minus its intermediate collections and boxing: the search hot path evaluates
        // tens of thousands of teams per recommendation, and those temporaries were most of its
        // garbage. Attribute order is first-appearance order, exactly as groupingBy produced it,
        // and the per-attribute damage sums add members in member order, so the doubles are
        // bit-identical.
        val attributeCount = IntArray(B5R1_ATTRIBUTE_SLOT_COUNT)
        val attributeGroupDamage = DoubleArray(B5R1_ATTRIBUTE_SLOT_COUNT)
        val attributeFirstSeen = IntArray(members.size)
        var attributeSeenCount = 0
        for (member in members) {
            val slot = (member.attribute ?: continue).ordinal
            if (attributeCount[slot] == 0) attributeFirstSeen[attributeSeenCount++] = slot
            attributeCount[slot]++
            attributeGroupDamage[slot] += scenarioDamage(member, LabyrinthRoleDamageType.PHYSICAL, targetCount) +
                scenarioDamage(member, LabyrinthRoleDamageType.MAGIC, targetCount)
        }
        val attributeCounts: Map<LabyrinthCharacterAttribute, Int> = if (attributeSeenCount == 0) {
            emptyMap()
        } else {
            LinkedHashMap<LabyrinthCharacterAttribute, Int>(attributeSeenCount * 2).also { counts ->
                for (index in 0 until attributeSeenCount) {
                    val slot = attributeFirstSeen[index]
                    counts[B5R1_ATTRIBUTE_SLOTS[slot]] = attributeCount[slot]
                }
            }
        }
        // Top-N by incremental damage with a bounded insertion sort. Ties keep first-appearance
        // order, which is what the stable sortedByDescending did.
        val maximumBonuses = config.maximumActiveAttributeBonuses
        val chosenSlots = IntArray(maximumBonuses)
        val chosenBonuses = DoubleArray(maximumBonuses)
        val chosenIncremental = DoubleArray(maximumBonuses)
        var chosenCount = 0
        for (index in 0 until attributeSeenCount) {
            val slot = attributeFirstSeen[index]
            val bonus = config.attributeDamageBonus.getValue(attributeCount[slot])
            if (bonus <= 0.0) continue
            val incremental = attributeGroupDamage[slot] * bonus
            if (chosenCount == maximumBonuses && chosenIncremental[chosenCount - 1] >= incremental) continue
            var position = if (chosenCount < maximumBonuses) chosenCount else chosenCount - 1
            while (position > 0 && chosenIncremental[position - 1] < incremental) {
                chosenSlots[position] = chosenSlots[position - 1]
                chosenBonuses[position] = chosenBonuses[position - 1]
                chosenIncremental[position] = chosenIncremental[position - 1]
                position--
            }
            chosenSlots[position] = slot
            chosenBonuses[position] = bonus
            chosenIncremental[position] = incremental
            if (chosenCount < maximumBonuses) chosenCount++
        }
        val activeBonuses: Map<LabyrinthCharacterAttribute, Double> = if (chosenCount == 0) {
            emptyMap()
        } else {
            LinkedHashMap<LabyrinthCharacterAttribute, Double>(chosenCount * 2).also { bonuses ->
                for (index in 0 until chosenCount) bonuses[B5R1_ATTRIBUTE_SLOTS[chosenSlots[index]]] = chosenBonuses[index]
            }
        }

        val physicalBase = members.sumOf { role ->
            scenarioDamage(role, LabyrinthRoleDamageType.PHYSICAL, targetCount) *
                (1.0 + (role.attribute?.let(activeBonuses::get) ?: 0.0))
        }
        val magicBase = members.sumOf { role ->
            scenarioDamage(role, LabyrinthRoleDamageType.MAGIC, targetCount) *
                (1.0 + (role.attribute?.let(activeBonuses::get) ?: 0.0))
        }
        val physicalUplift = combinedTeamUplift(members, LabyrinthRoleDamageType.PHYSICAL)
        val magicUplift = combinedTeamUplift(members, LabyrinthRoleDamageType.MAGIC)
        // V2 raw uplift already includes modeled buff/debuff effects. Percentile-normalized
        // defense-down remains a function signal until real enemy defense is supplied.
        val physicalDamage = physicalBase * (1.0 + physicalUplift)
        val magicDamage = magicBase * (1.0 + magicUplift)
        val totalDamage = physicalDamage + magicDamage
        val physicalRatio = if (totalDamage <= 0.0) 0.0 else physicalDamage / totalDamage
        // Team system is a member-composition fact, not a damage-share estimate.  A 3P+2M team
        // must remain MIXED even when the physical members happen to contribute >70% of modeled
        // damage.  Genuine universal tank/healer/support roles are ignored for this classification.
        val damageType = b5r1StrictMemberDamageSystem(members)

        val vanguard = members.minBy { it.position ?: Int.MAX_VALUE }
        val effectiveCharacterCount = members.count { role ->
            canonicalLabyrinthRoleId(role.characterId) in context.effectiveCharacterIds
        }
        // Effective Effect is a per-role system hint, not a team-total reward. Average the
        // individual system points across the current team, then let the configured player/system
        // blend decide how much of that hint reaches the final score. This prevents Effective
        // Effect from bypassing or diluting a deliberately low player score.
        val effectiveCharacterSystemBonus = if (
            effectiveCharacterCount > 0 &&
            (context.encounterStrategy?.preferEffectiveCharacters ?: true)
        ) {
            effectiveCharacterCount * config.encounterEffectiveCharacterBonus / members.size
        } else {
            0.0
        }
        // Preferred guide capabilities are graded on their actual 0..100 strength.  A true
        // 1300-range AOE (100) must be worth more than a marginal 300-range AOE (55); treating both
        // as a binary "hit" was exactly why Rino could lose her intended NY-Karyl niche.
        val preferredCapabilityContribution = context.encounterStrategy?.let { strategy ->
            strategy.preferredCapabilities.sumOf { capability ->
                val target = strategy.preferredCapabilityMemberTargets[capability] ?: 1
                members.mapNotNull { role -> role.encounterCapabilityScore(capability) }
                    .filter { it >= 50.0 }
                    .sortedDescending()
                    .take(target)
                    .sumOf { it / 100.0 }
            }
        } ?: 0.0
        val fallbackCapabilityContribution = context.encounterStrategy?.fallbackPreferences.orEmpty().sumOf { fallback ->
            val primaryCount = members.count { role ->
                (role.encounterCapabilityScore(fallback.primaryCapability) ?: -1.0) >= 50.0
            }
            if (primaryCount >= fallback.primaryTargetCount) {
                0.0
            } else {
                members.map { role ->
                    fallback.anyOf.maxOfOrNull { capability ->
                        role.encounterCapabilityScore(capability) ?: 0.0
                    } ?: 0.0
                }
                    .filter { it >= 50.0 }
                    .sortedDescending()
                    .take(fallback.maximumCount)
                    .sumOf { it / 100.0 }
            }
        }
        val preferredCapabilityBonus =
            (preferredCapabilityContribution + fallbackCapabilityContribution +
                encounterRequirementContribution(members, context.encounterStrategy)) *
                config.encounterPreferredCapabilityBonus
        val encounterBonus = preferredCapabilityBonus
        val frontlineScore = vanguard.labyrinthVanguardStrength(context)
        val frontlineScoreForTeam = if (
            context.optimizeBossVanguardSynergy &&
            frontlineScore > config.minimumReliableVanguard
        ) {
            config.minimumReliableVanguard +
                (frontlineScore - config.minimumReliableVanguard) * config.bossExcessVanguardSurvivalRetention
        } else {
            frontlineScore
        }
        val sustainAnchor = members.maxBy { effectiveSustainScore(it) }
        val sustainScore = effectiveSustainScore(sustainAnchor)
        val hasReliableFrontline = frontlineScore >= config.minimumReliableVanguard
        val hasEffectiveSustain = sustainScore >= config.minimumEffectiveSustain
        val relevantReduction = when (context.effectiveEnemyDamageType) {
            LabyrinthEnemyDamageType.PHYSICAL -> members.maxOf { it.functions.physicalAttackReduction.b5r1OrZero() }
            LabyrinthEnemyDamageType.MAGIC -> members.maxOf { it.functions.magicAttackReduction.b5r1OrZero() }
            LabyrinthEnemyDamageType.MIXED -> (
                members.maxOf { it.functions.physicalAttackReduction.b5r1OrZero() } +
                    members.maxOf { it.functions.magicAttackReduction.b5r1OrZero() }
                ) / 2.0
            LabyrinthEnemyDamageType.UNKNOWN -> max(
                members.maxOf { it.functions.physicalAttackReduction.b5r1OrZero() },
                members.maxOf { it.functions.magicAttackReduction.b5r1OrZero() },
            )
        }
        val survivalSupport = (
            members.sumOf { it.support.universalSurvival.b5r1OrZero() } / members.size +
                members.maxOf { max(it.functions.healing.b5r1OrZero(), it.functions.regeneration.b5r1OrZero()) } +
                relevantReduction
            ) / 3.0
        val survivalScore = frontlineScoreForTeam * 0.70 + survivalSupport * 0.30

        val aoe = members.maxOf { it.functions.aoeDamage.b5r1OrZero() }
        val singleTarget = members.maxOf { it.functions.singleTargetDamage.b5r1OrZero() }
        val multiTarget = if (targetCount >= 3) {
            members.maxOf {
                it.functions.threeTargetDamage ?: (
                    scenarioDamage(it, LabyrinthRoleDamageType.PHYSICAL, 3) +
                        scenarioDamage(it, LabyrinthRoleDamageType.MAGIC, 3)
                    )
            }
        } else {
            members.maxOf {
                it.functions.twoTargetDamage ?: (
                    scenarioDamage(it, LabyrinthRoleDamageType.PHYSICAL, 2) +
                        scenarioDamage(it, LabyrinthRoleDamageType.MAGIC, 2)
                    )
            }
        }
        val bossValue = members.maxOf { it.functions.bossMechanismValue.b5r1OrZero() }
        val matchingDefenseDown = when (damageType) {
            LabyrinthTeamDamageType.PHYSICAL -> members.maxOf { it.functions.physicalDefenseDown.b5r1OrZero() }
            LabyrinthTeamDamageType.MAGIC -> members.maxOf { it.functions.magicDefenseDown.b5r1OrZero() }
            LabyrinthTeamDamageType.MIXED -> (
                members.maxOf { it.functions.physicalDefenseDown.b5r1OrZero() } +
                    members.maxOf { it.functions.magicDefenseDown.b5r1OrZero() }
                ) / 2.0
            LabyrinthTeamDamageType.NONE -> 0.0
        }
        val scenarioFunction = if (targetCount == 1) singleTarget else max(aoe, multiTarget)
        val functionScore = (scenarioFunction + matchingDefenseDown + bossValue) / 3.0
        val missing = buildList {
            if (!hasReliableFrontline) add("可靠一号位/掩护者")
            if (context.defenseMarkStacks < config.defenseBaselineStacks && !hasEffectiveSustain) {
                add("有效治疗/续航")
            }
            if (targetCount >= 2 && multiTarget < config.minimumAoe) add("${targetCount}目标输出")
            if (targetCount == 1 && singleTarget < config.minimumSingleTarget) add("单体输出")
            if (matchingDefenseDown < config.minimumDefenseDown) {
                add(
                    when (damageType) {
                        LabyrinthTeamDamageType.PHYSICAL -> "物防降低"
                        LabyrinthTeamDamageType.MAGIC -> "魔防降低"
                        LabyrinthTeamDamageType.MIXED -> "双体系防御降低"
                        LabyrinthTeamDamageType.NONE -> "有效输出体系"
                    },
                )
            }
        }

        val ratedRoles = members.mapNotNull(LabyrinthRoleProfile::effectiveUserScore)
        val playerScore = ratedRoles.takeIf { it.isNotEmpty() }?.average()
        val playerCoverage = ratedRoles.size.toDouble() / members.size
        val damageScore = totalDamage / members.size
        val pureRolePenalty = if (context.defenseMarkStacks >= config.defenseBaselineStacks) {
            members.sumOf { role ->
                val bossSpecialist = role.functions.bossMechanismValue.b5r1OrZero() >= config.bossSpecialistThreshold ||
                    role.functions.selfSustain.b5r1OrZero() >= config.bossSpecialistThreshold
                val pure = role.functions.pureTank.b5r1OrZero() >= config.pureRoleThreshold ||
                    role.functions.pureHealer.b5r1OrZero() >= config.pureRoleThreshold
                if (pure && !bossSpecialist) config.postFourDefensePureRolePenalty else 0.0
            }
        } else {
            0.0
        }
        var pureTankCount = 0
        var pureHealerCount = 0
        for (role in members) {
            if (hasCompositeOffense(role, targetCount, damageType)) continue
            val tank = role.functions.pureTank.b5r1OrZero()
            val healer = role.functions.pureHealer.b5r1OrZero()
            // A dual-tagged defensive role occupies one slot, not two redundant slots.
            if (tank >= config.pureRoleThreshold && tank >= healer) pureTankCount++
            else if (healer >= config.pureRoleThreshold) pureHealerCount++
        }
        val duplicates = (pureTankCount - 1).coerceAtLeast(0) + (pureHealerCount - 1).coerceAtLeast(0)
        val survivalNeedsHelp = !hasReliableFrontline ||
            (context.defenseMarkStacks < config.defenseBaselineStacks && !hasEffectiveSustain)
        val duplicateMultiplier = when {
            context.defenseMarkStacks >= config.defenseBaselineStacks -> 1.0
            context.defenseMarkStacks <= 1 -> config.lowestDefenseDuplicateMultiplier
            else -> config.lowDefenseDuplicateMultiplier
        } * if (context.survivalRecovery || survivalNeedsHelp) config.survivalRecoveryDuplicateMultiplier else 1.0
        val duplicatePenalty = duplicates * config.duplicatePureRolePenalty * duplicateMultiplier
        var survivalMultiplier = 1.0
        var survivalGatePenalty = 0.0
        if (context.defenseMarkStacks <= 1) {
            if (!hasReliableFrontline && !hasEffectiveSustain) {
                survivalMultiplier = config.lowestDefenseNoAnchorMultiplier
                survivalGatePenalty = config.lowestDefenseGatePenalty
            } else if (!hasReliableFrontline || !hasEffectiveSustain) {
                survivalMultiplier = config.lowestDefenseOneAnchorMultiplier
            }
        } else if (context.defenseMarkStacks <= 3) {
            if (!hasReliableFrontline && !hasEffectiveSustain) {
                survivalMultiplier = config.lowDefenseNoAnchorMultiplier
                survivalGatePenalty = config.lowDefenseGatePenalty
            } else if (!hasReliableFrontline || !hasEffectiveSustain) {
                survivalMultiplier = config.lowDefenseOneAnchorMultiplier
            }
        }

        val systemDamage = (damageScore / config.damageSoftCeiling).coerceIn(0.0, 100.0)
        val systemSurvival = survivalScore.coerceIn(0.0, 100.0)
        val systemFunction = functionScore.coerceIn(0.0, 100.0)
        val systemCohesion = if (totalDamage > 0.0) {
            (max(physicalRatio, 1.0 - physicalRatio) * 100.0).coerceIn(50.0, 100.0)
        } else {
            100.0
        }
        val systemFormation = (
            100.0 - survivalGatePenalty * config.formationSurvivalGatePenaltyMultiplier -
                missing.size * config.formationMissingFunctionPenalty - pureRolePenalty
            ).coerceIn(0.0, 100.0)
        val mixedDamagePenalty = if (
            context.preferSingleDamageSystem && damageType == LabyrinthTeamDamageType.MIXED
        ) {
            config.battleMixedDamagePenalty
        } else {
            0.0
        }
        val systemComponents = if (!explain) emptyMap() else linkedMapOf(
            "预计输出" to systemDamage,
            "生存" to systemSurvival,
            "功能" to systemFunction,
            "阵型完整性" to systemFormation,
            "体系一致性" to systemCohesion,
            "有效效果角色系统加分" to effectiveCharacterSystemBonus,
            "重复纯职能扣分" to -duplicatePenalty,
            "物法混编偏好" to -mixedDamagePenalty,
            "遭遇攻略偏好" to preferredCapabilityBonus,
        )
        val rawSystemScore = (systemDamage * config.systemDamageWeight +
            systemSurvival * config.systemSurvivalWeight +
            systemFunction * config.systemFunctionWeight +
            systemFormation * config.systemFormationWeight +
            systemCohesion * config.systemCohesionWeight +
            effectiveCharacterSystemBonus).coerceIn(0.0, 100.0)
        val systemScore = max(0.0, rawSystemScore - duplicatePenalty - mixedDamagePenalty + encounterBonus)
        val effectivePlayerWeight = if (playerScore == null) 0.0 else config.playerWeight * playerCoverage
        val effectiveSystemWeight = 1.0 - effectivePlayerWeight
        val preRiskScore = (playerScore ?: 0.0) * effectivePlayerWeight + rawSystemScore * effectiveSystemWeight
        val riskAdjustedScore = max(
            0.0,
            preRiskScore * survivalMultiplier - survivalGatePenalty * config.riskGatePenaltyWeight,
        )
        val score = max(0.0, riskAdjustedScore - duplicatePenalty - mixedDamagePenalty + encounterBonus)
        val components = if (!explain) emptyMap() else linkedMapOf(
            "玩家经验" to (playerScore ?: 0.0) * effectivePlayerWeight,
            "系统适配" to rawSystemScore * effectiveSystemWeight,
            "生存风险" to riskAdjustedScore - preRiskScore,
            "重复纯职能" to -duplicatePenalty,
            "4守备后纯职能扣分" to -pureRolePenalty,
            "物法混编" to -mixedDamagePenalty,
            "遭遇攻略偏好" to preferredCapabilityBonus,
        )
        val reasons = if (!explain) emptyList() else buildList {
            labyrinthEncounterFormationReason(members, context)?.let(::add)
            if (duplicates > 0) {
                add("重复纯职能：纯坦${pureTankCount}名、纯治疗${pureHealerCount}名；扣${duplicatePenalty}分" +
                    if (context.survivalRecovery || survivalNeedsHelp) "（生存不足或失败后兜底，扣分减半）" else "")
            }
            add("预计${damageType.b5r1Label()}输出，物理占比${(physicalRatio * 100).toInt()}%")
            if (mixedDamagePenalty > 0.0) {
                add(
                    "战斗编组启用单一物/法体系偏好：当前为混合输出，软扣${mixedDamagePenalty.toInt()}分；" +
                        "若混编综合优势足够大仍可入选",
                )
            }
            context.encounterStrategy?.let { strategy ->
                add("遭遇攻略：${strategy.identityName}")
                if (strategy.requirements.isNotEmpty()) {
                    add("攻略偏好（尽力满足）：${strategy.requirements.joinToString("、") { it.label }}")
                }
                encounterRequirementShortfallReason(members, strategy)?.let { add(0, it) }
            }
            if (effectiveCharacterCount > 0) {
                add(
                    "官方有效效果角色：${effectiveCharacterCount}名；每名系统侧+" +
                        "${config.encounterEffectiveCharacterBonus.toInt()}，队伍系统均值+" +
                        "${"%.1f".format(effectiveCharacterSystemBonus)}，再按玩家/系统权重融合",
                )
            }
            if (activeBonuses.isNotEmpty()) {
                add(
                    "属性增伤：" + activeBonuses.entries.joinToString("、") { (attribute, bonus) ->
                        "${attribute.label}${attributeCounts.getValue(attribute)}人+${(bonus * 100).toInt()}%"
                    },
                )
            }
            val vanguardPhysicalUplift = roleTeamUplift(vanguard, LabyrinthRoleDamageType.PHYSICAL)
            val vanguardMagicUplift = roleTeamUplift(vanguard, LabyrinthRoleDamageType.MAGIC)
            add("一号位${vanguard.displayName}，可靠度${frontlineScore.toInt()}")
            if (context.optimizeBossVanguardSynergy) {
                val durability = vanguard.vanguardProfile?.let { profile ->
                    "物耐${profile.physicalDurability.b5r1OrZero().toInt()}/法耐${profile.magicDurability.b5r1OrZero().toInt()}；"
                }.orEmpty()
                add(
                    "Boss一号位联合优化：${durability}过生存线后超额肉度按" +
                        "${(config.bossExcessVanguardSurvivalRetention * 100).toInt()}%计入；" +
                        "该T物理队增益${(vanguardPhysicalUplift * 100).toInt()}%、" +
                        "法术队增益${(vanguardMagicUplift * 100).toInt()}%",
                )
            }
            add("玩家评分覆盖${(playerCoverage * 100).toInt()}%，有效权重${(effectivePlayerWeight * 100).toInt()}%")
            if (survivalMultiplier < 1.0) {
                add("低守备生存门槛：存活输出倍率${(survivalMultiplier * 100).toInt()}%")
            }
            if (context.defenseMarkStacks >= config.defenseBaselineStacks) {
                add("已达${config.defenseBaselineStacks}守备，纯坦/纯治疗降权，输出与复合功能升权")
            } else {
                add("未达${config.defenseBaselineStacks}守备，保留一号位与续航权重")
            }
            if (missing.isNotEmpty()) add("仍缺：${missing.joinToString("、")}")
        }
        return LabyrinthTeamEvaluation(
            // Preserve enumeration order during search, including equal-position tie breaks.
            members = if (explain) members.sortedBy { it.position ?: Int.MAX_VALUE } else members.toList(),
            score = score,
            systemScore = systemScore,
            playerScore = playerScore,
            playerCoverage = playerCoverage,
            requestedPlayerWeight = config.playerWeight,
            effectivePlayerWeight = effectivePlayerWeight,
            damageType = damageType,
            physicalRatio = physicalRatio,
            physicalDamage = physicalDamage,
            magicDamage = magicDamage,
            physicalTeamUplift = physicalUplift,
            magicTeamUplift = magicUplift,
            targetCount = targetCount,
            attributeCounts = attributeCounts,
            activeAttributeBonuses = activeBonuses,
            vanguardCharacterId = vanguard.characterId,
            frontlineScore = frontlineScore,
            sustainScore = sustainScore,
            hasReliableFrontline = hasReliableFrontline,
            hasEffectiveSustain = hasEffectiveSustain,
            survivalMultiplier = survivalMultiplier,
            survivalGatePenalty = survivalGatePenalty,
            survivalAnchorCharacterId = when {
                hasReliableFrontline -> vanguard.characterId
                hasEffectiveSustain -> sustainAnchor.characterId
                else -> null
            },
            missingCriticalFunctions = missing,
            systemComponents = systemComponents,
            components = components,
            reasons = reasons,
            pureTankCount = pureTankCount,
            pureHealerCount = pureHealerCount,
            duplicateRolePenalty = duplicatePenalty,
        )
    }

    private fun hasCompositeOffense(
        role: LabyrinthRoleProfile,
        targetCount: Int,
        teamType: LabyrinthTeamDamageType,
    ): Boolean {
        val threshold = config.compositeOffenseThreshold
        val damage = scenarioDamage(role, LabyrinthRoleDamageType.PHYSICAL, targetCount) +
            scenarioDamage(role, LabyrinthRoleDamageType.MAGIC, targetCount)
        if (damage >= threshold || role.support.universalOffense.b5r1OrZero() >= threshold) return true
        // Off-system support alone does not turn a pure healer/tank into useful offensive support.
        val physical = teamType == LabyrinthTeamDamageType.PHYSICAL || teamType == LabyrinthTeamDamageType.MIXED
        val magic = teamType == LabyrinthTeamDamageType.MAGIC || teamType == LabyrinthTeamDamageType.MIXED
        return (physical && (role.support.physicalOffense.b5r1OrZero() >= threshold ||
            role.functions.physicalDefenseDown.b5r1OrZero() >= threshold)) ||
            (magic && (role.support.magicOffense.b5r1OrZero() >= threshold ||
                role.functions.magicDefenseDown.b5r1OrZero() >= threshold))
    }

    private fun scenarioDamage(
        role: LabyrinthRoleProfile,
        system: LabyrinthRoleDamageType?,
        targetCount: Int,
    ): Double {
        val modeled = when (system) {
            LabyrinthRoleDamageType.PHYSICAL -> when (targetCount.coerceAtMost(3)) {
                1 -> role.physicalDamage1Target
                2 -> role.physicalDamage2Target
                else -> role.physicalDamage3Target
            }
            LabyrinthRoleDamageType.MAGIC -> when (targetCount.coerceAtMost(3)) {
                1 -> role.magicDamage1Target
                2 -> role.magicDamage2Target
                else -> role.magicDamage3Target
            }
            else -> null
        }
        return modeled ?: when (system) {
            LabyrinthRoleDamageType.PHYSICAL -> role.physicalDamagePotential.b5r1OrZero()
            LabyrinthRoleDamageType.MAGIC -> role.magicDamagePotential.b5r1OrZero()
            LabyrinthRoleDamageType.MIXED ->
                role.physicalDamagePotential.b5r1OrZero() + role.magicDamagePotential.b5r1OrZero()
            else -> 0.0
        }
    }

    private fun roleTeamUplift(role: LabyrinthRoleProfile, system: LabyrinthRoleDamageType): Double {
        val modeled = when (system) {
            LabyrinthRoleDamageType.PHYSICAL -> role.physicalTeamUplift
            LabyrinthRoleDamageType.MAGIC -> role.magicTeamUplift
            else -> null
        }
        if (role.modelStatus.startsWith("database-v") && modeled != null) {
            return modeled.coerceIn(
                config.modeledRoleNegativeUpliftFloor,
                config.modeledRolePositiveUpliftCap,
            )
        }
        val specific = when (system) {
            LabyrinthRoleDamageType.PHYSICAL -> role.support.physicalOffense.b5r1OrZero()
            LabyrinthRoleDamageType.MAGIC -> role.support.magicOffense.b5r1OrZero()
            else -> 0.0
        }
        return ((specific + role.support.universalOffense.b5r1OrZero()) / 100.0 * config.supportMultiplierAt100)
            .coerceIn(0.0, config.legacyRoleUpliftCap)
    }

    private fun combinedTeamUplift(
        members: List<LabyrinthRoleProfile>,
        system: LabyrinthRoleDamageType,
    ): Double = members.fold(1.0) { factor, role ->
        factor * (1.0 + roleTeamUplift(role, system))
    }.minus(1.0).coerceIn(config.combinedTeamUpliftFloor, config.combinedTeamUpliftCap)

    private fun effectiveSustainScore(role: LabyrinthRoleProfile): Double = maxOf(
        role.functions.healing.b5r1OrZero(),
        role.functions.regeneration.b5r1OrZero(),
        role.support.universalSurvival.b5r1OrZero() * 0.85,
    )

    private companion object {
        const val TEAM_SIZE = 5

        /**
         * Distinct-vanguard first teams tried when the beam holds no disjoint set. Each seed costs
         * one bounded search per follow-up team, so this stays small enough for on-device planning.
         */
        const val MAX_RESIDUAL_FIRST_TEAM_SEEDS = 6
    }
}


class Batch5Round1OptimizerReference(
    private val scorer: Batch5Round1ScorerReference,
    /**
     * Worker threads for the combination walk. Deliberately not tied to the CPU count: the game
     * and this app share one virtual machine, and saturating every core makes the game drop
     * frames, which lengthens the run more than the search it was meant to speed up. Set to 1 to
     * force the serial path.
     */
    private val searchThreads: Int = B5R1_ROLE_SEARCH_THREADS,
) {
    val scoringConfig: LabyrinthTeamScoringConfig
        get() = scorer.config

    fun bestFormation(
        roster: Collection<LabyrinthRoleProfile>,
        context: LabyrinthRoleDecisionContext,
        requiredCharacterId: String? = null,
    ): LabyrinthTeamEvaluation? = rankedFormationsInternal(
        roster, context, requiredCharacterId, systemOnly = false, limit = 1,
        requireFrontmostTank = false,
    ).firstOrNull()

    /**
     * Battle-only formation search. Reward/event marginal-value calculations deliberately keep
     * using [bestFormation], so this hard tank rule does not rewrite acquisition preferences.
     */
    /**
     * Best battle formation that contains every id in [requiredIds]. The bounded search pool
     * keeps the required roles in, and only combinations holding all of them are scored, so a
     * guide-mandated or official effective-effect role cannot be out-scored by a generic
     * high-rated pick. Returns null when no valid formation includes them (no compatible tank,
     * position clash).
     */
    fun bestBattleFormationIncluding(
        roster: Collection<LabyrinthRoleProfile>,
        context: LabyrinthRoleDecisionContext,
        requiredIds: Set<String>,
    ): LabyrinthTeamEvaluation? {
        if (requiredIds.isEmpty()) return bestBattleFormation(roster, context)
        val unique = roster.distinctBy(LabyrinthRoleProfile::characterId)
        if (!requiredIds.all { id -> unique.any { it.characterId == id } }) return null
        if (context.preferSingleDamageSystem && context.encounterStrategy?.vanguardIsolationRadius == null) {
            val cohesive = rankedFormationsInternal(
                roster = unique, context = context, requiredCharacterId = null, systemOnly = false,
                limit = 1, requireFrontmostTank = true, forbidMixedDamageSystem = true,
                requiredCharacterIds = requiredIds,
            ).firstOrNull()
            if (cohesive != null) return cohesive
        }
        return rankedFormationsInternal(
            roster = unique, context = context, requiredCharacterId = null, systemOnly = false,
            limit = 1, requireFrontmostTank = true, requiredCharacterIds = requiredIds,
        ).firstOrNull()
    }

    fun bestBattleFormation(
        roster: Collection<LabyrinthRoleProfile>,
        context: LabyrinthRoleDecisionContext,
    ): LabyrinthTeamEvaluation? {
        if (context.preferSingleDamageSystem && context.encounterStrategy?.vanguardIsolationRadius == null) {
            val cohesive = rankedFormationsInternal(
                roster = roster,
                context = context,
                requiredCharacterId = null,
                systemOnly = false,
                limit = 1,
                requireFrontmostTank = true,
                forbidMixedDamageSystem = true,
            ).firstOrNull()
            if (cohesive != null) return cohesive
        }
        return rankedFormationsInternal(
            roster = roster,
            context = context,
            requiredCharacterId = null,
            systemOnly = false,
            limit = 1,
            requireFrontmostTank = true,
        ).firstOrNull()
    }

    /**
     * Best formation with the frontmost-tank gate switched off. Used only to fill Boss follow-up
     * slots that would otherwise stay empty: an unsafe team that lands a few hits is strictly
     * better than no team when the Boss is down to a sliver.
     */
    fun bestSupplementFormation(
        roster: Collection<LabyrinthRoleProfile>,
        context: LabyrinthRoleDecisionContext,
    ): LabyrinthTeamEvaluation? = rankedFormationsInternal(
        roster = roster,
        context = context,
        requiredCharacterId = null,
        systemOnly = false,
        limit = 1,
        requireFrontmostTank = false,
    ).firstOrNull()

    fun bestSystemFormation(
        roster: Collection<LabyrinthRoleProfile>,
        context: LabyrinthRoleDecisionContext,
        requiredCharacterId: String? = null,
    ): LabyrinthTeamEvaluation? = rankedFormationsInternal(
        roster = roster,
        context = context,
        requiredCharacterId = requiredCharacterId,
        systemOnly = true,
        limit = 1,
        requireFrontmostTank = false,
    ).firstOrNull()

    fun rankedFormations(
        roster: Collection<LabyrinthRoleProfile>,
        context: LabyrinthRoleDecisionContext,
        requiredCharacterId: String? = null,
    ): List<LabyrinthTeamEvaluation> = rankedFormationsInternal(
        roster = roster,
        context = context,
        requiredCharacterId = requiredCharacterId,
        systemOnly = false,
        requireFrontmostTank = false,
    )

    fun rankedBattleFormations(
        roster: Collection<LabyrinthRoleProfile>,
        context: LabyrinthRoleDecisionContext,
    ): List<LabyrinthTeamEvaluation> = rankedFormationsInternal(
        roster = roster,
        context = context,
        requiredCharacterId = null,
        systemOnly = false,
        requireFrontmostTank = true,
    )

    private fun rankedFormationsInternal(
        roster: Collection<LabyrinthRoleProfile>,
        context: LabyrinthRoleDecisionContext,
        requiredCharacterId: String?,
        systemOnly: Boolean,
        limit: Int = scorer.config.teamSearchBeamWidth,
        requireFrontmostTank: Boolean,
        forbidMixedDamageSystem: Boolean = false,
        requiredCharacterIds: Set<String> = emptySet(),
    ): List<LabyrinthTeamEvaluation> {
        LabyrinthFourThreadRuntime.cooperate()
        val unique = roster.distinctBy(LabyrinthRoleProfile::characterId)
        if (unique.isEmpty()) return emptyList()
        if (requiredCharacterId != null && unique.none { it.characterId == requiredCharacterId }) return emptyList()
        if (requireFrontmostTank && unique.none {
                it.isEligibleBattleVanguard(context, scorer.config.vanguardGate(context))
            }) {
            return emptyList()
        }
        val searchPool = if (unique.size <= scorer.config.exhaustiveRosterLimit) {
            unique
        } else if (requireFrontmostTank) {
            battleSearchPool(unique, context, reservedIds = requiredCharacterIds)
        } else {
            unique.sortedByDescending { individualSearchPriority(it, context) }
                .take(scorer.config.exhaustiveRosterLimit)
                .let { limited ->
                    if (requiredCharacterId == null || limited.any { it.characterId == requiredCharacterId }) {
                        limited
                    } else {
                        limited.dropLast(1) + requireNotNull(unique.firstOrNull { it.characterId == requiredCharacterId })
                    }
                }
        }
        val size = minOf(TEAM_SIZE, searchPool.size)
        // Worst candidate first. For equal scores keep the earlier enumeration, exactly like
        // the old stable full sort, without retaining tens of thousands of evaluations.
        val order = compareByDescending<RankedCandidate> { it.formationRisk }
            .thenBy { it.score }.thenByDescending { it.ordinal }
        val comboCount = binomialCount(searchPool.size, size)
        val filter = CombinationFilter(
            context = context,
            requiredCharacterId = requiredCharacterId,
            requiredCharacterIds = requiredCharacterIds,
            vanguardGate = if (requireFrontmostTank) scorer.config.vanguardGate(context) else null,
            forbidMixedDamageSystem = forbidMixedDamageSystem,
            systemOnly = systemOnly,
        )
        val ranked = if (searchThreads > 1 && comboCount >= B5R1_PARALLEL_MIN_COMBINATIONS) {
            searchFormationsParallel(searchPool, size, comboCount.toInt(), filter, limit, order)
        } else {
            val best = PriorityQueue(order)
            var ordinal = 0L
            visitCombinations(searchPool, size) { team ->
                if ((ordinal and 31L) == 0L) LabyrinthFourThreadRuntime.cooperate()
                filter.offer(team, ordinal++, best, limit)
            }
            best.sortedWith(order.reversed())
        }
        return ranked.map { scorer.evaluate(it.members, context) }
    }

    /** The per-team gate and score shared verbatim by the serial and parallel walks. */
    private inner class CombinationFilter(
        private val context: LabyrinthRoleDecisionContext,
        private val requiredCharacterId: String?,
        private val requiredCharacterIds: Set<String>,
        private val vanguardGate: Double?,
        private val forbidMixedDamageSystem: Boolean,
        private val systemOnly: Boolean,
    ) {
        fun offer(team: List<LabyrinthRoleProfile>, ordinal: Long, best: PriorityQueue<RankedCandidate>, limit: Int) {
            if (requiredCharacterId != null && team.none { it.characterId == requiredCharacterId }) return
            if (requiredCharacterIds.isNotEmpty() && !requiredCharacterIds.all { id -> team.any { it.characterId == id } }) return
            if (vanguardGate != null && !team.hasEligibleVanguardAtActualFront(context, vanguardGate)) return
            val evaluation = scorer.evaluateForSearch(team, context)
            if (forbidMixedDamageSystem &&
                evaluation.damageType != LabyrinthTeamDamageType.PHYSICAL &&
                evaluation.damageType != LabyrinthTeamDamageType.MAGIC
            ) {
                return
            }
            val score = if (systemOnly) evaluation.systemScore else evaluation.score
            val formationRisk = labyrinthEncounterFormationRisk(team, context)
            val worst = best.peek()
            // Same lexicographic comparison as `order`, including Double.compare for signed
            // zero/NaN and enumeration ordinals. Only retained candidates need a wrapper object.
            val improves = worst != null && isBetter(score, formationRisk, ordinal, worst)
            if (best.size < limit || improves) {
                if (best.size == limit) best.poll()
                // evaluateForSearch returns independent members; both walks may reuse `team`.
                best.add(RankedCandidate(evaluation.members, score, ordinal, formationRisk))
            }
        }
    }

    private fun isBetter(score: Double, formationRisk: Int, ordinal: Long, worst: RankedCandidate): Boolean {
        val riskComparison = worst.formationRisk.compareTo(formationRisk)
        if (riskComparison != 0) return riskComparison > 0
        val scoreComparison = java.lang.Double.compare(score, worst.score)
        if (scoreComparison != 0) return scoreComparison > 0
        return worst.ordinal.compareTo(ordinal) > 0
    }

    /**
     * Splits the [comboCount] lexicographic combinations into [searchThreads] contiguous ranges,
     * ranks each range to its local top [limit], then merges. The merge order is (score desc,
     * enumeration index asc), which is the serial path's order: the serial ordinal counts only
     * teams that passed the gate, this one counts every enumerated combination, but both are
     * strictly increasing along the same enumeration, so every tie resolves to the same team.
     */
    private fun searchFormationsParallel(
        searchPool: List<LabyrinthRoleProfile>,
        size: Int,
        comboCount: Int,
        filter: CombinationFilter,
        limit: Int,
        order: Comparator<RankedCandidate>,
    ): List<RankedCandidate> {
        // Only (n,k) lexicographic indices are shared; roster/context/evaluation remain call-local.
        // Oversize dimensions keep the original fresh table rather than becoming resident.
        val flat = LabyrinthCombinationIndexCache.shared.get(searchPool.size, size, comboCount)
        val chunkCount = minOf(searchThreads, comboCount)
        val chunkSize = (comboCount + chunkCount - 1) / chunkCount
        val tasks = (0 until chunkCount).map { chunk ->
            Callable {
                val best = PriorityQueue(order)
                val team = ArrayList<LabyrinthRoleProfile>(size)
                val from = chunk * chunkSize
                val until = minOf(comboCount, from + chunkSize)
                for (index in from until until) {
                    if ((index and 31) == 0) LabyrinthFourThreadRuntime.cooperate()
                    team.clear()
                    val base = index * size
                    for (slot in 0 until size) team.add(searchPool[flat[base + slot]])
                    filter.offer(team, index.toLong(), best, limit)
                }
                best.toList()
            }
        }
        val partials = B5R1_ROLE_SEARCH_EXECUTOR.invokeAll(tasks).map { it.get() }
        val merged = ArrayList<RankedCandidate>(partials.sumOf { it.size })
        partials.forEach(merged::addAll)
        merged.sortWith(order.reversed())
        return merged.take(limit)
    }

    /** C(n, k), used to size the parallel split. */
    private fun binomialCount(n: Int, k: Int): Long {
        if (k < 0 || k > n) return 0L
        var result = 1L
        for (index in 0 until k) result = result * (n - index) / (index + 1)
        return result
    }

    /**
     * A bounded search must not trim away a low-scoring tank before combinations are generated.
     * Reserve one structurally valid cohort around the database-frontmost available tank, then
     * fill remaining beam input slots by the ordinary individual priority.
     */
    internal fun battleSearchPool(
        unique: List<LabyrinthRoleProfile>,
        context: LabyrinthRoleDecisionContext,
        reservedIds: Set<String> = emptySet(),
    ): List<LabyrinthRoleProfile> {
        val limit = scorer.config.exhaustiveRosterLimit
        val teamSize = minOf(TEAM_SIZE, unique.size)
        val gate = scorer.config.vanguardGate(context)
        val frontmostTank = unique
            .asSequence()
            .filter { it.isEligibleBattleVanguard(context, gate) }
            .minWithOrNull(compareBy<LabyrinthRoleProfile> { it.position }.thenBy { it.characterId })
            ?: return emptyList()
        val tankPosition = requireNotNull(frontmostTank.position)
        val compatible = unique
            .asSequence()
            .filter { role ->
                role.characterId != frontmostTank.characterId &&
                    role.position != null &&
                    (role.isEligibleBattleVanguard(context, gate) ||
                        requireNotNull(role.position) > tankPosition)
            }
            .sortedWith(compareBy<LabyrinthRoleProfile> {
                labyrinthEncounterFormationRisk(listOf(frontmostTank, it), context)
            }.thenByDescending { individualSearchPriority(it, context) })
            .take(teamSize - 1)
            .toList()
        if (compatible.size != teamSize - 1) return emptyList()

        val structuralCohort = listOf(frontmostTank) + compatible
        // Explicitly reserved roles (a guide core the caller decided to build the team around)
        // always enter the pool. The generic Effective Effect hint alone does not: it contributes
        // only through the role's weighted individual score, so a low score can still leave that
        // role outside the bounded pool unless the caller reserves it.
        val reserved = unique.filter { it.characterId in reservedIds && it.characterId != frontmostTank.characterId }
        val forcedIds = (structuralCohort + reserved).mapTo(hashSetOf(), LabyrinthRoleProfile::characterId)
        val remainder = unique
            .asSequence()
            .filterNot { it.characterId in forcedIds }
            .sortedByDescending { individualSearchPriority(it, context) }
            .toList()
        return (structuralCohort + reserved + remainder).distinctBy { it.characterId }.take(limit)
    }

    private data class RankedCandidate(
        val members: List<LabyrinthRoleProfile>,
        val score: Double,
        val ordinal: Long,
        val formationRisk: Int,
    )

    private fun individualSearchPriority(role: LabyrinthRoleProfile, context: LabyrinthRoleDecisionContext): Double {
        LabyrinthFourThreadRuntime.checkpoint()
        val targetCount = context.targetCount
        val physical = when (targetCount.coerceAtMost(3)) {
            1 -> role.physicalDamage1Target
            2 -> role.physicalDamage2Target
            else -> role.physicalDamage3Target
        } ?: role.physicalDamagePotential
        val magic = when (targetCount.coerceAtMost(3)) {
            1 -> role.magicDamage1Target
            2 -> role.magicDamage2Target
            else -> role.magicDamage3Target
        } ?: role.magicDamagePotential
        val scenarioFunction = if (targetCount == 1) {
            role.functions.singleTargetDamage.b5r1OrZero()
        } else {
            maxOf(
                role.functions.aoeDamage.b5r1OrZero(),
                if (targetCount >= 3) role.functions.threeTargetDamage.b5r1OrZero()
                else role.functions.twoTargetDamage.b5r1OrZero(),
            )
        }
        val effectiveSystemBonus = if (
            canonicalLabyrinthRoleId(role.characterId) in context.effectiveCharacterIds &&
            (context.encounterStrategy?.preferEffectiveCharacters ?: true)
        ) {
            scorer.config.encounterEffectiveCharacterBonus
        } else {
            0.0
        }
        val guideCapabilityPriority = context.encounterStrategy?.preferredCapabilities.orEmpty().sumOf { capability ->
            if ((role.encounterCapabilityScore(capability) ?: -1.0) >= 50.0) {
                scorer.config.encounterPreferredCapabilityBonus
            } else {
                0.0
            }
        }
        // Keep the prefilter on the same conceptual scale as the final player/system blend.
        // Effective Effect is added only to the system side, so a low player score remains low
        // instead of being bypassed by an unconditional post-blend bonus.
        val modeledTeamUpliftPriority = maxOf(
            role.physicalTeamUplift.b5r1OrZero(),
            role.magicTeamUplift.b5r1OrZero(),
        ).coerceAtLeast(0.0).times(100.0).coerceAtMost(100.0)
        val baseSystemPriority = (
            maxOf(physical.b5r1OrZero(), magic.b5r1OrZero()) +
                role.functions.reliableVanguard.b5r1OrZero() * 0.5 +
                modeledTeamUpliftPriority * 0.45 +
                maxOf(
                    role.functions.physicalDefenseDown.b5r1OrZero(),
                    role.functions.magicDefenseDown.b5r1OrZero(),
                ) * 0.3 +
                scenarioFunction * 0.2
            ) / 2.45
        val requirementPriority = encounterRequirementContribution(listOf(role), context.encounterStrategy) *
            scorer.config.encounterPreferredCapabilityBonus
        val systemPriority = (baseSystemPriority + effectiveSystemBonus + guideCapabilityPriority + requirementPriority)
            .coerceIn(0.0, 100.0)
        val playerScore = role.effectiveUserScore
        val playerWeight = if (playerScore == null) 0.0 else scorer.config.playerWeight
        return (playerScore ?: 0.0) * playerWeight + systemPriority * (1.0 - playerWeight)
    }

    private fun <T> visitCombinations(values: List<T>, size: Int, consume: (List<T>) -> Unit) {
        val working = ArrayList<T>(size)
        fun visit(start: Int) {
            if (working.size == size) {
                consume(working)
                return
            }
            val remaining = size - working.size
            for (index in start..values.size - remaining) {
                working += values[index]
                visit(index + 1)
                working.removeAt(working.lastIndex)
            }
        }
        visit(0)
    }

    private companion object {
        const val TEAM_SIZE = 5

        /**
         * Distinct-vanguard first teams tried when the beam holds no disjoint set. Each seed costs
         * one bounded search per follow-up team, so this stays small enough for on-device planning.
         */
        const val MAX_RESIDUAL_FIRST_TEAM_SEEDS = 6
    }
}


class Batch5Round1RolePolicyReference(
    private val optimizer: Batch5Round1OptimizerReference,
    private val config: LabyrinthRoleChoiceConfig = LabyrinthRoleChoiceConfig(),
) {
    fun chooseOneRole(
        candidateIds: List<String>,
        acquiredCharacterIds: Set<String>,
        profiles: Map<String, LabyrinthRoleProfile>,
        context: LabyrinthRoleDecisionContext,
        requireStrictProfiles: Boolean = config.requireStrictProfiles,
    ): LabyrinthOneRoleDecision {
        if (candidateIds.isEmpty() || candidateIds.distinct().size != candidateIds.size) {
            return LabyrinthOneRoleDecision.Unavailable("候选角色必须稳定识别且互不重复")
        }
        // The game does not offer an already-owned role in a three-card reward. A collision is
        // therefore stale local roster evidence, not a reason to strand the run. Exclude the
        // visible candidates from the *scoring* roster so none is counted twice, but still rank
        // all three visible cards normally. The caller records the discrepancy for diagnosis.
        val scoringAcquiredIds = acquiredCharacterIds - candidateIds.toSet()
        val requiredIds = candidateIds.toSet() + scoringAcquiredIds
        val missing = requiredIds.filterNot(profiles::containsKey).sorted()
        if (missing.isNotEmpty()) {
            return LabyrinthOneRoleDecision.Unavailable(
                reason = "角色资料不完整，保持等待而不盲选：${missing.joinToString()}",
                missingProfileIds = missing,
            )
        }
        val incomplete = requiredIds.filterNot { profiles.getValue(it).isStrictDecisionReady }.sorted()
        if (requireStrictProfiles && incomplete.isNotEmpty()) {
            return LabyrinthOneRoleDecision.Unavailable(
                reason = "角色资料缺少严格决策所需的属性/职阶/物法/站位，保持等待：${incomplete.joinToString()}",
                incompleteProfileIds = incomplete,
            )
        }
        val acquired = scoringAcquiredIds.map(profiles::getValue)
        // Context, optimizer and config stay fixed only within this invocation. Share repeated
        // sub-searches (including the candidates' common preceding teams), never across calls.
        val systemFormationCache = mutableMapOf<SystemFormationKey, LabyrinthTeamEvaluation?>()
        val rankings = candidateIds.map { candidateId ->
            val candidate = profiles.getValue(candidateId)
            val pool = acquired + candidate
            val placement = requireNotNull(bestCandidatePlacementPlan(pool, candidate, context, systemFormationCache))
            val playerScore = candidate.effectiveUserScore
            val playerWeight = optimizer.scoringConfig.playerWeight
            LabyrinthRoleCandidateRanking(
                characterId = candidateId,
                displayName = candidate.displayName,
                score = if (playerScore == null) {
                    placement.systemScore
                } else {
                    playerScore * playerWeight + placement.systemScore * (1.0 - playerWeight)
                },
                playerScore = playerScore,
                systemScore = placement.systemScore,
                candidateLayer = placement.candidateLayer,
                firstTeam = placement.teams.first(),
                candidateTeam = placement.candidateTeam,
                secondTeam = placement.teams.getOrNull(1),
                thirdTeam = placement.teams.getOrNull(2),
            )
        }.sortedWith(
            compareByDescending<LabyrinthRoleCandidateRanking> { it.score }
                .thenByDescending { profiles.getValue(it.characterId).effectiveUserScore.b5r1OrZero() }
                .thenBy { candidateIds.indexOf(it.characterId) },
        )
        val best = rankings.first()
        val chosen = profiles.getValue(best.characterId)
        val reasons = buildList {
            val conflicted = candidateIds.filter(acquiredCharacterIds::contains)
            if (conflicted.isNotEmpty()) {
                add("画面候选与本地已获得名单冲突（${conflicted.joinToString()}）；按实际三选一画面重新评分")
            }
            add("${chosen.displayName}的最佳部署落点为第${best.candidateLayer + 1}队；第一队保持当前池最优组合")
            chosen.attribute?.let { attribute ->
                val sameAttribute = best.candidateTeam.attributeCounts[attribute]
                val bonus = best.candidateTeam.activeAttributeBonuses[attribute]
                if (bonus != null && sameAttribute != null) {
                    add("与队内${attribute.label}属性组成${sameAttribute}人档，角色自身及同属性角色获得${(bonus * 100).toInt()}%伤害")
                }
            }
            addAll(best.candidateTeam.reasons.filterNot { it.startsWith("预计") }.take(3))
            val runnerUp = rankings.getOrNull(1)
            if (runnerUp != null) {
                add("综合边际评分${b5r1FormatScore(best.score)}，高于${runnerUp.displayName}的${b5r1FormatScore(runnerUp.score)}")
            }
        }
        return LabyrinthOneRoleDecision.Ready(chosen, rankings, reasons)
    }

    private fun bestCandidatePlacementPlan(
        pool: List<LabyrinthRoleProfile>,
        candidate: LabyrinthRoleProfile,
        context: LabyrinthRoleDecisionContext,
        systemFormationCache: MutableMap<SystemFormationKey, LabyrinthTeamEvaluation?>,
    ): CandidatePlacementPlan? {
        val plans = buildList {
            config.effectiveCandidateTeamWeights().indices.forEach { candidateLayer ->
                if (pool.distinctBy(LabyrinthRoleProfile::characterId).size - 1 < candidateLayer * TEAM_SIZE) {
                    return@forEach
                }
                candidatePlacementPlan(pool, candidate, context, candidateLayer, systemFormationCache)?.let(::add)
            }
        }
        return plans.maxWithOrNull(
            compareBy<CandidatePlacementPlan> { it.systemScore }
                .thenBy { -it.candidateLayer },
        )
    }

    private fun candidatePlacementPlan(
        pool: List<LabyrinthRoleProfile>,
        candidate: LabyrinthRoleProfile,
        context: LabyrinthRoleDecisionContext,
        candidateLayer: Int,
        systemFormationCache: MutableMap<SystemFormationKey, LabyrinthTeamEvaluation?>,
    ): CandidatePlacementPlan? {
        var remaining = pool.distinctBy(LabyrinthRoleProfile::characterId)
        val teams = mutableListOf<LabyrinthTeamEvaluation>()
        config.effectiveCandidateTeamWeights().indices.forEach { layer ->
            if (layer > 0 && remaining.size < TEAM_SIZE) return@forEach
            val teamPool: List<LabyrinthRoleProfile>
            val requiredId: String?
            when {
                layer < candidateLayer -> {
                    teamPool = remaining.filterNot { it.characterId == candidate.characterId }
                    if (teamPool.size < TEAM_SIZE) return null
                    requiredId = null
                }
                layer == candidateLayer -> {
                    if (remaining.none { it.characterId == candidate.characterId }) return null
                    teamPool = remaining
                    requiredId = candidate.characterId
                }
                else -> {
                    teamPool = remaining
                    requiredId = null
                }
            }
            // Snapshot the ordered pool, not sorted ids: stable enumeration breaks score ties.
            val searchKey = SystemFormationKey(teamPool.toList(), requiredId)
            val team = (if (systemFormationCache.containsKey(searchKey)) {
                systemFormationCache[searchKey]
            } else {
                optimizer.bestSystemFormation(teamPool, context, requiredId).also {
                    // containsKey above also reuses a cached null (no valid formation).
                    systemFormationCache[searchKey] = it
                }
            }) ?: return null
            teams += team
            val used = team.members.map(LabyrinthRoleProfile::characterId).toSet()
            remaining = remaining.filterNot { it.characterId in used }
        }
        val candidateTeam = teams.getOrNull(candidateLayer)
            ?.takeIf { team -> team.members.any { it.characterId == candidate.characterId } }
            ?: return null
        val weights = config.effectiveCandidateTeamWeights()
        val weightSum = teams.indices.sumOf { weights[it] }
        val systemScore = teams.indices.sumOf { teams[it].systemScore * weights[it] } / weightSum
        return CandidatePlacementPlan(candidateLayer, teams, candidateTeam, systemScore)
    }

    private data class SystemFormationKey(
        val orderedPool: List<LabyrinthRoleProfile>,
        val requiredId: String?,
    )

    private data class CandidatePlacementPlan(
        val candidateLayer: Int,
        val teams: List<LabyrinthTeamEvaluation>,
        val candidateTeam: LabyrinthTeamEvaluation,
        val systemScore: Double,
    )

    private companion object {
        const val TEAM_SIZE = 5

        /**
         * Distinct-vanguard first teams tried when the beam holds no disjoint set. Each seed costs
         * one bounded search per follow-up team, so this stays small enough for on-device planning.
         */
        const val MAX_RESIDUAL_FIRST_TEAM_SEEDS = 6
    }
}


private fun LabyrinthTeamDamageType.b5r1Label(): String = when (this) {
    LabyrinthTeamDamageType.PHYSICAL -> "物理"
    LabyrinthTeamDamageType.MAGIC -> "法术"
    LabyrinthTeamDamageType.MIXED -> "混合"
    LabyrinthTeamDamageType.NONE -> "无有效"
}


private fun Double?.b5r1OrZero(): Double = this ?: 0.0


private fun b5r1FormatScore(value: Double): String = "%.2f".format(value)
