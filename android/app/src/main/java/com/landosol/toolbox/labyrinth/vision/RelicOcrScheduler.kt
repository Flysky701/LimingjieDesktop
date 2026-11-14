package com.landosol.toolbox.labyrinth.vision

/** Two independent reads per unchanged slot, then reuse. Invalidating a page does not release
 * the physical request: a slow engine must not accumulate abandoned crops across transitions. */
internal class RelicOcrScheduler(private val clock: () -> Long, private val spacingMillis: Long = 350,
    private val maximumAttempts: Int = 2) {
    /**
     * @param confirmations how many completed reads of these exact pixels produced this text.
     *
     * Callers that must not act on a single look read this instead of counting reads themselves.
     * It belongs here because the count has to survive the caller leaving and re-entering the
     * page: a cached read is the result of the same physical work, and a per-visit counter cannot
     * tell that apart from a first look.
     */
    data class Read(val text: String?, val id: Long, val confirmations: Int = 1, val capturedAtMillis: Long = Long.MIN_VALUE)
    data class Ticket(val slot: String, val fingerprint: Long, val epoch: Long, val id: Long, val capturedAtMillis: Long = Long.MIN_VALUE)
    private data class Slot(
        val fingerprint: Long,
        var attempts: Int = 0,
        var read: Read? = null,
        var touchedAt: Long = Long.MIN_VALUE,
        var rearmed: Boolean = false,
    )
    private val slots = mutableMapOf<String, Slot>()
    private var epoch = 0L
    private var sequence = 0L
    private var pending: Ticket? = null
    private var nextStart = Long.MIN_VALUE
    private var lastCompletionAt = Long.MIN_VALUE

    /**
     * When the engine last answered anything, for any slot.
     *
     * Callers that must distinguish "my read is queued behind other slots" from "the engine has
     * died" read this instead of timing their own slot: the queue is serial, so a slot can wait
     * several reads for its turn while the engine is perfectly healthy.
     */
    @Synchronized fun lastCompletionAt(): Long = lastCompletionAt

    @Synchronized fun clear() { slots.clear(); epoch++ }

    @Synchronized fun cached(slot: String, fingerprint: Long): Read? {
        val state = slots[slot]
        if (state?.fingerprint == fingerprint) {
            state.touchedAt = clock()
            return state.read
        }
        slots[slot] = Slot(fingerprint, touchedAt = clock())
        return null
    }

    @Synchronized fun request(slot: String, fingerprint: Long, capturedAtMillis: Long = Long.MIN_VALUE): Ticket? {
        cached(slot, fingerprint)
        val state = slots.getValue(slot)
        if (pending != null || state.attempts >= maximumAttempts || clock() < nextStart) return null
        // A slot that has never been read goes first, so one crop cannot verify itself twice while
        // another waits for its only look. That priority lasts only while the other slot is still
        // being asked for. A caller that stops asking used to hold it forever: the EX challenge
        // page creates a single-target name slot on its first frames, and once the structural
        // detector recognises a multi-monster encounter that branch never runs again. The
        // monster-detail slot then read the name once, could never obtain the second independent
        // read it needs to be trusted, and the run reported a known encounter as having no
        // strategy on file (2026-09-19: 流夏的暗影 read correctly, reported as unmatched).
        val now = clock()
        if (
            state.attempts > 0 &&
            slots.any { (name, other) ->
                name != slot && other.attempts == 0 && now - other.touchedAt <= ABANDONED_SLOT_MILLIS
            }
        ) return null
        state.attempts++
        nextStart = now + spacingMillis
        return Ticket(slot, fingerprint, epoch, ++sequence, capturedAtMillis).also { pending = it }
    }

    @Synchronized fun complete(ticket: Ticket, text: String?) {
        // Recorded before any early return: the callback firing at all proves the engine is alive,
        // even when this particular result is stale and gets dropped below.
        lastCompletionAt = clock()
        if (pending != ticket) return
        pending = null
        if (ticket.epoch != epoch) return
        val state = slots[ticket.slot]?.takeIf { it.fingerprint == ticket.fingerprint } ?: return
        val trimmed = text?.trim()
        val previous = state.read
        if (previous != null && previous.text == trimmed) {
            state.read = Read(trimmed, ticket.id, previous.confirmations + 1, ticket.capturedAtMillis)
            return
        }
        state.read = Read(trimmed, ticket.id, capturedAtMillis = ticket.capturedAtMillis)
        // Two looks at the same pixels disagreed, so neither is confirmed and the budget is spent.
        // Buy exactly one more read rather than leaving a caller that waits for agreement waiting
        // for something that can no longer arrive.
        if (previous != null && !state.rearmed) {
            state.rearmed = true
            state.attempts = maximumAttempts - 1
        }
    }

    private companion object {
        /**
         * How long an unread slot keeps its priority after the last time it was asked for.
         *
         * Long enough that a slot requested once per frame always keeps it, short enough that a
         * slot whose page has moved on stops blocking every other slot.
         */
        const val ABANDONED_SLOT_MILLIS = 1_500L
    }
}
