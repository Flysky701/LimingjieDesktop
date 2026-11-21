package com.landosol.toolbox.labyrinth.kernel

import java.util.concurrent.atomic.AtomicLong

class KernelActionPermit<K : Any>(
    private val maxAgeMillis: Long = 1_200L,
    private val onTransition: ((issued: Boolean, key: K?, revision: Long, reason: String) -> Unit)? = null,
) {
    init { require(maxAgeMillis >= 0) }
    private val revision = AtomicLong()
    val currentRevision: Long get() = revision.get()
    private var key: K? = null
    private var capturedAt = -1L

    @Synchronized fun issue(current: K, capturedAtMillis: Long, reason: String = "confirmed"): Token {
        require(capturedAtMillis >= 0)
        if (key != current) {
            revoke("target_changed")
            key = current
            onTransition?.invoke(true, current, revision.incrementAndGet(), reason)
        }
        capturedAt = capturedAtMillis
        return Token(revision, revision.get(), capturedAt, maxAgeMillis)
    }

    @Synchronized fun capture(expected: K, now: Long): Token? =
        if (key == expected) Token(revision, revision.get(), capturedAt, maxAgeMillis).takeIf { it.isCurrent(now) } else null

    @Synchronized fun revoke(reason: String = "invalidated") {
        val previous = key ?: return
        key = null
        capturedAt = -1
        onTransition?.invoke(false, previous, revision.incrementAndGet(), reason)
    }

    class Token internal constructor(private val revision: AtomicLong, private val expected: Long,
                                    private val capturedAt: Long, private val maxAge: Long) {
        fun isCurrent(nowMillis: Long = System.currentTimeMillis()) =
            revision.get() == expected && nowMillis >= capturedAt && nowMillis - capturedAt <= maxAge
    }
}
