package com.landosol.toolbox.labyrinth.concurrent

import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean

/** One atomic cancellation source for a root and every synchronous child submitted by it. */
class WorkToken {
    private val cancelled = AtomicBoolean(false)
    private val lock = Any()
    private val listeners = LinkedHashSet<() -> Unit>()
    val isCancelled: Boolean get() = cancelled.get()

    fun cancel(): Boolean {
        if (!cancelled.compareAndSet(false, true)) return false
        val callbacks = synchronized(lock) { listeners.toList().also { listeners.clear() } }
        callbacks.forEach { it() }
        return true
    }

    fun checkpoint() {
        if (isCancelled) throw CancellationException("Labyrinth root cancelled")
    }

    internal fun listen(callback: () -> Unit): () -> Unit {
        val callNow = synchronized(lock) {
            if (isCancelled) true else { listeners.add(callback); false }
        }
        if (callNow) callback()
        return { synchronized(lock) { listeners.remove(callback) }; Unit }
    }
}

enum class WorkKind(internal val priority: Int) {
    DETAILED_VISION(0), OBSERVATION(0), PLANNING(1), DIAGNOSTIC(2),
}

/** Counts actual task exits, NOT Future.done (which is early for a running cancellation). */
internal class WorkScope(val token: WorkToken) {
    private val lock = Any()
    private var bodies = 0
    private var quiesced = false
    private val callbacks = ArrayList<() -> Unit>()
    fun retain() = synchronized(lock) { check(!quiesced); bodies++; Unit }
    fun release() {
        val ready = synchronized(lock) {
            check(bodies > 0)
            bodies--
            if (bodies == 0) {
                quiesced = true
                callbacks.toList().also { callbacks.clear() }
            } else emptyList()
        }
        ready.forEach { callback -> runCatching(callback) }
    }
    fun onQuiesced(callback: () -> Unit) {
        val now = synchronized(lock) {
            if (quiesced) true else { callbacks.add(callback); false }
        }
        if (now) runCatching(callback)
    }
}
