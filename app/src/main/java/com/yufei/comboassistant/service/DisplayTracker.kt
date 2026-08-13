package com.yufei.comboassistant.service

import com.yufei.comboassistant.domain.DisplaySnapshot

/**
 * Keeps configuration-transition samples from being treated as a stable display.
 * The service owns the 100 ms/300 ms scheduling and passes the returned generation
 * back with each sample so stale callbacks cannot restore an old orientation.
 */
class DisplayTracker(initial: DisplaySnapshot? = null) {
    private var generation: Long = 0L
    private var intermediateSample: DisplaySnapshot? = null

    var state: DisplayState = initial?.let(DisplayState::Stable) ?: DisplayState.Unstable(null)
        private set

    fun markUnstable(): Long {
        generation += 1L
        val previous = when (val current = state) {
            is DisplayState.Stable -> current.snapshot
            is DisplayState.Unstable -> current.previous
        }
        state = DisplayState.Unstable(previous)
        intermediateSample = null
        return generation
    }

    fun recordIntermediate(token: Long, snapshot: DisplaySnapshot): Boolean {
        if (token != generation) return false
        intermediateSample = snapshot
        state = DisplayState.Unstable(snapshot)
        return true
    }

    fun recordStable(token: Long, snapshot: DisplaySnapshot): Boolean {
        if (token != generation) return false
        if (intermediateSample != snapshot) {
            generation += 1L
            intermediateSample = null
            state = DisplayState.Unstable(snapshot)
            return false
        }
        state = DisplayState.Stable(snapshot)
        intermediateSample = null
        return true
    }

    fun initialize(snapshot: DisplaySnapshot) {
        state = DisplayState.Stable(snapshot)
        intermediateSample = null
    }
}
