package com.landosol.toolbox.labyrinth.kernel.event

import com.landosol.toolbox.gamedata.EventResource
import java.io.File
import java.util.concurrent.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
enum class EventPackKind { CATALOG, POLICY, BUNDLE }

@Serializable
data class EventPackManifest(
    val packId: String,
    val version: String = "1.0.0",
    val kind: EventPackKind = EventPackKind.BUNDLE,
    val order: Int = 100,
    val enabled: Boolean = true,
    val name: String = packId,
    val description: String = "",
)

@Serializable
data class EventPackDocument(
    val schemaVersion: Int = 1,
    val manifest: EventPackManifest,
    val overlay: GameEventOverlayDocument = GameEventOverlayDocument(),
    val rules: List<GameEventRule> = emptyList(),
    val unknownEventPolicy: UnknownGameEventPolicy? = null,
) {
    init {
        require(schemaVersion == 1) { "Unsupported event pack schemaVersion" }
        require(manifest.packId.isNotBlank() && manifest.version.isNotBlank()) { "Missing packId or version" }
        require(rules.all { it.id.isNotBlank() && !it.id.startsWith("builtin/") }) { "Blank or reserved rule id" }
        require(rules.distinctBy { it.id }.size == rules.size) { "Duplicate rule id" }
        when (manifest.kind) {
            EventPackKind.CATALOG -> require(rules.isEmpty() && unknownEventPolicy == null) { "CATALOG contains policy" }
            EventPackKind.POLICY -> require(overlay.events.isEmpty() && overlay.aliases.isEmpty()) { "POLICY contains catalog" }
            EventPackKind.BUNDLE -> Unit
        }
    }
}

object EventPackCodec {
    private val compact = Json { encodeDefaults = true }
    private val pretty = Json(compact) { prettyPrint = true }
    fun encode(document: EventPackDocument, prettyPrint: Boolean = false): String =
        (if (prettyPrint) pretty else compact).encodeToString(document)
    fun decode(text: String): EventPackDocument = compact.decodeFromString(text)
}

data class CompiledEventPackSet(
    val extensionPack: KernelEventExtensionPack = KernelEventExtensionPack(),
    val loadedManifests: List<EventPackManifest> = emptyList(),
    val ruleProvenance: Map<String, String> = emptyMap(),
    val quarantinedErrors: Map<String, String> = emptyMap(),
) {
    // Providers may retain mutable collections, including nested rule conditions and choice effects.
    internal fun detachedCopy() = copy(
        extensionPack = KernelEventExtensionPackCodec.decode(KernelEventExtensionPackCodec.encode(extensionPack)),
        loadedManifests = loadedManifests.toList(), ruleProvenance = ruleProvenance.toMap(),
        quarantinedErrors = quarantinedErrors.toMap(),
    )
}

class EventPackRepository(private val directory: File, private val baseEvents: (() -> List<EventResource>)? = null) {
    fun loadCompiled(): CompiledEventPackSet {
        if (!directory.exists()) return CompiledEventPackSet()
        val files = requireNotNull(directory.listFiles()) { "Cannot list event pack directory: " + directory }
        val errors = linkedMapOf<String, String>()
        val candidates = files.filter { it.isFile && it.extension == "json" }.mapNotNull { file ->
            try {
                EventPackCodec.decode(file.readBytes().decodeToString(throwOnInvalidSequence = true))
                    .takeIf { it.manifest.enabled }?.let { file.name to it }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                errors[file.name] = failure.message ?: failure.javaClass.simpleName
                null
            }
        }.sortedWith(compareBy({ it.second.manifest.order }, { it.second.manifest.packId }, { it.first }))

        val manifests = linkedMapOf<String, EventPackManifest>()
        val events = linkedMapOf<String, GameEventDefinition>()
        val aliases = linkedMapOf<String, String>()
        val rules = mutableListOf<GameEventRule>()
        val provenance = linkedMapOf<String, String>()
        var policy = UnknownGameEventPolicy.WAIT
        val catalog by lazy { baseEvents?.invoke()?.let { GameEventCatalogRegistry(it) } }
        for ((fileName, pack) in candidates) {
            val manifest = pack.manifest
            val conflict = when {
                manifest.packId in manifests -> "Duplicate packId: " + manifest.packId
                else -> pack.rules.firstOrNull { it.id in provenance }?.let { "Duplicate rule: " + it.id }
                    ?: pack.overlay.events.firstOrNull { it.id in events }?.let { "Duplicate event: " + it.id }
                    ?: pack.overlay.aliases.keys.firstOrNull { it in aliases }?.let { "Duplicate alias: " + it }
            }
            if (conflict != null) {
                errors[fileName] = conflict
                continue
            }
            if (pack.overlay.events.isNotEmpty() || pack.overlay.aliases.isNotEmpty()) {
                val registry = catalog
                try {
                    registry?.addOverlay(pack.overlay)
                } catch (invalid: IllegalArgumentException) {
                    errors[fileName] = invalid.message ?: "Invalid event overlay"
                    continue
                }
            }
            manifests[manifest.packId] = manifest
            pack.overlay.events.associateByTo(events) { it.id }
            aliases.putAll(pack.overlay.aliases)
            rules.addAll(pack.rules)
            for (rule in pack.rules) provenance[rule.id] = "${manifest.packId}@${manifest.version}#${rule.id}"
            pack.unknownEventPolicy?.let { policy = it }
        }
        return CompiledEventPackSet(KernelEventExtensionPack(
            overlay = GameEventOverlayDocument(events = events.values.toList(), aliases = aliases),
            rules = rules, unknownEventPolicy = policy,
        ), manifests.values.toList(), provenance, errors)
    }
}

object BuiltinEventPackPresets {
    private fun select(event: String, choice: String) = GameEventRule(
        "select/" + choice, 200, GameEventCondition.EventIdIn(setOf(event)), GameEventRuleAction.SelectByChoiceId(choice),
    )

    private fun arena(name: String) = GameEventRule("arena/difficulty", 200,
        GameEventCondition.EventFamilyIn(setOf("arena")), GameEventRuleAction.SelectByChoiceName(name))

    fun dailySafePolicyPack() = EventPackDocument(
        manifest = EventPackManifest("daily-safe", kind = EventPackKind.POLICY, name = "日常稳健"),
        rules = listOf(select("1107", "11073"), select("2315", "23152"), select("3315", "33152"),
            select("2316", "23162"), select("3316", "33162"), arena("一试身手吧！")),
        unknownEventPolicy = UnknownGameEventPolicy.WAIT,
    )

    fun highscoreGreedyPolicyPack() = EventPackDocument(
        manifest = EventPackManifest("highscore-greedy", kind = EventPackKind.POLICY, name = "高分追求"),
        rules = listOf(
            GameEventRule("slate/relic", 210, GameEventCondition.AllOf(listOf(
                GameEventCondition.EventIdIn(setOf("1107")), GameEventCondition.AlphaCoinRange(min = 2500),
                GameEventCondition.ChoiceEnabled("11071"),
            )), GameEventRuleAction.SelectByChoiceId("11071")),
            select("1107", "11073"), select("2315", "23151"), select("3315", "33151"),
            arena("与强敌战斗真是令人兴奋！"),
        ),
        unknownEventPolicy = UnknownGameEventPolicy.SAFE_PARSE_OR_WAIT,
    )
}
