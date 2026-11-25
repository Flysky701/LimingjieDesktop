package com.landosol.toolbox.labyrinth.kernel.event

import com.landosol.toolbox.labyrinth.LabyrinthRelicMark
import com.landosol.toolbox.labyrinth.LabyrinthStrategySettings
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

data class GameEventContext(
    val eventId: String, val familyId: String = GameEventCatalogRegistry.builtinFamily(eventId),
    val name: String = "", val area: Int = 0, val rawOcr: String = "", val catalogMatched: Boolean = true,
    val acquiredRoleIds: Set<String> = emptySet(), val relicStacks: Map<LabyrinthRelicMark, Int> = emptyMap(),
    val defenseStacks: Int = 0, val targetCount: Int = 1, val alphaCoin: Int? = null, val optionCount: Int = 0,
    val enabledChoiceIds: Set<String> = emptySet(), val rejectedChoiceIds: Set<String> = emptySet(),
    val tags: Set<String> = emptySet(),
)

@Serializable sealed interface GameEventCondition {
    fun matches(c: GameEventContext): Boolean
    @Serializable @SerialName("EventIdIn") data class EventIdIn(val ids: Set<String>) : GameEventCondition {
        override fun matches(c: GameEventContext) = c.eventId in ids
    }
    @Serializable @SerialName("EventFamilyIn") data class EventFamilyIn(val ids: Set<String>) : GameEventCondition {
        override fun matches(c: GameEventContext) = c.familyId in ids
    }
    @Serializable @SerialName("EventNameEquals") data class EventNameEquals(val name: String) : GameEventCondition {
        override fun matches(c: GameEventContext) = c.name == name
    }
    @Serializable @SerialName("TitleRegex") data class TitleRegex(val pattern: String) : GameEventCondition {
        private val regex by lazy { Regex(pattern) }
        init { Regex(pattern) }
        override fun matches(c: GameEventContext) = regex.containsMatchIn(c.name)
    }
    @Serializable @SerialName("OcrTextRegex") data class OcrTextRegex(val pattern: String) : GameEventCondition {
        private val regex by lazy { Regex(pattern) }
        init { Regex(pattern) }
        override fun matches(c: GameEventContext) = regex.containsMatchIn(c.rawOcr)
    }
    @Serializable @SerialName("AreaIn") data class AreaIn(val areas: Set<Int>) : GameEventCondition {
        override fun matches(c: GameEventContext) = c.area in areas
    }
    @Serializable @SerialName("OptionCountEquals") data class OptionCountEquals(val count: Int) : GameEventCondition {
        override fun matches(c: GameEventContext) = c.optionCount == count
    }
    @Serializable @SerialName("AcquiredRoleContains") data class AcquiredRoleContains(val roleId: String) : GameEventCondition {
        override fun matches(c: GameEventContext) = roleId in c.acquiredRoleIds
    }
    @Serializable @SerialName("AcquiredRoleCountRange") data class AcquiredRoleCountRange(val min: Int = 0, val max: Int = Int.MAX_VALUE) : GameEventCondition {
        override fun matches(c: GameEventContext) = c.acquiredRoleIds.size in min..max
    }
    @Serializable @SerialName("RelicMarkStackRange") data class RelicMarkStackRange(val mark: LabyrinthRelicMark, val min: Int = 0, val max: Int = Int.MAX_VALUE) : GameEventCondition {
        override fun matches(c: GameEventContext) = (c.relicStacks[mark] ?: 0) in min..max
    }
    @Serializable @SerialName("AlphaCoinRange") data class AlphaCoinRange(val min: Int = 0, val max: Int = Int.MAX_VALUE) : GameEventCondition {
        override fun matches(c: GameEventContext) = c.alphaCoin?.let { it in min..max } == true
    }
    @Serializable @SerialName("ChoiceEnabled") data class ChoiceEnabled(val choiceId: String) : GameEventCondition {
        override fun matches(c: GameEventContext) = choiceId in c.enabledChoiceIds && choiceId !in c.rejectedChoiceIds
    }
    @Serializable @SerialName("AllOf") data class AllOf(val conditions: List<GameEventCondition>) : GameEventCondition {
        override fun matches(c: GameEventContext) = conditions.all { it.matches(c) }
    }
    @Serializable @SerialName("AnyOf") data class AnyOf(val conditions: List<GameEventCondition>) : GameEventCondition {
        override fun matches(c: GameEventContext) = conditions.any { it.matches(c) }
    }
    @Serializable @SerialName("Not") data class Not(val condition: GameEventCondition) : GameEventCondition {
        override fun matches(c: GameEventContext) = !condition.matches(c)
    }
}

