package com.landosol.toolbox.labyrinth

import com.landosol.toolbox.labyrinth.vision.LabyrinthEntryPageState
import com.landosol.toolbox.labyrinth.vision.LabyrinthRelicMatch

/** Two distinct captured frames must agree on a visible reward before a tap can use its identity. */
internal class LabyrinthRelicIdentityGate(private val allowSpecial: Boolean = false) {
    private data class Seen(val identity: String, val count: Int)
    private val slots = mutableMapOf<String, Seen>()
    private var page: LabyrinthEntryPageState? = null
    private var frameAt = Long.MIN_VALUE
    private var pageStartedAt = Long.MIN_VALUE

    fun clear() { slots.clear(); page = null; frameAt = Long.MIN_VALUE; pageStartedAt = Long.MIN_VALUE }

    fun observe(currentPage: LabyrinthEntryPageState, frameTimestamp: Long, relics: List<LabyrinthRelicMatch>) {
        if (currentPage != page) {
            slots.clear(); page = currentPage; frameAt = Long.MIN_VALUE; pageStartedAt = frameTimestamp
        }
        if (frameTimestamp <= frameAt) return
        frameAt = frameTimestamp
        val visible = relics.map { it.slotId }.toSet()
        slots.keys.retainAll(visible)
        relics.forEach { relic ->
            val identity = identity(relic)
            if (identity == null) slots.remove(relic.slotId) else {
                val previous = slots[relic.slotId]
                slots[relic.slotId] = Seen(identity, if (previous?.identity == identity) previous.count + 1 else 1)
            }
        }
    }

    fun confirmed(relic: LabyrinthRelicMatch): Boolean = slots[relic.slotId]?.let {
        it.identity == identity(relic) && it.count >= 2
    } == true

    fun calibrationGraceOpen(nowMillis: Long): Boolean =
        pageStartedAt != Long.MIN_VALUE && nowMillis - pageStartedAt < 6_000L

    private fun identity(relic: LabyrinthRelicMatch): String? {
        if (!relic.recognized || relic.displayName.isNullOrBlank()) return null
        val mark = LabyrinthRelicMark.fromLabel(relic.attribute)
        val bonus = relic.attributeBonus?.trim()?.toIntOrNull()
        if (allowSpecial && relic.attribute == "特殊" && bonus == 0) return "${relic.relicId}:SPECIAL"
        if (mark == null || bonus == null || bonus <= 0) return null
        return "${relic.relicId}:${mark.name}:$bonus"
    }
}
