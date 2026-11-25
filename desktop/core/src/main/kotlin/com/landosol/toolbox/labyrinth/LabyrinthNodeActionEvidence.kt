package com.landosol.toolbox.labyrinth

import java.util.concurrent.atomic.AtomicLong

/** Current node-frame proof. Tiny synchronized snapshots also cover the legacy non-T2 caller. */
internal class LabyrinthNodeActionEvidence {
    private val revision = AtomicLong()
    private var newestCapturedAt: Long? = null
    private var verifiedCapturedAt: Long? = null

    /** Every accepted detail fences queued coordinates, including unverified/early-return frames. */
    @Synchronized
    fun observe(capturedAtMillis: Long, currentFrameVerified: Boolean) {
        revision.incrementAndGet()
        val previous = newestCapturedAt
        val advances = capturedAtMillis >= 0L && (previous == null || capturedAtMillis > previous)
        if (advances) newestCapturedAt = capturedAtMillis
        verifiedCapturedAt = capturedAtMillis.takeIf { advances && currentFrameVerified }
    }

    @Synchronized
    fun isCurrentFrame(capturedAtMillis: Long): Boolean = verifiedCapturedAt == capturedAtMillis

    @Synchronized
    fun invalidate() {
        revision.incrementAndGet()
        verifiedCapturedAt = null
    }

    @Synchronized
    fun reset() {
        invalidate()
        newestCapturedAt = null
    }

    @Synchronized
    fun capture(capturedAtMillis: Long): Token? =
        if (isCurrentFrame(capturedAtMillis)) Token(revision, revision.get(), capturedAtMillis) else null

    /** Only an atomic revision and scalar capture time cross into Android Main. */
    class Token internal constructor(
        private val revision: AtomicLong, private val expected: Long, private val capturedAtMillis: Long,
    ) {
        fun isCurrent(nowMillis: Long = System.currentTimeMillis()): Boolean =
            revision.get() == expected && nowMillis >= capturedAtMillis &&
                nowMillis - capturedAtMillis <= LabyrinthBalancedEvidence.MAX_ACTION_EVIDENCE_AGE_MILLIS
    }
}
