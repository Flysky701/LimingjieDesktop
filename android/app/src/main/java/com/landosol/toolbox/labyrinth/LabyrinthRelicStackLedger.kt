package com.landosol.toolbox.labyrinth

/** Origin of a recorded stack total. */
enum class LabyrinthRelicStackSource { HISTORY, MAP, CHOICE, DETAIL, ACQUISITION }

data class LabyrinthRelicStackRecord(val stacks: Int, val source: LabyrinthRelicStackSource)

/** A series appears once a reading arrives. A pending reward keeps the absolute total it expects. */
internal class LabyrinthRelicStackLedger {
    private data class VoteKey(val mark: LabyrinthRelicMark, val source: LabyrinthRelicStackSource, val slot: String)
    private data class Vote(val value: Int, val count: Int)
    private data class Acquisition(val mark: LabyrinthRelicMark, val expected: Int?, val estimate: Int)
    private val values = mutableMapOf<LabyrinthRelicMark, LabyrinthRelicStackRecord>()
    private val pending = mutableMapOf<VoteKey, Vote>()
    private val evidence = mutableMapOf<String, Long>()
    private val acquisitions = mutableMapOf<String, Acquisition>()
    private val ownedIds = mutableSetOf<String>()
    private val conflicts = mutableSetOf<LabyrinthRelicMark>()
    private val verifying = mutableMapOf<LabyrinthRelicMark, Long?>()
    private val evidenceAfter = mutableMapOf<LabyrinthRelicMark, Long>()
    val stacks: Map<LabyrinthRelicMark, Int> get() = values.mapValues { it.value.stacks }
    val records: Map<LabyrinthRelicMark, LabyrinthRelicStackRecord> get() = values.toMap()
    val auditMarks: Set<LabyrinthRelicMark> get() = conflicts.toSet()
    val needsAudit: Boolean get() = conflicts.isNotEmpty()
    val verificationMarks: Set<LabyrinthRelicMark> get() = verifying.keys.toSet()
    fun priorityVerificationMarks(nowMillis: Long): Set<LabyrinthRelicMark> = verifying.filterValues {
        it == null || nowMillis - it < MAP_VERIFY_TIMEOUT_MILLIS
    }.keys
    fun unverifiedMarks(nowMillis: Long): Set<LabyrinthRelicMark> = verifying.filterValues {
        it != null && nowMillis - it >= MAP_VERIFY_TIMEOUT_MILLIS
    }.keys - conflicts

    fun clear() {
        values.clear(); pending.clear(); evidence.clear(); acquisitions.clear(); ownedIds.clear()
        conflicts.clear(); verifying.clear(); evidenceAfter.clear()
    }

    fun clearPending() { pending.clear() }
    fun clearPending(mark: LabyrinthRelicMark) { pending.keys.removeAll { it.mark == mark } }

    fun restore(relics: List<LabyrinthObservedRelic>, records: Map<LabyrinthRelicMark, LabyrinthRelicStackRecord>) {
        clear()
        ownedIds += relics.map { it.relicId }
        LabyrinthRelicChoicePolicy().markStacks(relics).filterValues { it > 0 }.forEach { (mark, value) ->
            values[mark] = LabyrinthRelicStackRecord(value.coerceAtMost(30), LabyrinthRelicStackSource.HISTORY)
        }
        values.putAll(records.filterValues { it.stacks in 0..30 })
    }

    /** A frame stages changes privately; only a successful durable commit publishes them. */
    fun fork(): LabyrinthRelicStackLedger = LabyrinthRelicStackLedger().also {
        it.values.putAll(values); it.pending.putAll(pending); it.evidence.putAll(evidence)
        it.acquisitions.putAll(acquisitions); it.ownedIds.addAll(ownedIds); it.conflicts.addAll(conflicts)
        it.verifying.putAll(verifying); it.evidenceAfter.putAll(evidenceAfter)
    }

    fun prepare(relicId: String, mark: LabyrinthRelicMark, bonus: Int, fallbackBefore: Int?): Boolean {
        if (relicId in ownedIds || bonus <= 0) return false
        if (relicId !in acquisitions) {
            val before = values[mark]?.takeUnless { it.source == LabyrinthRelicStackSource.HISTORY }?.stacks ?: fallbackBefore
            acquisitions[relicId] = Acquisition(mark, before?.let { (it + bonus).coerceIn(0, 30) },
                ((values[mark]?.stacks ?: 0) + bonus).coerceIn(0, 30))
            clearPending(mark)
        }
        return true
    }

    fun cancel(relicId: String?) {
        acquisitions.remove(relicId)?.let { clearPending(it.mark) }
    }

