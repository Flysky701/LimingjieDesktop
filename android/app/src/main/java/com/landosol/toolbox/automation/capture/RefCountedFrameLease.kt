package com.landosol.toolbox.automation.capture

/**
 * One independently closeable handle to a shared, exclusively-owned value.
 *
 * Each reader must retain its own handle before publication and keep it open until actual work
 * quiesces (not merely until Future.cancel/isDone). Pending replacement closes only its handle.
 * [value] is unavailable after this handle closes, even when other handles remain alive.
 * Do not race close of a handle against its own value's use; retain a separate reader handle.
 * No Android dependency, pooling, implicit task ownership, or global frame storage.
 */
class RefCountedFrameLease<T : Any> private constructor(
    private val shared: Shared<T>,
) : AutoCloseable {
    private class Shared<T : Any>(value: T, disposer: (T) -> Unit) {
        var value: T? = value
        var disposer: ((T) -> Unit)? = disposer
        var references = 1
    }

    // Both per-handle and shared state use the same monitor; retain vs close is linearizable.
    private var closed = false

    val isClosed: Boolean
        get() = synchronized(shared) { closed }

    val value: T
        get() = synchronized(shared) {
            check(!closed) { "Frame lease handle is closed" }
            checkNotNull(shared.value) { "Frame lease value is released" }
        }

    fun retain(): RefCountedFrameLease<T> = synchronized(shared) {
        check(!closed) { "Cannot retain a closed frame lease handle" }
        checkNotNull(shared.value) { "Frame lease value is released" }
        check(shared.references < Int.MAX_VALUE) { "Too many frame lease handles" }
        shared.references++
        RefCountedFrameLease(shared)
    }

    override fun close() {
        val release = synchronized(shared) {
            if (closed) return
            closed = true
            shared.references--
            if (shared.references != 0) return@synchronized null
            val value = checkNotNull(shared.value)
            val disposer = checkNotNull(shared.disposer)
            // Clear references before invoking user code, including a disposer that throws.
            shared.value = null
            shared.disposer = null
            value to disposer
        }
        // Never invoke application disposal while holding the monitor.
        release?.let { (value, disposer) -> disposer(value) }
    }

    companion object {
        fun <T : Any> create(value: T, disposer: (T) -> Unit): RefCountedFrameLease<T> =
            RefCountedFrameLease(Shared(value, disposer))
    }
}
