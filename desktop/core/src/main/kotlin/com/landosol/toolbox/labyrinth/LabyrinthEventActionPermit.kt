package com.landosol.toolbox.labyrinth

import com.landosol.toolbox.labyrinth.vision.EntryPixelRect
import java.util.concurrent.atomic.AtomicLong

/** T2-only immutable identity for a currently confirmed, revalidated catalog event target. */
internal data class LabyrinthEventActionKey(
    val session: Any, val generation: Long, val sceneEpoch: Long,
    val eventId: String, val choiceId: String, val buttonRect: EntryPixelRect,
)

/** Revokes queued/native-dispatch actions without cancelling the same semantic calculation. */
internal class LabyrinthEventActionPermit {
    private val revision = AtomicLong()
    private var key: LabyrinthEventActionKey? = null
    fun update(current: LabyrinthEventActionKey?) {
        if (key != current) { key = current; revision.incrementAndGet() }
    }
    fun clear() = update(null)
    fun capture(expected: LabyrinthEventActionKey, capturedAtMillis: Long): Token? =
        if (expected == key && capturedAtMillis >= 0L) Token(revision, revision.get(), capturedAtMillis) else null

    /** Only an atomic and a scalar cross into Android Main; no Session, OCR or pixels. */
    class Token internal constructor(
        private val revision: AtomicLong, private val expected: Long, private val capturedAtMillis: Long,
    ) {
        fun isCurrent(nowMillis: Long = System.currentTimeMillis()): Boolean = revision.get() == expected &&
            nowMillis >= capturedAtMillis && nowMillis - capturedAtMillis <= LabyrinthBalancedEvidence.MAX_ACTION_EVIDENCE_AGE_MILLIS
    }
}