    /** A receipt reports the total after acquisition, so it is recorded as absolute. */
    fun received(relicId: String, mark: LabyrinthRelicMark?, bonus: Int, atMillis: Long, knownTotal: Int?): Boolean {
        if (!ownedIds.add(relicId)) return false
        if (mark != null && bonus > 0) {
            val current = values[mark]
            val stacks = knownTotal ?: run {
                // Readings are absolute: they already include this receipt.
                if (current == null || current.source == LabyrinthRelicStackSource.HISTORY) (current?.stacks ?: 0) + bonus
                else current.stacks
            }
            val source = if (knownTotal != null) LabyrinthRelicStackSource.ACQUISITION
                else current?.source?.takeIf { it == LabyrinthRelicStackSource.MAP } ?: LabyrinthRelicStackSource.HISTORY
            values[mark] = LabyrinthRelicStackRecord(stacks.coerceIn(0, 30), source)
            clearPending(mark)
            conflicts.remove(mark)
            verifying[mark] = null
            evidenceAfter[mark] = atMillis
        }
        return true
    }

    fun hasAcquisition(relicId: String): Boolean = relicId in acquisitions

    fun acceptCommitted(records: Map<LabyrinthRelicMark, LabyrinthRelicStackRecord>) {
        values.clear()
        values.putAll(records)
    }

    /** The map must not replace a pre-reward baseline while the same reward is still in flight. */
    fun observe(
        mark: LabyrinthRelicMark,
        value: Int?,
        source: LabyrinthRelicStackSource,
        slot: String,
        evidenceId: Long?,
        capturedAtMillis: Long = Long.MAX_VALUE,
    ) {
        if (evidenceId == null || capturedAtMillis <= (evidenceAfter[mark] ?: Long.MIN_VALUE)) return
        val key = "$source:$slot"
        if (evidence[key]?.let { evidenceId <= it } == true) return
        evidence[key] = evidenceId
        if (acquisitions.values.any { it.mark == mark }) return
        val voteKey = VoteKey(mark, source, slot)
        if (value == null || value !in 0..30) { pending.remove(voteKey); return }
        val previous = pending[voteKey]
        val count = if (previous?.value == value) (previous.count + 1).coerceAtMost(MAP_CONFLICT_CONFIRMATIONS) else 1
        pending[voteKey] = Vote(value, count)
        val existing = values[mark]
        val protected = existing != null && existing.source !in setOf(LabyrinthRelicStackSource.HISTORY, LabyrinthRelicStackSource.MAP)
        if (source == LabyrinthRelicStackSource.MAP && protected && existing?.stacks != value) {
            if (count >= MAP_CONFLICT_CONFIRMATIONS) conflicts += mark
            return
        }
        if (count < CONFIRMATIONS) return
        if (!(source == LabyrinthRelicStackSource.MAP && protected)) {
            values[mark] = LabyrinthRelicStackRecord(value, source)
        }
        clearPending(mark)
        conflicts.remove(mark)
        verifying.remove(mark)
    }

    /** Duplicate reward callbacks are no-ops, including their series increment. */
    fun acquired(relicId: String, atMillis: Long): Boolean {
        if (relicId in ownedIds) { acquisitions.remove(relicId); return false }
        val acquisition = acquisitions.remove(relicId) ?: return false
        ownedIds += relicId
        if (acquisition.expected == null) {
            values[acquisition.mark] = LabyrinthRelicStackRecord(acquisition.estimate, LabyrinthRelicStackSource.HISTORY)
        } else {
            values[acquisition.mark] = LabyrinthRelicStackRecord(acquisition.expected, LabyrinthRelicStackSource.ACQUISITION)
        }
        clearPending(acquisition.mark)
        conflicts.remove(acquisition.mark)
        verifying[acquisition.mark] = null
        evidenceAfter[acquisition.mark] = atMillis
        return true
    }

    /** Pure query for the pre-vision scan gate. It must not start or extend the 8s window. */
    fun peekWaitingForMap(nowMillis: Long): Boolean =
        verifying.values.any { it == null || nowMillis - it < MAP_VERIFY_TIMEOUT_MILLIS }

    /** Start the bounded reread window when the map becomes visible. */
    fun waitingForMap(nowMillis: Long): Boolean {
        verifying.keys.toList().forEach { mark -> if (verifying[mark] == null) verifying[mark] = nowMillis }
        return verifying.values.any { it != null && nowMillis - it < MAP_VERIFY_TIMEOUT_MILLIS }
    }

    fun beginMapReread() { verificationMarks.forEach(::clearPending) }

    companion object {
        const val CONFIRMATIONS = 2
        const val MAP_CONFLICT_CONFIRMATIONS = 3
        const val MAP_VERIFY_TIMEOUT_MILLIS = 8_000L
    }
}