@Serializable sealed interface GameEventRuleAction {
    @Serializable @SerialName("SelectByChoiceId") data class SelectByChoiceId(val choiceId: String) : GameEventRuleAction
    @Serializable @SerialName("SelectByChoiceName") data class SelectByChoiceName(val name: String) : GameEventRuleAction
    @Serializable @SerialName("SelectBySlot") data class SelectBySlot(val slot: Int) : GameEventRuleAction
    @Serializable @SerialName("SelectByChoiceRegex") data class SelectByChoiceRegex(val pattern: String) : GameEventRuleAction { init { Regex(pattern) } }
    @Serializable @SerialName("UseUtilityModel") data object UseUtilityModel : GameEventRuleAction
    @Serializable @SerialName("WaitWithReason") data class WaitWithReason(val reason: String) : GameEventRuleAction
}

@Serializable data class GameEventRule(val id: String, val priority: Int = 0, val condition: GameEventCondition, val action: GameEventRuleAction)
@Serializable data class KernelEventExtensionPack(
    val schemaVersion: Int = 1, val overlay: GameEventOverlayDocument = GameEventOverlayDocument(),
    val rules: List<GameEventRule> = emptyList(), val unknownEventPolicy: UnknownGameEventPolicy = UnknownGameEventPolicy.WAIT,
) {
    init {
        require(schemaVersion == 1)
        require(rules.distinctBy { it.id }.size == rules.size && rules.all { it.id.isNotBlank() && !it.id.startsWith("builtin/") })
    }
}

object KernelEventExtensionPackCodec {
    private val json = Json { encodeDefaults = true }
    fun encode(pack: KernelEventExtensionPack) = json.encodeToString(pack)
    fun decode(text: String) = json.decodeFromString<KernelEventExtensionPack>(text)
}

data class GameEventRuleEvaluation(val rule: GameEventRule?, val examinedRuleIds: List<String>) {
    val action get() = rule?.action ?: GameEventRuleAction.UseUtilityModel
}

class GameEventRuleEngine(rules: List<GameEventRule>) {
    private val ordered = rules.sortedWith(compareByDescending<GameEventRule> { it.priority }.thenBy { it.id })
    fun evaluate(context: GameEventContext): GameEventRuleEvaluation {
        val examined = mutableListOf<String>()
        val rule = ordered.firstOrNull {
            examined += it.id
            it.condition.matches(context)
        }
        return GameEventRuleEvaluation(rule, examined)
    }

    companion object {
        fun buildDefaultRules(strategy: LabyrinthStrategySettings): List<GameEventRule> = buildList {
            for ((id, choice) in mapOf("1107" to "11073", "1101" to "11012", "1102" to "11021", "1103" to "11031", "1104" to "11042")) {
                add(GameEventRule("builtin/fixed/" + id, 100, GameEventCondition.EventIdIn(setOf(id)), GameEventRuleAction.SelectByChoiceId(choice)))
            }
            strategy.effectiveEventChoiceOverrides().forEach { (name, choice) ->
                add(GameEventRule("builtin/saved/" + name, 50, GameEventCondition.EventNameEquals(name), GameEventRuleAction.SelectByChoiceName(choice)))
            }
        }
    }
}
