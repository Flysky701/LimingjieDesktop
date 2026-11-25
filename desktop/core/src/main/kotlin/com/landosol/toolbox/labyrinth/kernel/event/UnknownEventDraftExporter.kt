package com.landosol.toolbox.labyrinth.kernel.event

import com.landosol.toolbox.labyrinth.kernel.KernelEventKinds
import com.landosol.toolbox.labyrinth.kernel.KernelEventLog
import com.landosol.toolbox.labyrinth.kernel.UnknownGameEventObservedPayload
import com.landosol.toolbox.labyrinth.vision.normalizeEventText
import java.text.Normalizer
import java.util.UUID

object UnknownEventDraftExporter {
    private class Draft(val title: String) {
        val descriptions = sortedMapOf<Int, String>()
        val titles = linkedSetOf(title)
        var rawDescription = ""
    }

    fun exportCatalogDraft(log: KernelEventLog, packId: String = "draft-unknown-events"): EventPackDocument {
        val drafts = linkedMapOf<String, Draft>()
        for (event in log.filter(kind = KernelEventKinds.EVENT_UNKNOWN_OBSERVED)) {
            val payload = event.payload as? UnknownGameEventObservedPayload ?: continue
            val lines = payload.rawTextExcerpt.trim().lines()
            val title = lines.first().trim()
            if (title.isEmpty()) continue
            val key = normalizeEventText(Normalizer.normalize(title, Normalizer.Form.NFKC)).ifEmpty { title }
            val draft = drafts.getOrPut(key) { Draft(title) }
            draft.titles.add(title)
            val body = lines.drop(1).joinToString("\n").trim()
            if (body.length > draft.rawDescription.length) draft.rawDescription = body
            payload.choiceTextExcerpts.entries.forEachIndexed { index, (choiceId, excerpt) ->
                val slot = choiceId.substringAfterLast('/').toIntOrNull()?.takeIf { it > 0 } ?: (index + 1)
                val description = excerpt.trim()
                if (slot !in draft.descriptions || description.length > draft.descriptions.getValue(slot).length) {
                    draft.descriptions[slot] = description
                }
            }
        }
        val aliases = linkedMapOf<String, String>()
        val events = drafts.map { (key, draft) ->
            val id = "draft/" + UUID.nameUUIDFromBytes(key.toByteArray(Charsets.UTF_8))
            draft.titles.forEach { aliases[it] = id }
            val descriptions = draft.descriptions.values.toList().ifEmpty { listOf(draft.rawDescription) }
            val choices = descriptions.mapIndexed { index, description ->
                val slot = index + 1
                GameEventChoiceSpec("$id/$slot", slot, "选项$slot", description,
                    effects = GameEventEffectParser.parse(description).primitives)
            }
            GameEventDefinition(id, draft.title, choices = choices)
        }
        return EventPackDocument(manifest = EventPackManifest(packId, kind = EventPackKind.CATALOG),
            overlay = GameEventOverlayDocument(events = events, aliases = aliases))
    }

    fun exportCatalogDraftJson(log: KernelEventLog, packId: String = "draft-unknown-events",
                               prettyPrint: Boolean = true): String =
        EventPackCodec.encode(exportCatalogDraft(log, packId), prettyPrint)
}
