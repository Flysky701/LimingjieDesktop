package com.landosol.toolbox.labyrinth.kernel.event

import com.landosol.toolbox.gamedata.EventChoiceResource
import com.landosol.toolbox.gamedata.EventResource
import kotlinx.serialization.Serializable

@Serializable enum class GameEventCatalogLayer { BUILTIN, RESOURCE_PACK, USER_OVERLAY, DYNAMIC_OCR_DISCOVERED }

@Serializable data class GameEventChoiceSpec(
    val id: String, val slot: Int? = null, val name: String? = null, val description: String? = null,
    val effects: List<GameEventEffectPrimitive> = emptyList(), val conditionType: Int? = null,
    val conditionValue: Int? = null,
)

@Serializable data class GameEventDefinition(
    val id: String, val name: String? = null, val area: Int? = null, val familyId: String? = null,
    val tags: Set<String> = emptySet(), val choices: List<GameEventChoiceSpec> = emptyList(),
)

@Serializable data class GameEventOverlayDocument(
    val schemaVersion: Int = 1, val events: List<GameEventDefinition> = emptyList(),
    val aliases: Map<String, String> = emptyMap(),
) {
    init {
        require(schemaVersion == 1 && events.distinctBy { it.id }.size == events.size)
        require(events.all { it.id.isNotBlank() && it.choices.distinctBy { choice -> choice.id }.size == it.choices.size })
        require(aliases.all { it.key.isNotBlank() && it.value.isNotBlank() })
    }
}

class GameEventCatalogRegistry(events: List<EventResource> = emptyList(), baseLayer: GameEventCatalogLayer = GameEventCatalogLayer.BUILTIN) {
    @Volatile var revision: Long = 0
        private set
    private var resources = events.associateBy { it.id }
    private var definitions = emptyMap<String, GameEventDefinition>()
    private var aliases = emptyMap<String, String>()
    private var layers = events.associate { it.id to baseLayer }

    @Synchronized fun addOverlay(document: GameEventOverlayDocument, layer: GameEventCatalogLayer = GameEventCatalogLayer.USER_OVERLAY) {
        val next = resources.toMutableMap()
        val metadata = definitions.toMutableMap()
        for (patch in document.events) {
            val base = next[patch.id] ?: EventResource(id = patch.id)
            val choices = base.choices.associateBy { it.id }.toMutableMap()
            val previous = metadata[patch.id]
            val specs = previous?.choices.orEmpty().associateBy { it.id }.toMutableMap()
            for (spec in patch.choices) {
                val choice = choices[spec.id] ?: EventChoiceResource(id = spec.id)
                choices[spec.id] = choice.copy(slot = spec.slot ?: choice.slot, name = spec.name ?: choice.name,
                    description = spec.description ?: choice.description, conditionType = spec.conditionType ?: choice.conditionType,
                    conditionValue = spec.conditionValue ?: choice.conditionValue)
                specs[spec.id] = spec.copy(effects = spec.effects.ifEmpty { specs[spec.id]?.effects.orEmpty() })
            }
            val merged = base.copy(name = patch.name ?: base.name, area = patch.area ?: base.area,
                choices = choices.values.sortedBy { it.slot })
            require(merged.name.isNotBlank() && merged.choices.isNotEmpty() &&
                merged.choices.map { it.slot } == (1..merged.choices.size).toList() &&
                merged.choices.all { it.name.isNotBlank() && it.id.isNotBlank() }) { "事件目录或按钮槽位无效: " + patch.id }
            next[patch.id] = merged
            metadata[patch.id] = patch.copy(name = merged.name, area = merged.area, familyId = patch.familyId ?: previous?.familyId,
                tags = previous?.tags.orEmpty() + patch.tags, choices = specs.values.toList())
        }
        require(document.aliases.values.all(next::containsKey)) { "事件别名缺少目标" }
        resources = next
        definitions = metadata
        layers = layers + document.events.associate { it.id to layer }
        aliases = aliases + document.aliases
        revision++
    }

    @Synchronized fun toEventResources() = resources.values.toList()
    @Synchronized fun byId(id: String) = resources[id]
    @Synchronized fun byName(name: String) = resources.values.filter { it.name == name }
    @Synchronized fun byFamily(id: String) = resources.values.filter { familyId(it.id) == id }
    @Synchronized fun familyId(id: String) = definitions[id]?.familyId ?: builtinFamily(id)
    @Synchronized fun layer(id: String) = layers[id]
    @Synchronized fun tags(id: String) = definitions[id]?.tags.orEmpty()
    @Synchronized fun effects(eventId: String, choiceId: String) =
        definitions[eventId]?.choices?.find { it.id == choiceId }?.effects.orEmpty()
    @Synchronized fun resolveRecognitionAlias(alias: String) = resources[aliases[alias]]
    @Synchronized fun aliasesFor(eventId: String) = aliases.filterValues { it == eventId }.keys

    companion object {
        fun builtinFamily(id: String) = when (id) {
            "arena", "1105", "1109", "1113", "1117" -> "arena"
            "1101", "1102", "1103", "1104" -> "guild-recruit"
            else -> id
        }
    }
}
