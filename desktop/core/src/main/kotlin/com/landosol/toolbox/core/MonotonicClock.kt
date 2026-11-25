package com.landosol.toolbox.core

object MonotonicClock {
    fun elapsedRealtime(): Long = System.nanoTime() / 1_000_000
    fun uptimeMillis(): Long = elapsedRealtime()
}
