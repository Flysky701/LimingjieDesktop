package com.landosol.toolbox.labyrinth

import java.util.IdentityHashMap
import kotlin.math.max

/**
 * Numeric-only counterpart of LabyrinthTeamScorer.evaluateForSearch. One instance belongs to one
 * fixed config/context and the identity union of all pools searched by that operation. In particular,
 * a replacement profile with the same characterId is NOT an already prepared member.
 *
 * Prepared data is read-only after construction. Each search shard (including a nested helping
 * search) must own its own Scratch; neither thread-local nor global mutable state is used here.
 * Keep arithmetic, member accumulation order and primitive attribute comparisons in step with the
 * original scorer. The original remains the authoritative full/explained evaluation path.
 */
internal class LabyrinthPreparedSearchScorer(
    private val config: LabyrinthTeamScoringConfig,
    private val context: LabyrinthRoleDecisionContext,
    roles: Collection<LabyrinthRoleProfile>,
) {
    private val targetCount = context.targetCount.coerceAtMost(3)
    private val attributeSlotCount = LabyrinthCharacterAttribute.values().size
    private val attributeBonuses = DoubleArray(6) { if (it == 0) 0.0 else config.attributeDamageBonus.getValue(it) }
    private val strategy = context.encounterStrategy
    private val preferredCapabilities = strategy?.preferredCapabilities?.toList().orEmpty()
    private val preferredTargets = IntArray(preferredCapabilities.size) {
        strategy?.preferredCapabilityMemberTargets?.get(preferredCapabilities[it]) ?: 1
    }
    private val fallbacks = strategy?.fallbackPreferences?.toList().orEmpty()
    private val requirements = strategy?.requirements?.toList().orEmpty()
    private val prepared = IdentityHashMap<LabyrinthRoleProfile, PreparedRole>(roles.size)

    init {
        // Do not retain the caller's mutable collection, de-duplicate by id, or use data-class keys.
        for (role in roles) {
            com.landosol.toolbox.labyrinth.concurrent.LabyrinthFourThreadRuntime.checkpoint()
            if (!prepared.containsKey(role)) prepared[role] = prepare(role)
        }
    }

    internal class PreparedRole(
        val role: LabyrinthRoleProfile,
        val physicalDamage: Double,
        val magicDamage: Double,
        val physicalUplift: Double,
        val magicUplift: Double,
        val multiTarget: Double,
        val frontline: Double,
        val sustain: Double,
        val strictPhysical: Boolean,
        val strictMagic: Boolean,
        val effective: Boolean,
        val purePenalty: Double,
        val compositeAny: Boolean,
        val compositePhysical: Boolean,
        val compositeMagic: Boolean,
        val preferredScores: DoubleArray,
        val fallbackPrimaryMatches: BooleanArray,
        val fallbackScores: DoubleArray,
        val requirementMatches: BooleanArray,
    )

    class Scratch internal constructor(internal val owner: LabyrinthPreparedSearchScorer) {
        var score: Double = 0.0
        var systemScore: Double = 0.0
        var damageType: LabyrinthTeamDamageType = LabyrinthTeamDamageType.NONE

        internal val team = arrayOfNulls<PreparedRole>(5)
        internal val attributeCount = IntArray(owner.attributeSlotCount)
        internal val attributeGroupDamage = DoubleArray(owner.attributeSlotCount)
        internal val attributeFirstSeen = IntArray(owner.attributeSlotCount)
        internal val chosenSlots = IntArray(owner.config.maximumActiveAttributeBonuses)
        internal val chosenBonuses = DoubleArray(owner.config.maximumActiveAttributeBonuses)
        internal val chosenIncremental = DoubleArray(owner.config.maximumActiveAttributeBonuses)
        internal val activeBonuses = DoubleArray(owner.attributeSlotCount)
        internal val capabilityScores = DoubleArray(5)
    }

    fun newScratch(): Scratch = Scratch(this)

    internal fun requireCompatible(config: LabyrinthTeamScoringConfig, context: LabyrinthRoleDecisionContext) {
        require(this.config == config && this.context == context) { "Prepared search config/context mismatch" }
    }

    fun score(members: List<LabyrinthRoleProfile>, scratch: Scratch) {
        require(scratch.owner === this) { "Scratch belongs to another prepared search" }
        require(members.isNotEmpty() && members.size <= 5)
        val size = members.size
        val team = scratch.team
        team.fill(null)
        val attributeCount = scratch.attributeCount
        val attributeGroupDamage = scratch.attributeGroupDamage
        val attributeFirstSeen = scratch.attributeFirstSeen
        val chosenSlots = scratch.chosenSlots
        val chosenBonuses = scratch.chosenBonuses
        val chosenIncremental = scratch.chosenIncremental
        val activeBonuses = scratch.activeBonuses
        attributeCount.fill(0)
        attributeGroupDamage.fill(0.0)
        attributeFirstSeen.fill(0)
        chosenSlots.fill(0)
        chosenBonuses.fill(0.0)
        chosenIncremental.fill(0.0)
        activeBonuses.fill(0.0)
        scratch.capabilityScores.fill(0.0)
        var attributeSeenCount = 0
        for (index in 0 until size) {
            val member = members[index]
            val entry = requireNotNull(prepared[member]) { "Team member was not prepared by profile identity" }
            team[index] = entry
            val slot = (member.attribute ?: continue).ordinal
            if (attributeCount[slot] == 0) attributeFirstSeen[attributeSeenCount++] = slot
            attributeCount[slot]++
            attributeGroupDamage[slot] += entry.physicalDamage + entry.magicDamage
        }

        // Deliberately NOT Double.compareTo: baseline uses primitive >= and < even for NaN.
        val maximumBonuses = config.maximumActiveAttributeBonuses
        var chosenCount = 0
        for (index in 0 until attributeSeenCount) {
            val slot = attributeFirstSeen[index]
            val bonus = attributeBonuses[attributeCount[slot]]
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
        for (index in 0 until chosenCount) activeBonuses[chosenSlots[index]] = chosenBonuses[index]

        var physicalBase = 0.0
        for (index in 0 until size) {
            val entry = team[index]!!
            val attribute = entry.role.attribute
            physicalBase += entry.physicalDamage * (1.0 + if (attribute == null) 0.0 else activeBonuses[attribute.ordinal])
        }
        var magicBase = 0.0
        for (index in 0 until size) {
            val entry = team[index]!!
            val attribute = entry.role.attribute
            magicBase += entry.magicDamage * (1.0 + if (attribute == null) 0.0 else activeBonuses[attribute.ordinal])
        }
        var physicalFactor = 1.0
        for (index in 0 until size) physicalFactor *= 1.0 + team[index]!!.physicalUplift
        val physicalUplift = (physicalFactor - 1.0).coerceIn(config.combinedTeamUpliftFloor, config.combinedTeamUpliftCap)
        var magicFactor = 1.0
        for (index in 0 until size) magicFactor *= 1.0 + team[index]!!.magicUplift
        val magicUplift = (magicFactor - 1.0).coerceIn(config.combinedTeamUpliftFloor, config.combinedTeamUpliftCap)
        val physicalDamage = physicalBase * (1.0 + physicalUplift)
        val magicDamage = magicBase * (1.0 + magicUplift)
        val totalDamage = physicalDamage + magicDamage
        val physicalRatio = if (totalDamage <= 0.0) 0.0 else physicalDamage / totalDamage
        var hasPhysical = false
        var hasMagic = false
        var vanguard = team[0]!!
        var effectiveCharacterCount = 0
        for (index in 0 until size) {
            val entry = team[index]!!
            if (entry.strictPhysical) hasPhysical = true
            if (entry.strictMagic) hasMagic = true
            if ((entry.role.position ?: Int.MAX_VALUE) < (vanguard.role.position ?: Int.MAX_VALUE)) vanguard = entry
            if (entry.effective) effectiveCharacterCount++
        }
        val damageType = when {
            hasPhysical && hasMagic -> LabyrinthTeamDamageType.MIXED
            hasPhysical -> LabyrinthTeamDamageType.PHYSICAL
            hasMagic -> LabyrinthTeamDamageType.MAGIC
            else -> LabyrinthTeamDamageType.NONE
        }
        val effectiveCharacterSystemBonus = if (
            effectiveCharacterCount > 0 && (strategy?.preferEffectiveCharacters ?: true)
        ) {
            effectiveCharacterCount * config.encounterEffectiveCharacterBonus / size
        } else {
            0.0
        }
        var preferredCapabilityContribution = 0.0
        for (capabilityIndex in preferredCapabilities.indices) {
            var count = 0
            for (index in 0 until size) {
                val value = team[index]!!.preferredScores[capabilityIndex]
                if (value >= 50.0) count = insertCapability(scratch.capabilityScores, count, value)
            }
            var contribution = 0.0
            for (index in 0 until minOf(count, preferredTargets[capabilityIndex])) {
                contribution += scratch.capabilityScores[index] / 100.0
            }
            preferredCapabilityContribution += contribution
        }
        var fallbackCapabilityContribution = 0.0
        for (fallbackIndex in fallbacks.indices) {
            val fallback = fallbacks[fallbackIndex]
            var primaryCount = 0
            for (index in 0 until size) if (team[index]!!.fallbackPrimaryMatches[fallbackIndex]) primaryCount++
            val contribution = if (primaryCount >= fallback.primaryTargetCount) {
                0.0
            } else {
                var count = 0
                for (index in 0 until size) {
                    val value = team[index]!!.fallbackScores[fallbackIndex]
                    if (value >= 50.0) count = insertCapability(scratch.capabilityScores, count, value)
                }
                var sum = 0.0
                for (index in 0 until minOf(count, fallback.maximumCount)) sum += scratch.capabilityScores[index] / 100.0
                sum
            }
            fallbackCapabilityContribution += contribution
        }
        var requirementContribution = 0.0
        for (requirementIndex in requirements.indices) {
            var matched = 0
            for (index in 0 until size) if (team[index]!!.requirementMatches[requirementIndex]) matched++
            val minimumCount = requirements[requirementIndex].minimumCount
            requirementContribution += minOf(matched, minimumCount).toDouble() / minimumCount
        }
        val preferredCapabilityBonus =
            (preferredCapabilityContribution + fallbackCapabilityContribution + requirementContribution) *
                config.encounterPreferredCapabilityBonus
        val encounterBonus = preferredCapabilityBonus
        val frontlineScore = vanguard.frontline
        val frontlineScoreForTeam = if (
            context.optimizeBossVanguardSynergy && frontlineScore > config.minimumReliableVanguard
        ) {
            config.minimumReliableVanguard +
                (frontlineScore - config.minimumReliableVanguard) * config.bossExcessVanguardSurvivalRetention
        } else {
            frontlineScore
        }
        val first = team[0]!!
        var sustainScore = first.sustain
        var physicalReduction = first.role.functions.physicalAttackReduction.orZero()
        var magicReduction = first.role.functions.magicAttackReduction.orZero()
        var healing = max(first.role.functions.healing.orZero(), first.role.functions.regeneration.orZero())
        var aoe = first.role.functions.aoeDamage.orZero()
        var singleTarget = first.role.functions.singleTargetDamage.orZero()
        var multiTarget = first.multiTarget
        var bossValue = first.role.functions.bossMechanismValue.orZero()
        var physicalDefenseDown = first.role.functions.physicalDefenseDown.orZero()
        var magicDefenseDown = first.role.functions.magicDefenseDown.orZero()
        var universalSurvival = 0.0
        for (index in 0 until size) {
            val entry = team[index]!!
            val role = entry.role
            // maxBy uses Comparable ordering; the maxOf reductions below use Math.max semantics.
            if (sustainScore.compareTo(entry.sustain) < 0) sustainScore = entry.sustain
            universalSurvival += role.support.universalSurvival.orZero()
            if (index == 0) continue
            physicalReduction = max(physicalReduction, role.functions.physicalAttackReduction.orZero())
            magicReduction = max(magicReduction, role.functions.magicAttackReduction.orZero())
            healing = max(healing, max(role.functions.healing.orZero(), role.functions.regeneration.orZero()))
            aoe = max(aoe, role.functions.aoeDamage.orZero())
            singleTarget = max(singleTarget, role.functions.singleTargetDamage.orZero())
            multiTarget = max(multiTarget, entry.multiTarget)
            bossValue = max(bossValue, role.functions.bossMechanismValue.orZero())
            physicalDefenseDown = max(physicalDefenseDown, role.functions.physicalDefenseDown.orZero())
            magicDefenseDown = max(magicDefenseDown, role.functions.magicDefenseDown.orZero())
        }
        val hasReliableFrontline = frontlineScore >= config.minimumReliableVanguard
        val hasEffectiveSustain = sustainScore >= config.minimumEffectiveSustain
        val relevantReduction = when (context.effectiveEnemyDamageType) {
            LabyrinthEnemyDamageType.PHYSICAL -> physicalReduction
            LabyrinthEnemyDamageType.MAGIC -> magicReduction
            LabyrinthEnemyDamageType.MIXED -> (physicalReduction + magicReduction) / 2.0
            LabyrinthEnemyDamageType.UNKNOWN -> max(physicalReduction, magicReduction)
        }
        val survivalSupport = (universalSurvival / size + healing + relevantReduction) / 3.0
        val survivalScore = frontlineScoreForTeam * 0.70 + survivalSupport * 0.30
        val matchingDefenseDown = when (damageType) {
            LabyrinthTeamDamageType.PHYSICAL -> physicalDefenseDown
            LabyrinthTeamDamageType.MAGIC -> magicDefenseDown
            LabyrinthTeamDamageType.MIXED -> (physicalDefenseDown + magicDefenseDown) / 2.0
            LabyrinthTeamDamageType.NONE -> 0.0
        }
        val scenarioFunction = if (targetCount == 1) singleTarget else max(aoe, multiTarget)
        val functionScore = (scenarioFunction + matchingDefenseDown + bossValue) / 3.0
        var missingCount = 0
        if (!hasReliableFrontline) missingCount++
        if (context.defenseMarkStacks < config.defenseBaselineStacks && !hasEffectiveSustain) missingCount++
        if (targetCount >= 2 && multiTarget < config.minimumAoe) missingCount++
        if (targetCount == 1 && singleTarget < config.minimumSingleTarget) missingCount++
        if (matchingDefenseDown < config.minimumDefenseDown) missingCount++

        var ratedCount = 0
        var ratedSum = 0.0
        for (index in 0 until size) {
            val rating = team[index]!!.role.effectiveUserScore ?: continue
            ratedSum += rating
            ratedCount++
        }
        val playerScore = if (ratedCount == 0) 0.0 else ratedSum / ratedCount
        val playerCoverage = ratedCount.toDouble() / size
        val damageScore = totalDamage / size
        var pureRolePenalty = 0.0
        if (context.defenseMarkStacks >= config.defenseBaselineStacks) {
            for (index in 0 until size) pureRolePenalty += team[index]!!.purePenalty
        }
        var pureTankCount = 0
        var pureHealerCount = 0
        for (index in 0 until size) {
            val entry = team[index]!!
            val physical = damageType == LabyrinthTeamDamageType.PHYSICAL || damageType == LabyrinthTeamDamageType.MIXED
            val magic = damageType == LabyrinthTeamDamageType.MAGIC || damageType == LabyrinthTeamDamageType.MIXED
            if (entry.compositeAny || (physical && entry.compositePhysical) || (magic && entry.compositeMagic)) continue
            val tank = entry.role.functions.pureTank.orZero()
            val healer = entry.role.functions.pureHealer.orZero()
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
                missingCount * config.formationMissingFunctionPenalty - pureRolePenalty
            ).coerceIn(0.0, 100.0)
        val mixedDamagePenalty = if (context.preferSingleDamageSystem && damageType == LabyrinthTeamDamageType.MIXED) {
            config.battleMixedDamagePenalty
        } else {
            0.0
        }
        val rawSystemScore = (systemDamage * config.systemDamageWeight +
            systemSurvival * config.systemSurvivalWeight +
            systemFunction * config.systemFunctionWeight +
            systemFormation * config.systemFormationWeight +
            systemCohesion * config.systemCohesionWeight +
            effectiveCharacterSystemBonus).coerceIn(0.0, 100.0)
        val systemScore = max(0.0, rawSystemScore - duplicatePenalty - mixedDamagePenalty + encounterBonus)
        val effectivePlayerWeight = if (ratedCount == 0) 0.0 else config.playerWeight * playerCoverage
        val effectiveSystemWeight = 1.0 - effectivePlayerWeight
        val preRiskScore = playerScore * effectivePlayerWeight + rawSystemScore * effectiveSystemWeight
        val riskAdjustedScore = max(
            0.0,
            preRiskScore * survivalMultiplier - survivalGatePenalty * config.riskGatePenaltyWeight,
        )
        scratch.score = max(0.0, riskAdjustedScore - duplicatePenalty - mixedDamagePenalty + encounterBonus)
        scratch.systemScore = systemScore
        scratch.damageType = damageType
    }

    private fun prepare(role: LabyrinthRoleProfile): PreparedRole {
        val physical2 = scenarioDamage(role, LabyrinthRoleDamageType.PHYSICAL, 2)
        val magic2 = scenarioDamage(role, LabyrinthRoleDamageType.MAGIC, 2)
        val physical3 = scenarioDamage(role, LabyrinthRoleDamageType.PHYSICAL, 3)
        val magic3 = scenarioDamage(role, LabyrinthRoleDamageType.MAGIC, 3)
        val physicalDamage = when (targetCount) {
            2 -> physical2
            3 -> physical3
            else -> scenarioDamage(role, LabyrinthRoleDamageType.PHYSICAL, targetCount)
        }
        val magicDamage = when (targetCount) {
            2 -> magic2
            3 -> magic3
            else -> scenarioDamage(role, LabyrinthRoleDamageType.MAGIC, targetCount)
        }
        val universal = isUniversalDamageSystemRole(role)
        val bossSpecialist = role.functions.bossMechanismValue.orZero() >= config.bossSpecialistThreshold ||
            role.functions.selfSustain.orZero() >= config.bossSpecialistThreshold
        val pure = role.functions.pureTank.orZero() >= config.pureRoleThreshold ||
            role.functions.pureHealer.orZero() >= config.pureRoleThreshold
        val threshold = config.compositeOffenseThreshold
        // Reuse the capability and requirement implementations rather than maintaining a second
        // interpretation of skill facts. Their temporary collections exist only at preparation.
        val capabilityValues = if (preferredCapabilities.isEmpty() && fallbacks.isEmpty()) emptyList()
            else LabyrinthEncounterCapability.values().map { role.encounterCapabilityScore(it) }
        val singleMember = listOf(role)
        return PreparedRole(
            role = role,
            physicalDamage = physicalDamage,
            magicDamage = magicDamage,
            physicalUplift = roleTeamUplift(role, LabyrinthRoleDamageType.PHYSICAL),
            magicUplift = roleTeamUplift(role, LabyrinthRoleDamageType.MAGIC),
            multiTarget = if (targetCount >= 3) role.functions.threeTargetDamage ?: (physical3 + magic3)
                else role.functions.twoTargetDamage ?: (physical2 + magic2),
            frontline = role.labyrinthVanguardStrength(context),
            sustain = maxOf(role.functions.healing.orZero(), role.functions.regeneration.orZero(),
                role.support.universalSurvival.orZero() * 0.85),
            strictPhysical = !universal && role.damageType == LabyrinthRoleDamageType.PHYSICAL,
            strictMagic = !universal && role.damageType == LabyrinthRoleDamageType.MAGIC,
            effective = canonicalLabyrinthRoleId(role.characterId) in context.effectiveCharacterIds,
            purePenalty = if (pure && !bossSpecialist) config.postFourDefensePureRolePenalty else 0.0,
            compositeAny = physicalDamage + magicDamage >= threshold || role.support.universalOffense.orZero() >= threshold,
            compositePhysical = role.support.physicalOffense.orZero() >= threshold || role.functions.physicalDefenseDown.orZero() >= threshold,
            compositeMagic = role.support.magicOffense.orZero() >= threshold || role.functions.magicDefenseDown.orZero() >= threshold,
            preferredScores = DoubleArray(preferredCapabilities.size) { capabilityValues[preferredCapabilities[it].ordinal] ?: 0.0 },
            fallbackPrimaryMatches = BooleanArray(fallbacks.size) {
                (capabilityValues[fallbacks[it].primaryCapability.ordinal] ?: -1.0) >= 50.0
            },
            fallbackScores = DoubleArray(fallbacks.size) { index ->
                fallbacks[index].anyOf.maxOfOrNull { capability -> capabilityValues[capability.ordinal] ?: 0.0 } ?: 0.0
            },
            requirementMatches = BooleanArray(requirements.size) {
                evaluateEncounterRequirement(singleMember, requirements[it]).matchedCount > 0
            },
        )
    }

    /** Stable sortedDescending order, unlike the primitive attribute insertion comparisons. */
    private fun insertCapability(values: DoubleArray, count: Int, value: Double): Int {
        var position = count
        while (position > 0 && values[position - 1].compareTo(value) < 0) {
            values[position] = values[position - 1]
            position--
        }
        values[position] = value
        return count + 1
    }

    private fun isUniversalDamageSystemRole(role: LabyrinthRoleProfile): Boolean {
        val roleClass = role.roleClass.orEmpty()
        val physicalUplift = role.physicalTeamUplift ?: 0.0
        val magicUplift = role.magicTeamUplift ?: 0.0
        val crossSystemOffense = minOf(physicalUplift, magicUplift) >= 0.08 ||
            role.support.universalOffense.orZero() >= 35.0
        val universalSurvival = role.support.universalSurvival.orZero() >= 35.0
        val healer = roleClass == "治疗者" && max(role.functions.healing.orZero(), role.functions.regeneration.orZero()) >= 25.0
        val vanguard = roleClass == "掩护者" && (role.functions.reliableVanguard.orZero() >= 45.0 || universalSurvival)
        val universalBuffer = roleClass == "增益者" && crossSystemOffense
        return healer || vanguard || universalBuffer
    }

    private fun scenarioDamage(role: LabyrinthRoleProfile, system: LabyrinthRoleDamageType?, targetCount: Int): Double {
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
            LabyrinthRoleDamageType.PHYSICAL -> role.physicalDamagePotential.orZero()
            LabyrinthRoleDamageType.MAGIC -> role.magicDamagePotential.orZero()
            LabyrinthRoleDamageType.MIXED -> role.physicalDamagePotential.orZero() + role.magicDamagePotential.orZero()
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
            return modeled.coerceIn(config.modeledRoleNegativeUpliftFloor, config.modeledRolePositiveUpliftCap)
        }
        val specific = when (system) {
            LabyrinthRoleDamageType.PHYSICAL -> role.support.physicalOffense.orZero()
            LabyrinthRoleDamageType.MAGIC -> role.support.magicOffense.orZero()
            else -> 0.0
        }
        return ((specific + role.support.universalOffense.orZero()) / 100.0 * config.supportMultiplierAt100)
            .coerceIn(0.0, config.legacyRoleUpliftCap)
    }

    private fun Double?.orZero(): Double = this ?: 0.0
}
